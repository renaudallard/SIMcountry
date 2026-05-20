/*
 * Copyright (c) 2026 Renaud Allard <renaud@allard.it>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGES.
 */

package it.allard.simcountry.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.app.PendingIntent
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import it.allard.simcountry.R
import it.allard.simcountry.SimcountryApp
import it.allard.simcountry.daemon.autorestart.AutostartCoordinator
import it.allard.simcountry.ipc.SimControlSocketClient
import it.allard.simcountry.rules.AspectRules
import it.allard.simcountry.rules.RuleMatcher
import it.allard.simcountry.rules.RulesDoc
import it.allard.simcountry.telephony.CountryWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class CountryWatcherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watcher = CountryWatcher()
    private val callbacks = mutableMapOf<Int, TelephonyCallback>()
    private var tickJob: Job? = null
    private var overrideCheckJob: Job? = null
    private var reconnectJob: Job? = null
    private var subsChangedListener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    private var adbWifiObserver: ContentObserver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingOfflineNotifJob: Job? = null
    private var retryJob: Job? = null
    private val callbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "simcountry-telephony").apply { isDaemon = true }
    }

    private lateinit var app: SimcountryApp
    private lateinit var telephony: TelephonyManager
    private lateinit var subs: SubscriptionManager

    override fun onCreate() {
        super.onCreate()
        app = application as SimcountryApp
        telephony = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        subs = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        ensureNotificationChannel()
        app.container.keyguardGate.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        refreshSubscriptionCallbacks()
        startSubscriptionsChangedListener()
        startTickLoop()
        startDaemonReconnectLoop()
        startAutoReconnectWatchers()
        // If the daemon was already dead before the StateFlow collector
        // attached, no Connected -> Disconnected transition fires the
        // recovery from startDaemonReconnectLoop on its own. Kick it
        // here.
        if (app.container.simControlSocketClient.state.value !is SimControlSocketClient.State.Connected) {
            launchDaemonRecovery("service start")
        }
        if (intent?.action == ACTION_RECONNECT_DAEMON) {
            scope.launch { app.container.autostart.reconnectDaemon() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSubscriptionsChangedListener()
        unregisterAllCallbacks()
        stopAutoReconnectWatchers()
        retryJob?.cancel()
        retryJob = null
        callbackExecutor.shutdown()
        app.container.keyguardGate.stop()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_watcher)
            .setContentTitle(getString(R.string.watcher_notification_title))
            .setContentText(getString(R.string.watcher_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(
            NOTIF_ID,
            n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.watcher_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.watcher_notification_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(DAEMON_STATUS_CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                DAEMON_STATUS_CHANNEL_ID,
                getString(R.string.daemon_status_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.daemon_status_channel_desc)
                setShowBadge(true)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun hasPhoneStatePermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized
    private fun refreshSubscriptionCallbacks() {
        if (!hasPhoneStatePermission()) {
            Log.w(TAG, "missing READ_PHONE_STATE; cannot watch service state")
            return
        }
        val active = try {
            subs.activeSubscriptionInfoList ?: emptyList()
        } catch (se: SecurityException) {
            Log.w(TAG, "no permission for activeSubscriptionInfoList", se)
            emptyList()
        }
        val activeIds = active.map { it.subscriptionId }.toSet()
        val gone = callbacks.keys.filter { it !in activeIds }
        for (subId in gone) {
            val cb = callbacks.remove(subId) ?: continue
            try {
                telephony.createForSubscriptionId(subId).unregisterTelephonyCallback(cb)
            } catch (_: Throwable) {
            }
        }
        for (info in active) {
            val subId = info.subscriptionId
            if (callbacks.containsKey(subId)) continue
            val perSub = telephony.createForSubscriptionId(subId)
            val cb = object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
                override fun onServiceStateChanged(state: ServiceState) {
                    onAnyServiceStateChange()
                }
            }
            try {
                perSub.registerTelephonyCallback(callbackExecutor, cb)
                callbacks[subId] = cb
            } catch (t: Throwable) {
                Log.w(TAG, "registerTelephonyCallback failed for subId=$subId", t)
            }
        }
    }

    @Synchronized
    private fun unregisterAllCallbacks() {
        for ((subId, cb) in callbacks) {
            try {
                telephony.createForSubscriptionId(subId).unregisterTelephonyCallback(cb)
            } catch (_: Throwable) {
            }
        }
        callbacks.clear()
    }

    private fun startSubscriptionsChangedListener() {
        if (subsChangedListener != null) return
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                refreshSubscriptionCallbacks()
            }
        }
        try {
            subs.addOnSubscriptionsChangedListener(callbackExecutor, listener)
            subsChangedListener = listener
        } catch (t: Throwable) {
            Log.w(TAG, "addOnSubscriptionsChangedListener failed", t)
        }
    }

    private fun stopSubscriptionsChangedListener() {
        val listener = subsChangedListener ?: return
        try {
            subs.removeOnSubscriptionsChangedListener(listener)
        } catch (_: Throwable) {
        }
        subsChangedListener = null
    }

    private fun onAnyServiceStateChange() {
        if (!hasPhoneStatePermission()) return
        val dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        val effective = if (dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) dataSubId
        else SubscriptionManager.getDefaultVoiceSubscriptionId()
        if (effective == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            watcher.observe(null)
            return
        }
        val tm = telephony.createForSubscriptionId(effective)
        val operator = tm.networkOperator
        val country = if (operator.length >= 5) {
            CountryWatcher.Country(mcc = operator.substring(0, 3), mnc = operator.substring(3))
        } else if (operator.length >= 3) {
            CountryWatcher.Country(mcc = operator.substring(0, 3), mnc = null)
        } else {
            null
        }
        watcher.observe(country)
    }

    private fun startTickLoop() {
        tickJob?.cancel()
        tickJob = scope.launch {
            app.container.rulesStore.doc.collectLatest { doc ->
                while (isActive) {
                    val policy = CountryWatcher.Policy.fromSeconds(
                        doc.policy.stabilitySec,
                        doc.policy.reverseHysteresisSec,
                        doc.policy.minSwitchIntervalSec,
                    )
                    val settled = watcher.tick(policy)
                    if (settled != null) applyRule(doc, settled)
                    delay(5_000)
                }
            }
        }
    }

    private fun startDaemonReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var wasConnected = false
            app.container.simControlSocketClient.state.collect { st ->
                val isConnected = st is SimControlSocketClient.State.Connected
                if (isConnected) {
                    if (!wasConnected) {
                        val current = watcher.currentSettled
                        if (current != null) {
                            Log.i(TAG, "daemon reconnected; re-applying mcc=${current.mcc}")
                            applyRule(app.container.rulesStore.doc.value, CountryWatcher.Settled(current, null))
                        }
                    }
                    // Daemon is up: stop any retry storm in flight.
                    retryJob?.cancel()
                    retryJob = null
                } else if (wasConnected) {
                    // Daemon just dropped. Try aggressive recovery instead
                    // of waiting for an observer transition that will not
                    // come if Wireless Debugging is already on.
                    launchDaemonRecovery("daemon dropped")
                }
                updateDaemonStatusNotification(isConnected)
                wasConnected = isConnected
            }
        }
    }

    /**
     * Surface a user-visible notification whenever the daemon is offline
     * so the user knows they need to re-enable Wireless Debugging + Wi-Fi
     * (or whatever cleared the daemon) to bring country rules back. The
     * foreground-service notification stays low-priority for the watcher
     * itself; this is a separate channel with default importance so it
     * shows up in the status bar.
     *
     * The publish is deferred by [OFFLINE_NOTIFICATION_GRACE_MS] so that
     * a fast auto-reconnect does not flash a notification at the user.
     * If the daemon comes back during the grace window, we cancel.
     */
    private fun updateDaemonStatusNotification(connected: Boolean) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (connected) {
            pendingOfflineNotifJob?.cancel()
            pendingOfflineNotifJob = null
            nm.cancel(DAEMON_STATUS_NOTIF_ID)
            return
        }
        val paired = app.container.autostart.state.value is AutostartCoordinator.State.Paired
        if (!paired) {
            // Pre-pair we already have a big red banner inside the app;
            // a system notification would be noise.
            pendingOfflineNotifJob?.cancel()
            pendingOfflineNotifJob = null
            nm.cancel(DAEMON_STATUS_NOTIF_ID)
            return
        }
        // Defer; if maybeAutoReconnect (or anything else) brings the
        // daemon back during the grace window the connected branch
        // above cancels this job.
        pendingOfflineNotifJob?.cancel()
        pendingOfflineNotifJob = scope.launch {
            delay(OFFLINE_NOTIFICATION_GRACE_MS)
            val stillDown =
                app.container.simControlSocketClient.state.value !is SimControlSocketClient.State.Connected
            if (stillDown) postOfflineNotification(nm)
        }
    }

    private fun postOfflineNotification(nm: NotificationManager) {
        val devSettingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val devSettingsPi = PendingIntent.getActivity(
            this,
            0,
            devSettingsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, DAEMON_STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_watcher)
            .setContentTitle(getString(R.string.daemon_status_offline_title))
            .setContentText(getString(R.string.daemon_status_offline_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.daemon_status_offline_text)))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(devSettingsPi)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            nm.notify(DAEMON_STATUS_NOTIF_ID, n)
        }
    }

    private fun applyRule(doc: RulesDoc, settled: CountryWatcher.Settled) {
        val mcc = settled.country.mcc
        if (app.container.overrideDetector.isSuppressed(mcc)) {
            Log.i(TAG, "mcc=$mcc is currently suppressed; skipping switch")
            return
        }
        if (app.container.keyguardGate.isLocked()) {
            Log.i(TAG, "keyguard locked; deferring switch for mcc=$mcc")
            return
        }
        val aspects = RuleMatcher.match(doc, mcc, settled.country.mnc) ?: doc.defaults
        if (aspects.isEmpty()) {
            Log.i(TAG, "country=$mcc/${settled.country.mnc}: no rule, no defaults")
            return
        }
        val client = app.container.simControlSocketClient
        if (client.state.value !is SimControlSocketClient.State.Connected) {
            Log.w(TAG, "daemon not connected; cannot apply rule for mcc=$mcc")
            return
        }
        scope.launch {
            apply(client, mcc, aspects, doc.policy.overrideSuppressionSec.toLong() * 1000)
        }
    }

    private suspend fun apply(
        client: SimControlSocketClient,
        mcc: String,
        aspects: AspectRules,
        suppressionMs: Long,
    ) {
        val subByIccid = try {
            client.listSubs().associateBy { it.iccid }
        } catch (t: Throwable) {
            Log.e(TAG, "listSubs failed", t)
            return
        }
        var newData = -1
        var newVoice = -1
        var newSms = -1
        suspend fun applyAspect(
            aspect: SimControlSocketClient.SubAspect,
            iccid: String,
        ): Int? {
            val sub = subByIccid[iccid] ?: run {
                Log.w(TAG, "$aspect rule references iccid $iccid; not in active subs")
                return null
            }
            return runCatching {
                client.setDefaultSubId(aspect, sub.subId)
                sub.subId
            }.onFailure { Log.e(TAG, "setDefaultSubId($aspect, ${sub.subId})", it) }
                .getOrNull()
        }
        aspects.data?.let { ref ->
            applyAspect(SimControlSocketClient.SubAspect.DATA, ref.iccid)?.let { newData = it }
        }
        aspects.voice?.let { ref ->
            applyAspect(SimControlSocketClient.SubAspect.VOICE, ref.iccid)?.let { newVoice = it }
        }
        aspects.sms?.let { ref ->
            applyAspect(SimControlSocketClient.SubAspect.SMS, ref.iccid)?.let { newSms = it }
        }
        app.container.overrideDetector.recordOurSwitch(mcc, newData, newVoice, newSms)
        app.container.simRegistry.refresh()
        overrideCheckJob?.cancel()
        overrideCheckJob = scope.launch {
            delay(15_000)
            val cur = runCatching {
                Triple(
                    client.getDefaultSubId(SimControlSocketClient.SubAspect.DATA),
                    client.getDefaultSubId(SimControlSocketClient.SubAspect.VOICE),
                    client.getDefaultSubId(SimControlSocketClient.SubAspect.SMS),
                )
            }.getOrNull() ?: return@launch
            app.container.overrideDetector.detectAndMaybeSuppress(
                observedMcc = mcc,
                currentData = cur.first,
                currentVoice = cur.second,
                currentSms = cur.third,
                suppressionMs = suppressionMs,
            )
        }
    }

    /**
     * Auto-recovery: a paired device often comes back from a reboot
     * with Wireless Debugging toggled off (Motorola Lhotse / Android 16
     * does this) or with Wi-Fi not yet attached. Either condition
     * blocks the BootReceiver-triggered reconnect from reaching adbd.
     * Once the foreground service is up, watch both signals:
     *   - Settings.Global.adb_wifi_enabled toggling to 1
     *   - any Wi-Fi network becoming available
     * and fire reconnectDaemon when either flips, debounced so a flurry
     * of callbacks does not stampede AdbConnection.
     */
    private fun startAutoReconnectWatchers() {
        val handler = Handler(Looper.getMainLooper())
        val resolver = contentResolver
        val adbUri = Settings.Global.getUriFor(SETTING_ADB_WIFI_ENABLED)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val enabled = runCatching {
                    Settings.Global.getInt(resolver, SETTING_ADB_WIFI_ENABLED, 0)
                }.getOrDefault(0) == 1
                if (enabled) launchDaemonRecovery("$SETTING_ADB_WIFI_ENABLED -> 1")
            }
        }
        runCatching { resolver.registerContentObserver(adbUri, false, observer) }
            .onSuccess { adbWifiObserver = observer }
            .onFailure { Log.w(TAG, "observer register failed", it) }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                launchDaemonRecovery("wifi available")
            }
        }
        runCatching { cm.registerNetworkCallback(req, cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { Log.w(TAG, "network callback register failed", it) }
    }

    private fun stopAutoReconnectWatchers() {
        adbWifiObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        adbWifiObserver = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cb -> cm?.let { runCatching { it.unregisterNetworkCallback(cb) } } }
        networkCallback = null
    }

    /**
     * Drive an aggressive reconnect storm while the daemon is down and
     * Wireless Debugging is enabled: each `reconnectDaemon` attempt can
     * lose to a TCP socket release race or a transient mDNS / TLS
     * stumble, but the eventual launch sticks. Up to [MAX_RETRY_ATTEMPTS]
     * attempts spaced [RETRY_DELAY_MS] apart. If Wireless Debugging is
     * off we cannot dial adbd at all, so we skip the retries and just
     * surface the user-actionable notification.
     *
     * The job is cancelled by [startDaemonReconnectLoop] as soon as the
     * daemon transitions to Connected.
     */
    private fun launchDaemonRecovery(reason: String) {
        val paired = app.container.autostart.state.value is AutostartCoordinator.State.Paired
        if (!paired) return
        if (retryJob?.isActive == true) return
        Log.i(TAG, "daemon recovery starting: $reason")
        retryJob = scope.launch {
            for (attempt in 1..MAX_RETRY_ATTEMPTS) {
                if (!isActive) return@launch
                val connected =
                    app.container.simControlSocketClient.state.value is SimControlSocketClient.State.Connected
                if (connected) {
                    Log.i(TAG, "daemon back after $attempt attempt(s)")
                    return@launch
                }
                if (!wirelessDebuggingEnabled()) {
                    Log.i(TAG, "wireless debugging off; will surface notification, no retries")
                    break
                }
                Log.i(TAG, "auto-reconnect attempt $attempt/$MAX_RETRY_ATTEMPTS")
                app.container.autostart.reconnectDaemon()
                delay(RETRY_DELAY_MS)
            }
            Log.w(TAG, "daemon recovery gave up; surfacing offline notification")
            // Force the deferred notification job to fire immediately
            // instead of waiting for its 15s grace.
            pendingOfflineNotifJob?.cancel()
            pendingOfflineNotifJob = null
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null) postOfflineNotification(nm)
        }
    }

    private fun wirelessDebuggingEnabled(): Boolean = runCatching {
        Settings.Global.getInt(contentResolver, SETTING_ADB_WIFI_ENABLED, 0) == 1
    }.getOrDefault(false)

    companion object {
        private const val TAG = "CountryWatcherService"
        private const val CHANNEL_ID = "watcher"
        private const val NOTIF_ID = 1
        const val ACTION_RECONNECT_DAEMON = "it.allard.simcountry.action.RECONNECT_DAEMON"
        private const val SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val DAEMON_STATUS_CHANNEL_ID = "daemon_status"
        private const val DAEMON_STATUS_NOTIF_ID = 2
        private const val OFFLINE_NOTIFICATION_GRACE_MS = 15_000L
        private const val MAX_RETRY_ATTEMPTS = 20
        private const val RETRY_DELAY_MS = 3_000L

        fun start(context: Context) {
            val i = Intent(context, CountryWatcherService::class.java)
            context.startForegroundService(i)
        }

        fun startWithReconnect(context: Context) {
            val i = Intent(context, CountryWatcherService::class.java).setAction(ACTION_RECONNECT_DAEMON)
            context.startForegroundService(i)
        }
    }
}

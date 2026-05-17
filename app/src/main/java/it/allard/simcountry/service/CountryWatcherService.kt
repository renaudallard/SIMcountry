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
import android.os.IBinder
import android.os.RemoteException
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import it.allard.simcountry.R
import it.allard.simcountry.SimcountryApp
import it.allard.simcountry.ipc.ISimControl
import it.allard.simcountry.ipc.SimControlClient
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
        if (intent?.action == ACTION_RECONNECT_DAEMON) {
            scope.launch { app.container.autostart.reconnectDaemon() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSubscriptionsChangedListener()
        unregisterAllCallbacks()
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
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
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
            app.container.simControlClient.state.collect { st ->
                val isConnected = st is SimControlClient.State.Connected
                if (isConnected && !wasConnected) {
                    val current = watcher.currentSettled
                    if (current != null) {
                        Log.i(TAG, "daemon reconnected; re-applying mcc=${current.mcc}")
                        applyRule(app.container.rulesStore.doc.value, CountryWatcher.Settled(current, null))
                    }
                }
                wasConnected = isConnected
            }
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
        val client = app.container.simControlClient.iface
        if (client == null) {
            Log.w(TAG, "daemon not connected; cannot apply rule for mcc=$mcc")
            return
        }
        scope.launch {
            apply(client, mcc, aspects, doc.policy.overrideSuppressionSec.toLong() * 1000)
        }
    }

    private suspend fun apply(client: ISimControl, mcc: String, aspects: AspectRules, suppressionMs: Long) {
        val subById = try {
            client.listAllSubscriptions().associateBy { it.iccid }
        } catch (e: RemoteException) {
            Log.e(TAG, "listAllSubscriptions failed", e)
            return
        }
        var newData = -1
        var newVoice = -1
        var newSms = -1
        aspects.data?.let { ref ->
            val sub = subById[ref.iccid] ?: return@let
            if (!sub.isActive) tryActivateEsim(client, ref.iccid)
            runCatching {
                client.setDefaultDataSubId(sub.subId)
                newData = sub.subId
            }.onFailure { Log.e(TAG, "setDefaultDataSubId(${sub.subId})", it) }
        }
        aspects.voice?.let { ref ->
            val sub = subById[ref.iccid] ?: return@let
            if (!sub.isActive) tryActivateEsim(client, ref.iccid)
            runCatching {
                client.setDefaultVoiceSubId(sub.subId)
                newVoice = sub.subId
            }.onFailure { Log.e(TAG, "setDefaultVoiceSubId(${sub.subId})", it) }
        }
        aspects.sms?.let { ref ->
            val sub = subById[ref.iccid] ?: return@let
            if (!sub.isActive) tryActivateEsim(client, ref.iccid)
            runCatching {
                client.setDefaultSmsSubId(sub.subId)
                newSms = sub.subId
            }.onFailure { Log.e(TAG, "setDefaultSmsSubId(${sub.subId})", it) }
        }
        app.container.overrideDetector.recordOurSwitch(mcc, newData, newVoice, newSms)
        app.container.simRegistry.refresh()
        overrideCheckJob?.cancel()
        overrideCheckJob = scope.launch {
            delay(15_000)
            val cur = runCatching {
                Triple(client.defaultDataSubId, client.defaultVoiceSubId, client.defaultSmsSubId)
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

    private fun tryActivateEsim(client: ISimControl, iccid: String) {
        runCatching { client.activateEsimByIccid(iccid) }
            .onFailure { Log.w(TAG, "activateEsimByIccid($iccid) failed", it) }
    }

    companion object {
        private const val TAG = "CountryWatcherService"
        private const val CHANNEL_ID = "watcher"
        private const val NOTIF_ID = 1
        const val ACTION_RECONNECT_DAEMON = "it.allard.simcountry.action.RECONNECT_DAEMON"

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

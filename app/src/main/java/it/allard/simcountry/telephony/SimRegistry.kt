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

package it.allard.simcountry.telephony

import android.content.Context
import android.telephony.SubscriptionManager
import android.util.Log
import it.allard.simcountry.ipc.SimControlSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Combines two data sources to render the SIMs visible to the user:
 *   - The app's own [SubscriptionManager] enumerates active subscriptions
 *     with public fields (display name, carrier, MCC, MNC, isEmbedded).
 *     ICCID is gated behind READ_PRIVILEGED_PHONE_STATE so it is empty
 *     in app land.
 *   - The native daemon, running as shell uid, fills in the ICCID for
 *     each active subId via IPhoneSubInfo.
 *
 * Rules reference SIMs by ICCID so each successfully enriched sub gets
 * persisted; if the daemon is unreachable we still keep the app-side
 * picture, but rules cannot match because no ICCID is available.
 */
class SimRegistry(
    context: Context,
    private val socketClient: SimControlSocketClient,
) {
    data class LocalSubInfo(
        val subId: Int,
        val iccid: String,
        val displayName: String,
        val carrierName: String,
        val mcc: String?,
        val mnc: String?,
        val isEmbedded: Boolean,
        val isActive: Boolean,
    )

    @Serializable
    private data class Persisted(
        val iccid: String,
        val displayName: String,
        val carrierName: String,
        val isEmbedded: Boolean,
        val nickname: String? = null,
    )

    private val app = context.applicationContext
    private val subscriptionManager = app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
        as SubscriptionManager
    private val file = File(app.filesDir, FILE_NAME)
    private val tmp = File(app.filesDir, "$FILE_NAME.tmp")
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _persisted = MutableStateFlow(load())
    private val _live = MutableStateFlow<List<LocalSubInfo>>(emptyList())

    init {
        // Refresh whenever the daemon transitions to Connected so the
        // iccid enrichment fills in as soon as it becomes available. The
        // first emission is the initial Disconnected -- collect drops
        // those.
        scope.launch {
            socketClient.state.collect { st ->
                if (st is SimControlSocketClient.State.Connected) {
                    doRefresh()
                }
            }
        }
    }

    val subs: StateFlow<List<RegisteredSub>> = combine(_persisted, _live) { persisted, live ->
        val persistedByIccid = persisted.filter { it.iccid.isNotBlank() }.associateBy { it.iccid }
        live.map { s ->
            val nickname = if (s.iccid.isNotBlank()) persistedByIccid[s.iccid]?.nickname else null
            RegisteredSub(
                iccid = s.iccid,
                subId = s.subId,
                displayName = s.displayName,
                carrierName = s.carrierName,
                isEmbedded = s.isEmbedded,
                isActive = s.isActive,
                nickname = nickname,
            )
        }.sortedWith(compareByDescending<RegisteredSub> { it.isActive }.thenBy { it.displayName })
    }.let { flow ->
        val s = MutableStateFlow<List<RegisteredSub>>(emptyList())
        scope.launch { flow.collect { s.value = it } }
        s.asStateFlow()
    }

    fun refresh() {
        scope.launch { doRefresh() }
    }

    private suspend fun doRefresh() {
        val app = enumerateAppSide()
        val daemon = enumerateDaemonSide()
        val merged = app.map { s ->
            s.copy(iccid = daemon[s.subId].orEmpty())
        }
        _live.value = merged
        persistKnown(merged)
    }

    private fun enumerateAppSide(): List<LocalSubInfo> {
        val list = try {
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (se: SecurityException) {
            Log.w(TAG, "no READ_PHONE_STATE; skipping app-side enumeration", se)
            return emptyList()
        }
        return list.map { si ->
            LocalSubInfo(
                subId = si.subscriptionId,
                iccid = "",
                carrierName = si.carrierName?.toString().orEmpty(),
                displayName = si.displayName?.toString().orEmpty(),
                mcc = si.mccString,
                mnc = si.mncString,
                isEmbedded = si.isEmbedded,
                isActive = true,
            )
        }
    }

    private suspend fun enumerateDaemonSide(): Map<Int, String> {
        return try {
            socketClient.listSubs().associate { it.subId to it.iccid }
        } catch (t: Throwable) {
            Log.v(TAG, "daemon list_subs failed: ${t.message}")
            emptyMap()
        }
    }

    private fun persistKnown(list: List<LocalSubInfo>) {
        val identifiable = list.filter { it.iccid.isNotBlank() }
        if (identifiable.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val byIccid = _persisted.value
                    .filter { it.iccid.isNotBlank() }
                    .associateBy { it.iccid }
                    .toMutableMap()
                for (s in identifiable) {
                    val nick = byIccid[s.iccid]?.nickname
                    byIccid[s.iccid] = Persisted(s.iccid, s.displayName, s.carrierName, s.isEmbedded, nick)
                }
                save(byIccid.values.toList())
            }
        }
    }

    fun setNickname(iccid: String, nickname: String?) {
        scope.launch {
            mutex.withLock {
                val updated = _persisted.value.map {
                    if (it.iccid == iccid) it.copy(nickname = nickname) else it
                }
                save(updated)
            }
        }
    }

    private fun load(): List<Persisted> = try {
        if (!file.exists()) emptyList()
        else json.decodeFromString(ListSerializer(Persisted.serializer()), file.readText())
    } catch (t: Throwable) {
        Log.w(TAG, "load failed; resetting", t)
        emptyList()
    }

    private fun save(list: List<Persisted>) {
        _persisted.value = list
        runCatching {
            tmp.writeText(json.encodeToString(ListSerializer(Persisted.serializer()), list))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                error("rename ${tmp.name} -> ${file.name} failed")
            }
        }.onFailure { Log.e(TAG, "save failed", it) }
    }

    data class RegisteredSub(
        val iccid: String,
        val subId: Int?,
        val displayName: String,
        val carrierName: String,
        val isEmbedded: Boolean,
        val isActive: Boolean,
        val nickname: String?,
    ) {
        val hasIccid: Boolean get() = iccid.isNotBlank()
        val label: String
            get() = nickname?.takeIf { it.isNotBlank() }
                ?: displayName.ifBlank { carrierName }.ifBlank {
                    if (hasIccid) iccid.takeLast(6) else "subId $subId"
                }
    }

    companion object {
        private const val FILE_NAME = "sims.json"
        private const val TAG = "SimRegistry"
    }
}

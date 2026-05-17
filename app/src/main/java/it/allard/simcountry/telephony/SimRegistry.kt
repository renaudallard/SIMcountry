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
import it.allard.simcountry.ipc.SimControlClient
import it.allard.simcountry.ipc.SubInfo
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

class SimRegistry(
    context: Context,
    private val client: SimControlClient,
) {
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
    private val _liveLocal = MutableStateFlow<List<SubInfo>>(emptyList())
    private val _liveDaemon = MutableStateFlow<List<SubInfo>>(emptyList())

    val subs: StateFlow<List<RegisteredSub>> = combine(
        _persisted,
        _liveLocal,
        _liveDaemon,
    ) { persisted, local, daemon ->
        // Daemon entries are richer (real ICCID, inactive eSIMs included) so they win
        // per subscriptionId. The app-side query fills in the active set whenever the
        // daemon is not connected.
        val daemonBySubId = daemon.associateBy { it.subId }
        val merged = (daemon + local.filter { it.subId !in daemonBySubId }).distinctBy { it.subId }
        val persistedByIccid = persisted.filter { it.iccid.isNotBlank() }.associateBy { it.iccid }
        merged.map { s ->
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
        scope.launch {
            refreshFromSubscriptionManager()
            refreshFromDaemon()
        }
    }

    private fun refreshFromSubscriptionManager() {
        val list = try {
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (se: SecurityException) {
            Log.w(TAG, "no READ_PHONE_STATE; skipping app-side enumeration", se)
            return
        }
        _liveLocal.value = list.map { si ->
            SubInfo(
                subId = si.subscriptionId,
                iccid = si.iccId?.trim().orEmpty(),
                carrierName = si.carrierName?.toString().orEmpty(),
                displayName = si.displayName?.toString().orEmpty(),
                mcc = si.mccString,
                mnc = si.mncString,
                isEmbedded = si.isEmbedded,
                isActive = true,
            )
        }
        persistKnown(_liveLocal.value)
    }

    private suspend fun refreshFromDaemon() {
        val iface = client.iface ?: return
        val list = runCatching { iface.listAllSubscriptions() }
            .onFailure { Log.w(TAG, "listAllSubscriptions", it) }
            .getOrNull() ?: return
        _liveDaemon.value = list
        persistKnown(list)
    }

    private fun persistKnown(list: List<SubInfo>) {
        // Only keep entries with a real ICCID in the persisted store; rules reference
        // SIMs by ICCID and entries without one are not addressable from a rule.
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

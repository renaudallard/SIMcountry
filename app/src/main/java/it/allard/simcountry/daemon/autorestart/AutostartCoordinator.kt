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

package it.allard.simcountry.daemon.autorestart

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Drives the Wireless-ADB self-restart feature end to end.
 *
 *   - [pair] runs once: user enters the six-digit code from Developer
 *     Options, we discover the pair port over mDNS, run the SPAKE2 +
 *     AES-GCM pairing handshake, and record success.
 *   - [reconnectDaemon] runs on boot (and on demand): we discover the
 *     connect port, present our paired cert/key over TLS, and ask
 *     adbd to launch the same `app_process` invocation that the manual
 *     ADB command runs.
 */
class AutostartCoordinator(context: Context) {

    private val app = context.applicationContext
    private val stateFile = File(app.filesDir, STATE_FILE)
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val key: AdbRsaKey = AdbRsaKey.loadOrCreate(app)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun pair(pairingCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val port = AdbMdns.findPort(app, AdbMdns.SERVICE_TYPE_PAIRING, PAIR_DISCOVER_MS)
                ?: error("Could not find ADB pairing endpoint. Enable Wireless Debugging and tap \"Pair device with pairing code\".")
            val pairing = AdbPairing(key, pairingCode)
            pairing.pair(HOST, port)
            val now = System.currentTimeMillis()
            val next = State.Paired(pairedAt = now, lastConnectAt = null, lastError = null)
            saveAndPublish(next)
        }
    }

    suspend fun reconnectDaemon(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val current = _state.value
            if (current !is State.Paired) error("Device not paired with SIMcountry yet.")
            val port = AdbMdns.findPort(app, AdbMdns.SERVICE_TYPE_CONNECT, CONNECT_DISCOVER_MS)
                ?: error("Could not find Wireless ADB connect endpoint. Is Wireless Debugging enabled?")
            val connection = AdbConnection(HOST, port, key)
            val command = daemonStartCommand(app.packageName)
            val output = connection.executeShell(command)
            val now = System.currentTimeMillis()
            saveAndPublish(current.copy(lastConnectAt = now, lastError = null))
            output
        }.onFailure { t ->
            Log.w(TAG, "reconnectDaemon failed", t)
            val current = _state.value
            if (current is State.Paired) {
                saveAndPublish(current.copy(lastError = t.message))
            }
        }
    }

    private fun saveAndPublish(next: State) {
        _state.value = next
        runCatching {
            stateFile.writeText(json.encodeToString(SerializableState.serializer(), next.toSerializable()))
        }.onFailure { Log.w(TAG, "stateFile write failed", it) }
    }

    private fun loadState(): State = try {
        if (!stateFile.exists()) State.Unpaired
        else json.decodeFromString(SerializableState.serializer(), stateFile.readText()).toDomain()
    } catch (t: Throwable) {
        Log.w(TAG, "state load failed; assuming unpaired", t)
        State.Unpaired
    }

    @Serializable
    private data class SerializableState(
        val paired: Boolean = false,
        val pairedAt: Long = 0L,
        val lastConnectAt: Long? = null,
        val lastError: String? = null,
    ) {
        fun toDomain(): State =
            if (!paired) State.Unpaired
            else State.Paired(pairedAt, lastConnectAt, lastError)
    }

    private fun State.toSerializable(): SerializableState = when (this) {
        is State.Unpaired -> SerializableState(paired = false)
        is State.Paired -> SerializableState(
            paired = true,
            pairedAt = pairedAt,
            lastConnectAt = lastConnectAt,
            lastError = lastError,
        )
    }

    sealed interface State {
        data object Unpaired : State
        data class Paired(
            val pairedAt: Long,
            val lastConnectAt: Long? = null,
            val lastError: String? = null,
        ) : State
    }

    companion object {
        private const val TAG = "AutostartCoordinator"
        private const val STATE_FILE = "autostart.json"
        private const val HOST = "127.0.0.1"
        private const val PAIR_DISCOVER_MS = 15_000L
        private const val CONNECT_DISCOVER_MS = 8_000L

        fun daemonStartCommand(packageId: String): String {
            // Resolve the APK path in the outer shell so the failure case
            // surfaces in the WRTE stream that AdbConnection.executeShell
            // returns. Only the actual daemon invocation is backgrounded
            // (with its own stdout/stderr to /dev/null so adbd can close
            // the shell stream cleanly).
            val entry = "it.allard.simcountry.daemon.DaemonEntrypoint"
            return """APK=$(pm path $packageId | sed "s/^package://;1q"); """ +
                """[ -n "${'$'}APK" ] || { echo "no apk for $packageId" >&2; exit 90; }; """ +
                """(nohup /system/bin/app_process -Djava.class.path="${'$'}APK" /system/bin """ +
                """--nice-name=simcountry-daemon $entry $packageId </dev/null >/dev/null 2>&1 &)"""
        }
    }
}

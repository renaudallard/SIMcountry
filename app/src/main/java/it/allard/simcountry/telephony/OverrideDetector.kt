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
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

class OverrideDetector(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class Snapshot(val mcc: String, val data: Int, val voice: Int, val sms: Int, val at: Long)

    private val file = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private var lastApplied: Snapshot? = null

    private val _suppressedUntil = MutableStateFlow(loadSuppressions())
    val suppressedUntil: StateFlow<Map<String, Long>> = _suppressedUntil.asStateFlow()

    fun recordOurSwitch(mcc: String, data: Int, voice: Int, sms: Int) {
        lastApplied = Snapshot(mcc, data, voice, sms, now())
    }

    suspend fun detectAndMaybeSuppress(
        observedMcc: String,
        currentData: Int,
        currentVoice: Int,
        currentSms: Int,
        suppressionMs: Long,
    ): Boolean {
        val applied = lastApplied ?: return false
        if (applied.mcc != observedMcc) return false
        val mismatch =
            (applied.data != -1 && applied.data != currentData) ||
                (applied.voice != -1 && applied.voice != currentVoice) ||
                (applied.sms != -1 && applied.sms != currentSms)
        if (!mismatch) return false
        val sinceSwitch = now() - applied.at
        if (sinceSwitch < SETTLE_GRACE_MS) return false
        mutex.withLock {
            val next = _suppressedUntil.value.toMutableMap()
            next[observedMcc] = now() + suppressionMs
            _suppressedUntil.value = next
            saveSuppressions(next)
        }
        Log.i(TAG, "user override on mcc=$observedMcc -> suppressing for ${suppressionMs / 1000}s")
        return true
    }

    fun isSuppressed(mcc: String): Boolean {
        val until = _suppressedUntil.value[mcc] ?: return false
        return now() < until
    }

    suspend fun clearSuppression(mcc: String) {
        mutex.withLock {
            val next = _suppressedUntil.value.toMutableMap()
            next.remove(mcc)
            _suppressedUntil.value = next
            saveSuppressions(next)
        }
    }

    private fun loadSuppressions(): Map<String, Long> = try {
        if (!file.exists()) emptyMap()
        else json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), file.readText())
    } catch (t: Throwable) {
        Log.w(TAG, "loadSuppressions failed", t)
        emptyMap()
    }

    private fun saveSuppressions(map: Map<String, Long>) {
        runCatching { file.writeText(json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), map)) }
            .onFailure { Log.w(TAG, "saveSuppressions failed", it) }
    }

    companion object {
        private const val FILE_NAME = "suppressions.json"
        private const val TAG = "OverrideDetector"
        private const val SETTLE_GRACE_MS = 30_000L
    }
}

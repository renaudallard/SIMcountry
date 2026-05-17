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

package it.allard.simcountry.rules

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import it.allard.simcountry.telephony.Mcc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class RulesStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val tmp = File(context.filesDir, "$FILE_NAME.tmp")
    private val mutex = Mutex()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _doc = MutableStateFlow(load())
    val doc: StateFlow<RulesDoc> = _doc.asStateFlow()

    @Serializable
    private data class V1Rule(
        val mcc: String,
        val mnc: String? = null,
        val aspects: AspectRules = AspectRules(),
    )

    @Serializable
    private data class V1Doc(
        val version: Int = 1,
        val rules: List<V1Rule> = emptyList(),
        val defaults: AspectRules = AspectRules(),
        val policy: Policy = Policy(),
    )

    private fun load(): RulesDoc {
        if (!file.exists()) return RulesDoc()
        return loadFromText(file.readText())
    }

    private fun loadFromText(text: String): RulesDoc = try {
        val version = json.parseToJsonElement(text)
            .jsonObject["version"]?.jsonPrimitive?.intOrNull ?: 1
        if (version >= 2) {
            json.decodeFromString(RulesDoc.serializer(), text)
        } else {
            val v1 = json.decodeFromString(V1Doc.serializer(), text)
            val migrated = v1.rules.mapNotNull { lr ->
                val iso = Mcc.byMcc[lr.mcc]?.iso
                if (iso == null) {
                    Log.w(TAG, "dropping legacy rule for unknown mcc=${lr.mcc}")
                    null
                } else {
                    CountryRule(iso = iso, mcc = lr.mcc, mnc = lr.mnc, aspects = lr.aspects)
                }
            }
            RulesDoc(version = 2, rules = migrated, defaults = v1.defaults, policy = v1.policy)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "load failed; starting with empty rules", t)
        RulesDoc()
    }

    fun update(transform: (RulesDoc) -> RulesDoc) {
        scope.launch {
            mutex.withLock {
                val next = transform(_doc.value)
                _doc.value = next
                runCatching {
                    tmp.writeText(json.encodeToString(RulesDoc.serializer(), next))
                    if (!tmp.renameTo(file)) {
                        tmp.delete()
                        error("rename ${tmp.name} -> ${file.name} failed")
                    }
                }.onFailure { Log.e(TAG, "save failed", it) }
            }
        }
    }

    companion object {
        private const val FILE_NAME = "rules.json"
        private const val TAG = "RulesStore"
    }
}

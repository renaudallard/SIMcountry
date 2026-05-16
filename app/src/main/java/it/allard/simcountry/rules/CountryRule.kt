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

import kotlinx.serialization.Serializable

@Serializable
data class SimRef(val iccid: String)

@Serializable
data class AspectRules(
    val data: SimRef? = null,
    val voice: SimRef? = null,
    val sms: SimRef? = null,
) {
    fun isEmpty(): Boolean = data == null && voice == null && sms == null
}

@Serializable
data class CountryRule(
    val mcc: String,
    val mnc: String? = null,
    val aspects: AspectRules = AspectRules(),
)

@Serializable
data class Policy(
    val stabilitySec: Int = 60,
    val reverseHysteresisSec: Int = 120,
    val minSwitchIntervalSec: Int = 300,
    val overrideSuppressionSec: Int = 3600,
)

@Serializable
data class RulesDoc(
    val version: Int = 1,
    val rules: List<CountryRule> = emptyList(),
    val defaults: AspectRules = AspectRules(),
    val policy: Policy = Policy(),
)

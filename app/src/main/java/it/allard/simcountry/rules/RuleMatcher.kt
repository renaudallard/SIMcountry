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

import it.allard.simcountry.telephony.Mcc

object RuleMatcher {

    fun match(doc: RulesDoc, mcc: String?, mnc: String?): AspectRules? {
        if (mcc.isNullOrBlank()) return null
        val iso = Mcc.byMcc[mcc]?.iso ?: return null
        val candidates = doc.rules.filter { rule ->
            rule.iso == iso &&
                (rule.mcc == null || rule.mcc == mcc) &&
                (rule.mnc == null || rule.mnc == mnc)
        }
        val best = candidates.maxByOrNull { specificity(it, mcc, mnc) } ?: return null
        return mergeWithDefaults(best.aspects, doc.defaults)
    }

    private fun specificity(rule: CountryRule, mcc: String, mnc: String?): Int {
        var score = 0
        if (rule.mcc != null && rule.mcc == mcc) score += 10
        if (rule.mnc != null && rule.mnc == mnc) score += 1
        return score
    }

    private fun mergeWithDefaults(rule: AspectRules, defaults: AspectRules): AspectRules =
        AspectRules(
            data = rule.data ?: defaults.data,
            voice = rule.voice ?: defaults.voice,
            sms = rule.sms ?: defaults.sms,
        )
}

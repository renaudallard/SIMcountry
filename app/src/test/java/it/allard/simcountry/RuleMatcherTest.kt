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

package it.allard.simcountry

import it.allard.simcountry.rules.AspectRules
import it.allard.simcountry.rules.CountryRule
import it.allard.simcountry.rules.RuleMatcher
import it.allard.simcountry.rules.RulesDoc
import it.allard.simcountry.rules.SimRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleMatcherTest {

    private val ch = SimRef("8941001111111111111")
    private val eu = SimRef("8941002222222222222")
    private val verizon = SimRef("8941003333333333333")

    private val doc = RulesDoc(
        rules = listOf(
            // Any Swiss MCC -> ch
            CountryRule(iso = "CH", aspects = AspectRules(data = ch, voice = ch)),
            // France with operator 01 narrowing -> eu
            CountryRule(iso = "FR", mnc = "01", aspects = AspectRules(sms = eu)),
            // Any US MCC -> eu (base US rule)
            CountryRule(iso = "US", aspects = AspectRules(data = eu)),
            // US MCC 311 specifically -> verizon (overrides the base US rule)
            CountryRule(iso = "US", mcc = "311", aspects = AspectRules(data = verizon)),
        ),
        defaults = AspectRules(data = eu),
    )

    @Test fun isoOnlyMatchByAnyCountryMcc() {
        val m = RuleMatcher.match(doc, "228", null)!!
        assertEquals(ch, m.data)
        assertEquals(ch, m.voice)
        assertNull(m.sms)
    }

    @Test fun mncNarrowingApplies() {
        val m = RuleMatcher.match(doc, "208", "01")!!
        assertEquals(eu, m.sms)
        assertEquals(eu, m.data)
        assertNull(m.voice)
    }

    @Test fun mccOverrideBeatsBaseCountryRule() {
        val m = RuleMatcher.match(doc, "311", null)!!
        assertEquals(verizon, m.data)
    }

    @Test fun baseCountryRuleStillCoversOtherMccs() {
        val m = RuleMatcher.match(doc, "310", null)!!
        assertEquals(eu, m.data)
    }

    @Test fun unknownMccReturnsNull() {
        assertNull(RuleMatcher.match(doc, "999", null))
    }

    @Test fun unmappedMccReturnsNull() {
        // 200 is not assigned to any country.
        assertNull(RuleMatcher.match(doc, "200", null))
    }

    @Test fun nullMccReturnsNull() {
        assertNull(RuleMatcher.match(doc, null, "01"))
    }

    @Test fun defaultsFillUnsetAspects() {
        val m = RuleMatcher.match(doc, "228", "any")!!
        assertEquals(ch, m.data)
        assertNull(m.sms)
    }
}

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

import it.allard.simcountry.telephony.CountryWatcher
import it.allard.simcountry.telephony.CountryWatcher.Country
import it.allard.simcountry.telephony.CountryWatcher.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryWatcherTest {

    private val policy = Policy.fromSeconds(60, 120, 300)

    @Test fun settlesAfterStabilityWindow() {
        var t = 0L
        val w = CountryWatcher(now = { t })
        w.observe(Country("228", null))
        t = 59_000L
        assertNull(w.tick(policy))
        t = 60_000L
        val s = w.tick(policy)!!
        assertEquals("228", s.country.mcc)
        assertNull(s.previous)
    }

    @Test fun candidateChangeResetsStability() {
        var t = 0L
        val w = CountryWatcher(now = { t })
        w.observe(Country("228", null))
        t = 30_000L
        w.observe(Country("208", null))
        t = 60_000L
        assertNull(w.tick(policy))
        t = 90_000L
        val s = w.tick(policy)!!
        assertEquals("208", s.country.mcc)
    }

    @Test fun reverseHysteresisAppliesOnSecondReversal() {
        var t = 0L
        val w = CountryWatcher(now = { t })
        w.observe(Country("228", null))
        t = 60_000L
        w.tick(policy)
        t = 361_000L
        w.observe(Country("208", null))
        t = 421_000L
        w.tick(policy)
        t = 722_000L
        w.observe(Country("228", null))
        t = 781_000L
        assertNull(w.tick(policy))
        t = 842_000L
        val s = w.tick(policy)!!
        assertEquals("228", s.country.mcc)
    }

    @Test fun minSwitchIntervalGate() {
        var t = 0L
        val w = CountryWatcher(now = { t })
        w.observe(Country("228", null))
        t = 60_000L
        w.tick(policy)
        t = 120_000L
        w.observe(Country("208", null))
        t = 240_000L
        assertNull(w.tick(policy))
        t = 360_001L
        val s = w.tick(policy)!!
        assertEquals("208", s.country.mcc)
    }

    @Test fun observeNullClearsCandidate() {
        var t = 0L
        val w = CountryWatcher(now = { t })
        w.observe(Country("228", null))
        t = 30_000L
        w.observe(null)
        t = 90_000L
        assertNull(w.tick(policy))
    }
}

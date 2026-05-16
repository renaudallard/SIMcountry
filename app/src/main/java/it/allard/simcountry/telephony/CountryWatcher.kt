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

class CountryWatcher(
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Country(val mcc: String, val mnc: String?)
    data class Settled(val country: Country, val previous: Country?)

    private var candidate: Country? = null
    private var candidateSince: Long = 0L
    private var settled: Country? = null
    private var previousSettled: Country? = null
    private var lastSettleAt: Long = 0L

    @Synchronized
    fun observe(country: Country?) {
        val t = now()
        when {
            country == null -> {
                candidate = null
                candidateSince = 0L
            }
            country == settled -> {
                candidate = null
                candidateSince = 0L
            }
            country != candidate -> {
                candidate = country
                candidateSince = t
            }
        }
    }

    @Synchronized
    fun tick(policy: Policy): Settled? {
        val c = candidate ?: return null
        val t = now()
        val elapsed = t - candidateSince
        val isReversal = c == previousSettled
        val needed = if (isReversal) policy.reverseHysteresisMs else policy.stabilityMs
        if (elapsed < needed) return null
        if (settled != null && (t - lastSettleAt) < policy.minSwitchIntervalMs) return null
        val prev = settled
        settled = c
        previousSettled = prev
        lastSettleAt = t
        candidate = null
        candidateSince = 0L
        return Settled(c, prev)
    }

    @Synchronized
    fun reset() {
        candidate = null
        candidateSince = 0L
        settled = null
        previousSettled = null
        lastSettleAt = 0L
    }

    val currentSettled: Country?
        @Synchronized get() = settled

    data class Policy(
        val stabilityMs: Long,
        val reverseHysteresisMs: Long,
        val minSwitchIntervalMs: Long,
    ) {
        companion object {
            fun fromSeconds(stab: Int, rev: Int, min: Int) = Policy(
                stab * 1000L, rev * 1000L, min * 1000L,
            )
        }
    }
}

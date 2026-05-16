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

package it.allard.simcountry.autorestart

import it.allard.simcountry.daemon.autorestart.AdbPairing
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AdbPairingStreamTest {

    private val sharedKey: ByteArray = ByteArray(64) { it.toByte() }

    @Test fun encryptThenDecryptRecoversPlaintext() {
        // Two cipher instances with the same shared key model the two peers.
        val client = AdbPairing.Aes128GcmStream(sharedKey)
        val server = AdbPairing.Aes128GcmStream(sharedKey)

        val plain = ByteArray(8192) { ((it * 17) and 0xFF).toByte() }
        val ciphertext = client.encrypt(plain)
        val decoded = server.decrypt(ciphertext)
        assertArrayEquals(plain, decoded)
    }

    @Test fun ciphertextHasGcmTagAppended() {
        val s = AdbPairing.Aes128GcmStream(sharedKey)
        val plain = ByteArray(100)
        val ct = s.encrypt(plain)
        // 128-bit GCM tag = 16 bytes of overhead.
        assertEquals(plain.size + 16, ct.size)
    }

    @Test fun encryptCountersAdvance() {
        val s = AdbPairing.Aes128GcmStream(sharedKey)
        val a = s.encrypt("first".toByteArray())
        val b = s.encrypt("first".toByteArray())
        // Different nonces -> different ciphertexts.
        assertNotEquals(a.toList(), b.toList())
    }

    @Test fun decryptCountersAdvance() {
        // Simulate: peer encrypts two messages; we decrypt them in order.
        val peer = AdbPairing.Aes128GcmStream(sharedKey)
        val us = AdbPairing.Aes128GcmStream(sharedKey)
        // Drain our enc counter so it does not collide with peer's enc.
        us.encrypt(ByteArray(0))
        us.encrypt(ByteArray(0))
        val m1 = peer.encrypt("one".toByteArray())
        val m2 = peer.encrypt("two".toByteArray())
        assertEquals("one", String(us.decrypt(m1)))
        assertEquals("two", String(us.decrypt(m2)))
    }
}

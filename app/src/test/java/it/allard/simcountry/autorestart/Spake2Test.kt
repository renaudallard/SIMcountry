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

import it.allard.simcountry.daemon.autorestart.Ed25519Math
import it.allard.simcountry.daemon.autorestart.Spake2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class Spake2Test {

    @Test fun mAndNDecodeToValidPoints() {
        assertNotNull(Ed25519Math.decode(Spake2.M_BYTES))
        assertNotNull(Ed25519Math.decode(Spake2.N_BYTES))
    }

    @Test fun clientAndServerWithMatchingPasswordsAgreeOnKey() {
        val pw = "123456".toByteArray()
        val client = Spake2(Spake2.Role.CLIENT, pw)
        val server = Spake2(Spake2.Role.SERVER, pw)
        val ck = client.processPeerMessage(server.outboundMessage)
        val sk = server.processPeerMessage(client.outboundMessage)
        assertArrayEquals(ck, sk)
        assertEquals(64, ck.size)
    }

    @Test fun differentPasswordsProduceDifferentKeys() {
        val client = Spake2(Spake2.Role.CLIENT, "right".toByteArray())
        val server = Spake2(Spake2.Role.SERVER, "wrong".toByteArray())
        val ck = client.processPeerMessage(server.outboundMessage)
        val sk = server.processPeerMessage(client.outboundMessage)
        assertNotEquals(ck.toList(), sk.toList())
    }

    @Test fun outboundMessagesAreThirtyTwoBytes() {
        val pw = "abc".toByteArray()
        val client = Spake2(Spake2.Role.CLIENT, pw)
        val server = Spake2(Spake2.Role.SERVER, pw)
        assertEquals(32, client.outboundMessage.size)
        assertEquals(32, server.outboundMessage.size)
    }

    @Test fun runWithDifferentRandomnessSucceedsTwice() {
        val pw = "shared".toByteArray()
        repeat(3) {
            val c = Spake2(Spake2.Role.CLIENT, pw)
            val s = Spake2(Spake2.Role.SERVER, pw)
            val ck = c.processPeerMessage(s.outboundMessage)
            val sk = s.processPeerMessage(c.outboundMessage)
            assertArrayEquals(ck, sk)
        }
    }

    @Test fun processRejectsCorruptPeerMessage() {
        val pw = "p".toByteArray()
        val c = Spake2(Spake2.Role.CLIENT, pw)
        val bad = ByteArray(32) { 0xFF.toByte() }
        bad[31] = 0x7F // y exceeds field prime
        assertThrows(IllegalArgumentException::class.java) {
            c.processPeerMessage(bad)
        }
    }
}

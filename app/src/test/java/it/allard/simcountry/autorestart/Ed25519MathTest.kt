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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class Ed25519MathTest {

    @Test fun fieldPrimeIsCorrect() {
        // p = 2^255 - 19
        val expected = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        assertEquals(expected, Ed25519Math.P)
    }

    @Test fun subgroupOrderIsCorrect() {
        // l = 2^252 + 27742317777372353535851937790883648493
        val expected = BigInteger.ONE.shiftLeft(252).add(
            BigInteger("27742317777372353535851937790883648493"),
        )
        assertEquals(expected, Ed25519Math.L)
    }

    @Test fun basePointEncodesToRfc8032Constant() {
        // RFC 8032 base point in compressed form, little-endian:
        //   y = 4/5 mod p
        // The encoded value is 0x5866 6666 6666 6666 6666 6666 6666 6666
        //                      6666 6666 6666 6666 6666 6666 6666 6658 (reversed below).
        // We do not hardcode the expected constant; instead we check the
        // structural invariant that encode(B) starts with byte 0x58.
        val encoded = Ed25519Math.encode(Ed25519Math.B)
        assertEquals(32, encoded.size)
        // Each of the 30 middle bytes is 0x66 by construction of y = 4/5.
        for (i in 1 until 31) {
            assertEquals("byte $i must be 0x66", 0x66.toByte(), encoded[i])
        }
        assertEquals(0x58.toByte(), encoded[0])
    }

    @Test fun basePointTimesSubgroupOrderIsIdentity() {
        val lG = Ed25519Math.scalarMultBase(Ed25519Math.L)
        assertEquals(BigInteger.ZERO, lG.x.multiply(lG.z.modInverse(Ed25519Math.P)).mod(Ed25519Math.P))
    }

    @Test fun decodeEncodeRoundTripForBasePoint() {
        val encoded = Ed25519Math.encode(Ed25519Math.B)
        val decoded = Ed25519Math.decode(encoded)
        assertNotNull(decoded)
        val reEncoded = Ed25519Math.encode(decoded!!)
        assertArrayEquals(encoded, reEncoded)
    }

    @Test fun scalarMultIsDistributiveOverAddition() {
        // (k1 + k2) * G == k1*G + k2*G
        val k1 = BigInteger("1234567890123456789012345678901234567")
        val k2 = BigInteger("9876543210987654321098765432109876543")
        val left = Ed25519Math.scalarMultBase(k1.add(k2))
        val right = Ed25519Math.add(Ed25519Math.scalarMultBase(k1), Ed25519Math.scalarMultBase(k2))
        assertArrayEquals(Ed25519Math.encode(left), Ed25519Math.encode(right))
    }

    @Test fun negationAddsToIdentity() {
        val k = BigInteger("4242424242424242424242424242424242")
        val p = Ed25519Math.scalarMultBase(k)
        val sum = Ed25519Math.add(p, Ed25519Math.negate(p))
        // Should be the identity (0, 1, 1, 0) in affine coords (0, 1).
        val zInv = sum.z.modInverse(Ed25519Math.P)
        val x = sum.x.multiply(zInv).mod(Ed25519Math.P)
        val y = sum.y.multiply(zInv).mod(Ed25519Math.P)
        assertEquals(BigInteger.ZERO, x)
        assertEquals(BigInteger.ONE, y)
    }

    @Test fun decodeRejectsInvalidPoint() {
        // y = p - 1 with sign bit; recovers x = 0 with sign mismatch -> still valid? Let's instead
        // craft bytes whose y exceeds the field prime.
        val tooLarge = ByteArray(32) { 0xFF.toByte() }
        tooLarge[31] = 0x7F // clear sign bit, leaves y = 2^255 - 1 > p
        assertNull(Ed25519Math.decode(tooLarge))
    }

    @Test fun decodeRejectsOffCurvePoint() {
        // The y > p test above only exercises the bounds check at the top
        // of decode. The curve-membership check lives further down, in
        // recoverX, and only fires when (y^2-1)/(d*y^2+1) is a quadratic
        // non-residue mod p. Sweep small y values to find one that
        // satisfies y < p but has no x solving the curve equation, then
        // verify the decoder rejects both possible sign bits.
        var offCurveY = -1
        for (yVal in 2..100) {
            val bytes = ByteArray(32).also { it[0] = yVal.toByte() }
            if (Ed25519Math.decode(bytes) == null) {
                offCurveY = yVal
                break
            }
        }
        assertTrue("expected at least one off-curve y in [2,100]", offCurveY > 0)
        val withSignBit = ByteArray(32).also {
            it[0] = offCurveY.toByte()
            it[31] = 0x80.toByte()
        }
        assertNull(Ed25519Math.decode(withSignBit))
    }

    @Test fun littleEndianFromBytesRoundTrips() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val value = Ed25519Math.fromLittleEndian(bytes)
        assertEquals(BigInteger("0807060504030201", 16), value)
    }
}

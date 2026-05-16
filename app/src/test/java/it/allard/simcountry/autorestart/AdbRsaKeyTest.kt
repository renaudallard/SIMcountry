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

import it.allard.simcountry.daemon.autorestart.AdbRsaKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

class AdbRsaKeyTest {

    private fun newKey(): AdbRsaKey {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()
        return AdbRsaKey(
            privateKey = pair.private as java.security.interfaces.RSAPrivateKey,
            publicKey = pair.public as RSAPublicKey,
            userHost = "test@junit",
        )
    }

    @Test fun signProducesPkcs1V15WithRawTokenAsDigest() {
        val key = newKey()
        val token = ByteArray(20) { it.toByte() }
        val sig = key.sign(token)
        assertEquals(256, sig.size)

        val verifier = Signature.getInstance("NONEwithRSA")
        verifier.initVerify(key.publicKey)
        verifier.update(token)
        assert(verifier.verify(sig)) { "signature must verify under NONEwithRSA" }
    }

    @Test fun legacyStructHas524BytesAndCorrectModulusWordCount() {
        val key = newKey()
        val struct = AdbRsaKey.legacyRsaPublicKeyStruct(key.publicKey)
        assertEquals(524, struct.size)
        val buf = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buf.int) // modulus_size_words for 2048-bit RSA
    }

    @Test fun legacyStructModulusRoundTripsThroughLittleEndian() {
        val key = newKey()
        val struct = AdbRsaKey.legacyRsaPublicKeyStruct(key.publicKey)
        val buf = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        buf.int // modulus_size_words
        buf.int // n0inv
        val modulusLE = ByteArray(256).also { buf.get(it) }
        val modulusBE = ByteArray(256) { modulusLE[255 - it] }
        val recovered = BigInteger(1, modulusBE)
        assertEquals(key.publicKey.modulus, recovered)
    }

    @Test fun legacyStructN0invIsMontgomeryConstant() {
        val key = newKey()
        val struct = AdbRsaKey.legacyRsaPublicKeyStruct(key.publicKey)
        val buf = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        buf.int // skip modulus_size_words
        val n0inv = buf.int.toLong() and 0xFFFFFFFFL
        val twoTo32 = BigInteger.ONE.shiftLeft(32)
        val n0 = key.publicKey.modulus.mod(twoTo32)
        // n0inv must satisfy: n0 * n0inv ≡ -1 (mod 2^32)
        val product = n0.multiply(BigInteger.valueOf(n0inv)).mod(twoTo32).toLong()
        assertEquals(twoTo32.subtract(BigInteger.ONE).toLong(), product)
    }

    @Test fun legacyStructRrEqualsRSquaredModN() {
        val key = newKey()
        val struct = AdbRsaKey.legacyRsaPublicKeyStruct(key.publicKey)
        val buf = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        buf.int // modulus_size_words
        buf.int // n0inv
        val modulusLE = ByteArray(256).also { buf.get(it) }
        val rrLE = ByteArray(256).also { buf.get(it) }
        val rrBE = ByteArray(256) { rrLE[255 - it] }
        val rr = BigInteger(1, rrBE)
        val expected = BigInteger.ONE.shiftLeft(4096).mod(key.publicKey.modulus)
        assertEquals(expected, rr)
    }

    @Test fun authorizedKeyLineFormatsAsBase64SpaceUserHostNul() {
        val key = newKey()
        val line = key.adbAuthorizedKeyLine()
        assertEquals(0.toByte(), line[line.size - 1])
        val asString = String(line.copyOfRange(0, line.size - 1), Charsets.US_ASCII)
        val parts = asString.split(' ')
        assertEquals(2, parts.size)
        assertEquals("test@junit", parts[1])
        // Decoding the base64 should yield the 524-byte struct.
        val decoded = java.util.Base64.getDecoder().decode(parts[0])
        assertArrayEquals(
            AdbRsaKey.legacyRsaPublicKeyStruct(key.publicKey),
            decoded,
        )
    }
}

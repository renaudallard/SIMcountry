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

package it.allard.simcountry.daemon.autorestart

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 password-authenticated key exchange over Ed25519, configured to
 * match BoringSSL's `spake25519` implementation that adbd uses for
 * Wireless-Debugging pairing.
 *
 * The two roles are [Role.CLIENT] (we play this when pairing against the
 * device) and [Role.SERVER]. Each side picks a random scalar, derives the
 * SHA-512-of-password scalar, and exchanges a 32-byte compressed Ed25519
 * point. After processing the peer's message both sides arrive at the
 * same 64-byte shared secret.
 *
 * NOTE: this code matches BoringSSL's wire format and KDF as documented
 * in `crypto/curve25519/spake25519.c`, but has only been validated by
 * round-trip tests inside this repository. End-to-end correctness must
 * be confirmed against a real adbd before the autorestart flow can be
 * trusted.
 */
class Spake2(
    val role: Role,
    private val password: ByteArray,
    rng: SecureRandom = SecureRandom(),
) {

    enum class Role { CLIENT, SERVER }

    // BoringSSL pre-multiplies the random scalar by the curve cofactor (left_shift_3)
    // so the cofactor is cleared *once* during scalar multiplication on the peer's
    // unblinded point. Match that exactly: the integer value here is 8 * (rand mod L).
    private val privateScalar: BigInteger = randomScalar(rng).multiply(COFACTOR)
    private val passwordHashFull: ByteArray = MessageDigest.getInstance("SHA-512").digest(password)

    // BoringSSL applies a fix-up right after x25519_sc_reduce: add 0/L/2L/4L
    // as needed so the result is a multiple of 8. That ensures
    // passwordScalar * M_modified lands entirely in the prime-order
    // subgroup, so a passive observer cannot recover (pw mod 8) from the
    // outbound's cofactor component.
    private val passwordScalar: BigInteger = run {
        var s = Ed25519Math.fromLittleEndian(passwordHashFull).mod(Ed25519Math.L)
        if (s.testBit(0)) s = s.add(Ed25519Math.L)
        if (s.testBit(1)) s = s.add(Ed25519Math.L.shiftLeft(1))
        if (s.testBit(2)) s = s.add(Ed25519Math.L.shiftLeft(2))
        s
    }

    private val myPwPoint: Ed25519Math.Point = if (role == Role.CLIENT) M_POINT else N_POINT
    private val theirPwPoint: Ed25519Math.Point = if (role == Role.CLIENT) N_POINT else M_POINT

    /** 32-byte compressed point this side sends to the peer. */
    val outboundMessage: ByteArray = Ed25519Math.encode(
        Ed25519Math.add(
            Ed25519Math.scalarMultBase(privateScalar),
            Ed25519Math.scalarMult(passwordScalar, myPwPoint),
        ),
    )

    /**
     * Consume the peer's 32-byte message and return the 64-byte shared
     * secret. Throws when the peer's message is not a valid Ed25519 point.
     */
    fun processPeerMessage(theirMessage: ByteArray): ByteArray {
        require(theirMessage.size == 32) { "peer message must be 32 bytes" }
        val theirPoint = Ed25519Math.decode(theirMessage)
            ?: throw IllegalArgumentException("peer message is not a valid Ed25519 point")

        val negPwScalar = Ed25519Math.L.subtract(passwordScalar).mod(Ed25519Math.L)
        val unblinded = Ed25519Math.add(
            theirPoint,
            Ed25519Math.scalarMult(negPwScalar, theirPwPoint),
        )
        // privateScalar already includes the 8x cofactor factor; no extra multiply.
        val k = Ed25519Math.scalarMult(privateScalar, unblinded)
        val kBytes = Ed25519Math.encode(k)

        // BoringSSL hashes a length-prefixed transcript whose order is
        // always client-first: client name, server name, client msg, server msg,
        // dh_shared, password_hash. The names are the NUL-terminated ASCII
        // identifiers ("adb pair client\0" / "adb pair server\0"), each 16
        // bytes long because adbd passes their C-array sizeof which includes
        // the terminator.
        val clientMsg = if (role == Role.CLIENT) outboundMessage else theirMessage
        val serverMsg = if (role == Role.CLIENT) theirMessage else outboundMessage

        val sha = MessageDigest.getInstance("SHA-512")
        appendLengthPrefixed(sha, CLIENT_NAME)
        appendLengthPrefixed(sha, SERVER_NAME)
        appendLengthPrefixed(sha, clientMsg)
        appendLengthPrefixed(sha, serverMsg)
        appendLengthPrefixed(sha, kBytes)
        appendLengthPrefixed(sha, passwordHashFull)
        return sha.digest()
    }

    private fun appendLengthPrefixed(sha: MessageDigest, data: ByteArray) {
        val prefix = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(data.size.toLong()).array()
        sha.update(prefix)
        sha.update(data)
    }

    companion object {
        /**
         * Affine x, then y coordinates of M, little-endian. Taken from the
         * first 64 bytes of `kSpakeMSmallPrecomp` (i.e. 1*M) in AOSP's
         * `external/boringssl/src/crypto/curve25519/spake25519.cc`.
         */
        private val M_X_LE: ByteArray = byteArrayOf(
            0xc8.toByte(), 0xa6.toByte(), 0x63.toByte(), 0xc5.toByte(),
            0x97.toByte(), 0xf1.toByte(), 0xee.toByte(), 0x40.toByte(),
            0xab.toByte(), 0x62.toByte(), 0x42.toByte(), 0xee.toByte(),
            0x25.toByte(), 0x6f.toByte(), 0x32.toByte(), 0x6c.toByte(),
            0x75.toByte(), 0x2c.toByte(), 0xa7.toByte(), 0xd3.toByte(),
            0xbd.toByte(), 0x32.toByte(), 0x3b.toByte(), 0x1e.toByte(),
            0x11.toByte(), 0x9c.toByte(), 0xbd.toByte(), 0x04.toByte(),
            0xa9.toByte(), 0x78.toByte(), 0x6f.toByte(), 0x45.toByte(),
        )
        private val M_Y_LE: ByteArray = byteArrayOf(
            0x5a.toByte(), 0xda.toByte(), 0x7e.toByte(), 0x4b.toByte(),
            0xf6.toByte(), 0xdd.toByte(), 0xd9.toByte(), 0xad.toByte(),
            0xb6.toByte(), 0x62.toByte(), 0x6d.toByte(), 0x32.toByte(),
            0x13.toByte(), 0x1c.toByte(), 0x6b.toByte(), 0x5c.toByte(),
            0x51.toByte(), 0xa1.toByte(), 0xe3.toByte(), 0x47.toByte(),
            0xa3.toByte(), 0x47.toByte(), 0x8f.toByte(), 0x53.toByte(),
            0xcf.toByte(), 0xcf.toByte(), 0x44.toByte(), 0x1b.toByte(),
            0x88.toByte(), 0xee.toByte(), 0xd1.toByte(), 0x2e.toByte(),
        )

        /** First 64 bytes of `kSpakeNSmallPrecomp` (1*N). */
        private val N_X_LE: ByteArray = byteArrayOf(
            0x20.toByte(), 0x1b.toByte(), 0xc5.toByte(), 0xb3.toByte(),
            0x43.toByte(), 0x17.toByte(), 0x71.toByte(), 0x10.toByte(),
            0x44.toByte(), 0x1e.toByte(), 0x73.toByte(), 0xb3.toByte(),
            0xae.toByte(), 0x3f.toByte(), 0xbf.toByte(), 0x9f.toByte(),
            0xf5.toByte(), 0x44.toByte(), 0xc8.toByte(), 0x13.toByte(),
            0x8f.toByte(), 0xd1.toByte(), 0x01.toByte(), 0xc2.toByte(),
            0x8a.toByte(), 0x1a.toByte(), 0x6d.toByte(), 0xea.toByte(),
            0x4d.toByte(), 0x00.toByte(), 0x5d.toByte(), 0x6e.toByte(),
        )
        private val N_Y_LE: ByteArray = byteArrayOf(
            0x10.toByte(), 0xe3.toByte(), 0xdf.toByte(), 0x0a.toByte(),
            0xe3.toByte(), 0x7d.toByte(), 0x8e.toByte(), 0x7a.toByte(),
            0x99.toByte(), 0xb5.toByte(), 0xfe.toByte(), 0x74.toByte(),
            0xb4.toByte(), 0x46.toByte(), 0x72.toByte(), 0x10.toByte(),
            0x3d.toByte(), 0xbd.toByte(), 0xdc.toByte(), 0xbd.toByte(),
            0x06.toByte(), 0xaf.toByte(), 0x68.toByte(), 0x0d.toByte(),
            0x71.toByte(), 0x32.toByte(), 0x9a.toByte(), 0x11.toByte(),
            0x69.toByte(), 0x3b.toByte(), 0xc7.toByte(), 0x78.toByte(),
        )

        /** 32-byte compressed M (encoded from the affine coordinates). */
        val M_BYTES: ByteArray = Ed25519Math.encode(affinePoint(M_X_LE, M_Y_LE))

        /** 32-byte compressed N (encoded from the affine coordinates). */
        val N_BYTES: ByteArray = Ed25519Math.encode(affinePoint(N_X_LE, N_Y_LE))

        private val COFACTOR: BigInteger = BigInteger.valueOf(8)

        // adbd's PairingAuthCtx passes these names to SPAKE2_CTX_new with
        // a C `sizeof()` that includes the trailing NUL; match that.
        private val CLIENT_NAME: ByteArray = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        private val SERVER_NAME: ByteArray = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

        private val M_POINT: Ed25519Math.Point = affinePoint(M_X_LE, M_Y_LE)
        private val N_POINT: Ed25519Math.Point = affinePoint(N_X_LE, N_Y_LE)

        private fun affinePoint(xLe: ByteArray, yLe: ByteArray): Ed25519Math.Point {
            val x = Ed25519Math.fromLittleEndian(xLe)
            val y = Ed25519Math.fromLittleEndian(yLe)
            return Ed25519Math.Point(x, y, BigInteger.ONE, x.multiply(y).mod(Ed25519Math.P))
        }

        private fun randomScalar(rng: SecureRandom): BigInteger {
            val bytes = ByteArray(64)
            rng.nextBytes(bytes)
            return Ed25519Math.fromLittleEndian(bytes).mod(Ed25519Math.L)
        }
    }
}

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

import android.content.Context
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * RSA-2048 keypair used by the Wireless-ADB self-restart flow. ADB
 * authenticates the client by handing it a 20-byte random token; we sign
 * it with PKCS#1 v1.5 padding and no DigestInfo prefix, matching the
 * AOSP `RSA_sign(..., NID_sha1, ...)` call.
 *
 * The public key is also exported in ADB's legacy 524-byte struct so the
 * pairing flow can register it with the device.
 */
class AdbRsaKey internal constructor(
    val privateKey: RSAPrivateKey,
    val publicKey: RSAPublicKey,
    val userHost: String,
) {

    /**
     * Self-signed X.509 cert wrapping [publicKey], built lazily on first
     * access. The cert is content-free aside from the embedded key, so a
     * single instance can be reused across every connect.
     */
    val selfSignedCertificate: X509Certificate by lazy { AdbCertificate.selfSign(this) }

    /** Sign a token under PKCS#1 v1.5; the token is treated as the digest. */
    fun sign(token: ByteArray): ByteArray {
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(privateKey)
        sig.update(token)
        return sig.sign()
    }

    /**
     * Serialize the public key in the format adbd consumes: the 524-byte
     * RSAPublicKey struct, base64-encoded, then a space and a user@host
     * identifier terminated by a NUL byte.
     */
    fun adbAuthorizedKeyLine(): ByteArray {
        val struct = legacyRsaPublicKeyStruct(publicKey)
        val b64 = Base64.getEncoder().encodeToString(struct)
        val text = "$b64 $userHost"
        return text.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    }

    companion object {
        const val MODULUS_BITS: Int = 2048
        const val MODULUS_BYTES: Int = MODULUS_BITS / 8
        const val MODULUS_WORDS: Int = MODULUS_BYTES / 4
        private const val PRIVATE_FILE = "adb_rsa.pkcs8"
        private const val PUBLIC_FILE = "adb_rsa.x509"
        private const val USER_HOST = "simcountry@android"

        fun loadOrCreate(context: Context): AdbRsaKey {
            val privFile = File(context.filesDir, PRIVATE_FILE)
            val pubFile = File(context.filesDir, PUBLIC_FILE)
            return if (privFile.exists() && pubFile.exists()) {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes())) as RSAPrivateKey
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes())) as RSAPublicKey
                AdbRsaKey(priv, pub, USER_HOST)
            } else {
                val gen = KeyPairGenerator.getInstance("RSA")
                gen.initialize(MODULUS_BITS)
                val pair = gen.generateKeyPair()
                val priv = pair.private as RSAPrivateKey
                val pub = pair.public as RSAPublicKey
                privFile.writeBytes(priv.encoded)
                pubFile.writeBytes(pub.encoded)
                AdbRsaKey(priv, pub, USER_HOST)
            }
        }

        /**
         * The 524-byte adbd `RSAPublicKey` struct, little-endian:
         *   uint32 modulus_size_words   = 64 (256 bytes / 4)
         *   uint32 n0inv                = -1 / n[0] (mod 2^32)
         *   uint8  modulus[256]
         *   uint8  rr[256]              = R^2 mod n,  R = 2^2048
         *   uint32 exponent
         */
        internal fun legacyRsaPublicKeyStruct(pub: RSAPublicKey): ByteArray {
            val n = pub.modulus
            require(n.bitLength() in (MODULUS_BITS - 7)..MODULUS_BITS) {
                "modulus must be ~$MODULUS_BITS bits, was ${n.bitLength()}"
            }
            val e = pub.publicExponent.toInt()

            val r2 = BigInteger.ONE.shiftLeft(MODULUS_BITS * 2).mod(n)
            val twoTo32 = BigInteger.ONE.shiftLeft(32)
            val n0 = n.mod(twoTo32)
            // n0inv = -(n[0]^-1) mod 2^32
            val n0inv = twoTo32.subtract(n0.modInverse(twoTo32)).mod(twoTo32).toLong().toInt()

            val out = ByteBuffer.allocate(4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(MODULUS_WORDS)
            out.putInt(n0inv)
            out.put(toLittleEndianFixed(n, MODULUS_BYTES))
            out.put(toLittleEndianFixed(r2, MODULUS_BYTES))
            out.putInt(e)
            return out.array()
        }

        private fun toLittleEndianFixed(value: BigInteger, size: Int): ByteArray {
            val be = value.toByteArray() // big-endian, may have leading 0x00 sign byte
            val trimmed = if (be.size > size && be[0] == 0.toByte()) be.copyOfRange(1, be.size) else be
            require(trimmed.size <= size) { "value exceeds $size bytes" }
            val out = ByteArray(size)
            for (i in trimmed.indices) {
                out[i] = trimmed[trimmed.size - 1 - i]
            }
            return out
        }
    }
}

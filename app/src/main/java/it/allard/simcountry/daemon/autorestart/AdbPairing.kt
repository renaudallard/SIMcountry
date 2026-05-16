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

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * One-shot pairing against the Wireless-Debugging pair endpoint that adbd
 * advertises while the user has the "Pair device with pairing code" sheet
 * open. The user types the six-digit code from that sheet into the app;
 * this class drives the on-device pairing handshake so that the device
 * authorises our RSA public key for subsequent connects.
 *
 * Wire protocol matches AOSP `packages/modules/adb/pairing_connection/
 * pairing_connection.cpp` and `pairing_auth/`:
 *
 *   - TLSv1.3 with ALPN "adb"; the server cert is unverified, and we
 *     present our own self-signed cert (its public key is the RSA key
 *     adbd will store on success).
 *   - 6-byte packet framing: version=1, type, payload_size (network byte
 *     order). Type 0 = SPAKE2_MSG, type 1 = PEER_INFO.
 *   - SPAKE2_MSG: 32-byte Ed25519 point, exchanged plaintext.
 *   - PEER_INFO: AES-128-GCM(plaintext = 8192-byte PeerInfo). Key is
 *     derived via HKDF-SHA256(IKM = 64-byte SPAKE2 shared secret,
 *     salt = null, info = "adb pairing_auth aes-128-gcm key", L = 16).
 *     Nonce is a 12-byte buffer with the first 8 bytes holding a
 *     little-endian message counter (per direction); both encrypt and
 *     decrypt counters start at zero. AOSP's protocol reuses (key,
 *     nonce=0) for the two PEER_INFO exchanges in opposite directions;
 *     we replicate that for wire compatibility.
 *
 * NOTE: validated only against the AOSP source listing; needs a real
 * adbd run before this can be trusted as the autostart mechanism.
 */
class AdbPairing(
    private val key: AdbRsaKey,
    private val pairingCode: String,
) {

    fun pair(host: String, port: Int, timeoutMs: Int = 30_000): PeerInfo {
        Socket().use { plain ->
            plain.soTimeout = timeoutMs
            plain.connect(InetSocketAddress(host, port), timeoutMs)
            val ctx = AdbTls.newContext(key.selfSignedCertificate, key.privateKey)
            val tls = ctx.socketFactory.createSocket(plain, host, port, false) as SSLSocket
            tls.useClientMode = true
            AdbTls.applyAlpn(tls)
            tls.startHandshake()
            return tls.use { socket ->
                val sharedKey = exchangeSpake2(socket)
                val cipher = Aes128GcmStream(sharedKey)
                exchangePeerInfo(socket, cipher)
            }
        }
    }

    private fun exchangeSpake2(socket: SSLSocket): ByteArray {
        val spake = Spake2(Spake2.Role.CLIENT, pairingCode.toByteArray(Charsets.UTF_8))
        writePacket(socket.outputStream, TYPE_SPAKE2_MSG, spake.outboundMessage)
        val (type, payload) = readPacket(socket.inputStream)
        if (type != TYPE_SPAKE2_MSG) {
            throw IOException("expected SPAKE2_MSG (0), got type=$type")
        }
        if (payload.size != 32) {
            throw IOException("SPAKE2 payload must be 32 bytes, got ${payload.size}")
        }
        return spake.processPeerMessage(payload)
    }

    private fun exchangePeerInfo(socket: SSLSocket, cipher: Aes128GcmStream): PeerInfo {
        val ourPeerInfo = buildOurPeerInfo()
        val ourCiphertext = cipher.encrypt(ourPeerInfo)
        writePacket(socket.outputStream, TYPE_PEER_INFO, ourCiphertext)
        val (type, payload) = readPacket(socket.inputStream)
        if (type != TYPE_PEER_INFO) {
            throw IOException("expected PEER_INFO (1), got type=$type")
        }
        val theirPlaintext = cipher.decrypt(payload)
        if (theirPlaintext.size != PEER_INFO_SIZE) {
            throw IOException("decrypted PeerInfo size ${theirPlaintext.size} != $PEER_INFO_SIZE")
        }
        return PeerInfo(
            type = theirPlaintext[0],
            data = theirPlaintext.copyOfRange(1, PEER_INFO_SIZE),
        )
    }

    private fun buildOurPeerInfo(): ByteArray {
        val authLine = key.adbAuthorizedKeyLine()
        require(authLine.size <= PEER_INFO_SIZE - 1) {
            "ADB authorized key line too long: ${authLine.size}"
        }
        val out = ByteArray(PEER_INFO_SIZE)
        out[0] = TYPE_ADB_RSA_PUB_KEY
        authLine.copyInto(out, destinationOffset = 1)
        return out
    }

    private fun writePacket(out: OutputStream, type: Byte, payload: ByteArray) {
        val header = ByteArray(HEADER_SIZE)
        header[0] = HEADER_VERSION
        header[1] = type
        ByteBuffer.wrap(header, 2, 4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size)
        out.write(header)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readPacket(input: InputStream): Pair<Byte, ByteArray> {
        val header = ByteArray(HEADER_SIZE)
        readFully(input, header)
        val version = header[0]
        if (version != HEADER_VERSION) {
            throw IOException("unsupported pairing packet version $version")
        }
        val type = header[1]
        val payloadSize = ByteBuffer.wrap(header, 2, 4).order(ByteOrder.BIG_ENDIAN).int
        if (payloadSize < 0 || payloadSize > MAX_PAYLOAD) {
            throw IOException("payload size out of range: $payloadSize")
        }
        val payload = ByteArray(payloadSize).also { readFully(input, it) }
        return type to payload
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw EOFException("EOF after $read of ${buf.size} bytes")
            read += n
        }
    }

    data class PeerInfo(val type: Byte, val data: ByteArray)

    companion object {
        const val PEER_INFO_SIZE: Int = 8192
        const val TYPE_ADB_RSA_PUB_KEY: Byte = 0
        const val TYPE_ADB_DEVICE_GUID: Byte = 1

        private const val HEADER_SIZE: Int = 6
        private const val HEADER_VERSION: Byte = 1
        private const val TYPE_SPAKE2_MSG: Byte = 0
        private const val TYPE_PEER_INFO: Byte = 1
        private const val MAX_PAYLOAD: Int = PEER_INFO_SIZE * 2
    }

    internal class Aes128GcmStream(sharedKey: ByteArray) {
        private val derivedKey: SecretKeySpec
        private var encCounter: Long = 0L
        private var decCounter: Long = 0L

        init {
            val out = ByteArray(KEY_BYTES)
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(sharedKey, null, HKDF_INFO))
            hkdf.generateBytes(out, 0, KEY_BYTES)
            derivedKey = SecretKeySpec(out, "AES")
        }

        fun encrypt(plaintext: ByteArray): ByteArray {
            val nonce = nonceFor(encCounter)
            encCounter++
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, derivedKey, GCMParameterSpec(TAG_BITS, nonce))
            return cipher.doFinal(plaintext)
        }

        fun decrypt(ciphertext: ByteArray): ByteArray {
            val nonce = nonceFor(decCounter)
            decCounter++
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derivedKey, GCMParameterSpec(TAG_BITS, nonce))
            return cipher.doFinal(ciphertext)
        }

        private fun nonceFor(counter: Long): ByteArray {
            val nonce = ByteArray(NONCE_BYTES)
            ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).putLong(counter)
            return nonce
        }

        companion object {
            private const val KEY_BYTES = 16
            private const val NONCE_BYTES = 12
            private const val TAG_BITS = 128
            private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
        }
    }
}

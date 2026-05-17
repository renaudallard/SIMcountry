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
import javax.net.ssl.SSLSocket

/**
 * Speaks Wireless ADB to a connect endpoint and runs a single shell
 * command. Flow:
 *
 *   1. Plain TCP -> send CNXN.
 *   2. Server replies STLS.
 *   3. Send STLS, upgrade to TLSv1.3 with ALPN "adb" and a self-signed
 *      cert wrapping our RSA public key (already authorised through the
 *      pairing flow).
 *   4. Server sends CNXN over TLS confirming readiness.
 *   5. Send OPEN("shell:<cmd>"), drain the stream until CLSE.
 */
class AdbConnection(
    private val host: String,
    private val port: Int,
    private val key: AdbRsaKey,
) {

    /**
     * Run [command] over a fresh shell stream. Returns the combined stdout
     * collected before the server closed the stream. Throws on any
     * protocol-level deviation; the network socket and TLS socket are
     * always closed before returning.
     */
    fun executeShell(command: String, timeoutMs: Int = 30_000): String {
        Socket().use { plain ->
            plain.soTimeout = timeoutMs
            plain.connect(InetSocketAddress(host, port), timeoutMs)
            handshakeUpgrade(plain)
            val tls = upgradeToTls(plain)
            tls.use { socket ->
                readCnxnFromServer(socket)
                val remoteId = openShell(socket, command)
                return drainShell(socket, remoteId)
            }
        }
    }

    private fun handshakeUpgrade(plain: Socket) {
        val systemId = SYSTEM_ID_FEATURES.toByteArray(Charsets.US_ASCII) + 0
        writePacket(
            plain.getOutputStream(),
            AdbProtocol.CMD_CNXN,
            AdbProtocol.VERSION_SKIP_CHECKSUM,
            AdbProtocol.MAX_PAYLOAD,
            systemId,
        )
        val response = readPacket(plain.getInputStream())
        if (response.command != AdbProtocol.CMD_STLS) {
            throw ProtocolException(
                "expected STLS, got ${AdbProtocol.nameOf(response.command)}",
            )
        }
        writePacket(
            plain.getOutputStream(),
            AdbProtocol.CMD_STLS,
            STLS_VERSION,
            0,
            EMPTY,
        )
    }

    private fun upgradeToTls(plain: Socket): SSLSocket {
        val ctx = AdbTls.newContext(key.selfSignedCertificate, key.privateKey)
        val tls = ctx.socketFactory.createSocket(plain, host, port, false) as SSLSocket
        tls.useClientMode = true
        AdbTls.applyAlpn(tls)
        tls.startHandshake()
        return tls
    }

    private fun readCnxnFromServer(socket: SSLSocket) {
        // Modern adbd, when the TLS client cert matches a key authorised at
        // pairing time, replies straight with CNXN. Older adbd, or one that
        // has forgotten our key, runs the legacy AUTH dance instead:
        //
        //   server -> AUTH(TOKEN, <20-byte challenge>)
        //   client -> AUTH(SIGNATURE, <RSA-signed challenge>)
        //   [if server accepts]  server -> CNXN
        //   [if server rejects]  server -> AUTH(TOKEN, <new challenge>)
        //                        client -> AUTH(RSAPUBLICKEY, <ADB pubkey>)
        //                        user authorises on the device
        //                        server -> CNXN
        //
        // Walk through up to MAX_AUTH_ROUNDS before giving up.
        var sentSignature = false
        var sentPublicKey = false
        repeat(MAX_AUTH_ROUNDS) {
            val packet = readPacket(socket.inputStream)
            when (packet.command) {
                AdbProtocol.CMD_CNXN -> return
                AdbProtocol.CMD_AUTH -> {
                    if (packet.arg0 != AdbProtocol.AUTH_TOKEN) {
                        throw ProtocolException("unexpected AUTH subtype ${packet.arg0}")
                    }
                    if (packet.payload.size != AUTH_TOKEN_SIZE) {
                        throw ProtocolException(
                            "AUTH token must be $AUTH_TOKEN_SIZE bytes, got ${packet.payload.size}",
                        )
                    }
                    if (!sentSignature) {
                        writePacket(
                            socket.outputStream,
                            AdbProtocol.CMD_AUTH,
                            AdbProtocol.AUTH_SIGNATURE,
                            0,
                            key.sign(packet.payload),
                        )
                        sentSignature = true
                    } else if (!sentPublicKey) {
                        writePacket(
                            socket.outputStream,
                            AdbProtocol.CMD_AUTH,
                            AdbProtocol.AUTH_RSAPUBLICKEY,
                            0,
                            key.adbAuthorizedKeyLine(),
                        )
                        sentPublicKey = true
                    } else {
                        throw ProtocolException("adbd rejected our signature and public key")
                    }
                }
                else -> throw ProtocolException(
                    "expected CNXN/AUTH after TLS, got ${AdbProtocol.nameOf(packet.command)}",
                )
            }
        }
        throw ProtocolException("adbd kept requesting AUTH after $MAX_AUTH_ROUNDS rounds")
    }

    private fun openShell(socket: SSLSocket, command: String): Int {
        val payload = "shell:$command".toByteArray(Charsets.UTF_8) + 0
        writePacket(socket.outputStream, AdbProtocol.CMD_OPEN, OUR_LOCAL_ID, 0, payload)
        val okay = readPacket(socket.inputStream)
        if (okay.command != AdbProtocol.CMD_OKAY) {
            val reason = String(okay.payload, Charsets.UTF_8).trim().ifBlank { "no reason given" }
            throw ProtocolException(
                "shell open rejected (${AdbProtocol.nameOf(okay.command)}): $reason",
            )
        }
        if (okay.arg1 != OUR_LOCAL_ID) {
            throw ProtocolException(
                "OKAY arg1 ${okay.arg1} does not match our local id $OUR_LOCAL_ID",
            )
        }
        return okay.arg0
    }

    private fun drainShell(socket: SSLSocket, remoteId: Int): String {
        val output = StringBuilder()
        while (true) {
            val packet = readPacket(socket.inputStream)
            when (packet.command) {
                AdbProtocol.CMD_WRTE -> {
                    output.append(String(packet.payload, Charsets.UTF_8))
                    writePacket(socket.outputStream, AdbProtocol.CMD_OKAY, OUR_LOCAL_ID, remoteId, EMPTY)
                }
                AdbProtocol.CMD_CLSE -> {
                    writePacket(socket.outputStream, AdbProtocol.CMD_CLSE, OUR_LOCAL_ID, remoteId, EMPTY)
                    return output.toString()
                }
                AdbProtocol.CMD_OKAY -> {
                    // Mid-stream OKAY is harmless; the server simply confirmed a write.
                }
                else -> throw ProtocolException(
                    "unexpected ${AdbProtocol.nameOf(packet.command)} during shell stream",
                )
            }
        }
    }

    private fun writePacket(
        out: OutputStream,
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray,
    ) {
        val header = AdbProtocol.encodeHeader(command, arg0, arg1, payload, skipChecksum = true)
        out.write(header)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readPacket(input: InputStream): Packet {
        val header = ByteArray(AdbProtocol.HEADER_SIZE)
        readFully(input, header)
        val h = AdbProtocol.decodeHeader(header)
        val payload = if (h.dataLength > 0) {
            ByteArray(h.dataLength).also { readFully(input, it) }
        } else {
            EMPTY
        }
        return Packet(h.command, h.arg0, h.arg1, payload)
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw EOFException("EOF after $read of ${buf.size} bytes")
            read += n
        }
    }

    private data class Packet(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    )

    class ProtocolException(message: String) : IOException(message)

    companion object {
        // We only ever open a plain "shell:<cmd>" stream, so claiming
        // shell_v2 support in features=... would be misleading and would
        // also commit us to parsing v2's 5-byte sub-headers if adbd ever
        // decided to use them. Bare host:: works fine.
        private const val SYSTEM_ID_FEATURES = "host::"
        private const val OUR_LOCAL_ID = 1
        private const val STLS_VERSION = 1
        private const val AUTH_TOKEN_SIZE = 20
        private const val MAX_AUTH_ROUNDS = 4
        private val EMPTY = ByteArray(0)
    }
}

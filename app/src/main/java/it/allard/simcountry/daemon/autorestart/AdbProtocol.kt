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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB binary protocol primitives. Matches AOSP system/core/adb/protocol.txt.
 *
 * Each message is a 24-byte little-endian header followed by data_length
 * bytes of payload. The magic field equals command XOR 0xFFFFFFFF.
 *
 * Wireless ADB (Android 11+) negotiates A_STLS after the first CNXN and
 * tunnels every subsequent message inside TLS, with checksums disabled.
 */
object AdbProtocol {

    const val HEADER_SIZE: Int = 24
    const val MAX_PAYLOAD: Int = 1 shl 20

    /** A_CNXN: handshake announcing the system identity and capabilities. */
    const val CMD_CNXN: Int = 0x4E584E43

    /** A_AUTH: authentication challenge or response. */
    const val CMD_AUTH: Int = 0x48545541

    /** A_OPEN: client requests opening a stream (e.g. "shell:..."). */
    const val CMD_OPEN: Int = 0x4E45504F

    /** A_OKAY: stream-level acknowledgement. */
    const val CMD_OKAY: Int = 0x59414B4F

    /** A_CLSE: close a stream. */
    const val CMD_CLSE: Int = 0x45534C43

    /** A_WRTE: stream data. */
    const val CMD_WRTE: Int = 0x45545257

    /** A_SYNC: only used inside the file-sync sub-protocol. */
    const val CMD_SYNC: Int = 0x434E5953

    /** A_STLS: request to upgrade the connection to TLS (Android 11+). */
    const val CMD_STLS: Int = 0x534C5453

    /** Auth subtype: server hands a 20-byte SHA1-sized random challenge. */
    const val AUTH_TOKEN: Int = 1

    /** Auth subtype: client sends an RSA-PKCS1 signature of the challenge. */
    const val AUTH_SIGNATURE: Int = 2

    /** Auth subtype: client offers its public key in ADB legacy format. */
    const val AUTH_RSAPUBLICKEY: Int = 3

    /** ADB protocol revision that requires no checksums (TLS provides integrity). */
    const val VERSION_SKIP_CHECKSUM: Int = 0x01000001

    /** ADB protocol revision shipped before STLS. */
    const val VERSION_MIN: Int = 0x01000000

    private const val MAGIC_XOR: Int = -1 // 0xFFFFFFFF as signed int

    fun nameOf(command: Int): String = when (command) {
        CMD_CNXN -> "CNXN"
        CMD_AUTH -> "AUTH"
        CMD_OPEN -> "OPEN"
        CMD_OKAY -> "OKAY"
        CMD_CLSE -> "CLSE"
        CMD_WRTE -> "WRTE"
        CMD_SYNC -> "SYNC"
        CMD_STLS -> "STLS"
        else -> "0x%08X".format(command)
    }

    /**
     * Encode the 24-byte header of a packet. The payload bytes follow
     * directly after the returned header on the wire.
     *
     * When `skipChecksum` is true (the case for any TLS-wrapped session),
     * `data_check` is set to zero. The legacy "sum of payload bytes" path
     * is still supported for the initial pre-STLS exchange.
     */
    fun encodeHeader(
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray,
        skipChecksum: Boolean,
    ): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload too large: ${payload.size}" }
        val dataCheck = if (skipChecksum) 0 else payloadChecksum(payload)
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(dataCheck)
        buf.putInt(command xor MAGIC_XOR)
        return buf.array()
    }

    data class Header(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val dataCheck: Int,
    )

    /**
     * Parse a 24-byte header. Throws [IllegalArgumentException] when the
     * magic field does not match the command (a corrupt frame).
     */
    fun decodeHeader(header: ByteArray): Header {
        require(header.size == HEADER_SIZE) { "header must be $HEADER_SIZE bytes" }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val dataLength = buf.int
        val dataCheck = buf.int
        val magic = buf.int
        require(magic == (command xor MAGIC_XOR)) {
            "magic mismatch for ${nameOf(command)}"
        }
        require(dataLength in 0..MAX_PAYLOAD) {
            "data_length out of range: $dataLength"
        }
        return Header(command, arg0, arg1, dataLength, dataCheck)
    }

    /** Sum of payload bytes as unsigned, modulo 2^32. */
    fun payloadChecksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) sum += b.toInt() and 0xFF
        return sum
    }
}

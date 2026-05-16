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

import it.allard.simcountry.daemon.autorestart.AdbProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbProtocolTest {

    @Test fun cnxnCommandConstantMatchesAsciiLittleEndian() {
        // 'C' 'N' 'X' 'N' little-endian
        assertEquals(0x4E584E43, AdbProtocol.CMD_CNXN)
        assertEquals(0x48545541, AdbProtocol.CMD_AUTH)
        assertEquals(0x4E45504F, AdbProtocol.CMD_OPEN)
        assertEquals(0x59414B4F, AdbProtocol.CMD_OKAY)
        assertEquals(0x45534C43, AdbProtocol.CMD_CLSE)
        assertEquals(0x45545257, AdbProtocol.CMD_WRTE)
        assertEquals(0x534C5453, AdbProtocol.CMD_STLS)
    }

    @Test fun nameOfReturnsAdbMnemonic() {
        assertEquals("CNXN", AdbProtocol.nameOf(AdbProtocol.CMD_CNXN))
        assertEquals("STLS", AdbProtocol.nameOf(AdbProtocol.CMD_STLS))
        assertEquals("0x12345678", AdbProtocol.nameOf(0x12345678))
    }

    @Test fun encodeDecodeHeaderRoundTrip() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val header = AdbProtocol.encodeHeader(
            command = AdbProtocol.CMD_WRTE,
            arg0 = 7,
            arg1 = 11,
            payload = payload,
            skipChecksum = false,
        )
        assertEquals(AdbProtocol.HEADER_SIZE, header.size)
        val decoded = AdbProtocol.decodeHeader(header)
        assertEquals(AdbProtocol.CMD_WRTE, decoded.command)
        assertEquals(7, decoded.arg0)
        assertEquals(11, decoded.arg1)
        assertEquals(payload.size, decoded.dataLength)
        assertEquals(1 + 2 + 3 + 4, decoded.dataCheck)
    }

    @Test fun encodeWithSkipChecksumZeroesDataCheck() {
        val payload = byteArrayOf(0x10, 0x20, 0x30)
        val header = AdbProtocol.encodeHeader(
            command = AdbProtocol.CMD_OKAY,
            arg0 = 0,
            arg1 = 0,
            payload = payload,
            skipChecksum = true,
        )
        val decoded = AdbProtocol.decodeHeader(header)
        assertEquals(0, decoded.dataCheck)
        assertEquals(payload.size, decoded.dataLength)
    }

    @Test fun decodeRejectsCorruptMagic() {
        val good = AdbProtocol.encodeHeader(AdbProtocol.CMD_CNXN, 0, 0, ByteArray(0), true)
        val bad = good.copyOf()
        // Flip a byte in the magic field (last 4 bytes of the header).
        bad[20] = (bad[20].toInt() xor 0x55).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            AdbProtocol.decodeHeader(bad)
        }
    }

    @Test fun decodeRejectsWrongLengthHeader() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbProtocol.decodeHeader(ByteArray(20))
        }
    }

    @Test fun payloadChecksumIsUnsignedSum() {
        // signed -1 (0xFF) byte should sum as 255, not -1
        val payload = byteArrayOf(0xFF.toByte(), 0x01)
        assertEquals(256, AdbProtocol.payloadChecksum(payload))
    }

    @Test fun encodeRejectsOversizedPayload() {
        val tooBig = ByteArray(AdbProtocol.MAX_PAYLOAD + 1)
        assertThrows(IllegalArgumentException::class.java) {
            AdbProtocol.encodeHeader(AdbProtocol.CMD_WRTE, 0, 0, tooBig, true)
        }
    }

    @Test fun encodedHeaderLayoutIsLittleEndian() {
        val header = AdbProtocol.encodeHeader(
            command = AdbProtocol.CMD_CNXN,
            arg0 = AdbProtocol.VERSION_SKIP_CHECKSUM,
            arg1 = AdbProtocol.MAX_PAYLOAD,
            payload = ByteArray(0),
            skipChecksum = true,
        )
        // First four bytes are CMD_CNXN little-endian = 0x43 0x4E 0x58 0x4E
        assertArrayEquals(
            byteArrayOf(0x43, 0x4E, 0x58, 0x4E),
            header.copyOfRange(0, 4),
        )
    }
}

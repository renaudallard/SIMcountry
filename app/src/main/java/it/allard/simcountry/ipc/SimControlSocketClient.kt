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

package it.allard.simcountry.ipc

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Connects to the Rust daemon over 127.0.0.1:39351 and keeps the
 * connection alive with periodic pings. Originally targeted the abstract
 * Unix socket `\0simcountry-daemon`, but Android 16 SELinux blocks
 * untrusted_app -> shell `unix_stream_socket connectto`, so we tunnel
 * over loopback TCP instead. The phase 2 daemon only speaks ping/pong;
 * richer commands arrive in phase 3+. State is the surface the UI reads
 * to drive the green/red banner.
 */
class SimControlSocketClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val host: String = HOST,
    private val port: Int = PORT,
) {

    sealed interface State {
        data object Disconnected : State
        data object Connected : State
    }

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        scope.launch { reconnectLoop() }
    }

    private suspend fun reconnectLoop() {
        while (true) {
            try {
                withContext(Dispatchers.IO) { runConnection() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.v(TAG, "connection ended: ${e.message}")
            }
            _state.value = State.Disconnected
            delay(RECONNECT_DELAY_MS)
        }
    }

    private fun runConnection() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // Initial ping so we don't go Connected for any random listener.
            if (!roundtripPing(input, output)) {
                throw IOException("daemon did not pong on connect")
            }
            _state.value = State.Connected
            Log.i(TAG, "daemon socket connected")

            while (true) {
                Thread.sleep(KEEPALIVE_MS)
                if (!roundtripPing(input, output)) {
                    throw IOException("daemon did not pong on keepalive")
                }
            }
        }
    }

    private fun roundtripPing(input: DataInputStream, output: DataOutputStream): Boolean {
        writeFrame(output, "ping".toByteArray())
        val reply = readFrame(input)
        return reply.contentEquals("pong".toByteArray())
    }

    private fun writeFrame(out: DataOutputStream, payload: ByteArray) {
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val len = input.readInt()
        if (len < 0 || len > MAX_FRAME) {
            throw IOException("bad frame length $len")
        }
        val buf = ByteArray(len)
        input.readFully(buf)
        return buf
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 39351
        private const val TAG = "SimControlSocketClient"
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val KEEPALIVE_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 4_000
        private const val MAX_FRAME = 64 * 1024
    }
}

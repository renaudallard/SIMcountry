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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Talks to the Rust daemon over 127.0.0.1:39351 using length-prefixed
 * JSON frames. On connect we GetInfo to recover the daemon's version
 * and pid (drives the banner) and then keepalive with Ping every few
 * seconds. Disconnect triggers a reconnect with backoff.
 *
 * Schema mirrored on the Rust side as serde tagged enums with the same
 * "kind" discriminator and snake_case variant names.
 */
class SimControlSocketClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val host: String = HOST,
    private val port: Int = PORT,
) {

    sealed interface State {
        data object Disconnected : State
        data class Connected(val version: String, val pid: Int) : State
    }

    @Serializable
    sealed class Request {
        @Serializable
        @SerialName("ping")
        data object Ping : Request()

        @Serializable
        @SerialName("get_info")
        data object GetInfo : Request()
    }

    @Serializable
    sealed class Response {
        @Serializable
        @SerialName("pong")
        data object Pong : Response()

        @Serializable
        @SerialName("info")
        data class Info(val version: String, val pid: Int, val uid: Int) : Response()

        @Serializable
        @SerialName("error")
        data class Error(val message: String) : Response()
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

            val info = when (val r = roundtrip(input, output, Request.GetInfo)) {
                is Response.Info -> r
                else -> throw IOException("expected info on connect, got $r")
            }
            _state.value = State.Connected(version = info.version, pid = info.pid)
            Log.i(TAG, "daemon socket connected v=${info.version} pid=${info.pid} uid=${info.uid}")

            while (true) {
                Thread.sleep(KEEPALIVE_MS)
                val resp = roundtrip(input, output, Request.Ping)
                if (resp !is Response.Pong) {
                    throw IOException("ping got $resp")
                }
            }
        }
    }

    private fun roundtrip(
        input: DataInputStream,
        output: DataOutputStream,
        req: Request,
    ): Response {
        val reqBytes = WIRE.encodeToString(Request.serializer(), req).toByteArray()
        writeFrame(output, reqBytes)
        val replyBytes = readFrame(input)
        return WIRE.decodeFromString(Response.serializer(), String(replyBytes))
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

        private val WIRE = Json {
            classDiscriminator = "kind"
            ignoreUnknownKeys = true
        }
    }
}

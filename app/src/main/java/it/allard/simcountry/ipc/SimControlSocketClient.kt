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

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Talks to the Rust daemon over 127.0.0.1:39351 using length-prefixed
 * JSON frames. Authenticates with an apk-hash-derived HMAC handshake,
 * then runs a single long-lived connection: a Channel feeds caller
 * requests in; the connection coroutine alternates between draining
 * the channel and sending keepalive pings. Both keepalive and caller
 * commands share the same socket; replies are routed back via a
 * per-request CompletableDeferred.
 */
class SimControlSocketClient(
    context: Context,
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
        @Serializable @SerialName("hello") data object Hello : Request()
        @Serializable @SerialName("auth_response") data class AuthResponse(val hmac: String) : Request()
        @Serializable @SerialName("ping") data object Ping : Request()
        @Serializable @SerialName("get_info") data object GetInfo : Request()
        @Serializable @SerialName("get_default_data_sub_id") data object GetDefaultDataSubId : Request()
        @Serializable @SerialName("set_default_data_sub_id") data class SetDefaultDataSubId(@SerialName("sub_id") val subId: Int) : Request()
    }

    @Serializable
    sealed class Response {
        @Serializable @SerialName("challenge") data class Challenge(val nonce: String) : Response()
        @Serializable @SerialName("auth_ok") data object AuthOk : Response()
        @Serializable @SerialName("auth_fail") data class AuthFail(val message: String) : Response()
        @Serializable @SerialName("pong") data object Pong : Response()
        @Serializable @SerialName("info") data class Info(val version: String, val pid: Int, val uid: Int) : Response()
        @Serializable @SerialName("default_data_sub_id") data class DefaultDataSubId(@SerialName("sub_id") val subId: Int) : Response()
        @Serializable @SerialName("ok") data object Ok : Response()
        @Serializable @SerialName("error") data class Error(val message: String) : Response()
    }

    private data class Pending(val request: Request, val reply: CompletableDeferred<Response>)

    private val apkPath: String = context.applicationInfo.sourceDir
    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()
    private val commands = Channel<Pending>(Channel.UNLIMITED)

    init {
        scope.launch { reconnectLoop() }
    }

    /** Execute a request against the daemon. Throws IOException if no connection. */
    suspend fun execute(req: Request): Response {
        val pending = Pending(req, CompletableDeferred())
        commands.send(pending)
        return pending.reply.await()
    }

    suspend fun getDefaultDataSubId(): Int {
        return when (val r = execute(Request.GetDefaultDataSubId)) {
            is Response.DefaultDataSubId -> r.subId
            is Response.Error -> throw IOException(r.message)
            else -> throw IOException("unexpected response: $r")
        }
    }

    suspend fun setDefaultDataSubId(subId: Int) {
        when (val r = execute(Request.SetDefaultDataSubId(subId))) {
            is Response.Ok -> Unit
            is Response.Error -> throw IOException(r.message)
            else -> throw IOException("unexpected response: $r")
        }
    }

    private suspend fun reconnectLoop() {
        while (currentCoroutineContext().isActive) {
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

    private suspend fun runConnection() {
        val apkHash = sha256(File(apkPath))
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            handshake(input, output, apkHash)

            val info = when (val r = roundtrip(input, output, Request.GetInfo)) {
                is Response.Info -> r
                else -> throw IOException("expected info on connect, got $r")
            }
            _state.value = State.Connected(version = info.version, pid = info.pid)
            Log.i(TAG, "daemon authed v=${info.version} pid=${info.pid} uid=${info.uid}")

            try {
                while (currentCoroutineContext().isActive) {
                    val pending = withTimeoutOrNull(KEEPALIVE_MS) { commands.receive() }
                    if (pending == null) {
                        // No caller traffic in the last KEEPALIVE window; send a ping
                        // so we notice dead connections promptly.
                        val resp = roundtrip(input, output, Request.Ping)
                        if (resp !is Response.Pong) {
                            throw IOException("keepalive got $resp")
                        }
                    } else {
                        try {
                            val resp = roundtrip(input, output, pending.request)
                            pending.reply.complete(resp)
                        } catch (e: Throwable) {
                            pending.reply.completeExceptionally(e)
                            throw e
                        }
                    }
                }
            } finally {
                _state.value = State.Disconnected
            }
        }
    }

    private fun handshake(input: DataInputStream, output: DataOutputStream, apkHash: ByteArray) {
        val challenge = when (val r = roundtrip(input, output, Request.Hello)) {
            is Response.Challenge -> r
            else -> throw IOException("expected challenge, got $r")
        }
        val nonce = hexDecode(challenge.nonce)
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(apkHash, "HmacSHA256"))
        }
        val tag = mac.doFinal(nonce)
        when (val r = roundtrip(input, output, Request.AuthResponse(hmac = hexEncode(tag)))) {
            is Response.AuthOk -> Unit
            is Response.AuthFail -> throw IOException("auth fail: ${r.message}")
            else -> throw IOException("expected auth_ok, got $r")
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

    private fun sha256(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    private fun hexEncode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            out.append(HEX[(b.toInt() ushr 4) and 0xf])
            out.append(HEX[b.toInt() and 0xf])
        }
        return out.toString()
    }

    private fun hexDecode(s: String): ByteArray {
        require(s.length % 2 == 0) { "hex length must be even" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "non-hex char" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
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
        private val HEX = "0123456789abcdef".toCharArray()

        private val WIRE = Json {
            classDiscriminator = "kind"
            ignoreUnknownKeys = true
        }
    }
}

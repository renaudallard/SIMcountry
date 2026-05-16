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

import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS plumbing for Wireless ADB. The pairing and connect endpoints both
 * speak TLSv1.3 with ALPN "adb"; the certificate chain is self-signed by
 * the device and we deliberately do not verify it (the underlying pairing
 * protocol authenticates the exchange separately).
 */
object AdbTls {

    /** ALPN protocol identifier as defined by adbd. */
    const val ALPN_PROTOCOL: String = "adb"

    /**
     * Build an [SSLContext] that presents [clientCert] / [clientKey] as the
     * client certificate when adbd asks for one (the pairing flow does),
     * and trusts the server certificate unconditionally.
     */
    fun newContext(
        clientCert: X509Certificate? = null,
        clientKey: java.security.PrivateKey? = null,
    ): SSLContext {
        val ctx = SSLContext.getInstance("TLSv1.3")
        val keyManagers: Array<KeyManager>? = if (clientCert != null && clientKey != null) {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setKeyEntry("adb", clientKey, CharArray(0), arrayOf(clientCert))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, CharArray(0))
            kmf.keyManagers
        } else {
            null
        }
        ctx.init(keyManagers, arrayOf<TrustManager>(TRUST_ALL), SecureRandom())
        return ctx
    }

    /** Apply ALPN "adb" to the socket. Must be called before the handshake. */
    fun applyAlpn(socket: SSLSocket) {
        val params: SSLParameters = socket.sslParameters
        params.applicationProtocols = arrayOf(ALPN_PROTOCOL)
        socket.sslParameters = params
    }

    private val TRUST_ALL: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

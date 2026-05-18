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

import org.conscrypt.Conscrypt
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
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
    /**
     * Bundled Conscrypt provider, kept scoped to this object so SSLContexts
     * built via [newPairingContext] use Conscrypt (which exposes
     * `exportKeyingMaterial`) without affecting the rest of the app's TLS.
     */
    private val conscryptProvider: Provider = Conscrypt.newProvider()

    /**
     * Context for the pair step. Backed by the bundled Conscrypt provider
     * so [AdbPairing] can call `Conscrypt.exportKeyingMaterial` on the
     * resulting socket. Pair-side TLS still presents the client cert --
     * adbd's PairingConnectionCtx requires non-empty cert and key
     * regardless of the SPAKE2 layer riding on top.
     */
    fun newPairingContext(
        clientCert: X509Certificate,
        clientKey: java.security.PrivateKey,
    ): SSLContext = build(useConscrypt = true, clientCert = clientCert, clientKey = clientKey)

    /**
     * Context for the connect step. Uses the platform default TLS
     * provider, which mutual-auths cleanly with the paired client cert.
     * Conscrypt's TLSv1.3 client-auth on this device drops the
     * Certificate message, triggering the server's CERTIFICATE_REQUIRED
     * alert; the platform provider does not.
     */
    fun newContext(
        clientCert: X509Certificate? = null,
        clientKey: java.security.PrivateKey? = null,
    ): SSLContext = build(useConscrypt = false, clientCert = clientCert, clientKey = clientKey)

    private fun build(
        useConscrypt: Boolean,
        clientCert: X509Certificate? = null,
        clientKey: PrivateKey? = null,
    ): SSLContext {
        val ctx = if (useConscrypt) {
            SSLContext.getInstance("TLSv1.3", conscryptProvider)
        } else {
            SSLContext.getInstance("TLSv1.3")
        }
        val keyManagers: Array<KeyManager>? = if (clientCert != null && clientKey != null) {
            arrayOf(FixedKeyManager(clientCert, clientKey))
        } else {
            null
        }
        ctx.init(keyManagers, arrayOf<TrustManager>(TRUST_ALL), SecureRandom())
        return ctx
    }

    /**
     * KeyManager that returns our cert and key unconditionally. The default
     * SunX509-style KeyManager filters by the server's
     * certificate_authorities and signature_algorithms hints in the
     * TLSv1.3 CertificateRequest; adbd's self-signed-handshake pattern
     * does not include our cert in those hints, so the default sends an
     * empty Certificate message and the server tears the connection down
     * with TLSV1_ALERT_CERTIFICATE_REQUIRED.
     */
    private class FixedKeyManager(
        private val cert: X509Certificate,
        private val key: PrivateKey,
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
            arrayOf(ALIAS)
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String = ALIAS
        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ): String = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String? = null
        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ): String? = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)
        override fun getPrivateKey(alias: String?): PrivateKey = key

        companion object {
            private const val ALIAS = "adb"
        }
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

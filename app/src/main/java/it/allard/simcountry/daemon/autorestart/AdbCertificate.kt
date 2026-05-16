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

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Self-signed X.509 cert that wraps our RSA public key. Wireless ADB's
 * connect endpoint requires the client to present a certificate during
 * the TLS handshake; the certificate's contents do not matter because
 * adbd identifies the client by checking the embedded public key against
 * its trust list (populated during pairing).
 */
object AdbCertificate {

    /**
     * Build a self-signed certificate valid for ten years, signed by the
     * key inside [adbKey]. The subject and issuer DN are both `CN=adb`.
     */
    fun selfSign(adbKey: AdbRsaKey): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - DAY_MS)
        val notAfter = Date(now + TEN_YEARS_MS)
        val subject = X500Name("CN=adb")
        val serial = BigInteger.valueOf(now)
        val builder = JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, adbKey.publicKey,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(adbKey.privateKey)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private const val DAY_MS: Long = 24L * 60 * 60 * 1000
    private const val TEN_YEARS_MS: Long = 10L * 365 * DAY_MS
}

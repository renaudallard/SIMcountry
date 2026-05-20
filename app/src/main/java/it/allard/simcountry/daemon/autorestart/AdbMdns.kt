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

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentSkipListSet

/**
 * Discovers ADB's Wireless-Debugging endpoints over mDNS. adbd advertises
 *
 *   `_adb-tls-pairing._tcp.` while the user has the "Pair device with
 *   pairing code" sheet open in Developer Options,
 *
 *   `_adb-tls-connect._tcp.` whenever Wireless Debugging is enabled.
 *
 * Both endpoints are local to the device. We connect to 127.0.0.1 with
 * the discovered port number.
 */
object AdbMdns {

    const val SERVICE_TYPE_PAIRING: String = "_adb-tls-pairing._tcp."
    const val SERVICE_TYPE_CONNECT: String = "_adb-tls-connect._tcp."
    private const val TAG = "AdbMdns"

    /**
     * Discover the first matching ADB service on the device's mDNS responder
     * and return its TCP port. Returns null if no service is announced or
     * resolved within [timeoutMs]. Thin wrapper over [findPorts] for
     * single-result use cases (pair endpoint).
     */
    suspend fun findPort(context: Context, serviceType: String, timeoutMs: Long): Int? =
        findPorts(context, serviceType, timeoutMs).firstOrNull()

    /**
     * Collect every port advertised on the device's mDNS responder for
     * [serviceType] within [timeoutMs]. On builds where the responder
     * lingers stale advertisements alongside the live one (observed on
     * Motorola Lhotse / Android 16, where the connect-endpoint port
     * mutates on every Wireless Debugging toggle but NSD still returns
     * older instances), the caller can probe each candidate to find
     * the one that actually listens. Results are deduplicated and
     * returned in the order NSD resolved them.
     */
    suspend fun findPorts(context: Context, serviceType: String, timeoutMs: Long): List<Int> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val ports = ConcurrentSkipListSet<Int>()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(s: String) {}
            override fun onDiscoveryStopped(s: String) {}
            override fun onStartDiscoveryFailed(s: String, errorCode: Int) {
                Log.w(TAG, "discovery start failed for $s: $errorCode")
            }

            override fun onStopDiscoveryFailed(s: String, errorCode: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                Log.i(TAG, "found ${info.serviceName} of type ${info.serviceType}")
                // NsdManager.resolveService requires a fresh listener per
                // call when multiple resolves are in flight, so allocate one
                // for each match.
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "resolve failed for ${failed.serviceName}: $errorCode")
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        Log.i(TAG, "resolved ${resolved.serviceName} -> ${resolved.host}:${resolved.port}")
                        ports.add(resolved.port)
                    }
                }
                try {
                    nsd.resolveService(info, resolveListener)
                } catch (t: Throwable) {
                    Log.w(TAG, "resolveService threw", t)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
        }

        try {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (t: Throwable) {
            Log.w(TAG, "discoverServices threw", t)
            return emptyList()
        }

        try {
            withTimeoutOrNull(timeoutMs) {
                // Stay in the discovery window for the full budget so
                // multiple advertisements (stale + live) all surface.
                delay(timeoutMs)
            }
        } finally {
            try {
                nsd.stopServiceDiscovery(discoveryListener)
            } catch (_: Throwable) {
            }
        }
        return ports.toList()
    }
}

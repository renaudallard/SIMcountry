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

package it.allard.simcountry.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import it.allard.simcountry.SimcountryApp
import it.allard.simcountry.daemon.autorestart.AutostartCoordinator

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val triggers = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // Delivered to our own app only, after our APK is replaced.
            // The previously-running daemon was launched from the now-old
            // install path with the old APK hash, so its auth handshake
            // rejects our fresh client; running reconnect tears it down
            // (the autostart command pkills its peers) and starts the
            // updated binary.
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
        if (intent.action !in triggers) return
        val app = context.applicationContext as? SimcountryApp
        val paired = app?.container?.autostart?.state?.value is AutostartCoordinator.State.Paired
        if (paired) {
            CountryWatcherService.startWithReconnect(context)
        } else {
            CountryWatcherService.start(context)
        }
    }
}

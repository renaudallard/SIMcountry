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

package it.allard.simcountry.data

import android.content.Context
import it.allard.simcountry.daemon.autorestart.AutostartCoordinator
import it.allard.simcountry.ipc.SimControlClient
import it.allard.simcountry.ipc.SimControlSocketClient
import it.allard.simcountry.rules.RulesStore
import it.allard.simcountry.telephony.KeyguardGate
import it.allard.simcountry.telephony.OverrideDetector
import it.allard.simcountry.telephony.SimRegistry

class AppContainer(context: Context) {
    private val app = context.applicationContext
    val rulesStore: RulesStore = RulesStore(app)
    val simControlClient: SimControlClient = SimControlClient()
    val simControlSocketClient: SimControlSocketClient = SimControlSocketClient()
    val simRegistry: SimRegistry = SimRegistry(app, simControlClient)
    val overrideDetector: OverrideDetector = OverrideDetector(app)
    val keyguardGate: KeyguardGate = KeyguardGate(app)
    val autostart: AutostartCoordinator = AutostartCoordinator(app)
}

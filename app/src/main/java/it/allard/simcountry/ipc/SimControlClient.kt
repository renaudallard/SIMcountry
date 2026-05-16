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

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SimControlClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(State.Disconnected as State)
    val state: StateFlow<State> = _state.asStateFlow()

    private var deathRecipient: IBinder.DeathRecipient? = null

    init {
        scope.launch {
            BinderHolder.binder.collect { b -> onBinder(b) }
        }
    }

    private fun onBinder(b: IBinder?) {
        unhookDeath()
        if (b == null) {
            _state.value = State.Disconnected
            return
        }
        val iface = ISimControl.Stub.asInterface(b)
        val version = try {
            iface.version()
        } catch (e: RemoteException) {
            Log.w(TAG, "version() failed", e)
            BinderHolder.set(null)
            return
        }
        val pid = try {
            iface.pid()
        } catch (e: RemoteException) {
            -1L
        }
        val dr = IBinder.DeathRecipient {
            Log.w(TAG, "daemon binder died")
            BinderHolder.set(null)
        }
        try {
            b.linkToDeath(dr, 0)
            deathRecipient = dr
        } catch (e: RemoteException) {
            Log.w(TAG, "linkToDeath failed", e)
            BinderHolder.set(null)
            return
        }
        _state.value = State.Connected(iface, version, pid)
    }

    private fun unhookDeath() {
        val s = _state.value
        val b = (s as? State.Connected)?.iface?.asBinder()
        val dr = deathRecipient
        if (b != null && dr != null) {
            try {
                b.unlinkToDeath(dr, 0)
            } catch (_: NoSuchElementException) {
            }
        }
        deathRecipient = null
    }

    val iface: ISimControl?
        get() = (state.value as? State.Connected)?.iface

    sealed interface State {
        data object Disconnected : State
        data class Connected(val iface: ISimControl, val version: String, val pid: Long) : State
    }

    companion object {
        private const val TAG = "SimControlClient"
    }
}

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

package it.allard.simcountry.daemon

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.util.Log
import it.allard.simcountry.ipc.SimControlProvider
import kotlin.system.exitProcess

object DaemonEntrypoint {

    private const val TAG = "SimcountryDaemon"
    private const val APP_PACKAGE = "it.allard.simcountry"

    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val pid = Process.myPid()
        val uid = Process.myUid()
        Log.i(TAG, "starting uid=$uid pid=$pid")
        if (uid != 2000 && uid != 0) {
            Log.e(TAG, "must run as shell or root (uid=$uid); aborting")
            exitProcess(3)
        }

        val context = bootstrapContext()
        val server = SimControlServer(context)
        val targetPkg = pickTargetPackage(args, context)
        val authority = "$targetPkg.simcontrol"
        val uri = Uri.parse("content://$authority")

        val extras = Bundle().apply { putBinder(SimControlProvider.KEY_BINDER, server.asBinder()) }
        val result = try {
            context.contentResolver.call(uri, SimControlProvider.METHOD_ATTACH, null, extras)
        } catch (t: Throwable) {
            Log.e(TAG, "attachShell call threw against $authority", t)
            exitProcess(4)
        }
        if (result?.getBoolean("ok") != true) {
            Log.e(TAG, "attachShell rejected by $authority")
            exitProcess(5)
        }
        Log.i(TAG, "attached to $authority")

        Looper.loop()
    }

    private fun bootstrapContext(): Context {
        val atClass = Class.forName("android.app.ActivityThread")
        val at = atClass.getDeclaredMethod("systemMain").invoke(null)
        return atClass.getDeclaredMethod("getSystemContext").invoke(at) as Context
    }

    private fun pickTargetPackage(args: Array<String>, context: Context): String {
        if (args.isNotEmpty() && args[0].isNotBlank()) return args[0]
        val candidates = listOf("$APP_PACKAGE.debug", APP_PACKAGE)
        val pm = context.packageManager
        for (c in candidates) {
            try {
                pm.getPackageInfo(c, 0)
                return c
            } catch (_: Exception) {
            }
        }
        Log.e(TAG, "neither candidate installed: $candidates; pass the target package as arg[0]")
        return APP_PACKAGE
    }
}

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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.euicc.EuiccManager
import android.util.Log
import it.allard.simcountry.ipc.SubInfo
import java.lang.reflect.InvocationTargetException

class HiddenApi(private val context: Context) {

    private val sm: SubscriptionManager =
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    private val euicc: EuiccManager? =
        context.getSystemService(Context.EUICC_SERVICE) as? EuiccManager

    fun listAllSubscriptions(): List<SubInfo> {
        val active = sm.activeSubscriptionInfoList ?: emptyList()
        val activeIds = active.map { it.subscriptionId }.toSet()
        val all = invokeOrNull<List<SubscriptionInfo>>(sm, "getAvailableSubscriptionInfoList")
            ?: invokeOrNull<List<SubscriptionInfo>>(sm, "getCompleteActiveSubscriptionInfoList")
            ?: active
        return all.map { si ->
            SubInfo(
                subId = si.subscriptionId,
                iccid = (invokeOrNull<String>(si, "getIccId") ?: "").trim(),
                carrierName = si.carrierName?.toString() ?: "",
                displayName = si.displayName?.toString() ?: "",
                mcc = si.mccString,
                mnc = si.mncString,
                isEmbedded = si.isEmbedded,
                isActive = activeIds.contains(si.subscriptionId),
            )
        }
    }

    fun getDefaultDataSubId(): Int = SubscriptionManager.getDefaultDataSubscriptionId()
    fun getDefaultVoiceSubId(): Int = SubscriptionManager.getDefaultVoiceSubscriptionId()
    fun getDefaultSmsSubId(): Int = SubscriptionManager.getDefaultSmsSubscriptionId()

    fun setDefaultDataSubId(subId: Int) {
        invokeOrThrow(sm, "setDefaultDataSubId", Int::class.javaPrimitiveType!! to subId)
    }

    fun setDefaultVoiceSubId(subId: Int) {
        val ok = invokeIfPresent(sm, "setDefaultVoiceSubId", Int::class.javaPrimitiveType!! to subId)
            || invokeIfPresent(sm, "setDefaultVoiceSubscriptionId", Int::class.javaPrimitiveType!! to subId)
        if (!ok) throw NoSuchMethodException("setDefaultVoiceSubId / setDefaultVoiceSubscriptionId not found")
    }

    fun setDefaultSmsSubId(subId: Int) {
        val ok = invokeIfPresent(sm, "setDefaultSmsSubId", Int::class.javaPrimitiveType!! to subId)
            || invokeIfPresent(sm, "setDefaultSmsSubscriptionId", Int::class.javaPrimitiveType!! to subId)
        if (!ok) throw NoSuchMethodException("setDefaultSmsSubId / setDefaultSmsSubscriptionId not found")
    }

    fun activateEsimByIccid(iccid: String) {
        val mgr = euicc ?: throw UnsupportedOperationException("EuiccManager unavailable")
        if (!mgr.isEnabled) throw UnsupportedOperationException("eSIM not enabled on device")
        val target = listAllSubscriptions().firstOrNull { it.iccid == iccid && it.isEmbedded }
            ?: throw IllegalArgumentException("no eSIM profile with iccid=$iccid")
        if (target.isActive) return
        val shellCtx = try {
            context.createPackageContext("com.android.shell", 0)
        } catch (t: Throwable) {
            Log.w(TAG, "createPackageContext(shell) failed; falling back to system context", t)
            context
        }
        val intent = Intent(ACTION_ESIM_SWITCH_RESULT).setPackage(shellCtx.packageName)
        val pi = PendingIntent.getBroadcast(
            shellCtx,
            target.subId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            mgr.switchToSubscription(target.subId, pi)
        } catch (se: SecurityException) {
            Log.w(TAG, "switchToSubscription rejected; shell UID lacks WRITE_EMBEDDED_SUBSCRIPTIONS on this device", se)
            throw se
        }
    }

    private inline fun <reified T> invokeOrNull(target: Any, name: String, vararg args: Pair<Class<*>, Any?>): T? {
        return try {
            val types = args.map { it.first }.toTypedArray()
            val values = args.map { it.second }.toTypedArray()
            val m = target.javaClass.getMethod(name, *types)
            val r = m.invoke(target, *values)
            @Suppress("UNCHECKED_CAST")
            r as? T
        } catch (_: NoSuchMethodException) {
            null
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun invokeIfPresent(target: Any, name: String, vararg args: Pair<Class<*>, Any?>): Boolean {
        return try {
            val types = args.map { it.first }.toTypedArray()
            val values = args.map { it.second }.toTypedArray()
            val m = target.javaClass.getMethod(name, *types)
            m.invoke(target, *values)
            true
        } catch (_: NoSuchMethodException) {
            false
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun invokeOrThrow(target: Any, name: String, vararg args: Pair<Class<*>, Any?>): Any? {
        val types = args.map { it.first }.toTypedArray()
        val values = args.map { it.second }.toTypedArray()
        val m = try {
            target.javaClass.getMethod(name, *types)
        } catch (e: NoSuchMethodException) {
            throw NoSuchMethodException("${target.javaClass.simpleName}.$name not found on SDK ${android.os.Build.VERSION.SDK_INT}")
        }
        return try {
            m.invoke(target, *values)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    companion object {
        private const val TAG = "HiddenApi"
        const val ACTION_ESIM_SWITCH_RESULT = "it.allard.simcountry.ESIM_SWITCH_RESULT"
    }
}

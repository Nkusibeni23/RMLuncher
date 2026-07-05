package com.rmsoft.launcher.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.util.Log

/**
 * Enforces the RMSoft OS **eSIM-only** rule: the device may use an embedded (eSIM) profile but every
 * physical (removable) SIM is disabled, so a stolen phone can't be given a new physical SIM to dodge
 * tracking.
 *
 * Identifying physical vs embedded SIMs uses the public [android.telephony.SubscriptionInfo.isEmbedded]
 * (API 28+). Disabling one uses `SubscriptionManager.setUiccApplicationsEnabled(subId, false)` — a
 * privileged @SystemApi that needs MODIFY_PHONE_STATE, which RMLauncher holds only as a baked
 * privileged system app in RMSoft OS. It's called by reflection so the app still compiles against the
 * normal SDK, and every failure is a safe no-op (e.g. a sideloaded debug build without the privilege).
 */
object SimPolicy {
    private const val TAG = "SimPolicy"

    @SuppressLint("MissingPermission")
    fun enforceEsimOnly(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unsupported (< API 29)"
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            ?: return "no SubscriptionManager"

        val subs = runCatching { sm.activeSubscriptionInfoList }.getOrNull()
            ?: return "no active subscriptions (missing READ_PHONE_STATE?)"

        val physical = subs.filter { !it.isEmbedded }
        if (physical.isEmpty()) return "no physical SIM present"

        var disabled = 0
        physical.forEach { info ->
            runCatching {
                val m = SubscriptionManager::class.java.getMethod(
                    "setUiccApplicationsEnabled",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
                m.invoke(sm, info.subscriptionId, false)
                disabled++
                Log.i(TAG, "disabled physical SIM sub=${info.subscriptionId}")
            }.onFailure { Log.w(TAG, "could not disable sub=${info.subscriptionId}: ${it.message}") }
        }
        return if (disabled > 0) "disabled $disabled physical SIM(s)"
        else "physical SIM present but disable failed (needs MODIFY_PHONE_STATE / privileged)"
    }
}

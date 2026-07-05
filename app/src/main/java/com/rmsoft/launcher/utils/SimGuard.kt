package com.rmsoft.launcher.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import android.util.Log
import com.rmsoft.launcher.remote.RemoteConfig

/**
 * Anti-theft SIM-swap detector. Fingerprints the active SIM(s) at enrollment; if the SIM later
 * changes to a different one, the phone is very likely stolen — so we **lock immediately** and queue
 * a server alert (delivered on the next reconnect, so it survives being offline).
 *
 * Pairs with the eSIM-only policy ([SimPolicy]): a thief can't fit a physical SIM, and swapping the
 * eSIM profile changes the ICCID, which this catches.
 */
object SimGuard {
    private const val TAG = "SimGuard"

    @SuppressLint("MissingPermission")
    fun currentFingerprint(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unsupported"
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            ?: return "none"
        val subs = runCatching { sm.activeSubscriptionInfoList }.getOrNull()
        if (subs.isNullOrEmpty()) return "none"
        // ICCID is the SIM's stable serial (readable as a privileged system app); fall back to the
        // subscription id when it isn't. Sort so ordering doesn't cause false positives.
        return subs
            .mapNotNull { info ->
                runCatching { info.iccId }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: "sub${info.subscriptionId}"
            }
            .sorted()
            .joinToString(",")
    }

    /**
     * Compare the current SIM against the enrollment baseline. The first call establishes the
     * baseline. A change to a different, non-empty SIM = swap → lock the phone + queue a server
     * alert. Returns true when a swap was detected.
     */
    fun check(context: Context): Boolean {
        val current = currentFingerprint(context)
        val baseline = RemoteConfig.simBaseline(context)
        if (baseline == null) {
            RemoteConfig.setSimBaseline(context, current)
            Log.i(TAG, "SIM baseline established")
            return false
        }
        if (current != baseline && current != "none") {
            Log.w(TAG, "SIM swap detected")
            // Immediate defensive lock — works even with no network.
            runCatching { DeviceOwnerManager(context).lockNow() }
            RemoteConfig.setPendingAlert(context, "SIM_SWAP", "SIM changed on device")
            // Adopt the new fingerprint so we alert once per distinct SIM, not on every check.
            RemoteConfig.setSimBaseline(context, current)
            return true
        }
        return false
    }
}

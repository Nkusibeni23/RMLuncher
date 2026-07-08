package com.rmsoft.launcher.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rmsoft.launcher.remote.AgentService

/**
 * Background-agent mode: on boot (and after a self-update), grant the baseline Device Owner policies
 * (runtime permissions, location, tamper hardening — NOT kiosk) and start the invisible MDM agent.
 * RMSoft OS keeps its normal launcher, so we do NOT launch any UI here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Baseline policies only (grants location so the agent never crashes; no kiosk).
                // No-op if not provisioned as Device Owner.
                DeviceOwnerManager(context).applyBaselinePolicies()

                // Start the silent MDM device agent (MQTT: lock/wipe/track/ring/…).
                AgentService.start(context)

                // First-run Welcome (user-facing splash + RMSoft Mail): show once, only on a real
                // boot — not on an app self-update. No-op after it has been seen/dismissed.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    com.rmsoft.launcher.ui.WelcomeActivity.launchIfFirstRun(context)
                }
            }
        }
    }
}

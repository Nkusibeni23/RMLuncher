package com.rmsoft.launcher.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rmsoft.launcher.remote.AgentService
import com.rmsoft.launcher.ui.LauncherActivity

/**
 * Automatically launches the RMSOFT launcher after device reboot.
 * Ensures the device always boots into the locked-down environment.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Re-assert every Device Owner policy on boot — this also hides all non-whitelisted
            // apps and re-pins this launcher as Home. No-op if not provisioned as Device Owner.
            DeviceOwnerManager(context).applyAllPolicies()

            // Start the MDM device agent so the device checks in even before the UI is shown.
            AgentService.start(context)

            val launchIntent = Intent(context, LauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launchIntent)
        }
    }
}

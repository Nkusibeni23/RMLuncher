package com.rmsoft.launcher.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rmsoft.launcher.remote.AgentService
import com.rmsoft.launcher.ui.LauncherActivity

/**
 * Brings the RMSOFT launcher straight back to the foreground after a **reboot** and after the app
 * **updates itself** ([Intent.ACTION_MY_PACKAGE_REPLACED]). The latter collapses the brief flash of
 * the stock launcher that the OS shows while our package is being replaced during an update.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Re-assert every Device Owner policy — hides non-whitelisted apps, re-pins this
                // launcher as Home, re-grants permissions. No-op if not provisioned as Device Owner.
                DeviceOwnerManager(context).applyAllPolicies()

                // Start the MDM device agent so the device checks in even before the UI is shown.
                AgentService.start(context)

                context.startActivity(
                    Intent(context, LauncherActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }
        }
    }
}

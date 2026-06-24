package com.rmsoft.launcher.utils

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast
import com.rmsoft.launcher.remote.RemoteConfig
import com.rmsoft.launcher.ui.LauncherActivity

/**
 * Device admin component for the RMSOFT Android Enterprise launcher.
 *
 * This receiver is what makes the app eligible to be set as **Device Owner** — via QR/EMM
 * provisioning on a freshly factory-reset device, or via
 * `adb shell dpm set-device-owner com.rmsoft.launcher/.utils.RMSOFTAdminReceiver` in the lab.
 *
 * Its lifecycle callbacks bootstrap and protect the kiosk:
 *  - [onProfileProvisioningComplete] — first-boot QR provisioning: apply every lockdown policy
 *    and pin this launcher as the default Home app, then launch it.
 *  - [onEnabled] — admin (re)activated: re-assert the full policy set.
 *  - [onDisableRequested] — someone is trying to strip Device Owner; warn loudly.
 *  - [onDisabled] — admin actually removed (should never happen in production).
 */
class RMSOFTAdminReceiver : DeviceAdminReceiver() {

    /** Called once the admin is activated (manual enable or post-provisioning). */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled — asserting kiosk policies.")
        DeviceOwnerManager(context).applyAllPolicies()
    }

    /**
     * Fired at the end of Android Enterprise provisioning (factory reset → 6 taps → QR scan).
     * This is the entry point that turns a freshly provisioned device into a locked RMSOFT kiosk
     * with no further interaction: apply all policies, become the default Home app, and launch.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Provisioning complete — bootstrapping RMSOFT kiosk.")

        // Pull server URL / enrollment secret / facility from the QR (or NFC) admin-extras bundle
        // BEFORE the agent first polls, so this device enrolls against the right server with no
        // APK rebuild. Absent keys fall back to the values compiled into RemoteConfig.
        @Suppress("DEPRECATION")
        val extras = intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
        )
        RemoteConfig.applyProvisioningExtras(context, extras)

        // Seed this device's real stock-app packages before the first policy sweep so the kiosk grid
        // and lock-task allow-list are correct from the very first boot.
        AppWhitelist.seedStockApps(context)

        val owner = DeviceOwnerManager(context)
        owner.applyAllPolicies()
        owner.setAsDefaultHome()

        // Drop straight into the locked launcher.
        val launch = Intent(context, LauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { context.startActivity(launch) }
    }

    /**
     * Shown when an admin tries to deactivate this Device Owner. In production the Device Owner
     * is never removable through the normal flow, but if a removal is ever attempted we return a
     * blunt warning so it is an explicit, logged decision.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "Device admin disable REQUESTED — refusing in spirit; warning user.")
        runCatching {
            Toast.makeText(context, WARNING, Toast.LENGTH_LONG).show()
        }
        return WARNING
    }

    /** Should never fire in production — the kiosk is designed so the admin can't be removed. */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.e(TAG, "Device admin DISABLED — kiosk protection lost.")
    }

    companion object {
        private const val TAG = "RMSOFTAdmin"

        private const val WARNING =
            "WARNING: Removing RMSOFT device administration disables all security lockdown " +
                "policies on this facility device. This action is restricted and audited."

        /** Convenience accessor for the admin component used across the app. */
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, RMSOFTAdminReceiver::class.java)
    }
}

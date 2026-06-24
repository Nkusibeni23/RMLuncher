package com.rmsoft.launcher.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import com.rmsoft.launcher.remote.RemoteConfig
import com.rmsoft.launcher.utils.AppWhitelist
import com.rmsoft.launcher.utils.DeviceOwnerManager

/**
 * Handles the Android 10+ (API 29) managed-provisioning handshake that QR / NFC / cloud enrollment
 * drives. Without these two intents the modern setup-wizard flow ends in
 * "Can't set up device — contact your IT admin" even though the APK downloads, installs, and Device
 * Owner is granted. (ADB `dpm set-device-owner` bypasses this handshake entirely, which is why the
 * lab path always worked but QR provisioning never completed.)
 *
 *  - [DevicePolicyManager.ACTION_GET_PROVISIONING_MODE] — declare a fully-managed (Device Owner)
 *    deployment, not a work profile.
 *  - [DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE] — Device Owner is set by now: read the
 *    admin-extras (server URL / secret / facility), seed this device's stock apps, apply every
 *    kiosk policy and pin Home, then report success so the wizard finishes and boots the launcher.
 *
 * The compliance step mirrors [com.rmsoft.launcher.utils.RMSOFTAdminReceiver.onProfileProvisioningComplete]
 * and every call in it is idempotent, so whichever path the OS uses, the device ends up identical.
 */
class ProvisioningActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> replyProvisioningMode()
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> finishProvisioning()
            else -> {
                Log.w(TAG, "Unexpected provisioning action ${intent?.action}; finishing.")
                finish()
            }
        }
    }

    /** Tell the setup wizard this is a fully-managed device, not a work profile. */
    private fun replyProvisioningMode() {
        val result = Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
        )
        setResult(RESULT_OK, result)
        finish()
    }

    /** Bootstrap the kiosk at the policy-compliance step, then let the wizard finish. */
    private fun finishProvisioning() {
        Log.i(TAG, "Policy-compliance step — bootstrapping RMSOFT kiosk.")
        runCatching {
            @Suppress("DEPRECATION")
            val extras = intent.getParcelableExtra<PersistableBundle>(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
            )
            RemoteConfig.applyProvisioningExtras(this, extras)
            AppWhitelist.seedStockApps(this)
            val owner = DeviceOwnerManager(this)
            owner.applyAllPolicies()
            owner.setAsDefaultHome()
        }.onFailure { Log.e(TAG, "Kiosk bootstrap failed during compliance step.", it) }

        // Report success so the setup wizard completes. The system launches Home next, and we are
        // now the default Home app — so the device drops straight into the locked launcher.
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        private const val TAG = "RMSOFTProvisioning"
    }
}

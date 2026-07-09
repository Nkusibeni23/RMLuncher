package com.rmsoft.launcher.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Auto-provisions RMLauncher as Device Owner on first boot — INCLUDING the first boot after a factory
 * reset — so LOCK / WIPE / LOCATE keep working even when a thief resets the phone. This is the
 * "vault": Device Owner is no longer a manual `adb` step that a reset erases; the OS re-crowns the
 * agent itself, every boot, until it sticks.
 *
 * Requires privileged permissions allowlisted to the baked system app (MANAGE_DEVICE_ADMINS,
 * MANAGE_PROFILE_AND_DEVICE_OWNERS, WRITE_SECURE_SETTINGS) — so it ONLY works in RMSoft OS, not a
 * sideloaded build. Relies on the fact that a freshly-reset device has no accounts and isn't
 * provisioned yet, which is when Android permits promoting a Device Owner.
 */
object DeviceProvisioner {

    private const val TAG = "RMSOFTProvision"

    /** Make RMLauncher Device Owner if it isn't already. Safe to call on every boot. */
    fun ensureDeviceOwner(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (dpm.isDeviceOwnerApp(context.packageName)) return // already crowned — nothing to do

        val admin = ComponentName(context, RMSOFTAdminReceiver::class.java)

        // 1. Activate the device admin (hidden API; needs MANAGE_DEVICE_ADMINS).
        runCatching {
            if (!dpm.isAdminActive(admin)) {
                DevicePolicyManager::class.java
                    .getMethod("setActiveAdmin", ComponentName::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(dpm, admin, false)
            }
        }.onFailure { Log.w(TAG, "setActiveAdmin failed: ${it.message}") }

        // 2. Promote to Device Owner (hidden @SystemApi; needs MANAGE_PROFILE_AND_DEVICE_OWNERS).
        //    Only succeeds on an unprovisioned device with no accounts — i.e. a fresh boot / post-reset.
        val promoted = promoteToDeviceOwner(dpm, admin)
        Log.i(TAG, if (promoted) "auto-provisioned Device Owner ✅" else "Device Owner not set (accounts present?)")

        // 3. Mark the device provisioned so Setup Wizard doesn't block the phone on first boot.
        runCatching {
            Settings.Global.putInt(context.contentResolver, Settings.Global.DEVICE_PROVISIONED, 1)
            Settings.Secure.putInt(context.contentResolver, "user_setup_complete", 1)
        }
    }

    /** Try the setDeviceOwner signatures across AOSP versions; return true on the first that sticks. */
    private fun promoteToDeviceOwner(dpm: DevicePolicyManager, admin: ComponentName): Boolean {
        val cls = DevicePolicyManager::class.java
        val attempts = listOf<() -> Unit>(
            { cls.getMethod("setDeviceOwner", ComponentName::class.java).invoke(dpm, admin) },
            {
                cls.getMethod("setDeviceOwner", ComponentName::class.java, String::class.java)
                    .invoke(dpm, admin, "RMSoft")
            },
            {
                cls.getMethod(
                    "setDeviceOwner", ComponentName::class.java, String::class.java, Int::class.javaPrimitiveType,
                ).invoke(dpm, admin, "RMSoft", 0)
            },
        )
        for (attempt in attempts) {
            if (runCatching { attempt() }.isSuccess && dpm.isDeviceOwnerApp("com.rmsoft.launcher.debug")) {
                return true
            }
        }
        return dpm.isDeviceOwnerApp("com.rmsoft.launcher.debug")
    }
}

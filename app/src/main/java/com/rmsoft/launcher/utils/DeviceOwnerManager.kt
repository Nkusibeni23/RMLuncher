package com.rmsoft.launcher.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import com.rmsoft.launcher.BuildConfig
import com.rmsoft.launcher.ui.LauncherActivity

/**
 * Single source of truth for every Android Enterprise **Device Owner** policy the RMSOFT
 * launcher applies, and the control surface the admin panel drives.
 *
 * It is deliberately decoupled from any [android.app.Activity] so it can be driven from:
 *  - [RMSOFTAdminReceiver.onProfileProvisioningComplete] — one-time QR/EMM provisioning.
 *  - [LauncherActivity] — the every-launch re-assertion path.
 *  - [com.rmsoft.launcher.ui.AdminActivity] — the on-device admin control panel.
 *
 * Lock Task Mode itself ([android.app.Activity.startLockTask]) must be started from an Activity,
 * so that single call stays in the Activity; everything else lives here.
 *
 * On a device that is **not** provisioned as Device Owner every method is a safe no-op.
 */
class DeviceOwnerManager(context: Context) {

    private val appContext = context.applicationContext
    private val dpm =
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val statePrefs =
        appContext.getSharedPreferences("rmsoft_policy_state", Context.MODE_PRIVATE)

    /** Admin component backing the Device Owner. Must match the manifest <receiver>. */
    val adminComponent: ComponentName = ComponentName(appContext, RMSOFTAdminReceiver::class.java)

    /** True only when this app is the active Device Owner of the device. */
    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(appContext.packageName)

    // ─── Full lockdown ────────────────────────────────────────────────────────────

    /**
     * Apply every Device Owner policy required for a sealed security-facility kiosk.
     * Idempotent and safe to call when not Device Owner.
     */
    fun applyAllPolicies() {
        if (!isDeviceOwner()) return

        // Re-assert the *persisted* status-bar state (admin-toggleable) instead of forcing it off,
        // and align Lock Task features with it so an enabled status bar/shade isn't blocked by the OS.
        setStatusBarDisabled(isStatusBarDisabled())
        runCatching { dpm.setLockTaskPackages(adminComponent, lockTaskAllowlist()) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dpm.setLockTaskFeatures(adminComponent, lockTaskFeatures()) }
        }

        setCameraDisabled(true)
        setKeyguardDisabled(true)

        MANAGED_RESTRICTIONS.forEach { (key, _) ->
            // Debug builds keep app installation allowed so we can reinstall over ADB; every
            // other restriction (and all restrictions in release) stays enforced.
            val enforce = !(BuildConfig.DEBUG && key == UserManager.DISALLOW_INSTALL_APPS)
            setUserRestriction(key, enforce)
        }

        setAsDefaultHome()
        hideNonWhitelistedApps()
        grantLocationPermission()
    }

    /**
     * Auto-grant location to ourselves (Device Owner only) so the agent can report GPS coordinates
     * without any user prompt. Safe no-op when not Device Owner or on failure.
     */
    fun grantLocationPermission() {
        if (!isDeviceOwner()) return
        runCatching {
            listOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ).forEach { perm ->
                dpm.setPermissionGrantState(
                    adminComponent,
                    appContext.packageName,
                    perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }
    }

    /**
     * Lift [UserManager.DISALLOW_INSTALL_APPS] so apps can be (re)installed over ADB during
     * development. **Debug-only**: a no-op in release builds, where the restriction stays
     * enforced, and a safe no-op when not Device Owner. Note that [applyAllPolicies] already
     * refrains from re-adding this restriction in debug builds, so the unblock survives the
     * per-resume policy re-assertion.
     */
    fun allowAdbInstall() {
        if (!BuildConfig.DEBUG) return
        if (!isDeviceOwner()) return
        runCatching { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS) }
    }

    /** The packages permitted while in Lock Task Mode. */
    fun lockTaskAllowlist(): Array<String> = (
        listOf(appContext.packageName, "com.android.settings") +
            AppWhitelist.getWhitelistedPackages(appContext)
        ).distinct().toTypedArray()

    /**
     * Lock Task features follow the status-bar toggle. When the status bar is suppressed the kiosk
     * stays fully locked (no system UI). When the admin enables it we surface the status bar and the
     * notification shade — NOTIFICATIONS requires HOME, so the three flags are set together.
     */
    private fun lockTaskFeatures(): Int =
        if (isStatusBarDisabled()) {
            DevicePolicyManager.LOCK_TASK_FEATURE_NONE
        } else {
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                DevicePolicyManager.LOCK_TASK_FEATURE_HOME
        }

    /** Re-apply just the Lock Task features after the status-bar toggle changes, without a full sweep. */
    fun refreshLockTaskFeatures() {
        if (!isDeviceOwner()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dpm.setLockTaskFeatures(adminComponent, lockTaskFeatures()) }
        }
    }

    // ─── Individual policy controls (driven by the admin panel) ─────────────────────

    fun setStatusBarDisabled(disabled: Boolean) {
        if (!isDeviceOwner()) return
        runCatching { dpm.setStatusBarDisabled(adminComponent, disabled) }
            .onSuccess { statePrefs.edit().putBoolean(STATE_STATUS_BAR, disabled).apply() }
    }

    /** No system getter exists; we track the last value we set. */
    fun isStatusBarDisabled(): Boolean = statePrefs.getBoolean(STATE_STATUS_BAR, true)

    fun setCameraDisabled(disabled: Boolean) {
        if (!isDeviceOwner()) return
        runCatching { dpm.setCameraDisabled(adminComponent, disabled) }
    }

    fun isCameraDisabled(): Boolean =
        runCatching { dpm.getCameraDisabled(adminComponent) }.getOrDefault(false)

    fun setKeyguardDisabled(disabled: Boolean) {
        if (!isDeviceOwner()) return
        runCatching { dpm.setKeyguardDisabled(adminComponent, disabled) }
            .onSuccess { statePrefs.edit().putBoolean(STATE_KEYGUARD, disabled).apply() }
    }

    /** No system getter exists; we track the last value we set. */
    fun isKeyguardDisabled(): Boolean = statePrefs.getBoolean(STATE_KEYGUARD, true)

    fun setUserRestriction(key: String, enabled: Boolean) {
        if (!isDeviceOwner()) return
        runCatching {
            if (enabled) dpm.addUserRestriction(adminComponent, key)
            else dpm.clearUserRestriction(adminComponent, key)
        }
    }

    fun isUserRestrictionActive(key: String): Boolean {
        if (!isDeviceOwner()) return false
        return runCatching {
            dpm.getUserRestrictions(adminComponent).getBoolean(key, false)
        }.getOrDefault(false)
    }

    /** The subset of [MANAGED_RESTRICTIONS] currently enforced — for telemetry / live admin state. */
    fun activeUserRestrictions(): List<String> {
        if (!isDeviceOwner()) return emptyList()
        val current = runCatching { dpm.getUserRestrictions(adminComponent) }.getOrNull()
            ?: return emptyList()
        return MANAGED_RESTRICTIONS.map { it.first }.filter { current.getBoolean(it, false) }
    }

    // ─── Device actions ─────────────────────────────────────────────────────────────

    /** Immediately lock the device (sleep + lock). */
    fun lockNow() {
        if (!isDeviceOwner()) return
        runCatching { dpm.lockNow() }
    }

    /** Reboot the device (Device Owner only). */
    fun reboot() {
        if (!isDeviceOwner()) return
        runCatching { dpm.reboot(adminComponent) }
    }

    /**
     * Factory reset the device, wiping all user data. DESTRUCTIVE — gate behind confirmation.
     * Requires [UserManager.DISALLOW_FACTORY_RESET] to be cleared first, which this does.
     */
    fun factoryReset() {
        if (!isDeviceOwner()) return
        runCatching {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            dpm.wipeData(0)
        }
    }

    /**
     * Relinquish Device Owner. Lifts ALL restrictions and turns the device back into an ordinary
     * phone. Lab/decommission use — production should not expose this without strong auth.
     */
    fun relinquishDeviceOwner() {
        if (!isDeviceOwner()) return
        runCatching {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(appContext.packageName)
        }
    }

    // ─── Default Home ───────────────────────────────────────────────────────────────

    fun setAsDefaultHome() {
        if (!isDeviceOwner()) return
        val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val home = ComponentName(appContext, LauncherActivity::class.java)
        runCatching { dpm.addPersistentPreferredActivity(adminComponent, homeFilter, home) }
    }

    // ─── App management ───────────────────────────────────────────────────────────────

    fun setApplicationHidden(packageName: String, hidden: Boolean): Boolean {
        if (!isDeviceOwner()) return false
        return runCatching {
            dpm.setApplicationHidden(adminComponent, packageName, hidden)
        }.getOrDefault(false)
    }

    fun isApplicationHidden(packageName: String): Boolean {
        if (!isDeviceOwner()) return false
        return runCatching {
            dpm.isApplicationHidden(adminComponent, packageName)
        }.getOrDefault(false)
    }

    fun enableSystemApp(packageName: String) {
        if (!isDeviceOwner()) return
        runCatching { dpm.enableSystemApp(adminComponent, packageName) }
    }

    fun hideNonWhitelistedApps() {
        if (!isDeviceOwner()) return
        val pm = appContext.packageManager
        val keepVisible = (
            AppWhitelist.getWhitelistedPackages(appContext) +
                ESSENTIAL_SYSTEM_PACKAGES + appContext.packageName
            ).toSet()

        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launchable, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .forEach { pkg ->
                runCatching { dpm.setApplicationHidden(adminComponent, pkg, pkg !in keepVisible) }
            }

        AppWhitelist.getWhitelistedPackages(appContext).forEach { pkg ->
            runCatching { dpm.setApplicationHidden(adminComponent, pkg, false) }
        }
    }

    /** A launchable app plus its current hidden/whitelisted state — for the admin panel list. */
    data class ManagedApp(
        val packageName: String,
        val label: String,
        val hidden: Boolean,
        val whitelisted: Boolean,
    )

    /** Every launchable app on the device (excluding this launcher), with current state. */
    fun installedLaunchableApps(): List<ManagedApp> {
        val pm = appContext.packageManager
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launchable, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { it != appContext.packageName }
            .map { pkg ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                ManagedApp(
                    packageName = pkg,
                    label = label,
                    hidden = isApplicationHidden(pkg),
                    whitelisted = AppWhitelist.isWhitelisted(appContext, pkg),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    companion object {
        private const val STATE_STATUS_BAR = "status_bar_disabled"
        private const val STATE_KEYGUARD = "keyguard_disabled"

        /** Packages kept visible/enabled regardless of the whitelist so the device stays usable. */
        val ESSENTIAL_SYSTEM_PACKAGES = listOf(
            "com.android.settings",
            "com.android.systemui",
            "com.android.dialer",
            "com.google.android.dialer",
        )

        /**
         * Every user restriction the kiosk manages, paired with a human-readable label for the
         * admin panel. All are available on the app's minSdk (26).
         */
        val MANAGED_RESTRICTIONS: List<Pair<String, String>> = listOf(
            UserManager.DISALLOW_FACTORY_RESET to "Block factory reset",
            UserManager.DISALLOW_SAFE_BOOT to "Block safe boot",
            UserManager.DISALLOW_USB_FILE_TRANSFER to "Block USB file transfer",
            UserManager.DISALLOW_INSTALL_APPS to "Block app installation",
            UserManager.DISALLOW_UNINSTALL_APPS to "Block app uninstallation",
            UserManager.DISALLOW_CONFIG_BLUETOOTH to "Block Bluetooth config",
            UserManager.DISALLOW_ADD_USER to "Block adding users",
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA to "Block USB storage",
        )
    }
}

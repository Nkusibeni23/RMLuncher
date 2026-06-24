package com.rmsoft.launcher.utils

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
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

        // Enforce every managed restriction EXCEPT install/uninstall. Blocking those here would also
        // block managed pushes (INSTALL_APK), the app purge below, and ADB updates — so they stay off
        // by default; the admin can enforce them explicitly from the dashboard for production.
        MANAGED_RESTRICTIONS.forEach { (key, _) ->
            if (key != UserManager.DISALLOW_INSTALL_APPS && key != UserManager.DISALLOW_UNINSTALL_APPS) {
                setUserRestriction(key, true)
            }
        }

        setAsDefaultHome()
        hideNonWhitelistedApps()
        purgeNonWhitelistedUserApps()
        grantRuntimePermissions()
    }

    /**
     * Auto-grant the launcher's runtime (dangerous) permissions to itself — Device Owner only, no
     * user prompt. Special access like SYSTEM_ALERT_WINDOW / WRITE_SETTINGS are app-ops and can't be
     * granted this way; the kiosk no longer needs the overlay (see LauncherActivity.blockNotificationShade).
     */
    fun grantRuntimePermissions() {
        if (!isDeviceOwner()) return
        val perms = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        perms.forEach { perm ->
            runCatching {
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
    private fun lockTaskFeatures(): Int {
        // When the whole status bar is suppressed there's no system UI to grant features to.
        if (isStatusBarDisabled()) return DevicePolicyManager.LOCK_TASK_FEATURE_NONE
        // HOME is always kept (NOTIFICATIONS requires it); the rest follow per-feature admin toggles.
        var f = DevicePolicyManager.LOCK_TASK_FEATURE_HOME
        if (statePrefs.getBoolean(STATE_LTF_SYSTEM_INFO, true)) {
            f = f or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
        }
        if (statePrefs.getBoolean(STATE_LTF_NOTIFICATIONS, true)) {
            f = f or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
        }
        if (statePrefs.getBoolean(STATE_LTF_GLOBAL_ACTIONS, false)) {
            f = f or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
        }
        return f
    }

    /**
     * Granular control over what the notification/control panel exposes in kiosk (Lock Task):
     * the notification shade, the status-bar system info, and the power (global-actions) menu.
     * Persisted and re-applied via [refreshLockTaskFeatures]. Only effective while the status bar
     * is enabled and the device is pinned.
     */
    fun setNotificationPanelFeatures(notifications: Boolean, systemInfo: Boolean, globalActions: Boolean) {
        statePrefs.edit()
            .putBoolean(STATE_LTF_NOTIFICATIONS, notifications)
            .putBoolean(STATE_LTF_SYSTEM_INFO, systemInfo)
            .putBoolean(STATE_LTF_GLOBAL_ACTIONS, globalActions)
            .apply()
        refreshLockTaskFeatures()
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
     * Factory reset the device. DESTRUCTIVE — gate behind confirmation. When [wipeExternalStorage] is
     * true, also erases shared/SD storage and clears Factory Reset Protection so the phone comes up
     * completely clean.
     *
     * On Android 14+ (API 34) a Device Owner MUST call wipeDevice(); the old wipeData() throws there,
     * which is why the previous wipeData(0) silently failed. Errors are deliberately NOT swallowed so
     * the dashboard ack reflects a real failure instead of a misleading "requested".
     */
    fun factoryReset(wipeExternalStorage: Boolean = false) {
        check(isDeviceOwner()) { "not Device Owner" }
        // DISALLOW_FACTORY_RESET blocks the user, not the admin, but clear it to be safe.
        runCatching { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET) }

        val flags = if (wipeExternalStorage) {
            DevicePolicyManager.WIPE_EXTERNAL_STORAGE or DevicePolicyManager.WIPE_RESET_PROTECTION_DATA
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            dpm.wipeDevice(flags)
        } else {
            @Suppress("DEPRECATION")
            dpm.wipeData(flags)
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

    /**
     * Silently uninstall every **user-installed** (non-system) app that isn't whitelisted. System
     * apps can't be uninstalled — [hideNonWhitelistedApps] hides those instead. Device Owner only;
     * already-uninstalled apps are simply skipped, so this is safe to call on every policy sweep.
     */
    fun purgeNonWhitelistedUserApps() {
        if (!isDeviceOwner()) return
        val pm = appContext.packageManager
        val keep = (
            AppWhitelist.getWhitelistedPackages(appContext) +
                ESSENTIAL_SYSTEM_PACKAGES + appContext.packageName
            ).toSet()
        val installer = pm.packageInstaller
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 } // user-installed only
            .map { it.packageName }
            .filter { it !in keep }
            .forEach { pkg ->
                runCatching {
                    val pi = PendingIntent.getBroadcast(
                        appContext,
                        pkg.hashCode(),
                        Intent(UNINSTALL_ACTION).setPackage(appContext.packageName),
                        flags,
                    )
                    installer.uninstall(pkg, pi.intentSender)
                }
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
        // Per-feature notification/control-panel toggles (Lock Task features).
        private const val STATE_LTF_NOTIFICATIONS = "ltf_notifications"
        private const val STATE_LTF_SYSTEM_INFO = "ltf_system_info"
        private const val STATE_LTF_GLOBAL_ACTIONS = "ltf_global_actions"
        private const val UNINSTALL_ACTION = "com.rmsoft.launcher.PKG_UNINSTALL_STATUS"

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

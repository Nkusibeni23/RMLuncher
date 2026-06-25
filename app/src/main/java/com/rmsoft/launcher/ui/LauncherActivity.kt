package com.rmsoft.launcher.ui

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.rmsoft.launcher.BuildConfig
import com.rmsoft.launcher.R
import com.rmsoft.launcher.databinding.ActivityLauncherBinding
import com.rmsoft.launcher.model.AppItem
import com.rmsoft.launcher.remote.AgentService
import com.rmsoft.launcher.remote.KioskBridge
import com.rmsoft.launcher.utils.AppWhitelist
import com.rmsoft.launcher.utils.DeviceOwnerManager
import com.rmsoft.launcher.utils.StatusBarBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding
    private lateinit var appAdapter: AppGridAdapter
    private val appList = mutableListOf<AppItem>()
    private var receiversRegistered = false

    private val devicePolicyManager by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val activityManager by lazy {
        getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }
    private val keyguardManager by lazy {
        getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }
    /** All Device Owner policy logic lives here; safe no-op when not provisioned. */
    private val deviceOwner by lazy { DeviceOwnerManager(this) }
    private val statusBarBlocker by lazy { StatusBarBlocker(this) }

    // Refresh the app list when apps are installed/uninstalled.
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = loadApps()
    }

    // Re-assert the launcher and dismiss the keyguard each time the screen turns on.
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> dismissKeyguard()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyLockScreenBehavior()

        // First run: resolve this device's real stock-app packages (Phone, Messages, Contacts, Clock,
        // Calculator, Compass, Camera) into the whitelist before policies apply, so applyAllPolicies
        // unhides them and adds them to the Lock Task allowlist.
        AppWhitelist.seedStockApps(this)

        // Re-assert the full Device Owner lockdown on every cold start (no-op if not provisioned).
        deviceOwner.applyAllPolicies()

        // Debug builds: lift the app-install restriction so we can reinstall over ADB during
        // development. No-op in release, where DISALLOW_INSTALL_APPS stays enforced.
        if (BuildConfig.DEBUG) {
            deviceOwner.allowAdbInstall()
        }

        // Start the MDM device agent (enroll + poll for remote commands + telemetry).
        AgentService.start(this)

        binding = ActivityLauncherBinding.inflate(layoutInflater)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)

        enableImmersiveMode()
        setupGrid()
        loadApps()
        registerReceivers()

        // Hidden admin entry: long-press the RMSOFT brand title to open the PIN-gated panel.
        binding.brandName.setOnLongClickListener {
            startActivity(Intent(this, AdminActivity::class.java))
            true
        }

        // Block back navigation out of the launcher (modern, non-deprecated API).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Intentionally empty.
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Let the agent drive Lock Task enter/exit and grid refreshes through this resumed activity.
        KioskBridge.register(
            this,
            onRefreshSystemUi = { enableImmersiveMode(); blockNotificationShade() },
            onRefreshApps = { loadApps() },
        )
        enableLockTaskIfOwner()
        enableImmersiveMode()
        dismissKeyguard()
        blockNotificationShade()
        // Reload the grid so dashboard-driven whitelist/hide/install changes that happened while we
        // were away (or that produced no package broadcast) are reflected the moment we're back.
        loadApps()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // System bars can reappear after focus changes (shade pull, dialogs) — re-hide them.
        if (hasFocus) enableImmersiveMode()
    }

    // ─── Lock screen ──────────────────────────────────────────────────────────

    /** Keep the screen on, show over the keyguard, and turn the screen on for this activity. */
    private fun applyLockScreenBehavior() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // As Device Owner we disable the keyguard outright (DeviceOwnerManager.setKeyguardDisabled).
        // We must NOT declare show-when-locked in that case: it keeps the keyguard "showing" behind
        // this launcher, which then occludes any app launched from the grid (they render black). Only
        // use show-when-locked as a fallback on a non-managed device where the keyguard can't be off.
        val managed = deviceOwner.isDeviceOwner()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(!managed)
            setTurnScreenOn(true)
        } else if (!managed) {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    /**
     * Auto-dismiss the keyguard when the screen turns on. For a non-secure (swipe) keyguard
     * this removes it immediately. A secure keyguard (PIN/pattern/password) still prompts for
     * credentials unless the device is Device Owner (see [enableLockTaskIfOwner], which disables
     * the keyguard entirely).
     */
    private fun dismissKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && keyguardManager.isKeyguardLocked) {
            keyguardManager.requestDismissKeyguard(this, null)
        }
    }

    // ─── Notification shade ─────────────────────────────────────────────────────

    /**
     * Keep the notification shade un-pullable while still allowing heads-up banners.
     * Requires the "Display over other apps" permission; if it isn't granted yet we send the
     * user to the system grant screen once. The overlay persists across the app's lifetime
     * (including over launched whitelisted apps) until [onDestroy].
     */
    private fun blockNotificationShade() {
        if (!deviceOwner.isStatusBarDisabled()) {
            statusBarBlocker.hide() // admin enabled the shade — stop swallowing the pull-down gesture
            return
        }
        // Device Owner kiosk: Lock Task already blocks the notification shade at the OS level, so the
        // overlay is unnecessary — skipping it avoids the SYSTEM_ALERT_WINDOW permission entirely (and
        // the Android-15 ECM black-screen loop that requesting it triggers on a sideloaded app).
        if (deviceOwner.isDeviceOwner()) {
            statusBarBlocker.hide()
            return
        }
        // Non-managed fallback: use the overlay only if it's already granted; never bounce to Settings.
        if (statusBarBlocker.canBlock()) statusBarBlocker.show() else statusBarBlocker.hide()
    }

    // ─── Immersive sticky: hide nav + status bars, block swipe-out ──────────────

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            if (deviceOwner.isStatusBarDisabled()) {
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                // Admin enabled the status bar: keep it visible, still hide the nav bar.
                hide(WindowInsetsCompat.Type.navigationBars())
                show(WindowInsetsCompat.Type.statusBars())
            }
            // Sticky immersive: a swipe shows the bars only transiently, then they auto-hide.
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ─── Kiosk lockdown (Device Owner only) ─────────────────────────────────────

    /**
     * When provisioned as Device Owner, fully locks the device into this launcher:
     * disables the keyguard, strips Lock Task to nothing (no Home/Recents/notifications/
     * status bar), allow-lists the kiosk packages, and enters Lock Task Mode — which blocks
     * Home, Recents and the notification shade at the OS level. On a non-provisioned device
     * this is a safe no-op; the immersive + show-over-lockscreen behaviour above still applies.
     */
    private fun enableLockTaskIfOwner() {
        try {
            // (Re)configure the allow-list + feature lockdown via the central policy manager.
            deviceOwner.applyAllPolicies()

            val notLocked =
                activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE
            if (notLocked && devicePolicyManager.isLockTaskPermitted(packageName)) {
                startLockTask()
            }
        } catch (e: Exception) {
            // Not a device owner / lock task not permitted — run as a normal launcher.
        }
    }

    // ─── App grid ───────────────────────────────────────────────────────────────

    private fun setupGrid() {
        appAdapter = AppGridAdapter(appList) { app -> launchApp(app) }
        binding.appGrid.apply {
            layoutManager = GridLayoutManager(this@LauncherActivity, 4) // 4 columns
            adapter = appAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadApps() {
        // Resolve labels/icons off the main thread — getApplicationIcon() does disk I/O.
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val pm = packageManager
                AppWhitelist.getWhitelistedPackages(this@LauncherActivity).mapNotNull { packageName ->
                    try {
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        AppItem(
                            packageName = packageName,
                            label = pm.getApplicationLabel(appInfo).toString(),
                            icon = pm.getApplicationIcon(appInfo)
                        )
                    } catch (e: Exception) {
                        null // App not installed — skip silently
                    }
                }
            }
            appList.clear()
            appList.addAll(items)
            settingsTile()?.let { appList.add(it) } // always-present custom Settings tile
            appAdapter.notifyDataSetChanged()

            val empty = appList.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.appGrid.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    /** Internal tile that opens the launcher's own restricted [SettingsActivity]. */
    private fun settingsTile(): AppItem? {
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_settings) ?: return null
        return AppItem(
            packageName = "$packageName.settings",
            label = getString(R.string.settings_label),
            icon = icon,
            intent = Intent(this, SettingsActivity::class.java)
        )
    }

    private fun launchApp(app: AppItem) {
        // Internal tiles carry an explicit intent and stay within the launcher task.
        app.intent?.let {
            startActivity(it)
            return
        }
        packageManager.getLaunchIntentForPackage(app.packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    private fun registerReceivers() {
        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiversRegistered = true
    }

    // ─── Block hardware keys ─────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Block navigation/escape keys; let volume keys through so the user can
        // adjust device volume normally. Home / Recents are not delivered to
        // onKeyDown for ordinary apps — they are enforced by Lock Task Mode.
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_MENU -> true
            else -> super.onKeyDown(keyCode, event) // includes VOLUME_UP / VOLUME_DOWN
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        KioskBridge.unregister(this)
        statusBarBlocker.hide()
        if (receiversRegistered) {
            runCatching { unregisterReceiver(packageReceiver) }
            runCatching { unregisterReceiver(screenStateReceiver) }
            receiversRegistered = false
        }
    }
}

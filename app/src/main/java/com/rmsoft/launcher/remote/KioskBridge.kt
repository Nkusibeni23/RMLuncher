package com.rmsoft.launcher.remote

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Bridges the background agent (a Service) to the foreground [com.rmsoft.launcher.ui.LauncherActivity]
 * for the two operations that *require* an Activity: [Activity.startLockTask] / [Activity.stopLockTask].
 *
 * The launcher registers itself while resumed; the agent's command executor calls through here.
 * If no launcher is currently resumed the request is a no-op (the launcher re-asserts Lock Task on
 * its next onResume anyway).
 */
object KioskBridge {

    private var activityRef: WeakReference<Activity>? = null
    private var refresher: (() -> Unit)? = null
    private var appsRefresher: (() -> Unit)? = null

    fun register(
        activity: Activity,
        onRefreshSystemUi: (() -> Unit)? = null,
        onRefreshApps: (() -> Unit)? = null,
    ) {
        activityRef = WeakReference(activity)
        refresher = onRefreshSystemUi
        appsRefresher = onRefreshApps
    }

    fun unregister(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
            refresher = null
            appsRefresher = null
        }
    }

    /** Re-apply the launcher's immersive mode + notification-shade overlay (e.g. after a toggle). */
    fun refreshSystemUi() {
        activityRef?.get()?.let { a -> a.runOnUiThread { runCatching { refresher?.invoke() } } }
    }

    /**
     * Reload the home-screen app grid (e.g. after a whitelist change, hide/show, or app install
     * pushed from the dashboard). No-op if no launcher is currently resumed — it reloads the grid on
     * its next onResume anyway.
     */
    fun refreshApps() {
        activityRef?.get()?.let { a -> a.runOnUiThread { runCatching { appsRefresher?.invoke() } } }
    }

    fun enterKiosk() {
        activityRef?.get()?.let { a -> a.runOnUiThread { runCatching { a.startLockTask() } } }
    }

    fun exitKiosk() {
        activityRef?.get()?.let { a -> a.runOnUiThread { runCatching { a.stopLockTask() } } }
    }
}

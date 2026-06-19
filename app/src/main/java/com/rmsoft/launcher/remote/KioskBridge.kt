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

    fun register(activity: Activity, onRefreshSystemUi: (() -> Unit)? = null) {
        activityRef = WeakReference(activity)
        refresher = onRefreshSystemUi
    }

    fun unregister(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
            refresher = null
        }
    }

    /** Re-apply the launcher's immersive mode + notification-shade overlay (e.g. after a toggle). */
    fun refreshSystemUi() {
        activityRef?.get()?.let { a -> a.runOnUiThread { runCatching { refresher?.invoke() } } }
    }

    fun enterKiosk() {
        activityRef?.get()?.let { a -> a.runOnUiThread { runCatching { a.startLockTask() } } }
    }

    fun exitKiosk() {
        activityRef?.get()?.let { a -> a.runOnUiThread { runCatching { a.stopLockTask() } } }
    }
}

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

    fun register(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun unregister(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    fun enterKiosk() {
        activityRef?.get()?.let { a -> a.runOnUiThread { runCatching { a.startLockTask() } } }
    }

    fun exitKiosk() {
        activityRef?.get()?.let { a -> a.runOnUiThread { runCatching { a.stopLockTask() } } }
    }
}

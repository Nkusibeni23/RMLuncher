package com.rmsoft.launcher.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rmsoft.launcher.BuildConfig

/**
 * Debug-only helper to repoint the MDM agent at a new server over ADB, without rebuilding.
 *
 * This receiver is declared ONLY in the debug manifest (`app/src/debug/AndroidManifest.xml`), so it
 * does not exist in release builds. The [BuildConfig.DEBUG] guard below is a second line of defence.
 *
 * Usage (the debug applicationId is `com.rmsoft.launcher.debug`):
 * ```
 * adb shell am broadcast \
 *   -n com.rmsoft.launcher.debug/com.rmsoft.launcher.remote.DevToolsReceiver \
 *   -a com.rmsoft.launcher.action.SET_SERVER \
 *   --es url https://mdm.example.com
 *   # optional: --es secret <enrollment-secret>
 * ```
 * It sets the server URL (and optional enrollment secret), then clears the device token so the
 * agent re-enrolls against the new server on its next poll. For a production server switch use the
 * `SET_SERVER` command from the dashboard instead — this path is dev-only.
 */
class DevToolsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION_SET_SERVER) return

        val url = intent.getStringExtra(EXTRA_URL)?.trim()
        if (url.isNullOrEmpty() || !url.matches(Regex("^https?://.+"))) {
            Log.w(TAG, "SET_SERVER ignored — missing/invalid 'url' extra: $url")
            return
        }
        // Keep the current enrollment secret unless one is explicitly supplied.
        val secret = intent.getStringExtra(EXTRA_SECRET)?.takeIf { it.isNotBlank() }
            ?: RemoteConfig.enrollmentSecret(context)

        RemoteConfig.setServer(context, url, secret)
        RemoteConfig.clearEnrollment(context) // force re-enroll on next poll
        Log.i(TAG, "dev SET_SERVER → $url (token cleared; agent will re-enroll)")
    }

    companion object {
        private const val TAG = "RMSOFTDevTools"
        const val ACTION_SET_SERVER = "com.rmsoft.launcher.action.SET_SERVER"
        const val EXTRA_URL = "url"
        const val EXTRA_SECRET = "secret"
    }
}

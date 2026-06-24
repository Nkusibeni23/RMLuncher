package com.rmsoft.launcher.remote

import android.content.Context

/**
 * Connection + identity settings for the MDM device agent, persisted in SharedPreferences.
 *
 * [DEFAULT_SERVER_URL] and [DEFAULT_ENROLLMENT_SECRET] must match your rmsoft-mdm server. Set them
 * here before building, or push them via the QR provisioning extras bundle. The device token is
 * obtained at enrollment and stored here.
 */
object RemoteConfig {

    /** Base URL of the rmsoft-mdm server, e.g. https://mdm.rmsoft.example. No trailing slash. */
    const val DEFAULT_SERVER_URL = "https://mdm.tugane.com"

    /** Shared secret required to enroll — must equal the server's ENROLLMENT_SECRET. */
    const val DEFAULT_ENROLLMENT_SECRET = "rmsoft-enroll-d2a1e709424b3794"

    /** How often the agent polls the server for commands + posts telemetry. */
    const val POLL_INTERVAL_MS = 15_000L

    private const val PREFS = "rmsoft_remote"
    private const val KEY_SERVER = "server_url"
    private const val KEY_SECRET = "enrollment_secret"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_FACILITY = "facility"
    private const val KEY_PENDING_SERVER = "pending_server_url"
    private const val KEY_PENDING_REENROLL = "pending_server_reenroll"

    /**
     * Keys read from the QR / NFC provisioning admin-extras bundle
     * (`PROVISIONING_ADMIN_EXTRAS_BUNDLE`). Supplying these lets one signed APK enroll against any
     * server with no rebuild — the [DEFAULT_SERVER_URL] / [DEFAULT_ENROLLMENT_SECRET] are only the
     * fallback when the bundle omits them (e.g. the ADB lab path). See [applyProvisioningExtras].
     */
    const val EXTRA_SERVER_URL = "serverUrl"
    const val EXTRA_ENROLLMENT_SECRET = "enrollmentSecret"
    const val EXTRA_FACILITY = "facility"

    fun serverUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER, null) ?: DEFAULT_SERVER_URL

    fun enrollmentSecret(context: Context): String =
        prefs(context).getString(KEY_SECRET, null) ?: DEFAULT_ENROLLMENT_SECRET

    /** Optional facility/site label supplied at provisioning time (null if none). */
    fun facility(context: Context): String? = prefs(context).getString(KEY_FACILITY, null)

    fun deviceToken(context: Context): String? = prefs(context).getString(KEY_TOKEN, null)

    fun deviceId(context: Context): String? = prefs(context).getString(KEY_DEVICE_ID, null)

    fun isEnrolled(context: Context): Boolean = deviceToken(context) != null

    fun saveEnrollment(context: Context, deviceId: String, token: String) {
        prefs(context).edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    /** Drop the stored device identity so the agent re-enrolls on its next poll. */
    fun clearEnrollment(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_DEVICE_ID)
            .apply()
    }

    fun setServer(context: Context, url: String, secret: String) {
        prefs(context).edit()
            .putString(KEY_SERVER, url.trimEnd('/'))
            .putString(KEY_SECRET, secret)
            .apply()
    }

    /**
     * Persist server URL / enrollment secret / facility from the provisioning admin-extras bundle,
     * if present. Called from [com.rmsoft.launcher.utils.RMSOFTAdminReceiver] at the end of QR/NFC
     * provisioning, before the agent's first poll, so the device enrolls against the right server
     * with no APK rebuild. Missing or blank keys leave the existing value (or the compiled default)
     * untouched. [BaseBundle] covers both the PersistableBundle (provisioning) and Bundle cases.
     */
    fun applyProvisioningExtras(context: Context, extras: android.os.BaseBundle?) {
        if (extras == null) return
        val editor = prefs(context).edit()
        extras.getString(EXTRA_SERVER_URL)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { editor.putString(KEY_SERVER, it.trimEnd('/')) }
        extras.getString(EXTRA_ENROLLMENT_SECRET)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { editor.putString(KEY_SECRET, it) }
        extras.getString(EXTRA_FACILITY)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { editor.putString(KEY_FACILITY, it) }
        editor.apply()
    }

    /**
     * Stage a server-URL switch (from a SET_SERVER command). The new URL is NOT applied immediately
     * so the current tick can still ack + report telemetry to the old server; [applyPendingServer]
     * commits it at the end of the tick. If [reenroll] is true the device token is also cleared on
     * apply, forcing a fresh enrollment against the new server (needed when it's a different backend).
     */
    fun setPendingServer(context: Context, url: String, reenroll: Boolean) {
        prefs(context).edit()
            .putString(KEY_PENDING_SERVER, url.trimEnd('/'))
            .putBoolean(KEY_PENDING_REENROLL, reenroll)
            .apply()
    }

    /** Commit a staged server switch, if any. Returns the new URL when one was applied, else null. */
    fun applyPendingServer(context: Context): String? {
        val p = prefs(context)
        val pending = p.getString(KEY_PENDING_SERVER, null) ?: return null
        val edit = p.edit()
            .putString(KEY_SERVER, pending)
            .remove(KEY_PENDING_SERVER)
        if (p.getBoolean(KEY_PENDING_REENROLL, false)) {
            edit.remove(KEY_TOKEN).remove(KEY_DEVICE_ID)
        }
        edit.remove(KEY_PENDING_REENROLL).apply()
        return pending
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

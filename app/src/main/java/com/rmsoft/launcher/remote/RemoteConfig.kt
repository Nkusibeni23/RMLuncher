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

    /** Base URL of the RMSoft MDM server (rmsoft-server on Railway). No trailing slash. */
    const val DEFAULT_SERVER_URL = "https://aosp-production.up.railway.app"

    /** Shared secret required to enroll — must equal the server's ENROLLMENT_SECRET. */
    const val DEFAULT_ENROLLMENT_SECRET = "rmsoft-enroll-d2a1e709424b3794"

    // Enrollment login. rmsoft-server enforces an @rmsoft.rw account (ALLOWED_EMAIL_DOMAIN), so the
    // agent logs in with a device/service account to get a JWT, then enrolls. These are intentionally
    // BLANK by default — inject them once via the QR provisioning bundle (EXTRA_ENROLL_*) or
    // setEnrollCredentials(); never hardcode a real password in source.
    const val DEFAULT_ENROLL_EMAIL = ""
    const val DEFAULT_ENROLL_PASSWORD = ""

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

    // MQTT connection creds + topics, handed to us by rmsoft-server at enrollment. These drive the
    // real-time push channel (MqttManager); the HTTP poll fields above stay as a fallback/enroll.
    private const val KEY_MQTT_URL = "mqtt_url"
    private const val KEY_MQTT_USER = "mqtt_username"
    private const val KEY_MQTT_PASS = "mqtt_password"
    private const val KEY_TOPIC_CMD = "topic_commands"
    private const val KEY_TOPIC_ACK = "topic_acks"
    private const val KEY_TOPIC_LOC = "topic_location"
    private const val KEY_ENROLL_EMAIL = "enroll_email"
    private const val KEY_ENROLL_PASSWORD = "enroll_password"
    private const val KEY_SIM_BASELINE = "sim_baseline"
    private const val KEY_PENDING_ALERT_TYPE = "pending_alert_type"
    private const val KEY_PENDING_ALERT_INFO = "pending_alert_info"

    /**
     * Keys read from the QR / NFC provisioning admin-extras bundle
     * (`PROVISIONING_ADMIN_EXTRAS_BUNDLE`). Supplying these lets one signed APK enroll against any
     * server with no rebuild — the [DEFAULT_SERVER_URL] / [DEFAULT_ENROLLMENT_SECRET] are only the
     * fallback when the bundle omits them (e.g. the ADB lab path). See [applyProvisioningExtras].
     */
    const val EXTRA_SERVER_URL = "serverUrl"
    const val EXTRA_ENROLLMENT_SECRET = "enrollmentSecret"
    const val EXTRA_FACILITY = "facility"
    const val EXTRA_ENROLL_EMAIL = "enrollEmail"
    const val EXTRA_ENROLL_PASSWORD = "enrollPassword"

    /** Device/service @rmsoft.rw account the agent logs in with to enroll (blank until provisioned). */
    fun enrollEmail(context: Context): String =
        prefs(context).getString(KEY_ENROLL_EMAIL, null) ?: DEFAULT_ENROLL_EMAIL
    fun enrollPassword(context: Context): String =
        prefs(context).getString(KEY_ENROLL_PASSWORD, null)?.let { Crypto.decrypt(it) }
            ?: DEFAULT_ENROLL_PASSWORD

    fun setEnrollCredentials(context: Context, email: String, password: String) {
        prefs(context).edit()
            .putString(KEY_ENROLL_EMAIL, email.trim())
            .putString(KEY_ENROLL_PASSWORD, Crypto.encrypt(password)) // encrypted at rest
            .apply()
    }

    fun hasEnrollCredentials(context: Context): Boolean =
        enrollEmail(context).isNotBlank() && enrollPassword(context).isNotBlank()

    fun serverUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER, null) ?: DEFAULT_SERVER_URL

    fun enrollmentSecret(context: Context): String =
        prefs(context).getString(KEY_SECRET, null) ?: DEFAULT_ENROLLMENT_SECRET

    /** Optional facility/site label supplied at provisioning time (null if none). */
    fun facility(context: Context): String? = prefs(context).getString(KEY_FACILITY, null)

    fun deviceToken(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)?.let { Crypto.decrypt(it) }

    fun deviceId(context: Context): String? = prefs(context).getString(KEY_DEVICE_ID, null)

    fun isEnrolled(context: Context): Boolean = deviceToken(context) != null

    fun saveEnrollment(context: Context, deviceId: String, token: String) {
        prefs(context).edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_TOKEN, Crypto.encrypt(token)) // encrypted at rest
            .apply()
    }

    // ─── MQTT (real-time push) ────────────────────────────────────────────────────

    /** Persist the MQTT creds + topics returned by rmsoft-server at enrollment. */
    fun saveMqtt(
        context: Context,
        url: String,
        username: String,
        password: String,
        commandTopic: String,
        ackTopic: String,
        locationTopic: String,
    ) {
        prefs(context).edit()
            .putString(KEY_MQTT_URL, url)
            .putString(KEY_MQTT_USER, username)
            .putString(KEY_MQTT_PASS, Crypto.encrypt(password)) // encrypted at rest
            .putString(KEY_TOPIC_CMD, commandTopic)
            .putString(KEY_TOPIC_ACK, ackTopic)
            .putString(KEY_TOPIC_LOC, locationTopic)
            .apply()
    }

    fun mqttUrl(context: Context): String? = prefs(context).getString(KEY_MQTT_URL, null)
    fun mqttUsername(context: Context): String? = prefs(context).getString(KEY_MQTT_USER, null)
    fun mqttPassword(context: Context): String? =
        prefs(context).getString(KEY_MQTT_PASS, null)?.let { Crypto.decrypt(it) }
    fun commandTopic(context: Context): String? = prefs(context).getString(KEY_TOPIC_CMD, null)
    fun ackTopic(context: Context): String? = prefs(context).getString(KEY_TOPIC_ACK, null)
    fun locationTopic(context: Context): String? = prefs(context).getString(KEY_TOPIC_LOC, null)

    /** Heartbeat topic follows the device id: device/{id}/heartbeat. */
    fun heartbeatTopic(context: Context): String? =
        deviceId(context)?.let { "device/$it/heartbeat" }

    /** True once the MQTT push channel is provisioned (creds present). */
    fun hasMqtt(context: Context): Boolean = mqttUrl(context) != null && commandTopic(context) != null

    // ─── SIM-swap anti-theft ────────────────────────────────────────────────────

    /** Fingerprint of the SIM(s) seen at enrollment; a change means a swap. Null until first seen. */
    fun simBaseline(context: Context): String? = prefs(context).getString(KEY_SIM_BASELINE, null)
    fun setSimBaseline(context: Context, fp: String) {
        prefs(context).edit().putString(KEY_SIM_BASELINE, fp).apply()
    }

    /** An anti-theft alert (SIM_SWAP, TAMPER, …) awaiting delivery — survives offline until reconnect. */
    fun pendingAlertType(context: Context): String? =
        prefs(context).getString(KEY_PENDING_ALERT_TYPE, null)
    fun pendingAlertInfo(context: Context): String? =
        prefs(context).getString(KEY_PENDING_ALERT_INFO, null)
    fun setPendingAlert(context: Context, type: String, info: String) {
        prefs(context).edit()
            .putString(KEY_PENDING_ALERT_TYPE, type)
            .putString(KEY_PENDING_ALERT_INFO, info)
            .apply()
    }
    fun clearPendingAlert(context: Context) {
        prefs(context).edit()
            .remove(KEY_PENDING_ALERT_TYPE)
            .remove(KEY_PENDING_ALERT_INFO)
            .apply()
    }

    /** Drop the stored device identity so the agent re-enrolls on its next poll. */
    fun clearEnrollment(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_DEVICE_ID)
            .apply()
    }

    /**
     * Wipe the stored identity AND MQTT creds so [hasMqtt] is false and the agent re-enrolls from
     * scratch — used when the server deletes the device (RE_ENROLL). Keeps the server URL + secret.
     */
    fun clearForReEnroll(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_MQTT_URL)
            .remove(KEY_MQTT_USER)
            .remove(KEY_MQTT_PASS)
            .remove(KEY_TOPIC_CMD)
            .remove(KEY_TOPIC_ACK)
            .remove(KEY_TOPIC_LOC)
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
        // Enrollment login carried in the QR bundle so a fleet phone enrolls with no typing.
        extras.getString(EXTRA_ENROLL_EMAIL)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { editor.putString(KEY_ENROLL_EMAIL, it) }
        extras.getString(EXTRA_ENROLL_PASSWORD)?.takeIf { it.isNotEmpty() }
            ?.let { editor.putString(KEY_ENROLL_PASSWORD, Crypto.encrypt(it)) }
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

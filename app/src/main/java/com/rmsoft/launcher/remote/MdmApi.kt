package com.rmsoft.launcher.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin HTTP client for the rmsoft-mdm device API, built on [HttpURLConnection] + [org.json] so it
 * needs no third-party networking dependency. All calls are blocking and must be invoked off the
 * main thread (the agent runs them on Dispatchers.IO).
 */
class MdmApi(private val context: Context) {

    private val base get() = RemoteConfig.serverUrl(context)

    /** A command pulled from the server. */
    data class RemoteCommand(val id: String, val type: String, val payload: JSONObject)

    /**
     * Enroll this device against rmsoft-server. Two-step: log in with the device @rmsoft.rw account to
     * get a JWT, then POST /api/enroll to receive the deviceId + MQTT creds. Saves both to
     * [RemoteConfig] on success; the persistent MQTT link ([MqttManager]) takes over from there.
     * Returns true on success. No-ops (false) until enrollment credentials are provisioned.
     */
    fun enroll(name: String, model: String, androidSdk: Int, appVersion: String): Boolean {
        if (!RemoteConfig.hasEnrollCredentials(context)) return false // awaiting QR/login provisioning

        // 1. Login → JWT.
        val token = login() ?: return false

        // 2. Enroll → deviceId + MQTT creds.
        val body = JSONObject()
            .put("serialNumber", deviceSerial())
            .put("model", model)
            .put("androidVersion", android.os.Build.VERSION.RELEASE)
            .put("romBuild", android.os.Build.DISPLAY)
        hardwareSerial()?.let { body.put("hardwareSerial", it) }

        val res = request("POST", "/api/enroll", body, auth = false, bearer = token) ?: return false
        val deviceId = res.optString("deviceId").ifBlank { return false }
        val mqtt = res.optJSONObject("mqtt") ?: return false

        RemoteConfig.saveEnrollment(context, deviceId, token)
        RemoteConfig.saveMqtt(
            context,
            url = mqtt.getString("url"),
            username = mqtt.getString("username"),
            password = mqtt.getString("password"),
            commandTopic = mqtt.getString("commandTopic"),
            ackTopic = mqtt.getString("ackTopic"),
            locationTopic = mqtt.getString("locationTopic"),
        )
        return true
    }

    /** Log in with the device @rmsoft.rw account; returns the access token or null. */
    private fun login(): String? {
        val body = JSONObject()
            .put("email", RemoteConfig.enrollEmail(context))
            .put("password", RemoteConfig.enrollPassword(context))
        val res = request("POST", "/api/auth/login", body, auth = false) ?: return null
        return res.optString("accessToken").ifBlank { null }
    }

    /** Real hardware serial — readable because RMLauncher is Device Owner (else null). */
    private fun hardwareSerial(): String? = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) android.os.Build.getSerial()
        else @Suppress("DEPRECATION") android.os.Build.SERIAL
    } catch (e: SecurityException) {
        null
    }

    /**
     * Stable device identity. Prefers the real hardware serial (constant across factory resets, so a
     * wiped + re-enrolled phone is the SAME record instead of a duplicate); falls back to ANDROID_ID.
     */
    private fun deviceSerial(): String {
        hardwareSerial()?.takeIf { it.isNotBlank() && it != "unknown" }?.let { return it }
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        ) ?: "unknown-${android.os.Build.DISPLAY}"
    }

    /** Poll for pending commands (server marks them sent). */
    fun fetchCommands(): List<RemoteCommand> {
        val res = get("/api/device/commands") ?: return emptyList()
        val arr = res.optJSONArray("commands") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            RemoteCommand(c.getString("id"), c.getString("type"), c.optJSONObject("payload") ?: JSONObject())
        }
    }

    fun ack(commandId: String, success: Boolean, result: String) {
        post(
            "/api/device/ack",
            JSONObject()
                .put("commandId", commandId)
                .put("status", if (success) "acked" else "failed")
                .put("result", result),
        )
    }

    fun postTelemetry(
        battery: Int,
        deviceOwner: Boolean,
        lockTaskActive: Boolean,
        statusBarDisabled: Boolean,
        cameraDisabled: Boolean,
        keyguardDisabled: Boolean,
        restrictions: List<String>,
        lat: Double?,
        lng: Double?,
        appVersion: String,
        whitelist: List<String>,
    ) {
        val body = JSONObject()
            .put("battery", battery)
            .put("deviceOwner", deviceOwner)
            .put("lockTaskActive", lockTaskActive)
            .put("statusBarDisabled", statusBarDisabled)
            .put("cameraDisabled", cameraDisabled)
            .put("keyguardDisabled", keyguardDisabled)
            .put("restrictions", JSONArray(restrictions))
            .put("appVersion", appVersion)
            .put("whitelist", JSONArray(whitelist))
        if (lat != null && lng != null) {
            body.put("lat", lat).put("lng", lng)
        }
        post("/api/device/telemetry", body)
    }

    /** Upload the device's launchable-app inventory for the dashboard app picker. */
    fun postApps(apps: List<com.rmsoft.launcher.utils.AppInventory.Entry>) {
        val arr = JSONArray()
        apps.forEach {
            arr.put(
                JSONObject()
                    .put("packageName", it.packageName)
                    .put("label", it.label)
                    .put("system", it.system),
            )
        }
        post("/api/device/apps", JSONObject().put("apps", arr))
    }

    // ─── HTTP plumbing ──────────────────────────────────────────────────────────

    private fun get(path: String): JSONObject? = request("GET", path, null, auth = true)

    private fun post(path: String, body: JSONObject, auth: Boolean = true): JSONObject? =
        request("POST", path, body, auth)

    /**
     * [auth] attaches the persisted device token; [bearer] attaches an explicit token (used during
     * enrollment, before the token is saved). Pass one or the other.
     */
    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        auth: Boolean,
        bearer: String? = null,
    ): JSONObject? {
        return runCatching {
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json")
                val authToken = bearer ?: if (auth) RemoteConfig.deviceToken(context) else null
                authToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toString().toByteArray()) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            conn.disconnect()
            if (code in 200..299 && text.isNotBlank()) JSONObject(text) else null
        }.getOrNull()
    }
}

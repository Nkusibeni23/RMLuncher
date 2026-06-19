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

    /** Enroll this device; returns true on success (token saved to [RemoteConfig]). */
    fun enroll(name: String, model: String, androidSdk: Int, appVersion: String): Boolean {
        val body = JSONObject()
            .put("enrollmentSecret", RemoteConfig.enrollmentSecret(context))
            .put("name", name)
            .put("model", model)
            .put("androidSdk", androidSdk)
            .put("appVersion", appVersion)
        RemoteConfig.deviceId(context)?.let { body.put("deviceId", it) }

        val res = post("/api/device/enroll", body, auth = false) ?: return false
        val deviceId = res.optString("deviceId").ifBlank { return false }
        val token = res.optString("token").ifBlank { return false }
        RemoteConfig.saveEnrollment(context, deviceId, token)
        return true
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

    // ─── HTTP plumbing ──────────────────────────────────────────────────────────

    private fun get(path: String): JSONObject? = request("GET", path, null, auth = true)

    private fun post(path: String, body: JSONObject, auth: Boolean = true): JSONObject? =
        request("POST", path, body, auth)

    private fun request(method: String, path: String, body: JSONObject?, auth: Boolean): JSONObject? {
        return runCatching {
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json")
                if (auth) RemoteConfig.deviceToken(context)?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
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

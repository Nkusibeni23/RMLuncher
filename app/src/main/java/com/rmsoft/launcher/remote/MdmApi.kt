package com.rmsoft.launcher.remote

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
     * ZERO-TOUCH enrollment: the phone enrolls itself on first boot with the shared secret baked into
     * RMSoft OS — NO user login, no dial code, no user action. POSTs the baked secret + the hardware
     * serial to /api/device/enroll; the server auto-creates the device record (assigned to the default
     * admin owner) and hands back the MQTT creds. The device then appears in the dashboard on its own.
     * Returns true on success.
     */
    fun enroll(name: String, model: String, androidSdk: Int, appVersion: String): Boolean {
        val body = JSONObject()
            .put("enrollmentSecret", RemoteConfig.enrollmentSecret(context))
            .put("serialNumber", deviceSerial())
            .put("model", model)
            .put("androidVersion", android.os.Build.VERSION.RELEASE)
            .put("romBuild", android.os.Build.DISPLAY)
        hardwareSerial()?.let { body.put("hardwareSerial", it) }

        val res = request("POST", "/api/device/enroll", body, auth = false) ?: return false
        val deviceId = res.optString("deviceId").ifBlank { return false }
        val mqtt = res.optJSONObject("mqtt") ?: return false

        RemoteConfig.saveEnrollment(context, deviceId, "") // no user token in zero-touch
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

    /**
     * True hardware serial (e.g. 3B240DLJH0013A) — the identity that survives factory resets, so a
     * wiped + re-enrolled phone stays ONE record instead of duplicating.
     *
     * Reading it needs privilege: [android.os.Build.getSerial] only works when RMLauncher runs as the
     * privileged system app baked into RMSoft OS (with READ_PRIVILEGED_PHONE_STATE allowlisted) or as
     * Device Owner. A plain sideloaded /data build can't hold that permission and gets a
     * SecurityException — so we fall back to reading the raw `ro.boot.serialno` boot property, which
     * the privileged system app can read. Returns null only when nothing is readable.
     */
    private fun hardwareSerial(): String? {
        // 1. Standard API — succeeds for the privileged system app / Device Owner.
        runCatching {
            val s = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                android.os.Build.getSerial()
            else @Suppress("DEPRECATION") android.os.Build.SERIAL
            if (!s.isNullOrBlank() && s != android.os.Build.UNKNOWN) return s
        }
        // 2. Privileged-app fallback: the raw serial straight from the boot properties.
        for (prop in listOf("ro.boot.serialno", "ro.serialno")) {
            runCatching {
                val sp = Class.forName("android.os.SystemProperties")
                val get = sp.getMethod("get", String::class.java)
                val v = get.invoke(null, prop) as? String
                if (!v.isNullOrBlank()) return v
            }
        }
        return null
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
                // Trust our own MDM server explicitly: some AOSP builds reject this server's (valid)
                // Let's Encrypt chain, which silently killed enrollment. Mirrors the MQTT broker trust.
                if (this is HttpsURLConnection) {
                    sslSocketFactory = trustingSocketFactory()
                    setHostnameVerifier { _, _ -> true }
                }
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
            if (code in 200..299 && text.isNotBlank()) {
                JSONObject(text)
            } else {
                Log.w(TAG, "request $method $path -> HTTP $code: ${text.take(200)}")
                null
            }
        }.onFailure {
            Log.w(TAG, "request $method $path failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    /** Trust-all TLS for our own MDM server (see the note in [request]). */
    private fun trustingSocketFactory(): SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, java.security.SecureRandom())
        return ctx.socketFactory
    }

    companion object {
        private const val TAG = "RMSOFTApi"
    }
}

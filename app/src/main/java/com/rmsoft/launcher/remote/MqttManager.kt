package com.rmsoft.launcher.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.util.UUID

/**
 * Owns the MQTT connection lifecycle (Eclipse Paho — reliable on Android, unlike the HiveMQ/Netty
 * client which hangs). Real-time replacement for the 15s HTTP poll: subscribes to the device's
 * command topic and pushes acks / location / heartbeat back to rmsoft-server. Creds + topics come
 * from [RemoteConfig] (populated at enrollment). Resolution + connect run off the main thread.
 *
 * Ported from the RmsoftMdm agent's proven MQTT stack, adapted to RMLauncher's [MdmApi.RemoteCommand]
 * and org.json (no kotlinx.serialization dependency).
 */
class MqttManager(private val context: Context) {

    private val tag = "MqttManager"

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var client: MqttAsyncClient? = null
    private var disconnectedAt = 0L // when the link dropped, for the wedged-client safety reset
    private val WEDGE_RESET_MS = 180_000L // if auto-reconnect can't recover in 3 min, hard-reset
    private var commandListener: ((MdmApi.RemoteCommand) -> Unit)? = null

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String? = null) : State
    }

    val isConnected: Boolean get() = _state.value is State.Connected

    fun setCommandListener(listener: (MdmApi.RemoteCommand) -> Unit) {
        commandListener = listener
    }

    fun connect() {
        // Guard against duplicate connects (the agent loop can call this repeatedly).
        if (_state.value is State.Connecting || _state.value is State.Connected) {
            Log.i(tag, "already ${_state.value} — skipping duplicate connect")
            return
        }
        // CRITICAL: if a client already exists, Paho's automaticReconnect is recovering it. Opening a
        // SECOND client with our stable id makes the broker kick them in an endless loop (a
        // connection storm — the phone never stays online). So don't open a rival — UNLESS it's been
        // wedged offline too long, in which case tear it down and open a fresh one.
        client?.let { existing ->
            val downMs = if (disconnectedAt == 0L) 0L else System.currentTimeMillis() - disconnectedAt
            if (downMs < WEDGE_RESET_MS) {
                Log.i(tag, "client exists (auto-reconnecting, down ${downMs}ms) — not opening a rival")
                return
            }
            Log.w(tag, "client wedged ${downMs}ms — hard reset before reconnecting")
            runCatching { existing.setCallback(null); existing.disconnectForcibly(0, 0); existing.close() }
            client = null
        }
        val rawUrl = RemoteConfig.mqttUrl(context) ?: run { Log.w(tag, "no MQTT URL — not enrolled?"); return }
        val cmdTopic = RemoteConfig.commandTopic(context) ?: run { Log.w(tag, "no command topic"); return }
        val username = RemoteConfig.mqttUsername(context)
        val password = RemoteConfig.mqttPassword(context)
        _state.value = State.Connecting

        // Resolve + connect off the main thread. Force IPv4 for plain TCP: some brokers refuse MQTT
        // over IPv6 and Android prefers the AAAA record on IPv6 networks → ECONNREFUSED.
        Thread {
            try {
                val uri = URI(rawUrl)
                val ws = uri.scheme == "ws" || uri.scheme == "wss"
                val tls = uri.scheme == "mqtts" || uri.scheme == "ssl" || uri.scheme == "wss"
                val host = uri.host
                val serverUri = if (ws) {
                    // MQTT-over-WebSocket — connects on 443, so it works on locked-down WiFi that
                    // blocks 8883. Paho needs the FULL ws(s)://host:port/path (keep the /mqtt path).
                    val port = if (uri.port > 0) uri.port else if (tls) 443 else 80
                    val path = uri.path.ifEmpty { "/" }
                    "${if (tls) "wss" else "ws"}://$host:$port$path"
                } else {
                    // Native TCP MQTT. Force IPv4 for plain TCP (some brokers refuse IPv6 → ECONNREFUSED).
                    val scheme = if (tls) "ssl" else "tcp"
                    val port = if (uri.port > 0) uri.port else if (tls) 8883 else 1883
                    val connectHost = if (tls) host else
                        InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress ?: host
                    "$scheme://$connectHost:$port"
                }
                Log.i(tag, "connecting to $serverUri (host=$host)")

                // Stable client id per device — a fresh random id every reconnect leaked broker
                // sessions and churned connections. Same id → the broker cleanly replaces the old
                // session instead of piling up orphans.
                val clientId = "rmsoft-launcher-${RemoteConfig.deviceId(context) ?: "pending"}"
                val c = MqttAsyncClient(serverUri, clientId, MemoryPersistence())
                client = c

                c.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.i(tag, "connected (reconnect=$reconnect)")
                        _state.value = State.Connected
                        disconnectedAt = 0L
                        runCatching { c.subscribe(cmdTopic, 1) }
                            .onSuccess { Log.i(tag, "subscribed to $cmdTopic") }
                            .onFailure { Log.e(tag, "subscribe failed", it) }
                    }
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(tag, "connection lost: ${cause?.message}")
                        _state.value = State.Disconnected(cause?.message)
                        if (disconnectedAt == 0L) disconnectedAt = System.currentTimeMillis()
                    }
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.payload?.let { String(it) } ?: return
                        Log.d(tag, "rx command: $payload")
                        runCatching {
                            val obj = JSONObject(payload)
                            MdmApi.RemoteCommand(
                                id = obj.getString("cmdId"),
                                type = obj.getString("type"),
                                payload = obj.optJSONObject("payload") ?: JSONObject(),
                            )
                        }.onSuccess { commandListener?.invoke(it) }
                            .onFailure { Log.e(tag, "bad command payload", it) }
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    keepAliveInterval = 60
                    connectionTimeout = 15
                    isAutomaticReconnect = true
                    if (!username.isNullOrEmpty()) userName = username
                    if (!password.isNullOrEmpty()) this.password = password.toCharArray()
                    // Our broker (Mosquitto on the VPS) uses a self-signed cert. The link is still
                    // TLS-encrypted; we just accept the cert because our own CA isn't in Android's
                    // trust store. The per-device username/password is what authenticates us.
                    if (tls) socketFactory = trustingSocketFactory()
                }

                c.connect(opts, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) { /* connectComplete handles it */ }
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.e(tag, "connect failed", exception)
                        _state.value = State.Disconnected(exception?.message)
                        if (disconnectedAt == 0L) disconnectedAt = System.currentTimeMillis()
                    }
                })
            } catch (e: Exception) {
                Log.e(tag, "connect setup failed", e)
                _state.value = State.Disconnected(e.message)
            }
        }.start()
    }

    /**
     * TLS socket factory that accepts our broker's self-signed certificate. The connection is
     * encrypted, but the broker's identity is not verified (our own CA isn't in Android's trust
     * store) — the per-device MQTT password is what authenticates and protects the channel.
     * Hardening TODO: pin the CA (ship ca.crt in res/raw) or move to a Let's Encrypt cert so the
     * broker's identity is verified too.
     */
    private fun trustingSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        })
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, java.security.SecureRandom())
        return ctx.socketFactory
    }

    // ─── Publish helpers (topics come from RemoteConfig) ─────────────────────────

    /** Ack a command back to rmsoft-server so the dashboard shows ACKED / failed. */
    fun publishAck(cmdId: String, ok: Boolean, errorMessage: String? = null) {
        val topic = RemoteConfig.ackTopic(context) ?: return
        val body = JSONObject()
            .put("cmdId", cmdId)
            .put("ok", ok)
        if (errorMessage != null) body.put("errorMessage", errorMessage)
        publish(topic, body.toString())
    }

    /** Push a location fix to the device's location topic (Track / LOCATE_NOW). */
    fun publishLocation(lat: Double, lng: Double, accuracy: Float? = null) {
        val topic = RemoteConfig.locationTopic(context) ?: return
        // Field names must match rmsoft-server's handleLocation: latitude/longitude/accuracyM.
        val body = JSONObject()
            .put("latitude", lat)
            .put("longitude", lng)
            .put("source", "agent")
        if (accuracy != null) body.put("accuracyM", accuracy.toDouble())
        publish(topic, body.toString())
    }

    /** Heartbeat so the dashboard shows the device online; carries live telemetry when provided. */
    fun publishHeartbeat(telemetry: JSONObject? = null) {
        val topic = RemoteConfig.heartbeatTopic(context) ?: return
        val body = JSONObject().put("ts", System.currentTimeMillis())
        telemetry?.keys()?.forEach { k -> body.put(k, telemetry.get(k)) }
        publish(topic, body.toString())
    }

    /** Anti-theft alert (e.g. SIM_SWAP) to device/{id}/events — server escalates to LOST. */
    fun publishEvent(type: String, info: String? = null) {
        val deviceId = RemoteConfig.deviceId(context) ?: return
        val body = JSONObject().put("type", type)
        if (info != null) body.put("info", info)
        publish("device/$deviceId/events", body.toString())
    }

    /**
     * Send a WiFi + cell scan to device/{id}/scan so the server can resolve it to a position via the
     * Google Geolocation API — the indoor half of LOCATE. [cmdId] lets the server ack the LOCATE
     * command once it has the fix.
     */
    fun publishScan(cmdId: String, scan: NetworkScanner.Scan) {
        val deviceId = RemoteConfig.deviceId(context) ?: return
        val body = JSONObject()
            .put("cmdId", cmdId)
            .put("wifi", scan.wifi)
            .put("cells", scan.cells)
        publish("device/$deviceId/scan", body.toString())
    }

    fun publish(topic: String, payload: String, qos: Int = 1) {
        val c = client ?: run { Log.w(tag, "publish before connect"); return }
        try {
            c.publish(topic, MqttMessage(payload.toByteArray()).apply { this.qos = qos })
        } catch (e: MqttException) {
            Log.e(tag, "publish failed", e)
        }
    }

    fun disconnect() {
        runCatching { client?.disconnect() }
        client = null
        _state.value = State.Disconnected("manual")
    }
}

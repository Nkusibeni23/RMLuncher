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
                val tls = uri.scheme == "mqtts" || uri.scheme == "ssl"
                val scheme = if (tls) "ssl" else "tcp"
                val port = if (uri.port > 0) uri.port else if (tls) 8883 else 1883
                val host = uri.host
                val connectHost = if (tls) host else
                    InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress ?: host
                val serverUri = "$scheme://$connectHost:$port"
                Log.i(tag, "connecting to $serverUri (host=$host)")

                val c = MqttAsyncClient(serverUri, "rmsoft-launcher-${UUID.randomUUID()}", MemoryPersistence())
                client = c

                c.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.i(tag, "connected (reconnect=$reconnect)")
                        _state.value = State.Connected
                        runCatching { c.subscribe(cmdTopic, 1) }
                            .onSuccess { Log.i(tag, "subscribed to $cmdTopic") }
                            .onFailure { Log.e(tag, "subscribe failed", it) }
                    }
                    override fun connectionLost(cause: Throwable?) {
                        Log.w(tag, "connection lost: ${cause?.message}")
                        _state.value = State.Disconnected(cause?.message)
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
                }

                c.connect(opts, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) { /* connectComplete handles it */ }
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.e(tag, "connect failed", exception)
                        _state.value = State.Disconnected(exception?.message)
                    }
                })
            } catch (e: Exception) {
                Log.e(tag, "connect setup failed", e)
                _state.value = State.Disconnected(e.message)
            }
        }.start()
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
        val body = JSONObject()
            .put("lat", lat)
            .put("lng", lng)
        if (accuracy != null) body.put("accuracy", accuracy.toDouble())
        publish(topic, body.toString())
    }

    /** Heartbeat so the dashboard shows the device online. */
    fun publishHeartbeat() {
        val topic = RemoteConfig.heartbeatTopic(context) ?: return
        publish(topic, JSONObject().put("ts", System.currentTimeMillis()).toString())
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

package com.rmsoft.launcher.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the MDM device agent over **MQTT** (real-time push) instead of the
 * old 15s HTTP poll: enroll once → connect to rmsoft-server's broker → receive commands instantly →
 * execute via [CommandExecutor] → ack, and pulse a heartbeat + location so the RMSoft dashboard
 * shows the device online and locatable.
 *
 * Started on boot and from the launcher. [MdmApi] is still used for the one-time HTTP enrollment
 * (which hands back the MQTT creds); everything after that flows over the persistent MQTT link.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api by lazy { MdmApi(this) }
    private val executor by lazy { CommandExecutor(this) }
    private val mqtt by lazy { MqttManager(this) }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        scope.launch { runAgent() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private suspend fun runAgent() {
        // 1. Enroll once (HTTP) until rmsoft-server hands us MQTT creds. Retries on failure.
        while (scope.isActive && !RemoteConfig.hasMqtt(this)) {
            runCatching { ensureEnrolled() }.onFailure { Log.w(TAG, "enroll failed: ${it.message}") }
            if (!RemoteConfig.hasMqtt(this)) delay(ENROLL_RETRY_MS)
        }
        if (!scope.isActive) return

        // 2. Connect the MQTT push channel; commands are handled the instant they arrive.
        mqtt.setCommandListener { cmd -> scope.launch { handleCommand(cmd) } }
        mqtt.connect()

        // 3. Heartbeat + location pulse so the dashboard shows online + locatable.
        while (scope.isActive) {
            if (mqtt.isConnected) {
                mqtt.publishHeartbeat()
                publishLocationBestEffort()
            } else {
                mqtt.connect() // no-op if already connecting/connected; recovers a dropped link
            }
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun ensureEnrolled() {
        if (RemoteConfig.hasMqtt(this)) return
        // MdmApi.enroll posts to rmsoft-server and, on success, saves the MQTT creds into RemoteConfig.
        api.enroll(
            name = "${Build.MANUFACTURER} ${Build.MODEL}",
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidSdk = Build.VERSION.SDK_INT,
            appVersion = appVersion(),
        )
    }

    // ─── Command handling ────────────────────────────────────────────────────────

    private suspend fun handleCommand(cmd: MdmApi.RemoteCommand) {
        when (cmd.type) {
            "LOCATE_NOW" -> {
                val loc = freshFixOrLastKnown()
                if (loc != null) {
                    mqtt.publishLocation(loc.latitude, loc.longitude, loc.accuracy)
                    mqtt.publishAck(cmd.id, true, "location sent")
                } else {
                    mqtt.publishAck(cmd.id, false, "no location available")
                }
            }
            "WIPE", "FACTORY_RESET" -> {
                // wipeDevice() tears down the process immediately, so ack FIRST and give the MQTT
                // client a moment to flush. If the wipe itself throws (executor catches it), the
                // process survives and we send a failure ack so the dashboard shows the truth.
                mqtt.publishAck(cmd.id, true, "wipe started")
                delay(1200)
                val r = executor.execute(cmd)
                if (!r.success) mqtt.publishAck(cmd.id, false, r.message)
            }
            else -> {
                val r = executor.execute(cmd)
                mqtt.publishAck(cmd.id, r.success, if (r.success) null else r.message)
            }
        }
    }

    // ─── Location ────────────────────────────────────────────────────────────────

    @Volatile
    private var cachedFix: Location? = null

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun publishLocationBestEffort() {
        val loc = freshFixOrLastKnown() ?: return
        if (mqtt.isConnected) mqtt.publishLocation(loc.latitude, loc.longitude, loc.accuracy)
    }

    /**
     * Newest of every provider's last-known position and the most recent actively-requested fix.
     * Also kicks off a fresh request so a sealed kiosk — which runs no other location app — still
     * produces a position. Null if permission is absent or nothing has produced a fix yet.
     */
    private fun freshFixOrLastKnown(): Location? {
        if (!hasLocationPermission()) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        requestFreshFix(lm)
        return runCatching {
            (listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { lm.isProviderEnabled(it) }
                .mapNotNull { lm.getLastKnownLocation(it) } + listOfNotNull(cachedFix))
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    private fun requestFreshFix(lm: LocationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { lm.isProviderEnabled(it) }
                .forEach { provider ->
                    lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(this)) { loc ->
                        if (loc != null && loc.time >= (cachedFix?.time ?: 0L)) cachedFix = loc
                    }
                }
        }
    }

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" }.getOrDefault("?")

    // ─── Foreground notification ────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "RMSOFT Agent", NotificationManager.IMPORTANCE_MIN),
                )
            }
            return Notification.Builder(this, CHANNEL)
                .setContentTitle("RMSOFT")
                .setContentText("Device management active")
                .setSmallIcon(applicationInfo.icon)
                .setOngoing(true)
                .build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle("RMSOFT")
            .setContentText("Device management active")
            .setSmallIcon(applicationInfo.icon)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { mqtt.disconnect() }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "RMSOFTAgent"
        private const val CHANNEL = "rmsoft_agent"
        private const val NOTIF_ID = 4711
        private const val HEARTBEAT_INTERVAL_MS = 60_000L // 1 min — responsive online status
        private const val ENROLL_RETRY_MS = 15_000L

        /** Start the agent if it isn't already running. Safe to call repeatedly. */
        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

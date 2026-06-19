package com.rmsoft.launcher.remote

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.rmsoft.launcher.utils.AppWhitelist
import com.rmsoft.launcher.utils.DeviceOwnerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that runs the MDM device agent loop: ensure enrolled → poll for commands →
 * execute via [CommandExecutor] → ack → report telemetry, every [RemoteConfig.POLL_INTERVAL_MS].
 *
 * Started on boot and from the launcher. Polling (rather than push) is used because the sealed
 * kiosk cannot accept inbound connections — see the platform README.
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api by lazy { MdmApi(this) }
    private val executor by lazy { CommandExecutor(this) }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private suspend fun loop() {
        while (scope.isActive) {
            runCatching { tick() }.onFailure { Log.w(TAG, "agent tick failed: ${it.message}") }
            delay(RemoteConfig.POLL_INTERVAL_MS)
        }
    }

    private fun tick() {
        // 1. Enroll once if we have no token yet.
        if (!RemoteConfig.isEnrolled(this)) {
            val ok = api.enroll(
                name = "${Build.MANUFACTURER} ${Build.MODEL}",
                model = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidSdk = Build.VERSION.SDK_INT,
                appVersion = appVersion(),
            )
            if (!ok) return // try again next tick
        }

        // 2. Pull + execute + ack any pending commands.
        api.fetchCommands().forEach { cmd ->
            val result = executor.execute(cmd)
            api.ack(cmd.id, result.success, result.message)
        }

        // 3. Report telemetry.
        val owner = DeviceOwnerManager(this)
        val location = lastKnownLocation()
        api.postTelemetry(
            battery = batteryPercent(),
            deviceOwner = owner.isDeviceOwner(),
            lockTaskActive = lockTaskActive(),
            statusBarDisabled = owner.isStatusBarDisabled(),
            cameraDisabled = owner.isCameraDisabled(),
            keyguardDisabled = owner.isKeyguardDisabled(),
            restrictions = owner.activeUserRestrictions(),
            lat = location?.latitude,
            lng = location?.longitude,
            appVersion = appVersion(),
            whitelist = AppWhitelist.getWhitelistedPackages(this),
        )

        // 4. Commit any staged server-URL switch now that the ack + telemetry above have reached
        // the old server. The next tick polls the new URL (and re-enrolls if requested).
        RemoteConfig.applyPendingServer(this)?.let { Log.i(TAG, "server endpoint switched → $it") }
    }

    // ─── Telemetry helpers ──────────────────────────────────────────────────────

    private fun batteryPercent(): Int =
        (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun lockTaskActive(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    /**
     * Best last-known fix from GPS or network provider. Returns null if location permission is
     * absent (Device Owner auto-grants it via [DeviceOwnerManager.applyAllPolicies]) or no provider
     * has a fix yet. Uses last-known rather than active updates to keep the sealed kiosk's battery
     * and footprint minimal.
     */
    private fun lastKnownLocation(): Location? {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { lm.isProviderEnabled(it) }
                .mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" }.getOrDefault("?")

    // ─── Foreground notification ────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "RMSOFT Agent", NotificationManager.IMPORTANCE_MIN)
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
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "RMSOFTAgent"
        private const val CHANNEL = "rmsoft_agent"
        private const val NOTIF_ID = 4711

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

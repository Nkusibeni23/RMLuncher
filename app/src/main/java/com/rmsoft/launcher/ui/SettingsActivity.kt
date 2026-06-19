package com.rmsoft.launcher.ui

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.rmsoft.launcher.R
import com.rmsoft.launcher.databinding.ActivitySettingsBinding

/**
 * Restricted, branded Settings screen for the RMSOFT kiosk launcher.
 *
 * Intentionally exposes only Network (Wi-Fi), Display (brightness), Sound (volume) and
 * read-only Device info — no factory reset, developer options, accounts, app management
 * or security settings.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val batteryManager by lazy { getSystemService(BATTERY_SERVICE) as BatteryManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWifi()
        setupBrightness()
        setupVolume()
    }

    override fun onResume() {
        super.onResume()
        // Reflect any external changes when returning to this screen.
        syncBrightness()
        syncVolume()
        showDeviceInfo()
    }

    // ─── Network ────────────────────────────────────────────────────────────────

    private fun setupWifi() {
        binding.wifiRow.setOnClickListener {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_WIFI)
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            runCatching { startActivity(intent) }
        }
    }

    // ─── Display: system brightness (requires WRITE_SETTINGS) ─────────────────────

    private fun setupBrightness() {
        syncBrightness()
        binding.brightnessSeek.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (Settings.System.canWrite(this@SettingsActivity)) {
                    applyBrightness(progress)
                } else {
                    requestWriteSettings()
                }
            }
        })
    }

    private fun syncBrightness() {
        val current = Settings.System.getInt(
            contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128
        )
        binding.brightnessSeek.progress = current
    }

    private fun applyBrightness(value: Int) {
        val brightness = value.coerceIn(1, 255)
        Settings.System.putInt(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
        // Immediate visual feedback for the current window.
        window.attributes = window.attributes.apply { screenBrightness = brightness / 255f }
    }

    private fun requestWriteSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    // ─── Sound: media / ringtone / call volume ────────────────────────────────────

    private fun setupVolume() {
        bindVolume(binding.mediaSeek, AudioManager.STREAM_MUSIC)
        bindVolume(binding.ringSeek, AudioManager.STREAM_RING)
        bindVolume(binding.callSeek, AudioManager.STREAM_VOICE_CALL)
    }

    private fun bindVolume(seekBar: SeekBar, stream: Int) {
        seekBar.max = audioManager.getStreamMaxVolume(stream)
        seekBar.progress = audioManager.getStreamVolume(stream)
        seekBar.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    runCatching { audioManager.setStreamVolume(stream, progress, 0) }
                }
            }
        })
    }

    private fun syncVolume() {
        binding.mediaSeek.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.ringSeek.progress = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        binding.callSeek.progress = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
    }

    // ─── Device info (read-only) ──────────────────────────────────────────────────

    private fun showDeviceInfo() {
        binding.deviceName.text = deviceName()
        binding.androidVersion.text =
            getString(
                R.string.settings_android_version_value,
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT
            )
        binding.storageInfo.text = storageSummary()
        binding.batteryInfo.text = "${batteryManager.getIntProperty(
            BatteryManager.BATTERY_PROPERTY_CAPACITY
        )}%"
    }

    private fun deviceName(): String {
        val fromSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        } else {
            null
        }
        return fromSettings?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private fun storageSummary(): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        val available = stat.availableBytes
        val used = total - available
        return getString(
            R.string.settings_storage_value,
            Formatter.formatShortFileSize(this, used),
            Formatter.formatShortFileSize(this, total),
            Formatter.formatShortFileSize(this, available)
        )
    }

    /** SeekBar listener that only needs onProgressChanged. */
    private abstract class SimpleSeekBarListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}

package com.rmsoft.launcher.remote

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Plays a loud alarm to help locate a lost phone — even on silent. Forces the alarm stream to max
 * and loops the default alarm tone via [MediaPlayer] (works on API 26+, unlike Ringtone.isLooping
 * which needs API 28). Auto-stops after [DEFAULT_DURATION_MS]; a MESSAGE/UNLOCK or a new RING stops
 * an in-flight ring first.
 */
object Ringer {
    private const val TAG = "Ringer"
    const val DEFAULT_DURATION_MS = 30_000L

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var savedAlarmVolume: Int? = null

    @Synchronized
    fun start(context: Context, durationMs: Long = DEFAULT_DURATION_MS) {
        stop(context)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
        }.onFailure { Log.w(TAG, "could not raise alarm volume: ${it.message}") }

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
            handler.postDelayed({ stop(context) }, durationMs)
            Log.i(TAG, "ringing for ${durationMs}ms")
        }.onFailure { Log.e(TAG, "ring failed", it) }
    }

    @Synchronized
    fun stop(context: Context) {
        handler.removeCallbacksAndMessages(null)
        player?.runCatching { if (isPlaying) stop(); release() }
        player = null
        // Restore the user's alarm volume so the phone isn't left blasting on the next real alarm.
        savedAlarmVolume?.let { vol ->
            runCatching {
                (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .setStreamVolume(AudioManager.STREAM_ALARM, vol, 0)
            }
            savedAlarmVolume = null
        }
    }
}

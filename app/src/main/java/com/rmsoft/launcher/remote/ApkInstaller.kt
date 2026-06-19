package com.rmsoft.launcher.remote

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Silent APK installer for the RMSOFT kiosk. Downloads an APK over HTTP and installs it through
 * [PackageInstaller]. The install proceeds **without any user prompt only when the app is Device
 * Owner** — otherwise the system shows the usual confirmation (or blocks it under lockdown).
 */
object ApkInstaller {

    private const val INSTALL_ACTION = "com.rmsoft.launcher.APK_INSTALL_STATUS"

    /**
     * Download [url] (absolute, or relative to [base]) and commit an install session.
     * Returns a short human-readable result string for the command ack.
     */
    fun installFromUrl(context: Context, url: String, base: String): String {
        val fullUrl =
            if (url.startsWith("http", ignoreCase = true)) url
            else base.trimEnd('/') + "/" + url.trimStart('/')

        val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        return try {
            if (conn.responseCode !in 200..299) {
                "download failed: HTTP ${conn.responseCode}"
            } else {
                conn.inputStream.use { writeSession(context, it, conn.contentLengthLong) }
                "install started"
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun writeSession(context: Context, input: InputStream, length: Long) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (length > 0) params.setSize(length)

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("rmsoft_apk", 0, length).use { out ->
                input.copyTo(out)
                session.fsync(out)
            }
            // The system delivers install status to this PendingIntent; Device Owner installs
            // complete silently so we don't need to act on it.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(INSTALL_ACTION).setPackage(context.packageName),
                flags,
            )
            session.commit(pi.intentSender)
        }
    }
}

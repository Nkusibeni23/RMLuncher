package com.rmsoft.launcher.utils

import android.content.Context
import java.security.MessageDigest

/**
 * Stores the admin-panel PIN as a SHA-256 hash in SharedPreferences.
 *
 * On first run the [DEFAULT_PIN] is in effect until the admin changes it from the panel.
 * Change the default before production rollout.
 */
object AdminPinStore {

    /** PIN in effect until changed from the admin panel. */
    const val DEFAULT_PIN = "246813"

    private const val PREFS = "rmsoft_admin"
    private const val KEY_PIN = "pin_sha256"

    fun verify(context: Context, pin: String): Boolean = currentHash(context) == hash(pin)

    fun setPin(context: Context, pin: String) {
        prefs(context).edit().putString(KEY_PIN, hash(pin)).apply()
    }

    private fun currentHash(context: Context): String =
        prefs(context).getString(KEY_PIN, null) ?: hash(DEFAULT_PIN)

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

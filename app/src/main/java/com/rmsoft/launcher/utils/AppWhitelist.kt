package com.rmsoft.launcher.utils

import android.content.Context

/**
 * RMSOFT App Whitelist.
 *
 * Only apps listed here are visible on the device; everything else is hidden by the Device Owner.
 *
 * The whitelist is **persisted** in SharedPreferences so the admin panel can change it at runtime
 * without rebuilding the APK. [DEFAULTS] is the seed list used on first run (and after a reset).
 */
object AppWhitelist {

    private const val PREFS = "rmsoft_whitelist"
    private const val KEY_PACKAGES = "packages"

    /** Seed whitelist applied on first run / after [resetToDefaults]. */
    private val DEFAULTS: List<String> = listOf(

        // ── RMSOFT apps ──────────────────────────────────────────────────────
        // "com.rmsoft.yourapp",            // Add your main app here

        // ── Communication ────────────────────────────────────────────────────
        "com.android.dialer",               // Phone / calls
        "com.android.mms",                  // SMS messages
        "com.rmsoft.pis",                   // Ptis
        "com.android.chrome",
        "com.google.android.documentsui",

        // ── Essential system apps ─────────────────────────────────────────────
        "com.android.camera2",              // Camera (remove if cameras should be disabled)
    )

    /** Current whitelist — the persisted set if present, otherwise [DEFAULTS]. */
    fun getWhitelistedPackages(context: Context): List<String> {
        val stored = prefs(context).getStringSet(KEY_PACKAGES, null)
        return stored?.toList() ?: DEFAULTS
    }

    /**
     * First-run seed: resolve the real stock-app packages on THIS device (Phone, Messages, Contacts,
     * Clock, Calculator, Compass, Camera) and persist them as the whitelist. No-op once a whitelist
     * has been set (by a previous seed or by the admin/dashboard), so it never overrides a choice.
     */
    fun seedStockApps(context: Context) {
        if (prefs(context).contains(KEY_PACKAGES)) return
        val resolved = AppResolver.resolveStockApps(context)
        if (resolved.isNotEmpty()) setWhitelist(context, resolved)
    }

    fun setWhitelist(context: Context, packages: Collection<String>) {
        prefs(context).edit().putStringSet(KEY_PACKAGES, packages.toSet()).apply()
    }

    fun addToWhitelist(context: Context, packageName: String) {
        setWhitelist(context, getWhitelistedPackages(context).toMutableSet().apply { add(packageName) })
    }

    fun removeFromWhitelist(context: Context, packageName: String) {
        setWhitelist(context, getWhitelistedPackages(context).toMutableSet().apply { remove(packageName) })
    }

    fun isWhitelisted(context: Context, packageName: String): Boolean =
        getWhitelistedPackages(context).contains(packageName)

    /**
     * Reset to the device's stock apps — re-resolves real packages on this device and persists them.
     * Use this to recover a device that's holding a stale/wrong whitelist (e.g. AOSP names that don't
     * exist on this OEM). Falls back to clearing the override if nothing resolves.
     */
    fun resetToDefaults(context: Context) {
        val resolved = AppResolver.resolveStockApps(context)
        if (resolved.isNotEmpty()) setWhitelist(context, resolved)
        else prefs(context).edit().remove(KEY_PACKAGES).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

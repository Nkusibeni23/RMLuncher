package com.rmsoft.launcher.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager

/**
 * Resolves the **real** package names of stock apps on *this* device.
 *
 * OEMs ship different packages than AOSP (e.g. UMIDIGI's dialer/camera are not
 * `com.android.dialer` / `com.android.camera2`), so a hardcoded whitelist silently shows nothing.
 * Instead we ask Android which app currently fills each role — by default-app API or intent
 * resolution — which works on any device. Compass has no standard intent, so we scan launchable
 * apps for it by name.
 */
object AppResolver {

    /** Stock apps RMSOFT wants visible in the launcher, resolved to installed packages, in order. */
    fun resolveStockApps(context: Context): List<String> {
        val pm = context.packageManager
        val packages = LinkedHashSet<String>()

        // Phone / dialer — the default-dialer API is the most reliable, with ACTION_DIAL as fallback.
        (defaultDialer(context) ?: resolve(pm, Intent(Intent.ACTION_DIAL)))
            ?.let { packages.add(it) }

        // Messages — the registered default SMS app, else the messaging-category app.
        (Telephony.Sms.getDefaultSmsPackage(context) ?: resolveCategory(pm, Intent.CATEGORY_APP_MESSAGING))
            ?.let { packages.add(it) }

        // Contacts
        (resolveCategory(pm, Intent.CATEGORY_APP_CONTACTS)
            ?: resolve(pm, Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)))
            ?.let { packages.add(it) }

        // Clock — whichever app handles "show alarms".
        resolve(pm, Intent(AlarmClock.ACTION_SHOW_ALARMS))?.let { packages.add(it) }

        // Calculator
        resolveCategory(pm, Intent.CATEGORY_APP_CALCULATOR)?.let { packages.add(it) }

        // Compass — no canonical intent; match a launchable app by package/label.
        findLaunchableByKeyword(pm, "compass")?.let { packages.add(it) }

        // Camera — the still-image camera intent.
        resolve(pm, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))?.let { packages.add(it) }

        return packages.toList()
    }

    private fun defaultDialer(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        else null

    /** Best-match package for an intent, skipping the system resolver/chooser. */
    private fun resolve(pm: PackageManager, intent: Intent): String? =
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != "android" }

    private fun resolveCategory(pm: PackageManager, category: String): String? =
        resolve(pm, Intent(Intent.ACTION_MAIN).addCategory(category))

    /** First launchable app whose package name or label contains [keyword] (case-insensitive). */
    private fun findLaunchableByKeyword(pm: PackageManager, keyword: String): String? {
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(launchable, 0)) {
            val pkg = ri.activityInfo?.packageName ?: continue
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault("")
            if (pkg.contains(keyword, ignoreCase = true) || label.contains(keyword, ignoreCase = true)) {
                return pkg
            }
        }
        return null
    }
}

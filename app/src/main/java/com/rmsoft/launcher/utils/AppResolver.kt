package com.rmsoft.launcher.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager

/**
 * Resolves the **real** package names of stock apps on *this* device (Phone, Messages, Contacts,
 * Clock, Calculator, Compass, Camera).
 *
 * OEMs ship different packages than AOSP (e.g. MediaTek/UMIDIGI), and on stripped ROMs the role
 * APIs return nothing (no default SMS app set, apps don't declare the APP_* categories). A miss here
 * is destructive: an app the resolver doesn't return isn't whitelisted, and the Device Owner purge
 * then uninstalls it. So each role tries several strategies in order — the registered default, intent
 * resolution, then a list of well-known package names — and keeps the first that's actually installed.
 */
object AppResolver {

    /** Stock apps RMSOFT wants visible in the launcher, resolved to installed packages, in order. */
    fun resolveStockApps(context: Context): List<String> {
        val pm = context.packageManager
        val out = LinkedHashSet<String>()

        // Phone / dialer
        firstOf(
            pm,
            defaultDialer(context),
            resolve(pm, Intent(Intent.ACTION_DIAL)),
            firstInstalled(pm, KNOWN_DIALER),
        )?.let { out.add(it) }

        // Messages / SMS
        firstOf(
            pm,
            Telephony.Sms.getDefaultSmsPackage(context),
            resolveCategory(pm, Intent.CATEGORY_APP_MESSAGING),
            firstInstalled(pm, KNOWN_SMS),
        )?.let { out.add(it) }

        // Contacts
        firstOf(
            pm,
            resolveCategory(pm, Intent.CATEGORY_APP_CONTACTS),
            resolve(pm, Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)),
            firstInstalled(pm, KNOWN_CONTACTS),
        )?.let { out.add(it) }

        // Clock
        firstOf(
            pm,
            resolve(pm, Intent(AlarmClock.ACTION_SHOW_ALARMS)),
            firstInstalled(pm, KNOWN_CLOCK),
        )?.let { out.add(it) }

        // Calculator
        firstOf(
            pm,
            resolveCategory(pm, Intent.CATEGORY_APP_CALCULATOR),
            firstInstalled(pm, KNOWN_CALCULATOR),
        )?.let { out.add(it) }

        // Compass — no canonical intent; match a launchable app by name, else a known package.
        (findLaunchableByKeyword(pm, "compass") ?: firstInstalled(pm, KNOWN_COMPASS))
            ?.let { out.add(it) }

        // Camera — the still-image / capture intents, else a known package.
        firstOf(
            pm,
            resolve(pm, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
            resolve(pm, Intent(MediaStore.ACTION_IMAGE_CAPTURE)),
            firstInstalled(pm, KNOWN_CAMERA),
        )?.let { out.add(it) }

        // Browser — the registered default browser (CATEGORY_BROWSABLE web intent), else a known
        // Chromium package (Cromite first — the browser baked into RMSoft OS). Without this the kiosk
        // would hide/purge the browser, leaving only the WebView component (which has no browser UI).
        firstOf(
            pm,
            resolve(
                pm,
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE),
            ),
            firstInstalled(pm, KNOWN_BROWSER),
        )?.let { out.add(it) }

        return out.toList()
    }

    private fun defaultDialer(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        else null

    /** First non-null candidate that resolves to an app actually installed for this user. */
    private fun firstOf(pm: PackageManager, vararg candidates: String?): String? =
        candidates.firstOrNull { it != null && it != "android" && isInstalled(pm, it) }

    /** First package in [known] that's installed for this user. */
    private fun firstInstalled(pm: PackageManager, known: List<String>): String? =
        known.firstOrNull { isInstalled(pm, it) }

    // MATCH_UNINSTALLED_PACKAGES so we also find apps the purge uninstalled-for-user or hid — they're
    // still on the device, and re-whitelisting + unhiding (and install-existing) brings them back.
    private fun isInstalled(pm: PackageManager, pkg: String): Boolean =
        runCatching {
            pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES); true
        }.getOrDefault(false)

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

    // Well-known package names per role — last-resort fallback when the role/intent APIs come up
    // empty on a stripped or non-standard ROM. Only installed ones are kept.
    private val KNOWN_DIALER = listOf("com.google.android.dialer", "com.android.dialer", "com.android.phone")
    private val KNOWN_SMS = listOf("com.google.android.apps.messaging", "com.android.messaging", "com.android.mms")
    private val KNOWN_CONTACTS = listOf("com.google.android.contacts", "com.android.contacts")
    private val KNOWN_CLOCK = listOf("com.google.android.deskclock", "com.android.deskclock")
    private val KNOWN_CALCULATOR = listOf("com.google.android.calculator", "com.android.calculator2")
    private val KNOWN_COMPASS = listOf("com.google.android.apps.compass", "com.android.compass")
    private val KNOWN_CAMERA = listOf(
        "com.mediatek.camera", "com.android.camera2", "com.android.camera",
        "com.google.android.GoogleCamera",
    )
    private val KNOWN_BROWSER = listOf(
        "org.cromite.cromite",   // Cromite — the Chromium browser baked into RMSoft OS
        "com.android.chrome",    // Chrome
        "org.chromium.chrome",   // Chromium
        "org.bromite.bromite",   // Bromite
        "com.brave.browser",     // Brave
    )
}

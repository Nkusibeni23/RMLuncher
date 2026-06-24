package com.rmsoft.launcher.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Snapshot of the device's launchable apps, uploaded to the server so the dashboard can offer a
 * real app picker (label + package + system flag) instead of making the admin type package names.
 */
object AppInventory {

    data class Entry(val packageName: String, val label: String, val system: Boolean)

    /** Every launchable app on the device (excluding this launcher), sorted by label. */
    fun list(context: Context): List<Entry> {
        val pm = context.packageManager
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launchable, 0)
            .mapNotNull { ri ->
                val ai = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (ai.packageName == context.packageName) return@mapNotNull null
                Entry(
                    packageName = ai.packageName,
                    label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(ai.packageName),
                    system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}

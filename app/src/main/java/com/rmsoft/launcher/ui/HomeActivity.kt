package com.rmsoft.launcher.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rmsoft.launcher.R
import com.rmsoft.launcher.utils.DeviceOwnerManager

/**
 * Branded RMSoft OS home screen — an open, usable launcher (NOT the kiosk). Shows a clock, every
 * installed app in a grid, and a dock of favourites, over the RMSoft wallpaper. Registered as a HOME
 * activity so RMSoft OS boots into this instead of the stock launcher. Applies only baseline Device
 * Owner policies (never Lock Task / kiosk), so the phone stays a normal, usable device.
 */
class HomeActivity : AppCompatActivity() {

    private data class AppEntry(val label: String, val icon: Drawable, val launch: Intent)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Baseline policies only — safe no-op if not Device Owner. Never kiosk.
        runCatching { DeviceOwnerManager(this).applyBaselinePolicies() }

        val grid = findViewById<RecyclerView>(R.id.homeGrid)
        grid.layoutManager = GridLayoutManager(this, 4)
        grid.adapter = AppAdapter(loadApps())
    }

    override fun onResume() {
        super.onResume()
        // Reflect apps installed/removed (e.g. a dashboard INSTALL_APK) while we were away.
        val apps = loadApps()
        (findViewById<RecyclerView>(R.id.homeGrid).adapter as? AppAdapter)?.replace(apps)
        populateDock(apps)
    }

    /** Every launchable app on the device, sorted by name. Hides our own home/kiosk entries. */
    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            // Hide our own launcher entries (Home/kiosk), but keep RMSoft Mail (a real user app).
            if (ai.packageName == packageName && ai.name != MailActivity::class.java.name) {
                return@mapNotNull null
            }
            val launch = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(ai.packageName, ai.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            AppEntry(ri.loadLabel(pm).toString(), ri.loadIcon(pm), launch)
        }.sortedBy { it.label.lowercase() }
    }

    /** Fill the dock with Phone / Messages / RMSoft Mail / Camera, falling back to the first apps. */
    private fun populateDock(apps: List<AppEntry>) {
        val dock = findViewById<LinearLayout>(R.id.homeDock)
        dock.removeAllViews()
        fun find(vararg keys: String) =
            apps.firstOrNull { a -> keys.any { a.label.contains(it, ignoreCase = true) } }
        val picks = listOfNotNull(
            find("phone", "dial"),
            find("message", "sms"),
            find("rmsoft mail", "mail"),
            find("camera"),
        ).distinct().ifEmpty { apps.take(4) }.take(4)

        val inflater = LayoutInflater.from(this)
        picks.forEach { e ->
            val v = inflater.inflate(R.layout.item_home_app, dock, false)
            v.findViewById<ImageView>(R.id.appIcon).setImageDrawable(e.icon)
            v.findViewById<TextView>(R.id.appLabel).visibility = View.GONE // dock = icons only
            v.setOnClickListener { launch(e) }
            dock.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun launch(e: AppEntry) {
        runCatching { startActivity(e.launch) }
    }

    private inner class AppAdapter(private var items: List<AppEntry>) :
        RecyclerView.Adapter<AppAdapter.VH>() {

        fun replace(newItems: List<AppEntry>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.appIcon)
            val label: TextView = v.findViewById(R.id.appLabel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_home_app, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.icon.setImageDrawable(e.icon)
            holder.label.text = e.label
            holder.itemView.setOnClickListener { launch(e) }
        }

        override fun getItemCount(): Int = items.size
    }
}

package com.rmsoft.launcher.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import com.rmsoft.launcher.R

/**
 * First-run Welcome, shown ONCE after setup: a branded splash, then a skippable suggestion to use
 * RMSoft Mail. Purely user-facing onboarding — separate from the invisible MDM agent. It marks
 * itself "seen" the first time it appears, so it never returns (not on restart — only on first
 * boot / after a factory reset). Every screen is skippable; no dead ends.
 */
class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        markSeen(this) // shown once — never pop again, even if the user backgrounds it

        val flipper = findViewById<ViewFlipper>(R.id.welcomeFlipper)

        findViewById<Button>(R.id.welcomeGetStarted).setOnClickListener { flipper.displayedChild = 1 }
        findViewById<Button>(R.id.welcomeSkip).setOnClickListener { flipper.displayedChild = 2 }
        findViewById<Button>(R.id.welcomeMailUse).setOnClickListener {
            startActivity(Intent(this, MailActivity::class.java))
            flipper.displayedChild = 2
        }
        findViewById<Button>(R.id.welcomeMailSkip).setOnClickListener { flipper.displayedChild = 2 }
        findViewById<Button>(R.id.welcomeFinish).setOnClickListener { finish() }
    }

    companion object {
        private const val PREFS = "rmsoft_welcome"
        private const val KEY_SEEN = "seen"

        /** Show the Welcome once, on first boot only. No-op after it has been seen. */
        fun launchIfFirstRun(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_SEEN, false)) return
            context.startActivity(
                Intent(context, WelcomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        private fun markSeen(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SEEN, true).apply()
        }
    }
}

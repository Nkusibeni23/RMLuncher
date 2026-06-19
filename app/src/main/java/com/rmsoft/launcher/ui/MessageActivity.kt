package com.rmsoft.launcher.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.rmsoft.launcher.R

/**
 * Full-screen alert that shows an admin-pushed message (SHOW_MESSAGE command) over the kiosk.
 * Launched from [com.rmsoft.launcher.remote.CommandExecutor]; our own package is allow-listed in
 * Lock Task Mode so this is permitted while sealed.
 */
class MessageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the lock screen and keep the screen on while the message is up.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        setContentView(R.layout.activity_message)

        val title = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { null } ?: getString(R.string.app_name)
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()

        findViewById<android.widget.TextView>(R.id.messageTitle).text = title
        findViewById<android.widget.TextView>(R.id.messageBody).text = message
        findViewById<android.widget.Button>(R.id.messageDismiss).setOnClickListener { finish() }
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MESSAGE = "message"

        /** Launch the full-screen message from a non-Activity context (the agent service). */
        fun show(context: Context, title: String, message: String) {
            val intent = Intent(context, MessageActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_MESSAGE, message)
            context.startActivity(intent)
        }
    }
}

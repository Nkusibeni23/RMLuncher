package com.rmsoft.launcher.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.rmsoft.launcher.remote.AgentService
import com.rmsoft.launcher.remote.RemoteConfig

/**
 * Branded first-boot enrollment gate. Shown when the device has neither MQTT creds nor stored
 * enrollment credentials — the operator signs in with their @rmsoft.rw account, which the agent then
 * uses to enroll against rmsoft-server. Polls until enrollment completes, then drops into the
 * launcher. (Unified mail identity — mail.rmsoft.rw — plugs in here later; for now it uses the
 * rmsoft-server @rmsoft.rw accounts.)
 */
class EnrollmentActivity : Activity() {

    private val green = Color.parseColor("#12A85E")
    private val bg = Color.parseColor("#0A0C0B")
    private val fog = Color.parseColor("#F1F3F1")
    private val slate = Color.parseColor("#8C938F")

    private lateinit var status: TextView
    private lateinit var enrollBtn: Button
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            if (RemoteConfig.hasMqtt(this@EnrollmentActivity)) {
                startActivity(
                    Intent(this@EnrollmentActivity, LauncherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                finish()
            } else {
                handler.postDelayed(this, 2000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(dp(28), dp(28), dp(28), dp(28))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // Wordmark: RMsoft OS
        root.addView(TextView(this).apply {
            text = "RMSOFT"
            setTextColor(fog)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        })
        root.addView(TextView(this).apply {
            text = "OS"
            setTextColor(green)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            setPadding(0, dp(2), 0, dp(24))
        })
        root.addView(TextView(this).apply {
            text = "Sign in to enroll this device"
            setTextColor(slate)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        })

        val email = field("Email · you@rmsoft.rw", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT)
        val password = field("Password", InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT)
        root.addView(email)
        root.addView(password)

        status = TextView(this).apply {
            setTextColor(slate)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }

        enrollBtn = Button(this).apply {
            text = "Enroll device"
            setTextColor(Color.WHITE)
            setBackgroundColor(green)
            setTypeface(Typeface.DEFAULT_BOLD)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(52))
            setOnClickListener { submit(email.text.toString().trim(), password.text.toString()) }
        }
        root.addView(enrollBtn)
        root.addView(status)

        setContentView(root)
    }

    private fun submit(email: String, password: String) {
        if (!email.endsWith("@rmsoft.rw", ignoreCase = true)) {
            status.text = "Use your @rmsoft.rw work account."
            return
        }
        if (password.isEmpty()) {
            status.text = "Enter your password."
            return
        }
        RemoteConfig.setEnrollCredentials(this, email, password)
        AgentService.start(this)
        enrollBtn.isEnabled = false
        status.text = "Enrolling…"
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 2000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    private fun field(hint: String, inputType: Int) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(slate)
        setTextColor(fog)
        this.inputType = inputType
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object {
        /** True when the device needs the enrollment wizard (no creds and not enrolled yet). */
        fun isNeeded(activity: Activity): Boolean =
            !RemoteConfig.hasMqtt(activity) && !RemoteConfig.hasEnrollCredentials(activity)

        fun launchIfNeeded(activity: Activity): Boolean {
            if (!isNeeded(activity)) return false
            activity.startActivity(Intent(activity, EnrollmentActivity::class.java))
            return true
        }
    }
}

package com.rmsoft.launcher.ui

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.rmsoft.launcher.utils.AdminPinStore
import com.rmsoft.launcher.utils.AppWhitelist
import com.rmsoft.launcher.utils.DeviceOwnerManager

/**
 * On-device **admin control panel** for the RMSOFT kiosk.
 *
 * Reached by long-pressing the RMSOFT brand title on the launcher, gated by the admin PIN
 * ([AdminPinStore]). Exposes every Device Owner capability — kiosk enter/exit, lock/reboot,
 * per-policy toggles, app show/hide + whitelist editing, and the destructive factory-reset /
 * Device-Owner-removal actions — all driven through [DeviceOwnerManager], the same chokepoint a
 * future remote dashboard would use.
 *
 * The UI is built programmatically (no layout XML) so the panel is fully self-contained.
 */
class AdminActivity : AppCompatActivity() {

    private val deviceOwner by lazy { DeviceOwnerManager(this) }

    private val bg = Color.parseColor("#0A0F1E")
    private val card = Color.parseColor("#16213B")
    private val accent = Color.parseColor("#6BA8FF")
    private val danger = Color.parseColor("#FF6B6B")
    private val textPrimary = Color.parseColor("#FFFFFF")
    private val textSecondary = Color.parseColor("#B3FFFFFF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPinGate()
    }

    // ─── PIN gate ─────────────────────────────────────────────────────────────────

    private fun showPinGate() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "RMSOFT Admin"
            setTextColor(textPrimary)
            textSize = 24f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Enter admin PIN"
            setTextColor(textSecondary)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
        })
        val pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            gravity = Gravity.CENTER
            textSize = 20f
        }
        root.addView(pinInput, matchWidth())
        root.addView(primaryButton("Unlock") {
            if (AdminPinStore.verify(this, pinInput.text.toString())) {
                showPanel()
            } else {
                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                pinInput.text.clear()
            }
        })
        setContentView(root)
    }

    // ─── Panel ────────────────────────────────────────────────────────────────────

    private fun showPanel() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }

        content.addView(title("RMSOFT Admin Panel"))
        content.addView(statusView())

        // Kiosk controls.
        content.addView(sectionHeader("Kiosk"))
        content.addView(primaryButton("Exit kiosk (stop Lock Task)") {
            runCatching { stopLockTask() }
            toast("Lock Task stopped — device is serviceable")
        })
        content.addView(primaryButton("Re-enter kiosk (start Lock Task)") {
            runCatching { startLockTask() }
            toast("Lock Task started")
        })
        content.addView(primaryButton("Re-apply ALL policies") {
            deviceOwner.applyAllPolicies()
            toast("All policies re-applied")
            refresh()
        })
        content.addView(primaryButton("Lock device now") { deviceOwner.lockNow() })
        content.addView(primaryButton("Reboot device") {
            confirm("Reboot device?", "The device will restart and boot back into the kiosk.") {
                deviceOwner.reboot()
            }
        })

        // Policy toggles.
        content.addView(sectionHeader("Policies"))
        content.addView(policySwitch("Disable notification shade / status bar",
            deviceOwner.isStatusBarDisabled()) { deviceOwner.setStatusBarDisabled(it) })
        content.addView(policySwitch("Disable camera",
            deviceOwner.isCameraDisabled()) { deviceOwner.setCameraDisabled(it) })
        content.addView(policySwitch("Disable lock screen (keyguard)",
            deviceOwner.isKeyguardDisabled()) { deviceOwner.setKeyguardDisabled(it) })
        DeviceOwnerManager.MANAGED_RESTRICTIONS.forEach { (key, label) ->
            content.addView(policySwitch(label,
                deviceOwner.isUserRestrictionActive(key)) { deviceOwner.setUserRestriction(key, it) })
        }

        // App management.
        content.addView(sectionHeader("Apps (toggle = visible on kiosk)"))
        content.addView(secondaryButton("Reset whitelist to defaults") {
            confirm("Reset whitelist?", "Restores the built-in default app list.") {
                AppWhitelist.resetToDefaults(this)
                deviceOwner.applyAllPolicies()
                toast("Whitelist reset")
                refresh()
            }
        })
        deviceOwner.installedLaunchableApps().forEach { app ->
            content.addView(policySwitch(
                "${app.label}\n${app.packageName}",
                visible = !app.hidden,
            ) { visible ->
                deviceOwner.setApplicationHidden(app.packageName, !visible)
                if (visible) AppWhitelist.addToWhitelist(this, app.packageName)
                else AppWhitelist.removeFromWhitelist(this, app.packageName)
                deviceOwner.setStatusBarDisabled(deviceOwner.isStatusBarDisabled()) // keep allow-list fresh
                runCatching { deviceOwner.applyAllPolicies() }
            })
        }

        // Security.
        content.addView(sectionHeader("Security"))
        content.addView(secondaryButton("Change admin PIN") { showChangePinDialog() })

        // Danger zone.
        content.addView(sectionHeader("Danger zone"))
        content.addView(dangerButton("Factory reset (WIPE all data)") {
            confirm("Factory reset?", "This ERASES ALL DATA and resets the device. Cannot be undone.") {
                deviceOwner.factoryReset()
            }
        })
        content.addView(dangerButton("Remove Device Owner") {
            confirm("Remove Device Owner?",
                "Lifts ALL restrictions and unseals the device. Lab/decommission only.") {
                deviceOwner.relinquishDeviceOwner()
                toast("Device Owner removed")
                finish()
            }
        })

        content.addView(secondaryButton("Close panel") { finish() })

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); addView(content) }
        setContentView(scroll)
    }

    private fun refresh() = showPanel()

    private fun showChangePinDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "New PIN (min 4 digits)"
        }
        AlertDialog.Builder(this)
            .setTitle("Change admin PIN")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString()
                if (pin.length >= 4) {
                    AdminPinStore.setPin(this, pin)
                    toast("PIN updated")
                } else toast("PIN must be at least 4 digits")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── View builders ──────────────────────────────────────────────────────────────

    private fun statusView(): View = TextView(this).apply {
        val owner = if (deviceOwner.isDeviceOwner()) "YES" else "NO"
        text = "Device Owner: $owner\nPackage: $packageName"
        setTextColor(if (deviceOwner.isDeviceOwner()) accent else danger)
        textSize = 13f
        setPadding(dp(4), dp(4), dp(4), dp(12))
    }

    private fun title(t: String) = TextView(this).apply {
        text = t
        setTextColor(textPrimary)
        textSize = 22f
        setPadding(dp(4), 0, 0, dp(8))
    }

    private fun sectionHeader(t: String) = TextView(this).apply {
        text = t.uppercase()
        setTextColor(accent)
        textSize = 13f
        letterSpacing = 0.08f
        setPadding(dp(4), dp(20), 0, dp(8))
    }

    private fun policySwitch(label: String, visible: Boolean, onChange: (Boolean) -> Unit) =
        SwitchCompat(this).apply {
            text = label
            isChecked = visible
            setTextColor(textPrimary)
            setBackgroundColor(card)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            layoutParams = rowParams()
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    private fun primaryButton(label: String, onClick: () -> Unit) =
        styledButton(label, accent, Color.parseColor("#0A0F1E"), onClick)

    private fun secondaryButton(label: String, onClick: () -> Unit) =
        styledButton(label, card, textPrimary, onClick)

    private fun dangerButton(label: String, onClick: () -> Unit) =
        styledButton(label, danger, Color.parseColor("#0A0F1E"), onClick)

    private fun styledButton(label: String, bgColor: Int, fg: Int, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(fg)
            setBackgroundColor(bgColor)
            layoutParams = rowParams()
            setOnClickListener { onClick() }
        }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Confirm") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

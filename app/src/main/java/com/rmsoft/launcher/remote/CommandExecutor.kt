package com.rmsoft.launcher.remote

import android.content.Context
import com.rmsoft.launcher.ui.MessageActivity
import com.rmsoft.launcher.utils.AppWhitelist
import com.rmsoft.launcher.utils.DeviceOwnerManager
import org.json.JSONObject

/**
 * Maps a [MdmApi.RemoteCommand] from the server onto the corresponding [DeviceOwnerManager] action.
 * Returns a short human-readable result string for the ack; throws nothing (errors are reported).
 */
class CommandExecutor(private val context: Context) {

    private val owner = DeviceOwnerManager(context)

    data class Result(val success: Boolean, val message: String)

    fun execute(cmd: MdmApi.RemoteCommand): Result {
        val p = cmd.payload
        return runCatching {
            when (cmd.type) {
                "LOCK_NOW" -> { owner.lockNow(); ok("locked") }
                "REBOOT" -> { owner.reboot(); ok("reboot requested") }
                "REAPPLY_POLICIES" -> { owner.applyAllPolicies(); ok("policies re-applied") }
                "EXIT_KIOSK" -> { KioskBridge.exitKiosk(); ok("kiosk exited") }
                "ENTER_KIOSK" -> { KioskBridge.enterKiosk(); ok("kiosk entered") }
                "SET_STATUS_BAR_DISABLED" -> {
                    owner.setStatusBarDisabled(p.bool("disabled"))
                    owner.refreshLockTaskFeatures()  // align Lock Task with the new status-bar state
                    KioskBridge.refreshSystemUi()    // re-apply immersive + shade overlay on the launcher
                    ok("status bar=${p.bool("disabled")}")
                }
                "SET_CAMERA_DISABLED" -> { owner.setCameraDisabled(p.bool("disabled")); ok("camera disabled=${p.bool("disabled")}") }
                "SET_KEYGUARD_DISABLED" -> { owner.setKeyguardDisabled(p.bool("disabled")); ok("keyguard disabled=${p.bool("disabled")}") }
                "SET_USER_RESTRICTION" -> {
                    owner.setUserRestriction(p.getString("key"), p.bool("enabled"))
                    ok("${p.getString("key")}=${p.bool("enabled")}")
                }
                "SET_APP_HIDDEN" -> {
                    val pkg = p.getString("packageName")
                    val hidden = p.bool("hidden")
                    owner.setApplicationHidden(pkg, hidden)
                    if (hidden) AppWhitelist.removeFromWhitelist(context, pkg)
                    else AppWhitelist.addToWhitelist(context, pkg)
                    ok("$pkg hidden=$hidden")
                }
                "ENABLE_SYSTEM_APP" -> { owner.enableSystemApp(p.getString("packageName")); ok("enabled ${p.getString("packageName")}") }
                "SET_WHITELIST" -> {
                    val arr = p.optJSONArray("packages")
                    val list = (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
                    AppWhitelist.setWhitelist(context, list)
                    owner.applyAllPolicies() // re-hide/show + refresh lock-task allow-list
                    ok("whitelist set (${list.size} apps)")
                }
                "INSTALL_APK" -> {
                    val url = p.optString("url")
                    if (url.isBlank()) Result(false, "missing url")
                    else Result(true, ApkInstaller.installFromUrl(context, url, RemoteConfig.serverUrl(context)))
                }
                "SHOW_MESSAGE" -> {
                    MessageActivity.show(context, p.optString("title"), p.optString("message"))
                    ok("message shown")
                }
                "SET_SERVER" -> {
                    val url = p.optString("url").trim()
                    if (!url.matches(Regex("^https?://.+"))) Result(false, "invalid url: $url")
                    else {
                        // Stage the switch; AgentService applies it after this tick's ack +
                        // telemetry reach the current (old) server. See RemoteConfig.setPendingServer.
                        RemoteConfig.setPendingServer(context, url, p.bool("reenroll"))
                        ok("server switch staged → $url")
                    }
                }
                "FACTORY_RESET" -> { owner.factoryReset(); ok("factory reset requested") }
                else -> Result(false, "unknown command: ${cmd.type}")
            }
        }.getOrElse { Result(false, "error: ${it.message}") }
    }

    private fun ok(msg: String) = Result(true, msg)

    private fun JSONObject.bool(key: String): Boolean = optBoolean(key, false)
}

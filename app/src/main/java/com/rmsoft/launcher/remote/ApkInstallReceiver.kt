package com.rmsoft.launcher.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.rmsoft.launcher.utils.AppWhitelist
import com.rmsoft.launcher.utils.DeviceOwnerManager

/**
 * Handles the install-session result broadcast from [ApkInstaller]. A dashboard-pushed install only
 * puts the APK on disk — for it to actually appear and stay, the freshly installed package must be:
 *  - **whitelisted**, or the next policy sweep ([DeviceOwnerManager.purgeNonWhitelistedUserApps])
 *    would uninstall it as a non-whitelisted user app, and the home grid would never show it;
 *  - **unhidden** + added to the **Lock Task allow-list**, so it can launch inside the kiosk;
 *  - reflected on the **home grid** immediately.
 *
 * Without this receiver, INSTALL_APK installed an app that was invisible and then silently purged.
 */
class ApkInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkInstaller.INSTALL_ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        if (status != PackageInstaller.STATUS_SUCCESS || pkg.isNullOrBlank()) {
            if (status != PackageInstaller.STATUS_SUCCESS) {
                Log.w(TAG, "install status=$status pkg=$pkg msg=${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
            return
        }
        if (pkg == context.packageName) return // our own self-update — nothing to whitelist

        Log.i(TAG, "installed $pkg — whitelisting, unhiding, refreshing grid")
        AppWhitelist.addToWhitelist(context, pkg)
        val owner = DeviceOwnerManager(context)
        owner.setApplicationHidden(pkg, false)
        owner.refreshLockTaskPackages()
        KioskBridge.refreshApps()
    }

    private companion object {
        const val TAG = "RMSOFTApkInstall"
    }
}

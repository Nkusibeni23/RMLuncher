# ─── RMSOFT Launcher — R8 / ProGuard rules ──────────────────────────────────
#
# AGP automatically keeps classes referenced from AndroidManifest.xml (the
# Activities and Receivers), and AndroidX/Material/coroutines ship their own
# consumer rules. The entries below make the kiosk-critical components explicit
# so reflection- and manifest-driven entry points are never stripped or renamed.

# Keep line numbers for readable crash reports; hide the original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Manifest-declared components (defensive — also covered by AGP's generated rules).
-keep class com.rmsoft.launcher.ui.LauncherActivity { *; }
-keep class com.rmsoft.launcher.ui.SettingsActivity { *; }
-keep class com.rmsoft.launcher.utils.BootReceiver { *; }

# Device admin: referenced by the system via the manifest <receiver> + device_admin.xml,
# and instantiated reflectively during Android Enterprise provisioning.
-keep class com.rmsoft.launcher.utils.RMSOFTAdminReceiver { *; }
-keep class com.rmsoft.launcher.utils.DeviceOwnerManager { *; }

# Kotlin metadata / coroutines are handled by their bundled consumer rules; nothing
# custom required here.

# RMSOFT Launcher

A custom enterprise Android launcher **+ MDM agent** for RMSOFT LTD security-facility devices.

A full **Android Enterprise Device Owner** app — it enforces all kiosk/security policies
natively via `DevicePolicyManager`, with no Headwind MDM or any third-party MDM. On top of the
sealed kiosk, a built-in device agent connects to `rmsoft-server` over **real-time MQTT** (with an
HTTP telemetry/poll fallback) for remote management and anti-theft: remote lock, wipe, reboot,
ring, SIM-change alerts, live app-whitelist push, and message-to-device.

> 📖 **Full documentation lives in [`docs/`](docs/)** — start with [`docs/README.md`](docs/README.md):
> - [Install runbook](docs/INSTALL.md) — the exact ADB steps to turn a phone into a sealed kiosk
> - [Verification](docs/VERIFICATION.md) — how to prove the lockdown is working
> - [Architecture](docs/ARCHITECTURE.md) — component map and policy flow
> - [Provisioning](provisioning/PROVISIONING.md) — production QR-code enrollment

## What it does

- Shows only approved apps in a 4-column grid
- Dark branded UI with RMSOFT name and live clock
- **Device Owner lockdown** — official Lock Task (kiosk) Mode plus OS-level policies:
  - Status bar / notification shade disabled
  - Factory reset, safe boot, USB file transfer, USB storage blocked
  - App install / uninstall, add-user, Bluetooth config blocked
  - Camera and lock screen disabled
- Hides every app not on the whitelist; can re-enable specific system apps
- **QR code provisioning** — factory reset → 6 taps → scan → fully set up as Device Owner
  (see [`provisioning/PROVISIONING.md`](provisioning/PROVISIONING.md))
- Device Owner removal is blocked and warned (`onDisableRequested`)
- Auto-launches on device boot and re-asserts all policies
- Whitelist-based app control — only apps you approve are visible

### Remote management & anti-theft (MDM agent)

A background agent (`AgentService`) enrolls with `rmsoft-server` and holds a live command channel:

- **Real-time MQTT push** (Eclipse Paho) so commands land instantly, with a 15s HTTP telemetry
  poll as fallback and for enrollment
- **Remote commands** executed against `DevicePolicyManager`: `LOCK` / `LOCK_NOW`, `UNLOCK`,
  `REBOOT`, `WIPE`, `FACTORY_RESET`, `RING`, `MESSAGE` / `SHOW_MESSAGE`, `ENTER_KIOSK` /
  `EXIT_KIOSK`, `SET_WHITELIST`, `SET_APP_HIDDEN`, `ENABLE_SYSTEM_APP`, `INSTALL_APK` /
  `UPDATE_APP`, `SET_STATUS_BAR_DISABLED`, `SET_CAMERA_DISABLED`, `SET_KEYGUARD_DISABLED`,
  `SET_USER_RESTRICTION`, `SET_LOCK_TASK_FEATURES`, `REAPPLY_POLICIES`, `SET_SERVER`
- **Anti-theft** — SIM-change detection (`SimChangeReceiver` / `SimGuard`) alerts the server on a
  swapped SIM; remote ring/alarm (`Ringer`), remote lock, and remote wipe for lost/stolen devices
- **Remote app delivery** — push/install/update APKs silently as Device Owner (`ApkInstaller`)
- **Encrypted enrollment** — device logs in for a JWT + device token, MQTT creds handed back at
  enrollment; secrets injected via the QR provisioning bundle, never hardcoded

## Project structure

```
app/src/main/
├── AndroidManifest.xml              # Launcher declared as HOME intent
├── java/com/rmsoft/launcher/
│   ├── ui/
│   │   ├── LauncherActivity.kt      # Main home screen activity
│   │   └── AppGridAdapter.kt        # RecyclerView adapter for app grid
│   ├── model/
│   │   └── AppItem.kt               # App data model
│   ├── remote/                      # MDM device agent (remote mgmt + anti-theft)
│   │   ├── AgentService.kt          # Foreground agent — enroll, telemetry, command loop
│   │   ├── MqttManager.kt           # Real-time command push (Eclipse Paho)
│   │   ├── MdmApi.kt                # HTTP client to rmsoft-server (enroll/poll/ack)
│   │   ├── CommandExecutor.kt       # Maps server commands → DeviceOwnerManager actions
│   │   ├── RemoteConfig.kt          # Server URL, tokens, MQTT creds (SharedPreferences)
│   │   ├── ApkInstaller.kt          # Silent Device-Owner APK install/update
│   │   ├── Ringer.kt                # Remote ring/alarm for anti-theft
│   │   ├── SimChangeReceiver.kt     # SIM-swap detection → server alert
│   │   └── Crypto.kt                # Secret handling for enrollment
│   └── utils/
│       ├── AppWhitelist.kt          # ← Edit this to control which apps appear
│       ├── DeviceOwnerManager.kt    # All DevicePolicyManager lockdown logic
│       ├── RMSOFTAdminReceiver.kt   # DeviceAdminReceiver — provisioning + admin callbacks
│       ├── SimGuard.kt              # SIM-change policy / lock response
│       ├── StatusBarBlocker.kt      # Overlay shade-blocker (non-DO fallback)
│       └── BootReceiver.kt          # Re-assert policies + auto-launch after reboot
└── res/
    ├── xml/
    │   └── device_admin.xml         # Declared admin policies
    ├── layout/
    │   ├── activity_launcher.xml    # Main screen layout
    │   └── item_app.xml             # Individual app icon + label
    └── values/
        ├── strings.xml
        └── themes.xml
```

## How to add or remove apps

Open `AppWhitelist.kt` and add/remove package names:

```kotlin
fun getWhitelistedPackages(): List<String> = listOf(
    "com.rmsoft.yourapp",      // Your main app
    "com.android.dialer",      // Phone
    "com.android.mms",         // SMS
    // Add more here
)
```

To find an app's package name:
```bash
adb shell pm list packages | grep <appname>
```

## How to build and install

### Debug build (for testing on K671 sample)
```bash
# In Android Studio — click Run or:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk

# Set as default home screen when prompted on device
```

### Set as default launcher via ADB
```bash
adb shell cmd package set-home-activity com.rmsoft.launcher/.ui.LauncherActivity
```

### Install as system app (for production — requires root)
```bash
adb root
adb remount
adb push app-release.apk /system/priv-app/RMSOFTLauncher/RMSOFTLauncher.apk
adb shell chmod 644 /system/priv-app/RMSOFTLauncher/RMSOFTLauncher.apk
adb reboot
```

## Kiosk mode behavior

- **Back button** — blocked, does nothing
- **Home button** — returns to RMSOFT launcher
- **Recents button** — blocked
- **Volume buttons** — blocked (remove from onKeyDown to allow)
- **Power button** — allowed (sleep/wake)
- **Boot** — auto-launches RMSOFT launcher via BootReceiver

## Customization

- **Background color** — change `#0A0F1E` in `activity_launcher.xml`
- **Brand name** — change `RMSOFT` in `activity_launcher.xml`
- **Grid columns** — change `4` in `LauncherActivity.kt` GridLayoutManager
- **Allow volume buttons** — remove KEYCODE_VOLUME_UP/DOWN from onKeyDown

## Becoming Device Owner

Kiosk policies (status bar off, factory-reset block, app hiding, etc.) only take effect once the
app is the **Device Owner**. Two paths, both documented in
[`provisioning/PROVISIONING.md`](provisioning/PROVISIONING.md):

- **Production:** Android Enterprise QR provisioning (factory reset → 6 taps → scan QR).
- **Lab/sample (K671):** `adb shell dpm set-device-owner com.rmsoft.launcher/.utils.RMSOFTAdminReceiver`
  on a device with no accounts added.

Until then the app still runs as an ordinary home launcher (immersive mode + overlay
shade-blocker), with the Device Owner policies as safe no-ops.

## Next steps

1. Host the release APK on an HTTPS URL and fill in `provisioning/rmsoft_provisioning.json`
2. Generate the provisioning QR code (see PROVISIONING.md)
3. Test QR provisioning end-to-end on the K671 sample
4. Tune the app whitelist for the facility
5. Roll out to the fleet

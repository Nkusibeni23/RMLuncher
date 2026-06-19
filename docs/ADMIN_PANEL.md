# Admin Control Panel

An on-device, PIN-gated control panel that exposes **every Device Owner capability** locally. It
drives `DeviceOwnerManager` — the same policy chokepoint a future remote dashboard will use — so
the control logic is shared between local and (later) remote management.

## How to open it

1. On the launcher home screen, **long-press the RMSOFT brand title** (`RMOS` at the top).
2. Enter the admin PIN.
3. The control panel opens.

**Default PIN: `246813`** (`AdminPinStore.DEFAULT_PIN`). Change it from the panel
(Security → Change admin PIN) on first use, and change the default in code before production.

## What it controls

| Section | Controls |
| --- | --- |
| **Status** | Device Owner yes/no, package name. |
| **Kiosk** | Exit kiosk (stop Lock Task — for servicing), re-enter kiosk, re-apply ALL policies, lock device now, reboot device. |
| **Policies** | Toggle each policy individually: status bar / notification shade, camera, keyguard, and all 8 user restrictions (factory reset, safe boot, USB file transfer, install apps, uninstall apps, Bluetooth config, add user, USB storage). |
| **Apps** | A switch per installed app — ON = visible on the kiosk (un-hidden + added to whitelist), OFF = hidden (+ removed from whitelist). Plus "Reset whitelist to defaults". |
| **Security** | Change admin PIN. |
| **Danger zone** | Factory reset (wipe all data), Remove Device Owner. Both behind a confirm dialog. |

Each toggle calls straight through to `DeviceOwnerManager` and takes effect immediately. App
changes also re-run `applyAllPolicies()` so the Lock Task allow-list stays in sync.

## Code map

| File | Role |
| --- | --- |
| `ui/AdminActivity.kt` | The panel UI (built programmatically) and all action wiring. |
| `utils/AdminPinStore.kt` | SHA-256 PIN storage in SharedPreferences. |
| `utils/DeviceOwnerManager.kt` | Every control the panel calls (toggles, actions, app management). |
| `utils/AppWhitelist.kt` | Now persisted in SharedPreferences so panel edits survive reboots. |

---

## ⚠️ Updating the app once the kiosk is locked

This is the important operational gotcha. A sealed kiosk sets `DISALLOW_INSTALL_APPS`, which
**blocks `adb install` — including in-place updates of this app**. And because a production
(non-debuggable) Device Owner **cannot** be removed via `adb remove-active-admin`, you cannot
simply un-provision to update.

### The supported update workflow (once the panel is installed)

1. Open the admin panel → **Policies** → turn **"Block app installation" OFF**.
2. `adb install -r app/build/outputs/apk/release/app-release.apk` (now permitted).
3. Open the panel again → turn **"Block app installation" ON** (or tap **Re-apply ALL policies**).

The app stays Device Owner the whole time; only the restriction is briefly lifted.

### Bootstrapping the panel onto an already-locked device

If a device was locked down with a build that predates the panel, there is no software path in —
the lockdown blocks the install and the DO can't be removed. You must:

- **Recovery wipe:** `adb reboot recovery` → on the device, navigate (volume/power) to
  **Wipe data / factory reset** → reboot → re-provision (ADB or QR) with the new APK. Destructive.

### Recommended for lab / dev devices

Provision the **debug** build (`com.rmsoft.launcher.debug`, `debuggable=true`). A debuggable
admin **can** be removed with
`adb shell dpm remove-active-admin com.rmsoft.launcher.debug/.utils.RMSOFTAdminReceiver`, so you
can iterate freely. Use the release build only for production devices, and make sure the release
APK already contains the admin panel before you lock anything down.

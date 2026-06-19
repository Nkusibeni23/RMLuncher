# Install Runbook (Lab / ADB)

This is the **exact procedure that was run** to turn the sample phone into a sealed RMSOFT kiosk,
with the real commands and the output to expect. Use this for any lab/sample device. For
production fleet devices use QR provisioning instead — see
[../provisioning/PROVISIONING.md](../provisioning/PROVISIONING.md).

> Reference device used: **UMIDIGI YU3KK1**, Android **15** (API 35).

---

## Prerequisites

- macOS with `adb` installed (`brew install android-platform-tools`).
- The target phone has **no Google/email account** added (Device Owner can only be claimed on a
  device with no accounts — fresh or factory-reset, account setup skipped).
- USB debugging enabled: Settings → About phone → tap **Build number** 7× → Developer options →
  **USB debugging**. Accept the RSA prompt when you plug in.

## Why the package name matters

Debug builds install as `com.rmsoft.launcher.debug` (the `applicationIdSuffix` in
`app/build.gradle`); release builds install as `com.rmsoft.launcher`. **We use the release build**
so the package matches the QR provisioning payload and all docs. If you ever use a debug build,
append `.debug` to the package in every command below.

---

## Step 1 — Build the release APK

```bash
cd /Users/amiel/Development/RMLauncher
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

> The release APK is currently signed with the **debug key** (`signingConfig signingConfigs.debug`
> in `app/build.gradle`). Fine for lab. Before fleet rollout, set a real release keystore — the QR
> payload pins the signing certificate's SHA-256 checksum.

## Step 2 — Confirm the device is connected and eligible

```bash
adb devices -l                                   # device should be listed
adb shell getprop ro.build.version.release       # Android version
adb shell dpm list-owners                         # expect: "No owners." (none yet)
adb shell dumpsys account | grep "Account {"      # expect: no output (no accounts)
adb shell pm list users                           # expect: a single Owner user (0)
```

If a Device Owner or accounts already exist, `set-device-owner` in Step 4 will fail — factory
reset and skip account setup, then retry.

## Step 3 — Install the APK

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
# Expect: "Success"
```

Installing alone changes nothing about the lockdown — the policies only take effect once the app
is Device Owner (next step).

## Step 4 — Claim Device Owner (this activates the lockdown)

```bash
adb shell dpm set-device-owner com.rmsoft.launcher/.utils.RMSOFTAdminReceiver
```

Expected output:

```
Success: Device owner set to package com.rmsoft.launcher/.utils.RMSOFTAdminReceiver
Active admin set to component com.rmsoft.launcher/.utils.RMSOFTAdminReceiver
```

The moment this succeeds, `RMSOFTAdminReceiver.onEnabled()` fires and runs
`DeviceOwnerManager.applyAllPolicies()`: status bar disabled, factory reset / safe boot / USB /
install-uninstall / add-user / Bluetooth-config blocked, camera + keyguard disabled, all
non-whitelisted apps hidden, and RMSOFT pinned as the default Home app.

## Step 5 — Reboot into the kiosk

```bash
adb reboot
adb wait-for-device
# wait for boot to finish:
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 3; done
```

The phone boots straight into the RMSOFT launcher in Lock Task Mode. Proceed to
[VERIFICATION.md](./VERIFICATION.md) to confirm every policy is active.

---

## Updating the app later (while it stays Device Owner)

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk   # in-place update, stays Device Owner
```

Policy/whitelist changes require editing `AppWhitelist.kt` (or `DeviceOwnerManager`), rebuilding,
and reinstalling — there is no remote channel yet.

## Removing Device Owner (lab teardown only)

Production blocks removal (`onDisableRequested`). For the lab sample:

```bash
adb shell dpm remove-active-admin com.rmsoft.launcher/.utils.RMSOFTAdminReceiver
```

This lifts **all** restrictions, including `DISALLOW_FACTORY_RESET`, so the device can then be
reset or repurposed. Note: because the kiosk sets `DISALLOW_FACTORY_RESET`, you **cannot** reset
from the phone UI while it's locked — always use this command first.

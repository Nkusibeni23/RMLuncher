#!/usr/bin/env bash
#
# USB provisioning for the RMSOFT launcher — the Play-Protect-proof path.
#
# adb `dpm set-device-owner` bypasses the Google Play Protect scan that blocks QR provisioning of a
# self-signed DPC, so this always works in the lab / for staged devices.
#
# ON THE PHONE FIRST:
#   1. Factory reset (Settings -> System -> Reset -> Erase all data).
#   2. Through the setup wizard: connect Wi-Fi, but SKIP every account / restore prompt.
#      The device MUST have ZERO accounts or set-device-owner fails.
#   3. Settings -> About phone -> tap "Build number" 7x to unlock Developer options.
#   4. Settings -> System -> Developer options -> enable "USB debugging".
#   5. Plug in USB and tap "Allow" on the debugging prompt.
#
# Then run:  ./provision-usb.sh
set -euo pipefail

ADMIN="com.rmsoft.launcher/com.rmsoft.launcher.utils.RMSOFTAdminReceiver"
APK="$(dirname "$0")/app/build/outputs/apk/release/app-release.apk"

[ -f "$APK" ] || { echo "APK not found — run ./gradlew assembleRelease first."; exit 1; }

echo "Waiting for device (authorize the USB-debugging prompt if shown)…"
adb wait-for-device

# Don't let Play Protect's "verify apps over USB" interfere with the install.
adb shell settings put global verifier_verify_adb_installs 0 || true

echo "Installing $(basename "$APK")…"
adb install -r "$APK"

echo "Setting Device Owner…"
adb shell dpm set-device-owner "$ADMIN"

echo "✓ Done — the device should drop into the RMSOFT kiosk and enroll within ~15s."

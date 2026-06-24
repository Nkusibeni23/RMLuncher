#!/usr/bin/env bash
#
# RMSOFT launcher — download-from-server provisioning over USB.
#
# Pulls the CURRENT enrollment APK from the server, inspects the connected phone, installs/updates
# the launcher, makes it Device Owner, and grants every permission it needs. Re-runnable: on a
# device that's already provisioned it just updates the APK and re-grants permissions.
#
# adb `dpm set-device-owner` bypasses the Google Play Protect scan that blocks QR provisioning of a
# self-signed DPC, so this is the reliable path for staging devices.
#
# Usage:
#   ./provision-url.sh                 # uses the default server URL below
#   ./provision-url.sh <APK_URL>       # or pass an explicit URL
#   APK_URL=https://… ./provision-url.sh
#
# ON THE PHONE FIRST (only needed for a fresh Device-Owner setup):
#   1. Factory reset; through setup connect Wi-Fi but SKIP every account (DO needs ZERO accounts).
#   2. Settings → About phone → tap "Build number" 7× → Developer options → enable "USB debugging".
#   3. Plug in USB and tap "Allow".
set -euo pipefail

APK_URL="${APK_URL:-${1:-https://mdm.tugane.com/api/launcher.apk}}"
PKG="com.rmsoft.launcher"
ADMIN="$PKG/com.rmsoft.launcher.utils.RMSOFTAdminReceiver"

# Runtime permissions the agent needs (location telemetry + notifications). The Device Owner also
# self-grants these via setPermissionGrantState, but we grant over adb too so they're set instantly.
RUNTIME_PERMS=(
  android.permission.ACCESS_FINE_LOCATION
  android.permission.ACCESS_COARSE_LOCATION
  android.permission.POST_NOTIFICATIONS
)

say()  { printf '\033[1;36m▸ %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

command -v adb  >/dev/null || die "adb not found on PATH."
command -v curl >/dev/null || die "curl not found on PATH."

say "Waiting for device (authorize the USB-debugging prompt if it appears)…"
adb wait-for-device

# ─── 1. Inspect what's already on the phone ──────────────────────────────────────────────────────
say "Inspecting device…"
MODEL=$(adb shell getprop ro.product.model | tr -d '\r')
ANDROID=$(adb shell getprop ro.build.version.release | tr -d '\r')
SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "    Device:  $MODEL  (Android $ANDROID, API $SDK)"

ACCOUNTS=$(adb shell dumpsys account 2>/dev/null | grep -c "Account {" || true)
echo "    Accounts on device: $ACCOUNTS"

OWNERS=$(adb shell dpm list-owners 2>/dev/null | tr -d '\r')
IS_OWNER=no
echo "$OWNERS" | grep -q "$PKG" && IS_OWNER=yes
echo "    Device Owner set for us: $IS_OWNER"

INSTALLED_VER=$(adb shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | sed 's/.*versionName=//' | tr -d '\r' || true)
if [ -n "$INSTALLED_VER" ]; then echo "    Launcher installed: v$INSTALLED_VER"; else echo "    Launcher installed: no"; fi

# ─── 2. Download the current APK from the server ─────────────────────────────────────────────────
TMP=$(mktemp -t rmsoft-launcher.XXXXXX).apk
trap 'rm -f "$TMP"' EXIT
say "Downloading APK from $APK_URL"
curl -fSL --retry 3 --retry-delay 1 -o "$TMP" "$APK_URL" || die "Download failed."
SIZE=$(wc -c < "$TMP" | tr -d ' ')
# Verify it's a real APK (ZIP magic 'PK') and a sane size, so we never install a truncated/HTML body.
head -c2 "$TMP" | grep -q "PK" || die "Downloaded file is not an APK (no ZIP magic) — check the URL."
[ "$SIZE" -gt 1000000 ] || die "Downloaded file is only ${SIZE} bytes — looks truncated."
ok "Downloaded ${SIZE} bytes."

# ─── 3. Install / update ─────────────────────────────────────────────────────────────────────────
say "Disabling 'verify apps over USB' so Play Protect doesn't block the install…"
adb shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1 || true

say "Installing launcher (-r update, -g grant runtime permissions)…"
adb install -r -g "$TMP" || die "adb install failed."
ok "Installed."

# ─── 4. Device Owner ─────────────────────────────────────────────────────────────────────────────
if [ "$IS_OWNER" = yes ]; then
  ok "Already Device Owner — skipping set-device-owner."
elif [ "$ACCOUNTS" -gt 0 ]; then
  warn "Device has $ACCOUNTS account(s); set-device-owner WILL fail."
  warn "Factory reset and skip all accounts, then re-run. (APK is installed but NOT Device Owner.)"
else
  say "Setting Device Owner…"
  adb shell dpm set-device-owner "$ADMIN" || die "set-device-owner failed (account present, or already provisioned)."
  ok "Device Owner set."
fi

# ─── 5. Grant every permission ───────────────────────────────────────────────────────────────────
say "Granting runtime permissions…"
for p in "${RUNTIME_PERMS[@]}"; do
  if adb shell pm grant "$PKG" "$p" >/dev/null 2>&1; then echo "    granted $p"; else echo "    skip   $p (n/a on API $SDK)"; fi
done
# Special access: modify system settings (screen brightness from the custom Settings screen).
adb shell appops set "$PKG" WRITE_SETTINGS allow >/dev/null 2>&1 || true
echo "    appop  WRITE_SETTINGS allow"

# ─── 6. Verify + launch ──────────────────────────────────────────────────────────────────────────
say "Verifying…"
adb shell dpm list-owners | tr -d '\r' | grep -q "$PKG" && ok "Device Owner confirmed." || warn "Device Owner NOT set (see above)."
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

echo
ok "Done — the device should be in the RMSOFT kiosk and will enroll against the server within ~15s."

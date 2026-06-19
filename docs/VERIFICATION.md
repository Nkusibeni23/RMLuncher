# Verification — Proving the Lockdown Works

Every check below was run on the sample device after install. Each lists the command, what a
**pass** looks like, and what it proves. Run these after any install or policy change.

> All checks were confirmed PASSING on the UMIDIGI YU3KK1 (Android 15) sample.

---

## 1. Device Owner is set

```bash
adb shell dpm list-owners
```

**Pass:** `User 0: admin=com.rmsoft.launcher/.utils.RMSOFTAdminReceiver,DeviceOwner,Affiliated`

Proves the app holds full device-management authority.

## 2. Boots into the launcher

```bash
adb shell dumpsys activity activities | grep topResumedActivity
```

**Pass:** `topResumedActivity=ActivityRecord{… com.rmsoft.launcher/.ui.LauncherActivity …}`

Proves RMSOFT is the foreground app.

## 3. Lock Task (kiosk) Mode is active

```bash
adb shell dumpsys activity | grep mLockTaskModeState
```

**Pass:** `mLockTaskModeState=LOCKED`

Proves the OS-level kiosk is engaged — Home, Recents, notifications, and navigation out of the
app are blocked by Android itself, not just by app code. The lock-task feature flags are
`LOCK_TASK_FEATURE_NONE` (everything optional turned off).

## 4. RMSOFT is the default Home app

```bash
adb shell cmd shortcut get-default-launcher
```

**Pass:** `Launcher: ComponentInfo{com.rmsoft.launcher/com.rmsoft.launcher.ui.LauncherActivity}`

Proves the device always returns to the kiosk, with no launcher chooser.

## 5. Notification shade is blocked

```bash
adb shell cmd statusbar expand-notifications
```

**Pass:** no shade appears (command is a no-op) because `setStatusBarDisabled(true)` is in effect.

## 6. Non-whitelisted apps are hidden (whitelisted ones are not)

```bash
# Whitelisted — expect hidden=false:
adb shell dumpsys package com.android.chrome | grep -E "hidden=|suspended="
# Not whitelisted — expect hidden=true:
adb shell dumpsys package com.google.android.apps.youtube.music | grep -E "hidden="
```

**Pass:** Chrome → `hidden=false`; YouTube Music → `hidden=true`.

> Note: `dumpsys device_policy | grep applicationHidden` lists every package the policy *touched*
> — both the ones set visible (`false`) and hidden (`true`). It does **not** show the boolean, so
> it cannot be used to confirm hidden state. Always check the per-package `hidden=` flag as above.

## 7. User restrictions are enforced

```bash
adb shell dumpsys user | grep -iA4 "Device policy"
```

**Pass:** includes `no_install_apps`, `no_uninstall_apps`, `no_config_bluetooth`, `no_add_user`,
`no_usb_file_transfer`, `no_factory_reset`, `no_safe_boot`, `no_physical_media`.

Proves every escape hatch is walled off.

## 8. Camera is disabled

```bash
adb shell dumpsys device_policy | grep -i camera
```

**Pass:** `userRestriction_no_camera` present and the device's camera app (e.g.
`com.mediatek.camera`) shows `hidden=true`. Camera is off via both `setCameraDisabled(true)` and
the `no_camera` restriction.

## 9. Survives reboot

After `adb reboot` and boot completion, re-run checks 2 and 3 — both still pass. `BootReceiver`
re-asserts `applyAllPolicies()` and `LauncherActivity.onCreate` re-enters Lock Task.

## 10. Visual confirmation

```bash
adb exec-out screencap -p > /tmp/rmsoft_kiosk.png
```

**Pass:** the RMSOFT/RMOS launcher with the live clock and only whitelisted app tiles
(Chrome, Files, Settings).

---

## Known cosmetic note

A system **Back (◁) button** remains in the nav bar — Lock Task Mode strips Home and Recents but
Android always keeps Back. It is neutralized in `LauncherActivity` via
`onBackPressedDispatcher` (does nothing). Not a security gap. To remove it visually, switch the
device to gesture navigation.

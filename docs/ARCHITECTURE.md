# Architecture — Device Owner Layer

How the Android Enterprise Device Owner functionality is structured, and where each policy is
applied. The design principle: **one policy chokepoint** (`DeviceOwnerManager`) that both the
provisioning path and the runtime path call, so behaviour is identical no matter how the kiosk
starts.

---

## Components

| File | Role |
| --- | --- |
| `utils/DeviceOwnerManager.kt` | **Single source of truth for all `DevicePolicyManager` policy.** Applies user restrictions, status-bar disable, camera/keyguard disable, lock-task allow-list + features, default-Home pinning, and app hide/show. Safe no-op when not Device Owner. |
| `utils/RMSOFTAdminReceiver.kt` | `DeviceAdminReceiver` — the component set as Device Owner. Lifecycle callbacks bootstrap and protect the kiosk. |
| `ui/LauncherActivity.kt` | The kiosk home screen. Re-asserts policies on `onCreate`/`onResume` and calls `startLockTask()` (the one policy call that must come from an Activity). |
| `utils/BootReceiver.kt` | On `BOOT_COMPLETED`, re-asserts all policies and launches the kiosk. |
| `utils/AppWhitelist.kt` | The list of packages allowed to be visible. **Edit this to control the app grid.** |
| `utils/StatusBarBlocker.kt` | Overlay-based shade blocker — fallback for the non-Device-Owner case; redundant with `setStatusBarDisabled` once provisioned. |
| `res/xml/device_admin.xml` | Declares the admin policies used. |
| `AndroidManifest.xml` | Declares the receiver with `BIND_DEVICE_ADMIN`, the `DEVICE_ADMIN_ENABLED` + `PROFILE_PROVISIONING_COMPLETE` intent filters, and the `device_admin` meta-data. |

## Two entry points, one policy set

```
                         ┌─────────────────────────────────────┐
   QR / EMM provisioning │ RMSOFTAdminReceiver                  │
   (factory reset → QR)  │   onProfileProvisioningComplete() ───┼──┐
                         │   onEnabled()  ──────────────────────┼──┤
   adb set-device-owner  │                                      │  │
                         └─────────────────────────────────────┘  │
                                                                   ▼
   every boot            BootReceiver.onReceive() ───────────►  DeviceOwnerManager
                                                                .applyAllPolicies()
   every launch          LauncherActivity.onCreate()/onResume ─►  (+ startLockTask in Activity)
```

`applyAllPolicies()` is **idempotent** — safe to call repeatedly; each call re-asserts the same
state. This is why it runs on every boot and every launch: anything changed out from under the
kiosk is corrected immediately.

## What `applyAllPolicies()` does

1. `setStatusBarDisabled(true)` — kills the notification shade / quick settings.
2. `setLockTaskPackages(...)` — allow-list = launcher + `com.android.settings` + whitelist.
3. `setLockTaskFeatures(LOCK_TASK_FEATURE_NONE)` (API 28+) — no Home/Overview/notifications/etc.
4. `setCameraDisabled(true)` and `setKeyguardDisabled(true)`.
5. Adds user restrictions: factory reset, safe boot, USB file transfer, install apps, uninstall
   apps, config Bluetooth, add user, mount physical media.
6. `setAsDefaultHome()` — `addPersistentPreferredActivity` for the HOME intent.
7. `hideNonWhitelistedApps()` — hides every launchable app except whitelist + essential system
   packages + this launcher; explicitly un-hides whitelisted apps.

Lock Task Mode is *configured* here but *entered* by `LauncherActivity.startLockTask()` (Android
requires that call from an Activity).

## App management API (on `DeviceOwnerManager`)

| Method | Use |
| --- | --- |
| `setApplicationHidden(pkg, hidden)` | Hide/show any app without uninstalling it. |
| `enableSystemApp(pkg)` | Re-enable a system app that ships disabled. |
| `hideNonWhitelistedApps()` | Bulk-hide everything off the whitelist (run on boot/launch). |

These are the natural hook points for the future remote-control layer: a server-driven config
would call into exactly these methods plus the whitelist.

## Admin-removal protection

`RMSOFTAdminReceiver.onDisableRequested()` returns an audited warning string and toasts it; in
production the Device Owner is not removable through the normal flow. `onDisabled()` logs an error
(should never fire in production).

## Provisioning

The manifest's `PROFILE_PROVISIONING_COMPLETE` filter lets `onProfileProvisioningComplete()` fire
at the end of QR/EMM provisioning, which applies all policies, pins Home, and launches the kiosk
with no further interaction. The QR payload template and full flow are in
[../provisioning/PROVISIONING.md](../provisioning/PROVISIONING.md).

## Compatibility

`minSdk 26` / `targetSdk 35`. All user restrictions and policy calls used are available at API 26;
`setLockTaskFeatures` (API 28) is guarded with a version check.

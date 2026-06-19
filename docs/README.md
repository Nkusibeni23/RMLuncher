# RMSOFT Launcher — Documentation

Reference docs for the RMSOFT Android Enterprise **Device Owner** launcher. These describe how
the kiosk is built, installed, and verified so the process is repeatable by anyone on the team.

| Doc | What it covers |
| --- | --- |
| [INSTALL.md](./INSTALL.md) | Exact lab install runbook — the verified ADB procedure that turns a phone into a sealed RMSOFT kiosk (the steps actually run on the UMIDIGI sample). |
| [VERIFICATION.md](./VERIFICATION.md) | How to prove the lockdown is working — every check, the command, and what a pass looks like. |
| [ADMIN_PANEL.md](./ADMIN_PANEL.md) | The on-device PIN-gated admin control panel — how to open it, what it controls, and the critical app-update workflow. |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Component map and policy flow — which file does what, and where each Android Enterprise policy is applied. |
| [../provisioning/PROVISIONING.md](../provisioning/PROVISIONING.md) | Production QR-code provisioning (factory reset → 6 taps → scan) for fleet rollout. |

## Quick orientation

- **Device-side enforcement is done and verified.** The app is a self-contained Device Owner —
  no Headwind MDM or any third-party MDM. All policy enforcement is native `DevicePolicyManager`.
- **Two ways to become Device Owner:** ADB (lab, see INSTALL.md) and QR (production, see
  PROVISIONING.md). Both target the same admin component
  `com.rmsoft.launcher/.utils.RMSOFTAdminReceiver`.
- **Policy single source of truth:** `DeviceOwnerManager` (see ARCHITECTURE.md). Change the
  visible apps in `app/src/main/java/com/rmsoft/launcher/utils/AppWhitelist.kt`.

## Control surface

- **On-device admin panel** ([ADMIN_PANEL.md](./ADMIN_PANEL.md)) — built. Long-press the brand
  title, enter the PIN, and control every Device Owner capability locally (kiosk enter/exit,
  per-policy toggles, app show/hide + whitelist, lock/reboot, factory reset).

## Not yet built (next phase)

A **remote control + monitoring** layer (device agent + backend + dashboard) so policies can be
managed over the network. The admin panel already routes everything through `DeviceOwnerManager`,
so the remote layer calls the same methods — no rework of the enforcement core.

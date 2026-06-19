# RMSOFT Platform — Project Documentation

Self-hosted Android Enterprise device management for RMSOFT LTD security-facility phones. Every
component is built and operated in-house — **no Headwind MDM, no third-party MDM**. The platform
turns ordinary Android phones into sealed, remotely managed kiosks.

> This is the whole-project reference. For runnable steps see each subproject's README
> (`RMLauncher/README.md`, `rmsoft-mdm/README.md`) and `RMLauncher/docs/`.

---

## 1. Components

| Folder | What it is | Stack |
| --- | --- | --- |
| [`RMLauncher/`](./RMLauncher) | On-device **Device Owner launcher** — the sealed kiosk + embedded device agent | Kotlin, Android (Gradle), `minSdk 26` / `target 35` |
| [`rmsoft-mdm/server/`](./rmsoft-mdm/server) | Management **API server** | Fastify 5 + TypeScript, `node:sqlite` (Node 22+) |
| [`rmsoft-mdm/dashboard/`](./rmsoft-mdm/dashboard) | Admin **web dashboard** | React 18 + Vite 6 + TypeScript |

App identity: `com.rmsoft.launcher` (debug suffix `.debug`), version `1.0.0`.

---

## 2. Architecture

The phone is sealed and **cannot accept inbound connections**, so the device agent *polls* the
server (~every 15s), executes any pending commands locally via `DevicePolicyManager`, and posts
telemetry. The dashboard never talks to the phone directly — it enqueues commands on the server,
which the phone picks up on its next poll.

```
   ┌─────────────────────┐        HTTPS polling         ┌──────────────────────┐
   │  Phone (RMLauncher)  │  ──── enroll / poll ───────▶ │   rmsoft-mdm server   │
   │   device agent       │  ◀─── commands ──────────────│   (Fastify + SQLite)  │
   │   DeviceOwnerManager │  ──── telemetry / ack ──────▶ │                       │
   └─────────────────────┘                               └───────────┬──────────┘
                                                                      │ REST + JWT
                                                          ┌───────────▼──────────┐
                                                          │  Web dashboard (React)│
                                                          │  admin login + control│
                                                          └───────────────────────┘
```

**Command lifecycle:** `pending` → (device polls, marked) `sent` → (device executes + acks)
`acked` / `failed`. A device is considered **offline** if it hasn't polled within
`ONLINE_WINDOW_MS` (60s).

---

## 3. RMLauncher (on-device)

A full Android Enterprise **Device Owner** app declared as the HOME launcher. It enforces all
kiosk/security policies natively — the Device Owner policies are safe no-ops until the app actually
becomes Device Owner.

### Key source (`app/src/main/java/com/rmsoft/launcher/`)

| File | Role |
| --- | --- |
| `ui/LauncherActivity.kt` | Home screen — 4-column app grid, clock, branding |
| `ui/AppGridAdapter.kt` | RecyclerView adapter for the app grid |
| `ui/AdminActivity.kt` / `SettingsActivity.kt` | PIN-gated admin/settings screens |
| `utils/AppWhitelist.kt` | **Edit to control which apps appear** |
| `utils/DeviceOwnerManager.kt` | All `DevicePolicyManager` lockdown logic |
| `utils/RMSOFTAdminReceiver.kt` | `DeviceAdminReceiver` — provisioning + admin callbacks |
| `utils/BootReceiver.kt` | Re-assert policies + auto-launch after reboot |
| `utils/StatusBarBlocker.kt` | Overlay shade-blocker (non-DO fallback) |
| `utils/AdminPinStore.kt` | Stores the admin PIN |
| `remote/AgentService.kt` | Background poller (enroll → poll → execute → telemetry → apply staged server switch) |
| `remote/MdmApi.kt` | HTTP client for the server API |
| `remote/CommandExecutor.kt` | Maps server command types → `DeviceOwnerManager` calls |
| `remote/KioskBridge.kt` | Enter/exit Lock Task (kiosk) mode |
| `remote/RemoteConfig.kt` | Server URL, enrollment secret, device token, staged server switch (SharedPreferences) |

### Lockdown enforced (when Device Owner)
- Official **Lock Task / kiosk** mode
- Status bar & notification shade disabled
- Factory reset, safe boot, USB file transfer / storage blocked
- App install / uninstall, add-user, Bluetooth config blocked
- Camera and lock screen (keyguard) disabled
- Whitelist-only app grid; specific system apps can be re-enabled
- Device Owner removal blocked & warned (`onDisableRequested`)
- Auto-launch on boot, re-asserting all policies

### Becoming Device Owner
- **Production:** Android Enterprise QR provisioning (factory reset → 6 taps → scan QR). See
  [`provisioning/PROVISIONING.md`](./RMLauncher/provisioning/PROVISIONING.md).
- **Lab/sample (K671):** `adb shell dpm set-device-owner com.rmsoft.launcher/.utils.RMSOFTAdminReceiver`
  on a device with no accounts added.

---

## 4. rmsoft-mdm server

Fastify + TypeScript API over SQLite (Node's built-in `node:sqlite` — no native build). All SQL is
isolated in `repo.ts`, so swapping to Postgres for a large fleet touches only that file.

### Source (`server/src/`)
`index.ts` (bootstrap) · `config.ts` (env) · `db.ts` (schema: `devices`, `commands`, `telemetry`) ·
`repo.ts` (all SQL) · `auth.ts` (JWT admin + device-token guards) · `contracts.ts` (shared wire
types) · `routes/adminApi.ts` · `routes/deviceApi.ts`.

### API surface

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/api/admin/login` | — | Dashboard login → JWT |
| GET | `/api/admin/devices` | admin JWT | List fleet |
| GET | `/api/admin/devices/:id` | admin JWT | Device + commands + telemetry |
| POST | `/api/admin/devices/:id/commands` | admin JWT | Enqueue a command |
| POST | `/api/admin/devices/:id/group` | admin JWT | Assign / clear a device's group |
| GET | `/api/admin/groups` | admin JWT | List groups |
| POST | `/api/admin/groups` | admin JWT | Create a group |
| DELETE | `/api/admin/groups/:id` | admin JWT | Delete a group |
| POST | `/api/admin/groups/:id/commands` | admin JWT | Enqueue one command to every device in a group |
| GET | `/api/admin/apks` | admin JWT | List uploaded APKs |
| POST | `/api/admin/apks` | admin JWT | Upload an APK (multipart) |
| GET | `/uploads/:file` | — | Download a stored APK (referenced by `INSTALL_APK`) |
| POST | `/api/device/enroll` | enrollment secret | Enroll → device token (auto-creates the device) |
| GET | `/api/device/commands` | device token | Poll pending commands (marks them `sent`) |
| POST | `/api/device/ack` | device token | Ack a command result (`acked`/`failed`) |
| POST | `/api/device/telemetry` | device token | Report battery / owner / kiosk state |

> **Adding a device is device-initiated** — there is no "add device" button. A device appears in
> the dashboard the moment its agent successfully calls `/api/device/enroll` with the correct
> shared secret. Re-enrolling an existing device just rotates its token.

### Command catalog (`contracts.ts` ↔ `CommandExecutor.kt`)

| Command | Payload | Effect on device |
| --- | --- | --- |
| `LOCK_NOW` | — | Lock the screen now |
| `REBOOT` | — | Reboot |
| `REAPPLY_POLICIES` | — | Re-apply all Device Owner policies |
| `ENTER_KIOSK` / `EXIT_KIOSK` | — | Enter / exit Lock Task mode |
| `SET_STATUS_BAR_DISABLED` | `{ disabled: bool }` | Toggle status bar |
| `SET_CAMERA_DISABLED` | `{ disabled: bool }` | Toggle camera |
| `SET_KEYGUARD_DISABLED` | `{ disabled: bool }` | Toggle lock screen |
| `SET_USER_RESTRICTION` | restriction args | Set a `UserManager` restriction |
| `SET_APP_HIDDEN` | app args | Hide/show an app |
| `ENABLE_SYSTEM_APP` | `{ packageName }` | Re-enable a system app |
| `SET_WHITELIST` | `{ packages: [...] }` | Replace the visible-app whitelist |
| `INSTALL_APK` | `{ url, packageName? }` | Download + install an APK from the server |
| `SHOW_MESSAGE` | `{ title?, message }` | Show a full-screen message on the device |
| `SET_SERVER` | `{ url, reenroll? }` | Repoint the agent at a new server URL (see below) |
| `FACTORY_RESET` | — | Wipe the device |

**`SET_SERVER` (server migration).** Changes the URL the agent polls — used to move the fleet to a
new domain/server without rebuilding the APK. The switch is **deferred**: the device acks and posts
telemetry to the *current* server first, then applies the new URL on the next tick (see
`RemoteConfig.setPendingServer`/`applyPendingServer` + `AgentService` step 4). With `reenroll: true`
the device token is cleared so it re-enrolls against the new server (use when the target is a
different backend/database). Send it to a group to migrate the whole fleet at once. A wrong URL
strands the device, so keep the old server reachable until devices reappear on the new one.

---

## 5. Dashboard

React 18 + Vite SPA. Admin logs in, views the fleet (auto-refresh 5s), drills into a device, and
enqueues commands.

Source (`dashboard/src/`): `main.tsx` · `App.tsx` · `api.ts` (calls the server, holds JWT) ·
`contracts.ts` (mirror of server types) · `pages/Login.tsx` · `pages/Devices.tsx` ·
`pages/DeviceDetail.tsx` · `styles.css`. Dev server proxies `/api` → `:4000`.

---

## 6. Local development

Requires **Node 22+** (built-in `node:sqlite`). Two terminals:

```bash
# 1. server
cd rmsoft-mdm/server
cp .env.example .env        # then edit secrets
npm install
npm run dev                 # http://localhost:4000

# 2. dashboard
cd rmsoft-mdm/dashboard
npm install
npm run dev                 # http://localhost:5173  (proxies /api -> :4000)
```

Open http://localhost:5173 and log in with `ADMIN_USERNAME` / `ADMIN_PASSWORD` from `.env`.

**Connect a device:** in `RMLauncher/.../remote/RemoteConfig.kt`, set `DEFAULT_SERVER_URL` (the phone
must be able to reach it — `10.0.2.2:4000` only works on the emulator; a physical phone needs a LAN
IP / HTTPS URL) and `DEFAULT_ENROLLMENT_SECRET` (must equal the server's `ENROLLMENT_SECRET`). Build
(`./gradlew assembleDebug`), install, and the agent enrolls on first run.

### Build the launcher
```bash
cd RMLauncher
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell cmd package set-home-activity com.rmsoft.launcher/.ui.LauncherActivity
```

---

## 7. Configuration & secrets

Server config (`server/src/config.ts`) loads `server/.env` if present, else falls back to insecure
dev defaults. `.env` is git-ignored.

| Var | Purpose | Dev default |
| --- | --- | --- |
| `PORT` | Server port | `4000` |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | Dashboard login | `admin` / `changeme` |
| `JWT_SECRET` | Signs dashboard JWTs | `dev-insecure-secret-change-me` |
| `ENROLLMENT_SECRET` | Shared secret a device must present to enroll (also baked into the agent / QR) | `rmsoft-enroll-changeme` |
| `DB_FILE` | SQLite file (relative to `server/`) | `rmsoft-mdm.db` |
| `DASHBOARD_ORIGIN` | CORS origin in dev | `http://localhost:5173` |

The agent's `ENROLLMENT_SECRET` (in `RemoteConfig.kt`) **must match** the server's, or enrollment
fails with `403 bad enrollment secret`.

---

## 8. Production notes

- Change **every** secret in `server/.env`.
- Put the API behind HTTPS (TLS-terminating reverse proxy); set the launcher's `DEFAULT_SERVER_URL`
  to `https://…` (then drop `usesCleartextTraffic` from the manifest).
- SQLite is fine to start; for a large fleet reimplement `server/src/repo.ts` against Postgres —
  routes and dashboard are untouched.
- Host the release APK on an HTTPS URL and complete `provisioning/rmsoft_provisioning.json`, then
  generate the provisioning QR (see `PROVISIONING.md`).

---

## 9. Documentation map

- `README.md` — top-level overview
- `PROJECT.md` — **this file** (whole-project reference)
- `RMLauncher/README.md` + `RMLauncher/docs/` — INSTALL, VERIFICATION, ARCHITECTURE, ADMIN_PANEL
- `RMLauncher/provisioning/PROVISIONING.md` — QR enrollment
- `rmsoft-mdm/README.md` — server + dashboard runbook

# RMSOFT — Android Enterprise QR Code Provisioning

This app is a self-contained Android Enterprise **Device Owner**. It does **not** use Headwind
MDM or any third-party MDM — every policy is enforced natively via `DevicePolicyManager`.

There are two ways to make RMSOFT the Device Owner. Production uses **(A) QR provisioning**;
the lab/sample uses **(B) ADB**.

---

## A. QR code provisioning (production, factory-reset devices)

The flow on the device:

1. Factory reset the device (Settings → Erase all data, or `Recovery → wipe data`).
2. On the very first **"Hi there" / Welcome** setup screen, **tap the same empty spot 6 times**.
3. The device opens a **QR code scanner** (it may first download a QR reader / ask for Wi-Fi).
4. Scan the RMSOFT provisioning QR code.
5. The device connects to Wi-Fi, downloads the APK from the URL in the payload, verifies its
   signature, installs it, and sets `RMSOFTAdminReceiver` as **Device Owner**.
6. `RMSOFTAdminReceiver.onProfileProvisioningComplete()` fires automatically — it applies every
   lockdown policy, pins RMSOFT as the default Home app, and launches the locked kiosk. No
   further interaction is needed.

### Building the QR payload

The QR code encodes the JSON in [`rmsoft_provisioning.json`](./rmsoft_provisioning.json). Fill in
the three `REPLACE_WITH_…` values:

| Key | What to put |
| --- | --- |
| `…PACKAGE_DOWNLOAD_LOCATION` | A public HTTPS URL serving the release APK. The device downloads it during setup — it must be reachable over the provisioning Wi-Fi. |
| `…SIGNATURE_CHECKSUM` | Base64, URL-safe SHA-256 of the APK **signing certificate** (see below). |
| `…WIFI_SSID` / `…WIFI_PASSWORD` | The facility Wi-Fi the device joins to download the APK. Omit both if provisioning over a network the device already trusts. |

The component name is already correct:
`com.rmsoft.launcher/.utils.RMSOFTAdminReceiver`.

### Pointing the device at your server via the QR (no APK rebuild)

The `PROVISIONING_ADMIN_EXTRAS_BUNDLE` carries the MDM connection details to the device at
provisioning time, so **one signed APK enrolls against any server** — you never rebuild to change
the server URL or rotate the secret. `RMSOFTAdminReceiver.onProfileProvisioningComplete()` reads
these keys and persists them (via `RemoteConfig.applyProvisioningExtras`) before the agent's first
poll:

| Extras key | What to put |
| --- | --- |
| `serverUrl` | Base URL of your rmsoft-mdm server, e.g. `https://mdm.example.com` (no trailing slash). |
| `enrollmentSecret` | Must equal the server's `ENROLLMENT_SECRET`. |
| `facility` | Optional site/facility label stored on the device. |

Any key you omit falls back to the value compiled into `RemoteConfig.kt`. The **dashboard's Enroll
page generates this whole payload (and the QR) for you** with the server's current URL + secret
already filled in — prefer that over hand-editing this file.

### Computing the signature checksum

```bash
# From the signing certificate (DER), URL-safe base64, no padding:
keytool -list -printcert -jarfile rmsoft-launcher.apk
# …or compute directly from the cert:
openssl x509 -outform DER -in signing-cert.pem \
  | openssl dgst -sha256 -binary \
  | openssl base64 | tr '+/' '-_' | tr -d '='
```

Put the result in `PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM`.

### Generating the QR image

Encode the finished JSON (single line) into a QR code with any generator, e.g.:

```bash
# Example using `qrencode`:
qrencode -o rmsoft-qr.png -r rmsoft_provisioning.oneline.json
```

Print it and keep it with the provisioning runbook.

> **Hosting the APK:** any static HTTPS host works (S3 bucket, nginx, GitHub Releases asset).
> The URL must serve the **exact** APK whose certificate matches the checksum above, or the
> device rejects provisioning.

---

## B. ADB (lab / K671 sample, no factory reset of production fleet)

On a device with **no accounts added** (fresh or factory-reset, setup skipped):

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner com.rmsoft.launcher/.utils.RMSOFTAdminReceiver
```

`set-device-owner` triggers `onEnabled`, which applies the full policy set immediately.

> Device Owner can only be set when there are no other users/accounts on the device. If it
> fails with "Not allowed to set the device owner", factory reset and try before adding any
> Google account.

---

## Removing Device Owner (lab only)

In production the Device Owner is **not** removable (see `onDisableRequested`). For lab teardown:

```bash
adb shell dpm remove-active-admin com.rmsoft.launcher/.utils.RMSOFTAdminReceiver
```

# Matthair: Requirements (req42)

Structure follows the [req42](https://req42.de) framework. Requirement IDs (`F-…`, `Q-…`, `C-…`)
are stable and are referenced from the [architecture (arc42)](../architecture/arc42.md), from
commit messages and from code comments.

Status legend: ✅ implemented · 🟡 partially implemented / heuristic · ⏳ not implemented ·
🔬 implemented, **not yet verified on real hardware**.

---

## 1. Business goals / vision

**Vision:** People who run Home Assistant on de-Googled Android (/e/OS, GrapheneOS, LineageOS,
with or without microG) can add Matter devices, Wi-Fi and Thread alike, as easily as with Google's
"Add device" flow, without any proprietary service.

| ID | Goal | Measure |
|----|------|---------|
| G-1 | Matter commissioning into HA without Google Play Services | Works on /e/OS without microG |
| G-2 | Simple flow comparable to the Google flow | ≤ 4 wizard steps, no technical jargon on the happy path |
| G-3 | Reliability | ≥ 95 % success at good signal (> −75 dBm) |
| G-4 | Privacy | No traffic except to the user's own HA instance |
| G-5 | Free software | GPL-3.0, F-Droid compatible, reproducible build |

**Background.** The official HA Companion app delegates Matter commissioning to Google Play
Services. Its "minimal" (FOSS) flavour cannot add Matter devices at all, and with microG users
report a demanded GMS update. Since version 9 the Matter Server app (matter.js based) offers a
BLE proxy endpoint, so any client can lend its Bluetooth radio to the server's commissioner.
Matthair is that client for Android.

## 2. Stakeholders

| Role | Expectation |
|------|-------------|
| Home user on a de-Googled phone | "Scan code, wait, done." Clear help when something fails |
| HA power user / admin | Diagnostics, control over which proxy is used, multi-admin |
| HA / matterjs-server maintainers | Clean protocol use, actionable bug reports (diagnostic bundle) |
| F-Droid packagers | Only free dependencies, reproducible build |
| Contributors | Readable, documented, tested code; documented protocols |

## 3. Scope and context

**In scope:** a phone app that acts as **BLE proxy client** for the Matter Server, controls
commissioning through Home Assistant, and manages Matter devices in HA (list, rename, area,
share, remove).

**Out of scope:** being a Matter controller (no own fabric, no Matter SDK), storing Wi-Fi or
Thread secrets on the phone, controlling devices (on/off etc.), cloud features.

```
 ┌──────────┐  BLE (GATT 0xFFF6)  ┌──────────────┐  HTTPS/WSS (HA auth)   ┌─────────────────────┐
 │ Matter   │◄───────────────────►│  Matthair  │◄──────────────────────►│ Home Assistant       │
 │ device   │                     │  (Android)   │   ingress → /ble, /ws  │  ├ Matter integration│
 └──────────┘                     └──────────────┘                        │  ├ Supervisor/ingress│
      ▲                                                                   │  └ Matter Server app │
      └────────────── Wi-Fi / Thread (after commissioning, CASE) ─────────┴─────────────────────┘
```

## 4. Functional requirements

### 4.1 Business process "Add a Matter device"

1. User taps **Add device** and scans the QR code (or types the code).
2. App shows the device and a **check list**. Problems come with a one-tap fix.
3. User taps **Add to Home Assistant**. The phone stays near the device while progress is shown.
4. On success the user names the device and picks a room. On failure they see the cause and
   concrete next steps.

### 4.2 Epics and requirements

#### E-1 Connection to Home Assistant

| ID | Requirement | Acceptance criteria | Status |
|----|-------------|---------------------|--------|
| F-1.1 | Configure local URL and optional external URL | Local URL tried first, external as fallback | ✅ |
| F-1.2 | Sign in with HA login (OAuth2) | HA's own login page; code exchanged for tokens; refresh handled | ✅ verified on device |
| F-1.3 | Sign in with long-lived access token | Token verified with `auth/current_user` | ✅ |
| F-1.4 | Store tokens only encrypted (Android Keystore) | No plaintext token in prefs or logs | ✅ |
| F-1.5 | Reach the Matter Server only through authenticated HA ingress | No direct connection to port 5580 | ✅ |
| F-1.6 | Require an administrator | Non-admin gets a clear message, wizard blocked | ✅ |
| F-1.7 | Support self-signed certificates and private CAs | User CAs trusted; self-signed leaf can be pinned after showing its SHA-256 | ✅ |

#### E-2 Pre-flight check ("traffic light")

| ID | Requirement | Acceptance criteria | Status |
|----|-------------|---------------------|--------|
| F-2.1 | Bluetooth on, permissions granted, no battery optimisation | Each item green/yellow/red with a fix action | ✅ |
| F-2.2 | Matter Server running, version ≥ 9, `ble_proxy` enabled, `/ble` reachable | `/ble` verified with a real `hello` handshake | ✅ |
| F-2.3 | Offer to enable `ble_proxy` | Only after confirmation; app is restarted via Supervisor | 🔬 |
| F-2.4 | Thread: preferred dataset in HA, border router online | Count of border routers via `thread/discover_routers` | ✅ |
| F-2.5 | Wi-Fi credentials known to Matter Server | `wifi_credentials_set` from server info; entry only for Wi-Fi devices, once | ✅ |
| F-2.5a | Avoid typing Wi-Fi data (Android does not expose saved passwords to apps) | SSID pre-filled from the current connection (location permission, asked on demand); `WIFI:` QR code (router sticker / Android *Share*) via camera or photo fills SSID + password; 5 GHz hint | ✅ |
| F-2.6 | Warn about other BLE proxies | Warning if HA has loaded Bluetooth config entries | 🟡 heuristic |
| F-2.7 | Find the device before starting | RSSI shown; "move closer" below −80 dBm | ✅ |

#### E-3 Capture the setup code

| ID | Requirement | Acceptance criteria | Status |
|----|-------------|---------------------|--------|
| F-3.1 | Offline QR scan with free libraries | CameraX + ZXing core, no ML Kit | ✅ |
| F-3.2 | Manual code, 11 or 21 digits, with Verhoeff check | Dashes and spaces tolerated; live validation | ✅ |
| F-3.3 | Import QR from photo or gallery | System photo picker, no storage permission | ✅ |
| F-3.4 | Decode locally, show VID/PID/discriminator/flow, mask passcode | Passcode shown as `••••1234` | ✅ |
| F-3.5 | Warn on custom flow | Banner explaining the vendor app step | ✅ |
| F-3.6 | Read NFC tag (optional) | NDEF text or URI containing `MT:` | 🔬 |

#### E-4 BLE device discovery

| ID | Requirement | Acceptance criteria | Status |
|----|-------------|---------------------|--------|
| F-4.1 | Scan for service UUID `0xFFF6`, show discriminator, VID/PID, RSSI | Details visible with "technical details" on | ✅ |
| F-4.2 | Match against the code; warn on ambiguity with manual code | Warning if > 1 device matches the short discriminator | ✅ |
| F-4.3 | Signal hint below −80 dBm | Shown in check and during pairing | ✅ |
| F-4.4 | Help if nothing is found | Pairing mode, 15-min advertising timeout, factory reset | ✅ |

#### E-5 Commissioning

| ID | Requirement | Acceptance criteria | Status |
|----|-------------|---------------------|--------|
| F-5.1 | Start via HA `matter/commission` with `network_only=false` while the phone holds the proxy connection | Proxy handshake completes before the command is sent | ✅ |
| F-5.2 | Pin the attempt to the phone (`ble_proxy_id`) | Use as soon as the server supports it | ⏳ upstream |
| F-5.3 | Complete proxy protocol incl. `write_and_subscribe` and binary frames | All 12 commands, 4 events, opcodes 0x01/0x02 | 🔬 |
| F-5.4 | Coexist with several proxy clients | Ownership rules respected; "other proxy took over" detected | 🟡 |
| F-5.5 | Never transfer Wi-Fi/Thread secrets from the phone | Thread dataset copied HA → Matter Server only | ✅ |
| F-5.6 | Show phases: search, BLE, PASE, attestation, network, CASE, done | Phases derived from proxy events and BTP observation | 🟡 attestation/network heuristic |
| F-5.7 | Cancel closes the proxy and frees BLE | Next attempt waits for the server-side attempt | ✅ |
| F-5.8 | Foreground service during commissioning | `connectedDevice` type with progress notification | ✅ |
| F-5.9 | Retry BLE errors (e.g. GATT 133) with pause; after PASE check for a node before retrying | 3 GATT connects × 3 attempts; node check after PASE | ✅ |
| F-5.10 | After success: rename and assign area | Device registry update, new area creation | ✅ |

#### E-6 Extras

| ID | Requirement | Acceptance criteria | Status |
|----|-------------|---------------------|--------|
| F-6.1 | Multi-admin: add from another ecosystem via network | `network_only=true`, no BLE | ✅ |
| F-6.2 | Open a commissioning window to share a device | QR and manual code shown | ✅ |
| F-6.3 | Node list incl. offline devices; remove | Availability from Matter Server `get_nodes` | ✅ |
| F-6.4 | Zigbee permit join (optional) | ZHA and Zigbee2MQTT | 🔬 |
| F-6.5 | Diagnostic bundle | App log + Matter Server log tail, tokens and codes redacted, shared via share sheet | ✅ |
| F-6.6 | Device overview: status, network (Thread/Wi-Fi), device info, connected ecosystems | Data from device registry + `matter/node_diagnostics` | 🔬 |
| F-6.7 | Rename device and change room from the app | `config/device_registry/update`; empty name restores the device's own name; new rooms created on save | 🔬 |
| F-6.8 | Remove other ecosystems (fabrics) from a device, e.g. the Google/Android fabric left by GMS-based pairing | `matter/remove_matter_fabric`; HA's own fabric cannot be removed; confirmation required | 🔬 |

## 5. Quality requirements

| ID | Quality | Scenario / target | Status |
|----|---------|-------------------|--------|
| Q-1 | Privacy | Network monitor shows traffic only to the configured HA host(s); no telemetry, no crash reporting | ✅ by design |
| Q-2 | Security | Passcode only in memory, dropped after the wizard; scrubbed from logs; TLS where configured | ✅ |
| Q-3 | Reliability | ≥ 95 % success at > −75 dBm (10 runs Wi-Fi + 10 runs Thread, ≥ 9 each) | 🔬 to be measured |
| Q-4 | Performance | Wi-Fi device < 60 s, Thread device < 120 s | 🔬 to be measured |
| Q-5 | Robustness | Warn when the phone switches from Wi-Fi to mobile data during commissioning | ✅ (warning, no pause) |
| Q-6 | Usability | ≤ 4 wizard steps; every failure has a plain-language cause and next steps | ✅ |
| Q-7 | Recoverability | After cancelling mid-way, the device can be added again without a factory reset | 🔬 |
| Q-8 | Maintainability | Protocols documented; parser and BTP observer unit-tested | ✅ |
| Q-9 | Portability | Android 12+ (API 31–35), /e/OS with and without microG | 🔬 |
| Q-10 | Localisation | English (default) and German | ✅ |

## 6. Constraints

| ID | Constraint |
|----|-----------|
| C-1 | No dependency on GMS, Firebase, ML Kit or other proprietary SDKs |
| C-2 | F-Droid compatible: free libraries only, reproducible build (no VCS info, no dependency blob) |
| C-3 | License GPL-3.0 |
| C-4 | Android 12+ (minSdk 31), Kotlin, Jetpack Compose |
| C-5 | Home Assistant ≥ 2026.6, Matter Server app ≥ 9.x with `ble_proxy` |
| C-6 | Supervisor-based installation (HA OS / Supervised), needed for ingress |
| C-7 | Devices from Matter 1.0 to 1.6 |

## 7. Domain terms

See the glossary in [arc42 §12](../architecture/arc42.md#12-glossary).

## 8. Acceptance (release criteria)

| # | Criterion | Linked |
|---|-----------|--------|
| A-1 | Runs on /e/OS without microG and with microG | Q-9 |
| A-2 | 10 consecutive commissionings each of a Wi-Fi and a Thread device, ≥ 9 successful each | Q-3 |
| A-3 | With an ESPHome proxy also active: the app uses the phone or clearly reports what happens | F-5.4 |
| A-4 | After cancel mid-way the device can be re-added without factory reset | Q-7 |
| A-5 | Works with QR code and with manual code | F-3.1, F-3.2 |
| A-6 | Network monitor shows no traffic except to HA | Q-1 |
| A-7 | F-Droid build is reproducible | C-2 |

**Current state (v0.1.0):** builds, unit tests green. A-1 to A-4 and A-6/A-7 need real
hardware and an F-Droid build server and have not been run yet.

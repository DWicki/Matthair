# Matthair

**Add Matter devices (Wi-Fi and Thread) to Home Assistant from a de-Googled Android phone.**
No Google Play Services, no microG required, no cloud.

The phone doesn't commission anything itself. It lends its Bluetooth radio to the
**Matter Server app** in Home Assistant through the server's BLE proxy protocol. Home Assistant
commissions the device, and the fabric, certificates, Wi-Fi password and Thread dataset never
leave your HA host.

```
 Matter device ──BLE──► Phone (Matthair) ──HA ingress (authenticated)──► Matter Server ◄── Home Assistant
```

> **Status: 0.1.0, untested on hardware.** The app builds and the unit tests pass, but it has
> not yet been run against a real Home Assistant and real Matter devices. See
> [Known limitations](#known-limitations).

## Features

* Four-step flow like Google's: **Scan → Check → Connect → Finish**
* QR scan (offline, ZXing), manual 11/21-digit code with typo detection, QR from a photo, NFC tags
* Traffic-light pre-flight check with one-tap fixes: Bluetooth, permissions, battery
  optimisation, Matter Server version, `ble_proxy` option (can enable it for you), `/ble`
  reachability, Thread network and border routers, Wi-Fi credentials, competing BLE proxies,
  and the device's signal strength
* Live progress: search → Bluetooth → secure connection (PASE) → verification → network → done
* Automatic retries for flaky BLE (GATT 133), with no blind retries after PASE
* Clear error explanations with concrete next steps, and a redacted diagnostic bundle
* Name the device and pick a room right after pairing
* Device list with online/offline state, **share** a device with another ecosystem (QR), remove it
* Add a device that is already in another ecosystem (multi-admin, network only)
* Optional Zigbee "permit join" (ZHA / Zigbee2MQTT)
* English and German, light/dark theme, Material You colours

## Requirements

| Component | Version |
|-----------|---------|
| Android | 12 or newer (API 31+). Tested target: /e/OS with and without microG |
| Home Assistant | 2026.6 or newer, **HA OS or Supervised** (ingress is required) |
| Matter Server app | 9.x or newer, with the option **`ble_proxy: true`** |
| HA user | Administrator |

## Getting started

1. In Home Assistant open **Settings → Apps → Matter Server → Configuration**, enable
   `ble_proxy` and restart the app. (Matthair can also do this for you from its check list.)
2. Install Matthair and enter your HA address, e.g. `http://homeassistant.local:8123`.
3. Sign in with your HA account (or paste a long-lived access token).
4. Tap **Add device**, scan the code and follow the steps.

For **Thread** devices, set a preferred Thread network in HA (Settings → Thread) with an online
border router. For **Wi-Fi** devices, the Matter Server needs your 2.4 GHz network. The check list
offers to store it if missing.

## Building

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest
```

* Use a **HotSpot-based JDK 17** (e.g. Temurin). The IBM Semeru/OpenJ9 JVM crashed the Gradle
  daemon during dexing in our tests.
* The Android SDK path goes into `local.properties` (`sdk.dir=…`).
* Release builds are minified, contain no VCS info and no dependency metadata, to keep them
  reproducible for F-Droid.

## Documentation

| Document | Content |
|----------|---------|
| [docs/requirements/req42.md](docs/requirements/req42.md) | Requirements (req42): goals, stakeholders, functional and quality requirements with status, acceptance criteria |
| [docs/architecture/arc42.md](docs/architecture/arc42.md) | Architecture (arc42): context, building blocks, runtime, security concept, decisions, risks |
| [docs/ble-proxy-protocol.md](docs/ble-proxy-protocol.md) | The Matter Server's BLE proxy WebSocket protocol and how the app implements it |
| [docs/matter-protocol.md](docs/matter-protocol.md) | The parts of Matter the app relies on: setup codes, BLE advertisement, BTP, PASE detection, multi-admin |

## Privacy

Matthair connects **only** to the Home Assistant addresses you enter. It has no analytics, no
crash reporting and no update checks. Tokens are encrypted with a key in the Android Keystore
and excluded from backups. Setup codes are kept in memory only and removed from all logs.

## Known limitations

* Not yet verified on real hardware (see the acceptance criteria in req42 §8).
* HA Container/Core installations are not supported (no Supervisor, so no ingress).
* If an ESPHome Bluetooth proxy or HA's own adapter sees the device first, the Matter Server may
  use that one instead of the phone. The app detects and explains this. Pinning an attempt to
  the phone needs the proposed `ble_proxy_id` server option.
* The "verifying" and "joining network" phases are estimated from traffic. Only PASE is
  detected exactly.

## License

[GPL-3.0](LICENSE). Third-party libraries: AndroidX/Jetpack Compose (Apache-2.0), OkHttp
(Apache-2.0), kotlinx.coroutines/serialization (Apache-2.0), ZXing core (Apache-2.0).

"Matter" is a trademark of the Connectivity Standards Alliance. "Home Assistant" is a project of
the Open Home Foundation. This app is not affiliated with either.

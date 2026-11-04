# Matter protocol, the parts Matthair touches

Matthair is **not** a Matter controller. It parses setup codes, recognises Matter BLE
advertisements and passively observes BTP traffic to show progress. Everything else (PASE,
attestation, certificates, network provisioning, CASE) is done by the Matter Server. This page
documents exactly those parts of the Matter Core Specification the app depends on, with
pointers into the code.

Spec references are to the Matter Core Specification (CSA). Versions in the field range from
**1.0 to 1.6** (1.6 released June 2026, 1.6.1 maintenance release September 2026). The formats
below have not changed across these versions.

---

## 1. Onboarding payload (setup code)

Code: [`SetupPayloadParser.kt`](../app/src/main/java/io/github/dwicki/hamatterpair/matter/SetupPayloadParser.kt),
tests: [`SetupPayloadParserTest.kt`](../app/src/test/java/io/github/dwicki/hamatterpair/matter/SetupPayloadParserTest.kt).

### 1.1 Fields

| Field | Bits | Notes |
|-------|------|-------|
| Version | 3 | Must be 0 |
| Vendor ID | 16 | CSA-assigned, `0xFFF1–0xFFF4` are test vendors |
| Product ID | 16 | |
| Custom flow | 2 | 0 = standard, 1 = user intent, 2 = custom (vendor app/service required), 3 = reserved |
| Discovery capabilities | 8 | bit 0 SoftAP, bit 1 **BLE**, bit 2 on-network, bit 3 Wi-Fi PAF, bit 4 NFC |
| Discriminator | 12 | Identifies the device among several in commissioning mode |
| Passcode | 27 | 1 … 99 999 998, see §1.4 |
| Padding | 4 | 0 |

### 1.2 QR code: `MT:` + Base38

* Prefix `MT:`, then the 88 bits above in **little-endian bit order** (LSB of the first field in
  bit 0 of byte 0), encoded with **Base38**.
* Alphabet: `0-9 A-Z - .` (38 characters).
* Every 3 bytes become 5 characters (`value = b0 + b1·256 + b2·65536`, emitted LSB-first in base 38).
  A trailing 2 bytes become 4 characters, and 1 byte becomes 2.
* Optional TLV data (serial number etc.) may follow the 11 fixed bytes. It is ignored.
* Several payloads may be concatenated with `*`. Matthair uses the first.

Example (Matter SDK default test device): `MT:Y.K9042C00KA0648G00` decodes to VID `0xFFF1`,
PID `0x8000`, flow standard, capabilities BLE, discriminator 3840, passcode 20202021.

### 1.3 Manual pairing code (11 or 21 digits)

| Digits | Content |
|--------|---------|
| 1 | bit 2 = VID/PID present (21-digit form), bits 1–0 = discriminator bits 11–10 |
| 5 | bits 15–14 = discriminator bits 9–8, bits 13–0 = passcode bits 13–0 |
| 4 | passcode bits 26–14 |
| 5 | vendor ID (21-digit form only) |
| 5 | product ID (21-digit form only) |
| 1 | **Verhoeff** check digit over all previous digits |

Example: `3497-011-2332` decodes to short discriminator 15 and passcode 20202021.

> **Only the upper 4 bits of the discriminator** ("short discriminator") are in the manual code.
> With a manual code, up to 256 different full discriminators match, so several nearby
> devices in pairing mode can be ambiguous. Matthair warns when more than one matches.

Separators (`-`, spaces, dots) are ignored on input. The first digit must be ≤ 7 (8 and 9 are
reserved).

### 1.4 Passcode rules

Valid range 1 … 99 999 998. Forbidden because they are trivial: `00000000`, `11111111`,
`22222222`, … `99999999`, `12345678`, `87654321`. The parser rejects them.

The passcode is the PASE secret. Matthair keeps it only in memory, masks it in the UI
(`••••2021`), scrubs it from logs, and forgets it after the wizard ends.

### 1.5 Commissioning flows

| Flow | What the user must do | Matthair behaviour |
|------|-----------------------|----------------------|
| Standard | Nothing. An uncommissioned device advertises after power-up | Normal path |
| User intent | Press a button or similar to start advertising | Shows a hint |
| Custom | Use the vendor's app/service first | Shows a warning, proceeding is still allowed |

### 1.6 NFC

Since Matter 1.4.1 onboarding data can be stored on an NFC tag (an NDEF record containing the
`MT:` payload). Commissioning itself still runs over BLE. Matter 1.6 adds NFC as a full
commissioning transport, which Matthair does not implement (the Matter Server would have to
drive it). The app reads `MT:` strings from NDEF text or URI records.

## 2. BLE advertisement

Code: [`MatterBle.kt`](../app/src/main/java/io/github/dwicki/hamatterpair/matter/MatterBle.kt).

A device in commissioning mode advertises **service data** for UUID `0xFFF6`:

| Byte | Content |
|------|---------|
| 0 | Opcode, `0x00` = commissionable |
| 1–2 | Little endian: bits 0–11 discriminator, bits 12–15 advertisement version |
| 3–4 | Vendor ID (LE) |
| 5–6 | Product ID (LE) |
| 7 | bit 0: additional data (C3) available, bit 1: extended announcement |

Devices typically advertise at a fast interval for the first 30 s and stop after 15 min. After
that a power cycle or a button press is needed, which is why the app shows this tip when
nothing is found.

Thread devices **must** support BLE for commissioning. That makes the BLE path essential for
Thread.

## 3. BTP, the BLE Transport Protocol

Code: [`BtpObserver.kt`](../app/src/main/java/io/github/dwicki/hamatterpair/matter/BtpObserver.kt).

BTP carries Matter messages over GATT: client→device via writes to **C1**, device→client via
**indications on C2**. The proxy relays BTP frames byte-for-byte. Matthair only *reads*
them to derive progress.

### 3.1 Handshake

First C1 write and first C2 indication have the **H** flag (`0x40`) set and negotiate BTP version,
ATT MTU and window size. This is the exchange that requires `write_and_subscribe`
([ble-proxy-protocol.md §5.1](ble-proxy-protocol.md#51-write_and_subscribe-the-step-that-breaks-naive-clients)).

### 3.2 Data frames

```
 flags | [mgmt opcode] | [ack #] | seq # | [msg length LE16] | payload…
```

| Flag | Bit | Meaning |
|------|-----|---------|
| H | `0x40` | Handshake |
| M | `0x20` | Management opcode follows |
| A | `0x08` | Ack number follows |
| E | `0x04` | Ending segment |
| C | `0x02` | Continuing segment |
| B | `0x01` | Beginning segment, 2-byte message length follows |

A Matter message is reassembled from B … E segments.

## 4. Message layer (what the observer can see)

```
 msg flags(1) | session id(2, LE) | security flags(1) | counter(4) | [src node id(8)] | [dst id(8|2)] | payload
```

* `session id == 0` → **unsecured** session, used only during PASE. The protocol header is in
  clear text: exchange flags, opcode, exchange id, [vendor id], protocol id, [ack counter].
* `session id != 0` → encrypted. Nothing beyond the header is readable.

### 4.1 Secure Channel opcodes (protocol `0x0000`)

| Opcode | Message |
|--------|---------|
| `0x20` | PBKDFParamRequest |
| `0x21` | PBKDFParamResponse |
| `0x22` | Pake1 |
| `0x23` | Pake2 |
| `0x24` | Pake3 |
| `0x40` | StatusReport (general code LE16, protocol id LE32, protocol code LE16) |

How the observer derives phases:

| Observation | UI phase / decision |
|-------------|---------------------|
| First H-frame from device | BTP handshake ok, phase "Establishing secure connection" |
| PBKDFParamRequest / Pake1 | PASE started |
| StatusReport success after PASE start, **or** first message with session id ≠ 0 | **PASE succeeded**, phase "Verifying the device" |
| StatusReport with error during PASE | **PASE failed**: wrong code or device already commissioned |
| ≥ 3 large (≥ 400 B) encrypted device messages | Heuristic: DAC, PAI and attestation responses are done, phase "Joining your network" |
| Server closes BLE after PASE | Phase "Finishing up" (CASE over IP) |

The retry logic depends on the PASE result: before PASE, BLE errors are retried. After PASE,
the app first checks whether a new node exists, because a timeout late in the flow often means
the device was in fact commissioned.

## 5. Commissioning flow (done by the Matter Server)

1. **Discovery**: BLE scan for `0xFFF6` with the discriminator.
2. **PASE**: SPAKE2+ with the passcode, via PBKDF parameters and Pake1/2/3. Result: encrypted session.
3. **Arm fail-safe**: the device rolls everything back if commissioning does not finish in time
   (typically 60 s, extendable). That rollback is why a cancelled attempt does not require a
   factory reset. Matthair waits for the server-side attempt to end before retrying.
4. **Device attestation**: read the DAC and PAI certificates and the attestation response, then
   verify the chain up to a PAA root (production DCL, or the test DCL with `enable_test_net_dcl`).
5. **CSR → NOC**: the device generates an operational key, the commissioner installs root
   certificate, NOC and ACLs.
6. **Network provisioning**: Wi-Fi credentials or Thread operational dataset (both stored in
   the Matter Server or HA, never on the phone), then `ConnectNetwork`.
7. **Operational discovery and CASE** over IP. BLE is closed.
8. **CommissioningComplete**: the fail-safe is disarmed, and HA creates the device.

## 6. Multi-admin

A commissioned device can join further fabrics:

* **Open commissioning window** (enhanced, ECM): the current admin (HA, via
  `matter/open_commissioning_window`) asks the device to accept a new PASE with a fresh,
  temporary passcode. HA returns a new manual code and QR code. Matthair shows both.
* **Join from another ecosystem**: the other ecosystem opens a window and shows a code.
  Matthair sends it to HA with `matter/commission` and `network_only: true`. No BLE is
  involved, because the device is already on the IP network.

Matter 1.6 adds *Joint Fabric* (several controllers administering one shared fabric). It is not
used by HA yet and is out of scope.

# SPEC: BLE Proximity Locate

**Author:** Aurora
**Date:** 2026-06-20
**Status:** APPROVED

---

## What it is

A "getting warmer" proximity finder for stolen/lost phones.
The operator manually activates a BLE beacon on the remote
phone and walks toward the signal. Hot/cold game.

## What it is NOT

- NOT an SOS feature. SOS = be invisible. BLE beacon = be
  findable. These are opposites. BLE NEVER activates during
  SOS. A beacon during SOS is a homing signal for the attacker.
- NOT passive tracking. No background beaconing. No automatic
  activation. No always-on.
- NOT a replacement for GPS locate. GPS gets you to the
  building. BLE gets you to the room.

---

## Flow

1. Operator opens Remote Access on their phone
2. Operator taps dedicated "BLE Locate" button (not the
   regular locate — separate button, separate action)
3. Command goes over SimpleX to the target phone
4. Target phone starts advertising a BLE beacon with a
   pre-shared UUID known only to the paired devices
5. Operator's phone scans for that UUID
6. UI shows real-time signal strength indicator:
   - Far (weak RSSI) → Near → Very close (strong RSSI)
   - Signal strength bar that updates continuously
   - Optional audio tone that beeps faster as signal
     strengthens (like a metal detector)
7. Operator walks around following the signal
8. Operator taps "Stop" → command sent → beacon dies
9. If operator disconnects, beacon auto-stops after 5 minutes
   (timeout — never beacon indefinitely)

---

## Beacon specification

- BLE advertising mode, not connected mode
- UUID: derived from the shared SimpleX connection key
  (unique per device pair, not guessable by third parties)
- TX power: standard (not boosted — no need for 100m range,
  the operator is already in the building)
- Advertising interval: 100ms (fast scan, responsive signal)
- Timeout: 5 minutes if no stop command received

---

## UI — operator side

```
┌─────────────────────────────┐
│  BLE Locate — Zippy's phone │
│                             │
│         ████████░░          │  ← signal bar
│           NEAR              │
│                             │
│    🔊 Audio guide: ON       │
│                             │
│        [ STOP ]             │
└─────────────────────────────┘
```

Three zones based on RSSI thresholds:
- **Far:** RSSI < -80 dBm
- **Near:** -80 to -60 dBm
- **Very close:** > -60 dBm

Thresholds are approximate — RSSI varies wildly with
obstacles, phone orientation, and materials. This is a
compass, not a ruler.

## UI — target side

Nothing. No indication that BLE beacon is active. The
phone looks dead/normal. If the thief is holding it,
they see nothing.

---

## Where it lives

BLE Locate lives on the **Remote Access** screen ONLY.
It does NOT exist on the **Panic Response** screen.

These are two completely separate interfaces for opposite
situations:

- **Remote Access** = phone is stolen/lost. The operator
  is hunting the PHONE. Tools: locate, lock, ring, siren,
  wipe, BLE locate.
- **Panic Response** = person is in danger. The operator
  is helping the PERSON. Tools: GPS track, audio feed,
  camera feed. Everything silent. Nothing that alerts
  the attacker.

They never mix. BLE Locate doesn't need a "hard block
during SOS" — it simply doesn't exist in the panic
context. A beacon reveals position, which is exactly
what a victim in danger must NOT do.

## Safety rules

1. **Lives on Remote Access only.** Not on Panic Response.
   Not on any SOS-related screen. Different screen,
   different situation, different tools.

2. **NEVER automatic.** Operator must press the button.
   No "start beaconing when stolen" automation. The
   operator decides when it's safe to beacon.

3. **NEVER indefinite.** 5-minute auto-timeout. If the
   operator's app crashes, the beacon stops.

4. **UUID not guessable.** Derived from the shared
   connection key. A random person with a BLE scanner
   sees a UUID they can't associate with Aegis or any
   person. No identifying information in the advertisement.

5. **Operator-terminated.** Stop button kills the beacon
   immediately. No confirmation dialog — instant off.

---

## UWB — future enhancement

Pixel 6 Pro+ and Samsung S21+ have UWB (Ultra-Wideband)
chips. UWB gives 10-20cm accuracy with directional
pointing (like AirTag precision finding).

If both phones have UWB hardware, offer UWB locate as an
upgrade over BLE. Same flow, same safety rules, dramatically
better accuracy. Detect UWB capability at pairing time.

Not in scope for v1. BLE first.

---

## Priority

Low. Camera fix and remote control completion come first.
This is a "nice to have after the core is solid" feature.

---

*GPS gets you to the building. BLE gets you to the room.
SOS keeps you hidden. Never confuse them.*

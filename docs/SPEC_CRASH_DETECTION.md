# SPEC: Crash Detection

**Status:** IMPLEMENTED — Chad, 2026-06-04 (built on Artur's go-ahead;
spec was DRAFT — Aurora). `crashdetection/` (CrashDetector +
CrashDetectionStore + CrashCancelReceiver), wired into ProtocolService
(speed-gated, self-arming accelerometer), `SOSTrigger.CRASH_DETECTED`,
a 30s full-screen "I'm OK" countdown, and an Opsec settings screen.
**Tuning caveat:** the G thresholds + stillness window are the spec's
first-cut values and want real-vehicle testing before they're trusted.
**Owner:** Chad (implementation), Aurora (spec)

## Summary

Detect vehicle crashes using the phone's accelerometer and
trigger the existing SOS broadcast. Not a new subsystem —
a new trigger for the existing pipeline. Same output as
manual SOS (GPS, audio, camera to all trusted + emergency
contacts), different input (high-G deceleration instead of
button press).

## Why this belongs in Aegis

Native crash detection (Pixel, Samsung) calls emergency
services via Google/Samsung servers. Aegis crash detection
alerts YOUR chosen contacts via encrypted SimpleX. No
Google, no metadata, your people know before the ambulance
is dispatched. Not redundant — complementary. Native calls
the professionals. Aegis calls the people who care.

## Detection

### Trigger conditions (ALL must be true)

1. **Speed gate:** Phone was traveling >25 km/h within the
   last 60 seconds (GPS-based). This prevents false triggers
   from drops, falls, sports, or other non-vehicle impacts.
   The speed gate uses the existing GPS pipeline — no new
   sensor.

2. **Impact spike:** Accelerometer reads >4G sustained for
   >50ms. A car crash produces 20-60G at the vehicle
   structure; the phone inside experiences 4-15G depending
   on mounting and cabin deformation. 4G is the floor —
   high enough to filter drops (~2-3G) and rough roads
   (~1-2G), low enough to catch real impacts.

3. **Post-impact stillness:** After the spike, accelerometer
   reads <0.5G variance for >5 seconds. The vehicle stopped.
   The phone isn't being handled. This filters out hard
   braking (where the driver immediately picks up the phone
   or continues driving).

### What happens on trigger

1. **Full-screen countdown:** 30-second timer with a large
   "I'm OK" cancel button. The countdown is visible even
   from the lock screen (high-priority notification +
   overlay). Loud audible alert (not siren-level, but
   attention-getting).

2. **If cancelled:** Nothing happens. Event logged locally
   for false-positive tuning. No notification to anyone.

3. **If not cancelled (30s expires):**
   - SOS broadcast fires: GPS + audio + camera to all
     trusted + emergency contacts via SimpleX.
   - Message includes "Crash detected" tag so recipients
     know this was automatic, not manual SOS.
   - GPS continues broadcasting at 5s intervals (standard
     SOS cadence).
   - Audio stream starts (standard SOS audio).

### Sensitivity

One user-facing control: sensitivity slider in Opsec.

| Setting | G threshold | Speed gate | Use case |
|---|---|---|---|
| Low | >6G | >40 km/h | Highway driving, fewer false positives |
| Medium | >4G | >25 km/h | Default. Most vehicles. |
| High | >3G | >15 km/h | Motorcycles, bicycles (lower mass = lower G) |

## When monitoring runs

Crash monitoring is NOT always-on. It activates only when
the speed gate detects vehicle travel:

- GPS reports speed >25 km/h → start accelerometer
  monitoring at 100Hz
- Speed drops below 10 km/h for >2 minutes → stop
  accelerometer monitoring
- Screen off + no speed data for >5 minutes → stop

Battery impact: negligible. The accelerometer draws ~0.5mA.
GPS is already running for location sharing with trusted
contacts. Monitoring activates only during vehicle travel.

## Integration

### With Sentinel

Sentinel uses the accelerometer for intrusion detection.
Crash detection uses the same sensor for impact detection.
They share the sensor stream but watch for different
patterns:

- Sentinel: gentle pickup → proximity → recording
- Crash: violent spike → stillness

Both can run simultaneously. A crash event during Sentinel
monitoring overrides Sentinel (crash = higher priority
than intrusion).

### With SOS pipeline

Crash detection feeds the existing SOSCoordinator. No
new broadcast mechanism. The "Crash detected" tag is the
only addition to the SOS payload — a string field
indicating the trigger source (manual, crash, duress).

### With trust model

Crash SOS reaches the same contacts as manual SOS:
all trusted + all emergency. The trust model doesn't
change. The user's existing SOS list is the crash
notification list.

## Opt-in

Crash detection is OFF by default. Enabled in Opsec →
Crash Detection toggle. No warning dialog (unlike groups) —
this is a purely protective feature with no attack surface
expansion. It monitors YOUR sensors for YOUR safety.

When enabled, a small car icon appears in the status bar
during active monitoring (speed gate triggered). The icon
disappears when monitoring stops.

## Edge cases

**Phone in bag/pocket during crash:** Still works. The
accelerometer detects the impact regardless of phone
position. The G threshold accounts for attenuation.

**Motorcycle crash:** Use High sensitivity (>3G, >15 km/h).
Motorcycle crashes produce lower G at the phone because
the rider separates from the vehicle. The lower threshold
catches these.

**False positive — hard braking:** The post-impact stillness
filter handles this. Hard braking → driver immediately
grabs phone or continues driving → no stillness → no
trigger.

**False positive — speed bump:** Peak G on a speed bump is
~1-2G. Below the 4G threshold.

**Phone thrown from car in crash:** Accelerometer still
captures the impact. GPS still broadcasts location. Audio
may capture less (phone is outside the cabin). The system
still works — just with degraded audio.

**No GPS (tunnel, underground):** Speed gate can't activate
without GPS. Crash detection doesn't run in tunnels. This
is acceptable — the alternative (always-on monitoring)
costs too much battery for a rare edge case.

## Testing — without crashing a car

The thresholds need validation, **not** a real impact. Two layers,
zero collisions:

### Layer 1 — pure-logic unit tests (where thresholds get tuned)

The detection decision is a pure function over a sequence of
`(speedKmh, netG, tElapsedMs)` samples — speed gate → impact spike →
post-impact stillness → fire/don't. Extract that decision out of
`CrashDetector` (away from `SensorManager` / notifications / `Context`)
into an Android-free class (`CrashDecision`) and drive it with
synthetic + recorded traces as JVM unit tests:

| Trace | Expected |
|---|---|
| Real impact: >4G at 60 km/h, then <0.5G for 5s | **FIRE** |
| Hard braking: spike, then continued movement | no fire |
| Speed bump: ~1.5G at speed | no fire |
| Drop while parked: >6G but speed gate closed | no fire |
| Motorcycle (High sensitivity): >3G at 20 km/h → stillness | **FIRE** |

Deterministic, millisecond JVM runs. **Thresholds are calibrated here,
against published crash-G datasets — not against the user's bumper.**
The 4G floor / 25 km/h gate are conservative research numbers; the
tests pin them and guard against regressions.

### Layer 2 — safe end-to-end bench check (optional, no crash)

Set sensitivity High, have a **passenger** (never the driver) ride
above the speed gate, give the phone one sharp safe jolt to cross the G
threshold, then hold it still 5s → the real countdown fires → tap
"I'm OK". Exercises the live pipeline — gate, spike, stillness,
full-screen notification, cancel, SOS — with nothing harmed.

### Explicitly NOT a test mode

There is **no runtime "simulate crash" trigger.** Per the No-Test-Mode
principle (`SPEC_ACHIEVEMENTS.md` Killed Paths), we do not add a fake
trigger that modifies the real flow. Validation is *offline* (Layer 1)
or a *real but harmless* stimulus (Layer 2) — never a simulated event
inside the shipped detector.

## Not in this spec

- Integration with emergency services (112/911). Aegis
  calls your people, not the government. The native
  system handles official emergency calls.
- Airbag deployment detection (barometric pressure). Would
  improve accuracy but requires calibration data we don't
  have. Future consideration.
- Audio analysis (crash sound signature). ML model would
  improve detection but adds complexity. Threshold approach
  is sufficient for v1.

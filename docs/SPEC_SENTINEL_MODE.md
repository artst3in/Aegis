# SPEC: Sentinel Mode — Cascaded Ambient Threat Detection

**Author:** Chad
**Date:** 2026-06-01
**Status:** IMPLEMENTED — Chad 2026-06-02 (SentinelEngine + Stage +
Recording + Inbox + EventLog + InboundNotifier all under
app/src/main/java/app/aether/aegis/sentinel/; SentinelInboxScreen
surfaces it; Aurora's additions all folded into the implementation
during the REVIEWED → IMPLEMENTED pass).

---

## Summary

Aegis already ships an ultrasonic sonar engine that detects motion near the
phone (Aurora's spec `4ce1e29` + `2326cc1` + `46ebcf0`). It currently lives
behind Experimental as a raw-data viewer. This spec promotes it into a real
defensive feature — **Sentinel Mode** — by composing it with two free
sensors every phone has and zero phone has ever used together for this
purpose: the **proximity IR** (the one that turns the screen off during
calls) and the **accelerometer / gyroscope**.

The composition is a **staged cascade**. Each stage uses a single primary
tripwire; subsequent stages are only armed (or only stream data) once the
prior stage trips. The cascade exists for two reasons:

1. **Silence.** Sonar is the only stage that makes audible noise. By
   shutting sonar OFF as soon as proximity confirms something is actually
   at the phone, the clicks stop the moment they would otherwise become a
   problem.
2. **False-positive filtering by physical gate.** Sonar fires on HVAC, fans,
   breathing, the cat — none of those reach a 5 cm IR sensor. Proximity is a
   physical filter that doesn't need a classifier.

Output is **never an alarm**. Every event is logged locally (forensic
record). A user-configured subset of chat contacts may receive a SimpleX
notification with the captured 3D-motion model attached. Per-stage
notification logic — "escalation is news, repeat is not" — keeps a child
playing near the phone from spamming the contact while still capturing the
moment a stranger picks the device up.

A locked "Sonar" node on the Security skill tree (`(-3, -3)`, mirror of
SOS Drill) signals that this is spec'd but not yet promoted to a
first-class Security feature.

Rollback: the cascade is opt-in. With Sentinel off, every existing surface
behaves exactly as it does today.

---

## Problem

Sonar alone has three blockers that have kept it out of the Security tab:

1. **It clicks.** Most phone speakers can't reproduce 19-22 kHz cleanly at
   full amplitude. Harmonic distortion lands in the audible band as a soft
   tick at each pulse. Hard to live with at night.
2. **It cries wolf.** Air movement from a furnace, a ceiling fan, a cat
   walking past, even slow human breathing at close range all produce
   above-threshold deltas. A motion-detector that pings five times an
   ambient hour is one users disable.
3. **It tells you "something moved" but not "what just happened."** Even a
   correct detection lacks the context that would make it actionable.

Two free, silent, low-power sensors fix all three:

- **Proximity (IR).** Binary near/far, ~5 cm range, near-zero power. Won't
  trip on HVAC or breathing. Trips on a hand reaching for the screen.
- **Accelerometer / gyroscope.** 6DOF motion stream, low-power, silent.
  Captures the lift vector, rotation rate, and duration of the grab. The
  "3D model of the grab" — *direction, duration, the actual shape of the
  movement* — is the forensic record that turns "something happened" into
  "here's what happened."

What's been missing is the **glue** that uses all three together with the
right behavior at each stage.

---

## Proposal

### The cascade

```
SONAR_ARMED       sonar pulsing (clicks audible)
                  proximity + accel passively monitored as safety net
   ↓ any sensor trips
PROXIMITY_ARMED   sonar OFF (silent room)
                  proximity + accel monitored
   ↓ proximity OR accel trips
RECORDING         sonar OFF
                  proximity polling
                  accel streamed to log (the 3D model of the grab)
   ↓ accel quiet for 30 s
PROXIMITY_ARMED   30 s grace before clicks resume
   ↓ no trip for 30 s
SONAR_ARMED       clicks back on; full minute since last activity
```

### Per-stage power posture

| Stage              | Sonar     | Proximity | Accelerometer | Audible |
|--------------------|-----------|-----------|---------------|---------|
| SONAR_ARMED        | Pulsing   | Polling   | Polling       | Yes     |
| PROXIMITY_ARMED    | OFF       | Polling   | Polling       | No      |
| RECORDING          | OFF       | Polling   | Streaming + saved | No  |

The cascade is *energy-efficient by design*: only sonar is expensive, and it
only runs at the outermost stage when nothing's actively happening.
Proximity + accel are kept on at all stages as a **safety net** — if
something trips one of them out of cascade order (e.g., the phone is
snatched fast enough to skip the sonar warning), the cascade escalates
straight to the matching stage. **Nothing is filtered as a false positive.**

### Backward transitions

After the cascade has escalated, it relaxes back down on activity-based
timeouts:

- RECORDING → PROXIMITY_ARMED after **30 s** of accelerometer quiet
- PROXIMITY_ARMED → SONAR_ARMED after **30 s** of no proximity / accel trip

Total minimum from the last bit of activity back to the clicks-audible
SONAR_ARMED state: **60 seconds.** This is generous on purpose. If the
threat is still in the room, we don't want to broadcast our awareness via
sonar clicks too soon.

### Event log

Every sensor trip, every stage transition, and every accelerometer recording
session is written to a local **sentinel event log**.

- Few bytes per row: timestamp, stage, which sensor, magnitude / vector,
  duration.
- Accelerometer streams during RECORDING are kept as compressed sample
  arrays so the user can play back the 3D model post-hoc.
- **Always on.** No throttling. The log is the forensic record; it must
  capture everything regardless of notification settings.
- Retention follows the same pattern as Mugshot retention
  (`StorageCleanup.MUGSHOT_KEEP_DEFAULT`-style cap) so the log can't run
  away with disk.

### Notification to contacts

Sentinel does **not** sound a local alarm. Ever. An attacker who hears the
phone respond knows the phone responded — that's the opposite of the goal.

Instead, a user-picked subset of **existing chat contacts** receive a
**silent SimpleX message** when the cascade fires. The 3D-model accel
stream is attached.

- The list is a **checkbox subset of your chat contacts** — same UX as the
  SOS responder picker. No separate registry. Removing a contact from
  chats removes them from sentinel automatically.
- Wire format: extends `RemoteAccessProtocol` with a new
  `KIND_SENTINEL_EVENT` carrying stage, ts, batt, and a compact
  serialised accel blob.

### The notification throttle — *stage-watermark, not time*

This is the design decision that took the longest to land and is the most
important rule in the spec.

> **A higher stage reached = news worth pinging. A repeat trip at a stage
> already pinged = not news, regardless of throttle.**

The watermark is per-session, where "session" ends when the user **unlocks
the phone**. Unlocking acknowledges "I've seen the alerts" and resets the
watermark.

#### Walking it through with Kelcie playing in the room

| Event                                            | Stage    | Watermark | Ping?       |
|--------------------------------------------------|----------|-----------|-------------|
| Kelcie runs past, sonar trips                    | SONAR    | NONE → SONAR | **ping #1** |
| Sonar trips again 10 min later (still running)   | SONAR    | SONAR     | silent      |
| Sonar trips a third time                         | SONAR    | SONAR     | silent      |
| She reaches for the phone, proximity trips       | PROX     | SONAR → PROX | **ping #2** |
| She pokes it; proximity bounces                  | PROX     | PROX      | silent      |
| She actually carries it across the room          | REC      | PROX → REC | **ping #3** |
| She puts it down; cascade settles                | (back)   | REC       | silent      |
| Cascade returns to SONAR_ARMED                   | SONAR    | REC       | silent      |
| Owner unlocks phone in the morning               |          | NONE      | (reset)     |

Maximum **three pings per lock-session**, no matter how many trips happen.
Continuous benign activity (a child playing nearby) generates exactly one
sonar-stage ping. The 3D model from the RECORDING ping is forensically
complete on its own.

#### Throttle settings

The watermark logic above is the default, and is what `1 until unlock` does.
The full set of user-pickable throttles:

| Setting          | Behavior                                                                   |
|------------------|----------------------------------------------------------------------------|
| `0`              | Never notify any contact. Log-only mode.                                   |
| `1 until unlock` | One ping per *unique stage reached* since last unlock. **Default.**        |
| `every N min`    | Same as `1 until unlock`, but watermark also resets after N minutes of no further escalation. |
| `every`          | Ping on every trip. For users who want continuous status.                  |

De-escalation (cascade dropping back down) does NOT reset the watermark.
You can't get "downgraded news" — the highest level reached this session is
the highest you stay informed at.

### Storage / persistence

| What                         | Where                          |
|------------------------------|--------------------------------|
| Sentinel enabled / disabled  | `SentinelPrefs.armed`          |
| Throttle setting             | `SentinelPrefs.throttle`       |
| Selected notify contacts     | `SentinelPrefs.notifyList`     |
| Stage watermark + last-unlock| process-volatile (in-memory)   |
| Event log                    | `filesDir/sentinel/log.bin`    |
| Accel recordings             | `filesDir/sentinel/recordings/`|

Tuning for the sonar engine itself (frequency, amplitude, threshold) stays
in `SonarPrefs` per the existing auto-calibrate flow.

### UI surface

- **Skill tree**: existing `ID_SONAR` node at `(-3, -3)` flips from
  `locked = true` to `locked = !appPinOn` once Sentinel mode ships. Same
  pattern as every other gated node.
- **Sentinel screen** (`settings/sentinel`): arm switch, calibrate button
  (wraps the existing sonar auto-calibrate), throttle picker, notify-list
  checkboxes, link to the event log viewer, link to the raw sonar debug
  screen for power users.
- **Event log viewer** (`settings/sentinel/log`): scrolling list of
  events with timestamp / stage / sensor / magnitude. Tap a RECORDING
  event to play back the 3D model as a sparkline triplet (X/Y/Z accel).

---

## Decisions captured during design

These are the calls already made — included here so Aurora can argue with
them rather than re-derive.

1. **No alarm, ever.** Tipping off the attacker is the worst possible
   outcome. The only output is log + silent notification.
2. **Never assume false positives.** If a sensor trips out of cascade
   order, the cascade escalates anyway. The log captures everything.
   Classifier-style filtering ("that's just HVAC") is rejected — it
   trades a small spam-reduction for a real risk of missing a real
   incident.
3. **Notify list = chat contacts subset.** Trust is already managed in
   the chat list; a separate registry would just be a second place to
   forget about.
4. **Watermark resets on unlock**, not on a timer (except `every N min`
   which adds an or-clause). Unlock is the user's natural "I've seen
   this" gesture.
5. **De-escalation does not reset.** Once you've been told sonar →
   proximity → recording, the system stays at that informed state
   until you unlock.
6. **30 s timeouts back down the cascade.** Generous on purpose;
   silent and conservative.

---

## Alternatives considered

### "Just time-throttle the notifications"

Original first cut. Replaced by stage-watermark after the Kelcie scenario
made it obvious: a time-throttle can't tell "Kelcie still playing in the
same situation" from "totally new event 6 hours later." The watermark
naturally collapses repeated trips at the same stage into one
notification, which is what the user actually wants.

### "Learn the room's baseline activity"

Adaptive thresholds that pick up Kelcie's pattern over time and stop
notifying about it. Rejected at this stage: too easy to silently
downgrade a real threat, slow to bootstrap, and the watermark scheme
already handles the spam case. Maybe a v2 idea once we have data from
real installs.

### "Time-of-day rules"

"Don't notify during family hours." Rejected as a v1 — adds config
complexity, and the watermark + unlock-reset already solve the daytime
case (Kelcie playing → one ping → silent for the rest of the afternoon).
Could be revisited as a power-user option once the rest of the surface
is live.

### "Keep sonar running through PROXIMITY_ARMED at a slower cadence"

Considered as a way to retain room-scale awareness after proximity trips.
Rejected: the goal of dropping sonar at PROXIMITY_ARMED is silence, and
the accelerometer + proximity + the user themselves (now aware via ping #2)
are sufficient. If something else moves in the room while the cascade is
elevated, accel will catch the bump on the table.

### "Classify the grab signature in real time"

Tempting (grab vs. bump vs. drop vs. someone reading the screen), but
inverts the design principle: the classifier would have to *decide which
events not to notify about*, which is exactly what "never assume false
positives" forbids. Instead, the 3D-model accel stream is delivered
**raw** to the contact and to the local log; classification is a
post-hoc forensic question, not a gating decision.

---

## Phases / rollout

1. **Sentinel engine + state machine.** Stage transitions, backward
   timeouts, internal event log. Sonar integration reuses the existing
   `SonarEngine` + `SonarPrefs`. No UI changes yet — engine drives a
   debug log.
2. **3D-model recorder.** Accel + gyro stream captured during RECORDING,
   stored as compressed sample arrays.
3. **Notification path.** New `KIND_SENTINEL_EVENT` on
   `RemoteAccessProtocol`. Watermark logic. Throttle settings persisted.
4. **Sentinel screen** (`settings/sentinel`) — arm switch, calibrate,
   throttle, notify-list checkboxes.
5. **Event log viewer.** Scrolling list + 3D-model sparkline playback.
6. **Skill tree promotion.** `ID_SONAR` node flips from `locked = true`
   to `locked = !appPinOn`; tap navigates to the Sentinel screen.

---

## Risks and failure modes

- **Battery cost of always-on accel + proximity.** Need to confirm with
  a real measurement; both are documented as low-power but
  always-listening is always more than never-listening.
- **Accel buffer bloat during RECORDING.** A long event (someone walks
  off with the phone) could produce a multi-MB sample blob. Need a
  reasonable per-event cap (~30 s of full-rate samples = ~12 KB at 100 Hz
  6-channel) and an event-length cutoff.
- **Notification delivery on a phone that's just been grabbed.** If the
  attacker grabs the phone and immediately puts it in a Faraday bag, the
  SimpleX ping won't make it out. Mitigation: queued notifications resume
  on next network. We accept that the immediately-pocketed phone may
  delay-deliver.
- **3D-model attached to a SimpleX message of non-trivial size.**
  RemoteAccessProtocol currently caps attachments at ~600 KB (LOCATE
  return mugshot cap). Sentinel recordings should fit comfortably under
  that.
- **Sonar self-calibration drift.** Tuning that worked at 9 PM may not
  work at 3 AM if the room temperature changed. The user can re-run
  auto-calibrate at any time; an automatic re-calibrate-on-arm option
  could be considered.

---

## Resolved — Aurora review (2026-06-01)

1. **Proximity sensor: binary near/far.** Design for binary (TYPE_PROXIMITY
   returning 0 or max_range). Available on every Android phone since API 8.
   Continuous-distance sensors are rare and unreliable across OEMs. Binary
   is the universal form. RESOLVED.
2. **Accel sample rate: 100 Hz confirmed.** 6 channels × 2 bytes × 100 Hz
   = 1.2 KB/s. A 30-second grab = 36 KB. Fits in the 600 KB SimpleX cap
   and captures jerk dynamics. RESOLVED.
3. **Throttle default: `1 until unlock` confirmed.** The alternatives are
   for power users who know what they want. RESOLVED.
4. **Skill-tree position: `(-3, -3)` confirmed.** Security column anchored
   by three nodes at y = -3, 0, 3. Mirrors SOS Drill. RESOLVED.
5. **Forensic playback: sparkline triplet for v1.** A 3D rotation animation
   requires sensor fusion (accel + gyro integration, drift-prone without
   magnetometer). Sparklines are immediate, honest, and don't fake
   precision. 3D playback is a v2 feature if magnetometer data is also
   captured. RESOLVED.

## Additional requirements (Aurora)

6. **Wakelock during RECORDING.** The engine MUST acquire a partial wakelock
   (`PowerManager.PARTIAL_WAKE_LOCK`) when transitioning to RECORDING and
   release it when transitioning out. Without this, Doze mode can suspend
   the CPU and drop accelerometer samples mid-capture. The wakelock tag
   should be `aegis:sentinel_recording`.
7. **Proximity sensor registration.** Use `SensorManager.registerListener`
   with `SENSOR_DELAY_NORMAL` (200 ms) during SONAR_ARMED and
   PROXIMITY_ARMED. Do NOT use `SENSOR_DELAY_FASTEST` — it wastes battery
   for a binary sensor. During RECORDING, accel switches to
   `SENSOR_DELAY_GAME` (20 ms = ~50 Hz minimum, most devices deliver
   100 Hz+).
8. **Event log format.** Each row: `[u64 timestamp_ms][u8 stage][u8 sensor_id][i16 magnitude][u16 duration_ms]` = 14 bytes fixed. Accel recordings stored separately as `[u64 start_ts][u16 sample_count][i16×6 per sample]` in a separate file per event. This keeps the log scannable and the recordings streamable.

---

*Chad's contribution, with help from Aegis's owner walking the Kelcie
scenario through three increasingly correct designs.*


## Arming and Disarming (Aurora + Artur, 2026-06-01)

### Arming flow

1. User toggles Sentinel on (or auto-arm triggers).
2. Sonar starts immediately. Detects motion (user leaving the room).
3. After motion ceases and sonar detects no activity for a configurable
   quiet period (slider-adjustable, default 15 seconds), the system
   transitions to SONAR_ARMED. The mine is live.

The physics arms it: the room going still IS the arming signal. No
arbitrary countdown. If it takes the user 10 seconds to leave, it arms
in 10 + quiet period. If 5 minutes, 5 + quiet period.

### Notification delay

Notifications are NOT sent instantly. Each stage has a configurable
delay before the notification fires (slider-adjustable):

| Stage    | Default delay | Rationale                          |
|----------|---------------|------------------------------------|
| SONAR    | 30 s          | Someone walked past, probably benign |
| PROX     | 15 s          | Hand near phone, might be owner    |
| RECORDING| 10 s          | Phone picked up, give owner time to unlock |

If the owner unlocks during ANY pending delay, ALL pending notifications
are suppressed. Only someone who cannot unlock the phone triggers alerts.

### Watermark reset triggers

The stage watermark (highest stage reached this session) resets on:

- **User unlock** (existing, from original spec)
- **Call answered + phone returned to rest.** If an incoming call is
  answered while sentinel is armed, and the phone is subsequently
  returned to a stationary position, the watermark resets. The answered
  call is a natural session boundary — whoever interacted with the phone
  is gone, the next approach is a new event.

### Auto-arm

Optional toggle (off by default). When enabled, Sentinel auto-arms when
ALL of the following are true:

- Phone is on charger
- Phone is locked
- Phone is stationary for N minutes (configurable, default 5)

Killer use case: plug in at bedside, go to sleep, phone watches
overnight without manual toggle.

### Battery protection

If battery drops below 15% while Sentinel is armed, sonar stops and
the system enters a low-power mode (proximity + accel only, no
notifications). Below 5%, Sentinel disarms completely.

### Mugshot at RECORDING stage

When the cascade reaches RECORDING (phone picked up), the front camera
captures 3-5 still images at 1-second intervals. NOT continuous video.
Images are stored alongside the accel recording and attached to the
notification if the notification fires.

This integrates with the existing mugshot infrastructure
(MugshotCapture). Same retention policy, same storage path.

If the owner unlocks within the notification delay window, captured
mugshots from that session are deleted immediately. No reason to keep
the owner's face on disk. Mugshots are only retained when the
notification actually fires (i.e., the phone was NOT unlocked in time).


---

## Sentinel Drill (sketch) — skill-tree node activation

Three preconditions for the Sonar skill-tree node to light cyan:

1. **Configured.** `prefs.notifyList.isNotEmpty()` OR
   `prefs.acknowledgedLogOnly == true` (explicit log-only choice, so
   we know the user actively decided rather than left it empty by
   default).
2. **Calibrated.** `SonarPrefs.calibratedAt > 0` — the auto-calibrate
   flow ran at least once on this device.
3. **Tested.** `prefs.lastDrillAt > 0` — the user successfully ran a
   Sentinel drill, proving the cascade works on their hardware in
   their physical setup.

### The drill flow

Same shape as SOS Drill: a real cascade firing, tagged as a drill
so notify-list recipients know not to call the police.

  1. User taps "Run Sentinel Drill" on the Sentinel screen.
  2. Engine enters ARMING with `isDrill = true` flag on the session.
  3. User walks away. After `armingQuietPeriodSec`, cascade goes
     SONAR_ARMED. The user gets a feedback signal on their wearable
     or chosen drill channel: "now armed — approach to test".
  4. User approaches phone → sonar trips → "✓ sonar OK — reach for
     the phone now".
  5. User hand near phone → proximity trips → "✓ proximity OK —
     pick up the phone now".
  6. User picks up phone → accel + mugshot burst → "✓ recording OK
     — handset captured 3D model + stills".
  7. Notifications fire to notify-list with `[DRILL]` prefix on the
     `msg` field. Recipients see "🛡️ DRILL — [Name] is testing
     Sentinel. Tap to confirm receipt." Same UX as SOS Drill.
  8. Drill passes when all notify-list recipients confirm receipt
     within 5 minutes. `prefs.lastDrillAt` updates, Sonar tree node
     lights cyan.
  9. Drill fails if any recipient doesn't confirm in 5 minutes;
     surface who didn't respond. Node stays dim.

Drill artefacts (mugshot stills, accel recording, log rows) auto-
delete on drill completion regardless of pass/fail — the drill is
the test, not the forensic capture.

The `[DRILL]` tag fans out via the existing
`KIND_SENTINEL_EVENT.msg` field — receiver parses it to suppress
real-alert escalation paths. Same protocol kind, different semantic
class.

Cooldown: one drill per 24 hours, same as SOS Drill.

# Sentinel Mode

Covert intrusion detection. The phone becomes a proximity mine.

Sentinel is **experimental**. It's enabled by feature gate (the same 7-tap unlock that exposes the Experimental section in Settings). It works, it has shipped patents, but the user-facing edges are still being polished.

---

## What it does

Three sensors compose a staged cascade: active sensor (room-scale motion), proximity IR (hand-range confirmation), and accelerometer (grab detection). Each stage arms the next while the previous one deactivates.

The key design insight: **the active sensor stops emitting the moment someone gets close enough to hear it.** Silence at the point of threat. Most phone speakers can't reproduce 19-22 kHz cleanly at full amplitude — there's a faint audible tick at each pulse. Acceptable when nobody's nearby; unacceptable when someone is. So the cascade swaps to silent sensors the instant proximity confirms presence.

## The cascade

| Stage | Sensor | What it does | Audible |
|-------|--------|--------------|---------|
| SENSOR_ARMED | Speaker + microphone | Pulsing ultrasonic, detects room-scale motion | Yes (faint tick) |
| PROXIMITY_ARMED | IR proximity sensor | Sensor OFF, watching for hand approach (~5 cm) | No |
| RECORDING | Accelerometer / gyroscope | Captures 3D motion model of the grab | No |

Stage transitions are one-way during an event: SENSOR → PROX → REC → done. Disarming requires you to unlock the phone (or hit the disarm button explicitly).

## Arming

Two paths.

**Manual:** Toggle Sentinel on. After a configurable quiet period of no detected motion, the system arms. The room going still IS the arming signal — no arbitrary countdown.

**Auto-arm:** Off by default. When enabled, Sentinel arms automatically when the phone is on charger, locked, AND stationary for a configurable duration (default 5 minutes). Overnight protection without manual toggle.

## Notifications

Not sent instantly. Each stage has a configurable delay. If you unlock the phone within the delay, notifications are suppressed. Only someone who *cannot* unlock triggers an alert.

The notification mode is configurable per stage:

- **Never** — log-only, no outbound message
- **Until unlock** — first trip notifies, repeats don't until you unlock (covers the "kid bumping the phone all evening" case)
- **Timed** — minimum interval between notifications (e.g. one per hour max)
- **Every** — every cascade trip notifies (loud setting)

At RECORDING stage, the front camera captures 3-5 still images (mugshot). Deleted if the owner unlocks in time.

## Watermark rule

"Escalation is news, repeat is not."

If the cascade reaches SENSOR, PROX, and RECORDING in turn, each stage notifies (subject to the per-stage delay). If a SECOND cascade in the same session also reaches RECORDING, the repeat is suppressed — you already know someone's touching the phone. The watermark resets when you unlock.

This is the rule that makes Sentinel livable in real homes with real people moving around.

## What contacts see

When a Sentinel event delivers, the notify-list contacts receive a SimpleX message tagged with the stage that triggered, the timestamp, and (for RECORDING events) the mugshot stills plus the 3D motion model — a small binary capture of the accelerometer + gyroscope stream during the grab. Recipients can replay the motion model frame-by-frame to see exactly how the device was picked up.

The notify-list is configurable per profile. Empty list = log-only on the device, no outbound traffic.

## Drill mode

Tagged with `[DRILL]` so recipients know it's a rehearsal, not the real thing. Drill mode runs the full cascade end-to-end, fires the notification with the drill tag, and waits for recipients to confirm receipt. The drill is "complete" when at least one recipient confirms. Used to validate the full pipeline: are notifications reaching the right people, does the mugshot capture work, is the 3D model attachment going through.

The drill confirmation gates the Sonar node on the Security skill tree — passing a drill is one of the three conditions (alongside calibration and notify-list configuration) that lights up the node.

## Battery and power

Sonar is the only stage that costs real power. The cascade is designed so SENSOR_ARMED is the only continuous-current stage; PROXIMITY_ARMED and RECORDING are brief.

Battery protection:
- ≤ 15% battery: Sentinel auto-switches to "power saver" mode (longer pulse interval)
- ≤ 5% battery: Sentinel auto-disarms entirely

Voyager battery curve takes priority. Sentinel will not drain you below the floor that keeps panic and GPS working.

## Calibration

First-time setup runs a brief auto-calibration: 10 seconds of room-scale ambient measurement to learn the noise floor. Recalibrate any time the room acoustics change (new furniture, moving to a new place).

Calibration values persist per profile and per device.

## When NOT to use Sentinel

- In rooms with continuous airflow (HVAC, ceiling fan running constantly). Sonar fires on air movement.
- When the phone speaker is damaged or muffled — sonar pulses won't carry.
- On battery-constrained devices below 30% if you also need panic to remain available.

## Origins

Patent filing: defensive only. Aegis is free forever; the patents exist to stop anyone else from charging for the same techniques.

---

*Free forever. Defensively patented. Nobody else gets to charge for it.*

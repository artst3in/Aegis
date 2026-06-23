# SPEC: Panic Trigger v2 — two-path (slow direct-count + fast Emergency-SOS piggyback)

**Author:** Chad
**Date:** 2026-06-20
**Status:** DECIDED by owner (2026-06-20). Tracks issue #27.

## Decision (owner) — supersedes the Path-2 proposal below

The Emergency-SOS *piggyback* (Path 2, accessibility service) is **REJECTED**.
Detecting that the OS Emergency SOS fired has no robust app-available signal:
window-matching means **hardcoding a ROM-specific UI identity that silently dies
on a GrapheneOS update** (catastrophic for a life-safety trigger — it fails
exactly when you need it, with no warning), and the privileged telephony
callbacks aren't available to a sideloaded app. We do NOT ship brittle detection
for a panic button.

Shipped approach instead — own only what we can detect reliably, and make its
limits legible:

1. **Keep the slow-press counter exactly as-is** (`TARGET_ALTERNATIONS = 4`,
   `WINDOW_MS = 2000`). It already fires on deliberate ~once-a-second presses,
   which dodge the OS gestures. No threshold change.
2. **Triple-vibration on fire** — `PowerButtonSOSReceiver` buzzes three times
   (60 ms pulses) the instant the BUTTON SOS triggers, so it NEVER fails
   silently in a pocket. No buzz = it didn't fire. (NOT on duress-cancel — that
   path stays silent so a coercer gets no tell.)
3. **Tutorial copy** explains it: press slowly (~1/s); fast presses are taken by
   Android's camera (2×) / Emergency SOS (5×) and can't reach Aegis — an OS
   limit, not a bug; three buzzes confirm it fired; no buzz after four → too
   fast, slow down and keep pressing until you feel it.

Fast mashing therefore lands on the OS Emergency SOS (which calls real help) and
that is fine — the two are complementary, not competing. The sections below are
retained for the record but Path 2 is not built.

---

---

## Problem

The power-button panic trigger (`PowerButtonSOSReceiver`, "press 4×") counts
`ACTION_SCREEN_ON/OFF` toggles because apps cannot hook `KEYCODE_POWER` — that
lives in `system_server` and is not exposed by any public API.

On-device finding (GrapheneOS Pixel): **slow presses work, fast presses don't.**
Root cause: Android reserves rapid power presses for its OWN gestures, which
intercept at the input layer before any app sees them:

- **2 fast presses → Camera** ("Quickly open camera").
- **5 fast presses → Emergency SOS** (the 112 countdown screen — confirmed it
  shows a *cancelable countdown*, it does NOT silently auto-dial).

A fast mash is consumed by these gestures, so Aegis never gets a clean run of
screen-toggle events. Slow, deliberate presses trigger neither system gesture,
so the toggles come through and Aegis fires. We cannot out-rank a system power
gesture from userspace — so we stop trying to *count* fast presses and instead
*ride* the OS's own detection.

## Desired behaviour (owner)

| Gesture | OS Emergency SOS | Aegis 🆘 |
| --- | --- | --- |
| 4 slow presses | no (needs fast) | YES |
| 5 slow presses | no (needs fast) | YES |
| 5 fast presses | YES (112 countdown) | YES (in parallel) |

The point: a fast mash gives you BOTH — the OS calls real help (112) **and**
Aegis silently alerts your trusted contacts + captures mugshot/location.

## Design — two complementary paths

### Path 1 — slow, direct count (EXISTING, keep as-is)

`PowerButtonSOSReceiver` keeps counting `SCREEN_ON/OFF` toggles, firing at
`TARGET_ALTERNATIONS` (4) within the window. Slow presses dodge the OS gestures,
so the toggles arrive cleanly — this already fires on 4–5 slow presses, verified
on device. **No threshold change required.** (Optional: widen `WINDOW_MS` a touch
so 5 unhurried presses spread over a longer span still land inside one window —
low risk, but not strictly needed.)

### Path 2 — fast, piggyback on the OS Emergency SOS (NEW)

We can't count fast presses, so detect the *result*: when the OS Emergency SOS
countdown appears, raise the Aegis SOS alongside it.

- **Mechanism:** an **opt-in `AccessibilityService`** observing
  `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOWS_CHANGED`. When the Emergency SOS
  countdown window appears (matched by package/class), route to the SAME
  `SOSHandler` entry Path 1 uses, with the SAME state logic.
- **Why accessibility:** it is the only app-available signal that Emergency SOS
  fired. The privileged telephony route (`OutgoingEmergencyCallListener`,
  API 31+) needs `READ_PRIVILEGED_PHONE_STATE` — a system permission, dead end
  for a normal app. No broadcast exists for Emergency SOS.

## Detection identity — NEEDS on-device capture (GrapheneOS)

The Emergency SOS countdown window's package/class differs by ROM. On AOSP it is
typically SystemUI's emergency-gesture UI (`com.android.systemui`) or the
emergency dialer (`com.android.emergency` / `com.android.phone`). The exact
identity on the target **GrapheneOS** Pixel must be captured before we hardcode a
match — otherwise the service silently never fires.

Capture plan:
1. Manual: `adb shell "while true; do dumpsys window | grep -i mCurrentFocus;
   sleep 0.3; done"`, trigger Emergency SOS, read the focused window when the
   countdown shows.
2. In-app: ship a DEBUG-ONLY logging mode in the service that logs every window
   package/class; trigger once, read logcat, pin the match. Keep the match list
   configurable (a small set of known identities) rather than a single literal.

## Permission + UX

- `BIND_ACCESSIBILITY_SERVICE` — the user MUST enable it manually (a deliberately
  heavy, scary grant). A Device Owner can whitelist via
  `setPermittedAccessibilityServices` but cannot silently enable it.
- **Opt-in, off by default.** Honest copy: "Aegis watches for the system
  Emergency SOS so it can alert your trusted contacts the moment you call for
  help." The slow-press Path 1 keeps working without this permission.
- **Single narrow purpose.** The service does ONE thing — detect Emergency SOS —
  and touches nothing else. Document this; accessibility is heavily abused and we
  must be visibly minimal (and survive a Play-policy-style review even though we
  sideload).

## Duress / dedup

- Path 1 already has state-dependent logic: no SOS → trigger; DURESS active →
  cancel (the duress profile's only cancel); visible SOS → no-op. Path 2 MUST
  route through the same logic, not a parallel copy.
- **Dedup:** a slightly-fast 5-press could trip BOTH paths. Add a short debounce
  (e.g., ignore a second trigger within ~3 s) at the `SOSHandler` entry so only
  one SOS fires.
- **Trigger kind for Path 2:** the user mashed for help → visible
  `SOSTrigger.BUTTON` (not duress). Duress-cancel stays the slow power-×4 path.
  Confirm with Aurora.

## Open questions (Aurora)

1. Emergency SOS window identity on GrapheneOS — confirm package/class (capture
   above).
2. Is an `AccessibilityService` acceptable for a safety opt-in, given the
   permission weight? If not, fast-mash falls back to "OS Emergency SOS only" and
   Aegis stays on the slow path + in-app button.
3. Dedup window between Path 1 and Path 2.
4. If the user CANCELS the OS Emergency countdown, should Aegis still have fired?
   (Lean yes — they reached for help; the silent contact-alert is already
   warranted. Aurora's call.)
5. Does GrapheneOS ship Emergency SOS by default, and is it on for this user?
   If a user has it disabled, only Path 1 (slow) applies — document.

## Verification (on the GrapheneOS Pixel)

- Enable the accessibility service.
- 5 fast presses → OS Emergency SOS countdown AND Aegis SOS both fire; exactly
  one Aegis SOS (no double-fire).
- 4–5 slow presses → Aegis SOS only (no OS Emergency).
- Duress profile active + slow power-×4 → cancels the duress SOS (unchanged).

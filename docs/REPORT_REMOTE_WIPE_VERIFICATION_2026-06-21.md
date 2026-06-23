# Verification Report — Remote Wipe (three-tier) + Duress Guard

**Date:** 2026-06-21 (session ~20:57–22:11 local)
**Build under test:** `2026.06.826` (release variant, `applicationId = app.aether.aegis`, versionCode `202606826`)
**Author:** Chad (implementation + test design)
**Spec:** `docs/SPEC_REMOTE_WIPE_V2.md` (REVIEWED — Aurora approved with additions)
**Test plan:** `docs/TEST_PLAN_WIPE_V2.md`
**Tracking issues:** #25 (three-tier wipe), #24 (FRP option), #9 (duress-at-auth), #28 (DO wipe regression), #33 (SOS audio — new find)

> **Status: PASS.** Remote wipe verified end-to-end on a real GrapheneOS device
> across **Tier 1 (Device-Owner factory reset)** and **Tier 3 (Aegis-data wipe
> fall-through)**, with honest outcome reporting on every path and a duress guard
> that cannot be tricked into wiping. One genuine defect surfaced during testing
> (#33, SOS audio capture) — see §7.

---

## 1. Executive summary

A remote "kill switch" is one of the highest-stakes features in the app: it
destroys user data on command, it runs on a device the operator cannot see, and
it must behave correctly on hardened ROMs that deliberately break the normal
admin APIs. Getting it *almost* right is worse than not shipping it, because a
user in danger would rely on a guarantee that silently didn't hold.

Tonight we proved, on a physical GrapheneOS Pixel, that the implementation
delivers the two properties that actually matter for the threat model:

1. **It degrades honestly.** Where a full factory reset is possible (Device
   Owner) it factory-resets and *says so*; where the OS blocks the reset
   (GrapheneOS, non-DO) it falls through to destroying all app data and reports
   *that* instead — never the other way around. The contact broadcast is fired
   once, up front, and is never false because the Tier-3 floor cannot fail.
2. **It cannot be coerced into wiping.** A duress PIN entered at either the
   login prompt or the wipe prompt short-circuits *before any wipe tier runs*,
   raises a silent SOS, and revokes + blocks the operator — while showing the
   operator the exact same "wrong PIN" response a genuine mistake would.

Both were verified with the **minimum possible destruction**: a single cheap
(app-data-only) wipe and a single full factory reset, on a device that was
already being repurposed.

---

## 2. Why this is hard

**Threat model.** The target user is someone for whom phone security is not
optional — domestic-abuse / stalking / coercive-control / trafficking
survivors, journalists, activists, vulnerable family. The remote wipe exists so
that a lost, stolen, or seized phone can be neutralised remotely by a trusted
contact, and so that a coerced unlock attempt fails safe.

**The technical problem.** Android's device-management surface behaves
differently across three axes, all of which the feature has to handle:

- **Device Owner vs. non-DO Device Admin.** A Device Owner can factory-reset the
  whole device; a plain Device Admin can only call `wipeData()`, and only
  *some* flags are legal for it.
- **Stock Android vs. GrapheneOS.** GrapheneOS deliberately **silently no-ops**
  factory-reset requests in many configurations rather than honouring them, and
  rejects the privileged `WIPE_RESET_PROTECTION_DATA` (FRP) flag with a
  `SecurityException`. A large fraction of the app's audience runs GrapheneOS on
  exactly the Pixels we target, so "works on stock" is not enough.
- **No safe dry run.** There is no non-destructive way to ask the OS "would this
  wipe succeed?" — a successful `wipeData()` never returns (the process is
  gone). The only signals are *it came back* (failed/no-op) or *the device
  reset*. Correctness therefore has to be designed in, then proven on hardware.

This is the combinatorial trap the three-tier design exists to defuse.

---

## 3. Architecture under test

(Reference: `RemoteCommandHandler.fireWipe` / `wipePreflight`.)

### 3.1 Three tiers, tried in order

| Tier | Precondition | Mechanism | Outcome |
|---|---|---|---|
| **1** | Device Owner | `wipeDevice()` (API 34+) / `wipeData()` with full flags incl. FRP clear | full factory reset |
| **2** | non-DO active Device Admin | `wipeData()` **without** `WIPE_RESET_PROTECTION_DATA` (DO-only flag → `SecurityException` if passed) | factory reset on stock Android |
| **3** | always (floor) | `clearApplicationUserData()` | destroys all Aegis data (DB, vault, keys, messages, media, contacts); **cannot fail** |

The wipe attempts the strongest tier its privileges allow, and **falls through**
when a stronger tier returns without resetting (the GrapheneOS no-op case).
Tier 3 is the universal floor: every user, on every OS, is guaranteed at least
the destruction of all sensitive app data.

### 3.2 Honest reporting (the non-negotiable)

- The operator is shown the **actual** outcome — `phone factory-reset` (Tier 1/2)
  vs. `Aegis data destroyed — phone still running` (Tier 3). It must never claim
  a factory reset that didn't happen.
- The `[aegis:wiped]` broadcast to Trusted + Emergency contacts fires **once, up
  front**, and is **never false**: because Tier 3 always succeeds, "wiped" is
  always true by the time the process dies.
- All outbound traffic **flushes (~1.5 s)** before the data wipe kills the
  process, so the broadcast and the operator status both leave the device.

### 3.3 Duress-first

A duress PIN entered at the remote **auth** prompt or the **wipe** prompt
short-circuits *before any tier runs*: it sends `KIND_DURESS_SOS`, raises a
**silent SOS** (GPS + audio broadcast to contacts), and **revokes + blocks** the
operator. To the operator it is indistinguishable from a wrong PIN. Anti-spoof
is preserved at the auth prompt (where no session exists yet) via a
recent-outbound-auth check (`RemoteAccessSession.markAuthSent` /
`recentlyAttemptedAuth`).

---

## 4. Test methodology

From `TEST_PLAN_WIPE_V2.md`: verify every tier and the reporting with the
**fewest real wipes**. Wiping a device requires the *other* device as an
authenticated operator (paired + remote access granted in that direction).

- **Stage 0** — zero wipes (preflight + duress + wrong-PIN). Free; catches the
  most likely regressions.
- **Stage 1** — one **cheap** wipe (Tier 3, app-data-only; OS survives).
- **Stage 2** — one **expensive** wipe (Tier 1 DO factory reset).
- **Stage 3** — optional, **hard-gated**: Tier 2 on stock Android (OnePlus),
  only after that device's data is migrated; not run this session.

Roles this session: **target = GrapheneOS Pixel**, **operator = OnePlus** (which
also runs a second "contact" instance that receives SOS / wipe broadcasts).

---

## 5. Environment

| Item | Value |
|---|---|
| Target | Pixel, GrapheneOS, `sdk=36` |
| Operator | OnePlus (+ a second contact instance on the same device) |
| App build | `2026.06.826` release (`app.aether.aegis`), appUid `10144` |
| Pixel admin state (Stage 0/1) | Device Admin active, **Device Owner false** |
| Pixel admin state (Stage 2) | **Device Owner true** (provisioned via adb) |
| Operator host | Windows (logcat via `findstr`) |

PINs used were trivial throwaway test values on a device that was wiped at the
end; they are intentionally **not** recorded here. GPS coordinates observed in
the SOS broadcasts were the owner's real location and are **redacted**.

---

## 6. Results

### 6.1 Stage 0.1 — Preflight (non-DO)

`Diagnostics → Check remote-wipe readiness`:

> PARTIAL — not Device Owner (pkg=app.aether.aegis). Factory reset: Device-Admin
> tier — works on stock Android, no-ops on GrapheneOS. Aegis-data wipe (Tier 3):
> ALWAYS works — remote wipe will at least destroy all Aegis data. For a full
> factory reset provision Device Owner: adb shell dpm set-device-owner.
> **(DeviceOwner=false, adminActive=true, sdk=36)**

✅ Honest, accurate readout. Resolved the standing DO-vs-non-DO ambiguity: the
Pixel was **never** Device Owner during the earlier #28 report.

### 6.2 Stage 0.2 — Duress PIN at the WIPE prompt

Operator opened a session (real PIN) → full remote panel rendered (camera /
locate / mic / actions, with **Wipe device** + **Trigger SOS** in red). The
"instant screen lock" on connect was confirmed by the owner as the **auto-locate
by-design** behaviour.

Tapped **Wipe device** → entered the **duress** PIN. Observed:

- Operator: **`wipe: wrong device PIN — nothing was erased`** (wrong-PIN cover —
  no hint of a duress trip).
- Silent SOS fired: **`SOS ACTIVE · SILENT` / "Help is coming" / "Silent alert
  sent. GPS broadcasting. Trusted and emergency contacts notified."** GPS
  broadcasting [redacted], `Audio: recording`. Contact instance received
  **`SOS DURESS …`** alert.
- **Nothing erased** — Pixel data intact.

✅ Duress guard short-circuited before any tier ran.

### 6.3 Stage 0.2 (cont.) — Session revoked + operator blocked

With the (duress-tripped) session, the operator attempted further commands:

- **Ping** → `ping: session expired — re-auth`
- **Ring** → operator showed `Sent: Ring` (optimistic dispatch), target
  rejected: `ring: session expired — re-auth`
- **Lockscreen message** → `display: session expired — re-auth`
- Pixel → Remote Access → Trusted Contacts: **`debug — Blocked — toggle on to
  restore access`**

✅ Duress didn't merely expire the session — it **auto-blocked the operator**
until manually restored. Every subsequent command was authoritatively refused by
the target.

> **Notable catch.** The contact instance was observed reporting Aegis version
> `2026.06.820`. The owner caught that the **Pixel target had not yet updated to
> 826** and updated it before any destructive step — preventing a destructive
> wipe against the *wrong build*. (This is exactly the pre-wipe build check the
> plan calls for.)

### 6.4 Duress PIN at the LOGIN prompt (#9)

Fresh login attempt; entered the **duress** PIN at the authentication prompt
(before any session exists):

- Operator: **`Access denied — wrong PIN`** / **`Revoked by Pixel — retry`** —
  **no session opened** (operator never reached the action panel).
- Silent SOS fired (`SOS ACTIVE · SILENT`, GPS broadcasting [redacted]).
- Pixel: **`DURESS ALERT — debug may be un… / SOS DURESS …`**; `debug` blocked.

✅ Verified issue **#9** (duress-at-auth → silent SOS, no session, operator
blocked). **#9 closed.** Both duress entry points (login + wipe) confirmed.

### 6.5 Stage 1 — Tier-3 wipe (non-DO, cheap) — MANDATORY

Restored the operator, reopened the session, entered the **real** PIN at the wipe
prompt. Observed:

- Operator toast: **`wipe: Aegis data destroyed ✓ — phone still running,
  identity gone`** (honest Tier-3 wording — **not** "factory-reset").
- Operator notification: **`💥 Pixel's Aegis was wiped — Send a new invite link
  to reconnect — the old identity is gone.`** (does **not** claim a factory
  reset).
- Pixel: returned to **`Welcome to Aegis — Restore from a previous backup, or
  start fresh`** with a fresh camera-permission prompt — **OS alive**, all Aegis
  data + identity gone.
- Both messages were delivered **before** `clearApplicationUserData` killed the
  process (the ~1.5 s flush held).

Logcat (target, `21:38:41`) confirmed the fall-through at the code level:

```
W RemoteCommand: wipe: firing factory reset (DO=false, admin=true, sdk=36)
W RemoteCommand: wipe: attempt 1 (flags=1) returned without resetting
W RemoteCommand: wipe: attempt 2 (flags=0) returned without resetting
W RemoteCommand: wipe: tier-3 Aegis-data wipe (Tier 3)
W RemoteAccess:  wipe: factory reset did not take -> falling to Aegis-data wipe (Tier 3)
I ActivityManager: Force stopping app.aether.aegis appUid=10144 user=0: clearApplicationUserData
```

> **Key detail:** on GrapheneOS the `wipeData()` calls **returned without
> resetting** (silent no-op) — they did **not** throw. That is precisely why the
> non-DO path strips the DO-only `WIPE_RESET_PROTECTION_DATA` flag: passing it
> would `SecurityException`; omitting it lets the call no-op cleanly and the
> tier fall-through take over.

✅ Criterion **(c)** of #25 verified: non-DO `fireWipe` ran without crashing, the
factory-reset attempts no-op'd, Tier 3 destroyed all data, honest reporting +
broadcast fired, flush held. **One cheap wipe.**

### 6.6 Stage 2 — Tier-1 DO factory reset (expensive) — RUN BY OWNER CHOICE

The plan marked Stage 2 optional (DO reset evidenced by #7). The owner elected to
run it "one last time to make sure it still works," while the Pixel was fresh.

Provisioned Device Owner on the freshly-cleared Pixel:

```
adb shell dpm set-device-owner app.aether.aegis/.admin.AegisAdminReceiver
```

→ success; permissions auto-elevated to max (Device Owner behaviour). Preflight
then read:

> **READY ✓ (Device Owner tier)** — factory reset allowed. Remote wipe will fire
> (and clears FRP so the phone won't lock to a Google account after).
> **(DeviceOwner=true, adminActive=true, factoryResetBlocked=false, sdk=36)**

Remote wipe (real PIN). Observed:

- Operator: **`wipe: phone factory-reset ✓`** + **`💥 Pixel's Aegis was
  wiped …`** broadcast.
- Pixel: GrapheneOS boot logo → **`Welcome to GrapheneOS — Let's set up your
  device in a few steps`** = a **full factory reset**, device back to first boot.
- No logcat captured (the reset wiped the device and dropped adb) — the **setup
  wizard is the proof**.

✅ Criterion **(a)** of #25 verified: **Tier 1 DO factory reset works on
GrapheneOS.** This also **resolved #28** (the earlier "SecurityException →
no-op, nothing reset" is gone on build 826). **#28 closed.**

### 6.7 FRP note (#24)

At the DO wipe prompt the owner asked whether a "leave it FRP-locked" option
exists. It does **not**: that is enhancement **#24**, still open / awaiting spec
sign-off (it touches the frozen wipe path). Current behaviour is the opposite —
the DO path **always clears FRP** (`WIPE_RESET_PROTECTION_DATA`, anti-brick).
On this de-Googled GrapheneOS Pixel (no GMS) FRP is moot in either direction;
#24's scope already requires the toggle to detect GMS and hide itself on such
devices.

---

## 7. Defect surfaced during testing — #33 (SOS audio capture)

The most valuable outcome of the night was a **real bug**, found only because the
target's full logcat buffer was inspected rather than trusting the green UI.

At `21:31:30` (the duress-SOS window, ~7 min before the clean wipe), the target
logged:

```
E ProtocolService: java.lang.SecurityException: Starting FGS with type microphone
  callerApp=ProcessRecord{… app.aether.aegis/u0a144} targetSDK=35 requires permissions:
  all of the permissions allOf=true [android.permission.FOREGROUND_SERVICE_MICROPHONE]
  any of the permissions allOf=false [android.permission.CAPTURE_AUDIO_OUTPUT,
    android.permission.CAPTURE_MEDIA_OUTPUT,
    android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT,
    android.permission.RECORD_AUDIO]
  and the app must be in the eligible state/exemptions to access the foreground microphone
```

**Meaning.** The SOS UI showed `Audio: recording`, but the attempt to start the
**microphone-type foreground service** was rejected. On Android 14+ (targetSDK
35, device sdk 36) a `microphone` (while-in-use) FGS started from the
**background** is denied unless the app is in an eligible state / holds a
BG-start exemption. The SOS was triggered by an **incoming remote duress signal**
→ app in background → mic FGS start blocked.

**Impact (serious for a safety app).** The "Audio: recording" indicator was
**dishonest** — no duress audio was actually captured. A user would believe they
had an audio record of an incident when they did not. This is very likely the
real root cause behind **#8** (remote camera/mic capture failing with a generic
`capture_failed`).

Filed as **#33** (P-bug). Fix direction: make the mic FGS start eligible from a
background trigger (full-screen / high-priority notification grant, or start from
an already-foregrounded SOS activity, or an approved BG-start exemption) **and**
make the "Audio: recording" indicator truthful (show it only once the mic FGS is
actually running; surface failure otherwise). Re-check the same path for remote
Live mic / Listen (#8).

---

## 8. Issue ledger (this session)

| Issue | Action |
|---|---|
| **#9** duress-at-auth → silent SOS | **Closed** — verified §6.4 |
| **#28** DO wipe SecurityException + no-op | **Closed** — resolved on 826, verified §6.6 |
| **#25** three-tier wipe | Updated — (a)+(c) verified; only (b) stock Tier-2 remains |
| **#24** FRP-lock option | Confirmed not shipped; clarified scope (§6.7) — stays open |
| **#33** SOS audio mic-FGS failure | **Filed** — §7 |

---

## 9. Verification matrix

| Property | Path | Status |
|---|---|---|
| Tier 1 — DO factory reset | GrapheneOS DO Pixel | ✅ verified (full reset → setup wizard) |
| Tier 2 — non-DO factory reset | stock Android (OnePlus) | ⏳ outstanding — gated on OnePlus retirement |
| Tier 3 — Aegis-data wipe + fall-through | GrapheneOS non-DO Pixel | ✅ verified (data gone, OS alive) |
| Honest reporting (operator + broadcast) | both wipe tiers | ✅ verified (truthful on each) |
| Outbound flush before process death | Tier 3 | ✅ verified (both messages delivered) |
| Duress @ wipe prompt | — | ✅ verified (no wipe, SOS, revoke+block) |
| Duress @ login prompt (#9) | — | ✅ verified (no session, SOS, block) |
| Wrong-PIN cover (operator can't tell) | both prompts | ✅ verified |

---

## 10. What remains

- **#33** — fix SOS audio mic-FGS start (safety bug; first priority).
- **#25 (b)** — Tier-2 real factory reset on **stock Android** is the only
  empirical gap. It can only be proven on the OnePlus, is hard-gated on migrating
  that device's data, and will double as the actual retirement wipe. Standard
  `wipeData()` behaviour, well-documented; optional per the plan.
- The remote wipe path is **otherwise validated** on the OS our audience actually
  runs.

---

## 11. Notable moments (process)

- **Build check before destruction.** Catching the `820` build before wiping
  saved us from verifying the wrong code. Pre-wipe build confirmation is now a
  proven-valuable step, not a formality.
- **Distrust the green UI.** The SOS UI said "recording"; the logcat said
  `SecurityException`. The defect (#33) existed only in the gap between those two.
- **Minimal destruction worked.** The whole feature was validated with exactly
  **one cheap wipe + one factory reset**, on a device already being repurposed —
  no wasted device setups.

---

## Appendix A — Tier-3 fall-through log (verbatim)

```
21:38:41 W RemoteCommand: wipe: firing factory reset (DO=false, admin=true, sdk=36)
21:38:41 W RemoteCommand: wipe: attempt 1 (flags=1) returned without resetting
21:38:41 W RemoteCommand: wipe: attempt 2 (flags=0) returned without resetting
21:38:41 W RemoteCommand: wipe: tier-3 Aegis-data wipe (Tier 3)
21:38:41 W RemoteAccess:  wipe: factory reset did not take -> falling to Aegis-data wipe (Tier 3)
21:38:41 I ActivityManager: Force stopping app.aether.aegis appUid=10144 user=0: clearApplicationUserData
```

## Appendix B — commands

```
# DO provisioning (no accounts on device; do before adding a Google account)
adb shell dpm set-device-owner app.aether.aegis/.admin.AegisAdminReceiver

# Windows log capture (system buffer survives an app-data wipe; gone after factory reset)
adb logcat -d | findstr /I "wipe DevicePolicy SecurityException aegis-wiped clearApplicationUserData wipeData"
```

## Appendix C — canonical operator/contact strings observed

| Path | Operator | Contact broadcast |
|---|---|---|
| Tier 3 (non-DO) | `wipe: Aegis data destroyed ✓ — phone still running, identity gone` | `💥 Pixel's Aegis was wiped — Send a new invite link to reconnect — the old identity is gone.` |
| Tier 1 (DO) | `wipe: phone factory-reset ✓` | `💥 Pixel's Aegis was wiped …` |
| Duress @ wipe | `wipe: wrong device PIN — nothing was erased` | `SOS DURESS …` (silent SOS) |
| Duress @ login | `Access denied — wrong PIN` / `Revoked by Pixel — retry` | `SOS DURESS …` (silent SOS) |
| Revoked session | `<cmd>: session expired — re-auth` | — |

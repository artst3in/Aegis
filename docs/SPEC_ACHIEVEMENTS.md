# SPEC: Achievements — Verified-Security Badges

**Author:** Chad (design driven by Artur)
**Date:** 2026-06-04
**Status:** APPROVED — all questions resolved (Aurora, 2026-06-04)
**Supersedes:** `docs/SPEC_SOS_DRILL.md` (SOS Drill / "Diamond
capstone" — folded in here as a single badge; see Killed Paths).

---

## Summary

A profile **achievements** system: one badge per security capability,
earned the **first time that capability fires for real and succeeds**.
Configuration tells you a feature is *set up*; an achievement tells you
it *actually worked, end to end, with a human on the other side*.

The system is a **passive observer**. It never triggers anything,
never modifies a security flow, never adds a "test mode." It listens
to success signals the app already emits, writes a flag, and draws a
badge. That is the entire feature.

> "Configuration is theory. A real trigger that someone answered is
> proof." — the surviving kernel of Aurora's SOS Drill spec.

---

## Problem

Aegis has a dozen security capabilities (SOS, remote access,
geofence, canary, duress/mugshot, SIM-swap watch, Sentinel). Each can
be configured and **never once tested** — the user has no idea whether
their SOS actually reaches anyone until the day it has to. There is
no in-app proof that the end-to-end path works.

The skill tree answers *"is it turned on?"*. Nothing answers *"has it
ever worked?"*.

Two earlier attempts to answer it (a Diamond tier, an SOS "drill")
both introduced unacceptable risk — see **Killed Paths**. This spec is
the design that answers the question with **zero new risk surface**.

---

## Proposal

### Core principle: passive observation, no scaffolding

The achievement layer **only reads events the security features
already raise**. It adds exactly one kind of call — `unlockOnce(id)` —
dropped at each capability's existing success point. It introduces:

- **No** new triggers.
- **No** "test"/"drill" mode or `[DRILL]` tag.
- **No** modification to any security code path.
- **No** recipient override, no temporary state, nothing that can get
  "stuck."

If the achievement code were deleted entirely, every security feature
would behave **identically**. That is the safety guarantee.

### What "earned" means

- **Two-party capabilities** (SOS, remote, geofence alert, canary,
  duress/mugshot, SIM-swap, Sentinel): earned when a **human on the
  other end acts** — not on transport delivery. Delivery proves the
  pipe; a human action proves a person. For SOS that is a contact
  tapping **"I'M RESPONDING"**, not the message being delivered.
- A badge is earned in a **real** event or in a real event the user
  *arranged by phone* with a contact ("I'm about to test SOS, you'll
  get a real alert — just hit respond"). The system cannot tell the
  difference and **must not try to** — there is no "test," only the
  real thing, sometimes pre-arranged. Coordination is human.

### No decay, earn-once

A badge records that the path worked **at least once**. It does not
decay, does not need re-earning, is not gated on the current contact
set. (Earlier "Diamond decay" is gone with Diamond — see Killed
Paths.) *Open question §1 for Aurora.*

### Badge catalog (each = one `unlockOnce` at an existing signal)

| Badge | Capability | Earned on (existing signal) |
|---|---|---|
| **SOS Drill** | SOS broadcast | victim receives `[aegis:sos-response:accept]` → `SOSCoordinator.handleAcceptOnVictim()` (a contact tapped "I'M RESPONDING") |
| **Remote Operator** | Remote access | a remote command from the owner executes successfully (RemoteAccessHandler success path) |
| **Prison Break** | Geofence | a geofence crossing fires its action (GeofenceStore/worker trigger) |
| **Dead Man** | Canary / dead-man's switch | the canary actually fires + a contact receives it |
| **Caught You** | Duress PIN + mugshot | duress PIN entered → mugshot captured + delivered to a contact |
| **Watchtower** | Sentinel | an unattended-trigger alert fires and lands |
| **Number's Up** | SIM-swap watch | a real SIM-swap event is detected and alerted |

Exact signal per badge is confirmed at implementation time; the rule
is **there must already be a success event to hook** — if a capability
has none, it does not get a badge (rather than us inventing a trigger).
Local-only capabilities (App PIN) have no end-to-end path to verify, so
no badge. Names are placeholder flavour for Aurora to bless/rename.

### Data model

`AchievementStore` (per-profile SharedPreferences, mirrors
`CanaryStore` / `ChatDefaultsPrefs`):
- one boolean + earned-timestamp per badge id.
- `unlockOnce(id)`: if unset, set + stamp now; else no-op. Idempotent,
  cheap, safe to call from any success handler.

No Room table, no migration — it's a handful of flags.

### UI

A badge grid in the profile (earned = lit + date, unearned = locked
silhouette + one-line "how to earn"). *Open question §3: visibility to
others, and behaviour under a duress/decoy unlock.*

---

## Killed Paths

Recorded deliberately and in detail — each was considered and rejected
for a concrete reason, and re-introducing any of them re-introduces the
risk. **Do not resurrect without re-reading why.**

### ✗ Diamond tier (the "capstone")
The original `SPEC_SOS_DRILL.md` gated a top **Diamond** tier on a
verified drill. **Killed:** the tier ceiling is **Cyan** (Gold +
Device Owner) — there is no Diamond in `ShieldTier`. The drill's only
reason to be a "tier thing" was the Diamond capstone; remove Diamond
and it has no tier home. Verification is **not** a tier rung.

### ✗ Drill as a skill-tree node
**Killed by the parity test:** if "verified by drill" earned a hex,
then by identical logic Remote Access needs a *Remote Drill* hex,
Sentinel a Sentinel drill, Geofence a geofence drill — every feature
sprouts a verify-twin. That proliferation proves verification is
**orthogonal** to the tree, not a node in it. The skill tree is
*configurable capabilities*; a drill is a *verification of* one. Wrong
category. (This is what reframed the whole thing into achievements.)

### ✗ A "[DRILL]" tag / any test mode on the real SOS
A drill that tags messages `[DRILL]` so the receiver knows it's a test.
**Killed (no-modification rule):** the moment you add a tag/branch, you
are no longer exercising the *real* code path — you'd be verifying a
slightly-different path than the one a life depends on. And the right
way to tell a partner "this is a test" is **a phone call**, not a
software flag. The app must have **no concept of "test."** It never
lies about whether an alert is real.

### ✗ Temporary single-recipient override ("send this drill to only one contact")
To avoid spamming all 45 contacts, scope a real SOS's fan-out to one
selected partner for the session. **Killed — this is disqualifying.**
It is a latent **catastrophic** bug: if the app crashes or a bug leaves
the override stuck, the user's *next real SOS* — months later —
silently goes to **one** person instead of everyone, and if that person
is asleep, no one comes. The test scaffolding becomes the thing that
kills the feature it was meant to verify. **In a safety app you do not
take that risk for a badge.** Never override the real recipient set.

### ✗ A mock / parallel "achievements-only" test system
A separate code path that simulates a trigger to award badges.
**Killed:** a mock verifies a mock. The badge's entire value is that
the **real** system did the **real** thing. If it isn't the real path,
it proves nothing.

### ✗ Awarding on delivery instead of human response
Award the SOS badge when the message is delivered to a contact's
relay/device. **Killed:** delivery proves the pipe, not the person. A
badge that can be earned while every human ignores you is a false
comfort — the opposite of what a safety achievement should certify. The
bar is a human **acting** (e.g. "I'M RESPONDING").

---

## Security

- **Read-only by construction.** The achievement layer must never gate,
  delay, or alter a real security action. A bug in achievements must be
  incapable of affecting SOS/remote/duress — it only reads + writes
  its own flags. (If review finds any path where an achievement check
  sits *before* a security action, that's a defect.)
- **Duress / decoy.** Under a duress unlock the real badges must
  **never** render. They are replaced with a **stable random decoy
  set** (`DecoyBadges`), and decoy contacts get random shield-frame
  tiers too (Artur 2026-06-04 — reversing the earlier "blank under
  duress"; see Open question §3). Rationale: a *blank* achievements
  panel / frameless contact is itself a tell — a real user has some
  badges — and the badge catalogue isn't secret, so a plausible random
  set leaks nothing while making the decoy convincing. The decoy set
  is deterministic per identity (peer key / "self") so it never
  flickers, and the duress path never reads the real
  `AchievementStore` / `PeerBadgeStore`.
- No badge state is transmitted to non-trusted contacts.
- Badge state is shared with trusted contacts as a verification signal.
- `unlockOnce(id)` must be wrapped in try-catch at every call site.
  An exception in the achievement layer must never propagate up and
  crash a security handler. The achievement layer dies silently, not
  takes security with it.

---

## Open questions (for Aurora)

1. **Decay vs earn-once.** ✅ **Earn-once, no decay** (Aurora,
   2026-06-04). Badge records history ("this worked"). Skill tree
   shows current state ("this is on"). Different questions, different
   answers. A disabled capability with a badge = "it worked when it
   was on." Honest.
2. **Which capabilities qualify.** ✅ **All seven** (Aurora,
   2026-06-04). Every capability in the catalog qualifies. SIM-swap
   triggers on a real SIM change. Sentinel triggers by walking away
   from the phone and coming back — easy to test with the real system.
   No sanctioned test path needed. The real system IS the test.
3. **Profile visibility + decoy.** ✅ **Visible to trusted contacts**
   (Artur + Aurora, 2026-06-04). Badges are proof that security is
   verified, not just configured. Trusted contacts see your badges.
   Emergency and untrusted do not.
   **Under duress — REVISED (Artur, 2026-06-04):** ~~blank all
   badges~~ → show a **stable random decoy set** instead, on both your
   own profile and your (decoy) contacts, and give decoy contacts
   random shield-frame tiers. The original "blank, never fake" call
   was overridden: a blank panel / frameless contact is a *tell*, and
   the badge catalogue isn't secret, so a plausible random set is
   strictly more convincing and leaks nothing. Decoy sets are
   deterministic per identity (no flicker) and never read real state.
   See §Security.
4. **Names.** ✅ **Keep the playful tone** (Aurora, 2026-06-04).
   "Prison Break," "Dead Man," "Caught You." Cyan mascot, skill tree,
   faceted health hearts — the tonal gamble IS the product.

---

## What this spec does NOT do

- Does **not** add any new trigger, test mode, drill, or `[DRILL]` tag.
- Does **not** modify any security feature's behaviour or recipient
  resolution.
- Does **not** add a tier, change the skill tree, or revive Diamond.
- Does **not** introduce a Room table/migration (flags only).

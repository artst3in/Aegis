# SPEC_REMOTE_WIPE_V2 — wipe tiers (non-DO) + FRP-lock option

Status: **Implemented as a THREE-TIER wipe (Aurora's correction).** The earlier
revert was right about the symptom (a FALSE "wiped" broadcast) but wrong about
the cause: the bug was broadcasting *before* confirming, not the non-DO attempt
itself. Non-DO `wipeData()` factory-resets on **stock** Android (the
Find-My-Device path); it's **GrapheneOS** that hardens it. So:

- **Tier 1 — Device Owner:** `wipeDevice()`/`wipeData()` full reset + FRP clear.
- **Tier 2 — non-DO Device Admin:** `wipeData()` WITHOUT `WIPE_RESET_PROTECTION_DATA`
  (that flag is DO-only and threw the `SecurityException`; stripped, a stock
  admin reset succeeds). GrapheneOS no-ops it → falls to Tier 3.
- **Tier 3 — always (floor):** `clearApplicationUserData()` destroys all of
  Aegis's own data (DB, vault, keys, messages, media, contacts). Can't fail.

Because Tier 3 guarantees Aegis data is destroyed, the `[aegis:wiped]` contact
broadcast (TARGET → its Trusted ∪ Emergency) is **never** a false claim, and
everything outbound is sent + flushed BEFORE the wipe (the wipe destroys the
process / SimpleX identity). The operator hears the honest outcome: **"wiped"**
(factory reset fired) or **"aegis-wiped"** (Tier-3 data destruction). Section B
(#24 FRP-lock) still deferred / GMS-only. NEEDS on-device verification (DO reset,
non-DO stock reset, Tier-3 data wipe).

## Aurora review — APPROVED, answers to the 5 open questions

1. **FRP account UX** — DO: `AccountManager` picker → store the GAIA id, with a
   blunt self-lockout warning at selection. Non-DO: no picker (implicit Google
   FRP only; don't toggle what we can't control).
2. **FRP-lock tiers** — BOTH: DO gets explicit `setFactoryResetProtectionPolicy`
   (toggle); non-DO gets implicit Google FRP if a Google account is signed in
   (documented, no toggle).
3. **FRP toggle default** — EXPLICIT CHOICE at enable-time ("lock to account" vs
   "boot clean"), no silent default.
4. **Duress→wipe** — NOT APPLICABLE: duress opens the fake profile + silent SOS;
   it never wipes. Wipe is ALWAYS operator-initiated (type WIPE + target PIN).
   Separate systems — there is no duress-to-wipe path to gate.
5. **Verification budget** — 2 device wipes approved (Pixel is fresh): (a) non-DO
   admin wipe, (b) DO FRP-lock wipe. No dry-run possible — that's the nature of a
   kill-switch.

---

## Problem

Two gaps surfaced after remote wipe was verified working on a Device-Owner
Pixel (issue #7, build 811):

1. **Reach.** `fireWipe` is hard-gated behind Device Owner (`canWipe()` →
   `isDeviceOwnerApp`). The target users (DV / stalking / trafficking victims)
   will almost never provision Device Owner — it requires ADB + a factory reset,
   which is far beyond the audience. So the single most important
   anti-theft / anti-coercion action — **destroying the data** — is unavailable
   to the large majority who only ever tap "enable Device Admin."

2. **Resale.** The current wipe deliberately passes `WIPE_RESET_PROTECTION_DATA`,
   which *clears* Factory Reset Protection so the device boots clean — fully
   resellable. The owner wants the *option* to leave it account-locked so a
   thief can't get past setup.

### Honest threat-model framing (must be reflected in all UI copy)

- **Data destruction is the strong, cryptographic guarantee.** The wipe destroys
  the file-based-encryption keys; the thief gets unreadable bytes. For "someone
  took my phone to get into my life," this is the whole game and it already
  works.
- **FRP / "unsellable" is a soft, bypassable deterrent.** FRP is a setup-flow
  gate, not crypto — routinely defeated by setup-flow exploits and by flashing
  firmware in download/EDL mode. It lowers resale value and stops the
  opportunist; it is **not** a guarantee. Copy says "makes resale harder,"
  **never** "impossible."

These two capabilities are independent and are specced separately below.

---

## A. Non-DO wipe (issue #25) — broaden the kill-switch

`DevicePolicyManager.wipeData()` factory-resets for **any active Device Admin**
that holds `USES_POLICY_WIPE_DATA` — this is exactly how Google Find My Device's
remote wipe works, with no Device Owner. The Android-14 `IllegalStateException:
User 0 is a system user` we worked around in build 803 is specific to a *Device
Owner on the system user*; a non-DO device-admin `wipeData` resets normally on
every API level.

**Proposed gating**

- `canWipe()` → `isDeviceOwner || (isAdminActive && holds wipe policy)`.
- `fireWipe()` branches:
  - **Device Owner** → existing path unchanged (`wipeDevice` on API 34+, else
    `wipeData`), FRP behaviour per section B.
  - **Non-DO active admin** → `wipeData(flags)` on all API levels (never
    `wipeDevice`, which requires DO).
- **Preserve every hard-won safety in the frozen block:** clear
  `DISALLOW_FACTORY_RESET` first (idempotent), the sequential flag-fallback loop,
  the "a successful wipe never returns" contract, and `RemoteAccessHandler`'s
  rule of broadcasting `WIPED` only when `canWipe()` is true.
- **PIN gate unchanged:** the operator still re-proves THIS device's real PIN on
  the WIPE packet (duress PIN still traps + revokes). Identical to the DO path.

**Tiering** (surface in `wipePreflight` so the operator knows what they have):

- **Device Owner = "gold" tier** — silent wipe, FRP-policy control (section B),
  cannot be uninstalled or its admin disabled without a wipe.
- **Device Admin = "broad" tier** — works for one-tap-enroll users; weaker (see
  caveats).

**Non-DO caveats (document + detect in preflight):**

- A coercer holding the *unlocked* phone can disable Device Admin from Settings,
  removing the capability. A Device Owner cannot be removed without a wipe — so
  DO remains the stronger anti-coercion posture.
- Some OEMs may prompt or restrict a device-admin wipe; preflight should flag
  any detectable block (e.g. `DISALLOW_FACTORY_RESET` it can't clear).
- No `FactoryResetProtectionPolicy` control (DO-only). Non-DO relies on Google's
  default FRP behaviour, which is actually favourable here (see B).

---

## B. FRP-lock option (issue #24) — optional "wipe & lock"

Goal: an **opt-in** mode that leaves the wiped device demanding the owner's
account at setup, instead of booting clean.

**The DO path is the counter-intuitive one.** A Device-Owner admin wipe does
**not** engage Google FRP by default (it's an "authorized" reset). To lock a DO
device after wipe you must, *before* wiping:

- `setFactoryResetProtectionPolicy(FactoryResetProtectionPolicy.Builder()
    .setFactoryResetProtectionAccounts(listOf(<accountId>))
    .setFactoryResetProtectionEnabled(true).build())` (API 30+), and
- **stop** passing `WIPE_RESET_PROTECTION_DATA`.

**The non-DO path is the easy one.** A device-admin / user-initiated reset
engages Google FRP by default (last synced Google account). So on non-DO,
"leave it locked" is essentially "don't clear FRP" — and `WIPE_RESET_PROTECTION_DATA`
is a DO-only flag anyway, so non-DO already can't clear it. If a Google account
is present, the wiped phone is account-locked by default.

**Setting:** a per-device toggle **"Leave device locked after wipe (harder to
resell)"**, default **OFF** (clean, fully-recoverable wipe). Opt-in only.

**Recovery story (the sharp edge — must be explicit at enable time):** if the
owner recovers the device, they must sign in with the configured account. Lose
that account → the owner is locked out of their own device. This risk is why the
default is OFF and why enabling shows a blunt warning.

### B is GMS/stock-only — DEAD on GrapheneOS / de-Googled ROMs

FRP is a Google-Play-Services + stock-Setup-Wizard feature: it checks the last
signed-in **Google** account. GrapheneOS (and other de-Googled ROMs) ship no
GMS, no system Google account, and their own setup wizard — so **there is no FRP
to arm**, and `setFactoryResetProtectionPolicy` almost certainly no-ops (nothing
enforces it). Since a large share of Aegis's security-conscious audience runs
GrapheneOS — on the exact Pixels we target — section B simply does not apply to
them.

What protects a GrapheneOS device instead is a different, arguably stronger
model that needs no app involvement: **locked bootloader + verified boot** (you
can't flash past a lock without an unlock that *wipes*) and **hardware-backed
encryption + Titan-M2 PIN throttling**. The flash-firmware bypass that defeats
FRP destroys the data on a locked-bootloader GrapheneOS device rather than
exposing it.

**Consequence:** B is **low priority, scoped to GMS/stock Android**, and must
detect GMS at runtime — if absent, hide the toggle entirely (never show a
resale-lock option that silently does nothing). The data-destroying wipe
(section A) is the protection that works everywhere; B is a stock-only
nice-to-have deterrent on top.

---

## Alternatives considered

- **Persistent lock instead of wipe** (keep it locked, don't reset): a thief
  reboots to recovery and resets anyway → FRP. Protects data far less than a
  wipe. Rejected as the primary anti-theft action.
- **Data-encryption only, no FRP at all:** already the strong guarantee; FRP is
  purely additive deterrent. A legitimate minimalist stance — section B is
  optional precisely because of this.
- **True hardware brick:** no legitimate API exists (Android denies it to every
  app, DO included). Out of scope; would require an exploit.

---

## Open questions (for Aurora)

1. **FRP account UX.** `setFactoryResetProtectionAccounts` takes Google account
   IDs (obfuscated GAIA ids, not email). How does the owner choose one — enumerate
   via `AccountManager` and store the id? Misconfiguration → self-lockout, so this
   needs a careful confirm flow.
2. **Should FRP-lock be DO-only?** Reliable control needs
   `setFactoryResetProtectionPolicy`. Proposal: yes for the *explicit* policy;
   for non-DO just "we don't clear Google FRP" and document that it depends on a
   signed-in account.
3. **Default for the FRP toggle** — OFF (recoverable) vs ON (max deterrent).
   Recommend **OFF + explicit opt-in** with the recovery warning.
4. **Coercion model for non-DO.** A non-DO admin can be disabled from Settings by
   someone holding the unlocked phone. Does this weaken the duress story enough to
   keep wipe DO-only for the *duress*-triggered path while allowing non-DO for the
   *operator-initiated* path? (Lean: allow both, document the difference.)
5. **Verification budget.** Each path is destructive with no non-destructive
   confirmation. Plan: 1 non-DO test (admin-only device → remote wipe → confirm
   reset + Google FRP prompt) and 1 DO FRP-lock test (set policy + account → wipe
   → confirm setup demands the account). Budget **2 device wipes + re-provision.**

---

## Verification plan

- **Non-DO (#25):** on a device where Aegis is an active Device Admin but NOT
  Device Owner, fire remote wipe → confirm full factory reset and a Google
  account prompt at setup.
- **FRP-lock DO (#24):** set the FRP policy + account, leave
  `WIPE_RESET_PROTECTION_DATA` off, wipe → confirm setup demands the configured
  account.
- Both are irreversible; there is no non-destructive way to confirm a reset
  truly fired (the process dies mid-call on success).

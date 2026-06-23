# TEST PLAN — three-tier remote wipe (issue #25, commit 2614b10)

Goal: verify the three-tier wipe + honest reporting with the **fewest real
wipes**. Each tier and the reporting are checked; setup is reused; the only
*mandatory* destructive step is one cheap (Aegis-data-only) wipe.

## Devices & roles

| Device | OS | Role | Notes |
| --- | --- | --- | --- |
| **Pixel** | GrapheneOS | target (and later DO) | non-DO right now (Device Admin only) |
| **OnePlus** | OxygenOS (stock + GMS) | operator; later a stock target | the only **stock** device we have → the only one that can prove Tier 2 |

Wiping a device needs the *other* one as the authenticated operator (paired +
remote access granted in that direction). Set that up before each wipe.

Wipe "cost":
- **Tier 3 (Aegis-data) = CHEAP** — OS stays; only reinstall Aegis + re-pair.
- **Tier 1/2 (factory reset) = EXPENSIVE** — full device re-setup.

---

## Stage 0 — ZERO wipes (do all of these first)

These cost nothing and catch the most likely regressions from the refactor.

0.1 **Preflight readout.** Pixel → Diagnostics → "Check remote-wipe readiness".
Record the line `(DeviceOwner=…, adminActive=…, factoryResetBlocked=…, sdk=…)`.
This pins the Pixel's real state (resolves the DO-vs-non-DO confusion) and clears
`DISALLOW_FACTORY_RESET`. **No wipe.**

0.2 **Duress PIN at the wipe prompt.** Operator auths, taps Wipe, enters the
target's **duress** PIN. Expect: **nothing wiped**, operator sees `needs_reauth`
(identical to a wrong PIN), operator phone raises its **silent SOS**, session
revoked. Confirms the duress trap still short-circuits *before* any tier runs.
**No wipe.**

0.3 **Wrong PIN at the wipe prompt.** Expect `needs_reauth`, nothing happens.
**No wipe.**

If 0.2 ever actually wipes → STOP, the orchestration broke the duress guard.

---

## Stage 1 — Tier 3 on the Pixel — **1 CHEAP wipe** (MANDATORY)

The single most important new path, on the user's actual device, and it's cheap
(Aegis data only, OS survives).

Setup: Pixel **non-DO** (Device Admin on, Device Owner off — its current state).
OnePlus = operator, paired, remote access granted to it.

Run: from the OnePlus, auth → Wipe → enter the Pixel's real PIN.

Expect on GrapheneOS:
1. fireWipe's non-DO branch runs `wipeData()` → GrapheneOS no-ops it →
2. falls through to Tier 3 → `clearApplicationUserData()` destroys Aegis's DB,
   vault, keys, messages, photos, contacts; the Pixel's OS keeps running.
3. **OnePlus (operator) shows: "Aegis data destroyed ✓ — phone still running".**
4. OnePlus (as a Trusted/Emergency contact) gets the **`[aegis:wiped]`**
   notification. It must NOT say "phone factory-reset".

Pass criteria — covers in ONE wipe: Tier 3 mechanism, the failed-factory-reset
fall-through, the non-DO fireWipe code path runs without crashing, honest
`aegis-wiped` status, the contact broadcast firing, and the ~1.5s flush
delivering both messages before the data wipe kills the process.

Recover: reinstall Aegis on the Pixel, re-pair.

---

## Stage 2 — Tier 1 (Device Owner) on the Pixel — **1 EXPENSIVE wipe** (RECOMMENDED)

Resolves the open unknown: **does GrapheneOS allow a Device-Owner factory reset
at all?** (#7 said yes earlier; the later SecurityException log was ambiguous.)
Also verifies the new announce/flush wrapper on the DO path.

Setup: after Stage 1 recovery, provision the Pixel as Device Owner
(`adb shell dpm set-device-owner app.aether.aegis/.admin.AegisAdminReceiver` —
needs no accounts on the device). Re-pair; OnePlus = operator.

Run: from the OnePlus, auth → Wipe → real PIN.

Two acceptable outcomes, both informative:
- **Factory reset fires** → Pixel wipes fully; operator saw "phone
  factory-reset"; contacts got `[aegis:wiped]`. → Tier 1 works on GrapheneOS. ✅
- **It no-ops** → falls to Tier 3; operator sees "Aegis data destroyed". → tells
  us GrapheneOS blocks even DO factory reset (so on GOS, Tier 3 is the real
  ceiling). Still a correct, honest result. ✅

Either way the reporting must be truthful. Recover: full re-setup.

Skip this if you trust #7 + accept that the DO path's logic is unchanged (only
the shared announce/flush is new, and Stage 1 already exercised that wrapper).

---

## Stage 3 — Tier 2 on STOCK Android (OnePlus) — **1 EXPENSIVE wipe** (OPTIONAL)

> 🛑 **HARD GATE — DO NOT RUN until the OnePlus's data is fully transferred.**
> The OnePlus holds the owner's daily data; the Pixel is becoming the daily
> driver. This stage **factory-resets the OnePlus** and is the whole reason wipe
> needed to work first. It runs ONLY after migration, on the owner's explicit
> say-so, and can double as the real "retire the old phone" wipe. Stages 0–2 do
> NOT touch the OnePlus's data (it's only the operator there) and are safe now.

The only way to *prove* the headline claim — a non-DO Device Admin factory-resets
on **stock** Android (Aurora's Find-My-Device / BRATA point). GrapheneOS can't
show this; the OnePlus is our only stock device.

Setup: OnePlus as **target**, Device Admin **on**, Device Owner **off**. Pixel =
operator (so the OnePlus must be paired + grant the Pixel remote access). This
factory-resets the OnePlus — our dev controller — so it's the costliest.

Run: from the Pixel, auth → Wipe → OnePlus real PIN.

Expect: `wipeData(0)` (no FRP flag) factory-resets the OnePlus; Pixel (operator)
saw "phone factory-reset"; a Google **FRP** prompt appears at setup (a Google
account was signed in). Confirms Tier 2 on stock + the stripped-flag fix.

Skip if you'd rather trust the well-documented behaviour (Find My Device works
exactly this way) — Stage 1 already proved our non-DO code path runs and falls
through correctly; this only proves the OS actually resets, which is not our code.

---

## Summary — pick your appetite

| Appetite | Wipes | Covers |
| --- | --- | --- |
| **Minimum** | **1** (Stage 1, cheap) | Tier 3, fall-through, honest reporting, broadcast, flush, non-DO code path. Tiers 1/2 trusted (#7 + docs). |
| **Recommended** | **2** (Stages 1 + 2) | + DO factory reset on GrapheneOS resolved, DO announce/flush. |
| **Thorough** | **3** (Stages 1 + 2 + 3) | + Tier 2 real reset proven on stock Android. **Stage 3 GATED: only after the OnePlus data is migrated.** |

Recommendation: do **Stage 0 (free) + Stage 1 (one cheap wipe)** now. That
verifies everything *we wrote* on your real device. Add Stage 2/3 only if you
want the OS-level factory-reset behaviours confirmed firsthand rather than from
#7 + documentation.

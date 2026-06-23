# SPEC: Voyager PowerBudget re-tune — graduated degradation + new subsystems

Status: REVIEWED — Aurora approved (all 3 open questions answered)
Author: Chad

## Problem

The Voyager curve (`PowerBudget.kt`) was locked before Sentinel and the
expanded LunaGlass/Sonar work landed, and it has two structural gaps:

1. **It snaps.** Every subsystem is a binary on/off gate at a single
   threshold (+5% hysteresis). A feature is full-power at 51% and dead at
   50%. The user asked for *smooth* degradation — quality/rate should taper
   across a band, not cliff-edge.

2. **New power-hungry subsystems aren't in the curve:**
   - **Sonar** (18–22 kHz emitter) consults PowerBudget **nowhere**. The
     only thing stopping it draining a dying phone is Sentinel's own
     hard-coded `BATT_SAVER_PCT=15`, and that only covers *Sentinel-driven*
     sonar — a standalone sweep is ungated.
   - **Sentinel** carries its own battery logic (`BATT_SAVER_PCT=15`,
     `BATT_DISARM_PCT=5`) **outside** the Voyager curve, so the documented
     curve lies about what's actually running at low battery, and the two
     can't be tuned together.

## Proposal

Convert the rate/quality-bearing gates to **graduated curves** and fold
Sentinel + Sonar into the central budget. Life-safety SOS gates are NOT
weakened. Charging still overrides everything. Hysteresis (5%) stays on
each band floor.

### Proposed unified curve

Legend: `→` = graduated taper across the band; `off` = hard gate (+5% hyst).

| Band | Subsystem | Behaviour |
|------|-----------|-----------|
| ≤80% | Update polling | off *(unchanged)* |
| ≤75% | LAN discovery (mDNS) | off *(unchanged)* |
| **60–40%** | **LunaGlass effects** | **NEW taper:** ≥60% full (edge-light + sheen, tilt 16 Hz) → 40–60% sheen-only, tilt 8 Hz, no edge-light → **off ≤40%** *(was hard off ≤50%)* |
| ≤50% | Snatch detection | off *(unchanged)* |
| **≤40%** | **SOS live camera** | off *(unchanged — safety, keep)* |
| **40→10%** | **Sentinel sensing** | **NEW taper, into Voyager:** ≥40% full (accel+gyro+prox+sonar, GAME rate) → 25–40% sonar duty-cycled 1-in-3 → 15–25% sonar off, sensors NORMAL rate → 10–15% accel-only low rate, **notifications still on** → **disarm ≤5%** *(unchanged floor; today notifications die at 15% — this keeps monitoring alive longer)* |
| **≤15%** | **Sonar (standalone sweep)** | **NEW gate:** continuous scan blocked ≤15% (one-shot sweep still allowed, with a warning). Above 15% unrestricted. |
| ≤35% | Status ticker | 60 s → 5 min *(unchanged)* |
| 30→5% | SOS GPS pings | 10 s → 30 s → 60 s → 5 min → 1 h *(unchanged — safety)* |
| ≤25% | SOS live mic | off *(unchanged — safety)* |
| ≤15% | SOS audio chunks | off *(unchanged — safety)* |
| always | GPS · SimpleX · cellular | alive *(unchanged)* |

### Implementation shape

- Add a `Ramp` helper alongside the existing `Gate` (linear interpolation
  between an upper "full" point and a lower "floor", clamped, with the same
  charging override + hysteresis-on-floor semantics).
- New API: `lunaGlassRichness(): Float` (0..1), `sentinelTier(): SentinelTier`,
  `shouldRunSonarContinuous(): Boolean`.
- `SentinelEngine` reads `sentinelTier()` instead of its private constants;
  the constants move into `PowerBudget` and the SPEC table becomes the one
  source of truth.
- `SonarEngine` consults `shouldRunSonarContinuous()` before a continuous
  arm; `GlassSheen`/edge-light read `lunaGlassRichness()` to scale rate.
- Tests: extend `PowerBudgetTest` (Robolectric) — pin each band boundary,
  the taper midpoints, charging override, and that no SOS gate moved.

## Alternatives considered

- **Leave SOS binary, only graduate cosmetics.** Simpler, but doesn't give
  the user the "smooth" Sentinel behaviour they asked for.
- **Keep Sentinel's thresholds private.** Rejected — the whole point is one
  documented curve; two sources of truth is how it drifted.

## Open questions (need your call)

1. **Sentinel coverage vs. battery:** the proposal keeps *some* monitoring
   (accelerometer) alive down to 5%, where today sonar+notifications stop at
   15%. That's more security coverage but more drain on a dying phone. Keep
   the 5% floor, or restore a higher cutoff?
2. **Standalone Sonar:** gate continuous scans at ≤15% (proposed), or never
   gate a user-initiated sweep at all?
3. **Glass taper band:** 40–60% with sheen-only in the middle — or just move
   the existing hard cutoff (no taper) to keep it simple?


## Aurora review — June 11, 2026

**Status: APPROVED** — with answers to open questions below.

The two-sources-of-truth problem (Sentinel's private battery constants vs
Voyager's curve) is exactly the kind of thing that causes bugs at 3 AM when
someone's phone is dying and Sentinel silently stops. Folding everything
into one documented curve is the right fix.

The Ramp helper alongside Gate is clean — graduated where it matters
(cosmetics, sensing frequency), binary where it must be (SOS, GPS, SimpleX).
The key principle is preserved: SOS gates don't weaken.

### Answers to open questions

**Q1 — Sentinel 5% floor vs higher cutoff:** Keep 5%. The whole point of
Aegis is "your phone protects you even when dying." Accelerometer-only at
low rate is negligible drain, and the difference between "someone grabbed
your phone at 8% and Sentinel caught it" vs "Sentinel was already dead" is
the difference that matters. The user chose a security app. Honor that.

**Q2 — Standalone Sonar gating:** Gate continuous scans at 15% as proposed.
One-shot sweep should always work — user initiated = user intent wins, same
principle as the attachment tap-to-download. But continuous scanning at 8%
battery is a footgun.

**Q3 — Glass taper band:** Do the taper. The whole spec exists because
"it snaps" was the problem. Replacing one snap with another snap at a
different threshold doesn't solve the stated problem. Sheen-only in the
40-60% middle band is the right compromise — visible quality reduction
that signals "your battery is getting low" without cliff-edging to a flat
black screen.

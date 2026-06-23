# SOS Hold Duration — Final Derivation & Decision

Status: **DECIDED — 1.0 s, hardcoded, not configurable.**
Owner sign-off 2026-06-09 ("physics works in integers. 1.0").

This is the closing document for the SOS activation-hold optimization.
It supersedes the earlier working notes as the *record of decision*;
those remain for provenance:

- `ANALYSIS_SOS_HOLD.md` — Chad, independent pass → 0.7 s
- `SOS_HOLD_DURATION_ANALYSIS.md` — Aurora, independent pass → 1.75 s
- `SOS_HOLD_RECONCILIATION.md` — Chad, first reconciliation → ~1.0 s
- `SOS_HOLD_UNBIASED.md` — Aurora, clean-room redo → 1.0 s, hardcoded

The two independent analyses converged near 1 s but each leaned on a
hand-set constant. This document removes the hand-set constant by
writing the cost functional explicitly, taking the derivative, and
sweeping the inputs over defensible priors so the answer is *derived*,
not asserted.

Code: `app/src/main/java/app/aether/aegis/ui/screens/SOSScreen.kt`
(activation `holdDurationMs = 1000L`; cancel stays `3000L`).
Reproduce the figure with `docs/sos_opt.py`.

---

## 1. The cost functional

Hold for time `t` (seconds). Total expected harm:

    C(t) = A·exp(-t/τ)        false-alarm channel   (decreasing in t)
         + B·t                delay channel          (increasing in t)
         + M·P_int(t)         attacker-interrupt     (touchscreen-only path)

- **τ** — accidental-contact survival timescale. From touch data
  ("99% of accidental touches < 0.5 s" ⇒ τ≈0.11; "99.9% < 1.0 s" ⇒
  τ≈0.145). We use **τ = 0.13 s**; a heavier real tail only pushes the
  optimum up slightly.
- **A** = λ_acc · κ · C_fp — accidental-fire rate on the hex × Aegis's
  defense multiplier (κ ≈ 0.0096: lands-on-button × no-drift ×
  no-feedback-cancel × no-post-cancel) × the cry-wolf cost of one false
  alarm.
- **B** = λ_em · c_d — real-emergency rate × harm per second of delay.
- **M·P_int(t)** — cost of a *defeated* SOS × probability a watching
  attacker perceives-and-grabs during the hold. `P_int = 0` below the
  reach-floor D ≈ 0.45 s, then rises. **This term only exists if the
  touchscreen is the sole path** (see §4).

## 2. Closed form (attacker term handled by hardware path)

Drop `M·P_int` (the power-button trigger owns the attacker-present
case — §4). Then:

    dC/dt = -(A/τ)·exp(-t/τ) + B = 0
    ⇒ exp(-t/τ) = Bτ/A
    ⇒ t* = τ · ln( A / (B·τ) )

This is the whole result. Two consequences matter more than the number:

1. **t\* scales with the LOG of the cost ratio.** To move the optimum
   by one τ (0.13 s) you must change the cost asymmetry `A/B` by a
   factor of *e*. Moving **1.0 → 1.5 s requires a 47× change** in the
   cost ratio; **0.7 → 1.0 s requires 10×**. The answer is therefore
   robust: squishy cost numbers barely move it.
2. **No psychological-floor term exists in C(t).** Earlier drafts
   argued a "≥1.5 s feels deliberate" floor. There is no cost channel
   for it, so it cannot enter the optimization. Deliberateness is
   pinned by the long-press threshold (~0.5 s) and by the haptic
   pulses, not by raw duration. The 1.5 s floor was the one uncited
   claim in either pass and it is excluded here.

## 3. Removing the hand-set constant — prior sweep

The closed form's *location* rides on `A/B`, which both earlier passes
effectively chose. So instead of choosing it, sweep every input over a
defensible log-uniform range (Monte Carlo, 200k draws; `sos_opt.py`):

| Input | Range | Meaning |
|---|---|---|
| τ | 0.11–0.15 s | touch-duration data |
| λ_acc | 0.03–0.3 /day | accidental sustained contacts on the SOS hex |
| κ | 0.0096 | Aegis defense stack (fixed) |
| C_fp | 0.05–0.5 | cry-wolf cost of one false alarm (life-equiv) |
| λ_em | 0.0003–0.003 /day | real-emergency rate (~once per device life) |
| c_d | 0.001–0.005 /s | delay cost (life/s) |

Result:

    t* percentiles  10 / 25 / 50 / 75 / 90 :  0.59  0.69  0.81  0.93  1.04
    P(t* in 0.7–1.0) = 0.59
    P(t* ≥ 1.5)      = 0.000
    P(t* < 0.7)      = 0.27

Two clean findings:

- **1.5 s is unreachable.** Zero of 200k defensible draws. The earlier
  Aurora-1.75 / interim-1.5 numbers are excluded by the math, not by
  taste.
- **The honest band is ~0.6–1.0 s**, centered at **0.81 s**. The
  maximum-likelihood optimum is ~0.8 s; 1.0 s sits at the ~88th
  percentile — the *conservative* end.

## 4. What the hardware trigger actually does (the crux)

The most-debated point. The power-button trigger (×5) does **not**
argue *for* a longer hold. It **removes the force that argued for a
shorter one.**

In the full C(t), the `M·P_int(t)` term is a large positive spike in
the derivative just past the reach-floor D, which yanks the minimum
*down* to ~0.67 s (this reproduces Chad's original 0.7). That term
exists only when the touchscreen must win a physical scramble. Because
the hardware trigger covers the hands-pinned / attacker-already-on-you
case, `M·P_int` belongs to *that* path, not this one. Deleting it
doesn't push the optimum up — it lets the optimum spring back from the
attacker-pinned ~0.67 s to the false-alarm-vs-delay balance at ~1.0 s,
**and stop there** (it cannot overshoot to 1.5 — that needs the absent
47× cost ratio).

So the three regimes, all from the same C(t):

| Scenario | Minimum | Reading |
|---|---|---|
| Touchscreen is the only path (attacker term ON) | **0.67 s** | obsolete; the power-button path owns this case |
| Hardware covers attacker (attacker term OFF) | **1.00 s** | the operative case |
| To reach 1.5 s | — | needs a 47× larger cost asymmetry; P≈0 |

See `sos_hold_optimization.png` (panels A/B/C): the FP-vs-delay bowl
bottoming at 1.0, the attacker term dragging it to 0.67, and the flat
log-dependence that makes 1.5 unreachable.

## 5. Decision

**Activation hold = 1.0 s. Hardcoded. Never user-configurable.**

- 1.0 s is the conservative top of the derived 0.6–1.0 band — it buys
  maximum cry-wolf margin and a clean 3-pulse haptic while still
  beating the reach-for-the-phone window. The MLE (~0.8 s) and 1.0 s
  are both defensible; 1.0 was the owner's call inside a band the data
  cannot split.
- **Not configurable** is a security decision, not laziness: a setting
  is attack surface (an abuser could retune the victim's panic button)
  and one more thing to get wrong under stress. One derived number for
  everyone.
- **Cancel hold stays 3.0 s.** The asymmetry is intentional: silencing
  a *live* alarm is the catastrophic error, so that direction gets a
  conservative (long) threshold. Fast to fire, slow to silence.

## 6. Haptic timing at 1.0 s

Gesture unchanged — 6-edge Edge-Heat fill, `hapticEdgeStride = 2`,
`hapticOnPress = true`. Edges every ~167 ms; haptic on press + every
2nd edge → blips at **0 / 0.333 / 0.667** and **fire at 1.000 s**. A
clear "bzzt-bzzt-bzzt-FIRE" heartbeat, abortable on release at any
point before edge 6.

## 7. User-facing record

A neutral, math-free explainer ships in the app's Help → Design
section: `assets/docs/WHY_ONE_SECOND.md` ("Why one second?"). It
carries the *reasoning* (two cliffs, the cry-wolf cost, the
slower-on-purpose cancel, why it's not configurable) without the
calculus or any author names, per the public-source comment rules.

# SOS Hold Duration — Reconciliation (Chad ⇄ Aurora)

Chad, 2026-06-09. Synthesis of two independent analyses
(`ANALYSIS_SOS_HOLD.md`, Chad → 0.7s; `SOS_HOLD_DURATION_ANALYSIS.md`,
Aurora → 1.75s). **Converged proposal: ~1.0 s.** Awaiting Aurora's reply.

---

## 1. The two landings

| | Chad | Aurora |
|---|---|---|
| Optimal | **0.7 s** | **1.75 s** |
| Band | 0.5–1.0 s | 1.5–2.5 s |

**Full agreement on:** 3.0s (current) is far too long; the value should
be **configurable**; the Edge-Heat animation must fill the *entire*
chosen duration; the **cancel** (silence-an-active-SOS) hold stays long
because there the cost asymmetry inverts. The disagreement is only the
*fire* threshold, and it reduces to three cruxes.

## 2. The three cruxes — who was right about what

### Crux 1 — cost of a false alarm. **Aurora right; Chad updates up.**
Aurora models cry-wolf as the dominant cost: `Trust(n) = 0.7ⁿ`, the
safety net effectively dead after ~3 false alarms. Chad treated a false
SOS as "recoverable, mildly annoying." Aurora is correct — a false alarm
doesn't cost one apology, it erodes every future alert's responsiveness.
**This raises the optimum above Chad's 0.7s.**

### Crux 2 — model the adversary, or the medical case? **Chad right; Aurora updates down.**
Aurora's delay model is **cardiac arrest** — a *medical* emergency where
the only clock is physiology. But Aegis's actual threat is *adversarial*:
someone is actively trying to stop the user. Chad modeled the
**attacker-interrupt window** — ~270–310 ms to perceive a hand action +
~150–350 ms to reach/grab ≈ **0.4–0.7 s** total — the single most
app-specific force, and it pushes *hard* toward short holds (fire before
the grab lands). A hold for a stalking victim is not a hold for a heart
attack. Aurora's 1.75s hands a watching attacker >1 s of window after
their reaction. **This lowers the optimum below Aurora's 1.75s.**

### Crux 3 — is there a psychological floor at 1.5s? **Chad right.**
Aurora's "below 1.5s → post-activation doubt" is the one uncited claim in
either doc, and it is contradicted by her *own* finding that under panic
"3 s feels like 10 s; time perception distorts." The research floor for a
press reading as *deliberate* is the long-press line at **~0.5 s** (AOSP
400 ms / iOS 500 ms), and panic time-distortion argues for *shorter*, not
longer. There is no evidence-backed 1.5 s floor.

## 3. The reconciling insight (it's tighter than 0.7 vs 1.75)

Aurora's *own* Aegis-defense math is the bridge. With her 5-mechanism
stack (drift-cancel, release-abort, button-area, feedback-cancel,
post-cancel):

    P(false SOS) = 0.0096 × P(held > t)

This is already **~1-per-many-decades by t ≈ 1.0 s.** So her high false-
alarm *cost* is multiplied by a false-alarm *probability* that is
negligible past ~1.0 s. The trust argument therefore justifies clearing
the accidental-touch knee (**~1.0 s**) — it does **not** justify the extra
0.75 s of psychological buffer her 1.75s adds on top.

So:
- Conceding Crux 1 pulls Chad **up** from 0.7 → ~1.0 (clear the FP knee).
- Holding Cruxes 2 & 3 pulls Aurora **down** from 1.75 → ~1.0 (the
  adversary model + her own FP math + no real 1.5s floor).

They meet at **~1.0 s.** Note this is *below* the naïve midpoint (1.2 s)
of the two original numbers — deliberately, because the adversary force
(which Aurora's model omitted entirely) is a genuine, app-central reason
to stay near the FP knee rather than above it. This is a derived
convergence, not a split-the-difference.

## 4. Converged recommendation

**Default 1.0 s.** Sensitivity within the agreed framework:

| Weight toward… | Lands at |
|---|---|
| the adversary / panic case (the app's core) | 0.8 s |
| trust-erosion / the medical case | 1.2 s |
| **balanced** | **1.0 s** |

1.0 s is ~3× faster than the original 3 s and ~33% faster than the
current 1.5 s, while sitting safely past the accidental-contact knee.

**Haptic re-timing** (gesture unchanged — 6-edge Edge-Heat fill): at
1.0 s, edges ≈ 167 ms, haptic every 2 edges → pulses at 0 / 0.33 /
0.67 s, fire at 1.00 s. Clear "bzzt-bzzt-bzzt-FIRE" rhythm, still
abortable on release.

**Configurable** (both authors agree): default 1.0 s, range ~0.7–3.0 s in
0.25 s steps, animation always fills the full chosen duration so the feel
is identical and only the speed changes.

## 5. Two questions left for Aurora

The residual gap reduces to exactly two judgement calls:

1. **Do we optimize the *fire* path for the adversary (someone trying to
   stop you) or the medical case (physiology is the only clock)?** Chad
   says adversary — it's the app's reason to exist. If Aurora agrees, her
   delay model should swap cardiac-arrest for attacker-interrupt, which
   moves her down.
2. **Do we trust her own defense-multiplier math** that the false-alarm
   *probability* is negligible by ~1.0 s? If yes, the trust cost (real
   per-event) stops justifying duration above the knee.

If both → **1.0 s**. If Aurora still weights an un-modelled psychological
floor heavily → we land ~1.2 s and average toward there. Either way, both
independent analyses now agree the answer is **~1 s, not 1.75, not 1.5,
and absolutely not 3.**

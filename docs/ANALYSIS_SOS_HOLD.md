# ANALYSIS_SOS_HOLD — optimal SOS activation hold duration

Author: Chad. Independent analysis (Aurora doing the same separately; we
compare landing points). Research-grounded, decision-theoretic.

**Bottom line: T\* ≈ 0.7 s** (defensible band 0.5–1.0 s). The current
1.5 s is ~2× too long; the original 3 s was ~4× too long.

---

## 1. The correct framing

The SOS hold is not a stylistic "hold time." It is a **dwell-to-confirm
threshold** — formally identical to gaze-dwell selection (the "Midas
touch" problem) and to a **signal-detection criterion**. That literature
gives a closed-form answer.

Optimal decision threshold under asymmetric error costs (Bayes / SDT):

    t* = C_FP / (C_FP + C_FN)          [Bayes cost-minimizing cutoff]
    β* = (P(noise)/P(signal)) · (cost ratio)   [SDT likelihood criterion]

As **C_FN ≫ C_FP**, t\* → 0: the threshold collapses toward its floor and
the criterion goes *liberal* (fire on weaker evidence = shorter hold).
Our asymmetry is near-maximal:

- **C_FN (missed/late real SOS)** for a DV/stalking/trafficking victim:
  catastrophic, irreversible.
- **C_FP (false SOS)**: a "sorry, false alarm" message — recoverable,
  mildly cry-wolf.

The theory's verdict is unambiguous before we plug in a single number:
**the hold should be short** — as short as the floor allows.

Sources: SDT criterion [elvers.us], [NYU/Landy SDT chapter]; Bayes
threshold t\*=C_FP/(C_FP+C_FN) [Wiley JOM], [Springer ROC-in-cost-space];
gaze-dwell trade-off [GaVe, PMC10640920].

## 2. Cost model

Minimize over hold duration T, subject to T ≥ T_min:

    E[C](T) =  λ_acc · S_acc(T) · C_FP                         (accidental fire)
             + λ_emer · ( c_d · T  +  P_int(T) · C_miss )      (delay + interrupt)

- **S_acc(T)** — survival of an *accidental sustained contact on the SOS
  hex* past T. Steeply decreasing.
- **c_d · T** — linear harm of delaying the alert by T in a real
  emergency.
- **P_int(T)** — probability an attacker perceives the press and knocks
  the hand/phone away before the hold completes. Increasing in T.

FP term pulls T up; the emergency term pulls T down. With C_miss ≫ C_FP,
the emergency term dominates → T\* sits at the floor where S_acc(T) has
already decayed to negligible.

## 3. Parameters (research-grounded)

| Symbol | Meaning | Value / anchor | Source |
|---|---|---|---|
| **T_min** | floor: press reads as *deliberate*; below it, not an intentional hold | **0.40–0.50 s** (Android long-press 400 ms; iOS 0.5 s) | AOSP `ViewConfiguration`; Apple `UILongPressGestureRecognizer` |
| **S_acc** | accidental sustained contact survival | resolved ~200 ms (~99.5%); **"statistically unusual" past 0.5 s**; "strongly atypical" past 1.5–2 s | Schwarz CHI'14 palm-rejection; long-press defaults |
| **P_int** | attacker perceive-and-disrupt time | reaction **270–310 ms** + reach **150–350 ms** ≈ **0.42–0.66 s** total | interception-RT study (PMC11578145); Fitts movement |
| **comfort ceiling** | hold felt as "waiting" | **~1.0 s** (Nielsen), fine with progress feedback (we have Edge-Heat fill) | NN/g response-time limits |
| **gaze-dwell empirics** | analogous threshold | **~0.5 s** for speed, **0.8–1.0 s** for min-error (≈symmetric costs) | GaVe (PMC10640920) |
| C_miss / C_FP | cost asymmetry | ≫ 1 (≈10²–10³ defensible) | threat model |

## 4. Where T\* lands

Three forces, two of them pushing to the floor:

1. **Decision theory:** C_miss ≫ C_FP ⇒ threshold → floor.
2. **Attacker window:** a hold completing **under ~0.5 s fires before the
   grab lands** (P_int ≈ 0); every extra 100 ms past ~0.5 s opens the
   interrupt window. This is the most app-specific force and it points
   *down*, hard.
3. **Floor (the only thing holding T up):** T_min ≈ 0.5 s (deliberate-
   press threshold) — and, not coincidentally, the same ~0.5 s line where
   accidental contacts become statistically unusual. So below ~0.5 s you
   simultaneously lose intentionality AND start admitting accidental
   contacts. The floor is real and it is ~0.5 s.

The gaze-dwell "0.8–1.0 s for min-error" is for *roughly symmetric*
costs; our extreme asymmetry shifts the optimum **below** that band,
toward the speed end. Net:

    T*  ≈  T_min  +  small margin (haptic confidence + S_acc safety)
        ≈  0.5 s  +  ~0.2 s
        ≈  0.7 s

## 5. Sensitivity

- **Weight the emergency/attack case more** (the *correct* weighting for
  this app's threat model) → **0.5–0.6 s**.
- **Weight false-alarm/cry-wolf more** (e.g. a jumpy surveillance context)
  → **0.8–1.0 s**. Still below 1.5 s.
- **Across the whole plausible parameter space, T\* ∈ [0.5, 1.0] s.**
  No reasonable cost assignment lands at 1.5 s, let alone 3 s — those sit
  outside the feasible band entirely.

The result is robust because two independent forces (SDT cost asymmetry
*and* the attacker-grab window) push the same direction, and the floor is
pinned by two coincident effects (deliberateness *and* accidental-contact
die-off) at the same ~0.5 s.

## 6. Recommendation

**0.7 s.** Just above the deliberate-press floor (so it never feels like
a hair-trigger and the release-to-abort window still exists), comfortably
into the tail of the accidental-contact curve, and short enough to fire
before a watching attacker can knock the phone away. Anywhere in
**0.6–0.8 s** is well-justified; I would not go below 0.6 (haptic
confidence) nor above 1.0 (Nielsen ceiling + the asymmetry forbids paying
for delay).

**Haptic re-timing** (the gesture stays a 6-edge Edge-Heat fill): at
0.7 s, edges are ~117 ms, haptic every 2 edges → pulses at 0 / 0.23 /
0.47 s, fire at 0.70 s. Still a clear "bzzt-bzzt-bzzt-FIRE" rhythm —
fast, but perceived and abortable.

**Asymmetry retained:** this is the *fire* threshold only. The *cancel*
(silence an active SOS) hold stays long (3 s) — there the cost asymmetry
inverts (silencing a real alert is the catastrophe), so SDT prescribes a
*conservative* threshold. Fast to fire, hard to silence.

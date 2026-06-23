# SOS Hold Duration — Unbiased Reanalysis

Aurora, 2026-06-09. Clean-room. No anchoring to previous numbers.

## The four hard constraints

The hold duration T must satisfy all four simultaneously. Violate any one and the design fails.

### Constraint 1: T > T_deliberate (the press must feel intentional)

The industry threshold for "this is not a tap":
- Android long-press: 400ms (AOSP ViewConfiguration)
- iOS long-press: 500ms (UILongPressGestureRecognizer)

**T_deliberate = 0.5s**

### Constraint 2: T > T_accidental (no false activations)

Accidental touches: 99th percentile < 500ms, 99.9th < 1000ms.

But the REAL defense against false activation is not the hold duration — it's the NAVIGATION. The user must be on the SOS screen to use the hold. A pocket press can't navigate to the SOS tab, find the hex button, and hold it. The navigation step eliminates virtually all accidental activations regardless of hold duration.

With Aegis defenses (button-area, drift-cancel, release-to-abort, animation feedback):

    P(false SOS) is negligible at T > 0.5s

The hold duration is not the primary false-alarm gate. Navigation is.

**T_accidental ≈ 0.5s (bound by T_deliberate, not by accidental touches)**

### Constraint 3: T < T_attacker (fires before the grab)

An attacker who sees the victim reaching for the phone:
- Perception: ~200ms
- Decision: ~100ms  
- Motor response (close range): ~200ms
- Total close range: ~500ms

But: the attacker's clock starts when they PERCEIVE the SOS action. The victim's hold starts at press. If the attacker is watching the screen, they see the animation immediately. If watching the victim's hand, they see the press but not necessarily its purpose.

Attacker window from press to grab:
- Close range (arm's length): 0.4–0.6s
- Medium range (1–2 meters): 0.6–1.0s
- Across room: 1.0–2.0s

**T_attacker (close) ≈ 0.5s, T_attacker (medium) ≈ 0.8s**

### Constraint 4: T < T_stress (achievable under panic)

Gross motor (holding a button) is preserved under stress. With visual feedback (Edge-Heat), the user perceives progress. The binding constraint is psychological doubt ("is this working?"), not motor ability.

Nielsen's research: 1.0s is the "flow" threshold. With animation feedback, the flow threshold extends further.

**T_stress ≈ 1.5s with feedback, ~1.0s without**

## The feasible region

    T_deliberate (0.5s) < T < T_attacker (0.5–0.8s)

This is extremely tight. At close range, the constraints nearly overlap.

## The critical insight: two activation paths

Aegis has TWO SOS activations:

1. **Hardware trigger (power ×4):** rapid multi-press, works screen-off, works from pocket. Zero hold duration. Optimized for: attacker is present, phone is being grabbed, screen is inaccessible.

2. **Touchscreen hold:** requires SOS screen, visible animation. Optimized for: victim has a moment (bathroom, attacker in other room), deliberate activation.

These serve different scenarios. The touchscreen hold does NOT need to beat the close-range attacker — the hardware trigger covers that. The touchscreen hold needs to be:
- Deliberate (not accidental)
- Fast enough for the semi-private scenario (10–30 seconds of privacy)
- Long enough for meaningful feedback (3 haptic pulses minimum)

## The number

**Without hardware trigger (touchscreen is the only option):**

    T_feasible = [0.5, 0.8] → T* ≈ 0.65s (center of feasible region, close-range optimized)

**With hardware trigger (touchscreen is the deliberate path):**

    T* ≈ 1.0s (above the accidental knee, below stress threshold, with full 3-pulse feedback)

Since Aegis has both: **T* = 1.0s for touchscreen.**

The 1.0s aligns with:
- Nielsen's flow threshold (the animation fills 1.0s — feels like one continuous action, not a wait)
- Three full haptic pulses at 0/0.33/0.67s → fire at 1.0s (clear rhythmic confirmation)
- Past the accidental-contact knee (negligible false positives)
- Short enough for the semi-private scenario (1 second out of 10–30 seconds of privacy)
- 3× faster than the original 3.0s

## Result

**1.0 second. Hardcoded. Not configurable.**

The hardware trigger handles the attacker-present emergency. The touchscreen hold handles the deliberate activation. Each is optimized for its scenario. One number. No settings.

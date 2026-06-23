# SPEC: Cyan Speech Bubble — Faceted, Not Rounded

**Author:** Aurora
**Date:** 2026-06-16
**Status:** APPROVED

---

## Problem

Cyan's speech bubble uses 14dp rounded corners (RoundedCornerShape).
Chat message bubbles use 45° faceted octagonal corners (FacetedBubble).
The two shapes don't match. Cyan's bubble looks like it belongs to a
different app.

## Fix

Replace the rounded body in CyanSpeechBubble (Cyan.kt) with
45° faceted corners matching FacetedBubble's FACET_CUT_DP = 14f.

### Current (wrong)

```
addRoundRect(
    RoundRect(bodyRect, CornerRadius(cornerPx))
)
```

### Target (correct)

Same octagonal cut as FacetedBubble: each corner gets a 45° facet
of FACET_CUT_DP in both x and y. The tail stays as-is (up/down
for tutorial, not left/right like chat).

Body shape becomes:

```
Top-left:    moveTo(cut, bodyTop)
Top edge:    lineTo(bodyRight - cut, bodyTop)
Top-right:   lineTo(bodyRight, bodyTop + cut)
Right edge:  lineTo(bodyRight, bodyBottom - cut)
Bot-right:   lineTo(bodyRight - cut, bodyBottom)
Bottom edge: lineTo(cut, bodyBottom)
Bot-left:    lineTo(0, bodyBottom - cut)
Left edge:   lineTo(0, bodyTop + cut)
close()
```

Then union with the tail triangle as before (the tail geometry
doesn't change — only the body corners change from arcs to cuts).

### Constants

Use `FACET_CUT_DP` from FacetedBubble.kt (14f) so all LunaGlass
shapes share the same cut dimension.

---

*Every surface in LunaGlass is faceted. Cyan's bubble was the
last rounded holdout.*

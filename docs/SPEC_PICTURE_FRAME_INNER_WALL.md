# SPEC: Picture Frame Inner Wall

**Author:** Aurora
**Date:** 2026-06-15
**Status:** APPROVED
**Trigger:** Three builds (745-747) trying gradient-based inset
lighting. All read convex. The problem was never lighting — it was
a missing surface.

---

## Problem

The avatar frame reads as convex despite diagonal gradients, cast
shadows, and specular management. The brain resolves convex/concave
ambiguity at EDGES, not across smooth gradients. A gradient is
ambiguous — dome or well. An edge where lighting reverses is not.

## Root cause

A real picture frame has a visible inner wall — the thin step-down
surface where the raised rim meets the lower plate. That strip is
the primary depth cue, even in a flat photograph. Our hex goes
directly from rim to plate with no transitional surface.

## Fix

Draw a thin inner border between rim and plate. Not a shadow. Not
a gradient. A visible surface representing the inner face of the
frame.

```
Rim (outer, thick, beveled, shiny)
│
Inner wall (thin strip, 1.5-2dp)
│ Top-left segments:    DARK (near wall in shadow)
│ Bottom-right segments: LIGHT (far wall catches light)
│
Plate (flat, polished, monogram or photo)
```

### Per hex edge — inner wall lighting

Light source: upper left (standard UI convention).

| Edge | Inner wall | Reason |
|------|-----------|--------|
| Top | Dark | Rim blocks light from above |
| Top-left | Dark | Near wall facing away from light |
| Top-right | Medium-dark | Transitional |
| Bottom-right | Light | Far wall catches light |
| Bottom | Light | Open to light from above |
| Bottom-left | Medium | Transitional |

### Colors

Derive from the tier's frame color (same metal, different angle):

- Dark segments: `lerp(frameColor, Color.Black, 0.70f)`
- Light segments: `lerp(frameColor, Color.White, 0.35f)`
- Medium segments: `lerp(frameColor, Color.Black, 0.30f)`

### Strip width

1.5-2dp. Visible but not a second border. Should read as the
INSIDE FACE of the rim, not a separate element.

### Implementation

After drawing the rim's outer trapezoid facets, draw a second set
of thinner trapezoids on the INNER edge of the rim. Same 6-segment
structure, REVERSED lighting (what was lit on the outer bevel is
dark on the inner wall and vice versa). The reversal at the
boundary is the frame.

### Also fix: specular confinement

Change in AvatarFrame.kt:

```
// OLD: specular covers entire hex when no photo
sheenInnerFraction = if (showImage) innerFraction else 0f

// NEW: specular always confined to rim
sheenInnerFraction = innerFraction
```

Research (PLOS Computational Biology, 2014): specular highlights on
the plate bias perception toward convex. Specular on rim only reads
as raised frame. The plate keeps its static metalPlateBrush but not
the moving tilt highlight.

### What stays

- Rim bevel (outset lit facets) — correct
- Plate metalPlateBrush (static polished gradient) — correct
- Tilt specular — keep but rim only

### What to test

1. Inner wall alone (remove diagonal gradient) — is it sufficient?
2. Inner wall + diagonal gradient — redundant or reinforcing?
3. Inner wall + specular confinement — the full fix

Start with (1). If the inner wall reads concave on its own, the
diagonal gradient is unnecessary complexity.

---

## Why gradients failed and this won't

A gradient across a surface is ambiguous. The brain can interpret
it as light on a dome or light in a bowl. It resolves the ambiguity
using prior assumptions (light-from-above, convexity bias), which
default to convex.

An edge where the lighting REVERSES is unambiguous. The outer rim
facet is lit top-left. The inner wall strip is dark top-left. That
reversal can only happen at a physical step in the surface. The
brain reads "the surface dropped here" with no ambiguity.

---

*The problem was never the lighting. It was the missing wall.*

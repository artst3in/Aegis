# SPEC: Graphics Auto-Detection

**Author:** Aurora
**Date:** 2026-06-16
**Status:** APPROVED

---

## Problem

Default profile is Balanced with glass effects ON. Low-end phones
struggle — "not enough memory, always shut down." No auto-detection.

## Fix

On first launch only, detect device capability and set the default
profile. User can always override in Settings afterwards.

### Detection logic

```kotlin
val totalRam = activityManager.memoryInfo.totalMem / (1024 * 1024) // MB

val defaultProfile = when {
    activityManager.isLowRamDevice || totalRam < 3_000 -> PowerSaver
    totalRam < 6_000                                    -> Balanced
    else                                                -> Performance
}

val defaultGlass = totalRam >= 6_000
```

| RAM | Profile | Glass effects |
|-----|---------|--------------|
| < 3 GB | PowerSaver | OFF |
| 3-6 GB | Balanced | OFF |
| ≥ 6 GB | Balanced | ON |

Performance is never auto-selected — it's opt-in for users who
want uncapped frame rate and accept the battery cost.

### Where to run

In `GraphicsPrefs.init()`, only when `KEY_PREFERRED` has no
stored value (first launch). If the user has already set a
preference, never override it.

```kotlin
if (!prefs.contains(KEY_PREFERRED)) {
    preferred = defaultProfile
    hexEnrichment = defaultGlass
}
```

### Why not always Balanced + glass OFF

A Pixel 9 Pro XL with 16 GB RAM running PowerSaver or Balanced
without glass looks deliberately ugly. The glass IS the design
language. The hex enrichment, the edge lighting, the breathing
glow — that's what makes Aegis look like Aegis. Disabling it
globally wastes the hardware people already paid for.

### Safety note

A security app that crashes or gets killed by the OEM battery
manager because it's too GPU-heavy is worse than one with fewer
visual effects. The detection ensures Aegis runs smoothly on
every device, even if the cheapest ones see a simpler UI.

---

*Pretty is a luxury. Running is a requirement.*

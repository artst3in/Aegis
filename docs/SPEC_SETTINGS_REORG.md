# SPEC: Settings Reorganization — Hick + Miller + Chunking

**Author:** Chad
**Date:** 2026-06-02
**Status:** IMPLEMENTED — Chad 2026-06-02 (5-section math-driven
layout shipped in release 2026.06.200; "user-respect maths" note
added to AEGIS_USER_MANUAL.md per Artur)

---

## Summary

The Settings screen ran 18 user-visible rows in a flat ungrouped list:
12 link rows + 4 inline content cards + 2 conditional surfaces. That's
above Miller's working-memory ceiling (7±2) and incurs full Hick's-Law
serial-scan cost. Three of the four cards (Update, Capabilities) had
the structural problem that a permanent ~400 dp INFO surface ate
vertical real-estate on every visit forever.

This spec restructures Settings into 5 labelled sections of 2–3 rows
each, plus the Profile anchor card at the top and the About footer
card at the bottom. Inline content cards become link rows where
appropriate. Predicted decision-time drop: ~25%. Predicted error-rate
drop: substantially more (mis-categorisation is harder when categories
exist).

---

## Problem

### Counted

| Type | Count |
|------|-------|
| Link rows | 12 |
| Inline content cards | 4 |
| Conditional surfaces | 2 (OutboxCard, Experimental row) |
| **Total user-visible rows** | **18** |

Hick's Law cost: `log₂(19) ≈ 4.25` decision-time units. Well above the
7±2 working-memory ceiling — the user can no longer parallel-scan;
they're forced into serial.

### Structural problems

1. **No grouping.** 18 rows in flat list with zero category labels.
   The user has no scent of where a setting lives.
2. **Mixed types.** Link rows (action), conditional banners (Outbox),
   permanent info cards (Capabilities), and content cards (Update,
   About) compete for the same vertical real-estate. The user has to
   mentally classify each row before they can act.
3. **Redundant duplicate.** The "Updates" link row navigates to the
   same `settings/updates` screen that UpdateCard reproduces inline.
   Two surfaces for one feature.
4. **Permanent INFO eats forever.** CapabilitiesCard is a read-only
   inventory the user reads once after install. It takes ~400 dp on
   every Settings visit for the rest of the app's lifetime.

---

## Frameworks

For a vertical scrollable list, two laws dominate.

### Hick's Law

`time ≈ a + b · log₂(n + 1)` for n equiprobable choices.

Implication: doubling choices adds one full unit of decision cost,
linearly. There's no clever way around it for a flat list.

### Miller's 7±2

Working memory holds 5–9 items at once. Above 9, the brain switches
from parallel comprehension to serial scan, which is dramatically
slower (no longer logarithmic — closer to linear).

### Chunking (Tulving / Bower)

Items grouped under labels are processed at the **group level first**,
then drilled into. The effective search cost becomes
`log₂(groups) + log₂(items_per_group)`, which beats flat
`log₂(total)` once total > 8.

### Fitts's Law (not applicable here)

Fitts's Law governs *target acquisition*, used elsewhere in Aegis for
thumb-zone tab placement. Settings is a scrollable list with no
thumb-zone benefit, so Fitts doesn't drive this design.

---

## Frequency × domain analysis

Domain tags from the audit:

| Cluster | Members | Why together |
|---------|---------|--------------|
| **Messaging** | Quiet hours, Hold to send, Disappearing default | All shape how outbound messages and incoming notifications behave |
| **Appearance** | Nav order, Graphics, Language | All control how Aegis presents itself |
| **Data** | Profiles, Backup, Capabilities | All "what's in this install + how do I move/inspect it" |
| **Network** | SimpleX relays, Calls | Transport / ICE / signalling. Both about wire-level connectivity |
| **System** | Updates, Diagnostics, Experimental (cond) | All "Aegis as software": versioning, health, dev features |

5 clusters × avg 2.6 rows = 13 items.

---

## Predicted decision-time math

### Before (flat 18 rows)

- Items: 18
- Hick's cost: `log₂(19) ≈ 4.25`
- Working memory: **exceeds Miller's ceiling** → serial scan
- Realistic effective cost: closer to half-list linear search, ~9 items

### After (5 sections × ~3 rows + Profile card + About card)

- Outer level: 5 section headings → `log₂(6) ≈ 2.58`
- Inner level: ~3 items in target section → `log₂(4) = 2.00`
- Raw hierarchical cost: `2.58 + 2.00 = 4.58`
- BUT the labelled headings let the user **skip 4 of 5 sections
  at glance**. Effective items considered: 1 heading + 3 inner
  items ≈ 4 items.
- Effective Hick's cost: `log₂(5) ≈ 2.32`
- Within Miller's ceiling at every level → parallel scan throughout.

**Predicted decision-time reduction: ~25%** (4.25 → 3.2 effective).

Additional benefits not captured in the time-math:

- **Error rate drops.** Mis-categorisation is harder when categories
  exist. A user looking for "Quiet hours" no longer accidentally taps
  "SimpleX relays" because they're in different sections.
- **Discoverability gain.** Section labels function as a table of
  contents. The user gets an at-a-glance map of what Aegis is
  configurable about.

---

## Proposal

### Structural moves

1. **Convert CapabilitiesCard → CapabilitiesScreen** behind a "Data
   → Capabilities" link row. Same content, no inline footprint.
2. **Remove UpdateCard.** Its inline functionality lives in
   `UpdateSettingsScreen` (already accessible via the "System →
   Updates" row). The card was a duplicate surface.
3. **Move OutboxCard above the section list.** Treat it as a
   transient alert/banner, not a navigable destination. Self-hides
   when pending == 0 (existing behaviour).
4. **Introduce `SettingsSection(label) { … }`** composable. Small
   all-caps cyan label, matches the section-header style used in
   `DiagnosticsScreen` and `AboutScreen`. Pure visual chunk — not
   expandable, no collapse state. Rows stay always-visible inside
   their section.

### Final layout

```
[ Profile card ]                          ← identity anchor

[ OutboxCard ] (conditional)              ← transient alert

MESSAGING
    Quiet hours
    Hold to send / call
    Disappearing default

APPEARANCE
    Nav order
    Graphics
    Language

DATA
    Profiles
    Backup
    Capabilities                          ← was inline card

NETWORK
    SimpleX relays
    Calls

SYSTEM
    Updates                               ← UpdateCard absorbed
    Diagnostics
    Experimental (conditional)

[ About / Project Aether card ]           ← version footer
```

7 vertical chunks (Profile + 5 sections + About). Within Miller's
ceiling at every level.

### Security toggles deliberately absent

The Security tab's skill tree (`docs/SPEC_SKILL_TREE_VISUAL.md`) is
the canonical entry point for App PIN, Mugshot, Canary, SIM Watch,
Geofence, Vault PIN, Vault Duress, Device Admin, Device Owner, SOS
Drill, Sonar/Sentinel. Settings hosts only the non-security knobs.
This is by design and pre-dates this reorg; the spec preserves it.

---

## Alternatives considered

### Flat list, alphabetised

Standard "Settings A-Z" pattern from iOS Mail / Android Files. Easy
to scan if you know the name; useless if you don't. Aegis's user
might not know "Hold to send" is called that — they know they want
"the thing that stops me from accidentally sending half-typed
messages". Alphabetical sort breaks that mental model.

### Search-only

No grouping, no sections, just a search box at top. Works well at
massive scale (Google's Chrome Settings) but is overkill at 12 rows
and assumes the user has a keyword in mind. The user often doesn't —
they're browsing for what's possible. Sections beat search for
discovery.

### Expandable sections

Each section header is tappable to collapse/expand. Adds one tap to
every visit. Saves vertical space but breaks the "rows are always
visible" guarantee that makes section labels useful as a table of
contents. Rejected.

### Tabs instead of sections

Five-tab strip at the top of Settings. Adds a strong mode separation
but breaks the scroll-and-see pattern; user can't compare items
across categories. Rejected for the relatively small total row count
— tabs make sense at 50+ items, not 13.

---

## Open questions

None. Layout is unambiguous, math is uncontroversial.

---

## Out of scope

- Per-screen reorgs (LockSettings, GraphicsSettings, etc.) keep
  their existing internal organisation.
- The Security skill-tree visual stays untouched.
- The bottom-nav tab order stays untouched (already optimised for
  Fitts's Law, separate spec).

---

## Implementation note (non-binding)

- `SettingsSection` is a small wrapper Spacer + Text + content lambda.
  Sub-15-line composable.
- `CapabilitiesScreen` is a near-copy of the old CapabilitiesCard
  wrapped in a TopAppBar + scrollable column. Content unchanged.
- Route: `settings/capabilities` added to MainActivity NavHost.
- `UpdateCard` + the three private helpers (actionLabel,
  statusLabel, statusColor) deleted; functionality remains in
  `UpdateSettingsScreen` which the "System → Updates" row already
  navigated to. Net loss: ~330 lines of inline UI code from
  SettingsScreen.
- `expCtx` / `expPrefs` hoisted to the outer SettingsScreen scope so
  the 7-tap-to-unlock counter in About card AND the conditional
  Experimental row inside System section both read the same
  instance.

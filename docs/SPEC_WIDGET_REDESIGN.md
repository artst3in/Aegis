# SPEC: Widget Redesign — Family Dashboard

**Author:** Aurora
**Date:** 2026-06-16
**Status:** APPROVED

---

## What the widget IS

The widget is the only surface always visible without unlocking
or opening anything. It is a **family status dashboard** — one
glance tells you the state of everyone you protect.

The current widget is a reskinned contact list. It shows names
and battery. That is not a dashboard — it is a phone book.

---

## Layout

### Small (4×2 cells)

Compact mode. Shows threat level + SOS only.

```
┌──────────────────────────┐
│  AEGIS     ● ALL SAFE    │
│                          │
│       [ HOLD SOS → ]     │
└──────────────────────────┘
```

### Medium (4×4 cells)

Family overview. No per-contact detail.

```
┌──────────────────────────────┐
│  AEGIS              ● SAFE   │
│──────────────────────────────│
│  Zippy    🏠    82%    3     │
│  Flozzy   2.1km 47%         │
│  Aga      🏠    95%    1     │
│──────────────────────────────│
│       [ HOLD SOS → ]         │
└──────────────────────────────┘
```

### Large (5×6 cells, current default)

Full dashboard with status bar.

```
┌───────────────────────────────────┐
│  AEGIS                   ● SAFE   │
│───────────────────────────────────│
│  Zippy     🏠 Home    82%    3   │
│  Flozzy    📍 2.1km   47%       │
│  Aga       🏠 Home    95%    1   │
│───────────────────────────────────│
│  ⏱ Canary: 6h 23m                │
│  🛡 Sentinel: Armed               │
│───────────────────────────────────│
│         [ HOLD SOS → ]            │
└───────────────────────────────────┘
```

---

## Features

### 1. Threat level (ALL sizes)

One-word family status in the header. Computed from all contacts:

| State | Label | Color |
|-------|-------|-------|
| Everyone connected, inside geofence, battery > 15% | SAFE | Green |
| Someone offline > 1h, or battery < 15%, or canary < 2h | ALERT | Amber |
| SOS active (any contact) | SOS | Red pulse |
| Geofence breach (any contact) | BREACH | Red |
| Canary expired | CANARY | Red |

Priority: SOS > BREACH > CANARY > ALERT > SAFE.

This is the single most valuable pixel on the home screen. One
dot tells you whether to worry.

### 2. Per-contact location (medium + large)

Not just "online/offline". Where they ARE:

- **Inside geofence:** 🏠 + zone name ("Home", "Work", "School")
- **Outside all geofences:** 📍 + distance from YOU in km/mi
- **Unknown:** last seen timestamp

Location updates come from GPS heartbeats already being sent.
Zero extra traffic.

### 3. Per-contact unread count (medium + large)

Cyan number on the right side of each row. Only shown if > 0.
This is the most common reason to glance at a widget —
"do I have messages."

### 4. Canary countdown (large only)

The dead man's switch timer is the single feature that benefits
most from always-visible placement. If you don't check in within
the window, your contacts get an alert.

Show the countdown: "Canary: 6h 23m". Turns amber at < 2h,
red at < 30min. Tapping opens the canary screen to check in.

If canary is not armed, this row is hidden.

### 5. Sentinel status (large only)

"Sentinel: Armed" or hidden when disarmed. Cyan text, subtle.
Tapping opens Sentinel screen.

### 6. SOS strip (ALL sizes)

**Does NOT fire SOS.** Opens the app to the SOS screen where
the 1-second hold applies. Label: "HOLD SOS →" to set the
expectation.

If SOS is already active, the strip changes:
- Background: pulsing red
- Text: "SOS ACTIVE — 2m 34s" (elapsed time)
- Subtext: "GPS ✓ Audio ✓ Camera ✓" (what's broadcasting)
- Tapping opens the SOS active screen to monitor or cancel

### 7. SOS live thumbnail (large only, SOS active)

When SOS is active and camera snapshots are arriving from a
remote target, show the latest thumbnail in the widget. The
operator sees the capture on their HOME SCREEN.

This replaces the status bar when SOS is active:

```
┌───────────────────────────────────┐
│  AEGIS                   ● SOS    │
│───────────────────────────────────│
│  ┌────────┐  Zippy's phone        │
│  │ latest │  GPS: 51.2°N 4.4°E   │
│  │ photo  │  Audio: recording     │
│  └────────┘  2m 34s ago           │
│───────────────────────────────────│
│    [ SOS ACTIVE — tap to open ]   │
└───────────────────────────────────┘
```

This is the feature that makes the widget a command center,
not a phone book.

### 8. Per-contact quick actions (medium + large)

Long-press a contact row (if Glance supports it on the
target API level) or show a small action icon:

- 📍 Quick locate — request fresh GPS from that contact
- 💬 Quick message — open chat

If long-press is unavailable, tap opens chat (existing
behavior). Quick locate is accessible from the app.

---

## Visual

### Font

SansSerif throughout. "AEGIS" header in Bold + wide letter
spacing.

### Corners

`cornerRadius(2.dp)` — near-sharp. Glance can't do true
facets but sharp reads angular.

### Colors

| Element | Hex |
|---------|-----|
| Background | #050508 |
| Surface (rows) | #0E0E14 |
| Brand accent | #00FFFF (Aegis Cyan) |
| SOS / danger | #D32F2F |
| SOS active bg | #F44336 |
| Safe dot | #4CAF50 |
| Alert dot | #FF9800 |
| Text primary | #C8C8D0 |
| Text dim | #666670 |

### SOS active state

When SOS is active, the ENTIRE widget border glows red (if
Glance supports border color, otherwise the background shifts
to a very dark red #0A0204 to signal danger without being
garish).

---

## Strings (all in strings.xml)

```
widget_header = "AEGIS"
widget_safe = "SAFE"
widget_alert = "ALERT"
widget_sos_label = "SOS"
widget_breach = "BREACH"
widget_canary_label = "CANARY"
widget_hold_sos = "HOLD SOS →"
widget_sos_active = "SOS ACTIVE"
widget_sos_elapsed = "SOS ACTIVE — %1$s"
widget_sos_broadcasting = "GPS %1$s Audio %2$s Camera %3$s"
widget_sentinel_armed = "Sentinel: Armed"
widget_canary_remaining = "Canary: %1$s"
widget_home = "Home"
widget_distance = "%1$s"
widget_never_seen = "—"
widget_description (already exists)
```

---

## Safety

| Old | New |
|-----|-----|
| Single-tap SOS fires immediately | Opens app SOS screen with hold |
| No threat indicator | One-dot family status (SAFE/ALERT/SOS) |
| No location context | Per-contact geofence + distance |
| No canary visibility | Countdown timer always visible |
| No SOS monitoring | Live thumbnail + elapsed + broadcast status |
| Contact list | Family status dashboard |

---

## Implementation priority

1. Threat level dot + safe/alert/sos (quick, high value)
2. SOS opens app instead of firing (critical safety fix)
3. Per-contact unread count (most-wanted feature)
4. Per-contact location/geofence
5. Canary countdown
6. SOS active state with elapsed time
7. SOS live thumbnail (most complex, highest wow factor)
8. Visual cleanup (fonts, corners, colors)

---

*A phone book tells you who you know. A dashboard tells you
who needs you.*

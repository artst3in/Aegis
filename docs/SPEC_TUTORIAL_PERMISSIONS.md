# SPEC: Tutorial Permission Screen

**Author:** Aurora
**Date:** 2026-06-14
**Status:** SPEC — P0 for ship. OxygenOS killed Aegis during testing.
**Trigger:** 15-minute debugging session for a bug that didn't exist.
OxygenOS silently murdered background activity. This WILL happen to
every OnePlus, Xiaomi, Oppo, Samsung, and Huawei user.

---

## The problem

Aegis asks for many permissions. Users see the wall of permission
dialogs and either deny critical ones or get scared. Meanwhile, the
single most important "permission" — battery optimization exemption —
is never requested during setup, only buried in Diagnostics.

The result: SOS doesn't fire because location was denied. Sentinel
can't capture because camera was denied. Messages stop arriving
because the OEM killed background activity.

For a security app, a denied permission is a broken promise.

---

## New tutorial page: "Aegis needs your permission"

Insert AFTER the "What makes this different" page, BEFORE the trust
tiers page. Cyan mascot at normal size with a speech bubble:

"I need a few permissions to protect you. Here's why each one matters."

### Permission cards (top to bottom, in request order)

Each card shows: icon, permission name, one-line WHY, and a request
button that triggers the system dialog. Card turns green check on
grant, orange warning on deny with a "you can enable this later in
Settings" note.

| # | Permission | Why | API |
|---|-----------|-----|-----|
| 1 | Notifications | So you see SOS alerts, messages, and security events. | POST_NOTIFICATIONS (Android 13+) |
| 2 | Location | For SOS GPS coordinates and the Radar map. | ACCESS_FINE_LOCATION then ACCESS_BACKGROUND_LOCATION |
| 3 | Camera | For SOS snapshots, Sentinel mugshots, and remote capture. | CAMERA |
| 4 | Microphone | For SOS audio recording, voice calls, and remote listen. | RECORD_AUDIO |
| 5 | Phone state | To detect SIM swap attacks. | READ_PHONE_STATE |

### Battery optimization — the critical one

After the permission cards, a separate highlighted section:

"This is the most important step. Your phone manufacturer tries to
kill background apps to save battery. Aegis must stay alive to
protect you."

Button: "Disable battery optimization" fires
ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.

### OEM-specific warning

Detect manufacturer via Build.MANUFACTURER. If OnePlus, Xiaomi,
Oppo, Realme, Huawei, Samsung, or Vivo — show additional card:

"Your [manufacturer] phone has extra battery settings that can kill
Aegis. Open them now to make sure Aegis stays alive."

Button opens OEM battery settings intent (wrap in try-catch, fall
back to https://dontkillmyapp.com/ if intent doesn't resolve).

### Skip behavior

All permissions are skippable. "Continue" button shows grant count:
"Continue (4/5 granted)". If battery optimization NOT granted, show
warning: "Without this, messages and SOS may not work when your
screen is off."

---

## Strings

All text in strings.xml (not hardcoded). Aurora will translate once
strings are finalized.

---

*This spec exists because OxygenOS killed an SOS-capable app in the
background and the user had no idea.*

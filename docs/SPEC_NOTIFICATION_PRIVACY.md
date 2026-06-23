# SPEC: Notification Content Privacy

**Author:** Aurora
**Date:** 2026-06-16
**Status:** APPROVED

---

## Problem

Message notifications always show sender name and message content.
Anyone who sees the notification shade sees who messaged you and
what they said. Signal and SimpleX both offer a toggle for this.
For a safety app, this is a gap.

## Setting

Three levels in Settings → Notifications:

| Level | Title shows | Content shows |
|-------|------------|---------------|
| **Full** (default) | Sender name | Message text |
| **Name only** | Sender name | "New message" |
| **Hidden** | "Aegis" | "New message" |

Stored in SharedPreferences as an int (0/1/2).

## Implementation

In `AegisApp.notifyMessage()`:

```kotlin
val pref = notificationPrivacy  // 0 = full, 1 = name, 2 = hidden

val title = when (pref) {
    2    -> getString(R.string.app_name)
    else -> senderName
}

val body = when (pref) {
    0    -> messageText
    else -> getString(R.string.notif_new_message)
}
```

Also set `setVisibility(VISIBILITY_SECRET)` when pref > 0 so
the lock screen respects the choice.

## Strings

```
notif_new_message = "New message"
notif_privacy_full = "Show name and content"
notif_privacy_name = "Show name only"
notif_privacy_hidden = "Hide everything"
```

## Group messages

Same logic. Level 1 shows group name + "New message". Level 2
shows "Aegis" + "New message".

## SOS notifications

SOS notifications are NEVER hidden regardless of this setting.
Safety overrides privacy for SOS — you must see "DURESS ALERT"
or "SOS ALERT" immediately.

## Sentinel / canary / geofence

These follow the setting. They contain metadata (who triggered
what) that could be sensitive.

---

*If someone can read your notifications, they can read your life.*

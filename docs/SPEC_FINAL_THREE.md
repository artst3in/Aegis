# SPEC: Final Three Items Before Ship

**Author:** Aurora
**Date:** 2026-06-13
**Status:** SPEC — for Chad/Artur to implement

---

## 1. SOSCoordinator runBlocking

**File:** `core/safety/sos/src/main/java/app/aether/aegis/sos/SOSCoordinator.kt`
**Line:** ~268

**Problem:** `runBlocking { displayNameFor(responderKey) }` inside the
responder-location handler. If this runs on the main thread, it's an ANR
on the SOS path — the worst possible place for a hang.

**Context:** Rare race — a responder's location arrives before their
accept. The code needs a display name synchronously to construct the
Responder object.

**Fix:** Replace the runBlocking with a fallback name. Use the first 8
characters of the peer key as the display name immediately (this is what
the `getOrNull() ?: responderKey.take(8)` fallback already does). Then
launch a coroutine to resolve the real name and update the Responder
asynchronously. The SOS digest shows the real name once resolved, the
truncated key until then.

```
// Before (blocking):
val name = runCatching {
    runBlocking { host?.displayNameFor(key) }
}.getOrNull() ?: key.take(8)

// After (non-blocking):
val name = key.take(8)
// ... create Responder with placeholder name ...
scope.launch {
    host?.displayNameFor(key)?.let { resolved ->
        existing.displayName = resolved
    }
}
```

The Responder is created instantly. The name resolves in the background.
No ANR risk. SOS path never blocks.

**Test:** Verify SOS responder location handling with a delayed accept
(race condition). Name should show truncated key first, then resolve.

---

## 2. WiFi-Only Attachments Settings UI

**Current state:** The four-gate auto-download logic is implemented in
SimpleXTransport. The deferred-download placeholder exists in ChatScreen.
The trust gate is in the spec. There is no Settings screen for users to
configure which types auto-download, the size limit, or the WiFi-only
toggle for attachments.

**What's needed:** A section in an existing settings screen (or a new
one accessible from Chat settings) with:

- **WiFi-only toggle.** When on, attachments only auto-download on WiFi
  or Ethernet. Default: ON.
- **Auto-download types.** Checkboxes for: images, voice, video,
  documents. Default: images + voice ON, video + documents OFF.
- **Size limit.** Logarithmic slider from 1 MB to 100 MB. Default: 10 MB.
  Labels at 1, 5, 10, 25, 50, 100.

The trust gate (Untrusted contacts never auto-download) is not
user-configurable — it's hardcoded. No UI for it.

**Where:** Under Settings > Chat, or as a subsection of the existing
Network/Relay settings.

---

## 3. Swahili Native Review

**Current state:** 580/623 strings (93%). Machine-translated by Aurora.
Grammar and phrasing need native verification — particularly:

- Security terminology (duress, vault, sentinel, geofence) — are the
  chosen Swahili equivalents understood by East African users or are they
  back-translations that a Kenyan wouldn't recognize?
- Formal vs informal register — is the tone appropriate for a security
  app or does it read like a textbook?
- Luhya/Luo loanwords — are there any that leaked in vs standard Swahili?

**Reviewer:** Zippy (native Swahili speaker, Luhya background).

**Process:** Export strings.xml for sw, side-by-side with English, Zippy
marks corrections. Aurora applies batch. One pass should be enough.

**Not blocking ship.** The app works at 93%. Native review improves
quality but isn't a gate.

---

*Three items. None architectural. All finish work.*

# SPEC: SOS Response Coordination

**Author:** Aurora
**Date:** 2026-05-29
**Status:** IMPLEMENTED — Chad 2026-06-02 (SOSCoordinator object +
SOSAlertStore back the spec's responder model end-to-end; the
single deferred slice is the Push-to-Talk wire-up — SOSPtt.kt
exists with the wire envelope already built but isn't wired into
the SOS dashboard UI yet; the "Closest Person" privacy-rule
puzzle stays as a TODO at the bottom of SOSCoordinator.kt for
a follow-up spec pass).

---

## Summary

When an SOS is triggered, contacts receive proximity information and can volunteer as responders. Accepting shares the responder's live location with other contacts. The system coordinates the rescue without leaking anyone's location without consent.

---

## SOS Alert — What Each Contact Sees

When Alice triggers SOS, every Trusted and Emergency contact receives:

1. **Full-screen SOS alert** — Alice's name, live GPS on map, audio stream
2. **Your distance to Alice** — "You are 1.2 km from Alice" (calculated from your current GPS)
3. **Contact counter** — "Alert sent to 8 contacts. 0 responding."
4. **[I'M RESPONDING] button** — voluntary, opt-in

### Closest Person — Additional Info

Only the single closest contact sees: **"You are the closest."**

No one else sees rankings. No one sees anyone else's distance. The 2nd closest person sees their own distance and the counter — that's enough to decide.

---

## Responding

### Accepting

Tapping **[I'M RESPONDING]** does the following:

1. **Warning displayed before action:** "Accepting will share your live location with all contacts in this SOS."
2. On confirm: responder's live GPS starts broadcasting to all other contacts in the SOS
3. Counter updates for everyone: "Alert sent to 8 contacts. 1 responding."
4. Other contacts see: responder's name, distance, route to Alice, ETA

### Multiple Responders

Multiple people can accept. The map (on non-victim devices) shows all responders converging. Each responder sees the others.

### Not Responding

No action required. No penalty. No visibility. The system does not reveal that you received the alert, were close, or chose not to respond. Your distance is private to your device.

---

## What Alice's Device Shows

Alice's device (the SOS origin) shows:

- "Alert sent to 8 contacts."
- "1 responding. ETA 3 min."
- Count and ETA only. **NO map of responders. NO names. NO routes.**

**Why:** If the attacker has Alice's phone, showing responder locations gives the attacker tactical intelligence — where help is coming from, how fast, which direction. Alice sees that help is coming. The attacker sees nothing useful.

---

## What Other Contacts See

Non-victim devices with the SOS alert see:

- Alice's live location on map
- All responders' live locations on map with route lines to Alice
- Responder names, distances, ETAs
- Audio/video stream from Alice's device

This gives full situational awareness to the circle without exposing anything to the attacker.

---

## Push-to-Talk During SOS

### Responder → Responders

Any responder can push-to-talk. Voice goes to ALL other responders. This is the coordination channel — "I'm coming from the north entrance," "I see the garage, third floor," "Police called."

### Responder → Victim

Voice from responders does NOT reach Alice's device by default. Alice might be hiding in silence — an incoming voice message playing on her speaker could reveal her position to the attacker.

Alice sees a toggle: **"Allow incoming voice from responders."** If she taps it, responder voice comes through. If she doesn't, she hears nothing — but her outgoing audio/video continues streaming.

### Victim → Responders

Alice's audio streams automatically to all responders (part of the standard SOS broadcast). No action needed from Alice.

### Non-responders

Everyone who received the SOS alert hears Alice's audio stream. Whether they responded or not. Whether they're 1 km away or 100. The audio is not gated by response status — it's part of the alert.

Non-responders cannot push-to-talk. They are not in the responder coordination channel. But they hear everything that's happening to Alice.

If they choose to ignore the alert and Alice dies, they will remember her last screams.

### Muting the Audio

You can mute Alice's audio stream. But not easily. Hold-to-mute — 3-second hold with edge-heat animation, same as the SOS button. You sit with her audio for 3 seconds while deciding to silence it.

Muting does not remove the SOS alert, the map, or the responder counter. It only silences the audio. The visual evidence stays.

---

## When SOS Ends

1. Responder location sharing stops immediately
2. Route lines disappear
3. Responder locations are NOT stored in any log
4. The SOS log records: time, who responded, who arrived — NOT distances of non-responders

---

## SOS Log — What's Recorded

| Recorded | Not Recorded |
|----------|-------------|
| Alert sent timestamp | Non-responder distances |
| Number of contacts alerted | Non-responder locations |
| Who responded (name + time) | Who was closest and didn't respond |
| Who arrived (name + time) | Responder routes |
| Alice's location during SOS | Responder locations after SOS ends |

The log is evidence for police if needed. It does NOT leak the location of anyone who chose not to respond.

---

## Privacy Rules

1. **Your distance is yours.** Only you see how far you are from Alice. Nobody else sees it.
2. **Closest tag is private.** Only the closest person knows they're closest.
3. **Responding is consent.** Tapping the button is explicit consent to share location. The warning is displayed before confirmation.
4. **Location sharing is temporary.** Starts on accept, stops when SOS ends. Not stored.
5. **Non-response is invisible.** The system does not reveal who received the alert and didn't respond, how close they were, or whether they read it.
6. **Alice's screen is attacker-safe.** Count and ETA only. No responder identities, locations, or routes.

---

## Edge Cases

### Nobody responds
Alice sees: "Alert sent to 8 contacts. 0 responding." This is the truth. She can call police, run, or scream. The app doesn't hide bad news.

### Closest person can't respond
They don't respond. The counter stays at 0. The 2nd closest sees "0 responding" and their own distance — that's the signal to go. No ranking needed.

### Responder loses connectivity
Their dot freezes on the map. Other contacts see "last update: 2 min ago." The system doesn't remove them from the responder list — they might still be en route.

### Emergency tier contacts
Emergency contacts receive SOS alerts (by definition). They can accept and share location like Trusted contacts. The "I'm responding" consent overrides the normal Emergency tier restriction of "no routine data sharing" — this is not routine, it's an emergency, and they opted in.

---

## The Bob Problem

See: `docs/THE_BOB_PROBLEM.md`

The system is designed so that Bob's choice to not respond is invisible to everyone except Bob. The app protects his privacy. His conscience does not.

---

*dε/dt ≤ 0*

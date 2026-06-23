# SPEC: Three-State Presence (Online / Away / Offline)

**Author:** Aurora
**Date:** 2026-05-29
**Status:** IMPLEMENTED — Chad 2026-06-02

---

## Problem

A peer currently renders only two effective states:

- **Green (Online)** — actively in the Aegis app (foreground)
- **Offline** — everything else

This is wrong. A phone with Aegis alive in the background is still reachable — SOS alerts, location, remote commands all work. But it renders identical to a phone that is off or has the app killed. The user can't tell "she's reachable, just not looking at the app" from "her phone is dead."

Observed symptom: a peer shows offline everywhere, yet their battery still updates (because the background status ticker keeps sending). Offline + live battery is contradictory to the user.

---

## Root Cause

The status packet carries two distinct timestamps:

- `ts` — when the packet was generated. Proves the **background service is alive** right now.
- `inApp` — the sender's last **foreground** activity (InAppActivity heartbeat).

On receive, `handleInboundStatus` calls `patchDeviceStatus(ts = inApp, …)`. It stores **only `inApp`** into the single `lastActive` column and discards the packet's own `ts`. With only one timestamp, the receiver cannot distinguish:

- background-alive (recent `ts`, stale `inApp`) — should be **Away**
- dead (stale `ts`) — should be **Offline**

Both collapse to offline.

---

## Desired Behavior

Three states, derived from two timestamps:

| State | Color | Condition | Meaning |
|-------|-------|-----------|---------|
| **Online** | Green (`AegisOnline`, with glow) | `inApp` age < ONLINE_WINDOW | Actively in the app, looking at the screen |
| **Away** | Orange (`AegisWarning`) | `inApp` stale, but `ts` (last packet) age < AWAY_WINDOW | App alive in background — reachable, SOS/location work |
| **Offline** | Grey (`AegisOnSurfaceDim`) | `ts` age ≥ AWAY_WINDOW | No recent packet — app killed, phone off, or unreachable |

### Windows

- `ONLINE_WINDOW` = 5 minutes (matches existing foreground threshold)
- `AWAY_WINDOW` = aligned to the status ticker cadence with margin. Status ticker fires every 60 s on normal battery, every 5 min at ≤ 35 %. Set `AWAY_WINDOW` = 12 minutes so a peer on low-battery cadence (5 min ticks) still reads Away across a missed tick, and only flips to Offline after multiple consecutive misses.

`PeerStatus { Online, Away, Offline }` and `StatusDot` already support all three colors. Only the **computation** and the **stored data** need to change.

---

## Required Changes (for Chad)

1. **Carry both timestamps to the receiver.** The packet already includes both `ts` and `inApp` — no wire change needed.

2. **Store both.** Add a second column to `MemberStatusEntity` (e.g. `lastPacketMs`) alongside the existing `lastActive` (which holds `inApp`). Room migration required. Do not drop `lastActive`.
   - `lastActive` = `inApp` (foreground) — drives Online
   - `lastPacketMs` = `ts` (packet generation) — drives Away vs Offline

3. **Update `patchDeviceStatus`** to write both timestamps instead of collapsing to `ts = inApp`.

4. **Central status function.** Replace the inline `ageMs` checks (currently duplicated in MapScreen lines 220-223, DeviceStatusScreen, ChatScreen, StatusScreen, ContactDetailScreen) with one shared function:

   ```
   fun peerStatus(inAppAgeMs, packetAgeMs): PeerStatus =
       when {
           inAppAgeMs < ONLINE_WINDOW  -> Online
           packetAgeMs < AWAY_WINDOW   -> Away
           else                        -> Offline
       }
   ```

   Every screen calls this. No screen computes presence locally. (This also fixes the current duplication where MapScreen already has an Away branch but ChatScreen's `isOnline` is binary.)

5. **Self is always Online** while the app is in the foreground (existing `isSelf -> Online` branch stays).

---

## Clock Skew Note

The age calculation is `receiver_now − sender_timestamp`. If the two devices' clocks differ significantly, presence misreads (a peer with a fast clock could read Online forever; a slow clock could read Offline while active). This is a pre-existing risk, not introduced here. Out of scope for this spec, but worth a future ticket: clamp negative ages to 0 and consider a sanity ceiling.

---

## Acceptance

- Peer actively in app → green dot, everywhere (chat list, map, status grid, contact detail)
- Peer backgrounds the app → flips to orange within ONLINE_WINDOW, stays orange while the background service keeps pinging
- Peer's app killed / phone off → flips to grey after AWAY_WINDOW
- Battery and location continue to behave independently (battery shows whenever a packet arrives; location gated by permission + provider as today)

---

*dε/dt ≤ 0*

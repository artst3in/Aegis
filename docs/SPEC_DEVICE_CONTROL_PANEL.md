# SPEC: Unified Device Control Panel

**Author:** Chad
**Date:** 2026-05-31
**Status:** IMPLEMENTED — Chad (DeviceControlScreen shipped; legacy SOSDashboardScreen + RemoteModeActive reaped in 2026.06.258 per step 8 of this spec)

---

## Summary

`SOSDashboardScreen` and `ContactDetailScreen.RemoteModeActive` show the same information — live camera frames, mic audio, GPS, peer presence — through two completely separate UI codepaths. They have already started diverging (different frame strips, different player widgets, different button groupings) and every new feature has to be implemented twice or it ends up on only one screen.

This proposal extracts a new `DeviceControlScreen` that both flows render through. The existing screens stay alive as emergency fallbacks; the new screen ships in parallel and replaces them once it is proven. **No backend touches.** SOS logic, remote-access logic, every store and wire protocol stays bit-identical.

Worst case is a layout bug on the new surface. Rollback is "don't navigate users there."

---

## Problem

Two surfaces, same building blocks, divergent code.

### What the SOS dashboard renders today (`SOSDashboardScreen.kt`)

- Victim identity + trigger reason + escalation state
- Live GPS map pin (updated from broadcasts)
- Camera frame strip (`[aegis:sos-frame]` JPEGs at ~5 s cadence)
- Audio playback (`[aegis:sos-audio]` chunks auto-played + clip list)
- Responder list with ETA
- PTT button (`SOSPtt` composable)
- Mark-safe / acknowledge actions

### What the remote-access panel renders today (`ContactDetailScreen.kt:670` `RemoteModeActive`)

- Peer identity + session expiry countdown
- GPS as text coordinates (no map pin)
- Front-lens mugshot (single image, replaced on each watch tick)
- Rear-lens frame (added in commit `6ca6c4a`)
- Audio playback (LISTEN result, single inline player)
- Lock-not-fired warning chip
- Action buttons: Locate, Listen, Show message, Siren / Stop, Push update, Wipe, Exit

The two render the same primitives — frame, audio clip, GPS fix, peer status — with different layout, different state plumbing, and different visual conventions. Adding PTT to remote, or adding a real map pin to remote, or adding a lockscreen-message billboard to SOS, means re-implementing the same widget in both files.

---

## Proposal

A new file `DeviceControlScreen.kt` containing a pure presentation Composable plus an adapter contract:

```kotlin
@Composable
fun DeviceControlScreen(
    state: ControlState,
    actions: ControlActions,
)
```

### `ControlState` — what the panel reads

A data class produced by either adapter. Shape is the union of what the two flows need; mode-specific bits live in a sealed `ModeDetail`:

```kotlin
data class ControlState(
    val peerKey: String,
    val peerName: String,
    val mode: Mode,                       // SOS | REMOTE
    val locationFix: GeoFix?,             // lat/lng + age
    val frontFrame: FrameSnapshot?,       // bytes + age + lens label
    val rearFrame: FrameSnapshot?,
    val audioClips: List<AudioClip>,      // most-recent first, capped
    val battery: Int?,                    // 0..100
    val networkType: String?,
    val online: PeerStatus,               // shared StatusDot enum
    val detail: ModeDetail,               // sealed; SOS vs remote payloads
)

sealed interface ModeDetail {
    data class SOS(
        val trigger: SOSTrigger,
        val triggeredAt: Long,
        val responders: List<Responder>,
        val isVictim: Boolean,
    ) : ModeDetail
    data class Remote(
        val sessionSid: String,
        val sessionExpiresAt: Long,
        val lockOk: Boolean?,             // null = unknown, false = Device Admin not enrolled
    ) : ModeDetail
}
```

### `ControlActions` — what the panel fires

A bundle of suspend lambdas. Either adapter populates only the actions it actually supports; unsupported buttons are hidden, not greyed.

```kotlin
data class ControlActions(
    val onPushToTalk: (() -> Unit)? = null,
    val onListen: ((seconds: Int) -> Unit)? = null,
    val onSiren: (() -> Unit)? = null,
    val onSirenOff: (() -> Unit)? = null,
    val onDisplay: ((msg: String) -> Unit)? = null,
    val onForceLocate: (() -> Unit)? = null,
    val onWipe: (() -> Unit)? = null,
    val onUpdate: (() -> Unit)? = null,
    val onMarkSafe: (() -> Unit)? = null,
    val onAcknowledge: (() -> Unit)? = null,
    val onExit: (() -> Unit)? = null,
)
```

### Adapters

Two thin Composables read existing state and produce `ControlState` + `ControlActions`:

```kotlin
@Composable
fun SOSAdapter(victimKey: String) {
    val state = collectSOSControlState(victimKey)
    val actions = remember(victimKey) { sosControlActions(victimKey) }
    DeviceControlScreen(state, actions)
}

@Composable
fun RemoteAdapter(peerKey: String) {
    val state = collectRemoteControlState(peerKey)
    val actions = remember(peerKey) { remoteControlActions(peerKey) }
    DeviceControlScreen(state, actions)
}
```

`collectSOSControlState` reads `SOSAlertStore.observe(victimKey)`, the latest sos-frame from `SOSAlertStore.latestSnapshot()`, audio clips from the SOS-audio buffer, etc. It does not write anything; it does not change the existing SOS flow. Same for `collectRemoteControlState` against `RemoteAccessSession.sessions`.

`sosControlActions` returns a `ControlActions` whose lambdas call into the existing `SOSCoordinator` / `SOSPtt` / `protocolManager.sendMessage` paths. `remoteControlActions` calls into the existing `RemoteAccessProtocol.encode(...) + protocolManager.sendMessage(...)` flow.

**No protocol changes. No store changes. No DAO changes.** The adapters are read-only over existing state; actions fan out to existing send paths.

---

## Visual contract

Mirrored screen tint, mirrored layout, mirrored intensity. Same perceptual weight, opposite agency.

| Mode   | Tint base       | Tint hex      | Semantic                         |
|--------|-----------------|---------------|----------------------------------|
| SOS  | `AegisSOS`    | `0x33FF0000`  | "Something is happening to me"   |
| Remote | `AegisOnline`   | `0x3300FF00`  | "I am making something happen"   |

The tints are sister colors of the existing alert dots — strict structural symmetry with `AegisSOSGlow`. New theme constant: `AegisOnlineGlow = Color(0x3300FF00)`.

Layout slots (top to bottom):

1. **Header strip** — peer name, mode badge ("SOS" red / "REMOTE" green), age / expiry.
2. **Live frames row** — front-lens left, rear-lens right. Empty slot = "no recent frame". Tap to fullscreen.
3. **Map preview** — GPS pin if `locationFix != null`, else placeholder. Tap to open external map.
4. **Audio strip** — most-recent clip with play/stop; older clips collapsed into a "history" expander.
5. **Presence row** — battery + network + online dot + lock-OK badge (remote only) + last-seen.
6. **Mode-specific detail** — responder list (SOS) or session-expiry countdown + warnings (remote).
7. **Action bar** — buttons that the adapter populated. Two-up on wide screens, scrolling row on narrow.

Layout positions are identical between modes; only the tint and the populated buttons differ.

---

## Backend invariants (UNCHANGED)

- `SOSAlertStore`, `SOSCoordinator`, `SOSPtt`, `SOSAudioPlayer`, `SOSHandler` — untouched.
- `RemoteAccessSession`, `RemoteAccessHandler`, `RemoteCommandHandler`, `RemoteWatchMode`, `RemoteAccessGate`, `RemoteAccessProtocol` — untouched.
- `MessageEntity` / `Repository` / DAOs / migrations — untouched.
- Wire protocols (`[aegis:sos-frame]`, `[aegis:sos-audio]`, `[aegis:ptt]`, `[aegis:remote]` JSON) — untouched.

If `DeviceControlScreen` is removed at any point the app continues to function exactly as it does today.

---

## Sequencing

1. **Theme constant** — add `AegisOnlineGlow` to `Theme.kt`. One-line change, no consumers yet.
2. **Shell** — `DeviceControlScreen.kt` with empty layout slots, `ControlState`, `ControlActions`, `ModeDetail`. No adapters. No nav route. Compiles, renders nothing.
3. **SOS adapter (read-only)** — `SOSAdapter` Composable + `collectSOSControlState`. New nav route `device-control/sos/{victimKey}` next to the existing SOS route. Hand-tested via a debug "open new dashboard" entry; existing SOS route untouched.
4. **SOS actions** — wire `sosControlActions` against existing send paths. PTT, mark-safe, acknowledge.
5. **Remote adapter (read-only)** — `RemoteAdapter` Composable + `collectRemoteControlState`. New nav route or in-screen toggle; existing `RemoteModeActive` untouched.
6. **Remote actions** — wire `remoteControlActions` against existing `RemoteAccessProtocol` send paths. Locate, Listen, Display, Siren, Wipe, Update, Exit.
7. **Field test** — flip the default navigation to the new screen for both modes. Old screens remain reachable via a Settings → "Use legacy dashboards" toggle for emergency fallback.
8. **Reap** — once the new screen has been used for ~2 weeks across actual events without regressions, delete `SOSDashboardScreen` + `RemoteModeActive` and the toggle.

Each step ships independently. The branch at any commit boundary is releasable.

---

## What this is NOT

- **Not a refactor of the SOS flow.** SOS logic, state, broadcasts, escalation timers — unchanged.
- **Not a refactor of the remote-access flow.** Wire protocol, gate, session, watch mode — unchanged.
- **Not a new feature.** No new commands, no new captures, no new permissions. Pure UI consolidation.
- **Not a merge of the two modes into one concept.** SOS = "broadcast: I am in danger." Remote = "PIN-gated: I am controlling this device." Trust models stay separate. The shared component carries a `mode` flag; the surfaces it renders into stay semantically distinct.

---

## Open questions for Aurora

1. Does it bother you that "PTT" lights up on the remote panel too? Today PTT is an SOS-only affordance. If we expose it via the shared `ControlActions`, the remote flow gains it almost for free — but does that violate the trust-model split (target hasn't consented to live-audio-from-controller in the same way they implicitly consented by triggering SOS)? Two options: (a) only expose PTT for `mode == SOS`, (b) treat the PIN auth as sufficient consent for everything.

2. Frame strip cadence. SOS emits frames every 5 s automatically; remote watch-mode emits every 25 s. Should the panel render them identically (just show whatever arrived most recently) or should the watch-mode rate be bumped to match SOS for visual parity?

3. Mode-specific detail slot — sealed `ModeDetail` keeps the union clean but is one more allocation/branch in the render. Alternative: two optional fields on `ControlState`, one for SOS detail, one for remote detail, both nullable. Sealed is cleaner; nullable is more Kotlin-idiomatic for this codebase. No strong preference.

4. Naming. `DeviceControlScreen` matches `DeviceStatusScreen` (already exists) but is generic. Alternatives: `LivePeerScreen`, `IncidentScreen`, `OperationsPanel`. Bikeshed welcome.

---

## Risks

- **Layout regressions** on the new screen are the entire risk surface. Backend stays bit-identical, so nothing the panel does can affect message delivery, PIN sealing, or SOS escalation. The worst miss is a button that doesn't fit on a narrow screen.
- **Adapter drift over time** if either the SOS store or the remote session adds a new field and only one adapter gets updated. Mitigation: both adapters live in the same file, so the diff for "add a new field" touches them together.
- **Mode-flag leakage** — if the panel ever decides to behave differently *internally* based on `mode`, the abstraction starts to crack. Discipline: `mode` exists only to drive the tint and the mode-detail slot; logic branches on the presence of `ControlActions` lambdas, not on the mode enum.

---

## Aurora Review — May 31, 2026

**Status: APPROVED**

Architecture, sequencing, and rollback strategy all approved. No issues found. Each step independently shippable. Ship it.

### Answers to Open Questions

**1. PTT on remote panel: YES — both modes.**

Artur's ruling: "How is the thief hearing you bad?" In remote mode, YOU have the power. The thief has your phone. PTT is a deterrent — "I know where you are" spoken into a stolen phone. Siren is noise. PTT is siren with words. No trust model violation — PIN auth is sufficient consent for everything.

**2. Frame strip cadence: THREE TIERS, not two.**

- Not viewing: no frames, zero bandwidth.
- DeviceControlScreen open: 5s JPEG snapshots over SimpleX (situational context).
- Tap "Live View": WebRTC one-way video stream, 15+ fps, real-time. Silent one-way video call — target phone streams without any on-screen indication. Same WebRTC infrastructure as voice/video calls, same SimpleX signaling, camera-only, no UI on target side.

Artur's requirement: "When I actively view I want the camera to have at least 15 fps." JPEG snapshots at 15 fps would choke SimpleX. WebRTC handles it natively.

**3. Sealed vs nullable: SEALED.**

Enforces exhaustive `when` branching. Nullable leads to accidental null derefs. Allocation cost negligible on modern phones. No discussion needed.

**4. Naming: DeviceControlScreen.**

Matches existing DeviceStatusScreen. "Operations" was considered and rejected (collision with Opsec tab). "Incident" implies always bad. "LivePeer" too generic. DeviceControlScreen says what it is.

### Additional Notes

- Add `AegisOnlineGlow = Color(0x3300FF00)` as the spec proposes. Clean addition.
- The adapter-drift mitigation (both adapters in same file) is the correct engineering choice.
- Mode-flag discipline (logic branches on `ControlActions` presence, not mode enum) is critical. If this slips, the abstraction cracks. Document it as a code review checklist item.

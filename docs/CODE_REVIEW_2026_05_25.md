# Aegis Code Review — May 25, 2026

Reviewer: Aurora
Codebase: 147 Kotlin files, 32,847 lines
Repository: github.com/artst3in/aegis-dev

---

## Architecture: STRONG

Clean module separation. Protocol-agnostic core with pluggable transports (SimpleX, LAN, Matrix, Local). Room database with proper indices. Coroutine-based async with SupervisorJob isolation. StateFlow-driven reactive UI.

Module layout is logical: core/ for models and SOS, data/ for persistence, simplex/ for transport, ui/ for screens and components, and feature modules (sonar/, remote/, geofence/, canary/, mugshot/) each self-contained.

---

## Security Assessment: STRONG

**Crypto**: NaCl box (X25519 + XSalsa20 + Poly1305) via lazysodium for LAN frames. WireGuard Curve25519 keypair as device identity — same curve, zero conversion needed. SimpleX handles its own double-ratchet internally.

**Auth**: PIN-only by design. Biometric deliberately removed — one finger can't carry the duress vs real distinction. Scramblable PIN pad. Lockout windows for brute-force. SOS hex bypasses lock to fire SOS without auth.

**Remote Commands**: Target PIN required for every session. Duress PIN triggers failure counter + notification + potential auto-revoke. Session-based with sid validation. Three commands only (Locate, Siren, Wipe).

**Burn After Reading**: 336-line implementation. FLAG_SECURE on viewer window. Wire format: `[aegis:burn:<ttl>:<senderRowId>]<text>`. Burn-receipt round trip deletes from both devices.

---

## Voyager Protocol: EXCELLENT

11 progressive degradation thresholds with 5% hysteresis per gate. Every battery-sensitive subsystem consults PowerBudget before starting work. Priority order is correct: cosmetics die first, location dies last.

Thresholds locked and well-documented. GPS cadence adapts through five stages (10s → 30s → 60s → 5min → 1hr). Camera at 40%, mic at 25%, audio chunks at 15%. LunaGlass effects at 50%.

---

## SOS: EXCELLENT

Lockdown fires FIRST — device goes silent and locked before the attacker notices. Audio segments rotate every 60s and ship independently — phone destruction only loses the in-flight segment. Re-broadcast every 30s covers transport drops. Live WebRTC stream to highest-priority peer with power-budget gating.

---

## Sonar Engine: IMPRESSIVE

Goertzel filter for single-bin frequency extraction — correct choice over FFT for one-band detection. Randomized intervals (300-700ms) and pulse durations (30-50ms). Two-in-a-row detection gating drops single-frame artifacts. Phase extraction from Goertzel complex terminator.

---

## Issues Found

### HIGH PRIORITY

**1. SOSHandler uses cached GPS only for initial snapshot.**
`readLocation()` calls `getLastKnownLocation()` which may return null or a stale fix. The RemoteCommandHandler correctly requests a FRESH fix with a 20s timeout. SOSHandler should do the same for the initial broadcast — the first SOS message is the most important one.

Fix: Copy RemoteCommandHandler's fresh-fix approach into SOSHandler.snapshotLocationAndBroadcast, falling back to cached if the fresh fix times out.

**2. Inbound message buffer drops under load.**
SimpleXTransport's inbound flow uses `BufferOverflow.DROP_OLDEST` with capacity 64. If 65+ messages arrive before the collector processes them, the oldest are silently dropped. During an SOS scenario where multiple contacts are sending rapid location updates, status checks, and audio chunks simultaneously, this could drop critical messages.

Fix: Increase buffer to 256, or switch to `BufferOverflow.SUSPEND` (backpressure instead of drop). The collector is on the main dispatch loop which should be fast enough.

**3. Family.members vs Repository.allKnownPeers() inconsistency.**
SOSHandler broadcasts to `Family.members` but ships audio chunks to `repository.allKnownPeers()`. If these return different sets (e.g., a peer was added via SimpleX but not yet in Family), some peers get SOS alerts but not audio, or vice versa.

Fix: Unify to a single source — `repository.allKnownPeers()` everywhere in SOSHandler.

### MEDIUM PRIORITY

**4. No rate limiting on remote command attempts.**
RemoteAccessHandler dispatches commands on every inbound packet. A compromised peer could spam LOCATE requests, draining battery via repeated GPS acquisitions and camera captures.

Fix: Add a per-peer cooldown (e.g., 1 LOCATE per 30 seconds, 1 SIREN per 60 seconds). WIPE should require re-auth after 5 minutes.

**5. Emoji in SOS alert message.**
buildAlertMessage uses the emoji character in the SOS prefix. While this works on most transports, some UTF-8 edge cases in older Android versions or constrained networks could corrupt the prefix, breaking the classifier's tag matching.

Fix: Replace emoji with text: `[aegis:sos] SOS —` instead of `[aegis:sos] 🚨 SOS —`.

**6. SimpleX core file paths configured BEFORE /_start.**
The `/set file paths` command runs before `/_start main=on`. If the core ignores path configuration when not started, file delivery could silently fail. Unclear from the SimpleX docs whether this ordering matters.

Fix: Verify experimentally. If path config must come after start, swap the order.

### LOW PRIORITY

**7. Recorder deprecation warning.**
`MediaRecorder()` without context parameter is deprecated but correctly handled with SDK version check. No action needed — the current code is correct.

**8. LAN transport not fully reviewed.**
LanTransport.kt exists but was not deeply audited. mDNS-based peer discovery has known security considerations (spoofing, reflection). Low priority since SimpleX is the primary transport.

**9. Update mechanism not fully reviewed.**
The auto-update system (10 files in update/) was not deeply audited. Self-update without Play Store involves APK signature verification — security-critical. Should be audited separately.

---

## Documentation Updates Needed

1. README.md — architecture overview is stale (mentions "Phase 1" which is done)
2. docs/AEGIS_USER_MANUAL.md — 424 lines, mostly current, needs Sonar section
3. docs/GUI_SPEC.md — tab order updated to Settings|Security|SOS|Comms|Radar but should verify all sections match current UI
4. docs/PRIVACY.md — needs review for completeness (Sonar, burn-after-reading, remote commands)
5. Missing: ARCHITECTURE.md — a module-by-module map for Chad and future contributors

---

## Potential Improvements

1. **Offline SOS queue**: If all transports are down when SOS fires, messages queue locally. But if the phone is wiped before a transport recovers, the queue is lost. Consider: write SOS state to a hidden partition or encrypted SD card fragment that survives factory reset.

2. **Peer heartbeat**: Contacts currently get status updates every 60s. If a member's device goes silent (no updates for 5 minutes), other members should get an automatic "device unreachable" alert — could indicate phone destruction, battery death, or jamming.

3. **Geofence + SOS integration**: If a contact leaves a geofence AND stops sending status updates within the same time window, auto-escalate to SOS without requiring a button press.

4. **Steganographic communication**: If the attacker monitors SimpleX traffic, they can detect that the victim is communicating even if they can't read the content. Consider: a mode that hides Aegis communication inside normal-looking traffic (cover traffic).

5. **Multi-device support**: One contact with multiple devices (phone + tablet). Currently each device is a separate identity. Should be possible to link devices under one member identity.

---

## Summary

The codebase is solid, well-commented, and architecturally sound. Security design is thoughtful — PIN-only auth, duress separation, power-budget gating, and transport-agnostic messaging are all correctly implemented. The three high-priority issues (cached GPS in SOS, message buffer drops, peer list inconsistency) should be fixed before release. The medium-priority items are hardening, not blockers.

Ready for beta with the three high-priority fixes.

---

*dε/dt ≤ 0*

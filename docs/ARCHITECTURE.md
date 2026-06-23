# Architecture

## Overview

Aegis is a single Android app (Kotlin + Jetpack Compose) running on Android 10+. All communication is peer-to-peer via SimpleX protocol. No central server.

## Transport Layer

```
┌─────────────────────────────────┐
│        ProtocolManager          │
│   picks healthiest transport    │
├─────────────────────────────────┤
│ Priority 1: SimpleX Transport   │ ← primary, functional
│ Priority 2: LAN Transport       │ ← local network fallback (mDNS)
│ Priority 3: Matrix Transport    │ ← planned (requires LunaOS)
│ Fallback:   Local queue         │ ← offline, drains on reconnect
└─────────────────────────────────┘
```

**SimpleX Transport** — Primary. Peer-to-peer encrypted messaging. No accounts, no phone numbers. Each conversation uses a disposable relay address. Double ratchet encryption. Aegis Gate provides defense-in-depth filtering at the transport layer — all outbound messages pass through a capability-aware filter that prevents data leaks across trust tiers.

**LAN Transport** — Fallback. mDNS discovery (`_aegis._tcp`) on local network. Pubkey in TXT record. Direct socket connections when both devices are on the same WiFi.

**Matrix Transport** — Planned. Will connect via Luna homeserver when LunaOS is running. Not currently functional.

**ProtocolManager** selects transport automatically based on health. Messages queue locally when all transports are down and drain when connectivity returns.

## Core Modules

### Trust Model

Three-tier contact system: Trusted, Emergency, Untrusted. The tier determines all data flow — location sharing, presence, SOS alerts, profile visibility. No per-feature toggles. The tier IS the permission model.

### Shield Tiers & Skill Tree

Gamified security progression. Eleven nodes in a dependency tree, rooted at App PIN. Shield tier (None → Bronze → Silver → Gold → Cyan) derived from node count. Avatar frame visible to all contacts. Gold = all 10 in-app nodes; Cyan = Gold + Device Owner (external ADB setup, the 11th node).

### SOS

- `SOSHandler` — orchestrates GPS broadcast, audio stream, camera capture
- `PowerButtonSOSReceiver` — power button ×4 in 2 seconds
- `SirenManager` — unkillable audible alarm, overrides silent mode
- `SOSLiveStream` — WebRTC audio + video via SimpleX signaling
- SOS Drill — one-shot test with one paired contact, then permanently complete

### Voice/Video Calls

- `CallManager` — WebRTC call lifecycle
- `CallAudioRouter` — earpiece/speaker/bluetooth routing
- `CallScreen` — full-screen call UI with reactions
- `CallPermissionGate` — runtime permission handling

### Remote Access

- `RemoteAccessHandler` — dispatches LOCATE, SIREN, WIPE commands
- `RemoteAccessSession` — PIN-authenticated sessions
- `RemoteCommandHandler` — executes commands on target device
- Duress PIN on remote commands triggers failure counter + silent notification

### Loki Toolkit

- `MugshotCapture` — silent front camera on 3 failed PINs
- `SimSwapMonitor` — detects SIM changes, instant alert
- `DecoyFixtures` — generates fake profile for duress PIN
- `GeofenceEvaluator` — checks position against stored boundaries

### Sonar

- `SonarEngine` — ultrasonic ranging between family devices
- `SonarNotifier` — proximity alerts

### Canary

- `CanaryWorker` — periodic check-in timer
- Dead man's switch with pre-authored message

### Vault

- `VaultCrypto` — encryption behind separate PIN
- `VaultAttachmentCrypto` — encrypted file storage
- `VaultLockStore` — vault PIN and duress PIN management
- Forward-from-chat: any message or attachment can be forwarded into the vault

### Anonymous Groups

Application-layer anonymity on top of SimpleX. Members see rotating pseudonyms, never real identities. Forced perfect erasure on departure — keys, history, mappings all destroyed.

### Backup

- `BackupManager` — local encrypted backup/restore
- `BackupReminderWorker` — periodic reminder notifications

### Widget

- `AegisWidget` — home screen widget for quick status and SOS access

### i18n

- `LanguagePrefs` — multi-language support

## Data Layer

- `AegisDatabase` — Room database, encrypted at rest (SQLCipher)
- `Repository` — single source of truth for messages, contacts, state
- `Entities` / `Daos` — typed database access

## UI Layer

Jetpack Compose. Material 3. LunaGlass design system.

Screens include: ChatList, Chat, GroupChat, SOS, DeviceControl, Map/Radar, Settings, Lock, Profile, Contact detail, Sonar, SecureNotes, Vault, Security (skill tree), Help (in-app docs), and various settings screens.

Components: HoldToExecuteHex (SOS activation), GlassPanel, StatusDot, VoiceRecord, CallIsland, HexShape, ShieldTierBadge.

## Identity & Crypto

- `Identity` / `IdentityStore` — Curve25519 (X25519) key pair, generated on first launch
- `PeerCrypto` — NaCl/libsodium for peer-to-peer encryption
- `LockStore` — PIN management, duress PIN, biometric fallback

## Update System

- `UpdateClient` — polls GitHub releases for new APKs
- `UpdateCheckWorker` — periodic background check (24h)
- Downloads and triggers Android package installer

## Native Dependencies

- `libsimplex.so` — SimpleX core (Haskell, cross-compiled)
- `libsodium.so` — NaCl cryptography
- `libsqlcipher.so` — encrypted SQLite
- `libsupport.so` — Haskell runtime support

## Build

```
./gradlew assembleDebug    # APK
./gradlew bundleDebug      # AAB
```

Requires: JDK 17, Android SDK 35, Haskell cross-compiler (for libsimplex.so).

---

*Part of Project Aether. dε/dt ≤ 0*

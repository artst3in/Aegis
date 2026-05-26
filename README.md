<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=for-the-badge&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple?style=for-the-badge&logo=kotlin" alt="Language">
  <img src="https://img.shields.io/badge/Protocol-SimpleX-blue?style=for-the-badge" alt="Protocol">
  <img src="https://img.shields.io/badge/License-AGPL--3.0-red?style=for-the-badge" alt="License">
</p>

<h1 align="center">Aegis</h1>

<p align="center">
  <strong>Encrypted family communication + emergency response</strong>
</p>

<p align="center">
  <em>Part of Project Aether — the family nervous system</em>
</p>

---

## What Is Aegis

A family security app built around one constraint: if someone in your family is in danger, every other member knows immediately — location, audio, camera — and can act remotely.

All messaging runs through SimpleX. No accounts, no phone numbers, no metadata, no central server.

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                       Aegis                           │
│                                                       │
│  ┌───────────┐  ┌──────────┐  ┌───────────────────┐ │
│  │ Messaging │  │  Panic   │  │  Remote Access    │ │
│  │  + Calls  │  │  System  │  │  (lock/locate/    │ │
│  │           │  │          │  │   siren/wipe)     │ │
│  └─────┬─────┘  └────┬─────┘  └────────┬──────────┘ │
│        │              │                 │            │
│  ┌─────▼──────────────▼─────────────────▼──────────┐ │
│  │              SimpleX Transport                   │ │
│  │     (peer-to-peer, no server, no metadata)       │ │
│  └──────────────────────┬───────────────────────────┘ │
│                         │                             │
│  ┌──────────────────────▼───────────────────────────┐ │
│  │            LAN Transport (fallback)              │ │
│  │       (mDNS discovery on local network)          │ │
│  └──────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

**Kotlin + Jetpack Compose.** ~14,000 lines across ~140 source files. Full LunaGlass design system.

---

## Modules

### Messaging

| Feature | Implementation |
|---------|---------------|
| End-to-end encryption | SimpleX protocol — peer-to-peer, no accounts |
| Media | Photos, voice messages, documents, link previews |
| Voice/video calls | WebRTC via SimpleX signaling (2,351 lines) |
| Controls | Delivery ticks, typing indicators, editing, pinning |
| Privacy | Disappearing messages, scheduled messages, quiet hours |
| Secure notes | Encrypted local notebook |

### Panic System

| Feature | Implementation |
|---------|---------------|
| Hold-to-activate | 3-second edge-heat animation (462-line PanicHandler) |
| Hardware trigger | Power button ×4 in 2s (works with screen off) |
| GPS broadcast | Continuous location to all family members |
| Live stream | WebRTC audio + video via PanicLiveStream |
| Siren | Override silent mode, audible alert |
| Stealth | Screen dims to minimum, device stays unlocked |

### Voyager Mode

Progressive shutdown as battery dies. At 5%: fake dead-battery screen. GPS pings continue once per hour. PowerBudget module manages the degradation curve.

### Remote Access

| Command | Effect |
|---------|--------|
| LOCATE | Lock device + return GPS coordinates |
| SIREN | Trigger audible alarm remotely |
| WIPE | Broadcast goodbye to all contacts, then factory reset |

PIN-authenticated. Duress PIN triggers failure counter + silent notification. 1,016 lines.

### Loki Toolkit

| Feature | Implementation |
|---------|---------------|
| Mugshot | 3 failed PIN attempts → front camera captures attacker's face, sends to family |
| SIM swap detection | Monitors SIM state changes, instant alert on swap |
| Decoy profile | Duress PIN opens fake clean profile, silently triggers panic |

### Sonar

Acoustic proximity detection using speaker and microphone. 485-line engine for ultrasonic ranging between family devices.

### Canary

Dead man's switch. If the user doesn't open Aegis within a configurable interval, fires a pre-authored message to all paired peers. CanaryWorker runs as periodic background task.

### Geofence

Define zones (school, home, work). Alerts when family members enter or leave. GeofenceEvaluator checks position against stored boundaries.

---

## Transport Layers

| Transport | Status | Use case |
|-----------|--------|----------|
| **SimpleX** | Primary, functional | All messaging and panic over public internet |
| **LAN** | Functional | Local network fallback via mDNS discovery |
| **Matrix** | Planned | Via Luna homeserver (requires LunaOS) |

---

## Status

Internal testing. Source code will be released when stable.

---

## Project Aether

| Component | Role | Repository |
|-----------|------|------------|
| **LunaOS** | The Brain — consciousness OS, PI, inference engine | [artst3in/LunaOS](https://github.com/artst3in/LunaOS) |
| **Aegis** | The Shield — encrypted family communication | [artst3in/Aegis](https://github.com/artst3in/Aegis) |
| **LunaGlass** | The Body — hardware interface layer | Coming soon |

The **Superfield** is the cosmic aether — the superfluid vacuum that carries all waves in reality. **Project Aether** is the family aether — the encrypted medium that carries all signals in the family. Same architecture, different scale.

---

## License

**AGPL-3.0** — see [LICENSE](LICENSE).

Aegis incorporates [SimpleX Chat](https://simplex.chat/) (AGPL-3.0). See [ATTRIBUTION](ATTRIBUTION-SimpleX.md).

---

<div align="center">

🛡️

<em>Everything is light.</em>

**dε/dt ≤ 0**

</div>

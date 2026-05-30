<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=for-the-badge&logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple?style=for-the-badge&logo=kotlin" alt="Language">
  <img src="https://img.shields.io/badge/Protocol-SimpleX-blue?style=for-the-badge" alt="Protocol">
  <img src="https://img.shields.io/badge/License-AGPL--3.0-red?style=for-the-badge" alt="License">
</p>

<h1 align="center">Aegis</h1>

<p align="center">
  <strong>Encrypted communication. Emergency response. Personal security.</strong>
</p>

<p align="center">
  <em>Part of Project Aether</em>
</p>

---

## What Is Aegis

A personal security app built around one principle: if someone you protect is in danger, everyone in their circle knows immediately — location, audio, camera — and can act remotely.

All messaging runs through SimpleX. No accounts, no phone numbers, no metadata, no central server.

Aegis is for anyone with people they care about — partners, children, parents, close friends, trusted colleagues. The trust model handles the rest.

---

## Install

Download the latest APK from [Releases](https://github.com/artst3in/Aegis/releases).

Settings → Install unknown apps → Allow → Install.

Recommended: GrapheneOS or stock Android 10+.

---

## Trust Model

Every contact belongs to one of three tiers. One label per person. The label decides everything.

| Tier | Routine sharing | Panic alerts | Visibility |
|------|----------------|--------------|------------|
| **Trusted** | Location, presence, status, sensors | Yes | Full profile + shield tier badge |
| **Emergency** | Nothing | Yes | Alert-only placeholder |
| **Untrusted** | Nothing | No | Minimal |

**Trusted** is not "people I trust generally." It is the small set who actively want your daily location and you want them to have it. Partners, parents of young children, close friends who co-monitor. Usually under a dozen people.

**Emergency** is someone who should know if you are in danger but does not need your daily data. A doctor, a neighbor with your spare key, a reliable colleague.

**Untrusted** is everyone else. They can message you. They see nothing about your status or location.

No per-feature toggles. No granular controls. The tier is the decision.

---

## Shield Tiers

Security is gamified. Your shield tier is a colored frame around your avatar, visible to everyone in your circle.

| Tier | Frame | Requirement |
|------|-------|-------------|
| — | None | Nothing configured |
| Bronze | Bronze | App PIN set |
| Silver | Silver | 2–8 security features enabled |
| Gold | Gold | All 9 in-app features enabled |
| Cyan | Cyan | Gold + Device Owner (factory reset + ADB) |

Cyan is the Aegis brand color — the highest tier. It means the device was factory-reset, rebuilt around Aegis, and every security node is active. Disabling any feature drops the tier immediately.

---

## Skill Tree

Ten security features arranged in a dependency tree. Each feature requires its prerequisites.

| Node | Function | Requires |
|------|----------|----------|
| **App PIN** | Locks the app | Nothing |
| **App Duress** | Fake PIN opens a clean decoy profile and triggers silent panic | App PIN |
| **Mugshot** | Three wrong PINs trigger front camera capture, sent to Trusted contacts | App PIN |
| **Vault PIN** | Separate encrypted storage behind its own PIN | App PIN |
| **Vault Duress** | Hidden vault behind a second fake PIN | Vault PIN |
| **Canary** | Dead man's switch — no check-in within the set interval triggers an alert | App PIN |
| **Geofence** | Zone alerts when contacts enter or leave defined areas | App PIN |
| **SIM Watch** | SIM swap detection with instant alert | App PIN |
| **Panic Drill** | One-time test of the panic system with one paired contact | App PIN + 1 contact |
| **Device Owner** | Factory reset + ADB provisioning. Cannot be set in-app. | External setup |

---

## Messaging

End-to-end encrypted text, photos, voice messages, and documents. No accounts, no phone numbers, no metadata. All transport via SimpleX protocol.

| Feature | Details |
|---------|---------|
| Encryption | SimpleX protocol — peer-to-peer, no central server |
| Media | Photos, voice messages, documents |
| Calls | Voice and video via WebRTC over SimpleX signaling |
| Controls | Delivery receipts, typing indicators, editing, pinning |
| Privacy | Disappearing messages, scheduled messages, quiet hours |
| Storage | Encrypted local notebook (Secure Notes) |
| Groups | Standard (visible identity) and Anonymous (see below) |

---

## Anonymous Groups

Chat rooms where members cannot determine each other's real identities. Each member appears as a rotating pseudonym. Display names, SimpleX addresses, and profile data are never shared.

**Protected against:**
- External observers cannot enumerate members
- Members cannot learn each other's real identities
- A seized device reveals no other members' addresses or names
- Leaving the group triggers forced perfect erasure — message history, keys, and pseudonym mappings are destroyed. The device becomes indistinguishable from one that was never in the group.

**Not protected against:**
- The group creator knows all members' relay addresses. This is the honest trade-off for a serverless design.

Anonymous groups are chat only. No location, presence, status, or panic data flows through them.

---

## Panic System

| Feature | Details |
|---------|---------|
| Hold-to-activate | Three-second press with progressive edge-heat animation |
| Hardware trigger | Power button ×4 rapid press (works with screen off, from pocket) |
| GPS broadcast | Continuous location to all Trusted and Emergency contacts |
| Live stream | Encrypted audio and video via WebRTC |
| Siren | Overrides silent mode and volume settings |
| Stealth | Screen dims to minimum; device stays active |
| Radar | Real-time positions of all Trusted contacts on a map |

### Voyager Mode

Progressive shutdown as battery depletes. At 5%: a fake dead-battery screen replaces the display. GPS transmissions continue once per hour — days of location pings from a phone that appears dead.

---

## Duress PIN

Two PINs. The real one opens Aegis. The duress PIN opens a clean decoy profile and silently triggers panic to every Trusted and Emergency contact.

The second PIN's existence is cryptographically unprovable. An attacker who forces you to unlock the device sees a normal-looking messenger with decoy contacts and no alerts — while the real contacts are already receiving your location and the silent alarm.

---

## Loki Toolkit

| Feature | Details |
|---------|---------|
| **Mugshot** | Three failed PIN attempts trigger front camera capture, sent to Trusted contacts |
| **SIM swap alert** | Monitors SIM state changes; instant notification on swap |
| **Canary** | Dead man's switch with configurable interval and pre-authored message |
| **Geofence** | Zone-based alerts for arrivals and departures |
| **Decoy fixtures** | Synthetic contacts and conversation history for the duress profile |
| **Remote commands** | PIN-authenticated: locate, siren, lock, wipe |

### Remote Commands

| Command | Effect |
|---------|--------|
| LOCATE | Lock device, return GPS coordinates |
| SIREN | Trigger audible alarm remotely |
| WIPE | Broadcast notification to all contacts, then factory reset |

Entering the duress PIN on a remote command triggers a failure counter and silent notification.

---

## Sonar

Acoustic proximity detection. Uses the phone's speaker and microphone for ultrasonic ranging between paired devices. Determines who is physically nearby without GPS — works indoors, through walls, in environments where satellite positioning is unavailable.

---

## Vault

Encrypted storage behind its own PIN, independent from the app PIN. Sensitive documents, photos, and notes are protected by a separate encryption key derived from the vault PIN. The vault also supports its own duress PIN.

---

## Privacy

- No accounts. No phone numbers. No email.
- No servers you do not control. SimpleX uses disposable relay addresses.
- No metadata collection. SimpleX relays do not know who communicates with whom.
- No cloud storage. All data remains on-device.
- No analytics. No tracking. No advertising.
- Panic data travels through the same encrypted channels as messages. No third party receives it.

---

## Design

Aegis uses **LunaGlass** — the Project Aether design system.

- Dark backgrounds with cyan accents (#00FFFF)
- Flat-top hexagonal UI elements
- Inter typeface
- Edge-heat animation on hold-to-execute controls
- Shield tier badges on avatars

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                         Aegis                            │
│                                                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐  │
│  │Messaging │  │  Panic   │  │  Remote  │  │  Trust  │  │
│  │ + Calls  │  │  System  │  │  Access  │  │  Model  │  │
│  │ + Groups │  │ + Radar  │  │          │  │         │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬────┘  │
│       │              │             │              │       │
│  ┌────▼──────────────▼─────────────▼──────────────▼───┐  │
│  │              SimpleX Transport                     │  │
│  │       (peer-to-peer, no server, no metadata)       │  │
│  └────────────────────────┬───────────────────────────┘  │
│                           │                              │
│  ┌────────────────────────▼───────────────────────────┐  │
│  │            LAN Transport (fallback)                │  │
│  │         (mDNS discovery on local network)          │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  ┌──────────────────────────────────────────────────┐    │
│  │  Loki: Mugshot│SIM Watch│Canary│Geofence│Decoy  │    │
│  └──────────────────────────────────────────────────┘    │
│  ┌──────────────────────────────────────────────────┐    │
│  │  Security: Skill Tree│Shield Tiers│Vault│Sonar  │    │
│  └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
```

Kotlin + Jetpack Compose. Full LunaGlass design system. Android 10+ (GrapheneOS recommended).

---

## Versioning

```
YYYY.MM.BUILD — e.g., 2026.05.613
```

Year and month for temporal context. Build number increments per release. No semantic judgment. One number to bump. See [VERSIONING](docs/VERSIONING.md) for the full specification.

---

## Project Aether

| Component | Role | Repository |
|-----------|------|------------|
| **LunaOS** | The Brain — consciousness operating system | [artst3in/LunaOS](https://github.com/artst3in/LunaOS) |
| **Aegis** | The Shield — encrypted communication and personal security | [artst3in/Aegis](https://github.com/artst3in/Aegis) |
| **LunaGlass** | The Design — hardware interface and visual language | [artst3in/LunaGlass](https://github.com/artst3in/LunaGlass) |

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

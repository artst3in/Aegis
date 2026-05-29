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

## Install

Download the latest APK from [Releases](https://github.com/artst3in/Aegis/releases):

Settings → Install unknown apps → Allow → Install.

Recommended: GrapheneOS or stock Android 10+.

---

## Trust Model

Every contact belongs to one of three tiers. One label per person. The label decides everything.

| Tier | Routine sharing | Panic alerts | Visibility |
|------|----------------|--------------|------------|
| **Trusted** | Location, presence, status, sensors | Yes | Full profile + shield tier badge |
| **Emergency** | Nothing | Yes | Red "!" placeholder |
| **Untrusted** | Nothing | No | Mask icon |

**Trusted** is not "people I trust" — it's the small set who actively want your daily location and you want them to have it. Spouse, parents of young children, close co-monitoring family. Usually under a dozen.

**Emergency** is the doctor, the neighbor with your spare key, the reliable colleague. People who should be alerted in a real crisis but who don't need your daily battery level.

**Untrusted** is everyone else. They can message you. They know nothing about your status or location.

No per-feature toggles. No granular controls. The tier IS the decision.

---

## Shield Tiers

Security is gamified. Your shield tier is a colored frame around your avatar, visible to all family members.

| Tier | Frame | Requirement |
|------|-------|-------------|
| — | None | Nothing configured |
| 🥉 | **Bronze** | App PIN set |
| 🥈 | **Silver** | 2-8 security features enabled |
| 🥇 | **Gold** | All 9 in-app features enabled |
| 🔷 | **Cyan** | Gold + Device Owner (factory reset + ADB) |

Cyan is the Aegis brand color — the crown. It means you wiped your phone, rebuilt it around Aegis, and maxed every security node. Disabling any feature drops your tier immediately.

---

## Skill Tree

Ten security features arranged in a dependency tree. Each feature requires its prerequisites.

```
                     💎 CYAN
                  (all 10 nodes)
              /    |    |    |    \
          Canary  Geo  SIM  Panic  Vault
            |      |    |   Drill  Duress
            |      |    |    |      |
          App    App  App   App   Vault
         Duress  PIN  PIN   PIN    PIN
            \      |    |   /      |
             \     |    |  /       |
              ┌────┴────┴─┴───┐    |
              │    APP PIN    │────┘
              └───────────────┘
```

| Node | What it does | Gate |
|------|-------------|------|
| **App PIN** | Locks the app | None — always first |
| **App Duress** | Fake PIN → clean fake profile + silent panic | App PIN |
| **Mugshot** | 3 wrong PINs → front camera captures attacker's face → sent to family | App PIN |
| **Vault PIN** | Separate encrypted vault for sensitive files | App PIN |
| **Vault Duress** | Hidden vault behind a second fake PIN | Vault PIN |
| **Canary** | Dead man's switch — no check-in within interval → alert fires | App PIN |
| **Geofence** | Zone alerts when family enters/leaves defined areas | App PIN |
| **SIM Watch** | SIM swap detection → instant alert | App PIN |
| **Panic Drill** | Test panic system with one paired contact. Once, done forever. | App PIN + 1 contact |
| **Device Owner** | Factory reset + ADB setup. Proves phone is yours from boot. | Cannot be set in-app |

---

## Messaging

End-to-end encrypted text, photos, voice messages, and documents. No accounts, no phone numbers, no metadata — all via SimpleX protocol.

| Feature | Details |
|---------|---------|
| Encryption | SimpleX protocol — peer-to-peer, no central server |
| Media | Photos, voice messages, documents, link previews |
| Voice/video calls | WebRTC via SimpleX signaling |
| Controls | Delivery ticks, typing indicators, editing, pinning |
| Privacy | Disappearing messages, scheduled messages, quiet hours |
| Secure notes | Encrypted local notebook |
| Groups | Regular (identity-visible) and Anonymous (see below) |

---

## Anonymous Groups

Chat rooms where members don't know each other's real identities. Each member appears as a rotating pseudonym (e.g., `Mask-7F3A2`). The underlying SimpleX addresses and display names are never shared.

**What's protected:**
- External observer can't enumerate members (SimpleX metadata protection)
- Member A can't learn member B's real identity (application-layer anonymity)
- Seized device reveals no other members' addresses or names
- Leaving the group triggers forced perfect erasure — message history, attachments, keys, and pseudonym mappings are wiped. The device becomes indistinguishable from one that was never in the group.

**What's not protected:**
- The group creator runs the routing layer and knows all members' addresses. This is the honest trade-off for a no-server design.

Anonymous groups are **chat only** — no location, presence, status, or panic data flows through them.

---

## Panic System

| Feature | Details |
|---------|---------|
| Hold-to-activate | 3-second press with edge-heat animation (can't trigger accidentally) |
| Hardware trigger | Power button ×4 rapid press (works with screen off) |
| GPS broadcast | Continuous location to all Trusted + Emergency contacts |
| Live stream | Audio + video to family via encrypted WebRTC |
| Siren | Overrides silent mode |
| Stealth | Screen dims to minimum, device stays unlocked |
| Radar | Real-time family positions on a map during and after panic |

### Voyager Mode

Progressive shutdown as battery dies. At 5%: fake dead-battery screen. GPS pings continue once per hour — days of ghost transmissions on a "dead" phone.

---

## Duress PIN

Two PINs. The real one opens Aegis. The duress PIN opens a clean fake profile and silently triggers panic to your family.

The second PIN's existence is cryptographically unprovable. An attacker who forces you to unlock sees a normal-looking app with fake contacts and no alerts — while your real family is already receiving your location and the silent alarm.

---

## Loki Toolkit

| Feature | Details |
|---------|---------|
| **Mugshot** | 3 failed PIN attempts → front camera captures attacker → photo sent to family |
| **SIM swap alert** | Monitors SIM state changes, instant notification on swap |
| **Canary** | Dead man's switch — configurable interval, pre-authored message |
| **Geofence** | Zone-based alerts for family arrivals and departures |
| **Decoy fixtures** | Fake contacts and conversations for the duress profile |
| **Remote commands** | PIN-authenticated: locate, siren, lock, wipe |

### Remote Commands

| Command | Effect |
|---------|--------|
| LOCATE | Lock device + return GPS |
| SIREN | Trigger audible alarm remotely |
| WIPE | Broadcast goodbye to all contacts, then factory reset |

Duress PIN on remote commands triggers failure counter + silent notification to family.

---

## Sonar

Acoustic proximity detection. Uses speaker and microphone for ultrasonic ranging between family devices. Know who's physically near without GPS — works indoors, through walls.

---

## Vault

Encrypted storage with its own PIN, separate from the app PIN. Store sensitive documents, photos, and notes behind a second layer of encryption. Vault also supports its own duress PIN — opening the hidden vault behind a fake vault.

---

## Privacy

- No accounts. No phone numbers. No email.
- No servers you don't control. SimpleX uses disposable relays.
- No metadata collection. SimpleX doesn't know who talks to whom.
- No cloud. Everything stays on-device.
- No analytics. No tracking. No ads.
- No post-quantum cryptography — because the quantum computer that breaks classical crypto will never be built. The threat model is fictional.
- Panic data goes only to your family members, through the same encrypted channel.

---

## Design

Aegis uses **LunaGlass** — the Project Aether design system.

- Dark backgrounds, cyan accents (#00FFFF)
- Flat-top hexagonal UI elements
- Inter font
- Edge-heat animation on hold-to-execute buttons
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

**Kotlin + Jetpack Compose.** Full LunaGlass design system. Android 10+ (GrapheneOS recommended).

---

## Versioning

```
YYYY.MM.BUILD — e.g., 2026.05.478
```

Year and month for temporal context. Build number increments. Zero semantic judgment. One number to bump. See [VERSIONING](docs/VERSIONING.md).

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

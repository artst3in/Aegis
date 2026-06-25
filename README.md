<div align="center">

<img src="https://raw.githubusercontent.com/artst3in/Aegis/main/metadata/en-US/images/aegis_brand_logo.jpg" width="400" alt="Aegis" />

# Aegis

**Encrypted communication. Emergency response. Personal security.**

Part of [Project Aether](https://github.com/artst3in).

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL%203.0-blue.svg)](LICENSE)
[![Status: Alpha](https://img.shields.io/badge/Status-Alpha-orange.svg)]()
[![Android 10+](https://img.shields.io/badge/Android-10%2B-green.svg)]()

</div>

---

<p align="center">
  <img src="https://raw.githubusercontent.com/artst3in/Aegis/main/metadata/en-US/images/phoneScreenshots/01_onboarding.png" width="220" alt="Onboarding" />
  <img src="https://raw.githubusercontent.com/artst3in/Aegis/main/metadata/en-US/images/phoneScreenshots/02_sos.png" width="220" alt="SOS" />
  <img src="https://raw.githubusercontent.com/artst3in/Aegis/main/metadata/en-US/images/phoneScreenshots/04_comms.png" width="220" alt="Communication" />
</p>

---

## What is Aegis?

Aegis is a personal security app for Android. It combines encrypted messaging, emergency response, and device protection into a single application. No accounts. No phone numbers. No metadata. No ads. No telemetry. Free forever.

Transport is built on [SimpleX](https://simplex.chat/) — the only messenger protocol with no user identifiers of any kind.

## Quick Start

### 1. Install

Download the APK from the releases page. Two variants:
- **arm64** — Pixel, Samsung, OnePlus, most phones after 2018
- **armv7** — Infinix, Tecno, budget devices, older hardware

On your phone: Settings → Apps → Install unknown apps → Allow your browser. Open the APK, tap Install, launch Aegis.

### 2. Create your identity

Aegis generates a cryptographic key pair on first launch. There is no account, no password, and no phone number. Your identity is your key.

### 3. Set your App PIN

This is the root of your security. Every other feature depends on it. This PIN is separate from your device PIN.

### 4. Add contacts

Share your invite link as a QR code or text. The other person opens it in their copy of Aegis. A direct encrypted channel is established with no server involvement.

### 5. Assign trust tiers

| Tier | Routine data | SOS alerts |
|------|-------------|------------|
| **Trusted** | Location, presence, status, battery | Yes |
| **Emergency** | None | Yes |
| **Untrusted** | None | No |

**Trusted** — partners, parents, close friends who actively want your daily location.
**Emergency** — people who should know in a crisis but don't need routine data.
**Untrusted** — everyone else. They can message you. They see nothing beyond that.

### 6. Set up SOS

Open Settings → SOS. Enable the hardware trigger (power button ×4). Run the SOS Drill with one paired contact. The SOS button is always accessible from the navigation bar.

## Shield Tiers

Your security level is displayed as a colored frame around your avatar, visible to all contacts.

| Tier | Requirement |
|------|-------------|
| None | Nothing configured |
| Bronze | App PIN set |
| Silver | 2–9 security features enabled |
| Gold | All 10 in-app features enabled |
| Cyan | Gold + Device Owner (factory reset + ADB setup) |

Open Settings → Security to view your skill tree and begin progressing.

## Features

### Communication
- End-to-end encrypted messaging — text, voice notes, photos, video, files
- Voice and video calls (WebRTC, peer-to-peer)
- Anonymous identity (Aegis Protocol — no phone number or email)
- Anonymous groups (no member lists exposed to the server)
- Scheduled messages and notification privacy controls

### Emergency Response
- SOS broadcast — GPS location, live audio, live camera, siren, all at once
- Hardware trigger — 4× power button press, works with screen off
- Duress PIN — enters a decoy profile while silently activating SOS
- Crash detection — accelerometer-based vehicle crash detection with automatic SOS
- Canary — dead-man's switch, alerts contacts if you stop checking in

### Device Protection
- Sentinel — covert intrusion detection with evidence capture
- Remote access hub — locate, lock, wipe, siren, live camera, live mic, watch mode
- Mugshot capture — photographs failed unlock attempts
- SIM swap detection — alerts when SIM changes
- Snatch detection — detects device being grabbed from hand
- Sonar — ultrasonic proximity detection (speaker + microphone)

### Privacy & Encryption
- 24-word recovery phrase (BIP39) generates seal keypair
- TEE-wrapped key storage (hardware-backed)
- Argon2id for PIN verification and backup encryption
- Encrypted vault with separate duress PIN
- Lock curtain — hides content when device is locked
- Ephemeral profiles — temporary identities that self-destruct

### Design
- Every UI decision follows Hick's Law, Miller's 7±2, and Fitts's Law
- LunaGlass design system
- No top-level list exceeds 9 items
- SOS centered in navigation — in a crisis you stab the middle of the screen

## How Encryption Works

Aegis uses Argon2id to process your password. Instead of 10 billion guesses per second, an attacker gets 20. Not 20 million. Not 20 thousand. Twenty.

A 12-character password with Aegis: **150 million years** to crack using every computer on Earth.

Your 24-word recovery phrase generates a seal keypair stored in your device's Trusted Execution Environment (TEE). The phrase is the only backup. Lose it, and your data is gone forever. This is by design — if you can't recover it, neither can anyone else.

## Recommended Setup

For maximum protection:
- GrapheneOS on a Pixel phone (hardened Android, no Google services)
- Device Owner mode (enables Cyan shield tier)
- Full-disk encryption (enabled by default on modern Android)
- Strong device PIN (not biometric alone)

Aegis runs on any Android 10+ device. GrapheneOS is recommended, not required.

## Stats

| | |
|---|---|
| Kotlin files | 360 |
| Lines of code | ~113,000 |
| Screens | 61 |
| UI strings | 715 |
| Languages | 16 |

## Languages

Arabic, Chinese (Simplified), Dutch, English, French, German, Hindi, Italian, Japanese, Korean, Polish, Portuguese, Russian, Spanish, Swahili, Turkish, Ukrainian.

## Troubleshooting

**Messages not sending.** Check internet connectivity. SimpleX requires internet to reach relays. Messages queue locally and send when connectivity returns.

**SOS not triggering.** Confirm the hardware trigger is enabled in Settings → SOS. Test with the in-app SOS button first (hold one second).


## Build

See [BUILD.md](BUILD.md).

## Documentation

- [Documentation Index](docs/INDEX.md)
- [Privacy & Security Policy](docs/PRIVACY.md)
- [Data Safety](docs/DATA_SAFETY.md)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Security

Found a vulnerability? See [SECURITY.md](SECURITY.md).

## License

AGPL-3.0. See [LICENSE](LICENSE) and [ATTRIBUTION-SimpleX.md](ATTRIBUTION-SimpleX.md).

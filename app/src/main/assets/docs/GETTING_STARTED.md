# Getting Started

## Install

1. Download the APK from the Aegis releases page. Two variants are available:
   - **arm64** — Pixel, Samsung, OnePlus, and most phones manufactured after 2018
   - **armv7** — Infinix, Tecno, budget devices, and older hardware

2. On your phone: Settings → Apps → Install unknown apps → Allow your browser.

3. Open the downloaded APK and tap Install.

4. Launch Aegis.

## First Launch

### 1. Create your identity

Aegis generates a cryptographic key pair on first launch. There is no account, no password, and no phone number. Your identity is your key.

### 2. Set your App PIN

This is the root of your security. Every other feature in the skill tree depends on it. Choose a PIN you will not forget. This PIN is separate from your device PIN.

### 3. Add contacts

Share your invite link — as a QR code or as text. The other person opens the link in their copy of Aegis. A direct encrypted channel is established with no server involvement.

### 4. Assign trust tiers

Every contact receives one of three tiers:

| Tier | Routine data | SOS alerts |
|------|-------------|--------------|
| **Trusted** | Location, presence, status, battery | Yes |
| **Emergency** | None | Yes |
| **Untrusted** | None | No |

**Trusted** is for the people who actively want your daily location and you want them to have it — partners, parents, close friends.

**Emergency** is for people who should be alerted in a crisis but do not need your routine data — a doctor, a neighbor, a reliable colleague.

**Untrusted** is everyone else. They can message you. They see nothing beyond that.

## Shield Tiers

Your security level is displayed as a colored frame around your avatar, visible to all contacts in your circle.

| Tier | Requirement |
|------|-------------|
| None | Nothing configured |
| Bronze | App PIN set (you are here after step 2) |
| Silver | 2–9 security features enabled |
| Gold | All 10 in-app features enabled |
| Cyan | Gold + Device Owner (factory reset + ADB setup) |

Open Settings → Security to view your skill tree and begin progressing.

## SOS Setup

1. Open Settings → SOS.
2. Enable the hardware trigger (power button ×4).
3. Run the SOS Drill with one paired contact — one real test, completed permanently.
4. The SOS button is always accessible from the navigation bar.

## Duress PIN

1. Open Settings → Security → Duress PIN.
2. Set your real PIN and your duress PIN.
3. Real PIN opens Aegis normally.
4. Duress PIN opens a clean decoy profile while silently activating SOS.
5. The second PIN's existence is cryptographically unprovable.

## Connecting Contacts

Each person installs Aegis independently. To connect:

1. Open your profile to display your QR code.
2. The other person scans it or pastes the invite link.
3. SimpleX establishes an encrypted channel.
4. Assign the appropriate trust tier.

No server registration. No phone number exchange. The QR code is the connection.

## Recommended Device Configuration

For maximum protection:

- GrapheneOS on a Pixel phone (hardened Android, no Google services)
- Device Owner mode (enables Cyan shield tier)
- Full-disk encryption (enabled by default on modern Android)
- Strong device PIN (not biometric alone)

Aegis runs on any Android 10+ device. GrapheneOS is recommended, not required.

## Troubleshooting

**Messages not sending.** Verify internet connectivity. SimpleX requires internet to reach relays. If relays are temporarily unavailable, messages queue locally and send when connectivity returns.

**SOS not triggering.** Confirm that the hardware trigger is enabled in Settings → SOS. Test with the in-app SOS button first (hold three seconds).

**App crashing.** Export logs via Settings → Debug → Export logs and report the issue.

---

*Part of Project Aether.*

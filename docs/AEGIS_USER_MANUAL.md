# Aegis — User Manual

## 1. Overview

Aegis is an encrypted communication app with an integrated emergency response system. All messaging runs through the SimpleX protocol — no accounts, no phone numbers, no metadata, no central server. If someone in your circle is in danger, one activation broadcasts their GPS, audio, and camera to every authorized contact.

## 2. Installation

### 2.1 Download

Download the APK from the Aegis GitHub releases page. Two variants are available:

- **arm64** — modern devices (Pixel, Samsung, OnePlus, most phones manufactured after 2018)
- **armv7** — budget and older devices (Infinix, Tecno, older hardware)

### 2.2 Install

Settings → Apps → Install unknown apps → Allow your browser → Open the APK → Install.

### 2.3 Permissions

Aegis requests permissions progressively as you enable features:

- **Location** — for location sharing and SOS GPS broadcast
- **Camera** — for SOS live stream and mugshot capture
- **Microphone** — for voice messages, calls, and SOS audio stream
- **Notifications** — for message and SOS alerts
- **Phone** — for SIM swap detection
- **Battery optimization exempt** — to maintain background services for SOS and canary

### 2.4 Recommended device configuration

- Android 10 or later (required)
- GrapheneOS on Pixel (recommended, not required)
- Device Owner mode for maximum protection (requires factory reset + ADB)
- Battery optimization disabled for Aegis

## 3. Trust Model

Every contact in Aegis belongs to one of three tiers. The tier determines all data flow — location sharing, presence data, SOS alerts, and profile visibility.

### 3.1 Trusted

The people who actively want your routine data and you want them to have it. Partners, parents of young children, close friends who co-monitor. Usually under a dozen people.

Receives: location updates, presence, status, sensor data, battery level, SOS alerts. Sees your full profile with shield tier badge. Can message, call, and participate in groups.

### 3.2 Emergency

People who should be alerted in a genuine crisis but do not need your daily data. A doctor, a neighbor with your spare key, a trusted colleague.

Receives: SOS alerts only. Sees an alert-only placeholder instead of your profile. Cannot see your location, status, or battery.

### 3.3 Untrusted

Everyone else. They can message you. They have no visibility into your status or location. No SOS alerts.

### 3.4 Changing tiers

Open the contact's profile → tap the tier badge → select the new tier. Changes take effect immediately.

## 4. Shield Tiers

Your security level is displayed as a colored frame around your avatar, visible to all contacts in your circle.

| Tier | Frame | Nodes Required |
|------|-------|---------------|
| — | None | 0 |
| Bronze | Bronze | 1 (App PIN) |
| Silver | Silver | 2–9 |
| Gold | Gold | 10 (all in-app) |
| Cyan | Cyan | 11 (Gold + Device Owner) |

### 4.1 Progressing

Open Settings → Security to view your skill tree. Each node represents a security feature. Enable features to advance your tier.

### 4.2 Tier behavior

- The frame is visible to all contacts in your circle.
- Updates in real time as nodes are enabled or disabled.
- Disabling a node drops the tier immediately.
- No timers, no decay, no arbitrary requirements.

### 4.3 Cyan

Cyan is the Aegis brand color and the highest tier. It requires a factory-reset device provisioned through ADB with every security node active.

## 5. Skill Tree

Ten security features arranged in a dependency tree.

| Node | Function | Requires |
|------|----------|----------|
| App PIN | Locks the app | Nothing |
| App Duress | Fake PIN opens decoy profile + silent SOS | App PIN |
| Mugshot | Three wrong PINs trigger front camera capture, sent to Trusted contacts | App PIN |
| Vault PIN | Separate encrypted storage | App PIN |
| Vault Duress | Hidden vault behind a second fake PIN | Vault PIN |
| Canary | Dead man's switch | App PIN |
| Geofence | Zone alerts for arrivals and departures | App PIN |
| SIM Watch | SIM swap detection | App PIN |
| SOS Drill | One-time test with one paired contact | App PIN + 1 contact |
| Device Owner | Factory reset + ADB. Cannot be set in-app. | External setup |

### 5.1 Dependency logic

Every node requires App PIN. You cannot enable mugshot without a PIN — no PIN means no failed attempts to trigger it. You cannot enable geofence without a PIN — an attacker could open the app and disable zones. The dependencies exist for security, not complexity.

## 6. Messaging

### 6.1 Sending messages

Tap a contact from the chat list. Type your message. Send. All messages are end-to-end encrypted via SimpleX.

### 6.2 Attachments

Tap the attachment drawer to send photos, documents, voice messages, or your current location.

### 6.3 Voice messages

Hold the microphone button to record. Release to send. Swipe left to cancel.

### 6.4 Message features

- **Delivery receipts** — sent, delivered, read.
- **Typing indicators** — displayed when the other person is composing (requires both parties to run Aegis).
- **Edit** — tap a sent message to modify it.
- **Pin** — long-press to pin important messages.
- **Disappearing messages** — set a timer; messages auto-delete after the interval on both devices.
- **Scheduled messages** — compose now, send at a specified time.
- **Forward to vault** — save any message or attachment to your encrypted vault.

### 6.5 Voice and video calls

Tap the phone or video icon in a chat. Calls use WebRTC via SimpleX signaling — encrypted and peer-to-peer. Audio routes to earpiece, speaker, or Bluetooth automatically.

### 6.6 Secure notes

A private encrypted notebook accessible from the main menu. Notes remain on-device, protected by your app PIN.

### 6.7 Quiet hours

Settings → Notifications → Quiet Hours. Messages arrive normally but notifications are silenced during the configured period. SOS alerts always override quiet hours.

## 7. Groups

### 7.1 Standard groups

Group conversations where members see each other's display names and profiles. Create from the main menu.

### 7.2 Anonymous groups

Chat rooms where members cannot determine each other's real identities. Each member appears as a rotating pseudonym.

Protected against:
- External observers cannot enumerate members.
- Members cannot learn each other's real identities.
- A seized device reveals no other members' information.
- Leaving triggers forced perfect erasure — history, keys, and mappings are destroyed.

Not protected against:
- The group creator knows all members' relay addresses (trade-off for serverless design).

Anonymous groups support text only — no location, presence, or SOS data.

## 8. Dashboard

### 8.1 Status

All Trusted contacts at a glance: name, shield tier badge, online status, battery level, last seen time. Cross-timezone clocks display each contact's local time.

### 8.2 Radar

Real-time map displaying Trusted contacts' positions. Shows only verified GPS positions.

## 9. SOS

### 9.1 Activation

Two methods:

- **In-app:** Hold the SOS button for three seconds. The edge-heat animation confirms progress.
- **Hardware:** Press the power button four times rapidly within two seconds. Works with the screen off.

### 9.2 During SOS

1. GPS broadcasts continuously to all Trusted and Emergency contacts.
2. Audio streams in sixty-second encrypted segments.
3. Camera frames transmit from front and rear cameras.
4. A siren sounds (overrides silent mode and volume settings).
5. The radar shows real-time positions of all Trusted contacts.

### 9.3 Voyager Mode

Progressive shutdown as battery depletes. At 5%: a fake dead-battery screen replaces the display. GPS transmissions continue once per hour — extending location tracking for days on a phone that appears powered off.

## 10. Duress PIN

Two PINs for the app. The real PIN opens Aegis normally. The duress PIN opens a decoy profile with synthetic contacts and plausible message history, while silently activating SOS.

The second PIN's existence is cryptographically unprovable. An attacker who forces unlock sees a normal messenger. Your Trusted contacts see the alarm.

## 11. Vault

Encrypted storage behind a separate PIN, independent from the app PIN. Store sensitive documents, photos, and notes behind a second encryption layer.

The vault supports its own duress PIN — a third layer that presents a fake vault while alerting your contacts.

Forward any message or attachment from a chat directly into the vault.

## 12. Burn after Reading

Send messages that auto-delete from both devices when the recipient closes the viewer. Configurable timer. The message exists only long enough to be read.

## 13. Loki Toolkit

### 13.1 Mugshot

Three incorrect PIN attempts trigger a silent front camera capture. The photo is sent to Trusted contacts. The person entering the wrong PIN receives no indication.

### 13.2 SIM Watch

Monitors the device's SIM state. Any change to the carrier or ICCID triggers an immediate alert to Trusted contacts.

### 13.3 Canary

A dead man's switch with a configurable interval. If you do not open Aegis within the interval, a pre-authored message is sent to your contacts.

### 13.4 Geofence

Define zones with coordinates and radius. Receive alerts when contacts enter or leave defined areas. Zones are stored locally.

### 13.5 Decoy fixtures

The synthetic contacts and conversations displayed when the duress PIN is entered. Generated to appear plausible — normal names, realistic message history.

### 13.6 Remote commands

PIN-authenticated commands that contacts can send to each other's devices:

| Command | Effect |
|---------|--------|
| LOCATE | Lock device and return GPS coordinates |
| SIREN | Trigger audible alarm remotely |
| WIPE | Broadcast notification to all contacts, then factory reset |

Entering the duress PIN on a remote command triggers a failure counter and silent notification.

## 14. Sonar

Ultrasonic proximity detection between paired devices. Uses the phone's speaker and microphone for acoustic ranging. Determines who is physically nearby without GPS — operates indoors, through walls, in environments where satellite positioning is unavailable.

## 15. Settings

### 15.1 Profile

Display name and avatar. Your shield tier badge appears on your avatar automatically.

### 15.2 Security

Skill tree view with all ten nodes. Each toggleable with dependency enforcement.

### 15.3 SOS

SOS button configuration, hardware trigger toggle, siren settings, Voyager Mode toggle.

### 15.4 Notifications

Notification style, quiet hours, SOS alert override settings.

### 15.5 Updates

Aegis checks GitHub for new APK releases every 24 hours. When an update is available, a notification appears. Tap to download and install.

### 15.6 Backup

Local encrypted backup and restore. Configure automatic backup reminders.

### 15.7 Language

Multi-language support. Select your preferred language in Settings → Language.

### 15.8 Design

LunaGlass theme configuration.

## 16. Troubleshooting

### Messages not sending

Verify internet connectivity. SimpleX requires internet to reach relays. Messages queue locally when connectivity is unavailable and send automatically when restored.

### SOS not triggering

Confirm that the hardware trigger is enabled in Settings → SOS. Test with the SOS Drill first (Settings → Security → SOS Drill).

### App crashing

Export logs via Settings → Debug → Export logs. Report the issue.

### Location not updating

Verify that location permission is granted and battery optimization is disabled for Aegis (Settings → Apps → Aegis → Battery → Unrestricted).

### Calls not connecting

Verify that microphone permission is granted. WebRTC calls require internet. Calls may be blocked on restricted networks.

## 17. Security Architecture

### 17.1 Encryption

SimpleX protocol with double ratchet encryption. NaCl/libsodium for peer-to-peer cryptography. Forward secrecy ensures that compromising one message key does not expose past or future messages.

### 17.2 Metadata protection

SimpleX relays process encrypted blobs with disposable addresses. They cannot determine the sender, the recipient, or the conversation. No contact lists exist on any server. No user identifiers are transmitted.

### 17.3 Local-only storage

All data is stored locally in an encrypted database (SQLCipher). No cloud backup. No synchronization service. If you lose your device without a local backup, your data is lost. This is by design.

### 17.4 Open source

Aegis source code is licensed under AGPL-3.0. The cryptographic protocols (SimpleX, NaCl/libsodium) are well-studied and independently audited.

## 18. Design Language

Aegis uses LunaGlass — the Project Aether design system.

- Dark backgrounds with cyan accents (#00FFFF).
- Flat-top hexagonal UI elements.
- Inter typeface.
- Edge-heat animation on hold-to-execute controls.
- Shield tier badges on avatars.
- Glass panel surfaces with subtle borders.

---

*Part of Project Aether. Everything is light. dε/dt ≤ 0*

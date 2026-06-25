# Aegis — User Manual

## 1. Overview

Aegis is an encrypted communication and personal security app. All messaging runs through the SimpleX protocol — no accounts, no phone numbers, no metadata, no central server. If someone in your circle is in danger, one activation broadcasts GPS, audio, and camera to every authorized contact.

The app is organized into five tabs: System, Opsec, SOS, Comms, and Radar.

## 2. Installation

### 2.1 Download

Download the APK from the Aegis GitHub releases page or enable auto-update in Settings.

### 2.2 Install

Settings → Apps → Install unknown apps → Allow your browser → Open the APK → Install.

### 2.3 Permissions

Aegis requests permissions progressively as you enable features:

| Permission | Purpose |
|---|---|
| Location | SOS GPS broadcast, geofence, radar |
| Camera | SOS live stream, mugshot capture |
| Microphone | Voice messages, calls, SOS audio |
| Notifications | Message and SOS alerts |
| Phone | SIM swap detection |
| Bluetooth | Device proximity |
| Sensors | Motion detection (high-rate) |
| Battery exempt | Background services for SOS, canary |

### 2.4 Recommended device configuration

Android 10 or later (required). GrapheneOS on Pixel (recommended, not required). Device Owner mode for maximum protection (requires factory reset + ADB). Battery optimization disabled for Aegis.

## 3. Navigation

Aegis uses five bottom tabs:

| Tab | Function |
|---|---|
| System | Settings, profiles, security skill tree, diagnostics |
| Opsec | Loki toolkit, vault, lockdown |
| SOS | SOS activation, dashboard, evidence log |
| Comms | Chat list, contacts, groups, secure notes |
| Radar | Real-time map of trusted contacts |

## 4. Trust Model

Every contact belongs to one of three tiers. The tier controls all data flow.

### 4.1 Trusted

Partners, parents, close friends. Receives: location, presence, status, battery, SOS alerts. Full profile visibility. Can message, call, participate in groups.

### 4.2 Emergency

Crisis-only contacts: a doctor, neighbor, colleague. Receives SOS alerts only. No daily data.

### 4.3 Untrusted

Everyone else. Can message you. No visibility into status, location, or battery. No alerts.

### 4.4 Changing tiers

Open the contact's profile → tap the tier badge → select the new tier. Changes take effect immediately.

## 5. Shield Tiers

Your security level is displayed as a colored frame around your avatar.

| Tier | Frame | Nodes Required |
|---|---|---|
| None | No frame | 0 |
| Bronze | Bronze | 1 (App PIN) |
| Silver | Silver | 2-9 |
| Gold | Gold | 10 (all in-app) |
| Cyan | Cyan | 11 (Gold + Device Owner) |

Cyan is the highest tier. It requires a factory-reset device provisioned through ADB with every security node active.

## 6. Skill Tree

Ten security features arranged in a dependency tree. Every node requires App PIN as the root dependency.

| Node | Function | Requires |
|---|---|---|
| App PIN | Locks the app | Nothing |
| App Duress | Fake PIN opens decoy + silent SOS | App PIN |
| Mugshot | Wrong PINs trigger camera capture | App PIN |
| Vault PIN | Separate encrypted storage | App PIN |
| Vault Duress | Hidden vault behind second fake PIN | Vault PIN |
| Canary | Dead man's switch | App PIN |
| Geofence | Zone alerts | App PIN |
| SIM Watch | SIM swap detection | App PIN |
| SOS Drill | One-time test with one contact | App PIN + 1 contact |
| Device Owner | Factory reset + ADB | External setup |

## 7. Messaging

### 7.1 Sending messages

Tap a contact from the chat list. Type. Send. All messages are end-to-end encrypted via SimpleX.

### 7.2 Attachments

Tap the attachment drawer to send photos, documents, voice messages, or your current location.

### 7.3 Voice messages

Hold the microphone button to record. Release to send. Swipe left to cancel.

### 7.4 Message features

Delivery receipts (sent, delivered, read). Typing indicators. Edit sent messages. Pin important messages. Disappearing messages with configurable timer. Scheduled messages (compose now, send later). Forward any message or attachment to your encrypted vault.

### 7.5 Voice and video calls

Tap the phone or video icon in a chat. Calls use WebRTC via SimpleX signaling — encrypted and peer-to-peer. Audio routes to earpiece, speaker, or Bluetooth automatically.

### 7.6 Secure notes

A private encrypted notebook accessible from the Comms tab. Notes remain on-device, protected by your app PIN.

### 7.7 Quiet hours

Messages arrive normally but notifications are silenced during the configured period. SOS alerts always override quiet hours.

## 8. Groups

### 8.1 Standard groups

Group conversations where members see each other's display names and profiles.

### 8.2 Anonymous groups

Chat rooms where members cannot determine each other's identities. Each member appears as a rotating pseudonym. Protected against external observers, member enumeration, and device seizure.

## 9. SOS

### 9.1 Activation

Two methods: hold the in-app SOS button for three seconds (edge-heat animation confirms progress), or press the power button four times rapidly within two seconds (works with screen off).

### 9.2 During SOS

GPS broadcasts continuously. Audio streams in sixty-second encrypted segments. Camera frames transmit from front and rear cameras. A siren sounds (overrides silent mode). The radar shows real-time positions of all trusted contacts responding.

### 9.3 Voyager Mode

Progressive shutdown as battery depletes. At 5%: a fake dead-battery screen replaces the display. GPS transmissions continue once per hour — extending location tracking for days on a phone that appears powered off.

### 9.4 SOS dashboard

Live view of an active SOS event. Shows the victim's GPS trail, audio stream, camera frames, battery level, and the responding contacts' positions and distances.

## 10. Blood-Stays Visual

When battery drops below 20%, the screen progressively desaturates toward gray. Red elements (SOS button, health indicator, alerts) stay at full saturation — vivid red against a gray world.

This is the Witcher/Sin City visual language: the world fades, danger stays visible. At full desaturation (5% battery), the screen is entirely gray except for anything red.

Uses a per-pixel GPU shader on Android 13+. Older devices fall back to simple uniform desaturation.

## 11. Duress PIN

Two PINs for the app. The real PIN opens Aegis normally. The duress PIN opens a decoy profile with synthetic contacts and plausible message history, while silently activating SOS.

The second PIN's existence is cryptographically unprovable.

## 12. Vault

Encrypted storage behind a separate PIN, independent from the app PIN. Store sensitive documents, photos, and notes. Supports its own duress PIN — a third layer that presents a fake vault while alerting contacts.

## 13. Loki Toolkit

### 13.1 Mugshot

Incorrect PIN attempts trigger a silent front camera capture sent to trusted contacts.

### 13.2 SIM Watch

Monitors the device's SIM state. Any change to carrier or ICCID triggers an immediate alert.

### 13.3 Canary

Dead man's switch. If you do not open Aegis within a configurable interval, a pre-authored message is sent to your contacts.

### 13.4 Geofence

Define zones with coordinates and radius. Alerts when contacts enter or leave defined areas.

### 13.5 Decoy fixtures

Synthetic contacts and conversations displayed when the duress PIN is entered. Generated to appear plausible.

### 13.6 Remote commands

PIN-authenticated commands between paired devices:

| Command | Effect |
|---|---|
| LOCATE | Lock device, return GPS |
| SIREN | Trigger audible alarm remotely |
| WIPE | Notify all contacts, then factory reset |
| WATCH | Stream live camera and microphone |

### 13.7 Remote access

Live camera stream, live microphone stream, and watch mode — real-time surveillance of a paired device via encrypted channel. Requires PIN authentication from the requesting device.

## 15. Profiles

Multiple identity profiles on one device. Each profile has its own display name, avatar, and contact visibility. Switch between profiles without logging out. Contacts see only the profile you present to them.

## 16. Settings

### 16.1 System tab

Profile management. Security skill tree. Language picker. Graphics settings. Diagnostics and debug tools.

### 16.2 Auto-update

Aegis checks GitHub for new APK releases. When an update is available, download and install with one tap. Supports private repo authentication.

### 16.3 Backup

Local encrypted backup and restore. Configurable automatic reminders.

## 17. Security Architecture

### 17.1 Encryption

SimpleX protocol with double ratchet. NaCl/libsodium for peer-to-peer cryptography. Forward secrecy. X25519 device identity keys stored in Android Keystore with AES-256-GCM wrapping.

### 17.2 Metadata protection

SimpleX relays process encrypted blobs with disposable addresses. No contact lists on any server. No user identifiers transmitted.

### 17.3 Local-only storage

All data in an encrypted SQLCipher database. No cloud backup. No sync service. If you lose your device without a backup, data is lost. By design.

### 17.4 Open source

Aegis source code is AGPL-3.0 licensed.

## 18. Design Language

LunaGlass — the Project Aether design system. Dark backgrounds with cyan accents. Flat-top hexagonal UI elements. Inter typeface. Edge-heat animation on hold-to-execute controls. Shield tier badges. Glass panel surfaces. Blood-stays desaturation at low battery.

---

Part of Project Aether.

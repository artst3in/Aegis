# Privacy and Security

## Protocol

Aegis uses the SimpleX protocol. SimpleX is architecturally distinct from Signal, WhatsApp, and Telegram:

- **No user identifiers.** No phone number, no username, no account. Your identity is a cryptographic key pair. Nothing links your messages to any external identity.
- **No contact lists on servers.** SimpleX relays do not know who communicates with whom. Each conversation uses a different, disposable relay address.
- **No metadata.** Relays process encrypted blobs with disposable addresses. They cannot determine the sender, the recipient, or the conversation the message belongs to.
- **Double ratchet encryption.** Same protocol family as Signal, without the phone-number-based identity layer.

## Trust Model and Data Exposure

Your data exposure is controlled entirely by the tier you assign to each contact:

| Data | Trusted | Emergency | Untrusted |
|------|---------|-----------|-----------|
| Messages | Yes | Yes | Yes |
| Location | Yes | No | No |
| Presence and online status | Yes | No | No |
| Battery level | Yes | No | No |
| Sensor data | Yes | No | No |
| SOS alerts | Yes | Yes | No |
| Profile and avatar | Full | Alert-only placeholder | Minimal |
| Shield tier badge | Visible | Hidden | Hidden |

No per-feature toggles exist. The tier is the decision. This prevents misconfiguration under stress.

## Anonymous Groups

In anonymous groups, members cannot determine each other's real identities. Rotating pseudonyms replace display names. When you leave or are removed:

- All message history is erased from your device.
- All encryption keys are destroyed.
- All pseudonym mappings are deleted.
- Your device becomes indistinguishable from one that was never in the group.

The group creator knows all members' relay addresses. This is the trade-off for a design that requires no external servers.

## What Aegis stores (on your device only)

- Your messages and media.
- Your contacts and their tier assignments.
- Your location history (shared with Trusted contacts only).
- Your vault contents (encrypted behind a separate PIN).
- Your skill tree state and shield tier.
- Your backup archives (local, encrypted).

## What Aegis does not store or transmit

- Your phone number.
- Your email address.
- Your name (unless you enter it voluntarily).
- Your contacts' phone numbers.
- Who else uses Aegis.
- How many messages you send.
- When you are online (unless presence sharing is enabled for Trusted contacts).

## What SimpleX relays see

Encrypted blobs with disposable addresses. Nothing else.

## SOS data

During an SOS event, Aegis transmits to all Trusted and Emergency contacts:

- GPS coordinates (continuous).
- Audio stream (sixty-second encrypted segments).
- Camera frames (front and rear).

This data travels through the same SimpleX encrypted channels as regular messages. No server stores it. No third party receives it. Emergency contacts receive SOS alerts but not routine data — the tier distinction holds during emergencies.

## Duress PIN

The duress PIN creates a second, decoy profile. When entered:

- A clean messenger appears with synthetic contacts and plausible message history.
- A silent SOS activates in the background.
- The real profile is cryptographically inaccessible.

The existence of the second PIN is unprovable. An attacker examining the device sees only the decoy profile.

## Vault

The vault is encrypted storage within Aegis, behind its own PIN. Vault data is encrypted at rest using a key derived from the vault PIN — independent of the app PIN. The vault also supports its own duress PIN.

## Data storage

All data is stored locally on your device in an encrypted database (SQLCipher). Nothing is backed up to any cloud service. If you lose your device without a local backup, your data is lost. This is by design.

Aegis provides local encrypted backup — an export file that remains under your control. No cloud. No synchronization service.

## Cryptographic suite

Aegis uses NaCl/libsodium for peer-to-peer encryption and the SimpleX double ratchet for forward secrecy.

Not implemented. The post-quantum threat model is treated as out of scope; the cryptographic suite defends against every practical attacker.

## Open source

Aegis is open source (AGPL-3.0). The underlying cryptographic protocols (SimpleX, NaCl/libsodium) are well-studied and independently audited.

## Recommendations

- Use GrapheneOS on a Pixel phone for maximum operating system security.
- Enable full-disk encryption (enabled by default on modern Android).
- Use a strong PIN for device unlock (not biometric alone).
- Set up the App PIN and Duress PIN as your first security actions.
- Progress to Gold or Cyan shield tier for comprehensive protection.
- Enable the Canary if you operate in a high-risk environment.
- Keep the Trusted tier small — only people who actively need your daily data.

---

*Part of Project Aether.*

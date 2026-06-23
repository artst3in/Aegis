# Privacy & Security

## Architecture

Aegis uses the SimpleX protocol. SimpleX is fundamentally different from Signal, WhatsApp, or Telegram:

- **No user identifiers.** No phone number, no username, no account. You are a cryptographic key pair. Nothing links your messages to your identity.
- **No contact lists on servers.** SimpleX relays don't know who talks to whom. Each conversation uses a different, disposable relay address.
- **No metadata.** The relay sees encrypted blobs arriving and leaving. It doesn't know the sender, the recipient, or the conversation they belong to.
- **Double ratchet encryption.** Same protocol family as Signal, but without the phone-number-based identity layer.

## Trust Model and Privacy

Your data exposure is controlled entirely by the tier you assign to each contact:

| Data | Trusted | Emergency | Untrusted |
|------|---------|-----------|-----------|
| Messages | Yes | Yes | Yes |
| Location | Yes | No | No |
| Presence / online status | Yes | No | No |
| Battery level | Yes | No | No |
| Sensor data | Yes | No | No |
| SOS alerts | Yes | Yes | No |
| Profile / avatar | Full | Red "!" placeholder | Mask icon |
| Shield tier badge | Visible | Hidden | Hidden |

No per-feature toggles. The tier IS the decision. This prevents misconfiguration under stress.

## Anonymous Groups

In anonymous groups, members don't know each other's real identities. Rotating pseudonyms replace display names. When you leave or are removed:

- All message history is erased from your device
- All encryption keys are destroyed
- All pseudonym mappings are deleted
- Your device becomes indistinguishable from one that was never in the group

The group creator knows all members' addresses — this is the honest trade-off for a design that requires no external servers.

## What Aegis stores (on your device only)

- Your messages and media
- Your contacts and their tier assignments
- Your location history (shared with Trusted tier only)
- Your vault contents (encrypted behind separate PIN)
- Your skill tree state and shield tier
- Your backup archives (local, encrypted)

## What Aegis does NOT store or transmit

- Your phone number
- Your email
- Your name (unless you type it)
- Your contacts' phone numbers
- Who else uses Aegis
- How many messages you send
- When you're online (unless presence sharing is enabled for Trusted contacts)

## What SimpleX relays see

- Encrypted blobs with disposable addresses
- Nothing else

## SOS data

During an SOS event, Aegis sends to all Trusted and Emergency contacts:
- GPS coordinates (continuous)
- Audio stream (60-second encrypted segments)
- Camera frames (front and rear)

This data travels through the same SimpleX encrypted channels. No server stores it. No third party sees it. Emergency contacts receive the SOS alert but not routine data — the tier distinction holds even during emergencies.

## Duress PIN

The duress PIN creates a second, fake profile. When entered:
- A clean messenger appears (decoy contacts, decoy messages)
- A silent SOS triggers in the background
- The real profile is cryptographically hidden

The existence of the second PIN is unprovable. An attacker examining the device sees only the fake profile.

## Vault

The vault is encrypted storage within Aegis, behind a separate PIN. Vault data is encrypted at rest using a key derived from the vault PIN — not the app PIN. The vault also supports its own duress PIN.

## Data storage

All data is stored locally on your device in an encrypted database (SQLCipher). Nothing is backed up to any cloud. If you lose your device, you lose your data. This is by design.

Aegis offers local encrypted backup — an export file you control. No cloud. No sync service.

## Post-quantum cryptography

Aegis does not use post-quantum cryptography. The quantum computer that Shor's algorithm requires — coherent superposition of exponentially many states in physical hardware — will never be built. The threat model is fictional. Our cryptographic suite (NaCl/libsodium, double ratchet) defends against every real attacker.

## Open source

Aegis is open source (AGPL-3.0). The cryptographic protocols (SimpleX, NaCl/libsodium) are well-studied and independently audited.

## What we recommend

- Use GrapheneOS on a Pixel phone for maximum OS-level security
- Enable full-disk encryption (enabled by default on modern Android)
- Use a strong PIN (not biometric alone) for device unlock
- Set up the App PIN and Duress PIN (first two skill tree nodes)
- Progress to Gold or Cyan shield tier for comprehensive protection
- Set up the canary if you're in a high-risk environment
- Keep Trusted tier small — only people who actively need your daily data

---

*dε/dt ≤ 0*

# Aurora + Artur decisions — Profile Onboarding & Backup
**Date:** 2026-06-06
**Status:** FINAL — all decisions made

## Profile Onboarding (8 decisions)

### 1. One button
Replace "Create new" + "Create ephemeral" with one "New profile"
button. Permanent/ephemeral selected during onboarding flow.
Prevents tapping the wrong button.

### 2. A stays alive during B's onboarding
A locks (UI gated), background safety services keep running
(SOS, sentinel, remote-access, SimpleX receive). A's seal key
wiped. A's services run on cleartext safety data (routing keys,
trust tiers). ZERO impact on A.

A's chat notifications SUPPRESSED during onboarding. Only SOS
comes through. SOS overrides onboarding — onboarding breaks,
user handles the emergency, restarts onboarding later.

### 3. Never two profiles in parallel
NEVER. Wrong notification = catastrophic. One active profile at
all times. A runs safety-only during B's onboarding, not chat.

### 4. Completion behavior — permanent vs ephemeral
**Permanent B:** B is CREATED but not active. User gets explicit
warning: "Opening this profile will shut down [A]. No
notifications, no SOS, no help will come until you set up
contacts." User chooses: open now (risky) or postpone and stay
on A (switch later from profile picker).

**Ephemeral B:** now or never. Ephemeral is not saved to disk.
If you don't open it immediately, it dies. No "save for later."

### 5. Switching — permanent B is the new home
**Ephemeral B:** visitor. Lock = B dies, return to A.
**Permanent B:** new home. B stays. Switch to A explicitly from
profile picker. A is no longer the default.

### 6. SOS while on ephemeral B
Same as existing: destroy B → load A locked → SOS fires on
A's cleartext safety data. No unlock needed. Same as panicking
from lock screen.

### 7. Import into ephemeral
Allowed. Useful for inspecting a backup without committing it.
Restore into ephemeral → data in RAM → lock = gone.

### 8. Dual runtime architecture
Sound. A's safety services need only cleartext data (routing
keys, trust tiers — intentionally not sealed). A can be fully
locked while its safety runs. Same data layer as normal locked
state. One seal key in memory at a time.

## Backup spec corrections

### CRITICAL: backup does NOT carry the master key
Chad's spec was wrong. The backup carries DATA encrypted with
the backup password. NOT the seal private key. NOT the phrase.

**Export:** app is unlocked → reads data → re-encrypts with key
derived from backup password (Argon2id) → writes file. Master
key stays on device.

**Import:** enter backup password → derive key → decrypt data →
re-seal under the importing profile's phrase-derived key. The
importing profile provides the new master key.

**Recovery flow (forgot phrase):**
1. PIN-unlock the app (TEE releases seal key)
2. Export backup (requires PIN, not just biometric)
3. Create new profile with new 24-word phrase
4. Import backup → data re-sealed under new phrase
5. New master key. Old data preserved.

### Backup password requirements
- Minimum 12 characters
- No class requirements (no forced symbols/uppercase)
- "mymomlovesme" is valid and uncrackable with Argon2id
- Argon2id MODERATE mandatory for key derivation
- Length-only enforcement. Sentences > random gibberish.

### Export requires PIN
Even if the app is unlocked via biometric, export requires PIN.
Prevents an attacker who forced your fingerprint from
exfiltrating your data.

Biometric = read. PIN = act.

### Backup timestamp on radar
Trusted contacts see "Last backup: [time]" on your radar
profile. No push notification needed. Passive, always visible.
If they didn't expect a backup, they ask.

## Designed by
Artur Tokarczyk, 2026-06-06.

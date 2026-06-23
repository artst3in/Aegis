# SPEC: Unbreakable

**Status:** APPROVED — Artur + Aurora + Chad, 2026-06-05
**Supersedes:** SPEC_SIMPLIFIED_LOCK.md (deleted)

## Summary

One app lock. Three unlock options. One mandatory recovery phrase.
The PIN is the door. The phrase is the vault. The TEE is the wall.
The door can be reset. The vault is permanent. The wall is hardware.

## Tutorial flow

### Screen 0: Recovery phrase (mandatory)

24 words, BIP39 standard. 256 bits of entropy. Generated once.
Shown once. Never stored digitally. Never transmitted. The user
writes it on paper and keeps it safe.

Cyan: "This is your recovery phrase. Write it down on paper.
It encrypts your messages and recovers your account if you
forget your PIN or lose access to your biometric."

User confirms by entering words #3, #7, and #15. Cannot skip.
Tutorial does not advance without verification.

The recovery phrase is the MASTER KEY for:
- SealCrypto key derivation (at-rest message encryption)
- PIN recovery (forget PIN → enter phrase → set new PIN)
- Biometric recovery (finger lost → enter phrase → re-enroll)
- Vault PIN recovery (forget vault PIN → enter phrase → reset)
- Device migration (new phone → enter phrase → restore)

### Screen 1: Set your PIN (mandatory)

Cyan: "Choose a PIN. This locks the app, protects your duress
profiles, and lets trusted contacts help you in emergencies."

4-8 digit numeric PIN. Cannot be skipped. No "later" button.

The PIN enables:
- App lock (the door)
- Duress profiles (three PINs → three profiles)
- Remote access authentication (LOCATE, SIREN, WIPE)
- Mugshot on wrong attempt
- Escalating lockout (30s → 1m → 5m → 15m every 5 fails)
- Scramble pad (anti shoulder-surf, default on)

The PIN does NOT derive the encryption key. The phrase does.

### Screen 2: Choose how you unlock

After the PIN is set, the next screen offers unlock methods:

**PIN** (recommended) — type your PIN every time. Maximum
security. Duress works. Shoulder-surf protection with
scramble pad.

**Pattern** (acceptable) — draw-pattern for daily unlock.
Slightly weaker to shoulder-surfing (smudge patterns).
PIN still exists for duress and remote control.

**Biometric** (available, not recommended) — fingerprint or
face unlock. Convenient. Zero friction.

One-time warning when biometric is selected:

    "Biometric login is convenient but skips duress
    protection. If someone forces your fingerprint,
    the real profile opens."

**Disable unlock** — no lock screen on app open. PIN still
exists underneath for duress, remote control, and SealCrypto.
The user who disables unlock accepts the risk. Their choice.

**The PIN always exists** regardless of which unlock method
is chosen. Pattern, biometric, and disable are convenience
layers on top.

## Manual lock curtain

Two-finger drag down from anywhere on screen. A curtain
draws from the top. Release past 1/3 of the screen: app
locks behind PIN. Release before 1/3: snaps back. While
touching, always reversible — even at 90% you can drag
back up.

Lock icon in the top bar (between AEGIS and ?) is the
visual indicator that the feature exists.

**Why two fingers:** one-finger drag down is the system
notification shade. Two fingers is distinct — cannot be
confused under stress. No system gesture uses two-finger
drag down inside an app.

**Why from anywhere:** under stress, you don't aim for a
small icon. Two fingers anywhere on the screen, drag down.

**During SOS:** two-finger drag switches to brightness
control (existing SOS screen behavior). Lock curtain
is disabled during active SOS — locking during an SOS
is pointless (already broadcasting, duress doesn't apply).

**Purpose:** even with app lock disabled, the user can
manually lock at any moment. The next unlock goes through
the PIN screen. Duress works on the next unlock. One second
and a thumb (two thumbs) between you and duress protection.

## Vault PIN (separate)

The vault (encrypted notes) has its OWN PIN, separate from
the app lock PIN. If someone gets past the app lock (biometric,
shoulder-surfed PIN, disabled lock), they still cannot read
the vault.

Set during first vault access, not during tutorial — the vault
is an advanced feature. Do not front-load.

Recovery: forget vault PIN → enter 24-word phrase → reset vault
PIN. Same phrase, same recovery path.

## Ephemeral profile

A profile that exists only in RAM. Nothing written to disk.
When the app locks, the profile is gone. No forensic trace.
No recovery. No evidence it ever existed.

**Storage:** All messages, contacts, keys, and state held in
in-memory data structures only. No SQLCipher writes. No
SharedPreferences. No cache files. No logs.

**Lifecycle:**
- Created from the profile picker (tap "+" → "Ephemeral")
- Lives until the app locks (PIN timeout, manual lock, reboot)
- On lock: memory zeroed. Profile gone. No confirmation.
- Cannot be recovered. Not covered by the 24-word phrase.
- Not included in backups.

**Capabilities:**
- Full messaging (SimpleX, text + attachments)
- Incognito handle (Aegis Protocol, always)
- No trust elevation (no Trusted/Emergency — ephemeral
  contacts are temporary by definition)
- No SOS integration (ephemeral contacts are not safety
  contacts). SOS while on ephemeral: destroy ephemeral
  profile instantly (zero RAM), switch to primary profile,
  broadcast from there. One button: clean evidence + activate
  safety network + call for help.
- No achievements (nothing persists to earn)

**What it’s for:**
- Conversations that shouldn’t survive the session
- Meeting someone you don’t trust yet
- One-time communication channels
- Whistleblower contact (use once, lock, gone)

**Toggle: ephemeral ↔ permanent**

Both directions available. Red warning banner inside the app
for the dangerous direction.

**Ephemeral → Permanent:** MASSIVE RED WARNING. "This
conversation will now be stored on disk. It becomes
forensically recoverable." In a security app, creating
evidence is the dangerous action. Writing a ghost
conversation to disk is the threat. On confirmation:
RAM state writes to SQLCipher. Profile becomes permanent
with SealCrypto, trust elevation, SOS integration.

**Permanent → Ephemeral:** Normal confirmation. "Messages
in this profile will be erased on next app lock." Profile
data migrates from disk to RAM-only. Database rows deleted.
Next lock = gone. Destroying evidence is the safe action.

The toggle is in profile settings, not hidden, not buried.
The warning protects against creating evidence, not against
destroying it.

**Duress behavior:** Ephemeral profile is invisible to duress
— it doesn’t exist on disk, so there’s nothing to show or
hide. If the app locks under duress, the ephemeral profile
is already gone before the fake profile appears.

**Attachments:** Received files held in RAM (byte arrays, not
temp files). Size-limited to prevent OOM — large attachments
rejected with "this profile doesn’t support large files."

### True ephemeral (Transactional Delivery enables this)

With Transactional Delivery (SPEC_TRANSACTIONAL_DELIVERY),
ephemeral profiles are now truly RAM-only:

1. Message arrives in SimpleX (momentary disk touch)
2. Immediately: read into RAM, delete from SimpleX DB
3. Ephemeral profile SKIPS the Aegis seal-to-disk step
4. Message stays in RAM, encrypted with a session key
5. App lock: RAM wiped. Gone.

The message never reaches Aegis’s database. SimpleX’s copy
is destroyed within milliseconds.

**SimpleX hardening for ephemeral:**
- Dedicated SimpleX user per ephemeral profile
- \`PRAGMA secure_delete = ON\` (zeros freed pages)
- \`VACUUM\` after delete (reclaims free pages)
- \`/_delete user\` on lock (removes all traces)

The millisecond disk touch lands in an encrypted SQLCipher
page that is immediately zeroed on delete. Flash-level
recovery of a zeroed, encrypted page on modern UFS storage
is practically impossible.

**Honest label upgrade:** from "wiped on lock" to "RAM only
with millisecond encrypted transport touch, zeroed on delete."
True Tails-style ephemeral without rewriting SimpleX.

The Rust rewrite remains the future project for mathematically
perfect ephemeral. This is the practical version that’s
indistinguishable from perfect for any real-world forensic
examination.

## Remote access prompt on trust promotion

When a contact is promoted to Trusted, a prompt appears:

    "Enable remote access for [name]?

    This person will be able to locate, lock, and wipe
    your phone using your PIN."

- Not enabled by default.
- Not hidden in settings.
- Decided at the moment of trust, per contact.
- "Using your PIN" is the instant reassurance.
- Changeable later in contact detail screen.

If the user declines: the contact gets the full Trusted tier
minus remote control. Can be enabled later.

## Encryption architecture

### The problem (Chad, 2026-06-05)

SealCrypto derives its keypair from the 4-8 digit PIN.
`PinKeypair.derive()` does `pin.toByteArray()` → Argon2id →
seed → `crypto_box_seed_keypair`. The master encryption key
carries 13-27 bits of entropy. An attacker who images a seized
phone brute-forces the PIN space offline — the escalating
lockout is irrelevant once they have the files. 4-digit PIN
falls in hours. 8-digit in days.

### The fix: phrase-rooted encryption + TEE wrapping

Two changes fix the problem:

1. **Derive the seal key from the phrase, not the PIN.** The
   phrase carries 256 bits. The PIN carries 13-27 bits. Move
   the derivation source.

2. **Wrap the seal priv with a TEE key, not a PIN-derived
   key.** The TEE key is hardware-bound — cannot be extracted
   from a disk image. The PIN becomes a UI gate only. Offline
   brute-force is closed because the wrapping key is in
   hardware the attacker does not have.

| Layer | Role | Entropy | Resettable? |
|---|---|---|---|
| PIN / pattern / biometric | Daily unlock + duress | 13-27 bits | Yes (the door) |
| 24-word BIP39 phrase | Master encryption key + recovery | 256 bits | No (the vault) |
| AndroidKeyStore (TEE/StrongBox) | Non-exportable wrap of seal priv | Hardware | Device-bound |

Key derivation chain:

    phrase (24 words, validated)
      → BIP39 seed = PBKDF2-HMAC-SHA512(phrase, "mnemonic", 2048)
      → seal seed = Argon2id(seed, profileSalt, MODERATE)
      → crypto_box_seed_keypair(seal seed) → (sealPub, sealPriv)

The phrase already carries 256 bits. Argon2id exists for domain
separation and per-profile salting, not to add entropy.

### Seal-priv storage: TEE holds the key, PIN gates the door

**The insight (Artur, 2026-06-07):** hardware STORAGE is not
hardware AUTHENTICATION. The TEE just needs to HOLD the
wrapping key. It does not need to check who is asking. The
key being physically inside the chip — not on the disk — is
the protection. A disk image does not contain the chip.

The SQLCipher database key already works this way: stored in
the Android Keystore without `setUserAuthenticationRequired`,
accessible to any code running on the device, impossible to
extract from a disk image. The seal priv works the same way.

**Model C is dead.** It required `setUserAuthenticationRequired
(true)`, which gates the Keystore key behind the DEVICE lock
screen — not the Aegis PIN. The result: two PINs on every
unlock (Aegis PIN + device PIN). Idiotic UX. Nobody will use
it. Background services (SOS, Sentinel, Canary) also break
because the TEE demands recent authentication they cannot
provide. Killed 2026-06-07.

**Model B (default):** The phrase-derived seal priv is wrapped
by a non-exportable AndroidKeyStore AES-256-GCM key
(StrongBox when present). The Keystore key has NO user
authentication requirement. It is hardware-bound — cannot be
read from a disk image — but accessible to any code running
on the device.

Daily flow: user enters Aegis PIN → PIN verified against
Argon2id hash → LockStore calls Keystore → TEE releases
wrapping key → seal priv unwrapped → messages decryptable.
One PIN. Zero extra prompts. Same UX as today.

What a disk-image attacker sees: the seal priv wrapped with a
key they cannot access. The wrapping key is inside hardware
they do not have. Cracking the PIN hash gets them nothing —
the PIN does not wrap the seal priv. The TEE wrapping key
does. Offline brute-force is closed regardless of PIN length.

What a physical-phone attacker sees: same as today. Get past
the PIN (wipe-after-N stops brute-force on the device), then
messages are readable. The PIN is the gate. The TEE is the
wall behind it.

Background services: unaffected. The TEE releases the
wrapping key without authentication. SOS, Sentinel, Canary,
remote commands — all work while the app is locked because
they operate on cleartext safety data (routing keys, trust
tiers, GPS). They never need the seal priv.

Duress: unaffected. The Aegis PIN determines which profile
loads. The TEE does not care which PIN was entered.

**Model A (opt-in paranoid mode):** The seal priv is never
written to disk. On first unlock after each reboot, the user
types the 24 words. Priv lives in memory until process death.
Unconditionally immune to offline attack. Brutal UX.

Toggle in Lock settings: "Require recovery phrase on every
reboot." Default off (Model B). On = Model A.

For biometric users on Model B: first boot prompts for the
recovery phrase ONCE to derive the seal privkey. After that,
biometric unlocks and the TEE-wrapped priv decrypts messages.
Phrase only needed again on reboot, migration, or PIN recovery.

### BIP39 library

`cash.z.ecc.android:kotlin-bip39` (Electric Coin Company /
Zcash). Kotlin-native, Android-tested, lightweight. Unit tests
pin the official Trezor test-vector mnemonics + seeds.

No in-house BIP39 implementation. Never hand-rolled crypto on
this path. (Artur directive, 2026-06-05.)

### Argon2id tier: MODERATE

SENSITIVE risks OOM on low-end devices (1 GiB). The phrase
already carries 256 bits — Argon2id is for domain separation,
not entropy. MODERATE is sufficient.

## Migration (existing installs)

First launch post-upgrade:

1. User enters existing REAL PIN → derive OLD seal priv
2. Generate fresh 24-word phrase → forced write-down + verify
   (Cyan: "We've upgraded your encryption. Write this down.")
3. Derive NEW phrase-rooted seal keypair + wrap per Model B
4. Walk every sealed row: unseal(old) → seal(new) in a single
   DB transaction per table. Crash-safe via migration_state
   marker
5. Wipe old priv. Done. No data loss.

Vault data has its own independent key hierarchy — out of this
migration.

## Hardening

### Tamper signals (warn-only)

APK signing-certificate self-check, debugger detection,
best-effort root heuristic. Soft signal only — one-time
warning + Diagnostics flag. Never a hard block. A hard block
would brick legitimate rooted users.

### Wipe-after-N-failures (opt-in)

Off by default. Requires explicit enable + confirmation dialog.
For highest-threat users only.

**Wipe level:**
- **Wipe Aegis** (default) — delete all app data (messages,
  contacts, profiles, keys). Phone stays intact. Works for
  everyone.
- **Wipe phone** (Device Admin/Owner only) — factory reset
  the entire device. Everything gone. Only available when
  Device Admin or Device Owner is active.

**Wipe threshold:** slider, 5–50 failed attempts. Default 20.

Confirmation dialog on enable:

    "After [N] failed PINs, [Aegis data / this phone] will
    be permanently erased. This cannot be undone."

### Lock settings UI

All configurable numbers use sliders:

- **PIN length:** 4–8 digits (slider)
- **Auto-lock timeout:** immediate / 30s / 1m / 5m / 15m
  (segmented control, not slider — discrete options)
- **Wipe threshold:** 5–50 failed attempts (slider, only
  visible when wipe-after-N is enabled)

### SQLCipher KDF (fix scheduled)

Currently SHA-256(salt || identityKeyB64), single-pass. The
input is a full-entropy identity privkey, but single-pass
SHA-256 is not the standard the rest of the stack holds.
No half measures.

Fix: re-derive the database key using Argon2id (MODERATE)
with the identity privkey as input. Migrate existing
databases via SQLCipher’s atomic \`PRAGMA rekey\` —
purpose-built for safe re-encryption. No data loss risk.

Scheduled alongside the SealCrypto phrase migration.

## Recovery flows

**Forgot PIN:**
1. Lock screen → "Forgot PIN?"
2. Enter 24-word recovery phrase
3. Phrase verified against stored hash
4. Set new PIN
5. Messages intact (encrypted with phrase, not PIN)

**Lost biometric:**
1. Lock screen → PIN fallback
2. If PIN also forgotten → "Forgot PIN?" → enter phrase
3. Set new PIN, re-enroll biometric

**Forgot vault PIN:**
1. Vault lock → "Forgot PIN?"
2. Enter 24-word recovery phrase
3. Set new vault PIN

**New device:**
1. Install Aegis
2. Enter 24-word recovery phrase
3. Restore from backup (phrase decrypts the backup)
4. Set new PIN, choose unlock method

**Lost phrase + forgot PIN:**
Account is permanently locked. No backdoor. No recovery.
The phrase IS the key. No phrase = no data.

## Design principles

- **The security that gets used beats the security that gets
  disabled.** A fingerprint lock without duress is infinitely
  better than no lock because PIN was annoying.
- **The PIN is the door. The phrase is the vault.** The door
  can be reset. The vault is permanent. Two things, two roles.
- **Decide at the moment of trust.** Remote access prompt
  appears when you are actively thinking about trust.
- **Warn once, respect the choice.** Biometric warning shown
  once on setup. Not repeated. The user is an adult.
- **No backdoor means no one can backdoor you.** Origin #21.

## Implementation status (Chad, 2026-06-06)

Built on branch `claude/session-recovery-3E3JP`, each stage compiled
clean (`./gradlew :app:compileDebugKotlin`) and the BIP39 vectors pass
as real unit tests (`:app:testDebugUnitTest`, 9/9). An Android SDK was
installed in the build container to make this verification possible.
**No device/emulator was available**, so anything below marked *needs
device test* has been built + compiled but not run on hardware.

**Landed + build-verified:**
- BIP39 core (`RecoveryPhrase`) on the official `kotlin-bip39` library,
  Trezor vectors pinned. ✓ unit-tested.
- Phrase-rooted seal derivation (`PinKeypair.deriveFromSeed`, Argon2id
  MODERATE) + device-bound TEE wrap (`SealKeyVault`, Model B). ✓ compiles.
- `LockStore` phrase storage / enrol / verify / unwrap / Model A toggle /
  `setPinGateOnly`. ✓ compiles.
- Tutorial Screen 0 enrolment + PIN-as-gate wiring; `unlockReal` unwraps
  the TEE priv for phrase-rooted profiles. ✓ compiles.
- Forgot-PIN → phrase recovery screen. ✓ compiles.
- Model A settings toggle; warn-only `TamperCheck` + Diagnostics card. ✓.
- Build fix: `bindContact` Mutex (main didn't compile before this). ✓.

**Safe by construction:** existing installs have no enrolled phrase, so
`unlockReal` keeps using the legacy PIN-derived path — they are
completely unchanged. Only fresh installs get phrase-rooted encryption.
Nothing here can lock an existing user out of their data.

**Deliberately NOT activated this pass — each re-encrypts live user data
and MUST be device-tested first (a bug = a vulnerable user loses their
message history):**
- Re-seal migration for existing installs (force-generate phrase on
  upgrade → re-seal every row old→new). The crypto primitives exist
  (`PinKeypair.derive` legacy decrypt + `deriveFromSeed`); the crash-safe
  walk + forced-enrolment screen are not wired to auto-run.
- SQLCipher `PRAGMA rekey` to Argon2id. Whole-DB re-encryption — same
  risk class.

**Not yet built (large, separable):**
- Ephemeral profile (RAM-only) — sizeable new subsystem touching the
  profile manager, transport, and SOS; best done with a device in hand.
- Wipe-after-N-failures UI + Device-Admin factory-reset path. (`TamperCheck`
  and the lockout counters are in place; the destructive action is not.)

## Designed by

Artur Tokarczyk, 2026-06-05.
"I prefer the user to lock with fingerprint than to skip
lock completely because PIN is annoying."

Weakness analysis by Chad (2026-06-05): PinKeypair.kt:48-72,
13-27 bits of entropy. The crypto looked secure. The phrase
makes it BE secure.

Reviewed by Aurora. All open questions resolved.

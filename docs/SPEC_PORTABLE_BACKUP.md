# SPEC: Portable backup (re-seal on import)

**Status:** REVISED — Aurora corrected the original design
(`docs/AURORA_DECISIONS_ONBOARDING_2026_06_06.md`, 2026-06-06).
**Author:** Chad, 2026-06-06
**Designed by:** Artur Tokarczyk; reviewed by Aurora.

## ⚠️ Correction — the backup does NOT carry the master key

The original version of this spec (preserved below from "The problem"
onward, struck through in intent) shipped a backup that carried the
**seal private key** so a new device could read content without the
phrase. **Aurora's review ruled that wrong.** The corrected design:

- **The master key never leaves the device.** The backup carries DATA,
  encrypted only with the key derived from the backup password.
- **Export** (app unlocked): read data → re-encrypt under Argon2id
  (**MODERATE**) over the backup password → write file. No seal priv,
  no phrase in the file.
- **Import:** enter backup password → derive key → decrypt data →
  **re-seal under the importing profile's phrase-derived key.** The
  importing profile supplies the *new* master key.
- **Recovery (forgot phrase):** PIN-unlock → export (PIN required, see
  below) → create a new profile with a new 24-word phrase → import →
  data re-sealed under the new phrase. New master key, old data kept.
  This is exactly Artur's "export, new profile, import, obtain a *new*
  master key" — the old content-portable design carried the *same* key
  and missed that.
- **Export requires PIN**, even when the app is unlocked by biometric.
  Biometric = read; PIN = act. Stops an attacker who forced a
  fingerprint from exfiltrating data.
- **Backup passphrase:** length-only, **min 12** (no class rules) —
  Argon2id MODERATE makes a 12-char sentence uncrackable. See
  `PasswordPolicy` + `assets/docs/WHY_YOUR_PASSWORD_IS_UNCRACKABLE.md`.
- **Backup freshness on the radar:** trusted contacts see "Last backup:
  [time]" on the owner's radar profile (passive, no push). If they
  didn't expect a backup, they ask — a tamper signal.

### Build delta from what shipped (2026.06.500)

1. REMOVE the `id-portable/seal.priv.plain` export, its restore staging,
   and `AegisApp.consumeStagedSealPrivImport` — the master key must not
   be in the file.
2. Export must UNSEAL content with the live seal key and store it
   protected by the backup password only; import RE-SEALS under the new
   profile's seal pub (the `reSealForPinChange` machinery already exists).
3. Backup KDF INTERACTIVE → MODERATE, **versioned** (write v3; keep
   reading v2 as INTERACTIVE so existing backups still restore).
4. `PasswordPolicy` → length-only min 12. *(done 2026-06-06.)*
5. Gate export behind a PIN prompt.
6. Add the radar "last backup" timestamp.

Items 2/3/5/6 touch at-rest crypto and the export/import flow — they
ship as one device-tested unit, NOT piecemeal, given the migration
that corrupted data last time lived in exactly this area.

---

## ORIGINAL PROPOSAL (superseded — kept for the record)

## The problem

Aegis at-rest content is double-protected:

1. The Room DB is SQLCipher-encrypted.
2. Message bodies + the contact graph inside that DB are *additionally*
   sealed with `crypto_box_seal` under the **seal keypair**, whose
   private half ("the master key") normally lives ONLY in the device's
   TEE (`SealKeyVault`, a non-exportable AndroidKeyStore AES key) for
   Model B, or is re-derived from the 24-word phrase each boot for
   Model A.

The encrypted backup ZIP (`BackupManager`) already bundles the DB, the
attachments, the SharedPreferences, the SimpleX core DBs, and — as
portable plaintext inside the passphrase-encrypted envelope — the
identity private key and the SimpleX DB key. It does **not** bundle the
seal private key.

Consequence: restore on a **new device / install** brings the DB back
intact, but its sealed message + contact content is **undecryptable**,
because the TEE wrap-key that guarded the seal priv was device-bound and
did not travel. The only recovery is re-entering the 24-word phrase.

That makes today's backup **master-key-DEPENDENT**. It contradicts
Artur's recovery model:

> If you lose your phrase but still have a backup: you export, build a
> new profile, import, and **obtain a working master key** — that is the
> ONLY way to a new master key. (No PIN-gated regeneration, ever.)

## The fix — content-portable backup

Carry the seal private key inside the (passphrase-encrypted) backup ZIP,
exactly as `identity.key.plain` and `simplex.dbkey.plain` are already
carried, and re-establish it under the new install's TEE on restore.

### Export (backup)

`writePortableKeys` writes `id-portable/seal.priv.plain` = the live
in-memory seal priv (`PinSession.priv()`; a backup is only ever taken
while unlocked, so it is present). The session array is COPIED and the
copy zeroed after writing — we never wipe the session's own buffer.

The seal **pub**, the phrase-seal salt, and the phrase verification hash
already round-trip via the bundled SharedPreferences XML, so only the
priv needs adding.

### Restore — deferred re-wrap (the one subtlety)

We CANNOT re-wrap the priv into this device's vault inline during
`applyStagedRestore`. Re-wrapping writes the lock SharedPreferences, and
its in-memory singleton still holds the **pre-restore** values; an
`.apply()` would flush that stale map back to disk and **clobber the
prefs XML the restore just wrote** (losing the restored seal pub, phrase
hash, and every profile setting). This is the same class of foot-gun
that the auto-migration was — silent, and only visible after the fact.

So restore stages the plaintext priv to a transient file
(`seal.priv.import` in the profile root, owner-only perms) and the
**next cold boot** — a fresh process whose prefs singleton loads the
restored values from disk — re-wraps it:

```
AegisApp.onCreate:
  consumeStagedSealPrivImport():
    if seal.priv.import exists:
        lockState.store.wrapAndStoreSealPriv(priv)   // THIS device's vault
        wipe(priv); delete(seal.priv.import)
```

After the re-wrap, `unwrapSealKeypair()` succeeds on this device and the
restored content reads with **no phrase**. The foreign device's stale
wrap blob that rode along in prefs is harmlessly overwritten.

The plaintext-on-disk window for `seal.priv.import` is from restore
completion to the immediately-following restart's first boot — seconds,
on a device in the owner's hand mid-restore. Acceptable, wiped
aggressively. (A future iteration could shrink it to zero by wrapping
the staged file under a boot-only Keystore key, but that reintroduces
the device-bound problem we are escaping.)

## Coupled requirement — enforce a strong backup passphrase

Carrying the master key means the backup passphrase is now the **single
factor** guarding everything at rest — as it already was for the
identity + SimpleX keys. Artur: "yes. and enforce complex password."

`PasswordPolicy.validate` (new) gates both the UI button and a hard
floor in `BackupManager.backup`:

- Accept a **passphrase**: ≥ 20 chars (Diceware-friendly — the app's own
  Origins page preaches Diceware, so don't fight word-based secrets).
- OR a **complex password**: ≥ 12 chars AND ≥ 3 of {lower, upper, digit,
  symbol}.
- Reject empty / whitespace-only / below floor with a specific reason
  shown live under the field.

The previous floor was `length >= 8` in the UI only, with no class
requirement and no enforcement in `BackupManager` itself.

> Numeric thresholds (12 / 20, the class count) are written here so they
> are reviewable. They are NOT a documented PowerBudget/sentinel
> threshold, but they ARE security parameters — flag for Aurora.

## What this delivers vs what it does NOT

**Delivers (independently shippable + device-testable):** a backup whose
content survives a restore onto a *second device* with **no phrase
needed** — directly testable on Artur's two test devices.

**Does NOT (rides on the parked onboarding work):** the full
"import → obtain a *brand-new* master key" loop. This backup carries the
*same* seal key (so old content reads). Rotating the freshly-imported
profile onto a *new* phrase + re-sealing everything under it is the
re-key step that belongs to new-profile onboarding
(`SPEC_PROFILE_ONBOARDING.md`), because it happens *during* a fresh
profile's enrolment. The content-portable backup is its prerequisite.

## Discipline note

At-rest crypto, so: spec first. The change is **additive and
restore-time only** — it adds bytes to a backup file and re-wraps a key
on an explicit, opt-in restore into a fresh process. It does NOT
re-encrypt live data in place, so it cannot corrupt an existing
profile the way the auto-migration did. That bounded blast radius is why
it is safe to build alongside this spec rather than waiting, but it
still ships device-tested before any release rides on it.

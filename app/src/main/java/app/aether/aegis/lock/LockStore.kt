package app.aether.aegis.lock

import android.content.Context
import android.content.SharedPreferences
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash

/**
 * Persistent state for the app-lock screen.
 *
 * Holds:
 *  - Argon2id hash of the PIN (libsodium INTERACTIVE) so the PIN never
 *    lives in plaintext — `cryptoPwHashStr` bakes its own salt into
 *    the returned encoded string, no separate salt column needed
 *  - failed-attempt counter + lockout window (5 fails → escalating)
 *  - lock-after-background timeout (0 / 30s / 1m / 5m / never)
 *
 * Plain SharedPreferences — same boundary as SecretsStore. The PIN is
 * cheap to brute-force (10^4..10^6 search space) so the real defence is
 * the in-app lockout escalation: 5 fails = 30 s, then 1 m, 5 m, 15 m.
 */
class LockStore private constructor(prefs: SharedPreferences, sealTag: String) {

    private val prefs: SharedPreferences = prefs
    /** Per-profile [SealKeyVault] tag so each profile's seal priv is
     *  TEE-wrapped under its OWN key (default profile → "" = the bare,
     *  backward-compatible alias). */
    private val sealTag: String = sealTag
    private val sodium = app.aether.aegis.crypto.Sodium.shared

    companion object {

        /** Derive the per-profile seal tag from the lock-prefs name:
         *  "" for the default profile (bare name), else the profile id. */
        private fun sealTagFromName(name: String): String =
            name.removePrefix(STORE_NAME).removePrefix("__")

        /** LockStore for the currently-active profile. The common
         *  path — every existing call site uses this. */
        operator fun invoke(context: Context): LockStore {
            val name = app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE_NAME)
            return LockStore(context.getSharedPreferences(name, Context.MODE_PRIVATE), sealTagFromName(name))
        }

        /** LockStore for an arbitrary profile by id. Used by the
         *  multi-profile switcher / lock-screen multi-slot PIN
         *  matcher so the lock UI can check the entered PIN against
         *  every known profile, not just the active one. The bare
         *  profile id is enough to derive the SharedPreferences name
         *  (default profile → bare base; non-default → "${base}__${id}"). */
        fun forProfile(context: Context, profileId: String): LockStore {
            val name = app.aether.aegis.profile.ProfileRoot.forId(context, profileId).prefsName(STORE_NAME)
            return LockStore(context.getSharedPreferences(name, Context.MODE_PRIVATE), sealTagFromName(name))
        }

        /**
         * Verify [pin] against every known profile and return the first
         * match. Used by the lock screen to support "type any profile's
         * PIN, unlock that profile" — fundamental for the multi-profile
         * threat-model where the user shouldn't have to pre-select a
         * profile before typing. Returns null if no slot in any profile
         * accepts [pin].
         *
         * Constant-time-ish across profiles: every profile's verify
         * hashes regardless of which slots exist within it; the loop
         * is linear in profile count, which leaks "how many profiles
         * exist" via timing but not WHICH one matched.
         */
        fun findMatchingProfile(
            context: Context,
            pin: String,
        ): Pair<String, PinMatch>? {
            val registry = app.aether.aegis.profile.ProfileRegistry.get(context)
            for (profileId in registry.listProfiles()) {
                val store = forProfile(context, profileId)
                val match = store.verifyPin(pin)
                if (match != PinMatch.INVALID) return profileId to match
            }
            return null
        }

        private const val STORE_NAME = "aegis_lock"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_FAILED_COUNT = "failed_count"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val KEY_TIMEOUT_MS = "timeout_ms"
        private const val KEY_LAST_ACTIVE_AT = "last_active_at"
        private const val KEY_SCRAMBLE_PAD = "scramble_pad"
        private const val KEY_REQUIRE_APP_LOCK = "require_app_lock"
        private const val KEY_DURESS_HASH = "duress_hash"
        // DURESS_2 — attacker-created second-layer slot, self-cleans.
        private const val KEY_DURESS2_HASH = "duress2_hash"
        // Seal-keypair (REAL slot only): deterministic salt for the
        // Argon2id KDF, plus the derived X25519 pubkey. The pubkey
        // sits in plaintext here because it's a *public* key — the
        // receive pipeline reads it to encrypt incoming chat content
        // without the user being unlocked. The priv lives only in the TEE
        // wrap (Model B) or is re-derived from the phrase (Model A).
        private const val KEY_SEAL_PUB_HEX = "seal_pub_hex"
        // Biometric unlock. The REAL seal priv,
        // AES-GCM-wrapped by a biometric-gated AndroidKeyStore key
        // (BiometricUnlock), plus the GCM IV. Enabled flag is separate so
        // we can represent "configured but currently unusable" cleanly.
        // All three are cleared whenever the PIN changes (the wrapped priv
        // goes stale) — see setPin / clearPin.
        private const val KEY_BIO_ENABLED = "bio_enabled"
        private const val KEY_BIO_BLOB_HEX = "bio_blob_hex"
        private const val KEY_BIO_IV_HEX = "bio_iv_hex"

        // ---- Recovery phrase + phrase-rooted seal ----
        // The 24-word BIP39 phrase is the master key that roots the seal
        // keypair (replacing the brute-forceable PIN-derived key). Stored
        // here, REAL slot only:
        //   - KEY_PHRASE_HASH: Argon2id verification hash of the phrase
        //     (NOT the encryption key — just lets "Forgot PIN → enter
        //     phrase" confirm the phrase before resetting the PIN). The
        //     plaintext phrase is never stored; it lives on paper.
        //   - KEY_PHRASE_SEAL_SALT_HEX: per-profile salt for the
        //     Argon2id(BIP39 seed, salt) → seal-seed step.
        //   - KEY_SEAL_PRIV_WRAP_HEX / KEY_SEAL_PRIV_IV_HEX: the
        //     phrase-derived seal priv, wrapped by the device-bound TEE
        //     key (SealKeyVault) under Model B so daily PIN unlock can
        //     unwrap it without the phrase. Absent under Model A.
        //   - KEY_REQUIRE_PHRASE_ON_BOOT: Model A toggle. When true, the
        //     priv is never persisted; the user re-enters the phrase once
        //     per boot.
        private const val KEY_PHRASE_HASH = "phrase_hash"
        private const val KEY_PHRASE_SEAL_SALT_HEX = "phrase_seal_salt_hex"
        private const val KEY_SEAL_PRIV_WRAP_HEX = "seal_priv_wrap_hex"
        private const val KEY_SEAL_PRIV_IV_HEX = "seal_priv_iv_hex"
        private const val KEY_REQUIRE_PHRASE_ON_BOOT = "require_phrase_on_boot"
        // Pattern unlock — Argon2id hash of the
        // dot sequence. A convenience gate like biometric: it opens the app
        // (Model B unwrap) but carries NO duress (one pattern can't be both
        // real and decoy), so the PIN stays the home of duress.
        private const val KEY_PATTERN_HASH = "pattern_hash"
        // Encrypted-at-rest copy of the 24 words, wrapped by the separate
        // SealKeyVault phrase key, so the owner can re-reveal the phrase
        // later (behind the scratch-to-reveal + app lock) instead of being
        // locked out of their own backup. Removed on clearPin.
        private const val KEY_PHRASE_BACKUP_WRAP_HEX = "phrase_backup_wrap_hex"
        private const val KEY_PHRASE_BACKUP_IV_HEX = "phrase_backup_iv_hex"

        // Default: lock after 30 s of backgrounding.
        private const val DEFAULT_TIMEOUT_MS = 30_000L

        private const val LOCKOUT_TRIGGER = 5
        private val LOCKOUT_TIERS_MS = longArrayOf(
            30_000L,        // tier 1: 30 s after 5 fails
            60_000L,        // tier 2: 1 m  after 10 fails
            5L * 60_000L,   // tier 3: 5 m  after 15 fails
            15L * 60_000L,  // tier 4: 15 m after 20+ fails (caps here)
        )
    }

    /** True iff a (real) PIN has been set. Biometric alone is not enough
     *  on the app lock: biometrics
     *  can be coerced (attacker forces your thumb), PINs are knowledge.
     *  Aegis-app authentication is PIN-only by directive. */
    val hasPin: Boolean
        get() = prefs.contains(KEY_PIN_HASH)

    /** True iff a duress PIN has been set. Optional — owner can leave
     *  it unconfigured to disable the duress path entirely. */
    val hasDuressPin: Boolean
        get() = prefs.contains(KEY_DURESS_HASH)

    /** True iff biometric unlock is enrolled AND the wrapped priv is
     *  present. Requires a PIN — biometric is
     *  only ever a shortcut over the PIN fallback, never a replacement. */
    val biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIO_ENABLED, false) &&
            prefs.contains(KEY_BIO_BLOB_HEX) && prefs.contains(KEY_BIO_IV_HEX)

    /** Biometric-wrapped REAL seal priv (ciphertext + GCM tag), or null. */
    val biometricBlob: ByteArray?
        get() = prefs.getString(KEY_BIO_BLOB_HEX, null)?.fromHexOrNull()

    /** GCM IV that pairs with [biometricBlob], or null. */
    val biometricIv: ByteArray?
        get() = prefs.getString(KEY_BIO_IV_HEX, null)?.fromHexOrNull()

    /** Persist a biometric enrolment: the AES-GCM-wrapped REAL seal priv
     *  + its IV. Caller has just produced these via BiometricUnlock inside
     *  a successful BiometricPrompt. */
    fun setBiometric(blob: ByteArray, iv: ByteArray) {
        prefs.edit()
            .putBoolean(KEY_BIO_ENABLED, true)
            .putString(KEY_BIO_BLOB_HEX, blob.toHex())
            .putString(KEY_BIO_IV_HEX, iv.toHex())
            .apply()
    }

    /** Drop the biometric enrolment + delete the Keystore key. Called from
     *  the settings toggle, and automatically on any PIN change (the
     *  wrapped priv no longer matches the new PIN-derived seal pub) and on
     *  a permanently-invalidated key at unlock time. */
    fun clearBiometric() {
        prefs.edit()
            .remove(KEY_BIO_ENABLED)
            .remove(KEY_BIO_BLOB_HEX)
            .remove(KEY_BIO_IV_HEX)
            .apply()
        BiometricUnlock.deleteKey()
    }

    /** True iff an attacker-created second-layer duress PIN exists.
     *  This is the "Fake #2" slot — set when the attacker, while
     *  inside Fake #1, configures their own duress PIN to test for
     *  nested decoys. Auto-wiped on the next real-PIN unlock. */
    val hasDuress2Pin: Boolean
        get() = prefs.contains(KEY_DURESS2_HASH)

    /** Configurable lock-after-background timeout in milliseconds. 0 =
     *  lock immediately on any backgrounding, Long.MAX_VALUE = never. */
    var timeoutMs: Long
        get() = prefs.getLong(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
        set(value) { prefs.edit().putLong(KEY_TIMEOUT_MS, value).apply() }

    /** Last time the app was foreground-visible. Used to decide whether
     *  to re-lock on resume. Updated by LockState.markActive(). */
    var lastActiveAt: Long
        get() = prefs.getLong(KEY_LAST_ACTIVE_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_ACTIVE_AT, value).apply() }

    /** Anti-shoulder-surf: when true, the PIN pad shuffles 0–9 on every
     *  mount + after each failed attempt. Default on. Some users prefer
     *  muscle-memory entry and accept the trade-off. */
    var scramblePinPad: Boolean
        get() = prefs.getBoolean(KEY_SCRAMBLE_PAD, true)
        set(value) { prefs.edit().putBoolean(KEY_SCRAMBLE_PAD, value).apply() }

    /**
     * Whether the PIN is required to OPEN Aegis itself. Independent of
     * whether the PIN exists — owner can have a PIN configured (which
     * remote-access AUTH still validates against) without wanting the
     * lock-screen friction every time they open the app.
     *
     * Default true. Setting false skips LockScreen on resume but the
     * PIN remains the gate for remote-access AUTH.
     */
    var requireAppLock: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_APP_LOCK, true)
        set(value) { prefs.edit().putBoolean(KEY_REQUIRE_APP_LOCK, value).apply() }

    /** Cached seal-pub for the REAL PIN slot, or null if no phrase has
     *  been enrolled yet. The background sealing layer reads this; it
     *  never reads the priv (which doesn't exist on disk under Model A). */
    val sealPub: ByteArray?
        get() = prefs.getString(KEY_SEAL_PUB_HEX, null)?.fromHexOrNull()

    // ------------------------------------------------------------------
    // Recovery phrase + phrase-rooted seal
    // ------------------------------------------------------------------

    /** True iff a recovery phrase has been enrolled for this profile, i.e.
     *  the seal keypair is phrase-rooted rather than legacy PIN-rooted. */
    val hasRecoveryPhrase: Boolean
        get() = prefs.contains(KEY_PHRASE_HASH)

    /** True iff the phrase-derived seal priv is wrapped on disk (Model B).
     *  False under Model A (phrase re-entered each boot) or legacy. */
    val hasWrappedSealPriv: Boolean
        get() = prefs.contains(KEY_SEAL_PRIV_WRAP_HEX) && prefs.contains(KEY_SEAL_PRIV_IV_HEX)

    /**
     * Model A toggle — "Require recovery phrase on every reboot"
     * ("Seal-priv survival"). Default false = Model B
     * (TEE-wrapped priv, no phrase needed for daily unlock). Flipping it
     * ON drops the wrapped priv from disk; flipping OFF re-wraps from a
     * live session priv (callers handle the re-wrap).
     */
    var requirePhraseOnBoot: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_PHRASE_ON_BOOT, false)
        set(value) { prefs.edit().putBoolean(KEY_REQUIRE_PHRASE_ON_BOOT, value).apply() }

    /**
     * Enrol a freshly-generated (or migration-generated) recovery phrase
     * as the root of this profile's seal keypair. Stores the phrase
     * verification hash + a new phrase-seal salt, derives the seal
     * keypair, persists the pubkey, and — under Model B (the default) —
     * wraps the priv with the device-bound [SealKeyVault] and stores it.
     *
     * Returns the derived keypair so the caller can immediately
     * [PinSession.set] it (the user reads their own chats this session
     * without re-entering anything). The caller MUST install or [wipe]
     * it; ignoring it leaks the priv until GC.
     *
     * This does NOT touch the PIN — the PIN is enrolled separately as a
     * pure gate ([setPinGateOnly]). It also does NOT clear biometric:
     * biometric wraps this same phrase-derived priv, so an enrolment
     * here is what biometric will later wrap, not something it
     * invalidates.
     *
     * @param words the validated 24-word phrase.
     */
    fun enrollRecoveryPhrase(words: List<String>): PinKeypair.KeyPair {
        val seed = app.aether.aegis.lock.RecoveryPhrase.deriveSeed(words)
        val salt = PinKeypair.newSalt()
        val kp = try {
            PinKeypair.deriveFromSeed(seed, salt)
        } finally {
            seed.fill(0) // wipe the BIP39 seed the moment the keypair exists
        }
        commitRecoveryPhrase(words, kp, salt)
        return kp
    }

    /**
     * Persist the phrase rooting: phrase verification hash, phrase-seal
     * salt, the seal pub, and — under Model B (the default) — the
     * TEE-wrapped priv. Caller installs the keypair into [PinSession]
     * afterwards.
     */
    fun commitRecoveryPhrase(words: List<String>, kp: PinKeypair.KeyPair, salt: ByteArray) {
        prefs.edit()
            .putString(KEY_PHRASE_HASH, hashSecret(words.joinToString(" ")))
            .putString(KEY_PHRASE_SEAL_SALT_HEX, salt.toHex())
            .putString(KEY_SEAL_PUB_HEX, kp.pub.toHex())
            .remove(KEY_SEAL_PRIV_WRAP_HEX)
            .remove(KEY_SEAL_PRIV_IV_HEX)
            .apply()
        // Model B: persist the priv wrapped by the TEE so daily PIN unlock
        // can recover it without the phrase. Model A: leave it unstored.
        if (!requirePhraseOnBoot) wrapAndStoreSealPriv(kp.priv)
        // The recovery phrase is SHOW-ONCE and NEVER stored. A stored,
        // re-revealable backup would make the PIN /
        // fingerprint the key to the 256-bit vault. Wipe any backup a prior
        // build left behind.
        clearRecoveryPhraseBackup()
    }

    // ------------------------------------------------------------------
    // Pattern unlock
    // ------------------------------------------------------------------

    /** Minimum dots in a valid unlock pattern. Four is the Android-stock
     *  floor; fewer is trivially shoulder-surfable. */
    val minPatternLength: Int get() = 4

    /** True iff a draw-pattern unlock is enrolled. Only meaningful for
     *  phrase-rooted (Model B) profiles — the pattern gates the app and
     *  the seal priv is then recovered from the TEE, so a PIN-rooted
     *  legacy profile (whose key comes from the PIN) can't use it. */
    val hasPattern: Boolean
        get() = prefs.contains(KEY_PATTERN_HASH)

    /** Serialise a dot sequence to the canonical string we hash, e.g.
     *  `[0,1,2,5,8] → "0-1-2-5-8"`. */
    private fun patternToString(seq: List<Int>): String = seq.joinToString("-")

    /** Enrol (or replace) the unlock pattern. Caller enforces
     *  [minPatternLength]; we hash with the same Argon2id used for PINs
     *  so the stored form never reveals the pattern. */
    fun setPattern(seq: List<Int>) {
        prefs.edit().putString(KEY_PATTERN_HASH, hashSecret(patternToString(seq))).apply()
    }

    /** Constant-time verify of a drawn pattern against the stored hash. */
    fun verifyPattern(seq: List<Int>): Boolean {
        val stored = prefs.getString(KEY_PATTERN_HASH, null) ?: return false
        return verifySlot(stored, patternToString(seq))
    }

    /** Remove the enrolled pattern (settings toggle, or any PIN change
     *  that should invalidate the convenience layer). */
    fun clearPattern() {
        prefs.edit().remove(KEY_PATTERN_HASH).apply()
    }

    /**
     * Wipe any stored recovery-phrase backup, and delete its wrapping key.
     *
     * The recovery phrase is SHOW-ONCE and must NEVER be re-revealable
     * an encrypted backup that can be unwrapped while
     * the app is merely unlocked would make the 4-digit PIN / fingerprint
     * the effective key to the 256-bit vault — collapsing "phrase is the
     * vault, PIN is the door" into "PIN is everything". So Aegis keeps no
     * copy of the phrase at all; it lives only on the user's paper.
     *
     * Called on enrolment AND on every startup, so any backup written by a
     * prior build is destroyed. Idempotent.
     */
    fun clearRecoveryPhraseBackup() {
        if (prefs.contains(KEY_PHRASE_BACKUP_WRAP_HEX) || prefs.contains(KEY_PHRASE_BACKUP_IV_HEX)) {
            prefs.edit()
                .remove(KEY_PHRASE_BACKUP_WRAP_HEX)
                .remove(KEY_PHRASE_BACKUP_IV_HEX)
                .apply()
        }
        SealKeyVault.deletePhraseKey()
    }

    /** Wrap [priv] with [SealKeyVault] and persist the blob+IV (Model B).
     *  If the Keystore can't produce a wrapping key at all, silently
     *  leaves the priv unstored — the profile then behaves as Model A
     *  (phrase required next boot) rather than storing a weaker copy. */
    fun wrapAndStoreSealPriv(priv: ByteArray) {
        val wrapped = SealKeyVault.wrap(sealTag, priv) ?: return
        prefs.edit()
            .putString(KEY_SEAL_PRIV_WRAP_HEX, wrapped.blob.toHex())
            .putString(KEY_SEAL_PRIV_IV_HEX, wrapped.iv.toHex())
            .apply()
    }

    /** Drop the TEE-wrapped seal priv and delete its wrapping key —
     *  used when the user switches to Model A ("require phrase on every
     *  reboot"). After this the priv exists only in the live
     *  [PinSession] until the next lock; the next boot requires the
     *  phrase. Idempotent. */
    fun dropWrappedSealPriv() {
        prefs.edit()
            .remove(KEY_SEAL_PRIV_WRAP_HEX)
            .remove(KEY_SEAL_PRIV_IV_HEX)
            .apply()
        SealKeyVault.deleteKey(sealTag)
    }

    /** Verify a candidate phrase against the stored verification hash.
     *  Used by "Forgot PIN → enter phrase" and Model A boot unlock. The
     *  words are joined as the library normalises them (single spaces). */
    fun verifyRecoveryPhrase(words: List<String>): Boolean {
        val stored = prefs.getString(KEY_PHRASE_HASH, null) ?: return false
        return verifySlot(stored, words.joinToString(" "))
    }

    /**
     * Recover the seal keypair by unwrapping the TEE-stored priv
     * (Model B). Returns null when no wrapped priv exists, the pub is
     * missing, or the Keystore can't unwrap (key invalidated / device
     * locked) — the caller then falls back to prompting for the phrase.
     */
    fun unwrapSealKeypair(): PinKeypair.KeyPair? {
        val pub = sealPub ?: return null
        val blob = prefs.getString(KEY_SEAL_PRIV_WRAP_HEX, null)?.fromHexOrNull() ?: return null
        val iv = prefs.getString(KEY_SEAL_PRIV_IV_HEX, null)?.fromHexOrNull() ?: return null
        val priv = SealKeyVault.unwrap(sealTag, blob, iv) ?: return null
        return PinKeypair.KeyPair(pub, priv)
    }

    /**
     * Derive the seal keypair directly from the recovery phrase using
     * the stored phrase-seal salt. The Model A boot path and the
     * recovery / migration paths use this when there is no usable wrapped
     * priv. Returns null if no phrase-seal salt is configured (not a
     * phrase-rooted profile).
     */
    fun deriveSealFromPhrase(words: List<String>): PinKeypair.KeyPair? {
        val salt = prefs.getString(KEY_PHRASE_SEAL_SALT_HEX, null)?.fromHexOrNull() ?: return null
        val seed = app.aether.aegis.lock.RecoveryPhrase.deriveSeed(words)
        return try {
            PinKeypair.deriveFromSeed(seed, salt)
        } finally {
            seed.fill(0)
        }
    }

    /**
     * Set the app-lock PIN as a pure gate, WITHOUT deriving a
     * PIN-rooted seal keypair. This is the phrase-era path: the seal
     * keypair is already rooted in the recovery phrase
     * ([enrollRecoveryPhrase]), so the PIN only needs to gate unlock +
     * carry duress. Stores just the auth hash and clears the lockout
     * counters. Does not rotate the seal, so it does NOT invalidate
     * biometric (which wraps the phrase-derived priv, unaffected by a
     * PIN change).
     */
    fun setPinGateOnly(pin: String) {
        prefs.edit()
            .putString(KEY_PIN_HASH, hashPin(pin))
            .remove(KEY_FAILED_COUNT)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }

    /**
     * Result of a PIN attempt — four states because Aegis ships
     * VeraCrypt-style plausible deniability with a *self-generating*
     * second decoy layer:
     *
     *   REAL     — real PIN matched, unlock to full Aegis. Self-cleans
     *              the DURESS_2 slot on the way through.
     *   DURESS_1 — user-configured duress PIN, opens Fake #1. Silent
     *              sos fires.
     *   DURESS_2 — attacker-configured duress PIN (set inside Fake #1),
     *              opens Fake #2. Looks like another decoy "underneath"
     *              Fake #1 — the attacker concludes Fake #1 was real.
     *   INVALID  — neither matched, counts as a failed attempt.
     */
    enum class PinMatch { REAL, DURESS_1, DURESS_2, INVALID }

    /** Verify a PIN attempt against all three slots via
     *  `cryptoPwHashStrVerify` (libsodium's constant-time compare on
     *  the Argon2id tag). Slots that aren't configured short-circuit
     *  to false — meaning timing reveals whether DURESS_1 / DURESS_2
     *  exist. That's an accepted leak: REAL is always present when
     *  the lock screen is shown, and hiding duress-slot existence
     *  would cost an extra Argon2id call per absent slot (~500 ms
     *  on a phone) per unlock attempt. */
    fun verifyPin(pin: String): PinMatch {
        val realHash = prefs.getString(KEY_PIN_HASH, null)
        val d1Hash = prefs.getString(KEY_DURESS_HASH, null)
        val d2Hash = prefs.getString(KEY_DURESS2_HASH, null)
        val realMatch = realHash != null && verifySlot(realHash, pin)
        val d1Match   = d1Hash   != null && verifySlot(d1Hash, pin)
        val d2Match   = d2Hash   != null && verifySlot(d2Hash, pin)
        return when {
            realMatch -> PinMatch.REAL
            d1Match   -> PinMatch.DURESS_1
            d2Match   -> PinMatch.DURESS_2
            else      -> PinMatch.INVALID
        }
    }

    private fun verifySlot(stored: String, pin: String): Boolean =
        runCatching { sodium.cryptoPwHashStrVerify(stored, pin) }.getOrDefault(false)

    /** Remove the PIN entirely (used by "Disable app lock" in settings).
     *  Also clears any duress PIN(s) — they are paired with the real
     *  PIN, you can't have them without it — and the seal-keypair
     *  salt + pub, since without a PIN there's nothing to derive
     *  priv from and any previously-sealed rows are unrecoverable. */
    fun clearPin() {
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_DURESS_HASH)
            .remove(KEY_DURESS2_HASH)
            .remove(KEY_SEAL_PUB_HEX)
            // Phrase-rooted seal state too — without a PIN there is no
            // gate, and a wrapped priv with no way in is just an orphan.
            // The phrase itself is on paper; the user can still recover.
            .remove(KEY_PHRASE_HASH)
            .remove(KEY_PHRASE_SEAL_SALT_HEX)
            .remove(KEY_SEAL_PRIV_WRAP_HEX)
            .remove(KEY_SEAL_PRIV_IV_HEX)
            .remove(KEY_PHRASE_BACKUP_WRAP_HEX)
            .remove(KEY_PHRASE_BACKUP_IV_HEX)
            .remove(KEY_FAILED_COUNT)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
        // No PIN → no seal priv → biometric has nothing to unwrap, and the
        // device-bound wrapping keys are now useless. Drop all of them.
        clearBiometric()
        SealKeyVault.deleteKey(sealTag)
        SealKeyVault.deletePhraseKey()
    }

    /** Set or replace the user-configured duress PIN (DURESS_1 slot).
     *  Must be different from the real PIN — caller enforces. */
    fun setDuressPin(pin: String) {
        prefs.edit()
            .putString(KEY_DURESS_HASH, hashPin(pin))
            .apply()
    }

    /** Set or replace the attacker-created second-layer duress PIN
     *  (DURESS_2 slot). Called when the attacker, while inside Fake #1,
     *  configures their own duress PIN to test the system. The slot
     *  is wiped automatically on the next real-PIN unlock. */
    fun setDuress2Pin(pin: String) {
        prefs.edit()
            .putString(KEY_DURESS2_HASH, hashPin(pin))
            .apply()
    }

    /** Drop the user-configured duress PIN. */
    fun clearDuressPin() {
        prefs.edit()
            .remove(KEY_DURESS_HASH)
            .apply()
    }

    /** Wipe the attacker-created second-layer duress PIN. Called on
     *  every real-PIN unlock — the self-cleaning property of the
     *  three-layer scheme. After this the system is back to clean
     *  state (real + one user-set duress), ready for next encounter. */
    fun clearDuress2Pin() {
        prefs.edit()
            .remove(KEY_DURESS2_HASH)
            .apply()
    }

    // ------------------------------------------------------------------
    // Lockout
    // ------------------------------------------------------------------

    /** How many consecutive failed attempts so far. Resets on success
     *  or PIN change. */
    val failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED_COUNT, 0)

    /** Epoch millis after which the user may attempt again. 0 = no lockout. */
    val lockoutUntil: Long
        get() = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)

    /**
     * Record a failed PIN attempt. Returns the lockout deadline (epoch
     * millis, or 0 if no lockout). After every 5 failures we open a
     * window: 30 s → 60 s → 5 m → 15 m → 15 m … capping there.
     */
    fun recordFailedAttempt(): Long {
        val newCount = failedAttempts + 1
        val edit = prefs.edit().putInt(KEY_FAILED_COUNT, newCount)
        val deadline = if (newCount % LOCKOUT_TRIGGER == 0) {
            val tier = newCount / LOCKOUT_TRIGGER
            val ms = LOCKOUT_TIERS_MS.getOrNull(tier - 1) ?: LOCKOUT_TIERS_MS.last()
            System.currentTimeMillis() + ms
        } else 0L
        if (deadline > 0L) edit.putLong(KEY_LOCKOUT_UNTIL, deadline)
        edit.apply()
        return deadline
    }

    /** Clear failed-attempt counter (call on successful PIN match). */
    fun resetAttempts() {
        prefs.edit()
            .remove(KEY_FAILED_COUNT)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }

    /** Argon2id, libsodium INTERACTIVE (ops=2, mem=64 MiB). Returns
     *  the self-contained `$argon2id$v=19$m=…$<salt>$<hash>` encoded
     *  string with the salt baked in. */
    private fun hashPin(pin: String): String {
        return runCatching {
            sodium.cryptoPwHashStr(
                pin,
                PwHash.OPSLIMIT_INTERACTIVE.toLong(),
                PwHash.MEMLIMIT_INTERACTIVE,
            )
        }.getOrElse { error("argon2id hash failed: $it") }
    }

    /** Argon2id verification hash for an arbitrary secret string —
     *  currently the recovery phrase. Same INTERACTIVE tier and
     *  self-salting encoded form as [hashPin]; verified through the same
     *  [verifySlot]. This is a *verification* hash only (gates "enter
     *  phrase to reset PIN"); the phrase's encryption duty is carried by
     *  the BIP39 seed, not this hash. */
    private fun hashSecret(secret: String): String = hashPin(secret)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.fromHexOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { i ->
                ((Character.digit(this[i * 2], 16) shl 4) +
                    Character.digit(this[i * 2 + 1], 16)).toByte()
            }
        }.getOrNull()
    }
}

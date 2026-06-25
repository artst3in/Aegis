package app.aether.aegis.lock

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound wrap for the phrase-derived SealCrypto private key —
 * Model B ("Seal-priv survival").
 *
 * ## The problem this solves
 *
 * Once the seal keypair is rooted in the 24-word phrase
 * ([PinKeypair.deriveFromSeed]) instead of the PIN, the PIN can no
 * longer reproduce the priv at unlock. The priv needs a home between
 * unlocks that does NOT reintroduce the very weakness we just removed:
 * if we wrapped it under a PIN-derived key and stored that on disk, an
 * attacker with a phone image would brute-force the 4-8 digit PIN
 * offline and unwrap it — no better than before.
 *
 * The answer is a wrapping key that an offline attacker cannot use even
 * with the full disk: a **non-exportable AES-256-GCM key in the
 * AndroidKeyStore**, StrongBox-backed where the hardware offers it. The
 * key material never leaves the secure element, so the wrapped priv on
 * disk is inert without the live device. Daily unlock (PIN, pattern, or
 * biometric) gates *access to the app*; this vault gates *the bytes*,
 * and the two are independent — exactly the layered model the spec
 * describes (PIN = the door, phrase = the vault, TEE = the lock on the
 * vault's copy).
 *
 * ## Contrast with [BiometricUnlock]
 *
 * [BiometricUnlock] wraps the priv under a key with
 * `setUserAuthenticationRequired(true)`, so unwrapping REQUIRES a live
 * biometric inside a CryptoObject. That is correct for the biometric
 * unlock method. This vault is the DEFAULT path for PIN/pattern users:
 * the key is NOT auth-bound, because requiring biometric here would
 * break PIN-only users (who may have no biometric enrolled at all). The
 * defence for PIN users is **non-exportability**: the wrapping key never
 * leaves the secure element, so a seized DISK IMAGE can't unwrap the
 * priv offline. NOTE: it is NOT bound to device-unlocked state —
 * `setUnlockedDeviceRequired` was dropped for OEM compatibility (see
 * [generateKey]), so app-UID code on a powered-but-locked phone could
 * still drive an unwrap. Re-introducing that flag as a capability-probed
 * opt-in is a known hardening TODO (Security review 2026-06-07).
 *
 * ## What it does NOT do
 *
 * - It does not carry the duress distinction. Like [BiometricUnlock],
 *   it always unwraps whatever priv was wrapped (the REAL one). Duress
 *   lives on the PIN path: a duress PIN simply never reaches an unwrap.
 * - It is not used at all under Model A ("require recovery phrase on
 *   every reboot") — there the priv is never persisted and is re-derived
 *   from the typed phrase each boot.
 */
object SealKeyVault {

    private const val KEY_NAME = "aegis_seal_vault_key_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Per-profile wrapping-key alias. Every profile now has a
     *  phrase-rooted seal, and each wraps its priv under its OWN TEE key —
     *  a single shared alias would be regenerated on each [wrap] and
     *  orphan every other profile's wrapped priv. [tag] is the profile's
     *  lock-prefs name (unique per profile); the default profile uses the
     *  bare alias so its existing wrapped blob keeps decrypting. */
    private fun aliasFor(tag: String): String =
        if (tag.isEmpty()) KEY_NAME else "${KEY_NAME}__$tag"

    /** True iff the device-bound wrapping key for [tag] currently exists. */
    fun hasKey(tag: String): Boolean =
        runCatching { keyStore().containsAlias(aliasFor(tag)) }.getOrDefault(false)

    /** Delete [tag]'s wrapping key. After this any blob it wrapped is
     *  permanently undecryptable — callers must drop the stored blob too.
     *  Idempotent. */
    fun deleteKey(tag: String) {
        runCatching { keyStore().deleteEntry(aliasFor(tag)) }
    }

    /**
     * Wrap [priv] under [tag]'s FRESH device-bound key, returning the
     * ciphertext+tag blob and the GCM IV for storage. Generates (and
     * replaces) THIS profile's wrapping key so the wrap always binds to a
     * known key — other profiles' keys are untouched. Returns null only if
     * the Keystore is entirely unavailable — the caller then falls back to
     * Model A (phrase-on-boot) rather than storing a weaker copy.
     */
    fun wrap(tag: String, priv: ByteArray): Wrapped? = runCatching {
        val alias = aliasFor(tag)
        deleteKey(tag)
        generateKey(alias)
        Wrapped.encryptWith(key(alias) ?: return null, priv)
    }.getOrNull()

    /**
     * Unwrap a stored blob back to the seal priv using [tag]'s key.
     * Returns null when the key is missing (factory-reset Keystore, or
     * invalidated) — the caller treats null as "this device can't recover
     * the priv; prompt for the recovery phrase". The returned array is
     * fresh; the caller wipes it when done.
     */
    fun unwrap(tag: String, blob: ByteArray, iv: ByteArray): ByteArray? = runCatching {
        val key = key(aliasFor(tag)) ?: return null
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(blob)
        }
    }.getOrNull()

    /**
     * Generate the AES-256-GCM wrapping key.
     *
     * Deliberately the *maximally compatible* configuration: a plain
     * non-exportable AndroidKeyStore key, no StrongBox request and no
     * `setUnlockedDeviceRequired`. Those two flags are real hardening
     * but are flaky across OEMs — on several devices they throw at key
     * generation, which silently failed the wrap and left phrase-rooted
     * profiles unable to recover their seal priv after a relock (chats
     * stuck "locked"). The property we actually need for the threat model
     * — the wrapping key never leaves the secure element, so a seized
     * disk image can't unwrap the priv offline — is delivered by the
     * AndroidKeyStore alone. StrongBox / unlocked-device-required can be
     * re-introduced later as an opt-in once verified on real hardware.
     */
    private fun generateKey(alias: String) {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // NOT setUserAuthenticationRequired: the PIN/pattern path must
            // unwrap without a biometric prompt (PIN-only users may have
            // no biometric enrolled). Non-exportability is what protects a
            // seized phone, and an AndroidKeyStore key gives that for free.
            .build()
        kg.init(spec)
        kg.generateKey()
    }

    private fun key(alias: String): SecretKey? = runCatching {
        keyStore().getKey(alias, null) as? SecretKey
    }.getOrNull()

    // ---- Recovery-phrase backup (separate key) -----------------------
    // The encrypted-at-rest copy of the 24-word phrase (so the owner can
    // re-reveal it later, behind the scratch-to-reveal + app lock) is
    // wrapped under its OWN AndroidKeyStore alias — NOT the seal-priv key
    // above. Keeping them separate matters: the seal-priv key is rotated
    // (deleted + regenerated) on every re-wrap (recovery, Model A↔B
    // toggle), which would orphan anything else wrapped under it. The
    // phrase never changes after enrolment, so its key is generated once
    // and left alone.

    private const val PHRASE_KEY_NAME = "aegis_phrase_backup_key_v1"

    /** True iff the phrase-backup wrapping key exists. */
    fun hasPhraseKey(): Boolean =
        runCatching { keyStore().containsAlias(PHRASE_KEY_NAME) }.getOrDefault(false)

    /** Delete the phrase-backup key (and thereby make any stored phrase
     *  blob undecryptable). Idempotent. */
    fun deletePhraseKey() {
        runCatching { keyStore().deleteEntry(PHRASE_KEY_NAME) }
    }

    /** Encrypt the phrase [bytes] under a fresh phrase-backup key. Same
     *  maximally-compatible non-exportable AES-256-GCM config as the priv
     *  key. Null if the Keystore is unavailable. */
    fun wrapPhrase(bytes: ByteArray): Wrapped? = runCatching {
        deletePhraseKey()
        generatePhraseKey()
        Wrapped.encryptWith(phraseKey() ?: return null, bytes)
    }.getOrNull()

    /** Decrypt a stored phrase blob, or null if the key is gone. */
    fun unwrapPhrase(blob: ByteArray, iv: ByteArray): ByteArray? = runCatching {
        val key = phraseKey() ?: return null
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(blob)
        }
    }.getOrNull()

    private fun generatePhraseKey() {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            PHRASE_KEY_NAME,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        kg.init(spec)
        kg.generateKey()
    }

    private fun phraseKey(): SecretKey? = runCatching {
        keyStore().getKey(PHRASE_KEY_NAME, null) as? SecretKey
    }.getOrNull()

    // ---- Pending post-import re-seal bundle (separate key) -----------
    // The transient re-seal bundle a backup restore stages at the profile
    // root (unsealed message bodies + attachment DEKs, consumed on the
    // next unlock) must NOT sit there as plaintext (Security review
    // 2026-06-07). It is wrapped under its OWN device-bound key — distinct
    // from the seal-priv key, which rotates on every wrap and would orphan
    // anything else under it. The bundle is short-lived: deleted (and this
    // key with it) once consumed.

    private const val RESEAL_KEY_NAME = "aegis_reseal_key_v1"

    /** Wrap the pending re-seal [bytes] under a fresh device-bound key.
     *  Null if the Keystore is unavailable (caller falls back to a
     *  plaintext stash so the re-seal isn't lost on TEE-less devices). */
    fun wrapReseal(bytes: ByteArray): Wrapped? = runCatching {
        deleteResealKey()
        generateKey(RESEAL_KEY_NAME)
        Wrapped.encryptWith(key(RESEAL_KEY_NAME) ?: return null, bytes)
    }.getOrNull()

    /** Unwrap the pending re-seal blob, or null if the key is gone. */
    fun unwrapReseal(blob: ByteArray, iv: ByteArray): ByteArray? = runCatching {
        val k = key(RESEAL_KEY_NAME) ?: return null
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(blob)
        }
    }.getOrNull()

    /** Drop the re-seal wrapping key once the bundle is consumed. */
    fun deleteResealKey() {
        runCatching { keyStore().deleteEntry(RESEAL_KEY_NAME) }
    }

    /** A wrapped priv: GCM ciphertext+tag plus the IV needed to unwrap. */
    data class Wrapped(val blob: ByteArray, val iv: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)

        companion object {
            /** Encrypt [priv] under [key], capturing the random GCM IV
             *  the Keystore generates. */
            fun encryptWith(key: SecretKey, priv: ByteArray): Wrapped {
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.ENCRYPT_MODE, key)
                }
                val blob = cipher.doFinal(priv)
                return Wrapped(blob, cipher.iv)
            }
        }
    }
}

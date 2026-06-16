package app.aether.aegis.vault

import android.content.Context
import android.content.SharedPreferences
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import java.security.SecureRandom

/**
 * PIN-protect the vault as a layer above the app lock. Optional — when
 * no vault PIN is set, opening the vault uses whatever the app lock
 * already granted. When set, the vault re-prompts for its own PIN
 * (different from the app PIN) before showing any content.
 *
 * Three states the user can opt into:
 *   1. No vault PIN              — vault unlocks with the app PIN
 *      (status quo).
 *   2. Vault PIN only            — separate PIN, single volume.
 *      Implemented here.
 *   3. Vault PIN + Duress Vault  — VeraCrypt-style hidden volume.
 *      Deferred: needs the two-slot crypto-key layer + a hidden
 *      vault container.  This file is shaped to grow into that —
 *      slot A is the normal vault PIN, slot B will be the duress
 *      (always-present-random when unset so an attacker can't
 *      distinguish "no duress" from "duress with a key they don't
 *      have").
 *
 * Per-profile via ProfileRoot.prefsName: Family A and Family B can
 * carry independent vault PINs even on the same device.
 *
 * Crypto: Argon2id (libsodium INTERACTIVE params) for both the auth
 * hash and the KDF salt that VaultCrypto pairs with for the data
 * encryption key. Two independent Argon2id calls per unlock: one
 * `cryptoPwHashStr` for auth (salt baked into the encoded string),
 * one explicit-salt `cryptoPwHash` for the AES-256 KDF. The PIN
 * salt stored here is consumed by VaultCrypto only.
 */
class VaultLockStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE_NAME),
            Context.MODE_PRIVATE,
        )

    private val sodium = app.aether.aegis.crypto.Sodium.shared

    /** True iff a vault PIN has been configured. When false the
     *  vault opens without a re-prompt. Both the auth hash and the
     *  KDF salt must be present — the salt is what VaultCrypto pairs
     *  with the PIN to derive the AES-256 data key. */
    val hasPin: Boolean
        get() = prefs.contains(KEY_PIN_HASH) && prefs.contains(KEY_PIN_SALT)

    /** True iff a duress vault PIN has been configured. Optional —
     *  when unset, only the normal PIN unlocks the vault and there
     *  is no hidden slot. */
    val hasDuressPin: Boolean
        get() = prefs.contains(KEY_DURESS_HASH) && prefs.contains(KEY_DURESS_SALT)

    /** Outcome of a PIN attempt. NORMAL → main vault contents,
     *  DURESS → hidden vault contents, INVALID → wrong PIN. The UI
     *  presents both unlocked states identically so an attacker who
     *  watches the unlock can't tell which they entered. */
    enum class PinMatch { NORMAL, DURESS, INVALID }

    /** Set or replace the normal vault PIN. Wipes the failed-attempt
     *  counter so the new PIN starts clean. */
    fun setPin(pin: String) {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_PIN_HASH, hashPin(pin))
            .putString(KEY_PIN_SALT, salt.toHex())
            .remove(KEY_FAILED_COUNT)
            .apply()
    }

    /** Remove the normal vault PIN. Also clears the duress slot —
     *  it can't exist on its own. The vault becomes accessible
     *  directly (governed only by the app lock). */
    fun clearPin() {
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .remove(KEY_DURESS_HASH)
            .remove(KEY_DURESS_SALT)
            .remove(KEY_FAILED_COUNT)
            .apply()
    }

    /** Set or replace the duress vault PIN. Must differ from the
     *  normal one (caller enforces). When entered, unlocks into the
     *  hidden vault — a parallel set of entries the normal vault
     *  doesn't see. */
    fun setDuressPin(pin: String) {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_DURESS_HASH, hashPin(pin))
            .putString(KEY_DURESS_SALT, salt.toHex())
            .apply()
    }

    /** Remove the duress vault PIN. Hidden vault entries remain in
     *  storage but become unreachable through the gate — restoring
     *  the duress PIN to the same value would bring them back. */
    fun clearDuressPin() {
        prefs.edit()
            .remove(KEY_DURESS_HASH)
            .remove(KEY_DURESS_SALT)
            .apply()
    }

    /**
     * Unlock the vault with [pin] — verifies, then on a match
     * derives the matching slot's AES-256 encryption key and
     * populates [VaultSession] with the slot + key. Returns the
     * same PinMatch enum verifyPin does. Use this from the gate
     * UI; reserve plain [verifyPin] for places that just need to
     * confirm "user knows the current PIN" without unlocking
     * (e.g. PIN-change confirmation in settings).
     */
    fun unlock(pin: String): PinMatch {
        val match = verifyPin(pin)
        val salt = when (match) {
            PinMatch.NORMAL -> prefs.getString(KEY_PIN_SALT, null)?.fromHex()
            PinMatch.DURESS -> prefs.getString(KEY_DURESS_SALT, null)?.fromHex()
            PinMatch.INVALID -> null
        } ?: return match
        val key = VaultCrypto.deriveKey(pin, salt)
        // Replace any previously-held session key.
        VaultCrypto.wipe(VaultSession.encryptionKey)
        VaultSession.encryptionKey = key
        VaultSession.slot = match
        VaultSession.unlocked = true
        return match
    }

    /**
     * After a PIN set/change: derive the new key and prime
     * [VaultSession] with it. Used by the settings flow so the
     * caller doesn't need to re-prompt the user for the PIN they
     * just typed in. [pin] must be the just-set PIN (caller
     * knows it).
     */
    fun primeKey(pin: String, match: PinMatch) {
        val key = deriveKey(pin, match) ?: return
        VaultCrypto.wipe(VaultSession.encryptionKey)
        VaultSession.encryptionKey = key
    }

    /**
     * Derive (without storing) the encryption key for [pin]
     * against [slot]'s currently-stored salt. Doesn't verify
     * [pin] — caller is responsible. Returns null if the slot
     * has no salt configured (no PIN there yet).
     *
     * Used by the re-encryption path in Settings: capture
     * `oldKey = deriveKey(currentPin, slot)` BEFORE setPin
     * overwrites the salt, then `newKey = deriveKey(newPin, slot)`
     * AFTER. The two keys feed into
     * Repository.reencryptVaultSlot so the body ciphertext stays
     * keyed off the CURRENT PIN.
     */
    fun deriveKey(pin: String, slot: PinMatch): ByteArray? {
        val salt = when (slot) {
            PinMatch.NORMAL -> prefs.getString(KEY_PIN_SALT, null)?.fromHex()
            PinMatch.DURESS -> prefs.getString(KEY_DURESS_SALT, null)?.fromHex()
            PinMatch.INVALID -> null
        } ?: return null
        return VaultCrypto.deriveKey(pin, salt)
    }

    /** Verify the entered PIN against both slots. The normal slot's
     *  verify is unconditional (gate would not be shown without
     *  `hasPin`). The duress slot's verify is the same Argon2id
     *  evaluation whether or not a duress slot is configured —
     *  when the slot is empty we still hash the PIN with
     *  `cryptoPwHashStr` and discard the result, so the wall-clock
     *  time of [verifyPin] does not betray "duress slot exists".
     *  LazySodium's `Verify` does its own constant-time compare on
     *  the resulting tag. */
    fun verifyPin(pin: String): PinMatch {
        val normHash = prefs.getString(KEY_PIN_HASH, null)
        val dHash    = prefs.getString(KEY_DURESS_HASH, null)
        val normMatch = normHash != null && verifySlot(normHash, pin)
        val dMatch = if (dHash != null) {
            verifySlot(dHash, pin)
        } else {
            // Burn the equivalent of one Argon2id verify so timing
            // doesn't reveal whether a duress slot is configured.
            runCatching { hashPin(pin) }
            false
        }
        return when {
            normMatch -> PinMatch.NORMAL
            dMatch    -> PinMatch.DURESS
            else      -> PinMatch.INVALID
        }
    }

    private fun verifySlot(stored: String, pin: String): Boolean =
        runCatching { sodium.cryptoPwHashStrVerify(stored, pin) }.getOrDefault(false)

    /** Number of consecutive failed attempts. Resets on a successful
     *  verify (caller's responsibility — see [resetAttempts]). */
    val failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED_COUNT, 0)

    /** Bump the failure counter. UI uses this to gate retry pacing.
     *  No automatic lockout window here — the vault is local-only,
     *  so the threat model is "shoulder-surfer who already has the
     *  device unlocked"; we slow them down but don't permanently
     *  lock the vault. */
    fun recordFailedAttempt(): Int {
        val n = failedAttempts + 1
        prefs.edit().putInt(KEY_FAILED_COUNT, n).apply()
        return n
    }

    fun resetAttempts() {
        prefs.edit().remove(KEY_FAILED_COUNT).apply()
    }

    /** Argon2id, INTERACTIVE params (~ops=2, mem=64 MiB). Salt is
     *  baked into the returned `$argon2id$…` encoded string — the
     *  salt persisted in KEY_*_SALT is a separate one consumed by
     *  VaultCrypto's KDF, not by this auth hash. */
    private fun hashPin(pin: String): String {
        return runCatching {
            sodium.cryptoPwHashStr(
                pin,
                PwHash.OPSLIMIT_INTERACTIVE.toLong(),
                PwHash.MEMLIMIT_INTERACTIVE,
            )
        }.getOrElse { error("argon2id hash failed: $it") }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0) return null
        return ByteArray(length / 2) { i ->
            ((substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null)
                and 0xff).toByte()
        }
    }

    private companion object {
        private const val STORE_NAME = "aegis_vault_lock"
        private const val KEY_PIN_HASH = "vault_pin_hash"
        private const val KEY_PIN_SALT = "vault_pin_salt"
        private const val KEY_DURESS_HASH = "vault_duress_hash"
        private const val KEY_DURESS_SALT = "vault_duress_salt"
        private const val KEY_FAILED_COUNT = "vault_failed_count"
        private const val SALT_LEN = 16
    }
}

/**
 * In-memory unlock token for the current process. Once the user
 * passes the vault PIN gate, [VaultSession.unlocked] is true until
 * the process dies or the user explicitly locks. Stays in process
 * memory only — never persisted to disk, never cached past a kill.
 *
 * Tracks WHICH slot unlocked the vault — [slot] = NORMAL means the
 * main vault is showing, DURESS means the hidden vault is showing.
 * Repository queries filter `secure_notes.keySlot` by this value
 * so each slot sees a disjoint view of entries; new entries land
 * in the active slot. When no vault PIN is set, slot is NORMAL.
 *
 * Hidden-volume note: this segregation is by table column, not by
 * per-entry encryption, so a forensic actor with the SQLCipher DB
 * key (derived from the identity key, requires both root + key
 * extraction) CAN see both slots. The intent here is plausible
 * deniability under coercion, not crypto-strict deniability — the
 * attacker who forces "your vault PIN" sees the duress slot's
 * decoy content and has no reason to suspect a hidden slot
 * exists. Stronger deniability needs per-entry AES-GCM keyed off
 * the PIN, which is the natural follow-up.
 */
object VaultSession {
    @Volatile var unlocked: Boolean = false
    @Volatile var slot: VaultLockStore.PinMatch = VaultLockStore.PinMatch.NORMAL
    /** AES-256 key derived from the PIN that unlocked this session
     *  via Argon2id. Null when no PIN gate is configured. Zeroed on
     *  [lock]. */
    @Volatile var encryptionKey: ByteArray? = null

    fun lock() {
        unlocked = false
        slot = VaultLockStore.PinMatch.NORMAL
        VaultCrypto.wipe(encryptionKey)
        encryptionKey = null
    }
}

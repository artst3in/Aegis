package app.aether.aegis.vault

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-entry AES-GCM for the vault. A follow-up:
 * the slot-column-only segregation (which landed earlier) is
 * vulnerable to a forensic with both root AND the SQLCipher DB key,
 * because SQLCipher decrypts every row regardless of slot. Adding
 * per-entry encryption keyed off the slot's PIN makes the inactive
 * slot's data indistinguishable from random without the slot's
 * key — the actual crypto-deniability the user asked for.
 *
 * Threat model:
 *   - Attacker has root + DB key                          ← defeats SQLCipher alone
 *   - Attacker does NOT have the duress vault PIN
 *   - Result: hidden-vault rows look like random bytes,
 *     no oracle to confirm "there is hidden content here"
 *
 * Key derivation: Argon2id (libsodium, INTERACTIVE params) with the
 * slot's stored salt and the PIN. Independent of VaultLockStore's
 * auth hash — that one uses `cryptoPwHashStr` with its own internal
 * salt, this one uses `cryptoPwHash` with our explicit per-slot
 * salt — so recovering either output reveals nothing about the
 * other.
 *
 * Wire format per row: 12-byte random IV + AES/GCM/NoPadding
 * ciphertext (which includes the 16-byte GCM tag at the end). The
 * IV is stored in its own DB column rather than prepended to the
 * ciphertext — clearer, and Room handles BLOB columns directly.
 */
object VaultCrypto {

    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12
    private const val KEY_LEN = 32  // 256 bits

    private val sodium = app.aether.aegis.crypto.Sodium.shared

    /**
     * Derive a 256-bit AES key from a PIN + salt via Argon2id at
     * libsodium's INTERACTIVE cost (~ops=2, mem=64 MiB). The salt
     * MUST be the same one VaultLockStore persisted for the slot —
     * its only consumer is this function. Auth hashing uses a
     * separate Argon2id call inside VaultLockStore with libsodium's
     * own salt, so the two derivations are independent even though
     * they share the PIN.
     */
    fun deriveKey(pin: String, salt: ByteArray): ByteArray {
        require(salt.size == PwHash.SALTBYTES) {
            "salt must be ${PwHash.SALTBYTES} bytes, got ${salt.size}"
        }
        val hex = sodium.cryptoPwHash(
            pin,
            KEY_LEN,
            salt,
            PwHash.OPSLIMIT_INTERACTIVE.toLong(),
            PwHash.MEMLIMIT_INTERACTIVE,
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        return hex.hexToByteArray().also {
            check(it.size == KEY_LEN) { "argon2id returned ${it.size} bytes, expected $KEY_LEN" }
        }
    }

    /**
     * Encrypt [plain] under [key]. Returns (ciphertext, iv).
     * Ciphertext includes the GCM auth tag. Throws on JCE setup
     * failure — caller wraps in runCatching.
     */
    fun encrypt(plain: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
        require(key.size == KEY_LEN) { "key must be $KEY_LEN bytes" }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return cipher.doFinal(plain) to iv
    }

    /**
     * Decrypt [ct] under [key] with the given [iv]. Returns null on
     * any failure — wrong key (AEADBadTagException), malformed
     * input, JCE error. Caller treats null as "this entry is not
     * for this slot" and skips it.
     */
    fun decrypt(ct: ByteArray, iv: ByteArray, key: ByteArray): ByteArray? {
        if (key.size != KEY_LEN) return null
        if (iv.size != IV_LEN) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.doFinal(ct)
        }.getOrNull()
    }

    /** Zero out a key in-place so a heap dump after lock can't pull
     *  it back. Best-effort — the JVM may have copied the byte
     *  array, but we do what we can. */
    fun wipe(key: ByteArray?) {
        key?.fill(0)
    }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "hex string has odd length" }
        return ByteArray(length / 2) { i ->
            val hi = substring(i * 2, i * 2 + 1).toInt(16)
            val lo = substring(i * 2 + 1, i * 2 + 2).toInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }
}

package app.aether.aegis.lock

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android-Keystore plumbing for biometric app-unlock.
 *
 * The chat-decryption key (the X25519 seal priv) is derived from the
 * PIN via Argon2id — biometric auth alone yields no key material, so a
 * plain BiometricPrompt couldn't decrypt anything. The standard Android
 * answer, used here: a hardware-backed AES-GCM key in the AndroidKeyStore
 * with `setUserAuthenticationRequired(true)`. The key can only be USED
 * inside a BiometricPrompt CryptoObject that the OS unlocks on a
 * successful BIOMETRIC_STRONG auth. We wrap the seal priv with it at
 * enrolment and unwrap it on unlock.
 *
 * Security properties this gives us, for free, from the OS:
 *   - The wrapped priv can't be read without a live biometric match —
 *     not by other apps, not by a rooted dump of SharedPreferences
 *     (the AES key never leaves the TEE/StrongBox).
 *   - `setInvalidatedByBiometricEnrollment(true)`: enrolling a NEW
 *     fingerprint/face permanently invalidates the key. An attacker who
 *     adds their own fingerprint can't then unwrap the owner's priv —
 *     the unlock fails and we fall back to the PIN (which carries duress).
 *
 * What it deliberately does NOT do: carry the duress/real distinction.
 * Biometric always unwraps the REAL priv (that's what was enrolled), so
 * biometric always opens the real profile. Plausible deniability lives
 * only on the PIN fallback — see the spec's one-time warning.
 */
object BiometricUnlock {

    private const val KEY_NAME = "aegis_biometric_seal_key_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Wipe the Keystore key. Called when biometric is disabled or when
     *  the PIN changes (the wrapped priv is then stale). Idempotent. */
    fun deleteKey() {
        runCatching { keyStore().deleteEntry(KEY_NAME) }
    }

    /** Create a FRESH biometric-gated key and return a Cipher initialised
     *  for ENCRYPT, ready to wrap in a BiometricPrompt.CryptoObject for
     *  enrolment. Replaces any prior key so the wrap binds to the current
     *  biometric enrolment. Null if the device/Keystore can't honour the
     *  spec (no biometric hardware, etc.). */
    fun newEnrolmentCipher(): Cipher? = runCatching {
        deleteKey()
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_NAME,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // Per-use auth: every encrypt/decrypt must sit inside a fresh
            // biometric prompt.
            .setUserAuthenticationRequired(true)
            // New biometric enrolment nukes the key → attacker-added
            // fingerprint can't unwrap the owner's priv.
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        kg.init(spec)
        kg.generateKey()
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
    }.getOrNull()

    /** Cipher initialised for DECRYPT against the stored [iv], ready to
     *  wrap in a CryptoObject for unlock. Null when the key is missing or
     *  permanently invalidated (e.g. a new fingerprint was enrolled since
     *  enrolment) — the caller treats null as "biometric unavailable,
     *  fall back to PIN" and clears the stale enrolment. */
    fun unlockCipher(iv: ByteArray): Cipher? = runCatching {
        val key = key() ?: return null
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }.getOrNull()

    private fun key(): SecretKey? = runCatching {
        keyStore().getKey(KEY_NAME, null) as? SecretKey
    }.getOrNull()
}

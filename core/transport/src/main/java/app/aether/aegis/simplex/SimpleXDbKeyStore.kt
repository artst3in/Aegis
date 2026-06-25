package app.aether.aegis.simplex

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-wrapped passphrase for the SimpleX core's own
 * SQLCipher-encrypted DB (`simplex_v1_agent.db` / `simplex_v1_chat.db`).
 *
 * Pre-this-module, the passphrase passed to `chatMigrateInit` was
 * `identity.deviceId.take(64)` — the device's PUBLIC key, broadcast
 * on every contact pair. That gave the core DB essentially zero
 * at-rest protection: anyone with a copy of the .db file plus the
 * device's public identity (trivially obtained) could decrypt it.
 *
 * This module mints a 32-byte random passphrase on first use, wraps
 * it with an AndroidKeyStore AES-256-GCM key (hardware-backed on
 * modern Pixels), and persists the wrapped blob under
 * `filesDir/simplex_v1_dbkey.wrapped`. On subsequent boots the wrap
 * is unwrapped to recover the passphrase. The underlying AES key
 * never leaves the secure element; even root can only ask for
 * decrypt operations.
 *
 * Single global file (not per-profile) — SimpleX core is a single
 * process-wide singleton serving every profile.
 */
class SimpleXDbKeyStore(context: Context) {

    private val wrappedFile: File = File(context.filesDir, WRAPPED_FILE_NAME)

    /** True iff a Keystore-wrapped passphrase exists on disk. When
     *  false, the caller is on a fresh install: use [generateAndPersist]
     *  then init the core with the returned passphrase. */
    val hasWrappedPassphrase: Boolean
        get() = wrappedFile.exists()

    /** Unwrap and return the persisted passphrase. Throws if not
     *  persisted — caller checks [hasWrappedPassphrase] first. */
    fun loadPassphrase(): String {
        check(wrappedFile.exists()) { "no wrapped passphrase persisted" }
        val raw = wrappedFile.readBytes()
        require(raw.size > GCM_IV_BYTES) { "wrapped passphrase truncated" }
        val iv = raw.copyOfRange(0, GCM_IV_BYTES)
        val ct = raw.copyOfRange(GCM_IV_BYTES, raw.size)
        val plain = decryptWithKeystore(iv, ct)
        return try {
            Base64.encodeToString(plain, Base64.NO_WRAP)
        } finally {
            plain.fill(0)
        }
    }

    /** Mint a fresh 32-byte passphrase, wrap, persist. Returns the
     *  unwrapped base64 form for the caller to hand to
     *  `chatMigrateInit`. Use on fresh installs where there's no
     *  existing DB to migrate. */
    fun generateAndPersist(): String {
        val raw = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            persistWrapped(raw)
            return Base64.encodeToString(raw, Base64.NO_WRAP)
        } finally {
            raw.fill(0)
        }
    }

    /**
     * Wrap an externally-provided base64 passphrase (from a backup
     * restore) with THIS install's keystore alias and persist. Used by
     * the app's BackupManager (in :app) when migrating across
     * applicationIds: the old install's keystore wrap-key is gone
     * after uninstall, but the backup ZIP carries the plaintext
     * passphrase, which we then re-wrap under the new alias here.
     */
    fun importPlaintext(passphraseBase64: String) {
        val raw = Base64.decode(passphraseBase64, Base64.NO_WRAP)
        require(raw.size == PASSPHRASE_BYTES) {
            "expected $PASSPHRASE_BYTES-byte SimpleX passphrase, got ${raw.size}"
        }
        try {
            persistWrapped(raw)
        } finally {
            raw.fill(0)
        }
    }

    private fun persistWrapped(raw: ByteArray) {
        val (iv, ct) = encryptWithKeystore(raw)
        val tmp = File(wrappedFile.parentFile, "${wrappedFile.name}.tmp")
        tmp.writeBytes(iv + ct)
        if (!tmp.renameTo(wrappedFile)) {
            tmp.delete()
            throw java.io.IOException("rename ${tmp.name} → ${wrappedFile.name} failed")
        }
        wrappedFile.setReadable(false, false)
        wrappedFile.setReadable(true, true)
        wrappedFile.setWritable(false, false)
        wrappedFile.setWritable(true, true)
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No setUserAuthenticationRequired — SimpleX core
                // must come up cold-boot for the canary, geofence,
                // and sos infrastructure to keep working without
                // the user having unlocked Aegis yet.
                .build(),
        )
        return kg.generateKey()
    }

    private fun encryptWithKeystore(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return cipher.iv to cipher.doFinal(plaintext)
    }

    private fun decryptWithKeystore(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keystoreKey(),
            GCMParameterSpec(GCM_TAG_BYTES * 8, iv),
        )
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "app.aether.aegis.simplex.dbkey.wrap.v1"
        private const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val PASSPHRASE_BYTES = 32
        private const val WRAPPED_FILE_NAME = "simplex_v1_dbkey.wrapped"
    }
}

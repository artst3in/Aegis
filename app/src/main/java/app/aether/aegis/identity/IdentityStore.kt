package app.aether.aegis.identity

import app.aether.aegis.profile.ProfileRoot
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the device identity. Stored in app-private internal storage
 * under [ProfileRoot.identityFile].
 *
 * Security audit #1: the legacy on-disk format wrote the
 * Curve25519 private key as plaintext base64 — only protection was
 * Android full-disk encryption, which an attacker with root on a
 * running device defeats trivially. Identity keys are now wrapped
 * with an AES-256-GCM Keystore key (`AndroidKeyStore` provider,
 * alias [KEY_ALIAS]). On modern devices the underlying AES key
 * lives in StrongBox / TrustZone hardware and never leaves the
 * secure element — even root can only ask for decrypt operations,
 * not extract the key.
 *
 * On-disk format is always the Keystore-wrapped envelope (`v2:`
 * prefix); there is no plaintext-on-disk fallback.
 *
 * Phase-1 multi-profile: the file lives at [ProfileRoot.identityFile]
 * so each profile has its own keypair. Single Keystore alias is
 * shared across profiles — each profile's wrapped file is in its
 * own directory, and per-profile alias separation isn't justified
 * by the audit's threat model (rooted-device read of files at rest;
 * Keystore unwrap requires the app's UID context regardless of
 * which profile is active).
 */
class IdentityStore(profile: ProfileRoot) {

    private val file: File = profile.identityFile

    fun loadOrCreate(): Identity {
        return load() ?: generate().also { save(it) }
    }

    fun load(): Identity? {
        if (!file.exists()) return null
        val text = runCatching { file.readText().trim() }.getOrNull() ?: return null
        // Only the Keystore-wrapped format is supported.
        if (!text.startsWith(V2_PREFIX)) return null
        return loadV2(text.removePrefix(V2_PREFIX))
    }

    private fun loadV2(payload: String): Identity? {
        val decoded = runCatching { Base64.decode(payload, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        if (decoded.size < GCM_IV_BYTES + GCM_TAG_BYTES) return null
        val iv = decoded.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = decoded.copyOfRange(GCM_IV_BYTES, decoded.size)
        val plain = runCatching { decryptWithKeystore(iv, ciphertext) }
            .onFailure { Log.w(TAG, "Keystore decrypt failed — identity unreadable", it) }
            .getOrNull()
            ?: return null
        val b64 = Base64.encodeToString(plain, Base64.NO_WRAP)
        return runCatching { Identity.fromPrivateKeyB64(b64) }.getOrNull()
    }

    private fun generate(): Identity = Identity.generate()

    private fun save(identity: Identity) {
        file.parentFile?.mkdirs()
        val (iv, ct) = encryptWithKeystore(identity.privateKey)
        val payload = Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        file.writeText(V2_PREFIX + payload)
        // Owner-only POSIX perms — same hardening as the legacy path.
        // The Keystore wrap is defense-in-depth; this stops a non-root
        // sibling app from reading the wrapped blob and feeding it
        // back to Keystore for decrypt.
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    /**
     * Decrypt the on-disk identity using THIS install's keystore wrap-
     * key and return the raw 32-byte Curve25519 private key. Returns
     * null when no identity exists or when the keystore unwrap fails
     * (alias missing — e.g. after a cross-applicationId migration).
     *
     * Caller is responsible for wiping the returned ByteArray after
     * use; we don't have ownership over it once it leaves the store.
     *
     * Used by [app.aether.aegis.backup.BackupManager] to write a
     * portable identity into the backup ZIP — the on-disk keystore-
     * wrapped form isn't portable to another device (the wrap-key is
     * device-bound), so the backup carries the plaintext inside the
     * passphrase-encrypted ZIP instead.
     */
    fun exportPlaintext(): ByteArray? {
        if (!file.exists()) return null
        val text = runCatching { file.readText().trim() }.getOrNull() ?: return null
        if (!text.startsWith(V2_PREFIX)) return null
        val decoded = runCatching {
            Base64.decode(text.removePrefix(V2_PREFIX), Base64.NO_WRAP)
        }.getOrNull() ?: return null
        if (decoded.size < GCM_IV_BYTES + GCM_TAG_BYTES) return null
        val iv = decoded.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = decoded.copyOfRange(GCM_IV_BYTES, decoded.size)
        return runCatching { decryptWithKeystore(iv, ciphertext) }
            .onFailure { Log.w(TAG, "exportPlaintext: keystore decrypt failed", it) }
            .getOrNull()
    }

    /**
     * Inverse of [exportPlaintext]. Wraps [rawPrivateKey] with THIS
     * install's keystore alias and writes the encrypted blob to
     * [file], overwriting any existing identity. Used by the
     * BackupManager restore path on the new applicationId after the
     * keystore alias changed.
     */
    fun importPlaintext(rawPrivateKey: ByteArray) {
        require(rawPrivateKey.size == 32) {
            "expected 32-byte Curve25519 private key, got ${rawPrivateKey.size}"
        }
        file.parentFile?.mkdirs()
        val (iv, ct) = encryptWithKeystore(rawPrivateKey)
        val payload = Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        file.writeText(V2_PREFIX + payload)
        file.setReadable(false, false); file.setReadable(true, true)
        file.setWritable(false, false); file.setWritable(true, true)
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
                // No setUserAuthenticationRequired(true): we need this
                // key during cold-start BEFORE the lock screen renders
                // (the very first thing AegisApp.onCreate does is
                // load the identity to derive the DB passphrase). The
                // app-lock PIN is enforced separately at the UI layer.
                .build(),
        )
        return kg.generateKey()
    }

    private fun encryptWithKeystore(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        // Cipher.init generates a fresh random IV for GCM mode on
        // Android Keystore — pulling cipher.iv after init is the
        // documented way to read it back.
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

    private companion object {
        const val TAG = "IdentityStore"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "app.aether.aegis.identity.wrap.v1"
        const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val V2_PREFIX = "v2:"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BYTES = 16
    }
}

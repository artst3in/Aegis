package app.aether.aegis.lock

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-file encryption for chat attachments, sealed under the
 * REAL-PIN-derived pubkey.
 *
 * Layout per attachment:
 *
 *   on disk:   profiles/<id>/chat_enc/<uuid>.enc
 *              [ 12 B IV ][ AES-256/GCM ciphertext + 16 B tag ]
 *   in DB:     messages.attachmentPath → absolute path of the .enc
 *              messages.sealedDek      → crypto_box_seal(DEK) under
 *                                        [LockStore.sealPub] (~80 B)
 *
 * DEK is a fresh random 32-byte key per file. The DEK never touches
 * disk; only the sealed form does. Decrypt requires unsealing the
 * DEK via [PinSession.priv] (i.e. REAL-PIN unlock), then running
 * the AES-GCM open over the .enc file.
 *
 * Decrypt scratch files land in `cacheDir/chat_dec` and are wiped on
 * every lock event via [clearDecryptCache] — invoked from
 * [PinSession.clear] so plaintext copies never survive a relock.
 */
object ChatAttachmentSeal {

    private const val DEK_BYTES = 32
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val DECRYPT_SUBDIR = "chat_dec"

    /** Convention: encrypted chat attachments live under [chatEncDir]
     *  with a `.enc` suffix. The viewer uses this to decide whether
     *  the path needs the decrypt-to-temp detour. */
    fun isEncrypted(absolutePath: String?): Boolean =
        absolutePath?.endsWith(".enc") == true

    /** Encrypt [src] under a fresh random DEK, seal that DEK via
     *  [sealing], and write the ciphertext to a new `<uuid>.enc`
     *  file in [chatEncDir]. Deletes [src] on success. Returns null
     *  when no pub is configured (no PIN set) — caller falls back
     *  to keeping the plaintext file in place. */
    fun sealAttachment(
        src: File,
        sealing: SealingPolicy,
        chatEncDir: File,
    ): Sealed? {
        if (!sealing.canSeal) return null
        if (!src.exists()) return null
        val dek = ByteArray(DEK_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val sealedDek = sealing.trySeal(dek) ?: return null
            chatEncDir.mkdirs()
            val out = File(chatEncDir, "${UUID.randomUUID()}.enc")
            val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(dek, "AES"),
                    GCMParameterSpec(GCM_TAG_BITS, iv),
                )
            }
            out.outputStream().use { fileOut ->
                fileOut.write(iv)
                CipherOutputStream(fileOut, cipher).use { cipherOut ->
                    src.inputStream().use { it.copyTo(cipherOut) }
                }
            }
            src.delete()
            return Sealed(encryptedPath = out.absolutePath, sealedDek = sealedDek)
        } finally {
            dek.fill(0)
        }
    }

    /**
     * Unseal the DEK from [sealedDek] via [sealing] (requires
     * [PinSession] to hold the priv), then decrypt [encPath] to a
     * scratch file under [context.cacheDir]/chat_dec. Returns the
     * absolute path of the decrypted scratch file, or null on:
     *   - locked session (priv unavailable)
     *   - wrong key / corrupted ciphertext
     *   - missing source file
     */
    fun unsealAttachmentToTemp(
        encPath: String,
        sealedDek: ByteArray,
        sealing: SealingPolicy,
        context: Context,
    ): String? {
        val src = File(encPath)
        if (!src.exists()) return null
        val tempDir = File(context.cacheDir, DECRYPT_SUBDIR).apply { mkdirs() }
        // Deterministic scratch name keyed to the source ciphertext path.
        // The encrypted file is immutable once written, so the same source
        // always maps to the same plaintext scratch — meaning a chat that's
        // left and re-opened (every composable remount re-asks for the
        // viewable path) REUSES the already-decrypted file instead of
        // decrypting the same photo over and over. The scratch still lives
        // only until the next lock (clearDecryptCache on PinSession.clear),
        // so the plaintext window is unchanged.
        val stable = File(tempDir, stableName(encPath))
        if (stable.exists() && stable.length() > 0) return stable.absolutePath

        val dek = sealing.tryUnseal(sealedDek) ?: return null
        try {
            val tempOut = File(tempDir, "${UUID.randomUUID()}.tmp")
            return runCatching {
                val ok = src.inputStream().use { fileIn ->
                    val iv = ByteArray(IV_BYTES)
                    if (fileIn.read(iv) != IV_BYTES) return@use false
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(
                            Cipher.DECRYPT_MODE,
                            SecretKeySpec(dek, "AES"),
                            GCMParameterSpec(GCM_TAG_BITS, iv),
                        )
                    }
                    CipherInputStream(fileIn, cipher).use { cipherIn ->
                        tempOut.outputStream().use { cipherIn.copyTo(it) }
                    }
                    true
                }
                if (!ok) {
                    tempOut.delete(); null
                } else {
                    // Publish atomically to the stable name so a partial
                    // decrypt is never observed as a valid cache hit.
                    if (stable.exists()) stable.delete()
                    if (tempOut.renameTo(stable)) stable.absolutePath
                    else { tempOut.delete(); null }
                }
            }.getOrElse { tempOut.delete(); null }
        } finally {
            dek.fill(0)
        }
    }

    /**
     * Permanently decrypt an `.enc` attachment with an ALREADY-UNSEALED raw
     * [dek] into a new plaintext file under [destDir], returning its absolute
     * path (or null on failure). Unlike [unsealAttachmentToTemp] this is NOT
     * a wipe-on-lock scratch copy — it's the final resting form.
     *
     * Used by the import re-seal bridge when the destination profile has NO
     * seal key: the file can't stay sealed (there's no key to re-seal its DEK
     * under) and a no-phrase profile holds attachments as plaintext anyway, so
     * the bundled DEK is used to decrypt the file for good. Does NOT delete
     * [encPath]; the caller removes it only after the DB row is repointed, so
     * a failed DB write can't strand a row pointing at a deleted file.
     */
    fun decryptAttachmentToPlain(encPath: String, dek: ByteArray, destDir: File): String? {
        val src = File(encPath)
        if (!src.exists()) return null
        destDir.mkdirs()
        // Plaintext form → NO .enc suffix, so isEncrypted() reads false and the
        // viewer opens it directly. Mime/name for display live in the DB row.
        val out = File(destDir, UUID.randomUUID().toString())
        return runCatching {
            val ok = src.inputStream().use { fileIn ->
                val iv = ByteArray(IV_BYTES)
                if (fileIn.read(iv) != IV_BYTES) return@use false
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(dek, "AES"),
                        GCMParameterSpec(GCM_TAG_BITS, iv),
                    )
                }
                CipherInputStream(fileIn, cipher).use { cipherIn ->
                    out.outputStream().use { cipherIn.copyTo(it) }
                }
                true
            }
            if (ok) out.absolutePath else { out.delete(); null }
        }.getOrElse { out.delete(); null }
    }

    /** Stable scratch filename for a given encrypted source — SHA-256 of
     *  the source path, hex. Lets a re-opened chat hit the existing
     *  decrypted file instead of re-decrypting. */
    private fun stableName(encPath: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hex = md.digest(encPath.toByteArray()).joinToString("") { "%02x".format(it) }
        return "$hex.tmp"
    }

    /** Wipe every decrypted scratch file. Called from [PinSession.clear]
     *  (lock event) so the on-disk plaintext window closes the moment
     *  the user re-locks. */
    fun clearDecryptCache(context: Context) {
        runCatching {
            File(context.cacheDir, DECRYPT_SUBDIR).listFiles()?.forEach { it.delete() }
        }
    }

    data class Sealed(val encryptedPath: String, val sealedDek: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}

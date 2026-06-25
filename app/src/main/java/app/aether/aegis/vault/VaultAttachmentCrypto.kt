package app.aether.aegis.vault

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * File-on-disk AES-GCM for vault attachments. A Phase B
 * follow-up to the body-encryption layer in VaultCrypto.
 * Stops a filesystem-level forensic from reading photo / video /
 * document attachments without the slot's PIN.
 *
 * Layout:
 *   profiles/<id>/vault_enc/<uuid>.enc
 *
 * On-disk format per file:
 *   [ 12 bytes IV ][ ciphertext + 16-byte GCM tag ]
 *
 * Convention: encrypted attachments have an absolute path ending
 * in ".enc". Plaintext attachments retain whatever suffix they had
 * when imported (.jpg, .mp4, etc). The viewer detects ".enc" and
 * routes through decryptToTemp; non-".enc" paths pass through
 * unchanged.
 *
 * Existing pre-PIN attachments are NOT migrated by this layer —
 * encrypting them retroactively would require walking every vault
 * row and re-uploading from disk, which we do in the Settings
 * re-encryption pass alongside the body re-encryption. For
 * incremental rollout this commit handles only NEWLY-saved
 * attachments under an active session key.
 */
object VaultAttachmentCrypto {

    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** Encrypt [src] under [key] and write to a new file under
     *  [vaultEncDir]. Deletes [src] on success. Returns the path
     *  to the encrypted file. */
    fun encryptFile(src: File, key: ByteArray, vaultEncDir: File): String {
        vaultEncDir.mkdirs()
        val out = File(vaultEncDir, "${UUID.randomUUID()}.enc")
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        out.outputStream().use { fileOut ->
            // IV first, then the cipher-stream-wrapped output. The
            // GCM tag is auto-appended by the cipher when the
            // stream closes.
            fileOut.write(iv)
            CipherOutputStream(fileOut, cipher).use { cipherOut ->
                src.inputStream().use { it.copyTo(cipherOut) }
            }
        }
        src.delete()
        return out.absolutePath
    }

    /** Decrypt [encPath] under [key] to a temp file under
     *  [context.cacheDir]. Caller is responsible for deleting the
     *  temp file when the viewer closes (use a DisposableEffect
     *  in the composable). Returns null on auth failure or any
     *  IO problem — caller treats null as "this attachment isn't
     *  readable in the current slot". */
    fun decryptToTemp(encPath: String, key: ByteArray, context: Context): String? {
        val src = File(encPath)
        if (!src.exists()) return null
        val tempDir = File(context.cacheDir, "vault_dec").apply { mkdirs() }
        val tempOut = File(tempDir, "${UUID.randomUUID()}.tmp")
        return try {
            src.inputStream().use { fileIn ->
                val iv = ByteArray(IV_LEN)
                if (fileIn.read(iv) != IV_LEN) return null
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(GCM_TAG_BITS, iv),
                )
                CipherInputStream(fileIn, cipher).use { cipherIn ->
                    tempOut.outputStream().use { cipherIn.copyTo(it) }
                }
            }
            tempOut.absolutePath
        } catch (t: Throwable) {
            tempOut.delete()
            null
        }
    }

    /** True iff [absolutePath] points at an encrypted vault
     *  attachment. Used by the viewer layer to decide whether
     *  to route through decryptToTemp. */
    fun isEncrypted(absolutePath: String?): Boolean =
        absolutePath?.endsWith(".enc") == true

    /** Sweep the vault decrypt scratch directory. Called on app
     *  resume + on vault lock so plaintext copies don't survive
     *  past a user's logout. */
    fun clearDecryptCache(context: Context) {
        runCatching {
            File(context.cacheDir, "vault_dec").listFiles()?.forEach { it.delete() }
        }
    }
}

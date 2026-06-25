package app.aether.aegis.backup

import android.content.Context
import android.content.SharedPreferences
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import app.aether.aegis.profile.ProfileRoot
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted local backup + restore.
 *
 * File layout:
 *   bytes 0..7    "AEGISBAK"   magic
 *   byte  8       0x02          version
 *   bytes 9..24   Argon2id salt 16 bytes, fresh per backup
 *   bytes 25..36  AES-GCM nonce 12 bytes, fresh per backup
 *   bytes 37..end ciphertext    AES-256-GCM(zip)
 *
 * The plaintext is a ZIP containing:
 *   /db/app.aether.aegis.db            the SQLCipher Room database
 *   /id/<files>             identity + lock keystore
 *   /prefs/<name>.xml       every SharedPreferences file under shared_prefs/
 *   /files/<path>           attachments (filesDir/app_files/...)
 *
 * Encryption: AES-256-GCM with a key derived from the user's chosen
 * passphrase via Argon2id (libsodium INTERACTIVE — ops=2, mem=64 MiB).
 * Passphrase lives only in memory during backup/restore — never
 * persisted.
 *
 * The database is captured by closing the Room instance, copying the
 * raw .db file, then reopening. Lossless even with WAL — SQLCipher's
 * WAL is checkpointed on close.
 */
object BackupManager {

    private const val MAGIC = "AEGISBAK"

    // Single backup format version (Argon2id MODERATE password KDF).
    private const val VERSION: Byte = 0x03

    /** ZIP entry holding the unsealed re-seal bundle (data only, protected
     *  by the backup password envelope). */
    private const val RESEAL_BUNDLE_ENTRY = "reseal/bundle.bin"

    /** Filename, in the profile root, where restore stashes the bundle for
     *  the next boot to re-seal under the (new) profile key. Public so
     *  AegisApp can consume it. */
    const val RESEAL_PENDING_FILE = "reseal.bundle"

    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    private val sodium = app.aether.aegis.crypto.Sodium.shared

    sealed class Result {
        data object Ok : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Write an encrypted backup to [out]. Caller is responsible for the
     * stream lifecycle (close after this returns). Streams everything —
     * peak memory stays bounded regardless of attachment count.
     */
    suspend fun backup(
        context: Context,
        passphrase: CharArray,
        out: OutputStream,
    ): Result = withContext(Dispatchers.IO) {
        // Hard floor — enforced HERE, not just in the UI. The backup
        // password is the single factor guarding the file,
        // so a caller that bypasses the
        // dialog must not be able to write a weakly-protected backup.
        PasswordPolicy.validate(passphrase).let { v ->
            if (!v.ok) return@withContext Result.Failed(v.reason ?: "passphrase too weak")
        }
        runCatching {
            val rand = SecureRandom()
            val salt = ByteArray(SALT_LEN).also(rand::nextBytes)
            val nonce = ByteArray(NONCE_LEN).also(rand::nextBytes)
            // v3 -> Argon2id MODERATE.
            val key = deriveKey(passphrase, salt)
            // Re-seal bridge: unseal the five sealed fields with the
            // current key NOW (suspend, before the non-suspend zip block)
            // so import can re-seal them under the new profile's key. Null
            // if locked — then no bundle is written.
            val resealBundle = runCatching {
                app.aether.aegis.AegisApp.instance.repository.exportResealBundle()
            }.getOrNull()
            // Refuse to write a CORRUPT-ON-IMPORT backup: if this profile HAS a
            // seal key (sealed chats/contacts exist) but we couldn't produce the
            // re-seal bundle — because the profile is LOCKED (can seal but not
            // unseal) — the backup would carry sealed rows with no plaintext to
            // re-seal under the destination key, leaving the import permanently
            // unreadable (user report). Fail loudly instead; the user unlocks
            // and retries. (A profile with no seal key has nothing to re-seal,
            // so a null bundle there is fine.)
            if (resealBundle == null &&
                app.aether.aegis.AegisApp.instance.repository.canSeal
            ) {
                return@withContext Result.Failed(
                    "Unlock Aegis before backing up — your encrypted chats can't be " +
                        "exported while the app is locked.",
                )
            }
            try {
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.write(byteArrayOf(VERSION))
                out.write(salt)
                out.write(nonce)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                }
                CipherOutputStream(out, cipher).use { encStream ->
                    ZipOutputStream(encStream).use { zip ->
                        writeDb(context, zip)
                        writeFiles(context, zip)
                        writePrefs(context, zip)
                        writeSimplexCore(context, zip)
                        writePortableKeys(context, zip)
                        if (resealBundle != null) {
                            zip.putNextEntry(ZipEntry(RESEAL_BUNDLE_ENTRY))
                            zip.write(resealBundle)
                            zip.closeEntry()
                        }
                    }
                }
                Result.Ok
            } finally {
                java.util.Arrays.fill(key, 0)
            }
        }.getOrElse { Result.Failed(it.message ?: it::class.simpleName.orEmpty()) }
    }

    /** Per-stage progress emitted to the [Progress] callback so the
     *  UI can render real movement instead of a stuck "Restoring…"
     *  spinner. Bytes are the live count read from the source
     *  stream; stage labels the phase of the pipeline. */
    enum class Stage { DerivingKey, Decrypting, Applying, Done }
    data class Progress(
        val stage: Stage,
        val bytesProcessed: Long,
        val bytesTotal: Long,
    )

    /** Counting-only InputStream wrapper. Exposes a Volatile counter
     *  the progress sampler reads from a coroutine without locking. */
    private class CountingInputStream(private val src: InputStream) : InputStream() {
        @Volatile var bytesRead: Long = 0L
            private set
        override fun read(): Int {
            val b = src.read()
            if (b >= 0) bytesRead++
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = src.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
        override fun close() = src.close()
    }

    /**
     * Decrypt + restore the backup in [input]. ATOMIC: writes the
     * unpacked data into a staging directory first, then swaps over
     * the live data dir on success. Caller MUST then trigger an
     * application restart (Process.killProcess(myPid())) so the
     * SQLCipher core picks up the new DB file.
     *
     * [expectedTotalBytes] is the source file's total size (typically
     * pulled from the SAF DocumentFile.length). When > 0 the progress
     * callback receives a meaningful percent; when ≤ 0 it still ticks
     * with bytesProcessed so the UI can show throughput.
     */
    suspend fun restore(
        context: Context,
        passphrase: CharArray,
        input: InputStream,
        expectedTotalBytes: Long = -1L,
        // When true (import into a freshly-created profile), the importing
        // profile's OWN lock prefs (PIN/phrase/seal) are preserved instead
        // of being overwritten by the backup's. The restored content is
        // then re-sealed under the new key on next boot. Default false =
        // legacy full-clone restore (same-device recovery).
        preserveLockPrefs: Boolean = false,
        // First-run "restore from backup": import DATA only and leave the
        // install un-onboarded so the tutorial runs (permissions / recovery
        // phrase / PIN / profile). Skips the backup's profile prefs too (not
        // just lock prefs), so `onboarded` resets to false. Implies
        // [preserveLockPrefs]. Distinct from the Profiles-settings import,
        // which restores INTO a profile the user already set up with a phrase
        // + PIN and must NOT be re-onboarded.
        freshOnboarding: Boolean = false,
        onProgress: ((Progress) -> Unit)? = null,
    ): Result = kotlinx.coroutines.coroutineScope {
        val counting = CountingInputStream(input)
        val emit: (Stage) -> Unit = { stage ->
            onProgress?.invoke(
                Progress(stage, counting.bytesRead, expectedTotalBytes)
            )
        }
        // Progress ticker — re-emits every 250 ms with the current
        // byte count so the UI's LinearProgressIndicator animates
        // even when the inner code path is in a long copy loop.
        // Cancelled in the finally block below; scoped to this
        // coroutineScope so the parent waits until both branches
        // wind down.
        val progressJob = onProgress?.let {
            launch(Dispatchers.Default) {
                while (isActive) {
                    emit(Stage.Decrypting)
                    delay(250L)
                }
            }
        }
        try {
            // freshOnboarding implies preserveLockPrefs (we always keep the
            // fresh install's own lock state when re-onboarding).
            doRestore(context, passphrase, counting, emit, preserveLockPrefs || freshOnboarding, freshOnboarding)
        } finally {
            progressJob?.cancel()
        }
    }

    private suspend fun doRestore(
        context: Context,
        passphrase: CharArray,
        input: CountingInputStream,
        emit: (Stage) -> Unit,
        preserveLockPrefs: Boolean,
        freshOnboarding: Boolean,
    ): Result = withContext(Dispatchers.IO) {
        if (passphrase.isEmpty()) return@withContext Result.Failed("passphrase is empty")
        runCatching {
            val magicBuf = ByteArray(MAGIC.length)
            require(input.readFully(magicBuf) && magicBuf.toString(Charsets.US_ASCII) == MAGIC) {
                "not an Aegis backup"
            }
            val versionBuf = ByteArray(1)
            require(input.readFully(versionBuf)) { "truncated header (version)" }
            require(versionBuf[0] == VERSION) {
                "unsupported backup version ${versionBuf[0].toInt()}"
            }
            val salt = ByteArray(SALT_LEN)
            require(input.readFully(salt)) { "truncated header (salt)" }
            val nonce = ByteArray(NONCE_LEN)
            require(input.readFully(nonce)) { "truncated header (nonce)" }
            emit(Stage.DerivingKey)
            val key = deriveKey(passphrase, salt)
            // Declared OUTSIDE the try so the finally can always scrub it.
            val staging = File(context.filesDir, "restore-staging")
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                }
                staging.apply {
                    if (exists()) deleteRecursively()
                    mkdirs()
                }
                emit(Stage.Decrypting)
                CipherInputStream(input, cipher).use { decStream ->
                    ZipInputStream(decStream).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val target = File(staging, entry.name).apply {
                                parentFile?.mkdirs()
                            }
                            target.outputStream().use { o -> zip.copyTo(o) }
                            zip.closeEntry()
                        }
                    }
                }
                // Stage looks intact. Promote it.
                emit(Stage.Applying)
                applyStagedRestore(context, staging, preserveLockPrefs, freshOnboarding)
                emit(Stage.Done)
                Result.Ok
            } finally {
                java.util.Arrays.fill(key, 0)
                // ALWAYS scrub + remove the staging dir — on success and on
                // any failure/interruption. It holds DECRYPTED plaintext:
                // the SQLCipher DB, the portable identity + SimpleX keys,
                // and the unsealed re-seal bundle (message bodies + the
                // attachment DEKs). A failed/aborted restore previously
                // stranded all of that in filesDir until the next restore
                // attempt that might never come (Security review
                // 2026-06-07). Best-effort byte-zero before delete.
                runCatching { secureDeleteRecursively(staging) }
            }
        }.getOrElse {
            // Wrong passphrase shows up as a GCM AEADBadTagException.
            // Surface a user-readable message instead of the JCE stack.
            val msg = it.message.orEmpty()
            val human = when {
                "BadTag" in msg || it is javax.crypto.AEADBadTagException ->
                    "Wrong passphrase, or the file is corrupted."
                else -> msg.ifBlank { it::class.simpleName.orEmpty() }
            }
            Result.Failed(human)
        }
    }

    /** Overwrite every file's bytes with zeros, then delete the tree.
     *  Used to scrub the restore-staging dir, which holds decrypted
     *  plaintext (DB, portable keys, unsealed bundle). Best-effort:
     *  byte-overwrite doesn't defeat flash wear-levelling, but it beats a
     *  bare delete() that leaves the blocks intact and recoverable. */
    private fun secureDeleteRecursively(root: File) {
        if (!root.exists()) return
        root.walkBottomUp().forEach { f ->
            if (f.isFile) {
                runCatching {
                    val len = f.length()
                    if (len > 0) {
                        java.io.RandomAccessFile(f, "rw").use { raf ->
                            val zeros = ByteArray(8192)
                            var written = 0L
                            while (written < len) {
                                val n = minOf(zeros.size.toLong(), len - written).toInt()
                                raf.write(zeros, 0, n)
                                written += n
                            }
                            raf.fd.sync()
                        }
                    }
                }
            }
            runCatching { f.delete() }
        }
    }

    /** Argon2id MODERATE over [passphrase], output = 32-byte AES-256 key.
     *  The backup password is the single factor guarding the file, so the
     *  KDF cost is deliberately high. LazySodium's Lazy interface returns
     *  the output hex-encoded; we decode to raw bytes for the AES key.
     *  (libsodium: opslimit is a long, memlimit a JNA NativeLong.) */
    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        require(salt.size == PwHash.SALTBYTES) {
            "salt must be ${PwHash.SALTBYTES} bytes, got ${salt.size}"
        }
        val hex = sodium.cryptoPwHash(
            String(passphrase),
            KEY_BITS / 8,
            salt,
            PwHash.OPSLIMIT_MODERATE,
            PwHash.MEMLIMIT_MODERATE,
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        return hex.hexToByteArray().also {
            check(it.size == KEY_BITS / 8) {
                "argon2id returned ${it.size} bytes, expected ${KEY_BITS / 8}"
            }
        }
    }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "hex string has odd length" }
        return ByteArray(length / 2) { i ->
            val hi = substring(i * 2, i * 2 + 1).toInt(16)
            val lo = substring(i * 2 + 1, i * 2 + 2).toInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }

    /** Read fully into [dst]. Returns false on EOF before fill. */
    private fun InputStream.readFully(dst: ByteArray): Boolean {
        var off = 0
        while (off < dst.size) {
            val n = read(dst, off, dst.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun writeDb(context: Context, zip: ZipOutputStream) {
        // Phase 1 multi-profile: the DB lives under the active
        // profile root's databases/ dir, not the package's default
        // databases dir. Walk the profile's databases subdir.
        val dbDir = app.aether.aegis.AegisApp.instance.profileRoot.databasesDir
        if (!dbDir.exists()) return
        dbDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("app.aether.aegis.db")) {
                zip.putNextEntry(ZipEntry("db/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun writeFiles(context: Context, zip: ZipOutputStream) {
        val profile = app.aether.aegis.AegisApp.instance.profileRoot
        val attachments = profile.attachmentsDir
        if (attachments.exists()) {
            attachments.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(attachments).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry("files/$rel"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        // Identity-bound loose files (identity.key, avatar.*) live at
        // the top of the profile root. Ship those under id/ so the
        // restore path can put them back into the same profile root.
        // Subdirs (databases/, app_files/, vault/) are handled by the
        // db/, files/, vault/ entries respectively — skip them here.
        val denyTopLevel = setOf(
            ProfileRoot.DATABASES_SUBDIR,
            ProfileRoot.ATTACHMENTS_SUBDIR,
            ProfileRoot.VAULT_SUBDIR,
        )
        profile.root.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (f.name in denyTopLevel) return@forEach
            zip.putNextEntry(ZipEntry("id/${f.name}"))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** Bundle the SimpleX core's own SQLCipher databases under
     *  `simplex/` inside the backup ZIP. Without these, restore
     *  brings back Aegis-side metadata (knownPeers, messages table)
     *  but the SimpleX core starts with an empty store and the user
     *  sees no contacts or chats — because the actual chat content
     *  + SimpleX-paired contacts live in the core's chat DB, not in
     *  Aegis's Room DB.
     *
     *  Sidecars (-wal / -journal / -shm) are bundled too so SQLCipher
     *  has the complete pre-shutdown state to recover from. */
    private fun writeSimplexCore(context: Context, zip: ZipOutputStream) {
        val dir = context.filesDir
        // Both main DBs + every SQLCipher sidecar.
        dir.listFiles { f ->
            f.isFile && (
                f.name == "simplex_v1_agent.db" ||
                f.name == "simplex_v1_chat.db" ||
                f.name.startsWith("simplex_v1_agent.db-") ||
                f.name.startsWith("simplex_v1_chat.db-") ||
                f.name == "simplex_v1_dbkey.wrapped"
            )
        }?.forEach { f ->
            zip.putNextEntry(ZipEntry("simplex/${f.name}"))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun writePrefs(context: Context, zip: ZipOutputStream) {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!prefsDir.exists()) return
        prefsDir.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.forEach { f ->
            zip.putNextEntry(ZipEntry("prefs/${f.name}"))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /**
     * Export every keystore-wrapped secret as plaintext into the ZIP,
     * under `id-portable/`. The on-disk wrapped blobs (identity.key +
     * simplex_v1_dbkey.wrapped) are unrecoverable across an
     * applicationId change because the wrap-key lives in the Keystore
     * under the old UID. The plaintext copies inside the passphrase-
     * encrypted backup ZIP are the only way the NEXT install (the
     * renamed app.aether.aegis) can recover the user's identity and
     * SimpleX DB.
     */
    private fun writePortableKeys(context: Context, zip: ZipOutputStream) {
        val profile = app.aether.aegis.AegisApp.instance.profileRoot
        runCatching {
            val store = app.aether.aegis.identity.IdentityStore(profile)
            val plain = store.exportPlaintext()
            if (plain != null) {
                try {
                    zip.putNextEntry(ZipEntry("id-portable/identity.key.plain"))
                    zip.write(plain)
                    zip.closeEntry()
                } finally {
                    plain.fill(0)
                }
            }
        }
        runCatching {
            val store = app.aether.aegis.simplex.SimpleXDbKeyStore(context)
            if (store.hasWrappedPassphrase) {
                val pass = store.loadPassphrase()
                zip.putNextEntry(ZipEntry("id-portable/simplex.dbkey.plain"))
                zip.write(pass.toByteArray(Charsets.US_ASCII))
                zip.closeEntry()
            }
        }
        // NOTE: the seal private key (the at-rest "master key") is
        // deliberately NOT exported. The master key
        // must never leave the device. The backup carries DATA only; a
        // restore re-seals it under the importing profile's own
        // phrase-derived key (see SealedDataTransport). The earlier
        // "content-portable via exported seal.priv" approach is gone.
    }

    /** Swap staged data over live. Each subdir maps to a real location;
     *  unknown entries are silently dropped (forward-compat). */
    private fun applyStagedRestore(context: Context, staging: File, preserveLockPrefs: Boolean, freshOnboarding: Boolean) {
        // Phase 1 multi-profile: restore lands in the active profile
        // root, not the pre-Phase-1 flat layout. A backup taken before
        // Phase 1 has the same logical sections (db/, id/, files/);
        // the only thing that changed is *where* on disk they go.
        val profile = app.aether.aegis.AegisApp.instance.profileRoot
        profile.ensureLayout()
        // When importing into a freshly-created profile we KEEP that
        // profile's own lock prefs (its new PIN/phrase/seal) — the .xml
        // file we must NOT overwrite from the backup. Its restored content
        // gets re-sealed under the new key on next boot (reseal bundle).
        val lockPrefsXml = profile.prefsName("aegis_lock") + ".xml"
        // Fresh-onboarding import also skips the backup's PROFILE prefs, so
        // `onboarded` stays false and the tutorial runs (permissions / phrase
        // / PIN / name) on this install. Name/bio/avatar are then set up fresh
        // — "import chats + contacts, set everything else up normally".
        val profilePrefsXml = profile.prefsName("aegis_profile") + ".xml"
        // 1. DB files (the SQLCipher DB + its WAL/shm/journal sidecars).
        val dbStaging = File(staging, "db")
        if (dbStaging.exists()) {
            val dbDir = profile.databasesDir
            dbDir.listFiles()
                ?.filter { it.name.startsWith(ProfileRoot.DB_FILE_NAME) }
                ?.forEach { it.delete() }
            dbStaging.listFiles()?.forEach { src ->
                src.copyTo(File(dbDir, src.name), overwrite = true)
            }
        }
        // 2. Identity / loose profile-root files (keystore-wrapped form
        // of identity.key, avatar.* etc.). Cross-applicationId restores
        // get the keystore-wrapped identity REWRAPPED in step 6 below.
        val idStaging = File(staging, "id")
        if (idStaging.exists()) {
            idStaging.listFiles()?.forEach { src ->
                val dst = File(profile.root, src.name)
                src.copyTo(dst, overwrite = true)
            }
        }
        // 3. Attachments
        val filesStaging = File(staging, "files")
        if (filesStaging.exists()) {
            val target = profile.attachmentsDir
            filesStaging.walkTopDown().filter { it.isFile }.forEach { src ->
                val rel = src.relativeTo(filesStaging).path
                val dst = File(target, rel)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }
        // 4. SharedPreferences. Replace existing .xml files — the new
        // process re-reads them on next access.
        val prefsStaging = File(staging, "prefs")
        if (prefsStaging.exists()) {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs").apply { mkdirs() }
            prefsStaging.listFiles()?.forEach { src ->
                // Import-into-new-profile: keep this profile's own lock
                // prefs (new PIN/phrase/seal); don't let the backup's
                // overwrite them.
                if (preserveLockPrefs && src.name == lockPrefsXml) return@forEach
                // First-run import: keep this install un-onboarded so the
                // tutorial fires — don't import the backup's profile prefs
                // (carries `onboarded`) NOR the global tutorial flag prefs
                // (carries `tutorial_completed`, which would otherwise route
                // the user past the tutorial into name-only onboarding and
                // leave them with no recovery phrase / PIN).
                if (freshOnboarding &&
                    (src.name == profilePrefsXml || src.name == "tutorial_prefs.xml")
                ) return@forEach
                src.copyTo(File(prefsDir, src.name), overwrite = true)
            }
        }
        // 5. SimpleX core databases. Without these the cold-boot SimpleX
        // core starts with an empty chat store — no contacts, no
        // messages, even though Aegis-side metadata (knownPeers row,
        // messages table) is restored. Wipe any sidecars the fresh
        // install just created so SQLCipher doesn't get confused by
        // mismatched WAL state, then promote everything under simplex/
        // back into filesDir.
        val simplexStaging = File(staging, "simplex")
        if (simplexStaging.exists()) {
            context.filesDir.listFiles { f ->
                f.isFile && (
                    f.name == "simplex_v1_agent.db" ||
                    f.name == "simplex_v1_chat.db" ||
                    f.name.startsWith("simplex_v1_agent.db-") ||
                    f.name.startsWith("simplex_v1_chat.db-") ||
                    f.name == "simplex_v1_dbkey.wrapped"
                )
            }?.forEach { it.delete() }
            simplexStaging.listFiles()?.forEach { src ->
                src.copyTo(File(context.filesDir, src.name), overwrite = true)
            }
        }
        // 6. Portable keystore secrets. On a DIFFERENT DEVICE the on-disk
        // keystore-wrapped identity (step 2) + simplex_v1_dbkey.wrapped
        // (step 5) are unrecoverable — their wrap-keys live in the other
        // device's Keystore. The id-portable/ entries carry plaintext
        // copies (inside the passphrase-encrypted ZIP); rewrap them here
        // with THIS device's keystore aliases so the cold boot decrypts.
        val portable = File(staging, "id-portable")
        if (portable.exists()) {
            val idPlain = File(portable, "identity.key.plain")
            if (idPlain.exists()) {
                val plain = idPlain.readBytes()
                try {
                    app.aether.aegis.identity.IdentityStore(profile).importPlaintext(plain)
                } finally {
                    plain.fill(0)
                }
            }
            val dbKeyPlain = File(portable, "simplex.dbkey.plain")
            if (dbKeyPlain.exists()) {
                val pass = dbKeyPlain.readText(Charsets.US_ASCII).trim()
                app.aether.aegis.simplex.SimpleXDbKeyStore(context).importPlaintext(pass)
            }
            // NOTE: no seal.priv.plain handling. The master key is never in
            // the backup.
        }
        // 7. Re-seal bundle. The unsealed sealed-field data rides in the
        // ZIP under reseal/bundle.bin. Stash it in the profile root so the
        // next cold boot re-seals it under THIS profile's key and writes it
        // over the restored rows (AegisApp.consumePendingReseal). For a
        // full-clone restore the key is the same; for import-into-new-
        // profile it's the new key — which is the whole point.
        val resealStaged = File(staging, RESEAL_BUNDLE_ENTRY)
        if (resealStaged.exists()) {
            // Do NOT stash the bundle as plaintext — it carries unsealed
            // message bodies + the attachment DEKs and lingers at the
            // profile root until the next unlock (Security review
            // 2026-06-07). Wrap it under a device-bound TEE key. On-disk
            // format: [0x01][ivLen][iv][ciphertext]. If the Keystore is
            // unavailable (TEE-less device, Model A), fall back to a
            // [0x00]-marked plaintext stash so the re-seal still happens —
            // those devices are already weaker by construction.
            val plain = resealStaged.readBytes()
            val dest = File(profile.root, RESEAL_PENDING_FILE)
            val wrapped = app.aether.aegis.lock.SealKeyVault.wrapReseal(plain)
            dest.outputStream().use { o ->
                if (wrapped != null) {
                    o.write(0x01)
                    o.write(wrapped.iv.size)
                    o.write(wrapped.iv)
                    o.write(wrapped.blob)
                } else {
                    o.write(0x00)
                    o.write(plain)
                }
            }
            java.util.Arrays.fill(plain, 0)
        }
    }
}

package app.aether.aegis.data

import app.aether.aegis.groups.GroupDao
import app.aether.aegis.groups.GroupEntity
import app.aether.aegis.groups.GroupMemberEntity
import app.aether.aegis.profile.ProfileRoot
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import java.security.MessageDigest

// Schema version — INERT. Change detection is Room's own schema identity hash
// (recomputed on any @Entity change), compared disk-vs-code by
// DbRebuild.prepareForOpen, which does backup → wipe → recreate → restore.
// No migration chain, no manual counter; the hash is the single source of
// truth. Every install is a clean slate (no migration/compat code anywhere),
// so this is just the baseline — add/change entities freely.
private const val DB_SCHEMA_VERSION = 1

/**
 * The on-device, SQLCipher-encrypted Room database (one per profile; opened in
 * [open] with a key derived from the profile identity). Holds messages, peers,
 * groups, outbox, notes, stories, scheduled sends, network history, invites.
 *
 * Schema evolution is handled entirely by [DbRebuild] (backup → wipe →
 * recreate → restore), driven off Room's schema IDENTITY HASH — NOT a manual
 * migration chain and NOT the [DB_SCHEMA_VERSION] integer (which is inert; see
 * its note above). Practical upshot for anyone adding a feature: add or change
 * @Entity classes freely and rebuild — the hash notices the difference and
 * DbRebuild reshapes existing on-device data automatically. There are no
 * @Migration objects to write and no version number to bump; that hand-counter
 * is exactly what this design retired.
 */
@Database(
    entities = [
        MessageEntity::class,
        MemberStatusEntity::class,
        OutboxEntity::class,
        KnownPeerEntity::class,
        SecureNoteEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        StoryEntity::class,
        ScheduledMessageEntity::class,
        NetworkHistoryEntity::class,
        PendingInvitationEntity::class,
    ],
    version = DB_SCHEMA_VERSION,
    exportSchema = false,
)
abstract class AegisDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun statuses(): MemberStatusDao
    abstract fun outbox(): OutboxDao
    abstract fun knownPeers(): KnownPeerDao
    abstract fun secureNotes(): SecureNoteDao
    abstract fun groups(): GroupDao
    abstract fun stories(): StoryDao
    abstract fun scheduled(): ScheduledMessageDao
    abstract fun networkHistory(): NetworkHistoryDao
    abstract fun pendingInvitations(): PendingInvitationDao

    companion object {

        fun open(
            context: Context,
            identityPrivateKeyB64: String,
            profile: ProfileRoot,
            ephemeral: Boolean = false,
        ): AegisDatabase {
            // True ephemeral:
            // an ephemeral profile's database lives ONLY in RAM. Room's
            // in-memory builder backs it with an in-process SQLite that
            // never touches disk and vanishes when the connection closes /
            // the process dies. The whole Repository/DAO stack works
            // unchanged; combined with the transactional-delivery purge
            // (SimpleX's transport copy deleted after each seal), a received
            // message exists only in this RAM DB and is gone on lock. No
            // SQLCipher (nothing on disk to encrypt) and no migrations (the
            // schema is created fresh each session).
            if (ephemeral) {
                return Room.inMemoryDatabaseBuilder(context, AegisDatabase::class.java)
                    .build()
            }
            System.loadLibrary("sqlcipher")
            // Room accepts an absolute path as the `name` argument; we
            // pass the per-profile DB file so the database lives under
            // profiles/<id>/databases/app.aether.aegis.db, not in the package's
            // default databases dir. Pre-Phase-1 DBs are migrated to
            // this path by ProfileRegistry on first boot.
            val dbPath = profile.databaseFile.absolutePath
            // SQLCipher KDF: Argon2id MODERATE over the full-entropy
            // 256-bit identity privkey (SQLCipher PBKDF2s on top). Same
            // identity → same key, so the DB reopens every launch.
            val passphrase = derivePassphrase(identityPrivateKeyB64)

            // Schema evolution WITHOUT a migration chain: if the on-disk schema
            // differs from the current one, DbRebuild dumps every row to an
            // encrypted backup and wipes the DB so Room recreates the current
            // schema; we re-import below once it's open. No frozen ALTER TABLE
            // steps to carry forward, and it can't brick an install. See
            // DbRebuild for the crash-safety contract.
            runCatching {
                DbRebuild.prepareForOpen(context, dbPath, passphrase)
            }.onFailure { android.util.Log.e("AegisDatabase", "DbRebuild.prepareForOpen failed", it) }

            val factory: SupportSQLiteOpenHelper.Factory =
                SupportOpenHelperFactory(passphrase)
            val db = Room.databaseBuilder(context, AegisDatabase::class.java, dbPath)
                .openHelperFactory(factory)
                // No migrations: DbRebuild handles every schema change above.
                // Destructive fallback is the last-resort net only — e.g. the
                // export couldn't read a corrupt DB — better an empty DB than a
                // launch crash-loop.
                .fallbackToDestructiveMigration()
                .build()

            // Re-import the backup into the freshly-created schema (no-op
            // unless a rebuild is pending). Runs on the same connection Room
            // just opened.
            runCatching {
                DbRebuild.restoreIfPending(db.openHelper.writableDatabase, dbPath, passphrase)
            }.onFailure { android.util.Log.e("AegisDatabase", "DbRebuild.restoreIfPending failed", it) }
            return db
        }

        /**
         * Current DB passphrase derivation: Argon2id MODERATE over the
         * full-entropy identity privkey with a fixed domain salt
         * (the SQLCipher KDF). Deterministic — same
         * identity always yields the same key, so the DB reopens every
         * launch. The salt only domain-separates this KDF's output; the
         * 256-bit privkey supplies all the entropy.
         */
        private fun derivePassphrase(privateKeyB64: String): ByteArray {
            val sodium = app.aether.aegis.crypto.Sodium.shared
            // Fixed 16-byte salt (PwHash.SALTBYTES) from a versioned domain
            // string, so the derivation is stable across launches.
            val salt = MessageDigest.getInstance("SHA-256")
                .digest("aegis-db-argon2id-v2".toByteArray())
                .copyOf(PwHash.SALTBYTES)
            val hex = sodium.cryptoPwHash(
                privateKeyB64,
                32,
                salt,
                PwHash.OPSLIMIT_MODERATE,
                PwHash.MEMLIMIT_MODERATE,
                PwHash.Alg.PWHASH_ALG_ARGON2ID13,
            )
            return hexToBytes(hex).also {
                check(it.size == 32) { "argon2id db key was ${it.size} bytes, want 32" }
            }
        }

        private fun hexToBytes(s: String): ByteArray {
            require(s.length % 2 == 0) { "odd-length hex" }
            return ByteArray(s.length / 2) { i ->
                ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
            }
        }
    }
}

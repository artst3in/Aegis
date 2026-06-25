package app.aether.aegis.data

import android.content.Context
import android.database.Cursor
import android.util.Base64
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Schema evolution by BACKUP → WIPE → RECREATE → RESTORE, in place of a
 * Room migration chain.
 *
 * WHY this exists: a migration chain is N frozen, append-only `ALTER TABLE`
 * steps — once one runs on real data it can never be edited, so they only
 * accrete and a single broken link bricks every install whose DB is behind.
 * That cost compounds (Ccalc ∝ e^ε). Here the SQL schema is treated as a
 * DISPOSABLE PROJECTION of the data: on a version change we dump every row
 * to an encrypted backup, let Room recreate the CURRENT schema from the
 * entities, and re-insert. There is nothing to carry forward and nothing to
 * freeze.
 *
 * What this handles for FREE (zero per-change code): adding a column,
 * removing a column, adding/removing a table, reindexing — any STORAGE-layout
 * change. The restore matches old rows to the new schema column-by-column;
 * columns gone from the schema are dropped, new columns get their SQL default
 * (or a type-zero when NOT NULL without one). Only a SEMANTIC change — a
 * field's meaning/encoding/name changing — needs a one-line entry in [HOOKS].
 *
 * Crash-safety (this is a security app that gets force-killed): the backup is
 * the durable source of truth across the whole wipe→restore window. Order is
 * export+fsync → mark pending → wipe → (Room recreates) → restore → clear. A
 * crash anywhere re-enters with the pending marker set and the backup intact;
 * restore is idempotent (clear-then-insert in one transaction), so it simply
 * runs again. We never wipe while a rebuild is already pending. If the export
 * itself fails (corrupt DB), we do NOT wipe — Room's destructive fallback
 * takes over, which is no worse than the old chain's crash.
 *
 * Encryption: the backup is the same sensitive data as the (SQLCipher) DB, so
 * it is AES-256-GCM sealed under SHA-256(dbPassphrase) — no plaintext window.
 */
object DbRebuild {

    private const val TAG = "DbRebuild"
    private const val BACKUP_SUFFIX = ".rebuild.bak"
    private const val MARKER_SUFFIX = ".rebuild.pending"
    private const val FORMAT = 1
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    // Blobs can't live in JSON natively — tag + base64 them so restore can
    // tell a real string from an encoded BLOB.
    private const val BLOB_PREFIX = "blob:base64:"

    /**
     * Semantic transform hooks: `table -> mutate a backup row in place` so an
     * OLD row fits the CURRENT schema. ONLY for changes the generic
     * column-matcher can't infer — renames, re-encodings, type/meaning
     * changes. Storage-layout changes (add/drop column) need NO entry here.
     */
    // No hooks today — clean slate, so there are no renamed/re-encoded fields
    // to carry forward. Add an entry only for a genuine in-place transform the
    // generic column-matcher can't infer (a rename or a re-encoding).
    private val HOOKS: Map<String, (JSONObject) -> Unit> = emptyMap()

    /**
     * Run BEFORE Room is built. If an on-disk DB exists whose schema version
     * differs from [targetVersion], back it up (encrypted) and wipe the DB
     * files so Room recreates the current schema. Returns true if a restore
     * is now pending. Idempotent / crash-safe via the pending marker.
     */
    fun prepareForOpen(context: Context, dbPath: String, passphrase: ByteArray): Boolean {
        val marker = File(dbPath + MARKER_SUFFIX)
        // A rebuild was already staged by a previous (interrupted) launch.
        // Do NOT re-export or re-wipe — the backup is authoritative and the
        // restore is idempotent. Just signal restore-pending.
        if (marker.exists()) return true

        val dbFile = File(dbPath)
        if (!dbFile.exists()) return false // fresh install

        // Schema-change detection WITHOUT a hand-maintained version counter.
        // Room stamps every database it creates with an IDENTITY HASH — a
        // fingerprint of the whole schema (tables, columns, types, indices) it
        // recomputes automatically whenever an @Entity changes. We read that
        // hash from the on-disk file and compare it to the hash of the CURRENT
        // code's schema, obtained by spinning up a throwaway in-memory Room of
        // the same database class. Differ → the schema changed → back up + wipe
        // so Room recreates the current schema (restored below). This replaces
        // the old manual DB_SCHEMA_VERSION bump: nobody ever touches a number,
        // the schema fingerprints itself. Fail SAFE — if either hash can't be
        // read, defer to Room rather than wipe blindly.
        val onDiskHash = readIdentityHash(dbPath, passphrase)
        val expectedHash = expectedIdentityHash(context)
        if (onDiskHash == null || expectedHash == null) {
            Log.w(TAG, "schema identity unreadable (disk=$onDiskHash exp=$expectedHash); deferring to Room")
            return false
        }
        if (onDiskHash == expectedHash) return false // schema unchanged

        Log.i(TAG, "schema identity changed ($onDiskHash → $expectedHash): backing up + rebuilding")
        val exported = runCatching { exportAll(dbPath, passphrase) }.getOrElse {
            // Can't read the old data (corrupt?) — do NOT wipe blindly; let
            // Room's destructive fallback handle it. No worse than a crash.
            Log.e(TAG, "export failed; deferring to Room", it)
            return false
        }
        if (!exported) return false

        // Mark pending durably AFTER the backup is fsynced, BEFORE the wipe.
        runCatching {
            FileOutputStream(marker).use { it.write(byteArrayOf()); it.fd.sync() }
        }
        // Wipe the DB (+ WAL sidecars) so Room builds a fresh current schema.
        listOf("", "-wal", "-shm", "-journal").forEach {
            runCatching { File(dbPath + it).delete() }
        }
        return true
    }

    /**
     * Run AFTER Room has opened (the current schema now exists). If a rebuild
     * is pending, re-insert the backed-up rows and clear the marker + backup.
     * Idempotent: safe to call always; a no-op without a pending marker.
     */
    fun restoreIfPending(db: SupportSQLiteDatabase, dbPath: String, passphrase: ByteArray) {
        val marker = File(dbPath + MARKER_SUFFIX)
        if (!marker.exists()) return
        val backup = File(dbPath + BACKUP_SUFFIX)
        if (!backup.exists()) {
            Log.w(TAG, "pending marker without backup; clearing marker")
            runCatching { marker.delete() }
            return
        }
        val ok = runCatching { doRestore(db, backup, passphrase) }.getOrElse {
            Log.e(TAG, "restore failed; will retry on next launch", it)
            false
        }
        if (ok) {
            runCatching { backup.delete() }
            runCatching { marker.delete() }
            Log.i(TAG, "rebuild restore complete")
        }
    }

    // ---------------------------------------------------------------- export

    /** The schema identity hash Room stamped into the ON-DISK (encrypted) file,
     *  read from its `room_master_table`. Null if the file predates Room's
     *  master table or can't be opened → caller defers to Room (fail safe). */
    private fun readIdentityHash(dbPath: String, passphrase: ByteArray): String? = runCatching {
        val db = SQLiteDatabase.openDatabase(
            dbPath, passphrase, null, SQLiteDatabase.OPEN_READONLY, null,
        )
        try {
            db.rawQuery("SELECT identity_hash FROM room_master_table LIMIT 1", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } finally {
            db.close()
        }
    }.getOrNull()

    /** The schema identity hash of the CURRENT code, obtained by building a
     *  throwaway in-memory Room of the same database class and reading the hash
     *  it stamps on creation. No SQLCipher and nothing on disk — just Room's
     *  own compile-time fingerprint of today's entities. Null on any failure
     *  (caller defers). */
    private fun expectedIdentityHash(context: Context): String? = runCatching {
        val mem = androidx.room.Room
            .inMemoryDatabaseBuilder(context, AegisDatabase::class.java)
            .build()
        try {
            mem.openHelper.writableDatabase
                .query("SELECT identity_hash FROM room_master_table LIMIT 1").use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
        } finally {
            mem.close()
        }
    }.getOrNull()

    private fun exportAll(dbPath: String, passphrase: ByteArray): Boolean {
        val db = SQLiteDatabase.openDatabase(
            dbPath, passphrase, null, SQLiteDatabase.OPEN_READONLY, null,
        )
        val tables = JSONObject()
        try {
            val names = mutableListOf<String>()
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT IN ('android_metadata','room_master_table')",
                null,
            ).use { c -> while (c.moveToNext()) names.add(c.getString(0)) }

            for (table in names) {
                val rows = JSONArray()
                db.rawQuery("SELECT * FROM \"$table\"", null).use { c ->
                    val n = c.columnCount
                    while (c.moveToNext()) {
                        val row = JSONObject()
                        for (i in 0 until n) {
                            val col = c.getColumnName(i)
                            when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> row.put(col, JSONObject.NULL)
                                Cursor.FIELD_TYPE_INTEGER -> row.put(col, c.getLong(i))
                                Cursor.FIELD_TYPE_FLOAT -> row.put(col, c.getDouble(i))
                                Cursor.FIELD_TYPE_BLOB ->
                                    row.put(col, BLOB_PREFIX + Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP))
                                else -> row.put(col, c.getString(i))
                            }
                        }
                        rows.put(row)
                    }
                }
                tables.put(table, rows)
            }
        } finally {
            db.close()
        }

        val root = JSONObject().put("format", FORMAT).put("tables", tables)
        val sealed = encrypt(root.toString().toByteArray(Charsets.UTF_8), aesKey(passphrase))
        val tmp = File(dbPath + BACKUP_SUFFIX + ".tmp")
        FileOutputStream(tmp).use { it.write(sealed); it.fd.sync() }
        return tmp.renameTo(File(dbPath + BACKUP_SUFFIX))
    }

    // --------------------------------------------------------------- restore

    private data class ColInfo(val type: String, val notNull: Boolean, val hasDefault: Boolean)

    private fun doRestore(db: SupportSQLiteDatabase, backup: File, passphrase: ByteArray): Boolean {
        val plain = decrypt(backup.readBytes(), aesKey(passphrase))
        val tables = JSONObject(String(plain, Charsets.UTF_8)).getJSONObject("tables")
        return restoreTables(db, tables)
    }

    /** Pure restore step (no file / crypto), split out so it's unit-testable
     *  against a real SQLite. Re-inserts [tables] into [db]'s CURRENT schema in
     *  one transaction; idempotent (clear-then-insert). */
    internal fun restoreTables(db: SupportSQLiteDatabase, tables: JSONObject): Boolean {
        db.beginTransaction()
        try {
            // Defer FK enforcement to commit so we don't have to topologically
            // order inserts — the dumped data was already consistent.
            db.execSQL("PRAGMA defer_foreign_keys=ON")
            val keys = tables.keys()
            while (keys.hasNext()) {
                val table = keys.next()
                val cols = tableColumns(db, table)
                if (cols.isEmpty()) continue // table no longer exists → drop its data
                db.execSQL("DELETE FROM \"$table\"") // idempotent across resumed restores
                val rows = tables.getJSONArray(table)
                for (r in 0 until rows.length()) {
                    insertRow(db, table, rows.getJSONObject(r), cols)
                }
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    private fun tableColumns(db: SupportSQLiteDatabase, table: String): Map<String, ColInfo> {
        val out = LinkedHashMap<String, ColInfo>()
        db.query("PRAGMA table_info(\"$table\")").use { c ->
            val iName = c.getColumnIndex("name")
            val iType = c.getColumnIndex("type")
            val iNotNull = c.getColumnIndex("notnull")
            val iDflt = c.getColumnIndex("dflt_value")
            while (c.moveToNext()) {
                out[c.getString(iName)] = ColInfo(
                    type = (c.getString(iType) ?: "").uppercase(),
                    notNull = c.getInt(iNotNull) != 0,
                    hasDefault = !c.isNull(iDflt),
                )
            }
        }
        return out
    }

    private fun insertRow(
        db: SupportSQLiteDatabase,
        table: String,
        row: JSONObject,
        cols: Map<String, ColInfo>,
    ) {
        HOOKS[table]?.invoke(row)
        val names = ArrayList<String>(cols.size)
        val args = ArrayList<Any?>(cols.size)
        for ((name, info) in cols) {
            when {
                row.has(name) -> {
                    names.add(name)
                    args.add(jsonToArg(row.get(name)))
                }
                info.notNull && !info.hasDefault -> {
                    // NEW non-null column with no SQL default — old rows have
                    // no value. Supply a type-zero so the insert succeeds.
                    // (Want a specific default? give the column a SQL
                    // defaultValue; PRAGMA reports it and we omit it here.)
                    names.add(name)
                    args.add(zeroFor(info.type))
                }
                // nullable-missing → omit (NULL); has-default-missing → omit
                // (SQLite applies the default).
            }
        }
        if (names.isEmpty()) return
        val colList = names.joinToString(",") { "\"$it\"" }
        val placeholders = names.joinToString(",") { "?" }
        db.execSQL("INSERT OR REPLACE INTO \"$table\" ($colList) VALUES ($placeholders)", args.toArray())
    }

    private fun jsonToArg(v: Any?): Any? = when {
        v == null || v === JSONObject.NULL -> null
        v is String && v.startsWith(BLOB_PREFIX) ->
            Base64.decode(v.substring(BLOB_PREFIX.length), Base64.NO_WRAP)
        else -> v // Number / String — SupportSQLite binds these directly
    }

    private fun zeroFor(type: String): Any = when {
        type.contains("INT") -> 0L
        type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB") -> 0.0
        type.contains("BLOB") -> ByteArray(0)
        else -> "" // TEXT and affinity-less
    }

    // ----------------------------------------------------------------- crypto

    internal fun aesKey(passphrase: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(passphrase)

    internal fun encrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    internal fun decrypt(blob: ByteArray, key: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ct = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
}

package app.aether.aegis.data

import android.content.Context
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the part of [DbRebuild] that touches real data: restoring a backup
 * (taken at an OLD schema) into the CURRENT schema, against an actual SQLite
 * engine. This is the dangerous step — getting it wrong loses a survivor's
 * data — so it's exercised end to end, not just reasoned about.
 *
 * (The SQLCipher export half runs only on-device — the native cipher .so
 * isn't loadable in the JVM — but the restore logic and the crypto are.)
 */
// Stub Application — the real AegisApp pulls in lazysodium's native lib,
// which can't load in the JVM. We only need a Context for the in-memory DB.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DbRebuildTest {

    /** A plain (non-cipher) in-memory SQLite at the "current" schema. */
    private fun newDb(createSql: String): SupportSQLiteDatabase {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(null) // in-memory
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(createSql)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldV: Int, newV: Int) {}
                })
                .build(),
        )
        return helper.writableDatabase
    }

    @Test
    fun restore_handles_added_removed_columns_and_blobs() {
        // CURRENT schema: gained `added` (NOT NULL, no SQL default) and lost
        // the old `gone` column; keeps id/name; has a BLOB column.
        val db = newDb(
            "CREATE TABLE t (id TEXT PRIMARY KEY, name TEXT, added INTEGER NOT NULL, data BLOB)",
        )
        // OLD backup row: has id/name, an extinct `gone` column, a blob, and
        // NO `added` (it didn't exist yet).
        val blob = byteArrayOf(1, 2, 3, 4)
        val tables = JSONObject().put(
            "t",
            JSONArray().put(
                JSONObject()
                    .put("id", "x1")
                    .put("name", "Alice")
                    .put("gone", "extinct")
                    .put("data", "blob:base64:" + Base64.encodeToString(blob, Base64.NO_WRAP)),
            ),
        )

        assertTrue(DbRebuild.restoreTables(db, tables))

        db.query("SELECT id, name, added, data FROM t").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("x1", c.getString(0))
            assertEquals("Alice", c.getString(1))
            assertEquals(0L, c.getLong(2)) // new NOT NULL column → type-zero default
            assertArrayEquals(blob, c.getBlob(3)) // blob survived the json round-trip
        }
    }

    @Test
    fun restore_is_idempotent() {
        val db = newDb("CREATE TABLE t (id TEXT PRIMARY KEY, n INTEGER NOT NULL)")
        val tables = JSONObject().put(
            "t", JSONArray().put(JSONObject().put("id", "a").put("n", 7L)),
        )
        // Run twice (simulates a crash-resumed restore) — must not duplicate.
        assertTrue(DbRebuild.restoreTables(db, tables))
        assertTrue(DbRebuild.restoreTables(db, tables))
        db.query("SELECT count(*) FROM t").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    @Test
    fun restore_drops_data_for_tables_no_longer_in_schema() {
        val db = newDb("CREATE TABLE keep (id TEXT PRIMARY KEY)")
        val tables = JSONObject()
            .put("keep", JSONArray().put(JSONObject().put("id", "k")))
            .put("dropped", JSONArray().put(JSONObject().put("id", "d"))) // table gone in current schema
        assertTrue(DbRebuild.restoreTables(db, tables))
        db.query("SELECT id FROM keep").use { c ->
            assertEquals(1, c.count)
        }
        // `dropped` simply isn't recreated — no crash, its data is discarded.
    }

    @Test
    fun nullable_missing_column_stays_null() {
        val db = newDb("CREATE TABLE t (id TEXT PRIMARY KEY, opt TEXT)")
        val tables = JSONObject().put("t", JSONArray().put(JSONObject().put("id", "a")))
        assertTrue(DbRebuild.restoreTables(db, tables))
        db.query("SELECT opt FROM t").use { c ->
            assertTrue(c.moveToFirst())
            assertNull(c.getString(0))
        }
    }

    @Test
    fun crypto_round_trips() {
        val key = DbRebuild.aesKey("a-high-entropy-db-passphrase".toByteArray())
        val msg = """{"format":1,"tables":{"t":[]}}""".toByteArray()
        val sealed = DbRebuild.encrypt(msg, key)
        // ciphertext must differ from plaintext, and decrypt must recover it.
        assertTrue(!sealed.contentEquals(msg))
        assertArrayEquals(msg, DbRebuild.decrypt(sealed, key))
    }
}

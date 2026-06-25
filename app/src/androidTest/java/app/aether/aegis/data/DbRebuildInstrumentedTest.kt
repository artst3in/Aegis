package app.aether.aegis.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof of the ONE half of [DbRebuild] that can't run in the JVM:
 * reading and wiping a real SQLCipher-encrypted database. This drives the
 * complete contract against the actual cipher engine —
 *
 *   old encrypted DB (v1) → prepareForOpen (export + wipe) → new schema (v2)
 *   → restoreIfPending → data is all there, in the new shape.
 *
 * Run on a device/emulator:
 *   ./gradlew :app:connectedSideloadDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DbRebuildInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    // Any stable 32-byte key — SQLCipher treats it as the passphrase, and
    // DbRebuild derives the backup key from the same bytes.
    private val passphrase = ByteArray(32) { (it + 1).toByte() }
    private lateinit var dbPath: String

    private fun sidecars() = listOf("", "-wal", "-shm", "-journal", ".rebuild.bak", ".rebuild.pending")

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        dbPath = File(context.cacheDir, "rebuild_it_${System.nanoTime()}.db").absolutePath
        sidecars().forEach { File(dbPath + it).delete() }
    }

    @After
    fun tearDown() {
        sidecars().forEach { File(dbPath + it).delete() }
    }

    /** A real SQLCipher-backed helper at [version] that creates [createSql]
     *  on first open — exactly the factory Room uses in production. */
    private fun helper(version: Int, createSql: String): SupportSQLiteOpenHelper =
        SupportOpenHelperFactory(passphrase).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbPath)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL(createSql)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldV: Int, newV: Int) {}
                })
                .build(),
        )

    @Test
    fun full_encrypted_round_trip_survives_schema_change() {
        val blob = byteArrayOf(9, 8, 7, 6, 5)

        // 1. OLD encrypted DB at schema v1: has `gone`, lacks `added`.
        val oldHelper = helper(1, "CREATE TABLE t (id TEXT PRIMARY KEY, name TEXT, gone TEXT, data BLOB)")
        oldHelper.writableDatabase.execSQL(
            "INSERT INTO t (id, name, gone, data) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("x1", "Alice", "extinct", blob),
        )
        oldHelper.close()

        // 2. Schema changed to v2 → back up the real cipher DB and wipe it.
        val pending = DbRebuild.prepareForOpen(dbPath, passphrase, targetVersion = 2)
        assertTrue("rebuild should be pending after a version change", pending)
        assertFalse("old DB should be wiped", File(dbPath).exists())
        assertTrue("encrypted backup should exist", File("$dbPath.rebuild.bak").exists())
        assertTrue("pending marker should exist", File("$dbPath.rebuild.pending").exists())

        // 3. NEW schema v2: gained `added` (NOT NULL, no default), dropped `gone`.
        val newHelper = helper(2, "CREATE TABLE t (id TEXT PRIMARY KEY, name TEXT, added INTEGER NOT NULL, data BLOB)")
        val newDb = newHelper.writableDatabase

        // 4. Re-import from the encrypted backup.
        DbRebuild.restoreIfPending(newDb, dbPath, passphrase)

        // 5. Everything came back, reshaped to the new schema.
        newDb.query("SELECT id, name, added, data FROM t").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("x1", c.getString(0))
            assertEquals("Alice", c.getString(1))
            assertEquals("new NOT NULL column defaults to zero", 0L, c.getLong(2))
            assertArrayEquals("blob survived encryption + json round-trip", blob, c.getBlob(3))
        }
        assertFalse("backup cleared after success", File("$dbPath.rebuild.bak").exists())
        assertFalse("marker cleared after success", File("$dbPath.rebuild.pending").exists())
        newHelper.close()
    }

    @Test
    fun same_version_is_a_no_op() {
        val h = helper(5, "CREATE TABLE t (id TEXT PRIMARY KEY)")
        h.writableDatabase.execSQL("INSERT INTO t (id) VALUES ('keep')")
        h.close()

        // target == on-disk version → no backup, no wipe.
        assertFalse(DbRebuild.prepareForOpen(dbPath, passphrase, targetVersion = 5))
        assertTrue("DB untouched", File(dbPath).exists())
        assertFalse(File("$dbPath.rebuild.bak").exists())

        val again = helper(5, "CREATE TABLE t (id TEXT PRIMARY KEY)")
        again.writableDatabase.query("SELECT id FROM t").use { c ->
            assertEquals(1, c.count)
        }
        again.close()
    }
}

package app.aether.aegis.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the foundation of DbRebuild's schema-change detection: Room stamps every
 * database it creates with an IDENTITY HASH (a fingerprint of the whole schema)
 * that DbRebuild reads from `room_master_table` to decide whether the on-disk
 * schema still matches the code — replacing the old hand-bumped
 * DB_SCHEMA_VERSION. This proves the hash is present, readable, and DETERMINISTIC
 * (same schema → same hash), which is what makes the disk-vs-code comparison
 * valid. (The on-disk read uses SQLCipher and only runs on-device; the in-memory
 * "expected" half — exercised here — is the JVM-testable side.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SchemaIdentityHashTest {

    private fun identityHash(): String? {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AegisDatabase::class.java,
        ).build()
        return try {
            db.openHelper.writableDatabase
                .query("SELECT identity_hash FROM room_master_table LIMIT 1").use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
        } finally {
            db.close()
        }
    }

    @Test fun identity_hash_is_present_and_deterministic() {
        val first = identityHash()
        val second = identityHash()
        assertNotNull("Room must stamp an identity hash we can read", first)
        assertTrue(first!!.isNotBlank())
        // Same schema across two builds → identical fingerprint. If this ever
        // drifts, disk-vs-code detection would false-positive every launch.
        assertEquals(first, second)
    }
}

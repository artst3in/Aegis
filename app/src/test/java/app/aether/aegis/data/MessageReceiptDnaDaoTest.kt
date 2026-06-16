package app.aether.aegis.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the cross-device read-tick fix at the DB level — the part that does
 * NOT need two devices to verify. Exercises the DNA-keyed receipt markers and
 * the legacy/DNA partition on an in-memory Room DB (no SQLCipher, so no native
 * dependency).
 *
 * The bug being fixed: receipts matched the receiver's local itemId against the
 * sender's local itemId (different per-device counters). DNA is sender-minted
 * and identical on both ends, so markReadUpToDna selects exactly the right rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MessageReceiptDnaDaoTest {

    private lateinit var db: AegisDatabase
    private lateinit var dao: MessageDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AegisDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.messages()
    }

    @After fun tearDown() = db.close()

    private fun outbound(
        id: String,
        dna: Long?,
        itemId: Long? = null,
        status: String = "sent",
    ) = MessageEntity(
        id = id,
        fromKey = "me",
        toKey = "peer",
        peerKey = "peer",
        body = "m$id",
        timestamp = 0L,
        protocol = "SIMPLEX",
        type = "TEXT",
        delivered = false,
        outgoing = true,
        simplexItemId = itemId,
        messageDna = dna,
        status = status,
    )

    private fun statusOf(id: String): String =
        db.query("SELECT status FROM messages WHERE id = ?", arrayOf(id)).use {
            it.moveToFirst(); it.getString(0)
        }

    @Test fun markReadUpToDna_flips_dna_rows_up_to_the_boundary() = runBlocking {
        dao.insert(outbound("a", dna = 100))
        dao.insert(outbound("b", dna = 200))
        dao.insert(outbound("c", dna = 300))

        // Peer echoes "read up to DNA 200" — a value WE minted on rows a + b.
        dao.markReadUpToDna("peer", maxDna = 200)

        assertEquals("read", statusOf("a"))
        assertEquals("read", statusOf("b"))
        assertEquals("sent", statusOf("c"))   // beyond the boundary, untouched
    }

    @Test fun itemId_receipt_never_touches_a_dna_row() = runBlocking {
        // A DNA-bearing row also has an itemId. The buggy cross-device itemId
        // receipt must NOT mark it — only the DNA receipt may.
        dao.insert(outbound("a", dna = 100, itemId = 5))

        dao.markReadUpTo("peer", maxItemId = 9_999)   // huge — would match if not partitioned

        assertEquals("sent", statusOf("a"))           // untouched: messageDna IS NOT NULL
    }

    @Test fun dna_receipt_never_touches_a_legacy_row() = runBlocking {
        // A legacy/vanilla row (no DNA) must be marked only by the itemId path.
        dao.insert(outbound("a", dna = null, itemId = 5))

        dao.markReadUpToDna("peer", maxDna = 9_999)
        assertEquals("sent", statusOf("a"))           // untouched: messageDna IS NULL

        dao.markReadUpTo("peer", maxItemId = 5)
        assertEquals("read", statusOf("a"))           // the legacy path still works
    }

    @Test fun dna_markers_are_monotonic_and_dont_downgrade() = runBlocking {
        dao.insert(outbound("a", dna = 100, status = "read"))
        // A late 'sealed' receipt must not pull a 'read' row back down.
        dao.markSealedUpToDna("peer", maxDna = 100)
        assertEquals("read", statusOf("a"))
    }

    @Test fun delivered_then_sealed_then_read_climbs() = runBlocking {
        dao.insert(outbound("a", dna = 100, status = "sent"))
        dao.markDeliveredUpToDna("peer", 100); assertEquals("delivered", statusOf("a"))
        dao.markSealedUpToDna("peer", 100);    assertEquals("sealed", statusOf("a"))
        dao.markReadUpToDna("peer", 100);      assertEquals("read", statusOf("a"))
    }
}

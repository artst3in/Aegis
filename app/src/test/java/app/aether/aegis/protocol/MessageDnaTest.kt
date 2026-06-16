package app.aether.aegis.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the DNA mint invariants — the guarantees the read-receipt fix and
 * Phase-2 per-message addressing both rely on. These exercise the PURE mint
 * step ([MessageDna.next]) + the ISO codec, so they run as plain JUnit with no
 * Android/clock dependency; the persisted [MessageDna.mint] is covered
 * separately (Robolectric) where SharedPreferences exists.
 */
class MessageDnaTest {

    @Test fun next_is_now_when_clock_moved_forward() {
        // Normal case: the clock advanced past `last`, so DNA == now.
        val last = 1_000L
        val now = 2_000L
        assertEquals(2_000L, MessageDna.next(last, now))
    }

    @Test fun next_breaks_ties_within_the_same_nanosecond() {
        // Two sends land on the same clock reading → strictly increasing anyway.
        val now = 5_000L
        val first = MessageDna.next(0L, now)        // 5000
        val second = MessageDna.next(first, now)    // max(5000, 5001) = 5001
        val third = MessageDna.next(second, now)    // 5002
        assertTrue(first < second)
        assertTrue(second < third)
        assertEquals(5_001L, second)
        assertEquals(5_002L, third)
    }

    @Test fun next_stays_monotonic_under_a_backward_clock_step() {
        // The documented trade-off: clock jumps BACKWARD, DNA keeps climbing.
        val last = 10_000L
        val nowAfterRollback = 3_000L
        val minted = MessageDna.next(last, nowAfterRollback)
        assertEquals("DNA must climb +1 from last, not follow the clock back",
            10_001L, minted)
        assertTrue(minted > last)
    }

    @Test fun iso_round_trips_through_nanos() {
        // Format → parse must recover the exact epoch-nanos, incl. sub-second.
        val nanos = 1_781_000_158_075_471_695L  // ~2026, with ns fraction
        val iso = MessageDna.iso(nanos)
        assertTrue("expected a Z-suffixed UTC string, got $iso", iso.endsWith("Z"))
        assertEquals(nanos, MessageDna.parseOrNull(iso))
    }

    @Test fun iso_round_trips_on_a_whole_second() {
        // Exact-second case (no fraction in the ISO string) must still round-trip.
        val nanos = 1_781_000_000_000_000_000L
        assertEquals(nanos, MessageDna.parseOrNull(MessageDna.iso(nanos)))
    }

    @Test fun parse_rejects_garbage_without_throwing() {
        assertNull(MessageDna.parseOrNull("not-a-timestamp"))
        assertNull(MessageDna.parseOrNull(""))
    }
}

package app.aether.aegis.ui.components

import app.aether.aegis.core.Message
import app.aether.aegis.core.MessageType
import app.aether.aegis.core.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [computeRunMeta] — the same-sender run grouping that
 * drives the merged glass-slab bubbles. These pin the two rules that were
 * field-bug-fixed so they can't silently regress:
 *
 *   - CALL_LOG rows MERGE into a same-sender run (they're side-aligned call
 *     bubbles now, not centred chips). A text → call → text from one sender
 *     must be ONE run, not three.
 *   - BURN rows BREAK a run (they render as their own reveal-on-tap bubble).
 *
 * Plus the invariants: consecutive same-sender messages group, a different
 * sender breaks, and a day change breaks.
 */
class RunGroupingTest {

    private fun msg(id: String, from: String, type: MessageType = MessageType.TEXT) =
        Message(
            id = id,
            from = from,
            to = "peer",
            content = id,
            timestamp = 0L,
            protocol = Protocol.SIMPLEX,
            type = type,
        )

    private fun meta(
        msgs: List<Message>,
        dayOf: (Message) -> String = { "d" },
    ): Map<String, BubbleRunMeta> =
        computeRunMeta(msgs, dayOf = dayOf, naturalWidthPx = { 100f }, capPx = 1000f)

    @Test fun three_same_sender_texts_are_one_run() {
        val m = meta(listOf(msg("a", "X"), msg("b", "X"), msg("c", "X")))
        assertTrue("a is first", m["a"]!!.firstOfRun)
        assertFalse("a is not last", m["a"]!!.lastOfRun)
        assertFalse("b is mid (not first)", m["b"]!!.firstOfRun)
        assertFalse("b is mid (not last)", m["b"]!!.lastOfRun)
        assertFalse("c is not first", m["c"]!!.firstOfRun)
        assertTrue("c is last", m["c"]!!.lastOfRun)
    }

    @Test fun call_log_merges_into_same_sender_run() {
        // text → CALL_LOG → text, all from X → ONE run (the call is a middle
        // block, not a run-breaking centred chip).
        val m = meta(
            listOf(
                msg("a", "X"),
                msg("b", "X", MessageType.CALL_LOG),
                msg("c", "X"),
            ),
        )
        assertTrue("text a opens the run", m["a"]!!.firstOfRun)
        assertFalse("call b is a middle block, not a new run", m["b"]!!.firstOfRun)
        assertFalse("call b is not the last of the run", m["b"]!!.lastOfRun)
        assertTrue("text c closes the run", m["c"]!!.lastOfRun)
        // All three share the run's uniform width.
        assertEquals(m["a"]!!.widthPx, m["b"]!!.widthPx, 0.001f)
        assertEquals(m["a"]!!.widthPx, m["c"]!!.widthPx, 0.001f)
    }

    @Test fun burn_breaks_the_run() {
        // text → BURN → text, all from X → the burn stands alone and splits
        // the run, so each surrounding text is its own standalone bubble
        // (first AND last).
        val m = meta(
            listOf(
                msg("a", "X"),
                msg("b", "X", MessageType.BURN),
                msg("c", "X"),
            ),
        )
        assertTrue(m["a"]!!.firstOfRun && m["a"]!!.lastOfRun)
        assertTrue("burn is standalone", m["b"]!!.firstOfRun && m["b"]!!.lastOfRun)
        assertTrue(m["c"]!!.firstOfRun && m["c"]!!.lastOfRun)
    }

    @Test fun different_sender_breaks_run() {
        val m = meta(listOf(msg("a", "X"), msg("b", "Y")))
        assertTrue(m["a"]!!.firstOfRun && m["a"]!!.lastOfRun)
        assertTrue(m["b"]!!.firstOfRun && m["b"]!!.lastOfRun)
    }

    @Test fun day_change_breaks_run() {
        val day = mapOf("a" to "mon", "b" to "tue")
        val m = meta(listOf(msg("a", "X"), msg("b", "X")), dayOf = { day[it.id]!! })
        assertTrue("a closes its day's run", m["a"]!!.firstOfRun && m["a"]!!.lastOfRun)
        assertTrue("b opens the next day's run", m["b"]!!.firstOfRun && m["b"]!!.lastOfRun)
    }
}

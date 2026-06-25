package app.aether.aegis.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the shield-tier mapping (SPEC_TESTING #11).
 *
 * [ShieldTierEngine.tierFor] is a total, deterministic function of the
 * lit-node count. These pin its boundaries so a future re-tuning of the
 * curve can't silently shift a user's tier — the tier drives the
 * avatar-frame colour other contacts see, so a drift is user-visible and
 * trust-relevant.
 */
class ShieldTierEngineTest {

    @Test fun zero_or_negative_nodes_is_None() {
        assertEquals(ShieldTier.None, ShieldTierEngine.tierFor(0))
        // Defensive: a miscount must not underflow into a different tier.
        assertEquals(ShieldTier.None, ShieldTierEngine.tierFor(-3))
    }

    @Test fun one_node_is_Bronze() {
        assertEquals(ShieldTier.Bronze, ShieldTierEngine.tierFor(1))
    }

    @Test fun two_through_eight_nodes_is_Silver() {
        for (n in 2..8) {
            assertEquals("expected Silver at $n nodes", ShieldTier.Silver, ShieldTierEngine.tierFor(n))
        }
    }

    @Test fun nine_nodes_is_Gold() {
        // Gold = everything but Device Owner.
        assertEquals(ShieldTier.Gold, ShieldTierEngine.tierFor(9))
    }

    @Test fun all_ten_nodes_is_Cyan() {
        // Cyan = the crown, all 10 incl. Device Owner.
        assertEquals(ShieldTier.Cyan, ShieldTierEngine.tierFor(ShieldTierEngine.MAX_NODES))
    }

    @Test fun above_max_clamps_to_Cyan() {
        // A count above MAX_NODES must stay at the ceiling, never fall
        // through to None/null.
        assertEquals(ShieldTier.Cyan, ShieldTierEngine.tierFor(ShieldTierEngine.MAX_NODES + 5))
    }

    @Test fun tier_is_monotonic_non_decreasing_in_node_count() {
        var prev = -1
        for (n in 0..ShieldTierEngine.MAX_NODES) {
            val ord = ShieldTierEngine.tierFor(n).ordinal
            assertTrue("tier dropped at n=$n (ord $ord < prev $prev)", ord >= prev)
            prev = ord
        }
    }
}

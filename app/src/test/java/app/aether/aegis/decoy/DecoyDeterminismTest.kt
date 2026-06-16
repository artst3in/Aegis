package app.aether.aegis.decoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for decoy determinism (SPEC_TESTING #20).
 *
 * Under a duress unlock the UI shows DECOY data. Two properties matter:
 *  1. It must be STABLE per seed — a decoy that flickers between renders
 *     is a tell that the unlock was fake.
 *  2. Decoy keys must be recognisable as decoys (so real-vs-decoy paths
 *     never cross), and a real key must never be mistaken for a decoy.
 */
class DecoyDeterminismTest {

    @Test fun badges_for_a_seed_are_stable() {
        val a = DecoyBadges.forSeed("peer-key-123")
        val b = DecoyBadges.forSeed("peer-key-123")
        assertEquals("same seed must yield identical decoy badges (no flicker)", a, b)
    }

    @Test fun badges_vary_across_seeds() {
        // Across many seeds the subset must vary, else the decoy is a
        // constant — itself a tell.
        val distinct = (0..30).map { DecoyBadges.forSeed("seed-$it") }.toSet()
        assertTrue("decoy badge set never varies across seeds", distinct.size > 1)
    }

    @Test fun every_decoy_peer_is_recognised_as_decoy() {
        for (p in DecoyFixtures.peers()) {
            assertTrue("decoy peer ${p.publicKey} not recognised", DecoyFixtures.isDecoyKey(p.publicKey))
        }
    }

    @Test fun decoy_fixtures_are_stable_between_calls() {
        assertEquals(
            DecoyFixtures.peers().map { it.publicKey },
            DecoyFixtures.peers().map { it.publicKey },
        )
    }

    @Test fun a_non_fixture_key_is_not_a_decoy() {
        assertFalse(DecoyFixtures.isDecoyKey("a-real-peer-pubkey-not-in-fixtures"))
    }
}

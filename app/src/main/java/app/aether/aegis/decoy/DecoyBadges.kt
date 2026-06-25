package app.aether.aegis.decoy

import app.aether.aegis.achievements.Achievement

/**
 * Plausible, **fake** achievement badge sets for the duress decoy.
 *
 * Reversal of the original "blank under duress" decision:
 * a blank achievements panel is itself a tell — a real
 * user has *some* badges — and the badge catalogue isn't secret, so
 * showing random ones leaks nothing while making the decoy convincing.
 *
 * The set is **deterministic per [seed]** (a decoy peer key, or "self"
 * for the decoy profile) so it is stable across recompositions and
 * re-unlocks — a decoy whose badges flickered or changed run-to-run
 * would itself give the decoy away. This NEVER reads the real
 * AchievementStore / PeerBadgeStore; it is pure fixture data, only
 * ever called on the duress path.
 */
object DecoyBadges {

    /** A stable, random-looking subset of badge ids for [seed]. */
    fun forSeed(seed: String): Set<String> {
        // Seeded RNG → same seed always yields the same subset.
        val rnd = java.util.Random(seed.hashCode().toLong() * 31 + 7)
        return Achievement.entries
            .filter { rnd.nextInt(100) < 45 } // ~45% earned — looks lived-in
            .map { it.id }
            .toSet()
    }
}

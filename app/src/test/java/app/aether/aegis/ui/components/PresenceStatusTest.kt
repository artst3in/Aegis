package app.aether.aegis.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the presence window logic (SPEC_TESTING #23).
 *
 * peerStatusFromAges is the pure core behind the online/away/offline dot.
 * Pinning the windows (Online < 5 min in-app, Away < 30 min by packet)
 * guards against a re-tune silently making contacts look online when
 * they're gone, or vice-versa.
 */
class PresenceStatusTest {

    private val ONLINE = 5L * 60_000L   // ONLINE_WINDOW_MS
    private val AWAY = 30L * 60_000L    // AWAY_WINDOW_MS

    @Test fun fresh_inapp_activity_is_online() {
        assertEquals(PeerStatus.Online, peerStatusFromAges(0, 0))
        assertEquals(PeerStatus.Online, peerStatusFromAges(ONLINE - 1, AWAY * 10))
    }

    @Test fun online_window_upper_bound_is_exclusive() {
        // Exactly at the window → no longer Online.
        assertEquals(PeerStatus.Away, peerStatusFromAges(ONLINE, 0))
    }

    @Test fun stale_inapp_but_recent_packet_is_away() {
        assertEquals(PeerStatus.Away, peerStatusFromAges(ONLINE + 1, AWAY - 1))
    }

    @Test fun both_stale_is_offline() {
        assertEquals(PeerStatus.Offline, peerStatusFromAges(AWAY * 2, AWAY * 2))
    }

    @Test fun null_packet_age_with_stale_inapp_is_offline() {
        // No packet timestamp → treated as infinitely old → Offline, not
        // Away (a missing packet age must not read as "recently seen").
        assertEquals(PeerStatus.Offline, peerStatusFromAges(ONLINE + 1, null))
    }

    @Test fun negative_age_from_clock_skew_clamps_to_online() {
        assertEquals(PeerStatus.Online, peerStatusFromAges(-5_000, -5_000))
    }
}

package app.aether.aegis.remote

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the remote-access auth gate (SPEC_TESTING #9, invariant #5).
 *
 * This is the target-side guard that decides whether a remote operator
 * may drive the device. The security-critical behaviours:
 *  - wrong PINs auto-revoke the operator after a low threshold (the
 *    coercion-sabotage defence),
 *  - a revoked operator is dropped and their live session dies,
 *  - sessions only validate for their own sender.
 *
 * SharedPreferences-backed, so Robolectric runs it on the host JVM.
 */
// Use a PLAIN Application, not AegisApp — the real onCreate pulls in the
// native NaCl/SimpleX libs that can't load on the host JVM. The gate only
// needs a Context for SharedPreferences, so a bare Application suffices.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RemoteAccessGateTest {

    private lateinit var gate: RemoteAccessGate
    private val OP = "operator-pubkey"

    @Before
    fun setUp() {
        gate = RemoteAccessGate(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun failures_below_threshold_do_not_revoke() {
        repeat(RemoteAccessGate.FAIL_THRESHOLD - 1) {
            assertFalse("sub-threshold failure must not trip", gate.recordFailure(OP))
        }
        assertFalse(gate.isRevoked(OP))
    }

    @Test
    fun threshold_failures_trip_auto_revoke() {
        var tripped = false
        repeat(RemoteAccessGate.FAIL_THRESHOLD) { tripped = gate.recordFailure(OP) }
        assertTrue("the threshold failure must trip the revoke", tripped)
        assertTrue("operator must be revoked after the trip", gate.isRevoked(OP))
    }

    @Test
    fun failures_on_a_revoked_sender_are_absorbed() {
        repeat(RemoteAccessGate.FAIL_THRESHOLD) { gate.recordFailure(OP) }
        // Already revoked → further failures return false (no notification
        // spam) and don't re-trip.
        assertFalse(gate.recordFailure(OP))
    }

    @Test
    fun clearFailures_resets_the_counter() {
        repeat(RemoteAccessGate.FAIL_THRESHOLD - 1) { gate.recordFailure(OP) }
        gate.clearFailures(OP)
        var tripped = false
        repeat(RemoteAccessGate.FAIL_THRESHOLD - 1) { tripped = gate.recordFailure(OP) }
        assertFalse("cleared counter must not carry over", tripped)
        assertFalse(gate.isRevoked(OP))
    }

    @Test
    fun a_session_validates_for_its_own_sender() {
        val sid = gate.openSession(OP)
        assertEquals(OP, gate.validateSession(sid))
    }

    @Test
    fun an_unknown_sid_never_validates() {
        assertNull(gate.validateSession("not-a-real-sid"))
    }

    @Test
    fun revoking_kills_the_live_session() {
        val sid = gate.openSession(OP)
        assertEquals(OP, gate.validateSession(sid))
        gate.revoke(OP)
        assertNull("a revoked operator's sid must stop validating", gate.validateSession(sid))
    }

    @Test
    fun revoke_persists_across_gate_instances() {
        gate.revoke(OP)
        assertTrue(gate.isRevoked(OP))
        // A fresh gate over the same prefs file must see the revoke.
        val reborn = RemoteAccessGate(ApplicationProvider.getApplicationContext())
        assertTrue("revoke must survive a process restart", reborn.isRevoked(OP))
        gate.unrevoke(OP)
        assertFalse(gate.isRevoked(OP))
    }

    // ---- manual-injection adversary: forged / guessed / reused sids ----

    @Test
    fun a_blank_sid_never_validates() {
        // A hand-injected [aegis:remote] command that SKIPPED auth has no
        // sid (decodes to null/blank). The gate must authorise nothing.
        assertNull(gate.validateSession(""))
        assertNull(gate.validateSession("   "))
    }

    @Test
    fun sessions_are_isolated_per_sender() {
        val a = "operator-A"
        val b = "operator-B"
        val sidA = gate.openSession(a)
        val sidB = gate.openSession(b)
        assertNotEquals("two senders must never share a sid", sidA, sidB)
        // Each sid resolves only to its OWN sender — a stolen/guessed sid
        // can't be replayed to act "as" a different operator.
        assertEquals(a, gate.validateSession(sidA))
        assertEquals(b, gate.validateSession(sidB))
    }

    @Test
    fun revoking_one_sender_leaves_anothers_session_intact() {
        val a = "operator-A"
        val b = "operator-B"
        val sidA = gate.openSession(a)
        val sidB = gate.openSession(b)
        gate.revoke(a)
        assertNull("revoked sender's session must die", gate.validateSession(sidA))
        assertEquals("an unrelated sender's session must survive", b, gate.validateSession(sidB))
    }

    @Test
    fun session_ids_are_unguessable_high_entropy_and_unique() {
        // The whole auth model rests on a sid being unforgeable. 18 random
        // bytes → ≥24 url-safe base64 chars; across many opens, never a
        // collision and never short enough to brute-force.
        val seen = HashSet<String>()
        repeat(5000) {
            val sid = gate.openSession(OP)
            assertTrue("sid too short to be unguessable: '$sid'", sid.length >= 20)
            assertTrue("sid collision — generator not random", seen.add(sid))
        }
    }

    @Test
    fun peerHasActiveSession_reflects_revoke() {
        gate.openSession(OP)
        assertTrue(gate.peerHasActiveSession(OP))
        gate.revoke(OP)
        assertFalse("a revoked sender must report no active session", gate.peerHasActiveSession(OP))
    }
}

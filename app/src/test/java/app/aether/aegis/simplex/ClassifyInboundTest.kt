package app.aether.aegis.simplex

import app.aether.aegis.core.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests for inbound `[aegis:*]` envelope classification (SPEC_TESTING #7,
 * security invariant #2).
 *
 * classifyInbound is the wire-side guard that keeps control / evidence
 * envelopes out of the chat. The critical invariant: NO `[aegis:*]`
 * body may ever be classified as the visible TEXT type — known tags get
 * their dedicated type, and any UNRECOGNISED tag (e.g. one a newer build
 * emits) falls through to hidden STATUS, never TEXT. This is the guard
 * that closed the 2026.06 `[aegis:badges]` / SOS-audio chat leaks.
 */
class ClassifyInboundTest {

    private fun out(body: String) = SimpleXTransport.classifyInbound(body)
    private fun type(body: String) = out(body).first

    @Test fun plain_text_is_TEXT_and_unchanged() {
        val (t, b) = out("hello there")
        assertEquals(MessageType.TEXT, t)
        assertEquals("hello there", b)
    }

    @Test fun location_is_typed_and_prefix_stripped() {
        val (t, b) = out("[aegis:location]40.7,-74.0")
        assertEquals(MessageType.LOCATION, t)
        assertEquals("40.7,-74.0", b)
    }

    @Test fun sos_is_typed_and_prefix_stripped() {
        val (t, b) = out("[aegis:sos]Help, call me")
        assertEquals(MessageType.SOS, t)
        assertEquals("Help, call me", b)
    }

    @Test fun story_is_typed_and_prefix_stripped() {
        assertEquals(MessageType.STORY, type("[aegis:story]hi"))
    }

    @Test fun badges_envelope_is_hidden_STATUS() {
        // 2026.06 leak fix: [aegis:badges] must route to hidden STATUS,
        // not the visible TEXT default that leaked it into chat.
        assertEquals(MessageType.STATUS, type("[aegis:badges]gold,silver"))
    }

    @Test fun unknown_aegis_tag_is_hidden_STATUS_never_TEXT() {
        // Hardened fallback: ANY unrecognised [aegis:*] envelope (a future
        // build's tag) is hidden STATUS so it can't render as a bubble.
        assertEquals(MessageType.STATUS, type("[aegis:some-future-tag]payload"))
        assertEquals(MessageType.STATUS, type("[aegis:xyz]"))
    }

    @Test fun no_aegis_envelope_ever_classifies_as_TEXT() {
        val tags = listOf(
            "location", "status", "sim-swap", "geofence", "remote", "wiped",
            "typing", "tier", "hello", "identity", "sos", "sos-roster",
            "sos-response:1", "sos-coord", "sos-victim-voice", "sos-distance",
            "sos-closest", "sos-arrived", "read:1", "delivered:1", "sealed:1", "story",
            "burn:5:1", "burn-receipt:1", "badges", "totally-made-up",
        )
        for (tag in tags) {
            assertNotEquals("[$tag] leaked as TEXT", MessageType.TEXT, type("[aegis:$tag]payload"))
        }
    }

    @Test fun aegis_marker_not_at_start_is_plain_text() {
        // Only a LEADING [aegis: triggers control routing; the marker
        // mid-message is ordinary chat text.
        assertEquals(MessageType.TEXT, type("see [aegis:x] in the middle"))
    }

    @Test fun legacy_cmd_envelope_is_inert_hidden_STATUS_not_a_command() {
        // The original CRITICAL bug: `[aegis:cmd]<CMD>` let any paired peer
        // fire WIPE / LOCK / SIREN with zero auth. It was replaced by the
        // PIN-gated [aegis:remote] protocol. A hand-typed `[aegis:cmd]WIPE`
        // must now be an unrecognised tag → hidden STATUS (no action, and
        // never rendered as chat), proving the legacy command path is dead.
        assertEquals(MessageType.STATUS, type("[aegis:cmd]WIPE"))
        assertEquals(MessageType.STATUS, type("[aegis:cmd]LOCK"))
    }

    @Test fun remote_envelope_routes_to_control_not_text() {
        // A hand-injected [aegis:remote] packet is classified as a hidden
        // control message (STATUS) so it reaches RemoteAccessHandler — which
        // then enforces the PIN/session auth. The point here is only that it
        // never lands in chat as TEXT.
        assertEquals(MessageType.STATUS, type("""[aegis:remote]{"v":1,"kind":"wipe","sid":"forged"}"""))
    }
}

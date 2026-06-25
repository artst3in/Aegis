package app.aether.aegis.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the chat-envelope codec: round-trip fidelity, the additive-evolution
 * rule (ignore unknown fields, default absent optionals), and fail-safe
 * decoding (null, never a throw, on a malformed or identity-less frame).
 *
 * Runs under Robolectric so the encode/decode path gets a REAL `org.json`
 * implementation — the unit-test android.jar ships only throwing stubs.
 */
@RunWith(RobolectricTestRunner::class)
// Plain Application — do NOT boot the real AegisApp (its onCreate loads native
// libsodium via JNA, which isn't available in the unit-test JVM).
@Config(application = android.app.Application::class)
class ChatEnvelopeTest {

    @Test fun encode_decode_round_trips_minimal() {
        val env = ChatEnvelope(dna = "2026-06-13T15:35:58.075471695Z", text = "hello")
        val back = ChatEnvelope.decode(env.encode())
        assertEquals(env, back)
        assertNull("no replyToDna on a non-reply", back?.replyToDna)
    }

    @Test fun encode_decode_round_trips_with_reply() {
        val env = ChatEnvelope(
            dna = "2026-06-13T15:35:59.000000001Z",
            text = "re: that",
            replyToDna = "2026-06-13T15:35:58.075471695Z",
        )
        assertEquals(env, ChatEnvelope.decode(env.encode()))
    }

    @Test fun decode_ignores_unknown_future_fields() {
        // Forward-compat: an older reader must tolerate a newer sender's fields
        // (e.g. a Phase-2 burn TTL) without choking.
        val json = """{"dna":"2026-06-13T15:35:58.075471695Z","text":"hi",""" +
            """"burnTtlMs":5000,"reactions":["👍"]}"""
        val env = ChatEnvelope.decode(json)
        assertEquals("hi", env?.text)
        assertEquals("2026-06-13T15:35:58.075471695Z", env?.dna)
    }

    @Test fun decode_defaults_absent_optional_text() {
        // Backward-compat: a missing optional defaults rather than failing.
        val env = ChatEnvelope.decode("""{"dna":"2026-06-13T15:35:58.075471695Z"}""")
        assertEquals("", env?.text)
    }

    @Test fun decode_rejects_frame_with_no_dna() {
        // dna is the identity — a frame without it is not a valid chat frame.
        assertNull(ChatEnvelope.decode("""{"text":"orphan"}"""))
    }

    @Test fun decode_rejects_malformed_json_without_throwing() {
        assertNull(ChatEnvelope.decode("not json"))
        assertNull(ChatEnvelope.decode(""))
    }
}

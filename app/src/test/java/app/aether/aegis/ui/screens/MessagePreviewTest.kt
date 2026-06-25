package app.aether.aegis.ui.screens

import app.aether.aegis.core.Message
import app.aether.aegis.core.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tests for the contact-list last-message preview (SPEC_TESTING #8,
 * security invariant #2).
 *
 * The security-relevant rule: Aegis control / evidence envelopes — most
 * importantly the SOS-time `[aegis:sos-audio]` audio-chunk and
 * `[aegis:sos-frame]` camera fan-out — must NEVER surface as a readable
 * preview. They may show their attachment TYPE, or nothing, but never
 * the raw `[aegis:…]` marker. Regressing this re-opens the SOS chat
 * leak that shipped in 2026.06.
 */
class MessagePreviewTest {

    private val self = "SELF_KEY"
    private val peer = "PEER_KEY"

    private fun msg(
        content: String,
        from: String = peer,
        attachmentPath: String? = null,
        attachmentMime: String? = null,
        attachmentName: String? = null,
    ) = Message(
        id = "1",
        from = from,
        to = self,
        content = content,
        timestamp = 0L,
        protocol = Protocol.SIMPLEX,
        attachmentPath = attachmentPath,
        attachmentMime = attachmentMime,
        attachmentName = attachmentName,
    )

    @Test fun ordinary_incoming_text_shows_verbatim() {
        assertEquals("hello there", lastMsgPreview(msg("hello there"), self))
    }

    @Test fun self_text_gets_You_prefix() {
        assertEquals("You: hi", lastMsgPreview(msg("hi", from = self), self))
    }

    @Test fun SOS_sos_audio_shows_Voice_never_the_tag() {
        val preview = lastMsgPreview(
            msg(
                content = "[aegis:sos-audio] seg_001.m4a",
                from = self,
                attachmentPath = "/data/seg_001.m4a",
                attachmentMime = "audio/mp4",
            ),
            self,
        )
        assertEquals("You: Voice", preview)
        assertFalse("control tag leaked into preview: $preview", preview.contains("[aegis:"))
    }

    @Test fun SOS_sos_frame_shows_Photo_never_the_tag() {
        val preview = lastMsgPreview(
            msg(
                content = "[aegis:sos-frame] frame_12.jpg",
                attachmentPath = "/data/frame_12.jpg",
                attachmentMime = "image/jpeg",
            ),
            self,
        )
        assertEquals("Photo", preview)
        assertFalse(preview.contains("[aegis:"))
    }

    @Test fun bare_control_envelope_collapses_to_empty() {
        // No attachment + an `[aegis:…]` body → nothing, not the raw tag.
        assertEquals("", lastMsgPreview(msg("[aegis:tier]GOLD"), self))
        assertEquals("", lastMsgPreview(msg("[aegis:typing]", from = self), self))
    }

    @Test fun ordinary_attachment_with_blank_caption_shows_type() {
        assertEquals(
            "Voice",
            lastMsgPreview(
                msg(content = "", attachmentPath = "/data/a.m4a", attachmentMime = "audio/mp4"),
                self,
            ),
        )
        assertEquals(
            "Photo",
            lastMsgPreview(
                msg(content = "", attachmentPath = "/data/p.jpg", attachmentMime = "image/jpeg"),
                self,
            ),
        )
    }

    @Test fun incoming_text_truncates_to_60_chars() {
        val long = "x".repeat(200)
        // Incoming (no "You:" prefix) → exactly the 60-char body cap.
        assertEquals(60, lastMsgPreview(msg(long), self).length)
    }
}

package app.aether.aegis.protocol

import org.json.JSONObject

/**
 * The Aegis chat-plane message envelope — Aegis's OWN message format, carried
 * as opaque bytes by whatever transport rather than leaning on a transport's
 * content type (SimpleX `MCText`) and per-device row ids.
 *
 * Two planes, two content types (the separation is deliberate and load-bearing):
 *   - **Control plane** — `x.aegis` ([app.aether.aegis.cmdauth.ControlChannel]):
 *     commands (presence, ticks, capability hello, SOS). Dispatched, never
 *     shown, no history.
 *   - **Chat plane** — `x.aegis.chat` (THIS type): user messages as a structured
 *     envelope. Displayed, sealed at rest, kept in history.
 * Distinct types because chat (data) and control (commands) have different
 * trust, lifecycle, and evolution — a control bug must never be able to touch
 * chat, and the "this frame is a command, never a message" guarantee must hold.
 *
 * ## Evolution rule: additive only
 *
 * Fields are only ever ADDED, never repurposed or removed, and every reader
 * tolerates unknown fields (forward-compat) and missing optional fields
 * (backward-compat). [decode] therefore ignores anything it doesn't know and
 * defaults absent optionals — so a newer sender and an older receiver interop
 * without a version handshake. This is the wire half of capability negotiation.
 *
 * Phase 1 carries the minimum: [dna] (identity) + [text] (body) + an optional
 * [replyToDna] (reply-by-DNA, better than a transport's local quote id). Phase 2
 * folds burn TTL / edit version / reactions in here as further additive fields,
 * replacing the `[aegis:…]` text markers.
 */
data class ChatEnvelope(
    /** The sender-minted [MessageDna] for this message. The protocol's message
     *  identity; both ends hold the identical value. */
    val dna: String,
    /** The user-visible message body. This is the field that gets sealed at
     *  rest; the transport's plaintext mirror is purged after seal, as today. */
    val text: String,
    /** When this message is a reply, the [dna] of the quoted message. Null for
     *  non-replies. Reply-by-DNA travels in the envelope so it survives a
     *  transport swap, unlike a transport-local quote id. */
    val replyToDna: String? = null,
) {
    /** Serialize to the compact JSON carried as the `x.aegis.chat` payload.
     *  Optional fields are omitted when null so the wire stays minimal and a
     *  reader never has to distinguish "absent" from "null". */
    fun encode(): String = JSONObject()
        .put("dna", dna)
        .put("text", text)
        .apply { replyToDna?.let { put("replyToDna", it) } }
        .toString()

    companion object {
        /** SimpleX `MsgContent` type for the chat plane. A stable identity tag —
         *  the envelope evolves additively, the type string never versions
         *  (same discipline as the control plane's `x.aegis`). */
        const val CONTENT_TYPE = "x.aegis.chat"

        /**
         * Parse a chat-plane payload. Returns null (rather than throwing) on a
         * malformed body or a missing required field ([dna]), so a garbled or
         * spoofed frame can never crash the receive path — the caller drops it.
         * Unknown fields are ignored and absent optionals default, per the
         * additive-evolution rule.
         */
        fun decode(json: String): ChatEnvelope? = runCatching {
            val o = JSONObject(json)
            // dna is required — getString throws (→ null) if absent, which is
            // the validation we want: no identity, not a valid chat frame.
            val dna = o.getString("dna")
            ChatEnvelope(
                dna = dna,
                text = o.optString("text", ""),
                replyToDna = o.optString("replyToDna", "").ifBlank { null },
            )
        }.getOrNull()
    }
}

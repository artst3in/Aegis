package app.aether.aegis.protocol

/**
 * Aegis capability vocabulary — the structured "what this build can do" set
 * peers exchange so newer and older clients interoperate without a version
 * comparison (per SPEC_LAUNCH_MIGRATION: capability negotiation, NOT semantic
 * version maths).
 *
 * Capabilities ride a dedicated `[aegis:caps]<csv>` control envelope (announced
 * alongside the hello and on every app start), stored per-peer in
 * `known_peers.peerCapabilities`. The rule is strictly additive: a build only
 * ever ADDS capability tokens, and a reader ignores tokens it doesn't know — so
 * an older peer simply never claims a newer capability, and a newer peer gates
 * the corresponding behaviour off for it.
 *
 * ## Why this gate is load-bearing — and is NOT legacy/compat to delete
 *
 * The chat envelope ([ChatEnvelope], content type `x.aegis.chat`) is a NEW
 * SimpleX content type. A peer that has not ANNOUNCED [CHAT] doesn't match
 * "text" or the control type, so it falls into its file-attachment handler and
 * the message body is LOST. So `x.aegis.chat` is sent ONLY to a peer that
 * announced [CHAT]; everyone else gets the plain `MCText` shim.
 *
 * This is required even when EVERY peer is a current, clean-slate install — it
 * is not an old-build shim. Capabilities are exchanged ASYNCHRONOUSLY (the
 * `[aegis:caps]` envelope rides the hello and each app start), so a
 * freshly-paired peer has `peerCapabilities == null` for the first beat, until
 * that announce arrives; a chat message sent in that window must take the
 * MCText path or it is silently lost. The gate is ALSO the forward-evolution
 * path: a future wire format gates itself behind a NEW token here and is sent
 * only to peers that announced it. There is no version maths and nothing to
 * "carry forward" — only "ask what the peer can do, right now." Do NOT remove
 * this under the clean-slate / no-compat rule; deleting it loses real messages
 * to current peers during the announce window.
 */
object AegisCaps {

    /** Sends/receives the structured chat envelope (`x.aegis.chat`) with DNA
     *  identity and DNA-keyed read/delivered/sealed receipts. */
    const val CHAT = "chat"

    /** Accepts reply-by-DNA (`replyToDna` in the envelope) and the DNA-keyed
     *  edit/delete control frames, instead of the transport's native quote /
     *  update / delete. Implies [CHAT]. */
    const val EDIT_DNA = "editdna"

    /**
     * This build's own capability set, announced to peers. Comma-separated, no
     * spaces. ADD tokens here as features land; never remove or repurpose one
     * (a removed token would silently turn a peer's feature off).
     */
    val SELF: String = listOf(CHAT, EDIT_DNA).joinToString(",")

    /**
     * True if a peer's announced capability CSV ([known_peers.peerCapabilities])
     * contains [cap]. Null/blank (peer never announced, or a pre-caps build)
     * means "does not support" — fail safe to the degraded path.
     */
    fun supports(peerCaps: String?, cap: String): Boolean {
        if (peerCaps.isNullOrBlank()) return false
        // Exact token match within the CSV — guard against substring hits
        // (e.g. "chat" must not match a hypothetical "chatx").
        return peerCaps.split(',').any { it.trim() == cap }
    }
}

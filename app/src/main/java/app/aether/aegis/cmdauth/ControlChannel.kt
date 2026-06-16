package app.aether.aegis.cmdauth

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * The separated control channel.
 *
 * Control commands ride as a custom MsgContent type ([CONTENT_TYPE]) instead
 * of the chat-text field, so a vanilla SimpleX client renders them as an
 * empty message (it can't read the command) and a hand-typer can't produce
 * one (the compose box only emits `MCText`). For 1:1 commands that separation
 * IS the anti-spoof floor: only a genuine Aegis peer on an authenticated
 * SimpleX connection can place an x.aegis envelope into the conversation,
 * and SimpleX's double-ratchet already guarantees ordering + replay
 * resistance on that connection — so a direct envelope needs no further
 * application-layer signature or counter. Build/read it with
 * [buildPlainEnvelope] / [readPlain].
 *
 * 1:1 envelope (the MsgContent JSON):
 *
 *     {"type":"x.aegis","text":"","cmd":"<tag>","data":"<json>"}
 *
 * --- Signed primitives (GROUP path, future — NOT used for 1:1) ---
 *
 * [buildEnvelope] / [verifyEnvelope] add an Ed25519 signature + monotonic
 * counter. They are retained for the planned group control path, where a
 * command fans out through a relay the 1:1 ratchet does not bind, so a
 * per-sender signature is needed to attribute it and a counter to replay-
 * protect it. The signed envelope additionally carries `ctr` + `sig`:
 *
 *     {"type":"x.aegis","text":"","ctr":<n>,"cmd":"<tag>",
 *      "data":"<json>","sig":"<base64url Ed25519 over signedPayload>"}
 *
 * signedPayload (the exact bytes that are signed and verified):
 *
 *     "<recipientControlPubHex>|<ctr>|<cmd>|<data>"
 *
 * Why the recipient pubkey is IN the signature: it binds the command to its
 * intended recipient. A peer who relays a command we signed for them to a
 * third party fails — the third party reconstructs the payload with ITS OWN
 * pubkey, which won't match the signature. [ctr] is a per-pair monotonic
 * counter: the receiver rejects ctr ≤ the last it accepted (replay defense).
 *
 * Stateless + pure (no Android deps beyond Base64/JSON) so it's unit-checkable
 * apart from the native Ed25519 in [ControlKeypair].
 */
object ControlChannel {

    /**
     * The custom MsgContent type — a STABLE identity tag, not a version. A
     * vanilla SimpleX client renders it "no text" and can't forge it; that
     * recognition is this string's entire job, so it never changes.
     *
     * Versioning is deliberately NOT carried here. The protocol version is just
     * the app version — the one `YYYY.MM.BBB` scheme, mechanical, every build —
     * and it rides the hello capability exchange where peers negotiate it.
     * Baking a version into an exact-matched type would force a false choice:
     * freeze it forever, or run a parallel "bump on a breaking change" counter
     * — and that counter is exactly the semantic-version judgment the
     * `YYYY.MM.BBB` scheme exists to delete. So the type stays identity-only;
     * compatibility is a negotiation on the app version, never a string match on
     * the type.
     */
    const val CONTENT_TYPE = "x.aegis"

    /**
     * Build the UNSIGNED 1:1 control envelope carrying [cmd]/[data]. No
     * counter, no signature: the custom [CONTENT_TYPE] plus SimpleX's
     * authenticated, replay-resistant connection are the entire guarantee for
     * a direct message. Always succeeds — no key lookup, no native sign, so a
     * direct command can never be silently dropped for want of a bootstrapped
     * key (the desync that used to strand presence/ticks/location).
     */
    fun buildPlainEnvelope(cmd: String, data: String): JSONObject =
        JSONObject()
            .put("type", CONTENT_TYPE)
            .put("text", "")          // empty → vanilla clients show nothing
            .put("cmd", cmd)
            .put("data", data)

    /** A control command read off the 1:1 (unsigned) channel. */
    data class Plain(val cmd: String, val data: String)

    /**
     * Read [cmd]/[data] from an inbound x.aegis envelope — the 1:1 path.
     * 1:1 control is unsigned (the custom type plus SimpleX's authenticated,
     * replay-resistant connection are the whole guarantee), so there is no
     * signature or counter to check. Returns null only when [cmd] is absent
     * (malformed).
     */
    fun readPlain(env: JSONObject): Plain? {
        val cmd = env.optString("cmd").ifBlank { return null }
        val data = env.optString("data")
        return Plain(cmd, data)
    }

    /**
     * GROUP path (future). Build the SIGNED MsgContent envelope to send
     * [cmd]/[data] to a peer whose control pubkey is [recipientPubB64]
     * (learned at their hello), using our monotonic [ctr]. Returns null if the
     * recipient has no known control key (no bootstrapped channel) or we can't
     * sign. Not used on the 1:1 path — see [buildPlainEnvelope].
     */
    fun buildEnvelope(
        ctx: Context,
        recipientPubB64: String?,
        ctr: Long,
        cmd: String,
        data: String,
    ): JSONObject? {
        val recipientPubHex = hexOfB64(recipientPubB64) ?: return null
        val payload = signedPayload(recipientPubHex, ctr, cmd, data)
        val sig = ControlKeypair.sign(ctx, payload) ?: return null
        return JSONObject()
            .put("type", CONTENT_TYPE)
            .put("text", "")          // empty → vanilla clients show nothing
            .put("ctr", ctr)
            .put("cmd", cmd)
            .put("data", data)
            .put("sig", b64Encode(sig))
    }

    /** A verified inbound command (GROUP path, future). */
    data class Verified(val cmd: String, val data: String, val ctr: Long)

    /**
     * GROUP path (future). Verify an inbound SIGNED control envelope. Not used
     * on the 1:1 path — see [readPlain].
     *
     *  - [senderPubB64]: the peer's control pubkey (from their hello). Null →
     *    no channel was ever bootstrapped with them → reject.
     *  - [ourPub]: our own control pubkey bytes (recipient binding).
     *  - [lastCtr]: highest counter we've already accepted from this peer.
     *
     * Returns the verified (cmd, data, ctr) or null on ANY failure — no
     * sender key, replay/stale ctr, bad signature, malformed envelope. The
     * caller persists the new ctr on success. Fail = drop, never dispatch.
     */
    fun verifyEnvelope(
        env: JSONObject,
        senderPubB64: String?,
        ourPub: ByteArray,
        lastCtr: Long,
    ): Verified? {
        val senderPub = b64Decode(senderPubB64 ?: return null) ?: return null
        val ctr = env.optLong("ctr", -1L)
        if (ctr <= lastCtr) return null                       // replay / stale
        val cmd = env.optString("cmd").ifBlank { return null }
        val data = env.optString("data")
        val sig = b64Decode(env.optString("sig").ifBlank { return null }) ?: return null
        val ourPubHex = hexOf(ourPub)
        val payload = signedPayload(ourPubHex, ctr, cmd, data)
        return if (ControlKeypair.verify(payload, sig, senderPub)) {
            Verified(cmd, data, ctr)
        } else {
            null
        }
    }

    private fun signedPayload(recipientPubHex: String, ctr: Long, cmd: String, data: String): ByteArray =
        "$recipientPubHex|$ctr|$cmd|$data".toByteArray(Charsets.UTF_8)

    // ---- base64url + hex helpers ----
    private const val B64 = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    private fun b64Encode(bytes: ByteArray): String = Base64.encodeToString(bytes, B64)
    private fun b64Decode(s: String): ByteArray? =
        runCatching { Base64.decode(s, B64) }.getOrNull()
    private fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun hexOfB64(b64: String?): String? =
        if (b64.isNullOrEmpty()) null else b64Decode(b64)?.let { hexOf(it) }
}

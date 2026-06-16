package app.aether.aegis.admin

import android.content.Context
import android.util.Log
import app.aether.aegis.AegisApp
import app.aether.aegis.core.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Capability-bootstrap broadcaster (companion to [TierBroadcaster]).
 *
 * The per-peer `[aegis:…]` gate in
 * [app.aether.aegis.core.ProtocolManager.gateAegisControl] keeps control
 * envelopes (location, status, typing, tier announces) from
 * leaking as raw text to vanilla SimpleX clients — but the cost
 * is that a fresh Aegis ↔ Aegis pair starts with
 * `known_peers.isAegis = false` on BOTH sides, and the gate then
 * drops every aegis-tagged outbound until the flag flips. The
 * only way the flag flips is by RECEIVING an `[aegis:*]` from the
 * peer, which their side's gate is also dropping. Deadlock.
 *
 * The bootstrap envelope is `[aegis:hello]` (no identity payload),
 * carved out of both gates by design so it's always allowed
 * through. The catch-all in
 * [app.aether.aegis.simplex.SimpleXTransport.handleNewChatItems] (~line 2168)
 * then flips the receiver's isAegis row for the sender, and a
 * symmetric send from the other side completes the handshake.
 *
 * Two trigger paths populate it:
 *
 *   1. [app.aether.aegis.simplex.SimpleXTransport.handleContactConnected]
 *      fires once per FRESH pairing, sending the hello immediately.
 *      Covers new contacts post-500.
 *   2. [broadcastNow] fires on every cold start for every paired peer
 *      whose `controlPubKey` is still null — i.e. the hello handshake
 *      hasn't completed for that peer yet. This is RESILIENCE for the
 *      current clean-slate flow, not an old-build shim: the path-1 fresh
 *      pairing hello can simply not land (peer offline at pairing, app
 *      killed mid-handshake, a dropped message), leaving the channel
 *      half-open. Re-greeting on the next cold start closes that gap so
 *      the channel always converges. Filtering on the missing pubkey
 *      (not the `isAegis` flag) is deliberate: a peer can be isAegis=true
 *      yet still have no control channel, and that combination silently
 *      kills delivery ticks + typing/status/location/sos control until
 *      re-greeted. The pubkey isn't used to verify 1:1 commands (those
 *      ride the unsigned x.aegis type); it survives purely as this
 *      "already bootstrapped" marker, so once exchanged the peer is
 *      skipped on subsequent starts.
 *
 * Cost: one tiny SimpleX message per un-bootstrapped peer per cold
 * start. Zero once the control pubkey is exchanged for that peer.
 *
 * NOTE (clean-slate rule): the "paired before X shipped" history that
 * motivated this is gone, but the mechanism stays — it's how ANY peer,
 * including two brand-new installs, recovers a half-completed handshake.
 * Don't mistake it for migration/compat code.
 */
object HelloBroadcaster {

    suspend fun broadcastNow(context: Context) = withContext(Dispatchers.IO) {
        val repo = runCatching { AegisApp.instance.repository }.getOrNull() ?: return@withContext
        val peers = runCatching { repo.allKnownPeers() }
            .getOrElse {
                Log.w(TAG, "broadcastNow: allKnownPeers failed", it)
                return@withContext
            }
            // Re-greet anyone we haven't exchanged a control pubkey with —
            // this is the signal that the signed control channel isn't
            // bootstrapped yet, regardless of the older isAegis flag.
            .filter { it.controlPubKey.isNullOrEmpty() }
        if (peers.isEmpty()) return@withContext
        // AEGIS PROTOCOL: hello carries NO identity — only our Ed25519
        // CONTROL pubkey. The bare prefix alone flips isAegis; the key marks
        // the channel as bootstrapped (so we stop re-greeting) and seeds the
        // future signed group path. Same base64url encoding as
        // SimpleXTransport.sendAegisHello.
        val pubB64 = runCatching {
            val pub = app.aether.aegis.cmdauth.ControlKeypair.publicKey(AegisApp.instance)
            android.util.Base64.encodeToString(
                pub,
                android.util.Base64.URL_SAFE or
                    android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            )
        }.getOrDefault("")
        val body = "[aegis:hello]$pubB64"
        peers.forEach { peer ->
            runCatching {
                AegisApp.instance.protocolManager.sendMessage(
                    to = peer.publicKey,
                    content = body,
                    type = MessageType.STATUS,
                )
            }.onFailure { Log.w(TAG, "hello send to ${peer.publicKey} failed", it) }
        }
        Log.i(TAG, "broadcast aegis:hello (key=${pubB64.isNotEmpty()}) to ${peers.size} un-bootstrapped peer(s)")
    }

    private const val TAG = "HelloBroadcaster"
}

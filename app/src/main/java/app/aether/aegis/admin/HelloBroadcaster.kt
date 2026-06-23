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
 * envelopes (location, status, typing, tier announces) from leaking as raw
 * text to vanilla SimpleX clients. The control channel is opened for a peer
 * only once we KNOW they're Aegis — `known_peers.isAegis`, which flips the
 * first time we RECEIVE an `[aegis:*]` from them.
 *
 * Bootstrapping a fresh Aegis ↔ Aegis pair: both sides start isAegis=false,
 * and the discovery probe is the one-shot `[aegis:hello]` sent at pairing by
 * [app.aether.aegis.simplex.SimpleXTransport.handleContactConnected]. When a
 * peer RECEIVES that hello, their side flips isAegis for us and the catch-all
 * in handleNewChatItems records it; a symmetric send completes the handshake.
 * That single pairing probe is the only control message a NON-Aegis contact
 * ever sees — the unavoidable cost of auto-detecting whether a new contact
 * runs Aegis.
 *
 * [broadcastNow] is the cold-start CONVERGENCE retry: it re-greets peers whose
 * control channel is half-open (no `controlPubKey` yet) so a pairing hello
 * that didn't land — peer offline, app killed mid-handshake, dropped message —
 * still converges. It is GATED ON isAegis: a plain SimpleX contact is
 * isAegis=false forever (they never send us an aegis tag), so they can never
 * complete the handshake and must NOT be re-greeted — re-greeting every
 * null-pubkey peer regardless of isAegis sprayed `[aegis:hello]` junk at
 * vanilla contacts on every launch (user-reported spam). Gating on isAegis
 * blocks the control channel to non-Aegis peers, which is the whole point of
 * the gate; a real Aegis peer whose pairing hello was lost still converges as
 * long as ONE side received a hello (its re-greets reach the other and flip
 * isAegis there). Only a double-lost pairing hello stays half-open — rare, and
 * far better than spamming every SimpleX contact.
 *
 * Cost: one tiny SimpleX message per un-converged AEGIS peer per cold start,
 * zero once the control pubkey is exchanged, and zero ever to a vanilla peer.
 *
 * NOTE (clean-slate rule): not migration/compat — it's how any two installs,
 * including brand-new ones, recover a half-completed handshake.
 */
object HelloBroadcaster {

    suspend fun broadcastNow(context: Context) = withContext(Dispatchers.IO) {
        val repo = runCatching { AegisApp.instance.repository }.getOrNull() ?: return@withContext
        val peers = runCatching { repo.allKnownPeers() }
            .getOrElse {
                Log.w(TAG, "broadcastNow: allKnownPeers failed", it)
                return@withContext
            }
            // GATE THE CONTROL CHANNEL: re-greet only CONFIRMED Aegis peers
            // (isAegis) whose control channel is still half-open (no
            // controlPubKey yet). A vanilla SimpleX contact is isAegis=false
            // and can never complete the handshake, so without this gate the
            // re-greet sprayed `[aegis:hello]` at them on every cold start.
            // isAegis only flips on receiving an aegis tag → control stays
            // blocked to non-Aegis contacts, as it must.
            .filter { it.isAegis && it.controlPubKey.isNullOrEmpty() }
        if (peers.isEmpty()) return@withContext
        // AEGIS PROTOCOL: hello carries NO identity — only our Ed25519
        // CONTROL pubkey. The key marks the channel as bootstrapped (so we
        // stop re-greeting) and seeds the future signed group path. Same
        // base64url encoding as SimpleXTransport.sendAegisHello.
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
        Log.i(TAG, "broadcast aegis:hello (key=${pubB64.isNotEmpty()}) to ${peers.size} un-converged Aegis peer(s)")
    }

    private const val TAG = "HelloBroadcaster"
}

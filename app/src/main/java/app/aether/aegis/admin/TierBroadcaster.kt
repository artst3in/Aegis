package app.aether.aegis.admin

import android.content.Context
import android.util.Log
import app.aether.aegis.AegisApp
import app.aether.aegis.core.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tier-reward broadcast. The shield tier
 * a user has reached on their own skill tree is also the avatar
 * frame their Trusted contacts see — but only if those contacts
 * receive the tier-name over the wire.
 *
 * Envelope shape:  `[aegis:tier]<TIER_NAME>`
 * TIER_NAME is exactly the `name()` of [ShieldTier], so the receiver
 * can `valueOf()` it without a translation table.
 *
 * Sent to Trusted contacts only. Emergency / Untrusted peers don't
 * get tier announcements (consistent with the Trust Model — they
 * don't receive routine sharing of any kind). Sends route through
 * ProtocolManager.sendMessage which goes through the per-peer
 * `[aegis:…]` gate, so non-Aegis Trusted peers also silently drop
 * the announce.
 *
 * Cadence:
 *   - On app start (one shot after transports are up).
 *   - On any local change to one of the skill-tree node states
 *     (PIN set/unset, Vault PIN, Canary, etc.) — callers explicitly
 *     invoke [broadcastNow].
 *
 * No SharedPreferences flag — we always re-announce on app start
 * because peers might have missed previous announces (offline,
 * not paired yet, fresh install). The cost is one tiny SimpleX
 * message per Trusted contact per cold start.
 */
object TierBroadcaster {

    /** Idempotent — computes the current self tier and ships
     *  `[aegis:tier]<NAME>` to every Trusted contact. Safe to call
     *  often; the per-peer aegis-gate suppresses sends to non-Aegis
     *  peers. */
    suspend fun broadcastNow(context: Context) = withContext(Dispatchers.IO) {
        val tier = runCatching { ShieldTierEngine.currentTier(context) }
            .getOrElse {
                Log.w(TAG, "broadcastNow: tier compute failed", it)
                return@withContext
            }
        val targets = runCatching { AegisApp.instance.repository.trustedTargets() }
            .getOrNull() ?: return@withContext
        if (targets.isEmpty()) return@withContext
        val tierBody = "[aegis:tier]${tier.name}"
        // Crown shimmer style rides alongside the tier. It only renders at the
        // Cyan tier (the shine is the Cyan-crown reward), but we announce it
        // regardless so a contact already knows the style the moment Cyan lands
        // — no separate re-broadcast needed on tier-up. Mirrors the tier
        // envelope: STATUS, Trusted-only, dropped silently for non-Aegis peers
        // by the per-peer gate.
        val crownStyle = app.aether.aegis.prefs.ExperimentalPrefs(context).crownStyle
        val crownBody = "[aegis:crown]$crownStyle"
        // Capability announcement rides the same app-start fan-out so an
        // already-paired peer relearns our capabilities after we upgrade — that
        // is what lets a contact start sending us the chat envelope once both
        // sides support it. Pre-capability peers ignore the unknown envelope.
        val capsBody = "[aegis:caps]${app.aether.aegis.protocol.AegisCaps.SELF}"
        targets.forEach { peer ->
            runCatching {
                AegisApp.instance.protocolManager.sendMessage(
                    to = peer.publicKey,
                    content = tierBody,
                    type = MessageType.STATUS,
                )
            }.onFailure { Log.w(TAG, "tier send to ${peer.publicKey} failed", it) }
            runCatching {
                AegisApp.instance.protocolManager.sendMessage(
                    to = peer.publicKey,
                    content = crownBody,
                    type = MessageType.STATUS,
                )
            }.onFailure { Log.w(TAG, "crown send to ${peer.publicKey} failed", it) }
            runCatching {
                AegisApp.instance.protocolManager.sendMessage(
                    to = peer.publicKey,
                    content = capsBody,
                    type = MessageType.STATUS,
                )
            }.onFailure { Log.w(TAG, "caps send to ${peer.publicKey} failed", it) }
        }
    }

    private const val TAG = "TierBroadcaster"
}

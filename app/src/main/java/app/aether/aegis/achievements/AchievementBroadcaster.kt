package app.aether.aegis.achievements

import android.util.Log
import app.aether.aegis.AegisApp
import app.aether.aegis.core.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Shares the user's earned-badge set with TRUSTED contacts only —
 * badge state is shared with trusted contacts as a verification
 * signal, and is not transmitted to non-trusted contacts.
 *
 * Wire format is a plain control envelope sent as a normal text
 * message:
 *
 *     [aegis:badges]<id>,<id>,…
 *
 * The receiver's inbound `[aegis:…]` dispatch parses it into
 * [PeerBadgeStore]. Emergency and Untrusted contacts never receive it
 * — trust gating happens here, at the sender, by fanning out to
 * [app.aether.aegis.data.Repository.trustedTargets] only.
 *
 * Fire-and-forget and exception-safe: it is invoked from the
 * bulletproof [Achievements.unlock] path, so it must never throw.
 */
object AchievementBroadcaster {

    private const val TAG = "AchievementBroadcaster"

    /** Envelope prefix — also matched by the inbound dispatch. */
    const val ENVELOPE = "[aegis:badges]"

    /**
     * Announce [earnedIds] to every TRUSTED contact. Best-effort: a
     * per-peer send failure is swallowed so one offline contact can't
     * block the rest, and the whole thing is wrapped so nothing
     * bubbles back into the caller.
     */
    fun broadcastEarnedToTrusted(earnedIds: Set<String>) {
        val body = ENVELOPE + earnedIds.sorted().joinToString(",")
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val repo = AegisApp.instance.repository
                val pm = AegisApp.instance.protocolManager
                val trusted = repo.trustedTargets()
                for (peer in trusted) {
                    // STATUS type so it routes through the receiver's
                    // handleInboundStatus [aegis:…] dispatch. The
                    // outbound gate passes it because trusted contacts
                    // are confirmed-Aegis peers (isAegis); non-Aegis
                    // peers drop it harmlessly (they couldn't use it).
                    runCatching { pm.sendMessage(peer.publicKey, body, MessageType.STATUS) }
                }
                Log.i(TAG, "shared ${earnedIds.size} badges → ${trusted.size} trusted contacts")
            }.onFailure { Log.w(TAG, "badge broadcast failed — ignored", it) }
        }
    }
}

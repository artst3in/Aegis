package app.aether.aegis.prefs

import android.content.Context
import app.aether.aegis.AegisApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Per-conversation read watermark — the wall-clock time the user last had
 * a chat on screen. A conversation is UNREAD when its latest INBOUND
 * message arrived after this. Plain SharedPreferences, local-only: no DB
 * schema change, nothing on the wire. Keyed by the conversation id (peer
 * pubkey, or `group:<id>`), the same key space DraftStore uses.
 *
 * Reactive: the in-memory [reads] StateFlow mirrors the prefs so the chat
 * list, the inner Contacts/Groups tabs, and the bottom-nav badge all
 * recompute the instant a chat is opened.
 */
object ReadStore {
    private const val STORE = "aegis_reads"
    private fun prefs() =
        AegisApp.instance.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private val _reads = MutableStateFlow<Map<String, Long>>(
        prefs().all.entries.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap(),
    )

    /** conversationKey → last-read epoch millis. */
    val reads: StateFlow<Map<String, Long>> = _reads.asStateFlow()

    /** Mark [key] read up to [at] (now by default). Called whenever a chat
     *  is on screen — on open and on each message that lands while open.
     *  Never moves the watermark backward. */
    fun markRead(key: String, at: Long = System.currentTimeMillis()) {
        val cur = _reads.value[key] ?: 0L
        if (at <= cur) return
        prefs().edit().putLong(key, at).apply()
        _reads.value = _reads.value + (key to at)
    }

    fun lastReadAt(key: String): Long = _reads.value[key] ?: 0L
}

/**
 * Derives the set of conversations with unread inbound messages by joining
 * the latest-message-per-peer stream with [ReadStore]. A conversation
 * counts as unread when its newest message is FROM the peer (not your own
 * last reply) and is newer than your read watermark — the same rule
 * mainstream messengers use.
 */
object UnreadTracker {
    /** Conversation keys (peer pubkey or `group:<id>`) that are unread. */
    fun observe(): Flow<Set<String>> = combine(
        AegisApp.instance.repository.observeLastMessagePerPeer(),
        ReadStore.reads,
        AegisApp.instance.repository.observeGroups(),
        app.aether.aegis.groups.GroupModulePrefs.enabledFlowStatic(),
    ) { lastByPeer, reads, groups, groupModuleEnabled ->
        val self = AegisApp.instance.identity.deviceId
        // Group conversation keys that STILL EXIST. Leaving or deleting a
        // group can strand its message rows (peerKey "group:<id>") without
        // purging them; their last inbound message would then keep the
        // conversation "unread" forever — lighting the Groups tab dot and
        // the Comms nav dot even when the Groups screen shows none (the
        // "unread dot won't clear" report, screenshot: Groups ● with "No
        // groups yet"). Drop any group: key whose group is gone. Contact
        // keys aren't filtered — a peer pubkey is its own identity, not a
        // foreign key into a row that can disappear.
        val liveGroupKeys = groups.mapTo(HashSet()) { "group:${it.id}" }
        val unread = lastByPeer.entries.asSequence()
            .filter { (peer, msg) ->
                val isGroup = peer.startsWith("group:")
                // Group module gate. When the module is OFF, suppress ALL
                // group unreads: inbound group messages are already dropped
                // at dispatch while disabled, but messages stored BEFORE the
                // module was turned off keep their group:<id> key unread
                // forever (the Groups surface is disabled, so the user can't
                // open the group to clear the watermark). That stale key lit
                // the Groups tab dot, the Comms nav dot, and the widget,
                // falsely signalling that groups run in the background (user
                // report 2026-06-23). State is preserved — the dot returns
                // when the module is re-enabled.
                if (isGroup && !groupModuleEnabled) return@filter false
                msg.from != self && msg.timestamp > (reads[peer] ?: 0L) &&
                    (!isGroup || peer in liveGroupKeys)
            }
            .map { it.key }
            .toSet()
        // DIAGNOSTIC: when a conversation stays unread, log WHY for each
        // stuck key — last message's sender vs self, its timestamp vs the
        // read watermark, and a body snippet — into the in-app Connection
        // log (Diagnostics → Connection log, tag "UnreadDiag") so a dot that
        // won't clear can be SCREENSHOT instead of guessed. Throttled to log
        // only when the stuck SET changes, so it can't spam the log.
        if (unread != lastLoggedUnread) {
            lastLoggedUnread = unread
            unread.forEach { k ->
                val m = lastByPeer[k]
                app.aether.aegis.simplex.ConnectionLog.warn(
                    "UnreadDiag",
                    "stuck key=$k from=${m?.from} self=$self msgTs=${m?.timestamp} " +
                        "readWatermark=${reads[k] ?: 0L} body=\"${m?.content?.take(28)}\"",
                )
            }
        }
        unread
    }

    /** Last stuck-set we logged, so [observe] only emits a diagnostic line
     *  when the set actually changes (no per-emission spam). */
    @Volatile private var lastLoggedUnread: Set<String> = emptySet()
}

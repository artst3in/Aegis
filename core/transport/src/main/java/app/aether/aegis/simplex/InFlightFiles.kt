package app.aether.aegis.simplex

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide registry of SimpleX file transfers that haven't
 * completed yet. The chat UI subscribes to [active] so each
 * conversation can render a "Downloading file.jpg… [Cancel]" banner
 * for the files in flight to/from THAT peer, and the Cancel button
 * calls [SimpleXTransport.cancelFile] with the [Entry.fileId].
 *
 * State lives outside Compose so a transfer survives the user
 * scrolling, navigating away from the chat, or rotating. Cleared on
 * `rcvFileComplete` / `sndFileComplete` (download finished) or any
 * cancel / error event (transfer aborted).
 */
object InFlightFiles {

    /** [peerKey] identifies which conversation the file belongs to —
     *  the same string the chat list / chat screen use to scope
     *  messages. For direct chats that's `aegisIdFor(contact)`; for
     *  groups it's `"group:<uuid>"`. */
    data class Entry(
        val fileId: Long,
        val peerKey: String,
        val fileName: String,
        val totalBytes: Long?,
        val direction: Direction,
        val bytesTransferred: Long = 0L,
    ) {
        enum class Direction { Receiving, Sending }
    }

    private val _active = MutableStateFlow<Map<Long, Entry>>(emptyMap())
    val active: StateFlow<Map<Long, Entry>> = _active

    fun track(entry: Entry) {
        _active.value = _active.value + (entry.fileId to entry)
    }

    fun progress(fileId: Long, bytesTransferred: Long, totalBytes: Long?) {
        val cur = _active.value[fileId] ?: return
        _active.value = _active.value + (
            fileId to cur.copy(
                bytesTransferred = bytesTransferred,
                totalBytes = totalBytes ?: cur.totalBytes,
            )
        )
    }

    fun done(fileId: Long) {
        if (!_active.value.containsKey(fileId)) return
        _active.value = _active.value - fileId
    }

    /** Files for a specific conversation, in id order so the chat
     *  banner is stable across recompositions. */
    fun forPeer(peerKey: String): List<Entry> =
        _active.value.values.filter { it.peerKey == peerKey }.sortedBy { it.fileId }
}

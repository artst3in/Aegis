package app.aether.aegis.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue

/**
 * In-memory typing state — peer → expiration timestamp. When a peer
 * sends an `[aegis:typing]` control message we set their entry to
 * `now + 5 s`. The chat header reads this and renders the three-dot
 * animation while the timestamp is in the future.
 *
 * Not persisted: a process restart wipes the registry, which is fine
 * because the peer will re-fire on their next keystroke.
 */
object TypingTracker {

    private val expirations = mutableStateMapOf<String, Long>()

    /** Stamp [peerKey] as actively typing — drives the chat header. */
    fun mark(peerKey: String) {
        expirations[peerKey] = System.currentTimeMillis() + 5_000L
    }

    /** True if [peerKey] has fired a typing ping within the last 5 s. */
    fun isTyping(peerKey: String): Boolean {
        val exp = expirations[peerKey] ?: return false
        return exp > System.currentTimeMillis()
    }

    /** Immediately clear [peerKey]'s typing state. Called the moment their
     *  actual message arrives so the three-dot indicator drops at once
     *  instead of lingering for the rest of the 5 s window (the message is
     *  already on screen — "still typing" next to it reads as a bug). */
    fun clear(peerKey: String) {
        expirations.remove(peerKey)
    }
}

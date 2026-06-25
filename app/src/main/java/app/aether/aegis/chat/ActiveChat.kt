package app.aether.aegis.chat

/**
 * The conversation the user is looking at RIGHT NOW, or null when none is
 * foregrounded. Set/cleared by ChatScreen / GroupChatScreen on
 * ON_RESUME / ON_PAUSE (foreground, not merely composed), so it's null the
 * moment the app is backgrounded even if a chat screen is still on the stack.
 *
 * [key] uses the same conversation key the notification path targets:
 *   - 1:1   → the peer's aegis id ("simplex:<handle>")
 *   - group → "group:<uuid>"
 *
 * Read by AegisApp.notifyMessage to suppress a banner for a chat you're
 * already reading — otherwise every inbound message buzzed redundantly.
 */
object ActiveChat {
    @Volatile
    var key: String? = null
}

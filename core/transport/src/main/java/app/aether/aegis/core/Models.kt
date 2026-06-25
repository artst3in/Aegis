package app.aether.aegis.core

/**
 * Lightweight contact projection used by the chat-list / radar /
 * group-picker UI layers — built per-screen from KnownPeerEntity
 * rows so the UI doesn't reach into Room directly.
 *
 *   - [name]      — display name to render (KnownPeer.displayName).
 *   - [meshIp]    — first 20 chars of the pubkey, kept under the
 *                   "meshIp" name because the legacy Family
 *                   data layer used a 10.0.0.x mesh address as
 *                   the stable id. We never shipped the mesh, but
 *                   the slot continues to carry a stable
 *                   per-contact key that's short enough for badges.
 *   - [publicKey] — Curve25519 pubkey, the canonical identity.
 *
 * The pre-2026 LunaOS ecosystem version of this class also carried
 * `matrixId` + `simplexAddress`; both were always null on every
 * call site (the Matrix homeserver was never built; SimpleX
 * addresses live on KnownPeerEntity) and have been dropped.
 */
data class FamilyMember(
    val name: String,
    val meshIp: String,
    val publicKey: String,
)

/**
 * Message — protocol-agnostic.
 */
data class Message(
    val id: String,
    val from: String,
    val to: String,
    val content: String,
    val timestamp: Long,
    val protocol: Protocol,
    val type: MessageType = MessageType.TEXT,
    val attachmentPath: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val attachmentName: String? = null,
    /** Pixel dimensions of an image attachment — lets the chat bubble reserve
     *  the exact display height from the aspect ratio before the image decodes,
     *  so the list doesn't reflow on scroll. Null for non-images / legacy rows. */
    val attachmentWidth: Int? = null,
    val attachmentHeight: Int? = null,
    /** Lifecycle status — see MessageEntity comment. */
    val status: String = "sent",
    /** Per-rung receipt timestamps (epoch-ms) for the message Info view —
     *  see MessageEntity. Null until each rung is reached. */
    val deliveredAt: Long? = null,
    val sealedAt: Long? = null,
    val readAt: Long? = null,
    /** SimpleX core's chatItem.meta.itemId — null for non-SimpleX rows. */
    val simplexItemId: Long? = null,
    /** Transport-agnostic message DNA (epoch-nanos) — present for Aegis chat-
     *  envelope messages. Lets reaction/reply target a message by the shared
     *  identity instead of SimpleX's native quote (which doesn't attach to the
     *  x.aegis.chat custom content type). Null for vanilla/legacy rows. */
    val messageDna: Long? = null,
    /** When a reply, the simplexItemId of the quoted message — powers
     *  tap-a-citation → jump-to-original. Null for non-replies. */
    val replyToItemId: Long? = null,
    /** When an Aegis-envelope reply, the DNA of the quoted message — the
     *  transport-agnostic citation link used when no native quote exists
     *  (Aegis replies carry none). Null otherwise. */
    val replyToDna: Long? = null,
    /** JSON-encoded {emoji: count} for reactions. Null when empty. */
    val reactionsJson: String? = null,
    /** Pinned in the chat banner. */
    val pinned: Boolean = false,
    /** Edited after first send — UI tags with "edited" next to time. */
    val edited: Boolean = false,
    /** Burn-after-reading viewing window in seconds.
     *  Null = ordinary message. 0 = unlimited-until-close. */
    val burnTtlSeconds: Int? = null,
    /** Sealed-DEK for an encrypted attachment ([attachmentPath]
     *  ending in `.enc`). Null for plaintext attachments. Renderers
     *  pass this to ChatAttachmentSeal.unsealAttachmentToTemp to get
     *  a viewable scratch path. */
    val sealedDek: ByteArray? = null,
) {
    /** Returns the "other side" of the conversation regardless of direction. */
    fun peerKey(selfKey: String): String = if (from == selfKey) to else from
}

enum class Protocol {
    SIMPLEX,      // SimpleX SMP — the only network transport
    LOCAL,        // self-chat + queued-locally rows
}

enum class MessageType {
    TEXT,
    PHOTO,
    VOICE,
    FILE,
    SOS,        // Emergency alert
    STATUS,       // Device status update (read receipts also live here)
    LOCATION,     // Location update
    STORY,        // 24-hour ephemeral post fanned out to all paired peers
    BURN,         // Burn-after-reading — wiped from both devices on first read
    CALL_LOG,     // Per-call journal entry (WhatsApp-style chat history
                  // chip: "↗ Voice call · 3:45" / "↘ Missed video call").
                  // Local-only — never sent over the wire; both sides
                  // generate their own perspective when the call ends.
                  // Body is JSON: {video, duration_ms, connected, reason}.
    GROUP_SYSTEM, // Group membership / metadata event row in the chat
                  // history. peerKey = "group:<id>". Body is JSON
                  // describing the event: {kind, actor, subject?,
                  // from?, to?, seconds?}. Kinds: JOIN, LEAVE, KICK,
                  // RENAME, ROLE, TTL. Rendered as a compact centre
                  // chip (not a bubble) in GroupChatScreen. Written
                  // locally on user-driven actions AND captured from
                  // SimpleX group events when other members act —
                  // deduped by (groupId, kind, actor, subject,
                  // timestampMinute) so local + echoed don't double-
                  // render.
}

enum class SOSTrigger {
    BUTTON,      // Manual sos button press (in-app, post-unlock)
    LOCKSCREEN,  // SOS hex on the lock screen — no auth required
    DURESS,      // Fired silently by the duress-PIN unlock. Distinct from
                 // LOCKSCREEN so SOSHandler can SUPPRESS the visible
                 // "SOS ACTIVE" own-notification on this path — a coercer
                 // watching the victim unlock must see no tell. The name
                 // rides the alert body so trusted contacts know it was a
                 // coerced (duress) trigger. (Security review 2026-06-07.)
    SNATCH,      // Accelerometer snatch detection
    FALL,        // Fall detection
    INACTIVITY,  // No movement for extended period
    CRASH_DETECTED // Vehicle crash — accelerometer impact while driving.
                 // Same broadcast as a manual sos; the trigger name
                 // rides the alert body as the "Crash detected" source
                 // tag.
}

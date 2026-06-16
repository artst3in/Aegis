package app.aether.aegis.groups

import org.json.JSONObject

/**
 * Structured body for `MessageType.GROUP_SYSTEM` chat rows.
 * These rows are the
 * in-history audit trail for group membership and metadata
 * changes (rename, leave, kick, role-change, TTL set, etc.).
 *
 * Stored as JSON in `MessageEntity.body` so the schema doesn't
 * need a new column per kind and so future kinds can be added
 * without a Room migration. Rendered by [GroupSystemRenderer]
 * (a small Composable in GroupChatScreen) which switches on
 * `kind` to produce the human-readable chip text.
 *
 * Wire format example for a rename:
 * ```
 * {"kind":"RENAME","actor":"<pubkey>","from":"old name","to":"new name"}
 * ```
 *
 * All fields except `kind` and `actor` are optional — kinds that
 * don't have a subject simply omit it.
 */
object GroupSystemPayload {

    /** Discriminated kinds. The string form is what lands in the
     *  JSON body; keep it stable across versions so old rows stay
     *  readable after enum additions. */
    enum class Kind { JOIN, LEAVE, KICK, RENAME, ROLE, TTL, README }

    /**
     * Compose a payload string for storage in
     * `MessageEntity.body`.
     *
     * @param kind     the event kind
     * @param actor    pubkey of the user who performed the action
     * @param subject  pubkey of the user being acted on (KICK,
     *                 ROLE) — null when the event is whole-group
     *                 (RENAME, TTL, etc.)
     * @param from     old value for transitions (RENAME's prior
     *                 name, ROLE's prior role). Null when N/A.
     * @param to       new value for transitions (RENAME's new
     *                 name, ROLE's new role). Null when N/A.
     * @param seconds  TTL in seconds for TTL events. Null when
     *                 N/A.
     */
    fun encode(
        kind: Kind,
        actor: String,
        subject: String? = null,
        from: String? = null,
        to: String? = null,
        seconds: Long? = null,
    ): String {
        val obj = JSONObject()
        obj.put("kind", kind.name)
        obj.put("actor", actor)
        if (subject != null) obj.put("subject", subject)
        if (from != null) obj.put("from", from)
        if (to != null) obj.put("to", to)
        if (seconds != null) obj.put("seconds", seconds)
        return obj.toString()
    }

    /** Decoded view of a stored body. Returns null on parse
     *  failure so the renderer can fall back to a generic "system
     *  event" chip rather than crashing. */
    data class Decoded(
        val kind: Kind,
        val actor: String,
        val subject: String? = null,
        val from: String? = null,
        val to: String? = null,
        val seconds: Long? = null,
    )

    fun decode(body: String): Decoded? = runCatching {
        val obj = JSONObject(body)
        Decoded(
            kind = Kind.valueOf(obj.optString("kind")),
            actor = obj.optString("actor"),
            subject = obj.optString("subject").takeIf { it.isNotEmpty() },
            from = obj.optString("from").takeIf { it.isNotEmpty() },
            to = obj.optString("to").takeIf { it.isNotEmpty() },
            seconds = obj.optLong("seconds", -1L).takeIf { it >= 0 },
        )
    }.getOrNull()
}

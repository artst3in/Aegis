package app.aether.aegis.groups

/**
 * Per-member role inside a group. The permission matrix
 * in `GroupMembersScreen` consults this to decide which actions
 * are available on a given member, and which top-level actions
 * (rename / TTL / invite / delete) the current user can perform.
 *
 * The wire mapping is to SimpleX's lowercase role strings: OWNER
 * → "owner", ADMIN → "admin", MEMBER → "member". OBSERVER exists
 * in SimpleX but Aegis doesn't surface it as a separate concept
 * yet — receive-only members aren't useful for the chat-list /
 * SOS flows we care about.
 *
 * Stored as the enum's name() string in `GroupMemberEntity.role`
 * so the migration default `'MEMBER'` parses back to
 * [GroupRole.MEMBER] without any translation table.
 */
enum class GroupRole {
    MEMBER,
    ADMIN,
    OWNER,
    ;

    /** Lowercase SimpleX wire form. Used by the SimpleXTransport
     *  promote / demote send path. */
    fun toSimplex(): String = name.lowercase()

    companion object {
        /** Parse from the database string (enum.name()), defaulting
         *  to [MEMBER] for unknown / null. Defensive against future
         *  enum additions older clients can't decode. */
        fun fromStored(raw: String?): GroupRole =
            runCatching { valueOf(raw.orEmpty()) }.getOrDefault(MEMBER)

        /** Parse from SimpleX's lowercase wire form. Returns null
         *  for the OBSERVER case (we don't model it) so callers can
         *  skip role updates for observers rather than coercing
         *  them to MEMBER. */
        fun fromSimplex(raw: String?): GroupRole? = when (raw?.lowercase()) {
            "owner" -> OWNER
            "admin" -> ADMIN
            "member" -> MEMBER
            else -> null
        }
    }
}

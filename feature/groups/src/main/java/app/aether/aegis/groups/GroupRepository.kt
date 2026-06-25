package app.aether.aegis.groups

import kotlinx.coroutines.flow.Flow

/**
 * Pure group-table CRUD layer (group-module isolation,
 * Phase 2). Lives in
 * `:feature:groups` so the entire data layer for groups
 * (entities + DAO + this orchestrator) can be reasoned about
 * without involving the safety code.
 *
 * What this class IS:
 *   - A thin wrapper around [GroupDao] for the standard
 *     create / observe / mutate operations on the `groups`
 *     and `group_members` tables
 *   - The home of every group-table read/write that doesn't
 *     also touch a non-group table
 *
 * What this class IS NOT:
 *   - A general-purpose data layer. The cross-table operations
 *     (e.g. inserting a GROUP_SYSTEM row into the shared
 *     `messages` table) stay on the app-side `Repository`
 *     because `MessageDao` lives in :app and the boundary
 *     would otherwise demand a cascade of message-domain
 *     types moving into :feature:groups.
 *
 * The app-side `Repository` retains delegation methods with
 * the same names + signatures so existing call sites (UI,
 * SimpleXTransport) keep working unchanged. The structural
 * win is that the implementation now lives inside the module.
 */
class GroupRepository(
    private val dao: GroupDao,
) {

    fun observeGroups(): Flow<List<GroupEntity>> = dao.observeGroups()

    suspend fun getGroup(id: String): GroupEntity? = dao.get(id)

    suspend fun getGroupBySimplexId(simplexGroupId: Long): GroupEntity? =
        dao.getBySimplexId(simplexGroupId)

    fun observeGroupMembers(groupId: String): Flow<List<String>> =
        dao.observeMemberKeys(groupId)

    suspend fun groupMemberKeys(groupId: String): List<String> =
        dao.memberKeys(groupId)

    /** Full row reads for the resolver (KnownPeer → cached
     *  displayName → "member: <hex>" fallback chain in
     *  GroupMembersScreen / GroupChatScreen header). The bare
     *  [observeGroupMembers] above returns just the pubkey set —
     *  fan-out paths only need that; this one returns profile
     *  data the UI consumes. */
    fun observeGroupMemberRows(groupId: String): Flow<List<GroupMemberEntity>> =
        dao.observeMembers(groupId)

    suspend fun groupMemberRows(groupId: String): List<GroupMemberEntity> =
        dao.members(groupId)

    suspend fun createGroup(
        name: String,
        memberKeys: List<String>,
        simplexGroupId: Long? = null,
    ): GroupEntity {
        val now = System.currentTimeMillis()
        val group = GroupEntity(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            simplexGroupId = simplexGroupId,
            createdAt = now,
        )
        dao.upsertGroup(group)
        for (k in memberKeys) {
            dao.upsertMember(GroupMemberEntity(groupId = group.id, peerPubkey = k, joinedAt = now))
        }
        return group
    }

    suspend fun setGroupSimplexId(groupId: String, simplexGroupId: Long) {
        dao.get(groupId)?.let { dao.upsertGroup(it.copy(simplexGroupId = simplexGroupId)) }
    }

    /** Direct passthrough so callers (SimpleXTransport.renameGroup,
     *  decoy fixtures, etc.) can write back a modified row
     *  without having to also pull the DAO through. */
    suspend fun upsertGroup(group: GroupEntity) = dao.upsertGroup(group)

    suspend fun addGroupMember(groupId: String, peerPubkey: String) {
        dao.upsertMember(GroupMemberEntity(groupId, peerPubkey, System.currentTimeMillis()))
    }

    /** Cache the sender's announced profile name from an inbound
     *  group message. */
    suspend fun cacheGroupMemberDisplayName(
        groupId: String,
        peerPubkey: String,
        displayName: String,
    ) {
        dao.updateMemberDisplayName(
            groupId = groupId,
            pubkey = peerPubkey,
            displayName = displayName,
            seenAt = System.currentTimeMillis(),
        )
    }

    /** Set/clear the "our membership is awaiting admin approval" flag
     *  (SimpleX knocking). No-op if the value is unchanged so we don't
     *  churn the row on every inbound group event. */
    suspend fun setGroupMembershipPending(groupId: String, pending: Boolean) {
        dao.get(groupId)?.let { row ->
            if (row.membershipPending != pending) {
                dao.upsertGroup(row.copy(membershipPending = pending))
            }
        }
    }

    /** Flip a group's active flag — the per-group toggle. */
    suspend fun setGroupEnabled(groupId: String, enabled: Boolean) {
        dao.get(groupId)?.let { row ->
            dao.upsertGroup(row.copy(enabled = enabled))
        }
    }

    /** Configure or clear the per-group auto-disable timer. */
    suspend fun setGroupAutoDisableMinutes(groupId: String, minutes: Int?) {
        dao.get(groupId)?.let { row ->
            dao.upsertGroup(row.copy(autoDisableMinutes = minutes?.coerceAtLeast(1)))
        }
    }

    /** Cache the group's shared avatar file path (Part 1). Set both
     *  when the local admin picks one and when an inbound
     *  groupProfile carries an image. Pass null to clear. No-op if
     *  the row doesn't exist yet. */
    suspend fun setGroupAvatarPath(groupId: String, avatarPath: String?) {
        dao.get(groupId)?.let { row ->
            dao.upsertGroup(row.copy(avatarPath = avatarPath))
        }
    }

    /** Cache the group description (Part 2), capped at 280 chars to
     *  match the edit UI and keep the broadcast small. Blank → null. */
    suspend fun setGroupDescription(groupId: String, description: String?) {
        dao.get(groupId)?.let { row ->
            val cleaned = description?.trim()?.take(280)?.takeIf { it.isNotEmpty() }
            dao.upsertGroup(row.copy(description = cleaned))
        }
    }

    /** Designate (or clear, with null) the group's README message
     *  pointer (Part 3). The pin state of the message itself is
     *  managed separately by the caller. */
    suspend fun setGroupReadme(groupId: String, messageId: String?) {
        dao.get(groupId)?.let { row ->
            dao.upsertGroup(row.copy(readmeMessageId = messageId))
        }
    }

    /** Update a group member's stored role. */
    suspend fun setGroupMemberRole(
        groupId: String,
        peerPubkey: String,
        role: GroupRole,
    ) {
        dao.updateMemberRole(
            groupId = groupId,
            pubkey = peerPubkey,
            role = role.name,
        )
    }

    /** Cache the SimpleX-side numeric memberId on the local row. */
    suspend fun setGroupMemberSimplexId(
        groupId: String,
        peerPubkey: String,
        simplexMemberId: Long,
    ) {
        dao.updateMemberSimplexId(
            groupId = groupId,
            pubkey = peerPubkey,
            simplexMemberId = simplexMemberId,
        )
    }

    suspend fun removeGroupMember(groupId: String, peerPubkey: String) =
        dao.removeMember(groupId, peerPubkey)

    suspend fun deleteGroup(groupId: String) {
        dao.deleteAllMembers(groupId)
        dao.deleteGroup(groupId)
    }
}

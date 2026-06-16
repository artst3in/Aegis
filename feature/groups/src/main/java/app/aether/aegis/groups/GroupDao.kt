package app.aether.aegis.groups

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: GroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: GroupMemberEntity)

    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE id = :groupId")
    suspend fun get(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE simplexGroupId = :simplexGroupId")
    suspend fun getBySimplexId(simplexGroupId: Long): GroupEntity?

    @Query("SELECT peerPubkey FROM group_members WHERE groupId = :groupId")
    suspend fun memberKeys(groupId: String): List<String>

    @Query("SELECT peerPubkey FROM group_members WHERE groupId = :groupId")
    fun observeMemberKeys(groupId: String): Flow<List<String>>

    /** Full member rows for resolver use (displayName, joinedAt).
     *  observeMemberKeys returns just the pubkey set, which is what
     *  the fan-out paths need; this returns the cached profile data
     *  the GroupMembersScreen / GroupChatScreen header use to render
     *  names. */
    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun members(groupId: String): List<GroupMemberEntity>

    /** Refresh the cached [displayName] for a member after seeing
     *  their announced profile name on an inbound message or
     *  MEMBER_PROFILE_UPDATE event. Updates [displayNameSeenAt] to
     *  the current time so the UI can tag the cache as stale after
     *  ~30 days. */
    @Query(
        "UPDATE group_members SET displayName = :displayName, " +
            "displayNameSeenAt = :seenAt " +
            "WHERE groupId = :groupId AND peerPubkey = :pubkey",
    )
    suspend fun updateMemberDisplayName(
        groupId: String,
        pubkey: String,
        displayName: String,
        seenAt: Long,
    )

    /** Set a member's role (OWNER / ADMIN / MEMBER). Called both by
     *  the SimpleX role-read path (recordMember capturing memberRole
     *  from the wire) and the long-press bottom sheet's promote /
     *  demote actions. */
    @Query(
        "UPDATE group_members SET role = :role " +
            "WHERE groupId = :groupId AND peerPubkey = :pubkey",
    )
    suspend fun updateMemberRole(
        groupId: String,
        pubkey: String,
        role: String,
    )

    /** Stash the SimpleX-side numeric `groupMemberId` against a
     *  member row. The peerPubkey primary key carries the
     *  cryptographic identity; this carries the routing id used
     *  by the upstream `/_remove` and `/_member_role` commands. */
    @Query(
        "UPDATE group_members SET simplexMemberId = :simplexMemberId " +
            "WHERE groupId = :groupId AND peerPubkey = :pubkey",
    )
    suspend fun updateMemberSimplexId(
        groupId: String,
        pubkey: String,
        simplexMemberId: Long,
    )

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND peerPubkey = :pubkey")
    suspend fun removeMember(groupId: String, pubkey: String)

    @Query("DELETE FROM groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun deleteAllMembers(groupId: String)
}


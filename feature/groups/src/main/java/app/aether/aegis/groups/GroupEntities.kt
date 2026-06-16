package app.aether.aegis.groups

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Family-private group. Bridged 1:1 to a SimpleX group on the
 * Haskell core — `simplexGroupId` is whatever the core returned
 * after `/group <name>`.
 *
 * Lives in `:feature:groups` as part of the group-module
 * isolation work. The
 * `AegisDatabase` in `:app` references this class in its
 * `@Database(entities = …)` array via the one-way app→groups
 * dependency edge.
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** SimpleX-side numeric group ID (from the core's JSON
     *  response). Null until creation succeeds. */
    val simplexGroupId: Long?,
    val createdAt: Long,
    /** Original join URI we used to enter this group. Kept so
     *  the "reveal real identity" action can leave + rejoin with
     *  the opposite incognito flag without asking the user to
     *  re-paste the link. Null for groups created locally or
     *  joined before the link-capture shipped. */
    val joinLink: String? = null,
    /** True iff we joined this group with our real profile
     *  (incognito off). Default false because every fresh group
     *  join goes incognito as of 2026.05.603. Surfaced in
     *  GroupMembersScreen so the user knows which groups have
     *  their real name attached. */
    val realIdentity: Boolean = false,
    /** Per-group on/off toggle.
     *  When false, this group's SimpleX traffic is dropped at
     *  the transport boundary even if the group module as a
     *  whole is enabled — connection management inside the
     *  isolated module. Defaults to true so existing groups
     *  remain active on first migration. */
    val enabled: Boolean = true,
    /** Per-group auto-disable inactivity window in minutes. Null
     *  means no timer (stays on while module is on). Default
     *  null. For example: "Family group: no timer / Aegis
     *  Amsterdam: 30-minute timer after last visit." Resets on
     *  GroupChatScreen entry; fires by flipping [enabled] to
     *  false (the group's per-group toggle), not the module-wide
     *  switch. */
    val autoDisableMinutes: Int? = null,
    /** Absolute path to the group's shared avatar JPEG on local
     *  disk, or null for the generated-glyph fallback. The image is
     *  the *group's*
     *  (set by OWNER/ADMIN), not the member's — it does not reveal
     *  any individual identity. Stored as a file (not an inline
     *  BLOB) to reuse the Coil File(...) load path; the broadcast
     *  copy is EXIF-stripped + downscaled (256², JPEG 70) in the
     *  transport before it leaves the device. */
    val avatarPath: String? = null,
    /** Short "what this group is" string, ≤ 280 chars, or null.
     *  Shared with every member (not secret, not PIN-sealed — same
     *  class as the group
     *  name). Set by OWNER/ADMIN, cached from inbound groupProfile
     *  for everyone else. */
    val description: String? = null,
    /** MessageEntity.id of the message designated as this group's
     *  standing README/topic, or null. A pointer (not an
     *  isReadme flag on MessageEntity) because README-ness is group
     *  metadata and MessageEntity is cross-cut between 1:1 and
     *  groups in :app — keeping the pointer here keeps the group
     *  concern in the group module. One group → at most one README. */
    val readmeMessageId: String? = null,
    /** True while OUR membership in this group is awaiting an admin's
     *  approval — SimpleX's "knocking": the link is set to require
     *  acceptance, so on join the core puts us in member status
     *  `pending_approval` / `pending_review`. While pending we cannot
     *  post to the main group; messages have to go to the member-support
     *  ("knock the admins") scope, `#<sgid>(_support)`. sendToGroup
     *  consults this to pick the scope. Cleared the moment an inbound
     *  group event shows our membership is no longer pending (host
     *  approved us). Defaults false on migration of existing rows — they
     *  were all already-accepted members. */
    val membershipPending: Boolean = false,
)

/**
 * One row per (group, member). Composite primary key — the same
 * peer can be in many groups, but only once per group.
 */
@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "peerPubkey"],
)
data class GroupMemberEntity(
    val groupId: String,
    val peerPubkey: String,
    val joinedAt: Long,
    /** Cached profile name from the peer's announced SimpleX
     *  profile. The resolver in GroupMembersScreen /
     *  GroupChatScreen prefers
     *  KnownPeerEntity.displayName when the pubkey is also paired
     *  1:1, falls back to this cache when only the group has seen
     *  them, and finally to `"member: <8-char hex>"` when neither
     *  source has a name yet. Nullable on first migration;
     *  populated the first time we receive an inbound group
     *  message or MEMBER_JOIN / MEMBER_PROFILE_UPDATE event
     *  carrying the sender's profile. */
    val displayName: String? = null,
    /** Millis epoch of the last time [displayName] was refreshed
     *  from inbound traffic. UI shows a "·" dim suffix when the
     *  cache is older than ~30 days so the user knows the name
     *  may be stale (peer may have renamed themselves since). */
    val displayNameSeenAt: Long? = null,
    /** Member's role in the group — one of OWNER / ADMIN / MEMBER
     *  (with future room for OBSERVER). Defaults to MEMBER
     *  on first migration of existing rows; updated as SimpleX
     *  group events carry role information (recordMember reads
     *  `memberRole` off the SimpleX member JSON). Permission
     *  gates in GroupMembersScreen consult this. */
    val role: String = "MEMBER",
    /** SimpleX-side numeric `groupMemberId` — needed by the
     *  `/_remove #<sgid> <memberId>` and
     *  `/_member_role #<sgid> <memberId> <role>` upstream
     *  commands used by the long-press bottom sheet. The
     *  peerPubkey primary key carries the cryptographic
     *  identity; this one carries the routing id. Null on first
     *  migration of existing rows; populated as recordMember
     *  sees groupMemberId fields on inbound SimpleX events. */
    val simplexMemberId: Long? = null,
)

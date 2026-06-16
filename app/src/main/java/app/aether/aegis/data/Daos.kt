package app.aether.aegis.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.aether.aegis.groups.GroupEntity
import app.aether.aegis.groups.GroupMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE peerKey = :peerKey ORDER BY timestamp ASC")
    fun observeConversation(peerKey: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("UPDATE messages SET delivered = 1 WHERE id = :id")
    suspend fun markDelivered(id: String)

    @Query("UPDATE messages SET simplexItemId = :itemId WHERE id = :id")
    suspend fun setSimplexItemId(id: String, itemId: Long)

    /** Store the Aegis message DNA (epoch-nanos) on a local row. Set on send
     *  (the minted value) and on receive (parsed from the envelope), so both
     *  ends hold the identical value for cross-device receipt matching. */
    @Query("UPDATE messages SET messageDna = :dna WHERE id = :id")
    suspend fun setMessageDna(id: String, dna: Long)

    /** Update the transport label of a row (LOCAL→SIMPLEX on outbox drain). */
    @Query("UPDATE messages SET protocol = :protocol WHERE id = :id")
    suspend fun setProtocol(id: String, protocol: String)

    /** Mark a row failed by id (an outbox-queued send that exhausted retries).
     *  Keyed by row id, not itemId, because a never-sent queued row has none. */
    @Query("UPDATE messages SET status = 'error' WHERE id = :id")
    suspend fun markErrorById(id: String)

    /** Find a local message by its DNA — resolves a DNA-keyed edit / delete /
     *  reply-quote to the row it targets. */
    @Query("SELECT * FROM messages WHERE messageDna = :dna LIMIT 1")
    suspend fun findByDna(dna: Long): MessageEntity?

    /** Apply a DNA-keyed delete: remove the inbound row the peer addressed by
     *  DNA (their "delete for everyone"). outgoing=0 so a peer can only retract
     *  their OWN message, never one of ours. */
    @Query("DELETE FROM messages WHERE messageDna = :dna AND outgoing = 0")
    suspend fun deleteInboundByDna(dna: Long)

    /** Fetch a single row by its SimpleX itemId. Used by the
     *  sndFileComplete hook to identify which message a finished
     *  upload belongs to so the source file can be sealed at rest. */
    @Query("SELECT * FROM messages WHERE simplexItemId = :itemId LIMIT 1")
    suspend fun findBySimplexItemId(itemId: Long): MessageEntity?

    /** Inbound rows whose originating-transport mirror copy hasn't
     *  yet been purged — carry the chatRef needed for the retry.
     *  Drives the boot-time sweep. Bounded by the crash window
     *  since the last purge succeeded; typically empty on healthy
     *  operation. */
    @Query(
        "SELECT * FROM messages WHERE pendingPurgeChatRef IS NOT NULL " +
            "AND simplexItemId IS NOT NULL"
    )
    suspend fun pendingPurges(): List<MessageEntity>

    /** Clear the pending-purge marker after a successful
     *  [app.aether.aegis.transport.Transport.purgeOriginal]. Keyed on
     *  simplexItemId because the originating transport doesn't know
     *  our row id — only the SimpleX itemId. */
    @Query(
        "UPDATE messages SET pendingPurgeChatRef = NULL " +
            "WHERE simplexItemId = :itemId"
    )
    suspend fun clearPendingPurge(itemId: Long)

    @Query("UPDATE messages SET status = :status WHERE simplexItemId = :itemId")
    suspend fun setStatusByItemId(itemId: Long, status: String)

    /**
     * Monotonic status write — only advances, never regresses. SimpleX can
     * emit CIStatus events out of order (e.g. a late `sndSent` arriving
     * after `sndRcvd`), and the Aegis read-receipt path sets 'read'
     * independently of the SimpleX events; without a guard a stale event
     * clobbered a higher status, so a row that had reached ✓✓-read
     * flickered back to a single ✓. [newRank] is the caller-computed rank
     * of [status] (see Repository.statusRank); the row updates only when
     * that exceeds the row's current rank.
     */
    // Monotonic tick ladder shared by every CASE below and by
    // Repository.statusRank. Ranks:
    //   sending 0 · sent 1 · delivered 2 · sealed 3 · read 4 · error 5
    // 'delivered' (reached device, [aegis:delivered]) sits BELOW 'sealed'
    // (sealed at rest, [aegis:sealed]); 'error' is terminal and outranks all
    // so a late delivered/sealed/read can never paper over a failed send.
    @Query(
        "UPDATE messages SET status = :status " +
            "WHERE simplexItemId = :itemId AND :newRank > (CASE status " +
            "WHEN 'sending' THEN 0 WHEN 'sent' THEN 1 WHEN 'delivered' THEN 2 " +
            "WHEN 'sealed' THEN 3 WHEN 'read' THEN 4 WHEN 'error' THEN 5 ELSE 0 END)"
    )
    suspend fun setStatusByItemIdIfAdvances(itemId: Long, status: String, newRank: Int)

    /**
     * Bulk-mark every outgoing message to [peerKey] with a simplexItemId
     * ≤ [maxItemId] as 'read'. Driven by inbound read-receipt status
     * messages from the peer (see AegisApp.onInboundMessage).
     */
    @Query(
        "UPDATE messages SET status = 'read' " +
            "WHERE peerKey = :peerKey AND outgoing = 1 " +
            "AND simplexItemId IS NOT NULL AND simplexItemId <= :maxItemId " +
            // Legacy/vanilla rows only: a row that carries a messageDna is
            // matched by the cross-device-correct markReadUpToDna instead, so
            // the per-device itemId (which can mis-match across devices) never
            // touches it. messageDna IS NULL for every pre-DNA + vanilla row,
            // so this is a no-op change for the existing path.
            "AND messageDna IS NULL " +
            // Monotonic: advance to 'read' only from a lower rank. Won't
            // re-clobber an already-read row or override a terminal 'error'.
            "AND (CASE status WHEN 'sending' THEN 0 WHEN 'sent' THEN 1 " +
            "WHEN 'delivered' THEN 2 WHEN 'sealed' THEN 3 WHEN 'read' THEN 4 " +
            "WHEN 'error' THEN 5 ELSE 0 END) < 4"
    )
    suspend fun markReadUpTo(peerKey: String, maxItemId: Long)

    /**
     * Mark every outgoing message to [peerKey] with simplexItemId
     * ≤ [maxItemId] as 'delivered' (one BRIGHT tick). Driven by the
     * recipient's `[aegis:delivered:<id>]` receipt — "reached your device",
     * fired as soon as the message arrives, before the at-rest seal.
     * Monotonic: only advances from sending(0)/sent(1); never downgrades a
     * sealed(3)/read(4) row.
     */
    @Query(
        "UPDATE messages SET status = 'delivered' " +
            "WHERE peerKey = :peerKey AND outgoing = 1 " +
            "AND simplexItemId IS NOT NULL AND simplexItemId <= :maxItemId " +
            "AND messageDna IS NULL " +  // legacy/vanilla only — DNA rows use the DNA twin
            "AND (CASE status WHEN 'sending' THEN 0 WHEN 'sent' THEN 1 " +
            "WHEN 'delivered' THEN 2 WHEN 'sealed' THEN 3 WHEN 'read' THEN 4 " +
            "WHEN 'error' THEN 5 ELSE 0 END) < 2"
    )
    suspend fun markDeliveredUpTo(peerKey: String, maxItemId: Long)

    /**
     * Mark every outgoing message to [peerKey] with simplexItemId
     * ≤ [maxItemId] as 'sealed' (bright + dim ✓✓). Driven by the
     * recipient's `[aegis:sealed:<id>]` receipt — two ticks mean "sealed at
     * rest in the recipient's vault", NOT SimpleX transport delivery.
     * Monotonic: advances from sending(0)/sent(1)/delivered(2); never
     * downgrades a read(4) row.
     */
    @Query(
        "UPDATE messages SET status = 'sealed' " +
            "WHERE peerKey = :peerKey AND outgoing = 1 " +
            "AND simplexItemId IS NOT NULL AND simplexItemId <= :maxItemId " +
            "AND messageDna IS NULL " +  // legacy/vanilla only — DNA rows use the DNA twin
            "AND (CASE status WHEN 'sending' THEN 0 WHEN 'sent' THEN 1 " +
            "WHEN 'delivered' THEN 2 WHEN 'sealed' THEN 3 WHEN 'read' THEN 4 " +
            "WHEN 'error' THEN 5 ELSE 0 END) < 3"
    )
    suspend fun markSealedUpTo(peerKey: String, maxItemId: Long)

    /** Highest inbound simplexItemId for a peer — what the read receipt
     *  needs to advertise as "I've seen up to here". */
    @Query(
        "SELECT MAX(simplexItemId) FROM messages " +
            "WHERE peerKey = :peerKey AND outgoing = 0 AND simplexItemId IS NOT NULL"
    )
    suspend fun maxInboundItemId(peerKey: String): Long?

    // ---- DNA-keyed receipt markers (the cross-device read-tick fix) ----
    // Twins of the simplexItemId markers above, but matched on messageDna —
    // the sender-minted identity both ends share — instead of a per-device
    // itemId. The receiver echoes a DNA it received (= a value WE minted on
    // our outbound row), so "messageDna <= :maxDna" selects exactly our own
    // messages up to that point. Same monotonic rank guard. Used for Aegis
    // chat messages (which carry a DNA); vanilla/legacy rows keep the
    // itemId markers above.

    @Query(
        "UPDATE messages SET status = 'read', readAt = :at " +
            "WHERE peerKey = :peerKey AND outgoing = 1 " +
            "AND messageDna IS NOT NULL AND messageDna <= :maxDna " +
            "AND (CASE status WHEN 'sending' THEN 0 WHEN 'sent' THEN 1 " +
            "WHEN 'delivered' THEN 2 WHEN 'sealed' THEN 3 WHEN 'read' THEN 4 " +
            "WHEN 'error' THEN 5 ELSE 0 END) < 4"
    )
    suspend fun markReadUpToDna(peerKey: String, maxDna: Long, at: Long)

    @Query(
        "UPDATE messages SET status = 'delivered' " +
            "WHERE peerKey = :peerKey AND outgoing = 1 " +
            "AND messageDna IS NOT NULL AND messageDna <= :maxDna " +
            "AND (CASE status WHEN 'sending' THEN 0 WHEN 'sent' THEN 1 " +
            "WHEN 'delivered' THEN 2 WHEN 'sealed' THEN 3 WHEN 'read' THEN 4 " +
            "WHEN 'error' THEN 5 ELSE 0 END) < 2"
    )
    suspend fun markDeliveredUpToDna(peerKey: String, maxDna: Long)

    @Query(
        "UPDATE messages SET status = 'sealed' " +
            "WHERE peerKey = :peerKey AND outgoing = 1 " +
            "AND messageDna IS NOT NULL AND messageDna <= :maxDna " +
            "AND (CASE status WHEN 'sending' THEN 0 WHEN 'sent' THEN 1 " +
            "WHEN 'delivered' THEN 2 WHEN 'sealed' THEN 3 WHEN 'read' THEN 4 " +
            "WHEN 'error' THEN 5 ELSE 0 END) < 3"
    )
    suspend fun markSealedUpToDna(peerKey: String, maxDna: Long)

    // Exact-DNA twins (SPEC_LOSSY_LINK_RESILIENCE — "no watermarks; each
    // message confirmed by its EXACT DNA"). The up-to markers above infer that
    // everything below the watermark reached the same rung, which lies under
    // reorder / per-message seal failure; these touch ONLY the one message the
    // receipt names. Used by the fact-based reconciliation responses (and the
    // live delivered/sealed receipts, which are inherently per-message anyway).
    // Same monotonic ladder guard, so a receipt never lowers a higher rung.
    @Query(
        "UPDATE messages SET status = 'delivered', deliveredAt = :at " +
            "WHERE peerKey = :peerKey AND outgoing = 1 AND messageDna = :dna " +
            "AND (CASE status WHEN 'sending' THEN 0 WHEN 'sent' THEN 1 " +
            "WHEN 'delivered' THEN 2 WHEN 'sealed' THEN 3 WHEN 'read' THEN 4 " +
            "WHEN 'error' THEN 5 ELSE 0 END) < 2"
    )
    suspend fun markDeliveredByDna(peerKey: String, dna: Long, at: Long)

    @Query(
        "UPDATE messages SET status = 'sealed', sealedAt = :at " +
            "WHERE peerKey = :peerKey AND outgoing = 1 AND messageDna = :dna " +
            "AND (CASE status WHEN 'sending' THEN 0 WHEN 'sent' THEN 1 " +
            "WHEN 'delivered' THEN 2 WHEN 'sealed' THEN 3 WHEN 'read' THEN 4 " +
            "WHEN 'error' THEN 5 ELSE 0 END) < 3"
    )
    suspend fun markSealedByDna(peerKey: String, dna: Long, at: Long)

    /** Outbound messages to [peerKey] whose tick is stuck BELOW sealed and that
     *  are older than [olderThanMs] — the "dark or stuck" set the sender asks
     *  the receiver to reconcile. Excludes still-in-flight recent sends (age
     *  guard), terminal rungs (sealed/read), and errored rows. DNA-bearing only
     *  (vanilla rows use the itemId path). Newest first, capped by [limit] so a
     *  query stays small even after a long offline gap. */
    @Query(
        "SELECT messageDna FROM messages " +
            "WHERE peerKey = :peerKey AND outgoing = 1 AND messageDna IS NOT NULL " +
            "AND timestamp <= :olderThanMs " +
            "AND (CASE status WHEN 'sending' THEN 0 WHEN 'sent' THEN 1 " +
            "WHEN 'delivered' THEN 2 WHEN 'sealed' THEN 3 WHEN 'read' THEN 4 " +
            "WHEN 'error' THEN 5 ELSE 0 END) < 3 " +
            "ORDER BY messageDna DESC LIMIT :limit"
    )
    suspend fun stuckOutboundDnas(peerKey: String, olderThanMs: Long, limit: Int): List<Long>

    /** Highest inbound message DNA for a peer — what a DNA read receipt
     *  advertises as "I've seen up to here". Null when no inbound message
     *  from this peer carries a DNA (vanilla peer / legacy). */
    @Query(
        "SELECT MAX(messageDna) FROM messages " +
            "WHERE peerKey = :peerKey AND outgoing = 0 AND messageDna IS NOT NULL"
    )
    suspend fun maxInboundDna(peerKey: String): Long?

    @Query("UPDATE messages SET reactionsJson = :json WHERE simplexItemId = :itemId")
    suspend fun setReactionsByItemId(itemId: Long, json: String?)

    @Query("SELECT reactionsJson FROM messages WHERE simplexItemId = :itemId")
    suspend fun reactionsByItemId(itemId: Long): String?

    @Query("DELETE FROM messages WHERE peerKey = :peerKey")
    suspend fun clearConversation(peerKey: String)

    @Query(
        "SELECT * FROM messages WHERE body LIKE '%' || :query || '%' " +
            "ORDER BY timestamp DESC LIMIT 200"
    )
    suspend fun search(query: String): List<MessageEntity>

    /**
     * Latest message per peer — used by the chat-list contact rows to
     * show "last message + timestamp" beside each contact. JOIN-style
     * query so we get the full row, not just metadata.
     */
    @Query(
        "SELECT m.* FROM messages m " +
            "INNER JOIN (SELECT peerKey, MAX(timestamp) AS maxTs FROM messages GROUP BY peerKey) latest " +
            "ON m.peerKey = latest.peerKey AND m.timestamp = latest.maxTs"
    )
    fun observeLastMessagePerPeer(): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String)

    /** One-shot cleanup of control-class rows that landed in chat
     *  before the outbound + inbound filters were both in place.
     *  Called from AegisApp.onCreate so the fix is applied on update. */
    @Query("DELETE FROM messages WHERE type IN ('LOCATION', 'STATUS', 'SOS', 'STORY')")
    suspend fun purgeControlMessages(): Int

    @Query("UPDATE messages SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query(
        "UPDATE messages SET body = :body, sealedBody = :sealed, edited = 1 " +
            "WHERE id = :id"
    )
    suspend fun setEditedBody(id: String, body: String, sealed: ByteArray?)

    /** All rows that carry a PIN-sealed body. Used by:
     *   - the in-memory search fallback (SQL LIKE can't see inside
     *     the ciphertext, so unlocked sessions walk these rows
     *     in-memory and filter by decrypted body)
     *   - the one-time legacy-plaintext seal sweep that runs the
     *     first time a user unlocks after the seal feature shipped */
    @Query("SELECT * FROM messages WHERE sealedBody IS NOT NULL")
    suspend fun allSealed(): List<MessageEntity>

    /** Rows still holding a PLAINTEXT body that was never sealed —
     *  pre-enrolment history. Drives Repository.sealLegacyPlaintext().
     *  Returns empty once the sweep has run, so steady-state cost is one
     *  indexed-empty scan. */
    @Query("SELECT * FROM messages WHERE sealedBody IS NULL AND body != ''")
    suspend fun unsealedWithBody(): List<MessageEntity>

    /** Every non-null attachmentPath across messages — used by the
     *  app_files/ orphan-cleanup pass to decide which on-disk files
     *  are safe to delete (anything not referenced). Includes sealed
     *  rows too because pre-Phase-1 sealed paths might still point
     *  into the legacy app_files/ tree on a partial migration. */
    @Query("SELECT attachmentPath FROM messages WHERE attachmentPath IS NOT NULL")
    suspend fun allMessageAttachmentPaths(): List<String>

    /** Rows with attachments + their peerKey — joined against
     *  KnownPeers in the Repository to give the Diagnostics inspector
     *  a "this file is in the chat with X" annotation. */
    @Query("SELECT * FROM messages WHERE attachmentPath IS NOT NULL")
    suspend fun allAttachmentsWithPeer(): List<MessageEntity>

    /** Rows whose attachment path matches a glob-style [pattern]
     *  (SQL LIKE). Used by the sos-evidence video purge — pattern
     *  `%/video_%` matches the timestamped camera chunks then the
     *  caller tightens the match in Kotlin land. */
    @Query("SELECT * FROM messages WHERE attachmentPath LIKE :pattern")
    suspend fun matchByAttachmentPath(pattern: String): List<MessageEntity>

    /** Bulk-delete by attachment-path pattern. Returns the row count
     *  actually deleted. */
    @Query("DELETE FROM messages WHERE attachmentPath LIKE :pattern")
    suspend fun deleteByAttachmentPathPattern(pattern: String): Int

    /** Re-pair migration: when an existing contact's SimpleX identity
     *  changes (peer wiped + re-installed, lost their phone), every
     *  messages row that referenced the old peerKey needs to point at
     *  the new one so the chat history reattaches to the rebuilt
     *  contact. fromKey + toKey + peerKey all migrate in one pass. */
    @Query(
        "UPDATE messages SET peerKey = :newKey, " +
            "fromKey = CASE WHEN fromKey = :oldKey THEN :newKey ELSE fromKey END, " +
            "toKey   = CASE WHEN toKey   = :oldKey THEN :newKey ELSE toKey   END " +
            "WHERE peerKey = :oldKey"
    )
    suspend fun rewritePeerKey(oldKey: String, newKey: String): Int

    /** All rows that already carry a PIN-sealed DEK (attachment is
     *  encrypted). Used by the PIN-change re-seal flow to walk every
     *  attachment, unseal its DEK with the OLD priv, and re-seal
     *  under the NEW pubkey. The AES-GCM file on disk is unchanged
     *  — only the DEK wrapper rotates. */
    @Query("SELECT * FROM messages WHERE sealedDek IS NOT NULL")
    suspend fun allSealedAttachments(): List<MessageEntity>

    /** All pinned messages for a peer, newest first. Chat UI uses
     *  this as a sticky banner — typically only the latest is shown
     *  but the full list lives behind a tap. */
    @Query(
        "SELECT * FROM messages WHERE peerKey = :peerKey AND pinned = 1 " +
            "ORDER BY timestamp DESC"
    )
    fun observePinned(peerKey: String): Flow<List<MessageEntity>>
}

@Dao
interface MemberStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(status: MemberStatusEntity)

    @Query("SELECT * FROM member_status WHERE peerKey = :peerKey")
    suspend fun latest(peerKey: String): MemberStatusEntity?

    @Query("SELECT * FROM member_status")
    fun observeAll(): Flow<List<MemberStatusEntity>>

    /** Re-pair migration. peerKey is the primary key so we have to
     *  go through DELETE + INSERT semantics — caller reads the old
     *  row, inserts a copy under newKey, then deletes the old row. */
    @Query("DELETE FROM member_status WHERE peerKey = :peerKey")
    suspend fun deleteByPeerKey(peerKey: String): Int

    @Query("SELECT * FROM member_status WHERE peerKey = :peerKey")
    fun observe(peerKey: String): Flow<MemberStatusEntity?>

    @Query("DELETE FROM member_status WHERE peerKey = :peerKey")
    suspend fun deleteFor(peerKey: String)

    /** Rows already sealed under the current PIN pubkey. Used by the
     *  PIN-change re-seal flow to walk every status row and rotate
     *  the seal over to the NEW pub. */
    @Query("SELECT * FROM member_status WHERE sealedPayload IS NOT NULL")
    suspend fun allSealed(): List<MemberStatusEntity>

    /** Status rows whose sensitive payload is still cleartext (never
     *  sealed). Drives the Repository.sealLegacyPlaintext() sweep. */
    @Query("SELECT * FROM member_status WHERE sealedPayload IS NULL")
    suspend fun unsealedStatuses(): List<MemberStatusEntity>
}

@Dao
interface KnownPeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: KnownPeerEntity)

    @Query("SELECT * FROM known_peers ORDER BY pinned DESC, addedAt DESC")
    fun observeAll(): Flow<List<KnownPeerEntity>>

    @Query("UPDATE known_peers SET pinned = :pinned WHERE publicKey = :publicKey")
    suspend fun setPinned(publicKey: String, pinned: Boolean)

    @Query("UPDATE known_peers SET muted = :muted WHERE publicKey = :publicKey")
    suspend fun setMuted(publicKey: String, muted: Boolean)

    @Query("UPDATE known_peers SET disappearingTtl = :ttl WHERE publicKey = :publicKey")
    suspend fun setDisappearingTtl(publicKey: String, ttl: Long?)

    @Query("UPDATE known_peers SET verified = :verified WHERE publicKey = :publicKey")
    suspend fun setVerified(publicKey: String, verified: Boolean)

    @Query("UPDATE known_peers SET trustTier = :tier WHERE publicKey = :publicKey")
    suspend fun setTrustTier(publicKey: String, tier: String)

    @Query("UPDATE known_peers SET remoteAccessEnabled = :enabled WHERE publicKey = :publicKey")
    suspend fun setRemoteAccessEnabled(publicKey: String, enabled: Boolean)

    @Query("UPDATE known_peers SET blocked = :blocked WHERE publicKey = :publicKey")
    suspend fun setBlocked(publicKey: String, blocked: Boolean)

    @Query("UPDATE known_peers SET firstSosShownAt = :ts WHERE publicKey = :publicKey")
    suspend fun setFirstSOSShownAt(publicKey: String, ts: Long?)

    @Query("UPDATE known_peers SET isAegis = 1 WHERE publicKey = :publicKey AND isAegis = 0")
    suspend fun markIsAegis(publicKey: String)

    @Query("UPDATE known_peers SET peerReportedTier = :tier WHERE publicKey = :publicKey")
    suspend fun setPeerReportedTier(publicKey: String, tier: String?)

    @Query("UPDATE known_peers SET peerReportedCrownStyle = :style WHERE publicKey = :publicKey")
    suspend fun setPeerReportedCrownStyle(publicKey: String, style: Int?)

    @Query("UPDATE known_peers SET peerCapabilities = :caps WHERE publicKey = :publicKey")
    suspend fun setPeerCapabilities(publicKey: String, caps: String?)

    // ---- Command-auth control channel ----
    // setControlPubKey writes the hello-bootstrap marker.

    @Query("UPDATE known_peers SET controlPubKey = :pub WHERE publicKey = :publicKey")
    suspend fun setControlPubKey(publicKey: String, pub: String?)

    @Query("UPDATE known_peers SET notificationSoundUri = :uri WHERE publicKey = :publicKey")
    suspend fun setNotificationSoundUri(publicKey: String, uri: String?)

    @Query("UPDATE known_peers SET folder = :folder WHERE publicKey = :publicKey")
    suspend fun setFolder(publicKey: String, folder: String?)

    @Query("SELECT DISTINCT folder FROM known_peers WHERE folder IS NOT NULL AND folder != '' ORDER BY folder ASC")
    fun observeFolders(): Flow<List<String>>

    /** Routine-data recipients — Trusted only, never blocked. Drives
     *  every recurring location / status / sensor broadcast. */
    @Query("SELECT * FROM known_peers WHERE trustTier = 'TRUSTED' AND blocked = 0")
    suspend fun trustedTargets(): List<KnownPeerEntity>

    /** SOS recipients — Trusted ∪ Emergency, never blocked. Spec:
     *  recipients = (Trusted) ∪ (Emergency Contacts). Untrusted never
     *  appears here by construction. */
    @Query(
        "SELECT * FROM known_peers " +
            "WHERE trustTier IN ('TRUSTED', 'EMERGENCY') AND blocked = 0"
    )
    suspend fun sosTargets(): List<KnownPeerEntity>

    /**
     * Live count of presence-tier (TRUSTED) contacts — Phase 1
     * runtime gating. Drives whether the
     * location/presence module runs at all: zero Trusted contacts ⇒
     * the GPS listener is never registered (no orphaned attack
     * surface, no battery drain). A Flow so the gate reacts live as
     * the user adds/removes/retiers contacts.
     */
    @Query("SELECT COUNT(*) FROM known_peers WHERE trustTier = 'TRUSTED' AND blocked = 0")
    fun trustedCountFlow(): Flow<Int>

    /**
     * Live count of sos-tier (TRUSTED ∪ EMERGENCY) contacts —
     * Phase 1. Drives whether the sos
     * module runs: with nobody to alert, the power-button sos
     * receiver is not registered.
     */
    @Query(
        "SELECT COUNT(*) FROM known_peers " +
            "WHERE trustTier IN ('TRUSTED', 'EMERGENCY') AND blocked = 0"
    )
    fun sosCountFlow(): Flow<Int>

    @Query("SELECT * FROM known_peers")
    suspend fun all(): List<KnownPeerEntity>

    @Query("SELECT * FROM known_peers WHERE publicKey = :publicKey")
    suspend fun get(publicKey: String): KnownPeerEntity?

    @Query("UPDATE known_peers SET lastSeenAt = :ts WHERE publicKey = :publicKey")
    suspend fun touch(publicKey: String, ts: Long)

    @Query("UPDATE known_peers SET displayName = :displayName WHERE publicKey = :publicKey")
    suspend fun rename(publicKey: String, displayName: String)

    /**
     * Refresh the peer's SimpleX-announced profile fields. COALESCE so
     * a null input PRESERVES the existing column value instead of
     * clobbering it — SimpleX emits `contactUpdated` events for any
     * profile field change (name, bio, OR avatar) and only includes
     * the fields actually present in the payload, so a name-only
     * update was previously nulling the cached avatar on every
     * subsequent fan-out. User-reported 2026.05.649: "earlier it was
     * pulling avatar and now it doesn't."
     *
     * To explicitly clear a field, write directly via [rename] or a
     * future dedicated mutator — `null` here means "no change."
     */
    @Query(
        "UPDATE known_peers SET " +
            "announcedName = COALESCE(:name, announcedName), " +
            "announcedBio = COALESCE(:bio, announcedBio), " +
            "announcedAvatarPath = COALESCE(:avatarPath, announcedAvatarPath) " +
            "WHERE publicKey = :publicKey"
    )
    suspend fun updateAnnouncedProfile(
        publicKey: String,
        name: String?,
        bio: String?,
        avatarPath: String?,
    )

    @Query("DELETE FROM known_peers WHERE publicKey = :publicKey")
    suspend fun delete(publicKey: String)
}

@Dao
interface SecureNoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: SecureNoteEntity)

    @Query("SELECT * FROM secure_notes WHERE id = :id")
    suspend fun get(id: String): SecureNoteEntity?

    @Query("SELECT * FROM secure_notes ORDER BY pinned DESC, createdAt DESC")
    fun observeAll(): Flow<List<SecureNoteEntity>>

    @Query("SELECT * FROM secure_notes WHERE body LIKE '%' || :query || '%' ORDER BY pinned DESC, createdAt DESC")
    fun search(query: String): Flow<List<SecureNoteEntity>>

    @Query("SELECT * FROM secure_notes WHERE keySlot = :slot ORDER BY pinned DESC, createdAt DESC")
    fun observeBySlot(slot: String): Flow<List<SecureNoteEntity>>

    @Query("SELECT * FROM secure_notes WHERE keySlot = :slot AND body LIKE '%' || :query || '%' ORDER BY pinned DESC, createdAt DESC")
    fun searchBySlot(slot: String, query: String): Flow<List<SecureNoteEntity>>

    @Query("SELECT DISTINCT folder FROM secure_notes WHERE keySlot = :slot AND folder IS NOT NULL AND folder != '' ORDER BY folder ASC")
    fun observeFoldersForSlot(slot: String): Flow<List<String>>

    @Query("UPDATE secure_notes SET folder = :folder WHERE id = :id")
    suspend fun setFolder(id: String, folder: String?)

    @Query("DELETE FROM secure_notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM secure_notes")
    suspend fun wipeAll()

    @Query("SELECT attachmentPath FROM secure_notes WHERE attachmentPath IS NOT NULL")
    suspend fun allAttachmentPaths(): List<String>

    @Query("SELECT * FROM secure_notes WHERE keySlot = :slot")
    suspend fun allBySlotRaw(slot: String): List<SecureNoteEntity>

    @Query("UPDATE secure_notes SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)
}

// GroupDao moved to :feature:groups for module isolation
// (Phase 2). AegisDatabase
// references it via the feature module's package.

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entry: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT :limit")
    suspend fun ready(now: Long, limit: Int = 20): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun remove(id: String)

    @Query("DELETE FROM outbox WHERE toKey = :toKey")
    suspend fun removeForPeer(toKey: String)

    @Query("DELETE FROM outbox")
    suspend fun clearAll()

    /** Entries that have exhausted their retry budget — fetched before the
     *  purge so a linked chat row can be flipped to 'error' (otherwise an
     *  undeliverable queued message would sit on the hex clock forever). */
    @Query("SELECT * FROM outbox WHERE attempts >= :maxAttempts")
    suspend fun exhausted(maxAttempts: Int): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE attempts >= :maxAttempts")
    suspend fun purgeExhausted(maxAttempts: Int)

    @Query("UPDATE outbox SET attempts = attempts + 1, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    suspend fun reschedule(id: String, nextAttemptAt: Long)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observePending(): Flow<Int>
}

@Dao
interface ScheduledMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entry: ScheduledMessageEntity)

    /** All not-yet-ready messages, soonest first — composer screen
     *  uses this to show pending sends. */
    @Query("SELECT * FROM scheduled_messages WHERE scheduledFor > :now ORDER BY scheduledFor ASC")
    fun observePending(now: Long): Flow<List<ScheduledMessageEntity>>

    /** Anything overdue — the worker grabs these on each tick. */
    @Query("SELECT * FROM scheduled_messages WHERE scheduledFor <= :now ORDER BY scheduledFor ASC LIMIT 50")
    suspend fun ready(now: Long): List<ScheduledMessageEntity>

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun delete(id: String)

    /** Re-pair migration: any pending scheduled message targeted at
     *  the old peerKey rewires to the new identity. */
    @Query("UPDATE scheduled_messages SET toKey = :newKey WHERE toKey = :oldKey")
    suspend fun rewriteToKey(oldKey: String, newKey: String): Int
}

@Dao
interface StoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(story: StoryEntity)

    /** Active stories only — anything older than `cutoff` is hidden until
     *  the periodic sweep deletes it. Ordered newest first. */
    @Query("SELECT * FROM stories WHERE createdAt >= :cutoff ORDER BY createdAt DESC")
    fun observeActive(cutoff: Long): Flow<List<StoryEntity>>

    @Query("SELECT * FROM stories WHERE id = :id")
    suspend fun get(id: String): StoryEntity?

    @Query("UPDATE stories SET viewed = 1 WHERE id = :id")
    suspend fun markViewed(id: String)

    @Query("DELETE FROM stories WHERE createdAt < :cutoff")
    suspend fun purgeExpired(cutoff: Long): Int

    @Query("DELETE FROM stories WHERE id = :id")
    suspend fun delete(id: String)

    /** Every non-null attachmentPath — used by the app_files/ orphan
     *  cleanup pass alongside the messages and secure_notes equivalents. */
    @Query("SELECT attachmentPath FROM stories WHERE attachmentPath IS NOT NULL")
    suspend fun allStoryAttachmentPaths(): List<String>

    /** Re-pair migration: stories authored by the old peerKey rewire
     *  to point at the new identity. */
    @Query("UPDATE stories SET authorKey = :newKey WHERE authorKey = :oldKey")
    suspend fun rewriteAuthorKey(oldKey: String, newKey: String): Int
}

@Dao
interface NetworkHistoryDao {
    /**
     * Add deltas to the current hour's bucket. Atomic UPSERT — if no
     * row exists for that hour we insert with the deltas as the
     * initial values, otherwise we add the deltas to the existing
     * row.
     */
    @Query(
        """
        INSERT INTO network_history(hourEpochMs, rxBytes, txBytes)
        VALUES (:hourEpochMs, :rxDelta, :txDelta)
        ON CONFLICT(hourEpochMs) DO UPDATE SET
            rxBytes = rxBytes + :rxDelta,
            txBytes = txBytes + :txDelta
        """
    )
    suspend fun addDelta(hourEpochMs: Long, rxDelta: Long, txDelta: Long)

    /** Hourly rows from [sinceMs] forward, oldest first. */
    @Query("SELECT * FROM network_history WHERE hourEpochMs >= :sinceMs ORDER BY hourEpochMs ASC")
    fun observeFrom(sinceMs: Long): Flow<List<NetworkHistoryEntity>>

    /** Drop rows older than [cutoffMs]. Called by the sampler to
     *  keep the table bounded — never more than ~720 rows kept
     *  (30 d × 24 h). */
    @Query("DELETE FROM network_history WHERE hourEpochMs < :cutoffMs")
    suspend fun purgeOlder(cutoffMs: Long)
}

/** Outstanding 1:1 invitation links. */
@Dao
interface PendingInvitationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(inv: PendingInvitationEntity)

    /** Newest first — the list shows most-recently-created at top. */
    @Query("SELECT * FROM pending_invitations ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PendingInvitationEntity>>

    /** Snapshot for the auto-expire worker. */
    @Query("SELECT * FROM pending_invitations")
    suspend fun all(): List<PendingInvitationEntity>

    @Query("DELETE FROM pending_invitations WHERE connId = :connId")
    suspend fun delete(connId: Long)
}

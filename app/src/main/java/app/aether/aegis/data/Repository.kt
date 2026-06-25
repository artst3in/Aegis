package app.aether.aegis.data

import app.aether.aegis.core.Message
import app.aether.aegis.core.MessageType
import app.aether.aegis.core.Protocol
import app.aether.aegis.groups.GroupEntity
import app.aether.aegis.groups.GroupMemberEntity
import app.aether.aegis.groups.GroupRole
import app.aether.aegis.groups.GroupSystemPayload
import app.aether.aegis.lock.ChatAttachmentSeal
import app.aether.aegis.lock.SealingPolicy
import app.aether.aegis.profile.ProfileRoot
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Clean API over the DAOs. Translates between app.aether.aegis.core domain types
 * and DB entities, so the rest of the app never touches Room directly.
 */
class Repository(
    private val messages: MessageDao,
    private val statuses: MemberStatusDao,
    private val outbox: OutboxDao,
    private val knownPeers: KnownPeerDao,
    private val secureNotes: SecureNoteDao,
    // Pure group-table CRUD lives in :feature:groups for module
    // isolation (Phase 2). The methods
    // on this Repository that touch ONLY the groups +
    // group_members tables delegate here. Cross-table methods
    // (recordGroupSystem, etc.) stay in this file because they
    // hit the messages table whose DAO lives in :app.
    private val groupRepo: app.aether.aegis.groups.GroupRepository,
    private val stories: StoryDao,
    private val scheduled: ScheduledMessageDao,
    private val networkHistory: NetworkHistoryDao,
    private val pendingInvitations: PendingInvitationDao,
    private val selfKey: String,
    private val sealing: SealingPolicy = SealingPolicy.NOOP,
    /** Destination for sealed chat-attachment .enc files. Null in
     *  tests / pre-profile bootstrap; attachment sealing no-ops in
     *  that case. */
    private val chatEncAttachmentsDir: java.io.File? = null,
    /** Handle on the Room database for transactional bulk operations
     *  (e.g. PIN-change re-seal). Null in tests; the affected method
     *  falls back to non-transactional row-by-row writes. */
    private val db: AegisDatabase? = null,
) {

    // ---------------- Network usage history ----------------

    /**
     * Add deltas to the current hour's network bucket. Floor [nowMs]
     * to the start of the hour so multiple samples within the same
     * hour merge into one row.
     */
    suspend fun recordNetworkSample(rxDelta: Long, txDelta: Long, nowMs: Long = System.currentTimeMillis()) {
        if (rxDelta <= 0 && txDelta <= 0) return
        val hour = (nowMs / 3_600_000L) * 3_600_000L
        networkHistory.addDelta(hour, rxDelta.coerceAtLeast(0), txDelta.coerceAtLeast(0))
    }

    /** Hourly buckets from [sinceMs] forward. Caller picks the window
     *  (24h / 7d / 30d) by subtracting from now. */
    fun observeNetworkHistory(sinceMs: Long): Flow<List<NetworkHistoryEntity>> =
        networkHistory.observeFrom(sinceMs)

    /** Drop history older than 30 days. The sampler calls this on
     *  each tick so the table never grows past ~720 rows. */
    suspend fun purgeOldNetworkHistory() {
        networkHistory.purgeOlder(System.currentTimeMillis() - 30L * 24 * 3_600_000L)
    }

    // ---------------- Scheduled messages ----------------

    suspend fun scheduleMessage(toKey: String, body: String, scheduledFor: Long) {
        scheduled.enqueue(
            ScheduledMessageEntity(
                id = java.util.UUID.randomUUID().toString(),
                toKey = toKey,
                body = body,
                scheduledFor = scheduledFor,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    fun observePendingScheduled(): Flow<List<ScheduledMessageEntity>> =
        scheduled.observePending(System.currentTimeMillis())

    suspend fun readyScheduled(): List<ScheduledMessageEntity> =
        scheduled.ready(System.currentTimeMillis())

    suspend fun deleteScheduled(id: String) = scheduled.delete(id)

    // ---------------- Stories ----------------

    /** Stories from the last 24 h, ordered newest first. */
    fun observeActiveStories(): Flow<List<StoryEntity>> =
        stories.observeActive(System.currentTimeMillis() - STORY_TTL_MS)

    suspend fun upsertStory(story: StoryEntity) = stories.upsert(story)

    suspend fun getStory(id: String): StoryEntity? = stories.get(id)

    suspend fun markStoryViewed(id: String) = stories.markViewed(id)

    /** Sweep stories older than 24 h. Run from a periodic worker. */
    suspend fun purgeExpiredStories(): Int =
        stories.purgeExpired(System.currentTimeMillis() - STORY_TTL_MS)

    suspend fun deleteStory(id: String) = stories.delete(id)

    // Group-table CRUD delegates to GroupRepository in
    // :feature:groups. The wrappers here preserve the existing
    // call-site signatures so UI / SimpleX code keeps working
    // unchanged after the structural move.
    fun observeGroups() = groupRepo.observeGroups()
    suspend fun getGroup(id: String) = groupRepo.getGroup(id)
    suspend fun getGroupBySimplexId(simplexGroupId: Long) =
        groupRepo.getGroupBySimplexId(simplexGroupId)
    fun observeGroupMembers(groupId: String) = groupRepo.observeGroupMembers(groupId)
    suspend fun groupMemberKeys(groupId: String) = groupRepo.groupMemberKeys(groupId)

    suspend fun createGroup(
        name: String,
        memberKeys: List<String>,
        simplexGroupId: Long? = null,
    ) = groupRepo.createGroup(name, memberKeys, simplexGroupId)

    suspend fun setGroupSimplexId(groupId: String, simplexGroupId: Long) =
        groupRepo.setGroupSimplexId(groupId, simplexGroupId)

    suspend fun upsertGroup(group: GroupEntity) = groupRepo.upsertGroup(group)

    suspend fun addGroupMember(groupId: String, peerPubkey: String) =
        groupRepo.addGroupMember(groupId, peerPubkey)

    fun observeGroupMemberRows(groupId: String) = groupRepo.observeGroupMemberRows(groupId)
    suspend fun groupMemberRows(groupId: String) = groupRepo.groupMemberRows(groupId)

    suspend fun cacheGroupMemberDisplayName(
        groupId: String,
        peerPubkey: String,
        displayName: String,
    ) = groupRepo.cacheGroupMemberDisplayName(groupId, peerPubkey, displayName)

    suspend fun setGroupMembershipPending(groupId: String, pending: Boolean) =
        groupRepo.setGroupMembershipPending(groupId, pending)

    suspend fun setGroupEnabled(groupId: String, enabled: Boolean) =
        groupRepo.setGroupEnabled(groupId, enabled)

    suspend fun setGroupAutoDisableMinutes(groupId: String, minutes: Int?) =
        groupRepo.setGroupAutoDisableMinutes(groupId, minutes)

    // Group profile — facade
    // delegates to the isolated group module.
    suspend fun setGroupAvatarPath(groupId: String, avatarPath: String?) =
        groupRepo.setGroupAvatarPath(groupId, avatarPath)

    suspend fun setGroupDescription(groupId: String, description: String?) =
        groupRepo.setGroupDescription(groupId, description)

    suspend fun setGroupReadme(groupId: String, messageId: String?) =
        groupRepo.setGroupReadme(groupId, messageId)

    // ---------------- Pending invitations ----------------
    // Generated-but-unused 1:1 links.

    fun observePendingInvitations(): Flow<List<PendingInvitationEntity>> =
        pendingInvitations.observeAll()

    suspend fun allPendingInvitations(): List<PendingInvitationEntity> =
        pendingInvitations.all()

    suspend fun addPendingInvitation(connId: Long, link: String, label: String) {
        pendingInvitations.upsert(
            PendingInvitationEntity(
                connId = connId,
                link = link,
                label = label,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Drop a pending-invitation row (revoked, connected, or expired).
     *  Local-only; the relay-side teardown is the transport's job. */
    suspend fun removePendingInvitation(connId: Long) =
        pendingInvitations.delete(connId)

    suspend fun setGroupMemberRole(
        groupId: String,
        peerPubkey: String,
        role: GroupRole,
    ) = groupRepo.setGroupMemberRole(groupId, peerPubkey, role)

    suspend fun setGroupMemberSimplexId(
        groupId: String,
        peerPubkey: String,
        simplexMemberId: Long,
    ) = groupRepo.setGroupMemberSimplexId(groupId, peerPubkey, simplexMemberId)

    suspend fun removeGroupMember(groupId: String, peerPubkey: String) =
        groupRepo.removeGroupMember(groupId, peerPubkey)

    suspend fun deleteGroup(groupId: String) = groupRepo.deleteGroup(groupId)


    /**
     * Observe vault entries. If a vault PIN is set ([VaultLockStore.
     * hasPin] is true on the active profile), entries are filtered
     * to the slot the in-memory [VaultSession] unlocked under
     * (normal vs duress = hidden volume). With no vault PIN, all
     * entries surface — including legacy rows whose keySlot
     * defaults to "normal" from MIGRATION_20_21.
     */
    fun observeSecureNotes(query: String? = null): Flow<List<SecureNoteEntity>> {
        val store = runCatching {
            app.aether.aegis.vault.VaultLockStore(app.aether.aegis.AegisApp.instance)
        }.getOrNull()
        val slot = if (store?.hasPin == true) {
            when (app.aether.aegis.vault.VaultSession.slot) {
                app.aether.aegis.vault.VaultLockStore.PinMatch.DURESS -> "duress"
                else -> "normal"
            }
        } else null
        val raw = if (slot == null) {
            if (query.isNullOrBlank()) secureNotes.observeAll() else secureNotes.search(query)
        } else {
            if (query.isNullOrBlank()) secureNotes.observeBySlot(slot)
            else secureNotes.searchBySlot(slot, query)
        }
        // Per-entry AES-GCM decrypt. Rows whose cipher is null pass
        // through unchanged (no PIN was set when they were saved).
        // Rows whose cipher we can't decrypt with the current
        // session key get dropped — that's the hidden-volume "this
        // belongs to another slot" case. After a query is applied
        // to body LIKE %query%, the search runs against the
        // (possibly empty) plaintext column — fine for unencrypted
        // entries; encrypted ones can't be full-text searched
        // without decrypt-then-filter, which we do here in-memory.
        return raw.map { list -> list.mapNotNull { decryptIfNeeded(it) } }
            .let { flow ->
                if (slot != null && !query.isNullOrBlank()) {
                    // Encrypted entries don't match the SQL LIKE
                    // because body is empty post-encrypt. Apply the
                    // query against decrypted bodies here.
                    flow.map { list ->
                        list.filter { it.body.contains(query, ignoreCase = true) }
                    }
                } else flow
            }
    }

    /** Decrypt a row's body in place if it's encrypted under the
     *  current session key. Returns null if the row IS encrypted
     *  but the session can't decrypt it (no key or wrong slot's
     *  key — the "this entry isn't for the current slot" case).
     *  Plaintext rows (cipher null) pass through unchanged. */
    private fun decryptIfNeeded(row: SecureNoteEntity): SecureNoteEntity? {
        val ct = row.cipher
        val iv = row.iv
        if (ct == null || iv == null) return row
        val key = app.aether.aegis.vault.VaultSession.encryptionKey ?: return null
        val plain = app.aether.aegis.vault.VaultCrypto.decrypt(ct, iv, key) ?: return null
        return row.copy(body = String(plain, Charsets.UTF_8), cipher = null, iv = null)
    }

    /** Encrypt a row's body under the current session key if a key
     *  is available (i.e. a vault PIN is set and the user is
     *  unlocked). Returns the row with body emptied + cipher/iv
     *  set. When no session key is available the row passes
     *  through with plaintext body (pre-PIN era / no PIN
     *  configured). */
    private fun encryptIfPossible(row: SecureNoteEntity): SecureNoteEntity {
        val key = app.aether.aegis.vault.VaultSession.encryptionKey ?: return row
        if (row.body.isEmpty()) return row.copy(cipher = null, iv = null)
        val (ct, iv) = app.aether.aegis.vault.VaultCrypto.encrypt(
            row.body.toByteArray(Charsets.UTF_8), key,
        )
        return row.copy(body = "", cipher = ct, iv = iv)
    }

    /** Encrypt the file at [row].attachmentPath into vault_enc/ if
     *  a session key is available and the file isn't already
     *  encrypted (path doesn't end in .enc). Returns the row with
     *  attachmentPath updated to the new .enc path. The original
     *  plaintext file is deleted on success. No-op without a
     *  session key — attachment stays in app_files/ as plaintext. */
    private fun encryptAttachmentIfPossible(row: SecureNoteEntity): SecureNoteEntity {
        val key = app.aether.aegis.vault.VaultSession.encryptionKey ?: return row
        val path = row.attachmentPath ?: return row
        if (app.aether.aegis.vault.VaultAttachmentCrypto.isEncrypted(path)) return row
        val src = java.io.File(path)
        if (!src.exists()) return row
        val encDir = app.aether.aegis.AegisApp.instance.profileRoot.vaultEncDir
        val newPath = runCatching {
            app.aether.aegis.vault.VaultAttachmentCrypto.encryptFile(src, key, encDir)
        }.getOrNull() ?: return row
        return row.copy(attachmentPath = newPath)
    }

    /** Decrypt an encrypted vault attachment to a temp file in
     *  cacheDir and return that path. Returns [encPath] unchanged
     *  if it isn't an encrypted attachment, null if decryption
     *  fails (wrong slot / no key). Caller uses the returned path
     *  for display + is responsible for ensuring the temp is
     *  cleaned up — VaultAttachmentCrypto.clearDecryptCache runs
     *  on vault lock as a safety net. */
    fun decryptVaultAttachmentForView(encPath: String, context: android.content.Context): String? {
        if (!app.aether.aegis.vault.VaultAttachmentCrypto.isEncrypted(encPath)) return encPath
        val key = app.aether.aegis.vault.VaultSession.encryptionKey ?: return null
        return app.aether.aegis.vault.VaultAttachmentCrypto.decryptToTemp(encPath, key, context)
    }

    /** The vault slot a new entry should be tagged with. Reflects
     *  the current [app.aether.aegis.vault.VaultSession] when a vault PIN is
     *  set; "normal" otherwise. */
    fun currentVaultSlot(): String {
        val store = runCatching {
            app.aether.aegis.vault.VaultLockStore(app.aether.aegis.AegisApp.instance)
        }.getOrNull()
        if (store?.hasPin != true) return "normal"
        return when (app.aether.aegis.vault.VaultSession.slot) {
            app.aether.aegis.vault.VaultLockStore.PinMatch.DURESS -> "duress"
            else -> "normal"
        }
    }

    suspend fun saveSecureNote(
        body: String,
        pinned: Boolean = false,
        attachmentPath: String? = null,
        attachmentMime: String? = null,
        attachmentSize: Long? = null,
        attachmentName: String? = null,
        folder: String? = null,
    ): SecureNoteEntity {
        val now = System.currentTimeMillis()
        val note = SecureNoteEntity(
            id = java.util.UUID.randomUUID().toString(),
            body = body,
            createdAt = now,
            updatedAt = now,
            pinned = pinned,
            attachmentPath = attachmentPath,
            attachmentMime = attachmentMime,
            attachmentSize = attachmentSize,
            attachmentName = attachmentName,
            // New entries land in the slot the user is currently
            // unlocked under — normal vault stays normal, hidden
            // vault collects under "duress". Pre-existing rows are
            // grandfathered into "normal" by MIGRATION_20_21.
            keySlot = currentVaultSlot(),
            folder = folder?.trim()?.takeIf { it.isNotBlank() },
        )
        // Encrypt body + attachment file under the active session
        // key before persist. Both are no-ops when no vault PIN is
        // set; cipher/iv stay null and the attachmentPath stays in
        // app_files/. The attachment encryption walks first so it
        // sets the new attachmentPath into the row before the
        // (path-agnostic) body encryption runs.
        val prepared = encryptIfPossible(encryptAttachmentIfPossible(note))
        secureNotes.upsert(prepared)
        // Return the prepared row to the caller so the editor's
        // local copy reflects the new attachmentPath if encryption
        // moved the file. Without this the editor's "saved" state
        // still points at the old plaintext path, which has been
        // deleted by encryptFile.
        return prepared
    }

    suspend fun updateSecureNote(note: SecureNoteEntity) {
        val toSave = encryptIfPossible(
            encryptAttachmentIfPossible(note.copy(updatedAt = System.currentTimeMillis()))
        )
        secureNotes.upsert(toSave)
    }

    /**
     * Save a chat message into the secure-notes vault, handling the
     * cross-vault re-encryption when the chat attachment is sealed
     * under the PIN pubkey (sealedDek non-null). The chat .enc file
     * lives under `chat_enc/` and uses a DEK sealed under the PIN
     * pubkey; the vault uses a different key (Argon2id from
     * [app.aether.aegis.vault.VaultLockStore]). Storing the chat path verbatim
     * would land an unreadable reference in the vault row.
     *
     * Pipeline:
     *   1. If the attachment is PIN-encrypted, decrypt via
     *      [Repository.viewableAttachmentPath] to a scratch file
     *      under `cacheDir/chat_dec` (requires unlocked PinSession).
     *   2. Hand the decrypted scratch path to [saveSecureNote] —
     *      its existing [encryptAttachmentIfPossible] re-encrypts
     *      under the vault key and writes a fresh .enc under
     *      `vault_enc/`, deleting the scratch source.
     *   3. If PinSession is locked (priv unavailable), returns null
     *      — caller surfaces "unlock to save" toast.
     *
     * Attachments without a sealedDek (plaintext attachments / no
     * attachment) pass through unchanged.
     */
    suspend fun saveChatMessageToVault(
        msg: Message,
        body: String,
        context: android.content.Context,
    ): SecureNoteEntity? {
        val attachPath = msg.attachmentPath
        if (attachPath == null || msg.sealedDek == null) {
            // No attachment, or plaintext attachment → existing path
            // handles it without cross-vault dance.
            return saveSecureNote(
                body = body,
                attachmentPath = attachPath,
                attachmentMime = msg.attachmentMime,
                attachmentSize = msg.attachmentSize,
                attachmentName = msg.attachmentName,
            )
        }
        // PIN-encrypted chat attachment: decrypt to scratch, then
        // save through the regular vault pipeline (which re-encrypts
        // under the vault key + deletes the scratch source).
        val scratch = viewableAttachmentPath(msg, context)
            ?: return null  // locked — caller must unlock first
        return saveSecureNote(
            body = body,
            attachmentPath = scratch,
            attachmentMime = msg.attachmentMime,
            attachmentSize = msg.attachmentSize,
            attachmentName = msg.attachmentName,
        )
    }

    suspend fun deleteSecureNote(id: String) = secureNotes.delete(id)

    /** Wipe every vault entry on this profile — both the normal
     *  and the hidden (duress-slot) volumes. Called by the
     *  Security tab's Wipe Vault control after the typed-confirm.
     *  Attachment FILES on disk are NOT touched here; the caller
     *  walks the entries first and unlinks those before invoking
     *  this. */
    suspend fun wipeAllSecureNotes() = secureNotes.wipeAll()

    /** Every non-null attachment path across BOTH vault slots.
     *  Used by the Wipe Vault control to unlink files from disk
     *  before nuking the rows. */
    suspend fun allVaultAttachmentPaths(): List<String> =
        secureNotes.allAttachmentPaths()

    /** Union of every attachment path the UI could still try to
     *  render — across messages, stories, and secure notes. The
     *  app.aether.aegis.storage.StorageCleanup orphan-pruner uses
     *  this to identify safe-to-delete files in app_files/. */
    suspend fun allReferencedAttachmentPaths(): Set<String> {
        val set = HashSet<String>()
        runCatching { set.addAll(messages.allMessageAttachmentPaths()) }
        runCatching { set.addAll(stories.allStoryAttachmentPaths()) }
        runCatching { set.addAll(secureNotes.allAttachmentPaths()) }
        return set
    }

    /** Maps each non-null messages.attachmentPath to a human-readable
     *  peer label (the contact's display name, or the raw peerKey
     *  prefix as fallback). Used by the Storage card inspector to
     *  show "this 146 MB video is in the chat with Zippy" instead
     *  of just an opaque file path. Same path referenced by multiple
     *  rows is collapsed to whichever peer Room walks first — fine
     *  for diagnostic purposes. */
    suspend fun attachmentPathPeerLabels(): Map<String, String> {
        val peerByKey = runCatching {
            knownPeers.all().associate { it.publicKey to it.displayName }
        }.getOrDefault(emptyMap())
        val out = HashMap<String, String>()
        runCatching { messages.allAttachmentsWithPeer() }.getOrNull()?.forEach { row ->
            val path = row.attachmentPath ?: return@forEach
            val label = peerByKey[row.peerKey]
                ?: row.peerKey.removePrefix("simplex:").take(12)
            out.putIfAbsent(path, label)
        }
        return out
    }

    /** Returns (deletedRows, freedBytes) for every messages row
     *  whose attachmentPath ends in a name that matches [nameRegex].
     *  Two-stage: SQL pre-filter pulls all rows whose path matches
     *  the loose `extLike` pattern, then a Kotlin regex tightens
     *  the match on the basename. This avoids SQL LIKE's underscore
     *  ambiguity catching unintended files. Deletes the on-disk
     *  file before the row. */
    suspend fun purgeMessageAttachmentsMatching(
        extLike: String,
        nameRegex: Regex,
    ): Pair<Int, Long> {
        val candidates = runCatching {
            messages.matchByAttachmentPath(extLike)
        }.getOrDefault(emptyList())
        val toDelete = candidates.filter { row ->
            val name = row.attachmentPath?.substringAfterLast('/') ?: return@filter false
            nameRegex.matches(name)
        }
        var freed = 0L
        toDelete.forEach { row ->
            val p = row.attachmentPath ?: return@forEach
            val f = java.io.File(p)
            if (f.exists()) {
                val sz = f.length()
                if (f.delete()) freed += sz
            }
            runCatching { messages.deleteById(row.id) }
        }
        return toDelete.size to freed
    }

    /**
     * Re-encrypt every row in [slot] from [oldKey] to [newKey].
     * Called when a vault PIN is set / changed / cleared so the
     * encrypted body column is always keyed off the CURRENT PIN.
     *
     *   - oldKey null + newKey set → first-time encrypt (no PIN → PIN)
     *   - oldKey set  + newKey null → first-time decrypt (PIN → no PIN)
     *   - oldKey set  + newKey set  → PIN change, re-key
     *
     * Rows that we can't decrypt with [oldKey] are LEFT UNTOUCHED
     * — they belong to a different slot and a wrong-key decrypt
     * would corrupt them. Rows with cipher null and oldKey set are
     * also left untouched (already plaintext).
     */
    suspend fun reencryptVaultSlot(slot: String, oldKey: ByteArray?, newKey: ByteArray?) {
        val rows = secureNotes.allBySlotRaw(slot)
        for (row in rows) {
            // Determine plaintext source: either decrypt the
            // existing cipher under oldKey or use the body column.
            // Hoist to locals — row.cipher/iv are now cross-module entity
            // properties (entities moved to :core:transport) which Kotlin
            // won't smart-cast.
            val rowCipher = row.cipher
            val rowIv = row.iv
            val plaintextSource: String? = when {
                rowCipher != null && rowIv != null && oldKey != null -> {
                    app.aether.aegis.vault.VaultCrypto.decrypt(rowCipher, rowIv, oldKey)
                        ?.toString(Charsets.UTF_8)
                }
                rowCipher != null && rowIv != null && oldKey == null -> {
                    // Existing ciphertext but no oldKey — can't recover.
                    // Skip rather than blank it out.
                    null
                }
                else -> row.body
            }
            val plaintext = plaintextSource ?: continue
            // Re-encrypt under newKey, or store as plaintext.
            val (cipher, iv) = if (newKey != null && plaintext.isNotEmpty()) {
                app.aether.aegis.vault.VaultCrypto.encrypt(
                    plaintext.toByteArray(Charsets.UTF_8), newKey,
                )
            } else null to null
            secureNotes.upsert(
                row.copy(
                    body = if (newKey != null) "" else plaintext,
                    cipher = cipher,
                    iv = iv,
                )
            )
        }
    }

    suspend fun secureNoteById(id: String): SecureNoteEntity? =
        secureNotes.get(id)?.let { decryptIfNeeded(it) }

    suspend fun pinSecureNote(id: String, pinned: Boolean) = secureNotes.setPinned(id, pinned)

    /** Vault folder tags on the active slot. Slot scoping keeps
     *  the normal and hidden volumes' folder trees independent. */
    fun observeVaultFolders(): Flow<List<String>> =
        secureNotes.observeFoldersForSlot(currentVaultSlot())

    suspend fun setSecureNoteFolder(id: String, folder: String?) =
        secureNotes.setFolder(id, folder?.trim()?.takeIf { it.isNotBlank() })

    fun observeKnownPeers(): Flow<List<KnownPeerEntity>> =
        knownPeers.observeAll().map { list -> list.map { it.unsealIdentityForRead() } }

    /** Live presence-tier (TRUSTED) contact count — drives the
     *  location/presence module gate (Phase 1). */
    fun trustedCountFlow(): Flow<Int> = knownPeers.trustedCountFlow()

    /** Live sos-tier (TRUSTED ∪ EMERGENCY) contact count — drives the
     *  sos module gate (Phase 1). */
    fun sosCountFlow(): Flow<Int> = knownPeers.sosCountFlow()

    suspend fun allKnownPeers(): List<KnownPeerEntity> =
        knownPeers.all().map { it.unsealIdentityForRead() }

    suspend fun addKnownPeer(publicKey: String, displayName: String) {
        // Apply the active profile's default disappearing-messages
        // TTL so every new chat starts under the user's chosen
        // policy (Signal-style default-burn).
        // A new peer is always UNTRUSTED, and under the Aegis Protocol's
        // transport hardening untrusted history is disposable by
        // design — least valuable, most dangerous on a seized device. So
        // if the user hasn't set their own global default, untrusted chats
        // default to 48h auto-delete (not "forever"). setPeerTier clears
        // this when the contact is promoted to Trusted/Emergency.
        val defaultTtl = runCatching {
            app.aether.aegis.prefs.ChatDefaultsPrefs(
                app.aether.aegis.AegisApp.instance,
            ).defaultDisappearingTtlSeconds
        }.getOrNull() ?: UNTRUSTED_DEFAULT_TTL_SECONDS
        knownPeers.upsert(
            KnownPeerEntity(
                publicKey = publicKey,
                displayName = displayName,
                addedAt = System.currentTimeMillis(),
                lastSeenAt = null,
                disappearingTtl = defaultTtl,
            ).sealNameForWrite()
        )
        _peerMutations.emit(PeerMutation.Added(publicKey))
    }

    /**
     * Delete a contact and every trace of them this device holds:
     * known-peer row, the entire 1:1 conversation, last-known status,
     * any pending outbox to that peer. Group memberships are NOT
     * touched (groups can outlive any single member; removing the
     * peer from a group is a separate action via removeGroupMember).
     *
     * Irreversible — caller should already have shown a confirm
     * dialog. PeerMutation.Removed is emitted last so observers
     * have stable DB state before they react.
     */
    suspend fun removeKnownPeer(publicKey: String) {
        runCatching { messages.clearConversation(publicKey) }
        runCatching { messages.clearConversation("group:$publicKey") }  // no-op for non-groups
        runCatching { statuses.deleteFor(publicKey) }
        runCatching { outbox.removeForPeer(publicKey) }
        knownPeers.delete(publicKey)
        _peerMutations.emit(PeerMutation.Removed(publicKey))
    }

    suspend fun renameKnownPeer(publicKey: String, displayName: String) {
        if (!sealing.canSeal) {
            knownPeers.rename(publicKey, displayName)
        } else {
            // Seal the new name. Read the raw row to preserve every other
            // field (incl. the sealedAnnounced blob), reset the local name,
            // re-seal. Rename is a user action, so we're unlocked — but we
            // don't even need the priv here, only the cached pub.
            val raw = knownPeers.get(publicKey)
            if (raw != null) {
                knownPeers.upsert(
                    raw.copy(displayName = displayName, sealedName = null).sealNameForWrite()
                )
            } else {
                knownPeers.rename(publicKey, displayName)
            }
        }
        _peerMutations.emit(PeerMutation.Renamed(publicKey))
    }

    private val _peerMutations = MutableSharedFlow<PeerMutation>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    val peerMutations: SharedFlow<PeerMutation> = _peerMutations.asSharedFlow()

    sealed class PeerMutation {
        abstract val publicKey: String
        data class Added(override val publicKey: String) : PeerMutation()
        data class Removed(override val publicKey: String) : PeerMutation()
        data class Renamed(override val publicKey: String) : PeerMutation()
    }

    suspend fun updatePeerProfile(
        publicKey: String,
        announcedName: String?,
        announcedBio: String?,
        announcedAvatarPath: String?,
    ) {
        if (!sealing.canSeal) {
            // No sealing configured (no PIN/phrase) — legacy COALESCE path.
            knownPeers.updateAnnouncedProfile(publicKey, announcedName, announcedBio, announcedAvatarPath)
            return
        }
        val raw = knownPeers.get(publicKey) ?: return
        // COALESCE the announced fields: when UNLOCKED we can read the old
        // sealed values and preserve unprovided ones; while LOCKED we
        // can't unseal, so unprovided fields fall through to the provided
        // (possibly null) value. Background contactUpdated carries the full
        // profile in practice, so the locked case rarely drops anything.
        val base = if (sealing.canUnseal) raw.unsealIdentityForRead() else raw
        knownPeers.upsert(
            raw.copy(
                announcedName = announcedName ?: base.announcedName,
                announcedBio = announcedBio ?: base.announcedBio,
                announcedAvatarPath = announcedAvatarPath ?: base.announcedAvatarPath,
                sealedAnnounced = null,
            ).sealAnnouncedForWrite()
        )
    }

    suspend fun touchKnownPeer(publicKey: String) {
        knownPeers.touch(publicKey, System.currentTimeMillis())
    }

    suspend fun isKnown(publicKey: String): Boolean = knownPeers.get(publicKey) != null

    suspend fun knownPeerByKey(publicKey: String) =
        knownPeers.get(publicKey)?.unsealIdentityForRead()

    /**
     * Re-pair migration. Used when an existing contact's SimpleX
     * identity has changed (peer wiped + reinstalled, got a new
     * phone, etc.) and the user wants the new pairing to attach to
     * the existing contact row — keeping display name, avatar, trust
     * tier, chat history — instead of producing a duplicate "Zippy
     * 2" entry.
     *
     * Same key on both sides → trivial no-op. The bind layer just
     * refreshes its in-memory peerByKey mapping.
     *
     * Different keys → migrate every FK row from oldKey to newKey
     * inside a single transaction:
     *   - messages.peerKey / fromKey / toKey rewrite via
     *     MessageDao.rewritePeerKey
     *   - member_status row is reinserted under newKey (peerKey is
     *     a primary key there, no in-place rewrite possible)
     *   - stories.authorKey rewrite
     *   - scheduled_messages.toKey rewrite
     *   - known_peers row is duplicated to newKey carrying every
     *     metadata field (display name, avatar, pinned, muted,
     *     disappearingTtl, verified, trustTier, blocked, etc.) AND
     *     then the old key's row is deleted.
     *
     * Returns the number of messages rows migrated for the caller
     * to surface in a "moved N messages" toast. Returns 0 on
     * same-key short-circuit.
     */
    @androidx.room.Transaction
    suspend fun repairKnownPeer(oldKey: String, newKey: String): Int {
        if (oldKey == newKey) return 0
        val oldRow = knownPeers.get(oldKey) ?: return 0
        // 1. Copy known_peers row under the new key. We preserve
        // every user-set metadata field; the only thing that
        // changes is the primary key itself.
        knownPeers.upsert(oldRow.copy(publicKey = newKey))
        // 2. Migrate FK rows. Each is idempotent — re-running this
        // method after an earlier crash mid-transaction is safe.
        val rewritten = messages.rewritePeerKey(oldKey, newKey)
        statuses.latest(oldKey)?.let { st ->
            statuses.upsert(st.copy(peerKey = newKey))
            statuses.deleteByPeerKey(oldKey)
        }
        runCatching { stories.rewriteAuthorKey(oldKey, newKey) }
        runCatching { scheduled.rewriteToKey(oldKey, newKey) }
        // 3. Drop the original known_peers row — its data has
        // already moved to newKey above. Doing this last keeps the
        // FK migrations point-in-time consistent.
        knownPeers.delete(oldKey)
        return rewritten
    }

    suspend fun setPeerPinned(publicKey: String, pinned: Boolean) =
        knownPeers.setPinned(publicKey, pinned)
    suspend fun setPeerMuted(publicKey: String, muted: Boolean) =
        knownPeers.setMuted(publicKey, muted)
    suspend fun setPeerDisappearingTtl(publicKey: String, ttl: Long?) =
        knownPeers.setDisappearingTtl(publicKey, ttl)
    suspend fun setPeerVerified(publicKey: String, verified: Boolean) =
        knownPeers.setVerified(publicKey, verified)

    /** Default disposable-history TTL for UNTRUSTED contacts (48h), part
     *  of the Aegis Protocol transport hardening. Trusted/Emergency
     *  persist (null TTL). The number is owned by the tier module that
     *  defines the rule — `:feature:untrusted` — and read here across the
     *  allowed app → :feature:untrusted edge. */
    private val UNTRUSTED_DEFAULT_TTL_SECONDS =
        app.aether.aegis.untrusted.UntrustedPolicy.DISPOSABLE_TTL_SECONDS

    suspend fun setPeerTier(publicKey: String, tier: TrustTier) {
        knownPeers.setTrustTier(publicKey, tier.name)
        // Tier drives history retention (transport
        // hardening): Trusted/Emergency are the safety network and persist;
        // Untrusted is chat-only and disposable (48h auto-delete). Promotion
        // clears the disposable TTL; demotion (re)applies it.
        when (tier) {
            TrustTier.TRUSTED, TrustTier.EMERGENCY ->
                knownPeers.setDisappearingTtl(publicKey, null)
            TrustTier.UNTRUSTED ->
                knownPeers.setDisappearingTtl(publicKey, UNTRUSTED_DEFAULT_TTL_SECONDS)
        }
        // Aegis Protocol Stage 3: elevating
        // an Aegis contact to Trusted/Emergency reveals your REAL identity
        // to them over the [aegis:identity] overlay. Trust IS the moment you
        // let someone see who you are. One-way: demotion can't un-send it
        // (there is no revoke envelope).
        if (tier == TrustTier.TRUSTED || tier == TrustTier.EMERGENCY) {
            val peer = runCatching { knownPeerByKey(publicKey) }.getOrNull()
            if (peer?.isAegis == true) revealIdentityTo(publicKey)
            // Re-announce OUR ShieldTier now so the contact we just elevated
            // sees our tier "medal" frame immediately, instead of only after
            // our next cold start (the only other time TierBroadcaster ran).
            // broadcastNow ships [aegis:tier] to every Trusted contact and is
            // idempotent, so re-announcing to all is fine.
            runCatching {
                app.aether.aegis.admin.TierBroadcaster.broadcastNow(
                    app.aether.aegis.AegisApp.instance,
                )
            }
        }
        // Remote access is a Trusted-only capability:
        // the grant prompt appears only at Trusted promotion. Any tier below
        // Trusted therefore revokes it — demoting someone out of Trusted must
        // also pull their ability to locate/lock/wipe, or the grant would
        // outlive the trust that justified it. (Promotion does NOT auto-grant;
        // the contact-detail prompt does that, opt-in.)
        if (tier != TrustTier.TRUSTED) {
            knownPeers.setRemoteAccessEnabled(publicKey, false)
        }
    }

    /** Grant or revoke a contact's remote-access capability.
     *  Set true from the Trusted-promotion prompt
     *  or the contact-detail toggle; false to revoke. Remote commands still
     *  require the owner's real PIN per command — this flag is the per-contact
     *  gate on TOP of that, enforced in RemoteAccessHandler.handleAuth. */
    suspend fun setRemoteAccessEnabled(publicKey: String, enabled: Boolean) =
        knownPeers.setRemoteAccessEnabled(publicKey, enabled)

    /** Send the user's real identity (name/bio/avatar) to one peer over
     *  the `[aegis:identity]` overlay. Best-effort; the `[aegis:*]`
     *  capability gate in ProtocolManager drops it for a non-Aegis peer,
     *  so identity can only reach a confirmed Aegis client. */
    private suspend fun revealIdentityTo(publicKey: String) {
        val simplex = app.aether.aegis.AegisApp.instance.transports
            .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
            .firstOrNull() ?: return
        runCatching { simplex.sendIdentityEnvelope(publicKey) }
            .onFailure {
                android.util.Log.w("Repository", "identity reveal failed for $publicKey", it)
            }
    }

    /** Re-push identity to every currently-elevated (Trusted ∪ Emergency)
     *  contact — call after the user edits their profile so a trusted
     *  contact's view of your name/avatar/bio stays current (Aether
     *  Protocol Stage 3). Non-Aegis peers are skipped (and would be gated
     *  out regardless). */
    suspend fun revealIdentityToElevatedContacts() {
        runCatching { sosTargets() }.getOrNull().orEmpty().forEach { peer ->
            if (peer.isAegis) revealIdentityTo(peer.publicKey)
        }
    }
    suspend fun setPeerBlocked(publicKey: String, blocked: Boolean) =
        knownPeers.setBlocked(publicKey, blocked)
    suspend fun markFirstSOSShown(publicKey: String, ts: Long) =
        knownPeers.setFirstSOSShownAt(publicKey, ts)
    /** Idempotent — flips isAegis to true on first inbound aegis-tagged
     *  message from this peer. No-op if already set (cheap UPDATE
     *  filtered by `isAegis = 0`). */
    suspend fun markPeerIsAegis(publicKey: String) =
        knownPeers.markIsAegis(publicKey)
    /** Store the last tier-name this peer announced. Null clears it. */
    suspend fun setPeerReportedTier(publicKey: String, tier: String?) =
        knownPeers.setPeerReportedTier(publicKey, tier)
    /** Store the last crown-shimmer style this peer announced. Null clears it. */
    suspend fun setPeerReportedCrownStyle(publicKey: String, style: Int?) =
        knownPeers.setPeerReportedCrownStyle(publicKey, style)
    /** Store the capability CSV this peer announced (`[aegis:caps]`). */
    suspend fun setPeerCapabilities(publicKey: String, caps: String?) =
        knownPeers.setPeerCapabilities(publicKey, caps)
    /** Resolve a local message by its DNA (DNA-keyed edit/delete/reply). */
    suspend fun messageByDna(dna: Long): MessageEntity? =
        messages.findByDna(dna)
    /** Resolve a local message by its SimpleX itemId — used on send to map a
     *  reply's quoted itemId to that message's DNA for the envelope. */
    suspend fun messageByItemId(itemId: Long): MessageEntity? =
        messages.findBySimplexItemId(itemId)
    /** Apply an inbound DNA-keyed edit — the peer edited THEIR OWN message.
     *  Routes through setMessageEditedBody so the new text is re-sealed under
     *  the PIN exactly like any stored body (a raw body UPDATE would leave the
     *  old sealedBody winning). outgoing guard: a peer can only edit a message
     *  they sent us, never one of ours. */
    suspend fun applyInboundEditByDna(dna: Long, newBody: String) {
        val row = messages.findByDna(dna) ?: return
        if (row.outgoing) return
        setMessageEditedBody(row.id, newBody)
    }
    /** Apply an inbound DNA-keyed delete (peer retracted their own message). */
    suspend fun deleteInboundByDna(dna: Long) =
        messages.deleteInboundByDna(dna)

    // ---- Command-auth control channel ----
    /** Record the peer's Ed25519 control pubkey (hex) from the hello
     *  bootstrap — the "channel bootstrapped" marker (see
     *  KnownPeerEntity.controlPubKey). */
    suspend fun setPeerControlPubKey(publicKey: String, pubHex: String?) =
        knownPeers.setControlPubKey(publicKey, pubHex)
    suspend fun setPeerNotificationSoundUri(publicKey: String, uri: String?) =
        knownPeers.setNotificationSoundUri(publicKey, uri)
    suspend fun setPeerFolder(publicKey: String, folder: String?) =
        knownPeers.setFolder(publicKey, folder?.trim()?.takeIf { it.isNotBlank() })
    fun observeFolders(): kotlinx.coroutines.flow.Flow<List<String>> =
        knownPeers.observeFolders()

    /** Routine-data recipients — Trusted only. Drives every recurring
     *  location / status / sensor broadcast. */
    suspend fun trustedTargets(): List<KnownPeerEntity> =
        knownPeers.trustedTargets().map { it.unsealIdentityForRead() }
    /** SOS recipients — Trusted ∪ Emergency. Untrusted is excluded
     *  by construction (the Untrusted tier exists precisely to opt out
     *  of the sos broadcast). Names unseal when unlocked; while locked
     *  the sos path falls back to the pubkey prefix (SOSCoordinator). */
    suspend fun sosTargets(): List<KnownPeerEntity> =
        knownPeers.sosTargets().map { it.unsealIdentityForRead() }

    suspend fun clearConversation(peerKey: String) = messages.clearConversation(peerKey)
    suspend fun deleteMessage(id: String) = messages.deleteById(id)

    /**
     * SQL LIKE search runs over the plaintext `body` column, so it
     * naturally skips PIN-sealed rows (their body is empty). When the
     * session is unlocked we additionally walk the sealed rows
     * in-memory and filter by the decrypted body — slow on large
     * histories, but correct. When locked, only legacy plaintext
     * rows match. This is the right UX on every branch.
     */
    suspend fun searchMessages(query: String): List<Message> {
        if (query.isBlank()) return emptyList()
        val plaintextHits = messages.search(query).map { it.toDomain() }
        if (!sealing.canUnseal) return plaintextHits
        val needle = query.lowercase()
        val sealedHits = messages.allSealed()
            .mapNotNull { entity ->
                val plain = entity.sealedBody?.let { sealing.tryUnseal(it) } ?: return@mapNotNull null
                val decoded = String(plain, Charsets.UTF_8)
                if (decoded.lowercase().contains(needle)) entity.toDomain(decoded) else null
            }
        return (plaintextHits + sealedHits).sortedByDescending { it.timestamp }
    }

    /** Flow of {peerKey → last Message}, for the chat list previews. */
    fun observeLastMessagePerPeer(): Flow<Map<String, Message>> =
        messages.observeLastMessagePerPeer().map { rows ->
            rows.associate { it.peerKey to it.toDomain() }
        }

    fun conversation(peerKey: String): Flow<List<Message>> =
        messages.observeConversation(peerKey).map { rows -> rows.map { it.toDomain() } }

    suspend fun recordSent(
        toKey: String,
        body: String,
        protocol: Protocol,
        type: MessageType = MessageType.TEXT,
        burnTtlSeconds: Int? = null,
        idOverride: String? = null,
        replyToItemId: Long? = null,
        // Force the initial tick status. Used by the offline-queue path to
        // start a LOCAL row at "sending" (the hex clock) even though it's
        // Protocol.LOCAL — the outbox drain advances that same row once it
        // sends. Null = derive from the default rules below.
        statusOverride: String? = null,
    ): Message {
        val now = System.currentTimeMillis()
        val entity = MessageEntity(
            id = idOverride ?: UUID.randomUUID().toString(),
            fromKey = selfKey,
            toKey = toKey,
            peerKey = toKey,
            body = body,
            timestamp = now,
            protocol = protocol.name,
            type = type.name,
            // LOCAL means "no transport accepted, queued to outbox".
            // Self-chat also uses LOCAL but is delivered the moment
            // it lands in our DB, so override there.
            delivered = protocol != Protocol.LOCAL || toKey == selfKey,
            outgoing = true,
            burnTtlSeconds = burnTtlSeconds,
            // Initial tick state. A direct SimpleX send starts at "sending"
            // (the hex-clock pending state), NOT "sent": the row has only been
            // HANDED to the transport, it hasn't left the device. SimpleX's
            // sndSent event promotes it to "sent" (mapCiStatus →
            // setStatusByItemId, monotonic) once it actually goes out — so while
            // the radio is off it honestly shows the clock until it transmits.
            //   - self-note: never sends, lands complete ("read").
            //   - group: this path links no per-item simplexItemId, so sndSent
            //     could never promote it — it would be stuck at the clock; keep
            //     "sent" (the group fan-out has its own delivery semantics).
            //   - LOCAL (no transport accepted → outbox orphan): never gets an
            //     itemId on THIS row (the drain re-records under SimpleX), so it
            //     too would be stuck; keep "sent". Making the offline-queued row
            //     itself show pending needs the outbox→row link refactor (TODO).
            status = statusOverride ?: when {
                toKey == selfKey -> "read"
                toKey.startsWith("group:") -> "sent"
                protocol == Protocol.LOCAL -> "sent"
                else -> "sending"
            },
            replyToItemId = replyToItemId,
        )
        val sealed = entity.applySealing()
        messages.insert(sealed)
        // Return a domain Message that carries the ORIGINAL body —
        // callers (e.g. send-pipeline) need to actually transmit the
        // plaintext, not the ciphertext placeholder.
        return sealed.toDomain(plaintextOverride = body)
    }

    suspend fun recordReceived(
        fromKey: String,
        body: String,
        protocol: Protocol,
        type: MessageType = MessageType.TEXT,
        burnTtlSeconds: Int? = null,
        // Group-message override: when set, the row's peerKey is the
        // group's conversation id ("group:<uuid>") instead of the
        // sender's pubkey, so GroupChatScreen.observe finds it. fromKey
        // stays the actual sender for attribution.
        peerKeyOverride: String? = null,
        // Originating-transport identifiers — populated by SimpleX
        // for the post-persist core-mirror purge (see InboundMessage).
        // null for transports that don't track an itemId / chatRef.
        sourceItemId: Long? = null,
        sourceChatRef: String? = null,
        // When this inbound message is a reply, the simplexItemId of the
        // message it quotes — powers tap-a-citation → jump-to-original.
        replyToItemId: Long? = null,
        // Aegis message DNA (epoch-nanos) parsed from the chat envelope, when
        // the sender is an Aegis peer. null for vanilla MCText / legacy. Stored
        // so a read receipt we send back can echo it, and so it survives a
        // transport swap.
        messageDna: Long? = null,
        // When this inbound message is a reply, the DNA of the quoted message
        // (envelope replyToDna). Powers the receiver-side citation jump for
        // Aegis replies, which carry no native quote. null otherwise.
        replyToDna: Long? = null,
    ): Message {
        // Dedup redelivered items. The SimpleX core can re-deliver the SAME
        // chat item (same simplexItemId) — on reconnect, after a transient
        // error, during the redelivery storms we've seen. Without this guard
        // each redelivery inserted a fresh row with a NEW id and a NEW
        // `now` timestamp, which (a) duplicated the message in the chat and
        // (b) kept pushing the conversation's last-message time past the read
        // watermark, so its unread dot — and the Comms/Groups badges — never
        // cleared no matter how many times you opened it. If we've already
        // stored this item, return the existing row untouched.
        if (sourceItemId != null) {
            val dup = messages.findBySimplexItemId(sourceItemId)
            if (dup != null) return dup.toDomain(plaintextOverride = body)
        }
        val now = System.currentTimeMillis()
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            fromKey = fromKey,
            toKey = selfKey,
            peerKey = peerKeyOverride ?: fromKey,
            body = body,
            timestamp = now,
            protocol = protocol.name,
            type = type.name,
            delivered = true,
            outgoing = false,
            burnTtlSeconds = burnTtlSeconds,
            simplexItemId = sourceItemId,
            messageDna = messageDna,
            replyToDna = replyToDna,
            replyToItemId = replyToItemId,
            // Marker that the transport-layer mirror still exists
            // until ProtocolManager clears it after a successful
            // purgeOriginal. Boot-time sweep retries on crash.
            pendingPurgeChatRef = sourceChatRef,
        )
        val sealed = entity.applySealing()
        messages.insert(sealed)
        return sealed.toDomain(plaintextOverride = body)
    }

    /** Burn-after-reading: wipe a single message row both ends use the
     *  same id. Called by the BurnViewer on close (local side) and by
     *  the inbound burn-receipt handler (sender side). */
    suspend fun deleteMessageById(id: String) = messages.deleteById(id)

    suspend fun markDelivered(id: String) = messages.markDelivered(id)

    /**
     * Insert a local-only call-history journal row. Surfaces inline
     * in the chat as a WhatsApp-style chip ("↗ Voice call · 3:45" /
     * "↘ Missed video call"). Never broadcast — peer logs their own
     * perspective when their own [app.aether.aegis.call.CallManager.end] fires.
     */
    suspend fun recordCallLog(
        peerKey: String,
        outgoing: Boolean,
        video: Boolean,
        durationMs: Long,
        connected: Boolean,
        reason: String,
    ): Message {
        val now = System.currentTimeMillis()
        val body = org.json.JSONObject().apply {
            put("video", video)
            put("duration_ms", durationMs)
            put("connected", connected)
            put("reason", reason)
        }.toString()
        val entity = MessageEntity(
            id = UUID.randomUUID().toString(),
            fromKey = if (outgoing) selfKey else peerKey,
            toKey = if (outgoing) peerKey else selfKey,
            peerKey = peerKey,
            body = body,
            timestamp = now,
            protocol = Protocol.LOCAL.name,
            type = MessageType.CALL_LOG.name,
            delivered = true,
            outgoing = outgoing,
        )
        val sealed = entity.applySealing()
        messages.insert(sealed)
        return sealed.toDomain(plaintextOverride = body)
    }

    /**
     * Insert a local audit row into a group's chat history. Part of
     * groups hardening — these rows render
     * as compact centre chips in [GroupChatScreen] rather than
     * bubbles, and document membership / metadata changes
     * (rename, leave, kick, role-change, TTL set, etc.).
     *
     * Body is composed via [GroupSystemPayload.encode] from the
     * structured arguments. The message id is **deterministic** —
     * derived from `(peerKey, kind, actor, subject,
     * timestampMinute)` so the local-write path and the SimpleX-
     * echo path land on the same Room primary key. With Room's
     * REPLACE strategy, the second write is idempotent (same
     * body, same id, same row) — no double-render.
     *
     * Dedupe window is **one minute**: a local rename + the
     * server's echo a few seconds later collapse into one row;
     * two genuinely-separate renames a minute apart get two
     * rows.
     */
    suspend fun recordGroupSystem(
        groupId: String,
        kind: GroupSystemPayload.Kind,
        actor: String,
        subject: String? = null,
        from: String? = null,
        to: String? = null,
        seconds: Long? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): Message {
        val peerKey = "group:$groupId"
        val body = GroupSystemPayload.encode(
            kind = kind,
            actor = actor,
            subject = subject,
            from = from,
            to = to,
            seconds = seconds,
        )
        // Dedupe key — same components used elsewhere. Minute-
        // bucket the timestamp so local + SimpleX-echo collapse.
        val minute = timestamp / 60_000L
        val dedupeKey = "gs|$peerKey|${kind.name}|$actor|${subject ?: "-"}|$minute"
        val id = run {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(dedupeKey.toByteArray(Charsets.UTF_8))
            android.util.Base64.encodeToString(
                digest, android.util.Base64.URL_SAFE or
                    android.util.Base64.NO_PADDING or
                    android.util.Base64.NO_WRAP,
            ).take(24)
        }
        val entity = MessageEntity(
            id = id,
            fromKey = actor,
            // No "to" — group rows scope by peerKey, not by
            // destination. Keep toKey = peerKey for consistency with
            // how the rest of the messages table treats group rows.
            toKey = peerKey,
            peerKey = peerKey,
            body = body,
            timestamp = timestamp,
            protocol = Protocol.LOCAL.name,
            type = MessageType.GROUP_SYSTEM.name,
            delivered = true,
            outgoing = actor == selfKey,
        )
        val sealed = entity.applySealing()
        messages.insert(sealed)
        return sealed.toDomain(plaintextOverride = body)
    }

    fun statuses(): Flow<List<MemberStatusEntity>> =
        statuses.observeAll().map { rows -> rows.map { it.unsealStatusForRead() } }

    fun observeStatus(peerKey: String): Flow<MemberStatusEntity?> =
        statuses.observe(peerKey).map { it?.unsealStatusForRead() }

    /** One-shot read of the latest cached status for [peerKey]. Used
     *  by call sites that need a synchronous snapshot rather than a
     *  flow subscription (e.g. SOSCoordinator's distance probes). */
    suspend fun latestStatus(peerKey: String): MemberStatusEntity? =
        statuses.latest(peerKey)?.unsealStatusForRead()

    suspend fun upsertStatus(status: MemberStatusEntity) =
        statuses.upsert(status.sealStatusForWrite())

    suspend fun patchLocation(peerKey: String, latitude: Double, longitude: Double, ts: Long) {
        val cur = latestStatus(peerKey)
        val next = (cur ?: emptyStatus(peerKey)).copy(
            latitude = latitude,
            longitude = longitude,
            lastActive = ts,
        )
        upsertStatus(next)
    }

    suspend fun patchDeviceStatus(
        peerKey: String,
        batteryLevel: Int?,
        isCharging: Boolean?,
        networkType: String?,
        signalStrength: Int?,
        /** Foreground heartbeat (the sender's inApp timestamp).
         *  Drives the Online presence state. */
        ts: Long,
        /** Packet-generation timestamp from the sender's status
         *  ticker — proves the background service is alive even if
         *  the user isn't actively in the app. Null on the self-status
         *  partial patch (which carries no packet stamp); null then
         *  preserves the previous value instead of clearing it. */
        packetTs: Long? = null,
        /** Peer's app version (YYYY.MM.BBB). Null on the self-status
         *  partial patch; a peer status ping always carries it. */
        appVersion: String? = null,
    ) {
        val cur = latestStatus(peerKey)
        val next = (cur ?: emptyStatus(peerKey)).copy(
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            networkType = networkType,
            signalStrength = signalStrength,
            lastActive = ts,
            lastPacketMs = packetTs ?: cur?.lastPacketMs,
            appVersion = appVersion,
        )
        upsertStatus(next)
    }

    suspend fun patchWatchStatus(
        peerKey: String,
        heartRate: Int?,
        hrv: Int?,
        spo2: Int?,
        ts: Long,
    ) {
        val cur = latestStatus(peerKey)
        val next = (cur ?: emptyStatus(peerKey)).copy(
            heartRate = heartRate,
            hrv = hrv,
            spo2 = spo2,
            lastActive = ts,
        )
        upsertStatus(next)
    }

    private fun emptyStatus(peerKey: String) = MemberStatusEntity(
        peerKey = peerKey,
        batteryLevel = null,
        isCharging = null,
        networkType = null,
        signalStrength = null,
        latitude = null,
        longitude = null,
        lastActive = System.currentTimeMillis(),
        heartRate = null,
        hrv = null,
        spo2 = null,
    )

    suspend fun enqueueOutbox(
        toKey: String,
        body: String,
        type: MessageType = MessageType.TEXT,
        // The visible chat row this queued send belongs to — the drain advances
        // it in place instead of recording a duplicate. Null for callers that
        // don't pre-record a row.
        messageId: String? = null,
    ) {
        val now = System.currentTimeMillis()
        outbox.enqueue(
            OutboxEntity(
                id = UUID.randomUUID().toString(),
                toKey = toKey,
                body = body,
                type = type.name,
                createdAt = now,
                attempts = 0,
                nextAttemptAt = now,
                messageId = messageId,
            )
        )
    }

    /** Flip a row's transport label (e.g. LOCAL→SIMPLEX once an outbox-queued
     *  message actually drains). Cosmetic — the tick status drives delivery UI. */
    suspend fun setMessageProtocol(id: String, protocol: Protocol) =
        messages.setProtocol(id, protocol.name)

    suspend fun pendingOutbox(): List<OutboxEntity> = outbox.ready(System.currentTimeMillis())

    suspend fun removeOutbox(id: String) = outbox.remove(id)

    suspend fun rescheduleOutbox(id: String, backoffMs: Long) {
        outbox.reschedule(id, System.currentTimeMillis() + backoffMs)
    }

    fun pendingCount(): Flow<Int> = outbox.observePending()

    suspend fun clearOutbox() = outbox.clearAll()

    suspend fun clearOutboxForPeer(toKey: String) = outbox.removeForPeer(toKey)

    suspend fun purgeExhaustedOutbox(maxAttempts: Int = 30) {
        // Flip any linked chat row to 'error' BEFORE deleting the entry, so an
        // undeliverable queued message stops showing the hex clock and reads as
        // a failed send instead of sitting pending forever.
        outbox.exhausted(maxAttempts).forEach { entry ->
            entry.messageId?.let { runCatching { messages.markErrorById(it) } }
        }
        outbox.purgeExhausted(maxAttempts)
    }

    suspend fun recordSentAttachment(
        toKey: String,
        caption: String,
        attachmentPath: String,
        attachmentMime: String,
        attachmentSize: Long,
        attachmentName: String?,
        protocol: Protocol,
        type: MessageType = MessageType.FILE,
    ): Message {
        val now = System.currentTimeMillis()
        // Capture image dimensions so our OWN sent photo reserves its bubble
        // height too (read here, before any sealing, from the plaintext file).
        val (imgW, imgH) = decodeImageBounds(attachmentPath, attachmentMime)
        val entity = MessageEntity(
            id = java.util.UUID.randomUUID().toString(),
            fromKey = selfKey,
            toKey = toKey,
            peerKey = toKey,
            body = caption,
            timestamp = now,
            protocol = protocol.name,
            type = type.name,
            delivered = protocol != Protocol.LOCAL,
            outgoing = true,
            attachmentPath = attachmentPath,
            attachmentMime = attachmentMime,
            attachmentSize = attachmentSize,
            attachmentName = attachmentName,
            attachmentWidth = imgW,
            attachmentHeight = imgH,
        )
        val sealed = entity.applySealing()
        messages.insert(sealed)
        return sealed.toDomain(plaintextOverride = caption)
    }

    suspend fun recordReceivedAttachment(
        fromKey: String,
        caption: String,
        attachmentPath: String,
        attachmentMime: String,
        attachmentSize: Long,
        attachmentName: String?,
        protocol: Protocol,
        type: MessageType = MessageType.FILE,
        // Group-attachment override — mirrors recordReceived's
        // peerKeyOverride: when set, the row's peerKey is the group's
        // conversation id instead of the sender's pubkey, so the
        // attachment surfaces in GroupChatScreen.
        peerKeyOverride: String? = null,
        // Originating-transport identifiers; persisted on the row
        // so the post-receive purge and the boot-time sweep can
        // both target the right SimpleX item. See recordReceived
        // for the full contract.
        sourceItemId: Long? = null,
        sourceChatRef: String? = null,
    ): Message {
        // Read the image's pixel dimensions from the PLAINTEXT download BEFORE
        // sealAttachmentInPlace encrypts/moves it — cheap (inJustDecodeBounds
        // reads only the header). Lets the chat bubble reserve the exact height
        // so a photo-heavy chat doesn't reflow on scroll.
        val (imgW, imgH) = decodeImageBounds(attachmentPath, attachmentMime)
        // Upsert by source item id. Two cases land here with a row already
        // occupying this id:
        //   (1) a deferred placeholder (recordDeferredAttachment) the user
        //       just tapped to download — fill the real file into it;
        //   (2) a redelivered completion of a file we already stored.
        // Both must UPDATE that row, never insert a duplicate (the old code
        // always inserted a fresh UUID, so a redelivered completion stacked
        // a second copy of the same attachment). We preserve the existing
        // id / timestamp / sealed caption so the message keeps its place in
        // the conversation and we don't re-seal an already-sealed body —
        // applySealing no-ops once sealedBody is set, so a copy() that keeps
        // body/sealedBody is correct as-is.
        if (sourceItemId != null) {
            val existing = messages.findBySimplexItemId(sourceItemId)
            if (existing != null) {
                val (finalPath, sealedDek) = sealAttachmentInPlace(attachmentPath)
                val updated = existing.copy(
                    type = type.name,
                    attachmentPath = finalPath,
                    attachmentMime = attachmentMime,
                    attachmentSize = attachmentSize,
                    attachmentName = attachmentName,
                    attachmentWidth = imgW ?: existing.attachmentWidth,
                    attachmentHeight = imgH ?: existing.attachmentHeight,
                    sealedDek = sealedDek,
                    pendingPurgeChatRef = sourceChatRef,
                    delivered = true,
                )
                messages.insert(updated)
                return updated.toDomain(plaintextOverride = caption)
            }
        }
        val now = System.currentTimeMillis()
        // Encrypt-in-place when a PIN pubkey is configured: the file
        // landed plaintext at [attachmentPath] under SimpleX core's
        // appFilesFolder; we move it under the profile's chat_enc/
        // directory, AES-GCM under a fresh random DEK, and stash the
        // sealed DEK on the row. SimpleX has already delivered and
        // doesn't read this file again.
        val (finalPath, sealedDek) = sealAttachmentInPlace(attachmentPath)
        val entity = MessageEntity(
            id = java.util.UUID.randomUUID().toString(),
            fromKey = fromKey,
            toKey = selfKey,
            peerKey = peerKeyOverride ?: fromKey,
            body = caption,
            timestamp = now,
            protocol = protocol.name,
            type = type.name,
            delivered = true,
            outgoing = false,
            attachmentPath = finalPath,
            attachmentMime = attachmentMime,
            attachmentSize = attachmentSize,
            attachmentName = attachmentName,
            attachmentWidth = imgW,
            attachmentHeight = imgH,
            sealedDek = sealedDek,
            simplexItemId = sourceItemId,
            pendingPurgeChatRef = sourceChatRef,
        )
        val sealed = entity.applySealing()
        messages.insert(sealed)
        return sealed.toDomain(plaintextOverride = caption)
    }

    /**
     * Record a PLACEHOLDER row for an attachment whose auto-download was
     * deferred (the Wi-Fi / type / size / trust gate declined to pull it).
     * The file does NOT exist yet — [attachmentPath] is null on the row —
     * but we persist its metadata so the chat shows a tap-to-download bubble
     * instead of the file silently vanishing.
     *
     * WHY a placeholder at all: a file invitation skips the normal
     * message-emit path in the transport (only the COMPLETION of a download
     * writes a chat row). So if we simply don't `/freceive`, no row is ever
     * written and the user never learns a file was sent. This synthesizes
     * the row up front from the invitation metadata.
     *
     * Dedups by [sourceItemId] like [recordReceived] — the core re-delivers
     * invitations, and we must not stack duplicate placeholders. When the
     * user taps to download, [recordReceivedAttachment] finds THIS row by
     * the same [sourceItemId] and fills the path into it (see the upsert at
     * the top of that function).
     *
     * Does NOT seal a file (there is none) and never sets sealedDek; the
     * caption is sealed at rest exactly like any other inbound body.
     */
    suspend fun recordDeferredAttachment(
        fromKey: String,
        caption: String,
        attachmentMime: String,
        attachmentSize: Long,
        attachmentName: String?,
        type: MessageType,
        protocol: Protocol,
        peerKeyOverride: String? = null,
        sourceItemId: Long,
    ): Message {
        val existing = messages.findBySimplexItemId(sourceItemId)
        if (existing != null) return existing.toDomain(plaintextOverride = caption)
        val entity = MessageEntity(
            id = java.util.UUID.randomUUID().toString(),
            fromKey = fromKey,
            toKey = selfKey,
            peerKey = peerKeyOverride ?: fromKey,
            body = caption,
            timestamp = System.currentTimeMillis(),
            protocol = protocol.name,
            type = type.name,
            delivered = true,
            outgoing = false,
            attachmentPath = null,
            attachmentMime = attachmentMime,
            attachmentSize = attachmentSize,
            attachmentName = attachmentName,
            simplexItemId = sourceItemId,
        )
        val sealed = entity.applySealing()
        messages.insert(sealed)
        return sealed.toDomain(plaintextOverride = caption)
    }

    /** Read an image's pixel dimensions from its file HEADER only
     *  (inJustDecodeBounds — no pixel decode, so it's cheap) so the chat
     *  bubble can reserve the right display height before the image loads.
     *  Returns (null, null) for non-images or on any failure. */
    private fun decodeImageBounds(path: String, mime: String?): Pair<Int?, Int?> {
        if (mime?.startsWith("image/") != true) return null to null
        return runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                opts.outWidth to opts.outHeight
            } else null to null
        }.getOrDefault(null to null)
    }

    /** Move a plaintext attachment file under the profile's
     *  chat_enc/ directory, encrypting it with a fresh per-file DEK
     *  that's itself sealed under the PIN pubkey. Returns the new
     *  encrypted path + the sealed DEK to store on the row. Falls
     *  through to (originalPath, null) when no PIN is configured or
     *  encryption fails — caller persists the plaintext form. */
    private fun sealAttachmentInPlace(originalPath: String): Pair<String, ByteArray?> {
        val dir = chatEncAttachmentsDir ?: return originalPath to null
        if (!sealing.canSeal) return originalPath to null
        val src = java.io.File(originalPath)
        if (!src.exists()) return originalPath to null
        val sealed = ChatAttachmentSeal.sealAttachment(src, sealing, dir) ?: return originalPath to null
        return sealed.encryptedPath to sealed.sealedDek
    }

    /** Clear the pending-purge marker after a successful
     *  [app.aether.aegis.transport.Transport.purgeOriginal]. ProtocolManager
     *  and the boot-time sweep both call this on confirmation. */
    suspend fun clearPendingPurge(simplexItemId: Long) =
        messages.clearPendingPurge(simplexItemId)

    /** Inbound rows with an outstanding transport-mirror purge.
     *  Boot-time sweep walks these and retries. */
    suspend fun pendingPurges(): List<MessageEntity> = messages.pendingPurges()

    /** The conversation key (peerKey) of the message carrying SimpleX
     *  [itemId], or null. Used by the send-side Transactional-Delivery
     *  purge to resolve a chatRef once a sent item is delivered. */
    suspend fun peerKeyForSimplexItemId(itemId: Long): String? =
        messages.findBySimplexItemId(itemId)?.peerKey

    /** The full local row carrying SimpleX [itemId], or null. The
     *  send-side purge uses it to resolve the chatRef AND to skip
     *  attachments (their SimpleX item must survive until the file
     *  transfer completes — a separate purge handles those). */
    suspend fun messageBySimplexItemId(itemId: Long): MessageEntity? =
        messages.findBySimplexItemId(itemId)

    /**
     * Encrypt the file payload of a freshly-uploaded outgoing message
     * once SimpleX core has confirmed it doesn't need to read the
     * source file again. Wired from SimpleXTransport's
     * `sndFileComplete` / `sndFileCompleteXFTP` handlers.
     *
     * No-op when:
     *   - no PIN pubkey configured (canSeal false)
     *   - itemId doesn't match any row
     *   - row has no attachment, OR
     *   - row is already sealed (sealedDek non-null), OR
     *   - source file is already gone / unreadable
     *
     * Returns true iff the file was sealed (row updated). The boolean
     * is mostly diagnostic — callers don't depend on it.
     */
    suspend fun sealOutgoingAttachmentByItemId(itemId: Long): Boolean {
        if (!sealing.canSeal) return false
        val row = messages.findBySimplexItemId(itemId) ?: return false
        val attachmentPath = row.attachmentPath ?: return false
        if (row.sealedDek != null) return false
        val (newPath, sealedDek) = sealAttachmentInPlace(attachmentPath)
        if (sealedDek == null) return false
        messages.insert(row.copy(attachmentPath = newPath, sealedDek = sealedDek))
        return true
    }

    /**
     * Resolve a viewable path for the message's attachment.
     *
     * Returns:
     *   - null if the message has no attachment
     *   - [Message.attachmentPath] verbatim for plaintext attachments
     *     (no [Message.sealedDek] or not the `.enc` form)
     *   - the absolute path of a freshly-decrypted scratch file under
     *     `cacheDir/chat_dec` for PIN-encrypted attachments (one
     *     copy per call; cleared on every lock event via
     *     [app.aether.aegis.lock.PinSession.addOnLockListener])
     *   - null while LOCKED (no priv available to unseal the DEK)
     *
     * Blocking — performs AES-GCM decrypt synchronously. NEVER call
     * this on the main thread for a sealed attachment: a large file
     * (e.g. a multi-tens-of-MB document) takes seconds to decrypt and
     * will ANR-kill the UI. Compose callers must resolve it on
     * Dispatchers.IO (e.g. via produceState) and key the result on
     * (msg.id, msg.sealedDek.contentHashCode()) so it isn't re-run on
     * every recomposition. Bug-fix 2026-06-05: a 48 MB sealed file
     * decrypted inside remember{} on the composition thread froze the
     * chat on open-after-unlock until Android killed it.
     */
    fun viewableAttachmentPath(msg: Message, context: android.content.Context): String? {
        val path = msg.attachmentPath ?: return null
        val sealedDek = msg.sealedDek
        if (sealedDek == null || !ChatAttachmentSeal.isEncrypted(path)) return path
        return ChatAttachmentSeal.unsealAttachmentToTemp(path, sealedDek, sealing, context)
    }

    /**
     * Whether sealed attachment ciphertext can currently be unsealed —
     * i.e. a REAL-PIN session is active and holds the priv. The chat UI
     * uses this to decide between a "locked" chip (can't decrypt now)
     * and a "decrypting…" chip (can, but the IO is still in flight), so
     * the two states don't collapse into one ambiguous placeholder.
     * Cheap + synchronous; safe to read during composition.
     */
    val canUnsealAttachments: Boolean
        get() = sealing.canUnseal

    /** Whether a seal keypair is configured for this profile (a recovery
     *  phrase is enrolled). When false, sealed content can be neither written
     *  nor re-sealed — so a post-import re-seal must WAIT for a key rather than
     *  consume + discard the one-shot bundle (which left imported chats
     *  permanently unreadable). */
    val canSeal: Boolean
        get() = sealing.canSeal

    // ------------------------------------------------------------------
    // Portable-backup re-seal bridge
    //
    // The backup must NOT carry the seal master key. Instead, EXPORT
    // unseals the five sealed fields with the current (old) profile key
    // and writes them as PLAINTEXT into the backup (protected only by the
    // backup password's AES-GCM envelope). IMPORT, running under the NEW
    // profile's key, re-seals that plaintext with `trySeal` — so the
    // restored data ends up readable under the new phrase, and the old
    // master key never left the old device.
    //
    // Field codes in the serialized bundle:
    //   0 = message.sealedBody   1 = message.sealedDek
    //   2 = member_status.sealedPayload
    //   3 = knownPeer.sealedName 4 = knownPeer.sealedAnnounced
    // ------------------------------------------------------------------

    /**
     * Serialize every sealed field UNSEALED, for the backup. Returns null
     * if the profile can't unseal (locked / no key) — the caller then
     * writes no bundle and the backup is data-less for sealed content.
     * Runs while the source profile is unlocked (export requires PIN).
     */
    suspend fun exportResealBundle(): ByteArray? {
        if (!sealing.canUnseal) return null
        val items = ArrayList<Triple<Int, String, ByteArray>>()
        for (m in messages.allSealed()) {
            m.sealedBody?.let { sealing.tryUnseal(it)?.let { p -> items += Triple(0, m.id, p) } }
        }
        for (m in messages.allSealedAttachments()) {
            m.sealedDek?.let { sealing.tryUnseal(it)?.let { p -> items += Triple(1, m.id, p) } }
        }
        for (s in statuses.allSealed()) {
            s.sealedPayload?.let { sealing.tryUnseal(it)?.let { p -> items += Triple(2, s.peerKey, p) } }
        }
        for (pe in knownPeers.all()) {
            pe.sealedName?.let { sealing.tryUnseal(it)?.let { p -> items += Triple(3, pe.publicKey, p) } }
            pe.sealedAnnounced?.let { sealing.tryUnseal(it)?.let { p -> items += Triple(4, pe.publicKey, p) } }
        }
        val bos = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bos).use { out ->
            out.writeInt(items.size)
            for ((code, pk, plain) in items) {
                out.writeByte(code)
                out.writeUTF(pk)
                out.writeInt(plain.size)
                out.write(plain)
            }
        }
        return bos.toByteArray()
    }

    /**
     * Apply a bundle produced by [exportResealBundle] over the matching
     * restored rows, so the imported content becomes readable on THIS
     * profile. Two modes, chosen by whether the destination has a seal key:
     *
     *   - [SealingPolicy.canSeal] TRUE → RE-SEAL each field under this
     *     profile's pub (the original design: import into a profile that has
     *     its own recovery phrase). Only the pub is needed, so this works
     *     even while locked.
     *   - canSeal FALSE → write the bundle's PLAINTEXT straight into the
     *     plaintext columns and null the sealed blobs. A no-phrase profile
     *     stores plaintext at rest anyway; without this the imported rows
     *     stay sealed under the OLD device's key and read as permanently
     *     blank (user report 2026-06-15: "imported backup, chats encrypted,
     *     can't set nickname"). If the user later enrols a phrase,
     *     [sealLegacyPlaintext] seals these rows under the new key.
     *
     * Matches rows by primary key; rows absent from the imported DB are
     * skipped. Returns the number of fields applied.
     *
     * Field codes (see [exportResealBundle]): 0 message body, 1 attachment
     * DEK, 2 member-status payload, 3 peer name, 4 peer announced profile.
     */
    suspend fun applyResealBundle(bundle: ByteArray): Int {
        val reseal = sealing.canSeal
        // Parse the flat bundle into per-row plaintext, keyed by primary key.
        data class Field(val code: Int, val pk: String, val plain: ByteArray)
        val fields = ArrayList<Field>()
        java.io.DataInputStream(java.io.ByteArrayInputStream(bundle)).use { ins ->
            val n = ins.readInt()
            repeat(n) {
                val code = ins.readByte().toInt()
                val pk = ins.readUTF()
                val len = ins.readInt()
                val plain = ByteArray(len).also { ins.readFully(it) }
                fields += Field(code, pk, plain)
            }
        }
        val sealedMsgs = messages.allSealed().associateBy { it.id }
        val sealedAtt = messages.allSealedAttachments().associateBy { it.id }
        val sealedStat = statuses.allSealed().associateBy { it.peerKey }
        val peers = knownPeers.all().associateBy { it.publicKey }

        // Accumulate the post-transform entities here, then write once.
        val msgUpdates = HashMap<String, MessageEntity>()
        val statUpdates = HashMap<String, MemberStatusEntity>()
        val peerUpdates = HashMap<String, KnownPeerEntity>()
        // .enc files to delete only AFTER the DB write commits — so a failed
        // write can't strand a row pointing at an already-deleted file.
        val encToDelete = ArrayList<String>()

        for ((code, pk, plain) in fields) {
            when (code) {
                0 -> {  // message body
                    val row = msgUpdates[pk] ?: sealedMsgs[pk] ?: continue
                    msgUpdates[pk] = if (reseal) {
                        val s = sealing.trySeal(plain) ?: continue
                        row.copy(sealedBody = s, body = "")
                    } else {
                        row.copy(body = String(plain, Charsets.UTF_8), sealedBody = null)
                    }
                }
                1 -> {  // attachment DEK
                    val row = msgUpdates[pk] ?: sealedMsgs[pk] ?: sealedAtt[pk] ?: continue
                    if (reseal) {
                        val s = sealing.trySeal(plain) ?: continue
                        msgUpdates[pk] = row.copy(sealedDek = s)
                    } else {
                        // No key to re-seal the DEK under → decrypt the .enc
                        // file in place with the bundled raw DEK and repoint
                        // the row to the plaintext file.
                        val encPath = row.attachmentPath
                        if (encPath == null ||
                            !app.aether.aegis.lock.ChatAttachmentSeal.isEncrypted(encPath)
                        ) {
                            // Already plaintext / nothing to decrypt — just
                            // drop the now-meaningless DEK.
                            msgUpdates[pk] = row.copy(sealedDek = null)
                        } else {
                            val newPath = app.aether.aegis.lock.ChatAttachmentSeal
                                .decryptAttachmentToPlain(
                                    encPath, plain, java.io.File(encPath).parentFile ?: continue,
                                ) ?: continue
                            encToDelete += encPath
                            msgUpdates[pk] = row.copy(attachmentPath = newPath, sealedDek = null)
                        }
                    }
                }
                2 -> {  // member-status payload (JSON of the sensitive fields)
                    val row = statUpdates[pk] ?: sealedStat[pk] ?: continue
                    statUpdates[pk] = if (reseal) {
                        val s = sealing.trySeal(plain) ?: continue
                        row.copy(sealedPayload = s)
                    } else {
                        statusFromPlainPayload(row, plain)
                    }
                }
                3 -> {  // peer display name
                    val row = peerUpdates[pk] ?: peers[pk] ?: continue
                    peerUpdates[pk] = if (reseal) {
                        val s = sealing.trySeal(plain) ?: continue
                        row.copy(sealedName = s, displayName = "")
                    } else {
                        row.copy(displayName = String(plain, Charsets.UTF_8), sealedName = null)
                    }
                }
                4 -> {  // peer announced profile (JSON: name / bio / avatar)
                    val row = peerUpdates[pk] ?: peers[pk] ?: continue
                    peerUpdates[pk] = if (reseal) {
                        val s = sealing.trySeal(plain) ?: continue
                        row.copy(sealedAnnounced = s, announcedName = null, announcedBio = null, announcedAvatarPath = null)
                    } else {
                        peerFromPlainAnnounced(row, plain)
                    }
                }
            }
        }

        val applied = msgUpdates.size + statUpdates.size + peerUpdates.size
        val writes: suspend () -> Unit = {
            msgUpdates.values.forEach { messages.insert(it) }
            statUpdates.values.forEach { statuses.upsert(it) }
            peerUpdates.values.forEach { knownPeers.upsert(it) }
        }
        if (db != null) db.withTransaction { writes() } else writes()
        // Safe now the rows point at the plaintext files.
        encToDelete.forEach { runCatching { java.io.File(it).delete() } }
        return applied
    }

    /** Plaintext-path inverse of [sealStatusForWrite]: parse the bundle's
     *  status JSON into the inline columns and clear [sealedPayload]. Mirror
     *  of [unsealStatusForRead] but fed the bundle plaintext directly. */
    private fun statusFromPlainPayload(row: MemberStatusEntity, plain: ByteArray): MemberStatusEntity {
        val json = runCatching { org.json.JSONObject(String(plain, Charsets.UTF_8)) }
            .getOrNull() ?: return row.copy(sealedPayload = null)
        return MemberStatusEntity(
            peerKey = row.peerKey,
            batteryLevel = json.optIntOrNull("batteryLevel"),
            isCharging = json.optBoolOrNull("isCharging"),
            networkType = json.optStringOrNull("networkType"),
            signalStrength = json.optIntOrNull("signalStrength"),
            latitude = json.optDoubleOrNull("latitude"),
            longitude = json.optDoubleOrNull("longitude"),
            lastActive = row.lastActive,
            lastPacketMs = row.lastPacketMs,
            heartRate = json.optIntOrNull("heartRate"),
            hrv = json.optIntOrNull("hrv"),
            spo2 = json.optIntOrNull("spo2"),
            appVersion = json.optStringOrNull("appVersion"),
            sealedPayload = null,
        )
    }

    /** Plaintext-path inverse of [sealAnnouncedForWrite]: parse the bundle's
     *  announced-profile JSON into the cleartext columns and clear
     *  [sealedAnnounced]. */
    private fun peerFromPlainAnnounced(row: KnownPeerEntity, plain: ByteArray): KnownPeerEntity {
        val json = runCatching { org.json.JSONObject(String(plain, Charsets.UTF_8)) }
            .getOrNull() ?: return row.copy(sealedAnnounced = null)
        return row.copy(
            announcedName = json.optStringOrNull("announcedName"),
            announcedBio = json.optStringOrNull("announcedBio"),
            announcedAvatarPath = json.optStringOrNull("announcedAvatarPath"),
            sealedAnnounced = null,
        )
    }

    /**
     * Build the domain [Message]. [plaintextOverride] short-circuits
     * unsealing when the caller already holds the plaintext (insert
     * paths know it because they just got it from the network/UI).
     *
     * For pure read paths (Flow observers / search), the override is
     * null and [readableBody] decides:
     *   - sealedBody == null → return the legacy plaintext [body]
     *   - sealedBody != null AND unlocked → unseal + UTF-8 decode
     *   - sealedBody != null AND locked → return "" (UI shows blank,
     *     which is the correct locked-view UX)
     */
    private fun MessageEntity.toDomain(plaintextOverride: String? = null) = Message(
        id = id,
        from = fromKey,
        to = toKey,
        content = plaintextOverride ?: readableBody(),
        timestamp = timestamp,
        // Tolerate an unknown/legacy protocol string (e.g. a value from a
        // build before an enum member was removed, or a corrupt row)
        // instead of throwing IllegalArgumentException and crashing the
        // whole read. SimpleX is the sane default for a stored message.
        protocol = runCatching { Protocol.valueOf(protocol) }.getOrDefault(Protocol.SIMPLEX),
        type = MessageType.valueOf(type),
        attachmentPath = attachmentPath,
        attachmentMime = attachmentMime,
        attachmentSize = attachmentSize,
        attachmentName = attachmentName,
        attachmentWidth = attachmentWidth,
        attachmentHeight = attachmentHeight,
        status = status,
        deliveredAt = deliveredAt,
        sealedAt = sealedAt,
        readAt = readAt,
        simplexItemId = simplexItemId,
        messageDna = messageDna,
        replyToItemId = replyToItemId,
        replyToDna = replyToDna,
        reactionsJson = reactionsJson,
        pinned = pinned,
        edited = edited,
        burnTtlSeconds = burnTtlSeconds,
        sealedDek = sealedDek,
    )

    /**
     * Replace the entity's plaintext [body] with a sealed ciphertext
     * blob when a seal-pub is configured. Idempotent — if [sealedBody]
     * is already set or [body] is empty (attachment-only message),
     * the entity passes through unchanged.
     */
    private fun MessageEntity.applySealing(): MessageEntity {
        if (sealedBody != null) return this
        if (body.isEmpty()) return this
        val ciphertext = sealing.trySeal(body.toByteArray(Charsets.UTF_8)) ?: return this
        return copy(body = "", sealedBody = ciphertext)
    }

    /**
     * One-shot sweep that seals every row still holding PLAINTEXT in a
     * sealable column — message bodies, member-status payloads, and
     * contact name + announced identity — under the now-available
     * phrase-rooted seal key, nulling the cleartext.
     *
     * These are rows written BEFORE a recovery phrase was enrolled (or
     * while the seal key was unavailable). The per-write seal helpers
     * only ever sealed NEW writes, so without this sweep that
     * pre-enrolment history stayed plaintext in the SQLCipher DB forever
     * — contradicting the "encrypted at rest behind your key" guarantee.
     * The migrations' comments referenced a `sealLegacyPlaintextContacts()`
     * that never actually existed; this is it. (Security review
     * 2026-06-07.)
     *
     * Invoked on every REAL unlock (AegisApp observes [PinSession]). A
     * no-op when locked ([SealingPolicy.canSeal] false) or once everything
     * is already sealed — the message query targets only still-plaintext
     * rows, and the status/peer passes skip already-sealed rows, so
     * steady-state cost is three cheap scans. Best-effort per table: a
     * failure on one table never blocks the others.
     */
    suspend fun sealLegacyPlaintext() {
        if (!sealing.canSeal) return
        // Messages — plaintext body not yet sealed.
        runCatching {
            messages.unsealedWithBody().forEach { row ->
                val sealed = row.applySealing()
                if (sealed !== row) messages.insert(sealed)
            }
        }
        // Member status — sensitive payload still in cleartext columns.
        runCatching {
            statuses.unsealedStatuses().forEach { row ->
                val sealed = row.sealStatusForWrite()
                if (sealed !== row) statuses.upsert(sealed)
            }
        }
        // Known peers — local nickname + announced identity still cleartext.
        runCatching {
            knownPeers.all().forEach { row ->
                var out = row
                if (out.sealedName == null) out = out.sealNameForWrite()
                if (out.sealedAnnounced == null) out = out.sealAnnouncedForWrite()
                if (out !== row) knownPeers.upsert(out)
            }
        }
    }

    /** Decode [body] / [sealedBody] for display. Null-safe — never
     *  throws. Locked-but-sealed rows surface as the empty string,
     *  the same shape an unstyled bubble would render to. */
    private fun MessageEntity.readableBody(): String {
        val sealed = sealedBody ?: return body
        val plain = sealing.tryUnseal(sealed) ?: return ""
        return String(plain, Charsets.UTF_8)
    }

    // ---------------- MemberStatus sealing ----------------
    //
    // Two-tier scheme: presence-timestamp columns ([lastActive] and
    // [lastPacketMs]) stay in plaintext so the presence indicator
    // (StatusDot.peerStatusFor) keeps working when the PIN session
    // is locked. The truly sensitive payload — battery, location,
    // signal, vitals, app version — goes into [sealedPayload] under
    // the PIN pubkey; locked sessions read those as null (status
    // grid renders blanks, which is the right locked-view UX).
    //
    // Why timestamps stay plaintext: "last seen at T" is already
    // inferable from "the last chat message you got from this peer
    // was at T-ish"; sealing it costs the presence dot working
    // cold-boot, which broke remote-access / peer dashboards
    // showing every device as Offline until the user re-unlocked.
    //
    // While locked, [patchLocation] / [patchDeviceStatus] /
    // [patchWatchStatus] read a "null cur" for sensitive fields, so
    // the merge degrades to "this packet's fields only, sensitive
    // others null". Acceptable — the next status packet is full-
    // state and refills.

    private fun MemberStatusEntity.sealStatusForWrite(): MemberStatusEntity {
        if (!sealing.canSeal) return this
        val json = org.json.JSONObject().apply {
            putOrNull("batteryLevel", batteryLevel)
            putOrNull("isCharging", isCharging)
            putOrNull("networkType", networkType)
            putOrNull("signalStrength", signalStrength)
            putOrNull("latitude", latitude)
            putOrNull("longitude", longitude)
            putOrNull("heartRate", heartRate)
            putOrNull("hrv", hrv)
            putOrNull("spo2", spo2)
            putOrNull("appVersion", appVersion)
        }
        val sealed = sealing.trySeal(json.toString().toByteArray(Charsets.UTF_8)) ?: return this
        return MemberStatusEntity(
            peerKey = peerKey,
            batteryLevel = null,
            isCharging = null,
            networkType = null,
            signalStrength = null,
            latitude = null,
            longitude = null,
            // Presence timestamps stay plaintext (see header comment).
            lastActive = lastActive,
            lastPacketMs = lastPacketMs,
            heartRate = null,
            hrv = null,
            spo2 = null,
            appVersion = null,
            sealedPayload = sealed,
        )
    }

    private fun MemberStatusEntity.unsealStatusForRead(): MemberStatusEntity {
        val sealed = sealedPayload ?: return this
        val plain = sealing.tryUnseal(sealed) ?: return this
        val json = runCatching { org.json.JSONObject(String(plain, Charsets.UTF_8)) }
            .getOrNull() ?: return this
        return MemberStatusEntity(
            peerKey = peerKey,
            batteryLevel = json.optIntOrNull("batteryLevel"),
            isCharging = json.optBoolOrNull("isCharging"),
            networkType = json.optStringOrNull("networkType"),
            signalStrength = json.optIntOrNull("signalStrength"),
            latitude = json.optDoubleOrNull("latitude"),
            longitude = json.optDoubleOrNull("longitude"),
            // Prefer the inline column (new format). Pre-fix rows
            // were sealed with lastActive=0 wiped to the inline
            // column and the real value tucked into JSON — fall
            // back so those rows don't permanently show Offline.
            lastActive = if (lastActive > 0) lastActive else json.optLong("lastActive", 0L),
            lastPacketMs = lastPacketMs ?: json.optLongOrNull("lastPacketMs"),
            heartRate = json.optIntOrNull("heartRate"),
            hrv = json.optIntOrNull("hrv"),
            spo2 = json.optIntOrNull("spo2"),
            appVersion = json.optStringOrNull("appVersion"),
            sealedPayload = null,
        )
    }

    // ---- Contact-graph sealing ----------

    /** Seal the local contact name into [KnownPeerEntity.sealedName] and
     *  empty [displayName]. No-op if sealing isn't configured or the name
     *  is already empty (nothing to protect). Only the cached pub is
     *  needed, so this works while locked. */
    private fun KnownPeerEntity.sealNameForWrite(): KnownPeerEntity {
        if (!sealing.canSeal || displayName.isEmpty()) return this
        val sealed = sealing.trySeal(displayName.toByteArray(Charsets.UTF_8)) ?: return this
        return copy(displayName = "", sealedName = sealed)
    }

    /** Seal the announced-profile fields into [sealedAnnounced] and empty
     *  the cleartext columns. Wholesale (the caller passes the full set),
     *  so no unseal-merge is needed and it works while locked. */
    private fun KnownPeerEntity.sealAnnouncedForWrite(): KnownPeerEntity {
        if (!sealing.canSeal) return this
        if (announcedName == null && announcedBio == null && announcedAvatarPath == null) return this
        val json = org.json.JSONObject().apply {
            putOrNull("announcedName", announcedName)
            putOrNull("announcedBio", announcedBio)
            putOrNull("announcedAvatarPath", announcedAvatarPath)
        }
        val sealed = sealing.trySeal(json.toString().toByteArray(Charsets.UTF_8)) ?: return this
        return copy(
            announcedName = null,
            announcedBio = null,
            announcedAvatarPath = null,
            sealedAnnounced = sealed,
        )
    }

    /** Decrypt [sealedName] + [sealedAnnounced] back into the cleartext
     *  fields for the UI. While LOCKED (no priv) the sealed fields stay
     *  empty/null and the UI shows its locked placeholder — exactly how a
     *  sealed message body renders. Applied at every contact read funnel. */
    private fun KnownPeerEntity.unsealIdentityForRead(): KnownPeerEntity {
        var out = this
        sealedName?.let { sn ->
            sealing.tryUnseal(sn)?.let { out = out.copy(displayName = String(it, Charsets.UTF_8)) }
        }
        sealedAnnounced?.let { sa ->
            sealing.tryUnseal(sa)?.let { plain ->
                runCatching { org.json.JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull()?.let { j ->
                    out = out.copy(
                        announcedName = j.optStringOrNull("announcedName"),
                        announcedBio = j.optStringOrNull("announcedBio"),
                        announcedAvatarPath = j.optStringOrNull("announcedAvatarPath"),
                    )
                }
            }
        }
        return out
    }

    private fun org.json.JSONObject.putOrNull(key: String, value: Any?) {
        if (value == null) put(key, org.json.JSONObject.NULL)
        else put(key, value)
    }

    private fun org.json.JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    private fun org.json.JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    private fun org.json.JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }

    private fun org.json.JSONObject.optBoolOrNull(key: String): Boolean? =
        if (isNull(key) || !has(key)) null else optBoolean(key)

    private fun org.json.JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key, "").ifEmpty { null }

    suspend fun setSimplexItemId(messageId: String, itemId: Long) =
        messages.setSimplexItemId(messageId, itemId)
    suspend fun setStatusByItemId(itemId: Long, status: String) =
        messages.setStatusByItemIdIfAdvances(itemId, status, statusRank(status))

    /** Rank for monotonic tick progression. Mirrors the CASE ladders in
     *  MessageDao.setStatusByItemIdIfAdvances / markReadUpTo so a stale or
     *  out-of-order status event can never regress a row's tick. The order
     *  is: relay (sent) → recipient device (delivered) → sealed at rest
     *  (sealed) → opened (read); 'error' is terminal and outranks all. */
    private fun statusRank(status: String): Int = when (status) {
        "sending" -> 0
        "sent" -> 1
        "delivered" -> 2
        "sealed" -> 3
        "read" -> 4
        "error" -> 5
        else -> 0
    }
    suspend fun markReadUpTo(peerKey: String, maxItemId: Long) =
        messages.markReadUpTo(peerKey, maxItemId)

    /** Store the minted/parsed message DNA on a local row. */
    suspend fun setMessageDna(id: String, dna: Long) =
        messages.setMessageDna(id, dna)

    // DNA-keyed receipt twins (the cross-device read-tick fix) — match our
    // own outbound rows by the shared sender-minted DNA the peer echoed back,
    // not by a per-device itemId. See MessageDao for the rank guards.
    suspend fun markReadUpToDna(peerKey: String, maxDna: Long, at: Long = System.currentTimeMillis()) =
        messages.markReadUpToDna(peerKey, maxDna, at)
    suspend fun markDeliveredUpToDna(peerKey: String, maxDna: Long) =
        messages.markDeliveredUpToDna(peerKey, maxDna)
    suspend fun markSealedUpToDna(peerKey: String, maxDna: Long) =
        messages.markSealedUpToDna(peerKey, maxDna)
    /** Exact-DNA receipt application (SPEC_LOSSY_LINK_RESILIENCE — no
     *  watermarks). Marks ONLY the named message, never inferring lower DNAs. */
    suspend fun markDeliveredByDna(peerKey: String, dna: Long, at: Long = System.currentTimeMillis()) =
        messages.markDeliveredByDna(peerKey, dna, at)
    suspend fun markSealedByDna(peerKey: String, dna: Long, at: Long = System.currentTimeMillis()) =
        messages.markSealedByDna(peerKey, dna, at)
    /** Outbound DNAs to [peerKey] whose tick is stuck below sealed and older
     *  than [olderThanMs] — the reconciliation query set, newest-first, capped. */
    suspend fun stuckOutboundDnas(peerKey: String, olderThanMs: Long, limit: Int): List<Long> =
        messages.stuckOutboundDnas(peerKey, olderThanMs, limit)
    /** Highest inbound DNA for a peer — what a DNA read receipt advertises. */
    suspend fun maxInboundDna(peerKey: String): Long? =
        messages.maxInboundDna(peerKey)

    /** "Reached your device" confirmation ([aegis:delivered]) — flips our
     *  outgoing rows to a single BRIGHT tick. */
    suspend fun markDeliveredUpTo(peerKey: String, maxItemId: Long) =
        messages.markDeliveredUpTo(peerKey, maxItemId)

    /** "Sealed at rest in their vault" confirmation ([aegis:sealed]) —
     *  flips our outgoing rows to bright + dim ✓✓. */
    suspend fun markSealedUpTo(peerKey: String, maxItemId: Long) =
        messages.markSealedUpTo(peerKey, maxItemId)
    suspend fun maxInboundItemId(peerKey: String): Long? =
        messages.maxInboundItemId(peerKey)

    suspend fun setMessagePinned(id: String, pinned: Boolean) =
        messages.setPinned(id, pinned)

    suspend fun setMessageEditedBody(id: String, body: String) {
        val sealed = if (body.isEmpty()) null
        else sealing.trySeal(body.toByteArray(Charsets.UTF_8))
        if (sealed != null) messages.setEditedBody(id, "", sealed)
        else messages.setEditedBody(id, body, null)
    }

    /** Wipe control-message rows that leaked into chat before the
     *  outbound + inbound filters were complete. Returns row count
     *  for logging. */
    suspend fun purgeControlMessages(): Int = messages.purgeControlMessages()

    fun observePinnedMessages(peerKey: String) =
        messages.observePinned(peerKey).map { rows -> rows.map { it.toDomain() } }

    private companion object {
        private const val STORY_TTL_MS = 24L * 60 * 60 * 1000  // 24 hours
    }
    suspend fun setReactionsByItemId(itemId: Long, json: String?) =
        messages.setReactionsByItemId(itemId, json)
    suspend fun reactionsByItemId(itemId: Long): String? =
        messages.reactionsByItemId(itemId)
}

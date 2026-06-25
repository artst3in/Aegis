package app.aether.aegis.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted message. Mirrors app.aether.aegis.core.Message but with DB-friendly types
 * (enums as TEXT, no nullable transports of complex types).
 */
@Entity(
    tableName = "messages",
    indices = [
        Index("peerKey"),
        Index("timestamp"),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val fromKey: String,
    val toKey: String,
    val peerKey: String,
    val body: String,
    val timestamp: Long,
    val protocol: String,
    val type: String,
    val delivered: Boolean,
    val outgoing: Boolean,
    /** Absolute path of the file payload (or null if this is just text). */
    val attachmentPath: String? = null,
    /** MIME type (image, video, application, etc.). */
    val attachmentMime: String? = null,
    /** Bytes on disk. */
    val attachmentSize: Long? = null,
    /** Original filename (display only). */
    val attachmentName: String? = null,
    /** Pixel dimensions of an image attachment (null for non-images / legacy
     *  rows / not-yet-known). Used by the chat bubble to RESERVE the exact
     *  display height from the aspect ratio BEFORE the image decodes, so a
     *  photo-heavy chat doesn't reflow (and jump on scroll-up) as each picture
     *  loads. Non-destructive nullable column add — DbRebuild leaves old rows
     *  null and they fall back to the unreserved (legacy) layout. */
    val attachmentWidth: Int? = null,
    val attachmentHeight: Int? = null,
    /** SimpleX core's chatItem.meta.itemId — null for non-SimpleX or
     *  legacy rows. Used to match chatItemsStatusesUpdated events
     *  back to a local message. */
    val simplexItemId: Long? = null,
    /** Aegis message DNA — the transport-agnostic message identity, stored as
     *  epoch-nanoseconds (the comparable form; the wire carries the ISO-8601
     *  string, see [app.aether.aegis.protocol.MessageDna]). Sender-minted and
     *  identical on both ends, unlike [simplexItemId] which is a per-device
     *  counter. Null for non-Aegis (vanilla MCText) peers and legacy rows
     *  predating the envelope. Used to match read/delivered/sealed receipts
     *  across devices (the cross-device id-space mismatch that broke ticks). */
    val messageDna: Long? = null,
    /** When this message is a reply AND both ends speak the chat envelope, the
     *  [messageDna] of the quoted message — the transport-agnostic reply link
     *  (Phase 2), carried in the envelope so it survives a transport swap,
     *  unlike [replyToItemId] which is a per-device SimpleX id. Null for
     *  non-replies and for the vanilla native-quote path. */
    val replyToDna: Long? = null,
    /** When this message is a REPLY, the [simplexItemId] of the message it
     *  quotes — the link that powers tap-a-citation → jump-to-original (and
     *  the citation-chase back-stack). Null for non-replies. Set on send
     *  (the quoted message's simplexItemId) and on receive (from the
     *  SimpleX quotedItem reference). */
    val replyToItemId: Long? = null,
    /** Lifecycle status string — see comment above the entity. */
    val status: String = "sent",
    /** Per-rung receipt timestamps (epoch-ms), for the message Info view.
     *  Stamped when the matching DNA receipt is applied (delivered / sealed) or
     *  the read receipt lands. Null until that rung is reached, and null on
     *  legacy / vanilla rows that climb via the itemId path. The "sent" time is
     *  [timestamp]. Adding these is a non-destructive column add — DbRebuild
     *  restores existing rows column-by-column, leaving these null. */
    val deliveredAt: Long? = null,
    val sealedAt: Long? = null,
    val readAt: Long? = null,
    /** JSON-encoded map of emoji → count for reactions. Empty/null
     *  when there are no reactions. Example: {"👍":1,"❤️":2}. */
    val reactionsJson: String? = null,
    /** True when the user has pinned this message in the chat — it
     *  shows up in a sticky banner at the top of the conversation,
     *  tap to scroll back. Per-chat: each peerKey can have any
     *  number of pinned messages; the banner highlights the most
     *  recent one. */
    val pinned: Boolean = false,
    /** True if this message was edited after sending. Surfaces an
     *  "edited" tag next to the timestamp. We don't keep edit
     *  history — privacy. */
    val edited: Boolean = false,
    /**
     * Burn-after-reading viewing window in seconds.
     * Null = ordinary message. 0 = unlimited-until-close.
     * >0 = auto-close after N seconds of viewing.
     *
     * Until the recipient opens the BURN bubble, [body] is the raw
     * marker + payload; the bubble UI renders just a fire icon. When
     * the viewer closes, the row is DELETED on both devices via a
     * burn-receipt round trip.
     */
    val burnTtlSeconds: Int? = null,
    /**
     * libsodium crypto_box_seal of the message body bytes (UTF-8)
     * under the REAL-PIN-derived X25519 pubkey ([app.aether.aegis.lock.LockStore.sealPub]).
     * When non-null, [body] is empty — the plaintext only exists in
     * memory after the user enters their PIN and
     * [app.aether.aegis.lock.PinSession] holds the priv.
     *
     * Null on legacy rows written before this feature shipped, or
     * when no PIN/sealPub is configured. The Repository falls back
     * to reading [body] directly in that case.
     */
    val sealedBody: ByteArray? = null,
    /**
     * Sealed DEK for a PIN-encrypted attachment. When non-null,
     * [attachmentPath] points at a `.enc` file under the profile's
     * `chat_enc/` directory; readers unseal this blob via
     * [app.aether.aegis.lock.PinSession.priv] to obtain the per-file AES-GCM key
     * and decrypt to a scratch path under `cacheDir/chat_dec`.
     *
     * Null on plaintext attachments — either legacy rows written
     * before this feature shipped, or rows persisted while no PIN
     * was configured. Readers detect the encrypted form via
     * [app.aether.aegis.lock.ChatAttachmentSeal.isEncrypted].
     */
    val sealedDek: ByteArray? = null,
    /**
     * Originating transport's chatRef ("@<contactId>" for direct
     * SimpleX, "#<simplexGroupId>" for group, etc.) when a
     * `/_delete item <ref> <id> internal` purge of the transport's
     * own mirror copy is still pending. Null = nothing to chase.
     *
     * Set on inbound writes by the receive pipeline; flipped back
     * to null by ProtocolManager after a successful
     * [app.aether.aegis.transport.Transport.purgeOriginal]. The boot-time
     * sweep walks every row with a non-null value and retries,
     * closing the crash window between persist and purge.
     */
    val pendingPurgeChatRef: String? = null,
)

/**
 * Last known status for a family member.
 * Single row per peer (replace-on-update).
 */
@Entity(tableName = "member_status")
data class MemberStatusEntity(
    @PrimaryKey val peerKey: String,
    val batteryLevel: Int?,
    val isCharging: Boolean?,
    val networkType: String?,
    val signalStrength: Int?,
    val latitude: Double?,
    val longitude: Double?,
    /** Sender's last FOREGROUND activity timestamp (InAppActivity
     *  heartbeat) — i.e. when they were last looking at the screen.
     *  Drives the Online state. */
    val lastActive: Long,
    /** Sender's last STATUS PACKET generation timestamp. Proves the
     *  background service is alive even if the user isn't in the
     *  app. Drives the Away vs Offline distinction. Null when the
     *  packet is from a build that predates routine presence. */
    val lastPacketMs: Long? = null,
    val heartRate: Int?,
    val hrv: Int?,
    val spo2: Int?,
    /** Peer's Aegis app version (YYYY.MM.BBB cargo string), carried
     *  in the `version` field of the `[aegis:status]` envelope. Only
     *  populated for Trusted peers — Emergency / Untrusted drop the
     *  field at send time. Stays null when the peer is on a build
     *  older than the version-broadcasting one. */
    val appVersion: String? = null,
    /**
     * libsodium crypto_box_seal of the row's sensitive payload (a
     * JSON object carrying batteryLevel / isCharging / networkType /
     * signalStrength / latitude / longitude / lastActive /
     * lastPacketMs / heartRate / hrv / spo2 / appVersion) under the
     * REAL-PIN-derived pubkey.
     *
     * When non-null, the inline sensitive columns are NULL (or 0L
     * for [lastActive]) and the real values live inside this blob.
     * Repository decrypts on read when [app.aether.aegis.lock.PinSession] is
     * unlocked, else the row surfaces as "no status known" — which
     * is the correct UX for the locked state (radar shows blanks).
     *
     * Null on legacy rows / locked-state-write fallbacks.
     */
    val sealedPayload: ByteArray? = null,
)

/**
 * Pending outbound messages — held here until both protocols are down
 * is no longer true. Retry counter so we stop hammering forever.
 */
@Entity(
    tableName = "outbox",
    indices = [Index("nextAttemptAt")],
)
data class OutboxEntity(
    @PrimaryKey val id: String,
    val toKey: String,
    val body: String,
    val type: String,
    val createdAt: Long,
    val attempts: Int,
    val nextAttemptAt: Long,
    /** Local messages-table row this queued send belongs to. When set, the
     *  offline message already has a visible chat bubble (status "sending" =
     *  hex clock); on a successful drain we ADVANCE that same row (link its
     *  simplexItemId/DNA, flip protocol) instead of inserting a second row — so
     *  there's exactly one bubble that goes clock → ✓ when it finally sends.
     *  Null for legacy entries enqueued before this link existed; those fall
     *  back to the old record-a-new-row drain. */
    val messageId: String? = null,
)

/**
 * Peers we've explicitly trusted via QR pairing. The LAN transport
 * will only accept inbound traffic from a public key in this table.
 *
 * `displayName` is our LOCAL nickname for the peer (override of
 * whatever they call themselves). `announcedName` / `announcedBio` /
 * `announcedAvatarPath` mirror their actual profile as SimpleX
 * delivers it to us; the contact-detail sheet shows both so the
 * user can decide whether to use the announced name verbatim or
 * rename them locally.
 */
// Status column lives on MessageEntity (see below); the enum string is one of
// (monotonic ladder, see Repository.statusRank):
//   "sending"  — local-only, send hasn't returned yet            ⌛ dim
//   "sent"     — on the relay (SimpleX sndSent)                  ✓  dim
//   "delivered"— reached the peer's device; Aegis Protocol
//                [aegis:delivered] receipt                       ✓  bright
//   "sealed"   — sealed at rest in the peer's vault; Aether
//                Protocol [aegis:sealed] receipt                 ✓✓ bright+dim
//   "read"     — peer opened it; [aegis:read] receipt            ✓✓ bright+bright
//   "error"    — send failed                                     !
//   "received" — incoming, no status surfaced
// Note: SimpleX's own sndRcvd ack does NOT drive any tick — the transport
// copy is purged at "sent", so every rung above "sent" is an Aegis Protocol
// receipt the recipient sends back. We never show "error" inline — it's
// surfaced via lastSendError in the Status tab.

@Entity(tableName = "known_peers")
data class KnownPeerEntity(
    @PrimaryKey val publicKey: String,
    val displayName: String,
    val addedAt: Long,
    val lastSeenAt: Long?,
    /** What the peer calls themselves (from their profile broadcast). */
    val announcedName: String? = null,
    val announcedBio: String? = null,
    /** Local path to the peer's avatar copy (downloaded from their profile). */
    val announcedAvatarPath: String? = null,
    /** Pinned contacts sort to the top of the chat list. */
    val pinned: Boolean = false,
    /** Muted contacts don't fire message notifications (sos still does). */
    val muted: Boolean = false,
    /** Per-chat disappearing-messages TTL in seconds. Null = off. */
    val disappearingTtl: Long? = null,
    /** True when the user has marked this contact's safety code verified. */
    val verified: Boolean = false,
    /** Trust tier — single source of truth for what this contact
     *  receives. UNTRUSTED is the default for any newly added
     *  contact. Stored as the [TrustTier] enum's name() so Room
     *  serialises it as TEXT. */
    val trustTier: String = "UNTRUSTED",
    /** Per-contact remote-access grant (the remote-access prompt on
     *  trust promotion). When true, this contact may
     *  drive LOCATE / SIREN / LOCK / WIPE on this device via the
     *  remote-access protocol — but ONLY ever with the owner's real PIN,
     *  which they must still supply per command. Decided at the moment of
     *  Trusted promotion (a dedicated prompt), NOT a default: a contact
     *  can be fully Trusted (location, presence, sos) yet have remote
     *  control OFF. Changeable later from the contact detail screen.
     *  RemoteAccessHandler.handleAuth fails closed when this is false even
     *  if the PIN is correct. */
    val remoteAccessEnabled: Boolean = false,
    /** Orthogonal to [trustTier] — when true, the contact cannot send
     *  any message into Aegis. Their tier becomes moot while blocked.
     *  Demotion to Untrusted does NOT auto-block. */
    val blocked: Boolean = false,
    /** Timestamp of the first inbound sos from this peer that we
     *  surfaced the "you are part of their safety plan" onboarding
     *  banner for. Null = no sos from them ever, OR we haven't shown
     *  the banner yet. Set the moment the banner is displayed; checked
     *  on every subsequent inbound sos to suppress redundant
     *  onboarding. Recipient-side only — sender never sees this. */
    val firstSosShownAt: Long? = null,
    /** True once we've received any inbound message from this peer
     *  whose body starts with `[aegis:…]`. Gates outbound control
     *  envelopes (typing, status, location, sos) so non-Aegis
     *  SimpleX clients never see raw `[aegis:typing]` etc. in their
     *  chat view. Flip happens automatically in
     *  SimpleXTransport.handleNewChatItems on classifier match. */
    val isAegis: Boolean = false,
    /** Last shield tier this peer announced (None / Bronze / Silver /
     *  Gold / Cyan, the names of [app.aether.aegis.admin.ShieldTier]). Null
     *  means they've never sent an `[aegis:tier]` envelope yet —
     *  either because we haven't seen one (their reward visual stays
     *  off) or because their tier is in flux. ChatList renders the
     *  peer's avatar frame in this colour when present so the user
     *  sees their family's progress without opening anything. */
    val peerReportedTier: String? = null,
    /** Last crown-shimmer style this peer announced (0 = white→cyan glow,
     *  1 = diffraction rainbow foil, 2 = thin-film oil-slick foil — the values
     *  of [app.aether.aegis.prefs.ExperimentalPrefs.crownStyle]). Null means
     *  they've never sent an `[aegis:crown]` envelope yet, in which case their
     *  medal renders in the default glow rather than the viewer's own chosen
     *  style. Only visible at the Cyan tier (the shine is the Cyan-crown
     *  reward); carried regardless of tier so it's already known when they
     *  reach Cyan. */
    val peerReportedCrownStyle: Int? = null,
    /** Capabilities this peer announced (`[aegis:caps]<csv>`), e.g. "chat,
     *  editdna" — the [app.aether.aegis.protocol.AegisCaps] tokens for the
     *  features their build supports. Null means they've never announced (a
     *  pre-capability build, or not yet received), which gates every
     *  capability OFF — we fall back to the degraded path (plain MCText, native
     *  quote/edit) for them. Additive: unknown tokens are ignored. */
    val peerCapabilities: String? = null,
    /** Dead columns retained from pre-trust-model schema. Nothing reads
     *  them after the Trust Model commit; left in place because Room
     *  cannot drop a column without a table rebuild and the cost of
     *  carrying a few nullable booleans forever is zero. */
    val shareLocation: Boolean = true,
    val shareBattery: Boolean? = null,
    val shareNetwork: Boolean? = null,
    val shareSignal: Boolean? = null,
    /** Custom notification sound for inbound messages from this peer,
     *  as a content:// or android.resource:// URI string. Null = use
     *  the default channel sound. Drives a per-peer NotificationChannel
     *  spun up on demand (Android 8+ requires sound to be set on the
     *  channel, not the notification). */
    val notificationSoundUri: String? = null,
    /** Optional folder tag for chat-list organisation (Telegram-style
     *  folders). Null = unfiled / "All".
     *  Free-form string; user creates folders by naming them. Filter
     *  + sort applied at the ChatListScreen level. */
    val folder: String? = null,
    /** Phrase-rooted seal of the LOCAL contact name (the user's nickname,
     *  often the real one — "Mum"). Part of contact-graph sealing.
     *  Written by addKnownPeer (pairing, may run while locked) + rename
     *  (user action, unlocked). When set, [displayName] is emptied. */
    val sealedName: ByteArray? = null,
    /** Phrase-rooted seal of the peer's ANNOUNCED profile — a JSON
     *  snapshot of {announcedName, announcedBio, announcedAvatarPath}.
     *  Written by updatePeerProfile (background, may run while locked).
     *  Two separate blobs (name vs announced) so each independent writer
     *  touches only its own — you can SEAL while locked (cached pub) but
     *  cannot UNSEAL to merge (no priv), so a shared blob couldn't be
     *  updated field-by-field while locked. Routing fields (publicKey,
     *  trustTier, blocked, timestamps) stay cleartext so sos/presence
     *  filtering keeps working while locked. */
    val sealedAnnounced: ByteArray? = null,
    /** The peer's Ed25519 control-channel PUBLIC key (hex), learned from the
     *  hello bootstrap. Null until they've sent a hello carrying it.
     *  1:1 control commands ride the unsigned x.aegis type and are NOT
     *  verified against this key; it survives as the "channel bootstrapped"
     *  marker HelloBroadcaster reads (non-null → stop re-greeting) and as the
     *  key material the future signed GROUP path will need. */
    val controlPubKey: String? = null,
)

/**
 * "Secure Notes" — Telegram-style save-to-self. Local-only, encrypted
 * by the same SQLCipher passphrase as the rest of the DB. No network,
 * never leaves the device.
 */
@Entity(
    tableName = "secure_notes",
    indices = [Index("createdAt"), Index("body")],
)
data class SecureNoteEntity(
    @PrimaryKey val id: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Optional pin so a note can be kept at the top of the list. */
    val pinned: Boolean = false,
    /** Optional single attachment — image / video / file. Path is
     *  absolute, inside filesDir/app_files so the existing render
     *  widgets (AsyncImage, VideoBubble, FileChip) work unchanged. */
    val attachmentPath: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val attachmentName: String? = null,
    /** Vault slot (hidden volume). "normal"
     *  for the main vault, "duress" for the hidden vault that's
     *  reachable only via the duress vault PIN. Migration sets
     *  every pre-existing row to "normal". Filtered at query time
     *  via SecureNoteDao.observeBySlot. */
    val keySlot: String = "normal",
    /** Optional folder tag for vault organisation. Null = unfiled
     *  / "All". Free-form text; user creates folders by naming
     *  them. Scoped to the active slot via observeBySlot, so the
     *  normal and hidden volumes have independent folder trees. */
    val folder: String? = null,
    /** Per-entry AES-GCM ciphertext of the body bytes (the
     *  crypto-deniability follow-up). Null when no
     *  vault PIN was set at save time — the [body] column then
     *  holds plaintext. Non-null when encrypted — [body] is empty
     *  and the plaintext lives in [cipher] under the slot's key.
     *  Includes the 16-byte GCM auth tag. */
    val cipher: ByteArray? = null,
    /** Random 12-byte IV that pairs with [cipher]. Null iff cipher
     *  is null. */
    val iv: ByteArray? = null,
)

// GroupEntity + GroupMemberEntity moved to :feature:groups as part
// of the group-module isolation work. AegisDatabase imports them
// from there.

/**
 * Ephemeral 24-hour posts ("stories") visible to every paired peer.
 *
 *   - id              wire identifier (UUID minted by the author)
 *   - authorKey       pubkey of the poster; equal to selfKey for own posts
 *   - body            text caption (may be empty if the story is photo-only)
 *   - attachmentPath  local path to the JPG/PNG (null for text-only stories)
 *   - attachmentMime  MIME of the image
 *   - createdAt       epoch millis — used to gate the 24 h visibility window
 *   - viewed          true once the local user has opened this story
 *
 * Auto-deleted by a background sweep after `createdAt + 24 h`.
 */
@Entity(
    tableName = "stories",
    indices = [Index("authorKey"), Index("createdAt")],
)
data class StoryEntity(
    @PrimaryKey val id: String,
    val authorKey: String,
    val body: String,
    val attachmentPath: String? = null,
    val attachmentMime: String? = null,
    val createdAt: Long,
    val viewed: Boolean = false,
)

/**
 * A message the user wrote now but wants Aegis to send later. The
 * ScheduledMessageWorker polls this table once a minute and ships
 * anything whose scheduledFor has passed.
 */
@Entity(
    tableName = "scheduled_messages",
    indices = [Index("scheduledFor")],
)
data class ScheduledMessageEntity(
    @PrimaryKey val id: String,
    val toKey: String,
    val body: String,
    val scheduledFor: Long,
    val createdAt: Long,
)

/**
 * Hourly bucket of this profile's network usage. Background sampler
 * (see AegisApp's network ticker) reads TrafficStats.getUid{Rx,Tx}Bytes
 * delta every minute and adds it to the current hour's row. Backs
 * the network graphs in the Status / Settings NetworkCard. 720 rows
 * = a 30 day window at hourly resolution.
 *
 * hourEpochMs is the start of the hour (floor(now / 3 600 000) *
 * 3 600 000), so two samples in the same hour upsert into one row.
 */
@Entity(
    tableName = "network_history",
    indices = [Index(value = ["hourEpochMs"], unique = true)],
)
data class NetworkHistoryEntity(
    @PrimaryKey val hourEpochMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
)

/**
 * A 1:1 invitation link that has been generated but not yet used by
 * a peer. Without this tracking, invitation links otherwise live on
 * the relay forever with no way to see, revoke, or expire them. One
 * row per outstanding link.
 *
 * [connId] is the SimpleX agent connection id (pccConnId) returned
 * when the link was created; it is the **revoke handle** — the
 * transport tears down the pending handshake on the relay via
 * `/_delete :<connId>`. The row is removed when the peer connects
 * (handleContactConnected, best-effort by connection id), when the
 * user revokes, or when it auto-expires (the WorkManager job).
 */
@Entity(tableName = "pending_invitations")
data class PendingInvitationEntity(
    @PrimaryKey val connId: Long,
    /** The invitation URI, kept so the row can be re-shared / copied
     *  from the pending list. */
    val link: String,
    /** The temporary "pending-XXXXX" label generated at creation. */
    val label: String,
    /** Creation time (epoch ms) — drives both the list ordering and
     *  the auto-expire cutoff. */
    val createdAt: Long,
)

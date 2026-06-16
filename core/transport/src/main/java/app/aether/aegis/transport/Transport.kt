package app.aether.aegis.transport

import app.aether.aegis.core.MessageType
import app.aether.aegis.core.Protocol
import kotlinx.coroutines.flow.Flow

/**
 * Pluggable message transport. A `Transport` knows how to deliver a
 * payload from this device to another family member, and surfaces
 * inbound payloads as a Flow.
 *
 * Implementations:
 *   - SimpleXTransport: peer-to-peer SimpleX. Available when both devices
 *     are reachable on the public internet. Primary transport.
 *   - LANTransport: direct device-to-device on the local network
 *     (NaCl box over mDNS). Fallback when both devices share a network.
 *
 * `ProtocolManager` picks one based on health: SimpleX → LAN → Local-queue.
 */
interface Transport {
    val protocol: Protocol

    /** True if this transport believes it can deliver right now. */
    val isHealthy: Boolean

    /** True while start() is in progress. A first start can take several
     *  seconds (SimpleX DB open + passphrase migration), during which
     *  isHealthy is still false. The watchdog / onResume nudge consult
     *  this so they don't mistake "still coming up" for "down" and pelt
     *  an already-initialising transport with redundant restart attempts
     *  (the boot-time start/stop churn). Default false for transports
     *  with no meaningful warm-up. */
    val isStarting: Boolean get() = false

    /** Inbound message stream. Emit one per received message. */
    fun inbound(): Flow<InboundMessage>

    /**
     * Attempt delivery. Returns true on success (handed off to the network),
     * false if delivery failed and the caller should retry / try another transport.
     */
    suspend fun send(toKey: String, body: String, type: MessageType): Boolean

    /** Start any background coroutines / network connections. */
    suspend fun start() {}

    /** Tear down. */
    suspend fun stop() {}

    /**
     * Purge the transport's own mirror copy of a previously-delivered
     * inbound message. Called by ProtocolManager once the Aegis-side
     * (PIN-sealed) persistence has committed, so the only durable
     * plaintext copy is removed from the transport-internal store
     * (SimpleX core's chat.db, etc.). Default = no-op;
     * SimpleXTransport overrides with `/_delete item <ref> <id>
     * internal`. Returns true on success / no-op, false if the
     * transport failed to delete (caller logs but does not retry).
     */
    suspend fun purgeOriginal(chatRef: String, itemId: Long): Boolean = true
}

data class InboundMessage(
    val fromKey: String,
    val body: String,
    val type: MessageType,
    val timestamp: Long,
    /** Set for group messages: the conversation peerKey the message
     *  should be stored under (`"group:<aegis-uuid>"`). null = a 1:1
     *  direct message that's stored under fromKey as today. Routed
     *  into the messages table via Repository.recordReceived's
     *  `peerKeyOverride` parameter; consumed by notifyMessage to
     *  open the right chat tab on tap. */
    val groupKey: String? = null,
    /** SimpleX chatItem.meta.itemId for the source row in the
     *  underlying transport's own message store. Populated by
     *  SimpleXTransport; null for LAN / LOCAL paths.
     *  ProtocolManager uses this AFTER successful persistence into
     *  the Aegis (PIN-sealed) messages table to ask the originating
     *  transport to purge its plaintext mirror copy
     *  (`/_delete item <chatRef> <itemId> internal`). */
    val sourceItemId: Long? = null,
    /** Opaque-to-the-transport-layer chat reference that pairs with
     *  [sourceItemId] for the purge call. For SimpleX this is the
     *  literal `"@<contactId>"` or `"#<groupId>"` string the core
     *  expects in its delete command. */
    val sourceChatRef: String? = null,
    /** When this is a reply, the source-transport itemId of the quoted
     *  message (SimpleX chatItem.quotedItem.itemId). Stored as
     *  MessageEntity.replyToItemId so a tap on the citation can jump to
     *  the original. null for non-replies / non-SimpleX paths. */
    val replyToItemId: Long? = null,
    /** Aegis message DNA in epoch-nanoseconds, decoded from the chat envelope
     *  (`x.aegis.chat`) when the sender is an Aegis peer. The transport-agnostic
     *  message identity both ends share; stored as MessageEntity.messageDna and
     *  echoed in read/delivered/sealed receipts. null for vanilla MCText. */
    val messageDna: Long? = null,
    /** When this is an Aegis-envelope reply, the DNA of the quoted message
     *  (envelope `replyToDna`). Stored as MessageEntity.replyToDna; powers the
     *  receiver-side citation jump. null for non-replies / vanilla. */
    val replyToDna: Long? = null,
)

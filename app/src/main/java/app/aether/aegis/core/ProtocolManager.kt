package app.aether.aegis.core

import app.aether.aegis.data.Repository
import app.aether.aegis.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Heart of Aegis. Picks the best healthy transport for outbound messages,
 * routes inbound to the Repository, drains the outbox when transports recover.
 *
 * State machine (computed from transport health, not commanded):
 *   SIMPLEX_ACTIVE    → SimpleX healthy
 *   RECONNECTING      → SimpleX just came back, draining outbox
 *   DISCONNECTED      → no transport healthy; messages queue locally
 */
class ProtocolManager(
    private val repository: Repository,
    private val transports: List<Transport>,
    private val selfKey: String,
    private val onInbound: ((app.aether.aegis.transport.InboundMessage) -> Unit)? = null,
) {

    enum class State {
        SIMPLEX_ACTIVE,
        RECONNECTING,
        DISCONNECTED,
    }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Single-thread dispatcher for transport.start() / transport.stop()
    // calls only. Dispatchers.IO can pick up two `scope.launch` bodies
    // on two different worker threads — and if start() launches AFTER
    // stop() but its body wins the race, the SimpleXTransport
    // re-entry guard returns "already healthy, no-op" (transport not
    // yet torn down by the stop body that's still queued). Then the
    // stop body lands and the transport ends up down with no
    // re-start. Forcing lifecycle work onto a parallelism=1
    // sub-dispatcher serialises submission order, so a stop() that
    // arrives before a start() is always the one to run first.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val lifecycleDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var healthJob: Job? = null
    private var inboundJob: Job? = null
    private var outboxJob: Job? = null
    // Serialises drainOutbox(): it's fired both by the 10 s outboxJob AND
    // by recomputeState()'s RECONNECTING transition, and recomputeState
    // itself runs concurrently (health loop + onResume nudge). Without
    // this, two drains can interleave — both read pendingOutbox(), both
    // send()+removeOutbox() the same entry — delivering a queued message
    // twice or losing it. The lock makes the second drain see the first's
    // already-removed rows, so each entry is processed once.
    private val drainLock = Mutex()

    /** Per-peer high-water mark of the inbound itemId we've already sent a
     *  `[aegis:sealed]` confirmation for, so
     *  a busy conversation doesn't re-confirm the same items. */
    private val sealReceiptWatermarks = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Per-peer high-water mark of the inbound itemId we've already sent an
     *  Aegis Protocol `[aegis:delivered]` confirmation for. Parallel to
     *  [sealReceiptWatermarks] — delivered ("reached this device") is the
     *  rung below sealed, fired first; one watermark each keeps a busy
     *  conversation from re-confirming the same items on either rung. */
    private val deliveredReceiptWatermarks = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val healthCheckIntervalMs = 30_000L
    private val outboxDrainIntervalMs = 10_000L

    /** After this many failed delivery attempts (~5 minutes at our
     *  exponential backoff cap), give up on the message. Stops stale
     *  entries from burning battery forever — see drainOutbox. */
    private val MAX_OUTBOX_ATTEMPTS = 30

    /** Receipt reconciliation (SPEC_LOSSY_LINK_RESILIENCE). Only messages older
     *  than this are queried, so still-in-flight recent sends aren't treated as
     *  "stuck". The per-peer query is capped so a long offline backlog can't
     *  blow up the control frame. */
    private val RECONCILE_STUCK_AGE_MS = 30_000L
    private val RECONCILE_QUERY_CAP = 32

    /** SOS fast-retry cadence — see [sendSos]. We re-attempt
     *  every [SOS_RETRY_INTERVAL_MS] until a transport accepts or
     *  [SOS_RETRY_WINDOW_MS] elapses, after which the 30 s SOS
     *  re-broadcast loop takes over. The window is sized just past the
     *  SOS loop's own cadence so there's no coverage gap, and the
     *  interval is short enough that the alert leaves within a second or
     *  two of connectivity returning. */
    private val SOS_RETRY_INTERVAL_MS = 1_500L
    private val SOS_RETRY_WINDOW_MS = 32_000L

    /** Message types that belong in the chat-messages table (i.e. the
     *  user-visible inbox). Everything else is a control message routed
     *  to its own surface — map, status grid, sos banner, stories. */
    private val USER_VISIBLE_MESSAGE_TYPES = setOf(
        app.aether.aegis.core.MessageType.TEXT,
        app.aether.aegis.core.MessageType.PHOTO,
        app.aether.aegis.core.MessageType.VOICE,
        app.aether.aegis.core.MessageType.FILE,
        app.aether.aegis.core.MessageType.BURN,
    )

    /** Burn-after-reading marker shape:
     *  `[aegis:burn:<ttl>:<senderRowId>]<text>`. */
    private val BURN_MARKER = Regex(
        """\[aegis:burn:(\d+):([^]]+)](.*)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun start() {
        if (healthJob?.isActive == true) {
            app.aether.aegis.simplex.ConnectionLog.log("ProtocolManager", "start(): already running")
            return
        }
        app.aether.aegis.simplex.ConnectionLog.log(
            "ProtocolManager",
            "start(): launching ${transports.size} transports",
        )

        scope.launch(lifecycleDispatcher) { transports.forEach { it.start() } }

        // Boot-time pending-purge sweep (PR 3 of the geofence-canary-
        // duress split). Walks rows that were persisted to Aegis but
        // crashed before the originating transport's mirror copy was
        // deleted. Retries each via the appropriate transport's
        // purgeOriginal; on success, clears the pending marker.
        // Bounded — typically zero rows during healthy operation.
        scope.launch {
            runCatching {
                val pending = repository.pendingPurges()
                if (pending.isEmpty()) return@runCatching
                app.aether.aegis.simplex.ConnectionLog.log(
                    "ProtocolManager",
                    "pending-purge sweep: ${pending.size} row(s) to retry",
                )
                for (row in pending) {
                    val itemId = row.simplexItemId ?: continue
                    val chatRef = row.pendingPurgeChatRef ?: continue
                    // All pendingPurgeChatRef-tracked rows are
                    // SimpleX-originated (only SimpleXTransport
                    // populates the fields). Pick the SIMPLEX
                    // transport; if absent, leave the marker.
                    val transport = transports.firstOrNull {
                        it.protocol == app.aether.aegis.core.Protocol.SIMPLEX
                    } ?: continue
                    if (!transport.isHealthy) continue
                    val purged = runCatching {
                        transport.purgeOriginal(chatRef, itemId)
                    }.getOrDefault(false)
                    if (purged) repository.clearPendingPurge(itemId)
                }
            }.onFailure {
                app.aether.aegis.simplex.ConnectionLog.warn(
                    "ProtocolManager",
                    "pending-purge sweep failed: $it",
                )
            }
        }

        inboundJob = scope.launch {
            transports.forEach { transport ->
                launch {
                    transport.inbound().collect { msg ->
                        // Control messages (LOCATION, STATUS, SOS, STORY)
                        // must NOT land in the chat-messages table — they
                        // are metadata for other surfaces (map, status grid,
                        // sos banner, stories strip). A bug report found
                        // that previously every inbound landed in chat,
                        // turning routine location pings into spam.
                        //
                        // Source itemId + chat-ref travel with the message;
                        // bind to locals up front (InboundMessage lives in
                        // :core:transport, so its nullable fields can't
                        // smart-cast cross-module) so the delivered receipt can
                        // fire before persistence.
                        val srcItemId = msg.sourceItemId
                        val srcChatRef = msg.sourceChatRef
                        // Aegis message DNA (epoch-nanos), present for x.aegis.chat
                        // senders. When set, the delivered/seal receipts echo the
                        // DNA (cross-device-correct) instead of our per-device
                        // itemId; vanilla/legacy messages fall back to the itemId.
                        val srcDna = msg.messageDna
                        // Delivery confirmation (Aegis Protocol): the message
                        // reached this device. Fired BEFORE the at-rest seal so
                        // the sender's tick reaches 'delivered' (one bright
                        // tick) even if the seal below fails — the honest signal
                        // is "on your device, not yet sealed". Only for messages
                        // that get a bubble (BURN + user-visible types); routine
                        // control pings carry no tick and must not double the
                        // receipt traffic. Direct chats only, watermarked per
                        // peer. Sender maps it via markDeliveredUpTo by the
                        // itemId we echo.
                        val isUserMessage =
                            msg.type == app.aether.aegis.core.MessageType.BURN ||
                                msg.type in USER_VISIBLE_MESSAGE_TYPES
                        if (isUserMessage && srcItemId != null && msg.groupKey == null) {
                            val peer = msg.fromKey
                            if (srcItemId > (deliveredReceiptWatermarks[peer] ?: 0L)) {
                                deliveredReceiptWatermarks[peer] = srcItemId
                                val receipt = if (srcDna != null) "[aegis:delivereddna:$srcDna]"
                                              else "[aegis:delivered:$srcItemId]"
                                runCatching {
                                    sendMessage(
                                        to = peer,
                                        content = receipt,
                                        type = MessageType.STATUS,
                                    )
                                }.onFailure {
                                    app.aether.aegis.simplex.ConnectionLog.warn(
                                        "ProtocolManager", "delivered-receipt send failed: $it",
                                    )
                                }
                            }
                        }
                        var persisted = false
                        if (msg.type == app.aether.aegis.core.MessageType.BURN) {
                            // Pull the TTL out of the marker so the
                            // viewer knows how long it has to display
                            // the message. Body keeps the marker — the
                            // bubble renders a fire icon, and the
                            // viewer re-parses the marker to recover
                            // the clean text + the sender's row id
                            // for the burn-receipt round trip.
                            val ttl = BURN_MARKER.matchEntire(msg.body)
                                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                            repository.recordReceived(
                                fromKey = msg.fromKey,
                                body = msg.body,
                                protocol = transport.protocol,
                                type = msg.type,
                                burnTtlSeconds = ttl,
                                peerKeyOverride = msg.groupKey,
                                sourceItemId = msg.sourceItemId,
                                sourceChatRef = msg.sourceChatRef,
                                replyToItemId = msg.replyToItemId,
                                messageDna = msg.messageDna,
                                replyToDna = msg.replyToDna,
                            )
                            persisted = true
                        } else if (msg.type in USER_VISIBLE_MESSAGE_TYPES) {
                            repository.recordReceived(
                                fromKey = msg.fromKey,
                                body = msg.body,
                                protocol = transport.protocol,
                                type = msg.type,
                                peerKeyOverride = msg.groupKey,
                                sourceItemId = msg.sourceItemId,
                                sourceChatRef = msg.sourceChatRef,
                                replyToItemId = msg.replyToItemId,
                                messageDna = msg.messageDna,
                                replyToDna = msg.replyToDna,
                            )
                            persisted = true
                        }
                        // PIN-seal pipeline (PR 3 of the geofence-canary-
                        // duress split): once the Aegis-side row is
                        // committed (and sealed under the PIN pubkey by
                        // the Repository), ask the originating transport
                        // to purge its OWN plaintext mirror — SimpleX
                        // core's chat.db, etc. The pending-purge marker
                        // we just persisted (via sourceChatRef) lets the
                        // boot-time sweep retry on next start if we die
                        // before purge confirms. (srcItemId / srcChatRef bound
                        // to locals above.)
                        if (persisted && srcItemId != null && srcChatRef != null) {
                            val purged = runCatching {
                                transport.purgeOriginal(srcChatRef, srcItemId)
                            }.onFailure {
                                app.aether.aegis.simplex.ConnectionLog.warn(
                                    "ProtocolManager",
                                    "purgeOriginal failed for ${transport.protocol} " +
                                        "ref=$srcChatRef id=$srcItemId: $it",
                                )
                            }.getOrDefault(false)
                            if (purged) repository.clearPendingPurge(srcItemId)
                        }
                        // Seal confirmation (Aegis Protocol):
                        // now that this message is sealed at rest in OUR DB,
                        // tell the sender — that is what the bright + dim ✓✓
                        // mean. Watermarked per peer so a busy chat doesn't
                        // re-confirm. Direct chats only (group seal receipts
                        // are a separate concern). The sender's handler maps
                        // it via markSealedUpTo by the itemId we echo.
                        if (persisted && srcItemId != null && msg.groupKey == null) {
                            val peer = msg.fromKey
                            if (srcItemId > (sealReceiptWatermarks[peer] ?: 0L)) {
                                sealReceiptWatermarks[peer] = srcItemId
                                val receipt = if (srcDna != null) "[aegis:sealeddna:$srcDna]"
                                              else "[aegis:sealed:$srcItemId]"
                                runCatching {
                                    sendMessage(
                                        to = peer,
                                        content = receipt,
                                        type = MessageType.STATUS,
                                    )
                                }.onFailure {
                                    app.aether.aegis.simplex.ConnectionLog.warn(
                                        "ProtocolManager", "seal-receipt send failed: $it",
                                    )
                                }
                            }
                        }
                        onInbound?.invoke(msg)
                    }
                }
            }
        }

        healthJob = scope.launch {
            recomputeState(initial = true)
            while (isActive) {
                delay(healthCheckIntervalMs)
                recomputeState()
                // Watchdog: any transport that has flipped to !isHealthy
                // since the last tick gets one restart attempt. Catches
                // the silent-pump-death case (SimpleXCore.recvWait
                // throws, finally block flips isHealthy=false, UI was
                // claiming "online" until this tick fires). The
                // lifecycleDispatcher is single-threaded so the start
                // call serialises against any concurrent stop() that
                // a Pause notification might issue.
                //
                // Skip transports that are still in their first start()
                // (isStarting): a SimpleX DB open + migration can take
                // several seconds, during which isHealthy is legitimately
                // false. Treating that as "down" stacked a redundant
                // restart on top of the in-flight init — the boot-time
                // start/stop churn the logs showed.
                transports.filter { !it.isHealthy && !it.isStarting }.forEach { dead ->
                    app.aether.aegis.simplex.ConnectionLog.warn(
                        "ProtocolManager",
                        "watchdog: ${dead.protocol} is down — restarting",
                    )
                    scope.launch(lifecycleDispatcher) {
                        runCatching { dead.start() }.onFailure {
                            app.aether.aegis.simplex.ConnectionLog.warn(
                                "ProtocolManager",
                                "watchdog: ${dead.protocol} restart threw: ${it.message}",
                            )
                        }
                    }
                }
            }
        }

        outboxJob = scope.launch {
            while (isActive) {
                delay(outboxDrainIntervalMs)
                drainOutbox()
            }
        }
    }

    /** Force-recompute the state + kick the watchdog now instead of
     *  waiting up to [healthCheckIntervalMs]. Used by Activity.onResume
     *  so the in-app status dot reflects reality the moment the user
     *  comes back from background, rather than showing whatever the
     *  last tick painted up to 30 s earlier. */
    fun nudgeRecompute() {
        scope.launch {
            recomputeState()
            // Skip transports mid-first-start (isStarting) — see the
            // watchdog note above; otherwise the onResume nudge restarts a
            // transport that's still opening its DB, churning the boot.
            transports.filter { !it.isHealthy && !it.isStarting }.forEach { dead ->
                app.aether.aegis.simplex.ConnectionLog.warn(
                    "ProtocolManager",
                    "onResume nudge: ${dead.protocol} is down — restarting",
                )
                scope.launch(lifecycleDispatcher) {
                    runCatching { dead.start() }
                }
            }
        }
    }

    private suspend fun recomputeState(initial: Boolean = false) {
        val simplex = transports.firstOrNull { it.protocol == Protocol.SIMPLEX }
        val previous = _state.value

        _state.value = when {
            simplex?.isHealthy == true -> State.SIMPLEX_ACTIVE
            else                       -> State.DISCONNECTED
        }

        // SimpleX just came back up after being absent — surface a brief
        // RECONNECTING state while we flush anything that queued while the
        // transport was down, then settle on SIMPLEX_ACTIVE.
        if (!initial && previous != State.SIMPLEX_ACTIVE && _state.value == State.SIMPLEX_ACTIVE) {
            _state.value = State.RECONNECTING
            drainOutbox()
            // Reconnect is trigger #1 in SPEC_LOSSY_LINK_RESILIENCE: ask the
            // receiver (with facts, not watermarks) about any ticks that went
            // dark while the link was down. Heals receipts the lossy link
            // dropped; silent when nothing's stuck.
            sendReconciliationQueries()
            _state.value = State.SIMPLEX_ACTIVE
        }
    }

    /**
     * Receipt reconciliation query — SPEC_LOSSY_LINK_RESILIENCE, sender side.
     *
     * For each Aegis peer, find outbound DNAs whose tick is stuck below sealed
     * and older than [RECONCILE_STUCK_AGE_MS] (so genuinely in-flight sends are
     * left alone), and ask the receiver about them in one `[aegis:statusq:…]`
     * frame. The receiver answers with the real per-DNA state (exact receipts,
     * never a watermark); a DNA it doesn't hold gets no answer, so the tick
     * stays honestly dark. Silence = fine: we only ask when something looks
     * wrong, and never broadcast "all good". Capped per peer so a long offline
     * backlog can't bloat the query.
     *
     * Fired on the natural triggers the spec lists: reconnect (#1, the sweep
     * below), outbound user-message send (#4, in [sendMessage]), and unread
     * chat open (#3, [requestReceiptReconciliation] from ChatScreen — unread
     * only, the attention-leak defense). GPS heartbeat (#2) is the remaining
     * follow-up. Non-Aegis peers never produce stuck DNA rows (their sends are
     * DNA-less MCText), so the query is naturally a no-op for them.
     */
    private suspend fun reconcilePeer(peerKey: String) {
        val cutoff = System.currentTimeMillis() - RECONCILE_STUCK_AGE_MS
        val stuck = runCatching {
            repository.stuckOutboundDnas(peerKey, cutoff, RECONCILE_QUERY_CAP)
        }.getOrNull().orEmpty()
        if (stuck.isEmpty()) return
        sendMessage(
            to = peerKey,
            content = "[aegis:statusq:${stuck.joinToString(",")}]",
            type = MessageType.STATUS,
        )
    }

    /** Reconnect sweep — reconcile every Aegis peer's stuck ticks (trigger #1). */
    private suspend fun sendReconciliationQueries() {
        val peers = runCatching { repository.allKnownPeers() }.getOrElse {
            app.aether.aegis.simplex.ConnectionLog.warn(
                "ProtocolManager", "reconcile: allKnownPeers failed: $it",
            )
            return
        }
        for (peer in peers) {
            if (!peer.isAegis || peer.blocked) continue
            reconcilePeer(peer.publicKey)
        }
    }

    /** Fire-and-forget per-peer reconciliation for the triggers outside the
     *  reconnect sweep — outbound send (#4) and unread chat open (#3). Safe to
     *  call for any peer: a non-Aegis or fully-acked peer yields no query. */
    fun requestReceiptReconciliation(peerKey: String) {
        scope.launch { reconcilePeer(peerKey) }
    }

    private suspend fun drainOutbox() = drainLock.withLock {
        // Drop messages that have outlived MAX_OUTBOX_ATTEMPTS retries —
        // they're undeliverable (target peer was deleted, etc.) and keep
        // burning battery on the backoff timer otherwise.
        repository.purgeExhaustedOutbox(MAX_OUTBOX_ATTEMPTS)

        val pending = repository.pendingOutbox()
        for (entry in pending) {
            val type = MessageType.valueOf(entry.type)
            var delivered = false
            for (transport in orderedHealthyTransports()) {
                if (transport.send(entry.toKey, entry.body, type)) {
                    // Same filter as sendMessage — don't record
                    // control messages in our own chat table when a
                    // queued one drains successfully.
                    if (type in USER_VISIBLE_MESSAGE_TYPES) {
                        // The row this queued send belongs to: a linked entry
                        // (offline pre-recorded bubble) is ADVANCED in place; a
                        // legacy entry with no link records a fresh row.
                        val rowId = entry.messageId ?: run {
                            repository.recordSent(entry.toKey, entry.body, transport.protocol, type).id
                        }
                        if (transport is app.aether.aegis.simplex.SimpleXTransport) {
                            // Thread the SimpleX itemId so the row's ticks
                            // advance (sndSent → "sent", then the receipt
                            // ladder). The drain previously skipped this, so
                            // outbox messages stuck at their initial tick.
                            transport.consumeLastSentItemId(entry.toKey, entry.body)?.let { itemId ->
                                repository.setSimplexItemId(rowId, itemId)
                            }
                            // Thread the minted DNA (Aegis peers only) so a DNA
                            // read receipt later matches this message.
                            transport.consumeLastSentDna(entry.toKey, entry.body)?.let { dna ->
                                repository.setMessageDna(rowId, dna)
                            }
                        }
                        // Flip the (offline) LOCAL row to the real transport so
                        // its protocol label is honest; status climbs off the
                        // clock via the transport's sndSent event.
                        if (entry.messageId != null) {
                            repository.setMessageProtocol(rowId, transport.protocol)
                        }
                    }
                    repository.removeOutbox(entry.id)
                    delivered = true
                    break
                }
            }
            if (!delivered) {
                val backoff = (1_000L shl entry.attempts.coerceAtMost(8)).coerceAtMost(60_000L)
                repository.rescheduleOutbox(entry.id, backoff)
            }
        }
    }

    /** Priority order (SimpleX is the only network transport now). Filtered
     *  to healthy. Used by both message sends and outbox drains. */
    private fun orderedHealthyTransports(): List<Transport> {
        val priority = mapOf(
            Protocol.SIMPLEX  to 0,
        )
        return transports
            .filter { it.isHealthy }
            .sortedBy { priority[it.protocol] ?: Int.MAX_VALUE }
    }

    /**
     * Send a message. Iterates healthy transports by priority until one
     * accepts the target; if none do, queues to outbox.
     *
     * Each transport decides for itself whether it can deliver:
     *  - SimpleX returns true only for paired SimpleX contacts
     *
     * Self-chat (to == selfKey) short-circuits straight to recordSent —
     * no transport can deliver to ourselves and we'd just be cycling
     * through them to land in the outbox.
     */
    fun sendMessage(to: String, content: String, type: MessageType = MessageType.TEXT) {
        scope.launch {
            if (to == selfKey) {
                if (type in USER_VISIBLE_MESSAGE_TYPES) {
                    repository.recordSent(to, content, Protocol.LOCAL, type)
                }
                return@launch
            }
            // Gate `[aegis:…]` control envelopes on per-peer capability
            // (known_peers.isAegis, flipped on first inbound aegis-tag
            // from this peer — see SimpleXTransport.handleNewChatItems).
            // For unconfirmed peers, drop most control envelopes
            // entirely OR strip the prefix for sos / burn so the
            // human-readable body still lands as a normal chat
            // message. We use [gated] for BOTH the wire send AND the
            // local recordSent so the sender's chat view matches what
            // the recipient actually saw.
            val gated = gateAegisControl(to, content) ?: return@launch
            for (transport in orderedHealthyTransports()) {
                if (transport.send(to, gated, type)) {
                    // Mirror of the inbound filter: outbound control
                    // messages (LOCATION / STATUS / SOS / STORY) must
                    // NOT land in OUR OWN messages table either —
                    // otherwise every status broadcast we fan out to
                    // family also shows up in our own chat with that
                    // family member, spamming the inbox. Only user-
                    // visible types get recordSent.
                    if (type in USER_VISIBLE_MESSAGE_TYPES) {
                        // Local record keeps the ORIGINAL body (with
                        // any `[aegis:burn:…]` marker intact) so the
                        // BURN_MARKER regex and the disappearing-ttl
                        // bookkeeping continue to see the parseable
                        // form — inbound paths store the marker for
                        // the same reason, this keeps both sides
                        // symmetric. The WIRE send uses [gated] so a
                        // non-Aegis recipient sees the plain text.
                        val msg = repository.recordSent(to, content, transport.protocol, type)
                        if (transport is app.aether.aegis.simplex.SimpleXTransport) {
                            transport.consumeLastSentItemId(to, gated)?.let { itemId ->
                                repository.setSimplexItemId(msg.id, itemId)
                            }
                            // Same for the minted DNA (Aegis peers only).
                            transport.consumeLastSentDna(to, gated)?.let { dna ->
                                repository.setMessageDna(msg.id, dna)
                            }
                        }
                        // Piggyback fresh presence on chat activity: push
                        // live status + last-known location to Trusted
                        // contacts so an active conversation carries
                        // real-time battery/GPS instead of stale ticker
                        // data. Debounced + Trusted-only inside the service.
                        app.aether.aegis.services.ProtocolService.requestActivityPresenceRefresh()
                        // Reconciliation trigger #4 (SPEC_LOSSY_LINK_RESILIENCE):
                        // an outbound user message is a natural moment to ask the
                        // peer about any earlier ticks that went dark. No-op if
                        // nothing's stuck; never fires for control/STATUS sends
                        // (this branch is USER_VISIBLE only), so no feedback loop
                        // with the statusq frame itself.
                        requestReceiptReconciliation(to)
                    }
                    return@launch
                }
            }
            // Nothing accepted — record as queued IFF it's a real
            // chat message. Control messages don't get queued either;
            // status/location broadcasts are best-effort and the next
            // tick will fire a fresh one.
            if (type in USER_VISIBLE_MESSAGE_TYPES) {
                // Record a VISIBLE bubble immediately at "sending" (the hex
                // clock) and link the outbox entry to it, so a truly-offline
                // message shows up right away as pending and the drain advances
                // THIS row in place (no duplicate bubble on reconnect).
                val msg = repository.recordSent(
                    to, content, Protocol.LOCAL, type, statusOverride = "sending",
                )
                repository.enqueueOutbox(to, content, type, messageId = msg.id)
            }
        }
    }

    /**
     * Life-safety send path for SOS (SOS) traffic. Differs from
     * [sendMessage] in two deliberate ways, both bought at the cost of a
     * little redundant traffic — a trade we happily make when someone has
     * hit the SOS button:
     *
     *  1. **Races every healthy transport in parallel** instead of trying
     *     them in priority order and stopping at the first that accepts.
     *     SimpleX and LAN fire at once; whichever lands first wins. When
     *     the two devices are on the same network the LAN path delivers
     *     sub-second, versus the ~5 s SimpleX relay round-trip — so a
     *     co-located contact gets the alert almost instantly. Double
     *     delivery is safe: the receiver's SOS handling is idempotent
     *     (keyed by sender, same notification id, re-entry no-ops), so a
     *     contact reached by both paths sees one alert, not two.
     *
     *  2. **Retries fast** until something accepts, for up to
     *     [SOS_RETRY_WINDOW_MS]. A normal SOS that finds no healthy
     *     transport is dropped (it isn't a user-visible type, so it never
     *     hits the outbox) and only re-fires on the 30 s SOS loop — a
     *     brutal gap when the transport is briefly down (just resumed,
     *     mid-reconnect). Here we re-attempt every
     *     [SOS_RETRY_INTERVAL_MS] so the alert goes out within a second
     *     or two of connectivity returning, long before the 30 s loop.
     *
     * Fire-and-forget like [sendMessage]; the caller fans this out across
     * targets (each call launches its own race+retry, so all targets are
     * attempted concurrently). Self-sends are skipped.
     */
    fun sendSos(to: String, content: String, type: MessageType) {
        if (to == selfKey) return
        scope.launch {
            val gated = gateAegisControl(to, content) ?: return@launch
            val deadline = System.currentTimeMillis() + SOS_RETRY_WINDOW_MS
            while (isActive) {
                val healthy = orderedHealthyTransports()
                val accepted = if (healthy.isEmpty()) {
                    false
                } else {
                    // Fire ALL healthy transports at once; delivered if any
                    // accepts. awaitAll so we don't leave a transport's send
                    // dangling, and so LAN's fast accept isn't masked by
                    // SimpleX's slower one.
                    healthy
                        .map { t -> async { runCatching { t.send(to, gated, type) }.getOrDefault(false) } }
                        .awaitAll()
                        .any { it }
                }
                if (accepted) return@launch
                if (System.currentTimeMillis() >= deadline) return@launch
                delay(SOS_RETRY_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        // Tear down the transports asynchronously, then cancel only the
        // children of `scope` — NOT the scope itself. A cancelled
        // CoroutineScope is permanently dead, and any later start() call
        // launches into the dead scope, which silently no-ops. The bug
        // surfaced as "transport down randomly" + "protocol initialising
        // forever": ProtocolService.stopWork() calls stop() (on pause,
        // OOM-and-recreate, profile switch, etc.), and the next time the
        // service starts again, protocolManager.start() runs but its
        // scope.launch { transports.forEach { it.start() } } never
        // actually runs, so SimpleXTransport.isHealthy stays false and
        // the SimpleXCore.initialised flip never gets driven.
        //
        // Mirrors the same fix in SimpleXTransport.stop() (note there).
        // SupervisorJob keeps the scope itself alive while we kill the
        // in-flight pumps; start() reuses it cleanly on the next call.
        app.aether.aegis.simplex.ConnectionLog.log("ProtocolManager", "stop()")
        scope.launch(lifecycleDispatcher) { transports.forEach { it.stop() } }
        healthJob?.cancel(); healthJob = null
        inboundJob?.cancel(); inboundJob = null
        outboxJob?.cancel(); outboxJob = null
    }

    /**
     * Decide what (if anything) to actually transmit for an
     * outbound message whose body might be an `[aegis:…]` control
     * envelope. Returns:
     *   - [content] unchanged when the body isn't tagged, OR the
     *     recipient is a confirmed Aegis peer (known_peers.isAegis).
     *   - The tag-stripped body for sos / burn so a non-Aegis
     *     recipient still sees the human-readable text.
     *   - null to drop the message entirely for tags that have no
     *     useful fallback against a vanilla SimpleX client (typing,
     *     status, location, story, sim-swap, geofence, read
     *     receipts, call reactions, remote-access commands).
     *
     * Centralised here instead of at each call site so the rule is
     * one place to audit and one place to evolve. The flag flips
     * automatically when the peer sends us anything aegis-tagged
     * (SimpleXTransport.handleNewChatItems), so for Aegis ↔ Aegis
     * the gate is essentially invisible after the first exchange.
     */
    private suspend fun gateAegisControl(to: String, content: String): String? {
        if (!content.startsWith("[aegis:")) return content
        val peer = runCatching { repository.knownPeerByKey(to) }.getOrNull()
        if (peer?.isAegis == true) return content
        return when {
            // Capability-bootstrap envelope. `[aegis:hello]<name>` is
            // sent ungated by both sides immediately after a pairing
            // so each peer's inbound flips the other's known_peers
            // .isAegis row, unlocking the rest of the control surface
            // (location, status, typing, tier announces). Without
            // this exception, fresh Aegis ↔ Aegis pairs deadlock —
            // both sides' gates drop every aegis-tagged outbound, so
            // nobody ever receives the first inbound that would have
            // flipped the flag, and the deadlock persists forever.
            content.startsWith("[aegis:hello]") -> content
            // Capability announcement — part of the same bootstrap as the
            // hello (it tells the peer whether we speak the chat envelope), so
            // it must flow during the pre-isAegis window for the same
            // anti-deadlock reason. Mirror of the SimpleXTransport.aegisGated
            // carve-out.
            content.startsWith("[aegis:caps]") -> content
            // Burn-after-reading wrapper is `[aegis:burn:<ttl>:<id>]<text>`.
            // The recipient can't enforce burn semantics anyway, so
            // strip the marker and send as a normal message — the
            // text still reaches them.
            content.startsWith("[aegis:burn:") -> {
                val close = content.indexOf(']')
                if (close >= 0) content.substring(close + 1).ifBlank { null } else null
            }
            // Remote-access wire is `[aegis:remote]{json}` — AUTH,
            // LOCATE, SIREN, UPDATE, WIPE plus all the responses
            // (AUTH_OK, LOCATE_RESULT, …). Carved out for the same
            // reason as [aegis:hello]: it's a critical control
            // surface that has to work BEFORE the isAegis flag flips,
            // because the very first thing a sender does is
            // RemoteAccessHandler's AUTH send — if that gets gated,
            // the target never replies AUTH_OK, the sender's UI sits
            // on "connecting…" forever, and the whole remote loop is
            // unreachable until the hello-bootstrap also lands. A
            // vanilla SimpleX recipient sees the JSON as a chat
            // message of garbage text; harmless and the handler
            // verifies PIN before any action so unauth'd spam is
            // counter-tripped + REVOKED.
            content.startsWith("[aegis:remote]") -> content
            content.startsWith(app.aether.aegis.remote.RemoteAccessProtocol.WIPE_BROADCAST_PREFIX) -> content
            // Everything else: SOS, typing, status, location, story,
            // sim-swap, geofence, read receipts, call reactions,
            // remote-access commands, sos-audio captions, etc.
            // No useful fallback against a vanilla SimpleX client —
            // drop. The peer-capability flag will flip on first
            // inbound aegis-tag from them and these unlock
            // automatically.
            //
            // SOS is INTENTIONALLY here, not carved out: SOS is
            // Aegis-only by design. A vanilla SimpleX contact cannot be
            // promoted above Untrusted (the tier picker is gated on
            // isAegis), so a non-Aegis peer is never an SOS recipient —
            // and even if one somehow were, we drop rather than leak a
            // plaintext panic alert to a client that can't act on it. Do
            // NOT "restore" an [aegis:sos] plaintext strip here.
            else -> null
        }
    }
}

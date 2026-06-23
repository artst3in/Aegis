package app.aether.aegis.simplex

import app.aether.aegis.AegisApp
import app.aether.aegis.core.MessageType
import app.aether.aegis.core.Protocol
import app.aether.aegis.transport.InboundMessage
import app.aether.aegis.transport.Transport
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * SimpleX transport — backed by the bundled SimpleX Haskell core.
 *
 * v0 surface:
 *   - At start: initialise the core, ensure a profile exists.
 *   - createInboundLink(label): runs "/_connect $userId incognito=on" (Aether
 *     Protocol — incognito always; the `off` path is deleted), captures the
 *     `simplex:` URI from the response, returns it.
 *   - acceptLink(uri, peerLabel): runs "/_connect $userId incognito=on <uri>" to add the peer.
 *   - send(toKey, body, type): looks up the SimpleX contact name for that
 *     Aegis pubkey, runs "@<contact> <body>".
 *   - recvLoop: pumps `chatRecvMsgWait` on a background thread and emits
 *     InboundMessage to the flow on every "newChatItems" event with a
 *     text body from a known contact.
 *
 * The crypto (double ratchet, X3DH, agent layer) is all inside the core;
 * we never see ciphertext or queue keys.
 */
class SimpleXTransport(
    private val context: Context,
    /** The SimpleX core DB passphrase — the Keystore-wrapped key resolved
     *  once in AegisApp and shared with the eager core init, so the
     *  transport and the eager init always open the DB under the same key. */
    private val dbPassphrase: String,
) : Transport {

    override val protocol: Protocol = Protocol.SIMPLEX

    @Volatile
    override var isHealthy: Boolean = false
        private set

    // True for the duration of start() (set after the re-entry guard,
    // cleared in start()'s finally). The first start opens + migrates the
    // SimpleX DB which can take several seconds; without this flag the
    // watchdog / onResume nudge see isHealthy=false and wrongly declare
    // the transport "down — restarting" mid-init, stacking redundant
    // start attempts on top of the one already running.
    @Volatile
    override var isStarting: Boolean = false
        private set

    // 64 was too small for a sos scenario
    // where multiple peers fan out location + status + audio chunks
    // simultaneously. Bumped to 256 (4× headroom) while keeping
    // DROP_OLDEST so a slow collector can't backpressure the SimpleX
    // command parser and stall the session heartbeat. The drop
    // semantic still prioritises the most recent message — exactly
    // what we want during sos where the latest GPS supersedes the
    // previous one anyway.
    private val inboundFlow = MutableSharedFlow<InboundMessage>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override fun inbound(): Flow<InboundMessage> = inboundFlow.asSharedFlow()

    /**
     * Single source of truth for SimpleX status surfacing — the Status
     * tab pulls this. Anything else that needs to know whether the
     * SimpleX side is working should ask here, not poke at internals
     * directly, so we don't drift into three different versions of
     * "are we online" across the UI.
     */
    enum class CoreState { INITIALISING, READY, FAILED }

    /** Per-relay (SMP transport host) connection state, tracked from the
     *  core's hostConnected / hostDisconnected events. [connected] is the
     *  last-known state; [sinceMs] is when it last changed, so the UI can
     *  show "up 4m" / "down 12s". */
    data class RelayInfo(
        val host: String,
        val connected: Boolean,
        val sinceMs: Long,
    )

    data class NetworkSnapshot(
        val coreState: CoreState,
        val coreError: String?,
        val transportHealthy: Boolean,
        val pairedContacts: Int,
        val lastSendError: String?,
        /** Exception message from the most recent start() attempt that
         *  ran past core init but failed before flipping isHealthy=true.
         *  Lets the user see WHY the transport is down even when the
         *  core itself booted fine. Null when the last start() succeeded
         *  or when the failure was in core init (use [coreError] then). */
        val startError: String? = null,
        /** Wall-clock of the most recent inbound event from the core.
         *  Drives the "healthy pump but no traffic" warning state in
         *  the StatusScreen indicator — the prior label only checked
         *  [transportHealthy] which only reflects whether recvWait is
         *  alive, not whether SMP subscriptions are actually delivering.
         *  Zero when nothing has been received this session. */
        val lastEventAtMs: Long = 0L,
        /** Known SMP relays and their live up/down state. Empty before
         *  the first host event arrives (e.g. right after a cold start,
         *  before any contact's queue subscription opens a TCP session). */
        val relays: List<RelayInfo> = emptyList(),
        /** True while start() is running (DB open + migration) — lets the
         *  health verdict show "coming up" instead of "down" during the
         *  multi-second warm-up. */
        val starting: Boolean = false,
    )
    /** Most recent send-failure response, surfaced in the Status tab and
     *  to the chat composer (so a blocked/failed attachment toasts the
     *  reason instead of silently looking sent). */
    @Volatile var lastSendError: String? = null
        private set
    @Volatile private var startError: String? = null
    /** Last error from [acceptInvitation] — null on success or if the call
     *  hasn't been attempted yet. The UI reads this after a failed join
     *  attempt to surface the actual core error instead of a generic
     *  "couldn't join" string. Volatile because join attempts can happen
     *  on a non-main coroutine. */
    @Volatile var lastJoinError: String? = null
        private set
    /** Aegis-side id of the group a successful group-link accept resolved
     *  to — a `Known` plan (already a member) or a fresh join. The accept UI
     *  reads this to navigate INTO the group, instead of leaving the user on
     *  an unchanged screen: the "join by link does nothing" report was a
     *  Known group (already a member) that the flow silently re-persisted and
     *  never opened. Null for contact links / failed joins. */
    @Volatile var lastJoinedGroupAegisId: String? = null
        private set
    /** Stamped on every successful inbound event so the UI can tell
     *  the difference between "pump alive" (isHealthy=true) and "pump
     *  alive AND actually receiving traffic." Volatile because writes
     *  come from the pump coroutine and reads from any UI thread. */
    @Volatile private var lastEventAtMs: Long = 0L
    /** Per-relay up/down, keyed by transport host. Updated from the
     *  core's hostConnected / hostDisconnected events. ConcurrentHashMap
     *  because writes come from the pump coroutine and reads from the UI
     *  poll. */
    private val relayState = java.util.concurrent.ConcurrentHashMap<String, RelayInfo>()
    fun networkSnapshot(): NetworkSnapshot {
        val coreErr = SimpleXCore.initError
        val booted = SimpleXCore.initialised
        val coreState = when {
            coreErr != null -> CoreState.FAILED
            booted          -> CoreState.READY
            else            -> CoreState.INITIALISING
        }
        return NetworkSnapshot(
            coreState = coreState,
            coreError = coreErr,
            transportHealthy = isHealthy,
            pairedContacts = peerByKey.size,
            lastSendError = lastSendError,
            startError = startError,
            lastEventAtMs = lastEventAtMs,
            relays = relayState.values.sortedBy { it.host },
            starting = isStarting,
        )
    }

    /**
     * Proactively flip every known relay to DISCONNECTED.
     *
     * Called on an OS network-loss event (airplane mode, radio drop): the
     * core's own `hostDisconnected` lags a dead socket by the TCP timeout, so
     * without this the Network card kept showing relays as "connected" while
     * the device was offline (user-reported). The next networkSnapshot() poll
     * then honestly reads them as down. No-op when no relays are tracked yet.
     */
    fun markAllRelaysDown() {
        if (relayState.isEmpty()) return
        val now = System.currentTimeMillis()
        relayState.replaceAll { _, info ->
            if (info.connected) info.copy(connected = false, sinceMs = now) else info
        }
        ConnectionLog.log(TAG, "markAllRelaysDown (network lost)")
    }

    /** Self-heal entry point — user-facing button in the Network card.
     *  Drops the current state and runs start() again. Useful when a
     *  transient hiccup (file paths, /_start race, momentary network
     *  blip on the first command) left isHealthy=false; usually
     *  recovers without a reinstall. */
    suspend fun restart() {
        Log.i(TAG, "user requested SimpleX restart")
        runCatching { stop() }
        startError = null
        start()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pumpJob: Job? = null

    /**
     * Aegis routing key (e.g. "simplex:Zippy") → SimpleX contact info.
     * The numeric contactId is what we actually use to address sends —
     * it's immune to display names containing spaces or punctuation that
     * would break SimpleX's `@<name> <text>` shorthand parser. The name
     * is kept for log messages and as a fallback if we somehow paired
     * without seeing a contactId.
     */
    private data class SXContact(val name: String, val contactId: Long?)
    private val peerByKey = ConcurrentHashMap<String, SXContact>()
    // Peers we've already re-greeted this session to self-heal a stalled Aegis
    // handshake (see the inbound self-heal block). Bounds the recovery to ONE
    // extra hello per peer per process so we don't re-greet on every inbound,
    // and don't spam a genuinely-vanilla SimpleX contact.
    private val helloSelfHealed = ConcurrentHashMap.newKeySet<String>()
    // Guards the read-modify-write in bindContact. Must be a coroutine
    // Mutex, NOT a `synchronized(Any())` monitor: bindContact is a
    // suspend fun whose critical section awaits suspend repository calls
    // (repairKnownPeer / addKnownPeer), and Kotlin forbids suspension
    // points inside a `synchronized {}` block (it would compile-error,
    // and even conceptually you must not hold a JVM monitor across a
    // thread-hopping suspension). withLock suspends instead of blocking
    // while still serialising concurrent pairings of the same display
    // name — the exact race this lock exists to close.
    private val bindLock = Mutex()

    // Serialises the entire start() body. ProtocolService fires start()
    // from several edges — initial launch, onResume nudge, the watchdog,
    // and every network-reconnect event — and during the ~5 s init
    // window isHealthy is still false, so the post-init re-entry guard
    // doesn't stop a second (or third) start() from running concurrently.
    // The lifecycleDispatcher (parallelism=1) that was meant to serialise
    // them is defeated the instant start() does withContext(Dispatchers.IO),
    // which frees the single-thread slot. Concurrent inits then race on
    // opening the SAME SQLCipher file → "ErrorBusy" / "errorNotADatabase",
    // and the core comes up "healthy" but with a wedged controller: it can
    // send but its receive subscriptions are dead — no inbound, no status,
    // no read ticks (user-reported 2026-06-08). Holding this lock across the
    // whole method makes init run exactly once; the queued nudges acquire it
    // after and no-op via the re-entry guard.
    private val startLock = Mutex()

    override suspend fun start() = startLock.withLock {
      withContext(Dispatchers.IO) {
        // Re-entry guard. ProtocolManager.start() is called whenever
        // ProtocolService comes up — at aegisApp launch, after a pause /
        // resume, when the OS restarts a killed foreground service, on
        // profile switch, etc. — so transport.start() can be called
        // when we're already healthy. Without this guard we'd leak a
        // second pumpJob each time, fire /_start twice (which the core
        // logs as an error), and double-register the peer-mutation
        // collector.
        if (isHealthy && pumpJob?.isActive == true) {
            ConnectionLog.log(TAG, "start(): already healthy, no-op")
            return@withContext
        }
        ConnectionLog.log(TAG, "start(): entering")
        isStarting = true
        try {
            // Ensure the SimpleX core DB is open under the Keystore-wrapped
            // passphrase. A no-op if the eager init (AegisApp.onCreate)
            // already opened it with the same key.
            SimpleXCore.ensureInitialised(context, dbPassphrase)
            if (!SimpleXCore.initialised) {
                Log.w(TAG, "SimpleX core failed to init: ${SimpleXCore.initError}")
                ConnectionLog.warn(
                    TAG,
                    "core not initialised — aborting start: ${SimpleXCore.initError}",
                )
                isHealthy = false
                return@withContext
            }
            ConnectionLog.log(TAG, "core initialised; ensureUserProfile()")
            ensureUserProfile()
            ConnectionLog.log(TAG, "startReceiver()")
            startReceiver()
            // Configure the core's file sandbox BEFORE /_start. Paths
            // match simplex-chat's androidMain Files.android.kt exactly:
            //   appFilesDir       = profile root's app_files (attachments)
            //   coreTmpDir        = filesDir/temp_files
            //   wallpapersDir     = filesDir/assets/wallpapers  (parent = assets)
            //   remoteHostsDir    = tmpDir/remote_hosts  (tmpDir from getDir("temp"))
            //
            // Phase 1 multi-profile: appFilesDir lives under the
            // active profile root so attachments survive a profile
            // switch with the rest of that profile's state. Tmp +
            // assets stay global — they hold no identity-bound data.
            // Without this, the core can't read fileSource paths in
            // ApiSendMessages, so file delivery silently fails.
            val appFilesDir = app.aether.aegis.AegisApp.instance.profileRoot.attachmentsDir.apply { mkdirs() }
            val coreTmpDir = java.io.File(context.filesDir, "temp_files").apply { mkdirs() }
            val assetsDir = java.io.File(context.filesDir, "assets").apply { mkdirs() }
            val tmpDir = context.getDir("temp", Context.MODE_PRIVATE)
            val remoteHostsDir = java.io.File(tmpDir, "remote_hosts").apply { mkdirs() }
            val pathsJson = JSONObject().apply {
                put("appFilesFolder", appFilesDir.absolutePath)
                put("appTempFolder", coreTmpDir.absolutePath)
                put("appAssetsFolder", assetsDir.absolutePath)
                put("appRemoteHostsFolder", remoteHostsDir.absolutePath)
            }
            ConnectionLog.log(TAG, "/set file paths")
            send("/set file paths $pathsJson")
            // Push a network config BEFORE /_start, matching the
            // upstream order in startChat (SimpleXAPI.kt:566). Without
            // this, /_connect on a group/contact link can return
            // sentInvitation but no follow-up lifecycle event ever
            // arrives — the agent's SMP client uses an unconfigured
            // default state and the queue-subscription side of the
            // pairing silently never starts. Values mirror NetCfg.defaults
            // (SimpleXAPI.kt:4898) except logTLSErrors which we leave
            // off.
            val netCfg = JSONObject().apply {
                // AEGIS PROTOCOL — NO TOR. (Decentralization;
                // Origin #19 applied to routing.)
                //
                // socksProxy is PERMANENTLY null. Aegis never routes through
                // a SOCKS proxy / Tor — there is no setting, no toggle, no UI,
                // and no other code path anywhere in the app that sets a
                // proxy. This is the structural guarantee that a sos / SOS
                // broadcast can NEVER get stuck in a breakable Tor circuit:
                // the safety pipeline only has a direct path because no other
                // path exists in the binary. SimpleX's global SOCKS config
                // can't be scoped per-connection, so a "Tor for routine,
                // direct for sos" split would be a runtime flag — exactly
                // the disable-don't-delete anti-pattern. We delete instead.
                //
                // IP-level anonymity, if a user wants it, is an explicit
                // OS-level choice (Orbot / VPN) that they own and can see —
                // NOT something Aegis silently applies to the distress channel.
                //
                // DO NOT add a SOCKS / proxy / Tor / onion option here. The
                // reliability of every safety trigger depends on this absence.
                put("socksProxy", JSONObject.NULL)
                // Moot while socksProxy is null — the mode only governs WHEN a
                // proxy would be used, and there is none.
                put("socksMode", "always")
                // hostMode wire value is "public" (HostMode.Public's
                // @SerialName at SimpleXAPI.kt). "publicHost" was a
                // hand-built JSON mistake — the Haskell parser was
                // lenient enough to accept it but it didn't match the
                // canonical wire format. (Audit 2026.05.30 fix #2.)
                put("hostMode", "public")
                put("requiredHostMode", false)
                put("sessionMode", "user")
                put("smpProxyMode", "unknown")
                put("smpProxyFallback", "allowProtected")
                put("smpWebPortServers", "preset")
                put("tcpConnectTimeout", JSONObject().apply {
                    put("backgroundTimeout", 45_000_000)
                    put("interactiveTimeout", 15_000_000)
                })
                put("tcpTimeout", JSONObject().apply {
                    put("backgroundTimeout", 30_000_000)
                    put("interactiveTimeout", 10_000_000)
                })
                put("tcpTimeoutPerKb", 10_000)
                put("rcvConcurrency", 12)
                put("tcpKeepAlive", JSONObject().apply {
                    put("keepIdle", 30)
                    put("keepIntvl", 15)
                    put("keepCnt", 4)
                })
                // SMP application-level keepalive. Microseconds. Shortened from
                // the upstream 20 min (1200_000_000) to 2 min so a receive
                // subscription that died SILENTLY — dropped by a NAT/firewall
                // or a server restart without a TCP reset, which TCP keepalive
                // can miss — is re-pinged and, if unanswered, re-established
                // fast. Inbound is server-PUSH over the live subscription, so a
                // warm session = near-instant delivery; a stale one stalls
                // messages until it's noticed. Battery cost is marginal: the
                // socket is already kept warm by the 30 s TCP keepalive above,
                // so this app-level ping is actually the LESS frequent of the
                // two, and the app is battery-exempt anyway (a safety app must
                // hold its delivery channel). smpPingCount=3 → declared dead
                // after 3 unanswered pings.
                put("smpPingInterval", 120_000_000)
                put("smpPingCount", 3)
                put("logTLSErrors", false)
            }
            ConnectionLog.log(TAG, "/_network")
            send("/_network $netCfg")
            // tell core to start the chat (initial /u creates one; /_start runs it)
            ConnectionLog.log(TAG, "/_start main=on")
            send("/_start main=on")
            // Tell the core to encrypt local file content on disk.
            // Upstream wires this via apiSetEncryptLocalFiles right
            // after /_start; the wire command is /_files_encrypt on
            // (SimpleXAPI.kt:3847 — `is ApiSetEncryptLocalFiles ->
            // "/_files_encrypt ${onOff(enable)}"`). 624 first shipped
            // this with a guessed `/_set encrypt local files on`
            // which the core rejected as chatCmdError — the actual
            // file-encryption flag never got set and the error log
            // confused the connection state. Audit fix #3,
            // corrected to upstream's actual wire form.
            ConnectionLog.log(TAG, "/_files_encrypt on")
            send("/_files_encrypt on")

            isHealthy = true
            ConnectionLog.log(TAG, "transport healthy")
            // Rehydrate the in-memory routing map so peers paired in a
            // previous aegisApp session can still be addressed without
            // waiting for a contactConnected event (which only fires on
            // first pairing, never on restart).
            rehydrateContacts()
            ConnectionLog.log(TAG, "rehydrateContacts done: ${peerByKey.size} peers")
            // peerByKey was previously built once at startup, leaving
            // stale routes if a user "removed" a contact via the Aegis
            // UI. Subscribe to repository mutations so the map tracks
            // those edits in real time.
            observePeerMutations()
            // Bootstrap the control channel now that recvWait is live. The
            // old cold-start trigger (AegisApp.onCreate) raced the socket: it
            // fired before the transport connected, so the [aegis:hello] was
            // dropped and never retried — stranding every contact paired
            // before the control channel with a null controlPubKey and an
            // un-flipped isAegis. Presence, delivery/read ticks AND location
            // all ride that channel, so all three stayed dead for those peers
            // until a manual re-pair. Firing here (and after apiReconnect)
            // guarantees the hello goes out on a healthy transport.
            // broadcastNow only greets peers still missing a control pubkey,
            // so it's a cheap no-op once each is bootstrapped.
            scope.launch {
                runCatching {
                    app.aether.aegis.admin.HelloBroadcaster.broadcastNow(AegisApp.instance)
                }.onFailure { Log.w(TAG, "post-connect hello bootstrap failed", it) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            ConnectionLog.warn(TAG, "start() threw: ${t.message ?: t::class.simpleName}")
            // Stash the message so the Network card can show it. Without
            // this the user only sees "transport: down" with no clue
            // why — logcat is rarely available on a real-world handset.
            startError = t.message ?: t::class.simpleName ?: "unknown"
            isHealthy = false
        } finally {
            isStarting = false
        }
      }
    }

    private fun observePeerMutations() {
        scope.launch {
            AegisApp.instance.repository.peerMutations.collect { m ->
                when (m) {
                    is app.aether.aegis.data.Repository.PeerMutation.Removed -> {
                        // Drop the routing entry so any in-flight send
                        // path can't keep using the deleted contact.
                        val gone = peerByKey.remove(m.publicKey)
                        if (gone != null) {
                            Log.i(TAG, "peer removed → unbound ${m.publicKey}")
                            // Also tell the SimpleX core to drop the
                            // contact on its side. Without this the
                            // core keeps the contactId in its
                            // database, the Network card keeps
                            // reading non-zero pairedContacts, and
                            // the deleted peer can still address us.
                            val cid = gone.contactId
                            if (cid != null) {
                                runCatching {
                                    val resp = send("/_delete @$cid notify=off")
                                    if (resp.contains("\"type\":\"chatCmdError\"")) {
                                        Log.w(TAG, "core /_delete @$cid responded: ${resp.take(220)}")
                                    } else {
                                        Log.i(TAG, "core /_delete @$cid OK")
                                    }
                                }.onFailure {
                                    Log.w(TAG, "core /_delete @$cid threw", it)
                                }
                            }
                        }
                    }
                    is app.aether.aegis.data.Repository.PeerMutation.Added,
                    is app.aether.aegis.data.Repository.PeerMutation.Renamed -> {
                        // SimpleX-side state may have moved out from
                        // under us (alias edits via the core, or a
                        // peer added through a non-SimpleX path that
                        // implies a pairing exists). Re-pull the
                        // canonical contact list.
                        rehydrateContacts()
                    }
                }
            }
        }
    }

    // Holds the SAME startLock as start(), so a pause/resume that fires
    // stop() while a start() is mid-DB-migration waits for init to finish
    // before tearing the core down (instead of corrupting it underneath
    // start() and leaving a "healthy but dead pump"). start() and stop()
    // are now strictly serialised.
    override suspend fun stop(): Unit = startLock.withLock {
        ConnectionLog.log(TAG, "stop()")
        isHealthy = false
        pumpJob?.cancel()
        pumpJob = null
        // Cancel the in-flight children but DO NOT scope.cancel() — a
        // cancelled CoroutineScope is permanently dead and any future
        // scope.launch silently no-ops. Pausing Aegis from the
        // notification stops the service, which calls protocolManager
        // .stop() → transport.stop(). On Resume we restart and need
        // start() / startReceiver() to be able to relaunch in this
        // scope. Killing the scope meant the receiver coroutine never
        // came back, isHealthy stayed false, and the transport was
        // stuck "down" until full process restart. Cancelling just the
        // children (SupervisorJob keeps the scope itself alive) gives
        // us the "tear down active work, keep the scope reusable"
        // behaviour we actually want.
        val job = scope.coroutineContext[kotlinx.coroutines.Job]
        job?.children?.forEach { it.cancel() }
        // Explicit Unit terminator: the lambda's last expression
        // (job?.children?.forEach) yields Unit?, which would make
        // withLock<Unit?> and break the override's Unit return type.
        Unit
    }

    override suspend fun send(toKey: String, body: String, type: MessageType): Boolean =
        sendText(toKey, body, quotedItemId = null)

    /**
     * Send a text message with optional quoted-reply threading.
     * [quotedItemId] is the SimpleX chatItem.meta.itemId of the message
     * being replied to (from MessageEntity.simplexItemId). Null means
     * "not a reply". The receiver renders this as a real threaded
     * reply bubble instead of an inline quote.
     */
    suspend fun sendText(toKey: String, body: String, quotedItemId: Long? = null): Boolean {
        if (!isHealthy) return false
        val contact = peerByKey[toKey] ?: run {
            Log.w(TAG, "send: no SimpleX contact bound for $toKey")
            lastSendError = "no SimpleX contact bound for $toKey"
            return false
        }
        val contactId = contact.contactId ?: run {
            Log.w(TAG, "send: missing contactId for $toKey (name=${contact.name})")
            lastSendError = "missing contactId for ${contact.name} — re-pair this contact"
            return false
        }
        // Defense-in-depth: ProtocolManager.sendMessage already gates
        // `[aegis:…]` envelopes by peer capability before hitting any
        // transport, but the outbox drain and any future direct
        // caller of sendText would otherwise bypass it. Re-running
        // the same rules here ensures no aegis-tag wraps escape to a
        // vanilla SimpleX client regardless of entry point.
        val sendBody = aegisGated(toKey, body) ?: run {
            Log.i(TAG, "sendText: dropped aegis-tag envelope to non-Aegis peer $toKey: ${body.take(60)}")
            // Returning true so the caller treats this as "delivered"
            // (don't requeue in the outbox). The peer wasn't supposed
            // to receive this; silent drop is the correct outcome.
            return true
        }
        // Chat-envelope eligibility, computed up front because it gates BOTH
        // the burn carve-out and the chat-vs-MCText choice below. Gate on the
        // peer's ANNOUNCED chat capability, not merely isAegis: a pre-envelope
        // Aegis build would mis-handle x.aegis.chat as a file and LOSE the
        // message. A peer that hasn't announced "chat" (older build, or caps
        // not yet received) falls back to the plain MCText shim / control
        // channel — safe in both rollout directions.
        val peerSupportsChat = runCatching {
            val p = AegisApp.instance.repository.knownPeerByKey(toKey)
            p?.isAegis == true &&
                app.aether.aegis.protocol.AegisCaps.supports(
                    p.peerCapabilities, app.aether.aegis.protocol.AegisCaps.CHAT,
                )
        }.getOrDefault(false)
        // Control commands ride the separated channel (x.aegis), NOT the
        // chat-text field — a vanilla client renders them as an empty message
        // and a hand-typer can't produce them. EVERY [aegis:*] envelope goes
        // this way, the hello bootstrap included.
        //
        // EXCEPTION — burn-after-reading to a chat-envelope peer: it's a
        // DISPLAYED user message, so it rides x.aegis.chat (gaining a DNA +
        // the tick ladder) like any other chat message. The [aegis:burn:…]
        // marker stays INSIDE the envelope text, so the receiver classifies it
        // BURN exactly as before and the burn viewer + receipt round-trip are
        // untouched (the receipt still keys on the marker's sender-row id).
        // Burn to a non-chat peer, and every other [aegis:*], still goes
        // through sendControl.
        val burnViaEnvelope = peerSupportsChat && sendBody.startsWith("[aegis:burn:")
        if (sendBody.startsWith("[aegis:") && !burnViaEnvelope) {
            return sendControl(toKey, contactId, sendBody)
        }
        val msgContent: JSONObject = if (peerSupportsChat) {
            val dnaIso = app.aether.aegis.protocol.MessageDna.mint(context)
            app.aether.aegis.protocol.MessageDna.parseOrNull(dnaIso)?.let { nanos ->
                sentDnas.getOrPut("$toKey|$body") {
                    java.util.concurrent.ConcurrentLinkedQueue()
                }.add(nanos)
            }
            val env = JSONObject()
                .put("type", app.aether.aegis.protocol.ChatEnvelope.CONTENT_TYPE)
                // Carry the body in "text" too: harmless to an Aegis receiver
                // (it reads the envelope), and keeps the field present for any
                // generic inspector. A vanilla peer never receives this type.
                .put("text", sendBody)
                .put("dna", dnaIso)
            // Reply link by DNA: map the quoted message's local itemId to its
            // shared DNA so the receiver can resolve the citation jump (Aegis
            // replies carry no native quote).
            if (quotedItemId != null) {
                runCatching {
                    AegisApp.instance.repository.messageByItemId(quotedItemId)?.messageDna
                }.getOrNull()?.let { env.put("replyToDna", app.aether.aegis.protocol.MessageDna.iso(it)) }
            }
            env
        } else {
            JSONObject().put("type", "text").put("text", sendBody)
        }
        val composed = JSONObject()
            .put("fileSource", JSONObject.NULL)
            // Native quote is intentionally NOT used: it can't ride the
            // x.aegis.chat custom content type (the cause of the failed quoted
            // reactions/replies), and Aegis carries the quote in-body as
            // markdown instead (rendered via parseReplyQuote). The quotedItemId
            // param is retained only for the local reply-link bookkeeping the
            // caller does; it never goes on the wire.
            .put("quotedItemId", JSONObject.NULL)
            .put("msgContent", msgContent)
            .put("mentions", JSONObject())
        val cmd = "/_send @$contactId live=off ttl=default json [$composed]"
        return runCatching {
            val resp = send(cmd)
            val ok = !resp.contains("\"type\":\"chatCmdError\"") &&
                     !resp.contains("\"type\":\"errorStore\"")
            if (ok) {
                lastSendError = null
                // Capture the SimpleX itemId from the response so the
                // caller can link it to our local message row for
                // read-receipt status tracking.
                parseFirstItemId(resp)?.let { itemId ->
                    sentItemIds.getOrPut("$toKey|$body") {
                        java.util.concurrent.ConcurrentLinkedQueue()
                    }.add(itemId)
                }
            } else {
                lastSendError = resp.take(220)
                Log.w(TAG, "send failed for $toKey: ${resp.take(400)}")
            }
            ok
        }.getOrElse {
            Log.e(TAG, "send threw for $toKey", it)
            lastSendError = it.message ?: "unknown"
            false
        }
    }

    /**
     * Stash the original join URI + incognito-state on the GroupEntity
     * row for [simplexGroupId] so the GroupSettings "reveal real
     * identity" action can leave + rejoin with the opposite flag
     * without making the user re-paste the link. No-op if the row
     * doesn't exist yet — handleGroupJoined runs slightly later in
     * some race ordering; in practice acceptInvitation's
     * apiPrepareGroup synchronously creates the row before we land
     * here.
     */
    private suspend fun stashGroupJoinContext(
        simplexGroupId: Long,
        joinLink: String,
        realIdentity: Boolean,
    ) {
        runCatching {
            val repo = AegisApp.instance.repository
            val row = repo.getGroupBySimplexId(simplexGroupId) ?: return
            repo.upsertGroup(
                row.copy(joinLink = joinLink, realIdentity = realIdentity),
            )
            ConnectionLog.log(
                TAG,
                "group ctx stashed sgid=$simplexGroupId real=$realIdentity",
            )
        }.onFailure { Log.w(TAG, "stashGroupJoinContext failed", it) }
    }

    // Group identity is incognito only: there is NO identity switch.
    // Groups are incognito, always; the private 1:1 profile (real name,
    // bio, avatar) is never broadcast to a group. The former
    // switchGroupIdentity(useRealProfile = …) leave+rejoin path that
    // could reveal the real profile has been removed, and the reveal
    // option cannot be re-introduced casually: acceptInvitation no
    // longer accepts a groupIncognito flag and hardcodes incognito for
    // every group join, so the code path that would request a
    // non-incognito join no longer exists. Revealing in a group is, by
    // design, structurally absent — pair 1:1 if someone needs to know
    // who you are. (The reveal UI in GroupMembersScreen was removed in
    // the same change.)

    /**
     * Leave a group via upstream's `/_leave #<groupId>` command
     * (SimpleXAPI.kt:3910). [aegisGroupId] is our local UUID; we
     * resolve to the SimpleX numeric groupId before issuing the
     * command. Best-effort — returns false if the group has no
     * SimpleX side (LAN-only or never paired) or the core rejects.
     */
    suspend fun leaveGroup(aegisGroupId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        val group = runCatching {
            AegisApp.instance.repository.getGroup(aegisGroupId)
        }.getOrNull() ?: return@withContext false
        val sgid = group.simplexGroupId ?: return@withContext false
        val resp = send("/_leave #$sgid")
        val ok = !resp.contains("\"type\":\"chatCmdError\"")
        ConnectionLog.log(TAG, "leaveGroup #$sgid ok=$ok")
        ok
    }

    /**
     * Send a control command over the separated channel: a custom MsgContent
     * type (x.aegis) instead of the plaintext `[aegis:tag]` text the chat
     * layer (and a vanilla client, and a hand-typer) can see/produce.
     *
     * 1:1 commands ride UNSIGNED — the custom type a vanilla client can't
     * produce, plus SimpleX's authenticated + replay-resistant connection,
     * are the whole guarantee (no application-layer signature or counter). So
     * there is no key lookup and no "not bootstrapped" drop: a direct command
     * can never be silently lost for want of an exchanged control key.
     *
     * [controlBody] is the legacy `[aegis:<cmd>]<data>` envelope; we split it
     * at the first `]`, wrap, and `/_send` it. Keeping the legacy shape lets
     * the receiver reconstruct it and feed the existing handlers unchanged.
     */
    private suspend fun sendControl(
        toKey: String,
        contactId: Long,
        controlBody: String,
        // When set, the control message NATIVELY quotes this itemId. Used
        // by reactions to borrow SimpleX's cross-device quote mapping so
        // the recipient can resolve which message the emote targets.
        quotedItemId: Long? = null,
    ): Boolean {
        val close = controlBody.indexOf(']')
        if (close < 0) return false
        val cmd = controlBody.substring("[aegis:".length, close)
        val data = controlBody.substring(close + 1)

        val env = app.aether.aegis.cmdauth.ControlChannel.buildPlainEnvelope(cmd, data)
        val composed = JSONObject()
            .put("fileSource", JSONObject.NULL)
            .put("quotedItemId", if (quotedItemId != null) quotedItemId else JSONObject.NULL)
            .put("msgContent", env)
            .put("mentions", JSONObject())
        val sendCmd = "/_send @$contactId live=off ttl=default json [$composed]"
        return runCatching {
            val resp = send(sendCmd)
            val ok = !resp.contains("\"type\":\"chatCmdError\"") &&
                !resp.contains("\"type\":\"errorStore\"")
            if (!ok) Log.w(TAG, "sendControl [$cmd] → $toKey rejected: ${resp.take(120)}")
            ok
        }.getOrDefault(false)
    }

    /** Per-peer [aegis:…] gate, defense-in-depth twin of
     *  ProtocolManager.gateAegisControl. Reads the same isAegis flag
     *  out of known_peers and applies the same per-tag rules. Kept
     *  in sync by convention — if you change one, change both. */
    private suspend fun aegisGated(toKey: String, body: String): String? {
        if (!body.startsWith("[aegis:")) return body
        val peer = runCatching {
            AegisApp.instance.repository.knownPeerByKey(toKey)
        }.getOrNull()
        if (peer?.isAegis == true) return body
        return when {
            // Capability-bootstrap envelope — mirror of the
            // ProtocolManager.gateAegisControl carve-out. Has to be
            // here too because some sends skip ProtocolManager and
            // go straight through the transport (e.g. the post-pair
            // handshake from handleContactConnected below).
            body.startsWith("[aegis:hello]") -> body
            // Capability announcement — part of the same bootstrap as the
            // hello, so it must flow during the window before isAegis flips
            // (mirror of the gateAegisControl carve-out). A non-Aegis vanilla
            // peer can't reach here (hello/caps only go to Aegis-intended
            // peers), and an unknown envelope is harmless to any client.
            body.startsWith("[aegis:caps]") -> body
            // Remote-access control surface — same carve-out as in
            // ProtocolManager.gateAegisControl. Without this, AUTH /
            // LOCATE / UPDATE / WIPE and their replies all silently
            // drop when the peer's isAegis flag hasn't flipped, and
            // every remote command "does nothing."
            body.startsWith("[aegis:remote]") -> body
            body.startsWith(app.aether.aegis.remote.RemoteAccessProtocol.WIPE_BROADCAST_PREFIX) -> body
            body.startsWith("[aegis:burn:") -> {
                val close = body.indexOf(']')
                if (close >= 0) body.substring(close + 1).ifBlank { null } else null
            }
            // SOS and the whole sos-* coordination family fall here → drop.
            // SOS is Aegis-only by design: a vanilla SimpleX contact can't be
            // promoted above Untrusted (tier picker is isAegis-gated), so a
            // non-Aegis peer is never an SOS recipient or responder. Mirror of
            // ProtocolManager.gateAegisControl. Do NOT re-add an [aegis:sos*]
            // carve-out — leaking a panic alert to a client that can't act on
            // it is worse than dropping it.
            else -> null
        }
    }

    /**
     * Edit an already-sent message via SimpleX's /_update command.
     * Receiver sees the new text replacing the old one (SimpleX wires
     * this through ChatItemEdit). Best-effort — returns false if the
     * peer binding is gone or the core rejects the update.
     */
    suspend fun editText(toKey: String, itemId: Long, body: String, targetDna: Long? = null): Boolean {
        val contact = peerByKey[toKey] ?: return false
        val contactId = contact.contactId ?: return false
        // Chat-envelope peer with a DNA → edit by DNA over the control channel.
        // Like delete, a native /_update can't reach the peer's copy once Aegis
        // has sealed + purged the transport mirror, and the inbound
        // chatItemUpdated handler only refreshes status, never text — so the
        // edit never landed. The [aegis:editdna] frame resolves the DNA to the
        // peer's sealed row and re-seals the new body in place.
        val supportsDna = targetDna != null && runCatching {
            AegisApp.instance.repository.knownPeerByKey(toKey)?.let {
                app.aether.aegis.protocol.AegisCaps.supports(
                    it.peerCapabilities, app.aether.aegis.protocol.AegisCaps.EDIT_DNA,
                )
            }
        }.getOrNull() == true
        if (supportsDna) {
            val payload = JSONObject().put("d", targetDna).put("t", body).toString()
            return sendControl(toKey, contactId, "[aegis:editdna]$payload")
        }
        val msgContent = JSONObject().put("type", "text").put("text", body)
        val updatedMsg = JSONObject()
            .put("msgContent", msgContent)
            .put("mentions", JSONObject())
        val cmd = "/_update item @$contactId $itemId live=off json $updatedMsg"
        return runCatching {
            val resp = send(cmd)
            !resp.contains("\"type\":\"chatCmdError\"") &&
                !resp.contains("\"type\":\"errorStore\"")
        }.getOrDefault(false)
    }

    /**
     * Most-recent SimpleX itemId published by send(). Keyed by
     * "toKey|body" — the caller (ProtocolManager) looks it up by the
     * same key right after a successful send to thread the SimpleX
     * itemId into the local message row.
     */
    private val sentItemIds =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<Long>>()

    /**
     * Pop the next SimpleX itemId published for "toKey|body" (FIFO). The
     * previous single-slot AtomicReference held only the MOST RECENT send,
     * so whenever a second send landed before the caller consumed the
     * first — concurrent sends, or the outbox draining a batch — the first
     * message's itemId was overwritten and its delivery/read ticks were
     * stranded forever. A per-key queue keeps every pending link.
     */
    fun consumeLastSentItemId(toKey: String, body: String): Long? {
        val key = "$toKey|$body"
        val q = sentItemIds[key] ?: return null
        val id = q.poll()
        if (q.isEmpty()) sentItemIds.remove(key)
        return id
    }

    /**
     * Most-recent message DNA (epoch-nanos) minted for an outbound x.aegis.chat
     * message, keyed by "toKey|body" exactly like [sentItemIds]. The caller
     * pops it right after a successful send to thread the DNA onto the local
     * row, so a later read receipt that echoes this DNA matches our own message.
     * Empty for vanilla (MCText) sends — those carry no DNA.
     */
    private val sentDnas =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<Long>>()

    /** Pop the next minted DNA published for "toKey|body" (FIFO), or null. */
    fun consumeLastSentDna(toKey: String, body: String): Long? {
        val key = "$toKey|$body"
        val q = sentDnas[key] ?: return null
        val dna = q.poll()
        if (q.isEmpty()) sentDnas.remove(key)
        return dna
    }

    /**
     * Parses chatItems[0].chatItem.meta.itemId out of a /_send response.
     * Response shape: {result:{type:newChatItems, user, chatItems:[
     *   {chatInfo, chatItem:{chatDir, meta:{itemId,...}, ...}}, ...]}}
     */
    private fun parseFirstItemId(resp: String): Long? = runCatching {
        val root = JSONObject(resp)
        val obj = root.optJSONObject("result")
            ?: root.optJSONObject("resp")
            ?: root.optJSONObject("chatResponse")
            ?: return@runCatching null
        val items = obj.optJSONArray("chatItems") ?: return@runCatching null
        if (items.length() == 0) return@runCatching null
        items.getJSONObject(0)
            .optJSONObject("chatItem")
            ?.optJSONObject("meta")
            ?.optLong("itemId", -1L)
            ?.takeIf { it > 0 }
    }.getOrNull()

    /**
     * Create a new SimpleX one-time invitation link.
     *
     * The user-supplied `label` is held in a single-slot pending map; when
     * SimpleX later emits `contactConnected` for this invitation, we use
     * that label as the KnownPeer's display name. Until then, the link
     * is just a string the user shares out-of-band.
     */
    sealed class InviteResult {
        data class Ok(val uri: String) : InviteResult()
        data object SimplexNotReady : InviteResult()
        data class ParseFailed(val rawResponse: String) : InviteResult()
    }

    suspend fun generateInvitation(label: String): InviteResult = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext InviteResult.SimplexNotReady
        val uid = resolveUserId() ?: return@withContext InviteResult.SimplexNotReady
        // AEGIS PROTOCOL: the invite link you hand out is incognito — the
        // peer who scans it pairs with your random handle, never your real
        // profile. The `off` path is deleted (Origin #19).
        // Upstream format from SimpleXAPI.kt line 3956: "/_connect $userId incognito=on"
        var resp = send("/_connect $uid incognito=on")
        // Self-heal: a SimpleX install that booted before my user-create
        // fix landed has no active user — re-run ensureUserProfile and
        // retry once. After this point the user is permanent.
        if (resp.contains("\"type\":\"noActiveUser\"")) {
            android.util.Log.i(TAG, "noActiveUser on /_connect — recreating + retrying")
            ensureUserProfile()
            val retryUid = resolveUserId() ?: return@withContext InviteResult.SimplexNotReady
            resp = send("/_connect $retryUid incognito=on")
        }
        val uri = parseUriFromResponse(resp)
        if (uri == null) {
            android.util.Log.w(TAG, "couldn't extract invitation URI from: ${resp.take(500)}")
            return@withContext InviteResult.ParseFailed(resp)
        }
        pendingInviteLabel = label
        // Persist the pending link so it can be listed / revoked /
        // auto-expired. connId (the
        // relay-side pccConnId) is the revoke handle. If we can't
        // parse it the link still works — it just won't appear in the
        // manageable list, which is a safe degradation.
        val connId = parseConnIdFromResponse(resp)
        if (connId != null) {
            runCatching {
                AegisApp.instance.repository.addPendingInvitation(connId, uri, label)
            }
        } else {
            android.util.Log.w(TAG, "no pccConnId in invitation response — link not tracked")
        }
        InviteResult.Ok(uri)
    }

    /**
     * Revoke a pending 1:1 invitation.
     * Deletes the pending connection on the relay via upstream
     * `/_delete :<connId> full notify=on` (the ContactConnection chat
     * ref is `:<pccConnId>`), then drops the local row. The link goes
     * dead — anyone who tries it afterwards gets a connection error.
     *
     * Best-effort: if the connection already completed or was already
     * gone, we still remove the local row so the list stays clean.
     * Returns true on a clean relay delete.
     */
    suspend fun revokePendingInvitation(connId: Long): Boolean = withContext(Dispatchers.IO) {
        val ok = if (isHealthy) {
            val resp = send("/_delete :$connId full notify=on")
            !resp.contains("\"type\":\"chatCmdError\"")
        } else false
        runCatching { AegisApp.instance.repository.removePendingInvitation(connId) }
        ConnectionLog.log(TAG, "revokePendingInvitation :$connId ok=$ok")
        ok
    }

    /** Pull the pending connection id (pccConnId) out of an invitation
     *  response — the handle used to revoke the link. Mirrors
     *  [parseUriFromResponse]'s envelope handling. */
    private fun parseConnIdFromResponse(resp: String): Long? = runCatching {
        val root = JSONObject(resp)
        val obj = root.optJSONObject("result")
            ?: root.optJSONObject("resp")
            ?: root.optJSONObject("chatResponse")
            ?: return@runCatching null
        obj.optJSONObject("connection")
            ?.optLong("pccConnId", -1L)
            ?.takeIf { it > 0 }
    }.getOrNull()

    /**
     * Accept a peer's invitation link. `label` is what we'll display this
     * contact as locally; the SimpleX-side localDisplayName is whatever
     * the core picks and we map it via `peerByKey`.
     */
    /**
     * The two-field link pair that the SimpleX core operates on.
     * Lifted verbatim from upstream
     * [`CreatedConnLink`](SimpleXAPI.kt:6946):
     *
     *     data class CreatedConnLink(
     *         val connFullLink: String,
     *         val connShortLink: String?
     *     )
     *
     * A long link (`simplex:/...`, `https://simplex.chat/...`) is
     * itself the `connFullLink` and `connShortLink` is null. A
     * preset-server short link (`https://smp16.simplex.im/a#...`)
     * round-trips through `apiConnectPlan`, which fetches the
     * canonical full link from the SMP server and returns both.
     */
    data class CreatedConnLink(val connFullLink: String, val connShortLink: String?)

    /**
     * Outcome of an apiConnectPlan call. Carries the resolved
     * [CreatedConnLink] plus a [ResolvedPlan] discriminating which
     * follow-up API the caller should call. Mirrors upstream's
     * `Pair<CreatedConnLink, ConnectionPlan>` return type from
     * SimpleXAPI.kt:1518 with the ConnectionPlan narrowed to only
     * the cases we act on.
     */
    data class PlanResult(val link: CreatedConnLink, val plan: ResolvedPlan)

    /**
     * The branches of upstream's `sealed class ConnectionPlan`
     * (SimpleXAPI.kt:6963) we care about for outbound connect. The
     * core's response carries enough nesting to drive distinct
     * follow-up paths:
     *
     * - **[InvitationOk]** — first-time use of an invitation link.
     *   Follow up with [apiConnect]; the success response will be
     *   `sentConfirmation` or `sentInvitation`.
     *
     * - **[ContactAddressViaAddress]** — first-time use of a
     *   contact address (`/a#…` short link). The plan carries a
     *   provisional contactId; follow up with
     *   [apiConnectContactViaAddress] (NOT apiConnect), success
     *   response is `sentInvitationToContact`.
     *
     * - **[ContactAddressOk]** — contact-address link with no
     *   pre-allocated contactId. Follow up with [apiConnect] and
     *   expect `sentInvitationToContact`.
     *
     * - **[Known]** — already paired with this contact. Caller
     *   should bind locally (the [contactId] / [displayName]
     *   identify the existing pairing) and surface a friendly
     *   "already connected" note instead of failing.
     *
     * - **[OwnLink]** — the user pasted their own link. No-op.
     *
     * - **[GroupLink]** — the URL points at a group join. Outside
     *   the contact-adding flow; the inner [groupLinkPlan] mirrors
     *   upstream's [GroupLinkPlan] sealed class so the caller can
     *   pick between the simple `/_connect` flavour and the short-
     *   link prepare + connect-prepared flavour.
     *
     * - **[Unsupported]** — anything we don't know how to drive
     *   yet. Caller surfaces the raw plan type.
     */
    sealed class ResolvedPlan {
        object InvitationOk : ResolvedPlan()
        data class ContactAddressViaAddress(val contactId: Long, val displayName: String) : ResolvedPlan()
        object ContactAddressOk : ResolvedPlan()
        data class Known(val contactId: Long?, val displayName: String) : ResolvedPlan()
        object OwnLink : ResolvedPlan()
        /** Group-join plan. [shortLinkData] carries the
         *  `groupSLinkData_` JSON when the URL is a `/g#…` short
         *  link — that's the input apiPrepareGroup wants. When null
         *  the long-link flavour just calls apiConnect directly.
         *  [knownGroupId] is set when the core says we're already
         *  in this group (or have a join in flight). */
        data class GroupLink(
            val shortLinkData: JSONObject? = null,
            val knownGroupId: Long? = null,
            val knownDisplayName: String? = null,
            val directLink: Boolean = false,
        ) : ResolvedPlan()
        data class Unsupported(val planType: String, val subPlanType: String?) : ResolvedPlan()
    }

    /** Outcome of an [apiConnect] / [apiConnectContactViaAddress]
     *  call, mirroring the discriminated response handling upstream
     *  does at SimpleXAPI.kt:1529-1538 + 1664. */
    sealed class ConnectResult {
        /** Core accepted the link, sent confirmation or invitation
         *  out. For invitation links the pairing completes when
         *  contactConnected arrives; for contact addresses, when
         *  the other side accepts. */
        object Sent : ConnectResult()

        /** Core already has this contact paired. [displayName] is
         *  the SimpleX-side localDisplayName, [contactId] the
         *  core's numeric id (used to bind locally). */
        data class AlreadyExists(val displayName: String, val contactId: Long?) : ConnectResult()

        /** Core rejected the link / pairing failed. [reason] is the
         *  truncated raw response — surfaced verbatim so we don't
         *  paraphrase the core's error and the next bug report
         *  carries actual evidence. */
        data class Error(val reason: String) : ConnectResult()
    }

    /**
     * Port of upstream
     * [`apiConnectPlan`](SimpleXAPI.kt:1518). Returns the resolved
     * link pair AND the branch the caller should take.
     */
    suspend fun apiConnectPlan(connLink: String): PlanResult? =
        withContext(Dispatchers.IO) {
            val uid = resolveUserId() ?: run {
                lastJoinError = "No active SimpleX user yet — wait for the core to finish " +
                    "starting (chat-list network indicator should be green), then retry."
                return@withContext null
            }
            // Upstream command shape from SimpleXAPI.kt:3961:
            //   /_connect plan $userId $connLink$sigStr
            // We don't pass a LinkOwnerSig (upstream defaults to
            // null), so $sigStr is empty.
            val resp = send("/_connect plan $uid $connLink")
            val root = runCatching { JSONObject(resp) }.getOrNull() ?: run {
                lastJoinError = "SimpleX core returned a non-JSON reply: ${resp.take(160)}"
                return@withContext null
            }
            val obj = root.optJSONObject("result")
                ?: root.optJSONObject("resp")
                ?: root.optJSONObject("chatResponse")
                ?: run {
                    lastJoinError = "SimpleX core reply missing result envelope: ${resp.take(160)}"
                    return@withContext null
                }
            val rtype = obj.optString("type")
            // Diagnostic: a short-link group join can be rejected at THIS
            // step (resolving the link) rather than at /_prepare group, and
            // that path was logcat-only. Surface the step + full reply so
            // an invalidConnReq is traced to where it actually happens.
            ConnectionLog.log(TAG, "connectPlan ← type=$rtype for '${connLink.take(64)}'")
            if (rtype == "chatCmdError" || rtype == "chatError") {
                val msg = chatErrorMessage(obj)
                ConnectionLog.warn(TAG, "connectPlan rejected: ${msg ?: "?"} | ${resp.take(300)}")
                lastJoinError = msg ?: "Core rejected the link: ${resp.take(160)}"
                return@withContext null
            }
            if (rtype != "connectionPlan") {
                ConnectionLog.warn(TAG, "connectPlan unexpected type '$rtype': ${resp.take(300)}")
                lastJoinError = "Unexpected core response type '$rtype' — ${resp.take(160)}"
                return@withContext null
            }
            val cl = obj.optJSONObject("connLink") ?: run {
                lastJoinError = "Core response missing connLink: ${resp.take(160)}"
                return@withContext null
            }
            val full = cl.optString("connFullLink").takeIf { it.isNotBlank() }
                ?: run {
                    lastJoinError = "Core response missing connFullLink: ${resp.take(160)}"
                    return@withContext null
                }
            val short = cl.optString("connShortLink").takeIf { it.isNotBlank() }
            val link = CreatedConnLink(full, short)
            val plan = obj.optJSONObject("connectionPlan") ?: run {
                lastJoinError = "Core response missing connectionPlan body: ${resp.take(160)}"
                return@withContext null
            }
            PlanResult(link, parsePlan(plan))
        }

    /** Decode the nested ConnectionPlan JSON into the narrowed
     *  [ResolvedPlan] hierarchy. Mirrors the upstream sealed-class
     *  shape at SimpleXAPI.kt:6963 + 6970 + 6979. */
    private fun parsePlan(plan: JSONObject): ResolvedPlan {
        val planType = plan.optString("type")
        return when (planType) {
            "invitationLink" -> {
                val sub = plan.optJSONObject("invitationLinkPlan")
                val subType = sub?.optString("type")
                when (subType) {
                    "ok" -> ResolvedPlan.InvitationOk
                    "ownLink" -> ResolvedPlan.OwnLink
                    "known" -> {
                        val c = sub.optJSONObject("contact")
                        ResolvedPlan.Known(
                            contactId = c?.optLong("contactId", -1L)?.takeIf { it > 0 },
                            displayName = c?.optString("localDisplayName").orEmpty(),
                        )
                    }
                    "connecting" -> {
                        // Contact pairing is already in flight — treat
                        // as Sent so the UI doesn't double-fire.
                        ResolvedPlan.InvitationOk
                    }
                    else -> ResolvedPlan.Unsupported(planType, subType)
                }
            }
            "contactAddress" -> {
                val sub = plan.optJSONObject("contactAddressPlan")
                val subType = sub?.optString("type")
                when (subType) {
                    "ok" -> ResolvedPlan.ContactAddressOk
                    "ownLink" -> ResolvedPlan.OwnLink
                    "known", "connectingProhibit" -> {
                        val c = sub.optJSONObject("contact")
                        ResolvedPlan.Known(
                            contactId = c?.optLong("contactId", -1L)?.takeIf { it > 0 },
                            displayName = c?.optString("localDisplayName").orEmpty(),
                        )
                    }
                    "contactViaAddress" -> {
                        val c = sub.optJSONObject("contact")
                        val cid = c?.optLong("contactId", -1L)?.takeIf { it > 0 }
                        val name = c?.optString("localDisplayName").orEmpty()
                        if (cid != null) ResolvedPlan.ContactAddressViaAddress(cid, name)
                        else ResolvedPlan.ContactAddressOk
                    }
                    "connectingConfirmReconnect" -> ResolvedPlan.ContactAddressOk
                    else -> ResolvedPlan.Unsupported(planType, subType)
                }
            }
            "groupLink" -> {
                val sub = plan.optJSONObject("groupLinkPlan")
                val subType = sub?.optString("type")
                when (subType) {
                    "ok" -> ResolvedPlan.GroupLink(
                        shortLinkData = sub.optJSONObject("groupSLinkData_"),
                        // groupSLinkInfo_.direct decides how the prepared
                        // group connects: a `direct` link joins the host
                        // member straight, a non-direct one routes via the
                        // group's relays. Upstream feeds exactly this flag to
                        // apiPrepareGroup (`direct=on/off`). We previously
                        // hardcoded it false — so a direct-link group (small
                        // group, no relays) got prepared as a relay join with
                        // no relays, and /_connect group then failed with
                        // invalidConnReq on an otherwise-valid Share link.
                        directLink = sub.optJSONObject("groupSLinkInfo_")
                            ?.optBoolean("direct", false) ?: false,
                    )
                    "known" -> {
                        val g = sub.optJSONObject("groupInfo")
                        ResolvedPlan.GroupLink(
                            knownGroupId = g?.optLong("groupId", -1L)?.takeIf { it > 0 },
                            knownDisplayName = g?.optJSONObject("groupProfile")
                                ?.optString("displayName")
                                ?.takeIf { it.isNotBlank() }
                                ?: g?.optString("localDisplayName").orEmpty(),
                        )
                    }
                    "ownLink" -> ResolvedPlan.OwnLink
                    // connectingConfirmReconnect / connectingProhibit /
                    // noRelays — treat as Ok-with-no-prepare so we
                    // just fire /_connect; the core will re-emit the
                    // appropriate connecting/error event downstream.
                    "connectingConfirmReconnect",
                    "connectingProhibit",
                    "noRelays" -> ResolvedPlan.GroupLink()
                    else -> ResolvedPlan.Unsupported(planType, subType)
                }
            }
            else -> ResolvedPlan.Unsupported(planType, null)
        }
    }

    /**
     * Port of upstream
     * [`apiConnect`](SimpleXAPI.kt:1526). Used for the
     * **invitation-link** flow and the first-time
     * **contact-address Ok** flow.
     */
    /**
     * Port of upstream [reconnectAllServers](SimpleXAPI.kt:1369) →
     * wire command `/reconnect` (SimpleXAPI.kt:3938). Tells the core
     * to drop and re-establish every SMP relay subscription. The
     * right thing to call when the platform tells us the network
     * just changed (wifi → cell, IP renewed, captive portal cleared,
     * VPN toggled), since SMP socket reads can stall silently after
     * a route flip and our pump won't notice until it next gets a
     * read error — by which time messages have been sitting at the
     * relay un-fetched.
     *
     * Idempotent — safe to call on every network event; the core
     * coalesces back-to-back reconnect commands and a no-op
     * (everything already connected) just returns cmdOk.
     */
    suspend fun apiReconnect(): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        val resp = send("/reconnect")
        val root = runCatching { JSONObject(resp) }.getOrNull() ?: return@withContext false
        val obj = root.optJSONObject("result")
            ?: root.optJSONObject("resp")
            ?: root.optJSONObject("chatResponse")
            ?: return@withContext false
        val ok = obj.optString("type") == "cmdOk"
        if (!ok) Log.w(TAG, "apiReconnect: unexpected response: ${resp.take(220)}")
        if (ok) {
            // A reconnect can follow a long offline gap during which a
            // bootstrap hello may have been dropped. Re-greet any peer still
            // missing a control pubkey so the signed channel isn't stuck
            // dead until the next cold start (same rationale as post-start).
            scope.launch {
                runCatching {
                    app.aether.aegis.admin.HelloBroadcaster.broadcastNow(AegisApp.instance)
                }.onFailure { Log.w(TAG, "post-reconnect hello bootstrap failed", it) }
            }
        }
        ok
    }

    suspend fun apiConnect(connLink: CreatedConnLink): ConnectResult =
        withContext(Dispatchers.IO) {
            val uid = resolveUserId()
                ?: return@withContext ConnectResult.Error("no active user")
            // AEGIS PROTOCOL — code-deletion requirement: there is NO
            // incognito parameter and
            // NO non-incognito branch. The only code path emits incognito=on.
            // You cannot flip, by a bug or an upstream merge, a flag that
            // doesn't exist — the function to share a real profile is absent.
            val short = connLink.connShortLink ?: ""
            val resp = send("/_connect $uid incognito=on ${connLink.connFullLink} $short")
            parseConnectResponse(resp)
        }

    /**
     * Port of upstream
     * [`apiPrepareGroup`](SimpleXAPI.kt:1616). Required first step
     * for joining a `/g#…` SHORT group link: hands the resolved
     * [CreatedConnLink] + the inner `groupSLinkData_` (which
     * carries the GroupProfile pulled from the SMP server during
     * apiConnectPlan) to the core, which materialises a "prepared"
     * Chat row and returns its groupId. The follow-up call to
     * [apiConnectPreparedGroup] uses that groupId to actually
     * trigger the join.
     *
     * Returns the prepared groupId on success, null on error
     * (which is logged + raised to the caller as "couldn't join").
     */
    suspend fun apiPrepareGroup(
        connLink: CreatedConnLink,
        groupShortLinkData: JSONObject,
        directLink: Boolean = false,
    ): Long? = withContext(Dispatchers.IO) {
        val uid = resolveUserId() ?: return@withContext null
        val short = connLink.connShortLink ?: ""
        val dir = if (directLink) "on" else "off"
        // Upstream command shape from SimpleXAPI.kt:3964:
        //   /_prepare group $userId $connFullLink $connShortLink direct=on/off $groupShortLinkData(json)
        // Diagnostic: surface the exact pieces we feed /_prepare group so an
        // invalidConnReq can be traced to the link, the short-link slot, or
        // the short-link-data. Goes to the in-app connection log (the
        // join failure was previously only visible in logcat).
        ConnectionLog.log(
            TAG,
            "prepareGroup → full='${connLink.connFullLink.take(72)}' " +
                "shortEmpty=${short.isBlank()} short='${short.take(48)}' " +
                "direct=$dir sld='${groupShortLinkData.toString().take(110)}'",
        )
        val resp = send(
            "/_prepare group $uid ${connLink.connFullLink} $short direct=$dir $groupShortLinkData",
        )
        val root = runCatching { JSONObject(resp) }.getOrNull() ?: run {
            ConnectionLog.warn(TAG, "prepareGroup: non-JSON response: ${resp.take(300)}")
            return@withContext null
        }
        val obj = root.optJSONObject("result")
            ?: root.optJSONObject("resp")
            ?: root.optJSONObject("chatResponse")
            ?: run {
                ConnectionLog.warn(TAG, "prepareGroup: no result envelope: ${resp.take(300)}")
                return@withContext null
            }
        val rtype = obj.optString("type")
        if (rtype == "chatCmdError" || rtype == "chatError") {
            val msg = chatErrorMessage(obj)
            ConnectionLog.warn(TAG, "prepareGroup: core rejected — ${msg ?: "?"} | full=${resp.take(300)}")
            if (msg != null) lastJoinError = msg
            return@withContext null
        }
        if (rtype != "newPreparedChat") {
            Log.w(TAG, "apiPrepareGroup: unexpected response: ${resp.take(220)}")
            return@withContext null
        }
        // chat.chatInfo.groupInfo.groupId is what we need to feed
        // into apiConnectPreparedGroup.
        val gid = obj.optJSONObject("chat")
            ?.optJSONObject("chatInfo")
            ?.optJSONObject("groupInfo")
            ?.optLong("groupId", -1L)
            ?.takeIf { it > 0 }
        if (gid == null) {
            Log.w(TAG, "apiPrepareGroup: no groupId in newPreparedChat: ${resp.take(220)}")
        }
        gid
    }

    /**
     * Port of upstream
     * [`apiConnectPreparedGroup`](SimpleXAPI.kt:1651). Second step
     * of the short-link group-join flow. Takes the groupId minted
     * by [apiPrepareGroup] and actually triggers the join — the
     * core then emits the lifecycle events
     * (`userAcceptedGroupSent` / `groupLinkConnecting` /
     * `userJoinedGroup`) that [handleGroupJoined] persists.
     *
     * Returns [ConnectResult.Sent] on the core's
     * `startedConnectionToGroup` response. Anything else is
     * surfaced via [ConnectResult.Error] so the UI can fall back
     * to the failure copy.
     */
    suspend fun apiConnectPreparedGroup(
        groupId: Long,
    ): ConnectResult = withContext(Dispatchers.IO) {
        // AEGIS PROTOCOL (Origin #19): groups are always incognito too —
        // no parameter, no `off` branch.
        // Upstream command shape from SimpleXAPI.kt:3968:
        //   /_connect group #$groupId incognito=on
        // No initial message — upstream's UI lets the user type one,
        // we just join silently.
        val resp = send("/_connect group #$groupId incognito=on")
        val root = runCatching { JSONObject(resp) }.getOrNull()
            ?: return@withContext ConnectResult.Error(resp.take(220))
        val obj = root.optJSONObject("result")
            ?: root.optJSONObject("resp")
            ?: root.optJSONObject("chatResponse")
            // A rejected join (dead/expired/spam link) comes back as a bare
            // top-level {"error":{…invalidConnReq…}} with no result wrapper —
            // hand the whole root to the mapper so the user gets the curated
            // "invalid link, ask for a new one" copy instead of raw JSON.
            ?: return@withContext ConnectResult.Error(
                chatErrorMessage(root) ?: resp.take(220),
            )
        when (obj.optString("type")) {
            "startedConnectionToGroup" -> {
                // The synchronous response already carries the
                // groupInfo we'd otherwise wait for in the
                // lifecycle events. Persist immediately so the
                // Groups tab surfaces the row right after the toast
                // dismisses, instead of staying empty until the
                // host accepts (which can take many seconds).
                handleGroupJoined(obj)
                ConnectResult.Sent
            }
            "chatCmdError", "chatError" ->
                ConnectResult.Error(chatErrorMessage(obj) ?: resp.take(220))
            else -> {
                Log.w(TAG, "apiConnectPreparedGroup: unexpected response: ${resp.take(220)}")
                ConnectResult.Error(resp.take(220))
            }
        }
    }

    /**
     * Port of upstream
     * [`apiConnectContactViaAddress`](SimpleXAPI.kt:1661). Used
     * when the connection plan returns
     * `ContactAddressPlan.ContactViaAddress(contact)` carrying a
     * pre-allocated contactId.
     */
    suspend fun apiConnectContactViaAddress(contactId: Long): ConnectResult =
        withContext(Dispatchers.IO) {
            val uid = resolveUserId()
                ?: return@withContext ConnectResult.Error("no active user")
            // AEGIS PROTOCOL (Origin #19): incognito always — no parameter,
            // no `off` branch.
            // Upstream command shape from SimpleXAPI.kt:3970:
            //   /_connect contact $userId incognito=on $contactId
            val resp = send("/_connect contact $uid incognito=on $contactId")
            parseConnectResponse(resp)
        }

    /** Common discriminator for both /_connect entry points.
     *  Upstream apiConnect handles sentConfirmation / sentInvitation
     *  / contactAlreadyExists (SimpleXAPI.kt:1529); upstream
     *  apiConnectContactViaAddress handles sentInvitationToContact
     *  (SimpleXAPI.kt:1664). We collapse both into one parser since
     *  the caller doesn't need to distinguish which command was
     *  sent. */
    private fun parseConnectResponse(resp: String): ConnectResult {
        val root = runCatching { JSONObject(resp) }.getOrNull()
            ?: return ConnectResult.Error(resp.take(220))
        val obj = root.optJSONObject("result")
            ?: root.optJSONObject("resp")
            ?: root.optJSONObject("chatResponse")
            // Bare {"error":{…}} envelope (dead/expired/spam link) — route to
            // the curated mapper instead of leaking raw JSON to the user.
            ?: return ConnectResult.Error(chatErrorMessage(root) ?: resp.take(220))
        return when (obj.optString("type")) {
            "sentConfirmation", "sentInvitation", "sentInvitationToContact" ->
                ConnectResult.Sent
            "contactAlreadyExists" -> {
                val c = obj.optJSONObject("contact")
                ConnectResult.AlreadyExists(
                    displayName = c?.optString("localDisplayName")
                        ?.takeIf { it.isNotBlank() } ?: "(unknown)",
                    contactId = c?.optLong("contactId", -1L)?.takeIf { it > 0 },
                )
            }
            "chatCmdError", "chatError" ->
                ConnectResult.Error(chatErrorMessage(obj) ?: resp.take(220))
            else -> {
                Log.w(TAG, "apiConnect: unexpected response: ${resp.take(220)}")
                ConnectResult.Error(resp.take(220))
            }
        }
    }

    /** Map a chat-core error response to upstream's user-facing copy
     *  (SimpleXAPI.kt:1590 `connErrorText` / 1542 `apiConnectResponseAlert`).
     *  Returns null when the error type isn't one we have curated text
     *  for — caller falls back to a raw response excerpt so the user
     *  still has something to act on.
     *
     *  JSON shape (chatCmdError):
     *      { type: "chatCmdError",
     *        chatError: { type: "error",      errorType: { type: "invalidConnReq" } } }
     *      { type: "chatCmdError",
     *        chatError: { type: "errorAgent", agentError: { type: "SMP", smpErr: {...} } } }
     */
    private fun chatErrorMessage(errObj: JSONObject): String? {
        // The core nests the error two ways: `chatCmdError` responses carry
        // it under `chatError`, but some command failures (notably
        // `/_connect group` rejecting a dead/expired link) arrive as a bare
        // top-level `{"error":{type:"error",errorType:{…}}}` envelope with no
        // result/resp wrapper at all. Accept either key so a truncated
        // `{"error":…invalidConnReq…}` blob never leaks to the user in place
        // of the curated copy.
        val chatError = errObj.optJSONObject("chatError")
            ?: errObj.optJSONObject("error")
            ?: return null
        return when (chatError.optString("type")) {
            "error" -> {
                val et = chatError.optJSONObject("errorType")?.optString("type")
                when (et) {
                    "invalidConnReq" ->
                        "Invalid connection link. Check it wasn't truncated on copy " +
                            "(the chunk after `#` is the key — any missing character " +
                            "breaks it). One-time invitation links also stop working " +
                            "after the first acceptance, and the host can revoke or " +
                            "regenerate a group link."
                    "unsupportedConnReq" ->
                        "This link uses a newer protocol than this Aegis build supports."
                    "connReqMessageProhibited" ->
                        "The host's link doesn't allow connection messages — try again " +
                            "without typing one (we already don't, so this is likely a " +
                            "core mismatch — report it)."
                    null, "" -> null
                    else -> "Core error: $et"
                }
            }
            "errorAgent" -> {
                val agentError = chatError.optJSONObject("agentError") ?: return null
                when (agentError.optString("type")) {
                    "SMP" -> {
                        val smp = agentError.optJSONObject("smpErr")?.optString("type")
                        when (smp) {
                            "AUTH" ->
                                "The relay rejected the connection (queue already used " +
                                    "or revoked). One-time invitation links are valid for " +
                                    "exactly one acceptance — ask the host to share a " +
                                    "fresh link."
                            "QUOTA" ->
                                "The host's relay is over quota. Try again later or ask " +
                                    "the host to share a link via a different relay."
                            "BLOCKED" ->
                                "The host's relay has blocked this queue."
                            null, "" -> null
                            else -> "SMP error: $smp"
                        }
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * Orchestrator used by the UI's "Accept invitation" flow.
     * Mirrors the upstream sequence: plan to resolve any link
     * (long OR preset-server short) into a canonical
     * [CreatedConnLink] + [ResolvedPlan] pair, then dispatch the
     * correct follow-up API based on the plan branch.
     *
     * Group joins are ALWAYS incognito.
     * There is deliberately no parameter to request a non-incognito
     * group join — the private 1:1 profile must never reach a group.
     * 1:1 contact joins are unaffected (they use the caller's normal
     * profile, as before).
     */
    suspend fun acceptInvitation(
        simplexUri: String,
        label: String,
        // Set true on the single in-process retry after we clear an
        // orphaned phantom group record (see the GroupLink Known branch).
        // Guards against looping if the core won't drop the phantom.
        phantomRetried: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.IO) {
            lastJoinError = null
            lastJoinedGroupAegisId = null
            if (!isHealthy) {
                lastJoinError = "SimpleX core isn't running. Wait a moment and retry, or " +
                    "check the network indicator on the chat list."
                return@withContext false
            }

            val planned = apiConnectPlan(simplexUri) ?: run {
                Log.w(TAG, "acceptInvitation: plan failed for $simplexUri")
                // apiConnectPlan sets a specific lastJoinError on every
                // null branch (non-JSON, chatCmdError → curated copy,
                // missing envelope, etc.). Only fall back to the generic
                // "didn't recognise" copy if nothing more specific was
                // captured — previously this clobbered the real reason.
                if (lastJoinError == null) {
                    lastJoinError = "The SimpleX core didn't recognise that link. Double-check " +
                        "the link wasn't truncated when copied — the part after `#` is the key " +
                        "and any missing character breaks it."
                }
                return@withContext false
            }
            ConnectionLog.log(TAG, "acceptInvitation: plan=${planned.plan::class.simpleName} link=${planned.link.connFullLink.take(80)}")
            val outcome: ConnectResult = when (val p = planned.plan) {
                ResolvedPlan.InvitationOk,
                ResolvedPlan.ContactAddressOk ->
                    apiConnect(planned.link)
                is ResolvedPlan.ContactAddressViaAddress ->
                    apiConnectContactViaAddress(p.contactId)
                is ResolvedPlan.Known -> {
                    // Core already has the pairing. Don't re-issue
                    // /_connect (would error); just synthesise an
                    // AlreadyExists so the AlreadyExists branch
                    // below binds it locally.
                    ConnectResult.AlreadyExists(p.displayName, p.contactId)
                }
                ResolvedPlan.OwnLink -> {
                    Log.w(TAG, "acceptInvitation: refusing to connect to own link")
                    ConnectResult.Error("That link is your own — you can't add yourself.")
                }
                is ResolvedPlan.GroupLink -> {
                    when {
                        // "Known" branch — core already has us in
                        // the group. Skip /_connect (would error)
                        // and persist the local row idempotently so
                        // the Groups tab surfaces it on next refresh.
                        p.knownGroupId != null -> {
                            val repo = AegisApp.instance.repository
                            val existing = runCatching {
                                repo.getGroupBySimplexId(p.knownGroupId)
                            }.getOrNull()
                            if (existing != null) {
                                // We hold a REAL local group for this — we are
                                // genuinely a member. Open it (pasting a known
                                // group's link takes you into it).
                                ConnectionLog.log(TAG, "acceptInvitation: GroupLink Known #${p.knownGroupId} — opening existing group ${existing.id}")
                                lastJoinedGroupAegisId = existing.id
                                ConnectResult.Sent
                            } else {
                                // Core says "Known" but we hold NO local group:
                                // an ORPHANED PHANTOM from a prior half-join.
                                // apiPrepareGroup persists a core group record
                                // BEFORE the connect lands; if that connect
                                // never completes (host didn't accept, stalled,
                                // user left), the record stays — and every
                                // later paste then gets "Known" and can NEVER
                                // re-join. Aegis's own group-delete only removed
                                // the local row, never the core's. Delete the
                                // phantom from the core (`/_delete … full`) so a
                                // fresh paste can actually join.
                                ConnectionLog.log(TAG, "acceptInvitation: GroupLink Known #${p.knownGroupId} but NO local group — orphaned phantom; clearing core record")
                                runCatching { send("/_delete #${p.knownGroupId} full notify=off") }
                                    .onFailure { Log.w(TAG, "phantom /_delete #${p.knownGroupId} failed", it) }
                                if (!phantomRetried) {
                                    // Clear-and-retry in ONE tap. The /_delete is
                                    // async, so wait briefly for the core to drop
                                    // the phantom, then re-plan + connect the SAME
                                    // link. Previously we returned a red "Tap Join
                                    // once more to connect" here — but that read as
                                    // a failure, and if the delete hadn't landed a
                                    // second manual tap just hit the same phantom
                                    // again, so the error "never cleared"
                                    // (user-reported). Retrying in-process makes a
                                    // single Join actually join.
                                    ConnectionLog.log(TAG, "acceptInvitation: phantom cleared — auto-retrying join once")
                                    delay(700)
                                    return@withContext acceptInvitation(simplexUri, label, phantomRetried = true)
                                }
                                // Retried once and the core STILL reports the
                                // phantom — it won't let go. Surface a real error.
                                lastJoinError =
                                    "Couldn't clear a leftover group record from an " +
                                        "earlier attempt. Restart Aegis and try the link again."
                                ConnectResult.Error(lastJoinError!!)
                            }
                        }
                        // Short-link flavour — upstream uses
                        // prepare + connect-prepared because plain
                        // /_connect on a `/g#…` URL with the inline
                        // GroupShortLinkData doesn't queue the join
                        // (the core needs the prepared chat row
                        // first). Without this we'd silently send
                        // the right command but never see a join
                        // lifecycle event.
                        p.shortLinkData != null -> {
                            // PRIVACY: group joins are always incognito.
                            // The private
                            // 1:1 profile never reaches a group; there
                            // is no "reveal real identity" path.
                            val incog = true
                            ConnectionLog.log(TAG, "acceptInvitation: GroupLink short — prepare+connectPrepared (incognito=$incog)")
                            val gid = apiPrepareGroup(planned.link, p.shortLinkData, p.directLink)
                            if (gid == null) {
                                Log.w(TAG, "acceptInvitation: prepareGroup returned null")
                                // apiPrepareGroup may have already set
                                // lastJoinError with the core's actual
                                // error (e.g. invalidConnReq); prefer
                                // that over the generic fallback.
                                ConnectResult.Error(
                                    lastJoinError
                                        ?: "Couldn't prepare the group join (server didn't return a group id).",
                                )
                            } else {
                                val r = apiConnectPreparedGroup(gid)
                                if (r is ConnectResult.Sent) {
                                    // Only stash the join context once the
                                    // connect actually queued — otherwise we'd
                                    // tie this URI to a group row we're about
                                    // to delete.
                                    stashGroupJoinContext(gid, simplexUri, realIdentity = !incog)
                                    ConnectionLog.log(TAG, "acceptInvitation: connectPreparedGroup(#$gid) outcome=Sent")
                                    r
                                } else {
                                    // ROOT-CAUSE PREVENTION for the orphaned-
                                    // phantom bug: apiPrepareGroup already
                                    // persisted a core group row (so a later
                                    // re-paste of the same link returns
                                    // GroupLinkPlan.Known and can never join).
                                    // If the connect didn't queue, that row is
                                    // dead weight — delete it now so the next
                                    // paste sees a clean slate instead of a
                                    // Known phantom we have to clear after the
                                    // fact.
                                    ConnectionLog.log(TAG, "acceptInvitation: connectPreparedGroup(#$gid) outcome=${r::class.simpleName} — rolling back prepared group")
                                    runCatching { send("/_delete #$gid full notify=off") }
                                        .onFailure { Log.w(TAG, "rollback /_delete #$gid failed", it) }
                                    r
                                }
                            }
                        }
                        else -> {
                            val incog = true  // Part 0: groups always incognito
                            ConnectionLog.log(TAG, "acceptInvitation: GroupLink long — /_connect (incognito=$incog)")
                            // Long-link /_connect doesn't return a sgid
                            // synchronously — the group row is minted
                            // later when userJoinedGroup fires. Stash
                            // the link + incognito-state in a single-
                            // slot side channel so handleGroupJoined
                            // can attach them to the freshly-created
                            // GroupEntity. Without this the GroupSettings
                            // IdentityCard always reads as legacy
                            // ("joined before identity switch shipped")
                            // and can't be toggled — bug user reported
                            // 2026.05.611.
                            pendingGroupJoinCtx = PendingGroupJoinCtx(
                                joinLink = simplexUri,
                                realIdentity = !incog,
                            )
                            val r = apiConnect(planned.link)
                            ConnectionLog.log(TAG, "acceptInvitation: GroupLink apiConnect outcome=${r::class.simpleName}")
                            if (r !is ConnectResult.Sent) pendingGroupJoinCtx = null
                            r
                        }
                    }
                }
                is ResolvedPlan.Unsupported -> {
                    Log.w(
                        TAG,
                        "acceptInvitation: unsupported plan ${p.planType}/${p.subPlanType}",
                    )
                    ConnectResult.Error("Unsupported link type: ${p.planType}/${p.subPlanType ?: "?"}")
                }
            }

            when (outcome) {
                ConnectResult.Sent -> {
                    // Always defer binding to the contactConnected
                    // event. The /_connect response can come back
                    // with the contact's INITIAL announced
                    // localDisplayName before SimpleX has appended
                    // the unique _2 suffix for collision resolution;
                    // binding eagerly with the un-suffixed name
                    // caused collisions that clobbered existing
                    // contacts via KnownPeerEntity's upsert.
                    // contactConnected always carries the final
                    // SimpleX-resolved name.
                    pendingInviteLabel = label
                    // For group joins, the Sent ack means /_connect
                    // was queued — the actual join only completes
                    // when the host's bot fires a lifecycle event.
                    // Arm a timer so an invite-only / offline host
                    // surfaces as a notification instead of silent
                    // emptiness in the Groups tab.
                    if (planned.plan is ResolvedPlan.GroupLink) {
                        armGroupJoinTimer()
                    }
                    true
                }
                is ConnectResult.AlreadyExists -> {
                    // The core has the pairing — make SURE Aegis's
                    // known_peers has it too, otherwise the next
                    // rehydrateContacts() would treat this contact
                    // as a ghost and /_delete it (RELEASE 463
                    // ghost-purge). Calling bindContact populates
                    // both peerByKey and the known_peers row.
                    ConnectionLog.log(TAG, "acceptInvitation: already paired with ${outcome.displayName}; binding locally")
                    bindContact(outcome.displayName, outcome.contactId, label)
                    true
                }
                is ConnectResult.Error -> {
                    Log.w(TAG, "acceptInvitation: connect failed: ${outcome.reason}")
                    lastJoinError = outcome.reason
                    false
                }
            }
        }

    /** Identifier scheme: every SimpleX-paired peer is "simplex:<localDisplayName>". */
    private fun aegisIdFor(simplexContact: String) = "simplex:$simplexContact"

    private suspend fun bindContact(simplexContact: String, contactId: Long?, label: String) = bindLock.withLock {
        var id = aegisIdFor(simplexContact)
        val existing = peerByKey[id]
        // Collision detection: SimpleX is supposed to suffix
        // localDisplayName when two contacts share announced profile
        // names ("Aegis User", "Aegis User_2", ...), but the suffix
        // doesn't always come back on the /_connect response — and
        // even when it does, an out-of-band rename or a contact
        // pairing concurrent with another can race here. If we'd be
        // about to overwrite a known_peers row that already holds a
        // DIFFERENT SimpleX contactId, we generate our own unique
        // suffix so the existing contact's chat history, nickname,
        // verification state etc. stay intact instead of being
        // clobbered by the new pairing.
        if (
            existing != null && contactId != null &&
            existing.contactId != null && existing.contactId != contactId
        ) {
            var n = 2
            while (peerByKey.containsKey("${id}_$n")) n++
            val unique = "${id}_$n"
            Log.w(
                TAG,
                "localDisplayName collision: '$simplexContact' already holds contactId=" +
                    "${existing.contactId}, new one is contactId=$contactId — " +
                    "binding new contact as $unique to preserve the existing row.",
            )
            id = unique
        }
        val previous = peerByKey[id]
        val finalContactId = contactId ?: previous?.contactId
        peerByKey[id] = SXContact(simplexContact, finalContactId)
        // Re-pair: user explicitly armed RepairIntent before sending
        // themselves through the Accept-invite flow. The new pairing's
        // peerKey (id) attaches to the existing contact row at
        // RepairIntent.target — migrating chat history, name, avatar,
        // trust tier — instead of creating a duplicate "Zippy 2" entry.
        // Consume the intent atomically so a stale target can't bleed
        // into the next non-repair pairing.
        val repairTarget = app.aether.aegis.contact.RepairIntent.consume()
        if (repairTarget != null && repairTarget != id) {
            val moved = runCatching {
                AegisApp.instance.repository.repairKnownPeer(repairTarget, id)
            }.getOrDefault(0)
            // Drop the in-memory binding for the old peerKey too so the
            // outbound routing layer doesn't try to send through the
            // dead SimpleX queue any more.
            peerByKey.remove(repairTarget)
            Log.i(
                TAG,
                "RE-PAIR: $repairTarget → $id, $moved messages migrated",
            )
        } else {
            // Normal pairing path: persist so the chat list, status
            // grid, map, widget all see the new contact.
            AegisApp.instance.repository.addKnownPeer(id, label)
        }
        Log.i(TAG, "bound SimpleX contact: $simplexContact (contactId=$finalContactId) → \"$label\" as $id")
    }

    @Volatile private var pendingInviteLabel: String? = null

    /** Single in-flight "we just /_connected to a group, waiting for
     *  the host to accept" timer. The Sent response from /_connect
     *  is just an ack — the actual join completes when the host's
     *  bot fires userAcceptedGroupSent / groupLinkConnecting /
     *  userJoinedGroup. If none of those arrive within the timeout
     *  window, the host is most likely offline or — far more often —
     *  the link is for an invite-only group and the host silently
     *  drops uninvited /_connect requests. We surface a notification
     *  so the user isn't left wondering why the Groups tab is empty
     *  after a "join sent" toast. */
    @Volatile private var pendingGroupJoinJob: Job? = null
    private val GROUP_JOIN_TIMEOUT_MS = 30_000L

    /** Side channel for the long-link group-join path: /_connect on a
     *  `/g/…` URI doesn't return a sgid synchronously, so we can't
     *  call stashGroupJoinContext at the call site. Set this slot
     *  right before /_connect; handleGroupJoined drains it and
     *  stamps the link onto the freshly-created GroupEntity.
     *  Single-slot is fine — group joins are human-paced and serial. */
    private data class PendingGroupJoinCtx(val joinLink: String, val realIdentity: Boolean)
    @Volatile private var pendingGroupJoinCtx: PendingGroupJoinCtx? = null

    /**
     * Create a SimpleX group; returns the core's numeric groupId, or null
     * if the create failed. The caller is responsible for persisting the
     * Aegis-side GroupEntity and adding members via [addMemberToGroup].
     */
    suspend fun createSimplexGroup(name: String): Long? = withContext(Dispatchers.IO) {
        if (!app.aether.aegis.groups.GroupModulePrefs.currentEnabled()) {
            ConnectionLog.warn(TAG, "createSimplexGroup blocked — group module disabled")
            return@withContext null
        }
        if (!isHealthy) return@withContext null
        val uid = resolveUserId() ?: run {
            ConnectionLog.warn(TAG, "createSimplexGroup: no active user")
            return@withContext null
        }
        // PRIVACY: groups are ALWAYS incognito.
        // The join paths have always been incognito, but creation used the
        // legacy `/group <name>` shortcut — which binds the OWNER's group
        // membership to the main 1:1 profile, leaking the creator's real
        // displayName + bio (fullName) to every member. The avatar happened
        // not to leak only because the main profile carries no image in the
        // core. Switch to upstream ApiNewGroup (SimpleXAPI.kt:3899):
        //   /_group $userId incognito=on <groupProfile-json>
        // The `incognito=on` flag makes the creator join their OWN group
        // under a random per-group profile, the same guarantee every joiner
        // already gets. NOTE: this fixes groups created from here on — a
        // group already created non-incognito keeps the real owner profile
        // until it's recreated (the membership identity is fixed at create
        // time and can't be retroactively anonymised).
        //
        // The GroupProfile JSON shape ({displayName, fullName}) matches the
        // struct the working `/_group_profile` path already sends, so the
        // core's GroupProfile parser accepts it. displayName here is the
        // GROUP's name, not a member name — the incognito flag, not this
        // field, controls the owner's member identity.
        val displayName = name.trim().take(64).ifBlank { "group" }
        val profile = JSONObject().apply {
            put("displayName", displayName)
            put("fullName", "")
        }
        val resp = send("/_group $uid incognito=on $profile")
        runCatching {
            val obj = JSONObject(resp)
            val r = obj.optJSONObject("result")
                ?: obj.optJSONObject("resp")
                ?: obj.optJSONObject("chatResponse")
                ?: return@runCatching null
            r.optJSONObject("groupInfo")?.optLong("groupId")
                ?: r.optJSONObject("groupInfo")?.optJSONObject("groupProfile")?.optLong("groupId")
                ?: r.optLong("groupId").takeIf { it > 0 }
        }.getOrNull()
    }

    /**
     * Invite an existing SimpleX contact (resolved via our pubkey→localDisplayName
     * map) into a SimpleX group. Caller passes the SimpleX group's local name
     * (the same `safeName` used at creation time).
     */
    /**
     * Invite an existing SimpleX contact into a SimpleX group. Per
     * upstream (ApiAddMember, SimpleXAPI.kt:3903):
     *   /_add #<groupId> <contactId> <memberRole>
     * Roles: owner / admin / member / observer. We use "admin" so
     * paired family members get equal rights by default.
     */
    suspend fun addMemberToGroup(groupId: Long, peerPubkey: String): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        if (!app.aether.aegis.groups.GroupModulePrefs.currentEnabled()) {
            ConnectionLog.warn(TAG, "addMemberToGroup blocked — group module disabled")
            return@withContext false
        }
        val contact = peerByKey[peerPubkey] ?: return@withContext false
        val contactId = contact.contactId ?: return@withContext false
        val resp = send("/_add #$groupId $contactId admin")
        !resp.contains("\"type\":\"chatCmdError\"")
    }

    /**
     * A group's shareable join link, as returned by the core. [url] is the
     * thing you hand out — the `https://<server>/g#…` short link when the
     * core produced one, else the long `simplex:/contact#/…&grp=…` full
     * link. [shortLinkDataSet] is the load-bearing flag: a `/g#` short
     * link only RESOLVES for a joiner once its data has been published to
     * the link server. The core uploads it as part of creation, but until
     * the flag flips true a joiner who tries gets `invalidConnReq` — which
     * is exactly the failure we were chasing with externally-made links.
     */
    data class GroupShareLink(val url: String, val shortLinkDataSet: Boolean)

    /**
     * Create a shareable join link for [groupId] (upstream APICreateGroupLink,
     * SimpleXAPI.kt:3913): `/_create link #<groupId> <role>`. [role] is the
     * role a joiner gets — "member" by default (a shared link shouldn't
     * hand out admin to anyone who has it). Returns the link, or null on
     * error (logged). This is the host-side half that brings Aegis to
     * parity with the official client: previously Aegis could only invite
     * already-paired contacts directly and had no way to MINT a link.
     */
    suspend fun apiCreateGroupLink(groupId: Long, role: String = "member"): GroupShareLink? =
        withContext(Dispatchers.IO) {
            if (!isHealthy) return@withContext null
            if (!app.aether.aegis.groups.GroupModulePrefs.currentEnabled()) {
                ConnectionLog.warn(TAG, "apiCreateGroupLink blocked — group module disabled")
                return@withContext null
            }
            val resp = send("/_create link #$groupId $role")
            parseGroupLinkResponse(resp, "create")
        }

    /**
     * Fetch the existing join link for [groupId] (upstream APIGetGroupLink):
     * `/_get link #<groupId>`. Returns null if no link exists yet (the core
     * replies with an error we treat as "none") so the UI can offer to
     * create one. Lets the group screen show the same link on every open
     * instead of minting a new one each time.
     */
    suspend fun apiGetGroupLink(groupId: Long): GroupShareLink? =
        withContext(Dispatchers.IO) {
            if (!isHealthy) return@withContext null
            val resp = send("/_get link #$groupId")
            parseGroupLinkResponse(resp, "get")
        }

    /**
     * Revoke the join link for [groupId] (upstream APIDeleteGroupLink):
     * `/_delete link #<groupId>`. After this the shared URL stops working
     * for new joiners. Returns true on success.
     */
    suspend fun apiDeleteGroupLink(groupId: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (!isHealthy) return@withContext false
            val resp = send("/_delete link #$groupId")
            val ok = !resp.contains("\"type\":\"chatCmdError\"") &&
                !resp.contains("\"type\":\"chatError\"")
            ConnectionLog.log(TAG, "deleteGroupLink #$groupId ok=$ok")
            ok
        }

    /** Shared parser for the groupLinkCreated / groupLink responses. Pulls
     *  the shareable URL (short link preferred, full link fallback) and the
     *  shortLinkDataSet flag out of `groupLink.connLinkContact`. */
    private fun parseGroupLinkResponse(resp: String, op: String): GroupShareLink? {
        val root = runCatching { JSONObject(resp) }.getOrNull() ?: run {
            ConnectionLog.warn(TAG, "groupLink($op): non-JSON ${resp.take(160)}")
            return null
        }
        val obj = root.optJSONObject("result")
            ?: root.optJSONObject("resp")
            ?: root.optJSONObject("chatResponse")
            ?: return null
        val type = obj.optString("type")
        if (type == "chatCmdError" || type == "chatError") {
            // For "get" a missing link is an expected error, not a failure —
            // log quietly so the UI just offers "create".
            ConnectionLog.log(TAG, "groupLink($op): ${chatErrorMessage(obj) ?: "no link"}")
            return null
        }
        val gl = obj.optJSONObject("groupLink") ?: run {
            ConnectionLog.warn(TAG, "groupLink($op): response missing groupLink: ${resp.take(160)}")
            return null
        }
        val conn = gl.optJSONObject("connLinkContact") ?: return null
        val short = conn.optString("connShortLink").takeIf { it.isNotBlank() }
        val full = conn.optString("connFullLink").takeIf { it.isNotBlank() }
        val url = short ?: full ?: return null
        val dataSet = gl.optBoolean("shortLinkDataSet", false)
        ConnectionLog.log(
            TAG,
            "groupLink($op) → ${if (short != null) "short" else "full"} dataSet=$dataSet url=${url.take(56)}",
        )
        return GroupShareLink(url, dataSet)
    }

    /**
     * Accept a pending group invitation (upstream ApiJoinGroup,
     * SimpleXAPI.kt:3904): `/_join #<groupId>`. Fired from
     * [handleGroupJoined] when a `receivedGroupInvitation` arrives.
     *
     * Without this the core leaves us at group member status "invited"
     * forever: the group row exists locally but we never connect to the
     * host or other members (roster stays empty), and outbound
     * `/_send #group` messages get persisted in our own DB yet never
     * propagate — we are not an active member yet (user report: "group
     * has zero members, messages land but SimpleX sees nothing; in
     * SimpleX, Cyan is shown as invited").
     *
     * INCOGNITO CAVEAT: `apiJoinGroup` takes no incognito flag — for an
     * invitation-based join the membership profile is inherited from the
     * inviting CONTACT's connection. A contact you paired with
     * non-incognito (e.g. a Trusted family member) therefore introduces
     * you into their group under your real contact profile, NOT a random
     * incognito one. That diverges from the link-join paths (which pass
     * incognito=on) and from the incognito-only group rule. Forcing incognito
     * for invited groups needs a pre-join member-settings step and is
     * tracked as a separate follow-up.
     */
    private suspend fun apiJoinGroup(groupId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        val resp = send("/_join #$groupId")
        val ok = !resp.contains("\"type\":\"chatCmdError\"") &&
            !resp.contains("\"type\":\"chatError\"")
        ConnectionLog.log(
            TAG,
            "apiJoinGroup #$groupId ok=$ok${if (!ok) " resp=${resp.take(180)}" else ""}",
        )
        ok
    }

    /**
     * Send a text message to a SimpleX group. Per upstream
     * (SimpleXAPI.kt ApiSendMessages with ChatType.Group): the chatRef
     * is `#<groupId>`. Same JSON ComposedMessage shape as direct sends.
     */
    suspend fun sendToGroup(groupId: Long, body: String): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        // Group module gate — under group module isolation,
        // outbound group sends are blocked when the user
        // has the module disabled. UI shouldn't be able to reach
        // this path while disabled (the Groups tab hides the send
        // composer), but defence in depth: refuse at the transport
        // boundary regardless of caller.
        if (!app.aether.aegis.groups.GroupModulePrefs.currentEnabled()) {
            ConnectionLog.warn(TAG, "sendToGroup blocked — group module disabled")
            return@withContext false
        }
        // Per-group toggle. Even with the module on, a group
        // toggled off must not send. Cheap: single PK lookup.
        val aegisGroup = runCatching {
            AegisApp.instance.repository.getGroupBySimplexId(groupId)
        }.getOrNull()
        if (aegisGroup != null && !aegisGroup.enabled) {
            ConnectionLog.log(TAG, "sendToGroup blocked — group #$groupId disabled")
            return@withContext false
        }
        // Knocking: while our membership is awaiting admin approval the core
        // rejects posts to the main group. Address the member-support scope
        // instead — `#<sgid>(_support)` — which is exactly where the join UI
        // dropped the user (the "admin chat"). chatRef(_support) is upstream's
        // own syntax for the joiner→admins knock conversation.
        val chatRef = if (aegisGroup?.membershipPending == true) {
            ConnectionLog.log(TAG, "sendToGroup #$groupId → member-support scope (membership pending approval)")
            "#$groupId(_support)"
        } else {
            "#$groupId"
        }
        val msgContent = JSONObject().put("type", "text").put("text", body)
        val composed = JSONObject()
            .put("fileSource", JSONObject.NULL)
            .put("quotedItemId", JSONObject.NULL)
            .put("msgContent", msgContent)
            .put("mentions", JSONObject())
        val cmd = "/_send $chatRef live=off ttl=default json [$composed]"
        val resp = send(cmd)
        val ok = !resp.contains("\"type\":\"chatCmdError\"") &&
                 !resp.contains("\"type\":\"errorStore\"")
        if (!ok) {
            lastSendError = "group send failed: ${resp.take(220)}"
            Log.w(TAG, "group send failed for #$groupId: ${resp.take(400)}")
        }
        ok
    }

    /**
     * Low-level group send for CONTROL bodies that must not be recorded
     * as chat messages (currently: reactions). Unlike [sendToGroup] this
     * skips the module/per-group enable gates a user message honours —
     * a reaction is metadata on an existing message, not new content —
     * and can attach a native [quotedItemId] so the recipient recovers
     * the cross-device link to the reacted message. The body is an
     * `[aegis:…]` marker the receiver classifies as control and never
     * renders. Addressed to the main group scope only (reactions while
     * knocking aren't supported).
     */
    private suspend fun sendGroupRaw(
        groupId: Long,
        body: String,
        quotedItemId: Long? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        val msgContent = JSONObject().put("type", "text").put("text", body)
        val composed = JSONObject()
            .put("fileSource", JSONObject.NULL)
            .put("quotedItemId", if (quotedItemId != null) quotedItemId else JSONObject.NULL)
            .put("msgContent", msgContent)
            .put("mentions", JSONObject())
        val cmd = "/_send #$groupId live=off ttl=default json [$composed]"
        val resp = send(cmd)
        val ok = !resp.contains("\"type\":\"chatCmdError\"") &&
                 !resp.contains("\"type\":\"errorStore\"")
        if (!ok) Log.w(TAG, "sendGroupRaw #$groupId rejected: ${resp.take(220)}")
        ok
    }

    /**
     * Send a file to a SimpleX group. Mirrors sendFileToContact's
     * structure but addressed by `#<groupId>`.
     */
    suspend fun sendFileToGroup(
        groupId: Long,
        filePath: String,
        isImage: Boolean = false,
        caption: String = "",
        /** See sendFileToContact for the rationale. */
        localMessageId: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        val fileObj = java.io.File(filePath)
        if (!fileObj.exists() || !fileObj.canRead()) {
            lastSendError = "group file: not readable at $filePath"
            return@withContext false
        }
        // Groups are anonymous (Part 0 — always incognito); no member
        // is ever a "trusted contact", so group media is ALWAYS
        // scrubbed, unconditionally. Fail closed if it can't be proven
        // clean. (Currently no caller wires group media — this keeps
        // the policy true by construction if one ever does.)
        // In-place scrub (see sendFileToContact / MediaScrubber.scrubInPlace
        // for the single-source-of-truth rationale). Fail closed on block.
        if (!app.aether.aegis.util.MediaScrubber.scrubInPlace(filePath, isImage)) {
            lastSendError = "group media couldn't be metadata-scrubbed — not sent"
            ConnectionLog.warn(TAG, "group media scrub failed → blocked send to #$groupId")
            return@withContext false
        }
        val sendPath = filePath
        val fileSource = JSONObject().put("filePath", sendPath)
        val msgContent = if (isImage) {
            val preview = runCatching {
                val bmp = android.graphics.BitmapFactory.decodeFile(sendPath)
                val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 64, 64, true)
                val baos = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, baos)
                "data:image/jpeg;base64," + android.util.Base64.encodeToString(
                    baos.toByteArray(), android.util.Base64.NO_WRAP
                )
            }.getOrDefault("")
            JSONObject().put("type", "image").put("text", caption).put("image", preview)
        } else {
            JSONObject().put("type", "file").put("text", caption.ifBlank { fileObj.name })
        }
        val composed = JSONObject()
            .put("fileSource", fileSource)
            .put("quotedItemId", JSONObject.NULL)
            .put("msgContent", msgContent)
            .put("mentions", JSONObject())
        // Same knocking constraint as text sends: a pending member posts to
        // the member-support scope, not the main group.
        val pendingApproval = runCatching {
            AegisApp.instance.repository.getGroupBySimplexId(groupId)?.membershipPending
        }.getOrNull() == true
        val fileChatRef = if (pendingApproval) "#$groupId(_support)" else "#$groupId"
        val cmd = "/_send $fileChatRef live=off ttl=default json [$composed]"
        val resp = send(cmd)
        val ok = !resp.contains("\"type\":\"chatCmdError\"") &&
                 !resp.contains("\"type\":\"errorStore\"")
        ConnectionLog.log(
            TAG,
            "send file → $fileChatRef (${fileObj.length()} bytes, isImage=$isImage) ok=$ok" +
                (if (!ok) " resp=${resp.take(220)}" else ""),
        )
        if (!ok) {
            lastSendError = "group file send failed: ${resp.take(220)}"
            Log.w(TAG, "group file send failed for #$groupId: ${resp.take(400)}")
        } else if (localMessageId != null) {
            parseFirstItemId(resp)?.let { itemId ->
                runCatching {
                    AegisApp.instance.repository.setSimplexItemId(localMessageId, itemId)
                }.onFailure {
                    Log.w(TAG, "linking sent group-file row to itemId failed", it)
                }
            }
        }
        ok
    }

    /**
     * Send a file (photo/video/any) to a SimpleX contact resolved from the
     * given Aegis pubkey. Uses /_send with fileSource (upstream format),
     * NOT the /file CLI shorthand.
     *
     * For images: msgContent type="image" with base64 preview thumbnail.
     * For other files: msgContent type="file" with filename.
     *
     * The file must be on disk at a path the SimpleX core can read.
     */
    suspend fun sendFileToContact(
        peerPubkey: String,
        filePath: String,
        isImage: Boolean = false,
        caption: String = "",
        /** Source MIME, used to scope the mandatory metadata scrub: media
         *  (image/audio/video) is scrubbed, a known non-media document is
         *  passed through (the A/V scrubber can't process it and would
         *  fail-closed, which silently blocked all document sends). Blank
         *  = unknown → stays conservative (scrubbed). */
        mime: String = "",
        /** Local MessageEntity.id to link to the SimpleX itemId we
         *  parse out of the /_send response. Without this the row
         *  starts at status="sent" and stays there — chatItemsStatusesUpdated
         *  events fire keyed by the SimpleX itemId, and the matching
         *  query has no way to find the row. */
        localMessageId: String? = null,
        /** Forensic/duress channel marker. The sos system
         *  (snapshots, PTT) sends evidence to enrolled responders with
         *  GPS/timestamp DELIBERATELY intact — that's how they find
         *  you — so it bypasses the outbound metadata scrub. This is a
         *  fixed, code-level carve-out, NOT a user-facing toggle.
         *  Everything else defaults to false and is scrubbed unless the
         *  recipient is TRUSTED. */
        forensic: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        val contact = peerByKey[peerPubkey] ?: run {
            lastSendError = "file: no SimpleX contact bound for $peerPubkey"
            return@withContext false
        }
        val contactId = contact.contactId ?: run {
            lastSendError = "file: missing contactId for ${contact.name}"
            return@withContext false
        }

        // Sanity-check the file exists where we say it does.
        val fileObj = java.io.File(filePath)
        if (!fileObj.exists() || !fileObj.canRead()) {
            lastSendError = "file: not readable at $filePath (exists=${fileObj.exists()})"
            return@withContext false
        }

        // ---- Mandatory outbound metadata scrub --------------------
        // Policy: media sent to anyone but a TRUSTED contact MUST be
        // metadata-scrubbed — no exceptions, no
        // toggle. The only carve-out is the [forensic] duress
        // channel. EMERGENCY counts as NON-trusted here ("emergency
        // scrubs too") — only the exact TRUSTED tier is exempt. An
        // unknown peer is treated as non-trusted (scrub). If the file
        // can't be proven clean we FAIL CLOSED rather than leak.
        // Scrub IN PLACE (MediaScrubber.scrubInPlace) so the one file the
        // DB row, the at-rest seal, orphan-cleanup, and the uploader all
        // reference stays the single source of truth. A sibling scrubbed
        // file would be untracked → deleted mid-upload (stalled transfer)
        // and would leave the original unscrubbed.
        val sendPath = filePath
        if (!forensic) {
            val tier = runCatching {
                AegisApp.instance.repository.knownPeerByKey(peerPubkey)?.trustTier
            }.getOrNull()
            if (tier != app.aether.aegis.data.TrustTier.TRUSTED.name) {
                // The scrub targets MEDIA (image/audio/video) — that's where
                // GPS/EXIF/device metadata rides. A generic document (PDF,
                // doc, zip, …) carries none of that, AND the A/V remux can't
                // process it, so scrubInPlace fail-closed was silently
                // BLOCKING every document send to a non-trusted contact
                // (user-reported "files don't send"). Scope the scrub to
                // media; let a KNOWN non-media document through. Blank/unknown
                // mime stays conservative (still scrubbed, fail-closed).
                val isMediaMime = isImage ||
                    mime.startsWith("image/") ||
                    mime.startsWith("video/") ||
                    mime.startsWith("audio/")
                val skipScrub = mime.isNotBlank() && !isMediaMime
                if (!skipScrub) {
                    if (!app.aether.aegis.util.MediaScrubber.scrubInPlace(filePath, isImage)) {
                        lastSendError = "Can't send this to a non-trusted contact — " +
                            "its metadata couldn't be stripped. Send it to a Trusted " +
                            "contact, or send a photo instead."
                        ConnectionLog.warn(
                            TAG,
                            "media scrub failed → blocked send to $peerPubkey (tier=$tier, isImage=$isImage, mime=$mime)",
                        )
                        return@withContext false
                    }
                    ConnectionLog.log(TAG, "media scrubbed in place for non-trusted $peerPubkey (tier=$tier)")
                } else {
                    ConnectionLog.log(TAG, "document (mime=$mime) → no media scrub for non-trusted $peerPubkey")
                }
            }
        }

        // Build fileSource — upstream CryptoFile format (unencrypted)
        val fileSource = JSONObject().put("filePath", sendPath)

        // Build msgContent. The inline preview is decoded from the
        // scrubbed file ([sendPath]) so it matches what actually ships.
        val msgContent = if (isImage) {
            val preview = runCatching {
                val bmp = android.graphics.BitmapFactory.decodeFile(sendPath)
                val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 64, 64, true)
                val baos = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, baos)
                "data:image/jpeg;base64," + android.util.Base64.encodeToString(
                    baos.toByteArray(), android.util.Base64.NO_WRAP
                )
            }.getOrDefault("")
            JSONObject().put("type", "image").put("text", caption).put("image", preview)
        } else {
            // Caption only — do NOT fall back to the filename. The name now
            // rides the wire as the file path's basename (see Attachments.import),
            // so the receiver gets it from file.fileName; stuffing it into
            // `text` too made it render a second time as a chat line under the
            // file bubble (the "filename shows twice" report).
            JSONObject().put("type", "file").put("text", caption)
        }

        val composed = JSONObject()
            .put("fileSource", fileSource)
            .put("quotedItemId", JSONObject.NULL)
            .put("msgContent", msgContent)
            .put("mentions", JSONObject())
        val cmd = "/_send @$contactId live=off ttl=default json [$composed]"
        val resp = send(cmd)
        val ok = !resp.contains("\"type\":\"chatCmdError\"") &&
                 !resp.contains("\"type\":\"errorStore\"")
        ConnectionLog.log(
            TAG,
            "send file → @$contactId (${fileObj.length()} bytes, isImage=$isImage) ok=$ok" +
                (if (!ok) " resp=${resp.take(220)}" else ""),
        )
        if (ok) {
            lastSendError = null
            Log.i(TAG, "file send ok: $filePath (${fileObj.length()} bytes)")
            // Link the local row to the SimpleX item id so subsequent
            // chatItemsStatusesUpdated events can flip its status
            // through sent → delivered → read and the photo bubble
            // shows the same ✓ / ✓✓ / ✓✓ green progression as text.
            if (localMessageId != null) {
                parseFirstItemId(resp)?.let { itemId ->
                    runCatching {
                        AegisApp.instance.repository.setSimplexItemId(localMessageId, itemId)
                    }.onFailure {
                        Log.w(TAG, "linking sent file row to itemId failed", it)
                    }
                }
            }
        } else {
            lastSendError = "file send failed: ${resp.take(300)}"
            Log.w(TAG, "file send failed for $filePath: ${resp.take(400)}")
        }
        ok
    }

    // setProfile() DELETED (Aegis Protocol — code-deletion
    // requirement). The method that pushed the
    // user's real displayName/bio/avatar to the SimpleX core profile
    // (`/_profile`) — which SimpleX then broadcast to every contact — no
    // longer exists in this binary. The main SimpleX profile is vestigial
    // (a random handle from ensureUserProfile); real identity travels ONLY
    // through the E2E [aegis:identity] overlay (see sendIdentityEnvelope),
    // released to a contact when you elevate them to Trusted/Emergency.
    // You can't leak a real name through a transmit path that doesn't exist.

    /** Downscale + JPEG-compress an avatar to a square thumb suitable
     *  for inline broadcast inside a SimpleX profile struct. Returns
     *  a `data:image/jpeg;base64,…` URL, or null on failure.
     *
     *  [targetSide]/[quality] default to the 1:1 profile values
     *  (192² / JPEG 80); group avatars pass 256² / JPEG 70 —
     *  tighter quality because the image broadcasts to every
     *  member.
     *
     *  SECURITY: the decode-to-Bitmap + fresh JPEG compress is also
     *  the EXIF sanitiser. BitmapFactory decodes pixels only (no
     *  metadata) and `compress` writes a brand-new JPEG carrying NO
     *  EXIF, so GPS coordinates, device model, and capture timestamp
     *  in a source camera JPEG are stripped here. This is a HARD
     *  requirement for group avatars — the re-encode IS the
     *  defense, not just a size win.
     *  Do not add an EXIF-preserving fast path. */
    /**
     * Aegis Protocol — send the user's
     * REAL identity (name + bio + avatar) to [peerKey] over the
     * `[aegis:identity]` overlay. Called when the user elevates that
     * contact to Trusted/Emergency (Stage 3 trigger) and on profile edits
     * while still elevated. Routed through ProtocolManager.sendMessage so
     * the `[aegis:*]` capability gate (gateAegisControl) silently drops it
     * for a non-Aegis peer — real identity can only ever reach a confirmed
     * Aegis client, never a vanilla SimpleX one. Avatar is the same
     * 192²/JPEG-80 thumbnail the legacy profile push used; omitted when the
     * user has no photo avatar.
     */
    suspend fun sendIdentityEnvelope(peerKey: String) {
        val profile = AegisApp.instance.profileStore.snapshot()
        val json = org.json.JSONObject().apply {
            put("name", profile.displayName)
            if (profile.bio.isNotBlank()) put("bio", profile.bio)
            profile.avatarPath
                ?.let { encodeAvatarDataUrl(java.io.File(it)) }
                ?.let { put("img", it) }
        }
        AegisApp.instance.protocolManager.sendMessage(
            peerKey, "[aegis:identity]$json", MessageType.STATUS,
        )
    }

    private fun encodeAvatarDataUrl(
        file: java.io.File,
        targetSide: Int = 192,
        quality: Int = 80,
    ): String? = runCatching {
        val raw = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            ?: return null
        val side = minOf(raw.width, raw.height)
        val x = (raw.width - side) / 2
        val y = (raw.height - side) / 2
        val cropped = android.graphics.Bitmap.createBitmap(raw, x, y, side, side)
        val scaled = android.graphics.Bitmap.createScaledBitmap(cropped, targetSide, targetSide, true)
        val bos = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bos)
        val base64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        if (raw != cropped) runCatching { raw.recycle() }
        if (cropped != scaled) runCatching { cropped.recycle() }
        runCatching { scaled.recycle() }
        "data:image/jpeg;base64,$base64"
    }.getOrNull()

    // ---- Call signalling (foundation; full WebRTC orchestration TBD) ----
    // SimpleX delivers the SDP/ICE blobs through these commands; the actual
    // RTCPeerConnection lives in a WebView (see app.aether.aegis.call.WebRTCView).

    suspend fun callInvite(peerPubkey: String, callTypeJson: String): Boolean = simpleCallCmd(
        peerPubkey, "/_call invite", callTypeJson,
    )
    suspend fun callReject(peerPubkey: String): Boolean = simpleCallCmd(peerPubkey, "/_call reject")
    suspend fun callOffer(peerPubkey: String, offerJson: String): Boolean = simpleCallCmd(
        peerPubkey, "/_call offer", offerJson,
    )
    suspend fun callAnswer(peerPubkey: String, answerJson: String): Boolean = simpleCallCmd(
        peerPubkey, "/_call answer", answerJson,
    )
    suspend fun callExtra(peerPubkey: String, iceJson: String): Boolean = simpleCallCmd(
        peerPubkey, "/_call extra", iceJson,
    )
    suspend fun callEnd(peerPubkey: String): Boolean = simpleCallCmd(peerPubkey, "/_call end")

    /** /_call status — tells the SimpleX core that the WebRTC engine
     *  transitioned to a new connection state. Upstream fires this on
     *  every WCallResponse.Connection event; without it the core's
     *  internal call state can drift and some receivers stop getting
     *  follow-up events (callAnswer / callExtraInfo). Valid values:
     *  connected, connecting, disconnected, failed. */
    suspend fun callStatus(peerPubkey: String, status: String): Boolean =
        simpleCallCmd(peerPubkey, "/_call status", status)

    // ---- Per-chat disappearing messages ----

    /**
     * Set the chat-wide TTL for messages sent in this conversation.
     * Per upstream APISetChatTTL (SimpleXAPI.kt:3933):
     *   /_ttl <userId> @<contactId> <seconds-or-"default">
     * Passing null clears (off).
     */
    suspend fun setChatTtl(peerPubkey: String, seconds: Long?): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        val uid = resolveUserId() ?: return@withContext false
        // Direct vs group routing — groups use `#<sgid>`, contacts
        // `@<contactId>`.
        val chatRef: String = if (peerPubkey.startsWith("group:")) {
            val aegisGroupId = peerPubkey.removePrefix("group:")
            val group = runCatching {
                AegisApp.instance.repository.getGroup(aegisGroupId)
            }.getOrNull()
            val sgid = group?.simplexGroupId ?: return@withContext false
            "#$sgid"
        } else {
            val contact = peerByKey[peerPubkey] ?: return@withContext false
            val contactId = contact.contactId ?: return@withContext false
            "@$contactId"
        }
        val ttlStr = seconds?.toString() ?: "default"
        val resp = send("/_ttl $uid $chatRef $ttlStr")
        !resp.contains("\"type\":\"chatCmdError\"")
    }

    /**
     * Remove a member from a group via upstream
     * `/_remove #<groupId> <memberId> messages=on`
     * (SimpleXAPI.kt:3909). Caller passes the SimpleX-side memberId
     * (Long) — the receiver-side handler updates the local DB once
     * the core fires the corresponding event. Best-effort.
     */
    suspend fun removeGroupMember(
        aegisGroupId: String,
        memberId: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        val sgid = runCatching {
            AegisApp.instance.repository.getGroup(aegisGroupId)?.simplexGroupId
        }.getOrNull() ?: return@withContext false
        val resp = send("/_remove #$sgid $memberId messages=on")
        val ok = !resp.contains("\"type\":\"chatCmdError\"")
        ConnectionLog.log(TAG, "removeGroupMember #$sgid $memberId ok=$ok")
        ok
    }

    /**
     * Change a member's role via upstream
     * `/_member_role #<groupId> <memberId> <role>`
     * (SimpleXAPI.kt ApiMemberRole). [role] is the lowercase
     * SimpleX wire form — "owner" / "admin" / "member" / "observer".
     * The receiver-side handler updates the local DB once SimpleX
     * fires the corresponding event. Used by the Part-2 long-press
     * bottom sheet's promote / demote actions in GroupMembersScreen.
     */
    suspend fun setMemberRole(
        aegisGroupId: String,
        memberId: Long,
        role: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        val sgid = runCatching {
            AegisApp.instance.repository.getGroup(aegisGroupId)?.simplexGroupId
        }.getOrNull() ?: return@withContext false
        val resp = send("/_member_role #$sgid $memberId $role")
        val ok = !resp.contains("\"type\":\"chatCmdError\"")
        ConnectionLog.log(TAG, "setMemberRole #$sgid $memberId $role ok=$ok")
        ok
    }

    /**
     * Set the group's shared profile — name, optional description,
     * optional avatar — via
     * upstream `/_group_profile #<groupId> <json>` (SimpleXAPI.kt:3912).
     * This is the *group's* profile, visible to every member; it is
     * NOT a per-member identity (member identity is kept
     * incognito separately) and reveals no individual.
     *
     * `/_group_profile` writes the whole GroupProfile struct, so the
     * caller passes the desired FINAL state of every field (reading
     * current values for the ones it isn't changing) — otherwise an
     * omitted field could be cleared. [avatarPath] is a LOCAL file
     * path; it is EXIF-stripped + downscaled to 256² / JPEG 70 in
     * [encodeAvatarDataUrl] before it goes on the wire. A null
     * avatar/description omits that field. On success the local row
     * is mirrored so the UI updates without waiting for the echo.
     *
     * OWNER/ADMIN-gated at the UI; the core also enforces group role.
     */
    suspend fun setGroupProfile(
        aegisGroupId: String,
        name: String,
        description: String? = null,
        avatarPath: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        if (name.isBlank()) return@withContext false
        val repo = AegisApp.instance.repository
        val row = runCatching { repo.getGroup(aegisGroupId) }.getOrNull()
            ?: return@withContext false
        val sgid = row.simplexGroupId ?: return@withContext false
        val cleanName = name.trim()
        val cleanDesc = description?.trim()?.take(280)?.takeIf { it.isNotEmpty() }
        val imageDataUrl = avatarPath
            ?.let { java.io.File(it) }
            ?.takeIf { it.exists() && it.length() > 0L }
            ?.let { encodeAvatarDataUrl(it, targetSide = 256, quality = 70) }
        val profile = JSONObject().apply {
            put("displayName", cleanName)
            put("fullName", cleanName)
            if (cleanDesc != null) put("description", cleanDesc)
            if (imageDataUrl != null) put("image", imageDataUrl)
        }
        val resp = send("/_group_profile #$sgid $profile")
        val ok = !resp.contains("\"type\":\"chatCmdError\"")
        ConnectionLog.log(
            TAG,
            "setGroupProfile #$sgid name=$cleanName desc=${cleanDesc != null} avatar=${imageDataUrl != null} ok=$ok",
        )
        if (ok) {
            runCatching {
                repo.upsertGroup(
                    row.copy(
                        name = cleanName,
                        description = cleanDesc,
                        avatarPath = avatarPath,
                    ),
                )
            }
        }
        ok
    }

    /**
     * Set (or replace) the group's shared avatar from a picked source
     * image. Sanitises + downscales the
     * source into the group's LOCAL avatar file first
     * ([writeSanitizedJpeg] — decode→256² square→JPEG 70, which drops
     * any source EXIF), then broadcasts via [setGroupProfile] keeping
     * the current name + description. OWNER/ADMIN only (UI-gated).
     * Returns false if the source can't be decoded or the group isn't
     * resolvable. [sourceImagePath] may be a throwaway import; the
     * caller can delete it afterwards — we keep our own sanitised copy.
     */
    suspend fun setGroupAvatar(
        aegisGroupId: String,
        sourceImagePath: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val repo = AegisApp.instance.repository
        val row = runCatching { repo.getGroup(aegisGroupId) }.getOrNull()
            ?: return@withContext false
        val dest = AegisApp.instance.profileRoot.groupAvatarFile(aegisGroupId)
        if (!writeSanitizedJpeg(java.io.File(sourceImagePath), dest, side = 256, quality = 70)) {
            return@withContext false
        }
        setGroupProfile(
            aegisGroupId = aegisGroupId,
            name = row.name,
            description = row.description,
            avatarPath = dest.absolutePath,
        )
    }

    /** Decode [src], centre-crop to a [side]² square, and write a
     *  fresh JPEG at [quality] to [dest]. The decode→re-encode strips
     *  EXIF (GPS/device/timestamp) the same way [encodeAvatarDataUrl]
     *  does, so even the local copy carries none. Returns false if the
     *  source can't be decoded. */
    private fun writeSanitizedJpeg(
        src: java.io.File,
        dest: java.io.File,
        side: Int,
        quality: Int,
    ): Boolean = runCatching {
        val raw = android.graphics.BitmapFactory.decodeFile(src.absolutePath)
            ?: return false
        val sq = minOf(raw.width, raw.height)
        val x = (raw.width - sq) / 2
        val y = (raw.height - sq) / 2
        val cropped = android.graphics.Bitmap.createBitmap(raw, x, y, sq, sq)
        val scaled = android.graphics.Bitmap.createScaledBitmap(cropped, side, side, true)
        dest.parentFile?.mkdirs()
        java.io.FileOutputStream(dest).use { out ->
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        }
        if (raw != cropped) runCatching { raw.recycle() }
        if (cropped != scaled) runCatching { cropped.recycle() }
        runCatching { scaled.recycle() }
        true
    }.getOrElse {
        Log.w(TAG, "writeSanitizedJpeg failed", it)
        false
    }

    /**
     * Rename a group. Thin wrapper over [setGroupProfile] that
     * preserves the current description + avatar so a rename never
     * wipes them (the /_group_profile write is whole-struct).
     */
    suspend fun renameGroup(
        aegisGroupId: String,
        newName: String,
    ): Boolean {
        val row = runCatching {
            AegisApp.instance.repository.getGroup(aegisGroupId)
        }.getOrNull() ?: return false
        return setGroupProfile(
            aegisGroupId = aegisGroupId,
            name = newName,
            description = row.description,
            avatarPath = row.avatarPath,
        )
    }

    // ---- Safety-code verification (WhatsApp-style) ----

    /**
     * Fetch the SimpleX security verification code for a contact. Per
     * upstream APIGetContactCode (SimpleXAPI.kt:3952):
     *   /_get code @<contactId>
     * Returns the connectionCode string; both parties see the same
     * value if their session keys haven't been tampered with.
     */
    suspend fun getContactCode(peerPubkey: String): String? = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext null
        val contact = peerByKey[peerPubkey] ?: return@withContext null
        val contactId = contact.contactId ?: return@withContext null
        val resp = send("/_get code @$contactId")
        runCatching {
            val root = JSONObject(resp)
            val obj = root.optJSONObject("result")
                ?: root.optJSONObject("resp")
                ?: root.optJSONObject("chatResponse")
                ?: return@runCatching null
            obj.optString("connectionCode").ifBlank { null }
        }.getOrNull()
    }

    /**
     * Mark the contact's safety code as verified. Per upstream
     * APIVerifyContact (SimpleXAPI.kt:3954):
     *   /_verify code @<contactId> [<code>]
     * Passing the user-entered code makes the core compare; passing
     * null just marks verified without comparison.
     */
    suspend fun verifyContact(peerPubkey: String, code: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        val contact = peerByKey[peerPubkey] ?: return@withContext false
        val contactId = contact.contactId ?: return@withContext false
        val cmd = if (code.isNullOrBlank()) "/_verify code @$contactId"
        else "/_verify code @$contactId $code"
        val resp = send(cmd)
        !resp.contains("\"type\":\"chatCmdError\"")
    }

    private suspend fun simpleCallCmd(
        peerPubkey: String,
        verb: String,
        payload: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        val contact = peerByKey[peerPubkey] ?: run {
            Log.w(TAG, "simpleCallCmd $verb: no SimpleX contact bound for $peerPubkey")
            return@withContext false
        }
        // Address by numeric contactId where we have it — same rationale
        // as the text-send path: display names with spaces break the
        // shorthand @<name> parser.
        val ref = contact.contactId?.let { "@$it" } ?: "@${contact.name}"
        val cmd = if (payload == null) "$verb $ref" else "$verb $ref $payload"
        val resp = send(cmd)
        val ok = !resp.contains("\"type\":\"chatCmdError\"")
        if (!ok) Log.w(TAG, "$verb failed for $peerPubkey: ${resp.take(400)}")
        ok
    }

    private fun send(cmd: String): String = SimpleXCore.sendCommand(cmd)

    /**
     * The random incognito display name THIS device presents to the
     * contact [aegisKey]. Aegis pairs every contact with incognito=on, so
     * the SimpleX core mints a fresh random profile per connection — you
     * appear under a DIFFERENT name to each contact, unlinkable across
     * them. That unlinkability is the privacy win, but it also means the
     * user has no idea what name a given friend actually sees. This asks
     * the core for that per-contact alias — the
     * `customUserProfile.displayName` from `/_info @<contactId>` — so the
     * UI can finally show it.
     *
     * Returns null when the transport is down, the contact isn't bound in
     * [peerByKey] yet, or the core reports no custom profile for them (a
     * legacy non-incognito contact has a null customUserProfile because
     * they see the main profile name instead).
     */
    suspend fun myIncognitoNameFor(aegisKey: String): String? =
        withContext(Dispatchers.IO) {
            if (!isHealthy) return@withContext null
            val contactId = peerByKey[aegisKey]?.contactId ?: return@withContext null
            val root = runCatching { JSONObject(send("/_info @$contactId")) }
                .getOrNull() ?: return@withContext null
            val obj = root.optJSONObject("result")
                ?: root.optJSONObject("resp")
                ?: root.optJSONObject("chatResponse")
                ?: return@withContext null
            obj.optJSONObject("customUserProfile")
                ?.optString("displayName")
                ?.takeIf { it.isNotBlank() }
        }


    /**
     * List the SMP relay servers currently configured for the active
     * user. Returns the raw `smp://…` URIs as the core reports them;
     * an empty list means "using defaults" (the SimpleX team's
     * canonical relays). Best-effort parse — the JSON response shape
     * varies slightly across core versions; on any structural surprise
     * we return null so the caller can degrade.
     */
    suspend fun listSmpServers(): List<String>? = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext null
        val uid = resolveUserId() ?: return@withContext null
        val resp = send("/_smp $uid")
        runCatching {
            val root = JSONObject(resp)
            val obj = root.optJSONObject("result")
                ?: root.optJSONObject("resp")
                ?: root.optJSONObject("chatResponse")
                ?: return@runCatching null
            val arr = obj.optJSONArray("smpServers")
                ?: obj.optJSONObject("smpServers")?.optJSONArray("protoServers")
                ?: obj.optJSONArray("protoServers")
                ?: return@runCatching emptyList<String>()
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.opt(i)
                when (item) {
                    is String -> item
                    is JSONObject -> {
                        item.optJSONObject("server")?.optString("server")?.takeIf { it.isNotBlank() }
                            ?: item.optString("server").takeIf { it.isNotBlank() }
                            ?: item.optString("preset").takeIf { it.isNotBlank() }
                    }
                    else -> null
                }
            }
        }.getOrNull()
    }

    /**
     * Replace the SMP relay set. Pass an empty list to reset to the
     * SimpleX-team defaults (mapped to `/smp default`). Each URI must
     * be `smp://<fingerprint>@<host>[:port]`. Returns false if the core
     * rejects (bad URI, no active user, etc).
     */
    suspend fun setSmpServers(servers: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        val cmd = if (servers.isEmpty()) "/smp default"
                  else "/smp " + servers.joinToString(" ")
        val resp = send(cmd)
        !resp.contains("\"type\":\"chatCmdError\"")
    }

    /**
     * Ask the SimpleX core for the current contact list and populate
     * peerByKey. Called on start() so paired contacts survive an aegisApp
     * restart — without this, send() to a previously-paired peer fails
     * with "no SimpleX contact bound" until that peer happens to send
     * us a message and re-binds via handleNewChatItems.
     */
    /**
     * Resolve the SimpleX userId — needed for /_connect and /_contacts.
     * Caches after first successful resolution.
     */
    private fun resolveUserId(): Long? {
        activeUserId?.let { return it }
        val u = send("/u")
        runCatching {
            val root = JSONObject(u)
            val obj = root.optJSONObject("result")
                ?: root.optJSONObject("resp")
                ?: root.optJSONObject("chatResponse")
            val uid = obj?.optJSONObject("user")?.optLong("userId", -1L)?.takeIf { it > 0 }
            activeUserId = uid
        }
        return activeUserId
    }

    /** SimpleX userId — needed for `/_contacts <userId>`. Cached after ensureUserProfile. */
    @Volatile private var activeUserId: Long? = null

    private suspend fun rehydrateContacts() = withContext(Dispatchers.IO) {
        runCatching {
            val uid = resolveUserId() ?: run {
                Log.w(TAG, "rehydrateContacts: no activeUserId yet, skipping")
                return@withContext
            }
            val resp = send("/_contacts $uid")
            val root = JSONObject(resp)
            val obj = root.optJSONObject("result")
                ?: root.optJSONObject("resp")
                ?: root.optJSONObject("chatResponse")
                ?: return@withContext
            val contacts = obj.optJSONArray("contacts") ?: return@withContext

            // Cross-reference with Aegis's known_peers. Anything in the
            // core but missing from known_peers is a ghost — the user
            // deleted the contact while the transport wasn't listening
            // (e.g. core paused via the notification action), so the
            // observePeerMutations /_delete never fired and the core
            // still has the row. Reap them now or pairedContacts will
            // keep reporting the inflated count and Restart Simplex
            // can't fix it (rehydrate would just re-add them).
            val knownKeys: Set<String> = runCatching {
                AegisApp.instance.repository.allKnownPeers()
                    .map { it.publicKey }.toSet()
            }.getOrDefault(emptySet())

            // Rebuild peerByKey from scratch so a previous start()'s
            // entries (which may now be ghosts) can't survive a
            // user-initiated transport restart.
            val rebuilt = mutableMapOf<String, SXContact>()
            var restored = 0
            var purged = 0
            for (i in 0 until contacts.length()) {
                val c = contacts.optJSONObject(i) ?: continue
                val name = c.optString("localDisplayName")
                if (name.isBlank()) continue
                val cid = c.optLong("contactId", -1L).takeIf { it > 0 }
                val key = aegisIdFor(name)
                if (key in knownKeys) {
                    rebuilt[key] = SXContact(name, cid)
                    restored++
                    // Refresh announced profile + avatar from the live
                    // SimpleX contact record. Without this we never
                    // re-pull the avatar after a wipe — handleContactUpdated
                    // only fires on profile CHANGES, and once the cached
                    // path was nulled (old DAO bug, fixed at COALESCE)
                    // the row stayed null forever. updateAnnouncedProfile
                    // now uses COALESCE so nulls preserve existing; only
                    // non-null values flow through.
                    val profile = c.optJSONObject("profile")
                    val displayName = profile?.optString("displayName")?.takeIf { it.isNotBlank() }
                    val fullName = profile?.optString("fullName")?.takeIf { it.isNotBlank() }
                    val imageB64 = profile?.optString("image")?.takeIf { it.isNotBlank() }
                    val avatarPath = imageB64?.let { decodePeerAvatar(key, it) }
                    if (displayName != null || fullName != null || avatarPath != null) {
                        runCatching {
                            AegisApp.instance.repository.updatePeerProfile(
                                publicKey = key,
                                announcedName = displayName,
                                announcedBio = fullName,
                                announcedAvatarPath = avatarPath,
                            )
                        }.onFailure {
                            Log.w(TAG, "rehydrate profile update failed for $key", it)
                        }
                    }
                } else if (cid != null) {
                    runCatching {
                        val delResp = send("/_delete @$cid notify=off")
                        if (delResp.contains("\"type\":\"chatCmdError\"")) {
                            Log.w(TAG, "ghost purge: /_delete @$cid responded: ${delResp.take(220)}")
                        } else {
                            Log.i(TAG, "ghost purge: removed core contact '$name' (@$cid)")
                            purged++
                        }
                    }.onFailure {
                        Log.w(TAG, "ghost purge /_delete @$cid threw", it)
                    }
                } else {
                    Log.w(TAG, "ghost contact '$name' has no contactId, can't /_delete; skipping")
                }
            }
            peerByKey.clear()
            peerByKey.putAll(rebuilt)
            Log.i(TAG, "rehydrated $restored SimpleX contact(s); purged $purged ghost(s)")
        }.onFailure { Log.w(TAG, "rehydrateContacts failed", it) }
    }

    /**
     * Make sure the SimpleX core has an active user profile, otherwise
     * every subsequent command fails with `noActiveUser`. The right
     * sequence per simplex-chat's apiGetActiveUser/apiCreateActiveUser:
     *
     *   1. /u  → ShowActiveUser. If the response is `activeUser`
     *      we're already set; if it's `chatCmdError` with errorType
     *      `noActiveUser`, no profile exists.
     *   2. /_create user <NewUser-JSON> — note JSON shape:
     *      { "profile": {"displayName":"...","fullName":"...","shortDescr":null},
     *        "pastTimestamp": false, "userChatRelay": false }
     *      My earlier "/_create user 1 {...}" had the wrong arity AND
     *      a flat profile body, so the call silently no-op'd and the
     *      noActiveUser error stuck.
     *
     * Uses ProfileStore.displayName so paired contacts see the user's
     * real name from day one; falls back to "Aegis User" if the
     * profile-onboarding step hasn't run yet (in which case ProfileScreen
     * will call setProfile later to update).
     */
    private fun ensureUserProfile() {
        val show = send("/u")
        // If the core already has an active user, we're done.
        if (show.contains("\"type\":\"activeUser\"")) return

        // AEGIS PROTOCOL — code-deletion requirement: the main
        // SimpleX profile is VESTIGIAL. Every
        // connection is incognito, so this profile is never shared with
        // any peer. We deliberately DO NOT read the user's real
        // displayName/bio into it — if a future upstream change ever
        // transmitted the main profile, it must leak a random handle, not
        // "Cyan". Real identity lives only in the [aegis:identity] overlay.
        val randomHandle = "u" + java.util.UUID.randomUUID().toString()
            .replace("-", "").take(10)
        val profileJson = org.json.JSONObject().apply {
            put("displayName", randomHandle)
            put("fullName", "")
            put("shortDescr", org.json.JSONObject.NULL)
        }
        val name = randomHandle
        val newUserJson = org.json.JSONObject().apply {
            put("profile", profileJson)
            put("pastTimestamp", false)
            put("userChatRelay", false)
        }
        val createResp = send("/_create user $newUserJson")
        if (createResp.contains("\"type\":\"chatCmdError\"")) {
            android.util.Log.w(TAG, "user create failed: ${createResp.take(200)}")
        } else {
            android.util.Log.i(TAG, "SimpleX user created: $name")
        }
    }

    // ------------------------------------------------------------------
    // Multi-user management (ephemeral profiles)
    //
    // Aegis profiles share ONE SimpleX core DB, and historically ONE
    // global SimpleX user. An ephemeral profile needs its data to be
    // removable as a unit without touching any other profile's, so it
    // gets its OWN SimpleX user via [createSimplexUser]; entering it
    // switches the core to that user ([setActiveSimplexUser]); wiping it
    // on lock removes exactly that user's contacts + history
    // ([deleteSimplexUser]). These are additive wrappers — the existing
    // single-user flow is unchanged until a profile opts into its own
    // user. (Investigation 2026-06-06: one shared user; no per-profile
    // user existed before this.)
    // ------------------------------------------------------------------

    /**
     * Create a fresh, isolated SimpleX user (its own contacts + message
     * store within the shared core DB) and return its userId, or null on
     * failure. The profile is a vestigial random handle, incognito-only,
     * exactly like the main user (Aegis Protocol — never shared).
     */
    suspend fun createSimplexUser(): Long? = withContext(Dispatchers.IO) {
        val randomHandle = "u" + java.util.UUID.randomUUID().toString()
            .replace("-", "").take(10)
        val profileJson = org.json.JSONObject().apply {
            put("displayName", randomHandle)
            put("fullName", "")
            put("shortDescr", org.json.JSONObject.NULL)
        }
        val newUserJson = org.json.JSONObject().apply {
            put("profile", profileJson)
            put("pastTimestamp", false)
            put("userChatRelay", false)
        }
        val resp = send("/_create user $newUserJson")
        if (resp.contains("\"type\":\"chatCmdError\"")) {
            android.util.Log.w(TAG, "createSimplexUser failed: ${resp.take(200)}")
            return@withContext null
        }
        // Pull the new user's id out of the response (shape mirrors
        // activeUser: { ... "user": { "userId": N, ... } }).
        runCatching {
            val root = org.json.JSONObject(resp)
            val obj = root.optJSONObject("result")
                ?: root.optJSONObject("resp")
                ?: root.optJSONObject("chatResponse") ?: root
            obj.optJSONObject("user")?.optLong("userId", -1L)?.takeIf { it > 0 }
        }.getOrNull()
    }

    /**
     * Switch the SimpleX core's active user (apiSetActiveUser →
     * `/_user <id>`). Used when entering/leaving an ephemeral profile so
     * its messaging runs under its own isolated user. Updates the cached
     * [activeUserId] on success.
     */
    suspend fun setActiveSimplexUser(userId: Long): Boolean = withContext(Dispatchers.IO) {
        val resp = send("/_user $userId")
        val ok = !resp.contains("\"type\":\"chatCmdError\"")
        if (ok) activeUserId = userId
        else android.util.Log.w(TAG, "setActiveSimplexUser $userId failed: ${resp.take(180)}")
        ok
    }

    /**
     * Delete a SimpleX user and ALL its data (contacts, messages, SMP
     * queues) from the core DB (apiDeleteUser →
     * `/_delete user <id> del_smp=on`). This is how an ephemeral profile
     * leaves no SimpleX-layer trace on lock. Best-effort; returns whether
     * the core accepted it. The caller MUST ensure [userId] is the
     * EPHEMERAL user, never the primary — deleting the wrong user
     * destroys that profile's history.
     */
    suspend fun deleteSimplexUser(userId: Long): Boolean = withContext(Dispatchers.IO) {
        val resp = send("/_delete user $userId del_smp=on")
        val ok = !resp.contains("\"type\":\"chatCmdError\"") &&
            !resp.contains("\"type\":\"errorStore\"")
        if (ok) {
            if (activeUserId == userId) activeUserId = null
            android.util.Log.i(TAG, "deleteSimplexUser $userId OK")
        } else {
            android.util.Log.w(TAG, "deleteSimplexUser $userId failed: ${resp.take(200)}")
        }
        ok
    }

    private fun startReceiver() {
        // Capture THIS pump's own Job so its finally can tell whether it is
        // still the live pump before touching shared health state (see the
        // identity guard in the finally below).
        lateinit var thisJob: Job
        thisJob = scope.launch {
            // Defensive try/finally: an uncaught throwable from
            // SimpleXCore.recvWait (e.g. JNI surface returning an
            // unexpected error, native lib crash, socket reset) used
            // to silently kill the pump while `isHealthy` stayed
            // true — the UI then claimed "online" forever while
            // receiving nothing. We now log + flip the flag so the
            // health watchdog in ProtocolManager picks it up and
            // restarts the transport.
            //
            // No per-cycle wake lock here — upstream's SimplexService
            // explicitly comments out the wake lock it used to hold
            // ("Permanent wakelock prevents deep sleep and results in
            // high battery usage. Instead, the aegisApp relies on being
            // whitelisted for unrestricted battery usage, and also
            // takes wakelock on network events and on network
            // information changes, which allows it to reconnect.").
            // Aegis matches that posture: battery-optimization
            // exemption (Diagnostics), NetworkCallback +
            // apiReconnect on network change (build 614),
            // MY_PACKAGE_REPLACED FGS restart (621). 624 briefly
            // shipped a per-message wake lock based on an
            // audit reading of upstream; on closer inspection of
            // upstream that was a misread.
            try {
                while (isActive) {
                    val msg = try {
                        SimpleXCore.recvWait(POLL_TIMEOUT_MS)
                    } catch (t: Throwable) {
                        Log.w(TAG, "recvWait threw — pump exiting", t)
                        ConnectionLog.warn(
                            TAG,
                            "recvWait threw: ${t::class.simpleName}: ${t.message}",
                        )
                        break  // exit loop; finally flips isHealthy
                    } ?: continue
                    handleEvent(msg)
                }
            } finally {
                // Identity guard — ONLY the current pump may flip health.
                //
                // recvWait is a BLOCKING JNI call; pumpJob?.cancel() in stop()
                // can't interrupt it, so a cancelled pump stays parked in
                // recvWait until its POLL_TIMEOUT_MS elapses. Meanwhile stop()
                // is serialised with the next start() (startLock), so a fresh
                // start() may already have set isHealthy=true and launched a
                // NEW pump by the time this old, cancelled pump finally
                // unwinds. Without this guard the old pump's finally saw
                // isHealthy=true and flipped it back to false — knocking the
                // just-started transport "down" the instant it came up. The
                // watchdog + onResume nudge then restarted it, the new pump's
                // predecessor clobbered THAT one too, and the result was a
                // stop/start storm that pinned the CPU until Android killed
                // the process for excessive use (user-reported, debug build).
                //
                // stop() sets pumpJob=null and start() sets pumpJob=<new job>,
                // so a superseded pump is never == pumpJob: its exit is by
                // definition expected (stop already set isHealthy=false) and
                // must be silent. Only a pump that is STILL the live one may
                // report an unexpected exit (a genuine recvWait throw).
                if (pumpJob === thisJob && isHealthy) {
                    ConnectionLog.warn(TAG, "pump exited unexpectedly")
                    isHealthy = false
                }
            }
        }
        pumpJob = thisJob
    }

    private suspend fun handleEvent(json: String) {
        // Stamp arrival time so networkSnapshot() can distinguish
        // "pump alive" from "pump alive AND receiving traffic." UI
        // uses the delta against System.currentTimeMillis() to
        // downgrade the SimpleX status indicator to a "stale" state
        // when no events have arrived for several minutes despite the
        // transport reading healthy — the symptom an audit
        // chased and the user flagged as "the status is lying."
        lastEventAtMs = System.currentTimeMillis()
        // SimpleX emits events under one of three envelopes depending on
        // version: {"result":{...}} (current), {"resp":{...}} (v5/early
        // v6), or {"chatResponse":{...}} (some intermediate builds). Two
        // event types we care about:
        //   newChatItems     — a peer sent us a message
        //   contactConnected — a peer just finished pairing with us
        // Anything else, we ignore.
        runCatching {
            val outer = JSONObject(json)
            val resp = outer.optJSONObject("result")
                ?: outer.optJSONObject("resp")
                ?: outer.optJSONObject("chatResponse")
                ?: return
            val type = resp.optString("type")
            when (type) {
                "contactConnected" -> {
                    ConnectionLog.log(
                        TAG,
                        "← contactConnected: ${resp.optJSONObject("contact")?.optString("localDisplayName") ?: "?"}",
                    )
                    handleContactConnected(resp)
                }
                "newChatItems"     -> handleNewChatItems(resp)
                // Group-join lifecycle (upstream CR types
                // SimpleXAPI.kt:6439 / 6440 / 6460). All three arrive
                // at the joiner's side after `/_connect <group-link>`:
                //   userAcceptedGroupSent — server queued the join
                //   groupLinkConnecting   — host's link is establishing
                //   userJoinedGroup       — host accepted us into the group
                // Any of them signals we have a valid groupInfo to
                // persist as a local GroupEntity. handleGroupJoined is
                // idempotent, so processing all three is safe; the
                // first to arrive populates the row, the later ones
                // no-op. Earlier we only listened for the first and
                // last, so a join that stalled in the "connecting"
                // state never surfaced a group in the Groups tab.
                "userAcceptedGroupSent",
                "groupLinkConnecting",
                "userJoinedGroup" -> {
                    ConnectionLog.log(TAG, "← group event: $type")
                    handleGroupJoined(resp)
                }
                // Some builds emit `receivedGroupInvitation` instead
                // when the host is offline — same payload shape
                // (groupInfo), same persistence behaviour.
                "receivedGroupInvitation" -> handleGroupJoined(resp)
                // An admin changed the group's shared profile (name /
                // description / avatar). Upstream CR.GroupUpdated carries
                // the new GroupInfo under `toGroup` (SimpleXAPI.kt:6463).
                // Part 1/2 receive: cache the description + decode the
                // avatar so it propagates to every member.
                "groupUpdated" -> handleGroupProfileUpdated(resp)
                // File messages: newChatItems delivers the caption /
                // metadata, but the file isn't on disk yet. The core
                // emits rcvFileComplete once it's downloaded from XFTP
                // with chatItem.file.fileSource.filePath populated.
                "rcvFileComplete"  -> handleRcvFileComplete(resp)
                "chatItemsStatusesUpdated" -> handleStatusesUpdated(resp)
                // Singular twin — upstream emits chatItemUpdated for
                // content edits AND, in some cases, status flips. We
                // pipe it into the same status-extraction path so
                // sndRcvd flowing through this envelope still flips
                // "sent" → "delivered" on the bubble. Previously the
                // singular event was dropped on the floor, which the
                // user reported as "I never see delivered, only
                // sent → read."
                "chatItemUpdated" -> handleChatItemUpdated(resp)
                // SimpleX-native reactions are deliberately unused — Aegis
                // reactions ride the signed [aegis:reaction] control path
                // (see sendSignedReaction) so any emote is allowed. A core
                // chatItemReaction event therefore has nothing to apply.
                // Profile updates from the peer — display name, bio,
                // or avatar changed. Refresh our announced* mirror
                // on the KnownPeer row so the radar / chat header /
                // contact detail show the new value without a re-pair.
                "contactUpdated" -> handleContactUpdated(resp)
                "newContactConnection", "contactConnecting" -> {
                    Log.i(TAG, "pairing in progress: $type")
                }
                "callInvitation", "callOffer", "callAnswer",
                "callExtraInfo", "callEnded" -> {
                    app.aether.aegis.call.CallManager.onSimpleXCallEvent(type, resp)
                }
                // File-transfer lifecycle (upstream CR types,
                // SimpleXAPI.kt:6474-6497). We used to silently drop
                // every one except rcvFileComplete, which meant a
                // failed XFTP download or canceled send left the user
                // with no signal beyond the stalled file row.
                //
                // Progress events get a trace-level log (high volume,
                // not actionable to the user); accept/start get info;
                // errors / warnings / cancellations get warn so they
                // land in logcat at default verbosity. Future passes
                // can promote these into per-file UI state once we
                // have a place to render it.
                "rcvFileStart", "rcvFileAccepted",
                "sndFileStart" -> {
                    Log.i(TAG, "file event: $type ${resp.toString().take(160)}")
                    trackInFlightFile(resp, type)
                }
                "rcvFileProgressXFTP", "sndFileProgressXFTP" -> {
                    Log.v(TAG, "file progress: $type")
                    updateInFlightProgress(resp)
                }
                "sndFileComplete", "sndFileCompleteXFTP" -> {
                    Log.i(TAG, "file send complete: $type")
                    clearInFlightFile(resp)
                    sealOutgoingAttachmentForEvent(resp)
                }
                "rcvFileSndCancelled", "sndFileRcvCancelled",
                "sndFileCancelled" -> {
                    Log.w(TAG, "file cancelled: $type ${resp.toString().take(160)}")
                    clearInFlightFile(resp)
                }
                "rcvFileError", "sndFileError" -> {
                    Log.w(TAG, "file error: $type ${resp.toString().take(220)}")
                    clearInFlightFile(resp)
                }
                "rcvFileWarning", "sndFileWarning" ->
                    Log.w(TAG, "file warning: $type ${resp.toString().take(220)}")
                // Group member lifecycle. `connectedToGroupMember` is
                // the canonical "we now have a full pairing with this
                // member, treat them as an active participant" signal —
                // fires both when WE join an existing group (one
                // event per existing member) and when a new member
                // joins a group we're already in. `joinedGroupMember`
                // / `joinedGroupMemberConnecting` are earlier states
                // of the same arc; we record on those too because
                // some hosts skip straight from connecting to message
                // delivery without firing the connectedToGroupMember
                // event we'd otherwise rely on.
                "connectedToGroupMember",
                "joinedGroupMember",
                "joinedGroupMemberConnecting" -> {
                    ConnectionLog.log(TAG, "← $type")
                    handleGroupMemberJoined(resp)
                }
                // Member removal — drop the row so the count + the
                // members list both reflect the truth.
                "leftMember", "deletedMember", "deletedMemberUser" -> {
                    ConnectionLog.log(TAG, "← $type")
                    handleGroupMemberLeft(resp, type)
                }
                // Bulk member list — fired in response to /_get group
                // and occasionally as state sync. Walk the list and
                // upsert every entry.
                "groupMembers" -> {
                    ConnectionLog.log(TAG, "← groupMembers (bulk)")
                    handleGroupMembersBulk(resp)
                }
                // SMP relay transport lifecycle. The core opens one TCP
                // session per relay host it needs (each contact's queues
                // live on specific relays). We track per-host up/down here
                // so the Network card can show real relay connectivity and
                // the status verdict can require ≥1 live relay before it
                // promises delivery — previously these were only logged,
                // so "healthy" could be claimed with every relay dropped.
                "hostConnected" -> {
                    val host = resp.optString("transportHost").ifBlank { "?" }
                    relayState[host] = RelayInfo(host, connected = true, sinceMs = System.currentTimeMillis())
                    ConnectionLog.log(TAG, "← hostConnected $host")
                }
                "hostDisconnected" -> {
                    val host = resp.optString("transportHost").ifBlank { "?" }
                    // Keep the row (don't remove) so the card can show a
                    // relay as "down" rather than silently vanishing — a
                    // disappearing relay reads as "fine" to the user.
                    relayState[host] = RelayInfo(host, connected = false, sinceMs = System.currentTimeMillis())
                    ConnectionLog.log(TAG, "← hostDisconnected $host")
                }
                // Everything else — log the type so the diag screen
                // shows what the core is emitting. Catches the
                // "after /_connect the host's group bot fired
                // something we don't recognise yet" case: previously
                // every unhandled type was silently dropped, which
                // gave the impression "no events arrived" when
                // actually plenty did but their name didn't match
                // our explicit list. Carries a short payload prefix
                // so we can spot which `groupInfo` block to plumb.
                else -> ConnectionLog.log(
                    TAG, "← $type ${resp.toString().take(180)}",
                )
            }
        }.onFailure { Log.w(TAG, "event parse failed: ${it.message}") }
    }

    private suspend fun handleContactConnected(resp: JSONObject) {
        val contact = resp.optJSONObject("contact") ?: return
        val contactName = contact.optString("localDisplayName").ifBlank { return }
        val contactId = contact.optLong("contactId", -1L).takeIf { it > 0 }
        val label = pendingInviteLabel ?: contactName
        pendingInviteLabel = null
        bindContact(contactName, contactId, label)
        // A pending invitation that just connected is no longer
        // pending — drop it from the managed list.
        // Best-effort by connection id.
        contact.optJSONObject("activeConn")
            ?.optLong("connId", -1L)
            ?.takeIf { it > 0 }
            ?.let { cid ->
                scope.launch {
                    runCatching {
                        AegisApp.instance.repository.removePendingInvitation(cid)
                    }
                }
            }
        // Capability bootstrap. The gate on both sides would
        // otherwise drop every aegis-tagged outbound until the OTHER
        // side has sent us one first — but their side does the same,
        // so the deadlock never breaks. Fire a single
        // `[aegis:hello]<our-display-name>` here, ungated, so the
        // peer's handleNewChatItems catch-all flips their isAegis row
        // for us. They run the same handler on their pairing event
        // and send their own hello back, which flips our flag for
        // them. Result: routine status / location / tier flow as
        // soon as the handshake completes, instead of silently
        // never.
        scope.launch { sendAegisHello(contactName) }
        // Pull the peer's announced profile (name / bio / avatar) RIGHT
        // NOW from the contactConnected payload instead of waiting for a
        // later `contactUpdated` (which only fires when they CHANGE
        // something) or the next transport-restart rehydrate. Without
        // this a freshly paired contact rendered a blank monogram and
        // no name until they happened to edit their profile — user
        // report 2026.06: "takes really long to update status and
        // avatar of a fresh contact". Same decode path as
        // handleContactUpdated; peerKey matches the non-collision
        // binding (aegisIdFor(contactName)), and updatePeerProfile is
        // COALESCE-based so a profile with no image leaves any existing
        // avatar intact rather than nulling it.
        runCatching {
            val peerKey = aegisIdFor(contactName)
            val profile = contact.optJSONObject("profile")
            val displayName = profile?.optString("displayName")?.takeIf { it.isNotBlank() }
            val fullName = profile?.optString("fullName")?.takeIf { it.isNotBlank() }
            val image = profile?.optString("image")?.takeIf { it.isNotBlank() }
            val avatarPath = image?.let { decodePeerAvatar(peerKey, it) }
            if (displayName != null || fullName != null || avatarPath != null) {
                AegisApp.instance.repository.updatePeerProfile(
                    publicKey = peerKey,
                    announcedName = displayName,
                    announcedBio = fullName,
                    announcedAvatarPath = avatarPath,
                )
            }
        }.onFailure { Log.w(TAG, "handleContactConnected profile extract failed", it) }
        // Nudge an immediate status broadcast so a status-eligible
        // (Trusted) fresh contact sees our presence within one status-
        // ticker cycle (≤60 s) instead of waiting out the 5-min
        // broadcast throttle. No-op for the common untrusted case (the
        // broadcaster fans out to Trusted only), harmless otherwise.
        app.aether.aegis.services.ProtocolService.requestImmediateStatusBroadcast()
    }

    /** Send the post-pairing capability-bootstrap envelope. Carved out of
     *  the capability gate by design (the `[aegis:hello]` exception in
     *  [aegisGated]) — sending it any other way would be subject to the
     *  same flag the message is meant to flip. Rides the x.aegis
     *  control channel like every other command (sendText routes it to
     *  sendControl), so a vanilla peer sees an empty message rather than
     *  literal marker text. */
    // AEGIS PROTOCOL: hello carries NO identity payload (real name travels
    // exclusively through [aegis:identity] on trust elevation). It carries
    // our Ed25519 control PUBLIC key (base64url) — the "channel bootstrapped"
    // marker. Shape: `[aegis:hello]<base64url-pub>`.
    private suspend fun sendAegisHello(simplexContact: String) {
        val pubB64 = runCatching {
            val pub = app.aether.aegis.cmdauth.ControlKeypair.publicKey(AegisApp.instance)
            android.util.Base64.encodeToString(
                pub,
                android.util.Base64.URL_SAFE or
                    android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            )
        }.getOrDefault("")
        val body = "[aegis:hello]$pubB64"
        val peerId = aegisIdFor(simplexContact)
        val ok = sendText(peerId, body)
        ConnectionLog.log(TAG, "aegis:hello → $simplexContact ok=$ok (key=${pubB64.isNotEmpty()})")
        // Announce our capabilities right behind the hello so a freshly-paired
        // peer learns immediately whether we speak the chat envelope (the
        // app-start broadcast re-announces to already-bootstrapped peers after
        // an upgrade). Separate envelope, not appended to the hello, so a
        // pre-capability build just ignores it (unknown [aegis:*] → STATUS).
        runCatching {
            sendText(peerId, "[aegis:caps]${app.aether.aegis.protocol.AegisCaps.SELF}")
        }.onFailure { ConnectionLog.warn(TAG, "aegis:caps → $simplexContact failed: $it") }
    }

    /**
     * Persist a GroupEntity for a group we just joined via an
     * invite link. Fires on `userAcceptedGroupSent` (server queued
     * the join) and `userJoinedGroup` (host accepted us) — either
     * is enough to know the groupId we should track locally. Both
     * carry the same `groupInfo` object shape upstream.
     *
     * Idempotent: if we already have a GroupEntity for the
     * incoming simplexGroupId, repository.createGroup's Room
     * upsert overwrites the row in place without disturbing the
     * Aegis-side id. Members list stays empty here — group-member
     * events populate it later as the host introduces us to
     * everyone.
     */
    /** Start (or restart) the pending-group-join timeout. Cancels
     *  any previous timer first so back-to-back group joins each
     *  get their own window. The notification only fires if the
     *  job survives the full [GROUP_JOIN_TIMEOUT_MS] without being
     *  cancelled by a group lifecycle event. */
    private fun armGroupJoinTimer() {
        pendingGroupJoinJob?.cancel()
        pendingGroupJoinJob = scope.launch {
            kotlinx.coroutines.delay(GROUP_JOIN_TIMEOUT_MS)
            notifyGroupJoinTimeout()
            pendingGroupJoinJob = null
        }
    }

    /** Any group lifecycle event arriving means the host accepted
     *  our /_connect — cancel the timer so the user doesn't see a
     *  false "no response" notification a few seconds later. */
    private fun cancelGroupJoinTimer() {
        pendingGroupJoinJob?.cancel()
        pendingGroupJoinJob = null
    }

    /** Surface the "join wasn't acknowledged" notification through
     *  the normal messages channel. Most likely cause is an
     *  invite-only group; second most likely is an offline host. */
    private fun notifyGroupJoinTimeout() {
        runCatching {
            val ctx = context
            val nm = androidx.core.app.NotificationManagerCompat.from(ctx)
            if (!nm.areNotificationsEnabled()) return@runCatching
            val notif = androidx.core.app.NotificationCompat.Builder(ctx, AegisApp.CHANNEL_MESSAGES)
                .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_shield)
                .setColor(AegisApp.BRAND_CYAN_ARGB)
                .setContentTitle("Group join didn't go through")
                .setContentText(
                    "No response from the group host after 30 s. The link " +
                        "may be invite-only, or the host is offline.",
                )
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle()
                        .bigText(
                            "No response from the group host after 30 s. The " +
                                "link may be invite-only, or the host is offline. " +
                                "If you weren't explicitly invited to this group, " +
                                "ask the owner for an invitation directly.",
                        ),
                )
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_GROUP_JOIN_TIMEOUT, notif)
        }
    }

    private suspend fun handleGroupJoined(resp: JSONObject) {
        cancelGroupJoinTimer()
        // Group module gate. When disabled, drop the event — no
        // local persistence, no UI surface. Phase 2 will issue
        // upstream /_unsubscribe to stop these events at the
        // protocol level entirely.
        if (!app.aether.aegis.groups.GroupModulePrefs.currentEnabled()) {
            ConnectionLog.log(TAG, "handleGroupJoined dropped — group module disabled")
            return
        }
        val evType = resp.optString("type")
        val groupInfo = resp.optJSONObject("groupInfo")
        if (groupInfo == null) {
            Log.w(TAG, "group event '$evType' missing groupInfo: ${resp.toString().take(220)}")
            return
        }
        val sgid = groupInfo.optLong("groupId", -1L).takeIf { it > 0 }
        if (sgid == null) {
            Log.w(TAG, "group event '$evType' missing groupId in groupInfo: ${groupInfo.toString().take(220)}")
            return
        }
        val groupName = groupInfo.optJSONObject("groupProfile")
            ?.optString("displayName")
            ?.takeIf { it.isNotBlank() }
            ?: groupInfo.optString("localDisplayName").takeIf { it.isNotBlank() }
            ?: "group"
        val repo = AegisApp.instance.repository
        // Drain the pending-join slot regardless of branch — if the row
        // already existed, the IdentityCard is already wired; if we're
        // about to mint one we need to stamp joinLink onto it.
        val pendingCtx = pendingGroupJoinCtx.also { pendingGroupJoinCtx = null }
        val existing = runCatching { repo.getGroupBySimplexId(sgid) }.getOrNull()
        val groupRow = if (existing != null) {
            Log.i(TAG, "group event '$evType' for already-tracked group #$sgid ($groupName)")
            // If we have a pending ctx and the existing row lacks
            // joinLink, backfill — covers the case where the short-link
            // path stashed before the row had a chance to settle.
            if (pendingCtx != null && existing.joinLink == null) {
                runCatching {
                    repo.upsertGroup(existing.copy(
                        joinLink = pendingCtx.joinLink,
                        realIdentity = pendingCtx.realIdentity,
                    ))
                }
            }
            existing
        } else {
            runCatching {
                repo.createGroup(name = groupName, memberKeys = emptyList(), simplexGroupId = sgid)
            }.onSuccess {
                Log.i(TAG, "persisted group #$sgid ($groupName) via '$evType' event")
                // Newly-minted group — attach the stashed join context
                // so the IdentityCard can switch identities later.
                if (pendingCtx != null) {
                    runCatching {
                        repo.upsertGroup(it.copy(
                            joinLink = pendingCtx.joinLink,
                            realIdentity = pendingCtx.realIdentity,
                        ))
                        ConnectionLog.log(
                            TAG,
                            "group ctx attached via handleGroupJoined sgid=$sgid real=${pendingCtx.realIdentity}",
                        )
                    }
                }
            }.onFailure {
                Log.w(TAG, "failed to persist joined group #$sgid via '$evType'", it)
            }.getOrNull() ?: return
        }
        // userJoinedGroup / groupLinkConnecting carry the host as
        // `hostMember`. Record them so the new group at least shows a
        // count of 1 (the owner) before the per-member connection
        // events arrive. Idempotent — repository.addGroupMember
        // upserts on (groupId, peerPubkey).
        resp.optJSONObject("hostMember")?.let { host ->
            recordMember(groupRow.id, host)
        }
        // Knocking: if the link requires admin approval, our own membership
        // comes back as `pending_approval` / `pending_review`. Persist that
        // so sendToGroup routes to the member-support scope instead of the
        // main group (which the core rejects while we're pending). This runs
        // on every routed group lifecycle event, so the moment the host
        // approves us — a later event carries an active memberStatus — the
        // flag clears and sends switch back to the main group.
        val memberStatus = groupInfo.optJSONObject("membership")
            ?.optString("memberStatus")
        val pending = memberStatus == "pending_approval" || memberStatus == "pending_review"
        runCatching {
            repo.setGroupMembershipPending(groupRow.id, pending)
        }.onFailure { Log.w(TAG, "setGroupMembershipPending($pending) failed", it) }
        if (pending) {
            ConnectionLog.log(TAG, "group #$sgid join is PENDING admin approval (status=$memberStatus) — sends go to member-support scope")
        }
        // Record OUR OWN membership as a member row keyed by
        // identity.deviceId (the key GroupMembersScreen resolves "self" by),
        // carrying the role SimpleX reports for us. The members / hostMember
        // lists are OTHER members — self only ever arrives as `membership` —
        // so without this the local user is never in group_members, selfRole
        // defaults to MEMBER, and group-management actions are unreachable
        // even for an OWNER. Idempotent: upserts on (groupId, deviceId).
        app.aether.aegis.groups.GroupRole.fromSimplex(
            groupInfo.optJSONObject("membership")?.optString("memberRole"),
        )?.let { selfRole ->
            val selfKey = AegisApp.instance.identity.deviceId
            runCatching {
                AegisApp.instance.repository.addGroupMember(groupRow.id, selfKey)
                AegisApp.instance.repository.setGroupMemberRole(groupRow.id, selfKey, selfRole)
            }.onFailure { Log.w(TAG, "record self group membership failed", it) }
        }
        // Capture the group's shared profile (avatar + description) at
        // join time so a group you joined already shows its image/blurb
        // without waiting for a later groupUpdated. Part 1/2 receive.
        applyInboundGroupProfile(groupRow.id, groupInfo.optJSONObject("groupProfile"))
        // A `receivedGroupInvitation` is an invitation we have NOT yet
        // accepted — the core holds us at member status "invited" until
        // we call /_join, so the roster stays empty and our sends never
        // propagate. Accept it now (the group module is enabled — we'd
        // have bailed above otherwise). The OTHER event types routed
        // here (userJoinedGroup / groupLinkConnecting /
        // userAcceptedGroupSent) are POST-join lifecycle for the link
        // path — we already initiated those via /_connect, so we must
        // NOT re-join them, or the core errors on a duplicate join.
        if (evType == "receivedGroupInvitation") {
            val joined = apiJoinGroup(sgid)
            ConnectionLog.log(
                TAG,
                "auto-accepted group invitation #$sgid ($groupName) joined=$joined",
            )
        }
    }

    /**
     * Handle an inbound group-profile change (CR.GroupUpdated). The
     * updated GroupInfo is under `toGroup`; we resolve our local row
     * by SimpleX groupId, keep the name in sync (an admin may have
     * renamed), and apply the avatar/description via
     * [applyInboundGroupProfile]. No-op if the group module is off or
     * we don't track this group. Part 1/2 receive.
     */
    private suspend fun handleGroupProfileUpdated(resp: JSONObject) {
        if (!app.aether.aegis.groups.GroupModulePrefs.currentEnabled()) return
        val info = resp.optJSONObject("toGroup") ?: return
        val sgid = info.optLong("groupId", -1L).takeIf { it > 0 } ?: return
        val repo = AegisApp.instance.repository
        val row = runCatching { repo.getGroupBySimplexId(sgid) }.getOrNull() ?: return
        val profile = info.optJSONObject("groupProfile")
        val newName = profile?.optString("displayName")?.takeIf { it.isNotBlank() }
        if (newName != null && newName != row.name) {
            runCatching { repo.upsertGroup(row.copy(name = newName)) }
        }
        applyInboundGroupProfile(row.id, profile)
    }

    /**
     * Apply an inbound group profile to the local row: cache the
     * [description] and, if the profile carries an `image` data: URL,
     * decode it to the group's local avatar file and store the path.
     * Part 1/2 receive side.
     *
     * UPDATE-WHEN-PRESENT semantics: a missing description/image
     * leaves the cached value untouched rather than clearing it. This
     * deliberately trades "remote clears don't propagate" for "an
     * early/partial join event can't wipe a freshly-set avatar." The
     * common case (an admin setting or changing the profile) works;
     * clearing is the rare edge. The image is another admin's
     * broadcast — we only decode + display it; the outbound
     * EXIF-strip already happened on the sender.
     */
    private suspend fun applyInboundGroupProfile(
        aegisGroupId: String,
        groupProfile: JSONObject?,
    ) {
        if (groupProfile == null) return
        val repo = AegisApp.instance.repository
        groupProfile.optString("description").takeIf { it.isNotBlank() }?.let { desc ->
            runCatching { repo.setGroupDescription(aegisGroupId, desc) }
        }
        val image = groupProfile.optString("image").takeIf { it.isNotBlank() }
        if (image != null && image.startsWith("data:")) {
            runCatching {
                val b64 = image.substringAfter(',', "")
                if (b64.isNotEmpty()) {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    val file = AegisApp.instance.profileRoot.groupAvatarFile(aegisGroupId)
                    file.writeBytes(bytes)
                    repo.setGroupAvatarPath(aegisGroupId, file.absolutePath)
                }
            }.onFailure { Log.w(TAG, "inbound group avatar decode failed for $aegisGroupId", it) }
        }
    }

    /** Pull a stable per-member identifier out of a GroupMember JSON
     *  block and persist it as a member of [aegisGroupId]. Identifier
     *  preference order: SimpleX groupMemberId (numeric, unique within
     *  group) → memberId (their pairwise public key) → display name as
     *  a last resort so the member at least surfaces in the count.
     *
     *  Also captures the member's role (OWNER / ADMIN / MEMBER) from
     *  the wire — SimpleX's `memberRole` field, part of group
     *  hardening. */
    private suspend fun recordMember(aegisGroupId: String, member: JSONObject) {
        val gmid = member.optLong("groupMemberId", -1L).takeIf { it > 0 }
        val memberId = member.optString("memberId").takeIf { it.isNotBlank() }
        val name = member.optJSONObject("memberProfile")?.optString("displayName")
            ?.takeIf { it.isNotBlank() }
            ?: member.optString("localDisplayName").takeIf { it.isNotBlank() }
        val key = memberId
            ?: gmid?.let { "gmid:$it" }
            ?: name?.let { "name:$it" }
            ?: return
        runCatching {
            AegisApp.instance.repository.addGroupMember(aegisGroupId, key)
        }.onFailure { Log.w(TAG, "addGroupMember($key) failed", it) }
        // Cache the member's self-announced profile name so the roster shows
        // a real name instead of "Member · <hex>". addGroupMember only stores
        // the key; the name we resolved above has to be written separately.
        if (name != null) {
            runCatching {
                AegisApp.instance.repository.cacheGroupMemberDisplayName(aegisGroupId, key, name)
            }.onFailure { Log.w(TAG, "cacheGroupMemberDisplayName($key) failed", it) }
        }
        // Capture role if SimpleX provided one. OBSERVER skipped —
        // fromSimplex returns null and we leave the stored role
        // alone rather than coercing.
        val role = app.aether.aegis.groups.GroupRole.fromSimplex(
            member.optString("memberRole"),
        )
        if (role != null) {
            runCatching {
                AegisApp.instance.repository.setGroupMemberRole(
                    groupId = aegisGroupId,
                    peerPubkey = key,
                    role = role,
                )
            }.onFailure { Log.w(TAG, "setGroupMemberRole($key, $role) failed", it) }
        }
        // Stash the numeric SimpleX-side memberId so the Part-2
        // bottom sheet's Remove / Promote / Demote can call the
        // upstream /_remove and /_member_role commands without a
        // /_get group round-trip. Skip when the field is absent
        // (some events don't carry it; the next event that does
        // will fill it in).
        if (gmid != null) {
            runCatching {
                AegisApp.instance.repository.setGroupMemberSimplexId(
                    groupId = aegisGroupId,
                    peerPubkey = key,
                    simplexMemberId = gmid,
                )
            }.onFailure { Log.w(TAG, "setGroupMemberSimplexId($key, $gmid) failed", it) }
        }
    }

    /** [connectedToGroupMember] / [joinedGroupMember] /
     *  [joinedGroupMemberConnecting] — record the freshly-joined
     *  member against our local GroupEntity. Also writes a JOIN
     *  audit row (GROUP_SYSTEM) so the chat history shows membership
     *  arrivals as a centre chip. Part of group hardening. */
    private suspend fun handleGroupMemberJoined(resp: JSONObject) {
        cancelGroupJoinTimer()
        if (!app.aether.aegis.groups.GroupModulePrefs.currentEnabled()) return
        val sgid = resp.optJSONObject("groupInfo")
            ?.optLong("groupId", -1L)?.takeIf { it > 0 } ?: return
        val aegisGroup = runCatching {
            AegisApp.instance.repository.getGroupBySimplexId(sgid)
        }.getOrNull() ?: return
        val member = resp.optJSONObject("member") ?: return
        recordMember(aegisGroup.id, member)
        // Capture displayName eagerly — this is the only event that
        // carries the freshly-joined member's profile name before
        // any group message has arrived. Without this the name
        // doesn't land until their first message comes through.
        val memberKey = memberPubkeyOf(member)
        val profileName = member.optJSONObject("memberProfile")
            ?.optString("displayName")?.takeIf { it.isNotBlank() }
            ?: member.optString("localDisplayName").takeIf { it.isNotBlank() }
        if (memberKey != null && profileName != null && profileName != "group-member") {
            runCatching {
                AegisApp.instance.repository.cacheGroupMemberDisplayName(
                    groupId = aegisGroup.id,
                    peerPubkey = memberKey,
                    displayName = profileName,
                )
            }
        }
        // JOIN audit row. Deterministic id via Repository
        // .recordGroupSystem makes the local-write + the echo
        // (if any) collapse onto the same Room row.
        if (memberKey != null) {
            runCatching {
                AegisApp.instance.repository.recordGroupSystem(
                    groupId = aegisGroup.id,
                    kind = app.aether.aegis.groups.GroupSystemPayload.Kind.JOIN,
                    actor = memberKey,
                )
            }.onFailure {
                Log.w(TAG, "recordGroupSystem(JOIN) failed for $memberKey", it)
            }
        }
        // joinedGroupMemberConnecting also carries hostMember (us as
        // the inviter); skip it — we don't want to add ourselves.
    }

    /** [leftMember] / [deletedMember] / [deletedMemberUser] —
     *  pull the member out of the local GroupMember table so the
     *  chat UI's member count drops. Also writes a LEAVE or KICK
     *  audit row to the group's chat history as part of group
     *  hardening. [evType] discriminates:
     *  `leftMember` = voluntary, anything `deleted*` = removed by
     *  someone (KICK). */
    private suspend fun handleGroupMemberLeft(resp: JSONObject, evType: String) {
        if (!app.aether.aegis.groups.GroupModulePrefs.currentEnabled()) return
        val sgid = resp.optJSONObject("groupInfo")
            ?.optLong("groupId", -1L)?.takeIf { it > 0 } ?: return
        val aegisGroup = runCatching {
            AegisApp.instance.repository.getGroupBySimplexId(sgid)
        }.getOrNull() ?: return
        // `deletedMember` uses `deletedMember` field, others use `member`.
        val mem = resp.optJSONObject("member")
            ?: resp.optJSONObject("deletedMember")
            ?: return
        val memberId = mem.optString("memberId").takeIf { it.isNotBlank() }
            ?: ("gmid:" + mem.optLong("groupMemberId", -1L))
        runCatching {
            AegisApp.instance.repository.removeGroupMember(aegisGroup.id, memberId)
        }.onFailure { Log.w(TAG, "removeGroupMember($memberId) failed", it) }
        // Audit row. LEAVE for voluntary, KICK for forced removal.
        // For KICK we don't always know who removed the member —
        // SimpleX's deletedMember event doesn't reliably name the
        // remover, so we set actor = the member who was removed
        // (the visible-to-history record) and leave subject null.
        // When KICK is initiated locally via Part-2 UI, the local-
        // write path will carry actor = self, subject = removed,
        // and the deterministic-id dedupe will merge against this
        // row.
        val kind = when (evType) {
            "leftMember" -> app.aether.aegis.groups.GroupSystemPayload.Kind.LEAVE
            else -> app.aether.aegis.groups.GroupSystemPayload.Kind.KICK
        }
        runCatching {
            AegisApp.instance.repository.recordGroupSystem(
                groupId = aegisGroup.id,
                kind = kind,
                actor = memberId,
            )
        }.onFailure {
            Log.w(TAG, "recordGroupSystem($kind) failed for $memberId", it)
        }
    }

    /** Pull the canonical pubkey out of a SimpleX member JSON
     *  object — same priority chain as [recordMember]. Returns
     *  null when none of the fallbacks yield anything usable. */
    private fun memberPubkeyOf(member: JSONObject): String? {
        val gmid = member.optLong("groupMemberId", -1L).takeIf { it > 0 }
        val memberId = member.optString("memberId").takeIf { it.isNotBlank() }
        val name = member.optJSONObject("memberProfile")?.optString("displayName")
            ?.takeIf { it.isNotBlank() }
            ?: member.optString("localDisplayName").takeIf { it.isNotBlank() }
        return memberId
            ?: gmid?.let { "gmid:$it" }
            ?: name?.let { "name:$it" }
    }

    /** [groupMembers] — bulk member list, walked and upserted. */
    /**
     * Ask the SimpleX core for the FULL member roster of [simplexGroupId]
     * (`/_members`, upstream `apiListMembers`) and upsert it locally.
     *
     * The core only volunteers member rows incidentally — a `joinedGroupMember`
     * event per member it happens to relay — so the local roster ends up
     * being "whoever joined while we were watching", which is why a 10-member
     * group showed only 3 (the duct-tape: a partial cache, never the
     * authoritative list). This requests the real roster the core already
     * holds. Call it when opening a group. Idempotent (recordMember upserts).
     */
    suspend fun refreshGroupMembers(simplexGroupId: Long) {
        runCatching {
            val resp = send("/_members #$simplexGroupId")
            val root = JSONObject(resp)
            val obj = root.optJSONObject("result")
                ?: root.optJSONObject("resp")
                ?: root.optJSONObject("chatResponse")
                ?: return@runCatching
            if (obj.optString("type") == "groupMembers") {
                handleGroupMembersBulk(obj)
                ConnectionLog.log(TAG, "refreshGroupMembers #$simplexGroupId ok")
            } else {
                ConnectionLog.warn(
                    TAG,
                    "refreshGroupMembers #$simplexGroupId: unexpected ${obj.optString("type")}",
                )
            }
        }.onFailure { Log.w(TAG, "refreshGroupMembers(#$simplexGroupId) failed", it) }
    }

    private suspend fun handleGroupMembersBulk(resp: JSONObject) {
        if (!app.aether.aegis.groups.GroupModulePrefs.currentEnabled()) return
        val group = resp.optJSONObject("group") ?: return
        val groupInfo = group.optJSONObject("groupInfo") ?: return
        val sgid = groupInfo.optLong("groupId", -1L).takeIf { it > 0 } ?: return
        val aegisGroup = runCatching {
            AegisApp.instance.repository.getGroupBySimplexId(sgid)
        }.getOrNull() ?: return
        val members = group.optJSONArray("members") ?: return
        for (i in 0 until members.length()) {
            val m = members.optJSONObject(i) ?: continue
            recordMember(aegisGroup.id, m)
        }
    }

    /**
     * Port of upstream
     * [`cancelFile`](SimpleXAPI.kt:2133) — sends `/fcancel <id>`,
     * which the core picks up to drop the XFTP transfer and emit a
     * `*Cancelled` event that clears [InFlightFiles]. Used by the
     * chat banner's Cancel button.
     */
    suspend fun cancelFile(fileId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        val resp = send("/fcancel $fileId")
        // Core acknowledges with `chatItemUpdated` carrying the new
        // file status. We don't need to parse it — the cancellation
        // events fired by the agent are what flip InFlightFiles.
        val ok = resp.contains("\"type\"")
        if (!ok) Log.w(TAG, "cancelFile($fileId) unexpected response: ${resp.take(160)}")
        // Defensive: if the core dropped the entry without firing a
        // cancel event (rare race seen on flaky relays), nuke the
        // local tracker so the banner doesn't linger.
        InFlightFiles.done(fileId)
        ok
    }

    /** Extract the peer key from a file-event response — direct
     *  contacts use `aegisIdFor(localDisplayName)`, group transfers
     *  use the Aegis group id, matching the keys used by
     *  [handleNewChatItems] and the chat list / chat screen. */
    private suspend fun peerKeyForFileEvent(resp: JSONObject): String? {
        val aci = resp.optJSONObject("chatItem") ?: return null
        val info = aci.optJSONObject("chatInfo") ?: return null
        return when (info.optString("type")) {
            "direct" -> {
                val contact = info.optJSONObject("contact") ?: return null
                val name = contact.optString("localDisplayName").ifBlank { return null }
                aegisIdFor(name)
            }
            "group" -> {
                val groupInfo = info.optJSONObject("groupInfo") ?: return null
                val sgid = groupInfo.optLong("groupId", -1L).takeIf { it > 0 } ?: return null
                val aegisGroup = runCatching {
                    AegisApp.instance.repository.getGroupBySimplexId(sgid)
                }.getOrNull() ?: return null
                "group:${aegisGroup.id}"
            }
            else -> null
        }
    }

    /** Pull (fileId, fileName, fileSize) from a file event's
     *  chatItem.file payload. Returns null when the event shape
     *  doesn't carry the file metadata (some `*Warning` events
     *  reference the chat without a file block). */
    private fun fileMetaFromEvent(resp: JSONObject): Triple<Long, String, Long?>? {
        val ci = resp.optJSONObject("chatItem")?.optJSONObject("chatItem")
            ?: resp.optJSONObject("chatItem")
            ?: return null
        val file = ci.optJSONObject("file") ?: return null
        val id = file.optLong("fileId", -1L).takeIf { it > 0 } ?: return null
        val name = file.optJSONObject("fileSource")?.optString("filePath")
            ?.takeIf { it.isNotBlank() }
            ?.let { java.io.File(it).name }
            ?: file.optString("fileName").takeIf { it.isNotBlank() }
            ?: "file"
        val size = file.optLong("fileSize", -1L).takeIf { it > 0 }
        return Triple(id, name, size)
    }

    private suspend fun trackInFlightFile(resp: JSONObject, evType: String) {
        val meta = fileMetaFromEvent(resp) ?: return
        val peerKey = peerKeyForFileEvent(resp) ?: return
        val direction = if (evType == "sndFileStart") InFlightFiles.Entry.Direction.Sending
                        else InFlightFiles.Entry.Direction.Receiving
        InFlightFiles.track(
            InFlightFiles.Entry(
                fileId = meta.first,
                peerKey = peerKey,
                fileName = meta.second,
                totalBytes = meta.third,
                direction = direction,
            ),
        )
    }

    // One-shot guard: dump the real progress-event schema the first time we
    // can't find a size field, so the exact key names can be confirmed from a
    // device log instead of guessed.
    @Volatile private var loggedProgressSchemaOnce = false

    private fun updateInFlightProgress(resp: JSONObject) {
        val ci = resp.optJSONObject("chatItem")?.optJSONObject("chatItem")
            ?: resp.optJSONObject("chatItem")
            ?: return
        val file = ci.optJSONObject("file") ?: return
        val fileId = file.optLong("fileId", -1L).takeIf { it > 0 } ?: return
        val total = file.optLong("fileSize", -1L).takeIf { it > 0 }
            ?: resp.optLong("totalSize", -1L).takeIf { it > 0 }
        // SimpleX's XFTP progress events carry the transferred byte count
        // under `receivedSize` (download) / `sentSize` (upload). The previous
        // code read the abbreviated `rcvdSize`/`sntSize`, which the event does
        // NOT carry — so the lookup always failed, the tracker never advanced,
        // and the banner sat pinned at 0 %. Read the real names first; keep
        // the old abbreviations as a defensive fallback; if STILL nothing, log
        // the raw key set once so we can confirm the schema on-device.
        val rcvd = sequenceOf("receivedSize", "sentSize", "rcvdSize", "sntSize")
            .map { resp.optLong(it, -1L) }
            .firstOrNull { it >= 0 }
            ?: run {
                if (!loggedProgressSchemaOnce) {
                    loggedProgressSchemaOnce = true
                    Log.w(
                        TAG,
                        "file progress: no size field — keys=${resp.keys().asSequence().toList()} " +
                            "sample=${resp.toString().take(300)}",
                    )
                }
                return
            }
        InFlightFiles.progress(fileId, rcvd, total)
    }

    private fun clearInFlightFile(resp: JSONObject) {
        val ci = resp.optJSONObject("chatItem")?.optJSONObject("chatItem")
            ?: resp.optJSONObject("chatItem")
            ?: return
        val fileId = ci.optJSONObject("file")?.optLong("fileId", -1L)?.takeIf { it > 0 } ?: return
        InFlightFiles.done(fileId)
    }

    /** sndFileComplete fires once SimpleX has finished uploading the
     *  source file to the XFTP relay. After this point the core
     *  doesn't read the source again, so we can safely AES-GCM-seal
     *  it in place under a fresh DEK + the active PIN pubkey. The
     *  link from event to local row is the chatItem.meta.itemId
     *  (== messages.simplexItemId, populated by handleSentItem on
     *  the original /_send return). */
    private fun sealOutgoingAttachmentForEvent(resp: JSONObject) {
        val ci = resp.optJSONObject("chatItem")?.optJSONObject("chatItem")
            ?: resp.optJSONObject("chatItem")
            ?: return
        val itemId = ci.optJSONObject("meta")?.optLong("itemId", -1L)?.takeIf { it > 0 }
            ?: return
        scope.launch {
            runCatching {
                AegisApp.instance.repository.sealOutgoingAttachmentByItemId(itemId)
            }.onFailure {
                ConnectionLog.warn(TAG, "sealOutgoingAttachmentByItemId itemId=$itemId failed: $it")
            }
        }
    }

    private suspend fun handleNewChatItems(resp: JSONObject) {
        val items = resp.optJSONArray("chatItems") ?: return
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val info = item.optJSONObject("chatInfo") ?: continue
            val infoType = info.optString("type")
            // Group-message receive path: pre-fix, every inbound group
            // message was
            // silently dropped here because the handler only accepted
            // chatInfo.type == "direct". Send worked, the core
            // delivered, but receivers saw nothing — so a sender felt
            // they were typing into the void.
            //
            // For groups: parse groupInfo.groupId, look up the Aegis
            // GroupEntity via the stored simplexGroupId column, and
            // tag the InboundMessage with groupKey = "group:<uuid>".
            // Repository.recordReceived honours peerKeyOverride and
            // stores the row against the group's conversation id, so
            // GroupChatScreen.observe finds it. fromKey is the
            // sender's localDisplayName-derived aegis id for
            // attribution.
            val groupKey: String? = if (infoType == "group") {
                val groupInfo = info.optJSONObject("groupInfo") ?: continue
                val simplexGroupId = groupInfo.optLong("groupId", -1L).takeIf { it > 0 } ?: continue
                val aegisGroup = runCatching {
                    AegisApp.instance.repository.getGroupBySimplexId(simplexGroupId)
                }.getOrNull() ?: continue
                // Knocking reconciliation: every inbound group message carries
                // our current membership. The instant it reads non-pending,
                // the host has approved us — clear the flag so sends return to
                // the main group scope. (no-op when unchanged.)
                if (aegisGroup.membershipPending) {
                    val ms = groupInfo.optJSONObject("membership")?.optString("memberStatus")
                    val stillPending = ms == "pending_approval" || ms == "pending_review"
                    if (ms != null && !stillPending) {
                        runCatching {
                            AegisApp.instance.repository.setGroupMembershipPending(aegisGroup.id, false)
                        }
                        ConnectionLog.log(TAG, "group #$simplexGroupId membership now '$ms' — approved; sends return to main scope")
                    }
                }
                "group:${aegisGroup.id}"
            } else if (infoType == "direct") null
            else continue
            // Sender attribution. For direct chats it's the contact;
            // for groups the chatItem.chatDir.groupMember names the
            // member who sent it (or null if it's our own broadcast
            // reflected back, in which case we skip — the local send
            // path already recorded it).
            val contactName: String
            val contactId: Long?
            if (groupKey == null) {
                val contact = info.optJSONObject("contact") ?: continue
                contactName = contact.optString("localDisplayName")
                contactId = contact.optLong("contactId", -1L).takeIf { it > 0 }
            } else {
                val chatDir = item.optJSONObject("chatItem")?.optJSONObject("chatDir")
                // chatDir.type == "groupSnd" → our own outgoing
                // reflected back; skip to avoid double-recording.
                if (chatDir?.optString("type") == "groupSnd") continue
                val gm = chatDir?.optJSONObject("groupMember") ?: continue
                // Walk every name field upstream populates — earlier
                // we only read localDisplayName which is the alias
                // YOU set for the member (usually unset on fresh
                // join), so every inbound group message landed as
                // "group-member". memberProfile carries the actual
                // self-announced displayName + fullName. Use whichever
                // is non-empty in this priority order.
                val profile = gm.optJSONObject("memberProfile")
                contactName = sequenceOf(
                    gm.optString("localDisplayName"),
                    profile?.optString("displayName").orEmpty(),
                    profile?.optString("fullName").orEmpty(),
                    gm.optString("memberId"),
                ).firstOrNull { it.isNotBlank() } ?: "group-member"
                contactId = null
            }
            val chatItem = item.optJSONObject("chatItem") ?: continue
            val content = chatItem.optJSONObject("content") ?: continue
            val msgContent = content.optJSONObject("msgContent") ?: continue
            val mcType = msgContent.optString("type")
            // CONTROL CHANNEL: a command on the custom x.aegis type. For
            // 1:1 the type itself is the anti-spoof gate (a vanilla client
            // can't produce it) and SimpleX's authenticated, replay-resistant
            // connection does the rest — so we just read cmd/data and
            // reconstruct the legacy [aegis:<cmd>]<data> body for the existing
            // dispatch below; no signature or counter to verify. A malformed
            // envelope (no cmd) is dropped. Direct contacts only — group
            // control is not carried on this type yet (a signed group path is
            // future work), so a group x.aegis is dropped.
            var controlText: String? = null
            if (mcType == app.aether.aegis.cmdauth.ControlChannel.CONTENT_TYPE) {
                if (groupKey != null) continue
                val plain = app.aether.aegis.cmdauth.ControlChannel.readPlain(msgContent)
                if (plain == null) {
                    ConnectionLog.warn(TAG, "control: dropped malformed x.aegis from $contactName")
                    continue
                }
                controlText = "[aegis:${plain.cmd}]${plain.data}"
            }

            // Chat plane (x.aegis.chat): an Aegis peer's structured message
            // envelope. Decode DNA + text — the text flows on as the chat body
            // exactly like an MCText message, the DNA is threaded into
            // recordReceived so a read receipt we send back can echo it (the
            // cross-device tick match). A malformed envelope (no dna) is dropped.
            var inboundDna: Long? = null
            var inboundReplyDna: Long? = null
            var envelopeText: String? = null
            if (mcType == app.aether.aegis.protocol.ChatEnvelope.CONTENT_TYPE) {
                val env = app.aether.aegis.protocol.ChatEnvelope.decode(msgContent.toString())
                if (env == null) {
                    ConnectionLog.warn(TAG, "chat: dropped malformed x.aegis.chat from $contactName")
                    continue
                }
                inboundDna = app.aether.aegis.protocol.MessageDna.parseOrNull(env.dna)
                inboundReplyDna = env.replyToDna?.let { app.aether.aegis.protocol.MessageDna.parseOrNull(it) }
                envelopeText = env.text
            }

            // Non-text messages carry a file. Per upstream
            // (SimpleXAPI.kt:2790-2800), the client must explicitly
            // call /freceive <fileId> on incoming file invitations —
            // the core does NOT auto-accept. We fire that now; when
            // the download completes the core emits rcvFileComplete
            // which our handleRcvFileComplete picks up.
            if (mcType != "text" &&
                mcType != app.aether.aegis.cmdauth.ControlChannel.CONTENT_TYPE &&
                mcType != app.aether.aegis.protocol.ChatEnvelope.CONTENT_TYPE
            ) {
                val file = chatItem.optJSONObject("file")
                val fileId = file?.optLong("fileId", -1L)?.takeIf { it > 0 }
                if (fileId == null) {
                    ConnectionLog.warn(
                        TAG,
                        "file event missing fileId — mcType=$mcType groupKey=$groupKey",
                    )
                }
                if (fileId != null && file != null &&
                    !shouldAutoDownloadAttachment(
                        mcType, file.optLong("fileSize", -1L), groupKey, contactName,
                    )
                ) {
                    // Auto-download declined by the trust / network / type /
                    // size gate (see shouldAutoDownloadAttachment): record a
                    // tap-to-download PLACEHOLDER and remember the fileId,
                    // but do NOT /freceive. Skipping the download alone would
                    // make the file invisible — a file invitation writes no
                    // chat row of its own; only a completed download does —
                    // so the placeholder is what tells the user a file is
                    // waiting. The bind + continue below run for this branch
                    // too, so the sender is still resolved.
                    recordDeferredAttachmentRow(chatItem, file, fileId, mcType, contactName, groupKey)
                } else if (fileId != null) {
                    // approved_relays=on (was off — bug-fix 2026-06-05,
                    // "files are not delivered"). `approved_relays` is NOT
                    // "the relay must match the sender's" as an earlier
                    // comment wrongly claimed — it gates whether the core
                    // will pull from XFTP relays that aren't already in
                    // THIS device's approved-server set. The sender picks
                    // the relay (often a SimpleX default preset the
                    // receiver hasn't explicitly approved), so with =off
                    // the core returns ChatErrorType.FileNotApproved and
                    // the download never starts. Worse, that error comes
                    // back as a top-level "chatError" (not "chatCmdError"),
                    // so the old `freceiveOk` check read it as success while
                    // the transfer silently stalled — the "receiving
                    // forever, 0.0/0.4 MB" report. Upstream's own flow is
                    // exactly this: it ALSO retries with userApprovedRelays
                    // =true after asking; Aegis is headless (no approve
                    // dialog) and we control both ends + the payload is E2E
                    // encrypted, so we approve up front.
                    //
                    // encrypt=off (was on — bug-fix 2026-06-05, "received
                    // files are corrupt"). encrypt=on makes the core store
                    // the file under ITS OWN CryptoFile scheme and hand back
                    // fileSource.cryptoArgs (the per-file key/nonce) needed
                    // to read it. Aegis never consumes cryptoArgs — the
                    // receive path takes only filePath — so with =on we'd
                    // seal core-CIPHERTEXT under the PIN key and every
                    // received photo/file would decode to garbage. This was
                    // latent until the approved_relays fix let downloads
                    // actually complete. Aegis has its OWN at-rest layer:
                    // recordReceivedAttachment → sealAttachmentInPlace
                    // AES-GCM-encrypts the file under a PIN-sealed DEK and
                    // deletes the plaintext. So we take the file plaintext
                    // (=off) into app-private internal storage and let our
                    // seal own at-rest. The plaintext window is the sub-
                    // second move inside /data/data (not other-app
                    // readable); when no PIN is set the file stays plaintext
                    // there, same as everywhere else for no-PIN users. This
                    // supersedes "audit #3" — that fix predated the
                    // PIN seal-in-place pipeline and assumed encrypt=on was
                    // the only at-rest protection; it's now redundant AND
                    // unreadable, so off is strictly correct.
                    val freceiveResp = send("/freceive $fileId approved_relays=on encrypt=off")
                    // Positive-accept check: the core answers a successful
                    // /freceive with rcvFileAccepted (or
                    // rcvFileAcceptedSndCancelled if the sender bailed).
                    // Anything else — chatCmdError, chatError/FileNotApproved,
                    // FileAlreadyReceiving — is NOT an accept. We only raise
                    // the in-flight banner when the transfer truly started,
                    // so a hard failure can't leave a banner stuck forever.
                    val freceiveOk = freceiveResp.contains("\"type\":\"rcvFileAccepted\"")
                    ConnectionLog.log(
                        TAG,
                        "/freceive $fileId ($mcType, groupKey=$groupKey) ok=$freceiveOk " +
                            (if (!freceiveOk) "→ ${freceiveResp.take(220)}" else ""),
                    )
                    if (freceiveOk) {
                        // Surface the in-flight transfer to the chat UI
                        // right away. The banner stays up until
                        // rcvFileComplete (success) or one of the cancel
                        // / error events fires — both clear via the
                        // file-event dispatch up in classifyInbound.
                        val fileName = file.optJSONObject("fileSource")?.optString("filePath")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { java.io.File(it).name }
                            ?: file.optString("fileName").takeIf { it.isNotBlank() }
                            ?: "file"
                        val fileSize = file.optLong("fileSize", -1L).takeIf { it > 0 }
                        val peerKeyForBanner = groupKey ?: aegisIdFor(contactName)
                        InFlightFiles.track(
                            InFlightFiles.Entry(
                                fileId = fileId,
                                peerKey = peerKeyForBanner,
                                fileName = fileName,
                                totalBytes = fileSize,
                                direction = InFlightFiles.Entry.Direction.Receiving,
                            ),
                        )
                    }
                }
                // Bind sender if we don't already know them so
                // rcvFileComplete can route correctly later. Skip
                // bind for group-side senders — they aren't direct
                // contacts of ours.
                if (groupKey == null) {
                    val id = aegisIdFor(contactName)
                    if (!peerByKey.containsKey(id)) bindContact(contactName, contactId, contactName)
                }
                continue
            }
            // Verified signed-control reconstructs the legacy [aegis:…] body;
            // everything else is the real chat text.
            val text = controlText ?: envelopeText ?: msgContent.optString("text")
            // If we have no binding yet (e.g. invitee paired but hasn't
            // emitted contactConnected yet on our side), opportunistically
            // bind now so subsequent sends route correctly. Skip bind
            // for group senders.
            val id = aegisIdFor(contactName)
            if (groupKey == null && !peerByKey.containsKey(id)) {
                bindContact(contactName, contactId, contactName)
            }
            // Self-heal a stalled Aegis handshake. The hello/caps exchange
            // fires once on contactConnected (+ a cold-start retry); if that
            // window is missed — a dropped first hello, or the OS suspending
            // the app mid-pairing — `controlPubKey` stays null and the peer is
            // stuck looking like a vanilla SimpleX contact, which silently
            // degrades EVERYTHING Aegis-specific (chat envelope, presence,
            // tier, receipts). Receiving anything from them proves the link is
            // up NOW, so re-greet once per session for an un-bootstrapped
            // direct peer; the handshake then completes on the next exchange
            // with no manual "re-send hello" from the user. Guarded so a
            // genuinely-vanilla contact gets at most one extra empty hello.
            if (groupKey == null && helloSelfHealed.add(id)) {
                scope.launch {
                    val bootstrapped = runCatching {
                        !AegisApp.instance.repository.knownPeerByKey(id)
                            ?.controlPubKey.isNullOrEmpty()
                    }.getOrDefault(true)
                    if (!bootstrapped) {
                        ConnectionLog.log(
                            TAG,
                            "self-heal: re-greeting un-bootstrapped $contactName",
                        )
                        sendAegisHello(contactName)
                    }
                }
            }
            // AEGIS reaction — a control message that natively quotes the
            // message it reacts to. The quote's itemId is OUR local id for
            // that message (SimpleX maps it across the connection), so we
            // resolve the target here and fold the emote into its reaction
            // set, then drop the carrier (never a chat bubble). Works for
            // 1:1 (signed → controlText) and groups (plaintext text) alike;
            // the reactor key is the sender's aegis id so the UI can tell
            // self from peer. A reaction with no resolvable quote is junk
            // we silently discard.
            // DNA-addressed reaction (Aegis chat path) — resolves the target by
            // the shared message DNA carried in the body, NOT SimpleX's native
            // quote. Body: `[aegis:reactdna]{"d":<nanos>,"e":<emote>,"a":<bool>}`.
            // No quotedItem needed, which is the whole point: native quote
            // doesn't attach to the x.aegis.chat content type.
            if (text.startsWith("[aegis:reactdna]")) {
                val dirType = chatItem.optJSONObject("chatDir")?.optString("type")
                if (dirType == "directSnd") continue   // our own, already mirrored
                runCatching {
                    val obj = JSONObject(text.removePrefix("[aegis:reactdna]"))
                    val dna = obj.optLong("d", -1L).takeIf { it > 0 }
                    val emote = obj.optString("e")
                    val add = obj.optBoolean("a", true)
                    if (dna != null && emote.isNotEmpty()) {
                        val row = AegisApp.instance.repository.messageByDna(dna)
                        val itemId = row?.simplexItemId
                        if (itemId != null) applyReaction(itemId, emote, id, add)
                        else ConnectionLog.warn(TAG, "reactdna: no local row for dna=$dna from $contactName")
                    }
                }.onFailure { Log.w(TAG, "inbound reactdna parse failed", it) }
                continue
            }
            if (text.startsWith("[aegis:reaction]")) {
                // Our own sent reaction reflected back must NOT re-apply —
                // the send path already mirrored it under "me". Group
                // self-sends (groupSnd) are filtered far above; guard the
                // direct case here.
                val dirType = chatItem.optJSONObject("chatDir")?.optString("type")
                if (dirType == "directSnd") continue
                val targetItemId = chatItem.optJSONObject("quotedItem")
                    ?.optLong("itemId", -1L)?.takeIf { it > 0 }
                if (targetItemId != null) {
                    runCatching {
                        val obj = JSONObject(text.removePrefix("[aegis:reaction]"))
                        val emote = obj.optString("e")
                        val add = obj.optBoolean("a", true)
                        if (emote.isNotEmpty()) applyReaction(targetItemId, emote, id, add)
                    }.onFailure { Log.w(TAG, "inbound reaction parse failed", it) }
                } else {
                    ConnectionLog.warn(TAG, "reaction: no quoted target from $contactName — dropped")
                }
                continue
            }
            // SimpleX's wire format doesn't carry our MessageType across
            // the wall, so we tag control-class messages with a body
            // prefix on send and strip + re-tag on receive. Keeps chat
            // free of LOCATION / STATUS / SOS / STORY spam and routes
            // them to their dedicated handlers. Senders set the prefix
            // in ProtocolService.broadcastLocationToFamily,
            // .broadcastStatusToFamily, SOSHandler.broadcast, etc.
            val (msgType, msgBody) = classifyInbound(text)
            // Peer-capability detection: any `[aegis:…]`-prefixed
            // inbound proves the sender is running Aegis (no other
            // SimpleX client emits those tags). Flip the persisted
            // isAegis flag so outbound control envelopes (typing,
            // status, location, sos) are gated upstream and don't
            // leak raw marker text to vanilla SimpleX clients. The
            // DAO update is filtered by `isAegis = 0` so this is a
            // cheap no-op for already-confirmed peers.
            if (groupKey == null && text.startsWith("[aegis:")) {
                runCatching { AegisApp.instance.repository.markPeerIsAegis(id) }
                    .onFailure { Log.w(TAG, "markPeerIsAegis failed for $id", it) }
                // Control-channel bootstrap: a hello carries the peer's
                // Ed25519 control pubkey (`[aegis:hello]<base64url-pub>`),
                // reconstructed here from the x.aegis envelope like every
                // other command. 1:1 control no longer verifies a signature,
                // so the key isn't used to authenticate commands; we persist
                // it as the "channel bootstrapped" marker HelloBroadcaster
                // reads (non-empty controlPubKey → stop re-greeting this peer
                // on cold start) and as the key material a future signed group
                // path will need.
                if (text.startsWith("[aegis:hello]")) {
                    val pubB64 = text.removePrefix("[aegis:hello]").trim()
                    if (pubB64.isNotEmpty()) {
                        runCatching {
                            AegisApp.instance.repository.setPeerControlPubKey(id, pubB64)
                        }.onFailure { Log.w(TAG, "store control pubkey failed for $id", it) }
                    }
                }
            }
            // Hard-deny PLAINTEXT control. Every 1:1 command — the hello
            // included — rides the x.aegis channel, so an [aegis:*]
            // arriving as TEXT (controlText is null → it did NOT come through
            // the x.aegis path) is a hand-typed spoof. Drop it without
            // dispatching. Direct only — group control still rides text until
            // the group path lands.
            if (controlText == null && groupKey == null &&
                text.startsWith("[aegis:")
            ) {
                ConnectionLog.warn(
                    TAG,
                    "control: dropped plaintext ${text.take(24)}… from $contactName",
                )
                continue
            }
            // Pass the SimpleX itemId + chatRef through so the
            // ProtocolManager can ask us to purge the core's own
            // plaintext copy once the (PIN-sealed) Aegis row has
            // been persisted. chatRef is the same shape used by
            // /_delete item below: "@<contactId>" for direct chats,
            // "#<simplexGroupId>" for groups. Either may be null
            // (e.g. group with unknown simplexGroupId, contact not
            // yet bound) — caller skips the purge in that case.
            val itemId = chatItem.optJSONObject("meta")?.optLong("itemId", -1L)
                ?.takeIf { it > 0 }
            // Reply threading: a quoted message carries chatItem.quotedItem
            // with the original's itemId. Store it so a tap on this reply's
            // citation can jump to the quoted message.
            val quotedItemId = chatItem.optJSONObject("quotedItem")
                ?.optLong("itemId", -1L)?.takeIf { it > 0 }
            val chatRef: String? = if (groupKey != null) {
                val sgid = groupKey.removePrefix("group:").let { aid ->
                    runCatching { AegisApp.instance.repository.getGroup(aid)?.simplexGroupId }
                        .getOrNull()
                }
                sgid?.let { "#$it" }
            } else {
                contactId?.let { "@$it" }
            }
            // Refresh the group-member display-name cache from the
            // sender's announced profile. Part of group hardening —
            // without this,
            // members who aren't also 1:1-paired KnownPeers render
            // as "member: <8-char hex>" forever. Skip the synthetic
            // "group-member" fallback (means no profile was found
            // upstream — no point caching the placeholder). Cheap:
            // a single UPDATE WHERE keyed on the composite primary
            // key, on the IO dispatcher we're already on.
            if (groupKey != null && contactName.isNotBlank() && contactName != "group-member") {
                val groupId = groupKey.removePrefix("group:")
                runCatching {
                    AegisApp.instance.repository.cacheGroupMemberDisplayName(
                        groupId = groupId,
                        peerPubkey = id,
                        displayName = contactName,
                    )
                }.onFailure {
                    Log.w(TAG, "cacheGroupMemberDisplayName failed for $id in $groupId", it)
                }
            }
            // Group module gate — under group module isolation.
            // When disabled, drop group messages at the
            // dispatch boundary: no inboundFlow emission, no
            // notification surface, no chat history row. Phase 2
            // will issue the actual SMP unsubscribe so the bytes
            // never arrive; for now they arrive at the SimpleX
            // agent but die here.
            if (groupKey != null) {
                if (!app.aether.aegis.groups.GroupModulePrefs.currentEnabled()) {
                    ConnectionLog.log(
                        TAG,
                        "group message dropped — module disabled groupKey=$groupKey",
                    )
                    continue
                }
                // Per-group toggle. User can suspend Aegis
                // Amsterdam while keeping the family group active.
                val gid = groupKey.removePrefix("group:")
                val aegisGroup = runCatching {
                    AegisApp.instance.repository.getGroup(gid)
                }.getOrNull()
                if (aegisGroup != null && !aegisGroup.enabled) {
                    ConnectionLog.log(
                        TAG,
                        "group message dropped — per-group disabled groupKey=$groupKey",
                    )
                    continue
                }
            }
            inboundFlow.emit(
                InboundMessage(
                    fromKey = id,
                    body = msgBody,
                    type = msgType,
                    timestamp = System.currentTimeMillis(),
                    groupKey = groupKey,
                    sourceItemId = itemId,
                    sourceChatRef = chatRef,
                    replyToItemId = quotedItemId,
                    messageDna = inboundDna,
                    replyToDna = inboundReplyDna,
                )
            )
        }
    }

    /**
     * `/_delete item <chatRef> <itemId> internal` — purge our local
     * (SimpleX core's chat.db) copy of an inbound message after
     * Aegis has sealed it into its own DB. The `internal` mode is
     * the local-only counterpart of [deleteMessageForEveryone]'s
     * `broadcast`; the peer's copy is untouched. Idempotent: if the
     * row is already gone, the core returns an error which we
     * swallow to true (caller doesn't differentiate "already done"
     * from "freshly done"). Returns false only on hard failures
     * worth surfacing to the log.
     */
    override suspend fun purgeOriginal(chatRef: String, itemId: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (!isHealthy) return@withContext false
            val cmd = "/_delete item $chatRef $itemId internal"
            val resp = send(cmd)
            val ok = !resp.contains("\"type\":\"chatCmdError\"") ||
                resp.contains("\"errorType\":\"messageDeleted\"") ||
                resp.contains("\"chatItemNotFound\"")
            if (!ok) {
                ConnectionLog.warn(TAG, "/_delete internal $chatRef $itemId failed: ${resp.take(200)}")
            }
            ok
        }

    /**
     * Inbound file finished downloading. CR.RcvFileComplete carries an
     * AChatItem whose chatItem.file.fileSource.filePath is the bare
     * filename inside our configured appFilesDir. Resolve to an
     * absolute path, write directly to the Repository as a received
     * attachment (richer than InboundMessage carries), and post a
     * notification via the inbound flow with prerecorded semantics —
     * but since ProtocolManager always recordReceived's, we just write
     * the attachment row here and rely on the SENDER's recordSent for
     * the local sender-side copy. Notification is fired manually.
     */
    /**
     * Map a SimpleX CIStatus type tag to our short status string.
     * Used by the message bubble to render ✓ / ✓✓ icons.
     */
    private fun mapCiStatus(type: String): String? = when (type) {
        "sndNew"      -> "sending"
        "sndSent"     -> "sent"
        // Transactional Delivery: SimpleX's own delivery ack ("sndRcvd",
        // = "reached their device") does NOT drive any tick. We purge our
        // transport copy at "sent", so for real chat messages this event
        // won't even fire. The visible ticks above "sent" are all Aether
        // Protocol receipts the recipient sends back: [aegis:delivered]
        // (single bright), [aegis:sealed] (bright + dim ✓✓), [aegis:read]
        // (bright ✓✓) — see ProtocolManager + AegisApp.handleInboundStatus.
        "sndRcvd"     -> null
        "sndErrorAuth", "sndError" -> "error"
        // sndWarning is transient / usually self-recovering. Surfacing it
        // as a terminal 'error' would stick (the monotonic guard keeps
        // 'error' from being overwritten by a later 'delivered'/'read'),
        // so we skip it and let the real terminal status land.
        "sndWarning"  -> null
        "rcvNew", "rcvRead" -> null   // inbound, no status surfacing
        else -> null
    }

    private suspend fun handleStatusesUpdated(resp: JSONObject) {
        val items = resp.optJSONArray("chatItems") ?: return
        for (i in 0 until items.length()) {
            applyStatusFromAci(items.optJSONObject(i) ?: continue)
        }
    }

    /** Singular variant — upstream's chatItemUpdated wraps a single
     *  AChatItem. Pipe through the same status extraction so a
     *  status change delivered via the singular event still flips
     *  the bubble. Without this dispatch the "delivered" tick never
     *  appeared on certain SimpleX builds (user-visible: row stayed
     *  ✓ until the read receipt arrived, then jumped straight to
     *  the bright ✓✓). */
    private suspend fun handleChatItemUpdated(resp: JSONObject) {
        val aci = resp.optJSONObject("chatItem") ?: return
        applyStatusFromAci(aci)
    }

    /**
     * Inbound `contactUpdated` event — peer changed their display
     * name, bio, or avatar. We refresh the KnownPeer mirror so every
     * surface (radar tile, chat header, contact detail) reads the
     * new value without a re-pair. Avatar arrives as a base64 data
     * URL inside profile.image; we decode + store to a local file
     * under filesDir/peer_avatars/ so AsyncImage can render it
     * straight away.
     */
    private suspend fun handleContactUpdated(resp: JSONObject) {
        val contact = resp.optJSONObject("toContact") ?: return
        val localDisplayName = contact.optString("localDisplayName").ifBlank { return }
        val peerKey = aegisIdFor(localDisplayName)
        // AEGIS PROTOCOL (Stage 4): under
        // permanent incognito a peer's SimpleX profile is ALWAYS their
        // random per-connection handle (no real name/avatar). It must NEVER
        // populate the announced* identity fields — doing so would overwrite
        // a real identity the peer revealed via [aegis:identity] with their
        // meaningless random handle. announced* is now sourced EXCLUSIVELY
        // from the [aegis:identity] overlay (AegisApp.handleInboundStatus).
        // Un-elevated contacts therefore have empty announced* and render as
        // their stored random handle / local nickname, exactly as intended.
        // (Pre-incognito contacts keep whatever announced* they already had —
        // the documented migration boundary.)
        ConnectionLog.log(TAG, "contactUpdated peer=$peerKey (profile mirror suppressed — incognito)")
    }

    /** Decode a `data:image/...;base64,…` URL into a JPEG file under
     *  `filesDir/peer_avatars/<peerKey-hash>.jpg` and return the
     *  absolute path. Returns null on any parse / decode / write
     *  failure. Same filename for the same peer so an updated avatar
     *  overwrites the previous one rather than accumulating. */
    private fun decodePeerAvatar(peerKey: String, dataUrl: String): String? = runCatching {
        val comma = dataUrl.indexOf(',').takeIf { it >= 0 } ?: return null
        val base64 = dataUrl.substring(comma + 1)
        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        if (bytes.isEmpty()) return null
        val dir = java.io.File(context.filesDir, "peer_avatars").apply { mkdirs() }
        val safeName = peerKey.hashCode().toString().replace("-", "n") + ".jpg"
        val out = java.io.File(dir, safeName)
        // Atomic write — temp + rename. The previous direct
        // writeBytes truncated [out] to 0 bytes before writing, so a
        // composable hitting File.exists() during that window saw
        // true but AsyncImage couldn't decode the empty file. Result
        // was the avatar "popping in and out of existence" on every
        // contactUpdated event. tmp + rename leaves [out] either
        // intact (old bytes) or replaced (new bytes), never empty.
        val tmp = java.io.File(dir, "$safeName.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(out)) {
            // renameTo can fail on some filesystems if the target
            // exists; fall back to delete+rename.
            out.delete()
            if (!tmp.renameTo(out)) {
                tmp.delete()
                return null
            }
        }
        out.absolutePath
    }.getOrNull()

    private suspend fun applyStatusFromAci(aci: JSONObject) {
        val ci = aci.optJSONObject("chatItem") ?: return
        val meta = ci.optJSONObject("meta") ?: return
        val itemId = meta.optLong("itemId", -1L).takeIf { it > 0 } ?: return
        val ciStatus = meta.optJSONObject("itemStatus") ?: return
        val rawType = ciStatus.optString("type")
        val status = mapCiStatus(rawType) ?: run {
            ConnectionLog.log(TAG, "status-skip item=$itemId raw=$rawType")
            return
        }
        AegisApp.instance.repository.setStatusByItemId(itemId, status)
        ConnectionLog.log(TAG, "status-apply item=$itemId $rawType→$status")
        // Transactional Delivery — send side.
        // The instant a sent message is on the relay ("sent"), purge our
        // SimpleX transport copy so the core stores nothing of the
        // conversation. The relay still delivers to the recipient; the
        // two-dark tick arrives later via their [aegis:sealed] receipt, and
        // the read tick via [aegis:read] — both keyed by the itemId we keep
        // in OUR own DB, independent of the now-deleted SimpleX item. If the
        // relay later fails to deliver, the resend comes from Aegis's sealed
        // copy (outbox), not SimpleX.
        if (status == "sent") {
            runCatching {
                val row = AegisApp.instance.repository.messageBySimplexItemId(itemId)
                val peerKey = row?.peerKey
                // Skip attachments — their SimpleX item must survive until
                // the file transfer finishes; the attachment-complete flow
                // purges those (see sealOutgoingAttachment / line ~4490).
                if (row != null && row.attachmentPath == null && peerKey != null) {
                    val chatRef: String? = when {
                        peerKey.startsWith("group:") -> {
                            val sgid = AegisApp.instance.repository
                                .getGroup(peerKey.removePrefix("group:"))?.simplexGroupId
                            sgid?.let { "#$it" }
                        }
                        else -> peerByKey[peerKey]?.contactId?.let { "@$it" }
                    }
                    if (chatRef != null) purgeOriginal(chatRef, itemId)
                }
            }.onFailure { ConnectionLog.warn(TAG, "send-side purge item=$itemId failed: $it") }
        }
    }

    /**
     * Delete a previously-sent message on BOTH sides. Per upstream
     * ApiDeleteChatItem (SimpleXAPI.kt:3883):
     *   /_delete item <chatRef> <itemIds> <mode>
     * with `mode = "broadcast"` for the "delete for everyone"
     * flavour vs "internal" which only nukes our local copy.
     *
     * `peerKey` is the same conversation key the rest of the chat
     * code uses — "group:<aegisGroupId>" for groups, the contact
     * pubkey for direct chats.
     * Returns false silently when the peer / group can't be
     * resolved so the local-delete path still runs.
     */
    suspend fun deleteMessageForEveryone(
        peerKey: String,
        itemId: Long,
        // DNA of the message to retract. For a 1:1 peer on the chat envelope we
        // send an [aegis:deletedna] control frame instead of SimpleX's native
        // broadcast delete — native delete can't reach the peer's copy because
        // Aegis SEALS + PURGES the transport's plaintext mirror on receipt, so
        // the message only lives in their Aegis DB (and there was no inbound
        // delete handler at all). The control frame resolves the DNA to their
        // sealed row and removes it. Groups / legacy / vanilla keep the native
        // broadcast (best-effort).
        targetDna: Long? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        // Direct chat-envelope peer with a DNA → retract by DNA (the path that
        // actually reaches a sealed message).
        if (!peerKey.startsWith("group:") && targetDna != null) {
            val contact = peerByKey[peerKey]
            val contactId = contact?.contactId
            val supportsDna = runCatching {
                AegisApp.instance.repository.knownPeerByKey(peerKey)?.let {
                    app.aether.aegis.protocol.AegisCaps.supports(
                        it.peerCapabilities, app.aether.aegis.protocol.AegisCaps.EDIT_DNA,
                    )
                }
            }.getOrNull() == true
            if (contactId != null && supportsDna) {
                val ok = sendControl(peerKey, contactId, "[aegis:deletedna]$targetDna")
                ConnectionLog.log(TAG, "delete-for-all (dna) $peerKey dna=$targetDna ok=$ok")
                return@withContext ok
            }
        }
        val chatRef: String = if (peerKey.startsWith("group:")) {
            val aegisGroupId = peerKey.removePrefix("group:")
            val group = runCatching {
                AegisApp.instance.repository.getGroup(aegisGroupId)
            }.getOrNull()
            val sgid = group?.simplexGroupId ?: run {
                lastSendError = "delete: no simplexGroupId for $peerKey"
                return@withContext false
            }
            "#$sgid"
        } else {
            val contact = peerByKey[peerKey] ?: run {
                lastSendError = "delete: no SimpleX contact bound for $peerKey"
                return@withContext false
            }
            val contactId = contact.contactId ?: run {
                lastSendError = "delete: missing contactId for ${contact.name}"
                return@withContext false
            }
            "@$contactId"
        }
        val cmd = "/_delete item $chatRef $itemId broadcast"
        val resp = send(cmd)
        val ok = !resp.contains("\"type\":\"chatCmdError\"")
        ConnectionLog.log(
            TAG,
            "delete-for-all $chatRef item=$itemId ok=$ok" +
                (if (!ok) " resp=${resp.take(220)}" else ""),
        )
        ok
    }

    /**
     * Send a reaction on the AEGIS protocol — NOT SimpleX's native
     * `/_reaction` (which is locked to a fixed eight-emoji set). Any
     * emote string is allowed.
     *
     * The cross-device "which message?" link is the hard part: SimpleX
     * itemIds are per-device, so the sender's [targetItemId] is
     * meaningless to the recipient. We borrow SimpleX's own quote
     * mapping: the reaction is sent as a control message that NATIVELY
     * QUOTES the target item. On arrival the core hands the recipient
     * `chatItem.quotedItem.itemId` = THEIR local itemId for that same
     * message, so they can resolve the target without us inventing a
     * shared id. The emote itself rides in the signed `[aegis:reaction]`
     * body — `{"e":<emote>,"a":<add>}`.
     *
     * 1:1 reactions are signed (Ed25519 control channel); group
     * reactions ride plaintext, consistent with all other group control
     * until the group member-pubkey exchange lands. Either way the
     * carrier is classified as control and never shows as a chat bubble.
     *
     * Applies the sender's own reaction optimistically under the
     * reserved reactor id `"me"` so the bubble updates immediately.
     */
    suspend fun sendSignedReaction(
        peerPubkey: String,
        targetItemId: Long,
        emote: String,
        add: Boolean = true,
        // DNA of the reacted-to message, when known. For a 1:1 peer that speaks
        // the chat envelope we address the target by DNA in an [aegis:reactdna]
        // control frame instead of SimpleX's NATIVE quote — native quote does
        // not attach to the x.aegis.chat custom content type, so the old quoted
        // reaction silently failed (ok=false) on Aegis messages. Null / group /
        // non-chat peers keep the native-quote path.
        targetDna: Long? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isHealthy) return@withContext false
        if (emote.isBlank()) return@withContext false
        val data = JSONObject().put("e", emote).put("a", add).toString()
        val controlBody = "[aegis:reaction]$data"
        val ok = if (peerPubkey.startsWith("group:")) {
            val aegisGroupId = peerPubkey.removePrefix("group:")
            val sgid = runCatching {
                AegisApp.instance.repository.getGroup(aegisGroupId)?.simplexGroupId
            }.getOrNull() ?: run {
                lastSendError = "reaction: no simplexGroupId for $peerPubkey"
                return@withContext false
            }
            sendGroupRaw(sgid, controlBody, quotedItemId = targetItemId)
        } else {
            val contact = peerByKey[peerPubkey] ?: run {
                lastSendError = "reaction: no SimpleX contact bound for $peerPubkey"
                return@withContext false
            }
            val contactId = contact.contactId ?: run {
                lastSendError = "reaction: missing contactId for ${contact.name}"
                return@withContext false
            }
            // React by DNA whenever the target HAS a DNA — i.e. it's an
            // x.aegis.chat envelope message. We deliberately DON'T also require
            // the peer's announced EDIT_DNA capability: that gate was the
            // recurring "reactions broken" bug. If the caps announce was lost or
            // stale on a lossy link, useDna went false and we fell back to
            // SimpleX native quote, which CANNOT attach to the x.aegis.chat
            // content type and silently failed (ok=false, no reaction). A
            // message with a DNA proves the peer speaks the envelope, and
            // reactdna is harmless to a peer that somehow can't read it (they
            // just ignore it) — so a DNA is sufficient. Only a truly DNA-less
            // (vanilla/legacy) target falls back to native quote.
            val useDna = targetDna != null
            if (useDna) {
                val d = JSONObject().put("d", targetDna).put("e", emote).put("a", add).toString()
                sendControl(peerPubkey, contactId, "[aegis:reactdna]$d")  // no native quote
            } else {
                sendControl(peerPubkey, contactId, controlBody, quotedItemId = targetItemId)
            }
        }
        ConnectionLog.log(
            TAG,
            "reaction → $peerPubkey item=$targetItemId dna=$targetDna emote=$emote add=$add ok=$ok",
        )
        if (ok) {
            runCatching { applyReaction(targetItemId, emote, REACTOR_SELF, add) }
                .onFailure { Log.w(TAG, "reaction self-mirror failed", it) }
        }
        ok
    }

    /**
     * Fold one reaction into the [itemId] row's reaction set.
     *
     * Storage is `{"<emote>": ["<reactorId>", …]}` — the SET of
     * reactors per emote, NOT a bare count. Tracking WHO reacted is
     * what makes toggling correct (a self-remove can't underflow a
     * shared counter) and lets the UI highlight the emotes the local
     * user picked. [reactor] is [REACTOR_SELF] for our own reaction or
     * the peer's aegis id for an inbound one. Removing the last reactor
     * drops the emote; an empty row collapses to null.
     */
    private suspend fun applyReaction(
        itemId: Long,
        emote: String,
        reactor: String,
        add: Boolean,
    ) {
        val repo = AegisApp.instance.repository
        val current = JSONObject(repo.reactionsByItemId(itemId) ?: "{}")
        val arr = current.optJSONArray(emote) ?: org.json.JSONArray()
        // LinkedHashSet: de-dupe reactors, preserve first-seen order.
        val reactors = LinkedHashSet<String>()
        for (i in 0 until arr.length()) reactors.add(arr.getString(i))
        if (add) reactors.add(reactor) else reactors.remove(reactor)
        if (reactors.isEmpty()) current.remove(emote)
        else current.put(emote, org.json.JSONArray(reactors.toList()))
        val json = if (current.length() == 0) null else current.toString()
        repo.setReactionsByItemId(itemId, json)
        ConnectionLog.log(
            TAG,
            "reaction-apply: item=$itemId emote=$emote reactor=$reactor add=$add → $json",
        )
    }

    /**
     * The four-gate auto-download decision for an incoming file invitation:
     * trust ∧ network ∧ type ∧ size. Returns true to `/freceive` now, false
     * to defer to a tap-to-download placeholder. Order is cheap-checks-first
     * with trust first because an Untrusted sender is an unconditional defer.
     *
     * Trust gate applies to 1:1 only — group members aren't known-peer
     * contacts of ours, so there's no per-sender tier to read; group files
     * fall through to the network/type/size gates. See AttachmentPrefs and
     * docs/SPEC_WIFI_ONLY_ATTACHMENTS.md for the rationale of each gate.
     */
    private suspend fun shouldAutoDownloadAttachment(
        mcType: String,
        fileSize: Long,
        groupKey: String?,
        contactName: String,
    ): Boolean {
        val ctx = AegisApp.instance
        val prefs = app.aether.aegis.attachment.AttachmentPrefs(ctx)
        // Trust gate — 1:1 ONLY. Group members aren't known-peer contacts of
        // ours, so there's no per-sender tier to read; group files skip
        // straight to the network / type / size gates below.
        if (groupKey == null) {
            val tier = runCatching {
                AegisApp.instance.repository.knownPeerByKey(aegisIdFor(contactName))?.trustTier
            }.getOrNull()?.let {
                runCatching { app.aether.aegis.data.TrustTier.valueOf(it) }.getOrNull()
            }
            // An Untrusted sender's files NEVER auto-pull, regardless of
            // network / type / size.
            if (tier == app.aether.aegis.data.TrustTier.UNTRUSTED) return false
        }
        // Network gate: defer EVERY attachment — 1:1 AND group — on a metered
        // link when the Wi-Fi-only toggle is on. Group files USED to bypass
        // this entirely (and the size + type gates), so once group media send
        // was wired they auto-pulled gigabytes over mobile (user-reported
        // 2026-06-19). Groups honour the same policy now; deferred group files
        // get a tap-to-download placeholder like 1:1.
        if (prefs.wifiOnly && app.aether.aegis.net.NetworkMetering.isMetered(ctx)) return false
        // Type gate: only the user-selected media buckets auto-pull (any link).
        if (app.aether.aegis.attachment.MediaType.forMcType(mcType) !in prefs.autoTypes) return false
        // Size gate: nothing over the cap auto-pulls (any link). A negative /
        // zero (unknown) size can't be over a positive cap, so it passes —
        // the network / type gates still governed it.
        val cap = prefs.maxAutoBytes
        if (cap != app.aether.aegis.attachment.AttachmentPrefs.UNLIMITED_BYTES && fileSize > cap) return false
        return true
    }

    /**
     * Build + persist the deferred placeholder row for a file invitation we
     * chose not to auto-download, and remember its fileId so a later tap can
     * pull it. Mirrors the metadata derivation [handleRcvFileComplete] does
     * on a completed file (mime from extension, row type from mcType) so the
     * placeholder bubble already shows the right media kind + size before any
     * bytes are pulled.
     */
    private suspend fun recordDeferredAttachmentRow(
        chatItem: JSONObject,
        file: JSONObject,
        fileId: Long,
        mcType: String,
        contactName: String,
        groupKey: String?,
    ) {
        val sourceItemId = chatItem.optJSONObject("meta")?.optLong("itemId", -1L)
            ?.takeIf { it > 0 } ?: return
        // Prefer the ORIGINAL sent name (file.fileName) for display + MIME;
        // the on-disk path basename is the core's own handle and would show a
        // mangled "<uuid>.vnd.andr" placeholder. Same precedence as the
        // completion path (handleRcvFileComplete).
        val displayName = file.optString("fileName").takeIf { it.isNotBlank() }
            ?: file.optJSONObject("fileSource")?.optString("filePath")
                ?.takeIf { it.isNotBlank() }?.let { java.io.File(it).name }
            ?: "file"
        val fileSize = file.optLong("fileSize", 0L)
        // Same MIME + row-type derivation as the completion path so the
        // placeholder renders as the right media kind.
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val mimeFromExt = if (ext.isNotEmpty()) {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        } else null
        val mime = mimeFromExt ?: when (mcType) {
            "image" -> "image/*"
            "video" -> "video/*"
            "voice" -> "audio/*"
            else -> "application/octet-stream"
        }
        val msgType = when (mcType) {
            "image" -> MessageType.PHOTO
            "video" -> MessageType.PHOTO
            "voice" -> MessageType.VOICE
            else -> MessageType.FILE
        }
        val caption = chatItem.optJSONObject("content")
            ?.optJSONObject("msgContent")?.optString("text").orEmpty()
        AegisApp.instance.repository.recordDeferredAttachment(
            fromKey = aegisIdFor(contactName),
            caption = caption,
            attachmentMime = mime,
            attachmentSize = fileSize,
            attachmentName = displayName,
            type = msgType,
            protocol = Protocol.SIMPLEX,
            peerKeyOverride = groupKey,
            sourceItemId = sourceItemId,
        )
        // Remember the fileId so a tap can /freceive it — survives process
        // death (see DeferredDownloads).
        app.aether.aegis.attachment.DeferredDownloads.put(AegisApp.instance, sourceItemId, fileId)
        ConnectionLog.log(
            TAG,
            "deferred attachment fileId=$fileId item=$sourceItemId " +
                "($mcType, ${fileSize}B, groupKey=$groupKey)",
        )
    }

    /**
     * Pull a previously-deferred attachment on explicit user tap. Wraps
     * `/freceive` (same flags as the auto path) and, on a positive accept,
     * raises the in-flight banner so the existing progress UI takes over;
     * completion then upserts the placeholder row via
     * [handleRcvFileComplete]. On a non-accept (expired invitation, or core
     * state lost across a cold restart) the deferred entry is dropped so the
     * bubble can degrade to "Unavailable" — user intent tried, file is gone.
     *
     * @return true when the core accepted the receive and a download started.
     */
    suspend fun receiveDeferredFile(fileId: Long, itemId: Long, peerKey: String, fileName: String?): Boolean {
        val resp = send("/freceive $fileId approved_relays=on encrypt=off")
        val ok = resp.contains("\"type\":\"rcvFileAccepted\"")
        ConnectionLog.log(TAG, "deferred /freceive $fileId item=$itemId ok=$ok " +
            (if (!ok) "→ ${resp.take(200)}" else ""))
        if (ok) {
            InFlightFiles.track(
                InFlightFiles.Entry(
                    fileId = fileId,
                    peerKey = peerKey,
                    fileName = fileName ?: "file",
                    totalBytes = null,
                    direction = InFlightFiles.Entry.Direction.Receiving,
                ),
            )
        } else {
            // Invitation can't be resurrected — stop offering the tap.
            app.aether.aegis.attachment.DeferredDownloads.remove(AegisApp.instance, itemId)
        }
        return ok
    }

    private suspend fun handleRcvFileComplete(resp: JSONObject) {
        // Drop the InFlightFiles entry the moment the core says the
        // download finished, so the chat banner stops surfacing a
        // Cancel button for a file that's already on disk.
        clearInFlightFile(resp)
        val aci = resp.optJSONObject("chatItem")
        if (aci == null) {
            ConnectionLog.warn(TAG, "rcvFileComplete missing chatItem: ${resp.toString().take(220)}")
            return
        }
        val info = aci.optJSONObject("chatInfo")
        if (info == null) {
            ConnectionLog.warn(TAG, "rcvFileComplete missing chatInfo: ${aci.toString().take(220)}")
            return
        }
        val infoType = info.optString("type")
        // Same direct-vs-group routing as handleNewChatItems. For
        // groups we resolve groupKey via the SimpleX→Aegis group map
        // so the attachment lands in the group's conversation.
        val groupKey: String? = when (infoType) {
            "direct" -> null
            "group" -> {
                val groupInfo = info.optJSONObject("groupInfo") ?: return
                val sgid = groupInfo.optLong("groupId", -1L).takeIf { it > 0 } ?: return
                val aegisGroup = runCatching {
                    AegisApp.instance.repository.getGroupBySimplexId(sgid)
                }.getOrNull() ?: return
                "group:${aegisGroup.id}"
            }
            else -> return
        }
        // Sender attribution. Skip groupSnd (our own reflected back)
        // to avoid double-record. For group senders we use the
        // localDisplayName as the fromKey, same as handleNewChatItems.
        val contactName: String
        val contactId: Long?
        val ci = aci.optJSONObject("chatItem") ?: return
        if (groupKey == null) {
            val contact = info.optJSONObject("contact") ?: return
            contactName = contact.optString("localDisplayName").ifBlank { return }
            contactId = contact.optLong("contactId", -1L).takeIf { it > 0 }
        } else {
            val chatDir = ci.optJSONObject("chatDir")
            if (chatDir?.optString("type") == "groupSnd") return
            val gm = chatDir?.optJSONObject("groupMember") ?: return
            // Same multi-field walk as handleNewChatItems — earlier
            // this returned at .ifBlank{} for localDisplayName, which
            // dropped EVERY group file attachment because that field
            // is the local alias (usually unset). User-visible: group
            // media never appeared in the conversation. Fall through
            // memberProfile fields before giving up.
            val profile = gm.optJSONObject("memberProfile")
            contactName = sequenceOf(
                gm.optString("localDisplayName"),
                profile?.optString("displayName").orEmpty(),
                profile?.optString("fullName").orEmpty(),
                gm.optString("memberId"),
            ).firstOrNull { it.isNotBlank() } ?: run {
                ConnectionLog.warn(
                    TAG,
                    "group file: no name on member — dropping ${ci.optJSONObject("meta")?.optLong("itemId")}",
                )
                return
            }
            contactId = null
        }
        val content = ci.optJSONObject("content") ?: return
        val msgContent = content.optJSONObject("msgContent") ?: return
        val mcType = msgContent.optString("type")
        val caption = msgContent.optString("text", "")

        val file = ci.optJSONObject("file") ?: return
        // Two distinct things the core hands us, easy to conflate:
        //   fileSource.filePath = where the download landed on disk (the core
        //       names it with its own handle — not for display).
        //   fileName            = the ORIGINAL name the sender attached, which
        //       is what we show and infer the MIME from.
        // Using filePath for BOTH showed a received "report.apk" as
        // "<uuid>.vnd.andr" with mime application/octet-stream (no viewer could
        // open it). Keep them separate; fall back across each other if one is
        // blank.
        val onDiskPath = file.optJSONObject("fileSource")?.optString("filePath").orEmpty()
        val displayName = file.optString("fileName").takeIf { it.isNotBlank() }
            ?: java.io.File(onDiskPath).name
        if (onDiskPath.isBlank() && displayName.isBlank()) return
        val fileSize = file.optLong("fileSize", 0)
        // MIME resolution: derive from the file extension so Android's
        // intent resolver can match a concrete viewer (jpeg → Gallery,
        // pdf → PDF viewer, mp4 → media player). Wildcards like
        // "image/*" don't fire opens for many viewers, and the old
        // catch-all "application/octet-stream" matched nothing useful —
        // that was the "can't open" report from the field.
        //
        // The msgContent type only tells us the bucket the SENDER
        // chose (upstream packs anything non-image into MCFile, even
        // PDFs and ZIPs), so we still use it as a fallback when the
        // file has no extension.
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val mimeFromExt = if (ext.isNotEmpty()) {
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        } else null
        val mime = mimeFromExt ?: when (mcType) {
            "image" -> "image/*"
            "video" -> "video/*"
            "voice" -> "audio/*"
            else    -> "application/octet-stream"
        }
        // Row type. There's no dedicated VIDEO MessageType, so video maps
        // to PHOTO — both are visual media the bubble renders by MIME
        // (attachMime "video/*" → VideoBubble), and PHOTO keeps it in the
        // visual-media bucket for list previews / future filtering. It used
        // to map to VOICE, which mislabeled videos as voice notes in the
        // notification + chat-list preview (the bubble was always right
        // because it keys off MIME, not this enum). voice stays VOICE.
        val msgType = when (mcType) {
            "image" -> MessageType.PHOTO
            "video" -> MessageType.PHOTO
            "voice" -> MessageType.VOICE
            else    -> MessageType.FILE
        }
        // Core saves received files into appFilesDir; if filePath is
        // already absolute leave it, else resolve against appFilesDir.
        // Phase 1 multi-profile: appFilesDir is now per-profile.
        // Locate the bytes on disk from the core's path (NOT the display
        // name); fall back to resolving the display name under the profile's
        // attachments dir when the core gave no explicit path.
        val rawForDisk = onDiskPath.ifBlank { displayName }
        val candidate = java.io.File(rawForDisk)
        val absPath = if (candidate.isAbsolute) candidate.absolutePath
        else java.io.File(app.aether.aegis.AegisApp.instance.profileRoot.attachmentsDir, candidate.name).absolutePath
        val absFile = java.io.File(absPath)
        ConnectionLog.log(
            TAG,
            "rcvFileComplete: groupKey=$groupKey path=$absPath exists=${absFile.exists()} " +
                "size=${if (absFile.exists()) absFile.length() else -1} mcType=$mcType",
        )

        val id = aegisIdFor(contactName)
        // Only bind for direct contacts — group members aren't peers
        // of ours and shouldn't enter the peerByKey map.
        if (groupKey == null && !peerByKey.containsKey(id)) {
            bindContact(contactName, contactId, contactName)
        }

        // Story interception: photos sent as stories use a caption tag
        // `[aegis:story:<id>]<text?>`. We treat those as ephemeral 24-h
        // posts in the stories table, not chat messages, so they don't
        // appear in the conversation view.
        val storyMatch = STORY_CAPTION.matchEntire(caption)
        if (storyMatch != null) {
            val storyId = storyMatch.groupValues[1]
            val storyText = storyMatch.groupValues[2]
            AegisApp.instance.repository.upsertStory(
                app.aether.aegis.data.StoryEntity(
                    id = storyId,
                    authorKey = id,
                    body = storyText,
                    attachmentPath = absPath,
                    attachmentMime = mime,
                    createdAt = System.currentTimeMillis(),
                    viewed = false,
                )
            )
            return
        }
        // Walkie-talkie interception: voice clip from a family member
        // during this device's own active sos. We auto-play through
        // the speakerphone immediately so the SOS sender hears
        // it even if the phone is in a pocket / face-down / locked.
        // Falls through to a regular voice message if sos isn't
        // active here (so the clip still lands in chat).
        // PTT (Push-to-Talk) routing. Caption shape:
        //   [aegis:ptt:<victimKey>] — multi-target responder channel;
        //   plays if I'm a responder for that victim, or if I AM that
        //   victim AND I've opted in.
        if (caption.startsWith("[aegis:ptt:")) {
            val victimKey = caption.removePrefix("[aegis:ptt:").removeSuffix("]")
            val selfKey = runCatching { AegisApp.instance.identity.deviceId }
                .getOrDefault("")
            val store = app.aether.aegis.sos.SOSAlertStore
            val responderForThisSOS = store
                .respondersFor(victimKey)
                .any { it.peerKey == selfKey }
            val amVictim = victimKey == selfKey
            val victimOptedIn = amVictim && runCatching {
                AegisApp.instance.sosHandler.state.value != null
            }.getOrDefault(false)
            // Auto-play if either:
            //   - I'm an opted-in responder for this sos, OR
            //   - I am the victim and I have an active sos
            //     (the opt-in toggle controls fan-out at the sender;
            //     by the time the clip arrives at me, the sender
            //     already decided I'm allowed to hear it).
            if (responderForThisSOS || victimOptedIn) {
                playPttClip(absPath)
            }
        }
        // Auto-play victim's sos-audio chunks on the receiver's
        // speaker (everyone hears the victim's audio). The player gates internally on
        // SOSAlertStore active+muted state so a clip arriving
        // after the user hit hold-to-mute is silently dropped.
        if (caption.startsWith("[aegis:sos-audio]")) {
            app.aether.aegis.sos.SOSAudioPlayer.enqueue(context, id, absPath)
        }
        // Periodic still-frame fan-out from the victim's camera. The
        // dashboard renders the latest frame inline so receivers see
        // visual context without tapping Accept on the WebRTC call.
        // Only honour during an active sos from this peer — drops
        // late frames from a sos that's already ended.
        //
        // CRITICAL: return AFTER updating the dashboard, BEFORE
        // recordReceivedAttachment. Otherwise every 5 s rear-camera
        // ping during a stuck sos clutters the chat with full-
        // resolution photos. The dashboard preview is the only
        // intended surface; the frame file lives only in cacheDir.
        // Match BOTH the legacy untagged "[aegis:sos-frame]" and the
        // lens-tagged "[aegis:sos-frame:front|rear]" (no closing bracket in
        // the prefix, so the ":lens" variants are caught too — otherwise the
        // tagged frames would fall through and clutter the chat with full-res
        // photos). Untagged = rear.
        if (caption.startsWith("[aegis:sos-frame")) {
            if (app.aether.aegis.sos.SOSAlertStore.isActive(id)) {
                val lens = if (caption.startsWith("[aegis:sos-frame:front]")) "front" else "rear"
                app.aether.aegis.sos.SOSAlertStore.setLatestSnapshot(id, absPath, lens)
            }
            return
        }

        // chatRef + itemId so Aegis can ask SimpleX to purge its own
        // mirror copy after the file is sealed at rest. chatRef
        // shape mirrors handleNewChatItems — "@<contactId>" for
        // direct chats, "#<simplexGroupId>" for groups.
        val purgeItemId = ci.optJSONObject("meta")?.optLong("itemId", -1L)?.takeIf { it > 0 }
        val purgeChatRef: String? = if (groupKey != null) {
            val sgid = groupKey.removePrefix("group:").let { aid ->
                runCatching { AegisApp.instance.repository.getGroup(aid)?.simplexGroupId }
                    .getOrNull()
            }
            sgid?.let { "#$it" }
        } else {
            contactId?.let { "@$it" }
        }
        AegisApp.instance.repository.recordReceivedAttachment(
            fromKey = id,
            caption = caption,
            attachmentPath = absPath,
            attachmentMime = mime,
            attachmentSize = fileSize,
            attachmentName = displayName,
            protocol = Protocol.SIMPLEX,
            type = msgType,
            peerKeyOverride = groupKey,
            sourceItemId = purgeItemId,
            sourceChatRef = purgeChatRef,
        )
        // If this completion fulfils a deferred (tap-to-download) placeholder,
        // drop its remembered fileId — the file is now on the row and the
        // tap affordance is done. No-op for files that auto-downloaded.
        if (purgeItemId != null) {
            app.aether.aegis.attachment.DeferredDownloads.remove(AegisApp.instance, purgeItemId)
        }
        if (purgeItemId != null && purgeChatRef != null) {
            val purged = runCatching { purgeOriginal(purgeChatRef, purgeItemId) }
                .onFailure {
                    ConnectionLog.warn(
                        TAG,
                        "attachment purgeOriginal $purgeChatRef $purgeItemId failed: $it",
                    )
                }.getOrDefault(false)
            if (purged) AegisApp.instance.repository.clearPendingPurge(purgeItemId)
        }
        // Notification: post directly to keep ProtocolManager out of
        // the loop (it'd otherwise write a duplicate text-only row).
        AegisApp.instance.let { aegisApp ->
            val name = runCatching { aegisApp.repository.knownPeerByKey(id) }
                .getOrNull()?.displayName ?: contactName
            // Notification body — plain string, no emoji prefix.
            // Vector icons can't be inlined into NotificationCompat
            // text and the user's "drop if can't be LunaGlass" rule
            // applies here too. The notification's small-icon stays
            // the LunaGlass shield, which carries the brand visual.
            // Derive the label from mcType (not msgType) so video reads as
            // "Video" — video shares the PHOTO row type, so msgType alone
            // can't tell them apart.
            val body = caption.ifBlank {
                when (mcType) {
                    "image" -> "Photo"
                    "video" -> "Video"
                    "voice" -> "Voice"
                    else    -> "File"
                }
            }
            // Tap → open the CONVERSATION the attachment landed in. For a
            // group attachment that's the group ("group:<uuid>"), NOT the
            // sender's 1:1 chat — routing to the sender's DM leaked who-is-in-
            // which-chat and dropped the user in the wrong place (user report).
            // Mirror of the text path's `msg.groupKey ?: msg.fromKey`.
            val openTarget = groupKey ?: id
            // Key the PendingIntent + notification id on the TARGET, so a group
            // attachment and a 1:1 message from the same sender don't collide
            // (FLAG_UPDATE_CURRENT would otherwise let one overwrite the
            // other's deep-link, since extras aren't part of PendingIntent
            // identity).
            val notifId = openTarget.hashCode()
            val intent = aegisApp.packageManager.getLaunchIntentForPackage(aegisApp.packageName)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("open_chat", openTarget)
            }
            val pi = intent?.let {
                android.app.PendingIntent.getActivity(
                    aegisApp, notifId, it,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            }
            val notif = androidx.core.app.NotificationCompat.Builder(aegisApp, AegisApp.CHANNEL_MESSAGES)
                .setContentTitle(name)
                .setContentText(body)
                .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_shield)
                .setColor(AegisApp.BRAND_CYAN_ARGB)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .apply { pi?.let { setContentIntent(it) } }
                .build()
            if (androidx.core.app.NotificationManagerCompat.from(aegisApp).areNotificationsEnabled()) {
                androidx.core.app.NotificationManagerCompat.from(aegisApp).notify(notifId, notif)
            }
        }
    }

    /**
     * Extract the invitation URI from a SimpleX core response.
     * The envelope and field names have churned across versions:
     *   v5      : {"resp": {..., "connReqInvitation": "simplex:/..."}}
     *   v6      : {"resp": {..., "connLinkInvitation": {"connFullLink": "..."}}}
     *   v6.x+   : {"result": {..., "connLinkInvitation": {"connFullLink": "..."}}}
     * And the link format itself can be either:
     *   - simplex:/invitation#/?v=...               (SimpleX "long" link)
     *   - https://simplex.chat/invitation#/?v=...   (current "short" link
     *     served by SimpleX's web shim, which the SimpleX aegisApp on the
     *     other side resolves natively)
     * We try every envelope, then every field, and finally fall back to
     * regex-scanning the raw JSON so a future schema tweak doesn't
     * break us silently.
     */
    private fun parseUriFromResponse(resp: String): String? = runCatching {
        val root = JSONObject(resp)
        val obj = root.optJSONObject("result")
            ?: root.optJSONObject("resp")
            ?: root.optJSONObject("chatResponse")
            ?: return@runCatching null

        // v5: plain string field
        obj.optString("connReqInvitation").takeIf { isInvitationLink(it) }
            ?.let { return@runCatching it }

        // v6+: nested object
        obj.optJSONObject("connLinkInvitation")?.let { link ->
            link.optString("connFullLink").takeIf { isInvitationLink(it) }
                ?.let { return@runCatching it }
            link.optString("connShortLink").takeIf { isInvitationLink(it) }
                ?.let { return@runCatching it }
        }

        // Last-ditch: any invitation link substring in the raw text.
        // Three link shapes the core may emit (and we now accept):
        //   simplex:/...                        — SimpleX long link
        //   https://simplex.chat/...            — official web shim
        //   https://<smpN>.simplex.im/[acig]#…  — preset-server short link
        //                                         (contact / conn / invite /
        //                                         group flavour).
        val match = Regex(
            """(simplex:|https://simplex\.chat|https://[A-Za-z0-9.-]+\.simplex\.im)/[^"\s\\]+"""
        ).find(resp)
        match?.value
    }.getOrNull()

    private fun isInvitationLink(s: String): Boolean {
        if (s.isBlank()) return false
        if (s.startsWith("simplex:")) return true
        if (s.startsWith("https://simplex.chat/")) return true
        // SimpleX preset-server short link, e.g.
        //   https://smp16.simplex.im/a#vsxz…
        // The [acig] segment after the slash distinguishes contact /
        // connection / invitation / group flavours; the core handles
        // all four.
        return SIMPLEX_SHORT_LINK_RE.containsMatchIn(s)
    }


    private data class ParsedContact(val name: String, val contactId: Long?)

    private fun parseContactFromResponse(resp: String): ParsedContact? = runCatching {
        val root = JSONObject(resp)
        val obj = root.optJSONObject("result")
            ?: root.optJSONObject("resp")
            ?: root.optJSONObject("chatResponse")
            ?: return@runCatching null
        val c = obj.optJSONObject("contact") ?: obj.optJSONObject("connection")
        val name = c?.optString("localDisplayName").orEmpty()
        if (name.isBlank()) return@runCatching null
        val cid = c?.optLong("contactId", -1L)?.takeIf { it > 0 }
        ParsedContact(name, cid)
    }.getOrNull()

    /** Active walkie-talkie player. Released + replaced when a new
     *  clip arrives so back-to-back PTT messages don't stack
     *  MediaPlayer instances. Off the JNI callback thread because
     *  MediaPlayer.prepare() blocks on disk I/O. */
    @Volatile private var pttPlayer: android.media.MediaPlayer? = null

    private fun playPttClip(absPath: String) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val am = context.getSystemService(android.content.Context.AUDIO_SERVICE)
                    as? android.media.AudioManager
                am?.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                am?.isSpeakerphoneOn = true

                // Tear down the previous PTT player (if any) before
                // starting the new one — otherwise back-to-back clips
                // stack and we leak MediaPlayer instances.
                pttPlayer?.let { prev ->
                    runCatching { if (prev.isPlaying) prev.stop() }
                    runCatching { prev.release() }
                }
                pttPlayer = android.media.MediaPlayer().apply {
                    setAudioStreamType(android.media.AudioManager.STREAM_VOICE_CALL)
                    setDataSource(absPath)
                    prepare()   // we're on IO now — blocking OK
                    setOnCompletionListener { mp ->
                        runCatching { mp.release() }
                        if (pttPlayer === mp) pttPlayer = null
                        am?.isSpeakerphoneOn = false
                        am?.mode = android.media.AudioManager.MODE_NORMAL
                    }
                    start()
                }
            }
        }
    }

    companion object {
        private const val TAG = "SimpleXTransport"
        private const val POLL_TIMEOUT_MS = 500
        /** Reserved reactor id for the local user in a message's reaction
         *  set. Peer reactors are stored under their aegis id (a pubkey),
         *  which can never collide with this sentinel — so the UI can
         *  always tell "did I react?" by membership of this value. */
        const val REACTOR_SELF = "me"
        /** Notification id for the "group join didn't go through"
         *  toast — unique within Aegis's id range, distinct from the
         *  sonar / sos / message ids. */
        private const val NOTIF_GROUP_JOIN_TIMEOUT = 5800

        /** SimpleX preset-server short link, e.g.
         *  `https://smp16.simplex.im/a#vsxz…`. The [acig] segment
         *  distinguishes contact / connection / invitation / group
         *  flavours; the core handles all four. */
        private val SIMPLEX_SHORT_LINK_RE: Regex =
            Regex("""^https://[A-Za-z0-9.-]+\.simplex\.im/[acig]#\S+""", RegexOption.IGNORE_CASE)

        /** Caption shape for photo stories: `[aegis:story:<id>]<text>`.
         *  See AegisApp.handleInboundStory for the text-only variant. */
        private val STORY_CAPTION = Regex("""\[aegis:story:([^]]+)](.*)""", RegexOption.DOT_MATCHES_ALL)

        /**
         * Inspect an inbound text body for a control-class tag and
         * return (re-tagged MessageType, body-with-prefix-stripped).
         * Tags must match the prefixes that senders emit (see
         * ProtocolService.broadcastLocationToFamily etc.).
         */
        internal fun classifyInbound(body: String): Pair<MessageType, String> {
            return when {
                body.startsWith("[aegis:location]") ->
                    MessageType.LOCATION to body.removePrefix("[aegis:location]")
                body.startsWith("[aegis:status]") ->
                    MessageType.STATUS to body.removePrefix("[aegis:status]")
                body.startsWith("[aegis:sim-swap]") ->
                    // Sub-tag inside STATUS — receiver checks the body
                    // prefix to differentiate (see handleInboundStatus).
                    MessageType.STATUS to body
                body.startsWith("[aegis:geofence]") ->
                    MessageType.STATUS to body
                body.startsWith("[aegis:remote]") ->
                    // Auth-gated remote-access surface.
                    // Body is a JSON envelope; RemoteAccessHandler
                    // dispatches inbound and routes responses through
                    // RemoteAccessSession on the sender side.
                    MessageType.STATUS to body
                body.startsWith("[aegis:wiped]") ->
                    // Target broadcasts this to every paired contact
                    // immediately before factory-resetting itself.
                    // Receiver surfaces a high-
                    // priority "this peer wiped, re-invite to reconnect"
                    // notification — see AegisApp.handleInboundStatus.
                    MessageType.STATUS to body
                body.startsWith("[aegis:typing]") ->
                    // Sender-typing ping — receiver flips a per-peer
                    // 5 s expiration in TypingTracker. Not stored.
                    MessageType.STATUS to body
                body.startsWith("[aegis:tier]") ->
                    // Shield-tier announcement — body shape
                    // `[aegis:tier]<TIER_NAME>` where TIER_NAME is
                    // ShieldTier.name(). Receiver stores in
                    // known_peers.peerReportedTier; ChatList renders
                    // the avatar frame in that tier's colour. Kept
                    // wrapped so the handler can re-parse out the
                    // tier name.
                    MessageType.STATUS to body
                body.startsWith("[aegis:crown]") ->
                    // Crown-shimmer-style announcement — body shape
                    // `[aegis:crown]<n>` (0 glow / 1 rainbow / 2 oil-slick).
                    // Receiver stores in known_peers.peerReportedCrownStyle so
                    // the peer's Cyan medal renders in the style THEY chose, not
                    // the viewer's. Same handling family as `[aegis:tier]`.
                    MessageType.STATUS to body
                body.startsWith("[aegis:hello]") ->
                    // Capability-bootstrap envelope. Sent ungated by
                    // both sides after pairing — receipt flips
                    // known_peers.isAegis for the sender via the
                    // catch-all `[aegis:*]` detector above, which is
                    // the entire point of the message. No further
                    // action needed on this side; STATUS keeps it out
                    // of the chat history.
                    MessageType.STATUS to body
                body.startsWith("[aegis:identity]") ->
                    // Aegis Protocol identity overlay.
                    // Body shape `[aegis:identity]{json}` carrying the
                    // sender's REAL name/bio/avatar, sent only to a contact
                    // they elevated to Trusted/Emergency. STATUS-class so it
                    // never lands in chat history; AegisApp.handleInboundStatus
                    // parses the JSON and writes announcedName/Bio/Avatar.
                    MessageType.STATUS to body
                body.startsWith("[aegis:sos]") ->
                    MessageType.SOS to body.removePrefix("[aegis:sos]")
                // SOS coordination envelopes.
                // STATUS-class so they stay out of chat history. The
                // dispatcher in AegisApp.handleInboundStatus reads the
                // body prefix to route into SOSAlertStore.
                body.startsWith("[aegis:sos-roster]") ->
                    MessageType.STATUS to body
                body.startsWith("[aegis:sos-response:") ->
                    MessageType.STATUS to body
                body.startsWith("[aegis:sos-responder-loc]") ->
                    MessageType.STATUS to body
                body.startsWith("[aegis:sos-coord]") ->
                    MessageType.STATUS to body
                body.startsWith("[aegis:sos-victim-voice]") ->
                    MessageType.STATUS to body
                body.startsWith("[aegis:sos-distance]") ->
                    MessageType.STATUS to body
                body.startsWith("[aegis:sos-closest]") ->
                    MessageType.STATUS to body
                body.startsWith("[aegis:sos-arrived]") ->
                    MessageType.STATUS to body
                body.startsWith("[aegis:read:") ->
                    // Read receipt — body shape `[aegis:read:<id>]`,
                    // matched by AegisApp.AEGIS_READ_RECEIPT regex.
                    // Keep the full body so the handler can parse it.
                    MessageType.STATUS to body
                body.startsWith("[aegis:delivered:") ->
                    // Delivery confirmation (Aegis Protocol) — body shape
                    // `[aegis:delivered:<id>]`. The recipient sends this the
                    // instant the message reaches its device, before the
                    // at-rest seal; the sender's handler flips the row to
                    // 'delivered' (single bright tick). STATUS so it never
                    // appears in chat history.
                    MessageType.STATUS to body
                body.startsWith("[aegis:sealed:") ->
                    // Seal confirmation (transactional delivery) — body
                    // shape `[aegis:sealed:<id>]`. The recipient sends this
                    // after sealing our message at rest; the sender's handler
                    // flips the row to 'sealed' (bright + dim ✓✓). STATUS
                    // so it never appears in chat history.
                    MessageType.STATUS to body
                body.startsWith("[aegis:story]") ->
                    MessageType.STORY to body.removePrefix("[aegis:story]")
                body.startsWith("[aegis:burn:") ->
                    // Burn-after-reading. Marker
                    // shape: `[aegis:burn:<ttl>:<senderMsgId>]<text>`.
                    // The classifier just tags the type as BURN; the
                    // ProtocolService inbound path parses out the TTL
                    // and sender msgId. Keep the marker on the body
                    // so downstream can read it.
                    MessageType.BURN to body
                body.startsWith("[aegis:burn-receipt:") ->
                    // Recipient finished viewing a burn message.
                    // Sender's handler reads the embedded id and
                    // deletes the local row so it disappears on
                    // both devices. STATUS-class so it's filtered
                    // out of chat history.
                    MessageType.STATUS to body
                body.startsWith(
                    app.aether.aegis.achievements.AchievementBroadcaster.ENVELOPE,
                ) ->
                    // Verified-security badge set shared by a trusted
                    // contact — `[aegis:badges]<id>,<id>,…`. STATUS-class
                    // so it routes to AegisApp.handleInboundStatus (which
                    // feeds PeerBadgeStore) and never shows as a chat
                    // bubble. Was missing here, so the envelope fell to
                    // the TEXT default below and leaked its raw marker
                    // text into the conversation.
                    MessageType.STATUS to body
                // Defense-in-depth: ANY remaining `[aegis:…]`-prefixed
                // body is an Aegis control envelope — either one this
                // build doesn't handle, or a newer tag a peer on a later
                // version emitted. It must never render as a chat bubble.
                // Classify it as STATUS (the hidden control channel): the
                // dispatcher in handleInboundStatus no-ops on tags it
                // doesn't recognise, so an unknown control message is
                // silently dropped instead of leaking "[aegis:foo]…" text
                // into the user's conversation. Only a body with NO
                // `[aegis:` prefix is a genuine user TEXT message.
                body.startsWith("[aegis:") -> MessageType.STATUS to body
                else -> MessageType.TEXT to body
            }
        }
    }
}

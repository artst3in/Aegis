package app.aether.aegis.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.glassSheen
import app.aether.aegis.ui.components.glassEdgeLight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.AegisApp
import app.aether.aegis.core.Message
import app.aether.aegis.core.MessageType
import app.aether.aegis.core.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================
// CHAT
// ============================================================

/**
 * Per-peer watermark of the highest inbound simplexItemId we've already
 * advertised as "read" to that peer. Lets the live ChatScreen effect
 * re-fire on every messages-list change without re-sending the same read
 * receipt.
 *
 * PERSISTED (SharedPreferences): the in-memory-only version reset to 0 on
 * every process start, and combined with the foreground gate that meant a
 * message read just before the app was killed never got its receipt re-sent
 * in a way that advanced anything — the sender's tick stayed at 'sealed'.
 * Persisting the high-water mark lets the live path stay quiet across
 * restarts while the foreground catch-up (see ChatScreen) handles recovery.
 */
private object ReadReceiptWatermarks {
    private const val STORE = "aegis_read_receipt_watermarks"
    private fun prefs() =
        AegisApp.instance.getSharedPreferences(STORE, android.content.Context.MODE_PRIVATE)
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>(
        prefs().all.entries.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap(),
    )
    fun get(peerKey: String): Long = seen[peerKey] ?: 0L
    fun set(peerKey: String, itemId: Long) {
        seen[peerKey] = itemId
        prefs().edit().putLong(peerKey, itemId).apply()
    }
}

/**
 * Advertise "read up to the latest inbound item" to [peerKey]. Always stamps
 * the local unread-dot watermark ([ReadStore]); emits an `[aegis:read]` /
 * `[aegis:readdna]` receipt ONLY when there is genuinely something new to ack.
 *
 * Unread-only — attention-leak defense (SPEC_LOSSY_LINK_RESILIENCE,
 * ATTENTION_LEAK). This used to re-send the read receipt on every chat-open
 * ("force") as a resilience measure against a dropped STATUS packet. That blind
 * re-send is exactly the attention leak: a malicious peer could withhold a tick
 * and then measure how often the victim opens the chat (each open pinged back).
 * Opening a fully-read chat must generate ZERO outbound traffic. The
 * dropped-receipt resilience it provided is replaced by fact-based
 * reconciliation: on an UNREAD open we both send the read receipt AND fire
 * [ProtocolManager.requestReceiptReconciliation], which heals any stuck tick
 * without ever pinging on an already-read chat.
 */
private suspend fun advertiseReadUpToLatest(
    peerKey: String,
    newestMsgTs: Long,
) {
    val aegisApp = AegisApp.instance
    app.aether.aegis.prefs.ReadStore.markRead(
        peerKey,
        maxOf(System.currentTimeMillis(), newestMsgTs),
    )
    val maxItemId = aegisApp.repository.maxInboundItemId(peerKey)
    // Highest inbound DNA — present when this peer is an Aegis client sending
    // x.aegis.chat. Drives the cross-device-correct read tick.
    val maxDna = aegisApp.repository.maxInboundDna(peerKey)
    if (maxItemId == null && maxDna == null) return
    // Unread-only dedup (NO force bypass — attention-leak defense). If we've
    // already acked up to the newest inbound item, this open has nothing new to
    // report → return silently, so re-opening a fully-read chat emits zero
    // traffic and can't be used to measure how often the user looks at it.
    if (maxItemId != null && maxItemId <= ReadReceiptWatermarks.get(peerKey)) return
    if (maxItemId != null) ReadReceiptWatermarks.set(peerKey, maxItemId)
    runCatching {
        // DNA receipt marks DNA-bearing (Aegis) messages by the shared
        // identity; the itemId receipt marks any legacy/vanilla ones. Both fire
        // for a mixed conversation — the sender's markers are partitioned on
        // messageDna so they touch disjoint rows.
        maxDna?.let { dna ->
            aegisApp.protocolManager.sendMessage(
                to = peerKey,
                content = "[aegis:readdna:$dna]",
                type = MessageType.STATUS,
            )
        }
        maxItemId?.let { itemId ->
            aegisApp.protocolManager.sendMessage(
                to = peerKey,
                content = "[aegis:read:$itemId]",
                type = MessageType.STATUS,
            )
        }
    }
    // Reconciliation trigger #3 (SPEC_LOSSY_LINK_RESILIENCE): we only reach here
    // on a genuinely UNREAD open (the dedup above returned otherwise), the exact
    // moment the spec allows a reconciliation ping — it rides the read receipt
    // we're already sending, so a fully-read chat still pings nothing.
    aegisApp.protocolManager.requestReceiptReconciliation(peerKey)
}

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
@Composable
fun ChatScreen(memberId: String, navController: NavController) {
    val selfKey = AegisApp.instance.identity.deviceId
    val context = LocalContext.current
    // No early decoy bail-out: ChatScreen renders the same UI in real
    // + duress, and the message-source substitution happens inline
    // below via inDuress + isDecoyKey.
    // Resolve the contact name: KnownPeer is the source of truth for
    // paired peers. The peerKey itself is a usable last resort while
    // the KnownPeer row is still loading.
    var displayName by remember(memberId, selfKey) {
        mutableStateOf(
            when {
                memberId == selfKey -> "You (this device)"
                memberId.startsWith("simplex:") -> memberId.removePrefix("simplex:")
                else -> memberId.take(12)
            }
        )
    }
    LaunchedEffect(memberId) {
        if (memberId == selfKey) return@LaunchedEffect
        val resolved = AegisApp.instance.repository.knownPeerByKey(memberId)?.displayName
        if (!resolved.isNullOrBlank()) displayName = resolved
    }
    var messageText by remember { mutableStateOf(app.aether.aegis.prefs.DraftStore.get(memberId)) }
    // Persist the draft on every change so it survives leaving the chat
    // window; blanking the field on send clears it automatically.
    // (user-reported: "if you don't send before leaving, it's gone")
    LaunchedEffect(memberId) {
        androidx.compose.runtime.snapshotFlow { messageText }
            .collect { app.aether.aegis.prefs.DraftStore.set(memberId, it) }
    }
    var replyingTo by remember { mutableStateOf<Message?>(null) }
    // Chat-list scroll state — hoisted to the screen scope so the
    // send handlers can scroll-to-newest after a reply lands, and
    // the floating "jump to latest" hex can read firstVisibleItemIndex.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var editing by remember { mutableStateOf<Message?>(null) }
    var forwardCandidate by remember { mutableStateOf<Message?>(null) }
    var chatMenuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var ttlDialogOpen by remember { mutableStateOf(false) }
    // Compose-bar redesign: + drawer slides up; Schedule
    // moved into the chat overflow menu to keep the bar at 3 elements.
    var attachDrawerOpen by remember { mutableStateOf(false) }
    var scheduleOpen by remember { mutableStateOf(false) }
    // Burn-after-reading. pendingBurnTtl is null when
    // burn isn't armed; non-null is the TTL seconds (0 = unlimited
    // until close) the next send will use.
    var burnTtlPickerOpen by remember { mutableStateOf(false) }
    var pendingBurnTtl by remember { mutableStateOf<Int?>(null) }
    // Active burn viewer — set to the message being revealed; cleared
    // on close (after we fire the receipt + delete the local row).
    var burnViewing by remember { mutableStateOf<Message?>(null) }
    val chatScope = rememberCoroutineScope()
    val startCall = app.aether.aegis.call.rememberCallStarter(navController)
    // Staged attachment — set when the user picks a file, cleared on
    // send or via the X on the preview chip. Lets users add a caption
    // before committing instead of firing instantly on pick.
    var pendingAttachment by remember { mutableStateOf<app.aether.aegis.util.Attachments.Local?>(null) }
    // Queue of additional attachments waiting for their own preview +
    // caption pass. Multi-pick from the file drawer now stages the
    // first item in pendingAttachment and drops the rest here; the
    // Send tap consumes the staged one and pulls the next off the
    // queue so each gets its own confirmation.
    var pendingAttachmentQueue by remember {
        mutableStateOf<List<app.aether.aegis.util.Attachments.Local>>(emptyList())
    }
    val realMessages by AegisApp.instance.repository
        .conversation(memberId)
        .collectAsState(initial = emptyList())
    // Duress mode: if this is a decoy peer pubkey, substitute the
    // fake conversation. We keep a local list of "outgoing fakes" the
    // attacker has typed in this session so their messages appear as
    // sent (and immediately ✓✓-read) — feels real, leaks nothing.
    val inDuress = AegisApp.instance.lockState.inDuressMode
    val decoySent = remember { mutableStateListOf<app.aether.aegis.core.Message>() }
    val messagesRaw = if (inDuress && app.aether.aegis.decoy.DecoyFixtures.isDecoyKey(memberId)) {
        app.aether.aegis.decoy.DecoyFixtures.conversation(memberId) + decoySent
    } else realMessages
    // Resolve DNA reply-links to the quoted message's LOCAL itemId, then fill
    // replyToItemId — so the whole existing citation machinery (jump, "↳ N
    // replies" grouping, nested-quote chain) works unchanged for Aegis envelope
    // replies, which carry replyToDna instead of a native quote. A no-op when
    // no message has a DNA (vanilla/legacy conversation).
    val messages = remember(messagesRaw) {
        val dnaToItemId = messagesRaw.asSequence()
            .filter { it.messageDna != null && it.simplexItemId != null }
            .associate { it.messageDna!! to it.simplexItemId!! }
        if (dnaToItemId.isEmpty()) messagesRaw
        else messagesRaw.map { m ->
            if (m.replyToItemId == null && m.replyToDna != null) {
                dnaToItemId[m.replyToDna]?.let { m.copy(replyToItemId = it) } ?: m
            } else m
        }
    }

    // Foreground gate for read receipts. A paused activity keeps this
    // screen composed, so messages.size still changes when a message lands
    // while the phone is locked / app backgrounded — which fired a read
    // receipt even though the user only saw a notification. Track the
    // RESUMED state so "read" means the chat was actually in front of them.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isResumed by remember {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        )
    }
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    isResumed = true
                    // Mark THIS chat foregrounded so its inbound messages
                    // don't also fire a notification (redundant — you're
                    // looking at it).
                    app.aether.aegis.chat.ActiveChat.key = memberId
                    // Clear any already-posted notifications for this
                    // conversation — opening the chat IS reading them, so
                    // they shouldn't linger in the shade. notifyMessage
                    // keys message notifications to the conversation
                    // (openTarget == memberId here), so this id matches.
                    androidx.core.app.NotificationManagerCompat.from(context)
                        .cancel(memberId.hashCode())
                    // Reading the chat clears its unread badge.
                    app.aether.aegis.prefs.ReadStore.markRead(memberId)
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    isResumed = false
                    if (app.aether.aegis.chat.ActiveChat.key == memberId) {
                        app.aether.aegis.chat.ActiveChat.key = null
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            if (app.aether.aegis.chat.ActiveChat.key == memberId) {
                app.aether.aegis.chat.ActiveChat.key = null
            }
        }
    }

    // Read receipts. The peer's AegisApp.handleInboundStatus maps an inbound
    // `[aegis:read:<maxInboundItemId>]` to markReadUpTo, flipping their
    // outgoing rows to 'read' (bright + bright ✓✓). Two triggers, both gated
    // on the chat genuinely being on screen (isResumed) so we never leak
    // "read" from a background notification. Both go through the unread-only
    // advertiseReadUpToLatest — a fully-read chat acks NOTHING (attention-leak
    // defense), and the dropped-receipt recovery that a blind re-send used to
    // provide is now handled by reconciliation fired on the unread path:
    //
    //   (a) FOREGROUND catch-up — keyed on isResumed alone, so it fires every
    //       time the chat returns to the foreground, acking anything that
    //       arrived while we were backgrounded. Silent if nothing new.
    //
    //   (b) LIVE — keyed on messages.size, so each message that lands while
    //       the chat is open gets acked promptly. Watermark-gated so a busy
    //       chat doesn't re-confirm the same id. Not keyed on isResumed, so it
    //       doesn't double-fire with (a) on resume.
    LaunchedEffect(memberId, isResumed) {
        if (memberId == selfKey || !isResumed || messages.isEmpty()) return@LaunchedEffect
        advertiseReadUpToLatest(
            peerKey = memberId,
            newestMsgTs = messages.maxOfOrNull { it.timestamp } ?: 0L,
        )
    }
    LaunchedEffect(memberId, messages.size) {
        if (memberId == selfKey || !isResumed || messages.isEmpty()) return@LaunchedEffect
        advertiseReadUpToLatest(
            peerKey = memberId,
            newestMsgTs = messages.maxOfOrNull { it.timestamp } ?: 0L,
        )
    }

    // Presence on chat-open — "give, don't ask". Opening (or returning to)
    // a conversation pushes YOUR fresh status + last-known location to your
    // Trusted contacts. Combined with the on-send push, this means a contact
    // who merely READS our chat without replying still broadcasts their live
    // battery/GPS to us — closing the "he didn't reply so I never got his
    // update until the next ticker" gap. Debounced + Trusted-only inside the
    // service; skipped for self-chat.
    LaunchedEffect(memberId) {
        if (memberId != selfKey) {
            app.aether.aegis.services.ProtocolService.requestActivityPresenceRefresh()
        }
    }

    val peerEntity = remember(memberId) {
        // Cached on first read; refreshed when scope changes.
        null as app.aether.aegis.data.KnownPeerEntity?
    }
    val peers by AegisApp.instance.repository.observeKnownPeers().collectAsState(initial = emptyList())
    val statusRow by AegisApp.instance.repository.observeStatus(memberId).collectAsState(initial = null)
    // Three-state presence. The chat
    // header used to be binary (online OR offline) which meant a
    // background-alive peer like Zippy read as "offline" here even
    // though the radar tile correctly showed her as Away.
    val presence = remember(statusRow) {
        app.aether.aegis.ui.components.peerStatusFor(statusRow, System.currentTimeMillis())
    }
    val isOnline = presence == app.aether.aegis.ui.components.PeerStatus.Online

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // LunaGlass chat header: back arrow + 34dp hex avatar + name +
        // "online · SimpleX". Border-bottom 1dp AegisBorder.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                // Own the status-bar inset here. The hosting Scaffold
                // runs contentWindowInsets = 0 (each bar owns its own
                // inset, so tab + sub routes don't double-pad), which
                // means a sub-route like this one gets innerPadding.top
                // = 0 — nothing reserves the notification-bar strip for
                // us. Without this the header collides with the system
                // clock / status icons. Order matters: statusBarsPadding
                // sits AFTER background so the surface still fills behind
                // the status bar (solid chrome, nothing bleeds through),
                // while the back-arrow / avatar / name row is pushed
                // clear of the notification bar.
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "←",
                color = app.aether.aegis.ui.theme.AegisCyan,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { navController.popBackStack() },
            )
            Spacer(modifier = Modifier.width(12.dp))
            // Canonical avatar — same metal-frame + engraved-plate renderer as
            // the chat list / grid / radar / contact detail, so the header and
            // the list row stay visually consistent.
            val peerForFrame = peers.firstOrNull { it.publicKey == memberId }
            val reportedTierName = peerForFrame?.peerReportedTier
            val reportedTier = remember(reportedTierName) {
                runCatching { app.aether.aegis.admin.ShieldTier.valueOf(reportedTierName.orEmpty()) }
                    .getOrNull()
            }
            // Trust gate (2026.06 security fix): only a TRUSTED peer's announced
            // avatar is rendered — never a stranger's self-supplied image
            // (impersonation + leak vector). Fail closed → monogram otherwise.
            val peerTrusted = peerForFrame?.trustTier
                ?.let { runCatching { app.aether.aegis.data.TrustTier.valueOf(it) }.getOrNull() } ==
                app.aether.aegis.data.TrustTier.TRUSTED
            val headerAvatarPath = peerForFrame?.announcedAvatarPath?.takeIf { peerTrusted }
            app.aether.aegis.ui.components.AegisAvatar(
                size = 34.dp,
                tier = reportedTier,
                initial = displayName.take(1).uppercase(),
                avatarPath = headerAvatarPath,
                online = isOnline,
                onClick = {
                    val encoded = java.net.URLEncoder.encode(memberId, "UTF-8")
                    navController.navigate("contact/$encoded")
                },
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                // Tick once per second so the typing indicator can
                // expire automatically when the peer pauses for >5 s.
                var now by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(memberId) {
                    while (true) {
                        kotlinx.coroutines.delay(1_000)
                        now = System.currentTimeMillis()
                    }
                }
                val typing = remember(memberId, now) {
                    app.aether.aegis.chat.TypingTracker.isTyping(memberId)
                }
                // Peer-local-time append was removed 2026.05.632 —
                // showing the contact's wall-clock derived from their
                // longitude was more confusing than useful next to
                // a "last seen" / presence stamp that's in the
                // viewer's own time. All time displays now stay in
                // the local user's frame.
                val presenceLabel = when (presence) {
                    app.aether.aegis.ui.components.PeerStatus.Online -> "online · SimpleX"
                    app.aether.aegis.ui.components.PeerStatus.Away -> "away · SimpleX"
                    app.aether.aegis.ui.components.PeerStatus.Offline -> "offline · SimpleX"
                }
                val statusLine = when {
                    typing -> "typing…"
                    else -> presenceLabel
                }
                Text(
                    statusLine,
                    color = when {
                        typing -> app.aether.aegis.ui.theme.AegisCyan
                        presence == app.aether.aegis.ui.components.PeerStatus.Online -> app.aether.aegis.ui.theme.AegisOnline
                        presence == app.aether.aegis.ui.components.PeerStatus.Away -> app.aether.aegis.ui.theme.AegisWarning
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 11.sp,
                )
            }
            // Call icons + overflow menu — kept inline rather than in a
            // TopAppBar so the LunaGlass header layout owns the row.
            // Long-press to call (hold-to-execute) so
            // a stray tap while scrolling doesn't accidentally ring
            // someone. Tap shows a teaching toast — both prevents
            // accidents AND makes the button's purpose discoverable.
            // Video left, voice right — convention every messenger
            // (WhatsApp, Signal, Telegram, iMessage) uses. Used to
            // be voice-then-video and the muscle memory kept misfiring.
            CallActionIcon(
                icon = app.aether.aegis.ui.components.AegisIcons.Play,
                label = stringResource(R.string.call_video),
                onFire = { startCall(memberId, displayName, true) },
            )
            Spacer(modifier = Modifier.width(6.dp))
            CallActionIcon(
                icon = app.aether.aegis.ui.components.AegisIcons.Call,
                label = stringResource(R.string.call_voice),
                onFire = { startCall(memberId, displayName, false) },
            )
            Spacer(modifier = Modifier.width(2.dp))
            Box {
                IconButton(onClick = { chatMenuOpen = true }) {
                    AegisIcon(app.aether.aegis.ui.components.AegisIcons.More, "More", tint = app.aether.aegis.ui.theme.AegisCyan)
                }
                DropdownMenu(
                    expanded = chatMenuOpen,
                    onDismissRequest = { chatMenuOpen = false },
                ) {
                    // Help + Notes reachable from
                    // every screen. Chat's header row is already
                    // crowded with call icons, so the overflow menu
                    // carries them. Two taps, always available.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.help_help)) },
                        onClick = {
                            chatMenuOpen = false
                            navController.navigate("help")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.secure_notes_vault)) },
                        onClick = {
                            chatMenuOpen = false
                            navController.navigate("notes")
                        },
                    )
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_schedule_message)) },
                        onClick = {
                            chatMenuOpen = false
                            // Only open the schedule picker if there's
                            // something to send — empty drafts can't
                            // be scheduled.
                            if (messageText.isNotBlank()) scheduleOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_clear)) },
                        onClick = {
                            chatMenuOpen = false
                            confirmClear = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_disappearing_messages)) },
                        onClick = {
                            chatMenuOpen = false
                            ttlDialogOpen = true
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = app.aether.aegis.ui.theme.AegisBorder, thickness = 1.dp)

        // Tier-aware permanent banner (trust model):
        // Emergency contacts get the "sos alerts only" reminder so
        // the user is less likely to send casual chatter into a chat
        // they meant as a one-way alarm channel.
        val currentPeer = peers.firstOrNull { it.publicKey == memberId }
        val currentTier = currentPeer?.trustTier
            ?.let { runCatching { app.aether.aegis.data.TrustTier.valueOf(it) }.getOrNull() }
        if (currentTier == app.aether.aegis.data.TrustTier.EMERGENCY) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(app.aether.aegis.ui.theme.AegisSOS.copy(alpha = 0.08f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.chat_emergency_contact_they_receive),
                    fontSize = 11.sp,
                    color = app.aether.aegis.ui.theme.AegisSOS,
                )
            }
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(stringResource(R.string.chat_clear_confirm)) },
                text = { Text(stringResource(R.string.chat_deletes_every_message_in)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        chatScope.launch {
                            AegisApp.instance.repository.clearConversation(memberId)
                        }
                    }) { Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }

        forwardCandidate?.let { fwd ->
            ForwardDialog(
                message = fwd,
                onDismiss = { forwardCandidate = null },
                onPicked = { targetKey ->
                    forwardCandidate = null
                    chatScope.launch { forwardMessage(targetKey, fwd) }
                },
            )
        }

        // Date-separated rows (Today / Yesterday / Monday / 22 May / 22 May 2024).
        // Sealed-class mix so LazyColumn can render either a sticky-ish
        // day header or a message bubble at each index.
        // Pinned banner — sticky strip above the message list showing
        // the most-recent pinned message for this peer. Tap → scroll
        // to it. Long-press pin/unpin lives on the message bubble menu.
        val pinned by AegisApp.instance.repository
            .observePinnedMessages(memberId)
            .collectAsState(initial = emptyList())
        val topPinned = pinned.firstOrNull()
        if (topPinned != null) {
            Surface(
                color = app.aether.aegis.ui.theme.AegisSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📌", fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.chat_pinned),
                            fontSize = 10.sp,
                            color = app.aether.aegis.ui.theme.AegisCyan,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            topPinned.content.take(160),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    if (pinned.size > 1) {
                        Text(
                            "+${pinned.size - 1}",
                            fontSize = 11.sp,
                            color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                        )
                    }
                }
            }
        }

        val chatRows = remember(messages) {
            val out = mutableListOf<ChatRow>()
            // Track the day as a CHEAP local-epoch-day bucket, not the formatted
            // label. dayLabel() allocates three Calendars (+ a SimpleDateFormat)
            // per call; calling it per-message made this O(n)-expensive grouping
            // re-run on every status tick (receipt traffic), which janked the
            // scroll. Now the per-message cost is one arithmetic op and dayLabel
            // fires only when the day actually changes — once per separator.
            var lastDayBucket = Long.MIN_VALUE
            for (m in messages) {  // oldest → newest
                // Hide Aegis control / evidence envelopes from the chat.
                // These are real DB rows (the SOS dashboard reads the
                // `[aegis:sos-audio]` / `[aegis:sos-frame]` attachment
                // rows to build its audio + camera streams, so we can't
                // drop them from the conversation query) but they must
                // NOT render as chat bubbles — during an SOS the
                // outbound audio-chunk + frame fan-out was leaking raw
                // "[aegis:sos-audio] …" lines into the victim's own
                // conversation. Inbound control is already routed away as
                // STATUS by classifyInbound; this catches the SENDER-side
                // copies (and any attachment-caption envelope) the text
                // classifier never sees.
                // EXCEPTION: an INCOMING SOS voice recording is un-hidden
                // so the recipient gets it as a playable voice note (user
                // request). The SOS dashboard still finds these rows by the
                // [aegis:sos-audio] prefix — we only reveal them in the chat
                // view. Inbound only: the victim's OWN outgoing chunks (one
                // every ~60 s during an SOS) would flood their conversation,
                // which is exactly the clutter this filter was added to stop.
                if (m.content.startsWith("[aegis:")) {
                    val inboundSOSVoice = m.content.startsWith("[aegis:sos-audio]") &&
                        m.from != selfKey &&
                        m.attachmentMime?.startsWith("audio/") == true
                    if (!inboundSOSVoice) continue
                }
                val bucket = localEpochDay(m.timestamp)
                if (bucket != lastDayBucket) {
                    out += ChatRow.Sep(dayLabel(m.timestamp))
                    lastDayBucket = bucket
                }
                out += ChatRow.Msg(m)
            }
            out
        }

        // Auto-scroll to newest whenever an OUTGOING message lands —
        // covers reply, attachment, plain send, every path. We don't
        // Auto-scroll rules:
        //   - Always scroll to the newest on outgoing — we know the
        //     user just hit Send.
        //   - Scroll to newest on incoming when the user was at (or
        //     near) the bottom of the list.
        //
        // Why tolerance 5: a new message prepends at data index 0
        // (reverseLayout=true means index 0 is the visual bottom).
        // The user who was previously AT index 0 is now at index 1
        // by the time this LaunchedEffect reads firstVisibleItemIndex
        // — Compose has already remeasured. A burst of N messages
        // shifts them N positions. Treating ≤ 5 as "still at the
        // bottom" covers a sane burst window without yanking
        // someone genuinely 20+ messages back.
        val newestId = messages.lastOrNull()?.id
        LaunchedEffect(newestId) {
            val newest = messages.lastOrNull() ?: return@LaunchedEffect
            val outgoingNew = newest.from == selfKey
            val nearBottom = listState.firstVisibleItemIndex <= 5
            if (outgoingNew || nearBottom) {
                runCatching { listState.animateScrollToItem(0) }
            }
        }

        // ---- Citation-chase navigation ----
        // Tapping a reply's quote jumps to the original message (flashing it)
        // and pushes the spot you left onto a back-stack; ↩ pops one level so
        // you can read context at every hop and unwind it. The stack dies the
        // instant your viewport crosses back NEWER than the entry anchor
        // (its bottom) — whether by popping to empty, hitting ↓, or
        // free-scrolling past it. Anchors are message ids, resolved to a
        // scroll index at use-time so messages arriving mid-chase can't
        // misalign it.
        val citationStack = remember { androidx.compose.runtime.mutableStateListOf<String>() }
        var flashMsgId by remember { mutableStateOf<String?>(null) }
        val chatRowsState = androidx.compose.runtime.rememberUpdatedState(chatRows)
        // simplexItemId → Message index for the loaded window, so a reply
        // bubble can walk the quote chain upward (NestedQuoteChain). Rebuilt
        // when the row set changes; ancestors outside the window resolve to
        // null and simply end the chain.
        val msgByItemId by remember(chatRows) {
            mutableStateOf(
                chatRows.asSequence()
                    .mapNotNull { (it as? ChatRow.Msg)?.msg }
                    .mapNotNull { m -> m.simplexItemId?.let { it to m } }
                    .toMap(),
            )
        }
        // Inverse index: target itemId → the itemIds that REPLY to it, in
        // conversation order. Powers the "↳ N replies" affordance — the
        // forward complement of citation-chase (which jumps to the quoted
        // message; this jumps to the messages quoting THIS one).
        val repliesByItemId by remember(chatRows) {
            mutableStateOf(
                chatRows.asSequence()
                    .mapNotNull { (it as? ChatRow.Msg)?.msg }
                    .filter { it.replyToItemId != null && it.simplexItemId != null }
                    .groupBy({ it.replyToItemId!! }, { it.simplexItemId!! }),
            )
        }
        // Faceted-slab run grouping: consecutive same-sender messages
        // collapse into one slab (tail only on the last). Each run gets a
        // UNIFORM width = its widest line, capped at the typographic measure
        // (~74% of screen on a phone) so the block is a monoblock with no
        // saw-tooth. Measured once per row set. Attachment/reply messages
        // take the full cap so their richer content isn't clipped.
        val bubbleDensity = androidx.compose.ui.platform.LocalDensity.current
        val bubbleMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        val bubbleConfig = androidx.compose.ui.platform.LocalConfiguration.current
        val runMetaById = remember(chatRows) {
            val visible = chatRows.mapNotNull { (it as? ChatRow.Msg)?.msg }
            val screenWpx = with(bubbleDensity) { bubbleConfig.screenWidthDp.dp.toPx() }
            val padPx = with(bubbleDensity) { 28.dp.toPx() }   // 14 + 14 horizontal
            val style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
            val capPx = minOf(
                screenWpx * 0.74f,
                bubbleMeasurer.measure("n".repeat(60), style = style).size.width.toFloat() + padPx,
            )
            // A bubble must be at least as wide as its timestamp/status
            // footer. Without this floor a short message ("Uh") sizes itself
            // to the 1-char text width and the footer wraps vertically into a
            // malformed sliver ("1/0/J/u/n…"). Floor every text bubble at the
            // footer measure (clamped under the cap on tiny screens).
            val footerStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
            val footerFloorPx = minOf(
                capPx,
                bubbleMeasurer.measure("00 Jun, 00:00  aegis ✓✓", style = footerStyle)
                    .size.width.toFloat() + padPx,
            )
            app.aether.aegis.ui.components.computeRunMeta(
                messages = visible,
                dayOf = { dayLabel(it.timestamp) },
                naturalWidthPx = { m ->
                    when {
                        m.attachmentPath != null || m.replyToItemId != null -> capPx
                        // CALL_LOG content is JSON — measure the rendered call
                        // line, not the raw payload, or the bubble blows wide.
                        m.type == app.aether.aegis.core.MessageType.CALL_LOG -> maxOf(
                            footerFloorPx,
                            bubbleMeasurer.measure(
                                callLogTitle(m, selfKey, context), style = style,
                            ).size.width.toFloat() + padPx,
                        )
                        else -> maxOf(
                            footerFloorPx,
                            bubbleMeasurer.measure(
                                m.content.ifBlank { " " }, style = style,
                            ).size.width.toFloat() + padPx,
                        )
                    }
                },
                capPx = capPx,
            )
        }
        // Same-sender runs collapse into ONE glass slab, so the list renders
        // GROUPS, not raw rows. chatRendersRev is the exact reverseLayout item
        // order; renderIndexByMsgId maps a message to its list item so the
        // citation-jump/scroll math indexes by group, not by row.
        val chatRenders = remember(chatRows, runMetaById) {
            buildChatRenders(chatRows, runMetaById)
        }
        val chatRendersRev = remember(chatRenders) { chatRenders.asReversed() }
        val renderIndexByMsgId = remember(chatRendersRev) {
            buildMap {
                chatRendersRev.forEachIndexed { i, r ->
                    when (r) {
                        is ChatRender.Single -> put(r.msg.id, i)
                        is ChatRender.Run -> r.msgs.forEach { put(it.id, i) }
                        is ChatRender.Sep -> {}
                    }
                }
            }
        }
        fun rowIndexByMsgId(id: String): Int = renderIndexByMsgId[id] ?: -1
        // Representative message id of the list item at [i] — for resolving
        // "what's at the top of the viewport" back to a message.
        fun firstMsgIdOfRenderAt(i: Int): String? =
            when (val r = chatRendersRev.getOrNull(i)) {
                is ChatRender.Single -> r.msg.id
                is ChatRender.Run -> r.msgs.first().id
                else -> null
            }
        // Experimental real-glass sheen toggle (off by default).
        val glassSheenPrefs = remember(context) { app.aether.aegis.prefs.ExperimentalPrefs(context) }
        val glassSheenOn by glassSheenPrefs.glassSheenFlow.collectAsState()
        // Experimental real-glass 3D — the whole message pane tilts in
        // perspective with the phone. Reuses the same accelerometer-backed
        // TiltSensor as the sheen; acquired only while 3D is on AND
        // rich-graphics is allowed, released the moment either drops — so a
        // disabled effect costs ZERO battery.
        val glassThreeDOn by glassSheenPrefs.glassThreeDFlow.collectAsState()
        val richGraphics = app.aether.aegis.ui.LocalGraphicsRich.current
        val paneTiltActive = glassThreeDOn && richGraphics
        val paneTilt by app.aether.aegis.ui.components.TiltSensor.tilt.collectAsState()
        if (paneTiltActive) {
            DisposableEffect(Unit) {
                app.aether.aegis.ui.components.TiltSensor.acquire(context)
                onDispose { app.aether.aegis.ui.components.TiltSensor.release() }
            }
        }
        val jumpToReplyItem: (Long) -> Unit = { targetItemId ->
            chatScope.launch {
                // Resolve the quoted message, then its LIST item (a run slab
                // may hold several messages) so we scroll to the right group
                // and flash the exact message inside it.
                val tid = chatRowsState.value.asSequence()
                    .mapNotNull { (it as? ChatRow.Msg)?.msg }
                    .firstOrNull { it.simplexItemId == targetItemId }
                    ?.id
                val idx = tid?.let { renderIndexByMsgId[it] ?: -1 } ?: -1
                if (tid != null && idx >= 0) {
                    val anchorId = firstMsgIdOfRenderAt(listState.firstVisibleItemIndex)
                    if (anchorId != null) citationStack.add(anchorId)
                    runCatching { listState.animateScrollToItem(idx) }
                    flashMsgId = tid
                    kotlinx.coroutines.delay(1600)
                    if (flashMsgId == tid) flashMsgId = null
                }
            }
        }
        // Clear the stack once the viewport is newer than the entry anchor.
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
                .collect { idx ->
                    if (citationStack.isEmpty()) return@collect
                    val entryIdx = rowIndexByMsgId(citationStack.first())
                    if (entryIdx >= 0 && idx < entryIdx) citationStack.clear()
                }
        }

        // In-flight SimpleX file transfers for this peer / group —
        // banner sits between the chat header and the message list
        // and renders one row per transfer with a Cancel button.
        // Subscribes to InFlightFiles so a transfer that starts /
        // finishes while the chat is open shows + disappears live.
        InFlightFilesBanner(peerKey = memberId)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Single-plane pseudo-3D: tilt the whole pane in
                // perspective so the glass appears to protrude. One plane,
                // not per-bubble — per-bubble tilt is nauseating. Read in
                // the draw-phase lambda so a tilt update only re-draws, no
                // recomposition. ±7° is the sweet spot (enough depth, no
                // text legibility loss); cameraDistance 12·density keeps
                // the perspective subtle rather than fish-eyed.
                .then(
                    if (paneTiltActive) Modifier.graphicsLayer {
                        rotationY = (paneTilt.x * 7f).coerceIn(-7f, 7f)
                        rotationX = (-paneTilt.y * 7f).coerceIn(-7f, 7f)
                        cameraDistance = 12f * density
                    } else Modifier
                ),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            state = listState,
            reverseLayout = true,
        ) {
            // Floating date pill per GUI_SPEC. Sep rows become
            // stickyHeader → the visual-top date label pins itself
            // there while you scroll, only swapping when the visible
            // window crosses into a different day. Same behaviour as
            // WhatsApp / Telegram.
            chatRendersRev.forEach { render ->
                when (render) {
                    is ChatRender.Sep -> stickyHeader(key = "sep-${render.label}") {
                        DateSeparator(render.label)
                    }
                    is ChatRender.Single -> item(
                        key = "msg-${render.msg.id}",
                        // Separate reuse pools for media vs text bubbles: a media
                        // bubble carries a decrypt/produceState + Coil image and
                        // is much heavier than a text bubble, so reusing a text
                        // slot for it (or vice-versa) rebuilds from scratch.
                        // Distinct contentTypes let Compose recycle media slots
                        // for media — cheaper scrolling in a photo-heavy chat.
                        contentType = if (render.msg.attachmentMime != null) "media" else "text",
                    ) {
                        val m = render.msg
                        MessageBubble(
                            msg = m,
                            selfKey = selfKey,
                            navController = navController,
                            knownPeers = peers,
                            onJumpToReply = jumpToReplyItem,
                            resolveQuoted = { id -> msgByItemId[id] },
                            replyChildren = { id -> repliesByItemId[id] ?: emptyList() },
                            runMeta = runMetaById[m.id],
                            glassSheen = glassSheenOn,
                            glassEdgeLight = paneTiltActive,
                            flashing = m.id == flashMsgId,
                            onReply = { replyingTo = m },
                            onForward = { forwardCandidate = m },
                            onBurnReveal = { burnViewing = m },
                            onDelete = {
                                chatScope.launch {
                                    AegisApp.instance.repository.deleteMessage(m.id)
                                }
                            },
                            onEdit = {
                                // Pre-fill the composer with the existing
                                // content + flip into edit mode. The
                                // composer's send action checks editing!=null
                                // and writes back to the same message id
                                // instead of creating a new one.
                                editing = m
                                messageText = m.content
                            },
                        )
                    }
                    // Same-sender run → one shared glass slab with the
                    // per-message blocks inside (see MessageRun).
                    is ChatRender.Run -> item(key = "run-${render.msgs.first().id}", contentType = "run") {
                        MessageRun(
                            msgs = render.msgs,
                            selfKey = selfKey,
                            navController = navController,
                            onReply = { m -> replyingTo = m },
                            onForward = { m -> forwardCandidate = m },
                            onDelete = { m ->
                                chatScope.launch {
                                    AegisApp.instance.repository.deleteMessage(m.id)
                                }
                            },
                            onEdit = { m ->
                                editing = m
                                messageText = m.content
                            },
                            onBurnReveal = { m -> burnViewing = m },
                            onJumpToReply = jumpToReplyItem,
                            resolveQuoted = { id -> msgByItemId[id] },
                            replyChildren = { id -> repliesByItemId[id] ?: emptyList() },
                            runMetaById = { id -> runMetaById[id] },
                            knownPeers = peers,
                            glassSheen = glassSheenOn,
                            glassEdgeLight = paneTiltActive,
                            flashingId = flashMsgId,
                        )
                    }
                }
            }
        }

        // Editing banner — sits where the reply banner sits, with a
        // "✕ Cancel" affordance.
        editing?.let { ed ->
            Surface(
                color = app.aether.aegis.ui.theme.AegisCyanGlow,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.chat_editing_message),
                            fontSize = 11.sp,
                            color = app.aether.aegis.ui.theme.AegisCyan,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            ed.content.take(120),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    IconButton(onClick = { editing = null; messageText = "" }) {
                        AegisIcon(app.aether.aegis.ui.components.AegisIcons.Close, stringResource(R.string.chat_cancel_edit))
                    }
                }
            }
        }
            // Floating "jump to newest" hex — WhatsApp-style. Visible
            // only when the user has scrolled away from the bottom
            // (firstVisibleItemIndex > 0 in a reverseLayout column =
            // not on the latest message). Tap = animate to item 0.
            val showJump by androidx.compose.runtime.remember {
                androidx.compose.runtime.derivedStateOf {
                    // "Away from the newest message" = the list can still
                    // scroll toward the start (the bottom, under reverseLayout
                    // = scroll offset 0). This is more robust than
                    // `firstVisibleItemIndex > 0`, which could read > 0 while a
                    // tall last item (a run slab) settled at the bottom and so
                    // kept the button up when you were already there.
                    listState.canScrollBackward
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showJump,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit  = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp),
            ) {
                app.aether.aegis.ui.components.HexShape(
                    size = 42.dp,
                    borderColor = app.aether.aegis.ui.theme.AegisCyan,
                    fillColor = app.aether.aegis.ui.theme.AegisPanel.copy(alpha = 0.92f),
                    glow = true,
                    onClick = {
                        chatScope.launch {
                            runCatching { listState.animateScrollToItem(0) }
                        }
                    },
                ) {
                    Text(
                        "↓",
                        color = app.aether.aegis.ui.theme.AegisCyan,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // ↩ Citation-return — pops one level of the chase back-stack and
            // scrolls to that anchor. Sits above the "jump to newest" hex,
            // visible only while a chase is active.
            androidx.compose.animation.AnimatedVisibility(
                visible = citationStack.isNotEmpty(),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit  = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 64.dp),
            ) {
                app.aether.aegis.ui.components.HexShape(
                    size = 42.dp,
                    borderColor = app.aether.aegis.ui.theme.AegisCyan,
                    fillColor = app.aether.aegis.ui.theme.AegisPanel.copy(alpha = 0.92f),
                    glow = true,
                    onClick = {
                        chatScope.launch {
                            val anchorId = citationStack.removeLastOrNull()
                                ?: return@launch
                            val idx = rowIndexByMsgId(anchorId)
                            if (idx >= 0) runCatching { listState.animateScrollToItem(idx) }
                        }
                    },
                ) {
                    Text(
                        "↩",
                        color = app.aether.aegis.ui.theme.AegisCyan,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }  // close Box wrapper around LazyColumn

        replyingTo?.let { rt ->
            // WhatsApp-style reply preview above the compose bar. For
            // image attachments the actual thumb renders; for voice /
            // file the type icon + filename / size hint stands in.
            // Text-only quotes keep the existing single-line snippet.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val rtAttachPath = rt.attachmentPath
                    val rtAttachMime = rt.attachmentMime
                    if (rtAttachPath != null && rtAttachMime?.startsWith("image/") == true) {
                        // Decrypt off the composition thread (same rule as the
                        // message bubble): a sealed reply-thumbnail decrypted
                        // inline would freeze the chat for a large image. Key on
                        // contentHashCode(), not the raw ByteArray (reference
                        // equality), so it doesn't re-run every recomposition.
                        val needsDecrypt = rt.sealedDek != null &&
                            app.aether.aegis.lock.ChatAttachmentSeal.isEncrypted(rtAttachPath)
                        val viewable by androidx.compose.runtime.produceState<String?>(
                            initialValue = if (needsDecrypt) null else rtAttachPath,
                            key1 = rt.id,
                            key2 = rt.sealedDek?.contentHashCode(),
                        ) {
                            value = if (!needsDecrypt) rtAttachPath else withContext(Dispatchers.IO) {
                                AegisApp.instance.repository.viewableAttachmentPath(rt, context)
                            }
                        }
                        val viewablePath = viewable
                        if (viewablePath != null) {
                            coil.compose.AsyncImage(
                                model = java.io.File(viewablePath),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(MaterialTheme.shapes.small),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Replying to ${if (rt.from == selfKey) "yourself" else rt.from.removePrefix("simplex:")}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        // Compose the preview as an icon + text Row
                        // so attachment hints use the LunaGlass
                        // vector glyphs instead of system emoji
                        // (which render in the platform font and
                        // visually clash with the cyan-stroke
                        // aesthetic everywhere else).
                        val (previewIcon: Int?, previewText: String) = when {
                            rtAttachMime?.startsWith("image/") == true ->
                                app.aether.aegis.ui.components.AegisIcons.Gallery to rt.content.ifBlank { stringResource(R.string.story_screens_photo) }
                            rtAttachMime?.startsWith("audio/") == true ->
                                app.aether.aegis.ui.components.AegisIcons.Mic to rt.content.ifBlank { stringResource(R.string.chat_voice_message) }
                            rtAttachMime?.startsWith("video/") == true ->
                                app.aether.aegis.ui.components.AegisIcons.Play to rt.content.ifBlank { "Video" }
                            rtAttachPath != null ->
                                app.aether.aegis.ui.components.AegisIcons.File to rt.content.ifBlank {
                                    rt.attachmentName ?: stringResource(R.string.chat_file)
                                }
                            else -> null to rt.content.take(120)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (previewIcon != null) {
                                AegisIcon(
                                    icon = previewIcon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                previewText,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                    IconButton(onClick = { replyingTo = null }) {
                        AegisIcon(app.aether.aegis.ui.components.AegisIcons.Close, stringResource(R.string.chat_cancel_reply))
                    }
                }
            }
        }

        // Burn-armed chip — visible only while pendingBurnTtl is set.
        // X clears burn mode; tapping the body itself does nothing.
        pendingBurnTtl?.let { ttl ->
            Surface(
                color = app.aether.aegis.ui.theme.AegisPanel,
                shape = MaterialTheme.shapes.medium,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, app.aether.aegis.ui.theme.AegisSOS,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔥", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.chat_burn_after_reading),
                            fontSize = 11.sp,
                            color = app.aether.aegis.ui.theme.AegisSOS,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (ttl == 0) "Until close · wiped on view"
                            else "${ttl}s viewing window · wiped on close",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { pendingBurnTtl = null }) {
                        AegisIcon(app.aether.aegis.ui.components.AegisIcons.Close, "Cancel burn")
                    }
                }
            }
        }

        pendingAttachment?.let { att ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (att.mime.startsWith("image/")) {
                        coil.compose.AsyncImage(
                            model = java.io.File(att.path),
                            contentDescription = att.name ?: "attachment",
                            modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small),
                        )
                    } else {
                        AegisIcon(app.aether.aegis.ui.components.AegisIcons.Add, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            att.name ?: att.mime,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            "${att.mime} · ${humanSize(att.size)}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pendingAttachmentQueue.isNotEmpty()) {
                        Text(
                            "+${pendingAttachmentQueue.size} queued",
                            color = app.aether.aegis.ui.theme.AegisCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    IconButton(onClick = {
                        // Cancel = drop everything (staged + queue).
                        // Users picking a batch then bailing usually
                        // mean to bail on all of it; making them
                        // dismiss each item one at a time would be
                        // worse than the "send instantly" bug we
                        // just fixed.
                        pendingAttachment = null
                        pendingAttachmentQueue = emptyList()
                    }) {
                        AegisIcon(app.aether.aegis.ui.components.AegisIcons.Close, "Remove attachment")
                    }
                }
            }
        }

        // Attach drawer — slides in from the left edge when + is
        // tapped, slides back out on close. Anchored just above the
        // compose bar so it reads as "emerging from the + button".
        // Tap on a tile fires the picker AND closes the drawer; tap
        // on + again toggles it closed manually. Same callbacks as
        // before, just hosted in an animated panel instead of a
        // ModalBottomSheet.
        val drawerCtx = LocalContext.current
        androidx.compose.animation.AnimatedVisibility(
            visible = attachDrawerOpen,
            enter = androidx.compose.animation.slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 280),
            ) + androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 200),
            ),
            exit = androidx.compose.animation.slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 220),
            ) + androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 160),
            ),
        ) {
            ComposeAttachDrawer(
                onCamera = {
                    attachDrawerOpen = false
                    (drawerCtx as? app.aether.aegis.MainActivity)?.takePhoto { uri ->
                        chatScope.launch {
                            val local = withContext(Dispatchers.IO) {
                                app.aether.aegis.util.Attachments.import(drawerCtx, uri, fallbackMime = "image/jpeg")
                            }
                            if (local != null) pendingAttachment = local
                        }
                    }
                },
                onGallery = {
                    attachDrawerOpen = false
                    (drawerCtx as? app.aether.aegis.MainActivity)?.pickVisualMedia { uris ->
                        if (uris.isEmpty()) return@pickVisualMedia
                        chatScope.launch {
                            if (uris.size == 1) {
                                val local = withContext(Dispatchers.IO) {
                                    app.aether.aegis.util.Attachments.import(drawerCtx, uris[0])
                                }
                                if (local != null) pendingAttachment = local
                            } else {
                                uris.forEach { uri ->
                                    val local = withContext(Dispatchers.IO) {
                                        app.aether.aegis.util.Attachments.import(drawerCtx, uri)
                                    }
                                    if (local != null) sendStagedAttachment(memberId, "", local)
                                }
                            }
                        }
                    }
                },
                onFile = {
                    attachDrawerOpen = false
                    (drawerCtx as? app.aether.aegis.MainActivity)?.pickMultipleAttachments("*/*") { uris ->
                        if (uris.isEmpty()) return@pickMultipleAttachments
                        chatScope.launch {
                            // Single pick → stage in compose bar as
                            // before. Multi-pick: stage the FIRST and
                            // queue the rest so the user gets a
                            // preview + caption opportunity per item
                            // instead of the previous "tap pick → 7
                            // files shoot out unannounced" behaviour.
                            // pendingAttachmentQueue is drained one
                            // entry at a time as the user hits Send.
                            val imported = uris.mapNotNull { uri ->
                                withContext(Dispatchers.IO) {
                                    app.aether.aegis.util.Attachments.import(drawerCtx, uri)
                                }
                            }
                            if (imported.isEmpty()) return@launch
                            pendingAttachment = imported.first()
                            pendingAttachmentQueue = imported.drop(1)
                        }
                    }
                },
                onLocation = {
                    attachDrawerOpen = false
                    chatScope.launch {
                        val fix = readCurrentLocationForCompose(drawerCtx)
                        if (fix == null) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    drawerCtx,
                                    "Location unavailable — grant permission and try again",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            return@launch
                        }
                        val (lat, lng) = fix
                        // Plain geo: URI — no 📍 emoji prefix. The
                        // receiver bubble renders an inline OSM map
                        // preview with a cyan hex pin centred on the
                        // coords, plus the clickable URI below. Adding
                        // an emoji on top would compete with the
                        // LunaGlass vector and read as "two pins."
                        val body = "geo:%.5f,%.5f".format(lat, lng)
                        AegisApp.instance.protocolManager.sendMessage(
                            to = memberId, content = body,
                        )
                    }
                },
                onBurn = {
                    attachDrawerOpen = false
                    burnTtlPickerOpen = true
                },
            )
        }

        // Compose bar. Three elements only: + drawer,
        // text input, context-sensitive right hex (mic when empty,
        // send when text/attachment ready).
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val ctx = LocalContext.current
            val scope = rememberCoroutineScope()
            // LEFT: hex "+" → opens the attach drawer. The icon
            // rotates 45° on open so the same ic_aegis_plus glyph
            // reads as an "×" close affordance — same pattern as
            // ChatList's add-contact FAB.
            val addRotation by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (attachDrawerOpen) 45f else 0f,
                animationSpec = androidx.compose.animation.core.tween(durationMillis = 240),
                label = stringResource(R.string.chat_composeaddrotate),
            )
            app.aether.aegis.ui.components.HexShape(
                size = 38.dp,
                borderColor = app.aether.aegis.ui.theme.AegisCyan,
                fillColor = androidx.compose.ui.graphics.Color.Transparent,
                onClick = { attachDrawerOpen = !attachDrawerOpen },
            ) {
                app.aether.aegis.ui.components.AegisIcon(
                    icon = app.aether.aegis.ui.components.AegisIcons.Add,
                    contentDescription = if (attachDrawerOpen) "Close attachments" else "Add attachment",
                    tint = app.aether.aegis.ui.theme.AegisCyan,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(addRotation),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // CENTER: text input, fills remaining width.
            // LunaGlass pill: 20dp rounded, panel bg, cyan-on-focus border.
            var focused by remember { mutableStateOf(false) }
            // Debounced typing ping — fires `[aegis:typing]` at most
            // once every 3 s while the user is actively composing. Stops
            // firing on blank input. Receiver flips TypingTracker on for
            // 5 s, so the three-dot animation in the peer's chat header
            // stays alive while we type and dies ~2 s after we pause.
            //
            // Gated on peers.firstOrNull { … }.isAegis — vanilla SimpleX
            // clients render the raw "[aegis:typing]" body in their
            // chat view, so we don't emit it until we've confirmed
            // the peer is running Aegis (first inbound `[aegis:…]`
            // message from them flips the flag, see
            // SimpleXTransport.handleNewChatItems).
            var lastTypingPingAt by remember { mutableStateOf(0L) }
            val peerIsAegis = peers.firstOrNull { it.publicKey == memberId }?.isAegis == true
            androidx.compose.material3.OutlinedTextField(
                value = messageText,
                onValueChange = { newText ->
                    messageText = newText
                    if (newText.isNotBlank() && peerIsAegis) {
                        val now = System.currentTimeMillis()
                        if (now - lastTypingPingAt > 3_000L &&
                            !app.aether.aegis.decoy.DecoyFixtures.isDecoyKey(memberId)) {
                            lastTypingPingAt = now
                            chatScope.launch {
                                runCatching {
                                    AegisApp.instance.protocolManager.sendMessage(
                                        to = memberId,
                                        content = "[aegis:typing]",
                                        type = MessageType.STATUS,
                                    )
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
                placeholder = {
                    Text(if (pendingAttachment != null) stringResource(R.string.chat_add_caption) else "Message")
                },
                // Multiline so the keyboard's Return key inserts a
                // newline. The send button (rightShowsSend, below)
                // is the explicit send affordance — never the
                // keyboard's Return — so a longer composition
                // doesn't fire after each Enter. maxLines caps the
                // visible growth so the compose bar doesn't take
                // over the screen on a long draft.
                singleLine = false,
                maxLines = 5,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Default,
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                ),
                // Angular LunaGlass field — 45° CUT corners (faceted), not
                // rounded, to match the octagon bubbles + hex chrome.
                shape = androidx.compose.foundation.shape.CutCornerShape(10.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = app.aether.aegis.ui.theme.AegisPanel,
                    unfocusedContainerColor = app.aether.aegis.ui.theme.AegisPanel,
                    focusedBorderColor = app.aether.aegis.ui.theme.AegisCyan,
                    unfocusedBorderColor = app.aether.aegis.ui.theme.AegisBorder,
                ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            // RIGHT: context-sensitive hex. Mic when nothing to send;
            // send arrow (hold-to-execute) when text or attachment ready.
            val rightShowsSend = messageText.isNotBlank() || pendingAttachment != null
            if (rightShowsSend) {
                // Hex send button — 38dp cyan with cyan-glow fill, ↑ arrow.
                // HoldToExecuteHex: when the user has enabled hold-to-send
                // in Settings, the hex fills with cyan as they press and
                // only fires on full hold. When disabled, falls back to
                // tap.
                app.aether.aegis.ui.components.HoldToExecuteHex(
                    size = 38.dp,
                    borderColor = app.aether.aegis.ui.theme.AegisCyan,
                    fillColor = app.aether.aegis.ui.theme.AegisCyanGlow,
                    glow = false,
                    onExecute = {
                        val text = messageText.trim()
                        val attach = pendingAttachment
                        val replyTo = replyingTo
                        val editTarget = editing
                        val quotedId = replyTo?.simplexItemId
                        // Two body strings — `localBody` carries a
                        // markdown quote prefix so the sender's own
                        // bubble shows what they replied to (otherwise
                        // it renders as a context-free standalone
                        // message). `wireBody` is what hits the
                        // network: when SimpleX is going to attach the
                        // native quotedItemId we send the bare text
                        // and let the receiver render the quote
                        // natively; when we have no quotedItemId
                        // (LAN fallback or the target hasn't been
                        // SimpleX-quoted before) we send the markdown
                        // version so the receiver gets SOMETHING.
                        val quotedPrefix = replyTo?.let { rt ->
                            // 1:1 chat: any non-self message is from THIS
                            // contact, so quote them by the nickname you gave
                            // them — not the raw SimpleX handle (the anonymous
                            // alias) that rt.from carries.
                            val sender = if (rt.from == selfKey) "you"
                                         else displayName.ifBlank { rt.from.removePrefix("simplex:") }
                            // If the message we're replying to is ITSELF a
                            // reply, quote only its OWN text — strip its
                            // "> sender: …" prefix. Otherwise nested replies
                            // accumulate "> Bob: > Alice: …" chains and the
                            // parser only peels one layer, so the most recent
                            // citation stops being recognised as a quote and
                            // renders as plain message text (user-reported).
                            val rtOwnText = parseReplyQuote(rt.content).second ?: rt.content
                            // For attachment-only replies the
                            // original message's `content` is blank
                            // — surface a type placeholder so the
                            // receiver who can't render the
                            // SimpleX-native quote (older / vanilla
                            // client) still gets context.
                            val rtPreview = rtOwnText.ifBlank {
                                when {
                                    rt.attachmentMime?.startsWith("image/") == true -> "[Photo]"
                                    rt.attachmentMime?.startsWith("audio/") == true -> "[Voice]"
                                    rt.attachmentMime?.startsWith("video/") == true -> "[Video]"
                                    rt.attachmentPath != null ->
                                        "[File: ${rt.attachmentName ?: "attachment"}]"
                                    else -> ""
                                }
                            }
                            "> $sender: ${rtPreview.take(140)}\n\n"
                        } ?: ""
                        val localBody = quotedPrefix + text
                        // Replies always go on the wire as the markdown-quoted
                        // body, never via SimpleX's native quote: native quote
                        // does not attach to the x.aegis.chat custom content
                        // type (it silently failed on Aegis messages), and the
                        // in-body "> sender: …" quote renders via parseReplyQuote
                        // on every receiver — the transport-agnostic citation
                        // path. Sender-side jump still works off the local
                        // row's replyToItemId.
                        val wireBody = localBody
                        // Backward-compat alias for paths that didn't
                        // care which side they were on.
                        val composed = localBody
                        // Edit path: rewrite the existing message locally
                        // (sets edited=1) and tell SimpleX via /_update so
                        // the recipient sees the new text. We don't keep
                        // edit history — privacy. Only outgoing TEXT
                        // messages can be edited (attachment edit would
                        // need separate plumbing).
                        if (editTarget != null && text.isNotBlank() && attach == null) {
                            chatScope.launch {
                                AegisApp.instance.repository.setMessageEditedBody(
                                    editTarget.id, text,
                                )
                                val simplexId = editTarget.simplexItemId
                                if (simplexId != null) {
                                    runCatching {
                                        val simplex = AegisApp.instance.transports
                                            .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                                            .firstOrNull()
                                        val ok = simplex?.editText(
                                            memberId, simplexId, text,
                                            targetDna = editTarget.messageDna,
                                        ) ?: false
                                        if (!ok) {
                                            // Surface failure — local DB
                                            // shows "edited" but the peer
                                            // still has the old text. Better
                                            // for the user to know than to
                                            // silently lie.
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    context,
                                                    "Edit didn't reach the peer",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            }
                            editing = null
                            messageText = ""
                            return@HoldToExecuteHex
                        }
                        if (attach != null) {
                            // In duress + decoy peer: silently discard the
                            // attachment, just append a fake-sent message
                            // so the attacker sees feedback. Don't actually
                            // ship a real file anywhere.
                            if (inDuress && app.aether.aegis.decoy.DecoyFixtures.isDecoyKey(memberId)) {
                                decoySent.add(
                                    app.aether.aegis.decoy.DecoyFixtures.outgoingDecoyMessage(memberId, composed.ifBlank { "file" }),
                                )
                            } else {
                                scope.launch { sendStagedAttachment(memberId, composed, attach) }
                            }
                            // If the multi-pick queue had more items
                            // waiting, promote the next one to the
                            // compose bar so the user can review +
                            // caption it before sending. Drains one
                            // entry per Send tap.
                            val next = pendingAttachmentQueue.firstOrNull()
                            if (next != null) {
                                pendingAttachment = next
                                pendingAttachmentQueue = pendingAttachmentQueue.drop(1)
                            } else {
                                pendingAttachment = null
                            }
                            replyingTo = null
                            messageText = ""
                        } else if (text.isNotBlank() && inDuress && app.aether.aegis.decoy.DecoyFixtures.isDecoyKey(memberId)) {
                            // Decoy send: append to the local fake-conversation
                            // list. Nothing leaves the device. No network call.
                            decoySent.add(
                                app.aether.aegis.decoy.DecoyFixtures.outgoingDecoyMessage(memberId, composed),
                            )
                            replyingTo = null
                            messageText = ""
                        } else if (text.isNotBlank() && pendingBurnTtl != null) {
                            // Burn-after-reading send path. Generate the row UUID upfront
                            // so the wire marker carries it; the
                            // recipient echoes it back via the
                            // burn-receipt and we delete that exact
                            // row when the receipt arrives. Snapshot
                            // pendingBurnTtl into a local — the var
                            // can't be smart-cast through the closure
                            // boundary of the inner scope.launch.
                            val ttl = pendingBurnTtl ?: 0
                            val rowId = java.util.UUID.randomUUID().toString()
                            val outBody = "[aegis:burn:$ttl:$rowId]$composed"
                            val ctxRef = context
                            scope.launch {
                                val simplex = AegisApp.instance.transports
                                    .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                                    .firstOrNull()
                                val ok = simplex?.sendText(memberId, outBody, null) ?: false
                                if (ok) {
                                    AegisApp.instance.repository.recordSent(
                                        toKey = memberId,
                                        body = outBody,
                                        protocol = Protocol.SIMPLEX,
                                        type = MessageType.BURN,
                                        burnTtlSeconds = ttl,
                                        idOverride = rowId,
                                    )
                                    simplex.consumeLastSentItemId(memberId, outBody)?.let { id ->
                                        AegisApp.instance.repository.setSimplexItemId(rowId, id)
                                    }
                                    simplex.consumeLastSentDna(memberId, outBody)?.let { dna ->
                                        AegisApp.instance.repository.setMessageDna(rowId, dna)
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            ctxRef,
                                            "Burn message needs SimpleX online",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                            pendingBurnTtl = null
                            replyingTo = null
                            messageText = ""
                        } else if (text.isNotBlank()) {
                            scope.launch {
                                if (quotedId != null) {
                                    val simplex = AegisApp.instance.transports
                                        .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                                        .firstOrNull()
                                    val ok = simplex?.sendText(memberId, wireBody, quotedId) ?: false
                                    if (ok) {
                                        // Local row keeps the markdown
                                        // quote so the sender's bubble
                                        // shows the context. Receiver
                                        // got the native SimpleX quote
                                        // off wireBody + quotedId.
                                        val msg = AegisApp.instance.repository.recordSent(
                                            toKey = memberId,
                                            body = localBody,
                                            protocol = Protocol.SIMPLEX,
                                            // Link to the quoted message so a
                                            // tap on this reply's citation can
                                            // jump straight to it.
                                            replyToItemId = quotedId,
                                        )
                                        simplex.consumeLastSentItemId(memberId, wireBody)?.let { id ->
                                            AegisApp.instance.repository.setSimplexItemId(msg.id, id)
                                        }
                                        simplex.consumeLastSentDna(memberId, wireBody)?.let { dna ->
                                            AegisApp.instance.repository.setMessageDna(msg.id, dna)
                                        }
                                    } else {
                                        AegisApp.instance.protocolManager.sendMessage(
                                            to = memberId, content = localBody,
                                        )
                                    }
                                } else {
                                    AegisApp.instance.protocolManager.sendMessage(
                                        to = memberId, content = localBody,
                                    )
                                }
                            }
                            replyingTo = null
                            messageText = ""
                        }
                    },
                ) {
                    Text("↑", color = app.aether.aegis.ui.theme.AegisCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                // Hold to record voice. Released-too-quick presses are
                // dropped; slide LEFT past ~80 dp to cancel.
                app.aether.aegis.ui.components.VoiceRecordHex(
                    onRecorded = { local -> pendingAttachment = local },
                )
            }
        }

        // ---- Schedule dialog (triggered from chat overflow menu) ----
        if (scheduleOpen) {
            ScheduleDialog(
                onDismiss = { scheduleOpen = false },
                onPick = { whenMs ->
                    val text = messageText.trim()
                    if (text.isNotBlank()) {
                        chatScope.launch {
                            AegisApp.instance.repository.scheduleMessage(
                                toKey = memberId,
                                body = text,
                                scheduledFor = whenMs,
                            )
                        }
                        messageText = ""
                    }
                    scheduleOpen = false
                },
            )
        }

        // (Attach drawer moved above the compose bar — see the
        // AnimatedVisibility block higher up.)

        // ---- Per-chat disappearing-messages picker ----
        if (ttlDialogOpen) {
            val currentTtl = peers.firstOrNull { it.publicKey == memberId }?.disappearingTtl
            DisappearingTtlDialog(
                currentSeconds = currentTtl,
                onDismiss = { ttlDialogOpen = false },
                onPick = { newTtl ->
                    ttlDialogOpen = false
                    chatScope.launch {
                        val simplex = AegisApp.instance.transports
                            .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                            .firstOrNull()
                        if (simplex?.setChatTtl(memberId, newTtl) == true) {
                            AegisApp.instance.repository.setPeerDisappearingTtl(
                                memberId, newTtl,
                            )
                        }
                    }
                },
            )
        }

        // ---- Burn TTL picker ----
        if (burnTtlPickerOpen) {
            BurnTtlPickerDialog(
                onDismiss = { burnTtlPickerOpen = false },
                onPick = { ttl ->
                    pendingBurnTtl = ttl
                    burnTtlPickerOpen = false
                },
            )
        }

        // ---- Burn viewer (incoming reveal) ----
        burnViewing?.let { revealed ->
            val env = parseBurnEnvelope(revealed.content)
            BurnViewerDialog(
                text = env?.text ?: revealed.content,
                ttlSeconds = env?.ttlSeconds ?: 0,
                onClose = {
                    val msg = revealed
                    burnViewing = null
                    chatScope.launch {
                        runCatching {
                            // Receipt back to the sender so their row
                            // disappears too. Best-effort; even if the
                            // peer is offline we still wipe locally.
                            env?.senderRowId?.let { senderId ->
                                AegisApp.instance.protocolManager.sendMessage(
                                    to = memberId,
                                    content = "[aegis:burn-receipt:$senderId]",
                                    type = MessageType.STATUS,
                                )
                            }
                            AegisApp.instance.repository.deleteMessageById(msg.id)
                        }
                    }
                },
            )
        }
    }
}

/** Inline attach panel that sits above the compose bar. Hosted by
 *  an AnimatedVisibility that slides it in from the left edge — see
 *  ChatScreen for the wrapping. No ModalBottomSheet, no scrim: tiles
 *  are tap-once-and-go, and tapping + again toggles the panel back. */
@Composable
private fun ComposeAttachDrawer(
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFile: () -> Unit,
    onLocation: () -> Unit,
    onBurn: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(app.aether.aegis.ui.theme.AegisSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AttachDrawerTile(
            label = stringResource(R.string.chat_camera),
            icon = app.aether.aegis.ui.components.AegisIcons.Camera,
            onClick = onCamera,
        )
        AttachDrawerTile(
            label = stringResource(R.string.chat_gallery),
            icon = app.aether.aegis.ui.components.AegisIcons.Gallery,
            onClick = onGallery,
        )
        AttachDrawerTile(
            label = stringResource(R.string.chat_file),
            icon = app.aether.aegis.ui.components.AegisIcons.File,
            onClick = onFile,
        )
        AttachDrawerTile(
            label = stringResource(R.string.chat_location),
            icon = app.aether.aegis.ui.components.AegisIcons.Location,
            onClick = onLocation,
        )
        AttachDrawerTile(
            label = stringResource(R.string.chat_burn),
            icon = app.aether.aegis.ui.components.AegisIcons.Burn,
            onClick = onBurn,
        )
    }
}

@Composable
private fun AttachDrawerTile(
    label: String,
    onClick: () -> Unit,
    icon: Int? = null,
    glyph: String? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        app.aether.aegis.ui.components.HexShape(
            size = 56.dp,
            borderColor = app.aether.aegis.ui.theme.AegisCyan,
            fillColor = androidx.compose.ui.graphics.Color.Transparent,
            onClick = onClick,
        ) {
            when {
                icon != null -> app.aether.aegis.ui.components.AegisIcon(
                    icon = icon,
                    contentDescription = label,
                    tint = app.aether.aegis.ui.theme.AegisCyan,
                    modifier = Modifier.size(24.dp),
                )
                glyph != null -> Text(glyph, fontSize = 22.sp)
            }
        }
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Best-effort one-shot GPS fix for the compose-bar Location action.
 *  Tries GPS → NETWORK → PASSIVE last-known; null if none cached or
 *  no permission granted. */
@android.annotation.SuppressLint("MissingPermission")
private suspend fun readCurrentLocationForCompose(
    context: android.content.Context,
): Pair<Double, Double>? = withContext(Dispatchers.IO) {
    val fine = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_FINE_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return@withContext null
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
        as? android.location.LocationManager ?: return@withContext null
    val providers = listOfNotNull(
        if (fine) android.location.LocationManager.GPS_PROVIDER else null,
        android.location.LocationManager.NETWORK_PROVIDER,
        android.location.LocationManager.PASSIVE_PROVIDER,
    )
    for (p in providers) {
        val loc = runCatching {
            if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null
        }.getOrNull()
        if (loc != null) return@withContext loc.latitude to loc.longitude
    }
    null
}

@Composable
private fun VideoBubble(path: String, name: String?, size: Long, mime: String) {
    val context = LocalContext.current
    // Pull a thumbnail from the first frame so the bubble previews
    // the video without a custom VideoView. MediaMetadataRetriever's
    // setDataSource + frameAtTime decode a video frame and can block for
    // 100s of ms on a large file — NEVER on the composition thread, or
    // it janks/ANRs the chat (same rule as attachment decrypt). Resolve
    // it via produceState on Dispatchers.IO; the bubble shows its
    // play-arrow placeholder until the frame is ready.
    val thumb by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = path,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(path)
                val bmp = mmr.frameAtTime
                mmr.release()
                bmp
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", java.io.File(path),
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
                runCatching { context.startActivity(intent) }
            },
        contentAlignment = Alignment.Center,
    ) {
        val thumbBitmap = thumb
        if (thumbBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = thumbBitmap.asImageBitmap(),
                contentDescription = name ?: stringResource(R.string.chat_video),
                modifier = Modifier
                    .heightIn(max = 240.dp)
                    .widthIn(max = 260.dp),
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AegisIcon(
                        icon = app.aether.aegis.ui.components.AegisIcons.Play,
                        contentDescription = stringResource(R.string.chat_video),
                        tint = app.aether.aegis.ui.theme.AegisCyan,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(name ?: stringResource(R.string.chat_video))
                }
            }
        }
        // Play-arrow overlay over the thumb so it reads as "tap to
        // play". Pure black-on-white triangle is the universal video
        // play affordance (YouTube, every player) — kept outside the
        // LunaGlass palette intentionally so the cue reads as
        // "system control over media", not "themed accent". Flagged by
        // the LunaGlass audit as an exemption.
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Placeholder bubble for a DEFERRED attachment — one the auto-download gate
 * chose not to pull (Wi-Fi-only on a metered link, an excluded media type,
 * over the size cap, or an Untrusted sender). The file isn't on disk yet;
 * this shows what's waiting and offers a tap to fetch it on demand. An
 * explicit tap always overrides the gate that held it — user intent wins.
 *
 * States:
 *   - idle:        "<kind> · Tap to download", with a note when an Untrusted
 *                  sender is why it was held.
 *   - downloading: a spinner, driven by the same InFlightFiles match the
 *                  completed bubble uses; the row swaps to real media once
 *                  the download completes and fills the placeholder's path.
 *   - unavailable: the remembered fileId is gone (expired invitation, or
 *                  core state lost across a cold restart) — `/freceive` can't
 *                  resurrect it, so no tap is offered.
 */
@Composable
private fun DeferredAttachmentChip(msg: Message, mime: String, selfKey: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val size = msg.attachmentSize ?: 0L
    val itemId = msg.simplexItemId ?: -1L
    // The fileId stashed at defer-time. Null once pulled / cleared, or if
    // this item was never deferred. Keyed on (id, itemId) so a completion
    // that cleared the entry re-reads to the unavailable copy.
    val fileId = remember(msg.id, itemId) {
        app.aether.aegis.attachment.DeferredDownloads.fileIdFor(context, itemId)
    }
    // Sender trust tier — surfaced only when it's the reason the file was
    // held (Untrusted), so the placeholder is self-explanatory.
    val tier by produceState<app.aether.aegis.data.TrustTier?>(null, msg.from) {
        value = runCatching {
            AegisApp.instance.repository.knownPeerByKey(msg.from)?.trustTier
                ?.let { app.aether.aegis.data.TrustTier.valueOf(it) }
        }.getOrNull()
    }
    // Live transfer match — identical keying to the completed-bubble block,
    // so a tapped download shows progress here too.
    val xferActive by app.aether.aegis.simplex.InFlightFiles.active.collectAsState()
    val downloading = run {
        val pk = if (msg.to.startsWith("group:")) msg.to else msg.peerKey(selfKey)
        val nm = msg.attachmentName
        xferActive.values.any { it.peerKey == pk && (nm == null || it.fileName == nm) }
    }
    val kind = when {
        mime.startsWith("image/") -> "Photo"
        mime.startsWith("video/") -> "Video"
        mime.startsWith("audio/") -> "Voice note"
        else -> "File"
    }
    var requesting by remember(msg.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clickable(enabled = fileId != null && !downloading && !requesting) {
            val fid = fileId ?: return@clickable
            requesting = true
            scope.launch {
                val transport = AegisApp.instance.transports
                    .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
                val pk = if (msg.to.startsWith("group:")) msg.to else msg.peerKey(selfKey)
                val ok = transport?.receiveDeferredFile(fid, itemId, pk, msg.attachmentName) ?: false
                requesting = false
                if (!ok) {
                    Toast.makeText(context, "File no longer available", Toast.LENGTH_SHORT).show()
                }
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text("⬇ ", fontSize = 16.sp, color = app.aether.aegis.ui.theme.AegisCyan)
            Column(modifier = Modifier.widthIn(max = 220.dp)) {
                Text(
                    if (fileId == null) "$kind unavailable" else kind,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val sub = buildString {
                    if (size > 0) append(formatBytes(size))
                    if (fileId != null) {
                        if (isNotEmpty()) append(" · ")
                        append(if (downloading || requesting) "Downloading…" else "Tap to download")
                    }
                }
                if (sub.isNotBlank()) {
                    Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (fileId != null && tier == app.aether.aegis.data.TrustTier.UNTRUSTED) {
                    Text(
                        "From an Untrusted contact",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (downloading || requesting) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = app.aether.aegis.ui.theme.AegisCyan,
                )
            }
        }
    }
}

/** Bubble placeholder for an attachment whose ciphertext can't be
 *  decrypted right now — typically because the session is locked
 *  (priv unavailable) but also covers wrong-key / corrupted-blob
 *  edge cases. Same outer shape as [FileChip] so the bubble layout
 *  doesn't jump on lock/unlock. */
@Composable
private fun LockedAttachmentChip(mime: String, size: Long) {
    val label = when {
        mime.startsWith("image/") -> "Photo (locked)"
        mime.startsWith("audio/") -> "Voice note (locked)"
        mime.startsWith("video/") -> "Video (locked)"
        else                      -> "Attachment (locked)"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                "🔒 ", fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text(
                    label,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (size > 0) {
                    Text(
                        formatBytes(size),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Non-previewable attachment row (PDF, APK, ZIP, …). Renders purely
 * from row metadata — name/mime/size — and resolves the openable file
 * only when TAPPED. For a [sealed] row that resolve is a full AES-GCM
 * decrypt-to-temp, which we run on Dispatchers.IO: doing it eagerly at
 * render time (the old behaviour) decrypted the entire file just to
 * draw the chip and froze the chat for seconds on a large one (the
 * 48 MB APK open-after-unlock crash). While the tap-time decrypt is in
 * flight we show a one-shot "Opening…" toast so the tap feels alive.
 */
@Composable
private fun FileChip(
    msg: Message,
    name: String,
    mime: String,
    size: Long,
    sealed: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember(msg.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clickable(enabled = !opening) {
            opening = true
            scope.launch {
                // Resolve the openable plaintext path off the main
                // thread. For an unsealed row this is a cheap passthrough;
                // for a sealed row it's the heavy decrypt that must not
                // block the UI. Returns null while locked / on failure.
                val viewable = withContext(Dispatchers.IO) {
                    AegisApp.instance.repository.viewableAttachmentPath(msg, context)
                }
                opening = false
                if (viewable == null) {
                    Toast.makeText(
                        context,
                        if (sealed) "Unlock to open this file" else "File unavailable",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                // The path resolved, but the bytes may not actually be on
                // disk yet — an inbound transfer still downloading, or a
                // received file whose XFTP fetch never completed. Opening a
                // missing/empty file just fails silently in the target app
                // (this is why mugshots showed "a progress bar then nothing").
                // Tell the user which case it is instead of doing nothing.
                val srcFile = java.io.File(viewable)
                if (!srcFile.exists() || srcFile.length() == 0L) {
                    Toast.makeText(
                        context, "Still downloading — not ready yet", Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                // getUriForFile throws if the file sits outside the
                // FileProvider's configured roots — guard it so a path
                // mismatch surfaces as a message, not a swallowed crash.
                val uri = runCatching {
                    androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", srcFile,
                    )
                }.getOrNull()
                if (uri == null) {
                    Toast.makeText(
                        context, "Can't open this file location", Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                // Defensive: rows received on builds prior to 475 were
                // stored with mime="application/octet-stream" because the
                // ingest path didn't derive from the file extension.
                // Re-derive at open-time so those old rows are also
                // openable after the upgrade.
                val effectiveMime = if (mime.isBlank() || mime == "application/octet-stream") {
                    val ext = (name.ifBlank { viewable }).substringAfterLast('.', "").lowercase()
                    (if (ext.isNotEmpty())
                        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                     else null) ?: mime.ifBlank { "*/*" }
                } else mime
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, effectiveMime)
                    addFlags(
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
                // ActivityNotFoundException (no installed app handles this
                // MIME) was previously swallowed, so "nothing happens" looked
                // identical to a real failure. Surface it.
                val launched = runCatching { context.startActivity(intent); true }
                    .getOrDefault(false)
                if (!launched) {
                    Toast.makeText(
                        context, "No app installed to open this file type", Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        },
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(
                if (opening) "Opening…" else "$mime · ${formatBytes(size)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Transient placeholder shown while a sealed preview attachment's
 *  decrypt is still running on the IO dispatcher (produceState). Same
 *  outer shape as [FileChip] / [LockedAttachmentChip] so the bubble
 *  doesn't jump when the real preview swaps in. */
@Composable
private fun DecryptingAttachmentChip(mime: String, size: Long) {
    val label = when {
        mime.startsWith("image/") -> "Photo"
        mime.startsWith("audio/") -> "Voice note"
        mime.startsWith("video/") -> "Video"
        else                      -> "Attachment"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
                color = app.aether.aegis.ui.theme.AegisCyan,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    "$label · decrypting…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (size > 0) {
                    Text(
                        formatBytes(size),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private suspend fun sendStagedAttachment(
    peerKey: String,
    caption: String,
    local: app.aether.aegis.util.Attachments.Local,
) {
    // Video shares the PHOTO row type (no dedicated VIDEO MessageType);
    // the bubble renders by MIME. Was VOICE, which mislabeled sent videos.
    val type = when {
        local.mime.startsWith("image/") -> MessageType.PHOTO
        local.mime.startsWith("video/") -> MessageType.PHOTO
        else -> MessageType.FILE
    }
    val msg = AegisApp.instance.repository.recordSentAttachment(
        toKey = peerKey,
        caption = caption,
        attachmentPath = local.path,
        attachmentMime = local.mime,
        attachmentSize = local.size,
        attachmentName = local.name,
        protocol = Protocol.SIMPLEX,
        type = type,
    )
    val transport = AegisApp.instance.transports
        .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
    val ok = transport?.sendFileToContact(
        peerPubkey = peerKey,
        filePath = local.path,
        isImage = local.mime.startsWith("image/"),
        caption = caption,
        // MIME scopes the mandatory metadata scrub (media is scrubbed,
        // documents pass through instead of being silently blocked).
        mime = local.mime,
        // Link the row to its SimpleX itemId so the post-upload seal
        // (sealOutgoingAttachmentByItemId, fired on sndFileComplete) can
        // find it. Without this the sent photo was never sealed at rest —
        // it stayed plaintext and showed through the PIN lock. Also makes
        // sent-attachment delivery/read ticks advance.
        localMessageId = msg.id,
    ) ?: false
    // Surface a failed send — previously the return was ignored, so a
    // blocked/failed attachment just sat in the sender's chat looking sent
    // while never reaching the peer (user-reported "files don't send").
    if (!ok) {
        val why = transport?.lastSendError ?: "Couldn't send the attachment"
        withContext(Dispatchers.Main) {
            Toast.makeText(AegisApp.instance, why, Toast.LENGTH_LONG).show()
        }
    }
    // Piggyback fresh presence on this send too (same as text), so sending
    // a photo also refreshes battery/GPS to Trusted contacts. Debounced.
    app.aether.aegis.services.ProtocolService.requestActivityPresenceRefresh()
}

/**
 * WhatsApp-style centered chip for a call-history row. Body is the JSON
 * Repository.recordCallLog wrote — {video, duration_ms, connected, reason}.
 * Tapping the chip re-fires the call (same media type as the original).
 *
 *   ↗ Voice call · 3:45        outgoing, connected
 *   ↘ Video call · 12:08       incoming, connected
 *   ↗ Voice call · No answer   outgoing, never connected
 *   ↘ Missed video call        incoming, never connected
 */
@Composable
private fun CallLogChip(msg: Message, selfKey: String) {
    val outgoing = msg.from == selfKey
    val peerKey = if (outgoing) msg.to else msg.from
    val parsed = remember(msg.id) {
        runCatching { org.json.JSONObject(msg.content) }.getOrNull()
    }
    val video = parsed?.optBoolean(stringResource(R.string.chat_video)) ?: false
    val connected = parsed?.optBoolean("connected") ?: false
    val durationMs = parsed?.optLong("duration_ms") ?: 0L

    val mediaLabel = if (video) stringResource(R.string.call_video) else stringResource(R.string.call_voice)
    val arrow = if (outgoing) "↗" else "↘"
    val title = when {
        connected           -> "$arrow $mediaLabel · ${formatCallDuration(durationMs)}"
        outgoing            -> "$arrow $mediaLabel · No answer"
        else                -> "↘ Missed $mediaLabel"
    }
    val tint = if (!connected && !outgoing)
        MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant

    val context = LocalContext.current
    val displayName = remember(peerKey) { peerKey.take(12) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            border = androidx.compose.foundation.BorderStroke(1.dp, app.aether.aegis.ui.theme.AegisBorder),
            modifier = Modifier.clickable {
                app.aether.aegis.call.CallManager.placeCall(peer = peerKey, name = displayName, video = video)
            },
        ) {
            Text(
                title,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                color = tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** The one-line call summary ("↗ Video call · No answer") for a CALL_LOG
 *  row, as a plain (non-composable) string so it drives BOTH the bubble body
 *  and the run-width measure. Mirrors the old CallLogChip. */
private fun callLogTitle(msg: Message, selfKey: String, context: android.content.Context): String {
    val outgoing = msg.from == selfKey
    val parsed = runCatching { org.json.JSONObject(msg.content) }.getOrNull()
    val video = parsed?.optBoolean(context.getString(R.string.chat_video)) ?: false
    val connected = parsed?.optBoolean("connected") ?: false
    val durationMs = parsed?.optLong("duration_ms") ?: 0L
    val mediaLabel = if (video) context.getString(R.string.call_video)
                     else context.getString(R.string.call_voice)
    val arrow = if (outgoing) "↗" else "↘"
    return when {
        connected -> "$arrow $mediaLabel · ${formatCallDuration(durationMs)}"
        outgoing  -> "$arrow $mediaLabel · No answer"
        else      -> "↘ Missed $mediaLabel"
    }
}

/** Format a call duration as h:mm:ss or m:ss. */
private fun formatCallDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

/** One render group in the chat list. Same-sender runs of NORMAL bubbles
 *  collapse into [Run] (drawn as ONE glass slab); everything else stays its
 *  own item so the proven single-bubble path is untouched. */
private sealed interface ChatRender {
    data class Sep(val label: String) : ChatRender
    data class Single(val msg: Message) : ChatRender
    data class Run(val msgs: List<Message>) : ChatRender
}

/** Group [rows] (chronological) into render groups: consecutive same-sender
 *  NORMAL messages (a run, per [runMetaById].firstOfRun) become one
 *  [ChatRender.Run]; CALL_LOG / BURN and lone messages stay
 *  [ChatRender.Single]; date separators pass through and break a run. */
private fun buildChatRenders(
    rows: List<ChatRow>,
    runMetaById: Map<String, app.aether.aegis.ui.components.BubbleRunMeta>,
): List<ChatRender> = buildList {
    var run = mutableListOf<Message>()
    fun flush() {
        when (run.size) {
            0 -> {}
            1 -> add(ChatRender.Single(run[0]))
            else -> add(ChatRender.Run(run.toList()))
        }
        run = mutableListOf()
    }
    for (row in rows) when (row) {
        is ChatRow.Sep -> { flush(); add(ChatRender.Sep(row.label)) }
        is ChatRow.Msg -> {
            val m = row.msg
            // CALL_LOG now merges into runs as a side-aligned call bubble;
            // only BURN stands alone (reveal-on-tap).
            val special = m.type == app.aether.aegis.core.MessageType.BURN
            if (special) {
                flush()
                add(ChatRender.Single(m))
            } else {
                // firstOfRun (or no meta) starts a fresh run.
                if (runMetaById[m.id]?.firstOfRun != false) flush()
                run.add(m)
            }
        }
    }
    flush()
}

/** A faint hairline "scratch" between messages in a run — NOT a lit border.
 *  Inset from the slab edges so it reads as etched glass, not a divider. */
@Composable
private fun ScratchSeparator() {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(app.aether.aegis.ui.theme.AegisBorder.copy(alpha = 0.22f)),
        )
    }
}

/**
 * A merged same-sender run rendered as ONE faceted glass slab: a single
 * backdrop carries the fill, border, cyan glow, sheen and tilt edge-light;
 * the message blocks stack on top separated by [ScratchSeparator] hairlines.
 * Each block keeps its own swipe-reply, long-press menu, reactions,
 * timestamp and citation-jump flash — only the chrome is shared.
 *
 * The backdrop is [Modifier.matchParentSize] behind the message column, so
 * it always matches the run's exact bounds (including the last block's
 * reserved tail strip, where the slab draws its speech tail).
 */
@Composable
private fun MessageRun(
    msgs: List<Message>,
    selfKey: String,
    navController: NavController,
    onReply: (Message) -> Unit,
    onForward: (Message) -> Unit,
    onDelete: (Message) -> Unit,
    onEdit: (Message) -> Unit,
    onBurnReveal: (Message) -> Unit,
    onJumpToReply: (Long) -> Unit,
    resolveQuoted: (Long) -> Message?,
    replyChildren: (Long) -> List<Long>,
    runMetaById: (String) -> app.aether.aegis.ui.components.BubbleRunMeta?,
    knownPeers: List<app.aether.aegis.data.KnownPeerEntity>,
    glassSheen: Boolean,
    glassEdgeLight: Boolean,
    flashingId: String?,
) {
    val outgoing = msgs.first().from == selfKey
    val density = androidx.compose.ui.platform.LocalDensity.current
    // One slab shape for the WHOLE run: top facets + bottom facets + tail.
    // remembered (keyed on density + side) so a recomposition doesn't allocate
    // a fresh GenericShape — a NEW shape identity invalidates the cached
    // clip/border outline, forcing the faceted Path to rebuild on the next draw.
    val runShape = remember(density, outgoing) {
        val cutPx = with(density) { app.aether.aegis.ui.components.FACET_CUT_DP.dp.toPx() }
        val tailHPx = with(density) { app.aether.aegis.ui.components.FACET_TAIL_H_DP.dp.toPx() }
        val tailWPx = with(density) { app.aether.aegis.ui.components.FACET_TAIL_W_DP.dp.toPx() }
        val insetPx = with(density) { app.aether.aegis.ui.components.FACET_TAIL_INSET_DP.dp.toPx() }
        app.aether.aegis.ui.components.facetedBubbleShape(
            cut = cutPx, tailH = tailHPx, tailW = tailWPx, inset = insetPx,
            outgoing = outgoing, topCut = true, bottomTail = true,
        )
    }
    val runWidthDp = runMetaById(msgs.first().id)?.let { with(density) { it.widthPx.toDp() } }
    val fill = app.aether.aegis.ui.theme.AegisGlassFill
    // Both sides cyan-derived — NO teal (AegisBorder 0xFF1A3A40 is off-palette
    // for LunaGlass). Outgoing reads a touch brighter (yours), incoming a
    // dimmer cyan, so the side cue survives without a teal border.
    val border = if (outgoing) app.aether.aegis.ui.theme.AegisCyan.copy(alpha = 0.30f)
                 else app.aether.aegis.ui.theme.AegisCyan.copy(alpha = 0.16f)
    val rich = app.aether.aegis.ui.LocalGraphicsRich.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            // Single glass backdrop — sized to the message column behind it.
            // No drop-shadow: the faceted slab shape is CONCAVE (the tail apex
            // is a reflex vertex), so Modifier.shadow can't use the hardware
            // fast path and falls back to a per-frame alpha-mask rasterization —
            // a heavy scroll-jank cost for a glow that barely rendered on a
            // concave outline anyway. The outgoing side cue is carried by the
            // brighter cyan border below (0.30 vs 0.16 alpha).
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(runShape)
                    .background(fill)
                    .then(
                        if (!glassEdgeLight) Modifier.border(
                            androidx.compose.foundation.BorderStroke(1.dp, border), runShape,
                        ) else Modifier,
                    )
                    .glassSheen(shape = runShape, enabled = glassSheen && rich)
                    // Edge-light rim base is the SAME cyan for incoming and
                    // outgoing — it's "real glass", so the same pane at the
                    // same tilt must catch light identically on both sides
                    // (the per-side `border` tint only drives the static,
                    // tilt-off border above). Previously incoming used the
                    // dark teal AegisBorder and outgoing used cyan, so the two
                    // sides lit asymmetrically ("his edges thick, mine thin").
                    .glassEdgeLight(
                        shape = runShape,
                        base = app.aether.aegis.ui.theme.AegisCyan.copy(alpha = 0.30f),
                        enabled = glassEdgeLight && rich,
                    ),
            )
            Column(
                modifier = if (runWidthDp != null) Modifier.width(runWidthDp)
                           else Modifier.widthIn(max = 280.dp),
            ) {
                msgs.forEachIndexed { i, m ->
                    if (i > 0) ScratchSeparator()
                    MessageBubble(
                        msg = m,
                        selfKey = selfKey,
                        navController = navController,
                        knownPeers = knownPeers,
                        onJumpToReply = onJumpToReply,
                        resolveQuoted = resolveQuoted,
                        replyChildren = replyChildren,
                        runMeta = runMetaById(m.id),
                        glassSheen = glassSheen,
                        glassEdgeLight = glassEdgeLight,
                        flashing = m.id == flashingId,
                        chrome = false,
                        onReply = { onReply(m) },
                        onForward = { onForward(m) },
                        onBurnReveal = { onBurnReveal(m) },
                        onDelete = { onDelete(m) },
                        onEdit = { onEdit(m) },
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: Message,
    selfKey: String,
    navController: NavController,
    onReply: () -> Unit = {},
    onForward: () -> Unit = {},
    onDelete: () -> Unit = {},
    onEdit: () -> Unit = {},
    onBurnReveal: () -> Unit = {},
    /** Jump to the message this one quotes — given its simplexItemId. */
    onJumpToReply: (Long) -> Unit = {},
    /** Resolve a quoted message by its simplexItemId so the bubble can
     *  walk the reply chain upward and render an email-style nested
     *  citation stack. Returns null when the ancestor isn't in the
     *  currently-loaded window (chain rendering simply stops there). */
    resolveQuoted: (Long) -> Message? = { null },
    /** The simplexItemIds of messages that REPLY to this one, in
     *  conversation order. Drives the "↳ N replies" forward-jump
     *  affordance. Empty when nothing cites this message. */
    replyChildren: (Long) -> List<Long> = { emptyList() },
    /** Placement of this message within its same-sender run + the run's
     *  uniform bubble width. Null → standalone (top+bottom facets, tail,
     *  natural width) — the safe fallback. */
    runMeta: app.aether.aegis.ui.components.BubbleRunMeta? = null,
    /** Real-glass tilt sheen (experimental) — off unless the user enabled
     *  it AND rich graphics are on. */
    glassSheen: Boolean = false,
    /** Real-glass 3D edge-light (experimental) — when the pane tilts, the
     *  cyan rim flares/thins with the grazing angle. Tied to the 3D toggle,
     *  off unless that AND rich graphics are on. Replaces the static border
     *  while active. */
    glassEdgeLight: Boolean = false,
    /** Briefly highlighted because the user just jumped to it. */
    flashing: Boolean = false,
    /** Known peers, COLLECTED ONCE at the ChatScreen level and passed down.
     *  Previously every bubble collected observeKnownPeers() itself — N DB
     *  flow collectors that recomposed the whole visible chat on every
     *  presence/status/tier emission. Hoisted out for perf. */
    knownPeers: List<app.aether.aegis.data.KnownPeerEntity> = emptyList(),
    /** False when this message is one block INSIDE a merged same-sender
     *  [MessageRun] slab — drop the per-bubble glass fill, border, shadow,
     *  sheen, edge-light and per-bubble width (the run's single backdrop
     *  draws all of that once). Swipe-reply, the long-press menu, reactions,
     *  the timestamp and (when flashing) the citation-jump wash stay
     *  per-message. Default true = standalone glass bubble (unchanged). */
    chrome: Boolean = true,
) {
    // Call-history rows render as a normal side-aligned bubble (your calls
    // right, theirs left) so they merge into the same-sender runs instead of
    // fracturing the flow as centred chips. The content branch below swaps
    // the body for the call line ("↗ Video call · No answer").
    val isCallLog = msg.type == app.aether.aegis.core.MessageType.CALL_LOG
    val outgoing = msg.from == selfKey
    // Burn-after-reading bubble. Incoming burns show
    // a fire icon and reveal-on-tap; outgoing show a fire icon with
    // "awaiting receipt" subtitle. Both render in the right column
    // for outgoing / left for incoming via a wrapper Row.
    if (msg.type == app.aether.aegis.core.MessageType.BURN) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        ) {
            BurnBubble(msg = msg, outgoing = outgoing, onReveal = onBurnReveal)
        }
        return
    }
    // Protocol footer reads "aegis" when the SimpleX peer is a
    // confirmed Aegis user (known_peers.isAegis = true after the
    // hello-bootstrap handshake). Same transport carries the bits
    // either way, but the label should reflect the relationship —
    // "aegis" for the Aegis ↔ Aegis case, raw protocol name otherwise.
    val knownPeersForLabel = knownPeers
    val peerForLabel = if (outgoing) msg.to else msg.from
    val peerIsAegisForLabel = knownPeersForLabel
        .firstOrNull { it.publicKey == peerForLabel }?.isAegis == true
    // Resolve a quoted message's sender to a LOCAL display name. Citations
    // must NOT trust the `> sender:` prefix baked into the body at send
    // time — that captured the SENDER device's view of the name (often a
    // raw handle, or the wrong nickname across devices). Resolve from the
    // quoted message's actual `from` against our own known-peer nicknames
    // instead; fall back to the baked prefix only when the message isn't
    // in the loaded window.
    val quoteNameOf: (Message) -> String = { m ->
        when {
            m.from == selfKey -> "you"
            else -> knownPeersForLabel.firstOrNull { it.publicKey == m.from }
                ?.displayName?.takeIf { it.isNotBlank() }
                ?: m.from.removePrefix("simplex:").take(20).ifBlank { "peer" }
        }
    }
    val protocolLabel = when (msg.protocol) {
        Protocol.SIMPLEX -> if (peerIsAegisForLabel) "aegis" else "simplex"
        Protocol.LOCAL -> "queued"
    }
    var menuOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Custom-emote sheet target. When set, a small input lets the user
    // type ANY emoji to react with — Aegis reactions ride our own
    // protocol, so there's no fixed emoji set to honour.
    var showCustomEmote by remember { mutableStateOf(false) }

    // The set of emotes the LOCAL user has already reacted with on this
    // message — drives the custom-emote toggle direction.
    val myReactions = remember(msg.reactionsJson) {
        app.aether.aegis.ui.components.myReactionEmotes(msg.reactionsJson)
    }

    // Fire one reaction on the signed protocol. [add] false retracts a
    // reaction the user already placed. No-ops (with a toast) on rows
    // that predate SimpleX item tracking — there's no cross-device link
    // to target without an itemId.
    fun react(emote: String, add: Boolean) {
        val itemId = msg.simplexItemId
        if (itemId == null) {
            Toast.makeText(
                context,
                "Can't react to messages from before reaction support",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        // Conversation key — groups use msg.to ("group:<uuid>"); 1:1
        // chats resolve to the other party via peerKey(selfKey).
        val chatRef = if (msg.to.startsWith("group:")) msg.to else msg.peerKey(selfKey)
        scope.launch {
            val simplex = AegisApp.instance.transports
                .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                .firstOrNull()
            // Pass the target's DNA so a 1:1 chat-envelope peer is reacted to
            // by DNA (native quote fails on the x.aegis.chat type). Groups +
            // legacy peers fall back to native quote inside sendSignedReaction.
            val ok = simplex?.sendSignedReaction(
                chatRef, itemId, emote, add = add, targetDna = msg.messageDna,
            )
            if (ok != true) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Reaction failed — check Diagnostics → Connection log",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    if (showCustomEmote) {
        app.aether.aegis.ui.components.CustomEmoteDialog(
            onDismiss = { showCustomEmote = false },
            onPick = { emote -> react(emote, add = !myReactions.contains(emote)) },
        )
    }

    if (infoOpen) {
        MessageInfoDialog(msg = msg, outgoing = outgoing, onDismiss = { infoOpen = false })
    }

    val senderLabel = if (outgoing) "you"
                      else msg.from.removePrefix("simplex:").take(20).ifBlank { "peer" }
    val timeLabel = remember(msg.timestamp) { formatMessageTime(msg.timestamp) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    // Faceted LunaGlass slab. Consecutive messages from the same sender
    // collapse into ONE block: top facets only on the first message,
    // bottom facets + the speech tail only on the last, seamless
    // rectangles in between (the 1px border overlap reads as a hairline
    // divider). 45° octagon facets rhyme with the hex avatars; the tail
    // tucks inboard of the bottom facet (option B — all four corners cut).
    val firstOfRun = runMeta?.firstOfRun ?: true
    val lastOfRun = runMeta?.lastOfRun ?: true
    val tailHDp = app.aether.aegis.ui.components.FACET_TAIL_H_DP.dp
    // remembered (keyed on density + side + run-placement) so recomposition
    // doesn't allocate a fresh GenericShape and invalidate the cached outline.
    val bubbleShape = remember(density, outgoing, firstOfRun, lastOfRun) {
        val cutPx = with(density) { app.aether.aegis.ui.components.FACET_CUT_DP.dp.toPx() }
        val tailHPx = with(density) { app.aether.aegis.ui.components.FACET_TAIL_H_DP.dp.toPx() }
        val tailWPx = with(density) { app.aether.aegis.ui.components.FACET_TAIL_W_DP.dp.toPx() }
        val insetPx = with(density) { app.aether.aegis.ui.components.FACET_TAIL_INSET_DP.dp.toPx() }
        app.aether.aegis.ui.components.facetedBubbleShape(
            cut = cutPx, tailH = tailHPx, tailW = tailWPx, inset = insetPx,
            outgoing = outgoing, topCut = firstOfRun, bottomTail = lastOfRun,
        )
    }
    // Uniform width for the whole run (= run's widest line, capped at the
    // measure) so a run is a clean monoblock with no saw-tooth. Null →
    // standalone, natural width.
    val runWidthDp = runMeta?.let { with(density) { it.widthPx.toDp() } }
    // Flash a cyan wash on citation-jump arrival; otherwise the TRANSLUCENT
    // glass fill (85% opaque) so the cosmic background bleeds through —
    // real glass, not an opaque tile.
    val bubbleBg = if (flashing) app.aether.aegis.ui.theme.AegisCyanGlow
                   else app.aether.aegis.ui.theme.AegisGlassFill
    // Outgoing is distinguished by a SOFT cyan edge, not the harsh pure-cyan
    // border that read as a neon outline — a dim cyan (alpha ~0.3) sits a
    // shade above the neutral panel border without shouting. The flash state
    // keeps the full-bright cyan since it's transient + meant to grab the eye.
    val bubbleBorder = when {
        flashing -> app.aether.aegis.ui.theme.AegisCyan
        outgoing -> app.aether.aegis.ui.theme.AegisCyan.copy(alpha = 0.30f)
        else -> app.aether.aegis.ui.theme.AegisBorder
    }

    // Swipe-to-reply. Horizontal drag on the
    // bubble — past 80dp threshold (right for incoming, left for
    // outgoing — towards the user's own column) triggers onReply +
    // a haptic, then springs back. dragOffset is the live offset
    // while the user holds.
    val thresholdPx = with(density) { 80.dp.toPx() }
    val maxDragPx = with(density) { 100.dp.toPx() }
    val dragOffset = remember { androidx.compose.animation.core.Animatable(0f) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val direction = if (outgoing) -1f else 1f   // incoming swipes right, outgoing swipes left

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Run grouping: gap only BEFORE the first message of a run;
            // messages within a run butt together (0 gap) so the run reads
            // as one slab. The last message's tail supplies the trailing
            // space before the next run. When chrome is off the enclosing
            // MessageRun owns the outer gap, so add none here.
            .padding(top = if (firstOfRun && chrome) 6.dp else 0.dp, bottom = 0.dp)
            .pointerInput(msg.id) {
                // Axis disambiguation: only claim the gesture for
                // swipe-to-reply when the FIRST movement past touch-slop is
                // dominantly HORIZONTAL (|dx| > |dy|). detectHorizontalDrag
                // fired as soon as horizontal slop was crossed regardless of
                // vertical motion, so a slightly-off-vertical scroll grabbed
                // the bubble and triggered a reply. Now a near-vertical drag
                // never consumes → it stays a scroll.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var horizontal = false
                    val drag = awaitTouchSlopOrCancellation(down.id) { change, over ->
                        if (kotlin.math.abs(over.x) > kotlin.math.abs(over.y)) {
                            horizontal = true
                            change.consume()
                        }
                    }
                    if (drag != null && horizontal) {
                        horizontalDrag(drag.id) { change ->
                            val delta = change.positionChange().x
                            change.consume()
                            val target = (dragOffset.value + delta)
                                .coerceIn(-maxDragPx, maxDragPx)
                            // Only allow drag in the "reply" direction.
                            val clamped = if (target * direction < 0) 0f else target
                            scope.launch { dragOffset.snapTo(clamped) }
                        }
                        val triggered = kotlin.math.abs(dragOffset.value) >= thresholdPx &&
                            (dragOffset.value * direction) > 0
                        if (triggered) {
                            haptic.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                            )
                            onReply()
                        }
                        scope.launch { dragOffset.animateTo(0f) }
                    }
                }
            }
            .graphicsLayer { translationX = dragOffset.value },
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            // chrome=false → this bubble is a block inside a MessageRun slab:
            // transparent (the run backdrop draws the glass), no border /
            // shadow / sheen / edge-light, and it fills the slab width. The
            // citation-jump flash still washes THIS block so a jumped-to
            // message inside a run lights up on its own.
            val surfaceColor = when {
                chrome -> bubbleBg
                flashing -> app.aether.aegis.ui.theme.AegisCyanGlow
                else -> androidx.compose.ui.graphics.Color.Transparent
            }
            Surface(
                color = surfaceColor,
                shape = if (chrome) bubbleShape else androidx.compose.ui.graphics.RectangleShape,
                // The static rim is dropped while the tilt edge-light owns it,
                // so the border isn't drawn twice. No per-bubble rim at all
                // inside a run — the backdrop carries it.
                border = if (!chrome || glassEdgeLight) null
                         else androidx.compose.foundation.BorderStroke(1.dp, bubbleBorder),
                modifier = Modifier
                    // No drop-shadow: the faceted bubble shape is CONCAVE (tail
                    // apex = reflex vertex), so Modifier.shadow falls off the
                    // hardware fast path to a per-frame alpha-mask raster — a
                    // scroll-jank cost for a glow that barely showed on a
                    // concave outline. Outgoing is cued by its brighter cyan
                    // border instead. (Matches the MessageRun backdrop.)
                    // Fill the slab width inside a run; uniform run width when
                    // grouped standalone; natural (capped) width otherwise.
                    .then(
                        when {
                            !chrome -> Modifier.fillMaxWidth()
                            runWidthDp != null -> Modifier.width(runWidthDp)
                            else -> Modifier.widthIn(max = 280.dp)
                        },
                    )
                    // Real-glass tilt sheen (experimental, gated). Draws a
                    // moving highlight clipped to the faceted shape. Off inside
                    // a run — the backdrop owns the single shared sheen.
                    .glassSheen(
                        shape = bubbleShape,
                        enabled = chrome && glassSheen && app.aether.aegis.ui.LocalGraphicsRich.current,
                    )
                    // Tilt-reactive cyan edge-light — the grazing-angle rim
                    // flare that pairs with the 3D pane tilt. Replaces the
                    // static border (dropped above) while active. Off inside a
                    // run (the backdrop owns the rim).
                    .glassEdgeLight(
                        shape = bubbleShape,
                        base = bubbleBorder,
                        enabled = chrome && glassEdgeLight && app.aether.aegis.ui.LocalGraphicsRich.current,
                    )
                    .combinedClickable(
                        onClick = { },
                        onLongClick = { menuOpen = true },
                    ),
            ) {
                // Bottom tail-space only on the last message of a run — the
                // faceted shape draws the tail in that reserved strip below
                // the content.
                Column(
                    modifier = Modifier.padding(
                        start = 14.dp, end = 14.dp, top = 10.dp,
                        bottom = 10.dp + (if (lastOfRun) tailHDp else 0.dp),
                    ),
                ) {
                    // Group bubbles need a sender name — without it
                    // the user can't tell which member said what.
                    // Only renders for INCOMING messages in a GROUP
                    // chat (msg.to starts with "group:") — direct
                    // chats already have the peer's name in the
                    // header, and our own outgoing bubbles don't
                    // need a label.
                    // Sender label only on the FIRST message of a run — the
                    // rest of the run is the same person.
                    val isGroupConversation = msg.to.startsWith("group:")
                    if (!outgoing && isGroupConversation && firstOfRun) {
                        Text(
                            senderLabel,
                            color = app.aether.aegis.ui.theme.AegisCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    val attachPath = msg.attachmentPath
                    val attachMime = msg.attachmentMime
                    // Deferred attachment: the auto-download gate declined to
                    // pull this file (Wi-Fi-only / type / size / Untrusted
                    // sender), so the row is a placeholder — metadata known,
                    // path still null — offering a tap-to-download. Media-type
                    // rows only; a TEXT row never has a media type with a null
                    // path.
                    val isDeferredMedia = attachPath == null && attachMime != null &&
                        (msg.type == MessageType.PHOTO || msg.type == MessageType.VOICE ||
                            msg.type == MessageType.FILE)
                    if (isDeferredMedia) {
                        DeferredAttachmentChip(msg = msg, mime = attachMime!!, selfKey = selfKey)
                    }
                    if (attachPath != null && attachMime != null) {
                        val attachSize = msg.attachmentSize ?: 0L
                        // Is this row's file PIN-sealed (needs an AES-GCM
                        // decrypt before anything can read it) or already
                        // plaintext on disk?
                        val needsDecrypt = msg.sealedDek != null &&
                            app.aether.aegis.lock.ChatAttachmentSeal.isEncrypted(attachPath)
                        // canUnseal is cheap + synchronous; it tells us
                        // whether the REAL-PIN session can produce the priv.
                        // A sealed row we can't unseal right now → locked chip.
                        val canUnseal = AegisApp.instance.repository.canUnsealAttachments
                        val previewable = attachMime.startsWith("image/") ||
                            attachMime.startsWith("audio/") ||
                            attachMime.startsWith("video/")
                        when {
                            // Sealed but the session can't unseal → static
                            // locked chip, never a decrypt attempt.
                            needsDecrypt && !canUnseal ->
                                LockedAttachmentChip(mime = attachMime, size = attachSize)

                            // Preview types (image / audio / video) need the
                            // decrypted bytes to render. CRITICAL: the decrypt
                            // MUST run off the composition thread. Resolving it
                            // synchronously inside remember{} froze the chat for
                            // seconds on a large sealed file (a 48 MB doc) until
                            // Android ANR-killed it — the open-after-unlock
                            // crash. produceState runs the decrypt on
                            // Dispatchers.IO and re-renders when the path is
                            // ready. Keyed on contentHashCode() (NOT the raw
                            // ByteArray, whose equals() is reference identity)
                            // so a fresh-but-identical sealedDek on every
                            // messages-Flow re-emission doesn't re-trigger the
                            // decrypt every frame (the old ~1 fps receiver bug).
                            previewable -> {
                                val viewable by androidx.compose.runtime.produceState<String?>(
                                    initialValue = if (needsDecrypt) null else attachPath,
                                    key1 = msg.id,
                                    key2 = msg.sealedDek?.contentHashCode(),
                                ) {
                                    if (!needsDecrypt) {
                                        value = attachPath
                                    } else {
                                        value = withContext(Dispatchers.IO) {
                                            AegisApp.instance.repository
                                                .viewableAttachmentPath(msg, context)
                                        }
                                    }
                                }
                                val vp = viewable
                                when {
                                    // Decrypt still in flight (or it failed /
                                    // file vanished) → transient placeholder.
                                    vp == null ->
                                        DecryptingAttachmentChip(mime = attachMime, size = attachSize)
                                    attachMime.startsWith("image/") -> {
                                        // RESERVE the exact display size from the
                                        // stored aspect ratio, so the bubble holds
                                        // its height before Coil decodes and the
                                        // list never reflows / jumps on scroll.
                                        // Fit within 260×240 dp preserving aspect.
                                        // Legacy rows with no stored dims fall back
                                        // to the old unconstrained max bounds.
                                        val iw = msg.attachmentWidth
                                        val ih = msg.attachmentHeight
                                        val sizeMod = if (iw != null && ih != null && iw > 0 && ih > 0) {
                                            val scale = minOf(260f / iw, 240f / ih)
                                            Modifier.size((iw * scale).dp, (ih * scale).dp)
                                        } else {
                                            Modifier.heightIn(max = 240.dp).widthIn(max = 260.dp)
                                        }
                                        coil.compose.AsyncImage(
                                            model = java.io.File(vp),
                                            contentDescription = msg.attachmentName ?: "image",
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = sizeMod
                                                .clip(MaterialTheme.shapes.small)
                                                // combinedClickable so a
                                                // long-press on the image (not
                                                // just the bubble padding) opens
                                                // the menu instead of being
                                                // absorbed as a tap.
                                                .combinedClickable(
                                                    onClick = {
                                                        val encodedPath = java.net.URLEncoder.encode(vp, "UTF-8")
                                                        val nameQuery = msg.attachmentName?.let {
                                                            "?name=" + java.net.URLEncoder.encode(it, "UTF-8")
                                                        } ?: ""
                                                        navController.navigate("photo/$encodedPath$nameQuery")
                                                    },
                                                    onLongClick = { menuOpen = true },
                                                ),
                                        )
                                    }
                                    attachMime.startsWith("audio/") ->
                                        app.aether.aegis.ui.components.VoicePlayer(path = vp)
                                    else ->
                                        VideoBubble(
                                            path = vp,
                                            name = msg.attachmentName,
                                            size = attachSize,
                                            mime = attachMime,
                                        )
                                }
                            }

                            // Non-previewable file (PDF, APK, ZIP, …). A chip
                            // only needs name/mime/size to DRAW — it never needs
                            // the bytes until the user taps to open. So we render
                            // straight from row metadata and defer the decrypt to
                            // the tap handler (off-thread). This is what actually
                            // sank the 48 MB APK: it was a file chip, yet the old
                            // code decrypted the whole thing just to show the row.
                            else ->
                                FileChip(
                                    msg = msg,
                                    name = msg.attachmentName ?: "file",
                                    mime = attachMime,
                                    size = attachSize,
                                    sealed = needsDecrypt,
                                )
                        }
                        // Live transfer progress ON the bubble (not only the
                        // top banner) — match this attachment to an in-flight
                        // SimpleX transfer by conversation key + file name.
                        // Determinate when the size is known, indeterminate
                        // otherwise; vanishes on completion (InFlightFiles
                        // drops the entry).
                        val xferActive by app.aether.aegis.simplex.InFlightFiles
                            .active.collectAsState()
                        val xfer = run {
                            val pk = if (msg.to.startsWith("group:")) msg.to
                                     else msg.peerKey(selfKey)
                            val nm = msg.attachmentName
                            xferActive.values.firstOrNull {
                                it.peerKey == pk && (nm == null || it.fileName == nm)
                            }
                        }
                        if (xfer != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val total = xfer.totalBytes
                            if (total != null && total > 0L) {
                                val frac = (xfer.bytesTransferred.toFloat() / total.toFloat())
                                    .coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { frac },
                                    modifier = Modifier.fillMaxWidth().height(2.dp),
                                    color = app.aether.aegis.ui.theme.AegisCyan,
                                    drawStopIndicator = {},
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(2.dp),
                                    color = app.aether.aegis.ui.theme.AegisCyan,
                                )
                            }
                        }
                        if (msg.content.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                    // Reply preview — extract the WhatsApp-style
                    // `> sender: text\n\n` prefix that the sender
                    // prepends when replying so we can render it as a
                    // proper quoted block instead of leaving the raw
                    // markdown in the body. Body becomes the trailing
                    // remainder (text after the blank line).
                    val (quotedReply, bodyAfterQuote) = remember(msg.content) {
                        parseReplyQuote(msg.content)
                    }
                    if (quotedReply != null) {
                        NestedQuoteChain(
                            msg = msg,
                            level1 = quotedReply,
                            outgoing = outgoing,
                            resolveQuoted = resolveQuoted,
                            nameOf = quoteNameOf,
                            onJumpToReply = onJumpToReply,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    // A revealed SOS recording carries the routing marker
                    // ("[aegis:sos-audio] <file>") as its content — show a
                    // clean label above the voice player instead of the raw
                    // tag, so the recipient knows it's the SOS audio.
                    val isSosAudio = msg.content.startsWith("[aegis:sos-audio]")
                    val effectiveContent = when {
                        // Call bubble — show the call line instead of the raw
                        // JSON payload that CALL_LOG carries as its content.
                        isCallLog -> callLogTitle(msg, selfKey, context)
                        // Header is rendered below as a LunaGlass siren glyph
                        // + label — NOT a raw 🆘 emoji (off-palette, banned).
                        isSosAudio -> ""
                        else -> bodyAfterQuote ?: msg.content
                    }
                    if (isSosAudio) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            app.aether.aegis.ui.components.AegisIcon(
                                icon = app.aether.aegis.ui.components.AegisIcons.Siren,
                                contentDescription = "SOS recording",
                                tint = app.aether.aegis.ui.theme.AegisWarning,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "SOS recording",
                                color = app.aether.aegis.ui.theme.AegisWarning,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    // Geo-URI preview — when the message is the
                    // chat-attach "Location" share ("📍 geo:lat,lng")
                    // surface an inline OSM map preview so the user
                    // sees WHERE instead of having to parse a URI.
                    // Single-tap on the preview launches the OS's
                    // default map app via ACTION_VIEW.
                    val geoMatch = remember(effectiveContent) {
                        // Cheap precheck — only location shares carry "geo:";
                        // skip the regex for every other message (per bubble).
                        if (effectiveContent.contains("geo:")) GEO_URI_REGEX.find(effectiveContent) else null
                    }
                    if (geoMatch != null) {
                        val lat = geoMatch.groupValues[1].toDoubleOrNull()
                        val lng = geoMatch.groupValues[2].toDoubleOrNull()
                        if (lat != null && lng != null) {
                            GeoBubblePreview(lat = lat, lng = lng)
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                    if (effectiveContent.isNotBlank()) {
                        // URLs in the body get rendered as clickable
                        // cyan-underlined spans. We never auto-fetch
                        // OG previews — that would leak the recipient's
                        // IP + "I opened this message" to every URL
                        // host. Per-link "Show preview" affordance
                        // below the bubble fetches on explicit user tap.
                        val annotated = remember(effectiveContent) {
                            buildLinkifiedString(effectiveContent)
                        }
                        val ctx = LocalContext.current
                        if (annotated.getStringAnnotations("URL", 0, annotated.length).isEmpty()) {
                            Text(
                                effectiveContent,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                            )
                        } else {
                            androidx.compose.foundation.text.ClickableText(
                                text = annotated,
                                style = androidx.compose.ui.text.TextStyle(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                ),
                                onClick = { offset ->
                                    annotated.getStringAnnotations("URL", offset, offset)
                                        .firstOrNull()?.let { ann ->
                                            runCatching {
                                                ctx.startActivity(
                                                    android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(ann.item),
                                                    ).addFlags(
                                                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                                    )
                                                )
                                            }
                                        }
                                },
                            )
                            // Opt-in link preview — one card per unique
                            // URL in the message. Tapping fetches and
                            // renders title + description; nothing leaks
                            // to the link host until the user opts in.
                            val urls = remember(annotated) {
                                annotated.getStringAnnotations("URL", 0, annotated.length)
                                    .map { it.item }.distinct()
                            }
                            urls.forEach { url ->
                                Spacer(modifier = Modifier.height(4.dp))
                                LinkPreviewCard(url)
                            }
                        }
                    }
                    // Reaction chips — shared with group chat. Tapping a
                    // chip toggles the user's own reaction without
                    // reopening the menu.
                    app.aether.aegis.ui.components.ReactionChipsRow(
                        reactionsJson = msg.reactionsJson,
                        onToggle = { emote, add -> react(emote, add) },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    // "↳ N replies" — forward complement of citation-chase:
                    // this message is quoted by N others. Tapping cycles
                    // through them, jumping (and flashing) each in turn;
                    // each hop pushes the back-stack so ↩ returns here.
                    val replyKids = msg.simplexItemId?.let { replyChildren(it) }.orEmpty()
                    if (replyKids.isNotEmpty()) {
                        var cycle by remember(msg.id, replyKids.size) { mutableStateOf(0) }
                        Text(
                            "↳ ${replyKids.size} ${if (replyKids.size == 1) "reply" else "replies"}",
                            color = app.aether.aegis.ui.theme.AegisCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                .clickable {
                                    val target = replyKids[cycle % replyKids.size]
                                    cycle++
                                    onJumpToReply(target)
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    // Timestamp + delivery ticks once per run, on the LAST
                    // message — the run's latest status is what matters, and
                    // a time label between every stacked message would
                    // shatter the slab.
                    if (lastOfRun) Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Timestamp first — was being computed but never
                        // rendered (bug surfaced in the wild: chat bubbles
                        // had no time label at all). Format degrades
                        // gracefully today → "HH:mm", yesterday → label,
                        // else → "d MMM HH:mm".
                        Text(
                            if (msg.edited) "$timeLabel · edited" else timeLabel,
                            fontSize = 10.sp,
                            color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            protocolLabel,
                            fontSize = 10.sp,
                            color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                        )
                        if (outgoing) {
                            // Delivery-tick ladder (status → glyphs / per-tick
                            // colour). The COUNT of ticks = "reached their
                            // device" (1 = in transit, 2 = delivered, the
                            // universal convention); BRIGHTNESS = the Aegis
                            // progression after arrival (dim = mechanical, bright
                            // = sealed/read). Every step adds visual weight,
                            // nothing dims:
                            //   sending    ⬡ clock (dim)  — handed off, NOT yet
                            //              off the device (sndSent not fired);
                            //              offline it honestly sits here
                            //   sent       ✓   dim            — left the device
                            //              to a relay (SimpleX sndSent)
                            //   delivered  ✓✓  dim            — reached their
                            //              device ([aegis:delivered])
                            //   sealed     ✓✓  bright + dim   — sealed at rest in
                            //              their vault ([aegis:sealed])
                            //   read       ✓✓  bright + bright — they opened it
                            //              ([aegis:read])
                            //   error      !   error colour   — send failed
                            // Built as one AnnotatedString so the two ticks can
                            // carry different colours within a single inline run.
                            val bright = app.aether.aegis.ui.theme.AegisCyan
                            val dim = app.aether.aegis.ui.theme.AegisCyanDim
                            if (msg.status == "sending") {
                                // Pending: vector hex clock (LunaGlass — no emoji,
                                // no curves), NOT a tick. Means "not gone yet".
                                Spacer(modifier = Modifier.width(6.dp))
                                app.aether.aegis.ui.components.PendingHexClock(
                                    color = dim,
                                    glyphSize = 11.dp,
                                )
                            } else {
                            val ticks: androidx.compose.ui.text.AnnotatedString? =
                                when (msg.status) {
                                    "sent"      -> tickRun("✓" to dim)
                                    "delivered" -> tickRun("✓" to dim, "✓" to dim)
                                    "sealed"    -> tickRun("✓" to bright, "✓" to dim)
                                    "read"      -> tickRun("✓" to bright, "✓" to bright)
                                    "error"     -> tickRun("!" to MaterialTheme.colorScheme.error)
                                    else        -> null
                                }
                            if (ticks != null) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(ticks, fontSize = 10.sp)
                            }
                            } // end else (non-pending tick ladder)
                        }
                    }
                }
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_save_to_notes)) },
                    onClick = {
                        menuOpen = false
                        val body = "[$senderLabel · $timeLabel]\n${msg.content}"
                        scope.launch {
                            // Routes through saveChatMessageToVault to
                            // handle the cross-vault re-encryption for
                            // PIN-sealed chat attachments (decrypt with
                            // PinSession.priv, re-encrypt under the
                            // vault key). Returns null only when the
                            // PIN session is locked AND the attachment
                            // is encrypted; surfaces as a clearer
                            // toast in that case.
                            val saved = AegisApp.instance.repository.saveChatMessageToVault(
                                msg = msg,
                                body = body,
                                context = context,
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    if (saved != null) "Saved to Notes" else "Unlock Aegis to save this attachment",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
                val attachmentPath = msg.attachmentPath
                if (attachmentPath != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_save_to_device)) },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    // PIN-encrypted attachments: decrypt
                                    // to a scratch file first, then copy
                                    // to Downloads. Unlocked-session-only
                                    // — locked sessions get null and we
                                    // skip the save.
                                    val viewable = AegisApp.instance.repository
                                        .viewableAttachmentPath(msg, context)
                                        ?: return@withContext false
                                    saveAttachmentToDownloads(
                                        context,
                                        java.io.File(viewable),
                                        msg.attachmentName,
                                        msg.attachmentMime ?: "application/octet-stream",
                                    )
                                }
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        if (ok) "Saved to Downloads/Aegis" else "Save failed",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy)) },
                    onClick = {
                        menuOpen = false
                        copyToClipboard(context, "message", msg.content)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_reply)) },
                    onClick = { menuOpen = false; onReply() },
                )
                DropdownMenuItem(
                    text = { Text(if (msg.pinned) stringResource(R.string.contact_unpin) else "Pin") },
                    onClick = {
                        menuOpen = false
                        scope.launch {
                            AegisApp.instance.repository.setMessagePinned(
                                msg.id, !msg.pinned,
                            )
                        }
                    },
                )
                if (outgoing) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                }
                // Per-message Info — delivery/seal/read timestamps, size, and
                // diagnostic ids (SimpleX-style "message info").
                DropdownMenuItem(
                    text = { Text("Info") },
                    onClick = { menuOpen = false; infoOpen = true },
                )
                // Reaction palette — shared with group chat (single
                // source of truth in ReactionUi). Any emote is allowed;
                // a tap toggles the local user's reaction, "＋" opens the
                // custom-emote entry.
                app.aether.aegis.ui.components.ReactionPickerRow(
                    reactionsJson = msg.reactionsJson,
                    onReact = { emote, add -> menuOpen = false; react(emote, add) },
                    onCustom = { menuOpen = false; showCustomEmote = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_forward)) },
                    onClick = { menuOpen = false; onForward() },
                )
                // "Delete for me" stays local; "Delete for everyone"
                // additionally broadcasts a /_delete item …
                // broadcast through SimpleX so the receiver's bubble
                // turns into the "this message was deleted" tombstone.
                // The for-everyone option only renders for OUTGOING
                // messages — you can't broadcast-delete someone
                // else's message; their SimpleX core would reject it.
                // Also requires a simplexItemId — pre-SimpleX rows
                // (legacy, queued) can't be deleted remotely.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onDelete() },
                )
                val itemIdForBroadcast = msg.simplexItemId
                if (outgoing && itemIdForBroadcast != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.chat_delete_for_everyone),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            val chatRef = if (msg.to.startsWith("group:")) msg.to
                                          else msg.peerKey(selfKey)
                            scope.launch {
                                val simplex = AegisApp.instance.transports
                                    .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                                    .firstOrNull()
                                simplex?.deleteMessageForEveryone(
                                    chatRef, itemIdForBroadcast, targetDna = msg.messageDna,
                                )
                                // Fall through to local delete regardless of
                                // the broadcast outcome — the user's intent
                                // was "remove this from MY view", and the
                                // peer's tombstone is best-effort on top.
                                onDelete()
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Picks a target peer to forward [message] to. Lists every paired
 * known peer plus the user's own self-chat. Tapping a row commits
 * the forward via [onPicked].
 */
@Composable
private fun ForwardDialog(
    message: Message,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val peers by AegisApp.instance.repository.observeKnownPeers().collectAsState(initial = emptyList())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_forward_to)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (peers.isEmpty()) {
                    Text(stringResource(R.string.chat_no_contacts_paired_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    peers.forEach { p ->
                        ListItem(
                            headlineContent = { Text(p.displayName) },
                            modifier = Modifier.clickable { onPicked(p.publicKey) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private suspend fun forwardMessage(toKey: String, msg: Message) {
    // Local val: Message moved to :core:transport, so its nullable
    // attachmentMime can't smart-cast across the module edge.
    val attachMime = msg.attachmentMime
    if (msg.attachmentPath != null && attachMime != null) {
        // PIN-encrypted attachments: decrypt to a scratch file
        // first — the recipient's PIN keypair isn't ours, so
        // forwarding ciphertext sealed under OUR pubkey is useless
        // to them. Locked session returns null and the forward
        // silently drops, which is the right UX (the user has to
        // unlock to forward). Run on IO: this is invoked from
        // chatScope.launch (Main dispatcher), and the decrypt of a
        // large file would otherwise block the UI thread.
        val viewablePath = withContext(Dispatchers.IO) {
            AegisApp.instance.repository.viewableAttachmentPath(
                msg, AegisApp.instance.applicationContext,
            )
        } ?: return
        val local = app.aether.aegis.util.Attachments.Local(
            path = viewablePath,
            mime = attachMime,
            size = msg.attachmentSize ?: 0L,
            name = msg.attachmentName,
        )
        sendStagedAttachment(toKey, msg.content, local)
    } else {
        AegisApp.instance.protocolManager.sendMessage(
            to = toKey,
            content = msg.content,
        )
    }
}

/* --------------------------------------------------------------------
 * Chat-history date separators
 * ------------------------------------------------------------------ */

/** A row in the chat history — either a date separator label or a
 *  message bubble. Sealed-class so LazyColumn can switch on type. */
private sealed class ChatRow {
    data class Sep(val label: String) : ChatRow()
    data class Msg(val msg: app.aether.aegis.core.Message) : ChatRow()
}

/** Today / Yesterday / Monday / 22 May / 22 May 2024 — the same
 *  cadence Telegram/Signal use to group chat days. */
/** Cheap local-calendar-day bucket for [ts] (days since epoch in the device's
 *  current timezone). Allocation-free hot path for the chat grouping — used to
 *  detect day boundaries without the expensive [dayLabel] formatting. The TZ
 *  offset is taken at [ts] so DST shifts land on the right day. */
private fun localEpochDay(ts: Long): Long {
    val offset = java.util.TimeZone.getDefault().getOffset(ts).toLong()
    return Math.floorDiv(ts + offset, 86_400_000L)
}

private fun dayLabel(ts: Long): String {
    val now = System.currentTimeMillis()
    val msgCal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = msgCal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR) &&
        msgCal.get(java.util.Calendar.DAY_OF_YEAR) == nowCal.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today"
    val yesterdayCal = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        add(java.util.Calendar.DAY_OF_YEAR, -1)
    }
    val yesterday = msgCal.get(java.util.Calendar.YEAR) == yesterdayCal.get(java.util.Calendar.YEAR) &&
        msgCal.get(java.util.Calendar.DAY_OF_YEAR) == yesterdayCal.get(java.util.Calendar.DAY_OF_YEAR)
    if (yesterday) return "Yesterday"
    val sevenAgo = now - 7L * 86_400_000L
    val sameYear = msgCal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR)
    val fmt = when {
        ts > sevenAgo -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
        sameYear      -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
        else          -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
    }
    return fmt.format(java.util.Date(ts))
}

/** Per-message Info dialog (SimpleX-style "message info"): the receipt ladder
 *  with exact timestamps, the message size, and diagnostic ids. For an OUTGOING
 *  message the ladder is sent → delivered → sealed → read (a rung shows "—"
 *  until its receipt lands); an INCOMING message just shows when it was
 *  received. Read-only — purely informational / for diagnostics. */
@Composable
private fun MessageInfoDialog(msg: Message, outgoing: Boolean, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Message info") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (outgoing) {
                    MessageInfoRow("Sent", fullDateTime(msg.timestamp))
                    MessageInfoRow("Delivered ✓", msg.deliveredAt?.let { fullDateTime(it) } ?: "—")
                    MessageInfoRow("Sealed ✓✓", msg.sealedAt?.let { fullDateTime(it) } ?: "—")
                    MessageInfoRow("Read", msg.readAt?.let { fullDateTime(it) } ?: "—")
                } else {
                    MessageInfoRow("Received", fullDateTime(msg.timestamp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                MessageInfoRow("Text", "${msg.content.length} chars")
                msg.attachmentSize?.let { MessageInfoRow("Attachment", humanBytes(it)) }
                Spacer(modifier = Modifier.height(8.dp))
                MessageInfoRow("Type", msg.type.name)
                MessageInfoRow("Transport", msg.protocol.name)
                MessageInfoRow("Status", msg.status)
                msg.messageDna?.let { MessageInfoRow("DNA", it.toString()) }
                msg.simplexItemId?.let { MessageInfoRow("SimpleX id", it.toString()) }
                MessageInfoRow("Row id", msg.id)
            }
        },
    )
}

/** One label/value line in the message-info dialog. */
@Composable
private fun MessageInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            modifier = Modifier.width(96.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            value,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
        )
    }
}

/** Full date-time for the info dialog (one-shot, not a hot path). */
private fun fullDateTime(ts: Long): String =
    java.text.SimpleDateFormat("d MMM yyyy, HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(ts))

/** Human-readable byte size for the info dialog. */
private fun humanBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / (1024.0 * 1024.0))
}

@Composable
private fun DateSeparator(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = app.aether.aegis.ui.theme.AegisSurface,
                    shape = MaterialTheme.shapes.small,
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                label,
                color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/* --------------------------------------------------------------------
 * Schedule dialog (long-press send / dedicated ⏰ button)
 * ------------------------------------------------------------------ */

@Composable
private fun ScheduleDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val now = System.currentTimeMillis()
    val tomorrow9am = run {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 9)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        cal.timeInMillis
    }
    val presets = listOf(
        "In 5 minutes"        to (now + 5L * 60_000),
        "In 1 hour"           to (now + 60L * 60_000),
        "In 8 hours"          to (now + 8L * 60L * 60_000),
        "Tomorrow at 09:00"   to tomorrow9am,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_schedule_message)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.chat_pick_when_to_send) +
                        "after the scheduled time.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                presets.forEach { (label, ts) ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onPick(ts) },
                    ) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Call action icon — long-press to fire, tap teaches the gesture.
 *
 * Hold-to-execute protects against accidental
 * triggers (the call icons are tiny and easy to brush against while
 * scrolling). Tapping fires a brief toast that teaches the gesture
 * — solves discoverability too: users learn the icon does something
 * by trying it.
 *
 * Falls back to a plain tap when the hold-to-execute store has the
 * feature disabled, so the user can opt out of the gesture entirely.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CallActionIcon(
    @androidx.annotation.DrawableRes icon: Int,
    label: String,
    onFire: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { app.aether.aegis.gesture.HoldToExecuteStore(context) }
    val holdEnabled = remember { store.enabled }
    // Earlier the call icons used combinedClickable.onLongClick to
    // approximate hold-to-execute: a single threshold fire with no
    // visual feedback. When the user has Hold-to-execute enabled
    // they expect the same Edge-Heat hex animation as the sos
    // button. Routing through HoldToExecuteHex gives that. When
    // the global hold pref is OFF, HoldToExecuteHex falls back to
    // an instant-tap HexShape automatically.
    app.aether.aegis.ui.components.HoldToExecuteHex(
        size = 38.dp,
        borderColor = app.aether.aegis.ui.theme.AegisCyan,
        fillColor = androidx.compose.ui.graphics.Color.Transparent,
        heatColor = app.aether.aegis.ui.theme.AegisCyan,
        forceHold = false,
        hapticOnPress = true,
        onExecute = { onFire() },
    ) {
        AegisIcon(icon, label, tint = app.aether.aegis.ui.theme.AegisCyan)
    }
}

/**
 * Turn plain text into an AnnotatedString with cyan-underline spans
 * over any URLs (http / https / www / domain.tld patterns). Caller
 * uses ClickableText + getStringAnnotations("URL") to dispatch taps.
 * No network calls — link-preview fetch would leak the user's IP to
 * every host mentioned in chat history, so we just make URLs
 * tappable, with no link-preview fetch (a privacy decision).
 */
/**
 * Build the delivery-tick run as a single [AnnotatedString] so each tick
 * glyph can carry its own colour — the 'sealed' state, for instance, is one
 * bright tick followed by one dim tick, which a single-colour Text can't
 * express. Each vararg pair is (glyph, colour), appended left to right.
 * Kept tiny and allocation-light: it runs in the last-of-run footer of every
 * outgoing bubble.
 */
private fun tickRun(
    vararg glyphs: Pair<String, androidx.compose.ui.graphics.Color>,
): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        for ((glyph, color) in glyphs) {
            val start = length
            append(glyph)
            addStyle(androidx.compose.ui.text.SpanStyle(color = color), start, length)
        }
    }

/** URL matcher, compiled ONCE (was rebuilt on every buildLinkifiedString call —
 *  i.e. per chat bubble per first-composition). */
private val URL_REGEX = Regex(
    "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+|www\\.[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
    RegexOption.IGNORE_CASE,
)

private fun buildLinkifiedString(text: String): androidx.compose.ui.text.AnnotatedString {
    // Cheap precheck: the vast majority of messages contain no URL, so skip the
    // regex scan + annotated-string assembly entirely unless a URL marker is
    // even possible. This runs per bubble as items scroll in; the regex isn't
    // free, and a plain AnnotatedString is what the caller falls back to anyway.
    if (!text.contains("http", ignoreCase = true) && !text.contains("www.", ignoreCase = true)) {
        return androidx.compose.ui.text.AnnotatedString(text)
    }
    return androidx.compose.ui.text.buildAnnotatedString {
        var cursor = 0
        for (match in URL_REGEX.findAll(text)) {
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            val raw = match.value
            val href = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
            val start = length
            append(raw)
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = app.aether.aegis.ui.theme.AegisCyan,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                ),
                start = start,
                end = length,
            )
            addStringAnnotation(tag = "URL", annotation = href, start = start, end = length)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

/**
 * Opt-in link preview card — sits under a chat bubble when its body
 * contains a URL. Before the user taps "Show preview" we make zero
 * network calls. On tap we fetch metadata via LinkPreview, render
 * title + description in a panel-bg card, and cache the snapshot so
 * a re-render doesn't re-fetch.
 */
@Composable
private fun LinkPreviewCard(url: String) {
    var snap by remember(url) {
        mutableStateOf(app.aether.aegis.util.LinkPreview.cached(url))
    }
    var loading by remember(url) { mutableStateOf(false) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(
        color = app.aether.aegis.ui.theme.AegisPanel,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                url.removePrefix("https://").removePrefix("http://").take(80),
                color = app.aether.aegis.ui.theme.AegisCyan,
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            val snapNow = snap
            when {
                snapNow != null -> {
                    val s = snapNow
                    if (!s.title.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            s.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    if (!s.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            s.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                loading -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.chat_loading_preview),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                error != null -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "preview unavailable · $error",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                else -> {
                    Spacer(modifier = Modifier.height(4.dp))
                    androidx.compose.foundation.text.ClickableText(
                        text = androidx.compose.ui.text.AnnotatedString("Show preview"),
                        style = androidx.compose.ui.text.TextStyle(
                            color = app.aether.aegis.ui.theme.AegisCyan,
                            fontSize = 11.sp,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        ),
                        onClick = {
                            loading = true
                            error = null
                            scope.launch {
                                when (val r = app.aether.aegis.util.LinkPreview.fetch(url)) {
                                    is app.aether.aegis.util.LinkPreview.Result.Ok -> {
                                        snap = r.snap
                                        loading = false
                                    }
                                    is app.aether.aegis.util.LinkPreview.Result.Failed -> {
                                        error = r.reason
                                        loading = false
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Copy a chat attachment from the app's private storage into the
 * shared Downloads/Aegis folder so the user can see it in the Files
 * app, share it, back it up, etc.
 *
 * Android 10+: uses MediaStore.Downloads — no permission required,
 * scoped storage handles it.
 * Android 9–: writes directly to /storage/emulated/0/Download/Aegis
 * after WRITE_EXTERNAL_STORAGE grant (manifest declares maxSdk=32).
 */
private fun saveAttachmentToDownloads(
    context: android.content.Context,
    src: java.io.File,
    displayName: String?,
    mime: String,
): Boolean {
    if (!src.exists()) return false
    val name = displayName?.takeIf { it.isNotBlank() }
        ?: ("aegis-" + System.currentTimeMillis() + extFor(mime))
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
            put(
                android.provider.MediaStore.Downloads.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS + "/Aegis",
            )
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return false
        return runCatching {
            resolver.openOutputStream(uri).use { out ->
                if (out == null) return@runCatching false
                src.inputStream().use { it.copyTo(out) }
                true
            } ?: false
        }.also {
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.getOrDefault(false)
    } else {
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS,
            ),
            "Aegis",
        ).apply { mkdirs() }
        val target = java.io.File(dir, name)
        return runCatching {
            src.inputStream().use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            true
        }.getOrDefault(false)
    }
}

/**
 * Banner that surfaces in-flight SimpleX file transfers for the
 * current peer / group. One row per transfer with file name,
 * direction, transferred MB (when known) and a Cancel button. The
 * Cancel button calls `/fcancel <fileId>` via SimpleXTransport; the
 * resulting cancel event clears the entry from [InFlightFiles] and
 * the row disappears.
 */
@Composable
private fun InFlightFilesBanner(peerKey: String) {
    val active by app.aether.aegis.simplex.InFlightFiles.active.collectAsState()
    val entries = remember(active, peerKey) {
        active.values.filter { it.peerKey == peerKey }.sortedBy { it.fileId }
    }
    if (entries.isEmpty()) return
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        entries.forEach { e ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val verb = when (e.direction) {
                        app.aether.aegis.simplex.InFlightFiles.Entry.Direction.Receiving -> "Receiving"
                        app.aether.aegis.simplex.InFlightFiles.Entry.Direction.Sending   -> "Sending"
                    }
                    Text(
                        "$verb · ${e.fileName}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                    val total = e.totalBytes
                    if (total != null && total > 0L) {
                        val frac = (e.bytesTransferred.toFloat() / total.toFloat())
                            .coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            drawStopIndicator = {},
                        )
                        Text(
                            "%.1f / %.1f MB".format(
                                e.bytesTransferred / 1_048_576.0,
                                total / 1_048_576.0,
                            ),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        scope.launch {
                            val tx = AegisApp.instance.transports
                                .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                                .firstOrNull() ?: return@launch
                            tx.cancelFile(e.fileId)
                        }
                    },
                ) { Text(stringResource(R.string.secure_notes_cancel), fontSize = 12.sp) }
            }
        }
    }
}

/**
 * Per-chat disappearing-messages picker. Wraps the shared
 * [app.aether.aegis.ui.components.LogPeriodSlider] in an AlertDialog so the chat
 * overflow menu can fire it. Picker shows Off (snap left) and Never
 * (snap right) with a log-scale interior 1 minute → 1 year, matching
 * the auto-burn settings screen for visual consistency.
 *
 * `currentSeconds` seeds the slider position so the user sees their
 * existing TTL on open. `onPick` fires only after they hit "Apply" —
 * cancelling closes without a write.
 */
@Composable
private fun DisappearingTtlDialog(
    currentSeconds: Long?,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
) {
    var pending by remember { mutableStateOf(currentSeconds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_members_disappearing_messages)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.chat_autoburn_messages_in_this) +
                        "Off keeps them forever; Never is the same as Off.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                app.aether.aegis.ui.components.LogPeriodSlider(
                    valueSeconds = pending,
                    onValueChange = { pending = it },
                    minSeconds = 60.0,
                    maxSeconds = 365.0 * 24 * 3600,
                    instantSeconds = null,
                    neverSeconds = null,
                    allowNever = true,
                    instantLabel = "Off",
                    neverLabel = "Never",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(pending) }) { Text(stringResource(R.string.group_members_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/* ------------------------------------------------------------------
 * Reply quote — WhatsApp-style nested preview inside the bubble.
 * The sender prepends `> sender: text\n\n` to the body when they
 * tap Reply (see send path, ~ChatScreen:1001). On the receive side
 * we parse that prefix back out so the bubble can render a proper
 * quoted block with a left bar + tinted background, rather than
 * leaving raw markdown in the visible text.
 * ------------------------------------------------------------------ */

private data class ParsedQuote(val sender: String, val body: String)

/**
 * Match shape: `> sender: text` on the first line, blank line,
 * then the actual reply body. The sender label allows spaces but
 * stops before a colon; the quoted body runs greedily until the
 * `\n\n` separator. DOT_MATCHES_ALL so quotes can span linebreaks.
 */
private val REPLY_QUOTE_REGEX = Regex(
    """^> ([^:\n]+):[ \t]+([\s\S]*?)\n\n([\s\S]*)$""",
)

private fun parseReplyQuote(content: String): Pair<ParsedQuote?, String?> {
    // The reply prefix is anchored at "> " (see REPLY_QUOTE_REGEX `^> `), so a
    // message that doesn't start with it can't be a quote — skip the regex
    // (this runs per bubble; most messages are not replies).
    if (!content.startsWith("> ")) return null to null
    val match = REPLY_QUOTE_REGEX.find(content) ?: return null to null
    val sender = match.groupValues[1].trim()
    val quoted = match.groupValues[2].trim()
    val remainder = match.groupValues[3]
    return ParsedQuote(sender, quoted) to remainder
}

/**
 * Geo-URI marker the chat-attach "Location" share emits. Body shape:
 *   "📍 geo:LAT,LNG"
 * Captured groups are lat, lng. We render a small inline OSM map
 * preview directly in the bubble so the recipient sees WHERE without
 * having to copy the coordinates into a map app.
 */
private val GEO_URI_REGEX = Regex("""geo:(-?\d+\.\d+),(-?\d+\.\d+)""")

/**
 * Small in-bubble OSM map. Single hex pin centred on (lat, lng).
 * Map starts at zoom 14 (city-block scale); zoomable + pannable via
 * the standard osmdroid handlers. Tap-through to the OS map app via
 * ACTION_VIEW geo: URI so the user can route from their preferred
 * tool. Height capped at 180 dp so a chat with many shared
 * locations doesn't blow the LazyColumn out vertically.
 */
@Composable
private fun GeoBubblePreview(lat: Double, lng: Double) {
    val context = LocalContext.current
    val mapView = remember(lat, lng) {
        org.osmdroid.views.MapView(context).apply {
            setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Same disallow-intercept dance as the SOS dashboard
            // map — LazyColumn intercepts vertical drag otherwise.
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_POINTER_DOWN ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
            overlayManager.tilesOverlay.setColorFilter(
                org.osmdroid.views.overlay.TilesOverlay.INVERT_COLORS,
            )
            isHorizontalMapRepetitionEnabled = true
            minZoomLevel = 1.5
            maxZoomLevel = 19.0
            controller.setZoom(14.0)
            controller.setCenter(org.osmdroid.util.GeoPoint(lat, lng))
            val marker = org.osmdroid.views.overlay.Marker(this).apply {
                position = org.osmdroid.util.GeoPoint(lat, lng)
                // Pre-baked cyan variant — runtime setTint is
                // unreliable on VectorDrawableCompat backends, so
                // we ship a coloured-up vector asset.
                icon = androidx.core.content.ContextCompat
                    .getDrawable(context, app.aether.aegis.R.drawable.ic_aegis_pin_cyan)
                setAnchor(
                    org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                    org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM,
                )
            }
            overlays.add(marker)
        }
    }
    DisposableEffect(mapView) { onDispose { mapView.onDetach() } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .clickable {
                runCatching {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            },
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Recognised media placeholders that the send-side reply path injects
 * for attachment-only originals (see ~ChatScreen:1010). Detecting
 * them on render lets us paint a media chip (icon + label) inside
 * the quoted block instead of dumping the literal "[Photo]" text.
 */
private val PHOTO_TAG = "[Photo]"
private val VOICE_TAG = "[Voice]"
private val VIDEO_TAG = "[Video]"
private val FILE_TAG_PREFIX = "[File: "

/**
 * One reconstructed level of a citation chain: who was quoted, the
 * snapshot text to show, and the source itemId to jump to on tap
 * (null when that ancestor predates SimpleX item ids / isn't loaded).
 */
private data class QuoteLevel(val sender: String, val text: String, val jumpItemId: Long?)

/**
 * Email-style nested citation stack drawn above a reply bubble.
 *
 * Level 1 is the immediate parent, carried inline in this message's
 * `> sender: text` body prefix ([level1]). Deeper levels are recovered
 * by walking [Message.replyToItemId] up the ancestor graph: each
 * ancestor's OWN body prefix is exactly the snapshot of ITS parent, so
 * one hop yields one deeper quote plus the next itemId to follow.
 *
 * Why graph-walk and not one big nested string: the wire body only ever
 * encodes a single level of prefix (the send path deliberately strips
 * the parent's own quote — see ~ChatScreen:1276), so a reply-to-a-reply
 * does not physically contain its grandparent's text. Following the
 * itemId links is the only robust reconstruction, and it keeps every
 * level individually jump-able for citation-chase navigation.
 *
 * Folding: the two newest levels (1 + the first ancestor) stay
 * expanded; anything older collapses behind a "⋯ N earlier" toggle that
 * expands in place on tap. Each level indents a little further to read
 * as a thread. Does NOT fetch anything from the network — it only reads
 * the already-loaded message window via [resolveQuoted].
 */
@Composable
private fun NestedQuoteChain(
    msg: Message,
    level1: ParsedQuote,
    outgoing: Boolean,
    resolveQuoted: (Long) -> Message?,
    nameOf: (Message) -> String,
    onJumpToReply: (Long) -> Unit,
) {
    // Walk ancestors to collect levels 2+. At each hop the current
    // message's prefix is the snapshot of its parent (the next-deeper
    // quote), and its replyToItemId is where that level jumps to. The
    // guard caps runaway / cyclic chains defensively. The sender label is
    // resolved LOCALLY from the quoted message's actual sender (nameOf) —
    // not the baked `> sender:` prefix, which can hold a stale handle —
    // falling back to the prefix only when the message isn't loaded.
    // NB: the walk reads [resolveQuoted], backed by a per-conversation
    // itemId→Message map that fills ASYNCHRONOUSLY as the window loads. If
    // we keyed the memo only on (msg.id, replyToItemId) it would run once on
    // the first (empty-map) composition, cache an empty chain, and never
    // recompute once the parent became resolvable — so nesting silently
    // vanished while level 1 (parsed straight from the body) still showed.
    // Re-key on whether the immediate parent resolves so the walk reruns the
    // instant the window is ready.
    val parentResolvedId = msg.replyToItemId?.let(resolveQuoted)?.id
    val deeper = remember(msg.id, msg.replyToItemId, parentResolvedId) {
        buildList {
            var cur = msg.replyToItemId?.let(resolveQuoted)
            var guard = 0
            while (cur != null && guard < 32) {
                val (q, _) = parseReplyQuote(cur.content)
                if (q == null) break
                val parent = cur.replyToItemId?.let(resolveQuoted)
                val name = parent?.let(nameOf) ?: q.sender
                add(QuoteLevel(name, q.body, cur.replyToItemId))
                cur = parent
                guard++
            }
        }
    }
    // Level 1's sender resolved from the actual quoted message too.
    val level1Sender = msg.replyToItemId?.let(resolveQuoted)?.let(nameOf) ?: level1.sender
    // Collapsed by default — keep the bubble compact; the chain expands
    // only when the reader taps to read older context.
    var expanded by remember(msg.id) { mutableStateOf(false) }
    Column {
        // Level 1 — the immediate parent. Always shown.
        QuotedReplyBlock(
            sender = level1Sender,
            text = level1.body,
            outgoing = outgoing,
            onClick = msg.replyToItemId?.let { id -> { onJumpToReply(id) } },
        )
        // Levels 2+ — show the first one (level 2); fold the rest until
        // the reader expands. take(1) keeps exactly two levels visible
        // when collapsed.
        val visibleDeeper = if (expanded) deeper else deeper.take(1)
        visibleDeeper.forEachIndexed { i, lvl ->
            Spacer(modifier = Modifier.height(2.dp))
            Box(modifier = Modifier.padding(start = (8 * (i + 1)).dp)) {
                QuotedReplyBlock(
                    sender = lvl.sender,
                    text = lvl.text,
                    outgoing = outgoing,
                    onClick = lvl.jumpItemId?.let { id -> { onJumpToReply(id) } },
                )
            }
        }
        // Fold toggle — only when something older than level 2 exists.
        if (deeper.size > 1) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (expanded) "▲ collapse" else "⋯ ${deeper.size - 1} earlier",
                color = app.aether.aegis.ui.theme.AegisCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 16.dp, top = 1.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun QuotedReplyBlock(
    sender: String,
    text: String,
    outgoing: Boolean,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Chamfered (cut) corners, not rounded — the citation block has
            // to rhyme with the octagon message slabs, not read as a pill.
            .clip(androidx.compose.foundation.shape.CutCornerShape(7.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .background(app.aether.aegis.ui.theme.AegisCyanGlowSoft)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left cyan bar — same affordance WhatsApp uses to mark a
        // quoted block. 3dp wide, runs the full height of the quote.
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(app.aether.aegis.ui.theme.AegisCyan),
        )
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                sender,
                color = app.aether.aegis.ui.theme.AegisCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            // Media-placeholder detection: the send-side reply path
            // converts attachment-only originals into "[Photo]" /
            // "[Voice]" / "[Video]" / "[File: name]" placeholders, so
            // we can spot those here and render an icon-plus-label
            // chip rather than the literal text. Plain-text quotes
            // fall through to the default Text render.
            when {
                text == PHOTO_TAG -> MediaChip(app.aether.aegis.ui.components.AegisIcons.Camera, stringResource(R.string.story_screens_photo))
                text == VOICE_TAG -> MediaChip(app.aether.aegis.ui.components.AegisIcons.Mic, "Voice")
                text == VIDEO_TAG -> MediaChip(app.aether.aegis.ui.components.AegisIcons.Play, "Video")
                text.startsWith(FILE_TAG_PREFIX) && text.endsWith("]") ->
                    MediaChip(
                        app.aether.aegis.ui.components.AegisIcons.File,
                        text.removePrefix(FILE_TAG_PREFIX).removeSuffix("]"),
                    )
                else -> Text(
                    text,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MediaChip(@androidx.annotation.DrawableRes icon: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AegisIcon(
            icon = icon,
            contentDescription = label,
            tint = app.aether.aegis.ui.theme.AegisCyan,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Reasonable extension guess from MIME type for attachments where
 *  the sender didn't supply a filename. */
private fun extFor(mime: String): String = when {
    mime.startsWith("image/jpeg") -> ".jpg"
    mime.startsWith("image/png")  -> ".png"
    mime.startsWith("image/webp") -> ".webp"
    mime.startsWith("image/gif")  -> ".gif"
    mime.startsWith("video/mp4")  -> ".mp4"
    mime.startsWith("video/")     -> ".mp4"
    mime.startsWith("audio/mp4")  -> ".m4a"
    mime.startsWith("audio/aac")  -> ".aac"
    mime.startsWith("audio/")     -> ".m4a"
    mime == "application/pdf"     -> ".pdf"
    else -> ".bin"
}

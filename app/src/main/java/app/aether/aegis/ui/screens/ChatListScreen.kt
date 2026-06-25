package app.aether.aegis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.AegisApp
import app.aether.aegis.R
import app.aether.aegis.core.Message
import app.aether.aegis.core.identifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

// ============================================================
// CHAT LIST
// ============================================================

/** Top-of-list tab — contacts vs groups. Separates the two
 *  categories into distinct visual surfaces so the chat list
 *  doesn't conflate "people you know" with "rooms you're in". */
private enum class ChatListTab { Contacts, Groups }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(navController: NavController) {
    val selfKey = AegisApp.instance.identity.deviceId
    val startCall = app.aether.aegis.call.rememberCallStarter(navController)
    // Decoy gate: in duress mode we substitute fake-family data into
    // the SAME chat list rendering. AEGIS branding stays, the 5-tab
    // nav stays, hex avatars stay — only the underlying data is fake.
    // The attacker browses what looks like a normal Aegis chat list.
    val inDuress = AegisApp.instance.lockState.inDuressMode
    // initial = null (not emptyList) so we can tell "still loading" from
    // "genuinely no contacts". The empty-state Cyan mascot must only show once
    // the query has emitted — otherwise it flashes for a frame on (re)entering
    // the chats tab, before the list loads (user report: Cyan shows for a frame).
    val realPeersOrNull by AegisApp.instance.repository.observeKnownPeers().collectAsState(initial = null)
    val peersLoaded = realPeersOrNull != null
    val realPeers = realPeersOrNull ?: emptyList()
    val peers = if (inDuress) app.aether.aegis.decoy.DecoyFixtures.peers() else realPeers
    // Pending 1:1 invite links moved OUT of the chat list — they now live on
    // a dedicated screen surfaced by the Alert Center bell (they used to sit
    // inline here and collided with the empty-state mascot / add-contact hex).
    val groupsOrNull by AegisApp.instance.repository.observeGroups().collectAsState(initial = null)
    val groupsLoaded = groupsOrNull != null
    val groups = groupsOrNull ?: emptyList()
    // Group module master gate.
    // Off by default; the Groups tab below shows an enable card +
    // warning dialog instead of the groups list when this is false.
    // Same prefs instance reads on the SimpleX dispatcher's
    // inbound + outbound paths — single source of truth.
    val groupCtx = androidx.compose.ui.platform.LocalContext.current
    val groupModulePrefs = remember(groupCtx) {
        app.aether.aegis.groups.GroupModulePrefs(groupCtx)
    }
    val groupModuleEnabled by groupModulePrefs.enabledFlow.collectAsState()
    val groupAutoDisableEnabled by groupModulePrefs.autoDisableEnabledFlow.collectAsState()
    val groupAutoDisableMinutes by groupModulePrefs.autoDisableMinutesFlow.collectAsState()
    var groupEnableDialog by remember { mutableStateOf(false) }
    var groupAutoDisableDialog by remember { mutableStateOf(false) }
    val profile = remember { AegisApp.instance.profileStore.snapshot() }
    val members = remember(selfKey, peers) {
        peers.map { peer ->
            app.aether.aegis.core.FamilyMember(
                name = peer.displayName,
                meshIp = peer.publicKey.take(20),
                publicKey = peer.publicKey,
            )
        }
    }
    val avatarByKey: Map<String, String?> = remember(peers) {
        peers.associate { it.publicKey to it.announcedAvatarPath }
    }
    val pending by AegisApp.instance.repository.pendingCount().collectAsState(initial = 0)
    val realLastMsgByPeer by AegisApp.instance.repository.observeLastMessagePerPeer()
        .collectAsState(initial = emptyMap())
    // Conversations with unread inbound messages (peer pubkey or group key).
    // Drives the per-row dot AND the Contacts/Groups inner-tab dots.
    val unread by app.aether.aegis.prefs.UnreadTracker.observe()
        .collectAsState(initial = emptySet())
    val realStatuses by AegisApp.instance.repository.statuses().collectAsState(initial = emptyList())
    val lastMsgByPeer = if (inDuress) app.aether.aegis.decoy.DecoyFixtures.lastMessages() else realLastMsgByPeer
    val statuses = if (inDuress) app.aether.aegis.decoy.DecoyFixtures.statuses() else realStatuses
    val statusByPeer = remember(statuses) { statuses.associateBy { it.peerKey } }

    // Chat folders — chip row above the
    // peer list. Null selectedFolder = "All". Folders are derived
    // from KnownPeerEntity.folder (DISTINCT WHERE NOT NULL); empty
    // when no peer has a folder set, in which case we hide the
    // chip row entirely.
    val folders by AegisApp.instance.repository.observeFolders()
        .collectAsState(initial = emptyList())
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    val folderByKey = remember(peers) {
        peers.associate { it.publicKey to it.folder }
    }
    LaunchedEffect(folders) {
        // If the currently-selected folder was deleted (last peer
        // in it removed), reset to All so the list isn't empty.
        if (selectedFolder != null && selectedFolder !in folders) {
            selectedFolder = null
        }
    }

    val ctx = LocalContext.current
    // Recompute on every recomposition so the banner reflects the
    // current toggle state — cheap, it's a SharedPreferences read.
    var protectionEnabled by remember { mutableStateOf(AegisApp.instance.protectionEnabled) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                protectionEnabled = AegisApp.instance.protectionEnabled
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Update surface lives ONLY in Settings → Updates now. The
        // Settings-tab bottom-nav badge dot (driven by
        // UpdateState.pendingForBadge) signals "something to do".

        // Connection status moved to the Status tab — the chat list used
        // to show its own "SimpleX · Connected" banner alongside another
        // copy in Settings, with no shared source of truth. NetworkCard
        // in Status is now the only place that answers "is the network
        // up". This screen still surfaces the pause-state banner and
        // outbox queue, which are message-flow concerns, not network.
        if (!protectionEnabled) {
            Surface(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.chat_list_shield_paused_messages_and),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        // Service is already alive (foreground notification
                        // is showing the "paused" state) — the broadcast is
                        // the same one the Resume notification action fires.
                        ctx.sendBroadcast(
                            android.content.Intent(app.aether.aegis.services.ProtocolService.ACTION_RESUME)
                                .setPackage(ctx.packageName)
                        )
                        protectionEnabled = true
                    }) {
                        Text(stringResource(R.string.geofence_resume), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (pending > 0) {
            // Tap → Diagnostics, where both halves of "fix/clear"
            // live: HEALTH probes + Restart SimpleX transport +
            // Reset SimpleX core (the "fix"), and Clear outbox
            // queue (the "clear"). Without a tap target the banner
            // was a dead-end notice — user sees something's stuck
            // but has to hunt through Settings → Diagnostics to do
            // anything about it.
            Surface(
                color = app.aether.aegis.ui.theme.AegisWarning.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navController.navigate("diagnostics") },
            ) {
                Text(
                    "$pending message(s) queued — tap to fix or clear",
                    color = app.aether.aegis.ui.theme.AegisWarning,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                )
            }
        }

        // FamilyPresenceBar removed — the
        // per-peer online/offline glow on the contact row's hex
        // avatar already carries that signal, and the strip was
        // wasting vertical space at the top of the chat tab.

        var searchQuery by remember { mutableStateOf("") }
        var searchHits by remember { mutableStateOf<List<Message>>(emptyList()) }
        val searchScope = rememberCoroutineScope()
        LaunchedEffect(searchQuery) {
            // Debounce + run on IO. Empty query clears results.
            if (searchQuery.isBlank()) {
                searchHits = emptyList()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(150)
            searchHits = withContext(Dispatchers.IO) {
                AegisApp.instance.repository.searchMessages(searchQuery.trim())
            }
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.chat_list_search_messages)) },
            singleLine = true,
            // Top flush with the central 8dp header gap (no top inset),
            // so the search field starts at the same Y as every other
            // tab's first element. Bottom keeps a little breathing room.
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        )

        // Stories strip — horizontally-scrolling row of hex avatars at
        // the top of the chat list. "+" tile launches the composer; the
        // rest are peers (and self) with an active 24-h story.
        //
        // Collapsible per user request (2026.06.298): users who don't
        // use stories were burning ~80 dp of vertical screen on every
        // chat-list launch. Collapsed state renders a one-line header
        // chevron that the user taps to re-expand. State persists in
        // ChatListPrefs so the next launch remembers.
        val realStories by AegisApp.instance.repository.observeActiveStories()
            .collectAsState(initial = emptyList())
        val stories = if (inDuress) app.aether.aegis.decoy.DecoyFixtures.stories() else realStories
        // sortPrefs is the same ChatListPrefs instance built below for
        // sort + view-mode; instantiating it inline here too is fine —
        // the underlying SharedPreferences + StateFlows are
        // process-wide singletons so the two `remember` calls share
        // state.
        val storiesPrefs = remember(ctx) { app.aether.aegis.ui.ChatListPrefs(ctx) }
        val storiesCollapsed by storiesPrefs.storiesCollapsedFlow.collectAsState()
        if (searchQuery.isBlank()) {
            StoriesHeader(
                collapsed = storiesCollapsed,
                storyCount = stories.size,
                onToggle = { storiesPrefs.storiesCollapsed = !storiesCollapsed },
            )
            if (!storiesCollapsed) {
                StoriesStrip(
                    stories = stories,
                    selfKey = selfKey,
                    peerNames = remember(peers) { peers.associate { it.publicKey to it.displayName } },
                    onCompose = { navController.navigate("story/compose") },
                    onView = { storyId ->
                        val encoded = java.net.URLEncoder.encode(storyId, "UTF-8")
                        navController.navigate("story/$encoded")
                    },
                )
            }
        }

        // Pull-to-refresh: standard Material 3 PullToRefreshBox.
        // Triggers a manual SimpleX reconnect + flushes the outbox so
        // the user can yank the screen to force the network back when
        // things look stuck. Spinner cosmetic, the refresh itself is
        // fast.
        var refreshing by remember { mutableStateOf(false) }
        val refreshScope = rememberCoroutineScope()
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                refreshScope.launch {
                    runCatching {
                        // Restart transports — yanks SimpleX core if
                        // it's in a stuck reconnecting state.
                        AegisApp.instance.protocolManager.stop()
                        AegisApp.instance.protocolManager.start()
                    }
                    kotlinx.coroutines.delay(800)  // visual feedback
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
        // Top-of-list tab switcher — Contacts | Groups. Skipped
        // while a search is active (the search view spans both
        // categories already). Two tabs only, even on a fresh
        // install with zero of either; gives the user a single
        // affordance to find the New / Join surface for each.
        Column(modifier = Modifier.fillMaxSize()) {
        // rememberSaveable so the selected tab survives navigation —
        // tapping a group, viewing the chat, hitting back, should
        // return the user to the Groups tab (not snap them back to
        // Contacts). The default-on-fresh-launch stays Contacts.
        var chatListTab by androidx.compose.runtime.saveable.rememberSaveable {
            mutableStateOf(ChatListTab.Contacts)
        }
        // Protected Mode — Hide Groups removes the group *management*
        // surface (the create / join / disable header, the auto-disable
        // config, the "make a group" empty-state hint) while KEEPING the
        // Groups tab and the existing-conversation list, per the reviewed
        // spec §5b.3: hide management, not the conversations the child is
        // already in. (The separate GROUPS gate disables create/join/leave
        // but leaves them visible; HIDE_GROUPS takes the surface away.)
        val hideGroups = isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.HIDE_GROUPS)
        // Group module dead-man's switch (auto-disable timer):
        // any time the user is NOT on the Groups tab AND the module
        // is on AND the timer is armed, schedule a one-shot
        // WorkManager job. Re-entry to Groups cancels. The job
        // reads the prefs at firing time, so a manual disable in
        // the meantime makes the worker a no-op.
        LaunchedEffect(
            chatListTab,
            groupModuleEnabled,
            groupAutoDisableEnabled,
            groupAutoDisableMinutes,
        ) {
            if (chatListTab == ChatListTab.Groups) {
                app.aether.aegis.groups.GroupModuleAutoDisableWorker.cancel(groupCtx)
            } else if (groupModuleEnabled && groupAutoDisableEnabled) {
                app.aether.aegis.groups.GroupModuleAutoDisableWorker.schedule(
                    groupCtx, groupAutoDisableMinutes,
                )
            }
        }
        // Chat list leaving the composition entirely (user opened a
        // group chat, a setting, etc.). The Groups tab
        // is the engagement signal — once it's gone, the user is
        // by definition not on it. Schedule if appropriate.
        DisposableEffect(Unit) {
            onDispose {
                if (app.aether.aegis.groups.GroupModulePrefs.currentEnabled() &&
                    app.aether.aegis.groups.GroupModulePrefs.currentAutoDisableEnabled()
                ) {
                    app.aether.aegis.groups.GroupModuleAutoDisableWorker.schedule(
                        groupCtx,
                        app.aether.aegis.groups.GroupModulePrefs.currentAutoDisableMinutes(),
                    )
                }
            }
        }
        if (searchQuery.isBlank()) {
            androidx.compose.material3.TabRow(
                selectedTabIndex = chatListTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = app.aether.aegis.ui.theme.AegisCyan,
            ) {
                val contactsHaveUnread = unread.any { !it.startsWith("group:") }
                val groupsHaveUnread = unread.any { it.startsWith("group:") }
                ChatListTab.values().forEach { tab ->
                    val tabHasUnread = when (tab) {
                        ChatListTab.Contacts -> contactsHaveUnread
                        ChatListTab.Groups   -> groupsHaveUnread
                    }
                    androidx.compose.material3.Tab(
                        selected = chatListTab == tab,
                        onClick = { chatListTab = tab },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when (tab) {
                                        ChatListTab.Contacts -> "Contacts"
                                        ChatListTab.Groups   -> "Groups"
                                    },
                                    fontSize = 13.sp,
                                    letterSpacing = 1.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (tabHasUnread) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    UnreadDot()
                                }
                            }
                        },
                    )
                }
            }
            if (chatListTab == ChatListTab.Contacts) {
                ChatSortRow()
            }
        }
        // Hoist sort prefs + computation OUT of the LazyColumn —
        // remember() is forbidden inside LazyListScope.
        val sortPrefs = remember(ctx) { app.aether.aegis.ui.ChatListPrefs(ctx) }
        val sortMode by sortPrefs.sortFlow.collectAsState()
        val sortAscending by sortPrefs.ascendingFlow.collectAsState()
        val sortGroupByTrust by sortPrefs.groupByTrustFlow.collectAsState()
        val sortViewMode by sortPrefs.viewModeFlow.collectAsState()
        val visible = if (selectedFolder == null) members
            else members.filter { m ->
                m.publicKey == selfKey || folderByKey[m.publicKey] == selectedFolder
            }
        val sortedRows = remember(
            visible, peers, lastMsgByPeer, sortMode, sortAscending, sortGroupByTrust,
        ) {
            app.aether.aegis.ui.buildSortedChatList(
                members = visible,
                peers = peers,
                lastMsgByPeer = lastMsgByPeer,
                selfKey = selfKey,
                sortMode = sortMode,
                ascending = sortAscending,
                groupByTrust = sortGroupByTrust,
            )
        }
        // For GRID view, fold consecutive contact rows into N-tile
        // batches. Group headers stand alone (full width). Hoisted
        // out of LazyColumn — remember() can't run in LazyListScope.
        val renderRows: List<Any> = remember(sortedRows, sortViewMode) {
            if (sortViewMode != app.aether.aegis.ui.ChatViewMode.GRID) sortedRows
            else {
                val out = mutableListOf<Any>()
                val buf = mutableListOf<app.aether.aegis.ui.ChatListRow.Contact>()
                fun flushBuf() {
                    if (buf.isEmpty()) return
                    buf.chunked(GRID_COLS).forEach { out += GridRowBatch(it.toList()) }
                    buf.clear()
                }
                sortedRows.forEach { row ->
                    when (row) {
                        is app.aether.aegis.ui.ChatListRow.GroupHeader -> {
                            flushBuf(); out += row
                        }
                        is app.aether.aegis.ui.ChatListRow.Contact -> buf += row
                    }
                }
                flushBuf()
                out
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (searchQuery.isNotBlank()) {
                if (searchHits.isEmpty()) {
                    item {
                        Text(
                            "No matches for \"$searchQuery\"",
                            color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    items(searchHits, key = { it.id }) { hit ->
                        SearchResultRow(hit, navController, selfKey)
                    }
                }
                return@LazyColumn
            }
            // Add Contact row removed — pairing
            // is a one-time action, not worth permanent space at the
            // top of every chat list. Now lives at Settings → Add
            // Contact. The empty-state below routes new users there.
            // Contacts tab ONLY. Without the tab guard this rendered the
            // "Add a contact to get started" Cyan mascot on the GROUPS tab
            // too whenever the user had zero contacts — but the Groups tab
            // has its own "No groups yet" empty state below, so the mascot
            // there was a stray (user-reported). Groups never wants the
            // add-a-contact prompt.
            if (chatListTab == ChatListTab.Contacts && peersLoaded && members.isEmpty()) {
                item {
                    // Skip the Pick chooser ("Invite vs. Accept") for
                    // the first-launch user — they have nobody, so
                    // Invite is what they want. Pick mode is right
                    // for the FAB / radial menu where the user might
                    // be acting on a link they were just sent, but
                    // here it's a dead-end UX: tap → wall of two
                    // tiles → tap → maybe-real flow. One tap less
                    // matters more than the symmetry.
                    //
                    // When CyanGate.enabled AND the cyan_sitting.webp
                    // asset exists, swap in the mascot empty-state
                    // for the empty-states surface.
                    // The Cyan column is clickable on the whole
                    // surface and routes to the same add-contact
                    // flow. Either gate closed = legacy card.
                    val cyanResId = app.aether.aegis.ui.components.rememberCyanResId(
                        app.aether.aegis.ui.components.CyanAsset.Sitting,
                    )
                    if (cyanResId != 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navController.navigate("contact/add/invite")
                                },
                        ) {
                            app.aether.aegis.ui.components.CyanEmptyState(
                                message = "Add a contact to get started.",
                            )
                        }
                    } else {
                        EmptyContactsCard(
                            onClick = { navController.navigate("contact/add/invite") },
                        )
                    }
                }
            }
            // Pending 1:1 invite links no longer live inline here — they
            // collided with the empty-state mascot / add-contact hex on a
            // fresh install. They now have a dedicated screen reached from
            // the Alert Center bell (route "pending-invitations").
            // Folder chip row — only render when at least one peer
            // has a folder tag set, otherwise it's clutter.
            if (folders.isNotEmpty() && chatListTab == ChatListTab.Contacts) {
                item(key = "folder-chips") {
                    FolderChipRow(
                        folders = folders,
                        selected = selectedFolder,
                        onSelect = { selectedFolder = it },
                    )
                }
            }
            // Filter + sort hoisted above LazyColumn — remember()
            // can't run inside LazyListScope. sortedRows is the
            // post-sort + post-group view.
            if (chatListTab == ChatListTab.Contacts)
            items(
                renderRows,
                key = { row ->
                    when (row) {
                        is app.aether.aegis.ui.ChatListRow.GroupHeader -> "hdr-${row.tier.name}"
                        is app.aether.aegis.ui.ChatListRow.Contact     -> row.member.publicKey
                        is GridRowBatch -> "grid-${row.contacts.first().member.publicKey}"
                        else -> "?"
                    }
                },
            ) { row ->
                if (row is app.aether.aegis.ui.ChatListRow.GroupHeader) {
                    ChatGroupHeader(row.tier, row.count)
                    return@items
                }
                if (row is GridRowBatch) {
                    GridRow(
                        row.contacts, peers, statusByPeer, avatarByKey, selfKey,
                        onOpen = { member ->
                            val encoded = URLEncoder.encode(member.identifier, "UTF-8")
                            navController.navigate("chat/$encoded")
                        },
                        onDetails = { member ->
                            if (member.publicKey != selfKey) {
                                val encoded = URLEncoder.encode(member.identifier, "UTF-8")
                                navController.navigate("contact/$encoded")
                            }
                        },
                    )
                    return@items
                }
                val member = (row as app.aether.aegis.ui.ChatListRow.Contact).member
                val peerEntity = peers.firstOrNull { it.publicKey == member.publicKey }
                val lastMsg = lastMsgByPeer[member.publicKey]
                val statusRow = statusByPeer[member.publicKey]
                val now = System.currentTimeMillis()
                val isSelf = member.publicKey == selfKey
                // Use the central peerStatusFor helper from StatusDot.kt
                // so the chat list lands on the SAME presence verdict
                // the chat window does. The previous inline calc only
                // looked at lastActive (inApp foreground stamp) and
                // ignored lastPacketMs (heartbeat), so a peer whose
                // app is alive in background but not foregrounded
                // showed Offline in the chat list while the chat
                // window correctly read Away — user-reported
                // 2026.05.30 ("Pixel grey on chat list, orange in
                // chat window").
                val peerStatus = app.aether.aegis.ui.components.peerStatusFor(
                    status = statusRow,
                    nowMs = now,
                    isSelf = isSelf,
                )
                val tier = peerEntity?.trustTier
                    ?.let { runCatching { app.aether.aegis.data.TrustTier.valueOf(it) }.getOrNull() }
                    ?: app.aether.aegis.data.TrustTier.UNTRUSTED
                // Tier the peer themselves published (via
                // TierBroadcaster). Used to colour the avatar
                // border so we see family members' progress at a
                // glance. Null until they announce or for self.
                val reported = peerEntity?.peerReportedTier
                    ?.let { runCatching { app.aether.aegis.admin.ShieldTier.valueOf(it) }.getOrNull() }
                ContactRow(
                    name = member.name,
                    initial = member.name.take(1).uppercase(),
                    status = peerStatus,
                    verified = peerEntity?.verified == true,
                    muted = peerEntity?.muted == true,
                    lastMessage = lastMsg,
                    unread = !isSelf && unread.contains(member.publicKey),
                    selfKey = selfKey,
                    trustTier = if (isSelf) null else tier,
                    peerReportedTier = if (isSelf) null else reported,
                    peerReportedCrownStyle = if (isSelf) null else peerEntity?.peerReportedCrownStyle,
                    avatarPath = avatarByKey[member.identifier],
                    onClick = {
                        val encoded = URLEncoder.encode(member.identifier, "UTF-8")
                        navController.navigate("chat/$encoded")
                    },
                    onLongClick = {
                        if (!isSelf) {
                            val encoded = URLEncoder.encode(member.identifier, "UTF-8")
                            navController.navigate("contact/$encoded")
                        }
                    },
                    onAvatarClick = if (isSelf) null else {
                        {
                            val encoded = URLEncoder.encode(member.identifier, "UTF-8")
                            navController.navigate("contact/$encoded")
                        }
                    },
                    onCall = if (isSelf) null
                             else { -> startCall(member.identifier, member.name, false) },
                    onVideo = if (isSelf) null
                              else { -> startCall(member.identifier, member.name, true) },
                    compact = sortViewMode == app.aether.aegis.ui.ChatViewMode.COMPACT,
                )
            }

            // Groups section — only renders when the Groups tab is
            // selected. Contacts tab hides this entirely so the two
            // categories live in separate visual surfaces.
            // Groups tab is gated by the group module master gate.
            // Module OFF: show the enable card + warning context.
            // No group list, no group rows, no group affordances.
            // Module ON: show the existing groups header + list.
            if (chatListTab == ChatListTab.Groups && !groupModuleEnabled) {
                item(key = "group-module-disabled") {
                    app.aether.aegis.groups.GroupModuleDisabledCard(
                        onEnableClick = { groupEnableDialog = true },
                    )
                }
            }
            // The management header (Join / New / Disable) is the surface
            // Hide Groups removes — the conversation list below stays.
            if (chatListTab == ChatListTab.Groups && groupModuleEnabled && !hideGroups) {
                item(key = "groups-header") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { navController.navigate("group/join") }) {
                            Text(stringResource(R.string.chat_list_join_via_link), fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        // Frictionless disable — the rule is that
                        // disabling has no confirm. Enabling is what
                        // costs friction.
                        TextButton(onClick = { groupModulePrefs.enabled = false }) {
                            Text(
                                stringResource(R.string.chat_list_disable),
                                fontSize = 12.sp,
                                color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { navController.navigate("group/new") }) {
                            Text(stringResource(R.string.chat_list_new_group), fontSize = 12.sp)
                        }
                    }
                }
                // Auto-disable state row. Tap anywhere to open the
                // configure dialog. Dead-man's-switch UX.
                item(key = "groups-auto-disable") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { groupAutoDisableDialog = true }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (groupAutoDisableEnabled)
                                "Auto-disable after $groupAutoDisableMinutes min inactive"
                            else
                                "Auto-disable: off",
                            fontSize = 11.sp,
                            color = if (groupAutoDisableEnabled)
                                app.aether.aegis.ui.theme.AegisCyan
                            else
                                app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.group_members_configure),
                            fontSize = 11.sp,
                            color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                        )
                    }
                }
                if (groupsLoaded && groups.isEmpty()) {
                    item(key = "groups-empty") {
                        Text(
                            stringResource(R.string.chat_list_no_groups_yet_tap) +
                                "or Join via link to accept an invite.",
                            color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (chatListTab == ChatListTab.Groups && groupModuleEnabled)
            items(groups, key = { it.id }) { group ->
                var groupMenuOpen by remember(group.id) { mutableStateOf(false) }
                var confirmDeleteGroup by remember(group.id) { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                Box {
                    ListItem(
                        headlineContent = { Text(group.name, fontWeight = FontWeight.Bold) },
                        // Part 2: a one-line description subtitle when set
                        // aids telling groups apart without
                        // opening them; falls back to the generic "group".
                        supportingContent = {
                            Text(
                                group.description?.takeIf { it.isNotBlank() } ?: "group",
                                color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        },
                        // Part 1: group's shared avatar, falling back to
                        // the "#" glyph when none is set.
                        leadingContent = {
                            app.aether.aegis.ui.components.GroupAvatar(
                                avatarPath = group.avatarPath,
                            )
                        },
                        trailingContent = {
                            if (unread.contains("group:${group.id}")) UnreadDot()
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = { navController.navigate("group/${group.id}") },
                            onLongClick = { groupMenuOpen = true },
                        ),
                    )
                    DropdownMenu(
                        expanded = groupMenuOpen,
                        onDismissRequest = { groupMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.group_chat_delete_group)) },
                            // Protected Mode (GROUPS): this row-menu delete
                            // would otherwise bypass the gated Leave button.
                            enabled = !isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.GROUPS),
                            onClick = {
                                groupMenuOpen = false
                                confirmDeleteGroup = true
                            },
                        )
                    }
                }
                if (confirmDeleteGroup) {
                    AlertDialog(
                        onDismissRequest = { confirmDeleteGroup = false },
                        title = { Text("Delete \"${group.name}\"?") },
                        text = { Text(stringResource(R.string.chat_list_removes_the_group_and)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmDeleteGroup = false
                                scope.launch { AegisApp.instance.repository.deleteGroup(group.id) }
                            }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDeleteGroup = false }) { Text(stringResource(R.string.action_cancel)) }
                        },
                    )
                }
                HorizontalDivider(color = app.aether.aegis.ui.theme.AegisBorder)
            }
        }
        }  // tab-switcher Column close
        }  // PullToRefreshBox close
    }   // outer Column close
    // Radial add-contact menu — the user's "expanding
    // tree" iteration. Tap the hex FAB to fan out two
    // satellite hexes (Invite / Accept) connected by short cyan
    // lines. Tap the satellite to drop straight into that flow,
    // skipping the picker step. Tap outside or the FAB again to
    // collapse — kills a whole screen for what's a two-option
    // choice.
    AddContactRadialMenu(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 20.dp, bottom = 20.dp),
        onInvite = { navController.navigate("contact/add/invite") },
        onAccept = { navController.navigate("contact/add/accept") },
    )
    }  // Box close

    // Configure the auto-disable dead-man's switch. Toggle
    // arm/disarm + minutes picker (auto-disable timer).
    if (groupAutoDisableDialog) {
        var armed by remember { mutableStateOf(groupAutoDisableEnabled) }
        var minutes by remember { mutableStateOf(groupAutoDisableMinutes.toString()) }
        AlertDialog(
            onDismissRequest = { groupAutoDisableDialog = false },
            title = { Text(stringResource(R.string.group_members_autodisable_timer)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        stringResource(R.string.chat_list_automatically_turn_off_the) +
                            "period of inactivity in the Groups tab. " +
                            "Re-entering the tab resets the timer.",
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.group_members_armed), modifier = Modifier.weight(1f))
                        app.aether.aegis.ui.components.HexSwitch(
                            checked = armed,
                            onCheckedChange = { armed = it },
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { v ->
                            minutes = v.filter { c -> c.isDigit() }.take(4)
                        },
                        label = { Text(stringResource(R.string.group_members_minutes_min_1)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        ),
                        singleLine = true,
                        enabled = armed,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val mins = minutes.toIntOrNull()?.coerceAtLeast(1) ?: 30
                    groupModulePrefs.autoDisableEnabled = armed
                    groupModulePrefs.autoDisableMinutes = mins
                    groupAutoDisableDialog = false
                }) { Text(stringResource(R.string.group_members_save), color = app.aether.aegis.ui.theme.AegisCyan) }
            },
            dismissButton = {
                TextButton(onClick = { groupAutoDisableDialog = false }) {
                    Text(stringResource(R.string.secure_notes_cancel))
                }
            },
        )
    }

    // Group module enable confirm — warning text shows
    // EVERY time you flip it on. No "don't show again" checkbox.
    // Disable is frictionless elsewhere.
    if (groupEnableDialog) {
        AlertDialog(
            onDismissRequest = { groupEnableDialog = false },
            title = { Text(stringResource(R.string.chat_list_enable_group_chat)) },
            text = {
                Text(
                    stringResource(R.string.chat_list_public_groups_increase_your) +
                        "members cannot access your location, SOS, or " +
                        "safety data, but enabling this module exposes " +
                        "additional network endpoints.\n\n" +
                        "You can disable this at any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    groupEnableDialog = false
                    groupModulePrefs.enabled = true
                }) {
                    Text(stringResource(R.string.contact_detail_enable), color = app.aether.aegis.ui.theme.AegisCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { groupEnableDialog = false }) {
                    Text(stringResource(R.string.secure_notes_cancel))
                }
            },
        )
    }
}


/**
 * The add-contact FAB cluster. Collapsed = single 56 dp hex with a
 * `+` glyph. Expanded = the FAB rotates its glyph to `×`, two
 * 44 dp satellite hexes fade in above (Invite) and to the left
 * (Accept), each labelled, connected to the FAB by a short cyan
 * line.
 *
 * Interaction model:
 *   - Tap-and-release on the FAB → toggle expanded (legacy two-tap
 *     flow stays alive as a fallback).
 *   - Press-and-drag toward a satellite → satellite highlights once
 *     the finger enters its sector; release inside the sector fires
 *     the action without a second tap. Release outside collapses
 *     silently. This is the one-gesture flow.
 *
 * Sectors:
 *   - Accept = LEFT of the FAB (angle ≈ 180°). Anything in the left
 *     half-plane (~135°–225°) drives Accept.
 *   - Invite = ABOVE the FAB (angle ≈ 270° in screen coords). Anything
 *     in the top half-plane (~225°–315°) drives Invite.
 *   - Finger inside the FAB's own radius → no satellite armed
 *     (so a press-with-no-drag is the toggle gesture).
 */
@Composable
private fun AddContactRadialMenu(
    modifier: Modifier = Modifier,
    onInvite: () -> Unit,
    onAccept: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // While the user is dragging from a press, this tracks which
    // satellite the finger is currently over (null = none / FAB).
    var armed by remember { mutableStateOf<Satellite?>(null) }
    val cluster = 80.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fabRadiusPx = with(density) { 28.dp.toPx() }      // 56 dp / 2
    val clusterPx = with(density) { cluster.toPx() }

    // Full-screen scrim while expanded (and not in the middle of a
    // drag gesture, where we want pointer events to keep flowing).
    if (expanded && armed == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) { expanded = false },
        )
    }

    Box(modifier = modifier) {
        // Satellite: Accept (scan / paste). Sits LEFT of the FAB.
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
            exit  = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
            modifier = Modifier.offset(x = -cluster, y = 0.dp),
        ) {
            SatelliteHex(
                label = stringResource(R.string.chat_list_accept),
                armed = armed == Satellite.Accept,
                onClick = { expanded = false; armed = null; onAccept() },
            )
        }
        // Satellite: Invite (generate link / QR). Sits ABOVE the FAB.
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
            exit  = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
            modifier = Modifier.offset(x = 0.dp, y = -cluster),
        ) {
            SatelliteHex(
                label = stringResource(R.string.chat_list_invite),
                armed = armed == Satellite.Invite,
                onClick = { expanded = false; armed = null; onInvite() },
            )
        }

        // The FAB itself — primary tap target + drag origin.
        app.aether.aegis.ui.components.HexShape(
            size = 56.dp,
            borderColor = app.aether.aegis.ui.theme.AegisCyan,
            fillColor = app.aether.aegis.ui.theme.AegisCyanGlow,
            glow = true,
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Remember whether the menu was already open BEFORE
                    // the press so a pure tap on an already-open FAB
                    // collapses it (toggle behaviour). The press itself
                    // still opens the menu so drag-out gestures keep
                    // working when starting from the closed state.
                    val wasExpanded = expanded
                    expanded = true
                    armed = null
                    val origin = down.position
                    var dragged = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        val pos = change.position
                        val dx = pos.x - origin.x
                        val dy = pos.y - origin.y
                        val dist = kotlin.math.hypot(dx, dy)
                        // Anything outside the FAB hex but inside a
                        // generous capture ring around the satellites
                        // counts as armed. We use angle, not exact
                        // satellite hex hit-testing, so the user
                        // doesn't have to drag with surgical precision.
                        val newArmed = when {
                            dist < fabRadiusPx -> null
                            else -> {
                                val ang = Math.toDegrees(
                                    kotlin.math.atan2(dy.toDouble(), dx.toDouble())
                                )
                                // Compose Y grows DOWN, so "above the
                                // FAB" is negative dy → atan2 returns
                                // negative angles. Normalise.
                                val norm = ((ang + 360) % 360)
                                when {
                                    // 225°–315° = top half-plane → Invite
                                    norm in 225.0..315.0 -> Satellite.Invite
                                    // 135°–225° = left half-plane → Accept
                                    norm in 135.0..225.0 -> Satellite.Accept
                                    else -> null
                                }
                            }
                        }
                        if (newArmed != armed) armed = newArmed
                        if (dist > fabRadiusPx) dragged = true
                        if (!change.pressed) {
                            // Finger lifted — commit if armed, else
                            // either keep open (pure tap) or collapse
                            // (released-after-drag-into-nothing).
                            when {
                                armed == Satellite.Invite -> {
                                    expanded = false
                                    armed = null
                                    onInvite()
                                }
                                armed == Satellite.Accept -> {
                                    expanded = false
                                    armed = null
                                    onAccept()
                                }
                                !dragged -> {
                                    // Pure tap: TOGGLE. If the FAB was
                                    // already open before this press,
                                    // close it on release. If it was
                                    // closed, leave it open so the
                                    // user can tap a satellite (legacy
                                    // two-tap fallback) or the scrim.
                                    if (wasExpanded) {
                                        expanded = false
                                        armed = null
                                    }
                                }
                                else -> {
                                    expanded = false
                                    armed = null
                                }
                            }
                            break
                        }
                        change.consume()
                    }
                }
            },
        ) {
            // `+` collapsed → `×` expanded. Same glyph rotated 45° via
            // a Text rotation — keeps the visual identity (still the
            // same shape) while signalling "tap me again to close".
            val rotation by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (expanded) 45f else 0f,
                label = stringResource(R.string.chat_list_fabrotate),
            )
            app.aether.aegis.ui.components.AegisIcon(
                icon = app.aether.aegis.ui.components.AegisIcons.Add,
                contentDescription = stringResource(R.string.chat_list_add_contact),
                tint = app.aether.aegis.ui.theme.AegisCyan,
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

private enum class Satellite { Invite, Accept }

/** One arm of the radial menu — 44 dp hex with a label below.
 *  [armed] glows brighter when the user's finger is currently over
 *  its sector, giving live "you're about to fire this one" feedback. */
@Composable
private fun SatelliteHex(label: String, armed: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        app.aether.aegis.ui.components.HexShape(
            size = if (armed) 52.dp else 44.dp,
            borderColor = app.aether.aegis.ui.theme.AegisCyan,
            fillColor = if (armed) app.aether.aegis.ui.theme.AegisCyanGlow
                        else app.aether.aegis.ui.theme.AegisCyanGlow.copy(alpha = 0.4f),
            glow = true,
            onClick = onClick,
        ) {
            Text(
                label.take(1).uppercase(),
                color = app.aether.aegis.ui.theme.AegisCyan,
                fontSize = if (armed) 16.sp else 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label,
            color = app.aether.aegis.ui.theme.AegisCyan,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ProtocolStatusBar removed — see Status tab's NetworkCard for the
// authoritative connection status.

/** "07:02", stringResource(R.string.chat_yesterday), "Mon", "12 May" depending on age. */
private fun formatRelTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 24L * 3_600_000L -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
        diff < 48L * 3_600_000L -> "Yesterday"
        diff < 7L * 86_400_000L -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(ts))
        else                    -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
}

@Composable
private fun SearchResultRow(msg: Message, navController: NavController, selfKey: String) {
    val peerKey = if (msg.from == selfKey) msg.to else msg.from
    val peer by remember(peerKey) {
        mutableStateOf<app.aether.aegis.data.KnownPeerEntity?>(null)
    }.let { state ->
        LaunchedEffect(peerKey) {
            state.value = AegisApp.instance.repository.knownPeerByKey(peerKey)
        }
        state
    }
    val peerName = peer?.displayName ?: peerKey.removePrefix("simplex:")
    val time = remember(msg.timestamp) { formatMessageTime(msg.timestamp) }
    ListItem(
        headlineContent = { Text(peerName, fontWeight = FontWeight.Bold) },
        supportingContent = {
            Column {
                Text(msg.content.take(120), color = MaterialTheme.colorScheme.onSurface)
                Text(time, color = app.aether.aegis.ui.theme.AegisOnSurfaceDim, fontSize = 11.sp)
            }
        },
        modifier = Modifier.clickable {
            val encoded = java.net.URLEncoder.encode(peerKey, "UTF-8")
            navController.navigate("chat/$encoded")
        },
    )
    HorizontalDivider(color = app.aether.aegis.ui.theme.AegisBorder)
}

/**
 * LunaGlass contact row — 44dp hex avatar (cyan glow when online),
 * name + verified ✓ + muted 🔇 inline, status dot + last-message
 * preview, right-aligned relative time. 1dp border-bottom in
 * AegisBorder. Subtle cyan-glow background on tap (via Surface).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    name: String,
    initial: String,
    status: app.aether.aegis.ui.components.PeerStatus,
    verified: Boolean,
    muted: Boolean,
    lastMessage: Message?,
    /** Conversation has unread inbound messages → show a cyan dot. */
    unread: Boolean = false,
    selfKey: String,
    trustTier: app.aether.aegis.data.TrustTier?,
    peerReportedTier: app.aether.aegis.admin.ShieldTier? = null,
    /** Crown shimmer style the peer announced (0 glow / 1 rainbow / 2 oil-
     *  slick), or null if they never have. Renders their Cyan medal in THEIR
     *  chosen style rather than the viewer's local pref. */
    peerReportedCrownStyle: Int? = null,
    /** Absolute path of the peer's announced avatar JPEG, or null when
     *  none is cached. Rendered as a hex-cropped AsyncImage on top of
     *  the glyph layer; falls back to the initial when null or when
     *  the file doesn't exist on disk. */
    avatarPath: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** Tap on the avatar — opens contact info, same as long-press on
     *  the row. Discoverable on tap; long-press is kept as the
     *  power-user shortcut. */
    onAvatarClick: (() -> Unit)? = null,
    onCall: (() -> Unit)? = null,
    onVideo: (() -> Unit)? = null,
    /** Compact density: hides the last-message preview
     *  line, halves vertical padding. Triples contacts-per-screen
     *  for users with many contacts. Same row otherwise — keeps
     *  avatar, name, status dot, verified/muted chips, timestamp. */
    compact: Boolean = false,
) {
    val online = status == app.aether.aegis.ui.components.PeerStatus.Online
    // Trust-tier visual treatment:
    //   Trusted    — full avatar, normal border, cyan when online
    //   Emergency  — red "!" replaces the initial; row carries a red
    //                left-edge accent so it reads "this person's role
    //                is alarms, raise your bar before chatting"
    //   Untrusted  — mask glyph replaces the initial; muted border
    //   null (self / group) — same treatment as Trusted, no badge
    val isEmergency = trustTier == app.aether.aegis.data.TrustTier.EMERGENCY
    val isUntrusted = trustTier == app.aether.aegis.data.TrustTier.UNTRUSTED
    val rowBorderColor = when {
        isEmergency -> app.aether.aegis.ui.theme.AegisEmergency
        else        -> androidx.compose.ui.graphics.Color.Transparent
    }
    // Compact-mode density. The KDoc on the `compact` param has
    // long advertised "halves vertical padding" but the
    // implementation never did it — row height was identical to
    // normal, only the preview row was hidden, so compact just
    // looked like a normal row with missing data (user report
    // 2026.06.207: "compact view is the exact same row height as
    // normal, but just shows less data??"). Halving vertical
    // padding AND shrinking the avatar (44 → 32 dp) cut row
    // height by roughly 40% in that pass.
    //
    // Tightened again 2026.06.298 per user feedback that compact
    // still felt too tall — vertical padding 4 → 2 dp and avatar
    // 32 → 28 dp. Brings compact rows down to ~32 dp tall (vs
    // ~64 dp normal), roughly doubling the contact-per-screen
    // density vs the previous compact setting. Name font already
    // shrinks 16 → 13 sp on the compact path below.
    val rowVerticalPadding = if (compact) 2.dp else 12.dp
    val avatarSize = if (compact) 28.dp else 44.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .let { base ->
                if (isEmergency) base.then(
                    Modifier.drawBehind {
                        // Thin red bar on the left edge — flags the
                        // role at a glance without screaming "alarm".
                        drawRect(
                            color = rowBorderColor,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                            size = androidx.compose.ui.geometry.Size(3f.dp.toPx(), size.height),
                        )
                    }
                ) else base
            }
            // 16dp horizontal to match the 16dp content inset on the
            // Settings + Security tabs, so the primary content lines up
            // edge-wise across all the scrolling tabs
            // (coherent spacing). Was 14dp.
            .padding(horizontal = 16.dp, vertical = rowVerticalPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Canonical avatar — the single shared renderer (metal tier frame +
            // engraved-monogram plate, photo covers the plate when present). The
            // tilt-reactive shine is on by default inside AegisAvatar.
            app.aether.aegis.ui.components.AegisAvatar(
                size = avatarSize,
                tier = peerReportedTier,
                initial = initial,
                avatarPath = avatarPath,
                online = online,
                isEmergency = isEmergency,
                isUntrusted = isUntrusted,
                peerReportedCrownStyle = peerReportedCrownStyle,
                onClick = onAvatarClick,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (compact) {
                        // In compact mode the preview row is gone, so
                        // promote the status dot inline before the
                        // name. Same dot widget; same colour semantics.
                        app.aether.aegis.ui.components.StatusDot(status)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                    )
                    if (verified) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("✓", color = app.aether.aegis.ui.theme.AegisOnline, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    if (muted) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("🔇", fontSize = 11.sp)
                    }
                    // Fills the rest of the name line. The relative time
                    // used to live here (right-pushed), but on a 2-line
                    // row that left it floating diagonally above the call
                    // buttons — it now sits in the trailing column below,
                    // directly in line with them.
                    Spacer(modifier = Modifier.weight(1f))
                    // Unread marker — cyan dot at the right of the name line.
                    if (unread) UnreadDot()
                }
                if (!compact) {
                    Spacer(modifier = Modifier.height(3.dp))
                } else {
                    // Compact mode: status dot moves inline next to
                    // the name (already drawn above), so the preview
                    // row is omitted entirely.
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    app.aether.aegis.ui.components.StatusDot(status)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        lastMessage?.let { lastMsgPreview(it, selfKey) } ?: "—",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }
            }
            // Trailing right section: relative time + the call/video
            // buttons — tap fires the call directly,
            // self-row gets no buttons. The time and the buttons are
            // grouped here so the timestamp aligns to the SAME right
            // edge on every row and sits in line with the buttons,
            // instead of floating on the top text line with seemingly
            // random spacing.
            val timeLabel = lastMessage?.let { formatRelTime(it.timestamp) } ?: ""
            val hasCallButtons = onCall != null && onVideo != null
            // Video left, voice right per messenger convention
            // (WhatsApp, Signal, Telegram). Reordered to match the
            // chat-header inside ChatScreen. Shared by both layouts.
            val callButtons: @Composable () -> Unit = {
                app.aether.aegis.ui.components.HexShape(
                    size = 32.dp,
                    borderColor = app.aether.aegis.ui.theme.AegisCyan,
                    fillColor = androidx.compose.ui.graphics.Color.Transparent,
                    onClick = onVideo,
                ) {
                    app.aether.aegis.ui.components.AegisIcon(
                        icon = app.aether.aegis.ui.components.AegisIcons.Play,
                        contentDescription = stringResource(R.string.chat_list_video_call),
                        tint = app.aether.aegis.ui.theme.AegisCyan,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                app.aether.aegis.ui.components.HexShape(
                    size = 32.dp,
                    borderColor = app.aether.aegis.ui.theme.AegisCyan,
                    fillColor = androidx.compose.ui.graphics.Color.Transparent,
                    onClick = onCall,
                ) {
                    app.aether.aegis.ui.components.AegisIcon(
                        icon = app.aether.aegis.ui.components.AegisIcons.Call,
                        contentDescription = stringResource(R.string.chat_list_voice_call),
                        tint = app.aether.aegis.ui.theme.AegisCyan,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (timeLabel.isNotEmpty() || hasCallButtons) {
                Spacer(modifier = Modifier.width(8.dp))
                if (compact) {
                    // Single dense line: keep the time + buttons inline so
                    // the compact row height (~32dp) is unchanged.
                    if (timeLabel.isNotEmpty()) {
                        Text(
                            timeLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                        if (hasCallButtons) Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (hasCallButtons) {
                        Row(verticalAlignment = Alignment.CenterVertically) { callButtons() }
                    }
                } else {
                    // Two-line row: stack the time directly above the
                    // buttons, both right-aligned, so it lines up with them.
                    Column(horizontalAlignment = Alignment.End) {
                        if (timeLabel.isNotEmpty()) {
                            Text(
                                timeLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                        if (hasCallButtons) {
                            if (timeLabel.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) { callButtons() }
                        }
                    }
                }
            }
        }
    }
    HorizontalDivider(color = app.aether.aegis.ui.theme.AegisBorder, thickness = 1.dp)
}

/** Big, impossible-to-miss empty-state card
 *  ("the builder of the app cannot find [the add-contact button].
 *  Zippy has zero chance"). Centred QR icon + headline + one-line
 *  hint, full-width clickable. Routes to AddContactScreen which
 *  handles the Invite/Accept choice. */
@Composable
private fun EmptyContactsCard(onClick: () -> Unit) {
    app.aether.aegis.ui.components.GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .clickable(onClick = onClick),
        glow = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Big hex with a + glyph — visual match for the FAB so
            // the user learns "this shape = add" in one beat.
            app.aether.aegis.ui.components.HexShape(
                size = 80.dp,
                borderColor = app.aether.aegis.ui.theme.AegisCyan,
                fillColor = app.aether.aegis.ui.theme.AegisCyanGlow,
                glow = true,
            ) {
                app.aether.aegis.ui.components.AegisIcon(
                    icon = app.aether.aegis.ui.components.AegisIcons.Add,
                    contentDescription = null,
                    tint = app.aether.aegis.ui.theme.AegisCyan,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.chat_list_add_your_first_contact),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.chat_list_tap_here_or_the),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
/**
 * Telegram-style folder chip row. "All" + one chip per folder
 * tag in use. Single-select; clicking the active chip deselects
 * back to All. The folder tag itself lives on
 * KnownPeerEntity.folder; assigned via ContactDetailScreen and
 * read back via Repository.observeFolders().
 */
@Composable
private fun FolderChipRow(
    folders: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "folder-all") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.secure_notes_all)) },
            )
        }
        items(folders, key = { "folder-$it" }) { name ->
            FilterChip(
                selected = name == selected,
                onClick = { onSelect(if (name == selected) null else name) },
                label = { Text(name) },
            )
        }
    }
}

/** Number of tiles per row in GRID view mode. 3 is a sane balance
 *  on phone widths — bigger avatars stay legible, names don't
 *  truncate beyond first word. */
private const val GRID_COLS = 3

/** Marker for a chunked row of contact tiles in GRID mode. Kept as
 *  a top-level class so the `items()` `key = { ... }` switch can
 *  pattern-match it alongside the ChatListRow types. */
private data class GridRowBatch(val contacts: List<app.aether.aegis.ui.ChatListRow.Contact>)

/** Renders one row of up to [GRID_COLS] GridContactTiles. Tiles
 *  share `weight(1f)` so the row stays equal-column. Trailing empty
 *  slots are rendered as invisible spacers so the final partial row
 *  doesn't stretch its tiles. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GridRow(
    contacts: List<app.aether.aegis.ui.ChatListRow.Contact>,
    peers: List<app.aether.aegis.data.KnownPeerEntity>,
    statusByPeer: Map<String, app.aether.aegis.data.MemberStatusEntity>,
    avatarByKey: Map<String, String?>,
    selfKey: String,
    onOpen: (app.aether.aegis.core.FamilyMember) -> Unit,
    onDetails: (app.aether.aegis.core.FamilyMember) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        contacts.forEach { row ->
            val member = row.member
            val peerEntity = peers.firstOrNull { it.publicKey == member.publicKey }
            val statusRow = statusByPeer[member.publicKey]
            val now = System.currentTimeMillis()
            val isSelf = member.publicKey == selfKey
            val status = app.aether.aegis.ui.components.peerStatusFor(
                status = statusRow, nowMs = now, isSelf = isSelf,
            )
            val tier = peerEntity?.trustTier
                ?.let { runCatching { app.aether.aegis.data.TrustTier.valueOf(it) }.getOrNull() }
                ?: app.aether.aegis.data.TrustTier.UNTRUSTED
            val reported = peerEntity?.peerReportedTier
                ?.let { runCatching { app.aether.aegis.admin.ShieldTier.valueOf(it) }.getOrNull() }
            // Tap/long-press on the WHOLE cell (the full weighted slot), not
            // just the tightly-wrapped avatar+name column. The avatar is a small
            // 64dp hex centred in a much wider cell, so a column-sized target was
            // a small, easy-to-miss hit area — long-press landed inconsistently.
            // The full-cell target is large and unambiguous.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClick = { onOpen(member) },
                        onLongClick = { onDetails(member) },
                    ),
            ) {
                GridContactTile(
                    name = member.name,
                    initial = member.name.take(1).uppercase(),
                    status = status,
                    trustTier = if (isSelf) null else tier,
                    peerReportedTier = if (isSelf) null else reported,
                    avatarPath = avatarByKey[member.identifier],
                )
            }
        }
        // Pad incomplete trailing row with invisible spacers so the
        // remaining tiles don't stretch to fill leftover space.
        repeat(GRID_COLS - contacts.size) {
            Box(modifier = Modifier.weight(1f))
        }
    }
}

/** Sort + group control row sitting just below the TabRow. Compact:
 *  one button on the left ("Sort: <mode>") that opens a dropdown,
 *  toggles on the right (ascending direction, group-by-trust, view mode).
 *  Reads + writes ChatListPrefs so changes persist across app restarts. */
@Composable
private fun ChatSortRow() {
    val ctx = LocalContext.current
    val prefs = remember(ctx) { app.aether.aegis.ui.ChatListPrefs(ctx) }
    val mode by prefs.sortFlow.collectAsState()
    val ascending by prefs.ascendingFlow.collectAsState()
    val grouped by prefs.groupByTrustFlow.collectAsState()
    val view by prefs.viewModeFlow.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    // Two-row layout. Earlier single-row version crammed the
    // sort dropdown + direction + group-toggle + view-mode chips
    // into one Row with a weight(1f) Spacer; on the narrow Pixel
    // viewport the chips were squeezed to ~zero width and Compose
    // wrapped "Normal" one character per line (user report,
    // 2026.06.202). Split into two rows: sort controls above,
    // view-mode chips on their own row below with guaranteed
    // space.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            androidx.compose.material3.TextButton(onClick = { menuOpen = true }) {
                Text(
                    "Sort: ${mode.label}",
                    fontSize = 12.sp,
                    color = app.aether.aegis.ui.theme.AegisCyan,
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                app.aether.aegis.ui.ChatSortMode.values().forEach { m ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                m.label,
                                fontWeight = if (m == mode) FontWeight.SemiBold
                                             else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            prefs.sortMode = m
                            menuOpen = false
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        // Ascending/descending toggle.
        androidx.compose.material3.TextButton(
            onClick = { prefs.ascending = !ascending },
        ) {
            Text(
                // Mode-aware label so the user sees the actual semantic
                // ("Newest → Oldest" vs "Oldest → Newest" etc.), not a
                // generic ↑/↓ that means different things per mode.
                directionLabel(mode, ascending),
                fontSize = 11.sp,
                color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                // Never wrap — a long label like "Untrusted first" used to
                // squeeze the Grouped toggle into a per-character wrap.
                maxLines = 1,
                softWrap = false,
            )
        }
        // Group-by-trust toggle.
        androidx.compose.material3.TextButton(
            onClick = { prefs.groupByTrust = !grouped },
        ) {
            Text(
                if (grouped) "Grouped" else "Flat",
                fontSize = 11.sp,
                color = if (grouped) app.aether.aegis.ui.theme.AegisCyan
                        else app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
    // View-mode picker on its own row so the chips can't be
    // squeezed by the sort controls above. Right-aligned to keep
    // the visual centre of the screen empty for the LazyColumn
    // content below.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.chat_list_view),
            fontSize = 11.sp,
            color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
            modifier = Modifier.padding(end = 6.dp),
        )
        ViewModeChips(
            current = view,
            onSelect = { prefs.viewMode = it },
        )
    }
    }
}

/**
 * Inline 3-chip view-mode selector — Normal / Compact / Grid.
 * Selected chip renders as filled cyan; the other two stay dim.
 * Tighter than three TextButtons (smaller padding, no ripple
 * overflow) so the row fits alongside the sort controls without
 * wrapping.
 *
 * Lives next to [ChatSortRow] because it shares the same row
 * real estate. Standalone composable rather than inline so the
 * styling rules (selected vs unselected) sit in one place.
 */
@Composable
private fun ViewModeChips(
    current: app.aether.aegis.ui.ChatViewMode,
    onSelect: (app.aether.aegis.ui.ChatViewMode) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        app.aether.aegis.ui.ChatViewMode.values().forEach { mode ->
            val selected = mode == current
            val bg = if (selected) app.aether.aegis.ui.theme.AegisCyanGlow
                     else androidx.compose.ui.graphics.Color.Transparent
            val fg = if (selected) app.aether.aegis.ui.theme.AegisCyan
                     else app.aether.aegis.ui.theme.AegisOnSurfaceDim
            Box(
                modifier = Modifier
                    .padding(horizontal = 1.dp)
                    .clip(androidx.compose.foundation.shape.CutCornerShape(6.dp))
                    .background(bg)
                    .clickable { onSelect(mode) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    mode.label,
                    fontSize = 11.sp,
                    color = fg,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Mode-aware direction label — what "ascending" actually means
 *  depends on the sort mode, so showing "↑" alone is ambiguous. */
private fun directionLabel(mode: app.aether.aegis.ui.ChatSortMode, ascending: Boolean): String =
    when (mode) {
        app.aether.aegis.ui.ChatSortMode.TIME ->
            if (ascending) "Oldest first" else "Newest first"
        app.aether.aegis.ui.ChatSortMode.NAME ->
            if (ascending) "Z → A" else "A → Z"
        app.aether.aegis.ui.ChatSortMode.TRUST ->
            // Trusted is the TOP tier, Untrusted the bottom; Emergency sits
            // between them, so it's never an extreme — the label names the end
            // that actually leads (descending = best trust first = Trusted).
            if (ascending) "Untrusted first" else "Trusted first"
        app.aether.aegis.ui.ChatSortMode.TIER_ACHIEVED ->
            if (ascending) "Lowest first" else "Best first"
        app.aether.aegis.ui.ChatSortMode.VERIFIED ->
            if (ascending) "Unverified first" else "Verified first"
    }

/** Section header inserted between trust-tier groups when groupByTrust
 *  is on. Tier name + count, minimal styling so it reads like a
 *  divider rather than a row. */
@Composable
private fun ChatGroupHeader(tier: app.aether.aegis.data.TrustTier, count: Int) {
    val (label, color) = when (tier) {
        app.aether.aegis.data.TrustTier.EMERGENCY ->
            "Emergency" to app.aether.aegis.ui.theme.AegisEmergency
        app.aether.aegis.data.TrustTier.TRUSTED ->
            "Trusted" to app.aether.aegis.ui.theme.AegisCyan
        app.aether.aegis.data.TrustTier.UNTRUSTED ->
            "Untrusted" to app.aether.aegis.ui.theme.AegisOnSurfaceDim
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            color = color,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            count.toString(),
            color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
            fontSize = 10.sp,
        )
    }
}

/** Badoo-style tile for the GRID view mode. Big square avatar +
 *  name underneath. Tap = open chat; long-press = open contact
 *  details. No preview text, no timestamp, no chips — pure visual
 *  scan. Status visible via the avatar border tint (online/offline
 *  via the avatar's existing glow behaviour). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GridContactTile(
    name: String,
    initial: String,
    status: app.aether.aegis.ui.components.PeerStatus,
    trustTier: app.aether.aegis.data.TrustTier?,
    peerReportedTier: app.aether.aegis.admin.ShieldTier? = null,
    avatarPath: String? = null,
) {
    val online = status == app.aether.aegis.ui.components.PeerStatus.Online
    val isEmergency = trustTier == app.aether.aegis.data.TrustTier.EMERGENCY
    val isUntrusted = trustTier == app.aether.aegis.data.TrustTier.UNTRUSTED
    // Click/long-press is owned by the enclosing full-cell Box (see GridRow);
    // this is just the visual content.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(6.dp)
            .fillMaxWidth(),
    ) {
        // Canonical avatar — one renderer for every surface. Drops the old
        // bespoke gradient/glass fill (which read as the "awful cyan fill"
        // floating inside a fat ring) for the shared metal-plate look.
        app.aether.aegis.ui.components.AegisAvatar(
            size = 64.dp,
            tier = peerReportedTier,
            initial = initial,
            avatarPath = avatarPath,
            online = online,
            isEmergency = isEmergency,
            isUntrusted = isUntrusted,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Small cyan unread marker — a filled 8dp circle. Used on chat rows,
 *  the inner Contacts/Groups tabs, and (via the same colour) the
 *  bottom-nav Comms dot. */
@Composable
private fun UnreadDot() {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(app.aether.aegis.ui.theme.AegisCyan),
    )
}

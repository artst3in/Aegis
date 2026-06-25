package app.aether.aegis.ui.screens

import app.aether.aegis.AegisApp
import app.aether.aegis.core.Message
import app.aether.aegis.core.MessageType
import app.aether.aegis.core.Protocol
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisTopBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Group chat screen. The group's id is the Aegis-side UUID; SimpleX
 * delivery rides on top of the existing per-member 1:1 channels (we
 * `sendToGroup` when SimpleX is available, fan-out otherwise).
 *
 * Messages are stored against `peerKey = "group:<id>"` so the
 * existing Repository.conversation() query works without a schema
 * change.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GroupChatScreen(groupId: String, navController: NavController) {
    val selfKey = AegisApp.instance.identity.deviceId
    val group = produceState<app.aether.aegis.groups.GroupEntity?>(null, groupId) {
        value = AegisApp.instance.repository.getGroup(groupId)
    }.value
    // Per-group dead-man's switch (per-group toggles).
    // Entering this screen = engagement → cancel any pending
    // auto-disable for this group. Exiting (DisposableEffect's
    // onDispose) = reschedule iff the group has a timer
    // configured.
    val groupChatCtx = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(groupId) {
        app.aether.aegis.groups.GroupPerGroupAutoDisableWorker.cancel(
            groupChatCtx, groupId,
        )
        onDispose {
            // Was runBlocking { getGroup } INSIDE onDispose — i.e. a blocking
            // DB read on the MAIN thread every time you left a group chat.
            // Do it on the app scope instead so leaving never stalls the UI.
            app.aether.aegis.AegisApp.appScope.launch {
                val snap = runCatching {
                    AegisApp.instance.repository.getGroup(groupId)
                }.getOrNull()
                val minutes = snap?.autoDisableMinutes
                if (snap?.enabled == true && minutes != null) {
                    app.aether.aegis.groups.GroupPerGroupAutoDisableWorker.schedule(
                        groupChatCtx, groupId, minutes,
                    )
                }
            }
        }
    }
    // Mark this group foregrounded so its inbound messages don't ALSO buzz a
    // notification while you're reading it. Foreground-aware (resume/pause),
    // so backgrounding clears it even though the screen stays composed.
    val groupChatLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(groupChatLifecycle, groupId) {
        val gkey = "group:$groupId"
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    app.aether.aegis.chat.ActiveChat.key = gkey
                    // Opening the group chat reads its messages — clear any
                    // already-posted notifications for it (keyed to the
                    // conversation, gkey == openTarget in notifyMessage).
                    androidx.core.app.NotificationManagerCompat.from(groupChatCtx)
                        .cancel(gkey.hashCode())
                    // Reading the group clears its unread badge.
                    app.aether.aegis.prefs.ReadStore.markRead(gkey)
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                    if (app.aether.aegis.chat.ActiveChat.key == gkey) {
                        app.aether.aegis.chat.ActiveChat.key = null
                    }
                else -> {}
            }
        }
        groupChatLifecycle.lifecycle.addObserver(obs)
        onDispose {
            groupChatLifecycle.lifecycle.removeObserver(obs)
            if (app.aether.aegis.chat.ActiveChat.key == gkey) {
                app.aether.aegis.chat.ActiveChat.key = null
            }
        }
    }
    val memberRows by AegisApp.instance.repository
        .observeGroupMemberRows(groupId)
        .collectAsState(initial = emptyList())
    val memberKeys = memberRows.map { it.peerPubkey }
    val knownPeers by AegisApp.instance.repository
        .observeKnownPeers()
        .collectAsState(initial = emptyList())
    // Sender-name resolver shared with GroupMembersScreen — looks
    // up KnownPeer.displayName first (your 1:1 alias), falls
    // through to the cached group displayName from inbound
    // traffic, and finally to an 8-char hex prefix. Used by
    // GroupBubble to render sender labels.
    val nameResolver: (String) -> String = remember(knownPeers, memberRows) {
        { pubkey ->
            knownPeers.firstOrNull { it.publicKey == pubkey }?.displayName
                ?: memberRows.firstOrNull { it.peerPubkey == pubkey }?.displayName
                // Inbound group messages are attributed with the sender's
                // aegis id, which is `simplex:<member display name>` (the
                // name is embedded in the id). Member ROWS are keyed by the
                // member's crypto memberId, so the two lookups above miss for
                // anyone who isn't also a 1:1 contact — and `take(8)` then
                // turned every id into the literal "simplex:". Recover the
                // real name from the id itself before any hex fallback.
                ?: pubkey.substringAfter("simplex:", "").takeIf { it.isNotBlank() }
                ?: pubkey.take(8)
        }
    }
    val peerKey = "group:$groupId"
    val messages by AegisApp.instance.repository
        .conversation(peerKey)
        .collectAsState(initial = emptyList())
    // Keep the group's unread badge cleared while it's foregrounded — a
    // message landing while you're reading stays read.
    LaunchedEffect(messages.size) {
        if (app.aether.aegis.chat.ActiveChat.key == peerKey) {
            // Watermark at the newest message's time, not just now() — a
            // redelivered old message is recorded with a fresh receive-time
            // stamp that would otherwise revive the unread dot. (See the
            // matching note in ChatScreen.)
            app.aether.aegis.prefs.ReadStore.markRead(
                peerKey,
                maxOf(System.currentTimeMillis(), messages.maxOfOrNull { it.timestamp } ?: 0L),
            )
        }
    }

    var draft by remember { mutableStateOf(app.aether.aegis.prefs.DraftStore.get(peerKey)) }
    // Persist the draft so it survives leaving the group chat; send blanks
    // the field, which clears it. (user-reported draft loss)
    LaunchedEffect(peerKey) {
        androidx.compose.runtime.snapshotFlow { draft }
            .collect { app.aether.aegis.prefs.DraftStore.set(peerKey, it) }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Drives auto-scroll-to-newest, same as 1:1 chat (ChatScreen).
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val mutePrefs = remember(context) { app.aether.aegis.prefs.GroupMutePrefs(context) }
    // Re-render the menu label and the title row's chip when the
    // mute state changes. mutableStateOf is enough because the prefs
    // file is only touched from this screen.
    var muted by remember { mutableStateOf(mutePrefs.isMuted(peerKey)) }

    // ---- Part 3: pinned README ----
    // Live group row so the banner reflects set/clear immediately
    // (the produceState above is one-shot). readmeId points at the
    // MessageEntity.id designated as this group's standing topic.
    val liveGroups by AegisApp.instance.repository
        .observeGroups()
        .collectAsState(initial = emptyList())
    val liveGroup = liveGroups.firstOrNull { it.id == groupId } ?: group
    val readmeId = liveGroup?.readmeMessageId
    // Only OWNER/ADMIN may set/clear the README (same gate as the
    // other group-profile edits).
    val canManage = remember(memberRows, selfKey) {
        val role = app.aether.aegis.groups.GroupRole.fromStored(
            memberRows.firstOrNull { it.peerPubkey == selfKey }?.role,
        )
        role == app.aether.aegis.groups.GroupRole.OWNER ||
            role == app.aether.aegis.groups.GroupRole.ADMIN
    }
    // Set (msg != null) or clear (msg == null) the README pointer and
    // drop an audit chip into history. NOTE: this is a LOCAL
    // designation — the group profile defines no transport
    // for the README pointer (unlike avatar/description, which ride
    // groupProfile), so it does not yet propagate to other members.
    // Cross-member propagation is the one deferred piece of Part 3.
    val applyReadme: (Message?) -> Unit = { msg ->
        scope.launch {
            val repo = AegisApp.instance.repository
            repo.setGroupReadme(groupId, msg?.id)
            repo.recordGroupSystem(
                groupId = groupId,
                kind = app.aether.aegis.groups.GroupSystemPayload.Kind.README,
                actor = selfKey,
                to = if (msg == null) "cleared" else "set",
            )
        }
    }

    // Group media: pick an image/video, stage it (Attachments.import copies it
    // in + derives mime/size), then send through the group enclave via
    // sendFileToGroup. The group input previously had NO attach affordance at
    // all — "group media don't work" because they were never wired (the
    // backend sendFileToGroup existed but had no caller). Images + video only;
    // both are always metadata-scrubbed for anonymous groups inside the send.
    val groupMediaLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val g = liveGroup
        if (uri == null || g == null) return@rememberLauncherForActivityResult
        val sgid = g.simplexGroupId
        val gAegisId = g.id
        scope.launch {
            val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.aether.aegis.util.Attachments.import(context, uri)
            } ?: return@launch
            sendGroupAttachment(gAegisId, sgid, local)
        }
    }

    // Own the status-bar inset here (mirrors ChatScreen): this screen is a
    // bare Column, not a Scaffold, so nothing else pads the top — without
    // this the TopAppBar drew UNDER the system status bar (user-reported
    // overlap). statusBarsPadding on the Column lifts everything below the
    // bar; the TopAppBar's own windowInsets are zeroed so we don't pad twice.
    // navigationBarsPadding() so 3-button navigation doesn't cover the message
    // composer (user report). ~0 under gesture nav, button-height under 3-button
    // nav — auto-adapts, no mode detection. Before imePadding() so the inset
    // math gives total bottom = max(navBar, keyboard).
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        AegisTopBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            // Group identity + controls live in the SECOND row below — the top
            // bar carries only back + the shared ActionCluster. Cramming the
            // group name into the title slot squeezed it to a truncated 3-line
            // wedge beside the cluster (user report).
            title = {},
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    AegisIcon(app.aether.aegis.ui.components.AegisIcons.Back, stringResource(R.string.action_back))
                }
            },
        )

        // Second row — group identity (avatar + name + member count) and the
        // group-actions overflow, full width so the name gets the whole row
        // instead of fighting the action cluster.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            app.aether.aegis.ui.components.GroupAvatar(
                avatarPath = group?.avatarPath,
                size = 32.dp,
                glyphFontSize = 15.sp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group?.name ?: "Group",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (muted) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("🔕", fontSize = 13.sp)
                    }
                }
                Text(
                    "${memberKeys.size} member${if (memberKeys.size == 1) "" else "s"}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { menuOpen = true }) {
                AegisIcon(app.aether.aegis.ui.components.AegisIcons.More, "Group actions")
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(if (muted) "Unmute group" else stringResource(R.string.group_members_mute_group)) },
                    onClick = {
                        menuOpen = false
                        val next = !muted
                        mutePrefs.setMuted(peerKey, next)
                        muted = next
                    },
                )
                DropdownMenuItem(
                    text = { Text("Members (${memberKeys.size})") },
                    onClick = {
                        menuOpen = false
                        navController.navigate("group/$groupId/members")
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_chat_delete_group)) },
                    leadingIcon = {
                        AegisIcon(
                            icon = app.aether.aegis.ui.components.AegisIcons.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        confirmDelete = true
                    },
                )
            }
        }

        if (confirmDelete && group != null) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete \"${group.name}\"?") },
                text = {
                    Text(
                        stringResource(R.string.group_chat_removes_the_group_and) +
                            "Other members will keep their copy of the conversation.",
                    )
                },
                confirmButton = {
                    TextButton(
                        // Protected Mode: deleting the group is group
                        // management — locked under the GROUPS gate so a child
                        // can't drop a monitored/family room.
                        enabled = !isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.GROUPS),
                        onClick = {
                            confirmDelete = false
                            val gid = group.id
                            scope.launch {
                                AegisApp.instance.repository.deleteGroup(gid)
                                navController.popBackStack()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }

        // Part 3: sticky, collapsible README banner at the top of the
        // chat — the group's standing topic, distinct from a normal
        // pin. New members see it expanded; collapsible thereafter.
        if (readmeId != null) {
            val readmeMsg = remember(readmeId, messages) {
                messages.firstOrNull { it.id == readmeId }
            }
            var expanded by remember(readmeId) { mutableStateOf(true) }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.group_chat_group_readme),
                            color = app.aether.aegis.ui.theme.AegisCyan,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (canManage) {
                            TextButton(onClick = { applyReadme(null) }) {
                                Text(stringResource(R.string.sentinel_log_clear), fontSize = 11.sp)
                            }
                        }
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "Hide" else "Show", fontSize = 11.sp)
                        }
                    }
                    if (expanded) {
                        Text(
                            // Strip the "[#group] " wire prefix group
                            // messages carry, if present.
                            readmeMsg?.content?.substringAfter("] ", readmeMsg.content)
                                ?: "README message not in view.",
                            fontSize = 13.sp,
                            maxLines = 8,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // Auto-scroll to newest — mirrors 1:1 chat. reverseLayout means
        // index 0 is the visual bottom (newest). Always snap on an OUTGOING
        // message (the user just hit Send); on incoming, only when they're
        // already at/near the bottom (≤5 covers a burst without yanking
        // someone reading older history). Was missing entirely, so sending
        // in a group left the composer's new message off-screen below.
        LaunchedEffect(messages.lastOrNull()?.id) {
            val newest = messages.lastOrNull() ?: return@LaunchedEffect
            val outgoingNew = newest.from == selfKey
            val nearBottom = listState.firstVisibleItemIndex <= 5
            if (outgoingNew || nearBottom) {
                runCatching { listState.animateScrollToItem(0) }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            state = listState,
            reverseLayout = true,
        ) {
            items(messages.reversed(), key = { it.id }) { msg ->
                // GROUP_SYSTEM rows render as compact centre chips,
                // not message bubbles (groups hardening). Everything
                // else is a regular bubble — which owns its own
                // long-press menu (reactions for everyone + the README
                // toggle for OWNER/ADMIN) so there's one consistent
                // affordance instead of two parallel long-press paths.
                if (msg.type == MessageType.GROUP_SYSTEM) {
                    GroupSystemChip(msg, nameResolver)
                } else {
                    GroupBubble(
                        msg = msg,
                        selfKey = selfKey,
                        resolveName = nameResolver,
                        navController = navController,
                        canManage = canManage,
                        isReadme = msg.id == readmeId,
                        onToggleReadme = {
                            applyReadme(if (msg.id == readmeId) null else msg)
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Attach image/video → group media picker.
            IconButton(
                onClick = {
                    groupMediaLauncher.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts
                                .PickVisualMedia.ImageAndVideo,
                        ),
                    )
                },
            ) {
                AegisIcon(
                    icon = app.aether.aegis.ui.components.AegisIcons.Gallery,
                    contentDescription = "Attach media",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.group_chat_message)) },
                singleLine = true,
            )
            IconButton(
                onClick = {
                    val text = draft.trim()
                    if (text.isNotBlank() && group != null) {
                        val name = group.name
                        val simplexId = group.simplexGroupId
                        val members = memberKeys
                        val groupAegisId = group.id
                        draft = ""
                        scope.launch {
                            sendGroupMessage(
                                groupAegisId = groupAegisId,
                                simplexGroupName = name,
                                simplexGroupId = simplexId,
                                memberKeys = members,
                                body = text,
                            )
                        }
                    }
                },
            ) {
                AegisIcon(
                    icon = app.aether.aegis.ui.components.AegisIcons.Send,
                    contentDescription = stringResource(R.string.action_send),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private suspend fun sendGroupMessage(
    groupAegisId: String,
    simplexGroupName: String,
    simplexGroupId: Long?,
    memberKeys: List<String>,
    body: String,
) {
    val aegisApp = AegisApp.instance
    val pm = aegisApp.protocolManager
    val peerKey = "group:$groupAegisId"

    // Record the outgoing message locally so the sender's UI updates
    // immediately, regardless of whether the underlying SimpleX call
    // succeeds.
    aegisApp.repository.recordSent(peerKey, body, Protocol.SIMPLEX)

    // Try the native SimpleX group send first. Requires the numeric
    // groupId we stored when the group was created — if missing (legacy
    // groups created before the migration) fall through to fan-out.
    val simplex = aegisApp.transports.filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
    // Group messages stay in the group enclave. If the SimpleX
    // group send fails, the message fails. Group content must
    // never escape into 1:1 protocol channels.
    if (simplexGroupId != null) {
        simplex?.sendToGroup(simplexGroupId, body)
    }
}

/**
 * Send a picked image/video into the group. Mirrors ChatScreen's
 * sendStagedAttachment, but addressed to the group enclave: the row is
 * recorded locally (peerKey "group:<id>") so the sender's chat updates at
 * once, then sendFileToGroup pushes it over SimpleX. Group media is always
 * metadata-scrubbed inside sendFileToGroup (anonymous groups), and never
 * falls back to a 1:1 channel — if the group has no native id there's
 * nowhere safe to send it.
 */
private suspend fun sendGroupAttachment(
    groupAegisId: String,
    simplexGroupId: Long?,
    local: app.aether.aegis.util.Attachments.Local,
) {
    val aegisApp = AegisApp.instance
    val isImage = local.mime.startsWith("image/")
    // Video shares the PHOTO row type (rendered by MIME), same as 1:1.
    val type = if (isImage || local.mime.startsWith("video/")) {
        MessageType.PHOTO
    } else {
        MessageType.FILE
    }
    val msg = aegisApp.repository.recordSentAttachment(
        toKey = "group:$groupAegisId",
        caption = "",
        attachmentPath = local.path,
        attachmentMime = local.mime,
        attachmentSize = local.size,
        attachmentName = local.name,
        protocol = Protocol.SIMPLEX,
        type = type,
    )
    if (simplexGroupId == null) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            android.widget.Toast.makeText(
                aegisApp,
                "This group isn't fully connected yet — media can't be sent.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        return
    }
    val simplex = aegisApp.transports
        .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
    val ok = simplex?.sendFileToGroup(
        groupId = simplexGroupId,
        filePath = local.path,
        isImage = isImage,
        caption = "",
        localMessageId = msg.id,
    ) ?: false
    if (!ok) {
        val why = simplex?.lastSendError ?: "Couldn't send media to the group"
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            android.widget.Toast.makeText(aegisApp, why, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Compact centre chip for `MessageType.GROUP_SYSTEM` rows.
 * These render between
 * regular bubbles as an in-history audit trail of membership +
 * metadata changes. No bubble, no avatar, no timestamp on the
 * face of the chip — just a single dim line of text describing
 * what happened.
 *
 * Body is JSON parsed via [app.aether.aegis.groups.GroupSystemPayload.decode].
 * Parse failure falls through to a generic "system event" string
 * rather than crashing — protects against malformed wire data
 * from a misbehaving peer.
 */
@Composable
private fun GroupSystemChip(msg: Message, resolveName: (String) -> String) {
    val decoded = remember(msg.content) {
        app.aether.aegis.groups.GroupSystemPayload.decode(msg.content)
    }
    val text = remember(decoded, resolveName) {
        renderSystemText(decoded, resolveName)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Plain-text rendering for a decoded GROUP_SYSTEM payload. Short
 *  by design — these chips are ambient, not the focus.
 *  [resolveName] turns actor / subject pubkeys into the user's
 *  preferred label via the cache chain (KnownPeer → cached
 *  group displayName → 8-char hex fallback). */
private fun renderSystemText(
    decoded: app.aether.aegis.groups.GroupSystemPayload.Decoded?,
    resolveName: (String) -> String,
): String {
    if (decoded == null) return "· system event ·"
    val actor = resolveName(decoded.actor)
    val subject = decoded.subject?.let { resolveName(it) }
    return when (decoded.kind) {
        app.aether.aegis.groups.GroupSystemPayload.Kind.JOIN ->
            "$actor joined the group"
        app.aether.aegis.groups.GroupSystemPayload.Kind.LEAVE ->
            "$actor left the group"
        app.aether.aegis.groups.GroupSystemPayload.Kind.KICK ->
            "$actor removed ${subject ?: "a member"}"
        app.aether.aegis.groups.GroupSystemPayload.Kind.RENAME ->
            "$actor renamed the group" +
                (decoded.to?.let { " to \"$it\"" } ?: "")
        app.aether.aegis.groups.GroupSystemPayload.Kind.ROLE ->
            "$actor set ${subject ?: "a member"} to ${decoded.to ?: "?"}"
        app.aether.aegis.groups.GroupSystemPayload.Kind.TTL -> {
            val secs = decoded.seconds ?: 0L
            if (secs <= 0L) "$actor turned off disappearing messages"
            else "$actor set disappearing messages: ${formatTtl(secs)}"
        }
        app.aether.aegis.groups.GroupSystemPayload.Kind.README ->
            if (decoded.to == "cleared") "$actor cleared the group README"
            else "$actor set the group README"
    }
}

/** Compact "1 h" / "3 d" / "2 w" TTL label. Lowest unit that
 *  divides evenly; falls back to seconds for anything weirder. */
private fun formatTtl(seconds: Long): String {
    val mins = seconds / 60
    val hrs  = seconds / 3600
    val days = seconds / 86400
    val wks  = seconds / 604800
    return when {
        wks  > 0 && seconds % 604800 == 0L -> "${wks} w"
        days > 0 && seconds % 86400 == 0L  -> "${days} d"
        hrs  > 0 && seconds % 3600 == 0L   -> "${hrs} h"
        mins > 0 && seconds % 60 == 0L     -> "${mins} m"
        else -> "${seconds} s"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupBubble(
    msg: Message,
    selfKey: String,
    resolveName: (String) -> String,
    navController: NavController,
    canManage: Boolean = false,
    isReadme: Boolean = false,
    onToggleReadme: () -> Unit = {},
) {
    val outgoing = msg.from == selfKey
    // resolveName covers the full name chain (KnownPeer →
    // cached group displayName → 8-char hex).
    val senderName = if (outgoing) "you" else resolveName(msg.from)
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember(msg.id) { mutableStateOf(false) }
    var showCustomEmote by remember(msg.id) { mutableStateOf(false) }
    val myReactions = remember(msg.reactionsJson) {
        app.aether.aegis.ui.components.myReactionEmotes(msg.reactionsJson)
    }

    // Fire a reaction on the signed protocol. The group conversation key
    // ("group:<aegisGroupId>") is msg.to; sendSignedReaction resolves it
    // to the SimpleX group and rides the reaction as a quote-carrier so
    // members recover the cross-device link. No-op (toast) on messages
    // without an itemId to target — e.g. our own sends, whose itemId the
    // native group send doesn't hand back.
    fun react(emote: String, add: Boolean) {
        val itemId = msg.simplexItemId
        if (itemId == null) {
            android.widget.Toast.makeText(
                context,
                "Can't react to this message yet",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        scope.launch {
            val simplex = AegisApp.instance.transports
                .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                .firstOrNull()
            simplex?.sendSignedReaction(msg.to, itemId, emote, add)
        }
    }

    if (showCustomEmote) {
        app.aether.aegis.ui.components.CustomEmoteDialog(
            onDismiss = { showCustomEmote = false },
            onPick = { emote -> react(emote, add = !myReactions.contains(emote)) },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Surface(
                color = if (outgoing) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (!outgoing) {
                        Text(
                            senderName,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    // Attachment rendering — mirrors 1:1 ChatScreen so group
                    // media is actually usable: a DEFERRED file (held by the
                    // Wi-Fi-only/size gate that groups now honour) shows a
                    // tap-to-download chip; a COMPLETED file decrypts-if-sealed
                    // and renders the image, or a file chip for non-previewable
                    // types. Reuses the same internal chips as 1:1.
                    val attachPath = msg.attachmentPath
                    val attachMime = msg.attachmentMime
                    val isDeferredMedia = attachPath == null && attachMime != null &&
                        (msg.type == MessageType.PHOTO || msg.type == MessageType.VOICE ||
                            msg.type == MessageType.FILE)
                    if (isDeferredMedia) {
                        DeferredAttachmentChip(msg = msg, mime = attachMime!!, selfKey = selfKey)
                    }
                    if (attachPath != null && attachMime != null) {
                        val attachSize = msg.attachmentSize ?: 0L
                        val needsDecrypt = msg.sealedDek != null &&
                            app.aether.aegis.lock.ChatAttachmentSeal.isEncrypted(attachPath)
                        val canUnseal = AegisApp.instance.repository.canUnsealAttachments
                        val previewable = attachMime.startsWith("image/") ||
                            attachMime.startsWith("video/")
                        when {
                            needsDecrypt && !canUnseal ->
                                LockedAttachmentChip(mime = attachMime, size = attachSize)
                            previewable -> {
                                // Decrypt off the composition thread (large
                                // sealed files would ANR if resolved in
                                // remember{}); re-renders when the path lands.
                                val viewable by androidx.compose.runtime.produceState<String?>(
                                    initialValue = if (needsDecrypt) null else attachPath,
                                    key1 = msg.id,
                                    key2 = msg.sealedDek?.contentHashCode(),
                                ) {
                                    value = if (!needsDecrypt) {
                                        attachPath
                                    } else {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            AegisApp.instance.repository
                                                .viewableAttachmentPath(msg, context)
                                        }
                                    }
                                }
                                val vp = viewable
                                when {
                                    vp == null ->
                                        DecryptingAttachmentChip(mime = attachMime, size = attachSize)
                                    // Video can't be drawn by Coil (it decodes
                                    // stills) — it was rendering blank. Route it
                                    // through the shared VideoBubble (frame
                                    // thumbnail + in-app player), same as 1:1.
                                    attachMime.startsWith("video/") ->
                                        VideoBubble(
                                            path = vp,
                                            name = msg.attachmentName,
                                            size = attachSize,
                                            navController = navController,
                                        )
                                    else ->
                                        coil.compose.AsyncImage(
                                            model = java.io.File(vp),
                                            contentDescription = msg.attachmentName ?: "image",
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            modifier = Modifier
                                                .heightIn(max = 240.dp)
                                                .widthIn(max = 260.dp)
                                                .clip(MaterialTheme.shapes.small)
                                                .combinedClickable(
                                                    onClick = {
                                                        val enc = java.net.URLEncoder.encode(vp, "UTF-8")
                                                        val nameQ = msg.attachmentName?.let {
                                                            "?name=" + java.net.URLEncoder.encode(it, "UTF-8")
                                                        } ?: ""
                                                        navController.navigate("photo/$enc$nameQ")
                                                    },
                                                    onLongClick = { menuOpen = true },
                                                ),
                                        )
                                }
                            }
                            else -> FileChip(
                                msg = msg,
                                name = msg.attachmentName ?: "file",
                                mime = attachMime,
                                size = attachSize,
                                sealed = needsDecrypt,
                            )
                        }
                    }
                    // Caption / text — only when there's actually text (an
                    // image-only send has a blank body, no empty line).
                    if (msg.content.isNotBlank()) {
                        val textColor = if (outgoing) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface
                        // Linkify URLs (clickable) — shared with 1:1 via
                        // buildLinkifiedString. Plain Text when there's no URL
                        // (cheaper, and selection still works via long-press Copy).
                        val annotated = remember(msg.content) { buildLinkifiedString(msg.content) }
                        if (annotated.getStringAnnotations("URL", 0, annotated.length).isEmpty()) {
                            Text(msg.content, color = textColor)
                        } else {
                            androidx.compose.foundation.text.ClickableText(
                                text = annotated,
                                style = androidx.compose.ui.text.TextStyle(color = textColor),
                                onClick = { offset ->
                                    annotated.getStringAnnotations("URL", offset, offset)
                                        .firstOrNull()?.let { ann ->
                                            runCatching {
                                                context.startActivity(
                                                    android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(ann.item),
                                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                                )
                                            }
                                        }
                                },
                            )
                        }
                    }
                    // Reaction chips — shared with 1:1 chat.
                    app.aether.aegis.ui.components.ReactionChipsRow(
                        reactionsJson = msg.reactionsJson,
                        onToggle = { emote, add -> react(emote, add) },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // Reactions for everyone…
                app.aether.aegis.ui.components.ReactionPickerRow(
                    reactionsJson = msg.reactionsJson,
                    onReact = { emote, add -> menuOpen = false; react(emote, add) },
                    onCustom = { menuOpen = false; showCustomEmote = true },
                )
                // Copy the message text — groups had no way to copy at all.
                if (msg.content.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_copy)) },
                        onClick = {
                            menuOpen = false
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            cm?.setPrimaryClip(
                                android.content.ClipData.newPlainText("message", msg.content),
                            )
                        },
                    )
                }
                // …README toggle only for OWNER/ADMIN on text messages.
                if (canManage && msg.type == MessageType.TEXT) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isReadme) "Clear group README"
                                else "Set as group README",
                            )
                        },
                        onClick = { menuOpen = false; onToggleReadme() },
                    )
                }
            }
        }
    }
}

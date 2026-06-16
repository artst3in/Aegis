package app.aether.aegis.ui.screens

import app.aether.aegis.AegisApp
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/** A cached group-member profile name that hasn't been refreshed by
 *  inbound traffic in longer than this is shown with a dim "·"
 *  staleness marker — the peer may have renamed themselves since we
 *  last heard from them. The UI shows a small '·' dim suffix when the
 *  name is older than 30 days. */
private const val STALE_MEMBER_NAME_MS = 30L * 24 * 60 * 60 * 1000

/**
 * Group members + per-group settings (mute, rename, TTL, leave,
 * identity switch, plus Part 2 role badges and the long-press
 * promote/demote/remove sheet). Members are stored as opaque
 * per-member keys by SimpleX (`memberId` = pairwise public key);
 * we display whichever human-readable name we have for that key:
 *   1. KnownPeer.displayName if they're also a 1:1 contact.
 *   2. The cached profile name from a previous inbound from them
 *      (populated by SimpleXTransport.cacheGroupMemberDisplayName
 *      on every inbound group text).
 *   3. The raw memberId truncated, prefixed with `Member ·`.
 *
 * The "no names" complaint comes from case (3) being hit for every
 * row; surfacing the truncated id at least proves the row exists
 * and is distinct from other members. Future work: cache
 * member.memberProfile.displayName as members join.
 *
 * @param groupId the Aegis-local group id (NOT the SimpleX
 *   simplexGroupId; the two are looked up off the loaded [GroupEntity]
 *   when an upstream call needs the SimpleX id).
 * @param navController used only to pop back (up nav on back-press and
 *   after a successful Leave).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GroupMembersScreen(groupId: String, navController: NavController) {
    // One-shot async load of the group row (produceState runs the
    // suspend fetch off-composition). Null until it lands; the live
    // Flow below supersedes it for the fields that can change.
    val group = produceState<app.aether.aegis.groups.GroupEntity?>(null, groupId) {
        value = AegisApp.instance.repository.getGroup(groupId)
    }.value
    // Live view of this group's row so the profile cards (description,
    // avatar) reflect both local
    // edits and inbound groupProfile updates without re-navigating.
    // Falls back to the one-shot [group] until the Flow first emits.
    val liveGroups by AegisApp.instance.repository
        .observeGroups()
        .collectAsState(initial = emptyList())
    val liveGroup = liveGroups.firstOrNull { it.id == groupId } ?: group
    // Full rows (pubkey + cached displayName + joinedAt) instead of
    // the pubkey-only stream — gives the resolver below the cached
    // profile name so members who aren't 1:1-paired KnownPeers stop
    // rendering as "Member · 7a2c1f8d3e" forever. Cache is populated
    // by SimpleXTransport.cacheGroupMemberDisplayName on every
    // inbound group message.
    val memberRows by AegisApp.instance.repository
        .observeGroupMemberRows(groupId)
        .collectAsState(initial = emptyList())
    val memberKeys = memberRows.map { it.peerPubkey }
    // 1:1 paired contacts — used by the name resolver (a paired
    // contact's nickname outranks a cached group-profile name).
    val knownPeers by AegisApp.instance.repository
        .observeKnownPeers()
        .collectAsState(initial = emptyList())
    val context = androidx.compose.ui.platform.LocalContext.current
    val mutePrefs = remember(context) { app.aether.aegis.prefs.GroupMutePrefs(context) }
    // Mute + TTL key this group in the shared chat-pref namespace as
    // "group:<id>" so a group can't collide with a 1:1 peer pubkey.
    val peerKey = "group:$groupId"
    // Compose state mirroring the persisted mute flag; seeded from prefs
    // and written through on toggle (prefs are the source of truth).
    var muted by remember { mutableStateOf(mutePrefs.isMuted(peerKey)) }
    // Dialog-open Compose flags for the four modal surfaces below.
    var confirmLeave by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var ttlOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val transport = remember {
        AegisApp.instance.transports
            .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
            .firstOrNull()
    }
    // Pull the AUTHORITATIVE member roster from the core on open. Without
    // this the screen shows only the members we happened to see join — a
    // 10-person group rendered as 3. Keyed on the simplexGroupId so it fires
    // once the group row has loaded (produceState is async).
    val sgidForRoster = group?.simplexGroupId
    LaunchedEffect(sgidForRoster) {
        sgidForRoster?.let { transport?.refreshGroupMembers(it) }
    }

    // Self role — drives the permission gates for "+ invite",
    // long-press promote / demote / remove, etc. Per the
    // groups-hardening permission matrix.
    val selfKey = AegisApp.instance.identity.deviceId
    val selfRow = memberRows.firstOrNull { it.peerPubkey == selfKey }
    val selfRole = remember(selfRow?.role) {
        app.aether.aegis.groups.GroupRole.fromStored(selfRow?.role)
    }
    // OWNER + ADMIN can invite + remove + change roles (with sub-
    // gates for who they can act on — only OWNER may act on
    // another OWNER). MEMBER sees a read-only view.
    val canManage = selfRole == app.aether.aegis.groups.GroupRole.OWNER ||
        selfRole == app.aether.aegis.groups.GroupRole.ADMIN
    var invitePickerOpen by remember { mutableStateOf(false) }
    // Which member's long-press action sheet is open, or null. Hoisted
    // to screen scope (not per-row) so a single sheet instance is shared
    // and survives row recomposition.
    var actionSheetFor by remember {
        mutableStateOf<app.aether.aegis.groups.GroupMemberEntity?>(null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group?.name ?: "Group") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
                actions = {
                    // "+" invite — visible only to OWNER / ADMIN.
                    // Opens the member picker pre-filtered to peers
                    // NOT already in the group.
                    if (canManage) {
                        IconButton(onClick = { invitePickerOpen = true }) {
                            AegisIcon(AegisIcons.Add, stringResource(R.string.group_members_invite_member))
                        }
                    }
                },
            )
        },
    ) { padding ->
        // One scroll context for the WHOLE screen. Previously the options
        // cards sat in a non-scrollable Column and the member list was a
        // separate fillMaxSize LazyColumn below them — so the options got
        // shoved past the bottom edge (couldn't scroll to them) and the
        // member list was squeezed off-screen (members "barely showed").
        // Now everything scrolls together; the member rows are a plain
        // forEach (group sizes are bounded, so laziness isn't needed and a
        // LazyColumn can't be nested inside a verticalScroll anyway).
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // Part 0: groups are always incognito,
            // so this is a read-only statement of identity — no toggle.
            IdentityCard()

            // Part 1: the group's shared avatar + edit (OWNER/ADMIN).
            GroupImageCard(
                avatarPath = liveGroup?.avatarPath,
                canManage = canManage,
                onPick = {
                    (context as? app.aether.aegis.MainActivity)
                        ?.pickAttachment("image/*") { uri ->
                            scope.launch {
                                val local = kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.IO,
                                ) {
                                    app.aether.aegis.util.Attachments.import(context, uri)
                                } ?: return@launch
                                // Transport sanitises (EXIF strip + 256²)
                                // into the group's own file, so the import
                                // is throwaway — delete it after.
                                runCatching { transport?.setGroupAvatar(groupId, local.path) }
                                kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.IO,
                                ) {
                                    runCatching { java.io.File(local.path).delete() }
                                }
                            }
                        }
                },
            )

            // Part 2: the group's shared description. Read-only for
            // MEMBERs; OWNER/ADMIN get an Edit affordance. Shown to
            // everyone (cached from inbound groupProfile).
            GroupDescriptionCard(
                description = liveGroup?.description,
                canManage = canManage,
                onSave = { newDesc ->
                    scope.launch {
                        val name = liveGroup?.name ?: group?.name ?: return@launch
                        runCatching {
                            transport?.setGroupProfile(
                                aegisGroupId = groupId,
                                name = name,
                                description = newDesc.ifBlank { null },
                                avatarPath = liveGroup?.avatarPath,
                            )
                        }
                    }
                },
            )

            // Group link — create/share/revoke a join link, owner/admin
            // only. Brings Aegis to parity with the official client: a
            // shareable link anyone can use to join, alongside the
            // existing "invite a paired contact" picker.
            if (canManage) {
                GroupLinkCard(sgid = group?.simplexGroupId, transport = transport)
            }

            Card(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.group_members_mute_group), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (muted) "Notifications silenced — messages still arrive"
                            else "Notifications on",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    app.aether.aegis.ui.components.HexSwitch(
                        checked = muted,
                        onCheckedChange = { next ->
                            mutePrefs.setMuted(peerKey, next)
                            muted = next
                        },
                    )
                }
            }
            // Per-group on/off toggle.
            // When off, SimpleX drops this group's
            // inbound + outbound traffic even while the module
            // as a whole is enabled. Useful for "Aegis Amsterdam:
            // off until I want to check" while the family group
            // stays on.
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.group_members_active_in_this_group), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (group?.enabled == true)
                                "Receiving and sending. Toggle off to suspend without leaving."
                            else
                                "Suspended. No messages in or out until re-enabled.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = group?.enabled == true,
                        onCheckedChange = { next ->
                            scope.launch {
                                runCatching {
                                    AegisApp.instance.repository
                                        .setGroupEnabled(groupId, next)
                                }
                            }
                        },
                    )
                }
            }
            // Per-group auto-disable timer: opt-in inactivity countdown that flips
            // THIS group off (not the whole module). Resets on
            // entry to the group's chat.
            var perGroupTimerDialog by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { perGroupTimerDialog = true }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.group_members_autodisable_timer), fontWeight = FontWeight.SemiBold)
                        Text(
                            group?.autoDisableMinutes
                                ?.let { "$it min after last visit" }
                                ?: "Off — stays active while module is on",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        stringResource(R.string.group_members_configure),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            if (perGroupTimerDialog) {
                // "armed" = the timer is enabled at all; a null
                // autoDisableMinutes on the group means off.
                var armed by remember {
                    mutableStateOf(group?.autoDisableMinutes != null)
                }
                // 30 min default shown when no timer is set yet.
                var minutesStr by remember {
                    mutableStateOf((group?.autoDisableMinutes ?: 30).toString())
                }
                AlertDialog(
                    onDismissRequest = { perGroupTimerDialog = false },
                    title = { Text(stringResource(R.string.group_members_autodisable_timer_for_this)) },
                    text = {
                        Column {
                            Text(
                                stringResource(R.string.group_members_when_the_window_elapses) +
                                    "opening this group's chat, the group's " +
                                    "Active switch flips off automatically. " +
                                    "The module stays on; other groups are " +
                                    "unaffected.",
                                fontSize = 12.sp,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.group_members_armed), modifier = Modifier.weight(1f))
                                androidx.compose.material3.Switch(
                                    checked = armed,
                                    onCheckedChange = { armed = it },
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = minutesStr,
                                onValueChange = { v ->
                                    minutesStr = v.filter { c -> c.isDigit() }.take(4)
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
                            // Disarmed → null (off). Armed → at least 1 min;
                            // an unparseable/empty field falls back to 30.
                            val mins = if (armed)
                                minutesStr.toIntOrNull()?.coerceAtLeast(1) ?: 30
                            else null
                            scope.launch {
                                runCatching {
                                    AegisApp.instance.repository
                                        .setGroupAutoDisableMinutes(groupId, mins)
                                }
                            }
                            perGroupTimerDialog = false
                        }) { Text(stringResource(R.string.group_members_save), color = app.aether.aegis.ui.theme.AegisCyan) }
                    },
                    dismissButton = {
                        TextButton(onClick = { perGroupTimerDialog = false }) {
                            Text(stringResource(R.string.secure_notes_cancel))
                        }
                    },
                )
            }

            // Rename group — fires /_group_profile so every member's
            // local copy refreshes on next inbound. Optimistically
            // updates the local row on success so the header reflects
            // the new name immediately.
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.group_members_rename_group), fontWeight = FontWeight.SemiBold)
                        Text(
                            group?.name.orEmpty().ifBlank { "(unnamed)" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    OutlinedButton(onClick = { renameOpen = true }) {
                        Text(stringResource(R.string.group_members_rename), fontSize = 12.sp)
                    }
                }
            }

            // Per-group disappearing TTL — shared LogPeriodSlider via
            // dialog, same UX as the per-chat picker. Sends through
            // SimpleXTransport.setChatTtl with chatRef = #<sgid>.
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.group_members_disappearing_messages), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.group_members_off_1_min_1),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    OutlinedButton(onClick = { ttlOpen = true }) {
                        Text(stringResource(R.string.group_members_set), fontSize = 12.sp)
                    }
                }
            }

            if (renameOpen) {
                var newName by remember { mutableStateOf(group?.name.orEmpty()) }
                AlertDialog(
                    onDismissRequest = { renameOpen = false },
                    title = { Text(stringResource(R.string.group_members_rename_group)) },
                    text = {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it.take(80) },
                            singleLine = true,
                            label = { Text(stringResource(R.string.group_members_group_name)) },
                        )
                    },
                    confirmButton = {
                        TextButton(
                            // Protected Mode: renaming the group is group
                            // management — locked under the GROUPS gate.
                            enabled = newName.isNotBlank() && newName != group?.name &&
                                !isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.GROUPS),
                            onClick = {
                                val target = newName.trim()
                                val previousName = group?.name.orEmpty()
                                renameOpen = false
                                scope.launch {
                                    // Two writes: push the rename upstream so
                                    // every member's copy refreshes, AND record
                                    // a local GROUP_SYSTEM RENAME row so the
                                    // change shows in this device's chat history.
                                    runCatching { transport?.renameGroup(groupId, target) }
                                    runCatching {
                                        AegisApp.instance.repository.recordGroupSystem(
                                            groupId = groupId,
                                            kind = app.aether.aegis.groups.GroupSystemPayload.Kind.RENAME,
                                            actor = AegisApp.instance.identity.deviceId,
                                            from = previousName,
                                            to = target,
                                        )
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.group_members_save)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { renameOpen = false }) { Text(stringResource(R.string.secure_notes_cancel)) }
                    },
                )
            }

            if (ttlOpen) {
                var pending by remember { mutableStateOf<Long?>(null) }
                AlertDialog(
                    onDismissRequest = { ttlOpen = false },
                    title = { Text(stringResource(R.string.group_members_disappearing_messages)) },
                    text = {
                        Column {
                            Text(
                                stringResource(R.string.group_members_autoburn_messages_in_this),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            app.aether.aegis.ui.components.LogPeriodSlider(
                                valueSeconds = pending,
                                onValueChange = { pending = it },
                                // TTL range: 1 minute floor to 1 year ceiling,
                                // log-scaled. "Off"/null is the left end.
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
                        TextButton(onClick = {
                            val applied = pending
                            ttlOpen = false
                            scope.launch {
                                runCatching {
                                    transport?.setChatTtl(peerKey, applied)
                                }
                                runCatching {
                                    AegisApp.instance.repository.recordGroupSystem(
                                        groupId = groupId,
                                        kind = app.aether.aegis.groups.GroupSystemPayload.Kind.TTL,
                                        actor = AegisApp.instance.identity.deviceId,
                                        seconds = applied ?: 0L,
                                    )
                                }
                            }
                        }) { Text(stringResource(R.string.group_members_apply)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { ttlOpen = false }) { Text(stringResource(R.string.secure_notes_cancel)) }
                    },
                )
            }

            // Leave group — pulls the local row + fires /_leave so the
            // SimpleX core also tears down the group invariant. Confirms
            // first because re-joining requires a fresh invite from the
            // host, which the user can't summon on their own.
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.group_members_leave_group), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.group_members_removes_the_group_locally) +
                                "Re-joining requires a fresh invite.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    OutlinedButton(
                        onClick = { confirmLeave = true },
                        // Protected Mode: leaving a group can be locked so a
                        // child can't drop out of a family/monitored room.
                        enabled = !isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.GROUPS),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = app.aether.aegis.ui.theme.AegisSOS,
                        ),
                    ) {
                        Text(stringResource(R.string.group_members_leave), fontSize = 12.sp)
                    }
                }
            }

            if (confirmLeave) {
                AlertDialog(
                    onDismissRequest = { confirmLeave = false },
                    title = { Text("Leave group?") },
                    text = {
                        Text(
                            stringResource(R.string.group_members_youll_stop_receiving_messages) +
                                "would need to re-invite you to return.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmLeave = false
                            scope.launch {
                                val transport = AegisApp.instance.transports
                                    .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                                    .firstOrNull()
                                // Tear down upstream first (/_leave) so the
                                // SimpleX core drops us, THEN delete the local
                                // row. Both are best-effort (runCatching): we
                                // navigate away regardless so the user isn't
                                // stranded on a half-left group if either fails.
                                runCatching {
                                    transport?.leaveGroup(groupId)
                                }
                                runCatching {
                                    AegisApp.instance.repository.deleteGroup(groupId)
                                }
                                navController.navigateUp()
                            }
                        }) { Text(stringResource(R.string.group_members_leave), color = app.aether.aegis.ui.theme.AegisSOS) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.secure_notes_cancel)) }
                    },
                )
            }

            Text(
                "Members (${memberKeys.size})",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            memberRows.forEach { row ->
                    val memberKey = row.peerPubkey
                    val displayName = remember(memberKey, row.displayName, knownPeers) {
                        // Resolver chain:
                        //   1. KnownPeerEntity.displayName — if the
                        //      member is also a paired 1:1 contact, the
                        //      nickname YOU set wins.
                        //   2. GroupMemberEntity.displayName — cached
                        //      profile name from inbound group traffic.
                        //      Catches members we share a group with
                        //      but haven't paired with directly.
                        //   3. The historical hex / id fallbacks — only
                        //      reached when neither cache has anything
                        //      yet (first sight of a new member, or
                        //      pre-cache migration data).
                        knownPeers.firstOrNull { it.publicKey == memberKey }?.displayName
                            ?: row.displayName
                            ?: when {
                                memberKey.startsWith("gmid:") ->
                                    "Member #${memberKey.removePrefix("gmid:")}"
                                memberKey.startsWith("name:") ->
                                    memberKey.removePrefix("name:")
                                else -> "Member · ${memberKey.take(10)}"
                            }
                    }
                    // Staleness marker. Only flag when the name we're showing IS the
                    // cached group-profile name — i.e. case (2) of the
                    // resolver above. If the member is also a 1:1 KnownPeer
                    // the displayed name is YOUR nickname for them (case 1),
                    // which is never "stale"; and if we've never cached a
                    // profile name at all (case 3, hex fallback) there's
                    // nothing to be stale about. seenAt is set to "now" on
                    // every refresh (GroupDao.updateMemberDisplayName), so
                    // a cache untouched for >30 days means the peer may have
                    // renamed themselves since we last heard from them.
                    val nameStale = remember(
                        memberKey, row.displayName, row.displayNameSeenAt, knownPeers,
                    ) {
                        val knownName = knownPeers
                            .firstOrNull { it.publicKey == memberKey }?.displayName
                        val fromCache = knownName == null && row.displayName != null
                        val seenAt = row.displayNameSeenAt
                        fromCache && seenAt != null &&
                            System.currentTimeMillis() - seenAt > STALE_MEMBER_NAME_MS
                    }
                    val memberRole = remember(row.role) {
                        app.aether.aegis.groups.GroupRole.fromStored(row.role)
                    }
                    // OWNER + ADMIN can long-press members for the
                    // action sheet. Self can't act on self (avoid
                    // accidental self-demote / self-remove); OWNER
                    // can act on anyone, ADMIN can act on MEMBER
                    // only — the sheet itself enforces these in
                    // the per-action enabled gates.
                    val longPressable = canManage && memberKey != selfKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .let { base ->
                                if (longPressable) base.then(
                                    Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = { actionSheetFor = row },
                                    ),
                                ) else base
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(displayName, fontWeight = FontWeight.SemiBold)
                                // Dim "·" = cached profile name may be
                                // stale (>30 days since last refresh from
                                // this peer). See [nameStale] above.
                                if (nameStale) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "·",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                // Role badge — only OWNER / ADMIN
                                // get a chip. MEMBER is the default,
                                // no need to call it out.
                                if (memberRole != app.aether.aegis.groups.GroupRole.MEMBER) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    RoleChip(memberRole)
                                }
                            }
                            Text(
                                memberKey,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                if (memberKeys.isEmpty()) {
                    Text(
                        stringResource(R.string.group_members_no_members_yet_the) +
                            "will populate as their connections complete.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
        }
    }

    // Long-press action sheet for member management. State-hoisted
    // so the LazyColumn rows don't each carry their own copy.
    actionSheetFor?.let { target ->
        MemberActionSheet(
            target = target,
            selfRole = selfRole,
            displayName = run {
                val k = target.peerPubkey
                knownPeers.firstOrNull { it.publicKey == k }?.displayName
                    ?: target.displayName
                    ?: "Member · ${k.take(10)}"
            },
            onDismiss = { actionSheetFor = null },
            // Each handler does the same three-write dance: (1) push the
            // change upstream to SimpleX via the member's simplexMemberId,
            // (2) optimistically update the local member row, (3) append a
            // GROUP_SYSTEM audit row. The sheet is dismissed BEFORE the
            // launch so the UI feels immediate; all upstream calls are
            // best-effort (runCatching) and the local writes still land.
            // The `simplexMemberId ?: return@runCatching` skips only the
            // upstream call when we haven't synced the SimpleX id yet —
            // the local + audit writes below still run.
            onPromote = {
                val targetCopy = target
                actionSheetFor = null
                scope.launch {
                    runCatching {
                        val sgid = targetCopy.simplexMemberId ?: return@runCatching
                        transport?.setMemberRole(groupId, sgid, "admin")
                    }
                    runCatching {
                        AegisApp.instance.repository.setGroupMemberRole(
                            groupId = groupId,
                            peerPubkey = targetCopy.peerPubkey,
                            role = app.aether.aegis.groups.GroupRole.ADMIN,
                        )
                    }
                    runCatching {
                        AegisApp.instance.repository.recordGroupSystem(
                            groupId = groupId,
                            kind = app.aether.aegis.groups.GroupSystemPayload.Kind.ROLE,
                            actor = selfKey,
                            subject = targetCopy.peerPubkey,
                            to = "ADMIN",
                        )
                    }
                }
            },
            onDemote = {
                val targetCopy = target
                actionSheetFor = null
                scope.launch {
                    runCatching {
                        val sgid = targetCopy.simplexMemberId ?: return@runCatching
                        transport?.setMemberRole(groupId, sgid, "member")
                    }
                    runCatching {
                        AegisApp.instance.repository.setGroupMemberRole(
                            groupId = groupId,
                            peerPubkey = targetCopy.peerPubkey,
                            role = app.aether.aegis.groups.GroupRole.MEMBER,
                        )
                    }
                    runCatching {
                        AegisApp.instance.repository.recordGroupSystem(
                            groupId = groupId,
                            kind = app.aether.aegis.groups.GroupSystemPayload.Kind.ROLE,
                            actor = selfKey,
                            subject = targetCopy.peerPubkey,
                            to = "MEMBER",
                        )
                    }
                }
            },
            onRemove = {
                val targetCopy = target
                actionSheetFor = null
                scope.launch {
                    runCatching {
                        val sgid = targetCopy.simplexMemberId ?: return@runCatching
                        transport?.removeGroupMember(groupId, sgid)
                    }
                    runCatching {
                        AegisApp.instance.repository.removeGroupMember(
                            groupId, targetCopy.peerPubkey,
                        )
                    }
                    runCatching {
                        AegisApp.instance.repository.recordGroupSystem(
                            groupId = groupId,
                            kind = app.aether.aegis.groups.GroupSystemPayload.Kind.KICK,
                            actor = selfKey,
                            subject = targetCopy.peerPubkey,
                        )
                    }
                }
            },
        )
    }

    // Invite picker — list of paired KnownPeers that are NOT
    // already in the group. Tap one to invite.
    if (invitePickerOpen) {
        InviteMemberPicker(
            knownPeers = knownPeers,
            existingMemberKeys = memberKeys.toSet(),
            onDismiss = { invitePickerOpen = false },
            onPick = { peer ->
                invitePickerOpen = false
                scope.launch {
                    val sgid = group?.simplexGroupId
                    if (sgid != null) {
                        runCatching {
                            transport?.addMemberToGroup(sgid, peer.publicKey)
                        }
                    }
                    runCatching {
                        AegisApp.instance.repository.addGroupMember(
                            groupId, peer.publicKey,
                        )
                    }
                    // No GROUP_SYSTEM JOIN row written here — the
                    // SimpleX echo path in handleGroupMemberJoined
                    // emits it once the invite lands, with the
                    // dedupe id collapsing local + echo.
                }
            },
        )
    }
}

/** Cyan / amber chip used as the role badge next to an OWNER /
 *  ADMIN member's name. MEMBER renders no chip (default). */
@Composable
private fun RoleChip(role: app.aether.aegis.groups.GroupRole) {
    val (label, color) = when (role) {
        app.aether.aegis.groups.GroupRole.OWNER -> "OWNER" to app.aether.aegis.ui.theme.AegisCyan
        app.aether.aegis.groups.GroupRole.ADMIN -> "ADMIN" to app.aether.aegis.ui.theme.AegisWarning
        app.aether.aegis.groups.GroupRole.MEMBER -> return
    }
    androidx.compose.material3.Surface(
        color = color.copy(alpha = 0.18f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    ) {
        Text(
            label,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Long-press action sheet on a group member. Per the permission
 *  matrix:
 *
 *  - OWNER may Remove, Promote MEMBER → ADMIN, Demote ADMIN → MEMBER.
 *  - ADMIN may Remove MEMBERs and other ADMINs they outrank
 *    (admins are equal here — we only block KICKing OWNERs).
 *    Promote / Demote disabled (only OWNER may change roles).
 *  - MEMBER never reaches this sheet (its host gates on canManage).
 *
 *  The button-level `enabled` gates encode this so the sheet itself
 *  shows the user *why* an action is greyed out (ADMIN seeing a
 *  disabled Demote button is more informative than the action
 *  silently being absent).
 *
 *  @param target the member being acted on.
 *  @param selfRole the viewer's role in this group — drives which
 *    role-change buttons appear (only OWNER may change roles).
 *  @param displayName resolved name to title the sheet with.
 *  @param onPromote MEMBER → ADMIN. @param onDemote ADMIN → MEMBER.
 *  @param onRemove kick from the group. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MemberActionSheet(
    target: app.aether.aegis.groups.GroupMemberEntity,
    selfRole: app.aether.aegis.groups.GroupRole,
    displayName: String,
    onDismiss: () -> Unit,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onRemove: () -> Unit,
) {
    // Decode the target's stored role string into the enum; memoised on
    // the raw role so it only recomputes when the member's role changes.
    val targetRole = remember(target.role) {
        app.aether.aegis.groups.GroupRole.fromStored(target.role)
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (targetRole != app.aether.aegis.groups.GroupRole.MEMBER) {
                Spacer(modifier = Modifier.height(4.dp))
                RoleChip(targetRole)
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Only OWNER can change roles. Promote only when target
            // is currently MEMBER; Demote only when target is ADMIN.
            // OWNER never gets promoted (no role above) or demoted
            // from this sheet — group ownership transfer is a
            // separate workflow.
            val canChangeRoles = selfRole == app.aether.aegis.groups.GroupRole.OWNER
            // Protected Mode: managing a group (promote/demote/remove) is
            // locked under the same GROUPS gate as join/leave, so a child
            // can't run the room they're supposed to just be in.
            val groupsLocked = isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.GROUPS)
            if (canChangeRoles && targetRole == app.aether.aegis.groups.GroupRole.MEMBER) {
                TextButton(
                    onClick = onPromote,
                    enabled = !groupsLocked,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.group_members_promote_to_admin), color = app.aether.aegis.ui.theme.AegisCyan) }
            }
            if (canChangeRoles && targetRole == app.aether.aegis.groups.GroupRole.ADMIN) {
                TextButton(
                    onClick = onDemote,
                    enabled = !groupsLocked,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.group_members_demote_to_member)) }
            }

            // Remove. Disabled when target is OWNER (you can't kick
            // the owner — group dissolves) or when self lacks a
            // simplexMemberId for the target (means we haven't
            // synced enough state yet to issue the upstream
            // command).
            val canRemove = targetRole != app.aether.aegis.groups.GroupRole.OWNER &&
                target.simplexMemberId != null
            TextButton(
                onClick = onRemove,
                enabled = canRemove && !groupsLocked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.group_members_remove_from_group),
                    color = if (canRemove && !groupsLocked) app.aether.aegis.ui.theme.AegisSOS
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.secure_notes_cancel)) }
        }
    }
}

/** Invite picker — list of paired KnownPeers not already in the
 *  group. Tap one to call addMemberToGroup. Reuses [AlertDialog]
 *  for the simplest possible surface; a Material bottom-sheet
 *  list would be nicer but ramps complexity for a list that's
 *  typically a handful of contacts long.
 *
 *  @param knownPeers all paired 1:1 contacts.
 *  @param existingMemberKeys pubkeys already in the group — excluded
 *    from the candidate list so you can't double-invite.
 *  @param onPick invoked with the chosen peer to issue the invite. */
@Composable
private fun InviteMemberPicker(
    knownPeers: List<app.aether.aegis.data.KnownPeerEntity>,
    existingMemberKeys: Set<String>,
    onDismiss: () -> Unit,
    onPick: (app.aether.aegis.data.KnownPeerEntity) -> Unit,
) {
    // Invite candidates = paired peers not already in the group, sorted
    // case-insensitively by name. Memoised so the filter+sort only reruns
    // when the inputs change.
    val candidates = remember(knownPeers, existingMemberKeys) {
        knownPeers.filter { it.publicKey !in existingMemberKeys }
            .sortedBy { it.displayName.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_members_invite_member)) },
        text = {
            if (candidates.isEmpty()) {
                Text(
                    stringResource(R.string.group_members_every_paired_contact_is) +
                        "Pair a new contact first, then come back here.",
                    fontSize = 13.sp,
                )
            } else {
                Column {
                    candidates.forEach { peer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(peer) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(peer.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    peer.publicKey,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.tutorial_done)) }
        },
    )
}

/**
 * "Your identity in this group" card. Group identity is
 * incognito only — there is NO identity toggle; groups are always
 * incognito, so this card is a read-only statement of that
 * guarantee. The private 1:1 profile (real name, bio, avatar) is
 * never broadcast to a group; if someone needs to know who you are,
 * pair with them 1:1.
 *
 * The former reveal/anonymise switch (3 s hold-to-execute hex +
 * "GO PUBLIC" / "Anonymise" confirm dialogs) was removed in the
 * Part 0 change, along with the transport's switchGroupIdentity
 * leave+rejoin path it drove. There is intentionally nothing to
 * toggle here — the reveal option is structurally absent, not
 * merely hidden.
 */
@Composable
private fun IdentityCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.group_members_your_identity_here),
                color = app.aether.aegis.ui.theme.AegisCyan,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.group_members_anonymous_the_group_sees) +
                    "real name or avatar.",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                stringResource(R.string.group_members_groups_are_always_incognito) +
                    "your 1:1 chats — to let someone know who you are, add " +
                    "them as a contact.",
                color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * Group image card. Shows the group's
 * shared avatar; OWNER/ADMIN ([canManage]) get an Add/Change button
 * that runs [onPick]. Renders nothing for a MEMBER when no image is
 * set (no empty box for someone who can't fill it).
 *
 * The caution under the edit affordance is a security measure: since
 * every group is incognito, the picked photo must not reveal
 * the picker — it broadcasts to every member.
 *
 * @param avatarPath path to the group's shared avatar, or null/blank
 *   when none is set.
 * @param canManage OWNER/ADMIN — gates the Add/Change button and the
 *   privacy caution.
 * @param onPick runs the image picker; only wired when [canManage].
 */
@Composable
private fun GroupImageCard(
    avatarPath: String?,
    canManage: Boolean,
    onPick: () -> Unit,
) {
    // Nothing to show a MEMBER when there's no image AND they can't add
    // one — don't render an empty box for someone who can't fill it.
    if (avatarPath.isNullOrBlank() && !canManage) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                app.aether.aegis.ui.components.GroupAvatar(
                    avatarPath = avatarPath,
                    size = 64.dp,
                    glyphFontSize = 28.sp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.group_members_group_image),
                        color = app.aether.aegis.ui.theme.AegisCyan,
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (avatarPath.isNullOrBlank()) "No image set."
                        else "Shared with every member.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                if (canManage) {
                    OutlinedButton(onClick = onPick) {
                        Text(
                            if (avatarPath.isNullOrBlank()) stringResource(R.string.action_add) else stringResource(R.string.action_change),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            if (canManage) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    stringResource(R.string.group_members_make_sure_the_photo) +
                        "— it's the group's image, broadcast to every " +
                        "member, and this group is anonymous.",
                    color = app.aether.aegis.ui.theme.AegisWarning,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * Group description card. Shows the
 * group's shared "what this is" blurb to every member. OWNER/ADMIN
 * ([canManage]) get an Edit affordance → a 280-char multiline
 * dialog that calls [onSave]; MEMBERs see it read-only. When there
 * is no description AND the viewer can't edit, the card renders
 * nothing — no empty box for someone who can't fill it.
 *
 * @param description the shared blurb, or null/blank when unset.
 * @param canManage OWNER/ADMIN — gates the Edit affordance.
 * @param onSave invoked with the trimmed new description on save
 *   (capped at 280 chars at input).
 */
@Composable
private fun GroupDescriptionCard(
    description: String?,
    canManage: Boolean,
    onSave: (String) -> Unit,
) {
    // Same "no empty box for a non-editor" rule as GroupImageCard.
    if (description.isNullOrBlank() && !canManage) return
    // Compose state: whether the edit dialog is open.
    var editing by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.group_members_about_this_group),
                    color = app.aether.aegis.ui.theme.AegisCyan,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (canManage) {
                    OutlinedButton(onClick = { editing = true }) {
                        Text(
                            if (description.isNullOrBlank()) stringResource(R.string.action_add) else stringResource(R.string.action_edit),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                description?.takeIf { it.isNotBlank() } ?: "No description yet.",
                color = if (description.isNullOrBlank())
                    app.aether.aegis.ui.theme.AegisOnSurfaceDim
                else MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
            )
        }
    }

    if (editing) {
        var draft by remember { mutableStateOf(description.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.group_members_group_description)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.group_members_a_short_note_shown) +
                            "group is, the rules, who runs it.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft,
                        // Hard 280 cap enforced at input.
                        onValueChange = { draft = it.take(280) },
                        label = { Text(stringResource(R.string.group_members_description)) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${draft.length}/280",
                        fontSize = 11.sp,
                        color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    onSave(draft.trim())
                }) { Text(stringResource(R.string.group_members_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.secure_notes_cancel)) }
            },
        )
    }
}

/**
 * Owner/admin card to mint, share, and revoke a group join link — the
 * host-side half of group-link parity with the official client. Fetches
 * any existing link on open (so the same URL shows every time), offers to
 * create one if none, and surfaces the shortLinkDataSet "publishing" state
 * so the user doesn't share a `/g#` link before its data is on the server
 * (which would give the joiner invalidConnReq).
 *
 * @param sgid the SimpleX group id (NOT the Aegis-local id); the
 *   link API is keyed on it. Renders nothing if null or no transport.
 * @param transport the live SimpleX transport, or null when none.
 */
@Composable
private fun GroupLinkCard(
    sgid: Long?,
    transport: app.aether.aegis.simplex.SimpleXTransport?,
) {
    // No SimpleX group id / no transport → no link surface at all.
    if (sgid == null || transport == null) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // The fetched/created link, or null when none exists yet. Keyed on
    // sgid so navigating between groups re-fetches rather than reusing.
    var link by remember(sgid) {
        mutableStateOf<app.aether.aegis.simplex.SimpleXTransport.GroupShareLink?>(null)
    }
    // True during the initial fetch; gates the "Checking…" placeholder.
    var loading by remember(sgid) { mutableStateOf(true) }
    // True while a create / revoke call is in flight; disables those
    // buttons so a slow round-trip can't be double-fired.
    var busy by remember(sgid) { mutableStateOf(false) }

    // Fetch any existing link on open so the SAME URL shows each time
    // rather than minting a fresh one. Best-effort; null on failure.
    LaunchedEffect(sgid) {
        loading = true
        link = runCatching { transport.apiGetGroupLink(sgid) }.getOrNull()
        loading = false
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text("Group link", fontWeight = FontWeight.SemiBold)
            Text(
                "Anyone with this link can join the group.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            val current = link
            when {
                loading -> Text(
                    stringResource(R.string.settings_checking),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                current == null -> Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            link = runCatching { transport.apiCreateGroupLink(sgid) }.getOrNull()
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) stringResource(R.string.group_creating) else "Create group link") }
                else -> {
                    Text(
                        current.url,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!current.shortLinkDataSet) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Publishing the link… give it a few seconds before sharing, " +
                                "or it may not open for the other person yet.",
                            color = app.aether.aegis.ui.theme.AegisWarning,
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as? android.content.ClipboardManager
                                cm?.setPrimaryClip(
                                    android.content.ClipData.newPlainText("group link", current.url),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.diagnostics_copy), fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, current.url)
                                }
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent.createChooser(send, "Share group link"),
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Share", fontSize = 12.sp) }
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val ok = runCatching { transport.apiDeleteGroupLink(sgid) }
                                    .getOrDefault(false)
                                if (ok) link = null
                                busy = false
                            }
                        },
                    ) {
                        Text("Revoke link", color = app.aether.aegis.ui.theme.AegisSOS, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

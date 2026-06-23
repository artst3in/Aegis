package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisButton
import app.aether.aegis.ui.components.AegisOutlinedButton

import app.aether.aegis.AegisApp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisTopBar
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.theme.AegisCyan
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

/**
 * Contact detail / "tap their name" sheet. Shows the peer's announced
 * profile (display name, bio, avatar) as broadcast by their Aegis,
 * plus an editable local-nickname field that overrides what we call
 * them in chat list / status / map / widget.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(peerKey: String, navController: NavController) {
    val realPeers by AegisApp.instance.repository.observeKnownPeers().collectAsState(initial = emptyList())
    // Under a duress unlock, resolve against the DECOY roster, not the
    // real one — a decoy key wouldn't match a real peer anyway, and we
    // must never render a real contact's detail under a decoy session.
    val inDuress = AegisApp.instance.lockState.inDuressMode
    val peers = if (inDuress) app.aether.aegis.decoy.DecoyFixtures.peers() else realPeers
    val peer = peers.firstOrNull { it.publicKey == peerKey }
    val scope = rememberCoroutineScope()
    var nickname by remember(peerKey, peer?.displayName) { mutableStateOf(peer?.displayName.orEmpty()) }

    // The random incognito alias WE present to this contact. Aegis pairs
    // every contact incognito, so we appear under a different generated
    // name to each one — fetch it from the core so the user can finally
    // answer "what name do you have on here?". Skipped under duress (the
    // decoy peer has no real core contact to query).
    var myAlias by remember(peerKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(peerKey, inDuress) {
        if (inDuress) return@LaunchedEffect
        myAlias = runCatching {
            AegisApp.instance.transports
                .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                .firstOrNull()
                ?.myIncognitoNameFor(peerKey)
        }.getOrNull()
    }

    // Own the status-bar inset (bare Column, not a Scaffold); TopAppBar
    // insets zeroed so we don't pad twice.
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        AegisTopBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(stringResource(R.string.contact_title)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    AegisIcon(app.aether.aegis.ui.components.AegisIcons.Back, stringResource(R.string.action_back))
                }
            },
        )

        // Scroll-wrap everything below the TopAppBar. Without this the
        // Remote Access panel (last child) overflows the screen on
        // anything shorter than a tablet — exact bug that meant Locate
        // was unreachable on a Pixel.
        Column(
            modifier = Modifier
                .weight(1f, fill = true)
                .verticalScroll(rememberScrollState()),
        ) {

        if (peer == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.contact_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        // Avatar — wrapped in a 4 dp ring whose colour tracks the
        // peer's reported Shield tier. Same precedence as ChatList +
        // chat header. Border vanishes when the peer hasn't reported
        // a tier yet (still happy default cyan-on-cyan composition).
        val avatarTier = remember(peer.peerReportedTier) {
            runCatching { app.aether.aegis.admin.ShieldTier.valueOf(peer.peerReportedTier.orEmpty()) }
                .getOrNull()
        }
        // Trust gate (2026.06 security fix): only a TRUSTED contact's announced
        // avatar is rendered — never a stranger's chosen face (impersonation /
        // leak vector). Fail closed: anything not explicitly TRUSTED → no photo,
        // so the canonical avatar falls back to the engraved-initial metal plate
        // (the letter is from YOUR label, no impersonation concern).
        val trusted = peer.trustTier == app.aether.aegis.data.TrustTier.TRUSTED.name
        val avatar = peer.announcedAvatarPath?.takeIf { trusted && File(it).exists() }
        val initial = peer.displayName
            .ifBlank { peer.announcedName.orEmpty() }
            .trim().take(1).uppercase().ifBlank { "?" }
        Box(
            modifier = Modifier
                .padding(top = 24.dp)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center,
        ) {
            // Canonical avatar — same metal-frame + engraved-plate renderer as
            // every other surface (was a bespoke HexShape with an opaque surface
            // fill and a dim AegisBorder ring when no tier).
            app.aether.aegis.ui.components.AegisAvatar(
                size = 120.dp,
                tier = avatarTier,
                initial = initial,
                avatarPath = avatar,
            )
        }

        // Their announced name + bio (read-only).
        // YOUR label wins over a peer's self-announced name — a peer can
        // assert announcedName via [aegis:identity], so it must never
        // override the nickname you set. Consistent with the chat list,
        // chat header, and group member rows, which all prefer displayName.
        // The announced name only surfaces here if you never set a nickname.
        Text(
            peer.displayName.ifBlank { peer.announcedName ?: peer.displayName },
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.headlineSmall,
        )
        val announcedBio = peer.announcedBio
        if (!announcedBio.isNullOrBlank()) {
            Text(
                announcedBio,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            peer.publicKey,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The random alias WE present to THIS contact. Because Aegis is
        // always incognito, every contact sees a different generated name
        // — surface it so "what's your name on here?" finally has an
        // answer. Only shown once the core has returned it.
        myAlias?.let { alias ->
            GlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.contact_detail_you_appear_to_them),
                        color = AegisCyan,
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        alias,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        stringResource(R.string.contact_detail_a_random_alias_unique) +
                            "talk to sees a different one, and they can't be linked together.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
        }

        // Editable local nickname.
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = { Text(stringResource(R.string.contact_my_nickname)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            supportingText = {
                Text(
                    stringResource(R.string.contact_detail_local_only_they_dont),
                    fontSize = 11.sp,
                )
            },
        )
        // Pin lives alone on its own row now. Mute moved DOWN into the
        // notification group below so the two notification controls
        // (mute + sound) sit together where the user looks for them —
        // user-reported "per-contact mute … near notification sound".
        AegisOutlinedButton(
            onClick = {
                scope.launch {
                    AegisApp.instance.repository.setPeerPinned(peer.publicKey, !peer.pinned)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(if (peer.pinned) stringResource(R.string.contact_unpin) else stringResource(R.string.contact_pin))
        }

        AegisOutlinedButton(
            onClick = {
                val encoded = java.net.URLEncoder.encode(peer.publicKey, "UTF-8")
                navController.navigate("verify/$encoded")
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                if (peer.verified) "✓ Verified — view safety code"
                else "Verify safety code",
            )
        }

        // Trust tier picker. One control,
        // three buttons. Replaces every per-feature share toggle the
        // old layout carried — the tier IS the decision. Each
        // transition shows a tier-specific confirmation; promoting
        // Untrusted → Trusted is two-step because that's the most
        // consequential jump.
        // Trust tiers gate Aegis-specific behaviour (sos broadcast
        // routing, status share scope, remote AUTH eligibility) —
        // none of which a vanilla SimpleX peer can act on. Showing
        // the picker for non-Aegis contacts implies the choice has
        // meaning when it doesn't. Same gate as RemoteAccessPanel.
        if (peer.isAegis) {
            TrustTierPicker(peer = peer)
        }
        // Per-contact notification sound. Picks a system ringtone via
        // RingtoneManager — same picker the rest of Android uses, so the
        // user sees their existing tones (silent included). Sound is
        // pinned to a per-peer NotificationChannel created on demand.
        val soundLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val uri: android.net.Uri? = androidx.core.content.IntentCompat.getParcelableExtra(
                    result.data ?: return@rememberLauncherForActivityResult,
                    android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    android.net.Uri::class.java,
                )
                scope.launch {
                    AegisApp.instance.repository.setPeerNotificationSoundUri(
                        peer.publicKey, uri?.toString(),
                    )
                }
            }
        }
        val context = androidx.compose.ui.platform.LocalContext.current

        // ── Notifications group ──────────────────────────────────────
        // Section header so the two per-contact notification controls
        // (mute + custom sound) read as one cluster. Both write straight
        // to the KnownPeer row.
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.contact_detail_notifications),
            color = app.aether.aegis.ui.theme.AegisCyan,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
        )

        // Per-contact mute — silences THIS contact's message
        // notifications without touching anyone else. SOS / emergency
        // routing is unaffected; mute is a notification-surface toggle,
        // not a trust change. Moved here from the old Pin/Mute row so it
        // sits beside the custom sound.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.contact_mute), fontWeight = FontWeight.SemiBold)
                Text(
                    if (peer.muted)
                        "Muted — no notifications from this contact."
                    else "Notifications on for this contact.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            app.aether.aegis.ui.components.HexSwitch(
                checked = peer.muted,
                // Protected Mode: muting is gated under
                // contact management. A child could mute a Trusted contact
                // without demoting them — the parent's SOS/location arrive
                // but silently, functionally invisible. Same silent-sever
                // family as a demotion, so it locks with contacts.
                enabled = !isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.CONTACTS),
                onCheckedChange = { now ->
                    scope.launch {
                        AegisApp.instance.repository.setPeerMuted(peer.publicKey, now)
                    }
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.contact_notification_sound), fontWeight = FontWeight.SemiBold)
                Text(
                    peer.notificationSoundUri.let { uri ->
                        if (uri.isNullOrBlank()) "default"
                        else ringtoneDisplayName(context, uri)
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                // The ringtone picker is a separate system Activity, so
                // launching it backgrounds Aegis. Tell the lock gate this
                // backgrounding is intentional so auditioning tones for a
                // while doesn't trip the idle relock on return — user
                // bug 2026-06-07. See LockState.armPickerReturn.
                AegisApp.instance.lockState.armPickerReturn()
                val intent = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Notification sound")
                    peer.notificationSoundUri?.let {
                        putExtra(
                            android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            android.net.Uri.parse(it),
                        )
                    }
                }
                soundLauncher.launch(intent)
            }) { Text(stringResource(R.string.action_change)) }
        }

        AegisButton(
            enabled = nickname.trim() != peer.displayName && nickname.trim().isNotEmpty(),
            onClick = {
                scope.launch {
                    AegisApp.instance.repository.renameKnownPeer(peer.publicKey, nickname.trim())
                    navController.popBackStack()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text(stringResource(R.string.contact_save_nickname)) }

        // Folder picker (Telegram-style chat
        // folders). Free-form text input so the user can name their
        // own categories (Family / Allies / Work / Group A / …);
        // empty value clears the assignment. Saves on Done so the
        // chip row on the chat list updates immediately.
        var folderInput by remember(peerKey, peer.folder) {
            mutableStateOf(peer.folder.orEmpty())
        }
        OutlinedTextField(
            value = folderInput,
            onValueChange = { folderInput = it },
            label = { Text(stringResource(R.string.note_editor_folder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            supportingText = {
                Text(
                    stringResource(R.string.contact_detail_group_this_contact_under) +
                        "Leave blank to unfile.",
                    fontSize = 11.sp,
                )
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = {
                    scope.launch {
                        AegisApp.instance.repository.setPeerFolder(
                            peer.publicKey,
                            folderInput,
                        )
                    }
                },
            ),
        )

        // Live device status (battery, network, location, last seen)
        // — surfaced here for Trusted peers because Trusted is the
        // tier that authorises sharing of routine telemetry both
        // directions. Untrusted /
        // Emergency tiers show "—" or "hidden" rather than no panel
        // so the user can see WHY the slot is empty.
        TrustedPeerStatusPanel(peer = peer)

        // Remote access intentionally lives in the Radar tab, not here.
        // Locating / siren / wipe / etc. are *operational* actions for
        // a phone — they belong next to the device-status surface
        // (Radar → device), not next to relationship management
        // (nickname, mute, tier, delete) which is what this screen
        // is about. User-directed split 2026.05.30.

        // Delete contact — destructive, behind a confirm dialog.
        // Their verified-security badges —
        // shown only for TRUSTED contacts, since badges are a
        // trust-circle verification signal. Decoy peers
        // under a duress unlock have no stored badges, so this stays
        // blank without special-casing.
        if (peer.trustTier == app.aether.aegis.data.TrustTier.TRUSTED.name) {
            // Real badges normally; a stable random decoy set under a
            // duress unlock. Never reads the real
            // PeerBadgeStore on the duress path.
            val theirBadges = if (inDuress) {
                app.aether.aegis.decoy.DecoyBadges.forSeed(peer.publicKey)
            } else {
                app.aether.aegis.achievements.PeerBadgeStore.get(peer.publicKey)
            }
            if (theirBadges.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    stringResource(R.string.contact_detail_their_achievements),
                    color = app.aether.aegis.ui.theme.AegisCyan,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
                app.aether.aegis.ui.components.AchievementBadgeList(
                    earnedIds = theirBadges,
                    showUnearned = false,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Swipe-to-delete on the chat list was explicitly ruled out
        // ("too risky") so the only entry point is here, after the
        // user has navigated INTO the contact detail. Wipes the
        // 1:1 conversation, the last-known status, any pending
        // outbox, and the known-peer row itself.
        // Re-pair — for when the peer's SimpleX queue is dead (they
        // wiped + reinstalled, got a new phone, lost their PIN) but
        // we want to keep the existing contact row + chat history.
        // Arms RepairIntent then sends the user through the standard
        // Accept-invite flow. When pairing completes, bindContact
        // sees the armed target and runs Repository.repairKnownPeer
        // instead of creating a duplicate "Zippy 2".
        var confirmRepair by remember { mutableStateOf(false) }
        Spacer(modifier = Modifier.height(24.dp))
        AegisOutlinedButton(
            onClick = { confirmRepair = true },
            // Protected Mode: re-pair REPLACES this contact's SimpleX queue
            // with whatever link is accepted next — i.e. a child could
            // overwrite a trusted lifeline with an attacker's link. Gate it
            // with the same CONTACTS lock as add/delete.
            enabled = !isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.CONTACTS),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text(stringResource(R.string.contact_detail_repair_contact)) }
        if (confirmRepair) {
            AlertDialog(
                onDismissRequest = { confirmRepair = false },
                title = { Text("Re-pair with ${peer.displayName}?") },
                text = {
                    Text(
                        "Use this when ${peer.displayName}'s previous " +
                            "pairing is dead — they wiped Aegis, got a " +
                            "new phone, or you both lost your sync. " +
                            "Ask them to send a fresh invite link, then " +
                            "tap Continue. The new SimpleX queue " +
                            "attaches to THIS contact row instead of " +
                            "creating a duplicate; chat history, name, " +
                            "avatar, and trust tier are preserved even " +
                            "if their identity changed.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRepair = false
                        app.aether.aegis.contact.RepairIntent.arm(peer.publicKey)
                        // Registered route is "contact/add/accept"
                        // (MainActivity NavHost). The old
                        // "add-contact-accept" string here threw
                        // IllegalArgumentException because Compose
                        // Navigation crashes on unknown routes —
                        // user-reported crash on tapping Continue.
                        navController.navigate("contact/add/accept")
                    }) { Text(stringResource(R.string.tutorial_continue)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRepair = false }) {
                        Text(stringResource(R.string.secure_notes_cancel))
                    }
                },
            )
        }

        // Symmetric duplicate-merge — the fix for "after a re-pair the
        // contact shows up twice, and the OTHER side always sees me as new."
        // Re-pairing makes a brand-new SimpleX connection with no link back to
        // the old contact, and the initiating-side RepairIntent only merges on
        // ONE side. This lets EITHER side, independently and without a wipe,
        // absorb the old/dead duplicate into the live one: open the
        // freshly-connected contact, pick the old duplicate, and its name +
        // trust tier + chat history move here while THIS contact's live
        // connection survives. Reuses repairKnownPeer(old=picked, new=this) —
        // user-confirmed, never silent (a wrong auto-merge would route your
        // messages to the wrong person).
        var showMergePicker by remember { mutableStateOf(false) }
        var mergeTarget by remember { mutableStateOf<app.aether.aegis.data.KnownPeerEntity?>(null) }
        // Auto-surface a LIKELY duplicate: another contact with the same
        // display name (after a re-pair, both rows show the peer's real name
        // once identity is exchanged). One tap opens the SAME confirmed merge
        // — discovery only, the merge itself is never automatic.
        val possibleDup = remember(peers, peerKey, peer.displayName) {
            if (peer.displayName.isBlank()) null
            else peers.firstOrNull {
                it.publicKey != peerKey &&
                    it.displayName.equals(peer.displayName, ignoreCase = true)
            }
        }
        possibleDup?.let { dup ->
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(androidx.compose.foundation.shape.CutCornerShape(8.dp))
                    .background(app.aether.aegis.ui.theme.AegisWarning.copy(alpha = 0.12f))
                    .clickable { mergeTarget = dup }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Looks like a duplicate of “${dup.displayName}” — tap to merge them.",
                    fontSize = 13.sp,
                    color = app.aether.aegis.ui.theme.AegisWarning,
                )
            }
        }
        AegisOutlinedButton(
            onClick = { showMergePicker = true },
            enabled = !isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.CONTACTS),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text("Merge a duplicate into this contact") }
        if (showMergePicker) {
            val others = peers.filter { it.publicKey != peerKey }
            AlertDialog(
                onDismissRequest = { showMergePicker = false },
                title = { Text("Merge into ${peer.displayName}") },
                text = {
                    if (others.isEmpty()) {
                        Text("No other contacts to merge.")
                    } else {
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        ) {
                            Text(
                                "Pick the OLD / duplicate contact. Its chat history, " +
                                    "name and trust tier move here; it is removed; THIS " +
                                    "contact's current connection is what survives.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            others.forEach { o ->
                                Text(
                                    o.displayName.ifBlank { o.publicKey.take(14) + "…" },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { mergeTarget = o; showMergePicker = false }
                                        .padding(vertical = 12.dp),
                                    fontSize = 15.sp,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMergePicker = false }) {
                        Text(stringResource(R.string.secure_notes_cancel))
                    }
                },
            )
        }
        mergeTarget?.let { t ->
            AlertDialog(
                onDismissRequest = { mergeTarget = null },
                title = { Text("Merge ${t.displayName} here?") },
                text = {
                    Text(
                        "${t.displayName}'s chat history, name and trust tier will " +
                            "move into this contact, and ${t.displayName} will be " +
                            "removed. This contact's current connection is kept. " +
                            "This can't be undone.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val absorbedKey = t.publicKey
                        mergeTarget = null
                        scope.launch {
                            // old = the picked duplicate (absorbed), new = THIS
                            // contact (its live connection survives, adopting the
                            // picked contact's identity + history).
                            runCatching {
                                AegisApp.instance.repository.repairKnownPeer(absorbedKey, peerKey)
                            }
                        }
                    }) { Text("Merge") }
                },
                dismissButton = {
                    TextButton(onClick = { mergeTarget = null }) {
                        Text(stringResource(R.string.secure_notes_cancel))
                    }
                },
            )
        }

        var confirmDelete by remember { mutableStateOf(false) }
        Spacer(modifier = Modifier.height(8.dp))
        AegisOutlinedButton(
            onClick = { confirmDelete = true },
            // Protected Mode: contact management can be locked so a child
            // can't delete their own lifeline (e.g. the parent contact).
            enabled = !isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.CONTACTS),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text(stringResource(R.string.contact_detail_delete_contact)) }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete ${peer.displayName}?") },
                text = {
                    Text(
                        stringResource(R.string.contact_detail_removes_the_contact_every) +
                            "conversation, their last-known status, and any " +
                            "outbound messages still queued. They keep their " +
                            "copy on their device. You'll need to re-pair to " +
                            "talk again. This can't be undone.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch {
                            AegisApp.instance.repository.removeKnownPeer(peer.publicKey)
                            navController.popBackStack()
                        }
                    }) {
                        Text(stringResource(R.string.secure_notes_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.secure_notes_cancel)) }
                },
            )
        }
        }  // close scrollable inner Column
    }
}

/**
 * Auth-gated remote-access panel.
 *
 * Three states:
 *   1. Idle — single "Remote access" button. Tap → PIN sheet.
 *   2. Active — peer's PIN verified, in-memory session open. Shows
 *      the first LOCATE result + buttons for LOCATE / SIREN / WIPE.
 *      LOCATE re-fires lock + GPS (and re-fires the mugshot capture
 *      once that pipeline lands). SIREN confirms once. WIPE walks
 *      the three-gate ladder: "Are you sure?" → "type yes" → "final
 *      warning, unrecoverable."
 *   3. Revoked — peer revoked us (manual or auto after 3-in-60s PIN
 *      failures). Button is grayed. Resolves itself the next time
 *      they reauthorise.
 *
 * Manual revoke (the OPPOSITE direction — "I revoke THIS peer's
 * ability to access ME") lives in [RemoteAccessControlRow] just
 * below, observing [RemoteAccessGate.revoked].
 */
/**
 * "Everything I know about this peer's device" panel — battery,
 * network, last seen, current location. Only populated when the
 * peer is Trusted: that's the only tier authorised to receive (and
 * therefore receive-from) routine status broadcasts under the
 * trust model. For Untrusted / Emergency tiers we show the
 * row but explain WHY the data is blank so the user understands
 * the tier IS the gate rather than a missing feature.
 */
@Composable
private fun TrustedPeerStatusPanel(peer: app.aether.aegis.data.KnownPeerEntity) {
    val tier = runCatching { app.aether.aegis.data.TrustTier.valueOf(peer.trustTier) }
        .getOrDefault(app.aether.aegis.data.TrustTier.UNTRUSTED)
    val status by AegisApp.instance.repository.observeStatus(peer.publicKey)
        .collectAsState(initial = null)
    app.aether.aegis.ui.components.GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.contact_detail_device_status),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (tier != app.aether.aegis.data.TrustTier.TRUSTED) {
                Text(
                    when (tier) {
                        app.aether.aegis.data.TrustTier.EMERGENCY ->
                            "Emergency tier — routine status (battery, network, " +
                                "location) is not shared. SOS alerts still " +
                                "come through."
                        app.aether.aegis.data.TrustTier.UNTRUSTED ->
                            if (peer.isAegis)
                                "Untrusted tier — no status data is exchanged in " +
                                    "either direction. Promote to Trusted to see this."
                            else
                                "${peer.displayName} isn't running Aegis — a plain " +
                                    "SimpleX chat contact. No status, location, or SOS " +
                                    "in either direction, and they can't be promoted to " +
                                    "a trust tier. They'd need to install Aegis first."
                        else -> ""
                    },
                    color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                    fontSize = 11.sp,
                )
                return@Column
            }

            val s = status ?: run {
                Text(
                    stringResource(R.string.contact_detail_no_data_received_yet) +
                        " Cadence is ~5 minutes.",
                    color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                    fontSize = 11.sp,
                )
                return@Column
            }
            val battery = s.batteryLevel
            val charging = s.isCharging == true
            val net = s.networkType
            val lat = s.latitude
            val lng = s.longitude
            val lastSeen = s.lastActive
            val nowMs = System.currentTimeMillis()

            // Battery row gets a charging-icon variant so the
            // lightning-bolt LunaGlass vector replaces the ⚡ emoji.
            BatteryRow(battery, charging)
            DeviceStatusRow("Network", net ?: "—")
            DeviceStatusRow("Last seen", relativeAge(lastSeen, nowMs))
            if (lat != null && lng != null) {
                DeviceStatusRow(stringResource(R.string.chat_location), "%.4f, %.4f".format(lat, lng))
            } else {
                DeviceStatusRow(stringResource(R.string.chat_location), "no GPS fix yet")
            }
            DeviceStatusRow("Aegis version", s.appVersion ?: "—")

            // Wearable telemetry — paired watch / band biometrics
            // (heart rate, HRV, SpO₂). Pre-existing fields on the
            // MemberStatusEntity but the trusted panel was only
            // surfacing the phone-side data. Same row treatment so
            // the user gets the FULL picture from a single screen.
            val hr = s.heartRate
            val hrv = s.hrv
            val spo2 = s.spo2
            if (hr != null || hrv != null || spo2 != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.contact_detail_wearable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                hr?.let { DeviceStatusRow("Heart rate", "$it bpm") }
                hrv?.let { DeviceStatusRow("HRV", "$it ms") }
                spo2?.let { DeviceStatusRow("SpO₂", "$it %") }
            }
        }
    }
}

@Composable
private fun DeviceStatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.4f),
            color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
            fontSize = 12.sp,
        )
        Text(
            value,
            modifier = Modifier.weight(0.6f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Battery row — value cell carries an inline LunaGlass charging
 *  glyph when the peer's plug is in, so the % readout matches the
 *  visual language of the rest of the panel instead of mixing in a
 *  ⚡ emoji. */
@Composable
private fun BatteryRow(battery: Int?, charging: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.contact_detail_battery),
            modifier = Modifier.weight(0.4f),
            color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
            fontSize = 12.sp,
        )
        Row(
            modifier = Modifier.weight(0.6f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (battery == null) "—" else "$battery %",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (charging) {
                Spacer(modifier = Modifier.width(4.dp))
                app.aether.aegis.ui.components.AegisIcon(
                    icon = app.aether.aegis.ui.components.AegisIcons.Charging,
                    contentDescription = stringResource(R.string.map_charging),
                    tint = app.aether.aegis.ui.theme.AegisOnline,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private fun relativeAge(ts: Long?, now: Long): String {
    if (ts == null || ts <= 0) return "never"
    val delta = (now - ts).coerceAtLeast(0)
    val sec = delta / 1000
    return when {
        sec < 60    -> "${sec}s ago"
        sec < 3600  -> "${sec / 60}m ago"
        sec < 86400 -> "${sec / 3600}h ago"
        else        -> "${sec / 86400}d ago"
    }
}

@Composable
internal fun RemoteAccessPanel(peer: app.aether.aegis.data.KnownPeerEntity, navController: NavController) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessions by app.aether.aegis.remote.RemoteAccessSession.sessions.collectAsState()
    val revokedBy by app.aether.aegis.remote.RemoteAccessSession.revokedBy.collectAsState()
    val active = sessions[peer.publicKey]
    val isRevokedByPeer = peer.publicKey in revokedBy

    var pinSheetOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var deniedFlash by remember { mutableStateOf(false) }
    var timeoutFlash by remember { mutableStateOf(false) }

    // Subscribe to AUTH_DENIED bus so the PIN sheet knows to dismiss
    // and show the toast. Unsubscribes on dispose / peer change.
    DisposableEffect(peer.publicKey) {
        val unsub = app.aether.aegis.remote.DeniedBus.subscribe { from ->
            if (from == peer.publicKey) {
                pending = false
                pinSheetOpen = false
                deniedFlash = true
            }
        }
        onDispose(unsub)
    }

    // Safety timeout — the "Waiting for response…" state stayed
    // forever if the target was offline, the packet got dropped, or
    // SimpleX never delivered. AUTH_DENIED clears via DeniedBus and
    // AUTH_OK clears via the `active` branch above; this catches the
    // third case (no response). 25 s matches the existing LOCATE
    // safety timeout — long enough that a slow round-trip over
    // Tor still gets in, short enough that an offline target
    // surfaces as a clear failure rather than a hung
    // button. User-reported "waiting for response forever" 2026.05.640.
    LaunchedEffect(pending) {
        if (pending) {
            kotlinx.coroutines.delay(25_000L)
            if (pending) {
                pending = false
                timeoutFlash = true
            }
        }
    }
    // Clear the timeout banner the next time the user starts a new
    // attempt — otherwise a previous failure's message would linger
    // under the freshly-pressed button.
    LaunchedEffect(pinSheetOpen) {
        if (pinSheetOpen) timeoutFlash = false
    }

    // Vanilla-SimpleX gate. isAegis flips true on the first inbound
    // `[aegis:...]` envelope we see from the peer (see
    // SimpleXTransport.handleNewChatItems). For peers who've never
    // sent one — vanilla SimpleX clients on the other end — none
    // of the remote-access commands would land anywhere meaningful;
    // worse, the AUTH packet itself ([aegis:remote]{...} with the
    // PIN inside) would show up as a plain chat message in their
    // app. Don't offer the surface at all.
    if (!peer.isAegis) {
        app.aether.aegis.ui.components.GlassPanel(
            modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    stringResource(R.string.remote_access_remote_access),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                )
                Text(
                    "Available only when ${peer.displayName} is also running " +
                        "Aegis. Sending control commands to a vanilla SimpleX " +
                        "client would just deliver them as plain chat text.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
        return
    }

    app.aether.aegis.ui.components.GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.remote_access_remote_access),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
            )
            Text(
                "Enter ${peer.displayName}'s PIN to lock + locate their phone " +
                    "instantly. Session opens for 5 min; from there you can " +
                    "re-locate, sound the siren, or factory-reset.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Active session — deep-link to the unified
            // DeviceControlScreen. The legacy inline RemoteModeActive
            // path was removed in the same cleanup pass that dropped
            // LegacyDashboardsToggle; only the new surface ships.
            when {
                active != null -> {
                    androidx.compose.runtime.LaunchedEffect(active.sid) {
                        val encoded = java.net.URLEncoder.encode(peer.publicKey, "UTF-8")
                        navController.navigate("device-control/remote/$encoded")
                    }
                    Text(
                        stringResource(R.string.contact_detail_opening_remote_control),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                // Revoked-by-peer stays tappable. Earlier this button
                // was disabled, which made the state look terminal —
                // the user had no way to retry after the target had
                // unrevoked them locally (gate.unrevoke clears the
                // server-side block but doesn't send an over-the-wire
                // notice, so the requester's cached revokedBy entry
                // sticks). Tapping retries: if the target is still
                // blocking, AUTH_DENIED keeps the flag; if they've
                // unblocked, AUTH_OK calls RemoteAccessSession.open()
                // which clears revokedBy automatically (line 82).
                // 2026.05.638 user feedback: "revoke is permanent."
                isRevokedByPeer -> AegisButton(
                    enabled = !pending,
                    onClick = { pinSheetOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (pending) "Waiting for response…"
                        else "Revoked by ${peer.displayName} — retry",
                        fontSize = 12.sp,
                    )
                }
                else -> AegisButton(
                    enabled = !pending,
                    onClick = { pinSheetOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (pending) "Waiting for response…" else stringResource(R.string.device_status_remote_access), fontSize = 13.sp) }
            }

            if (deniedFlash) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.contact_detail_access_denied_wrong_pin),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
            if (timeoutFlash) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "No response — ${peer.displayName}'s phone may be offline. " +
                        "Try again when their device is back online.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
        }
    }

    if (pinSheetOpen) {
        RemoteAccessPinSheet(
            peer = peer,
            onDismiss = { pinSheetOpen = false; pending = false },
            onSubmit = { pin ->
                pending = true
                deniedFlash = false
                scope.launch {
                    // Operator-side duress trap (SPEC remote-duress: "opening a
                    // session under coercion"). If the operator enters their OWN
                    // duress PIN here — forced to open a remote session — fire a
                    // SILENT SOS on THIS (the operator's) phone, do NOT send the
                    // auth, and surface a fake connection failure. The coercer
                    // just sees a session that won't open. verifyPin is Argon2id,
                    // so it runs off the main thread.
                    val duress = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        runCatching {
                            val m = app.aether.aegis.lock.LockStore(context).verifyPin(pin)
                            m == app.aether.aegis.lock.LockStore.PinMatch.DURESS_1 ||
                                m == app.aether.aegis.lock.LockStore.PinMatch.DURESS_2
                        }.getOrDefault(false)
                    }
                    if (duress) {
                        runCatching {
                            AegisApp.instance.sosHandler.trigger(
                                app.aether.aegis.core.SOSTrigger.DURESS,
                            )
                        }
                        pending = false
                        timeoutFlash = true // looks like "no response — offline"
                        return@launch
                    }
                    // Record the attempt so a duress-SOS bounced back by the
                    // target (when this PIN is ITS duress code) is honoured even
                    // though no session opens — see onDuressSos's recent-auth gate.
                    app.aether.aegis.remote.RemoteAccessSession.markAuthSent(peer.publicKey)
                    AegisApp.instance.protocolManager.sendMessage(
                        to = peer.publicKey,
                        content = app.aether.aegis.remote.RemoteAccessProtocol.encode(
                            app.aether.aegis.remote.RemoteAccessProtocol.Packet(
                                kind = app.aether.aegis.remote.RemoteAccessProtocol.KIND_AUTH,
                                pin = pin,
                            ),
                        ),
                        type = app.aether.aegis.core.MessageType.STATUS,
                    )
                }
                pinSheetOpen = false
            },
        )
    }

    // RemoteAccessControlRow ("I block THIS peer from accessing ME")
    // intentionally not rendered here — the enclosing
    // RemoteAccessScreen places it under its own layout below the
    // panel. Was duplicated 2026.05.637/638; flagged by user.
}


@Composable
private fun RemoteAccessPinSheet(
    peer: app.aether.aegis.data.KnownPeerEntity,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter ${peer.displayName}'s PIN") },
        text = {
            Column {
                Text(
                    stringResource(R.string.contact_detail_on_success_their_phone) +
                        "On failure, they are notified — 3 failures in 60 s " +
                        "auto-revokes your access.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tutorial_pin)) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = pin.length >= 4,
                onClick = { onSubmit(pin) },
            ) { Text(stringResource(R.string.contact_detail_authenticate)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// RemoteAccessControlRow (the per-contact "block their access to MY phone"
// toggle) was removed when remote-access control was centralised into the
// Remote Access Hub (settings/remote-access-hub). The hub's per-contact grant
// switch — turning a grant OFF revokes + tears down streams; turning it ON
// clears any sticky revoke — plus its "Revoke everyone" panic button now cover
// everything this row did, in one place, with no duplication.

/** Resolve a notification-sound URI to a human-readable name via
 *  RingtoneManager (e.g. "Beep", "Chime", "Silent"). Best-effort —
 *  on failure falls back to "custom". */
private fun ringtoneDisplayName(context: android.content.Context, uriStr: String): String {
    val uri = runCatching { android.net.Uri.parse(uriStr) }.getOrNull() ?: return "custom"
    val ringtone = runCatching { android.media.RingtoneManager.getRingtone(context, uri) }
        .getOrNull() ?: return "custom"
    return runCatching { ringtone.getTitle(context) }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "custom"
}

/**
 * Trust-tier picker for one contact.
 * Three segmented buttons — Untrusted / Emergency / Trusted —
 * mirror the three tiers in left-to-right ascending order. Tapping
 * a different tier opens a confirmation dialog with the
 * exact transition wording; the Untrusted → Trusted leap also
 * carries a second "really?" step because that's the largest
 * privacy jump.
 *
 * Demotions confirm with a single brief warning. Demotion to
 * Untrusted reads "they will no longer receive any data from you,
 * including SOS alerts. They can still message you."
 */
@Composable
private fun TrustTierPicker(peer: app.aether.aegis.data.KnownPeerEntity) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val current = runCatching { app.aether.aegis.data.TrustTier.valueOf(peer.trustTier) }
        .getOrDefault(app.aether.aegis.data.TrustTier.UNTRUSTED)
    var pending by remember { mutableStateOf<app.aether.aegis.data.TrustTier?>(null) }
    var confirmStep by remember { mutableStateOf(0) }

    Text(
        stringResource(R.string.contact_detail_trust_tier),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Text(
        stringResource(R.string.contact_detail_one_label_per_contact),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        app.aether.aegis.data.TrustTier.values().forEach { tier ->
            val selected = current == tier
            val tint = when (tier) {
                app.aether.aegis.data.TrustTier.TRUSTED   -> app.aether.aegis.ui.theme.AegisCyan
                app.aether.aegis.data.TrustTier.EMERGENCY -> app.aether.aegis.ui.theme.AegisSOS
                app.aether.aegis.data.TrustTier.UNTRUSTED -> app.aether.aegis.ui.theme.AegisOnSurfaceDim
            }
            AegisOutlinedButton(
                onClick = {
                    if (tier != current) {
                        pending = tier
                        confirmStep = 1
                    }
                },
                // Protected Mode: trust-tier changes can be locked. The
                // nightmare case is silent — a child demotes the parent and
                // their own SOS + location quietly stop reaching them.
                enabled = !isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.TRUST_TIER),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = if (selected) tint else MaterialTheme.colorScheme.onSurface,
                    containerColor = if (selected) tint.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent,
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) tint else MaterialTheme.colorScheme.outline,
                ),
                // Default OutlinedButton contentPadding is 24dp on each
                // side — three buttons sharing a row by weight(1f) on
                // a narrow phone (OnePlus reported 2026.05.30) end up
                // with ~50dp of usable text width, which wraps the
                // 9-char labels "Emergency" / "Untrusted". Shrink to
                // 8dp + force single line so the labels always fit
                // intact at 13sp.
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    tier.label,
                    fontSize = 13.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        when (current) {
            app.aether.aegis.data.TrustTier.TRUSTED   ->
                "Receives location, presence, status, and SOS alerts."
            app.aether.aegis.data.TrustTier.EMERGENCY ->
                "Receives only SOS alerts. No routine sharing."
            app.aether.aegis.data.TrustTier.UNTRUSTED ->
                "Receives nothing. Chat only."
        },
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )

    // Remote access is managed ONLY in the Remote Access Hub (Opsec skill node)
    // now — no status line and no grant prompt on the contact card (owner's
    // call: keep the contact card free of remote-access UI entirely).

    val target = pending
    if (target != null && confirmStep > 0) {
        // Per-transition confirmation copy.
        // Promotions read deliberately heavy; demotions read fast.
        // Untrusted → Trusted is the only two-step path.
        val isTwoStep = current == app.aether.aegis.data.TrustTier.UNTRUSTED &&
            target == app.aether.aegis.data.TrustTier.TRUSTED
        val title = when {
            target == app.aether.aegis.data.TrustTier.UNTRUSTED            -> "Demote to Untrusted?"
            current == app.aether.aegis.data.TrustTier.UNTRUSTED && target == app.aether.aegis.data.TrustTier.EMERGENCY -> "Add as Emergency contact?"
            current == app.aether.aegis.data.TrustTier.UNTRUSTED && target == app.aether.aegis.data.TrustTier.TRUSTED   -> "Make Trusted?"
            current == app.aether.aegis.data.TrustTier.EMERGENCY && target == app.aether.aegis.data.TrustTier.TRUSTED   -> "Promote to Trusted?"
            else                                                -> "Change tier?"
        }
        val body = when {
            target == app.aether.aegis.data.TrustTier.UNTRUSTED ->
                "They will no longer receive any data from you, including " +
                    "sos alerts. They can still message you.\n\n" +
                    // Aegis Protocol: honest copy — a
                    // demotion can't un-reveal an identity already sent.
                    "They already have your name. Demotion stops future " +
                    "updates, not memory."
            current == app.aether.aegis.data.TrustTier.UNTRUSTED && target == app.aether.aegis.data.TrustTier.EMERGENCY ->
                "This person will receive SOS alerts including your " +
                    "location, audio, and camera capture. They still won't " +
                    "see your routine activity.\n\n" +
                    // Aegis Protocol Stage 3: promotion reveals identity.
                    "They will also see your real name, photo, and bio — " +
                    "revealed to them now over the encrypted identity overlay."
            current == app.aether.aegis.data.TrustTier.UNTRUSTED && target == app.aether.aegis.data.TrustTier.TRUSTED ->
                if (isTwoStep && confirmStep == 1)
                    "This person will receive your location, presence, " +
                        "status, and SOS alerts continuously. This is the " +
                        "most consequential change in the app. Tap Continue " +
                        "to review one more time."
                else
                    "Confirm: routine location, presence, status, sensors, " +
                        "and SOS alerts will start flowing to this person on " +
                        "the next scheduled update.\n\n" +
                        "They will also see your real name, photo, and bio — " +
                        "revealed now over the encrypted identity overlay."
            current == app.aether.aegis.data.TrustTier.EMERGENCY && target == app.aether.aegis.data.TrustTier.TRUSTED ->
                "This person will now also receive your routine activity, " +
                    "not just emergencies."
            else -> ""
        }
        AlertDialog(
            onDismissRequest = { pending = null; confirmStep = 0 },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    if (isTwoStep && confirmStep == 1) {
                        // Bump to the second confirmation step.
                        confirmStep = 2
                    } else {
                        scope.launch {
                            AegisApp.instance.repository.setPeerTier(peer.publicKey, target)
                        }
                        pending = null
                        confirmStep = 0
                        // (Remote-access grant prompt removed — remote access is
                        // configured only in the Remote Access Hub now.)
                    }
                }) {
                    Text(if (isTwoStep && confirmStep == 1) stringResource(R.string.tutorial_continue) else stringResource(R.string.vault_pin_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null; confirmStep = 0 }) { Text(stringResource(R.string.secure_notes_cancel)) }
            },
        )
    }
}


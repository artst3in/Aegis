package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisTopBar

import app.aether.aegis.ui.components.AegisButton
import app.aether.aegis.ui.components.AegisOutlinedButton

import app.aether.aegis.AegisApp
import app.aether.aegis.peer.QrCodes
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import app.aether.aegis.ui.components.AegisIcon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pair a SimpleX contact. UI is a two-button decision tree, mirroring
 * the Signal / WhatsApp pattern:
 *
 *   ┌────────────────┐  ┌────────────────┐
 *   │  Invite        │  │  Accept        │
 *   │  someone       │  │  someone's     │
 *   │  (share my QR) │  │  invite        │
 *   └────────────────┘  └────────────────┘
 *
 * Either branch is a single linear flow — no more "two stacked cards
 * each with its own name field" wall. The local-name prompt happens
 * AFTER pairing succeeds (or after the user has the link in hand),
 * so the user isn't asked for a name before they know who they're
 * adding.
 */


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    navController: NavController,
    /** Optional starting mode — when the chat-list radial menu sends
     *  the user straight into Invite or Accept we skip the Pick step,
     *  so the first thing they see is the QR generator / scanner
     *  rather than a chooser they already chose on. Default is Pick
     *  so the route stays usable on its own. */
    start: Mode = Mode.Pick,
) {
    var mode by remember { mutableStateOf(start) }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = {
                    Text(
                        when (mode) {
                            Mode.Pick -> stringResource(R.string.contact_add)
                            Mode.Invite -> stringResource(R.string.add_contact_invite_someone)
                            Mode.Accept -> stringResource(R.string.contact_accept_invitation)
                            Mode.JoinGroup -> "Join a group"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // When we landed straight into Invite/Accept from
                        // the radial menu (start != Pick), back goes ALL
                        // the way to the chat list — no Pick to fall back
                        // to. Otherwise back collapses sub-mode → Pick.
                        if (mode == Mode.Pick || start != Mode.Pick) navController.popBackStack()
                        else mode = Mode.Pick
                    }) {
                        AegisIcon(app.aether.aegis.ui.components.AegisIcons.Back, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            when (mode) {
                Mode.Pick -> PickFlow(
                    onInvite = { mode = Mode.Invite },
                    onAccept = { mode = Mode.Accept },
                )
                Mode.Invite -> InviteFlow(onDone = { navController.popBackStack() })
                Mode.Accept -> AcceptFlow(
                    onDone = { navController.popBackStack() },
                    onJoinedGroup = { gid ->
                        // Open the group the link resolved to (already a
                        // member, or freshly joined) instead of popping back
                        // to an unchanged screen.
                        navController.navigate("group/$gid") {
                            popUpTo("chats")
                        }
                    },
                )
                Mode.JoinGroup -> JoinGroupFlow(
                    onDone = { navController.popBackStack() },
                    onJoinedGroup = { gid ->
                        navController.navigate("group/$gid") {
                            popUpTo("chats")
                        }
                    },
                )
            }
        }
    }
}

enum class Mode { Pick, Invite, Accept, JoinGroup }

/** Step 1 — pick which side of the pairing the user is on. Two big
 *  hex-tile buttons; intuitive without reading. */
@Composable
private fun PickFlow(onInvite: () -> Unit, onAccept: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.add_contact_pair_via_simplex_exchange) +
                "(in person, SMS, e-mail). Both sides must run Aegis.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        AddContactChoice(
            title = stringResource(R.string.add_contact_invite_someone),
            subtitle = stringResource(R.string.add_contact_generate_a_onetime_link),
            glyph = "↗",
            onClick = onInvite,
        )
        AddContactChoice(
            title = stringResource(R.string.contact_accept_invite),
            subtitle = stringResource(R.string.add_contact_they_sent_you_a),
            glyph = "↙",
            onClick = onAccept,
        )
    }
}

@Composable
private fun AddContactChoice(
    title: String,
    subtitle: String,
    glyph: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = app.aether.aegis.ui.theme.AegisSurface,
        shape = CutCornerShape(12.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            app.aether.aegis.ui.components.HexShape(
                size = 52.dp,
                borderColor = app.aether.aegis.ui.theme.AegisCyan,
                fillColor = Color.Transparent,
            ) {
                Text(glyph, fontSize = 22.sp, color = app.aether.aegis.ui.theme.AegisCyan)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Invite flow — generate a link immediately, surface the QR + share
 *  button up front, ask for the local nickname only at the end. */
@Composable
private fun InviteFlow(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<String?>(null) }
    var nickname by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val peers by AegisApp.instance.repository.observeKnownPeers()
        .collectAsState(initial = emptyList())
    // Snapshot the EXISTING contacts before doing anything — synchronously
    // off the DB rather than waiting for the observeKnownPeers Flow to
    // emit. The Flow's initial = emptyList() means peersAtMount captured
    // at the very first composition would have been empty, and every
    // peer the Flow later emitted would look "new", which is why
    // tapping Done renamed an EXISTING contact instead of binding a
    // freshly-paired one.
    var peersAtMount by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(Unit) {
        peersAtMount = withContext(Dispatchers.IO) {
            AegisApp.instance.repository.allKnownPeers().map { it.publicKey }.toSet()
        }
    }
    // Generate-on-mount — the user picked "Invite someone", they want
    // the link, not another button to tap first.
    LaunchedEffect(Unit) {
        if (link != null) return@LaunchedEffect
        busy = true
        val transport = AegisApp.instance.transports
            .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
        // Use a temporary placeholder label — we'll set the real
        // nickname after the peer connects. SimpleX requires SOME label
        // at /_connect time but we never surface it to the user.
        val tmpLabel = "pending-${System.currentTimeMillis() % 100_000}"
        // Hard timeout — the SimpleX call has been seen to hang
        // indefinitely when the core is wedged on first boot, leaving
        // the screen on "Generating link…" forever with no way to
        // back out and try again. 20 s is generous (cold-boot core
        // init is ~10 s) and the worst case is the user re-tries.
        val r = withTimeoutOrNull(20_000L) {
            transport?.generateInvitation(tmpLabel)
        }
        when (r) {
            is app.aether.aegis.simplex.SimpleXTransport.InviteResult.Ok -> link = r.uri
            is app.aether.aegis.simplex.SimpleXTransport.InviteResult.SimplexNotReady -> {
                val coreErr = app.aether.aegis.simplex.SimpleXCore.initError
                error = when {
                    coreErr != null -> "SimpleX failed to boot: $coreErr"
                    app.aether.aegis.simplex.SimpleXCore.initialised ->
                        "SimpleX is starting — try again in a moment."
                    else -> "SimpleX is initialising. This takes ~10 seconds on first launch."
                }
            }
            is app.aether.aegis.simplex.SimpleXTransport.InviteResult.ParseFailed ->
                error = "Couldn't parse SimpleX's reply. Try again."
            null -> error = if (transport == null)
                "SimpleX transport unavailable."
            else
                "SimpleX didn't respond in 20 s. Tap back and try again — " +
                    "or restart Aegis if it persists."
        }
        busy = false
    }
    // Detect when the peer connects — the known_peers list grows by one
    // and we can match the new entry by exclusion. Auto-advance the
    // user to "name them" once that happens.
    // Only treat a peer as "new" once we know the baseline. Until the
    // DB snapshot lands, EVERY currently-known peer would look new and
    // tapping Done would rename one of them.
    val newPeer = peersAtMount?.let { base ->
        peers.firstOrNull { it.publicKey !in base }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val currentError = error
        when {
            currentError != null -> Text(
                currentError,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            busy -> {
                CircularProgressIndicator()
                Text(
                    stringResource(R.string.add_contact_generating_link),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            link != null && newPeer == null -> {
                val currentLink = link  // smart-cast capture
                if (currentLink != null) {
                    // Stage 1: link ready, waiting for peer to accept.
                    Text(
                        stringResource(R.string.add_contact_show_this_qr_to),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    val bitmap = remember(currentLink) {
                        QrCodes.render(currentLink, 720).asImageBitmap()
                    }
                    // QR codes need a pure-white quiet zone for
                    // scanners to work reliably (every QR design
                    // guideline says this) — outside the LunaGlass
                    // palette by necessity, not taste. A documented
                    // exemption to the LunaGlass palette rule.
                    Surface(
                        color = Color.White,
                        shape = CutCornerShape(12.dp),
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = stringResource(R.string.add_contact_invitation_qr),
                            modifier = Modifier.size(260.dp).padding(8.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AegisOutlinedButton(
                            onClick = {
                                copyToClipboard(context, "aegis-invite", currentLink)
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.contact_copy_link)) }
                        AegisOutlinedButton(
                            onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, currentLink)
                                }
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent.createChooser(send, "Share invitation"),
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.action_share)) }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = app.aether.aegis.ui.theme.AegisCyan,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.add_contact_waiting_for_them_to),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            link != null && newPeer != null -> {
                // Stage 2: peer connected — name them.
                Text(
                    stringResource(R.string.add_contact_paired),
                    color = app.aether.aegis.ui.theme.AegisCyan,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                // Aegis Protocol (Stage 5): the contact paired under
                // a random handle — unreadable, so force a local nickname.
                Text(
                    "They joined as “${newPeer.displayName}”. Give them a name so " +
                        "you remember who they are — the handle is random.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text(stringResource(R.string.contact_nickname)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // One-time "give, don't ask" reassurance: they see a random
                // handle for us too, until we choose to reveal.
                Text(
                    stringResource(R.string.add_contact_they_see_you_as) +
                        "hidden until you promote them to Trusted or Emergency.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
                AegisButton(
                    onClick = {
                        scope.launch {
                            val finalName = nickname.trim()
                            AegisApp.instance.repository
                                .renameKnownPeer(newPeer.publicKey, finalName)
                            Toast.makeText(
                                context, "Added $finalName",
                                Toast.LENGTH_SHORT,
                            ).show()
                            onDone()
                        }
                    },
                    // Force a nickname — the random handle is unusable on its own.
                    enabled = !busy && nickname.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}

/** Accept flow — scan or paste, then name. */
@Composable
private fun AcceptFlow(onDone: () -> Unit, onJoinedGroup: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Is this Accept actually a RE-PAIR? ContactDetailScreen armed
    // RepairIntent right before navigating here. If so, the nickname is
    // IRRELEVANT (the new connection merges into the existing contact row,
    // keeping its name/history/tier) — and worse, typing the same name would
    // trip bindContact's localDisplayName-collision rename and CREATE a
    // duplicate. So we tell the user what's happening and hide the field.
    val repairing = remember { app.aether.aegis.contact.RepairIntent.peek() != null }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (repairing) {
            // Re-pair banner: reassure the user their existing contact +
            // history survive, and that no nickname is needed.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.CutCornerShape(8.dp))
                    .background(app.aether.aegis.ui.theme.AegisCyan.copy(alpha = 0.12f))
                    .padding(12.dp),
            ) {
                Text(
                    "Re-pairing an existing contact. Scan or paste their fresh " +
                        "invite link and accept — their name, chat history and " +
                        "trust tier are kept. No nickname needed.",
                    color = app.aether.aegis.ui.theme.AegisCyan,
                    fontSize = 13.sp,
                )
            }
        }
        Text(
            stringResource(R.string.add_contact_got_their_invitation_link),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )

        // Primary CTA — scan. Big, obvious, hex-bordered.
        AegisButton(
            onClick = {
                val activity = context as? app.aether.aegis.MainActivity
                activity?.scanQr("Scan the invitation QR from their phone") { scanned ->
                    if (scanned.isNotBlank()) link = scanned
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            app.aether.aegis.ui.components.AegisIcon(
                icon = app.aether.aegis.ui.components.AegisIcons.Camera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.verify_contact_scan_their_qr), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        // Secondary — pick a screenshot / saved photo. Same callback
        // hook, just sourced from MediaStore via the system photo
        // picker. Lets users hand Aegis a screenshot of an invite
        // link instead of pointing the camera at another screen.
        AegisOutlinedButton(
            onClick = {
                val activity = context as? app.aether.aegis.MainActivity
                activity?.pickQrFromGallery { scanned ->
                    if (scanned.isNotBlank()) link = scanned
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            // LunaGlass: tinted gallery vector, not the "🖼" OS emoji.
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(
                    app.aether.aegis.R.drawable.ic_aegis_gallery,
                ),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                    app.aether.aegis.ui.theme.AegisCyan,
                ),
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.add_contact_pick_qr_from_photo), color = app.aether.aegis.ui.theme.AegisCyan)
        }

        // Paste fallback.
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text(stringResource(R.string.add_contact_or_paste_an_invitation)) },
            placeholder = { Text("simplex:/?...  or  https://smp*.simplex.im/[a|c|g]#…") },
            singleLine = false,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )

        // No client-side validation — upstream doesn't pre-check link
        // shapes either, it just hands the pasted text to /_connect
        // plan and surfaces whatever error the core returns. Doing
        // our own regex was rejecting custom-server short links
        // (`https://my-relay.example.com/g#…`) and any modern share
        // format that doesn't match `*.simplex.im` exactly — user
        // reported "link always appears invalid" on 2026.05.609.
        val trimmedLink = link.trim()
        val linkOk = trimmedLink.isNotEmpty()
        // A `/g#` preset-server short link is the group-join flavour.
        // The Aegis-side flow (acceptInvitation → apiConnectPlan →
        // ResolvedPlan.GroupLink → apiConnect) handles it identically
        // to a contact join over the wire, but the UI copy and the
        // nickname requirement only make sense for adding a person.
        // Detect group-shape upfront so we can flip the labels +
        // skip the nickname field when joining a room.
        // Best-effort group-link sniff for UI copy + nickname field
        // visibility. Matches both modern short links (`/g#…` on any
        // SMP server domain) and long-form `simplex:/contact#/?…&grp=…`
        // shares. If we guess wrong it's cosmetic — the button label
        // says "Add contact" instead of "Join group", or vice versa,
        // but the actual connect is dispatched by the core based on
        // the parsed plan, not by us.
        val isGroupLink = trimmedLink.contains("/g#", ignoreCase = true) ||
            trimmedLink.contains("grp=", ignoreCase = true) ||
            trimmedLink.contains("/contact#/group", ignoreCase = true)
        // No inline "looks invalid" hint — we no longer pre-check the
        // link shape. If the core rejects it after submit, the error
        // surfaces below.

        if (!isGroupLink && !repairing) {
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text(stringResource(R.string.contact_nickname_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Aegis Protocol (Stage 5): same "give, don't ask" reassurance
            // as the invite flow. Nickname stays optional here on purpose —
            // forcing it re-introduced the group-link-misdetection bug
            // (2026.05.611) — but the privacy note still applies.
            Text(
                stringResource(R.string.add_contact_theyll_see_you_as) +
                    "hidden until you promote them to Trusted or Emergency.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        } else {
            // The group's display name comes from the invite itself;
            // a per-user nickname doesn't apply. Surface a hint so
            // the user understands why the field disappeared.
            Text(
                stringResource(R.string.add_contact_joining_a_group_no) +
                    "comes from the invite.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }

        AegisButton(
            // Contact joins need a nickname; group joins don't.
            // Only gate on link-present + not-busy. Nickname is
            // optional now (for contact joins where the user didn't
            // type one, downstream falls back to SimpleX's announced
            // localDisplayName). Used to also require a nickname for
            // anything `!isGroupLink`, which silently disabled the
            // button when our heuristic guessed wrong on a group link
            // — user-reported 2026.05.611.
            // Protected Mode: when the link is a group invite, the join is
            // gated under GROUPS (a child can't join rooms they find on the
            // net). Contact joins are unaffected by this gate.
            enabled = !busy && linkOk &&
                !(isGroupLink && isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.GROUPS)),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    val transport = AegisApp.instance.transports
                        .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
                    val ok = withContext(Dispatchers.IO) {
                        transport?.acceptInvitation(trimmedLink, nickname.trim()) ?: false
                    }
                    busy = false
                    if (ok) {
                        // If the link resolved to a group we can open right
                        // now (already a member — the "Known" plan — or a
                        // sync-resolved join), navigate INTO it; otherwise a
                        // real join is in flight and the group surfaces in the
                        // Groups tab when userJoinedGroup fires.
                        val joinedGroup =
                            if (isGroupLink) transport?.lastJoinedGroupAegisId else null
                        val msg = when {
                            joinedGroup != null -> "Opening group…"
                            isGroupLink -> "Joining group…"
                            nickname.trim().isNotEmpty() -> "Paired with ${nickname.trim()}"
                            else -> "Paired"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        if (joinedGroup != null) onJoinedGroup(joinedGroup) else onDone()
                    } else {
                        // Surface the actual core error if the transport
                        // captured one — generic "check the link" was
                        // useless when the user couldn't tell whether
                        // the link, the core, the network, or our own
                        // pre-flight was rejecting it.
                        val real = transport?.lastJoinError
                        error = real
                            ?: if (isGroupLink)
                                "Couldn't join — SimpleX returned no specific error."
                            else
                                "Connect failed — SimpleX returned no specific error."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(
                if (busy) stringResource(R.string.contact_connecting)
                else if (isGroupLink) "Join group"
                else stringResource(R.string.contact_add),
                fontSize = 15.sp,
            )
        }

        error?.let { msg ->
            // Long-press to select via SelectionContainer + a Copy
            // chip for users who don't know the gesture. Either way
            // the user can grab the raw error text without retyping
            // it — user-reported 2026.05.612.
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(msg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            TextButton(
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg))
                    Toast.makeText(context, "Error copied", Toast.LENGTH_SHORT).show()
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(stringResource(R.string.add_contact_copy_error), fontSize = 11.sp)
            }
        }
    }
}

/**
 * Group-join flow — a DEDICATED, group-only window. No nickname field (a
 * group's name comes from the invite, and per-user nicknames don't apply),
 * group-framed copy throughout. Kept separate from the contact [AcceptFlow]
 * so joining a group and adding a contact never share one confusing adaptive
 * window that asks for a nickname and then hides it (user report 2026-06-16).
 * The wire path is identical — acceptInvitation → the core resolves a `/g`
 * link to a group join — only the UI differs.
 */
@Composable
private fun JoinGroupFlow(onDone: () -> Unit, onJoinedGroup: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Protected Mode: a child can't join rooms they find on the net.
    val gated = isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.GROUPS)

    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Scan or paste a GROUP invite link. The group's name comes from " +
                "the invite — there's no nickname to set.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        if (gated) {
            Text(
                "Joining groups is locked in Protected Mode.",
                color = app.aether.aegis.ui.theme.AegisWarning,
                fontSize = 12.sp,
            )
        }
        AegisButton(
            onClick = {
                val activity = context as? app.aether.aegis.MainActivity
                activity?.scanQr("Scan the group invite QR") { scanned ->
                    if (scanned.isNotBlank()) link = scanned
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            app.aether.aegis.ui.components.AegisIcon(
                icon = app.aether.aegis.ui.components.AegisIcons.Camera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Scan group QR", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        AegisOutlinedButton(
            onClick = {
                val activity = context as? app.aether.aegis.MainActivity
                activity?.pickQrFromGallery { scanned -> if (scanned.isNotBlank()) link = scanned }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(app.aether.aegis.R.drawable.ic_aegis_gallery),
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(app.aether.aegis.ui.theme.AegisCyan),
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.add_contact_pick_qr_from_photo), color = app.aether.aegis.ui.theme.AegisCyan)
        }
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("Group invite link") },
            placeholder = { Text("https://…/g#…   or   simplex:/…") },
            singleLine = false,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        val trimmedLink = link.trim()
        AegisButton(
            enabled = !busy && trimmedLink.isNotEmpty() && !gated,
            onClick = {
                busy = true; error = null
                scope.launch {
                    val transport = AegisApp.instance.transports
                        .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
                    // Empty nickname — groups don't take one.
                    val ok = withContext(Dispatchers.IO) {
                        transport?.acceptInvitation(trimmedLink, "") ?: false
                    }
                    busy = false
                    if (ok) {
                        val joinedGroup = transport?.lastJoinedGroupAegisId
                        Toast.makeText(
                            context,
                            if (joinedGroup != null) "Opening group…" else "Joining group…",
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (joinedGroup != null) onJoinedGroup(joinedGroup) else onDone()
                    } else {
                        error = transport?.lastJoinError
                            ?: "Couldn't join — SimpleX returned no specific error."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(
                if (busy) stringResource(R.string.contact_connecting) else "Join group",
                fontSize = 15.sp,
            )
        }
        error?.let { msg ->
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(msg, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

package app.aether.aegis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.theme.AegisOnline
import app.aether.aegis.ui.theme.AegisOnlineGlow
import app.aether.aegis.ui.theme.AegisSOS
import app.aether.aegis.ui.theme.AegisSOSGlow
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import kotlinx.coroutines.launch

/**
 * Unified live-peer surface.
 *
 * Pure presentation. Holds no state of its own beyond the
 * [ControlState] / [ControlActions] it's handed. Two adapters in
 * [DeviceControlAdapters.kt] project the existing SOSAlertStore /
 * RemoteAccessSession state into [ControlState] and wire actions to
 * existing send paths. Backend untouched.
 *
 * Layout slots (top-to-bottom):
 *   1. Header strip — peer name, mode badge, age/expiry
 *   2. Live frames row — front-lens + rear-lens (either may be null)
 *   3. Map preview — taps out to system map intent
 *   4. Audio strip — most-recent clip with play, older collapsed
 *   5. Presence row — battery + network + online dot + lock-OK
 *   6. Mode-specific detail — responders (sos) / expiry (remote)
 *   7. Action bar — only buttons whose [ControlActions] lambda is set
 *
 * Tint mirrors the mode: [AegisSOSGlow] for SOS,
 * [AegisOnlineGlow] for remote. Same perceptual weight, opposite
 * agency.
 *
 * Mode-flag discipline: the [Mode] enum
 * exists only to pick the tint + the mode-detail slot renderer.
 * Conditional behavior in actions must branch on the *presence* of
 * a lambda in [ControlActions], not on `mode ==`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlScreen(state: ControlState, actions: ControlActions) {
    // Mode only picks the tint here — agency lives in which action
    // lambdas the caller supplied, never in this enum (see class KDoc).
    val tint = when (state.mode) {
        ControlMode.SOS  -> AegisSOSGlow
        ControlMode.REMOTE -> AegisOnlineGlow
    }
    // Surface typed errors from the target as toasts. Without this
    // the user could press Locate / Listen / Display and see
    // nothing happen — the target was returning 'needs_*_permission'
    // / 'notifications_disabled' / 'not_device_admin' which used to
    // just get logged. The toast tells the user exactly why their
    // command was rejected on the target side.
    val context = androidx.compose.ui.platform.LocalContext.current
    val lastErr by app.aether.aegis.remote.RemoteAccessSession.lastError.collectAsState()
    androidx.compose.runtime.LaunchedEffect(lastErr) {
        val err = lastErr ?: return@LaunchedEffect
        if (err.peerKey != state.peerKey) return@LaunchedEffect
        val friendly = when (err.msg) {
            "needs_location_permission" ->
                "${err.forCmd}: target needs to grant Location permission"
            "needs_microphone_permission" ->
                "${err.forCmd}: target needs to grant Microphone permission"
            "needs_location_and_admin" ->
                "${err.forCmd}: target needs Location + Device Admin"
            "notifications_disabled" ->
                "${err.forCmd}: target has notifications disabled"
            "capture_failed" ->
                "${err.forCmd}: target couldn't capture audio (encoder error)"
            "too_large" ->
                "${err.forCmd}: recording too large to transmit"
            "rate_limited" ->
                "${err.forCmd}: rate-limited, wait a bit"
            "no session", "missing sid" ->
                "${err.forCmd}: session expired — re-auth"
            "needs_reauth" ->
                "${err.forCmd}: wrong device PIN — nothing was erased"
            "not_device_owner" ->
                "${err.forCmd}: target isn't Device Owner — can't factory-reset"
            else -> "${err.forCmd}: ${err.msg}"
        }
        android.widget.Toast.makeText(
            context, friendly, android.widget.Toast.LENGTH_LONG,
        ).show()
        app.aether.aegis.remote.RemoteAccessSession.consumeError()
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tint),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Own the status-bar inset. This screen is shown as a
                    // sub-route (device-control/remote), and the hosting
                    // Scaffold runs contentWindowInsets = 0, so nothing
                    // reserves the notification-bar strip for us — without
                    // this the header collided with the system clock /
                    // status icons. The tint background on the parent Box
                    // still fills edge-to-edge behind the bar; only the
                    // content is pushed clear.
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HeaderStrip(state)
                FramesRow(state)
                MapSlot(state)
                AudioStrip(state)
                PresenceRow(state)
                ModeDetailSlot(state.detail)
                ActionBar(state.mode, actions)
            }
        }
    }
}

/**
 * Top strip: peer name + truncated key, mode badge, age, and (remote
 * only) a battery readout. Read-only — purely renders [state].
 *
 * "Age" means different things per mode: for SOS it's time since the
 * alert was triggered; for remote there's no trigger time, so it's
 * back-computed from session expiry assuming a 5-minute session window
 * (expiry − 5 min ≈ session start). The battery line is a convenience
 * duplicate of [PresenceRow]'s, colour-coded red/amber at the 15%/30%
 * thresholds, with a charging bolt prefix.
 */
@Composable
private fun HeaderStrip(state: ControlState) {
    val accent = when (state.mode) {
        ControlMode.SOS  -> AegisSOS
        ControlMode.REMOTE -> AegisOnline
    }
    // SOS: time since trigger. Remote: no trigger time exists, so
    // approximate session start as expiry minus the 5-minute session
    // length and measure age from there.
    val ageMs = System.currentTimeMillis() - when (val d = state.detail) {
        is ModeDetail.SOS  -> d.triggeredAt
        is ModeDetail.Remote -> d.sessionExpiresAt - 5L * 60_000L
    }
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.peerName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    state.peerKey.take(16) + "…",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    when (state.mode) {
                        ControlMode.SOS  -> stringResource(R.string.tab_order_sos)
                        ControlMode.REMOTE -> "REMOTE"
                    },
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                )
                Text(
                    formatAge(ageMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val batt = (state.detail as? ModeDetail.Remote)?.batteryPct
                val chg  = (state.detail as? ModeDetail.Remote)?.charging
                if (batt != null) {
                    val plug = if (chg == true) "⚡" else ""
                    // Red at/below 15%, amber at/below 30%, neutral
                    // above — a quick at-a-glance health cue for a
                    // device the operator can't physically see.
                    val battColor = when {
                        batt <= 15 -> AegisSOS
                        batt <= 30 -> MaterialTheme.colorScheme.tertiary
                        else       -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        "$plug$batt%",
                        fontSize = 11.sp,
                        color = battColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Camera area. Three mutually exclusive renders:
 *
 *   1. LIVE — if a live cam/mic stream is up for THIS peer, host the
 *      WebRTC surface inline (video shows the remote camera; mic-only
 *      shows a black box with a listening indicator) and return early.
 *   2. REMOTE stills — the front/rear [FrameSlot] pair, always shown in
 *      remote mode (even empty) so the operator sees where snapshots
 *      land and that the capability exists; an empty pair shows a hint.
 *   3. SOS stills — in SOS mode there's no operator capture, so the row
 *      is suppressed entirely until the victim's stream produces a
 *      frame.
 *
 * "This peer" for the live case is a three-way match: remote mode AND
 * the session's liveStreamPeer is this key AND the active call's peer
 * is this key — so a live stream for a DIFFERENT peer never bleeds in.
 */
@Composable
private fun FramesRow(state: ControlState) {
    val isRemote = state.mode == ControlMode.REMOTE

    // Panel-hosted LIVE stream. When the operator started a live cam/mic
    // for THIS peer and the WebRTC call is up, render the live feed right
    // here on the console instead of bouncing to a separate call screen —
    // everything about a peer stays in one place.
    val liveStreamPeer by app.aether.aegis.remote.RemoteAccessSession
        .liveStreamPeer.collectAsState()
    val activeCall by app.aether.aegis.call.CallStore.active.collectAsState()
    val liveForThisPeer = isRemote &&
        liveStreamPeer == state.peerKey &&
        activeCall?.peerPubkey == state.peerKey
    if (liveForThisPeer) {
        val call = activeCall
        // "Connected" gates the indicator copy between Connecting… and
        // the live label; isVideo picks the 240dp camera box vs the
        // 72dp mic-only strip.
        val connected = call?.state == app.aether.aegis.call.CallState.Connected
        val isVideo = call?.media == app.aether.aegis.call.CallMediaType.Video
        // Free the shared WebView when this surface leaves composition
        // (stream ended or panel closed). Mirrors CallScreen's teardown;
        // onDispose runs after AndroidView has already detached the view,
        // so destroy is safe (destroying a still-drawn WebView crashes).
        DisposableEffect(Unit) {
            onDispose {
                runCatching { app.aether.aegis.call.CallManager.detachWebView() }
                runCatching { app.aether.aegis.call.CallManager.destroyWebView() }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (isVideo) "CAMERA · LIVE" else "MIC · LIVE",
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = AegisOnline,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isVideo) 240.dp else 72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                // Hosts the WebRTC engine. For a video stream it shows the
                // remote camera; for an audio-only mic stream it's a black
                // surface that just keeps the JS pipeline + audio alive,
                // with a listening indicator overlaid.
                app.aether.aegis.call.CallVideoSurface(Modifier.fillMaxSize())
                when {
                    !isVideo -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("●", color = AegisOnline, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (connected) "Listening — live mic" else "Connecting live mic…",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    !connected -> Text(
                        "Connecting live feed…",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    // In REMOTE mode the camera-feed area is ALWAYS shown — even before
    // any frame has arrived — so the operator can see where snapshots
    // will land and that the capability exists at all. Previously this
    // returned early while both frames were null, so on a fresh session
    // there was simply no camera panel on screen and the feature looked
    // missing ("I don't even see where the feed would be"). The two
    // FrameSlots render their own "no frame" placeholder, and the
    // ACTIONS panel's "Snap (front/rear)" / "Live" buttons fill them.
    // In SOS mode there's no operator-driven capture, so keep the old
    // behaviour: only surface frames once the victim's stream produces
    // one.
    if (!isRemote && state.frontFrame == null && state.rearFrame == null) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (isRemote) {
            Text(
                "CAMERA",
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FrameSlot(state.frontFrame, "FRONT", modifier = Modifier.weight(1f))
            FrameSlot(state.rearFrame, "REAR", modifier = Modifier.weight(1f))
        }
        // Hint only while empty, so a brand-new session explains how to
        // populate the feed instead of showing two blank boxes with no
        // affordance.
        if (isRemote && state.frontFrame == null && state.rearFrame == null) {
            Text(
                "Tap Snap (front/rear) below to capture a still, or " +
                    "Live to start a stream.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One lens tile (front or rear). Renders a placeholder box when [frame]
 * is null, otherwise decodes the JPEG bytes to a bitmap and shows it
 * with an age caption. Decode is wrapped in [remember] keyed on the
 * byte array so it only runs when the frame actually changes, and in
 * runCatching so a corrupt payload yields an empty tile rather than a
 * crash. [label] is the lens name shown as the tile header.
 */
@Composable
private fun FrameSlot(frame: FrameSnapshot?, label: String, modifier: Modifier = Modifier) {
    GlassPanel(modifier = modifier) {
        Column(modifier = Modifier.padding(6.dp)) {
            Text(
                label,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (frame == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF001717)),  // R zeroed (was 111717): no red in a cyan
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.device_control_no_frame),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val bitmap = remember(frame.bytes) {
                    runCatching {
                        android.graphics.BitmapFactory.decodeByteArray(
                            frame.bytes, 0, frame.bytes.size,
                        )
                    }.getOrNull()
                }
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "$label lens frame",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
                Text(
                    stringResource(R.string.device_control_fix) + formatAge(System.currentTimeMillis() - frame.ts) + " ago",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Location panel. Suppressed entirely when there's no fix. Shows the
 * lat/lng + age and, on tap, fires a `geo:` ACTION_VIEW intent so the
 * system map app opens the coordinate — this app ships no embedded map,
 * it hands off to whatever map handler exists on the device. The intent
 * carries FLAG_ACTIVITY_NEW_TASK and is wrapped in runCatching so a
 * device with no map handler fails silently rather than crashing.
 */
@Composable
private fun MapSlot(state: ControlState) {
    val fix = state.locationFix ?: return
    val context = LocalContext.current
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .clickable {
                    val uri = android.net.Uri.parse(
                        "geo:${fix.lat},${fix.lng}?q=${fix.lat},${fix.lng}",
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.device_control_location), fontSize = 9.sp, letterSpacing = 1.5.sp,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "%.5f, %.5f".format(fix.lat, fix.lng),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.device_control_fix) + formatAge(System.currentTimeMillis() - fix.ts) + " ago — tap to open map",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Captured-audio list. Suppressed when empty. The most-recent clip
 * ([state.audioClips] is newest-first) is always shown; any older clips
 * collapse behind a "Show history (N)" toggle to keep the panel short.
 */
@Composable
private fun AudioStrip(state: ControlState) {
    if (state.audioClips.isEmpty()) return
    val latest = state.audioClips.first()  // adapters sort newest-first
    val older = state.audioClips.drop(1)
    // Local-only UI state: whether the older-clip history is expanded.
    var expanded by remember { mutableStateOf(false) }
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                stringResource(R.string.device_control_audio),
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AudioClipRow(latest)
            if (older.isNotEmpty()) {
                Text(
                    if (expanded) "Hide history (${older.size})" else "Show history (${older.size})",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable { expanded = !expanded },
                )
                if (expanded) {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        older.forEach { AudioClipRow(it) }
                    }
                }
            }
        }
    }
}

/**
 * One playable clip row: a play/stop toggle plus the clip's age.
 *
 * Owns a single [android.media.MediaPlayer] scoped to the clip path
 * (re-created if the path changes), released in [onDispose] so the
 * player never leaks when the row leaves composition. All player calls
 * are runCatching-wrapped — a bad/missing file just leaves the toggle
 * showing Play rather than throwing. The `playing` flag is local UI
 * state reset on completion via the completion listener.
 */
@Composable
private fun AudioClipRow(clip: AudioClip) {
    val context = LocalContext.current
    // playing + player are both keyed on the clip path so a different
    // clip gets a fresh, independent player and toggle state.
    var playing by remember(clip.path) { mutableStateOf(false) }
    val player = remember(clip.path) { android.media.MediaPlayer() }
    // Release the player when the row is removed — prevents a leaked
    // native MediaPlayer if the panel is scrolled away or torn down.
    DisposableEffect(clip.path) {
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            modifier = Modifier.weight(1f),
            onClick = {
                if (playing) {
                    runCatching { player.stop() }
                    runCatching { player.reset() }
                    playing = false
                } else {
                    runCatching {
                        player.reset()
                        player.setDataSource(clip.path)
                        player.prepare()
                        player.start()
                        playing = true
                        player.setOnCompletionListener { playing = false }
                    }
                }
            },
        ) {
            Text(if (playing) "■ Stop" else "▶ Play", fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            formatAge(System.currentTimeMillis() - clip.ts) + " ago",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Presence summary: online dot + label, optional battery and network
 * type, and (remote only) a "lock didn't fire" error chip.
 *
 * The lock-OK chip appears only in remote mode and only when the target
 * reported `lockOk == false` — i.e. a remote LOCK command ran on a
 * device that has NOT enrolled Device-Admin, so the lock was a no-op.
 * It's a heads-up that the precondition is unmet, not a transient
 * failure.
 */
@Composable
private fun PresenceRow(state: ControlState) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            app.aether.aegis.ui.components.StatusDot(state.online)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                when (state.online) {
                    app.aether.aegis.ui.components.PeerStatus.Online  -> stringResource(R.string.status_online)
                    app.aether.aegis.ui.components.PeerStatus.Away    -> stringResource(R.string.status_away)
                    app.aether.aegis.ui.components.PeerStatus.Offline -> stringResource(R.string.status_offline)
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(12.dp))
            state.battery?.let {
                Text("🔋 $it%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.width(12.dp))
            }
            state.networkType?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Remote-only: lock-OK chip when target reports lock did
            // not fire (Device Admin not enrolled).
            if (state.detail is ModeDetail.Remote && state.detail.lockOk == false) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.device_control_lock_off),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/** Dispatch the mode-specific detail panel: responders for SOS,
 *  session/expiry for remote. */
@Composable
private fun ModeDetailSlot(detail: ModeDetail) {
    when (detail) {
        is ModeDetail.SOS  -> SOSDetailSlot(detail)
        is ModeDetail.Remote -> RemoteDetailSlot(detail)
    }
}

/**
 * SOS detail: the trigger label and the responder roster. Empty-state
 * copy differs by viewpoint — the victim sees "Waiting for responders",
 * a responder sees "No other responders yet". Each responder renders a
 * filled dot once arrived, hollow while still en route.
 */
@Composable
private fun SOSDetailSlot(detail: ModeDetail.SOS) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                "SOS · ${detail.trigger.name.lowercase()}",
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                color = AegisSOS,
            )
            if (detail.responders.isEmpty()) {
                Text(
                    if (detail.isVictim) "Waiting for responders…" else "No other responders yet.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "${detail.responders.size} responding",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                detail.responders.forEach { r ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(
                            if (r.arrived) "● " else "○ ",
                            color = if (r.arrived) AegisOnline else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                        Text(r.displayName, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/**
 * Remote detail: the live session's time-to-expiry, plus a repeat of
 * the "lock did not fire" warning (Device-Admin not enrolled on the
 * target) when applicable. Remaining time is clamped at zero so an
 * already-expired session reads "0s" rather than a negative age.
 */
@Composable
private fun RemoteDetailSlot(detail: ModeDetail.Remote) {
    val nowMs = System.currentTimeMillis()
    // Clamp so an expired session shows 0, not a negative duration.
    val remainingMs = (detail.sessionExpiresAt - nowMs).coerceAtLeast(0)
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                stringResource(R.string.device_control_remote_session),
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                color = AegisOnline,
            )
            Text(
                stringResource(R.string.device_control_expires_in) + formatAge(remainingMs),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail.lockOk == false) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.device_control_lock_did_not_fire),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The command bar — the operator's control surface.
 *
 * Builds a button list from ONLY the non-null lambdas in [actions], so
 * each adapter exposes exactly the commands it wants (SOS exposes
 * almost none; remote exposes the full locate/listen/live/snapshot/
 * ring/siren/update/watch set). Buttons render two-up.
 *
 * Every button is [wrap]ped to fire a "Sent: …" toast before invoking
 * the action. That toast means QUEUED/TRANSMITTED, not done — the
 * actual effect (siren sounding, message on lockscreen, wipe) happens
 * on the TARGET device, and any rejection arrives later as the typed
 * error toast handled in [DeviceControlScreen]. The toast exists
 * because otherwise a working button looks dead from the sender's side.
 *
 * Two commands are NOT plain buttons and are rendered inline instead:
 * Display (lockscreen message composer, [DisplayMessageButton]) and
 * Wipe (three-step confirmation ladder, [WipeButtonWithLadder]) — which
 * is why [onDisplay] is consumed but adds no entry to the button list,
 * and why the early-return also checks onDisplay/onWipe.
 *
 * [mode] is accepted but does not branch behaviour here — agency is
 * entirely a function of which lambdas are present.
 */
@Composable
private fun ActionBar(mode: ControlMode, actions: ControlActions) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Toast wrapper: every action button posts a brief confirmation
    // so the sender can see the command fired even when the
    // visible result is on the target's device (siren, display
    // message, wipe, etc.). Without this the buttons look dead
    // even when they're working — user-reported 2026.05.665.
    fun toast(label: String) {
        runCatching {
            android.widget.Toast.makeText(
                context, "Sent: $label", android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    // Toast-then-invoke wrapper. Ordering matters: the confirmation
    // toast fires BEFORE the action so the user always gets feedback
    // even if the action throws.
    fun wrap(label: String, action: () -> Unit): () -> Unit = {
        toast(label)
        action()
    }

    // Accumulate (label, onClick) pairs from whichever lambdas exist.
    // The order here is the on-screen order (chunked two-up below).
    val buttons = mutableListOf<Pair<String, () -> Unit>>()
    actions.onPushToTalk?.let { buttons += "Hold to talk" to wrap("PTT ping", it) }
    actions.onListen?.let { listen ->
        // Single fixed 10-second listen-in snapshot (vs the continuous
        // live mic). Target needs the Microphone grant or it replies
        // needs_microphone_permission.
        buttons += "Listen 10s" to wrap("Listen 10s") { listen(10) }
    }
    actions.onForceLocate?.let { buttons += stringResource(R.string.remote_locate) to wrap(stringResource(R.string.remote_locate), it) }
    actions.onLiveCam?.let { live ->
        // Lens picker — Rear is the stolen-phone default (what the
        // holder is pointing at); Front is the face-of-holder angle
        // for verification. Each button starts a fresh stream with
        // the chosen lens; sender can also flip mid-stream once
        // connected via the Flip button below.
        buttons += "Live (rear)" to wrap("Live view (rear)") { live("rear") }
        buttons += "Live (front)" to wrap("Live view (front)") { live("front") }
    }
    actions.onLiveCamFlip?.let { buttons += "Flip camera" to wrap("Flip camera", it) }
    actions.onLiveCamStop?.let { buttons += "Stop live cam" to wrap("Stop live cam", it) }
    actions.onLiveMic?.let { buttons += "Live mic" to wrap("Live mic", it) }
    actions.onLiveMicStop?.let { buttons += "Stop live mic" to wrap("Stop live mic", it) }
    actions.onSnapshot?.let { snap ->
        buttons += "Snap (rear)" to wrap("Snap (rear)") { snap("rear") }
        buttons += "Snap (front)" to wrap("Snap (front)") { snap("front") }
    }
    actions.onPing?.let { buttons += "Ping" to wrap("Ping", it) }
    actions.onWatchPause?.let { buttons += "Pause watch" to wrap("Pause watch", it) }
    actions.onWatchResume?.let { buttons += "Resume watch" to wrap("Resume watch", it) }
    actions.onRing?.let { buttons += "Ring (find)" to wrap("Ring", it) }
    actions.onRingOff?.let { buttons += "Stop ring" to wrap("Stop ring", it) }
    actions.onSiren?.let { buttons += stringResource(R.string.remote_siren) to wrap(stringResource(R.string.remote_siren), it) }
    actions.onSirenOff?.let { buttons += "Stop siren" to wrap("Stop siren", it) }
    // Display is NOT added to the flat button list — it gets its own
    // inline composer (DisplayMessageButton) further down. Consumed
    // here only so it doesn't fall through; the actual UI is below.
    actions.onDisplay?.let { _ -> /* handled inline via DisplayMessageButton below */ }
    actions.onUpdate?.let { buttons += "Push update" to wrap("Push update", it) }
    actions.onMarkSafe?.let { buttons += "Mark safe" to wrap("Mark safe", it) }
    actions.onAcknowledge?.let { buttons += "Acknowledge" to wrap("Acknowledge", it) }
    actions.onExit?.let { buttons += "Exit" to wrap("Exit", it) }

    // Suppress the whole panel only when there is genuinely nothing to
    // show — no flat buttons AND neither inline-rendered command
    // (display composer / wipe ladder) is available.
    if (buttons.isEmpty() && actions.onDisplay == null && actions.onWipe == null) return

    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                stringResource(R.string.device_control_actions),
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Render in two-up rows for readability.
            buttons.chunked(2).forEach { rowPair ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowPair.forEach { (label, onClick) ->
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = onClick,
                        ) { Text(label, fontSize = 11.sp) }
                    }
                    // Pad a lone trailing button so it keeps half-width
                    // rather than stretching across the row.
                    if (rowPair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
            // Inline lockscreen-message composer. Distinct toast copy for
            // a blank message (clears the banner) vs setting one.
            actions.onDisplay?.let { send ->
                DisplayMessageButton { msg ->
                    toast(if (msg.isBlank()) "Clear lockscreen msg" else "Lockscreen msg")
                    send(msg)
                }
            }
            // Wipe goes through the confirmation ladder; the action
            // (toast + send) only fires after the user types WIPE and
            // enters the TARGET's app PIN. The ladder hands that PIN
            // back here so it can ride the WIPE packet for target-side
            // verification.
            actions.onWipe?.let { wipe ->
                WipeButtonWithLadder(onConfirmed = { pin -> toast("WIPE"); wipe(pin) })
            }
            // Remote SOS — raise the target's SOS on their behalf. Confirm-gated
            // (it alerts the target's whole emergency network), then toast + send.
            actions.onRemoteSos?.let { sos ->
                RemoteSosButton(onConfirmed = { toast("Trigger SOS"); sos() })
            }
        }
    }
}

/** Confirmation-gated "Trigger SOS" button for the remote panel. Raising
 *  someone's SOS alerts their entire emergency network, so it takes a
 *  deliberate confirm — but unlike WIPE it's recoverable (they can cancel),
 *  so a single confirm is enough (no PIN gate). */
@Composable
private fun RemoteSosButton(onConfirmed: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        onClick = { confirming = true },
    ) {
        Text("Trigger SOS", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Raise their SOS?") },
            text = {
                Text(
                    "This triggers an SOS on the target's phone on their behalf — " +
                        "alerting their emergency contacts with their location, audio, " +
                        "and a photo. Use it when you can see they're in trouble. They " +
                        "can stop it from their own phone if it's a false alarm.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onConfirmed()
                }) { Text("Trigger SOS", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.secure_notes_cancel))
                }
            },
        )
    }
}

/** Three-step confirmation ladder + explicit scope notice for the
 *  WIPE action. The previous "Wipe device" button fired instantly
 *  with no confirmation — a single accidental tap factory-resets
 *  the target. Also clarifies what 'wipe' actually means: full
 *  factory reset (Device Owner only, otherwise no-op).
 *
 *  [onConfirmed] receives the TARGET device-owner's app PIN as typed
 *  in the final step. It is NOT verified here — the operator's own
 *  PIN is irrelevant; only the accessed device can say whether the PIN
 *  is right, so the PIN rides the WIPE packet and the target verifies
 *  it (a wrong PIN comes back as a "needs_reauth" error). This is the
 *  same receiver-PIN that authorised the session in the first place;
 *  re-typing it at the irreversible step proves the operator still
 *  holds it rather than merely riding a warm session. */
@Composable
private fun WipeButtonWithLadder(onConfirmed: (pin: String) -> Unit) {
    // step: 0 = idle, 1 = scope/warning, 2 = type-WIPE, 3 = target-PIN entry.
    // typed: the confirmation text; gate only opens on exactly "WIPE".
    var step by remember { mutableStateOf(0) }
    var typed by remember { mutableStateOf("") }
    // The SECOND gate, on wipe alone (the one irreversible command). Typing
    // "WIPE" proves intent; the target's app PIN proves AUTHORITY — it's the
    // device owner's own secret, the same one that opened this remote session.
    // Re-entering it here (verified target-side, never locally) is the
    // "are you really authorised, not just holding a warm session" check.
    var pin by remember { mutableStateOf("") }
    // For the operator-side duress trap only (NOT for verifying the target's
    // PIN — that stays target-side). verifyPin is Argon2id, so the check runs
    // off the main thread; [verifying] disables the button meanwhile.
    val wipeCtx = androidx.compose.ui.platform.LocalContext.current
    val wipeScope = androidx.compose.runtime.rememberCoroutineScope()
    var verifying by remember { mutableStateOf(false) }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        onClick = { step = 1 },
    ) {
        Text(
            stringResource(R.string.device_control_wipe_device),
            color = MaterialTheme.colorScheme.error,
            fontSize = 11.sp,
        )
    }
    if (step == 1) {
        AlertDialog(
            onDismissRequest = { step = 0 },
            title = { Text(stringResource(R.string.device_control_wipe_target_device)) },
            text = {
                Text(
                    stringResource(R.string.device_control_this_triggers_a_full) +
                        "All apps, accounts, photos, and files are erased. " +
                        "Only works if the target has Device Owner provisioned; " +
                        "otherwise the command is silently dropped on the target side. " +
                        "Cannot be undone. The target cannot stop it once fired.",
                )
            },
            confirmButton = {
                TextButton(onClick = { step = 2 }) {
                    Text(stringResource(R.string.tutorial_continue), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text(stringResource(R.string.secure_notes_cancel)) } },
        )
    }
    if (step == 2) {
        AlertDialog(
            onDismissRequest = { step = 0; typed = "" },
            title = { Text(stringResource(R.string.device_control_type_wipe_to_confirm)) },
            text = {
                Column {
                    Text(stringResource(R.string.device_control_this_is_the_last))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.uppercase().take(4) },
                        label = { Text(stringResource(R.string.device_control_type_wipe)) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                // Final gate: only enabled on an exact "WIPE" match.
                // Reset the ladder before firing so the dialog can't be
                // re-triggered from a stale state.
                TextButton(
                    enabled = typed == "WIPE",
                    onClick = {
                        typed = ""
                        // Intent proven. Now demand the TARGET's app PIN
                        // (authority) before firing — verified target-side.
                        step = 3
                    },
                ) { Text(stringResource(R.string.secure_notes_wipe), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { step = 0; typed = "" }) { Text(stringResource(R.string.secure_notes_cancel)) }
            },
        )
    }
    if (step == 3) {
        AlertDialog(
            onDismissRequest = { step = 0; pin = "" },
            title = { Text("Enter the device's PIN") },
            text = {
                Column {
                    Text(
                        "Type the target device's own app PIN to authorise the " +
                            "wipe. It's checked on that device, not here — a wrong " +
                            "PIN comes back as an error and nothing is erased. Only " +
                            "the real PIN works; a duress PIN will not wipe.",
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text("Device PIN") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pin.isNotBlank() && !verifying,
                    onClick = {
                        val entered = pin
                        verifying = true
                        wipeScope.launch {
                            // Operator-side duress trap (SPEC remote-duress): the
                            // entered value is the TARGET's PIN (verified target-
                            // side, never here). But if the operator — coerced
                            // into wiping — types their OWN duress PIN instead,
                            // catch it locally: fire a SILENT SOS on this phone,
                            // do NOT send the wipe, and FAKE success so the
                            // coercer believes it went through. Normally the
                            // target's PIN isn't the operator's, so this returns
                            // INVALID and we send as usual.
                            val duress = kotlinx.coroutines.withContext(
                                kotlinx.coroutines.Dispatchers.Default,
                            ) {
                                runCatching {
                                    val m = app.aether.aegis.lock.LockStore(wipeCtx).verifyPin(entered)
                                    m == app.aether.aegis.lock.LockStore.PinMatch.DURESS_1 ||
                                        m == app.aether.aegis.lock.LockStore.PinMatch.DURESS_2
                                }.getOrDefault(false)
                            }
                            verifying = false
                            step = 0; pin = ""
                            if (duress) {
                                runCatching {
                                    app.aether.aegis.AegisApp.instance.sosHandler.trigger(
                                        app.aether.aegis.core.SOSTrigger.DURESS,
                                    )
                                }
                                // Fake the normal "Sent: WIPE" confirmation so the
                                // coercer can't tell the wipe was trapped.
                                runCatching {
                                    android.widget.Toast.makeText(
                                        wipeCtx, "Sent: WIPE", android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } else {
                                onConfirmed(entered)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.secure_notes_wipe), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { step = 0; pin = "" }) {
                    Text(stringResource(R.string.secure_notes_cancel))
                }
            },
        )
    }
}

/**
 * Inline composer for the "show message on lockscreen" remote command.
 * Tap once to reveal the text field + Send/Clear pair; tap again to
 * collapse. Send fires the [send] callback with whatever's in the
 * field (200-char clamp matches the receiver's render width); Clear
 * resets the local field and sends an empty string so the lockscreen
 * banner is cleared on the remote device.
 *
 * Lives here rather than as a top-level button because the lockscreen
 * message + Send/Clear belong as a tight group, and unrolling them
 * inline keeps the button list above un-cluttered when the feature
 * is collapsed.
 */
@Composable
private fun DisplayMessageButton(send: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        onClick = { expanded = !expanded },
    ) { Text(if (expanded) "Hide message" else "Show message on lockscreen", fontSize = 11.sp) }
    if (expanded) {
        OutlinedTextField(
            value = msg,
            onValueChange = { msg = it.take(200) },
            label = { Text(stringResource(R.string.device_control_lockscreen_message)) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            maxLines = 3,
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { send(msg) },
            ) { Text(stringResource(R.string.device_control_send), fontSize = 11.sp) }
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { msg = ""; send("") },
            ) { Text(stringResource(R.string.sentinel_log_clear), fontSize = 11.sp) }
        }
    }
}

/** Format an elapsed-millis duration as a compact age string: seconds
 *  under a minute, m+s under an hour, h+m above. Used for frame/clip/
 *  fix ages and remote session time-to-expiry. */
private fun formatAge(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60   -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else     -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

// ---------------- Data contract ----------------
// The state/action types the adapters fill and the screen renders. The
// screen holds none of this itself — it's a pure function of these.

/** Which face of the console: victim-side SOS, or operator-side remote
 *  access. Drives ONLY tint + the mode-detail renderer; never gates
 *  command availability (that's the presence of an action lambda). */
enum class ControlMode { SOS, REMOTE }

/** A timestamped location fix. [ts] is when it was captured (for age). */
data class GeoFix(val lat: Double, val lng: Double, val ts: Long)

/** A single decoded-on-render camera frame. [bytes] is the raw JPEG,
 *  [ts] the capture time, [lensLabel] the source lens.
 *
 *  equals/hashCode are overridden to IDENTITY (not content): a
 *  ByteArray's default structural equality would force an O(n) compare
 *  on every recomposition diff, and two distinct captures are always
 *  distinct frames regardless of pixel content — identity is both
 *  cheaper and semantically correct here. */
data class FrameSnapshot(val bytes: ByteArray, val ts: Long, val lensLabel: String) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** A captured audio clip referenced by on-disk [path] (the MediaPlayer
 *  data source) with capture time [ts]. */
data class AudioClip(val path: String, val ts: Long)

/** One SOS responder row. [arrived] flips the dot from hollow (en
 *  route) to filled (on scene). */
data class ResponderTile(
    val peerKey: String,
    val displayName: String,
    val arrived: Boolean,
)

/** Mode-specific detail payload — exactly one variant per [ControlMode]. */
sealed interface ModeDetail {
    /** SOS detail. [isVictim] = the local device raised this SOS (drives
     *  the mark-safe vs acknowledge split and empty-state copy);
     *  [triggeredAt] anchors the header age. */
    data class SOS(
        val trigger: app.aether.aegis.core.SOSTrigger,
        val triggeredAt: Long,
        val responders: List<ResponderTile>,
        val isVictim: Boolean,
    ) : ModeDetail

    /** Remote-session detail. [sessionSid] is the auth-minted id every
     *  command carries; [sessionExpiresAt] drives the expiry countdown
     *  (and the back-computed header age); [lockOk] == false flags a
     *  remote LOCK that no-op'd because the target lacks Device-Admin. */
    data class Remote(
        val sessionSid: String,
        val sessionExpiresAt: Long,
        val lockOk: Boolean?,
        /** Target's battery percentage from the most-recent AUTH_OK /
         *  LOCATE / WATCH / PONG. Null = pre-2026.06 target or no
         *  battery info received yet. */
        val batteryPct: Int? = null,
        val charging: Boolean? = null,
    ) : ModeDetail
}

/** The complete render input for [DeviceControlScreen] — everything
 *  shown is a field here; the screen stores nothing else. Nullable
 *  slots ([locationFix], frames, battery, network) self-suppress their
 *  panel when absent. The adapters in DeviceControlAdapters.kt project
 *  the live stores into this. */
data class ControlState(
    val peerKey: String,
    val peerName: String,
    val mode: ControlMode,
    val locationFix: GeoFix?,
    val frontFrame: FrameSnapshot?,
    val rearFrame: FrameSnapshot?,
    val audioClips: List<AudioClip>,
    val battery: Int?,
    val networkType: String?,
    val online: app.aether.aegis.ui.components.PeerStatus,
    val detail: ModeDetail,
)

/** The command callbacks. A NULL field means "this device/mode does not
 *  offer this command" — [ActionBar] renders only the non-null ones, so
 *  this set IS the capability gate (not [ControlMode]). Each lambda
 *  fires-and-forgets: it queues a command, the effect/rejection lands on
 *  the target asynchronously. */
data class ControlActions(
    /** Push-to-talk intent ping. Currently signals intent only (no live
     *  audio); sent as a STATUS marker, not a session command. */
    val onPushToTalk: (() -> Unit)? = null,
    /** One-shot listen-in: target records [seconds] of mic audio and
     *  returns it as a base64 clip. Needs the target's Microphone grant. */
    val onListen: ((seconds: Int) -> Unit)? = null,
    /** Loud alarm on the target — bypasses DnD/silent, does NOT auto-stop
     *  (use [onSirenOff]). Distinct from the gentler [onRing]. */
    val onSiren: (() -> Unit)? = null,
    /** Silence a running siren. */
    val onSirenOff: (() -> Unit)? = null,
    /** Show [msg] on the target's lockscreen (empty string clears it).
     *  Rendered via the inline composer, not a flat button. */
    val onDisplay: ((msg: String) -> Unit)? = null,
    /** Force a fresh GPS fix from the target. Needs the Location grant
     *  (target replies needs_location_permission otherwise). */
    val onForceLocate: (() -> Unit)? = null,
    /** Factory-reset the target — IRREVERSIBLE, Device-Owner only
     *  (silently dropped otherwise). Gated by the confirmation ladder.
     *  The [String] is the TARGET device-owner's app PIN, collected by
     *  the ladder's final step and carried on the WIPE packet so the
     *  target can verify it itself — the operator never holds the
     *  authority, the device owner's secret does. */
    val onWipe: ((pin: String) -> Unit)? = null,
    /** Raise the TARGET's SOS on the owner's behalf, from the panel (remote
     *  SOS button). Confirmation-gated; fires a normal visible SOS on the
     *  target. Explicit and intentional — not a duress mechanism. */
    val onRemoteSos: (() -> Unit)? = null,
    /** Tell the target to pull + apply the latest app update (OTA). */
    val onUpdate: (() -> Unit)? = null,
    /** Open a stealth WebRTC video stream from target's camera back
     *  to this device. Target spins up a hidden WebView and dials an
     *  outgoing video call; sender's regular incoming-call notification
     *  fires and tapping Answer surfaces the live feed in CallScreen.
     *  [lens] is "rear" or "front" — picked at the moment the sender
     *  taps the button. See [app.aether.aegis.remote.RemoteLiveCamera]. */
    val onLiveCam: ((lens: String) -> Unit)? = null,
    /** Mid-stream lens flip — toggles the target's camera between
     *  front and rear without restarting the WebRTC pipeline. No-op
     *  if no stream is currently attached. */
    val onLiveCamFlip: (() -> Unit)? = null,
    /** Stop the live camera stream — tears down the WebRTC call and
     *  disarms the panel host. Symmetric with [onLiveMicStop]. */
    val onLiveCamStop: (() -> Unit)? = null,
    /** Audio-only sibling of LIVE_CAM — continuous one-way mic stream
     *  from target → sender via stealth WebRTC call. Heavier than
     *  [onListen] (single 10-second snapshot) but real-time. */
    val onLiveMic: (() -> Unit)? = null,
    /** Stop the live mic stream — tears down the WebRTC call. Symmetric
     *  with [onLiveCamStop]. */
    val onLiveMicStop: (() -> Unit)? = null,
    /** Light "find my phone" ringer — softer than [onSiren], no DnD
     *  bypass, auto-stops after 30 s. */
    val onRing: (() -> Unit)? = null,
    /** Stop a running find-my-phone ringer early. */
    val onRingOff: (() -> Unit)? = null,
    /** Single-JPEG snapshot from the chosen lens, returned in the
     *  same envelope as a LOCATE response so the existing frames row
     *  picks it up. [lens] is "front" or "rear". */
    val onSnapshot: ((lens: String) -> Unit)? = null,
    /** Cheap liveness probe — target replies with current battery +
     *  charging state, no GPS / camera cost. */
    val onPing: (() -> Unit)? = null,
    /** Pause periodic WATCH_TICK on the target — saves battery once
     *  the sender has confirmed where the device is. */
    val onWatchPause: (() -> Unit)? = null,
    /** Resume periodic WATCH_TICK reporting after [onWatchPause]. */
    val onWatchResume: (() -> Unit)? = null,
    /** SOS victim-side: cancel my own SOS ("I'm OK"). Present only when
     *  the local device is the victim. */
    val onMarkSafe: (() -> Unit)? = null,
    /** SOS responder-side: dismiss the alert banner. Present only when
     *  the local device is NOT the victim. */
    val onAcknowledge: (() -> Unit)? = null,
    /** Leave the console — closes the remote session and tears down any
     *  live stream/call so nothing keeps running headless. */
    val onExit: (() -> Unit)? = null,
)

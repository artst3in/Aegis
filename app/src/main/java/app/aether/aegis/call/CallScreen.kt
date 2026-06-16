package app.aether.aegis.call

import android.media.AudioManager
import android.media.ToneGenerator
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.*
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.HexShape
import app.aether.aegis.ui.components.hexInnerIcon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.aether.aegis.AegisApp
import app.aether.aegis.R
import app.aether.aegis.ui.theme.AegisBackground
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisPanel
import app.aether.aegis.ui.theme.AegisSOS
import app.aether.aegis.ui.theme.AegisWarning
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import kotlinx.coroutines.delay

/**
 * Active-call surface. Hosts the SimpleX WebRTC HTML/JS in a WebView,
 * exposes the JS↔Kotlin bridge to CallManager, and lays a control
 * overlay (mute, video toggle, switch camera, hang up) on top.
 *
 * Two entry paths:
 *  - Outgoing: navigated to from chat → places a /_call invite
 *  - Incoming: launched by the call notification's Accept action →
 *    CallStore already holds an incoming ActiveCall, we just attach
 *    the WebView so the JS engine can ingest the impending offer.
 *
 * Feedback while not yet connected:
 *  - Big pulsing avatar bubble with the peer's initial
 *  - Human-readable status ("Calling…", "Ringing…", "Connecting…")
 *  - Ringback tone on outgoing calls so the user hears that the call
 *    is actually in flight (silent-button-press is the #1 complaint
 *    when something goes wrong over the network)
 *  - Live elapsed-time counter once Connected
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallScreen(peerPubkey: String, peerName: String, video: Boolean, navController: NavController) {
    val activeCall by CallStore.active.collectAsState()
    var muted by remember { mutableStateOf(false) }
    var videoOn by remember { mutableStateOf(video) }
    // Manual chevron toggle for the bottom control bar — power-user
    // affordance from Tier 2 of CALL_SCREEN_TODO. WhatsApp / Telegram
    // auto-hide controls after a few seconds of inactivity during a
    // video call; that pattern needs touch interception on the
    // WebView which fights the local-video drag handler. Keeping it
    // manual sidesteps that conflict: tap the chevron to hide /
    // show, no surprises.
    var controlsVisible by remember { mutableStateOf(true) }
    val inPip by CallStore.inPip.collectAsState()

    LaunchedEffect(peerPubkey, video) {
        // Outgoing path: if no active call exists yet, this is our
        // initiation. Incoming path: CallStore was populated by the
        // SimpleX event handler — leave it alone.
        if (activeCall == null) {
            CallManager.placeCall(peerPubkey, peerName, video)
        }
    }
    // Track whether we ever saw a non-null activeCall. If we did and
    // it later becomes null (call ended, JS errored out, peer hung up)
    // pop CallScreen automatically — without this the user sits on a
    // pulsing "Preparing…" overlay with no clear way out after end().
    var hadCall by remember { mutableStateOf(false) }
    LaunchedEffect(activeCall) {
        if (activeCall != null) hadCall = true
        else if (hadCall) navController.popBackStack()
    }
    DisposableEffect(Unit) {
        onDispose {
            // Destroy the WebView only AFTER the composable has left
            // composition, so the AndroidView has already detached it
            // from the view hierarchy. Calling destroy() while the
            // WebView is still being drawn by AndroidView crashed the
            // app (Chromium aborts on a destroyed-then-touched WV).
            CallManager.detachWebView()
            CallManager.destroyWebView()
        }
    }

    // Proximity sensor screen-off — voice calls only. While the phone
    // is at the user's ear the proximity sensor reads "near" and the
    // OS dims the display (and ignores accidental touches), matching
    // every dialer / WhatsApp / Signal / Telegram voice call. Video
    // calls keep the screen on — you're looking at the camera.
    val proximityCtx = androidx.compose.ui.platform.LocalContext.current
    val isVoiceCall = activeCall?.media != CallMediaType.Video
    DisposableEffect(isVoiceCall) {
        val pm = proximityCtx.getSystemService(android.content.Context.POWER_SERVICE)
            as android.os.PowerManager
        val wl = if (isVoiceCall &&
            pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            pm.newWakeLock(
                android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "aegis:call-proximity",
            ).also { runCatching { it.acquire(20L * 60_000L) } }
        } else null
        onDispose {
            if (wl != null && wl.isHeld) runCatching { wl.release() }
        }
    }

    // Ringback tone on outgoing calls until Connected (or call ends).
    // Uses Android's built-in ToneGenerator → no asset shipping needed,
    // and the sound is the platform-standard ringback so users
    // immediately recognise it as "call in progress".
    val state = activeCall?.state
    val isOutgoing = activeCall?.outgoing == true
    val isWaiting = state != null && state != CallState.Connected && state != CallState.Ended
    LaunchedEffect(isOutgoing, isWaiting) {
        if (!isOutgoing || !isWaiting) return@LaunchedEffect
        val tone = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
        }.getOrNull() ?: return@LaunchedEffect
        try {
            // ToneGenerator.startTone is non-blocking; loop with manual
            // delays so we can stop the tone when the call connects or
            // the composable leaves the scope.
            while (isWaiting && isOutgoing) {
                tone.startTone(ToneGenerator.TONE_SUP_RINGTONE, 2000)
                delay(3000)  // 2s tone + 1s gap = standard ringback cadence
            }
        } finally {
            tone.stopTone()
            tone.release()
        }
    }

    // Elapsed-time counter — ticks once a second after Connected.
    val connectedAt = remember(state) { if (state == CallState.Connected) System.currentTimeMillis() else null }
    var elapsedSec by remember(connectedAt) { mutableStateOf(0) }
    LaunchedEffect(connectedAt) {
        val started = connectedAt ?: return@LaunchedEffect
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - started) / 1000).toInt()
            delay(1000)
        }
    }

    // Setup timeouts — calls used to hang on "Preparing…" indefinitely
    // when WebRTC failed to initialise, or on "Calling…" when the peer
    // was offline. Now each pre-Connected state has a hard ceiling.
    // Crossing it tears the call down with an actionable error.
    var timeoutMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) {
        val s = state ?: return@LaunchedEffect
        val limitMs = when (s) {
            CallState.WaitCapabilities -> 15_000L  // WebRTC + mic acquire
            CallState.InvitationSent -> 60_000L    // ringback / pick-up
            CallState.InvitationAccepted,
            CallState.OfferSent,
            CallState.OfferReceived,
            CallState.AnswerReceived,
            CallState.Negotiated -> 30_000L        // ICE candidate exchange
            CallState.Connected,
            CallState.Ended -> return@LaunchedEffect
        }
        delay(limitMs)
        // Re-check the live state — if it advanced we're fine.
        if (CallStore.active.value?.state == s) {
            timeoutMsg = when (s) {
                CallState.WaitCapabilities ->
                    "WebRTC didn't initialise. Check mic/camera permission and try again."
                CallState.InvitationSent ->
                    "${peerName} didn't pick up."
                else ->
                    "Couldn't establish the call (NAT / relay failure). Try toggling " +
                        "\"Relay-only calls\" in Settings."
            }
            CallManager.hangUp()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!inPip) TopAppBar(
            title = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(peerName, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.width(8.dp))
                        ConnectionQualityBars()
                        Spacer(modifier = Modifier.width(6.dp))
                        ResolutionBadge()
                    }
                    Text(
                        callSubtitle(
                            mediaText = if (videoOn) stringResource(R.string.call_video) else stringResource(R.string.call_voice),
                            state = state,
                            outgoing = isOutgoing,
                            elapsedSec = elapsedSec,
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Encryption indicator — Telegram/Signal pattern.
                    // SimpleX calls carry an AES key in the invite
                    // payload; if it's set, the leg is e2e-encrypted.
                    // The cyan "verified" hint reflects KnownPeer.verified
                    // (set after a manual safety-code check). Tap to
                    // open the verify screen so the user can act on it.
                    val encrypted = activeCall?.sharedKey != null
                    val peer by produceState<app.aether.aegis.data.KnownPeerEntity?>(null, peerPubkey) {
                        value = AegisApp.instance.repository.knownPeerByKey(peerPubkey)
                    }
                    val verified = peer?.verified == true
                    Row(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .clickable {
                                val ep = java.net.URLEncoder.encode(peerPubkey, "UTF-8")
                                navController.navigate("verify/$ep")
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when {
                                verified -> "🔒  e2e · ✓ verified"
                                encrypted -> "🔒  e2e"
                                else -> "🔓  unencrypted"
                            },
                            color = if (verified) app.aether.aegis.ui.theme.AegisCyan
                                    else if (encrypted) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                            fontSize = 10.sp,
                        )
                    }
                }
            },
            navigationIcon = {
                // hangUp clears CallStore.active; the auto-pop
                // LaunchedEffect above pops CallScreen exactly once
                // when activeCall transitions non-null → null. Calling
                // popBackStack here too would double-pop and dump the
                // user past ChatScreen onto the chat list.
                IconButton(onClick = { CallManager.hangUp() }) {
                    AegisIcon(app.aether.aegis.ui.components.AegisIcons.Back, "End call")
                }
            },
            actions = {
                // Minimize → Android Picture-in-Picture. WhatsApp 2026
                // pattern: drop the call into a corner window so the
                // user can navigate the rest of the app while it
                // keeps running. Manifest declares supportsPictureIn-
                // Picture on MainActivity; PiP-mode flag pipes back
                // through onPictureInPictureModeChanged → CallStore
                // .inPip, which we observe to hide chrome.
                val ctx = androidx.compose.ui.platform.LocalContext.current
                IconButton(onClick = {
                    val act = ctx as? android.app.Activity ?: return@IconButton
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val params = android.app.PictureInPictureParams.Builder()
                            .setAspectRatio(android.util.Rational(16, 9))
                            .build()
                        runCatching { act.enterPictureInPictureMode(params) }
                    }
                }) {
                    Text("⛶", color = Color.White, fontSize = 18.sp)
                }
            },
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // State-driven background tint behind the WebView. Cyan
            // glow when Connected, amber during negotiation, neutral
            // otherwise — see DynamicCallBackground.
            DynamicCallBackground(state)
            // WebRTC video host — shared with the device-control panel
            // (a remote live-cam stream renders inline there instead of
            // here). See [CallVideoSurface].
            CallVideoSurface(modifier = Modifier.fillMaxSize())

            // Pre-connected overlay: pulsing avatar + status caption.
            // We sit this on top of the WebView so the video tags
            // (which only fill once srcObject is set) don't show as
            // a black void to the user during the 1-5 s setup window.
            if (state != CallState.Connected) {
                CallConnectingOverlay(
                    peerName = peerName,
                    statusLine = callStatusLine(state, isOutgoing),
                )
                // Debug state badge — top-right pill, shows the raw
                // CallState enum so failed calls are diagnosable
                // without pulling logcat ("stuck at OfferSent" vs
                // "stuck at WaitCapabilities" point at different fixes).
                Surface(
                    color = AegisCyan.copy(alpha = 0.85f),
                    shape = CutCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp),
                ) {
                    Text(
                        state?.name ?: "—",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            // Hard-timeout dialog. The LaunchedEffect that watches
            // each pre-Connected state populates timeoutMsg when a
            // ceiling is hit; we present it here so the user always
            // gets an explanation instead of an indefinite Preparing…
            timeoutMsg?.let { msg ->
                AlertDialog(
                    onDismissRequest = {
                        timeoutMsg = null
                        navController.popBackStack()
                    },
                    title = { Text("Call didn't connect") },
                    text = { Text(msg) },
                    confirmButton = {
                        TextButton(onClick = {
                            timeoutMsg = null
                            navController.popBackStack()
                        }) { Text("OK") }
                    },
                )
            }

            // Chevron toggle for the control bar. Sits just above the
            // bar so users can hide a busy strip of buttons to maximise
            // the video area without losing access — tap to bring back.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (controlsVisible) 96.dp else 8.dp)
                    // Cut-corner chip echoes the hex/angular LunaGlass
                    // geometry instead of the pill it used to be.
                    .clip(CutCornerShape(6.dp))
                    .background(AegisPanel.copy(alpha = 0.7f))
                    .clickable { controlsVisible = !controlsVisible }
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                Text(
                    if (controlsVisible) "▾" else "▴",
                    color = AegisCyan,
                    fontSize = 14.sp,
                )
            }

            // Floating reactions overlay. Emoji rise
            // from the bottom whenever either side taps a reaction.
            CallReactionsOverlay()

            // Control bar overlay along the bottom.
            androidx.compose.animation.AnimatedVisibility(
                visible = controlsVisible,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit  = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
            // LunaGlass control scrim: a vertical fade from transparent
            // into the dark cyan-tinted panel colour, so the controls lift
            // off the video without a hard black bar. Replaces the flat
            // Color.Black 0.55 Surface.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                AegisBackground.copy(alpha = 0.82f),
                            ),
                        ),
                    ),
            ) {
                Column(modifier = Modifier.padding(top = 18.dp, bottom = 12.dp)) {
                    // Quick-reactions strip (Tier 3) — sits above the
                    // primary control buttons so neither bar fights the
                    // other for tap targets.
                    CallReactionsRow(peerKey = peerPubkey)
                    Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CallControlButton(
                        label = if (muted) "Unmute" else "Mute",
                        iconRes = AegisIcons.Mic,
                        // Muted is the "engaged / something-is-off" state →
                        // amber-filled plate so it reads as active at a glance.
                        accent = if (muted) AegisWarning else AegisCyan,
                        engaged = muted,
                        onClick = {
                            muted = !muted
                            // call.js's CallMediaSource enum keys to
                            // the literal string "mic" / "camera"
                            // (lower-case). "Microphone" / "Camera"
                            // (capitalised) fell through the switch
                            // and the JS side never toggled the
                            // track — mute did nothing, camera never
                            // sent video.
                            CallManager.toggleMedia("mic", !muted)
                        },
                    )
                    AudioRouteButton()
                    if (activeCall?.media == CallMediaType.Video) {
                        CallControlButton(
                            label = if (videoOn) "Camera off" else "Camera on",
                            iconRes = AegisIcons.Camera,
                            accent = if (videoOn) AegisCyan else AegisWarning,
                            engaged = !videoOn,
                            onClick = {
                                videoOn = !videoOn
                                CallManager.toggleMedia("camera", videoOn)
                            },
                        )
                        CallControlButton(
                            label = "Flip",
                            glyph = "⟲",
                            onClick = { CallManager.flipCamera() },
                        )
                    }
                    CallControlButton(
                        label = "End",
                        iconRes = AegisIcons.Call,
                        accent = AegisSOS,
                        // Filled red plate — the one destructive control,
                        // unmistakable in the strip.
                        engaged = true,
                        // hangUp clears CallStore.active; auto-pop
                        // takes us back to the chat. Explicit pop
                        // here would double-pop past the chat.
                        onClick = { CallManager.hangUp() },
                    )
                }
                }  // close Column
            }
            }  // close AnimatedVisibility
        }
    }
}

@Composable
private fun CallConnectingOverlay(peerName: String, statusLine: String) {
    val infinite = rememberInfiniteTransition(label = "call-pulse")
    val scale by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "call-pulse-scale",
    )
    val alpha by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "call-pulse-alpha",
    )
    Box(
        // Deep near-black LunaGlass backdrop (the app background colour)
        // instead of the old bluish-grey, so the cyan hex reads as glass.
        modifier = Modifier.fillMaxSize().background(AegisBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Pulsing metal-framed hex with the peer monogram — the same
            // LunaGlass avatar geometry used across the app, breathing while
            // the call sets up. scale/alpha carry the "ringing" pulse.
            Box(
                modifier = Modifier.scale(scale).alpha(alpha),
                contentAlignment = Alignment.Center,
            ) {
                HexShape(
                    size = 160.dp,
                    borderColor = AegisCyan,
                    borderWidth = 6.dp,
                    fillBrush = Brush.verticalGradient(
                        listOf(
                            AegisPanel.copy(alpha = 0.9f),
                            AegisBackground.copy(alpha = 0.9f),
                        ),
                    ),
                    medalFrame = true,
                    glow = true,
                    glowColor = AegisCyan.copy(alpha = 0.25f),
                ) {
                    Text(
                        peerName.firstOrNull()?.uppercase() ?: "?",
                        color = AegisCyan,
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(peerName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(statusLine, color = AegisCyan.copy(alpha = 0.75f), fontSize = 14.sp)
        }
    }
}

/** User-facing one-liner for the TopAppBar (compact). Includes the
 *  elapsed-time counter while Connected. */
private fun callSubtitle(
    mediaText: String,
    state: CallState?,
    outgoing: Boolean,
    elapsedSec: Int,
): String {
    if (state == CallState.Connected) {
        val mm = elapsedSec / 60
        val ss = elapsedSec % 60
        return "$mediaText · %d:%02d".format(mm, ss)
    }
    return "$mediaText · ${callStatusLine(state, outgoing)}"
}

/** User-facing label for the pre-connected overlay + the subtitle. */
private fun callStatusLine(state: CallState?, outgoing: Boolean): String = when (state) {
    null -> "Starting…"
    CallState.WaitCapabilities -> if (outgoing) "Preparing…" else "Incoming…"
    CallState.InvitationSent -> if (outgoing) "Calling…" else "Incoming…"
    CallState.InvitationAccepted -> "Connecting…"
    CallState.OfferSent, CallState.OfferReceived -> "Connecting…"
    CallState.AnswerReceived, CallState.Negotiated -> "Connecting…"
    CallState.Connected -> "Connected"
    CallState.Ended -> "Ended"
}

/**
 * Audio-route selector for the call screen. Single button showing the
 * current route (Earpiece / Speaker / Bluetooth / Wired); tap opens a
 * sheet to pick a different one. Routes that aren't currently
 * available on the hardware are omitted. WhatsApp / Telegram / Signal
 * all ship something equivalent — this closes Tier 1 c of the call-
 * screen TODO.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioRouteButton() {
    val available by app.aether.aegis.call.CallAudioRouter.available.collectAsState()
    val current by app.aether.aegis.call.CallAudioRouter.current.collectAsState()
    var sheetOpen by remember { mutableStateOf(false) }

    // Refresh available routes when the button first composes — picks
    // up devices that connected after the call started.
    LaunchedEffect(Unit) { app.aether.aegis.call.CallAudioRouter.refresh() }

    val label = when (current) {
        app.aether.aegis.call.CallAudioRouter.Route.Earpiece  -> "Earpiece"
        app.aether.aegis.call.CallAudioRouter.Route.Speaker   -> "Speaker"
        app.aether.aegis.call.CallAudioRouter.Route.Bluetooth -> "BT"
        app.aether.aegis.call.CallAudioRouter.Route.Wired     -> "Wired"
        null                                        -> "Audio"
    }
    // Short in-hex glyph for the route — there's no dedicated speaker
    // icon in the LunaGlass set, so a 2-3 char tag inside the hex carries
    // the current route while the label below spells it out.
    val glyph = when (current) {
        app.aether.aegis.call.CallAudioRouter.Route.Earpiece  -> "EAR"
        app.aether.aegis.call.CallAudioRouter.Route.Speaker   -> "SPK"
        app.aether.aegis.call.CallAudioRouter.Route.Bluetooth -> "BT"
        app.aether.aegis.call.CallAudioRouter.Route.Wired     -> "WIR"
        null                                        -> "AUD"
    }
    CallControlButton(
        label = label,
        glyph = glyph,
        // Speaker / Bluetooth are "audio is routed away from the ear" —
        // treat them as an engaged state so the active route is obvious.
        engaged = current == app.aether.aegis.call.CallAudioRouter.Route.Speaker ||
            current == app.aether.aegis.call.CallAudioRouter.Route.Bluetooth,
        onClick = { sheetOpen = true },
    )

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Route audio to",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                available.forEach { route ->
                    val routeLabel = when (route) {
                        app.aether.aegis.call.CallAudioRouter.Route.Earpiece  -> "Earpiece"
                        app.aether.aegis.call.CallAudioRouter.Route.Speaker   -> "Speaker"
                        app.aether.aegis.call.CallAudioRouter.Route.Bluetooth -> "Bluetooth"
                        app.aether.aegis.call.CallAudioRouter.Route.Wired     -> "Wired headset"
                    }
                    val isCurrent = route == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                app.aether.aegis.call.CallAudioRouter.select(route)
                                sheetOpen = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (isCurrent) "● " else "○ ",
                            color = if (isCurrent) app.aether.aegis.ui.theme.AegisCyan else Color.Gray,
                            fontSize = 16.sp,
                        )
                        Text(
                            routeLabel,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * 4-bar signal-strength indicator next to the peer name. Driven by
 * [CallStore.quality]: bars filled = quality bucket. Unknown =
 * grey-ghost, Failed = red, Poor = amber, Fair/Good = cyan.
 * Closes Tier 2 "Connection quality indicator" from CALL_SCREEN_TODO.
 */
@Composable
private fun ConnectionQualityBars() {
    val q by CallStore.quality.collectAsState()
    val (filled, color) = when (q) {
        CallQuality.Good    -> 4 to app.aether.aegis.ui.theme.AegisCyan
        CallQuality.Fair    -> 3 to app.aether.aegis.ui.theme.AegisCyan
        CallQuality.Poor    -> 2 to Color(0xFFFFC107)
        CallQuality.Failed  -> 1 to Color(0xFFFF5555)
        CallQuality.Unknown -> 0 to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) {
            val isOn = i <= filled
            Box(
                modifier = Modifier
                    .padding(end = 1.dp)
                    .width(3.dp)
                    .height((4 + i * 2).dp)
                    .background(
                        if (isOn) color
                        else color.copy(alpha = 0.20f),
                    ),
            )
        }
    }
}

/** Netflix-style adaptive resolution readout. Shows the current
 *  ladder rung the JS auto-monitor picked based on packet loss + RTT.
 *  Hidden when there's no stats snapshot yet (pre-Connected). Closes
 *  the "signal quality detection + automatic resolution adjustment
 *  Netflix-style" request. */
@Composable
private fun ResolutionBadge() {
    val stats by CallStore.stats.collectAsState()
    val bucket = stats?.bucket ?: return
    val short = when (bucket) {
        "AUTO_HIGH"     -> "720p"
        "AUTO_MEDIUM"   -> "480p"
        "AUTO_LOW"      -> "360p"
        "AUTO_VERY_LOW" -> "240p"
        else             -> bucket
    }
    Text(
        short,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * Subtle full-screen tint behind the WebView that reflects the call
 * state. Cyan glow when Connected, amber while Negotiating, red on
 * Failed. Helps the user read the call's overall health at a glance
 * without parsing the small state-badge pill — closes Tier 2
 * "Dynamic background by state" from CALL_SCREEN_TODO.
 */
@Composable
private fun DynamicCallBackground(state: CallState?) {
    val targetColor = when (state) {
        CallState.Connected                                  -> app.aether.aegis.ui.theme.AegisCyan.copy(alpha = 0.07f)
        CallState.OfferReceived, CallState.OfferSent,
        CallState.AnswerReceived, CallState.Negotiated      -> Color(0xFFFFC107).copy(alpha = 0.06f)
        null                                                  -> Color.Transparent
        else                                                  -> app.aether.aegis.ui.theme.AegisCyan.copy(alpha = 0.03f)
    }
    val animColor by androidx.compose.animation.animateColorAsState(
        targetValue = targetColor, label = "call-bg",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animColor),
    )
}

/**
 * One LunaGlass call control: a metal-framed flat-top [HexShape] over a
 * dark glass plate, with either a LunaGlass [iconRes] or a short [glyph]
 * inside, and a caption below. Replaces the old Material `CircleShape`
 * pill so the call screen speaks the same hex/cyan language as the rest
 * of the app.
 *
 * @param accent frame + content colour — cyan for a neutral control,
 *   amber for an "engaged / muted / camera-off" state, red for End.
 * @param engaged fills the plate with the accent (toggled-on look); off
 *   leaves the recessed dark-glass plate so the control reads as idle.
 *
 * The whole hex is the tap target (HexShape wires [onClick] with the
 * standard LunaGlass haptic); this only paints + captions it.
 */
@Composable
private fun CallControlButton(
    label: String,
    onClick: () -> Unit,
    iconRes: Int? = null,
    glyph: String? = null,
    accent: Color = AegisCyan,
    engaged: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HexShape(
            size = 58.dp,
            borderColor = accent,
            // Proportional to the hex (matches the avatar-frame rule) so the
            // medalFrame bevel reads as a chunky metal rim, not a hairline.
            borderWidth = (58f * 0.055f).dp,
            // Engaged → accent-filled plate; idle → recessed dark glass.
            fillBrush = if (engaged) {
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.6f)),
                )
            } else {
                Brush.verticalGradient(
                    listOf(AegisPanel.copy(alpha = 0.85f), AegisBackground.copy(alpha = 0.85f)),
                )
            },
            // Static metal bevel so the frame looks like the avatar medals
            // even with the animated sheen toggle off.
            medalFrame = true,
            onClick = onClick,
        ) {
            // Content colour flips to dark on a filled (engaged) plate so an
            // icon/glyph stays legible against the bright accent.
            val content = if (engaged) AegisBackground else accent
            when {
                iconRes != null -> AegisIcon(
                    iconRes,
                    label,
                    tint = content,
                    modifier = Modifier.size(hexInnerIcon(58.dp)),
                )
                glyph != null -> Text(
                    glyph,
                    color = content,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        }
        Text(
            label,
            color = Color.White,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

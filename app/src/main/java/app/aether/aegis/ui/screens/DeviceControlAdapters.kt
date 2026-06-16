package app.aether.aegis.ui.screens

import app.aether.aegis.AegisApp
import app.aether.aegis.core.MessageType
import app.aether.aegis.core.SOSTrigger
import app.aether.aegis.sos.SOSAlertStore
import app.aether.aegis.remote.RemoteAccessProtocol
import app.aether.aegis.remote.RemoteAccessSession
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember

/**
 * Adapters between the existing SOS / remote-access stores and the
 * unified [DeviceControlScreen].
 *
 * Read-only projection over existing state; actions fan out to the
 * existing send paths. No backend touches. Same file so a new field
 * on either store touches both adapters in one diff (mitigation
 * against adapter drift).
 *
 * Mode-flag discipline note: the
 * shell's [ControlMode] enum exists only to drive the tint + the
 * mode-detail slot renderer. Conditional behaviour in actions
 * branches on the *presence* of a lambda in [ControlActions], not
 * on the mode. Keep it that way.
 */

// ============================================================
// SOS ADAPTER
// ============================================================

/**
 * Entry composable for the SOS face of the unified console.
 *
 * Projects the [SOSAlertStore] / repository state for [victimKey] into
 * a [ControlState] and renders [DeviceControlScreen] in SOS mode. The
 * "victim" is whichever peer raised the SOS — it may be the local
 * device itself (self-triggered SOS), in which case [ControlState] is
 * built with `isVictim = true` and the action set flips (mark-safe vs
 * acknowledge).
 *
 * Renders nothing when there's no active alert for the peer, so the
 * caller can route here unconditionally and the screen self-suppresses
 * once the SOS clears.
 */
@Composable
fun SOSAdapter(victimKey: String) {
    val state = collectSOSControlState(victimKey)
    if (state == null) return  // no active SOS for this peer
    // Actions are keyed on victimKey only — they re-read live state via
    // the stores when invoked, so they never need to recompose.
    val actions = remember(victimKey) { sosControlActions(victimKey) }
    DeviceControlScreen(state, actions)
}

/**
 * Build the [ControlState] for an SOS alert, or null if no alert is
 * active for [victimKey]. Pure read-side projection — never sends.
 *
 * Sources: [SOSAlertStore] (alert + responders), the repository
 * conversation stream (audio clips), and the per-peer status row
 * (location / battery / network / online). All are observed reactively
 * so the screen tracks live updates; [nowTicker] forces a 1 Hz
 * recompose so age strings still advance between store emissions.
 */
@Composable
private fun collectSOSControlState(victimKey: String): ControlState? {
    val alert = SOSAlertStore.forPeer(victimKey) ?: return null
    // Force a recompose tick once a second so age/expiry render keeps
    // ticking even without store updates.
    val now = nowTicker()

    val peer by produceState<app.aether.aegis.data.KnownPeerEntity?>(initialValue = null, victimKey) {
        value = AegisApp.instance.repository.knownPeerByKey(victimKey)
    }
    // Fall back to a key prefix if the peer isn't in the address book
    // (e.g. an inbound SOS from someone not yet saved).
    val peerName = peer?.displayName ?: victimKey.take(8)

    // Audio clips come from the message stream — sos-audio messages
    // are attachment-bearing rows tagged `[aegis:sos-audio]`. Only the
    // victim's OWN clips count (from != selfKey), newest first.
    val messages by AegisApp.instance.repository
        .conversation(victimKey)
        .collectAsState(initial = emptyList())
    val selfKey = AegisApp.instance.identity.deviceId
    val audioClips = messages
        .asSequence()
        .filter {
            it.from != selfKey && it.attachmentPath != null &&
                it.content.startsWith("[aegis:sos-audio]")
        }
        .sortedByDescending { it.timestamp }
        .map { AudioClip(path = it.attachmentPath!!, ts = it.timestamp) }
        .toList()

    val status by AegisApp.instance.repository.observeStatus(victimKey)
        .collectAsState(initial = null)

    // Only surface a map fix when BOTH coordinates are present; a
    // partial fix (one null) is treated as no fix.
    val location = status?.let { s ->
        val lat = s.latitude
        val lng = s.longitude
        if (lat != null && lng != null) GeoFix(lat, lng, s.lastActive) else null
    }

    // SOS frame — file path on disk. Read bytes for the shell.
    // Cheap: bounded ~100 KB JPEGs, only re-read on alert tick (not
    // every recomposition, because we `remember` on the path).
    val framePath = alert.latestSnapshotPath
    val frontFrame = remember(framePath, alert.latestSnapshotAt) {
        if (framePath == null) return@remember null
        val bytes = runCatching { java.io.File(framePath).readBytes() }.getOrNull()
            ?: return@remember null
        FrameSnapshot(bytes = bytes, ts = alert.latestSnapshotAt, lensLabel = "front")
    }

    // A responder counts as "arrived" once it has reported an arrival
    // timestamp; until then it renders as still-en-route.
    val responders = SOSAlertStore.respondersFor(victimKey).map {
        ResponderTile(
            peerKey = it.peerKey,
            displayName = it.displayName,
            arrived = it.arrivedAt != null,
        )
    }

    val isVictim = victimKey == selfKey
    return ControlState(
        peerKey = victimKey,
        peerName = peerName,
        mode = ControlMode.SOS,
        locationFix = location,
        frontFrame = frontFrame,
        rearFrame = null,  // SOS broadcasts a single frame stream
        audioClips = audioClips,
        battery = status?.batteryLevel,
        networkType = status?.networkType,
        online = app.aether.aegis.ui.components.peerStatusFor(status, now, isSelf = isVictim),
        detail = ModeDetail.SOS(
            // SOSTrigger isn't carried over the wire for received
            // soss; default to BUTTON for the displayed lower-case
            // label. UI doesn't rely on this enum for correctness.
            trigger = SOSTrigger.BUTTON,
            triggeredAt = alert.startedAt,
            responders = responders,
            isVictim = isVictim,
        ),
    )
}

/**
 * The SOS action set — deliberately narrow. SOS has no remote-control
 * surface (no locate/lock/wipe/snapshot): the only buttons are a PTT
 * intent ping and the mark-safe / acknowledge pair, gated on whether
 * the local device is the victim or a responder.
 */
private fun sosControlActions(victimKey: String): ControlActions {
    val aegisApp = AegisApp.instance
    val selfKey = aegisApp.identity.deviceId
    // The victim sees "Mark safe" (cancel my own SOS); responders see
    // "Acknowledge" (dismiss the banner). Mutually exclusive by design.
    val isVictim = victimKey == selfKey

    // PTT — reuse the existing aegis:ptt wire envelope. The sos-side
    // PTT screen builds the same caption; we just send a recording-
    // less ping so the responder UI lights up the talk affordance.
    // Real audio capture / streaming is the WebRTC follow-up; for
    // this PR the button announces intent only.
    //
    // Mark-safe — victim's "I'm OK" cancels the SOS via
    // SOSHandler.cancel; receiver-side acknowledge dismisses
    // the banner via SOSAlertStore.markDismissed.
    return ControlActions(
        onPushToTalk = {
            // Existing SOSPtt entry point is a composable, not a
            // direct send. The pragmatic shim for the unified screen
            // is to fan out an [aegis:ptt:<victimKey>] STATUS marker
            // so receivers recognise the channel is opening. Voice
            // capture is unchanged from the SOS flow and reuses
            // SOSPtt when navigation lands on the SOS dashboard.
            // Documented: this button currently signals intent only.
            aegisApp.protocolManager.sendMessage(
                to = victimKey,
                content = "[aegis:ptt:$victimKey]",
                type = MessageType.STATUS,
            )
        },
        onMarkSafe = if (isVictim) {
            { aegisApp.sosHandler.cancel() }
        } else null,
        onAcknowledge = if (!isVictim) {
            { SOSAlertStore.markDismissed(victimKey) }
        } else null,
    )
}

// ============================================================
// REMOTE ADAPTER
// ============================================================

/**
 * Entry composable for the remote-access face of the unified console —
 * the operator-driven locate / lock / wipe / siren / snapshot / listen /
 * live-cam surface for one of the owner's own devices.
 *
 * Requires an AUTHENTICATED [RemoteAccessSession] for [peerKey]: every
 * command carries the session id (`sid`) the target minted at auth, and
 * renders nothing when there's no live session (so it self-suppresses
 * once the session expires or is closed). The target enforces the
 * preconditions per command (Device-Admin for lock, Device-Owner for
 * wipe, runtime Location/Microphone grants for locate/listen) and
 * replies with a typed error the screen surfaces as a toast.
 */
@Composable
fun RemoteAdapter(peerKey: String) {
    val state = collectRemoteControlState(peerKey)
    if (state == null) return  // no active remote session
    // Keyed on peerKey only: the action lambdas re-fetch the live sid on
    // every press (see currentSid), so they survive sid rotation without
    // being rebuilt.
    val actions = remember(peerKey) { remoteControlActions(peerKey) }
    DeviceControlScreen(state, actions)
}

/**
 * Build the [ControlState] for an active remote session, or null if
 * none exists for [peerKey]. Pure read-side projection.
 *
 * All visible data is pulled from the [RemoteAccessSession] entry the
 * target populated via its protocol replies: last LOCATE fix, the
 * front/rear mugshot JPEGs (base64), the LISTEN audio clip (base64,
 * materialised to a cache file), session sid/expiry, lock-OK flag, and
 * battery/charging. Battery/network/online also come from the per-peer
 * status row. [nowTicker] drives the 1 Hz age/expiry refresh.
 */
@Composable
private fun collectRemoteControlState(peerKey: String): ControlState? {
    val sessions by RemoteAccessSession.sessions.collectAsState()
    val session = sessions[peerKey] ?: return null
    val now = nowTicker()

    val peer by produceState<app.aether.aegis.data.KnownPeerEntity?>(initialValue = null, peerKey) {
        value = AegisApp.instance.repository.knownPeerByKey(peerKey)
    }
    val peerName = peer?.displayName ?: peerKey.take(8)

    val location = if (session.locateLat != null && session.locateLng != null) {
        GeoFix(
            lat = session.locateLat,
            lng = session.locateLng,
            ts = session.locateTs ?: now,
        )
    } else null

    // Decode mugshots only when the base64 actually changes — the
    // remember keys on the encoded string, so a recompose for any other
    // reason doesn't re-run the (non-trivial) Base64 decode.
    val frontFrame = remember(session.mugshotB64) {
        decodeFrame(session.mugshotB64, session.locateTs ?: now, "front")
    }
    val rearFrame = remember(session.rearMugshotB64) {
        decodeFrame(session.rearMugshotB64, session.locateTs ?: now, "rear")
    }

    // Audio comes back from KIND_LISTEN_RESULT as base64; materialise
    // to a scratch file under cacheDir so the AudioClipRow's
    // MediaPlayer can read it. Keyed on the base64 so the file is only
    // (re)written when a new clip arrives.
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioPath = remember(session.audioB64) {
        val b64 = session.audioB64 ?: return@remember null
        val dir = java.io.File(context.cacheDir, "remote_listen_in").apply { mkdirs() }
        val f = java.io.File(dir, "remote-${session.audioTs ?: System.currentTimeMillis()}.m4a")
        runCatching {
            f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP))
            f.absolutePath
        }.getOrNull()
    }
    val audioClips = audioPath?.let {
        listOf(AudioClip(path = it, ts = session.audioTs ?: now))
    }.orEmpty()

    val status by AegisApp.instance.repository.observeStatus(peerKey)
        .collectAsState(initial = null)

    return ControlState(
        peerKey = peerKey,
        peerName = peerName,
        mode = ControlMode.REMOTE,
        locationFix = location,
        frontFrame = frontFrame,
        rearFrame = rearFrame,
        audioClips = audioClips,
        battery = status?.batteryLevel,
        networkType = status?.networkType,
        online = app.aether.aegis.ui.components.peerStatusFor(status, now),
        detail = ModeDetail.Remote(
            sessionSid = session.sid,
            sessionExpiresAt = session.expiry,
            lockOk = session.lockOk,
            batteryPct = session.batteryPct,
            charging = session.charging,
        ),
    )
}

/** Decode a base64 JPEG frame into a [FrameSnapshot], or null if the
 *  input is absent or undecodable. [ts] stamps the frame's age and
 *  [label] is the lens tag ("front"/"rear") shown in the slot. Decode
 *  failure is swallowed (returns null) rather than thrown — a corrupt
 *  payload should leave the slot empty, not crash the console. */
private fun decodeFrame(b64: String?, ts: Long, label: String): FrameSnapshot? {
    if (b64.isNullOrBlank()) return null
    val bytes = runCatching {
        android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
    }.getOrNull() ?: return null
    return FrameSnapshot(bytes = bytes, ts = ts, lensLabel = label)
}

/**
 * Build the remote-command action set for [peerKey].
 *
 * Every action (except PTT) follows the same shape: re-fetch the live
 * session id via [currentSid], and only if present encode a
 * [RemoteAccessProtocol.Packet] of the matching `KIND_*` and send it as
 * a STATUS message to the target. A null sid is a silent no-op (logged)
 * — the button does nothing rather than sending an unauthenticated
 * command the target would reject.
 *
 * IMPORTANT — fire-and-forget semantics: these lambdas only QUEUE the
 * command onto the wire. They do NOT confirm the target accepted or
 * executed it; success/visible effect happens on the target's device,
 * and any rejection comes back asynchronously as a typed error
 * (surfaced by the screen as a toast). The per-button "Sent: …" toast
 * therefore means "transmitted", not "done".
 *
 * Precondition enforcement lives on the TARGET, not here: lock needs
 * Device-Admin, wipe needs Device-Owner provisioning (otherwise
 * silently dropped), locate needs the Location grant, listen/live-mic
 * need the Microphone grant. This side just sends the kind.
 */
private fun remoteControlActions(peerKey: String): ControlActions {
    val aegisApp = AegisApp.instance
    // Encode + send one protocol packet to the target, logging the kind
    // and a truncated sid/peer for the connection trace. STATUS type so
    // it rides the same envelope as ordinary presence traffic.
    fun send(packet: RemoteAccessProtocol.Packet) {
        app.aether.aegis.simplex.ConnectionLog.log(
            "RemoteAction",
            "send kind=${packet.kind} sid=${packet.sid?.take(6) ?: "null"}… to=${peerKey.take(20)}…",
        )
        aegisApp.protocolManager.sendMessage(
            to = peerKey,
            content = RemoteAccessProtocol.encode(packet),
            type = MessageType.STATUS,
        )
    }
    // Re-fetch sid on EVERY action invocation. Capturing it at
    // action-construction time (the pre-665 bug) meant any button
    // pressed before the session opened — or after a re-auth that
    // minted a new sid — silently no-op'd. Looking it up fresh
    // costs a Map read against RemoteAccessSession.sessions.value —
    // sub-microsecond — and tracks session state correctly.
    fun currentSid(): String? {
        val sid = RemoteAccessSession.sidFor(peerKey)
        if (sid == null) {
            app.aether.aegis.simplex.ConnectionLog.warn(
                "RemoteAction",
                "currentSid NULL for peer=${peerKey.take(20)}… — button no-op",
            )
        }
        return sid
    }
    return ControlActions(
        // PTT on remote: reuse the
        // SOS PTT wire envelope addressed to the target. Sent WITHOUT a
        // sid (it's an ordinary STATUS marker, not a session command),
        // so it works even before/outside an authenticated session.
        // Target's
        // RemoteAccessHandler doesn't auto-play it (live audio is a
        // follow-up via WebRTC); the clip lands as a regular voice
        // message in the target's chat. Sender's intent is conveyed
        // either way. The trust-model rationale gates this on the
        // target being a Trusted remote.
        onPushToTalk = {
            aegisApp.protocolManager.sendMessage(
                to = peerKey,
                content = "[aegis:ptt:$peerKey]",
                type = MessageType.STATUS,
            )
        },
        onListen = { seconds ->
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_LISTEN,
                    sid = it,
                    seconds = seconds,
                ))
            }
        },
        onSiren = {
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(kind = RemoteAccessProtocol.KIND_SIREN, sid = it))
            }
        },
        onSirenOff = {
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(kind = RemoteAccessProtocol.KIND_SIREN_OFF, sid = it))
            }
        },
        onDisplay = { msg ->
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_DISPLAY,
                    sid = it,
                    msg = msg,
                ))
            }
        },
        onForceLocate = {
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(kind = RemoteAccessProtocol.KIND_LOCATE, sid = it))
            }
        },
        onUpdate = {
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(kind = RemoteAccessProtocol.KIND_UPDATE, sid = it))
            }
        },
        onLiveCam = { lens ->
            currentSid()?.let {
                // Arm the panel to host the incoming stream (the target
                // calls back; CallManager auto-answers a panel live peer
                // and renders inline instead of spawning a CallScreen).
                app.aether.aegis.remote.RemoteAccessSession.startLiveStream(peerKey)
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_LIVE_CAM_START,
                    sid = it,
                    lens = lens,
                ))
            }
        },
        onLiveCamFlip = {
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_LIVE_CAM_FLIP,
                    sid = it,
                ))
            }
        },
        onLiveCamStop = {
            // Tear down the LOCAL panel host + WebRTC call FIRST, then
            // signal the target to stop capturing. Done locally even if
            // the sid is gone, so a stop always frees this side's
            // camera/call rather than leaking it on a dead session.
            app.aether.aegis.remote.RemoteAccessSession.stopLiveStream(peerKey)
            runCatching { app.aether.aegis.call.CallManager.hangUp() }
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_LIVE_CAM_STOP,
                    sid = it,
                ))
            }
        },
        onLiveMic = {
            currentSid()?.let {
                // Same arm-then-dial flow as live cam, audio-only.
                app.aether.aegis.remote.RemoteAccessSession.startLiveStream(peerKey)
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_LIVE_MIC_START,
                    sid = it,
                ))
            }
        },
        onLiveMicStop = {
            app.aether.aegis.remote.RemoteAccessSession.stopLiveStream(peerKey)
            runCatching { app.aether.aegis.call.CallManager.hangUp() }
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_LIVE_MIC_STOP,
                    sid = it,
                ))
            }
        },
        onRing = {
            currentSid()?.let {
                // Find-my-phone ringer with a fixed 30 s auto-stop so it
                // never rings forever if the OFF command is missed.
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_RING,
                    sid = it,
                    seconds = 30,
                ))
            }
        },
        onRingOff = {
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_RING_OFF,
                    sid = it,
                ))
            }
        },
        onSnapshot = { lens ->
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_SNAPSHOT,
                    sid = it,
                    lens = lens,
                ))
            }
        },
        onPing = {
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_PING,
                    sid = it,
                ))
            }
        },
        onWatchPause = {
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_WATCH_PAUSE,
                    sid = it,
                ))
            }
        },
        onWatchResume = {
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_WATCH_RESUME,
                    sid = it,
                ))
            }
        },
        onWipe = { pin ->
            // Factory-reset the target. Authenticated (sid required) and
            // gated UI-side by the WIPE confirmation ladder, whose final
            // step collects the TARGET's app PIN ([pin]) — carried on the
            // packet so the target re-verifies it before firing (the
            // operator's own PIN is irrelevant). The target additionally
            // requires Device-Owner provisioning or it silently drops the
            // command. Irreversible.
            val sid = currentSid()
            if (sid == null) {
                // Don't fail SILENTLY — the whole ladder runs, you type the PIN,
                // and then nothing happens because there's no live session
                // (never authenticated, or it idled out). That's the "I tried
                // Wipe and NOTHING" report. Tell the operator exactly what to do.
                android.widget.Toast.makeText(
                    aegisApp,
                    "No active remote session — open Remote access, enter the " +
                        "target's PIN to authenticate, then retry Wipe.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } else {
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_WIPE,
                    sid = sid,
                    pin = pin,
                ))
            }
        },
        onRemoteSos = {
            // Raise the target's SOS on their behalf (remote SOS button).
            // Session-gated; confirmation handled UI-side. The target fires a
            // normal visible SOS and can cancel it if it's a false alarm.
            currentSid()?.let {
                send(RemoteAccessProtocol.Packet(
                    kind = RemoteAccessProtocol.KIND_REMOTE_SOS,
                    sid = it,
                ))
            }
        },
        onExit = {
            // Leaving the panel tears down any live stream too, so the
            // WebRTC call + camera don't keep running headless.
            RemoteAccessSession.stopLiveStream(peerKey)
            runCatching { app.aether.aegis.call.CallManager.hangUp() }
            RemoteAccessSession.close(peerKey)
        },
    )
}

// ============================================================
// Helpers
// ============================================================

/** Recompose every second so age/expiry strings keep ticking even
 *  when no store update fires. */
@Composable
private fun nowTicker(): Long {
    val now = remember { androidx.compose.runtime.mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            now.value = System.currentTimeMillis()
        }
    }
    return now.value
}

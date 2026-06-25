package app.aether.aegis.call

import app.aether.aegis.AegisApp
import app.aether.aegis.simplex.SimpleXTransport
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Orchestrator for SimpleX-backed WebRTC calls.
 *
 * Three planes converge here:
 *
 *  1. The Compose UI (CallScreen) — owns a WebView that loads the
 *     SimpleX-vendored call.html. CallScreen registers the WebView
 *     with this manager via [attachWebView] so we can issue JS
 *     commands and receive JS-originated events.
 *
 *  2. The JS bridge — call.js calls `window.androidApi.onWebRtcMessage`
 *     (we install this) with WCallResponse objects: ok, error,
 *     description, offer SDP + iceCandidates, answer, ICE candidates,
 *     connected, ended, peerMedia, capabilities. We forward those
 *     either to the SimpleX core (offer/answer/ice) or to local
 *     state (connected/ended).
 *
 *  3. The SimpleX core — emits call events (`callInvitation`,
 *     `callOffer`, `callAnswer`, `callExtraInfo`, `callEnded`) which
 *     SimpleXTransport routes here via [onSimpleXCallEvent]. We feed
 *     those back into the JS engine by calling `processCommand({...})`
 *     in the WebView.
 *
 * State for a single active call lives in [CallStore.active]; the
 * Compose UI observes that StateFlow.
 */
object CallManager {

    private const val TAG = "CallManager"
    private const val CHANNEL_CALL = app.aether.aegis.AegisApp.CHANNEL_CALL
    private const val INCOMING_NOTIF_ID = 2000

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile private var webView: WebView? = null
    @Volatile private var jsReady = false
    /** Commands queued before the JS engine signalled ready. */
    private val pendingJsCommands = ArrayDeque<JSONObject>()
    /** Guards [jsReady] + [pendingJsCommands] as one unit. They're touched
     *  from BOTH the WebRTC-event coroutine (handleJsResponse) and the main
     *  thread (sendJsCommand from UI, end, attach/detach), so the
     *  check-then-enqueue and the flip-then-flush must be atomic — otherwise
     *  a command enqueued exactly as the flush flips jsReady=true is orphaned
     *  forever (a lost offer/answer/ICE → the call silently never connects),
     *  and the plain ArrayDeque can be mutated concurrently. */
    private val jsLock = Any()

    /**
     * Called by CallScreen once it has constructed the WebView and the
     * page has loaded. We inject the bridge that overrides call.js's
     * default sendMessageToNative (which just console.log'd) so the JS
     * pipeline talks to us.
     */
    /** True once a WebView has been wired AND its page has fired
     *  onPageFinished. Used by CallScreen.factory to decide whether
     *  to reuse the cached static WebView (page already loaded —
     *  reuse) vs create a fresh one + reload (first-ever call). */
    fun hasCachedWebView(): Boolean = webView != null
    fun cachedWebView(): WebView? = webView

    @SuppressLint("AddJavascriptInterface", "JavascriptInterface")
    fun attachWebView(wv: WebView) {
        if (webView === wv) {
            // Same WebView instance the AndroidView factory just handed
            // back to us (reuse path). Nothing to wire — bridge is
            // already attached, page is already loaded.
            return
        }
        // Evict any PRIOR WebView before adopting the new one. Without
        // this, a previous instance — especially a throwaway remote-live /
        // SOS streamer that tore down with detachWebView() only — is
        // orphaned ALIVE: it keeps running its WebRTC JS and keeps calling
        // androidApi.onWebRtcMessage(). Chromium's GIN Java-bridge
        // re-reflects that method on invocation and pins each resolved
        // java.lang.reflect.Method as a JNI GLOBAL ref; getMethod returns a
        // fresh Method instance every time, so the refs are all distinct
        // and never released while the WebView lives. Stacked across
        // orphaned streamers they overflow the JNI global ref table
        // (max 51200) → SIGABRT (observed: 50523 unique reflect.Method).
        // Killing the prior WebView releases its bridge + renderer refs.
        val prior = webView
        if (prior != null && prior !== wv) {
            runCatching { prior.removeJavascriptInterface("androidApi") }
            runCatching { prior.post { runCatching { prior.destroy() } } }
        }
        webView = wv
        resetJsPipeline()
        // COMPLETE fix for the JNI global-ref overflow crash: prefer a
        // WebMessageListener over addJavascriptInterface. The GIN Java-bridge
        // dispatches every @JavascriptInterface call through a freshly
        // reflected java.lang.reflect.Method pinned as a JNI global ref — on
        // the affected OEM WebView those leak per invocation, and a panic
        // call's high-frequency onWebRtcMessage stream overflowed the table
        // (max 51200) → SIGABRT. A WebMessageListener delivers via a native
        // postMessage channel with NO per-message reflection, so nothing
        // accumulates. Fall back to the bridge only where the feature is
        // unsupported. call.html calls androidApi.postMessage() when present,
        // else androidApi.onWebRtcMessage().
        runCatching { wv.removeJavascriptInterface("androidApi") }
        runCatching {
            androidx.webkit.WebViewCompat.removeWebMessageListener(wv, "androidApi")
        }
        if (androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.WEB_MESSAGE_LISTENER,
            )
        ) {
            androidx.webkit.WebViewCompat.addWebMessageListener(
                wv,
                "androidApi",
                setOf("*"),
                androidx.webkit.WebViewCompat.WebMessageListener { _, message, _, _, _ ->
                    val data = message.data ?: return@WebMessageListener
                    scope.launch { handleJsResponse(data) }
                },
            )
        } else {
            wv.addJavascriptInterface(Bridge(), "androidApi")
        }
    }

    /** CallScreen unmount — DOES NOT destroy the WebView. We keep the
     *  static instance alive across CallScreen recompositions so the
     *  next call reuses the same Chromium renderer process + audio
     *  device routing, matching upstream simplex-chat's pattern.
     *  Explicit destroy happens in destroyWebView() at call end. */
    fun detachWebView() {
        synchronized(jsLock) { pendingJsCommands.clear() }
    }

    /** Hard teardown — call ended, the user wants the WebView gone.
     *  Stops media tracks, closes the peer connection, then
     *  WebView.destroy(). The next call will create a fresh one. */
    fun destroyWebView() {
        val outgoing = webView
        webView = null
        resetJsPipeline()
        outgoing?.let { wv ->
            runCatching {
                wv.post {
                    wv.evaluateJavascript(
                        """
                        try {
                          if (typeof activeCall !== 'undefined' && activeCall) {
                            const pc = activeCall.connection;
                            pc.getSenders().forEach(s => { try { s.track && s.track.stop(); } catch(e) {} });
                            try { pc.close(); } catch(e) {}
                          }
                          if (typeof notConnectedCall !== 'undefined' && notConnectedCall && notConnectedCall.localStream) {
                            notConnectedCall.localStream.getTracks().forEach(t => { try { t.stop(); } catch(e) {} });
                          }
                        } catch(e) {}
                        """.trimIndent(),
                        null,
                    )
                    // Release the Java-bridge global refs deterministically
                    // BEFORE destroy — don't wait on chromium teardown.
                    runCatching { wv.removeJavascriptInterface("androidApi") }
                    runCatching { wv.destroy() }
                }
            }
        }
    }

    /**
     * Outgoing call initiation. Per upstream's CallView.desktop.kt
     * (line 38-44): we do NOT send `/_call invite` immediately. Instead
     * we wait for the JS pipeline to report its WCallResponse.Capabilities,
     * then dispatch the invitation with those capabilities. The
     * pendingOutgoingInvite flag carries that intent across the bridge.
     */
    @Volatile private var pendingOutgoingInvite: Boolean = false

    fun placeCall(peer: String, name: String, video: Boolean, stealth: Boolean = false) {
        // Evict our own mic holders before WebRTC tries to acquire it.
        // SOSHandler's chunked recorder + the sos live-stream
        // WebView are the two known in-process owners; if either is
        // still bound (e.g. a sos just resolved, a previous call
        // didn't tear down cleanly) getUserMedia returns
        // "couldn't start audio source" and the call hangs at
        // capabilities forever.
        runCatching { AegisApp.instance.sosHandler.releaseMicForCall() }
        // Same reason — any chat-bar VoiceRecorder still holding the
        // MIC AudioRecord would block getUserMedia with "couldn't
        // start audio source". Yank them.
        runCatching { app.aether.aegis.util.VoiceRecorder.cancelAll() }
        runCatching { acquireCallAudio() }
        val media = if (video) CallMediaType.Video else CallMediaType.Audio
        CallStore.set(
            ActiveCall(
                peerPubkey = peer,
                peerDisplayName = name,
                media = media,
                state = CallState.WaitCapabilities,
                outgoing = true,
                stealth = stealth,
            )
        )
        pendingOutgoingInvite = true
        // The CallScreen's WebView will load call.html → onPageLoaded
        // kicks the capabilities probe → handleJsResponse("capabilities")
        // sees pendingOutgoingInvite=true and sends /_call invite.
    }

    /**
     * STUN + TURN servers used for NAT traversal. We use SimpleX's own
     * infrastructure exclusively — never Google, who'd see "this IP is
     * opening a call right now" as a metadata leak. The endpoints,
     * username, and credential match the SimpleX upstream defaults
     * (call.js:68-73) and are SimpleX's publicly published values.
     *
     * Why TURN matters: ~30% of mobile carriers in the field run
     * symmetric NAT, where STUN-derived candidate pairs never line up
     * and direct connection fails. TURN relays around it. We also
     * unconditionally set "relay": true in the JS command — every call
     * is routed through TURN so the peer never sees our raw public
     * IP (Aegis-the-product is privacy-first; the bandwidth tradeoff
     * is acceptable).
     */
    private fun defaultIceServers(): String = """[
        {"urls":["stuns:stun.simplex.im:443"]},
        {"urls":["stun:stun.simplex.im:443"]},
        {"urls":["turns:turn.simplex.im:443?transport=tcp"],"username":"private2","credential":"Hxuq2QxUjnhj96Zq2r4HjqHRj"}
    ]"""

    /**
     * User tapped Accept on the incoming-call notification or banner.
     * Per upstream CallManager.answerIncomingCall (CallManager.kt:55-66):
     * we fire WCallCommand.Start at the JS engine with the call's media
     * type + iceServers. JS will then produce an offer and feed it back
     * through the bridge, which we forward via /_call offer.
     */
    fun acceptIncoming() {
        // Same mic-eviction guard as placeCall — see comment there.
        runCatching { AegisApp.instance.sosHandler.releaseMicForCall() }
        // Same reason — any chat-bar VoiceRecorder still holding the
        // MIC AudioRecord would block getUserMedia with "couldn't
        // start audio source". Yank them.
        runCatching { app.aether.aegis.util.VoiceRecorder.cancelAll() }
        runCatching { acquireCallAudio() }
        val active = CallStore.active.value ?: return
        if (!active.outgoing && active.state == CallState.InvitationSent) {
            CallStore.update { it.copy(state = CallState.InvitationAccepted) }
            val cmd = JSONObject().apply {
                put("type", "start")
                put("media", active.media.name.lowercase())
                if (!active.sharedKey.isNullOrBlank()) put("aesKey", active.sharedKey)
                put("iceServers", org.json.JSONArray(defaultIceServers()))
                put("relay", relayOnlyPref())
            }
            sendJsCommand(cmd)
            dismissIncomingNotification()
        }
    }

    private fun relayOnlyPref(): Boolean = runCatching {
        CallPrefs(AegisApp.instance).relayOnly
    }.getOrDefault(true)

    @Volatile private var audioFocusRequest: android.media.AudioFocusRequest? = null

    /**
     * Switch AudioManager into MODE_IN_COMMUNICATION + grab voice-comm
     * audio focus before WebView's getUserMedia runs. Chromium WebRTC
     * uses AudioSource.VOICE_COMMUNICATION which on Android 14+ refuses
     * to open AudioRecord while system mode is NORMAL, surfacing as
     * "Could not start audio source" with no other recorder holding the
     * mic. MODIFY_AUDIO_SETTINGS in the manifest is the required gate
     * for setMode to actually take effect — without it the assignment
     * silently no-ops (which is what the audioStateSnapshot kept
     * showing: mode=NORMAL even after we tried to flip it).
     *
     * Mirrors upstream simplex-chat's CallAudioDeviceManager start
     * sequence and the dropAudioManagerOverrides() teardown.
     */
    private fun acquireCallAudio() {
        val ctx = AegisApp.instance
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
            as? android.media.AudioManager ?: return
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = android.media.AudioFocusRequest.Builder(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        ).setAudioAttributes(attrs).build()
        runCatching { am.requestAudioFocus(req) }
        audioFocusRequest = req
        runCatching { am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION }
        // Refresh the route picker so the call screen can offer
        // earpiece / speaker / Bluetooth on this call's audio session.
        runCatching { CallAudioRouter.refresh() }
    }

    private fun releaseCallAudio() {
        val ctx = AegisApp.instance
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
            as? android.media.AudioManager ?: return
        runCatching { am.mode = android.media.AudioManager.MODE_NORMAL }
        audioFocusRequest?.let { req ->
            runCatching { am.abandonAudioFocusRequest(req) }
        }
        audioFocusRequest = null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            runCatching { am.clearCommunicationDevice() }
        }
        CallAudioRouter.clear()
    }

    /** Most recent JS-error message + audio-state snapshot, surfaced
     *  in CallScreen as a copyable dialog so a "couldn't start audio
     *  source" failure isn't swallowed by a toast cutoff. */
    private val _lastError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val lastError: kotlinx.coroutines.flow.StateFlow<String?> = _lastError

    fun clearLastError() { _lastError.value = null }

    /** One-shot diagnostic dump pasted into the lastError dialog so
     *  the user can read it off the CallScreen without pulling logcat.
     *  Audio mode + active AudioRecord sources + WebView build (an
     *  outdated OEM-shipped Chromium is a known cause of getUserMedia
     *  failures on Xiaomi/Oppo). */
    private fun audioStateSnapshot(): String {
        val ctx = AegisApp.instance
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
            as? android.media.AudioManager ?: return "AudioManager unavailable"
        val mode = when (am.mode) {
            android.media.AudioManager.MODE_NORMAL -> "NORMAL"
            android.media.AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            android.media.AudioManager.MODE_IN_CALL -> "IN_CALL"
            android.media.AudioManager.MODE_RINGTONE -> "RINGTONE"
            else -> "mode=${am.mode}"
        }
        val recActive = runCatching { am.activeRecordingConfigurations }.getOrNull()
        val recCount = recActive?.size ?: -1
        val recDesc = recActive?.joinToString(",") {
            // audioSource enum: 1=MIC, 6=VOICE_RECOGNITION,
            // 7=VOICE_COMMUNICATION, 9=UNPROCESSED, 10=VOICE_PERFORMANCE
            "src=${it.audioSource}"
        } ?: "?"
        val wv = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val wvLabel = if (wv != null) "${wv.packageName} ${wv.versionName}" else "unknown"
        val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (SDK ${android.os.Build.VERSION.SDK_INT})"
        return "mode=$mode active-records=$recCount [$recDesc]\n" +
            "webview=$wvLabel\n" +
            "device=$device"
    }

    fun rejectIncoming() {
        val active = CallStore.active.value ?: return
        scope.launch {
            transport()?.callReject(active.peerPubkey)
            CallStore.set(null)
            dismissIncomingNotification()
        }
    }

    fun hangUp() = end("user-hangup")

    /** Toggle mic ("Microphone") or camera ("Camera") on/off via the JS engine. */
    fun toggleMedia(source: String, enable: Boolean) {
        val cmd = JSONObject().apply {
            put("type", "media")
            put("source", source)
            put("enable", enable)
        }
        sendJsCommand(cmd)
    }

    /** Tracks the currently-selected camera so flipCamera actually
     *  toggles instead of always asking for the back camera. JS
     *  `replaceMedia` rebuilds the local stream with the new
     *  facingMode — there's no flip primitive in WebRTC. */
    @Volatile private var currentCamera: String = "user"

    /** Switch between front and back cameras. */
    fun flipCamera() {
        val next = if (currentCamera == "user") "environment" else "user"
        currentCamera = next
        sendJsCommand(JSONObject().put("type", "camera").put("camera", next))
    }

    private fun end(reason: String) {
        // Always release UI state first so the End button works even
        // when the JS pipeline is hung (preparing forever, perms
        // denied, etc.). Best-effort fire-and-forget the wire-level
        // /_call end and the JS engine — don't gate the close on
        // either succeeding.
        val active = CallStore.active.value
        Log.i(TAG, "ending call with ${active?.peerDisplayName ?: "?"}: $reason")
        // Drop a local journal row into the chat so the WhatsApp-style
        // history chip ("↗ Voice call · 3:45" / "↘ Missed video call")
        // appears inline. Local-only — peer logs their own perspective
        // when their side fires end() too. Stealth calls skip this:
        // a "1:23 video call" row appearing in the chat history would
        // give away the remote LIVE_CAM stream to whoever is reading
        // the device.
        if (active != null && !active.stealth) {
            val connectedAt = active.connectedAt
            val durationMs = connectedAt?.let { System.currentTimeMillis() - it } ?: 0L
            val logReason = when {
                connectedAt != null            -> "ended"
                active.outgoing                -> "no-answer"   // we rang, peer never picked up
                reason == "user-reject"        -> "declined"    // we tapped Reject
                else                            -> "missed"      // ringing dropped without action
            }
            scope.launch {
                runCatching {
                    AegisApp.instance.repository.recordCallLog(
                        peerKey = active.peerPubkey,
                        outgoing = active.outgoing,
                        video = active.media == CallMediaType.Video,
                        durationMs = durationMs,
                        connected = connectedAt != null,
                        reason = logReason,
                    )
                }
            }
        }
        CallStore.set(null)
        // A panel-hosted live cam/mic is just a call under the hood; clear the
        // "this peer's incoming call is a live stream" arming whenever that
        // call ENDS for ANY reason — target hung up, WebRTC dropped, never
        // connected — not only the operator's explicit Stop/Exit (the only
        // paths that cleared it before). A leaked flag made the NEXT normal
        // call from that peer auto-answer silently with no notification (user
        // report: "the other side connects without touching the phone").
        // stopLiveStream only clears when the key matches, so ending an
        // unrelated call can't wipe a genuinely-armed stream for another peer.
        active?.let {
            runCatching {
                app.aether.aegis.remote.RemoteAccessSession.stopLiveStream(it.peerPubkey)
            }
        }
        dismissIncomingNotification()
        // Force-mark the JS pipeline ready so any queued `end` command
        // doesn't just sit there forever. The page is going away
        // anyway when CallScreen detaches. No flush — don't replay stale
        // offers into an engine we're tearing down.
        forceJsReady()
        if (active != null) {
            scope.launch {
                runCatching { transport()?.callEnd(active.peerPubkey) }
            }
        }
        sendJsCommand(JSONObject().put("type", "end"))
        // WebView destruction is deferred to CallScreen's onDispose so
        // it only runs after AndroidView has detached the WebView from
        // its parent. Calling destroy() while the view is still being
        // drawn crashed the app on Android 16 (Chromium aborts on a
        // destroyed-then-touched WebView).
        // Restore AudioManager.mode + drop the voice-comm audio focus
        // we grabbed in placeCall/acceptIncoming so the device isn't
        // pinned in IN_COMMUNICATION when no call is active.
        runCatching { releaseCallAudio() }
    }

    // ===== JS bridge =====

    private class Bridge {
        @JavascriptInterface
        fun onWebRtcMessage(json: String) {
            // Called from the WebView thread.
            CallManager.scope.launch { CallManager.handleJsResponse(json) }
        }
    }

    private fun handleJsResponse(json: String) {
        val msg = runCatching { JSONObject(json) }.getOrNull() ?: return
        val resp = msg.optJSONObject("resp") ?: return
        val type = resp.optString("type")
        // Log.d(TAG, "JS → Kotlin: $type")
        val active = CallStore.active.value
        when (type) {
            "capabilities" -> {
                // Atomically flip ready + flush everything the SimpleX event
                // handlers queued before the WebView finished loading. (Was a
                // racy flag-set + non-locked drain that could orphan a queued
                // command → the call silently never connected.)
                markJsReadyAndFlush()
                // Outgoing-call leg: now that JS told us its capabilities,
                // construct the CallType and dispatch /_call invite. Per
                // upstream order (CallView.desktop.kt:38-44).
                if (pendingOutgoingInvite && active != null) {
                    pendingOutgoingInvite = false
                    val capabilities = resp.optJSONObject("capabilities")
                        ?: JSONObject().put("encryption", true)
                    val callType = JSONObject().apply {
                        put("media", active.media.name.lowercase())
                        put("capabilities", capabilities)
                    }.toString()
                    CallStore.update { it.copy(state = CallState.InvitationSent) }
                    scope.launch {
                        val ok = transport()?.callInvite(active.peerPubkey, callType) ?: false
                        if (!ok) {
                            Log.w(TAG, "callInvite failed for ${active.peerPubkey}")
                            end("invite-failed")
                        }
                    }
                }
            }
            "offer" -> {
                // JS WCallResponse.Offer = {offer:<sdp>, iceCandidates:<compressed>,
                // capabilities:{encryption}}. SimpleX expects
                // WebRTCCallOffer = {callType:{media, capabilities},
                //                    rtcSession:{rtcSession, rtcIceCandidates}}
                if (active == null) return
                CallStore.update { it.copy(state = CallState.OfferSent) }
                val capabilities = resp.optJSONObject("capabilities")
                    ?: JSONObject().put("encryption", true)
                val callType = JSONObject().apply {
                    put("media", active.media.name.lowercase())
                    put("capabilities", capabilities)
                }
                val rtcSession = JSONObject().apply {
                    put("rtcSession", resp.optString("offer"))
                    put("rtcIceCandidates", resp.optString("iceCandidates"))
                }
                val payload = JSONObject().apply {
                    put("callType", callType)
                    put("rtcSession", rtcSession)
                }
                scope.launch {
                    val ok = transport()?.callOffer(active.peerPubkey, payload.toString()) ?: false
                    if (!ok) Log.w(TAG, "callOffer dispatch failed")
                }
            }
            "answer" -> {
                // JS WCallResponse.Answer = {answer:<sdp>, iceCandidates:<compressed>}.
                // SimpleX expects WebRTCSession = {rtcSession, rtcIceCandidates}.
                if (active == null) return
                CallStore.update { it.copy(state = CallState.AnswerReceived) }
                val payload = JSONObject().apply {
                    put("rtcSession", resp.optString("answer"))
                    put("rtcIceCandidates", resp.optString("iceCandidates"))
                }
                scope.launch {
                    val ok = transport()?.callAnswer(active.peerPubkey, payload.toString()) ?: false
                    if (!ok) Log.w(TAG, "callAnswer dispatch failed")
                }
            }
            "ice" -> {
                // JS WCallResponse.Ice = {iceCandidates:<compressed>}.
                // SimpleX expects WebRTCExtraInfo = {rtcIceCandidates}.
                if (active == null) return
                val payload = JSONObject().put(
                    "rtcIceCandidates", resp.optString("iceCandidates"),
                )
                scope.launch {
                    transport()?.callExtra(active.peerPubkey, payload.toString())
                }
            }
            "connection" -> {
                // JS reports a WebRTC connection-state transition.
                // Tell the SimpleX core via /_call status so its
                // internal call state stays in sync — without this
                // the core can stop delivering follow-up callAnswer /
                // callExtraInfo events.
                val state = resp.optJSONObject("state")
                val connState = state?.optString("connectionState").orEmpty()
                if (connState == "connected") {
                    CallStore.update {
                    it.copy(
                        state = CallState.Connected,
                        connectedAt = it.connectedAt ?: System.currentTimeMillis(),
                    )
                }
                }
                // Map the raw WebRTC state to a subjective quality
                // bucket the UI consumes (call-screen bars indicator).
                // No actual stats yet — Tier 2 polish would parse
                // RTCStatsReport for packet-loss + RTT; for now the
                // state transition itself is the signal.
                CallStore.setQuality(
                    when (connState) {
                        "connected"    -> CallQuality.Good
                        "connecting"   -> CallQuality.Fair
                        "disconnected" -> CallQuality.Poor
                        "failed"       -> CallQuality.Failed
                        else            -> CallQuality.Unknown
                    }
                )
                val mapped = when (connState) {
                    "connected", "connecting", "disconnected", "failed" -> connState
                    else -> null
                }
                if (mapped != null && active != null) {
                    val peerKey = active.peerPubkey
                    scope.launch {
                        runCatching { transport()?.callStatus(peerKey, mapped) }
                    }
                }
            }
            "connected" -> {
                CallStore.update {
                    it.copy(
                        state = CallState.Connected,
                        connectedAt = it.connectedAt ?: System.currentTimeMillis(),
                    )
                }
            }
            "peerMedia" -> {
                // Peer toggled their mic / camera. Surface for UI
                // (mute indicator) — we don't have one yet, just
                // accept the event so it doesn't propagate as
                // unhandled noise.
            }
            "quality_stats" -> {
                // Live stats from the JS adaptive monitor (call.html).
                // Drives the connection-quality bars + a small RTT /
                // loss / bucket readout on the call screen.
                CallStore.setStats(
                    CallStatsSnapshot(
                        lossPct = resp.optDouble("loss_pct", 0.0).toFloat(),
                        rttMs   = resp.optInt("rtt_ms", 0),
                        bucket  = resp.optString("bucket").ifBlank { "AUTO_HIGH" },
                    )
                )
                // Real-stats refinement of the bars: packet loss > 5 %
                // OR RTT > 500 ms drops us to Poor regardless of the
                // raw connection state. The connection-state branch
                // still applies for transitions (Failed, disconnected).
                val lossPct = resp.optDouble("loss_pct", 0.0)
                val rttMs   = resp.optInt("rtt_ms", 0)
                val measured = when {
                    lossPct >= 10 || rttMs >= 800 -> CallQuality.Failed
                    lossPct >= 5  || rttMs >= 500 -> CallQuality.Poor
                    lossPct >= 1  || rttMs >= 150 -> CallQuality.Fair
                    else                            -> CallQuality.Good
                }
                CallStore.setQuality(measured)
            }
            "quality_adapt" -> {
                // Adaptive monitor changed buckets — no-op on the
                // Kotlin side, the JS already applied the constraints.
                // We log it via DiagLog so post-call debugging knows
                // how many rungs we travelled.
                app.aether.aegis.diag.DiagLog.i(
                    TAG,
                    "auto-resolution → ${resp.optString("bucket")} " +
                        "(${resp.optInt("w")}×${resp.optInt("h")} @ ${resp.optInt("fr")}fps)",
                )
            }
            "ended" -> {
                CallStore.set(null)
            }
            "error" -> {
                val message = resp.optString("message").ifBlank { "WebRTC engine failed" }
                val audioSnap = audioStateSnapshot()
                app.aether.aegis.diag.DiagLog.e(TAG, "JS reported error: $message · audio[$audioSnap]")
                // Unblock the pipeline so the End button works — without
                // this, the user is stuck on "Preparing…" with no way
                // out except force-quit. No flush: the engine just errored.
                forceJsReady()
                // Park the full text on a StateFlow so CallScreen can
                // render it in an AlertDialog with a Copy button. A
                // toast truncates the audio snapshot exactly at the
                // active-record list — the one piece that actually
                // names the culprit.
                _lastError.value = "$message\n\n$audioSnap"
                // Tear down the call state so CallScreen pops back to
                // chat instead of pulsing forever on "Preparing…".
                end("js-error")
            }
        }
    }

    // ===== SimpleX → Kotlin → JS =====

    /**
     * Called by SimpleXTransport.handleEvent when a `call*` event lands.
     * The [resp] is the inner event object (already unwrapped from the
     * result envelope).
     */
    fun onSimpleXCallEvent(type: String, resp: JSONObject) {
        // Log.d(TAG, "SimpleX → Kotlin: $type")
        // callInvitation is the one event that nests its payload —
        // upstream's CR.CallInvitation serialises as
        //   {"type":"callInvitation", "callInvitation":{"contact":...,
        //    "callType":..., "sharedKey":..., "callUUID":..., "callTs":...}}
        // The other call events (callOffer / callAnswer / callExtraInfo /
        // callEnded) put `contact` directly on the top-level resp.
        // Without this normalisation we'd read `contact` from the wrong
        // level on callInvitation, get null, and silently bail — which
        // was why peers never saw incoming calls.
        val payload = if (type == "callInvitation")
            (resp.optJSONObject("callInvitation") ?: resp)
        else resp
        val contact = payload.optJSONObject("contact")
        val peerName = contact?.optString("localDisplayName").orEmpty()
        val peerKey = if (peerName.isNotBlank()) "simplex:$peerName" else return

        when (type) {
            "callInvitation" -> {
                // RcvCallInvitation = {user, contact, callType, sharedKey?, callUUID, callTs}.
                // Read from `payload` (the unwrapped inner object) not the
                // outer `resp` — see the comment above the payload var.
                val callType = payload.optJSONObject("callType")
                val mediaStr = callType?.optString("media") ?: "audio"
                val media = if (mediaStr.equals("video", true))
                    CallMediaType.Video else CallMediaType.Audio
                val sharedKey = payload.optString("sharedKey", "").takeIf { it.isNotBlank() }
                CallStore.set(
                    ActiveCall(
                        peerPubkey = peerKey,
                        peerDisplayName = peerName,
                        media = media,
                        state = CallState.InvitationSent,
                        outgoing = false,
                        sharedKey = sharedKey,
                    )
                )
                // Panel-hosted live stream. If WE (the operator) just
                // started a live cam/mic for this peer, the incoming call
                // is the target streaming back. Auto-answer it and DON'T
                // post a notification — the device-control panel hosts the
                // WebView inline (no separate CallScreen). Without this
                // the live feed would bounce out to a full call surface,
                // which is the fragmentation we're killing.
                val panelLive = runCatching {
                    app.aether.aegis.remote.RemoteAccessSession.isLiveStreamPeer(peerKey)
                }.getOrDefault(false)
                // Auto-answer sos calls. If this peer has an active
                // sos alert on the receiver side, skip the normal
                // notification → tap-Accept dance and accept the call
                // immediately so the listening family member starts
                // hearing audio without doing anything.
                val sosActive = runCatching {
                    app.aether.aegis.sos.SOSAlertStore.isActive(peerKey)
                }.getOrDefault(false)
                if (panelLive) {
                    Log.i(TAG, "auto-accepting panel-hosted live stream from $peerName")
                    acceptIncoming()
                    // No notification: the device-control panel's
                    // CallVideoSurface picks up CallStore.active and hosts
                    // the WebView itself.
                } else if (sosActive) {
                    Log.i(TAG, "auto-accepting incoming call from SOS peer $peerName")
                    // The active-call slot must be flipped to "accepted"
                    // before the WebRTC engine processes the offer.
                    acceptIncoming()
                    // Bring up the call surface via a HIGH-priority
                    // full-screen-intent notification. Direct
                    // startActivity() from Application background is
                    // dropped silently on Android 10+ background-launch
                    // restrictions; full-screen-intent on a CATEGORY_CALL
                    // notification is the supported escape hatch and
                    // surfaces CallScreen as expected. Audio flows
                    // either way (acceptIncoming already did its job)
                    // but the UI affordance for mute/hang-up needs the
                    // notification path.
                    postIncomingNotification(
                        peerKey = peerKey,
                        peerName = peerName,
                        video = media == CallMediaType.Video,
                    )
                } else {
                    postIncomingNotification(peerKey, peerName, media == CallMediaType.Video)
                }
            }
            "callOffer" -> {
                // SimpleX CR.CallOffer = {user, contact, callType:{media, capabilities},
                // offer: WebRTCSession{rtcSession, rtcIceCandidates},
                // sharedKey?, askConfirmation}. JS WCallCommand.Offer
                // (WebRTC.kt:Offer) expects:
                //  {offer:<sdp>, iceCandidates:<compressed>, media, aesKey?, capabilities}.
                val offer = resp.optJSONObject("offer") ?: return
                val callType = resp.optJSONObject("callType")
                val media = callType?.optString("media")?.lowercase()
                    ?: CallStore.active.value?.media?.name?.lowercase()
                    ?: "video"
                val capabilities = callType?.optJSONObject("capabilities")
                    ?: JSONObject().put("encryption", true)
                val sharedKey = resp.optString("sharedKey", "").takeIf { it.isNotBlank() }
                // For outgoing calls the core generated this key at
                // /_call invite, embedded it in the CallInvitation
                // the peer received, and is now echoing it back so we
                // can pass it to our local WebRTC engine. Stash it on
                // ActiveCall so the call screen's "🔒 e2e" indicator
                // reflects the real state — without this update the
                // UI keeps reading the initial sharedKey=null from
                // placeCall and shows "🔓 unencrypted" even though
                // the media stream is encrypted with the core's key.
                CallStore.update {
                    it.copy(
                        state = CallState.OfferReceived,
                        sharedKey = sharedKey ?: it.sharedKey,
                    )
                }
                val cmd = JSONObject().apply {
                    put("type", "offer")
                    put("offer", offer.optString("rtcSession"))
                    put("iceCandidates", offer.optString("rtcIceCandidates"))
                    put("media", media)
                    if (sharedKey != null) put("aesKey", sharedKey)
                    put("capabilities", capabilities)
                    put("iceServers", org.json.JSONArray(defaultIceServers()))
                    put("relay", relayOnlyPref())
                }
                sendJsCommand(cmd)
            }
            "callAnswer" -> {
                // SimpleX CR.CallAnswer = {user, contact, answer: WebRTCSession}.
                // JS WCallCommand.Answer = {answer:<sdp>, iceCandidates:<compressed>}.
                val answer = resp.optJSONObject("answer") ?: return
                CallStore.update { it.copy(state = CallState.AnswerReceived) }
                val cmd = JSONObject().apply {
                    put("type", "answer")
                    put("answer", answer.optString("rtcSession"))
                    put("iceCandidates", answer.optString("rtcIceCandidates"))
                }
                sendJsCommand(cmd)
            }
            "callExtraInfo" -> {
                // SimpleX CR.CallExtraInfo = {user, contact, extraInfo: WebRTCExtraInfo{rtcIceCandidates}}.
                // JS WCallCommand.Ice = {iceCandidates}.
                val extraInfo = resp.optJSONObject("extraInfo") ?: return
                val cmd = JSONObject().apply {
                    put("type", "ice")
                    put("iceCandidates", extraInfo.optString("rtcIceCandidates"))
                }
                sendJsCommand(cmd)
            }
            "callEnded" -> {
                sendJsCommand(JSONObject().put("type", "end"))
                CallStore.set(null)
                dismissIncomingNotification()
            }
        }
    }

    private fun sendJsCommand(cmd: JSONObject) {
        // Atomically decide queue-vs-dispatch under the lock. Dispatch itself
        // happens OUTSIDE the lock (it only posts to the WebView looper).
        val dispatchNow = synchronized(jsLock) {
            if (!jsReady) {
                pendingJsCommands.add(cmd)
                false
            } else true
        }
        if (dispatchNow) dispatchJs(cmd)
    }

    /** Wrap [cmd] in call.js's `{corrId, command}` envelope and evaluate it on
     *  the WebView looper. Must only run once the JS engine is ready. */
    private fun dispatchJs(cmd: JSONObject) {
        // call.js expects `{corrId, command:{...}}` (see processCommand
        // line 290 in call.js: `const { corrId, command } = body`).
        // Passing the raw command makes JS destructure `command` as
        // undefined and throw on `command.type`, silently breaking the
        // whole pipeline — exactly the "calls go nowhere" symptom.
        val envelope = JSONObject()
            .put("corrId", nextCorrId())
            .put("command", cmd)
        val js = "processCommand(${envelope})"
        // Capture the WebView ref once. The old `webView?.post { webView?.
        // evaluateJavascript(…) }` re-read the volatile field inside the
        // posted runnable; if destroyWebView() nulled it between post and
        // execution the JS command was silently dropped — the "end" command
        // in particular, leaving the peer's WebRTC connection dangling.
        val wv = webView ?: return
        wv.post { wv.evaluateJavascript(js, null) }
    }

    /** Flip the pipeline to ready and flush everything queued, atomically.
     *  Draining inside the lock (into a local list) then dispatching outside
     *  it means no command added concurrently is lost and the deque is never
     *  mutated while iterated. */
    private fun markJsReadyAndFlush() {
        val drained = synchronized(jsLock) {
            jsReady = true
            val copy = pendingJsCommands.toList()
            pendingJsCommands.clear()
            copy
        }
        drained.forEach { dispatchJs(it) }
    }

    /** Force-ready WITHOUT flushing — used on JS error / call-end where we
     *  want the End command to get through but must NOT replay stale queued
     *  offers into a dying engine. */
    private fun forceJsReady() {
        synchronized(jsLock) { jsReady = true }
    }

    private fun resetJsPipeline() {
        synchronized(jsLock) {
            jsReady = false
            pendingJsCommands.clear()
        }
    }

    private var corrIdCounter = 0
    private fun nextCorrId(): Int = ++corrIdCounter

    /** Called from CallScreen after the WebView reports the page is fully loaded. */
    fun onPageLoaded() {
        // Split the bridge injection from the
        // capabilities probe into two distinct evaluateJavascript
        // calls. Bundling them was racy — if call.js's
        // sendMessageToNative fired from inside processCommand BEFORE
        // V8 finished assigning window.sendMessageToNative, the
        // response landed on the default (console.log) and the
        // capabilities probe silently dropped. Two calls with the
        // bridge waited-on via the result callback guarantees the
        // override is live before processCommand runs.
        val media = CallStore.active.value?.media?.name?.lowercase() ?: "audio"
        // Route call.js's high-frequency WebRTC messages through the
        // WebMessageListener channel (androidApi.postMessage) when present —
        // NO per-call JNI reflection, so it can't overflow the global ref
        // table the way the legacy onWebRtcMessage bridge did under a panic
        // call's message storm (the SIGABRT). Fall back to the bridge only
        // where the listener feature is unsupported.
        val bridgeJs = """
            (function() {
                window.sendMessageToNative = function(msg) {
                    var s = JSON.stringify(msg);
                    if (window.androidApi && window.androidApi.postMessage) {
                        window.androidApi.postMessage(s);
                    } else if (window.androidApi && window.androidApi.onWebRtcMessage) {
                        window.androidApi.onWebRtcMessage(s);
                    }
                };
                return 'ok';
            })()
        """.trimIndent()
        val probeJs = """
            if (typeof processCommand === 'function') {
                processCommand({corrId: 0, command: {type: 'capabilities', media: '$media'}});
            }
        """.trimIndent()
        webView?.post {
            webView?.evaluateJavascript(bridgeJs) { _ ->
                // Result callback runs ONLY after V8 finished the
                // assignment. Post the probe back to the main thread
                // so it's on the WebView's looper, not the JS one.
                webView?.post { webView?.evaluateJavascript(probeJs, null) }
            }
        }
    }

    // ===== Incoming notification =====

    private fun postIncomingNotification(peerKey: String, peerName: String, video: Boolean) {
        val ctx = AegisApp.instance
        val openCall = Intent(ctx, app.aether.aegis.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("incoming_call_peer", peerKey)
            putExtra("incoming_call_name", peerName)
            putExtra("incoming_call_video", video)
        }
        val openPI = PendingIntent.getActivity(
            ctx, INCOMING_NOTIF_ID, openCall,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val acceptPI = PendingIntent.getBroadcast(
            ctx, INCOMING_NOTIF_ID + 1,
            Intent(ACTION_ACCEPT).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val rejectPI = PendingIntent.getBroadcast(
            ctx, INCOMING_NOTIF_ID + 2,
            Intent(ACTION_REJECT).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // CallStyle notification. Gives the
        // lock-screen the "ringing pill" with Answer + Decline pill
        // buttons that WhatsApp / Telegram show, instead of a generic
        // expanded heads-up. Uses NotificationCompat.CallStyle from
        // androidx.core 1.10+; pre-31 OS falls back to the action-row
        // model automatically.
        val caller = androidx.core.app.Person.Builder()
            .setName(peerName)
            .setKey(peerKey)
            .setImportant(true)
            .build()
        val builder = NotificationCompat.Builder(ctx, CHANNEL_CALL)
            .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_shield)
            .setColor(0xFF00BCD4.toInt())  // brand cyan
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(openPI, true)
            .setContentIntent(openPI)
            .setStyle(
                androidx.core.app.NotificationCompat.CallStyle.forIncomingCall(
                    caller, rejectPI, acceptPI,
                ).setIsVideo(video),
            )
        NotificationManagerCompat.from(ctx).notify(INCOMING_NOTIF_ID, builder.build())
    }

    fun dismissIncomingNotification() {
        runCatching {
            NotificationManagerCompat.from(AegisApp.instance).cancel(INCOMING_NOTIF_ID)
        }
    }

    // ===== Helpers =====

    private fun transport(): SimpleXTransport? =
        AegisApp.instance.transports.filterIsInstance<SimpleXTransport>().firstOrNull()

    const val ACTION_ACCEPT = "app.aether.aegis.action.CALL_ACCEPT"
    const val ACTION_REJECT = "app.aether.aegis.action.CALL_REJECT"
}

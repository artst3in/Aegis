package app.aether.aegis.call

/**
 * Minimal call data types extracted from simplex-chat's WebRTC.kt.
 *
 * Intentionally narrow scope: enough state to route SimpleX `/_call`
 * commands and run the WebView-hosted WebRTC pipeline. The full
 * SimpleX type surface (Profile, Contact, MR.strings, etc.) is NOT
 * imported here — Aegis identifies peers by pubkey and we let the
 * SimpleX core handle the SDP/ICE plumbing under its `apiId` model.
 */
enum class CallMediaType { Audio, Video }

enum class CallState {
    WaitCapabilities,
    InvitationSent,
    InvitationAccepted,
    OfferSent,
    OfferReceived,
    AnswerReceived,
    Negotiated,
    Connected,
    Ended;
}

/** Subjective quality bucket derived from WebRTC connection state +
 *  RTCStatsReport (packet loss + RTT). Drives the bars indicator
 *  next to the peer name on the call screen. */
enum class CallQuality { Unknown, Failed, Poor, Fair, Good }

/** Live signal-quality snapshot. Populated by the JS-side adaptive
 *  resolution monitor (call.html) every 5 s while the call is
 *  connected. lossPct + rttMs come from getStats(); bucket is the
 *  current resolution ladder rung (AUTO_HIGH..AUTO_VERY_LOW). */
data class CallStatsSnapshot(
    val lossPct: Float,
    val rttMs: Int,
    val bucket: String,
)

data class CallType(val media: CallMediaType, val capabilities: CallCapabilities)
data class CallCapabilities(val encryption: Boolean = true)

data class ActiveCall(
    val peerPubkey: String,
    val peerDisplayName: String,
    val media: CallMediaType,
    val state: CallState,
    val outgoing: Boolean,
    val startedAt: Long = System.currentTimeMillis(),
    /** Wall-clock instant the WebRTC connection actually established.
     *  Null while ringing / negotiating. Used by CallManager.end() to
     *  log "missed" vs "ended" + the connected-time duration into the
     *  chat history. */
    val connectedAt: Long? = null,
    val sharedKey: String? = null,
    /** True when the call must NOT be reflected in any on-device UI:
     *  no CallIsland banner, no chat-log row, no in-app navigation.
     *  Set by initiators that need the WebRTC pipeline to run silently
     *  for stolen-phone scenarios — remote LIVE_CAM stream from a
     *  target the user no longer holds. Whoever is in physical
     *  possession of the device should see no indication that an
     *  outbound call is active. */
    val stealth: Boolean = false,
)

/**
 * Process-wide state of the current call (if any). Observed by the
 * UI; mutated by CallManager + SimpleXTransport's call-event parsing.
 */
object CallStore {
    private val _active = kotlinx.coroutines.flow.MutableStateFlow<ActiveCall?>(null)
    val active: kotlinx.coroutines.flow.StateFlow<ActiveCall?> = _active

    /** Live WebRTC connection quality. Updated from CallManager on
     *  every "connection" event from the JS engine. Reset to Unknown
     *  when the call ends. */
    private val _quality = kotlinx.coroutines.flow.MutableStateFlow(CallQuality.Unknown)
    val quality: kotlinx.coroutines.flow.StateFlow<CallQuality> = _quality

    /** Whether MainActivity is currently in Picture-in-Picture mode.
     *  Driven by [Activity.onPictureInPictureModeChanged]; observed
     *  by CallScreen to hide the TopAppBar + control bar so only the
     *  remote video shows in the PiP window. */
    private val _inPip = kotlinx.coroutines.flow.MutableStateFlow(false)
    val inPip: kotlinx.coroutines.flow.StateFlow<Boolean> = _inPip
    fun setInPip(value: Boolean) { _inPip.value = value }

    /** Most recent live stats from the JS adaptive monitor. Null
     *  before the first 5-second tick lands or after the call ends. */
    private val _stats = kotlinx.coroutines.flow.MutableStateFlow<CallStatsSnapshot?>(null)
    val stats: kotlinx.coroutines.flow.StateFlow<CallStatsSnapshot?> = _stats
    fun setStats(s: CallStatsSnapshot?) { _stats.value = s }

    fun set(call: ActiveCall?) {
        _active.value = call
        if (call == null) {
            _quality.value = CallQuality.Unknown
            _stats.value = null
        }
    }
    fun update(transform: (ActiveCall) -> ActiveCall) {
        _active.value?.let { _active.value = transform(it) }
    }
    fun setQuality(q: CallQuality) { _quality.value = q }
}

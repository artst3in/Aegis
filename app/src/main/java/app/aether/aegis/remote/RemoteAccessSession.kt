package app.aether.aegis.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sender-side state for the remote-access surface. In-memory only —
 * sessions die when the process is killed, which is exactly what we
 * want (borrow-a-friend's-phone scenario: closing the app must drop
 * the session so the friend can't keep poking at your phone after
 * you hand it back).
 *
 * Two pieces of state:
 *
 *   sessions   peer pubkey → ActiveSession{sid, expiry, firstLocate}
 *              Driven by AUTH_OK responses. Subsequent locate /
 *              siren / wipe packets pull the sid from here. Expires
 *              after [TTL_MS] of idle (matches gate-side TTL).
 *
 *   revokedBy  set of peer pubkeys who have told us "you're revoked".
 *              When present, the Contact Detail screen grays out the
 *              "Remote access" button. Survives within the process
 *              only; on app restart the sender tries again, target
 *              re-rejects, sender re-learns.
 *
 * State is exposed as StateFlow so the Compose UI re-renders the
 * moment an AUTH_OK / REVOKED packet lands.
 */
object RemoteAccessSession {

    const val TTL_MS = 5L * 60_000L

    data class ActiveSession(
        val sid: String,
        val expiry: Long,
        val locateLat: Double?,
        val locateLng: Double?,
        val locateTs: Long?,
        /** Base64 JPEG of the target's most-recent front-lens mugshot.
         *  Piggy-backed on AUTH_OK / LOCATE_RESULT / WATCH_TICK so the
         *  sender's UI shows a live "who's holding it" view. Null when
         *  the target had no mugshot on disk + camera capture failed. */
        val mugshotB64: String? = null,
        /** Rear-lens companion to [mugshotB64] — only watch-mode ticks
         *  populate this, since the one-shot LOCATE path only grabs
         *  the front-lens result from the existing wrong-PIN pipeline. */
        val rearMugshotB64: String? = null,
        /** Last base64 AAC/M4A blob returned from a LISTEN command.
         *  Sender UI exposes a play button while non-null. */
        val audioB64: String? = null,
        val audioTs: Long? = null,
        /** Whether the target's lockDevice succeeded on the most-recent
         *  LOCATE/WATCH_TICK. False = AegisAdminReceiver isn't enrolled
         *  → sender's UI surfaces "Lock did not fire — ask owner to
         *  enable Device Admin". Null = pre-this-version target. */
        val lockOk: Boolean? = null,
        /** Target's battery percentage from the most-recent AUTH_OK /
         *  LOCATE_RESULT / WATCH_TICK / PONG. Null = pre-this-version
         *  target (the wire-side field was added in 2026.06). */
        val batteryPct: Int? = null,
        val charging: Boolean? = null,
        /** Last [KIND_PONG] timestamp from the target — drives the
         *  "last seen" age string in the header strip. */
        val lastPongTs: Long? = null,
    )

    private val _sessions = MutableStateFlow<Map<String, ActiveSession>>(emptyMap())
    val sessions: StateFlow<Map<String, ActiveSession>> = _sessions.asStateFlow()

    private val _revokedBy = MutableStateFlow<Set<String>>(emptySet())
    val revokedBy: StateFlow<Set<String>> = _revokedBy.asStateFlow()

    /** Last typed error received from any peer's target-side handler.
     *  Drives the toast/banner that surfaces 'needs_location_permission',
     *  'notifications_disabled', etc. instead of letting commands
     *  fail silently. Read once by the UI then cleared via [consumeError]. */
    data class LastError(val peerKey: String, val forCmd: String, val msg: String, val ts: Long)

    private val _lastError = MutableStateFlow<LastError?>(null)
    val lastError: StateFlow<LastError?> = _lastError.asStateFlow()

    fun recordError(peerKey: String, forCmd: String, msg: String) {
        _lastError.value = LastError(peerKey, forCmd, msg, System.currentTimeMillis())
    }

    fun consumeError() {
        _lastError.value = null
    }

    /**
     * The peer whose LIVE WebRTC stream (camera or mic) the operator is
     * currently viewing INSIDE the device-control panel. Set when the
     * operator starts a live cam/mic from the panel; the incoming stealth
     * call the target places back is then auto-answered and rendered
     * inline in the panel instead of bouncing to a separate CallScreen
     * (the whole point — everything about a peer stays on one console).
     * Null = no panel-hosted live stream in flight.
     */
    private val _liveStreamPeer = MutableStateFlow<String?>(null)
    val liveStreamPeer: StateFlow<String?> = _liveStreamPeer.asStateFlow()

    /** Operator started a live cam/mic for [peerKey] — arm the panel to
     *  host the incoming stream and tell CallManager to auto-answer it. */
    fun startLiveStream(peerKey: String) { _liveStreamPeer.value = peerKey }

    /** Operator stopped the live stream (or left the panel). Only clears
     *  if [peerKey] is the one currently armed, so a stale stop for a
     *  different peer can't wipe an active stream. */
    fun stopLiveStream(peerKey: String) {
        if (_liveStreamPeer.value == peerKey) _liveStreamPeer.value = null
    }

    /** True when [peerKey]'s incoming call should be treated as a
     *  panel-hosted live stream (auto-answer, no separate call UI). */
    fun isLiveStreamPeer(peerKey: String): Boolean = _liveStreamPeer.value == peerKey

    fun isActive(peerKey: String): Boolean {
        val s = _sessions.value[peerKey] ?: return false
        if (s.expiry < System.currentTimeMillis()) {
            close(peerKey)
            return false
        }
        return true
    }

    fun isRevokedBy(peerKey: String): Boolean = peerKey in _revokedBy.value

    fun open(
        peerKey: String,
        sid: String,
        lat: Double?,
        lng: Double?,
        ts: Long?,
        mugshotB64: String? = null,
        batteryPct: Int? = null,
        charging: Boolean? = null,
    ) {
        val now = System.currentTimeMillis()
        _sessions.value = _sessions.value + (peerKey to ActiveSession(
            sid = sid,
            expiry = now + TTL_MS,
            locateLat = lat,
            locateLng = lng,
            locateTs = ts,
            mugshotB64 = mugshotB64,
            batteryPct = batteryPct,
            charging = charging,
        ))
        // Auth success implies the peer revoked nothing — clear any
        // stale flag from a previous coercion-sabotage round.
        if (peerKey in _revokedBy.value) {
            _revokedBy.value = _revokedBy.value - peerKey
        }
    }

    fun touch(peerKey: String) {
        val cur = _sessions.value[peerKey] ?: return
        _sessions.value = _sessions.value + (peerKey to cur.copy(
            expiry = System.currentTimeMillis() + TTL_MS,
        ))
    }

    fun updateLocate(
        peerKey: String,
        lat: Double?,
        lng: Double?,
        ts: Long?,
        mugshotB64: String? = null,
        rearMugshotB64: String? = null,
        lockOk: Boolean? = null,
        batteryPct: Int? = null,
        charging: Boolean? = null,
    ) {
        val cur = _sessions.value[peerKey] ?: return
        _sessions.value = _sessions.value + (peerKey to cur.copy(
            expiry = System.currentTimeMillis() + TTL_MS,
            locateLat = lat ?: cur.locateLat,
            locateLng = lng ?: cur.locateLng,
            locateTs = ts ?: cur.locateTs,
            mugshotB64 = mugshotB64 ?: cur.mugshotB64,
            rearMugshotB64 = rearMugshotB64 ?: cur.rearMugshotB64,
            lockOk = lockOk ?: cur.lockOk,
            batteryPct = batteryPct ?: cur.batteryPct,
            charging = charging ?: cur.charging,
        ))
    }

    fun updatePong(peerKey: String, batteryPct: Int?, charging: Boolean?) {
        val cur = _sessions.value[peerKey] ?: return
        _sessions.value = _sessions.value + (peerKey to cur.copy(
            expiry = System.currentTimeMillis() + TTL_MS,
            batteryPct = batteryPct ?: cur.batteryPct,
            charging = charging ?: cur.charging,
            lastPongTs = System.currentTimeMillis(),
        ))
    }

    fun updateAudio(peerKey: String, audioB64: String, ts: Long?) {
        val cur = _sessions.value[peerKey] ?: return
        _sessions.value = _sessions.value + (peerKey to cur.copy(
            expiry = System.currentTimeMillis() + TTL_MS,
            audioB64 = audioB64,
            audioTs = ts ?: System.currentTimeMillis(),
        ))
    }

    fun close(peerKey: String) {
        val gone = _sessions.value[peerKey] ?: return
        _sessions.value = _sessions.value - peerKey
        // Tell the target to stop its WatchMode + drop the gate
        // session so it doesn't keep streaming for the rest of the
        // TTL. Best-effort — if the message can't be sent (peer
        // offline) the target's session will TTL-expire on its own.
        runCatching {
            app.aether.aegis.AegisApp.instance.protocolManager.sendMessage(
                to = peerKey,
                content = RemoteAccessProtocol.encode(
                    RemoteAccessProtocol.Packet(
                        kind = RemoteAccessProtocol.KIND_EXIT,
                        sid = gone.sid,
                    ),
                ),
                type = app.aether.aegis.core.MessageType.STATUS,
            )
        }
    }

    fun markRevokedBy(peerKey: String) {
        _revokedBy.value = _revokedBy.value + peerKey
        close(peerKey)
    }

    /** Inverse of [markRevokedBy] — fires when the target broadcasts
     *  KIND_UNREVOKED (i.e. toggled their "block this peer" off) so
     *  the requester's sticky UI state recovers without requiring a
     *  manual AUTH retry. Idempotent — no-op when the entry isn't
     *  present, so a duplicate UNREVOKED packet doesn't error. */
    fun clearRevokedBy(peerKey: String) {
        if (peerKey in _revokedBy.value) {
            _revokedBy.value = _revokedBy.value - peerKey
        }
    }

    fun sidFor(peerKey: String): String? = _sessions.value[peerKey]?.sid
}

package app.aether.aegis.remote

import org.json.JSONObject

/**
 * Wire protocol for the auth-gated remote-access surface.
 *
 * Replaces the old `[aegis:cmd]<CMD>` envelope which let any paired
 * peer fire WIPE / LOCK / SIREN with zero authentication — that was
 * a CRITICAL bug. The new envelope requires the
 * SENDER to know the TARGET's PIN. Successful auth opens a 5-min
 * session keyed by an opaque server-issued [sid]; subsequent commands
 * carry the sid instead of re-prompting for PIN.
 *
 * Wire shape (always JSON, prefixed with the [aegis:remote] tag the
 * SimpleX transport routes on):
 *
 *   sender → target:
 *     {"v":1, "kind":"auth",   "pin":"1234"}              first contact
 *     {"v":1, "kind":"locate", "sid":"..."}               re-fire LOCATE
 *     {"v":1, "kind":"siren",  "sid":"..."}               start siren
 *     {"v":1, "kind":"wipe",   "sid":"..."}               factory reset
 *
 *   target → sender:
 *     {"v":1, "kind":"auth_ok",     "sid":"...",
 *                                   "lat":..., "lng":..., "ts":...}
 *     {"v":1, "kind":"auth_denied"}
 *     {"v":1, "kind":"locate_result","lat":..., "lng":..., "ts":...}
 *     {"v":1, "kind":"ok",          "for":"siren"|"wipe"}
 *     {"v":1, "kind":"err",         "for":"...", "msg":"reason"}
 *     {"v":1, "kind":"revoked"}      target revoked sender — drop session
 *
 * The auth response carries the very first LOCATE in the same round-
 * trip: success on remote-access AUTO-FIRES locate.
 * LOCATE merges lock + mugshot + GPS coords.
 */
object RemoteAccessProtocol {

    const val PREFIX = "[aegis:remote]"
    const val WIPE_BROADCAST_PREFIX = "[aegis:wiped]"

    const val KIND_AUTH         = "auth"
    const val KIND_LOCATE       = "locate"
    /** Sender hangs up. Target closes the session immediately +
     *  stops [app.aether.aegis.remote.RemoteWatchMode]. Without this the target
     *  keeps ticking location + camera every 25 s for the rest of
     *  the session TTL (up to 5 min) after the sender's UI exited. */
    const val KIND_EXIT         = "exit"
    const val KIND_SIREN        = "siren"
    /** Stop a remote-fired siren. Target side calls RemoteCommandHandler
     *  .stopSiren() — the same path the on-device biometric-gated stop
     *  uses. Necessary because the sender otherwise has no way to
     *  silence what they started (user reported having to reboot). */
    const val KIND_SIREN_OFF    = "siren_off"
    const val KIND_WIPE         = "wipe"
    /** Trigger the TARGET's SOS on the owner's behalf, from the remote
     *  control panel (SPEC remote-duress: "remote SOS button"). Use case:
     *  you locate a family member, see something is wrong, and raise their
     *  SOS for them. Explicit and intentional — NOT a duress mechanism. Fires
     *  a normal (visible) SOS on the target so the owner sees it and can stop
     *  it, and their emergency contacts are alerted. Session-gated like every
     *  other command. */
    const val KIND_REMOTE_SOS   = "remote_sos"
    /** Force the target to download + silent-install the latest
     *  Aegis APK. Useful for keeping family devices up to date
     *  without needing physical access. Target-side gated on
     *  Device Owner (silent install requires it); falls back to
     *  the prompt path if DO isn't provisioned. */
    const val KIND_UPDATE       = "update"
    /** Capture a short mic snippet (default 10 s, configurable via
     *  [Packet.seconds]) and return it base64-encoded in
     *  [KIND_LISTEN_RESULT]. Stolen-phone scenario: hear what's
     *  around the device without alerting whoever has it. */
    const val KIND_LISTEN       = "listen"
    /** Push a sticky high-priority lockscreen message — "If found,
     *  please contact X" etc. Carried in [Packet.msg]. */
    const val KIND_DISPLAY      = "display"
    /** Open a one-way WebRTC video stream from target → sender. Target
     *  spins up a hidden WebView (no on-device UI), captures from the
     *  requested lens, and places an outgoing WebRTC call to sender
     *  via [app.aether.aegis.call.CallManager]. Sender's regular incoming-call
     *  pipeline fires; tapping Answer opens [app.aether.aegis.call.CallScreen]
     *  with target's live video. Lens carried in [Packet.lens]
     *  ("front" or "rear"); defaults to rear (stolen-phone angle:
     *  see what the holder is pointing at). */
    const val KIND_LIVE_CAM_START = "live_cam_start"
    /** Mid-stream lens switch for an already-running LIVE_CAM session.
     *  Toggle semantics — target side flips between user/environment
     *  via [app.aether.aegis.call.CallManager.flipCamera]. No-op if no
     *  stream is currently attached for the requesting sender. */
    const val KIND_LIVE_CAM_FLIP  = "live_cam_flip"
    /** Tear down the stream initiated by [KIND_LIVE_CAM_START]. Sender
     *  fires when the user ends the call from their side; target also
     *  receives KIND_EXIT on session close, which has the same effect. */
    const val KIND_LIVE_CAM_STOP  = "live_cam_stop"
    /** Audio-only equivalent of LIVE_CAM — stealth WebRTC call from
     *  target's mic back to sender. No video track. Heavier than
     *  one-shot LISTEN but live and continuous. */
    const val KIND_LIVE_MIC_START = "live_mic_start"
    /** Tear down LIVE_MIC. Tied to session close + sender's hang-up
     *  via the same observer pattern as LIVE_CAM. */
    const val KIND_LIVE_MIC_STOP  = "live_mic_stop"
    /** Light "find my phone" attention tone — softer than sos SIREN,
     *  no DnD bypass, stops after [Packet.seconds] (default 30 s) or
     *  on KIND_RING_OFF. For the "I lost my phone in the couch" case
     *  where the user wants the device to chirp without sounding like
     *  an emergency. */
    const val KIND_RING           = "ring"
    /** Stop a remote-fired RING early. Sender-side button, also fires
     *  implicitly on session close (handleExit). */
    const val KIND_RING_OFF       = "ring_off"
    /** One-shot single-JPEG snapshot from the chosen lens. Reuses the
     *  LOCATE_RESULT envelope on return (mugshotB64 carries the JPEG)
     *  so the existing FramesRow renderer picks it up automatically. */
    const val KIND_SNAPSHOT       = "snapshot"
    /** Cheap liveness probe — sender → target. Target replies with
     *  [KIND_PONG] carrying battery + charging state. Useful for the
     *  field-test workflow "is this thing still alive?" without
     *  spinning up GPS or the camera pipeline. */
    const val KIND_PING           = "ping"
    /** Reply to [KIND_PING]. Battery + charging only — no GPS, no
     *  mugshot, no permission cost. */
    const val KIND_PONG           = "pong"
    /** Sender → target. Stops the periodic [RemoteWatchMode] tick so
     *  the target stops burning battery on every-25-seconds dual-lens
     *  capture + GPS fix once the sender has confirmed location.
     *  Session stays open — explicit LOCATE / SNAPSHOT still work. */
    const val KIND_WATCH_PAUSE    = "watch_pause"
    /** Sender → target. Re-arms [RemoteWatchMode]. */
    const val KIND_WATCH_RESUME   = "watch_resume"
    /** Sentinel-mode cascade event. SENDER side here is the device
     *  whose sentinel tripped; RECEIVER side is whichever contact is
     *  on the sentinel notify-list. Always-silent on the receiving
     *  end — surfaces as a notification + an entry in the inbound
     *  sentinel-log on the recipient's UI. Payload:
     *    - stage:       SentinelStage.label string; prefixed with
     *                   "[DRILL] " for drill-mode events so the
     *                   recipient can surface the "tap to confirm"
     *                   affordance and skip real-alert escalation
     *    - ts:          epoch ms of the event
     *    - batteryPct:  sender's battery at time of event (telemetry)
     *    - charging:    sender's charging state
     *    - audioB64:    optional base64-encoded 3D-model accel/gyro
     *                   recording blob (reused field — same wire as
     *                   LISTEN_RESULT, since both are opaque sensor
     *                   blobs). Cap ~600 KB; 30 s @ 100 Hz fits. */
    const val KIND_SENTINEL_EVENT = "sentinel_event"
    /** Receiver → sender confirmation that a [KIND_SENTINEL_EVENT]
     *  tagged as a drill was received and acknowledged. Sender uses
     *  this to track drill completion across the notify-list. */
    const val KIND_SENTINEL_DRILL_ACK = "sentinel_drill_ack"
    const val KIND_AUTH_OK      = "auth_ok"
    const val KIND_AUTH_DENIED  = "auth_denied"
    const val KIND_LOCATE_RESULT = "locate_result"
    /** Periodic location + mugshot tick from the target's
     *  [app.aether.aegis.remote.RemoteWatchMode]. Wire-identical to
     *  [KIND_LOCATE_RESULT]; separate kind so the sender's UI can
     *  distinguish a fresh push from a response to an explicit
     *  Locate tap (and avoid resetting the "Locating now…" spinner
     *  on every passive tick). */
    const val KIND_WATCH_TICK   = "watch_tick"
    /** Reply to [KIND_LISTEN] carrying the captured audio. */
    const val KIND_LISTEN_RESULT = "listen_result"
    const val KIND_OK           = "ok"
    const val KIND_ERR          = "err"
    const val KIND_REVOKED      = "revoked"
    /** Target → operator. Sent when a DURESS PIN is supplied at a remote
     *  prompt for THIS device: the operator (the coerced party) is told to
     *  raise their OWN silent SOS. Honoured by the operator ONLY when they
     *  have an active session with the sender (so an unsolicited frame can't
     *  trip someone's SOS). Carries no data — its presence is the signal. */
    const val KIND_DURESS_SOS   = "duress_sos"
    /** Target → requester. Symmetric with [KIND_REVOKED]: tells the
     *  requester their cached "you've been revoked" state should
     *  clear because the target just toggled the manual block off
     *  (or hit the auto-revoke cool-off path). Without this the
     *  requester's UI stayed sticky on "Revoked by <peer>" until they
     *  manually retried AUTH — user-reported "revoke is permanent"
     *  on 2026.05.638. */
    const val KIND_UNREVOKED    = "unrevoked"

    /** Parsed packet — only the fields each [kind] needs are populated.
     *
     *  [mugshotB64] piggy-backs the most-recent wrong-PIN mugshot JPEG
     *  on AUTH_OK + LOCATE_RESULT ("mugshot in authenticated
     *  remote LOCATE return"). We deliberately do NOT take a fresh
     *  snapshot from the background — instead we attach the latest
     *  file from filesDir/mugshots/, which is what the owner actually
     *  wants when remote-LOCATEing a stolen phone. */
    data class Packet(
        val kind: String,
        val pin: String? = null,
        val sid: String? = null,
        val lat: Double? = null,
        val lng: Double? = null,
        val ts: Long? = null,
        val forCmd: String? = null,
        val msg: String? = null,
        val mugshotB64: String? = null,
        /** Optional second mugshot (rear lens) so watch-mode ticks
         *  can deliver both faces of the device in one packet. Null
         *  on AUTH_OK / one-shot LOCATE — only watch ticks populate. */
        val rearMugshotB64: String? = null,
        /** Audio capture seconds for [KIND_LISTEN]. */
        val seconds: Int? = null,
        /** Base64 AAC/M4A bytes for [KIND_LISTEN_RESULT]. */
        val audioB64: String? = null,
        /** True iff the target's [app.aether.aegis.remote.RemoteCommandHandler.lockDevice]
         *  succeeded — i.e. [AegisAdminReceiver] is an active admin.
         *  Surfaced on AUTH_OK / LOCATE_RESULT / WATCH_TICK so the
         *  sender's UI can warn "Device Admin not enrolled — lock
         *  did not fire". Null = unknown / pre-this-version target. */
        val lockOk: Boolean? = null,
        /** Camera lens for [KIND_LIVE_CAM_START]: "front" or "rear".
         *  Null = target picks default (rear, since the stolen-phone
         *  angle is "what is the holder looking at"). */
        val lens: String? = null,
        /** Battery percentage 0..100. Surfaced on AUTH_OK,
         *  LOCATE_RESULT, WATCH_TICK, and PONG so the sender knows the
         *  target's battery state without having to ask separately. */
        val batteryPct: Int? = null,
        /** True iff the target is on a charger. Same payloads as
         *  [batteryPct]. */
        val charging: Boolean? = null,
    )

    fun encode(p: Packet): String {
        val o = JSONObject().apply {
            put("v", 1)
            put("kind", p.kind)
            p.pin?.let { put("pin", it) }
            p.sid?.let { put("sid", it) }
            p.lat?.let { put("lat", it) }
            p.lng?.let { put("lng", it) }
            p.ts?.let { put("ts", it) }
            p.forCmd?.let { put("for", it) }
            p.msg?.let { put("msg", it) }
            p.mugshotB64?.let { put("mug", it) }
            p.rearMugshotB64?.let { put("mug_rear", it) }
            p.seconds?.let { put("s", it) }
            p.audioB64?.let { put("aud", it) }
            p.lockOk?.let { put("lock_ok", it) }
            p.lens?.let { put("lens", it) }
            p.batteryPct?.let { put("batt", it) }
            p.charging?.let { put("chg", it) }
        }
        return PREFIX + o.toString()
    }

    /** Parse a body where the [PREFIX] has already been stripped. Returns
     *  null on malformed input — caller should drop unknowns silently. */
    fun decode(body: String): Packet? = runCatching {
        val o = JSONObject(body)
        Packet(
            kind = o.optString("kind").ifBlank { return@runCatching null },
            pin = o.optString("pin").ifBlank { null },
            sid = o.optString("sid").ifBlank { null },
            lat = if (o.has("lat") && !o.isNull("lat")) o.optDouble("lat") else null,
            lng = if (o.has("lng") && !o.isNull("lng")) o.optDouble("lng") else null,
            ts = if (o.has("ts") && !o.isNull("ts")) o.optLong("ts") else null,
            forCmd = o.optString("for").ifBlank { null },
            msg = o.optString("msg").ifBlank { null },
            mugshotB64 = o.optString("mug").ifBlank { null },
            rearMugshotB64 = o.optString("mug_rear").ifBlank { null },
            seconds = if (o.has("s") && !o.isNull("s")) o.optInt("s") else null,
            audioB64 = o.optString("aud").ifBlank { null },
            lockOk = if (o.has("lock_ok") && !o.isNull("lock_ok")) o.optBoolean("lock_ok") else null,
            lens = o.optString("lens").ifBlank { null },
            batteryPct = if (o.has("batt") && !o.isNull("batt")) o.optInt("batt") else null,
            charging = if (o.has("chg") && !o.isNull("chg")) o.optBoolean("chg") else null,
        )
    }.getOrNull()
}

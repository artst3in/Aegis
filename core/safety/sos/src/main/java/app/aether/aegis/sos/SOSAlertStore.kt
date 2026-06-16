package app.aether.aegis.sos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * In-memory registry of sos alerts received from paired peers. The
 * receiver-side sos dashboard (the sos dashboard adapter) reads this to
 * know which family member is currently broadcasting, when they
 * started, and where they are.
 *
 * Not persisted: a process restart drops the registry, which is fine
 * because the sos notification persists separately and tapping it
 * re-launches the dashboard. We also store nothing sensitive here —
 * just timestamps + the peer pubkey.
 *
 * The store also tracks the
 * coordination state for each active sos: how many contacts were
 * alerted, who has volunteered as a responder, where each responder
 * is, and whether THIS device has opted in. The data is shared
 * between the victim's phone (which relays + computes ETAs) and the
 * receiving phones (which display the responder map). The victim's
 * UI deliberately hides the per-responder details and shows
 * count + ETA only — that's a display-layer rule, not a transport
 * rule.
 */
object SOSAlertStore {

    /**
     * One opted-in responder. The locator is broadcast periodically
     * by the responder's phone, relayed through the victim's phone,
     * and lands here for everyone else's map to render.
     */
    data class Responder(
        val peerKey: String,
        val displayName: String,
        var latitude: Double? = null,
        var longitude: Double? = null,
        var accuracyMeters: Float? = null,
        var lastUpdate: Long = System.currentTimeMillis(),
        /** Set once the responder crosses the arrival geofence
         *  around the victim (~50 m). Null while they're still en
         *  route. Surfaced in the dashboard list as "● arrived". */
        var arrivedAt: Long? = null,
    )

    /**
     * Mutable fields are backed by [mutableStateOf] so that compose
     * consumers (the sos dashboard adapter) recompose when the victim's
     * roster broadcast or a coord-digest lands. Data-class would
     * have buried the mutable bits behind value-equality semantics —
     * fine for plain reads but the dashboard wouldn't observe the
     * field-level updates without us re-putting the whole Alert into
     * the map on every change.
     */
    class Alert(
        val peerKey: String,
        val startedAt: Long,
        initialEndedAt: Long? = null,
        initialLastText: String = "",
        initialRosterCount: Int? = null,
        initialIAmResponding: Boolean = false,
    ) {
        var endedAt: Long? by mutableStateOf(initialEndedAt)
        /** Free-text body of the most recent sos-update from this
         *  peer, useful for the dashboard header. */
        var lastText: String by mutableStateOf(initialLastText)
        /** How many contacts the victim alerted. Set when the
         *  `[aegis:sos-roster]` envelope arrives (or, on the
         *  victim's own phone, set when sos starts). Null means
         *  "not yet known" — UI shows just the responder count. */
        var rosterCount: Int? by mutableStateOf(initialRosterCount)
        /** Whether THIS device's owner opted in via "I'M RESPONDING".
         *  Once true, the device broadcasts its own GPS to the victim
         *  every 10 s until the sos ends or the user opts out. */
        var iAmResponding: Boolean by mutableStateOf(initialIAmResponding)

        /** Audio mute. When true, [SOSAudioPlayer] swallows newly
         *  arriving `[aegis:sos-audio]` clips for this peer instead
         *  of routing them to the speakerphone. Visual evidence
         *  (counter, map, responder list, recordings list) is
         *  unaffected. Flipped by the dashboard's
         *  hold-to-mute affordance — a 3-second deliberate hold so
         *  the user sits with the audio while deciding to silence
         *  it. */
        var isMuted: Boolean by mutableStateOf(false)

        /** Responder → Victim: responder voice (PTT)
         *  does NOT auto-play on the victim's device unless she has
         *  explicitly opted in. The victim broadcasts
         *  `[aegis:sos-victim-voice]on` to flip this flag for every
         *  contact. Off → responders silently drop the victim from
         *  their PTT fan-out. */
        var victimAllowsResponderVoice: Boolean by mutableStateOf(false)

        /** Receiver-side "you are the closest" badge. Set by an
         *  inbound `[aegis:sos-closest]on` directed envelope from
         *  the victim's coordinator; never set locally without a
         *  network signal. */
        var iAmClosest: Boolean by mutableStateOf(false)

        /** Latest still snapshot path from the SOS-active phone's
         *  periodic JPEG fan-out (`[aegis:sos-frame]`). The
         *  dashboard renders this inline so receivers see real-time
         *  visual context without having to Accept the WebRTC call.
         *  null until the first frame lands. */
        var latestSnapshotPath: String? by mutableStateOf(null)
        var latestSnapshotAt: Long by mutableStateOf(0L)

        val active: Boolean get() = endedAt == null
        val durationMs: Long get() = (endedAt ?: System.currentTimeMillis()) - startedAt
    }

    // Compose-observable. Reads from the sos dashboard adapter, ChatListScreen.
    private val alerts = mutableStateMapOf<String, Alert>()

    // Responder rosters keyed by the sos-victim's peerKey. Each
    // inner map is keyed by responder peerKey. Compose-observable.
    private val responders = mutableStateMapOf<String, androidx.compose.runtime.snapshots.SnapshotStateMap<String, Responder>>()

    /** Per-peer "I have dismissed this peer's sos locally." Survives
     *  in-memory across the sender's 30-s re-broadcast loop so the
     *  next inbound `[aegis:sos]` from a dismissed peer is silently
     *  dropped instead of re-creating the banner the user just
     *  swiped away. Cleared when:
     *   - the user explicitly opens the dashboard for that peer
     *     (they WANT to see it again), OR
     *   - the sender broadcasts a proper `[sos cancelled]`. */
    private val dismissedPeers = mutableSetOf<String>()

    fun activeAlerts(): List<Alert> = alerts.values.filter { it.active }

    fun all(): Map<String, Alert> = alerts

    /** Register or refresh an active sos from [peerKey]. Idempotent —
     *  if we already have an active alert for this peer, just refresh
     *  the text + start time stays the same. */
    fun markActive(peerKey: String, text: String) {
        val existing = alerts[peerKey]
        if (existing != null && existing.active) {
            existing.lastText = text
            return
        }
        alerts[peerKey] = Alert(
            peerKey = peerKey,
            startedAt = System.currentTimeMillis(),
            initialLastText = text,
        )
        // Fresh sos → fresh roster. Stale responder entries from a
        // previous (ended) sos for the same peer would otherwise
        // bleed onto the new dashboard.
        responders[peerKey] = mutableStateMapOf()
    }

    /** Mark an existing sos as ended. No-op if no active alert. */
    fun markEnded(peerKey: String) {
        val existing = alerts[peerKey] ?: return
        existing.endedAt = System.currentTimeMillis()
        // When an sos ends, responder location sharing stops and
        // routes disappear. Drop the roster so the dashboard re-renders
        // empty without us having to leak end-of-sos broadcasts.
        responders.remove(peerKey)
        // Stop any in-flight sos-audio playback — the speaker
        // shouldn't keep streaming after the sos ends, and
        // queued clips should be discarded rather than played
        // post-mortem. Best-effort; safe to no-op if the player
        // wasn't active for this peer.
        runCatching { SOSAudioPlayer.stopFor(peerKey) }
    }

    /** User-initiated dismiss: end the alert AND remember the peer so
     *  the sender's 30-s re-broadcast loop can't resurrect the banner
     *  every half-minute. The flag is cleared on an explicit cancel
     *  envelope from the sender or when the user opens the dashboard
     *  for them again. */
    fun markDismissed(peerKey: String) {
        markEnded(peerKey)
        dismissedPeers.add(peerKey)
    }

    /** True iff the receiver previously tapped Dismiss for this peer
     *  and the sender hasn't yet broadcast a proper cancel. Read by
     *  AegisApp.notifySOS to silently drop re-broadcasts. */
    fun isDismissed(peerKey: String): Boolean = peerKey in dismissedPeers

    /** Clear the dismissal flag — call when the sender cancels (the
     *  sos ended legitimately, so any future sos from them should
     *  alert again) or when the user opens the dashboard (they're
     *  re-engaging on purpose). */
    fun clearDismissed(peerKey: String) {
        dismissedPeers.remove(peerKey)
    }

    /** True iff there's an active sos for this peer right now. */
    fun isActive(peerKey: String): Boolean =
        alerts[peerKey]?.active == true

    fun forPeer(peerKey: String): Alert? = alerts[peerKey]

    /** Set the contact-count for an active sos (typically from an
     *  inbound `[aegis:sos-roster]<N>` envelope). No-op if there
     *  isn't an active alert from this peer. */
    fun setRosterCount(peerKey: String, count: Int) {
        alerts[peerKey]?.rosterCount = count
    }

    /** Snapshot of the responders for a given victim peer. Order is
     *  not stable across calls — caller sorts if needed. */
    fun respondersFor(peerKey: String): List<Responder> =
        responders[peerKey]?.values?.toList().orEmpty()

    /** Insert or refresh a responder for [victimKey]. The responder
     *  itself is identified by [responder.peerKey]. */
    fun upsertResponder(victimKey: String, responder: Responder) {
        val map = responders.getOrPut(victimKey) { mutableStateMapOf() }
        val existing = map[responder.peerKey]
        if (existing == null) {
            map[responder.peerKey] = responder
        } else {
            existing.latitude = responder.latitude ?: existing.latitude
            existing.longitude = responder.longitude ?: existing.longitude
            existing.accuracyMeters = responder.accuracyMeters ?: existing.accuracyMeters
            existing.lastUpdate = responder.lastUpdate
        }
    }

    /** Drop a responder from [victimKey]'s roster — e.g. when they
     *  withdraw via "Stop sharing". */
    fun removeResponder(victimKey: String, responderKey: String) {
        responders[victimKey]?.remove(responderKey)
    }

    /** Flip the local "I am responding" flag for this device's view
     *  of [victimKey]'s sos. Caller is responsible for the
     *  network-side accept envelope. */
    fun setRespondingSelf(victimKey: String, responding: Boolean) {
        alerts[victimKey]?.iAmResponding = responding
    }

    /** Mute / unmute the live audio stream from this peer locally.
     *  No network side-effect — the victim keeps streaming, we just
     *  swallow the chunks. See [Alert.isMuted]. */
    fun setMuted(victimKey: String, muted: Boolean) {
        alerts[victimKey]?.isMuted = muted
    }

    fun isMuted(victimKey: String): Boolean =
        alerts[victimKey]?.isMuted == true

    /** Victim's opt-in for incoming responder PTT — set by inbound
     *  `[aegis:sos-victim-voice]<on|off>` envelope. Read by
     *  responders to decide whether to include the victim in their
     *  PTT fan-out. */
    fun setVictimAllowsResponderVoice(victimKey: String, allow: Boolean) {
        alerts[victimKey]?.victimAllowsResponderVoice = allow
    }

    /** Closest-contact badge — flipped by the victim's coordinator
     *  via a directed `[aegis:sos-closest]on` envelope. Mutually
     *  exclusive: only ever set true for the single closest peer at
     *  any time; reset to false when distance ranks change. */
    fun setIAmClosest(victimKey: String, closest: Boolean) {
        alerts[victimKey]?.iAmClosest = closest
    }

    /** Record the path of the latest `[aegis:sos-frame]` JPEG
     *  received from [victimKey]. Receiver-side display only;
     *  dashboards observe via the Alert. */
    fun setLatestSnapshot(victimKey: String, path: String) {
        alerts[victimKey]?.let {
            it.latestSnapshotPath = path
            it.latestSnapshotAt = System.currentTimeMillis()
        }
    }
}

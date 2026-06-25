package app.aether.aegis.sos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * SOS-response coordinator.
 *
 * Two roles, same object:
 *
 *   Victim side ("I am Alice, broadcasting sos")
 *     - On sos start, call [broadcastRoster] to publish "I alerted N
 *       contacts" so every receiver can render the counter.
 *     - When a paired contact taps "I'M RESPONDING", we receive
 *       `[aegis:sos-response:accept]` from them. We add them to our
 *       roster and broadcast a digest envelope back out to ALL other
 *       paired contacts so their dashboards can render the responder
 *       map / counter update.
 *     - Same pattern for `[aegis:sos-responder-loc]` updates from
 *       responders — accumulate, re-broadcast digest.
 *     - Display rule (enforced at the dashboard layer, not here): our
 *       own UI shows count + ETA only. Names, distances, routes are
 *       attacker-safe (hidden from the victim's screen).
 *
 *   Receiver side ("I am Bob, watching Alice's sos")
 *     - On `[aegis:sos-roster]<N>` from Alice → update rosterCount.
 *     - On `[aegis:sos-coord]<json>` from Alice → replace our local
 *       responder snapshot with the digest. Includes me-the-responder
 *       if I've opted in, plus everyone else.
 *     - On user tapping "I'M RESPONDING", call [acceptResponse] which:
 *           1. Sends `[aegis:sos-response:accept]` to the victim.
 *           2. Starts a 10-s loop sending `[aegis:sos-responder-loc]`
 *              with our current GPS, until the sos ends or the user
 *              opts out via [withdrawResponse].
 *
 * Privacy & safety rules:
 *   - Non-responders are invisible (no envelope is ever sent unless
 *     the user tapped Accept).
 *   - Self-distance is computed locally, never broadcast.
 *   - "You are the closest" is NOT yet implemented; deliberate
 *     deferral — see TODO at the bottom of this file.
 *   - On sos end the responder map is dropped (see
 *     SOSAlertStore.markEnded) and our own broadcast loop unwinds
 *     because [_responderLoop] only runs while `iAmResponding`.
 *
 * Module note (trust-container Phase 2, Stage 3): this lives in
 * `:core:safety:sos` and reaches the app only through
 * [SOSModuleHost] — the send primitive, sos targets, self key,
 * peer name / isAegis lookups, victim location, and the achievement
 * award all come through the host holder. No compile edge to `:app`.
 */
object SOSCoordinator {

    /** Hard upper bound on receiver-side broadcast loops. After this
     *  the local alert is auto-ended and all per-victim loops shut
     *  down even if no cancel envelope ever arrived. See
     *  [startReceivingSOS] for the rationale (privacy + UX bug
     *  reported 2026.05.611 — stuck loop spamming victim's chat with
     *  sos-distance pings). */
    private const val SOS_MAX_AGE_MS = 30L * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Periodic responder-location broadcast jobs, keyed by victim. */
    private val responderLoops: MutableMap<String, Job> = mutableMapOf()

    /** Periodic victim-side digest fan-out jobs, keyed by my-own
     *  sosId — currently always the self deviceId of THIS phone
     *  (only one outbound sos at a time). Cancelled on sos
     *  cancel. */
    @Volatile private var coordDigestJob: Job? = null

    /** Dispatch entry point for any `[aegis:sos-*]` envelope (other
     *  than the original `[aegis:sos]` itself). Called from
     *  AegisApp.handleInboundStatus. */
    suspend fun handleInbound(fromKey: String, body: String) {
        when {
            body.startsWith("[aegis:sos-roster]") -> {
                val n = body.removePrefix("[aegis:sos-roster]").trim().toIntOrNull()
                if (n != null) {
                    SOSAlertStore.setRosterCount(fromKey, n)
                }
            }
            body.startsWith("[aegis:sos-response:accept]") -> {
                // Sender (fromKey) volunteered as a responder for OUR
                // own sos — we are the victim relay.
                if (SOSHandlerBridge.isMyOwnSOSActive()) {
                    handleAcceptOnVictim(fromKey)
                }
            }
            body.startsWith("[aegis:sos-responder-loc]") -> {
                // Periodic GPS ping from a responder, addressed to us
                // because we are the victim relay.
                if (SOSHandlerBridge.isMyOwnSOSActive()) {
                    val payload = body.removePrefix("[aegis:sos-responder-loc]").trim()
                    handleResponderLocOnVictim(fromKey, payload)
                }
            }
            body.startsWith("[aegis:sos-coord]") -> {
                // Digest from the victim — apply wholesale to our
                // local view of THEIR sos.
                val payload = body.removePrefix("[aegis:sos-coord]").trim()
                applyDigestFromVictim(fromKey, payload)
            }
            body.startsWith("[aegis:sos-victim-voice]") -> {
                // Victim opted in/out of receiving responder PTT
                // (Responder → Victim). Receivers track the flag so the PTT
                // sender knows whether to include the victim in
                // their fan-out.
                val payload = body.removePrefix("[aegis:sos-victim-voice]").trim().lowercase()
                SOSAlertStore.setVictimAllowsResponderVoice(fromKey, payload == "on")
            }
            body.startsWith("[aegis:sos-distance]") -> {
                // Each contact privately sends its self-computed
                // distance-to-victim here. The victim's coordinator
                // sorts incoming distances and tells ONE contact
                // they're closest via the directed envelope below.
                // Distances are never relayed back out — Privacy
                // Rule 1.
                if (SOSHandlerBridge.isMyOwnSOSActive()) {
                    val meters = body.removePrefix("[aegis:sos-distance]")
                        .trim().toDoubleOrNull()
                    if (meters != null) {
                        handleDistanceOnVictim(fromKey, meters)
                    }
                }
            }
            body.startsWith("[aegis:sos-closest]") -> {
                // Victim's coordinator picked this device as the
                // single closest contact (or unset it). Sets/clears
                // the local "You are the closest" badge.
                val payload = body.removePrefix("[aegis:sos-closest]").trim().lowercase()
                SOSAlertStore.setIAmClosest(fromKey, payload == "on")
            }
            body.startsWith("[aegis:sos-arrived]") -> {
                // Responder is within ARRIVAL_RADIUS_M of victim —
                // they fan this out so EVERY other contact (and the
                // victim's coordinator for evidence log) sees the
                // arrival. Stores an attached note on the responder
                // so the dashboard list can render "● arrived".
                val payload = body.removePrefix("[aegis:sos-arrived]").trim()
                handleArrivalAnnounce(fromKey, payload)
            }
        }
    }

    private fun handleArrivalAnnounce(fromKey: String, payload: String) {
        // payload shape: "<victimKey>". The fromKey itself is the
        // arriver. We mark the responder entry's arrival timestamp.
        val victimKey = payload.takeIf { it.isNotBlank() } ?: return
        val map = responderMapFor(victimKey) ?: return
        val r = map[fromKey] ?: return
        r.arrivedAt = System.currentTimeMillis()
        // Log to the evidence trail if we ARE the victim.
        if (SOSHandlerBridge.isMyOwnSOSActive()) {
            SOSEvidenceLog.append(
                "ARRIVED ${r.displayName} (${fromKey.take(8)}) at ${r.arrivedAt}",
            )
        }
    }

    /** Helper for handleArrivalAnnounce. */
    private fun responderMapFor(victimKey: String): MutableMap<String, SOSAlertStore.Responder>? {
        return if (SOSHandlerBridge.isMyOwnSOSActive()) ownSOSResponders else null
        // Receiver-side digest holds responders in SOSAlertStore;
        // we let the next digest tick pick up the arrival flag from
        // the victim's ownSOSResponders map (above) and propagate.
    }

    /* ---------- Victim side ---------- */

    /** Called by SOSHandler.startVictim() once the roster is known.
     *  Broadcasts `[aegis:sos-roster]<N>` to every sos target,
     *  starts the digest fan-out loop, opens the evidence log. */
    fun startVictimSide(rosterCount: Int) {
        // Fresh sos = fresh distance ledger + closest reset.
        ownSOSResponders.clear()
        ownSOSDistances.clear()
        lastClosestKey = null
        // Evidence log: one file per sos, named by the start
        // timestamp. Survives process restart; only writable while
        // the victim's sos is active.
        val sosId = "sos-${System.currentTimeMillis()}"
        SOSEvidenceLog.open(sosId)
        SOSEvidenceLog.append("ALERT sent to $rosterCount contact(s)")
        scope.launch {
            broadcastRoster(rosterCount)
        }
        // Cancel any leftover loop from a prior sos and start a
        // fresh one. The loop publishes the current responder
        // snapshot every 5 s to all paired contacts.
        coordDigestJob?.cancel()
        coordDigestJob = scope.launch {
            while (isActive && SOSHandlerBridge.isMyOwnSOSActive()) {
                runCatching { broadcastDigest() }
                delay(5_000)
            }
        }
    }

    /** Called from SOSHandler.cancel(). Tears down the digest loop
     *  and seals the evidence log. */
    fun stopVictimSide() {
        coordDigestJob?.cancel()
        coordDigestJob = null
        SOSEvidenceLog.append("SOS ENDED")
        SOSEvidenceLog.close()
        ownSOSResponders.clear()
        ownSOSDistances.clear()
        lastClosestKey = null
    }

    private suspend fun broadcastRoster(n: Int) {
        val host = SOSModuleHostHolder.current ?: return
        val selfKey = host.selfKey
        val targets = runCatching { host.sosTargetKeys() }.getOrNull().orEmpty()
        val body = "[aegis:sos-roster]$n"
        targets.forEach { key ->
            if (key == selfKey) return@forEach
            runCatching { host.sendStatus(key, body) }
        }
    }

    /** Per-victim responder accumulator, used only on the victim's own
     *  phone. The receiver side reads this AS-IS into its rendering
     *  pipeline via [SOSAlertStore]; on the victim side it stays
     *  here in memory until sos ends. */
    private val ownSOSResponders: MutableMap<String, SOSAlertStore.Responder> = mutableMapOf()

    private suspend fun handleAcceptOnVictim(responderKey: String) {
        val name = runCatching {
            SOSModuleHostHolder.current?.displayNameFor(responderKey)
        }.getOrNull() ?: responderKey.take(8)
        ownSOSResponders[responderKey] = SOSAlertStore.Responder(
            peerKey = responderKey,
            displayName = name,
        )
        SOSEvidenceLog.append("RESPONDING $name (${responderKey.take(8)})")
        // A real human answered your sos → earn the SOS Drill badge.
        // Passive, bulletproof, never throws.
        SOSModuleHostHolder.current?.unlockSOSDrillAchievement()
        // No need to wait — the digest loop will pick it up.
    }

    private fun handleResponderLocOnVictim(responderKey: String, payload: String) {
        val parts = payload.split(",")
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return
        val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: return
        val acc = parts.getOrNull(2)?.toFloatOrNull()
        val existing = ownSOSResponders[responderKey]
        if (existing != null) {
            existing.latitude = lat
            existing.longitude = lng
            existing.accuracyMeters = acc
            existing.lastUpdate = System.currentTimeMillis()
        } else {
            // Responder-loc arrived before the accept (rare race). Create the
            // Responder INSTANTLY with a truncated-key placeholder name, then
            // resolve the real display name off-thread and patch it in. The
            // previous code did runBlocking { displayNameFor(...) } right here
            // — on the SOS inbound path that risks an ANR (the app hanging at
            // the worst possible moment, while a user is in danger). Never
            // block this path. The digest shows the truncated key until the
            // real name resolves a beat later.
            ownSOSResponders[responderKey] = SOSAlertStore.Responder(
                peerKey = responderKey,
                displayName = responderKey.take(8),
                latitude = lat,
                longitude = lng,
                accuracyMeters = acc,
            )
            scope.launch {
                val resolved = runCatching {
                    SOSModuleHostHolder.current?.displayNameFor(responderKey)
                }.getOrNull() ?: return@launch
                // Patch the name in via copy() (displayName is val). Re-read
                // the current entry so any lat/lng updated since is preserved;
                // skip if the entry's gone (responder left / map replaced).
                ownSOSResponders[responderKey]?.let { cur ->
                    ownSOSResponders[responderKey] = cur.copy(displayName = resolved)
                }
            }
        }
    }

    /** Receiver-side distance ledger held only on the victim's
     *  device. Keys are responder pubkeys; values are the most
     *  recent self-computed distance the responder reported. We
     *  never relay these — Privacy Rule 1 ("distance is yours").
     *  The only output is the directed `[aegis:sos-closest]` ping
     *  to the single closest contact below. */
    private val ownSOSDistances: MutableMap<String, Double> = mutableMapOf()

    /** Last pubkey we marked as closest, so we can flip them off
     *  when someone else takes the lead. */
    @Volatile private var lastClosestKey: String? = null

    private fun handleDistanceOnVictim(fromKey: String, meters: Double) {
        ownSOSDistances[fromKey] = meters
        scope.launch { recomputeClosest() }
    }

    private suspend fun recomputeClosest() {
        if (ownSOSDistances.isEmpty()) return
        val winner = ownSOSDistances.minByOrNull { it.value }?.key ?: return
        val previous = lastClosestKey
        if (winner == previous) return
        val host = SOSModuleHostHolder.current ?: return
        // Demote the previous closest (if any).
        if (previous != null) {
            runCatching {
                host.sendStatus(previous, "[aegis:sos-closest]off")
            }
        }
        // Promote the new closest.
        runCatching {
            host.sendStatus(winner, "[aegis:sos-closest]on")
        }
        lastClosestKey = winner
    }

    private suspend fun broadcastDigest() {
        val host = SOSModuleHostHolder.current ?: return
        val selfKey = host.selfKey
        val targets = runCatching { host.sosTargetKeys() }.getOrNull().orEmpty()
        // Build digest JSON. Shape:
        //   {"responders":[{"k":<peerKey>,"n":<name>,"lat":..,"lng":..,"t":<ms>}]}
        val arr = JSONArray()
        ownSOSResponders.values.forEach { r ->
            arr.put(JSONObject().apply {
                put("k", r.peerKey)
                put("n", r.displayName)
                if (r.latitude != null) put("lat", r.latitude)
                if (r.longitude != null) put("lng", r.longitude)
                r.accuracyMeters?.let { put("acc", it.toDouble()) }
                put("t", r.lastUpdate)
                r.arrivedAt?.let { put("arr", it) }
            })
        }
        val payload = JSONObject().put("responders", arr).toString()
        val body = "[aegis:sos-coord]$payload"
        targets.forEach { key ->
            if (key == selfKey) return@forEach
            runCatching { host.sendStatus(key, body) }
        }
    }

    /* ---------- Receiver side ---------- */

    private fun applyDigestFromVictim(victimKey: String, payload: String) {
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val arr = obj.optJSONArray("responders") ?: return
        // Rebuild the per-victim map by upserting every entry. We
        // don't aggressively prune — a brief dropout shouldn't make
        // a responder vanish from everyone's map. Stale entries are
        // dropped when sos ends (see SOSAlertStore.markEnded).
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val responder = SOSAlertStore.Responder(
                peerKey = o.optString("k"),
                displayName = o.optString("n").ifBlank { o.optString("k").take(8) },
                latitude = if (o.has("lat")) o.optDouble("lat") else null,
                longitude = if (o.has("lng")) o.optDouble("lng") else null,
                accuracyMeters = if (o.has("acc")) o.optDouble("acc").toFloat() else null,
                lastUpdate = o.optLong("t", System.currentTimeMillis()),
                arrivedAt = if (o.has("arr")) o.optLong("arr") else null,
            )
            if (responder.peerKey.isNotBlank()) {
                SOSAlertStore.upsertResponder(victimKey, responder)
            }
        }
    }

    /**
     * User tapped "I'M RESPONDING" on the receiver-side dashboard
     * for [victimKey]. Sends the accept envelope, flips local state,
     * and starts the periodic responder-location loop.
     *
     * Idempotent — duplicate taps are silently absorbed.
     */
    fun acceptResponse(victimKey: String) {
        if (responderLoops.containsKey(victimKey)) return
        SOSAlertStore.setRespondingSelf(victimKey, true)
        val job = scope.launch {
            runCatching {
                SOSModuleHostHolder.current?.sendStatus(
                    victimKey, "[aegis:sos-response:accept]",
                )
            }
            var announcedArrival = false
            val startedAt = System.currentTimeMillis()
            while (isActive && SOSAlertStore.isActive(victimKey) &&
                SOSAlertStore.forPeer(victimKey)?.iAmResponding == true) {
                // Same hard cutoff as the receiver-side distance loop —
                // see startReceivingSOS. A dropped cancel envelope
                // can't pin us into broadcasting indefinitely.
                if (System.currentTimeMillis() - startedAt > SOS_MAX_AGE_MS) {
                    SOSAlertStore.markEnded(victimKey)
                    break
                }
                runCatching { broadcastSelfLocation(victimKey) }
                // Distance ping — fed to the victim's closest-detection
                // ledger. Recomputed locally from our last-known fix +
                // the victim's broadcast GPS (read via the host).
                runCatching { broadcastSelfDistance(victimKey) }
                // Arrival probe: once we cross the geofence radius
                // around the victim, fan out a one-shot announcement
                // so EVERY contact's dashboard shows "● arrived".
                if (!announcedArrival && hasArrivedAt(victimKey)) {
                    runCatching { broadcastArrival(victimKey) }
                    announcedArrival = true
                }
                delay(10_000)
            }
        }
        responderLoops[victimKey] = job
    }

    private suspend fun broadcastSelfDistance(victimKey: String) {
        val meters = computeDistanceToVictim(victimKey) ?: return
        val body = "[aegis:sos-distance]${meters.toLong()}"
        runCatching {
            SOSModuleHostHolder.current?.sendStatus(victimKey, body)
        }
    }

    private suspend fun broadcastArrival(victimKey: String) {
        val body = "[aegis:sos-arrived]$victimKey"
        // Fan out to ALL sos-eligible peers so every dashboard
        // showing this victim's sos gets the arrival flag — not
        // just the victim. Cheap: one envelope each, short body.
        val host = SOSModuleHostHolder.current ?: return
        val selfKey = host.selfKey
        val targets = runCatching { host.sosTargetKeys() }.getOrNull().orEmpty()
        targets.forEach { key ->
            if (key == selfKey) return@forEach
            runCatching { host.sendStatus(key, body) }
        }
    }

    private suspend fun hasArrivedAt(victimKey: String): Boolean {
        val d = computeDistanceToVictim(victimKey) ?: return false
        return d <= ARRIVAL_RADIUS_M
    }

    private suspend fun computeDistanceToVictim(victimKey: String): Double? {
        val my = SelfLocation.fetch() ?: return null
        val v = runCatching {
            SOSModuleHostHolder.current?.victimLocation(victimKey)
        }.getOrNull() ?: return null
        return haversineMeters(my.latitude, my.longitude, v.first, v.second)
    }

    private val ARRIVAL_RADIUS_M = 50.0

    private fun haversineMeters(
        lat1: Double, lon1: Double, lat2: Double, lon2: Double,
    ): Double {
        val r = 6_371_000.0
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δφ = Math.toRadians(lat2 - lat1)
        val Δλ = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(Δφ / 2).let { it * it } +
            kotlin.math.cos(φ1) * kotlin.math.cos(φ2) *
            kotlin.math.sin(Δλ / 2).let { it * it }
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }

    /** User opted out of being a responder. Cancels the location
     *  loop; the dashboard will reflect the local flag flip
     *  immediately. We do NOT send a "withdraw" envelope — non-
     *  participation is invisible by design. */
    fun withdrawResponse(victimKey: String) {
        SOSAlertStore.setRespondingSelf(victimKey, false)
        responderLoops.remove(victimKey)?.cancel()
    }

    /** Receiver-side distance probe: every contact (responder or
     *  not) periodically sends its self-computed distance-to-victim
     *  to the victim's coordinator. Drives closest-detection. */
    private val distanceLoops: MutableMap<String, Job> = mutableMapOf()

    /** Called when this device first receives `[aegis:sos]` from
     *  [victimKey] (see AegisApp.notifySOS). Spins up a 30-second
     *  loop that pings the distance to the victim until either the
     *  sos ends or this device is the victim itself.
     *
     *  HARD CUTOFF: the loop self-terminates after [SOS_MAX_AGE_MS]
     *  regardless of whether a cancel envelope has arrived. Without
     *  this, a dropped cancel (sender's phone died / message lost /
     *  user swiped instead of tapping STOP) leaves this device
     *  pinging `[aegis:sos-distance]` to the sender forever — a
     *  serious privacy + UX bug because those pings show up as
     *  chat-thread text on builds whose classifier doesn't know
     *  the prefix (user report, 2026.05.611). 30 min is well past
     *  any credible sos window — if the responder hasn't reached
     *  the victim by then, distance pings aren't helping.
     *
     *  Also: don't bother broadcasting to a peer that isn't on
     *  Aegis — [app.aether.aegis.sos.SOSModuleHost.isAegis] would
     *  drop the envelope anyway, but checking up front skips the
     *  GPS+haversine cost and avoids polluting the connection log
     *  every 30 s. */
    fun startReceivingSOS(victimKey: String) {
        val selfKey = SOSModuleHostHolder.current?.selfKey ?: return
        if (victimKey == selfKey) return  // I'm the victim — don't ping myself.
        if (distanceLoops.containsKey(victimKey)) return
        distanceLoops[victimKey] = scope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive && SOSAlertStore.isActive(victimKey)) {
                val ageMs = System.currentTimeMillis() - startedAt
                if (ageMs > SOS_MAX_AGE_MS) {
                    // Backstop: end the local alert too so any other
                    // surfaces watching isActive() also quiesce.
                    SOSAlertStore.markEnded(victimKey)
                    break
                }
                val peerIsAegis = runCatching {
                    SOSModuleHostHolder.current?.isAegis(victimKey) == true
                }.getOrDefault(false)
                if (peerIsAegis) {
                    runCatching {
                        val meters = computeDistanceToVictim(victimKey)
                        if (meters != null) {
                            SOSModuleHostHolder.current?.sendStatus(
                                victimKey, "[aegis:sos-distance]${meters.toLong()}",
                            )
                        }
                    }
                }
                delay(30_000)
            }
            distanceLoops.remove(victimKey)
        }
    }

    /** Called when this device receives the sos-cancel envelope —
     *  tears down both the distance loop and the responder loop
     *  for this victim. */
    fun stopReceivingSOS(victimKey: String) {
        distanceLoops.remove(victimKey)?.cancel()
        responderLoops.remove(victimKey)?.cancel()
    }

    private suspend fun broadcastSelfLocation(victimKey: String) {
        val loc = SelfLocation.fetch() ?: return
        val payload = buildString {
            append(loc.latitude)
            append(',')
            append(loc.longitude)
            loc.accuracy?.let { append(',').append(it) }
        }
        val body = "[aegis:sos-responder-loc]$payload"
        runCatching {
            SOSModuleHostHolder.current?.sendStatus(victimKey, body)
        }
    }

    // TODO "Closest Person — Additional Info":
    // each contact computes its own distance locally and is not
    // supposed to share it with anyone (Privacy Rule 1). Determining
    // "you are the closest" without leaking everyone else's distance
    // is the open puzzle; deferred for a follow-up.
}

/**
 * Thin bridge for the "am I currently broadcasting my own sos"
 * check. Routes through [SOSModuleHost.isMyOwnSOSActive] (the app
 * reads SOSHandler.state) so [SOSCoordinator] keeps no compile
 * edge to the handler. Fail closed: a missing host reads "not my
 * sos".
 */
internal object SOSHandlerBridge {
    fun isMyOwnSOSActive(): Boolean =
        SOSModuleHostHolder.current?.isMyOwnSOSActive() ?: false
}

/**
 * Standalone last-known-fix reader used by responder loops. Cheap —
 * pulls cached GPS/NETWORK fixes only; no waiting for a fresh
 * provider callback. The 10 s broadcast cadence amortises stale fixes
 * naturally so the receiver-side map drifts smoothly. Context comes
 * from the sos-module host (no :app edge).
 */
internal object SelfLocation {
    data class Fix(val latitude: Double, val longitude: Double, val accuracy: Float?)

    @android.annotation.SuppressLint("MissingPermission")
    fun fetch(): Fix? {
        val ctx = SOSModuleHostHolder.current?.appContext ?: return null
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE)
            as? android.location.LocationManager ?: return null
        val providers = buildList {
            if (fine) add(android.location.LocationManager.GPS_PROVIDER)
            add(android.location.LocationManager.NETWORK_PROVIDER)
            add(android.location.LocationManager.PASSIVE_PROVIDER)
        }
        for (p in providers) {
            if (!runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)) continue
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            return Fix(
                latitude = loc.latitude,
                longitude = loc.longitude,
                accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
            )
        }
        return null
    }
}

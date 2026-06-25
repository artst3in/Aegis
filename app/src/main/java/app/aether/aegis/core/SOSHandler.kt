package app.aether.aegis.core

import app.aether.aegis.AegisApp
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * SOS handler — triggers on button press or snatch detection.
 *
 * On trigger:
 *   1. Snapshot GPS (best available, doesn't block on permission denied).
 *   2. Start audio recording.
 *   3. Send a MessageType.SOS message to every sos-tier contact via
 *      the available transport. If no transport is healthy, the message
 *      lands in the outbox and is delivered when one comes back.
 *   4. Keep GPS-streaming: send a location update every 10s while active.
 *   5. Surface a sticky "SOS ACTIVE" state to the UI via StateFlow.
 */
class SOSHandler(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class Active(
        val trigger: SOSTrigger,
        val startedAt: Long,
        val lastLocation: Location?,
    )

    private val _state = MutableStateFlow<Active?>(null)
    val state: StateFlow<Active?> = _state

    /** Wall-clock instant of the most recent sos-end transition.
     *  Null until the first sos completes. Used by the Ghost-Mode
     *  auto-engage decision: low battery during or shortly after
     *  sos auto-engages; low battery without recent sos prompts
     *  the user instead of force-engaging. */
    private val _lastEndedAt = MutableStateFlow<Long?>(null)
    val lastEndedAt: StateFlow<Long?> = _lastEndedAt

    /** True iff sos is currently active OR ended within [windowMs].
     *  Driver for the Ghost-Mode auto-engage branch — see
     *  ProtocolService.checkDeceptionEngagement. */
    fun isSOSRecent(windowMs: Long = 60L * 60_000L): Boolean {
        if (_state.value != null) return true
        val ended = _lastEndedAt.value ?: return false
        return System.currentTimeMillis() - ended < windowMs
    }

    private var recorder: MediaRecorder? = null
    private var trackingJob: Job? = null
    private var audioRotationJob: Job? = null
    private var recordingFile: File? = null
    private val recordedSegments = mutableListOf<File>()
    private val lockdown = LockdownController(context)
    private var liveStream: app.aether.aegis.call.SOSLiveStream? = null

    // commit() (NOT apply()) on the pending-sos write below: the process is
    // killed immediately after, so the write MUST land synchronously — apply()
    // would lose the pending SOS when the process dies before its async flush.
    // Lint flags it as ApplySharedPref; that "fix" would be an SOS-dropping bug.
    @android.annotation.SuppressLint("ApplySharedPref")
    fun trigger(trigger: SOSTrigger, sosActivity: android.app.Activity? = null) {
        // SOS while on an EPHEMERAL profile: an
        // ephemeral profile has no safety contacts, so we can't broadcast
        // from it. One button must do three things — destroy the evidence,
        // switch to the real profile, and call for help. So: stash a
        // pending-sos flag, schedule the ephemeral wipe + switch to the
        // primary, and restart; AegisApp re-fires this trigger on the
        // primary profile once it's up. (If not ephemeral, fall through to
        // the normal path unchanged.)
        runCatching {
            val reg = app.aether.aegis.profile.ProfileRegistry.get(context)
            if (reg.isEphemeral(reg.activeProfileId)) {
                context.getSharedPreferences("aegis_state", Context.MODE_PRIVATE)
                    .edit().putString("pending_sos", trigger.name).commit()
                if (app.aether.aegis.profile.EphemeralProfile.scheduleWipeIfEphemeral(context)) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return
                }
            }
        }

        if (_state.value != null) return

        val startedAt = System.currentTimeMillis()
        _state.value = Active(trigger, startedAt, null)

        // Lockdown FIRST so the phone goes silent + locked before the
        // thief notices anything is happening.
        lockdown.engage(sosActivity)

        scope.launch { snapshotLocationAndBroadcast(trigger) }
        startAudioRecording()
        startLocationTracking(trigger)
        scope.launch { startLiveStream() }
        // Periodic JPEG snapshot fan-out — gives receivers an inline
        // visual preview before they tap Accept on the WebRTC live
        // call. PowerBudget gates inside the loop so we automatically
        // back off on ≤40 % battery.
        runCatching { app.aether.aegis.sos.SOSSnapshotStream.start() }
        // SOS coordination. Publish
        // the roster count and start the digest fan-out loop so every
        // contact's dashboard can show "Alert sent to N. M responding."
        // The roster is computed from THIS device's sosTargets() —
        // every contact sees the same number because they're being
        // alerted by the same roster.
        scope.launch {
            val n = runCatching {
                AegisApp.instance.repository.sosTargets().size
            }.getOrDefault(0)
            app.aether.aegis.sos.SOSCoordinator.startVictimSide(rosterCount = n)
        }

        // Sender-side "SOS ACTIVE — tap STOP" notification on the
        // SOS-active phone itself. Without this, snatch-triggered
        // soss on a locked phone had NO surface anywhere — the
        // SOSScreen only renders when Aegis is foregrounded, so
        // the owner had no way to know sos fired or how to stop
        // it. The "STOP SOS" action routes through
        // SOSCancelOwnReceiver which calls cancel() back into
        // this handler.
        // SUPPRESS the visible own-SOS notification on the duress path.
        // For every other trigger this banner is the owner's only "SOS is
        // live — tap STOP" surface (a snatch on a locked phone has no
        // other UI), so it stays. But on a DURESS unlock a MAX-priority,
        // vibrating, full-screen "SOS ACTIVE — broadcasting your location
        // + audio" alert over the lock screen would tell the coercer
        // exactly what just happened — the opposite of the silent-duress
        // guarantee. (Security review 2026-06-07.)
        if (trigger != SOSTrigger.DURESS) {
            runCatching { postOwnSOSNotification(trigger) }
        }

        // Re-broadcast the initial SOS every 30 s while active —
        // covers the case where a transport ate the first message.
        // The Basic/Full split was dropped; this is just
        // "what sos does" now, not a tier-gated behaviour.
        scope.launch {
            while (isActive && _state.value != null) {
                delay(30_000)
                if (_state.value == null) return@launch
                val loc = _state.value?.lastLocation
                broadcast(buildAlertMessage(trigger, loc), MessageType.SOS)
            }
        }
    }

    fun cancel() {
        if (_state.value != null) _lastEndedAt.value = System.currentTimeMillis()
        _state.value = null
        trackingJob?.cancel()
        trackingJob = null
        // Ship whatever's in the in-flight segment before tearing down
        // — otherwise the last <60 s of audio is lost.
        val tail = recordingFile
        stopAudioRecording(keepSegments = true)
        if (tail != null && tail.length() > 0L) shipAudioSegment(tail)
        liveStream?.stop()
        liveStream = null
        runCatching { app.aether.aegis.sos.SOSSnapshotStream.stop() }
        runCatching {
            androidx.core.app.NotificationManagerCompat
                .from(context)
                .cancel(app.aether.aegis.SOSCancelOwnReceiver.OWN_SOS_NOTIF_ID)
        }
        lockdown.clear()
        // Stop the coordinator's digest loop. Responder devices unwind
        // their own loops once they see sos ended in SOSAlertStore.
        app.aether.aegis.sos.SOSCoordinator.stopVictimSide()

        // Tag with [aegis:sos] so the receiver's SimpleXTransport
        // classifier routes it to AegisApp.notifySOS, which inspects
        // the inner body and clears the dashboard alert.
        val cancellation = "[aegis:sos][sos cancelled] ${AegisApp.instance.identity.shortId}"
        broadcast(cancellation, MessageType.SOS)
    }

    private suspend fun snapshotLocationAndBroadcast(trigger: SOSTrigger) {
        // First-broadcast gets a FRESH fix (20 s timeout) instead of
        // just the cached lastKnownLocation — a code review
        // flagged this as HIGH: getLastKnownLocation() can be null or
        // hours stale, and the first sos message is the most
        // important one ("where am I right NOW"). RemoteCommandHandler
        // already does this for LOCATE; mirroring the approach here.
        // Falls back to cached if the fresh fix times out.
        val loc = readFreshLocation() ?: readLocation()
        _state.value = _state.value?.copy(lastLocation = loc)
        val msg = buildAlertMessage(trigger, loc)
        broadcast(msg, MessageType.SOS)
    }

    private fun startLocationTracking(trigger: SOSTrigger) {
        // Interval is set by PowerBudget — Voyager curve, five stages:
        // 10 s → 30 s → 60 s → 5 min → 1 h as battery drops. Read each
        // iteration so the interval tightens / loosens with the charge.
        trackingJob = scope.launch {
            while (isActive) {
                val budget = AegisApp.instance.powerBudget
                budget.refresh()
                delay(budget.sosGpsIntervalMs())
                if (_state.value == null) return@launch
                val loc = readLocation() ?: continue
                _state.value = _state.value?.copy(lastLocation = loc)
                // Tag with the same prefix routine location broadcasts
                // use so the receiver's SimpleXTransport classifier
                // routes it as MessageType.LOCATION (not chat spam).
                // Body shape after the prefix is the same JSON the
                // routine broadcast uses, so the inbound handler has
                // one parse path.
                val tagged = """[aegis:location]{"lat":${loc.latitude},"lng":${loc.longitude},"ts":${System.currentTimeMillis()}}"""
                broadcast(tagged, MessageType.LOCATION)
            }
        }
    }

    /**
     * Best-effort current-location read using AOSP LocationManager —
     * no Play Services. Falls back through GPS → NETWORK → PASSIVE,
     * picking the first provider that's both enabled and has been
     * granted permission. Returns the last cached fix; if there isn't
     * one we don't try to wait for a new fix (sos needs to fire
     * fast, location is best-effort).
     */
    /**
     * Request a fresh fix from GPS / NETWORK with [timeoutMs] budget,
     * resolving on the first onLocationChanged callback. Returns null
     * if no provider is enabled or the timeout elapses. Used for the
     * initial sos broadcast where a fresh fix is worth the wait;
     * the subsequent streaming path stays cheap via [readLocation].
     */
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun readFreshLocation(timeoutMs: Long = 20_000L): Location? {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE)
            as? android.location.LocationManager ?: return null

        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine<Location?> { cont ->
                val provider = when {
                    fine && lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ->
                        android.location.LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ->
                        android.location.LocationManager.NETWORK_PROVIDER
                    else -> {
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                }
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        if (cont.isActive) cont.resume(loc)
                        runCatching { lm.removeUpdates(this) }
                    }
                    @Deprecated("kept for compat") override fun onStatusChanged(
                        p: String?, s: Int, b: android.os.Bundle?,
                    ) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
                lm.requestLocationUpdates(
                    provider, 0L, 0f, listener,
                    android.os.Looper.getMainLooper(),
                )
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun readLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return null
        val candidates = listOfNotNull(
            if (fine) android.location.LocationManager.GPS_PROVIDER else null,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
        )
        for (p in candidates) {
            runCatching {
                if (lm.isProviderEnabled(p)) {
                    val loc = lm.getLastKnownLocation(p)
                    if (loc != null) return loc
                }
            }
        }
        return null
    }

    private fun buildAlertMessage(trigger: SOSTrigger, location: Location?): String {
        val name = AegisApp.instance.identity.shortId
        val lat = location?.latitude
        val lng = location?.longitude
        // `[aegis:sos]` prefix so the receiver's SimpleXTransport
        // classifier re-tags MessageType.SOS and the chat layer
        // skips storing it as a regular message.
        //
        // No emoji in the wire prefix: the
        // 🚨 glyph encodes as 4 bytes in UTF-8 and a transport that
        // mangles multi-byte sequences (older Android encoders,
        // narrow-pipe links) could corrupt the prefix and break
        // SimpleXTransport's startsWith("[aegis:sos]") classifier.
        // Plain ASCII "SOS —" is bulletproof. UI surfaces still
        // render the 🚨 (notification title, dashboard) because those
        // are local rendering, not transport.
        return buildString {
            append("[aegis:sos]")
            append("SOS ")
            append(trigger.name)
            append(" ")
            append(name)
            if (lat != null && lng != null) {
                append("\n%.5f, %.5f".format(lat, lng))
                append("\nhttps://www.openstreetmap.org/?mlat=$lat&mlon=$lng")
            }
        }
    }

    /**
     * Fan-out to every paired peer. Reads the live peer list from the
     * repository, same source as audio chunk fan-out, so a peer added
     * via SimpleX gets every sos surface (alert + audio + cancellation).
     *
     * Called from both suspend contexts (snapshotLocationAndBroadcast,
     * re-broadcast loop) and non-suspend contexts (cancel()), so the
     * actual send launches into [scope].
     */
    /**
     * Ongoing notification on the SOS-active phone itself with a
     * single "STOP SOS" action wired to SOSCancelOwnReceiver.
     * Channel is CHANNEL_SOS (priority MAX, alarm category) so it
     * surfaces over a locked screen. Distinct from the receive-side
     * banner (SOS_NOTIF_ID = 1000) — uses OWN_SOS_NOTIF_ID = 1001
     * so the receiver-side dismiss action can't accidentally wipe
     * the sender's stop affordance.
     */
    private fun postOwnSOSNotification(trigger: SOSTrigger) {
        val ctx = context
        val cancelPi = android.app.PendingIntent.getBroadcast(
            ctx,
            0,
            android.content.Intent(app.aether.aegis.SOSCancelOwnReceiver.ACTION_CANCEL_OWN)
                .setPackage(ctx.packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = androidx.core.app.NotificationCompat.Action.Builder(
            app.aether.aegis.R.drawable.ic_notif_shield,
            "STOP SOS",
            cancelPi,
        ).build()

        // Tapping the notification body opens MainActivity → home
        // tab → SOSScreen renders the live broadcast panel.
        val openIntent = ctx.packageManager
            .getLaunchIntentForPackage(ctx.packageName)
            ?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        val openPi = openIntent?.let {
            android.app.PendingIntent.getActivity(
                ctx, 0, it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notif = androidx.core.app.NotificationCompat.Builder(
            ctx, AegisApp.CHANNEL_SOS,
        )
            .setContentTitle("SOS ACTIVE")
            .setContentText(
                "Triggered by ${trigger.name.lowercase()}. " +
                    "Tap STOP to silence or open Aegis to see the broadcast.",
            )
            .setStyle(
                androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                    "Aegis is broadcasting your location + audio to your contacts. " +
                        "Trigger: ${trigger.name.lowercase()}. Tap STOP to end the " +
                        "sos and stop all broadcasts."
                )
            )
            .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_shield)
            .setColor(AegisApp.BRAND_SOS_ARGB)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVibrate(longArrayOf(0, 200, 100, 200))
            .addAction(stopAction)
            .apply {
                openPi?.let {
                    setContentIntent(it)
                    setFullScreenIntent(it, true)
                }
            }
            .build()
        if (androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            androidx.core.app.NotificationManagerCompat.from(ctx)
                .notify(app.aether.aegis.SOSCancelOwnReceiver.OWN_SOS_NOTIF_ID, notif)
        }
    }

    private fun broadcast(content: String, type: MessageType) {
        val pm = AegisApp.instance.protocolManager
        val selfKey = AegisApp.instance.identity.deviceId
        scope.launch {
            runCatching {
                // SOS → Trusted ∪ Emergency.
                // Untrusted is excluded by construction — that tier
                // exists precisely to opt people out of the alert.
                AegisApp.instance.repository.sosTargets().forEach { peer ->
                    if (peer.publicKey == selfKey) return@forEach
                    // The alert + cancel (MessageType.SOS) are life-safety
                    // traffic: send them via the SOS path, which races
                    // SimpleX + LAN in parallel (LAN is sub-second when
                    // co-located) and retries fast if the transport is
                    // momentarily down — instead of the priority-order,
                    // drop-and-wait-30 s behaviour of sendMessage. The
                    // interleaved LOCATION pings stay on the normal path:
                    // they're frequent and best-effort, so amplifying them
                    // would just add traffic for no safety gain.
                    runCatching {
                        if (type == MessageType.SOS) {
                            pm.sendSos(peer.publicKey, content, type)
                        } else {
                            pm.sendMessage(peer.publicKey, content, type)
                        }
                    }
                }
            }
        }
    }

    private fun startAudioRecording() {
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return

        recordedSegments.clear()
        startNewAudioSegment()

        // Rotate every 60s and SHIP the completed segment as a voice
        // message to every paired peer. The hybrid spec — live stream
        // gives family a real-time listen, these chunks give them a
        // permanent evidence record even if the phone gets smashed
        // before the next rotation lands.
        audioRotationJob = scope.launch {
            // First segment ships FAST (15 s) so a short sos still
            // produces evidence — pre-fix the loop only rotated at
            // t=60s, so if the victim hit Cancel within the first
            // minute, nothing audio-bearing made it out. After the
            // initial short window, settle into the standard 60-s
            // rotation cadence.
            var firstRotation = true
            while (isActive && _state.value != null) {
                val window = if (firstRotation) FIRST_SEGMENT_MS else SEGMENT_DURATION_MS
                firstRotation = false
                delay(window)
                if (_state.value == null) return@launch
                val completed = rotateAudioSegment() ?: continue
                shipAudioSegment(completed)
            }
        }
    }

    private fun startNewAudioSegment() {
        runCatching {
            val outputFile = File(context.cacheDir, "sos_audio_${System.currentTimeMillis()}.m4a")
            val rec = (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION") MediaRecorder()
                }
                ).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            // Only register the file as the live segment AFTER start() succeeds,
            // so audioPath() (and the SOS UI's "Audio: recording" line) report a
            // recording ONLY when the mic FGS was actually claimed. start()
            // throws on Android 14+ when a background-triggered SOS can't hold
            // the microphone FGS type — previously recordingFile was set BEFORE
            // start(), so a failed start still showed "recording" (dishonest;
            // #33). On failure we now leave recordingFile null → UI shows "no mic".
            recorder = rec
            recordingFile = outputFile
            recordedSegments.add(outputFile)
        }.onFailure {
            recorder = null
            recordingFile = null
            android.util.Log.w("SOSHandler", "SOS audio segment failed to start (mic FGS unavailable?)", it)
        }
    }

    /** Stops the current segment, returns its File (now closed + flushed),
     *  and starts the next one. Null if no segment was active. */
    private fun rotateAudioSegment(): File? {
        val completed = recordingFile
        // Close the CURRENT MediaRecorder only — do NOT call
        // stopAudioRecording here, which cancels audioRotationJob
        // (= the very loop calling rotateAudioSegment, killing it
        // before the second rotation could fire). Net effect was
        // exactly one 60-s segment per sos, then silence; the
        // bug surfaced as "voice recording is being sent, but it's
        // empty" when MediaRecorder.stop() ran while a rotation
        // tick was queued and the file didn't have time to flush.
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        startNewAudioSegment()
        return completed
    }

    /**
     * Auto-initiate a WebRTC audio call to the highest-priority paired
     * peer so they can listen in real time. Best-effort; if the peer
     * is offline / doesn't auto-answer, the chunks (which fan out to
     * every peer) remain the durable record.
     *
     * Two PowerBudget gates layered on top:
     *   - shouldRunMicStream (≤25%): below this, no live stream at all
     *     (recorded chunks still ship). Above it, mic is on.
     *   - shouldRunCameraStream (≤40%): camera adds itself on top of
     *     mic when the budget allows. Camera is the heaviest sos
     *     subsystem and falls out first.
     */
    private suspend fun startLiveStream() {
        val budget = AegisApp.instance.powerBudget
        budget.refresh()
        if (!budget.shouldRunMicStream()) return
        // Live stream goes to one sos-eligible peer. Trusted ∪
        // Emergency only — Untrusted contacts are never recipients.
        val peer = AegisApp.instance.repository.sosTargets()
            .sortedByDescending { it.pinned }
            .firstOrNull() ?: return
        val withVideo = budget.shouldRunCameraStream()
        val stream = app.aether.aegis.call.SOSLiveStream(context)
        liveStream = stream
        stream.start(
            peerPubkey = peer.publicKey,
            peerName = peer.displayName,
            withVideo = withVideo,
        )
    }

    /**
     * Ship a completed 60 s segment to every paired peer as a voice
     * message tagged `[aegis:sos-audio]` so receivers can route it
     * to a dedicated sos-stream UI if they want (and so it can't be
     * confused with a normal voice message). Best-effort — failures
     * here don't block the next rotation.
     *
     * PowerBudget gate: sos audio chunks stop entirely at ≤15 % so
     * the last 15 % is reserved for pure location pings. The local
     * recording still happens (cheap — the mic was already on), it
     * just doesn't get uploaded.
     */
    private fun shipAudioSegment(file: File) {
        val budget = AegisApp.instance.powerBudget
        budget.refresh()
        if (!budget.shouldShipAudioChunks()) return
        scope.launch {
            runCatching {
                val simplex = AegisApp.instance.transports
                    .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                    .firstOrNull() ?: return@launch
                // Audio chunks ride the same recipient list as the
                // SOS text: Trusted ∪ Emergency, no Untrusted.
                val peers = AegisApp.instance.repository.sosTargets()
                for (peer in peers) {
                    runCatching {
                        simplex.sendFileToContact(
                            peerPubkey = peer.publicKey,
                            filePath = file.absolutePath,
                            isImage = false,
                            caption = "[aegis:sos-audio] ${file.name}",
                            // Duress audio to responders — forensic
                            // channel, exempt from the metadata scrub.
                            forensic = true,
                        )
                    }
                }
            }
        }
    }

    private fun stopAudioRecording(keepSegments: Boolean = false) {
        audioRotationJob?.cancel()
        audioRotationJob = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        if (!keepSegments) recordedSegments.clear()
    }

    /**
     * Public hook for call-paths to evict whatever this handler is
     * holding from the microphone. Stops the current MediaRecorder
     * segment if any (Aegis-side mic holder) and tears down the
     * sos-stream WebView. Keeps the sos state intact — sos
     * remains armed; we just temporarily yield audio capture so the
     * call's WebRTC pipeline can grab the mic.
     *
     * Why this exists: the WebRTC engine's getUserMedia fails with
     * "couldn't start audio source" when AudioRecord is already
     * exclusively held by another process — including our own
     * MediaRecorder. Forcing a release here drops the conflict.
     */
    fun releaseMicForCall() {
        stopAudioRecording(keepSegments = true)
        runCatching { liveStream?.stop() }
    }

    fun audioPath(): String? = recordingFile?.absolutePath

    fun shutdown() {
        cancel()
        scope.cancel()
    }

    private companion object {
        /** How long each sos-audio chunk runs before being shipped.
         *  60 s is short enough that a smashed-phone scenario only loses
         *  one in-flight segment, long enough that we're not paying the
         *  SimpleX message-overhead tax every few seconds. */
        private const val SEGMENT_DURATION_MS = 60_000L
        /** First sos-audio segment ships fast so a short sos
         *  (cancel inside the first minute) still produces a
         *  non-empty file for responders. After this initial
         *  window the loop settles into the 60-s rotation.
         *  5 s (was 15): on a snatch/duress the thief can hard-kill the
         *  phone at the PMIC level with a ~10 s power hold and no software
         *  override, so the first audio must be on the wire well before
         *  that — get the evidence out, then settle into the rotation. */
        private const val FIRST_SEGMENT_MS = 5_000L
    }
}

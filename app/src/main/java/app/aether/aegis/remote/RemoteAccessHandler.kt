package app.aether.aegis.remote

import app.aether.aegis.AegisApp
import app.aether.aegis.core.MessageType
import app.aether.aegis.lock.LockStore
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Target-side dispatcher for `[aegis:remote]` packets.
 *
 *   AUTH   → verify PIN against [LockStore]. Only [LockStore.PinMatch.REAL]
 *            grants access; duress and invalid alike trigger
 *            failure-counter + notification + possible auto-revoke.
 *            On success: open a session, immediately fire LOCATE
 *            (lock + GPS) and return both the sid and the locate
 *            result in AUTH_OK.
 *   LOCATE → validate sid, re-fire lock + GPS, return LOCATE_RESULT.
 *   SIREN  → validate sid, fire siren, ACK.
 *   WIPE   → validate sid, broadcast WIPED to all contacts, fire
 *            factory reset (Device-Owner only).
 *
 * The handler also dispatches sender-side responses (AUTH_OK,
 * AUTH_DENIED, LOCATE_RESULT, OK, ERR, REVOKED) into
 * [RemoteAccessSession]. Same module so wire-format knowledge stays
 * in one place.
 */
object RemoteAccessHandler {

    private const val TAG = "RemoteAccess"

    // Per-peer cooldowns on the destructive /
    // expensive commands. A compromised peer (or a misbehaving sender
    // bug) could spam LOCATE and drain the target battery via repeated
    // GPS+camera acquisitions, or SIREN/SIREN_OFF flip-flop. WIPE is
    // gated separately by re-verifying this device-owner's app PIN on
    // the packet (see handleWipe), not by a timestamp. Map is keyed
    // (fromKey → cmd → lastFiredAtMs) and never bloats — sessions
    // auto-close so old entries become unreachable; we trim
    // opportunistically below.
    private val lastFired = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, Long>>()

    private const val LOCATE_COOLDOWN_MS = 30_000L
    private const val SIREN_COOLDOWN_MS  = 60_000L
    private const val UPDATE_COOLDOWN_MS = 5L * 60_000L
    private const val LISTEN_COOLDOWN_MS = 15_000L

    /** Returns true if [fromKey] is allowed to fire [cmd] right now;
     *  records the timestamp on success. False means rate-limited —
     *  caller should sendErr "rate_limited" and return. */
    private fun rateLimitAllow(fromKey: String, cmd: String, cooldownMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val perPeer = lastFired.getOrPut(fromKey) { java.util.concurrent.ConcurrentHashMap() }
        val last = perPeer[cmd] ?: 0L
        if (now - last < cooldownMs) return false
        perPeer[cmd] = now
        return true
    }

    /** Tear down any live camera / mic stream owned by [senderKey]. Call when
     *  a sender is REVOKED so revocation actually cuts an in-flight stream
     *  instead of letting it run until the 5-min idle timeout — a revoked peer
     *  must not keep eavesdropping. (Auto-revoke can't coincide with a live
     *  stream: it fires on failed AUTH, which never holds a session.) */
    fun stopStreamsFor(senderKey: String) {
        if (RemoteLiveCamera.senderKey() == senderKey) RemoteLiveCamera.stop()
        if (RemoteLiveMic.senderKey() == senderKey) RemoteLiveMic.stop()
    }

    suspend fun handle(fromKey: String, body: String) {
        val pkt = RemoteAccessProtocol.decode(body) ?: run {
            Log.w(TAG, "malformed packet from $fromKey")
            return
        }
        when (pkt.kind) {
            // ---- Inbound (target side) ----
            RemoteAccessProtocol.KIND_AUTH   -> handleAuth(fromKey, pkt)
            RemoteAccessProtocol.KIND_LOCATE -> handleLocate(fromKey, pkt)
            RemoteAccessProtocol.KIND_SIREN  -> handleSiren(fromKey, pkt)
            RemoteAccessProtocol.KIND_SIREN_OFF -> handleSirenOff(fromKey, pkt)
            RemoteAccessProtocol.KIND_WIPE   -> handleWipe(fromKey, pkt)
            RemoteAccessProtocol.KIND_REMOTE_SOS -> handleRemoteSos(fromKey, pkt)
            RemoteAccessProtocol.KIND_UPDATE -> handleUpdate(fromKey, pkt)
            RemoteAccessProtocol.KIND_LISTEN -> handleListen(fromKey, pkt)
            RemoteAccessProtocol.KIND_DISPLAY -> handleDisplay(fromKey, pkt)
            RemoteAccessProtocol.KIND_LIVE_CAM_START -> handleLiveCamStart(fromKey, pkt)
            RemoteAccessProtocol.KIND_LIVE_CAM_FLIP  -> handleLiveCamFlip(fromKey, pkt)
            RemoteAccessProtocol.KIND_LIVE_CAM_STOP  -> handleLiveCamStop(fromKey, pkt)
            RemoteAccessProtocol.KIND_LIVE_MIC_START -> handleLiveMicStart(fromKey, pkt)
            RemoteAccessProtocol.KIND_LIVE_MIC_STOP  -> handleLiveMicStop(fromKey, pkt)
            RemoteAccessProtocol.KIND_RING     -> handleRing(fromKey, pkt)
            RemoteAccessProtocol.KIND_RING_OFF -> handleRingOff(fromKey, pkt)
            RemoteAccessProtocol.KIND_SNAPSHOT -> handleSnapshot(fromKey, pkt)
            RemoteAccessProtocol.KIND_PING     -> handlePing(fromKey, pkt)
            RemoteAccessProtocol.KIND_WATCH_PAUSE  -> handleWatchPause(fromKey, pkt)
            RemoteAccessProtocol.KIND_WATCH_RESUME -> handleWatchResume(fromKey, pkt)
            RemoteAccessProtocol.KIND_EXIT   -> handleExit(fromKey, pkt)
            // ---- Inbound (sender side — responses to OUR earlier sends) ----
            RemoteAccessProtocol.KIND_AUTH_OK       -> onAuthOk(fromKey, pkt)
            RemoteAccessProtocol.KIND_AUTH_DENIED   -> onAuthDenied(fromKey)
            RemoteAccessProtocol.KIND_LOCATE_RESULT -> onLocateResult(fromKey, pkt)
            RemoteAccessProtocol.KIND_WATCH_TICK    -> onLocateResult(fromKey, pkt)
            RemoteAccessProtocol.KIND_LISTEN_RESULT -> onListenResult(fromKey, pkt)
            RemoteAccessProtocol.KIND_OK            -> {
                // Most OKs are best-effort no-ops. The wipe OK carries a human
                // status in [msg] ("wiped" / "aegis-wiped") — surface it to the
                // operator (via the same channel as errors) so they learn what
                // actually happened to the target.
                if (pkt.forCmd == "wipe") {
                    pkt.msg?.let { RemoteAccessSession.recordError(fromKey, "wipe", it) }
                }
            }
            RemoteAccessProtocol.KIND_ERR           -> onErr(fromKey, pkt)
            RemoteAccessProtocol.KIND_REVOKED       -> onRevoked(fromKey)
            RemoteAccessProtocol.KIND_DURESS_SOS    -> onDuressSos(fromKey)
            RemoteAccessProtocol.KIND_UNREVOKED     -> onUnrevoked(fromKey)
            RemoteAccessProtocol.KIND_PONG          -> onPong(fromKey, pkt)
            RemoteAccessProtocol.KIND_SENTINEL_EVENT -> onSentinelEvent(fromKey, pkt)
            RemoteAccessProtocol.KIND_SENTINEL_DRILL_ACK -> onSentinelDrillAck(fromKey, pkt)
            else -> Log.w(TAG, "unknown kind ${pkt.kind} from $fromKey")
        }
    }

    // ---------- Target-side handlers ----------

    private suspend fun handleAuth(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val aegisApp = AegisApp.instance
        val gate = aegisApp.remoteAccessGate

        // ConnectionLog the receipt so the user can see on the
        // target's diag log that the AUTH arrived. From the requester
        // side ALL four no-response causes (silently revoked, offline,
        // SimpleX dropped, aegisApp crashed) look identical; the target's
        // diag log is the only place you can tell them apart.
        app.aether.aegis.simplex.ConnectionLog.log(
            TAG,
            "AUTH received from ${fromKey.take(12)}…",
        )

        // Already revoked → swallow silently. We don't even tell the
        // sender it was denied; from their UI it just looks like a
        // timeout. Discourages probing. The ConnectionLog entry below
        // is the one place the user can confirm "yes my packet
        // arrived, it was dropped because revoked" when debugging
        // their own self-paired setup.
        if (gate.isRevoked(fromKey)) {
            Log.i(TAG, "auth from revoked $fromKey — dropping")
            app.aether.aegis.simplex.ConnectionLog.warn(
                TAG,
                "AUTH dropped — sender ${fromKey.take(12)}… is in gate.revoked",
            )
            return
        }

        val pin = pkt.pin
        val match = pin?.let { LockStore(aegisApp).verifyPin(it) }
        // Duress trap (SPEC remote-duress): a DURESS PIN supplied to remote
        // auth means someone is being coerced into opening a session against
        // THIS device. Don't just deny it — INSTANT-revoke the sender so even a
        // later correct PIN can't open a session, and tear down anything live.
        // The response is indistinguishable from a wrong PIN (AUTH_DENIED), so
        // the coercer can't tell a duress trip from a typo. (The operator's own
        // duress PIN is trapped operator-side, firing their silent SOS; this is
        // the target-side half — its own duress code refusing + revoking.)
        if (match == LockStore.PinMatch.DURESS_1 || match == LockStore.PinMatch.DURESS_2) {
            gate.revoke(fromKey)
            app.aether.aegis.simplex.ConnectionLog.warn(
                TAG, "AUTH duress PIN from ${fromKey.take(12)}… — instant revoke + duress SOS",
            )
            // Tell the coerced operator's phone to raise its own SILENT SOS —
            // the AUTH-prompt half of duress, mirroring the WIPE-prompt path.
            // Sent FIRST so it isn't lost if the revoke tears anything down. The
            // operator honours it via a recent-auth check (no session exists
            // yet — the auth itself was the duress), so the SOS still fires.
            send(fromKey, RemoteAccessProtocol.Packet(kind = RemoteAccessProtocol.KIND_DURESS_SOS))
            send(fromKey, RemoteAccessProtocol.Packet(kind = RemoteAccessProtocol.KIND_AUTH_DENIED))
            send(fromKey, RemoteAccessProtocol.Packet(kind = RemoteAccessProtocol.KIND_REVOKED))
            return
        }
        if (match != LockStore.PinMatch.REAL) {
            val tripped = gate.recordFailure(fromKey)
            aegisApp.notifyRemoteAccessAttempt(fromKey, tripped)
            app.aether.aegis.simplex.ConnectionLog.warn(
                TAG,
                "AUTH denied — wrong PIN from ${fromKey.take(12)}…" +
                    if (tripped) " (auto-revoke tripped)" else "",
            )
            send(fromKey, RemoteAccessProtocol.Packet(kind = RemoteAccessProtocol.KIND_AUTH_DENIED))
            if (tripped) {
                send(fromKey, RemoteAccessProtocol.Packet(kind = RemoteAccessProtocol.KIND_REVOKED))
            }
            return
        }
        // Per-contact remote-access grant. The
        // correct PIN is necessary but NOT sufficient: the sender must also
        // have been granted remote access at Trusted promotion (or later from
        // contact detail). Fail CLOSED if not — a contact who somehow learns
        // the PIN but was never granted control still can't drive the device.
        // Treated exactly like a wrong PIN (AUTH_DENIED + failure count) so
        // the sender can't distinguish "wrong PIN" from "not authorised",
        // which would otherwise confirm the PIN was right.
        val granted = runCatching {
            aegisApp.repository.knownPeerByKey(fromKey)?.remoteAccessEnabled
        }.getOrNull() == true
        if (!granted) {
            val tripped = gate.recordFailure(fromKey)
            aegisApp.notifyRemoteAccessAttempt(fromKey, tripped)
            app.aether.aegis.simplex.ConnectionLog.warn(
                TAG,
                "AUTH denied — ${fromKey.take(12)}… has no remote-access grant" +
                    if (tripped) " (auto-revoke tripped)" else "",
            )
            send(fromKey, RemoteAccessProtocol.Packet(kind = RemoteAccessProtocol.KIND_AUTH_DENIED))
            if (tripped) {
                send(fromKey, RemoteAccessProtocol.Packet(kind = RemoteAccessProtocol.KIND_REVOKED))
            }
            return
        }
        app.aether.aegis.simplex.ConnectionLog.log(
            TAG,
            "AUTH OK — opening session for ${fromKey.take(12)}…",
        )

        // Real PIN. Clear the failure counter (legitimate user just
        // logged in), open a session, fire LOCATE in the same trip.
        gate.clearFailures(fromKey)
        val sid = gate.openSession(fromKey)
        // NB: the Remote Operator badge is intentionally NOT earned here.
        // This is the TARGET side — the device being accessed. The badge
        // belongs to the OPERATOR (the device that authenticated INTO
        // this one), so it's unlocked in onAuthOk() where the sender
        // processes the AUTH_OK reply. Awarding it here credited the
        // wrong party — the phone that got remotely accessed, not the
        // one doing the accessing.
        // Send AUTH_OK IMMEDIATELY — just sid + lockOk. The previous
        // path did fireLocate (up to 20 s GPS timeout) + readLatestMugshotB64
        // (5–10 s firing both cameras) BEFORE replying, so the sender
        // sat on "Authenticating…" for 30+ s. Now they see access
        // granted within one SMP round-trip; location + mugshot
        // arrive seconds later via the watch-tick fan-out.
        val battSnap = readBatterySnapshot()
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_AUTH_OK,
                sid = sid,
                ts = System.currentTimeMillis(),
                lockOk = app.aether.aegis.admin.AdminGate.isActive(aegisApp),
                batteryPct = battSnap.first,
                charging = battSnap.second,
            ),
        )
        // Kick off continuous watch mode — every WATCH_INTERVAL_MS
        // the target now pushes a fresh location + dual-lens
        // mugshot so the owner's UI shows a live trail without the
        // sender having to spam Locate. RemoteWatchMode.start has
        // been amended to fire the first tick IMMEDIATELY rather
        // than after WATCH_INTERVAL_MS, so the sender's UI gets a
        // fresh fix + mugshot within a few seconds of AUTH_OK
        // instead of waiting 25 s for the first watch cycle.
        RemoteWatchMode.start(fromKey)
        // Spec: success is SILENT on the target. No toast, no banner,
        // no notification — owner is presumed to have entered their
        // own PIN on a borrowed phone after a theft.
    }

    private suspend fun handleLocate(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "locate", "missing sid")
        val sessionSender = gate.validateSession(sid)
        if (sessionSender != fromKey) {
            return sendErr(fromKey, "locate", "no session")
        }
        if (!rateLimitAllow(fromKey, "locate", LOCATE_COOLDOWN_MS)) {
            return sendErr(fromKey, "locate", "rate_limited")
        }
        // Pre-check the permissions LOCATE depends on so the sender
        // gets a typed error toast instead of a silently-blank
        // LOCATE_RESULT. Lock requires Device Admin; GPS requires
        // ACCESS_FINE_LOCATION (or COARSE — we accept either).
        val ctx = AegisApp.instance
        val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasAdmin = app.aether.aegis.admin.AdminGate.isActive(ctx)
        if (!hasLocation && !hasAdmin) {
            return sendErr(fromKey, "locate", "needs_location_and_admin")
        }
        if (!hasLocation) {
            return sendErr(fromKey, "locate", "needs_location_permission")
        }
        // Lock-without-admin is a partial result — the GPS fix is
        // still useful even if the screen didn't lock. Surface via
        // lockOk on LOCATE_RESULT rather than failing the whole
        // command.
        val (lat, lng) = RemoteCommandHandler.fireLocate()
        val battSnap = readBatterySnapshot()
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_LOCATE_RESULT,
                lat = lat,
                lng = lng,
                ts = System.currentTimeMillis(),
                mugshotB64 = readLatestMugshotB64(),
                lockOk = hasAdmin,
                batteryPct = battSnap.first,
                charging = battSnap.second,
            ),
        )
    }

    /** Battery percent + charging state from PowerBudget, cheap to
     *  read (sticky intent). Surfaced on every meta-payload so the
     *  sender always knows the target's juice level. */
    private fun readBatterySnapshot(): Pair<Int?, Boolean?> {
        return runCatching {
            val pb = AegisApp.instance.powerBudget
            pb.refresh()
            pb.level.value to pb.charging.value
        }.getOrElse { null to null }
    }

    private suspend fun handleListen(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "listen", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "listen", "no session")
        }
        if (!rateLimitAllow(fromKey, "listen", LISTEN_COOLDOWN_MS)) {
            return sendErr(fromKey, "listen", "rate_limited")
        }
        // Pre-check RECORD_AUDIO so the sender gets a typed error
        // instead of a generic 'capture_failed' (which currently
        // also masks codec / encoder failures).
        val ctx = AegisApp.instance
        val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            return sendErr(fromKey, "listen", "needs_microphone_permission")
        }
        val seconds = pkt.seconds ?: 10
        val file = RemoteCommandHandler.captureMicAudio(seconds)
        if (file == null) {
            // Honest reason: a background-triggered capture can't claim the mic
            // FGS on Android 14+ (#8/#33). Distinguish that from a real
            // codec/hardware fault so the operator knows to retry with the
            // screen on rather than thinking the mic is broken.
            sendErr(
                fromKey, "listen",
                if (RemoteCommandHandler.captureForegroundEligible()) "capture_failed"
                else "capture_unavailable_background",
            )
            return
        }
        // Cap at ~600 KB raw bytes to keep the JSON envelope
        // manageable. 10 s @ 32 kbps mono AAC ≈ 40 KB so this is
        // very generous; covers the 30 s upper bound with margin.
        if (file.length() > 600_000) {
            sendErr(fromKey, "listen", "too_large")
            return
        }
        val b64 = runCatching {
            android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return sendErr(fromKey, "listen", "encode_failed")
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_LISTEN_RESULT,
                seconds = seconds,
                audioB64 = b64,
                ts = System.currentTimeMillis(),
            ),
        )
    }

    private fun handleExit(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid
        if (sid != null) gate.closeSession(sid) else gate.closeAllForSender(fromKey)
        RemoteWatchMode.stop(fromKey)
        // If a live-cam stream is still attached for this sender, tear
        // it down too — otherwise the hidden WebView keeps the camera
        // active for the full session TTL after the sender's UI exited.
        if (RemoteLiveCamera.senderKey() == fromKey) {
            RemoteLiveCamera.stop()
        }
        if (RemoteLiveMic.senderKey() == fromKey) {
            RemoteLiveMic.stop()
        }
        // Ringer isn't sender-scoped (it's a single device tone) —
        // session close from any sender silences it so the device
        // stops chirping after a remote session ends.
        Ringer.stop()
        // No reply — sender already moved on.
    }

    private suspend fun handleLiveCamStart(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "live_cam", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "live_cam", "no session")
        }
        // Camera is the gate. RECORD_AUDIO is also useful (one-way
        // listen-in while watching) but optional — getUserMedia falls
        // back to video-only if the mic is denied. The auto-grant pass
        // covers both when Aegis is Device Owner.
        val ctx = AegisApp.instance
        val hasCamera = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCamera) {
            return sendErr(fromKey, "live_cam", "needs_camera_permission")
        }
        // Resolve a human-readable name for the sender so CallManager's
        // bookkeeping has something to log. Falls back to a pubkey
        // prefix when the peer row is missing.
        val name = runCatching {
            AegisApp.instance.repository.knownPeerByKey(fromKey)?.displayName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fromKey.take(12)
        RemoteLiveCamera.start(fromKey, name, pkt.lens)
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "live_cam_start",
                msg = "stream_started",
            ),
        )
    }

    private fun handleLiveCamFlip(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "live_cam", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "live_cam", "no session")
        }
        // Only flip if THIS sender's stream is the one currently
        // attached — guards against a second authorised peer racing a
        // flip onto an active stream owned by someone else.
        if (RemoteLiveCamera.senderKey() != fromKey) {
            return sendErr(fromKey, "live_cam", "no_stream")
        }
        runCatching { app.aether.aegis.call.CallManager.flipCamera() }
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "live_cam_flip",
                msg = "flipped",
            ),
        )
    }

    private fun handleLiveCamStop(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "live_cam", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "live_cam", "no session")
        }
        if (RemoteLiveCamera.senderKey() == fromKey) {
            RemoteLiveCamera.stop()
        }
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "live_cam_stop",
                msg = "stream_stopped",
            ),
        )
    }

    private suspend fun handleLiveMicStart(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "live_mic", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "live_mic", "no session")
        }
        val ctx = AegisApp.instance
        val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            return sendErr(fromKey, "live_mic", "needs_microphone_permission")
        }
        val name = runCatching {
            AegisApp.instance.repository.knownPeerByKey(fromKey)?.displayName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fromKey.take(12)
        RemoteLiveMic.start(fromKey, name)
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "live_mic_start",
                msg = "stream_started",
            ),
        )
    }

    private fun handleLiveMicStop(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "live_mic", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "live_mic", "no session")
        }
        if (RemoteLiveMic.senderKey() == fromKey) {
            RemoteLiveMic.stop()
        }
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "live_mic_stop",
                msg = "stream_stopped",
            ),
        )
    }

    private fun handleRing(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "ring", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "ring", "no session")
        }
        Ringer.start(AegisApp.instance, pkt.seconds)
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "ring",
                msg = "ringing",
            ),
        )
    }

    private fun handleRingOff(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "ring_off", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "ring_off", "no session")
        }
        Ringer.stop()
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "ring_off",
                msg = "silenced",
            ),
        )
    }

    private suspend fun handleSnapshot(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "snapshot", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "snapshot", "no session")
        }
        val ctx = AegisApp.instance
        val hasCamera = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasCamera) {
            return sendErr(fromKey, "snapshot", "needs_camera_permission")
        }
        val lens = pkt.lens?.takeIf { it == "front" || it == "rear" } ?: "rear"
        val file = runCatching {
            app.aether.aegis.mugshot.MugshotCapture.captureSingleLens(ctx, lens)
        }.getOrNull()
        if (file == null || file.length() == 0L) {
            // Same honest split as listen: a backgrounded/locked target can't
            // claim the camera FGS on Android 14+ (#8), so the "no frame" is the
            // OS blocking background capture, not a broken camera.
            return sendErr(
                fromKey, "snapshot",
                if (RemoteCommandHandler.captureForegroundEligible()) "capture_failed"
                else "capture_unavailable_background",
            )
        }
        if (file.length() > 600_000) {
            return sendErr(fromKey, "snapshot", "too_large")
        }
        val b64 = runCatching {
            android.util.Base64.encodeToString(
                file.readBytes(),
                android.util.Base64.NO_WRAP,
            )
        }.getOrNull() ?: return sendErr(fromKey, "snapshot", "encode_failed")
        // Result piggy-backs on LOCATE_RESULT so the sender's existing
        // FramesRow renderer surfaces the JPEG automatically. Front
        // lens populates the "front" slot, rear populates "rear" — same
        // semantics as the LOCATE return.
        val (frontB64, rearB64) = if (lens == "front") b64 to null else null to b64
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_LOCATE_RESULT,
                mugshotB64 = frontB64,
                rearMugshotB64 = rearB64,
                ts = System.currentTimeMillis(),
                forCmd = "snapshot",
                lens = lens,
            ),
        )
    }

    private fun handleWatchPause(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "watch_pause", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "watch_pause", "no session")
        }
        RemoteWatchMode.stop(fromKey)
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "watch_pause",
                msg = "paused",
            ),
        )
    }

    private fun handleWatchResume(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "watch_resume", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "watch_resume", "no session")
        }
        RemoteWatchMode.start(fromKey)
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "watch_resume",
                msg = "resumed",
            ),
        )
    }

    private fun handlePing(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "ping", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "ping", "no session")
        }
        val battSnap = readBatterySnapshot()
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_PONG,
                ts = System.currentTimeMillis(),
                batteryPct = battSnap.first,
                charging = battSnap.second,
            ),
        )
    }

    private fun handleDisplay(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "display", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "display", "no session")
        }
        val text = pkt.msg ?: ""
        val result = runCatching { RemoteCommandHandler.pushLockscreenMessage(text) }
            .getOrElse { it.message ?: "failed" }
        // pushLockscreenMessage can return 'notifications_disabled'
        // (no POST_NOTIFICATIONS permission or user-disabled in
        // Settings). Used to come back as KIND_OK msg=... which
        // sender's handler treats as a no-op — display looked
        // broken even though the target had refused. Map to
        // KIND_ERR so the sender's UI surfaces it.
        if (result == "notifications_disabled") {
            return sendErr(fromKey, "display", "notifications_disabled")
        }
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "display",
                msg = result,
            ),
        )
    }

    /** Capture a FRESH mugshot for the LOCATE return
     *  ("Lock and Mugshot fire simultaneously"). Front + rear, EXIF
     *  GPS + timestamp burned in, base64-encoded for inline transport.
     *  Caps the encoded JPEG at ~600 KB raw — anything bigger gets
     *  dropped (SimpleX handles larger messages fine but a 5 MB photo
     *  in a JSON envelope is rude). Falls back to the latest on-disk
     *  mugshot when fresh capture fails (no permission, no camera,
     *  another aegisApp holds the camera, etc.). NO_WRAP keeps the
     *  encoding on one line so the JSON parser doesn't choke. */
    private suspend fun readLatestMugshotB64(): String? {
        val ctx = AegisApp.instance
        val fresh = runCatching {
            app.aether.aegis.mugshot.MugshotCapture.captureForRemoteLocate(ctx)
        }.getOrNull()
        val f = fresh ?: RemoteCommandHandler.latestMugshotFile() ?: return null
        if (f.length() > 600_000) return null
        return runCatching {
            android.util.Base64.encodeToString(
                f.readBytes(),
                android.util.Base64.NO_WRAP,
            )
        }.getOrNull()
    }

    private suspend fun handleSiren(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "siren", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "siren", "no session")
        }
        if (!rateLimitAllow(fromKey, "siren", SIREN_COOLDOWN_MS)) {
            return sendErr(fromKey, "siren", "rate_limited")
        }
        val result = runCatching { RemoteCommandHandler.fireSiren() }.getOrElse { it.message ?: "failed" }
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "siren",
                msg = result,
            ),
        )
    }

    private suspend fun handleSirenOff(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "siren_off", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "siren_off", "no session")
        }
        // Share the SIREN cooldown so a sender can't flip-flop on/off
        // every second to provoke the audio pipeline. Stopping a siren
        // the owner just fired (within 60 s) is still allowed via
        // re-AUTH; rate-limit applies to the SIREN_OFF kind only.
        if (!rateLimitAllow(fromKey, "siren_off", SIREN_COOLDOWN_MS)) {
            return sendErr(fromKey, "siren_off", "rate_limited")
        }
        val result = runCatching { RemoteCommandHandler.stopSiren() }
            .getOrElse { it.message ?: "failed" }
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "siren_off",
                msg = result,
            ),
        )
    }

    private suspend fun handleUpdate(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "update", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "update", "no session")
        }
        if (!rateLimitAllow(fromKey, "update", UPDATE_COOLDOWN_MS)) {
            return sendErr(fromKey, "update", "rate_limited")
        }
        // Owner explicitly asked — bypass the routine PowerBudget +
        // wifi-only gates the scheduled worker honours. ACK
        // immediately so the sender's UI doesn't time-out; the
        // download + install happens async in a worker scope.
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "update",
                msg = "downloading",
            ),
        )
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        app.aether.aegis.AegisApp.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val ctx = AegisApp.instance
                val token = app.aether.aegis.update.SecretsStore(ctx).githubToken
                val client = app.aether.aegis.update.UpdateClient(token = token)
                val outcome = client.check(ctx)
                if (outcome !is app.aether.aegis.update.UpdateClient.CheckOutcome.UpdateAvailable) {
                    Log.i(TAG, "remote update: nothing newer to install")
                    return@launch
                }
                val release = outcome.release
                val apk = java.io.File(ctx.filesDir, "update.apk")
                val outcome2 = client.downloadApk(release, apk)
                if (outcome2 !is app.aether.aegis.update.UpdateClient.DownloadOutcome.Ok || !apk.exists()) {
                    Log.w(TAG, "remote update: download failed ($outcome2)")
                    return@launch
                }
                app.aether.aegis.update.UpdateState.set(
                    app.aether.aegis.update.UpdateState.Status.Installing(release),
                )
                app.aether.aegis.update.UpdateInstaller.beginInstall(
                    context = ctx, apk = apk,
                    silent = true, foreground = false,
                )
            }.onFailure { Log.w(TAG, "remote update failed: $it") }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ██  REMOTE WIPE AUTH GATE — DO NOT MODIFY WITHOUT A FULL RE-TEST  ██
    // ════════════════════════════════════════════════════════════════════
    // Authorisation half of the factory-reset path (the actual reset lives in
    // RemoteCommandHandler.fireWipe — see the frozen banner there). The order
    // of these checks is load-bearing and security-critical:
    //   1. valid session for this exact sender (sid match),
    //   2. the operator re-proves THIS device's REAL PIN on the wipe packet
    //      (a warm session is NOT enough to nuke the device),
    //   3. a DURESS PIN traps: refuse + instant-revoke + silent operator SOS,
    //      returning the SAME "needs_reauth" an attacker sees for a wrong PIN,
    //   4. three-tier wipe (announce → flush → wipe): broadcast [aegis:wiped] to
    //      the target's own Trusted ∪ Emergency contacts ONCE up front (true in
    //      every tier — Tier 3 always destroys Aegis data), then attempt a real
    //      factory reset (Tier 1 DO / Tier 2 non-DO admin) and, if it returns
    //      (didn't fire), fall to the Tier-3 Aegis-data wipe. The operator is
    //      told the honest outcome ("wiped" vs "aegis-wiped"). The earlier bug
    //      was the FALSE broadcast (sent before confirming), not the attempt
    //      (issue #25); everything outbound now flushes before the wipe.
    //   5. A real wipe (factory reset OR Tier-3 data clear) never returns; any
    //      return is failure.
    // Re-test on a real device after ANY change here (DO factory reset, non-DO
    // admin reset on stock, and the Tier-3 Aegis-data wipe).
    // ════════════════════════════════════════════════════════════════════
    private suspend fun handleWipe(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "wipe", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "wipe", "no session")
        }
        // WIPE is nuclear. A live session is NOT enough — a session kept
        // warm for an hour through routine LOCATE traffic shouldn't be able
        // to factory-reset the device on its own. The operator must re-prove
        // OUR PIN (this device-owner's own app PIN) on the WIPE packet
        // itself, and we verify it HERE rather than trusting the sender. The
        // sender's ladder collects it fresh at the irreversible step; a wrong
        // PIN comes back as "needs_reauth" and nothing is erased. Only the
        // REAL PIN authorises — a duress PIN deliberately does NOT wipe
        // (the device owner may be entering it under coercion).
        val match = pkt.pin?.let { LockStore(AegisApp.instance).verifyPin(it) }
        // Duress trap (SPEC scenario 2): the operator is coerced into revealing
        // this device's PIN and gives the DURESS code at the wipe prompt. We
        // must NOT wipe, AND we instant-revoke the session — the attacker sees a
        // response identical to a wrong PIN ("needs_reauth") and believes the
        // wipe is just failing, while the device stays untouched and the session
        // dies. (The operator's own duress PIN is trapped operator-side too.)
        if (match == LockStore.PinMatch.DURESS_1 || match == LockStore.PinMatch.DURESS_2) {
            gate.revoke(fromKey)
            app.aether.aegis.simplex.ConnectionLog.warn(
                TAG, "WIPE duress PIN from ${fromKey.take(12)}… — refused + instant revoke",
            )
            // Scenario 2 (SPEC): the operator was coerced into giving THIS
            // device's PIN and entered the duress code. They are the coerced
            // party — tell their phone to raise its own silent SOS. Sent BEFORE
            // the error (and we do NOT send KIND_REVOKED here) so the operator
            // still sees the live session when it arrives and honours the
            // signal. The error then reads as a plain wrong PIN.
            send(fromKey, RemoteAccessProtocol.Packet(kind = RemoteAccessProtocol.KIND_DURESS_SOS))
            return sendErr(fromKey, "wipe", "needs_reauth")
        }
        val pinOk = match == LockStore.PinMatch.REAL
        if (!pinOk) {
            return sendErr(fromKey, "wipe", "needs_reauth")
        }
        // THREE-TIER wipe (see RemoteCommandHandler's frozen banner). The wipe
        // ALWAYS destroys at least Aegis's own data (Tier 3 can't fail), so the
        // [aegis:wiped] contact broadcast is never a lie. EVERYTHING outbound is
        // sent + flushed BEFORE the wipe — a factory reset kills the process and
        // the Aegis-data wipe destroys the SimpleX identity, so nothing can be
        // sent after. Order: announce → flush → wipe.
        //
        // ~1.5s flush window: a fire-and-forget SimpleX send isn't delivery-
        // confirmed, so we give it a beat to actually reach the relay (which then
        // holds it for the recipients) before tearing the transport down.
        val flushMs = 1_500L

        // Contact broadcast — TARGET → its own Trusted ∪ Emergency contacts
        // ("re-invite me"). Sent ONCE, up front: we are certain to destroy Aegis
        // data, so it's true regardless of which tier lands. This is the message
        // that once fired FALSELY; it now only goes out when destruction is
        // guaranteed.
        broadcastWiped()

        // Tier 1/2: attempt a real factory reset if the device can (DO, or an
        // active Device Admin — works on stock Android). fireWipe returns ONLY on
        // failure. Tell the operator "wiped" and let the sends flush; if the
        // reset fires the process dies here with the operator already informed.
        if (RemoteCommandHandler.factoryResetCapable()) {
            send(fromKey, RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK, forCmd = "wipe", msg = "wiped",
            ))
            kotlinx.coroutines.delay(flushMs)
            val res = runCatching { RemoteCommandHandler.fireWipe() }.getOrElse { it.message ?: "failed" }
            // Still alive → factory reset did NOT fire (GrapheneOS / blocked).
            // Fall through to Tier 3 rather than reporting failure.
            Log.w(TAG, "wipe: factory reset did not take ($res) — falling to Aegis-data wipe")
        }

        // Tier 3 floor — destroy Aegis's OWN data (always works, can't fail).
        // Correct the operator to the HONEST outcome (phone intact, Aegis gone)
        // and let it flush before the data wipe tears down the SimpleX identity.
        send(fromKey, RemoteAccessProtocol.Packet(
            kind = RemoteAccessProtocol.KIND_OK, forCmd = "wipe", msg = "aegis-wiped",
        ))
        kotlinx.coroutines.delay(flushMs)
        val res = runCatching { RemoteCommandHandler.wipeAegisData() }.getOrElse { it.message ?: "failed" }
        // wipeAegisData kills the process; reaching here means even Tier 3 didn't
        // tear us down (should not happen). Report the truth.
        Log.w(TAG, "wipe: Tier-3 returned without clearing ($res)")
        return sendErr(fromKey, "wipe", "wipe_failed: $res")
    }

    /**
     * Remote SOS (SPEC remote-duress: the remote SOS button). A trusted
     * operator raises THIS device's SOS on the owner's behalf. Session-gated
     * like every command. Fires a normal (visible) SOS — SOSTrigger.BUTTON —
     * so the owner sees "SOS ACTIVE — STOP" and can cancel a false alarm, and
     * their emergency contacts (including the operator) are alerted. Not a
     * duress path: this is explicit and intentional, no silence.
     */
    private suspend fun handleRemoteSos(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val gate = AegisApp.instance.remoteAccessGate
        val sid = pkt.sid ?: return sendErr(fromKey, "remote_sos", "missing sid")
        if (gate.validateSession(sid) != fromKey) {
            return sendErr(fromKey, "remote_sos", "no session")
        }
        runCatching {
            AegisApp.instance.sosHandler.trigger(app.aether.aegis.core.SOSTrigger.BUTTON)
        }.onFailure { Log.w(TAG, "remote SOS trigger failed", it) }
        send(
            fromKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_OK,
                forCmd = "remote_sos",
            ),
        )
    }

    // ---------- Sender-side response handlers ----------

    private fun onAuthOk(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val sid = pkt.sid ?: return
        // OPERATOR side: we sent AUTH and the target accepted. This phone
        // is the one driving the remote session, so THIS is where the
        // Remote Operator badge is earned (moved here from the target-
        // side handleAuth, which was crediting the accessed device).
        app.aether.aegis.achievements.Achievements.unlock(
            app.aether.aegis.achievements.Achievement.REMOTE_OPERATOR,
        )
        RemoteAccessSession.open(
            peerKey = fromKey,
            sid = sid,
            lat = pkt.lat,
            lng = pkt.lng,
            ts = pkt.ts,
            mugshotB64 = pkt.mugshotB64,
            batteryPct = pkt.batteryPct,
            charging = pkt.charging,
        )
    }

    private fun onAuthDenied(fromKey: String) {
        // No state to mutate — the UI flow polls
        // RemoteAccessSession.isActive() before falling back to its
        // "denied" toast. The denied result is delivered through a
        // separate in-process channel so the PIN sheet knows to dismiss.
        DeniedBus.publish(fromKey)
    }

    private fun onLocateResult(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        RemoteAccessSession.updateLocate(
            peerKey = fromKey,
            lat = pkt.lat,
            lng = pkt.lng,
            ts = pkt.ts,
            mugshotB64 = pkt.mugshotB64,
            rearMugshotB64 = pkt.rearMugshotB64,
            lockOk = pkt.lockOk,
            batteryPct = pkt.batteryPct,
            charging = pkt.charging,
        )
    }

    private fun onListenResult(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val b64 = pkt.audioB64 ?: return
        RemoteAccessSession.updateAudio(fromKey, b64, pkt.ts)
    }

    /**
     * Sender-side handler for typed errors from the target. The
     * legacy path just logged these — user-flagged 2026.05.667
     * 'NOTHING works' because handlers were silently dropping
     * commands that needed permissions the target hadn't granted
     * (notifications_disabled, needs_location_permission,
     * needs_microphone_permission, etc.). Surfacing via
     * [RemoteAccessSession.lastError] so the UI can toast it next
     * to the action button.
     */
    private fun onErr(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val forCmd = pkt.forCmd ?: "unknown"
        val msg = pkt.msg ?: "unknown error"
        Log.w(TAG, "remote err for $forCmd: $msg")
        app.aether.aegis.simplex.ConnectionLog.warn(
            TAG,
            "remote err for $forCmd: $msg (peer=${fromKey.take(20)}…)",
        )
        RemoteAccessSession.recordError(fromKey, forCmd, msg)
    }

    private fun onRevoked(fromKey: String) {
        RemoteAccessSession.markRevokedBy(fromKey)
        AegisApp.instance.notifyRemoteAccessRevokedByPeer(fromKey)
    }

    /**
     * Operator side (SPEC remote-duress, cross-device half). The target tells
     * us a DURESS PIN was supplied at one of ITS remote prompts — meaning WE,
     * the operator, are being coerced — so raise our own SILENT SOS. Honoured
     * ONLY when we have an active operator session with this peer: that ties
     * the signal to a remote operation we actually started (the coerced wipe),
     * so an unsolicited or spoofed frame from a peer we aren't operating can't
     * trip our SOS. Fires SOSTrigger.DURESS — silent, no visible tell.
     */
    private fun onDuressSos(fromKey: String) {
        // Honour when we have an active session (the WIPE-duress case) OR we
        // recently SENT an auth to this peer (the AUTH-duress case, where the
        // auth itself was the duress so no session ever opened). Either proves
        // WE initiated the operation, keeping the anti-spoof guarantee: a peer
        // we aren't operating can't trip our SOS with an unsolicited frame.
        val active = RemoteAccessSession.sessions.value.containsKey(fromKey)
        val recentAuth = RemoteAccessSession.recentlyAttemptedAuth(fromKey)
        if (!active && !recentAuth) {
            app.aether.aegis.simplex.ConnectionLog.warn(
                TAG, "duress_sos from ${fromKey.take(12)}… ignored — no session or recent auth",
            )
            return
        }
        app.aether.aegis.simplex.ConnectionLog.warn(
            TAG, "duress_sos from ${fromKey.take(12)}… — raising silent SOS",
        )
        runCatching {
            AegisApp.instance.sosHandler.trigger(app.aether.aegis.core.SOSTrigger.DURESS)
        }.onFailure { Log.w(TAG, "duress_sos: SOS trigger failed", it) }
    }

    /** Inbound counterpart to [broadcastUnrevoked] — the target just
     *  toggled their "block this peer" off. Clear our sticky cached
     *  flag so the UI returns to the normal idle state without
     *  requiring a manual retry. Idempotent on already-clear state. */
    private fun onUnrevoked(fromKey: String) {
        RemoteAccessSession.clearRevokedBy(fromKey)
    }

    private fun onPong(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        RemoteAccessSession.updatePong(fromKey, pkt.batteryPct, pkt.charging)
    }

    /** Inbound sentinel-cascade notification from a peer who has us on
     *  their sentinel notify-list. Decodes optional 3D-model recording
     *  + mugshot, persists everything to the local SentinelInbox, fires
     *  an Android notification. Silent — no sound, no full-screen
     *  intent — matches the spec's "no alarm, ever" rule on both
     *  sender AND receiver ends.
     *
     *  Drill events (msg starts with "[DRILL] ") are stored with the
     *  drill flag set so the inbox UI can surface the "Confirm
     *  receipt" affordance. The notification text changes to make
     *  the drill nature obvious to the recipient. */
    private suspend fun onSentinelEvent(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val ts = pkt.ts ?: System.currentTimeMillis()
        val rawMsg = pkt.msg ?: "unknown"
        val isDrill = rawMsg.startsWith("[DRILL]")
        val stage = if (isDrill) rawMsg.removePrefix("[DRILL]").trim() else rawMsg
        val recording = pkt.audioB64?.let {
            runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull()
        }
        val mugshot = pkt.mugshotB64?.let {
            runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull()
        }
        val ctx = AegisApp.instance
        runCatching {
            app.aether.aegis.sentinel.SentinelInbox(ctx).append(
                timestampMs = ts,
                fromPeerKey = fromKey,
                stage = stage,
                batteryPct = pkt.batteryPct,
                recordingBytes = recording,
                mugshotBytes = mugshot,
                isDrill = isDrill,
            )
        }.onFailure { Log.w(TAG, "sentinel inbox append failed", it) }
        // Resolve display name for the notification body.
        val name = runCatching {
            ctx.repository.knownPeerByKey(fromKey)?.displayName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fromKey.take(12)
        val notifStage = if (isDrill) "DRILL — $stage" else stage
        runCatching {
            app.aether.aegis.sentinel.SentinelInboundNotifier.notify(
                ctx, name, notifStage, pkt.batteryPct,
            )
        }.onFailure { Log.w(TAG, "sentinel notify failed", it) }
    }

    /** Receiver of our outbound drill — they tapped Confirm in their
     *  inbox. Record the confirmation on our local SentinelPrefs so
     *  the drill UI can show "3/4 confirmed". When all pending
     *  recipients confirm, drill passes → lastDrillAt updates → the
     *  Sonar skill-tree node lights cyan. */
    private fun onSentinelDrillAck(fromKey: String, pkt: RemoteAccessProtocol.Packet) {
        val ctx = AegisApp.instance
        runCatching {
            val prefs = app.aether.aegis.sentinel.SentinelPrefs(ctx)
            if (prefs.drillStartedAt == 0L) return@runCatching
            // Add this peer to the confirmed set.
            prefs.drillConfirmedRecipients = prefs.drillConfirmedRecipients + fromKey
            // If everyone we expected has confirmed, drill passes.
            val pending = prefs.drillPendingRecipients
            val confirmed = prefs.drillConfirmedRecipients
            if (pending.isNotEmpty() && confirmed.containsAll(pending)) {
                Log.i(TAG, "drill passed — all ${pending.size} recipients confirmed")
                prefs.lastDrillAt = System.currentTimeMillis()
                prefs.drillStartedAt = 0L
                prefs.drillPendingRecipients = emptySet()
                prefs.drillConfirmedRecipients = emptySet()
            }
        }.onFailure { Log.w(TAG, "drill-ack handling failed", it) }
    }

    /** Fire-and-forget broadcast of [RemoteAccessProtocol.KIND_UNREVOKED]
     *  to a peer we just toggled BACK ON in our gate. Public because
     *  the UI toggle (RemoteAccessControlRow) wires it after calling
     *  [RemoteAccessGate.unrevoke]; both calls together replace what
     *  was a half-loop before, where the local gate would clear but
     *  the requester's cached revokedBy entry stayed sticky and the
     *  user reported "revoke is permanent." */
    fun broadcastUnrevoked(toKey: String) {
        send(toKey, RemoteAccessProtocol.Packet(kind = RemoteAccessProtocol.KIND_UNREVOKED))
    }

    // ---------- Helpers ----------

    private suspend fun broadcastWiped() {
        val aegisApp = AegisApp.instance
        runCatching {
            // "My device has been remote-wiped" is sos-class news:
            // it tells observers the user has been compromised badly
            // enough to nuke the device. Trusted ∪ Emergency see it,
            // Untrusted does not.
            aegisApp.repository.sosTargets().forEach { peer ->
                runCatching {
                    aegisApp.protocolManager.sendMessage(
                        to = peer.publicKey,
                        content = "${RemoteAccessProtocol.WIPE_BROADCAST_PREFIX}{}",
                        type = MessageType.STATUS,
                    )
                }
            }
        }
    }

    private fun send(toKey: String, packet: RemoteAccessProtocol.Packet) {
        runCatching {
            AegisApp.instance.protocolManager.sendMessage(
                to = toKey,
                content = RemoteAccessProtocol.encode(packet),
                type = MessageType.STATUS,
            )
        }
    }

    private fun sendErr(toKey: String, forCmd: String, msg: String) {
        send(
            toKey,
            RemoteAccessProtocol.Packet(
                kind = RemoteAccessProtocol.KIND_ERR,
                forCmd = forCmd,
                msg = msg,
            ),
        )
    }
}

/**
 * One-shot signal that an AUTH_DENIED was just received from a given
 * peer. The sender's PIN sheet observes this to dismiss + show the
 * "Access denied" toast. Implemented as a tiny pub-sub so we don't
 * have to thread a Continuation through the whole network layer.
 */
object DeniedBus {
    private val listeners = mutableListOf<(String) -> Unit>()

    @Synchronized
    fun subscribe(cb: (String) -> Unit): () -> Unit {
        listeners += cb
        return { synchronized(this) { listeners -= cb } }
    }

    @Synchronized
    fun publish(fromKey: String) {
        listeners.toList().forEach { runCatching { it(fromKey) } }
    }
}

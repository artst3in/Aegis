package app.aether.aegis.remote

import app.aether.aegis.AegisApp
import app.aether.aegis.admin.AdminGate
import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume

/**
 * Low-level executors for the three remote-access primitives:
 * **LOCATE** (lock + GPS fix), **SIREN** (loud unkillable alarm),
 * **WIPE** (Device-Owner factory reset).
 *
 * The public auth-gated dispatch lives in [RemoteAccessHandler].
 * Everything here is the actual side-effect plumbing — no PIN check,
 * no session bookkeeping. Caller is responsible for proving the
 * sender had the right.
 *
 * Each method falls back gracefully when Aegis isn't Device Owner:
 *   - LOCATE locks via DevicePolicyManager.lockNow (works on plain
 *     Device Admin too) and returns whatever GPS we can scrape.
 *   - SIREN works without any privilege (delegates to app.aether.aegis.sos.SirenManager).
 *   - WIPE refuses without DO.
 *
 * Three-command surface. The old per-command surface
 * (FORCE_GPS / FORCE_NETWORK / LOCK / GET_POSITION / SIREN_ON /
 * SIREN_OFF) is gone — LOCATE = lock + mugshot
 * + GPS, merged; mugshot stays local.
 */
object RemoteCommandHandler {

    private const val TAG = "RemoteCommand"

    // ---- LOCATE ----

    /** Lock the device, snapshot location, return (lat, lng) — null
     *  pair if no fix is available. The mugshot pipeline is read
     *  separately via [latestMugshotFile]; we deliberately don't try
     *  to fire the front camera from a background context (no
     *  LifecycleOwner) — the most-recent wrong-PIN mugshot is what
     *  the owner actually wants on remote LOCATE. */
    suspend fun fireLocate(): Pair<Double?, Double?> {
        // Explicit logging so adb logcat surfaces what happened. User
        // reported AUTH succeeded but the target neither locked nor
        // returned coords; without these lines all three sub-steps
        // (lock, GPS fix, mugshot upstream) failed silently.
        val lockResult = runCatching { lockDevice() }
            .getOrElse { it.message ?: "exception" }
        Log.i(TAG, "fireLocate: lock=$lockResult")
        val coords = runCatching { snapshotLocation() }
            .getOrElse {
                Log.w(TAG, "fireLocate: snapshotLocation threw", it)
                null to null
            }
        Log.i(TAG, "fireLocate: coords=$coords")
        return coords
    }

    /** Fire fresh front + rear mugshot captures and return both base64-
     *  encoded JPEG bodies. Either or both may be null if the lens
     *  isn't available, no CAMERA permission, or capture failed.
     *  Used by [RemoteWatchMode]'s periodic ticks so each tick
     *  delivers both faces of the device. */
    suspend fun captureBothMugshotsB64(): Pair<String?, String?> {
        val aegisApp = AegisApp.instance
        val front = runCatching {
            app.aether.aegis.mugshot.MugshotCapture.captureForRemoteLocate(aegisApp)
        }.getOrNull()
        // captureForRemoteLocate writes both files; the latest two
        // -locate-{front,rear}.jpg in mugshots/ are this tick's
        // outputs. Pick them up by suffix.
        val mugshotsDir = java.io.File(aegisApp.filesDir, "mugshots")
        val frontFile = front  // explicit front handle from API
        val rearFile = mugshotsDir.listFiles()
            ?.filter { it.name.endsWith("-locate-rear.jpg") && it.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
        // Reap older locate-* captures aggressively — every 25 s tick
        // would otherwise pile up megabytes. Keep the 4 freshest
        // (covers ~2 minutes of UI history).
        runCatching { reapOldLocateCaptures(mugshotsDir, keep = 8) }
        return encodeJpegB64(frontFile) to encodeJpegB64(rearFile)
    }

    private fun encodeJpegB64(file: java.io.File?): String? {
        if (file == null || !file.isFile) return null
        if (file.length() == 0L || file.length() > 600_000) return null
        return runCatching {
            android.util.Base64.encodeToString(
                file.readBytes(),
                android.util.Base64.NO_WRAP,
            )
        }.getOrNull()
    }

    private fun reapOldLocateCaptures(dir: java.io.File, keep: Int) {
        if (!dir.isDirectory) return
        val locateFiles = dir.listFiles()
            ?.filter { it.name.contains("-locate-") && it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (locateFiles.size <= keep) return
        locateFiles.drop(keep).forEach { runCatching { it.delete() } }
    }

    /** Returns the most-recent mugshot JPEG on disk, or null if there
     *  isn't one. Used by [RemoteAccessHandler] to piggy-back the
     *  picture of whoever was using the phone on LOCATE returns. */
    fun latestMugshotFile(): java.io.File? {
        val dir = java.io.File(AegisApp.instance.filesDir, "mugshots")
        if (!dir.isDirectory) return null
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
    }

    /** Drop the device to its lock screen. Requires
     *  [app.aether.aegis.admin.AegisAdminReceiver] to be an active Device Admin
     *  — pre-this-version Aegis never enrolled, so this silently
     *  no-op'd. We now short-circuit on the gate so the failure
     *  surfaces in [app.aether.aegis.remote.RemoteAccessHandler]'s log + on the
     *  sender's UI via the `lockOk` packet field. */
    fun lockDevice(): String {
        val ctx = AegisApp.instance
        if (!app.aether.aegis.admin.AdminGate.isActive(ctx)) {
            Log.w(TAG, "lockDevice skipped: AegisAdminReceiver not enrolled")
            return "not_device_admin"
        }
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return "no DPM"
        // FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY (forces a FULL re-auth on unlock
        // — no biometric/PIN-shortcut) is ONLY valid on a managed profile with
        // a separate work challenge. On the PRIMARY user — the Device-Owner
        // Pixel case — lockNow(flag) throws IllegalArgumentException, so the
        // screen never locked (user-reported "LOCATE doesn't lock"). Try the
        // stronger eviction where it's supported, then fall back to a plain
        // lockNow() so the screen ALWAYS locks.
        val evicted = runCatching {
            dpm.lockNow(DevicePolicyManager.FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)
        }
        if (evicted.isSuccess) return "locked"
        Log.w(
            TAG,
            "lockDevice: evict-key lock rejected (${evicted.exceptionOrNull()?.message}) — plain lockNow",
        )
        runCatching { dpm.lockNow() }
            .onFailure { return "failed: ${it.message}" }
        return "locked"
    }

    // ---- LISTEN ----

    /** Record [seconds] of microphone audio to AAC, return the raw
     *  M4A bytes. Capped at 30 s defensively (anything longer makes
     *  the JSON envelope unwieldy; the sender can re-issue listen
     *  for back-to-back chunks). Returns null on capture failure /
     *  no RECORD_AUDIO permission / unsupported encoder.
     *
     *  The recording is silent — no shutter, no LED on devices we
     *  can suppress it on. That's the point: the owner of a stolen
     *  phone should be able to hear what's around it without
     *  alerting the thief. */
    suspend fun captureMicAudio(seconds: Int): java.io.File? = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO,
    ) {
        val ctx = AegisApp.instance
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "captureMicAudio: no RECORD_AUDIO permission")
            return@withContext null
        }
        val durationMs = (seconds.coerceIn(1, 30)) * 1000
        val outDir = java.io.File(ctx.cacheDir, "remote_listen").apply { mkdirs() }
        val out = java.io.File(outDir, "listen-${System.currentTimeMillis()}.m4a")
        val recorder = android.media.MediaRecorder().apply {
            setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(32_000)
            setAudioSamplingRate(22_050)
            setOutputFile(out.absolutePath)
        }
        return@withContext try {
            recorder.prepare()
            recorder.start()
            kotlinx.coroutines.delay(durationMs.toLong())
            runCatching { recorder.stop() }
            recorder.release()
            // Reap older captures so cacheDir doesn't bloat.
            outDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(3)
                ?.forEach { runCatching { it.delete() } }
            if (out.length() > 0) out else null
        } catch (t: Throwable) {
            Log.w(TAG, "captureMicAudio failed", t)
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
            out.delete()
            null
        }
    }

    /**
     * Whether the app is currently foreground-eligible to (re)claim a
     * microphone/camera foreground service. Capture relies on [ProtocolService]
     * holding those FGS types, which Android 14+ only grants from a foreground /
     * visible state (or an exemption); from the background — exactly the
     * remote-rescue case — the FGS start is refused and capture fails.
     *
     * Callers ATTEMPT the capture regardless (so an exempt device that CAN
     * capture in the background still works) and use this ONLY to choose an
     * honest error on failure: `capture_unavailable_background` when we were
     * background-ineligible vs. the generic `capture_failed` for a true
     * codec/hardware fault. Heuristic by design: reads process importance. A
     * visible activity reports IMPORTANCE_FOREGROUND (100); a lone running FGS
     * reports IMPORTANCE_FOREGROUND_SERVICE (125) — which does NOT grant the
     * right to ADD mic/camera — so we require strictly foreground.
     */
    fun captureForegroundEligible(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance <=
            android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    // ---- DISPLAY ----

    /** Post a sticky, high-priority lockscreen notification with the
     *  owner-supplied [text]. Stays up until the owner dismisses via
     *  another DISPLAY with empty text, or the user dismisses
     *  manually. Use case: "If found, please contact +1-555-…". */
    fun pushLockscreenMessage(text: String): String {
        val ctx = AegisApp.instance
        val nm = androidx.core.app.NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return "notifications_disabled"
        // Dismiss when an empty message comes in — owner cancels the
        // billboard from the sender's UI.
        if (text.isBlank()) {
            nm.cancel(DISPLAY_NOTIF_ID)
            return "cleared"
        }
        val notif = androidx.core.app.NotificationCompat.Builder(
            ctx, app.aether.aegis.AegisApp.CHANNEL_SOS,
        )
            .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_shield)
            .setColor(app.aether.aegis.AegisApp.BRAND_SOS_ARGB)
            .setContentTitle("Aegis — message from owner")
            .setContentText(text.take(160))
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
            .build()
        nm.notify(DISPLAY_NOTIF_ID, notif)
        return "displayed"
    }

    private const val DISPLAY_NOTIF_ID = 7301

    private suspend fun snapshotLocation(): Pair<Double?, Double?> {
        val ctx = AegisApp.instance
        // Android 12+ users commonly grant only "approximate" (COARSE)
        // — gating on FINE alone meant LOCATE silently returned (null,
        // null) for the majority of devices. Accept either; we just
        // route around the GPS provider when fine isn't granted, since
        // GPS_PROVIDER throws SecurityException on coarse-only.
        val fine = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            Log.w(TAG, "snapshotLocation: no location permission")
            return null to null
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE)
            as? android.location.LocationManager ?: return null to null

        val fresh = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
            kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { cont ->
                val provider = when {
                    fine && lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ->
                        android.location.LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ->
                        android.location.LocationManager.NETWORK_PROVIDER
                    else -> {
                        cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                }
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(loc: android.location.Location) {
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
                @Suppress("MissingPermission")
                lm.requestLocationUpdates(
                    provider, 0L, 0f, listener,
                    android.os.Looper.getMainLooper(),
                )
            }
        }
        val loc = fresh ?: run {
            val providers = listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER,
            )
            providers.mapNotNull { p ->
                @Suppress("MissingPermission")
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            }.maxByOrNull { it.time }
        } ?: return null to null
        return loc.latitude to loc.longitude
    }

    // ---- SIREN ----
    //
    // Delegates to app.aether.aegis.sos.SirenManager — the same loud
    // ToneGenerator-driven CDMA emergency tone the sos flow uses.
    // The previous MediaPlayer + default-alarm-ringtone setup was
    // 'very gentle' (user-reported 2026.05.668). Tone CDMA_HIGH_SS_2
    // on STREAM_ALARM at max volume, looped — unmistakably an
    // emergency wail, not a notification ding. Bypasses Do-Not-
    // Disturb / silent mode because STREAM_ALARM is exempt on every
    // Android policy.

    fun fireSiren(): String {
        return runCatching {
            app.aether.aegis.sos.SirenManager.start(AegisApp.instance)
            "siren on"
        }.getOrElse { "failed: ${it.message}" }
    }

    fun stopSiren(): String {
        return runCatching {
            app.aether.aegis.sos.SirenManager.stop(AegisApp.instance)
            "siren off"
        }.getOrElse { "failed: ${it.message}" }
    }

    // ---- WIPE ----

    // ════════════════════════════════════════════════════════════════════
    // ██  REMOTE WIPE — DO NOT MODIFY WITHOUT A FULL ON-DEVICE RE-TEST  ██
    // ════════════════════════════════════════════════════════════════════
    //
    // This is the factory-reset path. It is DESTRUCTIVE and IRREVERSIBLE, and
    // it can only be exercised by actually wiping a real Device-Owner phone —
    // there is NO non-destructive way to confirm `wipeData` truly resets, so
    // every change here costs someone a full device wipe + re-provision to
    // verify. Treat this block as FROZEN.
    //
    // It was hard-won. The history that matters:
    //   • THREE-TIER wipe (the false "wiped" broadcast was the real bug, not the
    //     attempt — review caught this). A wipe ALWAYS destroys at least Aegis's own data
    //     (Tier 3 can't fail), so the [aegis:wiped] contact broadcast is never a
    //     lie, and EVERYTHING outbound is sent + flushed BEFORE the wipe (a
    //     factory reset kills the process; the Aegis-data wipe destroys the
    //     SimpleX identity — nothing can be sent after). Tiers:
    //       1. Device Owner       → wipeDevice()/wipeData() full reset + FRP.
    //       2. non-DO Device Admin → wipeData() WITHOUT WIPE_RESET_PROTECTION_DATA
    //          (that flag is DO-only & throws SecurityException for a plain
    //          admin; stripped, a stock-Android admin reset succeeds — the
    //          Find-My-Device path. GrapheneOS hardens this → it returns → Tier 3).
    //       3. always            → [wipeAegisData] destroys DB/vault/keys/msgs/
    //          media/contacts/prefs (clearApplicationUserData). Phone intact,
    //          Aegis gone. The guaranteed floor.
    //     Operator hears the honest outcome: "wiped" (1/2 fired) or "aegis-wiped"
    //     (fell to 3). [factoryResetCapable] gates the 1/2 attempt only.
    //   • A lingering DISALLOW_FACTORY_RESET user-restriction makes a Device
    //     Owner's OWN wipeData() throw SecurityException and SILENTLY no-op.
    //     This was the 2026-06 field failure: DO provisioned, siren/locate
    //     worked, but wipe did nothing while the operator saw a false "wiped".
    //     We clear it (idempotently) immediately before the AUTHORISED wipe.
    //     PermissionAutoGrant deliberately does NOT clear it globally (that
    //     would weaken anti-theft) — only right here, on an authorised wipe.
    //   • Failures used to be swallowed into an unlogged return string, so a
    //     non-firing wipe was undiagnosable. Everything is logged now.
    //   • FRP (Factory Reset Protection): the wipe sets
    //     WIPE_RESET_PROTECTION_DATA so the reset device doesn't boot into an
    //     FRP lock demanding the old Google account (the real "brick"). DO is
    //     allowed to clear it; layered fallback if the platform rejects the
    //     flag. A normal DO device (no registered account) has no FRP anyway.
    //   • A SUCCESSFUL wipe never returns from wipeData — the process is gone.
    //     So any return from [fireWipe] means the reset did NOT take.
    //
    // Before triggering a real wipe, the owner can run [wipePreflight] on the
    // target (Diagnostics → "Check remote-wipe readiness"); it performs the
    // SAME preconditions WITHOUT wiping and reports READY / the exact blocker.
    // If preflight says READY, the wipe will fire.
    // ════════════════════════════════════════════════════════════════════

    /** True iff a real FACTORY RESET is plausible — Device Owner (Tier 1) or an
     *  active Device Admin (Tier 2: non-DO wipeData factory-resets on stock
     *  Android — GrapheneOS hardens it). When false, [RemoteAccessHandler.handleWipe]
     *  skips straight to the Tier-3 Aegis-data wipe. This gates the factory-reset
     *  ATTEMPT only — a wipe always destroys at least Aegis's own data, so the
     *  command is never a no-op. */
    fun factoryResetCapable(): Boolean {
        val ctx = AegisApp.instance
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        if (dpm.isDeviceOwnerApp(ctx.packageName)) return true
        return runCatching { dpm.isAdminActive(AdminGate.component(ctx)) }.getOrDefault(false)
    }

    /**
     * NON-DESTRUCTIVE readiness check for the remote wipe. Runs every
     * precondition [fireWipe] needs — Device-Owner status, clearing +
     * verifying the DISALLOW_FACTORY_RESET restriction, Device-Admin — but
     * NEVER calls wipeData. Returns a human-readable verdict so the owner can
     * confirm a one-shot wipe will actually fire BEFORE committing to it.
     *
     * Side effect (intentional + safe): it CLEARS DISALLOW_FACTORY_RESET, so
     * running preflight also pre-arms the device — if it reports READY, the
     * blocker is already gone.
     */
    fun wipePreflight(): String {
        val ctx = AegisApp.instance
        val sdk = Build.VERSION.SDK_INT
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return "NOT READY — no DevicePolicyManager"
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) {
            val adminOn = runCatching { dpm.isAdminActive(AdminGate.component(ctx)) }.getOrDefault(false)
            return "PARTIAL — not Device Owner (pkg=${ctx.packageName}).\n" +
                "Factory reset: ${if (adminOn) "Device-Admin tier — works on stock Android, " +
                    "no-ops on GrapheneOS" else "unavailable (no Device Admin)"}.\n" +
                "Aegis-data wipe (Tier 3): ALWAYS works — remote wipe will at least " +
                "destroy all Aegis data. For a full factory reset provision Device " +
                "Owner: adb shell dpm set-device-owner.\n" +
                "(DeviceOwner=false, adminActive=$adminOn, sdk=$sdk)"
        }
        val admin = AdminGate.component(ctx)
        val adminActive = runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)
        // Clear the factory-reset block now (idempotent) and verify it's gone —
        // the one precondition that silently no-ops a DO wipe.
        runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET) }
            .onFailure { Log.w(TAG, "preflight: clear DISALLOW_FACTORY_RESET failed: ${it.message}") }
        val um = ctx.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
        val frBlocked = runCatching {
            um?.hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET) == true
        }.getOrDefault(false)
        val verdict = if (frBlocked) {
            "NOT READY — factory reset is still BLOCKED (DISALLOW_FACTORY_RESET set " +
                "by a policy this app can't clear). Remote wipe will no-op."
        } else {
            "READY ✓ (Device Owner tier) — factory reset allowed. Remote wipe will " +
                "fire (and clears FRP so the phone won't lock to a Google account after)."
        }
        val report = "$verdict\n(DeviceOwner=true, adminActive=$adminActive, " +
            "factoryResetBlocked=$frBlocked, sdk=$sdk)"
        Log.i(TAG, "wipePreflight: $report")
        return report
    }

    /**
     * Tier 1/2 factory reset. Device Owner → wipeDevice()/wipeData() with full
     * flags (incl. FRP clear). Non-DO active Device Admin → wipeData() WITHOUT
     * WIPE_RESET_PROTECTION_DATA (that flag is DO-only and throws SecurityException
     * for a plain admin — stripping it is what lets a stock-Android admin reset
     * succeed; the Find-My-Device path). A SUCCESSFUL reset NEVER returns (the OS
     * tears the process down mid-call), so ANY return means it did NOT fire and
     * the caller falls through to [wipeAegisData]. The caller MUST have already
     * sent + flushed the contact broadcast / operator status — nothing can be
     * sent after this.
     */
    fun fireWipe(): String {
        val ctx = AegisApp.instance
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return "no DPM"
        val admin = AdminGate.component(ctx)
        val isDO = dpm.isDeviceOwnerApp(ctx.packageName)
        val isAdmin = runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)
        if (!isDO && !isAdmin) {
            Log.w(TAG, "fireWipe: no factory-reset capability (not DO, not active admin)")
            return "skipped: no factory-reset capability"
        }
        // DISALLOW_FACTORY_RESET (the documented silent-no-op cause) can only be
        // cleared by a Device Owner; a non-DO admin can't set/clear restrictions,
        // so only the DO clears it.
        if (isDO) {
            runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET) }
                .onFailure { Log.w(TAG, "wipe: clear DISALLOW_FACTORY_RESET failed: ${it.message}") }
        }
        Log.w(TAG, "wipe: firing factory reset (DO=$isDO, admin=$isAdmin, sdk=${Build.VERSION.SDK_INT})")
        // DO uses wipeDevice() on API 34+ (a DO's wipeData() on the system user
        // throws "User 0 is a system user, cannot be removed"). A non-DO admin
        // must use wipeData() (wipeDevice is DO-only) and must NOT pass
        // WIPE_RESET_PROTECTION_DATA (DO-only flag → SecurityException).
        //
        // Try each flag form IN SEQUENCE. A SUCCESSFUL call never returns, so
        // reaching the next line means it did NOT fire (threw or silently
        // no-op'd). DO ordering: FRP-clearing wipe first (anti-brick), then
        // external-only, then flagless.
        val attempts = if (isDO) {
            listOf(
                DevicePolicyManager.WIPE_EXTERNAL_STORAGE or
                    DevicePolicyManager.WIPE_RESET_PROTECTION_DATA,
                DevicePolicyManager.WIPE_EXTERNAL_STORAGE,
                0,
            )
        } else {
            listOf(DevicePolicyManager.WIPE_EXTERNAL_STORAGE, 0)
        }
        for ((i, flags) in attempts.withIndex()) {
            runCatching {
                if (isDO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    dpm.wipeDevice(flags)
                } else {
                    @Suppress("DEPRECATION")
                    dpm.wipeData(flags)
                }
            }.onFailure { Log.e(TAG, "wipe: attempt ${i + 1} (flags=$flags) threw", it) }
            // Still executing → that call didn't reset. Fall to the next form.
            Log.w(TAG, "wipe: attempt ${i + 1} (flags=$flags) returned without resetting")
        }
        Log.w(TAG, "wipe: factory reset did NOT take — caller falls to Aegis-data wipe (Tier 3)")
        return "no-op: factory reset returned without resetting"
    }

    /**
     * Tier-3 floor: destroy ALL of Aegis's own data — encrypted DB, vault, keys,
     * recovery phrase, messages, photos, contacts, attachments, SharedPreferences,
     * cache — via the OS's own "clear app storage" primitive
     * ([android.app.ActivityManager.clearApplicationUserData]). Needs NO admin/DO
     * and CANNOT meaningfully fail, so it is the guaranteed wipe floor: the phone
     * is not factory-reset, but everything Aegis held is gone and unrecoverable.
     * The call clears the data and KILLS the process, so — like a factory reset —
     * it does not return on success. The caller MUST have already sent + flushed
     * the broadcast / operator status; the SimpleX identity is about to be
     * destroyed along with everything else.
     */
    fun wipeAegisData(): String {
        val ctx = AegisApp.instance
        Log.w(TAG, "wipe: Tier-3 Aegis-data wipe (clearApplicationUserData)")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val ok = runCatching { am?.clearApplicationUserData() == true }
            .onFailure { Log.e(TAG, "wipe: clearApplicationUserData threw", it) }
            .getOrDefault(false)
        // Reached only if the clear didn't immediately tear us down.
        Log.w(TAG, "wipe: clearApplicationUserData returned ok=$ok (process should be dying)")
        return "aegis-wipe returned (ok=$ok)"
    }
}

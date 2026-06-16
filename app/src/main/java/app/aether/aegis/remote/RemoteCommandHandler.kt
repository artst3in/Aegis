package app.aether.aegis.remote

import app.aether.aegis.AegisApp
import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
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
        runCatching {
            dpm.lockNow(DevicePolicyManager.FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)
        }.onFailure { return "failed: ${it.message}" }
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

    /** True iff this app is provisioned as Device Owner — the only mode in
     *  which [fireWipe] can actually factory-reset. Callers check this up
     *  front so a non-DO target refuses the command (and skips the bogus
     *  "wiped" broadcast) instead of discovering mid-fire that the wipe is a
     *  silent no-op. */
    fun canWipe(): Boolean {
        val ctx = AegisApp.instance
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        return dpm?.isDeviceOwnerApp(ctx.packageName) == true
    }

    fun fireWipe(): String {
        val ctx = AegisApp.instance
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return "no DPM"
        if (!dpm.isDeviceOwnerApp(ctx.packageName)) {
            Log.w(TAG, "wipe skipped: not Device Owner")
            return "skipped: not Device Owner"
        }
        runCatching {
            // Nuclear. There is no recovery after this fires.
            dpm.wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE)
        }.onFailure { return "failed: ${it.message}" }
        return "wipe initiated"
    }
}

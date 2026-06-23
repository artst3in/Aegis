package app.aether.aegis.sos

import app.aether.aegis.AegisApp
import app.aether.aegis.simplex.SimpleXTransport
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import app.aether.aegis.mugshot.HeadlessLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Periodic rear-camera still capture while the user's own sos is
 * active. Every [SNAPSHOT_INTERVAL_MS] we take a single JPEG via
 * CameraX and fan it out to every sos target tagged
 * `[aegis:sos-frame]`. Receivers cache the latest frame and the
 * dashboard renders it inline so contacts see real-time visual
 * context before they ever Accept the WebRTC call invite.
 *
 * Power budget gates: only runs when [PowerBudget.shouldRunCameraStream]
 * AND [PowerBudget.shouldShipAudioChunks] are both open — same
 * thresholds as the sos-audio chunk fan-out. Going below either
 * floor (≤40 % camera, ≤15 % network ship) automatically suspends
 * the loop without tearing it down; it resumes when the device
 * climbs back above hysteresis.
 *
 * Rear camera by default (environment view: what's in front of the
 * attacker, what the victim is looking at). Falls through to front
 * if the device has no rear lens.
 */
object SOSSnapshotStream {

    private const val TAG = "SOSSnapshotStream"
    private const val SNAPSHOT_INTERVAL_MS = 5_000L
    // Preview-grade frame size + quality (see capture()): ~720p @ JPEG 70
    // keeps each frame ~100 KB instead of a full-sensor ~4 MB still.
    private const val FRAME_TARGET_W = 1280
    private const val FRAME_TARGET_H = 720
    private const val FRAME_JPEG_QUALITY = 70

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loopJob: Job? = null
    private val lifecycleOwner = HeadlessLifecycleOwner()

    /** Begin the capture loop. Idempotent. Caller is responsible for
     *  checking CAMERA permission before invoking; we log + bail if
     *  it's missing. */
    fun start() {
        if (loopJob?.isActive == true) return
        val ctx = AegisApp.instance.applicationContext
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "skipping — no CAMERA permission")
            return
        }
        lifecycleOwner.start()
        loopJob = scope.launch {
            while (isActive) {
                runCatching { tick(ctx) }.onFailure { Log.w(TAG, "snapshot tick failed", it) }
                delay(SNAPSHOT_INTERVAL_MS)
            }
            runCatching { lifecycleOwner.stop() }
        }
    }

    /** Stop the loop and tear down CameraX bindings. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        runCatching { lifecycleOwner.stop() }
    }

    private suspend fun tick(ctx: Context) {
        val budget = AegisApp.instance.powerBudget
        budget.refresh()
        if (!budget.shouldRunCameraStream()) return
        if (!budget.shouldShipAudioChunks()) return
        val dir = File(ctx.cacheDir, "sos_frames").apply { mkdirs() }
        val ts = System.currentTimeMillis()
        // Ship BOTH lenses so responders see the SCENE (rear) AND who's with
        // the victim (front), each correctly tagged so the panic dashboard
        // routes them to the right slot. Previously only the rear camera was
        // shipped and the dashboard mislabelled it "front" (user report:
        // "the front camera sends back camera photos"). Rear first (the
        // stolen/grabbed-phone default angle), then front.
        val rear = File(dir, "frame-$ts-rear.jpg")
        val rearOk = runCatching {
            capture(ctx, rear, CameraSelector.LENS_FACING_BACK)
        }.getOrDefault(false)
        if (rearOk && rear.exists() && rear.length() > 0L) ship(rear, "rear")

        val front = File(dir, "frame-$ts-front.jpg")
        val frontOk = runCatching {
            capture(ctx, front, CameraSelector.LENS_FACING_FRONT)
        }.getOrDefault(false)
        if (frontOk && front.exists() && front.length() > 0L) ship(front, "front")
    }

    private suspend fun ship(file: File, lens: String) {
        val pm = AegisApp.instance.protocolManager
        val selfKey = AegisApp.instance.identity.deviceId
        val targets = runCatching {
            AegisApp.instance.repository.sosTargets()
        }.getOrNull().orEmpty()
        if (targets.isEmpty()) return
        val simplex = AegisApp.instance.transports
            .filterIsInstance<SimpleXTransport>()
            .firstOrNull() ?: return
        targets.forEach { peer ->
            if (peer.publicKey == selfKey) return@forEach
            runCatching {
                simplex.sendFileToContact(
                    peerPubkey = peer.publicKey,
                    filePath = file.absolutePath,
                    isImage = true,
                    caption = "[aegis:sos-frame:$lens] ${file.name}",
                    // Duress evidence to enrolled responders — GPS +
                    // timestamp are DELIBERATELY kept so they can find
                    // the user. Exempt from the outbound metadata scrub.
                    forensic = true,
                )
            }
        }
    }

    @Suppress("MissingPermission")
    private suspend fun capture(
        ctx: Context,
        out: File,
        lensFacing: Int,
    ): Boolean = withContext(Dispatchers.Main) {
        val provider = ProcessCameraProvider.getInstance(ctx).await()
        val available = runCatching {
            provider.hasCamera(
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
            )
        }.getOrDefault(false)
        if (!available) return@withContext false
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        // SOS frames are a LIVE dashboard preview (and inline chat thumb),
        // not forensic stills — a full-sensor 12 MP / ~4 MB JPEG every 5 s
        // burned the victim's battery + data and the receiver's.
        // Bound the capture to ~720p and drop JPEG quality to 70: enough to
        // see who/what is in frame, ~100 KB instead of 4 MB. CameraX still
        // writes the correct EXIF orientation, so the dashboard/Coil render
        // it upright.
        val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setResolutionStrategy(
                androidx.camera.core.resolutionselector.ResolutionStrategy(
                    android.util.Size(FRAME_TARGET_W, FRAME_TARGET_H),
                    androidx.camera.core.resolutionselector.ResolutionStrategy
                        .FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector)
            .setJpegQuality(FRAME_JPEG_QUALITY)
            .build()
        runCatching { provider.unbindAll() }
        provider.bindToLifecycle(lifecycleOwner, selector, capture)

        val done = CompletableDeferred<Boolean>()
        val opts = ImageCapture.OutputFileOptions.Builder(out).build()
        capture.takePicture(
            opts,
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    done.complete(true)
                }
                override fun onError(exc: ImageCaptureException) {
                    Log.w(TAG, "takePicture failed", exc)
                    done.complete(false)
                }
            },
        )
        val ok = done.await()
        runCatching { provider.unbindAll() }
        ok
    }

    private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addListener(
                { runCatching { cont.resume(get()) } },
                Runnable::run,
            )
        }
}

// HeadlessLifecycleOwner consolidated to its public copy at
// app/src/main/java/app/aether/aegis/mugshot/HeadlessLifecycleOwner.kt
// after the duplicate was flagged in the 2026.06.286 cleanup pass.
// The public version drives the registry to RESUMED on start()
// rather than STARTED — minor upgrade for sos capture too, since
// CameraX is documented to auto-pause STARTED owners between
// take-picture calls in some pipeline configs.

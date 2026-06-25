package app.aether.aegis.mugshot

import app.aether.aegis.AegisApp
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * Headless front + rear camera capture invoked from the LockScreen
 * when the wrong-PIN mugshot threshold is exceeded. Uses CameraX
 * bound to the activity's lifecycle owner so we don't need our own
 * surface; ImageCapture pipes the frame straight to a JPEG with no
 * visible UI.
 *
 * **Local-only**. The JPEG lands in
 * `filesDir/mugshots/` with GPS + timestamp burned into EXIF and a
 * notification fires to the owner. Nothing is sent to paired
 * contacts. The remote-LOCATE path ([captureForRemoteLocate]) is the
 * only way a mugshot leaves the device, and only to the authenticated
 * AUTH initiator after they prove the right PIN.
 *
 * Idempotent via MugshotStore's firedThisStreak flag — we don't burn
 * through five frames during one extended lockout.
 */
object MugshotCapture {

    private const val TAG = "MugshotCapture"

    suspend fun captureAndShip(context: Context, lifecycleOwner: LifecycleOwner) {
        // Name kept for backwards compatibility with LockScreen's
        // call site; behaviour is now
        // capture-and-STORE — no SimpleX fan-out. Mugshots are a
        // local-only forensic, viewable by the owner once they
        // unlock the phone.
        val store = MugshotStore(context)
        if (!store.enabled || store.firedThisStreak) return

        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "skipping — no CAMERA permission")
            return
        }

        // filesDir/mugshots/ — persistent (cacheDir can be evicted
        // under storage pressure before the owner ever sees the
        // notification).
        val dir = File(context.filesDir, "mugshots").apply { mkdirs() }
        val ts = System.currentTimeMillis()
        val frontOut = File(dir, "mugshot-$ts-front.jpg")
        val rearOut  = File(dir, "mugshot-$ts-rear.jpg")

        // Front (face) + rear (environment) captured
        // together. Rear is best-effort — phones with no rear camera
        // and edge cases (camera held by another app) just produce a
        // front-only mugshot and degrade gracefully.
        val frontOk = runCatching {
            capture(context, lifecycleOwner, frontOut, CameraSelector.LENS_FACING_FRONT)
        }.getOrElse { Log.w(TAG, "front capture failed", it); false }

        val rearOk = runCatching {
            capture(context, lifecycleOwner, rearOut, CameraSelector.LENS_FACING_BACK)
        }.getOrElse { Log.w(TAG, "rear capture failed", it); false }

        val primary = when {
            frontOk && frontOut.length() > 0L -> frontOut
            rearOk  && rearOut.length()  > 0L -> rearOut
            else -> return
        }
        store.firedThisStreak = true
        // A duress unlock caught a mugshot for real → earn the Caught
        // You badge. Mugshots are local-only
        // by design (no contact delivery), so capture IS the success
        // signal here.
        app.aether.aegis.achievements.Achievements.unlock(
            app.aether.aegis.achievements.Achievement.CAUGHT_YOU,
        )

        // Burn GPS + timestamp into each surviving JPEG's EXIF so the
        // forensic record stands on its own. Best-effort — no fix means
        // the photo still saves, just without geotag.
        val loc = runCatching { lastKnownFix(context) }.getOrNull()
        listOfNotNull(
            frontOut.takeIf { frontOk && it.length() > 0L },
            rearOut.takeIf  { rearOk  && it.length()  > 0L },
        ).forEach { f -> stampExif(f, loc, ts) }

        // Cap retention. Without this filesDir/mugshots/ grew
        // unbounded — one user's data dir hit 6.5 GB mostly from
        // accumulated mugshots after a release storm. 50 keeps a
        // useful forensic backlog (~10 MB) without runaway growth.
        runCatching {
            app.aether.aegis.storage.StorageCleanup.pruneMugshots(
                context,
                app.aether.aegis.storage.StorageCleanup.MUGSHOT_KEEP_DEFAULT,
            )
        }

        notifyOwner(context, primary)
    }

    /** Last-known GPS fix, ignoring permission denial. Doesn't request
     *  a fresh location — mugshot fires in a tight window and waiting
     *  on GPS warmup would lose the face. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnownFix(context: Context): android.location.Location? {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE)
            as? android.location.LocationManager ?: return null
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
        )
        return providers.mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    /** Burn GPS lat/lng + timestamp into the JPEG's EXIF in-place. */
    private fun stampExif(file: File, loc: android.location.Location?, ts: Long) {
        runCatching {
            val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
            val date = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(ts))
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, date)
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, date)
            if (loc != null) {
                exif.setGpsInfo(loc)
            }
            exif.saveAttributes()
        }.onFailure { Log.w(TAG, "EXIF stamp failed for ${file.name}", it) }
    }

    private fun notifyOwner(context: Context, photo: File) {
        runCatching {
            val nm = androidx.core.app.NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            val notif = androidx.core.app.NotificationCompat.Builder(
                context, AegisApp.CHANNEL_SOS,
            )
                .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_shield)
                .setColor(AegisApp.BRAND_SOS_ARGB)
                .setContentTitle("Someone tried to crack your PIN")
                .setContentText("Mugshot saved — open Aegis to view.")
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                        "${MugshotStore(context).triggerThreshold} wrong-PIN attempts triggered " +
                            "a silent front-camera capture. The photo is stored on this " +
                            "device only; nothing was sent to any of your paired contacts."
                    )
                )
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()
            nm.notify("mugshot-${photo.name}".hashCode(), notif)
        }.onFailure { Log.w(TAG, "mugshot notification failed", it) }
    }

    /** Called on successful real-PIN unlock to reset the streak flag. */
    fun resetStreak(context: Context) {
        MugshotStore(context).firedThisStreak = false
    }

    /**
     * Headless capture for the auth-gated remote-LOCATE pipeline
     * Fires front + rear in sequence with
     * EXIF GPS + timestamp burned in, just like the wrong-PIN path —
     * but driven by a synthetic LifecycleOwner so we don't need a UI
     * activity, and without touching the wrong-PIN streak counter
     * (this is forensic on demand, not a tripwire).
     *
     * Returns the FRONT JPEG file when capture succeeded; null when
     * the device has no CAMERA permission or both lens captures
     * failed.
     */
    /**
     * Single-lens snapshot variant for the remote-access SNAPSHOT
     * action. Same capture pipeline as the LOCATE return but only
     * binds + fires the one lens the sender asked for, so the
     * round-trip is faster and we don't waste a rear capture when
     * the sender wanted front (or vice versa). [lens] is "front"
     * (face-of-holder) or "rear" (what they're pointing at).
     */
    suspend fun captureSingleLens(context: Context, lens: String): File? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "snapshot skip — no CAMERA permission")
            return null
        }
        val lensFacing = when (lens) {
            "front" -> CameraSelector.LENS_FACING_FRONT
            else    -> CameraSelector.LENS_FACING_BACK
        }
        val dir = File(context.filesDir, "mugshots").apply { mkdirs() }
        val ts = System.currentTimeMillis()
        val out = File(dir, "mugshot-$ts-snapshot-$lens.jpg")
        val owner = HeadlessLifecycleOwner().apply { start() }
        return try {
            val ok = runCatching {
                capture(context, owner, out, lensFacing)
            }.getOrElse { Log.w(TAG, "snapshot $lens failed", it); false }
            if (ok && out.length() > 0L) {
                val loc = runCatching { lastKnownFix(context) }.getOrNull()
                stampExif(out, loc, ts)
                runCatching {
                    app.aether.aegis.storage.StorageCleanup.pruneMugshots(
                        context,
                        app.aether.aegis.storage.StorageCleanup.MUGSHOT_KEEP_DEFAULT,
                    )
                }
                out
            } else null
        } finally {
            owner.stop()
        }
    }

    suspend fun captureForRemoteLocate(context: Context): File? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "remote-locate skip — no CAMERA permission")
            return null
        }
        val dir = File(context.filesDir, "mugshots").apply { mkdirs() }
        val ts = System.currentTimeMillis()
        val frontOut = File(dir, "mugshot-$ts-locate-front.jpg")
        val rearOut  = File(dir, "mugshot-$ts-locate-rear.jpg")

        val owner = HeadlessLifecycleOwner().apply { start() }
        try {
            val frontOk = runCatching {
                capture(context, owner, frontOut, CameraSelector.LENS_FACING_FRONT)
            }.getOrElse { Log.w(TAG, "remote-locate front failed", it); false }
            val rearOk = runCatching {
                capture(context, owner, rearOut, CameraSelector.LENS_FACING_BACK)
            }.getOrElse { Log.w(TAG, "remote-locate rear failed", it); false }

            val loc = runCatching { lastKnownFix(context) }.getOrNull()
            listOfNotNull(
                frontOut.takeIf { frontOk && it.length() > 0L },
                rearOut.takeIf  { rearOk  && it.length()  > 0L },
            ).forEach { f -> stampExif(f, loc, ts) }

            // Same retention cap as the wrong-PIN path — see comment there.
            runCatching {
                app.aether.aegis.storage.StorageCleanup.pruneMugshots(
                    context,
                    app.aether.aegis.storage.StorageCleanup.MUGSHOT_KEEP_DEFAULT,
                )
            }

            return when {
                frontOk && frontOut.length() > 0L -> frontOut
                rearOk  && rearOut.length()  > 0L -> rearOut
                else -> null
            }
        } finally {
            owner.stop()
        }
    }

    private suspend fun capture(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        out: File,
        lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    ): Boolean = withContext(Dispatchers.Main) {
        val provider = ProcessCameraProvider.getInstance(context).await()
        // Sanity-guard: avoid binding a lens the device doesn't have
        // (rear-cameraless tablets, etc.) — provider.hasCamera throws
        // on some forks, so wrap.
        val available = runCatching {
            provider.hasCamera(
                CameraSelector.Builder().requireLensFacing(lensFacing).build()
            )
        }.getOrDefault(false)
        if (!available) return@withContext false
        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        runCatching { provider.unbindAll() }
        provider.bindToLifecycle(lifecycleOwner, selector, capture)

        val done = CompletableDeferred<Boolean>()
        val opts = ImageCapture.OutputFileOptions.Builder(out).build()
        capture.takePicture(
            opts,
            ContextCompat.getMainExecutor(context),
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

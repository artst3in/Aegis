package app.aether.aegis.crashdetection

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aether.aegis.AegisApp
import app.aether.aegis.core.SOSTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Vehicle crash detection.
 *
 * NOT a new broadcast subsystem — a new *trigger* for the existing
 * sos pipeline. Driven by [app.aether.aegis.services.ProtocolService]
 * (the foreground service that already owns sensors + location): it
 * feeds us each [Location] via [onLocation]; we keep a 60-second speed
 * history and, only while the speed gate is open, register the
 * accelerometer and watch for an impact.
 *
 * Trigger (all three required):
 *  1. Speed gate — was travelling above the sensitivity's km/h in the
 *     last 60 s (filters drops/falls/sport).
 *  2. Impact spike — net acceleration crosses the sensitivity's G
 *     threshold.
 *  3. Post-impact stillness — < [STILLNESS_MOVE_G] for
 *     [STILLNESS_WINDOW_MS] (the vehicle stopped and the phone isn't
 *     being handled — filters hard braking, where the driver keeps
 *     going or grabs the phone).
 *
 * Then a 30 s "I'm OK" countdown; if not cancelled, fires
 * `sosHandler.trigger(CRASH_DETECTED)` — the same GPS/audio/camera
 * broadcast as a manual sos.
 *
 * Singleton: one device, one detector. State is @Volatile because the
 * sensor callback, the location feed, and the cancel receiver all
 * touch it from different threads.
 *
 * NOTE: the G thresholds and the stillness window are first-cut values
 * and want real-vehicle tuning before they're trusted —
 * flagged the same way the media A/V scrub was.
 */
object CrashDetector {

    private const val TAG = "CrashDetector"

    // Tuning (spec). Impact + speed gate come from the store (per
    // sensitivity); these two are fixed.
    private const val STILLNESS_MOVE_G = 0.5f          // above this = "handled"/moving
    private const val STILLNESS_WINDOW_MS = 5_000L
    private const val SPEED_WINDOW_MS = 60_000L        // speed-gate look-back
    private const val COUNTDOWN_SEC = 30
    private const val REARM_DEBOUNCE_MS = 30_000L      // ignore re-trigger for 30 s

    private const val MONITOR_NOTIF_ID = 0x0C04        // ongoing "monitoring" icon
    private const val COUNTDOWN_NOTIF_ID = 0x0C05      // the 30 s countdown

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var appCtx: Context? = null
    private var sensorManager: SensorManager? = null
    private var accelSensor: Sensor? = null
    @Volatile private var accelRegistered = false
    @Volatile private var armed = false               // speed gate open + enabled

    /** Timestamped speed samples (elapsedRealtime ms → km/h). */
    private val speedSamples = ArrayDeque<Pair<Long, Float>>()

    // Impact state machine.
    @Volatile private var watchingStillness = false
    @Volatile private var spikeAtMs = 0L
    @Volatile private var movedSinceSpike = false
    @Volatile private var lastFiredAtMs = 0L
    @Volatile private var countdownJob: Job? = null

    /** Called by ProtocolService on start. Idempotent. */
    fun attach(context: Context) {
        appCtx = context.applicationContext
        if (sensorManager == null) {
            sensorManager = appCtx?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
    }

    /** Called by ProtocolService on stop. Tears everything down. */
    fun detach() {
        disarm()
        cancelCountdown()
        speedSamples.clear()
    }

    /**
     * Feed a fresh location. Updates the speed history and arms/disarms
     * the accelerometer based on the speed gate. No-op (and disarms) if
     * the feature is off or the fix carries no speed.
     */
    fun onLocation(loc: Location) {
        val ctx = appCtx ?: return
        val store = CrashDetectionStore(ctx)
        if (!store.enabled) { if (armed) disarm(); return }
        if (!loc.hasSpeed()) return

        val now = SystemClock.elapsedRealtime()
        val kmh = loc.speed * 3.6f
        synchronized(speedSamples) {
            speedSamples.addLast(now to kmh)
            while (speedSamples.isNotEmpty() && now - speedSamples.first().first > SPEED_WINDOW_MS) {
                speedSamples.removeFirst()
            }
        }
        val maxRecent = synchronized(speedSamples) { speedSamples.maxOfOrNull { it.second } ?: 0f }
        val gateOpen = maxRecent >= store.speedGateKmh
        if (gateOpen && !armed) arm() else if (!gateOpen && armed) disarm()
    }

    private fun arm() {
        val sm = sensorManager ?: return
        val s = accelSensor ?: return
        if (!accelRegistered) {
            // GAME rate (~50 Hz) — fine enough to catch a sub-100ms
            // impact spike.
            accelRegistered = sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME)
        }
        armed = true
        postMonitoringNotification()
        Log.i(TAG, "armed (speed gate open)")
    }

    private fun disarm() {
        if (accelRegistered) {
            sensorManager?.unregisterListener(listener)
            accelRegistered = false
        }
        armed = false
        watchingStillness = false
        appCtx?.let { NotificationManagerCompat.from(it).cancel(MONITOR_NOTIF_ID) }
    }

    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val mag = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            // Net g (gravity removed): linear-accel sensor already
            // excludes gravity; raw accelerometer needs it subtracted.
            val netG = if (accelSensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                mag / 9.81f
            } else {
                kotlin.math.abs(mag - 9.81f) / 9.81f
            }
            onNetG(netG)
        }
    }

    private fun onNetG(netG: Float) {
        val ctx = appCtx ?: return
        val now = SystemClock.elapsedRealtime()
        if (countdownJob?.isActive == true) return          // already counting down
        if (now - lastFiredAtMs < REARM_DEBOUNCE_MS) return  // post-event debounce

        if (watchingStillness) {
            // Any real movement during the window = not a crash
            // (hard braking / phone handled). Abort.
            if (netG > STILLNESS_MOVE_G) movedSinceSpike = true
            return
        }

        val threshold = CrashDetectionStore(ctx).impactThresholdG
        if (netG >= threshold) {
            // Impact spike → watch for post-impact stillness.
            watchingStillness = true
            spikeAtMs = now
            movedSinceSpike = false
            Log.i(TAG, "impact spike ${"%.1f".format(netG)}G — watching for stillness")
            scope.launch {
                delay(STILLNESS_WINDOW_MS)
                val wasStill = !movedSinceSpike
                watchingStillness = false
                if (wasStill) {
                    Log.i(TAG, "post-impact stillness confirmed — starting countdown")
                    startCountdown()
                } else {
                    Log.i(TAG, "movement after impact — false positive, ignored")
                }
            }
        }
    }

    private fun startCountdown() {
        val ctx = appCtx ?: return
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (sec in COUNTDOWN_SEC downTo 1) {
                if (!isActive) return@launch
                postCountdownNotification(ctx, sec)
                delay(1_000L)
            }
            // Not cancelled → this is a real crash. Fire the existing
            // sos broadcast with the CRASH_DETECTED source tag.
            NotificationManagerCompat.from(ctx).cancel(COUNTDOWN_NOTIF_ID)
            lastFiredAtMs = SystemClock.elapsedRealtime()
            runCatching {
                AegisApp.instance.sosHandler.trigger(SOSTrigger.CRASH_DETECTED)
            }.onFailure { Log.w(TAG, "crash sos trigger failed", it) }
        }
    }

    /** Called by [CrashCancelReceiver] when the user taps "I'm OK". */
    fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        lastFiredAtMs = SystemClock.elapsedRealtime() // debounce after a cancel too
        appCtx?.let { NotificationManagerCompat.from(it).cancel(COUNTDOWN_NOTIF_ID) }
        Log.i(TAG, "countdown cancelled — I'm OK")
    }

    private fun launchIntent(ctx: Context): PendingIntent? {
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(
            ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun postMonitoringNotification() {
        val ctx = appCtx ?: return
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(ctx, AegisApp.CHANNEL_SERVICE)
            .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_car)
            .setColor(AegisApp.BRAND_CYAN_ARGB)
            .setContentTitle("Crash detection active")
            .setContentText("Watching for impacts while you're driving")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(launchIntent(ctx))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(MONITOR_NOTIF_ID, n) }
    }

    private fun postCountdownNotification(ctx: Context, sec: Int) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val cancelPi = PendingIntent.getBroadcast(
            ctx, 1,
            Intent(ctx, CrashCancelReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = launchIntent(ctx)
        val n = NotificationCompat.Builder(ctx, AegisApp.CHANNEL_SOS)
            .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_car)
            .setColor(AegisApp.BRAND_SOS_ARGB)
            .setContentTitle("Crash detected — are you OK?")
            .setContentText("Sending sos in ${sec}s. Tap “I'm OK” to cancel.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setProgress(COUNTDOWN_SEC, COUNTDOWN_SEC - sec, false)
            .addAction(0, "I'M OK", cancelPi)
            .apply { open?.let { setContentIntent(it); setFullScreenIntent(it, true) } }
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(COUNTDOWN_NOTIF_ID, n) }
    }
}

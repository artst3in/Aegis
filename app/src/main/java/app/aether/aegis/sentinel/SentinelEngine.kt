package app.aether.aegis.sentinel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log
import app.aether.aegis.AegisApp
import app.aether.aegis.sonar.SonarState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sentinel state machine — implements the unattended-phone
 * monitoring cascade.
 *
 * Phase 1 scope: state transitions, sensor management, wakelock,
 * event log writes. Notification path lives in phase 3; accelerometer
 * recording (3D model file) lives in phase 2. Per-frame accel samples
 * during RECORDING are currently dropped after being checked for
 * activity — the engine is structurally ready to fan them out to a
 * recorder once that class is built.
 *
 * State machine:
 *
 *   SONAR_ARMED     sonar pulsing, proximity + accel polling
 *      ↓ any sensor trip
 *   PROXIMITY_ARMED sonar OFF, proximity + accel polling
 *      ↓ proximity / accel trip
 *   RECORDING       sonar OFF, proximity polling, accel streaming
 *      (wakelock held)
 *      ↓ 30 s of accel quiet
 *   PROXIMITY_ARMED 30 s grace before clicks resume
 *      ↓ 30 s no trip
 *   SONAR_ARMED
 *
 * Anything that trips out of cascade order escalates straight to the
 * matching stage — "never assume false positives." Accel trip during
 * SONAR_ARMED jumps to RECORDING; proximity trip jumps to
 * PROXIMITY_ARMED.
 */
class SentinelEngine(private val context: Context) {

    private val appCtx = context.applicationContext
    private val prefs by lazy { SentinelPrefs(appCtx) }
    private val eventLog by lazy { SentinelEventLog(appCtx) }
    private val recording by lazy { SentinelRecording(appCtx) }
    private val sonar by lazy { SonarState.engine(appCtx) }
    private val sensorManager = appCtx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val powerManager = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val proximitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var supervisorJob: Job? = null
    private var sonarObserverJob: Job? = null
    private var batteryObserverJob: Job? = null
    private var callObserverJob: Job? = null
    private var autoArmObserverJob: Job? = null

    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    private val _stage = MutableStateFlow(SentinelStage.OFF)
    val stage: StateFlow<SentinelStage> = _stage.asStateFlow()

    /** Highest stage notified about during the current lock-session.
     *  Reset to OFF on [onUserUnlock]. The notification path (phase 3)
     *  reads this to decide whether a stage transition counts as
     *  "news" worth pinging the contact list about. */
    private val _watermark = MutableStateFlow(SentinelStage.OFF)
    val watermark: StateFlow<SentinelStage> = _watermark.asStateFlow()

    /** Epoch-ms of the last sensor activity at any stage. Used by the
     *  supervisor loop to drive backward transitions. */
    @Volatile private var lastActivityMs: Long = 0L

    /** Epoch-ms when watermark was last raised. TIMED throttle uses
     *  this to know when to auto-reset. */
    @Volatile private var lastWatermarkAtMs: Long = 0L

    /** Last accel magnitude observed. ACCEL listener updates this on
     *  every sample; the supervisor loop reads it to test for "quiet"
     *  transitions. */
    @Volatile private var lastAccelMag: Double = 0.0

    /** Latest gyroscope reading. Gyro samples are interleaved with
     *  accel samples in the recording stream; we cache the last gyro
     *  values so that when an accel sample arrives we can pair them
     *  into a single 6-channel row instead of writing them
     *  separately. */
    @Volatile private var lastGyroX: Float = 0f
    @Volatile private var lastGyroY: Float = 0f
    @Volatile private var lastGyroZ: Float = 0f

    /** Most-recent sealed recording file. The notification path
     *  attaches this when the watermark raises (potentially to a
     *  stage AFTER RECORDING has already ended, e.g. the cascade
     *  passes through RECORDING and the watermark notification
     *  fires for the RECORDING level only after sealing). */
    @Volatile private var lastSealedRecording: java.io.File? = null

    /** Per-stage pending notification jobs (notification
     *  delays). Each watermark raise schedules a delayed-fire job; the
     *  owner-unlock path cancels every pending job at once. Map is
     *  guarded by `this`. */
    private val pendingNotifications = mutableMapOf<SentinelStage, Job>()

    /** Mugshot files captured during the current cascade session.
     *  On RECORDING entry, the front camera captures 3-5
     *  stills at 1 s intervals. If the owner unlocks before any
     *  notification fires, these are deleted — "no reason to keep the
     *  owner's face on disk." If the notification DOES fire, the
     *  newest is attached. */
    private val sessionMugshots = java.util.concurrent.CopyOnWriteArrayList<java.io.File>()
    @Volatile private var mugshotJob: Job? = null
    /** Tracks whether ANY notification from this session has actually
     *  fired. If false at unlock-time, all session mugshots get
     *  deleted (owner identity not preserved). If true, they stay
     *  (forensic record of who didn't unlock in time). */
    @Volatile private var sessionAlertFired: Boolean = false

    /** Engine state visible to the arming supervisor. ARMING_QUIET_SINCE
     *  tracks the wall-clock time of the last sensor activity while in
     *  ARMING; the supervisor uses it to decide when room-stillness
     *  has elapsed long enough to flip ARMING → SONAR_ARMED. */
    @Volatile private var armingQuietSinceMs: Long = 0L

    /** Battery-protection mode. When true, sonar
     *  has been stopped because battery dropped below the threshold;
     *  proximity + accel continue but no notifications go out. */
    @Volatile private var batterySaverEngaged: Boolean = false

    /** Drill mode flag. When true, all notifications fire with the
     *  [DRILL] tag and recipients are tracked via SentinelPrefs
     *  drillPendingRecipients / drillConfirmedRecipients. Set by
     *  [startDrill]; cleared by [finishDrill] or by drill timeout in
     *  the supervisor. */
    @Volatile private var inDrillMode: Boolean = false

    // ---- Sensor listeners ----------------------------------------------

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Binary near/far: anything below
            // sensor max range counts as "near".
            val maxRange = proximitySensor?.maximumRange ?: 5f
            val near = event.values[0] < maxRange
            if (near) onProximityTrip()
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    }

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // TYPE_LINEAR_ACCELERATION already has gravity subtracted.
            // TYPE_ACCELEROMETER includes gravity (~9.81 baseline at rest).
            // We computed the magnitude either way; the quiet threshold
            // is calibrated against whichever sensor type is delivering.
            val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
            val mag = sqrt((ax * ax + ay * ay + az * az).toDouble())
            val activity = if (accelSensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) mag
                           else abs(mag - GRAVITY)
            lastAccelMag = activity
            // Stream into the per-event recording (phase 2). The
            // recorder is a no-op when no file is open, so it's safe
            // to call on every sample regardless of stage.
            if (_stage.value == SentinelStage.RECORDING && recording.isRecording()) {
                recording.append(ax, ay, az, lastGyroX, lastGyroY, lastGyroZ)
            }
            if (activity > ACCEL_TRIP_THRESHOLD) onAccelTrip(activity)
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    }

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            lastGyroX = event.values[0]
            lastGyroY = event.values[1]
            lastGyroZ = event.values[2]
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    }

    // ---- Public lifecycle ---------------------------------------------

    /** Arm the cascade. Per the arming-flow design, this does
     *  NOT immediately make the cascade live — it transitions to
     *  ARMING, where sonar starts so it can detect the user leaving.
     *  After [SentinelPrefs.armingQuietPeriodSec] of room-stillness,
     *  the arming supervisor flips ARMING → SONAR_ARMED and the mine
     *  is live. Idempotent — second call while armed is a no-op. */
    fun arm() {
        if (_stage.value != SentinelStage.OFF) return
        prefs.armed = true
        log("ARM (entering arming phase)")
        armingQuietSinceMs = System.currentTimeMillis()
        sessionAlertFired = false
        sessionMugshots.clear()
        transitionTo(SentinelStage.ARMING, "armed by user")
        startSupervisor()
        startBatteryObserver()
        startCallObserver()
    }

    fun disarm() {
        prefs.armed = false
        log("DISARM")
        // Cancel any pending notifications + clean up session
        // mugshots if no alert ever fired.
        cancelPendingNotifications("disarm")
        cleanupSessionMugshots(force = !sessionAlertFired)
        mugshotJob?.cancel(); mugshotJob = null
        stopAllSensors()
        sonar.stop()
        supervisorJob?.cancel(); supervisorJob = null
        sonarObserverJob?.cancel(); sonarObserverJob = null
        batteryObserverJob?.cancel(); batteryObserverJob = null
        callObserverJob?.cancel(); callObserverJob = null
        autoArmObserverJob?.cancel(); autoArmObserverJob = null
        releaseWakeLock()
        batterySaverEngaged = false
        _stage.value = SentinelStage.OFF
        _watermark.value = SentinelStage.OFF
    }

    /** Kick off a guided Sentinel drill. Snapshots the current
     *  notify-list as the pending-recipient set, arms the cascade in
     *  drill mode, and lets the user walk through the cascade. Each
     *  delayed notification fires with the [DRILL] tag so recipients
     *  see "🛡️ DRILL — tap to confirm" rather than a real alert.
     *
     *  Returns false (and does nothing) if a drill is on cooldown
     *  (24 h since last) or if sentinel is already armed in real
     *  mode — drills are exclusive of real operation. */
    fun startDrill(): Boolean {
        if (prefs.drillCooldownRemainingMs() > 0L) {
            Log.w(TAG, "drill blocked — ${prefs.drillCooldownRemainingMs() / 1000}s cooldown remaining")
            return false
        }
        if (_stage.value != SentinelStage.OFF) {
            Log.w(TAG, "drill blocked — sentinel already armed")
            return false
        }
        val recipients = prefs.notifyList
        if (recipients.isEmpty()) {
            Log.w(TAG, "drill blocked — notify-list is empty")
            return false
        }
        log("DRILL start — ${recipients.size} recipient(s)")
        inDrillMode = true
        prefs.drillStartedAt = System.currentTimeMillis()
        prefs.drillPendingRecipients = recipients
        prefs.drillConfirmedRecipients = emptySet()
        arm()
        return true
    }

    /** Force-end the current drill — used on user cancel or on the
     *  5-minute confirmation timeout. Resets engine + cleans up
     *  artefacts; does NOT mark lastDrillAt (only full confirmation
     *  passes the drill). */
    fun cancelDrill() {
        if (!inDrillMode) return
        log("DRILL cancelled")
        inDrillMode = false
        prefs.drillStartedAt = 0L
        prefs.drillPendingRecipients = emptySet()
        prefs.drillConfirmedRecipients = emptySet()
        disarm()
    }

    /** Called by [LockState.unlock] when the user successfully
     *  enters their REAL PIN (duress unlocks deliberately skip this).
     *  Resets the watermark, cancels all pending
     *  notifications, and deletes session mugshots if no alert
     *  actually fired ("No reason to keep the owner's face
     *  on disk."). */
    fun onUserUnlock() {
        if (_watermark.value != SentinelStage.OFF) {
            log("WATERMARK reset on unlock (was ${_watermark.value.label})")
            _watermark.value = SentinelStage.OFF
        }
        cancelPendingNotifications("user unlock")
        if (!sessionAlertFired) {
            cleanupSessionMugshots(force = true)
        }
        // Owner's face shouldn't be cached even across sessions if
        // they unlocked in time — start fresh on next session.
        sessionMugshots.clear()
        sessionAlertFired = false
    }

    // ---- State machine -------------------------------------------------

    /** Anything that comes back saying "I saw something" goes through
     *  here — and the engine decides whether to escalate the stage
     *  based on the cascade rules. */
    @Synchronized
    private fun escalate(target: SentinelStage, reason: String) {
        lastActivityMs = System.currentTimeMillis()
        val cur = _stage.value
        if (target.ordinal <= cur.ordinal) {
            // Same stage or lower — don't downgrade on a trip. Backward
            // transitions only happen via timeouts in the supervisor.
            return
        }
        transitionTo(target, reason)
    }

    @Synchronized
    private fun transitionTo(target: SentinelStage, reason: String) {
        val prev = _stage.value
        if (prev == target) return
        Log.i(TAG, "transition $prev → $target ($reason)")
        eventLog.append(
            timestampMs = System.currentTimeMillis(),
            stage = target,
            sensor = SensorId.STAGE_TRANSITION,
            magnitude = 0,
        )
        // Seal any open recording before changing stage. If we're
        // LEAVING RECORDING, the file is finalised here; if we're
        // entering RECORDING, no recording is open yet.
        if (prev == SentinelStage.RECORDING && target != SentinelStage.RECORDING) {
            val sealed = recording.stop()
            sealed?.let {
                Log.i(TAG, "recording sealed: ${it.name} (${it.length()} bytes)")
                lastSealedRecording = it
            }
            // Gyro is only needed during RECORDING — release it.
            if (gyroRegistered) {
                sensorManager.unregisterListener(gyroListener)
                gyroRegistered = false
            }
        }
        // Per-stage sensor configuration.
        when (target) {
            SentinelStage.OFF -> {
                stopAllSensors(); sonar.stop(); releaseWakeLock()
            }
            SentinelStage.ARMING -> {
                // Same sensor posture as SONAR_ARMED — sonar pulsing so
                // we can detect the user leaving the room. But the
                // arming supervisor watches for room-stillness before
                // letting the cascade go live.
                ensureProximityRegistered(SensorManager.SENSOR_DELAY_NORMAL)
                ensureAccelRegistered(SensorManager.SENSOR_DELAY_NORMAL)
                if (!sonar.running.value) sonar.start()
                releaseWakeLock()
                armingQuietSinceMs = System.currentTimeMillis()
            }
            SentinelStage.SONAR_ARMED -> {
                // Sonar pulsing + proximity NORMAL + accel NORMAL.
                ensureProximityRegistered(SensorManager.SENSOR_DELAY_NORMAL)
                ensureAccelRegistered(SensorManager.SENSOR_DELAY_NORMAL)
                if (!sonar.running.value) sonar.start()
                releaseWakeLock()
            }
            SentinelStage.PROXIMITY_ARMED -> {
                // Sonar OFF + proximity NORMAL + accel NORMAL.
                sonar.stop()
                ensureProximityRegistered(SensorManager.SENSOR_DELAY_NORMAL)
                ensureAccelRegistered(SensorManager.SENSOR_DELAY_NORMAL)
                releaseWakeLock()
            }
            SentinelStage.RECORDING -> {
                // Sonar OFF + proximity NORMAL + accel GAME (≈50 Hz)
                // + gyro GAME for the 3D-model recording.
                // Wakelock acquired so Doze can't drop samples mid-grab.
                sonar.stop()
                ensureProximityRegistered(SensorManager.SENSOR_DELAY_NORMAL)
                ensureAccelRegistered(SensorManager.SENSOR_DELAY_GAME)
                ensureGyroRegistered(SensorManager.SENSOR_DELAY_GAME)
                acquireWakeLock()
                // Start the per-event recording. The file is sealed
                // when we transition OUT of RECORDING (in the next
                // call to transitionTo), so the file always reflects
                // exactly one cascade peak.
                if (!recording.isRecording()) {
                    recording.start(System.currentTimeMillis())
                }
                // Front-camera mugshot burst on entry
                // to RECORDING. 5 stills @ 1 s intervals. If the
                // owner unlocks before any notification fires, these
                // get deleted on unlock (face-not-cached). If the
                // notification fires, the newest is attached.
                startMugshotBurst()
                // Skill-tree-node "tested" precondition: any cascade
                // reaching RECORDING on real hardware is the proof.
                // Once set, prefs.lastDrillAt stays — the user has
                // demonstrated the feature works end-to-end on their
                // device. Implicit-drill model for log-only users
                // and as a fast path when no explicit drill is in
                // progress. During an explicit drill, the ACK handler
                // sets lastDrillAt only after all recipients confirm,
                // so we skip the implicit mark here.
                if (prefs.lastDrillAt == 0L && !inDrillMode) {
                    prefs.lastDrillAt = System.currentTimeMillis()
                    Log.i(TAG, "implicit drill recorded — Sonar tree node now configurable")
                }
            }
        }
        _stage.value = target
        // Watermark only ratchets UP — never down. Backward transitions
        // do not reset the watermark; only unlock does.
        if (target.ordinal > _watermark.value.ordinal) {
            _watermark.value = target
            lastWatermarkAtMs = System.currentTimeMillis()
            Log.i(TAG, "watermark raised to ${target.label}")
            maybeFireNotification(target)
        }
    }

    /** Stage-watermark notification gate. "A higher stage
     *  reached = news worth pinging. A repeat trip at a stage already
     *  pinged = not news, regardless of throttle." Notifications are
     *  QUEUED with a per-stage delay,
     *  not fired immediately. If the owner unlocks during the delay,
     *  the pending notification is cancelled — only someone who can
     *  NOT unlock the phone triggers an alert. */
    private fun maybeFireNotification(stage: SentinelStage) {
        if (prefs.throttle == SentinelThrottle.NEVER) return
        if (batterySaverEngaged) {
            log("notification suppressed — battery-saver mode")
            return
        }
        scheduleDelayedNotification(stage)
    }

    /** Hook for the EVERY throttle mode: every trip schedules a
     *  fresh delayed notification (still subject to the per-stage
     *  delay + owner-unlock cancellation). UNTIL_UNLOCK + TIMED
     *  both honour the watermark-only path above. */
    private fun maybeFireEveryTripNotification(stage: SentinelStage) {
        if (prefs.throttle != SentinelThrottle.EVERY) return
        if (batterySaverEngaged) return
        scheduleDelayedNotification(stage)
    }

    private fun scheduleDelayedNotification(stage: SentinelStage) {
        val delaySec = when (stage) {
            SentinelStage.SONAR_ARMED     -> prefs.delaySonarSec
            SentinelStage.PROXIMITY_ARMED -> prefs.delayProximitySec
            SentinelStage.RECORDING       -> prefs.delayRecordingSec
            else -> return  // OFF / ARMING never notify
        }
        // If a pending job already exists for this stage, leave it.
        // The first crossing into a stage is the one that counts; a
        // re-trip at the same stage within the delay window must NOT
        // reset the timer (otherwise sustained activity would push
        // the notification out forever).
        synchronized(this) {
            if (pendingNotifications[stage]?.isActive == true) return
        }
        val drillTag = inDrillMode
        val job = scope.launch {
            kotlinx.coroutines.delay(delaySec * 1000L)
            // Owner unlock during the delay cancels this job before
            // it runs; if we got here, the delay elapsed without
            // unlock. Fire for real (or for drill).
            sessionAlertFired = true
            // A REAL Sentinel alert fired (not a calibration drill) →
            // earn the Watchtower badge.
            // Gated on !drillTag: there is no "test" path to a badge.
            if (!drillTag) {
                app.aether.aegis.achievements.Achievements.unlock(
                    app.aether.aegis.achievements.Achievement.WATCHTOWER,
                )
            }
            SentinelNotifier.fire(
                appCtx, stage,
                lastSealedRecording,
                sessionMugshots.lastOrNull(),
                isDrill = drillTag,
            )
        }
        synchronized(this) {
            pendingNotifications[stage] = job
        }
        log("notification for ${stage.label} queued (delay ${delaySec}s)")
    }

    private fun cancelPendingNotifications(reason: String) {
        synchronized(this) {
            if (pendingNotifications.isEmpty()) return
            log("cancelling ${pendingNotifications.size} pending notifications: $reason")
            pendingNotifications.values.forEach { it.cancel() }
            pendingNotifications.clear()
        }
    }

    // ---- Mugshot burst at RECORDING ------------------------------------

    private fun startMugshotBurst() {
        if (mugshotJob?.isActive == true) return
        mugshotJob = scope.launch {
            try {
                repeat(MUGSHOT_BURST_COUNT) { i ->
                    if (!isActive) return@launch
                    val f = runCatching {
                        app.aether.aegis.mugshot.MugshotCapture.captureSingleLens(appCtx, "front")
                    }.getOrNull()
                    if (f != null) sessionMugshots.add(f)
                    if (i < MUGSHOT_BURST_COUNT - 1) {
                        kotlinx.coroutines.delay(MUGSHOT_BURST_INTERVAL_MS)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "mugshot burst failed", t)
            }
        }
    }

    private fun cleanupSessionMugshots(force: Boolean) {
        if (!force) return
        val snapshot = sessionMugshots.toList()
        sessionMugshots.clear()
        scope.launch {
            snapshot.forEach { f ->
                runCatching { f.delete() }
            }
            if (snapshot.isNotEmpty()) {
                Log.i(TAG, "deleted ${snapshot.size} session mugshots (owner unlocked in time)")
            }
        }
    }

    // ---- Battery protection --------------------------

    private fun startBatteryObserver() {
        batteryObserverJob?.cancel()
        batteryObserverJob = scope.launch {
            val pb = AegisApp.instance.powerBudget
            pb.level.collect { pct ->
                when {
                    pct <= BATT_DISARM_PCT -> {
                        if (_stage.value != SentinelStage.OFF) {
                            log("battery $pct% — full disarm")
                            disarm()
                        }
                    }
                    pct <= BATT_SAVER_PCT -> {
                        if (!batterySaverEngaged) {
                            log("battery $pct% — saver mode (sonar off, no notifications)")
                            batterySaverEngaged = true
                            sonar.stop()
                            cancelPendingNotifications("battery saver")
                        }
                    }
                    else -> {
                        if (batterySaverEngaged) {
                            log("battery $pct% recovered — saver mode off")
                            batterySaverEngaged = false
                            // Re-engage sonar if we were in a stage
                            // that wants it.
                            if (_stage.value == SentinelStage.ARMING ||
                                _stage.value == SentinelStage.SONAR_ARMED) {
                                if (!sonar.running.value) sonar.start()
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Call-answered watermark reset ---------------

    private fun startCallObserver() {
        callObserverJob?.cancel()
        callObserverJob = scope.launch {
            // Track call lifecycle. When an active call goes from
            // non-null → null, the user (or peer) just hung up. If
            // the phone subsequently goes quiet, the watermark
            // resets — answering the call is implicit session
            // boundary acknowledgement.
            var hadActive = false
            app.aether.aegis.call.CallStore.active.collect { call ->
                if (call != null) hadActive = true
                else if (hadActive) {
                    hadActive = false
                    // Give 10 s for the phone to come to rest after
                    // the call ends, then reset watermark.
                    kotlinx.coroutines.delay(10_000L)
                    if (lastAccelMag < ACCEL_QUIET_THRESHOLD &&
                        _watermark.value != SentinelStage.OFF) {
                        log("watermark reset — call answered + phone at rest")
                        _watermark.value = SentinelStage.OFF
                        cancelPendingNotifications("call answered")
                    }
                }
            }
        }
    }

    // ---- Sensor management --------------------------------------------

    @Volatile private var proxRegistered = false
    @Volatile private var accelRegistered = false
    @Volatile private var accelDelay = -1
    @Volatile private var gyroRegistered = false

    private fun ensureProximityRegistered(delay: Int) {
        if (proximitySensor == null) return
        if (proxRegistered) return
        proxRegistered = sensorManager.registerListener(proximityListener, proximitySensor, delay)
    }

    private fun ensureAccelRegistered(delay: Int) {
        if (accelSensor == null) return
        if (accelRegistered && accelDelay == delay) return
        if (accelRegistered) sensorManager.unregisterListener(accelListener)
        accelRegistered = sensorManager.registerListener(accelListener, accelSensor, delay)
        accelDelay = delay
    }

    private fun ensureGyroRegistered(delay: Int) {
        if (gyroSensor == null) return
        if (gyroRegistered) return
        gyroRegistered = sensorManager.registerListener(gyroListener, gyroSensor, delay)
    }

    private fun stopAllSensors() {
        if (proxRegistered) {
            sensorManager.unregisterListener(proximityListener)
            proxRegistered = false
        }
        if (accelRegistered) {
            sensorManager.unregisterListener(accelListener)
            accelRegistered = false
            accelDelay = -1
        }
        if (gyroRegistered) {
            sensorManager.unregisterListener(gyroListener)
            gyroRegistered = false
        }
        // Seal any open recording. Engine disarm shouldn't leave a
        // dangling file with no count backfilled.
        if (recording.isRecording()) recording.stop()
    }

    // ---- Wakelock -----------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val wl = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "aegis:sentinel_recording",
        )
        wl.setReferenceCounted(false)
        wl.acquire(RECORDING_MAX_MS)  // hard ceiling; supervisor releases earlier on quiet
        wakeLock = wl
        Log.i(TAG, "wakelock acquired")
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        try { if (wl.isHeld) wl.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    // ---- Sensor trip handlers -----------------------------------------

    private fun onProximityTrip() {
        lastActivityMs = System.currentTimeMillis()
        eventLog.append(
            timestampMs = lastActivityMs,
            stage = _stage.value,
            sensor = SensorId.PROXIMITY,
            magnitude = 1,  // binary near
        )
        // Each discrete trip advances the cascade ONE stage:
        //   SONAR_ARMED      → PROXIMITY_ARMED (cascade entry)
        //   PROXIMITY_ARMED  → RECORDING       (cascade advance)
        //   RECORDING        → (no-op; just refresh activity)
        val target = when (_stage.value) {
            SentinelStage.OFF             -> return
            SentinelStage.ARMING          -> {
                // During arming, proximity = user still here; reset
                // the room-stillness timer and don't escalate.
                armingQuietSinceMs = lastActivityMs
                return
            }
            SentinelStage.SONAR_ARMED     -> SentinelStage.PROXIMITY_ARMED
            SentinelStage.PROXIMITY_ARMED -> SentinelStage.RECORDING
            SentinelStage.RECORDING       -> return
        }
        escalate(target, "proximity trip from ${_stage.value.label}")
        maybeFireEveryTripNotification(_stage.value)
    }

    private fun onAccelTrip(activity: Double) {
        lastActivityMs = System.currentTimeMillis()
        eventLog.append(
            timestampMs = lastActivityMs,
            stage = _stage.value,
            sensor = SensorId.ACCEL,
            magnitude = (activity * 100).toInt().coerceIn(0, Short.MAX_VALUE.toInt()),
        )
        // ARMING: accel = user still here; reset arming timer.
        if (_stage.value == SentinelStage.ARMING) {
            armingQuietSinceMs = lastActivityMs
            return
        }
        // Accel trip out of cascade order = jump straight to RECORDING.
        // Per spec: "Nothing is filtered as a false positive."
        if (_stage.value.ordinal < SentinelStage.RECORDING.ordinal &&
            _stage.value != SentinelStage.OFF) {
            escalate(SentinelStage.RECORDING, "accel trip mag=%.2f".format(activity))
        }
        maybeFireEveryTripNotification(_stage.value)
    }

    private fun onSonarTrip(timestampMs: Long) {
        lastActivityMs = timestampMs
        eventLog.append(
            timestampMs = timestampMs,
            stage = _stage.value,
            sensor = SensorId.SONAR,
            magnitude = 1,  // sonar's own delta is in [SonarEngine.detectionThreshold] range
        )
        when (_stage.value) {
            SentinelStage.ARMING -> {
                // During arming, sonar trips just reset the
                // "room still" timer. The cascade doesn't go live
                // until the user has been gone long enough.
                armingQuietSinceMs = timestampMs
            }
            SentinelStage.SONAR_ARMED -> {
                escalate(SentinelStage.PROXIMITY_ARMED, "sonar trip")
                maybeFireEveryTripNotification(_stage.value)
            }
            else -> { /* sonar is off at PROXIMITY_ARMED+; this branch shouldn't fire */ }
        }
    }

    // ---- Supervisor loop ----------------------------------------------

    /** Drives the backward transitions (timeouts). The supervisor
     *  doesn't run sensors itself — it just polls the wall clock
     *  against [lastActivityMs] to decide when to relax the cascade. */
    private fun startSupervisor() {
        supervisorJob?.cancel()
        supervisorJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val sinceActivity = now - lastActivityMs
                val cur = _stage.value
                when (cur) {
                    SentinelStage.ARMING -> {
                        // Room-stillness based arming. When
                        // armingQuietPeriodSec has elapsed with NO
                        // sensor activity, flip ARMING → SONAR_ARMED.
                        // "The mine is live."
                        val sinceArmingQuiet = now - armingQuietSinceMs
                        if (sinceArmingQuiet > prefs.armingQuietPeriodSec * 1000L) {
                            log("arming complete — room still for ${sinceArmingQuiet}ms")
                            transitionTo(SentinelStage.SONAR_ARMED, "arming quiet elapsed")
                        }
                    }
                    SentinelStage.RECORDING -> {
                        // Drop to PROXIMITY_ARMED after 30 s of accel quiet.
                        if (sinceActivity > STAGE_TIMEOUT_MS && lastAccelMag < ACCEL_QUIET_THRESHOLD) {
                            log("recording → proximity (quiet ${sinceActivity}ms)")
                            transitionTo(SentinelStage.PROXIMITY_ARMED, "recording timeout")
                        }
                    }
                    SentinelStage.PROXIMITY_ARMED -> {
                        // Drop to SONAR_ARMED after 30 s of no trip.
                        if (sinceActivity > STAGE_TIMEOUT_MS) {
                            log("proximity → sonar (quiet ${sinceActivity}ms)")
                            transitionTo(SentinelStage.SONAR_ARMED, "proximity timeout")
                        }
                    }
                    else -> { /* nothing to time out from */ }
                }
                // Honour TIMED throttle: watermark resets after N min
                // of no further escalation. This lets the next trip
                // ping again even mid-session.
                if (prefs.throttle == SentinelThrottle.TIMED &&
                    _watermark.value != SentinelStage.OFF &&
                    (now - lastWatermarkAtMs) > prefs.timedIntervalMinutes * 60_000L
                ) {
                    log("watermark auto-reset (TIMED throttle)")
                    _watermark.value = SentinelStage.OFF
                }
                // Drill 5-minute timeout. If not every recipient has
                // confirmed by then, the drill fails — engine disarms,
                // prefs reset, lastDrillAt unchanged. UI sees the
                // unconfirmed set + can show "who didn't respond".
                if (inDrillMode && prefs.drillStartedAt > 0L &&
                    (now - prefs.drillStartedAt) > DRILL_TIMEOUT_MS
                ) {
                    log("DRILL timed out — ${prefs.drillPendingRecipients.size - prefs.drillConfirmedRecipients.size} recipient(s) didn't confirm")
                    cancelDrill()
                }
                // Drill-pass detection: the ACK handler resets
                // drillStartedAt to 0 the moment every recipient
                // confirms. When that happens, exit drill mode and
                // disarm — the test is complete.
                if (inDrillMode && prefs.drillStartedAt == 0L &&
                    prefs.drillPendingRecipients.isEmpty()
                ) {
                    log("DRILL passed — all recipients confirmed")
                    inDrillMode = false
                    disarm()
                }
                delay(SUPERVISOR_TICK_MS)
            }
        }
        // Mirror sonar's own internal detection into our cascade. Sonar
        // posts to lastDetectionAt every time delta crosses threshold;
        // we forward those into the sentinel state machine.
        sonarObserverJob?.cancel()
        sonarObserverJob = sonar.lastDetectionAt
            .filterNotNull()
            .onEach { ts ->
                if (_stage.value == SentinelStage.SONAR_ARMED) onSonarTrip(ts)
            }
            .launchIn(scope)
    }

    private fun log(msg: String) = Log.i(TAG, msg)

    /** Auto-arm observer. When enabled, the engine
     *  auto-arms when ALL of:
     *   - phone is charging
     *   - phone is locked (lockState.isLocked == true)
     *   - phone has been stationary for [autoArmStationaryMinutes]
     *  This is the bedside-overnight use case: plug in, fall asleep,
     *  phone watches itself. The observer is always running while
     *  Aegis is alive, but it only triggers when the user has opted
     *  in via prefs.autoArmEnabled.
     *
     *  Kept here on SentinelEngine rather than as a separate worker
     *  so it shares the same scope + sensor handles. */
    fun startAutoArmObserver() {
        autoArmObserverJob?.cancel()
        autoArmObserverJob = scope.launch {
            var stationarySinceMs = 0L
            while (isActive) {
                kotlinx.coroutines.delay(15_000L)  // 15 s poll
                if (!prefs.autoArmEnabled) continue
                if (_stage.value != SentinelStage.OFF) continue  // already armed
                val pb = AegisApp.instance.powerBudget
                pb.refresh()
                if (!pb.charging.value) { stationarySinceMs = 0L; continue }
                val locked = runCatching { AegisApp.instance.lockState.isLocked }.getOrElse { false }
                if (!locked) { stationarySinceMs = 0L; continue }
                if (lastAccelMag > ACCEL_QUIET_THRESHOLD) { stationarySinceMs = System.currentTimeMillis(); continue }
                if (stationarySinceMs == 0L) stationarySinceMs = System.currentTimeMillis()
                val sinceStationary = System.currentTimeMillis() - stationarySinceMs
                if (sinceStationary >= prefs.autoArmStationaryMinutes * 60_000L) {
                    log("auto-arm triggered (charger + locked + stationary ${sinceStationary}ms)")
                    arm()
                    stationarySinceMs = 0L
                }
            }
        }
    }

    companion object {
        private const val TAG = "SentinelEngine"
        /** Per spec: 30 s in each backward transition. */
        private const val STAGE_TIMEOUT_MS = 30_000L
        private const val SUPERVISOR_TICK_MS = 1_000L
        /** Hard ceiling on a wakelock — supervisor releases earlier. */
        private const val RECORDING_MAX_MS = 10L * 60_000L
        /** Standard gravity. Used for ACCELEROMETER fallback to
         *  estimate "motion above resting". */
        private const val GRAVITY = 9.81
        /** Linear-acceleration magnitude (m/s²) above which we treat a
         *  sample as activity. ~0.5 covers a hand tap; below that is
         *  generally device-internal noise. */
        private const val ACCEL_TRIP_THRESHOLD = 0.5
        /** Quiet threshold for RECORDING → PROXIMITY_ARMED. Lower
         *  than the trip threshold to give a hysteresis margin so the
         *  engine doesn't bounce. */
        private const val ACCEL_QUIET_THRESHOLD = 0.2
        /** Battery-protection thresholds. */
        private const val BATT_SAVER_PCT = 15
        private const val BATT_DISARM_PCT = 5
        /** Mugshot burst on RECORDING entry. */
        private const val MUGSHOT_BURST_COUNT = 5
        private const val MUGSHOT_BURST_INTERVAL_MS = 1_000L
        /** Drill recipient-confirmation deadline. Per spec: matches
         *  SOS Drill's 5-minute window. */
        private const val DRILL_TIMEOUT_MS = 5L * 60L * 1000L
    }
}

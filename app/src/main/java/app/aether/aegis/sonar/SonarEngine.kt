package app.aether.aegis.sonar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ultrasonic sonar engine.
 *
 * Emits short 18-22 kHz tone bursts from the speaker and listens for
 * the echo via the microphone, applying a Goertzel filter to isolate
 * the target frequency band. Single-bin extraction beats a full FFT
 * here because we only care about one band; CPU is a fraction.
 *
 * The loop is randomised — 300-700 ms intervals,
 * 30-50 ms pulses, frequency adjustable per device. Randomisation
 * prevents pattern detection (human rhythm-spotting, ultrasound
 * detector apps, criminal awareness).
 *
 * The whole feature is hidden behind the
 * Experimental gate; this class is constructed only when the user
 * has explicitly enabled it, so zero AudioTrack/AudioRecord work
 * runs by default.
 *
 * Phase 1 deliverable: live raw readings exposed via [latest] for the
 * SonarScreen viewer to render.
 *
 * Phase 2 ("sonar → Loki"): emits ambient-threat detections
 * when the magnitude delta crosses [detectionThreshold] for two
 * consecutive readings, surfaced on the Security tab alongside the
 * other Loki sensors. Two-in-a-row gating drops one-frame artefacts
 * (engine fan, fridge cycle); a real proximity ping holds across at
 * least one measurement window.
 */
class SonarEngine(private val context: Context) {

    /** Persisted tuning store — engine seeds its tunables from here on
     *  construction and writes back when auto-calibrate finishes. The
     *  individual @Volatile fields below are the live snapshot the
     *  loop reads; persistence happens on calibration commit, not on
     *  every slider drag. */
    private val prefs: SonarPrefs by lazy { SonarPrefs(context) }

    data class Reading(
        val timestamp: Long,
        val frequencyHz: Int,
        val magnitude: Double,
        /** Phase in radians, derived from the Goertzel terminator. */
        val phase: Double,
        /** Magnitude change vs the previous reading — interpret as
         *  "something in the field just moved". */
        val deltaFromPrev: Double,
    )

    private val _latest = MutableStateFlow<Reading?>(null)
    val latest: StateFlow<Reading?> = _latest.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Magnitude-delta threshold for the Loki-grade "something moved"
     *  signal. Defaults conservatively to a value that fires on
     *  almost any room motion; user can crank it up from SonarScreen
     *  once they've confirmed real detections + want fewer false
     *  positives from HVAC / breathing / fans. Hardware varies wildly
     *  at 20 kHz so this MUST be tunable, not a constant. */
    @Volatile var detectionThreshold: Double = SonarPrefs(context).threshold

    /** Rolling count of detections inside the current UTC day. Resets
     *  at midnight via the tick that crosses the day boundary. */
    private val _detectionsToday = MutableStateFlow(0)
    val detectionsToday: StateFlow<Int> = _detectionsToday.asStateFlow()
    private var detectionsDayKey: Long = currentDayKey()

    /** Epoch-ms of the latest detection. Null when there hasn't been
     *  one this process lifetime. */
    private val _lastDetectionAt = MutableStateFlow<Long?>(null)
    val lastDetectionAt: StateFlow<Long?> = _lastDetectionAt.asStateFlow()

    /** Callback fired on every detection — used to surface a
     *  notification from the host (AegisApp). Set once at construction
     *  via [setOnDetection]; lambda captures the timestamp + magnitude
     *  delta so the notification body can report what was seen. */
    @Volatile private var onDetection: ((Long, Double) -> Unit)? = null

    fun setOnDetection(cb: ((Long, Double) -> Unit)?) {
        onDetection = cb
    }

    /** User-adjustable target band (per-device
     *  hearing test). Defaults to 19 kHz — most adults can't hear
     *  it, but phone-speaker high-frequency rolloff is steep above
     *  ~19 kHz on the average handset, so 20 kHz was running into
     *  "the speaker can't actually reproduce the tone" territory.
     *  19 kHz still passes the inaudibility bar (adult hearing limit
     *  ~18 kHz declining with age) and the bounce is much stronger. */
    @Volatile var targetFrequencyHz: Int = SonarPrefs(context).frequencyHz
        set(value) {
            field = value.coerceIn(18_000, 22_000)
        }

    /** Pulse amplitude 0.0..1.0 applied to the PCM sine burst before
     *  it's written to AudioTrack. Default 0.6 — quiet enough that
     *  speaker harmonic distortion at the carrier frequency doesn't
     *  generate audible subharmonics on most phones, loud enough
     *  that the echo still registers cleanly above room ambient.
     *  Tunable per-device via the SonarScreen slider: drop it until
     *  the clicks disappear, raise it until detections still fire. */
    @Volatile var pulseAmplitude: Float = SonarPrefs(context).amplitude
        set(value) {
            field = value.coerceIn(0.1f, 1.0f)
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var prevMagnitude: Double = 0.0
    private var tickCounter: Long = 0
    private var loggedNoSpeaker: Boolean = false

    fun start() {
        if (_running.value) return
        if (!hasMicPermission()) {
            Log.w(TAG, "no RECORD_AUDIO permission — sonar refuses to start")
            return
        }
        _running.value = true
        SonarState.setRunning(true)
        tickCounter = 0
        prevMagnitude = 0.0
        loggedNoSpeaker = false
        Log.i(TAG, "sonar engine started at ${targetFrequencyHz}Hz, " +
            "threshold=${"%.6f".format(detectionThreshold)}")
        loopJob = scope.launch {
            while (isActive) {
                runCatching { tick() }
                    .onFailure { Log.w(TAG, "sonar tick failed: ${it.message}") }
                // Randomised 300-700 ms gap. Prevents the loop from
                // landing on a fixed rhythm any clever ear or detector
                // could lock onto.
                delay(Random.nextLong(300L, 700L))
            }
        }
    }

    fun stop() {
        if (!_running.value) return
        _running.value = false
        SonarState.setRunning(false)
        loopJob?.cancel()
        loopJob = null
    }

    /** One emit-then-record-then-extract cycle. */
    private fun tick() {
        val freq = targetFrequencyHz
        val pulseMs = Random.nextInt(30, 51)  // 30-50 ms pulse
        tickCounter++

        val track = buildTrack(freq, pulseMs) ?: run {
            Log.w(TAG, "tick #$tickCounter: AudioTrack build returned null")
            return
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            // Build can return a non-null AudioTrack whose state is
            // STATE_UNINITIALIZED — happens when the requested format
            // isn't supported on the device. play() on such a track
            // silently no-ops; the mic captures nothing useful and we
            // sit at zero magnitude forever.
            Log.w(TAG, "tick #$tickCounter: AudioTrack state=${track.state}, not INITIALIZED")
            runCatching { track.release() }
            return
        }
        val record = buildRecord() ?: run {
            Log.w(TAG, "tick #$tickCounter: AudioRecord build returned null")
            runCatching { track.release() }
            return
        }
        try {
            // Start mic capture FIRST so the echo isn't clipped at
            // the start by AudioRecord's own buffer-warmup latency.
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "tick #$tickCounter: AudioRecord didn't enter RECORDING")
                return
            }
            // Brief warmup; AudioRecord's first ~10 ms of data is
            // garbage on most devices.
            Thread.sleep(10)
            track.play()
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Log.w(TAG, "tick #$tickCounter: AudioTrack didn't enter PLAYING")
            }
            // Capture window covers AudioTrack output latency (80-200ms
            // on Android — the pulse hasn't even left the speaker until
            // this much time has passed), the pulse itself, and the
            // echo tail. The earlier 90ms window read the mic BEFORE
            // the pulse actually played — magnitude stayed at room
            // ambient and delta never crossed threshold. CAPTURE_MS
            // is now ~300ms so we always have the pulse + echo in
            // the buffer.
            Thread.sleep(CAPTURE_MS.toLong())
            runCatching { track.stop() }

            val sampleRate = SAMPLE_RATE
            val want = sampleRate * CAPTURE_MS / 1000
            val buf = ShortArray(want)
            var off = 0
            val deadline = System.currentTimeMillis() + 200
            while (off < want && System.currentTimeMillis() < deadline) {
                val n = record.read(buf, off, want - off)
                if (n <= 0) break
                off += n
            }
            runCatching { record.stop() }

            if (off < 64) {
                Log.w(TAG, "tick #$tickCounter: only $off samples captured, skipping")
                return
            }

            val (mag, phase) = goertzel(buf, off, freq, sampleRate)
            val delta = if (prevMagnitude == 0.0) 0.0
                        else kotlin.math.abs(mag - prevMagnitude)
            prevMagnitude = mag
            val now = System.currentTimeMillis()
            // Diagnostic logging: first 5 ticks always (so we see
            // SOMETHING in logcat on first run) + every 100th tick
            // thereafter (low-volume heartbeat). Without this, a
            // "0 detections" report is impossible to debug — we
            // can't tell whether the engine is even getting samples
            // or what magnitude the room produces at idle.
            if (tickCounter <= 5L || tickCounter % 100L == 0L) {
                Log.i(
                    TAG,
                    "tick #$tickCounter: freq=${freq}Hz samples=$off " +
                        "mag=${"%.6f".format(mag)} delta=${"%.6f".format(delta)} " +
                        "threshold=${"%.6f".format(detectionThreshold)}",
                )
            }
            _latest.value = Reading(
                timestamp = now,
                frequencyHz = freq,
                magnitude = mag,
                phase = phase,
                deltaFromPrev = delta,
            )
            // Loki phase-2 gate: two consecutive over-threshold deltas
            // = one detection. Day-key compare rolls the counter at
            // local midnight (UTC granularity is good enough).
            val dayKey = currentDayKey()
            if (dayKey != detectionsDayKey) {
                detectionsDayKey = dayKey
                _detectionsToday.value = 0
            }
            // Single-frame trigger with a per-detection cooldown so
            // continuous motion doesn't fan out into a hundred events.
            // The earlier two-in-a-row gate was a noise filter that
            // suppressed real signals — at 300–700 ms tick spacing,
            // a hand passing the phone often shows up in only one
            // sample. 1.5 s cooldown is long enough that the alert
            // notification doesn't spam, short enough that a second
            // distinct event still registers.
            if (delta >= detectionThreshold &&
                now - (_lastDetectionAt.value ?: 0L) > 1_500L
            ) {
                _detectionsToday.value = _detectionsToday.value + 1
                _lastDetectionAt.value = now
                SonarState.recordDetection()
                runCatching { onDetection?.invoke(now, delta) }
            }
        } finally {
            runCatching { track.release() }
            runCatching { record.release() }
        }
    }

    private fun buildTrack(freqHz: Int, pulseMs: Int, amplitude: Float = pulseAmplitude): AudioTrack? = runCatching {
        val samples = generateSineBurst(freqHz, pulseMs, amplitude)
        // CONTENT_TYPE_MUSIC instead of SONIFICATION — SONIFICATION
        // is the "UI tap" path; on some audio HALs that triggers a
        // click-suppression filter or dynamic compressor that mangles
        // high-frequency content and produces audible transients
        // exactly where you don't want them. MUSIC takes the raw
        // passthrough path, which is what an ultrasonic burst needs.
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        val wrote = track.write(samples, 0, samples.size)
        if (wrote != samples.size) {
            Log.w(TAG, "AudioTrack.write wrote $wrote of ${samples.size} samples")
        }
        // Pin the output to the built-in loudspeaker. AudioAttributes
        // alone follows the active media route, so when BT or wired
        // headphones are connected the ultrasonic burst goes through
        // THEM and never reaches the room — mic sees nothing, no
        // detections ever fire. setPreferredDevice silently falls
        // back to default when the speaker isn't enumerable, so it's
        // safe to call unconditionally on API 23+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val speaker = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                runCatching { track.preferredDevice = speaker }
            } else if (!loggedNoSpeaker) {
                loggedNoSpeaker = true
                Log.w(TAG, "no TYPE_BUILTIN_SPEAKER device — audio may route to headphones / BT")
            }
        }
        // Full output — at lower volumes the bounce wasn't strong
        // enough for the mic to register. Combined with the 15 ms
        // Hann envelope, max volume doesn't click on most hardware
        // while giving the strongest possible echo signal. If the
        // user does hear a click they can lower the frequency or
        // we'll revisit a per-device gain.
        runCatching { track.setVolume(1.0f) }
        track
    }.onFailure { Log.w(TAG, "buildTrack threw", it) }.getOrNull()

    private fun buildRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        // Try sources in descending order of fidelity. Many handsets
        // advertise UNPROCESSED but actually return zero-filled buffers,
        // which kept magnitude pegged at 0 and is the most likely
        // reason "0 detections" persisted. VOICE_RECOGNITION usually
        // works because the OS exposes it for keyword wake-words.
        // MIC is the universal last resort.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (src in sources) {
            val rec = runCatching {
                AudioRecord(
                    src,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf,
                )
            }.getOrNull() ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "mic source = $src")
                return rec
            }
            runCatching { rec.release() }
        }
        Log.w(TAG, "no audio source initialized")
        return null
    }

    /** Generate a [pulseMs]-long single-tone sine burst at [freqHz]
     *  with a long cosine-shaped envelope (fade in / fade out over
     *  the first / last 15 ms each, or pulse/2 if the pulse is short).
     *  Longer ramps push the spectral spread of the on/off transient
     *  way down — without them you hear an audible "click" at every
     *  pulse boundary because the abrupt 20 kHz on/off generates
     *  broadband content well into the audible band. Returns
     *  16-bit PCM at [SAMPLE_RATE]. */
    private fun generateSineBurst(freqHz: Int, pulseMs: Int, amplitude: Float = pulseAmplitude): ShortArray {
        val n = (SAMPLE_RATE * pulseMs / 1000)
        val out = ShortArray(n)
        val twoPiFOverFs = 2.0 * Math.PI * freqHz / SAMPLE_RATE
        // 15 ms ramp — Hann-style cosine. Up from the original 5 ms;
        // 5 ms was audibly clicky on speakers that already struggle
        // at the target band.
        val rampSamples = (SAMPLE_RATE * 15 / 1000).coerceAtMost(n / 2)
        val amp = amplitude.toDouble().coerceIn(0.0, 1.0)
        for (i in 0 until n) {
            val s = sin(twoPiFOverFs * i)
            val env = when {
                i < rampSamples              -> 0.5 - 0.5 * cos(Math.PI * i / rampSamples)
                i >= n - rampSamples         -> 0.5 - 0.5 * cos(Math.PI * (n - i) / rampSamples)
                else                          -> 1.0
            }
            out[i] = (s * env * amp * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /**
     * Goertzel single-bin DFT — much cheaper than FFT when we only
     * care about one frequency. Returns (magnitude, phase) of the
     * [target] component in [samples].
     */
    private fun goertzel(
        samples: ShortArray,
        n: Int,
        target: Int,
        sampleRate: Int,
    ): Pair<Double, Double> {
        val w = 2.0 * Math.PI * target / sampleRate
        val cosW = cos(w)
        val coeff = 2.0 * cosW
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            val s0 = samples[i] / 32768.0 + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        // Real + imag components (the standard Goertzel terminator).
        val real = s1 - s2 * cosW
        val imag = s2 * sin(w)
        val magnitude = hypot(real, imag) / (n / 2.0)
        val phase = kotlin.math.atan2(imag, real)
        return magnitude to phase
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun currentDayKey(): Long = System.currentTimeMillis() / 86_400_000L

    /**
     * Single pulse + capture + Goertzel cycle at the requested
     * frequency / amplitude. Returns the magnitude, or null if the
     * audio stack failed to set up. Blocks for ~CAPTURE_MS + setup.
     *
     * Used by [selfTest] and [autoCalibrate]; mirrors the logic in
     * the main [tick] loop but parameterised and synchronous so the
     * calibration sequencer can sweep through candidate values.
     */
    private fun probeOnce(freqHz: Int, amplitude: Float, pulseMs: Int): Double? {
        if (!hasMicPermission()) return null
        val track = buildTrack(freqHz, pulseMs, amplitude) ?: return null
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return null
        }
        val record = buildRecord() ?: run {
            runCatching { track.release() }
            return null
        }
        return try {
            record.startRecording()
            Thread.sleep(10)
            track.play()
            Thread.sleep(CAPTURE_MS.toLong())
            runCatching { track.stop() }
            val want = SAMPLE_RATE * CAPTURE_MS / 1000
            val buf = ShortArray(want)
            var off = 0
            val deadline = System.currentTimeMillis() + 400
            while (off < want && System.currentTimeMillis() < deadline) {
                val n = record.read(buf, off, want - off)
                if (n <= 0) break
                off += n
            }
            runCatching { record.stop() }
            if (off < 64) null
            else goertzel(buf, off, freqHz, SAMPLE_RATE).first
        } finally {
            runCatching { track.release() }
            runCatching { record.release() }
        }
    }

    data class CalibrationResult(
        val frequencyHz: Int,
        val amplitude: Float,
        val threshold: Double,
        val noiseFloor: Double,
        val steps: List<String>,
    )

    /**
     * One-shot auto-calibrate: sweeps the candidate frequency band
     * top-down, picks the highest band the speaker actually drives
     * cleanly, sweeps amplitudes top-down to find the minimum that
     * still produces a usable echo, then measures the idle delta
     * floor and sets threshold = floor × 2.5 (with a hard minimum).
     *
     * Refuses to run while the main tick loop is active — the audio
     * stack can only host one consumer at a time. Caller should
     * stop sonar first.
     *
     * [progress] is invoked on whatever thread autoCalibrate is on
     * (callers should marshal to main if updating Compose state).
     *
     * Returns the chosen tuning, or null on failure. On success the
     * engine's three @Volatile fields and SonarPrefs are both
     * updated, so the new tuning takes effect immediately AND
     * survives a process restart.
     */
    suspend fun autoCalibrate(progress: (String) -> Unit): CalibrationResult? {
        if (!hasMicPermission()) { progress("✗ no RECORD_AUDIO permission"); return null }
        if (_running.value) { progress("✗ stop sonar first — audio is in use"); return null }
        val steps = mutableListOf<String>()
        fun log(s: String) { steps += s; progress(s) }

        log("Self-test: 1 kHz audible beep…")
        val selfMag = probeOnce(1_000, 0.5f, 200)
        if (selfMag == null) { log("✗ audio stack rejected the test"); return null }
        if (selfMag < 0.001) { log("✗ self-test magnitude $selfMag — mic captured silence"); return null }
        log("  ✓ self-test mag=%.4f".format(selfMag))

        // Frequency sweep — high to low. Pick the HIGHEST band that
        // still bounces well, since higher = quieter to the ear.
        log("Frequency sweep…")
        val freqCandidates = intArrayOf(21_000, 20_000, 19_000, 18_500, 18_000)
        var chosenFreq = -1
        for (freq in freqCandidates) {
            val mag = probeOnce(freq, 0.7f, 50) ?: continue
            log("  $freq Hz → mag=%.5f".format(mag))
            // Bar is 10x the noise floor we'd typically see at idle
            // (~5e-6) — well above what HVAC / breathing produces.
            if (mag > 0.0003) { chosenFreq = freq; break }
        }
        if (chosenFreq < 0) {
            log("✗ no frequency produced a usable echo — speaker may not reach ultrasonic")
            return null
        }
        log("  → selected $chosenFreq Hz")

        // Amplitude sweep — high to low. Pick the LOWEST amplitude
        // that still produces a clean echo (≈ 5x the would-be noise
        // floor). Quieter pulse = less audible click on phones whose
        // HF speaker response is dirty.
        log("Amplitude sweep at $chosenFreq Hz…")
        val ampCandidates = floatArrayOf(0.9f, 0.7f, 0.5f, 0.4f, 0.3f, 0.2f)
        var chosenAmp = 0.6f
        var bestAmpMag = 0.0
        for (amp in ampCandidates) {
            val mag = probeOnce(chosenFreq, amp, 50) ?: continue
            log("  amp=${(amp * 100).toInt()}% → mag=%.5f".format(mag))
            if (mag > 0.0002) {
                chosenAmp = amp
                bestAmpMag = mag
            } else if (chosenAmp != 0.6f) {
                // Already found a working louder amplitude; this one
                // is too quiet — keep the previous (lowest-working).
                break
            }
        }
        log("  → selected ${(chosenAmp * 100).toInt()}% amplitude (mag=%.5f)".format(bestAmpMag))

        // Idle-floor measurement. 8 probes ≈ 4 seconds. User has been
        // told to keep still; max delta we see during this window
        // becomes the threshold base.
        log("Measuring idle delta floor (~4 s, keep still)…")
        val deltas = mutableListOf<Double>()
        var prev = 0.0
        repeat(8) { i ->
            val m = probeOnce(chosenFreq, chosenAmp, 40) ?: return@repeat
            if (prev > 0) deltas += kotlin.math.abs(m - prev)
            prev = m
            kotlinx.coroutines.delay(300)
        }
        val maxFloor = deltas.maxOrNull() ?: 0.0
        // Threshold = 2.5× the worst idle delta, with a hard floor
        // at 0.0001 so we don't trip on quantisation noise.
        val threshold = (maxFloor * 2.5).coerceAtLeast(0.0001)
        log("  idle floor max-Δ=%.5f → threshold=%.5f".format(maxFloor, threshold))

        // Commit. Engine fields take effect on the next tick; prefs
        // persist so the next process gets the same tuning instead of
        // resetting to defaults.
        targetFrequencyHz = chosenFreq
        pulseAmplitude = chosenAmp
        detectionThreshold = threshold
        prefs.frequencyHz = chosenFreq
        prefs.amplitude = chosenAmp
        prefs.threshold = threshold
        prefs.calibratedAt = System.currentTimeMillis()
        log("✓ calibrated — try waving a hand near the phone now")

        return CalibrationResult(
            frequencyHz = chosenFreq,
            amplitude = chosenAmp,
            threshold = threshold,
            noiseFloor = maxFloor,
            steps = steps.toList(),
        )
    }

    /**
     * Diagnostic self-test — fires one AUDIBLE 1 kHz beep and reports
     * back the Goertzel magnitude the mic picked up. Verifies the
     * speaker → mic loop works at all without involving any ultrasonic
     * hardware quirks. If this returns a healthy magnitude (>0.01) but
     * the regular ultrasonic loop reports zero, the limitation is the
     * speaker's HF response, not the audio stack.
     *
     * Returns the measured magnitude at 1 kHz, or null if the audio
     * stack failed to set up. Blocks for ~500 ms.
     */
    fun selfTest(): Double? {
        if (!hasMicPermission()) return null
        val testFreq = 1_000
        val pulseMs = 200
        val track = buildTrack(testFreq, pulseMs) ?: return null
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return null
        }
        val record = buildRecord() ?: run {
            runCatching { track.release() }
            return null
        }
        return try {
            record.startRecording()
            Thread.sleep(10)
            track.play()
            Thread.sleep(CAPTURE_MS.toLong())
            runCatching { track.stop() }
            val want = SAMPLE_RATE * CAPTURE_MS / 1000
            val buf = ShortArray(want)
            var off = 0
            val deadline = System.currentTimeMillis() + 400
            while (off < want && System.currentTimeMillis() < deadline) {
                val n = record.read(buf, off, want - off)
                if (n <= 0) break
                off += n
            }
            runCatching { record.stop() }
            if (off < 64) null
            else {
                val (mag, _) = goertzel(buf, off, testFreq, SAMPLE_RATE)
                Log.i(TAG, "selfTest: 1kHz tone → magnitude=$mag (samples=$off)")
                mag
            }
        } finally {
            runCatching { track.release() }
            runCatching { record.release() }
        }
    }

    companion object {
        private const val TAG = "SonarEngine"
        /** 44.1 kHz Nyquist = 22.05 kHz, so 18-22 kHz fits comfortably. */
        private const val SAMPLE_RATE = 44_100
        /** Mic capture window per tick (ms). Must cover AudioTrack
         *  output latency (~80-200ms on Android) plus the pulse plus
         *  a short reverb tail. */
        private const val CAPTURE_MS = 300
    }
}

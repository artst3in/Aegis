package app.aether.aegis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.R
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.GlassPanel
import kotlin.math.PI
import kotlin.math.ln

/**
 * Phase-1 raw-data viewer for the sonar prototype.
 * Live magnitude (dB), phase, frequency, and movement delta from
 * [app.aether.aegis.sonar.SonarEngine]. Start/stop toggle + frequency slider. Once readings
 * are validated on real hardware (Phase 2), this becomes the basis
 * for "leave the phone on a table, alert me if someone approaches."
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonarScreen(navController: NavController) {
    val context = LocalContext.current
    // Engine is process-wide via SonarState — leaving SonarScreen does
    // NOT stop it. Otherwise sonar can never detect anything that
    // happens while the user is doing anything else with the phone.
    val engine = remember { app.aether.aegis.sonar.SonarState.engine(context) }
    val running by engine.running.collectAsState()
    val latest by engine.latest.collectAsState()
    val detectionsToday by app.aether.aegis.sonar.SonarState.detectionsToday.collectAsState()
    // Slider-backing state is seeded ONCE from the engine's current tuning
    // (remember with no key) and thereafter is the source of truth for the
    // sliders; each onValueChange writes straight back to the engine. The
    // engine fields are plain vars, not flows, so we mirror them locally
    // rather than collectAsState. Auto-calibrate also writes these to
    // reflect committed values without a full reload.
    var frequency by remember { mutableIntStateOf(engine.targetFrequencyHz) }
    var threshold by remember { mutableStateOf(engine.detectionThreshold.toFloat()) }
    var amplitude by remember { mutableStateOf(engine.pulseAmplitude) }
    // Self-test / calibrate are long IO operations; the *Busy flags gate
    // their buttons (and each other, and the start button) so two audio
    // operations can't run at once.
    var selfTestResult by remember { mutableStateOf<String?>(null) }
    var selfTestBusy by remember { mutableStateOf(false) }
    var calibrateBusy by remember { mutableStateOf(false) }
    val calibrateLog = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sonar_sonar_raw_data)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        AegisIcon(AegisIcons.Back, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Start/Stop pinned to the very top of the column so the
            // user can always toggle the engine regardless of how many
            // tuning panels live below — earlier the button sat after
            // every other section and got cut off on shorter screens.
            Button(
                onClick = { if (running) engine.stop() else engine.start() },
                modifier = Modifier.fillMaxWidth(),
                colors = if (running)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors(),
            ) {
                Text(if (running) "Stop sonar" else "Start sonar")
            }

            // Auto-calibrate panel — top of the screen because it's
            // the "I just want it to work" affordance. Sweeps
            // frequency + amplitude, measures the idle delta floor,
            // sets threshold = floor × 2.5. Result persists via
            // SonarPrefs so a restart doesn't wipe the tuning.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.sonar_autocalibrate), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.sonar_place_the_phone_where) +
                            "keep still during the run (~10 s). Picks the " +
                            "highest ultrasonic band your speaker drives " +
                            "cleanly, the quietest amplitude that still " +
                            "echoes, and a threshold above the idle noise " +
                            "floor. Survives a restart.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !calibrateBusy && !selfTestBusy && !running,
                        onClick = {
                            calibrateBusy = true
                            calibrateLog.clear()
                            // Calibration drives the audio loop for ~10 s —
                            // run it on Dispatchers.IO, never the main
                            // thread. The callback streams progress lines
                            // back; busy flag is cleared in finally-style at
                            // the end regardless of success.
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val result = runCatching {
                                    engine.autoCalibrate { line ->
                                        // Trim long scrollback so the
                                        // panel doesn't grow forever
                                        // if calibration ever loops.
                                        calibrateLog += line
                                        if (calibrateLog.size > 30) {
                                            calibrateLog.removeAt(0)
                                        }
                                    }
                                }.getOrNull()
                                if (result != null) {
                                    // Reflect committed values into the
                                    // slider state so the UI shows the
                                    // new tuning without a full reload.
                                    frequency = result.frequencyHz
                                    amplitude = result.amplitude
                                    threshold = result.threshold.toFloat()
                                }
                                calibrateBusy = false
                            }
                        },
                    ) {
                        Text(if (calibrateBusy) "Calibrating…" else "Run auto-calibrate")
                    }
                    if (calibrateLog.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column {
                            // Colour the streamed log by leading glyph the
                            // engine emits: ✓ pass → cyan, ✗ fail → error
                            // red, anything else → neutral progress text.
                            calibrateLog.forEach { line ->
                                Text(
                                    line,
                                    color = if (line.startsWith("✓"))
                                        app.aether.aegis.ui.theme.AegisCyan
                                    else if (line.startsWith("✗"))
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                    if (running) {
                        Text(
                            stringResource(R.string.sonar_stop_sonar_first_calibrate),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            // Self-test panel — fires a single AUDIBLE 1 kHz beep and
            // reports the Goertzel magnitude back. Proves the
            // speaker→mic loop works at all; if this comes back
            // healthy (>0.01) but the ultrasonic loop reports zero,
            // the bottleneck is the speaker's HF response, not the
            // audio stack. Most important first-run debug tool.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.sonar_audio_loop_selftest), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.sonar_fires_one_audible_200) +
                            "what the mic captured back. Magnitude > 0.01 = " +
                            "speaker + mic + DSP path all work. Magnitude " +
                            "≈ 0 = something is broken before ultrasonic " +
                            "even comes into play.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !selfTestBusy && !running,
                        onClick = {
                            selfTestBusy = true
                            selfTestResult = null
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val mag = runCatching { engine.selfTest() }.getOrNull()
                                // Magnitude→verdict bands (Goertzel output of
                                // the 1 kHz self-test tone). Tuned empirically:
                                // >0.05 healthy, 0.005–0.05 works-but-quiet,
                                // 0.0001–0.005 barely audible, below that the
                                // mic effectively heard silence. null = the
                                // audio stack refused the request entirely.
                                selfTestResult = when {
                                    mag == null  -> "FAILED: audio stack rejected the test"
                                    mag > 0.05   -> "GOOD: magnitude %.4f — audio loop works".format(mag)
                                    mag > 0.005  -> "OK: magnitude %.4f — loop works but quiet".format(mag)
                                    mag > 0.0001 -> "WEAK: magnitude %.4f — mic barely heard it".format(mag)
                                    else         -> "BAD: magnitude %.6f — mic captured silence".format(mag)
                                }
                                selfTestBusy = false
                            }
                        },
                    ) {
                        Text(if (selfTestBusy) "Beeping…" else "Run self-test")
                    }
                    selfTestResult?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        // Colour keyed off the verdict prefix set above:
                        // GOOD → cyan, OK/WEAK → tertiary, everything else
                        // (BAD/FAILED) → error red.
                        Text(
                            it,
                            color = when {
                                it.startsWith("GOOD") -> app.aether.aegis.ui.theme.AegisCyan
                                it.startsWith("OK")   -> MaterialTheme.colorScheme.tertiary
                                it.startsWith("WEAK") -> MaterialTheme.colorScheme.tertiary
                                else                   -> MaterialTheme.colorScheme.error
                            },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (running) {
                        Text(
                            stringResource(R.string.sonar_stop_sonar_first_selftest),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            // Status panel — running/stopped readout plus the
            // "audible clicks" troubleshooting note. The pulse cadence
            // (30–50 ms pulses, 300–700 ms gaps) is randomised so the
            // emission isn't a fixed tone an eavesdropper could filter
            // out, and so standing waves don't build up in a room.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.sonar_status), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (running) "RUNNING — emitting random 30–50 ms pulses every 300–700 ms"
                        else "stopped",
                        color = if (running) app.aether.aegis.ui.theme.AegisCyan
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.sonar_if_you_hear_clicks) +
                            "handset drivers can't reproduce 20 kHz cleanly and " +
                            "the on/off transient becomes audible. Try lowering " +
                            "the frequency below, or test with a BT speaker / " +
                            "headphones plugged in.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                    )
                }
            }

            // Pulse amplitude — primary audible-click control. Most
            // phone speakers can't reproduce ≥19 kHz cleanly at full
            // amplitude and the harmonic distortion they generate
            // shows up as audible clicks. Drop this until the clicks
            // disappear, raise until detections still fire.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Pulse amplitude: ${(amplitude * 100).toInt()} %",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.sonar_hearing_audible_clicks_drop) +
                            "can't reproduce ultrasonic cleanly at full " +
                            "volume — the harmonics it generates ARE the " +
                            "click. Most devices land between 30 % and 60 %.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    Slider(
                        value = amplitude,
                        onValueChange = {
                            amplitude = it
                            engine.pulseAmplitude = it
                        },
                        valueRange = 0.1f..1.0f,
                    )
                }
            }

            // Target-frequency panel. Range is bounded 18–22 kHz: below
            // ~18 kHz most adults start to hear it; above ~22 kHz the
            // 44.1 kHz capture path runs into the Nyquist limit (22.05 kHz)
            // and the tone clips. Writes straight through to the engine.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Target frequency: ${frequency} Hz",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.sonar_adjust_per_device_speakers) +
                            "above ~18 kHz is inaudible to most adults; " +
                            "above ~22 kHz the 44.1 kHz sample rate clips.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    Slider(
                        value = frequency.toFloat(),
                        onValueChange = {
                            frequency = it.toInt()
                            engine.targetFrequencyHz = frequency
                        },
                        valueRange = 18_000f..22_000f,
                        // 7 interior steps → 9 stops (the two endpoints plus
                        // 7) at 500 Hz spacing: 18, 18.5, …, 22 kHz.
                        steps = 7,  // 18, 18.5, …, 22 kHz
                    )
                }
            }

            // Detection sensitivity panel — dial the threshold live so
            // the user can tune for their hardware without rebuilding.
            // Counter underneath confirms the engine is actually firing
            // detections (vs "0 detections" meaning silent processing).
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Detection threshold: %.4f".format(threshold),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.sonar_lower_more_sensitive_compare) +
                            "value below — if Δ peaks at 0.001 when you move " +
                            "your hand past the phone, set threshold to ~0.0005.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    // Range 0.0001–0.01: the floor is near the idle noise
                    // delta on a quiet device, the ceiling well above any
                    // real movement, so the whole usable sensitivity band
                    // fits one slider. Lower = more sensitive.
                    Slider(
                        value = threshold,
                        onValueChange = {
                            threshold = it
                            engine.detectionThreshold = it.toDouble()
                        },
                        valueRange = 0.0001f..0.01f,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Detections today: $detectionsToday",
                        color = app.aether.aegis.ui.theme.AegisCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Live reading panel — the most recent SonarReading from the
            // engine flow (freq / magnitude / dB / phase / movement delta /
            // age). Null until the first pulse round-trips after Start.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.sonar_latest_reading), fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    val r = latest
                    if (r == null) {
                        Text(
                            stringResource(R.string.sonar_no_data_yet_tap),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    } else {
                        // Linear magnitude → dB (20·log10). coerceAtLeast
                        // floors the input at 1e-9 so a zero/near-zero
                        // reading can't produce -Infinity. ln/ln(10) because
                        // kotlin.math has no log10 on the common path here.
                        val db = 20.0 * ln(r.magnitude.coerceAtLeast(1e-9)) / ln(10.0)
                        ReadingRow("freq",      "${r.frequencyHz} Hz")
                        ReadingRow("magnitude", "%.4f".format(r.magnitude))
                        ReadingRow("dB",        "%.1f dB".format(db))
                        ReadingRow("phase",     "%.2f rad (%.0f°)"
                            .format(r.phase, r.phase * 180.0 / PI))
                        // Delta highlighted cyan past 0.02 — a visual cue
                        // that movement is large enough to likely cross a
                        // typical detection threshold. Display-only; the
                        // engine's own threshold is what actually fires.
                        ReadingRow(
                            "delta",
                            "%.4f".format(r.deltaFromPrev),
                            tint = if (r.deltaFromPrev > 0.02) app.aether.aegis.ui.theme.AegisCyan
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ReadingRow("age", "${(System.currentTimeMillis() - r.timestamp)} ms")
                    }
                }
            }

            SentinelControlPanel()
            SentinelViewerLinks(navController)

        }
    }
}

/** A label/value line in the live-reading panel: dim label on the left
 *  (30 % width), monospace value on the right (70 %). [tint] overrides
 *  the value colour when set (used to flag a large movement delta);
 *  Color.Unspecified falls back to the default on-surface colour. */
@Composable
private fun ReadingRow(label: String, value: String, tint: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.weight(0.3f),
        )
        Text(
            value,
            color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.weight(0.7f),
        )
    }
}

/** Sentinel arm panel — lives at the bottom of the SonarScreen
 *  while the feature is still in Experimental. Arm/disarm switch,
 *  current stage + watermark readout, notify-list checkbox picker
 *  (subset of chat contacts), throttle picker, auto-arm toggle.
 *
 *  When Sentinel gets promoted to a first-class Security feature,
 *  this panel lifts into its own [settings/sentinel] screen with
 *  the same controls. For now SonarScreen is the testing surface —
 *  sonar calibration + sentinel arming live together because they
 *  share the underlying sonar engine. */
@Composable
private fun SentinelControlPanel() {
    val ctx = LocalContext.current
    val engine = remember(ctx) { app.aether.aegis.sentinel.SentinelState.engine(ctx) }
    val prefs = remember(ctx) { app.aether.aegis.sentinel.SentinelPrefs(ctx) }
    val stage by engine.stage.collectAsState()
    val watermark by engine.watermark.collectAsState()
    // "Armed" is derived purely from the stage being anything other than
    // OFF — there's no separate armed boolean to drift out of sync. The
    // switch reflects (and toggles) this.
    val armed = stage != app.aether.aegis.sentinel.SentinelStage.OFF

    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sonar_sentinel_mode), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.sonar_layered_cascade_sonar_proximity) +
                            "Notifies your selected contacts when something " +
                            "approaches the phone while you're away.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = armed,
                    onCheckedChange = { on ->
                        if (on) engine.arm() else engine.disarm()
                    },
                )
            }
            if (armed) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Current stage: ${stage.label}",
                    color = app.aether.aegis.ui.theme.AegisCyan,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                // Watermark = highest stage already notified-on this
                // arming session. Shown so the user understands why a
                // re-trip at the same level is silent under the
                // once-per-stage throttle; it resets on unlock.
                if (watermark != app.aether.aegis.sentinel.SentinelStage.OFF) {
                    Text(
                        "Notification watermark: ${watermark.label} " +
                            "(resets on phone unlock)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    SentinelNotifyListPicker(prefs)
    SentinelThrottlePicker(prefs)
    SentinelAutoArmRow(prefs)
}

/** Checkbox list of chat contacts — tick the ones who should
 *  receive silent SimpleX pings when the cascade escalates. */
@Composable
private fun SentinelNotifyListPicker(prefs: app.aether.aegis.sentinel.SentinelPrefs) {
    val peers by app.aether.aegis.AegisApp.instance.repository
        .observeKnownPeers().collectAsState(initial = emptyList())
    // Local mirror of prefs.notifyList so the checkboxes update instantly;
    // every toggle writes back to prefs in the same tap, so the local copy
    // and persisted set never diverge.
    var selected by remember { mutableStateOf(prefs.notifyList) }
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(stringResource(R.string.sonar_notify_list), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.sonar_contacts_who_receive_silent) +
                    "the cascade escalates. Empty = log-only mode.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            if (peers.isEmpty()) {
                Text(
                    stringResource(R.string.sonar_no_contacts_yet_add),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@Column
            }
            // Explicit "log-only" acknowledgement — lets the user run
            // Sentinel without ticking any contacts while still
            // satisfying the "configured" precondition for the skill-tree
            // node. Sentinel still records every event to the local log;
            // only the outbound SimpleX dispatch is skipped. Persisted so
            // the choice survives a relaunch.
            var ackLogOnly by remember { mutableStateOf(prefs.acknowledgedLogOnly) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ackLogOnly = !ackLogOnly
                        prefs.acknowledgedLogOnly = ackLogOnly
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = ackLogOnly, onCheckedChange = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.sonar_logonly_record_events_locally),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            peers.forEach { peer ->
                val isOn = peer.publicKey in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (isOn) selected - peer.publicKey
                                       else selected + peer.publicKey
                            prefs.notifyList = selected
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = isOn,
                        onCheckedChange = null,  // row click handles it
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(peer.displayName, fontSize = 13.sp)
                }
            }
        }
    }
}

/** Throttle picker — radio-style choice among the four modes.
 *  Defaults to UNTIL_UNLOCK per spec; per-mode behaviour described
 *  inline so the user picks with full context. */
@Composable
private fun SentinelThrottlePicker(prefs: app.aether.aegis.sentinel.SentinelPrefs) {
    var current by remember { mutableStateOf(prefs.throttle) }
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(stringResource(R.string.sonar_notification_throttle), fontWeight = FontWeight.SemiBold)
            app.aether.aegis.sentinel.SentinelThrottle.values().forEach { t ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            current = t
                            prefs.throttle = t
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current == t, onClick = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(t.label, fontSize = 13.sp)
                        Text(
                            throttleHelp(t),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

/** One-line plain-language description of each throttle mode, shown
 *  under its radio button so the user picks with full context. */
private fun throttleHelp(t: app.aether.aegis.sentinel.SentinelThrottle): String = when (t) {
    app.aether.aegis.sentinel.SentinelThrottle.NEVER ->
        "Log-only mode — never pings contacts."
    app.aether.aegis.sentinel.SentinelThrottle.UNTIL_UNLOCK ->
        "One ping per unique stage reached. Resets when you unlock the phone. (Default.)"
    app.aether.aegis.sentinel.SentinelThrottle.TIMED ->
        "Same as Once per unlock, but also resets after N minutes of quiet."
    app.aether.aegis.sentinel.SentinelThrottle.EVERY ->
        "Pings on every trip. Spam mode — for completionists only."
}

/** Auto-arm toggle. When on, the engine auto-arms when the phone is
 *  on the charger + locked + stationary for N minutes. */
@Composable
private fun SentinelAutoArmRow(prefs: app.aether.aegis.sentinel.SentinelPrefs) {
    var autoArm by remember { mutableStateOf(prefs.autoArmEnabled) }
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sonar_autoarm_at_bedside), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.sonar_automatically_arms_sentinel_when) +
                            "charging, locked, and stationary for " +
                            "${prefs.autoArmStationaryMinutes} minutes. " +
                            "Killer use case: plug in at bedside, watch " +
                            "overnight without manual toggle.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = autoArm,
                    onCheckedChange = {
                        autoArm = it
                        prefs.autoArmEnabled = it
                        if (it) {
                            app.aether.aegis.sentinel.SentinelState
                                .engine(app.aether.aegis.AegisApp.instance)
                                .startAutoArmObserver()
                        }
                    },
                )
            }
        }
    }
}

/** Two link rows at the bottom of the Sentinel section: one into the
 *  inbox (events received from peers), one into the local event log
 *  (sender-side debug trace). Plus a "Send test notification" affordance
 *  so the user can verify the SimpleX dispatch path works without
 *  having to actually arm the cascade and trigger sensors. */
@Composable
private fun SentinelViewerLinks(navController: NavController) {
    val ctx = LocalContext.current
    val inboxUnread by remember(ctx) {
        app.aether.aegis.sentinel.SentinelInbox(ctx).unreadCount
    }.collectAsState()
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(stringResource(R.string.sonar_sentinel_viewers), fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navController.navigate("settings/sentinel/inbox") },
            ) {
                Text(
                    if (inboxUnread > 0)
                        "Inbox — events from contacts ($inboxUnread unread)"
                    else "Inbox — events from contacts",
                    fontSize = 12.sp,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { navController.navigate("settings/sentinel/log") },
            ) {
                Text(stringResource(R.string.sonar_local_event_log_debug), fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    // Synthesises one SENTINEL_EVENT for each
                    // notify-list recipient — quickest possible
                    // confirmation that the SimpleX dispatch reaches
                    // them. No cascade required, no sensors used.
                    app.aether.aegis.sentinel.SentinelNotifier.fire(
                        ctx,
                        app.aether.aegis.sentinel.SentinelStage.RECORDING,
                        recording = null,
                        mugshot = null,
                    )
                },
            ) {
                Text(stringResource(R.string.sonar_send_test_notification_to), fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            SentinelDrillRow()
        }
    }
}

/** Guided drill button + live status. Pressing "Run drill" arms the
 *  cascade in drill mode — the user walks through it themselves
 *  (approach phone → touch screen → pick up), each stage fires a
 *  drill-tagged notification to the notify-list, recipients tap
 *  "Confirm receipt" in their inbox, and the drill passes when
 *  every recipient confirms within 5 minutes. */
@Composable
private fun SentinelDrillRow() {
    val ctx = LocalContext.current
    val engine = remember(ctx) { app.aether.aegis.sentinel.SentinelState.engine(ctx) }
    val prefs = remember(ctx) { app.aether.aegis.sentinel.SentinelPrefs(ctx) }
    val stage by engine.stage.collectAsState()
    // Tick every second so the drill-status display + cooldown timer
    // refresh smoothly during the drill window.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(1_000); tick++ }
    }
    // These prefs aren't flows, so re-read them keyed on `tick` to pull the
    // latest persisted drill state once per second — the engine mutates
    // them out-of-band as recipients confirm and the cooldown elapses.
    val cooldownMs = remember(tick) { prefs.drillCooldownRemainingMs() }
    val drillActive = remember(tick) { prefs.drillStartedAt > 0L }
    val pending = remember(tick) { prefs.drillPendingRecipients }
    val confirmed = remember(tick) { prefs.drillConfirmedRecipients }

    Column {
        val cooldownLeftS = (cooldownMs / 1000).toInt()
        // A drill can start only when none is running, the cooldown has
        // fully elapsed, and there's at least one notify-list recipient to
        // confirm receipt — a drill with nobody to ack proves nothing.
        val canRun = !drillActive && cooldownMs == 0L && prefs.notifyList.isNotEmpty()
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = canRun,
            onClick = { engine.startDrill() },
        ) {
            Text(
                when {
                    drillActive -> "Drill in progress — walk through cascade"
                    cooldownMs > 0L -> "Drill cooldown: ${cooldownLeftS / 3600}h ${(cooldownLeftS % 3600) / 60}m"
                    prefs.notifyList.isEmpty() -> "Pick at least one notify-list contact first"
                    else -> "Run Sentinel drill"
                },
                fontSize = 12.sp,
            )
        }
        if (drillActive) {
            Spacer(modifier = Modifier.height(4.dp))
            // Drill window is fixed at 5 minutes: every recipient must
            // confirm within it for the drill to pass. coerceAtLeast(0)
            // keeps the countdown from going negative if the engine hasn't
            // yet flipped drillActive off after expiry.
            val elapsedS = ((System.currentTimeMillis() - prefs.drillStartedAt) / 1000).toInt()
            val remainingS = (5 * 60 - elapsedS).coerceAtLeast(0)
            Text(
                "Stage: ${stage.label} · ${confirmed.size}/${pending.size} confirmed · ${remainingS}s remaining",
                color = app.aether.aegis.ui.theme.AegisCyan,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { engine.cancelDrill() },
            ) {
                Text(stringResource(R.string.sonar_cancel_drill), fontSize = 11.sp)
            }
        }
        if (prefs.lastDrillAt > 0L && !drillActive) {
            Spacer(modifier = Modifier.height(4.dp))
            val sinceLast = (System.currentTimeMillis() - prefs.lastDrillAt) / 1000
            val ageLabel = when {
                sinceLast < 3600 -> "${sinceLast / 60}m ago"
                sinceLast < 86400 -> "${sinceLast / 3600}h ago"
                else -> "${sinceLast / 86400}d ago"
            }
            Text(
                "Last successful drill: $ageLabel",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
        }
    }
}

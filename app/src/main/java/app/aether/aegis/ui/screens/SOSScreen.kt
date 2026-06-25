package app.aether.aegis.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.aegis.AegisApp

// ============================================================
// SOS
// ============================================================

@Composable
fun SOSScreen() {
    val active by AegisApp.instance.sosHandler.state.collectAsState()
    val sos = AegisApp.instance.sosHandler
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Duress: silent SOS already fired on unlock. The SOS button
    // on-screen must look indistinguishable from the real one to the
    // attacker, but the trigger is a no-op (would double-broadcast and
    // waste battery + bandwidth). The visual + vibration heartbeat
    // still plays so it FEELS like SOS worked.
    val inDuress = AegisApp.instance.lockState.inDuressMode
    // Fake-active local state so the button feels responsive in duress.
    var duressActive by remember { mutableStateOf(false) }

    // Activation: HoldToExecuteHex with the Edge
    // Heat animation drives the hold confirmation. 6 edges × ~167 ms
    // = 1.0-second hold; per-edge TextHandleMove haptic (15 ms each).
    // Release before edge 6 = abort, no broadcast.
    //
    // Vibration on FIRE: three rapid 30 ms pulses
    // via the Vibrator service, not the long HapticFeedback LongPress.
    // Vibrations should be felt, not heard.
    val vibrator = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    }
    fun fireSOS() {
        // Three short blips: vibrate-pause-vibrate-pause-vibrate. The
        // waveform is [delay, vibrate, pause, vibrate, pause, vibrate].
        runCatching {
            vibrator?.vibrate(
                android.os.VibrationEffect.createWaveform(
                    longArrayOf(0, 30, 50, 30, 50, 30),
                    -1,
                )
            )
        }
        if (inDuress) {
            duressActive = true
        } else {
            // Pass the hosting Activity so LockdownController can dim
            // its window. Cast is safe because the
            // SOS tab is rendered inside MainActivity's window.
            sos.trigger(
                app.aether.aegis.core.SOSTrigger.BUTTON,
                sosActivity = context as? android.app.Activity,
            )
        }
    }

    // Two-finger vertical swipe to re-brighten / re-dim
    // the screen during SOS. Hidden gesture — a thief who picks up
    // the phone won't discover it. Active ONLY while SOS is firing
    // (real or duress simulated), so the gesture is dead weight on
    // the idle SOS tab. Held on the outer Box so it covers the
    // whole SOS surface.
    val brightnessGestureActive = active != null || duressActive
    val activityRef = context as? android.app.Activity
    val lockdown = remember { app.aether.aegis.core.LockdownController(context) }

    // "Help is coming" reassurance wave: on the moment sos
    // activates, a dim red energy ring expands and the words fade in then
    // out as the screen dims. Purely reassurance — fires once per
    // activation transition.
    var helpVisible by remember { mutableStateOf(false) }
    val helpWave = remember { androidx.compose.animation.core.Animatable(0f) }
    val justActivated = active != null || duressActive
    LaunchedEffect(justActivated) {
        if (justActivated) {
            helpVisible = true
            helpWave.snapTo(0f)
            helpWave.animateTo(1f, androidx.compose.animation.core.tween(2200))
            kotlinx.coroutines.delay(600)
            helpVisible = false
        }
    }
    val activeNow = active != null || duressActive
    // Dashboard metaphor: the SOS console is laid out the SAME idle and
    // active — every control (status line, STOP, SIREN) is always
    // present, so activating SOS NEVER reflows the screen. Nothing
    // appears out of nowhere, nothing slides; the secondary controls
    // simply light up. Idle they sit as faint outlines (like an unlit
    // car dashboard); SOS energises them. This alpha is the unlit
    // glow level.
    val ghost = 0.20f
    // Live siren flag hoisted out of the (previously active-only) panel
    // so the always-present SIREN tile can read it in both states.
    val sirenOn by app.aether.aegis.sos.SirenManager.on.collectAsState()
    var confirmStart by remember { mutableStateOf(false) }
    // Empty-recipients check — surfaced as a top overlay (not a console
    // child) so it can't change the console height and shove the hex.
    val sosRecipientCount by produceState(0) {
        value = runCatching {
            AegisApp.instance.repository.sosTargets().size
        }.getOrDefault(0)
    }
    // In duress, `active` from SOSHandler is null (we skipped the real
    // trigger), so synthesize a fake location from a decoy entry to
    // render the same UI. Computed once here for the always-present
    // status panel.
    val loc = active?.lastLocation ?: if (duressActive) {
        val decoy = app.aether.aegis.decoy.DecoyFixtures.statuses().firstOrNull()
        val decoyLat = decoy?.latitude
        val decoyLng = decoy?.longitude
        if (decoyLat != null && decoyLng != null) {
            android.location.Location("decoy").apply {
                latitude = decoyLat
                longitude = decoyLng
            }
        } else null
    } else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .let { base ->
                if (brightnessGestureActive && activityRef != null) {
                    base.pointerInput(brightnessGestureActive) {
                        // Two-finger vertical swipe
                        // adjusts brightness while SOS is active.
                        // Hidden gesture (no UI affordance) so a
                        // thief poking at the dim SOS screen never
                        // accidentally re-brightens it.
                        var current = activityRef.window.attributes.screenBrightness.let {
                            if (it < 0f) 0.5f else it
                        }
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var lastAvgY: Float? = null
                            while (true) {
                                val event = awaitPointerEvent()
                                val activePtrs = event.changes.filter { it.pressed }
                                if (activePtrs.isEmpty()) break
                                if (activePtrs.size >= 2) {
                                    val avgY = activePtrs
                                        .map { it.position.y }
                                        .average().toFloat()
                                    val prev = lastAvgY
                                    if (prev != null) {
                                        val delta = (prev - avgY) * 0.0015f
                                        current = (current + delta).coerceIn(0.01f, 1f)
                                        lockdown.setBrightness(activityRef, current)
                                    }
                                    lastAvgY = avgY
                                    activePtrs.forEach { it.consume() }
                                } else {
                                    lastAvgY = null
                                }
                            }
                        }
                    }
                } else base
            },
        contentAlignment = Alignment.Center,
    ) {
        // Top overlay band — mutually-exclusive, height-independent of
        // the console so neither one can push the centred hex:
        //   • idle + no recipients → the warning,
        //   • active → the "Help is coming" reassurance wave.
        // Both live at TopCenter so they occupy the same dead space
        // above the console instead of colliding with the status line.
        if (helpVisible) {
            val p = helpWave.value
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = app.aether.aegis.ui.theme.AegisSOS.copy(alpha = (1f - p) * 0.30f),
                    radius = size.minDimension * 0.62f * p,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                )
            }
            Text(
                stringResource(R.string.s_o_s_help_is_coming),
                color = app.aether.aegis.ui.theme.AegisSOS.copy(alpha = 1f - p * 0.4f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
            )
        } else if (sosRecipientCount == 0 && !activeNow) {
            // Empty-recipients warning. With the Trust Model defaults a
            // fresh install has zero sos recipients (every new contact
            // starts UNTRUSTED). A sos would still record audio + fire
            // local lockdown, but the BROADCAST silently reaches nobody
            // — easy to mistake for a working alert. Surface the gap.
            Text(
                stringResource(R.string.s_o_s_no_sos_recipients_promote) +
                    "Trusted or Emergency so this button reaches someone.",
                color = app.aether.aegis.ui.theme.AegisWarning,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp, start = 24.dp, end = 24.dp),
            )
        }

        // The console — IDENTICAL child set idle and active, so the hex
        // never shifts. Width is pinned so the panels don't jump as
        // their content changes length.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                if (active != null) "SOS ACTIVE · SILENT" else stringResource(R.string.sos_emergency),
                color = if (active != null) app.aether.aegis.ui.theme.AegisSOS else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.SemiBold,
            )

            // Central SOS hex. 120 dp. HoldToExecuteHex
            // drives the Edge Heat hold animation; 3 s hold (500 ms
            // per edge) before firing. Cyan border when idle, red
            // when active.
            // Always reads as a danger button: red border, black fill, red
            // text — never cyan/transparent. Active state swaps the
            // black fill for the red SOS glow.
            app.aether.aegis.ui.components.HoldToExecuteHex(
                size = 120.dp,
                borderColor = app.aether.aegis.ui.theme.AegisSOS,
                heatColor = app.aether.aegis.ui.theme.AegisSOS,
                fillColor = if (activeNow) app.aether.aegis.ui.theme.AegisSOSGlow
                            else Color.Black,
                glow = activeNow,
                glowColor = app.aether.aegis.ui.theme.AegisSOSGlow,
                enabled = !activeNow,
                // 1.0 s total hold (down from an original 3 s, then 1.5 s).
                // The number is an expected-cost optimum, not a feel. Model
                // the harm as two competing terms: a false-alarm cost that
                // falls off EXPONENTIALLY as the hold gets longer (an
                // accidental sustained press on this exact hex is already
                // rare past ~0.5 s and vanishing by ~1 s), and a delay cost
                // that rises only LINEARLY (every held second is a second a
                // real alert is late, and a second a watching attacker has
                // to knock your hand off the screen). The minimum sits where
                // those cross, and because the location depends only on the
                // LOG of the cost ratio it lands at ~1 s across every
                // defensible weighting — not 1.5, not 3. The hardware
                // trigger (power ×5) covers the hands-pinned / attacker-
                // already-on-you case, so this touchscreen hold does NOT have
                // to win a physical scramble; that's why it can sit at the
                // calm ~1 s optimum instead of being shoved down to a
                // hair-trigger. Hardcoded on purpose — never user-settable.
                // Fast to fire; the CANCEL hold below deliberately stays 3 s,
                // because there the asymmetry inverts (silencing a LIVE alarm
                // is the expensive mistake, so that direction gets a
                // conservative threshold). 6 edges × ~167 ms, blips every
                // 2 edges → a haptic heartbeat:
                //
                //   t=0      blip   (hapticOnPress)
                //   t=0.167  edge 1 silent
                //   t=0.333  edge 2 blip
                //   t=0.5    edge 3 silent
                //   t=0.667  edge 4 blip
                //   t=0.833  edge 5 silent
                //   t=1.0    edge 6 → fireSOS (3-blip pattern)
                holdDurationMs = 1000L,
                forceHold = true,
                hapticOnPress = true,
                hapticEdgeStride = 2,
                // 1 s is too short to read six sequential edges — all six
                // warm together to bright max, then the hex emits an energy
                // wave and dims. Consistent with the shorter arm time.
                simultaneousWarmup = true,
                onExecute = { fireSOS() },
            ) {
                Text(
                    if (activeNow) stringResource(R.string.sos_active) else stringResource(R.string.sos_title),
                    color = app.aether.aegis.ui.theme.AegisSOS,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
            }

            Text(
                when {
                    activeNow -> "Silent alert sent. GPS broadcasting. Trusted and emergency contacts notified."
                    else      -> "Hold to activate silent alarm. Trusted and emergency contacts will be notified."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 240.dp),
            )

            // ---- STOP panel — ALWAYS present (faint when idle). ----
            // GPS/audio telemetry on the left, the hold-to-cancel hex on
            // the right. Idle the whole panel is a dim outline showing
            // "standby" gauges; active it lights red and the hex arms.
            app.aether.aegis.ui.components.GlassPanel(
                glow = activeNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (activeNow) 1f else ghost),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.sos_broadcasting),
                                color = app.aether.aegis.ui.theme.AegisSOS,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                when {
                                    !activeNow -> "GPS: standby"
                                    loc != null -> "GPS: %.5f, %.5f".format(loc.latitude, loc.longitude)
                                    else -> "GPS: waiting for fix"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                            Text(
                                "Audio: " + when {
                                    !activeNow -> "standby"
                                    sos.audioPath() != null -> "recording"
                                    else -> "no mic"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                        // STOP hex — small red ring matching the
                        // SOS animation. In duress this just
                        // flips the fake-active flag; the real sos
                        // (fired silently at unlock) keeps running.
                        //
                        // Hold-to-fire matching the sos-trigger
                        // ritual: a single tap can't cancel an
                        // active alarm. The whole point of SOS is
                        // that it survives a hostile environment —
                        // a pocket bump, an attacker grabbing the
                        // phone and mashing the screen, a sleeve
                        // brushing the surface during the broadcast
                        // — none of those should silence the alert.
                        // Matches the sos-trigger 3 s hold so the
                        // visual + haptic language is symmetric:
                        // both ends of the SOS lifecycle gate
                        // behind the same deliberate gesture. Same
                        // edge-heat animation, same 1-second
                        // heartbeat stride (hapticEdgeStride = 2).
                        // `enabled = activeNow` keeps the idle ghost
                        // un-armable — you can't cancel an alarm that
                        // isn't running.
                        app.aether.aegis.ui.components.HoldToExecuteHex(
                            size = 36.dp,
                            borderColor = app.aether.aegis.ui.theme.AegisSOS,
                            heatColor = app.aether.aegis.ui.theme.AegisSOS,
                            fillColor = if (activeNow) app.aether.aegis.ui.theme.AegisSOSGlow
                                        else Color.Transparent,
                            glow = activeNow,
                            glowColor = app.aether.aegis.ui.theme.AegisSOSGlow,
                            enabled = activeNow,
                            holdDurationMs = 3000L,
                            forceHold = true,
                            hapticOnPress = true,
                            hapticEdgeStride = 2,
                            onExecute = {
                                if (inDuress) duressActive = false
                                else sos.cancel()
                            },
                        ) {
                            Text(
                                stringResource(R.string.sos_stop),
                                color = app.aether.aegis.ui.theme.AegisSOS,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                }
            }

            // ---- SIREN panel — ALWAYS present (faint when idle). ----
            // GUI_SPEC: confirm on start (so a stray tap doesn't fire a
            // max-volume alarm), biometric-gate on stop ("cannot be
            // stopped by thief"). The SirenManager owns the audio
            // pipeline so the UI just observes its on flag. Idle the
            // tile is a dim outline and taps are inert — the siren is a
            // SOS tool, not something to fire from the resting screen.
            app.aether.aegis.ui.components.GlassPanel(
                glow = sirenOn,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (activeNow) 1f else ghost),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (sirenOn) "SIREN ACTIVE" else "SIREN (optional)",
                        color = if (sirenOn) app.aether.aegis.ui.theme.AegisSOS
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = if (sirenOn) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    app.aether.aegis.ui.components.HexShape(
                        size = 48.dp,
                        borderColor = app.aether.aegis.ui.theme.AegisSOS,
                        fillColor = if (sirenOn) app.aether.aegis.ui.theme.AegisSOS.copy(alpha = 0.25f)
                                    else Color.Transparent,
                        glow = sirenOn,
                        onClick = {
                            // Inert until SOS is active — the siren
                            // belongs to a live SOS, not the idle dash.
                            if (!activeNow) return@HexShape
                            if (sirenOn) {
                                promptBiometricStopSiren(context)
                            } else {
                                confirmStart = true
                            }
                        },
                    ) {
                        app.aether.aegis.ui.components.AegisIcon(
                            icon = if (sirenOn) app.aether.aegis.ui.components.AegisIcons.SirenStop
                                   else app.aether.aegis.ui.components.AegisIcons.Siren,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = app.aether.aegis.ui.theme.AegisSOS,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        if (sirenOn) "Tap to stop — biometric required."
                        else "Requires confirmation. Cannot be stopped by thief.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (confirmStart) {
            AlertDialog(
                onDismissRequest = { confirmStart = false },
                title = { Text(stringResource(R.string.s_o_s_start_siren)) },
                text = {
                    Text(
                        stringResource(R.string.s_o_s_plays_at_maximum_alarm) +
                            "unlock to stop it. Bystanders nearby will " +
                            "hear an unmistakable alarm.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmStart = false
                        app.aether.aegis.sos.SirenManager.start(context)
                    }) { Text("START SIREN", color = app.aether.aegis.ui.theme.AegisSOS) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmStart = false }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
    }
}

/** Stop the siren behind a biometric gate so a thief who grabs the
 *  phone can't silence it without unlocking. Falls back to immediate
 *  stop on devices with no enrolled biometric — better to let the owner
 *  silence it than leave them stuck. */
private fun promptBiometricStopSiren(context: android.content.Context) {
    val activity = context as? androidx.fragment.app.FragmentActivity
    if (activity == null) {
        app.aether.aegis.sos.SirenManager.stop(context)
        return
    }
    val canAuth = androidx.biometric.BiometricManager.from(activity).canAuthenticate(
        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
    )
    if (canAuth != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
        app.aether.aegis.sos.SirenManager.stop(context)
        return
    }
    val prompt = androidx.biometric.BiometricPrompt(
        activity,
        androidx.core.content.ContextCompat.getMainExecutor(activity),
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: androidx.biometric.BiometricPrompt.AuthenticationResult,
            ) {
                app.aether.aegis.sos.SirenManager.stop(context)
            }
        },
    )
    runCatching {
        prompt.authenticate(
            androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Stop siren")
                .setSubtitle("Authenticate to silence the alarm")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build(),
        )
    }
}

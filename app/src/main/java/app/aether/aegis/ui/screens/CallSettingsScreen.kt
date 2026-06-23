package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisTopBar

import app.aether.aegis.ui.components.AegisButton

import app.aether.aegis.call.CallPrefs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import app.aether.aegis.ui.components.AegisIcon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

/**
 * Settings page for the WebRTC call surface. Currently just the
 * relay-only toggle — the most common failure mode in the field is
 * "TURN can't be reached, no candidate pairs". Flipping relay-only
 * off opens up direct STUN + local-network paths at the cost of
 * exposing peer IPs to each other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallSettingsScreen(navController: NavController) {
    val ctx = LocalContext.current
    val prefs = remember { CallPrefs(ctx) }
    // relayOnly mirrors the persisted pref; the toggle writes BOTH the
    // local state (for immediate recomposition) and prefs (to persist),
    // so this is a plain mutableStateOf rather than a derived value.
    var relayOnly by remember { mutableStateOf(prefs.relayOnly) }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = { Text(stringResource(R.string.settings_calls)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(app.aether.aegis.ui.components.AegisIcons.Back, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.call_relayonly_calls), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (relayOnly) "Privacy-first: all audio routes through simplex.im's TURN " +
                            "relay so the peer never sees your public IP. Higher latency, " +
                            "depends on the relay being reachable."
                        else "Direct: WebRTC tries STUN + local-network candidates first, falls " +
                            "back to relay. Faster and more reliable, but the peer learns " +
                            "your public IP if a direct path opens.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                app.aether.aegis.ui.components.HexSwitch(
                    checked = relayOnly,
                    onCheckedChange = {
                        // Persist immediately — there's no "save" button on
                        // this screen; the pref is read fresh when the next
                        // call sets up its ICE policy.
                        relayOnly = it
                        prefs.relayOnly = it
                    },
                )
            }

            HorizontalDivider()

            Text(
                stringResource(R.string.call_troubleshooting),
                color = app.aether.aegis.ui.theme.AegisCyan,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "If a call sits on \"Preparing…\" or \"Calling…\" past 15 s, the call " +
                    "auto-cancels with an error explaining where it got stuck. The CallScreen " +
                    "shows the raw WebRTC state in the top-right corner so you can spot " +
                    "which leg failed — WaitCapabilities = WebRTC didn't initialise, " +
                    "InvitationSent = peer never received the invite, OfferSent / " +
                    "AnswerReceived = ICE negotiation failed.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // Mic test — opens an AudioRecord directly with Chromium's
            // VOICE_COMMUNICATION source and reports success/failure.
            // Isolates whether the "couldn't start audio source" call
            // failure is an Aegis-side audio issue or specific to
            // Chromium WebView's getUserMedia. If THIS succeeds and
            // calls fail, the WebView is the problem.
            // Holds the formatted result line from the last mic test run.
            // Null until the button is pressed; coloured cyan on OK, error
            // colour otherwise (the "OK" prefix is the success signal).
            var micTestResult by remember { mutableStateOf<String?>(null) }
            Text(
                stringResource(R.string.call_microphone_test),
                color = app.aether.aegis.ui.theme.AegisCyan,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            AegisButton(onClick = { micTestResult = runMicTest(ctx) }) {
                Text(stringResource(R.string.call_run_mic_test))
            }
            micTestResult?.let {
                Text(
                    it,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (it.startsWith("OK")) app.aether.aegis.ui.theme.AegisCyan
                            else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Open an AudioRecord with the same audio source / sample rate /
 *  channel config Chromium WebRTC uses, record 100 ms of audio, and
 *  report success or the failure code. */
@android.annotation.SuppressLint("MissingPermission")
private fun runMicTest(ctx: android.content.Context): String {
    // Permission gate first — SuppressLint silences the lint warning for
    // the AudioRecord calls below, so we MUST hand-check here or we'd
    // throw a SecurityException at runtime instead of returning a clean
    // failure string.
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        ctx, android.Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!granted) return "FAIL: RECORD_AUDIO not granted"

    // Match Chromium WebRTC's capture config exactly (48 kHz mono PCM16)
    // so a pass/fail here is representative of what a real call would hit.
    val sampleRate = 48_000
    val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
    val format = android.media.AudioFormat.ENCODING_PCM_16BIT
    val minBuf = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, format)
    // Negative / zero means the device rejected the config outright — no
    // point trying to open a recorder against it.
    if (minBuf <= 0) return "FAIL: AudioRecord.getMinBufferSize=$minBuf"

    // Probe sources in descending preference order: VOICE_COMMUNICATION is
    // what WebRTC actually requests (AEC/AGC tuned), MIC and DEFAULT are
    // fallbacks that help diagnose an OEM that only blocks the comms source.
    val sources = listOf(
        "VOICE_COMMUNICATION" to android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        "MIC" to android.media.MediaRecorder.AudioSource.MIC,
        "DEFAULT" to android.media.MediaRecorder.AudioSource.DEFAULT,
    )
    val results = mutableListOf<String>()
    for ((name, src) in sources) {
        // Walk the full open→start→read→release lifecycle for each source,
        // capturing exactly which step failed. Each stage is wrapped in
        // runCatching so one source throwing never aborts the whole probe —
        // we want to report on all three. minBuf * 2 gives headroom so the
        // short read below isn't starved.
        val rec = runCatching {
            android.media.AudioRecord(src, sampleRate, channelConfig, format, minBuf * 2)
        }.getOrNull()
        if (rec == null) {
            results += "$name: ctor threw"
        } else {
            val state = rec.state
            if (state != android.media.AudioRecord.STATE_INITIALIZED) {
                results += "$name: state=$state (not INITIALIZED)"
                runCatching { rec.release() }
            } else {
                val started = runCatching { rec.startRecording() }.isSuccess
                if (!started) {
                    results += "$name: startRecording threw"
                    runCatching { rec.release() }
                } else {
                    val rs = rec.recordingState
                    if (rs != android.media.AudioRecord.RECORDSTATE_RECORDING) {
                        results += "$name: recordingState=$rs (not RECORDING)"
                        runCatching { rec.stop(); rec.release() }
                    } else {
                        val buf = ShortArray(minBuf)
                        val read = rec.read(buf, 0, buf.size)
                        runCatching { rec.stop(); rec.release() }
                        if (read < 0) {
                            results += "$name: read=$read"
                        } else {
                            results += "$name: OK ($read samples)"
                        }
                    }
                }
            }
        }
    }
    // Overall pass = at least one source reached the successful read
    // branch (its line contains ": OK "). The first endsWith() clause is
    // effectively always true for any line containing "OK" (it compares a
    // string to its own suffix-after-"OK"), so the ": OK " contains check
    // is the load-bearing test. The header colour key (cyan vs error) is
    // driven by the leading "OK" the caller checks for.
    val ok = results.any { it.endsWith("OK${it.substringAfter("OK")}") || it.contains(": OK ") }
    val header = if (ok) "OK · at least one source works:" else "FAIL · every source rejected:"
    return "$header\n${results.joinToString("\n")}"
}

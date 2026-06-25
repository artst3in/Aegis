package app.aether.aegis.ui.screens

import app.aether.aegis.AegisApp
import app.aether.aegis.simplex.SimpleXTransport
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.components.HexShape
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisCyanGlow
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisSOS
import app.aether.aegis.ui.theme.AegisSOSGlow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Push-to-talk card on the SOS dashboard
 * (push-to-talk during SOS).
 *
 *   Responder → Responders: PTT fans out to every OTHER responder
 *     in the SOS. This is the coordination channel — "I see the
 *     garage, third floor."
 *   Responder → Victim: PTT reaches the victim's device ONLY if she
 *     has flipped "Allow incoming voice from responders" on her own
 *     dashboard. Defaults off so a clip can't accidentally play on
 *     her speaker and reveal her position while she's hiding.
 *   Non-responders: this card is HIDDEN. Voice in the responder
 *     channel isn't available to people who haven't opted in.
 *
 * Wire envelope: `[aegis:ptt:<victimKey>]` caption on a SimpleX
 * file message. The victimKey lets each receiver figure out which
 * SOS the clip belongs to (and therefore whether they're a
 * responder for it, and whether to auto-play).
 */
@Composable
fun SOSPushToTalk(peerKey: String, peerName: String) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var outFile by remember { mutableStateOf<File?>(null) }

    // Gate: only opted-in responders see (and can use) PTT.
    // Non-responders observe the SOS but cannot speak into the
    // coordination channel.
    val alert = app.aether.aegis.sos.SOSAlertStore.forPeer(peerKey)
    if (alert?.iAmResponding != true) return

    GlassPanel(modifier = Modifier.fillMaxWidth(), glow = pressed) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.s_o_s_ptt_push_to_talk),
                    color = AegisOnSurfaceDim,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                )
                Text(
                    if (pressed) "Recording — release to send."
                    else "Hold to record. Plays on every other responder's speaker. " +
                        (if (alert.victimAllowsResponderVoice)
                            "$peerName has opened the channel and will hear it too."
                        else "$peerName isn't listening to responder voice."),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                )
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .pointerInput(peerKey) {
                        detectTapGestures(
                            onPress = {
                                // Start recording.
                                haptic.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove,
                                )
                                pressed = true
                                val file = startRecording(context)
                                outFile = file
                                recorder = if (file != null) currentRecorder else null
                                val released = tryAwaitRelease()
                                pressed = false
                                val r = recorder
                                val f = outFile
                                recorder = null
                                outFile = null
                                scope.launch {
                                    val stoppedFile = withContext(Dispatchers.IO) {
                                        stopRecording(r, f)
                                    }
                                    if (stoppedFile != null && released) {
                                        // Fan out to every OTHER
                                        // responder + the victim if she's
                                        // opted in. Pulls the responder
                                        // list from the local coord
                                        // snapshot we already have.
                                        val targets = buildPttTargets(peerKey)
                                        shipPttFanOut(peerKey, targets, stoppedFile)
                                    } else if (stoppedFile != null) {
                                        // Cancelled (drag-off) — discard.
                                        stoppedFile.delete()
                                    }
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                HexShape(
                    size = 64.dp,
                    borderColor = if (pressed) AegisSOS else AegisCyan,
                    fillColor = if (pressed) AegisSOSGlow else AegisCyanGlow,
                    glow = pressed,
                    glowColor = if (pressed) AegisSOSGlow else AegisCyanGlow,
                ) {
                    Text(
                        peerName.take(1).uppercase(),
                        color = if (pressed) AegisSOS else AegisCyan,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// MediaRecorder lives in a top-level mutable so we can stop it from a
// different coroutine. Single PTT button per screen, so a singleton is
// fine for the v1.
private var currentRecorder: android.media.MediaRecorder? = null

private fun startRecording(context: android.content.Context): File? {
    return runCatching {
        val out = File(context.cacheDir, "ptt-${System.currentTimeMillis()}.m4a")
        val rec = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            android.media.MediaRecorder(context)
        else
            @Suppress("DEPRECATION") android.media.MediaRecorder()
        rec.apply {
            setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setOutputFile(out.absolutePath)
            prepare()
            start()
        }
        currentRecorder = rec
        out
    }.getOrNull()
}

private fun stopRecording(rec: android.media.MediaRecorder?, out: File?): File? {
    if (rec == null) return null
    runCatching { rec.stop() }
    runCatching { rec.release() }
    if (currentRecorder == rec) currentRecorder = null
    return out?.takeIf { it.exists() && it.length() > 0 }
}

/**
 * Build the recipient list for a single PTT clip:
 * every other opted-in responder, plus the victim if she's flipped
 * the "Allow incoming voice from responders" toggle. The victim
 * pubkey is [victimKey]; our own pubkey is excluded from the
 * responder roster automatically (we are the sender).
 */
private fun buildPttTargets(victimKey: String): List<String> {
    val selfKey = AegisApp.instance.identity.deviceId
    val alert = app.aether.aegis.sos.SOSAlertStore.forPeer(victimKey)
    val responders = app.aether.aegis.sos.SOSAlertStore.respondersFor(victimKey)
        .map { it.peerKey }
        .filter { it != selfKey }
    return if (alert?.victimAllowsResponderVoice == true) {
        (responders + victimKey).distinct()
    } else {
        responders
    }
}

private suspend fun shipPttFanOut(
    victimKey: String,
    targets: List<String>,
    file: File,
) = withContext(Dispatchers.IO) {
    val simplex = AegisApp.instance.transports
        .filterIsInstance<SimpleXTransport>()
        .firstOrNull() ?: return@withContext
    if (targets.isEmpty()) {
        file.delete()
        return@withContext
    }
    val caption = "[aegis:ptt:$victimKey]"
    targets.forEach { peer ->
        runCatching {
            simplex.sendFileToContact(
                peerPubkey = peer,
                filePath = file.absolutePath,
                isImage = false,
                caption = caption,
                // Live duress audio to responders — forensic channel,
                // exempt from the outbound metadata scrub.
                forensic = true,
            )
        }
    }
}

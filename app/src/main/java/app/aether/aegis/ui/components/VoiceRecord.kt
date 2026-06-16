package app.aether.aegis.ui.components

import app.aether.aegis.util.Attachments
import app.aether.aegis.util.VoiceRecorder
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Offset

/**
 * Hold-to-record voice button with slide-to-cancel — phase-2 spec.
 *
 *   - Press and hold → recording starts (haptic, red ring, "●" pulse)
 *   - While holding, drag LEFT to cancel — past ~80 dp the message is
 *     discarded on release. A "slide to cancel ←" label appears next
 *     to the button while pressed to teach the gesture.
 *   - Release straight up → recording stops + ships via [onRecorded].
 *   - Press < VoiceRecorder.minDurationMs is discarded (accidental tap).
 *
 * Affordance is a hex (LunaGlass language) sized to match the
 * compose-bar send hex. Used by the redesigned compose bar where the
 * right slot toggles between mic (input empty) and send (input
 * non-empty).
 *
 * Lock-for-hands-free (slide UP) is a v2 polish — needs more layout
 * affordance than the current single-row composer has.
 */
@Composable
fun VoiceRecordHex(
    size: Dp = 38.dp,
    onRecorded: (Attachments.Local) -> Unit,
) {
    val context = LocalContext.current
    // One recorder instance for the button's lifetime; survives recompose.
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    // Horizontal drag of the press, clamped to ≤ 0 (left only). Drives both
    // the button's translation and the slide-to-cancel decision.
    var dragOffsetX by remember { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 80 dp left-drag = cancel. Resolved to px once because the gesture loop
    // compares against raw pointer pixels, not dp.
    val cancelThresholdPx = with(density) { 80.dp.toPx() }
    val haptic = LocalHapticFeedback.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (recording) {
            Text(
                stringResource(R.string.voice_slide_cancel),
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .graphicsLayer { translationX = dragOffsetX },
            )
        }
        HexShape(
            size = size,
            borderColor = if (recording) MaterialTheme.colorScheme.error
                          else app.aether.aegis.ui.theme.AegisCyan,
            fillColor = if (recording) MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
                        else Color.Transparent,
            modifier = Modifier
                .graphicsLayer { translationX = dragOffsetX }
                // Hold-to-record gesture, hand-rolled rather than using a
                // detectTapGestures combo so we can track the drag offset
                // continuously (for slide-to-cancel) inside one press.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Anchor the drag to where the finger landed, not the
                        // button centre, so cancel distance is finger-relative.
                        val startX = down.position.x
                        // Recording may fail to start (mic busy / permission);
                        // only flip into the recording UI if it actually began.
                        val started = recorder.start()
                        if (started) {
                            recording = true
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        // Drain pointer events until the finger lifts, tracking
                        // leftward travel. Consume only real moves so the parent
                        // scroll container can't steal the gesture mid-record.
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            if (change.positionChange() != Offset.Zero) {
                                change.consume()
                            }
                            // coerceAtMost(0f): rightward drag is ignored; only
                            // left movement counts toward cancel.
                            dragOffsetX = (change.position.x - startX)
                                .coerceAtMost(0f)
                        }
                        // Decide cancel BEFORE resetting the offset for the
                        // next gesture.
                        val cancelled = dragOffsetX < -cancelThresholdPx
                        dragOffsetX = 0f
                        if (recording) {
                            // stop() returns the clip (or null if under
                            // VoiceRecorder.minDurationMs — an accidental tap).
                            val local = recorder.stop()
                            recording = false
                            // Ship only a real clip that wasn't slid-to-cancel.
                            if (local != null && !cancelled) onRecorded(local)
                            // Distinct haptic confirms the discard on cancel.
                            if (cancelled) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                },
        ) {
            if (recording) {
                Text(
                    "●",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                AegisIcon(
                    icon = AegisIcons.Mic,
                    contentDescription = stringResource(R.string.a11y_voice_record),
                    tint = app.aether.aegis.ui.theme.AegisCyan,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Play/pause widget for a voice-message file. Uses MediaPlayer for
 * simplicity (one short clip at a time, no scrubbing). The button
 * label flips between ▶ and ❚❚ via a state that the player's
 * onCompletionListener resets.
 */
@Composable
fun VoicePlayer(path: String, modifier: Modifier = Modifier) {
    var playing by remember { mutableStateOf(false) }
    val player = remember { MediaPlayer() }
    // Re-prepare whenever the source path changes; release on leave so the
    // native MediaPlayer (a scarce system resource) never leaks. Keyed on
    // `path` so reusing this composable for a different clip rebinds.
    DisposableEffect(path) {
        runCatching {
            player.reset()
            player.setDataSource(path)
            player.prepare()
            // Reset the toggle when playback finishes naturally so the label
            // flips back to ▶ without the user tapping pause.
            player.setOnCompletionListener { playing = false }
        }
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                if (playing) {
                    runCatching { player.pause() }
                    playing = false
                } else {
                    // Restart from the head on each play — these are short
                    // one-shot clips with no scrubbing, so resume isn't worth
                    // tracking a position for.
                    runCatching {
                        player.seekTo(0)
                        player.start()
                    }
                    playing = true
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (playing) "❚❚" else "▶",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            stringResource(R.string.chat_voice_message),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

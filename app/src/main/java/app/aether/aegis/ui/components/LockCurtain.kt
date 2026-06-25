package app.aether.aegis.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisCyanGlow
import kotlinx.coroutines.launch

/**
 * Manual lock curtain.
 *
 * Two-finger drag DOWN from anywhere on screen pulls a curtain from the
 * top. Release past 1/3 of the screen height → [onLock] fires (the app
 * locks behind the PIN). Release before 1/3 → the curtain snaps back.
 * While touching it is always reversible — drag back up to 0 and the
 * lock never triggers.
 *
 * ## Why two fingers, on the Initial pass
 *
 * One-finger drag-down is the system notification shade; under stress
 * the user must not have to compete with it or aim for a small icon.
 * Two fingers is unambiguous and no in-app component uses it. We claim
 * the gesture on [PointerEventPass.Initial] — the parent sees the event
 * before child scrollables — and only consume once two fingers are
 * actually moving downward together, so ordinary one-finger scrolls and
 * taps inside the content are completely unaffected.
 *
 * ## Disabled during sos
 *
 * [enabled] is wired false while a SOS is active: the SOS screen
 * owns the two-finger gesture for brightness, and locking mid-sos is
 * pointless (already broadcasting; duress doesn't apply). When disabled
 * this composable is a transparent pass-through.
 *
 * NOTE: multi-touch gesture arbitration is notoriously device-dependent;
 * the engagement threshold and feel here are a first cut that wants
 * tuning on real hardware.
 */
@Composable
fun LockCurtain(
    enabled: Boolean,
    onLock: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Current curtain extension in px (0 = hidden, grows as the user
    // drags down). Driven directly by the drag and animated back on a
    // sub-threshold release.
    var curtainPx by remember { mutableFloatStateOf(0f) }
    var containerH by remember { mutableStateOf(1) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerH = it.height }
            .then(
                if (!enabled) Modifier
                else Modifier.pointerInput(Unit) {
                    // Two thresholds keep the curtain from stealing a
                    // pinch-zoom on the radar map (the bug: any downward
                    // two-finger drift engaged + consumed the gesture, so
                    // osmdroid never saw the pinch):
                    //   pinchSlop     — if the distance BETWEEN the two
                    //                   fingers changes past this, it's a
                    //                   pinch (zoom), not a curtain; bail
                    //                   WITHOUT consuming so the map gets it.
                    //   engageSlopPx  — require this much sustained downward
                    //                   parallel travel before we commit +
                    //                   start consuming, which gives a pinch
                    //                   time to reveal itself first. Below
                    //                   the threshold we don't consume, so a
                    //                   gesture we ultimately hand off isn't
                    //                   swallowed.
                    val pinchSlop = 24.dp.toPx()
                    val engageSlopPx = 16.dp.toPx()
                    fun spreadOf(
                        changes: List<androidx.compose.ui.input.pointer.PointerInputChange>,
                    ): Float {
                        val a = changes[0].position
                        val b = changes[1].position
                        return kotlin.math.hypot(a.x - b.x, a.y - b.y)
                    }
                    awaitPointerEventScope {
                        while (true) {
                            // Wait until at least two pointers are down.
                            var ev = awaitPointerEvent(PointerEventPass.Initial)
                            var pressed = ev.changes.filter { it.pressed }
                            if (pressed.size < 2) continue
                            // Baseline finger spread — the pinch reference.
                            val baseSpread = spreadOf(pressed)

                            var engaged = false
                            var abandoned = false
                            var accum = 0f
                            // Track the two-finger gesture until a finger lifts.
                            while (true) {
                                ev = awaitPointerEvent(PointerEventPass.Initial)
                                pressed = ev.changes.filter { it.pressed }
                                if (pressed.size < 2) break
                                val dy = pressed
                                    .map { it.position.y - it.previousPosition.y }
                                    .average().toFloat()
                                if (!engaged) {
                                    // Pinch wins before we commit: the fingers
                                    // spread or close past the slop → hand the
                                    // gesture to the map, never consuming.
                                    if (kotlin.math.abs(spreadOf(pressed) - baseSpread) > pinchSlop) {
                                        abandoned = true
                                        break
                                    }
                                    // Accumulate parallel downward travel; an
                                    // up move bleeds it back toward 0.
                                    accum = (accum + dy).coerceAtLeast(0f)
                                    // Commit only once it's clearly a downward
                                    // curtain drag, THEN start consuming.
                                    if (accum >= engageSlopPx) {
                                        engaged = true
                                        curtainPx = accum
                                        pressed.forEach { it.consume() }
                                    }
                                } else {
                                    accum = (accum + dy).coerceAtLeast(0f)
                                    curtainPx = accum
                                    // Claim the touches so child scrollables stay put.
                                    pressed.forEach { it.consume() }
                                }
                            }

                            // Release: past 1/3 → lock; otherwise snap back.
                            if (!abandoned && engaged && curtainPx > size.height / 3f) {
                                curtainPx = 0f
                                onLock()
                            } else if (curtainPx > 0f) {
                                val from = curtainPx
                                scope.launch {
                                    animate(from, 0f) { v, _ -> curtainPx = v }
                                }
                            }
                        }
                    }
                },
            ),
    ) {
        content()

        // The curtain itself — a dark panel sliding down from the top with
        // a lock glyph + a hint that flips once past the lock threshold.
        if (curtainPx > 0f) {
            val heightDp = with(density) { curtainPx.toDp() }
            val past = curtainPx > containerH / 3f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                AegisCyanGlow.copy(alpha = 0.10f),
                                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.94f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    HexShape(
                        size = 44.dp,
                        borderColor = if (past) AegisCyan else AegisBorder,
                        fillColor = if (past) AegisCyanGlow else androidx.compose.ui.graphics.Color.Transparent,
                    ) {
                        // LunaGlass lock vector (not the OS 🔒 emoji, which
                        // renders its own multicolour glyph and ignores the
                        // cyan tint).
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(
                                app.aether.aegis.R.drawable.ic_aegis_lock,
                            ),
                            contentDescription = null,
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                if (past) AegisCyan else AegisBorder,
                            ),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    androidx.compose.material3.Text(
                        if (past) "Release to lock" else "Keep dragging to lock",
                        color = if (past) AegisCyan else AegisBorder,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

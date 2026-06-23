package app.aether.aegis.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisPanel
import kotlin.math.roundToInt

/**
 * Flat-top hexagonal slider. Drop-in API-compatible with
 * [androidx.compose.material3.Slider] for the slots Aegis uses
 * (value / onValueChange / onValueChangeFinished / valueRange / steps /
 * enabled). Replaces the round-thumb, round-track Material slider so every
 * range control (attachment size, mugshot threshold, quiet hours, sonar,
 * graphics, and the LogPeriodSlider wrapper) speaks the same LunaGlass
 * facet language as [HexSwitch].
 *
 * Visuals:
 *  - Track: a thin elongated flat-top hex bar spanning the width. The
 *    inactive remainder is panel-fill + faint border; the active portion
 *    (left of the thumb) is clipped-fill AegisCyan.
 *  - Thumb: a flat-top hexagon (matching the switch thumb) with a cyan
 *    glow halo.
 *
 * [steps] follows Material's semantics (interior stops; total = steps + 2):
 * the value snaps to the nearest discrete stop, but no tick glyphs are
 * painted — several callers use 40+ steps where ticks would just clutter
 * the slim track, and the label above each slider already names the value.
 *
 * Drag/tap update the value continuously; [onValueChangeFinished] fires
 * once per gesture on release, so callers keep their "persist on release,
 * preview on drag" pattern unchanged. The thumb position is derived from
 * the controlled [value] every frame — this composable holds no value
 * state of its own, only the measured width.
 */
@Composable
fun HexSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val thumbRpx = with(density) { THUMB_R.toPx() }
    // Measured component width in px; the usable travel is the width minus a
    // thumb radius of inset on each side so the thumb tip never clips the edge.
    var widthPx by remember { mutableIntStateOf(0) }

    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = (end - start).takeIf { it != 0f } ?: 1f

    // Current fraction (0..1) from the controlled value — clamped so an
    // out-of-range value can't push the thumb past the track.
    val fraction = ((value - start) / span).coerceIn(0f, 1f)

    // Translate a touch x (px, element-relative) into a value, snapping to the
    // discrete stops when steps>0. Mirrors Material: stops = steps + 1 segments.
    fun emit(xPx: Float) {
        val travel = (widthPx - 2 * thumbRpx).takeIf { it > 0f } ?: return
        var f = ((xPx - thumbRpx) / travel).coerceIn(0f, 1f)
        if (steps > 0) {
            val segments = steps + 1
            f = (f * segments).roundToInt().toFloat() / segments
        }
        onValueChange(start + f * span)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TOUCH)
            .alpha(if (enabled) 1f else 0.5f)
            .onSizeChanged { widthPx = it.width }
            // ONE gesture detector for both tap and drag. The previous version
            // ran detectTapGestures and detectDragGestures as two separate
            // pointerInputs; the tap detector won the gesture and fired only on
            // release, so the thumb looked frozen mid-drag and "snapped" to the
            // lift position (user report 2026-06-21). Handling press → drag →
            // release in a single awaitEachGesture removes that conflict: jump to
            // the press point immediately (tap), follow the pointer every frame
            // (drag), fire onValueChangeFinished once on lift. Consuming the down
            // also claims the gesture from an enclosing vertical scroll.
            .pointerInput(enabled, valueRange, steps, widthPx) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    emit(down.position.x)            // tap / press: jump to point
                    down.consume()
                    // Follow the pointer until it lifts; emit on every move.
                    drag(down.id) { change ->
                        emit(change.position.x)
                        change.consume()
                    }
                    onValueChangeFinished?.invoke()  // once per gesture, on lift
                }
            },
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(TOUCH)) {
            drawHexSlider(fraction, thumbRpx)
        }
    }
}

/**
 * Paint the track (inactive + active), discrete ticks, and the hex thumb.
 * Split out from the composable so the drawing math is testable/readable in
 * isolation; [fraction] is the 0..1 thumb position and [thumbRpx] the thumb
 * circumradius in px (also the track end-inset).
 */
private fun DrawScope.drawHexSlider(fraction: Float, thumbRpx: Float) {
    val w = size.width
    val cy = size.height / 2f
    val left = thumbRpx
    val right = w - thumbRpx
    val travel = (right - left).coerceAtLeast(0f)
    val thumbX = left + fraction * travel

    // Track: thin elongated flat-top hex, pointed ends at left/right insets.
    val trackHalfH = TRACK_H.toPx() / 2f
    val tip = trackHalfH                     // horizontal length of each pointed end
    val track = Path().apply {
        moveTo(left, cy - trackHalfH)
        lineTo(right, cy - trackHalfH)
        lineTo(right + tip, cy)
        lineTo(right, cy + trackHalfH)
        lineTo(left, cy + trackHalfH)
        lineTo(left - tip, cy)
        close()
    }
    drawPath(track, color = AegisPanel)
    drawPath(track, color = AegisBorder, style = Stroke(width = 1f.dp.toPx()))

    // Active fill: clip to the track, paint cyan from the left tip to the thumb.
    clipPath(track) {
        drawRect(
            color = AegisCyan.copy(alpha = 0.55f),
            topLeft = Offset(left - tip, cy - trackHalfH),
            size = androidx.compose.ui.geometry.Size(
                width = (thumbX - (left - tip)).coerceAtLeast(0f),
                height = trackHalfH * 2,
            ),
        )
    }

    // Thumb: flat-top hexagon (same orientation as the switch thumb).
    val r = thumbRpx
    val rHalf = r / 2f
    val hexTop = cy - (r * SQRT3) / 2f
    val hexBot = cy + (r * SQRT3) / 2f
    val thumb = Path().apply {
        moveTo(thumbX - rHalf, hexTop)
        lineTo(thumbX + rHalf, hexTop)
        lineTo(thumbX + r, cy)
        lineTo(thumbX + rHalf, hexBot)
        lineTo(thumbX - rHalf, hexBot)
        lineTo(thumbX - r, cy)
        close()
    }
    // Glow halo first, then the thumb on top. The thumb is filled SOLID
    // AegisCyan (was AegisCyanGlow, alpha 0.15 — which let the dark track show
    // through so the thumb read as a hollow outline; user report 2026-06-21).
    // The stroke keeps a crisp edge against the track.
    for (i in 1..3) {
        drawCircle(
            color = AegisCyan.copy(alpha = 0.10f / i),
            radius = r + i * 1.5f.dp.toPx(),
            center = Offset(thumbX, cy),
        )
    }
    drawPath(thumb, color = AegisCyan)
    drawPath(thumb, color = AegisCyan, style = Stroke(width = 1.5f.dp.toPx()))
}

private const val SQRT3 = 1.7320508f
// Thumb circumradius 10 dp → 20 dp-wide hex, comfortably inside the 40 dp
// touch band; track is a slim 6 dp so the thumb reads as the dominant element.
private val THUMB_R = 10.dp
private val TRACK_H = 6.dp
private val TOUCH = 40.dp

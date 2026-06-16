package app.aether.aegis.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisCyanGlow
import app.aether.aegis.ui.theme.AegisPanel
import app.aether.aegis.ui.theme.AegisSurface
import kotlin.math.sqrt

/**
 * Two-hex binary switch. Drop-in API-compatible with
 * [androidx.compose.material3.Switch].
 *
 * Geometry per user spec: "length two hexes, height one hex". The
 * track is itself a flat-top hexagon stretched so its top and bottom
 * edges fit exactly two thumb hexes side-by-side. The thumb is a
 * regular flat-top hexagon (width = 2R, height = R√3) that slides
 * the full width of the track — left tip flush with track left tip
 * in OFF, right tip flush with track right tip in ON.
 *
 *   Track total: 4R wide × R√3 tall, flat-top edge length = 3R.
 *   Thumb:       2R wide × R√3 tall, flat-top edge length = R.
 *   Travel:      2R (thumb center from x=R to x=3R).
 *
 * With R = 14 dp → switch is 56 × ~24 dp. The composable wraps the
 * visual in a 56 × 36 dp click area so the touch target hits Material
 * accessibility minimums; the painted hex stays at its true height.
 */
@Composable
fun HexSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // 120 ms ease-out tween — solid, responsive, precise. The
    // earlier MediumBouncy spring read as "broken engineering"
    // because the thumb visibly overshoots and rebounds before
    // settling. Linear-ish curve with a fast-in-slow-out finish
    // commits to the destination on tap without ringing.
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "hex-switch-thumb",
    )
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(width = SWITCH_W, height = TOUCH_H)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && onCheckedChange != null,
                onClick = { onCheckedChange?.invoke(!checked) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(width = SWITCH_W, height = HEX_H)) {
            val w = size.width
            val h = size.height
            val r = w / 4f                // hex circumradius in px
            val rHalf = r / 2f
            val cy = h / 2f

            // Track: elongated flat-top hex. Left/right tips at the
            // canvas edges; flat top/bottom run from (R/2, 0) to
            // (3.5R, 0) — exactly two thumb widths long.
            val trackPath = Path().apply {
                moveTo(rHalf, 0f)
                lineTo(w - rHalf, 0f)
                lineTo(w, cy)
                lineTo(w - rHalf, h)
                lineTo(rHalf, h)
                lineTo(0f, cy)
                close()
            }
            drawPath(trackPath, color = AegisPanel)
            drawPath(
                trackPath,
                color = AegisBorder,
                style = Stroke(width = 1f.dp.toPx()),
            )

            // Thumb: regular flat-top hex of width 2R. OFF: center at
            // cx=R (left tip at 0). ON: center at cx=3R (right tip
            // at w). Travel = 2R.
            val cx = r + 2f * r * progress
            val thumbPath = Path().apply {
                moveTo(cx - rHalf, 0f)
                lineTo(cx + rHalf, 0f)
                lineTo(cx + r, cy)
                lineTo(cx + rHalf, h)
                lineTo(cx - rHalf, h)
                lineTo(cx - r, cy)
                close()
            }
            // Fade-cross fill + stroke from OFF -> ON so the thumb
            // visually heats up rather than flipping in a single frame.
            val fillOff = AegisSurface
            val fillOn = AegisCyanGlow
            val fillBlended = Color(
                red = fillOff.red * (1 - progress) + fillOn.red * progress,
                green = fillOff.green * (1 - progress) + fillOn.green * progress,
                blue = fillOff.blue * (1 - progress) + fillOn.blue * progress,
                alpha = fillOff.alpha * (1 - progress) + fillOn.alpha * progress,
            )
            val strokeOff = AegisBorder
            val strokeOn = AegisCyan
            val strokeBlended = Color(
                red = strokeOff.red * (1 - progress) + strokeOn.red * progress,
                green = strokeOff.green * (1 - progress) + strokeOn.green * progress,
                blue = strokeOff.blue * (1 - progress) + strokeOn.blue * progress,
                alpha = strokeOff.alpha * (1 - progress) + strokeOn.alpha * progress,
            )
            drawPath(thumbPath, color = fillBlended)
            drawPath(
                thumbPath,
                color = strokeBlended,
                style = Stroke(width = 1.5f.dp.toPx()),
            )
            // Soft cyan glow under the thumb when ON.
            if (progress > 0f) {
                for (i in 1..3) {
                    val radius = r + i * 1.5f.dp.toPx()
                    drawCircle(
                        color = AegisCyan.copy(alpha = 0.10f * progress / i),
                        radius = radius,
                        center = Offset(cx, cy),
                    )
                }
            }
        }
    }
}

// Visual dimensions: thumb circumradius 18 dp → switch 72 × 31 dp.
// The earlier 56 × 24 sized cleanly but read as "loose" inside the
// usual settings-row chrome (text + 16 dp padding), so we bumped R
// to 18 so the hex anchors visually next to its label. The 2 : √3
// length-to-height ratio is preserved per the user's "two hexes
// long, one hex tall" spec. Click box stays a hair taller than the
// hex (40 dp) to keep the touch target at Material's 48 dp slop.
private val SWITCH_W = 72.dp
private val HEX_H = ((72.0 * sqrt(3.0) / 4.0).toInt()).dp
private val TOUCH_H = 40.dp

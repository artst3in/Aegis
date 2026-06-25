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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisCyanGlow
import app.aether.aegis.ui.theme.AegisPanel
import app.aether.aegis.ui.theme.AegisSurface

/**
 * Flat-top hexagonal checkbox. Drop-in API-compatible with
 * [androidx.compose.material3.Checkbox] for the slots Aegis actually
 * uses (checked / onCheckedChange / enabled). Exists so multi-select
 * lists (group member picker, sonar peer toggles) read as the same
 * LunaGlass facet language as [HexSwitch] / [HexRadio] instead of the
 * round-cornered Material square.
 *
 * Geometry mirrors the [HexSwitch] thumb: a flat-top hexagon (flat top
 * and bottom edges, pointed left/right), drawn 22 dp wide inside a
 * 40 dp click box so the touch target clears Material's 48 dp slop.
 * Unchecked = panel fill + faint border. Checked = cyan glow fill +
 * cyan stroke + a dark checkmark, cross-faded over 120 ms so the box
 * "lights up" rather than snapping.
 *
 * A null [onCheckedChange] renders a display-only box (the row's own
 * click handles the toggle) — same contract as Material's Checkbox.
 */
@Composable
fun HexCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // 120 ms ease-out: matches HexSwitch so every binary control in the
    // app animates with the same timing.
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "hex-checkbox",
    )
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(TOUCH)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && onCheckedChange != null,
                onClick = { onCheckedChange?.invoke(!checked) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(BOX)) {
            val w = size.width
            val h = size.height
            val r = w / 2f          // circumradius (tip-to-center horizontally)
            val rHalf = r / 2f
            val cx = w / 2f
            val cy = h / 2f

            // Flat-top hex (same orientation as the switch thumb): flat top
            // edge of length r, pointed left/right tips at the box edges.
            val hex = Path().apply {
                moveTo(cx - rHalf, 0f)
                lineTo(cx + rHalf, 0f)
                lineTo(cx + r, cy)
                lineTo(cx + rHalf, h)
                lineTo(cx - rHalf, h)
                lineTo(cx - r, cy)
                close()
            }

            // Cross-fade fill + stroke OFF -> ON.
            val fill = lerpColor(AegisPanel, AegisCyanGlow, progress)
            val stroke = lerpColor(AegisBorder, AegisCyan, progress)
            drawPath(hex, color = fill)
            drawPath(hex, color = stroke, style = Stroke(width = 1.5f.dp.toPx()))

            // Checkmark, drawn only once the box is mostly lit so it doesn't
            // smear during the fade. Dark ink against the cyan glow.
            if (progress > 0.15f) {
                val check = Path().apply {
                    moveTo(w * 0.30f, h * 0.52f)
                    lineTo(w * 0.44f, h * 0.66f)
                    lineTo(w * 0.72f, h * 0.34f)
                }
                drawPath(
                    check,
                    color = AegisSurface.copy(alpha = progress),
                    style = Stroke(width = 2f.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // Soft cyan halo when checked, echoing the switch glow.
            if (progress > 0f) {
                for (i in 1..3) {
                    drawCircle(
                        color = AegisCyan.copy(alpha = 0.08f * progress / i),
                        radius = r + i * 1.5f.dp.toPx(),
                        center = Offset(cx, cy),
                    )
                }
            }
        }
    }
}

/** Component-local channel-wise color lerp — keeps the cross-fade math in
 *  one place across the hex controls without pulling in an animation API. */
internal fun lerpColor(from: Color, to: Color, t: Float): Color = Color(
    red = from.red * (1 - t) + to.red * t,
    green = from.green * (1 - t) + to.green * t,
    blue = from.blue * (1 - t) + to.blue * t,
    alpha = from.alpha * (1 - t) + to.alpha * t,
)

private val BOX = 22.dp
private val TOUCH = 40.dp

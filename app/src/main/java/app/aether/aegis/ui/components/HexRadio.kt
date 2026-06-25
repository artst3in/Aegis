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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisCyanGlow
import app.aether.aegis.ui.theme.AegisPanel

/**
 * Flat-top hexagonal single-choice control. Drop-in API-compatible with
 * [androidx.compose.material3.RadioButton] (selected / onClick / enabled).
 * Used in the app's exclusive-choice lists (language, crash sensitivity,
 * invitation expiry, sonar throttle, notification privacy) so they share
 * the LunaGlass facet language instead of the round Material radio dot.
 *
 * A hollow flat-top hex ring is always drawn; selecting fills a smaller
 * concentric hex with cyan glow + cyan stroke and lights a soft halo,
 * cross-faded over 120 ms to match [HexSwitch] / [HexCheckbox]. A null
 * [onClick] renders display-only (the row owns the click) — same contract
 * as Material's RadioButton.
 */
@Composable
fun HexRadio(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "hex-radio",
    )
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(TOUCH)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && onClick != null,
                onClick = { onClick?.invoke() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(BOX)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f

            // Outer ring hex (unfilled). Stroke heats border -> cyan on select.
            val outer = flatTopHex(cx, cy, w / 2f)
            val ringStroke = lerpColor(AegisBorder, AegisCyan, progress)
            drawPath(outer, color = AegisPanel.copy(alpha = 0.6f))
            drawPath(outer, color = ringStroke, style = Stroke(width = 1.5f.dp.toPx()))

            // Inner filled hex grows in as it's selected (0 -> 0.52·r), so the
            // dot "blooms" rather than popping at full size.
            if (progress > 0f) {
                val innerR = (w / 2f) * 0.52f * progress
                val inner = flatTopHex(cx, cy, innerR)
                drawPath(inner, color = AegisCyanGlow)
                drawPath(inner, color = AegisCyan.copy(alpha = progress), style = Stroke(width = 1.5f.dp.toPx()))
                // Halo echoing the other hex controls.
                for (i in 1..3) {
                    drawCircle(
                        color = AegisCyan.copy(alpha = 0.07f * progress / i),
                        radius = w / 2f + i * 1.5f.dp.toPx(),
                        center = Offset(cx, cy),
                    )
                }
            }
        }
    }
}

/** Build a flat-top hexagon path (flat top/bottom, pointed left/right tips)
 *  centered at [cx],[cy] with horizontal circumradius [r]. The hex height is
 *  r·√3, vertically centered on [cy]. */
private fun flatTopHex(cx: Float, cy: Float, r: Float): Path {
    val rHalf = r / 2f
    val top = cy - (r * SQRT3) / 2f
    val bot = cy + (r * SQRT3) / 2f
    return Path().apply {
        moveTo(cx - rHalf, top)
        lineTo(cx + rHalf, top)
        lineTo(cx + r, cy)
        lineTo(cx + rHalf, bot)
        lineTo(cx - rHalf, bot)
        lineTo(cx - r, cy)
        close()
    }
}

private const val SQRT3 = 1.7320508f
private val BOX = 22.dp
private val TOUCH = 40.dp

package app.aether.aegis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.aegis.ui.theme.AegisCyan
import kotlin.math.max
import kotlin.math.min

/**
 * Three stacked sparklines — X / Y / Z channels of an inertial
 * recording. The "forensic playback v1" choice: honest, no
 * sensor-fusion drift, the eye reads three line traces faster than
 * any 3D animation would convey. Each channel is rendered in its
 * own mini-canvas with a horizontal centre-line for the zero axis;
 * the y-axis auto-scales to the channel's own min/max so even a
 * small wobble is legible.
 *
 * Channel colours: X red-ish, Y green-ish, Z cyan — same convention
 * Android's sensor visualisation tools use, so anyone who's looked
 * at a logcat sensor dump reads it instantly.
 *
 * Use case: the inbox detail screen for a Sentinel RECORDING event
 * loads the per-event accel file via SentinelRecording.read() and
 * passes its X / Y / Z series here.
 */
@Composable
fun SparklineTriplet(
    xs: List<Float>,
    ys: List<Float>,
    zs: List<Float>,
    height: Dp = 56.dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ChannelSparkline("X", xs, Color(0xFFE57373), height)
        ChannelSparkline("Y", ys, Color(0xFF81C784), height)
        ChannelSparkline("Z", zs, AegisCyan, height)
    }
}

@Composable
private fun ChannelSparkline(
    label: String,
    values: List<Float>,
    color: Color,
    height: Dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Text(
            label,
            fontSize = 10.sp,
            color = color,
            modifier = Modifier.width(18.dp),
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(height)
                .padding(vertical = 2.dp),
        ) {
            if (values.isEmpty()) return@Canvas
            val w = size.width
            val h = size.height
            // Auto-scale: find min/max so the channel uses its own
            // full vertical extent. Tiny wobble around zero gets
            // amplified to fill the panel — gives the "shape of the
            // grab" maximum visual fidelity.
            var mn = values.first()
            var mx = values.first()
            for (v in values) { mn = min(mn, v); mx = max(mx, v) }
            val span = max(0.001f, mx - mn)
            // Zero axis (or channel midline if zero is out of range)
            val midY = if (mn <= 0f && mx >= 0f) {
                h * (mx / span)
            } else {
                h / 2f
            }
            drawLine(
                color = color.copy(alpha = 0.18f),
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1f,
            )
            // Sparkline path.
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = w * i / (values.size - 1).coerceAtLeast(1)
                val y = h - h * ((v - mn) / span)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 1.6f))
        }
    }
}


package app.aether.aegis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aether.aegis.ui.theme.AegisCyan

/**
 * Cellular-style signal-strength bars (0–4) for a peer's last-reported
 * radio signal. Drives off [MemberStatusEntity.signalStrength] (dBm from
 * the sender's TelephonyManager), the same value the status/radar views
 * used to print as raw "-70 dBm" text.
 *
 * Rising bars, lit cyan up to the current [level] and dimmed cyan above it
 * (NO teal — LunaGlass palette). A null/unknown reading shows all bars
 * dimmed (level 0) rather than hiding, so the slot stays visually stable
 * as readings come and go.
 *
 * Compact by design (defaults ~16×12 dp) so it sits inline next to a
 * presence dot in a contact row or the radar dock without reflowing it.
 */

/** Map a dBm reading to a 0–4 bar level. Thresholds are the standard
 *  cellular RSRP/RSSI buckets; null (no reading) is 0. The boundaries are
 *  deliberately wide so a normally-fluctuating signal doesn't jitter a bar
 *  up and down every status tick. */
fun signalLevel(dbm: Int?): Int = when {
    dbm == null -> 0
    dbm >= -75 -> 4     // excellent
    dbm >= -90 -> 3     // good
    dbm >= -105 -> 2    // fair
    dbm >= -118 -> 1    // poor
    else -> 0           // effectively no usable signal
}

@Composable
fun SignalBars(
    dbm: Int?,
    modifier: Modifier = Modifier,
    /** Total bars to draw; the lit count comes from [signalLevel]. */
    barCount: Int = 4,
    width: Dp = 16.dp,
    height: Dp = 12.dp,
    lit: Color = AegisCyan,
    /** Unlit bars — a dim cyan, never teal/grey, to stay on-palette. */
    dim: Color = AegisCyan.copy(alpha = 0.22f),
) {
    val level = signalLevel(dbm).coerceIn(0, barCount)
    Canvas(modifier = modifier.size(width, height)) {
        val n = barCount
        // Gap is a fraction of a bar slot so the cluster reads as one glyph.
        val slot = size.width / n
        val barW = slot * 0.62f
        val gap = slot - barW
        for (i in 0 until n) {
            // Bars rise left→right: shortest is 40% height, tallest full.
            val frac = 0.40f + 0.60f * (i.toFloat() / (n - 1).coerceAtLeast(1))
            val barH = size.height * frac
            val x = i * slot + gap / 2f
            val y = size.height - barH
            drawRect(
                color = if (i < level) lit else dim,
                topLeft = Offset(x, y),
                size = Size(barW, barH),
            )
        }
    }
}

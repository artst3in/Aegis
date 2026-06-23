package app.aether.aegis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan

/**
 * Determinate progress bar in the LunaGlass language: the SAME elongated bar
 * as a Material LinearProgressIndicator, but with CUT (chamfered) ends instead
 * of rounded caps — so it reads as a "long hex" like [HexSwitch]'s track,
 * instead of a stadium pill. Every LunaGlass surface is faceted; the progress
 * bar was a rounded holdout.
 *
 * [CutCornerShape] with `percent = 50` cuts each corner by half the bar's
 * height, so the left/right ends taper to the flat-top hex tip at ANY bar
 * height — and clipping the parent also clips the fill, so the fill's left end
 * inherits the same tip while its right edge is the live progress position.
 *
 * Drop-in for the determinate `LinearProgressIndicator(progress = { x })`:
 * pass [progress] as a 0..1 Float (not a lambda).
 */
@Composable
fun HexProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = AegisCyan,
    trackColor: Color = AegisBorder,
    height: Dp = 6.dp,
) {
    val p = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(height)
            .clip(CutCornerShape(percent = 50))
            .background(trackColor),
    ) {
        if (p > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(p)
                    .background(color),
            )
        }
    }
}

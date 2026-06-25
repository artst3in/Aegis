package app.aether.aegis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compass-needle radar glyph rendered in Compose rather than as a
 * vector drawable. The revised radar icon (from the LunaGlass icon
 * reference) calls for
 * a stylised "N" inside the filled upper triangle, drawn as NEGATIVE
 * SPACE — the N strokes must be the background colour cutting
 * through the cyan fill. A regular vector drawable can't do that
 * under Compose's [androidx.compose.material3.Icon] tinting (the
 * ColorFilter.tint replaces every non-transparent pixel uniformly,
 * so the N would tint cyan too and disappear into the triangle).
 *
 * So we draw it directly: diamond outline + filled top half + N
 * stroke in [AegisBackground] over the fill.
 */
@Composable
fun RadarHexIcon(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val px = w / 24f  // viewBox is 24×24

        val outlineStroke = 1.8f * px
        // Thin N strokes — the N is a tiny detail; thick strokes blob together.
        val nStroke = 1.3f * px

        // Diamond outline: M12,3 L17,12 L12,21 L7,12 Z
        val diamond = Path().apply {
            moveTo(12f * px, 3f * px)
            lineTo(17f * px, 12f * px)
            lineTo(12f * px, 21f * px)
            lineTo(7f * px, 12f * px)
            close()
        }
        drawPath(
            path = diamond,
            color = color,
            style = Stroke(width = outlineStroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // Filled top half + the negative-space "N", composited in their OWN
        // layer so the N can be punched as a TRUE transparent hole. The old
        // code painted the N in the (opaque) background colour to FAKE the
        // cut-out; that worked flat, but now the icon catches a metal shine —
        // and a background-coloured N is still opaque, so the shine's SrcAtop
        // reflected across it "like solid metal" (user report). BlendMode.Clear
        // erases to genuine transparency, so the shine skips the N and it stays
        // a real hole.
        val topHalf = Path().apply {
            moveTo(12f * px, 3f * px)
            lineTo(17f * px, 12f * px)
            lineTo(7f * px, 12f * px)
            close()
        }
        val layerBounds = androidx.compose.ui.geometry.Rect(0f, 0f, w, h)
        drawContext.canvas.saveLayer(layerBounds, androidx.compose.ui.graphics.Paint())
        drawPath(path = topHalf, color = color)
        // N strokes cleared to transparent (colour ignored under Clear).
        // Geometry: narrower (x 11→13) and lower (y 8→11) than the original so
        // the strokes clear the triangle's steep top-edge outline instead of
        // overlapping it into a blob (the "needle eats the N" report). Sits in
        // the wider lower part of the filled triangle.
        val clear = androidx.compose.ui.graphics.BlendMode.Clear
        drawLine(
            color = Color.Black,
            start = Offset(11f * px, 11f * px),
            end   = Offset(11f * px,  8f * px),
            strokeWidth = nStroke, cap = StrokeCap.Round, blendMode = clear,
        )
        drawLine(
            color = Color.Black,
            start = Offset(11f * px,  8f * px),
            end   = Offset(13f * px, 11f * px),
            strokeWidth = nStroke, cap = StrokeCap.Round, blendMode = clear,
        )
        drawLine(
            color = Color.Black,
            start = Offset(13f * px, 11f * px),
            end   = Offset(13f * px,  8f * px),
            strokeWidth = nStroke, cap = StrokeCap.Round, blendMode = clear,
        )
        drawContext.canvas.restore()
    }
}

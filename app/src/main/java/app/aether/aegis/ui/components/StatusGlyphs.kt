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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Message-status pending glyph: a hex clock.
 *
 * Drawn as vector strokes (NOT an emoji — emoji ignore the tint and clash with
 * the monochrome ticks) and curve-free, so it obeys LunaGlass (the app is hexes
 * everywhere). It's a flat-top hexagon — the SAME orientation as [HexShape] /
 * every avatar+medal+switch — with two straight clock hands, so it reads as the
 * universal "waiting to send" cue while staying on-brand.
 *
 * Shown for the `"sending"` status: a message that's been handed to the
 * transport but has NOT yet left the device (`sndSent` hasn't fired) — offline,
 * it honestly sits here until the radio returns. See ChatScreen's tick ladder.
 */
@Composable
fun PendingHexClock(
    color: Color,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 11.dp,
) {
    Canvas(modifier = modifier.size(glyphSize)) {
        val w = size.minDimension
        val cx = size.width / 2f
        val cy = size.height / 2f
        // Slight inset so the stroke isn't clipped at the bounds.
        val r = w / 2f * 0.90f
        // Stroke scales with the glyph; floored so it stays visible at ~11dp.
        val stroke = (w * 0.11f).coerceAtLeast(1.4f)

        // Flat-top hexagon face: vertices at (π/3)·i — identical to hexVertices
        // in HexShape (0° = right vertex → flat top + bottom, points L/R).
        val face = Path()
        for (i in 0 until 6) {
            val a = (PI / 3.0 * i).toFloat()
            val x = cx + r * cos(a)
            val y = cy + r * sin(a)
            if (i == 0) face.moveTo(x, y) else face.lineTo(x, y)
        }
        face.close()
        drawPath(face, color, style = Stroke(width = stroke, join = StrokeJoin.Miter))

        // Hands: minute straight up (12), hour to ~2 o'clock. Butt caps keep
        // them angular. Length tuned so they read inside the small face.
        val hand = r * 0.60f
        drawLine(color, Offset(cx, cy), Offset(cx, cy - hand), strokeWidth = stroke, cap = StrokeCap.Butt)
        drawLine(
            color, Offset(cx, cy),
            Offset(cx + hand * 0.72f, cy - hand * 0.42f),
            strokeWidth = stroke, cap = StrokeCap.Butt,
        )
    }
}

/**
 * Angular hourglass glyph — PARKED for later use (an alternative/secondary
 * "waiting" indicator, e.g. scheduled-send or a long-running queue). Curve-free
 * to match LunaGlass: two bars, a sharp-waisted bowtie, and a triangular sand
 * pile. Not currently wired into any surface; kept here as a ready asset so the
 * design doesn't have to be re-derived. Drawn by [drawHourglass] so it can be
 * composed standalone or inside another Canvas.
 */
@Composable
fun HourglassGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 11.dp,
) {
    Canvas(modifier = modifier.size(glyphSize)) { drawHourglass(color) }
}

/** Draw the parked angular hourglass into the current [DrawScope] bounds. */
fun DrawScope.drawHourglass(color: Color) {
    val w = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    val hw = w / 2f * 0.74f          // half-width of the frame
    val hh = w / 2f * 0.90f          // half-height
    val waist = w * 0.06f            // exaggerated narrow waist for legibility
    val stroke = (w * 0.11f).coerceAtLeast(1.4f)

    // Top + bottom bars.
    drawLine(color, Offset(cx - hw, cy - hh), Offset(cx + hw, cy - hh), strokeWidth = stroke, cap = StrokeCap.Butt)
    drawLine(color, Offset(cx - hw, cy + hh), Offset(cx + hw, cy + hh), strokeWidth = stroke, cap = StrokeCap.Butt)

    // Bowtie glass outline (sharp mitered corners, no curves).
    val glass = Path().apply {
        moveTo(cx - hw * 0.9f, cy - hh)
        lineTo(cx + hw * 0.9f, cy - hh)
        lineTo(cx + waist, cy)
        lineTo(cx + hw * 0.9f, cy + hh)
        lineTo(cx - hw * 0.9f, cy + hh)
        lineTo(cx - waist, cy)
        close()
    }
    drawPath(glass, color, style = Stroke(width = stroke, join = StrokeJoin.Miter))

    // Settled sand pile in the lower bulb (filled triangle) + thin falling stream.
    val sand = Path().apply {
        moveTo(cx - hw * 0.48f, cy + hh)
        lineTo(cx + hw * 0.48f, cy + hh)
        lineTo(cx, cy + hh * 0.45f)
        close()
    }
    drawPath(sand, color)
    drawLine(color, Offset(cx, cy + waist), Offset(cx, cy + hh * 0.4f), strokeWidth = stroke * 0.4f, cap = StrokeCap.Butt)
}

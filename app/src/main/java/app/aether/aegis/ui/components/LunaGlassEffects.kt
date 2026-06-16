package app.aether.aegis.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aether.aegis.ui.theme.AegisDataRain
import app.aether.aegis.ui.theme.AegisGlassBorder
import app.aether.aegis.ui.theme.AegisGlassFill
import app.aether.aegis.ui.theme.AegisHexGridStroke
import app.aether.aegis.ui.theme.AegisScanLine
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Frosted-glass panel surface.
 *
 * Recipe: 85% opaque dark fill + 12% cyan border + 12dp backdrop
 * blur. Modifier.blur in Compose blurs the receiver's own content, not
 * the backdrop, so on Android 12+ we get a soft inner haze (still
 * reads as "glass") and below 12 we get the flat dark + border
 * variant. True backdrop-blur would need a RenderEffect + offscreen
 * pass and is much heavier — this is the intended fallback.
 */
fun Modifier.lunaGlass(corner: Dp = 12.dp): Modifier = this
    .clip(RoundedCornerShape(corner))
    .background(AegisGlassFill)
    .border(1.dp, AegisGlassBorder, RoundedCornerShape(corner))

/**
 * Hex grid background tile. Faint flat-top hexagon
 * tessellation, drawn as strokes only (no fill), behind whatever the
 * caller stacks on top. Use as the root layer of a Scaffold.
 *
 * Hex radius defaults to 40dp. Stroke colour
 * [AegisHexGridStroke] is alpha-3% cyan, deliberately subliminal —
 * you should feel it more than you see it.
 */
@Composable
fun HexGridBackground(
    modifier: Modifier = Modifier,
    hexRadius: Dp = 40.dp,
    strokeColor: Color = AegisHexGridStroke,
) {
    // The grid is static — drawing it from a Canvas block re-runs that
    // block on every parent recomposition, which during scroll can
    // mean 60×/sec across 200+ hex cells. Bake the grid into an
    // ImageBitmap once per (size, hexRadius) and blit. Drops the
    // per-frame draw cost from "200 hex × 6 lines" to "one bitmap
    // copy" — the difference between buttery scroll and the jank
    // the user is seeing.
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val widthPx = with(density) { cfg.screenWidthDp.dp.toPx() }.toInt().coerceAtLeast(1)
    val heightPx = with(density) { cfg.screenHeightDp.dp.toPx() }.toInt().coerceAtLeast(1)
    val rPx = with(density) { hexRadius.toPx() }
    val bitmap = androidx.compose.runtime.remember(widthPx, heightPx, rPx, strokeColor) {
        renderHexGridBitmap(widthPx, heightPx, rPx, strokeColor)
    }
    androidx.compose.foundation.Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier.fillMaxSize(),
    )
}

/** One-shot raster of the hex-grid backdrop. Same geometry as the
 *  former Canvas implementation; just drawn into a Bitmap that the
 *  caller blits with zero per-frame cost. */
private fun renderHexGridBitmap(
    width: Int,
    height: Int,
    rPx: Float,
    strokeColor: Color,
): androidx.compose.ui.graphics.ImageBitmap {
    val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 0.8f
        color = strokeColor.let {
            android.graphics.Color.argb(
                (it.alpha * 255).toInt(),
                (it.red * 255).toInt(),
                (it.green * 255).toInt(),
                (it.blue * 255).toInt(),
            )
        }
    }
    val stepX = rPx * 1.5f
    val stepY = rPx * 1.7320508f  // √3
    val cols = (width / stepX).toInt() + 2
    val rows = (height / stepY).toInt() + 2
    val path = android.graphics.Path()
    for (col in 0..cols) {
        for (row in 0..rows) {
            val cx = col * stepX
            val cy = row * stepY + if (col % 2 == 1) stepY / 2f else 0f
            if (cx > width + rPx || cy > height + rPx) continue
            path.reset()
            for (i in 0 until 6) {
                val angle = (Math.PI / 3) * i
                val x = cx + rPx * kotlin.math.cos(angle).toFloat()
                val y = cy + rPx * kotlin.math.sin(angle).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, paint)
        }
    }
    return bmp.asImageBitmap()
}


/**
 * Scan line crawling top-to-bottom over the parent.
 *
 * Implementation note: a Canvas with an animated draw position
 * re-runs its draw block on every frame and invalidates the full
 * viewport — that's the scroll-jank trap. Instead we draw ONE line
 * into a fixed thin Box and animate its Y translation via
 * graphicsLayer. The compositor moves the layer; Compose doesn't
 * recompose anything. One drawLine total, 60fps motion, zero scroll
 * cost.
 */
@Composable
fun ScanLine(modifier: Modifier = Modifier) {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val heightPx = with(density) { cfg.screenHeightDp.dp.toPx() }
    // User-tunable backdrop intensity (Settings → LunaGlass effects).
    // 0..1 multiplier on the scan-line alpha so users can balance
    // visibility against distraction without flipping the toggle.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val effects = androidx.compose.runtime.remember { app.aether.aegis.ui.GraphicsPrefs(ctx) }
    val intensity by effects.effectsIntensityFlow.collectAsState()
    // 30 s sweep (was 10 s). The CRT-scan effect is supposed to feel
    // ambient, not constant motion — a longer sweep period halves
    // the per-second invalidation work that was competing with chat-
    // scroll frames on mid-range GPUs.
    val infinite = rememberInfiniteTransition(label = "scan")
    val y by infinite.animateFloat(
        initialValue = 0f,
        targetValue = heightPx,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30_000, easing = LinearEasing),
        ),
        label = "scan-y",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .graphicsLayer { translationY = y }
            .background(AegisScanLine.copy(alpha = AegisScanLine.alpha * intensity)),
    )
}

/**
 * Data rain — drifting tiny hex particles.
 *
 * Optional, low-priority polish. We
 * gate behind a settings toggle so battery-conscious users (Voyager-
 * style curve already applies on top) can disable it.
 *
 * ~20 particles, 4dp hexes, falling at ~20 px/s with slow spin.
 * Re-spawn at top once they leave the bottom edge.
 */
@Composable
fun DataRain(
    modifier: Modifier = Modifier,
    // Halved from 28 — each particle is 6 drawLine calls per frame
    // at ~60 fps, so 28 was ~10k drawLines/sec. 14 is plenty for the
    // ambient parallax effect and frees up GPU budget for scrolling.
    particleCount: Int = 14,
) {
    // Pre-roll random positions + speeds so the field looks stable on
    // first paint. Random seed fixed per-composition so we don't
    // re-randomise every recomposition.
    val particles = remember(particleCount) {
        val rng = Random(0xAE61570L)
        List(particleCount) {
            Particle(
                xFrac = rng.nextFloat(),
                yFrac = rng.nextFloat(),
                speedPxPerSec = 14f + rng.nextFloat() * 22f,
                spinDegPerSec = (rng.nextFloat() - 0.5f) * 30f,
                phaseOffset = rng.nextFloat(),
                // Size jitter so the field looks layered, not stamped.
                sizeJitter = 0.7f + rng.nextFloat() * 0.8f,
            )
        }
    }
    // User-tunable backdrop intensity multiplier. Same prefs source as
    // ScanLine; settings slider drives both.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val effects = remember { app.aether.aegis.ui.GraphicsPrefs(ctx) }
    val intensity by effects.effectsIntensityFlow.collectAsState()
    val infinite = rememberInfiniteTransition(label = "rain")
    val t by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
        ),
        label = "rain-t",
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        // Halved per user feedback — 5 dp hexes were too loud in the
        // background. 2.5 dp particles read as a quieter texture
        // without losing the parallax-depth effect.
        val baseR = 2.5.dp.toPx()
        val strokePx = 0.9.dp.toPx()
        for (p in particles) {
            // Time elapsed for this particle (looped phase).
            val elapsed = ((t + p.phaseOffset) % 1f) * 60f  // seconds
            val hexR = baseR * p.sizeJitter
            val x = p.xFrac * size.width
            val y0 = p.yFrac * size.height
            val y = (y0 + p.speedPxPerSec * elapsed) % (size.height + hexR * 2) - hexR
            val rot = (p.spinDegPerSec * elapsed) * (Math.PI / 180).toFloat()
            // Smaller hexes get slightly lower alpha — fakes depth so
            // the field reads as a parallax layer instead of stamped
            // confetti.
            val alphaMul = (0.6f + p.sizeJitter * 0.4f).coerceAtMost(1f)
            val color = AegisDataRain.copy(alpha = AegisDataRain.alpha * alphaMul * intensity)
            // Draw a small flat-top hex outline at (x,y) rotated by rot.
            var prev: Offset? = null
            var first: Offset? = null
            for (i in 0 until 6) {
                val angle = (Math.PI / 3) * i + rot
                val vx = x + hexR * cos(angle).toFloat()
                val vy = y + hexR * sin(angle).toFloat()
                val here = Offset(vx, vy)
                prev?.let { drawLine(color, it, here, strokePx) }
                if (first == null) first = here
                prev = here
            }
            val finalPrev = prev
            val finalFirst = first
            if (finalPrev != null && finalFirst != null) {
                drawLine(color, finalPrev, finalFirst, strokePx)
            }
        }
    }
}

private data class Particle(
    val xFrac: Float,
    val yFrac: Float,
    val speedPxPerSec: Float,
    val spinDegPerSec: Float,
    val phaseOffset: Float,
    val sizeJitter: Float,
)

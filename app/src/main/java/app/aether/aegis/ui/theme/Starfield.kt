package app.aether.aegis.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Cosmic starfield background. Previously a Compose `Canvas { ... }`
 * that re-issued ~250 drawCircle calls every time its layer was
 * invalidated — which is what scrolling a LazyColumn over it triggers,
 * tens of times per second, causing visible stutter and tearing on
 * settings.
 *
 * Now: render the starfield once into an ImageBitmap (keyed off the
 * screen size, regenerated only on configuration change), then blit
 * the bitmap. Compose treats the Image as a constant texture — zero
 * per-frame cost.
 */
@Composable
fun StarfieldBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val effectiveProfile by app.aether.aegis.ui.EffectiveGraphicsProfile.state.collectAsState()
    // PowerSaver short-circuits the entire backdrop stack: SOLID BLACK
    // background (OLED pixels off → free), no starfield bitmap, no
    // hex grid, no scan line, no data rain. Everything that costs
    // pixels we don't strictly need gets dropped.
    if (effectiveProfile == app.aether.aegis.ui.GraphicsProfile.PowerSaver) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            content()
        }
        return
    }

    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val w = with(density) { config.screenWidthDp.dp.toPx() }.roundToInt().coerceAtLeast(1)
    val h = with(density) { config.screenHeightDp.dp.toPx() }.roundToInt().coerceAtLeast(1)
    val bitmap: ImageBitmap = remember(w, h) { renderStarfield(w, h) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val effects = remember { app.aether.aegis.ui.GraphicsPrefs(ctx) }
    // Voyager gate overlays the user toggles — both must say yes.
    val voyagerOk = app.aether.aegis.ui.LocalGraphicsBackdrops.current
    // Observe StateFlows so Settings toggles take effect immediately.
    val userHexGrid by effects.hexGridFlow.collectAsState()
    val userScanLine by effects.scanLineFlow.collectAsState()
    val userDataRain by effects.dataRainFlow.collectAsState()
    // Hex grid is a one-shot baked bitmap — zero per-frame cost — so
    // Voyager-gating it was overzealous. Honour the user toggle only.
    // Animated effects (data rain, scan line) keep the battery gate.
    val showHexGrid = userHexGrid
    val showScanLine = userScanLine && voyagerOk
    val showDataRain = userDataRain && voyagerOk
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                // Force the starfield into its own hardware layer so
                // the GPU caches the upload + composites with a single
                // blit per frame instead of re-rasterising during
                // scroll. Same trick used for the hex grid.
                .graphicsLayer(),
            contentScale = ContentScale.Crop,
        )
        if (showHexGrid) {
            app.aether.aegis.ui.components.HexGridBackground()
        }
        // Data rain belongs to the BACKDROP layer (above starfield +
        // hex grid, below UI). Drawing it after content() put it on
        // top of every button and text label, which made the screen
        // look like it was raining on the user. ScanLine stays on
        // top — it's the CRT-scan effect that sweeps over everything
        // by design.
        if (showDataRain) {
            app.aether.aegis.ui.components.DataRain()
        }
        content()
        if (showScanLine) {
            app.aether.aegis.ui.components.ScanLine()
        }
        // Debug strip lives inline inside AegisHeader now — no floating
        // overlay here. See AegisHeader for the conditional render.
    }
}

/**
 * One-shot raster of the starfield. Same colours, alphas, and seeded
 * RNG as the original Compose-Canvas version so the visual is identical;
 * the change is only in render-frequency.
 */
private fun renderStarfield(width: Int, height: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val rand = Random(0xAE615L)

    // Soft scattered white stars
    repeat(STAR_COUNT) {
        val x = rand.nextFloat() * width
        val y = rand.nextFloat() * height
        val r = 0.6f + rand.nextFloat() * 1.4f
        val alpha = 0.04f + rand.nextFloat() * 0.14f
        paint.color = Color.White.copy(alpha = alpha).toArgb()
        canvas.drawCircle(x, y, r, paint)
    }
    // Warm amber + cool steel — real-cosmos speckle.
    repeat(COLOURED_STAR_COUNT) {
        val x = rand.nextFloat() * width
        val y = rand.nextFloat() * height
        val r = 1.2f + rand.nextFloat() * 1.6f
        val warm = rand.nextBoolean()
        val base = if (warm) Color(0xFFFFC864) else Color(0xFF6496FF)
        paint.color = base.copy(alpha = 0.10f + rand.nextFloat() * 0.08f).toArgb()
        canvas.drawCircle(x, y, r, paint)
    }
    // Soft top-down vignette so the bottom feels weighted under the navbar.
    val gradient = android.graphics.LinearGradient(
        0f, 0f, 0f, height.toFloat(),
        intArrayOf(0x00000000, 0x59000000),  // alpha 0 → 0.35
        floatArrayOf(0f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    val gradPaint = Paint().apply { shader = gradient }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradPaint)

    return bmp.asImageBitmap()
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).roundToInt(),
    (red * 255).roundToInt(),
    (green * 255).roundToInt(),
    (blue * 255).roundToInt(),
)

private const val STAR_COUNT = 220
private const val COLOURED_STAR_COUNT = 14

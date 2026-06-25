package app.aether.aegis.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import app.aether.aegis.AegisApp

/**
 * "Blood stays" battery-driven desaturation as a reusable Modifier.
 * Originally lived inline inside MainActivity.setContent; extracted here
 * so dialogs and other windowed surfaces (which compose OUTSIDE the
 * activity's renderEffect tree) can apply the same effect to their own
 * content.
 *
 * Reads the live battery level from PowerBudget. On API 33+ uses an
 * AGSL RuntimeShader (selective hue-band preservation — pure red stays at
 * full saturation, everything else fades to luminance). On API 29-32
 * falls back to a single-pass standard saturation matrix (no selective
 * red — requires per-pixel conditional which the matrix can't express).
 *
 * Returns [Modifier] (no-op) above 20 % battery so callers always get a
 * valid modifier they can plug into any surface without conditionals.
 */
@Composable
fun bloodStaysDesaturationModifier(): Modifier {
    val batteryLevel by AegisApp.instance.powerBudget.level.collectAsState()
    val saturation = saturationForBattery(batteryLevel)
    if (saturation >= 0.999f) return Modifier
    return if (Build.VERSION.SDK_INT >= 33) {
        // remember()-keyed on saturation so a fresh RenderEffect
        // materialises whenever the saturation changes — keeps the
        // GPU layer in sync with the shader uniform on every battery
        // tick or preview-toggle flip. Without the key, the layer
        // cached the old effect and ignored uniform updates.
        val shader = remember { android.graphics.RuntimeShader(BLOOD_STAYS_AGSL) }
        val effect = remember(saturation) {
            shader.setFloatUniform("saturation", saturation)
            android.graphics.RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
        Modifier.graphicsLayer(
            renderEffect = effect,
            compositingStrategy =
                androidx.compose.ui.graphics.CompositingStrategy.Offscreen,
        )
    } else {
        val matrix = ColorMatrix().also { it.setToSaturation(saturation) }
        val filter = ColorFilter.colorMatrix(matrix)
        val paint = Paint().also { it.colorFilter = filter }
        Modifier.drawWithContent {
            drawIntoCanvas { canvas ->
                val r = Rect(Offset.Zero, size)
                canvas.saveLayer(r, paint)
                drawContent()
                canvas.restore()
            }
        }
    }
}

/** AGSL shader for the API 33+ path. Per-pixel HSL hue check —
 *  any pixel whose hue lands in the red band (0-40 or 320-360) keeps its
 *  full saturation regardless of the global curve; everything else lerps
 *  toward luminance. Single GPU pass, no offscreen compounding, no blend
 *  math to misinterpret. */
private const val BLOOD_STAYS_AGSL = """
    uniform float saturation;
    uniform shader content;

    half4 main(float2 coord) {
        half4 c = content.eval(coord);

        float cmax = max(c.r, max(c.g, c.b));
        float cmin = min(c.r, min(c.g, c.b));
        float delta = cmax - cmin;

        float hue = 0.0;
        if (delta > 0.001) {
            if (cmax == c.r)      hue = mod((c.g - c.b) / delta, 6.0) * 60.0;
            else if (cmax == c.g) hue = ((c.b - c.r) / delta + 2.0) * 60.0;
            else                  hue = ((c.r - c.g) / delta + 4.0) * 60.0;
        }
        if (hue < 0.0) hue += 360.0;

        float isRed = smoothstep(40.0, 20.0, hue)
                    + smoothstep(320.0, 340.0, hue);
        isRed = clamp(isRed, 0.0, 1.0);

        float luma = dot(c.rgb, half3(0.213, 0.715, 0.072));
        half3 gray = half3(luma, luma, luma);

        float pixelSat = mix(saturation, 1.0, isRed);
        half3 result = mix(gray, c.rgb, pixelSat);

        return half4(result, c.a);
    }
"""

package app.aether.aegis.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.max
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.aether.aegis.ui.theme.AegisCyan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot

/**
 * Device-tilt source for the "real glass" sheen — the highlight that
 * slides across a glass surface as you move the phone, like light on a
 * frosted window.
 *
 * Reads the accelerometer (the gravity vector) and exposes a smoothed
 * tilt in [-1, 1] per axis. Reference-counted: the sensor is only
 * registered while at least one glass surface is on screen and the
 * effect is enabled, and torn down the moment the last one leaves — so a
 * disabled or off-screen effect costs ZERO battery. Sample rate is
 * SENSOR_DELAY_UI (~16 Hz), enough for a smooth highlight without
 * hammering the CPU.
 */
object TiltSensor {
    private val _tilt = MutableStateFlow(Offset.Zero)
    val tilt: StateFlow<Offset> = _tilt

    private var sm: SensorManager? = null
    private var sensor: Sensor? = null
    private var refs = 0

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            // values[0] = left/right, values[1] = up/down (screen plane),
            // range ±~9.8. Normalise and low-pass so the highlight glides
            // rather than jitters.
            val tx = (e.values[0] / 9.8f).coerceIn(-1f, 1f)
            val ty = (e.values[1] / 9.8f).coerceIn(-1f, 1f)
            val prev = _tilt.value
            // Smoothing factor: higher = snappier (less lag), lower = glassier
            // but laggy. 0.28 tracks the hand without jitter.
            val a = 0.28f
            val nx = prev.x + (tx - prev.x) * a
            val ny = prev.y + (ty - prev.y) * a
            // PERF deadband: every emit redraws EVERY on-screen glass surface
            // (sheen + edge-light). The accelerometer jitters and the
            // smoothing asymptotes, so without this the tilt kept emitting
            // sub-perceptible deltas — redrawing the whole chat at the sensor
            // rate even when the phone was sitting still. Skip emits below a
            // visually-negligible threshold so a near-still phone costs zero
            // GPU; real motion always clears it.
            if (kotlin.math.abs(nx - prev.x) < 0.0025f &&
                kotlin.math.abs(ny - prev.y) < 0.0025f
            ) return
            _tilt.value = Offset(nx, ny)
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    }

    @Synchronized
    fun acquire(context: Context) {
        if (refs++ == 0) {
            val mgr = context.applicationContext
                .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sm = mgr
            sensor = mgr?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            // UI (~16 Hz), not GAME (~50 Hz): the glass effects don't need
            // 50 Hz, and at GAME every sample redrew every on-screen glass
            // surface — 3× the redraw load for no visible gain. The deadband
            // above + 16 Hz keeps the tilt smooth while cutting GPU work.
            sensor?.let { mgr?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    @Synchronized
    fun release() {
        if (--refs <= 0) {
            refs = 0
            sm?.unregisterListener(listener)
            sm = null
            sensor = null
            _tilt.value = Offset.Zero
        }
    }
}

/**
 * Peak brightness of the moving metal hotspot (see [metalShine]). Metal is
 * specular — a sharp, near-white glint — so the travelling highlight peaks
 * bright. One knob for every metal surface (nav glyphs + tier medals); dial
 * it if the glint over/undershoots on device.
 */
const val METAL_SHEEN_INTENSITY = 10f

/**
 * Real-glass sheen: a soft highlight "hotspot" that slides across the
 * surface with device tilt, clipped to [shape]. Layered ON TOP of the
 * surface content (a glint), so the panel reads as actual glass catching
 * light rather than a flat fill.
 *
 * No-op (and no sensor cost) when [enabled] is false — the caller gates
 * it on the experimental toggle AND the rich-graphics preference, so
 * low-power devices and users who don't want it pay nothing.
 */
fun Modifier.glassSheen(
    shape: Shape,
    enabled: Boolean,
    // When true the glint is masked to the drawn CONTENT's own alpha
    // (BlendMode.SrcAtop in an offscreen layer) instead of filling the
    // whole [shape]. Use for ICON GLYPHS: a transparent-background icon
    // would otherwise show the translucent gradient as a grey box around
    // the glyph (the bottom-nav "grey box under the icon" report). Panels
    // and bubbles leave this false — there the full fill IS the glass.
    maskToContent: Boolean = false,
    // Brightness multiplier on the glint (per-alpha clamped to ≤1). 1× = the
    // soft bubble tuning; the Cyan CROWN passes a large value for the lush
    // full-reflection foil — the look that was "perfect" before. Metals use
    // metalShine instead, so this stays the glass/crown knob.
    intensity: Float = 1f,
): Modifier =
    if (!enabled) this else composed {
        val ctx = LocalContext.current
        DisposableEffect(Unit) {
            TiltSensor.acquire(ctx)
            onDispose { TiltSensor.release() }
        }
        val tiltState = TiltSensor.tilt.collectAsState()
        // ONE reflection for the whole screen, not a clone per block. The
        // light source lives in WINDOW (screen) space; each surface tracks its
        // own top-left in the window and draws only the slice of that single
        // soft gradient that falls on it — so a bubble top-left and one
        // bottom-right show DIFFERENT parts of the same reflection instead of
        // every block carrying an identical (fake-looking) hotspot.
        var originX by remember { mutableStateOf(0f) }
        var originY by remember { mutableStateOf(0f) }
        val cfg = LocalConfiguration.current
        val density = LocalDensity.current
        val screenWpx = with(density) { cfg.screenWidthDp.dp.toPx() }
        val screenHpx = with(density) { cfg.screenHeightDp.dp.toPx() }
        this
            // Offscreen layer so the SrcAtop blend below can mask the glint
            // to the glyph's own pixels (icon case) rather than the parent
            // canvas. No-op for the default full-fill (panel/bubble) path.
            .then(
                if (maskToContent) Modifier.graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                } else Modifier,
            )
            .onGloballyPositioned {
                val p = it.positionInWindow()
                originX = p.x
                originY = p.y
            }
            // PERF: the clip path depends only on size/shape, NOT tilt — build
            // it ONCE per size in drawWithCache instead of re-creating the
            // outline + Path every frame for every bubble (the sheen's main
            // per-frame cost; mirrors the edge-light's caching). Only the
            // brush, which tracks the light position, stays in the hot path.
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val clip = Path().apply { addOutline(outline) }
                val radius = max(screenWpx, screenHpx) * 0.55f
                onDrawWithContent {
                    drawContent()
                    val tilt = tiltState.value
                    // Global light position in screen space — a gentle drift,
                    // not an obvious central point. Lower amplitude so it
                    // doesn't slam to a corner.
                    val lx = screenWpx * (0.5f + (tilt.x * 0.9f).coerceIn(-0.5f, 0.5f))
                    val ly = screenHpx * (0.5f + (tilt.y * 0.9f).coerceIn(-0.5f, 0.5f))
                    val brush = Brush.radialGradient(
                        colors = listOf(
                            // Soft glass glint at 1× (bubbles, matte); a bright
                            // white→cyan reflection at high [intensity] (the Cyan
                            // crown's lush foil). Per-alpha clamped so a big
                            // multiplier can't overflow.
                            Color.White.copy(alpha = (0.13f * intensity).coerceAtMost(1f)),
                            AegisCyan.copy(alpha = (0.06f * intensity).coerceAtMost(1f)),
                            Color.Transparent,
                        ),
                        center = Offset(lx - originX, ly - originY),
                        radius = radius,
                    )
                    clipPath(clip) {
                        // SrcAtop when masking: paint the glint only where the
                        // content (glyph) is already opaque, so a transparent
                        // icon background stays transparent — no grey box.
                        drawRect(
                            brush = brush,
                            blendMode = if (maskToContent) BlendMode.SrcAtop
                                        else BlendMode.SrcOver,
                        )
                    }
                }
            }
    }

/**
 * Polished-metal shine for "medal" buttons — the tier hexes and the bottom-
 * nav glyphs. Two parts:
 *
 *  1. STATIC 3D bevel — a FIXED upper-left highlight + lower-right shadow
 *     that does NOT move with tilt, so the button reads as bolted to the
 *     background and raised/protruding (a stamped medal, not floating glass).
 *  2. A MOVING specular hotspot whose CENTRE tracks the tilt direction — the
 *     highlight comes from whichever way you tip the phone (up/down/left/
 *     right), like light catching real metal, not a fixed streak.
 *
 * This is the CHROME treatment — Bronze/Silver/Gold (the metals). The Cyan
 * CROWN uses [glassSheen] instead (its lush full white→cyan reflection foil),
 * so this modifier is pure chrome and carries no holographic mode.
 * [maskToContent] clips everything to the drawn glyph (icons) via SrcAtop;
 * otherwise it fills [shape] (the hex medal). [intensity] scales the chrome
 * glint's peak brightness. [shine] = false draws ONLY the bevel (SOS: matte,
 * but still raised). No-op + no sensor cost when [enabled] is false.
 */
fun Modifier.metalShine(
    shape: Shape,
    enabled: Boolean,
    maskToContent: Boolean = false,
    intensity: Float = METAL_SHEEN_INTENSITY,
    // When false, ONLY the static 3D bevel is drawn — no moving glint, no sheen.
    // For surfaces that must read as dead-serious: a shining emergency (SOS)
    // button is wrong, so SOS protrudes but never shines.
    shine: Boolean = true,
    // Peak alpha of the static bevel (the raised look). Drive it from the
    // SELECTED state: an active button protrudes strongly into the light (high
    // bevel), an inactive one sits nearly flat in shadow (low bevel).
    bevel: Float = 0.5f,
    // Specular hotspot radius as a fraction of the longest side. The default
    // 0.55 is a broad medal glow; small values (avatars pass ~0.30) give a
    // TIGHT, bright glint that reads as polished metal catching a point light
    // instead of a soft white wash ("shitty sheen").
    radiusFactor: Float = 0.55f,
): Modifier =
    if (!enabled) this else composed {
        val ctx = LocalContext.current
        DisposableEffect(Unit) {
            TiltSensor.acquire(ctx)
            onDispose { TiltSensor.release() }
        }
        val tiltState = TiltSensor.tilt.collectAsState()
        this
            // Offscreen layer so SrcAtop can mask the shading to the glyph's
            // own pixels (icon case). No-op for the full-fill medal path.
            .then(
                if (maskToContent) Modifier.graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                } else Modifier,
            )
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val clip = Path().apply { addOutline(outline) }
                val w = size.width
                val h = size.height
                val maxDim = max(w, h)
                // STATIC bevel for DEPTH (no tilt — never moves, so the button
                // reads as bolted down and raised, not floating). Light on the
                // upper-left, dark on the lower-right, the two ramps OVERLAPPING
                // across the middle so a CENTRED glyph is actually shaded. (The
                // previous version left the middle transparent, so thin nav
                // glyphs caught almost none of it — the "no protrusion" report.)
                val bevelA = bevel.coerceIn(0f, 1f)
                val bevelHi = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.White.copy(alpha = bevelA),
                        0.55f to Color.Transparent,
                    ),
                    start = Offset.Zero, end = Offset(w, h),
                )
                val bevelLo = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = bevelA),
                    ),
                    start = Offset.Zero, end = Offset(w, h),
                )
                onDrawWithContent {
                    drawContent()
                    val bm = if (maskToContent) BlendMode.SrcAtop else BlendMode.SrcOver
                    clipPath(clip) {
                        // 1. Static depth.
                        drawRect(brush = bevelHi, blendMode = bm)
                        drawRect(brush = bevelLo, blendMode = bm)
                        // 2. The MOVING highlight. Skipped when [shine] is false
                        // (SOS: matte, bevel only). if-block not early-return:
                        // clipPath is inline without a finally, so a non-local
                        // return would leak its canvas save/clip.
                        if (shine) {
                            val tilt = tiltState.value
                            // The hotspot CENTRE tracks the tilt DIRECTION, so the
                            // light comes from whichever way you tip the phone —
                            // up/down/left/right — not a fixed diagonal streak.
                            val cx = (0.5f + tilt.x * 0.85f).coerceIn(0f, 1f) * w
                            val cy = (0.5f + tilt.y * 0.85f).coerceIn(0f, 1f) * h
                            // Metal — a TIGHT, bright white specular blob: a
                            // localized glint that slides around the glyph as you
                            // tilt, not a full glow. (The Cyan crown's lush foil is
                            // glassSheen's job, not this; metalShine is chrome.)
                            val peak = (0.10f * intensity).coerceIn(0f, 1f)
                            val brush = Brush.radialGradient(
                                // Sharper falloff than a 3-stop soft glow: the
                                // glint stays bright to ~35 % of the radius then
                                // drops fast, so a small radiusFactor reads as a
                                // crisp specular streak, not a fuzzy cloud.
                                colorStops = arrayOf(
                                    0f to Color.White.copy(alpha = peak),
                                    0.35f to Color.White.copy(alpha = peak * 0.55f),
                                    0.7f to Color.White.copy(alpha = peak * 0.12f),
                                    1f to Color.Transparent,
                                ),
                                center = Offset(cx, cy),
                                radius = maxDim * radiusFactor,
                            )
                            drawRect(brush = brush, blendMode = bm)
                        }
                    }
                }
            }
    }

/** DIFFRACTION foil palette — a true embossed-hologram grating sweeps the
 *  FULL spectral wheel as the angle changes, so this rotates the whole hue
 *  circle: cyan → blue → violet → magenta → red → orange → yellow → green →
 *  (wrap). Saturated "rainbow holo". Anchored at brand cyan so flat = cyan. */
private val DIFFRACTION_PALETTE = listOf(
    Color(0xFF00FFFF),  // cyan (rest)
    Color(0xFF2E8BFF),  // blue
    Color(0xFF8A4DFF),  // violet
    Color(0xFFFF3DD0),  // magenta
    Color(0xFFFF5050),  // red
    Color(0xFFFF9A20),  // orange
    Color(0xFFFFE83D),  // yellow
    Color(0xFF45FF7A),  // green
)

/** THIN-FILM foil palette — soap-bubble / oil-slick iridescence (Newton's
 *  series). Subtractive, so it's magenta-forward and pastel, NOT a saturated
 *  spectral rainbow (pure red shows as magenta, etc.): cyan → mint → pale gold
 *  → pink → indigo → (wrap). Anchored at brand cyan so flat = cyan. */
private val THINFILM_PALETTE = listOf(
    Color(0xFF00FFFF),  // cyan (rest)
    Color(0xFF79FFC6),  // mint-green
    Color(0xFFFFE6A8),  // pale gold
    Color(0xFFFF7AD9),  // pink / magenta
    Color(0xFF8E8CFF),  // indigo / periwinkle
)

/** Continuous iridescent colour for phase [t] (wraps), interpolating
 *  [palette]. Drives the foil's GLOBAL hue: the whole surface is filled with a
 *  sample of this, and tilt moves [t] — so every pixel shifts hue together,
 *  with no spatial centre or band to read as an artefact. */
private fun iridescentFoil(t: Float, palette: List<Color>): Color {
    val n = palette.size
    val x = (((t % 1f) + 1f) % 1f) * n
    val i = x.toInt() % n
    val f = x - kotlin.math.floor(x)
    return androidx.compose.ui.graphics.lerp(palette[i], palette[(i + 1) % n], f)
}

/**
 * Holographic foil — the ALTERNATIVE Cyan-crown treatment to [glassSheen]'s
 * white→cyan glow. Tilt maps to a single point in the iridescent palette and
 * the WHOLE surface is filled with that hue, so the entire foil recolours at
 * once as you tip the phone — like a flat foil under a changing light. NO
 * spatial reference: not the sweep-gradient's pinwheel centre, not the linear
 * gradient's band/line — both read as artefacts moving across the surface.
 * Crown-only, behind a debug toggle so it can be compared with the glow.
 * [maskToContent] clips to the drawn glyph (icons) via SrcAtop; [intensity]
 * scales overall strength (so an inactive crown tab still fades back).
 */
fun Modifier.holoFoil(
    shape: Shape,
    enabled: Boolean,
    maskToContent: Boolean = false,
    intensity: Float = METAL_SHEEN_INTENSITY,
    // false → DIFFRACTION (saturated rainbow); true → THIN-FILM (pastel,
    // magenta-forward oil-slick). Both anchor at brand cyan when flat.
    thinFilm: Boolean = false,
): Modifier =
    if (!enabled) this else composed {
        val palette = if (thinFilm) THINFILM_PALETTE else DIFFRACTION_PALETTE
        val ctx = LocalContext.current
        DisposableEffect(Unit) {
            TiltSensor.acquire(ctx)
            onDispose { TiltSensor.release() }
        }
        val tiltState = TiltSensor.tilt.collectAsState()
        this
            .then(
                if (maskToContent) Modifier.graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                } else Modifier,
            )
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val clip = Path().apply { addOutline(outline) }
                val w = size.width
                val h = size.height
                onDrawWithContent {
                    drawContent()
                    val tilt = tiltState.value
                    val k = (intensity / METAL_SHEEN_INTENSITY).coerceIn(0f, 1f)
                    val a = 0.62f * k
                    // NO spatial centre and NO band/line. Tilt maps to a single
                    // point in the iridescent palette and the WHOLE surface is
                    // filled with that hue, so the entire foil recolours at once
                    // as you tip the phone — like a flat foil under a changing
                    // light. Only a whisper of gradient (two adjacent palette
                    // samples) gives it life without reading as a directional
                    // feature.
                    val phase = (tilt.x + tilt.y) * 0.5f
                    val cA = iridescentFoil(phase, palette).copy(alpha = a)
                    // Tiny offset so the whisper of gradient stays a whisper —
                    // at rest (phase 0) both ends are essentially brand cyan, so
                    // a flat phone reads as plain cyan; the iridescence only
                    // emerges as you tilt and [phase] walks the palette.
                    val cB = iridescentFoil(phase + 0.06f, palette).copy(alpha = a)
                    val foil = Brush.linearGradient(
                        colors = listOf(cA, cB),
                        start = Offset.Zero,
                        end = Offset(w, h),
                    )
                    clipPath(clip) {
                        drawRect(
                            brush = foil,
                            blendMode = if (maskToContent) BlendMode.SrcAtop
                                        else BlendMode.SrcOver,
                        )
                    }
                }
            }
    }

/**
 * The Cyan-CROWN shimmer for "earned" surfaces — top-bar text/icons, etc.
 * Applies the crown-holder's chosen style ([ExperimentalPrefs.crownStyle]:
 * glow / rainbow / oil-slick) ONLY when the user is at the Cyan tier AND glass
 * effects are on; a no-op otherwise. Metals do NOT shine the top bar — Cyan
 * only, per SPEC_SHINE_TIERS. [maskToContent] defaults true (masks the shimmer
 * to the glyph/text pixels). Honours the debug tier override for testing.
 */
@Composable
fun Modifier.crownShimmer(maskToContent: Boolean = true): Modifier {
    val ctx = LocalContext.current
    val prefs = remember(ctx) { app.aether.aegis.prefs.ExperimentalPrefs(ctx) }
    val glassOn by prefs.glassSheenFlow.collectAsState()
    val crownStyle by prefs.crownStyleFlow.collectAsState()
    val tierOverride by prefs.debugTierFlow.collectAsState()
    val tier = remember(ctx, tierOverride) {
        runCatching { app.aether.aegis.admin.ShieldTierEngine.currentTier(ctx) }
            .getOrDefault(app.aether.aegis.admin.ShieldTier.None)
    }
    val rich = app.aether.aegis.ui.LocalGraphicsRich.current
    if (!(glassOn && rich && tier == app.aether.aegis.admin.ShieldTier.Cyan)) return this
    val shape = androidx.compose.ui.graphics.RectangleShape
    return when (crownStyle) {
        1 -> this.holoFoil(shape, enabled = true, maskToContent = maskToContent)
        2 -> this.holoFoil(shape, enabled = true, maskToContent = maskToContent, thinFilm = true)
        else -> this.glassSheen(shape, enabled = true, maskToContent = maskToContent)
    }
}

/** AGSL for the mascot's cyan-zone foil: foil ONLY the cyan pixels (her hair/
 *  eyes/accents), leaving skin/clothes untouched — Pokémon-card-style zone
 *  foiling on a baked raster. "Cyan-ness" = how much green+blue exceed red,
 *  weighted by brightness, so dark or skin pixels score ~0. */
private const val CYAN_FOIL_AGSL = """
    uniform shader content;
    layout(color) uniform half4 foil;
    half4 main(float2 coord) {
        half4 c = content.eval(coord);
        half cyanness = clamp(min(c.g, c.b) - c.r, 0.0, 1.0);
        half bright = clamp(max(c.g, c.b), 0.0, 1.0);
        half k = cyanness * bright * 0.9;
        return half4(mix(c.rgb, foil.rgb, k), c.a);
    }
"""

/**
 * Cyan mascot shimmer — foils ONLY her cyan zones (hair/eyes/accents) via an
 * AGSL colour-key shader, so skin and clothes stay matte (the Pokémon-card
 * intent). Self-gated: a no-op unless the user is at the Cyan crown tier with
 * glass effects on, AND the device is Android 13+ (API 33, where AGSL runtime
 * shaders exist) — older devices just don't shimmer the mascot. The foil
 * colour follows the crown style: glow (cyan↔white on tilt), rainbow
 * (diffraction palette), or oil-slick (thin-film palette), tilt-driven.
 */
@Composable
fun Modifier.cyanZoneFoil(): Modifier {
    if (android.os.Build.VERSION.SDK_INT < 33) return this
    val ctx = LocalContext.current
    val prefs = remember(ctx) { app.aether.aegis.prefs.ExperimentalPrefs(ctx) }
    val glassOn by prefs.glassSheenFlow.collectAsState()
    val crownStyle by prefs.crownStyleFlow.collectAsState()
    val tierOverride by prefs.debugTierFlow.collectAsState()
    val tier = remember(ctx, tierOverride) {
        runCatching { app.aether.aegis.admin.ShieldTierEngine.currentTier(ctx) }
            .getOrDefault(app.aether.aegis.admin.ShieldTier.None)
    }
    val rich = app.aether.aegis.ui.LocalGraphicsRich.current
    if (!(glassOn && rich && tier == app.aether.aegis.admin.ShieldTier.Cyan)) return this
    DisposableEffect(Unit) {
        TiltSensor.acquire(ctx)
        onDispose { TiltSensor.release() }
    }
    val tilt by TiltSensor.tilt.collectAsState()
    val shader = remember { android.graphics.RuntimeShader(CYAN_FOIL_AGSL) }
    val phase = (tilt.x + tilt.y) * 0.5f
    val foilColor = when (crownStyle) {
        1 -> iridescentFoil(phase, DIFFRACTION_PALETTE)
        2 -> iridescentFoil(phase, THINFILM_PALETTE)
        else -> lerp(
            // Glow: a constant faint sheen on her cyan zones that flashes
            // brighter as you tilt. The 0.25 resting baseline is the fix for
            // "invisible" — the foil used to rest on AegisCyan (== her own hair
            // colour), so mix(cyan,cyan) was a no-op until a hard tilt. Boosted
            // tilt gain (1.5, was 0.5) so a normal hand-tilt reads, matching the
            // responsiveness of the bars' moving highlight.
            AegisCyan, Color.White,
            (0.25f + (kotlin.math.abs(tilt.x) + kotlin.math.abs(tilt.y)) * 1.5f)
                .coerceIn(0f, 1f),
        )
    }
    return this.graphicsLayer {
        shader.setColorUniform("foil", foilColor.toArgb())
        renderEffect = android.graphics.RenderEffect
            .createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    }
}

/**
 * Edge-light: a tilt-reactive rim around a glass bubble that mimics how a
 * cyan-illuminated glass slab catches and internally reflects light at its
 * edges. As the pane tilts toward grazing, the whole rim fattens and the
 * *leading* edge (in the tilt direction) blooms bright/opaque while the
 * trailing edge fades toward transparent — so one side reads thick and lit,
 * the other thin and dark, like light piped through the slab.
 *
 * This is the Fresnel grazing-angle reflection model — reflectivity climbs
 * toward 1 as the view approaches grazing — which is the same optical family
 * as total internal reflection escaping at a boundary, and the cheapest
 * faithful fake. NOT a ray-traced TIR simulation.
 *
 * Meant to REPLACE the static [Surface] border while active: the caller
 * passes its normal border [base] colour and the illumination [glow] (cyan),
 * and drops the Surface's own [androidx.compose.foundation.BorderStroke] so
 * the rim isn't drawn twice. At rest (zero tilt) it collapses back to a
 * near-uniform [base] rim, so a still screen looks exactly like the static
 * border. No-op (and no sensor cost) when [enabled] is false.
 *
 * Reads the same refcounted [TiltSensor] as [glassSheen]; both gate on the
 * experimental toggle AND rich-graphics, so a disabled effect costs nothing.
 */
fun Modifier.glassEdgeLight(
    shape: Shape,
    base: Color,
    glow: Color = AegisCyan,
    enabled: Boolean,
): Modifier =
    if (!enabled) this else composed {
        val ctx = LocalContext.current
        DisposableEffect(Unit) {
            TiltSensor.acquire(ctx)
            onDispose { TiltSensor.release() }
        }
        // Read tilt as a State and consume it ONLY inside the draw lambda, so
        // a 50 Hz tilt update invalidates DRAW, not composition.
        val tiltState = TiltSensor.tilt.collectAsState()
        // PERF: the rim geometry (segment endpoints + outward normals) depends
        // only on size/shape, not tilt — so walk the PathMeasure ONCE per size
        // via drawWithCache and keep just the cheap per-frame work (a dot
        // product + a drawLine per segment) in the hot path. Walking
        // PathMeasure every frame for every bubble was the edge-light's main
        // cost and made it crawl on dense chats.
        drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val pm = PathMeasure().apply { setPath(path, false) }
            val len = pm.length
            val step = 5.dp.toPx().coerceAtLeast(3f)
            // Flat arrays: [startX, startY, endX, endY, nx, ny] per segment.
            val starts = ArrayList<Offset>()
            val ends = ArrayList<Offset>()
            val nxs = ArrayList<Float>()
            val nys = ArrayList<Float>()
            var d = 0f
            var segStart = pm.getPosition(0f)
            while (d < len) {
                val nd = (d + step).coerceAtMost(len)
                val mid = (d + nd) * 0.5f
                val tan = pm.getTangent(mid)
                // Outward normal for a CLOCKWISE-wound outline (which
                // facetedBubbleShape always is) is simply the tangent rotated
                // −90°: (tan.y, −tan.x). We deliberately do NOT flip it "away
                // from the centre": that heuristic only holds for a convex
                // shape, and the speech TAIL is a protrusion whose edges sit
                // on the far side of the centroid — the flip reversed their
                // normals, so the tail lit at the OPPOSITE tilt from the body
                // (the "tail edge-light is wrong" report). No flip → the tail
                // tracks the body.
                val nx = tan.y
                val ny = -tan.x
                val nl = hypot(nx, ny).coerceAtLeast(1e-4f)
                val segEnd = pm.getPosition(nd)
                starts.add(segStart); ends.add(segEnd)
                nxs.add(nx / nl); nys.add(ny / nl)
                segStart = segEnd
                d = nd
            }
            val restA = 0.30f                 // uniform rim alpha at rest
            val rimW = 1.dp.toPx()            // UNIFORM rim width, every edge,
            // every tilt. The width must NOT track the light. Modulating it
            // per segment (fat where lit, thin where shadowed) made the
            // light-facing edge bulge while the others stayed thin ("panel
            // thickness uneven"), and — because each 5 dp butt segment was
            // then a different-width rectangle — stepped the rim into a
            // visible string of tiles down every edge ("edges visibly poorly
            // done"). With one width, abutting butt segments form a single
            // clean continuous hairline; the tilt now reads purely as
            // brightness, so the rim shimmers instead of swelling.
            onDrawWithContent {
                drawContent()
                val tilt = tiltState.value
                // Grazing strength: tilt magnitude in [0,1]. 0 → calm static
                // rim; 1 → full directional flare.
                val k = hypot(tilt.x, tilt.y).coerceIn(0f, 1f)
                // Light direction in SCREEN space. y negated so tilting the TOP
                // of the phone toward the eye lights the TOP edge.
                val dirx: Float
                val diry: Float
                if (k > 1e-3f) { dirx = tilt.x / k; diry = -tilt.y / k } else { dirx = 0f; diry = -1f }
                for (i in starts.indices) {
                    // Only edges whose outward normal faces the light catch it.
                    val lit = (nxs[i] * dirx + nys[i] * diry).coerceIn(0f, 1f)
                    val dirA = 0.07f + 0.88f * lit
                    val a = (restA + (dirA - restA) * k).coerceIn(0f, 1f)
                    // Brightness-only lighting — uniform width (see rimW).
                    val wseg = rimW
                    val col = lerp(base, glow, lit * k)
                    // Only a SUBTLE white glint at the very peak — the rim must
                    // read as cyan-lit glass, not a white border. The old full
                    // white bloom (lit*lit*k up to 1.0) washed the brightest
                    // edge to white; cap it so cyan stays dominant.
                    val hot = lerp(col, Color.White, lit * lit * k * 0.22f)
                    drawLine(
                        color = hot.copy(alpha = a),
                        start = starts[i],
                        end = ends[i],
                        strokeWidth = wseg,
                        // Butt, not Round: round caps on each 5 dp segment
                        // overlapped at the joints and brightened into a string
                        // of "LED dots". Butt segments abut cleanly.
                        cap = StrokeCap.Butt,
                    )
                }
            }
        }
    }

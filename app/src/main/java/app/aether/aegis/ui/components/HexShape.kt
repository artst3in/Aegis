package app.aether.aegis.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisCyanGlow
import app.aether.aegis.ui.theme.AegisHexGradCenter
import app.aether.aegis.ui.theme.AegisHexGradMid
import app.aether.aegis.ui.theme.AegisHexGradEdge

/**
 * Regular hexagon (FLAT-top). Backbone of
 * LunaGlass — avatars, nav buttons, the SOS button, anything that's
 * a "control".
 *
 * Edges are NOT stroked lines: each of the
 * 6 hex edges is drawn as a TRAPEZOID between the inner hex (radius
 * r-w/2) and the outer hex (r+w/2), with both trapezoid corners on
 * the radial line from centre to vertex. This produces clean 120°
 * miter joints automatically and zero round caps — geometrically
 * perfect.
 *
 * Visual layers (back-to-front):
 *  1. Optional halo (radial gradient out from centre) — animated alpha
 *     when [breathing] is set.
 *  2. Optional gradient fill inside polygon — bright cyan core, dark
 *     rim, gives depth.
 *  3. Six trapezoid edges. When [edgeLighting] is true, per-edge alpha
 *     varies for a top-left light source (upper-left brightest,
 *     lower-right dimmest).
 *  4. Centered content (avatar initial, icon glyph, etc.).
 */
@Composable
fun HexShape(
    size: Dp,
    modifier: Modifier = Modifier,
    borderColor: Color = AegisCyan,
    borderWidth: Dp = 1.5.dp,
    fillColor: Color = Color.Transparent,
    /** Custom interior fill brush — takes precedence over [fillColor] and
     *  [gradientFill] when set. Used by the tier-medal avatar to paint a
     *  darkened metallic plate of the frame colour behind the initial (the
     *  "engraved medal" look) instead of the old cyan radial gradient. */
    fillBrush: Brush? = null,
    gradientFill: Boolean = false,
    edgeLighting: Boolean = false,
    glow: Boolean = false,
    glowColor: Color = AegisCyanGlow,
    breathing: Boolean = false,
    /** Tilt-reactive metal shimmer across the hex — for tier "medal"
     *  frames so an earned tier shines like metal as you move the phone.
     *  Caller gates it on the LunaGlass sheen toggle; here it's also gated
     *  on rich graphics. Clipped to the flat-top hex so the glint follows
     *  the frame. */
    medalSheen: Boolean = false,
    /** When in (0,1), the medal sheen is clipped to a hex *ring* (frame band)
     *  instead of the whole hex: the inner hole has this fraction of the outer
     *  radius, so the glint paints only the frame between the hole and the
     *  edge. Used by avatars — the medal-coloured frame should shimmer, but the
     *  photo/initial inside it must NOT be washed by the highlight (user
     *  report: "I can see the light on my contact avatar"). Pass the photo's
     *  size/hex-size ratio so the hole exactly matches the inset photo. 0 (the
     *  default) keeps the original full-hex shine for tier medals / nav / map
     *  glyphs, where the whole face is the medal. */
    sheenInnerFraction: Float = 0f,
    /** Earned-tier MEDAL frame: a thick, statically-beveled band in the tier
     *  colour — the canonical avatar-frame look (Comms, Radar, System, grid).
     *  Unlike [medalSheen] (the opt-in, tilt-reactive *animated* shimmer that
     *  needs the sensor + rich graphics), this is the always-on STATIC metal
     *  treatment: a wider band + top-left edge-lighting bevel, no sensor, no
     *  per-frame recomposition. So a medal reads as metal even with the sheen
     *  toggle off and on every surface — fixing "frames are thin / only the
     *  chat list looks metal". The animated shimmer still layers on top when
     *  [medalSheen] is also set. */
    medalFrame: Boolean = false,
    /** Peak brightness of the medal shine (metalShine / glassSheen / holoFoil).
     *  Default is the full medal/nav strength; avatars pass a softer value so
     *  the moving glint shines the frame + plate without blowing out an
     *  engraved monogram underneath. */
    medalIntensity: Float = METAL_SHEEN_INTENSITY,
    /** Specular hotspot radius (fraction of the hex's longest side) for the
     *  metal-tier glint. Avatars pass a small value for a TIGHT polished-metal
     *  glint instead of the broad medal glow. Only affects the chrome
     *  (metalShine) path; the Cyan-crown foil keeps its own lush reflection. */
    medalSpecularRadiusFactor: Float = 0.55f,
    /** Crown shimmer style to render the Cyan medal in (0 = glow, 1 = rainbow,
     *  2 = oil-slick). Null (the default) reads the LOCAL user's own pref —
     *  correct for the user's own chrome (nav, their own avatar). A PEER's
     *  avatar passes the peer's announced style (their `peerReportedCrownStyle`,
     *  or 0 when they've announced none) so their medal shines in the style THEY
     *  chose, not the viewer's. Ignored for non-Cyan frames. */
    crownStyleOverride: Int? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val richEnabled = app.aether.aegis.ui.LocalGraphicsRich.current
    val gradFill = gradientFill && richEnabled
    val edgeLit = edgeLighting && richEnabled
    val breathAnim = breathing && richEnabled
    val breathAlpha = if (breathAnim) breathingAlpha() else 1f
    // A medal's shine depends on its frame colour (medal frames set
    // borderColor = tier.color(), so the colour IS the tier signal):
    //   Cyan crown          → the lush full sheen (glassSheen), or the
    //                         iridescent holoFoil when the debug A/B toggle is on.
    //   Bronze/Silver/Gold  → chrome (metalShine: bevel + glint).
    //   anything else (e.g. the Untrusted dim frame) → the full sheen too, the
    //                         way it looked before the metal split.
    val ctx = LocalContext.current
    val localCrownStyle by remember(ctx) {
        app.aether.aegis.prefs.ExperimentalPrefs(ctx).crownStyleFlow
    }.collectAsState(initial = 0)
    // Peer avatars override with the peer's announced style; everything else
    // (the user's own chrome) follows the local pref.
    val crownStyle = crownStyleOverride ?: localCrownStyle
    val medalEnabled = medalSheen && richEnabled
    val isMetalTier = borderColor == app.aether.aegis.ui.theme.AegisShieldBronze ||
        borderColor == app.aether.aegis.ui.theme.AegisShieldSilver ||
        borderColor == app.aether.aegis.ui.theme.AegisGold
    // Sheen clip region: the full hex face by default (tier medals / nav /
    // map), or a frame-only RING when the caller sets sheenInnerFraction —
    // so an avatar's medal frame shimmers while the photo inside stays matte.
    val sheenShape: Shape =
        if (sheenInnerFraction > 0f && sheenInnerFraction < 1f) hexRingShape(sheenInnerFraction)
        else HexagonShape
    val medalShine: Modifier = when {
        borderColor == AegisCyan && crownStyle == 1 ->
            Modifier.holoFoil(shape = sheenShape, enabled = medalEnabled, intensity = medalIntensity, thinFilm = false)
        borderColor == AegisCyan && crownStyle == 2 ->
            Modifier.holoFoil(shape = sheenShape, enabled = medalEnabled, intensity = medalIntensity, thinFilm = true)
        borderColor == AegisCyan ->
            Modifier.glassSheen(shape = sheenShape, enabled = medalEnabled, intensity = medalIntensity)
        isMetalTier ->
            Modifier.metalShine(
                shape = sheenShape, enabled = medalEnabled,
                intensity = medalIntensity, radiusFactor = medalSpecularRadiusFactor,
            )
        else ->
            Modifier.glassSheen(shape = sheenShape, enabled = medalEnabled, intensity = medalIntensity)
    }
    Box(
        modifier = modifier
            .size(size)
            .then(medalShine)
            .let {
                if (onClick != null) it.clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                } else it
            },
        contentAlignment = Alignment.Center,
    ) {
        if (glow) {
            Canvas(modifier = Modifier.size(size)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = glowColor.alpha * breathAlpha),
                            Color.Transparent,
                        ),
                        center = Offset(this.size.width / 2, this.size.height / 2),
                        radius = this.size.width * 0.75f,
                    ),
                    radius = this.size.width * 0.6f,
                    center = Offset(this.size.width / 2, this.size.height / 2),
                )
            }
        }
        Canvas(modifier = Modifier.size(size)) {
            val s = this.size
            val polyPath = cachedHexPolyPath(s)
            when {
                // Custom fill brush (the tier-medal metal plate) wins — see
                // [fillBrush]. Drawn unconditionally (not gated on rich
                // graphics): it's a cheap two-stop gradient, not an effect.
                fillBrush != null -> {
                    drawPath(polyPath, brush = fillBrush)
                }
                gradFill -> {
                    drawPath(
                        path = polyPath,
                        brush = Brush.radialGradient(
                            colors = listOf(AegisHexGradCenter, AegisHexGradMid, AegisHexGradEdge),
                            center = Offset(s.width / 2, s.height / 2),
                            radius = s.width / 2,
                        ),
                    )
                }
                fillColor != Color.Transparent -> {
                    drawPath(polyPath, color = fillColor)
                }
            }
            // Trapezoid edges. Per-edge alpha tints the
            // top-left lighter than the bottom-right when edgeLit.
            // Edges are computed once per (size, borderWidth) and
            // shared across every HexShape of that geometry — see
            // hexEdgesCache.
            // Medal frames (Bronze/Silver/Gold metal + the Cyan crown) get a
            // thicker band so the metal bevel / holo-foil / glass-sheen
            // material actually reads — a 1.5dp hairline is too thin to show
            // the shine. Gated to the medal context (medalSheen + a tier
            // colour) so ordinary cyan hexes (buttons, FAB, search) stay thin.
            // A medal frame is thick + beveled whether the thickness comes from
            // the always-on static treatment ([medalFrame]) or the opt-in
            // animated shimmer ([medalSheen] on a tier colour). Either way the
            // band widens so the metal material reads — a 1.5dp hairline is too
            // thin to show it.
            val isMedalFrame = medalFrame ||
                (medalSheen && (isMetalTier || borderColor == AegisCyan))
            val wPx = (if (isMedalFrame) borderWidth * 1.9f else borderWidth).toPx()
            val edgePaths = cachedHexEdgePaths(s, wPx)
            if (isMedalFrame) {
                // Frame MOLDING (CSS "ridge" profile): shade each band ACROSS
                // its width, not as one flat facet. The OUTER edge is an outset
                // bevel (lit top-left, raised); the INNER edge is an INSET bevel
                // dropping to the recessed picture — the reverse (lit
                // bottom-right). A cross-section gradient per facet is what reads
                // as a raised frame around a sunken plate; a flat-shaded facet
                // reads as one face of a convex gem. inFrac maps the outer
                // vertices in to the band's inner radius (no angle-wrap math).
                val verts = hexVertices(s)
                val cx = s.width / 2f
                val cy = s.height / 2f
                val inFrac = ((s.width / 2f - wPx) / (s.width / 2f)).coerceIn(0f, 1f)
                for (i in 0 until 6) {
                    val (va, vb) = EDGES_CCW_FROM_TOP[i]
                    val outerMid = Offset((verts[va].x + verts[vb].x) / 2f, (verts[va].y + verts[vb].y) / 2f)
                    val innerMid = Offset(
                        cx + ((verts[va].x + verts[vb].x) / 2f - cx) * inFrac,
                        cy + ((verts[va].y + verts[vb].y) / 2f - cy) * inFrac,
                    )
                    val of = EDGE_LIGHTING_ALPHA[i]
                    drawPath(
                        path = edgePaths[i],
                        brush = Brush.linearGradient(
                            colors = listOf(
                                bevelShade(borderColor, of, BEVEL_STRENGTH),          // outer: outset
                                bevelShade(borderColor, 2f - of, BEVEL_STRENGTH),     // inner: inset (reversed)
                            ),
                            start = outerMid,
                            end = innerMid,
                        ),
                    )
                }
                // INNER WALL — the visible step-down face between the raised rim
                // and the lower plate (the primary depth cue of a real picture
                // frame, present even in a flat photo). A thin band just inside
                // the rim, lit INSET: the near walls (top / top-left / upper-
                // right) sit in shadow, the far walls (bottom / bottom-right)
                // catch the light. The LIGHTING REVERSAL at the rim→wall
                // boundary is what the brain reads as "the surface drops here" —
                // unambiguous in a way a smooth gradient can never be.
                val rimHalf = wPx / 2f
                val wallOuter = (s.width / 2f) - rimHalf            // = rim's inner edge
                val wallW = (s.width * 0.024f).coerceIn(1.5.dp.toPx(), 3.5.dp.toPx())
                val wallInner = (wallOuter - wallW).coerceAtLeast(0f)
                val rr = s.width / 2f
                for (i in 0 until 6) {
                    val (va, vb) = EDGES_CCW_FROM_TOP[i]
                    val dax = (verts[va].x - cx) / rr; val day = (verts[va].y - cy) / rr
                    val dbx = (verts[vb].x - cx) / rr; val dby = (verts[vb].y - cy) / rr
                    val wall = Path().apply {
                        moveTo(cx + dax * wallOuter, cy + day * wallOuter)
                        lineTo(cx + dbx * wallOuter, cy + dby * wallOuter)
                        lineTo(cx + dbx * wallInner, cy + dby * wallInner)
                        lineTo(cx + dax * wallInner, cy + day * wallInner)
                        close()
                    }
                    drawPath(wall, color = innerWallColor(borderColor, i))
                }
            } else {
                for (i in 0 until 6) {
                    val color = if (edgeLit) borderColor.copy(
                        alpha = (borderColor.alpha * EDGE_LIGHTING_ALPHA[i]).coerceIn(0f, 1f),
                    ) else borderColor
                    drawPath(path = edgePaths[i], color = color)
                }
            }
        }
        // Strip Android's legacy font-metric padding from any Text the
        // caller puts inside the hex AND collapse the line box to the
        // glyph height. Without lineHeight set, LineHeightStyle is a
        // no-op (the docs say it "applies when lineHeight is set"),
        // and the line box keeps the typeface's leading — which is
        // what was still pushing emojis a pixel off centre.
        val base = LocalTextStyle.current
        CompositionLocalProvider(
            LocalTextStyle provides base.copy(
                textAlign = TextAlign.Center,
                lineHeight = if (base.fontSize.isSp) base.fontSize else 14.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        ) {
            content()
        }
    }
}

/**
 * Double-ring hex avatar.
 *
 * Inner hex holds the avatar / initial. A configurable [gap] surrounds
 * it, then an outer ring. The ring breathes cyan when [online] is true
 * (3s, calm); when [sos] is true it pulses red fast (1s, urgent).
 *
 * Falls back to a single HexShape when neither online nor SOS is
 * set, so the ring isn't drawn for offline contacts.
 */
@Composable
fun DoubleRingHex(
    size: Dp,
    modifier: Modifier = Modifier,
    gap: Dp = 3.dp,
    innerBorder: Color = AegisCyan,
    innerFill: Color = Color.Transparent,
    online: Boolean = false,
    sos: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    if (!online && !sos) {
        HexShape(
            size = size,
            modifier = modifier,
            borderColor = innerBorder,
            fillColor = innerFill,
            onClick = onClick,
            content = content,
        )
        return
    }
    val outerSize = size
    val innerSize = size - gap * 2
    val ringColor = if (sos) app.aether.aegis.ui.theme.AegisSOS else AegisCyan
    val ringAlpha = if (sos) {
        breathingAlpha(periodMs = 1000, low = 0.4f, high = 1f)
    } else {
        breathingAlpha(periodMs = 3000, low = 0.4f, high = 0.8f)
    }
    Box(
        modifier = modifier.size(outerSize),
        contentAlignment = Alignment.Center,
    ) {
        HexShape(
            size = outerSize,
            borderColor = ringColor.copy(alpha = ringAlpha),
            borderWidth = 1.dp,
            glow = sos,
            glowColor = ringColor.copy(alpha = 0.35f * ringAlpha),
        )
        HexShape(
            size = innerSize,
            borderColor = innerBorder,
            fillColor = innerFill,
            onClick = onClick,
            content = content,
        )
    }
}

@Composable
fun breathingAlpha(
    periodMs: Int = 3000,
    low: Float = 0.4f,
    high: Float = 0.8f,
): Float {
    val infinite = rememberInfiniteTransition(label = "breath")
    val alpha by infinite.animateFloat(
        initialValue = low,
        targetValue = high,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs / 2),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath-alpha",
    )
    return alpha
}

/**
 * LunaGlass icon-to-hex size ratio: 1/φ ≈ 0.618 (golden ratio
 * reciprocal). Use [hexInnerIcon] to size an [AegisIcon] placed
 * inside a [HexShape] of a given outer size; the result keeps the
 * icon comfortably inside the hex's inscribed circle with
 * golden-ratio breathing room on every side.
 *
 * Geometric note: a flat-top hex of width W has an inscribed circle
 * of diameter W·√3/2 ≈ 0.866 W, and a square inscribed inside that
 * circle has side W·√6/4 ≈ 0.612 W. The golden-ratio choice 0.618
 * is within 1 % of the maximal inscribed-square fit and reads more
 * harmoniously than the geometric maximum.
 *
 * Worked examples — caller sizes hex, helper sizes the icon:
 *   28 dp hex → 17 dp icon
 *   36 dp hex → 22 dp icon
 *   44 dp hex → 27 dp icon
 *   60 dp hex → 37 dp icon
 *
 * (Sizing convention shared with the LunaGlass icon reference.)
 */
/** How hard the medal-frame facets lighten/darken for the raised-rim bevel.
 *  The rim height read correct here; the convex-dome impression came NOT from
 *  this bevel but from the rim never casting a shadow onto the plate (a
 *  directionally-lit hex with no cast shadow is just a dome). The cast shadow
 *  is drawn separately on the plate; this keeps the rim's chunky 3D edge. */
private const val BEVEL_STRENGTH: Float = 0.32f

/** Inner-wall facet colour — the step-down face of a picture frame, lit INSET
 *  (reversed from the rim's outset). Near walls (top / top-left / upper-right)
 *  fall in shadow; far walls (bottom / bottom-right) catch the upper-left
 *  light; the sides are mid. Same metal as the frame, just at a different angle
 *  to the light. Index matches EDGES_CCW_FROM_TOP. */
private fun innerWallColor(fc: Color, edgeIndex: Int): Color = when (edgeIndex) {
    0, 1 -> lerp(fc, Color.Black, 0.70f)   // top, upper-left — darkest near wall
    5    -> lerp(fc, Color.Black, 0.42f)   // upper-right — medium-dark
    2    -> lerp(fc, Color.Black, 0.12f)   // lower-left — medium
    3, 4 -> lerp(fc, Color.White, 0.35f)   // bottom, lower-right — lit far wall
    else -> fc
}

/** Shade a bevel facet from its base metal colour by a lighting factor [f]
 *  (EDGE_LIGHTING_ALPHA, 0.80‥1.20 around 1.0): >1 lerps toward white (facing
 *  the light), <1 toward black (facing away), scaled by [strength]. */
private fun bevelShade(base: Color, f: Float, strength: Float): Color =
    if (f >= 1f) lerp(base, Color.White, (((f - 1f) / 0.20f) * strength).coerceIn(0f, 1f))
    else lerp(base, Color.Black, (((1f - f) / 0.20f) * strength).coerceIn(0f, 1f))

const val LUNAGLASS_ICON_HEX_RATIO: Float = 0.618f

fun hexInnerIcon(hexSize: Dp): Dp = hexSize * LUNAGLASS_ICON_HEX_RATIO

/** Flat-top hex polygon path. Vertices are indexed 0=right, 1=lower-
 *  right, 2=lower-left, 3=left, 4=upper-left, 5=upper-right
 *  (matches the LunaGlass icon reference). */
private fun hexPath(s: Size): Path = Path().apply {
    val verts = hexVertices(s)
    moveTo(verts[0].x, verts[0].y)
    for (i in 1 until 6) lineTo(verts[i].x, verts[i].y)
    close()
}

/** Process-wide cache of computed hex Path objects keyed by (size,
 *  borderWidth). Without this, every HexShape redraw allocates seven
 *  Path objects (the polygon + six trapezoid edges) and fills them
 *  with new line segments — for a chat list with ~10 visible 44 dp
 *  avatars that's 70 path constructions per scroll-induced redraw
 *  per frame, which dominates the scroll-cost profile. Path content
 *  depends only on geometry (Size + edgeWidth), so sharing a single
 *  instance across every row of the same size is safe — drawPath
 *  just reads the path. Compose's per-row remember{} doesn't help
 *  for items disposed + recreated at the LazyColumn edges; a global
 *  cache does. */
private data class HexEdgeKey(val width: Float, val height: Float, val edge: Float)
private val hexPolyCache = java.util.concurrent.ConcurrentHashMap<Size, Path>()
private val hexEdgesCache = java.util.concurrent.ConcurrentHashMap<HexEdgeKey, Array<Path>>()

internal fun cachedHexPolyPath(s: Size): Path =
    hexPolyCache.getOrPut(s) { hexPath(s) }

internal fun cachedHexEdgePaths(s: Size, edgeWidthPx: Float): Array<Path> {
    val key = HexEdgeKey(s.width, s.height, edgeWidthPx)
    return hexEdgesCache.getOrPut(key) {
        Array(6) { i ->
            val (ai, bi) = EDGES_CCW_FROM_TOP[i]
            hexEdgeTrapezoid(s, ai, bi, edgeWidthPx)
        }
    }
}

internal fun hexVertices(s: Size): Array<Offset> {
    val cx = s.width / 2f
    val cy = s.height / 2f
    val r = s.width / 2f
    return Array(6) { i ->
        val angle = (Math.PI / 3) * i  // 0° = right vertex → flat top
        Offset(cx + r * cos(angle).toFloat(), cy + r * sin(angle).toFloat())
    }
}

/**
 * Trapezoid for one hex edge between vertex indices [vertA] and [vertB].
 * Inner side sits on r - w/2, outer side on r + w/2; both corners on
 * the radial line from centre to each vertex. Yields automatic 120°
 * miter joints at shared vertices when adjacent edges are drawn.
 *
 * Returns a closed Path: outerA → outerB → innerB → innerA → close.
 */
internal fun hexEdgeTrapezoid(
    s: Size,
    vertA: Int,
    vertB: Int,
    edgeWidth: Float,
): Path {
    val cx = s.width / 2f
    val cy = s.height / 2f
    val r = s.width / 2f
    val rOut = r + edgeWidth / 2f
    val rIn = (r - edgeWidth / 2f).coerceAtLeast(0f)
    val angA = (Math.PI / 3) * vertA
    val angB = (Math.PI / 3) * vertB
    val aOut = Offset(cx + rOut * cos(angA).toFloat(), cy + rOut * sin(angA).toFloat())
    val bOut = Offset(cx + rOut * cos(angB).toFloat(), cy + rOut * sin(angB).toFloat())
    val bIn = Offset(cx + rIn * cos(angB).toFloat(), cy + rIn * sin(angB).toFloat())
    val aIn = Offset(cx + rIn * cos(angA).toFloat(), cy + rIn * sin(angA).toFloat())
    return Path().apply {
        moveTo(aOut.x, aOut.y)
        lineTo(bOut.x, bOut.y)
        lineTo(bIn.x, bIn.y)
        lineTo(aIn.x, aIn.y)
        close()
    }
}

/**
 * CCW edge order starting from the top edge — the canonical iteration
 * for Edge Heat animation.
 *   0: top         (verts 4 → 5, upper-left → upper-right)
 *   1: upper-left  (verts 3 → 4)
 *   2: lower-left  (verts 2 → 3)
 *   3: bottom      (verts 1 → 2)
 *   4: lower-right (verts 0 → 1)
 *   5: upper-right (verts 5 → 0)
 */
internal val EDGES_CCW_FROM_TOP: Array<Pair<Int, Int>> = arrayOf(
    4 to 5,
    3 to 4,
    2 to 3,
    1 to 2,
    0 to 1,
    5 to 0,
)

/** Per-edge alpha modulation for top-left light source. Index matches
 *  [EDGES_CCW_FROM_TOP]: top → upper-left → lower-left → bottom →
 *  lower-right → upper-right. */
internal val EDGE_LIGHTING_ALPHA: FloatArray = floatArrayOf(
    1.05f,  // top
    1.20f,  // upper-left  (brightest — light source corner)
    1.10f,  // lower-left
    0.95f,  // bottom
    0.80f,  // lower-right (dimmest — opposite the light)
    0.90f,  // upper-right
)

/**
 * Compose [Shape] for clipping arbitrary content to a flat-top
 * hexagon — used when we want e.g. an image cropped into a
 * hex-shaped avatar.
 */
val HexagonShape = GenericShape { s, _ ->
    val cx = s.width / 2f
    val cy = s.height / 2f
    val r = s.width / 2f
    for (i in 0 until 6) {
        val angle = (Math.PI / 3) * i
        val x = cx + r * cos(angle).toFloat()
        val y = cy + r * sin(angle).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * Flat-top hexagonal RING (annulus) shape: the outer hex with a concentric
 * inner hex of radius [innerFraction]·r punched out, so the filled region is
 * just the frame band between them. Used to clip the avatar medal sheen to the
 * frame so the glint never washes the photo inside (see [HexShape]'s
 * `sheenInnerFraction`).
 *
 * The hole is produced WITHOUT relying on an even-odd fill rule: the inner hex
 * is wound in the OPPOSITE direction to the outer one, so the default non-zero
 * rule (which is what the sheen modifiers' `clipPath` uses) already subtracts
 * it. [innerFraction] is clamped to (0,1); callers pass the inset photo's
 * size-to-hex ratio so the hole matches the photo exactly.
 */
fun hexRingShape(innerFraction: Float): Shape = GenericShape { s, _ ->
    val cx = s.width / 2f
    val cy = s.height / 2f
    val r = s.width / 2f
    val ri = r * innerFraction.coerceIn(0.01f, 0.99f)
    // Outer hex — forward winding (0 → 5).
    for (i in 0 until 6) {
        val angle = (Math.PI / 3) * i
        val x = cx + r * cos(angle).toFloat()
        val y = cy + r * sin(angle).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
    // Inner hex — REVERSE winding (5 → 0). Opposite direction means the
    // non-zero rule treats it as a hole rather than a second filled hex.
    for (i in 5 downTo 0) {
        val angle = (Math.PI / 3) * i
        val x = cx + ri * cos(angle).toFloat()
        val y = cy + ri * sin(angle).toFloat()
        if (i == 5) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

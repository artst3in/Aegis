package app.aether.aegis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.aegis.ui.GraphicsPrefs
import app.aether.aegis.ui.theme.AegisCyan
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Master gate for the Cyan mascot.
 *
 * Two locks must be open for Cyan to appear anywhere:
 *
 *   1. `CyanGate.enabled` — compile-time switch flipped in source.
 *   2. Drawable existence — `cyan_*.webp` files present in
 *      `res/drawable/`. The lookup is `Resources.getIdentifier`
 *      at runtime; missing resources resolve to id 0 and fall
 *      through to the legacy path.
 *
 * Default-off so the integration scaffolding can ship without
 * art assets and without changing what the user sees today.
 * When the assets land and we want Cyan live, change
 * [enabled] to `true` and the eight WebP files referenced in
 * [CyanAsset] simultaneously light up across splash, empty
 * states, onboarding, About, and tier-up.
 *
 * Sites where Cyan deliberately does NOT appear (sos, lock,
 * duress, active chats, radar with contacts) ARE NOT wired to
 * this gate. Flipping [enabled]
 * cannot cause her to leak into a crisis surface.
 */
object CyanGate {
    /** Master switch. Flipped on in 2026.06.291 when the full
     *  8-slot asset set landed. Some slots are placeholders
     *  to be replaced later (the celebrate + onboard_3/4 mappings
     *  approximate the intended poses rather than nail
     *  them) — that's intentional, the "use whatever we have"
     *  call. Flip back to false to A/B test the legacy fall-
     *  through path if a regression shows up. */
    const val enabled = true
}

/**
 * Named WebP slots from the asset checklist. The string
 * is the drawable base-name (no extension) — both `cyan_splash.png`
 * and `cyan_splash.webp` resolve to the same [Resources
 * .getIdentifier] result, so either format can be dropped in
 * without code changes.
 */
enum class CyanAsset(val resName: String) {
    Splash("cyan_splash"),
    Sitting("cyan_sitting"),
    Celebrate("cyan_celebrate"),
    Headshot("cyan_headshot"),
    Onboard1("cyan_onboard_1"),
    Onboard2("cyan_onboard_2"),
    Onboard3("cyan_onboard_3"),
    Onboard4("cyan_onboard_4"),
}

/**
 * Returns the drawable resource id for [asset] if the gate is
 * open AND the file exists in `res/drawable/`. Returns 0
 * otherwise — callers check `id != 0` to decide whether to
 * render the mascot or fall through to the legacy UI.
 *
 * Resolved via `Resources.getIdentifier` rather than the
 * generated `R.drawable.cyan_*` constants so the project
 * compiles before any Cyan assets exist. Once the
 * WebPs are dropped in, the same call starts returning their ids without a
 * code change.
 */
@Composable
fun rememberCyanResId(asset: CyanAsset): Int {
    if (!CyanGate.enabled) return 0
    val ctx = LocalContext.current
    // Runtime gate per GraphicsPrefs.cyanMascot — the user-facing
    // toggle on the Graphics settings screen. Defaults to true; off
    // makes every Cyan composable fall through to legacy UI
    // immediately (StateFlow republishes when the pref is written).
    val prefs = remember(ctx) { GraphicsPrefs(ctx) }
    val userEnabled by prefs.cyanMascotFlow.collectAsState()
    if (!userEnabled) return 0
    return remember(asset) {
        ctx.resources.getIdentifier(asset.resName, "drawable", ctx.packageName)
    }
}

/**
 * Render one Cyan WebP inside a flat-top hex frame — single
 * source of truth for every surface that shows the mascot. Wraps
 * an [Image] in two layers:
 *
 *   1. [HexShape]   — draws the cyan border + (optional) glow halo
 *                     in the canonical LunaGlass language.
 *   2. [HexagonShape] — clips the bitmap itself to the hex outline
 *                     so the source render's dark-blue rectangular
 *                     background never bleeds past the frame.
 *
 * The two together let Cyan ship without anyone hand-editing
 * transparency into the WebPs: whatever rectangular padding the
 * AI-generation tool baked in gets clipped away at render time.
 *
 * [colorFilter] passes through to the [Image] so the tier-up
 * overlay can tint the character without touching the frame
 * border. [borderColor] defaults to AegisCyan so most surfaces
 * stay on-brand; the tier-up overlay flips it to the tier colour
 * for a unified celebratory frame.
 */
@Composable
private fun CyanHex(
    resId: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    borderColor: Color = AegisCyan,
    glow: Boolean = false,
    colorFilter: ColorFilter? = null,
    description: String = "Cyan",
) {
    HexShape(
        size = size,
        modifier = modifier,
        borderColor = borderColor,
        glow = glow,
    ) {
        // Pure clip-to-hex on a same-size image: the source's rectangular
        // background is clipped to the hex outline. (An earlier 2× zoom to
        // centre the face pushed the body off-screen — "looks very bad",
        // user 2026.06.297 — so we don't zoom; face-centred composition is
        // the source art's job.)
        Image(
            painter = painterResource(resId),
            contentDescription = description,
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
            modifier = Modifier
                .size(size)
                .clip(HexagonShape)
                // Cyan-crown reward: at the max tier her cyan zones (hair/eyes)
                // shimmer with the chosen crown style. No-op below Cyan / on
                // Android <13. See Modifier.cyanZoneFoil.
                .cyanZoneFoil(),
        )
    }
}

/** Which edge of [CyanSpeechBubble] grows the little tail, so the
 *  bubble points AT Cyan wherever she sits relative to it:
 *   - [Up]   — Cyan is ABOVE the bubble (tutorial / empty state:
 *              pose on top, bubble beneath it).
 *   - [Down] — Cyan is BELOW the bubble (About card: bubble on top,
 *              her photo beneath it).
 *   - [None] — no tail (bare caption use).
 */
enum class BubbleTail { Up, Down, None }

/**
 * Speech bubble next to / below Cyan — replaces the plain Text
 * label that used to sit under the empty-state image. Reads as
 * Cyan actually saying the line to the user, not just a caption.
 *
 * Rounded rectangle + a small triangular TAIL on the [tail] edge so
 * it reads as a real speech bubble (💬) rather than a plain card —
 * the tail points toward Cyan (it needs to read as a proper speech
 * bubble). Static only (no Compose animations per
 * project policy). Sized to fit content with a max-width cap so a
 * long line wraps neatly rather than stretching across the screen.
 *
 * Always appends "— Stay safe." as a small second-line signature
 * (her signature line is "Stay safe." — her last word in every
 * context where she speaks). The
 * signature is rendered dimmer + smaller than the main message so
 * it reads as a sign-off, not a continuation. [showSignature] is
 * left as an opt-out for the rare surface that doesn't want her
 * to close — defaults to true.
 */
@Composable
fun CyanSpeechBubble(
    message: String,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 240.dp,
    showSignature: Boolean = true,
    tail: BubbleTail = BubbleTail.Up,
) {
    // Single fill for body + tail so they read as one shape. The
    // surfaceVariant fill is near-black on our dark theme, so a cyan
    // border is what actually makes the bubble visible (dark on
    // dark, needs a border). Brand cyan at a
    // soft alpha — present, not shouty.
    val bubbleColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
    val borderColor = AegisCyan.copy(alpha = 0.6f)
    val borderWidth = 1.5.dp
    val tailH = 9.dp

    // ONE shape: the rounded body UNIONed with the tail triangle, so
    // the border traces the whole silhouette as a single outline. The
    // old approach (full border on the rect + a separate triangle) left
    // a stray line across the tail's base — the rect's own border
    // showing through (a redundant line where the
    // arrow is). A path union has no internal edge there.
    // ONE shape: the CHAMFERED (cut-corner) body UNIONed with the tail
    // triangle, so the border traces the whole silhouette as a single
    // outline. Cut corners (not rounded) so the bubble speaks the LunaGlass
    // hex/angular language like GlassPanel — was a soft RoundedCornerShape
    // that clashed with it (user report 2026-06-16).
    val density = LocalDensity.current
    // Share the chat message-bubble facet dimension (FacetedBubble.FACET_CUT_DP
    // = 14dp) so every LunaGlass surface cuts identically — one facet depth
    // across bubbles, panels, and switches.
    val cornerPx = with(density) { FACET_CUT_DP.dp.toPx() }
    val tailWpx = with(density) { 18.dp.toPx() }
    val tailHpx = with(density) { tailH.toPx() }
    val shape = remember(tail, cornerPx, tailWpx, tailHpx) {
        GenericShape { size, _ ->
            val cx = size.width / 2f
            val up = tail == BubbleTail.Up
            val down = tail == BubbleTail.Down
            val bodyTop = if (up) tailHpx else 0f
            val bodyBottom = if (down) size.height - tailHpx else size.height
            // Clamp the chamfer so its 45° HYPOTENUSE (length c·√2 — the cut
            // edge you actually see) never exceeds the flat straight segment
            // of the edge it bounds (length edge − 2c). Setting
            //     c·√2 ≤ edge − 2c   ⇒   c ≤ edge / (2 + √2) ≈ 0.293·edge
            // means each side of the octagon reads hypotenuse ≤ flat ≥
            // hypotenuse — the three pieces are at most equal, with the flat
            // never shorter than a cut. An earlier edge/3 cap left the
            // hypotenuse (~0.47·edge) clearly longer than the flat
            // (~0.33·edge), which looked wrong on a single-line bubble (user
            // report 2026-06-16). Binding dimension is the shorter edge — the
            // body height for one-liners. Body height (not the tail-inclusive
            // size.height) is what the cut actually bounds.
            val bodyH = bodyBottom - bodyTop
            val maxCutFraction = 1f / (2f + kotlin.math.sqrt(2f))  // ≈ 0.293
            val c = minOf(cornerPx, bodyH * maxCutFraction, size.width * maxCutFraction)
            // Cut-corner (chamfered) rectangle: each corner is sliced flat,
            // clockwise from the top edge.
            val body = Path().apply {
                moveTo(c, bodyTop)
                lineTo(size.width - c, bodyTop)
                lineTo(size.width, bodyTop + c)
                lineTo(size.width, bodyBottom - c)
                lineTo(size.width - c, bodyBottom)
                lineTo(c, bodyBottom)
                lineTo(0f, bodyBottom - c)
                lineTo(0f, bodyTop + c)
                close()
            }
            if (up || down) {
                // Triangle base overlaps the body by 1px so the union
                // merges into one contour (no hairline gap).
                val tail2 = Path().apply {
                    if (up) {
                        moveTo(cx, 0f)
                        lineTo(cx - tailWpx / 2f, tailHpx + 1f)
                        lineTo(cx + tailWpx / 2f, tailHpx + 1f)
                    } else {
                        moveTo(cx, size.height)
                        lineTo(cx - tailWpx / 2f, size.height - tailHpx - 1f)
                        lineTo(cx + tailWpx / 2f, size.height - tailHpx - 1f)
                    }
                    close()
                }
                op(body, tail2, PathOperation.Union)
            } else {
                addPath(body)
            }
        }
    }

    Column(
        modifier = modifier
            .widthIn(max = maxWidth)
            .background(bubbleColor, shape)
            .border(borderWidth, borderColor, shape)
            // Keep text clear of the tail strip on whichever side it's on.
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = 10.dp + if (tail == BubbleTail.Up) tailH else 0.dp,
                bottom = 10.dp + if (tail == BubbleTail.Down) tailH else 0.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        if (showSignature) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.cyan_stay_safe),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}


/**
 * Empty-state composable. Renders the
 * compact waist-up [CyanAsset.Onboard2] illustration above a
 * one-liner. Renders nothing when the gate is closed or the asset is
 * absent — callers wrap the call so a fallback UI takes over.
 *
 * @return true if Cyan rendered; false if the gate is closed
 *         and the caller should fall through to its legacy
 *         empty-state UI.
 */
@Composable
fun CyanEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    imageSize: Dp = 160.dp,
): Boolean {
    val resId = rememberCyanResId(CyanAsset.Onboard2)
    if (resId == 0) return false
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Waist-up pose (transparent bg): in-app surfaces show a
        // compact Cyan reminder, not the full-body figure reserved for
        // the tutorial. Onboard2 is the waist-up
        // finger-up sticker.
        CyanPose(asset = CyanAsset.Onboard2, size = imageSize)
        Spacer(modifier = Modifier.height(12.dp))
        // Speech bubble — reads as Cyan saying [message] to the
        // user rather than a caption under an illustration. Cyan sits
        // ABOVE it here, so the tail points up (default).
        CyanSpeechBubble(message = message)
    }
    return true
}

/**
 * Inline Cyan headshot for the About / Project Aether row in
 * Settings, on the About / Project Aether page. Sits beside
 * the existing wordmark + version block. Renders nothing when
 * the gate is closed or the asset is absent.
 */
@Composable
fun CyanHeadshot(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    val resId = rememberCyanResId(CyanAsset.Headshot)
    if (resId == 0) return
    CyanHex(
        resId = resId,
        size = size,
        modifier = modifier,
    )
}

/**
 * Splash mascot. Replaces the existing hex-shield painter when
 * the gate is open. Caller still handles the AEGIS wordmark +
 * version subtitle — Cyan is just the central image.
 *
 * @return true if rendered; false if the caller should keep
 *         using the legacy hex shield painter.
 */
@Composable
fun CyanSplashImage(
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
): Boolean {
    val resId = rememberCyanResId(CyanAsset.Splash)
    if (resId == 0) return false
    // Splash gets the full LunaGlass treatment — hex frame plus the
    // soft cyan glow halo so the mascot reads as "the brand mark"
    // not just "an image on the splash screen."
    CyanHex(resId = resId, size = size, modifier = modifier, glow = true)
    return true
}

/**
 * Onboarding step illustration. [step] is 1-based to match the
 * four-step flow (welcome / permissions / first contact
 * / done). Renders nothing for out-of-range step or closed gate.
 *
 * ## Frameless — no CyanHex wrapper
 *
 * The four onboard source renders (`cyan_onboard_1/3/4` especially)
 * already carry a baked-in cyan hex frame behind the character — plus
 * the glowing shield/orb she holds. Wrapping those in [CyanHex] drew a
 * SECOND hex around the first: Cyan showed with the
 * shield on her hand, but there was a cyan hex behind her, AND she
 * was wrapped in a second hex. Rendering the source as-is via [CyanPose]
 * (ContentScale.Fit, transparent, no frame) shows the art's own hex
 * exactly once. `cyan_onboard_2` is the transparent waist-up sticker
 * and reads fine frameless too.
 *
 * Splash and headshot deliberately keep [CyanHex] (see
 * [CyanSplashImage] / [CyanHeadshot]): their sources have NO baked-in
 * hex, so the frame is what gives them one.
 */
@Composable
fun CyanOnboardImage(
    step: Int,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val asset = when (step) {
        1 -> CyanAsset.Onboard1
        2 -> CyanAsset.Onboard2
        3 -> CyanAsset.Onboard3
        4 -> CyanAsset.Onboard4
        else -> return
    }
    // NOTE: do NOT clip these to a hex to hide the rectangular backing — the
    // figure (shield, accent lines) overflows the inscribed hex, so a hex clip
    // shaved the shield/lines (user report). The proper fix for the "grey box"
    // is a transparent re-export of the asset; until then we render it whole.
    CyanPose(asset = asset, modifier = modifier, size = size)
}

/**
 * Public single-asset pose renderer — the general escape hatch for
 * surfaces that need a specific [CyanAsset] shown as a full figure.
 * Added for the onboarding tutorial, which
 * picks a different pose per page; also used on the About card.
 *
 * ## Full-body, no hex frame
 *
 * Unlike the truly hex-framed helpers ([CyanSplashImage] /
 * [CyanHeadshot], the tier-up badge), this renders the WHOLE figure
 * via [ContentScale.Fit] and adds NO frame of its own. Two kinds of
 * source flow through here:
 *
 *   - Stripped-to-transparency stickers (cyan_onboard_2 /
 *     cyan_sitting / cyan_celebrate) — the full pose shows on the
 *     app's dark background with her gestures (the raised finger)
 *     intact, which the old hex-crop clipped off.
 *   - Sources that carry their OWN baked-in hex (cyan_onboard_1/3/4)
 *     — passed here on purpose since 2026-06-06 so their baked hex
 *     shows exactly once. Wrapping them in [CyanHex] drew a second
 *     hex (the double-hex problem); their near-black navy
 *     backing blends into the app's dark surface acceptably.
 *
 * Renders nothing and returns false when the gate is closed or the
 * asset file is absent — callers fall through to a text-only layout
 * (the tutorial still reads fine as speech bubbles alone when the
 * mascot is toggled off in Graphics settings).
 *
 * @return true if Cyan rendered; false if the caller should lay
 *         out without her.
 */
@Composable
fun CyanPose(
    asset: CyanAsset,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
): Boolean {
    val resId = rememberCyanResId(asset)
    if (resId == 0) return false
    Image(
        painter = painterResource(resId),
        contentDescription = "Cyan",
        contentScale = ContentScale.Fit,
        // Cyan-crown reward: at the max tier her cyan zones (hair/eyes, and any
        // baked-in cyan frame) shimmer with the chosen crown style. The foil was
        // previously only on the hex-framed helpers (CyanHex → splash / headshot
        // / tier badge), so every full-figure surface that flows through here —
        // the chat-list empty state, tutorial, onboarding, About — never shone.
        // No-op below Cyan tier / glass-off / Android <13. See cyanZoneFoil.
        modifier = modifier.size(size).cyanZoneFoil(),
    )
    return true
}

/**
 * Tier-up celebration overlay.
 *
 * Static-only per project policy — no Compose
 * `AnimatedVisibility` / `fadeIn` / `fadeOut`. The overlay
 * appears for [holdMs] then removes itself via a
 * [LaunchedEffect] timer + `onDismiss` callback. Tier-frame
 * colour is applied as an [ColorFilter] tint so a single image
 * works for every tier.
 *
 * Caller is responsible for state hoisting — wrap the call
 * with `if (tierUpVisible)` and provide `onDismiss = {
 * tierUpVisible = false }`. The composable does not own
 * visibility state because the trigger (tier achievement
 * event) lives outside the composable lifecycle.
 *
 * Renders nothing when the gate is closed; the caller's
 * `if (tierUpVisible)` branch can stand alone with no visual
 * fallback (tier-up was a celebratory polish, not a feature
 * users depend on).
 */
@Composable
fun CyanTierUpOverlay(
    tierColor: Color,
    onDismiss: () -> Unit,
    holdMs: Long = 2000L,
) {
    val resId = rememberCyanResId(CyanAsset.Celebrate)
    if (resId == 0) {
        // Gate closed — still auto-dismiss so callers don't
        // get a stuck-visible state when Cyan is off.
        LaunchedEffect(Unit) {
            delay(holdMs)
            onDismiss()
        }
        return
    }
    LaunchedEffect(Unit) {
        delay(holdMs)
        onDismiss()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Tier-up hex frame inherits the tier colour so the whole
        // celebratory mark (border + tinted character inside)
        // reads as one piece. Glow halo on so it feels like an
        // achievement not just a sticker.
        CyanHex(
            resId = resId,
            size = 220.dp,
            borderColor = tierColor,
            glow = true,
            colorFilter = ColorFilter.tint(
                tierColor,
                blendMode = androidx.compose.ui.graphics.BlendMode.Modulate,
            ),
            description = "Tier achieved",
        )
    }
}

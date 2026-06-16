package app.aether.aegis.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.aegis.R
import app.aether.aegis.admin.ShieldTier
import app.aether.aegis.ui.LocalGraphicsRich
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisEmergency
import app.aether.aegis.ui.theme.AegisOnSurfaceDim

/**
 * THE canonical avatar — one source of truth for how a member's hex
 * avatar is drawn everywhere (Comms list + grid, Radar pins + dock,
 * System / Settings self, contact detail, chat header).
 *
 * Before this existed every screen rolled its own [HexShape] call with
 * its own border-colour rule and its own (inconsistent) fill, so the
 * tier "medal" frame only looked like metal on the chat list, frames
 * were thin elsewhere, and the radar self pin carried an ugly bright-
 * cyan fill. This consolidates the look so the frame is computed AT THE
 * SOURCE and merely displayed per-screen.
 *
 * The avatar is ONE piece of polished metal:
 *   - Frame + back plate are the SAME colour (the member's frame colour,
 *     see [frameColorFor]) and the SAME material, so they catch light
 *     identically — a thick beveled rim around a metal plate.
 *   - A tilt-reactive specular highlight ([Modifier.metalShine]) sweeps
 *     the WHOLE hex as the phone tips, like light on real metal. On by
 *     default (gated only on rich-graphics, NOT the experimental sheen
 *     toggle) so the shine is everywhere, not just a few screens.
 *   - The [initial] is engraved into the plate — a recessed groove whose
 *     lit lip tracks the same device tilt, so it reads as etched into the
 *     metal and reacts to motion rather than to a fixed light. A profile
 *     photo, when present, sits on top and covers the plate (a picture in
 *     its frame); the metal rim + its shine stay around it.
 *   - Emergency / Untrusted keep a CLEAN (matte, plate-free) hex so the
 *     red "!" / incognito mask read unambiguously — those signals outrank
 *     the decorative metal.
 *
 * Tier resolution is the CALLER's job (self → ShieldTierEngine
 * .currentTier, peer → KnownPeer.peerReportedTier) — see [frameColorFor].
 */
@Composable
fun AegisAvatar(
    size: Dp,
    /** Resolved shield tier, or null / [ShieldTier.None] for no medal. */
    tier: ShieldTier?,
    modifier: Modifier = Modifier,
    /** First-letter monogram engraved into the metal plate when no photo. */
    initial: String = "",
    /** Absolute path of the member's avatar JPEG, or null. Covers the plate
     *  when the file exists. */
    avatarPath: String? = null,
    /** Online → cyan presence glow behind the hex (suppressed for emergency /
     *  untrusted, whose colour is a fixed semantic signal). */
    online: Boolean = false,
    /** Emergency contact — red "!" on a clean hex, red frame. */
    isEmergency: Boolean = false,
    /** Untrusted contact — incognito mask on a clean hex, dim frame. */
    isUntrusted: Boolean = false,
    /** Crown-shimmer style the PEER announced (0 glow / 1 rainbow / 2 oil-
     *  slick). Ignored for non-Cyan tiers; for the Cyan crown it selects the
     *  iridescence. Only consulted when [isSelf] is false. */
    peerReportedCrownStyle: Int? = null,
    /** True when this is the LOCAL user's own avatar. The Cyan crown then
     *  shines in the user's OWN selected iridescence (read from prefs) instead
     *  of a peer's announced style — fixes "my own frame is hardcoded cyan
     *  instead of the selected iridescence". */
    isSelf: Boolean = false,
    /** Deprecated knob — the tilt shine is on by default now. Kept so call
     *  sites that passed the old sheen toggle still compile; ignored. */
    animatedSheen: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val frameColor = frameColorFor(tier, isEmergency, isUntrusted, online)
    // Metal treatment applies to ordinary contacts (any tier incl. none);
    // emergency / untrusted stay matte so their semantic glyph reads cleanly.
    val metal = !isEmergency && !isUntrusted
    val rich = LocalGraphicsRich.current
    val shineOn = metal && rich

    // Photo wins over the plate when an announced avatar is cached AND still on
    // disk. remember on the path so the File.exists() hit isn't repeated every
    // recomposition.
    val showImage = !isEmergency && !isUntrusted && avatarPath != null &&
        remember(avatarPath) { java.io.File(avatarPath).exists() }

    // Metal back plate: a gradient of the FRAME colour (not graphite) so the
    // plate is visibly the same metal as the rim — top a touch brighter, bottom
    // a touch darker for body, with the tilt specular adding the live shine.
    val plateBrush: Brush? = if (!showImage && metal) metalPlateBrush(frameColor) else null

    // Photo fits INSIDE the inner wall, so it fills only the recessed plate and
    // never covers the walls (the walls are the depth cue and must stay
    // visible — a picture sits IN the well, it doesn't overpaint the frame's
    // inner face). Derived from the same constants HexShape uses for the rim
    // band (borderWidth × 1.9) and the inner wall, so it tracks if they change.
    val rimBandDp = size.value * 0.06f * 1.9f
    val wallDp = (size.value * 0.024f).coerceIn(1.5f, 3.5f)
    val photoInset = (size.value - rimBandDp - 2f * wallDp)
        .coerceAtLeast(size.value * 0.5f).dp

    androidx.compose.foundation.layout.Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        HexShape(
            size = size,
            borderColor = frameColor,
            // Chunky raised frame — proportional to the avatar so a 44 dp
            // chat-list hex and a 120 dp contact-detail hex both read as a thick
            // metal rim (the medalFrame path widens + bevels it). The recessed
            // depth is carried by the inner WALL drawn inside HexShape.
            borderWidth = (size.value * 0.06f).dp,
            fillBrush = plateBrush,
            glow = online && metal,
            medalFrame = metal,
            // HexShape owns the tilt-reactive material: chrome for Bronze/
            // Silver/Gold, the iridescent foil for the Cyan crown (per style).
            medalSheen = shineOn,
            medalIntensity = AVATAR_SHINE_INTENSITY,
            // Dispersed, smooth glint (polish, not a hard dot).
            medalSpecularRadiusFactor = 0.62f,
            // FULL-hex shine: rim AND plate are equally polished, one consistent
            // reflective surface. (Clipping it to a ring made the shine a SECOND
            // hexagon offset from the rim — the "two hexagons, one shines" — and
            // left the plate matte. The inner wall, not a matte plate, carries
            // the recess, so the plate can shine without going convex.)
            sheenInnerFraction = 0f,
            // Self → null so the Cyan crown follows the user's OWN selected
            // iridescence; a peer → their announced style (0 if none).
            crownStyleOverride = if (isSelf) null else (peerReportedCrownStyle ?: 0),
            onClick = onClick,
        )
        // Content sits ABOVE the shine so the monogram stays MATTE — an engraved
        // letter is a recess and must not catch the rim's reflection (it was
        // washing out). A photo also sits on top, covering the plate cleanly.
        when {
            showImage -> {
                Box(
                    modifier = Modifier
                        .size(photoInset)
                        .clip(HexagonShape),
                ) {
                    coil.compose.AsyncImage(
                        model = java.io.File(avatarPath!!),
                        contentDescription = stringResource(R.string.profile_avatar),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(photoInset),
                    )
                }
            }
            isUntrusted -> {
                Image(
                    painter = painterResource(R.drawable.ic_aegis_incognito),
                    contentDescription = "untrusted",
                    colorFilter = ColorFilter.tint(AegisOnSurfaceDim),
                    modifier = Modifier.size(size * 0.5f),
                )
            }
            isEmergency -> {
                Text(
                    "!",
                    color = AegisEmergency,
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            else -> {
                EngravedInitial(
                    text = initial,
                    frameColor = frameColor,
                    fontSizeSp = size.value * 0.40f,
                )
            }
        }
    }
}

/**
 * Canonical avatar-frame colour rule, shared by every surface so the
 * frame means the same thing everywhere:
 *   Emergency → red, Untrusted → deliberately dim, an earned tier → that
 *   tier's metal colour, otherwise cyan (online-or-not; presence is
 *   carried by the glow + status dot, not by dimming the frame).
 */
fun frameColorFor(
    tier: ShieldTier?,
    isEmergency: Boolean,
    isUntrusted: Boolean,
    online: Boolean = false,
): Color = when {
    isEmergency -> AegisEmergency
    isUntrusted -> AegisOnSurfaceDim
    tier != null && tier != ShieldTier.None -> tier.color()
    else -> AegisCyan
}

/**
 * Plate brush — the SAME polished metal as the rim (the plate should be just
 * as reflective, not a dark matte floor). A near-uniform tier-metal body with a
 * whisper of vertical sheen; the recess is conveyed by the rim's CAST SHADOW
 * (drawn in HexShape) and the depth of the raised bevel, NOT by darkening the
 * plate. The live reflection is the tilt specular sweeping the whole hex —
 * rim and plate alike.
 */
fun metalPlateBrush(frameColor: Color): Brush = Brush.verticalGradient(
    colors = listOf(
        // Polished metal, as bright as the rim (the depth now comes from the
        // visible INNER WALL drawn in HexShape, not from darkening the plate).
        lerp(frameColor, Color.White, 0.12f),
        frameColor,
        lerp(frameColor, Color(0xFF05070A), 0.10f),
    ),
)

/** Peak brightness of the avatar's tilt specular. Paired with a TIGHT radius
 *  (see medalSpecularRadiusFactor) so it's a bright, crisp glint confined to a
 *  small hotspot — polished metal catching a point light — not a broad wash. */
private const val AVATAR_SHINE_INTENSITY = 7f

/**
 * The monogram engraved into the metal plate — a STATIC recess that reads as
 * laser-etched. Two stacked glyphs sell the carve as a fixed letterpress:
 *   - a faint, fixed 1 px lower-lip highlight (the lit far wall of the groove),
 *   - the letter proper in a DARK burnished tone on top (the recessed groove).
 *
 * The lip does NOT move with tilt: an earlier version offset the highlight
 * glyph by the tilt vector, which slid it out from under the dark glyph into a
 * visible ghost second letter that swam as you tipped the phone ("the light
 * moves, which is wrong"). The avatar's tilt reaction is owned entirely by the
 * metal SHINE sweeping over the whole hex; the engraving itself stays put, the
 * way a real etched groove does — only the light playing over it moves.
 */
@Composable
private fun EngravedInitial(
    text: String,
    frameColor: Color,
    fontSizeSp: Float,
) {
    if (text.isBlank()) return
    Box(contentAlignment = Alignment.Center) {
        // Fixed lit lower lip — a soft 1 px highlight, NOT pure white and NOT
        // offset by tilt, so it merges into an etched edge instead of reading
        // as a separate ghost letter.
        Text(
            text,
            color = Color.White.copy(alpha = 0.20f),
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset(y = 1.dp),
        )
        // The groove itself — a dark, burnished version of the plate metal so
        // it reads as the SAME material carved away, not a foreign black glyph.
        Text(
            text,
            color = lerp(frameColor, Color(0xFF05070A), 0.82f),
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

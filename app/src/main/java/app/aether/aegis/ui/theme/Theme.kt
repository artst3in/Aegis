package app.aether.aegis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.aegis.R

// LunaGlass palette. Dark + cyan + hex everything.
// Glow tokens are expressed as colours-with-alpha; consumers do their
// own radial-gradient + blur if they need the actual glow effect.
val AegisOnSurface    = Color(0xFFF0F0F0)  // primary text — neutral off-white (R=G=B). Was E0F0F0, a faint cyan-tinted white; off-white (not pure #FFFFFF) avoids halation on the near-black bg.
val AegisOnSurfaceDim = Color(0xFF999999)  // secondary text, timestamps — TRUE neutral grey (R=G=B). Was 6A8A8A grey-teal (R=0x6A greyed a cyan into teal); a neutral grey isn't a cyan so it's on-rule and reads cleaner.
val AegisCyan         = Color(0xFF00FFFF)  // primary accent — pure cyan
val AegisCyanDim      = Color(0xFF007A7A)  // inactive hex borders / gradient edge
val AegisCyanGlow     = Color(0x2600FFFF)  // glow backgrounds (alpha 0.15)
val AegisCyanGlowSoft = Color(0x1400FFFF)  // softer glow (alpha 0.08)
val AegisBorder       = Color(0x3300FFFF)  // all borders — faint cyan hairline (LunaGlass). Was 0xFF1A3A40 dark teal: off-palette/forbidden.
val AegisPanel        = Color(0xFF001A1E)  // glass panel fill — R zeroed (was 0D1A1E): no red in a cyan, even a near-black one
val AegisAccent       = Color(0xFFFFD700)  // gold — functional accent only (pinned notes, shield tier). NOT brand.
val AegisSOS        = Color(0xFFFF0000)  // SOS, siren, danger — pure RGB red
val AegisSOSGlow    = Color(0x33FF0000)  // alpha 0.2
// Emergency-CONTACT tier accent (the relationship role, NOT an active alarm).
// Warm amber = "priority / raise your bar", deliberately NOT the SOS red — an
// emergency contact frame must never read as "this person is calling SOS right
// now". Sits between cyan (Trusted) and grey (Untrusted) in the tier ordering;
// distinct from the muted bronze/gold/silver SHIELD metals.
val AegisEmergency  = Color(0xFFFFA000)  // amber — emergency-contact frame + "!"
val AegisOnline       = Color(0xFF00FF00)  // online status dot — pure RGB green
val AegisOnlineGlow   = Color(0x3300FF00)  // remote-mode screen tint — sister of AegisSOSGlow
val AegisWarning      = Color(0xFFFF9800)  // warnings, "away", protocol degraded
// Soft red for non-life-threatening trouble — low battery, offline,
// "transport: down". Distinct from AegisSOS (pure RGB red) which
// is reserved for life-safety affordances so the two never get
// muddled.
val AegisDanger       = Color(0xFFFF5555)
// Informational state colour — "initialising", "connecting",
// progress indicators that aren't completed and aren't an error
// either. Material blue rather than cyan so it's visually distinct
// from the brand-cyan "active / accent" affordance.
val AegisInfo         = Color(0xFF2196F3)

// Shield-tier frame colours. Five tiers,
// linear by lit-node count. Bronze → Silver → Gold are the standard
// medal climb; Cyan is the crown — the Aegis brand colour worn only
// by users who maxed every node, so it reads as "this person IS
// Aegis" rather than a generic default. AegisCyan itself (top of
// this file) is reused for the top tier; we just need bronze /
// silver / gold lower down.
val AegisShieldBronze  = Color(0xFFCD7F32)  // classic bronze (1 node)
val AegisShieldSilver  = Color(0xFF9AA4AD)  // muted silver (2-8 nodes)
val AegisGold          = Color(0xFFD4AF37)  // metallic gold, not lemon (9 nodes)

// LunaGlass effect tokens
val AegisHexGradCenter = Color(0xB300FFFF)  // hex radial gradient center
val AegisHexGradMid    = Color(0x8000DCDC)  // hex radial gradient mid stop
val AegisHexGradEdge   = Color(0x4D007A7A)  // hex radial gradient edge
val AegisGlassFill     = Color(0xD9001A1E)  // frosted panel — 85% opacity — R zeroed (was 0D1A1E)
val AegisGlassBorder   = Color(0x1F00FFFF)  // frosted panel border — 12% cyan
val AegisHexGridStroke = Color(0x1A00FFFF)  // global hex-grid backdrop stroke — ~10% cyan, just visible
// Source alphas bumped from 0x0F → 0x33 and 0x33 → 0x66 so that at
// effectsIntensity = 1.0 (slider max, per GraphicsPrefs default)
// the scan line and data-rain are actually readable. Previously
// max-intensity rendered at the same near-invisible level the
// minimum mode used, and the user had no way to make the effects
// land in the foreground.
val AegisScanLine      = Color(0x3300FFFF)  // crawling-down scan line
val AegisDataRain      = Color(0x6600A0B0)  // drifting particles — darker muted teal so it sits in the background

val AegisPrimary       = AegisCyan
val AegisPrimaryDeep   = AegisCyanDim
val AegisBackground    = Color(0xFF050508)  // main background
val AegisSurface       = Color(0xFF001214)  // cards, headers — R zeroed (was 0A1214)
val AegisSurfaceHi     = AegisPanel

// LunaGlass body face — Inter (Rasmus Andersson, OFL). Every text
// style in LunaGlass uses one sans-serif, "Inter, sans-serif"
// everywhere. Inter was designed purpose-built for screen UI — high
// x-height, even stroke widths, open counters — and scored highest in
// a 6-criteria evaluation against Georgia / PT Serif /
// system default.
//
// Bundled as static Regular + SemiBold + Bold TTFs from the rsms/
// inter v4.1 release. Static instances avoid the variable-font
// pitfall we hit with Bitter (Compose needs FontVariation.Settings
// per slot or it renders the default instance everywhere). License
// at assets/licenses/LICENSE-Inter.txt.
val LunaGlassFont: FontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

@Composable
private fun darkColorSchemeForMode(): androidx.compose.material3.ColorScheme = darkColorScheme(
    primary = AegisPrimary,
    onPrimary = AegisBackground,
    primaryContainer = AegisPrimaryDeep,
    onPrimaryContainer = AegisOnSurface,
    secondary = AegisAccent,
    onSecondary = AegisBackground,
    background = AegisBackground,
    surface = AegisSurface,
    surfaceVariant = AegisSurfaceHi,
    onBackground = AegisOnSurface,
    onSurface = AegisOnSurface,
    onSurfaceVariant = AegisOnSurfaceDim,
    error = AegisSOS,
    onError = Color.White,
)

private fun em(value: Float) = TextUnit(value, TextUnitType.Em)

/**
 * Canonical screen-title style — the LunaGlass treatment every app-bar
 * title uses. Defined as ONE named style so the whole app's title look
 * can be retuned from a single place ("LunaGlass everywhere; define
 * the style somewhere so we can adjust all at once later").
 *
 * This style is wired straight into Material3's `titleLarge` slot below,
 * which is exactly what a small `Scaffold` `TopAppBar` renders its title
 * with — so editing it here restyles every TopAppBar title across the
 * ~40 screens at once, with no per-screen changes.
 *
 * The explicit cyan [color] is the load-bearing part: a TopAppBar only
 * falls back to `onSurface` when the title's text style carries no
 * colour of its own, so baking cyan in here turns every default title
 * LunaGlass-cyan. Screens that set their own Text colour (e.g. white
 * over a photo in PhotoViewer) still override it locally.
 *
 * Letter-spacing is a subtle echo of the AEGIS brand header — kept
 * modest (not the header's 4sp) because screen titles are real words,
 * not a five-letter logotype.
 */
val AegisTitleStyle: TextStyle = TextStyle(
    fontFamily = LunaGlassFont,
    fontWeight = FontWeight.SemiBold,
    fontSize = 18.sp,
    letterSpacing = em(0.04f),
    color = AegisCyan,
)

private val AegisTypography = Typography(
    // LunaGlass spec 173d23c: "Georgia, serif — everywhere, always".
    // Single family for every text style; weight + size + letter-
    // spacing carry the hierarchy. Labels keep the all-caps
    // letter-spacing treatment from the design ref (the wide
    // tracking is what gives them their "SECTION LABEL" feel, not
    // monospace), just rendered in the serif now.
    displayLarge   = TextStyle(fontFamily = LunaGlassFont, fontWeight = FontWeight.Bold,     fontSize = 48.sp, letterSpacing = em(0.03f)),
    displayMedium  = TextStyle(fontFamily = LunaGlassFont, fontWeight = FontWeight.Bold,     fontSize = 32.sp, letterSpacing = em(0.03f)),
    headlineLarge  = TextStyle(fontFamily = LunaGlassFont, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    headlineMedium = TextStyle(fontFamily = LunaGlassFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    headlineSmall  = TextStyle(fontFamily = LunaGlassFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    // The LunaGlass screen-title style (cyan) — the single source of
    // truth for every TopAppBar title. See [AegisTitleStyle].
    titleLarge     = AegisTitleStyle,
    titleMedium    = TextStyle(fontFamily = LunaGlassFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall     = TextStyle(fontFamily = LunaGlassFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge      = TextStyle(fontFamily = LunaGlassFont, fontWeight = FontWeight.Normal,   fontSize = 16.sp),
    bodyMedium     = TextStyle(fontFamily = LunaGlassFont, fontWeight = FontWeight.Normal,   fontSize = 14.sp),
    bodySmall      = TextStyle(fontFamily = LunaGlassFont, fontWeight = FontWeight.Normal,   fontSize = 12.sp),
    labelLarge     = TextStyle(fontFamily = LunaGlassFont, fontWeight = FontWeight.Normal,   fontSize = 12.sp, letterSpacing = em(0.15f)),
    labelMedium    = TextStyle(fontFamily = LunaGlassFont, fontWeight = FontWeight.Normal,   fontSize = 11.sp, letterSpacing = em(0.15f)),
    labelSmall     = TextStyle(fontFamily = LunaGlassFont, fontWeight = FontWeight.Normal,   fontSize = 10.sp, letterSpacing = em(0.2f)),
)

/**
 * Global LunaGlass shape scale. Material 3 components (Button,
 * OutlinedButton, TextButton, Card, dialogs, menus, text fields…) read
 * their corner shape from the theme's [androidx.compose.material3.Shapes],
 * which otherwise defaults to ROUNDED corners — leaving every untouched
 * Button rounded no matter how faceted the rest of the surface is.
 *
 * Switching the whole scale to [CutCornerShape] facets all 280+ components
 * that don't pass an explicit shape, in one place and with zero per-call
 * changes. The size tiers mirror M3's own defaults (4/8/12/16/28 dp rounded)
 * but cut instead of rounded, scaled to the LunaGlass cut depths already
 * used across the app (≈12–14 dp on panels/bubbles). The ~51 call sites that
 * pass an explicit `CutCornerShape` keep doing so — they now MATCH this
 * theme scale rather than override a rounded default.
 */
private val AegisShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.CutCornerShape(4.dp),
    small      = androidx.compose.foundation.shape.CutCornerShape(6.dp),
    medium     = androidx.compose.foundation.shape.CutCornerShape(8.dp),
    large      = androidx.compose.foundation.shape.CutCornerShape(12.dp),
    extraLarge = androidx.compose.foundation.shape.CutCornerShape(16.dp),
)

@Composable
fun AegisTheme(content: @Composable () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = androidx.compose.runtime.remember { app.aether.aegis.ui.GraphicsPrefs(ctx) }
    // Observe the StateFlow so a Settings toggle propagates LIVE —
    // previously this was `remember { prefs.hexEnrichment }` which
    // snapshotted the value once and never re-read.
    val userRich by prefs.hexEnrichmentFlow.collectAsState()
    // Voyager gate — at ≤50 % battery the lunaGlassAllowed StateFlow
    // flips false; AND with the user pref so EITHER (low battery) OR
    // (user toggled off) suppresses the effect layer.
    val voyagerAllowed by app.aether.aegis.AegisApp.instance.powerBudget.lunaGlassAllowed
        .collectAsState(initial = true)
    var rich = userRich && voyagerAllowed
    var backdrops = voyagerAllowed
    // Force-real-glass override: keep the rich layer ON even under the ≤50 %
    // Voyager gate, so the glass / metal-avatar effects can be reviewed
    // off-charger. Structured so scrubbing this block from the public source
    // leaves valid code (rich/backdrops keep their gated values).
    // >>> DEBUG-ONLY
    val forceRich by app.aether.aegis.prefs.ExperimentalPrefs(ctx)
        .forceRichGraphicsFlow.collectAsState()
    if (app.aether.aegis.BuildConfig.HAS_DEBUG_CHANNEL && forceRich) {
        rich = true
        backdrops = true
    }
    // <<< DEBUG-ONLY
    androidx.compose.runtime.CompositionLocalProvider(
        app.aether.aegis.ui.LocalGraphicsRich provides rich,
        app.aether.aegis.ui.LocalGraphicsBackdrops provides backdrops,
    ) {
        MaterialTheme(
            colorScheme = darkColorSchemeForMode(),
            typography = AegisTypography,
            // Faceted (cut-corner) shape scale so every Material 3 component
            // that doesn't pass an explicit shape inherits LunaGlass facets
            // instead of the rounded M3 default. See [AegisShapes].
            shapes = AegisShapes,
            content = content,
        )
    }
}

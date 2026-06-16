package app.aether.aegis.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Graphics profile — three discrete settings the user can pick from
 * the Graphics tab, ordered from most permissive (most watts) to
 * least permissive (least watts).
 *
 *   Performance — no refresh-rate cap (native 60/90/120 Hz on the
 *                 device); all LunaGlass overlays honour their
 *                 individual toggles; starfield background renders;
 *                 graphics-layer baking on.
 *
 *   Balanced    — 60 Hz refresh-rate cap; LunaGlass overlays honour
 *                 toggles; starfield background; baking on.
 *
 *   PowerSaver  — 30 Hz refresh-rate cap; LunaGlass overlays forced
 *                 off regardless of toggle state; starfield REPLACED
 *                 with solid black (OLED pixels off → free); baking on.
 *
 * The ordinal IS the watts-budget order — `ordinal == 0` is most
 * permissive, `ordinal == 2` is least. The Voyager curve in
 * PowerBudget publishes a "ceiling" profile based on battery level;
 * the user's preferred profile is then capped by it (whichever is
 * more restrictive wins). [GraphicsProfile.cappedBy] implements that.
 *
 * Independent from the profile: a battery-driven desaturation curve
 * (the Witcher-style "low health" fade). That curve runs regardless
 * of which profile is active — colors gradually wash out between 20 %
 * and 5 % battery, fully monochrome at ≤ 5 %. Implemented separately
 * from the profile machinery because the user always gets that
 * ambient warning, even if they manually pinned themselves to
 * Performance.
 */
enum class GraphicsProfile {
    Performance,
    Balanced,
    PowerSaver,
    ;

    /** Returns the more restrictive of `this` and [ceiling] — used to
     *  combine the user's preferred profile with Voyager's max-allowed
     *  ceiling. "More restrictive" = higher ordinal. */
    fun cappedBy(ceiling: GraphicsProfile): GraphicsProfile =
        if (this.ordinal >= ceiling.ordinal) this else ceiling

    /** Display label shown on the slider chip + Settings entry. */
    val label: String get() = when (this) {
        Performance -> "Performance"
        Balanced    -> "Balanced"
        PowerSaver  -> "Power saver"
    }

    /** One-line description shown under the slider, for the user to
     *  recognise what they're picking. */
    val description: String get() = when (this) {
        Performance -> "No refresh-rate cap. Effects honour toggles."
        Balanced    -> "60 Hz cap. Effects honour toggles."
        PowerSaver  -> "30 Hz cap. Effects off. Solid black background."
    }
}

/** Battery-level → maximum saturation factor. The Witcher-style
 *  desaturation curve. Independent from the profile slider — runs
 *  whenever Aegis renders. Values:
 *
 *    pct ≥ 20  → 1.0  (full color)
 *    pct ≤ 5   → 0.0  (full grayscale)
 *    5 < pct < 20 → linear interpolation
 *
 *  Wired in MainActivity via a `Modifier.graphicsLayer { ... }`
 *  with a saturation color matrix; cheap GPU shader pass, runs
 *  every frame regardless of profile.
 */
fun saturationForBattery(percentage: Int): Float = when {
    percentage >= 20 -> 1f
    percentage <=  5 -> 0f
    else             -> (percentage - 5) / 15f
}

/**
 * Process-wide hot snapshot of the **effective** Graphics profile —
 * the user's preferred profile already `cappedBy` the Voyager
 * battery-ceiling. Published by MainActivity once on each crossing
 * and observed by render-tree consumers that aren't on the
 * MainActivity composition (e.g. Starfield's background composable
 * needs PowerSaver to swap out the bitmap for a solid-black box and
 * skip all overlays).
 *
 * We use a StateFlow instead of CompositionLocal because the
 * Starfield reader sits inside a `remember(w, h)` closure that
 * doesn't recompose on local changes — flow.collectAsState gives us
 * a live re-read every time the profile crosses.
 */
object EffectiveGraphicsProfile {
    private val _state = MutableStateFlow(GraphicsProfile.Balanced)
    val state: StateFlow<GraphicsProfile> = _state
    fun set(profile: GraphicsProfile) { _state.value = profile }
}

package app.aether.aegis.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * CompositionLocal-published flag for whether per-hex LunaGlass
 * enrichment (gradient fill, edge lighting, breathing glow) is on.
 * HexShape + DoubleRingHex read this to decide whether to spin
 * infinite animations / extra canvas passes. Default true so unset
 * call sites stay rich; AegisTheme overrides to the user's pref.
 */
val LocalGraphicsRich = compositionLocalOf { true }

/** Sibling flag for the backdrop effects (hex grid / scan line / data
 *  rain). Same battery gate as [LocalGraphicsRich] but exposed
 *  separately so the StarfieldBackground reader doesn't also have to
 *  read the prefs. AND-ed with the user toggles in StarfieldBackground. */
val LocalGraphicsBackdrops = compositionLocalOf { true }

/**
 * User-facing toggles for the LunaGlass visual effects layer.
 *
 * Each setting is exposed as both a `var` (read/write through to
 * SharedPrefs) AND a `StateFlow` (observable for Compose). State
 * flows live in a process-wide companion so a setter at one call
 * site immediately propagates to every subscriber across the app —
 * AegisTheme + StarfieldBackground previously read the prefs once
 * via `remember { ... }` and never picked up changes, so toggles
 * silently did nothing until the next process restart.
 */
class GraphicsPrefs(context: Context) {

    private val prefs: SharedPreferences = singletonPrefs(context.applicationContext)

    var masterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MASTER, value).apply()
            republishAll(prefs)
        }

    var hexGrid: Boolean
        get() = prefs.getBoolean(KEY_HEX_GRID, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HEX_GRID, value).apply()
            republishAll(prefs)
        }

    var scanLine: Boolean
        get() = prefs.getBoolean(KEY_SCAN_LINE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SCAN_LINE, value).apply()
            republishAll(prefs)
        }

    /** Data rain is the heaviest backdrop effect (~20 particles
     *  redrawing every frame). Default OFF — opt-in for users who
     *  want it and have GPU headroom to spare. */
    var dataRain: Boolean
        get() = prefs.getBoolean(KEY_DATA_RAIN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DATA_RAIN, value).apply()
            republishAll(prefs)
        }

    var hexEnrichment: Boolean
        get() = prefs.getBoolean(KEY_HEX_ENRICHMENT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HEX_ENRICHMENT, value).apply()
            republishAll(prefs)
        }

    /**
     * Cyan mascot visibility — user-facing on/off for the chibi mascot
     * that lights up splash, empty states, About, onboarding, and the
     * tier-up overlay. Default ON so a fresh install gets the brand
     * mark; flipping it OFF makes every Cyan composable fall through
     * to its legacy UI (or render nothing).
     *
     * Independent of [CyanGate.enabled] — the const is a hard
     * compile-time kill switch (for shipping a Cyan-free APK); this
     * pref is the user-facing soft toggle. Both must agree before
     * the mascot renders.
     */
    var cyanMascot: Boolean
        get() = prefs.getBoolean(KEY_CYAN_MASCOT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CYAN_MASCOT, value).apply()
            republishAll(prefs)
        }

    /**
     * Backdrop effects intensity multiplier (0..1, default 1.0). Both
     * the scan line and data rain composables multiply their per-frame
     * alpha by this value. 0 = effectively off (kept distinct from the
     * individual on/off toggles so a user can soften without losing
     * the toggled-on state). The user-facing slider in
     * [GraphicsSettingsScreen] writes into this field; the comment
     * "invisible at <90% brightness vs distracting at high" is what
     * the slider lets each user balance on their own device.
     */
    var effectsIntensity: Float
        get() = prefs.getFloat(KEY_EFFECTS_INTENSITY, 1.0f).coerceIn(0f, 1f)
        set(value) {
            prefs.edit().putFloat(KEY_EFFECTS_INTENSITY, value.coerceIn(0f, 1f)).apply()
            republishAll(prefs)
        }

    /**
     * The user's preferred [GraphicsProfile]. Balanced by default — a
     * sane "smooth-but-power-aware" middle ground for a fresh install.
     * The effective profile at any moment is this value `cappedBy` the
     * Voyager-published ceiling (see [PowerBudget.ceilingGraphicsProfile]);
     * the user can always pick something equal-or-more conservative than
     * the ceiling, but never more permissive.
     */
    var preferred: GraphicsProfile
        get() = readProfile(prefs)
        set(value) {
            prefs.edit().putInt(KEY_PREFERRED, value.ordinal).apply()
            republishAll(prefs)
        }

    /** Observable forms — collected by AegisTheme + StarfieldBackground.
     *  Values fold in the master switch (effective* helpers) so
     *  consumers don't have to. */
    val hexGridFlow: StateFlow<Boolean>       get() = _hexGridFlow
    val scanLineFlow: StateFlow<Boolean>      get() = _scanLineFlow
    val dataRainFlow: StateFlow<Boolean>      get() = _dataRainFlow
    val hexEnrichmentFlow: StateFlow<Boolean> get() = _hexEnrichmentFlow
    val effectsIntensityFlow: StateFlow<Float> get() = _effectsIntensityFlow
    val preferredFlow: StateFlow<GraphicsProfile> get() = _preferredFlow
    val cyanMascotFlow: StateFlow<Boolean> get() = _cyanMascotFlow

    companion object {
        private const val STORE_NAME = "aegis_lunaglass"
        private const val KEY_MASTER = "master_enabled"
        private const val KEY_HEX_GRID = "hex_grid"
        private const val KEY_SCAN_LINE = "scan_line"
        private const val KEY_DATA_RAIN = "data_rain"
        private const val KEY_HEX_ENRICHMENT = "hex_enrichment"
        private const val KEY_EFFECTS_INTENSITY = "effects_intensity"
        private const val KEY_PREFERRED = "preferred_profile"
        // Cyan toggle is NOT gated on master_enabled — the mascot is
        // a brand surface, not a battery-cost effect like the
        // backdrops are. Stays visible even when LunaGlass effects
        // are turned off.
        private const val KEY_CYAN_MASCOT = "cyan_mascot"

        private fun readProfile(p: SharedPreferences): GraphicsProfile {
            val ord = p.getInt(KEY_PREFERRED, GraphicsProfile.Balanced.ordinal)
            val values = GraphicsProfile.values()
            return values.getOrElse(ord) { GraphicsProfile.Balanced }
        }

        // Process-wide singletons so multiple GraphicsPrefs
        // constructions across the UI hierarchy share state.
        @Volatile private var prefsInstance: SharedPreferences? = null
        private fun singletonPrefs(ctx: Context): SharedPreferences =
            prefsInstance ?: synchronized(this) {
                prefsInstance ?: ctx.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE).also {
                    prefsInstance = it
                }
            }

        private fun master(p: SharedPreferences) = p.getBoolean(KEY_MASTER, true)
        private fun effective(p: SharedPreferences, key: String, default: Boolean = true) =
            master(p) && p.getBoolean(key, default)

        // Lazy because the flow objects need to be in a defined state
        // before any code observes them — they're built off the
        // current SharedPrefs values at first access.
        private val _hexGridFlow         by lazy { MutableStateFlow(true) }
        private val _scanLineFlow        by lazy { MutableStateFlow(true) }
        private val _dataRainFlow        by lazy { MutableStateFlow(true) }
        private val _hexEnrichmentFlow   by lazy { MutableStateFlow(true) }
        private val _effectsIntensityFlow by lazy { MutableStateFlow(1.0f) }
        private val _preferredFlow       by lazy { MutableStateFlow(GraphicsProfile.Balanced) }
        private val _cyanMascotFlow      by lazy { MutableStateFlow(true) }

        private fun republishAll(p: SharedPreferences) {
            _hexGridFlow.value         = effective(p, KEY_HEX_GRID)
            _scanLineFlow.value        = effective(p, KEY_SCAN_LINE)
            _dataRainFlow.value        = effective(p, KEY_DATA_RAIN, default = false)
            _hexEnrichmentFlow.value   = effective(p, KEY_HEX_ENRICHMENT)
            _effectsIntensityFlow.value = p.getFloat(KEY_EFFECTS_INTENSITY, 1.0f).coerceIn(0f, 1f)
            _preferredFlow.value       = readProfile(p)
            // Bypass master_enabled — see KEY_CYAN_MASCOT note.
            _cyanMascotFlow.value      = p.getBoolean(KEY_CYAN_MASCOT, true)
        }
    }

    init {
        // First-construct hydration — make sure the flows reflect what
        // SharedPrefs currently says (defaults if never written).
        republishAll(prefs)
    }
}

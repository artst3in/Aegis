package app.aether.aegis.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Experimental-features gate:
 *
 *   "Sonar does NOT appear anywhere in the app unless explicitly
 *    enabled via Settings > Experimental. Experimental section itself
 *    is hidden until user taps the version number 7 times (same
 *    pattern as Android Developer Options). Ship it silent. Let power
 *    users find it."
 *
 * Two pref slots:
 *
 *   unlocked      true once the user has tapped the version 7 times.
 *                 Persistent — once unlocked, stays unlocked.
 *   sonarEnabled  true when the user has flipped the Sonar toggle
 *                 inside Experimental. Default false even when
 *                 [unlocked] is true; the feature opts itself in
 *                 only when the user explicitly says so.
 *
 * Process-wide singleton StateFlows so Compose can observe.
 */
class ExperimentalPrefs(context: Context) {

    private val prefs: SharedPreferences = singletonPrefs(context.applicationContext)

    var unlocked: Boolean
        get() = prefs.getBoolean(KEY_UNLOCKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_UNLOCKED, value).apply()
            _unlockedFlow.value = value
            if (!value) {
                // Lock implies every experimental feature off — never leave
                // one running when its gate has been removed.
                sonarEnabled = false
                glassSheenEnabled = false
                glassThreeDEnabled = false
                forceRichGraphics = false
                debugTierOverride = null
                // crownStyle is a real earned preference — preserved across
                // experimental lock, not reset.
            }
        }

    var sonarEnabled: Boolean
        get() = prefs.getBoolean(KEY_SONAR, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SONAR, value).apply()
            _sonarFlow.value = value
        }

    /** Real-glass sheen — the tilt-reactive highlight on chat bubbles.
     *  Off by default (it drives the accelerometer + per-frame redraws,
     *  so it has a battery cost). */
    var glassSheenEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLASS_SHEEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GLASS_SHEEN, value).apply()
            _glassSheenFlow.value = value
        }

    /** Real-glass 3D — the perspective pane-tilt above the sheen. A tier
     *  beyond [glassSheenEnabled]: the whole message pane appears to
     *  protrude and parallax as you tilt the phone. Off by default; it
     *  too drives the accelerometer + per-frame redraws. */
    var glassThreeDEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLASS_3D, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GLASS_3D, value).apply()
            _glassThreeDFlow.value = value
        }

    /** DEBUG tier override — lets a debug build preview each ShieldTier's
     *  medal treatment (chrome vs holographic foil) without actually earning
     *  the nodes. Stores the [app.aether.aegis.admin.ShieldTier] name, or null
     *  for "no override" (use the real node count). Honoured ONLY by debug
     *  builds — ShieldTierEngine gates the read on BuildConfig.HAS_DEBUG_CHANNEL
     *  — so it sits dormant/null in release. */
    var debugTierOverride: String?
        get() = prefs.getString(KEY_DEBUG_TIER, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_DEBUG_TIER) else putString(KEY_DEBUG_TIER, value)
            }.apply()
            _debugTierFlow.value = value
        }

    /** Crown shimmer style — 0 = white→cyan glow ([glassSheen]); 1 = diffraction
     *  rainbow foil; 2 = thin-film oil-slick foil. The EARNED Cyan-crown reward:
     *  the crown-holder picks how the mascot shimmers. Persisted (a real
     *  preference, not cleared on experimental-lock). */
    var crownStyle: Int
        get() = prefs.getInt(KEY_CROWN_STYLE, 0)
        set(value) {
            prefs.edit().putInt(KEY_CROWN_STYLE, value).apply()
            _crownStyleFlow.value = value
        }

    /** DEBUG force-real-glass — overrides the Voyager battery gate so the
     *  LunaGlass rich layer (tilt sheen, metal avatar shine, backdrops) stays
     *  ON even under 50 % battery. For developing/reviewing the glass effects
     *  without keeping the phone on a charger. Honoured ONLY by debug builds
     *  (Theme gates the read on BuildConfig.HAS_DEBUG_CHANNEL) — dormant in
     *  release. Cleared on experimental-lock. */
    var forceRichGraphics: Boolean
        get() = prefs.getBoolean(KEY_FORCE_RICH, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FORCE_RICH, value).apply()
            _forceRichFlow.value = value
        }

    val unlockedFlow: StateFlow<Boolean> get() = _unlockedFlow
    val sonarFlow: StateFlow<Boolean> get() = _sonarFlow
    val glassSheenFlow: StateFlow<Boolean> get() = _glassSheenFlow
    val glassThreeDFlow: StateFlow<Boolean> get() = _glassThreeDFlow
    /** Observed by the bottom nav so a debug tier change applies live. */
    val debugTierFlow: StateFlow<String?> get() = _debugTierFlow
    /** Observed by the nav/medals so the crown style applies live. */
    val crownStyleFlow: StateFlow<Int> get() = _crownStyleFlow
    /** Observed by AegisTheme so the force-real-glass override applies live. */
    val forceRichGraphicsFlow: StateFlow<Boolean> get() = _forceRichFlow

    /** Lock the section again — invalidates the 7-tap counter and
     *  drops every experimental toggle. */
    fun lock() {
        unlocked = false
    }

    companion object {
        private const val STORE_NAME = "aegis_experimental"
        private const val KEY_UNLOCKED = "unlocked"
        private const val KEY_SONAR = "sonar_enabled"
        private const val KEY_GLASS_SHEEN = "glass_sheen_enabled"
        private const val KEY_GLASS_3D = "glass_3d_enabled"
        private const val KEY_DEBUG_TIER = "debug_tier_override"
        private const val KEY_CROWN_STYLE = "crown_shimmer_style"
        private const val KEY_FORCE_RICH = "force_rich_graphics"

        @Volatile private var prefsInstance: SharedPreferences? = null
        private fun singletonPrefs(ctx: Context): SharedPreferences =
            prefsInstance ?: synchronized(this) {
                prefsInstance ?: ctx.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE).also {
                    prefsInstance = it
                }
            }

        private val _unlockedFlow by lazy { MutableStateFlow(false) }
        private val _sonarFlow by lazy { MutableStateFlow(false) }
        private val _glassSheenFlow by lazy { MutableStateFlow(false) }
        private val _glassThreeDFlow by lazy { MutableStateFlow(false) }
        private val _debugTierFlow by lazy { MutableStateFlow<String?>(null) }
        private val _crownStyleFlow by lazy { MutableStateFlow(0) }
        private val _forceRichFlow by lazy { MutableStateFlow(false) }
    }

    init {
        _unlockedFlow.value = unlocked
        _sonarFlow.value = sonarEnabled
        _glassSheenFlow.value = glassSheenEnabled
        _glassThreeDFlow.value = glassThreeDEnabled
        _debugTierFlow.value = debugTierOverride
        _crownStyleFlow.value = crownStyle
        _forceRichFlow.value = forceRichGraphics
    }
}

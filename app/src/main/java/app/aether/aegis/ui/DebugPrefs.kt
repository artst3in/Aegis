package app.aether.aegis.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Process-wide CompositionLocal for the debug overlay flag. */
val LocalDebugOverlay = compositionLocalOf { false }

/**
 * Debug overlay selection. Instead of one all-or-nothing toggle, the
 * overlay is now a MENU of independently-selectable items — the user
 * atomically picks what they want drawn:
 *
 *   - [Item.COUNTER]     FPS / heap / network text in the header.
 *   - [Item.GRAPH]       frame-time sparkline in the header.
 *   - [Item.COARSE_GRID] 8 dp alignment grid + bright centre lines,
 *                        drawn over the WHOLE window (header included) so
 *                        pixel-level placement — e.g. the centred lock
 *                        icon — can be eyeballed.
 *   - [Item.FINE_GRID]   2 dp alignment grid for sub-pixel nudging.
 *
 * Persisted as a string-set so the selection survives a restart. Backed
 * by a single process-wide StateFlow so flipping an item in Diagnostics
 * shows/hides it live everywhere. Diagnostic instrumentation, not part
 * of the LunaGlass spec — default empty (nothing drawn).
 */
class DebugPrefs(context: Context) {

    private val prefs: SharedPreferences =
        singletonPrefs(context.applicationContext)

    /** The selectable overlay items. [label]/[desc] drive the menu rows. */
    enum class Item(val key: String, val label: String, val desc: String) {
        COUNTER("counter", "Counters", "FPS · heap · network bytes in the header"),
        GRAPH("graph", "Frame graph", "Frame-time sparkline in the header"),
        COARSE_GRID("coarse_grid", "Coarse grid", "8 dp grid + centre lines (whole screen)"),
        FINE_GRID("fine_grid", "Fine grid", "2 dp grid for sub-pixel alignment"),
    }

    fun isOn(item: Item): Boolean = _enabled.value.contains(item.key)

    fun set(item: Item, on: Boolean) {
        val next = _enabled.value.toMutableSet().apply {
            if (on) add(item.key) else remove(item.key)
        }
        // Defensive copy — SharedPreferences keeps a reference to the set
        // it's handed, and our StateFlow value must not alias mutable
        // state that could change under it.
        prefs.edit().putStringSet(KEY_ENABLED, HashSet(next)).apply()
        _enabled.value = next
    }

    /** Live set of enabled item keys. Collect + check with [Item.key]. */
    val enabledFlow: StateFlow<Set<String>> get() = _enabled

    companion object {
        private const val STORE_NAME = "aegis_debug"
        // New string-set key; the legacy boolean "overlay_enabled" is
        // intentionally ignored (a dev-only toggle, not worth migrating).
        private const val KEY_ENABLED = "overlay_items"

        @Volatile private var prefsInstance: SharedPreferences? = null
        private fun singletonPrefs(ctx: Context): SharedPreferences =
            prefsInstance ?: synchronized(this) {
                prefsInstance ?: ctx.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE).also {
                    prefsInstance = it
                }
            }

        private val _enabled by lazy { MutableStateFlow<Set<String>>(emptySet()) }
    }

    init {
        _enabled.value = prefs.getStringSet(KEY_ENABLED, emptySet())?.toSet() ?: emptySet()
    }
}

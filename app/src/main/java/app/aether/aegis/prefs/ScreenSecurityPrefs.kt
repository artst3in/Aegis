package app.aether.aegis.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Screen-privacy preferences. Currently a single toggle: "block
 * screenshots", which backs Android's `FLAG_SECURE` on MainActivity's
 * window.
 *
 * `FLAG_SECURE` is one flag with three effects, all of which the
 * target user wants the moment they turn this on:
 *   - the OS refuses screenshots AND screen recording of Aegis,
 *   - the Recents / app-switcher thumbnail renders blank (no chat
 *     preview leaking to someone flipping through open apps),
 *   - the window won't mirror to non-secure external displays / casts.
 *
 * Default **ON**. Aegis's threat model is a phone
 * that gets seized, shoulder-surfed, or grabbed mid-use — exactly the
 * situations where a Recents preview or a casual screenshot leaks a
 * conversation. The secure-by-default posture wins over the
 * convenience cost (can't screenshot Aegis for a support thread,
 * blank Recents card); the user can opt OUT in Settings → Privacy if
 * they need to. Flipping it is instant and needs no restart: MainActivity
 * collects [blockFlow] and adds/clears the flag live, and also applies
 * the persisted value in `onCreate` so it's in force from the first
 * frame after a cold start.
 *
 * Exposed as both a `var` (read/write through to SharedPreferences)
 * AND a process-wide [StateFlow], so a write at the Settings call site
 * propagates to the Activity's collector immediately — the same
 * pattern GraphicsPrefs uses for its live toggles.
 */
class ScreenSecurityPrefs(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    /** When true, MainActivity holds `FLAG_SECURE` on its window. */
    var blockScreenshots: Boolean
        get() = prefs.getBoolean(KEY_BLOCK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BLOCK, value).apply()
            _blockFlow.value = value
        }

    /** Observable form — collected by MainActivity to add/clear
     *  `FLAG_SECURE` the instant the toggle flips, no restart. */
    val blockFlow: StateFlow<Boolean> get() = _blockFlow

    init {
        // Hydrate the process-wide flow from persisted state on first
        // construction (idempotent — defaults to false if never set).
        _blockFlow.value = prefs.getBoolean(KEY_BLOCK, true)
    }

    companion object {
        private const val STORE_NAME = "aegis_screen_security"
        private const val KEY_BLOCK = "block_screenshots"

        // Process-wide so a write at one call site (Settings) reaches
        // the Activity's collector regardless of which instance wrote.
        // Initial true matches the default-ON posture; init{} re-reads
        // the persisted value (honouring an explicit opt-out) anyway.
        private val _blockFlow = MutableStateFlow(true)
    }
}

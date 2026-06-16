package app.aether.aegis.gesture

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Settings for press-and-hold-to-execute on send / call / video-call
 * buttons. Guards against accidental triggers (most relevant:
 * tapping the video-call icon by mistake while scrolling chat).
 *
 * Off by default. When on, every button that opts in shows a fill
 * animation during the press and only fires on release if the hold
 * duration was met.
 */
class HoldToExecuteStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE_NAME),
            Context.MODE_PRIVATE,
        )

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /** Hold duration in milliseconds. 200 / 500 / 1000. */
    var durationMs: Long
        get() = prefs.getLong(KEY_DURATION_MS, 500L)
        set(value) {
            prefs.edit().putLong(KEY_DURATION_MS, value.coerceIn(100L, 3000L)).apply()
        }

    companion object {
        private const val STORE_NAME = "aegis_hold_to_execute"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_DURATION_MS = "duration_ms"

        /**
         * Reactive (enabled, durationMs) for live UI. Re-emits whenever
         * either pref is written, so a toggle on the settings screen
         * propagates to an already-open chat/call screen WITHOUT an app
         * restart — consumers previously read the store once via `remember{}`,
         * which is why the old copy told the user to restart. Backed by a
         * SharedPreferences change listener registered for the flow's
         * collection lifetime (unregistered on close).
         */
        fun settingsFlow(context: Context): Flow<Pair<Boolean, Long>> = callbackFlow {
            val store = HoldToExecuteStore(context)
            val prefs = context.getSharedPreferences(
                app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE_NAME),
                Context.MODE_PRIVATE,
            )
            trySend(store.enabled to store.durationMs)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                trySend(store.enabled to store.durationMs)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }
}

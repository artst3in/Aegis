package app.aether.aegis.mugshot

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings for the "failed PIN → silent front-camera capture" feature.
 *
 * Off by default. When enabled, the lock screen monitors failed PIN
 * attempts; after [triggerThreshold] consecutive wrong PINs the front
 * camera silently captures a still and ships it to every paired peer
 * as a normal photo (caption tag `[aegis:mugshot]`).
 */
class MugshotStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /** How many wrong PINs in a row before we capture. 3 by default. */
    var triggerThreshold: Int
        get() = prefs.getInt(KEY_THRESHOLD, 3).coerceIn(1, 10)
        set(value) { prefs.edit().putInt(KEY_THRESHOLD, value.coerceIn(1, 10)).apply() }

    /** True once a capture has fired this session — prevents repeated
     *  captures on the same lockout streak. Reset on successful unlock. */
    var firedThisStreak: Boolean
        get() = prefs.getBoolean(KEY_FIRED, false)
        set(value) { prefs.edit().putBoolean(KEY_FIRED, value).apply() }

    private companion object {
        private const val STORE_NAME = "aegis_mugshot"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_FIRED = "fired"
    }
}

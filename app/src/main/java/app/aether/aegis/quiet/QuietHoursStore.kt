package app.aether.aegis.quiet

import android.content.Context
import android.content.SharedPreferences

/**
 * Night auto-silence — mutes notification sounds during a configured
 * window (default 23:00–07:00). SOS alerts always override; this
 * is for chat / status / story notifications only.
 *
 * Times stored as minutes-since-midnight (0..1439) so the wrap-around
 * window (start > end) is naturally expressible.
 */
class QuietHoursStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE_NAME),
            Context.MODE_PRIVATE,
        )

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    var startMinute: Int
        get() = prefs.getInt(KEY_START, 23 * 60)
        set(value) { prefs.edit().putInt(KEY_START, value.coerceIn(0, 1439)).apply() }

    var endMinute: Int
        get() = prefs.getInt(KEY_END, 7 * 60)
        set(value) { prefs.edit().putInt(KEY_END, value.coerceIn(0, 1439)).apply() }

    /** True iff right now is inside the quiet window. Wraps around
     *  midnight cleanly when start > end (e.g. 23:00 → 07:00 means
     *  03:00 IS quiet). */
    fun isQuietNow(): Boolean {
        if (!enabled) return false
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            cal.get(java.util.Calendar.MINUTE)
        val s = startMinute
        val e = endMinute
        return if (s <= e) nowMin in s..e
        else nowMin >= s || nowMin <= e
    }

    private companion object {
        private const val STORE_NAME = "aegis_quiet"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_START = "start_min"
        private const val KEY_END = "end_min"
    }
}

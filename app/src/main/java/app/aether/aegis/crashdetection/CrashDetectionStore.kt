package app.aether.aegis.crashdetection

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings for vehicle crash detection.
 * Per-profile, OFF by default — a purely protective feature with no
 * attack-surface expansion.
 *
 * One user-facing control: a [sensitivity] (Low/Med/High) that sets
 * both the impact G-threshold and the speed gate together, per the
 * spec's table:
 *
 * | Low  | >6G | >40 km/h | highway, fewest false positives |
 * | Med  | >4G | >25 km/h | default, most cars              |
 * | High | >3G | >15 km/h | motorcycles / bicycles          |
 */
class CrashDetectionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE_NAME),
            Context.MODE_PRIVATE,
        )

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply() }

    /** 0 = Low, 1 = Medium (default), 2 = High. */
    var sensitivity: Int
        get() = prefs.getInt(KEY_SENS, 1).coerceIn(0, 2)
        set(v) { prefs.edit().putInt(KEY_SENS, v.coerceIn(0, 2)).apply() }

    /** Impact threshold in g for the current [sensitivity]. */
    val impactThresholdG: Float
        get() = when (sensitivity) {
            0 -> 6f
            2 -> 3f
            else -> 4f
        }

    /** Speed gate in km/h for the current [sensitivity] — crash
     *  monitoring only arms above this. */
    val speedGateKmh: Float
        get() = when (sensitivity) {
            0 -> 40f
            2 -> 15f
            else -> 25f
        }

    companion object {
        val SENSITIVITY_LABELS = listOf("Low", "Medium", "High")

        /** "Low · >6G above 40 km/h" style summary for the slider. */
        fun summary(sensitivity: Int): String = when (sensitivity.coerceIn(0, 2)) {
            0 -> "Low — >6G above 40 km/h (highway)"
            2 -> "High — >3G above 15 km/h (motorcycle/bike)"
            else -> "Medium — >4G above 25 km/h (most cars)"
        }

        private const val STORE_NAME = "aegis_crash_detection"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SENS = "sensitivity"
    }
}

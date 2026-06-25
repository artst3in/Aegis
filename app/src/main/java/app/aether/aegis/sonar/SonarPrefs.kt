package app.aether.aegis.sonar

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted Sonar tuning — survives process restart so manual
 * slider tweaks and auto-calibration results don't reset every
 * time the app gets killed.
 *
 * Lives in its own store rather than the LunaGlass/Graphics prefs
 * because Sonar is unrelated to the visual layer.
 */
class SonarPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    var frequencyHz: Int
        get() = prefs.getInt(KEY_FREQ, DEFAULT_FREQ)
        set(value) { prefs.edit().putInt(KEY_FREQ, value.coerceIn(18_000, 22_000)).apply() }

    var amplitude: Float
        get() = prefs.getFloat(KEY_AMP, DEFAULT_AMP)
        set(value) { prefs.edit().putFloat(KEY_AMP, value.coerceIn(0.1f, 1.0f)).apply() }

    var threshold: Double
        get() = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD.toFloat()).toDouble()
        set(value) { prefs.edit().putFloat(KEY_THRESHOLD, value.toFloat().coerceAtLeast(0.00001f)).apply() }

    var calibratedAt: Long
        get() = prefs.getLong(KEY_CALIBRATED_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_CALIBRATED_AT, value).apply() }

    companion object {
        private const val STORE_NAME = "aegis_sonar"
        private const val KEY_FREQ = "freq_hz"
        private const val KEY_AMP  = "amplitude"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_CALIBRATED_AT = "calibrated_at"

        private const val DEFAULT_FREQ = 19_000
        private const val DEFAULT_AMP  = 0.6f
        private const val DEFAULT_THRESHOLD = 0.0001
    }
}

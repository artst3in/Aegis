package app.aether.aegis.geofence

import android.content.Context
import android.content.SharedPreferences

/**
 * One geofence per device. User configures a circular zone (centre +
 * radius). When their location exits the zone, Aegis fires a silent
 * alert to every paired peer. They can pause for X hours; auto-resumes.
 *
 * Crucially: the watched person is the same person who configured the
 * geofence. The consent is continuous — the "geofence active" indicator
 * stays visible while it's armed. No covert-tracking shape.
 */
class GeofenceStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE_NAME),
            Context.MODE_PRIVATE,
        )

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /** Zone centre, decimal degrees. NaN = not configured. */
    var centerLat: Double
        get() = java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_CENTER_LAT, java.lang.Double.doubleToRawLongBits(Double.NaN))
        )
        set(value) {
            prefs.edit().putLong(
                KEY_CENTER_LAT, java.lang.Double.doubleToRawLongBits(value),
            ).apply()
        }

    var centerLng: Double
        get() = java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_CENTER_LNG, java.lang.Double.doubleToRawLongBits(Double.NaN))
        )
        set(value) {
            prefs.edit().putLong(
                KEY_CENTER_LNG, java.lang.Double.doubleToRawLongBits(value),
            ).apply()
        }

    /** Zone radius, metres. Default 500 m. */
    var radiusMeters: Int
        get() = prefs.getInt(KEY_RADIUS, 500)
        set(value) { prefs.edit().putInt(KEY_RADIUS, value.coerceIn(50, 50_000)).apply() }

    /** Epoch ms until which the geofence is paused. 0 = not paused. */
    var pausedUntil: Long
        get() = prefs.getLong(KEY_PAUSED_UNTIL, 0L)
        set(value) { prefs.edit().putLong(KEY_PAUSED_UNTIL, value).apply() }

    /** Last fence-state we observed — true = inside, false = outside,
     *  null = unknown (haven't evaluated yet). The unknown state is
     *  critical: defaulting to "inside" caused a spurious alert the
     *  first time a GPS fix landed outside the zone (e.g. user sets
     *  the zone at home while currently at work). On unknown we
     *  seed with the current eval without firing. */
    var lastInside: Boolean?
        get() {
            val s = prefs.getString(KEY_LAST_INSIDE, null) ?: return null
            return s == "true"
        }
        set(value) {
            val e = prefs.edit()
            if (value == null) e.remove(KEY_LAST_INSIDE)
            else e.putString(KEY_LAST_INSIDE, value.toString())
            e.apply()
        }

    val isConfigured: Boolean
        get() = !centerLat.isNaN() && !centerLng.isNaN()

    val isPausedNow: Boolean
        get() = System.currentTimeMillis() < pausedUntil

    /** True iff the fence should actively monitor right now. */
    val isActive: Boolean
        get() = enabled && isConfigured && !isPausedNow

    fun pauseFor(hours: Int) {
        pausedUntil = System.currentTimeMillis() + hours * 3600L * 1000L
    }

    fun resumeNow() { pausedUntil = 0L }

    private companion object {
        private const val STORE_NAME = "aegis_geofence"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CENTER_LAT = "center_lat"
        private const val KEY_CENTER_LNG = "center_lng"
        private const val KEY_RADIUS = "radius_m"
        private const val KEY_PAUSED_UNTIL = "paused_until"
        private const val KEY_LAST_INSIDE = "last_inside"
    }
}

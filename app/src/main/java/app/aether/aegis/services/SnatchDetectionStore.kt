package app.aether.aegis.services

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-profile setting for accelerometer snatch detection (a hard
 * throw/grab/yank → sos broadcast).
 *
 * **OFF by default, deliberately.** The g-force heuristic false-triggers
 * real sos broadcasts to emergency contacts on ordinary jolts — a
 * dropped or tossed phone reads identically to a snatch. So the user must
 * opt in explicitly (Diagnostics → Snatch detection). While off,
 * [ProtocolService] never registers the ~50 Hz accelerometer at all
 * (also a power win), and even if it did the sos trigger is gated on
 * the same flag.
 *
 * Mirrors [app.aether.aegis.crashdetection.CrashDetectionStore]'s
 * per-profile SharedPreferences pattern.
 */
class SnatchDetectionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE_NAME),
            Context.MODE_PRIVATE,
        )

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply() }

    companion object {
        private const val STORE_NAME = "aegis_snatch_detection"
        private const val KEY_ENABLED = "enabled"
    }
}

package app.aether.aegis.canary

import android.content.Context
import android.content.SharedPreferences

/**
 * Dead-man's switch — author one message, set a check-in interval,
 * and Aegis auto-sends to every paired peer if you stop checking in.
 *
 * "Check-in" = any app foreground event. Just opening Aegis resets
 * the clock. Owner doesn't have to remember to do anything special.
 *
 * Per-user, not per-peer: the canary belongs to the author. Recipient
 * list is "all paired peers" by default; selecting individual
 * recipients is a v2 refinement.
 */
class CanaryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE_NAME),
            Context.MODE_PRIVATE,
        )

    /** True iff the canary feature is armed. False = entirely disabled. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /** The message body that fires when the canary triggers. */
    var message: String
        get() = prefs.getString(KEY_MESSAGE, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_MESSAGE, value).apply() }

    /** How long without a check-in before the canary fires.
     *  Defaults to 24 h. UI offers 12 / 24 / 48 h. */
    var intervalMs: Long
        get() = prefs.getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)
        set(value) { prefs.edit().putLong(KEY_INTERVAL_MS, value).apply() }

    /** Last time the user opened the app (foregrounded). The canary
     *  worker uses this to decide whether to fire. */
    var lastCheckInAt: Long
        get() = prefs.getLong(KEY_LAST_CHECKIN, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_CHECKIN, value).apply() }

    /** True once the canary has fired in the current absence period —
     *  prevents duplicate sends if the worker re-runs while the user
     *  is still away. Cleared on next successful check-in. */
    var alreadyFired: Boolean
        get() = prefs.getBoolean(KEY_ALREADY_FIRED, false)
        set(value) { prefs.edit().putBoolean(KEY_ALREADY_FIRED, value).apply() }

    /** Stamp a fresh check-in. Called whenever the app foregrounds. */
    fun recordCheckIn() {
        val edit = prefs.edit().putLong(KEY_LAST_CHECKIN, System.currentTimeMillis())
        if (alreadyFired) edit.putBoolean(KEY_ALREADY_FIRED, false)
        edit.apply()
    }

    /** True when the canary should fire NOW — enabled, message set,
     *  past interval, and not already fired in this absence period. */
    fun isOverdue(): Boolean {
        if (!enabled || message.isBlank() || alreadyFired) return false
        if (lastCheckInAt == 0L) return false   // never checked in = never armed
        return System.currentTimeMillis() - lastCheckInAt >= intervalMs
    }

    private companion object {
        private const val STORE_NAME = "aegis_canary"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MESSAGE = "message"
        private const val KEY_INTERVAL_MS = "interval_ms"
        private const val KEY_LAST_CHECKIN = "last_checkin"
        private const val KEY_ALREADY_FIRED = "already_fired"
        private const val DEFAULT_INTERVAL_MS = 24L * 60 * 60 * 1000  // 24 h
    }
}

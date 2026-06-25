package app.aether.aegis.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * How long a generated-but-unused 1:1 invitation link survives before
 * the auto-expire worker revokes it.
 *
 * Per-profile (routed through [app.aether.aegis.profile.ProfileRegistry]
 * like the other prefs). Stored as an hour count; **0 = never expire**.
 * Default is 24 h — deliberately aggressive for the at-risk user,
 * because a link left live on the relay is a standing exposure anyone
 * who finds it can still use. The user can loosen or disable it.
 */
class InvitationExpiryPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE_NAME),
            Context.MODE_PRIVATE,
        )

    /** Expiry window in hours. 0 = never. Default [DEFAULT_HOURS]. */
    var expiryHours: Int
        get() = prefs.getInt(KEY_HOURS, DEFAULT_HOURS)
        set(value) {
            prefs.edit().putInt(KEY_HOURS, value.coerceAtLeast(0)).apply()
        }

    companion object {
        const val DEFAULT_HOURS = 24

        /** Selectable presets (hours), in menu order; 0 = never. */
        val PRESETS = listOf(1, 6, 24, 168, 0)

        /** Human label for a preset hour count. */
        fun label(hours: Int): String = when (hours) {
            0 -> "Never"
            1 -> "1 hour"
            6 -> "6 hours"
            24 -> "24 hours"
            168 -> "7 days"
            else -> "$hours h"
        }

        private const val STORE_NAME = "aegis_invitation_expiry"
        private const val KEY_HOURS = "expiry_hours"
    }
}

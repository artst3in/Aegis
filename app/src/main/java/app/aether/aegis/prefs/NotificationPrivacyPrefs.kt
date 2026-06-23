package app.aether.aegis.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * How much of a notification's content is shown on the shade / lock
 * screen. For a safety app this is not cosmetic: anyone who can see the
 * notification shade can see who is messaging the user and what they say,
 * which in a coercive-control situation is exactly the leak we exist to
 * prevent.
 *
 * Three levels, modelled on Signal / SimpleX. Stored as the ordinal int
 * (0/1/2) so the on-disk value is stable even if labels change.
 *
 * Device-global (NOT per-profile): the shade is a single physical surface
 * shared by whoever is looking at the phone, so the privacy choice has to
 * apply regardless of which profile is active.
 */
enum class NotificationPrivacy {
    /** Sender name + message text — the default, most useful. */
    FULL,

    /** Sender name (or group name) but the body is redacted to a generic
     *  "New message". You can see who, not what. */
    NAME_ONLY,

    /** Neither name nor content: a generic "Aegis / New message". Nothing
     *  about your contacts or conversations leaks to a shoulder-surfer. */
    HIDDEN;

    companion object {
        /** Map a persisted ordinal back to a level, clamping anything
         *  out of range to the safe default ([FULL]). */
        fun fromOrdinal(value: Int): NotificationPrivacy =
            entries.getOrElse(value) { FULL }
    }
}

/**
 * Persisted [NotificationPrivacy] choice. Reads are cheap and synchronous
 * so notification builders can consult it inline on the posting path.
 */
class NotificationPrivacyPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    /** Current privacy level. Defaults to [NotificationPrivacy.FULL]. */
    var level: NotificationPrivacy
        get() = NotificationPrivacy.fromOrdinal(prefs.getInt(KEY_LEVEL, 0))
        set(value) { prefs.edit().putInt(KEY_LEVEL, value.ordinal).apply() }

    private companion object {
        private const val STORE_NAME = "aegis_notification_privacy"
        private const val KEY_LEVEL = "level"
    }
}

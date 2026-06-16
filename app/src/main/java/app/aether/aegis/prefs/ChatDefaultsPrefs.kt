package app.aether.aegis.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Default policies applied to new chats. Per-profile (routed through
 * [app.aether.aegis.profile.ProfileRegistry] so two profiles can carry different
 * defaults — Family A might want 24 h disappearing on every new
 * contact, Family B might leave it off).
 *
 * Today: one knob — `defaultDisappearingTtlSeconds`. Null = no
 * default, new chats keep messages forever (status quo). Non-null =
 * every new known-peer row gets that TTL applied at creation. Existing
 * peers are untouched — the per-contact TTL on
 * [app.aether.aegis.data.KnownPeerEntity.disappearingTtl] is the authoritative
 * value and the default only seeds it.
 *
 * Default-disappearing-messages (a Signal-steal item). Skipped if
 * Vault ships first.
 */
class ChatDefaultsPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE_NAME),
            Context.MODE_PRIVATE,
        )

    /** Default disappearing-messages TTL applied to a new peer when
     *  they are first added. Null = forever (no auto-burn). */
    var defaultDisappearingTtlSeconds: Long?
        get() = prefs.getLong(KEY_TTL, -1L).takeIf { it > 0L }
        set(value) {
            prefs.edit().apply {
                if (value == null || value <= 0L) remove(KEY_TTL)
                else putLong(KEY_TTL, value)
            }.apply()
        }

    private companion object {
        private const val STORE_NAME = "aegis_chat_defaults"
        private const val KEY_TTL = "default_disappearing_ttl"
    }
}

package app.aether.aegis.profile

import android.content.Context
import android.content.SharedPreferences

/**
 * Aegis user profile — stored locally only.
 *
 * Stored in app-private SharedPreferences (FDE-protected on Android,
 * same security boundary as the identity.key file). The avatar lives
 * in filesDir/avatar.<ext>; the prefs hold the path so we can swap
 * formats later without breaking compatibility.
 *
 * AEGIS PROTOCOL: this profile is NEVER pushed to the SimpleX core.
 * The setProfile() method that synced to /_profile was deleted
 * (Origin #19). Real identity travels only through the E2E
 * [aegis:identity] overlay on trust elevation
 * (SimpleXTransport.sendIdentityEnvelope).
 */
class ProfileStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            ProfileRegistry.get(context).current.prefsName(NAME),
            Context.MODE_PRIVATE,
        )

    var displayName: String
        get() = prefs.getString(KEY_NAME, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_NAME, value.trim()).apply() }

    var bio: String
        get() = prefs.getString(KEY_BIO, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_BIO, value.trim()).apply() }

    var avatarPath: String?
        get() = prefs.getString(KEY_AVATAR, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_AVATAR)
                else putString(KEY_AVATAR, value)
            }.apply()
        }

    /** True once the user has finished the first-launch onboarding. */
    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) { prefs.edit().putBoolean(KEY_ONBOARDED, value).apply() }

    fun snapshot() = Profile(displayName, bio, avatarPath)

    private companion object {
        private const val NAME = "aegis_profile"
        private const val KEY_NAME      = "display_name"
        private const val KEY_BIO       = "bio"
        private const val KEY_AVATAR    = "avatar_path"
        private const val KEY_ONBOARDED = "onboarded"
    }
}

data class Profile(
    val displayName: String,
    val bio: String,
    val avatarPath: String?,
)

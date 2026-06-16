package app.aether.aegis.update

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny key-value store for app secrets the user pastes in (currently
 * just a GitHub Personal Access Token for the private-repo update
 * channel). Lives in app-private SharedPreferences which on a modern
 * Android is FDE-protected — same security boundary as the identity
 * keypair file. Not as strong as AndroidKeyStore-backed
 * EncryptedSharedPreferences, but the AndroidX security-crypto library
 * is deprecated and overkill here.
 */
class SecretsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    /**
     * GitHub PAT for the private-repo update channel. Lookup order:
     *  1. User-pasted value in SharedPreferences (overrides bundled).
     *  2. BuildConfig.BUNDLED_GITHUB_PAT — baked in at build time so
     *     friends installing a shared APK get auto-updates without
     *     having to copy a token.
     *  3. null — anonymous, which 404s on private repos.
     *
     * Setting null/blank clears the override; the bundled fallback
     * (if any) still applies on next read.
     */
    var githubToken: String?
        get() = prefs.getString(KEY_GITHUB_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: app.aether.aegis.BuildConfig.BUNDLED_GITHUB_PAT.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_GITHUB_TOKEN)
                else putString(KEY_GITHUB_TOKEN, value.trim())
            }.apply()
        }

    /** True iff the user has explicitly set a token (vs falling back to BUNDLED). */
    val hasUserOverride: Boolean
        get() = !prefs.getString(KEY_GITHUB_TOKEN, null).isNullOrBlank()

    private companion object {
        private const val STORE_NAME = "aegis_secrets"
        private const val KEY_GITHUB_TOKEN = "github_pat"
    }
}

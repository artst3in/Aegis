package app.aether.aegis.prefs

import android.content.Context
import app.aether.aegis.AegisApp

/**
 * Per-chat unsent-draft persistence.
 *
 * Compose state is lost the instant a chat screen leaves composition, so a
 * typed-but-unsent message vanished the moment you switched away — you had
 * to send before leaving or lose it (user-reported). This keeps the draft
 * keyed by the conversation id (a direct peer key, or `group:<id>` for a
 * group) so it survives navigation: restored on return, cleared on send.
 *
 * Plain SharedPreferences, local-only. The draft is the user's OWN unsent
 * text and is cleared the moment it's sent. (Sealing it under the phrase key
 * is a possible future hardening, but it must be writable while unlocked —
 * which it always is here, since you're actively typing.)
 */
object DraftStore {

    private const val STORE = "aegis_drafts"

    private fun prefs() =
        AegisApp.instance.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** The saved draft for [key], or "" if none. */
    fun get(key: String): String = prefs().getString(key, "").orEmpty()

    /** Persist the draft for [key] — or clear it when [text] is blank (so a
     *  sent message, which blanks the field, removes the row automatically). */
    fun set(key: String, text: String) {
        prefs().edit().apply {
            if (text.isBlank()) remove(key) else putString(key, text)
        }.apply()
    }

    fun clear(key: String) = prefs().edit().remove(key).apply()
}

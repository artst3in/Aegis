package app.aether.aegis.prefs

import android.content.Context

/**
 * Per-group mute state. SharedPreferences-backed so no Room
 * migration is needed for a simple boolean-per-group flag. The
 * notification dispatch path consults [isMuted] before posting any
 * group message banner / sound / vibration; muted groups still
 * record the message into the conversation, they just don't pull
 * the user's attention.
 *
 * Stored as a CSV of muted group ids under a single key. Reading
 * is a constant-time HashSet check after the first call.
 */
class GroupMutePrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREF_NAME, Context.MODE_PRIVATE,
    )

    @Volatile private var cache: Set<String>? = null

    private fun snapshot(): Set<String> = cache ?: run {
        val csv = prefs.getString(KEY_MUTED, "").orEmpty()
        val parsed = if (csv.isEmpty()) emptySet()
                     else csv.split(',').filter { it.isNotBlank() }.toHashSet()
        cache = parsed
        parsed
    }

    fun isMuted(groupId: String): Boolean = groupId in snapshot()

    fun setMuted(groupId: String, muted: Boolean) {
        val cur = snapshot().toHashSet()
        val changed = if (muted) cur.add(groupId) else cur.remove(groupId)
        if (!changed) return
        cache = cur
        prefs.edit().putString(KEY_MUTED, cur.joinToString(",")).apply()
    }

    private companion object {
        const val PREF_NAME = "group_mute"
        const val KEY_MUTED = "muted_ids"
    }
}

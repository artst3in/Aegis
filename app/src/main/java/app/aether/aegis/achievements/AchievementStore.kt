package app.aether.aegis.achievements

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-profile store of which [Achievement] badges this user has earned
 * and when. Flags only — no Room table, no
 * migration, by design.
 *
 * Storage shape: one key per badge, `earned_at_<id>` → epoch-ms of when
 * it was first earned. Presence of the key IS "earned"; the value is
 * the timestamp for the "earned <date>" label. Earn-once / no-decay:
 * once set, never cleared by the app.
 *
 * Routed through [app.aether.aegis.profile.ProfileRegistry] like the
 * other per-profile prefs so two profiles keep separate badge sets.
 */
class AchievementStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE_NAME),
            Context.MODE_PRIVATE,
        )

    fun isEarned(a: Achievement): Boolean = prefs.contains(key(a.id))

    /** Epoch-ms when [a] was earned, or null if not earned. */
    fun earnedAt(a: Achievement): Long? =
        prefs.getLong(key(a.id), -1L).takeIf { it > 0L }

    /**
     * Mark [a] earned if it isn't already. Idempotent — a no-op once
     * set (earn-once). Returns true only on the transition from
     * unearned → earned, so the caller can fire a "new badge" effect /
     * broadcast exactly once.
     */
    fun unlockOnce(a: Achievement): Boolean {
        if (prefs.contains(key(a.id))) return false
        prefs.edit().putLong(key(a.id), System.currentTimeMillis()).apply()
        return true
    }

    /** Stable ids of every earned badge — the payload shared with
     *  trusted contacts. */
    fun earnedIds(): Set<String> =
        Achievement.entries.filter { isEarned(it) }.map { it.id }.toSet()

    private fun key(id: String) = "earned_at_$id"

    companion object {
        private const val STORE_NAME = "aegis_achievements"
    }
}

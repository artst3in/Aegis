package app.aether.aegis.achievements

import android.util.Log
import app.aether.aegis.AegisApp

/**
 * The single entry point security handlers call to award a badge.
 * Drop `Achievements.unlock(X)` at a
 * capability's EXISTING success point — nothing else.
 *
 * SAFETY: this call is
 * **bulletproof — it never throws.** Everything it touches runs inside
 * a catch-all, so an exception in the achievement layer can never
 * propagate up and crash the sos / remote / duress handler that
 * called it. The achievement layer dies silently; it never takes
 * security down with it. This is what lets us guarantee that deleting
 * the achievement code leaves every feature behaving identically.
 *
 * Do NOT add any logic here that a security flow's correctness depends
 * on, and never call this BEFORE the security action it observes — it
 * is a passive witness, downstream of success, only.
 */
object Achievements {

    private const val TAG = "Achievements"

    /**
     * Record that [a] was earned, if it hasn't been already. On the
     * first time (and only then), announce the updated badge set to
     * trusted contacts as a verification signal. Total function: any
     * failure is swallowed and logged, never rethrown.
     */
    fun unlock(a: Achievement) {
        runCatching {
            val app = AegisApp.instance
            val store = AchievementStore(app)
            if (store.unlockOnce(a)) {
                Log.i(TAG, "earned badge: ${a.id}")
                // Newly earned → share the set with trusted contacts.
                // Also inside the outer runCatching, so a transport
                // hiccup can't bubble into the security handler.
                AchievementBroadcaster.broadcastEarnedToTrusted(store.earnedIds())
            }
        }.onFailure {
            // Deliberately swallowed — see the class KDoc. A broken
            // achievement layer must NEVER break a security feature.
            Log.w(TAG, "unlock(${a.id}) failed — ignored", it)
        }
    }

    /**
     * Re-announce the CURRENT earned badge set to every trusted contact,
     * independent of a fresh [unlock]. Without this, badges earned
     * BEFORE a contact was promoted to Trusted (or before both sides ran
     * a build that routes the `[aegis:badges]` envelope correctly) never
     * reach them — so the peer's "trusted contact" card shows an empty
     * achievements row forever. Call on app foreground so the set keeps
     * converging. No-op when nothing is earned (we never send an empty
     * envelope). Bulletproof like [unlock]: any failure is swallowed.
     */
    fun resyncToTrusted() {
        runCatching {
            val ids = AchievementStore(AegisApp.instance).earnedIds()
            if (ids.isNotEmpty()) {
                AchievementBroadcaster.broadcastEarnedToTrusted(ids)
            }
        }.onFailure { Log.w(TAG, "resyncToTrusted failed — ignored", it) }
    }
}

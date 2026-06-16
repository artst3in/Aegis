package app.aether.aegis.profile

import android.content.Context
import android.util.Log
import app.aether.aegis.AegisApp
import app.aether.aegis.simplex.SimpleXTransport

/**
 * Lifecycle orchestrator for ephemeral profiles, built
 * on the honest "wiped on lock" model (NOT "never
 * written to disk"; the SimpleX core can't run in-memory, so an ephemeral
 * profile writes like any other and is destroyed as a unit on lock).
 *
 * The unit of isolation is a DEDICATED SimpleX user: an ephemeral profile
 * gets its own SimpleX user ([SimpleXTransport.createSimplexUser]) so its
 * contacts + history can be removed cleanly with
 * [SimpleXTransport.deleteSimplexUser] without touching any other
 * profile's data (all Aegis profiles otherwise share one SimpleX user).
 *
 * Three lifecycle moments:
 *   - [activateIfEphemeral] (on start): switch the core to this profile's
 *     own SimpleX user, creating it on first run. Non-ephemeral profiles
 *     are untouched — the existing single shared-user flow is unchanged.
 *   - [scheduleWipeIfEphemeral] (on lock): mark the wipe + switch to a
 *     surviving profile, then the caller restarts. The heavy delete is
 *     DEFERRED off the lock event (no blocking work / process death
 *     mid-lock) and is fail-safe — the flag survives a force-kill.
 *   - [processPendingWipes] (on start): complete any scheduled wipe —
 *     delete the SimpleX user + recursively delete the Aegis footprint.
 *
 * Forensic caveat (documented, not hidden): deletion is `File.delete` +
 * SimpleX `/_delete user`, not a secure overwrite — residual sectors /
 * WAL fragments may survive until the OS reuses the space. "Wiped on
 * lock", not "unrecoverable".
 */
object EphemeralProfile {
    private const val TAG = "EphemeralProfile"

    private fun transport(): SimpleXTransport? =
        AegisApp.instance.transports.filterIsInstance<SimpleXTransport>().firstOrNull()

    /**
     * Begin a user-initiated switch INTO ephemeral profile [id]: mark the
     * entering one-shot, set it active, and request the kill-restart the
     * profile switch normally does. On the restart, [onStart] sees the
     * entering flag and ACTIVATES (rather than wiping) the profile.
     */
    fun enterEphemeral(context: Context, id: String) {
        val reg = ProfileRegistry.get(context)
        reg.markEnteringEphemeral(id)
        reg.setActiveProfile(id)
    }

    /**
     * Single start-time entry point (call once the SimpleX core is up).
     * In order:
     *   1. Finish any scheduled wipes from a previous lock.
     *   2. If the active profile is ephemeral:
     *      - entering one-shot set → we just switched in: ACTIVATE (create
     *        its SimpleX user on first run, select it).
     *      - else → a reboot left an ephemeral profile active; it must NOT
     *        survive a reboot, so schedule its wipe + switch away +
     *        restart. ([processPendingWipes] then finishes it.)
     */
    suspend fun onStart(context: Context, requestRestart: () -> Unit) {
        processPendingWipes(context)
        val reg = ProfileRegistry.get(context)
        val id = reg.activeProfileId
        if (!reg.isEphemeral(id)) return
        if (reg.consumeEnteringEphemeral(id)) {
            activate(context, id)
        } else {
            // Reboot (or any cold start) with an ephemeral profile active:
            // it didn't survive. Schedule the wipe, switch away, restart.
            if (scheduleWipeIfEphemeral(context)) requestRestart()
        }
    }

    /** Ensure the ephemeral profile [id] has its own SimpleX user and
     *  make it the active core user. Idempotent. */
    private suspend fun activate(context: Context, id: String) {
        val t = transport() ?: return
        var uid = ProfileRegistry.get(context).simplexUserId(id)
        if (uid == null) {
            uid = t.createSimplexUser() ?: run {
                Log.w(TAG, "couldn't create SimpleX user for ephemeral $id")
                return
            }
            ProfileRegistry.get(context).setSimplexUserId(id, uid)
        }
        t.setActiveSimplexUser(uid)
        Log.i(TAG, "ephemeral $id active on SimpleX user $uid")
    }

    /**
     * On lock: if the active profile is ephemeral, schedule its wipe and
     * switch the active profile to a surviving (non-ephemeral) one. The
     * caller restarts the process; [processPendingWipes] finishes the job
     * on the next start. Returns true if a wipe was scheduled (the caller
     * should then kill the process to restart clean).
     */
    fun scheduleWipeIfEphemeral(context: Context): Boolean {
        val reg = ProfileRegistry.get(context)
        val id = reg.activeProfileId
        if (!reg.isEphemeral(id)) return false
        val survivor = reg.listProfiles().firstOrNull { it != id && !reg.isEphemeral(it) }
            ?: ProfileRoot.DEFAULT_PROFILE_ID
        reg.scheduleEphemeralWipe(id, reg.simplexUserId(id) ?: -1L)
        reg.setActiveProfile(survivor)
        // The survivor must come up LOCKED so the user re-authenticates and the
        // PIN-derived seal loads — otherwise it restarts unlocked-but-sealed
        // (chats locked, no PIN prompt). No-op at unlock time for a survivor
        // with no PIN (LockState only forces the gate when a PIN exists).
        reg.armLockOnNextStart()
        Log.i(TAG, "scheduled ephemeral wipe of $id; switching to $survivor")
        return true
    }

    /**
     * On start: complete any scheduled ephemeral wipes — delete each
     * scheduled profile's SimpleX user and recursively delete its Aegis
     * footprint. Must run before the profile is used again so a wiped
     * ephemeral leaves no live data.
     */
    suspend fun processPendingWipes(context: Context) {
        val reg = ProfileRegistry.get(context)
        val pending = reg.pendingEphemeralWipes()
        if (pending.isEmpty()) return
        val t = transport()
        for ((id, uid) in pending) {
            if (uid > 0 && t != null) {
                runCatching { t.deleteSimplexUser(uid) }
                    .onFailure { Log.w(TAG, "deleteSimplexUser $uid failed", it) }
            }
            reg.forceDeleteProfileFootprint(id)
            reg.clearPendingEphemeralWipe(id)
            Log.i(TAG, "ephemeral wipe of $id complete")
        }
    }
}

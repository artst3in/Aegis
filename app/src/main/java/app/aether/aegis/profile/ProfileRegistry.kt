package app.aether.aegis.profile

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Registry of installed profiles plus a one-time migration that moves
 * pre-Phase-1 data (everything sitting at the top of [Context.getFilesDir]
 * because it was written before multi-profile existed) into the
 * default profile's root.
 *
 * Phase 1 single-profile: registry knows exactly one profile, with
 * id [ProfileRoot.DEFAULT_PROFILE_ID]. Phase 2 will add list/create/
 * delete + active-profile switching. The interface is shaped now so
 * Phase 2 doesn't need to retrofit it onto every caller.
 *
 * Threading: all methods are safe to call from the main thread.
 */
class ProfileRegistry private constructor(private val context: Context) {

    /** Active profile root. Resolved on first access: reads
     *  [activeProfileId] from the pre-profile registry pref; if
     *  unset (cold install or pre-Phase-2 user) falls back to
     *  [ProfileRoot.DEFAULT_PROFILE_ID] so existing data continues
     *  to load from the same place. */
    val current: ProfileRoot by lazy {
        ProfileRoot.forId(context, activeProfileId)
    }

    /**
     * Pre-profile registry — lives at `filesDir/profiles_registry.xml`
     * (a regular SharedPreferences file, but its name doesn't go
     * through ProfileRoot.prefsName since it has to be readable
     * BEFORE we know which profile to activate). Stores the
     * currently-active profile id and a tiny per-profile metadata
     * blob (just createdAt for now; Phase 2b adds optional
     * display name + colour for the indicator strip).
     */
    private val registryPrefs =
        context.getSharedPreferences("aegis_profiles_registry", Context.MODE_PRIVATE)

    /** Id of the currently-active profile. Defaults to
     *  [ProfileRoot.DEFAULT_PROFILE_ID] so the cold-install /
     *  pre-Phase-2 path keeps working. */
    val activeProfileId: String
        get() = registryPrefs.getString(KEY_ACTIVE_PROFILE, null)
            ?.takeIf { it.isNotBlank() }
            ?: ProfileRoot.DEFAULT_PROFILE_ID

    /** Switch the active profile. Phase 2 UX is "set the active id
     *  then restart the process" — re-binding AegisApp's identity /
     *  repository / transports / running services in place is too
     *  fragile to do live, and a kill+restart is cheap. UI calls
     *  this then either advises the user to relaunch or kills its
     *  own process so the system restarts us clean. */
    fun setActiveProfile(id: String) {
        require(id.isNotBlank()) { "profile id must be non-blank" }
        // commit() (synchronous) instead of apply() (async). The
        // caller — ProfilesSettingsScreen / LockScreen — immediately
        // follows up with Process.killProcess(myPid()), which can
        // race apply()'s in-memory-then-disk write. The new active
        // id then doesn't survive the restart and the app boots
        // back into the previous profile ("restart doesn't boot
        // into the profile" user report). Synchronous commit is a
        // few ms; well under the kill latency.
        registryPrefs.edit().putString(KEY_ACTIVE_PROFILE, id).commit()
    }

    /**
     * Remove a profile from disk: its identity, DB, attachments,
     * avatar, prefs, and registry metadata. Refuses to delete the
     * currently-active profile (the running app's data) and refuses
     * to delete the last surviving profile (would leave the app in
     * an unbootable empty state). Caller is responsible for any UX
     * confirmation; the registry just nukes.
     *
     * Returns true on success, false on a guarded skip (active /
     * last surviving / unknown id).
     */
    fun deleteProfile(id: String): Boolean {
        if (id.isBlank()) return false
        if (id == activeProfileId) {
            Log.w(TAG, "refusing to delete the active profile $id")
            return false
        }
        val all = listProfiles()
        if (id !in all) {
            Log.w(TAG, "deleteProfile: unknown id $id")
            return false
        }
        if (all.size <= 1) {
            Log.w(TAG, "refusing to delete last surviving profile $id")
            return false
        }
        runCatching {
            val root = ProfileRoot.forId(context, id)
            root.root.deleteRecursively()
            // Wipe per-profile prefs files. Naming convention is
            // ProfileRoot.prefsName(...) which prefixes profile ids
            // onto store names. Best-effort sweep of shared_prefs/.
            val sharedPrefsDir = File(context.filesDir.parentFile, "shared_prefs")
            if (sharedPrefsDir.isDirectory) {
                sharedPrefsDir.listFiles()
                    ?.filter { it.name.contains(id) && it.name.endsWith(".xml") }
                    ?.forEach { it.delete() }
            }
            // Drop the metadata key.
            registryPrefs.edit()
                .remove("$KEY_META_PREFIX$id")
                .remove("$KEY_EPHEMERAL_PREFIX$id")
                .remove("$KEY_SIMPLEX_UID_PREFIX$id")
                .commit()
            Log.i(TAG, "deleted profile $id")
        }.onFailure {
            Log.w(TAG, "deleteProfile $id failed", it)
            return false
        }
        return true
    }

    /** Enumerate every profile id on disk. Always includes the
     *  active id even if nothing has been written yet (cold
     *  install) so a UI that lists profiles never shows an empty
     *  state on a fresh install. */
    fun listProfiles(): List<String> {
        val byDisk = ProfileRoot.profilesParent(context).listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.toMutableList()
            ?: mutableListOf()
        if (activeProfileId !in byDisk) byDisk += activeProfileId
        return byDisk.distinct().sorted()
    }

    /**
     * Create a new profile root + register it. Idempotent — calling
     * with an existing id is a no-op aside from refreshing the
     * createdAt metadata. Does NOT switch to the new profile; the
     * caller can call [setActiveProfile] separately if it wants
     * the next process restart to land on it.
     */
    fun createProfile(id: String): ProfileRoot {
        require(id.isNotBlank()) { "profile id must be non-blank" }
        require(id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "profile id must be filesystem-safe (letters / digits / - _)"
        }
        val root = ProfileRoot.forId(context, id)
        val metaKey = "$KEY_META_PREFIX$id"
        if (!registryPrefs.contains(metaKey)) {
            registryPrefs.edit()
                .putLong(metaKey, System.currentTimeMillis())
                .apply()
        }
        return root
    }

    /**
     * Create a profile flagged EPHEMERAL. Identical to
     * [createProfile] but records the ephemeral flag so the lock-time
     * wipe knows to destroy this profile entirely (its Aegis directory +
     * its dedicated SimpleX user) instead of persisting it. The dedicated
     * SimpleX userId is bound later via [setSimplexUserId] once the
     * transport creates it on first activation.
     */
    fun createEphemeralProfile(id: String): ProfileRoot {
        val root = createProfile(id)
        registryPrefs.edit().putBoolean("$KEY_EPHEMERAL_PREFIX$id", true).apply()
        return root
    }

    /** True iff [id] is an ephemeral (wipe-on-lock) profile. */
    fun isEphemeral(id: String): Boolean =
        registryPrefs.getBoolean("$KEY_EPHEMERAL_PREFIX$id", false)

    /**
     * Promote an ephemeral profile to PERMANENT
     * ("ephemeral → permanent" — the *dangerous* direction: it makes
     * throwaway data forensically permanent). Just clears the ephemeral
     * flag + any scheduled wipe; the profile keeps its own SimpleX user
     * and its on-disk data, and will no longer be wiped on lock. (The
     * reverse, permanent → ephemeral, is deliberately NOT offered: with
     * the shared SimpleX user a permanent profile has no isolated user to
     * delete, so it can't be cleanly wiped.)
     */
    fun makePermanent(id: String) {
        registryPrefs.edit()
            .remove("$KEY_EPHEMERAL_PREFIX$id")
            .remove("$KEY_PENDING_WIPE_PREFIX$id")
            .apply()
    }

    /** The dedicated SimpleX userId bound to profile [id], or null if it
     *  hasn't been created/bound yet (or the profile uses the shared user). */
    fun simplexUserId(id: String): Long? =
        registryPrefs.getLong("$KEY_SIMPLEX_UID_PREFIX$id", -1L).takeIf { it > 0 }

    /** Bind [userId] as profile [id]'s dedicated SimpleX user. */
    fun setSimplexUserId(id: String, userId: Long) {
        registryPrefs.edit().putLong("$KEY_SIMPLEX_UID_PREFIX$id", userId).apply()
    }

    /**
     * Schedule profile [id] for an ephemeral wipe on the NEXT start. We
     * defer the heavy work (delete SimpleX user + recursive dir delete)
     * off the lock event — doing it there would mean blocking work +
     * process death mid-lock. The flag survives a force-kill, so the wipe
     * is fail-safe: if the app dies before restart, the next launch still
     * completes it. [simplexUserId] is captured now (-1 if none bound).
     */
    fun scheduleEphemeralWipe(id: String, simplexUserId: Long) {
        registryPrefs.edit().putLong("$KEY_PENDING_WIPE_PREFIX$id", simplexUserId).commit()
    }

    /** Profiles awaiting an ephemeral wipe → their captured SimpleX
     *  userId (or -1). Read on start by the wipe processor. */
    fun pendingEphemeralWipes(): Map<String, Long> =
        registryPrefs.all.entries
            .filter { it.key.startsWith(KEY_PENDING_WIPE_PREFIX) }
            .associate { it.key.removePrefix(KEY_PENDING_WIPE_PREFIX) to ((it.value as? Long) ?: -1L) }

    /** Clear a completed pending wipe. */
    fun clearPendingEphemeralWipe(id: String) {
        registryPrefs.edit().remove("$KEY_PENDING_WIPE_PREFIX$id").apply()
    }

    /**
     * Mark that we are deliberately ENTERING ephemeral profile [id] (a
     * user-initiated switch, which kill-restarts the process). On the
     * next start this one-shot lets the orchestrator tell "just entered,
     * activate it" apart from "locked/rebooted with an ephemeral profile
     * active, wipe it" — the two are otherwise indistinguishable after a
     * process death. Committed synchronously since the switch kills the
     * process immediately after.
     */
    fun markEnteringEphemeral(id: String) {
        registryPrefs.edit().putBoolean("$KEY_ENTERING_PREFIX$id", true).commit()
    }

    /** Read-and-clear the entering one-shot for [id]. */
    fun consumeEnteringEphemeral(id: String): Boolean {
        val v = registryPrefs.getBoolean("$KEY_ENTERING_PREFIX$id", false)
        if (v) registryPrefs.edit().remove("$KEY_ENTERING_PREFIX$id").commit()
        return v
    }

    /**
     * Arm a one-shot "lock on next start" flag. Set when leaving an ephemeral
     * profile (its lock wipes it + switches to a surviving profile + restarts):
     * the survivor must come up LOCKED so the user re-authenticates and the
     * PIN-derived seal actually loads — otherwise the app restarts "unlocked"
     * with no PIN prompt but the survivor's sealed chats unreadable (user
     * report). Global (not per-profile) — it's a cross-profile transition
     * signal. Committed synchronously (the caller kills the process next).
     */
    fun armLockOnNextStart() {
        registryPrefs.edit().putBoolean(KEY_LOCK_ON_NEXT_START, true).commit()
    }

    /** Read-and-clear the lock-on-next-start one-shot. */
    fun consumeLockOnNextStart(): Boolean {
        val v = registryPrefs.getBoolean(KEY_LOCK_ON_NEXT_START, false)
        if (v) registryPrefs.edit().remove(KEY_LOCK_ON_NEXT_START).commit()
        return v
    }

    /** Force-delete a profile's on-disk footprint, bypassing the
     *  active/last-profile guards in [deleteProfile]. Used only by the
     *  ephemeral wipe processor, which has already switched away to a
     *  surviving profile. */
    fun forceDeleteProfileFootprint(id: String) {
        runCatching {
            ProfileRoot.forId(context, id).root.deleteRecursively()
            val sharedPrefsDir = File(context.filesDir.parentFile, "shared_prefs")
            sharedPrefsDir.listFiles()
                ?.filter { it.name.contains(id) && it.name.endsWith(".xml") }
                ?.forEach { it.delete() }
            registryPrefs.edit()
                .remove("$KEY_META_PREFIX$id")
                .remove("$KEY_EPHEMERAL_PREFIX$id")
                .remove("$KEY_SIMPLEX_UID_PREFIX$id")
                .commit()
        }.onFailure { Log.w(TAG, "forceDeleteProfileFootprint $id failed", it) }
    }

    companion object {
        private const val TAG = "ProfileRegistry"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"
        // Per-profile metadata keys, suffixed with profile id.
        // Today: only createdAt (Long, epoch ms). Phase 2b adds
        // display name + colour.
        private const val KEY_META_PREFIX = "meta_"
        // Ephemeral profiles — RAM-lifetime profiles
        // fully wiped on lock. We store an ephemeral flag and the
        // dedicated SimpleX userId so the lock-time wipe can delete
        // exactly that user's data ([SimpleXTransport.deleteSimplexUser]).
        private const val KEY_EPHEMERAL_PREFIX = "ephemeral_"
        private const val KEY_SIMPLEX_UID_PREFIX = "simplex_uid_"
        private const val KEY_PENDING_WIPE_PREFIX = "pending_wipe_"
        private const val KEY_ENTERING_PREFIX = "entering_eph_"
        private const val KEY_LOCK_ON_NEXT_START = "lock_on_next_start"

        @Volatile private var instance: ProfileRegistry? = null

        fun get(context: Context): ProfileRegistry =
            instance ?: synchronized(this) {
                instance ?: ProfileRegistry(context.applicationContext).also { instance = it }
            }
    }
}

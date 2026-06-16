package app.aether.aegis.groups

/**
 * Narrow contract the app module implements to give the group
 * module the few app-side capabilities it legitimately needs.
 * The cross-module API is a zero data bridge.
 *
 * Every method here is either:
 *
 *   - **A pub key passthrough.** Pub keys are already visible to
 *     every group member by protocol design; sharing them across
 *     the module boundary leaks nothing.
 *   - **A bare-id passthrough.** The active profile's id, used to
 *     scope SharedPreferences. The id is already exposed in the
 *     app's local files dir layout.
 *   - **A scoped group-table CRUD primitive.** No safety tables
 *     (known_peers, SOS, presence, sensor) are reachable
 *     through this interface — by intent.
 *
 * Things this interface deliberately does NOT expose:
 *
 *   - Contact list, trust tiers, display names from
 *     KnownPeerEntity
 *   - SOS state or fan-out
 *   - Location, presence, sensor, mugshot, canary surfaces
 *   - The full Repository class
 *
 * If a new app-side capability is needed by the group module, it
 * gets added as a method here with the same "narrow and named"
 * discipline.
 *
 * App module installs an implementation into
 * [GroupModuleHostHolder.current] during AegisApp.onCreate. All
 * group-module call sites pass through the holder; an unset
 * holder means the host hasn't initialised yet (very-early
 * worker invocations) and call sites should fail soft rather
 * than crash.
 */
interface GroupModuleHost {

    /** Active profile id — used to scope GroupModulePrefs's
     *  SharedPreferences file per profile. Returns null when the
     *  profile system
     *  isn't initialised yet (cold-start workers); callers fall
     *  back to a legacy unsuffixed file. */
    fun activeProfileId(): String?

    /** Whether a group is currently active (per-group toggle).
     *  Returns null when the groupId isn't in the local DB. */
    suspend fun isGroupEnabled(groupId: String): Boolean?

    /** Per-group auto-disable inactivity window in minutes.
     *  Null = no timer. */
    suspend fun groupAutoDisableMinutes(groupId: String): Int?

    /** Flip a group's active flag. The per-group bottom-sheet
     *  toggle calls this directly; the per-group auto-disable
     *  worker calls this when the inactivity window elapses. */
    suspend fun setGroupEnabled(groupId: String, enabled: Boolean)
}

/**
 * Process-wide holder for the active [GroupModuleHost]. App
 * sets it once in onCreate; group module reads through it.
 *
 * Why a global slot and not constructor injection: the group
 * module's WorkManager workers + Composable prefs lookups are
 * reachable from many call sites with varying lifetimes. A
 * single static slot is the cheapest way to give them all the
 * same instance without requiring every call site to plumb the
 * host through. Profile switches kill the process so the slot
 * never holds stale data across profiles.
 */
object GroupModuleHostHolder {
    @Volatile
    var current: GroupModuleHost? = null
}

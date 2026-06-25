package app.aether.aegis.presence

/**
 * Narrow contract the app module implements so the presence module
 * (`:core:safety:presence`) can do its job — routine location + status
 * broadcasting to Trusted contacts — WITHOUT a compile-time edge back
 * to `:app` (trust-container Phase 2, Stage 2).
 *
 * The presence module owns the *what/when* of a presence broadcast
 * (the `[aegis:location]` / `[aegis:status]` envelope shape, the
 * Trusted-only fan-out). This host gives it only the two primitives it
 * needs: the Trusted target list and a way to send. It deliberately
 * does NOT expose the Repository, the sos surface, the contact list,
 * trust tiers, sensors, or anything else — a presence-module file that
 * tried to import them would not compile.
 *
 * Same pattern as [app.aether.aegis.groups.GroupModuleHost]: `:app`
 * installs an implementation into [PresenceModuleHostHolder.current]
 * during AegisApp.onCreate; call sites read through the holder and
 * fail soft (no-op) if it's unset.
 */
interface PresenceModuleHost {

    /** Public keys of the TRUSTED contacts — the only tier that
     *  receives routine presence. Empty when
     *  the presence tier is inactive; the broadcaster then no-ops. */
    suspend fun trustedTargets(): List<String>

    /** Send one presence control message to one peer. [kind] selects
     *  the on-wire MessageType in `:app` (LOCATION vs STATUS) so the
     *  presence module never imports the core enum. */
    suspend fun sendPresence(toPubkey: String, body: String, kind: PresenceKind)
}

/** Which routine-presence channel a body belongs to. Maps 1:1 to the
 *  app's MessageType.{LOCATION,STATUS} on the `:app` side of the host. */
enum class PresenceKind { LOCATION, STATUS }

/**
 * Process-wide holder for the active [PresenceModuleHost]. App sets it
 * once in onCreate; the presence module reads through it. A profile
 * switch kills the process, so the slot never holds stale cross-profile
 * data.
 */
object PresenceModuleHostHolder {
    @Volatile
    var current: PresenceModuleHost? = null
}

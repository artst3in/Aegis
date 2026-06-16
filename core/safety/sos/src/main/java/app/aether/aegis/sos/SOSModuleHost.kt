package app.aether.aegis.sos

import android.content.Context

/**
 * Narrow contract `:app` implements so the sos module
 * (`:core:safety:sos`) can reach the one app-level primitive its
 * relocated leaves still need — the application [Context] — WITHOUT a
 * compile-time edge back to `:app` (trust-container Phase 2,
 * Stage 3).
 *
 * Today this exposes only [appContext], used by [SOSAudioPlayer] to
 * restore the speakerphone route from a code path that has no Context
 * in scope (SOSAlertStore.markEnded → SOSAudioPlayer.stopFor).
 * Later Stage-3 slices widen this interface (sos targets, self key,
 * a send primitive, the own-sos gate) as the still-entangled sos
 * classes — SOSCoordinator, SOSSnapshotStream, SOSEvidenceLog —
 * move in behind it. It deliberately does NOT expose the Repository,
 * the transport, or trust tiers: a sos-module file importing those
 * would not compile, which is the structural guarantee we want.
 *
 * Same pattern as [PresenceModuleHost][app.aether.aegis.presence.PresenceModuleHost]
 * and GroupModuleHost: `:app` installs an implementation into
 * [SOSModuleHostHolder.current] during AegisApp.onCreate; call sites
 * read through the holder and fail soft (no-op) if it's unset.
 */
interface SOSModuleHost {

    /**
     * Process-wide application context. Never an Activity — the sos
     * audio path runs from background receivers and from a in SOS
     * phone that may be locked, so an Activity context would be wrong
     * (and frequently null) here.
     */
    val appContext: Context

    /**
     * True iff THIS device is currently broadcasting its OWN sos —
     * i.e. the app-side SOSHandler has an active sos state. Gates
     * the victim-only evidence log ([SOSEvidenceLog.append]) so a
     * receiver's coord-broadcast path can call append() freely without
     * writing a log on a phone that isn't the victim.
     *
     * Implemented in `:app` (reads SOSHandler.state); the sos
     * module has no edge to the handler, so it asks through here. Fail
     * closed: a missing host means "not my sos", so no evidence is
     * written when the app isn't fully up.
     */
    fun isMyOwnSOSActive(): Boolean

    // ---- Stage-3 widening for SOSCoordinator ----
    // Each primitive hides an :app-only type (Repository entities, the
    // transport's MessageType, the achievements module) behind a plain
    // signature, so the relocated coordinator never gains a compile edge
    // back to :app. Implemented in AegisApp.onCreate; callers read through
    // the holder and fail soft (no-op / empty / null) when it's unset.

    /** This device's own key — sos fan-outs skip it as a target. */
    val selfKey: String

    /** Send one STATUS-class control envelope to [peerKey]. The
     *  coordinator only ever sends STATUS, so the transport's MessageType
     *  (in :app) stays hidden. Fire-and-forget. */
    fun sendStatus(peerKey: String, body: String)

    /** Pubkeys of the user's sos targets (Trusted ∪ Emergency). Empty
     *  list when the host is unset. */
    suspend fun sosTargetKeys(): List<String>

    /** Display name for a peer, or null if unknown. */
    suspend fun displayNameFor(peerKey: String): String?

    /** Whether [peerKey] is a confirmed Aegis client (gates control
     *  sends so we don't spam a vanilla SimpleX peer). */
    suspend fun isAegis(peerKey: String): Boolean

    /** A peer's last-known broadcast location as (lat, lng), or null if
     *  none is on record. Drives responder distance / arrival math
     *  without exposing the Room status entity. */
    suspend fun victimLocation(peerKey: String): Pair<Double, Double>?

    /** Award the SOS Drill achievement — a real human answered a
     *  sos. No-op-safe; never throws. */
    fun unlockSOSDrillAchievement()
}

/**
 * Process-wide holder for the active [SOSModuleHost]. App sets it
 * once in onCreate; the sos module reads through it. A profile
 * switch kills the process, so the slot never holds stale
 * cross-profile data.
 */
object SOSModuleHostHolder {
    @Volatile
    var current: SOSModuleHost? = null
}

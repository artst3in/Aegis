package app.aether.aegis.achievements

import android.content.Context
import app.aether.aegis.AegisApp

/**
 * Badges earned by OTHER contacts, received over the wire via the
 * `[aegis:badges]` envelope.
 *
 * Per-peer, keyed by the contact's pubkey, stored as a string-set of
 * badge ids. Local cache only — no Room table, no migration. We store
 * whatever a peer sends; **trust gating happens at render time** (a
 * contact's badges are only shown if YOU have them at TRUSTED tier),
 * so a non-trusted peer's announcement is harmlessly cached but never
 * surfaced.
 *
 * Forward-compatible: unknown ids (a peer on a newer build) are stored
 * verbatim and simply skipped by the renderer via [Achievement.byId].
 */
object PeerBadgeStore {

    private const val STORE_NAME = "aegis_peer_badges"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(
        app.aether.aegis.profile.ProfileRegistry.get(ctx).current.prefsName(STORE_NAME),
        Context.MODE_PRIVATE,
    )

    /** Parse + store an inbound `[aegis:badges]<id>,<id>,…` envelope
     *  from [peerKey]. Never throws. */
    fun recordEnvelope(peerKey: String, body: String) {
        runCatching {
            val csv = body.removePrefix(AchievementBroadcaster.ENVELOPE).trim()
            val ids = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs(AegisApp.instance).edit().putStringSet(peerKey, ids).apply()
        }
    }

    /** Earned badge ids for [peerKey] (empty if none/unknown). */
    fun get(peerKey: String): Set<String> =
        runCatching {
            prefs(AegisApp.instance).getStringSet(peerKey, emptySet()) ?: emptySet()
        }.getOrDefault(emptySet())
}

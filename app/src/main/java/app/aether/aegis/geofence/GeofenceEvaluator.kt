package app.aether.aegis.geofence

import app.aether.aegis.AegisApp
import app.aether.aegis.core.MessageType
import android.content.Context
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Single source of truth for "is our current location inside the
 * configured zone?". Called from ProtocolService.locationListener on
 * every fresh GPS update; we keep the state machine here so the
 * service code stays simple.
 *
 * Edge-triggered: only fires the alert when the inside→outside
 * transition happens, not every update. Re-entering the zone (outside
 * →inside) silently updates the lastInside flag with no alert.
 */
object GeofenceEvaluator {

    /**
     * Evaluate the current location against the configured zone.
     * Returns null normally; returns an alert body when an
     * inside→outside transition just happened (caller broadcasts).
     */
    fun evaluate(context: Context, lat: Double, lng: Double): String? {
        val store = GeofenceStore(context)
        if (!store.isActive) return null

        val cLat = store.centerLat
        val cLng = store.centerLng
        val r = store.radiusMeters
        val dist = haversineMeters(cLat, cLng, lat, lng)
        val nowInside = dist <= r
        val wasInside = store.lastInside
        store.lastInside = nowInside

        // First-ever eval: just seed the state, don't fire. Stops the
        // "set zone at home, immediate alert because I'm currently at
        // work" false-positive.
        if (wasInside == null) return null

        if (wasInside && !nowInside) {
            // Inside → outside: fire.
            val json = org.json.JSONObject().apply {
                put("lat", lat); put("lng", lng)
                put("c_lat", cLat); put("c_lng", cLng)
                put("r", r)
                put("ts", System.currentTimeMillis())
            }
            // A geofence you set actually crossed and fired → earn the
            // Prison Break badge.
            app.aether.aegis.achievements.Achievements.unlock(
                app.aether.aegis.achievements.Achievement.PRISON_BREAK,
            )
            return "[aegis:geofence]$json"
        }
        return null
    }

    /** Broadcast the zone enter/exit alert to Trusted contacts. This
     *  is routine whereabouts data under the trust model —
     *  Emergency and Untrusted never see it. */
    suspend fun broadcast(body: String) {
        val peers = runCatching { AegisApp.instance.repository.trustedTargets() }
            .getOrNull() ?: return
        for (peer in peers) {
            runCatching {
                AegisApp.instance.protocolManager.sendMessage(
                    to = peer.publicKey,
                    content = body,
                    type = MessageType.STATUS,
                )
            }
        }
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) *
            cos(Math.toRadians(lat2)) *
            sin(dLng / 2).let { it * it }
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}

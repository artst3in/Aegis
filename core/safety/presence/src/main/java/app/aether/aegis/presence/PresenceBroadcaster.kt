package app.aether.aegis.presence

/**
 * Routine presence broadcasting (trust-container Phase 2,
 * Stage 2). Lifted out of `ProtocolService` verbatim — same envelope
 * shapes, same Trusted-only fan-out — and reachable back to `:app` only
 * through [PresenceModuleHost]. `ProtocolService` still owns the GPS
 * listener + the power-budget cadence policy; it just calls in here to
 * actually emit, so the broadcasting can no longer touch anything but
 * the two host primitives.
 *
 * Both methods no-op cleanly if the host isn't installed yet or there
 * are no Trusted contacts (presence tier inactive).
 */
object PresenceBroadcaster {

    /**
     * Routine location → Trusted only. `[aegis:location]{lat,lng,ts}`;
     * the receiver's transport re-tags + strips before it reaches
     * AegisApp.handleInboundLocation.
     */
    suspend fun broadcastLocation(lat: Double, lng: Double, ts: Long) {
        val host = PresenceModuleHostHolder.current ?: return
        val targets = runCatching { host.trustedTargets() }.getOrNull() ?: return
        if (targets.isEmpty()) return
        val body = """[aegis:location]{"lat":$lat,"lng":$lng,"ts":$ts}"""
        targets.forEach {
            runCatching { host.sendPresence(it, body, PresenceKind.LOCATION) }
        }
    }

    /**
     * Periodic status → Trusted only: battery + network + lastSeen +
     * in-app heartbeat + app version. [appVersion] is passed in because
     * BuildConfig lives in `:app`, not this module.
     */
    suspend fun broadcastStatus(
        batteryLevel: Int?,
        isCharging: Boolean?,
        networkType: String?,
        signalStrength: Int?,
        ts: Long,
        inAppTs: Long,
        appVersion: String,
    ) {
        val host = PresenceModuleHostHolder.current ?: return
        val targets = runCatching { host.trustedTargets() }.getOrNull() ?: return
        if (targets.isEmpty()) return
        val json = org.json.JSONObject().apply {
            put("battery", batteryLevel ?: org.json.JSONObject.NULL)
            put("charging", isCharging ?: false)
            put("net", networkType ?: org.json.JSONObject.NULL)
            put("signal", signalStrength ?: org.json.JSONObject.NULL)
            put("ts", ts)
            put("inApp", inAppTs)
            put("version", appVersion)
        }
        val body = "[aegis:status]$json"
        targets.forEach {
            runCatching { host.sendPresence(it, body, PresenceKind.STATUS) }
        }
    }
}

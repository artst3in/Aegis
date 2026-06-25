package app.aether.aegis.sentinel

/**
 * Stages of the Sentinel cascade.
 *
 * Ordinal IS the stage hierarchy — higher ordinal = more escalated.
 * This matters for the stage-watermark notification rule: "escalation
 * is news, repeat is not." A trip at a stage already pinged (i.e.,
 * stage.ordinal <= watermark.ordinal) is silent; reaching a new high
 * water mark fires a notification.
 */
enum class SentinelStage {
    OFF,                  // sentinel disarmed
    ARMING,               // sonar starting, waiting for room-stillness to lock the cascade live
    SONAR_ARMED,          // sonar pulsing (clicks audible); proximity + accel passively monitored
    PROXIMITY_ARMED,      // sonar OFF (silent); proximity + accel monitored
    RECORDING,            // sonar OFF; proximity polling; accel streamed to log
    ;

    /** Display label for log + future UI. */
    val label: String get() = when (this) {
        OFF             -> "off"
        ARMING          -> "arming"
        SONAR_ARMED     -> "sonar-armed"
        PROXIMITY_ARMED -> "proximity-armed"
        RECORDING       -> "recording"
    }
}

/**
 * Notification throttle modes. The Kelcie-scenario default is
 * [UNTIL_UNLOCK]: one notification per *unique stage reached* since
 * the last phone unlock, so a child playing nearby generates at most
 * one ping at each cascade level instead of spamming the contact.
 */
enum class SentinelThrottle {
    NEVER,        // 0 — log-only mode, never notify
    UNTIL_UNLOCK, // 1 — one per unique stage reached since last unlock (default)
    TIMED,        // N min — same as UNTIL_UNLOCK + watermark resets after N min of quiet
    EVERY,        // ∞ — ping on every trip (for completionists)
    ;

    val label: String get() = when (this) {
        NEVER        -> "Never (log only)"
        UNTIL_UNLOCK -> "Once per unlock"
        TIMED        -> "Timed"
        EVERY        -> "Every trip"
    }
}

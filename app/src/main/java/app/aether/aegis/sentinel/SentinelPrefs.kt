package app.aether.aegis.sentinel

import android.content.Context
import android.content.SharedPreferences

/**
 * Persisted Sentinel configuration — survives process restart so the
 * armed state, throttle choice, and timed-throttle interval don't
 * reset every time Android kills Aegis.
 *
 * Notify-list (subset of chat contacts) lives in a separate store
 * keyed by peer pubkey; that lands in phase 3 alongside the
 * notification wire. Phase 1 only persists what the engine itself
 * needs.
 */
class SentinelPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    var armed: Boolean
        get() = prefs.getBoolean(KEY_ARMED, false)
        set(value) { prefs.edit().putBoolean(KEY_ARMED, value).apply() }

    var throttle: SentinelThrottle
        get() = SentinelThrottle.values().getOrElse(
            prefs.getInt(KEY_THROTTLE, SentinelThrottle.UNTIL_UNLOCK.ordinal),
        ) { SentinelThrottle.UNTIL_UNLOCK }
        set(value) { prefs.edit().putInt(KEY_THROTTLE, value.ordinal).apply() }

    /** Interval in minutes when [throttle] is [SentinelThrottle.TIMED].
     *  After this many minutes of no further escalation, the watermark
     *  resets so the next trip can ping again. */
    var timedIntervalMinutes: Int
        get() = prefs.getInt(KEY_TIMED_MIN, DEFAULT_TIMED_MIN).coerceIn(1, 240)
        set(value) { prefs.edit().putInt(KEY_TIMED_MIN, value.coerceIn(1, 240)).apply() }

    /** Peer pubkeys (chat-contact subset) that should receive
     *  silent SimpleX pings when the cascade escalates. Empty by
     *  default so installing Sentinel doesn't accidentally start
     *  notifying anyone before the user picks. The notify-list
     *  picker UI ships with the Sentinel screen; until then this
     *  stays empty and the notifier no-ops. */
    var notifyList: Set<String>
        get() = prefs.getStringSet(KEY_NOTIFY_LIST, emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_NOTIFY_LIST, value).apply() }

    /** Include the 3D-model recording blob in outgoing
     *  notifications. Default true — the recording is the forensic
     *  payload the contact actually wants. Off if the user wants
     *  notifications-only with no payload attached. */
    var attachRecording: Boolean
        get() = prefs.getBoolean(KEY_ATTACH_RECORDING, true)
        set(value) { prefs.edit().putBoolean(KEY_ATTACH_RECORDING, value).apply() }

    /** Arming flow: after the user
     *  toggles Sentinel on, sonar starts but does not consider the
     *  cascade live until the room has been still for this many
     *  seconds. The physics arms it — the silence is the arming
     *  signal. Default 15 s. */
    var armingQuietPeriodSec: Int
        get() = prefs.getInt(KEY_ARMING_QUIET_SEC, DEFAULT_ARMING_QUIET_SEC).coerceIn(3, 120)
        set(value) { prefs.edit().putInt(KEY_ARMING_QUIET_SEC, value.coerceIn(3, 120)).apply() }

    /** Per-stage notification delays.
     *  After a watermark raises, the notification is QUEUED rather
     *  than fired immediately. If the owner unlocks during the
     *  delay, all pending notifications are cancelled. Defaults
     *  matched to the spec table. */
    var delaySonarSec: Int
        get() = prefs.getInt(KEY_DELAY_SONAR, DEFAULT_DELAY_SONAR).coerceIn(0, 300)
        set(value) { prefs.edit().putInt(KEY_DELAY_SONAR, value.coerceIn(0, 300)).apply() }

    var delayProximitySec: Int
        get() = prefs.getInt(KEY_DELAY_PROX, DEFAULT_DELAY_PROX).coerceIn(0, 300)
        set(value) { prefs.edit().putInt(KEY_DELAY_PROX, value.coerceIn(0, 300)).apply() }

    var delayRecordingSec: Int
        get() = prefs.getInt(KEY_DELAY_REC, DEFAULT_DELAY_REC).coerceIn(0, 300)
        set(value) { prefs.edit().putInt(KEY_DELAY_REC, value.coerceIn(0, 300)).apply() }

    /** Auto-arm: when phone is on charger + locked + stationary for
     *  [autoArmStationaryMinutes] minutes, automatically transition
     *  through arming → SONAR_ARMED. Killer use case: plug in at
     *  bedside, fall asleep, phone watches overnight. Off by default
     *  because auto-anything is opt-in. */
    var autoArmEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ARM, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_ARM, value).apply() }

    var autoArmStationaryMinutes: Int
        get() = prefs.getInt(KEY_AUTO_ARM_STATIONARY_MIN, DEFAULT_AUTO_ARM_STATIONARY_MIN).coerceIn(1, 60)
        set(value) { prefs.edit().putInt(KEY_AUTO_ARM_STATIONARY_MIN, value.coerceIn(1, 60)).apply() }

    /** Explicit "I deliberately picked log-only" acknowledgement.
     *  Together with [notifyList].isNotEmpty() this satisfies the
     *  "configured" precondition for the Sonar skill-tree node:
     *  either the user picked contacts to notify, OR they actively
     *  decided to run in log-only mode. An empty notify-list alone
     *  doesn't count — that's just "user hasn't touched the
     *  feature." */
    var acknowledgedLogOnly: Boolean
        get() = prefs.getBoolean(KEY_ACK_LOG_ONLY, false)
        set(value) { prefs.edit().putBoolean(KEY_ACK_LOG_ONLY, value).apply() }

    /** Epoch-ms when the user successfully completed a Sentinel
     *  drill. Third precondition for the skill-tree node — proves
     *  the cascade works end-to-end on the user's hardware.
     *  Zero = never drilled. */
    var lastDrillAt: Long
        get() = prefs.getLong(KEY_LAST_DRILL_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_DRILL_AT, value).apply() }

    /** Epoch-ms when the current drill was started. Zero = no drill
     *  in progress. The drill engine uses this for the 5-min
     *  recipient-confirmation timeout AND the 24-h cooldown gate. */
    var drillStartedAt: Long
        get() = prefs.getLong(KEY_DRILL_STARTED_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_DRILL_STARTED_AT, value).apply() }

    /** Notify-list snapshot taken when the drill started. Drill
     *  passes only when every peer in this set is also in
     *  [drillConfirmedRecipients]. Snapshotted so a contact added
     *  mid-drill doesn't gate the result. */
    var drillPendingRecipients: Set<String>
        get() = prefs.getStringSet(KEY_DRILL_PENDING, emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_DRILL_PENDING, value).apply() }

    /** Peers who have sent back a KIND_SENTINEL_DRILL_ACK for the
     *  current drill. Cleared on each new drill start. */
    var drillConfirmedRecipients: Set<String>
        get() = prefs.getStringSet(KEY_DRILL_CONFIRMED, emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_DRILL_CONFIRMED, value).apply() }

    /** One drill per 24 hours, same as SOS Drill.
     *  Returns milliseconds until the next drill is allowed, or 0
     *  if the cooldown has expired (or never started). */
    fun drillCooldownRemainingMs(): Long {
        val last = lastDrillAt
        if (last == 0L) return 0L
        val elapsed = System.currentTimeMillis() - last
        val cooldown = 24L * 60L * 60L * 1000L
        return (cooldown - elapsed).coerceAtLeast(0L)
    }

    /** Skill-tree-node truth test: configured + calibrated + drilled.
     *  Calibration is checked against SonarPrefs since Sentinel
     *  reuses the sonar engine. */
    fun isFullyConfigured(): Boolean {
        val configured = notifyList.isNotEmpty() || acknowledgedLogOnly
        val calibrated = SonarPrefs(prefs.let {
            // Reach back to context — prefs themselves don't carry one,
            // but SonarPrefs only needs the application context which
            // we passed in at construction. Re-derive cleanly via the
            // SharedPreferences's file path is awkward; cleaner to
            // store the ctx field directly.
            ctxRef
        }).calibratedAt > 0L
        val drilled = lastDrillAt > 0L
        return configured && calibrated && drilled
    }

    // SonarPrefs is constructed from a Context, not from our
    // already-opened SharedPreferences. Keep a reference to the
    // application context so isFullyConfigured can build it.
    private val ctxRef: android.content.Context = context.applicationContext

    companion object {
        private const val STORE_NAME = "aegis_sentinel"
        private const val KEY_ARMED = "armed"
        private const val KEY_THROTTLE = "throttle"
        private const val KEY_TIMED_MIN = "timed_interval_min"
        private const val DEFAULT_TIMED_MIN = 30
        private const val KEY_NOTIFY_LIST = "notify_list"
        private const val KEY_ATTACH_RECORDING = "attach_recording"
        private const val KEY_ARMING_QUIET_SEC = "arming_quiet_sec"
        private const val DEFAULT_ARMING_QUIET_SEC = 15
        private const val KEY_DELAY_SONAR = "delay_sonar_sec"
        private const val KEY_DELAY_PROX = "delay_prox_sec"
        private const val KEY_DELAY_REC = "delay_rec_sec"
        private const val DEFAULT_DELAY_SONAR = 30
        private const val DEFAULT_DELAY_PROX = 15
        private const val DEFAULT_DELAY_REC = 10
        private const val KEY_AUTO_ARM = "auto_arm"
        private const val KEY_AUTO_ARM_STATIONARY_MIN = "auto_arm_stationary_min"
        private const val DEFAULT_AUTO_ARM_STATIONARY_MIN = 5
        private const val KEY_ACK_LOG_ONLY = "ack_log_only"
        private const val KEY_LAST_DRILL_AT = "last_drill_at"
        private const val KEY_DRILL_STARTED_AT = "drill_started_at"
        private const val KEY_DRILL_PENDING = "drill_pending"
        private const val KEY_DRILL_CONFIRMED = "drill_confirmed"
    }
}

// Bring SonarPrefs into scope for isFullyConfigured without a
// full import line in the @file:JvmStatic header. Local typealias
// keeps the rest of the file unchanged.
private typealias SonarPrefs = app.aether.aegis.sonar.SonarPrefs

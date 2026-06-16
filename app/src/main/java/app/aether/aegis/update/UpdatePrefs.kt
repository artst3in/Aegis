package app.aether.aegis.update

import android.content.Context
import android.content.SharedPreferences

/**
 * User-facing toggles for the auto-update channel.
 *
 * Default is WiFi-only. An 80–90 MB APK pull over cellular is a
 * non-trivial chunk of data — paid SIMs in Kenya can be ~1 GB / week.
 * We don't want Aegis silently eating someone's month.
 */
class UpdatePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    /** True = download only over WiFi (the safe default). False =
     *  any connected network, cellular included. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) { prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply() }

    /** Wall-clock of the most recent attempt at a check (auto or
     *  manual). Used by AutoUpdateCheck to rate-limit cold-start
     *  checks to once per hour even if the user force-quits and
     *  re-opens repeatedly. 0 = never checked. */
    var lastCheckAt: Long
        get() = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_CHECK_AT, value).apply() }

    /** Wall-clock of the most recent unattended auto-install. Throttles
     *  back-to-back installs from the periodic worker: if the user has
     *  the check interval pinned at 15 min and we're shipping multiple
     *  builds per hour, the worker would otherwise download an 80 MB
     *  APK and silent-install every 15 min — 64 % battery / hour, hot
     *  device, process churn destroying the PIN session every tick.
     *  Manual installs from Settings ignore this throttle (the user is
     *  asking explicitly). 0 = never auto-installed. */
    var lastAutoInstallAt: Long
        get() = prefs.getLong(KEY_LAST_AUTO_INSTALL_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_AUTO_INSTALL_AT, value).apply() }

    /** Period for the background update-check worker, in seconds. Null
     *  means "never auto-check" (worker is cancelled outright). Default
     *  24 h matches the legacy hardcoded cadence. Min enforced by the
     *  LogPeriodSlider at the UI; the worker also clamps at PeriodicWork
     *  minimums (15 min on AOSP). */
    var checkIntervalSeconds: Long?
        get() = if (prefs.contains(KEY_CHECK_INTERVAL))
            prefs.getLong(KEY_CHECK_INTERVAL, 24L * 3600L).takeIf { it > 0 }
        else 24L * 3600L
        set(value) {
            val ed = prefs.edit()
            if (value == null) ed.putLong(KEY_CHECK_INTERVAL, -1L)
            else ed.putLong(KEY_CHECK_INTERVAL, value)
            ed.apply()
        }

    /** versionCodes that previously triggered a BootHealthMonitor
     *  rollback. UpdateCheckWorker refuses to auto-install any
     *  versionCode in this set — without this, a single broken
     *  build spins the device in an install → crash → rollback →
     *  re-install loop forever (worker re-detects the bad build as
     *  "newer than current", auto-installs again). The user can
     *  still manually install a blocklisted version from the
     *  Settings → Updates panel (they know what they're doing); the
     *  block is purely on the unattended worker path.
     *
     *  Stored as a comma-separated string of decimal versionCodes
     *  because SharedPreferences' StringSet has thread-safety
     *  gotchas and we only need a few entries at most. */
    var knownBadVersionCodes: Set<Long>
        get() = (prefs.getString(KEY_KNOWN_BAD, null) ?: "")
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()
        set(value) {
            prefs.edit()
                .putString(KEY_KNOWN_BAD, value.joinToString(","))
                .apply()
        }

    /** versionCode that was running at the last package-replace event.
     *  BootReceiver compares the freshly-installed versionCode against
     *  this to tell an UPDATE (higher) from a ROLLBACK/downgrade (lower)
     *  and word the post-install notification accordingly — otherwise a
     *  rollback wrongly announces "Aegis was updated" (user report
     *  2026.06.14). 0 = no replace seen yet (first replace reads as an
     *  update, which is correct). Written ONLY by BootReceiver after it
     *  compares, so the new process doesn't clobber it before the read. */
    var lastSeenVersionCode: Long
        get() = prefs.getLong(KEY_LAST_SEEN_VC, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_SEEN_VC, value).apply() }

    fun addKnownBadVersionCode(code: Long) {
        knownBadVersionCodes = knownBadVersionCodes + code
    }

    fun removeKnownBadVersionCode(code: Long) {
        knownBadVersionCodes = knownBadVersionCodes - code
    }

    private companion object {
        private const val STORE_NAME = "aegis_update_prefs"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_LAST_CHECK_AT = "last_check_at"
        private const val KEY_LAST_AUTO_INSTALL_AT = "last_auto_install_at"
        private const val KEY_CHECK_INTERVAL = "check_interval_sec"
        private const val KEY_KNOWN_BAD = "known_bad_version_codes"
        private const val KEY_LAST_SEEN_VC = "last_seen_version_code"
    }
}

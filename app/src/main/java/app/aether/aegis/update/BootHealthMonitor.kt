package app.aether.aegis.update

import app.aether.aegis.BuildConfig
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Implements A/B updates with rollback.
 *
 * Flow:
 *  1. Every Application.onCreate increments `attempts`. If `attempts`
 *     ever reaches `MAX_FAILED_BOOTS` without an intervening success,
 *     we treat the new install as broken and roll back to
 *     filesDir/previous.apk via UpdateInstaller.rollback().
 *  2. 60s after launch, if we're still alive, we call `markSuccess()`
 *     which zeroes `attempts` and pins `last_successful_version` to
 *     the running versionCode.
 *  3. The uncaught-exception handler logs a crash flag so the next
 *     boot knows the previous run died unexpectedly.
 *
 * The "60s alive" check is the equivalent of GrapheneOS's A/B health
 * check: if you can run for a minute, the update is good.
 */
class BootHealthMonitor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("aegis_health", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun onAppCreate(): Outcome {
        val attempts = prefs.getInt(KEY_ATTEMPTS, 0) + 1
        prefs.edit().putInt(KEY_ATTEMPTS, attempts).apply()
        installCrashHandler()

        val current = BuildConfig.VERSION_CODE
        val lastGood = prefs.getInt(KEY_LAST_GOOD_VERSION, current)

        if (attempts >= MAX_FAILED_BOOTS && current != lastGood) {
            Log.w(TAG, "$attempts consecutive failed boots on $current — rolling back to $lastGood")
            // Blocklist this versionCode from the unattended worker
            // path BEFORE we trigger rollback. Without this, the
            // worker re-detects $current as "newer than $lastGood"
            // on its next tick and auto-installs the same broken
            // build again, looping the device forever. The user can
            // still install it manually from Settings if they think
            // they've fixed whatever caused the crash, and
            // markSuccess (60 s of healthy uptime) clears the entry.
            runCatching {
                UpdatePrefs(context).addKnownBadVersionCode(current.toLong())
            }.onFailure { Log.w(TAG, "failed to mark $current as known-bad: $it") }
            val rolled = UpdateInstaller.rollback(context)
            return if (rolled) Outcome.ROLLBACK_TRIGGERED else Outcome.ROLLBACK_FAILED
        }

        scope.launch {
            delay(HEALTH_DELAY_MS)
            markSuccess()
        }
        return Outcome.HEALTHY
    }

    private fun markSuccess() {
        prefs.edit()
            .putInt(KEY_ATTEMPTS, 0)
            .putInt(KEY_LAST_GOOD_VERSION, BuildConfig.VERSION_CODE)
            .putLong(KEY_LAST_GOOD_AT, System.currentTimeMillis())
            .apply()
        // If the current versionCode was blocklisted (user installed
        // it manually after a previous failure, or shipped a fix that
        // happens to share the same versionCode), clear the entry so
        // the worker can auto-install future builds without us
        // accumulating stale entries forever.
        runCatching {
            UpdatePrefs(context).removeKnownBadVersionCode(BuildConfig.VERSION_CODE.toLong())
        }
        // Successful boot means we can drop the rollback APK we held in
        // reserve from the last update.
        // (We keep it until at least one success: that's our A/B
        //  guarantee. After success, we still want a previous APK for
        //  the *next* update, so we leave it in place — it'll be
        //  overwritten by stashCurrentApk on the next update install.)
    }

    private fun installCrashHandler() {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Persist the FULL stack trace to a file (not just toString):
            // a debuggable build with no adb access still needs to surface
            // *where* it died, not only the exception class. Read back by
            // lastCrashReport() and shown on next launch in debug builds.
            runCatching {
                val sw = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(sw))
                val when_ = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US,
                ).format(java.util.Date())
                java.io.File(context.filesDir, CRASH_FILE).writeText(
                    "Aegis ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "$when_  ·  thread=${thread.name}\n\n$sw",
                )
            }
            prefs.edit()
                .putString(KEY_LAST_CRASH, error.toString().take(512))
                .putLong(KEY_LAST_CRASH_AT, System.currentTimeMillis())
                .commit()  // commit not apply: must hit disk before the process dies
            existing?.uncaughtException(thread, error)
        }
    }

    /** Full stack trace of the most recent crash, or null if none recorded
     *  (or already cleared). Written by the uncaught-exception handler. */
    fun lastCrashReport(): String? =
        java.io.File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText()

    /** Wall-clock time of the last crash, 0 if none. */
    fun lastCrashAt(): Long = prefs.getLong(KEY_LAST_CRASH_AT, 0L)

    /** Drop the saved crash report once the user has seen it. */
    fun clearCrashReport() {
        runCatching { java.io.File(context.filesDir, CRASH_FILE).delete() }
        prefs.edit().remove(KEY_LAST_CRASH).remove(KEY_LAST_CRASH_AT).apply()
    }

    /**
     * The most recent ABNORMAL process death from Android's own exit-reason
     * registry (API 30+), formatted for display — or null if there's
     * nothing new to show.
     *
     * This is the piece the Java [installCrashHandler] CANNOT see: a
     * `Thread.setDefaultUncaughtExceptionHandler` only fires for uncaught
     * JVM exceptions. A **native (JNI) crash**, an **ANR**, and a
     * **low-memory kill** all tear the process down via signal, so they
     * leave no `last_crash.txt`. `getHistoricalProcessExitReasons` records
     * them anyway, with a native tombstone trace for native crashes — no
     * adb, no root.
     *
     * Returns null on pre-R, when the last death was a normal/user exit, or
     * when we've already surfaced this exact death (deduped by timestamp via
     * [markExitReasonSeen]) so it doesn't nag on every launch.
     */
    fun lastExitReason(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        // pid=0 → all pids for our package; cap at a few most-recent.
        val info = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        }.getOrNull()?.firstOrNull() ?: return null

        // Only surface deaths the user would call a "crash". Normal exits
        // (user swiped away, self-stop, OS reclaiming a backgrounded app on
        // a low-RAM device during normal use) are noise. SIGNALED +
        // CRASH_NATIVE are the native faults; CRASH is the JVM one (also
        // caught by our handler, but harmless to echo); ANR + the resource
        // kills are the rest of what we miss.
        val abnormal = info.reason in setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_LOW_MEMORY,
        )
        if (!abnormal) return null

        // Dedup: don't re-surface a death we've already shown.
        if (info.timestamp <= prefs.getLong(KEY_LAST_EXIT_SHOWN, 0L)) return null

        val reasonName = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH (JNI / core)"
            ApplicationExitInfo.REASON_CRASH -> "JVM crash"
            ApplicationExitInfo.REASON_ANR -> "ANR (app not responding)"
            ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW MEMORY (OS reclaimed)"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
            else -> "reason ${info.reason}"
        }
        val when_ = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss", java.util.Locale.US,
        ).format(java.util.Date(info.timestamp))
        // Native crashes + ANRs carry a tombstone/trace; pull it if present.
        val trace = runCatching {
            info.traceInputStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        return buildString {
            append("Process death: $reasonName\n")
            append("$when_  ·  importance=${info.importance}\n")
            info.description?.let { append("desc: $it\n") }
            if (!trace.isNullOrBlank()) {
                append("\n")
                append(trace)
            } else {
                append("\n(No trace attached — reason code only. ")
                append("Native tombstones aren't always exported to the app; ")
                append("an adb `dumpsys activity exit-info` shows the full one.)")
            }
        }
    }

    /** Mark the latest exit-reason as seen so [lastExitReason] stops
     *  surfacing it. Call when the user dismisses/clears the card. */
    fun markExitReasonSeen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val ts = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 1)
        }.getOrNull()?.firstOrNull()?.timestamp ?: return
        prefs.edit().putLong(KEY_LAST_EXIT_SHOWN, ts).apply()
    }

    enum class Outcome { HEALTHY, ROLLBACK_TRIGGERED, ROLLBACK_FAILED }

    private companion object {
        private const val TAG = "BootHealthMonitor"
        private const val KEY_ATTEMPTS = "boot_attempts"
        private const val KEY_LAST_GOOD_VERSION = "last_good_version"
        private const val KEY_LAST_GOOD_AT = "last_good_at"
        private const val KEY_LAST_CRASH = "last_crash"
        private const val KEY_LAST_CRASH_AT = "last_crash_at"
        // Timestamp of the most recent ApplicationExitInfo death we've
        // already surfaced, so abnormal-exit reporting doesn't nag.
        private const val KEY_LAST_EXIT_SHOWN = "last_exit_shown"
        private const val CRASH_FILE = "last_crash.txt"
        private const val MAX_FAILED_BOOTS = 3
        private const val HEALTH_DELAY_MS = 60_000L
    }
}

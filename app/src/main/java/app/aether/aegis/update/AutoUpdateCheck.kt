package app.aether.aegis.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Cold-start update check. Called from AegisApp.onCreate so that
 * opening the app after it was closed (not backgrounded — fully
 * killed, then relaunched) checks GitHub for new builds in
 * addition to the 24 h worker.
 *
 * Rate-limited via UpdatePrefs.lastCheckAt — we won't hit GitHub more
 * than once an hour even if the user force-quits and reopens
 * repeatedly. Respects the WiFi-only setting (matches the worker).
 *
 * Result is fire-and-forget: success → UpdateState set to Available (or
 * Downloaded if the APK is already on disk), which the launch-time
 * UpdateAvailableDialog picks up to ASK the user whether to download +
 * install. We never auto-download. Failure → silent (Settings still
 * shows the previous status).
 */
object AutoUpdateCheck {

    private const val TAG = "AutoUpdateCheck"
    private const val MIN_INTERVAL_MS = 60L * 60_000L  // 1 hour

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun maybeRun(context: Context) {
        val appCtx = context.applicationContext
        // Seed status from disk first so a previously-downloaded APK
        // surfaces as "Install" instantly, without needing GitHub
        // contact. Idempotent — does nothing if no update.apk exists
        // or if the existing one is stale.
        UpdateState.reconcileFromDisk(appCtx)
        val prefs = UpdatePrefs(appCtx)
        val now = System.currentTimeMillis()
        if (now - prefs.lastCheckAt < MIN_INTERVAL_MS) {
            Log.i(TAG, "Skipping auto-check: last ran ${(now - prefs.lastCheckAt) / 1000}s ago")
            return
        }
        if (prefs.wifiOnly && app.aether.aegis.net.NetworkMetering.isMetered(appCtx)) {
            Log.i(TAG, "Skipping auto-check: WiFi-only and currently metered")
            return
        }
        prefs.lastCheckAt = now
        app.aether.aegis.AegisApp.appScope.launch(Dispatchers.IO) {
            runCatching {
                val token = SecretsStore(appCtx).githubToken
                val client = UpdateClient(token = token)
                val outcome = client.check(appCtx)
                if (outcome is UpdateClient.CheckOutcome.UpdateAvailable) {
                    val release = outcome.release
                    // If a download already happened (manual or via
                    // the background worker) AND it matches the SHA
                    // GitHub is now reporting, skip straight to
                    // Downloaded so the UI offers Install instead of
                    // asking for a re-download.
                    // If a download already happened (manual or via the
                    // background worker) AND it matches the SHA GitHub now
                    // reports, skip straight to Downloaded so the launch
                    // prompt / Settings offers Install instead of asking for
                    // a re-download. Otherwise surface Available — the
                    // launch prompt (UpdateAvailableDialog) asks the user
                    // whether to download + install. We do NOT auto-download.
                    val apk = java.io.File(appCtx.filesDir, "update.apk")
                    val onDiskMatches = apk.exists() &&
                        runCatching { UpdateClient.sha256Of(apk) }
                            .getOrNull() == release.sha
                    if (onDiskMatches) {
                        UpdateState.set(UpdateState.Status.Downloaded(release, apk.absolutePath))
                        Log.i(TAG, "Auto-check: already downloaded ${release.shortSha}")
                    } else {
                        UpdateState.set(UpdateState.Status.Available(release))
                        Log.i(TAG, "Auto-check found ${release.shortSha}")
                    }
                } else {
                    Log.i(TAG, "Auto-check: $outcome")
                }
            }.onFailure { Log.w(TAG, "Auto-check failed: $it") }
        }
    }
}

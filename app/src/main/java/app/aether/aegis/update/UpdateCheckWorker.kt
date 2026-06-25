package app.aether.aegis.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Auto-update worker.
 *
 * Polls github.com/<BuildConfig.UPDATE_REPO> every 24 hours, downloads
 * the APK to filesDir/update.apk, and hands off to UpdateInstaller.
 * Basic mode prompts the user; Full mode silent-installs if Device
 * Owner. The repo flipped at build time by the release-channel split
 * — debug polls the private dev repo, release polls Aegis (public).
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Defensive: a periodic work record can survive an app update, so
        // even though schedule() never enqueues on the Play build, refuse
        // to run if a stale schedule fires there.
        if (!app.aether.aegis.BuildConfig.SELF_UPDATE) return Result.success()
        return runCatching {
            // PowerBudget gate — update polling is the first subsystem
            // to shed under the Voyager curve (≤80 % shuts off). If the
            // battery is below the threshold we report success without
            // doing any work; the worker will retry on its normal 24 h
            // cadence and pick back up when the phone is back above
            // the hysteresis ceiling.
            app.aether.aegis.AegisApp.instance.powerBudget.refresh()
            if (!app.aether.aegis.AegisApp.instance.powerBudget.shouldRunUpdateCheck()) {
                return Result.success()
            }
            // Double-check the metered-network policy. WorkManager's
            // constraint should have prevented this from running on
            // cellular when wifiOnly is set, but the constraint relies
            // on the OS-level activeNetwork classification which can
            // lag a transition. A second check inside the worker
            // means we never burn a paying user's data because of a
            // race.
            val prefs = UpdatePrefs(applicationContext)
            if (prefs.wifiOnly && currentlyMetered(applicationContext)) {
                return Result.success()
            }
            val token = SecretsStore(applicationContext).githubToken
            val client = UpdateClient(token = token)
            val outcome = client.check(applicationContext)
            if (outcome is UpdateClient.CheckOutcome.UpdateAvailable) {
                // check() already filters out blocklisted (rolled-back-from /
                // crash-rolled) builds, so anything that reaches here is an
                // offerable build.
                val release = outcome.release
                // If a download already happened (manual + left app)
                // AND it matches the SHA GitHub is now reporting, skip
                // straight to Downloaded so Settings offers Install
                // instead of asking for a re-download.
                val apk = File(applicationContext.filesDir, "update.apk")
                val onDiskMatches = apk.exists() &&
                    runCatching { UpdateClient.sha256Of(apk) }
                        .getOrNull() == release.sha
                if (onDiskMatches) {
                    UpdateState.set(UpdateState.Status.Downloaded(release, apk.absolutePath))
                } else {
                    UpdateState.set(UpdateState.Status.Available(release))
                }
                // DO-only auto-download + auto-install. With Device
                // Owner status the user has explicitly trusted Aegis
                // to manage the device — the entire point of DO is
                // updates that happen without user involvement. We
                // download the APK in the worker, then hand off to
                // UpdateInstaller.beginInstall with silent=true. The
                // silentInstall path now sets INSTALL_REASON_POLICY
                // when DO is present, so the install completes
                // without any system dialog or notification.
                //
                // Non-DO devices: untouched. The Available status
                // surfaces in Settings, the user picks "Download &
                // install" when ready.
                if (UpdateInstaller.isDeviceOwner(applicationContext) && !onDiskMatches) {
                    // Skip auto-install if this versionCode previously
                    // triggered a rollback. BootHealthMonitor adds the
                    // current versionCode to UpdatePrefs.knownBad
                    // before calling rollback(); we honour that here so
                    // a single bad release can't spin the device in an
                    // install → crash → rollback → re-install loop.
                    // Manual install from Settings still works — this
                    // is purely the unattended worker path. Status
                    // stays Available, so the user sees the broken
                    // build offered and can decide if they trust it.
                    val blocked = prefs.knownBadVersionCodes.contains(release.versionCode)
                    // Install-rate throttle. If we auto-installed within
                    // the last AUTO_INSTALL_MIN_GAP_MS, skip — the user
                    // may have set a short check interval (15 min floor)
                    // and we don't want to download 80 MB + silent-
                    // install every tick when we're shipping multiple
                    // builds per hour. Status stays Available so manual
                    // install from Settings still works for users who
                    // explicitly want the newest.
                    val now = System.currentTimeMillis()
                    val gap = now - prefs.lastAutoInstallAt
                    val rateLimited = gap < AUTO_INSTALL_MIN_GAP_MS
                    if (blocked || rateLimited) {
                        // No download, no install — leave Status.Available
                        // so Settings shows "tap Update" for the user
                        // who knows what they're doing.
                    } else {
                        val dlOutcome = client.downloadApk(release, apk)
                        if (dlOutcome is UpdateClient.DownloadOutcome.Ok) {
                            UpdateState.set(
                                UpdateState.Status.Downloaded(release, apk.absolutePath),
                            )
                            UpdateState.set(UpdateState.Status.Installing(release))
                            prefs.lastAutoInstallAt = now
                            UpdateInstaller.beginInstall(
                                applicationContext, apk,
                                silent = true,
                                foreground = false,
                            )
                        }
                        // If download failed we just leave Available
                        // status set — the next worker tick or a cold
                        // start will retry. No notification, no toast —
                        // matches the "you don't even know it updated"
                        // contract.
                    }
                }
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val UNIQUE_NAME = "aegis-update-check"

        /** Minimum gap between unattended auto-installs from the
         *  periodic worker. Two hours covers the "dev session
         *  shipping multiple builds per hour" case (the cause of the
         *  2026.05.685 heat regression) without holding back genuine
         *  security updates noticeably — a real security patch reaches
         *  the device on the very next tick, just not on the tick
         *  immediately after a previous auto-install. Manual install
         *  from Settings bypasses this entirely. */
        private const val AUTO_INSTALL_MIN_GAP_MS = 2L * 60L * 60L * 1000L

        /** Schedule (or re-schedule) the periodic worker at the
         *  user-configured cadence. Pass null in UpdatePrefs to
         *  cancel the worker outright (the "Never" snap on the
         *  LogPeriodSlider). Re-call after any preference change so
         *  the network constraint + interval stay in sync — WorkManager
         *  ignores updates with KEEP policy. */
        fun schedule(context: Context) {
            // Play build (SELF_UPDATE=false) doesn't self-update — Play
            // owns updates and the install permissions aren't present.
            // Cancel any stale schedule and never enqueue.
            if (!app.aether.aegis.BuildConfig.SELF_UPDATE) {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
                return
            }
            val prefs = UpdatePrefs(context)
            val intervalSec = prefs.checkIntervalSeconds
            if (intervalSec == null) {
                // User picked "Never" — cancel any running schedule.
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
                return
            }
            // Constraint is always CONNECTED — the wifiOnly preference is
            // enforced by the transport-based check inside doWork(), not
            // by WorkManager's NetworkType.UNMETERED. UNMETERED depends
            // on the OS-level NET_CAPABILITY_NOT_METERED bit, which Pixel
            // sometimes drops on perfectly normal home WiFi (captive
            // portals, hotspot inheritance, VPN-on-WiFi) and effectively
            // means "wifi-only update channel never fires while on WiFi".
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            // WorkManager periodic floor is 15 min — clamp so a too-
            // aggressive slider value doesn't silently no-op.
            val clamped = intervalSec.coerceAtLeast(15L * 60L)
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                clamped, TimeUnit.SECONDS,
            )
                .setConstraints(constraints)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** True if the current connection should be treated as "burning
         *  data" for update-channel purposes. Transport-based, not
         *  capability-based: NET_CAPABILITY_NOT_METERED is famously
         *  unreliable on Pixel (WiFi gets misclassified as metered when
         *  it inherits a metered upstream, behind a captive portal, or
         *  on VPN-over-WiFi). We trust the transport bit, which the OS
         *  gets right: WiFi or Ethernet → safe; cellular / Bluetooth-
         *  tether → metered. VPN inspects its underlying transport
         *  (e.g. VPN over WiFi reads as WiFi). */
        private fun currentlyMetered(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return true
            val net = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(net) ?: return true
            val onWifiOrEthernet =
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            return !onWifiOrEthernet
        }
    }
}

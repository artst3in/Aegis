package app.aether.aegis.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide update flow state machine.
 *
 * Single sealed [Status] replaces the previous flat `status/busy/pending`
 * triple — the UI can now drive a single button whose label/enabled state
 * is derived from one source of truth instead of three:
 *
 *   Idle         → "Check now"
 *   Checking     → "Checking…" (disabled)
 *   UpToDate     → "Check now" + subtext
 *   Available    → "Download"  (release on GitHub, no local APK yet)
 *   Downloading  → "Downloading…" (disabled)
 *   Downloaded   → "Install"   (local APK matches remote release SHA)
 *   Failed       → "Retry" + error
 *
 * The Downloaded state is computed by comparing the local
 * `filesDir/update.apk`'s git-blob SHA against the remote release SHA.
 * If a check finds a new release whose SHA matches an APK already on
 * disk, we skip straight to Install — fixes the "downloaded, left app,
 * checked again, asked to download again" bug.
 *
 * State lives outside Compose so a long-running download survives the
 * user scrolling, navigating, or rotating.
 */
object UpdateState {

    sealed class Status {
        object Idle : Status()
        object Checking : Status()
        object UpToDate : Status()
        data class Available(val release: UpdateClient.ReleaseInfo) : Status()
        /** Bytes downloaded + total. [totalBytes] is null when the
         *  server omits Content-Length (rare on GitHub raw blobs);
         *  the UI then renders an indeterminate progress bar. */
        data class Downloading(
            val release: UpdateClient.ReleaseInfo,
            val bytesDownloaded: Long = 0L,
            val totalBytes: Long? = null,
        ) : Status()
        data class Downloaded(val release: UpdateClient.ReleaseInfo, val apkPath: String) : Status()
        /** Session committed via PackageInstaller, awaiting either the
         *  confirm dialog launch (via InstallSessionReceiver) or the
         *  STATUS_SUCCESS callback. UI uses this to disable the
         *  primary button so users don't tap Install twice and stack
         *  two sessions on top of each other. */
        data class Installing(val release: UpdateClient.ReleaseInfo) : Status()
        data class Failed(val message: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    fun set(s: Status) {
        // Any state change other than entering Downloading clears a
        // stale cancel flag — otherwise a user who cancels mid-flight
        // and then starts a fresh download would have the new one die
        // on the first read-loop check.
        if (s !is Status.Downloading) cancelRequested.set(false)
        _status.value = s
    }

    /** Set by the Settings UI when the user taps "Cancel" on an
     *  in-flight download. UpdateClient.downloadApk checks this flag
     *  every chunk and aborts (cleaning up the .part file) when set.
     *  AtomicBoolean rather than a flow because the IO coroutine
     *  reads it on every read() pass — a hot loop, no need to
     *  collect a Flow. */
    val cancelRequested: AtomicBoolean = AtomicBoolean(false)

    /** True iff the user should see "update available" anywhere — drives
     *  the Settings-tab badge dot. */
    val pendingForBadge: StateFlow<Boolean> get() = _pendingDerived
    private val _pendingDerived = MutableStateFlow(false)

    init {
        // Re-derive the badge flag whenever status changes.
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        app.aether.aegis.AegisApp.appScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _status.collect { s ->
                _pendingDerived.value = s is Status.Available ||
                    s is Status.Downloading ||
                    s is Status.Downloaded ||
                    s is Status.Installing
            }
        }
    }

    /** Boot-time / on-resume sweep: if there's an update.apk on disk
     *  whose versionCode is newer than what's installed, set
     *  Downloaded so the UI offers Install instead of asking the user
     *  to re-check. Cleans up stale APKs whose versionCode matches or
     *  is older than installed (post-install leftover).
     *
     *  Also rescues a stuck Installing state: the ACTION_VIEW system
     *  installer path (non-Device-Owner) gives us no callback when
     *  the user cancels the system prompt, so the UI would otherwise
     *  sit on "Installing…" forever. On resume we either flip to
     *  Idle (install succeeded — apk is stale) or back to Downloaded
     *  (apk still newer than installed, user can retry). */
    fun reconcileFromDisk(context: Context) {
        val apk = File(context.filesDir, "update.apk")
        // No APK on disk — if we're stuck in Installing, install
        // succeeded (or someone deleted it). Drop to Idle either way.
        if (!apk.exists()) {
            if (_status.value is Status.Installing) _status.value = Status.Idle
            return
        }
        val pm = context.packageManager
        val info = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull()
        if (info == null) {
            apk.delete()
            if (_status.value is Status.Installing) _status.value = Status.Idle
            return
        }
        val apkCode = info.longVersionCode
        val installedCode = runCatching {
            pm.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        if (apkCode <= installedCode) {
            // Install succeeded — clean up leftover APK and clear
            // any stuck Installing/Downloaded state.
            apk.delete()
            if (_status.value is Status.Installing ||
                _status.value is Status.Downloaded) {
                _status.value = Status.Idle
            }
            return
        }
        // Synthesise minimal ReleaseInfo from APK metadata so the UI
        // can render "Install (2026.05.331)" without re-checking the
        // manifest. shortSha here is a misnomer (we use it as the
        // display label across the update screen, not necessarily a
        // git SHA); prefer the APK's versionName (= the Aether
        // YYYY.MM.BBB cargo version) over the
        // raw versionCode integer. No "v" prefix anywhere — Aether
        // cargo is bare-numeric.
        val displayVersion = info.versionName?.takeIf { it.isNotBlank() } ?: apkCode.toString()
        val release = UpdateClient.ReleaseInfo(
            sha = "",
            shortSha = displayVersion,
            downloadUrl = "",
            notes = "Downloaded · $displayVersion",
            // reconcileFromDisk synthesises a placeholder ReleaseInfo
            // from a pre-existing update.apk — used to surface the
            // "Installable" state on cold start without re-hitting
            // GitHub. We have the local apkCode here, so propagate it
            // as the versionCode for known-bad checks downstream.
            versionCode = apkCode,
        )
        _status.value = Status.Downloaded(release, apk.absolutePath)
    }
}

package app.aether.aegis.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.aether.aegis.update.SecretsStore
import app.aether.aegis.update.UpdateClient
import app.aether.aegis.update.UpdateInstaller
import app.aether.aegis.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Launch-time "update available — install now?" prompt.
 *
 * AutoUpdateCheck runs on cold start and only CHECKS — it sets UpdateState
 * to Available (or Downloaded if the APK is already on disk) and never
 * auto-downloads. This dialog observes that and ASKS the user whether to
 * download + install:
 *   - "Update" runs the same single-tap download → install as the Settings
 *     screen (beginInstall(silent) self-adapts: silent where Aegis is the
 *     installer of record + signing matches, else a one-tap system confirm).
 *   - "Later" defers for this session, keyed by version SHA so a *newer*
 *     build still prompts even after an earlier deferral.
 *
 * Hosted in MainActivity's unlocked content, so it never appears over the
 * lock screen.
 */
@Composable
fun UpdateAvailableDialog() {
    val status by UpdateState.status.collectAsState()
    val context = LocalContext.current
    var dismissedSha by remember { mutableStateOf<String?>(null) }

    // Only prompt for an update the user can act on right now.
    val release = when (val s = status) {
        is UpdateState.Status.Available -> s.release
        is UpdateState.Status.Downloaded -> s.release
        else -> null
    } ?: return
    if (release.sha == dismissedSha) return

    AlertDialog(
        onDismissRequest = { dismissedSha = release.sha },
        title = { Text(stringResource(R.string.update_available)) },
        text = {
            Text(
                "A new version of Aegis is available (build ${release.versionCode}). " +
                    "Download and install it now?",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                dismissedSha = release.sha
                Toast.makeText(context, "Downloading update…", Toast.LENGTH_SHORT).show()
                startDownloadAndInstall(context.applicationContext, release)
            }) { Text(stringResource(R.string.update_dialog_update)) }
        },
        dismissButton = {
            TextButton(onClick = { dismissedSha = release.sha }) { Text(stringResource(R.string.update_dialog_later)) }
        },
    )
}

/**
 * Mirrors the Settings screen's single-tap download → install: the user
 * authorised both phases with one tap, so chain straight through. Skips
 * the download when the matching APK is already on disk.
 */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
private fun startDownloadAndInstall(appContext: Context, release: UpdateClient.ReleaseInfo) {
    UpdateState.set(UpdateState.Status.Downloading(release))
    app.aether.aegis.AegisApp.appScope.launch(Dispatchers.IO) {
        val apk = java.io.File(appContext.filesDir, "update.apk")
        val onDisk = apk.exists() &&
            runCatching { UpdateClient.sha256Of(apk) }.getOrNull() == release.sha
        if (onDisk) {
            UpdateState.set(UpdateState.Status.Installing(release))
            UpdateInstaller.beginInstall(appContext, apk, silent = true, foreground = true)
            return@launch
        }
        val token = SecretsStore(appContext).githubToken
        val client = UpdateClient(token = token)
        val outcome = client.downloadApk(
            release = release,
            dest = apk,
            isCancelled = { UpdateState.cancelRequested.get() },
            onProgress = { bytes, total ->
                UpdateState.set(UpdateState.Status.Downloading(release, bytes, total))
            },
        )
        when (outcome) {
            is UpdateClient.DownloadOutcome.Ok -> {
                UpdateState.set(UpdateState.Status.Installing(release))
                UpdateInstaller.beginInstall(appContext, apk, silent = true, foreground = true)
            }
            is UpdateClient.DownloadOutcome.Cancelled ->
                UpdateState.set(UpdateState.Status.Available(release))
            is UpdateClient.DownloadOutcome.Failed ->
                UpdateState.set(UpdateState.Status.Failed("Download failed; try again."))
        }
    }
}

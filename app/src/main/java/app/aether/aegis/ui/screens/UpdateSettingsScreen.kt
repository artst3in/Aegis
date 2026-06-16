package app.aether.aegis.ui.screens

import app.aether.aegis.update.UpdateCheckWorker
import app.aether.aegis.update.UpdatePrefs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.ui.components.GlassPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Controls when the auto-update worker is allowed to download new
 * APK builds. WiFi-only by default — a single APK pull is ~45–65 MB,
 * which is a non-trivial bite out of a cellular plan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { UpdatePrefs(context) }
    // Local mirrors of the two persisted update prefs, kept as Compose
    // state so the toggle / slider re-render instantly; each onChange
    // writes through to prefs AND re-schedules the worker (the worker's
    // network constraint and cadence are baked at schedule time).
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }
    var checkInterval by remember { mutableStateOf(prefs.checkIntervalSeconds) }
    // The rollback panel only matters when there's actually a
    // previous.apk on disk — UpdateInstaller.stashCurrentApk dropped
    // one there before the most recent install. Without that file
    // there's nothing to roll back TO.
    val previousApk = remember { java.io.File(context.filesDir, "previous.apk") }
    // Gate the whole rollback panel on the stash file being present AND
    // non-empty — a zero-length leftover would offer a rollback that
    // installs nothing.
    val canRollback = remember { previousApk.exists() && previousApk.length() > 0 }
    // Best-effort version label for the stashed build, parsed straight
    // from the APK archive (no install needed). Null when there's no
    // stash or the archive can't be read — the UI just omits the
    // "(was X)" suffix in that case.
    val previousVersion = remember {
        if (!canRollback) null
        else runCatching {
            val info = context.packageManager.getPackageArchiveInfo(previousApk.absolutePath, 0)
            info?.versionName
        }.getOrNull()
    }
    // Two-step rollback gate: 0 = idle, 1 = first confirmation dialog,
    // 2 = type-to-confirm dialog. Drives the `when (rollbackStep)` below.
    var rollbackStep by remember { mutableStateOf(0) }
    // The text the user types in step 2; must equal "ROLLBACK" (case-
    // insensitive) to arm the final button.
    var typedConfirm by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_updates)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Text("←", fontSize = 20.sp)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The action panel — Check / Download / Install / Skip.
            // Used to live as an inline UpdateCard on the main
            // Settings screen; the 2026.06.200 reorg deleted it
            // assuming this screen already hosted the button. It
            // didn't — restored here at the top so "tap Updates →
            // tap Check" still works.
            UpdateActionPanel()

            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.settings_how_it_works), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.update_aegis_polls_github_every) +
                            "Each APK is ~45–65 MB. By default we only download " +
                            "over WiFi — pulling that much over a cellular plan " +
                            "every release would eat real money.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.update_check_interval), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.update_how_often_aegis_polls) +
                            "phone more often; Never disables the background worker entirely " +
                            "(you can still tap Check now).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    app.aether.aegis.ui.components.LogPeriodSlider(
                        valueSeconds = checkInterval,
                        onValueChange = { v ->
                            checkInterval = v
                            prefs.checkIntervalSeconds = v
                            UpdateCheckWorker.schedule(context)
                        },
                        // 15 min = WorkManager's PeriodicWork floor on
                        // AOSP. 1 month upper end — anything slower
                        // than that and you should just pick Never;
                        // an update check that runs once a quarter is
                        // a pretense, not a policy. Previously instant
                        // and min were both 1 hour, so "1 hour"
                        // appeared at TWO slider positions (the
                        // left-zone snap and the start of the log
                        // range), and max was 1 year — both
                        // user-reported 2026.05.619.
                        minSeconds = 60.0 * 60,                 // 1 hour
                        maxSeconds = 30.0 * 24 * 3600,          // 1 month
                        instantSeconds = 15L * 60,              // 15 min
                        allowNever = true,
                        instantLabel = "15 min",
                        neverLabel = "Never",
                    )
                }
            }

            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_wifi_only), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (wifiOnly) "Updates download only over Wi-Fi (recommended)."
                            else "Updates download over any connection — cellular included.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    app.aether.aegis.ui.components.HexSwitch(
                        checked = wifiOnly,
                        onCheckedChange = {
                            wifiOnly = it
                            prefs.wifiOnly = it
                            // Re-schedule the worker so the new network
                            // constraint takes effect on the next 24 h
                            // tick — KEEP policy would ignore the
                            // toggle until an app upgrade.
                            UpdateCheckWorker.schedule(context)
                        },
                    )
                }
            }

            // Roll-back panel — UpdateInstaller stashes the running APK
            // as previous.apk before each install, so we can always
            // re-install the immediately-prior version. Useful when a
            // new build breaks something user-visible and BootHealth
            // didn't trip its auto-rollback heuristic. Gated on the
            // file actually existing so we don't show a button that
            // does nothing on a clean install.
            if (canRollback) {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            stringResource(R.string.update_roll_back_to_previous),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            buildString {
                                append("Re-installs the build that was running before ")
                                append("the most recent update")
                                previousVersion?.let { append(" (was $it)") }
                                append(". Your data is preserved.")
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { rollbackStep = 1 },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Roll back to previous build",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    // Two-step rollback confirmation. Less destructive than WIPE
    // (data survives) so we don't need the full three-gate WIPE
    // ladder — but still a "tap then type to confirm" guard so a
    // stray tap doesn't kick a reinstall.
    when (rollbackStep) {
        1 -> AlertDialog(
            onDismissRequest = { rollbackStep = 0 },
            title = { Text("Roll back to previous build?") },
            text = {
                Text(
                    stringResource(R.string.update_the_system_installer_will) +
                        "tap Install to confirm, depending on Android " +
                        "version. Your data, settings, contacts, and " +
                        "messages are preserved.",
                )
            },
            confirmButton = {
                TextButton(onClick = { rollbackStep = 2; typedConfirm = "" }) {
                    Text(stringResource(R.string.tutorial_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { rollbackStep = 0 }) { Text(stringResource(R.string.secure_notes_cancel)) }
            },
        )
        2 -> AlertDialog(
            onDismissRequest = { rollbackStep = 0 },
            title = { Text(stringResource(R.string.update_type_rollback_to_confirm)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.update_beltandbraces_type_rollback_below) +
                            "prove you mean it.",
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = typedConfirm,
                        onValueChange = { typedConfirm = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.update_rollback)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typedConfirm.trim().equals("ROLLBACK", ignoreCase = true),
                    onClick = {
                        rollbackStep = 0
                        app.aether.aegis.update.UpdateInstaller.rollback(context)
                    },
                ) {
                    Text(stringResource(R.string.update_roll_back), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { rollbackStep = 0 }) { Text(stringResource(R.string.secure_notes_cancel)) }
            },
        )
    }
}

/**
 * The check / download / install action panel. Restored here after
 * the 2026.06.200 Settings reorg accidentally deleted the old
 * inline UpdateCard without re-hosting its functionality (user
 * report 2026.06.200: "where the fuck is the check update
 * button"). Placed at the TOP of UpdateSettingsScreen so the tap
 * path is one extra hop than before — Settings → Updates → Check
 * — and that hop has been visible at first glance ever since.
 *
 * Single primary button that adapts to [UpdateState.Status]:
 *   - Idle / UpToDate / Failed → "Check for updates"
 *   - Available → "Update (sha)"
 *   - Downloading → "Downloading…" (with cancel button)
 *   - Downloaded → "Update (sha)" (single-tap re-install)
 *   - Installing → "Installing…" (disabled)
 *
 * Plus a GitHub PAT field (private-repo auth) and a Skip button
 * that nukes the staged APK when something is pending.
 */
@Composable
private fun UpdateActionPanel() {
    val context = LocalContext.current
    val secrets = remember { app.aether.aegis.update.SecretsStore(context) }
    val status by app.aether.aegis.update.UpdateState.status.collectAsState()
    // Re-derive the disk-side state every time this screen comes
    // back to the foreground — covers the path where the user left
    // the app while a download was in flight from the worker.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                app.aether.aegis.update.UpdateState.reconcileFromDisk(context.applicationContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(stringResource(R.string.settings_updates), fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            // Channel + source repo line — small dim sub-label so the
            // user can tell at a glance which build type they're on
            // and where the auto-update worker polls. Values come
            // straight from BuildConfig, which the release-channel
            // split flips per build type (HAS_DEBUG_CHANNEL,
            // UPDATE_REPO, RELEASE_CHANNEL). Always shown — both
            // variants want the visibility.
            Text(
                "Channel: ${app.aether.aegis.BuildConfig.RELEASE_CHANNEL} · " +
                    "polling ${app.aether.aegis.BuildConfig.UPDATE_REPO}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                statusLabel(status),
                color = statusColor(status),
                fontSize = 12.sp,
            )
            // Live download progress — determinate when the server
            // advertised Content-Length, indeterminate when it didn't.
            (status as? app.aether.aegis.update.UpdateState.Status.Downloading)?.let { dl ->
                Spacer(modifier = Modifier.height(6.dp))
                val total = dl.totalBytes
                if (total != null && total > 0L) {
                    val frac = (dl.bytesDownloaded.toFloat() / total.toFloat())
                        .coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth(),
                        drawStopIndicator = {},
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "%.1f / %.1f MB · %d%%".format(
                            dl.bytesDownloaded / 1_048_576.0,
                            total / 1_048_576.0,
                            (frac * 100).toInt(),
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "%.1f MB downloaded".format(dl.bytesDownloaded / 1_048_576.0),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // NB: do NOT capture secrets.githubToken here — it's read fresh at
            // click time below so a just-saved token is used on the FIRST check.
            val appContext = context.applicationContext
            val busy = status is app.aether.aegis.update.UpdateState.Status.Checking ||
                status is app.aether.aegis.update.UpdateState.Status.Downloading ||
                status is app.aether.aegis.update.UpdateState.Status.Installing
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    // Protected Mode: the self-update controls can be locked.
                    enabled = !busy &&
                        !isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.UPDATES),
                    onClick = {
                        // Read BOTH the token and the status FRESH at click
                        // time. SecretsStore is plain SharedPreferences (not
                        // observed by Compose), so a token just saved in the
                        // token entry field doesn't recompose this screen — a
                        // composition-captured `token` would still be the OLD
                        // value, so the FIRST check after saving a token always
                        // failed (empty/stale token), and only the second click
                        // — after the failure recomposed us — saw it. Reading
                        // secrets.githubToken here picks up the save immediately.
                        // (status likewise read fresh to dodge a collectAsState
                        // frame-lag race.)
                        runUpdateAction(
                            appContext,
                            secrets.githubToken,
                            app.aether.aegis.update.UpdateState.status.value,
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(actionLabel(status)) }
                // Cancel — only meaningful while bytes are streaming.
                if (status is app.aether.aegis.update.UpdateState.Status.Downloading) {
                    OutlinedButton(
                        onClick = {
                            app.aether.aegis.update.UpdateState.cancelRequested.set(true)
                        },
                    ) { Text(stringResource(R.string.secure_notes_cancel)) }
                }
                // Skip — only meaningful when something is pending
                // (Available = nothing downloaded yet, Downloaded = staged
                // on disk). Nuke any staged update.apk and drop back to
                // Idle so the panel stops nagging; the next check will
                // re-offer it if the release is still newer.
                if (status is app.aether.aegis.update.UpdateState.Status.Available ||
                    status is app.aether.aegis.update.UpdateState.Status.Downloaded) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            runCatching {
                                java.io.File(appContext.filesDir, "update.apk").delete()
                            }
                            app.aether.aegis.update.UpdateState.set(app.aether.aegis.update.UpdateState.Status.Idle)
                        },
                    ) { Text(stringResource(R.string.tutorial_skip)) }
                }
            }
        }
    }
}

/** Drive the single primary button. The action depends on which Status
 *  the system is currently in. */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
private fun runUpdateAction(
    appContext: android.content.Context,
    token: String?,
    status: app.aether.aegis.update.UpdateState.Status,
) {
    when (status) {
        is app.aether.aegis.update.UpdateState.Status.Available -> {
            // Respect WiFi-only. Background worker already had this
            // via its UNMETERED network constraint; manual Download
            // honours the same setting so the user isn't burning
            // cellular gigabytes on the very setting they thought
            // protected them.
            val prefs = app.aether.aegis.update.UpdatePrefs(appContext)
            if (prefs.wifiOnly && isUpdateNetworkMetered(appContext)) {
                app.aether.aegis.update.UpdateState.set(
                    app.aether.aegis.update.UpdateState.Status.Failed(
                        "Wi-Fi only is on — switch to Wi-Fi or turn it off below.",
                    ),
                )
                return
            }
            app.aether.aegis.update.UpdateState.set(
                app.aether.aegis.update.UpdateState.Status.Downloading(status.release),
            )
            app.aether.aegis.AegisApp.appScope.launch(Dispatchers.IO) {
                val client = app.aether.aegis.update.UpdateClient(token = token)
                val apk = java.io.File(appContext.filesDir, "update.apk")
                val outcome = client.downloadApk(
                    release = status.release,
                    dest = apk,
                    isCancelled = { app.aether.aegis.update.UpdateState.cancelRequested.get() },
                    onProgress = { bytes, total ->
                        app.aether.aegis.update.UpdateState.set(
                            app.aether.aegis.update.UpdateState.Status.Downloading(
                                release = status.release,
                                bytesDownloaded = bytes,
                                totalBytes = total,
                            ),
                        )
                    },
                )
                when (outcome) {
                    is app.aether.aegis.update.UpdateClient.DownloadOutcome.Ok -> {
                        // Single-tap Update: the user already
                        // authorised both phases with their first
                        // click, so chain straight into the install
                        // instead of parking at Downloaded.
                        app.aether.aegis.update.UpdateState.set(
                            app.aether.aegis.update.UpdateState.Status.Installing(status.release),
                        )
                        app.aether.aegis.update.UpdateInstaller.beginInstall(
                            appContext, apk,
                            silent = true,
                            foreground = true,
                        )
                    }
                    is app.aether.aegis.update.UpdateClient.DownloadOutcome.Cancelled ->
                        app.aether.aegis.update.UpdateState.set(
                            app.aether.aegis.update.UpdateState.Status.Available(status.release),
                        )
                    is app.aether.aegis.update.UpdateClient.DownloadOutcome.Failed ->
                        app.aether.aegis.update.UpdateState.set(
                            app.aether.aegis.update.UpdateState.Status.Failed("Download failed; try again."),
                        )
                }
            }
        }
        is app.aether.aegis.update.UpdateState.Status.Downloaded -> {
            // Install-only path: the APK was already fetched (state carries
            // its path). If the file vanished out from under us, fall back
            // to Idle rather than crash — a fresh check re-stages it.
            val apk = java.io.File(status.apkPath)
            if (apk.exists()) {
                app.aether.aegis.update.UpdateState.set(
                    app.aether.aegis.update.UpdateState.Status.Installing(status.release),
                )
                app.aether.aegis.update.UpdateInstaller.beginInstall(
                    appContext, apk,
                    silent = true,
                    foreground = true,
                )
            } else {
                app.aether.aegis.update.UpdateState.set(app.aether.aegis.update.UpdateState.Status.Idle)
            }
        }
        else -> {
            // Idle / UpToDate / Failed → kick off a fresh check.
            app.aether.aegis.update.UpdateState.set(app.aether.aegis.update.UpdateState.Status.Checking)
            app.aether.aegis.AegisApp.appScope.launch(Dispatchers.IO) {
                val client = app.aether.aegis.update.UpdateClient(token = token)
                val outcome = client.check(appContext)
                val next: app.aether.aegis.update.UpdateState.Status = when (outcome) {
                    is app.aether.aegis.update.UpdateClient.CheckOutcome.UpToDate ->
                        app.aether.aegis.update.UpdateState.Status.UpToDate
                    is app.aether.aegis.update.UpdateClient.CheckOutcome.NeedsToken ->
                        app.aether.aegis.update.UpdateState.Status.Failed(
                            "GitHub returned 404 — paste a PAT above.",
                        )
                    is app.aether.aegis.update.UpdateClient.CheckOutcome.RateLimited -> {
                        val mins = outcome.resetAt?.let { (it - System.currentTimeMillis()) / 60_000 }
                        app.aether.aegis.update.UpdateState.Status.Failed(
                            "Rate-limited — retry in ${mins ?: "~60"} min.",
                        )
                    }
                    is app.aether.aegis.update.UpdateClient.CheckOutcome.Failed ->
                        app.aether.aegis.update.UpdateState.Status.Failed(outcome.reason)
                    is app.aether.aegis.update.UpdateClient.CheckOutcome.UpdateAvailable -> {
                        // Already-downloaded reconciliation: if update.apk
                        // is on disk AND its SHA-256 matches the release the
                        // server just advertised, jump straight to
                        // Downloaded (single-tap re-install) instead of
                        // re-downloading the same ~45-65 MB. A SHA mismatch
                        // (partial / stale file) falls back to Available.
                        val apk = java.io.File(appContext.filesDir, "update.apk")
                        val onDiskMatches = apk.exists() &&
                            runCatching { app.aether.aegis.update.UpdateClient.sha256Of(apk) }
                                .getOrNull() == outcome.release.sha
                        if (onDiskMatches) {
                            app.aether.aegis.update.UpdateState.Status.Downloaded(
                                outcome.release, apk.absolutePath,
                            )
                        } else {
                            app.aether.aegis.update.UpdateState.Status.Available(outcome.release)
                        }
                    }
                }
                app.aether.aegis.update.UpdateState.set(next)
            }
        }
    }
}

/** Label for the single primary button per update state. Available and
 *  Downloaded both read "Update (sha)" — to the user they're the same
 *  one-tap action; the difference (download-then-install vs install-only)
 *  is handled in [runUpdateAction], not surfaced here. */
private fun actionLabel(status: app.aether.aegis.update.UpdateState.Status): String = when (status) {
    is app.aether.aegis.update.UpdateState.Status.Idle -> "Check for updates"
    is app.aether.aegis.update.UpdateState.Status.Checking -> "Checking…"
    is app.aether.aegis.update.UpdateState.Status.UpToDate -> "Check again"
    is app.aether.aegis.update.UpdateState.Status.Available -> "Update (${status.release.shortSha})"
    is app.aether.aegis.update.UpdateState.Status.Downloading -> "Downloading…"
    is app.aether.aegis.update.UpdateState.Status.Downloaded -> "Update (${status.release.shortSha})"
    is app.aether.aegis.update.UpdateState.Status.Installing -> "Installing…"
    is app.aether.aegis.update.UpdateState.Status.Failed -> "Retry"
}

/** Human-readable status line shown above the button. Failed carries its
 *  own message straight through. */
private fun statusLabel(status: app.aether.aegis.update.UpdateState.Status): String = when (status) {
    is app.aether.aegis.update.UpdateState.Status.Idle -> "Tap below to check for a new build."
    is app.aether.aegis.update.UpdateState.Status.Checking -> "Talking to GitHub…"
    is app.aether.aegis.update.UpdateState.Status.UpToDate -> "You're on the latest build."
    is app.aether.aegis.update.UpdateState.Status.Available -> "New build available."
    is app.aether.aegis.update.UpdateState.Status.Downloading -> "Downloading the new APK…"
    is app.aether.aegis.update.UpdateState.Status.Downloaded -> "Downloaded — tap Update to apply."
    is app.aether.aegis.update.UpdateState.Status.Installing -> "Committing install session…"
    is app.aether.aegis.update.UpdateState.Status.Failed -> status.message
}

/** Colour for the status line: cyan = action waiting (Available /
 *  Downloaded), error red = Failed, dim for the neutral / in-flight
 *  states. */
@Composable
private fun statusColor(status: app.aether.aegis.update.UpdateState.Status): Color = when (status) {
    is app.aether.aegis.update.UpdateState.Status.Available,
    is app.aether.aegis.update.UpdateState.Status.Downloaded -> app.aether.aegis.ui.theme.AegisCyan
    is app.aether.aegis.update.UpdateState.Status.Failed -> MaterialTheme.colorScheme.error
    else -> app.aether.aegis.ui.theme.AegisOnSurfaceDim
}

/** True iff the device's active connection is metered (cellular / paid
 *  hotspot). Used to block the manual Download button when the user
 *  has Wi-Fi-only set — the background worker already does this via
 *  WorkManager's NetworkType.UNMETERED constraint. */
private fun isUpdateNetworkMetered(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return true
    val net = cm.activeNetwork ?: return true
    val caps = cm.getNetworkCapabilities(net) ?: return true
    return !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

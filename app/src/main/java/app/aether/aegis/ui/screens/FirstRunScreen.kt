package app.aether.aegis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.backup.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * First-run gate. Shown as the very first destination on a fresh
 * install — before the user sees the chat list — so a backup
 * restore can land before any meaningful state accumulates.
 *
 * Two paths:
 *
 *   Restore from backup — SAF picker → passphrase → BackupManager
 *       .restore() with the same progress + auto-relaunch dance as
 *       Settings → Backup → Restore. On success the process kills
 *       itself and the alarm relaunches MainActivity. The next
 *       process start sees the marker file written by the restore
 *       and skips this screen.
 *
 *   Start fresh — write the marker file, navigate to the splash.
 *       Identity + DB were already created by AegisApp.onCreate;
 *       nothing else to do.
 *
 * Marker is `filesDir/.aegis_first_run_done`. Survives both the
 * "start fresh" path (we touch it explicitly) and the "restore"
 * path (writeMarker fires before the kill, ensuring the next boot
 * doesn't show this screen again).
 *
 * Detection of "first run" lives at MainActivity's NavHost
 * startDestination decision — when the marker is absent, we send
 * the user here instead of the splash.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstRunScreen(navController: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var restorePassphrase by remember { mutableStateOf("") }
    var restoreBusy by remember { mutableStateOf(false) }
    var restoreResult by remember { mutableStateOf<String?>(null) }
    var restoreProgress by remember { mutableStateOf<BackupManager.Progress?>(null) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                stringResource(R.string.first_run_welcome_to_aegis),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.first_run_restore_from_a_previous),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(40.dp))

            // ---- Restore from backup ----
            Button(
                enabled = !restoreBusy,
                onClick = {
                    val activity = ctx as? app.aether.aegis.MainActivity ?: return@Button
                    activity.openBackupFile { uri ->
                        if (uri != null) pendingRestoreUri = uri
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (restoreBusy) "Restoring…" else "Restore from backup") }

            if (restoreBusy) {
                restoreProgress?.let { p ->
                    Spacer(modifier = Modifier.height(8.dp))
                    val pct = if (p.bytesTotal > 0)
                        (p.bytesProcessed * 100 / p.bytesTotal).coerceIn(0, 100).toInt()
                    else null
                    val stageLabel = when (p.stage) {
                        BackupManager.Stage.DerivingKey -> "Deriving key"
                        BackupManager.Stage.Decrypting -> "Decrypting + unzipping"
                        BackupManager.Stage.Applying -> "Applying"
                        BackupManager.Stage.Done -> stringResource(R.string.tutorial_done)
                    }
                    if (pct != null) {
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "$stageLabel · $pct% · ${formatBytes(p.bytesProcessed)} / ${formatBytes(p.bytesTotal)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "$stageLabel · ${formatBytes(p.bytesProcessed)} read",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } ?: run {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            restoreResult?.let {
                Spacer(modifier = Modifier.height(8.dp))
                // Success vs failure is inferred from the message prefix —
                // must stay in sync with the "Restore complete · restarting…"
                // string set on the Ok branch below.
                val ok = it.startsWith("Restore complete")
                Text(
                    it,
                    fontSize = 13.sp,
                    color = if (ok) app.aether.aegis.ui.theme.AegisOnline
                    else MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "or",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(modifier = Modifier.height(24.dp))

            // ---- Start fresh ----
            OutlinedButton(
                enabled = !restoreBusy,
                onClick = {
                    runCatching {
                        markFirstRunDone(ctx)
                    }
                    navController.navigate("splash") {
                        popUpTo("first_run") { inclusive = true }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.first_run_start_fresh)) }
        }
    }

    // Passphrase dialog
    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = {
                pendingRestoreUri = null
                restorePassphrase = ""
            },
            title = { Text(stringResource(R.string.first_run_enter_backup_passphrase)) },
            text = {
                OutlinedTextField(
                    value = restorePassphrase,
                    onValueChange = { restorePassphrase = it },
                    label = { Text(stringResource(R.string.first_run_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = restorePassphrase.isNotEmpty() && !restoreBusy,
                    onClick = {
                        // Move the passphrase into a char[] and clear the
                        // String-backed field immediately; the array is
                        // zeroed (Arrays.fill) the moment restore returns so
                        // the secret isn't left sitting in Compose state.
                        val pw = restorePassphrase.toCharArray()
                        restorePassphrase = ""
                        val src = uri
                        pendingRestoreUri = null
                        scope.launch {
                            restoreBusy = true
                            restoreResult = null
                            restoreProgress = null
                            // Probe the file size up front so the progress
                            // bar can show a percentage; -1 = unknown (SAF
                            // provider didn't report a size), which makes the
                            // UI fall back to an indeterminate spinner.
                            val totalBytes = runCatching {
                                ctx.contentResolver.openFileDescriptor(src, "r")?.use {
                                    it.statSize
                                }
                            }.getOrNull() ?: -1L
                            val r = withContext(Dispatchers.IO) {
                                val inp = ctx.contentResolver.openInputStream(src)
                                    ?: return@withContext BackupManager.Result.Failed(
                                        "Couldn't open backup file.",
                                    )
                                inp.use {
                                    BackupManager.restore(
                                        ctx, pw, it,
                                        expectedTotalBytes = totalBytes,
                                        // First-run import = DATA only (chats,
                                        // contacts, identity, SimpleX core). The
                                        // backup's lock + profile prefs are NOT
                                        // cloned, so this install stays
                                        // un-onboarded and the tutorial fires for
                                        // permissions / recovery phrase / PIN.
                                        // The imported content is then sealed
                                        // under the phrase the user sets (the
                                        // pending reseal bundle re-fires once
                                        // onboarding enrols it). Without this the
                                        // install inherited the OLD device's
                                        // seal-pub with no usable phrase — "no
                                        // passphrase, can't recover" (user report
                                        // 2026-06-15).
                                        preserveLockPrefs = true,
                                        freshOnboarding = true,
                                        onProgress = { p -> restoreProgress = p },
                                    )
                                }
                            }
                            java.util.Arrays.fill(pw, ' ')
                            when (r) {
                                is BackupManager.Result.Ok -> {
                                    // Write the marker BEFORE the kill so
                                    // the next boot lands directly on the
                                    // restored splash, not back here.
                                    runCatching { markFirstRunDone(ctx) }
                                    restoreResult = "Restore complete · restarting…"
                                    restoreBusy = false
                                    scheduleRelaunchAndDie(ctx)
                                }
                                is BackupManager.Result.Failed -> {
                                    restoreResult = "Restore failed: ${r.reason}"
                                    restoreBusy = false
                                }
                            }
                        }
                    },
                ) { Text(stringResource(R.string.first_run_restore)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingRestoreUri = null
                    restorePassphrase = ""
                }) { Text(stringResource(R.string.secure_notes_cancel)) }
            },
        )
    }
}

/** Marker file used by MainActivity's startDestination logic to
 *  decide whether to show FirstRunScreen vs the regular splash. */
internal const val FIRST_RUN_MARKER = ".aegis_first_run_done"

/** Write the first-run marker (its contents are just a timestamp for
 *  debugging — only existence matters). Must run on BOTH exit paths
 *  ("start fresh" and a successful restore) so the next launch skips
 *  this screen. */
internal fun markFirstRunDone(context: android.content.Context) {
    File(context.filesDir, FIRST_RUN_MARKER).writeText(
        System.currentTimeMillis().toString(),
    )
}

/** True until [markFirstRunDone] has run once. Drives MainActivity's
 *  choice of start destination (FirstRunScreen vs splash). */
internal fun isFirstRun(context: android.content.Context): Boolean =
    !File(context.filesDir, FIRST_RUN_MARKER).exists()

/** Same alarm-then-kill pattern Settings → Backup → Restore uses
 *  on success. Schedules a 3.5 s relaunch of MainActivity via
 *  AlarmManager (setAndAllowWhileIdle so no SCHEDULE_EXACT_ALARM
 *  permission is needed), then suicides. The wait gives Compose
 *  time to paint the "Restore complete · restarting…" line so
 *  the user sees confirmation before the process vanishes. */
private fun scheduleRelaunchAndDie(context: android.content.Context) {
    runCatching {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?: return@runCatching
        launchIntent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
        )
        val pi = android.app.PendingIntent.getActivity(
            context, 0, launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(android.content.Context.ALARM_SERVICE)
            as android.app.AlarmManager
        am.setAndAllowWhileIdle(
            android.app.AlarmManager.RTC,
            System.currentTimeMillis() + 3500L,
            pi,
        )
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 3500L)
    }
}

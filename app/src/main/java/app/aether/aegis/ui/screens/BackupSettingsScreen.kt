package app.aether.aegis.ui.screens

import app.aether.aegis.backup.BackupManager
import app.aether.aegis.backup.BackupPrefs
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manual backup + restore + reminder cadence.
 *
 * Backup: user picks a destination via SAF (CreateDocument), enters
 * a passphrase, BackupManager.backup streams an encrypted ZIP there.
 *
 * Restore: SAF OpenDocument → passphrase → BackupManager.restore
 * unpacks into the live data dir, then we kill the process so the
 * SQLCipher core remounts from the new DB on relaunch.
 *
 * Reminder: dropdown chooses Off / Daily / Weekly / Monthly. A
 * 24-hour periodic worker (BackupReminderWorker) notifies if the
 * configured interval has elapsed since the last successful backup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(navController: NavController) {
    val ctx = LocalContext.current
    // BackupPrefs is the small SharedPreferences-backed store for the
    // reminder cadence + last-backup timestamp. remember keeps one
    // instance across recomposition.
    val prefs = remember { BackupPrefs(ctx) }
    // Currently-selected reminder interval (days). Mutable Compose
    // state so the radio group re-renders on tap; persisted to prefs
    // immediately on change.
    var interval by remember { mutableStateOf(prefs.intervalDays) }
    // Snapshot of the last-successful-backup timestamp read once at
    // entry. This is NOT observed for live updates — it seeds
    // lastShown, which is what the UI actually renders.
    val lastBackupAt by remember(prefs) { mutableStateOf(prefs.lastBackupAt) }
    // The timestamp the "Last backup" line shows. Separate from
    // lastBackupAt so a successful backup in THIS session can update
    // the label without re-reading prefs.
    var lastShown by remember { mutableStateOf(lastBackupAt) }
    val scope = rememberCoroutineScope()

    // Modal state machines for backup + restore. Both surfaces share
    // the same pattern: ask for the passphrase, do the streaming,
    // surface result.
    var backupPassphrase by remember { mutableStateOf("") }
    var backupConfirmPassphrase by remember { mutableStateOf("") }
    // Non-null once the user has chosen a SAF destination; its presence
    // is what shows the backup passphrase dialog. Cleared when the
    // dialog is dismissed or the backup is kicked off.
    var pendingBackupUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // Disables the backup button + confirm while a backup streams.
    var backupBusy by remember { mutableStateOf(false) }
    // Result line under the backup button; the "Backup saved" prefix is
    // load-bearing — it's what colours the text success vs error below.
    var backupResult by remember { mutableStateOf<String?>(null) }
    // PIN gate on export (PIN = act). Shown before the SAF file
    // picker when a PIN is configured.
    var showExportPinPrompt by remember { mutableStateOf(false) }
    var exportPin by remember { mutableStateOf("") }
    var exportPinError by remember { mutableStateOf<String?>(null) }

    /**
     * Launch the SAF "create document" picker; the chosen uri arms the
     * passphrase dialog. Reused by the no-PIN path (button onClick) and
     * the post-PIN path (after the export PIN gate passes). Does nothing
     * if the host context isn't [MainActivity] — the SAF launcher is
     * registered there.
     */
    fun startBackupPicker() {
        val activity = ctx as? app.aether.aegis.MainActivity ?: return
        val name = "aegis-backup-${System.currentTimeMillis()}.aegisbak"
        activity.createBackupFile(name) { uri ->
            if (uri != null) pendingBackupUri = uri
        }
    }

    var restorePassphrase by remember { mutableStateOf("") }
    // Set when the user picks a backup file. Drives a two-stage flow:
    // first the destructive-overwrite confirmation (restoreConfirmation),
    // then — once confirmed — the passphrase dialog keyed off this uri.
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var restoreBusy by remember { mutableStateOf(false) }
    // Result line; "Restore complete" prefix is load-bearing for the
    // success-vs-error colour discriminator below.
    var restoreResult by remember { mutableStateOf<String?>(null) }
    // True while the "this overwrites everything" confirmation is up,
    // BEFORE the passphrase dialog. Gates the passphrase prompt so the
    // user can't skip the warning.
    var restoreConfirmation by remember { mutableStateOf(false) }
    // Live restore progress (stage + bytes) pushed from BackupManager so
    // the progress bar / label update during the long decrypt+unzip.
    var restoreProgress by remember { mutableStateOf<BackupManager.Progress?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string._backup)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Backups are encrypted with a passphrase you choose. They " +
                    "include your messages, contacts, attachments, identity, " +
                    "and settings. Aegis cannot recover the passphrase — " +
                    "store it somewhere safe.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )

            // ---- Manual backup ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Back up now", fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            lastShown == 0L -> "Never backed up."
                            else -> "Last backup: ${formatTs(lastShown)}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        enabled = !backupBusy,
                        onClick = {
                            // Export requires the REAL PIN even when the app is
                            // already unlocked (incl. via biometric) — the
                            // rule "biometric = read, PIN = act". An attacker who
                            // forced a fingerprint must not be able to exfiltrate
                            // a full backup. If no PIN is set there's nothing to
                            // gate on, so go straight to the file picker.
                            val store = app.aether.aegis.AegisApp.instance.lockState.store
                            if (store.hasPin) {
                                exportPin = ""
                                exportPinError = null
                                showExportPinPrompt = true
                            } else {
                                startBackupPicker()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (backupBusy) "Working…" else "Create backup file") }
                    backupResult?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            it,
                            color = if (it.startsWith("Backup saved"))
                                app.aether.aegis.ui.theme.AegisCyan else MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // ---- Restore ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Restore from file", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Overwrites everything currently in this app. The app " +
                            "will restart automatically when done.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        enabled = !restoreBusy,
                        onClick = {
                            val activity = ctx as? app.aether.aegis.MainActivity ?: return@OutlinedButton
                            activity.openBackupFile { uri ->
                                if (uri != null) {
                                    pendingRestoreUri = uri
                                    restoreConfirmation = true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (restoreBusy) "Restoring…" else "Pick a backup file") }
                    if (restoreBusy) {
                        // Live restore progress. Determinate bar only when
                        // we know the total size; otherwise indeterminate
                        // with a running "N read" byte count. The stages
                        // mirror BackupManager's pipeline: derive key →
                        // decrypt+unzip → apply → done.
                        restoreProgress?.let { p ->
                            Spacer(modifier = Modifier.height(6.dp))
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
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                                Text(
                                    "$stageLabel · $pct% · ${formatBytes(p.bytesProcessed)} / ${formatBytes(p.bytesTotal)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                                Text(
                                    "$stageLabel · ${formatBytes(p.bytesProcessed)} read",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } ?: run {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    restoreResult?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        // Green when restore succeeded, red on failure.
                        // Both messages flow through restoreResult so
                        // the colour discriminates without a separate
                        // state field.
                        val ok = it.startsWith("Restore complete")
                        Text(
                            it,
                            color = if (ok) app.aether.aegis.ui.theme.AegisOnline
                            else MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // ---- Reminder cadence ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Backup reminder", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Aegis notifies you when this much time passes since " +
                            "your last successful backup.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Cadence radio group. Each option is a (days, label)
                    // pair from BackupPrefs.intervals (e.g. Off / Daily /
                    // Weekly / Monthly); selecting one writes intervalDays
                    // through immediately so the periodic worker picks it
                    // up on its next 24-hour tick.
                    BackupPrefs.intervals.forEach { (days, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = interval == days,
                                onClick = {
                                    interval = days
                                    prefs.intervalDays = days
                                },
                            )
                            Text(label, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    // ---- Export PIN gate (PIN required to act) ----
    if (showExportPinPrompt) {
        AlertDialog(
            onDismissRequest = { showExportPinPrompt = false; exportPin = "" },
            title = { Text("Enter your PIN") },
            text = {
                Column {
                    Text(
                        "Backing up exports all your data. Confirm your PIN — " +
                            "fingerprint alone isn't enough to export.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exportPin,
                        onValueChange = { exportPin = it; exportPinError = null },
                        label = { Text(stringResource(R.string.tutorial_pin)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = exportPinError != null,
                        supportingText = exportPinError?.let { { Text(it, fontSize = 11.sp) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = exportPin.isNotEmpty(),
                    onClick = {
                        val store = app.aether.aegis.AegisApp.instance.lockState.store
                        // Only the REAL PIN authorises an export. A duress PIN
                        // must NOT silently produce a real backup for a coercer.
                        if (store.verifyPin(exportPin) ==
                            app.aether.aegis.lock.LockStore.PinMatch.REAL) {
                            showExportPinPrompt = false
                            exportPin = ""
                            startBackupPicker()
                        } else {
                            exportPinError = "Wrong PIN"
                            exportPin = ""
                        }
                    },
                ) { Text(stringResource(R.string.tutorial_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportPinPrompt = false; exportPin = "" }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // ---- Backup passphrase dialog ----
    pendingBackupUri?.let { uri ->
        AlertDialog(
            onDismissRequest = {
                pendingBackupUri = null
                backupPassphrase = ""
                backupConfirmPassphrase = ""
            },
            title = { Text("Backup passphrase") },
            text = {
                // Live strength check. The backup now carries the seal
                // master key, so a weak passphrase is a weak lock on
                // everything — PasswordPolicy is the same gate
                // BackupManager enforces server-side, surfaced here so the
                // user sees WHY the button is disabled rather than a dead
                // control.
                val policy = remember(backupPassphrase) {
                    app.aether.aegis.backup.PasswordPolicy.validate(backupPassphrase.toCharArray())
                }
                Column {
                    Text(
                        "Pick a passphrase of at least 12 characters — a short " +
                            "sentence or four random words is perfect, no symbols " +
                            "needed. Length is what makes it uncrackable. Aegis " +
                            "cannot recover it for you.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = backupPassphrase,
                        onValueChange = { backupPassphrase = it },
                        label = { Text(stringResource(R.string.first_run_passphrase)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        // Only flag as an error once the user has actually
                        // typed something — an empty field on open isn't a
                        // "mistake" to shout about.
                        isError = backupPassphrase.isNotEmpty() && !policy.ok,
                        supportingText = if (backupPassphrase.isNotEmpty() && policy.reason != null) {
                            { Text(policy.reason!!, fontSize = 11.sp) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = backupConfirmPassphrase,
                        onValueChange = { backupConfirmPassphrase = it },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = backupConfirmPassphrase.isNotEmpty() &&
                            backupConfirmPassphrase != backupPassphrase,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                // Re-validate here too — `text` lambda's local can't be read
                // from this sibling slot, so recompute against the policy.
                val pwOk = remember(backupPassphrase) {
                    app.aether.aegis.backup.PasswordPolicy.validate(backupPassphrase.toCharArray()).ok
                }
                TextButton(
                    enabled = pwOk &&
                        backupPassphrase == backupConfirmPassphrase && !backupBusy,
                    onClick = {
                        val pw = backupPassphrase.toCharArray()
                        backupPassphrase = ""
                        backupConfirmPassphrase = ""
                        val target = uri
                        pendingBackupUri = null
                        scope.launch {
                            backupBusy = true
                            backupResult = null
                            val r = withContext(Dispatchers.IO) {
                                val out = ctx.contentResolver.openOutputStream(target)
                                    ?: return@withContext BackupManager.Result.Failed(
                                        "Couldn't open destination file.",
                                    )
                                out.use { BackupManager.backup(ctx, pw, it) }
                            }
                            java.util.Arrays.fill(pw, ' ')
                            backupResult = when (r) {
                                is BackupManager.Result.Ok -> {
                                    val now = System.currentTimeMillis()
                                    prefs.lastBackupAt = now
                                    lastShown = now
                                    "Backup saved · ${formatTs(now)}"
                                }
                                is BackupManager.Result.Failed -> "Backup failed: ${r.reason}"
                            }
                            backupBusy = false
                        }
                    },
                ) { Text("Encrypt & save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingBackupUri = null
                    backupPassphrase = ""
                    backupConfirmPassphrase = ""
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // ---- Restore confirmation + passphrase ----
    // Two dialogs sharing pendingRestoreUri: while restoreConfirmation
    // is true the destructive-overwrite warning shows; clearing it
    // (without clearing the uri) falls through to the passphrase dialog
    // in the else branch. Cancelling clears the uri so neither shows.
    if (restoreConfirmation) {
        AlertDialog(
            onDismissRequest = {
                restoreConfirmation = false
                pendingRestoreUri = null
            },
            title = { Text("Restore from backup?") },
            text = {
                Text(
                    "This OVERWRITES your current data — every chat, contact, " +
                        "and setting in this app. The app will restart when " +
                        "the restore finishes. There is no undo.",
                )
            },
            confirmButton = {
                // Continue past the warning: drop the confirmation flag
                // but KEEP pendingRestoreUri so the else-branch passphrase
                // dialog opens. Deliberate — not a missed clear.
                TextButton(onClick = { restoreConfirmation = false }) {
                    Text(stringResource(R.string.tutorial_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    restoreConfirmation = false
                    pendingRestoreUri = null
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    } else pendingRestoreUri?.let { uri ->
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
                        val pw = restorePassphrase.toCharArray()
                        restorePassphrase = ""
                        val src = uri
                        pendingRestoreUri = null
                        scope.launch {
                            restoreBusy = true
                            restoreResult = null
                            restoreProgress = null
                            // Pull total byte count from SAF so the
                            // progress bar has a denominator. Falls
                            // back to -1 = indeterminate (statSize can
                            // be unavailable for some providers).
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
                                // If this profile was created via the
                                // onboarding "import a backup" path, a
                                // pending.import marker sits in its root.
                                // Then we preserve THIS profile's lock prefs
                                // (its new PIN/phrase/seal) and re-seal the
                                // restored content under the new key — so the
                                // imported data is unlocked by the NEW
                                // profile's PIN, not the backup's old one.
                                // Consume the marker either way (one-shot:
                                // a normal in-place restore has no marker and
                                // keeps the backup's own lock prefs).
                                val marker = java.io.File(
                                    app.aether.aegis.AegisApp.instance.profileRoot.root,
                                    "pending.import",
                                )
                                val intoNewProfile = marker.exists()
                                runCatching { marker.delete() }
                                inp.use {
                                    BackupManager.restore(
                                        ctx, pw, it,
                                        expectedTotalBytes = totalBytes,
                                        preserveLockPrefs = intoNewProfile,
                                        onProgress = { p -> restoreProgress = p },
                                    )
                                }
                            }
                            java.util.Arrays.fill(pw, ' ')
                            when (r) {
                                is BackupManager.Result.Ok -> {
                                    // Surface a visible success message
                                    // BEFORE killing the process, AND
                                    // schedule the relaunch through
                                    // AlarmManager so the user lands
                                    // back in MainActivity automatically
                                    // instead of staring at a launcher
                                    // wondering whether anything happened.
                                    // Mirrors the alarm-then-kill pattern
                                    // Diagnostics → Restart Aegis uses.
                                    restoreResult = "Restore complete · restarting…"
                                    restoreBusy = false
                                    scheduleRelaunchAndDie(ctx)
                                    return@launch
                                }
                                is BackupManager.Result.Failed -> {
                                    restoreResult = "Restore failed: ${r.reason}"
                                }
                            }
                            restoreBusy = false
                        }
                    },
                ) { Text(stringResource(R.string.first_run_restore)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingRestoreUri = null
                    restorePassphrase = ""
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Restart Aegis cleanly after a successful restore — same
 *  alarm-then-kill pattern Diagnostics → Restart Aegis uses.
 *  Schedules a relaunch of MainActivity via AlarmManager
 *  (setAndAllowWhileIdle so no SCHEDULE_EXACT_ALARM permission is
 *  needed and Doze can't eat it), then suicides. The alarm fire and
 *  the kill are scheduled at the SAME delay (3500 ms) so the new
 *  process is already queued to launch the instant this one dies; the
 *  gap also gives Compose time to paint the "Restore complete ·
 *  restarting…" message so the user sees confirmation before the
 *  process disappears. Whole body is best-effort (runCatching) — a
 *  failed relaunch schedule must not leave the app un-killable.
 *
 *  NOTE: the relaunch intent CLEAR_TASK/CLEAR_TOP-resets the back stack
 *  so the user lands fresh in MainActivity rather than mid-restore. */
private fun scheduleRelaunchAndDie(context: Context) {
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
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
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

/** Render a backup timestamp as `yyyy-MM-dd HH:mm` in the device's
 *  locale/zone. 0 (never-backed-up sentinel) renders as an em dash. */
private fun formatTs(ms: Long): String {
    if (ms == 0L) return "—"
    return java.text.SimpleDateFormat(
        "yyyy-MM-dd HH:mm", java.util.Locale.getDefault(),
    ).format(java.util.Date(ms))
}

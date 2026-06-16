package app.aether.aegis.ui.screens

import app.aether.aegis.profile.ProfileRegistry
import app.aether.aegis.profile.ProfileRoot
import app.aether.aegis.lock.LockStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.GlassPanel
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Phase 2 multi-profile UI — list every profile on disk, create a
 * new one, switch to one. The data layer (ProfileRegistry +
 * ProfileRoot per-id) landed in Phase 2 foundation; this is the
 * visible surface on top.
 *
 * Threat-model UX:
 *  - Profile ids are short opaque slugs (letters / digits / - _),
 *    never the user's chosen display name. Display names live
 *    INSIDE each profile (ProfileStore.displayName) and aren't
 *    readable until that profile is unlocked. The Settings list
 *    here shows ids only when locked, the active profile's
 *    display name + id when unlocked.
 *  - Switching = kill the process. Live re-bind of AegisApp's
 *    repository / identity / transports would be fragile mid-
 *    flight; a kill + reopen is half a second and rock-solid.
 *  - The create flow includes the sos-fires-for-active warning:
 *    each profile can only
 *    SOS when IT is the unlocked one, because the locked
 *    profile's identity is sealed by its own PIN.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    // Process-wide registry of profiles on disk + which one is active.
    val registry = remember { ProfileRegistry.get(context) }
    // The list of profile ids, held in Compose state so create/delete
    // re-render the list. Refreshed by re-calling listProfiles() after
    // each mutation (there's no live observer on the registry here).
    var profiles by remember { mutableStateOf(registry.listProfiles()) }
    // The currently-unlocked profile. Read once per composition; it only
    // changes via a process kill + relaunch, so it can't go stale mid-screen.
    val activeId = registry.activeProfileId
    var createOpen by remember { mutableStateOf(false) }
    // True when the open create-dialog is for an EPHEMERAL profile
    // — wiped entirely on lock.
    var createEphemeral by remember { mutableStateOf(false) }
    // Onboarding chooser: one "New profile" button opens this;
    // it picks permanent/ephemeral and create/import.
    var onboardingOpen by remember { mutableStateOf(false) }
    // When the create-dialog is collecting id+PIN for an IMPORT (vs a
    // fresh create), this is true — onCreate then arms the import.
    var createForImport by remember { mutableStateOf(false) }
    // Permanent profiles are phrase-rooted: this holds (id, pin, words)
    // while the just-generated 24-word phrase is shown once before the
    // profile is created. Non-null = the show-once reveal dialog is up;
    // acknowledging it is what actually creates the profile.
    var phraseReveal by remember { mutableStateOf<Triple<String, String, List<String>>?>(null) }
    // True while the ephemeral→permanent confirmation is up.
    var showMakePermanent by remember { mutableStateOf(false) }
    // Holds the target id while its "switch to X?" confirmation is up;
    // confirming sets the active profile and kills the process.
    var pendingSwitch by remember { mutableStateOf<String?>(null) }
    // Holds the target id while its delete confirmation is up.
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    /**
     * Create a profile rooted in [words] (phrase-rooted seal, TEE-wrapped)
     * with [pin] as a pure GATE — never a PIN-derived seal, so the PIN can
     * be changed without rekeying and a duress PIN can stay
     * indistinguishable. Permanent profiles pass the words the user just
     * wrote down; ephemeral profiles pass an auto-generated phrase that is
     * never shown (they're wiped on lock, so there's nothing to recover).
     *
     * Side effects depend on the flags:
     *  - ephemeral → enter the new profile and kill the process at once.
     *  - createForImport → drop a `pending.import` marker so the next
     *    restore re-seals under THIS profile's key, then queue a switch.
     *  - plain permanent create → just refresh the list; the new profile
     *    stays inactive (the current one stays unlocked).
     */
    fun enrollAndCreate(newId: String, pin: String, ephemeral: Boolean, words: List<String>) {
        if (ephemeral) registry.createEphemeralProfile(newId) else registry.createProfile(newId)
        val ls = LockStore.forProfile(context, newId)
        // enrollRecoveryPhrase derives the seal keypair from the phrase and
        // wraps the priv in the TEE; wipe the returned keypair since this
        // profile isn't the active session (we're enrolling it from OUTSIDE,
        // so the in-memory key material must not linger).
        ls.enrollRecoveryPhrase(words).wipe()
        ls.setPinGateOnly(pin)
        if (createForImport) {
            // Marker consumed by BackupSettingsScreen's restore path: its
            // presence flips preserveLockPrefs so the imported data is
            // re-sealed under this new profile's key/PIN rather than the
            // backup's original lock prefs.
            runCatching {
                java.io.File(
                    app.aether.aegis.profile.ProfileRoot.forId(context, newId).root,
                    "pending.import",
                ).writeText("1")
            }
        }
        if (ephemeral) {
            // Ephemeral profiles enter immediately; the kill makes the new
            // process come up already inside the ephemeral profile.
            app.aether.aegis.profile.EphemeralProfile.enterEphemeral(context, newId)
            android.os.Process.killProcess(android.os.Process.myPid())
        } else {
            profiles = registry.listProfiles()
            // Import needs the new profile ACTIVE to re-seal the restored
            // data under its key, so switch into it; a plain "start fresh"
            // permanent profile stays inactive (current profile stays home).
            if (createForImport) pendingSwitch = newId
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string._profiles)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        AegisIcon(AegisIcons.Back, "back")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.profiles_about_profiles), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.profiles_each_profile_is_a) +
                            "same phone — its own contacts, messages, identity, " +
                            "PIN, and vault. Useful for keeping two families " +
                            "(or a family + a work crew) cleanly separated. " +
                            "Switching closes the current profile and re-opens " +
                            "the other from cold; data is encrypted at rest " +
                            "with that profile's identity key.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.profiles_sos_alerts_only_fire) +
                            "currently-unlocked profile. The other profile's " +
                            "identity is sealed by its own PIN and can't be " +
                            "signed for until you switch to it.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
            }

            // Protected Mode: switching / deleting profiles can be locked
            // so a child can't strand themselves in (or wipe) a profile.
            val profilesGated = isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.PROFILES)
            profiles.forEach { id ->
                ProfileRow(
                    id = id,
                    isActive = id == activeId,
                    canDelete = id != activeId && profiles.size > 1,
                    gated = profilesGated,
                    onTap = {
                        if (id != activeId) pendingSwitch = id
                    },
                    onDelete = { pendingDelete = id },
                )
            }

            // ONE "New profile" button (onboarding decisions):
            // tapping it opens the onboarding chooser — permanent vs
            // ephemeral, create-new vs import — instead of separate
            // buttons you might tap by mistake. The current profile stays
            // ALIVE in the background (its safety services keep running);
            // we do NOT kill the process to start onboarding, only when
            // you actually open a new permanent profile or enter an
            // ephemeral one.
            OutlinedButton(
                onClick = { onboardingOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.profiles_new_profile)) }

            // Ephemeral → permanent. Only when the
            // ACTIVE profile is ephemeral. The DANGEROUS direction: it
            // makes throwaway data forensically permanent — hence the red
            // warning. (Permanent → ephemeral isn't offered; see makePermanent.)
            if (registry.isEphemeral(activeId)) {
                OutlinedButton(
                    onClick = { showMakePermanent = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.profiles_make_this_profile_permanent),
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    // ---- Onboarding chooser (one button -> these choices) ----
    if (onboardingOpen) {
        AlertDialog(
            onDismissRequest = { onboardingOpen = false },
            title = { Text(stringResource(R.string.profiles_new_profile)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.profiles_your_current_profile_stays) +
                            "— SOS and alerts keep running — while you set up a " +
                            "new one. Pick how it should work:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Permanent · create
                    OutlinedButton(
                        onClick = {
                            onboardingOpen = false
                            createEphemeral = false; createForImport = false; createOpen = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.profiles_permanent_start_fresh)) }
                    // Permanent · import
                    OutlinedButton(
                        onClick = {
                            onboardingOpen = false
                            createEphemeral = false; createForImport = true; createOpen = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.profiles_permanent_import_a_backup)) }
                    // Ephemeral · create — wiped on lock.
                    OutlinedButton(
                        onClick = {
                            onboardingOpen = false
                            createEphemeral = true; createForImport = false; createOpen = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.profiles_ephemeral_start_fresh)) }
                    // Ephemeral · import — inspect a backup without committing
                    // it to disk; gone on lock.
                    OutlinedButton(
                        onClick = {
                            onboardingOpen = false
                            createEphemeral = true; createForImport = true; createOpen = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.profiles_ephemeral_import_a_backup)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { onboardingOpen = false }) { Text(stringResource(R.string.secure_notes_cancel)) }
            },
        )
    }

    if (showMakePermanent) {
        AlertDialog(
            onDismissRequest = { showMakePermanent = false },
            title = { Text(stringResource(R.string.profiles_make_permanent), color = MaterialTheme.colorScheme.error) },
            text = {
                Text(
                    stringResource(R.string.profiles_this_conversation_will_be) +
                        "being wiped on lock and becomes forensically recoverable. " +
                        "In a security app, creating permanent evidence is the " +
                        "dangerous action. There is no going back to ephemeral.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    registry.makePermanent(activeId)
                    profiles = registry.listProfiles()
                    showMakePermanent = false
                }) {
                    Text("Make permanent", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMakePermanent = false }) { Text(stringResource(R.string.profiles_keep_ephemeral)) }
            },
        )
    }

    if (createOpen) {
        CreateProfileDialog(
            existingIds = profiles.toSet(),
            onDismiss = { createOpen = false },
            onCreate = { newId, pin ->
                createOpen = false
                // Generate the recovery phrase up front for BOTH paths.
                // Ephemeral discards it silently; permanent shows it once.
                val words = app.aether.aegis.lock.RecoveryPhrase.generate()
                if (createEphemeral) {
                    // Ephemeral: phrase is auto-generated and NEVER shown —
                    // the profile is wiped on lock, so there's nothing to
                    // recover. Enroll + enter immediately.
                    enrollAndCreate(newId, pin, ephemeral = true, words = words)
                } else {
                    // Permanent: show the 24-word phrase once (the only
                    // recovery) before the profile is created.
                    phraseReveal = Triple(newId, pin, words)
                }
            },
        )
    }

    // ---- Permanent-profile phrase reveal (show-once) ----
    phraseReveal?.let { (newId, pin, words) ->
        AlertDialog(
            onDismissRequest = { /* must acknowledge — no dismiss */ },
            title = { Text(stringResource(R.string.profiles_write_down_your_recovery)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.profiles_these_24_words_are) +
                            "Aegis never stores them and will never show them again. " +
                            "Write them down and keep them safe — without them, a " +
                            "lost PIN means lost data.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        words.mapIndexed { i, w -> "${i + 1}. $w" }.joinToString("   "),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    phraseReveal = null
                    enrollAndCreate(newId, pin, ephemeral = false, words = words)
                }) { Text(stringResource(R.string.profiles_ive_written_it_down)) }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete $target?") },
            text = {
                Text(
                    "Permanently removes the $target profile from this device: " +
                        "its identity key, contacts, messages, attachments, PIN, " +
                        "and vault. Cannot be undone. The remaining profile(s) " +
                        "are untouched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = registry.deleteProfile(target)
                    if (ok) profiles = registry.listProfiles()
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.secure_notes_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.secure_notes_cancel)) }
            },
        )
    }

    pendingSwitch?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("Switch to $target?") },
            text = {
                Text(
                    "Aegis will close and re-open as the $target profile. " +
                        "Any active SOS, call, or upload on the current " +
                        "profile is cancelled. You'll be prompted for " +
                        "$target's PIN on the next launch.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingSwitch = null
                    registry.setActiveProfile(target)
                    // Hard kill — the new process loads with the new
                    // active id and rebinds AegisApp pointers cleanly
                    // on AegisApp.onCreate. Live-rebind is too
                    // fragile to attempt mid-flight.
                    android.os.Process.killProcess(android.os.Process.myPid())
                }) {
                    Text(stringResource(R.string.profiles_switch), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitch = null }) { Text(stringResource(R.string.secure_notes_cancel)) }
            },
        )
    }
}

/**
 * One row in the profile list.
 *
 * @param id the opaque profile slug (never a display name — see the
 *   screen KDoc's threat model).
 * @param isActive this is the currently-unlocked profile; renders the
 *   ACTIVE badge and is non-tappable (you can't switch to yourself).
 * @param canDelete show the delete affordance — caller passes
 *   `id != active && profiles.size > 1`; the registry guards the same
 *   invariant again on delete (can't delete active / last-surviving).
 * @param gated Protected Mode lock on the profiles surface; while true,
 *   both switch (tap) and delete are disabled so a child can't strand
 *   themselves in or wipe a profile.
 */
@Composable
private fun ProfileRow(
    id: String,
    isActive: Boolean,
    canDelete: Boolean,
    gated: Boolean,
    onTap: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            // Protected Mode locks switching: an inactive row stops being
            // tappable while gated.
            .clickable(enabled = !isActive && !gated, onClick = onTap),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    id,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (isActive) "Active — unlocked now"
                    else "Locked — tap to switch",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isActive) {
                Text(
                    stringResource(R.string.profiles_active),
                    color = app.aether.aegis.ui.theme.AegisCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            } else if (canDelete) {
                // Delete affordance — only rendered for inactive
                // profiles. Active + last-surviving are guarded both
                // here and inside ProfileRegistry.deleteProfile.
                // Protected Mode disables it while the profiles gate is on.
                TextButton(onClick = onDelete, enabled = !gated) {
                    Text(
                        stringResource(R.string.secure_notes_delete),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/**
 * Collects the id + PIN for a new profile (create OR import — the caller
 * already decided which via createForImport). Validates on confirm and
 * only fires [onCreate] with the SANITISED id once every check passes;
 * the actual create/enroll happens in the caller's enrollAndCreate.
 *
 * @param existingIds ids already on disk — rejected as duplicates.
 * @param onCreate invoked with `(idClean, pin)`; never the raw id.
 */
@Composable
private fun CreateProfileDialog(
    existingIds: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (id: String, pin: String) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // Profile ids are restricted to a safe slug charset (lowercased,
    // letters/digits/-/_) — they become on-disk directory names, so no
    // path separators or surprises. Shown back to the user as "Sanitised
    // to: …" when it differs from what they typed.
    val idClean = id.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_new_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.profiles_pick_a_short_id) +
                        "only on this Settings screen, not at the lock " +
                        "screen. The PIN below unlocks the new profile.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it; error = null },
                    label = { Text(stringResource(R.string.profiles_profile_id)) },
                    singleLine = true,
                )
                if (idClean != id && id.isNotBlank()) {
                    Text(
                        "Sanitised to: $idClean",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(12); error = null },
                    label = { Text(stringResource(R.string.profiles_new_pin)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { v -> confirm = v.filter { it.isDigit() }.take(12); error = null },
                    label = { Text(stringResource(R.string.tutorial_confirm_pin)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Validation order matters: empty < duplicate < reserved
                // (DEFAULT_PROFILE_ID is the implicit first profile, can't
                // be re-created) < PIN length < PIN match. First failing
                // check wins and surfaces its message; only an all-pass
                // falls through to onCreate.
                when {
                    idClean.isBlank() -> error = "Pick an id"
                    idClean in existingIds -> error = "Id already exists"
                    idClean == ProfileRoot.DEFAULT_PROFILE_ID ->
                        error = "Reserved id"
                    pin.length < 4 -> error = "PIN must be 4+ digits"
                    pin != confirm -> error = "PINs don't match"
                    else -> onCreate(idClean, pin)
                }
            }) { Text(stringResource(R.string.profiles_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.secure_notes_cancel)) }
        },
    )
}

package app.aether.aegis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.AegisApp
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.components.LogPeriodSlider
import kotlinx.coroutines.launch

/**
 * App-lock configuration: the app PIN, its convenience gates (scramble pad,
 * PIN-on-launch, pattern, biometric), auto-lock timeout, the three-layer
 * duress PIN, wipe-after-N-failures, and PIN removal.
 *
 * SECURITY MODEL — almost every control on this screen behaves differently
 * depending on which "layer" the session is in (see [layer] below). The
 * single most important invariant: in ANY duress layer (layer > 0) the
 * REAL profile's settings must never be mutated and must never be revealed.
 * Concretely that means duress sessions either (a) write to the DURESS_2
 * decoy slot, (b) silently no-op while faking the success UI, or (c) hide
 * the control entirely — never write through to the real store. Each control
 * below gates on `inDuress` / `inLayer1` / `inLayer2` accordingly.
 *
 * NOTE — the app-lock PIN here is a separate gate from the *vault* PIN
 * (VaultLockStore, see SecureNotesScreen). This screen does not touch the
 * vault. The app PIN is purely an auth gate over the phrase-rooted seal: it
 * gates entry, NOT the encryption key, so changing the PIN never re-encrypts
 * anything (see the Save-PIN handler).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockSettingsScreen(navController: NavController) {
    val lockState = AegisApp.instance.lockState
    val store = lockState.store
    // Three-layer duress:
    //
    //   Layer 0 (real)   — all real, edit anything.
    //   Layer 1 (Fake #1)— duress PIN panel writes are REAL (persist
    //                      to DURESS_2 slot). Attacker creates Fake #2
    //                      themselves. Real PIN-change is a silent
    //                      no-op (mustn't let the attacker lock owner out).
    //   Layer 2 (Fake #2)— duress panel shows "Duress PIN configured"
    //                      with no edit option. Looks like a finished
    //                      profile. Attacker concludes Fake #1 is real.
    val layer = lockState.duressLayer
    val inLayer1 = layer == 1
    val inLayer2 = layer == 2
    val inDuress = layer > 0
    // In any duress layer we always claim a PIN is set — a decoy profile
    // with "No PIN set." would tip the attacker off. In the real profile
    // we read the actual store. All the UI state below is a LOCAL MIRROR
    // seeded once from the store; in duress the mirrors update but the
    // writes behind them are suppressed (see each handler).
    var hasPin by remember { mutableStateOf(if (inDuress) true else store.hasPin) }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var timeoutMs by remember { mutableStateOf(store.timeoutMs) }
    var scramble by remember { mutableStateOf(store.scramblePinPad) }
    var requireAppLock by remember { mutableStateOf(store.requireAppLock) }
    var requirePhraseOnBoot by remember { mutableStateOf(store.requirePhraseOnBoot) }
    val settingsContext = androidx.compose.ui.platform.LocalContext.current
    // Wipe-after-N (opt-in). Backing store + mirrored UI state. This store
    // is separate from the LockStore; it persists the self-destruct policy.
    val wipeStore = remember { app.aether.aegis.lock.WipeOnFailureStore(settingsContext) }
    var wipeEnabled by remember { mutableStateOf(wipeStore.enabled) }
    var wipeThreshold by remember { mutableStateOf(wipeStore.threshold) }
    var wipePhone by remember { mutableStateOf(wipeStore.wipePhone) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    // Device Owner gates the "wipe whole phone" option: a factory-reset call
    // is a silent no-op without Device Owner, so we only offer it when armed.
    // Read once — provisioning state doesn't change within a settings visit.
    val deviceOwner = remember {
        app.aether.aegis.admin.DeviceOwnerStatus.isActive(settingsContext)
    }
    // Pattern unlock enrolment state. patternStage drives a small 2-step
    // draw→confirm wizard inside the panel.
    var patternEnabled by remember { mutableStateOf(store.hasPattern) }
    var patternStage by remember { mutableStateOf(0) } // 0 idle, 1 draw, 2 confirm
    var firstPattern by remember { mutableStateOf<List<Int>?>(null) }
    var patternErr by remember { mutableStateOf<String?>(null) }
    // Strong-biometric capability is fixed for the session; read once.
    val biometricCapable = remember {
        app.aether.aegis.ui.components.deviceHasStrongBiometric(settingsContext)
    }
    var biometricOn by remember { mutableStateOf(store.biometricEnabled) }
    var showBioWarn by remember { mutableStateOf(false) }
    var bioError by remember { mutableStateOf<String?>(null) }
    // The label reads the same in all three layers — "Duress PIN set"
    // when configured, "Duress PIN (optional)" when not. The
    // progression mimics what setting up a duress looks like in a
    // real profile:
    //
    //   Layer 0 (real)   — reflects the actual DURESS_1 slot.
    //   Layer 1 (Fake #1)— reflects the DURESS_2 slot. False initially.
    //                       Becomes true the moment the attacker hits
    //                       Save here. That same write creates Fake #2.
    //   Layer 2 (Fake #2)— always true (attacker had to set DURESS_2
    //                       to get here; that's the same slot we read).
    //                       Update / Remove buttons silently no-op so
    //                       the attacker can't accidentally lock
    //                       themselves out of Fake #2.
    var hasDuress by remember {
        mutableStateOf(
            when {
                inLayer2 -> true
                inLayer1 -> store.hasDuress2Pin
                else     -> store.hasDuressPin
            }
        )
    }
    // Entry fields for the duress-PIN panel. Which slot a Save here targets
    // (DURESS_1 vs the DURESS_2 decoy) depends on the layer — see the Save
    // handler far below.
    var duressPin by remember { mutableStateOf("") }
    var confirmDuressPin by remember { mutableStateOf("") }
    var duressError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_app_lock)) },
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
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        if (hasPin) "PIN set." else "No PIN set.",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.lock_aegis_requires_unlock_on) +
                            "PIN only — biometrics deliberately not supported on the " +
                            "app lock (they can't carry the real / duress distinction; " +
                            "one finger can't refuse to be coerced).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        if (hasPin) stringResource(R.string.duress_change_pin) else "Set PIN",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text(stringResource(R.string.tutorial_new_pin_48_digits)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text(stringResource(R.string.duress_confirm_pin)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Enabled only when 4..8 digits AND the two fields match.
                    // The digit/length filtering happens in onValueChange.
                    Button(
                        enabled = newPin.length in 4..8 && newPin == confirmPin,
                        onClick = {
                            // Duress: pretend to save, write nothing.
                            // The owner is protected; the attacker
                            // sees the standard success state. Returning here
                            // is what guarantees no real-PIN mutation in a
                            // coerced session.
                            if (inDuress) {
                                hasPin = true
                                newPin = ""
                                confirmPin = ""
                                error = null
                                return@Button
                            }
                            // The seal is phrase-rooted, so the PIN is purely
                            // a gate: setting OR changing it never rotates the
                            // seal and never re-encrypts data. setPinGateOnly
                            // stores ONLY the auth hash — no key derivation
                            // off the PIN, so there is no re-encryption step
                            // to order or to fail half-way.
                            val saveResult = runCatching { store.setPinGateOnly(newPin) }
                            if (saveResult.isFailure) {
                                error = "Couldn't save PIN: " +
                                    (saveResult.exceptionOrNull()?.message
                                        ?: saveResult.exceptionOrNull()?.toString()
                                        ?: "unknown error")
                                return@Button
                            }
                            // Read-back verification: confirm the hash actually
                            // persisted before we tell the user it's set — a
                            // silent write failure would leave them lockable
                            // out with no PIN they think they set.
                            if (!store.hasPin) {
                                error = "PIN write didn't persist — " +
                                    "check Diagnostics and report this."
                                return@Button
                            }
                            hasPin = true
                            newPin = ""
                            confirmPin = ""
                            error = null
                        },
                    ) { Text(if (hasPin) "Update PIN" else stringResource(R.string.tutorial_save_pin)) }
                }
            }

            if (hasPin) {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.lock_scramble_pin_pad), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.lock_shuffles_digit_positions_on) +
                                    "surfing. Trade-off: muscle memory no longer helps.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        app.aether.aegis.ui.components.HexSwitch(
                            checked = scramble,
                            onCheckedChange = {
                                // Mirror always flips so the toggle feels
                                // live; the real persisted setting only
                                // changes outside a duress session.
                                scramble = it
                                if (!inDuress) store.scramblePinPad = it
                            },
                        )
                    }
                }

                // PIN-on-launch toggle. Owner can disable the lock
                // screen on resume without removing the PIN — remote
                // AUTH still validates against it. Useful when phone
                // theft / coercion isn't the threat model but a remote
                // wipe-on-loss is.
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.lock_require_pin_to_open), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.lock_when_off_aegis_opens) +
                                    "for remote-access AUTH (LOCATE / SIREN / WIPE) and remains " +
                                    "the way to enter the duress profile.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        app.aether.aegis.ui.components.HexSwitch(
                            checked = requireAppLock,
                            onCheckedChange = {
                                // Same duress-mirror pattern as scramble.
                                requireAppLock = it
                                if (!inDuress) store.requireAppLock = it
                            },
                        )
                    }
                }

                // Model A toggle — "Require recovery phrase on every
                // reboot" (seal-priv survival). Only
                // meaningful for phrase-rooted profiles, and hidden in a
                // duress session (the decoy must not expose the real
                // profile's protection model). ON drops the TEE-wrapped
                // priv so a seized phone holds NO at-rest copy of the seal
                // key — maximum security, at the cost of re-typing the 24
                // words after each reboot. OFF re-wraps from the live
                // session priv to restore zero-friction daily unlock.
                if (store.hasRecoveryPhrase && !inDuress) {
                    GlassPanel(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.lock_require_recovery_phrase_on),
                                    fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.lock_maximum_security_your_encryption) +
                                        "the phone, so a seized device holds nothing to unwrap. " +
                                        "You'll re-enter your 24 words once after each restart.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                            app.aether.aegis.ui.components.HexSwitch(
                                checked = requirePhraseOnBoot,
                                onCheckedChange = {
                                    // Persist the flag first, then bring the
                                    // at-rest key material into line with it.
                                    // This whole panel is `!inDuress`, so the
                                    // write is unconditionally real here.
                                    requirePhraseOnBoot = it
                                    store.requirePhraseOnBoot = it
                                    if (it) {
                                        // Model A: forget the stored copy, so
                                        // a seized phone holds NO at-rest
                                        // wrapped seal priv. The next boot
                                        // must re-derive it from the 24 words.
                                        store.dropWrappedSealPriv()
                                    } else {
                                        // Model B: re-wrap from the live priv
                                        // (settings are only reachable unlocked,
                                        // so PinSession.priv() is the real,
                                        // already-decrypted key). If somehow
                                        // null we leave nothing stored rather
                                        // than wrapping garbage — boot then
                                        // falls back to phrase entry.
                                        app.aether.aegis.lock.PinSession.priv()?.let { priv ->
                                            store.wrapAndStoreSealPriv(priv)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                // Pattern unlock. A convenience
                // gate over the phrase-rooted Model-B unlock — only offered
                // for phrase-rooted profiles (the seal priv comes from the
                // TEE, not the pattern) and hidden in duress. The PIN stays
                // as fallback + the home of duress.
                if (store.hasRecoveryPhrase && !inDuress) {
                    GlassPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.lock_pattern_unlock), fontWeight = FontWeight.SemiBold)
                                    Text(
                                        stringResource(R.string.lock_draw_a_pattern_to) +
                                            "Your PIN still works and stays the way to enter a " +
                                            "duress profile.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                    )
                                }
                                app.aether.aegis.ui.components.HexSwitch(
                                    checked = patternEnabled,
                                    onCheckedChange = { want ->
                                        if (want) {
                                            // Enabling only OPENS the wizard;
                                            // patternEnabled flips true once
                                            // the confirm draw succeeds, not
                                            // on toggle.
                                            patternStage = 1
                                            firstPattern = null
                                            patternErr = null
                                        } else {
                                            patternEnabled = false
                                            patternStage = 0
                                            store.clearPattern()
                                        }
                                    },
                                )
                            }
                            if (patternStage == 1 || patternStage == 2) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    if (patternStage == 1) "Draw your pattern"
                                    else "Draw it again to confirm",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                app.aether.aegis.ui.components.PatternLock(
                                    modifier = Modifier.fillMaxWidth(),
                                    onPattern = { seq ->
                                        // Two-pass enrolment: stage 1 captures,
                                        // stage 2 must match before we persist.
                                        when {
                                            // Reject too-short patterns first,
                                            // in either stage (minPatternLength
                                            // is the store's policy floor).
                                            seq.size < store.minPatternLength ->
                                                patternErr = "Use at least ${store.minPatternLength} dots."
                                            patternStage == 1 -> {
                                                firstPattern = seq
                                                patternStage = 2
                                                patternErr = null
                                            }
                                            else -> {
                                                // Stage 2: persist only on an
                                                // exact re-draw; any mismatch
                                                // restarts at stage 1 so we
                                                // never store an unconfirmed
                                                // pattern. (This panel is
                                                // !inDuress, so the write is
                                                // real.)
                                                if (seq == firstPattern) {
                                                    store.setPattern(seq)
                                                    patternEnabled = true
                                                    patternStage = 0
                                                    patternErr = null
                                                } else {
                                                    patternErr = "Didn't match — start again."
                                                    patternStage = 1
                                                    firstPattern = null
                                                }
                                            }
                                        }
                                    },
                                )
                                patternErr?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Wipe-after-N-failures.
                // Opt-in, off by default, hidden in duress (the decoy must
                // not reveal — or let an attacker arm — a self-destruct).
                // Enabling requires confirming the dialog; the threshold
                // slider and wipe-level only show once armed.
                if (store.hasPin && !inDuress) {
                    GlassPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.lock_wipe_after_failed_attempts),
                                        fontWeight = FontWeight.SemiBold)
                                    Text(
                                        stringResource(R.string.lock_after_too_many_wrong) +
                                            "For the highest-threat users. Irreversible.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                    )
                                }
                                app.aether.aegis.ui.components.HexSwitch(
                                    checked = wipeEnabled,
                                    onCheckedChange = { want ->
                                        if (want) {
                                            // Don't arm until the dialog is
                                            // confirmed — flipping the switch
                                            // alone must NOT enable a
                                            // self-destruct. The mirror stays
                                            // off until the dialog's confirm.
                                            showWipeConfirm = true
                                        } else {
                                            wipeEnabled = false
                                            wipeStore.enabled = false
                                        }
                                    },
                                )
                            }
                            if (wipeEnabled) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    "Wipe after $wipeThreshold wrong PINs",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                // Persist only on release (onValueChangeFinished),
                                // not on every drag tick — avoids hammering the
                                // store while sliding.
                                Slider(
                                    value = wipeThreshold.toFloat(),
                                    onValueChange = { wipeThreshold = it.toInt() },
                                    onValueChangeFinished = { wipeStore.threshold = wipeThreshold },
                                    valueRange = app.aether.aegis.lock.WipeOnFailureStore.MIN_THRESHOLD
                                        .toFloat()..app.aether.aegis.lock.WipeOnFailureStore.MAX_THRESHOLD.toFloat(),
                                    // 5..50 inclusive → 45 intervals, 44 in-between steps.
                                    steps = (app.aether.aegis.lock.WipeOnFailureStore.MAX_THRESHOLD -
                                        app.aether.aegis.lock.WipeOnFailureStore.MIN_THRESHOLD) - 1,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                // Wipe level. "Wipe phone" only when Aegis is
                                // Device Owner — otherwise the factory-reset
                                // call is a no-op, so we don't offer it.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            if (wipePhone) "Wipe entire phone" else "Wipe Aegis data only",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            if (wipePhone)
                                                "Factory-resets the whole device."
                                            else
                                                "Erases Aegis (messages, contacts, keys). Phone untouched.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    if (deviceOwner) {
                                        app.aether.aegis.ui.components.HexSwitch(
                                            checked = wipePhone,
                                            onCheckedChange = {
                                                wipePhone = it
                                                wipeStore.wipePhone = it
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (showWipeConfirm) {
                        val levelText = if (wipePhone) "this phone" else "Aegis data"
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showWipeConfirm = false },
                            title = { Text(stringResource(R.string.lock_arm_selfdestruct)) },
                            text = {
                                Text(
                                    "After $wipeThreshold failed PINs, $levelText will be " +
                                        "permanently erased. This cannot be undone.",
                                )
                            },
                            confirmButton = {
                                // Confirm is the ONLY place the self-destruct
                                // is actually armed — both the mirror and the
                                // store flip here, and the threshold is
                                // re-persisted in case it was dragged before
                                // confirming.
                                TextButton(onClick = {
                                    wipeEnabled = true
                                    wipeStore.enabled = true
                                    wipeStore.threshold = wipeThreshold
                                    showWipeConfirm = false
                                }) {
                                    Text(stringResource(R.string.lock_arm), color = app.aether.aegis.ui.theme.AegisSOS)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showWipeConfirm = false }) { Text(stringResource(R.string.secure_notes_cancel)) }
                            },
                        )
                    }
                }

                // Biometric unlock. A shortcut
                // over the PIN — only offered once a PIN exists (the
                // fallback) and the device has a strong biometric enrolled.
                // Hidden entirely in a duress session: enrolling there would
                // wrap the decoy key, and we never want the decoy profile to
                // teach the attacker that biometric "works".
                if (hasPin && biometricCapable && !inDuress) {
                    GlassPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.lock_unlock_with_fingerprint_face),
                                        fontWeight = FontWeight.SemiBold)
                                    Text(
                                        stringResource(R.string.lock_fast_convenient_but_it) +
                                            "fingerprint opens the real profile. PIN stays the " +
                                            "fallback. Recommended only if coercion isn't your " +
                                            "threat.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                    )
                                }
                                app.aether.aegis.ui.components.HexSwitch(
                                    checked = biometricOn,
                                    onCheckedChange = { want ->
                                        bioError = null
                                        if (want) {
                                            // Enabling routes through the
                                            // one-time warning + enrolment
                                            // (which wraps the REAL seal priv);
                                            // it doesn't flip on directly.
                                            showBioWarn = true
                                        } else {
                                            store.clearBiometric()
                                            biometricOn = false
                                        }
                                    },
                                )
                            }
                            bioError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // One-time warning before enrolment.
                // "Enable anyway" wraps the REAL seal priv from the
                // live session under a biometric-gated key.
                if (showBioWarn) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showBioWarn = false },
                        title = { Text(stringResource(R.string.tutorial_biometric_skips_duress)) },
                        text = {
                            Text(
                                stringResource(R.string.lock_biometric_login_is_convenient) +
                                    "protection. If someone forces your fingerprint, the " +
                                    "real profile opens. PIN is recommended for maximum " +
                                    "security.",
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showBioWarn = false
                                // Enrolment needs the live, decrypted seal
                                // priv to wrap under a biometric-gated key.
                                // It can only be missing if the session priv
                                // isn't loaded — bail with guidance rather
                                // than enrol against nothing.
                                val priv = app.aether.aegis.lock.PinSession.priv()
                                if (priv == null) {
                                    bioError = "Unlock with your PIN first, then enable biometric."
                                    return@TextButton
                                }
                                // enrolBiometric wraps the REAL priv; the
                                // callback reports success/failure so we only
                                // show "on" once the keystore actually took.
                                app.aether.aegis.ui.components.enrolBiometric(
                                    settingsContext, priv,
                                ) { ok ->
                                    biometricOn = ok
                                    if (!ok) bioError = "Couldn't enable biometric. Try again."
                                }
                            }) { Text(stringResource(R.string.tutorial_enable_anyway)) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { showBioWarn = false },
                            ) { Text(stringResource(R.string.tutorial_keep_pin_only)) }
                        },
                    )
                }

                // Device Admin enrollment moved to its own screen
                // (Opsec → Device Admin / "settings/deviceadmin"); it no
                // longer belongs on the app-PIN screen.

                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.settings_auto_lock), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.lock_how_long_after_backgrounding),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Shared log-scale slider. Lock store speaks
                        // milliseconds (0 = Immediately, Long.MAX_VALUE =
                        // Never); we translate at the boundary.
                        LogPeriodSlider(
                            valueSeconds = lockTimeoutMsToSec(timeoutMs),
                            onValueChange = { v ->
                                // Slider speaks seconds (null = Never); the
                                // store speaks ms. Translate, mirror locally,
                                // and only persist outside a duress session.
                                val t = lockTimeoutSecToMs(v)
                                timeoutMs = t
                                if (!inDuress) store.timeoutMs = t
                            },
                            minSeconds = 1.0,
                            maxSeconds = 365.0 * 24 * 3600,
                            instantSeconds = 0L,
                            neverSeconds = null,
                            allowNever = true,
                            instantLabel = "Immediately",
                            neverLabel = "Never",
                        )
                    }
                }

                // Three-layer duress panel:
                //
                //   Layer 0 (real)   — input + Save persists DURESS_1.
                //   Layer 1 (Fake #1)— input + Save persists DURESS_2.
                //                       Attacker creates Fake #2 themselves.
                //   Layer 2 (Fake #2)— sealed: "Duress PIN configured"
                //                       with no input fields, no buttons.
                //                       Reads as a finished real profile.
                //                       Attacker concludes Fake #1 was real.
                //
                // The panel is ALWAYS rendered — hiding it would tip off
                // a thief familiar with Aegis.
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // Same status label in all three layers, so the
                        // panel looks identical from the attacker's
                        // perspective. They see "Duress PIN set." in
                        // Fake #2 just like they would in a real
                        // profile with a duress configured.
                        Text(
                            if (hasDuress) "Duress PIN set." else "Duress PIN (optional)",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.lock_entering_this_pin_opens) +
                                "a normal messenger — no SOS, no tracking, no recordings. " +
                                "Your Trusted contacts are alerted silently at the moment you enter it. " +
                                "Use it when someone is forcing you to unlock the phone.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = duressPin,
                            onValueChange = {
                                duressPin = it.filter { c -> c.isDigit() }.take(8)
                            },
                            label = { Text(stringResource(R.string.lock_duress_pin_48_digits)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = confirmDuressPin,
                            onValueChange = {
                                confirmDuressPin = it.filter { c -> c.isDigit() }.take(8)
                            },
                            label = { Text(stringResource(R.string.duress_confirm_duress_pin)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        duressError?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = duressPin.length in 4..8 &&
                                    duressPin == confirmDuressPin,
                                onClick = {
                                    // Classify the entered PIN against the
                                    // existing slots so we can reject a duress
                                    // PIN that collides with the real or
                                    // already-set duress PIN — a collision
                                    // would make the gate ambiguous.
                                    val match = store.verifyPin(duressPin)
                                    when {
                                        inLayer2 -> {
                                            // Sealed: silent no-op + fake
                                            // success. The attacker can't
                                            // accidentally lock themselves
                                            // out of Fake #2 by mucking
                                            // with the duress here, and
                                            // we don't extend to Layer 3.
                                            hasDuress = true
                                            duressPin = ""
                                            confirmDuressPin = ""
                                            duressError = null
                                        }
                                        inLayer1 -> {
                                            // Attacker setting up their
                                            // duress PIN. Writes really
                                            // persist to DURESS_2 — the
                                            // attacker creates Fake #2.
                                            // Must collide with neither
                                            // real nor DURESS_1.
                                            if (match == app.aether.aegis.lock.LockStore.PinMatch.REAL ||
                                                match == app.aether.aegis.lock.LockStore.PinMatch.DURESS_1) {
                                                duressError = "Duress PIN must differ from the current PIN."
                                            } else {
                                                store.setDuress2Pin(duressPin)
                                                hasDuress = true
                                                duressPin = ""
                                                confirmDuressPin = ""
                                                duressError = null
                                            }
                                        }
                                        match == app.aether.aegis.lock.LockStore.PinMatch.REAL -> {
                                            // Real profile, but duress PIN
                                            // collides with real PIN.
                                            duressError = "Duress PIN must differ from the real PIN."
                                        }
                                        else -> {
                                            store.setDuressPin(duressPin)
                                            hasDuress = true
                                            duressPin = ""
                                            confirmDuressPin = ""
                                            duressError = null
                                        }
                                    }
                                },
                            ) { Text(if (hasDuress) "Update duress PIN" else "Save duress PIN") }
                            if (hasDuress) {
                                OutlinedButton(onClick = {
                                    // Remove targets the slot matching the
                                    // current layer: Fake #2 clears DURESS_2,
                                    // real clears DURESS_1. Layer 2 is sealed
                                    // (no-op) so the attacker can't unwind
                                    // their own decoy. The mirror always
                                    // flips so the UI reflects "removed".
                                    when {
                                        inLayer2 -> { /* sealed: no-op */ }
                                        inLayer1 -> store.clearDuress2Pin()
                                        else     -> store.clearDuressPin()
                                    }
                                    hasDuress = false
                                }) { Text(stringResource(R.string.story_screens_remove)) }
                            }
                        }
                    }
                }

                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(stringResource(R.string.lock_remove_pin), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.lock_deletes_the_pin_entirely) +
                                "one is set. After this, you'd set a fresh PIN to use " +
                                "the lock screen again. To temporarily disable the " +
                                "unlock screen WITHOUT losing your PIN, use the " +
                                "\"Require PIN to open Aegis\" toggle above instead.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            // In duress, faking a successful disable
                            // would lock-unlock the (fake) state and
                            // possibly drop the attacker back to the
                            // launcher unexpectedly — so we only flip
                            // the local mirrors and stay put. The real
                            // PIN is never cleared from a coerced session.
                            if (!inDuress) {
                                // Real profile: drop the PIN, then unlock so
                                // the user isn't bounced to a lock screen for
                                // a PIN that no longer exists.
                                store.clearPin()
                                lockState.unlock()
                            }
                            hasPin = false
                            hasDuress = false
                        }) {
                            // Button label was "Disable app lock" — misleading
                            // because the action also wipes the PIN, leaving
                            // the user with nothing to re-enable later. User
                            // flagged 2026.05.30: "disabling pin REMOVES IT.
                            // pin should stay once set." Now matches the
                            // section title so the destruction is clear.
                            Text(
                                stringResource(R.string.lock_remove_pin),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

// Bridge the LockStore's ms-based timeout (0 = Immediately,
// Long.MAX_VALUE = Never) onto the shared LogPeriodSlider's
// seconds-with-null convention (instantSeconds=0L, neverSeconds=null).
/** ms → slider seconds. 0 stays 0 (Immediately); Long.MAX_VALUE maps to
 *  null (Never); otherwise integer-divide to whole seconds. */
private fun lockTimeoutMsToSec(ms: Long): Long? = when {
    ms <= 0L -> 0L
    ms == Long.MAX_VALUE -> null
    else -> ms / 1000L
}

/** Inverse of [lockTimeoutMsToSec]: slider seconds → store ms. null →
 *  Long.MAX_VALUE (Never); 0 → 0 (Immediately); else ×1000. */
private fun lockTimeoutSecToMs(sec: Long?): Long = when {
    sec == null -> Long.MAX_VALUE
    sec <= 0L -> 0L
    else -> sec * 1000L
}

// DeviceAdminEnrollPanel was moved out of Lock settings into its own
// DeviceAdminScreen ("settings/deviceadmin"). Device Admin is its own
// opsec node now, not an app-PIN sub-panel.

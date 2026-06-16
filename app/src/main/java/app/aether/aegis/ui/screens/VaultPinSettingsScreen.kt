package app.aether.aegis.ui.screens

import app.aether.aegis.AegisApp
import app.aether.aegis.vault.VaultCrypto
import app.aether.aegis.vault.VaultLockStore
import app.aether.aegis.vault.VaultSession
import kotlinx.coroutines.launch
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
 * Set, change, or clear the vault PIN (first
 * state: "Vault PIN only"). Hidden-volume duress PIN is deferred to
 * a follow-up that wires the second key slot.
 *
 * Set: confirm matches, persist via [VaultLockStore.setPin]. Existing
 * PIN required to change OR clear so a thief who briefly holds the
 * device can't nuke the vault PIN to walk into the vault.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultPinSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val store = remember { VaultLockStore(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Whether a normal / duress PIN currently exists. Drive both the
    // copy ("set" vs "change") and which fields/buttons are shown. Flipped
    // locally on a successful set/clear so the UI updates without re-query.
    var hasPin by remember { mutableStateOf(store.hasPin) }
    var hasDuress by remember { mutableStateOf(store.hasDuressPin) }

    // Field buffers. `current` is only used to authorise a change to the
    // existing normal PIN; the duress fields are entirely separate inputs
    // (see the note further down — sharing them once caused a real bug).
    var current by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var duressFresh by remember { mutableStateOf("") }
    var duressConfirm by remember { mutableStateOf("") }
    // Mutually-exclusive-ish status lines: error (red) on a failed action,
    // info (primary) on success. Cleared on any field edit so a stale
    // message never lingers over fresh input.
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vault_pin_vault_pin)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
            )
        },
    ) { padding ->
        // Even the vault-PIN settings sit behind the app PIN: changing the
        // vault lock is itself a sensitive operation.
        app.aether.aegis.ui.components.PinGuardedContent(
            navController = navController,
            featureLabel = stringResource(R.string.vault_pin_vault_pin),
        ) {
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        if (hasPin) "Vault PIN is set" else "No vault PIN",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (hasPin)
                            "Opening the vault requires this PIN, even after the app is unlocked."
                        else
                            "Set a PIN to add a second-factor gate in front of the vault. " +
                                "Optional — without it the vault opens with just the app PIN.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            // Current PIN (only needed to change / clear an existing one).
            if (hasPin) {
                PinField(
                    label = stringResource(R.string.vault_pin_current_vault_pin),
                    value = current,
                    onChange = { current = it; error = null; info = null },
                )
            }

            PinField(
                label = if (hasPin) "New vault PIN" else stringResource(R.string.vault_pin_vault_pin),
                value = fresh,
                onChange = { fresh = it; error = null; info = null },
            )
            PinField(
                label = stringResource(R.string.vault_pin_confirm),
                value = confirm,
                onChange = { confirm = it; error = null; info = null },
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            info?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }

            // Set / change the normal vault PIN. Button enables only when
            // both new fields meet MIN_PIN and — if changing — the current
            // field is long enough to be a plausible PIN. The actual
            // correctness checks happen in onClick so we can show a precise
            // error rather than just greying the button.
            Button(
                enabled = fresh.length >= MIN_PIN && confirm.length >= MIN_PIN &&
                    (!hasPin || current.length >= MIN_PIN),
                onClick = {
                    // Require the correct existing PIN before allowing a
                    // change — stops a thief with a briefly-unlocked phone
                    // from overwriting the vault PIN to walk in.
                    if (hasPin && store.verifyPin(current) != VaultLockStore.PinMatch.NORMAL) {
                        error = "Current PIN is wrong"
                        return@Button
                    }
                    if (fresh != confirm) {
                        error = "PINs don't match"
                        return@Button
                    }
                    // Capture the OLD key (if any) before setPin
                    // overwrites the salt — needed to decrypt
                    // existing entries during re-encryption.
                    val oldKey = if (hasPin) {
                        store.deriveKey(current, VaultLockStore.PinMatch.NORMAL)
                    } else null
                    // setPin rotates the salt, so the NEW key must be
                    // derived AFTER it. Re-encrypt the existing "normal"
                    // slot entries from old → new key off the main thread,
                    // then wipe the old key bytes from memory.
                    store.setPin(fresh)
                    val newKey = store.deriveKey(fresh, VaultLockStore.PinMatch.NORMAL)
                    hasPin = true
                    current = ""
                    fresh = ""
                    confirm = ""
                    // Re-encryption runs off the main thread; don't claim it's
                    // finished until it actually is. The old code set the
                    // success message synchronously, BEFORE the async work
                    // ran (and would still claim success if it failed). Show
                    // an in-progress note, then the real outcome.
                    info = "Vault PIN saved. Re-encrypting existing entries…"
                    scope.launch {
                        val reOk = runCatching {
                            AegisApp.instance.repository.reencryptVaultSlot(
                                "normal", oldKey, newKey,
                            )
                        }.isSuccess
                        VaultCrypto.wipe(oldKey)
                        // Prime the new key so the immediate next vault read
                        // sees plaintext without an extra unlock prompt.
                        store.primeKey(fresh, VaultLockStore.PinMatch.NORMAL)
                        VaultSession.unlocked = true
                        VaultSession.slot = VaultLockStore.PinMatch.NORMAL
                        info = if (reOk) {
                            "Vault PIN saved. Existing entries re-encrypted."
                        } else {
                            "Vault PIN saved, but re-encrypting existing entries failed."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (hasPin) stringResource(R.string.duress_change_pin) else stringResource(R.string.recovery_unlock_set_pin)) }

            // "Remove vault PIN" was intentionally removed
            // ("the vault needs a second lock, always"). The
            // vault lock is mandatory — it can be CHANGED but never
            // disabled, and the gate (VaultGate) forces setup if one is
            // somehow absent. A removable second lock isn't a second lock.

            // --- Hidden volume — duress vault PIN -------------------
            // Only offered once the normal vault PIN exists. Setting
            // a duress PIN reveals a parallel "hidden vault": when
            // the user enters this PIN at the gate, the vault renders
            // a disjoint set of entries (those tagged keySlot=duress
            // in secure_notes). Under
            // coercion the user types the duress PIN; the attacker
            // sees decoy entries and has no signal that a hidden
            // slot exists.
            //
            // The setting flow uses its OWN PIN inputs — earlier
            // releases shared the "Current PIN" field with the
            // change-normal-PIN flow, which made the duress
            // section's Set button silently disabled until the user
            // scrolled up and typed their current normal PIN.
            // Reported as "can't set duress vault PIN".
            if (hasPin) {
                Spacer(modifier = Modifier.height(8.dp))
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            if (hasDuress) "Duress vault PIN is set"
                            else "No duress vault PIN",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.vault_pin_entering_this_pin_at) +
                                "vault — a parallel set of entries that the normal " +
                                "vault never shows. Tell no one it exists.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
                PinField(
                    label = if (hasDuress) "New duress PIN" else "Duress PIN",
                    value = duressFresh,
                    onChange = { duressFresh = it; error = null; info = null },
                )
                PinField(
                    label = stringResource(R.string.vault_pin_confirm_duress),
                    value = duressConfirm,
                    onChange = { duressConfirm = it; error = null; info = null },
                )
                // Set / change the duress PIN. Unlike the normal flow this
                // does NOT require the current normal PIN — the user is
                // already past the vault gate (unlocked) when they reach
                // here, so re-authing would be redundant. It does enforce
                // that the duress PIN differs from the normal one.
                Button(
                    enabled = duressFresh.length >= MIN_PIN &&
                        duressConfirm.length >= MIN_PIN,
                    onClick = {
                        if (duressFresh != duressConfirm) {
                            error = "Duress PINs don't match"
                            return@Button
                        }
                        // Differ from the NORMAL PIN — we can't
                        // verify the current normal PIN string here
                        // (the user typically lands in Settings via
                        // the unlocked Vault gate), but we can check
                        // the new duress PIN doesn't hash to the
                        // normal slot.
                        if (store.verifyPin(duressFresh) ==
                            VaultLockStore.PinMatch.NORMAL) {
                            error = "Duress PIN must differ from the normal vault PIN"
                            return@Button
                        }
                        store.setDuressPin(duressFresh)
                        hasDuress = true
                        duressFresh = ""
                        duressConfirm = ""
                        info = "Duress vault PIN saved."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (hasDuress) "Change duress PIN" else "Set duress PIN") }
                // The duress PIN — unlike the mandatory normal vault PIN —
                // CAN be removed. clearDuressPin only forgets the PIN; the
                // hidden-slot entries stay encrypted in storage, so setting
                // the same PIN again would re-expose them (the status copy
                // says exactly this so the user isn't misled into thinking
                // "remove" wipes the hidden vault).
                if (hasDuress) {
                    OutlinedButton(
                        onClick = {
                            store.clearDuressPin()
                            hasDuress = false
                            info = "Duress vault PIN removed. Hidden vault entries are still in storage; restoring the same PIN would surface them again."
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text(stringResource(R.string.vault_pin_remove_duress_pin)) }
                }
            }
        }
        }
    }
}

/**
 * Masked numeric PIN input shared by every PIN field on this screen.
 * Sanitises at the source: strips non-digits and caps length at [MAX_PIN]
 * so the rest of the screen only ever sees a clean digit string, and uses
 * the NumberPassword keyboard + password masking so the PIN isn't shown or
 * suggested.
 */
@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(MAX_PIN)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

// PIN length bounds. MIN_PIN gates the Set/Change buttons; MAX_PIN caps
// input in PinField. 4 is the usual minimum-PIN floor; 12 is a generous
// ceiling that still fits a single field comfortably.
private const val MIN_PIN = 4
private const val MAX_PIN = 12

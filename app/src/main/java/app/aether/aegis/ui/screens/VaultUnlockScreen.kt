package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisTopBar

import app.aether.aegis.ui.components.AegisButton

import app.aether.aegis.vault.VaultLockStore
import app.aether.aegis.vault.VaultSession
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
 * Vault gate. Wraps any content composable and enforces the vault's
 * OWN lock, which is MANDATORY and always separate from the app lock
 * ("the vault is too important to be unlocked
 * together with the app — it needs a second lock, always"):
 *
 *   - No vault PIN set yet → a setup prompt forces the user to create
 *     one before any entry. The vault is NEVER openable on the app lock
 *     alone. (This closes the old `!hasPin → open` path.)
 *   - Vault PIN set, session not unlocked → PIN entry.
 *   - Unlocked → render [content]. The vault then STAYS unlocked until
 *     the APP locks (explicit lock or idle timeout) — see below.
 *
 * ## Lock lifetime
 *
 * The vault used to re-lock in an `onDispose` here, i.e. the moment the
 * gate's content left composition. That fired on EVERY navigation within
 * the vault (open a note, view an attachment), every config change, and
 * every recomposition that disposed the node — so the vault "locked
 * itself constantly" and demanded the PIN again mid-use. The corrected
 * rule: a vault PIN is still MANDATORY and separate from the app PIN (you
 * must enter it once to get in), but once you're in it stays unlocked
 * until the app itself locks. That app-lock linkage lives outside this
 * composable so it can't be defeated by recomposition:
 *
 *   - explicit lock (lockManual / lockNow) → PinSession.clear() → the
 *     on-lock listener registered in AegisApp drops VaultSession.
 *   - idle-timeout relock → LockState.onForegrounded drops VaultSession.
 *   - "Lock vault now" in Security settings → VaultSession.lock() direct.
 *
 * Every entry point into the vault routes through this so callers don't
 * branch themselves — `navigate("notes")` stays a single call.
 *
 * First state ("Vault PIN only"). The
 * hidden-volume duress state (open into a parallel vault if the duress
 * PIN matches) needs the two-key-slot crypto layer; the duress slot
 * exists in VaultLockStore and unlock() already routes to it.
 */
@Composable
fun VaultGate(navController: NavController, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember { VaultLockStore(context) }
    // A vault PIN now ALWAYS exists once you're past the gate, and the
    // session is re-locked on every exit, so the only "already unlocked"
    // case is a same-composition recomposition.
    var hasPin by remember { mutableStateOf(store.hasPin) }
    var unlocked by remember { mutableStateOf(VaultSession.unlocked && store.hasPin) }
    when {
        unlocked -> {
            // No re-lock on dispose. The vault now stays unlocked until
            // the APP locks (see the KDoc "Lock lifetime" note) — the
            // old onDispose lock fired on every navigation/recomposition
            // and re-prompted for the PIN mid-use. App-lock linkage is
            // wired in AegisApp (on-lock listener) and LockState
            // (idle-timeout), where recomposition can't bypass it.
            content()
        }
        hasPin -> VaultPinPrompt(
            navController = navController,
            store = store,
            onUnlock = {
                VaultSession.unlocked = true
                unlocked = true
            },
        )
        else -> VaultSetupPrompt(
            navController = navController,
            store = store,
            onCreated = {
                hasPin = true
                unlocked = true
            },
        )
    }
}

/**
 * First-run setup for the mandatory vault lock. Shown when no vault PIN
 * exists — the user cannot enter the vault until they create one, so
 * the vault is always behind its own lock. New PIN + confirmation; on
 * success the PIN is stored and the session is primed (no re-prompt for
 * the PIN they just typed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultSetupPrompt(
    navController: NavController,
    store: VaultLockStore,
    onCreated: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = { Text("Set up the vault lock") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Create a vault PIN", fontWeight = FontWeight.SemiBold)
                    Text(
                        "The vault always has its own lock, separate from the " +
                            "app PIN — so even with the app open, your most " +
                            "sensitive files stay sealed until you enter this. " +
                            "It can't be recovered if forgotten.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            OutlinedTextField(
                value = pin,
                onValueChange = { v -> pin = v.filter { it.isDigit() }.take(MAX_PIN); error = null },
                label = { Text("New vault PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = confirm,
                onValueChange = { v -> confirm = v.filter { it.isDigit() }.take(MAX_PIN); error = null },
                label = { Text(stringResource(R.string.tutorial_confirm_pin)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = error != null,
                supportingText = { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } },
                modifier = Modifier.fillMaxWidth(),
            )
            AegisButton(
                enabled = pin.length >= MIN_PIN && confirm.length >= MIN_PIN,
                onClick = {
                    keyboard?.hide()
                    if (pin != confirm) {
                        error = "PINs don't match"
                        confirm = ""
                        return@AegisButton
                    }
                    // Persist the PIN, then unlock with it so the session
                    // key is derived + primed — no second prompt.
                    store.setPin(pin)
                    store.unlock(pin)
                    onCreated()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create vault lock") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultPinPrompt(
    navController: NavController,
    store: VaultLockStore,
    onUnlock: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(store.failedAttempts) }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = { Text("Vault locked") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Enter vault PIN", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Separate from the app PIN. Wrong attempts are " +
                            "remembered until the next successful unlock.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            OutlinedTextField(
                value = pin,
                onValueChange = { v -> pin = v.filter { it.isDigit() }.take(MAX_PIN) },
                label = { Text(stringResource(R.string.tutorial_pin)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = error != null,
                supportingText = {
                    val e = error
                    if (e != null) Text(e, color = MaterialTheme.colorScheme.error)
                    else if (failed > 0) Text("$failed failed attempt(s)", fontSize = 11.sp)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            AegisButton(
                enabled = pin.length >= MIN_PIN,
                onClick = {
                    keyboard?.hide()
                    // store.unlock = verifyPin + derive AES-256 key
                    // + populate VaultSession. The single call lands
                    // us in a fully-unlocked state ready for
                    // encrypted reads.
                    when (store.unlock(pin)) {
                        app.aether.aegis.vault.VaultLockStore.PinMatch.NORMAL,
                        app.aether.aegis.vault.VaultLockStore.PinMatch.DURESS -> {
                            store.resetAttempts()
                            onUnlock()
                        }
                        app.aether.aegis.vault.VaultLockStore.PinMatch.INVALID -> {
                            failed = store.recordFailedAttempt()
                            pin = ""
                            error = "Wrong PIN"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unlock") }
        }
    }
}

private const val MIN_PIN = 4
private const val MAX_PIN = 12

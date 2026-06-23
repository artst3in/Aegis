package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.aegis.AegisApp
import app.aether.aegis.lock.RecoveryPhrase
import app.aether.aegis.ui.theme.AegisCyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * "Forgot PIN → enter phrase" recovery flow.
 * Reachable from [LockScreen] via the "Forgot PIN?"
 * link.
 *
 * Because the seal keypair is rooted in the 24-word phrase, the phrase
 * alone is enough to recover: it re-derives the very same seal keypair
 * ([LockStore.deriveSealFromPhrase]) and opens the real profile, then
 * the user sets a fresh PIN. No old PIN, no backdoor — the
 * phrase IS the key.
 *
 * Two stages:
 *   0 (phrase) — paste/type the 24 words. We first validate the BIP39
 *                checksum locally, then verify against the stored
 *                verification hash so a *valid-but-wrong* phrase (someone
 *                else's) is rejected too.
 *   1 (newPin) — set a new app-lock PIN ([LockStore.setPinGateOnly]).
 *
 * On a successful phrase the recovered priv is re-wrapped into the TEE
 * ([LockStore.wrapAndStoreSealPriv]) so subsequent daily unlocks work
 * through Model B again without the phrase.
 *
 * This path only applies to phrase-rooted profiles (those that have
 * enrolled a phrase). A legacy PIN-rooted profile has no phrase to
 * recover with; [LockScreen] only offers the link when
 * [LockStore.hasRecoveryPhrase] is true.
 *
 * @param onCancel return to the PIN pad without changes.
 * @param onRecovered the real profile is now unlocked + a new PIN set.
 */
@Composable
fun RecoveryUnlockScreen(onCancel: () -> Unit, onRecovered: () -> Unit) {
    val lockState = AegisApp.instance.lockState
    val store = lockState.store
    val scope = rememberCoroutineScope()

    // stage 0 = enter phrase, stage 1 = set new PIN. We only advance to 1
    // AFTER the profile is actually unlocked (a verified phrase + a
    // re-derived keypair), so reaching the PIN step is itself proof the
    // recovery succeeded. `working` gates the buttons during the heavy
    // Argon2id verify/derive so it can't be double-submitted.
    var stage by remember { mutableStateOf(0) }
    var phrase by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.recovery_unlock_recover_with_your_phrase),
            color = AegisCyan,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))

        when (stage) {
            0 -> {
                Text(
                    stringResource(R.string.recovery_unlock_enter_your_24word_recovery) +
                        "This unlocks your account and lets you set a new PIN.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it; error = null },
                    label = { Text(stringResource(R.string.recovery_unlock_24word_phrase)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                error?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                AegisButton(
                    enabled = !working && phrase.isNotBlank(),
                    onClick = {
                        val words = RecoveryPhrase.normalize(phrase)
                        // Local checksum check first — cheap, catches typos
                        // before the heavier Argon2id verify/derive.
                        if (!RecoveryPhrase.isValid(words)) {
                            error = "That's not a valid 24-word phrase. Check for typos."
                            return@AegisButton
                        }
                        working = true
                        error = null
                        scope.launch {
                            // Verify against the stored hash BEFORE deriving:
                            // a BIP39-valid phrase can still be the wrong
                            // account's, and we don't want to derive/unlock
                            // off an unverified phrase. Heavy crypto runs on
                            // Dispatchers.Default, off the UI thread.
                            val ok = withContext(Dispatchers.Default) {
                                store.verifyRecoveryPhrase(words)
                            }
                            if (!ok) {
                                working = false
                                error = "Valid phrase, but not this account's. " +
                                    "Make sure it's the phrase you saved for this profile."
                                return@launch
                            }
                            // Re-derive the seal keypair from the phrase and
                            // open the real profile.
                            val kp = withContext(Dispatchers.Default) {
                                store.deriveSealFromPhrase(words)
                            }
                            if (kp == null) {
                                working = false
                                error = "Couldn't reconstruct your keys. Check Diagnostics."
                                return@launch
                            }
                            // Restore Model B: re-wrap the priv into the TEE so
                            // future unlocks don't need the phrase again.
                            withContext(Dispatchers.Default) {
                                store.wrapAndStoreSealPriv(kp.priv)
                            }
                            lockState.unlockWithKeypair(kp)
                            working = false
                            stage = 1
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (working) stringResource(R.string.settings_checking) else "Recover") }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.recovery_unlock_back_to_pin)) }
            }
            1 -> {
                Text(
                    stringResource(R.string.recovery_unlock_recovered_set_a_new),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
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
                    label = { Text(stringResource(R.string.tutorial_confirm_pin)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                AegisButton(
                    // 4–8 digits, and confirm must match. setPinGateOnly sets
                    // ONLY the PIN gate — the seal keypair is already restored
                    // and re-wrapped into the TEE from the phrase above, so
                    // this PIN is just the day-to-day unlock, not the key root.
                    enabled = newPin.length in 4..8 && newPin == confirmPin,
                    onClick = {
                        store.setPinGateOnly(newPin)
                        onRecovered()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.recovery_unlock_set_pin)) }
            }
        }
    }
}

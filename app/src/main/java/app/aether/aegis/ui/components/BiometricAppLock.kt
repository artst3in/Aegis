package app.aether.aegis.ui.components

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.aether.aegis.lock.BiometricUnlock
import app.aether.aegis.lock.LockStore
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * BiometricPrompt drivers for the app lock (the "Biometric" unlock
 * method). Two flows, both using Android's BiometricPrompt with a
 * CryptoObject so success actually yields key material:
 *
 *   - [enrolBiometric]  — wrap the REAL seal priv under a fresh
 *                         biometric-gated Keystore key.
 *   - [biometricUnlock] — unwrap it on a successful match.
 *
 * BIOMETRIC_STRONG only — NOT DEVICE_CREDENTIAL: the device PIN can't
 * carry Aegis's duress distinction, and the fallback must be Aegis's own
 * PIN pad. The prompt's negative button is therefore the route back to
 * the PIN, where duress lives.
 */

/** True if the device has a usable strong biometric enrolled. */
fun deviceHasStrongBiometric(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG,
    ) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Enrol biometric unlock: prompt once, and on success AES-GCM-wrap
 * [sealPriv] (the REAL seal priv, from the currently-unlocked session)
 * under a fresh biometric-gated key and persist it in [LockStore].
 * [onResult] reports success/failure on the main thread.
 */
fun enrolBiometric(context: Context, sealPriv: ByteArray, onResult: (Boolean) -> Unit) {
    val activity = context as? FragmentActivity ?: return onResult(false)
    val cipher = BiometricUnlock.newEnrolmentCipher() ?: return onResult(false)
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val ok = runCatching {
                    val c = result.cryptoObject?.cipher ?: return@runCatching false
                    val blob = c.doFinal(sealPriv)
                    LockStore(context).setBiometric(blob, c.iv)
                    true
                }.getOrDefault(false)
                onResult(ok)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false)
            }
        },
    )
    runCatching {
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Enable biometric unlock")
                .setSubtitle("Confirm to link your fingerprint or face")
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
            BiometricPrompt.CryptoObject(cipher),
        )
    }.onFailure { onResult(false) }
}

/**
 * Biometric unlock: prompt, and on success unwrap the REAL seal priv and
 * hand it to [onPriv] (caller installs it into the session and unlocks).
 * Biometric ALWAYS opens the real profile — duress lives only on the PIN
 * fallback. [onUnavailable] fires when biometric can't be used right now
 * (no enrolment, key invalidated by a new fingerprint, hardware gone);
 * the caller leaves the PIN pad in place. A user-cancelled prompt is a
 * no-op (they chose the PIN).
 */
fun biometricUnlock(
    context: Context,
    onPriv: (ByteArray) -> Unit,
    onUnavailable: () -> Unit,
) {
    val activity = context as? FragmentActivity ?: return onUnavailable()
    val store = LockStore(context)
    val iv = store.biometricIv ?: return onUnavailable()
    val cipher = BiometricUnlock.unlockCipher(iv)
    if (cipher == null) {
        // Key missing or permanently invalidated (new biometric enrolled
        // since). Drop the stale enrolment so the UI stops offering it.
        store.clearBiometric()
        return onUnavailable()
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                runCatching {
                    val c = result.cryptoObject?.cipher ?: return
                    val blob = store.biometricBlob ?: return
                    onPriv(c.doFinal(blob))
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Lockout / vendor errors that mean "key no longer usable"
                // → clear so we stop showing biometric; otherwise leave the
                // enrolment (a plain cancel must keep biometric available).
                if (errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT ||
                    errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS ||
                    errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE
                ) {
                    store.clearBiometric()
                }
                // Either way the PIN pad stays on screen — duress fallback.
            }
        },
    )
    runCatching {
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Aegis")
                .setSubtitle("Use your fingerprint or face")
                .setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
            BiometricPrompt.CryptoObject(cipher),
        )
    }.onFailure { onUnavailable() }
}

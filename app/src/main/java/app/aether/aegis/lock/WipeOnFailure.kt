package app.aether.aegis.lock

import android.app.ActivityManager
import android.content.Context

/**
 * Opt-in "wipe after N failed unlocks" (hardening —
 * "Wipe-after-N-failures").
 *
 * OFF by default. For the highest-threat users only, and gated behind an
 * explicit enable + confirmation dialog in Lock settings. When armed, the
 * lock screen counts consecutive wrong PINs and, on reaching the
 * threshold, irreversibly erases data.
 *
 * Two levels (spec):
 *   - **Wipe Aegis** (default, [wipePhone] = false): clears all of
 *     Aegis's own app data — messages, contacts, profiles, keys — via
 *     [ActivityManager.clearApplicationUserData]. Works on every device,
 *     no special privilege. The phone itself is untouched.
 *   - **Wipe phone** ([wipePhone] = true): full factory reset via
 *     [RemoteCommandHandler.fireWipe] — only possible when Aegis is
 *     Device Owner, so the UI only offers it then.
 *
 * Threshold is a slider, 5–50, default 20 (spec). It is compared against
 * the SAME consecutive-failure counter the lockout escalation uses
 * ([LockStore.failedAttempts]); a REAL/duress unlock resets that counter,
 * so a legitimate user who eventually types the right PIN never trips it.
 */
class WipeOnFailureStore private constructor(private val prefs: android.content.SharedPreferences) {

    companion object {
        operator fun invoke(context: Context): WipeOnFailureStore {
            // Same per-profile prefs file as the lock state, so the wipe
            // policy travels with the profile it protects.
            val name = app.aether.aegis.profile.ProfileRegistry.get(context)
                .current.prefsName(STORE_NAME)
            return WipeOnFailureStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
        }

        private const val STORE_NAME = "aegis_wipe_on_fail"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_WIPE_PHONE = "wipe_phone"

        const val MIN_THRESHOLD = 5
        const val MAX_THRESHOLD = 50
        const val DEFAULT_THRESHOLD = 20
    }

    /** Armed or not. Default OFF — this never fires unless the owner has
     *  deliberately turned it on through the confirmation dialog. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /** Consecutive wrong-PIN count that triggers the wipe (5..50). */
    var threshold: Int
        get() = prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)
            .coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        set(value) {
            prefs.edit().putInt(KEY_THRESHOLD, value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)).apply()
        }

    /** True = factory-reset the whole device (Device Owner only).
     *  False (default) = wipe only Aegis's app data. */
    var wipePhone: Boolean
        get() = prefs.getBoolean(KEY_WIPE_PHONE, false)
        set(value) { prefs.edit().putBoolean(KEY_WIPE_PHONE, value).apply() }

    /**
     * Should a wipe fire now, given the current consecutive-failure
     * count? True iff armed AND [failedAttempts] has reached the
     * threshold. The lock screen calls this right after recording a
     * failed attempt.
     */
    fun shouldWipe(failedAttempts: Int): Boolean =
        enabled && failedAttempts >= threshold
}

/**
 * Executes the data wipe selected by [WipeOnFailureStore]. Separate from
 * the store so the (irreversible) action is isolated and easy to audit.
 */
object LocalWipe {

    /**
     * Wipe Aegis's own app data — messages, contacts, profiles, keys,
     * SharedPreferences, the lot — and let the OS restart the process
     * clean. Equivalent to "Clear storage" in Android settings. Works on
     * any device with no special privilege. Returns false only if the
     * call couldn't be made (it normally does not return at all — the OS
     * kills the process as part of the wipe).
     */
    fun wipeAegisData(context: Context): Boolean {
        // Clear the AndroidKeyStore FIRST. clearApplicationUserData()
        // erases files + SharedPreferences but, on most Android versions,
        // does NOT remove Keystore entries — orphaning the wrapped seal
        // priv, the phrase-backup key, the pending-reseal key, the
        // biometric key, and the identity-wrap key after a "wipe"
        // (Security review 2026-06-07). The AndroidKeyStore is scoped to
        // the app's UID, so every alias in it belongs to Aegis: enumerate
        // and delete them all so the wipe leaves no cryptographic residue.
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                .apply { load(null) }
            ks.aliases().toList().forEach { alias ->
                runCatching { ks.deleteEntry(alias) }
            }
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return runCatching { am.clearApplicationUserData() }.getOrDefault(false)
    }

    /**
     * Factory-reset the whole device. Only effective when Aegis is Device
     * Owner (otherwise a no-op string from the underlying call). Delegates
     * to the same executor the remote WIPE command uses so there's one
     * nuclear path, not two.
     */
    fun wipePhone(): String =
        runCatching { app.aether.aegis.remote.RemoteCommandHandler.fireWipe() }
            .getOrElse { it.message ?: "failed" }
}

package app.aether.aegis.simswap

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings + last-seen-SIM tracker for the SIM swap alert feature.
 *
 * On boot we read the current SIM's carrier+operator into KEY_LAST_CARRIER
 * / KEY_LAST_OPERATOR. Whenever the SIM_STATE_CHANGED broadcast fires
 * and the new reading differs, we know a swap happened.
 *
 * "Differs" is intentionally loose: any change in carrier name, MCC/MNC,
 * or operator counts. We don't store IMSI / phone number / etc. — that
 * would persist sensitive identifiers on disk, and the broad-strokes
 * change-detection is enough.
 */
class SimSwapStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE_NAME),
            Context.MODE_PRIVATE,
        )

    /** True iff the SIM swap alert is armed. Defaults off — opt-in. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /** Free-form fingerprint of the last SIM we observed. Combination
     *  of carrier name + simOperator + simCountryIso. Empty if we've
     *  never observed a SIM (no permission, no SIM inserted). */
    var lastSimFingerprint: String
        get() = prefs.getString(KEY_LAST_FINGERPRINT, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_LAST_FINGERPRINT, value).apply() }

    /** Human-readable carrier name as of the last observation — shown
     *  to peers in the alert body so they know what changed. */
    var lastCarrier: String
        get() = prefs.getString(KEY_LAST_CARRIER, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_LAST_CARRIER, value).apply() }

    private companion object {
        private const val STORE_NAME = "aegis_simswap"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_FINGERPRINT = "last_fp"
        private const val KEY_LAST_CARRIER = "last_carrier"
    }
}

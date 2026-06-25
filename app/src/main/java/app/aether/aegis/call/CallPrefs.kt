package app.aether.aegis.call

import android.content.Context
import android.content.SharedPreferences

/**
 * Call-related user preferences. Currently a single toggle for
 * relay-only ICE mode:
 *
 *  - ON  (default): WebRTC uses TURN candidates only. Hides peer IPs
 *    from each other at the cost of routing through simplex.im's
 *    relay servers (latency + their bandwidth). Privacy-first default.
 *  - OFF: WebRTC uses every candidate path it can find (STUN + TURN +
 *    local). Faster, more likely to succeed on weird networks, but the
 *    peer learns your public IP when a direct path opens.
 *
 * When users report "calls don't connect" the most reliable knob is
 * flipping this off — it widens the candidate set so a flaky TURN
 * server isn't a single point of failure.
 */
class CallPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    var relayOnly: Boolean
        get() = prefs.getBoolean(KEY_RELAY_ONLY, true)
        set(value) { prefs.edit().putBoolean(KEY_RELAY_ONLY, value).apply() }

    companion object {
        private const val STORE_NAME = "aegis_call"
        private const val KEY_RELAY_ONLY = "relay_only"
    }
}

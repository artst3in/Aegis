package app.aether.aegis.simswap

import app.aether.aegis.AegisApp
import app.aether.aegis.core.MessageType
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reacts to SIM_STATE_CHANGED broadcasts. When the SIM fingerprint
 * differs from the last-observed one (carrier / operator / country),
 * fire a control-tagged alert to every paired peer. The alert lands
 * either over Wi-Fi (if connected) or from the new SIM's connection
 * once the thief brings it online — Aegis's outbox handles the gap.
 *
 * The store records the new fingerprint after sending, so a single
 * physical swap fires exactly one alert per paired peer.
 *
 * Requires READ_PHONE_STATE to read TelephonyManager. Without that
 * permission we silently skip — the feature degrades but doesn't
 * crash.
 */
class SimSwapMonitor(private val context: Context) {

    private val store = SimSwapStore(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (!store.enabled) return
            scope.launch { reconcile() }
        }
    }

    /** Register the broadcast listener. Safe to call from app start. */
    fun start() {
        // Seed the store with the current fingerprint if we've never
        // observed one — otherwise the first SIM-state-changed event
        // would be misread as a "swap" right after install.
        scope.launch { seedIfEmpty() }

        val filter = IntentFilter().apply {
            addAction("android.intent.action.SIM_STATE_CHANGED")
            addAction(TelephonyManager.ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                context, receiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "SIM receiver registration failed", it) }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun seedIfEmpty() {
        if (store.lastSimFingerprint.isNotBlank()) return
        val fp = readFingerprint() ?: return
        store.lastSimFingerprint = fp
        store.lastCarrier = readCarrier().orEmpty()
    }

    private suspend fun reconcile() {
        val newFp = readFingerprint() ?: ""
        val oldFp = store.lastSimFingerprint
        // Format migration: the previous fingerprint had four `|`-
        // separated parts (state|operator|country|carrier). The
        // carrier field has been dropped to fix the roaming false
        // alert. If the stored value still has the old shape, treat
        // this broadcast as a silent re-seed so the first event after
        // the update doesn't fire a fake swap alert to family.
        if (oldFp.count { it == '|' } != newFp.count { it == '|' }) {
            store.lastSimFingerprint = newFp
            store.lastCarrier = readCarrier().orEmpty()
            return
        }
        if (newFp == oldFp) return
        val oldCarrier = store.lastCarrier
        val newCarrier = readCarrier().orEmpty()
        // Update store FIRST so a repeated broadcast for the same
        // swap doesn't fire a second alert. The send is best-effort
        // from here.
        store.lastSimFingerprint = newFp
        store.lastCarrier = newCarrier

        // A real SIM swap (a known prior fingerprint changed; first-run
        // and format re-seeds were handled above) → earn the Number's
        // Up badge.
        if (oldFp.isNotBlank()) {
            app.aether.aegis.achievements.Achievements.unlock(
                app.aether.aegis.achievements.Achievement.NUMBERS_UP,
            )
        }

        val body = buildAlert(oldCarrier, newCarrier)
        // SIM swap is routine status data (a "sensor" event), not a
        // sos — Trusted only, per the trust model.
        val peers = AegisApp.instance.repository.trustedTargets()
        for (peer in peers) {
            runCatching {
                AegisApp.instance.protocolManager.sendMessage(
                    to = peer.publicKey,
                    content = body,
                    type = MessageType.STATUS,
                )
            }
        }
    }

    private fun readFingerprint(): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        if (!hasPhoneStatePermission()) return null
        // CRITICAL: fingerprint must use SIM-card-side fields ONLY.
        // Earlier versions mixed in networkOperatorName, which changes
        // when the phone roams to a different country's network even
        // though the SIM hasn't moved — every international trip fired
        // a false "SIM swap" alert at every paired family member.
        // simOperator (SIM's home MCC+MNC), simCountryIso (SIM's home
        // country), and simState (READY / ABSENT / PIN_REQUIRED) all
        // come from the card itself, so a real swap is the only thing
        // that flips them.
        val operator = tm.simOperator.orEmpty()
        val country = tm.simCountryIso.orEmpty()
        val state = tm.simState
        return "$state|$operator|$country"
    }

    private fun readCarrier(): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        if (!hasPhoneStatePermission()) return null
        return tm.networkOperatorName.takeIf { it.isNotBlank() }
            ?: tm.simOperatorName.takeIf { it.isNotBlank() }
    }

    private fun hasPhoneStatePermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_PHONE_STATE,
    ) == PackageManager.PERMISSION_GRANTED

    /** Body of the alert. `[aegis:sim-swap]` prefix so receivers route
     *  it as STATUS-class and don't store it in chat. JSON for the
     *  receiver's handler. */
    private fun buildAlert(oldCarrier: String, newCarrier: String): String {
        val json = org.json.JSONObject().apply {
            put("old", oldCarrier)
            put("new", newCarrier)
            put("ts", System.currentTimeMillis())
        }
        return "[aegis:sim-swap]$json"
    }

    private companion object {
        private const val TAG = "SimSwapMonitor"
    }
}

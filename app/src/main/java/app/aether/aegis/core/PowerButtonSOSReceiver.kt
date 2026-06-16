package app.aether.aegis.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Hardware sos via the power button ("press it 4 times").
 *
 * Stock Android doesn't let userspace intercept `KEYCODE_POWER`, but
 * every press toggles the screen — so a press-and-release sequence
 * fires `ACTION_SCREEN_OFF` → `ACTION_SCREEN_ON` → `ACTION_SCREEN_OFF`
 * → … in lock-step. We count alternations inside a ~3 s window; on the
 * fourth, we hand off to [SOSHandler.trigger].
 *
 * Registered at runtime (the two screen broadcasts can't be declared
 * in the manifest on modern Android — they're explicit-receiver only).
 * Lifecycle is owned by [AegisApp.onCreate] for the duration of the
 * process; no service binding, no extra wake locks.
 */
class PowerButtonSOSReceiver(
    private val appContext: Context,
) : BroadcastReceiver() {

    private val timestamps = ArrayDeque<Long>()

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON -> {
                val now = System.currentTimeMillis()
                timestamps.addLast(now)
                while (timestamps.isNotEmpty() && now - timestamps.first() > WINDOW_MS) {
                    timestamps.removeFirst()
                }
                if (timestamps.size >= TARGET_ALTERNATIONS) {
                    Log.i(TAG, "power-button x$TARGET_ALTERNATIONS in ${WINDOW_MS} ms")
                    timestamps.clear()
                    runCatching {
                        // State-dependent hardware trigger (SPEC duress trap).
                        // The gesture is bidirectional based on state ONLY the
                        // user can see:
                        //   - no SOS active  → trigger a (voluntary) SOS
                        //   - DURESS active  → CANCEL it (power x4 is the silent
                        //     duress SOS's only cancel — the duress profile has
                        //     no cancel UI, so a coerced real-PIN can't find or
                        //     stop the broadcast)
                        //   - voluntary SOS active → no-op (cancel via the SOS
                        //     screen; power x4 never touches a visible SOS)
                        // The coercer believes x4 triggers SOS — true normally —
                        // so they avoid the one gesture that stops it. Their own
                        // fear is the lock on the cancel button.
                        val sos = app.aether.aegis.AegisApp.instance.sosHandler
                        val active = sos.state.value
                        when {
                            active == null -> sos.trigger(SOSTrigger.BUTTON)
                            active.trigger == SOSTrigger.DURESS -> sos.cancel()
                            else -> { /* visible SOS already running — leave it */ }
                        }
                    }.onFailure { Log.w(TAG, "power-button sos handling failed", it) }
                }
            }
        }
    }

    /** Guards against double register / unregister — the trust-container
     *  gate (Phase 1) may flip our lifecycle on
     *  contact changes, and unregistering a never-registered receiver
     *  throws IllegalArgumentException. */
    private var registered = false

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        appContext.registerReceiver(this, filter)
        registered = true
    }

    /** Unregister and reset the counter. Called when the last
     *  sos-eligible (Trusted ∪ Emergency) contact is removed — with
     *  nobody to alert, the sos module stands down entirely. */
    fun unregister() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(this) }
        timestamps.clear()
        registered = false
    }

    private companion object {
        private const val TAG = "PowerSOS"
        // The trigger is "power button ×4". Each physical
        // press toggles the screen state once, so 4 presses produce
        // 4 SCREEN_ON/SCREEN_OFF events — but USB attach/detach also
        // toggles the screen on some hardware, and a Pixel will flip
        // the screen on every adb command if the OEM "wake on USB"
        // option is on. To avoid spurious sos during DO setup, the
        // counter requires 4 alternations within a 2 s window (was
        // 3 s) — that's still hand-mash territory for a real user
        // but a notch above passive USB chatter.
        private const val TARGET_ALTERNATIONS = 4
        private const val WINDOW_MS = 2_000L
    }
}

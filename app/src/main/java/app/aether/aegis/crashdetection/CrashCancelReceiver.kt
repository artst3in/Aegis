package app.aether.aegis.crashdetection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the "I'M OK" action on the crash-countdown notification.
 * Cancels the pending crash sos so
 * nothing is broadcast. Same process as [CrashDetector], so it just
 * calls into the singleton.
 */
class CrashCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        runCatching { CrashDetector.cancelCountdown() }
    }
}

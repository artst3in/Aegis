package app.aether.aegis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/**
 * Handles the "STOP SOS" notification action posted on the
 * sender's OWN phone when [app.aether.aegis.core.SOSHandler] is active.
 *
 * Earlier the only way the sender could stop their own sos was
 * from the in-app SOSScreen, which they could only see if Aegis
 * was foregrounded — useless in the snatch-detection case where
 * the phone is locked in the thief's pocket and the owner doesn't
 * know sos fired at all. Now [SOSHandler.start] posts an
 * ongoing notification with this action so the owner has a way out
 * regardless of UI state.
 */
class SOSCancelOwnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { AegisApp.instance.sosHandler.cancel() }
        runCatching {
            NotificationManagerCompat.from(context)
                .cancel(OWN_SOS_NOTIF_ID)
        }
    }

    companion object {
        const val ACTION_CANCEL_OWN = "app.aether.aegis.action.SOS_CANCEL_OWN"
        /** Distinct from SOS_NOTIF_ID (1000) which is the
         *  receive-side banner. The sender's own banner uses 1001
         *  so dismissing one doesn't affect the other. */
        const val OWN_SOS_NOTIF_ID = 1001
    }
}

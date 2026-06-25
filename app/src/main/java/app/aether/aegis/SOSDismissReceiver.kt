package app.aether.aegis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/**
 * Handles the Dismiss action attached to inbound sos notifications.
 *
 * The sos banner is built with `setOngoing(true)` + `setAutoCancel
 * (false)` so a quick swipe doesn't accidentally clear what is the
 * single most important notification the app can show. The trade-off
 * is that without an explicit Dismiss affordance, a stale sos
 * notification can only be cleared by the sender broadcasting a
 * `[aegis:sos][sos cancelled]` envelope — and if that never
 * arrives (sender's app force-stopped, sender's phone died, sender
 * never resolves the alert), the receiver is stuck with the banner
 * forever. Force-stopping Aegis to silence it is the only workaround
 * the user has, which defeats the purpose of running a security app.
 *
 * Dismiss does the same thing the cancel-envelope path would do:
 *   1. Marks the alert ended in SOSAlertStore so the in-app
 *      dashboard chip stops showing.
 *   2. Cancels the system notification.
 *
 * Does NOT propagate a cancel back to the sender — they're still
 * in SOS. This is a receiver-side acknowledgement only.
 */
class SOSDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val peerKey = intent.getStringExtra(EXTRA_PEER_KEY).orEmpty()
        if (peerKey.isNotBlank()) {
            // markDismissed (not markEnded) so the sender's 30 s
            // re-broadcast loop can't resurrect the banner every
            // half-minute. AegisApp.notifySOS checks isDismissed
            // before re-creating the alert; the flag clears on the
            // sender's proper cancel envelope or when the user
            // re-opens the dashboard for that peer.
            app.aether.aegis.sos.SOSAlertStore.markDismissed(peerKey)
        }
        NotificationManagerCompat.from(context).cancel(AegisApp.SOS_NOTIF_ID)
    }

    companion object {
        const val ACTION_DISMISS = "app.aether.aegis.action.SOS_DISMISS"
        const val EXTRA_PEER_KEY = "peer_key"
    }
}

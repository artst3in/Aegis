package app.aether.aegis.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the Accept / Reject pending intents fired from the incoming-
 * call notification. Registered at app start in AegisApp so the actions
 * work even when MainActivity isn't on top.
 *
 *  - Accept: hands off to CallManager.acceptIncoming, then launches
 *    MainActivity with the incoming-call extras so the user lands
 *    inside CallScreen.
 *  - Reject: tells CallManager to send /_call reject and dismiss the
 *    notification; no UI follow-up needed.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CallManager.ACTION_ACCEPT -> {
                val active = CallStore.active.value
                CallManager.acceptIncoming()
                if (active != null) {
                    // Surface the call UI. CallScreen will pick up the
                    // CallStore's incoming state and skip the placeCall
                    // path (since outgoing == false).
                    val open = Intent(context, app.aether.aegis.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("incoming_call_peer", active.peerPubkey)
                        putExtra("incoming_call_name", active.peerDisplayName)
                        putExtra("incoming_call_video", active.media == CallMediaType.Video)
                    }
                    context.startActivity(open)
                }
            }
            CallManager.ACTION_REJECT -> CallManager.rejectIncoming()
        }
    }
}

package app.aether.aegis

import app.aether.aegis.core.MessageType
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/**
 * Handles the Reply + Mark-as-read pending intents fired from the
 * message notification. Reply works for both regular notification
 * inline-reply AND Android Auto voice reply — Auto reads our
 * MessagingStyle notification, attaches the user's spoken text via
 * RemoteInput, and broadcasts here.
 *
 * Contract: showsUserInterface=false on both actions, so we MUST NOT
 * open any activity from here. Just fire the send, dismiss the
 * notification, and return.
 */
class MessageReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val peerKey = intent.getStringExtra(EXTRA_PEER_KEY).orEmpty()
        if (peerKey.isBlank()) return

        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY_TEXT)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (text.isNotBlank()) {
                    AegisApp.instance.protocolManager.sendMessage(
                        to = peerKey,
                        content = text,
                        type = MessageType.TEXT,
                    )
                }
                // Either way clear the notification so the user (or
                // Auto) doesn't see the same prompt linger.
                NotificationManagerCompat.from(context).cancel(peerKey.hashCode())
            }
            ACTION_MARK_READ -> {
                NotificationManagerCompat.from(context).cancel(peerKey.hashCode())
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "app.aether.aegis.action.MSG_REPLY"
        const val ACTION_MARK_READ = "app.aether.aegis.action.MSG_MARK_READ"
        const val EXTRA_PEER_KEY = "app.aether.aegis.extra.PEER_KEY"
        const val KEY_REPLY_TEXT = "app.aether.aegis.reply.TEXT"
    }
}

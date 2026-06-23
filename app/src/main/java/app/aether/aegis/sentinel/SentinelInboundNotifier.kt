package app.aether.aegis.sentinel

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Surfaces an inbound sentinel event from a contact as a SILENT
 * Android notification. Per the Sentinel rule —
 * "Sentinel does NOT sound a local alarm. Ever." — the receiver
 * follows the same constraint as the sender: no sound, no
 * heads-up, no vibration. The notification appears in the shade,
 * the badge dot updates, and the user discovers it on their own
 * terms.
 *
 * Uses [app.aether.aegis.AegisApp.CHANNEL_SENTINEL_INBOX] which is
 * configured IMPORTANCE_LOW with `setSound(null, null)` so the
 * channel-wide policy enforces silence even if a future caller
 * forgets the per-notification flags.
 */
object SentinelInboundNotifier {

    private const val NOTIF_ID_BASE = 5800
    private val counter = java.util.concurrent.atomic.AtomicInteger(0)

    fun notify(context: Context, peerName: String, stage: String, batteryPct: Int?) {
        runCatching {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            val batt = batteryPct?.let { " · battery $it%" } ?: ""
            // Notification content privacy. A sentinel event names a contact
            // and what their cascade detected — sensitive metadata, so it
            // follows the user's privacy choice. HIDDEN redacts both the
            // contact name and the event detail; any level above FULL marks
            // the lock screen SECRET. (SOS is exempt; this is not SOS.)
            val privacy = app.aether.aegis.prefs.NotificationPrivacyPrefs(context).level
            val full = privacy == app.aether.aegis.prefs.NotificationPrivacy.FULL
            val hidden = privacy == app.aether.aegis.prefs.NotificationPrivacy.HIDDEN
            val title = if (hidden) {
                context.getString(app.aether.aegis.R.string.app_name)
            } else "$peerName · Sentinel: $stage"
            val body = if (full) {
                "Open Aegis to view event + 3D-model recording$batt"
            } else "New alert"
            // Tap intent: open MainActivity with an extra that the
            // activity reads to navigate into settings/sentinel/inbox.
            val openIntent = android.content.Intent(
                context,
                app.aether.aegis.MainActivity::class.java,
            ).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_sentinel_inbox", true)
            }
            val pi = android.app.PendingIntent.getActivity(
                context, 0, openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(
                context,
                app.aether.aegis.AegisApp.CHANNEL_SENTINEL_INBOX,
            )
                .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_shield)
                .setColor(app.aether.aegis.AegisApp.BRAND_CYAN_ARGB)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .apply { if (!full) setVisibility(NotificationCompat.VISIBILITY_SECRET) }
                .build()
            // Unique id per notification so multiple inbound events
            // stack in the shade rather than overwriting each other.
            // Counter wraps every 10000 to bound the id range.
            val id = NOTIF_ID_BASE + (counter.incrementAndGet() % 10_000)
            nm.notify(id, n)
        }
    }
}

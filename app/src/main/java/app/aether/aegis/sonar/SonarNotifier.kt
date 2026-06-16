package app.aether.aegis.sonar

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Surfaces a sonar detection to the user as a notification — the
 * connective tissue between "the engine saw something move" and
 * "the user actually finds out about it".
 *
 * Already throttled in the engine (per-detection cooldown of 1.5 s),
 * so the only extra job here is keeping the channel quiet and
 * collapsing repeats — `setOnlyAlertOnce` on the same notif id so a
 * burst of activity within a minute updates the existing toast
 * instead of stacking ten.
 */
object SonarNotifier {

    private const val NOTIF_ID = 5701

    fun notify(context: Context, ts: Long, delta: Double) {
        runCatching {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            val n = NotificationCompat.Builder(context, app.aether.aegis.AegisApp.CHANNEL_SONAR)
                .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_shield)
                .setColor(app.aether.aegis.AegisApp.BRAND_CYAN_ARGB)
                .setContentTitle("Sonar — motion near phone")
                .setContentText(
                    "Δ = %.4f · %s".format(
                        delta,
                        java.text.SimpleDateFormat(
                            "HH:mm:ss",
                            java.util.Locale.getDefault(),
                        ).format(java.util.Date(ts)),
                    ),
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }
}

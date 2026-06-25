package app.aether.aegis.admin

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Fires the one-shot "Cyan engaged" notification the first time
 * every skill-tree node lights up (Cyan, 10/10 nodes including
 * Device Owner). Cyan is the Aegis
 * brand colour and the highest honour — the avatar frame for users
 * who maxed the climb. Idempotent: gates on a SharedPreferences
 * flag so the user sees the welcome exactly once per completion.
 * If they ever drop below Cyan and climb back, the flag resets so
 * they see it again.
 *
 * Called from [app.aether.aegis.AegisApp.onCreate] inside a runCatching so a
 * notification failure can't take down startup.
 *
 * Earlier iterations of this announcer were called PALLADION (then
 * Diamond) and used a silver-white accent. The tier system was later
 * collapsed to five rungs with Cyan as the crown,
 * so the announcer now wears the brand colour itself.
 */
object CyanAnnouncer {

    private const val STORE = "aegis_cyan_tier"
    private const val KEY_ANNOUNCED = "announced"
    private const val NOTIF_ID = 4710

    fun checkAndAnnounce(context: Context) {
        val tier = ShieldTierEngine.currentTier(context)
        val p = prefs(context)
        if (tier != ShieldTier.Cyan) {
            // Dropped below the crown — reset the flag so the next
            // ascent re-fires. Cheap.
            if (p.getBoolean(KEY_ANNOUNCED, false)) {
                p.edit().remove(KEY_ANNOUNCED).apply()
            }
            return
        }
        if (p.getBoolean(KEY_ANNOUNCED, false)) return
        post(context)
        p.edit().putBoolean(KEY_ANNOUNCED, true).apply()
    }

    private fun post(context: Context) {
        runCatching {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            val n = NotificationCompat.Builder(context, app.aether.aegis.AegisApp.CHANNEL_SONAR)
                .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_shield)
                .setColor(app.aether.aegis.AegisApp.BRAND_CYAN_ARGB)
                .setContentTitle("Cyan engaged")
                .setContentText("Every node lit. You are Aegis.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Every node in the skill tree is lit. You wear the " +
                            "Aegis brand — silent updates, full keyguard " +
                            "lockdown on sos, credential-encrypted key " +
                            "eviction. The shield that does not fall."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            app.aether.aegis.profile.ProfileRegistry.get(context).current.prefsName(STORE),
            Context.MODE_PRIVATE,
        )
}

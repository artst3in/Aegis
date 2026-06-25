package app.aether.aegis.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.aether.aegis.services.ProtocolService

/**
 * Auto-start Aegis after two events:
 *
 *  - BOOT_COMPLETED     — device reboot, restore the foreground service
 *                         that keeps the protocol manager + transports
 *                         alive.
 *  - MY_PACKAGE_REPLACED — self-update install just finished. Android
 *                         kills the old process the moment
 *                         PackageInstaller swaps in the new APK; without
 *                         this branch the user is left with no running
 *                         Aegis until they manually open the app. Both
 *                         BOOT_COMPLETED and MY_PACKAGE_REPLACED are on
 *                         the allowed-list for foreground-service starts
 *                         from a BroadcastReceiver on Android 12+, so
 *                         startForegroundService here is safe.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val serviceIntent = Intent(context, ProtocolService::class.java)
                context.startForegroundService(serviceIntent)
                // Group module auto-disable timer is intentionally
                // canceled across reboots: the module stays in its last explicit
                // state (on if user left it on), but a pending
                // pre-reboot timer would fire at an unrelated
                // moment after restart and surprise the user. Next
                // Groups tab exit will reschedule from zero.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    runCatching {
                        app.aether.aegis.groups.GroupModuleAutoDisableWorker
                            .cancel(context)
                    }
                }
                // After a self-update, re-launch the app so the user lands
                // back in it instead of having to tap the launcher icon.
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    // Distinguish an UPDATE from a ROLLBACK/downgrade so the
                    // notification doesn't announce "Aegis was updated" after
                    // the user (or BootHealthMonitor) just rolled BACK (user
                    // report 2026.06.14). Compare the freshly-installed
                    // versionCode against the one running at the previous
                    // replace; lower = downgrade. Written back here (not in
                    // Application.onCreate, which runs first and would clobber
                    // the previous value before this read).
                    val prefs = app.aether.aegis.update.UpdatePrefs(context)
                    val currentVc = app.aether.aegis.BuildConfig.VERSION_CODE.toLong()
                    val prevVc = prefs.lastSeenVersionCode
                    val rolledBack = prevVc > 0L && currentVc < prevVc
                    prefs.lastSeenVersionCode = currentVc
                    val replaceTitle =
                        if (rolledBack) "Aegis rolled back" else "Aegis was updated"
                    val replaceText =
                        if (rolledBack) {
                            "Restored ${app.aether.aegis.BuildConfig.VERSION_NAME}. Tap to open."
                        } else {
                            "Tap to open"
                        }
                    val launchIntent = context.packageManager
                        .getLaunchIntentForPackage(context.packageName)
                        ?: return
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    // (1) Direct launch — instant + silent when allowed
                    // (Device Owner, or pre-Android-10). On Android 10+ a
                    // normal app's background-activity-start from a receiver
                    // is dropped, which is why a plain install "closes and
                    // you must reopen manually".
                    runCatching { context.startActivity(launchIntent) }
                    // (2) Full-screen-intent fallback for the normal
                    // (non-Device-Owner) case where (1) is BAL-blocked. An
                    // FSI on a HIGH-importance channel is the sanctioned way
                    // to surface UI from the background: screen-on (the
                    // just-tapped-update case) → the system launches the
                    // activity immediately; locked/off → a heads-up the user
                    // taps once. Same mechanism the call/sos screens use.
                    // NOTE: Android 14+ may downgrade FSI for non-call/alarm
                    // apps unless the user grants the FSI permission in
                    // settings; then it lands as a one-tap heads-up.
                    runCatching {
                        val pi = android.app.PendingIntent.getActivity(
                            context, 0, launchIntent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                                android.app.PendingIntent.FLAG_IMMUTABLE,
                        )
                        val notif = androidx.core.app.NotificationCompat.Builder(
                            context, app.aether.aegis.AegisApp.CHANNEL_MESSAGES,
                        )
                            .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_shield)
                            .setColor(app.aether.aegis.AegisApp.BRAND_CYAN_ARGB)
                            .setContentTitle(replaceTitle)
                            .setContentText(replaceText)
                            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
                            .setAutoCancel(true)
                            .setTimeoutAfter(10_000)
                            .setContentIntent(pi)
                            .setFullScreenIntent(pi, true)
                            .build()
                        if (androidx.core.app.NotificationManagerCompat.from(context)
                                .areNotificationsEnabled()
                        ) {
                            androidx.core.app.NotificationManagerCompat.from(context)
                                .notify(1502, notif)
                        }
                    }
                }
            }
        }
    }
}

package app.aether.aegis.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives PackageInstaller session callbacks for [UpdateInstaller].
 *
 * On every install attempt, `session.commit()` fires an IntentSender
 * with `EXTRA_STATUS` set to one of:
 *
 *   STATUS_PENDING_USER_ACTION  → system needs the user to confirm.
 *                                 Carries the confirmation Intent in
 *                                 EXTRA_INTENT. We launch it; without
 *                                 that the Install button looks dead.
 *   STATUS_SUCCESS              → install finished. No-op.
 *   STATUS_FAILURE_*            → log and reset UpdateState so the
 *                                 user can retry.
 *
 * Registered dynamically from [AegisApp.onCreate] — broadcasts with
 * setPackage(packageName) can be intercepted by a runtime receiver,
 * which is simpler than declaring the receiver in the manifest with
 * the right export rules for Android 13+ implicit-broadcast rules.
 */
class InstallSessionReceiver : BroadcastReceiver() {

    // UnsafeIntentLaunch suppressed by design: this receiver is registered
    // RECEIVER_NOT_EXPORTED (AegisApp.onCreate) and the result PendingIntent is
    // built with setPackage(self) (UpdateInstaller), so only the system's
    // PackageInstaller callback can deliver here — no third-party app can inject
    // the EXTRA_INTENT we launch. Sanitizing the system's confirmation intent
    // would risk breaking the self-update confirm dialog.
    @android.annotation.SuppressLint("UnsafeIntentLaunch")
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Log.w(TAG, "PENDING_USER_ACTION without EXTRA_INTENT")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // ALWAYS post the heads-up notification. Previously
                // we tried startActivity first and only fell back to
                // a notification if it threw — but Android 12+'s
                // background-activity-launch rule silently NO-OPS
                // the call (no exception, just nothing visible), so
                // runCatching.isSuccess returned true and the
                // notification was skipped. User saw nothing at all
                // — neither dialog nor notification. Always-posting
                // gives a guaranteed path; if startActivity also
                // worked we get a redundant heads-up that self-
                // cancels on STATUS_SUCCESS, harmless.
                surfaceInstallPrompt(context, confirm)
                // Still try the direct launch — when the receiver
                // fires while a foreground activity (Settings,
                // typically) is alive the activity start succeeds
                // and the user gets the dialog instantly.
                runCatching { context.startActivity(confirm) }
                    .onFailure { Log.w(TAG, "could not launch install confirm: $it") }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "install succeeded")
                UpdateState.set(UpdateState.Status.Idle)
                runCatching {
                    androidx.core.app.NotificationManagerCompat.from(context)
                        .cancel(CONFIRM_NOTIF_ID)
                }
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "status=$status"
                Log.w(TAG, "install failed: $msg")
                UpdateState.set(UpdateState.Status.Failed("Install failed: $msg"))
            }
        }
    }

    /**
     * High-priority fallback notification carrying the system's
     * install-confirmation Intent. fullScreenIntent forces it to pop
     * as a heads-up takeover on Android 10+ — same mechanism the
     * sos notification uses to grab the lock screen — so the user
     * lands on the install dialog without having to dig through the
     * shade. Acts as a manual replacement for the OS-issued
     * "Aegis wants to install" notification that fires when the
     * silent path demotes from a background context.
     */
    private fun surfaceInstallPrompt(context: Context, confirm: Intent) {
        val pi = android.app.PendingIntent.getActivity(
            context, 0, confirm,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = androidx.core.app.NotificationCompat.Builder(
            context, app.aether.aegis.AegisApp.CHANNEL_SERVICE,
        )
            .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_shield)
            .setColor(app.aether.aegis.AegisApp.BRAND_CYAN_ARGB)
            .setContentTitle("Install Aegis update")
            .setContentText("Tap to confirm the system install dialog.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(CONFIRM_NOTIF_ID, notif)
        }
    }

    companion object {
        private const val TAG = "InstallSessionRx"
        private const val CONFIRM_NOTIF_ID = 1501
        const val ACTION = "app.aether.aegis.UPDATE_INSTALL_RESULT"
    }
}

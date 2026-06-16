package app.aether.aegis.backup

import app.aether.aegis.AegisApp
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic reminder: if BackupPrefs.intervalDays > 0 and the last
 * backup is older than that interval, post a low-priority notification
 * pointing to Settings → Backup. Runs once every 24h. No-op if the
 * cadence is set to Off or the user is current.
 */
class BackupReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /**
     * Check the cadence and post a reminder if one is due. A thrown error
     * maps to [Result.retry] so WorkManager re-attempts later rather than
     * silently dropping a missed backup nudge; a clean run (including the
     * "not due" no-op) is [Result.success].
     */
    override suspend fun doWork(): Result {
        return runCatching {
            val prefs = BackupPrefs(applicationContext)
            // isReminderDue() folds in both the "cadence Off" and "user is
            // current" cases, so the worker can stay armed unconditionally
            // and bail cheaply here.
            if (!prefs.isReminderDue()) return@runCatching Result.success()
            postReminder()
            Result.success()
        }.getOrElse { Result.retry() }
    }

    /**
     * Build and post the low-priority reminder notification. Tapping it
     * deep-links into Settings → Backup via the `open_route` extra that
     * MainActivity reads. Suppressed (not posted) when the user has
     * notifications disabled, so we don't no-op-crash on Android 13+.
     */
    private fun postReminder() {
        val open = Intent(applicationContext, app.aether.aegis.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_route", "settings/backup")
        }
        // IMMUTABLE is required from Android 12+; UPDATE_CURRENT refreshes the
        // extras if a PendingIntent with this request code already exists.
        val pi = PendingIntent.getActivity(
            applicationContext, NOTIF_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(applicationContext, AegisApp.CHANNEL_SERVICE)
            .setSmallIcon(app.aether.aegis.R.drawable.ic_notif_shield)
            .setColor(AegisApp.BRAND_CYAN_ARGB)
            .setContentTitle("Time to back up Aegis")
            .setContentText("Your data is not safe from app deletion. Tap to back up to a file.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Your data is not safe from app deletion. Tap to back up to a file " +
                        "you control — settings, contacts, messages, attachments.",
                ),
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // Gate on the runtime toggle: notify() would throw without POST_-
        // NOTIFICATIONS on Android 13+, and posting against the user's wish
        // is pointless anyway.
        if (NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            NotificationManagerCompat.from(applicationContext).notify(NOTIF_ID, notif)
        }
    }

    companion object {
        // Unique work name so re-scheduling replaces rather than stacks.
        private const val UNIQUE_NAME = "aegis-backup-reminder"
        // Fixed notification + PendingIntent request id; a new reminder
        // replaces the previous one instead of piling up a stack.
        private const val NOTIF_ID = 1700

        /** Schedule (or re-schedule) the periodic worker. Called from
         *  AegisApp.onCreate so the worker is always armed; doWork
         *  bails fast when the user has cadence Off or is current. */
        fun schedule(context: Context) {
            // Don't nag while the battery is low — a backup reminder is never
            // urgent enough to spend reserve power on.
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<BackupReminderWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                // 6 h initial delay so a fresh install isn't nagged the moment
                // it launches, before the user has any data worth backing up.
                .setInitialDelay(6, TimeUnit.HOURS)
                .build()
            // KEEP: an existing schedule wins, so calling this every onCreate
            // is idempotent and won't reset the 24 h cadence on each launch.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }
    }
}

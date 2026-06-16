package app.aether.aegis.schedule

import app.aether.aegis.AegisApp
import app.aether.aegis.core.MessageType
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Polls the scheduled_messages table every 15 minutes (WorkManager's
 * periodic minimum). Anything overdue gets shipped via protocolManager
 * + deleted from the table.
 *
 * 15-minute granularity means "Send in 5 minutes" can actually fire up
 * to ~20 minutes late. Fine for human-scale "remind X tomorrow morning"
 * scheduling; if we ever need sub-15-min precision we'd switch to a
 * foreground-service alarm.
 */
class ScheduledMessageWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val ready = AegisApp.instance.repository.readyScheduled()
            for (msg in ready) {
                runCatching {
                    AegisApp.instance.protocolManager.sendMessage(
                        to = msg.toKey,
                        content = msg.body,
                        type = MessageType.TEXT,
                    )
                }
                AegisApp.instance.repository.deleteScheduled(msg.id)
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val UNIQUE_NAME = "aegis-scheduled-messages"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduledMessageWorker>(
                15, TimeUnit.MINUTES,
            ).build()
            // 15 min is the floor WorkManager allows for periodic jobs.
            // For sub-15-minute precision we'd need foreground service
            // polling, which is overkill for "send this in 4 hours".
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

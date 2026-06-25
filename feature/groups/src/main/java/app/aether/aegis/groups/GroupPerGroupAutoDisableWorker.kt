package app.aether.aegis.groups

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Per-group dead-man's switch (the per-group toggle).
 *
 * Mirrors [GroupModuleAutoDisableWorker] but at the per-group
 * granularity: when the group's configured inactivity window
 * elapses without a GroupChatScreen visit, flip the group's
 * `enabled` flag to false. The MODULE stays on (the family
 * group keeps working); only the timed-out group goes quiet.
 *
 * Same lifecycle pattern: cancel on group-chat entry, schedule
 * on group-chat exit. One unique-work slot per group, keyed by
 * group id.
 */
class GroupPerGroupAutoDisableWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val groupId = inputData.getString(KEY_GROUP_ID) ?: return Result.success()
        return runCatching {
            // Cross-module boundary: workers go through the host
            // interface instead of touching Repository directly.
            // No-op if the host hasn't installed yet (cold-start
            // race) — next group exit will reschedule.
            val host = GroupModuleHostHolder.current ?: run {
                Log.w(TAG, "host not installed, skipping auto-disable for $groupId")
                return@runCatching Result.success()
            }
            val enabled = host.isGroupEnabled(groupId)
                ?: return@runCatching Result.success()
            if (!enabled) {
                // Already off — manual disable beat us to it.
                return@runCatching Result.success()
            }
            if (host.groupAutoDisableMinutes(groupId) == null) {
                // User disabled the per-group timer while we were
                // pending. Drop silently.
                return@runCatching Result.success()
            }
            Log.i(TAG, "auto-disable group $groupId — inactivity window elapsed")
            host.setGroupEnabled(groupId, false)
            Result.success()
        }.getOrElse {
            Log.w(TAG, "per-group auto-disable failed for $groupId", it)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "GroupPerGroupAutoDisable"
        private const val KEY_GROUP_ID = "groupId"
        private fun uniqueName(groupId: String): String =
            "aegis-group-auto-disable-$groupId"

        fun schedule(context: Context, groupId: String, minutes: Int) {
            val safe = minutes.coerceAtLeast(1)
            val request = OneTimeWorkRequestBuilder<GroupPerGroupAutoDisableWorker>()
                .setInitialDelay(safe.toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_GROUP_ID to groupId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(groupId),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context, groupId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(groupId))
        }
    }
}

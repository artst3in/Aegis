package app.aether.aegis.groups

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Dead man's switch for the group-chat attack surface — the
 * group-module auto-disable timer.
 *
 * Lifecycle:
 *   - User opens Groups tab → [cancel] called via the
 *     ChatListScreen DisposableEffect
 *   - User exits Groups tab → [schedule] called with the
 *     configured inactivity window (default 30 min)
 *   - Window elapses without re-entry → [doWork] runs and flips
 *     `GroupModulePrefs.enabled = false`
 *   - Re-entry before the window closes → [cancel] runs,
 *     pending job dropped, next exit reschedules from zero
 *
 * Behaviour rule: on reboot the module STAYS in its last explicit
 * state (on if user left it on). The timer is canceled by the
 * BootReceiver to avoid an unexpected disable during continued
 * use. See [cancel] for the boot-receiver path.
 *
 * The worker is intentionally NOT periodic — it's a single-shot
 * with an initial delay equal to the user's configured window.
 * Two reasons:
 *   1. Periodic WorkManager has a 15-minute floor; the default
 *      is 30 min but the user can pick lower in the
 *      configure dialog.
 *   2. The "user re-entered the tab, reset the clock" UX needs
 *      explicit cancellation; a long-running periodic check
 *      would either miss the reset or fire prematurely.
 */
class GroupModuleAutoDisableWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            // Re-check the gate at firing time. The user may have
            // disabled the module manually in the meantime
            // (frictionless toggle-off elsewhere); we must not
            // overwrite an explicit on-state with a stale off.
            val prefs = GroupModulePrefs(applicationContext)
            if (!prefs.enabled) {
                Log.i(TAG, "auto-disable: module already off, no-op")
                return@runCatching Result.success()
            }
            if (!prefs.autoDisableEnabled) {
                Log.i(TAG, "auto-disable: feature toggled off, no-op")
                return@runCatching Result.success()
            }
            Log.i(TAG, "auto-disable: inactivity window elapsed, disabling module")
            prefs.enabled = false
            Result.success()
        }.getOrElse {
            Log.w(TAG, "auto-disable worker failed", it)
            // Retry would risk flipping the gate at an unexpected
            // moment after the user re-engaged. Better to drop
            // the job and let the next tab-exit reschedule.
            Result.success()
        }
    }

    companion object {
        private const val TAG = "GroupAutoDisable"
        private const val UNIQUE_NAME = "aegis-group-auto-disable"

        /** Arm the auto-disable for [minutes] minutes from now.
         *  Replaces any previously-pending job (REPLACE policy)
         *  so callers don't have to cancel first. */
        fun schedule(context: Context, minutes: Int) {
            val safe = minutes.coerceAtLeast(1)
            val request = OneTimeWorkRequestBuilder<GroupModuleAutoDisableWorker>()
                .setInitialDelay(safe.toLong(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** Cancel any pending auto-disable. Called on Groups tab
         *  entry (the user is actively engaged) and on boot (the
         *  module stays in its last explicit state across reboots,
         *  so a pending pre-reboot timer would be surprising). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}

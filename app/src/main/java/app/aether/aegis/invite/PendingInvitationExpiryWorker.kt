package app.aether.aegis.invite

import app.aether.aegis.AegisApp
import app.aether.aegis.prefs.InvitationExpiryPrefs
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic sweep that revokes pending 1:1 invitation links older than
 * the configured expiry window.
 *
 * Runs hourly; the cutoff is read fresh each run so a settings change
 * takes effect on the next tick. "Never" (0 h) is a no-op. Only UNUSED
 * links are here to revoke — a link that already connected was dropped
 * from `pending_invitations` at connect time, so the
 * "used during the countdown" edge case is handled for free: a
 * connected link is no longer pending and won't be touched.
 */
class PendingInvitationExpiryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val hours = InvitationExpiryPrefs(applicationContext).expiryHours
        if (hours <= 0) return@runCatching Result.success() // "Never"

        val cutoff = System.currentTimeMillis() - hours * 3_600_000L
        val repo = AegisApp.instance.repository
        val transport = AegisApp.instance.transports
            .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
            .firstOrNull()

        val expired = repo.allPendingInvitations().filter { it.createdAt < cutoff }
        for (inv in expired) {
            // revokePendingInvitation tears down the relay side AND
            // drops the local row. Fall back to a local clear if the
            // transport is down so an expired row can't get stuck.
            if (transport != null) transport.revokePendingInvitation(inv.connId)
            else repo.removePendingInvitation(inv.connId)
        }
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        private const val UNIQUE_NAME = "aegis-invitation-expiry"

        /** Enqueue the hourly sweep. KEEP so an existing schedule
         *  survives app restarts (matches CanaryWorker/UpdateCheck). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PendingInvitationExpiryWorker>(
                1, TimeUnit.HOURS,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

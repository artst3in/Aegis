package app.aether.aegis.canary

import app.aether.aegis.AegisApp
import app.aether.aegis.core.MessageType
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic check on the canary store — if the user hasn't foregrounded
 * the app in [CanaryStore.intervalMs], fire the pre-authored message
 * to every paired peer. Idempotent via the alreadyFired flag so the
 * worker can run frequently without double-sending.
 *
 * Schedule cadence: every 30 min. The actual fire threshold is
 * configured by the user (12 / 24 / 48 h), so 30 min granularity is
 * fine — at worst the canary fires up to half an hour after the
 * threshold is technically crossed.
 */
class CanaryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val store = CanaryStore(applicationContext)
            if (!store.isOverdue()) return@runCatching Result.success()

            // Canary is the delayed-sos equivalent: "I'm gone if I
            // don't check in". Recipients are Trusted ∪ Emergency
            // (the sos resolution set) — Untrusted
            // never gets the message. Sent as plain TEXT so it lands
            // in the recipient's inbox like a real chat.
            val peers = AegisApp.instance.repository.sosTargets()
            if (peers.isEmpty()) return@runCatching Result.success()
            val body = store.message
            for (peer in peers) {
                runCatching {
                    AegisApp.instance.protocolManager.sendMessage(
                        to = peer.publicKey,
                        content = body,
                        type = MessageType.TEXT,
                    )
                }
            }
            // The dead-man's switch actually fired to your contacts →
            // earn the Dead Man badge.
            app.aether.aegis.achievements.Achievements.unlock(
                app.aether.aegis.achievements.Achievement.DEAD_MAN,
            )
            store.alreadyFired = true
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val UNIQUE_NAME = "aegis-canary-check"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<CanaryWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}

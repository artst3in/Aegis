package app.aether.aegis.remote

import app.aether.aegis.AegisApp
import app.aether.aegis.core.MessageType
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Target-side periodic streamer. Once a remote-access session opens
 * via [RemoteAccessHandler.handleAuth], this kicks off a coroutine
 * that emits [RemoteAccessProtocol.KIND_WATCH_TICK] packets every
 * [TICK_INTERVAL_MS] — each carrying a fresh GPS fix, fresh front +
 * rear mugshots, and the current lock-success flag.
 *
 * The sender's UI (ContactDetailScreen.RemoteModeActive) replaces
 * its `session.locateLat / locateLng / mugshotB64 / rearMugshotB64`
 * each tick, so the owner sees a live-ish trail of where the device
 * is and what cameras can see — without having to spam Locate.
 *
 * Auto-stops on:
 *   - session expiry (validateSession returns null)
 *   - explicit [stop] (sender hits Exit remote mode, peer revokes)
 *   - process death (scope tied to SupervisorJob, GC'd with object)
 *
 * Battery: TICK_INTERVAL_MS is 25 s (under the LOCATE rate-limit
 * cooldown of 30 s, but watch ticks bypass the limit since they're
 * gated by session validity, not per-command). Each tick is ~2 GPS
 * acquisitions + 2 camera captures = ~5 s of radio + camera work,
 * so duty cycle is ~20 %. Acceptable on a presumed-stolen device
 * the owner is actively tracking; would not be acceptable as
 * background telemetry.
 */
object RemoteWatchMode {

    private const val TAG = "RemoteWatch"
    private const val TICK_INTERVAL_MS = 25_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Start streaming for [fromKey]. Idempotent — re-starting an
     *  already-watched peer cancels the prior job first. */
    fun start(fromKey: String) {
        stop(fromKey)
        jobs[fromKey] = scope.launch {
            // First tick fires IMMEDIATELY — AUTH_OK no longer carries
            // location or mugshot (split out for sub-second login
            // latency), so the sender's UI needs the first watch
            // payload right away or they sit looking at empty
            // panels for 25 s. Subsequent ticks honour the normal
            // interval.
            while (isActive) {
                val gate = AegisApp.instance.remoteAccessGate
                // Watch only as long as the underlying session is
                // alive. We don't know the sid here — peer revocation
                // and TTL expiry both end up making
                // `sessionPeerFor(fromKey)` return null.
                if (gate.peerHasActiveSession(fromKey).not()) {
                    Log.i(TAG, "watch stopping — no session for $fromKey")
                    break
                }
                runCatching { tick(fromKey) }
                    .onFailure { Log.w(TAG, "tick failed for $fromKey", it) }
                delay(TICK_INTERVAL_MS)
            }
            jobs.remove(fromKey)
        }
    }

    fun stop(fromKey: String) {
        jobs.remove(fromKey)?.cancel()
    }

    fun isWatching(fromKey: String): Boolean = jobs[fromKey]?.isActive == true

    private suspend fun tick(fromKey: String) {
        val (lat, lng) = RemoteCommandHandler.fireLocate()
        val (frontB64, rearB64) = RemoteCommandHandler.captureBothMugshotsB64()
        val pb = runCatching {
            AegisApp.instance.powerBudget.apply { refresh() }
        }.getOrNull()
        val packet = RemoteAccessProtocol.Packet(
            kind = RemoteAccessProtocol.KIND_WATCH_TICK,
            lat = lat,
            lng = lng,
            ts = System.currentTimeMillis(),
            mugshotB64 = frontB64,
            rearMugshotB64 = rearB64,
            lockOk = app.aether.aegis.admin.AdminGate.isActive(AegisApp.instance),
            batteryPct = pb?.level?.value,
            charging = pb?.charging?.value,
        )
        runCatching {
            AegisApp.instance.protocolManager.sendMessage(
                to = fromKey,
                content = RemoteAccessProtocol.encode(packet),
                type = MessageType.STATUS,
            )
        }.onFailure { Log.w(TAG, "tick send failed for $fromKey", it) }
    }
}

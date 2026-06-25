package app.aether.aegis.sentinel

import android.content.Context
import android.util.Base64
import android.util.Log
import app.aether.aegis.AegisApp
import app.aether.aegis.core.MessageType
import app.aether.aegis.remote.RemoteAccessProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Send-side of the Sentinel notification path.
 *
 * Fires when the engine's watermark crosses to a new high stage AND
 * the throttle setting permits a ping. Composes a
 * [RemoteAccessProtocol.KIND_SENTINEL_EVENT] packet, optionally
 * attaches the 3D-model accel recording, and fans it out to every
 * peer pubkey on [SentinelPrefs.notifyList].
 *
 * Notifications are sent over the regular STATUS transport — same
 * channel as PTT pings, sos broadcasts, and remote-access auth
 * traffic — so they piggyback on Aegis's existing delivery
 * guarantees (queued on send, retried on reconnect, signed,
 * end-to-end-encrypted by SimpleX).
 *
 * No alarm, ever. The receiving Aegis surfaces these as a normal
 * silent notification + an entry on the future inbound-sentinel-log
 * UI. The sending Aegis writes a [SensorId.NOTIFY] row to its own
 * local event log so the user can audit which notifications fired.
 */
object SentinelNotifier {

    private const val TAG = "SentinelNotifier"
    private const val MAX_ATTACH_BYTES = 600_000  // SimpleX-friendly cap

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Emit a sentinel notification for the cascade stage just reached.
     *
     * [recording] is the most-recent finalised recording file (or
     * null if none — e.g. the cascade only reached PROXIMITY_ARMED
     * and no recording was made). Only attached when
     * [SentinelPrefs.attachRecording] is on AND the file is under
     * [MAX_ATTACH_BYTES].
     */
    fun fire(
        context: Context,
        stage: SentinelStage,
        recording: File?,
        mugshot: File? = null,
        isDrill: Boolean = false,
    ) {
        val app = AegisApp.instance
        val prefs = SentinelPrefs(context)
        val recipients = prefs.notifyList
        if (recipients.isEmpty()) {
            Log.i(TAG, "watermark raised to ${stage.label} but notify-list empty — log only")
            return
        }
        if (prefs.throttle == SentinelThrottle.NEVER) {
            Log.i(TAG, "throttle = NEVER — silent log only")
            return
        }
        val ts = System.currentTimeMillis()
        // Battery snapshot — sender-side telemetry (same fields as the
        // PING/PONG path, so the recipient's UI can show "Aegis on
        // bedside, 64% battery, ${stage}").
        val battSnap = runCatching {
            val pb = app.powerBudget
            pb.refresh()
            pb.level.value to pb.charging.value
        }.getOrElse { null to null }
        val attach = recording?.takeIf { prefs.attachRecording && it.length() in 1..MAX_ATTACH_BYTES }
        val b64 = attach?.let {
            runCatching {
                Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
            }.getOrNull()
        }
        // Front-camera mugshot from the RECORDING-stage burst.
        // Attached when present; cap at the same SimpleX
        // size limit. Reuses the existing mugshotB64 field on the
        // protocol — the recipient already knows how to render that.
        val mugB64 = mugshot?.takeIf { it.length() in 1..MAX_ATTACH_BYTES }?.let {
            runCatching {
                Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
            }.getOrNull()
        }
        // Drill messages are prefixed so the receiver can route them
        // to the "tap to confirm" affordance instead of the regular
        // alert flow. Same protocol kind, different semantic class.
        val msgWithDrillTag = if (isDrill) "[DRILL] ${stage.label}" else stage.label
        val packet = RemoteAccessProtocol.Packet(
            kind = RemoteAccessProtocol.KIND_SENTINEL_EVENT,
            ts = ts,
            msg = msgWithDrillTag,
            batteryPct = battSnap.first,
            charging = battSnap.second,
            audioB64 = b64,
            mugshotB64 = mugB64,
        )
        val wire = RemoteAccessProtocol.encode(packet)
        scope.launch {
            recipients.forEach { peerKey ->
                runCatching {
                    app.protocolManager.sendMessage(
                        to = peerKey,
                        content = wire,
                        type = MessageType.STATUS,
                    )
                    Log.i(TAG, "sentinel event sent → ${peerKey.take(16)}… stage=${stage.label}")
                }.onFailure {
                    Log.w(TAG, "sentinel send failed for ${peerKey.take(16)}…", it)
                }
            }
        }
        // Local audit row so the user can see "yes, a ping went out at
        // 14:32" without diffing the SimpleX outbound queue.
        runCatching {
            SentinelEventLog(context).append(
                timestampMs = ts,
                stage = stage,
                sensor = SensorId.NOTIFY,
                magnitude = recipients.size,
            )
        }
    }
}

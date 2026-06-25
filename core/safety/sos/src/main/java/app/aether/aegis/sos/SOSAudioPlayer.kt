package app.aether.aegis.sos

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Auto-plays inbound `[aegis:sos-audio]` chunks on the receiver's
 * speakerphone the moment they arrive (so everyone hears the
 * victim's audio).
 *
 * The receiver does NOT need to open Aegis, accept a call, or even
 * unlock the phone — clips play through whatever output the system
 * routes them to, with the alarm stream targeting MAX so the audio
 * lands loud even when ringer is silenced. Bob's phone "screams"
 * because Alice's voice is on his speaker, not because Aegis is
 * blasting a synthesised alarm tone.
 *
 * The audio is ungated by response status: every receiver
 * hears Alice regardless of whether they tapped "I'M RESPONDING."
 * Only [SOSAlertStore.isMuted] (set by hold-to-mute) can silence
 * it on a given device — and even then, the visual evidence keeps
 * flowing.
 *
 * Queue model: clips are FIFO and play one at a time. If a new clip
 * arrives mid-playback we append; on completion we pull the next
 * one off the deque. There is intentionally no de-duplication: each
 * 60 s rotation produces a distinct file and missing one would
 * leak silence into the evidence trail.
 */
object SOSAudioPlayer {

    private const val TAG = "SOSAudioPlayer"

    private val queue = ConcurrentLinkedDeque<Pair<String, String>>()  // victimKey, absPath

    @Volatile private var current: MediaPlayer? = null
    @Volatile private var currentVictim: String? = null

    /**
     * Hand the player a freshly-received sos-audio file. No-op if
     * this peer's sos isn't currently active in SOSAlertStore
     * (we never auto-play OLD soss) or if the receiver muted the
     * stream via hold-to-mute.
     */
    fun enqueue(context: Context, victimKey: String, absPath: String) {
        if (!SOSAlertStore.isActive(victimKey)) {
            // Log.d(TAG, "drop clip — sos not active for $victimKey")
            return
        }
        if (SOSAlertStore.isMuted(victimKey)) {
            // Log.d(TAG, "drop clip — muted for $victimKey")
            return
        }
        queue.add(victimKey to absPath)
        // Kick the player only if it's idle. If something is already
        // playing the onCompletion hook will drain the queue.
        if (current == null) drain(context)
    }

    /**
     * Stop any in-flight playback and clear the queue for [victimKey].
     * Called when the user mutes mid-clip (cuts the audio
     * immediately) or when the sos ends. Other victims' queues are
     * untouched — overlapping soss, however rare, should not
     * silence each other.
     */
    @Synchronized
    fun stopFor(victimKey: String) {
        queue.removeAll { it.first == victimKey }
        if (currentVictim == victimKey) {
            val cur = current
            current = null
            currentVictim = null
            cur?.let {
                runCatching { if (it.isPlaying) it.stop() }
                runCatching { it.release() }
            }
            restoreAudioMode()
        }
    }

    @Synchronized
    private fun drain(context: Context) {
        if (current != null) return
        val next = queue.pollFirst() ?: run {
            restoreAudioMode()
            return
        }
        val (victim, path) = next
        // Drop late items for muted peers — the user may have
        // engaged hold-to-mute after we enqueued.
        if (SOSAlertStore.isMuted(victim)) {
            drain(context)
            return
        }
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.mode = AudioManager.MODE_NORMAL
            am?.isSpeakerphoneOn = true
            // Use STREAM_ALARM so the volume level is decoupled from
            // the ringer/silent slider. A phone in "do not disturb"
            // can still alarm at full volume — sos deliberately
            // bypasses the user's quiet settings.
            am?.let {
                val max = it.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                it.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
            val player = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_ALARM)
                setDataSource(path)
                prepare()
                setOnCompletionListener { mp ->
                    runCatching { mp.release() }
                    if (current === mp) {
                        current = null
                        currentVictim = null
                    }
                    drain(context)
                }
                setOnErrorListener { mp, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    runCatching { mp.release() }
                    if (current === mp) {
                        current = null
                        currentVictim = null
                    }
                    drain(context)
                    true
                }
                start()
            }
            current = player
            currentVictim = victim
        }.onFailure {
            Log.w(TAG, "playback failed", it)
            current = null
            currentVictim = null
            // Keep draining — a bad file shouldn't lock the queue.
            drain(context)
        }
    }

    private fun restoreAudioMode() {
        runCatching {
            // This path (stopFor → restoreAudioMode, and the empty-queue
            // branch of drain) has no Context in scope, and the sos
            // module has no compile-time edge back to AegisApp. Pull the
            // app context through the module host instead. No-op if the
            // host isn't installed yet (process not fully up / unit
            // harness) — leaving the speaker route as-is is harmless.
            val ctx = SOSModuleHostHolder.current?.appContext ?: return@runCatching
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isSpeakerphoneOn = false
        }
    }
}

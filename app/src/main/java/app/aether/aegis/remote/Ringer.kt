package app.aether.aegis.remote

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Light "find my phone" ringer — distinct from the sos SIREN.
 *
 * SIREN is a CDMA emergency wail at max alarm volume that bypasses
 * DnD; it's for the "I'm being attacked" scenario. RING is for "I
 * lost my phone in the couch": loop the system default notification
 * tone at a normal alarm volume, no DnD override. Stops on
 * [stop], on the per-call timeout, or when the session closes.
 *
 * Uses [android.media.Ringtone] rather than MediaPlayer because the
 * built-in Ringtone respects the system "notification" sound the
 * user already picked — sounds familiar instead of jarring.
 */
object Ringer {

    private const val TAG = "Ringer"
    private const val DEFAULT_SECONDS = 30

    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    @Volatile private var ringtone: android.media.Ringtone? = null
    @Volatile private var stopAt: Long = 0L

    /**
     * Start ringing for [seconds] (clamped 5-120). Idempotent — a
     * second start while already ringing just extends the timeout
     * to the new seconds value.
     */
    fun start(ctx: Context, seconds: Int?) {
        val dur = (seconds ?: DEFAULT_SECONDS).coerceIn(5, 120)
        stopAt = System.currentTimeMillis() + dur * 1000L
        if (active.get()) {
            Log.i(TAG, "extend ringer to ${dur}s")
            armWatchdog(dur)
            return
        }
        main.post {
            runCatching {
                val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val rt = RingtoneManager.getRingtone(ctx.applicationContext, uri)
                    ?: return@runCatching
                rt.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                rt.isLooping = true
                ringtone = rt
                rt.play()
                active.set(true)
                Log.i(TAG, "ring start ${dur}s")
                armWatchdog(dur)
            }.onFailure {
                Log.w(TAG, "ring start failed", it)
                active.set(false)
                ringtone = null
            }
        }
    }

    fun stop() {
        if (!active.getAndSet(false)) return
        main.post {
            runCatching { ringtone?.stop() }
            ringtone = null
            Log.i(TAG, "ring stop")
        }
    }

    fun isRinging(): Boolean = active.get()

    private fun armWatchdog(seconds: Int) {
        main.postDelayed({
            if (System.currentTimeMillis() >= stopAt) stop()
        }, seconds * 1000L + 100L)
    }
}

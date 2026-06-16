package app.aether.aegis.sos

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Loud audible alarm for sos situations.
 *
 *  - Routed through STREAM_ALARM so the device's silent-mode + Do-Not-
 *    Disturb policy doesn't silence it (Android explicitly exempts the
 *    alarm stream from these on every vendor's policy).
 *  - Pegs the alarm-stream volume to its max, snapshotting the prior
 *    value so stop() restores it.
 *  - Loops a CDMA emergency-call tone on a coroutine so we don't have to
 *    ship a WAV asset; ToneGenerator is part of the platform.
 *  - Single global instance — the siren is either on or off, never
 *    multiple concurrent loops.
 *
 * The SOS UI is the only allowed entry point. Stopping the siren is
 * gated by a biometric prompt at the call site (per GUI_SPEC: "cannot
 * be stopped by thief").
 */
object SirenManager {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loopJob: Job? = null
    private var generator: ToneGenerator? = null
    private var savedAlarmVolume: Int? = null

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    fun start(context: Context) {
        if (_on.value) return
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE)
            as? AudioManager ?: return
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        savedAlarmVolume = runCatching {
            am.getStreamVolume(AudioManager.STREAM_ALARM)
        }.getOrNull()
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0) }

        val tg = runCatching {
            ToneGenerator(AudioManager.STREAM_ALARM, 100)
        }.getOrNull() ?: return
        generator = tg
        _on.value = true
        loopJob = scope.launch {
            // CDMA emergency tone is the loudest, most-distinct of the
            // built-in ToneGenerator presets and is unambiguous as an
            // alarm rather than a notification ding. 800 ms pulse + 100 ms
            // gap → near-continuous wail without one long blocking call.
            while (isActive) {
                tg.startTone(ToneGenerator.TONE_CDMA_HIGH_SS_2, 800)
                delay(900)
            }
        }
    }

    fun stop(context: Context) {
        if (!_on.value) return
        loopJob?.cancel()
        loopJob = null
        runCatching {
            generator?.stopTone()
            generator?.release()
        }
        generator = null
        // Restore the user's alarm volume so we don't permanently leave
        // their device pinned at max-loud after a siren run.
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE)
            as? AudioManager
        val prior = savedAlarmVolume
        if (am != null && prior != null) {
            runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, prior, 0) }
        }
        savedAlarmVolume = null
        _on.value = false
    }
}

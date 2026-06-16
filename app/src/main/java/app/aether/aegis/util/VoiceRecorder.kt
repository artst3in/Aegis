package app.aether.aegis.util

import app.aether.aegis.AegisApp
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

/**
 * Press-and-hold voice recorder. Records to a file inside
 * Attachments.attachmentsDir() (= filesDir/app_files) so the resulting
 * Attachments.Local is sendable through the existing attachment path.
 *
 * Encoder choice: AAC-LC inside MPEG_4 container. SimpleX-chat encodes
 * voice as AAC too (per upstream RecorderInterface). 16 kHz mono is
 * enough fidelity for speech while keeping files tiny.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startedAtMs: Long = 0L

    init { liveInstances.add(this) }

    fun start(): Boolean {
        if (recorder != null) return false
        val target = File(
            Attachments.attachmentsDir(context),
            "voice-${UUID.randomUUID()}.m4a",
        )
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        return runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            currentFile = target
            startedAtMs = System.currentTimeMillis()
            true
        }.getOrElse {
            runCatching { r.release() }
            target.delete()
            false
        }
    }

    /**
     * Stops the recorder. Returns the recorded Local if there was a
     * file with at least [minDurationMs] of audio (defaults to 300 ms
     * — anything shorter is an accidental tap); null otherwise.
     */
    fun stop(minDurationMs: Long = 300L): Attachments.Local? {
        val r = recorder ?: return null
        val durationMs = System.currentTimeMillis() - startedAtMs
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        val f = currentFile ?: return null
        currentFile = null
        if (durationMs < minDurationMs || !f.exists() || f.length() == 0L) {
            f.delete()
            return null
        }
        return Attachments.Local(
            path = f.absolutePath,
            mime = "audio/mp4",
            size = f.length(),
            name = f.name,
        )
    }

    fun cancel() {
        val r = recorder
        recorder = null
        runCatching { r?.stop() }
        runCatching { r?.release() }
        currentFile?.delete()
        currentFile = null
    }

    companion object {
        // Weak registry so the call path can yank the mic away from any
        // live VoiceRecorder before getUserMedia runs. Without this, a
        // recorder that wasn't cleanly released (e.g. user navigated to
        // CallScreen while a chat-bar mic gesture was active) keeps
        // the AudioRecord open and WebRTC fails with "couldn't start
        // audio source".
        private val liveInstances =
            java.util.Collections.newSetFromMap(
                java.util.WeakHashMap<VoiceRecorder, Boolean>()
            )

        /** Evict every live recorder. Called from CallManager before
         *  outgoing/incoming call setup. */
        fun cancelAll() {
            liveInstances.toList().forEach { it.cancel() }
        }
    }
}

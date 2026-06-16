package app.aether.aegis.sentinel

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-event accelerometer + gyroscope sample recorder — the "3D model
 * of the grab" forensic file. One file per RECORDING session.
 *
 * Format:
 *
 *   [u64 start_ts][u16 sample_count][i16 ax][i16 ay][i16 az][i16 gx][i16 gy][i16 gz]…
 *
 * Each sample is 6 × i16 = 12 bytes. At the confirmed 100 Hz
 * rate that's 1.2 KB/s. A 30-second grab = 36 KB. Comfortably under
 * the 600 KB SimpleX size cap for the notification attachment.
 *
 * Sample values are scaled fixed-point: float m/s² × 1000 fits in
 * int16 for any plausible grab acceleration (max ±32.7 m/s² ≈ ±3.3 g).
 * Gyroscope: float rad/s × 1000, same range.
 *
 * File naming: `recordings/<ts>.bin`. Pruning follows the same
 * Mugshot pattern — oldest files removed when [MAX_TOTAL_BYTES] is
 * exceeded.
 */
class SentinelRecording(context: Context) {

    private val dir: File = File(
        context.applicationContext.filesDir,
        "sentinel/recordings",
    ).apply { mkdirs() }

    private val sampleCount = AtomicInteger(0)
    @Volatile private var currentFile: File? = null
    @Volatile private var raf: RandomAccessFile? = null
    @Volatile private var startTimestampMs: Long = 0L

    /** Begin a new recording session. [startTs] is written to the
     *  file header; samples appended after. Idempotent — call to
     *  [stop] is required before a new [start]. */
    @Synchronized
    fun start(startTs: Long) {
        if (raf != null) return
        val f = File(dir, "$startTs.bin")
        try {
            val r = RandomAccessFile(f, "rw")
            // Pre-write header: u64 ts + u16 count. Count gets
            // backfilled in [stop] so streaming writes don't have to
            // seek back on every sample.
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                putLong(startTs)
                putShort(0)
            }.array()
            r.write(header)
            raf = r
            currentFile = f
            startTimestampMs = startTs
            sampleCount.set(0)
            Log.i(TAG, "recording started → ${f.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "recording start failed", t)
        }
    }

    /** Append one combined accel+gyro sample. Values in m/s² (accel)
     *  and rad/s (gyro). If gyro isn't available the engine passes 0s
     *  — file still parses cleanly. */
    fun append(ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) {
        val r = raf ?: return
        val buf = ByteBuffer.allocate(SAMPLE_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(quantise(ax))
            putShort(quantise(ay))
            putShort(quantise(az))
            putShort(quantise(gx))
            putShort(quantise(gy))
            putShort(quantise(gz))
        }.array()
        try {
            synchronized(this) {
                r.write(buf)
                sampleCount.incrementAndGet()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sample append failed — closing recording", t)
            stop()
        }
    }

    /** Finalise the current file: backfill the u16 sample count and
     *  close. Returns the file (now durable on disk) so the
     *  notification path can attach it to the SimpleX message. */
    @Synchronized
    fun stop(): File? {
        val r = raf ?: return null
        val f = currentFile
        val count = sampleCount.get().coerceIn(0, UShort.MAX_VALUE.toInt())
        try {
            // Seek back to count slot at offset 8 (after the u64 ts).
            r.seek(8)
            r.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(count.toShort()).array())
            r.close()
            Log.i(TAG, "recording stopped: ${f?.name} ($count samples, ${f?.length()} bytes)")
            // Best-effort retention sweep.
            prune()
        } catch (t: Throwable) {
            Log.w(TAG, "recording stop failed", t)
        } finally {
            raf = null
            currentFile = null
            startTimestampMs = 0L
        }
        return f
    }

    fun isRecording(): Boolean = raf != null
    fun currentSampleCount(): Int = sampleCount.get()

    /** List recordings on disk, newest-first. */
    fun list(): List<File> =
        dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Read a recording back into samples. Used by the (future)
     *  forensic playback UI to draw the X/Y/Z sparkline triplet. */
    fun read(file: File): Recording? {
        if (!file.exists() || file.length() < HEADER_BYTES) return null
        return try {
            RandomAccessFile(file, "r").use { r ->
                val header = ByteArray(HEADER_BYTES)
                r.readFully(header)
                val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val ts = hb.long
                val expectCount = hb.short.toInt() and 0xFFFF
                val bodyLen = (r.length() - HEADER_BYTES).toInt()
                val actualCount = bodyLen / SAMPLE_BYTES
                val n = minOf(expectCount, actualCount)
                if (n <= 0) return@use Recording(ts, emptyList())
                val body = ByteArray(n * SAMPLE_BYTES)
                r.readFully(body)
                val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                Recording(ts, List(n) {
                    Sample(
                        ax = dequantise(bb.short),
                        ay = dequantise(bb.short),
                        az = dequantise(bb.short),
                        gx = dequantise(bb.short),
                        gy = dequantise(bb.short),
                        gz = dequantise(bb.short),
                    )
                })
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recording read failed: ${file.name}", t); null
        }
    }

    private fun prune() {
        val files = list().reversed() // oldest first for deletion
        var total = files.sumOf { it.length() }
        var i = 0
        while (total > MAX_TOTAL_BYTES && i < files.size) {
            val f = files[i++]
            total -= f.length()
            try { f.delete() } catch (_: Throwable) {}
        }
    }

    private fun quantise(v: Float): Short =
        (v * SCALE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private fun dequantise(s: Short): Float = s.toFloat() / SCALE

    data class Sample(
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float,
    )

    data class Recording(val startTsMs: Long, val samples: List<Sample>)

    companion object {
        private const val TAG = "SentinelRecording"
        private const val HEADER_BYTES = 10  // u64 ts + u16 count
        private const val SAMPLE_BYTES = 12  // 6 × i16
        private const val SCALE = 1000f      // fixed-point: 1 unit = 0.001 m/s² or rad/s
        /** 50 recordings × ~50 KB each = ~2.5 MB. Plenty of headroom
         *  on a modern phone; auto-prunes if exceeded. */
        private const val MAX_TOTAL_BYTES = 5L * 1024L * 1024L
    }
}

package app.aether.aegis.sentinel

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary forensic event log for Sentinel cascade activity.
 *
 * Format — fixed-width 14-byte rows:
 *
 *   [u64 timestamp_ms][u8 stage][u8 sensor_id][i16 magnitude][u16 duration_ms]
 *
 * Fixed-width is intentional: the log is scannable by direct offset
 * (no varint decoding), tail-readable, and trivially truncatable to
 * a row boundary when the retention cap kicks in. Accelerometer
 * recordings live in separate per-event files (see
 * [SentinelRecording]) — the log row carries only the event metadata,
 * not the sample stream.
 *
 * Retention follows the Mugshot pattern: oldest rows pruned when the
 * file exceeds [MAX_BYTES].
 *
 * Thread-safety: all writes go through a single synchronized append.
 * The engine writes from a coroutine; that's fine because RandomAccessFile
 * doesn't share state across processes and we're the only writer.
 */
class SentinelEventLog(context: Context) {

    private val file: File = File(
        context.applicationContext.filesDir,
        "sentinel/log.bin",
    ).apply { parentFile?.mkdirs() }

    /** Append a single event row. Cheap — one synchronised append +
     *  occasional truncation when the cap is breached. */
    fun append(
        timestampMs: Long,
        stage: SentinelStage,
        sensor: SensorId,
        magnitude: Int,        // scaled: sonar mag × 10000, accel mag × 100, etc.
        durationMs: Int = 0,
    ) {
        val row = ByteBuffer.allocate(ROW_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(timestampMs)
            put(stage.ordinal.toByte())
            put(sensor.ordinal.toByte())
            putShort(magnitude.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            putShort(durationMs.coerceIn(0, UShort.MAX_VALUE.toInt()).toShort())
        }.array()
        synchronized(this) {
            try {
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(raf.length())
                    raf.write(row)
                }
                if (file.length() > MAX_BYTES) prune()
            } catch (t: Throwable) {
                Log.w(TAG, "log append failed", t)
            }
        }
    }

    /** Read up to [limit] most-recent rows, oldest-first. */
    fun tail(limit: Int = 200): List<Row> = synchronized(this) {
        if (!file.exists() || file.length() < ROW_BYTES) return emptyList()
        val rows = (file.length() / ROW_BYTES).toInt()
        val take = limit.coerceAtMost(rows)
        val start = (rows - take).toLong() * ROW_BYTES
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(take * ROW_BYTES)
                raf.readFully(buf)
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                List(take) {
                    Row(
                        timestampMs = bb.long,
                        stage = SentinelStage.values().getOrElse(bb.get().toInt()) { SentinelStage.OFF },
                        sensor = SensorId.values().getOrElse(bb.get().toInt()) { SensorId.STAGE_TRANSITION },
                        magnitude = bb.short.toInt(),
                        durationMs = bb.short.toInt() and 0xFFFF,
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "log read failed", t); emptyList()
        }
    }

    fun clear() = synchronized(this) {
        try { file.delete() } catch (_: Throwable) {}
    }

    fun sizeBytes(): Long = if (file.exists()) file.length() else 0L

    private fun prune() {
        // Keep the second half; drop the oldest half. Cheap and
        // bounded. The Mugshot pattern uses count-based pruning; we
        // use byte-based since rows are fixed-width anyway.
        try {
            val keep = MAX_BYTES / 2
            val raf = RandomAccessFile(file, "rw")
            raf.use {
                val dropFrom = it.length() - keep
                it.seek(dropFrom)
                val buf = ByteArray(keep.toInt())
                it.readFully(buf)
                it.setLength(0)
                it.write(buf)
            }
            Log.i(TAG, "log pruned, kept ${file.length()} bytes")
        } catch (t: Throwable) {
            Log.w(TAG, "log prune failed", t)
        }
    }

    data class Row(
        val timestampMs: Long,
        val stage: SentinelStage,
        val sensor: SensorId,
        /** Scaled integer — units depend on the sensor (see [SensorId]). */
        val magnitude: Int,
        /** Milliseconds, 0 for instantaneous events. */
        val durationMs: Int,
    )

    companion object {
        private const val TAG = "SentinelEventLog"
        private const val ROW_BYTES = 14
        /** Soft cap. Two halves of 256 KB = 512 KB max on disk. At
         *  14 bytes per row that's ~36k events kept. */
        private const val MAX_BYTES = 512L * 1024L
    }
}

/** Sensor / event source for a log row. */
enum class SensorId {
    STAGE_TRANSITION, // not a sensor trip — recorded when state machine advances/retreats
    SONAR,            // ultrasonic engine detected motion
    PROXIMITY,        // IR near/far trip
    ACCEL,            // accelerometer above quiet threshold
    NOTIFY,           // notification fired to contacts (or would have)
}

package app.aether.aegis.sentinel

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Receiver-side inbox for inbound `KIND_SENTINEL_EVENT` packets.
 *
 * When a contact has us on their Sentinel notify-list and their
 * cascade fires, their Aegis fans out a SimpleX packet to ours. The
 * inbound handler in [app.aether.aegis.remote.RemoteAccessHandler]
 * decodes it and appends a row here, plus fires an Android
 * notification + writes any attached forensic blobs (3D-model
 * recording, mugshot) to disk for later playback.
 *
 * Format mirrors [SentinelEventLog] for consistency: fixed-width
 * binary rows, tail-readable by direct offset. One row per inbound
 * event, plus optional sidecar files for blobs.
 *
 *   row: [u64 timestamp_ms][u8 stage][u8 unread][u16 peerLen][peer bytes (max 256)]
 *        [u8 hasRecording][u8 hasMugshot][i16 batteryPct][u16 reserved]
 *
 * Variable-length peer key is awkward in fixed-row format; we cap
 * peer-key at 256 bytes and pad with zeros. Real SimpleX pubkeys are
 * ~64 chars hex so this fits comfortably.
 *
 * Sidecar blobs:
 *   recordings/<ts>.bin  — accel recording (raw bytes from sender)
 *   mugshots/<ts>.jpg    — mugshot JPEG
 */
class SentinelInbox(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "sentinel/inbox")
        .apply { mkdirs() }
    private val logFile = File(dir, "log.bin")
    private val recordingsDir = File(dir, "recordings").apply { mkdirs() }
    private val mugshotsDir = File(dir, "mugshots").apply { mkdirs() }

    // Process-wide unread count via the companion's flow so multiple
    // SentinelInbox instances (one per call site) share the same
    // reactive value. The instance fields used to be local and each
    // call site's UI was reading its own stale flow.
    val unreadCount: StateFlow<Int> = sharedUnreadCount

    init { refreshUnreadCount() }

    /** Append an inbound sentinel event to the inbox. Returns the
     *  timestamp used as the file-key for sidecar blobs. Caller
     *  writes recording/mugshot to that key if present. */
    fun append(
        timestampMs: Long,
        fromPeerKey: String,
        stage: String,
        batteryPct: Int?,
        recordingBytes: ByteArray? = null,
        mugshotBytes: ByteArray? = null,
        isDrill: Boolean = false,
    ): Long = synchronized(this) {
        val peerBytes = fromPeerKey.toByteArray(Charsets.UTF_8).take(256).toByteArray()
        // Status byte packs two flags:
        //   bit 0 = unread (default true on append)
        //   bit 1 = isDrill (true if this row originated from a drill)
        val statusByte = (if (isDrill) 0x02 else 0x00) or 0x01
        val row = ByteBuffer.allocate(ROW_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(timestampMs)
            put(stageOrdinal(stage).toByte())
            put(statusByte.toByte())
            putShort(peerBytes.size.toShort())
            // Pad peer to PEER_FIELD_BYTES (256) zeros.
            put(peerBytes)
            put(ByteArray(PEER_FIELD_BYTES - peerBytes.size))
            put(if (recordingBytes != null) 1.toByte() else 0)
            put(if (mugshotBytes != null) 1.toByte() else 0)
            putShort((batteryPct ?: -1).toShort())
            putShort(0)  // reserved
        }.array()
        try {
            RandomAccessFile(logFile, "rw").use {
                it.seek(it.length()); it.write(row)
            }
            if (recordingBytes != null) {
                File(recordingsDir, "$timestampMs.bin").writeBytes(recordingBytes)
            }
            if (mugshotBytes != null) {
                File(mugshotsDir, "$timestampMs.jpg").writeBytes(mugshotBytes)
            }
            refreshUnreadCount()
        } catch (t: Throwable) {
            Log.w(TAG, "inbox append failed", t)
        }
        timestampMs
    }

    /** Read all rows newest-first. Used by the inbox viewer UI. */
    fun tail(limit: Int = 200): List<Row> = synchronized(this) {
        if (!logFile.exists() || logFile.length() < ROW_BYTES) return emptyList()
        val totalRows = (logFile.length() / ROW_BYTES).toInt()
        val take = limit.coerceAtMost(totalRows)
        val start = (totalRows - take).toLong() * ROW_BYTES
        return try {
            RandomAccessFile(logFile, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(take * ROW_BYTES)
                raf.readFully(buf)
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                List(take) {
                    val ts = bb.long
                    val stage = bb.get().toInt()
                    val statusByte = bb.get().toInt()
                    val unread = (statusByte and 0x01) != 0
                    val isDrill = (statusByte and 0x02) != 0
                    val peerLen = (bb.short.toInt() and 0xFFFF).coerceIn(0, PEER_FIELD_BYTES)
                    val peerBytes = ByteArray(PEER_FIELD_BYTES).also { bb.get(it) }
                    val peer = String(peerBytes, 0, peerLen, Charsets.UTF_8)
                    val hasRec = bb.get().toInt() != 0
                    val hasMug = bb.get().toInt() != 0
                    val batt = bb.short.toInt()
                    bb.short  // reserved
                    Row(ts, peer, stageLabel(stage), unread, isDrill, hasRec, hasMug, batt.takeIf { it >= 0 })
                }.reversed()  // newest first
            }
        } catch (t: Throwable) {
            Log.w(TAG, "inbox read failed", t); emptyList()
        }
    }

    fun recordingFor(ts: Long): File? =
        File(recordingsDir, "$ts.bin").takeIf { it.exists() }
    fun mugshotFor(ts: Long): File? =
        File(mugshotsDir, "$ts.jpg").takeIf { it.exists() }

    /** Send a [KIND_SENTINEL_DRILL_ACK] back to the peer who fired
     *  the drill event. Called when the user taps "Confirm receipt"
     *  on a drill row in the inbox UI. Fire-and-forget via the
     *  process-wide IO scope; the UI doesn't wait for delivery. */
    fun sendDrillAck(toPeerKey: String) {
        val packet = app.aether.aegis.remote.RemoteAccessProtocol.Packet(
            kind = app.aether.aegis.remote.RemoteAccessProtocol.KIND_SENTINEL_DRILL_ACK,
            ts = System.currentTimeMillis(),
        )
        val wire = app.aether.aegis.remote.RemoteAccessProtocol.encode(packet)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                app.aether.aegis.AegisApp.instance.protocolManager.sendMessage(
                    to = toPeerKey,
                    content = wire,
                    type = app.aether.aegis.core.MessageType.STATUS,
                )
            }.onFailure { Log.w(TAG, "drill-ack send failed", it) }
        }
    }

    /** Mark all events as read — wipes the unread flag in every row
     *  by rewriting the file. O(n) but n is small (capped at MAX). */
    fun markAllRead(): Unit = synchronized(this) {
        if (!logFile.exists() || logFile.length() < ROW_BYTES) return@synchronized
        try {
            RandomAccessFile(logFile, "rw").use { raf ->
                val totalRows = (raf.length() / ROW_BYTES).toInt()
                for (i in 0 until totalRows) {
                    // Status byte at offset 9. Preserve the drill bit
                    // (0x02), clear only the unread bit (0x01).
                    raf.seek(i * ROW_BYTES + 9L)
                    val cur = raf.read()
                    raf.seek(i * ROW_BYTES + 9L)
                    raf.write(cur and 0x02)
                }
            }
            refreshUnreadCount()
        } catch (t: Throwable) {
            Log.w(TAG, "markAllRead failed", t)
        }
    }

    private fun refreshUnreadCount() {
        sharedUnreadCount.value = tail(MAX_INBOX_ROWS).count { it.unread }
    }

    data class Row(
        val timestampMs: Long,
        val fromPeerKey: String,
        val stage: String,
        val unread: Boolean,
        val isDrill: Boolean,
        val hasRecording: Boolean,
        val hasMugshot: Boolean,
        val batteryPct: Int?,
    )

    private fun stageOrdinal(label: String) = when (label) {
        "sonar-armed" -> 2
        "proximity-armed" -> 3
        "recording" -> 4
        else -> 0
    }
    private fun stageLabel(ordinal: Int) = when (ordinal) {
        2 -> "sonar-armed"; 3 -> "proximity-armed"; 4 -> "recording"; else -> "off"
    }

    companion object {
        private const val TAG = "SentinelInbox"
        private const val PEER_FIELD_BYTES = 256
        private const val ROW_BYTES = 8 + 1 + 1 + 2 + PEER_FIELD_BYTES + 1 + 1 + 2 + 2
        private const val MAX_INBOX_ROWS = 500
        /** Process-wide unread counter shared across all SentinelInbox
         *  instances. Any instance's append/markAllRead refreshes the
         *  same flow, so the UI badge stays correct regardless of
         *  which call site constructed the inbox handle. */
        private val sharedUnreadCount = MutableStateFlow(0)
    }
}

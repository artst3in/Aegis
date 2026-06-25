package app.aether.aegis.sos

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-sos evidence log (the "SOS Log").
 *
 * Writes a plain-text append-only trail to `filesDir/sos_logs/<id>.log`
 * during the victim's own sos — the file becomes the durable record
 * the user can hand to police if needed. Privacy carve-outs:
 *
 *   Recorded: alert-sent timestamp, contacts alerted count, who
 *             responded (name + time), who arrived (name + time),
 *             victim's own location samples during sos.
 *   NOT recorded: non-responder distances, non-responder locations,
 *             who was closest and didn't respond, responder routes,
 *             responder locations after sos end.
 *
 * Only the victim's device writes this file (SOSHandlerBridge gate
 * inside append). On non-victim devices [append] is a no-op so the
 * sos-coord broadcast path can call it freely.
 */
object SOSEvidenceLog {

    private const val TAG = "SOSEvidenceLog"
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)

    @Volatile private var current: File? = null
    private val lock = Any()

    /** Open a fresh log file for a new sos. Subsequent appends route
     *  to this file until [close] runs. */
    fun open(sosId: String) {
        synchronized(lock) {
            runCatching {
                // App context via the module host — the sos module has
                // no edge back to AegisApp. No host → no log (we can't be
                // the victim if the app isn't up).
                val ctx = SOSModuleHostHolder.current?.appContext ?: return@runCatching
                val dir = File(ctx.filesDir, "sos_logs").apply { mkdirs() }
                val f = File(dir, "$sosId.log")
                f.appendText("=== SOS ${ts.format(Date())} (${sosId}) ===\n")
                current = f
            }.onFailure { Log.w(TAG, "open($sosId) failed", it) }
        }
    }

    /** Append a structured-text line. Silent no-op when no sos is
     *  open OR this device isn't the victim. */
    fun append(line: String) {
        // Victim-only gate, asked through the host (SOSHandlerBridge
        // lives in :app). Fail closed: != true also covers "host not
        // installed" so we never write evidence on a non-victim phone.
        if (SOSModuleHostHolder.current?.isMyOwnSOSActive() != true) return
        synchronized(lock) {
            val f = current ?: return
            runCatching { f.appendText("${ts.format(Date())} $line\n") }
                .onFailure { Log.w(TAG, "append failed", it) }
        }
    }

    /** Seal the current log. The file stays on disk for evidence; we
     *  just stop accepting writes. Idempotent. */
    fun close() {
        synchronized(lock) {
            current?.let {
                runCatching { it.appendText("=== END ${ts.format(Date())} ===\n") }
            }
            current = null
        }
    }

    /** All recorded sos logs on this device. */
    fun all(): List<File> {
        val ctx = SOSModuleHostHolder.current?.appContext ?: return emptyList()
        val dir = File(ctx.filesDir, "sos_logs")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.isFile && it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }
}

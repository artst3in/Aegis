package app.aether.aegis.diag

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live tail of the Android logcat for THIS app's PID. Captures every
 * `Log.v/d/i/w/e` call the app makes — including the SimpleX core
 * stdout we pipe through libsupport, the WorkManager / Compose /
 * Coil / OkHttp diagnostics, native crash signatures, and the native
 * `simplex:` log relay lines — without needing each call site to be
 * routed through a custom wrapper.
 *
 * Owned by [start] / [stop]; the diagnostic screen is the only
 * caller, so the logcat subprocess only runs while the screen is
 * open. Cheap to start (a few hundred ms cold) but we still don't
 * want it running 24/7 — that's why [app.aether.aegis.simplex.ConnectionLog]
 * exists as the always-on lifecycle subset.
 *
 * Buffer is capped at [CAP] entries. Older lines drop off the
 * bottom so the UI's LazyColumn doesn't grow unbounded during long
 * triage sessions.
 *
 * The legacy [i] / [w] / [e] / [d] forwarders are kept so existing
 * call sites (CallManager etc.) still compile — they just defer to
 * Log.* and the logcat tail captures them like any other log call.
 */
object DiagLog {

    private const val CAP = 2_500
    private const val TAG = "DiagLog"

    data class Line(val raw: String, val level: Char, val tag: String)

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var pumpJob: Job? = null

    /** Start the logcat tail. Idempotent — a second call while a
     *  pump is alive is a no-op. */
    fun start() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch { runLogcat() }
    }

    fun stop() {
        pumpJob?.cancel()
        pumpJob = null
    }

    fun clear() {
        _lines.value = emptyList()
    }

    /** Plain-text snapshot for the clipboard. */
    fun snapshot(): String = _lines.value.joinToString("\n") { it.raw }

    // Legacy explicit-record API — kept so call sites that used the
    // old wrapper-style DiagLog still compile. Writes through to
    // Log.* like any other call; the logcat tail captures it.
    fun i(tag: String, line: String) { Log.i(tag, line) }
    fun w(tag: String, line: String) { Log.w(tag, line) }
    fun e(tag: String, line: String) { Log.e(tag, line) }
    fun d(tag: String, line: String) { Log.d(tag, line) }

    private suspend fun runLogcat() = withContext(Dispatchers.IO) {
        val pid = android.os.Process.myPid()
        var proc: java.lang.Process? = null
        try {
            // -v threadtime → "MM-DD HH:MM:SS.MMM PID TID L TAG: msg"
            // --pid=$pid    → only our process; logcat does the filter
            //                 kernel-side so we don't read+toss the
            //                 entire system buffer in userspace
            // *:V           → every level (the per-tag verbosity check
            //                 still lives in the Log.* call site)
            proc = ProcessBuilder(
                "logcat", "-v", "threadtime", "--pid=$pid", "*:V",
            ).redirectErrorStream(true).start()
            val reader = proc.inputStream.bufferedReader()
            while (true) {
                val raw = reader.readLine() ?: break
                if (raw.isBlank()) continue
                val (level, tag) = parsePrefix(raw)
                val next = (_lines.value + Line(raw, level, tag)).takeLast(CAP)
                _lines.value = next
            }
        } catch (t: Throwable) {
            Log.w(TAG, "logcat tail failed", t)
        } finally {
            runCatching { proc?.destroy() }
        }
    }

    /** Pull (level, tag) out of a threadtime-formatted line.
     *  Format: "MM-DD HH:MM:SS.MMM PID TID L TAG: msg" — be
     *  defensive on OEM builds whose prefix shape varies. */
    private fun parsePrefix(raw: String): Pair<Char, String> {
        val parts = raw.split(' ').filter { it.isNotEmpty() }
        val level = parts.getOrNull(4)?.firstOrNull() ?: 'I'
        val tag = parts.getOrNull(5)?.substringBefore(':') ?: "?"
        return level to tag
    }
}

package app.aether.aegis.simplex

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * In-app ring buffer of SimpleX-core / transport / protocol lifecycle
 * events, surfaced in the SimpleX detail screen so the user can see
 * exactly what happened without an `adb logcat` cable. The same
 * messages also go to Log.i so logcat captures them when available,
 * but the ring buffer is the source of truth for the in-app viewer.
 *
 * Designed for the "transport down" / "stuck initialising" class of
 * bugs: every step of start() / stop() / ensureInitialised /
 * /_connect / pump dispatch leaves a breadcrumb here. The user opens
 * Network → SimpleX → Connection log, hits Copy, and pastes the trail
 * into a bug report.
 */
object ConnectionLog {

    private const val CAP = 250

    data class Entry(
        val ts: Long,
        val tag: String,
        val message: String,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, message: String) {
        Log.i(tag, message)
        val now = System.currentTimeMillis()
        val cur = _entries.value
        val next = (cur + Entry(now, tag, message)).takeLast(CAP)
        _entries.value = next
    }

    fun warn(tag: String, message: String) {
        Log.w(tag, message)
        val now = System.currentTimeMillis()
        val cur = _entries.value
        val next = (cur + Entry(now, tag, "WARN $message")).takeLast(CAP)
        _entries.value = next
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** Render the current buffer as a single newline-joined string —
     *  what the in-app Copy button writes to the clipboard. NEWEST FIRST:
     *  the most recent events are what a bug report needs, and putting them
     *  at the TOP means a truncated paste keeps the signal instead of the
     *  oldest relay-churn noise (user-reported: "it cuts off the new ones"). */
    fun snapshot(): String = _entries.value.asReversed().joinToString("\n") { e ->
        "${ts.format(e.ts)} [${e.tag}] ${e.message}"
    }
}

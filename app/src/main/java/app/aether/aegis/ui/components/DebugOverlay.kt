package app.aether.aegis.ui.components

import android.view.Choreographer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisSOS

/**
 * Tiny inline debug strip — meant to live in the empty space inside
 * AegisHeader (right of "AEGIS"), barely readable. Shows current FPS
 * and a mini sparkline of recent frame times. The DiagLog ring
 * buffer continues to record log entries in the background; reach
 * them from a future diagnostic screen if we need them.
 *
 * Frame-time source is Choreographer.FrameCallback (Android's native
 * vsync). On a 120 Hz panel a healthy frame is ~8.3 ms; bars turn
 * red above 17 ms (dropped frame). Refresh tick is every BUFFER_SIZE
 * frames so the digits don't flicker.
 */
@Composable
fun DebugStrip(
    modifier: Modifier = Modifier,
    showCounter: Boolean = true,
    showGraph: Boolean = true,
) {
    val frameTimes = remember { FloatArray(BUFFER_SIZE) }
    var head by remember { mutableIntStateOf(0) }
    var lastTickNanos by remember { mutableStateOf(0L) }
    var displayFps by remember { mutableIntStateOf(0) }
    var heapMb by remember { mutableIntStateOf(0) }
    // Cumulative bytes since strip first mounted (i.e. since the app
    // came to foreground). TrafficStats.getUid*Bytes returns the OS
    // counter total for our UID, so we subtract a baseline taken on
    // first tick. -1 from TrafficStats means "not supported on this
    // kernel"; we treat that as 0 so the strip still renders.
    var rxBaseline by remember { mutableStateOf(-1L) }
    var txBaseline by remember { mutableStateOf(-1L) }
    var rxBytes by remember { mutableStateOf(0L) }
    var txBytes by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        val choreographer = Choreographer.getInstance()
        val uid = android.os.Process.myUid()
        // Self-reposting frame callback: each doFrame re-posts itself so we
        // sample one delta per vsync. Removed on dispose so it stops when the
        // strip leaves composition (no leak / no background CPU burn).
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                // Skip the very first frame — with no prior timestamp the
                // delta would be garbage.
                if (lastTickNanos != 0L) {
                    val deltaMs = (frameTimeNanos - lastTickNanos) / 1_000_000f
                    frameTimes[head] = deltaMs
                    head = (head + 1) % BUFFER_SIZE
                    // Recompute the displayed digits only once per full ring
                    // wrap (every BUFFER_SIZE frames) so FPS/heap/bytes don't
                    // flicker frame-to-frame; the sparkline still redraws live.
                    if (head == 0) {
                        val avg = frameTimes.average().toFloat().coerceAtLeast(0.001f)
                        displayFps = (1000f / avg).toInt()
                        val rt = Runtime.getRuntime()
                        heapMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
                        val rxNow = android.net.TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
                        val txNow = android.net.TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
                        if (rxBaseline < 0) { rxBaseline = rxNow; txBaseline = txNow }
                        rxBytes = rxNow - rxBaseline
                        txBytes = txNow - txBaseline
                    }
                }
                lastTickNanos = frameTimeNanos
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        onDispose { choreographer.removeFrameCallback(callback) }
    }

    // FPS colour bands: ≥100 full cyan (120 Hz panel healthy), ≥55 dimmed
    // cyan (60 Hz healthy), ≥30 amber (sluggish), below that SOS red.
    val fpsColor = when {
        displayFps >= 100 -> AegisCyan
        displayFps >= 55 -> AegisCyan.copy(alpha = 0.8f)
        displayFps >= 30 -> Color(0xFFFFA726)  // amber
        else             -> AegisSOS
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCounter) {
            Text(
                "${displayFps}f",
                color = fpsColor,
                fontSize = 7.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.width(3.dp))
        }
        if (showGraph) {
            // Mini sparkline — 60 frames wide × ~10 dp tall.
            Canvas(
                modifier = Modifier
                    .width(48.dp)
                    .height(10.dp),
            ) {
                val w = size.width
                val h = size.height
                val barW = w / BUFFER_SIZE
                // Full-height bar = 33 ms (~two dropped frames at 60 Hz);
                // clamp so a stall doesn't draw past the top.
                val maxMs = 33f
                for (i in 0 until BUFFER_SIZE) {
                    val ms = frameTimes[i].coerceAtMost(maxMs)
                    val barH = (ms / maxMs) * h
                    // Per-bar colour: <9 ms cyan (smooth even at 120 Hz),
                    // <17 ms amber (fine at 60 Hz, late for 120), else red
                    // (a dropped frame at 60 Hz).
                    val color = when {
                        ms < 9f  -> AegisCyan.copy(alpha = 0.7f)
                        ms < 17f -> Color(0xFFFFA726).copy(alpha = 0.7f)
                        else     -> AegisSOS.copy(alpha = 0.85f)
                    }
                    drawRect(
                        color = color,
                        topLeft = Offset(i * barW, h - barH),
                        size = androidx.compose.ui.geometry.Size(barW * 0.85f, barH),
                    )
                }
            }
            Spacer(modifier = Modifier.width(3.dp))
        }
        if (showCounter) {
            Text(
                "${heapMb}M",
                color = AegisOnSurfaceDim,
                fontSize = 7.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.width(3.dp))
            // Cumulative network bytes since app foregrounded. Tight
            // ↓X ↑Y form so the strip still fits in the header.
            Text(
                "↓${fmtBytes(rxBytes)}↑${fmtBytes(txBytes)}",
                color = AegisOnSurfaceDim,
                fontSize = 7.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Full-window alignment grid for pixel-poking. Drawn ABOVE everything
 * (including the AEGIS header) so a centred element like the lock icon
 * can be checked against the bright centre lines. Reads [DebugPrefs] so
 * the coarse/fine layers toggle live from the Diagnostics debug menu;
 * renders nothing when neither is selected, so it's a cheap no-op in the
 * common case.
 *
 *   - Coarse: 8 dp lines, plus a brighter vertical + horizontal centre
 *     line (the centre is what alignment usually hinges on).
 *   - Fine: 2 dp lines, very dim — for sub-pixel nudging once coarse
 *     gets you close.
 */
@Composable
fun DebugGridOverlay() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(ctx) { app.aether.aegis.ui.DebugPrefs(ctx) }
    val enabled by prefs.enabledFlow.collectAsState()
    val coarse = enabled.contains(app.aether.aegis.ui.DebugPrefs.Item.COARSE_GRID.key)
    val fine = enabled.contains(app.aether.aegis.ui.DebugPrefs.Item.FINE_GRID.key)
    if (!coarse && !fine) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Fine first (dimmest, underneath), then coarse, then the centre
        // lines on top so the brightest reference reads cleanly.
        if (fine) {
            val step = 2.dp.toPx()
            val c = AegisCyan.copy(alpha = 0.06f)
            var x = 0f
            while (x <= size.width) { drawLine(c, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = 0f
            while (y <= size.height) { drawLine(c, Offset(0f, y), Offset(size.width, y), 1f); y += step }
        }
        if (coarse) {
            val step = 8.dp.toPx()
            val c = AegisCyan.copy(alpha = 0.16f)
            var x = 0f
            while (x <= size.width) { drawLine(c, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = 0f
            while (y <= size.height) { drawLine(c, Offset(0f, y), Offset(size.width, y), 1f); y += step }
            // Bright centre cross — the alignment reference.
            val cl = AegisCyan.copy(alpha = 0.45f)
            drawLine(cl, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1.5f)
            drawLine(cl, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.5f)
        }
    }
}

/** Compact human byte count for the cramped header strip: G/M/K/B with
 *  decimal (1000, not 1024) units. Kept short on purpose — the whole
 *  ↓X↑Y readout has to fit in the leftover header space. */
internal fun fmtBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1fG".format(b / 1_000_000_000.0)
    b >= 1_000_000     -> "%.1fM".format(b / 1_000_000.0)
    b >= 1_000         -> "%.0fK".format(b / 1_000.0)
    else               -> "${b}B"
}

private const val BUFFER_SIZE = 60

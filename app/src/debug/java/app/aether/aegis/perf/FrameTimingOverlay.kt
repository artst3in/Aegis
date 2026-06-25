package app.aether.aegis.perf

import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.aegis.ui.DebugPrefs

/**
 * Debug-only overlay host — the SINGLE home for every dev readout, drawn as a
 * compact top-right stack that NEVER touches the app chrome.
 *
 * (Name kept as `FrameTimingOverlay` for the call site + the release stub; it
 * now hosts more than frame timing — see the rows below.)
 *
 * Every row is gated by its own [DebugPrefs] item and the whole overlay is OFF
 * by default, so a stock debug build renders nothing and costs nothing:
 *   - [DebugPrefs.Item.BUILD]         running build version (which build is this?)
 *   - [DebugPrefs.Item.COUNTER]       FPS · heap · network (via [DebugStrip])
 *   - [DebugPrefs.Item.GRAPH]         frame-time sparkline (via [DebugStrip])
 *   - [DebugPrefs.Item.FRAME_TIMING]  avg / p95 / dropped-frame pill
 *
 * Two bugs this layout fixes:
 *   1. EXTREME CPU. The frame-timing pill used to render UNCONDITIONALLY and
 *      self-repost a Choreographer callback every vsync — that forces a
 *      continuous render loop, pinning the main thread + RenderThread even on a
 *      perfectly idle screen (user-reported). It now starts the Choreographer
 *      ONLY while FRAME_TIMING is enabled, and the overlay returns early when
 *      nothing is selected, so the default build is genuinely zero-cost.
 *   2. WRECKED HEADER. The FPS counters used to live INSIDE AegisHeader's icon
 *      row; once wide enough they shoved the icons and overlapped the wordmark
 *      (user report). They render here now, off the chrome entirely.
 *
 * The release variant is a no-op stub — none of this exists in the release DEX.
 */
@Composable
fun FrameTimingOverlay(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { DebugPrefs(ctx) }
    val items by prefs.enabledFlow.collectAsState()
    val showBuild = items.contains(DebugPrefs.Item.BUILD.key)
    val showCounter = items.contains(DebugPrefs.Item.COUNTER.key)
    val showGraph = items.contains(DebugPrefs.Item.GRAPH.key)
    val showTiming = items.contains(DebugPrefs.Item.FRAME_TIMING.key)
    // Nothing selected → render nothing AND start no Choreographer. This is the
    // whole point of the CPU fix: zero overhead unless a readout is asked for.
    if (!showBuild && !showCounter && !showGraph && !showTiming) return

    var avgMs by remember { mutableStateOf(VSYNC_BUDGET_MS) }
    var p95Ms by remember { mutableStateOf(VSYNC_BUDGET_MS) }
    var dropped by remember { mutableStateOf(0) }

    // Frame-time sampler — runs ONLY while FRAME_TIMING is on. Keying the effect
    // on showTiming tears the Choreographer loop down the instant it's switched
    // off, so it can never keep forcing frames in the background.
    DisposableEffect(showTiming) {
        if (!showTiming) return@DisposableEffect onDispose { }
        val window = ArrayDeque<Float>(WINDOW)
        var lastFrameNanos = 0L
        var lastUpdateNanos = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameNanos != 0L) {
                    val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000f
                    // Drop > 250 ms deltas (background/foreground transitions)
                    // so they don't skew p95 — we measure scroll smoothness.
                    if (deltaMs < 250f) {
                        if (window.size >= WINDOW) window.removeFirst()
                        window.addLast(deltaMs)
                    }
                }
                lastFrameNanos = frameTimeNanos
                if (frameTimeNanos - lastUpdateNanos > UPDATE_PERIOD_NS && window.size >= 30) {
                    lastUpdateNanos = frameTimeNanos
                    avgMs = window.average().toFloat()
                    val sorted = window.toList().sorted()
                    p95Ms = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)]
                    dropped = window.count { it > VSYNC_BUDGET_MS }
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        Choreographer.getInstance().postFrameCallback(callback)
        onDispose { Choreographer.getInstance().removeFrameCallback(callback) }
    }

    val timingColor = when {
        p95Ms <= VSYNC_BUDGET_MS -> Color(0xFF4ADE80) // green — locked to vsync
        p95Ms <= 25f             -> Color(0xFFFACC15) // yellow — borderline
        else                     -> Color(0xFFEF4444) // red — stuttering
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Drop below the system status bar so the pills don't collide with
            // the clock / battery icons (edge-to-edge window).
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 4.dp, end = 4.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (showBuild) {
                Pill {
                    Text(
                        app.aether.aegis.BuildConfig.VERSION_NAME,
                        color = Color(0xFF4ADE80),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (showCounter || showGraph) {
                Pill {
                    app.aether.aegis.ui.components.DebugStrip(
                        showCounter = showCounter,
                        showGraph = showGraph,
                    )
                }
            }
            if (showTiming) {
                Pill {
                    // "avg / p95 · dropped" — monospace so digits don't jitter.
                    Text(
                        "%.1f / %.1f · %d".format(avgMs, p95Ms, dropped),
                        color = timingColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** Shared translucent-black rounded background so each readout reads as a
 *  distinct floating chip over whatever's behind it. */
@Composable
private fun Pill(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) { content() }
}

/** ~2 s window at 60 Hz; long enough to smooth one bad frame, short enough
 *  to react to a scroll within ~500 ms. */
private const val WINDOW = 120

/** Recompute the readout twice a second (not every vsync). */
private const val UPDATE_PERIOD_NS = 500_000_000L

/** 60 Hz vsync budget (1000 / 60). The colour ladder keys off this. */
private const val VSYNC_BUDGET_MS = 16.67f

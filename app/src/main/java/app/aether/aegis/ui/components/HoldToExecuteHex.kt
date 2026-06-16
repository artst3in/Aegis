package app.aether.aegis.ui.components

import app.aether.aegis.gesture.HoldToExecuteStore
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisCyanGlow
import kotlinx.coroutines.isActive
import kotlin.math.floor

/**
 * A HexShape that requires press-and-hold to fire when
 * HoldToExecuteStore is enabled. Otherwise behaves as a normal tap
 * button (current default — opt-in feature).
 *
 * Visual: Edge Heat animation.
 * Six TRAPEZOID edges light up CCW starting from the top edge, each
 * heating uniformly (whole-edge opacity ramps 0.1 → 1.0 across its
 * 1/6 slice of the hold duration). Vibrate on each COMPLETED edge.
 * Sixth edge complete = action fires.
 *
 * Reactor-ignition metaphor — the hex powers up edge by edge, not as
 * a continuous trail. Discrete segments, not a sweep.
 *
 * Falls back to ordinary HexShape semantics when the feature is off:
 * single tap fires [onExecute].
 */
@Composable
fun HoldToExecuteHex(
    size: Dp,
    modifier: Modifier = Modifier,
    borderColor: Color = AegisCyan,
    borderWidth: Dp = 1.5.dp,
    heatColor: Color = AegisCyan,
    fillColor: Color = AegisCyanGlow,
    glow: Boolean = false,
    glowColor: Color = AegisCyanGlow,
    enabled: Boolean = true,
    /** Override the user-pref hold duration. SOS uses 3000 ms
     *  (6 edges × 500 ms) for the SOS button; the default
     *  pulls from HoldToExecuteStore for everything else. */
    holdDurationMs: Long? = null,
    /** When true, the hold path runs even if the user has disabled
     *  hold-to-execute globally. SOS always needs hold confirmation
     *  regardless of the global setting. */
    forceHold: Boolean = false,
    /** Fire a small haptic blip when the user first presses down.
     *  SOS uses this for the t=0 marker so the hold heartbeat
     *  pattern is blip-(edge,edge)-blip-(edge,edge)-blip-(edge,edge)-fire. */
    hapticOnPress: Boolean = false,
    /** Fire the per-edge haptic only every Nth edge completion (1-
     *  indexed). 1 = blip on every edge (default). 2 = blip on edges
     *  2 and 4 (sos's 1-second heartbeat at 3 s total hold). The
     *  final edge (6) never blips here — onExecute owns its own
     *  fire-pattern. */
    hapticEdgeStride: Int = 1,
    /** Arming-animation style. false (default) = the reactor edge-by-edge
     *  ignition (CCW segments). true = all six edges warm SIMULTANEOUSLY
     *  to bright max over the hold, then the hex emits an energy wave and
     *  dims — used by SOS, where a ~1 s hold is too short to read six
     *  sequential edges and a single synchronized ramp + release is the
     *  honest visual. */
    simultaneousWarmup: Boolean = false,
    onExecute: () -> Unit,
    content: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // Reactive: a toggle on the settings screen propagates here live, no app
    // restart. Seed with the current values so there's no first-frame flip
    // before the flow's initial emission lands.
    val seed = remember { HoldToExecuteStore(context).let { it.enabled to it.durationMs } }
    val settings = HoldToExecuteStore.settingsFlow(context).collectAsState(initial = seed)
    val holdEnabled = forceHold || settings.value.first
    val holdDuration = holdDurationMs ?: settings.value.second

    if (!holdEnabled) {
        HexShape(
            size = size,
            modifier = modifier,
            borderColor = borderColor,
            borderWidth = borderWidth,
            fillColor = fillColor,
            glow = glow,
            glowColor = glowColor,
            onClick = if (enabled) onExecute else null,
            content = content,
        )
        return
    }

    val progress = remember { Animatable(0f) }
    // Completion energy-wave (simultaneousWarmup only): 0 → 1 right after
    // the hold completes, ballooning the edge halo outward + a ring while
    // the edges dim. Idle at 0.
    val wave = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }
    // Last edge index whose completion we vibrated for (0..5). -1 = none.
    var lastCompletedEdge by remember { mutableIntStateOf(-1) }

    LaunchedEffect(pressed) {
        if (pressed) {
            lastCompletedEdge = -1
            if (hapticOnPress) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            val remaining = ((1f - progress.value) * holdDuration).toLong()
                .coerceAtLeast(0L)
            progress.animateTo(
                targetValue = 1f,
                // LinearEasing — tween() defaults to FastOutSlowIn, so
                // progress was ease-curved and per-edge boundaries
                // crossed at non-uniform wall-clock times. That made
                // the SOS haptic heartbeat read as "uneven pulses"
                // even though the math said every other edge. Linear
                // ramp = edges complete at exactly N × (hold / 6)
                // milliseconds, so a stride-2 schedule lands on
                // 1.0 / 2.0 / (3.0) seconds for a 3 s hold.
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = remaining.toInt(),
                    easing = androidx.compose.animation.core.LinearEasing,
                ),
            ) {
                // Each edge takes 1/6 of total progress; when we cross
                // a 1/6 boundary, that edge just completed (vibrate
                // per edge, not at vertex). We blip
                // only on every [hapticEdgeStride]th edge — stride 1
                // = every edge (the default), stride 2 = sos's
                // 1-second heartbeat at 3 s total.
                val completed = floor(this.value * 6).toInt().coerceIn(0, 6)
                while (lastCompletedEdge < completed - 1 && lastCompletedEdge < 5) {
                    lastCompletedEdge++
                    val edgeNum = lastCompletedEdge + 1  // 1..5
                    if (edgeNum % hapticEdgeStride == 0) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
            if (progress.value >= 1f && isActive) {
                // Don't fire a haptic here — onExecute owns the
                // confirmation pattern (e.g. SOS's 3-blip via
                // Vibrator). The per-edge blips already covered the
                // hold journey.
                onExecute()
                if (simultaneousWarmup) {
                    // Bright max is held → emit the energy wave + dim. The
                    // edges stay at full (progress==1) while the wave runs,
                    // then everything resets to idle.
                    wave.snapTo(0f)
                    wave.animateTo(
                        1f,
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 520,
                            easing = androidx.compose.animation.core.LinearOutSlowInEasing,
                        ),
                    )
                    wave.snapTo(0f)
                }
                progress.snapTo(0f)
                pressed = false
            }
        } else {
            progress.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(150))
            lastCompletedEdge = -1
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .pointerInput(holdEnabled, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    while (true) {
                        val ev = awaitPointerEvent()
                        val change = ev.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                    }
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Base hex — dim border underneath, so an unstarted button
        // still looks like a hex outline. We draw the dim outline
        // explicitly here (a HexShape with transparent fill would do
        // the same, but we want pixel-aligned overlap with the heat
        // trapezoids so the brightening reads as the SAME edges
        // changing colour, not as a new layer popping in).
        Canvas(modifier = Modifier.size(size)) {
            val s = this.size
            val wPx = borderWidth.toPx()
            // Solid interior fill (e.g. the black SOS button). The hold
            // path doesn't render through HexShape, so its fillColor was
            // never painted — draw it here, under the edges (sos
            // should be completely black inside, not transparent).
            if (fillColor != androidx.compose.ui.graphics.Color.Transparent) {
                drawPath(cachedHexPolyPath(s), color = fillColor)
            }
            // Dim base outline using the same trapezoid geometry.
            for (i in 0 until 6) {
                val (ai, bi) = EDGES_CCW_FROM_TOP[i]
                drawPath(
                    path = hexEdgeTrapezoid(s, ai, bi, wPx),
                    color = borderColor.copy(alpha = 0.30f),
                )
            }
            if (simultaneousWarmup) {
                // SOS style: all six edges heat together (0.1 → 1.0 across
                // the whole hold), then on completion the wave fades them
                // out while the halo balloons + a ring expands. base = the
                // shared edge brightness; during the wave it dims 1 → 0.
                val w = wave.value
                val base = if (w > 0f) (1f - w) else (0.1f + progress.value * 0.9f)
                if (progress.value > 0f || w > 0f) {
                    val heatWidthPx = wPx * 2.4f
                    // Halo width grows with the wave → energy pushing outward.
                    val glowWidthPx = wPx * 5.5f * (1f + w * 2.4f)
                    val glowAlpha = if (w > 0f) (1f - w) * 0.30f else base * 0.18f
                    for (i in 0 until 6) {
                        val (ai, bi) = EDGES_CCW_FROM_TOP[i]
                        drawPath(
                            path = hexEdgeTrapezoid(s, ai, bi, glowWidthPx),
                            color = heatColor.copy(alpha = glowAlpha.coerceIn(0f, 1f)),
                        )
                        if (base > 0f) {
                            drawPath(
                                path = hexEdgeTrapezoid(s, ai, bi, heatWidthPx),
                                color = heatColor.copy(alpha = base.coerceIn(0f, 1f)),
                            )
                        }
                    }
                    // Expanding energy ring — emitted on completion, fades out.
                    if (w > 0f) {
                        val cx = s.width / 2f
                        val cy = s.height / 2f
                        val r0 = s.width * 0.30f
                        val r = r0 + (s.width * 0.5f - r0) * (w * 1.6f)
                        drawCircle(
                            color = heatColor.copy(alpha = ((1f - w) * 0.5f).coerceIn(0f, 1f)),
                            radius = r,
                            center = androidx.compose.ui.geometry.Offset(cx, cy),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = wPx * 2f),
                        )
                    }
                }
            } else if (progress.value > 0f) {
                // Reactor style: edges ignite CCW, one 1/6-slice at a time.
                val activeEdgeIdx = floor(progress.value * 6).toInt().coerceAtMost(5)
                val activeFrac = (progress.value * 6f) - activeEdgeIdx
                val heatWidthPx = wPx * 2.4f       // thicker than the base
                val glowWidthPx = wPx * 5.5f       // halo behind
                for (i in 0 until 6) {
                    val op = when {
                        progress.value >= 1f -> 1f
                        i < activeEdgeIdx -> 1f
                        i == activeEdgeIdx -> 0.1f + activeFrac * 0.9f
                        else -> 0f
                    }
                    if (op <= 0f) continue
                    val (ai, bi) = EDGES_CCW_FROM_TOP[i]
                    // Glow behind first.
                    drawPath(
                        path = hexEdgeTrapezoid(s, ai, bi, glowWidthPx),
                        color = heatColor.copy(alpha = op * 0.18f),
                    )
                    // Bright trapezoid on top.
                    drawPath(
                        path = hexEdgeTrapezoid(s, ai, bi, heatWidthPx),
                        color = heatColor.copy(alpha = op),
                    )
                }
            }
        }
        // Same font-padding strip as HexShape — see comment there.
        // lineHeight MUST be set or LineHeightStyle.Trim is a no-op,
        // which is what was leaving emoji glyphs a pixel off-centre.
        val base = LocalTextStyle.current
        CompositionLocalProvider(
            LocalTextStyle provides base.copy(
                textAlign = TextAlign.Center,
                lineHeight = if (base.fontSize.type == androidx.compose.ui.unit.TextUnitType.Sp)
                    base.fontSize else 14.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
        ) {
            content()
        }
    }
}

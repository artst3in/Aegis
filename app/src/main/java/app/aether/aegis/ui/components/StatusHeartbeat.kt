package app.aether.aegis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aether.aegis.ui.HealthVerdict
import app.aether.aegis.ui.theme.AegisOnline
import app.aether.aegis.ui.theme.AegisSOS
import app.aether.aegis.ui.theme.AegisWarning
import kotlin.math.exp

/**
 * The status heartbeat — the app's "I'm alive and watching over you"
 * pulse. Used by the header status dot AND the Diagnostics network-card
 * orb, driven off a single wall-clock phase so the two beat in lockstep
 * no matter when each composes.
 *
 * Why a heartbeat (and why these colours): for the at-risk users this app
 * exists for, the indicator has to read instantly under stress. Colour
 * carries the verdict — green = covered, amber = degraded, red = down —
 * and MOTION carries liveness: a calm steady beat when you're safe, a
 * quicker beat when something needs a glance, and stillness when it has
 * stopped (a dead heart doesn't beat). The healthy state stays GREEN, not
 * cyan: cyan is the brand colour all over the chrome, so a cyan health
 * dot would lose both its all-clear meaning and its contrast.
 */

/** Verdict → LunaGlass status colour. Single source so every surface
 *  agrees. */
fun verdictColor(verdict: HealthVerdict): Color = when (verdict) {
    HealthVerdict.OPERATIONAL -> AegisOnline
    HealthVerdict.DEGRADED    -> AegisWarning
    HealthVerdict.OFFLINE     -> AegisSOS
}

/**
 * Smooth delivery-health colour: green (1.0) → amber (0.5) → red (0.0).
 * Two-segment lerp THROUGH amber so the midpoint is a clean amber rather
 * than the muddy grey a direct green↔red blend passes through. Driven by
 * [NetworkHealth.healthFraction] so the dot fades continuously — fully
 * green with every relay up, more amber with each relay down (relative to
 * the total), toward red as the connection fails.
 */
fun healthColor(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return if (f >= 0.5f) androidx.compose.ui.graphics.lerp(AegisWarning, AegisOnline, (f - 0.5f) * 2f)
           else androidx.compose.ui.graphics.lerp(AegisSOS, AegisWarning, f * 2f)
}

// Beat cadence by health. The period scales CONTINUOUSLY with the same
// health fraction that drives the colour, so beat and hue move together:
// calm green at full health (~2.4 s), quickening toward red as it drops
// (~0.9 s near zero). Offline (red / no connection) flatlines — never beats.
private const val CALM_PERIOD_MS = 2_400f    // full health (green)
private const val QUICK_PERIOD_MS = 900f     // near-zero health (toward red)

private fun gaussian(x: Float, center: Float, width: Float): Float {
    val d = (x - center) / width
    return exp(-0.5f * d * d)
}

/**
 * Maps a 0..1 cycle phase to a 0..1 beat intensity shaped like a real
 * cardiac "lub-dub": two close bumps near the start of the cycle, then a
 * flat rest until the next one. Pure so it's trivially testable.
 */
fun heartbeatPulse(phase: Float): Float {
    val lub = gaussian(phase, 0.06f, 0.045f)
    val dub = gaussian(phase, 0.20f, 0.050f) * 0.72f
    return maxOf(lub, dub).coerceIn(0f, 1f)
}

/**
 * Live beat intensity (0..1). The cadence tracks [fraction] (1=green …
 * 0=red) so the heartbeat speeds up as the colour shifts toward red;
 * [verdict] only decides whether it beats at all — OFFLINE flatlines at 0.
 *
 * Returned as [State] so callers read `.value` inside a draw / graphics
 * layer lambda — that confines the per-frame invalidation to drawing,
 * never recomposing the surrounding header or card.
 */
@Composable
fun rememberVerdictPulse(verdict: HealthVerdict, fraction: Float): State<Float> {
    val intensity = remember { mutableFloatStateOf(0f) }
    // Read the latest fraction inside the loop WITHOUT restarting it, so a
    // continuously-changing health value smoothly retimes the beat.
    val fractionState = androidx.compose.runtime.rememberUpdatedState(fraction)
    // Restart the loop only when crossing the offline (flatline) boundary —
    // never on an ordinary fraction change, which would jump the phase.
    val offline = verdict == HealthVerdict.OFFLINE
    LaunchedEffect(offline) {
        if (offline) {
            intensity.floatValue = 0f
            return@LaunchedEffect
        }
        // Phase accumulator (0..1): advancing by dt/period each frame means a
        // shifting period eases the beat faster/slower instead of snapping.
        var phase = 0f
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val dt = (now - last).coerceAtLeast(0L)
            last = now
            val f = fractionState.value.coerceIn(0f, 1f)
            val period = QUICK_PERIOD_MS + (CALM_PERIOD_MS - QUICK_PERIOD_MS) * f
            phase = (phase + dt.toFloat() / period) % 1f
            intensity.floatValue = heartbeatPulse(phase)
        }
    }
    return intensity
}

/**
 * A circular status dot that beats. [coreSize] is the resting diameter of
 * the solid centre; the glow halo blooms outward on each beat. The canvas
 * reserves room around the core for the halo.
 */
@Composable
fun StatusHeartbeatDot(
    verdict: HealthVerdict,
    /** Continuous health fraction (1=green … 0=red) driving the SMOOTH dot
     *  colour. The 3-state [verdict] still sets the beat cadence. */
    fraction: Float,
    modifier: Modifier = Modifier,
    coreSize: Dp = 7.dp,
) {
    val color = healthColor(fraction)
    val pulse = rememberVerdictPulse(verdict, fraction)
    // Canvas is ~3.4x the core so the halo has room to bloom.
    Canvas(modifier = modifier.size(coreSize * 3.4f)) {
        val p = pulse.value
        val c = Offset(size.width / 2f, size.height / 2f)
        val baseR = coreSize.toPx() / 2f
        // Soft halo: alpha + radius both swell with the beat.
        drawCircle(
            color = color.copy(alpha = 0.10f + 0.28f * p),
            radius = baseR * (1.4f + 1.9f * p),
            center = c,
        )
        // Solid core: a gentle swell on the beat, never shrinking below
        // its resting size so it always reads as a clear dot.
        drawCircle(
            color = color,
            radius = baseR * (1f + 0.22f * p),
            center = c,
        )
    }
}

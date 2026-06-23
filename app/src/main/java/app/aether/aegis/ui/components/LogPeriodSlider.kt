package app.aether.aegis.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp
import kotlin.math.ln

/**
 * Generic log-scale "period" slider — instant ↔ never. Same shape
 * as the auto-lock control on LockSettingsScreen and intended to
 * replace every other time-window picker in the app per the user's
 * "any period setting should be this slider" feedback.
 *
 * Slider position is 0..1 with snap zones at both ends:
 *   ≤ SNAP_FRAC → instant / off ([instantSeconds] or 0)
 *   ≥ 1 - SNAP_FRAC → never ([neverSeconds] or null)
 *   middle 90 % → log-evenly mapped between [minSeconds] and
 *                 [maxSeconds]
 *
 * Caller owns persistence and conversion to its own type. The
 * slider speaks SECONDS as a Long; `null` is reserved for "Never"
 * when [allowNever] is true (otherwise the upper snap maps to
 * [maxSeconds]).
 */
@Composable
fun LogPeriodSlider(
    valueSeconds: Long?,
    onValueChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    /** Range floor for the log-scale interior. Drag below the snap
     *  zone returns [instantSeconds] (default null → "instant"). */
    minSeconds: Double = 1.0,
    /** Range ceiling for the log-scale interior. */
    maxSeconds: Double = 365.0 * 24 * 3600,
    /** Value emitted when the slider hits the lower snap. null
     *  reads as "instant / off" (e.g. burn-default = no auto-burn);
     *  caller can override with 0 if "off" doesn't make sense
     *  semantically and the floor should be 0 seconds. */
    instantSeconds: Long? = null,
    /** Value emitted when the slider hits the upper snap. */
    neverSeconds: Long? = null,
    /** Whether the upper snap is reachable. False locks the
     *  slider's top to [maxSeconds] instead of "Never". */
    allowNever: Boolean = true,
    /** Label rendered above the slider for the instant snap (≤
     *  SNAP_FRAC of travel). */
    instantLabel: String = "Off",
    /** Label rendered for the upper snap. */
    neverLabel: String = "Never",
) {
    // Slider thumb position 0..1, seeded from the caller's seconds. Re-keyed
    // on valueSeconds so an external change to the persisted value moves the
    // thumb; local drags update it without round-tripping through the caller.
    var slider by remember(valueSeconds) {
        mutableStateOf(sliderForSeconds(valueSeconds, minSeconds, maxSeconds, allowNever))
    }
    // Live preview of the seconds the current thumb maps to — recomputed as
    // the user drags so the label updates before onValueChangeFinished fires.
    val previewSec = remember(slider, allowNever) {
        secondsForSlider(slider, minSeconds, maxSeconds, allowNever, instantSeconds, neverSeconds)
    }
    // Label resolution order matters: the snap sentinels (instant/never) are
    // checked first because they're distinguished by VALUE, not position, and
    // null also reads as instant — only a real interior value gets formatted.
    val display = remember(previewSec, allowNever, instantLabel, neverLabel) {
        when {
            previewSec == instantSeconds -> instantLabel
            previewSec == neverSeconds && allowNever -> neverLabel
            previewSec == null -> instantLabel
            else -> humanReadableSeconds(previewSec)
        }
    }
    Column(modifier = modifier) {
        Text(
            display,
            color = app.aether.aegis.ui.theme.AegisCyan,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        HexSlider(
            value = slider,
            onValueChange = { slider = it },
            // Commit to the caller only on release, not on every drag frame —
            // persistence/side-effects happen once per gesture, not per pixel.
            onValueChangeFinished = {
                onValueChange(
                    secondsForSlider(
                        slider, minSeconds, maxSeconds, allowNever,
                        instantSeconds, neverSeconds,
                    )
                )
            },
            valueRange = 0f..1f,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                instantLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
            if (allowNever) {
                Text(
                    neverLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

// Each end reserves 5% of travel as a snap zone for the instant/never
// sentinels, so the log-scale interior occupies the middle 90% (or 95% when
// there's no "never" snap). Small enough that the snaps don't eat usable range.
private const val SNAP_FRAC = 0.05f

/**
 * Map a 0..1 thumb position to seconds. The two snap zones return the
 * sentinel values; the interior is an EXPONENTIAL (log-linear) map so a
 * fixed drag distance multiplies the duration — the only way one slider
 * can span "1 second" to "1 year" with usable precision at both ends.
 *
 * Inverse of [sliderForSeconds]. Keep the `travel`/`SNAP_FRAC` algebra in
 * sync between the two or the thumb will jump when a value round-trips.
 */
private fun secondsForSlider(
    p: Float,
    minSeconds: Double,
    maxSeconds: Double,
    allowNever: Boolean,
    instantSeconds: Long?,
    neverSeconds: Long?,
): Long? = when {
    p <= SNAP_FRAC -> instantSeconds
    allowNever && p >= 1f - SNAP_FRAC -> neverSeconds
    // No "never" snap: the top instead pins to the range ceiling.
    !allowNever && p >= 1f - SNAP_FRAC -> maxSeconds.toLong()
    else -> {
        // Interpolate linearly in LOG space → exponential in real seconds.
        val logMin = ln(minSeconds)
        val logMax = ln(maxSeconds)
        // Usable interior width: both snaps reserved when never is allowed,
        // only the lower one otherwise.
        val travel = if (allowNever) (1f - 2 * SNAP_FRAC) else (1f - SNAP_FRAC)
        val frac = (p - SNAP_FRAC) / travel
        exp(logMin + frac * (logMax - logMin)).toLong()
    }
}

/**
 * Inverse of [secondsForSlider]: place the thumb for a given seconds value.
 * Sentinels map to the ends (null → never-or-floor, ≤0 → floor, ≥ceiling →
 * top); everything else is positioned by its log within [minSeconds,
 * maxSeconds]. The same `travel`/`SNAP_FRAC` algebra mirrors the forward map.
 */
private fun sliderForSeconds(
    sec: Long?,
    minSeconds: Double,
    maxSeconds: Double,
    allowNever: Boolean,
): Float = when {
    sec == null -> if (allowNever) 1f else 0f
    sec <= 0L -> 0f
    sec >= maxSeconds.toLong() && allowNever -> 1f
    else -> {
        // Clamp before the log so an out-of-range value can't push the thumb
        // past the snap zones.
        val clamped = sec.toDouble().coerceIn(minSeconds, maxSeconds)
        val logMin = ln(minSeconds)
        val logMax = ln(maxSeconds)
        val travel = if (allowNever) (1f - 2 * SNAP_FRAC) else (1f - SNAP_FRAC)
        val frac = ((ln(clamped) - logMin) / (logMax - logMin)).toFloat()
        SNAP_FRAC + frac * travel
    }
}

/**
 * Format seconds as a single rounded unit (second/minute/hour/day/week/
 * month/year) for the slider label. Each branch adds half the next unit
 * before integer-dividing so the displayed value rounds to nearest rather
 * than truncating (e.g. 50 min → "1 hour", not "0 hours"). Months are
 * approximated at 30 days, years at 365 — display-only, not for arithmetic.
 */
private fun humanReadableSeconds(sec: Long): String = when {
    sec < 60L -> {
        val s = sec.coerceAtLeast(1L)
        "$s second${if (s == 1L) "" else "s"}"
    }
    sec < 3600L -> {
        val m = (sec + 30) / 60L
        "$m minute${if (m == 1L) "" else "s"}"
    }
    sec < 86400L -> {
        val h = (sec + 1800) / 3600L
        "$h hour${if (h == 1L) "" else "s"}"
    }
    sec < 86400L * 7 -> {
        val d = (sec + 43200) / 86400L
        "$d day${if (d == 1L) "" else "s"}"
    }
    sec < 86400L * 30 -> {
        val w = (sec + 86400L * 3) / (86400L * 7)
        "$w week${if (w == 1L) "" else "s"}"
    }
    sec < 86400L * 365 -> {
        val mo = (sec + 86400L * 15) / (86400L * 30)
        "$mo month${if (mo == 1L) "" else "s"}"
    }
    else -> {
        val y = sec / (86400L * 365)
        "$y year${if (y == 1L) "" else "s"}"
    }
}

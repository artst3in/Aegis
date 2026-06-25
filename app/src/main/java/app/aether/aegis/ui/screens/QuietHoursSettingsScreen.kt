package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.AegisTopBar
import app.aether.aegis.ui.components.HexSlider

import app.aether.aegis.quiet.QuietHoursStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.ui.components.GlassPanel

/**
 * Configure the night-auto-silence window. Wraps cleanly around
 * midnight (e.g. 23:00 → 07:00). SOS always overrides, so this is
 * for chat-class notifications only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietHoursSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val store = remember { QuietHoursStore(context) }
    // Compose mirror of the persisted enabled flag; the start/end mirrors
    // below additionally re-snap their stored value on load (see comment).
    var enabled by remember { mutableStateOf(store.enabled) }
    // Snap on load in case a prior bug stored an off-grid value
    // (e.g. 299 reading as 04:59 from the steps=47 → 29.979-minute-
    // interval era). Without this the screen would render 04:59
    // forever even after the snap-on-drag fix shipped.
    var startMin by remember {
        mutableStateOf(snapHalfHour(store.startMinute.toFloat())).also {
            if (it.value != store.startMinute) store.startMinute = it.value
        }
    }
    var endMin by remember {
        mutableStateOf(snapHalfHour(store.endMinute.toFloat())).also {
            if (it.value != store.endMinute) store.endMinute = it.value
        }
    }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = { Text(stringResource(R.string.settings_quiet_hours)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.settings_how_it_works), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.quiet_hours_mutes_chat_notification_sounds) +
                            "SOS alerts ALWAYS override — they will still wake you up.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_enabled), fontWeight = FontWeight.SemiBold)
                        // Summary "HH:MM → HH:MM"; the window may wrap past
                        // midnight (start > end), which the matcher in
                        // QuietHoursStore handles — nothing special here.
                        Text(
                            "${fmtTime(startMin)} → ${fmtTime(endMin)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    app.aether.aegis.ui.components.HexSwitch(
                        checked = enabled,
                        onCheckedChange = {
                            // Mirror then persist (see field declarations).
                            enabled = it
                            store.enabled = it
                        },
                    )
                }
            }
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.settings_start), fontWeight = FontWeight.SemiBold)
                    Text(fmtTime(startMin),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    HexSlider(
                        value = startMin.toFloat(),
                        // Snap explicitly to the nearest half-hour
                        // boundary on every drag. The previous
                        // `valueRange = 0..1439, steps = 47` setup
                        // gave 49 stops at step-size 29.979, which
                        // landed on times like 6:29 or 22:59 instead
                        // of the round half-hours the user expected.
                        onValueChange = {
                            startMin = snapHalfHour(it)
                        },
                        onValueChangeFinished = { store.startMinute = startMin },
                        valueRange = 0f..1410f,
                        steps = 46,  // 0, 30, 60, …, 1410 = 48 values
                    )
                }
            }
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.settings_end), fontWeight = FontWeight.SemiBold)
                    Text(fmtTime(endMin),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    // Same half-hour-snapped slider as Start (see that
                    // block for why the range/steps are 0..1410 / 46). Snap
                    // on every drag; commit to the store only on release.
                    HexSlider(
                        value = endMin.toFloat(),
                        onValueChange = {
                            endMin = snapHalfHour(it)
                        },
                        onValueChangeFinished = { store.endMinute = endMin },
                        valueRange = 0f..1410f,
                        steps = 46,
                    )
                }
            }
        }
    }
}

/** Snap a slider value (minutes-of-day, 0..1410) to the nearest
 *  half-hour boundary. Used by both Start and End sliders so the
 *  displayed time always reads as a clean HH:00 or HH:30 — the
 *  prior unsnapped values landed on 6:29 / 22:59 because the
 *  step distance worked out to 29.979 minutes. */
private fun snapHalfHour(raw: Float): Int {
    val n = ((raw / 30f).toInt().coerceAtLeast(0)) * 30
    return n.coerceIn(0, 1410)
}

/** Render a minutes-of-day value as a zero-padded 24-hour "HH:MM" string.
 *  The coerce guards are defensive only — callers pass snapped 0..1410
 *  values, so h/m can't actually exceed 23/59, but clamping keeps the
 *  formatter total even if an off-grid value ever slips through. */
private fun fmtTime(minutes: Int): String {
    val h = (minutes / 60).coerceIn(0, 23)
    val m = (minutes % 60).coerceIn(0, 59)
    return "%02d:%02d".format(h, m)
}

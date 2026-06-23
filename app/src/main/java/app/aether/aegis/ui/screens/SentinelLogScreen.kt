package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.AegisTopBar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.sentinel.SensorId
import app.aether.aegis.sentinel.SentinelEventLog
import app.aether.aegis.sentinel.SentinelStage
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisSOS
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Sender-side forensic event log viewer. Shows the binary event log
 * (every sensor trip, every stage transition, every notification
 * dispatch) in reverse-chronological order. Useful for:
 *   - debugging "did the cascade fire when I expected?"
 *   - calibration sanity checks ("sonar tripped 12 times overnight
 *     but only 3 reached PROXIMITY_ARMED — speaker amplitude
 *     looks fine, false-positive rate is high")
 *   - confirming auto-arm bedside flow worked
 *
 * Append-only, no edit affordance. Clear-log button at the top for
 * starting fresh after calibration tuning.
 */
/**
 * Renders the local Sentinel event log as a flat, reverse-chronological
 * list with a top-of-list "size" header and a Clear action in the app
 * bar. This is the SENDER-side debug trace (what THIS phone's cascade
 * did); inbound events from contacts live in [SentinelInboxScreen]
 * instead. Read-only snapshot — see the [rows] note below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentinelLogScreen(navController: NavController) {
    val ctx = LocalContext.current
    val log = remember(ctx) { SentinelEventLog(ctx) }
    // Snapshot read, not a live flow: the log is append-only and only
    // grows while sensors are tripping, so a single tail() at screen
    // entry is current enough. Capped at the most recent 500 events to
    // bound the LazyColumn; older entries stay on disk but aren't shown.
    // Clearing the log replaces `rows` with emptyList() directly rather
    // than re-reading.
    var rows by remember { mutableStateOf(log.tail(500)) }
    LaunchedEffect(Unit) { rows = log.tail(500) }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = { Text(stringResource(R.string.sentinel_log_sentinel_event_log)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
                actions = {
                    // Clear-log: wipe the on-disk log AND the displayed
                    // snapshot in one tap. Intended for starting fresh
                    // after a calibration-tuning session so the next
                    // overnight run's stats aren't polluted by test trips.
                    // No confirmation dialog — the log is debug telemetry,
                    // not user data worth guarding.
                    TextButton(onClick = {
                        log.clear()
                        rows = emptyList()
                    }) {
                        Text(stringResource(R.string.sentinel_log_clear), fontSize = 12.sp)
                    }
                },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.sentinel_log_no_events_logged_arm) +
                        "sensors — every trip and stage transition lands here.",
                    color = AegisOnSurfaceDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // Keyed header row so it stays stable across recompositions
            // as the event list below changes. Reports count + on-disk
            // size (integer KB) so the user can gauge how chatty the
            // cascade has been and whether a clear is overdue.
            item("size") {
                Text(
                    "${rows.size} events · ${log.sizeBytes() / 1024} KB on disk",
                    color = AegisOnSurfaceDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            // Newest first — reverse the list. `tail()` returns oldest→
            // newest; reversing here is cheaper than reading the file
            // backwards and keeps the on-disk format append-only.
            // Composite key (timestamp + sensor name) because a single
            // millisecond can carry more than one event (e.g. a sensor
            // trip and the stage transition it causes).
            items(rows.reversed(), key = { "${it.timestampMs}-${it.sensor.name}" }) { row ->
                EventLogRow(row)
            }
        }
    }
}

/** A single log line: monospace `HH:mm:ss` timestamp on the left, a
 *  colour-coded, human-readable detail string on the right. One row per
 *  logged event; purely presentational (no tap target). */
@Composable
private fun EventLogRow(row: SentinelEventLog.Row) {
    // Colour encodes the cascade layer at a glance: cyan for the
    // sonar/proximity/notify stack (the "normal" pipeline), SOS-red for
    // the accelerometer (the loudest, last-stage sensor), dim grey for
    // bookkeeping stage-transition markers.
    val sensorColor = when (row.sensor) {
        SensorId.SONAR -> AegisCyan
        SensorId.PROXIMITY -> AegisCyan.copy(alpha = 0.8f)
        SensorId.ACCEL -> AegisSOS
        SensorId.NOTIFY -> AegisCyan
        SensorId.STAGE_TRANSITION -> AegisOnSurfaceDim
    }
    // Time-only format (no date): the log is read as a within-session
    // timeline. Memoised per timestamp so we don't reformat on every
    // recomposition.
    val time = remember(row.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(row.timestampMs))
    }
    // Per-sensor detail string. `magnitude` is overloaded by sensor:
    // recipient count for NOTIFY, raw accel magnitude for ACCEL.
    val detail = when (row.sensor) {
        SensorId.STAGE_TRANSITION -> "→ ${row.stage.label}"
        SensorId.NOTIFY -> "notify (×${row.magnitude} recipients)"
        SensorId.SONAR -> "sonar trip @ ${row.stage.label}"
        SensorId.PROXIMITY -> "proximity @ ${row.stage.label}"
        SensorId.ACCEL -> "accel mag=${row.magnitude} @ ${row.stage.label}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            time,
            color = AegisOnSurfaceDim,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(64.dp),
        )
        Text(
            detail,
            color = sensorColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

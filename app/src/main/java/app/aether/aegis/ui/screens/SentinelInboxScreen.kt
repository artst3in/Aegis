package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.AegisTopBar

import app.aether.aegis.ui.components.AegisOutlinedButton

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.AegisApp
import app.aether.aegis.sentinel.SentinelInbox
import app.aether.aegis.sentinel.SentinelRecording
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.components.SparklineTriplet
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisSOS
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Inbox for inbound `KIND_SENTINEL_EVENT` packets from contacts who
 * have us on their notify-list. Each row is one cascade event — tap
 * to expand the detail view with the attached 3D-model sparkline,
 * mugshot (if captured), and battery telemetry.
 *
 * The shape mirrors the Diagnostics scroll-list pattern but with
 * tap-to-expand inline rather than a separate detail route — events
 * are short enough that the full payload renders comfortably below
 * the row.
 *
 * Mark-all-read on screen entry: opening the inbox IS the user's
 * acknowledgement gesture (same logic as the unlock → watermark
 * reset rule), so the badge clears on view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentinelInboxScreen(navController: NavController) {
    val ctx = LocalContext.current
    val inbox = remember(ctx) { SentinelInbox(ctx) }
    // Re-read on every screen entry; the inbox is append-only so a
    // re-read picks up everything new since last view without
    // needing a live flow. Capped at the most recent 200 events to
    // bound the list.
    var rows by remember { mutableStateOf(inbox.tail(200)) }
    LaunchedEffect(Unit) {
        // Initial read + mark-all-read are coupled deliberately: opening
        // the inbox IS the acknowledgement gesture, so we snapshot the
        // rows (which still carry their unread flags for display) and
        // immediately clear the unread badge. Ordering matters — read the
        // rows BEFORE markAllRead() mutates the on-disk state.
        rows = inbox.tail(200)
        inbox.markAllRead()
    }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = { Text(stringResource(R.string.sentinel_inbox_sentinel_inbox)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        AegisIcon(AegisIcons.Back, "back")
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
                    stringResource(R.string.sentinel_inbox_no_sentinel_events_yet) +
                        "their Sentinel notify-list triggers a cascade, the " +
                        "events arrive here.",
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(12.dp),
        ) {
            // Composite key (timestamp + 8-char peer-key prefix) keeps row
            // identity stable across recompositions and disambiguates two
            // events that share a millisecond from different senders.
            items(rows, key = { "${it.timestampMs}-${it.fromPeerKey.take(8)}" }) { row ->
                InboxEventCard(row, inbox)
            }
        }
    }
}

/** One event card. Header line is always visible; tap to expand the
 *  forensic payload (sparkline + mugshot). */
@Composable
private fun InboxEventCard(row: SentinelInbox.Row, inbox: SentinelInbox) {
    var expanded by remember { mutableStateOf(false) }
    // Was runBlocking { knownPeerByKey } during COMPOSITION (per card) — a
    // main-thread DB stall for every inbox row. Resolve asynchronously with
    // produceState so composition never blocks; show a truncated key until
    // the name loads.
    val peerName by androidx.compose.runtime.produceState(
        initialValue = row.fromPeerKey.take(12) + "…",
        key1 = row.fromPeerKey,
    ) {
        value = runCatching {
            AegisApp.instance.repository.knownPeerByKey(row.fromPeerKey)?.displayName
        }.getOrNull() ?: row.fromPeerKey.take(12) + "…"
    }
    // Full date+time here (unlike the time-only sender log): an inbox
    // event may be days old by the time the recipient checks it, so the
    // date is load-bearing context. Memoised per timestamp.
    val timeStr = remember(row.timestampMs) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(row.timestampMs))
    }
    // Stage colour escalates with severity: SOS-red once recording (the
    // intruder is physically handling the phone), cyan at proximity-armed
    // (something approached), dim otherwise. The string keys are the wire
    // stage labels, not the SentinelStage enum.
    val stageColor = when (row.stage) {
        "recording" -> AegisSOS
        "proximity-armed" -> AegisCyan
        else -> AegisOnSurfaceDim
    }
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (row.isDrill) {
                            // "🛡️ DRILL" chip — recipient sees it's a
                            // test, not a real alert. Spec UX language.
                            Text(
                                stringResource(R.string.sentinel_inbox_drill),
                                color = AegisCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                        Text(peerName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    Text(
                        "${row.stage.uppercase()} · $timeStr",
                        color = stageColor,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                // Sender battery at trip time, if telemetry was attached.
                // Red at or below 15 % — a low-battery sender's phone may
                // die before the full cascade reports, so the recipient
                // should treat a low reading as "this may be all I get".
                row.batteryPct?.let { batt ->
                    Text(
                        "$batt%",
                        color = if (batt <= 15) AegisSOS else AegisOnSurfaceDim,
                        fontSize = 11.sp,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (expanded) "▾" else "▸", color = AegisOnSurfaceDim)
            }
            if (row.isDrill) {
                // Confirm-receipt button for drill rows. Sends a
                // KIND_SENTINEL_DRILL_ACK back to the peer so their
                // drill counter ticks. State persists via "confirmed"
                // local flag — disabled after first tap.
                // Local-only confirmed flag: drives the disabled state so
                // the recipient can't double-ack. NOT persisted — a screen
                // re-entry resets it, but a second ack is harmless on the
                // sender side (idempotent drill counter), and the cost of
                // persisting per-row ack state isn't worth it for a drill.
                var confirmed by remember { mutableStateOf(false) }
                Spacer(modifier = Modifier.height(6.dp))
                AegisOutlinedButton(
                    onClick = {
                        if (!confirmed) {
                            inbox.sendDrillAck(row.fromPeerKey)
                            confirmed = true
                        }
                    },
                    enabled = !confirmed,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (confirmed) "✓ Confirmed"
                        else "Confirm receipt — ${peerName} can verify their cascade",
                        fontSize = 11.sp,
                    )
                }
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                ExpandedDetail(row, inbox)
            }
        }
    }
}

/** Forensic payload: mugshot + sparkline triplet, both loaded
 *  lazily on expand so untapped events don't pay the decode cost. */
@Composable
private fun ExpandedDetail(row: SentinelInbox.Row, inbox: SentinelInbox) {
    val ctx = LocalContext.current
    // Mugshot — decoded from the sidecar JPEG. Keyed on timestampMs so the
    // decode runs once per event and is cached for the lifetime of the
    // expanded card; runCatching guards a missing/corrupt file (returns
    // null → the block below simply renders nothing).
    if (row.hasMugshot) {
        val bitmap = remember(row.timestampMs) {
            inbox.mugshotFor(row.timestampMs)?.let {
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(it.absolutePath)
                }.getOrNull()
            }
        }
        bitmap?.let { bmp ->
            Text(
                stringResource(R.string.sentinel_inbox_frontcamera_capture),
                fontSize = 10.sp,
                color = AegisOnSurfaceDim,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = stringResource(R.string.sentinel_inbox_mugshot_from_recording_stage),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    // Recording — sparkline triplet of the X/Y/Z accel channels. Same
    // lazy/keyed decode pattern as the mugshot above.
    if (row.hasRecording) {
        val rec = remember(row.timestampMs) {
            inbox.recordingFor(row.timestampMs)?.let {
                SentinelRecording(ctx).read(it)
            }
        }
        rec?.let { r ->
            Text(
                // 100 Hz sample rate, so sample-count / 100 = duration in
                // seconds. Two sparklines follow: accel (here) then gyro.
                "3D-model recording · ${r.samples.size} samples · " +
                    "%.1f s".format(r.samples.size / 100f),
                fontSize = 10.sp,
                color = AegisOnSurfaceDim,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            SparklineTriplet(
                xs = r.samples.map { it.ax },
                ys = r.samples.map { it.ay },
                zs = r.samples.map { it.az },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.sentinel_inbox_gyroscope_rads),
                fontSize = 10.sp,
                color = AegisOnSurfaceDim,
                fontWeight = FontWeight.SemiBold,
            )
            // Second triplet: gyroscope (rad/s) for the same window, so
            // the recipient can distinguish a pick-up (rotation) from a
            // slide (translation only).
            SparklineTriplet(
                xs = r.samples.map { it.gx },
                ys = r.samples.map { it.gy },
                zs = r.samples.map { it.gz },
            )
        }
    }
    // Some stages (e.g. proximity-armed) escalate before any forensic
    // payload is captured — say so explicitly rather than rendering an
    // empty expansion that looks like a load failure.
    if (!row.hasRecording && !row.hasMugshot) {
        Text(
            stringResource(R.string.sentinel_inbox_no_forensic_payload_attached),
            color = AegisOnSurfaceDim,
            fontSize = 11.sp,
        )
    }
}

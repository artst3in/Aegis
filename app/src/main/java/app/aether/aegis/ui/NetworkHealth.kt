package app.aether.aegis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.aether.aegis.AegisApp
import app.aether.aegis.simplex.SimpleXTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

/**
 * Single source of truth for "is the network actually working" — the
 * honest, end-to-end verdict that drives both the app-header status dot
 * and the Diagnostics network card.
 *
 * The whole point: GREEN is a promise. It means messages, calls AND
 * SOS alerts will reach the target right now — not merely that the
 * transport object reports `isHealthy`. The old dot went green the
 * instant the receive pump launched, which could be true while every SMP
 * relay was mid-reconnect or while the outbox was backing up. This model
 * folds every link in the delivery chain into one verdict:
 *
 *   - the secure core is READY (not initialising / failed),
 *   - the receive pump is alive,
 *   - at least one SMP relay is actually connected (and ideally all of
 *     the ones we know about),
 *   - nothing is stuck in the send outbox.
 *
 * Only when every gating check passes do we promise delivery (green).
 * Anything recoverable-but-not-guaranteed is amber; a broken link is red.
 *
 * This object holds NO Android/Compose colour — it's pure logic so it
 * stays testable and the colour mapping lives with the UI.
 */
enum class HealthVerdict {
    /** Every gating check passes — delivery is guaranteed. Green. */
    OPERATIONAL,

    /** Working but a link is weak (coming up, a relay flapping, queued
     *  messages). Recoverable; delivery not guaranteed this instant. Amber. */
    DEGRADED,

    /** A delivery-critical link is broken. Messages will not land until
     *  it recovers. Red. */
    OFFLINE,
}

/** Per-check outcome. PASS = good, WARN = soft trouble (amber), FAIL =
 *  delivery-critical break (red). */
enum class CheckState { PASS, WARN, FAIL }

/** One row in the health breakdown — a named link in the delivery chain
 *  with its own state and a short human detail ("3 connected", "down 12s"). */
data class HealthCheck(
    val label: String,
    val state: CheckState,
    val detail: String,
)

/** The computed verdict plus its supporting breakdown. */
data class NetworkHealth(
    val verdict: HealthVerdict,
    val headline: String,
    val summary: String,
    val checks: List<HealthCheck>,
    /** Connected-relay / total-relay counts, surfaced for the card's
     *  relay sub-list. Both zero when no relay session has opened yet. */
    val relaysUp: Int = 0,
    val relaysTotal: Int = 0,
    /** Continuous 0..1 delivery-health fraction for a SMOOTH status-dot
     *  colour (green → amber → red), distinct from the 3-state [verdict].
     *  1.0 = every relay up and all gates green; it falls proportionally as
     *  relays drop (each one down pulls it toward amber, relative to the
     *  total) and lands at 0.0 when a delivery-critical link is broken
     *  (no connection → red). */
    val healthFraction: Float = 0f,
)

/**
 * True for a SimpleX XFTP (file-transfer) server, which must NOT count toward
 * message-delivery health: those are connected only during an attachment
 * up/download and idle out otherwise. SMP message relays start "smp…", file
 * servers start "xftp…" (the host arrives as a bare hostname, e.g.
 * "xftp1.simplex.im"); matched case-insensitively and tolerant of a leading
 * scheme/userinfo just in case the host ever arrives prefixed.
 */
internal fun isFileRelay(host: String): Boolean {
    val label = host.substringAfterLast('@').substringAfterLast('/')
    return label.startsWith("xftp", ignoreCase = true)
}

// A relay that just dropped is treated as "reconnecting" (amber) rather
// than "down" (red) for this long, so the normal sub-second SMP relay
// cycling doesn't flash the dot red. SimpleX reconnects fast; a relay
// still down past this window is a real outage.
private const val RELAY_GRACE_MS = 12_000L

// Inbound considered "active" if an event arrived within this window.
// Past it we say "idle" — NOT a failure, since a quiet chat legitimately
// receives nothing. Receiving is informational and never gates the
// verdict (we can't prove receive works without someone sending).
private const val RECENT_EVENT_MS = 120_000L

/**
 * Pure verdict computation. [snap] is the SimpleX transport snapshot (null
 * if the transport object doesn't exist yet), [pendingOutbox] the current
 * send-queue depth, [nowMs] the wall clock (injected for testability).
 */
fun computeNetworkHealth(
    snap: SimpleXTransport.NetworkSnapshot?,
    pendingOutbox: Int,
    nowMs: Long,
): NetworkHealth {
    if (snap == null) {
        return NetworkHealth(
            verdict = HealthVerdict.OFFLINE,
            headline = "Offline",
            summary = "The secure transport isn't running.",
            checks = emptyList(),
        )
    }

    // 1. Secure core — the SimpleX engine itself.
    val coreCheck = when (snap.coreState) {
        SimpleXTransport.CoreState.READY ->
            HealthCheck("Secure core", CheckState.PASS, "ready")
        SimpleXTransport.CoreState.INITIALISING ->
            HealthCheck("Secure core", CheckState.WARN, "starting…")
        SimpleXTransport.CoreState.FAILED ->
            HealthCheck("Secure core", CheckState.FAIL, snap.coreError ?: "failed")
    }

    // 2. Receive pump — recvWait loop alive = we can hear inbound traffic.
    val pumpCheck = when {
        snap.transportHealthy -> HealthCheck("Message pump", CheckState.PASS, "listening")
        snap.starting -> HealthCheck("Message pump", CheckState.WARN, "coming up…")
        else -> HealthCheck("Message pump", CheckState.FAIL, snap.startError ?: "down")
    }

    // 3. Relays — the SMP transport hosts our contacts' queues live on.
    //
    // Two things make a naive "every relay must be up" check wrong here:
    //
    //  - XFTP (file-transfer) servers are NOT message relays. SimpleX connects
    //    to them only during an attachment up/download and lets them idle out
    //    otherwise, so an XFTP server being "down" says nothing about whether
    //    messages or SOS land. Exclude them from delivery health entirely.
    //  - SimpleX opens a socket to an SMP relay only when it has live work
    //    there (a queue to watch, a message to push) and closes idle ones, so
    //    the relay list accumulates every server touched this session and
    //    idled-out ones read "down". That is NORMAL — an idle relay reconnects
    //    on demand. A genuine "can't deliver" surfaces as a backed-up outbox
    //    (check 4) or as EVERY relay down, not as some servers idling off.
    //
    // So we require only that AT LEAST ONE SMP relay is up: a partial count is
    // healthy, not degraded. We still show the honest count in the detail.
    val smpRelays = snap.relays.filterNot { isFileRelay(it.host) }
    val total = smpRelays.size
    val up = smpRelays.count { it.connected }
    val relayCheck = when {
        // Nothing to connect to: no relay sessions because no contacts.
        total == 0 && snap.pairedContacts == 0 ->
            HealthCheck("Relays", CheckState.PASS, "no contacts yet")
        // Have contacts but no relay row yet (cold start, subscriptions
        // still opening) — transient.
        total == 0 ->
            HealthCheck("Relays", CheckState.WARN, "connecting…")
        up == 0 -> {
            // Distinguish a brief reconnect blip from a real outage.
            val mostRecentChange = smpRelays.maxOfOrNull { it.sinceMs } ?: 0L
            if (nowMs - mostRecentChange < RELAY_GRACE_MS)
                HealthCheck("Relays", CheckState.WARN, "reconnecting…")
            else
                HealthCheck("Relays", CheckState.FAIL, "all $total down")
        }
        // At least one SMP relay up = the delivery path is live. Idle servers
        // dropping off is normal, not degradation — PASS, but show the count
        // honestly so the card still tells the truth about what's connected.
        else -> HealthCheck(
            "Relays", CheckState.PASS,
            if (up < total) "$up of $total up" else "$up connected",
        )
    }

    // 4. Outbox — anything queued means delivery hasn't happened yet, so
    //    we can't promise it. Queued is recoverable (still retrying), so
    //    amber, not red.
    val outboxCheck = when {
        pendingOutbox <= 0 -> HealthCheck("Outbox", CheckState.PASS, "clear")
        else -> HealthCheck(
            "Outbox", CheckState.WARN,
            "$pendingOutbox queued",
        )
    }

    // 5. Receiving — informational only (never gates the verdict).
    val age = if (snap.lastEventAtMs > 0) nowMs - snap.lastEventAtMs else -1L
    val recvCheck = when {
        age in 0..RECENT_EVENT_MS -> HealthCheck("Receiving", CheckState.PASS, "active")
        else -> HealthCheck("Receiving", CheckState.PASS, "idle")
    }

    val checks = listOf(coreCheck, pumpCheck, relayCheck, outboxCheck, recvCheck)

    // Verdict = worst of the GATING checks (receiving doesn't gate).
    val gating = listOf(coreCheck, pumpCheck, relayCheck, outboxCheck)
    val verdict = when {
        gating.any { it.state == CheckState.FAIL } -> HealthVerdict.OFFLINE
        gating.any { it.state == CheckState.WARN } -> HealthVerdict.DEGRADED
        else -> HealthVerdict.OPERATIONAL
    }

    val (headline, summary) = when (verdict) {
        HealthVerdict.OPERATIONAL ->
            "Operational" to "Messages, calls and SOS alerts will reach your contacts."
        HealthVerdict.DEGRADED ->
            "Degraded" to "Connected, but delivery isn't guaranteed right now."
        HealthVerdict.OFFLINE ->
            "Offline" to "Messages won't be delivered until the connection recovers."
    }

    // Continuous fraction for the smooth dot gradient. Relay-driven — each
    // relay down pulls it toward amber relative to the total, all down toward
    // red — then clamped by the gating links: a core/pump break forces 0
    // (red), a soft warn halves it, a queued outbox dampens it.
    val relayFraction = when {
        total == 0 && snap.pairedContacts == 0 -> 1f          // nothing to connect — fine
        total == 0 -> 0.4f                                    // connecting…
        up == 0 -> {
            val mostRecentChange = smpRelays.maxOfOrNull { it.sinceMs } ?: 0L
            if (nowMs - mostRecentChange < RELAY_GRACE_MS) 0.4f else 0f
        }
        // At least one SMP relay up = delivery path live; idle servers
        // dropping off doesn't dim the dot (same rule as the verdict above).
        else -> 1f
    }
    val gateMul = when {
        coreCheck.state == CheckState.FAIL || pumpCheck.state == CheckState.FAIL -> 0f
        coreCheck.state == CheckState.WARN || pumpCheck.state == CheckState.WARN -> 0.5f
        else -> 1f
    }
    val outboxMul = if (outboxCheck.state == CheckState.WARN) 0.7f else 1f
    val healthFraction = (relayFraction * gateMul * outboxMul).coerceIn(0f, 1f)

    return NetworkHealth(
        verdict = verdict,
        headline = headline,
        summary = summary,
        checks = checks,
        relaysUp = up,
        relaysTotal = total,
        healthFraction = healthFraction,
    )
}

/**
 * Live network health for Compose. Polls the SimpleX snapshot on [pollMs]
 * and folds in the reactive outbox depth. Used by both the header dot and
 * the Diagnostics network card so they can never disagree about whether
 * we're online.
 */
@Composable
fun rememberNetworkHealth(pollMs: Long = 2_000L): NetworkHealth {
    val transport = remember {
        runCatching {
            AegisApp.instance.transports.filterIsInstance<SimpleXTransport>().firstOrNull()
        }.getOrNull()
    }
    val pending by remember {
        runCatching { AegisApp.instance.repository.pendingCount() }
            .getOrDefault(flowOf(0))
    }.collectAsState(initial = 0)

    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(pollMs) {
        while (true) {
            delay(pollMs)
            tick++
        }
    }

    // tick + pending in the key so the verdict recomputes on each poll and
    // whenever the outbox depth changes.
    return remember(tick, pending) {
        computeNetworkHealth(
            snap = transport?.networkSnapshot(),
            pendingOutbox = pending,
            nowMs = System.currentTimeMillis(),
        )
    }
}

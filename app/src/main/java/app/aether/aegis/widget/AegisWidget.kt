package app.aether.aegis.widget

import app.aether.aegis.AegisApp
import app.aether.aegis.MainActivity
import app.aether.aegis.core.ProtocolManager
import app.aether.aegis.core.identifier
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.unit.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.flow.first

/**
 * The "always visible" Aegis home-screen widget — a **family status
 * dashboard**, not a phone book. One glance answers "is everyone I
 * protect OK, and do I need to act?":
 *
 *  - a one-word THREAT level in the header (SAFE / ALERT / CANARY / SOS),
 *    the single most valuable pixel on the home screen;
 *  - per-contact rows with battery and an unread indicator (medium/large);
 *  - the canary countdown and Sentinel status (large only);
 *  - an SOS strip that, critically, does **not** fire SOS — it OPENS the
 *    app to the SOS screen where the deliberate hold-to-fire gesture lives
 *    (see [WidgetSosStrip]).
 *
 * Built on Glance so we get a Compose-style declarative layout inside a
 * RemoteViews surface; reads directly from `AegisApp.instance` because
 * widget receivers run in the app process — there is no separate widget
 * process to marshal across. [provideGlance] does ALL the I/O up front
 * (one-shot `.first()` / `.value` reads) and the @Composable tree stays a
 * pure function of the resulting [WidgetData] snapshot.
 *
 * Glance can't tick: elapsed times and countdowns are snapshotted at
 * render time and only refresh when the host re-renders the widget. That
 * is acceptable for an at-a-glance surface — the live, second-accurate
 * view lives in-app behind the tap.
 */
class AegisWidget : GlanceAppWidget() {

    // Exact: Glance hands us the real cell size so the layout fills the
    // widget box precisely rather than snapping to a responsive bucket.
    // LocalSize.current inside provideContent then drives the small /
    // medium / large feature gating (see [WidgetTier]).
    override val sizeMode = SizeMode.Exact

    /**
     * Build the widget's content tree on each refresh. Pulls a one-shot
     * snapshot of every data source rather than collecting a flow — Glance
     * re-invokes this whenever it needs a new render, so a live
     * subscription here would leak past the render.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val aegisApp = AegisApp.instance
        val selfKey = aegisApp.identity.deviceId
        // Index statuses by peer key so the per-member lookup below is O(1).
        val statuses = aegisApp.repository.statuses().first().associateBy { it.peerKey }
        val protocolState = aegisApp.protocolManager.state.value
        val knownPeers = aegisApp.repository.observeKnownPeers().first()
        // Conversation keys with unread inbound messages (peer pubkey or
        // "group:<id>"). Drives the per-row cyan unread dot.
        val unread = app.aether.aegis.prefs.UnreadTracker.observe().first()

        // Adapt persisted peers into the in-memory FamilyMember shape so the
        // snapshot mapping can reuse `identifier`. meshIp is a vestigial field
        // (LAN transport is gone); a key prefix is a harmless filler here.
        val members = knownPeers.map {
            app.aether.aegis.core.FamilyMember(
                name = it.displayName,
                meshIp = it.publicKey.take(20),
                publicKey = it.publicKey,
            )
        }
        // Flatten member + its latest status into a render-ready snapshot.
        // Nullable battery/network/lastSeen: a peer we've never heard from
        // has no status row yet, which PeerRow renders as an em-dash.
        val peerSnapshots = members
            .filter { it.publicKey != selfKey }  // never list ourselves on the family board
            .map { m ->
                val s = statuses[m.identifier]
                PeerSnapshot(
                    name = m.name,
                    identifier = m.identifier,
                    battery = s?.batteryLevel,
                    network = s?.networkType,
                    lastSeen = s?.lastActive,
                    hasUnread = m.identifier in unread,
                )
            }

        // SOS active snapshot — null when no SOS is in flight. startedAt is
        // rendered as elapsed time in the strip.
        val sos = aegisApp.sosHandler.state.value

        // Canary: armed iff enabled AND the user has checked in at least once
        // (a never-checked-in canary is inert). Remaining can go negative —
        // that's the "expired" / CANARY threat state.
        val canary = app.aether.aegis.canary.CanaryStore(context)
        val canaryArmed = canary.enabled && canary.lastCheckInAt != 0L
        val canaryRemainingMs =
            if (canaryArmed) canary.intervalMs - (System.currentTimeMillis() - canary.lastCheckInAt)
            else 0L

        val sentinelArmed = app.aether.aegis.sentinel.SentinelPrefs(context).armed

        val data = WidgetData(
            peers = peerSnapshots,
            protocol = protocolState,
            sosStartedAt = sos?.startedAt,
            canaryArmed = canaryArmed,
            canaryRemainingMs = canaryRemainingMs,
            sentinelArmed = sentinelArmed,
            nowMs = System.currentTimeMillis(),
        )

        provideContent {
            WidgetContent(data)
        }
    }
}

/**
 * Immutable, render-ready snapshot of everything the widget shows. Built
 * entirely in [AegisWidget.provideGlance] so the @Composable tree is a pure
 * function of it. All time-derived values ([sosStartedAt], [canaryRemainingMs])
 * are relative to [nowMs], captured at snapshot time.
 */
private data class WidgetData(
    val peers: List<PeerSnapshot>,
    val protocol: ProtocolManager.State,
    /** Epoch-ms the active SOS started, or null if no SOS is active. */
    val sosStartedAt: Long?,
    val canaryArmed: Boolean,
    /** Time left on the canary; <= 0 means expired (CANARY threat). */
    val canaryRemainingMs: Long,
    val sentinelArmed: Boolean,
    val nowMs: Long,
)

/**
 * Immutable, render-ready view of one family member. Nullable fields mean
 * "no status received yet", not "zero".
 */
private data class PeerSnapshot(
    val name: String,
    val identifier: String,
    val battery: Int?,
    val network: String?,
    val lastSeen: Long?,
    val hasUnread: Boolean,
)

/** Which feature set a given widget size shows. Cheaper than threading the
 *  DpSize everywhere — resolved once from [LocalSize] at the top of
 *  [WidgetContent]. */
private enum class WidgetTier { SMALL, MEDIUM, LARGE }

/**
 * Family threat level shown as the header dot + word. Computed from all
 * contacts plus the owner's own safety subsystems. Priority order (highest
 * first) per the dashboard design: SOS > CANARY > ALERT > SAFE.
 *
 * BREACH (geofence) is intentionally absent for now — per-contact geofence
 * state isn't surfaced to the widget yet; it slots in here above CANARY when
 * that data path lands.
 */
private enum class Threat(val label: String, val argb: Long) {
    SOS("SOS", 0xFFD32F2F),
    CANARY("CANARY", 0xFFD32F2F),
    ALERT("ALERT", 0xFFFF9800),
    SAFE("SAFE", 0xFF4CAF50),
}

/** One hour, the "offline too long" / "canary getting close" threshold base. */
private const val ONE_HOUR_MS = 60L * 60_000L

/**
 * Fold the snapshot into a single [Threat]. Order matters — the first
 * matching condition wins, mirroring the SOS > CANARY > ALERT > SAFE
 * priority. ALERT trips on any one of: a contact below 15 % battery, a
 * contact silent for over an hour, or the canary having less than 2 h left.
 */
private fun computeThreat(d: WidgetData): Threat {
    if (d.sosStartedAt != null) return Threat.SOS
    if (d.canaryArmed && d.canaryRemainingMs <= 0L) return Threat.CANARY
    val lowBattery = d.peers.any { (it.battery ?: 100) < 15 }
    val staleContact = d.peers.any { it.lastSeen != null && d.nowMs - it.lastSeen!! > ONE_HOUR_MS }
    val canarySoon = d.canaryArmed && d.canaryRemainingMs in 1..(2 * ONE_HOUR_MS)
    // No live transport = we can't broadcast for help. That's the owner's
    // own safety, so it counts as an ALERT, not just a status nicety.
    val offline = d.protocol == ProtocolManager.State.DISCONNECTED
    if (lowBattery || staleContact || canarySoon || offline) return Threat.ALERT
    return Threat.SAFE
}

// --- Dashboard palette (literal because Glance renders to RemoteViews and
// can't read Compose MaterialTheme tokens). Keep brand cyan in sync with
// Theme.kt AegisCyan if it ever changes. ---
private val BG = ColorProvider(Color(0xFF050508))
private val SURFACE = ColorProvider(Color(0xFF0E0E14))
private val CYAN = ColorProvider(Color(0xFF00FFFF))
private val TEXT = ColorProvider(Color(0xFFC8C8D0))
private val DIM = ColorProvider(Color(0xFF666670))
private val SOS_IDLE = Color(0xFFD32F2F)
private val SOS_ACTIVE = Color(0xFFF44336)

// Near-sharp corners: Glance can't do true LunaGlass facets, but 2.dp reads
// angular rather than rounded, matching the in-app aesthetic.
private const val CORNER_DP = 2

/**
 * Root dashboard layout. Resolves the size tier, then stacks: header
 * (brand + threat dot), an optional per-contact list (medium/large), an
 * optional canary/sentinel status block (large), and the SOS strip pinned
 * to the bottom (all sizes). When SOS is active the whole background shifts
 * to a very dark red to signal danger without being garish.
 */
@Composable
private fun WidgetContent(d: WidgetData) {
    val size = LocalSize.current
    // Height-driven tiers (matches the 4×2 / 4×4 / 5×6 design buckets). The
    // dp cutoffs are deliberately generous so a slightly-resized widget keeps
    // its features rather than flickering between tiers.
    val tier = when {
        size.height < 130.dp -> WidgetTier.SMALL
        size.height < 220.dp -> WidgetTier.MEDIUM
        else -> WidgetTier.LARGE
    }
    val threat = computeThreat(d)
    val sosActive = d.sosStartedAt != null
    // SOS-active danger wash on the whole surface (Glance can't colour the
    // widget border, so the background carries the signal instead).
    val background = if (sosActive) ColorProvider(Color(0xFF0A0204)) else BG

    Column(
        modifier = GlanceModifier.fillMaxSize().background(background).padding(12.dp),
    ) {
        WidgetHeader(threat)

        if (tier != WidgetTier.SMALL) {
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(d.peers) { peer -> PeerRow(peer, tier) }
            }
            if (tier == WidgetTier.LARGE) {
                StatusBlock(d)
            }
        } else {
            // Small tier shows only threat + SOS, so push the strip down.
            Box(modifier = GlanceModifier.defaultWeight()) { Text("") }
        }

        WidgetSosStrip(d)
    }
}

/**
 * Header row: the "AEGIS" wordmark (taps open the app) and the family
 * threat indicator (coloured dot + word) pinned to the right.
 */
@Composable
private fun WidgetHeader(threat: Threat) {
    val threatColor = ColorProvider(Color(threat.argb))
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "AEGIS",
            style = TextStyle(
                color = CYAN,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
            ),
            // Tapping the wordmark opens the app to its default screen.
            modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
        )
        // Spacer eats the middle so the threat indicator sits hard-right.
        Box(modifier = GlanceModifier.defaultWeight()) { Text("") }
        // Filled "dot" — Glance has no circle primitive, so a tiny squared
        // box reads as the status pip at this size.
        Box(
            modifier = GlanceModifier
                .size(8.dp)
                .background(threatColor)
                .cornerRadius(CORNER_DP.dp),
        ) { Text("") }
        Box(modifier = GlanceModifier.width(6.dp)) { Text("") }
        Text(
            threat.label,
            style = TextStyle(
                color = threatColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
            ),
        )
    }
}

/**
 * One contact row: name on the left, an unread pip + compact "battery%
 * network" tail on the right. Tapping deep-links into the chat with that
 * peer via [OPEN_CHAT_PARAM], which MainActivity decodes on launch.
 *
 * Per-contact location (geofence zone / distance) is part of the dashboard
 * design but not yet wired — the location data path to the widget lands
 * separately; until then the tail is battery + network as before.
 */
@Composable
private fun PeerRow(peer: PeerSnapshot, tier: WidgetTier) {
    val openChat = actionStartActivity<MainActivity>(
        actionParametersOf(OPEN_CHAT_PARAM to peer.identifier),
    )
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(openChat),
    ) {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(SURFACE)
                .cornerRadius(CORNER_DP.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                peer.name,
                style = TextStyle(
                    color = TEXT,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            // Unread pip: a single cyan dot, shown only when there is unread
            // inbound mail (the most common reason to glance at a widget). We
            // only have has-unread, not an exact count, so it's a dot not a
            // number — an exact count needs a per-conversation tally the
            // tracker doesn't expose yet.
            if (peer.hasUnread) {
                Box(
                    modifier = GlanceModifier
                        .size(8.dp)
                        .background(CYAN)
                        .cornerRadius(CORNER_DP.dp),
                ) { Text("") }
                Box(modifier = GlanceModifier.width(8.dp)) { Text("") }
            }
            Text(
                // Compose the status tail from whatever we have; fall back
                // to an em-dash only when there's truly nothing.
                buildString {
                    peer.battery?.let { append("$it% ") }
                    peer.network?.let { append(it) }
                    if (isEmpty() && peer.lastSeen == null) append("—")
                },
                style = TextStyle(
                    color = DIM,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.SansSerif,
                ),
            )
        }
    }
}

/**
 * Large-tier status block: canary countdown and Sentinel state. Each row is
 * shown only when its feature is armed, and each is independently tappable
 * to open the relevant screen. Hidden entirely when neither is armed.
 */
@Composable
private fun StatusBlock(d: WidgetData) {
    if (!d.canaryArmed && !d.sentinelArmed) return
    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        if (d.canaryArmed) {
            // Amber under 2 h, red under 30 min — the closer the dead-man's
            // switch is to firing, the louder the colour.
            val rem = d.canaryRemainingMs
            val color = when {
                rem <= 30L * 60_000L -> ColorProvider(Color(0xFFD32F2F))
                rem <= 2 * ONE_HOUR_MS -> ColorProvider(Color(0xFFFF9800))
                else -> CYAN
            }
            Text(
                "Canary: ${formatDuration(rem)}",
                style = TextStyle(
                    color = color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                ),
                modifier = GlanceModifier
                    .padding(vertical = 2.dp)
                    .clickable(openRoute("settings/canary")),
            )
        }
        if (d.sentinelArmed) {
            Text(
                "Sentinel: Armed",
                style = TextStyle(
                    color = CYAN,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif,
                ),
                modifier = GlanceModifier
                    .padding(vertical = 2.dp)
                    .clickable(openRoute("settings/experimental")),
            )
        }
    }
}

/**
 * The bottom strip — the one place the old widget was actively dangerous.
 *
 * **It does NOT fire SOS.** A single accidental tap on a home-screen button
 * broadcasting your location + audio is the wrong default; the strip instead
 * OPENS the app to the SOS screen, where the deliberate hold-to-fire gesture
 * lives. The "HOLD SOS →" label sets that expectation.
 *
 * When SOS is already active the strip flips to a live monitor: bright red,
 * "SOS ACTIVE — <elapsed>", and a tap that opens the SOS screen to watch or
 * cancel the broadcast.
 */
@Composable
private fun WidgetSosStrip(d: WidgetData) {
    val active = d.sosStartedAt != null
    val barColor = if (active) SOS_ACTIVE else SOS_IDLE
    val label = if (active) {
        "SOS ACTIVE — ${formatDuration(d.nowMs - d.sosStartedAt!!)}"
    } else {
        "HOLD SOS →"
    }
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(top = 8.dp)
            // Opens the in-app SOS screen (allow-listed route) — never fires.
            .clickable(openRoute("sos")),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(barColor))
                .cornerRadius(CORNER_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                ),
            )
        }
    }
}

/** Open the app to a fixed in-app [route], carried as the `open_route`
 *  extra. MainActivity validates it against its allow-list before any
 *  navigation, so this can only ever reach the handful of screens the
 *  widget links to. */
private fun openRoute(route: String) =
    actionStartActivity<MainActivity>(actionParametersOf(OPEN_ROUTE_PARAM to route))

/** Format a millisecond duration as a compact "Hh Mm" / "Mm Ss" string for
 *  the canary countdown and SOS elapsed line. Negative clamps to 0. */
private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

/** Action-parameter key carrying the peer identifier on a row tap; read by
 *  MainActivity to deep-link into that chat. `internal` so the Activity in
 *  the same module references the exact same key. */
internal val OPEN_CHAT_PARAM = ActionParameters.Key<String>("open_chat")

/** Action-parameter key carrying a fixed in-app route (SOS / canary /
 *  sentinel) for the widget's non-chat taps. Validated against an
 *  allow-list in MainActivity. */
internal val OPEN_ROUTE_PARAM = ActionParameters.Key<String>("open_route")

/** Manifest-registered receiver that binds this Glance widget. */
class AegisWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = AegisWidget()
}

package app.aether.aegis.widget

import app.aether.aegis.AegisApp
import app.aether.aegis.MainActivity
import app.aether.aegis.core.SOSTrigger
import app.aether.aegis.core.ProtocolManager
import app.aether.aegis.core.identifier
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
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
 * The "always visible" Aegis home-screen widget.
 * Renders a snapshot of the family: per-contact
 * status (battery + last-seen), the active protocol band, and a
 * tappable sos button. Each row deep-links into the chat with that
 * contact.
 *
 * Built on Glance so we get a real Compose-style declarative layout
 * inside a RemoteViews surface; reads directly from
 * `AegisApp.instance.repository` because widget receivers run in the
 * aegisApp process.
 */
class AegisWidget : GlanceAppWidget() {

    // Exact: Glance hands us the real cell size so the layout fills the
    // widget box precisely rather than snapping to a responsive bucket.
    override val sizeMode = SizeMode.Exact

    /**
     * Build the widget's content tree on each refresh. Pulls a one-shot
     * snapshot of every data source (`.first()` / `.value`) rather than
     * collecting a flow — Glance re-invokes this whenever it needs a new
     * render, so a live subscription here would leak past the render.
     *
     * Runs in the app process (widget receivers share it), so reaching
     * straight into `AegisApp.instance.repository` is safe — there is no
     * separate widget process to marshal across.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val aegisApp = AegisApp.instance
        val selfKey = aegisApp.identity.deviceId
        // Index statuses by peer key so the per-member lookup below is O(1).
        val statuses = aegisApp.repository.statuses().first().associateBy { it.peerKey }
        val protocolState = aegisApp.protocolManager.state.value
        val knownPeers = aegisApp.repository.observeKnownPeers().first()
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
        val peerSnapshots = members.map { m ->
            val s = statuses[m.identifier]
            PeerSnapshot(
                name = m.name,
                identifier = m.identifier,
                battery = s?.batteryLevel,
                network = s?.networkType,
                lastSeen = s?.lastActive,
                isSelf = m.publicKey == selfKey,
            )
        }

        provideContent {
            WidgetContent(peerSnapshots, protocolState)
        }
    }
}

/**
 * Immutable, render-ready view of one family member for the widget.
 * Decoupled from the DB/entity types so [provideGlance] does all the
 * I/O up front and the @Composable tree stays pure. Nullable fields
 * mean "no status received yet", not "zero".
 */

private data class PeerSnapshot(
    val name: String,
    val identifier: String,
    val battery: Int?,
    val network: String?,
    val lastSeen: Long?,
    val isSelf: Boolean,
)

/**
 * Root widget layout: header (brand + protocol band), a scrollable peer
 * list, and the SOS strip pinned to the bottom. All colours are literal
 * Glance [ColorProvider]s because Glance renders to RemoteViews and can't
 * read Compose [MaterialTheme] tokens.
 */
@Composable
private fun WidgetContent(peers: List<PeerSnapshot>, protocol: ProtocolManager.State) {
    val bg = ColorProvider(Color(0xFF050508))
    val surface = ColorProvider(Color(0xFF0E0E14))
    // Brand cyan (AegisCyan, 0xFF00FFFF), duplicated here as a literal
    // because Glance widgets can't read Compose theme tokens. Keep in sync
    // with Theme.kt AegisCyan if the brand colour ever changes.
    val accent = ColorProvider(Color(0xFF00FFFF))
    val dim = ColorProvider(Color(0xFF666670))

    Column(
        modifier = GlanceModifier.fillMaxSize().background(bg).padding(12.dp),
    ) {
        // Header — "AEGIS" + protocol indicator
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "AEGIS",
                style = TextStyle(
                    color = accent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                ),
                // Tapping the wordmark opens the app to its default screen.
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
            // Fixed spacer: Glance Rows have no `spacedBy`, so width a box.
            Box(modifier = GlanceModifier.width(12.dp)) { Text("") }
            Text(
                protocolLabel(protocol),
                style = TextStyle(
                    color = protocolColor(protocol),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }

        LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            items(peers) { peer ->
                PeerRow(peer, surface, dim, accent)
            }
        }

        // SOS strip — the whole 48 dp bar is the tap target so it's hard to
        // miss under stress. Fires a Glance ActionCallback (runs in-process)
        // rather than launching an Activity, so the panic broadcast goes out
        // without ever bringing the app to the foreground.
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(top = 8.dp)
                .clickable(actionRunCallback<TriggerSOSAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Color(0xFFD32F2F)))
                    .cornerRadius(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "SOS",
                    style = TextStyle(
                        color = ColorProvider(Color.White),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
}

/**
 * One peer row: name on the left, a compact "battery% network" tail on
 * the right. Tapping the row deep-links into the chat with that peer by
 * passing the peer's [PeerSnapshot.identifier] through [OPEN_CHAT_PARAM],
 * which MainActivity reads on launch to route to the conversation.
 */
@Composable
private fun PeerRow(
    peer: PeerSnapshot,
    surface: ColorProvider,
    dim: ColorProvider,
    accent: ColorProvider,
) {
    // Deep-link payload: the peer identifier, decoded by MainActivity into
    // a chat-screen navigation on cold/warm start.
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
                .background(surface)
                .cornerRadius(8.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                peer.name,
                style = TextStyle(
                    color = ColorProvider(Color(0xFFC8C8D0)),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                ),
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                // Compose the status tail from whatever we have; fall back
                // to an em-dash only when there's truly nothing (no battery,
                // no network, and never seen).
                buildString {
                    peer.battery?.let { append("$it% ") }
                    peer.network?.let { append(it) }
                    if (isEmpty() && peer.lastSeen == null) append("—")
                },
                style = TextStyle(
                    color = dim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }
}

/** Short human label for the transport band shown in the header. */
private fun protocolLabel(state: ProtocolManager.State): String = when (state) {
    ProtocolManager.State.SIMPLEX_ACTIVE   -> "simplex · ok"
    ProtocolManager.State.RECONNECTING     -> "reconnecting…"
    ProtocolManager.State.DISCONNECTED     -> "offline"
}

/** Colour for the protocol band: amber=active, blue=reconnecting,
 *  red=offline. Wrapped in a Glance ColorProvider (no theme access). */
private fun protocolColor(state: ProtocolManager.State): ColorProvider = ColorProvider(when (state) {
    ProtocolManager.State.SIMPLEX_ACTIVE   -> Color(0xFFFF9800)
    ProtocolManager.State.RECONNECTING     -> Color(0xFF2196F3)
    ProtocolManager.State.DISCONNECTED     -> Color(0xFFF44336)
})

/** Action-parameter key carrying the peer identifier on a row tap; read
 *  by MainActivity to deep-link into that chat. `internal` so the
 *  Activity in the same module can reference the exact same key. */
internal val OPEN_CHAT_PARAM = ActionParameters.Key<String>("open_chat")

/**
 * Glance callback for the SOS strip. Runs in-process and fires the panic
 * broadcast via the shared [sosHandler] WITHOUT opening the app — so a
 * one-tap SOS from the home screen never lights up a foreground UI that
 * an attacker could see.
 */
class TriggerSOSAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        AegisApp.instance.sosHandler.trigger(SOSTrigger.BUTTON)
    }
}

/** Manifest-registered receiver that binds this Glance widget. */
class AegisWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = AegisWidget()
}

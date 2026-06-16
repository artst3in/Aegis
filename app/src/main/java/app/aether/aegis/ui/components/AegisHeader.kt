package app.aether.aegis.ui.components

import app.aether.aegis.AegisApp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * LunaGlass top bar — "AEGIS" left (letter-spaced cyan with glow),
 * protocol status right. Shown on every tab route; sub-routes
 * (chat/x, profile, contact/x, etc.) hide it via the Scaffold's
 * isTabRoute check and bring their own context-specific top bar.
 *
 * The Notes 📓 button on the right of the chat-list header was the
 * design call to keep the 5-tab nav clean; we attach it here when
 * the current route is "chats".
 */
@Composable
fun AegisHeader(navController: NavController, currentRoute: String?) {
    // The header dot now reflects the honest end-to-end delivery verdict
    // (NetworkHealth), not just transport.isHealthy — green ONLY when
    // messages, calls and SOS alerts are actually guaranteed to land. It
    // beats: a calm green heartbeat when covered, a quicker amber one when
    // degraded, still red when down. Same computation + pulse the
    // Diagnostics card uses, so the dot and the card can never disagree.
    val health = app.aether.aegis.ui.rememberNetworkHealth()

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val debugPrefs = androidx.compose.runtime.remember { app.aether.aegis.ui.DebugPrefs(ctx) }
    // Granular debug overlay: the header strip shows the counter and/or
    // the frame graph independently, per the Diagnostics debug menu.
    val debugItems by debugPrefs.enabledFlow.collectAsState()
    val showCounter = debugItems.contains(app.aether.aegis.ui.DebugPrefs.Item.COUNTER.key)
    val showGraph = debugItems.contains(app.aether.aegis.ui.DebugPrefs.Item.GRAPH.key)
    val sentinelEngine = androidx.compose.runtime.remember(ctx) {
        app.aether.aegis.sentinel.SentinelState.engine(ctx)
    }
    val sentinelStage by sentinelEngine.stage.collectAsState()

    // Fixed, always-pinned top bar: AEGIS left, LOCK dead-centre,
    // and the right cluster in a stable order — from the right edge:
    // network dot, help, vault. Items no longer reflow as conditions
    // toggle; each keeps its slot.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Solid, opaque chrome: the bar is fixed furniture like
            // a Windows title bar — content ends UNDER it, nothing bleeds
            // through. (Was alpha 0.88, which let the map show through.)
            .background(MaterialTheme.colorScheme.background)
            // Push below the system status bar — we're drawing edge-to-edge,
            // so without this the AEGIS wordmark collides with the time /
            // notification icons.
            .windowInsetsPadding(WindowInsets.statusBars)
            // Tighter vertical padding — the statusBars inset already keeps
            // us clear of the notification bar, so the extra 14dp was wasted
            // space. 8dp pulls the bar up a touch.
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // AEGIS wordmark — pinned left. Same in real + duress modes: the
        // attacker must not be able to tell which mode they're in, and the
        // wordmark is exactly what they saw on the lock screen.
        Text(
            stringResource(R.string.tutorial_aegis),
            color = AegisCyan,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
            style = MaterialTheme.typography.titleLarge.copy(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = AegisCyan.copy(alpha = 0.5f),
                    blurRadius = 12f,
                ),
            ),
            modifier = Modifier.align(Alignment.CenterStart).crownShimmer(),
        )

        // Lock — pinned dead CENTRE of the bar. The visual hint that the
        // two-finger-drag-down lock gesture exists; tapping it fires the
        // same lockManual. Only meaningful when a PIN exists.
        if (currentRoute in TAB_ROUTES && AegisApp.instance.lockState.store.hasPin) {
            // Size the IMAGE to 24dp (not a wrapping Box) and centre it,
            // exactly like the vault/help icons. Previously the image sat
            // unsized at the Box's TopStart, so it rendered at its intrinsic
            // size off-centre — the "lock sits slightly low" misalignment.
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(
                    app.aether.aegis.R.drawable.ic_aegis_lock,
                ),
                contentDescription = stringResource(R.string.header_lock_now),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(AegisCyan),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(24.dp)
                    .crownShimmer()
                    .clickable { AegisApp.instance.lockState.lockManual() },
            )
        }

        // Right cluster — pinned right, fixed order. From the right edge:
        // network dot, then help, then vault. The sentinel-armed chip
        // tucks in to the left when armed; the debug strip (dev-only) sits
        // past the dot when enabled.
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Sentinel-armed indicator — minimal cyan shield chip, lit when
            // the cascade is armed. Tap → SonarScreen (arm/disarm switch).
            if (sentinelStage != app.aether.aegis.sentinel.SentinelStage.OFF) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        app.aether.aegis.R.drawable.ic_aegis_security,
                    ),
                    contentDescription = stringResource(R.string.header_sentinel_armed),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(AegisCyan),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { navController.navigate("settings/sonar") },
                )
            }
            if (currentRoute in TAB_ROUTES) {
                // Vault (secure notes) — a security tool, on every tab so
                // the user can drop a note from wherever they are.
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        app.aether.aegis.R.drawable.ic_aegis_vault,
                    ),
                    contentDescription = stringResource(R.string.secure_notes_vault),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(AegisCyan),
                    modifier = Modifier
                        .size(24.dp)
                        .crownShimmer()
                        .clickable { navController.navigate("notes") },
                )
                // Help — opens the in-app docs (Origins + manual / privacy).
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        app.aether.aegis.R.drawable.ic_aegis_help,
                    ),
                    contentDescription = stringResource(R.string.help_help),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(AegisCyan),
                    modifier = Modifier
                        .size(24.dp)
                        .crownShimmer()
                        .clickable { navController.navigate("help") },
                )
            }
            // Network status heartbeat — far right. Beats per the honest
            // delivery verdict; taps into Diagnostics.
            StatusHeartbeatDot(
                verdict = health.verdict,
                fraction = health.healthFraction,
                modifier = Modifier.clickable { navController.navigate("diagnostics") },
            )
            // Debug strip — tiny FPS + sparkline + heap + net counters.
            // Default off; pick the pieces in Diagnostics → Debug overlay.
            if (showCounter || showGraph) {
                DebugStrip(showCounter = showCounter, showGraph = showGraph)
            }
        }
    }
    // 1px cyan divider under the header — the exact same line the bottom
    // nav draws on top, so the two bars read as one coherent frame.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AegisCyan.copy(alpha = 0.5f)),
    )
}

/** Set of root tab routes, mirrored from MainActivity. Sub-routes
 *  (chat/x, profile, settings/x …) bring their own top bar; this
 *  list is what the header uses to decide whether to surface the
 *  per-tab shortcuts (notes, etc.). */
private val TAB_ROUTES = setOf("chats", "map", "sos", "security", "settings")

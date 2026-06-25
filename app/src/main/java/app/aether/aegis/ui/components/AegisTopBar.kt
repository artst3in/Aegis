package app.aether.aegis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aether.aegis.ui.theme.AegisCyan

/**
 * Fixed content-band height for THE Aegis bar. Tuned to fit a TWO-LINE title
 * (rendered at titleMedium) so a wrapping title — "Disappearing default",
 * "Remote Access", a peer name + subtitle — cannot grow the bar or shift the
 * pinned back-arrow / [ActionCluster]. The whole point: this number is the same
 * on every screen, so the cluster and the cyan divider land in the exact same
 * place whether the left cell is one line or two (user report: bar drifted
 * between 1- and 2-line screens).
 */
internal val AEGIS_HEADER_HEIGHT = 50.dp

/**
 * THE single Aegis bar — one frame used on every screen; only the [left] cell
 * changes (AEGIS wordmark on tabs via [AegisHeader]; back-arrow + title on
 * sub-screens via [AegisTopBar]). The right side is always the shared
 * [ActionCluster] (preceded by any per-screen [actions]), pinned at a fixed
 * height so it never moves.
 *
 * Layout is a Row at [AEGIS_HEADER_HEIGHT]: the left cell takes the flexible
 * space (`weight(1f)`) and a long/2-line title wraps WITHIN that band; the
 * actions + cluster are separate fixed-width children, so the title can never
 * push them. Vertical centering in a FIXED height means a 1-line and a 2-line
 * left cell both centre to the same baseline — the cluster + divider don't budge.
 */
@Composable
fun AegisBarFrame(
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.statusBars,
    actions: @Composable RowScope.() -> Unit = {},
    left: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(windowInsets)
                .height(AEGIS_HEADER_HEIGHT)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) { left() }
            actions()
            ActionCluster()
        }
        // 1px cyan hairline — the same line the bottom nav draws on top, so the
        // two bars read as one coherent frame.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AegisCyan.copy(alpha = 0.5f)),
        )
    }
}

/**
 * The sub-screen top bar — same slots/signature as before, so the ~47 call
 * sites are unchanged. But it no longer DRAWS a bar: it publishes its
 * title/back/actions (tagged with this screen's route) into [AegisBarSlot], and
 * the ONE persistent bar mounted in MainActivity (outside the NavHost) renders
 * it via [AegisSubBar]. That's what makes the bar stay mounted across
 * navigation and only swap the title, instead of the whole bar (chrome,
 * cluster, badges) being torn down and rebuilt on every screen change (user
 * report). [modifier] / [windowInsets] are now no-ops (the persistent bar owns
 * its inset) — kept only for call-site compatibility.
 */
@Composable
fun AegisTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = WindowInsets.statusBars,
) {
    val nav = LocalNavController.current
    val content = AegisBarContent(navigationIcon = navigationIcon, actions = actions, title = title)
    // Re-publish every recomposition so a dynamic title (peer/group name) keeps
    // the persistent bar current. Tagged with the route so the bar shows it only
    // on the matching destination (no stale bar on screens that don't publish).
    androidx.compose.runtime.SideEffect {
        AegisBarSlot.current = (nav?.currentBackStackEntry?.destination?.route) to content
    }
    // Renders nothing — the persistent bar does the drawing.
}

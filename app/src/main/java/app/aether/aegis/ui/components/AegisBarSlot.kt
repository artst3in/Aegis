package app.aether.aegis.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The per-screen content a sub-screen wants in THE persistent top bar: its
 * back button, screen title, and any per-screen actions. Carried as composable
 * lambdas so dynamic titles (a peer/group name) update live.
 */
class AegisBarContent(
    val navigationIcon: @Composable () -> Unit,
    val actions: @Composable RowScope.() -> Unit,
    val title: @Composable () -> Unit,
)

/**
 * Single shared slot that lets a sub-screen (inside the NavHost) feed its
 * title/back/actions UP to the ONE persistent bar that lives in [MainActivity]
 * (outside the NavHost). This is what makes the bar "stay and just swap the
 * title" instead of being torn down and rebuilt on every navigation (user
 * report: the whole bar — chrome, cluster, badges — redrew on each screen).
 *
 * [current] is `route to content`: the route the content was published for,
 * paired with the content. The persistent bar renders it ONLY when the route
 * matches the current destination — so a screen that doesn't publish (the
 * custom chat/group headers, chrome-free surfaces, tab routes) never shows a
 * stale bar, and we need no hand-maintained exclusion list. [AegisTopBar]
 * publishes here every recomposition (so dynamic titles refresh); the match is
 * by route template, which both sides read from the NavController.
 */
object AegisBarSlot {
    var current by mutableStateOf<Pair<String?, AegisBarContent>?>(null)
}

/**
 * Renders [content] inside the shared [AegisBarFrame] — the sub-screen variant
 * of the persistent bar (back + title left, per-screen actions + the shared
 * ActionCluster right). Title at titleMedium so a two-line title still fits the
 * fixed band. Invoked once by [MainActivity]; never recreated on navigation.
 */
@Composable
fun AegisSubBar(content: AegisBarContent) {
    AegisBarFrame(actions = content.actions) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content.navigationIcon()
            Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                ) {
                    ProvideTextStyle(MaterialTheme.typography.titleMedium) { content.title() }
                }
            }
        }
    }
}

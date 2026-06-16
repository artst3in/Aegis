package app.aether.aegis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.R
import app.aether.aegis.ui.TabOrderPrefs
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.GlassPanel

/**
 * User-rearrangeable bottom-nav tabs.
 *
 * Four non-sos tabs (Security, Settings, Chats, Radar) shown in
 * their current order with ▲/▼ arrows to swap neighbours. SOS is
 * locked at the centre slot (non-negotiable; the central position is
 * the whole point of the criticality argument).
 *
 * Drag-reorder would be nicer than arrow buttons, but ships in a
 * future polish pass — arrows are a one-evening change with zero
 * extra dependencies and let users put tabs wherever the fuck they
 * want today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabOrderSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { TabOrderPrefs(context) }
    // The persisted order is a StateFlow; collecting it means a move()
    // below re-emits and this screen recomposes with the new ordering
    // without any local mirror state. SOS is NOT in this list — it's
    // pinned centre and rendered separately.
    val order by prefs.nonSOSOrder.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_order_nav_order)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        AegisIcon(AegisIcons.Back, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.tab_order_reorder_bottom_nav), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.tab_order_sos_stays_in_the) +
                            "Reorder the other four with the ▲/▼ arrows. " +
                            "The final layout is rendered as " +
                            "[1] [2] SOS [3] [4].",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            // One row per reorderable tab. ▲ is disabled on the first row,
            // ▼ on the last — move() swaps a neighbour and the collected
            // flow drives the recomposition.
            order.forEachIndexed { index, route ->
                NavOrderRow(
                    label = labelFor(route),
                    iconRes = iconFor(route),
                    canMoveUp = index > 0,
                    canMoveDown = index < order.size - 1,
                    onUp = { prefs.move(index, index - 1) },
                    onDown = { prefs.move(index, index + 1) },
                )
            }

            // SOS — shown but disabled so users see WHY it's not in
            // the rearrangeable list. Rendered as a static panel (no
            // arrows, error-red icon) rather than omitted entirely, so its
            // absence from the list above doesn't read as a missing feature.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AegisIcon(
                        icon = R.drawable.ic_aegis_sos,
                        contentDescription = stringResource(R.string.tab_order_sos),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.tab_order_sos_locked_centre), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.tab_order_always_at_the_middle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // Restore the built-in default ordering. prefs.reset() re-emits
            // on the flow, so the rows above reorder without any extra
            // local-state plumbing.
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { prefs.reset() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.tab_order_reset_to_default)) }
        }
    }
}

/**
 * A single reorderable tab row: icon + label on the left, ▲/▼ buttons on
 * the right. The arrows are greyed (onSurfaceVariant) when disabled at a
 * list boundary, cyan when actionable — a purely visual affordance; the
 * IconButton's own `enabled` is what actually blocks the tap.
 */
@Composable
private fun NavOrderRow(
    label: String,
    iconRes: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AegisIcon(
                icon = iconRes,
                contentDescription = label,
                tint = app.aether.aegis.ui.theme.AegisCyan,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onUp, enabled = canMoveUp) {
                Text("▲", fontSize = 18.sp,
                    color = if (canMoveUp) app.aether.aegis.ui.theme.AegisCyan
                            else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDown, enabled = canMoveDown) {
                Text("▼", fontSize = 18.sp,
                    color = if (canMoveDown) app.aether.aegis.ui.theme.AegisCyan
                            else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Map a stored nav route key to its localised display label. @Composable
 * because it pulls from string resources. The "map" route is surfaced to
 * the user as "Radar". Unknown routes fall back to the raw key rather
 * than crashing — defensive against a stale persisted ordering.
 */
@Composable
private fun labelFor(route: String): String = when (route) {
    "security" -> stringResource(R.string.nav_security)
    "settings" -> stringResource(R.string.nav_settings)
    "chats"    -> stringResource(R.string.nav_chats)
    "map"      -> stringResource(R.string.nav_radar)
    else        -> route
}

/** Route-key → drawable for the row icon. Unknown keys fall back to the
 *  chats icon (arbitrary but safe; mirrors [labelFor]'s lenient default). */
private fun iconFor(route: String): Int = when (route) {
    "security" -> R.drawable.ic_aegis_security
    "settings" -> R.drawable.ic_aegis_settings
    "chats"    -> R.drawable.ic_aegis_chats
    "map"      -> R.drawable.ic_aegis_radar
    else        -> R.drawable.ic_aegis_chats
}

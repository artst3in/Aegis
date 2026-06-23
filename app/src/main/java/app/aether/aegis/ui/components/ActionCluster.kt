package app.aether.aegis.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aether.aegis.AegisApp
import app.aether.aegis.R
import app.aether.aegis.ui.theme.AegisCyan

/**
 * The shared right-side action cluster — ONE "Lego brick" placed in every
 * header (tab [AegisHeader], the per-screen [AegisTopBar], the chat header) so
 * the same safety actions are one tap away on every screen.
 *
 * Order, left→right: **help · vault · alert · ●status · 🔒lock**. Lock sits at
 * the far edge because, by Fitts's Law, the corner is the fastest target to
 * hit and the panic-lock is the most urgent action; status (passive, you
 * glance at it) sits just inside it. (Placed in a TopAppBar `actions` slot the
 * last item lands at the far-right corner — exactly where lock belongs.)
 *
 * Sources the NavController from [LocalNavController] so it needs no per-call
 * wiring; renders nothing if none is provided (defensive). Lock is shown only
 * when a PIN exists (nothing to lock to otherwise).
 */
@Composable
fun ActionCluster() {
    val nav = LocalNavController.current ?: return
    // Same honest delivery verdict the Diagnostics card uses, so the dot here
    // and the card can never disagree.
    val health = app.aether.aegis.ui.rememberNetworkHealth()
    val hasPin = AegisApp.instance.lockState.store.hasPin

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Help — in-app docs (Origins + manual / privacy). Innermost: always
        // present, but least-used so it gets the least-reachable slot.
        Image(
            painter = painterResource(R.drawable.ic_aegis_help),
            contentDescription = stringResource(R.string.help_help),
            colorFilter = ColorFilter.tint(AegisCyan),
            modifier = Modifier.size(22.dp).crownShimmer()
                .clickable { nav.navigate("help") },
        )
        // Vault (secure notes) — drop a note from anywhere.
        Image(
            painter = painterResource(R.drawable.ic_aegis_vault),
            contentDescription = stringResource(R.string.secure_notes_vault),
            colorFilter = ColorFilter.tint(AegisCyan),
            modifier = Modifier.size(22.dp).crownShimmer()
                .clickable { nav.navigate("notes") },
        )
        // Alert Center bell — actionable issues (permissions, battery, canary,
        // sentinel, pending invites) in one non-invasive badge.
        AlertBell(navController = nav)
        // Network status heartbeat — glanceable; taps into Diagnostics.
        StatusHeartbeatDot(
            verdict = health.verdict,
            fraction = health.healthFraction,
            modifier = Modifier.clickable { nav.navigate("diagnostics") },
        )
        // Lock — far-right corner = fastest target for the most urgent action.
        // Hint + tap-shortcut for the two-finger drag-down lock gesture; only
        // meaningful when a PIN exists.
        if (hasPin) {
            Image(
                painter = painterResource(R.drawable.ic_aegis_lock),
                contentDescription = stringResource(R.string.header_lock_now),
                colorFilter = ColorFilter.tint(AegisCyan),
                modifier = Modifier.size(22.dp).crownShimmer()
                    .clickable { AegisApp.instance.lockState.lockManual() },
            )
        }
    }
}

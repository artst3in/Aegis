package app.aether.aegis.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.aether.aegis.ui.theme.AegisCyan
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Notes should be reachable from EVERY
 * screen. On TAB routes, AegisHeader already carries Notes + Help
 * next to the SimpleX status, and every meaningful SUB-route either
 * has its own TopAppBar (settings sub-pages, contact detail, photo
 * viewer, etc.) or is a focus surface where ambient icons would be
 * a distraction.
 *
 * The overlay used to render hex-wrapped icons in a banner row that
 * stole visible real-estate at the top of the chat. Now it's a
 * narrow last-resort fallback: bare icons (no hex chrome) in a
 * tight top-right corner, shown only on the small set of sub-
 * routes that don't already carry actions.
 */
private val TAB_ROUTES = setOf("chats", "map", "sos", "settings", "security")

@Composable
fun GlobalActionsOverlay(navController: NavController) {
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val hidden = route.isBlank() ||
        // Tab routes — AegisHeader has the icons inline.
        route in TAB_ROUTES ||
        // Sub-routes with their own TopAppBar / inline header.
        route.startsWith("chat/") ||
        route.startsWith("contact/") ||
        route.startsWith("settings/") ||
        route.startsWith("help") ||
        route.startsWith("note") ||
        route == "notes" ||
        route.startsWith("origin") ||
        route.startsWith("status/") ||
        route.startsWith("photo/") ||
        route.startsWith("language") ||
        // Focus / takeover surfaces.
        route == "splash" ||
        route == "sos" ||
        route.startsWith("sos/") ||
        route.startsWith("call/") ||
        route.startsWith("lock")
    if (hidden) return

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Bare icons matching AegisHeader's style — no hex
            // wrapper, no ripple, just the tinted glyph.
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { navController.navigate("help") },
            ) {
                AegisIcon(
                    icon = AegisIcons.Help,
                    contentDescription = stringResource(R.string.help_help),
                    tint = AegisCyan,
                )
            }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { navController.navigate("notes") },
            ) {
                AegisIcon(
                    icon = AegisIcons.Vault,
                    contentDescription = stringResource(R.string.secure_notes_vault),
                    tint = AegisCyan,
                )
            }
        }
    }
}

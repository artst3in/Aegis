package app.aether.aegis.ui.screens

import app.aether.aegis.prefs.InvitationExpiryPrefs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.GlassPanel
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Invitation-link expiry picker.
 *
 * Pending 1:1 invite links auto-revoke after the chosen window; the
 * hourly [app.aether.aegis.invite.PendingInvitationExpiryWorker]
 * enforces it. "Never" disables auto-expiry — links then persist
 * until used or manually revoked from the Pending Invitations list in
 * Comms. Default is 24 h (set in [InvitationExpiryPrefs]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvitationExpirySettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { InvitationExpiryPrefs(context) }
    // Compose mirror of the persisted expiry window (in hours; one of the
    // PRESETS, where the "Never" preset disables auto-expiry). Edits write
    // through to prefs so the hourly worker reads the new window.
    var hours by remember { mutableStateOf(prefs.expiryHours) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.invitation_expiry_invitation_expiry)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Explainer panel: localized lead sentence + hard-coded tail
            // noting that the user can always revoke a link by hand from the
            // Pending Invitations list in Comms (independent of auto-expiry).
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.invitation_expiry_about_this_setting), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.invitation_expiry_a_11_invite_link) +
                            "someone connects with it. To limit exposure, Aegis " +
                            "auto-revokes unused links after this window. You can " +
                            "always revoke a link by hand from the Pending " +
                            "Invitations list in Comms.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            // One radio row per preset window. Single source of truth for the
            // option list + their display labels is InvitationExpiryPrefs, so
            // adding/removing a window is a one-place change. Whole row is
            // clickable (larger tap target); both paths do the same
            // mirror+persist with the same selected-by-equality check.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(4.dp)) {
                    InvitationExpiryPrefs.PRESETS.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    hours = preset
                                    prefs.expiryHours = preset
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = hours == preset,
                                onClick = {
                                    // Mirror then persist (same as the row's
                                    // clickable handler above).
                                    hours = preset
                                    prefs.expiryHours = preset
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(InvitationExpiryPrefs.label(preset))
                        }
                    }
                }
            }
        }
    }
}

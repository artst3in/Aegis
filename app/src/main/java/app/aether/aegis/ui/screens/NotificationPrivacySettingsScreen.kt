package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisTopBar
import app.aether.aegis.ui.components.HexRadio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.prefs.NotificationPrivacy
import app.aether.aegis.prefs.NotificationPrivacyPrefs
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.GlassPanel

/**
 * Picker for how much of a notification's content is shown on the shade /
 * lock screen — Full / Name only / Hidden. For a safety app this is a
 * first-class privacy control: anyone who can see the shade can otherwise
 * read who is messaging the user and what they say.
 *
 * Writes through to [NotificationPrivacyPrefs] immediately; the choice
 * applies to the next notification posted. SOS / duress alerts ignore this
 * setting (safety overrides privacy) — that exemption is enforced in the
 * notification builders, not here.
 *
 * Strings are inline English pending translation extraction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPrivacySettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { NotificationPrivacyPrefs(context) }
    // Compose mirror of the persisted level; edits persist on selection.
    var level by remember { mutableStateOf(prefs.level) }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = { Text("Notification privacy") },
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
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("What shows on the shade", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Controls how much a message notification reveals to " +
                            "anyone who can see your screen. SOS and duress " +
                            "alerts always show in full — safety overrides this.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            // Each option is a tappable row with a radio + a one-line example
            // of exactly what a notification will look like at that level.
            PrivacyOptionRow(
                title = "Show name and content",
                example = "Zippy: See you at 6pm",
                selected = level == NotificationPrivacy.FULL,
            ) {
                level = NotificationPrivacy.FULL
                prefs.level = NotificationPrivacy.FULL
            }
            PrivacyOptionRow(
                title = "Show name only",
                example = "Zippy: New message",
                selected = level == NotificationPrivacy.NAME_ONLY,
            ) {
                level = NotificationPrivacy.NAME_ONLY
                prefs.level = NotificationPrivacy.NAME_ONLY
            }
            PrivacyOptionRow(
                title = "Hide everything",
                example = "Aegis: New message",
                selected = level == NotificationPrivacy.HIDDEN,
            ) {
                level = NotificationPrivacy.HIDDEN
                prefs.level = NotificationPrivacy.HIDDEN
            }
        }
    }
}

/**
 * One radio option: title, a "this is what you'll see" example line, and a
 * leading radio button. The whole panel is the tap target.
 */
@Composable
private fun PrivacyOptionRow(
    title: String,
    example: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HexRadio(selected = selected, onClick = onSelect)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    example,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

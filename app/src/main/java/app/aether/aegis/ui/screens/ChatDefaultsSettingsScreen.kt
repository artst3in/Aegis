package app.aether.aegis.ui.screens

import app.aether.aegis.prefs.ChatDefaultsPrefs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.components.LogPeriodSlider
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Picker for the default disappearing-messages TTL applied to NEW
 * chats. Existing chats keep their own TTL (set per-contact in
 * ContactDetailScreen or ChatScreen). Provides a Signal-style
 * default-burn option.
 *
 * Picker uses the shared [LogPeriodSlider] per the user's "everywhere
 * with periods, it should be this slider" feedback. Range: 1 minute
 * (lower snap = "Off / no auto-burn", emitted as `null`) up to 1 year
 * (upper snap = "Never", also `null`). Mid-travel is log-even so the
 * common 1-day / 1-week zone is comfortably resolvable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDefaultsSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { ChatDefaultsPrefs(context) }
    // Compose mirror of the persisted default TTL in seconds, or null for
    // "no auto-burn". Edits write through to prefs immediately so the value
    // is applied to the next NEW chat created. Only affects new chats.
    var ttl by remember { mutableStateOf(prefs.defaultDisappearingTtlSeconds) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string._disappearing_default)) },
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
            // Explainer panel. Reuses the generic "About this setting" header
            // string. Stresses that this default applies only to NEW chats;
            // existing chats keep their per-contact TTL.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.invitation_expiry_about_this_setting), fontWeight = FontWeight.SemiBold)
                    Text(
                        "When you add a new contact, messages will auto-burn " +
                            "after this period. Existing chats keep their own " +
                            "TTL — adjust those in each Contact's detail screen.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Default auto-burn", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    // Shared log-scale period slider (used everywhere a TTL is
                    // chosen so the feel is consistent). Range 1 minute .. 1
                    // year; both end snaps emit null (instant/never both mean
                    // "no auto-burn here"). Each change mirrors then persists.
                    LogPeriodSlider(
                        valueSeconds = ttl,
                        onValueChange = { v ->
                            ttl = v
                            prefs.defaultDisappearingTtlSeconds = v
                        },
                        minSeconds = 60.0,                 // lower bound: 1 minute
                        maxSeconds = 365.0 * 24 * 3600,    // upper bound: 1 year (365 days)
                        instantSeconds = null,             // lower snap → null = "Off"
                        neverSeconds = null,               // upper snap → null = "Never"
                        allowNever = true,
                        instantLabel = "Off",
                        neverLabel = "Never",
                    )
                }
            }
        }
    }
}

package app.aether.aegis.ui.screens

import app.aether.aegis.simswap.SimSwapStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.ui.components.GlassPanel

/**
 * Single-toggle settings screen for the SIM-swap alert.
 *
 * WHY this exists: a stolen phone is often "laundered" by ejecting the
 * owner's SIM and inserting the thief's. Detecting that swap is a strong
 * tamper signal, so this screen lets the user arm/disarm a broadcast that
 * fires to every paired peer the moment the active SIM changes (carrier
 * name in/out). The actual detection + queuing lives in [SimSwapStore] and
 * its receiver; this screen ONLY surfaces the enabled flag and the
 * last-seen carrier — it does no detection itself.
 *
 * The whole feature is gated behind [PinGuardedContent]: an attacker who
 * already has the phone must not be able to silently disarm the very alert
 * that would expose them, so the body is hidden until the AUTH PIN proves
 * the operator is the owner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimSwapSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    // Store is the single source of truth (SharedPreferences-backed); the
    // local `enabled` mirror below is what Compose observes for redraws.
    val store = remember { SimSwapStore(context) }
    // Snapshot the persisted flag once into Compose state. Writes go to BOTH
    // this mirror (for recomposition) and back to the store (for durability).
    var enabled by remember { mutableStateOf(store.enabled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sim_swap_sim_swap_alert)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Text("←", fontSize = 20.sp)
                    }
                },
            )
        },
    ) { padding ->
        // PIN gate: the whole settings body only renders after the owner
        // proves the AUTH PIN. Prevents a thief who has the unlocked phone
        // from disarming the swap alert that would otherwise out them.
        app.aether.aegis.ui.components.PinGuardedContent(
            navController = navController,
            featureLabel = stringResource(R.string.security_sim_watch),
        ) {
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Explainer panel: the leading sentence is localized; the tail is
            // hard-coded English describing the queue-until-network behaviour
            // (offline at swap time → alert is held and flushed once any link,
            // Wi-Fi or the new SIM, comes up).
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.settings_how_it_works), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.sim_swap_when_the_sim_in) +
                            "to every paired peer with the old and new carrier names. " +
                            "If cellular is down at the moment of the swap the alert " +
                            "queues until network returns — either over Wi-Fi, or from " +
                            "the new SIM's connection once the thief brings it online.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            // Arm/disarm row. The subtitle, when armed, shows the carrier the
            // store last recorded as the baseline — that is the value a future
            // swap is compared against; blank falls back to "unknown".
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_enabled), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (enabled) stringResource(R.string.loki_sim_armed) + " " +
                                store.lastCarrier.ifBlank { "unknown" }
                            else "Off — no alert will fire on SIM change.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    app.aether.aegis.ui.components.HexSwitch(
                        checked = enabled,
                        onCheckedChange = {
                            // Mirror into Compose state first (drives the
                            // subtitle redraw), then persist to the store.
                            enabled = it
                            store.enabled = it
                        },
                    )
                }
            }
        }
        }
    }
}

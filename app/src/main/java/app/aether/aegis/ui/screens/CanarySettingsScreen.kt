package app.aether.aegis.ui.screens

import app.aether.aegis.canary.CanaryStore
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
import app.aether.aegis.ui.components.LogPeriodSlider

/**
 * Canary message — dead-man's switch. User writes one message; if
 * they don't open the app within the configured window, the message
 * auto-sends to every paired peer.
 *
 * Honest UX: shipping a dead-man's switch deserves a clear "this is
 * what fires" preview so the user understands what their absence
 * will cause.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanarySettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val store = remember { CanaryStore(context) }

    // Local mirrors of the persisted CanaryStore fields; every edit writes
    // straight through to the store (no save button). intervalMs is the
    // check-in window in millis — the worker compares it against the last
    // recorded check-in to decide whether to fire.
    var enabled by remember { mutableStateOf(store.enabled) }
    var message by remember { mutableStateOf(store.message) }
    var intervalMs by remember { mutableStateOf(store.intervalMs) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_canary)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Text("←", fontSize = 20.sp)
                    }
                },
            )
        },
    ) { padding ->
        // PIN-gated: the canary message and its timing are coercion-
        // sensitive (they reveal who gets alerted and on what schedule),
        // so the whole config sits behind the app PIN.
        app.aether.aegis.ui.components.PinGuardedContent(
            navController = navController,
            featureLabel = stringResource(R.string.security_canary),
        ) {
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.loki_deadmans_switch), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.canary_if_you_dont_open) +
                            "the message below auto-sends to every paired contact. " +
                            "Opening the app any time resets the timer.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            // Master enable / disable.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_enabled), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (enabled) stringResource(R.string.loki_canary_armed)
                            else "Off. No canary will fire.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    app.aether.aegis.ui.components.HexSwitch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            store.enabled = it
                            // Always stamp on toggle (not just on enable)
                            // so the clock is current — guards against a
                            // stale lastCheckInAt that would otherwise
                            // fire the canary immediately on next worker
                            // tick. Also stamp if the worker had never
                            // run (lastCheckInAt == 0).
                            store.recordCheckIn()
                        },
                    )
                }
            }

            // Message body. Persisted live on every keystroke (store.message
            // = it) so a draft survives leaving the screen without a save.
            // Capped at 8 lines visually but otherwise free text — sent
            // verbatim to peers when the canary fires.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.settings_message), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.canary_sent_verbatim_to_every),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = message,
                        onValueChange = {
                            message = it
                            store.message = it
                        },
                        placeholder = {
                            Text(
                                stringResource(R.string.canary_i_havent_checked_in) +
                                    "Please reach out to verify I'm okay.",
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        maxLines = 8,
                    )
                }
            }

            // Interval selector — the check-in window. This is the heart of
            // the dead-man's switch: how long the user can stay away before
            // the message fires.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.settings_checkin_window), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.canary_how_long_without_opening),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Logarithmic period picker. The slider works in
                    // seconds; we convert to/from the stored millis here.
                    // Range is deliberately 1 hour → 30 days: shorter than
                    // an hour risks firing on an ordinary night's sleep;
                    // longer than a month and the dead-man's switch stops
                    // being meaningfully "dead-man's". `null` from the
                    // slider is the "Never" sentinel, disabled here.
                    LogPeriodSlider(
                        valueSeconds = intervalMs / 1000L,
                        onValueChange = { v ->
                            // allowNever=false + instantSeconds=1h means
                            // both snap zones produce non-null Longs, so
                            // the null case is unreachable here.
                            val sec = v ?: (60L * 60)
                            intervalMs = sec * 1000L
                            store.intervalMs = intervalMs
                        },
                        minSeconds = 60.0 * 60,            // 1 hour
                        maxSeconds = 30.0 * 24 * 3600,     // 30 days
                        instantSeconds = 60L * 60,
                        allowNever = false,
                        instantLabel = "1 hour",
                    )
                }
            }
        }
        }
    }
}

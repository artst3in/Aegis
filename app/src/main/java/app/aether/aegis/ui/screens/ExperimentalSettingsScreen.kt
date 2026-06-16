package app.aether.aegis.ui.screens

import app.aether.aegis.prefs.ExperimentalPrefs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.GlassPanel

/**
 * Experimental section: visible only after the
 * user taps the version number 7 times in About. Houses features
 * the project hasn't promised to ship — sonar today, others later.
 *
 *   "Ship it silent. Let power users find it."
 *
 * A lock button at the bottom re-hides the entire section. Once
 * relocked, the version-tap counter resets and the user can't see
 * anything experimental until they re-discover it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentalSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { ExperimentalPrefs(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string._experimental)) },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.experimental_here_be_dragons), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.experimental_these_features_are_unfinished) +
                            "may not work on your hardware, and may be removed " +
                            "without notice. They live behind the 7-tap gate " +
                            "for a reason.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            // Sonar is NOT a standalone toggle any more: it is Sentinel's
            // ultrasonic detector and has no use outside the Sentinel cascade
            // (SentinelEngine owns the SonarEngine; the old sonarEnabled flag
            // gated nothing functional). It now lives under Sentinel below —
            // the raw-data / calibration viewer is reachable from there.

            // Sentinel — covert intrusion detection cascade.
            // Patent filed (SFTR-SNT-001). Engine code exists;
            // UI gated here until field-tested.
            val sentinelPrefs = remember { app.aether.aegis.sentinel.SentinelPrefs(context) }
            var sentinelEnabled by remember { mutableStateOf(sentinelPrefs.armed) }
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.sonar_sentinel_mode), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.experimental_covert_intrusion_detection_threesensor) +
                                "sonar → proximity → recording. Silences the " +
                                "emitter the instant someone gets close.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    app.aether.aegis.ui.components.HexSwitch(
                        checked = sentinelEnabled,
                        onCheckedChange = {
                            sentinelEnabled = it
                            sentinelPrefs.armed = it
                        },
                    )
                }
            }
            if (sentinelEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { navController.navigate("settings/sentinel/inbox") },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.experimental_inbox)) }
                    TextButton(
                        onClick = { navController.navigate("settings/sentinel/log") },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.experimental_event_log)) }
                    // Sonar (the detector) calibration + raw-data viewer —
                    // folded in here since it only exists to serve Sentinel.
                    TextButton(
                        onClick = { navController.navigate("settings/sonar") },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.security_sonar_experimental)) }
                }
            }

            // Snatch detection — moved here 2026-06-04: the
            // accelerometer heuristic false-fired SOS on ordinary jolts
            // (a dropped/thrown phone), so it's experimental until
            // reworked. OFF by default via SnatchDetectionStore;
            // ProtocolService spins the sensor up/down on the next status
            // tick after this flips.
            // NOTE: a fresh SnatchDetectionStore(context) is built both to
            // read the initial value AND inside onCheckedChange to write it
            // — the store is the source of truth; local state is only the
            // switch's visual position.
            var snatchEnabled by remember {
                mutableStateOf(app.aether.aegis.services.SnatchDetectionStore(context).enabled)
            }
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.experimental_snatch_detection), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.experimental_a_hard_throwgrabyank_fires) +
                                "False-fires on ordinary jolts — experimental.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    app.aether.aegis.ui.components.HexSwitch(
                        checked = snatchEnabled,
                        onCheckedChange = {
                            snatchEnabled = it
                            app.aether.aegis.services.SnatchDetectionStore(context).enabled = it
                        },
                    )
                }
            }

            // Vehicle crash detection — moved here 2026-06-04 from
            // the main Safety settings: experimental until field-tested.
            // The enable toggle lives right here (matching snatch); the
            // detail screen carries sensitivity. OFF by default via
            // CrashDetectionStore.
            val crashStore = remember { app.aether.aegis.crashdetection.CrashDetectionStore(context) }
            var crashEnabled by remember { mutableStateOf(crashStore.enabled) }
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.experimental_vehicle_crash_detection), fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.experimental_detect_a_vehicle_crash) +
                                "Accelerometer impact while driving — experimental.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    app.aether.aegis.ui.components.HexSwitch(
                        checked = crashEnabled,
                        onCheckedChange = {
                            crashEnabled = it
                            crashStore.enabled = it
                        },
                    )
                }
            }
            if (crashEnabled) {
                TextButton(
                    onClick = { navController.navigate("settings/crashdetection") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.experimental_crash_sensitivity_settings)) }
            }

            // Real glass (sheen + 3D) and Crown shimmer GRADUATED to the
            // normal Graphics settings (Settings → Appearance → Graphics) on
            // 2026-06-13 — they're complete, so they no longer hide behind the
            // 7-tap gate. Backing prefs are unchanged (ExperimentalPrefs), so
            // existing choices carried over. See GraphicsSettingsScreen.


            // Protected Mode — accident-prevention guard-rails behind a
            // 4th PIN (lock wipe / contact-delete / trust changes / etc.
            // so a child on their own phone — or you, against an impulse —
            // can't cut the safety net). Not a simple on/off toggle, so it
            // opens its own config screen rather than carrying a HexSwitch.
            // Lives here, like every other experimental feature, until
            // field-tested. Strings are literals for now (English-only)
            // until it graduates and the copy is extracted for translation.
            // Unlike the other features here, armed-state is a live global
            // StateFlow (not a one-shot store read), so the "Manage"/"Set up"
            // label and the description update instantly when PIN setup
            // completes on the config screen and pops back here.
            val pmArmed by app.aether.aegis.protectedmode.ProtectedMode.armed.collectAsState()
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Protected Mode", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (pmArmed)
                                "Armed — destructive actions are locked behind the 4th PIN."
                            else
                                "Lock destructive actions (wipe, delete/mute contacts, " +
                                    "trust changes, groups…) behind a 4th PIN. For a " +
                                    "child's own phone, or as a commitment device on yourself.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = { navController.navigate("settings/protectedmode") }) {
                        Text(if (pmArmed) "Manage" else "Set up")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            // Re-hide the whole section: prefs.lock() resets the 7-tap
            // discovery gate so the user must re-discover Experimental from
            // About before it reappears. Note this hides the SECTION; it does
            // NOT disarm the features toggled on above (Sentinel, snatch,
            // crash, Protected Mode keep running on their own stores).
            OutlinedButton(
                onClick = {
                    prefs.lock()
                    navController.navigateUp()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.experimental_relock_experimental_section)) }
        }
    }
}

package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.AegisTopBar
import app.aether.aegis.ui.components.HexSlider

import app.aether.aegis.mugshot.MugshotStore
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
 * Settings screen for the duress-mugshot capture.
 *
 * WHY this exists: when an attacker is brute-forcing the lock PIN, the most
 * valuable forensic artifact is a photo of who is holding the phone. This
 * screen arms a silent dual-camera capture that fires after N consecutive
 * wrong PINs and lets the user pick that N (the [MugshotStore.triggerThreshold]).
 * Capture/storage/EXIF logic lives in [MugshotStore] and the capture
 * pipeline; this screen only edits the enabled flag and the threshold.
 *
 * Privacy guarantee surfaced to the user: the JPEGs stay ON-DEVICE and are
 * never auto-sent to contacts — they leave only when a paired contact runs
 * a remote-LOCATE and proves the AUTH PIN. This screen does NOT perform any
 * capture or transmission itself.
 *
 * Gated behind [PinGuardedContent] for the same reason as the other tamper
 * features: an attacker holding the phone must not be able to disable the
 * camera that is about to photograph them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MugshotSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val store = remember { MugshotStore(context) }
    // Compose mirrors of the two persisted values; edits write through to the
    // store so they survive process death (the streak itself lives in the
    // store too and is reset by a successful real-PIN unlock).
    var enabled by remember { mutableStateOf(store.enabled) }
    var threshold by remember { mutableStateOf(store.triggerThreshold) }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = { Text(stringResource(R.string.mugshot_mugshot_on_failed_pin)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
            )
        },
    ) { padding ->
        // PIN gate: hide the whole body until the owner proves the AUTH PIN,
        // so the person triggering wrong-PIN captures can't turn them off.
        app.aether.aegis.ui.components.PinGuardedContent(
            navController = navController,
            featureLabel = stringResource(R.string.security_mugshot),
        ) {
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Explainer panel. The copy is interpolated with the LIVE
            // threshold so the description updates as the slider below moves
            // (e.g. "After 5 wrong PIN attempts…"). Spells out the on-device-
            // only privacy promise so the user understands nothing is sent.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.settings_how_it_works), fontWeight = FontWeight.SemiBold)
                    Text(
                        "After ${threshold} wrong PIN attempts in a row, the front and " +
                            "rear cameras silently capture a still each. The JPEGs are " +
                            "stored locally on this device only — nothing is sent to your " +
                            "paired contacts. GPS coords and a timestamp are burned into " +
                            "the EXIF so the forensic record stands on its own. " +
                            "A high-priority notification fires to you so you see the " +
                            "capture on next unlock. The streak resets on the next " +
                            "successful real-PIN unlock. " +
                            "No preview, no shutter sound — the attacker doesn't see " +
                            "anything happen. " +
                            "The picture only leaves the device if a paired contact runs " +
                            "remote-LOCATE against you and proves the right AUTH PIN.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_enabled), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (enabled) stringResource(R.string.loki_mugshot_armed, threshold)
                            else "Off — no capture will fire.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    app.aether.aegis.ui.components.HexSwitch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            store.enabled = it
                            // Re-toggling clears the per-streak fired
                            // flag so the next failed-PIN run can fire
                            // again — otherwise a previous test leaves
                            // it stuck at true and silent-fails.
                            store.firedThisStreak = false
                        },
                    )
                }
            }

            // Threshold picker: how many consecutive wrong PINs trigger the
            // capture. Allowed range is 1..10 wrong attempts.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.mugshot_threshold), fontWeight = FontWeight.SemiBold)
                    Text(
                        "$threshold wrong PINs",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HexSlider(
                        // Track the drag live (and clamp to 1..10) so the
                        // label updates continuously; only commit to the store
                        // on release to avoid a write per drag frame. steps=8
                        // yields the 10 discrete stops over the 1..10 range
                        // (endpoints + 8 interior steps).
                        value = threshold.toFloat(),
                        onValueChange = { threshold = it.toInt().coerceIn(1, 10) },
                        onValueChangeFinished = { store.triggerThreshold = threshold },
                        valueRange = 1f..10f,
                        steps = 8,
                    )
                }
            }
        }
        }
    }
}

package app.aether.aegis.ui.screens

import app.aether.aegis.crashdetection.CrashDetectionStore
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
 * Crash Detection settings: one toggle
 * plus a Low/Med/High sensitivity that sets both the impact G-threshold
 * and the speed gate. Off by default; no warning dialog — it's purely
 * protective (it only watches YOUR sensors for YOUR safety).
 *
 * WHY this exists: a vehicle crash can leave the user unable to summon help.
 * When armed, the detector watches accelerometer + speed while moving and,
 * on a likely impact, runs a 30-second "I'm OK" countdown before firing the
 * normal SOS to the user's contacts (complementing — not replacing — any
 * native/carrier crash service that calls professionals). The detection and
 * the meaning of each sensitivity level live in [CrashDetectionStore]; this
 * screen only edits the enabled flag and the 0/1/2 sensitivity index.
 *
 * Unlike the tamper features (SIM-swap, mugshot) this screen is NOT
 * PIN-gated: it watches only the owner's own sensors for the owner's own
 * safety, so there's no attacker-disable threat to defend against.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashDetectionSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val store = remember { CrashDetectionStore(context) }
    // Compose mirrors of the two persisted values; edits write through to the
    // store. `sensitivity` is a 0/1/2 index (Low/Med/High).
    var enabled by remember { mutableStateOf(store.enabled) }
    var sensitivity by remember { mutableStateOf(store.sensitivity) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crash_detection_crash_detection)) },
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
            // string. The localized lead sentence is followed by hard-coded
            // copy describing the 30s countdown and the moving-only battery
            // story (monitoring is gated on motion, so idle cost is ~nil).
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.invitation_expiry_about_this_setting), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.crash_detection_detects_a_vehicle_crash) +
                            "driving and — after a 30-second “I'm OK” countdown — " +
                            "fires your normal sos: live GPS, audio, and camera " +
                            "to your trusted and emergency contacts. Native crash " +
                            "detection calls the professionals; this calls the " +
                            "people who care. Monitoring only runs while you're " +
                            "moving, so the battery cost is negligible.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.crash_detection_crash_detection), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (enabled) "On — watching for impacts while driving"
                            else "Off",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            // Mirror then persist (see field declarations).
                            enabled = it
                            store.enabled = it
                        },
                    )
                }
            }

            // Sensitivity selector is only shown while armed — there is
            // nothing to tune when detection is off, so hiding it keeps the
            // screen uncluttered. Toggling `enabled` recomposes this branch.
            if (enabled) {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) {
                        Text(
                            stringResource(R.string.crash_detection_sensitivity),
                            color = app.aether.aegis.ui.theme.AegisCyan,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
                        )
                        // One radio row per sensitivity index (0=Low,1=Med,
                        // 2=High). The whole row is clickable, not just the
                        // RadioButton, so the larger tap target is forgiving;
                        // both paths write the same mirror+store update.
                        // CrashDetectionStore.summary(s) renders the human
                        // description of each level's G-threshold + speed gate.
                        (0..2).forEach { s ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        sensitivity = s
                                        store.sensitivity = s
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = sensitivity == s,
                                    onClick = {
                                        sensitivity = s
                                        store.sensitivity = s
                                    },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    CrashDetectionStore.summary(s),
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

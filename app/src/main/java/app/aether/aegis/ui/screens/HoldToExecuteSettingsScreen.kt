package app.aether.aegis.ui.screens

import app.aether.aegis.gesture.HoldToExecuteStore
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
 * Configures hold-to-execute. When enabled,
 * send / voice call / video call buttons require a press-and-hold.
 * Hold duration is 200 / 500 / 1000 ms.
 *
 * Off by default — opt-in, so existing tap-to-send muscle memory
 * keeps working until the user turns it on.
 *
 * WHY this exists: the send and (especially) the call icons are easy to
 * brush accidentally while scrolling, and an accidental voice/video call
 * leaks presence/audio — a real risk for the at-risk users this app
 * targets. Requiring a deliberate hold guards against that. This screen
 * only edits the persisted flag + duration in [HoldToExecuteStore]; the
 * actual press-and-hold gesture handling lives in the chat composer/call
 * buttons, which observe the store reactively
 * (HoldToExecuteStore.settingsFlow), so a toggle here applies to open chats
 * immediately — no app restart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldToExecuteSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val store = remember { HoldToExecuteStore(context) }
    // Compose mirrors of the persisted values; edits write through to the
    // store. durationMs is one of the three discrete presets (200/500/1000).
    var enabled by remember { mutableStateOf(store.enabled) }
    var durationMs by remember { mutableStateOf(store.durationMs) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hold_to_execute_hold_to_send_call)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Text("←", fontSize = 20.sp)
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Explainer panel. Localized lead sentence + hard-coded tail
            // describing the cyan fill-on-press affordance, release-to-cancel,
            // and the long-press-to-call behaviour. The closing line notes the
            // toggle applies live (open chats observe the store via
            // HoldToExecuteStore.settingsFlow).
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.settings_how_it_works), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.hold_to_execute_send_voice_call_and) +
                            "to fire. The send hex fills with cyan as you press; release " +
                            "early to cancel. Voice / video call icons long-press to ring; " +
                            "a tap shows a 'Hold to call' hint. " +
                            "Guards against accidental triggers — especially the call " +
                            "icons which are easy to brush while scrolling. " +
                            "Toggling applies to open chats immediately.",
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
                        // Subtitle reflects the live duration when armed; the
                        // off-state copy spells out that tap fires instantly.
                        Text(
                            if (enabled) "On — hold ${durationMs}ms to fire."
                            else "Off — tap fires immediately.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    app.aether.aegis.ui.components.HexSwitch(
                        checked = enabled,
                        onCheckedChange = {
                            // Mirror then persist (see field declarations).
                            enabled = it
                            store.enabled = it
                        },
                    )
                }
            }

            // Hold-duration picker. Three fixed presets (values in ms; the
            // 1000 ms option is labelled "1 second"). 500 ms is the default.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.hold_to_execute_hold_duration), fontWeight = FontWeight.SemiBold)
                    Text(
                        "${durationMs}ms",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Label→value pairs; the Long suffix matches the store's
                    // millisecond field type so no conversion is needed.
                    listOf(
                        "200 ms — quick safety stop" to 200L,
                        "500 ms — default"           to 500L,
                        "1 second — deliberate"      to 1000L,
                    ).forEach { (label, value) ->
                        val selected = durationMs == value
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    // Mirror then persist; selection is by
                                    // exact value match against durationMs.
                                    durationMs = value
                                    store.durationMs = value
                                },
                            )
                            Text(label, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

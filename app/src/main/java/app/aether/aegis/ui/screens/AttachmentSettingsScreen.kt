package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisTopBar
import app.aether.aegis.ui.components.HexSlider

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
import app.aether.aegis.attachment.AttachmentPrefs
import app.aether.aegis.attachment.MediaType
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.HexSwitch
import app.aether.aegis.ui.components.GlassPanel
import kotlin.math.roundToInt

/**
 * User-facing controls for the attachment auto-download policy that
 * [AttachmentPrefs] persists and SimpleXTransport's four-gate
 * `shouldAutoDownloadAttachment` enforces. Until this screen existed the
 * policy ran but was unconfigurable — every install was pinned to the
 * protect-first defaults (WiFi-only ON, images + voice only, 25 MB cap).
 *
 * Three controls, each writing through to [AttachmentPrefs] immediately
 * (the prefs expose StateFlows the transport already reads live, so a
 * change here takes effect on the very next incoming file):
 *   - WiFi-only toggle      → [AttachmentPrefs.wifiOnly]
 *   - per-type auto-download → [AttachmentPrefs.autoTypes]
 *   - size cap slider        → [AttachmentPrefs.maxAutoBytes]
 *
 * NOT shown: the trust gate (an Untrusted contact's attachments NEVER
 * auto-download) is hardcoded and deliberately not user-configurable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { AttachmentPrefs(context) }
    // Compose mirrors of the persisted policy. Edits write through to prefs
    // synchronously; the prefs' StateFlows propagate to the live gate.
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }
    var autoTypes by remember { mutableStateOf(prefs.autoTypes) }
    var maxBytes by remember { mutableStateOf(prefs.maxAutoBytes) }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = { Text("Attachment downloads") },
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
            // Explainer. Stresses the protect-first intent and that an
            // Untrusted contact never auto-downloads regardless of these.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("How it works", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Incoming files auto-download only when ALL of these allow " +
                            "it: a connection the WiFi-only rule permits, a type you've " +
                            "enabled, and a size under the cap. Anything deferred shows " +
                            "a tap-to-download bubble in the chat. Files from Untrusted " +
                            "contacts never auto-download — that's fixed and not shown here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            // WiFi-only master gate. ON = defer every attachment on a metered
            // link (cellular / hotspot); WiFi + Ethernet still auto-pull.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("WiFi-only", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Auto-download only on WiFi or Ethernet. On a metered " +
                                "connection, everything waits for a tap.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    HexSwitch(
                        checked = wifiOnly,
                        onCheckedChange = { on -> wifiOnly = on; prefs.wifiOnly = on },
                    )
                }
            }

            // Per-type opt-in. Reuses MediaType.label so the bucket names
            // match the policy's own vocabulary. A type that's off waits for
            // a tap even when the network + size gates would allow it.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Auto-download types", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    MediaType.values().forEach { type ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(type.label, modifier = Modifier.weight(1f))
                            HexSwitch(
                                checked = type in autoTypes,
                                onCheckedChange = { on ->
                                    // Build the new set, mirror, persist.
                                    val next = if (on) autoTypes + type else autoTypes - type
                                    autoTypes = next
                                    prefs.autoTypes = next
                                },
                            )
                        }
                    }
                }
            }

            // Size cap. Discrete log-ish presets 1 MB … 100 MB plus an
            // Unlimited end-snap (UNLIMITED_BYTES). The slider position maps
            // to a preset index; we persist only on release (onValueChangeFinished)
            // so a drag doesn't thrash SharedPreferences.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Max auto-download size", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        sizeLabelForStep(stepForBytes(maxBytes)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    var sizeStep by remember { mutableStateOf(stepForBytes(maxBytes).toFloat()) }
                    HexSlider(
                        value = sizeStep,
                        onValueChange = { sizeStep = it },
                        onValueChangeFinished = {
                            val bytes = bytesForStep(sizeStep.roundToInt())
                            maxBytes = bytes
                            prefs.maxAutoBytes = bytes
                        },
                        // 7 positions (six MB presets + Unlimited) → six gaps,
                        // i.e. five interior steps.
                        valueRange = 0f..SIZE_PRESET_MB.size.toFloat(),
                        steps = SIZE_PRESET_MB.size - 1,
                    )
                }
            }
        }
    }
}

// ---- size-cap preset mapping ----------------------------------------------

/** Discrete MB presets for the size cap; the slider's last position past
 *  these is "Unlimited" ([AttachmentPrefs.UNLIMITED_BYTES]). */
private val SIZE_PRESET_MB = listOf(1L, 5L, 10L, 25L, 50L, 100L)

private fun bytesForStep(step: Int): Long =
    if (step >= SIZE_PRESET_MB.size) AttachmentPrefs.UNLIMITED_BYTES
    else SIZE_PRESET_MB[step] * 1024L * 1024L

/** Inverse of [bytesForStep]: the slider position for a stored byte cap.
 *  Unlimited / non-positive → the far ("Unlimited") end; otherwise the
 *  first preset that is ≥ the stored value (so a non-preset stored cap
 *  snaps up to the nearest offered step rather than vanishing). */
private fun stepForBytes(bytes: Long): Int {
    if (bytes == AttachmentPrefs.UNLIMITED_BYTES || bytes <= 0L) return SIZE_PRESET_MB.size
    val idx = SIZE_PRESET_MB.indexOfFirst { it * 1024L * 1024L >= bytes }
    return if (idx < 0) SIZE_PRESET_MB.size else idx
}

private fun sizeLabelForStep(step: Int): String =
    if (step >= SIZE_PRESET_MB.size) "Unlimited" else "${SIZE_PRESET_MB[step]} MB"

package app.aether.aegis.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import app.aether.aegis.R
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import androidx.compose.ui.res.stringResource

/**
 * Live capability dashboard — reflects actual device state.
 *
 * Each capability shows active/inactive based on whether
 * the underlying permission or feature is currently enabled.
 * The user sees exactly what their phone can do RIGHT NOW
 * and what it COULD do if they enable the missing permission.
 *
 * NOT a control panel: every line is read-only status. Toggling a
 * capability on happens elsewhere (Settings/Opsec, system permission
 * dialogs); this screen only mirrors the result. The permission reads
 * below are [remember]ed, so the snapshot is taken once per entry into
 * the screen — a permission granted while this screen is open won't flip
 * a dot live; the user has to leave and return. Acceptable for a status
 * view, and it keeps the composition cheap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilitiesScreen(navController: NavController) {
    val context = LocalContext.current

    // ---- Permission checks (snapshotted once via remember; see KDoc) ----
    val dpm = remember {
        context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
    }
    // Use AdminGate — the single source of truth opsec uses. The old code
    // hardcoded "AegisDeviceAdmin", but the receiver class is actually
    // AegisAdminReceiver, so isAdminActive checked a non-existent
    // component and always reported "inactive" even when admin was on.
    val isDeviceAdmin = remember {
        app.aether.aegis.admin.AdminGate.isActive(context)
    }
    // Device Owner is a strictly higher privilege than Device Admin and
    // can only be set via ADB provisioning on a fresh device — it gates
    // the silent-update / lockdown / tamper-resist capabilities below.
    val isDeviceOwner = remember {
        dpm?.isDeviceOwnerApp(context.packageName) == true
    }
    val hasCamera = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }
    val hasMic = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val hasLocation = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.capabilities_capabilities)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    AegisIcon(AegisIcons.Back, stringResource(R.string.action_back))
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                stringResource(R.string.capabilities_live_status_green_active),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Each CapGroup is one collapsible section; a CapLine is one
            // capability. `active = true` lines are features that are
            // always on (no permission needed); lines wired to a hasX flag
            // dim and show a disabledLabel when the permission is missing.
            CapGroup("Messaging") {
                CapLine("SimpleX protocol (2-hop routing, no Tor, no phone number)", active = true)
                CapLine("Encrypted attachments (photo / video / file)", active = true)
                CapLine("Burn-after-reading messages", active = true)
                CapLine("Encrypted message DB (SQLCipher)", active = true)
                CapLine("Invitation link management (revoke + auto-expire)", active = true)
            }

            CapGroup("Trust tiers") {
                CapLine("Trusted contacts: full SOS + location + presence", active = true)
                CapLine("Emergency contacts: SOS broadcast only", active = true)
                CapLine("Untrusted contacts: chat only, zero safety data", active = true)
                CapLine("Per-contact trust assignment on pairing", active = true)
                CapLine("Compile-time module separation between tiers", active = true)
            }

            CapGroup("Groups") {
                CapLine("Anonymous community chat (incognito only)", active = true)
                CapLine("No private messaging between members", active = true)
                CapLine("No real identity revealed to group", active = true)
                CapLine("Group avatar, description, and pinned README", active = true)
                CapLine("Isolated module (off by default, zero data bridge)", active = true)
            }

            CapGroup("Authentication & deniability") {
                CapLine("App-lock PIN (Argon2id, libsodium INTERACTIVE)", active = true)
                CapLine("Three-layer duress (real + Fake #1 + Fake #2)", active = true)
                CapLine("Scramble PIN pad (anti shoulder-surf)", active = true)
                CapLine("Escalating wrong-PIN lockout (30 s \u2192 15 m)", active = true)
                CapLine(
                    "Mugshot on wrong PIN (front camera, silent)",
                    active = hasCamera,
                    disabledLabel = "needs camera permission",
                )
                CapLine("Separate launch lock vs remote-AUTH PIN gate", active = true)
                CapLine("Duress blanks all badges and group avatars", active = true)
            }

            CapGroup("Threat detection") {
                CapLine("Snatch detection (accelerometer)", active = true)
                CapLine("Sonar (ultrasonic motion detection)", active = hasMic, disabledLabel = "needs microphone permission")
                CapLine("SIM-swap monitor", active = true)
                CapLine(
                    "Geofence triggers",
                    active = hasLocation,
                    disabledLabel = "needs location permission",
                )
                CapLine("Dead-man\u2019s canary check-in", active = true)
                CapLine(
                    "Crash detection (accelerometer + GPS speed gate)",
                    active = hasLocation,
                    disabledLabel = "needs location permission",
                )
            }

            CapGroup("Threat response") {
                CapLine(
                    "SOS broadcast + 5 s GPS pushes",
                    active = hasLocation,
                    disabledLabel = "needs location permission",
                )
                CapLine(
                    "SOS: rotating audio segments, alert every 30 s",
                    active = hasMic,
                    disabledLabel = "needs microphone permission",
                )
                CapLine(
                    "Crash detected: 30 s countdown then SOS fires",
                    active = hasLocation,
                    disabledLabel = "needs location permission",
                )
                CapLine("All triggers feed the same SOS pipeline", active = true)
            }

            CapGroup("Remote access (PIN-gated)") {
                // LOCATE needs BOTH location (for the fix) and camera (for
                // the mugshot), so the disabledLabel names whichever of the
                // two is actually missing rather than a generic message.
                CapLine(
                    "LOCATE: lock + GPS fix + latest mugshot",
                    active = hasLocation && hasCamera,
                    disabledLabel = if (!hasLocation && !hasCamera) "needs location + camera"
                        else if (!hasLocation) "needs location permission"
                        else "needs camera permission",
                )
                CapLine("SIREN: max-volume alarm (toggleable)", active = true)
                CapLine("PUSH UPDATE: deploy latest APK to family device", active = true)
            }

            CapGroup("Contact awareness") {
                CapLine(
                    "Tactical radar (peer location hex map)",
                    active = hasLocation,
                    disabledLabel = "needs location permission",
                )
                CapLine("Group + 1:1 calls (WebRTC, voice / video)", active = hasMic, disabledLabel = "needs microphone permission")
                CapLine("Wrong-PIN intrusion alerts to trusted contacts", active = true)
                CapLine(
                    "Battery + online/offline presence sharing",
                    active = hasLocation,
                    disabledLabel = "needs location permission",
                )
            }

            CapGroup("Achievements") {
                CapLine("Earn-once badges for verified capabilities", active = true)
                CapLine("Visible to trusted contacts (proof of setup)", active = true)
                CapLine("Blanked under duress", active = true)
                CapLine("Achievement layer dies silently (never crashes security)", active = true)
            }

            CapGroup("Skill tree") {
                CapLine("Visual capability map (unlock by configuring)", active = true)
                CapLine("Progressive disclosure of advanced features", active = true)
                CapLine("Health-heart ladder for security posture", active = true)
            }

            CapGroup("Operations") {
                CapLine("Auto-update via GitHub Releases", active = true)
                CapLine("Encrypted notes vault", active = true)
                CapLine(stringResource(R.string.settings_quiet_hours), active = true)
                CapLine("Themed monochrome adaptive icon (Android 13+)", active = true)
            }

            // The group header itself doubles as a status line: a checkmark
            // when the privilege is held, otherwise a hint on how to get it.
            CapGroup(if (isDeviceAdmin) "Device Admin \u2714" else "Device Admin \u2014 enable in Settings") {
                CapLine(
                    "Remote lock screen",
                    active = isDeviceAdmin,
                    disabledLabel = "enable in Settings \u2192 Security \u2192 Device Admin",
                )
                CapLine(
                    "Enforce PIN complexity",
                    active = isDeviceAdmin,
                    disabledLabel = "enable in Settings \u2192 Security \u2192 Device Admin",
                )
                CapLine(
                    "Remote WIPE (factory reset on AUTH command)",
                    active = isDeviceAdmin,
                    disabledLabel = "enable in Settings \u2192 Security \u2192 Device Admin",
                )
            }

            CapGroup(if (isDeviceOwner) "Device Owner \u2714" else "Device Owner \u2014 requires ADB") {
                CapLine(
                    "Silent self-update (no install prompt)",
                    active = isDeviceOwner,
                    disabledLabel = "requires Device Owner (ADB provisioning)",
                )
                CapLine(
                    "Lockdown: keyguard + lock-task + status-bar disable",
                    active = isDeviceOwner,
                    disabledLabel = "requires Device Owner (ADB provisioning)",
                )
                CapLine(
                    "Lockdown: evict credential-encrypted key on lockNow",
                    active = isDeviceOwner,
                    disabledLabel = "requires Device Owner (ADB provisioning)",
                )
                CapLine(
                    "Tamper-resistant uninstall (DO protection)",
                    active = isDeviceOwner,
                    disabledLabel = "requires Device Owner (ADB provisioning)",
                )
                CapLine(
                    "Auto-grant all runtime permissions",
                    active = isDeviceOwner,
                    disabledLabel = "requires Device Owner (ADB provisioning)",
                )
            }

            // Tail hint, tiered by current privilege: nudge toward the NEXT
            // rung up. Nothing yet → explain both; admin-only → explain how
            // to reach owner. Already owner → no hint (nothing left to gain).
            if (!isDeviceAdmin && !isDeviceOwner) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.capabilities_device_admin_enable_in) +
                        "Device Owner: factory-reset, skip account setup, run scripts/provision-red.sh.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            } else if (isDeviceAdmin && !isDeviceOwner) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.capabilities_device_owner_factoryreset_the) +
                        "skip account setup, then run scripts/provision-red.sh from a host.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                stringResource(R.string.capabilities_stay_safe),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Collapsible section header with its capability rows. Starts expanded so
 * the whole dashboard is visible on first open; tapping the header (or its
 * caret) toggles [expanded]. State is per-instance and not hoisted —
 * collapse state is intentionally not remembered across screen exits.
 */
@Composable
private fun CapGroup(label: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (expanded) "\u25be" else "\u25b8",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (expanded) {
        Column { content() }
    }
}

/**
 * One capability row: a filled dot + label when [active], a hollow dot +
 * dimmed label when not. When inactive and [disabledLabel] is given, that
 * label renders underneath as the "how to enable" hint. The dot uses a
 * monospace font so dots line up vertically regardless of label width.
 */
@Composable
private fun CapLine(
    label: String,
    active: Boolean,
    disabledLabel: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // Filled vs hollow circle = active vs inactive at a glance.
            if (active) "\u25cf" else "\u25cb",
            color = if (active) MaterialTheme.colorScheme.primary
                    else app.aether.aegis.ui.theme.AegisOnSurfaceDim,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            if (!active && disabledLabel != null) {
                Text(
                    disabledLabel,
                    fontSize = 11.sp,
                    color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                )
            }
        }
    }
}

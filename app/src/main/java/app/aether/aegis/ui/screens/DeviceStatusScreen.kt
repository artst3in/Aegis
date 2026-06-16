package app.aether.aegis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import app.aether.aegis.ui.components.AegisIcon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.AegisApp
import app.aether.aegis.data.MemberStatusEntity

/**
 * Per-device hardware status detail. Reached by tapping a tile on the
 * Status tab. Surfaces every field on MemberStatusEntity that gets
 * pinged across by [aegis:status] / [aegis:location] / wearable bio
 * pings, so the user can see what the remote device is reporting
 * without having to read it out of the chat log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceStatusScreen(peerKey: String, navController: NavController) {
    // "Self" = this device's own status tile. The self path reads local
    // sources (profileStore, live DPM checks) rather than the broadcast
    // MemberStatusEntity, so several cards branch on isSelf below.
    val selfKey = AegisApp.instance.identity.deviceId
    val isSelf = peerKey == selfKey
    val status by AegisApp.instance.repository.observeStatus(peerKey)
        .collectAsState(initial = null)
    // Look up the peer row only for a remote device — our own profile name
    // comes from profileStore, not the known-peers table.
    val peer by produceState<app.aether.aegis.data.KnownPeerEntity?>(initialValue = null, peerKey) {
        if (!isSelf) value = AegisApp.instance.repository.knownPeerByKey(peerKey)
    }
    val displayName = when {
        isSelf -> AegisApp.instance.profileStore.displayName.ifBlank { "You" }
        // Fall back to a truncated key when we have no nickname for the peer.
        else -> peer?.displayName ?: peerKey.take(12)
    }
    // Tick once per second so "age" labels stay live without forcing
    // the user to leave and re-enter the screen.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (isSelf) "this device" else "remote device",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(app.aether.aegis.ui.components.AegisIcons.Back, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PresenceCard(status, isSelf, nowMs)
            PowerCard(status)
            NetworkInfoCard(status)
            LocationCard(status)
            BiometricsCard(status)
            // Self-only opsec posture (Device Admin / Owner). Peers
            // don't broadcast their privilege state, so it would always
            // read "unknown" for a remote device — show it only for your
            // own phone, where it's a live local check.
            if (isSelf) OpsecPostureCard()
            IdentityCard(peerKey, isSelf)

            // Remote Access entry — gated on !isSelf because you
            // don't remote-control your own phone. Moved
            // out of ContactDetailScreen ("REMOTE is operational,
            // not relational — it belongs next to device status,
            // not next to nickname + mute") 2026.05.30.
            if (!isSelf) {
                val encodedKey = remember(peerKey) {
                    java.net.URLEncoder.encode(peerKey, "UTF-8")
                }
                OutlinedButton(
                    onClick = { navController.navigate("remote/$encodedKey") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AegisIcon(
                        icon = app.aether.aegis.ui.components.AegisIcons.RemoteCmd,
                        contentDescription = null,
                        tint = app.aether.aegis.ui.theme.AegisCyan,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.device_status_remote_access),
                        color = app.aether.aegis.ui.theme.AegisCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Online / Away / Offline / never-seen presence, plus the last-ping
 * timestamp. Self is always shown Online (it's the local device). For a
 * peer, presence comes from the shared [peerStatusFor] helper, and the
 * "(time ago)" suffix is driven by whichever timestamp that helper used
 * for the verdict — foreground when Online, heartbeat otherwise — so the
 * label and its age agree.
 */
@Composable
private fun PresenceCard(status: MemberStatusEntity?, isSelf: Boolean, nowMs: Long) {
    // Central presence calc — was an
    // inline ageMs lastActive-only check; missed Away for
    // background-alive peers. peerStatusFor reads both
    // foreground + heartbeat timestamps.
    val presence = app.aether.aegis.ui.components.peerStatusFor(
        status = status, nowMs = nowMs, isSelf = isSelf,
    )
    // Age label still drives the "(time ago)" suffix; pick which
    // timestamp drives it from the same precedence the helper
    // uses (foreground when Online, heartbeat otherwise).
    val ageMs = when {
        isSelf -> 0L
        presence == app.aether.aegis.ui.components.PeerStatus.Online ->
            status?.lastActive?.let { nowMs - it } ?: 0L
        else -> {
            val stamp = status?.lastPacketMs ?: status?.lastActive
            stamp?.let { nowMs - it } ?: Long.MAX_VALUE
        }
    }
    val (presenceLabel, presenceColor) = when {
        isSelf -> "online · this device" to app.aether.aegis.ui.theme.AegisOnline
        presence == app.aether.aegis.ui.components.PeerStatus.Online ->
            stringResource(R.string.status_online) to app.aether.aegis.ui.theme.AegisOnline
        presence == app.aether.aegis.ui.components.PeerStatus.Away ->
            "away (${formatAge(ageMs)})" to app.aether.aegis.ui.theme.AegisWarning
        status == null ->
            "never seen" to app.aether.aegis.ui.theme.AegisOnSurfaceDim
        else ->
            "offline (${formatAge(ageMs)})" to app.aether.aegis.ui.theme.AegisDanger
    }
    DeviceStatusCard("Presence") {
        FieldRow(stringResource(R.string.sonar_status), presenceLabel, presenceColor)
        FieldRow(
            "Last ping",
            status?.lastActive?.let { formatAbsoluteAndAge(it, nowMs) } ?: "—",
        )
    }
}

/**
 * Battery percentage + charging state for the device. Battery colour is
 * a traffic-light on the reported level. Thresholds (≤15 danger, ≤35
 * warning) are display-only here — they tint the readout and are NOT the
 * PowerBudget/Voyager curve that actually governs behaviour.
 */
@Composable
private fun PowerCard(status: MemberStatusEntity?) {
    val battery = status?.batteryLevel
    val charging = status?.isCharging == true
    val (batteryLabel, batteryColor) = when {
        battery == null -> "—" to null
        // Purely cosmetic traffic-light bands for the readout.
        battery <= 15   -> "$battery %" to app.aether.aegis.ui.theme.AegisDanger
        battery <= 35   -> "$battery %" to app.aether.aegis.ui.theme.AegisWarning
        else            -> "$battery %" to app.aether.aegis.ui.theme.AegisOnline
    }
    DeviceStatusCard("Power") {
        FieldRow(stringResource(R.string.status_battery), batteryLabel, batteryColor)
        // Charging row uses a stroke-only LunaGlass lightning vector
        // when on, tinted on-surface; plain "no" otherwise.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.status_charging),
                modifier = Modifier.weight(0.4f),
                color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                fontSize = 13.sp,
            )
            Row(
                modifier = Modifier.weight(0.6f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (charging) {
                    app.aether.aegis.ui.components.AegisIcon(
                        icon = app.aether.aegis.ui.components.AegisIcons.Charging,
                        contentDescription = stringResource(R.string.map_charging),
                        tint = app.aether.aegis.ui.theme.AegisOnline,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.device_status_yes), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("no", fontSize = 13.sp)
                }
            }
        }
    }
}

/** Connection type, radio signal strength (bars + dBm), and the peer's
 *  Aegis build. All three are "—" when not reported. The version field
 *  is only populated for Trusted peers on a version-broadcasting build. */
@Composable
private fun NetworkInfoCard(status: MemberStatusEntity?) {
    DeviceStatusCard("Network") {
        FieldRow("Connection", status?.networkType ?: "—")
        FieldRow("Signal") {
            val sig = status?.signalStrength
            app.aether.aegis.ui.components.SignalBars(dbm = sig)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                sig?.let { "$it dBm" } ?: "—",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
            )
        }
        // Peer's Aegis build (Trusted-only, populated when the peer
        // is on a version-broadcasting build).
        FieldRow("Aegis version", status?.appVersion ?: "—")
    }
}

/** Last reported GPS fix. Renders the "no GPS fix yet" placeholder
 *  unless BOTH lat and lng are present — a half-populated fix is treated
 *  as no fix rather than shown as (lat, 0). */
@Composable
private fun LocationCard(status: MemberStatusEntity?) {
    val lat = status?.latitude
    val lng = status?.longitude
    DeviceStatusCard(stringResource(R.string.chat_location)) {
        if (lat != null && lng != null) {
            FieldRow("Latitude", "%.5f".format(lat))
            FieldRow("Longitude", "%.5f".format(lng))
            FieldRow("Lat,Lng", "%.5f, %.5f".format(lat, lng))
        } else {
            FieldRow(stringResource(R.string.diagnostics_fix), "no GPS fix yet", app.aether.aegis.ui.theme.AegisOnSurfaceDim)
        }
    }
}

/** Wearable bio readout (heart rate / HRV / SpO₂) from a paired device.
 *  The card always renders so the absence of a wearable is explicit ("no
 *  wearable data") rather than a silently missing panel. */
@Composable
private fun BiometricsCard(status: MemberStatusEntity?) {
    // Pulled from paired wearables. All three are optional; if the
    // device isn't broadcasting them the card still renders so the
    // user knows "no wearable connected" rather than wondering whether
    // the panel just isn't there.
    val hr = status?.heartRate
    val hrv = status?.hrv
    val spo2 = status?.spo2
    val anyPresent = hr != null || hrv != null || spo2 != null
    DeviceStatusCard("Wearable") {
        if (!anyPresent) {
            FieldRow(stringResource(R.string.sonar_status), "no wearable data", app.aether.aegis.ui.theme.AegisOnSurfaceDim)
        } else {
            FieldRow("Heart rate", hr?.let { "$it bpm" } ?: "—")
            FieldRow("HRV", hrv?.let { "$it ms" } ?: "—")
            FieldRow("SpO₂", spo2?.let { "$it %" } ?: "—")
        }
    }
}

/**
 * Self-only opsec posture: whether Aegis currently holds Device Admin
 * and Device Owner privileges.
 *
 * These two flags decide which protective powers are actually live:
 *  - Device Admin gates lock-now enforcement and the wipe-on-failure
 *    policy (AdminGate / AegisAdminReceiver).
 *  - Device Owner unlocks the stronger surface — silent permission
 *    grants, uninstall-block, and the Cyan shield tier.
 * The Capabilities screen lists this per-power; this card is the
 * at-a-glance "is my phone hardened?" summary on the device-status view,
 * next to the rest of this device's posture. Both are cheap local DPM
 * lookups that don't change within a session, so they're remembered once.
 */
@Composable
private fun OpsecPostureCard() {
    val context = LocalContext.current
    val adminActive = remember { app.aether.aegis.admin.AdminGate.isActive(context) }
    val ownerActive = remember { app.aether.aegis.admin.AdminGate.isDeviceOwner(context) }
    val onColor = app.aether.aegis.ui.theme.AegisOnline
    val offColor = app.aether.aegis.ui.theme.AegisOnSurfaceDim
    DeviceStatusCard("Opsec posture") {
        FieldRow(
            stringResource(R.string.security_device_admin),
            if (adminActive) "active ✔" else "inactive",
            if (adminActive) onColor else offColor,
        )
        FieldRow(
            stringResource(R.string.diagnostics_device_owner),
            if (ownerActive) "provisioned ✔" else "not provisioned",
            if (ownerActive) onColor else offColor,
        )
    }
}

/** The device's public key (monospace, full, not truncated — this is
 *  the verifiable identity) and its role relative to us. */
@Composable
private fun IdentityCard(peerKey: String, isSelf: Boolean) {
    DeviceStatusCard("Identity") {
        FieldRow("Public key", peerKey, mono = true)
        FieldRow("Role", if (isSelf) "self" else "paired peer")
    }
}

/** Shared card chrome for every section on this screen: an uppercase
 *  cyan title over a [GlassPanel], with the section's rows in [content].
 *  GlassPanel (not Material Card) per the app-wide LunaGlass treatment. */
@Composable
private fun DeviceStatusCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    // GlassPanel rather than Material Card — per the LunaGlass audit,
    // every card-shaped surface in the app should use the LunaGlass
    // glass treatment (1 dp cyan border + tinted backdrop + corner
    // radius) instead of Material Card's flat surface.
    app.aether.aegis.ui.components.GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title.uppercase(),
                color = app.aether.aegis.ui.theme.AegisCyan,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/** A label : value row inside a card. 40/60 weight split. [valueColor]
 *  null falls back to the default on-surface colour; [mono] switches the
 *  value to a monospace face (used for keys / fixed-width data). */
@Composable
private fun FieldRow(
    label: String,
    value: String,
    valueColor: Color? = null,
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
            fontSize = 13.sp,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            value,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(0.6f),
        )
    }
}

/** Slot variant of [FieldRow] — same label column, but the value is an
 *  arbitrary composable (used for the Signal row's bars + dBm caption). */
@Composable
private fun FieldRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
            fontSize = 13.sp,
            modifier = Modifier.weight(0.4f),
        )
        Row(
            modifier = Modifier.weight(0.6f),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Coarse "Ns/Nm/Nh/Nd ago" relative-age label. Single largest unit, no
 *  rounding — for the at-a-glance presence/last-ping captions. */
private fun formatAge(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60     -> "${s}s ago"
        s < 3600   -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else       -> "${s / 86_400}d ago"
    }
}

/** "HH:mm:ss · N ago" — wall-clock time of [ts] plus its relative age.
 *  Local time zone / locale (this is a same-device, human-facing label,
 *  not a wire format). */
private fun formatAbsoluteAndAge(ts: Long, nowMs: Long): String {
    val date = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(ts))
    return "$date · ${formatAge(nowMs - ts)}"
}

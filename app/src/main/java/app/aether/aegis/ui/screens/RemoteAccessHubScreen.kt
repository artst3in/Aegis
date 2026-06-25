package app.aether.aegis.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import app.aether.aegis.AegisApp
import app.aether.aegis.admin.AdminGate
import app.aether.aegis.remote.RemoteAccessHandler
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.AegisTopBar
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.components.HexSwitch
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisSOS
import app.aether.aegis.ui.theme.AegisWarning
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.launch

/**
 * Remote Access Hub — the single, centralised control surface for "who can
 * remote-control THIS phone."
 *
 * Why this screen exists:
 *  - Before it, the per-contact remote-access GRANT lived on the contact card
 *    and the BLOCK lived in two places (contact card + the operator
 *    RemoteAccessScreen). Three controls for one concept, easy to lose track of
 *    who actually has access. This hub is now the ONE place that grant is
 *    managed; the contact card only deep-links here as a read-only status hop.
 *
 * Hard gate on Device Admin (owner's call: "if you want remote control, give
 * the app admin rights"):
 *  - Remote LOCATE / lock / wipe all go through DevicePolicyManager and are
 *    inert without an active Device Admin. Granting a contact access while admin
 *    is off would hand them buttons that silently no-op in the exact emergency
 *    they were granted for. So when admin is NOT active this screen refuses to
 *    show the contact list at all and instead surfaces a one-tap enrol CTA. The
 *    only way past the gate is to activate Device Admin — which is the whole
 *    point.
 *
 * Opt-in model (already enforced target-side in RemoteAccessHandler.handleAuth):
 *  - A contact can drive this device only if `remoteAccessEnabled` is true for
 *    them AND they aren't in the revoke set. The per-row switch here is the
 *    canonical control for that grant. Turning it ON also clears any sticky
 *    revoke (duress / failed-PIN auto-revoke / a previous panic) so a single
 *    toggle fully restores a contact — no separate "un-revoke" to hunt for.
 *    Turning it OFF revokes + tears down any live stream + closes sessions, so
 *    access is cut immediately, not at the next session timeout.
 *
 * Panic button ("Revoke everyone"):
 *  - One tap cuts every contact's access at once — for a phone believed
 *    compromised. Confirm-gated so it can't be a fat-finger.
 *
 * Duress: the granted-contacts list is sensitive (it reveals who can reach this
 * device). Under a decoy unlock the hub shows nothing, the same rule the chat
 * list and pending-invitations screen apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteAccessHubScreen(navController: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val inDuress = AegisApp.instance.lockState.inDuressMode

    // Device Admin state. Re-read on every resume (the user may enrol from the
    // OS dialog and come back) and via the enrol launcher's result callback so
    // the gate flips the moment admin is granted without a manual refresh.
    var adminActive by remember { mutableStateOf(AdminGate.isActive(ctx)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) adminActive = AdminGate.isActive(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val enrollLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { adminActive = AdminGate.isActive(ctx) }

    // All known peers + the live revoke set. The hub lists only TRUSTED Aegis
    // peers (remote access is a Trusted-only, Aegis-only capability) that aren't
    // hard-blocked.
    val allPeers by AegisApp.instance.repository.observeKnownPeers()
        .collectAsState(initial = emptyList())
    val revoked by AegisApp.instance.remoteAccessGate.revoked.collectAsState()
    val candidates = remember(allPeers) {
        allPeers.filter {
            it.trustTier == app.aether.aegis.data.TrustTier.TRUSTED.name &&
                it.isAegis && !it.blocked
        }
    }
    val grantedCount = candidates.count { it.remoteAccessEnabled }

    var showRevokeAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = {
                    // 18sp + single line: at the inherited titleLarge (~22sp)
                    // "REMOTE ACCESS" wrapped to two lines, which grew the bar
                    // and pushed the divider/content down vs other sub-screens
                    // (user report: top bar shifts between screens). 18sp
                    // matches the AEGIS wordmark and fits on one line.
                    Text(
                        "REMOTE ACCESS",
                        fontWeight = FontWeight.Bold,
                        color = AegisCyan,
                        fontSize = 18.sp,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
            )
        },
    ) { pad ->
        if (inDuress) {
            // Decoy unlock: never reveal who can reach this device.
            Box(
                modifier = Modifier.fillMaxSize().padding(pad).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No remote access configured.",
                    color = AegisOnSurfaceDim,
                    fontSize = 13.sp,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                "Let a trusted contact locate, lock, or wipe this phone in an " +
                    "emergency. They can do nothing without your PIN.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // ── Device Admin gate ──────────────────────────────────────────
            AdminGatePanel(
                active = adminActive,
                onGrant = {
                    enrollLauncher.launch(
                        AdminGate.enrollIntent(
                            ctx,
                            "Aegis needs device-admin rights so a trusted " +
                                "contact can lock or wipe this phone remotely " +
                                "if it is lost or stolen.",
                        ),
                    )
                },
            )

            // Hard gate: no admin → no contact list. The enrol CTA above is the
            // only way forward, matching the skill tree (the node is locked
            // behind Device Admin and inaccessible until it's active).
            if (!adminActive) return@Column

            Spacer(modifier = Modifier.height(16.dp))

            if (candidates.isEmpty()) {
                Text(
                    "No trusted contacts yet. Promote a contact to Trusted to " +
                        "let them be your emergency remote operator.",
                    color = AegisOnSurfaceDim,
                    fontSize = 13.sp,
                )
                return@Column
            }

            Text(
                "TRUSTED CONTACTS",
                color = AegisOnSurfaceDim,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))

            candidates.forEach { peer ->
                val isRevoked = peer.publicKey in revoked
                RemoteOperatorRow(
                    name = peer.displayName.ifBlank { peer.publicKey.take(12) },
                    // A grant that's been revoked (duress / failed PIN / panic)
                    // reads as OFF — toggling restores it (see below).
                    checked = peer.remoteAccessEnabled && !isRevoked,
                    blocked = peer.remoteAccessEnabled && isRevoked,
                    onChange = { now ->
                        if (now) {
                            // Grant + clear any sticky revoke in one action so a
                            // single toggle fully restores access and tells the
                            // peer their cached "revoked" flag is cleared.
                            scope.launch {
                                AegisApp.instance.repository
                                    .setRemoteAccessEnabled(peer.publicKey, true)
                            }
                            AegisApp.instance.remoteAccessGate.unrevoke(peer.publicKey)
                            RemoteAccessHandler.broadcastUnrevoked(peer.publicKey)
                        } else {
                            // Cut access now: drop the grant, revoke (so even a
                            // correct PIN is dropped before it's checked), kill
                            // any live stream, and close warm sessions.
                            scope.launch {
                                AegisApp.instance.repository
                                    .setRemoteAccessEnabled(peer.publicKey, false)
                            }
                            val gate = AegisApp.instance.remoteAccessGate
                            gate.revoke(peer.publicKey)
                            gate.closeAllForSender(peer.publicKey)
                            RemoteAccessHandler.stopStreamsFor(peer.publicKey)
                        }
                    },
                )
            }

            // ── Panic: revoke everyone ─────────────────────────────────────
            if (grantedCount > 0) {
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { showRevokeAll = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AegisSOS),
                ) {
                    Text("REVOKE EVERYONE", fontWeight = FontWeight.Bold)
                }
                Text(
                    // NB: the trigger is a compromised/untrusted OPERATOR, not a
                    // compromised THIS-phone — if your own phone is taken you
                    // want operators to KEEP access so they can wipe/locate it.
                    "Removes remote access from all $grantedCount contact" +
                        (if (grantedCount > 1) "s" else "") +
                        " at once — use if someone you trusted is now a threat, " +
                        "or a contact's device may be compromised.",
                    color = AegisOnSurfaceDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showRevokeAll) {
        AlertDialog(
            onDismissRequest = { showRevokeAll = false },
            title = { Text("Revoke everyone?") },
            text = {
                Text(
                    "Every contact loses the ability to remote-control this " +
                        "phone immediately. Use this if someone you granted " +
                        "access to is now a risk — you can re-grant per contact " +
                        "later.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val gate = AegisApp.instance.remoteAccessGate
                    candidates.filter { it.remoteAccessEnabled }.forEach { peer ->
                        scope.launch {
                            AegisApp.instance.repository
                                .setRemoteAccessEnabled(peer.publicKey, false)
                        }
                        gate.revoke(peer.publicKey)
                        gate.closeAllForSender(peer.publicKey)
                        RemoteAccessHandler.stopStreamsFor(peer.publicKey)
                    }
                    showRevokeAll = false
                }) { Text("Revoke all", color = AegisSOS) }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeAll = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Device-Admin status banner + enrol CTA. When admin is missing this is an
 * amber warning with a Grant button (the hard gate); when present it's a quiet
 * cyan confirmation so the user can see the prerequisite is satisfied.
 */
@Composable
private fun AdminGatePanel(active: Boolean, onGrant: () -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AegisIcon(
                    AegisIcons.Lock,
                    contentDescription = null,
                    tint = if (active) AegisCyan else AegisWarning,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (active) "Device Admin active" else "Device Admin required",
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) AegisCyan else AegisWarning,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (active)
                    "Remote lock and wipe can run on this device."
                else
                    "Remote lock and wipe need device-admin rights. Grant them " +
                        "to enable remote access.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            if (!active) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(onClick = onGrant) {
                    Text("Grant admin rights")
                }
            }
        }
    }
}

/**
 * One trusted contact's remote-operator row: name, a one-line state, and the
 * grant switch. [blocked] (granted-but-revoked) is surfaced distinctly so the
 * user understands why a previously-granted contact reads as OFF and that
 * toggling restores them.
 */
@Composable
private fun RemoteOperatorRow(
    name: String,
    checked: Boolean,
    blocked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        blocked -> "Blocked — toggle on to restore access."
                        checked -> "Can locate, lock, and wipe with your PIN."
                        else -> "No remote access."
                    },
                    color = if (blocked) AegisSOS
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            HexSwitch(checked = checked, onCheckedChange = onChange)
        }
    }
}

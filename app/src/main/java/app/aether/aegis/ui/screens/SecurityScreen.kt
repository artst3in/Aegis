package app.aether.aegis.ui.screens

import androidx.compose.foundation.background
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
import app.aether.aegis.AegisApp
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.SkillNode
import app.aether.aegis.ui.components.SkillTreeView
import kotlinx.coroutines.launch
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisCyanGlow
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Security tab — the living shield, drawn as a skill tree.
 * Each hex IS a protection feature;
 * dependency edges between hexes trace honeycomb walls so the
 * branches read as a circuit board, not a checklist. APP PIN is
 * the trunk; left-branch = personal-defense (Mugshot, App Duress,
 * SOS Drill); upper-right = vault (Vault PIN → Vault Duress);
 * far-right = awareness (Canary → SIM Watch → Geofence). Device
 * Owner sits below the GROUND line — it's NG+, only enableable via
 * factory-reset + ADB.
 *
 * Voyager doesn't get a hex — protocol, always on. Remote Commands
 * doesn't get one either — per-contact, lives in ContactDetail.
 *
 * Tap any hex to open its detail / settings screen. The descriptive
 * row list below the tree mirrors the gating with text subtitles,
 * so users who don't want to read a graph still get the info.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(navController: NavController) {
    val context = LocalContext.current
    val aegisApp = AegisApp.instance
    val inDuress = aegisApp.lockState.inDuressMode

    // Live snapshots — re-read on every recomposition. Cheap: each
    // store reads from SharedPreferences which is a memory-backed
    // cache after first access.
    val appPinOn      = remember { aegisApp.lockState.store.hasPin }
    val appDuressOn   = remember { aegisApp.lockState.store.hasDuressPin }
    val vaultStore    = remember { app.aether.aegis.vault.VaultLockStore(context) }
    val vaultPinOn    = remember { vaultStore.hasPin }
    val vaultDuressOn = remember { vaultStore.hasDuressPin }
    val canaryOn      = remember { app.aether.aegis.canary.CanaryStore(context).enabled }
    val geofenceOn    = remember { app.aether.aegis.geofence.GeofenceStore(context).enabled }
    val simSwapOn     = remember { app.aether.aegis.simswap.SimSwapStore(context).enabled }
    val mugshotOn     = remember { app.aether.aegis.mugshot.MugshotStore(context).enabled }
    // Device Admin — the in-app one-tap enrollment (ACTION_ADD_DEVICE_ADMIN).
    // Returns true if EITHER the user accepted the in-app enrollment OR
    // they set Device Owner via ADB — DO promotion automatically marks
    // the admin receiver active. Either path lights this node, which is
    // the design: Owner is two-for-one and gets Admin "for free".
    val deviceAdminOn = remember {
        app.aether.aegis.admin.AdminGate.isActive(context)
    }
    // Device Owner is the unlock for the three setup-dependent
    // capabilities (silent install, keyguard/status-bar lockdown,
    // evict-credential-key on lock). It can only be enabled via ADB
    // after a factory reset — not toggleable in-app. Defined up here
    // (rather than after the list, where it used to live) so the
    // descriptive readout below can carry it as its own row.
    val isDeviceOwner = remember {
        app.aether.aegis.admin.DeviceOwnerStatus.isActive(context)
    }
    // Remote-access hub node lights only once Device Admin is active AND at
    // least one trusted Aegis contact is enlisted as a remote operator
    // (remoteAccessEnabled). Observed so the node + row update live when a grant
    // is toggled in the hub.
    val peers by aegisApp.repository.observeKnownPeers()
        .collectAsState(initial = emptyList())
    val remoteOperatorOn = peers.any {
        it.trustTier == app.aether.aegis.data.TrustTier.TRUSTED.name &&
            it.isAegis && it.remoteAccessEnabled
    }

    // Skill-tree feature list — used both for the visual tree
    // (above) and the descriptive row list (below). The tree gates
    // children on parents existing (App duress needs App PIN, etc.)
    // so locked tiles render dim and tap-routes through to the
    // parent's setup screen.
    val features = listOf(
        ShieldHex(stringResource(R.string.security_app_pin),      appPinOn,    "settings/lock",     "Lock-screen PIN"),
        ShieldHex(stringResource(R.string.vault_pin_vault_pin),    vaultPinOn,  "settings/vaultpin", "Optional vault gate"),
        ShieldHex(
            "App duress", appDuressOn, "settings/lock",
            if (appPinOn) "Decoy unlock + silent sos"
            else "Set the App PIN first",
            locked = !appPinOn,
        ),
        ShieldHex(
            "Vault duress", vaultDuressOn, "settings/vaultpin",
            if (vaultPinOn) "Hidden vault under a second PIN"
            else "Set the Vault PIN first",
            locked = !vaultPinOn,
        ),
        ShieldHex(stringResource(R.string.security_canary),       canaryOn,    "settings/canary",   stringResource(R.string.loki_deadmans_switch)),
        ShieldHex(stringResource(R.string.security_geofence),     geofenceOn,  "settings/geofence", "Alert on leaving zone"),
        ShieldHex(stringResource(R.string.security_sim_watch),    simSwapOn,   "settings/simswap",  "Alert on SIM swap"),
        ShieldHex(
            stringResource(R.string.security_mugshot), mugshotOn, "settings/mugshot",
            if (appPinOn) "Snap on wrong PIN"
            else "Set the App PIN first",
            locked = !appPinOn,
        ),
        // Device Admin + Device Owner — surfaced as their own readout
        // rows (not just skill-tree hexes) so the text list matches the
        // tree node-for-node, the way the user expects every node to
        // have a row. Admin routes into Lock settings (the one-tap
        // ACTION_ADD_DEVICE_ADMIN button). Owner has no in-app toggle —
        // it is ADB-only after a factory reset — so its row routes to
        // the same Lock screen where the explainer lives, and reads as
        // locked until it's actually granted.
        ShieldHex(
            stringResource(R.string.security_device_admin), deviceAdminOn, "settings/deviceadmin",
            if (appPinOn) "One-tap enrollment"
            else "Set the App PIN first",
            locked = !appPinOn,
        ),
        ShieldHex(
            stringResource(R.string.diagnostics_device_owner), isDeviceOwner, "settings/deviceadmin",
            if (isDeviceOwner) "Full unattended control"
            else "ADB-only after factory reset",
            locked = !isDeviceOwner,
        ),
        // Remote access — the emergency operator grant. Branches off Device
        // Admin (the capability is inert without it), so it's locked until
        // admin is active, then grey until a trusted operator is enlisted.
        ShieldHex(
            "Remote access", remoteOperatorOn, "settings/remote-access-hub",
            if (deviceAdminOn)
                (if (remoteOperatorOn) "Trusted operators enlisted" else "Add a trusted operator")
            else "Enable Device Admin first",
            locked = !deviceAdminOn,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // No page title — the bottom-nav label ("Opsec") already names
        // this tab. Dropped the redundant "OPSEC" heading so every tab
        // is uniform under the shared AEGIS header; the one-line purpose
        // blurb below stays because it's actual guidance, not a title.
        // No leading spacer — the header-to-content gap is the central
        // 8dp owned by MainActivity, so this tab starts flush like the rest.
        Text(
            stringResource(R.string.security_your_living_shield_each),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Shield tier badge — current frame + node count.
        // The colour foreshadows the avatar
        // frame the family dashboard will draw around this profile.
        val sentinelConfiguredForTier = remember {
            runCatching {
                app.aether.aegis.sentinel.SentinelPrefs(aegisApp).isFullyConfigured()
            }.getOrDefault(false)
        }
        val nodeCount = listOf(
            appPinOn, appDuressOn, vaultPinOn, vaultDuressOn,
            canaryOn, geofenceOn, simSwapOn, mugshotOn,
            deviceAdminOn,
            // SOS Drill is designed but not wired yet.
            isDeviceOwner,
            sentinelConfiguredForTier,
        ).count { it }
        val tier = app.aether.aegis.admin.ShieldTierEngine.tierFor(nodeCount)
        ShieldTierBadge(tier = tier, nodes = nodeCount)
        Spacer(modifier = Modifier.height(8.dp))

        // Skill-tree visual. Replaces
        // the earlier hex-flower cluster. Each hex IS a feature;
        // dependency edges trace honeycomb walls between them. Device
        // Owner sits below GROUND because it can only be turned on
        // via ADB / factory reset.
        val treeNodes = buildSkillTreeNodes(
            appPinOn      = appPinOn,
            appDuressOn   = appDuressOn,
            vaultPinOn    = vaultPinOn,
            vaultDuressOn = vaultDuressOn,
            canaryOn      = canaryOn,
            geofenceOn    = geofenceOn,
            simSwapOn     = simSwapOn,
            mugshotOn     = mugshotOn,
            deviceAdminOn = deviceAdminOn,
            isDeviceOwner = isDeviceOwner,
            remoteOperatorOn = remoteOperatorOn,
        )
        SkillTreeView(
            nodes = treeNodes,
            edges = SKILL_TREE_EDGES,
            // Hand-routed dependency polylines (from
            // marked screenshots — "lead polyline there"):
            //  - PIN→SOS via (-2,0): out App PIN's upper-left (the
            //    Mugshot side) then down, off the Device-Admin side.
            //  - Admin→DO via (1,2): bulge the Device Owner branch out
            //    to the right so the trunk doesn't read as one fused
            //    vertical line.
            routeVia = mapOf(
                // (ID_PIN to ID_SOS) waypoint removed with the node.
                (ID_ADMIN to ID_DO) to (1 to 2),
            ),
            onNodeTap = { node ->
                if (node.route.isNotEmpty()) navController.navigate(node.route)
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Brief readout below the cluster — gives the user the same
        // information as the hex glows in text form, plus subtitles
        // for what each feature does.
        features.forEach { f ->
            ShieldRow(feature = f, onClick = { navController.navigate(f.route) })
        }

        // Sonar is no longer surfaced as a standalone Security tile — it is
        // Sentinel's detector and is reached only through the Sentinel
        // controls (Experimental → Sentinel → Sonar viewer).

        if (inDuress) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                stringResource(R.string.security_duress_mode_active_some),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * One-line tier badge — frame colour + tier name + node count.
 * Sits between the SECURITY header and the skill tree on the
 * Security tab. The same colour will draw around the user's avatar
 * once the family dashboard wires up frames.
 */
@Composable
private fun ShieldTierBadge(tier: app.aether.aegis.admin.ShieldTier, nodes: Int) {
    app.aether.aegis.ui.components.GlassPanel(
        glow = tier == app.aether.aegis.admin.ShieldTier.Cyan,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Six-hex strip: one per tier, lit up to the current
            // tier's index. Reads as a progress bar without needing
            // a separate label per pip — the colour escalation is
            // the signal.
            val tiers = app.aether.aegis.admin.ShieldTier.values()
            val achievedIdx = tier.ordinal
            tiers.forEachIndexed { idx, t ->
                val on = idx <= achievedIdx
                Box(
                    modifier = Modifier
                        .size(width = 14.dp, height = 8.dp)
                        .padding(end = 2.dp)
                        .background(
                            if (on) t.color() else AegisOnSurfaceDim.copy(alpha = 0.2f),
                        ),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tier.displayName,
                    fontWeight = FontWeight.SemiBold,
                    color = tier.color(),
                )
                Text(
                    "$nodes / ${app.aether.aegis.admin.ShieldTierEngine.MAX_NODES} nodes lit",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * Skill-tree node positions (axial flat-top q, r) and edges.
 * The graph is the REAL
 * dependency tree — every node gates on its true parent, no
 * "visual chain" cheats:
 *
 *   App PIN (trunk) → App Duress, Mugshot, Vault PIN, Canary,
 *                     Geofence, SIM Watch, SOS Drill
 *   Vault PIN → Vault Duress
 *   Device Owner: independent root, below GROUND (NG+ via ADB)
 *
 * Positions chosen so:
 *   (a) The cube-line path between any connected pair traces
 *       through ONLY empty cells — no edge crosses another node.
 *   (b) No two PIN-children are direct hex neighbours of each
 *       other — the three-nodes-bunched complaint about the
 *       earlier layout was Canary/Geofence/Duress landing in
 *       three adjacent cells. Children are at least 2 axial
 *       distance from each other.
 *   (c) Each pin-out polyline diverges from its siblings within
 *       one hex of pin, so pin → vault and pin → sim don't read
 *       as "SIM is gated behind Vault" — they fan out
 *       immediately into different quadrants.
 *   (d) Sibling NODES don't sit adjacent to ANOTHER sibling's
 *       cube-line path either — earlier layout had Canary at
 *       (1,-3) hugging the pin → geo vertical (Geofence read as
 *       gated by Canary) and Duress at (-1,-2) touching three
 *       cells of the pin → SOS trace (Duress read as part of
 *       the path to Drill). Canary pushed up-right to (2,-4),
 *       Duress and SOS stepped up-left to (-2,-3) and (-4,-1)
 *       so each branch has clear air around it.
 *
 * Hand-traced paths:
 *
 *   pin → mugshot (-3, 0)         cells (0,0) → (-1,0) → (-2,0) → (-3,0)
 *   pin → duress  (-2,-3)         cells (0,0) → (0,-1) → (-1,-1) → (-1,-2) → (-2,-2) → (-2,-3)
 *   pin → SOS   (-4,-1)         cells (0,0) → (-1,0) → (-2,0) → (-2,-1) → (-3,-1) → (-4,-1)
 *   pin → vault   ( 3,-1)         cells (0,0) → (1,0) → (2,-1) → (3,-1)
 *   pin → canary  ( 2,-4)         cells (0,0) → (1,-1) → (1,-2) → (2,-3) → (2,-4)
 *   pin → sim     ( 3,-3)         cells (0,0) → (1,-1) → (2,-2) → (3,-3)
 *   pin → geo     ( 0,-4)         cells (0,0) → (0,-1) → (0,-2) → (0,-3) → (0,-4)
 *   pin → DO      ( 0, 3)         cells (0,0) → (0, 1) → (0, 2) → (0, 3)
 *   vault → vDur  ( 5,-4)         cells (3,-1) → (4,-2) → (4,-3) → (5,-4)
 */
private const val ID_PIN          = "pin"
private const val ID_VAULT        = "vault"
private const val ID_DURESS       = "duress"
private const val ID_VAULT_DURESS = "vaultDuress"
private const val ID_CANARY       = "canary"
private const val ID_GEOFENCE     = "geofence"
private const val ID_SIM          = "sim"
private const val ID_MUGSHOT      = "mugshot"
// ID_SOS removed with the SOS Drill node (not wired yet).
private const val ID_ADMIN        = "deviceAdmin"
private const val ID_DO           = "deviceOwner"
private const val ID_REMOTE_HUB   = "remoteAccessHub"

private val SKILL_TREE_EDGES: List<Pair<String, String>> = listOf(
    // App PIN → seven direct children (every PIN-gated node).
    // Mugshot and SOS Drill are SIBLINGS off App PIN, both on the
    // left — neither gates the other. SOS must
    // NOT depend on Mugshot, so the edge is PIN→SOS, not
    // Mugshot→SOS. With App PIN lifted to (0,-1) and both children
    // in the left column (Mugshot -3,0 / SOS -3,2), the two traces
    // leave App PIN's left-hand walls and fork apart immediately —
    // one branch off the trunk that splits into Mugshot + SOS,
    // nowhere near Device Admin on the trunk-base below (Admin is not
    // in the SOS path).
    ID_PIN    to ID_MUGSHOT,
    ID_PIN    to ID_DURESS,
    // ID_PIN → ID_SOS removed with the SOS Drill node (not wired yet).
    ID_PIN    to ID_VAULT,
    ID_PIN    to ID_CANARY,
    ID_PIN    to ID_SIM,
    ID_PIN    to ID_GEOFENCE,
    // (Sonar edge removed 2026-06-04 — Sonar is Experimental and no
    // longer a skill-tree node.)
    // Vault PIN → Vault Duress (the only real sub-branch).
    ID_VAULT  to ID_VAULT_DURESS,
    // Device Admin sits ON the trunk-base — directly below PIN,
    // above ground. Enrolling Admin is the in-app one-tap step
    // (ACTION_ADD_DEVICE_ADMIN); Device Owner is the further ADB
    // step. PIN → Admin → DO is the dependency chain: PIN gates
    // Admin, Admin is a prerequisite for the Owner promotion to
    // make sense (Owner auto-activates the admin receiver as
    // part of `dpm set-device-owner`, so reaching DO without
    // Admin is impossible by construction).
    ID_PIN    to ID_ADMIN,
    ID_ADMIN  to ID_DO,
    // Remote access hangs off Device Admin (one short branch to its left): the
    // remote LOCATE/lock/wipe capability is inert without admin, so the tree
    // shows the dependency literally — you can't light Remote access until
    // Device Admin is lit first.
    ID_ADMIN  to ID_REMOTE_HUB,
)

private fun buildSkillTreeNodes(
    appPinOn: Boolean,
    appDuressOn: Boolean,
    vaultPinOn: Boolean,
    vaultDuressOn: Boolean,
    canaryOn: Boolean,
    geofenceOn: Boolean,
    simSwapOn: Boolean,
    mugshotOn: Boolean,
    deviceAdminOn: Boolean,
    isDeviceOwner: Boolean,
    remoteOperatorOn: Boolean,
): List<SkillNode> = listOf(
    // Trunk — App PIN. Nudged one cell UP from the origin
    // (layout review): at (0, 0) it sat flush against
    // Device Admin (0, 1) and the two lit hexes read as one blob.
    SkillNode(
        id = ID_PIN,
        label = "App PIN",
        q = 0, r = -1,
        active = appPinOn,
        // Pulse when the trunk is empty — "start here" affordance.
        pulse  = !appPinOn,
        route  = "settings/lock",
    ),
    // Left side — three nodes in three distinct directions so the
    // pin→{mugshot,duress,sos} polylines diverge immediately
    // after leaving pin's hex instead of overlapping.
    SkillNode(
        id = ID_MUGSHOT,
        label = "Mugshot",
        q = -3, r = 0,
        active = mugshotOn,
        locked = !appPinOn,
        route  = "settings/mugshot",
    ),
    SkillNode(
        id = ID_DURESS,
        label = "App Duress",
        q = -2, r = -3,
        active = appDuressOn,
        locked = !appPinOn,
        route  = "settings/lock",
    ),
    // SOS Drill node removed: the drill flow is
    // designed but not wired, so a permanently-locked node just cluttered the
    // tree. Re-add here (+ the ID_PIN→ID_SOS edge) when the drill flow
    // ships. It was never counted toward the tier, so removal is cosmetic.
    // Right side — Vault PIN → Vault Duress is the only real
    // sub-branch. SIM Watch + Canary spread upward, Geofence
    // pushed further up so it doesn't sit next to Canary.
    SkillNode(
        id = ID_VAULT,
        label = "Vault PIN",
        // Up-right one cell from (3, -1) (layout
        // review) so it isn't stacked flush above Sonar. Up-right
        // (not up-left) keeps it clear of the App PIN→Sonar polyline.
        q = 4, r = -2,
        active = vaultPinOn,
        locked = !appPinOn,
        route  = "settings/vaultpin",
    ),
    SkillNode(
        id = ID_VAULT_DURESS,
        label = "Vault Duress",
        q = 5, r = -4,
        active = vaultDuressOn,
        locked = !vaultPinOn,
        route  = "settings/vaultpin",
    ),
    SkillNode(
        id = ID_CANARY,
        label = "Canary",
        q = 2, r = -4,
        active = canaryOn,
        locked = !appPinOn,
        route  = "settings/canary",
    ),
    SkillNode(
        id = ID_SIM,
        label = "SIM Watch",
        q = 3, r = -3,
        active = simSwapOn,
        locked = !appPinOn,
        route  = "settings/simswap",
    ),
    SkillNode(
        id = ID_GEOFENCE,
        label = "Geofence",
        q = 0, r = -4,
        active = geofenceOn,
        locked = !appPinOn,
        route  = "settings/geofence",
    ),
    // Sonar is intentionally NOT a skill-tree node: it is an
    // Experimental feature (gated behind ExperimentalPrefs), so it is
    // surfaced only by the SonarLokiRow below the tree once unlocked,
    // not as a first-class hex everyone sees. Removed 2026-06-04.
    SkillNode(
        id = ID_ADMIN,
        label = "Device Admin",
        // Trunk-base position. PIN is the trunk at (0, 0), Device
        // Owner is the root deep underground at (0, 3). Admin
        // sits at (0, 1) — directly below PIN, ABOVE the ground
        // line (pixel y = sqrt(3) ≈ 1.73 vs ground at 2.6) — so
        // the visible tree-base extends one cell down before
        // disappearing underground at DO. PIN → Admin → DO reads
        // as trunk → trunk-base → root.
        q = 0, r = 1,
        active = deviceAdminOn,
        // Setting Admin without PIN doesn't make sense as a flow
        // (PIN is the trunk every other security feature depends
        // on); gate Admin behind PIN to match the rest of the
        // tree. Tap routes into the dedicated Device Admin screen
        // where the one-tap ACTION_ADD_DEVICE_ADMIN button lives.
        locked = !appPinOn,
        route  = "settings/deviceadmin",
    ),
    // Remote access — short branch down-left of Device Admin (0,1) into the
    // open pocket left of the trunk. Moved off the cramped (-1,1) where it
    // overlapped the App PIN / Device Admin cluster (user report). Stays ABOVE
    // the GROUND line — it's an in-app feature, not the NG+ Device-Owner zone
    // below — so it sits at the same vertical level as Device Admin, two cells
    // to its left.
    SkillNode(
        id = ID_REMOTE_HUB,
        label = "Remote Access",
        q = -2, r = 2,
        // Lit only once admin is on AND a trusted operator is enlisted.
        active = deviceAdminOn && remoteOperatorOn,
        // HARD gate: locked AND inaccessible without Device Admin (no
        // tapWhenLocked). The only way to unlock it is to activate the parent
        // Device Admin node — the "give the app admin rights" rule made literal.
        // Once admin is on it's reachable (grey until a contact is enlisted).
        locked = !deviceAdminOn,
        route  = "settings/remote-access-hub",
    ),
    SkillNode(
        id = ID_DO,
        label = "Device Owner",
        q = 0, r = 3,
        active = isDeviceOwner,
        // Until it's actually provisioned, Device Owner is an UNAVAILABLE node
        // (ADB-only after factory reset) and must READ as locked — dark + a
        // padlock — not as a plain tappable hex (user report: it "looks
        // tappable" but isn't enableable in-app). tapWhenLocked keeps the
        // explainer reachable on tap. Reaching DO via `dpm set-device-owner`
        // auto-activates the admin receiver, so achieving this also lights
        // [ID_ADMIN] for free ("two points for Owner" in the tier count).
        locked = !isDeviceOwner,
        tapWhenLocked = true,
        route  = "settings/deviceadmin",
    ),
)

/** One feature in the cluster — its label, current state, and where
 *  tapping the hex should take the user.
 *
 *  [locked] = true greys the hex out and disables navigation. Used
 *  for the skill-tree gating: an App-duress or Vault-duress hex is
 *  locked until its parent PIN exists (you can't have a duress for
 *  a PIN that isn't set yet). */
private data class ShieldHex(
    val label: String,
    val active: Boolean,
    val route: String,
    val subtitle: String,
    val locked: Boolean = false,
)

/** Row beneath the tree — label + subtitle + status pill. Tap
 *  routes to the feature's detail screen, mirroring the hex tap. */
@Composable
private fun ShieldRow(feature: ShieldHex, onClick: () -> Unit) {
    app.aether.aegis.ui.components.GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Locked → LunaGlass padlock (was the raw 🔒 emoji); active →
                // filled cyan dot; available-but-off → hollow dot.
                if (feature.locked) {
                    AegisIcon(
                        AegisIcons.Lock,
                        contentDescription = "locked",
                        tint = AegisOnSurfaceDim.copy(alpha = 0.5f),
                        modifier = Modifier.size(12.dp),
                    )
                } else {
                    Text(
                        if (feature.active) "●" else "○",
                        color = if (feature.active) AegisCyan else AegisOnSurfaceDim,
                        fontSize = 10.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    feature.label,
                    fontWeight = FontWeight.SemiBold,
                    color = if (feature.locked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    feature.subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            if (!feature.locked) {
                TextButton(onClick = onClick) {
                    Text(if (feature.active) "Configure" else stringResource(R.string.contact_detail_enable))
                }
            }
        }
    }
}

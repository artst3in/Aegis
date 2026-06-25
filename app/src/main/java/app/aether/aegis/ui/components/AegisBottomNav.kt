package app.aether.aegis.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.R
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisSOS

/**
 * Five-tab hex nav. SOS is centered + scaled up so it reads as the
 * one button that matters. Cyan glow + cyan rim on active; red on
 * SOS. Inactive tabs dim out.
 */
@Composable
fun AegisBottomNav(navController: NavController, currentRoute: String?) {
    // Same nav in real + duress modes — the whole point of plausible
    // deniability is that the attacker can't tell which mode they're
    // in. Hiding tabs would reveal the decoy. The decoy surfaces are
    // populated with fake-family content but visually identical.
    // 5 tabs, SOS dead centre. Map absorbs
    // Status (battery + online/offline overlay on each pin). Security
    // is the new home for duress + Loki (mugshot, SIM swap, canary,
    // geofence) — everything threat-defense, out of Settings so
    // Settings stays clean.
    //
    // Default order — left → right = increasing
    // urgency, SOS locked centre:
    //   Settings | Security | SOS | Chats | Radar
    // Boring → Shield → Emergency → Daily use → Locate. Chats sits
    // at position 4 (right-of-centre thumb rest, 90 % right-handed).
    // Per-user override lives in TabOrderPrefs; the user reorders
    // the non-sos tabs via Settings → Nav, and SOS stays
    // anchored at index 2 of the final 5.
    //
    // Glyphs are LunaGlass vector drawables rather
    // than Unicode emoji. Vectors are 24×24 with content geometrically
    // centred in the viewport, so they finally sit OCD-centred in the
    // hex frame — Unicode emoji baselines never quite landed on the
    // hex's centre no matter how hard we trimmed font padding.
    val context = androidx.compose.ui.platform.LocalContext.current
    val tabPrefs = androidx.compose.runtime.remember { app.aether.aegis.ui.TabOrderPrefs(context) }
    val nonSOS by tabPrefs.nonSOSOrder.collectAsState()
    val tabs = androidx.compose.runtime.remember(nonSOS) {
        val byRoute = ALL_TABS.associateBy { it.route }
        val nonSOSTabs = nonSOS.mapNotNull { byRoute[it] }
        // Drop SOS into slot 2 of the 5-tab final list. Locked
        // central position is non-negotiable.
        // requireNotNull rather than !! — code review note: a
        // future refactor that renames "sos" could break the
        // assumption, and the explicit message names the cause.
        val sosTab = requireNotNull(byRoute["sos"]) {
            "ALL_TABS must contain a tab with route=sos"
        }
        buildList {
            addAll(nonSOSTabs.take(2))
            add(sosTab)
            addAll(nonSOSTabs.drop(2))
        }
    }

    // Protected Mode: System (settings) / Opsec (security) tabs lock IN
    // PLACE — never hidden, because hiding a tab would shift the row and
    // knock SOS off its non-negotiable centre slot (and destroy the
    // muscle memory for the one control that matters under stress). A
    // locked tab stays put, dims, gains a lock glyph, and its tap prompts
    // for the disarm PIN instead of navigating. Inert under duress
    // (invariant 3): a decoy session renders the nav exactly as normal.
    val pmArmed by app.aether.aegis.protectedmode.ProtectedMode.armed.collectAsState()
    val pmGates by app.aether.aegis.protectedmode.ProtectedMode.gates.collectAsState()
    val inDecoy = runCatching {
        app.aether.aegis.AegisApp.instance.lockState.inDuressMode
    }.getOrDefault(false)
    val lockedRoutes: Set<String> = if (pmArmed && !inDecoy) buildSet {
        if (app.aether.aegis.protectedmode.ProtectedMode.Gate.SYSTEM_TAB in pmGates) add("settings")
        if (app.aether.aegis.protectedmode.ProtectedMode.Gate.OPSEC_TAB in pmGates) add("security")
    } else emptySet()
    // Which locked tab (if any) the user just tapped — drives the disarm
    // PIN prompt. Cleared on dismiss / success.
    var pinPromptRoute by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 1px cyan divider on top of the nav — matched to the header's
        // bottom divider so both bars frame the content coherently.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AegisCyan.copy(alpha = 0.5f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Solid, opaque chrome — fixed furniture, content ends
                // above it (was alpha 0.94, which bled the map through).
                .background(MaterialTheme.colorScheme.background)
                // Below the gesture bar / nav bar so nothing's covered.
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // weight(1f) on every slot guarantees equal-width tabs, so
            // SOS — which is rendered larger than the others — sits
            // dead centre even with a different icon size. The old
            // SpaceAround arrangement put equal *gaps* between items,
            // which shifted the visual centre off-true the moment one
            // item had a different width.
            // Any conversation with unread inbound → a dot on the Comms tab.
            val unread by app.aether.aegis.prefs.UnreadTracker.observe()
                .collectAsState(initial = emptySet())
            tabs.forEach { tab ->
                val isActive = currentRoute == tab.route
                val isSOS = tab.route == "sos"
                val isLocked = tab.route in lockedRoutes
                val showBadge = (tab.route == "settings" && hasPendingUpdate()) ||
                    (tab.route == "chats" && unread.isNotEmpty())
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    NavTabHex(
                        tab = tab,
                        active = isActive,
                        isSOS = isSOS,
                        showBadge = showBadge,
                        isLocked = isLocked,
                        onClick = {
                            // Locked tab: the tap IS the disarm entry point
                            // (discoverable for the owner, useless to a
                            // child who can't enter the PIN). Otherwise
                            // navigate normally.
                            if (isLocked) pinPromptRoute = tab.route
                            else navController.navigate(tab.route) { popUpTo("chats") }
                        },
                    )
                }
            }
        }
    }

    // Disarm prompt — accepts the protected-mode PIN OR the app PIN
    // (master override, invariant 2). On success: disarm and complete the
    // navigation the user originally intended.
    pinPromptRoute?.let { route ->
        app.aether.aegis.ui.screens.ProtectedPinDialog(
            title = "Disarm Protected Mode",
            onDismiss = { pinPromptRoute = null },
            onSuccess = {
                app.aether.aegis.protectedmode.ProtectedMode.disarm()
                pinPromptRoute = null
                navController.navigate(route) { popUpTo("chats") }
            },
        )
    }
}

@Composable
private fun NavTabHex(
    tab: NavTab,
    active: Boolean,
    isSOS: Boolean,
    onClick: () -> Unit,
    showBadge: Boolean = false,
    isLocked: Boolean = false,
) {
    // No HexShape wrapper — the LunaGlass nav icons
    // already ARE hex outlines with content inside them (sos = hex
    // + exclamation, radar = hex + crosshair, etc.). Wrapping them in
    // HexShape's flat-top frame produced the double-hex-with-mismatched-
    // orientations mess: outer flat-top hex from HexShape + inner
    // pointy-top hex from the LunaGlass vector + the actual glyph in
    // the middle. The icon is the hex; we just tint it.
    // The active tab pushes FORWARD (bigger) into the light; inactive tabs sit
    // back, smaller, in shadow. SOS is always prominent.
    val iconSize = when {
        isSOS  -> 36.dp
        active -> 32.dp
        else   -> 28.dp
    }
    // Active non-sos tabs adopt the user's current ShieldTier
    // colour — Bronze / Silver / Gold /
    // Cyan as the user climbs the skill tree. Every tab switch
    // becomes a tiny visible reward without needing a separate
    // badge. None tier falls back to near-white (AegisOnSurface)
    // instead of the brand cyan — Cyan is the *crown*, so giving
    // it away for free at zero progress dilutes the reward.
    val tierCtx = androidx.compose.ui.platform.LocalContext.current
    // Observe the debug tier override so a preview change applies LIVE (the
    // flow is always null in release, so this is a no-op there). Keying the
    // tier lookup on it forces a recompute when the override flips.
    val dbgTierOverride by androidx.compose.runtime.remember(tierCtx) {
        app.aether.aegis.prefs.ExperimentalPrefs(tierCtx).debugTierFlow
    }.collectAsState()
    val currentTier = androidx.compose.runtime.remember(tierCtx, dbgTierOverride) {
        runCatching { app.aether.aegis.admin.ShieldTierEngine.currentTier(tierCtx) }
            .getOrDefault(app.aether.aegis.admin.ShieldTier.None)
    }
    val activeColor = if (currentTier == app.aether.aegis.admin.ShieldTier.None)
        app.aether.aegis.ui.theme.AegisOnSurface else currentTier.color()
    // Crown reward: once the user reaches the Cyan tier, the nav medals flip
    // from chrome to the holographic foil.
    val medalHolo = currentTier == app.aether.aegis.admin.ShieldTier.Cyan
    // Metal-medal shimmer on the tab glyph — same LunaGlass sheen toggle as
    // the chat bubbles. The nav icons are tier-coloured "medals", so they
    // glint as you tilt the phone. Self-acquires the tilt sensor only while
    // on (ref-counted), so it's free when the toggle is off.
    val navSheenOn by androidx.compose.runtime.remember(tierCtx) {
        app.aether.aegis.prefs.ExperimentalPrefs(tierCtx).glassSheenFlow
    }.collectAsState(initial = false)
    val medalGlint = navSheenOn && app.aether.aegis.ui.LocalGraphicsRich.current
    // Crown shimmer style: 0 = glassSheen glow, 1 = diffraction rainbow foil,
    // 2 = thin-film oil-slick foil. The crown-holder's choice (Settings →
    // Experimental → Crown shimmer). Observed so it applies live. Default 0.
    val crownStyle by androidx.compose.runtime.remember(tierCtx) {
        app.aether.aegis.prefs.ExperimentalPrefs(tierCtx).crownStyleFlow
    }.collectAsState(initial = 0)
    val tint = when {
        // Locked (Protected Mode) wins over every other state — a dimmed
        // tab must read as "inert" regardless of whether it's the active
        // route. SOS is never lockable, so this never dims the centre.
        isLocked         -> AegisOnSurfaceDim.copy(alpha = 0.4f)
        active && isSOS  -> AegisSOS
        active             -> activeColor
        isSOS            -> AegisSOS.copy(alpha = 0.65f)  // always visibly "danger"
        // Inactive: the PROPER (tier) colour but DIM — sitting back in shadow,
        // not a generic silver-grey. It lights up to full [activeColor] when
        // selected.
        else               -> activeColor.copy(alpha = 0.4f)
    }
    // Lighting model: an inactive tab is flat + faint (back in shadow, still
    // glinting a little); the selected tab protrudes hard into the light (full
    // glint + strong bevel). SOS keeps a strong bevel always (it must read
    // raised) but never shines.
    val navIntensity = if (active) METAL_SHEEN_INTENSITY else 2.5f
    val navBevel = when {
        isSOS  -> 0.8f
        active -> 0.85f
        else   -> 0.2f
    }
    // The shine treatment for this tab's glyph:
    //   SOS   → matte, bevel only (a shining emergency button is wrong).
    //   Cyan crown → the ORIGINAL lush full-reflection foil (glassSheen),
    //                intensity-scaled so an inactive crown tab fades back.
    //   metals → chrome glint + bevel (metalShine).
    val rectShape = androidx.compose.ui.graphics.RectangleShape
    val shineMod: Modifier = when {
        isSOS -> Modifier.metalShine(
            shape = rectShape, enabled = medalGlint, maskToContent = true,
            intensity = navIntensity, bevel = navBevel, shine = false,
        )
        medalHolo && crownStyle == 1 -> Modifier.holoFoil(
            shape = rectShape, enabled = medalGlint, maskToContent = true,
            intensity = navIntensity, thinFilm = false,
        )
        medalHolo && crownStyle == 2 -> Modifier.holoFoil(
            shape = rectShape, enabled = medalGlint, maskToContent = true,
            intensity = navIntensity, thinFilm = true,
        )
        medalHolo -> Modifier.glassSheen(
            shape = rectShape, enabled = medalGlint, maskToContent = true,
            intensity = navIntensity,
        )
        else -> Modifier.metalShine(
            shape = rectShape, enabled = medalGlint, maskToContent = true,
            intensity = navIntensity, bevel = navBevel, shine = true,
        )
    }
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val label = stringResource(tab.labelRes)
        Box(contentAlignment = Alignment.TopEnd) {
            if (tab.route == "map") {
                // Radar tab needs Compose-rendered rendering because
                // the LunaGlass radar glyph has a negative-space N
                // that a vector drawable + uniform tint can't render
                // Everything else is a normal
                // tinted vector.
                RadarHexIcon(
                    color = tint,
                    modifier = Modifier
                        .size(iconSize)
                        .then(shineMod),
                    size = iconSize,
                )
            } else {
                androidx.compose.foundation.Image(
                    painter = painterResource(tab.iconRes),
                    contentDescription = label,
                    colorFilter = ColorFilter.tint(tint),
                    modifier = Modifier
                        .size(iconSize)
                        .then(shineMod),
                )
            }
            if (isLocked) {
                // Small lock glyph in the corner — the "dimmed + lock"
                // affordance. Takes precedence over the update badge: a
                // locked Settings tab can't act on a pending update anyway.
                Text(
                    "🔒",
                    fontSize = 9.sp,
                    modifier = Modifier.offset(x = 3.dp, y = (-3).dp),
                )
            } else if (showBadge) {
                Box(
                    modifier = Modifier
                        .offset(x = (-2).dp, y = 2.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AegisCyan),
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label,
            color = tint,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
        )
    }
}

/** Observes UpdateState.pending so the Settings tab's badge dot
 *  reflects whether a check has surfaced a release the user hasn't
 *  installed yet. Snapshotted into a plain Boolean so the parent
 *  composable doesn't have to import the StateFlow plumbing. */
@Composable
private fun hasPendingUpdate(): Boolean {
    val pending by app.aether.aegis.update.UpdateState.pendingForBadge.collectAsState()
    return pending
}

private data class NavTab(
    val route: String,
    @androidx.annotation.StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
)

/** All five tabs by route id. The runtime list is composed from this
 *  + the user's TabOrderPrefs (with SOS locked at index 2). */
private val ALL_TABS = listOf(
    NavTab("security", R.string.nav_security, R.drawable.ic_aegis_security),
    NavTab("settings", R.string.nav_settings, R.drawable.ic_aegis_settings),
    NavTab("sos",    R.string.nav_sos,    R.drawable.ic_aegis_sos),
    NavTab("chats",    R.string.nav_chats,    R.drawable.ic_aegis_chats),
    NavTab("map",      R.string.nav_radar,    R.drawable.ic_aegis_radar),
)

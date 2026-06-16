package app.aether.aegis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import app.aether.aegis.ui.GraphicsPrefs
import app.aether.aegis.ui.GraphicsProfile
import app.aether.aegis.ui.components.GlassPanel
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Graphics tab — three-position profile slider on top, fine-grained
 * effect toggles below. Replaces the old per-effect LunaGlassSettings
 * UI: users overwhelmingly want one decision ("how much battery do I
 * want this thing to burn") rather than five separate toggles.
 *
 * The slider's positions, top down by ordinal:
 *   0  Performance   no refresh-rate cap, effects honour toggles
 *   1  Balanced      60 Hz cap, effects honour toggles
 *   2  Power saver   30 Hz cap, effects forced off, solid black bg
 *
 * Voyager publishes a ceiling profile based on battery level (the
 * curve in [app.aether.aegis.power.PowerBudget.ceilingGraphicsProfile]);
 * positions above that ceiling are disabled in the UI. The user can
 * always pick something equal-or-more conservative than the ceiling.
 *
 * The fine-grained toggles (hex grid, scan line, data rain, intensity)
 * still apply, but only in Performance + Balanced — in PowerSaver
 * they're force-disabled regardless of the toggle state. The toggle
 * row is dimmed and labelled to make that explicit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphicsSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { GraphicsPrefs(context) }

    // Force a battery snapshot on screen entry. PowerBudget's
    // ceiling StateFlow only updates on refresh() ticks (60 s
    // cadence from AegisApp). If the user opens this screen mid-tick
    // the ceiling shows its initial Performance value until the next
    // tick, which is the "slider stuck at max performance even at
    // 16 % battery until you touched it" bug — the touch happened
    // to land on the next composition after the tick fired.
    LaunchedEffect(Unit) {
        runCatching { app.aether.aegis.AegisApp.instance.powerBudget.refresh() }
    }

    val preferred by prefs.preferredFlow.collectAsState()
    val ceiling by app.aether.aegis.AegisApp.instance.powerBudget
        .ceilingGraphicsProfile.collectAsState()
    val batteryLevel by app.aether.aegis.AegisApp.instance.powerBudget.level
        .collectAsState(initial = 100)
    val charging by app.aether.aegis.AegisApp.instance.powerBudget.charging
        .collectAsState(initial = false)
    val effective = preferred.cappedBy(ceiling)

    // effective = what's ACTUALLY rendering = the user's preference
    // clamped by Voyager's battery ceiling. The toggle switches edit the
    // stored prefs directly (and mirror into local state for instant UI
    // feedback); the ceiling never touches the stored prefs, it only caps
    // what's live — so a low battery dims the toggles without losing the
    // user's choice.
    var hexGrid by remember { mutableStateOf(prefs.hexGrid) }
    var scanLine by remember { mutableStateOf(prefs.scanLine) }
    var dataRain by remember { mutableStateOf(prefs.dataRain) }
    var intensity by remember { mutableStateOf(prefs.effectsIntensity) }
    var cyanMascot by remember { mutableStateOf(prefs.cyanMascot) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.graphics_graphics)) },
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
            ProfileSliderPanel(
                preferred = preferred,
                effective = effective,
                ceiling = ceiling,
                batteryLevel = batteryLevel,
                charging = charging,
                onPick = { picked ->
                    // Voyager always wins — clamp the user's pick to
                    // something at-or-below the ceiling (= same or higher
                    // ordinal). They can't choose a more permissive
                    // profile than the battery allows.
                    val clamped = picked.cappedBy(ceiling)
                    prefs.preferred = clamped
                },
            )

            val effectsLive = effective != GraphicsProfile.PowerSaver
            if (!effectsLive) {
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            stringResource(R.string.graphics_effects_suppressed_in_power),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            stringResource(R.string.graphics_all_visual_effects_below) +
                                "profile is at Power saver — your individual toggles " +
                                "are remembered and will re-apply when the profile " +
                                "moves back to Balanced or Performance.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            EffectToggleRow(
                title = stringResource(R.string.graphics_hex_grid_backdrop),
                subtitle = stringResource(R.string.graphics_faint_repeating_hex_pattern),
                checked = hexGrid,
                enabled = effectsLive,
                onCheckedChange = { hexGrid = it; prefs.hexGrid = it },
            )
            EffectToggleRow(
                title = stringResource(R.string.graphics_scan_line),
                subtitle = stringResource(R.string.graphics_single_cyan_line_crawling),
                checked = scanLine,
                enabled = effectsLive,
                onCheckedChange = { scanLine = it; prefs.scanLine = it },
            )
            EffectToggleRow(
                title = stringResource(R.string.graphics_cyan_mascot),
                subtitle = stringResource(R.string.graphics_show_the_chibi_mascot) +
                    "onboarding, and tier-up. Off = legacy hex shield + plain " +
                    "text everywhere.",
                checked = cyanMascot,
                // NOT gated by effectsLive — the mascot is a brand
                // surface, not a backdrop effect. Stays togglable
                // even when the master effects switch is off.
                enabled = true,
                onCheckedChange = { cyanMascot = it; prefs.cyanMascot = it },
            )
            EffectToggleRow(
                title = stringResource(R.string.graphics_data_rain),
                subtitle = stringResource(R.string.graphics_tiny_hex_particles_drifting),
                checked = dataRain,
                enabled = effectsLive,
                onCheckedChange = { dataRain = it; prefs.dataRain = it },
            )
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.graphics_effects_intensity), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.graphics_scan_line_data_rain) +
                                    "they're distracting on a bright screen, higher " +
                                    "if they disappear at low brightness.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                        Text(
                            "${(intensity * 100).toInt()} %",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Slider(
                        value = intensity,
                        // Drag updates only the live state for a smooth
                        // preview; persist once on release so we don't
                        // thrash prefs storage on every drag frame.
                        onValueChange = { intensity = it },
                        onValueChangeFinished = { prefs.effectsIntensity = intensity },
                        valueRange = 0f..1f,
                        enabled = effectsLive,
                    )
                }
            }

            // ── Real glass (graduated from Experimental 2026-06-13) ──
            // Tilt-reactive sheen + 3D parallax on chat bubbles and the medal
            // frames. Both run the accelerometer and redraw per frame while on
            // screen, so they carry a real battery cost — hence they sit beside
            // the other effect toggles and grey out in Power saver (where rich
            // graphics, and so the glass itself, are off anyway). Backed by
            // ExperimentalPrefs still, so a user's existing choice carries over
            // unchanged from when these lived behind the 7-tap gate.
            val glassPrefs = remember { app.aether.aegis.prefs.ExperimentalPrefs(context) }
            // Scope for re-announcing the crown style to contacts when it
            // changes (so their copy of our medal updates), off the UI thread.
            val crownBroadcastScope = rememberCoroutineScope()
            var glassOn by remember { mutableStateOf(glassPrefs.glassSheenEnabled) }
            EffectToggleRow(
                title = "Real glass (chat bubbles)",
                subtitle = "Bubbles and medal frames catch the light as you tilt " +
                    "the phone. Looks great; costs battery (motion sensor + redraws).",
                checked = glassOn,
                enabled = effectsLive,
                onCheckedChange = { glassOn = it; glassPrefs.glassSheenEnabled = it },
            )
            var glass3dOn by remember { mutableStateOf(glassPrefs.glassThreeDEnabled) }
            EffectToggleRow(
                title = "Real glass — 3D (tilt the pane)",
                subtitle = "The whole chat pane protrudes and parallaxes as you tilt " +
                    "the phone. Pairs with the sheen above; same battery cost.",
                checked = glass3dOn,
                enabled = effectsLive,
                onCheckedChange = { glass3dOn = it; glassPrefs.glassThreeDEnabled = it },
            )

            // Crown shimmer — the EARNED Cyan-crown reward: once every node is
            // maxed, Cyan + the medals shimmer, and the holder picks HOW. Only
            // shown at the Cyan tier (un-earned users never see the control). A
            // pick is announced to Trusted contacts so their copy of our medal
            // restyles too. Debug builds can fake the tier via the override in
            // the Experimental section; ShieldTierEngine.currentTier honours it.
            val crownTierOverride by glassPrefs.debugTierFlow.collectAsState()
            val crownTier = remember(context, crownTierOverride) {
                runCatching { app.aether.aegis.admin.ShieldTierEngine.currentTier(context) }
                    .getOrDefault(app.aether.aegis.admin.ShieldTier.None)
            }
            if (crownTier == app.aether.aegis.admin.ShieldTier.Cyan) {
                val crownStyle by glassPrefs.crownStyleFlow.collectAsState()
                GlassPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Crown shimmer", fontWeight = FontWeight.SemiBold)
                        Text(
                            "You maxed every node — Cyan shimmers now. Choose how " +
                                "she catches the light. (Needs \"Real glass\" on.)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            listOf("Glow" to 0, "Rainbow" to 1, "Oil-slick" to 2).forEach { (label, mode) ->
                                val sel = crownStyle == mode
                                Text(
                                    label,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            glassPrefs.crownStyle = mode
                                            // Push the new style to Trusted
                                            // contacts so their copy of our
                                            // Cyan medal restyles too.
                                            crownBroadcastScope.launch {
                                                runCatching {
                                                    app.aether.aegis.admin.TierBroadcaster
                                                        .broadcastNow(context)
                                                }
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    color = if (sel) app.aether.aegis.ui.theme.AegisCyan
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The 3-position profile slider with live preview labels and a small
 * battery-curve hint when the ceiling clamps the user's choice.
 *
 * Implementation: a discrete Material Slider with `steps = 1` over the
 * range 0..2 — gives three snap positions that map directly to
 * GraphicsProfile.ordinal. Each tick label below shows whether the
 * corresponding position is allowed, picked, or above the ceiling.
 */
@Composable
private fun ProfileSliderPanel(
    preferred: GraphicsProfile,
    effective: GraphicsProfile,
    ceiling: GraphicsProfile,
    batteryLevel: Int,
    charging: Boolean,
    onPick: (GraphicsProfile) -> Unit,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(stringResource(R.string.graphics_graphics_profile), fontWeight = FontWeight.SemiBold)
            Text(
                effective.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Slider(
                value = preferred.ordinal.toFloat(),
                onValueChange = { v ->
                    val ord = v.toInt().coerceIn(0, GraphicsProfile.values().lastIndex)
                    val picked = GraphicsProfile.values()[ord]
                    onPick(picked)
                },
                valueRange = 0f..2f,
                steps = 1,
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                GraphicsProfile.values().forEach { profile ->
                    val isPicked = profile == preferred
                    val isEffective = profile == effective
                    // Ordinal grows MORE conservative (0 Performance →
                    // 2 PowerSaver). A profile below the ceiling's ordinal
                    // is more permissive than the battery allows → blocked.
                    val isBlocked = profile.ordinal < ceiling.ordinal
                    val color = when {
                        isBlocked -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        isPicked || isEffective -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = when (profile.ordinal) {
                            0 -> Alignment.Start
                            GraphicsProfile.values().lastIndex -> Alignment.End
                            else -> Alignment.CenterHorizontally
                        },
                    ) {
                        Text(
                            profile.label,
                            fontSize = 11.sp,
                            color = color,
                            fontWeight = if (isPicked) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            if (ceiling != GraphicsProfile.Performance) {
                Spacer(modifier = Modifier.height(10.dp))
                val explainer = if (charging) {
                    "Battery curve is suspended while charging — but cached " +
                        "ceiling hasn't refreshed yet (${ceiling.label})."
                } else {
                    "Battery currently caps you at ${ceiling.label.lowercase()} " +
                        "(at $batteryLevel %). Plug in or charge above the next " +
                        "threshold to unlock more permissive modes."
                }
                Text(
                    explainer,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }

            if (preferred != effective) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Your saved preference: ${preferred.label}. Effective right " +
                        "now: ${effective.label} (capped by battery).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * One labelled effect switch (title + subtitle + HexSwitch).
 *
 * When [enabled] is false (the master effects switch is off in Power
 * saver) the row dims its text AND forces the switch visually off
 * (`checked && enabled`) — the stored preference is untouched, this is
 * purely a "this won't apply right now" presentation. The mascot row is
 * the deliberate exception: its caller passes `enabled = true` so it
 * stays togglable regardless of the effects master, because it's a brand
 * surface rather than a battery-costing backdrop effect.
 */
@Composable
private fun EffectToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) Color.Unspecified
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (enabled) 1f else 0.5f),
                    fontSize = 12.sp,
                )
            }
            app.aether.aegis.ui.components.HexSwitch(
                checked = checked && enabled,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        }
    }
}


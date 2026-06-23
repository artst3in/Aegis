package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisOutlinedButton

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.AegisApp
import app.aether.aegis.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ============================================================
// SETTINGS
// ============================================================

@Composable
fun SettingsScreen(
    navController: NavController? = null,
) {
    // Same Settings surface in real + duress modes — the attacker must
    // not be able to tell modes apart. We DO hide the Duress PIN entry
    // panel inside LockSettingsScreen on the basis of inDuressMode so
    // the duress mechanism itself doesn't leak.
    val inDuress = AegisApp.instance.lockState.inDuressMode
    val scope = rememberCoroutineScope()
    // 7-tap-unlock gate state — hoisted to the outer scope because
    // BOTH the System-section's conditional Experimental row AND
    // the About card's cargo-version tap-counter need to read +
    // write the same ExperimentalPrefs instance. Re-declaring
    // inside either scope would make the other one orphan.
    val expCtx = LocalContext.current
    val expPrefs = remember { app.aether.aegis.prefs.ExperimentalPrefs(expCtx) }
    val expUnlocked by expPrefs.unlockedFlow.collectAsState()
    // Block-screenshots (FLAG_SECURE) toggle. Writing to the pref
    // flips the process-wide StateFlow MainActivity collects, so the
    // window's FLAG_SECURE updates live; local state drives the row.
    val screenSecPrefs = remember(expCtx) {
        app.aether.aegis.prefs.ScreenSecurityPrefs(expCtx)
    }
    var blockScreenshots by remember { mutableStateOf(screenSecPrefs.blockScreenshots) }
    Column(
        // Horizontal padding ONLY — no top or bottom frame. padding()
        // sits outside verticalScroll, so any top/bottom value becomes
        // a fixed band between the shared header/nav and the content.
        // Every tab's content sits flush below the header (ChatList /
        // Comms does the same), so the tabs read as one coherent whole
        // instead of each starting at a different height.
        // (2026-06-03 QOL — equal spacing on all tabs.)
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // No page title — the bottom-nav label ("System") already names
        // this tab; a redundant in-screen heading just wasted vertical
        // space and was styled differently from every other tab. The
        // header-to-content gap is owned centrally by MainActivity (a
        // single 8dp spacer for every tab) so each screen starts flush —
        // no leading spacer here, or this tab would sit lower than the
        // rest.

        // Profile (name + avatar + Edit) lives at the very top of
        // Settings — identity should be the first thing you
        // see, not buried below a dozen utility rows.
        ProfileCard(navController)

        // OutboxCard — transient state surface. Lives above the
        // structured section list because it's an ALERT, not a
        // navigation item. Self-hides when pending == 0; identical
        // to the chat-list banner pattern.
        OutboxCard()

        // ────────────────────────────────────────────────────────
        // SECTION LIST — five domains, 12 destinations
        //
        // Layout is the output of the settings-reorg analysis (full
        // Hick's Law + Miller's 7±2 + chunking analysis). Each
        // section header is a visual chunk so the user scans 5
        // headings, identifies the right one, then scans 2-3 items
        // within. Total scan cost drops from log₂(19) ≈ 4.25 (flat
        // 18 rows) to ~3.2 effective (chunked 5×3), a 25% drop in
        // decision time plus a measurable error-rate reduction.
        //
        // Security feature toggles (App PIN, Mugshot, Canary, SIM
        // Watch, Geofence, Vault PIN, Vault Duress, Device Admin,
        // Device Owner) intentionally do NOT live here — the
        // canonical entry point for every security toggle is the
        // Security skill tree.
        // Settings hosts only the non-security knobs.
        // ────────────────────────────────────────────────────────

        SettingsSection("Messaging") {
            // Notifications — deep-link OUT to Android's per-app
            // notification settings. That system surface is the canonical
            // place to control per-channel sound, vibration, importance,
            // and the lock-screen visibility of each Aegis channel
            // (messages, calls, SOS, sentinel) — duplicating those knobs
            // in-app would only drift out of sync with the OS. The
            // in-app complements are Quiet hours (below) and the
            // per-contact sound/mute on each contact card. Addresses the
            // "missing notification setting in app" report
            // (2026-06-07). Uses LocalContext; launching the settings
            // Activity backgrounds Aegis, so arm the picker-return grace
            // first or the idle relock fires on the way back.
            val notifCtx = LocalContext.current
            SettingsLinkRow(
                title = stringResource(R.string._notifications),
                subtitle = stringResource(R.string._perchannel_sound_vibration_and) +
                    "messages, calls, SOS, and sentinel alerts.",
                onClick = {
                    runCatching {
                        AegisApp.instance.lockState.armPickerReturn()
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                        ).putExtra(
                            android.provider.Settings.EXTRA_APP_PACKAGE,
                            notifCtx.packageName,
                        )
                        notifCtx.startActivity(intent)
                    }.onFailure {
                        // Fall back to the generic app-details page if the
                        // OEM doesn't honour the channel-settings action.
                        runCatching {
                            notifCtx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings
                                        .ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts(
                                        "package", notifCtx.packageName, null,
                                    ),
                                ),
                            )
                        }
                    }
                },
            )
            // In-app notification CONTENT privacy (full / name only / hidden) —
            // distinct from the OS link above, which controls sound/channels.
            // This decides how much of a message leaks to the shade.
            SettingsLinkRow(
                title = "Notification privacy",
                subtitle = "Hide sender names or message text on the lock screen.",
                onClick = { navController?.navigate("settings/notifications") },
            )
            SettingsLinkRow(
                title = stringResource(R.string.settings_quiet_hours),
                subtitle = stringResource(R.string._mute_chat_notifications_at),
                onClick = { navController?.navigate("settings/quiet") },
            )
            SettingsLinkRow(
                title = stringResource(R.string.hold_to_execute_hold_to_send_call),
                subtitle = stringResource(R.string._pressandhold_send_call_video),
                onClick = { navController?.navigate("settings/hold") },
            )
            SettingsLinkRow(
                title = stringResource(R.string._disappearing_default),
                subtitle = stringResource(R.string._autoburn_ttl_applied_to),
                onClick = { navController?.navigate("settings/chatdefaults") },
            )
            SettingsLinkRow(
                title = "Attachment downloads",
                subtitle = "WiFi-only, which types auto-download, size cap",
                onClick = { navController?.navigate("settings/attachments") },
            )
            SettingsLinkRow(
                title = stringResource(R.string.invitation_expiry_invitation_expiry),
                subtitle = stringResource(R.string._autorevoke_unused_11_invite),
                onClick = { navController?.navigate("settings/invites") },
            )
        }

        // Crash detection moved to Experimental (7-tap gate) 2026-06-04 —
        // experimental until field-tested. See ExperimentalSettingsScreen.

        SettingsSection(stringResource(R.string.help_privacy)) {
            // Block screenshots — FLAG_SECURE on the app window. One
            // flag, three effects (no screenshots, no screen
            // recording, blank Recents thumbnail). Off by default;
            // toggling is instant (MainActivity collects the prefs
            // flow). Inline toggle rather than a sub-screen — it's a
            // single boolean.
            SettingsToggleRow(
                title = stringResource(R.string._block_screenshots),
                subtitle = stringResource(R.string._stops_screenshots_screen_recording) +
                    "app-switcher preview from capturing Aegis.",
                checked = blockScreenshots,
                onCheckedChange = { next ->
                    blockScreenshots = next
                    screenSecPrefs.blockScreenshots = next
                },
            )
        }

        SettingsSection("Appearance") {
            SettingsLinkRow(
                title = stringResource(R.string.tab_order_nav_order),
                subtitle = stringResource(R.string._rearrange_the_bottom_tabs),
                onClick = { navController?.navigate("settings/nav") },
            )
            SettingsLinkRow(
                title = stringResource(R.string.graphics_graphics),
                subtitle = stringResource(R.string._performance_balanced_power_saver),
                onClick = { navController?.navigate("settings/graphics") },
            )
            SettingsLinkRow(
                title = stringResource(R.string._language),
                subtitle = languageSubtitle(LocalContext.current),
                onClick = { navController?.navigate("language?first=false") },
            )
        }

        SettingsSection("Data") {
            SettingsLinkRow(
                title = stringResource(R.string._profiles),
                subtitle = stringResource(R.string._multiple_isolated_aegis_profiles),
                onClick = { navController?.navigate("settings/profiles") },
            )
            SettingsLinkRow(
                title = stringResource(R.string._backup),
                subtitle = stringResource(R.string._encrypted_file_backup_data),
                onClick = { navController?.navigate("settings/backup") },
            )
        }

        SettingsSection("Network") {
            SettingsLinkRow(
                title = stringResource(R.string.relay_simplex_relays),
                subtitle = stringResource(R.string._advanced_override_the_default),
                onClick = { navController?.navigate("settings/relays") },
            )
            SettingsLinkRow(
                title = stringResource(R.string.settings_calls),
                subtitle = stringResource(R.string._relayonly_ice_and_call),
                onClick = { navController?.navigate("settings/calls") },
            )
        }

        SettingsSection(stringResource(R.string.settings_title)) {
            SettingsLinkRow(
                title = stringResource(R.string.diagnostics_diagnostics),
                subtitle = stringResource(R.string._health_probes_driver_reloads),
                onClick = { navController?.navigate("diagnostics") },
            )
            // Capabilities — read-only INFO about what Aegis does.
            // Lives in System (app self-knowledge) rather than Data
            // (which is YOUR data — profiles, backups, etc.) because
            // it describes the app's behaviour, not the user's
            // content.
            SettingsLinkRow(
                title = stringResource(R.string.capabilities_capabilities),
                subtitle = stringResource(R.string._readonly_inventory_of_every),
                onClick = { navController?.navigate("settings/capabilities") },
            )

            // Updates — kept at the BOTTOM of System, just above the
            // Experimental gate, so it's reachable after scrolling to
            // the end without scrolling back up (2026-06-03 QOL).
            // Absorbs what was a separate UpdateCard; the Settings-tab
            // bottom-nav badge dot (UpdateState.pendingForBadge)
            // signals when there's something to install.
            // Sideload channel only — the Play build doesn't self-update
            // (Play owns updates; the install permissions are stripped),
            // so the self-update screen would be inert and is hidden.
            if (app.aether.aegis.BuildConfig.SELF_UPDATE) {
                SettingsLinkRow(
                    title = stringResource(R.string.settings_updates),
                    subtitle = stringResource(R.string._wifionly_by_default_apks),
                    onClick = { navController?.navigate("settings/updates") },
                )
            }

            // Experimental — visible only once the user has tapped
            // the cargo version 7 times in the About card below.
            // [expPrefs] / [expCtx] / [expUnlocked] are hoisted in
            // the outer SettingsScreen scope (shared with the
            // About card's tap-counter).
            if (expUnlocked) {
                SettingsLinkRow(
                    title = stringResource(R.string._experimental),
                    subtitle = stringResource(R.string._unfinished_features_hidden_behind),
                    onClick = { navController?.navigate("settings/experimental") },
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            // LunaGlass chamfer — angular cut corners, not the Material default
        // RoundedCornerShape, so System cards speak the same hex geometry as
        // GlassPanel everywhere else (user report 2026-06-15: round corners).
        shape = androidx.compose.foundation.shape.CutCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Logo + wordmark / version share the top row so the
                // logo sits at brand height, NOT compressed against
                // the dna line (which used to force-wrap because the
                // Row was middle-aligned across the whole card).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        // Aether wordmark in monospace — same typographic
                        // pattern the design reference uses for axioms.
                        Text(
                            stringResource(R.string._project_aether),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string._aegis),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            app.aether.aegis.BuildConfig.AETHER_OFFICIAL,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // The hex shield — same artwork as the splash
                    // hold. Tap → Origins. When the user has reached
                    // Cyan tier (every skill-tree node lit), a faint
                    // cyan halo sits behind the shield as a passive
                    // reward — the brand colour bleeding through.
                    val cyanCtx = androidx.compose.ui.platform.LocalContext.current
                    val isCyanTier = app.aether.aegis.admin.ShieldTierEngine.currentTier(cyanCtx) ==
                        app.aether.aegis.admin.ShieldTier.Cyan
                    Box(
                        modifier = Modifier.size(96.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isCyanTier) {
                            androidx.compose.foundation.Canvas(
                                modifier = Modifier.size(96.dp),
                            ) {
                                drawCircle(
                                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                        colors = listOf(
                                            app.aether.aegis.ui.theme.AegisCyanGlow,
                                            androidx.compose.ui.graphics.Color.Transparent,
                                        ),
                                        center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2),
                                        radius = size.minDimension / 2,
                                    ),
                                    radius = size.minDimension / 2,
                                )
                            }
                        }
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(app.aether.aegis.R.mipmap.ic_aegis_foreground),
                            contentDescription = stringResource(R.string.splash_aegis_logo),
                            modifier = Modifier
                                .size(80.dp)
                                .clickable {
                                    navController?.navigate("settings/origins")
                                },
                        )
                    }
                }
                // Cyan moved OUT of this mid-card slot
                // (2026-06-03) — she now signs the card from the very
                // bottom, smaller, carrying the support ask in her
                // speech bubble (see the footer below). Keeping her
                // here AND at the bottom would double her up.
                Spacer(modifier = Modifier.height(8.dp))
                // 7-tap unlock for the Experimental section.
                var tapCount by remember { mutableIntStateOf(0) }
                val tapScope = rememberCoroutineScope()
                // Technical build details — Cargo version + git SHA + Build DNA.
                // Full card width now, no compete with the logo on the same row.
                Text(
                    "cargo  ${app.aether.aegis.BuildConfig.AETHER_CARGO}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable {
                        if (expPrefs.unlocked) return@clickable
                        tapCount += 1
                        if (tapCount >= 7) {
                            tapCount = 0
                            expPrefs.unlocked = true
                            tapScope.launch {
                                android.widget.Toast.makeText(
                                    expCtx,
                                    "Experimental features unlocked.",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        } else if (tapCount in 3..6) {
                            val remaining = 7 - tapCount
                            tapScope.launch {
                                android.widget.Toast.makeText(
                                    expCtx,
                                    "$remaining more taps…",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
                Text(
                    "sha    ${app.aether.aegis.BuildConfig.GIT_SHA.take(7)}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "dna    ${app.aether.aegis.BuildConfig.BUILD_DNA}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Support button. Cyan asks for
                // it herself, from the very bottom of About
                // (2026-06-03): her speech bubble carries the ask, with
                // her photo smaller, directly beneath it. The whole
                // block is the tap target — it opens the Revolut Pro
                // link in the system browser. No badges, no pitch, no
                // attention-drawing; only discoverable by scrolling all
                // the way down here, and the onboarding tutorial only
                // *points* to this spot rather than dropping a pay
                // button in a brand-new user's face.
                val ctx = LocalContext.current
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // bottom was 24dp; halved (2026-06-03 QOL)
                        // — stacked with the card's own 16dp padding it
                        // left a lot of dead space under Cyan at the
                        // very bottom of About.
                        .padding(top = 8.dp, bottom = 6.dp)
                        .clickable {
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(SUPPORT_URL),
                                    ),
                                )
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // showSignature = false: "Stay safe." is Cyan's
                    // tutorial closer, not her line here. Here she just
                    // asks for support.
                    app.aether.aegis.ui.components.CyanSpeechBubble(
                        message = "Support Project Aether",
                        showSignature = false,
                        // Cyan's photo sits BELOW this bubble, so the
                        // tail points down at her.
                        tail = app.aether.aegis.ui.components.BubbleTail.Down,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    // Waist-up pose (2026-06-03): in-app surfaces
                    // show a compact Cyan — she's a reminder here, not
                    // fully present. Full-body is reserved for the
                    // tutorial. Transparent bg, sits under her bubble.
                    app.aether.aegis.ui.components.CyanPose(
                        asset = app.aether.aegis.ui.components.CyanAsset.Onboard2,
                        size = 120.dp,
                    )
                }
            }
        }
    }
}

/** Revolut Pro destination for the Support footer link. Plain
 *  `const val` so a future move to BuildConfig is a single-site
 *  edit. The single use site is now the About card's bottom
 *  Cyan-and-bubble support affordance (above); the onboarding
 *  tutorial no longer opens a payment link directly — it only points
 *  users here.
 *  Kept `internal` rather than `private` so a future in-app support
 *  surface can reuse the SAME URL instead of copying it. */
internal const val SUPPORT_URL = "https://checkout.revolut.com/pay/7d734fa1-9e39-4ec3-a7e7-f22ea98742d9"

@Composable
private fun ProfileCard(navController: NavController?) {
    val profile = remember { AegisApp.instance.profileStore.snapshot() }
    Card(
        // Bottom-only inset: this is the first tab element, so its TOP
        // must be flush with the central 8dp header gap (a top inset here
        // would push Settings lower than the other tabs).
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        // LunaGlass chamfer — angular cut corners, not the Material default
        // RoundedCornerShape, so System cards speak the same hex geometry as
        // GlassPanel everywhere else (user report 2026-06-15: round corners).
        shape = androidx.compose.foundation.shape.CutCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarBubble(
                name = profile.displayName.ifBlank { "?" },
                avatarPath = profile.avatarPath,
                // Your OWN shield tier colours your own frame, the same metal
                // medal a peer sees. None → visible cyan (handled inside).
                tier = app.aether.aegis.admin.ShieldTierEngine.currentTier(AegisApp.instance),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.displayName.ifBlank { "(no name set)" },
                    fontWeight = FontWeight.SemiBold,
                )
                if (profile.bio.isNotBlank()) {
                    Text(
                        profile.bio,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { navController?.navigate("profile") }) {
                Text(stringResource(R.string.action_edit))
            }
        }
    }
}

@Composable
private fun OutboxCard() {
    val scope = rememberCoroutineScope()
    val pending by AegisApp.instance.repository.pendingCount().collectAsState(initial = 0)
    if (pending == 0) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        // LunaGlass chamfer — angular cut corners, not the Material default
        // RoundedCornerShape, so System cards speak the same hex geometry as
        // GlassPanel everywhere else (user report 2026-06-15: round corners).
        shape = androidx.compose.foundation.shape.CutCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string._outbox), fontWeight = FontWeight.SemiBold)
            Text(
                "$pending message${if (pending == 1) "" else "s"} queued for delivery. " +
                    "Aegis retries with exponential backoff; entries past 30 attempts " +
                    "(~5 min) auto-purge.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            AegisOutlinedButton(onClick = {
                scope.launch { AegisApp.instance.repository.clearOutbox() }
            }) {
                Text(stringResource(R.string._clear_all_queued))
            }
        }
    }
}

/** Subtitle for Settings → Language: shows the endonym of the currently
 *  picked language, or "Auto" if the user is following the device locale. */
private fun languageSubtitle(context: android.content.Context): String {
    val prefs = app.aether.aegis.i18n.LanguagePrefs(context)
    val tag = if (prefs.picked) prefs.tag else "system"
    return app.aether.aegis.i18n.LanguagePrefs.supported
        .firstOrNull { it.tag == tag }?.native
        ?: "Auto"
}

/** True iff the device's active connection is metered (cellular / paid
 *  hotspot). Used to block the manual Download button when the user
 *  has Wi-Fi-only set — the background worker already does this via
 *  WorkManager's NetworkType.UNMETERED constraint. */
private fun isUpdateNetworkMetered(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return true
    val net = cm.activeNetwork ?: return true
    val caps = cm.getNetworkCapabilities(net) ?: return true
    // Transport-based, NOT NET_CAPABILITY_NOT_METERED — mirrors
    // AutoUpdateCheck.currentlyMetered / UpdateCheckWorker. The capability
    // bit is unreliable: unlimited cellular often advertises NOT_METERED, so
    // the old check treated mobile as unmetered and let the manual "check
    // for updates" run over cellular even with Wi-Fi-only ON (user-reported:
    // "Wi-Fi-only set but it still updated on mobile"). Only actual
    // Wi-Fi/Ethernet counts as unmetered.
    val onWifiOrEthernet =
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    return !onWifiOrEthernet
}

/** Standard "settings entry" row — title + subtitle + chevron, full-
 *  width clickable, flat (no shadow, matches the rest of the page). */
/**
 * Visual chunk for the Settings list. Renders a small all-caps
 * cyan section label, then the supplied rows. Pure decoration —
 * the header is a Text, not a button or expandable surface, so
 * the user can't collapse a section accidentally. Chunking is
 * cognitive; the rows stay always-visible.
 *
 * Rationale: Hick's Law + Miller's 7±2
 * + chunking. Five sections × 2-3 rows each beats a flat 12-row
 * list on decision time by ~25% and on error rate by even more.
 *
 * Label style matches the section headers in DiagnosticsScreen
 * and AboutScreen — 11sp uppercase cyan with 1.5sp letter
 * spacing — so the Settings surface reads as part of the same
 * visual system.
 */
@Composable
private fun SettingsSection(label: String, content: @Composable () -> Unit) {
    Spacer(modifier = Modifier.height(14.dp))
    Text(
        label.uppercase(),
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
        color = app.aether.aegis.ui.theme.AegisCyan,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
    content()
}

@Composable
internal fun SettingsLinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        // LunaGlass chamfer — angular cut corners, not the Material default
        // RoundedCornerShape, so System cards speak the same hex geometry as
        // GlassPanel everywhere else (user report 2026-06-15: round corners).
        shape = androidx.compose.foundation.shape.CutCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Inline toggle row — same card shell as [SettingsLinkRow] but with
 *  a [HexSwitch] instead of the "›" chevron. Tapping anywhere on the
 *  row flips the switch (the whole card is the target, matching the
 *  link-row affordance). Used for one-boolean settings that don't
 *  warrant their own sub-screen, e.g. Privacy → Block screenshots. */
@Composable
internal fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onCheckedChange(!checked) },
        // LunaGlass chamfer — angular cut corners, not the Material default
        // RoundedCornerShape, so System cards speak the same hex geometry as
        // GlassPanel everywhere else (user report 2026-06-15: round corners).
        shape = androidx.compose.foundation.shape.CutCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            app.aether.aegis.ui.components.HexSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

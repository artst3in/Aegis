package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisButton

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.AegisApp
import app.aether.aegis.R
import app.aether.aegis.ui.components.CyanAsset
import app.aether.aegis.ui.components.CyanPose
import app.aether.aegis.ui.components.CyanSpeechBubble
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.components.HexShape
import app.aether.aegis.ui.components.RadarHexIcon
import app.aether.aegis.ui.components.ScratchToReveal
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisSOS
import app.aether.aegis.ui.theme.LunaGlassFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Onboarding tutorial — a 9-page,
 * Cyan-narrated, swipeable walkthrough that auto-shows once on a
 * fresh install and is replayable forever from Help.
 *
 * ## Why a separate flow from `profile/onboard`
 *
 * `ProfileScreen(isOnboarding = true)` is the FUNCTIONAL first run
 * (pick a display name, create identity). This tutorial is the
 * EDUCATIONAL layer that sits in front of it — it explains *what
 * Aegis is and why* before the user is asked to do anything. They
 * are deliberately decoupled: the tutorial can be replayed at any
 * time without re-triggering identity setup, and a user who skips
 * the tutorial still lands in the normal onboarding.
 *
 * ## First-launch gating
 *
 * The decision of whether to show this at all lives in
 * [firstRunNextDestination], called from both the splash and the
 * first-run language picker. It only auto-shows for a genuinely
 * fresh install (not yet onboarded AND the `tutorial_completed`
 * flag unset) so existing users updating into this build are never
 * surprised by it.
 *
 * ## Required-action page
 *
 * Page 6 embeds an inline PIN setup — the ONE page with a required
 * action. The user either sets a PIN (then the pager auto-advances)
 * or skips the whole tutorial. On replay, if a PIN already exists,
 * the page shows "PIN already set ✓" and Cyan says "Already done."
 *
 * @param navController host nav controller — used to leave the
 *        tutorial on completion / skip.
 * @param replay true when launched from Help → "Replay Tutorial".
 *        In replay mode, exiting pops back to wherever the user
 *        came from (Help) instead of advancing into onboarding,
 *        and the `tutorial_completed` flag is left as-is.
 */
@Composable
fun TutorialScreen(navController: NavController, replay: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // pageCount is fixed at 9 per spec. rememberPagerState takes a
    // lambda so the count can be hoisted later if pages become
    // conditional; today it's constant.
    val pagerState = rememberPagerState(pageCount = { TUTORIAL_PAGE_COUNT })

    // Security setup (recovery phrase + PIN) is MANDATORY, not optional.
    // ProfileScreen onboarding only collects a name, so the tutorial is
    // the sole place a fresh install roots its encryption (phrase) and
    // sets its lock (PIN). Skipping or swiping past those pages used to
    // drop the user into the app with neither — no encryption root, no
    // app lock (user-reported 2026-06-07). We track both flags here and
    // use them to (a) lock the pager on an unsatisfied security page and
    // (b) reroute Skip into the setup instead of out of it. On replay
    // (phrase + PIN already exist) nothing is gated.
    val store = AegisApp.instance.lockState.store
    var hasPhrase by remember { mutableStateOf(store.hasRecoveryPhrase) }
    var hasPin by remember { mutableStateOf(store.hasPin) }
    // True while the user is parked on a security page they haven't
    // satisfied yet: phrase page (6) without a phrase, or PIN page (7)
    // without a PIN. Drives pager lock + Skip visibility.
    val securityGateBlocks =
        (pagerState.currentPage == PAGE_PHRASE && !hasPhrase) ||
            (pagerState.currentPage == PAGE_PIN && !hasPin)

    /**
     * Leave the tutorial. Always marks it completed (idempotent —
     * a no-op on replay since the flag is already set). First-run
     * exits route into the normal continuation
     * ([firstRunNextDestination]); replay exits pop back to Help.
     */
    fun exitTutorial() {
        markTutorialCompleted(ctx)
        if (replay) {
            navController.popBackStack()
        } else {
            // Fresh install: hand off to the normal post-language
            // continuation. Because the flag is now set,
            // firstRunNextDestination resolves to onboarding (or
            // chats if somehow already onboarded), never back to
            // the tutorial.
            // Bare "tutorial" route id (the optional ?replay arg
            // defaults false) — matches the string the splash /
            // language picker navigate with, so this pop reliably
            // removes the tutorial from the back stack.
            navController.navigate(firstRunNextDestination(ctx)) {
                popUpTo("tutorial") { inclusive = true }
            }
        }
    }

    /**
     * Skip handler. "Skip" means skip the LESSONS, never the security
     * setup: if the phrase or PIN isn't set yet it jumps to the first
     * unsatisfied setup page (which is then locked until completed)
     * instead of leaving the tutorial. Only once both are set does Skip
     * actually exit.
     */
    fun onSkip() {
        when {
            !hasPhrase -> scope.launch { pagerState.animateScrollToPage(PAGE_PHRASE) }
            !hasPin -> scope.launch { pagerState.animateScrollToPage(PAGE_PIN) }
            else -> exitTutorial()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            // Lock swiping while parked on an unsatisfied security page so
            // the phrase/PIN setup can't be swiped past. Programmatic
            // auto-advance (onEnrolled / onPinJustSet) still works — only
            // the user's finger is held. Free everywhere else, and never
            // gated on replay (both flags already true).
            userScrollEnabled = !securityGateBlocks,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                PAGE_WELCOME -> TutorialPageWelcome()
                PAGE_PERMISSIONS -> TutorialPagePermissions()
                PAGE_DIFFERENT -> TutorialPageDifferent()
                PAGE_SAFETY -> TutorialPageSafety()
                PAGE_TIERS -> TutorialPageTiers()
                PAGE_SKILLTREE -> TutorialPageSkillTree()
                PAGE_TABS -> TutorialPageTabs()
                PAGE_PHRASE -> TutorialPageRecoveryPhrase(
                    // Screen 0 of the lock setup: the
                    // 24-word phrase is enrolled BEFORE the PIN because it
                    // roots the encryption the PIN then merely gates. On
                    // success record the flag (unlocks the pager) and
                    // advance to the PIN page.
                    onEnrolled = {
                        hasPhrase = true
                        scope.launch { pagerState.animateScrollToPage(PAGE_PIN) }
                    },
                )
                PAGE_PIN -> TutorialPagePin(
                    // Auto-advance from the PIN page to the unlock-method
                    // chooser once the PIN is set.
                    onPinJustSet = {
                        hasPin = true
                        scope.launch { pagerState.animateScrollToPage(PAGE_UNLOCK) }
                    },
                )
                PAGE_UNLOCK -> TutorialPageUnlockMethod(
                    // Lock-setup Screen 2: after the PIN, offer the
                    // optional faster unlock methods (pattern / biometric /
                    // disable). Not gated — PIN is the default and always
                    // stays. Continue advances to Ready.
                    onContinue = { scope.launch { pagerState.animateScrollToPage(PAGE_READY) } },
                )
                PAGE_READY -> TutorialPageReady(
                    // "Get Started" ADVANCES to the Thank-You closer — it
                    // must not exit here, or tapping it skips the final page
                    // entirely (Cyan's "Stay safe." sign-off + the support ask).
                    onGetStarted = { scope.launch { pagerState.animateScrollToPage(PAGE_THANKYOU) } },
                )
                PAGE_THANKYOU -> TutorialPageThankYou(onDone = { exitTutorial() })
            }
        }

        // ---- Skip (top-right) ----
        // Skips the LESSONS, not security. Hidden entirely while the
        // user is parked on an unsatisfied phrase/PIN page (there's
        // nothing to skip to — they must complete it); everywhere else
        // it routes through onSkip, which forces the setup first and
        // only truly exits once phrase + PIN are both set.
        if (!securityGateBlocks) {
            TextButton(
                onClick = { onSkip() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Text(stringResource(R.string.tutorial_skip), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
        }

        // ---- Progress dots (bottom-centre) ----
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(TUTORIAL_PAGE_COUNT) { i ->
                // Current page gets the full cyan dot; the rest are
                // dim. No animation (project policy) — the dot just
                // swaps colour as the page settles.
                val active = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .size(if (active) 9.dp else 7.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) AegisCyan
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        ),
                )
            }
        }
    }
}

/** Page count + per-page indices as NAMED constants so the pager, the
 *  progress-dot row, the security-gate lock, and every `animateScrollToPage`
 *  target reference the same source of truth and can't drift when a page is
 *  inserted (this set grew 9 → 11 → 12 exactly that way). The order here IS
 *  the on-screen order and must match the `when(page)` branch order above.
 *  Phrase is enrolled before the PIN (the phrase roots encryption, the PIN
 *  only gates), and the Permissions explainer leads early — right after the
 *  welcome — because a fresh user about to meet the OS permission prompts
 *  needs the "here's why, and you're in control" reassurance before the long
 *  permission list scares them off. */
private const val PAGE_WELCOME = 0
private const val PAGE_PERMISSIONS = 1
private const val PAGE_DIFFERENT = 2
private const val PAGE_SAFETY = 3
private const val PAGE_TIERS = 4
private const val PAGE_SKILLTREE = 5
private const val PAGE_TABS = 6
private const val PAGE_PHRASE = 7
private const val PAGE_PIN = 8
private const val PAGE_UNLOCK = 9
private const val PAGE_READY = 10
private const val PAGE_THANKYOU = 11
private const val TUTORIAL_PAGE_COUNT = 12

/**
 * Shared per-page layout: a scrollable content area on top and
 * Cyan + her speech bubble pinned toward the bottom (content
 * above, mascot ~40% below).
 *
 * [title] is the page's short heading (e.g. "What makes this
 * different") shown at the top of the content area for orientation;
 * pass null for pages that don't want one (page 1 leads with the
 * wordmark instead).
 *
 * [bubble] is Cyan's line. [showSignature] gates her "— Stay safe."
 * sign-off: it's "her last word", so we show
 * it ONLY on the final page (page 9) rather than repeating it under
 * all nine bubbles.
 *
 * [asset] picks the pose — one of the three clean full-body sticker
 * assets ([CyanAsset.Sitting] / [CyanAsset.Onboard2] /
 * [CyanAsset.Celebrate]); [CyanPose] renders it full-body on a
 * transparent background (no hex frame). When the mascot is gated
 * off (Graphics toggle) [CyanPose] renders nothing and the page
 * reads as title + content + bubble — still coherent.
 */
@Composable
private fun TutorialPageScaffold(
    asset: CyanAsset,
    bubble: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    showSignature: Boolean = false,
    // Pages with a tall interactive grid (the recovery-phrase word grid,
    // the unlock-method pattern pad) can't afford the ~250 dp the bottom
    // mascot block eats — Cyan ended up crowding / overlapping the grid and
    // the pattern was undrawable. On those pages, set
    // this: Cyan moves to a small LEFT column and the content gets the full
    // height on her right, with her speech bubble full-width along the
    // bottom.
    mascotOnSide: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    if (mascotOnSide) {
        // Layout (2026-06-08): instructions span the FULL width
        // across the top; Cyan + her bubble tuck into the BOTTOM-LEFT
        // (bubble directly above her); the interactive widget (phrase
        // grid / pattern pad) fills the space to her RIGHT. Frees the
        // most room for the grid and stops Cyan crowding it.
        Column(
            modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        ) {
            // Clear the Skip button, then the title runs full-width on top.
            Spacer(modifier = Modifier.height(48.dp))
            if (title != null) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            // Content runs FULL WIDTH and takes the slack ABOVE Cyan so
            // that space isn't wasted; scrolls within its own bounds, never
            // behind her.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Cyan occupies a bottom-LEFT CELL (not a full-height column):
            // her bubble sits directly above her with its tail pointing
            // DOWN at her. Cell is wide enough that the bubble reads
            // comfortably; the rest of the row stays empty.
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.width(190.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CyanSpeechBubble(
                        message = bubble,
                        showSignature = showSignature,
                        tail = app.aether.aegis.ui.components.BubbleTail.Down,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    CyanPose(asset = asset, size = 72.dp)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top: content area. weight(1f) so it takes the slack above
        // the fixed mascot block; scrollable so a small screen never
        // clips the denser pages (tiers, skill tree).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Leave room below the Skip button so the title never
            // sits under it.
            Spacer(modifier = Modifier.height(48.dp))
            if (title != null) {
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            content()
        }

        // Bottom: Cyan pose + speech bubble. Fixed block so the
        // mascot lands in the same place on every page (the page
        // content above flexes; she doesn't jump around).
        CyanPose(asset = asset, size = 160.dp)
        Spacer(modifier = Modifier.height(10.dp))
        // Cyan sits ABOVE the bubble here, so the tail points up at
        // her (CyanSpeechBubble default).
        CyanSpeechBubble(message = bubble, showSignature = showSignature)
        // Clear the progress dots at the very bottom.
        Spacer(modifier = Modifier.height(44.dp))
    }
}

/** Page 1 — Welcome. Cyan opens by naming
 *  the umbrella project (Project Aether), not Aegis, and introduces
 *  herself — establishing that Aegis is one part of something
 *  bigger before any feature talk. */
@Composable
private fun TutorialPageWelcome() {
    TutorialPageScaffold(
        asset = CyanAsset.Sitting, // full-body standing — warm intro
        bubble = stringResource(R.string.tutorial_bubble_welcome),
    ) {
        // Brand mark, matching the splash treatment so the welcome
        // page reads as the same product the user just launched.
        Text(
            stringResource(R.string.tutorial_aegis),
            style = TextStyle(
                fontFamily = LunaGlassFont,
                fontWeight = FontWeight.Bold,
                fontSize = 48.sp,
                letterSpacing = TextUnit(0.12f, TextUnitType.Em),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            ),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            stringResource(R.string.tutorial_part_of_project_aether),
            color = AegisCyan,
            fontSize = 12.sp,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            stringResource(R.string.tutorial_encrypted_communication_personal_security),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Permissions explainer — the second page, right after the welcome.
 *
 * Aegis requests an unusually broad permission set (location, camera, mic,
 * background execution, SIM state, nearby devices, self-install…). To a fresh
 * user that reads as alarming — exactly the population that's most primed to
 * abandon an app that "wants everything." This page gets ahead of that: every
 * group is named in plain language with WHY it exists in safety terms, and
 * each row is INTERACTIVE — it shows the live grant state and lets the user
 * grant it right here, the way mainstream apps onboard permissions:
 *
 *   - Runtime groups (notifications, location, camera, mic, media, phone,
 *     nearby) request the real Android permission on tap via a per-row
 *     [androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions]
 *     launcher. Once a row has been asked and is still denied (the
 *     "don't ask again" state, where the system dialog no longer appears), a
 *     further tap deep-links to the app's settings page instead.
 *   - Settings-access groups (background activity, install updates) can't be
 *     granted by a runtime dialog, so their rows deep-link straight to the
 *     relevant system settings screen.
 *
 * Background activity is surfaced FIRST: it is the single setting that most
 * determines whether SOS alerts and messages actually arrive — on aggressive
 * OEMs (OnePlus/OxygenOS, Xiaomi/MIUI, etc.) the OS otherwise suspends or kills
 * the transport in the background, silently stranding inbound safety traffic.
 * Only the standard Doze exemption is app-requestable; the OEM-specific killers
 * (OxygenOS "deep optimization", auto-launch allow-lists) can't be toggled by
 * an app, so the Diagnostics screen carries the deeper per-OEM guidance.
 *
 * Every row re-reads its grant state on ON_RESUME, so returning from a system
 * dialog or settings screen flips the chip to "Allowed ✓" without a refresh.
 * Nothing here is mandatory — this whole page is skippable; the actual feature
 * gates still re-prompt later if a permission is missing when first needed.
 *
 * Mascot is side-docked ([TutorialPageScaffold.mascotOnSide]) so the long
 * scrollable list gets the full width and isn't crowded by Cyan.
 */
@Composable
private fun TutorialPagePermissions() {
    val ctx = LocalContext.current
    TutorialPageScaffold(
        asset = CyanAsset.Sitting,
        title = stringResource(R.string.tutorial_perm_title),
        bubble = stringResource(R.string.tutorial_perm_bubble),
        mascotOnSide = true,
    ) {
        Text(
            stringResource(R.string.tutorial_perm_intro),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(14.dp))

        // Background activity FIRST — the one that decides whether SOS and
        // messages arrive at all. Settings-access (battery-opt exemption), so
        // the row deep-links to the system dialog rather than a runtime prompt.
        SettingsPermissionRow(
            title = stringResource(R.string.tutorial_perm_background_title),
            why = stringResource(R.string.tutorial_perm_background_why),
            isGranted = { isIgnoringBatteryOptimizations(ctx) },
            onOpen = { requestAllowBackgroundActivity(ctx) },
            highlight = true,
        )

        // OEM-specific killer card — only on manufacturers known to suspend or
        // kill background apps beyond the standard Doze exemption (OnePlus /
        // OxygenOS, Xiaomi/MIUI, Oppo, Vivo, Realme, Huawei, Samsung). The Doze
        // exemption above does NOT cover these vendor "deep optimization" /
        // auto-launch lists, and they can't be toggled by an app — so the best
        // we can do is deep-link the user into the right vendor screen, with a
        // dontkillmyapp.com fallback. This is the half of the OnePlus problem
        // the standard API can't fix (see SPEC_TUTORIAL_PERMISSIONS).
        aggressiveOemLabel()?.let { oem ->
            OemKillerCard(oem = oem, onOpen = { openOemAutostartSettings(ctx, oem) })
        }

        // Runtime groups — each requests its real permission on tap.
        RuntimePermissionRow(
            title = stringResource(R.string.tutorial_perm_notif_title),
            why = stringResource(R.string.tutorial_perm_notif_why),
            permissions = notificationPermissions(),
        )
        RuntimePermissionRow(
            title = stringResource(R.string.tutorial_perm_location_title),
            why = stringResource(R.string.tutorial_perm_location_why),
            permissions = listOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
        RuntimePermissionRow(
            title = stringResource(R.string.tutorial_perm_camera_title),
            why = stringResource(R.string.tutorial_perm_camera_why),
            permissions = listOf(android.Manifest.permission.CAMERA),
        )
        RuntimePermissionRow(
            title = stringResource(R.string.tutorial_perm_mic_title),
            why = stringResource(R.string.tutorial_perm_mic_why),
            permissions = listOf(android.Manifest.permission.RECORD_AUDIO),
        )
        RuntimePermissionRow(
            title = stringResource(R.string.tutorial_perm_media_title),
            why = stringResource(R.string.tutorial_perm_media_why),
            permissions = mediaPermissions(),
        )
        RuntimePermissionRow(
            title = stringResource(R.string.tutorial_perm_phone_title),
            why = stringResource(R.string.tutorial_perm_phone_why),
            permissions = listOf(android.Manifest.permission.READ_PHONE_STATE),
        )
        RuntimePermissionRow(
            title = stringResource(R.string.tutorial_perm_nearby_title),
            why = stringResource(R.string.tutorial_perm_nearby_why),
            permissions = nearbyPermissions(),
        )

        // Install updates — REQUEST_INSTALL_PACKAGES is special-access, granted
        // on the "install unknown apps" settings screen, not a runtime dialog.
        SettingsPermissionRow(
            title = stringResource(R.string.tutorial_perm_install_title),
            why = stringResource(R.string.tutorial_perm_install_why),
            isGranted = { canRequestPackageInstalls(ctx) },
            onOpen = { openInstallSourcesSettings(ctx) },
            highlight = false,
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            stringResource(R.string.tutorial_perm_footer),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Interactive runtime-permission row: title + plain-language [why] + a state
 * chip ("Allow" / "Granted ✓"). Tapping a not-yet-granted row fires the real
 * Android permission prompt for [permissions]; once asked and still denied
 * (the "don't ask again" state, where the dialog no longer shows), a further
 * tap deep-links to the app's settings page. Re-reads its grant state on
 * ON_RESUME so it flips to granted the moment the user returns.
 *
 * [permissions] is the SDK-applicable set for this group (e.g. empty on an API
 * level where the permission isn't a runtime grant); an empty set reads as
 * "Granted ✓" and is non-interactive, since there is nothing to request.
 */
@Composable
private fun RuntimePermissionRow(title: String, why: String, permissions: List<String>) {
    val ctx = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    fun computeGranted(): Boolean = permissions.isEmpty() || permissions.all {
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(computeGranted()) }
    // Tracks whether we've already fired the system prompt. After one denial
    // the dialog may never reappear ("don't ask again"), so a second tap must
    // route to settings instead of silently no-op'ing.
    var asked by remember { mutableStateOf(false) }
    // Asked at least once and still not granted → show the "Denied" affordance
    // (orange chip + "enable later in Settings" note, per SPEC_TUTORIAL_PERMISSIONS).
    val denied = asked && !granted
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> granted = computeGranted() }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = computeGranted()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    PermissionCard(
        title = title,
        why = why,
        granted = granted,
        denied = denied,
        highlight = false,
        onClick = if (granted) null else {
            {
                if (!asked) {
                    asked = true
                    launcher.launch(permissions.toTypedArray())
                } else {
                    // Asked once and still denied → the system won't re-prompt;
                    // send the user to app settings to flip it manually.
                    openAppDetailsSettings(ctx)
                }
            }
        },
    )
}

/**
 * Interactive settings-access row for a grant that has NO runtime dialog
 * (battery-optimization exemption, install-unknown-apps). [isGranted] reads
 * the current state; tapping a not-granted row runs [onOpen] (a settings deep
 * link). Re-reads [isGranted] on ON_RESUME so it flips when the user returns.
 * [highlight] tints the card's accent for the safety-critical background row.
 */
@Composable
private fun SettingsPermissionRow(
    title: String,
    why: String,
    isGranted: () -> Boolean,
    onOpen: () -> Unit,
    highlight: Boolean,
) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var granted by remember { mutableStateOf(isGranted()) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = isGranted()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    PermissionCard(
        title = title,
        why = why,
        granted = granted,
        denied = false,
        highlight = highlight,
        onClick = if (granted) null else onOpen,
    )
}

/** Shared glass card for both permission-row variants: bold [title] + muted
 *  [why], with a trailing state chip — "Granted ✓" (green) when [granted],
 *  "Denied" (SOS) once [denied] (asked + refused), else "Allow" in the accent
 *  colour (SOS red when [highlight], else cyan). On [denied] a one-line "enable
 *  later in Settings" note is added under the why, per the spec. A non-null
 *  [onClick] makes the whole card tappable. */
@Composable
private fun PermissionCard(
    title: String,
    why: String,
    granted: Boolean,
    denied: Boolean,
    highlight: Boolean,
    onClick: (() -> Unit)?,
) {
    val accent = if (highlight) AegisSOS else AegisCyan
    val chip = when {
        granted -> stringResource(R.string.tutorial_perm_granted)
        denied -> stringResource(R.string.tutorial_perm_denied)
        else -> stringResource(R.string.tutorial_perm_allow)
    }
    val chipColor = when {
        granted -> app.aether.aegis.ui.theme.AegisOnline
        denied -> AegisSOS
        else -> accent
    }
    GlassPanel(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    why,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (denied) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.tutorial_perm_denied_note),
                        fontSize = 11.sp,
                        color = AegisSOS,
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                chip,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = chipColor,
            )
        }
    }
}

// ---- permission-group resolution (SDK-aware) + settings deep links --------

/** POST_NOTIFICATIONS is a runtime permission only on Android 13+ (API 33);
 *  below that, notifications are granted by default, so the group reads as
 *  already-granted (empty request set). */
private fun notificationPermissions(): List<String> =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        listOf(android.Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }

/** Scoped media reads on Android 13+ (API 33) replace the single
 *  READ_EXTERNAL_STORAGE grant used on older versions. */
private fun mediaPermissions(): List<String> =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        listOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_AUDIO,
        )
    } else {
        @Suppress("DEPRECATION")
        listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/** The nearby-devices runtime permissions exist only on Android 12+ (API 31);
 *  older versions used the legacy install-time Bluetooth permissions, so the
 *  group reads as already-granted there. */
private fun nearbyPermissions(): List<String> =
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        emptyList()
    }

/** True if Aegis is already exempt from Doze battery optimization (on the
 *  doze whitelist). Mirrors the Diagnostics probe; null PowerManager or any
 *  throw is treated as "not exempt" so the explainer offers the request. */
private fun isIgnoringBatteryOptimizations(ctx: android.content.Context): Boolean =
    runCatching {
        ctx.getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }.getOrDefault(false)

/** True if Aegis may install/update packages from itself (the special
 *  "install unknown apps" access REQUEST_INSTALL_PACKAGES gates). Drives the
 *  self-update OTA install. Any throw → false so the row offers the link. */
private fun canRequestPackageInstalls(ctx: android.content.Context): Boolean =
    runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

/** Fire the system "allow background activity" (ignore-battery-optimizations)
 *  dialog for Aegis. The @SuppressLint is deliberate and matches Diagnostics:
 *  Google flags this intent as policy-sensitive, but reliable background
 *  delivery is core to a safety app, so we ask for it directly.
 *
 *  The direct per-app dialog (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, with
 *  a `package:` URI) only works when REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is
 *  declared in the manifest — without it the system SILENTLY drops the intent
 *  (startActivity returns without throwing and nothing appears), which read as
 *  the row "leading nowhere". With the permission declared the dialog shows.
 *
 *  We still layer two fallbacks so the tap ALWAYS lands somewhere even on an
 *  OEM that omits the per-app dialog Activity (those DO throw
 *  ActivityNotFoundException): (2) the system-wide battery-optimization app
 *  list, which needs no permission, then (3) this app's App-info page. */
@android.annotation.SuppressLint("BatteryLife")
private fun requestAllowBackgroundActivity(ctx: android.content.Context) {
    // 1) Direct per-app exemption dialog — one tap to confirm. Requires the
    //    REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (declared in manifest).
    val direct = android.content.Intent(
        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    ).apply {
        data = android.net.Uri.parse("package:${ctx.packageName}")
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { ctx.startActivity(direct) }.isSuccess) return
    // 2) Fallback: the system-wide battery-optimization app list. No permission
    //    needed; the user finds Aegis and switches it to "Don't optimize".
    val list = android.content.Intent(
        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
    ).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { ctx.startActivity(list) }.isSuccess) return
    // 3) Last resort: this app's App-info page (always present).
    openAppDetailsSettings(ctx)
}

/** Open the per-app "install unknown apps" settings screen so the user can
 *  grant REQUEST_INSTALL_PACKAGES (no runtime dialog exists for it). */
private fun openInstallSourcesSettings(ctx: android.content.Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
    ).apply {
        data = android.net.Uri.parse("package:${ctx.packageName}")
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }
}

/** Open this app's system "App info" page — the fallback for a runtime
 *  permission the system will no longer prompt for ("don't ask again"). */
private fun openAppDetailsSettings(ctx: android.content.Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    ).apply {
        data = android.net.Uri.fromParts("package", ctx.packageName, null)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }
}

/**
 * The OEM "deep optimization will kill us" warning card, shown only on
 * manufacturers that need it ([aggressiveOemLabel]). Names the manufacturer so
 * the warning reads as specific, not generic FUD, and offers a button into the
 * vendor's auto-start / battery screen ([onOpen]). Styled like the recovery-
 * phrase warning — amber/SOS accent — because a missed setting here silently
 * breaks SOS delivery.
 */
@Composable
private fun OemKillerCard(oem: String, onOpen: () -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠", color = AegisSOS, fontSize = 15.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    stringResource(R.string.tutorial_perm_oem_title, oem),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.tutorial_perm_oem_warning, oem),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            AegisButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tutorial_perm_oem_open, oem))
            }
        }
    }
}

/** A short label for the current device's manufacturer IF it's one known to
 *  aggressively kill background apps beyond standard Doze; null otherwise (the
 *  card is only worth showing where the vendor actually breaks background
 *  delivery). Honor ships EMUI-family management, Redmi/Poco are Xiaomi/MIUI. */
private fun aggressiveOemLabel(): String? {
    val m = android.os.Build.MANUFACTURER?.lowercase().orEmpty()
    return when {
        m.contains("oneplus") -> "OnePlus"
        m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "Xiaomi"
        m.contains("oppo") -> "Oppo"
        m.contains("vivo") -> "Vivo"
        m.contains("realme") -> "Realme"
        m.contains("huawei") || m.contains("honor") -> "Huawei"
        m.contains("samsung") -> "Samsung"
        else -> null
    }
}

/** Deep-link into the vendor's auto-start / background-management screen for
 *  [oem]. These component names are version-fragile and undocumented, so each
 *  is attempted only if it actually resolves on THIS device; the first that
 *  launches wins. If none resolve (newer/older skin, ROM variation), fall back
 *  to the per-vendor guide at dontkillmyapp.com. All launches are best-effort —
 *  a missing Activity must never crash onboarding. */
private fun openOemAutostartSettings(ctx: android.content.Context, oem: String) {
    val candidates: List<Pair<String, String>> = when (oem) {
        "OnePlus" -> listOf(
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        )
        "Xiaomi" -> listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        "Oppo", "Realme" -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        )
        "Vivo" -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        )
        "Huawei" -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        )
        "Samsung" -> listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        )
        else -> emptyList()
    }
    for ((pkg, cls) in candidates) {
        val launched = runCatching {
            val intent = android.content.Intent().apply {
                component = android.content.ComponentName(pkg, cls)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            @Suppress("DEPRECATION")
            if (ctx.packageManager.resolveActivity(intent, 0) != null) {
                ctx.startActivity(intent)
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (launched) return
    }
    // No vendor screen resolved — open the per-OEM guide instead.
    runCatching {
        ctx.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://dontkillmyapp.com/${oem.lowercase()}"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Page 2 — what makes Aegis different (no number / account / server). */
@Composable
private fun TutorialPageDifferent() {
    TutorialPageScaffold(
        asset = CyanAsset.Celebrate, // full-body, finger up
        title = stringResource(R.string.tutorial_what_makes_this_different),
        bubble = stringResource(R.string.tutorial_bubble_different),
    ) {
        TutorialBullet(stringResource(R.string.tutorial_bullet_protocol))
        TutorialBullet(stringResource(R.string.tutorial_bullet_metadata))
        TutorialBullet(stringResource(R.string.tutorial_bullet_e2ee))
    }
}

/** Page 3 — the SOS safety net. Finger-up "listen up" pose — the
 *  shield pose was retired in the pose-palette trim:
 *  too many distinct Cyan variants across the flow, and
 *  "finger up" reads as guidance better than "here's the shield". */
@Composable
private fun TutorialPageSafety() {
    TutorialPageScaffold(
        asset = CyanAsset.Celebrate, // full-body, finger up
        title = stringResource(R.string.tutorial_your_safety_net),
        bubble = stringResource(R.string.tutorial_bubble_safety),
    ) {
        TutorialBullet("Hold the SOS button 1 second — or press power 4× at a steady pace")
        // Cadence: the trigger needs 4 presses within a 2-second window, i.e.
        // roughly one press every half-second — NOT once a second (that overruns
        // the window and never fires). Faster than ~0.5s/press and Android's own
        // gestures (2× = camera, 5× = Emergency SOS) grab the presses at a layer
        // no app can override — an OS limit, not our bug.
        TutorialBullet("Press power about twice a second — roughly one press every half-second. Mashing it faster gets taken by Android's camera (2×) and Emergency SOS (5×) shortcuts, not Aegis. That's an Android limit, not a bug.")
        TutorialBullet("Three quick buzzes mean it fired — you'll feel it even in a pocket. No buzz after four presses? You went too fast: ease off the speed and keep pressing until you feel it.")
        TutorialBullet(stringResource(R.string.tutorial_bullet_sos_broadcast))
        TutorialBullet(stringResource(R.string.tutorial_bullet_sos_lockscreen))
    }
}

/** Page 4 — the three trust tiers, one card each. */
@Composable
private fun TutorialPageTiers() {
    TutorialPageScaffold(
        asset = CyanAsset.Sitting, // full-body standing
        title = stringResource(R.string.tutorial_three_tiers_of_trust),
        bubble = stringResource(R.string.tutorial_bubble_tiers),
    ) {
        TutorialTierCard("Trusted", "Everything — location, presence, SOS.")
        Spacer(modifier = Modifier.height(8.dp))
        TutorialTierCard("Emergency", stringResource(R.string.tutorial_tier_emergency_desc))
        Spacer(modifier = Modifier.height(8.dp))
        TutorialTierCard("Untrusted", stringResource(R.string.tutorial_tier_untrusted_desc))
    }
}

/** Page 5 — the skill tree, shown as a short node chain. */
@Composable
private fun TutorialPageSkillTree() {
    TutorialPageScaffold(
        asset = CyanAsset.Celebrate, // full-body, finger up
        title = stringResource(R.string.tutorial_the_skill_tree),
        bubble = "Set your App PIN first — it's the trunk that unlocks everything.",
    ) {
        // Accurate shape of the real tree (SecurityScreen): App PIN is the
        // TRUNK and gates almost every other node — they're PARALLEL children,
        // not a sequence (the old chain "App PIN → Duress → Vault → Canary"
        // implied a false order AND wrong gating). The only real sub-branch is
        // Vault PIN → Vault Duress, and App PIN → Device Admin → Device Owner
        // is the climb to the top (Cyan) tier. We show App PIN fanning out to
        // a few representative branches rather than a misleading line.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TutorialSkillNode("App\nPIN")
            TutorialNodeArrow()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TutorialSkillNode(stringResource(R.string.tutorial_node_app_duress))
                TutorialSkillNode(stringResource(R.string.tutorial_node_vault_pin))
                TutorialSkillNode(stringResource(R.string.tutorial_node_device_owner))
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            stringResource(R.string.tutorial_skill_tree_explanation),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Page 6 — the tab tour. The actual "how do I use this app" page:
 * the five bottom-nav tabs, each shown with its REAL nav icon (so the
 * user recognises it the moment they land in the app) + a one-line
 * description of what lives there.
 *
 * Rows are listed in the on-screen left→right default order
 * (System · Opsec · SOS · Comms · Radar — see [ALL_TABS] /
 * AegisBottomNav), with SOS tinted its danger red and locked-centre
 * note, so this page reads as a literal map of the bar at the bottom
 * of the screen. Labels are pulled from the same `nav_*` string
 * resources the bar uses, so the tutorial and the bar can never drift
 * out of sync. (Per-user tab reordering in Settings → Nav can change
 * the live order; we show the shipped default, which is what a
 * first-run user actually sees.)
 *
 * Cyan points (Onboard2) — she's literally pointing the tabs out.
 */
@Composable
private fun TutorialPageTabs() {
    TutorialPageScaffold(
        asset = CyanAsset.Celebrate, // full-body, finger up (was the waist-up Onboard2 — mixing framings looked off)
        title = stringResource(R.string.tutorial_find_your_way_around),
        bubble = stringResource(R.string.tutorial_bubble_tabs),
    ) {
        Text(
            stringResource(R.string.tutorial_left_to_right_along),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        // Default shipped order (AegisBottomNav ALL_TABS, SOS locked
        // at centre). Each row reuses the bar's own icon + label res.
        TutorialTabRow(
            iconRes = R.drawable.ic_aegis_settings,
            labelRes = R.string.nav_settings,
            desc = stringResource(R.string.tutorial_tab_system_desc),
        )
        TutorialTabRow(
            iconRes = R.drawable.ic_aegis_security,
            labelRes = R.string.nav_security,
            desc = stringResource(R.string.tutorial_tab_opsec_desc),
        )
        TutorialTabRow(
            iconRes = R.drawable.ic_aegis_sos,
            labelRes = R.string.nav_sos,
            // SOS is the locked-centre tab; tint it the same danger
            // red the bar uses so it's unmistakable here too.
            tint = AegisSOS,
            desc = stringResource(R.string.tutorial_tab_sos_desc),
        )
        TutorialTabRow(
            iconRes = R.drawable.ic_aegis_chats,
            labelRes = R.string.nav_chats,
            desc = stringResource(R.string.tutorial_tab_comms_desc),
        )
        TutorialTabRow(
            // Radar's glyph has a negative-space "N" a flat tint can't
            // render, so the row uses the Canvas-drawn RadarHexIcon —
            // same as the bar (see TutorialTabRow / RadarHexIcon).
            iconRes = R.drawable.ic_aegis_radar,
            labelRes = R.string.nav_radar,
            isRadar = true,
            desc = stringResource(R.string.tutorial_tab_radar_desc),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            stringResource(R.string.tutorial_sos_stays_locked_in),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Page 7 — inline PIN setup, the only required-action page.
 *
 * Reuses the live LockStore so the PIN set here is the real app
 * PIN — identical to Settings → App Lock's first-time path
 * (LockSettingsScreen.kt): [LockStore.setPin] derives + persists
 * the keypair, [PinSession.set] opens the in-memory session, and
 * any pre-PIN plaintext rows are sealed under the fresh pubkey.
 * (On a fresh install there's nothing to seal; the calls are kept
 * for parity so the tutorial path can never diverge from the
 * canonical one.)
 *
 * If a PIN already exists (the user is replaying the tutorial), the
 * page shows a done state instead of the fields — there's no second
 * PIN to set, and re-deriving would be a destructive PIN *change*,
 * which this page must never trigger.
 *
 * [onPinJustSet] fires once, right after a successful first-time
 * set, so the host pager can auto-advance to the next page.
 */
@Composable
private fun TutorialPagePin(onPinJustSet: () -> Unit) {
    val store = AegisApp.instance.lockState.store
    val scope = rememberCoroutineScope()
    val context = LocalContext.current  // for getString() in onClick (non-composable)
    // Snapshot whether a PIN exists at entry. Flips to true the
    // moment we set one, which swaps the body to the done state.
    var hasPin by remember { mutableStateOf(store.hasPin) }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    TutorialPageScaffold(
        asset = CyanAsset.Celebrate, // full-body, finger up
        title = stringResource(R.string.tutorial_set_your_pin),
        // Replay-with-PIN gets the warm acknowledgement from the
        // spec; first-time gets the instruction.
        bubble = if (hasPin) stringResource(R.string.tutorial_pin_already_nice) else stringResource(R.string.tutorial_pin_set_first),
    ) {
        if (hasPin) {
            // Done state — replay path, or right after a successful
            // first-time set before the pager finishes advancing.
            Text(
                stringResource(R.string.tutorial_pin_already_set),
                color = app.aether.aegis.ui.theme.AegisOnline,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.tutorial_your_messages_and_attachments),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            return@TutorialPageScaffold
        }

        // With a recovery phrase enrolled, the PIN is
        // the door, not the key — the phrase encrypts. Word it honestly
        // so the user isn't told the PIN protects what it doesn't.
        Text(
            if (store.hasRecoveryPhrase) {
                stringResource(R.string.tutorial_pin_explanation_new)
            } else {
                stringResource(R.string.tutorial_pin_explanation_existing)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = newPin,
            onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(8) },
            label = { Text(stringResource(R.string.tutorial_new_pin_48_digits)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = confirmPin,
            onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(8) },
            label = { Text(stringResource(R.string.tutorial_confirm_pin)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))
        AegisButton(
            enabled = newPin.length in 4..8 && newPin == confirmPin,
            onClick = {
                // Two paths depending on whether the phrase already
                // rooted the seal on the previous page:
                //   - Phrase enrolled (the normal new
                //     flow): the PIN is a pure gate. setPinGateOnly stores
                //     only the auth hash; the session is already seal-ready
                //     from phrase enrolment (Screen 0), so we don't derive
                //     or install a PIN keypair.
                val saveResult = runCatching { store.setPinGateOnly(newPin) }
                if (saveResult.isFailure) {
                    error = "Couldn't save PIN: " +
                        (saveResult.exceptionOrNull()?.message ?: "unknown error")
                    return@AegisButton
                }
                if (!store.hasPin) {
                    error = context.getString(R.string.tutorial_pin_persist_error)
                    return@AegisButton
                }
                hasPin = true
                newPin = ""
                confirmPin = ""
                error = null
                onPinJustSet()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.tutorial_save_pin)) }
    }
}

/**
 * Page 8 — "Choose how you unlock" (lock-setup Screen 2). Shown
 * right after the PIN is set. The PIN is the base and ALWAYS stays (it's
 * what duress, remote access, and SealCrypto use); the options here are
 * additive convenience layers — a pattern, biometric, or disabling the
 * lock screen entirely. Nothing here is mandatory: a user who just taps
 * Continue keeps PIN-only, the recommended choice.
 *
 * The pattern + biometric enrolment reuse the exact flows from
 * LockSettings (`PatternLock` + `store.setPattern`; the warning dialog +
 * `enrolBiometric`), so onboarding and settings can't diverge. Biometric
 * enrolment needs the seal priv, which `PinSession` already holds from the
 * phrase enrolment two pages back.
 *
 * [onContinue] advances the pager to the Ready page.
 */
@Composable
private fun TutorialPageUnlockMethod(onContinue: () -> Unit) {
    val store = AegisApp.instance.lockState.store
    val ctx = LocalContext.current
    val biometricCapable = remember {
        app.aether.aegis.ui.components.deviceHasStrongBiometric(ctx)
    }
    var patternEnabled by remember { mutableStateOf(store.hasPattern) }
    var patternStage by remember { mutableStateOf(0) } // 0 idle, 1 draw, 2 confirm
    var firstPattern by remember { mutableStateOf<List<Int>?>(null) }
    var patternErr by remember { mutableStateOf<String?>(null) }
    var biometricOn by remember { mutableStateOf(store.biometricEnabled) }
    var showBioWarn by remember { mutableStateOf(false) }
    var bioError by remember { mutableStateOf<String?>(null) }
    var lockDisabled by remember { mutableStateOf(!store.requireAppLock) }
    var showDisableConfirm by remember { mutableStateOf(false) }

    TutorialPageScaffold(
        asset = CyanAsset.Celebrate,
        title = stringResource(R.string.tutorial_choose_how_you_unlock),
        bubble = "PIN is the safest. Add a faster way if you like — your PIN always stays.",
        mascotOnSide = true,
    ) {
        Text(
            stringResource(R.string.tutorial_your_pin_is_set),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))

        UnlockOptionCard(
            title = stringResource(R.string.tutorial_pin),
            tag = "Recommended · active",
            desc = "Type your PIN to unlock. Full duress + shoulder-surf protection.",
            active = true,
        )
        UnlockOptionCard(
            title = stringResource(R.string.tutorial_pattern),
            tag = if (patternEnabled) stringResource(R.string.settings_enabled) else stringResource(R.string.action_add),
            desc = "Draw a pattern for daily unlock. Your PIN still covers duress.",
            active = patternEnabled,
            onClick = if (patternEnabled) null else { { patternStage = 1; patternErr = null } },
        )
        if (patternStage > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (patternStage == 1) stringResource(R.string.tutorial_draw_pattern) else stringResource(R.string.tutorial_draw_pattern_confirm),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            app.aether.aegis.ui.components.PatternLock(
                modifier = Modifier.fillMaxWidth(),
                onPattern = { seq ->
                    when {
                        seq.size < store.minPatternLength ->
                            patternErr = ctx.getString(R.string.tutorial_pattern_min_dots, store.minPatternLength)
                        patternStage == 1 -> {
                            firstPattern = seq; patternStage = 2; patternErr = null
                        }
                        else -> {
                            if (seq == firstPattern) {
                                store.setPattern(seq)
                                patternEnabled = true; patternStage = 0; patternErr = null
                            } else {
                                patternErr = ctx.getString(R.string.tutorial_pattern_mismatch)
                                patternStage = 1; firstPattern = null
                            }
                        }
                    }
                },
            )
            patternErr?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
        if (biometricCapable) {
            UnlockOptionCard(
                title = stringResource(R.string.tutorial_fingerprint_face),
                tag = if (biometricOn) stringResource(R.string.settings_enabled) else stringResource(R.string.action_add),
                desc = "Fast unlock. Note: biometric skips duress protection.",
                active = biometricOn,
                onClick = if (biometricOn) null else { { showBioWarn = true } },
            )
            bioError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
        UnlockOptionCard(
            title = stringResource(R.string.tutorial_no_lock_screen),
            tag = if (lockDisabled) "On" else "Off",
            desc = "Open Aegis without unlocking. PIN still guards duress + remote access.",
            active = lockDisabled,
            onClick = {
                if (lockDisabled) {
                    store.requireAppLock = true; lockDisabled = false
                } else {
                    showDisableConfirm = true
                }
            },
        )

        Spacer(modifier = Modifier.height(16.dp))
        AegisButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tutorial_continue)) }
    }

    if (showBioWarn) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBioWarn = false },
            title = { Text(stringResource(R.string.tutorial_biometric_skips_duress)) },
            text = {
                Text(
                    stringResource(R.string.tutorial_biometric_login_is_convenient),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBioWarn = false
                    val priv = app.aether.aegis.lock.PinSession.priv()
                    if (priv == null) {
                        bioError = ctx.getString(R.string.tutorial_bio_need_pin_first)
                        return@TextButton
                    }
                    app.aether.aegis.ui.components.enrolBiometric(ctx, priv) { ok ->
                        biometricOn = ok
                        if (!ok) bioError = ctx.getString(R.string.tutorial_bio_failed)
                    }
                }) { Text(stringResource(R.string.tutorial_enable_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { showBioWarn = false }) { Text(stringResource(R.string.tutorial_keep_pin_only)) }
            },
        )
    }
    if (showDisableConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDisableConfirm = false },
            title = { Text(stringResource(R.string.tutorial_open_without_a_lock)) },
            text = {
                Text(
                    stringResource(R.string.tutorial_aegis_will_open_without),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.requireAppLock = false; lockDisabled = true; showDisableConfirm = false
                }) { Text(stringResource(R.string.tutorial_disable_lock)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirm = false }) { Text(stringResource(R.string.tutorial_keep_lock)) }
            },
        )
    }
}

/** One unlock-method row on the chooser: title + description on the left,
 *  a state tag on the right, cyan when active. [onClick] null = not
 *  tappable (e.g. the always-on PIN, or an already-enabled method). */
@Composable
private fun UnlockOptionCard(
    title: String,
    tag: String,
    desc: String,
    active: Boolean,
    onClick: (() -> Unit)? = null,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) AegisCyan else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    desc,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                tag,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) AegisCyan else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Page 7 — recovery-phrase enrolment. Screen 0 of the lock setup:
 * the 24-word BIP39 phrase is the master key
 * that roots SealCrypto, so it is established BEFORE the PIN (which only
 * gates unlock).
 *
 * Two stages:
 *   0 (show)    — the 24 words in a numbered grid + "I've written it
 *                 down". The phrase is generated once on first visit and
 *                 never regenerated across recompositions. FLAG_SECURE
 *                 (default on, ScreenSecurityPrefs) keeps it off
 *                 screenshots / the Recents thumbnail.
 *   1 (confirm) — re-enter three RANDOM words (distinct positions, chosen
 *                 per phrase) to prove the user actually saved them. On
 *                 match we [LockStore.enrollRecoveryPhrase]
 *                 (derives the phrase-rooted seal keypair, wraps the priv
 *                 in the TEE under Model B) and install the session so
 *                 the user is seal-ready immediately.
 *
 * On replay (a phrase already exists) the page shows a done state — we
 * never regenerate or overwrite an existing phrase, which would orphan
 * the user's encrypted data.
 *
 * [onEnrolled] fires once after a successful enrolment so the host pager
 * advances to the PIN page.
 */
@Composable
private fun TutorialPageRecoveryPhrase(onEnrolled: () -> Unit) {
    val store = AegisApp.instance.lockState.store
    val scope = rememberCoroutineScope()
    val context = LocalContext.current  // for getString() in onClick (non-composable)
    var alreadyEnrolled by remember { mutableStateOf(store.hasRecoveryPhrase) }
    // Generate exactly once per visit, and ONLY when nothing is enrolled
    // yet. The recovery phrase is SHOW-ONCE: it is never stored and never
    // re-displayed (re-revealing it behind the PIN or
    // fingerprint would make THOSE the key to the 256-bit vault, defeating
    // "phrase is the vault, PIN is the door"). On replay we show a
    // done-state, never the words.
    val words = remember {
        if (store.hasRecoveryPhrase) emptyList()
        else app.aether.aegis.lock.RecoveryPhrase.generate()
    }
    var stage by remember { mutableStateOf(0) }
    // Re-drawn on every entry into the confirm stage (bumped by the
    // "I've written it down" button) so you can't peek the phrase, note
    // which 3 positions are asked, go back, and copy them — each visit
    // asks for a DIFFERENT three (user-reported 2026-06-08).
    var challengeSeed by remember { mutableStateOf(0) }
    // Three DISTINCT word positions to verify, chosen at random per
    // challenge so it isn't the predictable "always #3/#7/#15". Sorted
    // ascending so the user reads them in transcription order. 0-indexed.
    val checkIdx = remember(words, challengeSeed) {
        if (words.size < 3) emptyList()
        else words.indices.shuffled().take(3).sorted()
    }
    // Parallel answer inputs, one per checkIdx position — reset to blank
    // whenever a fresh challenge is drawn.
    val answers = remember(words, challengeSeed) { mutableStateListOf("", "", "") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    TutorialPageScaffold(
        asset = CyanAsset.Celebrate, // full-body, finger up
        title = stringResource(R.string.tutorial_your_recovery_phrase),
        bubble = if (alreadyEnrolled) {
            stringResource(R.string.tutorial_phrase_already_saved)
        } else {
            stringResource(R.string.tutorial_phrase_write_down)
        },
        // Side-dock (small mascot beside the wide word-grid) ONLY when the grid
        // is shown. On replay (already enrolled, no grid) the page is nearly
        // empty, so use the centered layout where Cyan is full-size instead of
        // a tiny 72dp figure marooned in the corner.
        mascotOnSide = !alreadyEnrolled,
    ) {
        // Already enrolled: NEVER show the phrase again. It is shown ONCE,
        // at enrolment; after that it lives only on the user's paper. We do
        // not store it and do not re-reveal it — not behind the PIN, not
        // behind biometric, not at all.
        if (alreadyEnrolled) {
            Text(
                stringResource(R.string.tutorial_recovery_phrase_already_set),
                color = app.aether.aegis.ui.theme.AegisOnline,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.tutorial_it_is_your_master),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            AegisButton(onClick = onEnrolled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tutorial_done)) }
            return@TutorialPageScaffold
        }

        when (stage) {
            0 -> {
                // Instruction stage. The phrase page used to pile the intro
                // text + warning + the 24-word grid + button onto ONE screen,
                // so the grid and button slid under Cyan with no scroll cue —
                // and nothing told the user to scratch. Splitting the
                // instruction out keeps the phrase screen short enough to fit
                // above Cyan, and the scratch step is now spelled out.
                // (user-reported 2026-06-09)
                Text(
                    stringResource(R.string.tutorial_this_encrypts_your_messages),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                PhraseRevealWarning()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.tutorial_phrase_intro),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                AegisButton(
                    onClick = { stage = 1 },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.tutorial_phrase_show)) }
            }
            1 -> {
                // Phrase grid — MINIMAL surrounding text on purpose, so all
                // 24 words + the button fit above Cyan without scrolling. The
                // one line carries the only thing they still need at the grid:
                // scratch, then transcribe in order.
                Text(
                    stringResource(R.string.tutorial_phrase_scratch),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                RecoveryWordGrid(words)
                Spacer(modifier = Modifier.height(16.dp))
                AegisButton(
                    // Bump the seed so a fresh random trio is drawn each
                    // time the confirm stage opens.
                    onClick = { challengeSeed++; stage = 2 },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.tutorial_ive_written_it_down)) }
            }
            2 -> {
                val nums = checkIdx.map { it + 1 }
                val positions = when (nums.size) {
                    3 -> "${nums[0]}, ${nums[1]}, and ${nums[2]}"
                    else -> nums.joinToString(", ")
                }
                Text(
                    stringResource(R.string.tutorial_phrase_confirm, positions),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                checkIdx.forEachIndexed { i, idx ->
                    OutlinedTextField(
                        value = answers[i],
                        onValueChange = { answers[i] = it.trim(); error = null },
                        label = { Text(stringResource(R.string.tutorial_phrase_word_n, idx + 1)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (i < checkIdx.lastIndex) Spacer(modifier = Modifier.height(8.dp))
                }
                error?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                AegisButton(
                    enabled = !working && answers.all { it.isNotBlank() },
                    onClick = {
                        // Compare each answer against the word at its random
                        // position, case-insensitively (BIP39 words are
                        // lowercase but the user may have capitalised on
                        // paper).
                        val ok = checkIdx.indices.all { i ->
                            answers[i].equals(words[checkIdx[i]], ignoreCase = true)
                        }
                        if (!ok) {
                            error = context.getString(R.string.tutorial_phrase_mismatch)
                            return@AegisButton
                        }
                        working = true
                        error = null
                        scope.launch {
                            // Argon2id MODERATE is heavy (~256 MiB) — keep it
                            // off the main thread so the UI doesn't jank.
                            val kp = withContext(Dispatchers.Default) {
                                store.enrollRecoveryPhrase(words)
                            }
                            app.aether.aegis.lock.PinSession.set(kp)
                            alreadyEnrolled = true
                            working = false
                            onEnrolled()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (working) "Securing…" else stringResource(R.string.vault_pin_confirm)) }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { stage = 1 }) { Text(stringResource(R.string.tutorial_show_the_words_again)) }
                // Skip the re-type verification. The phrase has still been
                // generated and SHOWN (stage 1) — this only bypasses the
                // three-word quiz that proves it was transcribed. It exists
                // because forcing the quiz "just to test" was turning fresh
                // users away (they didn't want to write down 24 words before
                // even trying the app). Opt-in and de-emphasised so the
                // verified path stays the obvious default; the warning above
                // the grid still spells out that the phrase is unrecoverable.
                // Same enrol path as Confirm — derives the phrase-rooted seal
                // keypair and opens the session — so a skipped enrolment is
                // cryptographically identical to a verified one, just
                // unproven on the user's side.
                TextButton(
                    enabled = !working,
                    onClick = {
                        working = true
                        error = null
                        scope.launch {
                            val kp = withContext(Dispatchers.Default) {
                                store.enrollRecoveryPhrase(words)
                            }
                            app.aether.aegis.lock.PinSession.set(kp)
                            alreadyEnrolled = true
                            working = false
                            onEnrolled()
                        }
                    },
                ) {
                    Text(
                        // Inline English (matches the other inline tutorial
                        // strings in this file); translation extraction is
                        // handled separately in the i18n pass.
                        "Skip — I've saved my phrase",
                        color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                    )
                }
            }
        }
    }
}

/** The shoulder-surf warning shown directly above the scratch-to-reveal
 *  recovery phrase. Cyan/amber caution copy — the user must understand
 *  the stakes BEFORE they scratch the master key into view. */
@Composable
private fun PhraseRevealWarning() {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠", color = AegisSOS, fontSize = 16.sp)
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    stringResource(R.string.tutorial_anyone_who_sees_these),
                    color = AegisSOS,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.tutorial_make_sure_no_one),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.tutorial_this_is_the_only),
                color = AegisSOS,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * The 24 recovery words in a two-column numbered grid (1..24). Each cell
 * is "<n>. <word>".
 *
 * Only the WORDS are hidden behind the scratch cover; the 1–24 numbers
 * stay visible (covering the whole scaffold read as
 * "too much"). Achieved with two identical-geometry layers in one panel:
 *   - lower layer: the real words + numbers, wrapped in [ScratchToReveal]
 *     so the cover hides them until scratched;
 *   - upper layer: the SAME grid with its word slots painted transparent,
 *     drawn on top of the cover — so its numbers read through while its
 *     (invisible) words leave the scratch layer beneath in charge of word
 *     visibility.
 * Both layers are the same composable, so their layouts line up exactly
 * and the visible numbers sit precisely over the hidden ones.
 */
@Composable
private fun RecoveryWordGrid(words: List<String>) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Box {
            ScratchToReveal(modifier = Modifier.fillMaxWidth()) {
                WordGridContent(words, wordsVisible = true)
            }
            // Numbers-only overlay — words transparent. Has no pointer
            // input, so scratch drags fall through to the cover beneath.
            WordGridContent(words, wordsVisible = false)
        }
    }
}

/** Inner grid body shared by both reveal layers. [wordsVisible] paints
 *  the words cyan (real content) or transparent (numbers-only overlay);
 *  the numbers and all spacing are identical either way so the two layers
 *  register pixel-for-pixel. */
@Composable
private fun WordGridContent(words: List<String>, wordsVisible: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        // 12 rows of 2 — left column words 1..12, right column 13..24.
        for (row in 0 until 12) {
            Row(modifier = Modifier.fillMaxWidth()) {
                RecoveryWordCell(row, words, wordsVisible, Modifier.weight(1f))
                RecoveryWordCell(row + 12, words, wordsVisible, Modifier.weight(1f))
            }
            if (row < 11) Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

/** One "<n>. <word>" cell. The number is always painted; the word is cyan
 *  when [wordsVisible], else transparent (but still laid out, so geometry
 *  matches across layers). Guards [index] against a short/empty list so it
 *  can never throw during an unexpected recomposition. */
@Composable
private fun RecoveryWordCell(
    index: Int,
    words: List<String>,
    wordsVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val word = words.getOrNull(index) ?: return
    Row(modifier = modifier.padding(vertical = 1.dp)) {
        Text(
            "${index + 1}.".padEnd(3),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            word,
            color = if (wordsVisible) AegisCyan else Color.Transparent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Page 8 — ready. Shield pose + halo, single stringResource(R.string.tutorial_get_started) CTA.
 *  The CTA ADVANCES to the Thank-You page (the host wires
 *  [onGetStarted] to a forward page scroll), not straight into the
 *  app — otherwise tapping it skipped Cyan's "Stay safe." closer and
 *  the support ask. The actual hand-off into the
 *  app happens from the Thank-You page's buttons. */
@Composable
private fun TutorialPageReady(onGetStarted: () -> Unit) {
    TutorialPageScaffold(
        asset = CyanAsset.Celebrate, // full-body, finger up
        title = stringResource(R.string.tutorial_youre_ready),
        bubble = stringResource(R.string.tutorial_bubble_ready),
    ) {
        AegisButton(onClick = onGetStarted) { Text(stringResource(R.string.tutorial_get_started)) }
    }
}

/**
 * Page 9 — thank you + hand-off into the app. The final page, and the
 * only one where Cyan signs off with "— Stay safe." (her last word).
 * Reached by swipe OR by the previous page's "Get Started" button, so
 * this closer is no longer skippable by tapping through.
 *
 * Deliberately NO donation button here: asking a
 * user to pay before they've even used the app reads as rude. The
 * tutorial only *points* to where support lives — Cyan asks for it
 * herself, gently, at the bottom of System → About. So this page
 * just thanks the user, states the free-forever promise, and hands
 * off into the app. The single CTA enters Aegis.
 */
@Composable
private fun TutorialPageThankYou(onDone: () -> Unit) {
    TutorialPageScaffold(
        asset = CyanAsset.Sitting, // full-body standing — warm closer
        title = stringResource(R.string.tutorial_thank_you),
        bubble = stringResource(R.string.tutorial_bubble_thanks),
        showSignature = true, // closer — stringResource(R.string.capabilities_stay_safe)
    ) {
        Text(
            stringResource(R.string.tutorial_aegis_is_free_forever),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        // Pointer, not a pitch — where to find support once they've
        // actually used the app. No button, no link tap here.
        Text(
            stringResource(R.string.tutorial_if_it_ever_helps),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        AegisButton(onClick = onDone) { Text(stringResource(R.string.tutorial_enter_aegis)) }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.tutorial_you_can_replay_this),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ---- Small shared building blocks ----

/** A single "• text" line, cyan bullet, used by the feature pages. */
@Composable
private fun TutorialBullet(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(stringResource(R.string.tutorial_), color = AegisCyan, fontSize = 15.sp)
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
        )
    }
}

/**
 * One row of the tab tour (page 6): the tab's real nav icon on the
 * left, then its label + a one-line description.
 *
 * The icon rendering mirrors AegisBottomNav exactly so the tutorial
 * shows the genuine glyph the user will tap: [isRadar] routes to the
 * Canvas-drawn [RadarHexIcon] (its negative-space "N" can't survive a
 * flat [ColorFilter.tint]); every other tab is a tinted vector. The
 * label comes from the same `nav_*` string resource the bar uses.
 *
 * @param tint icon colour — defaults to brand cyan; SOS passes its
 *        danger red so the row matches the bar's locked-centre button.
 */
@Composable
private fun TutorialTabRow(
    @androidx.annotation.DrawableRes iconRes: Int,
    @androidx.annotation.StringRes labelRes: Int,
    desc: String,
    tint: androidx.compose.ui.graphics.Color = AegisCyan,
    isRadar: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = stringResource(labelRes)
        Box(
            modifier = Modifier.size(34.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isRadar) {
                RadarHexIcon(color = tint, size = 28.dp)
            } else {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = label,
                    colorFilter = ColorFilter.tint(tint),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color = tint,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Text(
                desc,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

/** One trust-tier card: tier name (cyan) + what that tier sees. */
@Composable
private fun TutorialTierCard(tier: String, sees: String) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(tier, color = AegisCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                sees,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
}

/** A single skill-tree node — small cyan-bordered hex with a short
 *  label inside. Decorative; the real tree lives in Security. */
@Composable
private fun TutorialSkillNode(label: String) {
    HexShape(
        size = 52.dp,
        borderColor = AegisCyan,
        fillColor = app.aether.aegis.ui.theme.AegisPanel,
    ) {
        Text(
            label,
            color = AegisCyan,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/** The "→" connector drawn between skill nodes. */
@Composable
private fun TutorialNodeArrow() {
    Text(
        "→",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}

// ---- First-launch gating + completion flag ----

/** SharedPreferences file + key for the one-time tutorial flag.
 *  Separate tiny prefs file so it's trivially inspectable and never
 *  tangled with feature settings. */
private const val TUTORIAL_PREFS = "tutorial_prefs"
private const val KEY_TUTORIAL_COMPLETED = "tutorial_completed"

/** True once the user has finished OR skipped the tutorial. Drives
 *  the first-launch auto-show in [firstRunNextDestination]. */
internal fun isTutorialCompleted(ctx: android.content.Context): Boolean =
    ctx.getSharedPreferences(TUTORIAL_PREFS, android.content.Context.MODE_PRIVATE)
        .getBoolean(KEY_TUTORIAL_COMPLETED, false)

/** Mark the tutorial done. Idempotent — safe to call on every exit
 *  path (finish, skip, replay) without checking first. */
internal fun markTutorialCompleted(ctx: android.content.Context) {
    ctx.getSharedPreferences(TUTORIAL_PREFS, android.content.Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_TUTORIAL_COMPLETED, true)
        .apply()
}

/**
 * The destination to send a first-run user to after the splash /
 * language picker. Single source of truth so the splash and the
 * language picker's `onPicked` can't disagree about whether the
 * tutorial runs.
 *
 * Order of precedence:
 *
 *   1. Already onboarded → "chats". A user with an identity is NOT
 *      a fresh install — this covers existing users updating into
 *      the build (they never get the tutorial shoved in front of
 *      them) and anyone returning after completing onboarding.
 *   2. Tutorial not yet completed → "tutorial". The genuine
 *      first-run case.
 *   3. Otherwise → "profile/onboard". Tutorial done (or skipped)
 *      but identity still not set up — continue into the functional
 *      onboarding.
 */
internal fun firstRunNextDestination(ctx: android.content.Context): String {
    val onboarded = AegisApp.instance.profileStore.onboarded
    return when {
        onboarded -> "chats"
        !isTutorialCompleted(ctx) -> "tutorial"
        else -> "profile/onboard"
    }
}

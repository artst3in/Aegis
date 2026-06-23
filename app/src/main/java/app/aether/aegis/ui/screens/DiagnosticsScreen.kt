package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.AegisTopBar

import app.aether.aegis.ui.components.AegisButton
import app.aether.aegis.ui.components.AegisOutlinedButton

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import app.aether.aegis.AegisApp
import app.aether.aegis.BuildConfig
import app.aether.aegis.simplex.ConnectionLog
import app.aether.aegis.ui.CheckState
import app.aether.aegis.ui.HealthCheck
import app.aether.aegis.ui.HealthVerdict
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.components.HexShape
import app.aether.aegis.ui.components.rememberVerdictPulse
import app.aether.aegis.ui.components.verdictColor
import app.aether.aegis.ui.computeNetworkHealth
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisOnline
import app.aether.aegis.ui.theme.AegisSOS
import app.aether.aegis.ui.theme.AegisWarning
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * One-stop diagnostics. Per user request, every "is the app actually
 * working" surface lives here instead of being scattered across the
 * settings tree:
 *
 *   - Live health probes (permissions, providers, transport, power)
 *     run on screen open and surface as red / yellow / green chips.
 *   - Driver-reload actions: re-arm location, restart SimpleX
 *     transport, re-broadcast hello + tier handshakes.
 *   - Inline connection log (the same ring buffer the SimpleX detail
 *     screen used to own — pulled here so all log surfaces live in
 *     one place).
 *   - Build / system metadata.
 *   - Debug overlay toggle (moved from Settings).
 *
 * Reachable from Settings → Diagnostics, with a deep link of
 * "diagnostics" in the nav graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Tick the probe state every 2 s so changes (e.g. permission
    // grant in system settings while this screen is open) reflect
    // without manual refresh.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2_000)
            tick++
        }
    }
    val probes = remember(tick) { runProbes(context) }
    val connLog by ConnectionLog.entries.collectAsState()
    // Last recorded uncaught crash (full stack trace), written by
    // BootHealthMonitor's handler to filesDir/last_crash.txt. Surfaced
    // here so the owner can copy the trace off a RELEASE build too — the
    // debug-only startup overlay doesn't run there — without needing adb.
    // Null when nothing has crashed since the last Clear.
    val crashMonitor = remember { app.aether.aegis.update.BootHealthMonitor(context) }
    var crashReport by remember { mutableStateOf(crashMonitor.lastCrashReport()) }
    // Abnormal process death from Android's exit-reason registry — catches
    // what the JVM crash handler can't: native (JNI/core) crashes, ANRs,
    // and low-memory kills. Null when the last death was normal or already
    // seen. Surfaced as a sibling card to the JVM crash report.
    var exitReason by remember { mutableStateOf(crashMonitor.lastExitReason()) }

    // Run the logcat tail while Diagnostics is open so the CALL LOG card
    // below has something to show. logcat dumps the existing per-PID
    // buffer before it follows, so opening this screen right after a
    // failed call still surfaces that call's CallJS lines (they're in the
    // buffer until it rotates). The Developer Tools screen owns the same
    // lifecycle for the full log; both are idempotent and never on-screen
    // at once, so the two start/stop pairs don't fight.
    val sysLog by app.aether.aegis.diag.DiagLog.lines.collectAsState()
    DisposableEffect(Unit) {
        app.aether.aegis.diag.DiagLog.start()
        onDispose { app.aether.aegis.diag.DiagLog.stop() }
    }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = { Text(stringResource(R.string.diagnostics_diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
            )
        },
    ) { padding ->
        // LazyColumn over Column(verticalScroll) — sampler at 116
        // showed all the scroll-busy time in
        // GraphicsLayerOwnerLayer.{mapBounds,updateMatrix} and
        // NodeCoordinator.rectInParent: the scroll-Column rendered
        // every card into one layer tree and translated every nested
        // layer's matrix on every frame. LazyColumn disposes the
        // cards that aren't on screen so only the visible ones
        // contribute matrix work.
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Bake each card into its own graphics layer. The scroll-
            // time sampler at 117 showed every busy sample in
            // GraphicsLayerOwnerLayer.{mapBounds, updateMatrix}: every
            // nested layer in every visible card was getting its
            // matrix recomputed on every scroll frame. Wrapping each
            // card in graphicsLayer() forces Compose to rasterize the
            // card into a single texture; scrolling then translates
            // that one texture instead of walking the nested layer
            // tree per frame. Re-rasterizes only when card content
            // actually changes — for static cards (HealthCard,
            // PowerCard, SystemCard) that's essentially never; for
            // tickers (ProcessViewerCard at 0.5 Hz) that's twice a
            // second, still cheap.
            val card: Modifier = Modifier.graphicsLayer()
            // A recorded crash is the single most important thing to
            // surface — it's WHY the app died last time. Pin it to the
            // very top, but only when one actually exists.
            crashReport?.let { rpt ->
                item("crash") {
                    Box(modifier = card) {
                        CrashReportCard(
                            report = rpt,
                            onCopy = {
                                runCatching {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("aegis-crash", rpt))
                                }
                            },
                            onClear = { crashMonitor.clearCrashReport(); crashReport = null },
                        )
                    }
                }
            }
            // Abnormal process death (native crash / ANR / OOM kill) — the
            // thing the JVM handler above structurally can't catch. Shown
            // right below the JVM crash card, equally pinned to the top.
            exitReason?.let { rpt ->
                item("exit-reason") {
                    Box(modifier = card) {
                        CrashReportCard(
                            report = rpt,
                            onCopy = {
                                runCatching {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("aegis-exit", rpt))
                                }
                            },
                            onClear = { crashMonitor.markExitReasonSeen(); exitReason = null },
                        )
                    }
                }
            }
            // Network health is the headline for a network app — the
            // honest "will my messages/calls/SOS alerts land" verdict sits
            // right at the top. (The emergency SOS-stop control moved
            // into the consolidated DANGER ZONE card at the bottom.)
            item("nethealth") { Box(modifier = card) { NetworkHealthCard() } }
            // Snatch toggle moved to the Experimental section 2026-06-04.
            item("health")     { Box(modifier = card) { HealthCard(probes) } }
            // Sentinel status card removed from Diagnostics 2026-06 — it
            // belongs with the Sonar/Sentinel surface, not the
            // "is the app working" diagnostics readout.
            //
            // The process viewer, full system log and debug-overlay (grid)
            // toggles moved to the debug-only DEVELOPER TOOLS screen 2026-06:
            // Diagnostics is the user-facing "is the app working + how do I
            // fix it" surface; engineering instrumentation lives behind the
            // developer door and only ships in debug builds.
            item("storage")    { Box(modifier = card) { StorageCard() } }
            item("actions")    { Box(modifier = card) { QuickActionsCard(context, scope) } }
            item("power")      { Box(modifier = card) { PowerCard() } }
            item("network")    { Box(modifier = card) { NetworkUsageCard() } }
            item("connLog") {
                Box(modifier = card) {
                    ConnectionLogCard(connLog, onCopy = {
                        runCatching {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("aegis-log", ConnectionLog.snapshot()))
                        }
                    }, onClear = { ConnectionLog.clear() })
                }
            }
            // CALL LOG — the WebRTC pipeline's own console, filtered out of
            // the full logcat tail. Lives on the main (release-visible)
            // Diagnostics screen because video calls fail on the RELEASE
            // build, where the full System Log behind Developer Tools
            // doesn't exist. Lets a failed call be captured + shared
            // on-device, no adb.
            item("callLog") {
                Box(modifier = card) {
                    CallLogCard(sysLog, onCopy = {
                        runCatching {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(
                                ClipData.newPlainText(
                                    "aegis-call-log",
                                    sysLog.filter(::isCallLogLine).joinToString("\n") { it.raw },
                                ),
                            )
                        }
                    }, onClear = { app.aether.aegis.diag.DiagLog.clear() })
                }
            }
            item("system")     { Box(modifier = card) { SystemCard() } }
            item("integrity")  { Box(modifier = card) { IntegrityCard(context) } }
            // Developer-tools entry — debug builds only. Release users never
            // see it, so the engineering toys can't clutter the status
            // screen they actually need.
            if (BuildConfig.DEBUG) {
                item("devtools") {
                    Box(modifier = card) {
                        DeveloperToolsEntryCard(
                            onOpen = { navController.navigate("developer-tools") },
                        )
                    }
                }
            }
            // DANGER ZONE — dead last so it can't be fat-fingered while
            // scrolling, and visually walled off in red. Holds every
            // destructive action: stop SOS, purge videos, reset core,
            // and the full zeroize wipe.
            item("danger")     { Box(modifier = card) { DangerZoneCard(context, scope) } }
        }
    }
}

/**
 * Device-integrity card. Surfaces the
 * warn-only [TamperCheck] signals + the APK signer fingerprint so the
 * owner can eyeball it against the published Aegis fingerprint.
 * Read-only and advisory — nothing here blocks or
 * changes app behaviour.
 */
@Composable
private fun IntegrityCard(context: Context) {
    // Computed once per card composition — these checks touch the
    // filesystem + PackageManager and don't change while the screen is up.
    val signals = remember { app.aether.aegis.integrity.TamperCheck.signals(context) }
    val signer = remember { app.aether.aegis.integrity.TamperCheck.signerSha256(context) }
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            DiagCardHeader("DEVICE INTEGRITY")
            if (signals.isEmpty()) {
                Text(
                    stringResource(R.string.diagnostics_no_tampering_signals_detected),
                    color = AegisOnline,
                    fontSize = 13.sp,
                )
            } else {
                signals.forEach { s ->
                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(stringResource(R.string.diagnostics_), color = AegisWarning, fontSize = 13.sp)
                        Text(
                            s,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.diagnostics_signer_sha256),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Text(
                signer ?: "unavailable",
                color = AegisCyan,
                fontSize = 10.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}

/* ---------- Probes ---------- */

/**
 * Tri-state health verdict for a single [Probe], carrying the dot colour
 * the HealthCard paints. WHY three and not a boolean: a degraded-but-
 * usable state (coarse-only location, while-in-use background, a
 * subsystem subject to Doze) is materially different from outright
 * broken — the user can still operate, just with reduced guarantees, so
 * it earns amber rather than red.
 */
private enum class ProbeStatus(val color: androidx.compose.ui.graphics.Color) {
    OK(AegisOnline),    // green — guarantee fully met
    WARN(AegisWarning), // amber — degraded but usable
    FAIL(AegisSOS),     // red — guarantee broken
}

/**
 * One row in the HEALTH card: a named capability, its [status], a short
 * human-readable [detail], and an optional one-tap [fix]. Pure data —
 * produced fresh by [runProbes] on each 2 s tick and rendered by
 * [HealthCard]; holds no live state itself.
 */
private data class Probe(
    val label: String,
    val status: ProbeStatus,
    val detail: String,
    /** Optional "Fix" callback wired to the relevant system settings
     *  page (or canonical repair action). Null when the probe is OK
     *  (nothing to fix) or when the fix isn't reachable from inside the
     *  app (e.g. Device Owner provisioning needs adb). */
    val fix: ((Context) -> Unit)? = null,
)

/**
 * Run every "is the app actually able to do its job" probe synchronously
 * and return them in display order. Each probe reads a live system signal
 * (a permission grant, a provider toggle, a transport's health flag, the
 * Doze whitelist) and maps it to a [Probe] with an actionable [Probe.fix]
 * where one exists.
 *
 * Called fresh on every 2 s tick so a grant the user just toggled in
 * system settings (while this screen is open) reflects without a manual
 * refresh. Cheap enough to re-run at that cadence — all checks are
 * in-memory PackageManager / system-service lookups.
 *
 * Does NOT request any permission or change any state; it only reads and
 * reports. The actual repair is deferred to the per-probe [Probe.fix]
 * callback the user taps.
 */
private fun runProbes(ctx: Context): List<Probe> {
    val probes = mutableListOf<Probe>()

    // Notification permission — runtime-gated only on Android 13 (TIRAMISU)
    // and up; on older releases the permission is implicit, so we skip the
    // probe entirely rather than show a row that can never be red.
    // FAIL (not WARN): silent SOS alerts defeat the whole point of the app.
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        val ok = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        probes += Probe(
            label = "Notification permission",
            status = if (ok) ProbeStatus.OK else ProbeStatus.FAIL,
            detail = if (ok) "granted" else "denied — SOS alerts will be silent",
            fix = if (ok) null else { c -> openNotificationSettings(c) },
        )
    }

    // Location permission. Fine → OK; coarse-only → WARN (we can still
    // place the user roughly but get no GPS-grade fix for an SOS); neither
    // → FAIL. The "Fix" routes to app details so the user can upgrade the
    // grant — coarse can't be promoted to fine without that trip.
    val fine = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    probes += Probe(
        label = "Location permission",
        status = when {
            fine -> ProbeStatus.OK
            coarse -> ProbeStatus.WARN
            else -> ProbeStatus.FAIL
        },
        detail = when {
            fine -> "fine granted"
            coarse -> "coarse only — no GPS fixes"
            else -> "denied — no location at all"
        },
        fix = if (fine) null else { c -> openAppDetails(c) },
    )

    // Background location — a distinct runtime permission only from
    // Android 10 (Q); before that, foreground location implies background.
    // Only WARN when missing (never FAIL): foreground fixes still work, we
    // just can't keep updating location once Aegis is backgrounded.
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val bg = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        probes += Probe(
            label = "Background location",
            status = if (bg) ProbeStatus.OK else ProbeStatus.WARN,
            detail = if (bg) "always allowed"
            else "only while using app — fixes pause when Aegis is backgrounded",
            // Fire the system "Allow all the time" request directly rather
            // than dropping the user on the generic App-info page to hunt
            // for the toggle (user request).
            fix = if (bg) null else { c -> requestBackgroundLocation(c) },
        )
    }

    // Location providers actually switched on at the OS level — distinct
    // from the app permission above: the user can grant Aegis location yet
    // have GPS/network location toggled off device-wide. isProviderEnabled
    // can throw on some OEMs / locked-down profiles, so each read is
    // wrapped and defaults to "off" (fail safe — better to under-report
    // than crash the diagnostics screen).
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (lm != null) {
        val gpsOn = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)
        val netOn = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
            .getOrDefault(false)
        // OK only when BOTH are on (best-accuracy + fastest first-fix);
        // either alone is WARN; neither is FAIL.
        val anyOn = gpsOn || netOn
        probes += Probe(
            label = "Location providers",
            status = when {
                gpsOn && netOn -> ProbeStatus.OK
                anyOn -> ProbeStatus.WARN
                else -> ProbeStatus.FAIL
            },
            detail = buildString {
                append("GPS ")
                append(if (gpsOn) "on" else "off")
                append(", NETWORK ")
                append(if (netOn) "on" else "off")
            },
            fix = if (gpsOn && netOn) null
            else { c -> openLocationSourceSettings(c) },
        )
    }

    // Microphone & camera — prerequisites for SOS evidence capture
    // (audio stream, video stream, duress mugshot). Only WARN when denied,
    // not FAIL: the rest of SOS (location broadcast, contact alerts) still
    // fires without them, so a missing capture permission degrades the
    // alert rather than killing it.
    val mic = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    probes += Probe(
        label = "Microphone permission",
        status = if (mic) ProbeStatus.OK else ProbeStatus.WARN,
        detail = if (mic) "granted" else "denied — SOS audio won't capture",
        fix = if (mic) null else { c -> openAppDetails(c) },
    )
    val cam = ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
    probes += Probe(
        label = "Camera permission",
        status = if (cam) ProbeStatus.OK else ProbeStatus.WARN,
        detail = if (cam) "granted" else "denied — SOS video & mugshot disabled",
        fix = if (cam) null else { c -> openAppDetails(c) },
    )

    // Transport health — one probe per registered transport (today just
    // SimpleX). isHealthy is the transport's own live verdict. The "Fix"
    // fires the canonical RESTART_TRANSPORT broadcast rather than touching
    // the transport directly, so the restart runs through ProtocolService
    // (the owner of the transport lifecycle) on its own thread.
    // AegisApp.instance access is guarded — during very early boot the
    // singleton may not be wired yet; getOrNull → empty list skips the rows.
    val transports = runCatching { AegisApp.instance.transports }.getOrNull().orEmpty()
    transports.forEach { t ->
        probes += Probe(
            label = "${t.protocol} transport",
            status = if (t.isHealthy) ProbeStatus.OK else ProbeStatus.FAIL,
            detail = if (t.isHealthy) "healthy" else "down — watchdog will retry",
            fix = if (t.isHealthy) null else { c ->
                c.sendBroadcast(
                    android.content.Intent(app.aether.aegis.services.ProtocolService.ACTION_RESTART_TRANSPORT)
                        .setPackage(c.packageName),
                )
            },
        )
    }

    // Device Owner — needs to be checked first because DO status
    // counts as an implicit battery-optimization exemption below.
    val dpm = ctx.getSystemService(android.app.admin.DevicePolicyManager::class.java)
    val isDO = dpm?.isDeviceOwnerApp(ctx.packageName) == true

    // Battery optimization exemption. Counts as OK if we're either
    // on the doze whitelist OR provisioned as Device Owner — DO
    // status means the user gave Aegis full-system trust, and the
    // raw isIgnoringBatteryOptimizations API doesn't reflect the
    // "Unrestricted" toggle in newer Pixel battery settings.
    val pw = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    if (pw != null) {
        val ignoringOpts = pw.isIgnoringBatteryOptimizations(ctx.packageName)
        val effectivelyExempt = ignoringOpts || isDO
        probes += Probe(
            label = "Battery optimization",
            status = if (effectivelyExempt) ProbeStatus.OK else ProbeStatus.WARN,
            detail = when {
                ignoringOpts -> "exempted"
                isDO -> "Device Owner — exempt by policy"
                else -> "subject to Doze — background socket I/O may stall"
            },
            fix = if (effectivelyExempt) null
            else { c -> requestIgnoreBatteryOptimizations(c) },
        )
    }
    probes += Probe(
        label = "Device Owner",
        status = if (isDO) ProbeStatus.OK else ProbeStatus.WARN,
        detail = if (isDO) "provisioned — silent install + lockdown available"
        else "not provisioned — some Loki features fall back to UI prompts",
    )

    return probes
}

/** Open this app's system "App info" page — the canonical landing spot
 *  for fixing a denied runtime permission (location, mic, camera,
 *  background location). NEW_TASK because we're launching a settings
 *  Activity from a non-Activity Context. Swallows any launch failure:
 *  a missing settings Activity must not crash diagnostics. */
private fun openAppDetails(ctx: Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    ).apply {
        data = android.net.Uri.fromParts("package", ctx.packageName, null)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }
}

/** Request the background-location ("Allow all the time") upgrade DIRECTLY
 *  through the system permission flow, instead of dropping the user on the
 *  generic App-info page to hunt for the toggle.
 *
 *  Android requires foreground location to be granted FIRST, so the probe
 *  only offers this once ACCESS_FINE_LOCATION is held; on API 30+ the OS then
 *  presents its own "Allow all the time" choice. We don't handle the result —
 *  the screen recomputes the probes on resume, so the row clears itself once
 *  granted. Falls back to App-info when we can't reach a hosting Activity (the
 *  request needs one) or below Android 10, where no separate background-
 *  location permission exists. */
private fun requestBackgroundLocation(ctx: Context) {
    val activity = ctx.activityOrNull()
    if (activity == null ||
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q
    ) {
        openAppDetails(ctx)
        return
    }
    runCatching {
        androidx.core.app.ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            REQ_BACKGROUND_LOCATION,
        )
    }.onFailure { openAppDetails(ctx) }
}

/** requestPermissions code for the background-location upgrade. We never read
 *  it back (the resume recompute re-reads the grant), it just has to be a
 *  stable non-negative int. */
private const val REQ_BACKGROUND_LOCATION = 0xB6

/** Unwrap a (possibly Compose- or theme-wrapped) [Context] to its hosting
 *  Activity, or null if none is in the wrapper chain — ActivityCompat
 *  .requestPermissions needs a real Activity to surface the system dialog. */
private fun Context.activityOrNull(): android.app.Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

/** Open the per-app notification settings page directly. Falls back to
 *  the generic App-info page ([openAppDetails]) if the dedicated screen
 *  isn't resolvable on this OEM build — some skins drop the
 *  APP_NOTIFICATION_SETTINGS action. */
private fun openNotificationSettings(ctx: Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,
    ).apply {
        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }
        .onFailure { openAppDetails(ctx) }
}

/** Open the system "Location" master-toggle page (device-wide GPS /
 *  network provider switches) — the fix for the "Location providers"
 *  probe, which is about the OS toggles, not the app's permission. */
private fun openLocationSourceSettings(ctx: Context) {
    val intent = android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }
}

/** Prompt the user to add Aegis to the battery-optimization (Doze)
 *  whitelist so background socket I/O isn't throttled. The @SuppressLint
 *  is deliberate: Google flags this intent as policy-sensitive, but
 *  reliable background delivery is core to a safety app, so we ask for
 *  it directly rather than via the generic battery-saver list. */
@android.annotation.SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(ctx: Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    ).apply {
        data = android.net.Uri.parse("package:${ctx.packageName}")
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { ctx.startActivity(intent) }
}

/* ---------- Sections ---------- */

/**
 * Network data-usage card (restored — the original lived in the old
 * StatusScreen and was lost in a cleanup). Reads the per-hour
 * `network_history` table (sampled by AegisApp's network ticker via
 * TrafficStats) through [Repository.observeNetworkHistory] and shows
 * total ↓rx / ↑tx over a 24 h / 7 d / 30 d window plus a stacked
 * hourly bar chart. Answers "how much data is Aegis using?".
 */
@Composable
private fun NetworkUsageCard() {
    // NB: not named `app` — that would shadow the `app.aether.aegis`
    // package and break the fully-qualified references below.
    val aegisApp = AegisApp.instance
    var rangeHours by remember { mutableStateOf(24) }
    val sinceMs = remember(rangeHours) {
        System.currentTimeMillis() - rangeHours * 3_600_000L
    }
    val history by aegisApp.repository.observeNetworkHistory(sinceMs)
        .collectAsState(initial = emptyList())

    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            DiagCardHeader("DATA USAGE")
            Text(
                stringResource(R.string.diagnostics_how_much_data_aegis),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(24 to "24h", 24 * 7 to "7d", 24 * 30 to "30d").forEach { (hrs, label) ->
                    FilterChip(
                        selected = rangeHours == hrs,
                        onClick = { rangeHours = hrs },
                        label = { Text(label, fontSize = 11.sp) },
                    )
                }
            }
            val totalRx = history.sumOf { it.rxBytes }
            val totalTx = history.sumOf { it.txBytes }
            Text(
                "↓ ${app.aether.aegis.ui.components.fmtBytes(totalRx)}" +
                    "   ↑ ${app.aether.aegis.ui.components.fmtBytes(totalTx)}" +
                    "   ·  total ${app.aether.aegis.ui.components.fmtBytes(totalRx + totalTx)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            if (history.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.diagnostics_building_usage_history),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            } else {
                // Tallest bar = the busiest hour; everything scales to it so
                // the chart always fills the height. coerceAtLeast(1) guards
                // the all-zero window (no divide-by-zero, flat baseline).
                val maxBar = (history.maxOfOrNull { it.rxBytes + it.txBytes } ?: 1L)
                    .coerceAtLeast(1L)
                val cyan = app.aether.aegis.ui.theme.AegisCyan
                val cyanDim = app.aether.aegis.ui.theme.AegisCyanDim
                androidx.compose.foundation.Canvas(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(top = 6.dp),
                ) {
                    // One slot per history row; the bar fills the middle 70%
                    // of its slot (15% gutter each side) so adjacent hours
                    // read as separate bars rather than a solid block.
                    val barW = size.width / history.size
                    history.forEachIndexed { i, row ->
                        val total = (row.rxBytes + row.txBytes).coerceAtLeast(0L)
                        if (total == 0L) return@forEachIndexed
                        val h = (total.toFloat() / maxBar) * size.height
                        val rxH = (row.rxBytes.toFloat() / maxBar) * size.height
                        val x = i * barW + barW * 0.15f
                        val w = barW * 0.7f
                        // Rx (cyan) at the bottom, Tx (dim cyan) stacked above.
                        drawRect(
                            color = cyan.copy(alpha = 0.75f),
                            topLeft = androidx.compose.ui.geometry.Offset(x, size.height - rxH),
                            size = androidx.compose.ui.geometry.Size(w, rxH),
                        )
                        drawRect(
                            color = cyanDim.copy(alpha = 0.65f),
                            topLeft = androidx.compose.ui.geometry.Offset(x, size.height - h),
                            size = androidx.compose.ui.geometry.Size(w, h - rxH),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders the list of [runProbes] verdicts as a coloured-dot checklist:
 * one row per capability with a status dot, label, detail line, and an
 * inline "Fix" button where the probe surfaced one. Stateless — the
 * caller re-supplies a fresh [probes] list on each tick, so the card
 * just paints whatever it's handed.
 */
@Composable
private fun HealthCard(probes: List<Probe>) {
    val ctx = LocalContext.current
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            DiagCardHeader("HEALTH")
            probes.forEach { p ->
                Row(
                    modifier = Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(p.status.color),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            p.label,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                        )
                        Text(
                            p.detail,
                            color = AegisOnSurfaceDim,
                            fontSize = 11.sp,
                        )
                    }
                    // Inline Fix button — only renders when the probe
                    // surfaced one (broken permissions, disabled
                    // providers, dead transports, missing battery
                    // exemption). Tap routes to the relevant system
                    // settings page or fires the canonical repair
                    // action so the user doesn't have to dig.
                    p.fix?.let { action ->
                        TextButton(onClick = { action(ctx) }) {
                            Text(stringResource(R.string.diagnostics_fix), color = AegisCyan, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Thin red rule + leading gap between actions inside the DANGER ZONE
 *  card, so each destructive control reads as its own walled-off block
 *  rather than a run-on list of red buttons. */
@Composable
private fun DangerDivider() {
    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = AegisSOS.copy(alpha = 0.25f), thickness = 1.dp)
    Spacer(modifier = Modifier.height(10.dp))
}

/**
 * DANGER ZONE — the single home for every destructive / irreversible
 * action, walled off in red at the very bottom of Diagnostics so none of
 * it can be fat-fingered while scrolling. Ordered least-to-most
 * catastrophic:
 *
 *   1. Stop all SOS        — operational; tears down an in-flight SOS.
 *   2. Purge videos        — deletes message rows + their video files.
 *   3. Reset SimpleX core  — wipes the SimpleX DBs + passphrase, kills
 *                            the process (Aegis-side history survives).
 *   4. Wipe all Aegis data — clean-install zeroize; the point of no
 *                            return, gated behind a typed confirmation.
 *
 * Consolidated here (2026-06) from cards scattered across the screen —
 * the SOS-stop card up top, the purge buttons inside STORAGE, the core
 * reset inside DRIVER RELOADS — so "anything that destroys something"
 * has exactly one predictable place.
 */
@Composable
private fun DangerZoneCard(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
    val ctx = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var confirmText by remember { mutableStateOf("") }
    // Typed-confirmation gate. A literal word the user must reproduce —
    // deliberately not localized so muscle memory can't autopilot it.
    val CONFIRM_WORD = "ERASE"

    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            DiagCardHeader("DANGER ZONE", AegisSOS)
            Text(
                "Destructive and irreversible actions. Everything here " +
                    "either tears down a live operation or permanently " +
                    "deletes data.",
                color = AegisOnSurfaceDim,
                fontSize = 12.sp,
            )

            // ---- 1. Stop all SOS ----
            // Idempotent: SOSHandler.cancel() tears down the repeating
            // broadcast, audio/video capture, lockdown and coordinator,
            // and emits "[sos cancelled]" so contacts' alerts clear —
            // safe to tap even when nothing is active.
            val sos by AegisApp.instance.sosHandler.state.collectAsState()
            val sosActive = sos != null
            DangerDivider()
            Text(
                if (sosActive) "SOS ACTIVE — broadcasting to your contacts."
                else "Force-stop anything in flight and clear contacts' alerts.",
                color = if (sosActive) AegisSOS else AegisOnSurfaceDim,
                fontSize = 11.sp,
                fontWeight = if (sosActive) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(modifier = Modifier.height(8.dp))
            AegisButton(
                onClick = { runCatching { AegisApp.instance.sosHandler.cancel() } },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AegisSOS),
            ) {
                Text(stringResource(R.string.diagnostics_stop_all_sos), fontWeight = FontWeight.Bold)
            }

            // The control-channel separation probe moved to DEVELOPER TOOLS
            // (debug-only) 2026-06 — it's an engineering probe, not a
            // destructive action, so it doesn't belong in the Danger Zone.

            // ---- 2. Purge videos ----
            // Both delete message rows AND their on-disk attachments.
            // Conservative targets the timestamped sos-test segments;
            // aggressive sweeps every video attachment regardless of
            // naming. Destructive of chat content, hence here.
            DangerDivider()
            Text(
                "Delete video attachments and their message rows. " +
                    "Cannot be undone.",
                color = AegisOnSurfaceDim,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            var sosBusy by remember { mutableStateOf(false) }
            var sosReport by remember {
                mutableStateOf<app.aether.aegis.storage.StorageCleanup.PurgeReport?>(null)
            }
            DiagButton(if (sosBusy) "Purging…" else "Purge timestamped videos") {
                if (sosBusy) return@DiagButton
                sosBusy = true
                scope.launch {
                    sosReport = withContext(Dispatchers.IO) {
                        app.aether.aegis.storage.StorageCleanup.purgeSOSEvidenceVideos(context)
                    }
                    sosBusy = false
                }
            }
            sosReport?.let { r ->
                Text(
                    "Timestamped-video purge: ${r.deletedRows} rows, ${formatBytes(r.freed)} freed",
                    fontSize = 10.sp,
                    color = AegisOnline,
                )
            }
            var nukeBusy by remember { mutableStateOf(false) }
            var nukeReport by remember {
                mutableStateOf<app.aether.aegis.storage.StorageCleanup.PurgeReport?>(null)
            }
            DiagButton(if (nukeBusy) "Nuking…" else "Purge ALL videos (aggressive)") {
                if (nukeBusy) return@DiagButton
                nukeBusy = true
                scope.launch {
                    nukeReport = withContext(Dispatchers.IO) {
                        app.aether.aegis.storage.StorageCleanup.purgeAllVideoAttachments(context)
                    }
                    nukeBusy = false
                }
            }
            nukeReport?.let { r ->
                Text(
                    "All-video purge: ${r.deletedRows} rows, ${formatBytes(r.freed)} freed",
                    fontSize = 10.sp,
                    color = AegisOnline,
                )
            }

            // ---- 3. Reset SimpleX core (nuclear) ----
            // Wipes the SimpleX-side databases + their keystore-wrapped
            // passphrase, then kills the process. The relaunch boots a
            // clean core with a fresh passphrase + empty agent/chat DBs.
            // Use when the core boot reports errorNotADatabase (mismatched
            // passphrase + DB, typical after a failed cross-rename
            // restore). Aegis-side known_peers + messages tables are
            // untouched, so contact history stays — Re-pair restores
            // delivery.
            DangerDivider()
            Text(
                "Wipe the SimpleX core databases and restart. Contacts and " +
                    "chat history are kept; queue state is destroyed. Use " +
                    "only when the core won't boot.",
                color = AegisOnSurfaceDim,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            var confirmCoreReset by remember { mutableStateOf(false) }
            if (confirmCoreReset) {
                AlertDialog(
                    onDismissRequest = { confirmCoreReset = false },
                    title = { Text(stringResource(R.string.diagnostics_reset_simplex_core)) },
                    text = {
                        Text(
                            stringResource(R.string.diagnostics_wipes_the_simplexside_databases) +
                                "(simplex_v1_agent.db, simplex_v1_chat.db) " +
                                "and the wrapped passphrase, then restarts " +
                                "the app. Aegis-side contacts and chat " +
                                "history are kept; SimpleX-side queue " +
                                "state is destroyed. After restart, use " +
                                "Re-pair on each contact to plumb " +
                                "delivery again. Use only when the core " +
                                "is failing to boot.",
                            fontSize = 13.sp,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmCoreReset = false
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    val dir = context.filesDir
                                    dir.listFiles { f ->
                                        f.isFile && (
                                            f.name == "simplex_v1_agent.db" ||
                                            f.name == "simplex_v1_chat.db" ||
                                            f.name.startsWith("simplex_v1_agent.db-") ||
                                            f.name.startsWith("simplex_v1_chat.db-") ||
                                            f.name == "simplex_v1_dbkey.wrapped"
                                        )
                                    }?.forEach { it.delete() }
                                }
                                // Alarm-then-kill so the user lands back
                                // in MainActivity after the suicide.
                                restartProcess(context)
                            }
                        }) {
                            Text(stringResource(R.string.diagnostics_reset), color = AegisSOS, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmCoreReset = false }) {
                            Text(stringResource(R.string.secure_notes_cancel))
                        }
                    },
                )
            }
            DiagButton("Reset SimpleX core (nuclear)") { confirmCoreReset = true }

            // ---- 4. Wipe all Aegis data ----
            DangerDivider()
            Text(
                stringResource(R.string.diagnostics_wipe_all_aegis_data) +
                    "return Aegis to a brand-new, clean-install state.",
                color = AegisOnSurfaceDim,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            AegisButton(
                onClick = { confirmText = ""; showDialog = true },
                // Protected Mode: the Danger Zone can be locked so a child
                // can't wipe the whole app (and their safety net with it).
                enabled = !isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.DANGER_ZONE),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AegisSOS),
            ) {
                Text("Wipe all Aegis data", fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showDialog) {
        val confirmed = confirmText.trim().equals(CONFIRM_WORD, ignoreCase = false)
        AlertDialog(
            onDismissRequest = { showDialog = false },
            icon = {
                Text("⚠", color = AegisSOS, fontSize = 34.sp)
            },
            title = {
                Text(
                    stringResource(R.string.diagnostics_erase_all_aegis_data),
                    color = AegisSOS,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.diagnostics_this_is_irreversible_it) +
                            "clean install and destroys, permanently:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    listOf(
                        "Every profile — real and decoy.",
                        "All messages, contacts, and groups.",
                        "The encrypted vault and all secure notes.",
                        "Your recovery-phrase keys and identity. Without a " +
                            "saved recovery phrase you can never reopen these " +
                            "chats — not even by reinstalling.",
                        "PIN, duress PINs, and every setting.",
                        "On-device backups, captures, and the SimpleX core.",
                    ).forEach { line ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(stringResource(R.string.tutorial_), color = AegisSOS, fontSize = 13.sp)
                            Text(
                                line,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.diagnostics_nothing_is_sent_anywhere) +
                            "Your contacts keep their own copies of your past " +
                            "messages; you will not.",
                        fontSize = 12.sp,
                        color = AegisOnSurfaceDim,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "Type $CONFIRM_WORD to confirm.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = confirmText.isNotEmpty() && !confirmed,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AegisSOS,
                            cursorColor = AegisSOS,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = confirmed,
                    onClick = {
                        showDialog = false
                        // Point of no return. clearApplicationUserData()
                        // erases everything and the OS kills the process
                        // mid-call, so no code after this runs.
                        app.aether.aegis.lock.LocalWipe.wipeAegisData(ctx)
                    },
                ) {
                    Text(
                        stringResource(R.string.diagnostics_erase_everything),
                        color = if (confirmed) AegisSOS else AegisOnSurfaceDim,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.secure_notes_cancel)) }
            },
        )
    }
}

/**
 * DRIVER RELOADS — the non-destructive "kick a subsystem" actions. Each
 * button force-resets one driver without restarting the whole app:
 * re-arm the location stream, restart the SimpleX transport, drain/clear
 * the outbox, re-broadcast the hello + shield-tier handshakes, or (last,
 * confirmed) a full process restart.
 *
 * Everything here is recoverable — these reload state, they don't delete
 * it; the destructive operations live in the DANGER ZONE card instead.
 * Most actions fire a broadcast to ProtocolService (the lifecycle owner)
 * or run a one-shot on Dispatchers.IO so the UI thread never blocks on
 * network/DB work.
 */
@Composable
private fun QuickActionsCard(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            DiagCardHeader("DRIVER RELOADS")
            Text(
                stringResource(R.string.diagnostics_forcereset_individual_subsystems_without),
                color = AegisOnSurfaceDim,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            // Non-destructive remote-wipe readiness check. Run this on the
            // device you intend to wipe BEFORE triggering the (one-shot,
            // irreversible) remote wipe — it verifies Device-Owner + clears
            // and re-checks the factory-reset block, and reports READY or the
            // exact blocker, WITHOUT wiping anything.
            var wipeReadiness by remember { mutableStateOf<String?>(null) }
            DiagButton("Check remote-wipe readiness") {
                wipeReadiness = runCatching {
                    app.aether.aegis.remote.RemoteCommandHandler.wipePreflight()
                }.getOrElse { "preflight error: ${it.message}" }
            }
            wipeReadiness?.let { report ->
                AlertDialog(
                    onDismissRequest = { wipeReadiness = null },
                    title = { Text("Remote-wipe readiness") },
                    text = { Text(report, fontSize = 13.sp) },
                    confirmButton = {
                        TextButton(onClick = { wipeReadiness = null }) { Text(stringResource(R.string.diagnostics_close)) }
                    },
                )
            }
            DiagButton("Re-arm location stream") {
                context.sendBroadcast(
                    Intent(app.aether.aegis.services.ProtocolService.ACTION_REARM_LOCATION)
                        .setPackage(context.packageName),
                )
            }
            DiagButton("Restart SimpleX transport") {
                context.sendBroadcast(
                    Intent(app.aether.aegis.services.ProtocolService.ACTION_RESTART_TRANSPORT)
                        .setPackage(context.packageName),
                )
            }
            // Clear outbox queue — co-located with the transport-
            // restart actions so a user who tapped the chat-list
            // "$N messages queued — tap to fix or clear" banner
            // sees both halves on the same screen. Same Repository
            // call as the OutboxCard in Settings.
            val pending by AegisApp.instance.repository.pendingCount()
                .collectAsState(initial = 0)
            DiagButton(
                if (pending > 0) "Clear outbox queue ($pending pending)"
                else "Clear outbox queue",
            ) {
                scope.launch(Dispatchers.IO) {
                    runCatching { AegisApp.instance.repository.clearOutbox() }
                }
            }
            // "Reset SimpleX core (nuclear)" used to live here. It wipes
            // the SimpleX-side databases + wrapped passphrase and kills
            // the process — destructive of queue state — so it moved to
            // the DANGER ZONE card with the other destructive ops.
            // The main-thread stack sampler moved to DEVELOPER TOOLS
            // (debug-only) 2026-06 — it's a profiling instrument, not a
            // user-facing repair action.
            DiagButton("Re-handshake all peers (hello broadcast)") {
                scope.launch(Dispatchers.IO) {
                    runCatching { app.aether.aegis.admin.HelloBroadcaster.broadcastNow(context) }
                }
            }
            DiagButton("Re-broadcast shield tier") {
                scope.launch(Dispatchers.IO) {
                    runCatching { app.aether.aegis.admin.TierBroadcaster.broadcastNow(context) }
                }
            }
            // Full process restart. Device Owner Aegis cannot be
            // force-stopped from system Settings, so the user has
            // no escape hatch if something goes weird at the
            // process level (state leak, leaked WebRTC engine,
            // stuck UI thread). This button kills the process and
            // schedules an Alarm 500 ms in the future to relaunch
            // MainActivity. ProtocolService's START_STICKY also
            // auto-restarts the background work independent of the
            // alarm. Confirmed with a dialog first because killing
            // the process is destructive of in-flight work.
            var confirmRestart by remember { mutableStateOf(false) }
            if (confirmRestart) {
                AlertDialog(
                    onDismissRequest = { confirmRestart = false },
                    title = { Text(stringResource(R.string.diagnostics_restart_aegis)) },
                    text = {
                        Text(
                            stringResource(R.string.diagnostics_kills_the_aegis_process) +
                                "sends are dropped to the outbox queue. Use this when " +
                                "something feels stuck and you can't force-stop because " +
                                "Aegis is Device Owner.",
                            fontSize = 13.sp,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmRestart = false
                            restartProcess(context)
                        }) {
                            Text(stringResource(R.string.diagnostics_restart), color = AegisSOS, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmRestart = false }) { Text(stringResource(R.string.secure_notes_cancel)) }
                    },
                )
            }
            AegisOutlinedButton(
                onClick = { confirmRestart = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AegisSOS,
                ),
            ) {
                Text(stringResource(R.string.diagnostics_restart_aegis_full_reboot), fontSize = 12.sp)
            }
        }
    }
}

/**
 * Hard process restart: schedule a relaunch alarm, then kill our own
 * PID. WHY this exists at all — a Device Owner Aegis can't be force-
 * stopped from system Settings, so without an in-app escape hatch a
 * process-level wedge (leaked WebRTC engine, stuck main thread, state
 * leak) would strand the user. The alarm-then-suicide ordering is the
 * whole trick: schedule the relaunch FIRST so it survives the kill.
 * Best-effort — the whole body is wrapped so a missing launch intent or
 * alarm failure can't leave us half-dead.
 */
private fun restartProcess(context: Context) {
    runCatching {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?: return@runCatching
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TASK,
        )
        val pi = android.app.PendingIntent.getActivity(
            context, 0, launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        // setExact requires SCHEDULE_EXACT_ALARM on API 31+ which we
        // don't declare; on Pixel the call silently no-op'd, so the
        // process kill fired but the relaunch alarm never did and the
        // user was stranded. setAndAllowWhileIdle works without any
        // alarm permission — for a 500 ms delay 'inexact' is
        // effectively immediate, and we add Doze-bypass so the alarm
        // still fires if the device idled into Doze in the gap.
        am.setAndAllowWhileIdle(
            android.app.AlarmManager.RTC,
            System.currentTimeMillis() + 500L,
            pi,
        )
        // Process suicide. The alarm above relaunches MainActivity
        // 500 ms later; ProtocolService is START_STICKY so the
        // foreground worker also comes back on its own pipeline.
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/** Shared full-width cyan outlined action button — the house style for
 *  every non-destructive Diagnostics action so they all read alike.
 *  (Destructive controls use a red-tinted variant inline.) */
@Composable
private fun DiagButton(label: String, onClick: () -> Unit) {
    AegisOutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = AegisCyan,
        ),
    ) {
        Text(label, fontSize = 12.sp)
    }
}

/**
 * Live PSS memory breakdown — the same numbers `adb shell dumpsys meminfo`
 * reports, surfaced in-app so a real leak can be diagnosed without a PC.
 * Reads [android.os.Debug.MemoryInfo] summary stats (API 23+) on a 2 s
 * ticker, off the main thread (getMemoryInfo walks /proc maps and isn't
 * free).
 *
 * Why this matters: OEM "Running services" memory screens often show
 * VIRTUAL / memory-mapped size, which for Aegis (native SimpleX core +
 * SQLCipher DB + mmapped attachments) can read as several GB while actual
 * physical use is a fraction of that. **Total PSS** is the real physical
 * footprint. The breakdown localises a leak: Graphics big = leaked
 * bitmaps/surfaces; Native big = buffers / the SimpleX core; Java big =
 * unbounded in-memory collections.
 */
@Composable
private fun MemoryCard() {
    data class Mem(
        val totalPssKb: Long, val javaKb: Long, val nativeKb: Long,
        val graphicsKb: Long, val codeKb: Long, val stackKb: Long,
        val otherKb: Long, val swapKb: Long,
    )
    var mem by remember { mutableStateOf<Mem?>(null) }
    LaunchedEffect(Unit) {
        fun sample(): Mem {
            val mi = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(mi)
            fun stat(k: String) = mi.getMemoryStat(k)?.toLongOrNull() ?: 0L
            return Mem(
                totalPssKb = stat("summary.total-pss"),
                javaKb = stat("summary.java-heap"),
                nativeKb = stat("summary.native-heap"),
                graphicsKb = stat("summary.graphics"),
                codeKb = stat("summary.code"),
                stackKb = stat("summary.stack"),
                otherKb = stat("summary.private-other"),
                swapKb = stat("summary.total-swap"),
            )
        }
        mem = withContext(Dispatchers.IO) { sample() }
        while (true) {
            kotlinx.coroutines.delay(2_000L)
            mem = withContext(Dispatchers.IO) { sample() }
        }
    }
    val m = mem ?: return
    fun mb(kb: Long) = "%,.0f MB".format(kb / 1024.0)
    // Total PSS colour: a chat app's real footprint is a few hundred MB.
    // Green < 500 MB, amber < 1 GB, red beyond — a multi-GB PSS is a real
    // leak (vs a multi-GB *virtual* figure on the OEM screen, which isn't).
    val totalColor = when {
        m.totalPssKb > 1_000_000L -> AegisSOS
        m.totalPssKb > 500_000L -> AegisWarning
        else -> AegisOnline
    }
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderTick()
                Text(
                    "MEMORY (PSS)",
                    color = AegisCyan,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "real RAM · 0.5 Hz",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = AegisOnSurfaceDim,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            ProcessRow("Total PSS", mb(m.totalPssKb), totalColor)
            ProcessRow("Java heap", mb(m.javaKb))
            ProcessRow("Native heap", mb(m.nativeKb))
            ProcessRow("Graphics", mb(m.graphicsKb))
            ProcessRow("Code", mb(m.codeKb))
            ProcessRow("Stack", mb(m.stackKb))
            ProcessRow("Other", mb(m.otherKb))
            if (m.swapKb > 0L) ProcessRow("Swap", mb(m.swapKb))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Total PSS is real physical memory (not the inflated virtual " +
                    "figure OEM task managers show). Graphics large = leaked " +
                    "bitmaps; Native = buffers/core; Java = in-memory objects.",
                color = AegisOnSurfaceDim,
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * Task-Manager-style live view of Aegis's own process. 0.5 Hz refresh
 * via a LaunchedEffect ticker that drives [app.aether.aegis.diag.ProcessSampler].
 * Sandbox limits us to /proc/self — we can't see other apps —
 * which is fine: the whole point is "what is Aegis itself doing
 * right now."
 *
 * Threads listed top-down by CPU%. Sleeping threads pile up at the
 * bottom (0% CPU) — useful for confirming, say, that the
 * SimpleX/Haskell GC workers are idle when the app is.
 */
@Composable
private fun ProcessViewerCard() {
    val sampler = remember { app.aether.aegis.diag.ProcessSampler() }
    var snap by remember { mutableStateOf<app.aether.aegis.diag.ProcessSnapshot?>(null) }
    LaunchedEffect(Unit) {
        // Sample on Dispatchers.IO — the sampler reads ~115 /proc files
        // per tick (one stat + one comm per thread, plus the process-
        // level files), parses them, and sorts the result. Running
        // that on the main thread blocks Compose's frame budget hard
        // enough to show up as 60%+ sustained main-thread CPU in the
        // very viewer it powers. IO dispatch keeps the work off the
        // critical thread and the state assignment on a background
        // coroutine writes back into the StateFlow-backed `snap` so
        // Compose recomposes on its own.
        snap = withContext(Dispatchers.IO) { sampler.sample() }
        while (true) {
            kotlinx.coroutines.delay(2_000L)
            snap = withContext(Dispatchers.IO) { sampler.sample() }
        }
    }
    var expanded by remember { mutableStateOf(false) }
    val s = snap ?: return
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderTick()
                Text(
                    stringResource(R.string.diagnostics_process),
                    color = AegisCyan,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "pid ${s.pid} · 0.5 Hz",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = AegisOnSurfaceDim,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            ProcessRow("CPU", "%.1f %%".format(s.totalCpuPct), cpuColor(s.totalCpuPct))
            ProcessRow("RSS", "%,d KB".format(s.rssKb))
            ProcessRow(
                "Heap",
                "java %,d / native %,d KB".format(s.javaUsedKb, s.nativeKb),
            )
            ProcessRow("Threads", "${s.threadCount}")
            ProcessRow(
                "Net",
                "↓ %.1f / ↑ %.1f KB/s".format(s.rxKbps, s.txKbps),
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    stringResource(R.string.diagnostics_threads),
                    color = AegisCyan,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                val visibleCount = if (expanded) s.threads.size else minOf(15, s.threads.size)
                Text(
                    "$visibleCount of ${s.threads.size} · ${if (expanded) "hide" else "expand"}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = AegisOnSurfaceDim,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Header row
            // Drop the threads list out of the accessibility tree.
                // Stack-sampler showed Android a11y framework
                // (getAllUncoveredSemanticsNodesToIntObjectMap) walking
                // the whole semantics graph every tick, which combined
                // with the 0.5 Hz updates pegged the main thread at
                // ~55% even off-screen for accessibility services. The
                // diagnostic content isn't useful for screen readers
                // anyway.
            Column(
                modifier = Modifier.semantics(
                    mergeDescendants = false,
                ) { invisibleToUser() },
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    ThreadCell("NAME", weight = 5f, dim = true)
                    ThreadCell("STATE", weight = 2f, dim = true)
                    ThreadCell("CPU", weight = 2f, dim = true, end = true)
                }
                val show = if (expanded) s.threads else s.threads.take(15)
                // Stable keys so Compose can diff individual rows
                // instead of rebuilding the entire list each tick.
                show.forEach { t ->
                    key(t.tid) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp),
                        ) {
                            ThreadCell(t.name, weight = 5f)
                            ThreadCell(t.state, weight = 2f)
                            ThreadCell(
                                "%.1f%%".format(t.cpuPct),
                                weight = 2f,
                                end = true,
                                color = cpuColor(t.cpuPct),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One label/value line in the process viewer's summary block (CPU, RSS,
 *  heap, threads, net). Monospace, fixed-width label column so the values
 *  align in a tidy column. */
@Composable
private fun ProcessRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = AegisOnSurfaceDim,
            modifier = Modifier.width(70.dp),
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A single weighted cell in the per-thread table (NAME / STATE / CPU).
 *  RowScope extension so [weight] resolves against the parent Row. [end]
 *  right-aligns the CPU column; [dim] greys the header labels; single-line
 *  with no overflow handling — thread names are expected to fit the column. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ThreadCell(
    text: String,
    weight: Float,
    dim: Boolean = false,
    end: Boolean = false,
    color: androidx.compose.ui.graphics.Color? = null,
) {
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = color ?: if (dim) AegisOnSurfaceDim else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(weight),
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End
        else androidx.compose.ui.text.style.TextAlign.Start,
        maxLines = 1,
    )
}

/** Cyan when idle, white at moderate, warning yellow if a single
 *  thread / process is hammering ≥30 %, SOS red ≥80 %. Helps spot
 *  the runaway in the list. */
private fun cpuColor(pct: Float): androidx.compose.ui.graphics.Color = when {
    pct >= 80f -> AegisSOS
    pct >= 30f -> AegisWarning
    pct >= 1f  -> AegisCyan
    else       -> AegisOnSurfaceDim
}

/**
 * Storage breakdown of Aegis's filesDir + cacheDir, sorted by size
 * descending. Surfaces the contributors that grew unbounded —
 * update.apk + previous.apk stash, mugshots/ with no retention cap,
 * accumulated diag logs — so they can be wiped without an ADB
 * session. Run cleanup button fires the full pass and reports bytes
 * reclaimed.
 */
@Composable
private fun StorageCard() {
    val ctx = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    // breakdown() walks profiles/<id>/* + filesDir tree — for a real
    // profile with attachments that's plenty of file I/O. Defer it to
    // a coroutine on IO instead of running synchronously inside
    // remember{} on the main thread. Empty initial state until the
    // first sample lands.
    var breakdown by remember {
        mutableStateOf<List<Pair<String, Long>>>(emptyList())
    }
    LaunchedEffect(refreshTick) {
        breakdown = withContext(Dispatchers.IO) {
            app.aether.aegis.storage.StorageCleanup.breakdown(ctx)
        }
    }
    var lastReport by remember {
        mutableStateOf<app.aether.aegis.storage.StorageCleanup.Report?>(null)
    }
    var busy by remember { mutableStateOf(false) }
    val cleanScope = rememberCoroutineScope()
    // Inspector state hoisted up so the purge actions below can
    // refresh the file list after deleting things, instead of
    // leaving the user staring at a stale pre-purge listing.
    var topFiles by remember {
        mutableStateOf<List<app.aether.aegis.storage.StorageCleanup.AppFileEntry>?>(null)
    }
    var topBusy by remember { mutableStateOf(false) }
    suspend fun refreshTopFiles() {
        if (topFiles != null) {
            topFiles = withContext(Dispatchers.IO) {
                app.aether.aegis.storage.StorageCleanup.listTopAppFiles(ctx, 20)
            }
        }
    }

    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderTick()
                Text(
                    stringResource(R.string.diagnostics_storage),
                    color = AegisCyan,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "${formatBytes(breakdown.sumOf { it.second })} tracked",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = AegisOnSurfaceDim,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (breakdown.isEmpty()) {
                Text(
                    stringResource(R.string.diagnostics_all_known_sinks_empty),
                    fontSize = 11.sp,
                    color = AegisOnSurfaceDim,
                )
            } else {
                breakdown.forEach { (label, sz) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(
                            label,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatBytes(sz),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (sz >= 100_000_000L) AegisWarning
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            DiagButton(if (busy) "Cleaning…" else "Run cleanup") {
                if (busy) return@DiagButton
                busy = true
                cleanScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        app.aether.aegis.storage.StorageCleanup.runFull(ctx)
                    }
                    lastReport = r
                    refreshTick++
                    busy = false
                }
            }
            lastReport?.let { r ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Freed ${formatBytes(r.total)}: " +
                        "apks ${formatBytes(r.updateApksFreed)}, " +
                        "mugshots ${formatBytes(r.mugshotsFreed)}, " +
                        "diag ${formatBytes(r.diagLogsFreed)}, " +
                        "cache ${formatBytes(r.cacheFreed)}",
                    fontSize = 10.sp,
                    color = AegisOnline,
                )
            }
            // Orphan-attachment sweep — walks profileRoot.attachmentsDir
            // (app_files/) and deletes every file NOT referenced by any
            // messages / stories / secure_notes row. Targets the
            // 2026.05.693 user-reported 5.9 GB app_files/ vs <50 MB
            // actual chat history mismatch. Safe by construction.
            var orphanBusy by remember { mutableStateOf(false) }
            var orphanReport by remember {
                mutableStateOf<app.aether.aegis.storage.StorageCleanup.OrphanReport?>(null)
            }
            Spacer(modifier = Modifier.height(4.dp))
            DiagButton(
                if (orphanBusy) "Sweeping app_files/…" else "Sweep orphan attachments"
            ) {
                if (orphanBusy) return@DiagButton
                orphanBusy = true
                cleanScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        app.aether.aegis.storage.StorageCleanup.pruneOrphanAppFiles(ctx)
                    }
                    orphanReport = r
                    refreshTick++
                    orphanBusy = false
                }
            }
            orphanReport?.let { r ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Orphan sweep: ${r.deletedFiles} files, ${formatBytes(r.freed)} freed",
                    fontSize = 10.sp,
                    color = AegisOnline,
                )
            }
            // The two video-purge actions (timestamped + aggressive
            // all-video) used to live here, but they DELETE message rows
            // and their attachments — destructive of user data. They now
            // live in the DANGER ZONE card at the bottom with the other
            // destructive operations, so this card stays a safe,
            // read-mostly storage view (breakdown + cache cleanup +
            // orphan sweep + inspector). The build/version line that was
            // stranded under those buttons is gone too — SYSTEM owns it.
            // Inspector: when the orphan sweep leaves app_files/ still
            // huge (DB-referenced bloat — single oversized file, or a
            // pattern of unwanted-but-referenced rows), list the
            // largest files so we can see what's actually there. Marks
            // each row with ●ref / ○orphan so it's clear which would
            // survive a follow-up orphan sweep. State hoisted above
            // so the purge actions can re-trigger this refresh.
            Spacer(modifier = Modifier.height(4.dp))
            DiagButton(
                if (topBusy) "Listing…" else "Inspect app_files/ (top 20)"
            ) {
                if (topBusy) return@DiagButton
                topBusy = true
                cleanScope.launch {
                    topFiles = withContext(Dispatchers.IO) {
                        app.aether.aegis.storage.StorageCleanup.listTopAppFiles(ctx, 20)
                    }
                    topBusy = false
                }
            }
            topFiles?.let { list ->
                Spacer(modifier = Modifier.height(6.dp))
                if (list.isEmpty()) {
                    Text(
                        stringResource(R.string.diagnostics_appfiles_is_empty),
                        fontSize = 10.sp,
                        color = AegisOnSurfaceDim,
                    )
                } else {
                    list.forEach { e ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable {
                                    runCatching {
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            ctx,
                                            "${ctx.packageName}.fileprovider",
                                            java.io.File(e.absolutePath),
                                        )
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, e.mime)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        ctx.startActivity(Intent.createChooser(intent, "Open with"))
                                    }
                                },
                        ) {
                            Row {
                                Text(
                                    if (e.referenced) "●" else "○",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = if (e.referenced) AegisOnline else AegisWarning,
                                    modifier = Modifier.width(14.dp),
                                )
                                Text(
                                    e.relativePath,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    formatBytes(e.sizeBytes),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = if (e.sizeBytes >= 100_000_000L) AegisWarning
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            e.peerLabel?.let { label ->
                                Text(
                                    "from $label · tap to open",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.sp,
                                    color = AegisCyan,
                                    modifier = Modifier.padding(start = 14.dp),
                                )
                            } ?: Text(
                                stringResource(R.string.diagnostics_tap_to_open),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                                color = AegisOnSurfaceDim,
                                modifier = Modifier.padding(start = 14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkHealthCard() {
    // The headline network panel. Unlike the old text-dump card, this
    // renders the honest end-to-end verdict (see NetworkHealth): green
    // ONLY when every delivery-critical link is up, so the user can
    // trust it as "messages, calls and SOS alerts will land." Polls the
    // SimpleX snapshot + outbox depth every 2 s and recomputes.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2_000)
            tick++
        }
    }
    val transport = remember {
        AegisApp.instance.transports
            .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
            .firstOrNull()
    } ?: return
    val pending by remember {
        runCatching { AegisApp.instance.repository.pendingCount() }
            .getOrDefault(kotlinx.coroutines.flow.flowOf(0))
    }.collectAsState(initial = 0)
    val scope = rememberCoroutineScope()

    val snap = remember(tick) { transport.networkSnapshot() }
    val health = remember(tick, pending) {
        computeNetworkHealth(snap, pending, System.currentTimeMillis())
    }
    val accent = verdictColor(health.verdict)
    // Same heartbeat that drives the header dot — shared phase, so the
    // card orb and the dot beat together.
    val orbPulse = rememberVerdictPulse(health.verdict, health.healthFraction)

    GlassPanel(modifier = Modifier.fillMaxWidth(), glow = health.verdict == HealthVerdict.OPERATIONAL) {
        Column(modifier = Modifier.padding(16.dp)) {
            // --- Verdict header: orb + headline + plain-language meaning ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    // Beat the orb in time with the heartbeat. Reading the
                    // pulse in the graphicsLayer block keeps the per-frame
                    // invalidation to the layer — no recomposition of the
                    // card on every frame.
                    modifier = Modifier.graphicsLayer {
                        val p = orbPulse.value
                        scaleX = 1f + 0.12f * p
                        scaleY = 1f + 0.12f * p
                    },
                ) {
                    HexShape(
                        size = 26.dp,
                        borderColor = accent,
                        borderWidth = 1.5.dp,
                        fillColor = accent.copy(alpha = 0.22f),
                        // Steady glow when we're promising delivery; the
                        // scale pulse above carries the beat for amber.
                        glow = health.verdict == HealthVerdict.OPERATIONAL,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        health.headline.uppercase(),
                        color = accent,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        health.summary,
                        color = AegisOnSurfaceDim,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = AegisBorder, thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // --- The delivery chain, link by link ---
            health.checks.forEach { CheckRow(it) }

            // --- Per-relay detail (the SMP hosts our queues live on) ---
            // Only MESSAGE relays — XFTP file-transfer servers are connected
            // on-demand during an attachment transfer and idle out otherwise,
            // so listing them here as "down" was pure noise (it's what made
            // the card look mostly-broken when delivery was fine). They don't
            // gate delivery health, so they don't belong in this list.
            val msgRelays = remember(snap.relays) {
                snap.relays.filterNot { app.aether.aegis.ui.isFileRelay(it.host) }
            }
            if (msgRelays.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    stringResource(R.string.diagnostics_relays),
                    color = AegisOnSurfaceDim,
                    fontSize = 9.5.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                // A "down" relay here is almost always just idle, not broken:
                // SimpleX connects to a server only while it has live work
                // there and reconnects on demand. Say so, so the red rows
                // don't read as an outage when delivery is actually fine.
                Text(
                    stringResource(R.string.diagnostics_relays_idle_note),
                    color = AegisOnSurfaceDim,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                val now = System.currentTimeMillis()
                msgRelays.forEach { relay -> RelayRow(relay.host, relay.connected, now - relay.sinceMs) }
            }

            // --- Errors, only when present ---
            snap.coreError?.let { ErrorBlock("Core error", it, AegisSOS) }
            snap.startError?.let { ErrorBlock("Last start error", it, AegisWarning) }
            snap.lastSendError?.let { ErrorBlock("Last send error", it, AegisSOS) }

            // --- One-tap recovery ---
            Spacer(modifier = Modifier.height(12.dp))
            AegisOutlinedButton(
                onClick = {
                    scope.launch { runCatching { transport.restart() } }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
            ) {
                Text(stringResource(R.string.diagnostics_reconnect_now), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Per-check state → colour. */
private fun checkColor(state: CheckState): Color = when (state) {
    CheckState.PASS -> AegisOnline
    CheckState.WARN -> AegisWarning
    CheckState.FAIL -> AegisSOS
}

/** One link in the delivery chain: a coloured tick, the link name, and
 *  its short status on the right. */
@Composable
private fun CheckRow(check: HealthCheck) {
    val color = checkColor(check.state)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Filled dot for PASS, hollow ring for WARN/FAIL so state reads
        // even without colour (accessibility / glance).
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CutCornerShape(50))
                .background(if (check.state == CheckState.PASS) color else Color.Transparent)
                .then(
                    if (check.state != CheckState.PASS)
                        Modifier.background(color.copy(alpha = 0.18f))
                    else Modifier,
                ),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            check.label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            check.detail,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** A single SMP relay's host + up/down + how long it's held that state. */
@Composable
private fun RelayRow(host: String, connected: Boolean, ageMs: Long) {
    val color = if (connected) AegisOnline else AegisSOS
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CutCornerShape(50))
                .background(color),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            host,
            color = AegisOnSurfaceDim,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Text(
            (if (connected) "up " else "down ") + shortAge(ageMs),
            color = color,
            fontSize = 11.sp,
        )
    }
}

/** Compact "12s" / "4m" / "2h" age formatting for relay state. */
private fun shortAge(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60      -> "${s}s"
        s < 3_600   -> "${s / 60}m"
        else        -> "${s / 3_600}h"
    }
}

/** Red/amber labelled error block, only rendered when an error exists. */
@Composable
private fun ErrorBlock(label: String, message: String, color: Color) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Text(
        message,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
    )
}

/**
 * POWER · VOYAGER — visualises the staged power budget: which subsystems
 * are currently running vs. shed, and the battery floor at which each one
 * cuts out when not charging. The thresholds shown (80/50/50/40/25 %) are
 * the documented Voyager curve and MUST track [PowerBudget]'s
 * shouldRun*() gates — this card reads those live flags, it doesn't own
 * the numbers. A green dot means the subsystem is active right now; red
 * means power-shed. Charging is reflected in the battery line because the
 * floors only apply on battery.
 */
@Composable
private fun PowerCard() {
    val budget = remember { AegisApp.instance.powerBudget }
    val level by budget.level.collectAsState()
    val charging by budget.charging.collectAsState()
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            DiagCardHeader("POWER · VOYAGER")
            Text(
                stringResource(R.string.diagnostics_subsystems_shut_off_as) +
                    "to each row is the floor at which that subsystem turns off " +
                    "when not charging.",
                color = AegisOnSurfaceDim,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            FieldRow(stringResource(R.string.contact_detail_battery), "$level %" + if (charging) " · charging" else "")
            Spacer(modifier = Modifier.height(4.dp))
            PowerStageRow("Update polling",     "≤80%", budget.shouldRunUpdateCheck())
            PowerStageRow("LunaGlass effects",  "≤50%", budget.shouldRunLunaGlassEffects())
            PowerStageRow(stringResource(R.string.experimental_snatch_detection),   "≤50%", budget.shouldRunSnatchDetection())
            PowerStageRow("SOS camera stream", "≤40%", budget.shouldRunCameraStream())
            PowerStageRow("SOS mic stream",   "≤25%", budget.shouldRunMicStream())
        }
    }
}

/** One row of the Voyager ladder: subsystem name, its battery-floor
 *  [threshold] label, and a dot that's green when [active] (running) and
 *  red when shed. The threshold text is purely informational — the
 *  active/shed decision was already made upstream by PowerBudget. */
@Composable
private fun PowerStageRow(label: String, threshold: String, active: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (active) AegisOnline else AegisSOS),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
        )
        Text(
            threshold,
            color = AegisOnSurfaceDim,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Inline view of the always-on [ConnectionLog] ring buffer — the SimpleX
 * connection-lifecycle subset (relay up/down, reconnects, send results).
 * Collapsed by default to one line; tap the header to expand to the last
 * 80 entries. Copy lifts the full snapshot to the clipboard; Clear empties
 * the buffer. This is the user-facing log that ships in release builds;
 * the heavier full-logcat [SystemLogCard] is debug-only.
 */
@Composable
private fun ConnectionLogCard(
    entries: List<ConnectionLog.Entry>,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    // Collapsed by default. Inflating 80 monospace rows pushed every
    // other card off-screen — user couldn't reach the Driver Reloads
    // or System cards without scrolling forever. Header row stays
    // tappable to toggle, plus the most recent line shows even when
    // collapsed so the user can spot a fresh failure at a glance.
    var expanded by remember { mutableStateOf(false) }
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderTick()
                Text(
                    "CONNECTION LOG · ${entries.size}",
                    color = AegisCyan,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (expanded) "▾" else "▸",
                    color = AegisCyan,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.diagnostics_copy), color = AegisCyan, fontSize = 12.sp)
                }
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.sentinel_log_clear), color = AegisOnSurfaceDim, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            val sdf = remember {
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            }
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.diagnostics_empty),
                    color = AegisOnSurfaceDim,
                    fontSize = 11.sp,
                )
            } else if (!expanded) {
                // Show only the most recent line so the card stays
                // short. Tap the header to expand.
                val newest = entries.last()
                Text(
                    "${sdf.format(java.util.Date(newest.ts))} [${newest.tag}] ${newest.message}",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            } else {
                // Expanded: render the last 80 lines inside a fixed-
                // height scrollable box so the rest of the diagnostics
                // page stays reachable. Newest at top.
                val recent = entries.takeLast(80).reversed()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column {
                        recent.forEach { e ->
                            Text(
                                "${sdf.format(java.util.Date(e.ts))} [${e.tag}] ${e.message}",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full Aegis system log — the live logcat tail for THIS process,
 * surfaced from [DiagLog]. Where [ConnectionLogCard] shows only the
 * always-on SimpleX lifecycle subset, this card shows EVERYTHING the
 * app logs: SOS/sentinel/sonar, crypto + control-channel, WorkManager,
 * the native `simplex:` relay lines, Compose/Coil/OkHttp, native crash
 * signatures — the inside view the connection log can't give.
 *
 * The underlying logcat subprocess only runs while this screen is open
 * (started/stopped by the caller's DisposableEffect) — it's heavier
 * than the ConnectionLog ring buffer, so we don't pay for it 24/7.
 *
 * A free-text filter greps tag+message live (logcat is noisy — 2 500
 * lines deep). Level drives the row colour so errors/warnings jump out.
 */
@Composable
private fun SystemLogCard(
    lines: List<app.aether.aegis.diag.DiagLog.Line>,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    // Apply the filter case-insensitively over the raw line (tag is
    // already embedded in raw, so one contains() covers tag + message).
    val shown = remember(lines, filter) {
        if (filter.isBlank()) lines
        else lines.filter { it.raw.contains(filter, ignoreCase = true) }
    }
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderTick()
                Text(
                    "SYSTEM LOG · ${lines.size}",
                    color = AegisCyan,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (expanded) "▾" else "▸",
                    color = AegisCyan,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.diagnostics_copy), color = AegisCyan, fontSize = 12.sp)
                }
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.sentinel_log_clear), color = AegisOnSurfaceDim, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (lines.isEmpty()) {
                Text(
                    // Capture is starting; on most devices the first lines
                    // land within a second of opening the screen.
                    "Capturing… (logcat tail starts when this screen opens)",
                    color = AegisOnSurfaceDim,
                    fontSize = 11.sp,
                )
            } else if (!expanded) {
                val newest = lines.last()
                Text(
                    newest.raw,
                    color = logLevelColor(newest.level),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            } else {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    label = { Text("Filter (tag or text)", fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                // Render the last 300 of the (filtered) lines, newest at
                // top, in a fixed-height scroll box so the rest of the
                // diagnostics page stays reachable.
                val recent = shown.takeLast(300).asReversed()
                if (recent.isEmpty()) {
                    Text(
                        stringResource(R.string.diagnostics_empty),
                        color = AegisOnSurfaceDim,
                        fontSize = 11.sp,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Column {
                            recent.forEach { line ->
                                Text(
                                    line.raw,
                                    color = logLevelColor(line.level),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** logcat tags the call / remote-access pipeline writes under. The
 *  WebRTC HTML/JS console is piped to "CallJS" (see CallVideoSurface);
 *  the native side logs under CallManager / RemoteAccessHandler / the
 *  audio router. "chromium" / "cr_media" carry the WebView's own native
 *  WebRTC + media-device diagnostics (ICE, getUserMedia, codec) — noisy
 *  but exactly the lines that explain a black remote video or a dead
 *  mic. All filtered to our PID already by the logcat tail. */
private val CALL_LOG_TAGS = setOf(
    "CallJS", "CallManager", "CallStore", "CallAudioRouter",
    "RemoteAccessHandler", "RemoteCommandHandler", "RemoteLiveCamera",
    "CallVideoSurface",
    "chromium", "cr_media", "WebRtcAudioManager", "WebRtcAudioRecord",
    "WebRtcAudioTrack",
)

/** Keyword fallback for call-relevant lines that land under a generic
 *  tag (System.err, AndroidRuntime, a bare renderer tag). Matched
 *  case-insensitively against the whole raw line. */
private val CALL_LOG_KEYWORDS = listOf(
    "webrtc", "getusermedia", "icecandidate", "peerconnection",
    "audio device", "MediaStream",
)

/** True when a captured log line is part of the call / WebRTC pipeline —
 *  drives the CALL LOG card's filter so the user sees just the call
 *  story, not the whole system tail. */
private fun isCallLogLine(line: app.aether.aegis.diag.DiagLog.Line): Boolean {
    if (line.tag in CALL_LOG_TAGS) return true
    val r = line.raw
    return CALL_LOG_KEYWORDS.any { r.contains(it, ignoreCase = true) }
}

/**
 * CALL LOG — the WebRTC pipeline's console, surfaced on RELEASE so the
 * video-call path can be triaged on-device without adb.
 *
 * The call WebView pipes every console.* line from the SimpleX call.js
 * engine to logcat under "CallJS" (see [app.aether.aegis.call.CallVideoSurface]);
 * the native handlers log under "CallManager" / "RemoteAccessHandler",
 * and the WebView's own native WebRTC stack under "chromium" / "cr_media".
 * This card runs off the same [app.aether.aegis.diag.DiagLog] tail the
 * Developer Tools screen uses, but filters to just those call-related
 * lines ([isCallLogLine]) and shows them in a copyable block.
 *
 * WHY it isn't just the full System Log: that card lives behind the
 * debug-only Developer Tools door. Video calls fail on the RELEASE build
 * the user carries, where that door doesn't exist — so the call subset
 * gets an always-available home on the main Diagnostics screen.
 *
 * Reproduce → return here → Copy → paste back. logcat dumps its existing
 * buffer when the tail starts, so a call that just failed is usually
 * still captured even though the tail wasn't running during it.
 */
@Composable
private fun CallLogCard(
    lines: List<app.aether.aegis.diag.DiagLog.Line>,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Pre-filter to call-pipeline lines only; recompute when the tail
    // pushes a new batch.
    val callLines = remember(lines) { lines.filter(::isCallLogLine) }
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderTick()
                Text(
                    "CALL LOG · ${callLines.size}",
                    color = AegisCyan,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (expanded) "▾" else "▸",
                    color = AegisCyan,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.diagnostics_copy), color = AegisCyan, fontSize = 12.sp)
                }
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.sentinel_log_clear), color = AegisOnSurfaceDim, fontSize = 12.sp)
                }
            }
            Text(
                "WebRTC / call pipeline log. Place a call, come back here, " +
                    "then Copy and share it so a broken call can be traced.",
                color = AegisOnSurfaceDim,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (callLines.isEmpty()) {
                Text(
                    "No call activity captured yet. Make (or attempt) a call, " +
                        "then return — logcat keeps a short buffer, so a call " +
                        "that just failed should still show up.",
                    color = AegisOnSurfaceDim,
                    fontSize = 11.sp,
                )
            } else if (!expanded) {
                val newest = callLines.last()
                Text(
                    newest.raw,
                    color = logLevelColor(newest.level),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            } else {
                // Newest at top, last 300, fixed-height scroll box so the
                // rest of the page stays reachable. Selectable so a partial
                // copy is possible too.
                val recent = callLines.takeLast(300).asReversed()
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Column {
                            recent.forEach { line ->
                                Text(
                                    line.raw,
                                    color = logLevelColor(line.level),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Map a logcat level char (V/D/I/W/E) to a row colour so errors and
 *  warnings stand out in the dense monospace wall. */
private fun logLevelColor(level: Char): Color = when (level) {
    'E', 'F' -> AegisSOS              // error / fatal — red
    'W' -> AegisWarning               // warning — amber
    'V', 'D' -> AegisOnSurfaceDim     // verbose / debug — recede
    else -> app.aether.aegis.ui.theme.AegisOnSurface  // info — primary text
}

/**
 * SYSTEM — static build + device metadata, the first thing to read off
 * into a bug report so a trace can be tied to an exact build. Version /
 * build / git-sha come from BuildConfig (compile-time constants); the
 * rest are read live from the OS.
 */
@Composable
private fun SystemCard() {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            DiagCardHeader("SYSTEM")
            FieldRow("Version", BuildConfig.AETHER_CARGO)
            FieldRow("Build", BuildConfig.BUILD_DNA)
            FieldRow("Git", BuildConfig.GIT_SHA.take(7)) // short sha — full is in BuildConfig
            // "Installer of record" — which store/sideload path installed
            // us; a sanity check that the APK came from where it should.
            // getInstallSourceInfo is API 30 (R)+; older releases fall back
            // to the deprecated getInstallerPackageName. "unknown" when the
            // installer didn't record itself (common for adb / direct APK).
            val installer = runCatching {
                val ctx = AegisApp.instance.applicationContext
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                    ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
                else
                    @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
            }.getOrNull() ?: "unknown"
            FieldRow("Installer of record", installer)
            FieldRow("Android", "API ${android.os.Build.VERSION.SDK_INT}")
            FieldRow("Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        }
    }
}

/**
 * Surfaces the most recent uncaught crash (full stack trace) recorded by
 * BootHealthMonitor's handler. The only place a RELEASE tester can
 * retrieve "why did it die last time" — the debug startup overlay
 * doesn't run on release. Copy lifts the whole trace to the clipboard so
 * it can be pasted into a bug report; Clear deletes the saved file.
 * Selectable so a partial copy is possible too. Only rendered by the
 * caller when a report exists.
 */
@Composable
private fun CrashReportCard(report: String, onCopy: () -> Unit, onClear: () -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderTick(AegisSOS)
                Text(
                    "LAST CRASH",
                    color = AegisSOS,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.diagnostics_copy), color = AegisCyan, fontSize = 12.sp)
                }
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.sentinel_log_clear), color = AegisOnSurfaceDim, fontSize = 12.sp)
                }
            }
            Text(
                "The most recent unexpected shutdown. Copy this and send it " +
                    "over so the cause can be tracked down.",
                color = AegisOnSurfaceDim,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Fixed-height scroll box so a long trace doesn't push the
            // rest of Diagnostics off-screen. Newest crash only.
            androidx.compose.foundation.text.selection.SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        report,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * DEBUG OVERLAY toggles — a per-item menu (not one master switch) for the
 * on-screen developer overlays: FPS/recomposition counters, the alignment
 * grids that draw over the whole screen including the header. Lives on the
 * debug-only DeveloperTools screen. Each [DebugPrefs.Item] flips
 * independently and persists through [DebugPrefs]; the overlay renderer
 * elsewhere observes the same flow.
 */
@Composable
private fun DebugToggleCard(context: Context) {
    val prefs = remember { app.aether.aegis.ui.DebugPrefs(context) }
    // Atomic menu: each overlay item toggles independently instead of one
    // all-or-nothing switch. Counters/graph live in the header; the grids
    // overlay the whole screen for alignment checking.
    val enabled by prefs.enabledFlow.collectAsState()
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            DiagCardHeader("DEBUG OVERLAY")
            Text(
                "Pick what to draw. The grids overlay the whole screen " +
                    "(header included) — use them to eyeball element " +
                    "alignment down to the pixel.",
                color = AegisOnSurfaceDim,
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.height(6.dp))
            app.aether.aegis.ui.DebugPrefs.Item.values().forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.label,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            item.desc,
                            color = AegisOnSurfaceDim,
                            fontSize = 11.sp,
                        )
                    }
                    app.aether.aegis.ui.components.HexSwitch(
                        checked = enabled.contains(item.key),
                        onCheckedChange = { prefs.set(item, it) },
                    )
                }
            }
        }
    }
}

/**
 * Canonical Diagnostics card header — a short accent tick followed by a
 * small-caps, letter-spaced label. One component so every card reads the
 * same; the [accent] colours both the tick and the label (cyan for normal
 * sections, red for the SOS / danger cards). Emits its own trailing
 * spacing so call sites are a single line.
 */
@Composable
private fun DiagCardHeader(label: String, accent: Color = AegisCyan) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HeaderTick(accent)
        Text(
            label,
            color = accent,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(modifier = Modifier.height(10.dp))
}

/** Leading accent tick — a short rounded bar that anchors a section
 *  label and gives the whole screen a consistent, designed rhythm. Used
 *  by [DiagCardHeader] and inline by the cards whose header is a custom
 *  Row (label + right-side content) so every header reads the same. */
@Composable
private fun HeaderTick(accent: Color = AegisCyan) {
    Box(
        modifier = Modifier
            .size(width = 3.dp, height = 12.dp)
            .clip(CutCornerShape(2.dp))
            .background(accent),
    )
    Spacer(modifier = Modifier.width(8.dp))
}

/** Two-column key/value line used by the SYSTEM and POWER cards: dim
 *  label on the left (~45%), monospace value on the right (~55%) so
 *  numbers and identifiers line up cleanly down the card. */
@Composable
private fun FieldRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            label,
            color = AegisOnSurfaceDim,
            fontSize = 12.sp,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.55f),
        )
    }
}

// SentinelStatusCard was removed from Diagnostics 2026-06. The Sentinel
// status surface lives with the Sonar/Sentinel screens; it didn't belong
// in the "is the app working" diagnostics readout.

/* ====================================================================
 * DEVELOPER TOOLS — debug-only engineering instrumentation
 *
 * Diagnostics is the user-facing "is the app working + how do I fix it"
 * surface. Everything here is for whoever is BUILDING Aegis, not running
 * it: live process/thread sampling, the full logcat tail, the alignment-
 * grid overlay toggles, the main-thread profiler, and the control-channel
 * separation probe. The entry point only renders in debug builds
 * (BuildConfig.DEBUG), so release users never reach it.
 * ==================================================================== */

/** Single tappable row at the bottom of Diagnostics (debug builds only)
 *  that opens the [DeveloperToolsScreen]. Kept deliberately plain so it
 *  reads as a door, not another status card. */
@Composable
private fun DeveloperToolsEntryCard(onOpen: () -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                DiagCardHeader("DEVELOPER TOOLS")
                Text(
                    "Process viewer, full system log, debug-grid overlay, " +
                        "main-thread profiler, channel probe. Debug builds only.",
                    color = AegisOnSurfaceDim,
                    fontSize = 11.sp,
                )
            }
            Text("›", color = AegisCyan, fontSize = 22.sp)
        }
    }
}

/**
 * Debug-only screen holding the engineering instrumentation that used to
 * clutter Diagnostics. Reached via [DeveloperToolsEntryCard]; the route is
 * registered unconditionally but the only way in is the debug-gated entry.
 *
 * Owns the [DiagLog] logcat-tail lifecycle: the heavier capture subprocess
 * runs only while this screen is on-screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperToolsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sysLog by app.aether.aegis.diag.DiagLog.lines.collectAsState()
    DisposableEffect(Unit) {
        app.aether.aegis.diag.DiagLog.start()
        onDispose { app.aether.aegis.diag.DiagLog.stop() }
    }
    Scaffold(
        topBar = {
            AegisTopBar(
                title = { Text("Developer tools") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val card: Modifier = Modifier.graphicsLayer()
            item("process") { Box(modifier = card) { ProcessViewerCard() } }
            item("memory") { Box(modifier = card) { MemoryCard() } }
            item("sysLog") {
                Box(modifier = card) {
                    SystemLogCard(sysLog, onCopy = {
                        runCatching {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(
                                ClipData.newPlainText(
                                    "aegis-system-log",
                                    app.aether.aegis.diag.DiagLog.snapshot(),
                                ),
                            )
                        }
                    }, onClear = { app.aether.aegis.diag.DiagLog.clear() })
                }
            }
            item("probes") { Box(modifier = card) { DeveloperProbesCard(context, scope) } }
            item("debug")  { Box(modifier = card) { DebugToggleCard(context) } }
        }
    }
}

/**
 * Engineering probes that don't fit the "status / fix" model: the
 * main-thread stack profiler (find a busy-loop) and the control-channel
 * separation probe (does the core round-trip a custom MsgContent type).
 * Both moved here from Diagnostics 2026-06.
 */
@Composable
private fun DeveloperProbesCard(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            DiagCardHeader("PROBES")

            // Main-thread stack sampler. Captures the main thread's stack
            // trace 30 × every 100 ms, counts which top frame appears most
            // often, surfaces the top 10. Use when the Process viewer shows
            // app.aether.aegis hot permanently — the top frame tells you
            // exactly what method is being called in a loop.
            var stackResult by remember { mutableStateOf<String?>(null) }
            var stackBusy by remember { mutableStateOf(false) }
            DiagButton(
                if (stackBusy) "Sampling…" else "Sample main-thread stack",
            ) {
                if (stackBusy) return@DiagButton
                stackBusy = true
                scope.launch(Dispatchers.IO) {
                    val mainThread = android.os.Looper.getMainLooper().thread
                    val frameCounts = HashMap<String, Int>()
                    repeat(30) {
                        val stack = runCatching { mainThread.stackTrace }
                            .getOrNull() ?: return@repeat
                        stack.take(6).forEach { frame ->
                            val key = "${frame.className}.${frame.methodName}"
                            frameCounts[key] = (frameCounts[key] ?: 0) + 1
                        }
                        kotlinx.coroutines.delay(100L)
                    }
                    stackResult = if (frameCounts.isEmpty()) "no samples captured"
                    else frameCounts.entries
                        .sortedByDescending { it.value }
                        .take(10)
                        .joinToString("\n") { (k, v) ->
                            "${v.toString().padStart(3)} × $k"
                        }
                    stackBusy = false
                }
            }
            stackResult?.let { sample ->
                AlertDialog(
                    onDismissRequest = { stackResult = null },
                    title = { Text(stringResource(R.string.diagnostics_mainthread_sample_30_100)) },
                    text = {
                        Text(sample, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("aegis-stack", sample))
                            stackResult = null
                        }) { Text(stringResource(R.string.diagnostics_copy)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { stackResult = null }) { Text(stringResource(R.string.diagnostics_close)) }
                    },
                )
            }

        }
    }
}

package app.aether.aegis.alert

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisSOS
import app.aether.aegis.ui.theme.AegisWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-app Alert Center — the bell.
 *
 * One calm, non-invasive surface that aggregates everything the user might
 * need to act on, so a missed OS notification (or a phone that suppressed
 * it) doesn't mean a missed safety signal. Built on the established
 * notification-center conventions:
 *
 *  - THREE severities only (more breeds confusion): CRITICAL / WARNING /
 *    INFO → red / amber / cyan.
 *  - Every entry is ACTIONABLE and names its subject — it carries a [route]
 *    deep-link to where you fix or review it. If nothing can be done about a
 *    state, it isn't surfaced as an alert.
 *  - Only criticals also fire an OS notification (elsewhere); the bell is the
 *    durable in-app record, never an interrupt.
 *
 * Entry kinds (by behaviour):
 *  - Conditions (transport / permissions / battery): recomputed live, they
 *    vanish the instant the user fixes them — nothing to dismiss.
 *  - Info nudges + events (backup / canary / sentinel): [dismissible], and a
 *    dismissed nudge stays quiet for a day (see [AlertDismiss]) so the bell
 *    never nags.
 *
 * This file is the aggregator. Event-store sources (SOS received from a
 * contact, mugshot captured, SIM-swap, geofence, peer-wiped) plug in here as
 * additional entries in [rememberAlerts] — the read APIs for those stores are
 * the next increment; the bell + panel already render whatever this returns.
 */
enum class AlertSeverity(val rank: Int, val color: Color) {
    INFO(1, AegisCyan),
    WARNING(2, AegisWarning),
    CRITICAL(3, AegisSOS),
}

/**
 * One actionable item in the bell. [id] is stable per logical alert (so a
 * recurring condition de-dupes and a dismissal can target it). [route] is the
 * NavHost destination tapping the row opens; null = informational only.
 */
data class AlertEntry(
    val id: String,
    val severity: AlertSeverity,
    val title: String,
    val detail: String,
    val route: String? = null,
    val dismissible: Boolean = false,
)

/**
 * Per-id dismissal with a re-arm window, so dismissing an INFO nudge or event
 * silences it for [DEFAULT_SNOOZE_MS] (a day) rather than forever — the
 * "don't nag, but don't hide a recurring problem permanently" rule. Conditions
 * never dismiss (they clear themselves on fix). Reactive via [version] so the
 * composable recomputes the moment something is dismissed.
 */
object AlertDismiss {
    private const val STORE = "aegis_alert_dismiss"
    private const val DEFAULT_SNOOZE_MS = 24L * 60 * 60 * 1000

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun isDismissed(ctx: Context, id: String): Boolean =
        prefs(ctx).getLong(id, 0L) > System.currentTimeMillis()

    fun dismiss(ctx: Context, id: String, forMs: Long = DEFAULT_SNOOZE_MS) {
        prefs(ctx).edit().putLong(id, System.currentTimeMillis() + forMs).apply()
        _version.value++
    }
}

/**
 * Compute the live alert list. Recomputes when transport health changes, on a
 * 15 s ticker, on app resume (permissions/battery can change while we were
 * in system settings), and whenever a dismissal lands. Cheap — a handful of
 * SharedPreferences + permission reads.
 */
@Composable
fun rememberAlerts(): List<AlertEntry> {
    val ctx = LocalContext.current
    val dismissVersion by AlertDismiss.version.collectAsState()

    // Pending 1:1 invite links — surfaced here (and ONLY here, deep-linking to
    // PendingInvitationsScreen) since they no longer sit inline in the chat
    // list. Suppressed under duress: a decoy unlock must not reveal that a real
    // outstanding link exists, the same rule the chat list applies to contacts.
    val inDuress = app.aether.aegis.AegisApp.instance.lockState.inDuressMode
    val pendingInvites by app.aether.aegis.AegisApp.instance.repository
        .observePendingInvitations()
        .collectAsState(initial = emptyList())

    // Re-read self-state periodically + on resume (returning from the system
    // permission / battery screens must refresh the conditions immediately).
    var tick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(15_000L); tick++ }
    }

    return remember(tick, dismissVersion, inDuress, pendingInvites.size) {
        val now = System.currentTimeMillis()
        val out = mutableListOf<AlertEntry>()

        // --- Condition: unused invite links waiting -----------------------
        // INFO, not dismissible: it's a live condition that clears the instant
        // the link is accepted (becomes a contact) or revoked. Hidden under
        // duress so the decoy never leaks a real outstanding link.
        if (!inDuress && pendingInvites.isNotEmpty()) {
            val n = pendingInvites.size
            out += AlertEntry(
                "pending-invites", AlertSeverity.INFO,
                "$n invite link${if (n > 1) "s" else ""} waiting",
                "Unused invitations you created — tap to view or revoke.",
                "pending-invitations",
            )
        }

        // --- Connection/delivery health is DELIBERATELY NOT a bell entry ---
        // The header heartbeat dot is the dedicated connection indicator
        // (colour + beat), and tapping it already opens the Diagnostics
        // network card — which shows this exact verdict text plus the relay
        // breakdown and a Reconnect button. Surfacing the same condition as a
        // bell alert too was pure double-signalling (user report), so the bell
        // only carries conditions that have NO indicator of their own.

        // --- Condition: critical safety permissions denied ----------------
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        val missing = buildList {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) add("Location")
            if (!granted(Manifest.permission.RECORD_AUDIO)) add("Microphone")
            if (!granted(Manifest.permission.CAMERA)) add("Camera")
            if (Build.VERSION.SDK_INT >= 33 &&
                !granted(Manifest.permission.POST_NOTIFICATIONS)
            ) add("Notifications")
        }
        if (missing.isNotEmpty()) out += AlertEntry(
            "perm", AlertSeverity.CRITICAL,
            "${missing.size} permission${if (missing.size > 1) "s" else ""} missing",
            "${missing.joinToString(", ")} — safety features need these. Tap to fix.",
            "diagnostics",
        )

        // --- Condition: location granted but not "Allow all the time" -----
        // Foreground-only location means SOS / Sentinel / geofence can't read
        // a position once Aegis is backgrounded — exactly when it matters.
        // Surfaced separately from the CRITICAL "missing" alert above (and
        // only when FINE is already granted, since background can't be
        // requested before foreground) so the user sees that an upgrade IS
        // available — a fix exists, it just hadn't been flagged (user report).
        // WARNING, not CRITICAL: foreground location still works, this is the
        // "all the time" upgrade. Android 10+ only; pre-29 background is
        // implied by foreground so there's nothing to grant.
        if (Build.VERSION.SDK_INT >= 29 &&
            granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) out += AlertEntry(
            "bg-location", AlertSeverity.WARNING,
            "Location is foreground-only",
            "Set location to \"Allow all the time\" so SOS and Sentinel can still find you when Aegis is in the background.",
            "diagnostics",
        )

        // --- Condition: battery optimization (the OEM background-killer) ---
        // A Device Owner is exempt from Doze/battery-optimization BY POLICY, but
        // PowerManager.isIgnoringBatteryOptimizations can still report false for
        // it — so this alert fired on the Device-Owner Pixel even though nothing
        // was wrong (user report). Treat Device Owner as exempt, exactly as the
        // Diagnostics battery probe does.
        val pm = ctx.getSystemService(PowerManager::class.java)
        val ignoring = runCatching {
            pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        }.getOrDefault(true)
        val isDeviceOwner = runCatching {
            ctx.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                ?.isDeviceOwnerApp(ctx.packageName) == true
        }.getOrDefault(false)
        if (!ignoring && !isDeviceOwner) out += AlertEntry(
            "battery", AlertSeverity.WARNING,
            "Battery optimization is on",
            "Android may kill Aegis in the background, so messages stop arriving. Tap to fix.",
            "diagnostics",
        )

        // --- Info: backup overdue -----------------------------------------
        runCatching {
            val last = app.aether.aegis.backup.BackupPrefs(ctx).lastBackupAt
            val overdueMs = 7L * 24 * 60 * 60 * 1000
            if (last == 0L || now - last > overdueMs) out += AlertEntry(
                "backup", AlertSeverity.INFO,
                if (last == 0L) "No backup yet" else "Backup is overdue",
                "Back up your encrypted data so you can recover it after a wipe or new phone.",
                "settings/backup", dismissible = true,
            )
        }

        // --- Condition/event: canary timer --------------------------------
        runCatching {
            val cs = app.aether.aegis.canary.CanaryStore(ctx)
            if (cs.enabled && cs.lastCheckInAt != 0L) {
                val remaining = cs.intervalMs - (now - cs.lastCheckInAt)
                when {
                    remaining <= 0L -> out += AlertEntry(
                        "canary", AlertSeverity.CRITICAL,
                        "Canary overdue",
                        "Your dead-man's-switch is past due — check in now to stop it firing to your contacts.",
                        "settings/canary",
                    )
                    remaining <= 2L * 60 * 60 * 1000 -> out += AlertEntry(
                        "canary", AlertSeverity.WARNING,
                        "Canary check-in due soon",
                        "Open the canary screen to reset the timer before it fires.",
                        "settings/canary",
                    )
                }
            }
        }

        // --- Event: Sentinel sensor trips in the last 24h -----------------
        runCatching {
            val rows = app.aether.aegis.sentinel.SentinelEventLog(ctx).tail(50)
            val windowStart = now - 24L * 60 * 60 * 1000
            val trips = rows.count {
                it.timestampMs >= windowStart &&
                    it.sensor != app.aether.aegis.sentinel.SensorId.STAGE_TRANSITION
            }
            if (trips > 0) out += AlertEntry(
                "sentinel", AlertSeverity.WARNING,
                "Sentinel detected activity",
                "$trips event${if (trips > 1) "s" else ""} while armed in the last 24h. Tap to review.",
                "settings/sentinel/log", dismissible = true,
            )
        }

        out.filterNot { it.dismissible && AlertDismiss.isDismissed(ctx, it.id) }
            .sortedByDescending { it.severity.rank }
    }
}

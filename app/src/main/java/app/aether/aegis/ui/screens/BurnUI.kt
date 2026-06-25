package app.aether.aegis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.aether.aegis.core.Message
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisPanel
import app.aether.aegis.ui.theme.AegisSOS
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Burn-after-reading UI surfaces.
 *
 * Three components:
 *   - [BurnBubble]: replaces MessageBubble's body for [MessageType.BURN]
 *     rows. Fire icon + label; tap (incoming) opens the viewer. Outgoing
 *     burns just show "awaiting receipt" — the sender already knows
 *     what they wrote, and the row vanishes once the recipient's
 *     receipt arrives.
 *   - [BurnTtlPickerDialog]: 5 s / 30 s / Until close picker shown from
 *     the attach drawer.
 *   - [BurnViewerDialog]: full-screen Dialog with `FLAG_SECURE`
 *     (no screenshots, no Recents preview), a countdown progress bar,
 *     and a one-tap close that fires the burn-receipt back and wipes
 *     the local row.
 *
 * The marker shape on the wire is `[aegis:burn:<ttl>:<senderRowId>]<text>`.
 * We store the full marker in the body so the viewer can recover the
 * sender's row id (for the receipt) and the cleaned text on open.
 */

// Wire/storage marker for a burn message. Shape:
//   [aegis:burn:<ttlSeconds>:<senderRowId>]<text>
// DOT_MATCHES_ALL so a multi-line body is captured whole by group 3.
// The senderRowId capture is `[^]]+` — anything up to the closing
// bracket — so a row id can't accidentally swallow the `]` delimiter.
private val BURN_MARKER = Regex(
    """\[aegis:burn:(\d+):([^]]+)](.*)""",
    RegexOption.DOT_MATCHES_ALL,
)

/** Parsed components of a stored burn message. Null if the marker is
 *  malformed (treat as corrupted — the bubble still renders, but the
 *  viewer falls back to the raw body and skips the receipt). */
data class BurnEnvelope(
    val ttlSeconds: Int,
    val senderRowId: String,
    val text: String,
)

/** Split a stored burn body back into its [BurnEnvelope] parts, or null
 *  if the body isn't a well-formed burn marker. A non-numeric TTL is
 *  treated as malformed too (returns null) rather than defaulting — a
 *  corrupt TTL shouldn't silently become an indefinite reveal. */
fun parseBurnEnvelope(body: String): BurnEnvelope? =
    BURN_MARKER.matchEntire(body)?.let { m ->
        val ttl = m.groupValues[1].toIntOrNull() ?: return null
        BurnEnvelope(
            ttlSeconds = ttl,
            senderRowId = m.groupValues[2],
            text = m.groupValues[3],
        )
    }

/**
 * Chat-row body for a burn message. Replaces the normal text bubble.
 *
 * Tap-to-reveal is wired ONLY for incoming burns ([outgoing] false):
 * the recipient opens the viewer, which is what consumes the one-shot.
 * Outgoing burns are non-clickable and read "Awaiting receipt" — the
 * sender wrote the text and the row disappears once the recipient's
 * burn-receipt comes back, so there's nothing to reveal here.
 *
 * Cyan tint outgoing / SOS-red incoming, matching the rest of the
 * chat's direction colouring. [msg] is accepted for parity with the
 * other bubble composables but only the direction + reveal callback
 * drive this surface.
 */
@Composable
fun BurnBubble(
    msg: Message,
    outgoing: Boolean,
    onReveal: () -> Unit,
) {
    val tint = if (outgoing) AegisCyan else AegisSOS
    // clickable only when incoming — opening the viewer is the act that
    // burns the message, so the sender's own row must not trigger it.
    Surface(
        color = AegisPanel,
        shape = CutCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, tint),
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clickable(enabled = !outgoing, onClick = onReveal),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            app.aether.aegis.ui.components.AegisIcon(
                icon = app.aether.aegis.ui.components.AegisIcons.Burn,
                contentDescription = "burn after reading",
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    stringResource(R.string.chat_burn_after_reading),
                    color = tint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (outgoing) "Awaiting receipt"
                    else "Tap to reveal — once.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * TTL picker — three preset windows. 5 s for orders read at a glance,
 * 30 s for sentences, 0 = "unlimited until close" for longer briefs.
 * 0 maps to "no countdown, user dismisses manually".
 */
@Composable
fun BurnTtlPickerDialog(
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = AegisPanel,
            shape = CutCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AegisCyan),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.chat_burn_after_reading),
                    color = AegisCyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.burn_u_i_recipient_sees_a_fire) +
                        "view; the message is wiped from both devices on close.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(14.dp))
                BurnTtlOption("5 seconds", "Glance — orders, codes.", 5, onPick)
                BurnTtlOption("30 seconds", "Read a sentence or two.", 30, onPick)
                BurnTtlOption("Until close", "No timer — closes when dismissed.", 0, onPick)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.secure_notes_cancel)) }
                }
            }
        }
    }
}

/** One tappable row in the TTL picker. Tapping reports [ttl] seconds
 *  (0 = until-close) back through [onPick]; the dialog closes from the
 *  caller's handler, not here. */
@Composable
private fun BurnTtlOption(
    label: String,
    subtitle: String,
    ttl: Int,
    onPick: (Int) -> Unit,
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(ttl) }
            .padding(vertical = 6.dp),
    ) {
        Column {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Full-screen burn viewer. Sets `FLAG_SECURE` on the Dialog window the
 * moment it's composed so the OS skips this surface for screenshots
 * and the Recents thumbnail. Countdown fires [onClose] when the timer
 * hits zero; an explicit Done button does the same.
 *
 * The TTL-zero case ("unlimited until close") just shows the body
 * with a Done button — no progress bar, no countdown.
 */
@Composable
fun BurnViewerDialog(
    text: String,
    ttlSeconds: Int,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        // FLAG_SECURE. Drop it on the actual
        // dialog window, not the parent — the dialog renders into its
        // own window and that's the one the screen-capture pipeline
        // sees. SideEffect runs after composition so the LocalView is
        // bound to the dialog window.
        val view = LocalView.current
        DisposableEffect(view) {
            val dialogWindow = (view.parent as? DialogWindowProvider)?.window
            dialogWindow?.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
            onDispose { /* dialog tears down with the window */ }
        }

        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    app.aether.aegis.ui.components.AegisIcon(
                        icon = app.aether.aegis.ui.components.AegisIcons.Burn,
                        contentDescription = "burn after reading",
                        tint = AegisSOS,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.chat_burn_after_reading),
                        color = AegisSOS,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.tutorial_done), color = AegisCyan)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (ttlSeconds > 0) {
                    BurnCountdown(ttlSeconds = ttlSeconds, onElapsed = onClose)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AegisBorder, CutCornerShape(12.dp))
                        .background(AegisPanel, CutCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Text(
                        text,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.burn_u_i_no_screenshots_no_copy),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/**
 * Countdown bar for the burn viewer. Only used for a positive TTL — the
 * "until close" case (ttl 0) skips this composable entirely.
 *
 * Fires [onElapsed] exactly once, when the timer reaches zero, to close
 * the viewer and trigger the burn. Keyed on `Unit` so the loop starts
 * once on first composition and is NOT restarted by recomposition.
 */
@Composable
private fun BurnCountdown(ttlSeconds: Int, onElapsed: () -> Unit) {
    // Tenth-of-a-second ticks so the bar moves smoothly. Compose state
    // is the live count of ticks remaining; LaunchedEffect drives the
    // delay loop and fires onElapsed exactly once.
    val totalTicks = ttlSeconds * 10
    var ticks by remember { mutableIntStateOf(totalTicks) }
    LaunchedEffect(Unit) {
        while (ticks > 0) {
            delay(100)
            ticks -= 1
        }
        onElapsed()
    }
    // Round UP to whole seconds so the digit reads the user-facing TTL
    // (e.g. 5s) on the first frame and only hits 0 as the bar empties.
    val secondsLeft = (ticks + 9) / 10
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${secondsLeft}s",
                color = AegisCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(8.dp))
            app.aether.aegis.ui.components.HexProgressBar(
                progress = ticks.toFloat() / totalTicks.toFloat(),
                color = AegisCyan,
                trackColor = AegisBorder,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

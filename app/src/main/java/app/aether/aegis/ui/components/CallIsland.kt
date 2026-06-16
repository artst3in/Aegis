package app.aether.aegis.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.aether.aegis.call.CallMediaType
import app.aether.aegis.call.CallState
import app.aether.aegis.call.CallStore
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisPanel
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * WhatsApp-style floating call island. Pinned to the
 * top of the app frame whenever there's an active call AND the user
 * has navigated away from the call screen itself. One-tap returns to
 * the call. Re-uses the in-process [CallStore]; no overlay window
 * permission needed because the island lives inside the same Compose
 * tree as everything else.
 *
 * Hidden when:
 *   - no call is active
 *   - the user IS on the call screen (route starts with "call/")
 *   - PiP mode is on (the OS PiP window IS the call surface)
 */
@Composable
fun CallIsland(navController: NavController) {
    val active by CallStore.active.collectAsState()
    val inPip by CallStore.inPip.collectAsState()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val onCallScreen = route.startsWith("call/")
    // Stealth calls (remote LIVE_CAM stream to a target the user no
    // longer holds) must NOT surface any visual indication — that's
    // the whole point. The CallStore entry still exists so CallManager
    // can route the WebRTC signaling; the island just hides itself.
    val visible = active != null && active?.stealth != true && !onCallScreen && !inPip

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        // Snapshot the call once visible; null again => animate out via the
        // outer AnimatedVisibility (don't crash mid-exit).
        val call = active ?: return@AnimatedVisibility
        // Ticking clock driving the live duration. Re-keyed on peer/connect
        // time so a new call restarts the timer cleanly. 500 ms keeps the
        // seconds digit fresh without a per-frame recomposition.
        var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(call.peerPubkey, call.connectedAt) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(500)
            }
        }
        val connectedAt = call.connectedAt
        // Three phases: ended, pre-connect (calling/incoming), and the live
        // m:ss counter. connectedAt is null until the call actually connects,
        // so the elapsed maths only runs once it's set.
        val durationLabel = when {
            call.state == CallState.Ended -> "ended"
            connectedAt == null           ->
                if (call.outgoing) stringResource(R.string.call_calling) else "Incoming…"
            else -> {
                // coerceAtLeast(0): guard against a connectedAt slightly in the
                // future from clock skew producing a negative duration.
                val secs = ((nowMs - connectedAt) / 1000L).coerceAtLeast(0L)
                "%d:%02d".format(secs / 60, secs % 60)
            }
        }

        Surface(
            color = AegisPanel,
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, AegisCyan),
            modifier = Modifier
                .fillMaxWidth()
                // On a sub-route there's no AegisHeader above to provide the
                // status-bar inset, and the host Scaffold no longer pads its
                // content (contentWindowInsets=0), so keep the island clear
                // of the status bar ourselves. On a tab route it already
                // sits below the header. This lives inside AnimatedVisibility
                // so it costs nothing when no call is active.
                .then(
                    if (route in setOf("chats", "map", "sos", "security", "settings"))
                        Modifier
                    else
                        Modifier.statusBarsPadding(),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable {
                    // URL-encode pubkey + name because they're interpolated
                    // into a nav route path (both can contain '/', '+', etc.).
                    val pk = java.net.URLEncoder.encode(call.peerPubkey, "UTF-8")
                    val name = java.net.URLEncoder.encode(call.peerDisplayName, "UTF-8")
                    // launchSingleTop: tapping the island while a stale call
                    // route is on the stack reuses it instead of stacking a
                    // second call screen.
                    navController.navigate("call/$pk/$name/${call.media.name}") {
                        launchSingleTop = true
                    }
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AegisCyan),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        call.peerDisplayName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        if (call.media == CallMediaType.Video) "Video · $durationLabel"
                        else "Voice · $durationLabel",
                        color = AegisCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    stringResource(R.string.call_island_tap_return),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

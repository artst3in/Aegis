package app.aether.aegis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisOnline
import app.aether.aegis.ui.theme.AegisWarning

/**
 * Tiny status dot — 8dp circle, green / orange / grey for online /
 * away / offline. Online state gets a soft glow to read as "alive".
 */
enum class PeerStatus { Online, Away, Offline }

/** Online window — sender was in the foreground this recently. */
private const val ONLINE_WINDOW_MS: Long = 5L * 60_000L

/** Away window — sender's background status ticker fires every
 *  5 minutes (STATUS_BROADCAST_MIN_INTERVAL_MS in ProtocolService).
 *  30 minutes covers six consecutive missed pings, which is the
 *  point at which "phone in pocket, app alive" stops being a
 *  plausible explanation and the peer is likely genuinely unreachable
 *  (airplane mode, dead battery, force-stopped). This matches the
 *  threshold the radar dock has been using for months and replaces
 *  an earlier Long.MAX_VALUE that broke Offline entirely — once
 *  seen a peer never expired. */
private const val AWAY_WINDOW_MS: Long = 30L * 60_000L

/**
 * Central presence computation for the presence states. Every
 * screen that paints an online/away/offline indicator should call
 * this with the receiver-local age of each timestamp.
 *
 * Args are ages in ms (now − stored ts). Negative ages clamp to 0
 * to guard against clock skew where the sender's clock is briefly
 * ahead of ours.
 */
fun peerStatusFromAges(inAppAgeMs: Long, packetAgeMs: Long?): PeerStatus {
    val inApp = inAppAgeMs.coerceAtLeast(0)
    val packet = (packetAgeMs ?: Long.MAX_VALUE).coerceAtLeast(0)
    return when {
        inApp < ONLINE_WINDOW_MS -> PeerStatus.Online
        packet < AWAY_WINDOW_MS -> PeerStatus.Away
        else -> PeerStatus.Offline
    }
}

/** Convenience overload — pulls the ages from a MemberStatusEntity
 *  + a now-ms snapshot. Returns Offline when status is null. A null
 *  lastPacketMs (no status ping recorded yet) passes through as a
 *  null packet age, which [peerStatusFromAges] treats as "no
 *  background heartbeat" → eligible for Offline. */
fun peerStatusFor(
    status: app.aether.aegis.data.MemberStatusEntity?,
    nowMs: Long,
    isSelf: Boolean = false,
): PeerStatus {
    if (isSelf) return PeerStatus.Online
    if (status == null) return PeerStatus.Offline
    val inAppAge = nowMs - status.lastActive
    val packetAge = status.lastPacketMs?.let { nowMs - it }
    return peerStatusFromAges(inAppAge, packetAge)
}

@Composable
fun StatusDot(status: PeerStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        PeerStatus.Online -> AegisOnline
        PeerStatus.Away -> AegisWarning
        PeerStatus.Offline -> AegisOnSurfaceDim
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .let {
                if (status == PeerStatus.Online) it.shadow(
                    elevation = 4.dp,
                    shape = CircleShape,
                    spotColor = color,
                    ambientColor = color,
                ) else it
            }
            .clip(CircleShape)
            .background(color),
    )
}

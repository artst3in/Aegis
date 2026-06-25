package app.aether.aegis.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.aegis.achievements.Achievement
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Verified-security badge list. Renders the
 * badge catalogue as rows: a lit cyan disc + title + earned date for
 * earned badges, a dim locked disc + the one-line "how to earn" for
 * unearned ones.
 *
 * Used for the user's own badges in the profile ([showUnearned] = true,
 * so the locked ones advertise how to get them) and for a trusted
 * contact's badges in the contact detail ([showUnearned] = false —
 * only what they've actually earned). Duress/decoy blanking is the
 * caller's responsibility (the profile simply doesn't render this under
 * a duress unlock — blank, never fake).
 */
@Composable
fun AchievementBadgeList(
    earnedIds: Set<String>,
    showUnearned: Boolean,
    modifier: Modifier = Modifier,
    earnedAt: (Achievement) -> Long? = { null },
) {
    // Full catalogue when advertising how-to-earn; otherwise only earned
    // badges. Iteration order is the enum's declaration order (a stable,
    // curated sequence), so no extra sort is needed.
    val items = Achievement.entries.filter { showUnearned || it.id in earnedIds }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { a ->
            val earned = a.id in earnedIds
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(36.dp).clip(CircleShape),
                    color = if (earned) AegisCyan.copy(alpha = 0.22f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            if (earned) "✓" else "🔒",
                            color = if (earned) AegisCyan else AegisOnSurfaceDim,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        a.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        // Earned title is full-contrast; locked is dimmed.
                        color = if (earned) MaterialTheme.colorScheme.onSurface
                        else AegisOnSurfaceDim,
                    )
                    Text(
                        // Subtitle does double duty: an earned date when held,
                        // otherwise the one-line "how to earn" hint.
                        if (earned) earnedLabel(earnedAt(a)) else a.howToEarn,
                        fontSize = 11.sp,
                        color = AegisOnSurfaceDim,
                    )
                }
            }
        }
    }
}

/**
 * Subtitle for an earned badge. Falls back to a bare "Earned" when no
 * timestamp is known (ts null or ≤ 0) — the badge is genuinely earned,
 * we just don't have a date to show, so never invent one.
 */
private fun earnedLabel(ts: Long?): String {
    if (ts == null || ts <= 0L) return "Earned"
    // Device locale + timezone: a personal-history date the user reads, not
    // a wire value, so local formatting is correct here.
    val d = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ts))
    return "Earned $d"
}

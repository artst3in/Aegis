package app.aether.aegis.ui.components

import app.aether.aegis.profile.ProfileRegistry
import app.aether.aegis.profile.ProfileRoot
import app.aether.aegis.ui.theme.AegisCyan
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 2 dp coloured bar at the top of the app.
 * The user needs a glanceable cue to know WHICH profile they're
 * currently operating in, so a casual look doesn't end with a
 * message sent from the wrong family identity.
 *
 * Visibility rule: when only one profile exists (the default), the
 * strip is invisible. This matters for plausible deniability —
 * a shoulder-surfer who's never been told the multi-profile feature
 * exists shouldn't be tipped off by a UI element.
 *
 * Colour derivation: SHA-1 the profile id (deterministic), pull the
 * first byte, map to one of 6 LunaGlass-palette hues. The DEFAULT
 * profile, if multi-profile is in play, gets the canonical cyan;
 * every other profile gets a hash-derived hue distinct from cyan.
 * Future Phase 2b iteration could let the user pick their own
 * colour from a small palette.
 */
@Composable
fun ProfileIndicatorStrip() {
    val context = LocalContext.current
    val activeId = remember { ProfileRegistry.get(context).activeProfileId }
    val profileCount = remember { ProfileRegistry.get(context).listProfiles().size }
    if (profileCount <= 1) return
    val color = remember(activeId) { colourFor(activeId) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(color),
    )
}

private fun colourFor(profileId: String): Color {
    if (profileId == ProfileRoot.DEFAULT_PROFILE_ID) return AegisCyan  // brand cyan token
    val md = java.security.MessageDigest.getInstance("SHA-1")
    val hash = md.digest(profileId.toByteArray(Charsets.UTF_8))
    val idx = (hash[0].toInt() and 0xff) % PALETTE.size
    return PALETTE[idx]
}

// Distinct from AegisCyan (the default-profile colour) so the eye
// catches the difference. All chosen for legibility against the
// dark LunaGlass background.
private val PALETTE = listOf(
    Color(0xFFFFC107),  // amber
    Color(0xFFFF6B6B),  // coral
    Color(0xFF8B5CF6),  // violet
    Color(0xFF10B981),  // emerald
    Color(0xFFF97316),  // orange
    Color(0xFFEC4899),  // pink
)

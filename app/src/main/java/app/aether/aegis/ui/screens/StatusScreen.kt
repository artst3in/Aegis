package app.aether.aegis.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// FamilyStatusPanel + BatteryBar + NetworkRow lived here for the
// old Status tab + SimpleX detail surface. Both surfaces are
// gone (Status was folded into Radar;
// SimpleX detail folded into Diagnostics). AvatarBubble stays
// because Settings → Profile card uses it as the avatar widget.

/** Self-profile avatar bubble (Settings → Profile card). Thin wrapper
 *  over the canonical [app.aether.aegis.ui.components.AegisAvatar] so the
 *  owner's own avatar uses the SAME metal-frame + engraved-plate renderer
 *  as every peer surface. [tier] is the owner's own shield tier (None →
 *  visible cyan, handled by AegisAvatar). */
@Composable
internal fun AvatarBubble(
    name: String,
    avatarPath: String?,
    tier: app.aether.aegis.admin.ShieldTier? = null,
) {
    app.aether.aegis.ui.components.AegisAvatar(
        size = 48.dp,
        tier = tier,
        initial = name.firstOrNull()?.uppercase() ?: "?",
        avatarPath = avatarPath,
        // Self avatar — the Cyan crown shines in the user's OWN selected
        // iridescence (local crown-style pref), not a peer's announced style.
        isSelf = true,
    )
}

package app.aether.aegis.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Circular group avatar.
 *
 * Renders the group's shared image from [avatarPath] via Coil,
 * falling back to the legacy "#" glyph in a secondary-tinted circle
 * when the path is null or the file is missing/empty. This is the
 * *group's* image, not any member's identity — it reveals no
 * individual (Part 0 keeps member identity incognito).
 *
 * Shared by the chat-list group row, the GroupChatScreen header, and
 * the GroupMembersScreen image card so all three stay visually
 * consistent and the glyph fallback lives in exactly one place.
 *
 * The on-wire copy is EXIF-stripped + downscaled in the transport
 * before broadcast; the file this loads is the local sanitised copy
 * (set locally) or the decoded inbound copy.
 */
@Composable
fun GroupAvatar(
    avatarPath: String?,
    size: Dp = 48.dp,
    glyphFontSize: TextUnit = 22.sp,
) {
    val file = avatarPath
        ?.let { java.io.File(it) }
        ?.takeIf { it.exists() && it.length() > 0L }
    Surface(
        modifier = Modifier.size(size).clip(CircleShape),
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f),
    ) {
        if (file != null) {
            AsyncImage(
                model = file,
                contentDescription = stringResource(R.string.a11y_group_avatar),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "#",
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = glyphFontSize,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

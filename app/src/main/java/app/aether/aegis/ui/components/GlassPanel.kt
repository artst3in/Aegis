package app.aether.aegis.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyanGlow
import app.aether.aegis.ui.theme.AegisPanel

/**
 * Glass panel — the LunaGlass building block. Dark fill, 1px border, and
 * 12dp CHAMFERED (cut) corners that echo the hex/angular LunaGlass geometry —
 * was soft RoundedCornerShape, which read as "curved corners everywhere" and
 * clashed with the hex language. When [glow] is true, a cyan inner highlight +
 * outer shadow lifts the panel from the background.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .let {
                if (glow) it.shadow(
                    elevation = 8.dp,
                    shape = CutCornerShape(12.dp),
                    spotColor = AegisCyanGlow,
                    ambientColor = AegisCyanGlow,
                ) else it
            },
        shape = CutCornerShape(12.dp),
        color = AegisPanel,
        border = BorderStroke(1.dp, AegisBorder),
    ) {
        content()
    }
}

package app.aether.aegis.groups

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The empty-state surface for the Groups tab when the group
 * module is disabled. Fresh install lands
 * here: a single "Enable Group Chat" affordance plus the
 * attack-surface warning copy. No groups, no list affordances,
 * nothing else.
 *
 * Lives in `:feature:groups` so the module's own visible state
 * is module-owned. The host (app) only ever invokes this and
 * passes through the enable callback — the styling, copy, and
 * structure are the module's concern.
 *
 * Cyan is hard-coded as `0xFF00BCD4` rather than pulled from a
 * theme token because the boundary forbids importing
 * `app.aether.aegis.ui.theme.AegisCyan`. The hex matches the
 * theme value bit-for-bit, so the visual is identical.
 */
@Composable
fun GroupModuleDisabledCard(
    onEnableClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brandCyan = Color(0xFF00BCD4)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "GROUP CHAT IS OFF",
                color = brandCyan,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "Aegis treats group code as additional attack surface " +
                    "and ships it off by default. Your 1:1 chats and " +
                    "safety features are unaffected.",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Enable when you want to participate in a group. " +
                    "You can disable again at any time.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            // Explicit cut-corner shape: the :feature:groups module can't
            // depend on the app module's AegisOutlinedButton wrapper (the
            // dependency runs app → feature, not the reverse), so it sets the
            // LunaGlass facet directly. 8 dp matches AegisButtonShape.
            OutlinedButton(
                onClick = onEnableClick,
                modifier = Modifier.fillMaxWidth(),
                shape = CutCornerShape(8.dp),
            ) {
                Text("Enable Group Chat", color = brandCyan)
            }
        }
    }
}

package app.aether.aegis.ui.components

import app.aether.aegis.AegisApp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * THE one persistent top bar — a SINGLE [AegisBarFrame] call site used for every
 * normal route, mounted ONCE in MainActivity's content column (outside the
 * NavHost). Its left cell shows the AEGIS wordmark on tab routes and the
 * back-arrow + screen title on sub-routes (fed via [AegisBarSlot]); its actions
 * are the sentinel chip on tabs or the per-screen actions on sub-routes. Because
 * it's one instance, the chrome — background, divider, and especially the
 * ActionCluster — is NOT recreated when crossing the tab↔sub boundary; only the
 * left/actions content swaps. (Previously the tab header lived in the Scaffold
 * topBar and sub-screens drew their own bar, so the first visit to any sub-screen
 * tore down one ActionCluster and built another — user report.)
 */
@Composable
fun AegisPersistentBar(
    navController: NavController,
    isTabRoute: Boolean,
    subContent: AegisBarContent?,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sentinelEngine = androidx.compose.runtime.remember(ctx) {
        app.aether.aegis.sentinel.SentinelState.engine(ctx)
    }
    val sentinelStage by sentinelEngine.stage.collectAsState()

    AegisBarFrame(
        actions = {
            if (isTabRoute) {
                // Sentinel-armed chip — lit when the cascade is armed; tap →
                // SonarScreen. Sits just left of the shared cluster.
                if (sentinelStage != app.aether.aegis.sentinel.SentinelStage.OFF) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(
                            app.aether.aegis.R.drawable.ic_aegis_security,
                        ),
                        contentDescription = stringResource(R.string.header_sentinel_armed),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(AegisCyan),
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { navController.navigate("settings/sonar") },
                    )
                }
            } else {
                subContent?.actions?.invoke(this)
            }
        },
    ) {
        if (isTabRoute) {
            // AEGIS wordmark — same in real + duress modes (the attacker must
            // not be able to tell which mode they're in; it matches the lock
            // screen).
            Text(
                stringResource(R.string.tutorial_aegis),
                color = AegisCyan,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                style = MaterialTheme.typography.titleLarge.copy(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = AegisCyan.copy(alpha = 0.5f),
                        blurRadius = 12f,
                    ),
                ),
                modifier = Modifier.padding(start = 16.dp).crownShimmer(),
            )
        } else if (subContent != null) {
            // Sub-route: back-arrow + screen title (titleMedium so a 2-line
            // title fits the fixed band).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                subContent.navigationIcon()
                Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                    ) {
                        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.titleMedium) {
                            subContent.title()
                        }
                    }
                }
            }
        } else {
            // Sub-route whose content hasn't published yet — the one-frame gap
            // on first land. Show JUST the back arrow so the bar stays steady
            // (no stale previous title); the real title appears next frame when
            // the screen publishes. The bar/cluster never leave.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
                    AegisIcon(AegisIcons.Back, "back")
                }
            }
        }
    }
}

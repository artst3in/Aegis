package app.aether.aegis.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.AegisApp
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Gate the body of a security-feature settings screen behind App
 * PIN existing.
 *
 * Every leaf node in the skill tree (Mugshot, Canary, Geofence, SIM
 * Watch, Vault PIN, App Duress, etc.) declares App PIN as its
 * unlock parent. The skill
 * tree visual respects that gating — locked hexes dim and ignore
 * taps — but the user can still bypass via Settings → \<feature\>
 * deep-links or the legacy Settings list. This wrapper closes that
 * bypass: when App PIN isn't set, [content] is replaced with a
 * one-card explainer and a button to set the PIN.
 *
 * Use at the top of each guarded screen's Composable body:
 *
 *     PinGuardedContent(navController) { /* normal screen body */ }
 *
 * LockSettingsScreen (where App PIN itself is set) does NOT use
 * this — that'd be a chicken-and-egg lockout.
 */
@Composable
fun PinGuardedContent(
    navController: NavController,
    featureLabel: String = "this feature",
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val hasPin = remember { AegisApp.instance.lockState.store.hasPin }
    if (hasPin) {
        content()
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            stringResource(R.string.pin_guard_required),
            color = AegisCyan,
            fontSize = 13.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "$featureLabel is the trunk-gated branch of the skill tree. " +
                "It needs the App PIN set before it can do anything — " +
                "without one, an attacker who picks up the phone could " +
                "open Aegis and disable it directly, which would defeat " +
                "the feature's whole purpose.",
            color = AegisOnSurfaceDim,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { navController.navigate("settings/lock") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.pin_guard_set_pin))
        }
    }
}

package app.aether.aegis.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnline
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Dedicated screen for the two privileged device-management tiers Aegis
 * can hold. Split out of Lock settings (where the enrollment button used
 * to live) so it's a first-class opsec node with its own route — the
 * Security tab's "Device Admin" / "Device Owner" rows and the skill-tree
 * nodes point here, not at the app-PIN screen.
 *
 *  - **Device Admin** — enrollable in-app via the OS confirmation dialog
 *    ([AdminGate.enrollIntent]). Lets remote LOCATE drop the device to
 *    the lock screen, and gates the duress/keyguard helpers.
 *  - **Device Owner** — strictly stronger, but can ONLY be granted over
 *    ADB after a factory reset (`dpm set-device-owner`). Not toggleable
 *    in-app, so this screen explains the one-time command rather than
 *    offering a button. Granting Owner also activates Admin for free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceAdminScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Re-probe on every resume — the user may flip admin in the OS
    // confirmation dialog (or via ADB for Owner) and come back; the
    // status lines must reflect that without a manual refresh.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var adminActive by remember {
        mutableStateOf(app.aether.aegis.admin.AdminGate.isActive(context))
    }
    var ownerActive by remember {
        mutableStateOf(app.aether.aegis.admin.DeviceOwnerStatus.isActive(context))
    }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                adminActive = app.aether.aegis.admin.AdminGate.isActive(context)
                ownerActive = app.aether.aegis.admin.DeviceOwnerStatus.isActive(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.security_device_admin), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(app.aether.aegis.ui.components.AegisIcons.Back, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Privileged device-management roles. These let Aegis recover " +
                    "or lock down the phone after a theft — they are the " +
                    "difference between \"I can see where it is\" and \"I can " +
                    "lock it from my other phone.\"",
                color = AegisOnSurfaceDim,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // ---- Device Admin (enrollable in-app) ----
            // Status text is green (AegisOnline) when active, error-red when
            // off — off is framed as a security shortfall, not a neutral
            // default, because without it remote Locate can't lock the phone.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.security_device_admin), fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (adminActive)
                            "✓ Active — remote Locate can lock this device's screen."
                        else
                            "Off — remote Locate can find the phone but can't lock " +
                                "the screen. Enable to allow stolen-device recovery.",
                        color = if (adminActive) AegisOnline
                                else MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                    // Enrollment button only when inactive — there's no
                    // in-app DISABLE (de-admin happens in OS settings), so
                    // once active the button has nothing to do and is hidden.
                    if (!adminActive) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(onClick = {
                            // Fire the OS device-admin confirmation dialog.
                            // The `explanation` is what the system shows the
                            // user as the justification. runCatching guards
                            // the rare no-activity-to-handle case so a
                            // missing handler can't crash the screen; the
                            // resume re-probe will reflect the result either
                            // way.
                            val intent = app.aether.aegis.admin.AdminGate.enrollIntent(
                                context,
                                explanation = "Aegis uses this to lock the screen " +
                                    "when you ask it to — for example, from your " +
                                    "other phone after a theft.",
                            )
                            runCatching { context.startActivity(intent) }
                        }) { Text(stringResource(R.string.lock_enable_device_admin)) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ---- Device Owner (ADB-only — explain, don't offer a button) ----
            // No enroll button here BY DESIGN: Device Owner can't be granted
            // from inside the app (Android only allows it pre-provisioning,
            // over ADB, on a clean device). So when inactive we render the
            // exact `dpm set-device-owner` command for the user to run
            // instead of a button that couldn't work.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.diagnostics_device_owner), fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (ownerActive)
                            "✓ Provisioned — silent install, keyguard lockdown, and " +
                                "evict-key-on-lock are all available."
                        else
                            "Not provisioned. Device Owner is the strongest role: it " +
                                "unlocks silent updates, full lock-screen/status-bar " +
                                "lockdown, and credential-key eviction on lock. It " +
                                "can't be granted from inside the app — only once, " +
                                "over ADB, on a freshly factory-reset phone with no " +
                                "other accounts:",
                        color = if (ownerActive) AegisOnline
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    if (!ownerActive) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "adb shell dpm set-device-owner \\\n" +
                                "  app.aether.aegis/.admin.AegisAdminReceiver",
                            color = AegisCyan,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Granting Owner this way also activates Device Admin " +
                                "automatically — you don't need to do both.",
                            color = AegisOnSurfaceDim,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

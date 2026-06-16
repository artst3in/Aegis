package app.aether.aegis.ui.screens

import app.aether.aegis.AegisApp
import app.aether.aegis.MainActivity
import app.aether.aegis.util.Attachments
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import app.aether.aegis.ui.components.AegisIcon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Edit your own profile. Two entry points:
 *   1. First-launch onboarding (no back button, must save to continue).
 *   2. Settings → Edit profile (back button, can cancel).
 *
 * Saves to ProfileStore locally. Real identity is shared with
 * elevated contacts via the Aether [aegis:identity] overlay
 * (SimpleXTransport.sendIdentityEnvelope), NOT via the SimpleX
 * core profile. The setProfile() method was deleted (Origin #19).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(navController: NavController, isOnboarding: Boolean) {
    val context = LocalContext.current
    val store = remember { AegisApp.instance.profileStore }
    var displayName by remember { mutableStateOf(store.displayName) }
    var bio by remember { mutableStateOf(store.bio) }
    var avatarPath by remember { mutableStateOf(store.avatarPath) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Whole screen scrolls so the keyboard never covers the active field —
    // imePadding pushes the layout up, and the scroll handles any
    // overflow above the avatar.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(
            title = { Text(if (isOnboarding) stringResource(R.string.profile_welcome) else stringResource(R.string.profile_title)) },
            navigationIcon = {
                if (!isOnboarding) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(app.aether.aegis.ui.components.AegisIcons.Back, stringResource(R.string.action_back))
                    }
                }
            },
        )

        if (isOnboarding) {
            // Cyan welcome illustration above the prompt —
            // first-run onboarding
            // step 1. The other three steps (permissions / first
            // contact / done) don't yet exist as
            // discrete screens in Aegis — today's onboarding is a
            // single profile-setup form. Step 1 lands here and
            // the rest hook in if/when the 4-step flow ships.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                app.aether.aegis.ui.components.CyanOnboardImage(step = 1, size = 200.dp)
            }
            Text(
                stringResource(R.string.profile_set_your_name_and) +
                    "what you put here — you can change it later in Settings.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }

        // Avatar — the full canonical AegisAvatar (metal frame + your earned
        // tier + engraved monogram), NOT a bare cropped photo, so your profile
        // previews exactly what your contacts see. Tap opens the photo picker.
        app.aether.aegis.ui.components.AegisAvatar(
            size = 96.dp,
            tier = app.aether.aegis.admin.ShieldTierEngine.currentTier(context),
            initial = displayName.firstOrNull()?.uppercase()?.toString() ?: "?",
            avatarPath = avatarPath,
            isSelf = true,
            modifier = Modifier
                .padding(top = 12.dp)
                .align(Alignment.CenterHorizontally),
            onClick = {
                (context as? MainActivity)?.pickAttachment("image/*") { uri ->
                    scope.launch {
                        val local = withContext(Dispatchers.IO) {
                            Attachments.import(context, uri)
                        } ?: return@launch
                        // Avatar lives under the active profile root so it
                        // travels with the profile, not the package filesDir.
                        val ext = local.mime.substringAfter('/').take(8)
                        val target = AegisApp.instance.profileRoot.avatarFile(ext)
                        File(local.path).copyTo(target, overwrite = true)
                        File(local.path).delete()
                        avatarPath = target.absolutePath
                        // Persist immediately so every other screen picks up
                        // the new avatar without needing the Save round-trip.
                        store.avatarPath = target.absolutePath
                    }
                }
            },
        )
        Text(
            stringResource(R.string.profile_tap_to_change_photo),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(R.string.profile_display_name)) },
            placeholder = { Text(stringResource(R.string.profile_your_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 4.dp),
        )
        OutlinedTextField(
            value = bio,
            onValueChange = { bio = it },
            label = { Text(stringResource(R.string.profile_bio)) },
            placeholder = { Text(stringResource(R.string.profile_bio_hint)) },
            modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp),
            maxLines = 4,
        )

        Button(
            enabled = !busy && displayName.trim().isNotEmpty(),
            onClick = {
                busy = true
                scope.launch {
                    store.displayName = displayName.trim()
                    store.bio = bio.trim()
                    store.avatarPath = avatarPath
                    store.onboarded = true

                    // Aegis Protocol: do NOT
                    // push the real profile to SimpleX. The main SimpleX
                    // profile is vestigial (a random handle) and every
                    // connection is incognito — your real name must never
                    // touch the transport. Instead, real identity rides the
                    // [aegis:identity] overlay, re-sent here to every
                    // currently-elevated (Trusted/Emergency) contact so their
                    // view of your name/avatar/bio stays current.
                    AegisApp.instance.repository.revealIdentityToElevatedContacts()

                    Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    if (isOnboarding) {
                        navController.navigate("chats") {
                            popUpTo(0) { inclusive = true }
                        }
                    } else {
                        navController.popBackStack()
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(if (isOnboarding) stringResource(R.string.profile_get_started) else if (busy) stringResource(R.string.profile_saving) else stringResource(R.string.action_save))
        }

        // Achievements — your own
        // verified-security badges. Hidden during onboarding. Under a
        // duress unlock, show a stable random DECOY set rather than the
        // real badges (reversing the earlier "blank":
        // a blank panel is itself a tell, and the catalogue isn't
        // secret). The duress path never reads the real store.
        if (!isOnboarding) {
            val inDuress = AegisApp.instance.lockState.inDuressMode
            val achStore = remember {
                app.aether.aegis.achievements.AchievementStore(context)
            }
            val earnedIds = if (inDuress) {
                app.aether.aegis.decoy.DecoyBadges.forSeed("self")
            } else {
                achStore.earnedIds()
            }
            Text(
                stringResource(R.string.profile_achievements),
                color = app.aether.aegis.ui.theme.AegisCyan,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 2.dp),
            )
            Text(
                stringResource(R.string.profile_proof_your_safety_net) +
                    "first time that feature fires for real.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            )
            app.aether.aegis.ui.components.AchievementBadgeList(
                earnedIds = earnedIds,
                showUnearned = true,
                // No real timestamps under duress — just "Earned".
                earnedAt = { if (inDuress) null else achStore.earnedAt(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 0.dp),
            )
        }
    }
}

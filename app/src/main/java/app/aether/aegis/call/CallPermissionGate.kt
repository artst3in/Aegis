package app.aether.aegis.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavController

/**
 * Compose-side permission gate for the call surface.
 *
 * Why this exists: CallScreen mounts a WebView that invokes
 * getUserMedia({audio[, video]}). WebChromeClient.onPermissionRequest()
 * grants the WebView side, but the underlying Android runtime grants for
 * RECORD_AUDIO / CAMERA must already be in place — if the user revoked
 * them in system settings (or denied them during onboarding) getUserMedia
 * silently fails and the user sits on "Preparing…" forever.
 *
 * Behaviour: returns a `startCall(peer, name, video)` function that
 *  1. checks runtime grants up front,
 *  2. either navigates directly (granted) or fires a permission request
 *     and navigates on success,
 *  3. on denial, surfaces a blocking dialog explaining what's missing,
 *     with a one-tap shortcut to the app's permission page.
 */
@Composable
fun rememberCallStarter(navController: NavController): (String, String, Boolean) -> Unit {
    val context = LocalContext.current
    // The call we're waiting on a permission result for. Held across the
    // system dialog round-trip because the result callback has no args.
    var pending by remember { mutableStateOf<CallRequest?>(null) }
    // Non-null drives the "permission denied" explainer dialog below.
    var deniedFor by remember { mutableStateOf<CallRequest?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Ignore the result map and re-check actual grants — covers the
        // "Only this time" / partial-grant cases uniformly.
        val req = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (missingCallPermissions(context, req.video).isEmpty()) {
            navigateToCall(navController, req)
        } else {
            // Still missing something → surface the explainer instead of
            // navigating into a call that would hang on "Preparing…".
            deniedFor = req
        }
    }

    deniedFor?.let { req ->
        val needsCamera = req.video
        AlertDialog(
            onDismissRequest = { deniedFor = null },
            title = { Text("Permission needed") },
            text = {
                Text(
                    if (needsCamera) {
                        "Aegis needs the microphone and camera to place a video call. " +
                            "Grant them in app settings and try again."
                    } else {
                        "Aegis needs the microphone to place a call. " +
                            "Grant it in app settings and try again."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deniedFor = null
                    openAppDetailsSettings(context)
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { deniedFor = null }) { Text("Cancel") }
            },
        )
    }

    // Returned starter: the only thing call entry points call. Fast-path
    // navigates straight in when grants are already held; otherwise stashes
    // the request and fires the runtime prompt.
    return { peer, name, video ->
        val req = CallRequest(peer, name, video)
        val missing = missingCallPermissions(context, video)
        if (missing.isEmpty()) {
            navigateToCall(navController, req)
        } else {
            pending = req
            launcher.launch(missing.toTypedArray())
        }
    }
}

/** One pending call's parameters, carried through the permission round-trip. */
private data class CallRequest(val peer: String, val name: String, val video: Boolean)

/**
 * Runtime permissions still missing for this call. Audio is always
 * required; camera only for video. Returns the subset NOT yet granted so
 * the caller can both decide whether to prompt and what to request.
 */
private fun missingCallPermissions(context: Context, video: Boolean): List<String> {
    val required = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (video) add(Manifest.permission.CAMERA)
    }
    return required.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }
}

/** Navigate to the call route, URL-encoding peer + name since they're
 *  interpolated into the route path. media segment is video|audio. */
private fun navigateToCall(nav: NavController, req: CallRequest) {
    val ep = java.net.URLEncoder.encode(req.peer, "UTF-8")
    val en = java.net.URLEncoder.encode(req.name, "UTF-8")
    // runCatching guards the first-frame race where the controller's graph
    // isn't set yet (cold launch from a call notification): navigate()
    // throws "Navigation graph has not been set" rather than no-op'ing.
    // Callers that MUST land the call (the incoming-call effect) wait for
    // the graph first; this is the backstop for every other entry point.
    runCatching {
        nav.navigate("call/$ep/$en/${if (req.video) "video" else "audio"}")
    }
}

/**
 * Open this app's system settings page so the user can flip a grant we
 * can't re-request in-app (e.g. permanently denied). NEW_TASK because we
 * may be launched from a non-Activity context; the launch is best-effort.
 */
private fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

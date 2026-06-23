package app.aether.aegis.ui.screens

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import app.aether.aegis.R
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import java.io.File

/**
 * Fullscreen IN-APP video player. Reached by tapping a video bubble in a 1:1
 * or group chat.
 *
 * Plays the (already-decrypted) file DIRECTLY via the platform [VideoView]:
 * MediaPlayer opens the path in-process and sniffs the container, so the
 * decrypt-scratch's ".tmp" name is irrelevant. This deliberately does NOT hand
 * the file to an external player, which was the old approach and broke two
 * ways:
 *   1. A content:// URI over a ".tmp" file resolves to application/octet-
 *      stream, and inbound video carries a WILDCARD "video" mime (subtype
 *      "star"), so the external ACTION_VIEW routinely matched no player.
 *   2. Worse, it leaked the decrypted plaintext of a SEALED attachment to a
 *      third-party app — the opposite of what sealing is for. Keeping playback
 *      in-process means the plaintext never leaves Aegis.
 *
 * Pure-black chrome like [PhotoViewerScreen] — the video is the content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoViewerScreen(
    filePath: String,
    fileName: String?,
    navController: NavController,
) {
    val file = remember(filePath) { File(filePath) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(fileName ?: stringResource(R.string.chat_video), color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(AegisIcons.Back, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (!file.exists()) {
                Text(
                    stringResource(R.string.video_viewer_not_found),
                    color = Color.White,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            // Standard transport controls (play/pause/seek);
                            // tapping the surface toggles them. Anchored to the
                            // view itself so the popup tracks it inside Compose.
                            val controller = MediaController(ctx)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            // Auto-start once buffered so playback begins
                            // without a second tap.
                            setOnPreparedListener { mp ->
                                mp.isLooping = false
                                start()
                            }
                            setVideoPath(filePath)
                        }
                    },
                    // Stop decoding + release the MediaPlayer when the viewer
                    // leaves the composition (back / nav away).
                    onRelease = { it.stopPlayback() },
                )
            }
        }
    }
}

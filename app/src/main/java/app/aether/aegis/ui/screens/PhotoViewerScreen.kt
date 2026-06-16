package app.aether.aegis.ui.screens

import app.aether.aegis.AegisApp
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import app.aether.aegis.ui.components.AegisIcon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fullscreen photo viewer. Reached by tapping an image bubble in the
 * chat. Has a Save action that copies the file into the device's public
 * Pictures/Aegis directory via MediaStore, so the system gallery and
 * other apps can see it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    filePath: String,
    fileName: String?,
    navController: NavController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(filePath) { File(filePath) }

    Scaffold(
        // Pure black + white text is the right call for a fullscreen
        // photo viewer regardless of the LunaGlass dark-cyan theme
        // elsewhere — every photo app does this (Photos, Gallery,
        // Instagram, WhatsApp) because the image is the content and
        // any tinted surround pulls the viewer's eye / shifts
        // perceived photo colours. Flagged by the LunaGlass audit
        // but the audit itself notes the exemption is reasonable.
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(fileName ?: stringResource(R.string.story_screens_photo), color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(app.aether.aegis.ui.components.AegisIcons.Back, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                saveImageToGallery(context, file, fileName)
                            }
                            Toast.makeText(
                                context,
                                if (ok) "Saved to Pictures/Aegis" else "Save failed",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }) { Text(stringResource(R.string.action_save), color = Color.White) }
                },
            )
        },
    ) { padding ->
        // Pinch-zoom + pan + double-tap toggle + swipe-down to dismiss.
        // Standard image-viewer expectations.
        var scale by remember { mutableStateOf(1f) }
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (!file.exists()) {
                Text(
                    "File not found:\n$filePath",
                    color = Color.White,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                coil.compose.AsyncImage(
                    model = file,
                    contentDescription = fileName ?: "photo",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        )
                        .pointerInput(Unit) {
                            // Two-finger pinch + pan-while-zoomed.
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        }
                        // Three separate pointerInput modifiers so the
                        // gesture detectors don't compete inside one
                        // awaitPointerEvent loop: transform (pinch/pan),
                        // vertical-drag (dismiss), and tap (double-tap zoom)
                        // each get their own. The `scale <= 1.01f` checks use
                        // an epsilon, not == 1f, because pinch leaves scale a
                        // hair off exactly 1.0 — anything within the epsilon
                        // counts as "at base zoom" for dismiss/reset purposes.
                        .pointerInput(Unit) {
                            // Single-finger drag — at base zoom this
                            // becomes swipe-down-to-dismiss. We
                            // accumulate offsetY during the drag and
                            // pop on release if it crossed 150 px.
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (scale <= 1.01f && offsetY > 150f) {
                                        navController.popBackStack()
                                    } else if (scale <= 1.01f) {
                                        offsetY = 0f
                                    }
                                },
                                onVerticalDrag = { _, delta ->
                                    if (scale <= 1.01f) offsetY += delta
                                },
                            )
                        }
                        .pointerInput(Unit) {
                            // Double-tap: toggle 1× ↔ 2.5×.
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.01f) {
                                        scale = 1f; offsetX = 0f; offsetY = 0f
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                            )
                        },
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

/**
 * Copies [src] into the device's public Pictures/Aegis directory via
 * MediaStore so the gallery picks it up. Returns true on success.
 *
 * On API ≥ Q (10) we use relative-path scoped storage; on older we
 * write to the legacy file path directly. The MediaStore.Images insert
 * triggers a media-scan automatically.
 */
private fun saveImageToGallery(context: Context, src: File, displayName: String?): Boolean {
    if (!src.exists()) return false
    val name = displayName?.takeIf { it.isNotBlank() }
        ?: ("aegis-" + System.currentTimeMillis() + ".jpg")
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, guessImageMime(name))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API ≥ Q (10): scoped storage. RELATIVE_PATH places the file
            // under Pictures/Aegis without WRITE_EXTERNAL_STORAGE. IS_PENDING
            // hides the row from other apps until we flip it to 0 after the
            // bytes are fully written, so nothing reads a half-copied image.
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Aegis",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return false
    val ok = runCatching {
        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
            true
        } ?: false
    }.getOrDefault(false)
    if (ok) {
        // Publish the completed file: clear IS_PENDING (API ≥ Q) so the
        // gallery + other apps can see it. No-op pre-Q (pending never set).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    } else {
        // Copy failed — delete the placeholder row so a failed save can't
        // strand an empty/half-written entry: an orphaned 0-byte gallery
        // item pre-Q, or a stuck IS_PENDING=1 row on API ≥ Q.
        runCatching { resolver.delete(uri, null, null) }
    }
    return ok
}

/** Best-effort MIME from the filename extension, defaulting to JPEG.
 *  Drives the MediaStore MIME_TYPE so the gallery indexes the file with
 *  the right type even though we never inspect the actual bytes. */

private fun guessImageMime(name: String): String = when {
    name.endsWith(".png", true) -> "image/png"
    name.endsWith(".webp", true) -> "image/webp"
    name.endsWith(".gif", true) -> "image/gif"
    else -> "image/jpeg"
}

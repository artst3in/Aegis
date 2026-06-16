package app.aether.aegis.ui.screens

import app.aether.aegis.AegisApp
import app.aether.aegis.data.SecureNoteEntity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import app.aether.aegis.ui.components.AegisIcon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen note editor. Notes can carry one optional attachment
 * (image / video / file) — the attachment row sits between the title
 * bar and the body so it's discoverable but not pushy.
 *
 * Two entry paths:
 *  - "note/new" → blank editor; Save creates a new SecureNoteEntity
 *  - "note/{id}" → loads existing, edits in-place, Save updates
 *
 * Auto-saves on back-press as a safety net in addition to the
 * explicit Save button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(noteId: String?, navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // `loaded` is the persisted entity once it exists (null = unsaved new
    // note). After the first save it flips non-null so subsequent saves go
    // down the UPDATE path instead of creating a second row.
    var loaded by remember { mutableStateOf<SecureNoteEntity?>(null) }
    var body by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf(false) }
    // `initial*` capture the values as last persisted, so the dirty-check
    // (savable / auto-save) can compare current-vs-saved rather than
    // current-vs-blank. Updated in lockstep on every successful commit.
    var initialBody by remember { mutableStateOf("") }
    var initialAttachmentPath by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf("") }

    // Staged attachment — set when user picks/captures, mirrors the
    // ChatScreen pendingAttachment flow so the same widgets work.
    var attachment by remember { mutableStateOf<app.aether.aegis.util.Attachments.Local?>(null) }

    // On entry (or noteId change), hydrate the editor from the stored note.
    // noteId == null is the "note/new" path — nothing to load, fields stay
    // blank and `loaded` stays null until the first commit.
    LaunchedEffect(noteId) {
        if (noteId != null) {
            val entity = AegisApp.instance.repository.secureNoteById(noteId)
            loaded = entity
            body = entity?.body.orEmpty()
            pinned = entity?.pinned ?: false
            initialBody = body
            initialAttachmentPath = entity?.attachmentPath
            folder = entity?.folder.orEmpty()
            // Hydrate the staged-attachment so the UI shows the saved one.
            val attachPath = entity?.attachmentPath
            val attachMime = entity?.attachmentMime
            if (attachPath != null && attachMime != null) {
                attachment = app.aether.aegis.util.Attachments.Local(
                    path = attachPath,
                    mime = attachMime,
                    size = entity.attachmentSize ?: 0L,
                    name = entity.attachmentName,
                )
            }
        }
    }

    // Attachment dirty-check by path identity: a different (or cleared)
    // path vs the last-saved one counts as changed. Editing only the body
    // leaves the path untouched, so this stays false in that case.
    fun isAttachmentChanged(): Boolean {
        val cur = attachment?.path
        return cur != initialAttachmentPath
    }

    /**
     * Persist the current editor state. UPDATEs the existing row when
     * [loaded] is set, otherwise INSERTs — but only if there's something
     * worth saving (non-blank body OR an attachment), so an empty new note
     * leaves nothing behind. Resyncs the `initial*` baseline so the
     * dirty-check immediately reads "clean". [thenPop] = explicit Save
     * button (toast + back); false = silent save used by the auto-save path.
     */
    fun commit(thenPop: Boolean) {
        val trimmed = body.trim()
        val existing = loaded
        val a = attachment
        scope.launch {
            if (existing != null) {
                AegisApp.instance.repository.updateSecureNote(
                    existing.copy(
                        body = trimmed,
                        pinned = pinned,
                        updatedAt = System.currentTimeMillis(),
                        attachmentPath = a?.path,
                        attachmentMime = a?.mime,
                        attachmentSize = a?.size,
                        attachmentName = a?.name,
                        folder = folder.trim().takeIf { it.isNotBlank() },
                    )
                )
                initialBody = trimmed
                initialAttachmentPath = a?.path
                loaded = existing.copy(
                    body = trimmed,
                    pinned = pinned,
                    attachmentPath = a?.path,
                    attachmentMime = a?.mime,
                    attachmentSize = a?.size,
                    attachmentName = a?.name,
                )
            } else if (trimmed.isNotBlank() || a != null) {
                val saved = AegisApp.instance.repository.saveSecureNote(
                    body = trimmed,
                    pinned = pinned,
                    attachmentPath = a?.path,
                    attachmentMime = a?.mime,
                    attachmentSize = a?.size,
                    attachmentName = a?.name,
                    folder = folder.trim().takeIf { it.isNotBlank() },
                )
                loaded = saved
                initialBody = trimmed
                initialAttachmentPath = a?.path
            }
            if (thenPop) {
                android.widget.Toast.makeText(
                    AegisApp.instance, "Saved",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                navController.popBackStack()
            }
        }
    }

    // Safety-net auto-save on back-press / system gesture / process leave:
    // onDispose fires when this composable leaves the tree, catching the
    // case where the user navigates away without tapping Save. Only writes
    // when something actually changed vs the saved baseline, to avoid
    // touching updatedAt on a pure read. Mirrors `commit` (body, pin,
    // attachment, AND folder); it doesn't refresh the `initial*` baseline,
    // which is harmless on teardown.
    DisposableEffect(noteId) {
        onDispose {
            val trimmed = body.trim()
            val existing = loaded
            val a = attachment
            val folderNorm = folder.trim().takeIf { it.isNotBlank() }
            // Dirty if body, pin state, folder, or attachment differs from
            // saved. `folder` is included so a folder-only edit dismissed via
            // back-gesture (without tapping Save) isn't silently dropped.
            val changed = trimmed != initialBody.trim() ||
                pinned != (existing?.pinned ?: false) ||
                folderNorm != existing?.folder ||
                isAttachmentChanged()
            if (changed) {
                scope.launch {
                    if (existing != null) {
                        AegisApp.instance.repository.updateSecureNote(
                            existing.copy(
                                body = trimmed,
                                pinned = pinned,
                                updatedAt = System.currentTimeMillis(),
                                attachmentPath = a?.path,
                                attachmentMime = a?.mime,
                                attachmentSize = a?.size,
                                attachmentName = a?.name,
                                folder = folderNorm,
                            )
                        )
                    } else if (trimmed.isNotBlank() || a != null) {
                        AegisApp.instance.repository.saveSecureNote(
                            body = trimmed,
                            pinned = pinned,
                            attachmentPath = a?.path,
                            attachmentMime = a?.mime,
                            attachmentSize = a?.size,
                            attachmentName = a?.name,
                            folder = folderNorm,
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (loaded == null) "New vault entry" else "Vault entry") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(app.aether.aegis.ui.components.AegisIcons.Back, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { pinned = !pinned }) {
                        AegisIcon(
                            icon = app.aether.aegis.ui.components.AegisIcons.Star,
                            contentDescription = if (pinned) stringResource(R.string.contact_unpin) else "Pin",
                            tint = if (pinned) MaterialTheme.colorScheme.secondary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (loaded != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            AegisIcon(app.aether.aegis.ui.components.AegisIcons.Delete, stringResource(R.string.action_delete))
                        }
                    }
                    // Save is enabled only when there's content AND it's
                    // dirty: a brand-new note (loaded == null) is savable as
                    // soon as it has content; an existing note is savable
                    // only once body/pin/attachment actually differ from the
                    // saved version, so re-saving an unchanged note is a no-op.
                    val savable = (body.trim().isNotBlank() || attachment != null) &&
                        (loaded == null ||
                            body.trim() != initialBody.trim() ||
                            pinned != (loaded?.pinned ?: false) ||
                            isAttachmentChanged())
                    TextButton(
                        enabled = savable,
                        onClick = { commit(thenPop = true) },
                    ) {
                        Text(
                            stringResource(R.string.action_save),
                            color = if (savable) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.note_editor_delete_note)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        val existing = loaded ?: return@TextButton
                        scope.launch {
                            AegisApp.instance.repository.deleteSecureNote(existing.id)
                            loaded = null
                            initialBody = ""
                            body = ""
                            attachment = null
                            navController.popBackStack()
                        }
                    }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // Attachment row — pick / camera buttons live here. If an
            // attachment is staged, show a preview chip with an X.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    (context as? app.aether.aegis.MainActivity)?.pickAttachment("*/*") { uri ->
                        scope.launch {
                            val local = withContext(Dispatchers.IO) {
                                app.aether.aegis.util.Attachments.import(context, uri)
                            }
                            if (local != null) attachment = local
                        }
                    }
                }) {
                    AegisIcon(app.aether.aegis.ui.components.AegisIcons.Add, "Attach", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = {
                    (context as? app.aether.aegis.MainActivity)?.takePhoto { uri ->
                        scope.launch {
                            val local = withContext(Dispatchers.IO) {
                                app.aether.aegis.util.Attachments.import(context, uri, fallbackMime = "image/jpeg")
                            }
                            if (local != null) attachment = local
                        }
                    }
                }) {
                    AegisIcon(
                        app.aether.aegis.ui.components.AegisIcons.Camera,
                        "Take photo",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }

            attachment?.let { att ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // For encrypted attachments (.enc paths from
                        // the vault-encryption layer), decrypt to a
                        // cache temp file on display. The temp is
                        // wiped on screen exit by the
                        // VaultAttachmentCrypto.clearDecryptCache
                        // sweep + on vault lock.
                        // Decrypt-on-display: encrypted vault attachments
                        // (.enc) are written to a cache temp only for the
                        // duration of viewing; plaintext-path attachments
                        // (a freshly-imported pick not yet sealed) display
                        // directly. Keyed on att.path so a swapped
                        // attachment re-decrypts.
                        val displayPath = remember(att.path) {
                            if (app.aether.aegis.vault.VaultAttachmentCrypto.isEncrypted(att.path)) {
                                AegisApp.instance.repository
                                    .decryptVaultAttachmentForView(att.path, context)
                            } else att.path
                        }
                        // Hand the (decrypted) file to an external viewer
                        // via FileProvider + a transient read grant. All
                        // guarded with runCatching/early-returns: a missing
                        // file or no-handler app must fail silently, never
                        // crash the vault.
                        val openExternal: () -> Unit = openExternal@ {
                            val p = displayPath ?: return@openExternal
                            val file = java.io.File(p)
                            if (!file.exists()) return@openExternal
                            val uri = runCatching {
                                androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                            }.getOrNull() ?: return@openExternal
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, att.mime)
                                addFlags(
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
                                )
                            }
                            runCatching { context.startActivity(intent) }
                        }
                        if (att.mime.startsWith("image/") && displayPath != null) {
                            coil.compose.AsyncImage(
                                model = java.io.File(displayPath),
                                contentDescription = att.name ?: "attachment",
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(onClick = openExternal),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable(onClick = openExternal),
                                contentAlignment = Alignment.Center,
                            ) {
                                app.aether.aegis.ui.components.AegisIcon(
                                    icon = iconForMime(att.mime),
                                    contentDescription = null,
                                    tint = app.aether.aegis.ui.theme.AegisCyan,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                att.name ?: att.mime,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                            Text(
                                "${att.mime} · ${humanSizeBytes(att.size)}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { attachment = null }) {
                            AegisIcon(app.aether.aegis.ui.components.AegisIcons.Close, "Remove attachment")
                        }
                    }
                }
            }

            // Folder tag — slot-scoped, free-form, optional. Empty
            // unfiles. Saved as part of the normal commit path.
            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it },
                label = { Text(stringResource(R.string.note_editor_folder)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.note_editor_unfiled)) },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                BasicTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 22.sp,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                        MaterialTheme.colorScheme.primary
                    ),
                    decorationBox = { inner ->
                        if (body.isEmpty()) {
                            Text(
                                stringResource(R.string.note_editor_write),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 16.sp,
                            )
                        }
                        inner()
                    },
                )
            }
        }
    }
}

/** Pick a representative glyph for a non-image attachment by MIME family.
 *  Images never reach here (they get a thumbnail); the `else` covers
 *  documents and anything unrecognised. */
private fun iconForMime(mime: String): Int = when {
    mime.startsWith("video/") -> app.aether.aegis.ui.components.AegisIcons.Play
    mime.startsWith("audio/") -> app.aether.aegis.ui.components.AegisIcons.Mic
    mime.startsWith("image/") -> app.aether.aegis.ui.components.AegisIcons.Gallery
    else -> app.aether.aegis.ui.components.AegisIcons.File
}

/** Coarse human-readable byte size (B / KB / MB) for the attachment chip.
 *  Integer-division truncation is fine — this is a glanceable label, not
 *  an exact accounting. Uses 1024-based units. */
private fun humanSizeBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

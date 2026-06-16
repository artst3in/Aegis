package app.aether.aegis.ui.screens

import app.aether.aegis.AegisApp
import app.aether.aegis.data.SecureNoteEntity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * The vault interior (redesigned 2026-06-05). A single flat notes list
 * used to mash text, photos, videos, and documents into one column; this
 * splits them into three browsable views, file-manager style, with a
 * real media gallery.
 *
 * IMPORTANT — this is the *inside* of the vault only. It changes nothing
 * about the vault lock: VaultLockStore / VaultCrypto / the vault PIN gate
 * are untouched. Everything here runs only after the vault is already
 * unlocked, over the rows the session key already decrypts.
 *
 * Bucketing needs no schema change: every [SecureNoteEntity] already
 * carries at most one attachment, so each media/file is already its own
 * row. We sort rows into:
 *   - MEDIA — image or video attachment → gallery grid + zoom pager
 *   - FILES — any other attachment → file-manager list
 *   - NOTES — no attachment → text cards
 * A row with both a body and an attachment lives in Media/Files; its body
 * is the caption.
 *
 * Attachments may be `.enc` (VaultAttachmentCrypto). Every viewable path
 * is resolved on Dispatchers.IO via produceState — a sealed photo grid
 * must never decrypt on the composition thread (the chat-attachment
 * lesson: a big file freezes the UI).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureNotesScreen(navController: NavController) {
    // Live search query; the repository flow below re-queries on each
    // change. Empty string = show everything (the DAO treats blank as
    // "no filter").
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // The notes stream is ALREADY scoped to the unlocked slot: the repo's
    // session key (set when the vault PIN was verified) decides whether we
    // observe the normal rows or the hidden duress-volume rows. This screen
    // never sees both at once and never decides which slot it is — it just
    // renders whatever the already-unlocked session decrypts.
    val notes by AegisApp.instance.repository
        .observeSecureNotes(query)
        .collectAsState(initial = emptyList())
    // The entry currently being forwarded to a contact; null = no sheet.
    var forwardNote by remember { mutableStateOf<SecureNoteEntity?>(null) }
    // Active top-level bucket. Media-first because the gallery is the
    // headline view; survives recomposition but resets on screen leave.
    var tab by remember { mutableStateOf(VaultTab.MEDIA) }
    // Index into the current media list for the full-screen viewer; null
    // = closed.
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    // Wipe-vault flow (moved here from the Security/opsec screen — the
    // vault is its natural home). 0 = idle, 1 = confirm, 2 = type-to-confirm.
    var wipeStep by remember { mutableStateOf(0) }
    // Text the user types in step 2; must equal "WIPE VAULT" to arm the
    // confirm button. Reset to "" each time the flow is (re)entered.
    var wipeTyped by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.secure_notes_vault)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Text("←", fontSize = 20.sp)
                    }
                },
                actions = {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            AegisIcon(AegisIcons.More, "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.secure_notes_wipe_vault), color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; wipeTyped = ""; wipeStep = 1 },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate("note/new") },
                containerColor = MaterialTheme.colorScheme.primary,
            ) { AegisIcon(AegisIcons.Add, "New vault entry") }
        },
    ) { padding ->
        // Folder chips are derived from the (slot-scoped) rows, so the
        // duress volume shows only its own folders — no leak of the real
        // volume's folder names into a coerced session.
        val folders by AegisApp.instance.repository.observeVaultFolders()
            .collectAsState(initial = emptyList())
        var selectedFolder by remember { mutableStateOf<String?>(null) }
        // Drop the folder filter if the selected folder vanishes (renamed,
        // emptied, or last row deleted) so we don't strand the user on an
        // always-empty view.
        LaunchedEffect(folders) {
            if (selectedFolder != null && selectedFolder !in folders) selectedFolder = null
        }
        val visible = if (selectedFolder == null) notes
            else notes.filter { it.folder == selectedFolder }

        // Bucket the visible rows into the three tabs. `remember(visible)`
        // recomputes only when the set itself changes (query/folder/slot),
        // not on every recomposition. NOTES is the complement of media+files
        // (no attachment at all), so every row lands in exactly one bucket.
        val media = remember(visible) { visible.filter { it.isMedia() } }
        val files = remember(visible) { visible.filter { it.isFile() } }
        val texts = remember(visible) { visible.filter { it.attachmentPath == null } }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.secure_notes_search_the_vault)) },
                leadingIcon = { AegisIcon(AegisIcons.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            )

            if (folders.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item(key = "vault-folder-all") {
                        FilterChip(
                            selected = selectedFolder == null,
                            onClick = { selectedFolder = null },
                            label = { Text(stringResource(R.string.secure_notes_all)) },
                        )
                    }
                    items(folders, key = { "vault-folder-$it" }) { name ->
                        FilterChip(
                            selected = name == selectedFolder,
                            onClick = { selectedFolder = if (name == selectedFolder) null else name },
                            label = { Text(name) },
                        )
                    }
                }
            }

            // Media / Files / Notes segmented tabs with live counts.
            TabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
                VaultTab.values().forEach { t ->
                    val count = when (t) {
                        VaultTab.MEDIA -> media.size
                        VaultTab.FILES -> files.size
                        VaultTab.NOTES -> texts.size
                    }
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(if (count > 0) "${t.label} $count" else t.label, fontSize = 13.sp) },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    VaultTab.MEDIA -> VaultMediaGrid(
                        items = media,
                        query = query,
                        onOpen = { viewerIndex = it },
                    )
                    VaultTab.FILES -> VaultEntryList(
                        items = files,
                        query = query,
                        emptyHint = "No files yet. Tap + to add a document, " +
                            "or long-press a chat attachment to save it here.",
                        navController = navController,
                        onForward = { forwardNote = it },
                        scope = scope,
                    )
                    VaultTab.NOTES -> VaultEntryList(
                        items = texts,
                        query = query,
                        emptyHint = "No notes yet. Tap + to write one, or " +
                            "long-press a chat message to save it here.",
                        navController = navController,
                        onForward = { forwardNote = it },
                        scope = scope,
                    )
                }
            }
        }

        forwardNote?.let { note ->
            ForwardVaultEntryDialog(
                note = note,
                onDismiss = { forwardNote = null },
                onSent = { forwardNote = null },
            )
        }

        // Bounds-guard the viewer index against the live media list: a
        // delete (or slot switch) can shrink `media` while the viewer is
        // open, so re-validate against the CURRENT indices every frame
        // rather than trusting the stored index.
        val idx = viewerIndex
        if (idx != null && idx in media.indices) {
            VaultMediaViewer(
                items = media,
                startIndex = idx,
                onDismiss = { viewerIndex = null },
                onForward = { forwardNote = it },
            )
        }

        // ---- Wipe-vault confirm flow (moved here from opsec) ----
        // Two-stage gate: step 1 explains the blast radius, step 2 forces
        // typing "WIPE VAULT" so a destructive, unrecoverable action can't
        // fire on a single mis-tap. Stepping back to 0 cancels.
        when (wipeStep) {
            1 -> AlertDialog(
                onDismissRequest = { wipeStep = 0 },
                title = { Text("Wipe vault?") },
                text = {
                    Text(
                        stringResource(R.string.secure_notes_every_note_photo_video) +
                            "the normal volume and the hidden (duress) volume — " +
                            "will be deleted, and the vault PINs removed. Chats, " +
                            "contacts, app lock, and identity remain.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { wipeStep = 2; wipeTyped = "" }) { Text(stringResource(R.string.tutorial_continue)) }
                },
                dismissButton = { TextButton(onClick = { wipeStep = 0 }) { Text(stringResource(R.string.secure_notes_cancel)) } },
            )
            2 -> AlertDialog(
                onDismissRequest = { wipeStep = 0 },
                title = { Text(stringResource(R.string.secure_notes_type_wipe_vault_to)) },
                text = {
                    Column {
                        Text(stringResource(R.string.secure_notes_beltandbraces), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = wipeTyped,
                            onValueChange = { wipeTyped = it },
                            singleLine = true,
                            placeholder = { Text("WIPE VAULT") },
                        )
                    }
                },
                confirmButton = {
                    // Enabled only on the exact (case-insensitive) phrase.
                    TextButton(
                        enabled = wipeTyped.trim().equals("WIPE VAULT", ignoreCase = true),
                        onClick = {
                            // Close the dialog first, then run the wipe off
                            // the UI thread and leave the (now-empty) vault.
                            wipeStep = 0
                            scope.launch {
                                performWipeVault(ctx)
                                navController.navigateUp()
                            }
                        },
                    ) { Text(stringResource(R.string.secure_notes_wipe), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { wipeStep = 0 }) { Text(stringResource(R.string.secure_notes_cancel)) } },
            )
        }
    }
}

/**
 * Wipe every vault entry + attachment across BOTH slots (normal AND the
 * hidden duress volume) and clear both vault PINs, so a coerced user
 * can't surrender the duress PIN and have the hidden volume re-emerge.
 * App lock, identity, chats, contacts are untouched — only the vault.
 *
 * Ordering matters for crash-safety and for the no-resurrection guarantee:
 * files first (so we don't orphan the DB index of paths), then the rows,
 * then the PINs, then the live session. A crash mid-wipe leaves a
 * partially-emptied vault rather than a recoverable one — acceptable, since
 * the whole point is destruction. Each step is wrapped in runCatching so
 * one failure (e.g. a single undeletable file) can't abort the rest.
 *
 * Does NOT touch app lock / identity / chats / contacts — only the vault
 * surface. Runs on a coroutine (caller's scope); not main-thread safe to
 * skip.
 */
private suspend fun performWipeVault(context: android.content.Context) {
    val repo = AegisApp.instance.repository
    val store = app.aether.aegis.vault.VaultLockStore(context)
    // 1. Delete attachment files for every entry across BOTH slots. Done
    //    BEFORE the rows so a crash can't strand files whose DB index is
    //    already gone (the row list is how we enumerate the paths).
    runCatching {
        repo.allVaultAttachmentPaths().forEach { p ->
            runCatching { java.io.File(p).delete() }
        }
    }
    // 2. Wipe the DB rows — both slots, not just the unlocked one.
    runCatching { repo.wipeAllSecureNotes() }
    // 3. Clear both PIN slots. Removing the duress PIN too is the crux of
    //    the no-resurrection guarantee: with no DURESS PIN left, a coerced
    //    user can't later be made to "reveal" the hidden volume. The next
    //    vault open then hits VaultGate's mandatory fresh-lock setup.
    runCatching { store.clearPin() }
    // 4. Drop any in-memory unlocked session so the just-wiped slot's key
    //    can't linger and decrypt freshly-written rows.
    app.aether.aegis.vault.VaultSession.lock()
}

/** The three browsable buckets inside the vault. [label] is the tab caption
 *  (live count is appended by the caller). MEDIA/FILES/NOTES are mutually
 *  exclusive per row; see [isMedia]/[isFile]. */
private enum class VaultTab(val label: String) {
    MEDIA("Media"), FILES("Files"), NOTES("Notes")
}

/** True when this row's attachment is an image or video — the Media bucket.
 *  Needs BOTH a path AND a media MIME; a media MIME with no path (or vice
 *  versa) is not viewable, so it falls through to Files/Notes. */
private fun SecureNoteEntity.isMedia(): Boolean {
    val m = attachmentMime ?: return false
    return attachmentPath != null && (m.startsWith("image/") || m.startsWith("video/"))
}

/** True for any non-media attachment — the Files bucket. Defined as the
 *  complement of [isMedia] within "has a path", so Media/Files/Notes
 *  partition the rows with no overlap. */
private fun SecureNoteEntity.isFile(): Boolean =
    attachmentPath != null && !isMedia()

/* ------------------------------------------------------------------ */
/* Media gallery                                                        */
/* ------------------------------------------------------------------ */

/**
 * Adaptive thumbnail grid for the Media bucket. Each tile decrypts its own
 * preview off the main thread (see [VaultMediaThumb]); tapping invokes
 * [onOpen] with the tile's index INTO [items], which the caller maps to the
 * full-screen viewer. Shows a context-appropriate empty hint when [items]
 * is empty (distinguishing "no media" from "no search match").
 */
@Composable
private fun VaultMediaGrid(
    items: List<SecureNoteEntity>,
    query: String,
    onOpen: (Int) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyVaultHint(
            if (query.isBlank())
                "No photos or videos yet.\nTap + and attach an image or video."
            else "No media matches \"$query\".",
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        contentPadding = PaddingValues(bottom = 88.dp, top = 3.dp),
    ) {
        itemsIndexed(items) { index, note ->
            VaultMediaThumb(note = note, onClick = { onOpen(index) })
        }
    }
}

/** Local indexed-items shim for [LazyGridScope]. The grid DSL we pin to has
 *  no `itemsIndexed` overload matching the signature used elsewhere, so this
 *  wraps the count-based `items` and feeds both the index and the element.
 *  Keys on the stable row id so the grid keeps composition across reorders. */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.itemsIndexed(
    items: List<SecureNoteEntity>,
    itemContent: @Composable (Int, SecureNoteEntity) -> Unit,
) {
    items(count = items.size, key = { items[it].id }) { i -> itemContent(i, items[i]) }
}

/**
 * A single square thumbnail. Resolves the (possibly `.enc`-sealed) attachment
 * to a viewable path on Dispatchers.IO via [produceState] — a sealed-photo
 * grid must NEVER decrypt on the composition thread (a large file there
 * freezes scrolling). Until the path resolves the tile shows just its
 * placeholder background; videos additionally get a play badge so the tile
 * reads as "video" even when no frame can be decoded.
 */
@Composable
private fun VaultMediaThumb(note: SecureNoteEntity, onClick: () -> Unit) {
    val context = LocalContext.current
    val isVideo = note.attachmentMime?.startsWith("video/") == true
    // Resolve a viewable (decrypted-if-sealed) path off the main thread.
    // Keyed on note.id so each tile resolves once and re-resolves only if
    // the row identity changes.
    val path = note.attachmentPath
    val viewable by produceState<String?>(initialValue = null, key1 = note.id) {
        value = withContext(Dispatchers.IO) { resolveVaultPath(path, context) }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val vp = viewable
        if (vp != null) {
            // For video we let Coil decode the file too — it pulls a
            // frame for many formats; if it can't, the play badge still
            // signals "video".
            coil.compose.AsyncImage(
                model = java.io.File(vp),
                contentDescription = note.attachmentName ?: if (isVideo) stringResource(R.string.chat_video) else "image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isVideo) {
            Surface(
                color = Color.Black.copy(alpha = 0.45f),
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("▶", color = Color.White, fontSize = 15.sp)
                }
            }
        }
    }
}

/**
 * Full-screen, swipeable media viewer — the gallery payoff. Images get
 * pinch-zoom + pan; video gets a tap-to-play-in-system-player frame.
 * Renders in a borderless Dialog so it covers the whole screen.
 */
@Composable
private fun VaultMediaViewer(
    items: List<SecureNoteEntity>,
    startIndex: Int,
    onDismiss: () -> Unit,
    onForward: (SecureNoteEntity) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Open on the tapped tile; the count comes from the live list so the
    // pager stays valid if the set shrinks (the caller also bounds-checks).
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { items.size })
    // The entry the delete button is asking to confirm; null = no prompt.
    // Vault deletes are unrecoverable (no trash; the attachment file is
    // unlinked with the row), so the trash icon arms this instead of
    // deleting outright (user-reported: "vault needs confirmation
    // before deleting files").
    var pendingDelete by remember { mutableStateOf<SecureNoteEntity?>(null) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val note = items[page]
                val isVideo = note.attachmentMime?.startsWith("video/") == true
                // Per-page decrypt off the main thread, same as the grid; a
                // null result shows the spinner (path gone / vault relocked).
                val path = note.attachmentPath
                val viewable by produceState<String?>(initialValue = null, key1 = note.id) {
                    value = withContext(Dispatchers.IO) { resolveVaultPath(path, context) }
                }
                val vp = viewable
                // Pinch-zoom transform, reset per page (keyed on note.id) so
                // swiping to a new image starts un-zoomed and un-panned.
                var scale by remember(note.id) { mutableStateOf(1f) }
                var offsetX by remember(note.id) { mutableStateOf(0f) }
                var offsetY by remember(note.id) { mutableStateOf(0f) }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (vp == null) {
                        CircularProgressIndicator(color = Color.White)
                    } else {
                        coil.compose.AsyncImage(
                            model = java.io.File(vp),
                            contentDescription = note.attachmentName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY,
                                )
                                // Zoom/pan only for stills; video pages
                                // stay fixed and open the system player.
                                .then(
                                    if (isVideo) Modifier
                                    else Modifier.pointerInput(note.id) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            // Clamp zoom to 1x..5x; pan only
                                            // applies while zoomed in, and
                                            // zeroes back out at 1x so the
                                            // image always re-centres.
                                            scale = (scale * zoom).coerceIn(1f, 5f)
                                            if (scale > 1f) {
                                                offsetX += pan.x
                                                offsetY += pan.y
                                            } else {
                                                offsetX = 0f; offsetY = 0f
                                            }
                                        }
                                    },
                                ),
                        )
                        if (isVideo) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.5f),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clickable { openExternal(context, vp, note.attachmentMime) },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("▶", color = Color.White, fontSize = 28.sp)
                                }
                            }
                        }
                    }
                    // Caption — the row's body, if any.
                    if (note.body.isNotBlank()) {
                        Text(
                            note.body,
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 3,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp),
                        )
                    }
                }
            }
            // Close + actions + counter row.
            val current = items.getOrNull(pagerState.currentPage)
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Text("✕", color = Color.White, fontSize = 22.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                if (current != null) {
                    // Send to a contact (reuses the vault forward sheet).
                    IconButton(onClick = { onForward(current); onDismiss() }) {
                        AegisIcon(AegisIcons.Send, stringResource(R.string.device_control_send), tint = Color.White)
                    }
                    // Export — share the decrypted file out via the system.
                    // This deliberately moves a vault item OUTSIDE the sealed
                    // store (into a FileProvider-shared temp the OS chooser
                    // can read), so it is a real exfiltration surface — gated
                    // only by being inside an already-unlocked vault. Decrypt
                    // happens on IO; share only if it resolved.
                    IconButton(onClick = {
                        scope.launch {
                            val p = withContext(Dispatchers.IO) {
                                resolveVaultPath(current.attachmentPath, context)
                            }
                            if (p != null) shareVaultFile(context, p, current.attachmentMime)
                        }
                    }) {
                        Text("↗", color = Color.White, fontSize = 22.sp)
                    }
                    // Arm the delete confirmation for the on-screen entry;
                    // actual delete + close happens once confirmed.
                    IconButton(onClick = { pendingDelete = current }) {
                        AegisIcon(AegisIcons.Delete, stringResource(R.string.secure_notes_delete), tint = app.aether.aegis.ui.theme.AegisSOS)
                    }
                }
                Text(
                    "${pagerState.currentPage + 1} / ${items.size}",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            // Delete confirmation — on confirm, delete the row then close
            // the whole viewer (the gallery list refreshes underneath).
            // We capture `target` (not the live current page) so a swipe
            // between arming and confirming can't delete the wrong item.
            pendingDelete?.let { target ->
                VaultDeleteConfirmDialog(
                    note = target,
                    onConfirm = {
                        // Fire-and-forget delete; closing the viewer is what
                        // the user sees, the row removal lands asynchronously.
                        scope.launch { AegisApp.instance.repository.deleteSecureNote(target.id) }
                        onDismiss()
                    },
                    onDismiss = { pendingDelete = null },
                )
            }
        }
    }
}

/**
 * Confirmation gate shared by every vault-delete affordance. Vault
 * deletes are destructive and unrecoverable — there is no trash, and the
 * attachment file is unlinked along with the DB row — so the trash icons
 * arm this dialog rather than deleting on first tap (user-reported:
 * "vault needs confirmation before deleting files").
 *
 * The dialog dismisses itself BEFORE invoking [onConfirm] so callers can
 * safely tear down state (e.g. close the media viewer) in the same frame.
 */
@Composable
private fun VaultDeleteConfirmDialog(
    note: SecureNoteEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val noun = note.deleteNoun()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this $noun?") },
        text = {
            Text("This $noun will be permanently removed from the vault. This can't be undone.")
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(stringResource(R.string.secure_notes_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.secure_notes_cancel)) } },
    )
}

/** Human noun for the delete prompt, so it reads "Delete this photo?"
 *  rather than a generic "entry". */
private fun SecureNoteEntity.deleteNoun(): String {
    val m = attachmentMime
    return when {
        m?.startsWith("video/") == true -> "video"
        m?.startsWith("image/") == true -> "photo"
        attachmentPath != null -> "file"
        else -> "note"
    }
}

/* ------------------------------------------------------------------ */
/* Files + Notes lists                                                  */
/* ------------------------------------------------------------------ */

/**
 * Shared list renderer for both the Files and Notes buckets (they differ
 * only in their leading icon and empty-state copy). Each card routes to the
 * note editor, and exposes pin / forward / delete. Pin and delete mutate the
 * (slot-scoped) repository on [scope]; delete is gated by a confirm dialog
 * inside the card. [emptyHint] is shown only when nothing matches the query
 * vs. nothing exists.
 */
@Composable
private fun VaultEntryList(
    items: List<SecureNoteEntity>,
    query: String,
    emptyHint: String,
    navController: NavController,
    onForward: (SecureNoteEntity) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (items.isEmpty()) {
        EmptyVaultHint(if (query.isBlank()) emptyHint else "Nothing matches \"$query\".")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        items(items, key = { it.id }) { note ->
            VaultEntryCard(
                note = note,
                onOpen = { navController.navigate("note/${note.id}") },
                onPin = { scope.launch { AegisApp.instance.repository.pinSecureNote(note.id, !note.pinned) } },
                onDelete = { scope.launch { AegisApp.instance.repository.deleteSecureNote(note.id) } },
                onForward = { onForward(note) },
            )
        }
    }
}

/**
 * One row in the Files/Notes list. Files lead with a MIME-typed icon and
 * show name + size; notes show the first line as title and the remainder as
 * a 2-line preview. Pinned rows get a tinted container and (per the repo's
 * ordering) float to the top. Tapping the body opens the editor; the trailing
 * icons pin, forward, and delete.
 */
@Composable
private fun VaultEntryCard(
    note: SecureNoteEntity,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onForward: () -> Unit,
) {
    // Trash icon arms this; the delete only fires once confirmed.
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (note.pinned)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isFile = note.attachmentPath != null
            if (isFile) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    AegisIcon(
                        icon = iconForVaultMime(note.attachmentMime),
                        contentDescription = null,
                        tint = app.aether.aegis.ui.theme.AegisCyan,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                val title = when {
                    isFile -> note.attachmentName ?: note.attachmentMime ?: stringResource(R.string.chat_file)
                    else -> note.body.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "Note"
                }
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (!isFile && note.body.contains('\n')) {
                    Text(
                        note.body.substringAfter('\n').trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatNoteDate(note.updatedAt.takeIf { it > 0 } ?: note.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isFile && (note.attachmentSize ?: 0L) > 0L) {
                        Text(
                            "  ·  ${formatBytes(note.attachmentSize ?: 0L)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(onClick = onPin) {
                AegisIcon(
                    icon = AegisIcons.Star,
                    contentDescription = if (note.pinned) "unpin" else "pin",
                    tint = if (note.pinned) MaterialTheme.colorScheme.secondary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onForward) {
                AegisIcon(AegisIcons.Send, stringResource(R.string.forward_vault_entry_dialog_send_to_contact),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { confirmDelete = true }) {
                AegisIcon(AegisIcons.Delete, "delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (confirmDelete) {
        VaultDeleteConfirmDialog(
            note = note,
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Centred placeholder text shown when a bucket has no rows. Pure layout —
 *  the caller decides the message (empty bucket vs. no search match). */
@Composable
private fun EmptyVaultHint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(top = 64.dp, start = 24.dp, end = 24.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/* ------------------------------------------------------------------ */
/* Helpers                                                              */
/* ------------------------------------------------------------------ */

/** Resolve a vault attachment path to a viewable one: decrypt `.enc`
 *  blobs to a session temp, pass plaintext through. MUST be called off
 *  the main thread. Returns null if the path is gone or decrypt fails
 *  (e.g. vault relocked mid-view). */
private fun resolveVaultPath(path: String?, context: android.content.Context): String? {
    val p = path ?: return null
    return if (app.aether.aegis.vault.VaultAttachmentCrypto.isEncrypted(p)) {
        AegisApp.instance.repository.decryptVaultAttachmentForView(p, context)
    } else p
}

/** Hand a (decrypted) vault file to the system viewer via a FileProvider
 *  content:// URI (ACTION_VIEW). Like [shareVaultFile] this is a deliberate
 *  out-of-vault hand-off; the temp grant is read-only and scoped to the
 *  target app. No-op if the file is gone or the URI can't be built. */
private fun openExternal(context: android.content.Context, path: String, mime: String?) {
    val file = java.io.File(path)
    if (!file.exists()) return
    val uri = runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
    }.getOrNull() ?: return
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime ?: "*/*")
        addFlags(
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }
    runCatching { context.startActivity(intent) }
}

/** Share a decrypted vault file out via the system chooser (export). The
 *  passed [path] is the already-decrypted temp file from resolveVaultPath. */
private fun shareVaultFile(context: android.content.Context, path: String, mime: String?) {
    val file = java.io.File(path)
    if (!file.exists()) return
    val uri = runCatching {
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
    }.getOrNull() ?: return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mime ?: "*/*"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(intent, "Export from vault")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Pick a list icon from the attachment MIME's top-level type; unknown /
 *  null types fall back to the generic file glyph. */
private fun iconForVaultMime(mime: String?): Int = when {
    mime == null -> AegisIcons.File
    mime.startsWith("video/") -> AegisIcons.Play
    mime.startsWith("audio/") -> AegisIcons.Mic
    mime.startsWith("image/") -> AegisIcons.Gallery
    else -> AegisIcons.File
}

// formatBytes lives in ScreenUtils.kt (package-internal) — reused here.

// Shared formatter for the card timestamp. Locale-default so dates read
// naturally per device; reused (not re-allocated) across every card.
private val NOTE_DATE = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault())
private fun formatNoteDate(ts: Long): String = NOTE_DATE.format(Date(ts))

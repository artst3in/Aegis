package app.aether.aegis.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.AegisApp
import app.aether.aegis.core.MessageType
import app.aether.aegis.data.StoryEntity
import app.aether.aegis.ui.components.HexShape
import app.aether.aegis.ui.components.HexagonShape
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisCyanGlow
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisSurface
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tiny collapse-toggle row that sits above [StoriesStrip] on the
 * chat-list screen — gives users who don't use stories a way to
 * reclaim ~80 dp of vertical screen on every launch. Tap anywhere
 * on the row to expand / collapse; the state lives in
 * [app.aether.aegis.ui.ChatListPrefs.storiesCollapsed] so the next
 * launch remembers.
 *
 * Layout: "Stories" label on the left, count chip when there are
 * any, chevron on the right. ~28 dp tall — small enough that
 * keeping the row visible when collapsed costs almost nothing
 * compared to the full strip.
 */
@Composable
fun StoriesHeader(
    collapsed: Boolean,
    storyCount: Int,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.story_screens_stories),
            color = AegisOnSurfaceDim,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (storyCount > 0) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "·  $storyCount",
                color = AegisOnSurfaceDim,
                fontSize = 11.sp,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        // Chevron: ▾ when expanded (down = currently down/open),
        // ▸ when collapsed (right = will expand to the right/down on tap).
        Text(
            if (collapsed) "▸" else "▾",
            color = AegisOnSurfaceDim,
            fontSize = 11.sp,
        )
    }
}

/**
 * The horizontal hex-avatar strip at the top of the chat list. Tile #1
 * is always the composer ("+"). Subsequent tiles are stories, with self
 * first if you have an active one, then peers ordered newest first.
 *
 * Cyan border + glow on unviewed peer stories, dim border on viewed.
 * Self stories use cyan glow regardless (your own is always "fresh").
 *
 * @param peerNames public-key → display-name lookup; a missing entry
 *   falls back to "Peer" so the strip never blocks on contact resolution.
 */
@Composable
fun StoriesStrip(
    stories: List<StoryEntity>,
    selfKey: String,
    peerNames: Map<String, String>,
    onCompose: () -> Unit,
    onView: (String) -> Unit,
) {
    // Group stories by author so each peer shows a single tile (open
    // the tile → scroll through all of theirs). For now we just point
    // at the newest; the viewer handles paging. Memoised on `stories`
    // so we only regroup when the underlying list changes.
    val byAuthor = remember(stories) {
        stories.groupBy { it.authorKey }
            .mapValues { entry -> entry.value.sortedByDescending { it.createdAt } }
    }
    // Display order: self first (so your own story is always the first
    // real tile after the composer), then peers most-recent-first. Each
    // peer's `.first()` is their newest because byAuthor sorted descending
    // above. Memoised on (grouping, selfKey).
    val ordered = remember(byAuthor, selfKey) {
        val self = byAuthor[selfKey]
        val peers = byAuthor.filterKeys { it != selfKey }.entries
            .sortedByDescending { it.value.first().createdAt }
        buildList {
            if (self != null) add(selfKey to self)
            addAll(peers.map { it.key to it.value })
        }
    }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Composer always pinned at index 0; keyed by author public key.
        item { ComposerTile(onClick = onCompose) }
        items(ordered, key = { it.first }) { (authorKey, list) ->
            val newest = list.first()
            val isSelf = authorKey == selfKey
            // Tile shows the unviewed ring if ANY of this author's stories
            // are unseen — opening the tile pages through all of them.
            val anyUnviewed = list.any { !it.viewed }
            StoryTile(
                story = newest,
                label = if (isSelf) "You" else (peerNames[authorKey] ?: "Peer"),
                // Never highlight your own tile as "unviewed" — you wrote it.
                unviewed = anyUnviewed && !isSelf,
                onClick = { onView(newest.id) },
            )
        }
    }
}

/** The leading "+" tile of [StoriesStrip] — a cyan hex with a camera
 *  glyph that opens [StoryComposerScreen]. Always present, even with
 *  zero stories, so posting is one tap from the chat list. */
@Composable
private fun ComposerTile(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp),
    ) {
        // Hex + camera glyph rather than hex + bare "+" — semantically
        // tighter (you're capturing something to post) and visually
        // distinct from the add-contact FAB at the bottom-right (also
        // a hex + plus). The user flagged the previous "+" Unicode
        // shape as broken-looking; this is a proper LunaGlass vector.
        HexShape(
            size = 56.dp,
            borderColor = AegisCyan,
            fillColor = AegisCyanGlow,
            onClick = onClick,
        ) {
            app.aether.aegis.ui.components.AegisIcon(
                icon = app.aether.aegis.R.drawable.ic_aegis_camera,
                contentDescription = stringResource(R.string.story_screens_new_story),
                tint = AegisCyan,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.story_screens_your_story),
            color = AegisOnSurfaceDim,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

/** One author's tile in [StoriesStrip]: hex avatar (clipped attachment
 *  image, or a single-letter fallback) plus label. [unviewed] drives the
 *  cyan ring + glow; viewed/own tiles use the dim border. Tap opens the
 *  author's newest story in [StoryViewerScreen]. */
@Composable
private fun StoryTile(
    story: StoryEntity,
    label: String,
    unviewed: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp),
    ) {
        // Hex avatar — if there's an image, clip it into a hexagon; else
        // fall back to a single-letter avatar.
        HexShape(
            size = 56.dp,
            borderColor = if (unviewed) AegisCyan else AegisBorder,
            fillColor = AegisSurface,
            glow = unviewed,
            glowColor = AegisCyanGlow,
            onClick = onClick,
        ) {
            val path = story.attachmentPath
            if (!path.isNullOrBlank()) {
                AsyncImage(
                    model = java.io.File(path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(HexagonShape),
                )
            } else {
                Text(
                    label.take(1).uppercase(),
                    color = if (unviewed) AegisCyan else AegisOnSurfaceDim,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            color = if (unviewed) AegisCyan else AegisOnSurfaceDim,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

/* --------------------------------------------------------------------
 * Composer
 * ------------------------------------------------------------------ */

/**
 * Compose-and-post screen for a new story: a caption field plus an
 * optional single photo (gallery pick or camera). "Post" is enabled
 * only once there's a caption OR an attachment, and fans the story out
 * via [postStory]. Navigates up on success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryComposerScreen(navController: NavController) {
    val context = LocalContext.current
    // Cast to MainActivity for its pickAttachment/takePhoto launchers —
    // the Activity owns the ActivityResult plumbing. Nullable cast so a
    // preview / non-MainActivity host degrades to "buttons do nothing"
    // rather than crashing.
    val activity = context as? app.aether.aegis.MainActivity
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var attachment by remember { mutableStateOf<app.aether.aegis.util.Attachments.Local?>(null) }
    // Guards the Post button against double-submit while the suspend
    // fan-out is in flight.
    var posting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.story_screens_new_story)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Text("←", fontSize = 20.sp)
                    }
                },
                actions = {
                    // Enabled only with content to post and no in-flight
                    // send. postStory does the local-save-then-fan-out; we
                    // pop back regardless once it returns (per-peer sends
                    // are best-effort inside postStory).
                    TextButton(
                        enabled = !posting && (text.isNotBlank() || attachment != null),
                        onClick = {
                            posting = true
                            scope.launch {
                                postStory(text, attachment)
                                posting = false
                                navController.navigateUp()
                            }
                        },
                    ) { Text(if (posting) "Posting…" else "Post") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.story_screens_visible_to_every_paired),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.story_screens_whats_happening)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 8,
            )

            // Attachment row — pick from gallery OR camera. Both paths run
            // Attachments.import off the main thread (it copies the picked
            // file into app-private storage) before exposing it as state.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    activity?.pickAttachment("image/*") { uri ->
                        scope.launch {
                            val local = withContext(Dispatchers.IO) {
                                app.aether.aegis.util.Attachments.import(context, uri)
                            }
                            attachment = local
                        }
                    }
                }) {
                    app.aether.aegis.ui.components.AegisIcon(
                        icon = app.aether.aegis.ui.components.AegisIcons.Gallery,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.story_screens_photo))
                }
                OutlinedButton(onClick = {
                    activity?.takePhoto { uri ->
                        scope.launch {
                            val local = withContext(Dispatchers.IO) {
                                app.aether.aegis.util.Attachments.import(context, uri)
                            }
                            attachment = local
                        }
                    }
                }) {
                    app.aether.aegis.ui.components.AegisIcon(
                        icon = app.aether.aegis.ui.components.AegisIcons.Camera,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.story_screens_camera))
                }
                if (attachment != null) {
                    TextButton(onClick = { attachment = null }) {
                        Text(stringResource(R.string.story_screens_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            attachment?.let { local ->
                AsyncImage(
                    model = java.io.File(local.path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .background(AegisSurface),
                )
            }
        }
    }
}

/**
 * Fan out a story to every paired peer and stash a local copy.
 *
 *   - text-only: send as MessageType.STORY with body = caption
 *   - with photo: send as MessageType.PHOTO with caption
 *     `[aegis:story:<id>]<text>` so the receiving SimpleXTransport
 *     routes it to the stories table instead of the chat (see
 *     SimpleXTransport.recordReceivedAttachment intercept).
 */
private suspend fun postStory(text: String, attachment: app.aether.aegis.util.Attachments.Local?) {
    val aegisApp = AegisApp.instance
    val selfKey = aegisApp.identity.deviceId
    val storyId = java.util.UUID.randomUUID().toString()
    val now = System.currentTimeMillis()

    // 1. Local copy first so the strip updates immediately.
    aegisApp.repository.upsertStory(
        StoryEntity(
            id = storyId,
            authorKey = selfKey,
            body = text,
            attachmentPath = attachment?.path,
            attachmentMime = attachment?.mime,
            createdAt = now,
            viewed = true,
        )
    )

    // 2. Fan out to Trusted peers only — stories are routine social
    //    content under the trust model. Emergency and Untrusted
    //    never see them. Sent as separate SimpleX messages since
    //    there's no group-broadcast primitive at this layer.
    val peers = withContext(Dispatchers.IO) { aegisApp.repository.trustedTargets() }
    val simplex = aegisApp.transports.filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
    for (peer in peers) {
        // Per-peer best-effort: one peer's send failure (offline, no
        // queue) must not abort the fan-out to the rest, so each send is
        // wrapped in its own runCatching and swallowed.
        runCatching {
            if (attachment != null && simplex != null) {
                // Photo path: the [aegis:story:<id>] caption prefix is the
                // routing marker the receiver intercepts to file the
                // inbound file as a story rather than a chat photo.
                val captionTag = "[aegis:story:$storyId]$text"
                simplex.sendFileToContact(
                    peerPubkey = peer.publicKey,
                    filePath = attachment.path,
                    isImage = attachment.mime.startsWith("image/"),
                    caption = captionTag,
                )
            } else {
                // Text-only path: a [aegis:story]-prefixed JSON blob sent
                // as MessageType.STORY. Same routing intent as the photo
                // caption tag, but carried in the message body since
                // there's no file.
                val payload = "[aegis:story]" + org.json.JSONObject()
                    .put("id", storyId)
                    .put("text", text)
                    .toString()
                aegisApp.protocolManager.sendMessage(
                    to = peer.publicKey,
                    content = payload,
                    type = MessageType.STORY,
                )
            }
        }
    }
}

/* --------------------------------------------------------------------
 * Viewer
 * ------------------------------------------------------------------ */

/**
 * Full-bleed single-story viewer (Instagram/Snapchat style): author
 * header, the photo (if any), the caption, on a pure-black backdrop.
 * Tapping anywhere dismisses. Loads the story by id, marks it viewed on
 * open, and offers a Delete action when the viewer is the author.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryViewerScreen(storyId: String, navController: NavController) {
    var story by remember { mutableStateOf<StoryEntity?>(null) }
    val scope = rememberCoroutineScope()

    // Load + mark-viewed on open. Re-keyed on storyId so navigating
    // between stories without leaving the route reloads. markStoryViewed
    // is what clears the unviewed ring on the strip next time it renders.
    LaunchedEffect(storyId) {
        story = AegisApp.instance.repository.getStory(storyId)
        if (story != null) {
            AegisApp.instance.repository.markStoryViewed(storyId)
        }
    }
    val s = story ?: run {
        // Pure-black background + white text is the right call for
        // a full-bleed Instagram/Snapchat-style story viewer
        // regardless of the LunaGlass dark-cyan theme — same
        // rationale as PhotoViewerScreen. A deliberate exemption
        // from the LunaGlass dark-cyan theme.
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.story_screens_story_not_found), color = Color.White)
        }
        return
    }

    // Two-step name resolution: "You" for own stories (no DB lookup
    // needed), otherwise null so the peer flow below fills it in. Memoised
    // on the author key.
    val authorName = remember(s.authorKey) {
        val aegisApp = AegisApp.instance
        if (s.authorKey == aegisApp.identity.deviceId) "You"
        else null  // resolved below from peers flow
    }
    val peers by AegisApp.instance.repository.observeKnownPeers().collectAsState(initial = emptyList())
    // "You" → matched peer display name → "Peer" fallback (peer not yet
    // resolved / no longer known). The flow updates resolvedName live.
    val resolvedName = authorName
        ?: peers.firstOrNull { it.publicKey == s.authorKey }?.displayName
        ?: "Peer"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { navController.navigateUp() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // fillMaxSize so the weighted spacers actually distribute (header
            // pinned to the top, media centred) and statusBarsPadding so the
            // author header clears the notification bar instead of rendering
            // underneath it ("stories render too high").
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Author header overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HexShape(
                    size = 36.dp,
                    borderColor = AegisCyan,
                    fillColor = AegisSurface,
                    glow = true,
                ) {
                    Text(
                        resolvedName.take(1).uppercase(),
                        color = AegisCyan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(resolvedName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        formatRelTime(s.createdAt),
                        color = AegisOnSurfaceDim,
                        fontSize = 11.sp,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // Delete is offered only on your OWN stories — deleting is
                // local (removes this device's copy); it does not recall
                // copies already fanned out to peers.
                if (s.authorKey == AegisApp.instance.identity.deviceId) {
                    TextButton(onClick = {
                        scope.launch {
                            AegisApp.instance.repository.deleteStory(s.id)
                            navController.navigateUp()
                        }
                    }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            val path = s.attachmentPath
            if (!path.isNullOrBlank()) {
                AsyncImage(
                    model = java.io.File(path),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (s.body.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    s.body,
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/** Compact relative-age label ("just now" / "Nm" / "Nh" / "Nd" ago) for
 *  the story header. Coarse on purpose — stories are ephemeral social
 *  content, so minute-bucket precision is plenty. */
private fun formatRelTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        else -> "${diff / 86_400_000L}d ago"
    }
}

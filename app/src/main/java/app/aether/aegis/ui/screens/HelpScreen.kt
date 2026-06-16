package app.aether.aegis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.components.HexShape
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * In-app help — the docs we ship under `assets/docs/` plus the
 * Technology Origins page (moved out of Settings).
 *
 * Each card opens a [DocViewerScreen] which renders the markdown
 * with a tiny line-based parser — good enough for headings, bold,
 * code blocks, blockquotes. Nothing fancy; the docs themselves are
 * the deliverable.
 */
/**
 * One row in the Help index. [route] is both the nav destination AND the
 * stable identity used to slot the entry into a section bucket (see
 * [inSection]) — so it must be unique across [HELP_ENTRIES]. [iconRes]
 * (vector drawable) wins over [glyph] (text) when both are present.
 */
private data class HelpEntry(
    val title: String,
    val subtitle: String,
    val route: String,
    val glyph: String? = null,
    @DrawableRes val iconRes: Int? = null,
)

private val HELP_ENTRIES = listOf(
    HelpEntry(
        title = "Getting Started",
        subtitle = "First-run setup, pairing contacts, sending your first message.",
        route = "help/doc/GETTING_STARTED.md",
        glyph = "→",
        iconRes = AegisIcons.Send,
    ),
    // Replay the Cyan-guided onboarding tutorial.
    // route carries replay=true so exiting pops back here rather
    // than advancing into first-run onboarding; the one-time
    // `tutorial_completed` flag is left untouched.
    HelpEntry(
        title = "Replay Tutorial",
        subtitle = "Walk through Aegis basics with Cyan.",
        route = "tutorial?replay=true",
        iconRes = AegisIcons.Play,
    ),
    HelpEntry(
        title = "User Manual",
        subtitle = "Every screen, every gesture, every feature — explained.",
        route = "help/doc/AEGIS_USER_MANUAL.md",
        iconRes = AegisIcons.Notes,
    ),
    HelpEntry(
        title = "Privacy",
        subtitle = "What Aegis stores, what it never sends, what it can't see.",
        route = "help/doc/PRIVACY.md",
        iconRes = AegisIcons.Lock,
    ),
    HelpEntry(
        title = "Design philosophy",
        subtitle = "Why your time matters — Hick's, Miller's, Fitts's law, applied.",
        route = "help/doc/DESIGN_PHILOSOPHY.md",
        glyph = "∑",
        iconRes = AegisIcons.Help,
    ),
    HelpEntry(
        title = "Origins",
        subtitle = "Where each Aegis feature comes from — Voyager, Enigma, ARPANET.",
        route = "settings/origins",
        glyph = "★",
        iconRes = AegisIcons.Voyager,
    ),
    HelpEntry(
        title = "Architecture",
        subtitle = "How the pieces fit — SimpleX core, Compose UI, SOS pipeline.",
        route = "help/doc/ARCHITECTURE.md",
        glyph = "◈",
        iconRes = AegisIcons.More,
    ),
    HelpEntry(
        title = "Versioning",
        subtitle = "Aether YYYY.MM.BBB — one scheme universe-wide.",
        route = "help/doc/VERSIONING.md",
        glyph = "▣",
        iconRes = AegisIcons.Notes,
    ),
    HelpEntry(
        title = "The Shield",
        subtitle = "What Aegis does and why — the complete overview.",
        route = "help/doc/FAMILY_SPEC.md",
        glyph = "◉",
        iconRes = AegisIcons.Lock,
    ),
    HelpEntry(
        title = "Project Aether",
        subtitle = "Project Aether — Aegis, LunaOS, and the LunaGlass design system.",
        route = "help/doc/ABOUT_PROJECT_AETHER.md",
        iconRes = AegisIcons.Mesh,
    ),
    HelpEntry(
        title = "Why One Second",
        subtitle = "The SOS hold is one second — calculated, not guessed. The math behind the SOS button.",
        route = "help/doc/WHY_ONE_SECOND.md",
        iconRes = AegisIcons.Siren,
    ),
    HelpEntry(
        title = "Why No Group SOS",
        subtitle = "How group SOS would help stalkers find you — and why it will never exist.",
        route = "help/doc/WHY_NO_GROUP_SOS.md",
        iconRes = AegisIcons.Siren,
    ),
    HelpEntry(
        title = "Why SOS Needs Aegis",
        subtitle = "Why SOS only reaches contacts who also run Aegis — and why that protects you, not just the app.",
        route = "help/doc/WHY_NO_NONAEGIS_SOS.md",
        iconRes = AegisIcons.Siren,
    ),
    HelpEntry(
        title = "Why No Group DMs",
        subtitle = "Why you cannot privately message group members — and why this protects you.",
        route = "help/doc/WHY_NO_GROUP_DM.md",
        iconRes = AegisIcons.Send,
    ),
    HelpEntry(
        title = "Why No Tor",
        subtitle = "Why Aegis uses 2-hop routing instead of Tor — and why that's safer.",
        route = "help/doc/WHY_NO_TOR.md",
    ),
    HelpEntry(
        title = "Attention Leak",
        subtitle = "A new attack vector we discovered — how chat-open frequency becomes surveillance.",
        route = "help/doc/ATTENTION_LEAK.md",
        iconRes = AegisIcons.RemoteCmd,
    ),
    HelpEntry(
        title = "Ephemeral Profiles",
        subtitle = "Throwaway identities wiped on lock — and the honest limits of \"wiped.\"",
        route = "help/doc/EPHEMERAL_PROFILES.md",
        iconRes = AegisIcons.Ghost,
    ),
    HelpEntry(
        title = "What Aegis Can and Can't Protect",
        subtitle = "Every layer we built — and the one physical limit no software can cross.",
        route = "help/doc/PROTECTION_LIMITS.md",
        iconRes = AegisIcons.Geofence,
    ),
    // An earlier help reorg claimed this in the Security section
    // but never added the entry — the doc shipped orphaned. Wired here so
    // Security reads the intended 5.
    HelpEntry(
        title = "Why Your Password Is Uncrackable",
        subtitle = "Length beats complexity: why a four-word backup passphrase outlasts every GPU.",
        route = "help/doc/WHY_YOUR_PASSWORD_IS_UNCRACKABLE.md",
        iconRes = AegisIcons.Lock,
    ),
)

/**
 * Help entries that are hidden behind the same 7-tap-on-cargo-version
 * gate that unlocks the Experimental settings section. Sentinel
 * (covert intrusion detection) lives here because the feature
 * itself is gated the same way — the docs unlock together with the
 * feature so the user only sees help for things they can actually
 * configure.
 *
 * Add future experimental-only docs to this list. Anything stable
 * goes in HELP_ENTRIES (always visible).
 */
private val HELP_EXPERIMENTAL = listOf(
    HelpEntry(
        title = "Sentinel Mode",
        subtitle = "Covert intrusion detection — three-sensor cascade. Patent filed.",
        route = "help/doc/SENTINEL.md",
        iconRes = AegisIcons.More,
    ),
    HelpEntry(
        title = "Snatch Detection",
        subtitle = "Accelerometer-based grab detection — experimental.",
        route = "help/doc/SNATCH_DETECTION.md",
        iconRes = AegisIcons.More,
    ),
    HelpEntry(
        title = "Crash Detection",
        subtitle = "Vehicle impact detection with 30-second countdown — experimental.",
        route = "help/doc/CRASH_DETECTION.md",
        iconRes = AegisIcons.More,
    ),
)

/**
 * Section partition of [HELP_ENTRIES] for the categorized Help layout
 * (the categorized-help reorg). The reorg's render referenced these four
 * lists but they were never defined — the build broke. Defined here by
 * route so every stable entry lands in exactly one section and the master
 * list stays the single source of truth (add a doc to HELP_ENTRIES and
 * slot its route into one bucket below). All 16 stable entries are
 * covered; HELP_EXPERIMENTAL renders separately behind the 7-tap gate.
 */
private fun List<HelpEntry>.inSection(vararg routes: String): List<HelpEntry> {
    val set = routes.toSet()
    return filter { it.route in set }
}

// Each section is a filtered VIEW of HELP_ENTRIES by route, not a
// separate list — so a doc is registered exactly once (in HELP_ENTRIES)
// and its section is chosen by listing its route in one bucket below.
// Order within a bucket = render order on screen.
private val HELP_BASICS = HELP_ENTRIES.inSection(
    "help/doc/GETTING_STARTED.md",
    "tutorial?replay=true",
    "help/doc/AEGIS_USER_MANUAL.md",
)
private val HELP_SECURITY = HELP_ENTRIES.inSection(
    "help/doc/PRIVACY.md",
    "help/doc/WHY_YOUR_PASSWORD_IS_UNCRACKABLE.md",
    "help/doc/WHY_NO_TOR.md",
    "help/doc/EPHEMERAL_PROFILES.md",
    "help/doc/PROTECTION_LIMITS.md",
)
private val HELP_DESIGN = HELP_ENTRIES.inSection(
    "help/doc/DESIGN_PHILOSOPHY.md",
    "settings/origins",
    "help/doc/WHY_ONE_SECOND.md",
    "help/doc/WHY_NO_GROUP_SOS.md",
    "help/doc/WHY_NO_NONAEGIS_SOS.md",
    "help/doc/WHY_NO_GROUP_DM.md",
    "help/doc/FAMILY_SPEC.md",
)
private val HELP_TECHNICAL = HELP_ENTRIES.inSection(
    "help/doc/ARCHITECTURE.md",
    "help/doc/VERSIONING.md",
    "help/doc/ABOUT_PROJECT_AETHER.md",
)

/**
 * The Help index: a categorized list of doc cards (Basics / Security /
 * Design decisions / Technical), with an Experimental section appended
 * only when the 7-tap unlock is active. Each card navigates to its
 * [HelpEntry.route] — most open a [DocViewerScreen]; a few jump to live
 * screens (tutorial replay, Origins).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(navController: NavController) {
    // Experimental-unlock state — hoisted out of LazyColumn because
    // LazyListScope's item lambdas aren't a Composable context;
    // LocalContext.current / remember / collectAsState can't fire
    // inside them. Single read at the outer scope, used to gate the
    // EXPERIMENTAL section below.
    val expCtx = LocalContext.current
    val expPrefs = remember { app.aether.aegis.prefs.ExperimentalPrefs(expCtx) }
    // Live flow so the Experimental section appears the instant the user
    // completes the 7-tap unlock elsewhere, without leaving Help.
    val expUnlocked by expPrefs.unlockedFlow.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.help_help)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Intro blurb, then four fixed sections each = one header item
            // + the cards from its bucket. Section headers are bare `item`s
            // (not part of the bucket) so an empty bucket would still show
            // its header — acceptable since every bucket is non-empty.
            item {
                Column {
                    Text(
                        stringResource(R.string.help_how_aegis_works),
                        color = AegisCyan,
                        fontSize = 13.sp,
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.help_the_same_documents_that) +
                            "the app so you have them when you need them. Read offline.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            // ── Basics ──
            item {
                Text(
                    stringResource(R.string.help_basics),
                    color = AegisCyan,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            items(HELP_BASICS) { entry ->
                HelpCard(entry) { navController.navigate(entry.route) }
            }
            // ── Security ──
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.help_security),
                    color = AegisCyan,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            items(HELP_SECURITY) { entry ->
                HelpCard(entry) { navController.navigate(entry.route) }
            }
            // ── Design decisions ──
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.help_design_decisions),
                    color = AegisCyan,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            items(HELP_DESIGN) { entry ->
                HelpCard(entry) { navController.navigate(entry.route) }
            }
            // ── Technical ──
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.help_technical),
                    color = AegisCyan,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            items(HELP_TECHNICAL) { entry ->
                HelpCard(entry) { navController.navigate(entry.route) }
            }

            // Experimental help section — gated by the same 7-tap-
            // on-cargo-version unlock that exposes the Experimental
            // section in Settings. Help for experimental features
            // unlocks together with the features themselves so the
            // user only sees docs for things they can actually
            // configure. `expUnlocked` is hoisted to the outer
            // HelpScreen scope (LazyListScope can't host Composable
            // calls).
            if (expUnlocked) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.help_experimental),
                        color = AegisCyan,
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                }
                items(HELP_EXPERIMENTAL) { entry ->
                    HelpCard(entry) { navController.navigate(entry.route) }
                }
            }
        }
    }
}

/** One tappable help entry: hex icon (drawable or text glyph) + title +
 *  subtitle. Icon takes precedence over glyph when both are set. */
@Composable
private fun HelpCard(entry: HelpEntry, onClick: () -> Unit) {
    GlassPanel(modifier = Modifier.clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            HexShape(
                size = 44.dp,
                borderColor = AegisCyan,
                fillColor = AegisPanel,
            ) {
                // Prefer the vector drawable; fall back to a text glyph.
                // An entry with neither renders an empty hex (intentional —
                // no entry currently does that).
                when {
                    entry.iconRes != null -> AegisIcon(
                        icon = entry.iconRes,
                        contentDescription = null,
                        tint = AegisCyan,
                        modifier = Modifier.size(app.aether.aegis.ui.components.hexInnerIcon(44.dp)),
                    )
                    entry.glyph != null -> Text(
                        entry.glyph,
                        color = AegisCyan,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    entry.subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * Renders a single bundled markdown doc from `assets/docs/<filename>`.
 * The title bar derives its label from the filename (strip `.md`,
 * underscores → spaces). The body streams through [MarkdownRender].
 * Docs ship inside the APK so Help works fully offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocViewerScreen(filename: String, navController: NavController) {
    val context = LocalContext.current
    // null = still loading (or load failed → stays null → "Loading…"
    // placeholder shown). Re-keyed on filename so a different doc reloads.
    var raw by remember(filename) { mutableStateOf<String?>(null) }
    LaunchedEffect(filename) {
        // Asset read off the main thread; runCatching swallows a missing
        // file so a bad route degrades to the placeholder rather than
        // crashing.
        raw = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("docs/$filename").bufferedReader().use { it.readText() }
            }.getOrNull()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(filename.removeSuffix(".md").replace('_', ' ')) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        val text = raw
        if (text == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(pad).padding(24.dp),
            ) {
                Text(
                    stringResource(R.string.help_loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                MarkdownRender(text)
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/**
 * Minimal markdown renderer — handles the subset our docs actually
 * use. Each line is classified once; no regex backtracking, no
 * nested-block magic. Good enough that the docs read as docs and not
 * as raw .md text.
 *
 *   `#`/`##`/`###` headings → sized + bold + cyan
 *   `> ` quote               → muted, indented
 *   ``` fenced code blocks  → monospace panel
 *   `- `/`* ` list items     → bullet + body
 *   `**bold**`               → bold span (inline)
 *   `[text](href)`           → just the visible text (links open
 *                              external — handled later if needed)
 *   blank line               → paragraph break
 */
@Composable
private fun MarkdownRender(md: String) {
    val lines = md.lines()
    // Line-at-a-time state machine. `inCode` + `codeBuf` accumulate the
    // body of a fenced block until the closing ```; the manual `i` index
    // (rather than a for-loop) lets the plain-paragraph and table branches
    // below consume multiple lines in one pass.
    var inCode = false
    val codeBuf = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        // Fence toggles code mode. Opening fence: start buffering. Closing
        // fence: flush the buffer as one CodeBlock and reset.
        if (line.startsWith("```")) {
            if (inCode) {
                CodeBlock(codeBuf.toString())
                codeBuf.clear()
                inCode = false
            } else {
                inCode = true
            }
            i++
            continue
        }
        // Inside a fence every line is verbatim — no block dispatch.
        if (inCode) {
            codeBuf.appendLine(line)
            i++
            continue
        }
        when {
            line.startsWith("# ") -> {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    line.removePrefix("# "),
                    color = AegisCyan,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            line.startsWith("## ") -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    line.removePrefix("## "),
                    color = AegisCyan,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            line.startsWith("### ") -> {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    line.removePrefix("### "),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            // Blockquote → muted panel. Single-line only; a multi-line
            // quote renders as one panel per line (acceptable for the
            // short quotes the docs use).
            line.startsWith("> ") -> {
                Surface(
                    color = AegisPanel,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                ) {
                    Text(
                        stripInline(line.removePrefix("> ")),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
            // Unordered list item (either bullet marker) → cyan bullet
            // glyph + body. The bullet itself comes from a string resource.
            line.startsWith("- ") || line.startsWith("* ") -> {
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(stringResource(R.string.tutorial_), color = AegisCyan, fontSize = 13.sp)
                    Text(
                        stripInline(line.removePrefix(if (line.startsWith("- ")) "- " else "* ")),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                    )
                }
            }
            // Blank line → paragraph gap (the body-paragraph joiner below
            // also treats a blank as a block boundary via startsNewBlock).
            line.isBlank() -> {
                Spacer(modifier = Modifier.height(8.dp))
            }
            line.trimStart().startsWith("|") -> {
                // Table row — greedily collect all consecutive | lines into
                // one block so TableBlock can lay out the whole grid at once.
                val tableLines = mutableListOf(line)
                while (i + 1 < lines.size && lines[i + 1].trimStart().startsWith("|")) {
                    i++
                    tableLines.add(lines[i])
                }
                TableBlock(tableLines)
            }
            line.startsWith("---") || line.startsWith("***") || line.startsWith("___") -> {
                // Horizontal rule
                Spacer(modifier = Modifier.height(4.dp))
                androidx.compose.material3.HorizontalDivider(
                    color = AegisBorder,
                    thickness = 1.dp,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            else -> {
                // Merge consecutive plain body lines into ONE paragraph.
                // The .md sources are hard-wrapped at ~70 columns; rendering
                // each source line as its own Text broke every paragraph into
                // stair-stepped fragments instead of letting it wrap to the
                // screen width. Joining the run with spaces lets Compose
                // reflow it naturally. We stop at the next block-level
                // construct so headings / lists / quotes / tables / rules /
                // code fences / numbered items still start fresh.
                val para = StringBuilder(line.trim())
                while (i + 1 < lines.size && !startsNewBlock(lines[i + 1])) {
                    i++
                    para.append(' ').append(lines[i].trim())
                }
                Text(
                    stripInline(para.toString()),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        i++
    }
    if (inCode && codeBuf.isNotEmpty()) {
        // Unterminated fence — render what we have anyway.
        CodeBlock(codeBuf.toString())
    }
}

/** True when [raw] begins a new block-level construct, so the plain-
 *  paragraph joiner in [MarkdownRender] knows to stop accumulating and
 *  let the normal dispatch handle it. Kept in sync with the `when` arms
 *  there — anything NOT matched here is a plain continuation line that
 *  belongs to the current paragraph. */
private fun startsNewBlock(raw: String): Boolean {
    if (raw.isBlank()) return true
    val t = raw.trimStart()
    return raw.startsWith("#") ||
        raw.startsWith("> ") ||
        raw.startsWith("- ") || raw.startsWith("* ") ||
        raw.startsWith("```") ||
        raw.startsWith("---") || raw.startsWith("***") || raw.startsWith("___") ||
        t.startsWith("|") ||
        // ordered-list item: "1. ", "2) ", …
        Regex("^\\d+[.)] ").containsMatchIn(t)
}

/** Strip the most common inline markdown so paragraphs read cleanly.
 *  We don't recompose into styled spans — that'd require AnnotatedString
 *  per paragraph and the docs read fine without bold/italic emphasis. */
private fun stripInline(s: String): String =
    s.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        .replace(Regex("""__(.+?)__"""), "$1")
        .replace(Regex("""\*(.+?)\*"""), "$1")
        .replace(Regex("""_(.+?)_"""), "$1")
        .replace(Regex("""`([^`]+?)`"""), "$1")
        .replace(Regex("""\[(.+?)]\((.+?)\)"""), "$1")

/** Renders a markdown pipe-table. First data row is treated as the
 *  header (cyan + bold, divider beneath); the GFM `|---|` separator row
 *  is dropped. Cells share width equally (weight 1f) — no column sizing,
 *  which is fine for the small reference tables the docs use. */
@Composable
private fun TableBlock(lines: List<String>) {
    // Parse markdown table: split by |, trim, skip separator rows (|---|)
    val rows = lines
        .filter { !it.matches(Regex("""^\|[-|: ]+\|$""")) }  // skip separator
        .map { row ->
            // drop(1)/dropLast(1) discard the empty strings either side of
            // the leading/trailing pipe so cell indices line up.
            row.split("|")
                .drop(1)                       // leading empty
                .dropLast(1)                    // trailing empty
                .map { it.trim() }
        }
        .filter { it.isNotEmpty() }

    if (rows.isEmpty()) return

    Surface(
        color = AegisPanel,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AegisBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Row 0 is the header (separator already filtered out), so it
            // gets bold cyan styling and a divider beneath it.
            rows.forEachIndexed { idx, cells ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                ) {
                    cells.forEach { cell ->
                        Text(
                            stripInline(cell),
                            modifier = Modifier.weight(1f),
                            color = if (idx == 0) AegisCyan
                                    else MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontWeight = if (idx == 0) FontWeight.Bold
                                         else FontWeight.Normal,
                        )
                    }
                }
                if (idx == 0) {
                    androidx.compose.material3.HorizontalDivider(
                        color = AegisBorder,
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** A fenced-code-block panel: monospace text in a bordered glass surface.
 *  Trailing newline trimmed so the closing fence doesn't leave a blank
 *  line. No syntax highlighting — the docs only use code blocks for
 *  short literal snippets. */
@Composable
private fun CodeBlock(text: String) {
    Surface(
        color = AegisPanel,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AegisBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text.trimEnd(),
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

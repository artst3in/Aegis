package app.aether.aegis.storage

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manual cleanup pass for the data sinks that grew unbounded —
 * primarily the auto-update APK stash and the mugshot directory,
 * which together can balloon Aegis's `/data/data/<pkg>/files/`
 * footprint into the gigabytes range (user-reported 6.5 GB on a
 * device running 2026.05.674, mostly stale update.apk copies and
 * uncapped mugshot writes from the rapid-release storm).
 *
 * Purpose-built for the bridge-build migration flow: trim the data
 * dir to something that fits in a portable backup ZIP before the
 * user kicks off "Create backup file". Also wired into the
 * Diagnostics screen so the cleanup can run independently of a
 * backup operation.
 *
 * Returns per-call counts + bytes reclaimed for the Diagnostics UI;
 * never throws, every operation is best-effort and individual
 * failures are logged.
 */
object StorageCleanup {

    private const val TAG = "StorageCleanup"

    /** Default cap for mugshot retention. Each JPEG ~100–300 KB; 50
     *  files = ~10 MB max, plenty of forensic history without
     *  unbounded growth. */
    const val MUGSHOT_KEEP_DEFAULT = 50

    /** Diag log retention in days. The DiagLog ring buffer in memory
     *  is bounded, but any on-disk dumps older than this get nuked. */
    const val DIAG_KEEP_DAYS_DEFAULT = 14

    data class Report(
        val updateApksFreed: Long,
        val mugshotsFreed: Long,
        val diagLogsFreed: Long,
        val cacheFreed: Long,
    ) {
        val total: Long get() =
            updateApksFreed + mugshotsFreed + diagLogsFreed + cacheFreed
    }

    /** Run the full cleanup pass. Idempotent — re-running it is a
     *  no-op once everything is at the target state. */
    fun runFull(context: Context, mugshotKeep: Int = MUGSHOT_KEEP_DEFAULT): Report {
        val apks = pruneUpdateApks(context)
        val mugs = pruneMugshots(context, mugshotKeep)
        val diag = pruneDiagLogs(context, DIAG_KEEP_DAYS_DEFAULT)
        val cache = pruneCache(context)
        val r = Report(apks, mugs, diag, cache)
        Log.i(
            TAG,
            "cleanup: apks=${apks}B mugshots=${mugs}B diag=${diag}B cache=${cache}B total=${r.total}B",
        )
        return r
    }

    /** Wipe `update.apk` + `previous.apk`. Worker re-downloads next
     *  cycle if a real new release is available, so deletion is safe.
     *  Returns total bytes reclaimed. */
    fun pruneUpdateApks(context: Context): Long {
        var freed = 0L
        for (name in listOf("update.apk", "previous.apk")) {
            val f = File(context.filesDir, name)
            if (f.exists()) {
                val sz = f.length()
                if (f.delete()) freed += sz
            }
        }
        return freed
    }

    /** Keep the [maxKeep] most-recent mugshots, delete the rest.
     *  filesDir/mugshots/ has historically had NO cap — every
     *  wrong-PIN + remote LOCATE writes a fresh JPEG. */
    fun pruneMugshots(context: Context, maxKeep: Int): Long {
        val dir = File(context.filesDir, "mugshots")
        if (!dir.exists()) return 0L
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: return 0L
        if (files.size <= maxKeep) return 0L
        var freed = 0L
        files.drop(maxKeep).forEach { f ->
            val sz = f.length()
            if (f.delete()) freed += sz
        }
        return freed
    }

    /** Delete diag log files older than [keepDays]. */
    fun pruneDiagLogs(context: Context, keepDays: Int): Long {
        val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
        var freed = 0L
        for (subDir in listOf("diag", "logs")) {
            val dir = File(context.filesDir, subDir)
            if (!dir.exists()) continue
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                if (f.lastModified() < cutoff) {
                    val sz = f.length()
                    if (f.delete()) freed += sz
                }
            }
        }
        // Also: startup-errors.log at the root if huge.
        val startupLog = File(context.filesDir, "startup-errors.log")
        if (startupLog.exists() && startupLog.length() > 1_000_000L) {
            val sz = startupLog.length()
            if (startupLog.delete()) freed += sz
        }
        return freed
    }

    /** Cache dir is Android's auto-purgeable space, but a long-
     *  running process may have accumulated decrypted vault scratch
     *  files + log exports + Coil bitmap cache. Clear unconditionally
     *  — same effect as Settings → Apps → Aegis → Clear cache. */
    fun pruneCache(context: Context): Long {
        val dir = context.cacheDir
        if (!dir.exists()) return 0L
        var freed = 0L
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val sz = f.length()
            if (f.delete()) freed += sz
        }
        return freed
    }

    data class OrphanReport(val deletedFiles: Int, val freed: Long)

    data class PurgeReport(val deletedRows: Int, val freed: Long)

    /** Purge every messages row whose attachment is a timestamped
     *  video — file name `video_YYYYMMDD_HHMMSS[_N].{mp4,mov,webm,
     *  m4v,3gp,mkv}` (any case on the extension). Conservative: only
     *  the camera-naming pattern. */
    suspend fun purgeSOSEvidenceVideos(context: Context): PurgeReport {
        val app = app.aether.aegis.AegisApp.instance
        val nameRegex = Regex(
            """video_\d{8}_\d{6}(_\d+)?\.(mp4|mov|webm|m4v|3gp|mkv)""",
            RegexOption.IGNORE_CASE,
        )
        val (rows, freed) = app.repository.purgeMessageAttachmentsMatching(
            extLike = "%/video_%",
            nameRegex = nameRegex,
        )
        return PurgeReport(rows, freed)
    }

    /** Aggressive purge: nukes every messages row in app_files/ whose
     *  attachment is ANY recognizable video format, irrespective of
     *  the filename. Use when the conservative purge above leaves
     *  behind files because their name doesn't match the timestamp
     *  pattern (custom-renamed clips, share-target imports, weird
     *  naming from a third-party group bridge). User has already
     *  reviewed the inspector by this point and chosen to nuke. */
    suspend fun purgeAllVideoAttachments(context: Context): PurgeReport {
        val app = app.aether.aegis.AegisApp.instance
        // Catch every recognised video extension, any case.
        val nameRegex = Regex(
            """.+\.(mp4|mov|webm|m4v|3gp|mkv|avi|wmv|flv|mts|m2ts)""",
            RegexOption.IGNORE_CASE,
        )
        val (rows, freed) = app.repository.purgeMessageAttachmentsMatching(
            extLike = "%",  // walk every messages row with non-null attachmentPath
            nameRegex = nameRegex,
        )
        return PurgeReport(rows, freed)
    }

    /** Single entry in the app_files/ inspector list. */
    data class AppFileEntry(
        val relativePath: String,
        val absolutePath: String,
        val sizeBytes: Long,
        /** True when at least one messages / stories / secure_notes
         *  row's attachmentPath points at this file's absolute path.
         *  False = orphan (would be removed by [pruneOrphanAppFiles]). */
        val referenced: Boolean,
        /** Display name of the contact the referencing message row
         *  is to/from, when we can resolve it. Null when the file
         *  is an orphan, or the row's peer isn't a known contact, or
         *  the row is a story / secure-note (no peer concept). */
        val peerLabel: String?,
        /** The MIME type for ACTION_VIEW preview. video/mp4 for the
         *  sos-shaped names; bytes are guessed from extension. */
        val mime: String,
    )

    /** Top [n] files in profileRoot.attachmentsDir by size, with a
     *  flag for whether each is referenced by a DB row. Used by the
     *  Diagnostics StorageCard inspector after the orphan sweep leaves
     *  the dir still bloated — the largest referenced files
     *  pinpoint either a single oversized attachment or a pattern
     *  worth targeting with a more aggressive sweep. */
    suspend fun listTopAppFiles(context: Context, n: Int = 20): List<AppFileEntry> {
        val app = app.aether.aegis.AegisApp.instance
        val attachmentsDir = app.profileRoot.attachmentsDir
        if (!attachmentsDir.exists()) return emptyList()
        val referenced = runCatching {
            app.repository.allReferencedAttachmentPaths()
        }.getOrDefault(emptySet())
        // Build a path → peer-label lookup so each entry can say who
        // the conversation was with. messages.peerKey only — stories
        // and secure_notes have no peer concept.
        val peerByPath: Map<String, String> = runCatching {
            app.repository.attachmentPathPeerLabels()
        }.getOrDefault(emptyMap())
        val all = attachmentsDir.walkTopDown()
            .filter { it.isFile }
            .map { f ->
                val abs = f.absolutePath
                AppFileEntry(
                    relativePath = f.relativeTo(attachmentsDir).path,
                    absolutePath = abs,
                    sizeBytes = f.length(),
                    referenced = abs in referenced,
                    peerLabel = peerByPath[abs],
                    mime = mimeForName(f.name),
                )
            }
            .sortedByDescending { it.sizeBytes }
            .take(n)
            .toList()
        return all
    }

    private fun mimeForName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".webm")                          -> "video/webm"
            lower.endsWith(".3gp")                           -> "video/3gpp"
            lower.endsWith(".mkv")                           -> "video/x-matroska"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png")                           -> "image/png"
            lower.endsWith(".gif")                           -> "image/gif"
            lower.endsWith(".webp")                          -> "image/webp"
            lower.endsWith(".m4a") || lower.endsWith(".aac") -> "audio/mp4"
            lower.endsWith(".mp3")                           -> "audio/mpeg"
            lower.endsWith(".ogg")                           -> "audio/ogg"
            lower.endsWith(".pdf")                           -> "application/pdf"
            else                                             -> "*/*"
        }
    }

    /** Walk profileRoot.attachmentsDir and delete every file whose
     *  absolute path is NOT referenced by any messages / secure_notes
     *  / stories row. Safe by construction: any attachment the UI
     *  could still try to render is kept.
     *
     *  Targets the 2026.05.693 user-reported case where app_files/
     *  ballooned past 5 GB despite the chat history being only a few
     *  messages — orphans from failed downloads, pre-sealing copies,
     *  and SimpleX core retries accumulate here. */
    suspend fun pruneOrphanAppFiles(context: Context): OrphanReport {
        val appCtx = context.applicationContext
        val app = app.aether.aegis.AegisApp.instance
        val attachmentsDir = app.profileRoot.attachmentsDir
        if (!attachmentsDir.exists()) return OrphanReport(0, 0L)

        // Gather every absolute path the DB still points at. Anything
        // outside this set in the on-disk tree is orphan + safe to
        // delete.
        val referenced = runCatching {
            app.repository.allReferencedAttachmentPaths()
        }.getOrDefault(emptySet())

        var freed = 0L
        var deleted = 0
        attachmentsDir.walkTopDown().filter { it.isFile }.forEach { f ->
            if (f.absolutePath !in referenced) {
                val sz = f.length()
                if (f.delete()) {
                    freed += sz
                    deleted++
                }
            }
        }
        return OrphanReport(deleted, freed)
    }

    /** Recursive byte count for [dir]. Cheap on warm caches, can take
     *  a few seconds on large trees (.walkTopDown does a full
     *  traversal). Always returns 0 if the dir doesn't exist. */
    fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    /** Snapshot of the major contributors for the Diagnostics card.
     *  Returns labelled (path, byte) pairs sorted descending. Walks
     *  into profiles/<id>/ subdirs so the user can see exactly which
     *  attachment / DB / vault tree is the bloat — a flat
     *  "profiles/ = N GB" row hides the structure that matters. */
    fun breakdown(context: Context): List<Pair<String, Long>> {
        val pairs = mutableListOf<Pair<String, Long>>()
        pairs += "update.apk" to (File(context.filesDir, "update.apk").length().coerceAtLeast(0))
        pairs += "previous.apk" to (File(context.filesDir, "previous.apk").length().coerceAtLeast(0))
        pairs += "mugshots/" to dirSize(File(context.filesDir, "mugshots"))
        // Walk into profiles/<id>/ rather than rolling up the whole
        // tree. Knowing whether the row is "app_files/" vs "chat_enc/"
        // vs "databases/" is what tells the user whether the bloat
        // is real attachments, sealed-attachment duplicates, or an
        // un-checkpointed DB WAL.
        val profilesDir = File(context.filesDir, "profiles")
        if (profilesDir.exists()) {
            profilesDir.listFiles()?.filter { it.isDirectory }?.forEach { profile ->
                profile.listFiles()?.forEach { entry ->
                    val label = "profiles/${profile.name}/${entry.name}" +
                        if (entry.isDirectory) "/" else ""
                    val sz = if (entry.isDirectory) dirSize(entry) else entry.length()
                    if (sz > 0) pairs += label to sz
                }
            }
        }
        pairs += "diag/" to dirSize(File(context.filesDir, "diag"))
        pairs += "cache/" to dirSize(context.cacheDir)
        pairs += "simplex DB" to (
            File(context.filesDir, "simplex_v1_agent.db").length().coerceAtLeast(0) +
            File(context.filesDir, "simplex_v1_chat.db").length().coerceAtLeast(0)
        )
        // Inflight transfers — aborted SimpleX downloads can squat
        // here indefinitely if cleanup didn't fire.
        pairs += "simplex inflight" to (
            dirSize(File(context.filesDir, "simplex_v1_files")) +
            dirSize(File(context.filesDir, "inflight"))
        )
        return pairs.filter { it.second > 0 }.sortedByDescending { it.second }
    }
}

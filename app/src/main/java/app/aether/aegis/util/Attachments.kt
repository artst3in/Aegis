package app.aether.aegis.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/**
 * Copies a content://URI returned from a picker into a stable on-disk
 * path SimpleX's Haskell core can read. We never let the core touch the
 * raw URI because it ages out as soon as the picker activity finishes.
 */
object Attachments {

    data class Local(
        val path: String,
        val mime: String,
        val size: Long,
        val name: String?,
    )

    fun import(context: Context, uri: Uri, fallbackMime: String? = null): Local? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: fallbackMime ?: "application/octet-stream"
        val (displayName, size) = readMeta(context, uri)
        // Stage under the ORIGINAL display name so it survives the wire:
        // SimpleX transmits the basename of the file path, the receiver
        // shows it, and Android infers the MIME from its extension. A
        // UUID-only name dropped the real filename AND, via a truncated
        // mime-subtype "extension" (e.g. application/vnd.android.package-
        // archive → ".vnd.andr"), left receivers with an unopenable
        // octet-stream blob. Sanitise only path-breaking characters; keep
        // spaces/parens (valid on Android FS, fine inside the /_send JSON).
        // Fall back to a UUID name only when the picker gives us nothing.
        val safeName = displayName
            ?.map { c -> if (c == '/' || c.code == 92 || c.isISOControl()) '_' else c }
            ?.joinToString("")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val ext = mime.substringAfter('/', "bin").take(8)   // fallback only
        val fileName = safeName ?: "${UUID.randomUUID()}.$ext"
        // Don't clobber a previous send of the same-named file — insert
        // " (n)" before the extension so both copies survive on disk.
        val target = uniqueFile(attachmentsDir(context), fileName)
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return null
            Local(
                path = target.absolutePath,
                mime = mime,
                size = target.length().takeIf { it > 0 } ?: size ?: 0L,
                name = displayName,
            )
        }.getOrNull()
    }

    /** Resolve a non-colliding file in [dir] for [name], inserting " (n)"
     *  before the extension when needed so two sends of "report.pdf" don't
     *  overwrite each other while keeping the human-readable name. */
    private fun uniqueFile(dir: File, name: String): File {
        val initial = File(dir, name)
        if (!initial.exists()) return initial
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = File(dir, "$base ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }

    /**
     * Per-profile attachments directory. Mirrors simplex-chat's
     * appFilesDir layout — picked files MUST land inside the
     * directory we declared to the SimpleX core (via /set file paths
     * appFilesFolder=...), otherwise the core's sandbox rejects sends.
     *
     * Phase 1 multi-profile: this is the *current* profile's app_files.
     */
    fun attachmentsDir(context: Context): File =
        app.aether.aegis.profile.ProfileRegistry.get(context).current.attachmentsDir

    private fun readMeta(context: Context, uri: Uri): Pair<String?, Long?> {
        return context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null to null
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
            val si = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
            (ni?.let { c.getString(it) }) to (si?.let { c.getLong(it) })
        } ?: (null to null)
    }
}

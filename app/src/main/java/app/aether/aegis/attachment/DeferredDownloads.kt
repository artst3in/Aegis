package app.aether.aegis.attachment

import android.content.Context

/**
 * Durable map from a deferred attachment's chat-item id to its SimpleX
 * `fileId`, so a tap-to-download can pull the file later — including after
 * process death.
 *
 * WHY persist (not just hold in memory): the chat row for a deferred file is
 * written at INVITATION time with `attachmentPath == null`. The only thing
 * needed to later turn that placeholder into a real download is the
 * `fileId`, which the core assigned to the invitation. If the process dies
 * between invitation and tap (very normal on Android — the app is killed
 * constantly), an in-memory map would lose it and the placeholder would be
 * a dead end. SharedPreferences survives, so the button still works after a
 * cold start.
 *
 * Caveat handled by the caller, not here: surviving the `fileId` does NOT
 * guarantee the SimpleX-side invitation survived. If `/freceive` later fails
 * (expired, or core state lost across a cold restart), the caller degrades
 * the placeholder to "Unavailable" and calls [remove]. This store only
 * remembers the id; it makes no promise the id still resolves.
 *
 * Keyed by the chat item's `simplexItemId` (the same id the placeholder row
 * carries and that the completion event reports), so lookup from a rendered
 * message is a direct hit.
 */
object DeferredDownloads {

    private const val STORE = "aegis_deferred_downloads"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** Remember that [itemId]'s attachment was deferred and is pullable via
     *  [fileId]. Overwrites any prior entry for the same item. */
    fun put(context: Context, itemId: Long, fileId: Long) {
        prefs(context).edit().putLong(itemId.toString(), fileId).apply()
    }

    /** The `fileId` to `/freceive` for a deferred [itemId], or null if this
     *  item was never deferred / has already been pulled or cleared. */
    fun fileIdFor(context: Context, itemId: Long): Long? {
        val p = prefs(context)
        val key = itemId.toString()
        return if (p.contains(key)) p.getLong(key, -1L).takeIf { it > 0 } else null
    }

    /** Drop the entry once the file has downloaded (completion) or proven
     *  unpullable (failed `/freceive`). Idempotent. */
    fun remove(context: Context, itemId: Long) {
        prefs(context).edit().remove(itemId.toString()).apply()
    }
}

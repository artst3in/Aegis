package app.aether.aegis.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import app.aether.aegis.core.Message
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Body preview text for the chat-list contact row.
 *  - Attachments → "Photo" / "Video" / "Voice" / filename
 *  - Outgoing messages prefix "You: "
 *  - Text body truncated at 60 chars.
 *
 * Plain text only — vector icons can't be inlined into a Compose
 * Text overload that takes a String, so the previous "📷 / 🎬 /
 * 🎙 / 📎" emoji prefixes were dropped per the user's "drop
 * completely if can't be LunaGlass" rule.
 */
internal fun lastMsgPreview(msg: Message, selfKey: String): String {
    // Control / evidence envelopes (e.g. the SOS-time
    // [aegis:sos-audio] audio-chunk + [aegis:sos-frame] camera fan-out)
    // carry a non-blank "[aegis:…]" caption, so the plain
    // content.take(60) path used to leak the raw tag into the contact's
    // last-message preview. Treat any [aegis:…] body as control: show the
    // attachment TYPE if it has one, otherwise nothing — never the tag.
    val isControl = msg.content.startsWith("[aegis:")
    val body = when {
        msg.attachmentPath != null && (msg.content.isBlank() || isControl) -> when {
            msg.attachmentMime?.startsWith("image/") == true -> "Photo"
            msg.attachmentMime?.startsWith("video/") == true -> "Video"
            msg.attachmentMime?.startsWith("audio/") == true -> "Voice"
            else -> msg.attachmentName ?: "File"
        }
        isControl -> ""
        else -> msg.content.take(60)
    }
    if (body.isEmpty()) return ""
    val youPrefix = if (msg.from == selfKey) "You: " else ""
    return youPrefix + body
}

internal fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

private val MSG_TIME = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault())
internal fun formatMessageTime(ts: Long): String = MSG_TIME.format(java.util.Date(ts))

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
}

internal fun ageString(ts: Long): String {
    val delta = (System.currentTimeMillis() - ts) / 1000
    return when {
        delta < 60 -> "${delta}s ago"
        delta < 3600 -> "${delta / 60}m ago"
        delta < 86400 -> "${delta / 3600}h ago"
        else -> "${delta / 86400}d ago"
    }
}

internal fun copyToClipboard(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

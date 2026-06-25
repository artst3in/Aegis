package app.aether.aegis.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.aegis.R
import app.aether.aegis.simplex.SimpleXTransport
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisCyanGlowSoft

/*
 * Shared reaction UI — the SINGLE source of truth for how reactions look
 * and parse, used by both the 1:1 chat (ChatScreen) and group chat
 * (GroupChatScreen). Reactions ride the Aegis signed protocol (see
 * SimpleXTransport.sendSignedReaction), so any emote is allowed; the
 * storage format is the reactor SET
 *   {"<emote>": ["me", "<peerKey>", …]}
 * where "me" (SimpleXTransport.REACTOR_SELF) marks the local user. The
 * screens own only the SEND call (which differs by transport); everything
 * visual lives here so the two chats can never drift apart.
 */

/**
 * Default one-tap reaction palette. Not a closed set — the "＋" affordance
 * in [ReactionPickerRow] reaches any typed emoji — just the shortcuts.
 */
val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "🔥", "😮", "😢", "🙏", "🎉")

/** One reaction chip: the emote, how many reacted, and whether the local
 *  user is one of them. */
data class ReactionChipData(val emote: String, val count: Int, val mine: Boolean)

/**
 * Parse the reactor-set store into display chips, most-reacted first.
 * Tolerant of null / malformed JSON (returns empty). [mine] is true when
 * the local user's reserved id is in the emote's reactor set.
 */
fun parseReactionChips(json: String?): List<ReactionChipData> {
    if (json.isNullOrBlank()) return emptyList()
    val obj = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return emptyList()
    val out = ArrayList<ReactionChipData>()
    obj.keys().forEach { emote ->
        val arr = obj.optJSONArray(emote) ?: return@forEach
        var mine = false
        for (i in 0 until arr.length()) {
            if (arr.optString(i) == SimpleXTransport.REACTOR_SELF) mine = true
        }
        if (arr.length() > 0) out.add(ReactionChipData(emote, arr.length(), mine))
    }
    return out.sortedByDescending { it.count }
}

/** Emotes the local user has already placed on a message — drives the
 *  picker highlight and toggle direction. */
fun myReactionEmotes(json: String?): Set<String> =
    parseReactionChips(json).filter { it.mine }.map { it.emote }.toSet()

/**
 * The reaction palette shown inside a message's long-press menu: a
 * one-tap row of [QUICK_REACTIONS] (the local user's already-placed ones
 * highlighted) plus a "＋" that opens a custom-emote entry. [onReact]
 * receives the emote and whether this is an ADD (false = retract an
 * existing reaction). [onCustom] opens the custom-emote sheet.
 */
@Composable
fun ReactionPickerRow(
    reactionsJson: String?,
    onReact: (emote: String, add: Boolean) -> Unit,
    onCustom: () -> Unit,
) {
    val mine = remember(reactionsJson) { myReactionEmotes(reactionsJson) }
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QUICK_REACTIONS.forEach { emote ->
            val isMine = mine.contains(emote)
            Text(
                emote,
                fontSize = 22.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .then(if (isMine) Modifier.background(AegisCyanGlowSoft) else Modifier)
                    .clickable { onReact(emote, !isMine) }
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        Text(
            "＋",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = AegisCyan,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { onCustom() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * The reaction chips rendered under a bubble. Each chip shows the emote
 * and (when >1) its count; the local user's own reactions get a cyan
 * highlight + border. Tapping a chip toggles the user's reaction for that
 * emote via [onToggle] (emote, add) — the same affordance as the picker
 * without reopening the menu. Renders nothing when there are no chips.
 */
@Composable
fun ReactionChipsRow(
    reactionsJson: String?,
    onToggle: (emote: String, add: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = remember(reactionsJson) { parseReactionChips(reactionsJson) }
    if (chips.isEmpty()) return
    Row(modifier = modifier.horizontalScroll(rememberScrollState())) {
        chips.forEach { chip ->
            Surface(
                color = if (chip.mine) AegisCyanGlowSoft else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                border = if (chip.mine) BorderStroke(1.dp, AegisCyan) else null,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onToggle(chip.emote, !chip.mine) },
            ) {
                Text(
                    if (chip.count > 1) "${chip.emote} ${chip.count}" else chip.emote,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Custom-emote entry sheet: lets the user type ANY emoji to react with
 * (Aegis reactions aren't restricted to a fixed set). Caller gates
 * visibility and supplies [onPick] (the trimmed emote) + [onDismiss].
 */
@Composable
fun CustomEmoteDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_react_custom_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.chat_react_custom_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = typed,
                    // Cap to a single short emote — a reaction is one
                    // glyph, not a sentence.
                    onValueChange = { typed = it.take(8) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val emote = typed.trim()
                onDismiss()
                if (emote.isNotEmpty()) onPick(emote)
            }) { Text(stringResource(R.string.chat_react_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

package app.aether.aegis.ui.screens

import app.aether.aegis.AegisApp
import app.aether.aegis.data.SecureNoteEntity
import app.aether.aegis.vault.VaultAttachmentCrypto
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Forward-vault-entry-to-chat picker. Shows a list of paired
 * contacts; tap one to ship the entry as a chat message.
 *
 * Mechanics depend on the entry's shape:
 *   text only → protocolManager.sendMessage(peer, body, TEXT)
 *   attachment + optional caption → SimpleXTransport.
 *     sendFileToContact(peer, plaintextPath, isImage, caption=body)
 *
 * For attachments encrypted in the vault (path ends in .enc), the
 * file is first decrypted to a temp under cacheDir/vault_dec via
 * VaultAttachmentCrypto.decryptToTemp; the temp is the path
 * actually shipped through SimpleX. cleanup runs on
 * activity-resume + vault-lock per the existing sweep.
 *
 * The recipient gets plaintext on their end — they don't have the
 * vault key, and forwarding into a chat is an explicit "share this
 * with someone" action. The vault's original encrypted copy stays
 * intact.
 *
 * @param note the (already-decrypted) vault entry to forward; its
 *   [SecureNoteEntity.body] is plaintext here because the Repository's
 *   observeSecureNotes mapping decrypts it before the UI sees it.
 * @param onDismiss closes the dialog without sending.
 * @param onSent invoked once a send to one peer SUCCEEDS; the caller
 *   typically dismisses on this. Not called on failure (the dialog
 *   stays open showing the error so the user can retry / pick another
 *   peer).
 */
@Composable
fun ForwardVaultEntryDialog(
    note: SecureNoteEntity,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val context = LocalContext.current
    // Paired 1:1 contacts only — group forwarding isn't offered here.
    // Hot Flow, so the picker stays current if a pairing completes
    // while the dialog is open.
    val peers by AegisApp.instance.repository.observeKnownPeers()
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    // Compose state: true for the whole duration of one in-flight send.
    // Gates dismissal, disables every row's click, and flips the cancel
    // button label so a slow attachment send can't be double-fired or
    // dismissed mid-flight.
    var sending by remember { mutableStateOf(false) }
    // Compose state: last send-failure message, or null when clean.
    // Rendered in the error colour at the top of the list.
    var status by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        // Refuse the back-press / scrim dismiss while a send is in
        // flight — see [sending].
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(stringResource(R.string.forward_vault_entry_dialog_send_to_contact)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                status?.let { s ->
                    Text(s, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                if (peers.isEmpty()) {
                    Text(
                        stringResource(R.string.forward_vault_entry_dialog_no_paired_contacts_yet) +
                            "via Chats → +.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                } else {
                    LazyColumn(
                        // Cap the picker height so a large contact list
                        // doesn't push the dialog off-screen; it scrolls
                        // within this bound.
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        // Keyed on publicKey so row identity is stable across
                        // Flow re-emits.
                        items(peers, key = { it.publicKey }) { peer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // Tap-to-send: there is no separate confirm
                                    // step. Disabled while another send is in
                                    // flight to prevent a second concurrent send.
                                    .clickable(enabled = !sending) {
                                        sending = true
                                        status = null
                                        scope.launch {
                                            // Send off the main thread — attachment
                                            // forwards decrypt + hand a file to
                                            // SimpleX, neither of which may block UI.
                                            val ok = withContext(Dispatchers.IO) {
                                                forwardOne(note, peer.publicKey, context)
                                            }
                                            sending = false
                                            if (ok) onSent()
                                            else status = "Send failed — try again."
                                        }
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        peer.displayName,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        // Truncated key fingerprint as a
                                        // disambiguator under the nickname.
                                        peer.publicKey.take(24) + "…",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Single-tap-to-send replaces a confirm button; just
            // dismiss when done.
            TextButton(onClick = { if (!sending) onDismiss() }) {
                Text(if (sending) "Sending…" else stringResource(R.string.secure_notes_cancel))
            }
        },
    )
}

/**
 * Send one vault entry to one peer. Returns true on success, false on
 * any failure (blank text-only body, missing transport, decrypt miss,
 * or a thrown send). Never throws — every failure path collapses to
 * false so the caller can simply surface "try again".
 *
 * Two shapes, decided by whether the entry carries an attachment:
 *   - text-only → protocolManager.sendMessage(TEXT).
 *   - attachment (+ optional caption) → SimpleXTransport.sendFileToContact.
 *
 * Runs on Dispatchers.IO (caller's responsibility) because the
 * attachment branch decrypts a file and hands it to the transport.
 */
private suspend fun forwardOne(
    note: SecureNoteEntity,
    peerPubkey: String,
    context: android.content.Context,
): Boolean {
    val attachmentPath = note.attachmentPath
    val body = note.body  // already decrypted via Repository.observeSecureNotes mapping
    val aegisApp = AegisApp.instance

    return if (attachmentPath == null) {
        // Text-only forward. A blank body has nothing to send → fail
        // rather than ship an empty message.
        if (body.isBlank()) false
        else runCatching {
            aegisApp.protocolManager.sendMessage(
                to = peerPubkey,
                content = body,
                type = app.aether.aegis.core.MessageType.TEXT,
            )
        }.isSuccess
    } else {
        // Attachment forward. Decrypt to a temp file if encrypted;
        // pass the resulting plaintext path to SimpleX. The temp
        // gets swept on the next clearDecryptCache call.
        val plaintextPath = if (VaultAttachmentCrypto.isEncrypted(attachmentPath)) {
            // Vault-encrypted (.enc) attachment: decrypt to a temp under
            // cacheDir/vault_dec and ship THAT. A null here means the
            // decrypt failed (wrong key / gone) — abort the send.
            aegisApp.repository.decryptVaultAttachmentForView(attachmentPath, context)
                ?: return false
        } else attachmentPath
        // Default to a generic binary type when the entry has no recorded
        // MIME; only an explicit image/* type opts into image handling.
        val mime = note.attachmentMime ?: "application/octet-stream"
        val isImage = mime.startsWith("image/")
        // File sends go through SimpleX specifically; if no SimpleX
        // transport is live there's no path to deliver an attachment.
        val simplex = aegisApp.transports
            .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
            .firstOrNull() ?: return false
        runCatching {
            simplex.sendFileToContact(
                peerPubkey = peerPubkey,
                filePath = plaintextPath,
                isImage = isImage,
                caption = body,
            )
        }.getOrDefault(false)
    }
}

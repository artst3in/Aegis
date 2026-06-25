package app.aether.aegis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import app.aether.aegis.AegisApp
import app.aether.aegis.R
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.AegisTopBar
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import kotlinx.coroutines.launch

/**
 * The dedicated home for unused 1:1 invite links — moved OUT of the chat list.
 *
 * These were previously a section pinned above the contact list. On a fresh
 * install (no contacts yet) the full-screen "add a contact" empty-state mascot
 * and the floating add-contact hex both occupy the same space, and the pending
 * rows collided with them (user report). Invite links are a transient "to-do",
 * not a conversation, so they belong with the other actionable items: the
 * Alert Center surfaces a single entry that deep-links here.
 *
 * Duress: real outstanding links must NEVER surface under a decoy unlock, the
 * same rule the chat list applies — under duress this screen shows the empty
 * state and the Alert Center omits the entry entirely, so an attacker browsing
 * the decoy never learns a real link exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingInvitationsScreen(navController: NavController) {
    // Suppress under duress — identical gate to ChatListScreen's shownPending.
    val inDuress = AegisApp.instance.lockState.inDuressMode
    val allPending by AegisApp.instance.repository
        .observePendingInvitations()
        .collectAsState(initial = emptyList())
    val pending = if (inDuress) emptyList() else allPending
    val scope = rememberCoroutineScope()
    // The transport that owns the relay-side invitation; null when SimpleX
    // isn't up, in which case we still clear the row locally on revoke (it
    // would otherwise be unrevokable).
    val simplexTransport = remember {
        AegisApp.instance.transports
            .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
            .firstOrNull()
    }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = { Text(stringResource(R.string.chat_list_pending_invitations)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
            )
        },
    ) { pad ->
        if (pending.isEmpty()) {
            // Calm empty state — no outstanding links is the normal,
            // healthy condition, so this reads as reassurance not absence.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.pending_invitations_none),
                    color = AegisOnSurfaceDim,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "pending-invites-intro") {
                    Text(
                        stringResource(R.string.pending_invitations_intro),
                        color = AegisOnSurfaceDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(pending, key = { "pending-${it.connId}" }) { inv ->
                    PendingInvitationRow(
                        invitation = inv,
                        onRevoke = {
                            // Revoke on the relay + drop the row. If the
                            // transport is down we still clear locally.
                            scope.launch {
                                simplexTransport?.revokePendingInvitation(inv.connId)
                                    ?: AegisApp.instance.repository
                                        .removePendingInvitation(inv.connId)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * One row: an unused 1:1 invite link with its temporary label, age, and a
 * frictionless Revoke action (no confirm dialog — revoking is low-risk and
 * regenerating a link is cheap).
 */
@Composable
private fun PendingInvitationRow(
    invitation: app.aether.aegis.data.PendingInvitationEntity,
    onRevoke: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(invitation.label, fontWeight = FontWeight.Medium)
        },
        supportingContent = {
            Text(
                "Created ${relativePendingTime(invitation.createdAt)} · unused link",
                color = AegisOnSurfaceDim,
                fontSize = 11.sp,
            )
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(48.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "⧗", // hourglass-ish glyph — "pending"
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        },
        trailingContent = {
            TextButton(onClick = onRevoke) {
                Text(
                    stringResource(R.string.chat_list_revoke),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
        },
    )
}

/** Coarse "x min/h/d ago" label for a pending invitation's age. */
private fun relativePendingTime(epochMs: Long): String {
    val d = System.currentTimeMillis() - epochMs
    return when {
        d < 60_000L -> "just now"
        d < 3_600_000L -> "${d / 60_000L} min ago"
        d < 86_400_000L -> "${d / 3_600_000L} h ago"
        else -> "${d / 86_400_000L} d ago"
    }
}

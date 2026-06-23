package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisButton

import app.aether.aegis.AegisApp
import app.aether.aegis.core.identifier
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisTopBar
import app.aether.aegis.ui.components.HexCheckbox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pick a name + members for a new group. Members are selected from
 * the paired KnownPeers — exactly the "form groups using contacts
 * (not joining public groups)" model.
 *
 * Behind the scenes: we ask the SimpleX core to mint the group,
 * persist a row in `groups`, then invite each selected contact via
 * SimpleX. SimpleX delivers the invite to those peers' chats; they
 * accept on their end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCreateScreen(navController: NavController) {
    val selfKey = AegisApp.instance.identity.deviceId
    // Candidate members = the paired contacts (KnownPeers), observed live so
    // the list reflects pairings made while this screen is open. There is no
    // "discover public groups" path by design — groups are formed only from
    // contacts you've already paired with.
    val knownPeers by AegisApp.instance.repository.observeKnownPeers().collectAsState(initial = emptyList())
    val candidates = remember(selfKey, knownPeers) {
        // Adapt each peer into the FamilyMember shape the row UI expects.
        // meshIp here is just a short publicKey prefix used as a stable,
        // glanceable sub-label — it is NOT a network address.
        knownPeers.map { p ->
            app.aether.aegis.core.FamilyMember(
                name = p.displayName,
                meshIp = p.publicKey.take(20),
                publicKey = p.publicKey,
            )
        }
    }

    var name by remember { mutableStateOf("") }
    // Selected member identifiers; mutableStateListOf so checkbox toggles
    // recompose the affected rows without rebuilding the whole list.
    val selected = remember { mutableStateListOf<String>() }
    // Guards against double-submit while the async create is in flight.
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Own the status-bar inset (bare Column, not a Scaffold); TopAppBar
    // insets zeroed so we don't pad twice.
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        AegisTopBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(stringResource(R.string.group_new)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    AegisIcon(app.aether.aegis.ui.components.AegisIcons.Back, stringResource(R.string.action_back))
                }
            }
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.group_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )

        Text(
            stringResource(R.string.group_create_members),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        )

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(candidates, key = { it.identifier }) { member ->
                val isSelected = member.identifier in selected
                ListItem(
                    headlineContent = {
                        Text(member.name, fontWeight = FontWeight.SemiBold)
                    },
                    supportingContent = {
                        Text(
                            member.meshIp,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        HexCheckbox(
                            checked = isSelected,
                            onCheckedChange = {
                                if (isSelected) selected.remove(member.identifier)
                                else selected.add(member.identifier)
                            },
                        )
                    },
                )
            }
        }

        AegisButton(
            // Protected Mode: group creation can be locked so a child
            // can't spin up / wander into groups they find online.
            enabled = !creating && name.isNotBlank() && selected.isNotEmpty() &&
                !isGatedNow(app.aether.aegis.protectedmode.ProtectedMode.Gate.GROUPS),
            onClick = {
                creating = true
                val groupName = name.trim()
                val memberKeys = selected.toList()
                scope.launch {
                    // 1) Ask the SimpleX core to mint the group (IO; may be
                    //    null if SimpleX is unavailable — see below).
                    val simplexId = withContext(Dispatchers.IO) {
                        val transport = AegisApp.instance.transports
                            .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
                        transport?.createSimplexGroup(groupName)
                    }
                    // 2) Persist the local `groups` row regardless of the
                    //    SimpleX result, so the group exists locally even
                    //    when minting failed (simplexId == null).
                    val group = AegisApp.instance.repository.createGroup(groupName, memberKeys, simplexId)
                    // Record OURSELVES as OWNER of the group we just created,
                    // keyed by identity.deviceId — the key GroupMembersScreen
                    // resolves "self" by. SimpleX member events only ever carry
                    // OTHER members (self arrives separately as `membership`),
                    // so without this the creator is never in group_members,
                    // selfRole defaults to MEMBER, and the group's own
                    // management UI (rename / invite / promote / remove) is
                    // unreachable for the owner.
                    val ownerKey = AegisApp.instance.identity.deviceId
                    AegisApp.instance.repository.addGroupMember(group.id, ownerKey)
                    AegisApp.instance.repository.setGroupMemberRole(
                        group.id, ownerKey, app.aether.aegis.groups.GroupRole.OWNER,
                    )
                    // 3) Best-effort SimpleX invites — already recorded
                    //    locally above, so the group stays reachable via
                    //    Loopback even if SimpleX isn't. Only fan out invites
                    //    when minting actually returned a group id.
                    withContext(Dispatchers.IO) {
                        val transport = AegisApp.instance.transports
                            .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
                        if (simplexId != null) {
                            memberKeys.forEach { pk ->
                                transport?.addMemberToGroup(simplexId, pk)
                            }
                        }
                    }
                    // Done either way — release the guard and return to the
                    // previous screen (the live peer/group flows refresh it).
                    creating = false
                    navController.popBackStack()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(if (creating) stringResource(R.string.group_creating) else stringResource(R.string.group_create))
        }
    }
}

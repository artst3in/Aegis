package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisTopBar

import app.aether.aegis.ui.components.AegisButton
import app.aether.aegis.ui.components.AegisOutlinedButton

import app.aether.aegis.AegisApp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import app.aether.aegis.ui.components.AegisIcon
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
 * SimpleX SMP relay configuration. Replaces the long-standing TODO
 * placeholder card on the Settings screen.
 *
 * Power-user feature. Touching this can break message delivery if a
 * URI is malformed or unreachable, so the UI:
 *   - shows the current servers and a "Reset to defaults" affordance
 *     up top so a user can always get back to a working state,
 *   - validates URIs against the smp://<fingerprint>@<host>[:port] shape
 *     before sending, refusing obviously bad inputs locally,
 *   - surfaces the SimpleX core's response inline on failure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySettingsScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    // loading: gates the "Current relays" body on the initial async fetch.
    var loading by remember { mutableStateOf(true) }
    // servers: the SMP relay URIs the core currently has configured. Empty
    // list is meaningful — it means "using SimpleX's built-in defaults",
    // not "fetch failed", so the empty branch shows the defaults hint.
    var servers by remember { mutableStateOf<List<String>>(emptyList()) }
    // status: last action result echoed back to the user (success or the
    // core's rejection). Null = nothing done yet this session.
    var status by remember { mutableStateOf<String?>(null) }
    var inputUri by remember { mutableStateOf("") }
    // working: serialises mutations. Every add/remove/reset button is
    // disabled while a core call is in flight so two overlapping
    // setSmpServers() calls can't race on the same list.
    var working by remember { mutableStateOf(false) }

    /**
     * Re-read the live relay list from the SimpleX core. Runs the core
     * call off the main thread (Dispatchers.IO) since it crosses the JNI
     * bridge. A null transport or null reply both collapse to the empty
     * list (= "defaults"), which is the safe display state.
     */
    suspend fun refresh() {
        loading = true
        // The app can hold several transports; pick the SimpleX one. May be
        // absent if SimpleX isn't initialised, hence the nullable handling.
        val simplex = AegisApp.instance.transports
            .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
        servers = withContext(Dispatchers.IO) { simplex?.listSmpServers() } ?: emptyList()
        loading = false
    }

    // One-shot load on first composition.
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            AegisTopBar(
                title = { Text(stringResource(R.string.relay_simplex_relays)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(app.aether.aegis.ui.components.AegisIcons.Back, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.relay_poweruser_setting_the_defaults) +
                    "the SimpleX team operates. Replacing them lets you " +
                    "use a different SMP relay — your own server, a " +
                    "trusted alternative — but if any URI is wrong or " +
                    "unreachable, message delivery breaks until you fix it.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.relay_current_relays),
                        color = app.aether.aegis.ui.theme.AegisCyan,
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        loading -> Text(
                            stringResource(R.string.relay_loading),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        servers.isEmpty() -> Text(
                            stringResource(R.string.relay_using_simplex_defaults),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        else -> servers.forEach { uri ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    uri,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                // Per-relay delete. Removing a relay is just
                                // "set the list to everything except this
                                // one" — the core has no remove primitive, so
                                // we always send the full desired list.
                                IconButton(
                                    enabled = !working,
                                    onClick = {
                                        val next = servers.filterNot { it == uri }
                                        scope.launch {
                                            working = true
                                            val simplex = AegisApp.instance.transports
                                                .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                                                .firstOrNull()
                                            val ok = withContext(Dispatchers.IO) {
                                                simplex?.setSmpServers(next) ?: false
                                            }
                                            status = if (ok) "Removed $uri" else "Core rejected the change"
                                            if (ok) servers = next
                                            working = false
                                        }
                                    },
                                ) { AegisIcon(app.aether.aegis.ui.components.AegisIcons.Delete, "remove") }
                            }
                        }
                    }
                }
            }

            // Add new relay. The field reflects validity live: it only
            // flags an error once the user has typed something (blank is
            // not an error, just empty), and the Add button below stays
            // disabled until the shape check passes so a malformed URI
            // never reaches the core.
            OutlinedTextField(
                value = inputUri,
                onValueChange = { inputUri = it },
                label = { Text(stringResource(R.string.relay_add_relay)) },
                placeholder = { Text(stringResource(R.string.relay_smpfingerprinthostport)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = inputUri.isNotBlank() && !isValidSmpUri(inputUri.trim()),
                supportingText = {
                    val trimmed = inputUri.trim()
                    if (trimmed.isNotBlank() && !isValidSmpUri(trimmed)) {
                        Text(stringResource(R.string.relay_must_start_with_smp))
                    } else {
                        Text(stringResource(R.string.relay_format_smpbase64fingerprinthostport))
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AegisButton(
                    enabled = !working && isValidSmpUri(inputUri.trim()),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // distinct() guards against adding a relay the list
                        // already contains. As with delete, the whole list
                        // is resubmitted — there's no append primitive.
                        val next = (servers + inputUri.trim()).distinct()
                        scope.launch {
                            working = true
                            val simplex = AegisApp.instance.transports
                                .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
                            val ok = withContext(Dispatchers.IO) {
                                simplex?.setSmpServers(next) ?: false
                            }
                            if (ok) {
                                servers = next
                                inputUri = ""
                                status = "Added"
                            } else {
                                status = "Core rejected — check the URI."
                            }
                            working = false
                        }
                    },
                ) { Text(stringResource(R.string.action_add)) }
                // Reset to defaults: hand the core an EMPTY list, which it
                // interprets as "fall back to the bundled SimpleX servers".
                // This is the always-available escape hatch back to a known
                // working configuration if a custom relay broke delivery.
                AegisOutlinedButton(
                    enabled = !working,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            working = true
                            val simplex = AegisApp.instance.transports
                                .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
                            val ok = withContext(Dispatchers.IO) {
                                simplex?.setSmpServers(emptyList()) ?: false
                            }
                            if (ok) {
                                status = "Reset to defaults."
                                refresh()
                            } else {
                                status = "Core rejected the reset."
                            }
                            working = false
                        }
                    },
                ) { Text(stringResource(R.string.relay_reset_to_defaults)) }
            }

            status?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** Lightweight smp:// URI shape check. We don't validate the
 *  fingerprint (the core does that on accept), just the prefix +
 *  presence of a host so obviously bad input never reaches /smp. */
private fun isValidSmpUri(uri: String): Boolean {
    // Must carry the smp:// scheme. Case-insensitive so a stray
    // "SMP://" paste from a docs page still passes.
    if (!uri.startsWith("smp://", ignoreCase = true)) return false
    val rest = uri.removePrefix("smp://")
    val at = rest.indexOf('@')
    // Require a non-empty fingerprint before '@' (at > 0) and a
    // non-empty remainder after it (at != last index) — reject "@host"
    // and "fingerprint@".
    if (at <= 0 || at == rest.length - 1) return false
    // Host is everything after '@' up to the first path / query marker.
    val host = rest.substring(at + 1).substringBefore('/').substringBefore('?')
    // Insist on a dotted host so a bare token can't masquerade as one.
    return host.isNotBlank() && '.' in host
}

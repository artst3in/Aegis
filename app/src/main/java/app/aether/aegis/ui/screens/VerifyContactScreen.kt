package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisTopBar

import app.aether.aegis.ui.components.AegisButton
import app.aether.aegis.ui.components.AegisOutlinedButton

import app.aether.aegis.AegisApp
import app.aether.aegis.peer.QrCodes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import app.aether.aegis.ui.components.AegisIcon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Safety-code verification, WhatsApp-style.
 *
 * SimpleX's connection code is a hash of both parties' identity keys
 * (`/_get code @<contactId>`). Both sides see the same string if and
 * only if no one is sitting in the middle. Two paths to verify:
 *
 *  1. Compare the displayed string out-of-band (voice call, in person).
 *  2. One side shows the QR — the other taps "Scan their QR" → camera
 *     reads the other phone's code → strings are compared in-app and
 *     verified automatically on match (or warned on mismatch).
 *
 * "Mark verified" pins the contact as verified in both our local DB
 * and the SimpleX core (so the official-client recipients see the
 * green badge too). Unverify by hitting the same button again.
 *
 * @param peerKey the contact's public key — both the lookup key into the
 *   observed peer list AND the SimpleX contact handle used to fetch/set the
 *   safety code. The screen still renders if the peer row isn't loaded yet
 *   (falls back to "contact"), but verification needs a real SimpleX peer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyContactScreen(peerKey: String, navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val peers by AegisApp.instance.repository.observeKnownPeers().collectAsState(initial = emptyList())
    val peer = peers.firstOrNull { it.publicKey == peerKey }

    // `code` = the SimpleX safety code (null until fetched, or on failure).
    var code by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    // Transient user-facing result line (match / mismatch / errors).
    var status by remember { mutableStateOf("") }

    // Fetch the safety code off the main thread when the peer changes. The
    // code is a hash of both identity keys; a null result means SimpleX has
    // no code for this contact (unavailable / not yet connected), and the UI
    // shows the "couldn't fetch" message instead of the code + QR.
    LaunchedEffect(peerKey) {
        loading = true
        val simplex = AegisApp.instance.transports
            .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
        code = withContext(Dispatchers.IO) { simplex?.getContactCode(peerKey) }
        loading = false
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            AegisTopBar(
                title = { Text("Verify ${peer?.displayName ?: "contact"}") },
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
        ) {
            Text(
                "Compare this code with the one ${peer?.displayName ?: "your contact"} sees on their phone " +
                    "(over a separate channel — voice call, in person). If they match, the conversation is " +
                    "end-to-end secure between just the two of you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Three-way render: still fetching → spinner; no code → error;
            // otherwise → the code, QR, and the scan/pick/mark controls.
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                code == null -> Text(
                    stringResource(R.string.verify_contact_couldnt_fetch_the_safety),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    // Smart-cast escape hatch: `code` is a var, so capture
                    // it as the local `c` for use in callbacks/remember.
                    val c = code ?: return@Column
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            // Insert thin spaces every 5 chars so the
                            // string reads in groups instead of a wall.
                            c.chunked(5).joinToString(" "),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    // Render the same code as a QR so the other side can
                    // scan it (480 px source bitmap, displayed at 220 dp).
                    // Keyed on `c` so a refreshed code regenerates the QR.
                    val bitmap = remember(c) { QrCodes.render(c, 480).asImageBitmap() }
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.verify_contact_safety_code_qr),
                        modifier = Modifier
                            .size(220.dp)
                            .align(Alignment.CenterHorizontally),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    // Current verified state drives the bottom button's
                    // label/action (Mark vs Clear) and the toggle value sent
                    // to SimpleX further down.
                    val verified = peer?.verified == true
                    // Scan THEIR QR — camera path. The other device shows
                    // its own safety code (this same screen, mirrored);
                    // we scan it and compare strings. Match → auto-mark
                    // verified, no manual eyeballing. Mismatch → loud
                    // warning, do NOT mark.
                    // Shared callback for both Scan and Pick-from-photo:
                    // compare the decoded text against our own safety
                    // code `c`, mark verified on match, warn loudly on
                    // mismatch. Same security model either way — the
                    // photo path is convenience, the trust still comes
                    // from the user having seen the code on a separate
                    // channel.
                    val onCode: (String) -> Unit = { scanned ->
                        val theirs = scanned.trim()
                        if (theirs.isNotBlank()) {
                            // Exact string equality IS the security check —
                            // if the two hashes match there is no MITM. Any
                            // difference is treated as a failed verification.
                            if (theirs == c) {
                                scope.launch {
                                    val simplex = AegisApp.instance.transports
                                        .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                                        .firstOrNull()
                                    // Push the verified state into the core
                                    // first; only mirror it into our local DB
                                    // if the core accepts, so the two never
                                    // disagree.
                                    val ok = withContext(Dispatchers.IO) {
                                        simplex?.verifyContact(peerKey, c) ?: false
                                    }
                                    if (ok) {
                                        AegisApp.instance.repository.setPeerVerified(peerKey, true)
                                        status = "Match ✓ — marked verified"
                                        Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                                    } else {
                                        status = "Codes match but SimpleX rejected — try again"
                                    }
                                }
                            } else {
                                status = "✗ Codes don't match — someone may be in the middle. " +
                                    "Do NOT trust this contact until you've checked again on a " +
                                    "separate channel."
                                Toast.makeText(
                                    context,
                                    "Codes don't match",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                    // Camera path: scanning requires MainActivity's launcher
                    // plumbing, so bail with a status note if we're somehow
                    // hosted elsewhere rather than crashing on the cast.
                    AegisOutlinedButton(
                        onClick = {
                            val activity = context as? app.aether.aegis.MainActivity
                            if (activity == null) {
                                status = "Scanner unavailable"
                                return@AegisOutlinedButton
                            }
                            activity.scanQr(
                                "Point camera at ${peer?.displayName ?: "their"} safety-code QR",
                                onCode,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        app.aether.aegis.ui.components.AegisIcon(
                            icon = app.aether.aegis.ui.components.AegisIcons.Camera,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.verify_contact_scan_their_qr))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    AegisOutlinedButton(
                        onClick = {
                            (context as? app.aether.aegis.MainActivity)?.pickQrFromGallery(onCode)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.verify_contact_pick_qr_from_photo))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Manual toggle: the out-of-band path (compared the code
                    // by voice/in person, no scan). Passing the code marks
                    // verified; passing null clears it — same call, toggled.
                    AegisButton(
                        onClick = {
                            scope.launch {
                                val simplex = AegisApp.instance.transports
                                    .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().firstOrNull()
                                val ok = withContext(Dispatchers.IO) {
                                    simplex?.verifyContact(peerKey, if (verified) null else c) ?: false
                                }
                                if (ok) {
                                    AegisApp.instance.repository.setPeerVerified(peerKey, !verified)
                                    status = if (!verified) "Verified ✓" else "Verification cleared"
                                    Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                                } else {
                                    status = "SimpleX rejected the request"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (verified) "Clear verification" else "Mark verified")
                    }
                    if (status.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            status,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

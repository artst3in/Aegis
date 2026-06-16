package app.aether.aegis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.protectedmode.ProtectedMode
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisSOS

/** Human labels + one-line descriptions for each gate, in display order.
 *  Kept here (UI layer) rather than on the enum so the domain type stays
 *  free of presentation strings. */
private val GATE_ROWS: List<Triple<ProtectedMode.Gate, String, String>> = listOf(
    Triple(ProtectedMode.Gate.SYSTEM_TAB, "Lock System tab", "Dims the Settings tab in place — it stops opening."),
    Triple(ProtectedMode.Gate.OPSEC_TAB, "Lock Opsec tab", "Dims the Security tab in place — PIN/duress/admin config."),
    Triple(ProtectedMode.Gate.CONTACTS, "Lock contact management", "Blocks adding and deleting contacts."),
    Triple(ProtectedMode.Gate.TRUST_TIER, "Lock trust-tier changes", "Blocks promote/demote — the silent SOS-break."),
    Triple(ProtectedMode.Gate.GROUPS, "Lock group membership", "Blocks create / join / leave."),
    Triple(ProtectedMode.Gate.HIDE_GROUPS, "Hide group management", "Removes create / join — existing group chats stay visible."),
    Triple(ProtectedMode.Gate.DANGER_ZONE, "Lock Danger Zone", "Blocks wipe-all, core reset, the destructive ops."),
    Triple(ProtectedMode.Gate.PROFILES, "Lock profiles", "Blocks switching and deleting profiles."),
    Triple(ProtectedMode.Gate.UPDATES, "Lock updates", "Blocks the self-update controls."),
)

/**
 * Protected Mode configuration + arm/disarm.
 *
 * Reached from Security (Opsec) → Protected Mode, itself gated behind the
 * Experimental unlock for now (prototype). SOS is never in the gate list
 * (invariant 1); the app PIN always disarms (invariant 2, enforced in
 * [ProtectedPinDialog]); the mode is inert under duress (invariant 3,
 * enforced in [ProtectedMode.isGated]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectedModeScreen(navController: NavController) {
    val armed by ProtectedMode.armed.collectAsState()
    val liveGates by ProtectedMode.gates.collectAsState()
    var hasPin by remember { mutableStateOf(ProtectedMode.hasPin()) }

    // The presets, narrowed to the gates actually enforced in this build
    // (see ProtectedMode.WIRED). Used for seeding and the preset chips.
    val childWired = remember { ProtectedMode.Preset.CHILD.gates.intersect(ProtectedMode.WIRED) }
    val lockdownWired = remember { ProtectedMode.Preset.LOCKDOWN.gates.intersect(ProtectedMode.WIRED) }

    // Local edit buffer (the working set of gates to arm). Seeded from the
    // live selection, or the Child preset on first setup so the common case
    // is one tap to arm. Edits here are inert until Arm is pressed — while
    // armed the chips/toggles are disabled, so this can only change while OFF.
    var selected by remember {
        mutableStateOf(if (liveGates.isNotEmpty()) liveGates else childWired)
    }

    // PIN setup fields — only shown until a PIN exists (!hasPin). pin2 is the
    // confirmation; pinError surfaces the mismatch. Cleared after a set.
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    // Gates the disarm dialog (which accepts protected-mode PIN OR app PIN).
    var showDisarmPrompt by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Protected Mode") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        AegisIcon(AegisIcons.Back, "back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Accident-prevention, not security. A separate PIN that makes destructive actions inert — so a child on their own phone (or you, against an impulse) can't cut the safety net. SOS is never locked. Your app PIN always disarms it.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))

            // ── Status banner ──────────────────────────────────────────
            GlassPanel(glow = armed, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (armed) "🔒" else "🔓", fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (armed) "Protected Mode is ON" else "Protected Mode is OFF",
                            fontWeight = FontWeight.SemiBold,
                            color = if (armed) AegisCyan else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (armed) "${liveGates.size} gates locked. Tap a dimmed tab to disarm."
                            else "Pick what to lock, then arm.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── PIN setup ──────────────────────────────────────────────
            if (!hasPin) {
                Text("SET THE PROTECTED-MODE PIN", color = AegisCyan, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Distinct from your app, duress, and vault PINs. Whoever holds this PIN can disarm.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                PinField("PIN (4–12 digits)", pin1) { pin1 = it; pinError = null }
                Spacer(Modifier.height(8.dp))
                PinField("Confirm PIN", pin2) { pin2 = it; pinError = null }
                pinError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = AegisSOS, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    // Length 4–12 matches the PinField cap; the match check
                    // is deferred to onClick so the user sees a reason rather
                    // than a silently-disabled button on a typo.
                    enabled = pin1.length in 4..12,
                    onClick = {
                        when {
                            pin1 != pin2 -> pinError = "PINs don't match."
                            else -> {
                                ProtectedMode.setPin(pin1)
                                hasPin = true
                                pin1 = ""; pin2 = "" // don't leave the PIN in state
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Set PIN") }
                Spacer(Modifier.height(16.dp))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("● Protected-mode PIN set", color = AegisCyan, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    // "Change" only flips the UI back to the setup fields
                    // (hasPin = false); it does not clear the stored PIN
                    // until a new one is actually set. Hidden while armed so
                    // the PIN can't be swapped out from under an active lock.
                    if (!armed) {
                        TextButton(onClick = { hasPin = false }) { Text("Change") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Presets ────────────────────────────────────────────────
            Text("PRESETS", color = AegisCyan, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Preset chips compare the WHOLE selection set against a
                // preset; "Custom" is the implicit state when the selection
                // matches neither, so its onClick is a no-op (you reach it by
                // toggling individual gates, not by tapping the chip).
                FilterChip(
                    selected = selected == childWired,
                    enabled = !armed,
                    onClick = { selected = childWired },
                    label = { Text("Child") },
                )
                FilterChip(
                    selected = selected == lockdownWired,
                    enabled = !armed,
                    onClick = { selected = lockdownWired },
                    label = { Text("Lockdown") },
                )
                FilterChip(
                    selected = selected != childWired && selected != lockdownWired,
                    enabled = !armed,
                    onClick = { },
                    label = { Text("Custom") },
                )
            }
            Spacer(Modifier.height(12.dp))

            // ── À-la-carte gates ───────────────────────────────────────
            Text("WHAT TO LOCK", color = AegisCyan, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            // Only show gates actually enforced in this build (WIRED); a
            // listed-but-unwired gate would be a toggle that does nothing.
            GATE_ROWS.filter { it.first in ProtectedMode.WIRED }.forEach { (gate, label, desc) ->
                GateToggle(
                    label = label,
                    desc = desc,
                    checked = gate in selected,
                    enabled = !armed,
                    onCheckedChange = { on ->
                        selected = if (on) selected + gate else selected - gate
                    },
                )
            }
            Spacer(Modifier.height(16.dp))

            // ── Arm / Disarm ───────────────────────────────────────────
            if (armed) {
                Button(
                    onClick = { showDisarmPrompt = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AegisSOS),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Disarm Protected Mode", fontWeight = FontWeight.Bold) }
            } else {
                // Can't arm without a PIN (nothing would disarm it) or with
                // an empty selection (nothing would be locked).
                Button(
                    enabled = hasPin && selected.isNotEmpty(),
                    onClick = { ProtectedMode.arm(selected) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Arm Protected Mode", fontWeight = FontWeight.Bold) }
                if (!hasPin) {
                    Spacer(Modifier.height(6.dp))
                    Text("Set a PIN first.", color = AegisOnSurfaceDim, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDisarmPrompt) {
        ProtectedPinDialog(
            title = "Disarm Protected Mode",
            onDismiss = { showDisarmPrompt = false },
            onSuccess = {
                showDisarmPrompt = false
                ProtectedMode.disarm()
            },
        )
    }
}

/**
 * One à-la-carte gate row: label + description + a Switch. [enabled] is
 * false while the mode is armed (the gate set is frozen until disarm), and
 * the label dims to signal that. Pure presentation — toggling just edits
 * the parent's `selected` buffer; nothing is enforced until Arm.
 */
@Composable
private fun GateToggle(
    label: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Text(desc, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

/**
 * Numeric, masked PIN entry. Filters non-digits and hard-caps at 12 chars
 * at the input layer (the 4–12 length rule is enforced by the caller's
 * enable gate), so the field can only ever hold a valid-shaped PIN.
 */
@Composable
private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        // Sanitise on input: digits only, max 12 — keeps state clean
        // regardless of paste / IME quirks.
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(12)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Reusable PIN prompt for disarming. Accepts EITHER the protected-mode
 * PIN OR the app PIN (invariant 2 — the owner can always disarm with the
 * master credential, even if they forgot the protected-mode PIN). Shared
 * by [ProtectedModeScreen] and the locked-tab tap in the bottom nav.
 */
@Composable
fun ProtectedPinDialog(
    title: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val context = LocalContext.current
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "Enter the Protected-Mode PIN (or your app PIN).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                PinField("PIN", entry) { entry = it; error = null }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = AegisSOS, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Accept either credential — the protected-mode PIN OR the
                // owner's real app PIN (invariant 2: the master credential
                // always disarms, even if the protected-mode PIN is lost).
                val ok = ProtectedMode.verify(entry) || appPinAccepts(context, entry)
                if (ok) onSuccess() else error = "Wrong PIN."
            }) { Text("Unlock") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Compose-reactive read of [ProtectedMode.isGated]. Subscribes to the
 * armed + gates flows so a control wrapped in `enabled = !isGatedNow(g)`
 * flips live the instant the mode is armed or disarmed. The source of
 * truth — including the duress-inert check — stays in [ProtectedMode].
 */
@Composable
fun isGatedNow(gate: ProtectedMode.Gate): Boolean {
    val armed by ProtectedMode.armed.collectAsState()
    val gates by ProtectedMode.gates.collectAsState()
    // Reference both so the composition subscribes and re-runs on change.
    armed.let { }; gates.let { }
    return ProtectedMode.isGated(gate)
}

/** App-PIN master override: true iff [pin] is the owner's REAL app PIN. */
private fun appPinAccepts(context: android.content.Context, pin: String): Boolean =
    runCatching {
        app.aether.aegis.lock.LockStore(context).verifyPin(pin) ==
            app.aether.aegis.lock.LockStore.PinMatch.REAL
    }.getOrDefault(false)

package app.aether.aegis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import app.aether.aegis.AegisApp
import app.aether.aegis.ui.components.HexShape
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisCyanGlow
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisSOS
import app.aether.aegis.ui.theme.AegisSOSGlow
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * The gate every app entry passes through when a PIN is set.
 *
 *   ┌──────────────────────────┐
 *   │                          │
 *   │       ⬡  (96 dp)         │   ← brand mark
 *   │       AEGIS              │
 *   │                          │
 *   │      ⬡  (64 dp)          │   ← biometric trigger
 *   │   "Use PIN"              │
 *   │                          │
 *   │     ◇◆◇◇◇◇              │   ← PIN dots (when in PIN mode)
 *   │     1 2 3                │
 *   │     4 5 6                │   ← hex number pad
 *   │     7 8 9                │
 *   │       0  ⌫               │
 *   │                          │
 *   │       ⬡  (36 dp red)     │   ← SOS — bypasses lock, fires sos
 *   └──────────────────────────┘
 */
@Composable
fun LockScreen() {
    val lockState = AegisApp.instance.lockState
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mugshotScope = rememberCoroutineScope()
    val store = lockState.store

    // The PIN pad is ALWAYS the fallback and the home of duress: a single
    // fingerprint can't carry the duress-vs-real distinction, so biometric
    // (re-added later) is
    // only ever a SHORTCUT layered over this pad. Biometric unwraps the
    // REAL seal priv and opens the real profile; an attacker who forces a
    // finger gets the real profile, but the user who instead types a
    // duress PIN here still gets a decoy. "That's my only PIN" stays an
    // unprovable claim.
    var pinEntry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var lockoutUntil by remember { mutableStateOf(store.lockoutUntil) }
    var digitOrder by remember {
        mutableStateOf(if (store.scramblePinPad) (0..9).shuffled() else (0..9).toList())
    }

    // Biometric unlock. On a successful match we receive the unwrapped
    // REAL seal priv; install it + the cached pub into the session and flip
    // the lock exactly like a REAL PIN match does (mugshot streak reset,
    // legacy-plaintext seal walk). No PIN was typed, so this is the only
    // place that path runs without one.
    val onBiometricPriv: (ByteArray) -> Unit = onBiometricPriv@{ priv ->
        val pub = store.sealPub ?: return@onBiometricPriv
        app.aether.aegis.lock.PinSession.set(
            app.aether.aegis.lock.PinKeypair.KeyPair(pub, priv),
        )
        app.aether.aegis.mugshot.MugshotCapture.resetStreak(context)
        lockState.unlock()
    }
    val biometricAvailable = remember {
        store.biometricEnabled &&
            app.aether.aegis.ui.components.deviceHasStrongBiometric(context)
    }
    // Biometric is NOT auto-presented: the system BiometricPrompt
    // renders over everything, including the emergency SOS button, so
    // auto-showing it would bury the one control that must always be
    // reachable on a lock screen. The lock screen (PIN pad + sos) shows
    // immediately; the "Use fingerprint / face" button below invokes the
    // prompt only when the user chooses to.

    // "Forgot PIN → enter phrase" recovery. Only
    // offered for phrase-rooted profiles — a legacy PIN-rooted profile
    // has no phrase to recover with. When true, the recovery screen
    // replaces the pad entirely.
    var showRecovery by remember { mutableStateOf(false) }
    val recoveryAvailable = remember { store.hasRecoveryPhrase }
    // >>> DEBUG-ONLY (stripped for public build)
    var showResetConfirm by remember { mutableStateOf(false) }
    // <<< DEBUG-ONLY
    if (showRecovery) {
        RecoveryUnlockScreen(
            onCancel = { showRecovery = false },
            onRecovered = { showRecovery = false },
        )
        return
    }

    // Pattern unlock. Start in pattern mode
    // when enrolled; the user can flip to the PIN pad (which is also the
    // home of duress). Pattern is a Model-B convenience gate — on a
    // correct draw we recover the seal priv from the TEE and open the
    // REAL profile. It carries no duress (a single pattern can't be both
    // real and decoy); coercion protection lives on the PIN.
    var patternMode by remember { mutableStateOf(store.hasPattern) }
    val onPatternDrawn: (List<Int>) -> Unit = onPatternDrawn@{ seq ->
        if (store.verifyPattern(seq)) {
            app.aether.aegis.mugshot.MugshotCapture.resetStreak(context)
            // No usable wrapped priv → can't unwrap; send to phrase
            // recovery rather than opening into undecryptable chats.
            if (!store.hasWrappedSealPriv) {
                showRecovery = true
                return@onPatternDrawn
            }
            val kp = store.unwrapSealKeypair()
            if (kp != null) app.aether.aegis.lock.PinSession.set(kp)
            lockState.unlock()
        } else {
            val deadline = store.recordFailedAttempt()
            error = "Wrong pattern."
            if (deadline > 0L) lockoutUntil = deadline
        }
    }

    // Tick once per second when in a lockout window so the countdown
    // updates and we can re-enable input automatically.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockoutUntil) {
        if (lockoutUntil > nowMs) {
            while (System.currentTimeMillis() < lockoutUntil) {
                kotlinx.coroutines.delay(500L)
                nowMs = System.currentTimeMillis()
            }
            nowMs = System.currentTimeMillis()
        }
    }
    val locked = lockoutUntil > nowMs
    val remainingSec = if (locked) ((lockoutUntil - nowMs) / 1000L) + 1 else 0L

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Brand mark (96 dp hex). The foreground adaptive-icon
            // PNG is the actual Aegis logo (the cyan hex shield) —
            // rendering it inside the brand hex feels like the app's mark,
            // not a placeholder.
            HexShape(
                size = 96.dp,
                borderColor = AegisCyan,
                fillColor = AegisCyanGlow,
                glow = true,
                glowColor = AegisCyanGlow,
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(
                        app.aether.aegis.R.mipmap.ic_aegis_foreground,
                    ),
                    contentDescription = stringResource(R.string._aegis),
                    modifier = Modifier.size(96.dp),
                )
            }

            Text(
                stringResource(R.string.tutorial_aegis),
                color = AegisCyan,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp,
                // >>> DEBUG-ONLY (stripped for public build)
                modifier = app.aether.aegis.lock.debugLockResetModifier(
                    onTriggered = { showResetConfirm = true },
                ),
                // <<< DEBUG-ONLY
            )
            // >>> DEBUG-ONLY (stripped for public build)
            app.aether.aegis.lock.DebugLockResetHint()
            // <<< DEBUG-ONLY

            if (locked) {
                Text(
                    "Locked. Try again in $remainingSec s.",
                    color = AegisSOS,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (error != null) {
                val errorText = error
                Text(
                    errorText.orEmpty(),
                    color = AegisSOS,
                    fontSize = 12.sp,
                )
            }

            if (store.hasPattern && patternMode && !locked) {
                app.aether.aegis.ui.components.PatternLock(
                    modifier = Modifier.fillMaxWidth(0.78f),
                    onPattern = onPatternDrawn,
                )
                androidx.compose.material3.TextButton(onClick = { patternMode = false }) {
                    Text(stringResource(R.string.lock_use_pin), color = AegisOnSurfaceDim, fontSize = 13.sp)
                }
            } else {
            // PIN dots — show entered length as filled hex pips.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(8) { i ->
                    HexShape(
                        size = 14.dp,
                        borderColor = if (i < pinEntry.length) AegisCyan else AegisBorder,
                        fillColor = if (i < pinEntry.length) AegisCyanGlow else Color.Transparent,
                    ) {}
                }
            }
            // 3x4 number pad
            NumberPad(
                enabled = !locked,
                digitOrder = digitOrder,
                onDigit = { d ->
                    if (pinEntry.length < 8) {
                        error = null
                        pinEntry += d
                        if (pinEntry.length >= 4) {
                            // Three-state verify: REAL, DURESS, or INVALID.
                            // Duress unlock fires SOS silently before
                            // returning, so by the time the decoy UI
                            // paints, family is already being alerted.
                            when (store.verifyPin(pinEntry)) {
                                app.aether.aegis.lock.LockStore.PinMatch.REAL -> {
                                    app.aether.aegis.mugshot.MugshotCapture.resetStreak(context)
                                    // Phrase-rooted Model-B profile that has NO stored
                                    // wrapped priv to unwrap (a prior build's wrap
                                    // silently failed, or the TEE key was invalidated):
                                    // unlocking now would open the app into chats that
                                    // can't decrypt. Route to phrase recovery instead —
                                    // it re-derives the seal priv from the 24 words,
                                    // re-wraps it into the (now-reliable) TEE, and sets a
                                    // PIN — so the user is never left staring at locked
                                    // chats with no obvious way out.
                                    if (store.hasRecoveryPhrase && !store.requirePhraseOnBoot &&
                                        !store.hasWrappedSealPriv) {
                                        store.resetAttempts()
                                        pinEntry = ""
                                        showRecovery = true
                                        return@NumberPad
                                    }
                                    lockState.unlockReal()
                                    pinEntry = ""
                                }
                                app.aether.aegis.lock.LockStore.PinMatch.DURESS_1 -> {
                                    // User-configured duress PIN — open
                                    // Fake #1 and fire TRULY silent SOS.
                                    // SOSTrigger.DURESS (not LOCKSCREEN) so
                                    // SOSHandler suppresses the visible
                                    // "SOS ACTIVE" notification that would
                                    // otherwise alert the coercer.
                                    AegisApp.instance.sosHandler.trigger(
                                        app.aether.aegis.core.SOSTrigger.DURESS,
                                    )
                                    lockState.unlockDuress1()
                                    pinEntry = ""
                                }
                                app.aether.aegis.lock.LockStore.PinMatch.DURESS_2 -> {
                                    // Attacker-set PIN — they're testing
                                    // the system, not coercing. Open
                                    // Fake #2 silently, no extra SOS.
                                    lockState.unlockDuress2()
                                    pinEntry = ""
                                }
                                app.aether.aegis.lock.LockStore.PinMatch.INVALID -> {
                                    // Multi-profile fallback: maybe the
                                    // user typed a DIFFERENT profile's
                                    // PIN. By design there
                                    // is no profile picker on the lock
                                    // screen (would leak profile count
                                    // to an observer), so any profile's
                                    // PIN unlocks straight into that
                                    // profile.
                                    val cross = app.aether.aegis.lock.LockStore.findMatchingProfile(
                                        context, pinEntry,
                                    )
                                    val currentActive = app.aether.aegis.profile.ProfileRegistry.get(context).activeProfileId
                                    if (cross != null && cross.first != currentActive) {
                                        // Match on a non-active profile.
                                        // Set active id and hard-kill so
                                        // the new process loads the
                                        // matching profile. The user
                                        // sees a brief reload and lands
                                        // on the new profile's lock
                                        // screen (still locked — the
                                        // PIN they just typed isn't
                                        // re-applied across the process
                                        // boundary; live re-bind is too
                                        // fragile to attempt). On
                                        // re-prompt they type the same
                                        // PIN again and unlock.
                                        app.aether.aegis.profile.ProfileRegistry.get(context)
                                            .setActiveProfile(cross.first)
                                        android.os.Process.killProcess(
                                            android.os.Process.myPid(),
                                        )
                                        return@NumberPad
                                    }
                                    if (pinEntry.length == 8) {
                                        val deadline = store.recordFailedAttempt()
                                        error = "Wrong PIN."
                                        pinEntry = ""
                                        if (deadline > 0L) lockoutUntil = deadline
                                        if (store.scramblePinPad) {
                                            digitOrder = (0..9).shuffled()
                                        }
                                        // Mugshot: if armed, fire after
                                        // [triggerThreshold] consecutive
                                        // wrong PINs. MugshotCapture is
                                        // idempotent per streak.
                                        val mug = app.aether.aegis.mugshot.MugshotStore(context)
                                        if (mug.enabled &&
                                            store.failedAttempts >= mug.triggerThreshold) {
                                            mugshotScope.launch {
                                                app.aether.aegis.mugshot.MugshotCapture.captureAndShip(
                                                    context, lifecycleOwner,
                                                )
                                            }
                                        }
                                        // Wipe-after-N (opt-in,
                                        // off by default). Fire the mugshot FIRST
                                        // (above) so the wrong-PIN evidence is
                                        // captured before the data — and possibly
                                        // the whole device — is erased. Nuclear and
                                        // irreversible; only reaches here if the
                                        // owner armed it through the confirmation
                                        // dialog in Lock settings.
                                        val wipeStore = app.aether.aegis.lock.WipeOnFailureStore(context)
                                        if (wipeStore.shouldWipe(store.failedAttempts)) {
                                            if (wipeStore.wipePhone) {
                                                app.aether.aegis.lock.LocalWipe.wipePhone()
                                            } else {
                                                app.aether.aegis.lock.LocalWipe.wipeAegisData(context)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                onBackspace = {
                    if (pinEntry.isNotEmpty()) pinEntry = pinEntry.dropLast(1)
                },
            )
            // Offer the pattern grid when one is enrolled.
            if (store.hasPattern && !locked) {
                androidx.compose.material3.TextButton(onClick = { patternMode = true }) {
                    Text(stringResource(R.string.lock_use_pattern), color = AegisOnSurfaceDim, fontSize = 13.sp)
                }
            }
            }
            // Re-present the biometric prompt if the user dismissed it.
            // Only shown when biometric is actually enrolled + usable.
            // Hex-framed fingerprint glyph (AegisIcons.Fingerprint) — the
            // LunaGlass biometric trigger the lock-screen mockup calls for,
            // not a bare text link.
            if (biometricAvailable) {
                Spacer(modifier = Modifier.height(12.dp))
                // Flat-top hex control wrapping the now-FRAMELESS fingerprint
                // glyph (the drawable's own hex was removed 2026-06-07 to
                // stop the hex-in-hex nesting). HexShape supplies the LunaGlass
                // frame + tap haptic; the ridge glyph sits centred inside.
                HexShape(
                    size = 56.dp,
                    borderColor = AegisCyan,
                    onClick = {
                        app.aether.aegis.ui.components.biometricUnlock(
                            context, onPriv = onBiometricPriv, onUnavailable = {},
                        )
                    },
                ) {
                    app.aether.aegis.ui.components.AegisIcon(
                        icon = app.aether.aegis.ui.components.AegisIcons.Fingerprint,
                        contentDescription = stringResource(R.string.lock_use_fingerprint_or_face),
                        tint = AegisCyan,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            // Recovery entry — only when this profile has a phrase to
            // recover with. Opens the 24-word recovery flow.
            if (recoveryAvailable) {
                androidx.compose.material3.TextButton(onClick = { showRecovery = true }) {
                    Text(
                        stringResource(R.string.lock_forgot_pin),
                        color = AegisOnSurfaceDim,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        // SOS SOS — bottom of screen, no auth required (speed > security
        // in an emergency). Lock screen stays up after firing so a thief
        // can't see chat content even via the SOS flow.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hold-to-execute, same 3 s confirmation as the main sos
            // button — a single accidental tap on the lock screen must not
            // fire SOS. forceHold so it ignores the global hold pref.
            app.aether.aegis.ui.components.HoldToExecuteHex(
                size = 36.dp,
                borderColor = AegisSOS,
                heatColor = AegisSOS,
                fillColor = Color.Black,
                holdDurationMs = 3000L,
                forceHold = true,
                hapticOnPress = true,
                hapticEdgeStride = 2,
                onExecute = {
                    // Silent SOS. Stays on lock screen.
                    AegisApp.instance.sosHandler.trigger(app.aether.aegis.core.SOSTrigger.LOCKSCREEN)
                },
            ) {
                Text(
                    stringResource(R.string.tab_order_sos),
                    color = AegisSOS,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.lock_hold_for_silent_emergency),
                color = AegisOnSurfaceDim,
                fontSize = 9.sp,
            )
        }

        // >>> DEBUG-ONLY (stripped for public build)
        app.aether.aegis.lock.DebugLockResetConfirmation(
            show = showResetConfirm,
            onDismiss = { showResetConfirm = false },
            onConfirmed = {
                showResetConfirm = false
                pinEntry = ""
                error = null
                lockoutUntil = 0L
                lockState.unlock()
            },
        )
        // <<< DEBUG-ONLY
    }
}

@Composable
private fun NumberPad(
    enabled: Boolean,
    digitOrder: List<Int>,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    // digitOrder is a permutation of 0..9. Place the first 9 in the
    // 3x3 grid, the 10th in the bottom-row centre slot (where the "0"
    // sat in a fixed layout), and keep ⌫ pinned bottom-right so the
    // backspace doesn't move under the user.
    val d = digitOrder.map { it.toString() }
    val rows = listOf(
        listOf(d[0], d[1], d[2]),
        listOf(d[3], d[4], d[5]),
        listOf(d[6], d[7], d[8]),
        listOf("",   d[9], "⌫"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { cell ->
                    if (cell.isEmpty()) {
                        Spacer(modifier = Modifier.size(48.dp))
                    } else {
                        HexShape(
                            size = 48.dp,
                            borderColor = if (enabled) AegisBorder else AegisOnSurfaceDim,
                            fillColor = Color.Transparent,
                            onClick = {
                                if (!enabled) return@HexShape
                                if (cell == "⌫") onBackspace() else onDigit(cell)
                            },
                        ) {
                            Text(
                                cell,
                                color = if (enabled) AegisCyan else AegisOnSurfaceDim,
                                fontSize = if (cell == "⌫") 18.sp else 20.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

// Biometric helper removed — Aegis app lock is PIN only by directive.
// Biometric unlock survives only on the
// siren-stop sheet (a thief who grabs the phone shouldn't be able to
// silence the alarm without authenticating).

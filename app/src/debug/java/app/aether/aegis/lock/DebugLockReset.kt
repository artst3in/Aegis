package app.aether.aegis.lock

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import app.aether.aegis.profile.ProfileRegistry
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisSOS
import kotlinx.coroutines.delay

/**
 * Lock-screen emergency PIN-reset gesture — debug source-set variant
 * (real implementation).
 *
 * This file lives in `app/src/debug/java/` and is compiled ONLY into
 * the debug variant.
 * The release variant uses the no-op stub at
 * `app/src/release/java/app/aether/aegis/lock/DebugLockReset.kt`,
 * which means the AlertDialog code path, the gesture handler, and
 * the wipe logic literally do not exist in the release binary —
 * not behind a flag, not in dead R8 branches, not in the DEX at all.
 *
 * The user-facing contract:
 *   - Long-press the AEGIS title on LockScreen for 5 s. The gesture
 *     threshold is way above the platform default (~500 ms) so an
 *     accidental brush can't summon the dialog.
 *   - A confirmation dialog explains scope: wipes the PIN slot for
 *     every profile (and duress slots, since they're paired with
 *     the real PIN), clears the lockout counter, drops to unlocked
 *     state. Identity, vault, messages, and Device Owner status are
 *     untouched — vault has its own independent PIN-derived key.
 *
 * Defence in depth is the AlertDialog confirmation, not the hold
 * duration. The 5 s threshold is anti-accident UX, not a security
 * control.
 *
 * Was previously inline in LockScreen.kt behind
 * `if (BuildConfig.DEBUG)` branches; that pattern depended on R8
 * dead-code elimination to keep the dialog out of release. Moving
 * to a source-set split makes the separation a compile-time
 * guarantee.
 */

/** Always true in debug builds. The release stub returns false. */
val debugLockResetAvailable: Boolean = true

/**
 * Modifier wrapping a 5 s hold gesture. When the user presses and
 * holds the host composable for the full duration without lifting,
 * [onTriggered] fires; release before 5 s cancels.
 *
 * Self-contained — manages its own `holdPressedAt` state, so the
 * caller's only responsibility is to wire `onTriggered` into the
 * dialog-visibility state.
 */
@Composable
fun debugLockResetModifier(onTriggered: () -> Unit): Modifier {
    var holdPressedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(holdPressedAt) {
        val start = holdPressedAt ?: return@LaunchedEffect
        delay(5000L)
        if (holdPressedAt == start) {
            onTriggered()
            holdPressedAt = null
        }
    }
    return Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            holdPressedAt = System.currentTimeMillis()
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.all { !it.pressed }) {
                    holdPressedAt = null
                    break
                }
            }
        }
    }
}

/**
 * Tiny dim hint below the AEGIS title that tells a tester the
 * affordance exists without scraping the source. Release builds
 * render nothing.
 */
@Composable
fun DebugLockResetHint() {
    Text(
        "debug build · long-press title 5 s to reset PIN",
        color = AegisOnSurfaceDim,
        fontSize = 9.sp,
    )
}

/**
 * Confirmation dialog rendered when [show] is true. Tapping Reset
 * wipes the PIN slot for every profile via [ProfileRegistry] and
 * invokes [onConfirmed] (which the caller uses to clear local pin
 * entry state + drop to unlocked). Dismiss / cancel calls
 * [onDismiss] without touching any store.
 *
 * Multi-profile wipe is deliberate: lock-screen verification scans
 * every profile, so leaving a sibling profile's PIN intact would
 * just re-trigger the lock screen.
 */
@Composable
fun DebugLockResetConfirmation(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
) {
    if (!show) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset app lock?", color = AegisSOS) },
        text = {
            Text(
                "Wipes the PIN slot for every profile (including " +
                    "duress slots) and clears the lockout counter. " +
                    "Identity, vault, messages, and DO status are " +
                    "untouched — vault still requires its own PIN.\n\n" +
                    "Debug builds only.",
                color = AegisOnSurfaceDim,
                fontSize = 12.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val registry = ProfileRegistry.get(context)
                for (pid in registry.listProfiles()) {
                    LockStore.forProfile(context, pid).clearPin()
                }
                onConfirmed()
            }) { Text("Reset", color = AegisSOS) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AegisCyan)
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    )
}

package app.aether.aegis.lock

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Lock-screen emergency PIN-reset gesture — release source-set
 * variant (no-op stub).
 *
 * This file lives in `app/src/release/java/` and is compiled ONLY
 * into the release variant. The debug variant uses the real
 * implementation at
 * `app/src/debug/java/app/aether/aegis/lock/DebugLockReset.kt`.
 *
 * Every symbol below is a no-op: the modifier returns plain
 * [Modifier], the hint renders nothing, the confirmation dialog
 * is never shown. The AlertDialog code path, the gesture handler,
 * and the wipe logic literally do not exist in the release binary
 * — not behind a flag, not in dead R8 branches, not in the DEX at
 * all. That's the whole point of the source-set split.
 */

/** Always false in release builds. The debug impl returns true. */
val debugLockResetAvailable: Boolean = false

/** No-op modifier — release builds never wire the long-press
 *  gesture. The [onTriggered] callback is captured but unreachable. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun debugLockResetModifier(onTriggered: () -> Unit): Modifier = Modifier

/** No-op — the dim debug hint never appears in release. */
@Composable
fun DebugLockResetHint() {
    // Intentionally empty. Release builds don't render the hint.
}

/** No-op — the confirmation dialog never appears in release, even
 *  if [show] is true (which it never will be, since the modifier
 *  that flips it is also a no-op). */
@Composable
@Suppress("UNUSED_PARAMETER")
fun DebugLockResetConfirmation(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
) {
    // Intentionally empty.
}

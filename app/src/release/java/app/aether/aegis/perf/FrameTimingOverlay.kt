package app.aether.aegis.perf

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Frame-timing overlay — release source-set variant (no-op stub).
 *
 * This file lives in `app/src/release/java/` and is compiled ONLY
 * into the release variant. The debug variant uses the real
 * implementation at
 * `app/src/debug/java/app/aether/aegis/perf/FrameTimingOverlay.kt`.
 *
 * Nothing rendered, no Choreographer subscription, no rolling
 * buffer — the developer-only perf readout doesn't exist in the
 * release binary at all. Release users get a clean screen with no
 * floating pill in the top-right corner.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun FrameTimingOverlay(modifier: Modifier = Modifier) {
    // Intentionally empty. Release builds don't render the overlay.
}

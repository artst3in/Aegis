package app.aether.aegis.mugshot

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Tiny standalone LifecycleOwner so CameraX can bind from places that
 * don't own a real Activity / Fragment lifecycle — specifically the
 * remote-LOCATE handler, which fires from the SimpleX inbound coroutine
 * and has no UI context.
 *
 * Usage:
 *   val owner = HeadlessLifecycleOwner().apply { start() }
 *   try {
 *       MugshotCapture.captureAndShip(context, owner)
 *   } finally {
 *       owner.stop()
 *   }
 *
 * The registry transitions CREATED → STARTED → RESUMED on start(), and
 * STARTED → CREATED → DESTROYED on stop(). CameraX needs STARTED at
 * minimum to bind; RESUMED keeps it from auto-pausing the capture
 * pipeline mid-shot.
 */
class HeadlessLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = registry

    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

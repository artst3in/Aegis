package app.aether.aegis.sonar

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide sonar state + engine handle.
 *
 * The engine itself ([SonarEngine]) now lives here as a lazy
 * singleton rather than being owned by [SonarScreen], so once the
 * user starts sonar it keeps pinging even after they navigate away
 * — same lifetime as the app process. Without this, sonar would
 * stop the moment the user backed out of the raw-data viewer,
 * which is why "fired sonar, moved the phone around, nothing
 * happened" was the lived experience.
 *
 * Single source of truth for every consumer:
 *   - SonarScreen: drives start/stop, renders raw readings
 *   - SecurityScreen: reads counters for the Loki tile
 *   - The notification path: fires on each detection
 */
object SonarState {
    @Volatile private var engineInstance: SonarEngine? = null

    /** Lazy process-wide engine. First caller picks the Context; later
     *  callers reuse the same instance regardless of which screen they
     *  came from. Application context is fine — no Activity binding.
     *
     *  On first build we attach a detection callback that fires a
     *  notification — that's the missing piece between "engine ticks"
     *  and "user actually finds out something moved". */
    fun engine(context: Context): SonarEngine =
        engineInstance ?: synchronized(this) {
            engineInstance ?: SonarEngine(context.applicationContext).also { e ->
                val appCtx = context.applicationContext
                e.setOnDetection { ts, delta ->
                    SonarNotifier.notify(appCtx, ts, delta)
                }
                engineInstance = e
            }
        }

    /** True while the engine is actively pinging. The SonarScreen's
     *  start/stop toggle flips this. */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _detectionsToday = MutableStateFlow(0)
    val detectionsToday: StateFlow<Int> = _detectionsToday.asStateFlow()

    private val _lastDetectionAt = MutableStateFlow<Long?>(null)
    val lastDetectionAt: StateFlow<Long?> = _lastDetectionAt.asStateFlow()

    private var dayKey: Long = currentDayKey()

    fun setRunning(running: Boolean) { _running.value = running }

    fun recordDetection() {
        val now = System.currentTimeMillis()
        val today = currentDayKey()
        if (today != dayKey) {
            dayKey = today
            _detectionsToday.value = 0
        }
        _detectionsToday.value = _detectionsToday.value + 1
        _lastDetectionAt.value = now
    }

    private fun currentDayKey(): Long = System.currentTimeMillis() / 86_400_000L
}

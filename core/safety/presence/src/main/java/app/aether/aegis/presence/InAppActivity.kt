package app.aether.aegis.presence

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tracks whether the user is actively *in* the Aegis app — not just
 * whether the phone is unlocked or the process is alive.
 *
 * The green "online" dot on a peer's row was previously driven by the
 * status ticker's wall-clock timestamp: as long as ProtocolService
 * was running (i.e. the app was installed and the service was up), it
 * stamped lastActive = now every 60 s, so every contact looked
 * online forever. The user saw their second phone (untouched for
 * three hours) still shining green.
 *
 * Now: ProcessLifecycleOwner tells us when the WHOLE app process
 * enters / leaves the foreground. While in the foreground a heartbeat
 * coroutine bumps [lastForegroundMs] every 30 s. When backgrounded
 * the value freezes at the moment of the last refresh, so peers see
 * "last active 3 h ago" and render offline / away accordingly.
 *
 *   onStart  → record now, start heartbeat
 *   tick     → record now every [HEARTBEAT_MS]
 *   onStop   → record now (final stamp), stop heartbeat
 *   later    → value stays frozen at the onStop timestamp
 */
object InAppActivity {

    private const val HEARTBEAT_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null

    // Init 0L, NOT now(). When the process is (re)started in the BACKGROUND —
    // foreground-service restart, BOOT_COMPLETED, OEM revival — without the
    // user ever opening an Activity, isForegrounded stays false and nowOrLast()
    // serves this value. Seeding it with now() made a never-foregrounded device
    // broadcast "last active = just now", so peers showed it ONLINE (green)
    // while it was actually asleep/locked, decaying to away as the ONLINE
    // window expired. 0L reads as "never active in-app" → peers correctly see
    // Away (background heartbeat) / Offline until a real onStart stamps it.
    private val _lastForegroundMs = MutableStateFlow(0L)
    val lastForegroundMs: StateFlow<Long> = _lastForegroundMs.asStateFlow()

    @Volatile var isForegrounded: Boolean = false
        private set

    /** Wire ProcessLifecycleOwner. Call once at AegisApp.onCreate. */
    fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                stamp()
                isForegrounded = true
                heartbeatJob?.cancel()
                heartbeatJob = scope.launch {
                    while (isActive) {
                        delay(HEARTBEAT_MS)
                        stamp()
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                stamp()  // final stamp at the moment of backgrounding
                isForegrounded = false
                heartbeatJob?.cancel()
                heartbeatJob = null
            }
        })
    }

    /** The timestamp peers should treat as "last active in-app". When
     *  the app is currently in the foreground this is bumped to now
     *  on demand; when backgrounded it's the last stamped value. */
    fun nowOrLast(): Long =
        if (isForegrounded) System.currentTimeMillis()
        else _lastForegroundMs.value

    private fun stamp() {
        _lastForegroundMs.value = System.currentTimeMillis()
    }
}

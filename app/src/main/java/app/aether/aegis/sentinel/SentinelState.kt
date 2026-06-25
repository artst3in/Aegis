package app.aether.aegis.sentinel

import android.content.Context

/**
 * Process-wide [SentinelEngine] singleton — same pattern as
 * [app.aether.aegis.sonar.SonarState.engine]. Lets multiple call sites
 * (settings screen, lock-state observer, future event-log viewer)
 * share the same running engine instead of constructing competing
 * copies that would fight over the audio + sensor stack.
 */
object SentinelState {

    @Volatile private var instance: SentinelEngine? = null

    fun engine(context: Context): SentinelEngine =
        instance ?: synchronized(this) {
            instance ?: SentinelEngine(context.applicationContext).also { instance = it }
        }

    /** If prefs say "armed", spin the engine up on cold start. Called
     *  from AegisApp.onCreate so the cascade survives an OS-driven
     *  process kill. No-op when sentinel is disarmed.
     *
     *  Always starts the auto-arm observer if the user has opted in,
     *  even when the engine itself starts disarmed — the observer is
     *  what flips the engine to armed when bedside conditions match. */
    fun reArmIfNeeded(context: Context) {
        val prefs = SentinelPrefs(context)
        val e = engine(context)
        if (prefs.armed) e.arm()
        if (prefs.autoArmEnabled) e.startAutoArmObserver()
    }
}

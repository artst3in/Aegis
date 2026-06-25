package app.aether.aegis.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.aether.aegis.ui.GraphicsProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Voyager-style graceful degradation.
 *
 * One central gate every battery-sensitive subsystem consults before
 * starting work. As the battery drops, services power down in priority
 * order; the most critical signal (location → SimpleX → cellular) stays
 * alive the longest. Each binary gate carries 5 %-point hysteresis so
 * the state doesn't flap when the level hovers near a threshold.
 *
 * Thresholds (locked in with the user; do not change casually):
 *
 *   ≤80%  update polling
 *   ≤50%  snatch detection
 *   ≤40%  live sos camera stream (WebRTC video)
 *   ≤35%  status ticker throttled 60 s → 5 min
 *   ≤30%  sos GPS pings 10 s → 30 s
 *   ≤25%  live sos mic stream (WebRTC audio)
 *   ≤20%  sos GPS pings → 60 s
 *   ≤15%  sos audio chunks stop
 *   ≤10%  sos GPS pings → 5 min
 *   ≤5%   sos GPS pings → 1 hour (heartbeat-only)
 *   always alive: GPS + SimpleX + cellular radio
 */
class PowerBudget(private val context: Context) {

    /** 0..100. Read lazily from the platform's sticky battery intent. */
    private val _level = MutableStateFlow(readBatteryPercent())
    val level: StateFlow<Int> = _level

    private val _charging = MutableStateFlow(readCharging())
    val charging: StateFlow<Boolean> = _charging

    // Per-subsystem gates. Each carries the trigger threshold (below
    // which it powers down) plus the implicit 5 % hysteresis margin
    // (which it must re-cross going up to power back on).
    private val updatePolling     = Gate(offAt = 80)
    private val lunaGlassEffects  = Gate(offAt = 50)  // visual eye-candy — cosmetic, sheds early
    private val snatchDetection   = Gate(offAt = 50)
    private val sosCameraStream = Gate(offAt = 40)
    private val sosMicStream    = Gate(offAt = 25)
    private val sosAudioChunks  = Gate(offAt = 15)

    /** Battery-aware flag observed by AegisTheme to gate the LunaGlass
     *  enrichment layer at low battery. StateFlow form so Compose
     *  recomposes when the gate flips at the threshold crossing. */
    private val _lunaGlassAllowed = MutableStateFlow(true)
    val lunaGlassAllowed: StateFlow<Boolean> = _lunaGlassAllowed

    /**
     * Voyager-published **ceiling** for the Graphics profile slider.
     * The user's preferred profile is then `cappedBy` this ceiling —
     * they can always pick something equal-or-more conservative, but
     * never something more permissive than Voyager allows.
     *
     * Thresholds with 5 %-point hysteresis (same shape as every other
     * gate in this class):
     *
     *   battery ≥ 80 %  → Performance (no cap)
     *   battery 40-80 % → Balanced
     *   battery < 40 %  → PowerSaver only
     *
     * Hysteresis: once the ceiling drops, the battery has to climb 5
     * points above the drop threshold for it to rise again. With the
     * charger connected the ceiling is forced to Performance — there's
     * nothing to conserve.
     */
    private val perfCeilingGate = Gate(offAt = 80)   // ≥80% Performance, re-rise at 85
    private val balancedCeilingGate = Gate(offAt = 40)   // ≥40% Balanced, re-rise at 45
    private val _ceilingGraphicsProfile = MutableStateFlow(GraphicsProfile.Performance)
    val ceilingGraphicsProfile: StateFlow<GraphicsProfile> = _ceilingGraphicsProfile

    /** Refresh the cached battery snapshot. Cheap — sticky intent read. */
    fun refresh() {
        _level.value = readBatteryPercent()
        _charging.value = readCharging()
        val b = _level.value
        // Tick every gate so hysteresis state stays in sync.
        updatePolling.update(b)
        lunaGlassEffects.update(b)
        snatchDetection.update(b)
        sosCameraStream.update(b)
        sosMicStream.update(b)
        sosAudioChunks.update(b)
        perfCeilingGate.update(b)
        balancedCeilingGate.update(b)
        // Republish the LunaGlass gate as a StateFlow so AegisTheme's
        // CompositionLocal flips when the gate crosses its threshold.
        // While the charger is connected we override every gate to
        // "allowed" — there's nothing to conserve.
        _lunaGlassAllowed.value = _charging.value || lunaGlassEffects.isAllowed
        // Republish the Graphics-profile ceiling. While charging,
        // ceiling is forced to Performance (no cap) regardless of
        // current battery level.
        _ceilingGraphicsProfile.value = when {
            _charging.value              -> GraphicsProfile.Performance
            perfCeilingGate.isAllowed    -> GraphicsProfile.Performance
            balancedCeilingGate.isAllowed -> GraphicsProfile.Balanced
            else                         -> GraphicsProfile.PowerSaver
        }
    }

    // ----- Binary gates (consulted before starting a heavy job) -----
    //
    // Every gate short-circuits to true while the charger is connected.
    // The Voyager curve exists to extend runtime on battery; with wall
    // power there's no runtime to extend, so subsystems run at full
    // capacity regardless of what the battery percentage says.

    fun shouldRunUpdateCheck(): Boolean      = _charging.value || updatePolling.isAllowed
    fun shouldRunLunaGlassEffects(): Boolean = _charging.value || lunaGlassEffects.isAllowed
    fun shouldRunSnatchDetection(): Boolean  = _charging.value || snatchDetection.isAllowed
    fun shouldRunCameraStream(): Boolean     = _charging.value || sosCameraStream.isAllowed
    fun shouldRunMicStream(): Boolean        = _charging.value || sosMicStream.isAllowed
    fun shouldShipAudioChunks(): Boolean     = _charging.value || sosAudioChunks.isAllowed

    // ----- Throttle stages -----

    /** Status ticker cadence — 60 s by default, 5 min below 35 %.
     *  Default 60 s while charging regardless of battery level. */
    fun statusTickerMs(): Long {
        if (_charging.value) return 60_000L
        val b = _level.value
        return if (b <= 35) 5L * 60 * 1000 else 60_000L
    }

    /** SOS GPS cadence — 10 s → 30 s → 60 s → 5 min → 1 h.
     *  Default 10 s while charging regardless of battery level. */
    fun sosGpsIntervalMs(): Long {
        if (_charging.value) return 10_000L
        val b = _level.value
        return when {
            b <= 5  -> 60L * 60 * 1000   // 1 hour
            b <= 10 -> 5L * 60 * 1000    // 5 minutes
            b <= 20 -> 60_000L           // 60 seconds
            b <= 30 -> 30_000L           // 30 seconds
            else    -> 10_000L           // default
        }
    }

    /** True once battery hits the heartbeat-only band (≤5 %) — and we
     *  are NOT charging. With the charger connected we're never in
     *  heartbeat mode, even at 1 %. */
    fun isInHeartbeatBand(): Boolean = !_charging.value && _level.value <= 5

    // ----- Battery / charging reads -----

    private fun readBatteryPercent(): Int {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return 100  // missing intent — assume full so nothing trips
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 100
    }

    private fun readCharging(): Boolean {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * A single hysteresis-aware gate. Powers down at [offAt] (or below)
     * and only powers back on at offAt + [hysteresisMargin] (or above).
     * Default 5 %-point margin matches the rest of the curve.
     */
    private class Gate(
        private val offAt: Int,
        private val hysteresisMargin: Int = 5,
    ) {
        @Volatile var isAllowed: Boolean = true
            private set

        fun update(battery: Int) {
            if (isAllowed) {
                if (battery <= offAt) isAllowed = false
            } else {
                if (battery >= offAt + hysteresisMargin) isAllowed = true
            }
        }
    }
}

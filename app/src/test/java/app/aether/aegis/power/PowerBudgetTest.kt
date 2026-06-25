package app.aether.aegis.power

import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the Voyager power curve (SPEC_TESTING #19).
 *
 * The subsystem shed-thresholds are DOCUMENTED numeric invariants (the
 * CLAUDE.md permission ladder specifically protects them) — a silent
 * drift would either drain a phone that should be conserving, or kill
 * the SOS mic/camera too early in an emergency. These pin the curve:
 *
 *   update polling  off ≤80   LunaGlass fx    off ≤50
 *   snatch detect   off ≤50   SOS camera      off ≤40
 *   SOS mic         off ≤25
 *
 * + a 5-point hysteresis margin, and a hard charger override.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PowerBudgetTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun setBattery(pct: Int, charging: Boolean = false) {
        val i = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, pct)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(
                BatteryManager.EXTRA_STATUS,
                if (charging) BatteryManager.BATTERY_STATUS_CHARGING
                else BatteryManager.BATTERY_STATUS_DISCHARGING,
            )
        }
        @Suppress("DEPRECATION") app.sendStickyBroadcast(i)
    }

    private fun budgetAt(pct: Int, charging: Boolean = false): PowerBudget {
        setBattery(pct, charging)
        return PowerBudget(app).also { it.refresh() }
    }

    @Test fun full_battery_runs_every_subsystem() {
        val pb = budgetAt(95)
        assertTrue(pb.shouldRunUpdateCheck())
        assertTrue(pb.shouldRunLunaGlassEffects())
        assertTrue(pb.shouldRunSnatchDetection())
        assertTrue(pb.shouldRunCameraStream())
        assertTrue(pb.shouldRunMicStream())
    }

    @Test fun update_polling_sheds_first_below_80() {
        val pb = budgetAt(79)
        assertFalse("update polling sheds at ≤80%", pb.shouldRunUpdateCheck())
        assertTrue("LunaGlass fx (≤50) still runs at 79%", pb.shouldRunLunaGlassEffects())
    }

    @Test fun low_battery_sheds_cosmetic_and_heavy_streams() {
        val pb = budgetAt(20)
        assertFalse(pb.shouldRunLunaGlassEffects())  // ≤50
        assertFalse(pb.shouldRunCameraStream())      // ≤40
        assertFalse(pb.shouldRunMicStream())         // ≤25
    }

    @Test fun sos_mic_is_the_most_protected_stream() {
        // Most-protected non-charging gate: alive at 30%, dead at 20%.
        assertTrue(budgetAt(30).shouldRunMicStream())
        assertFalse(budgetAt(20).shouldRunMicStream())
    }

    @Test fun charging_overrides_every_gate() {
        val pb = budgetAt(3, charging = true)
        assertTrue(pb.shouldRunUpdateCheck())
        assertTrue(pb.shouldRunCameraStream())
        assertTrue(pb.shouldRunMicStream())
    }

    @Test fun gate_hysteresis_requires_climbing_past_the_margin() {
        val pb = PowerBudget(app)
        setBattery(79); pb.refresh()
        assertFalse(pb.shouldRunUpdateCheck())          // shed at ≤80
        setBattery(82); pb.refresh()
        assertFalse("must not re-enable before offAt+5", pb.shouldRunUpdateCheck())
        setBattery(85); pb.refresh()
        assertTrue("re-enables once ≥ offAt+margin", pb.shouldRunUpdateCheck())
    }
}

package app.aether.aegis.util

import android.content.pm.ActivityInfo
import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * zxing-android-embedded's default [CaptureActivity] honours
 * `setOrientationLocked(true)` by freezing to the *current* device
 * orientation when launched — which on a phone the user happens to
 * be tilting in landscape, or that goes through a brief sensor
 * rotation while the activity is mid-create, lands the scanner
 * permanently sideways. Pin the scanner activity to portrait
 * regardless of sensor state.
 */
class PortraitCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
    }
}

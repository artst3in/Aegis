package app.aether.aegis.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * [DEVICE_OWNER] Admin receiver for Aegis.
 *
 * Enables: remote lock, remote wipe, camera/mic policy,
 * VPN enforcement, app install restrictions.
 *
 * Set as Device Owner via:
 *   adb shell dpm set-device-owner app.aether.aegis/.admin.AegisAdminReceiver
 *
 * (Was previously "aegis/.admin..." — the applicationId rename
 * to app.aether.aegis in de79d1b made the old form fail with
 * "Unknown package name: aegis".)
 */
class AegisAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        // Device admin enabled — log it
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // Device admin disabled — this should never happen in production
    }
}

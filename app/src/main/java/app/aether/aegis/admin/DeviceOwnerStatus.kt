package app.aether.aegis.admin

import android.app.admin.DevicePolicyManager
import android.content.Context

/**
 * One-place check for "is Aegis provisioned as Device Owner?".
 *
 * Used as the 10th node in the skill tree — the gate between Gold
 * and Cyan tier (see ShieldTierEngine). The Cyan tier is what
 * unlocks the brand-cyan avatar frame and the one-shot "you are
 * Aegis" announcer notification.
 *
 * Cheap call (single DPM lookup), no caching needed — DO state
 * doesn't change inside a session.
 */
object DeviceOwnerStatus {

    fun isActive(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
            ?: return false
        return runCatching { dpm.isDeviceOwnerApp(context.packageName) }
            .getOrDefault(false)
    }
}

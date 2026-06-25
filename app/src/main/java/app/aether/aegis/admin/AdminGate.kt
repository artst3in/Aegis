package app.aether.aegis.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Thin probe around [DevicePolicyManager] for the Device Admin
 * registration state. Aegis's remote-access LOCATE command calls
 * `DevicePolicyManager.lockNow()` to drop the device to the lock
 * screen — that API throws `SecurityException` (or silently no-ops)
 * unless [AegisAdminReceiver] is an active admin.
 *
 * Pre-this-module nothing in the codebase ever enrolled the admin or
 * checked it was enrolled, so remote LOCATE returned with location
 * data but the screen never locked. Surfacing the gate lets the
 * sender's UI render "Lock won't work — enable Device Admin first"
 * with a one-tap intent, and lets [app.aether.aegis.remote.RemoteCommandHandler]
 * fall back gracefully when admin is missing.
 */
object AdminGate {

    fun component(context: Context): ComponentName =
        ComponentName(context, AegisAdminReceiver::class.java)

    fun isActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching { dpm.isAdminActive(component(context)) }.getOrDefault(false)
    }

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
    }

    /** Intent that launches the OS Add-Device-Admin confirmation
     *  screen. Caller starts it from an Activity context. The user
     *  sees Aegis's admin-policies XML and taps Activate. */
    fun enrollIntent(context: Context, explanation: String): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
        }
}

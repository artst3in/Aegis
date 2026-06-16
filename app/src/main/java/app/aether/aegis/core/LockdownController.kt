package app.aether.aegis.core

import app.aether.aegis.admin.AegisAdminReceiver
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * The "phone unkillable + silent" sos mode.
 *
 * Activated by SOSHandler whenever a sos trigger fires. The full
 * lockdown (power-off suppression, lock-task pinning, status-bar lock)
 * requires Device Owner; without DO, we fall back to a best-effort
 * partial: ringer/audio mute + brightness dim only.
 *
 * This supersedes the old "lockNow() on sos" behaviour:
 * sos now KEEPS the device unlocked + dims the screen to minimum
 * instead. Dim + unlocked = stealth (no bright glow attracts attention)
 * + usability (the owner can still see the dashboard, send updates,
 * cancel). A bright glowing locked screen was the worst of both
 * worlds.
 *
 * Effects, all reversed by `clear()`:
 *   1. Screen brightness driven to ~1 % on the sos activity's
 *      window (stealth — looks dead from the outside, fully
 *      interactive from above).
 *   2. Power-off menu suppressed — the long-press power button no
 *      longer offers "Power off" / "Restart" (Lock Task feature flag).
 *   3. All notifications hidden from the lock screen.
 *   4. App pinned via lock-task mode — recents / home / back disabled.
 *   5. Ringer + every audio stream forced silent + muted.
 *   6. Status bar locked.
 */
class LockdownController(private val context: Context) {

    data class PreviousState(
        val ringerMode: Int,
        val streamVolumes: Map<Int, Int>,
    )

    private var previous: PreviousState? = null
    private var lockTaskActive = false
    private var dimmedActivity: java.lang.ref.WeakReference<Activity>? = null
    private var previousBrightness: Float = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    fun engage(sosActivity: Activity?) {
        Log.i(TAG, "engaging sos lockdown")
        snapshotAudioState()
        muteEverything()
        if (isDeviceOwner()) {
            applyDeviceOwnerLockdown(sosActivity)
        }
        // Dim, don't lock. Apply on the sos activity's
        // window so navigating within the same Activity (Compose) keeps
        // the dim active. 0.01f is effectively the lowest the OS will
        // honour without falling back to "use system default".
        sosActivity?.let { dimWindow(it, 0.01f) }
    }

    fun clear() {
        Log.i(TAG, "clearing sos lockdown")
        restoreAudioState()
        if (lockTaskActive) {
            runCatching {
                dpm()?.setLockTaskPackages(adminComponent(), emptyArray())
            }
            lockTaskActive = false
        }
        // Reverse the DPM policies that engage() applied. Without this,
        // a sos that engaged the DO-side disables (keyguard features,
        // status bar) left them stuck even after cancel — user lost
        // fingerprint + the notification shade until they cleared DO
        // entirely via adb. Reverse unconditionally, idempotent.
        clearDeviceOwnerLockdown()
        restoreBrightness()
        previous = null
    }

    /** Reverse every DPM policy this class can apply. Called from
     *  [clear] on sos end AND from [forceClearDpmPolicies] at app
     *  start so a previous-process sos that died without cancelling
     *  doesn't leave the user stuck. Idempotent — no-ops cleanly when
     *  we're not DO. */
    private fun clearDeviceOwnerLockdown() {
        val dpm = dpm() ?: return
        if (!isDeviceOwner()) return
        val admin = adminComponent()
        runCatching {
            dpm.setKeyguardDisabledFeatures(
                admin,
                DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE,
            )
        }.onFailure { Log.w(TAG, "restore keyguard features failed: $it") }
        runCatching {
            dpm.setStatusBarDisabled(admin, false)
        }.onFailure { Log.w(TAG, "restore status bar failed: $it") }
    }

    /** Belt-and-braces recovery — called at app start so any stale
     *  lockdown left over from a force-killed previous-process sos
     *  is reverted before the user sees a phone with no fingerprint
     *  and no notification shade. */
    fun forceClearDpmPolicies() {
        clearDeviceOwnerLockdown()
    }

    /** Public hook for the brightness gesture.
     *  Caller is responsible for clamping; 0.01..1.0 is the OS-honoured
     *  range. */
    fun setBrightness(activity: Activity, value: Float) {
        runCatching {
            val lp = activity.window.attributes
            lp.screenBrightness = value.coerceIn(0.01f, 1f)
            activity.window.attributes = lp
        }
    }

    private fun dimWindow(activity: Activity, value: Float) {
        runCatching {
            val lp = activity.window.attributes
            previousBrightness = lp.screenBrightness
            lp.screenBrightness = value
            activity.window.attributes = lp
            dimmedActivity = java.lang.ref.WeakReference(activity)
        }.onFailure { Log.w(TAG, "dimWindow failed: $it") }
    }

    private fun restoreBrightness() {
        val activity = dimmedActivity?.get() ?: return
        runCatching {
            val lp = activity.window.attributes
            lp.screenBrightness = previousBrightness
            activity.window.attributes = lp
        }
        dimmedActivity = null
        previousBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }

    private fun snapshotAudioState() {
        val am = audioManager() ?: return
        val volumes = STREAMS.associateWith { am.getStreamVolume(it) }
        previous = PreviousState(ringerMode = am.ringerMode, streamVolumes = volumes)
    }

    private fun muteEverything() {
        val am = audioManager() ?: return
        runCatching { am.ringerMode = AudioManager.RINGER_MODE_SILENT }
        // The sos walkie-talkie is one-way (victim →
        // family). PTT-back audio stays muted on the victim's speaker
        // so the attacker can't hear anything; if the victim has
        // wired or Bluetooth headphones plugged in, we leave the
        // voice-call stream audible so the audio routes there and
        // ONLY there (Android routes STREAM_VOICE_CALL to the active
        // SCO/A2DP device automatically when one is connected).
        val earbudsConnected = hasEarbuds(am)
        STREAMS.forEach { stream ->
            if (stream == AudioManager.STREAM_VOICE_CALL && earbudsConnected) {
                // Leave voice-call at its prior level so PTT-back is
                // audible exclusively in the owner's ear.
                return@forEach
            }
            runCatching { am.setStreamVolume(stream, 0, 0) }
        }
    }

    private fun hasEarbuds(am: AudioManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            return am.isWiredHeadsetOn || am.isBluetoothA2dpOn || am.isBluetoothScoOn
        }
        return runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
                when (d.type) {
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_HEARING_AID -> true
                    else -> false
                }
            }
        }.getOrDefault(false)
    }

    private fun restoreAudioState() {
        val am = audioManager() ?: return
        val prev = previous ?: return
        runCatching { am.ringerMode = prev.ringerMode }
        prev.streamVolumes.forEach { (stream, vol) ->
            runCatching { am.setStreamVolume(stream, vol, 0) }
        }
    }

    private fun applyDeviceOwnerLockdown(sosActivity: Activity?) {
        val dpm = dpm() ?: return
        val admin = adminComponent()
        runCatching {
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS.inv().inv(),  // suppress notifications
                )
            }
            sosActivity?.let { act ->
                act.startLockTask()
                lockTaskActive = true
            }
        }.onFailure { Log.w(TAG, "lock-task setup failed: $it") }

        runCatching {
            dpm.setKeyguardDisabledFeatures(
                admin,
                DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL,
            )
        }
        runCatching {
            dpm.setStatusBarDisabled(admin, true)
        }
    }

    private fun audioManager(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun dpm(): DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    private fun adminComponent() = ComponentName(context, AegisAdminReceiver::class.java)

    private fun isDeviceOwner(): Boolean = dpm()?.isDeviceOwnerApp(context.packageName) == true

    private companion object {
        private const val TAG = "LockdownController"
        // Every audio stream we can mute. Even alarm and accessibility
        // — the requirement is "no sounds, period".
        private val STREAMS = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_DTMF,
            AudioManager.STREAM_ACCESSIBILITY,
        ).toList()
    }
}

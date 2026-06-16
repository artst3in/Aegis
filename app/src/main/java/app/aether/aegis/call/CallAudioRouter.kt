package app.aether.aegis.call

import app.aether.aegis.AegisApp
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audio-routing surface for live calls — Tier 1 c of the call-screen
 * TODO. WhatsApp, Telegram, Signal, Google Phone all ship a "speaker /
 * earpiece / Bluetooth" toggle on the call screen; Aegis didn't.
 *
 * On API 31+ (Android 12, our floor + 2): drives
 * [AudioManager.setCommunicationDevice] which is the modern call-
 * routing API. Tracks which physical device is currently selected via
 * [AudioManager.getCommunicationDevice]; lists what's available via
 * [AudioManager.getAvailableCommunicationDevices].
 *
 * On API 29-30: falls back to the legacy split — [setSpeakerphoneOn]
 * for the speaker/earpiece flip; we don't surface Bluetooth on old
 * Android because pre-31 SCO routing was a footgun-fest (manual
 * startBluetoothSco, BroadcastReceiver state tracking, etc.) we
 * don't need to ship to fix the 90% case.
 */
object CallAudioRouter {

    private const val TAG = "CallAudioRouter"

    enum class Route { Earpiece, Speaker, Bluetooth, Wired }

    /** Routes the device can currently use for the active call.
     *  Speaker + earpiece are always present on phones; Bluetooth /
     *  wired show up only when something's plugged in / paired. */
    private val _available = MutableStateFlow(listOf(Route.Earpiece, Route.Speaker))
    val available: StateFlow<List<Route>> = _available.asStateFlow()

    /** Currently-selected route. Null when no call is up. */
    private val _current = MutableStateFlow<Route?>(null)
    val current: StateFlow<Route?> = _current.asStateFlow()

    /** Refresh [available] from AudioManager. Cheap; safe to call on
     *  every call-screen recomposition. */
    fun refresh() {
        val ctx = AegisApp.instance
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val routes = mutableSetOf(Route.Earpiece, Route.Speaker)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices.forEach { dev ->
                deviceToRoute(dev)?.let { routes += it }
            }
            // Reflect whatever the system thinks is the current route
            // so the UI's selected pill stays in sync with the OS.
            val cur = am.communicationDevice?.let(::deviceToRoute)
            if (cur != null) _current.value = cur
        } else {
            // Pre-31: derive from speakerphone state + wired-headset
            // presence. Bluetooth not exposed; user uses system
            // controls if they need it.
            @Suppress("DEPRECATION")
            val plugged = am.isWiredHeadsetOn
            if (plugged) routes += Route.Wired
        }
        _available.value = routes.toList()
    }

    /** Apply a route. Returns false if the platform refused (rare;
     *  usually means the device disconnected between [refresh] and
     *  [select], e.g. you yanked a Bluetooth headset). */
    fun select(route: Route): Boolean {
        val ctx = AegisApp.instance
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        Log.i(TAG, "switching audio route → $route")
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val target = am.availableCommunicationDevices
                .firstOrNull { deviceToRoute(it) == route }
            if (target == null) {
                Log.w(TAG, "route $route not available on this device")
                false
            } else {
                am.setCommunicationDevice(target)
            }
        } else {
            // Legacy path — speaker on/off is the only knob we expose.
            @Suppress("DEPRECATION")
            when (route) {
                Route.Speaker  -> { am.isSpeakerphoneOn = true; true }
                Route.Earpiece -> { am.isSpeakerphoneOn = false; true }
                else           -> false
            }
        }
        if (ok) _current.value = route
        return ok
    }

    /** Reset state when the call ends. */
    fun clear() {
        _current.value = null
        _available.value = listOf(Route.Earpiece, Route.Speaker)
    }

    private fun deviceToRoute(dev: AudioDeviceInfo): Route? = when (dev.type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Route.Earpiece
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER  -> Route.Speaker
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET      -> Route.Bluetooth
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET      -> Route.Wired
        else                                   -> null
    }
}

package app.aether.aegis.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Single source of truth for "is the active connection one the user would
 * consider metered / mobile-data?".
 *
 * WHY this exists: three OTA-update gates (AutoUpdateCheck,
 * UpdateCheckWorker, UpdateSettingsScreen) each hand-rolled this check, and
 * the attachment auto-download gate needs the SAME rule. Three copies had
 * already drifted — two used the transport test below, the third used the
 * unreliable [NetworkCapabilities.NET_CAPABILITY_NOT_METERED] capability —
 * so a user on Wi-Fi could see different answers depending on which path
 * asked. Consolidating here means the answer is defined once.
 *
 * WHY transport-based, not the NOT_METERED capability: NET_CAPABILITY_
 * NOT_METERED is set by the carrier/OEM and is wrong in the field often
 * enough to matter — some Pixel builds, many VPNs, and captive-portal
 * Wi-Fi report a Wi-Fi link as "metered", which would defer downloads the
 * user explicitly wanted on Wi-Fi. We instead treat the LINK TYPE as the
 * authority: Wi-Fi and Ethernet are unmetered; everything else (cellular,
 * and the "we genuinely don't know" cases) is metered. Erring toward
 * "metered" is the safe default for this app — it defers, costing the user
 * a tap, never a surprise data bill.
 */
object NetworkMetering {

    /**
     * True when the active network is NOT Wi-Fi/Ethernet — i.e. treat it as
     * mobile data and gate accordingly. Returns true (metered) whenever the
     * connection state can't be read: no ConnectivityManager, no active
     * network, or no capabilities. A null/unknown link is assumed costly on
     * purpose (see class doc).
     */
    fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(net) ?: return true
        val onWifiOrEthernet =
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return !onWifiOrEthernet
    }
}

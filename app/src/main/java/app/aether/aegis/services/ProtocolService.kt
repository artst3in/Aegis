package app.aether.aegis.services

import app.aether.aegis.AegisApp
import app.aether.aegis.R
import app.aether.aegis.core.SOSTrigger
import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Single foreground service for the whole app.
 *
 * Replaces four separate foreground services with one persistent
 * notification ("Aegis · shield active") that supervises:
 *   - ProtocolManager (transports + outbox drain)
 *   - Location streaming → repository.patchLocation
 *   - Battery/network ticker → repository.patchDeviceStatus
 *   - Accelerometer-based snatch detection → SOSHandler.trigger
 *
 * Pause vs stop: the notification's action is "Pause", not "Turn
 * off" — tapping it stops the inner work (location, sensors, ticker,
 * protocol, wireguard) but keeps the service alive in the
 * foreground, so the notification stays put and switches to a red
 * "shield paused" badge with a "Resume" action. The only way to
 * fully kill it is to force-stop the app from system settings; this
 * is deliberate, since the family-safety promise breaks the moment a
 * snitch notification can be quietly swiped away.
 */
class ProtocolService : Service(), SensorEventListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val locationManager by lazy {
        getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    /**
     * AOSP LocationListener — replaces FusedLocationProviderClient so
     * we don't depend on Google Play Services for GPS streaming. Talks
     * to the platform GPS / network providers directly.
     */
    /** Last successfully-broadcast location + timestamp — used to
     *  throttle the outbound LOCATION fan-out so we don't burn battery
     *  + bandwidth sending every 30 s GPS tick over SimpleX. */
    @Volatile private var lastBroadcastLat: Double = Double.NaN
    @Volatile private var lastBroadcastLng: Double = Double.NaN
    @Volatile private var lastBroadcastAtMs: Long = 0L

    private val locationListener = LocationListener { loc ->
        // Feed crash detection first (cheap, synchronous) — it keeps a
        // 60 s speed history and arms/disarms its own accelerometer.
        app.aether.aegis.crashdetection.CrashDetector.onLocation(loc)
        scope.launch {
            AegisApp.instance.repository.patchLocation(
                peerKey = AegisApp.instance.identity.deviceId,
                latitude = loc.latitude,
                longitude = loc.longitude,
                ts = System.currentTimeMillis(),
            )
            // Routine fan-out to family. Skip if (a) we've broadcast within
            // the current broadcast interval, AND (b) the user hasn't
            // moved more than MIN_BROADCAST_METERS since the last broadcast.
            // Either being false trips a fresh broadcast.
            //
            // The interval scales with battery — "Voyager mode": Voyager 1
            // still transmits from 24 billion km on 470 W, and Aegis keeps
            // phoning home at low charge too, just less often. Below 10 %
            // we drop to one push per hour (heartbeat-only); below 30 %
            // we step down to 15 min; otherwise the normal 5 min.
            //
            // While charging we skip the curve entirely and run at the
            // default cadence — there's no battery to conserve.
            val interval = broadcastIntervalMs()
            val now = System.currentTimeMillis()
            val movedFar = if (lastBroadcastLat.isNaN()) true
                else haversineMeters(lastBroadcastLat, lastBroadcastLng, loc.latitude, loc.longitude) > MIN_BROADCAST_METERS
            val timeElapsed = now - lastBroadcastAtMs > interval
            if (movedFar || timeElapsed) {
                broadcastLocationToFamily(loc.latitude, loc.longitude, now)
                lastBroadcastLat = loc.latitude
                lastBroadcastLng = loc.longitude
                lastBroadcastAtMs = now
            }
            // Geofence: if armed, evaluate every fresh GPS event for
            // an inside→outside transition. Edge-triggered, idempotent.
            runCatching {
                app.aether.aegis.geofence.GeofenceEvaluator
                    .evaluate(this@ProtocolService, loc.latitude, loc.longitude)
                    ?.let { app.aether.aegis.geofence.GeofenceEvaluator.broadcast(it) }
            }
        }
    }

    private var sensorManager: SensorManager? = null
    private var accel: Sensor? = null
    private var snatchSpikeAtMs = 0L
    private var snatchMotionSamples = 0

    /** True iff the user has asked us to stand down via the notification action. */
    @Volatile private var paused = false
    /** Cancellable handle for the status ticker so resume() can recreate it. */
    private var statusTickerJob: Job? = null
    /** Watchdog that re-posts the notification if the user swipes it away. */
    private var notificationWatchdogJob: Job? = null
    /** Set true once we've successfully registered the location
     *  listener with at least one provider. Read by the watchdog
     *  below to decide whether re-arming is needed. */
    @Volatile private var locationStreamArmed = false
    /** Cancellable handle for the location-stream watchdog — re-runs
     *  startLocationStream every minute so a permission granted after
     *  the service launched, or a system GPS toggle, is picked up
     *  without restarting the app. */
    private var locationWatchdogJob: Job? = null

    /**
     * Trust-containers Phase 1 — presence-module gate. True iff
     * the user has ≥1 Trusted contact. Fed live by [trustedCountFlow]
     * collection in [startWork]; read at the top of [startLocationStream]
     * so the GPS listener is never registered while there's nobody to
     * share location with. Removing the last Trusted contact tears the
     * listener down; adding the first arms it. (The status ticker is
     * deliberately NOT gated on this — it is shared infra that also
     * drives the power budget + snatch reconcile, and its location/
     * status broadcasts already no-op on an empty Trusted set.) */
    @Volatile private var presenceTierActive = false

    /**
     * Trust-containers Phase 1 — sos-module gate (snatch
     * detection half). True iff there is ≥1 sos-eligible (Trusted ∪
     * Emergency) contact. Fed by [sosCountFlow] in [startWork]; gates
     * the accelerometer snatch detector — with nobody to alert there is
     * nothing for a snatch to trigger, so the ~50 Hz sensor is not run.
     * (The power-button half of the sos gate lives in AegisApp.) */
    @Volatile private var sosTierActive = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PAUSE -> {
                    android.util.Log.i(TAG, "user requested pause via notification")
                    pause()
                }
                ACTION_RESUME -> {
                    android.util.Log.i(TAG, "user requested resume via notification")
                    resume()
                }
                ACTION_REARM_LOCATION -> {
                    android.util.Log.i(TAG, "rearm location requested via diagnostics")
                    startLocationStream()
                }
                ACTION_RESTART_TRANSPORT -> {
                    android.util.Log.i(TAG, "restart transport requested via diagnostics")
                    scope.launch {
                        runCatching { AegisApp.instance.protocolManager.stop() }
                        runCatching { AegisApp.instance.protocolManager.start() }
                    }
                }
                ACTION_REFRESH_PRESENCE -> {
                    // Chat-activity nudge (ProtocolManager fires this on a
                    // user-visible send). While you're actively messaging,
                    // push fresh status + last-known location to Trusted
                    // contacts NOW instead of waiting out the ≤5-min status
                    // ticker — so their view of your battery/GPS is live.
                    // Debounced so a burst of messages = one refresh.
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - lastActivityRefreshAtMs >= ACTIVITY_REFRESH_MIN_MS) {
                        lastActivityRefreshAtMs = nowMs
                        scope.launch { runCatching { refreshPresenceNow() } }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Honour the persisted toggle — if the user paused us before a
        // reboot / force-stop, come back paused. They must explicitly
        // tap Resume; we never silently re-arm.
        paused = !AegisApp.instance.protectionEnabled
        try {
            // Android 14+ requires startForeground to declare the FGS
            // types that match the manifest. Omitting LOCATION here was
            // why requestLocationUpdates silently stopped delivering
            // callbacks the moment the app moved to background — GPS
            // looked dead from the user's POV. DATA_SYNC stays for the
            // SimpleX maintenance work this service also does.
            // Manifest declares location|dataSync|microphone|camera|specialUse.
            // mic + camera are passed so Android 14+ FGS-type enforcement
            // permits the call WebView's getUserMedia (without microphone in
            // the FGS type even an Activity-side AudioRecord init fails with
            // "Could not start audio source" app-wide while the service runs).
            //
            // The PERSISTENT type is the load-bearing part. Aegis's transport
            // service is always-on, but Android 14 (API 34) caps a *dataSync*
            // FGS at ~6 h per 24 h and then HARD-CRASHES the app with
            // ForegroundServiceDidNotStopInTimeException (user-reported
            // overnight crash). dataSync is for discrete transfers, not a
            // persistent connection — so on 34+ we run as specialUse (the
            // declared always-on E2E messaging link), which has no such cap.
            // Pre-34 has no cap and no specialUse type, so dataSync stays.
            // Prefer the FULL mask (mic+camera so capture can use them when
            // we're foreground-eligible), but DEGRADE rather than die if the OS
            // refuses — see startForegroundDegrading. A dead transport is the
            // worst possible outcome, so it must survive a background (re)start
            // even when mic/camera can't be claimed.
            if (!startForegroundDegrading()) {
                android.util.Log.e(TAG, "startForeground failed for every mask; stopping")
                stopSelf()
                return
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "startForeground failed", t)
            stopSelf()
            return
        }

        // ContextCompat handles the RECEIVER_EXPORTED flag requirement on
        // Android 14+ — receiver is explicitly NOT exported because the
        // control actions only ever fire from our own notification.
        val filter = IntentFilter().apply {
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
            addAction(ACTION_REARM_LOCATION)
            addAction(ACTION_RESTART_TRANSPORT)
            addAction(ACTION_REFRESH_PRESENCE)
        }
        ContextCompat.registerReceiver(
            this, controlReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        if (!paused) startWork()
        startNotificationWatchdog()
        // Listen for network changes regardless of pause state — even
        // when paused we want to be ready to reconnect the moment the
        // user resumes, and registering the callback is cheap. Lives
        // until onDestroy.
        registerNetworkCallback()
    }

    /**
     * Android 13+ lets the user swipe a foreground-service notification
     * away regardless of FLAG_NO_CLEAR / setOngoing — the system no
     * longer treats those as absolute. The notification disappearing
     * silently is exactly the failure mode the family-safety contract
     * forbids, so we poll every few seconds and re-post if the OS has
     * removed our notification. This is the only reliable way to
     * achieve "the only way to clear it is to force-stop the app".
     */
    private fun startNotificationWatchdog() {
        notificationWatchdogJob?.cancel()
        notificationWatchdogJob = scope.launch {
            while (isActive) {
                delay(NOTIFICATION_REPOST_CHECK_MS)
                runCatching {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE)
                        as android.app.NotificationManager
                    val present = nm.activeNotifications.any {
                        it.id == NOTIFICATION_ID && it.packageName == packageName
                    }
                    if (!present) {
                        android.util.Log.w(TAG, "notification dismissed — reposting")
                        NotificationManagerCompat.from(this@ProtocolService)
                            .notify(NOTIFICATION_ID, buildNotification(paused))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY  // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Belt-and-suspenders for Android 14+'s foreground-service timeout.
     * The system calls this shortly before a *time-capped* FGS type (e.g.
     * dataSync, ~6 h/24 h) hits its limit, and will throw
     * ForegroundServiceDidNotStopInTimeException — crashing the app — if we
     * don't act. We already migrated the persistent type off dataSync to
     * specialUse (uncapped) in onCreate, so this shouldn't fire; if it ever
     * does (a future capped type, an OEM quirk), re-affirm the foreground
     * as specialUse only so the always-on service SURVIVES instead of
     * taking the whole app down. Falls back to a clean stop if even that
     * fails. Only invoked on API 34+; a no-op presence on older devices.
     */
    override fun onTimeout(startId: Int) {
        android.util.Log.w(TAG, "FGS onTimeout(startId=$startId) — re-affirming FGS (degrading)")
        // Re-affirm the foreground, degrading the type mask if the OS refuses
        // (a background re-affirm can't keep mic/camera on 14+). Surviving on a
        // reduced mask beats taking the whole app down on the FGS timeout.
        if (!startForegroundDegrading()) {
            android.util.Log.e(TAG, "onTimeout re-affirm failed for every mask; stopping to avoid hard crash")
            stopSelf()
        }
    }

    /**
     * Foreground the service, preferring the FULL type mask but DEGRADING to
     * progressively smaller ones if the OS refuses, so the always-on transport
     * SURVIVES instead of being torn down. Returns true if any mask stuck.
     *
     * Why this exists: on Android 14+ a microphone/camera-typed FGS cannot be
     * (re)started from the background — startForeground throws SecurityException
     * ("…must be in the eligible state … foreground microphone") — and a
     * background cold-start can also hit ForegroundServiceStartNotAllowed. The
     * previous code called stopSelf() on ANY startForeground failure, so a
     * background restart with mic+camera in the mask could kill the whole
     * transport (and capture stayed broken). We now fall back to the
     * persistent-only types (specialUse/dataSync, then +location) so the
     * messaging link stays up. Capture still needs the full mask claimed from a
     * foreground-eligible state; that on-demand elevation is tracked in #8/#33.
     */
    private fun startForegroundDegrading(): Boolean {
        for (mask in foregroundMaskFallbacks()) {
            val r = runCatching {
                androidx.core.app.ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification(paused), mask,
                )
            }
            if (r.isSuccess) return true
            android.util.Log.w(TAG, "startForeground rejected for mask=$mask — degrading", r.exceptionOrNull())
        }
        return false
    }

    /**
     * FGS type masks to try in order, WIDEST first. The widest carries
     * mic+camera (so capture runs when we're foreground/eligible — the call
     * WebView's getUserMedia and every remote/SOS/mugshot capture need those
     * types in the live FGS); each fallback drops the types most likely to be
     * refused from the background. LOCATION keeps GPS callbacks alive; the
     * PERSISTENT type is specialUse on 34+ (uncapped; dataSync is capped
     * ~6h/24h and hard-crashes the always-on service) and dataSync below 34.
     * None of these are time-capped on 34+, so re-affirming can't re-trigger
     * the timeout.
     */
    private fun foregroundMaskFallbacks(): List<Int> {
        val persistent =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val location = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val mic = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val camera = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return listOf(
            location or persistent or mic or camera,  // full — capture-capable (foreground/eligible)
            location or persistent,                    // drop mic+camera (background-safe-ish)
            persistent,                                // bare always-on type — last resort
        )
    }

    /**
     * Drops the SimpleX core's SMP relay subscriptions and rebuilds
     * them from scratch — mirror of upstream's `reconnectAllServers`
     * (SimpleXAPI.kt:1369). Fired by [networkCallback] on every
     * platform-level network change. Without this, the SimpleX core's
     * TCP reads can sit silently on a dead route after a wifi → cell
     * flip (or VPN toggle, captive portal cleared, IP renewal) until
     * the OS finally times out the socket — by which time messages
     * have been parked at the relay un-fetched and we look offline
     * to peers. This is the most common cause of the "both ends say
     * the other is offline" symptom on otherwise healthy devices.
     */
    private fun reconnectSimplex(reason: String) {
        scope.launch {
            val transports = runCatching { AegisApp.instance.transports }.getOrNull()
                ?: return@launch
            transports.filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>().forEach { t ->
                runCatching {
                    app.aether.aegis.simplex.ConnectionLog.log(
                        "ProtocolService",
                        "network event ($reason) — /reconnect",
                    )
                    t.apiReconnect()
                }.onFailure {
                    app.aether.aegis.simplex.ConnectionLog.warn(
                        "ProtocolService",
                        "apiReconnect threw: ${it.message}",
                    )
                }
            }
        }
    }

    // --- Network-change → /reconnect: edge-triggered + debounced ---
    //
    // onCapabilitiesChanged fires on EVERY capability update the OS
    // makes for the default network — signal-strength steps, bandwidth
    // re-estimates, metered/temp-not-metered flips — not just real
    // connectivity transitions. On a live device that is several times a
    // second. The old code re-fired /reconnect on each one whenever the
    // network merely stayed VALIDATED, so the SMP relay links were torn
    // down and rebuilt continuously and never settled: nonstop
    // hostDisconnected/hostConnected churn, multi-second delivery lag,
    // and messages cut off mid-exchange (the peer logged messageError /
    // decrypt failures). Worst of all it was asymmetric in effect —
    // sending is a quick push that slips between reconnects, but
    // receiving needs a SUSTAINED subscription the storm kept resetting,
    // so a device could send yet never reliably receive.
    //
    // Fix: only treat a genuine EDGE as a reconnect trigger — a brand-new
    // default network, or validation flipping false→true for the current
    // one — and DEBOUNCE the actual /reconnect so the burst of callbacks
    // during a real network switch collapses into a single reconnect once
    // it settles. lastReconnectNetwork / lastValidated track the edge.
    @Volatile private var lastReconnectNetwork: android.net.Network? = null
    @Volatile private var lastValidated = false
    private var reconnectDebounceJob: kotlinx.coroutines.Job? = null

    /** Coalesce reconnect requests: a network switch emits onAvailable +
     *  several onCapabilitiesChanged in quick succession, and we want ONE
     *  reconnect after the dust settles, not one per callback. */
    private fun scheduleReconnect(reason: String) {
        reconnectDebounceJob?.cancel()
        reconnectDebounceJob = scope.launch {
            delay(RECONNECT_DEBOUNCE_MS)
            reconnectSimplex(reason)
        }
    }

    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        // A new network became the default (wifi connects after cell, VPN
        // toggle, etc.). Don't reconnect yet — defer to the
        // onCapabilitiesChanged that always follows, so we only reconnect
        // on a vetted route. Just reset edge-tracking so that network's
        // validation transition is recognised as new.
        override fun onAvailable(network: android.net.Network) {
            if (network != lastReconnectNetwork) {
                lastReconnectNetwork = network
                lastValidated = false
            }
        }

        // Fires on every capability update for the default network. Only
        // the RISING edge of validation (or a brand-new network arriving
        // already validated) is a real "route just became usable" event
        // worth a reconnect; a network that is already validated emitting
        // further capability updates is not, and must NOT re-kick.
        override fun onCapabilitiesChanged(
            network: android.net.Network,
            caps: android.net.NetworkCapabilities,
        ) {
            val validated =
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val networkChanged = network != lastReconnectNetwork
            when {
                validated && (networkChanged || !lastValidated) -> {
                    // New usable route, or this one just passed validation.
                    lastReconnectNetwork = network
                    lastValidated = true
                    scheduleReconnect("validated")
                }
                !validated -> {
                    // Lost validation (captive portal, dead route) — arm
                    // the next rising edge so it triggers a reconnect.
                    if (network == lastReconnectNetwork) lastValidated = false
                }
                // else: already validated, no real change — ignore.
            }
        }

        // The default network went away entirely (airplane mode, radio off,
        // last route dropped). Arm the next rising edge for reconnect AND tell
        // the transport to flip its relays to disconnected immediately — the
        // core's own hostDisconnected lags a dead socket by the TCP timeout, so
        // without this the Network card kept claiming "connected to relays"
        // while plainly offline (user-reported).
        override fun onLost(network: android.net.Network) {
            if (network == lastReconnectNetwork) lastValidated = false
            runCatching {
                AegisApp.instance.transports
                    .filterIsInstance<app.aether.aegis.simplex.SimpleXTransport>()
                    .forEach { it.markAllRelaysDown() }
            }
        }
    }

    @Volatile private var networkCallbackRegistered = false

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return
        runCatching {
            // registerDefaultNetworkCallback is what we want — fires
            // when the system's CHOSEN default network changes,
            // which is the event the SimpleX core actually cares
            // about. registerNetworkCallback with a wide request can
            // double-fire on every Wi-Fi roam.
            cm.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure {
            app.aether.aegis.simplex.ConnectionLog.warn(
                "ProtocolService",
                "registerDefaultNetworkCallback failed: ${it.message}",
            )
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        networkCallbackRegistered = false
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(controlReceiver) }
        unregisterNetworkCallback()
        stopWork()
        scope.cancel()
    }

    // ----- Pause / resume -----

    private fun pause() {
        if (paused) return
        paused = true
        AegisApp.instance.protectionEnabled = false
        stopWork()
        updateNotification()
    }

    private fun resume() {
        if (!paused) return
        paused = false
        AegisApp.instance.protectionEnabled = true
        startWork()
        updateNotification()
    }

    /**
     * Send a routine LOCATION update to every Trusted peer.
     * JSON body so the receiver side can
     * parse it structurally; SOSHandler uses a different format
     * ("[sos update] lat, lng" free-text) so the two paths stay
     * distinguishable.
     */
    private suspend fun broadcastLocationToFamily(lat: Double, lng: Double, ts: Long) {
        // Moved into :core:safety:presence (trust-containers Phase 2,
        // Stage 2). The presence module owns the envelope + Trusted-only
        // fan-out and can reach :app only through PresenceModuleHost
        // (installed in AegisApp.onCreate). This service keeps the GPS
        // listener + power-budget cadence; it just calls in to emit.
        app.aether.aegis.presence.PresenceBroadcaster.broadcastLocation(lat, lng, ts)
    }

    /**
     * Periodic status broadcast — battery + network + lastSeen. Sent
     * to every paired peer so the chat-list status dot, the Status
     * grid, and the Map's "no GPS fix" tile all reflect live data.
     * Previously this fired locally only (self status row updated,
     * but never shipped) which is why friends always read as offline.
     */
    private suspend fun broadcastStatusToFamily(
        batteryLevel: Int?,
        isCharging: Boolean?,
        networkType: String?,
        signalStrength: Int?,
        ts: Long,
        inAppTs: Long,
    ) {
        // Moved into :core:safety:presence (trust-containers Phase 2,
        // Stage 2). Tier IS the decision (Trusted-only); the app version
        // is passed in because BuildConfig lives in :app.
        app.aether.aegis.presence.PresenceBroadcaster.broadcastStatus(
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            networkType = networkType,
            signalStrength = signalStrength,
            ts = ts,
            inAppTs = inAppTs,
            appVersion = app.aether.aegis.BuildConfig.AETHER_CARGO,
        )
    }

    /** Great-circle distance between two lat/lng pairs, in metres. */
    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) *
            kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2).let { it * it }
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }

    private fun startWork() {
        runCatching { AegisApp.instance.protocolManager.start() }
            .onFailure { android.util.Log.e(TAG, "protocolManager.start failed", it) }
        // Trust-containers Phase 1 — presence-module gate. Track
        // the live Trusted-contact count; arm/disarm the location
        // stream as it crosses zero. distinctUntilChanged so we only
        // act on real transitions, not every emission.
        scope.launch {
            AegisApp.instance.repository.trustedCountFlow()
                .distinctUntilChanged()
                .collect { count ->
                    presenceTierActive = count > 0
                    // Re-evaluate now rather than waiting for the 60 s
                    // watchdog tick — startLocationStream arms or tears
                    // down per the gate above.
                    if (!paused) startLocationStream()
                }
        }
        // Trust-containers Phase 1 — sos-module gate (snatch
        // half). Arm/disarm the accelerometer as the sos-eligible
        // count crosses zero.
        scope.launch {
            AegisApp.instance.repository.sosCountFlow()
                .distinctUntilChanged()
                .collect { count ->
                    sosTierActive = count > 0
                    if (!sosTierActive) {
                        // No one to alert — tear the sensor down now.
                        sensorManager?.unregisterListener(this@ProtocolService)
                        sensorManager = null
                        accel = null
                    } else if (!paused) {
                        startSnatchDetection()
                    }
                }
        }
        startLocationStream()
        startLocationWatchdog()
        startStatusTicker()
        startSnatchDetection()
        // Crash detection — fed by the
        // location listener below; self-arms its own accelerometer only
        // while the speed gate is open, so it costs nothing at rest.
        app.aether.aegis.crashdetection.CrashDetector.attach(this)
    }

    private fun stopWork() {
        app.aether.aegis.crashdetection.CrashDetector.detach()
        runCatching { locationManager.removeUpdates(locationListener) }
        locationStreamArmed = false
        locationWatchdogJob?.cancel()
        locationWatchdogJob = null
        sensorManager?.unregisterListener(this)
        sensorManager = null
        accel = null
        statusTickerJob?.cancel()
        statusTickerJob = null
        runCatching { AegisApp.instance.protocolManager.stop() }
    }

    /**
     * Re-runs [startLocationStream] every minute. Catches three slow
     * failure modes that used to leave peers stuck on "waiting for
     * GPS…" indefinitely:
     *
     *   - User granted ACCESS_FINE_LOCATION AFTER the service started
     *     (e.g. they declined at first install, then later flipped
     *     the permission in system settings).
     *   - User toggled the system Location switch back on after we
     *     observed it off.
     *   - GPS provider came back online after being temporarily
     *     unavailable (no satellite view, etc.).
     *
     * Cheap — the re-arm is a no-op if [locationStreamArmed] is true
     * and the permission / provider set hasn't changed.
     */
    private fun startLocationWatchdog() {
        locationWatchdogJob?.cancel()
        locationWatchdogJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(60_000)
                if (!paused) startLocationStream()
            }
        }
    }

    // ----- Location streaming (was LocationService) -----

    private fun startLocationStream() {
        // Trust-containers Phase 1: presence module is gated on
        // having ≥1 Trusted contact. With none, don't register the GPS
        // listener at all (and tear down any prior registration). The
        // trustedCountFlow collector + the 60 s watchdog re-arm us the
        // moment a Trusted contact appears.
        if (!presenceTierActive) {
            if (locationStreamArmed) {
                runCatching { locationManager.removeUpdates(locationListener) }
                locationStreamArmed = false
                app.aether.aegis.simplex.ConnectionLog.log(
                    TAG, "presence: no Trusted contacts — location module idle",
                )
            }
            return
        }
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            // No permission. Used to return silently — peers see
            // "waiting for GPS…" forever with no indication of why.
            // Log so the diagnostic strip / connection log carry the
            // reason. The watchdog below will retry every minute so a
            // permission granted later auto-arms.
            if (locationStreamArmed) {
                android.util.Log.w(
                    TAG,
                    "location: lost permission while running — unregistering",
                )
                runCatching { locationManager.removeUpdates(locationListener) }
                locationStreamArmed = false
            }
            app.aether.aegis.simplex.ConnectionLog.warn(
                TAG, "location: no FINE / COARSE permission — listener not registered",
            )
            return
        }

        // Subscribe to EVERY available provider concurrently rather
        // than picking one — Android delivers from each independently
        // and the listener already keeps the most recent fix. This
        // means:
        //   - GPS disabled or no satellite view (indoors) → NETWORK
        //     (cell-towers + WiFi positioning, baked into AOSP, no
        //     Play Services) still produces a coarse fix every
        //     LOCATION_INTERVAL_MS.
        //   - GPS available → fine fixes flow in alongside the
        //     coarse ones; the more recent / more accurate value
        //     wins at the listener.
        //   - Both off → PASSIVE picks up anything any other app on
        //     the device requests.
        //
        // Previously the function picked ONE provider and stuck with
        // it, so a user indoors with GPS enabled (system-level) but
        // no satellite lock got zero updates instead of NETWORK
        // fallback.
        val providers = buildList {
            if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
            // PASSIVE only as a tertiary — useful when nothing else
            // is on but some other app on the device might be
            // listening, e.g. the system Maps widget.
            if (isEmpty() && locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                add(LocationManager.PASSIVE_PROVIDER)
            }
        }
        if (providers.isEmpty()) {
            if (locationStreamArmed) {
                runCatching { locationManager.removeUpdates(locationListener) }
                locationStreamArmed = false
            }
            app.aether.aegis.simplex.ConnectionLog.warn(
                TAG,
                "location: no provider enabled (GPS/NETWORK/PASSIVE all off) — " +
                    "user must enable location in system settings",
            )
            return
        }

        // Re-arm: remove any previous registration so we don't double-
        // register on subsequent watchdog ticks. Per Android docs,
        // requestLocationUpdates with the same listener replaces the
        // entry for the same provider, but providers added in a prior
        // call and not requested again would leak. removeUpdates first,
        // then re-register against the current provider set.
        runCatching { locationManager.removeUpdates(locationListener) }
        var registered = 0
        providers.forEach { provider ->
            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    LOCATION_INTERVAL_MS,
                    /* minDistanceMeters */ 0f,
                    locationListener,
                    Looper.getMainLooper(),
                )
                registered++
            }.onFailure {
                android.util.Log.w(TAG, "requestLocationUpdates($provider) failed", it)
            }
        }
        val wasArmed = locationStreamArmed
        locationStreamArmed = registered > 0
        if (locationStreamArmed && !wasArmed) {
            app.aether.aegis.simplex.ConnectionLog.log(
                TAG,
                "location: armed (${providers.joinToString()})",
            )
        }
    }

    // ----- Status ticker (was StatusCollectorService) -----

    private fun startStatusTicker() {
        statusTickerJob?.cancel()
        statusTickerJob = scope.launch {
            // First snapshot is immediate, not 60 s out — otherwise the
            // Status / Map tab reads as "dead" until the first tick lands.
            runCatching { snapshotStatus() }
            // Seed the location row from the platform's last-known fix so
            // self shows up on the map without waiting for a fresh GPS
            // event (which can take minutes indoors / on cellular).
            runCatching { seedLastKnownLocation() }
            while (isActive) {
                // Interval driven by PowerBudget: 60 s by default, 5 min
                // when battery ≤ 35 %.
                val budget = AegisApp.instance.powerBudget
                budget.refresh()
                reconcileSnatchDetection(budget)
                delay(budget.statusTickerMs())
                runCatching { snapshotStatus() }
                // Keep location as live as status: an active one-shot fix
                // whenever the passive GPS stream has gone stale past the
                // broadcast interval (deep-Doze nightstand case). Self-
                // throttled, so this is a no-op while the listener is
                // delivering. Runs after the status push so a slow GPS
                // cold-start never delays the presence heartbeat.
                runCatching { refreshLocationActive() }
            }
        }
    }

    /**
     * Snatch detection is the only subsystem with a binary on/off
     * threshold in the upper half of the curve, so it gets a dedicated
     * spin-up / spin-down check inside the status loop. Everything else
     * either changes cadence (status ticker, GPS) or is sos-time only.
     */
    private fun reconcileSnatchDetection(budget: app.aether.aegis.power.PowerBudget) {
        val running = sensorManager != null
        // Trust-containers Phase 1: snatch only runs while there's
        // a sos-eligible contact AND the power budget allows it — plus
        // the user opt-in (OFF by default). Folding the toggle into
        // [allowed] means flipping it off here unregisters the sensor on
        // the next status-loop tick.
        val allowed = sosTierActive && budget.shouldRunSnatchDetection() &&
            SnatchDetectionStore(this).enabled
        if (allowed && !running) startSnatchDetection()
        else if (!allowed && running) {
            sensorManager?.unregisterListener(this)
            sensorManager = null
            accel = null
        }
    }

    /**
     * One-shot seed of self's location row from the platform's cached
     * fix. Without this, a brand-new install shows no self-pin on the
     * map until the location callback fires its first event, which on
     * some hardware doesn't happen until a foreground request actually
     * gets a satellite lock.
     */
    /** Outbound LOCATION fan-out cadence, widened on the battery curve
     *  ("Voyager mode"). Shared by the continuous listener and the active
     *  ticker refresh so both throttle identically. Charging → default
     *  5 min; ≤10 % → hourly heartbeat; ≤30 % → 15 min; else 5 min.
     *  (Factored out of the listener verbatim — same thresholds.) */
    private fun broadcastIntervalMs(): Long {
        val pb = AegisApp.instance.powerBudget
        val battery = pb.level.value
        return when {
            pb.charging.value -> MIN_BROADCAST_INTERVAL_MS
            battery <= 10     -> 60L * 60_000L
            battery <= 30     -> 15L * 60_000L
            else              -> MIN_BROADCAST_INTERVAL_MS
        }
    }

    /**
     * Actively pull ONE fresh location fix and fan it out to Trusted
     * contacts. Driven by the status ticker so a contact's GPS view stays
     * as live as their battery/presence dot.
     *
     * WHY this exists: the continuous [locationListener] silently stops
     * being serviced in deep Doze — a phone left stationary overnight
     * (on a nightstand, unplugged) gets its passive location stream
     * suspended, while the status ticker still fires during Doze
     * maintenance windows. The result a user reported was a contact
     * reading "Away" (status fresh) with a GPS fix 10 h old. A one-shot
     * [getCurrentLocation] actively requests a fix during that same
     * maintenance window (the FGS holds the `location` type), where the
     * passive stream would just keep waiting.
     *
     * Self-throttled against [lastBroadcastAtMs] on the same battery
     * curve as the listener: while the listener IS delivering (foreground
     * / moving) this is a cheap no-op; it only spends a GPS fix once the
     * passive stream has gone stale past the interval. Honest timestamps —
     * we broadcast the fix's own time, so a genuinely old last-known fix
     * is never relabelled as current.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun refreshLocationActive() {
        if (!presenceTierActive) return
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        // Only spend an active fix once the passive stream has gone quiet
        // past the broadcast interval — otherwise the listener has it
        // covered and this would just burn the GPS.
        if (System.currentTimeMillis() - lastBroadcastAtMs < broadcastIntervalMs()) return
        val provider = when {
            fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> return
        }
        val fix = withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) { requestSingleFix(provider) } ?: return
        val now = System.currentTimeMillis()
        // Always carry the fix's OWN time — never relabel an older fix as
        // "now". A contact's GPS age must be honest (this is a safety app:
        // a stale location dressed up as current is worse than an obviously
        // old one). Only substitute now for a zero/garbage provider time.
        val ts = fix.time.takeIf { it > 0L } ?: now
        app.aether.aegis.crashdetection.CrashDetector.onLocation(fix)
        runCatching {
            AegisApp.instance.repository.patchLocation(
                peerKey = AegisApp.instance.identity.deviceId,
                latitude = fix.latitude,
                longitude = fix.longitude,
                ts = ts,
            )
        }
        broadcastLocationToFamily(fix.latitude, fix.longitude, ts)
        lastBroadcastLat = fix.latitude
        lastBroadcastLng = fix.longitude
        lastBroadcastAtMs = now
        app.aether.aegis.simplex.ConnectionLog.log(
            TAG, "location: active refresh fix via $provider (passive stream was stale)",
        )
    }

    /** One-shot location request bridged to a coroutine. getCurrentLocation
     *  on R+ (actively services the request, auto-cancels), requestSingleUpdate
     *  on Q. Resumes null on any failure; the caller wraps a timeout. */
    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun requestSingleFix(provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val cancel = android.os.CancellationSignal()
                cont.invokeOnCancellation { runCatching { cancel.cancel() } }
                runCatching {
                    locationManager.getCurrentLocation(
                        provider, cancel, mainExecutor,
                    ) { loc -> if (cont.isActive) cont.resume(loc) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            } else {
                val listener = LocationListener { loc -> if (cont.isActive) cont.resume(loc) }
                cont.invokeOnCancellation { runCatching { locationManager.removeUpdates(listener) } }
                runCatching {
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }

    private suspend fun seedLastKnownLocation() {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        val best: Location? = providers.mapNotNull { p ->
            runCatching { locationManager.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time }
        if (best != null) {
            AegisApp.instance.repository.patchLocation(
                peerKey = AegisApp.instance.identity.deviceId,
                latitude = best.latitude,
                longitude = best.longitude,
                ts = best.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        }
    }

    /**
     * Push fresh status + last-known location to Trusted contacts right
     * now, bypassing the periodic ticker. Driven by chat activity
     * (ACTION_REFRESH_PRESENCE) so that while you're actively messaging, a
     * Trusted contact's battery/GPS view stays live instead of going stale
     * between ≤5-min ticks. Both broadcasts are Trusted-only by
     * construction (PresenceBroadcaster gates on trustedTargets), so this
     * never leaks status to a non-Trusted recipient. Uses the cached
     * last-known fix — no fresh (expensive) GPS request per message.
     */
    private suspend fun refreshPresenceNow() {
        // Status (battery/network): force past the throttle, then emit.
        forceStatusBroadcast = true
        runCatching { snapshotStatus() }
        // Location: last-known only.
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return
        val best: Location? = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { p ->
            runCatching { locationManager.getLastKnownLocation(p) }.getOrNull()
        }.maxByOrNull { it.time }
        if (best != null) {
            broadcastLocationToFamily(
                best.latitude,
                best.longitude,
                best.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        }
    }

    /** Last time we broadcast our status to peers. Throttled (vs the
     *  local-only snapshot which fires every 60 s) so we don't burn a
     *  SimpleX send per peer every minute when nothing's changed. */
    @Volatile private var lastStatusBroadcastAtMs = 0L

    private suspend fun snapshotStatus() {
        val (battery, charging) = readBattery()
        val (network, signal) = readNetwork()
        val now = System.currentTimeMillis()
        // Use InAppActivity's stamp (last foreground tick + heartbeat)
        // rather than `now`, so self's lastActive reflects "really
        // using the app" instead of "ticker fired in the background".
        // Was the cause of the second-phone-untouched-for-3-hours
        // still showing green: the ticker stamped lastActive=now every
        // 60 s regardless of whether the user was even near the device.
        val inAppTs = app.aether.aegis.presence.InAppActivity.nowOrLast()
        AegisApp.instance.repository.patchDeviceStatus(
            peerKey = AegisApp.instance.identity.deviceId,
            batteryLevel = battery,
            isCharging = charging,
            networkType = network,
            signalStrength = signal,
            ts = inAppTs,
        )
        // Broadcast at most every STATUS_BROADCAST_MIN_INTERVAL_MS so
        // peers see a fresh status row roughly every 5 min (or sooner
        // when the ticker fires earlier and the throttle window has
        // elapsed). Bigger interval = less radio traffic for what is
        // ultimately a "yes I'm still alive" ping.
        // `forceStatusBroadcast` lets a just-connected contact pull a
        // fresh status push without waiting out the throttle window —
        // see requestImmediateStatusBroadcast(). Consumed once here.
        val forced = forceStatusBroadcast
        if (forced || now - lastStatusBroadcastAtMs >= STATUS_BROADCAST_MIN_INTERVAL_MS) {
            forceStatusBroadcast = false
            lastStatusBroadcastAtMs = now
            broadcastStatusToFamily(battery, charging, network, signal, now, inAppTs)
        }
    }

    private fun readBattery(): Pair<Int?, Boolean?> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null to null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    private fun readNetwork(): Pair<String?, Int?> {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null to null
        val active = cm.activeNetwork ?: return "none" to null
        val caps = cm.getNetworkCapabilities(active) ?: return "none" to null
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val signal = readCellularSignal()
        return type to signal
    }

    private fun readCellularSignal(): Int? {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        val ss = runCatching { tm.signalStrength }.getOrNull() ?: return null
        return runCatching { ss.cellSignalStrengths.firstOrNull()?.dbm }.getOrNull()
    }

    // ----- Snatch detection (was SnatchDetectService) -----

    private fun startSnatchDetection() {
        // DISABLED 2026-06-04: snatch detection must NOT send sos
        // — the accelerometer heuristic false-triggered real sos
        // broadcasts. We no longer register the ~50 Hz sensor at all (also a
        // power win). The trigger in onSensorChanged() is neutered too.
        // Re-enable by restoring the body below + the trigger line once the
        // heuristic is reworked.
        // User opt-in (OFF by default). Snatch's g-force heuristic
        // false-fires sos on ordinary jolts, so it stays dark until the
        // user flips the Diagnostics toggle. reconcileSnatchDetection()
        // checks the same flag and spins the sensor down the moment it's
        // turned off.
        if (!SnatchDetectionStore(this).enabled) {
            android.util.Log.i(TAG, "snatch detection off by setting")
            return
        }
        // Trust-containers Phase 1 — sos-module gate: no
        // sos-eligible contact ⇒ nothing for a snatch to trigger, so
        // the accelerometer is never registered.
        if (!sosTierActive) return
        // PowerBudget gate — snatch detection runs the accelerometer at
        // SENSOR_DELAY_GAME (~50 Hz), which is genuinely expensive. Off
        // below 50 % so sos detection capacity gets sacrificed before
        // sos-response capacity.
        AegisApp.instance.powerBudget.refresh()
        if (!AegisApp.instance.powerBudget.shouldRunSnatchDetection()) {
            android.util.Log.i(TAG, "snatch detection suppressed by PowerBudget")
            return
        }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accel?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val isLinear = event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION
        val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        val gForce = if (isLinear) magnitude / 9.81f else (magnitude - 9.81f) / 9.81f
        val now = SystemClock.elapsedRealtime()

        if (gForce > SNATCH_G) {
            snatchSpikeAtMs = now
            snatchMotionSamples = 1
            return
        }
        if (snatchSpikeAtMs == 0L) return
        if (now - snatchSpikeAtMs > SNATCH_SUSTAIN_WINDOW_MS) {
            snatchSpikeAtMs = 0L; snatchMotionSamples = 0; return
        }
        if (gForce > SNATCH_MOTION_G) {
            snatchMotionSamples++
            if (now - snatchSpikeAtMs >= SNATCH_MIN_SUSTAIN_MS &&
                snatchMotionSamples >= SNATCH_MIN_MOTION_SAMPLES) {
                // Only reachable when the user has opted into snatch
                // detection (SnatchDetectionStore, OFF by default) — the
                // sensor isn't even registered otherwise.
                AegisApp.instance.sosHandler.trigger(SOSTrigger.SNATCH)
                snatchSpikeAtMs = 0L; snatchMotionSamples = 0
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ----- Notification -----

    private fun updateNotification() {
        NotificationManagerCompat.from(this)
            .notify(NOTIFICATION_ID, buildNotification(paused))
    }

    private fun buildNotification(paused: Boolean): Notification {
        val openAegis = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pausePI = PendingIntent.getBroadcast(
            this, REQ_PAUSE,
            Intent(ACTION_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val resumePI = PendingIntent.getBroadcast(
            this, REQ_RESUME,
            Intent(ACTION_RESUME).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Cyan tier (all 10 skill-tree nodes lit) gets its own
        // service-notification wording — the "shield active"
        // tier-neutral text didn't reward the user who climbed the
        // whole tree.
        val cyanTier = app.aether.aegis.admin.ShieldTierEngine.currentTier(this) ==
            app.aether.aegis.admin.ShieldTier.Cyan
        val activeTitle = if (cyanTier) "Aegis · Cyan engaged" else "Aegis · shield active"
        val activeText = if (cyanTier) "Maximum protection — the shield that does not fall."
                         else "Tap to open."
        val builder = NotificationCompat.Builder(this, AegisApp.CHANNEL_SERVICE)
            .setContentTitle(if (paused) "Aegis · paused" else activeTitle)
            .setContentText(if (paused) "Tap Resume to re-arm." else activeText)
            .setColor(AegisApp.BRAND_CYAN_ARGB)
            .setSmallIcon(
                if (paused) R.drawable.ic_notif_shield_paused
                else R.drawable.ic_notif_shield
            )
            // Android tints the monochrome small icon with this colour
            // in the status bar, so paused becomes a red exclamation hex
            // and active stays the familiar green outline.
            // Brand cyan when active; red when paused so the icon
            // tints distinctly in the status bar.
            .setColor(if (paused) 0xFFD32F2F.toInt() else 0xFF00FFFF.toInt())
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openAegis)
            .addAction(
                if (paused) {
                    NotificationCompat.Action.Builder(0, "Resume", resumePI).build()
                } else {
                    NotificationCompat.Action.Builder(0, "Pause", pausePI).build()
                }
            )

        val notif = builder.build()
        // Belt-and-braces: setOngoing already sets FLAG_ONGOING_EVENT, but
        // FLAG_NO_CLEAR explicitly blocks "clear all" from removing it.
        // Combined with foreground-service status, the only way the user
        // can make this notification disappear is to force-stop the app
        // from system settings — exactly the contract we want for a
        // family-safety shield.
        notif.flags = notif.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return notif
    }

    companion object {
        /** Set by the transport when a new contact connects so the next
         *  status snapshot broadcasts to peers even if the 5-min
         *  throttle window hasn't elapsed. A status-eligible (Trusted)
         *  fresh contact then sees our presence within one ticker cycle
         *  (≤60 s) instead of up to 5 min — the visible half of the
         *  "fresh contact takes really long to update" report (2026.06).
         *  Consumed (cleared) by snapshotStatus on the next tick. */
        @Volatile
        private var forceStatusBroadcast = false

        /** Request that the next status snapshot bypass the broadcast
         *  throttle. Thread-safe (single volatile write); safe to call
         *  even when no ProtocolService instance is running — the flag
         *  is simply honoured whenever the ticker next fires. */
        @JvmStatic
        fun requestImmediateStatusBroadcast() { forceStatusBroadcast = true }

        /** Last activity-driven presence refresh (elapsedRealtime), and
         *  its debounce window. Active chatting fires ACTION_REFRESH_PRESENCE
         *  per message; we coalesce a burst into one status+location push. */
        @Volatile
        private var lastActivityRefreshAtMs = 0L
        private const val ACTIVITY_REFRESH_MIN_MS = 8_000L

        /** Fire-and-forget nudge to push fresh status + last-known location
         *  to Trusted contacts now (used by ProtocolManager on a user-visible
         *  send so an active conversation carries live battery/GPS). No-op if
         *  the service isn't up; Trusted-only fan-out happens inside. */
        @JvmStatic
        fun requestActivityPresenceRefresh() {
            runCatching {
                val ctx = AegisApp.instance.applicationContext
                ctx.sendBroadcast(
                    Intent(ACTION_REFRESH_PRESENCE).setPackage(ctx.packageName),
                )
            }
        }

        private const val TAG = "ProtocolService"
        private const val NOTIFICATION_ID = 1
        private const val REQ_PAUSE = 100
        private const val REQ_RESUME = 101

        private const val LOCATION_INTERVAL_MS = 30_000L
        private const val LOCATION_MIN_INTERVAL_MS = 15_000L
        private const val STATUS_TICK_MS = 60_000L
        // Status broadcast throttle — fire to peers at most every 5 min
        // (the local ticker still runs every 60 s for self's own row).
        private const val STATUS_BROADCAST_MIN_INTERVAL_MS = 5L * 60_000L
        private const val NOTIFICATION_REPOST_CHECK_MS = 3_000L
        // Debounce window for network-change → SMP /reconnect. A real
        // network switch emits a burst of NetworkCallback events over
        // ~1-2 s; we wait this long after the last one so the burst
        // collapses into a single reconnect instead of thrashing the
        // relay links. Long enough to coalesce the burst, short enough
        // that a genuine wifi↔cell flip recovers message flow promptly.
        private const val RECONNECT_DEBOUNCE_MS = 1_500L
        // Routine outbound LOCATION broadcast — either at most every
        // 5 min, or sooner if the user moved >200 m. Keeps the family
        // map fresh without burning battery + bandwidth on every
        // 30-s GPS tick.
        private const val MIN_BROADCAST_INTERVAL_MS = 5L * 60_000L
        private const val MIN_BROADCAST_METERS = 200.0
        /** Budget for one active single-fix request before we give up and
         *  leave it to the next ticker pass. GPS cold-start can be slow, but
         *  the status loop's delay is ≥60 s so 30 s fits without stalling. */
        private const val FRESH_FIX_TIMEOUT_MS = 30_000L
        private const val SNATCH_G = 3.5f
        private const val SNATCH_MOTION_G = 1.2f
        private const val SNATCH_SUSTAIN_WINDOW_MS = 4_000L
        private const val SNATCH_MIN_SUSTAIN_MS = 1_500L
        private const val SNATCH_MIN_MOTION_SAMPLES = 30

        /** Notification action — suspends work but keeps the service alive. */
        const val ACTION_PAUSE = "app.aether.aegis.action.PAUSE"
        /** Notification action — re-arms everything after a pause. */
        const val ACTION_RESUME = "app.aether.aegis.action.RESUME"
        /** Diagnostics action — re-runs startLocationStream immediately
         *  instead of waiting up to a minute for the watchdog. */
        const val ACTION_REARM_LOCATION = "app.aether.aegis.action.REARM_LOCATION"
        /** Diagnostics action — full stop + start of the protocol
         *  manager, useful when "Aegis says online but nothing flows". */
        const val ACTION_RESTART_TRANSPORT = "app.aether.aegis.action.RESTART_TRANSPORT"

        /** Chat-activity nudge → push fresh status + last-known location to
         *  Trusted contacts now (debounced). Fired by ProtocolManager on a
         *  user-visible send so an active conversation carries live presence. */
        const val ACTION_REFRESH_PRESENCE = "app.aether.aegis.action.REFRESH_PRESENCE"
    }
}

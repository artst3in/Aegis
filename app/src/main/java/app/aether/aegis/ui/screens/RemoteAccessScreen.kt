package app.aether.aegis.ui.screens

import app.aether.aegis.AegisApp
import app.aether.aegis.R
import app.aether.aegis.ui.components.AegisIcon
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisSOS
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Per-peer Remote Access surface — locate, siren, wipe, etc.
 *
 * Lives in the Radar branch (Status tab → tap device → "Remote
 * access") rather than the comms branch (Chat → contact → detail).
 * The user's split: "locating a stolen phone is technical, not
 * comms. You don't put REMOTE next to nickname and mute. You put
 * it next to the device-status readout."
 *
 * Architecturally this is just a thin Scaffold around
 * [RemoteAccessPanel] (still defined in ContactDetailScreen.kt
 * because the whole auth + action-button state machine lives there;
 * marking it `internal` was a one-line change vs. moving 500+ lines).
 * The screen adds:
 *   - A TopAppBar with the peer's name + the remote-access glyph
 *     (matches the SOS dashboard adapter's framing for symmetry between
 *     the two "operational" surfaces).
 *   - A short device-status banner so the user knows what they're
 *     about to operate on (last seen, battery if known).
 *   - The peer's "block this contact from remote-accessing ME"
 *     reciprocal toggle (was tucked under the panel on the old
 *     ContactDetailScreen home; surfaces equally here).
 *
 * The PIN auth, session timer, LOCATE/SIREN/WIPE buttons, and
 * AUTH_DENIED toast all come from the unchanged [RemoteAccessPanel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteAccessScreen(peerKey: String, navController: NavController) {
    val peer by produceState<app.aether.aegis.data.KnownPeerEntity?>(initialValue = null, peerKey) {
        value = AegisApp.instance.repository.knownPeerByKey(peerKey)
    }
    val status by AegisApp.instance.repository.observeStatus(peerKey)
        .collectAsState(initial = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AegisIcon(
                                icon = AegisIcons.RemoteCmd,
                                contentDescription = null,
                                tint = AegisCyan,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                (peer?.displayName ?: peerKey.take(12)).uppercase(),
                                fontWeight = FontWeight.Bold,
                                color = AegisCyan,
                            )
                        }
                        Text(
                            stringResource(R.string.remote_access_remote_access),
                            color = AegisOnSurfaceDim,
                            fontSize = 11.sp,
                            letterSpacing = 1.5.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        AegisIcon(
                            AegisIcons.Back,
                            stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Peer is still loading from the repository (suspending
            // knownPeerByKey roundtrip). Show a quiet placeholder
            // instead of letting the inner panel render a confused
            // null state. Typically resolves in well under 100 ms on
            // a populated DB.
            val resolvedPeer = peer
            if (resolvedPeer == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
                return@Column
            }

            DeviceStatusBanner(status)

            // Two location sources, fresher first:
            //   1. RemoteAccessSession.locate* — a coordinate the
            //      user just demanded via the LOCATE button. Always
            //      newer than the routine broadcast, and a deliberate
            //      ask, so it wins when present.
            //   2. MemberStatusEntity.latitude/longitude — the
            //      routine 5-min [aegis:status] / [aegis:location]
            //      broadcast. Drives the empty-frame fallback so the
            //      map renders even before LOCATE has been pressed.
            val sessions by app.aether.aegis.remote.RemoteAccessSession.sessions.collectAsState()
            val activeSession = sessions[peerKey]
            val effectiveLat = activeSession?.locateLat ?: status?.latitude
            val effectiveLng = activeSession?.locateLng ?: status?.longitude
            val effectiveTs = activeSession?.locateTs ?: status?.lastPacketMs ?: status?.lastActive
            val name = resolvedPeer.displayName.ifBlank { "target" }
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                RemoteAccessMap(
                    lat = effectiveLat,
                    lng = effectiveLng,
                    fixAt = effectiveTs,
                    peerName = name,
                )
            }

            // Auth gate + session UI + action buttons. Reused as-is
            // from the ContactDetailScreen extraction.
            RemoteAccessPanel(peer = resolvedPeer, navController = navController)

            // Reciprocal block — "I won't let THIS peer remote-access
            // me." Lives on the same screen now so the full
            // remote-access mental model is in one place: what I can
            // do TO them (top), what they can do TO me (bottom).
            RemoteAccessControlRow(peer = resolvedPeer)
        }
    }
}

/**
 * Compact "what device am I operating on" banner. Surfaces the
 * three things that matter when you're about to fire LOCATE or
 * WIPE: last-seen recency, battery, and whether the peer is
 * currently broadcasting status at all.
 *
 * Kept inline in this file rather than reused from DeviceStatusScreen
 * because the call site needs only a tight three-row summary, not
 * the full multi-card breakdown. Anything richer than this and the
 * user should just go back to the Status drill-down.
 */
@Composable
private fun DeviceStatusBanner(status: app.aether.aegis.data.MemberStatusEntity?) {
    val now = System.currentTimeMillis()
    // Use lastPacketMs when available (background-service heartbeat)
    // and fall back to lastActive (foreground InAppActivity stamp) so
    // a peer that's been backgrounded for hours still reads as
    // "online" while the service is broadcasting.
    val freshestStamp = status?.lastPacketMs ?: status?.lastActive
    val (presenceLabel, presenceColor) = when {
        freshestStamp == null ->
            "no status broadcast yet" to AegisOnSurfaceDim
        now - freshestStamp < 5L * 60_000L ->
            "online · ${ageString(freshestStamp)}" to AegisCyan
        now - freshestStamp < 60L * 60_000L ->
            "idle · ${ageString(freshestStamp)}" to AegisOnSurfaceDim
        else ->
            "stale · ${ageString(freshestStamp)}" to AegisSOS
    }
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.remote_access_target_device),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    presenceLabel,
                    color = presenceColor,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                val battery = status?.batteryLevel
                if (battery != null) {
                    Text(
                        "$battery%",
                        color = if (battery <= 15) AegisSOS
                                else MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * OSMDroid map of the target device's last-known coordinates.
 *
 * Mirrors the SOS dashboard adapter's `DashboardMap` exactly — same tile
 * source (MAPNIK), same zoom range, same antimeridian-wrap, same
 * cyan-tinted hex pin, same parent-scroll workaround for the
 * verticalScroll wrapper that would otherwise eat every drag before
 * osmdroid sees it. Kept as a local copy rather than promoting
 * DashboardMap to `internal` so the SOS dashboard adapter's surface
 * stays stable; the SOS and remote-access dashboards drift
 * independently from here on, and the few duplicated lines are a
 * worthwhile price for that separation.
 *
 * Renders the map frame even when lat/lng are null so the user can
 * see "no fix yet" without the UI shifting around once LOCATE
 * returns and the marker appears.
 */
@Composable
private fun RemoteAccessMap(
    lat: Double?,
    lng: Double?,
    fixAt: Long?,
    peerName: String,
) {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isHorizontalMapRepetitionEnabled = true
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(85.0, -85.0, 0)
            minZoomLevel = 1.5
            maxZoomLevel = 19.0
            controller.setZoom(13.0)
            // The enclosing verticalScroll was eating every drag
            // gesture before osmdroid could see it — user could
            // pinch-zoom (multi-touch) but never pan one finger.
            // requestDisallowInterceptTouchEvent on ACTION_DOWN
            // tells the parent scroll to keep its hands off until
            // the touch ends.
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_POINTER_DOWN ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false  // never consume — osmdroid handles pan/zoom
            }
        }
    }
    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }
    Box {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(12.dp)),
            update = { view ->
                view.overlays.removeAll { it is Marker }
                if (lat != null && lng != null) {
                    val gp = GeoPoint(lat, lng)
                    Marker(view).apply {
                        position = gp
                        title = peerName
                        // Pre-baked cyan variant — Drawable.setTint at
                        // runtime is silently ignored by several
                        // VectorDrawableCompat backends.
                        icon = androidx.core.content.ContextCompat
                            .getDrawable(context, app.aether.aegis.R.drawable.ic_aegis_pin_cyan)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }.also { view.overlays.add(it) }
                    view.controller.setCenter(gp)
                }
                view.invalidate()
            },
        )
        // Caption overlay in the top-right of the map frame. Three
        // states the user needs to be able to distinguish at a glance:
        //   - no fix → "no fix yet"
        //   - fresh  → no overlay, the map speaks for itself
        //   - stale  → "as of Xm ago" so the user doesn't act on
        //              hours-old coordinates thinking they're current
        val now = System.currentTimeMillis()
        val captionText: String? = when {
            lat == null || lng == null -> "no fix yet"
            fixAt == null -> null
            now - fixAt < 2L * 60_000L -> null
            else -> "as of ${ageString(fixAt)}"
        }
        captionText?.let { text ->
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Text(
                    text,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}


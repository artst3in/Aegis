package app.aether.aegis.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.AegisApp
import app.aether.aegis.core.FamilyMember
import app.aether.aegis.core.identifier
import app.aether.aegis.ui.components.AegisIcons
import app.aether.aegis.ui.components.GlassPanel
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisCyanGlow
import app.aether.aegis.ui.theme.AegisOnline
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import app.aether.aegis.ui.theme.AegisPanel
import app.aether.aegis.ui.theme.AegisSOS
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

// ============================================================
// RADAR (was Map + Status)
// ============================================================

/**
 * Radar tab — interactive map of every paired peer with a LunaGlass
 * dock below for full family contact status. The "radar" framing is
 * literal: a slow cyan sweep arc rotates over the map, and each peer
 * marker pulses when the sweep passes through its bearing.
 *
 * Tapping a member tile drills down to DeviceStatusScreen (per-device
 * battery, GPS, network, wearable, identity). The "📍" button centres
 * the map on that peer without leaving the radar.
 */
@Composable
fun MapScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val inDuress = AegisApp.instance.lockState.inDuressMode
    val realStatuses by AegisApp.instance.repository.statuses().collectAsState(initial = emptyList())
    val realPeers by AegisApp.instance.repository.observeKnownPeers().collectAsState(initial = emptyList())
    val statuses = if (inDuress) app.aether.aegis.decoy.DecoyFixtures.statuses() else realStatuses
    // The radar is a live location/status map, so it only makes sense for
    // contacts who actually share routine data — TRUSTED. EMERGENCY peers
    // "receive nothing routinely" (sos only) and UNTRUSTED "nothing, ever",
    // so both would sit here as permanent pinless / statusless rows: dead
    // space that made the radar look like a dumb copy of the chat list.
    // Filtering to Trusted makes the radar your safety circle, distinct
    // from Comms (which is everyone you message). Applies in duress too —
    // the decoy roster carries tiers, so it stays plausible.
    val knownPeers = (if (inDuress) app.aether.aegis.decoy.DecoyFixtures.peers() else realPeers)
        .filter { it.trustTier == app.aether.aegis.data.TrustTier.TRUSTED.name }
    // Has contacts, but none are Trusted yet → the radar is empty-but-you,
    // so show a one-line nudge instead of a blank dock.
    val hasUntrustedOnly = !inDuress && knownPeers.isEmpty() && realPeers.isNotEmpty()
    val selfKey = AegisApp.instance.identity.deviceId
    // Profile + per-peer avatar lookup. Read on every recomposition
    // rather than remembered — earlier the remembered snapshot meant
    // a freshly-set avatar in ProfileScreen never reached the radar
    // until the next process restart, because the Map composable
    // stays in the back stack while ProfileScreen is on top and
    // re-entering it triggers a recompose but not a `remember`
    // re-evaluation. The snapshot read is a cheap SharedPreferences
    // lookup.
    val selfProfile = AegisApp.instance.profileStore.snapshot()
    val selfDisplayName = selfProfile.displayName.ifBlank { "You" }
    val selfAvatarPath = selfProfile.avatarPath
    val knownPeerByKey = remember(knownPeers) { knownPeers.associateBy { it.publicKey } }

    val everyone = remember(selfKey, knownPeers) {
        listOf(
            FamilyMember(
                name = AegisApp.instance.profileStore.displayName.ifBlank { "You" },
                meshIp = selfKey.take(20),
                publicKey = selfKey,
            ),
        ) + knownPeers.map { peer ->
            FamilyMember(
                name = peer.displayName,
                meshIp = peer.publicKey.take(20),
                publicKey = peer.publicKey,
            )
        }
    }
    val statusByKey = statuses.associateBy { it.peerKey }
    // Every count on this screen is about CONTACTS — other people. YOU are
    // always on the list and on the map, but are NEVER tallied, so the
    // numbers stay consistent instead of "sometimes including me".
    // peerKeys excludes self by construction (knownPeers never contains us).
    val peerKeys = remember(knownPeers) { knownPeers.map { it.publicKey }.toSet() }
    // Online = contacts whose status was seen in the last 5 min.
    val onlineCount = remember(statuses, peerKeys) {
        val now = System.currentTimeMillis()
        statuses.asSequence()
            .filter { it.peerKey in peerKeys && now - it.lastActive < 5L * 60_000L }
            .map { it.peerKey }
            .toSet().size
    }
    // Pins = contacts with a known location (your own pin is not counted).
    val withGps = remember(statuses, peerKeys) {
        statuses.asSequence()
            .filter { it.peerKey in peerKeys && it.latitude != null }
            .map { it.peerKey }
            .toSet().size
    }

    val mapView = remember {
        org.osmdroid.views.MapView(context).apply {
            setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Dark-mode the tiles via osmdroid's built-in invert filter —
            // turns OSM Mapnik's daytime map into a usable LunaGlass-
            // friendly inverted surface. Cheap (per-pixel shader).
            overlayManager.tilesOverlay.setColorFilter(
                org.osmdroid.views.overlay.TilesOverlay.INVERT_COLORS,
            )
            // Pan freely: horizontal wrap on so users can scroll
            // across the antimeridian without the map locking at the
            // edge; vertical wrap off (the poles cap travel naturally
            // via the lat limit below).
            isHorizontalMapRepetitionEnabled = true
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(85.0, -85.0, 0)
            // Zoom range opens all the way out to "whole world fits
            // on one tile" (≈1.5) so the user can frame the planet,
            // not just their country. Earlier the floor was 5.0 to
            // keep labels visible, but user feedback was that "I
            // can't zoom out to see the world" trumps label legibility
            // at the top of the zoom range.
            minZoomLevel = 1.5
            maxZoomLevel = 19.0
            controller.setZoom(5.0)
            controller.setCenter(org.osmdroid.util.GeoPoint(52.2, 19.0))
        }
    }
    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }

    // One-shot auto-center on first available pin. Without this guard
    // the AndroidView update block re-centers on every status push,
    // so a user-pan was being snapped back the next time any paired
    // peer broadcast their location — felt like the map was haunted.
    val didInitialCenter = remember { mutableStateOf(false) }

    // Radar centre tracks the user's geographic location through the
    // map's current projection, so as you pan/zoom the sweep stays
    // anchored to "you on the planet" instead of "centre of the
    // viewport". When the user has no GPS fix yet, falls back to
    // screen-centre so the radar still animates.
    val selfStatus = statusByKey[selfKey]
    val selfLat = selfStatus?.latitude
    val selfLng = selfStatus?.longitude
    var radarCenter by remember { mutableStateOf<Offset?>(null) }
    val selfLatRef = androidx.compose.runtime.rememberUpdatedState(selfLat)
    val selfLngRef = androidx.compose.runtime.rememberUpdatedState(selfLng)

    fun recomputeRadarCenter() {
        val lat = selfLatRef.value
        val lng = selfLngRef.value
        if (lat == null || lng == null) {
            radarCenter = null
            return
        }
        val proj = mapView.projection ?: return
        val p = android.graphics.Point()
        proj.toPixels(org.osmdroid.util.GeoPoint(lat, lng), p)
        radarCenter = Offset(p.x.toFloat(), p.y.toFloat())
    }

    // Peer markers live in the Compose layer (same Canvas as the
    // sweep) rather than as osmdroid Marker overlays. Lets the radar
    // sweep gently light each pin as it passes over its bearing,
    // then fade back to base brightness — classic radar ping behaviour
    // you can't get with static Marker bitmaps.
    val everyoneRef = androidx.compose.runtime.rememberUpdatedState(everyone)
    val statusByKeyRef = androidx.compose.runtime.rememberUpdatedState(statusByKey)
    var peerMarkers by remember { mutableStateOf<List<PeerMarker>>(emptyList()) }
    // Sticky avatar cache (pubkey → last non-null avatar path). announcedAvatarPath
    // can momentarily read null on a STATUS-only marker rebuild — the known_peers
    // snapshot lags the status update — which dropped the radar blip to its
    // first-letter monogram and back, a visible flicker (user report). Once we've
    // seen a real avatar for a peer we keep using it; only a NEW non-null path
    // replaces it (a genuine avatar change), a transient null does not.
    val avatarCache = remember { mutableMapOf<String, String>() }
    // Sticky tier cache (pubkey → last non-null ShieldTier). Same lag as the
    // avatar cache above: peerReportedTier reads null on a STATUS-only marker
    // rebuild when the known_peers snapshot trails the status update, which
    // dropped a peer's frame to the default cyan and back — the "Pixel randomly
    // shows the wrong colour on the radar" flicker. Once we've seen a real tier
    // we keep it; only a NEW non-null tier replaces it, a transient null does not.
    val tierCache = remember { mutableMapOf<String, app.aether.aegis.admin.ShieldTier>() }
    // Which peer's quick-actions menu is open (their publicKey), or null.
    var avatarMenuKey by remember { mutableStateOf<String?>(null) }
    // Screen-space position of the tapped pin (in the map Box's coordinate
    // system) so the quick-action wheel fans out FROM the avatar on the map
    // rather than from a recentred hub.
    var avatarMenuPos by remember { mutableStateOf<Offset?>(null) }
    // The map Box's top-left in WINDOW coordinates — added to avatarMenuPos
    // (which is map-Box-relative) to place the Popup wheel at the avatar.
    var mapBoxOrigin by remember { mutableStateOf(Offset.Zero) }

    fun recomputePeerMarkers() {
        val proj = mapView.projection ?: return
        val now = System.currentTimeMillis()
        // Only real positions on the radar — a peer without a GPS
        // fix simply doesn't appear. The earlier "ghost ring" pass
        // was placing unfixed peers around a synthetic circle to
        // signal "I know about them, no fix yet", but that turned
        // into permanent fake positions for any contact who'd never
        // share location (Untrusted, Emergency, non-Aegis, or simply
        // a Trusted peer without GPS permission). User: "no, don't
        // clutter map with fake positions". Peers without a fix
        // still appear in the family dock list below the radar.
        val fixed = mutableListOf<PeerMarker>()
        everyoneRef.value.forEach { member ->
            val s = statusByKeyRef.value[member.identifier]
            val lat = s?.latitude ?: return@forEach
            val lng = s.longitude ?: return@forEach
            val isSelf = member.publicKey == selfKey
            val displayName = if (isSelf) selfDisplayName
                else knownPeerByKey[member.publicKey]?.displayName ?: member.name
            val freshAvatar = if (isSelf) selfAvatarPath
                else knownPeerByKey[member.publicKey]?.announcedAvatarPath
            // Update the cache only on a real path; fall back to the last-known
            // one when this rebuild reports null, so the pin can't flicker to
            // its monogram on a status-only refresh.
            if (freshAvatar != null) avatarCache[member.publicKey] = freshAvatar
            val avatarPath = freshAvatar ?: avatarCache[member.publicKey]
            // Published shield tier → avatar-frame colour (same as Comms). For
            // SELF this is OUR OWN current tier (was hardcoded null, so the
            // self pin never showed the tier we'd earned); for peers it's the
            // tier they announced.
            val tier = if (isSelf) {
                app.aether.aegis.admin.ShieldTierEngine.currentTier(AegisApp.instance)
                    .takeIf { it != app.aether.aegis.admin.ShieldTier.None }
            } else {
                val fresh = knownPeerByKey[member.publicKey]?.peerReportedTier
                    ?.let { runCatching { app.aether.aegis.admin.ShieldTier.valueOf(it) }.getOrNull() }
                    ?.takeIf { it != app.aether.aegis.admin.ShieldTier.None }
                // Cache a real tier; fall back to the last-known one when this
                // rebuild reports null, so the frame can't flicker to default cyan.
                if (fresh != null) tierCache[member.publicKey] = fresh
                fresh ?: tierCache[member.publicKey]
            }
            val p = android.graphics.Point()
            proj.toPixels(org.osmdroid.util.GeoPoint(lat, lng), p)
            // Central presence calc —
            // peerStatusFor consults BOTH lastActive (foreground)
            // and lastPacketMs (background heartbeat), so a peer
            // with the app alive in background but not foregrounded
            // reads Away on the map instead of collapsing to
            // Offline. Was a duplicated inline three-way check.
            val status = app.aether.aegis.ui.components.peerStatusFor(
                status = s, nowMs = now, isSelf = isSelf,
            )
            fixed += PeerMarker(
                pubkey = member.publicKey,
                name = displayName,
                avatarPath = avatarPath,
                pos = Offset(p.x.toFloat(), p.y.toFloat()),
                isSelf = isSelf,
                status = status,
                hasFix = true,
                tier = tier,
                crownStyle = if (isSelf) null
                    else knownPeerByKey[member.publicKey]?.peerReportedCrownStyle,
            )
        }
        peerMarkers = fixed
    }

    // Recompute on every GPS / status update.
    LaunchedEffect(selfLat, selfLng, statusByKey, everyone) {
        recomputeRadarCenter()
        recomputePeerMarkers()
    }

    // Initial-layout race fallback. mapView.projection is null until
    // the first osmdroid layout pass, so the recomputePeerMarkers
    // call above can run before the map has a projection and return
    // empty — leaving the radar with sweep but no peer hexes until
    // the user manually pans. Re-fire on a short delay so markers
    // appear once the projection is live.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(600)
        recomputeRadarCenter()
        recomputePeerMarkers()
        kotlinx.coroutines.delay(1500)
        recomputeRadarCenter()
        recomputePeerMarkers()
    }

    // Recompute on every map move/zoom. osmdroid fires onScroll for
    // pan + every frame of a fling; onZoom for pinch + double-tap.
    DisposableEffect(mapView) {
        val listener = object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                recomputeRadarCenter()
                recomputePeerMarkers()
                return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                recomputeRadarCenter()
                recomputePeerMarkers()
                return false
            }
        }
        mapView.addMapListener(listener)
        onDispose { mapView.removeMapListener(listener) }
    }

    // Bounding box of every family pin (self + peers
    // with a fix) plus a generous padding band. Once we have at least
    // one pin we clamp the scrollable area so the user can't pan into
    // oblivion. Padding is a coarse degree value — finer per-zoom
    // padding would feel jittery as the user pinches.
    val familyBounds: org.osmdroid.util.BoundingBox? = remember(statusByKey, everyone) {
        val pts = everyone.mapNotNull { m ->
            val s = statusByKey[m.identifier] ?: return@mapNotNull null
            val lat = s.latitude ?: return@mapNotNull null
            val lng = s.longitude ?: return@mapNotNull null
            org.osmdroid.util.GeoPoint(lat, lng)
        }
        if (pts.isEmpty()) null else {
            val lats = pts.map { it.latitude }
            val lngs = pts.map { it.longitude }
            val pad = 2.5 // ≈ 250 km of breathing room around the family
            org.osmdroid.util.BoundingBox(
                (lats.max() + pad).coerceAtMost(85.0),
                (lngs.max() + pad).coerceAtMost(180.0),
                (lats.min() - pad).coerceAtLeast(-85.0),
                (lngs.min() - pad).coerceAtLeast(-180.0),
            )
        }
    }
    LaunchedEffect(familyBounds) {
        familyBounds?.let {
            mapView.setScrollableAreaLimitDouble(it)
        }
    }

    // Recenter: snap the camera back to fit every family pin on screen
    // with margin. Used by the bottom-right hex button and by the
    // double-tap gesture below.
    fun recenterOnFamily() {
        // Explicit "fit everyone" — the user deliberately wants the whole
        // family view, so drop any remembered single-contact focus.
        MapFocus.peerKey = null
        val box = familyBounds ?: return
        // zoomToBoundingBox needs the view laid out — guard against
        // pre-layout calls (the AndroidView factory runs before width
        // is non-zero).
        if (mapView.width <= 0 || mapView.height <= 0) {
            mapView.post { mapView.zoomToBoundingBox(box, true, 80) }
        } else {
            mapView.zoomToBoundingBox(box, true, 80)
        }
    }

    // Double-tap = zoom in toward the tapped point — the universal map
    // gesture. (Recenter-on-family lives on its own control; double-tap
    // belongs to zoom, which is what every map muscle-memory expects.)
    // Intercepted at the osmdroid touch level so we don't fight pinch/pan.
    DisposableEffect(mapView, familyBounds) {
        val gestureListener = object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                // zoomInFixing keeps the point under the finger anchored, so
                // the map grows toward where you tapped, not the centre.
                runCatching { mapView.controller.zoomInFixing(e.x.toInt(), e.y.toInt()) }
                return true
            }
        }
        val detector = android.view.GestureDetector(mapView.context, gestureListener)
        // Disallow parent intercept while the user is touching the
        // map — otherwise a parent verticalScroll / LazyColumn (the
        // SOS dashboard pattern) silently eats vertical drag and
        // the map appears un-pannable. Belt-and-braces for MapScreen
        // too even though its current parent is a plain Column.
        val touchListener = android.view.View.OnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_POINTER_DOWN ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            detector.onTouchEvent(ev); false
        }
        mapView.setOnTouchListener(touchListener)
        onDispose { mapView.setOnTouchListener(null) }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // Match the ~8dp gap every other tab leaves between the shared
        // AEGIS header and its first content. The map used to sit flush
        // against the header divider, which read as "the radar starts
        // higher than the other tabs" when switching between them — its
        // cyan frame fused with the header's cyan underline. This even
        // spacer drops the radar frame onto the same baseline as the
        // Chats / Opsec / Settings cards.
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { mapBoxOrigin = it.positionInWindow() }
                // Clip so the map tiles + radar sweep overlay stay INSIDE
                // the frame and don't bleed past the bottom under the
                // contacts dock.
                .clipToBounds()
                // Cyan frame for LunaGlass consistency with the rest of the
                // app's panels.
                .border(1.dp, AegisCyan.copy(alpha = 0.5f)),
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    // No osmdroid markers — peer pins are drawn in the
                    // Compose RadarSweepOverlay so they pulse when the
                    // sweep passes their bearing. We still use this
                    // update block for the one-shot auto-centre on
                    // first available GPS pin.
                    if (!didInitialCenter.value) {
                        // Prefer the contact the user just drilled into (and
                        // came back from) over "first pin" — otherwise the
                        // rebuilt MapView snaps to self every time you return
                        // from a contact card. Falls back to the first
                        // located pin on a genuine first load.
                        val focusPin = MapFocus.peerKey?.let { fk ->
                            val s = statusByKey[fk]
                            val la = s?.latitude
                            val lo = s?.longitude
                            if (la != null && lo != null)
                                org.osmdroid.util.GeoPoint(la, lo)
                            else null
                        }
                        val firstPin = focusPin ?: everyone.firstNotNullOfOrNull { m ->
                            val s = statusByKey[m.identifier]
                            val lat = s?.latitude
                            val lng = s?.longitude
                            if (lat != null && lng != null)
                                org.osmdroid.util.GeoPoint(lat, lng)
                            else null
                        }
                        firstPin?.let {
                            // Tighter zoom when re-anchoring on a specific
                            // contact; the wider 13 for the generic case.
                            view.controller.setZoom(if (focusPin != null) 15.0 else 13.0)
                            view.controller.setCenter(it)
                            didInitialCenter.value = true
                        }
                    }
                    view.invalidate()
                },
            )
            // Cyan tint scrim — pulls the inverted tiles toward the
            // LunaGlass palette without obscuring labels. 10 % alpha.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(AegisCyan.copy(alpha = 0.05f)),
            )
            // Radar sweep overlay — anchored to the user's geographic
            // position via radarCenter (recomputed on every map move),
            // so as you pan/zoom the sweep stays put on "you", not on
            // screen-centre. Sweep radius is computed from the live
            // distance to the farthest viewport corner so the arc
            // reaches visible edges even when zoomed in and the user's
            // pixel position is far off-screen.
            RadarSweepOverlay(
                center = radarCenter,
                peers = peerMarkers,
                // Tap a pin → quick-actions menu (status / contact / chat /
                // call / video / navigate) instead of going straight to the
                // contact card.
                onPeerTap = { pubkey, pos -> avatarMenuKey = pubkey; avatarMenuPos = pos },
                onSelfTap = { navController?.navigate("profile") },
            )
            // Floating "RADAR · n online · m pins" chip in the top-
            // left of the map. Used to be a full-width Column header
            // that stole vertical space from the actual map; now it
            // overlays the tiles and the map gets the full Box.
            RadarHeaderChip(
                online = onlineCount,
                total = peerKeys.size,
                withGps = withGps,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 10.dp),
            )
            // "Recenter" hex button — bottom-right of
            // the map, one tap snaps the camera back to fit every
            // family pin. Dimmed when we have no pins to centre on.
            if (familyBounds != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp),
                ) {
                    app.aether.aegis.ui.components.HexShape(
                        size = 48.dp,
                        borderColor = AegisCyan,
                        fillColor = AegisPanel.copy(alpha = 0.92f),
                        glow = true,
                        onClick = { recenterOnFamily() },
                    ) {
                        app.aether.aegis.ui.components.AegisIcon(
                            icon = app.aether.aegis.ui.components.AegisIcons.Recenter,
                            contentDescription = stringResource(R.string.map_recenter_on_family),
                            tint = AegisCyan,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        // Bottom dock — list of every paired peer + self. Transparent so
        // it sits on the app's plain background like every other list,
        // instead of carrying its own panel fill.
        Surface(
            color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp, max = 320.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                // Plain label, no aggregate. The live online/on-map counts
                // live in the one on-map RADAR chip; repeating online/away
                // here was the second, redundant counter — and the
                // per-peer rows just below already show each status.
                Text(
                    "CONTACTS",
                    color = AegisCyan,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                if (hasUntrustedOnly) {
                    Text(
                        "Only Trusted contacts share location and appear here. " +
                            "Open a contact and promote them to Trusted to add them to the radar.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(everyone, key = { it.publicKey }) { member ->
                        val s = statusByKey[member.identifier]
                        val isSelf = member.publicKey == selfKey
                        // Per-row avatar: self uses our own profile
                        // image (already read on every recompose
                        // above), peers use their announcedAvatarPath
                        // from the KnownPeer row. Either may be null
                        // — FamilyDockRow falls back to a first-letter
                        // monogram in that case.
                        val avatarPath = if (isSelf) selfAvatarPath
                        else knownPeerByKey[member.publicKey]?.announcedAvatarPath
                        // Self shows OUR OWN current tier (was null, so the self
                        // dock row never showed the tier we'd earned —
                        // inconsistent with the self map pin, which already used
                        // currentTier). Peers use their announced tier.
                        val rowTier = if (isSelf)
                            app.aether.aegis.admin.ShieldTierEngine.currentTier(AegisApp.instance)
                                .takeIf { it != app.aether.aegis.admin.ShieldTier.None }
                        else knownPeerByKey[member.publicKey]?.peerReportedTier
                            ?.let { runCatching { app.aether.aegis.admin.ShieldTier.valueOf(it) }.getOrNull() }
                            ?.takeIf { it != app.aether.aegis.admin.ShieldTier.None }
                        FamilyDockRow(
                            name = member.name,
                            avatarPath = avatarPath,
                            status = s,
                            isSelf = isSelf,
                            tier = rowTier,
                            crownStyle = if (isSelf) null
                                else knownPeerByKey[member.publicKey]?.peerReportedCrownStyle,
                            onOpenDetail = {
                                val encoded = java.net.URLEncoder.encode(member.publicKey, "UTF-8")
                                navController?.navigate("status/device/$encoded")
                            },
                            onLocate = {
                                val lat = s?.latitude ?: return@FamilyDockRow
                                val lng = s.longitude ?: return@FamilyDockRow
                                mapView.controller.animateTo(org.osmdroid.util.GeoPoint(lat, lng))
                                mapView.controller.setZoom(15.0)
                            },
                            // Hop straight to this person's chat — no hunting
                            // for them in the chat list. Self has no DM.
                            onMessage = if (isSelf) null else {
                                {
                                    val encoded = java.net.URLEncoder.encode(member.publicKey, "UTF-8")
                                    navController?.navigate("chat/$encoded")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // Pin quick-actions — a reliable LunaGlass dialog (the radial-pie /
    // NWN-wheel arrangement is a follow-on visual pass). Resolves the tapped
    // peer's name + GPS fix to wire each action.
    avatarMenuKey?.let { pk ->
        val member = everyone.firstOrNull { it.publicKey == pk }
        val st = member?.let { statusByKey[it.identifier] }
        val name = member?.name ?: "Contact"
        val encoded = java.net.URLEncoder.encode(pk, "UTF-8")
        val lat = st?.latitude
        val lng = st?.longitude
        // Assemble the action ring. Navigate only joins when we hold a
        // fix for the peer — no point routing to an unknown location.
        val actions = buildList {
            // Remember which contact we're drilling into so the radar
            // re-anchors on THEM (not self) when we come back — see MapFocus.
            add(RadialAction("Status", AegisIcons.Person) {
                MapFocus.peerKey = member?.identifier
                navController?.navigate("status/device/$encoded")
            })
            add(RadialAction("Contact", AegisIcons.Vault) {
                MapFocus.peerKey = member?.identifier
                navController?.navigate("contact/$encoded")
            })
            add(RadialAction("Chat", AegisIcons.Send) {
                MapFocus.peerKey = member?.identifier
                navController?.navigate("chat/$encoded")
            })
            add(RadialAction("Call", AegisIcons.Call) {
                app.aether.aegis.call.CallManager.placeCall(pk, name, video = false)
            })
            add(RadialAction("Video", AegisIcons.Play) {
                app.aether.aegis.call.CallManager.placeCall(pk, name, video = true)
            })
            if (lat != null && lng != null) {
                add(RadialAction("Navigate", AegisIcons.Location) {
                    // Hand off to the user's OWN map app for routing —
                    // Aegis never queries an external routing server, so
                    // neither endpoint leaks.
                    val uri = android.net.Uri.parse("google.navigation:q=$lat,$lng")
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                })
            }
        }
        // Fan the wheel out FROM the avatar on the map (map-Box-relative
        // pin position + the Box's window origin), not a recentred hub.
        val px = avatarMenuPos
        AnchoredRadialMenu(
            windowAnchor = androidx.compose.ui.unit.IntOffset(
                (mapBoxOrigin.x + (px?.x ?: 0f)).toInt(),
                (mapBoxOrigin.y + (px?.y ?: 0f)).toInt(),
            ),
            name = name,
            actions = actions,
            onDismiss = { avatarMenuKey = null; avatarMenuPos = null },
        )
    }
}

/** One slot in the radial avatar menu: a label, an icon drawable, and
 *  what to do when tapped. */
private data class RadialAction(
    val label: String,
    @androidx.annotation.DrawableRes val icon: Int,
    val onClick: () -> Unit,
)

/**
 * Last contact the user drilled into FROM the radar (status / contact /
 * chat). Process-scoped + retained OUTSIDE composition on purpose: the
 * radar tab's MapScreen — and its osmdroid MapView — is torn down and
 * rebuilt when you navigate into a contact and back, which resets the
 * one-shot auto-centre guard and snapped the camera to self ("first
 * pin"). Stashing the focused peer here lets the auto-centre re-anchor on
 * the contact you came from instead of you. Cleared by [recenterOnFamily]
 * (the explicit "fit everyone" button) so the user can deliberately get
 * back to the whole-family view. Identifier = statusByKey key.
 */
private object MapFocus {
    @Volatile var peerKey: String? = null
}

/**
 * Neverwinter-Nights-style radial quick-action wheel, anchored to the
 * tapped avatar's position ON the map.
 *
 * The action hexes bloom outward in a ring centred on the avatar itself
 * (no separate hub hex — the pin already sits at the centre under the
 * wheel), so it reads as the abilities fanning out of the contact you
 * tapped rather than a menu recentred on screen. Rendered in a [Popup]
 * placed in window space via [windowAnchor]; the position provider clamps
 * the wheel fully on-screen, so slots near a map edge shift inward
 * instead of clipping. The peer's name floats above the top slot.
 */
@Composable
private fun AnchoredRadialMenu(
    windowAnchor: androidx.compose.ui.unit.IntOffset,
    name: String,
    actions: List<RadialAction>,
    onDismiss: () -> Unit,
) {
    val provider = remember(windowAnchor) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ): androidx.compose.ui.unit.IntOffset {
                // Centre the wheel on the avatar, clamped so the whole ring
                // stays on-screen near a map edge.
                val x = (windowAnchor.x - popupContentSize.width / 2)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = (windowAnchor.y - popupContentSize.height / 2)
                    .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }
        // One spring drives the whole bloom: 0 → 1 fans the ring out and
        // fades it in. Slight under-damping gives a small, lively overshoot.
        val bloom by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.62f, stiffness = 320f),
            label = "radial-bloom",
        )
        val ringRadius = 92.dp
        val hexSize = 56.dp
        // Square box big enough for the ring + a slot diameter all round,
        // so the box centre = the wheel centre = (via the provider) the
        // avatar.
        Box(
            modifier = Modifier
                .size(ringRadius * 2 + hexSize * 2)
                // Dismiss on a tap anywhere in the wheel's empty space — the
                // square content box's corners and the hub gap between slots.
                // The Popup's outside-dismiss only fires for taps BEYOND the
                // popup window; these gaps are INSIDE it, so without this a
                // tap there did nothing ("areas outside the wheel that don't
                // close it when tapped"). Slot hexes carry their own onClick
                // and, as children, win the hit-test, so only true gaps here
                // dismiss. No indication → no ripple across the whole square.
                .clickable(
                    interactionSource = remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    },
                    indication = null,
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            // Peer name floats just above the top slot — context without a
            // hub hex stealing the centre.
            Text(
                name.take(12),
                color = AegisCyan,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = -(ringRadius + hexSize * 0.6f))
                    .alpha(bloom),
            )
            actions.forEachIndexed { i, action ->
                // Even distribution, first slot at the top (−90°), going
                // clockwise. Radius scales with the bloom so the slot
                // travels out from the hub as the menu opens.
                val angle = (-90.0 + i * 360.0 / actions.size) * Math.PI / 180.0
                val r = ringRadius * bloom
                val dx = (r.value * cos(angle)).dp
                val dy = (r.value * sin(angle)).dp
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = dx, y = dy)
                        .alpha(bloom),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    app.aether.aegis.ui.components.HexShape(
                        size = hexSize,
                        borderColor = AegisCyan,
                        fillColor = AegisPanel,
                        gradientFill = true,
                        edgeLighting = true,
                        onClick = {
                            onDismiss()
                            action.onClick()
                        },
                    ) {
                        app.aether.aegis.ui.components.AegisIcon(
                            icon = action.icon,
                            contentDescription = action.label,
                            tint = AegisCyan,
                            modifier = Modifier.size(
                                app.aether.aegis.ui.components.hexInnerIcon(hexSize),
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        action.label,
                        color = AegisCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarHeaderChip(
    online: Int,
    total: Int,
    withGps: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = AegisPanel.copy(alpha = 0.78f),
        border = androidx.compose.foundation.BorderStroke(1.dp, AegisBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.map_radar),
                color = AegisCyan,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Both numerator and denominator exclude self — the chip
            // is about PEERS' GPS-sharing state, not your own.
            // Earlier the math was "withGps / (total-1)" which left
            // self in the numerator: a solo install displayed "1/0"
            // (you have GPS, zero peers paired). Now reads cleanly
            // as "0/0" when there are no peers, and the chip
            // collapses to a "solo" label entirely in that case to
            // avoid the empty-fraction noise.
            val peerTotal = (total - 1).coerceAtLeast(0)
            val peerWithGps = (withGps - 1).coerceAtLeast(0)
            if (peerTotal == 0) {
                Text(
                    stringResource(R.string.map_solo),
                    color = AegisOnSurfaceDim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                val tintCount = when {
                    peerWithGps == 0         -> app.aether.aegis.ui.theme.AegisWarning
                    peerWithGps == peerTotal -> AegisOnline
                    else                     -> AegisCyan
                }
                // Was a bare "1/3" + tiny pin icon. The fraction alone
                // didn't read as "with GPS" — the user saw "1/3 · 2
                // online" and assumed the two were contradictory. Add
                // an explicit "on map" suffix so the fraction is
                // self-explanatory at a glance.
                app.aether.aegis.ui.components.AegisIcon(
                    icon = app.aether.aegis.ui.components.AegisIcons.Location,
                    contentDescription = null,
                    tint = tintCount,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    "$peerWithGps/$peerTotal on map",
                    color = tintCount,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (peerTotal > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "· $online online",
                    color = if (online == total) AegisOnline else AegisCyan,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/** Single peer's projected screen position + status bucket. Recomputed
 *  on every map move/zoom and on every GPS update so the dot tracks
 *  the underlying geographic point. [hasFix] = false means we placed
 *  the marker on the outer ring as a ghost because the peer hasn't
 *  shared a GPS fix yet.
 *
 *  [name] and [avatarPath] carry the data the on-map HexShape needs
 *  to render an avatar (image if file present, first-letter monogram
 *  otherwise). [pubkey] doubles as the navigation key for the
 *  tap-to-open-contact action. */
private data class PeerMarker(
    val pubkey: String,
    val name: String,
    val avatarPath: String?,
    val pos: Offset,
    val isSelf: Boolean,
    val status: app.aether.aegis.ui.components.PeerStatus,
    val hasFix: Boolean = true,
    // Shield tier the peer published — colours the avatar frame so the
    // family's security progress reads at a glance, same as Comms. Null
    // = none announced (falls back to the status colour).
    val tier: app.aether.aegis.admin.ShieldTier? = null,
    // Crown shimmer style the peer announced (0 glow / 1 rainbow / 2 oil-slick),
    // or null if none — so the peer's Cyan medal pin shimmers in THEIR style,
    // not the viewer's local pref. Same source as the Comms list.
    val crownStyle: Int? = null,
)

/**
 * Animated radar sweep on top of the map.
 *
 * [center] is the screen-pixel point the sweep emanates from. Caller
 * passes the projected pixel of the user's GPS location, so as the
 * map pans/zooms the radar stays anchored to "you on Earth" rather
 * than to the centre of the viewport. Null falls back to viewport
 * centre — used while we have no GPS fix.
 *
 * [peers] are drawn as small hexes at their projected pixel; each one
 * lights up when the sweep's leading edge passes its bearing, then
 * fades back to a low base brightness over ~90° of sweep travel.
 * Classic radar ping behaviour.
 *
 * Three concentric range rings + a 4 s/rev arc fading from cyan at
 * the leading edge to transparent at ~80° trail. Sweep radius is
 * computed live as "distance from centre to farthest viewport corner
 * + diagonal buffer" so the arc reaches the visible edges even when
 * the centre is far off-screen (zoom-in case). Purely decorative —
 * map gestures pass straight through (Canvas doesn't consume pointer
 * events).
 */
@Composable
private fun RadarSweepOverlay(
    center: Offset?,
    peers: List<PeerMarker>,
    onPeerTap: (pubkey: String, pos: Offset) -> Unit,
    onSelfTap: () -> Unit,
) {
    // No LunaGlass-enrichment gate here — the sweep IS the Radar
    // screen's identity, not cosmetic decoration. Hiding it on low
    // battery just leaves a map labelled "RADAR" with no radar on it.
    val transition = rememberInfiniteTransition(label = stringResource(R.string.map_radarsweep))
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = stringResource(R.string.map_radarangle),
    )
    // Two Canvases stacked. The sweep + range rings live on a 0.45-alpha
    // layer so they read as ambient atmosphere over the map — that part
    // was always correct. The peer + self HEX markers used to share that
    // same layer, which silently dimmed them to effective alpha ≈ 0.25
    // against busy inverted-Mapnik tiles — every peer rim was technically
    // drawn but visually undetectable. There were ~10 reports of "no
    // hexes on the radar" before this diagnosis stuck. Markers now go on
    // a full-alpha layer so they pop.
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .alpha(0.45f),
    ) {
        val cx = center?.x ?: (size.width / 2f)
        val cy = center?.y ?: (size.height / 2f)
        val viewportDiag = kotlin.math.sqrt(
            size.width * size.width + size.height * size.height,
        )
        // Sweep radius is the live distance from the (possibly far
        // off-screen) centre to the farthest viewport corner, plus a
        // diagonal buffer. The earlier fixed 3× diagonal was too short
        // once the user zoomed in: the user's pixel could land tens of
        // thousands of pixels outside the viewport, and a 3× diagonal
        // arc never reached visible territory — radar appeared gone.
        // Canvas clips draws outside the viewport so the extra length
        // costs nothing on screen.
        val toFarthestCorner = maxOf(
            kotlin.math.hypot(cx.toDouble(), cy.toDouble()),
            kotlin.math.hypot((size.width - cx).toDouble(), cy.toDouble()),
            kotlin.math.hypot(cx.toDouble(), (size.height - cy).toDouble()),
            kotlin.math.hypot((size.width - cx).toDouble(), (size.height - cy).toDouble()),
        ).toFloat()
        val sweepR = (toFarthestCorner + viewportDiag).coerceAtLeast(viewportDiag * 2f)
        // Range rings stay at viewport scale — anything bigger would
        // mean off-screen-only rings even when zoomed out far enough
        // to see the user's location.
        val ringMaxR = viewportDiag * 0.35f

        val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
        listOf(0.33f, 0.66f, 1.0f).forEach { r ->
            drawCircle(
                color = AegisCyan.copy(alpha = 0.35f),
                radius = ringMaxR * r,
                center = Offset(cx, cy),
                style = Stroke(width = 1.2f, pathEffect = dashed),
            )
        }
        // Crosshair ticks at the outer ring. Off-screen ticks are
        // invisible anyway; no need to special-case.
        val tickLen = ringMaxR * 0.06f
        listOf(0.0, Math.PI / 2, Math.PI, 3 * Math.PI / 2).forEach { a ->
            val sx = cx + (ringMaxR - tickLen) * cos(a).toFloat()
            val sy = cy + (ringMaxR - tickLen) * sin(a).toFloat()
            val ex = cx + ringMaxR * cos(a).toFloat()
            val ey = cy + ringMaxR * sin(a).toFloat()
            drawLine(
                color = AegisCyan.copy(alpha = 0.5f),
                start = Offset(sx, sy),
                end = Offset(ex, ey),
                strokeWidth = 1.5f,
                cap = StrokeCap.Round,
            )
        }
        // Sweep arc trail — radial lines along the trail with alpha
        // falling off. Cheap enough at 60 fps even with sweepR huge,
        // because each line is a single GPU draw clipped to viewport.
        val leading = Math.toRadians(angle.toDouble() - 90.0)
        val trailDeg = 80
        for (i in 0 until trailDeg) {
            val a = leading - Math.toRadians(i.toDouble())
            val alpha = (1f - i / trailDeg.toFloat()) * 0.55f
            drawLine(
                color = AegisCyan.copy(alpha = alpha),
                start = Offset(cx, cy),
                end = Offset(
                    cx + sweepR * cos(a).toFloat(),
                    cy + sweepR * sin(a).toFloat(),
                ),
                strokeWidth = 2f,
            )
        }
        // Leading edge — bright cyan, stacked draws fake a glow.
        val la = leading
        drawLine(
            color = AegisCyanGlow.copy(alpha = 0.8f),
            start = Offset(cx, cy),
            end = Offset(
                cx + sweepR * cos(la).toFloat(),
                cy + sweepR * sin(la).toFloat(),
            ),
            strokeWidth = 4f,
        )
        drawLine(
            color = AegisCyan,
            start = Offset(cx, cy),
            end = Offset(
                cx + sweepR * cos(la).toFloat(),
                cy + sweepR * sin(la).toFloat(),
            ),
            strokeWidth = 1.5f,
        )
    }
    // Markers — full alpha. Self centred at `center` (skipped if no
    // GPS), peers at their projected pixel, ghosts on a ring. Pulse
    // animation reads the same `angle` so each peer still lights up
    // as the sweep crosses its bearing.
    // Halo canvas — full alpha for the self-glow and the per-peer
    // pulse halos that follow the sweep. Hex bodies themselves are
    // rendered as positioned composables below so they can hold an
    // avatar image and accept a tap-to-open-contact click — neither
    // is reachable from inside a DrawScope.
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = center?.x ?: (size.width / 2f)
        val cy = center?.y ?: (size.height / 2f)
        val selfHaloR = 30.dp.toPx()
        if (center != null) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(AegisCyanGlow.copy(alpha = 0.8f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = selfHaloR,
                ),
                radius = selfHaloR,
                center = Offset(cx, cy),
            )
        }
        val leadingDeg = ((angle - 90.0) + 360.0) % 360.0
        val pulseDecayDeg = 90.0
        val haloR = 72.dp.toPx()
        peers.forEach { marker ->
            if (marker.isSelf || !marker.hasFix) return@forEach
            val dx = marker.pos.x - cx
            val dy = marker.pos.y - cy
            val bearingDeg = ((Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())) + 360.0)) % 360.0
            val degBehind = ((leadingDeg - bearingDeg) + 360.0) % 360.0
            val pulse = if (degBehind < pulseDecayDeg)
                (1.0 - degBehind / pulseDecayDeg).toFloat() else 0f
            if (pulse <= 0.4f) return@forEach
            val pinColor = when (marker.status) {
                app.aether.aegis.ui.components.PeerStatus.Online  -> AegisCyan
                app.aether.aegis.ui.components.PeerStatus.Away    -> app.aether.aegis.ui.theme.AegisWarning
                app.aether.aegis.ui.components.PeerStatus.Offline -> app.aether.aegis.ui.theme.AegisOnSurfaceDim
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(pinColor.copy(alpha = pulse * 0.5f), Color.Transparent),
                    center = marker.pos,
                    radius = haloR,
                ),
                radius = haloR,
                center = marker.pos,
            )
        }
    }
    // Hex composable layer. Each peer + self gets a positioned
    // [HexShape] (size matched, avatar centred inside) that accepts
    // clicks to open the contact card. Position is the projected
    // pixel of the peer's GPS fix, or a slot on the outer ghost ring
    // for peers we haven't located yet.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val hexSize = 40.dp
    val hexHalfPx = with(density) { (hexSize / 2).toPx() }
    Box(modifier = Modifier.fillMaxSize()) {
        peers.forEach { marker ->
            // key() by peer identity is load-bearing: without it Compose
            // matches these pins POSITIONALLY, so when the peers list
            // reorders or changes membership (status tick, a GPS fix
            // arriving, ghost→fixed) each slot's AsyncImage state is
            // re-bound to a DIFFERENT peer — avatars land on the wrong
            // pins and flicker against the monogram fallback. Keyed, each
            // pin's image state stays with its own peer.
            key(marker.pubkey) {
                PeerHex(
                    marker = marker,
                    size = hexSize,
                    modifier = Modifier.offset {
                        androidx.compose.ui.unit.IntOffset(
                            (marker.pos.x - hexHalfPx).toInt(),
                            (marker.pos.y - hexHalfPx).toInt(),
                        )
                    },
                    onClick = {
                        if (marker.isSelf) onSelfTap() else onPeerTap(marker.pubkey, marker.pos)
                    },
                )
            }
        }
    }
}

/**
 * Single peer (or self) hex on the radar canvas. LunaGlass HexShape
 * with the contact's avatar inside — image if announcedAvatarPath
 * points at a present file, first-letter monogram otherwise. Border
 * is tinted by status (online cyan / away amber / offline grey) and
 * glows when the peer is online. Ghost hexes (no GPS fix yet) drop
 * to dim grey, lower alpha, no glow — still tappable so the user
 * can drill down into a ghost contact's status page.
 */
@Composable
private fun PeerHex(
    marker: PeerMarker,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ghost = !marker.hasFix
    Box(modifier = modifier.alpha(if (ghost) 0.7f else 1f)) {
        // Canonical avatar — same metal-frame + engraved-plate renderer as
        // Comms. The old radar-only cyan self fill (AegisCyan @ 0.20) is gone:
        // it was the "awful cyan fill" and, stacked under the self glow on a
        // self pin that overlapped a peer pin, read as the "me as cyan and
        // Pixel randomly" flicker.
        app.aether.aegis.ui.components.AegisAvatar(
            size = size,
            tier = if (ghost) null else marker.tier,
            initial = marker.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            avatarPath = marker.avatarPath?.takeIf { !ghost },
            online = !ghost && marker.status == app.aether.aegis.ui.components.PeerStatus.Online,
            peerReportedCrownStyle = marker.crownStyle,
            isSelf = marker.isSelf,
            onClick = onClick,
        )
    }
}

/**
 * Compact LunaGlass row — hex avatar + name + presence/battery line,
 * 📍 to centre the map and chevron to drill down. Replaces the old
 * pill-shaped PersonChip; reads in the dock without being a wall of
 * Material chip cards.
 */
@Composable
private fun FamilyDockRow(
    name: String,
    avatarPath: String?,
    status: app.aether.aegis.data.MemberStatusEntity?,
    isSelf: Boolean,
    onOpenDetail: () -> Unit,
    onLocate: () -> Unit,
    tier: app.aether.aegis.admin.ShieldTier? = null,
    /** Crown shimmer style the peer announced (0/1/2), or null. Renders their
     *  Cyan medal in their style rather than the viewer's local pref. */
    crownStyle: Int? = null,
    /** Hop to this contact's chat. Null for self (no DM). */
    onMessage: (() -> Unit)? = null,
) {
    val now = System.currentTimeMillis()
    // Central presence helper — same calc the chat list, map
    // markers, and DeviceStatusScreen now use. Reads both lastActive +
    // lastPacketMs so backgrounded peers read Away, not Offline.
    val presence = app.aether.aegis.ui.components.peerStatusFor(
        status = status, nowMs = now, isSelf = isSelf,
    )
    val ringColor = when (presence) {
        app.aether.aegis.ui.components.PeerStatus.Online  -> AegisCyan
        app.aether.aegis.ui.components.PeerStatus.Away    -> app.aether.aegis.ui.theme.AegisWarning
        app.aether.aegis.ui.components.PeerStatus.Offline -> AegisBorder
    }
    val online = presence == app.aether.aegis.ui.components.PeerStatus.Online
    val hasFix = status?.latitude != null
    val lastActive = status?.lastActive
    val locationText = when {
        hasFix && isSelf && lastActive != null -> "here · ${ageString(lastActive)}"
        hasFix && lastActive != null           -> ageString(lastActive)
        isSelf                                  -> "waiting for GPS…"
        else                                    -> "no GPS fix"
    }

    GlassPanel(
        glow = online,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Canonical avatar — identical renderer to Comms + the radar pins.
            app.aether.aegis.ui.components.AegisAvatar(
                size = 36.dp,
                tier = tier,
                initial = name.take(1).uppercase(),
                avatarPath = avatarPath,
                online = online,
                peerReportedCrownStyle = crownStyle,
                isSelf = isSelf,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasFix) {
                        app.aether.aegis.ui.components.AegisIcon(
                            icon = app.aether.aegis.ui.components.AegisIcons.Location,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(11.dp),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Text(
                        locationText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
            val battery = status?.batteryLevel
            val appVersion = status?.appVersion
            if (battery != null || appVersion != null) {
                Column(horizontalAlignment = Alignment.End) {
                    if (battery != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${battery}%",
                                color = if (battery > 20) MaterialTheme.colorScheme.onSurface else AegisSOS,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (status?.isCharging == true) {
                                Spacer(modifier = Modifier.width(3.dp))
                                app.aether.aegis.ui.components.AegisIcon(
                                    icon = app.aether.aegis.ui.components.AegisIcons.Charging,
                                    contentDescription = stringResource(R.string.map_charging),
                                    tint = app.aether.aegis.ui.theme.AegisOnline,
                                    modifier = Modifier.size(11.dp),
                                )
                            }
                        }
                        // Signal as bars (was raw "-70 dBm" text). Keep the
                        // dBm as a trailing caption for the precise reading.
                        val sig = status?.signalStrength
                        if (sig != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                app.aether.aegis.ui.components.SignalBars(
                                    dbm = sig,
                                    width = 14.dp,
                                    height = 10.dp,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "$sig dBm",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 9.sp,
                                )
                            }
                        }
                    }
                    if (appVersion != null) {
                        // Peer's Aegis build — only present for
                        // Trusted (gate-side filter), and only
                        // after they're on a version-broadcasting
                        // build. Lets the user spot "X is on an old
                        // build" before debugging weird behaviour.
                        Text(
                            "v$appVersion",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            // Trailing actions in FIXED columns so the pin + chat icons sit
            // at the SAME x-position on every row. A row missing one action
            // renders an empty slot of equal size instead of letting the
            // other icon slide into its place — previously a pin-only row
            // put its pin where a pin+chat row put its chat button, so you'd
            // reach for one and hit the other. 40dp slots (was 32) widen the
            // tap target and a gap separates the two so they're no longer a
            // misclick trap.
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .then(if (hasFix) Modifier.clickable(onClick = onLocate) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (hasFix) {
                    app.aether.aegis.ui.components.AegisIcon(
                        icon = app.aether.aegis.ui.components.AegisIcons.Location,
                        contentDescription = stringResource(R.string.map_locate_on_map),
                        tint = AegisCyan,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            // Chat hop — jump straight into this contact's conversation.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .then(if (onMessage != null) Modifier.clickable(onClick = onMessage) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                if (onMessage != null) {
                    app.aether.aegis.ui.components.AegisIcon(
                        icon = app.aether.aegis.ui.components.AegisIcons.Send,
                        contentDescription = "Open chat",
                        tint = AegisCyan,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}


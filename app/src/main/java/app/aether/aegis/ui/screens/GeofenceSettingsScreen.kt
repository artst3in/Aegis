package app.aether.aegis.ui.screens

import app.aether.aegis.geofence.GeofenceStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.ui.components.GlassPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Configure the geofence centred at the user's CURRENT location, with
 * an adjustable radius. Pause buttons (1 / 4 / 8 hours) for predictable
 * absences (school trip, lunch break). Auto-resumes when the pause
 * window expires.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeofenceSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val store = remember { GeofenceStore(context) }
    val scope = rememberCoroutineScope()
    // Local UI mirrors of the persisted GeofenceStore fields. Each control
    // writes both this state (immediate recomposition) and the store
    // (persistence) on change — there is no separate save step.
    var enabled by remember { mutableStateOf(store.enabled) }
    var radius by remember { mutableStateOf(store.radiusMeters) }
    var centerLat by remember { mutableStateOf(store.centerLat) }
    var centerLng by remember { mutableStateOf(store.centerLng) }
    var pausedUntil by remember { mutableStateOf(store.pausedUntil) }
    var setZoneStatus by remember { mutableStateOf<String?>(null) }
    // nowMs is a coarse clock used only to decide whether the pause window
    // is still active. Refreshed every 30 s so the "Paused until …" status
    // flips back to active on its own, without the user re-entering the
    // screen. 30 s is plenty — pause windows are measured in hours.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }
    // Derived: paused iff the stored expiry is still in the future. Both
    // pausedUntil and nowMs are state, so this re-evaluates when either
    // the user pauses/resumes or the 30 s tick advances the clock.
    val paused = pausedUntil > nowMs

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.security_geofence)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Text("←", fontSize = 20.sp)
                    }
                },
            )
        },
    ) { padding ->
        // The whole geofence config sits behind the app-PIN gate — zone
        // coordinates and pause schedule are sensitive (they reveal home
        // location and predictable absences), so an attacker who grabs an
        // unlocked-but-not-PIN'd phone can't read or alter them.
        app.aether.aegis.ui.components.PinGuardedContent(
            navController = navController,
            featureLabel = stringResource(R.string.security_geofence),
        ) {
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.settings_how_it_works), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.geofence_set_a_circular_zone) +
                            "the zone, an alert goes to every paired peer. The whole thing " +
                            "is set up on YOUR phone, by you — paired peers can't configure " +
                            "it on you remotely.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            // Enable + indicator
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_enabled), fontWeight = FontWeight.SemiBold)
                        // Status precedence is deliberate: Off beats
                        // Paused beats "no zone set" beats Active. A
                        // disabled geofence shouldn't advertise its pause
                        // or zone state, and a paused one shouldn't claim
                        // to be active.
                        Text(
                            when {
                                !enabled -> "Off."
                                paused -> "Paused until " + formatMessageTimestamp(pausedUntil)
                                !store.isConfigured -> "On but no zone set yet — tap 'Set zone here' below."
                                else -> "Geofence active · ${radius} m radius"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    app.aether.aegis.ui.components.HexSwitch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            store.enabled = it
                        },
                    )
                }
            }

            // Set / reset zone
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.geofence_zone_centre), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (store.isConfigured)
                            "%.5f, %.5f".format(centerLat, centerLng)
                        else "not set",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        // Read the current location and pin the geofence
                        // to it. Tries GPS → NETWORK → PASSIVE (each
                        // platform-direct, no Play Services). If
                        // permission isn't granted or no fix is cached
                        // yet, surface the status inline so the user
                        // knows why nothing happened.
                        scope.launch {
                            val loc = readCurrentLocation(context)
                            if (loc != null) {
                                centerLat = loc.first
                                centerLng = loc.second
                                store.centerLat = loc.first
                                store.centerLng = loc.second
                                // Seed lastInside=true: we KNOW the phone is
                                // at the zone centre right now, so the next
                                // tick shouldn't read a stale "was outside"
                                // and fire a spurious exit alert.
                                store.lastInside = true
                                setZoneStatus = "Zone set: %.5f, %.5f".format(
                                    loc.first, loc.second,
                                )
                            } else {
                                setZoneStatus =
                                    "No GPS fix yet. Open the Map tab for 30 s, " +
                                    "then come back."
                            }
                        }
                    }) {
                        Text(if (store.isConfigured) "Reset zone here" else "Set zone here")
                    }
                    setZoneStatus?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // Radius slider. Range goes up to 50 km — the old 5 km cap
            // was unusable for anyone with a normal commute (one user
            // reported a 25 km drive to work tripping the geofence at
            // the office every morning). 50 m floor stays so a sloppy
            // GPS fix can't accidentally fire on a phone sitting still
            // in the kitchen.
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.geofence_radius), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (radius >= 1000) "%.1f km".format(radius / 1000f)
                        else "$radius m",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Live drag updates only the local `radius` (cheap
                    // recomposition); the store write is deferred to
                    // onValueChangeFinished so we persist once on release
                    // rather than on every intermediate frame. coerceIn
                    // re-clamps defensively even though the range already
                    // bounds the slider.
                    Slider(
                        value = radius.toFloat(),
                        onValueChange = { radius = it.toInt().coerceIn(50, 50_000) },
                        onValueChangeFinished = { store.radiusMeters = radius },
                        valueRange = 50f..50_000f,
                    )
                }
            }

            // Pause
            GlassPanel(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.geofence_pause), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (paused) "Paused until " + formatMessageTimestamp(pausedUntil)
                        else "Pause for predictable absences. Auto-resumes.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Fixed 1/4/8 h pause presets. After each, re-read the
                    // store's computed expiry into local state so `paused`
                    // recomputes and the status row updates immediately.
                    // The Resume button only appears while paused.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            store.pauseFor(1)
                            pausedUntil = store.pausedUntil
                        }) { Text("1h") }
                        OutlinedButton(onClick = {
                            store.pauseFor(4)
                            pausedUntil = store.pausedUntil
                        }) { Text("4h") }
                        OutlinedButton(onClick = {
                            store.pauseFor(8)
                            pausedUntil = store.pausedUntil
                        }) { Text("8h") }
                        if (paused) {
                            OutlinedButton(onClick = {
                                store.resumeNow()
                                pausedUntil = 0L
                            }) { Text(stringResource(R.string.geofence_resume)) }
                        }
                    }
                }
            }
        }
        }
    }
}

/** Best-effort current location read via the platform LocationManager.
 *  Tries GPS → NETWORK → PASSIVE; returns the first non-null cached
 *  fix as (lat, lng), or null if none are available. */
@android.annotation.SuppressLint("MissingPermission")
private suspend fun readCurrentLocation(context: android.content.Context): Pair<Double, Double>? =
    withContext(Dispatchers.IO) {
        // Hand-check both location grants (SuppressLint silences the lint
        // on getLastKnownLocation below). Either fine OR coarse is enough
        // to attempt a read; neither → bail with null so the caller shows
        // its "no fix" hint.
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return@withContext null
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE)
            as? android.location.LocationManager ?: return@withContext null
        // Provider preference order: GPS (most accurate, only if fine
        // granted) → NETWORK → PASSIVE (whatever another app last fetched).
        // We read CACHED fixes only (getLastKnownLocation), never request a
        // live update — keeps this cheap and avoids holding a listener.
        val providers = listOfNotNull(
            if (fine) android.location.LocationManager.GPS_PROVIDER else null,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
        )
        for (p in providers) {
            val loc = runCatching {
                if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null
            }.getOrNull()
            if (loc != null) return@withContext loc.latitude to loc.longitude
        }
        null
    }

/** Short, locale-aware timestamp for the "paused until <…>" row.
 *  Today → HH:mm; yesterday → "yesterday HH:mm"; otherwise short
 *  date + time. Lived in SOSDashboardScreen as `formatMessageTimestamp`
 *  before that screen was reaped; moved here as a private helper
 *  since this is now the only caller. */
private fun formatMessageTimestamp(ts: Long): String {
    val now = System.currentTimeMillis()
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = cal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR) &&
        cal.get(java.util.Calendar.DAY_OF_YEAR) == nowCal.get(java.util.Calendar.DAY_OF_YEAR)
    val yesterday = run {
        val y = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        cal.get(java.util.Calendar.YEAR) == y.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == y.get(java.util.Calendar.DAY_OF_YEAR)
    }
    val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    val dateFmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
    return when {
        sameDay -> timeFmt.format(java.util.Date(ts))
        yesterday -> "yesterday " + timeFmt.format(java.util.Date(ts))
        else -> dateFmt.format(java.util.Date(ts))
    }
}

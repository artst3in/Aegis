package app.aether.aegis.ui.screens

import app.aether.aegis.AegisApp
import app.aether.aegis.BuildConfig
import app.aether.aegis.R
import app.aether.aegis.ui.theme.LunaGlassFont
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource

/**
 * In-app splash. The system splash (Theme.Aegis.Splash) shows briefly
 * while the activity initialises; this Compose splash takes over and
 * holds the brand for a full HOLD_MS so the user actually sees it.
 *
 * Logo scales in subtly (0.9 → 1.0) over the hold to give a sense of
 * presence; static after that. Routes to chats (or onboarding) when
 * the time is up.
 */
@Composable
fun AegisSplashScreen(navController: NavController) {
    var visible by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = tween(durationMillis = HOLD_MS.toInt()),
        label = stringResource(R.string.splash_splashscale),
    )

    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        visible = true
        delay(HOLD_MS)
        // First-run language gate: if the user has never explicitly
        // picked, route to the language picker BEFORE profile
        // onboarding. After picking, the picker navigates onward.
        val languagePicked = app.aether.aegis.i18n.LanguagePrefs(ctx).picked
        // Language gate runs first; everything past it (tutorial vs
        // onboarding vs chats) is centralised in
        // firstRunNextDestination so the splash and the language
        // picker can't disagree about whether the onboarding
        // tutorial auto-shows.
        val next = when {
            !languagePicked -> "language?first=true"
            else -> firstRunNextDestination(ctx)
        }
        navController.navigate(next) {
            popUpTo("splash") { inclusive = true }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The hex shield (cyan), centred + slightly scaled on enter.
            // When the user has reached Cyan tier (every skill-tree
            // node lit), a soft cyan halo sits behind the shield —
            // brand colour leaking out as the ambient reward for
            // completing the climb.
            val cyanTierSplash = androidx.compose.runtime.remember(ctx) {
                app.aether.aegis.admin.ShieldTierEngine.currentTier(ctx) == app.aether.aegis.admin.ShieldTier.Cyan
            }
            // Brand mark presented in the SAME hex+glow treatment as the
            // lock screen (HexShape 96dp there), only larger here — so
            // when the splash hands off to the lock the mark reads as the
            // same object settling into place instead of swapping from a
            // bare image to a framed one (2026-06-07: "splash→lock
            // logo jump"). Hero size 176dp; the splash's enter-scale still
            // animates it in.
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(176.dp).scale(scale),
                contentAlignment = Alignment.Center,
            ) {
                if (cyanTierSplash) {
                    // Cyan-tier reward: an extra, brighter halo behind the
                    // hex glow — the ambient "you finished the climb" cue.
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier.size(176.dp),
                    ) {
                        drawCircle(
                            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(
                                    app.aether.aegis.ui.theme.AegisCyanGlow,
                                    Color.Transparent,
                                ),
                                center = androidx.compose.ui.geometry.Offset(
                                    size.width / 2,
                                    size.height / 2,
                                ),
                                radius = size.minDimension / 2,
                            ),
                            radius = size.minDimension / 2,
                        )
                    }
                }
                // Same HexShape framing the lock screen uses for the brand
                // mark. The foreground PNG (the cyan hex shield) sits
                // inside the glowing hex, matching the lock exactly.
                app.aether.aegis.ui.components.HexShape(
                    size = 176.dp,
                    borderColor = app.aether.aegis.ui.theme.AegisCyan,
                    fillColor = app.aether.aegis.ui.theme.AegisCyanGlow,
                    glow = true,
                    glowColor = app.aether.aegis.ui.theme.AegisCyanGlow,
                ) {
                    Image(
                        painter = painterResource(R.mipmap.ic_aegis_foreground),
                        contentDescription = stringResource(R.string.splash_aegis_logo),
                        modifier = Modifier.size(176.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.tutorial_aegis),
                style = TextStyle(
                    fontFamily = LunaGlassFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 56.sp,
                    letterSpacing = TextUnit(0.12f, TextUnitType.Em),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                ),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "PROJECT AETHER · ${BuildConfig.AETHER_OFFICIAL}",
                style = TextStyle(
                    fontFamily = LunaGlassFont,
                    fontSize = 11.sp,
                    letterSpacing = TextUnit(0.25f, TextUnitType.Em),
                    color = app.aether.aegis.ui.theme.AegisOnSurfaceDim,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

private const val HOLD_MS = 1500L

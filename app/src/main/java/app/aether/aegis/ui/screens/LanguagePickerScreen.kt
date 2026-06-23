package app.aether.aegis.ui.screens

import app.aether.aegis.ui.components.AegisTopBar
import app.aether.aegis.ui.components.HexRadio

import app.aether.aegis.ui.components.AegisButton

import app.aether.aegis.i18n.LanguagePrefs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.LocalTextStyle
import app.aether.aegis.ui.components.AegisIcon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import app.aether.aegis.R

/**
 * Language picker. Used in two contexts:
 *
 *  - First-run gate (AegisSplashScreen routes here when
 *    LanguagePrefs.picked is false), with [onPicked] handling
 *    where to go next.
 *  - Settings → Language, reached via a SettingsLinkRow, with the
 *    standard back arrow popping the back stack.
 *
 * Selection is applied via LanguagePrefs.setLocale which calls
 * AppCompatDelegate.setApplicationLocales — Android recreates the
 * activity automatically so the new locale is live before the user
 * sees the next frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerScreen(
    navController: NavController,
    isFirstRun: Boolean = false,
    onPicked: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val prefs = remember { LanguagePrefs(ctx) }
    var selected by remember {
        mutableStateOf(if (prefs.picked) prefs.tag.ifBlank { "system" } else "system")
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            AegisTopBar(
                title = {
                    Text(
                        if (isFirstRun) "Choose your language" else stringResource(R.string._language),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    if (!isFirstRun) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            AegisIcon(app.aether.aegis.ui.components.AegisIcons.Back, stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (isFirstRun) {
                Text(
                    stringResource(R.string.language_picker_aegis_is_available_in) +
                        "later in Settings → Language.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            LanguagePrefs.supported.forEach { opt ->
                LanguageRow(
                    opt = opt,
                    selected = selected == opt.tag,
                    onClick = { selected = opt.tag },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            AegisButton(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                onClick = {
                    prefs.setLocale(selected)
                    if (isFirstRun) {
                        onPicked?.invoke()
                    } else {
                        // Recreate so MainActivity.attachBaseContext
                        // re-applies the new locale. AppCompatDelegate
                        // alone doesn't recreate our non-AppCompat
                        // FragmentActivity, so without this the change
                        // silently does nothing on the running screen.
                        var c: android.content.Context = ctx
                        while (c is android.content.ContextWrapper && c !is android.app.Activity) {
                            c = c.baseContext
                        }
                        (c as? android.app.Activity)?.recreate()
                    }
                },
            ) {
                Text(if (isFirstRun) stringResource(R.string.tutorial_continue) else stringResource(R.string.group_members_apply), fontSize = 15.sp)
            }
        }
    }
}

/**
 * Strips Android's legacy font-metric padding and trims the line box to
 * the glyph height. Pair it with an explicit `lineHeight` on the Text —
 * LineHeightStyle is a no-op while lineHeight is unspecified, so on its
 * own it does nothing. Without this the LunaGlass serif (and the script
 * fallbacks the language endonyms pull in) leave each row ~2x too tall
 * with the text stranded in the middle.
 */
private val TightLineStyle = androidx.compose.ui.text.TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

@Composable
private fun LanguageRow(
    opt: app.aether.aegis.i18n.LanguageOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick),
        color = if (selected) app.aether.aegis.ui.theme.AegisCyan.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surface,
        shape = CutCornerShape(12.dp),
    ) {
        Row(
            // 6dp vertical (was 12) — the endonym + English subtitle are
            // a compact two-line stack; the old padding plus the radio's
            // touch-target reservation left each row ~2x taller than its
            // text. Tightened here, with the radio's min size collapsed
            // below.
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    opt.native,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    // Collapse the line box to the glyph height. The serif
                    // face (and the fallback faces for Arabic/Devanagari/
                    // CJK that the endonyms force in) carry tall intrinsic
                    // leading; without an explicit lineHeight + trim the
                    // row balloons to ~2x and the text floats in the
                    // middle. Same fix the hex components use.
                    lineHeight = 17.sp,
                    style = LocalTextStyle.current.merge(TightLineStyle),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (opt.native != opt.english) {
                    Text(
                        opt.english,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        style = LocalTextStyle.current.merge(TightLineStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // The real height culprit: a Material3 RadioButton reserves
            // a 48dp minimum interactive target, so even with tight text
            // each row floored at ~48dp and the endonym floated in the
            // middle. The whole Surface is already clickable, so the
            // radio doesn't need its own 48dp hit-box — collapse the
            // enforced minimum to 0 and it draws at its natural ~20dp.
            CompositionLocalProvider(
                androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp,
            ) {
                HexRadio(selected = selected, onClick = onClick)
            }
        }
    }
}

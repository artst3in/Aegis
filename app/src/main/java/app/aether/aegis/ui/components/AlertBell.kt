package app.aether.aegis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import app.aether.aegis.alert.AlertDismiss
import app.aether.aegis.alert.AlertEntry
import app.aether.aegis.alert.rememberAlerts
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisOnSurfaceDim

/**
 * The Alert Center bell — lives in the header's right cluster. Shows a small
 * severity-coloured badge (highest active severity) with the count; tapping
 * opens a calm dropdown panel of actionable items, each deep-linking to where
 * it's fixed/reviewed. Non-invasive by design: no popups, no toasts — the
 * badge is the only ambient signal.
 *
 * Severity → colour comes from [app.aether.aegis.alert.AlertSeverity]; the
 * list itself is computed by [rememberAlerts].
 */
@Composable
fun AlertBell(navController: NavController, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val alerts = rememberAlerts()
    var open by remember { mutableStateOf(false) }
    val top = alerts.maxByOrNull { it.severity.rank }?.severity

    Box(modifier) {
        BellGlyph(
            tint = AegisCyan,
            modifier = Modifier
                .size(24.dp)
                .clickable { open = true },
        )
        // Severity badge — a coloured pip with the count, top-end corner.
        if (alerts.isNotEmpty() && top != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(top.color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (alerts.size > 9) "9+" else alerts.size.toString(),
                    color = Color.Black,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 320.dp),
        ) {
            Text(
                if (alerts.isEmpty()) "ATTENTION" else "ATTENTION · ${alerts.size}",
                color = AegisCyan,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 4.dp),
            )
            if (alerts.isEmpty()) {
                Text(
                    "All clear — nothing needs your attention.",
                    color = AegisOnSurfaceDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            } else {
                alerts.forEach { entry ->
                    AlertRow(
                        entry = entry,
                        onOpen = {
                            open = false
                            entry.route?.let { runCatching { navController.navigate(it) } }
                        },
                        onDismiss = if (entry.dismissible) {
                            { AlertDismiss.dismiss(ctx, entry.id) }
                        } else null,
                    )
                }
            }
        }
    }
}

/** One alert row: severity pip + title/detail (tap = open the deep-link),
 *  with an optional ✕ to snooze a dismissible nudge for a day. */
@Composable
private fun AlertRow(entry: AlertEntry, onOpen: () -> Unit, onDismiss: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(entry.severity.color),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                entry.detail,
                color = AegisOnSurfaceDim,
                fontSize = 11.sp,
            )
        }
        if (onDismiss != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "✕",
                color = AegisOnSurfaceDim,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .padding(4.dp),
            )
        }
    }
}

/** Canvas-drawn bell — no Material Icons dependency, no drawable asset.
 *  Dome + flared body, a rim line, a top knob and a clapper dot. */
@Composable
private fun BellGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Bell body — left side sweeps up to the dome and back down the right.
        val body = Path().apply {
            moveTo(w * 0.26f, h * 0.64f)
            cubicTo(w * 0.26f, h * 0.34f, w * 0.34f, h * 0.20f, w * 0.50f, h * 0.20f)
            cubicTo(w * 0.66f, h * 0.20f, w * 0.74f, h * 0.34f, w * 0.74f, h * 0.64f)
        }
        drawPath(body, tint, style = stroke)
        // Rim across the bottom of the body.
        drawLine(
            tint,
            Offset(w * 0.20f, h * 0.66f),
            Offset(w * 0.80f, h * 0.66f),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
        // Top knob + clapper.
        drawCircle(tint, radius = w * 0.045f, center = Offset(w * 0.50f, h * 0.165f))
        drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.50f, h * 0.78f))
    }
}

package app.aether.aegis.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Why this file exists: Material 3 buttons do NOT read the theme's
 * [androidx.compose.material3.Shapes] scale for their corner. Their default
 * shape token is `CornerFull`, which the M3 source maps to a hardcoded
 * `CircleShape` (a full pill) — independent of the five `Shapes` slots. So
 * setting `shapes = AegisShapes` on `MaterialTheme` faceted cards, dialogs,
 * menus and text fields, but left every `Button` / `OutlinedButton` a rounded
 * pill, clashing with the LunaGlass angular language everywhere else.
 *
 * There is no theme knob for `CornerFull`, so the only fix is to pass an
 * explicit cut-corner [Shape]. These thin wrappers default that shape to
 * [AegisButtonShape] and otherwise forward every parameter to the real M3
 * composable, so a call site migrates by a pure rename — `Button(` →
 * `AegisButton(`, `OutlinedButton(` → `AegisOutlinedButton(` — with no
 * argument changes. A call site that genuinely wants a different shape can
 * still pass `shape = …` and it overrides the default as usual.
 */

/** LunaGlass faceted button corner — cut, not rounded. 8 dp matches the
 *  `medium` tier of `AegisShapes` so buttons sit in the same shape family
 *  as panels and dialogs. */
val AegisButtonShape: Shape = CutCornerShape(8.dp)

/**
 * [Button] with a faceted (cut-corner) shape by default. Drop-in for the M3
 * `Button`: every parameter is forwarded unchanged; only the `shape` default
 * differs. See the file header for why this wrapper is necessary.
 */
@Composable
fun AegisButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = AegisButtonShape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = Button(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

/**
 * [OutlinedButton] with a faceted (cut-corner) shape by default. Drop-in for
 * the M3 `OutlinedButton`: parameters are forwarded unchanged. The `border`
 * default mirrors M3's own (`outlinedButtonBorder(enabled)`) so the thin
 * outline that makes this an *outlined* button is preserved — passing
 * `border = null` here would erase it.
 */
@Composable
fun AegisOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = AegisButtonShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
)

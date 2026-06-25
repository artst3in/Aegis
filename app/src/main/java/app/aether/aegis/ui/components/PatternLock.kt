package app.aether.aegis.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import app.aether.aegis.ui.theme.AegisBorder
import app.aether.aegis.ui.theme.AegisCyan

/**
 * A 3×3 draw-pattern input (the "Pattern" unlock method).
 *
 * Drag across the nine dots; each dot the finger passes over (once) is
 * appended to the sequence. On release the sequence is emitted via
 * [onPattern] as dot indices 0..8 (row-major: top-left = 0, bottom-right
 * = 8) and the grid resets. A sequence shorter than two dots is treated
 * as an accidental tap and ignored.
 *
 * Purely an INPUT widget — it knows nothing about hashing or unlocking.
 * The lock screen / settings hash the emitted sequence via
 * [app.aether.aegis.lock.LockStore.setPattern] / `verifyPattern`.
 *
 * NOTE: like the lock curtain, drag feel + hit radius want tuning on real
 * hardware; the values here are a first cut.
 */
@Composable
fun PatternLock(
    modifier: Modifier = Modifier,
    onPattern: (List<Int>) -> Unit,
    dotColor: Color = AegisBorder,
    activeColor: Color = AegisCyan,
) {
    // Square side in px, captured from layout (not the draw phase).
    var side by remember { mutableFloatStateOf(0f) }
    var selected by remember { mutableStateOf<List<Int>>(emptyList()) }
    var dragPos by remember { mutableStateOf<Offset?>(null) }

    fun center(index: Int): Offset {
        val cell = side / 3f
        return Offset(cell * (index % 3) + cell / 2f, cell * (index / 3) + cell / 2f)
    }

    // Append the dot under [pos] to the path if it's a new one.
    fun consider(pos: Offset) {
        if (side <= 0f) return
        val cell = side / 3f
        val hitR = cell / 3f
        for (i in 0 until 9) {
            if (i in selected) continue
            val c = center(i)
            val dx = pos.x - c.x
            val dy = pos.y - c.y
            if (dx * dx + dy * dy <= hitR * hitR) {
                selected = selected + i
                return
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onSizeChanged { side = minOf(it.width, it.height).toFloat() }
            .pointerInput(Unit) {
                // Claim the gesture at the source. This grid lives inside
                // the tutorial's verticalScroll (and HorizontalPager); with
                // detectDragGestures the scroll won the touch-slop race and
                // the page scrolled instead of drawing — the pattern was
                // un-drawable (user-reported 2026-06-07, same class as the
                // scratch-to-reveal bug). awaitEachGesture + consuming the
                // down and every move makes this surface win the pointer
                // before either ancestor can start.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    selected = emptyList()
                    dragPos = down.position
                    consider(down.position)
                    down.consume()
                    var active = true
                    while (active) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            if (selected.size >= 2) onPattern(selected)
                            selected = emptyList()
                            dragPos = null
                            active = false
                        } else {
                            dragPos = change.position
                            consider(change.position)
                            change.consume()
                        }
                    }
                }
            }
            .drawBehind {
                val radius = side / 18f
                // Connecting lines between consecutive selected dots.
                for (i in 0 until selected.size - 1) {
                    drawLine(
                        color = activeColor,
                        start = center(selected[i]),
                        end = center(selected[i + 1]),
                        strokeWidth = radius / 2f,
                    )
                }
                // Trailing line from the last selected dot to the finger.
                val last = selected.lastOrNull()
                val pos = dragPos
                if (last != null && pos != null) {
                    drawLine(
                        color = activeColor.copy(alpha = 0.6f),
                        start = center(last),
                        end = pos,
                        strokeWidth = radius / 2f,
                    )
                }
                // The nine dots — filled + larger when part of the path.
                for (i in 0 until 9) {
                    val on = i in selected
                    drawCircle(
                        color = if (on) activeColor else dotColor,
                        radius = if (on) radius * 1.4f else radius,
                        center = center(i),
                    )
                }
            },
    )
}

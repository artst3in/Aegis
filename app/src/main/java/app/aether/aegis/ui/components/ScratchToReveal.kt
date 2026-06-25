package app.aether.aegis.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.aether.aegis.ui.theme.AegisCyan

/**
 * A lottery-scratch-card reveal. [content] — the
 * sensitive thing, e.g. the 24-word recovery phrase — is hidden under an
 * opaque cover the user must physically scratch off with a finger before
 * it shows. Nothing is revealed by a glance, a screenshot of the closed
 * state, or a shoulder-surfer until the owner deliberately drags across
 * it.
 *
 * Why a scratcher and not a tap-to-reveal: a tap is an accident waiting
 * to happen (a stray touch exposes the master key). Dragging enough
 * surface area to clear the threshold is a deliberate, sustained gesture
 * — the user has unmistakably *chosen* to expose it, ideally after
 * heeding the warning above it and checking no one is watching. Combined
 * with the app's default `FLAG_SECURE`, the phrase never leaves the
 * owner's own eyes.
 *
 * Implementation: the cover is drawn into an offscreen compositing layer
 * so finger strokes painted with [BlendMode.Clear] punch genuine
 * transparent holes through it, showing [content] beneath. Reveal
 * progress is tracked by how many cells of a coarse grid the finger has
 * touched; once [revealFraction] of them are scratched the remaining
 * cover fades away and [onRevealed] fires once.
 *
 * @param onRevealed invoked exactly once when the threshold is crossed.
 * @param revealFraction fraction of grid cells (0..1) that must be
 *        scratched before the cover auto-clears. Default 0.45 — enough to
 *        prove intent without forcing the user to scrub every pixel.
 */
@Composable
fun ScratchToReveal(
    modifier: Modifier = Modifier,
    revealFraction: Float = 0.45f,
    onRevealed: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    // Scratch stroke points, in local px. Drawn with BlendMode.Clear.
    val points = remember { mutableStateListOf<Offset>() }
    // Coarse grid of "touched" cells → cheap coverage estimate. Stored as
    // packed (col * GRID_ROWS + row) ints so we can count distinct cells.
    val touchedCells = remember { mutableStateListOf<Int>() }
    var revealed by remember { mutableStateOf(false) }

    // Cover fades out once revealed so the transition reads as "scratched
    // clean" rather than a hard pop.
    val coverAlpha by animateFloatAsState(
        targetValue = if (revealed) 0f else 1f,
        animationSpec = tween(durationMillis = 320),
        label = "scratchCoverAlpha",
    )

    Box(modifier = modifier) {
        // The protected content sits underneath, always composed; the
        // cover is what gates visibility.
        content()

        if (coverAlpha > 0f) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    // matchParentSize, NOT fillMaxSize: this Box lives inside
                    // the tutorial's verticalScroll, where the incoming max
                    // height is infinite. fillMaxHeight against an infinite
                    // constraint collapses the cover Canvas to 0 px, so the
                    // opaque cover never draws and the recovery words show
                    // instantly, unhidden (user-reported 2026-06-07).
                    // matchParentSize sizes the overlay to the measured
                    // content() beneath it regardless of the parent's
                    // constraints.
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = coverAlpha
                        // Offscreen so BlendMode.Clear erases the cover
                        // layer itself, not whatever is on the screen.
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .pointerInput(revealed) {
                        if (revealed) return@pointerInput
                        // Claim the gesture at the source. The scratch
                        // surface lives inside a HorizontalPager (the
                        // tutorial) AND a verticalScroll (the page body),
                        // both of which run their own drag detectors. With
                        // detectDragGestures the scratch LOST the
                        // touch-slop race — a finger drag swiped the page
                        // or scrolled the column instead of scratching, so
                        // the phrase never revealed (user-reported
                        // 2026-06-07). awaitEachGesture + consuming the
                        // down and every move immediately (no slop wait)
                        // makes this surface win the pointer before either
                        // ancestor can start, in any drag direction.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            registerScratch(down.position, size.width, size.height, points, touchedCells)
                            down.consume()
                            var active = true
                            while (active) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    active = false
                                } else {
                                    registerScratch(
                                        change.position, size.width, size.height,
                                        points, touchedCells,
                                    )
                                    change.consume()
                                    val total = (GRID_COLS * GRID_ROWS).toFloat()
                                    if (!revealed &&
                                        touchedCells.distinct().size / total >= revealFraction
                                    ) {
                                        revealed = true
                                        onRevealed()
                                        active = false
                                    }
                                }
                            }
                        }
                    },
            ) {
                // 1) Opaque cover.
                drawRect(color = COVER)
                // 2) A faint hint pattern so it reads as "scratch me".
                //    Diagonal cyan ticks, low alpha.
                val step = 28.dp.toPx()
                var x = 0f
                while (x < size.width + size.height) {
                    drawLine(
                        color = AegisCyan.copy(alpha = 0.10f),
                        start = Offset(x, 0f),
                        end = Offset(x - size.height, size.height),
                        strokeWidth = 2f,
                    )
                    x += step
                }
                // 3) Punch the scratched path through with Clear.
                if (points.size >= 2) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                    }
                    drawPath(
                        path = path,
                        color = Color.Transparent,
                        style = Stroke(width = 56f, cap = StrokeCap.Round),
                        blendMode = BlendMode.Clear,
                    )
                }
            }
        }
    }
}

/** Cover colour — a near-opaque slate so the hint ticks read but the
 *  content beneath does not bleed through. */
private val COVER = Color(0xFF1B2530)

private const val GRID_COLS = 14
private const val GRID_ROWS = 8

/** Record a scratch sample: add the point for the Clear-path and mark the
 *  grid cell it falls in for coverage accounting. (The old
 *  `Modifier.matchContentSize()` fillMaxSize helper was removed — it
 *  collapsed to 0 px under the tutorial's unbounded-height scroll; the
 *  cover now uses BoxScope.matchParentSize instead.) */
private fun registerScratch(
    pos: Offset,
    width: Int,
    height: Int,
    points: SnapshotStateList<Offset>,
    touchedCells: SnapshotStateList<Int>,
) {
    if (width <= 0 || height <= 0) return
    points.add(pos)
    val col = ((pos.x / width) * GRID_COLS).toInt().coerceIn(0, GRID_COLS - 1)
    val row = ((pos.y / height) * GRID_ROWS).toInt().coerceIn(0, GRID_ROWS - 1)
    val cell = col * GRID_ROWS + row
    if (cell !in touchedCells) touchedCells.add(cell)
}

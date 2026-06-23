package app.aether.aegis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.aegis.ui.theme.AegisCyan
import app.aether.aegis.ui.theme.AegisCyanGlow
import app.aether.aegis.ui.theme.AegisOnSurfaceDim
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Skill-tree visual.
 *
 * Nodes are flat-top hexes placed on axial (q, r). Dependency edges
 * are continuous polylines that trace the honeycomb's walls: each
 * intermediate hex contributes a perimeter arc between its entry and
 * exit walls (shorter side; CCW on ties), so the path reads as a
 * single circuit-trace rather than a corridor or a midpoint
 * shortcut.
 *
 * Earlier PIL/React attempts kept failing — 1px wall gaps, double
 * walls, midpoint cut-throughs. Compose's float-precision Path with
 * miter joins draws the polyline cleanly because consecutive hex
 * vertices are exact float matches at shared corners.
 *
 * Behind everything sits a ghost grid of empty hex outlines so the
 * cells the path traverses are visible context. Below the GROUND
 * line: Device Owner (NG+, not toggleable in-app).
 */
@Composable
fun SkillTreeView(
    nodes: List<SkillNode>,
    edges: List<Pair<String, String>>,
    onNodeTap: (SkillNode) -> Unit,
    modifier: Modifier = Modifier,
    hexRadiusDp: Dp = 22.dp,
    groundAxialR: Float = 1.5f,
    // Optional per-edge routing waypoint (axial q,r). When an edge is
    // present here, its polyline is traced parent → via → child instead
    // of the straight cube-line, so it can bend deliberately. Used to
    // lead SOS Drill's branch out App PIN's upper-left and down,
    // rather than the straight diagonal that hugs Device Admin
    // (a marked route to lead the polyline there).
    routeVia: Map<Pair<String, String>, Pair<Int, Int>> = emptyMap(),
) {
    val density = LocalDensity.current
    val hexRadius = with(density) { hexRadiusDp.toPx() }
    val hexDiameter = hexRadiusDp * 2

    val nodeById = nodes.associateBy { it.id }

    // Pre-compute polylines for every dependency edge. Memoised so
    // we don't redo cube-line + perimeter trace on every
    // recomposition.
    //
    // History: 467 swapped this for straight perimeter-to-perimeter
    // lines after the user reported sibling vertices bleeding into
    // the path ("sos going through duress"). Restored in this
    // commit because the user prefers the hex-wall circuit-trace
    // look. If specific edges sit on top of a sibling vertex with
    // the current layout, fix by nudging the offending node a cell
    // rather than reverting the rendering — the aesthetic is the
    // point.
    val edgePolylines = remember(nodes, edges, routeVia, hexRadius) {
        edges.mapNotNull { (a, b) ->
            val na = nodeById[a] ?: return@mapNotNull null
            val nb = nodeById[b] ?: return@mapNotNull null
            val via = routeVia[a to b]
            val cells = if (via != null) {
                // Bent route: parent → via → child. hexLine guarantees
                // adjacency within each leg; drop(1) dedupes the shared
                // via cell so cellPathPolyline sees one continuous
                // adjacent sequence and traces the corner at `via`.
                val viaAxial = Axial(via.first, via.second)
                hexLine(Axial(na.q, na.r), viaAxial) +
                    hexLine(viaAxial, Axial(nb.q, nb.r)).drop(1)
            } else {
                hexLine(Axial(na.q, na.r), Axial(nb.q, nb.r))
            }
            val polyline = cellPathPolyline(cells, hexRadius)
            Triple(na, nb, polyline)
        }
    }

    // Bounds in pixel space — compute from what's actually drawn
    // (node centres + every polyline vertex), not from a rectangle
    // of every cell in [qMin..qMax]×[rMin..rMax]. Iterating cells
    // makes the canvas swell at the corners because q and r are
    // coupled in the y formula: cells far from any node land at
    // extreme y values and waste 100s of dp.
    val nodePoints = nodes.map { axialToPixel(Axial(it.q, it.r), hexRadius) }
    val polyPoints = edgePolylines.flatMap { it.third }
    val allPoints = nodePoints + polyPoints
    val pad = hexRadius * 1.2f
    val minX = (allPoints.minOfOrNull { it.x } ?: 0f) - pad
    val maxX = (allPoints.maxOfOrNull { it.x } ?: 0f) + pad
    val minY = (allPoints.minOfOrNull { it.y } ?: 0f) - pad
    val maxY = (allPoints.maxOfOrNull { it.y } ?: 0f) + pad
    val originX = -minX
    val originY = -minY
    val widthPx = maxX - minX
    val heightPx = maxY - minY
    val widthDp = with(density) { widthPx.toDp() }
    val heightDp = with(density) { heightPx.toDp() }

    // Offset every pre-computed polyline into canvas-local
    // coordinates (origin at the top-left of the box).
    val offsetEdges = remember(edgePolylines, originX, originY) {
        edgePolylines.map { (na, nb, poly) ->
            Triple(na, nb, poly.map { Offset(it.x + originX, it.y + originY) })
        }
    }

    Box(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .width(widthDp)
            .height(heightDp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 1. Ground line — horizontal across the canvas at the
            //    axial-r row given by [groundAxialR]. Device Owner
            //    sits below this line; everything else above.
            //
            //    No ghost honeycomb drawn here: LunaGlass already
            //    paints a global hex grid behind every screen, and a
            //    second layer on top of that just muddied the
            //    contrast. The polyline still traces real lattice
            //    walls — they line up with the backdrop's hexes.
            val groundY = hexRadius * sqrt(3f) * groundAxialR + originY
            drawLine(
                color = AegisOnSurfaceDim.copy(alpha = 0.4f),
                start = Offset(0f, groundY),
                end = Offset(widthPx, groundY),
                strokeWidth = 1f,
            )

            // 2. Dependency paths. Each polyline is drawn as one Path
            //    with miter joins so the 120° hex-vertex turns stay
            //    sharp.
            for ((na, nb, polyline) in offsetEdges) {
                if (polyline.size < 2) continue
                val color = pathColour(na, nb)
                val path = Path().apply {
                    moveTo(polyline[0].x, polyline[0].y)
                    for (i in 1 until polyline.size) {
                        lineTo(polyline[i].x, polyline[i].y)
                    }
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = 2f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Miter,
                        miter = 4f,
                    ),
                )
            }
        }

        // 3. Node hexes as positioned HexShape composables — they
        //    handle their own tap target, glow, and label. Placed at
        //    absolute pixel offsets relative to the box's top-left
        //    so the centres line up exactly with the axial grid.
        for (node in nodes) {
            val centre = axialToPixel(Axial(node.q, node.r), hexRadius)
            val topLeftX = centre.x + originX - hexRadius
            val topLeftY = centre.y + originY - hexRadius
            Box(
                modifier = Modifier.offset {
                    IntOffset(topLeftX.roundToInt(), topLeftY.roundToInt())
                },
            ) {
                val border = when {
                    node.locked  -> AegisOnSurfaceDim.copy(alpha = 0.4f)
                    node.active  -> AegisCyan
                    else         -> AegisOnSurfaceDim
                }
                val fill = when {
                    // Locked → dark scrim so the hex reads as switched-OFF and
                    // clearly NOT tappable. A transparent fill made locked nodes
                    // (e.g. Device Owner) look identical to available ones —
                    // they appeared tappable when they aren't (user report).
                    node.locked  -> Color.Black.copy(alpha = 0.45f)
                    node.active  -> AegisCyanGlow.copy(alpha = 0.6f)
                    else         -> Color.Transparent
                }
                val labelColor = when {
                    node.locked  -> AegisOnSurfaceDim.copy(alpha = 0.5f)
                    node.active  -> AegisCyan
                    else         -> AegisOnSurfaceDim
                }
                val tapHandler: (() -> Unit)? =
                    if (node.locked && !node.tapWhenLocked) null
                    else { { onNodeTap(node) } }
                HexShape(
                    size = hexDiameter,
                    borderColor = border,
                    fillColor = fill,
                    glow = node.active && !node.locked,
                    glowColor = AegisCyanGlow,
                    breathing = node.pulse,
                    onClick = tapHandler,
                ) {
                    // Locked nodes show a small LunaGlass padlock ABOVE the
                    // label (not instead of it) so the node reads as locked AND
                    // you can still see WHAT it is / what's gated behind what
                    // (user report: a bare padlock hid which node was which).
                    if (node.locked) {
                        androidx.compose.foundation.layout.Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        ) {
                            AegisIcon(
                                AegisIcons.Lock,
                                contentDescription = "locked",
                                tint = AegisOnSurfaceDim.copy(alpha = 0.7f),
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                text = node.label.replace(' ', '\n'),
                                color = labelColor,
                                fontSize = 7.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                lineHeight = 8.sp,
                            )
                        }
                    } else {
                        Text(
                            text = node.label.replace(' ', '\n'),
                            color = labelColor,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            lineHeight = 9.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One vertex in the skill tree.
 *
 * @param id           Stable key — used by [edges] to address this node.
 * @param label        Short text drawn inside the hex; spaces become
 *                     line breaks so multi-word names wrap.
 * @param q, r         Axial flat-top coordinates of this node's cell.
 * @param active       Lit, configured-and-armed.
 * @param locked       Gated by a parent that isn't satisfied yet.
 *                     Locked nodes render dim and (by default) ignore
 *                     taps.
 * @param pulse        Slow breathing glow — used on the "start here"
 *                     hex when the tree is otherwise dark.
 * @param tapWhenLocked  Let a locked hex still respond to taps so the
 *                     user can read the "set X first" detail screen.
 * @param route        Navigation target on tap.
 */
data class SkillNode(
    val id: String,
    val label: String,
    val q: Int,
    val r: Int,
    val active: Boolean = false,
    val locked: Boolean = false,
    val pulse: Boolean = false,
    val tapWhenLocked: Boolean = false,
    val route: String = "",
)

private fun pathColour(a: SkillNode, b: SkillNode): Color = when {
    // Either endpoint locked → the path is GATED but still visible
    // so the tree reads as connected. Dim cyan, not dim grey — at
    // 25 % grey the line vanished against the LunaGlass backdrop
    // and SOS Drill looked disconnected from its parent.
    a.locked || b.locked     -> AegisCyan.copy(alpha = 0.25f)
    // Both endpoints lit → full cyan circuit trace.
    a.active && b.active     -> AegisCyan.copy(alpha = 0.9f)
    // At least one endpoint accessible but the other not yet
    // configured → half-bright so the user can see what's reachable.
    else                     -> AegisCyan.copy(alpha = 0.4f)
}

// ───────────────────────── hex maths ─────────────────────────

private data class Axial(val q: Int, val r: Int)

/** Pixel centre of an axial flat-top hex cell. Flat-top means rows
 *  are slightly staggered horizontally; `radius` is centre-to-vertex
 *  in pixels. */
private fun axialToPixel(a: Axial, radius: Float): Offset {
    val x = radius * 1.5f * a.q
    val y = radius * sqrt(3f) * (a.r + a.q / 2f)
    return Offset(x, y)
}

/**
 * The six axial neighbour offsets, ordered so index `i` matches the
 * hex edge between vertices `i` and `(i + 1) % 6` (vertex 0 at 0°,
 * increasing every 60°). HexShape uses the same vertex
 * ordering, so directions and edges share one number.
 *
 *   0 → edge 0 (verts 0,1) — lower-right (midpoint 30°)
 *   1 → edge 1 (verts 1,2) — bottom      (90°)
 *   2 → edge 2 (verts 2,3) — lower-left  (150°)
 *   3 → edge 3 (verts 3,4) — upper-left  (210°)
 *   4 → edge 4 (verts 4,5) — top         (270°)
 *   5 → edge 5 (verts 5,0) — upper-right (330°)
 */
private val DIRECTIONS = arrayOf(
    Axial( 1,  0),
    Axial( 0,  1),
    Axial(-1,  1),
    Axial(-1,  0),
    Axial( 0, -1),
    Axial( 1, -1),
)

private fun hexVertex(centre: Offset, radius: Float, vertIdx: Int): Offset {
    val angle = (PI / 3.0) * vertIdx
    return Offset(
        centre.x + (radius * cos(angle)).toFloat(),
        centre.y + (radius * sin(angle)).toFloat(),
    )
}

/** Index of [from]'s edge that faces [to]. Caller guarantees the
 *  cells are axially adjacent (hexLine does). */
private fun edgeIndexTo(from: Axial, to: Axial): Int {
    val dq = to.q - from.q
    val dr = to.r - from.r
    for (i in 0..5) {
        if (DIRECTIONS[i].q == dq && DIRECTIONS[i].r == dr) return i
    }
    error("hex cells $from and $to are not adjacent (dq=$dq, dr=$dr)")
}

/** Cube-round preserving x + y + z = 0 — drop the coord with the
 *  largest rounding delta and reconstruct it (Red Blob Games). */
private fun cubeRound(x: Float, y: Float, z: Float): Triple<Int, Int, Int> {
    var rx = x.roundToInt()
    var ry = y.roundToInt()
    var rz = z.roundToInt()
    val xd = abs(rx - x)
    val yd = abs(ry - y)
    val zd = abs(rz - z)
    when {
        xd > yd && xd > zd -> rx = -ry - rz
        yd > zd            -> ry = -rx - rz
        else               -> rz = -rx - ry
    }
    return Triple(rx, ry, rz)
}

/** Cube-line algorithm — sequence of axial cells along the straight
 *  line from [a] to [b]. Consecutive cells are always hex-adjacent. */
private fun hexLine(a: Axial, b: Axial): List<Axial> {
    val ax = a.q.toFloat()
    val ay = (-a.q - a.r).toFloat()
    val az = a.r.toFloat()
    val bx = b.q.toFloat()
    val by = (-b.q - b.r).toFloat()
    val bz = b.r.toFloat()
    val n = max(max(abs(bx - ax), abs(by - ay)), abs(bz - az)).toInt()
    if (n == 0) return listOf(a)
    return (0..n).map { i ->
        val t = i.toFloat() / n
        val (cx, _, cz) = cubeRound(
            ax + (bx - ax) * t,
            ay + (by - ay) * t,
            az + (bz - az) * t,
        )
        Axial(cx, cz)
    }
}

/**
 * Build the polyline that traces honeycomb walls from cells[0] to
 * cells[last]. Algorithm:
 *   - Pick a starting vertex on wall(cells[0], cells[1]).
 *   - For each intermediate cell C_i:
 *       translate the current vertex into C_i's frame; find the
 *       exit wall facing C_{i+1}; walk C_i's perimeter to one of
 *       the exit wall's vertices, taking the shorter arc (CCW on
 *       tie). Each perimeter step is exactly one hex wall.
 *   - The last polyline point sits on wall(cells[last-1], end),
 *     which is also a vertex of the end node's hex.
 *
 * Every segment IS one wall of the honeycomb — float-precise
 * vertex reuse at shared corners means no gaps. PIL versions
 * couldn't pull this off because integer pixel rounding broke
 * continuity; Compose Paths reuse the same Offset.
 */
private fun cellPathPolyline(cells: List<Axial>, hexRadius: Float): List<Offset> {
    if (cells.size < 2) return emptyList()
    val out = mutableListOf<Offset>()

    val firstExitEdge = edgeIndexTo(cells[0], cells[1])
    val candA = firstExitEdge
    val candB = (firstExitEdge + 1) % 6
    val startCentre = axialToPixel(cells[0], hexRadius)
    val endCentre = axialToPixel(cells.last(), hexRadius)
    // Pick the starting vertex closer to the end so the polyline
    // tends to flow toward the goal instead of zigzagging back.
    val candAPos = hexVertex(startCentre, hexRadius, candA)
    val candBPos = hexVertex(startCentre, hexRadius, candB)
    var currentVertIdx = if (
        hypot((candAPos.x - endCentre.x).toDouble(), (candAPos.y - endCentre.y).toDouble()) <
        hypot((candBPos.x - endCentre.x).toDouble(), (candBPos.y - endCentre.y).toDouble())
    ) candA else candB

    out.add(hexVertex(startCentre, hexRadius, currentVertIdx))

    if (cells.size == 2) {
        // Direct neighbours — path IS the single shared wall.
        val other = if (currentVertIdx == candA) candB else candA
        out.add(hexVertex(startCentre, hexRadius, other))
        return out
    }

    for (i in 1 until cells.size - 1) {
        val prevCell = cells[i - 1]
        val cur = cells[i]
        val nextCell = cells[i + 1]
        val curCentre = axialToPixel(cur, hexRadius)

        // Re-index currentVertIdx from prevCell's frame into curCell's.
        // Adjacent hexes share two corners with vertex indices related
        // by the +3 / +4 offset:
        //   prev's vert (e)       ↔ cur's vert (e + 4) % 6
        //   prev's vert (e + 1)%6 ↔ cur's vert (e + 3) % 6
        val ePrev = edgeIndexTo(prevCell, cur)
        currentVertIdx = when (currentVertIdx) {
            ePrev                 -> (ePrev + 4) % 6
            (ePrev + 1) % 6       -> (ePrev + 3) % 6
            else -> error(
                "vertex $currentVertIdx is not on the wall (edge $ePrev) " +
                    "between $prevCell and $cur"
            )
        }

        val exitEdge = edgeIndexTo(cur, nextCell)
        val exitV1 = exitEdge
        val exitV2 = (exitEdge + 1) % 6

        // Shortest perimeter walk from currentVertIdx to either exit
        // vertex. Tie → CCW (consistent side, so opposite-edge
        // through-cells always swing the same way).
        var bestSteps = 7
        var bestTarget = currentVertIdx
        var bestCcw = true
        for (target in intArrayOf(exitV1, exitV2)) {
            val ccw = (target - currentVertIdx + 6) % 6
            val cw  = (currentVertIdx - target + 6) % 6
            if (ccw < bestSteps) {
                bestSteps = ccw; bestTarget = target; bestCcw = true
            }
            if (cw < bestSteps) {
                bestSteps = cw; bestTarget = target; bestCcw = false
            }
        }

        var v = currentVertIdx
        for (k in 1..bestSteps) {
            v = if (bestCcw) (v + 1) % 6 else (v + 5) % 6
            out.add(hexVertex(curCentre, hexRadius, v))
        }
        currentVertIdx = bestTarget
    }

    return out
}

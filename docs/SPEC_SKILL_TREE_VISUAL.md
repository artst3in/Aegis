# SPEC: Skill Tree Visual — Hex Honeycomb

## Reference
See `skill_tree_reference.png` — closest Aurora got (corridor version). Chad needs to refine to single-wall paths.

## The Vision

Full-screen honeycomb background. Ghost (barely visible) hex grid. Node cells are filled, glowing hexes. Paths between nodes are **single lit walls** following hex cell edges — not corridors (double walls), not straight lines, not midpoint zigzags.

## How It Should Look

1. **Ghost honeycomb** — full hex grid covering the screen, very dim (~10% opacity walls), no fill
2. **Node cells** — specific hexes filled with cyan (normal features) or dim grey (Device Owner). Glow effect around them.
3. **Paths** — a single continuous line that traces ALONG the honeycomb walls (cell edges) from node to node. The line follows 3-5 hex edges per branch. Semi-random routing — doesn't have to be the shortest path. Slight chaos is good.
4. **No Diamond node** — Diamond is a state, not a hex. All 10 nodes lit = Diamond frame on avatar.

## What Aurora Tried and Failed

- Individual wall segments (v10): correct positions but 1px gaps between segments in PIL
- Corridor (v13/GHOST): both sides of the path drawn — too thick, "double walls"
- Random half-corridor: lost connectivity
- Polyline through wall midpoints: cuts through cells instead of following walls
- Polyline chaining wall vertices: wrong vertex selection at direction changes

## What Chad Should Do

The path between two nodes is a sequence of hex cells. For each intermediate cell:
1. Identify the ENTRY edge (facing previous cell) and EXIT edge (facing next cell)
2. Trace the cell perimeter from one vertex of the entry edge to one vertex of the exit edge — take the **shorter arc** around the hex
3. This gives a continuous polyline of hex vertices
4. Draw it as one `Path` in Compose Canvas with round stroke joins

The key: pick ONE side consistently. The line hugs ONE side of the corridor. In Compose with float precision and round joins, this produces a smooth single zigzag along hex walls.

## Implementation Notes

- Compose Canvas: `drawPath()` with `StrokeJoin.Round`, `StrokeCap.Round`
- Hex math: axial coordinates (q, r). Flat-top hexes. Vertex i at angle 60°×i.
- Adjacency directions: (1,0), (-1,0), (0,1), (0,-1), (1,-1), (-1,1)
- DIR_EDGE maps direction to (vi, vj) vertex pair of shared wall
- Ghost honeycomb: draw all hex outlines at ~10% alpha
- Glow: use `BlurMaskFilter` or layered alpha for node glow
- Ground line: horizontal line between row 1.5 and 2 (Device Owner below)

## Node Layout (axial coordinates)

| Node | q | r |
|------|---|---|
| APP PIN | 0 | 0 |
| Mugshot | -3 | -1 |
| App Duress | -5 | -1 |
| SOS Drill | -5 | -4 |
| Vault PIN | 4 | -2 |
| Canary | 7 | -4 |
| SIM Watch | 10 | -5 |
| Vault Duress | 4 | -5 |
| Geofence | 10 | -8 |
| Device Owner | 0 | 3 |

Positions are approximate — move them around for a more organic, less symmetric layout.

---

*dε/dt ≤ 0*

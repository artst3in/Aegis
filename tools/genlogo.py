#!/usr/bin/env python3
"""
Aegis logo generator — the single source of truth for the brand mark.

WHY THIS EXISTS
---------------
The Aegis mark is a honeycomb of flat-top hexagons forming a
wide-shouldered SHIELD. It only ever lived as exported PNGs, so every
tweak meant hand-editing pixels and hoping — which is how a one-line
colour change turns into a nine-round saga. This script regenerates the
whole mark from the underlying hex math, so a future change is a single
constant edit + a re-run, and every hex stays pixel-perfect.

The geometry below was reverse-engineered from the original 432px
foreground (connected-component analysis to find the 19 hex centres,
then solving the lattice). DO NOT eyeball new positions — add them on
the lattice (col, k) grid and the placement stays exact.

THE DESIGN (Artur, 2026-06-06)
------------------------------
- Shape: a pointy-top hexagon of flat-top unit hexes (columns 3-4-5-4-3)
  PLUS two "shoulder" hexes at the top of the outer columns
  [(-2,-2) and (2,-2)], which raise the outer columns to peak height and
  turn the diamond into a shield that tapers to the bottom point.

- Colour: ONE brand colour — LunaGlass cyan #00FFFF — everywhere. It is
  NOT darkened per ring. Instead each ring steps DOWN in OPACITY, so the
  mark reads as a force-field shield: the centre is fully opaque and
  "impenetrable", the outer rings are translucent and you can see through
  them (on a transparent background the wallpaper shows through the outer
  shield — the weakest part of the field). This is why we use alpha, not
  a darker cyan: alpha keeps the single brand hue and earns the
  see-through metaphor; a darker cyan would just look muddy and lose it.

- Borders: each hex is rimmed with the OPACITY of the ring one step
  INWARD (toward the centre), so the rims cascade brighter as they near
  the core. The centre keeps its own (full) opacity as a border rather
  than no border at all — without that the centre hex renders a touch
  smaller than its bordered neighbours and the gap around it blows out.

GEOMETRY (432-space; scaled per output size)
--------------------------------------------
Flat-top hex lattice. Vertical neighbour step DYV = sqrt(3)*R; column
step DX = 1.5*R; odd columns offset DYV/2. Drawn radius is 90% of the
lattice radius, leaving the thin gaps between hexes.

USAGE
-----
  python3 tools/genlogo.py            # regenerate all app assets
  python3 tools/genlogo.py --preview  # write previews to /tmp instead
"""

import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

# --- Lattice constants, measured from the original 432px mark ----------
BASE = 432.0      # reference canvas; everything scales from here
DX_B = 53.8       # column horizontal step  (1.5 * R)
DYV_B = 62.0      # vertical neighbour step  (sqrt(3) * R)
DYOFF_B = 31.0    # odd-column vertical offset (DYV / 2)
RLAT_B = 35.8     # lattice circumradius (DYV / sqrt(3))
GAP = 0.90        # drawn radius as a fraction of RLAT -> thin gaps

# Uniform shrink toward centre so the SHIELD fits the adaptive-icon safe
# zone. The original honeycomb fit the launcher only because its top
# corners were EMPTY; the shield fills those corners, so a circular icon
# mask would clip the shoulders (Artur 2026-06-06: "doesn't fit, reduce
# it a tiny bit"). The adaptive safe zone is a 66dp circle inside the
# 108dp canvas -> radius 0.611 of the half-canvas (132px in 432-space).
# The shoulder vertices reach ~190px at SCALE=1.0, so we pull everything
# in to keep them inside that circle. Verify with `--preview` (the
# keyline circle is drawn on the dark preview).
SCALE = 0.68

# --- Force-field opacity ramp (single hue, alpha steps down by ring) ---
# Clean even thirds (Artur 2026-06-06): each ring sheds a third of the
# opacity, and "outside" is the natural 0% -> 100% / 66% / 33% / 0%. Feels
# principled and reads as energy falling off from the core. In bytes:
# 255 / 168 / 84. The outer fills go faint on a near-black splash, but the
# cascade borders below keep the shield's shape legible, and over a
# wallpaper (transparent launcher bg) the see-through is the whole point.
BRAND = (0, 255, 255)          # LunaGlass cyan #00FFFF — the only colour
FILL_ALPHA = {0: 255, 1: 168, 2: 84}    # 100% / 66% / 33%
# Border opacity = the FILL opacity of the ring one step inward. The
# centre borders itself at full opacity so it merges into one solid hex.
BORDER_ALPHA = {0: 255, 1: 255, 2: 168}

# (col, k) -> ring index (distance from centre). The two shoulders
# (-2,-2)/(2,-2) are the shield additions.
HEXES = {
    (0, 0): 0,
    (0, -1): 1, (0, 1): 1, (-1, -1): 1, (-1, 0): 1, (1, -1): 1, (1, 0): 1,
    (0, -2): 2, (0, 2): 2, (-1, -2): 2, (-1, 1): 2, (1, -2): 2, (1, 1): 2,
    (-2, -1): 2, (-2, 0): 2, (-2, 1): 2, (2, -1): 2, (2, 0): 2, (2, 1): 2,
    (-2, -2): 2, (2, -2): 2,   # <-- shield shoulders (top-left / top-right)
}

# Output targets, relative to the res/ dir. The foreground is the in-app
# brand mark (splash / lock / settings) AND the adaptive launcher
# foreground; the legacy ic_aegis.png is the pre-API-26 launcher icon +
# widget preview, needed at every density.
FOREGROUND = ("mipmap-xxxhdpi/ic_aegis_foreground.png", 432)
LEGACY = [
    ("mipmap-mdpi/ic_aegis.png", 48),
    ("mipmap-hdpi/ic_aegis.png", 72),
    ("mipmap-xhdpi/ic_aegis.png", 96),
    ("mipmap-xxhdpi/ic_aegis.png", 144),
    ("mipmap-xxxhdpi/ic_aegis.png", 192),
]


def _hex_points(cx, cy, r):
    """Six vertices of a FLAT-TOP hexagon (vertices on the left/right
    horizontal axis, flat edges top and bottom)."""
    return [
        (cx + r * math.cos(math.radians(60 * i)),
         cy + r * math.sin(math.radians(60 * i)))
        for i in range(6)
    ]


def render(size, ss=4):
    """Render the mark to an RGBA image of `size`x`size`.

    Drawn at `ss`x supersampling and downsampled with LANCZOS for clean
    anti-aliased edges at any density. Background stays transparent —
    the force-field rings are meant to let whatever is behind show
    through.
    """
    S = size * ss
    sc = (S / BASE) * SCALE
    DX, DYV, DYOFF, RLAT = DX_B * sc, DYV_B * sc, DYOFF_B * sc, RLAT_B * sc
    cx = cy = S / 2.0
    rdraw = RLAT * GAP

    # Soft glow layer (blurred copy of the fills) sits under the bodies
    # so the mark has the LunaGlass halo rather than hard flat shapes.
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(glow)
    body = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    bdraw = ImageDraw.Draw(body)
    bw = max(2, int(2.4 * sc))

    for (col, k), ring in HEXES.items():
        x = cx + col * DX
        y = cy + k * DYV + (DYOFF if col % 2 != 0 else 0.0)
        poly = _hex_points(x, y, rdraw)
        r, g, b = BRAND
        gdraw.polygon(poly, fill=(r, g, b, FILL_ALPHA[ring]))
        bdraw.polygon(poly, fill=(r, g, b, FILL_ALPHA[ring]))
        # Border = same hue, opacity of the ring one step inward.
        bdraw.line(poly + [poly[0]], fill=(r, g, b, BORDER_ALPHA[ring]),
                   width=bw)

    glow = glow.filter(ImageFilter.GaussianBlur(radius=6 * ss))
    glow.putalpha(glow.split()[3].point(lambda a: int(a * 0.50)))
    out = Image.alpha_composite(glow, body)
    return out.resize((size, size), Image.LANCZOS)


def _res_dir():
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.normpath(os.path.join(here, "..", "app", "src", "main", "res"))


def save_all():
    res = _res_dir()
    path, sz = FOREGROUND
    render(sz).save(os.path.join(res, path))
    print("wrote", path)
    for path, sz in LEGACY:
        render(sz).save(os.path.join(res, path))
        print("wrote", path)


def save_previews():
    """Dump previews to /tmp, composited on dark AND on a light/checker
    background so the see-through rings are visible (judging a
    transparent mark on the wrong background is how you 'fix' problems
    that aren't real)."""
    img = render(432)
    img.save("/tmp/logo_preview.png")
    dark = Image.new("RGBA", img.size, (8, 10, 15, 255))
    comp = Image.alpha_composite(dark, img).convert("RGB")
    # Draw the adaptive-icon 66dp safe-zone circle (radius 132px in
    # 432-space). Everything important must sit INSIDE this for a circular
    # launcher mask not to clip it.
    dd = ImageDraw.Draw(comp)
    cx = cy = 216
    rsafe = 132
    dd.ellipse([cx - rsafe, cy - rsafe, cx + rsafe, cy + rsafe],
               outline=(255, 80, 80), width=2)
    comp.save("/tmp/logo_on_dark.png")
    # checker to show transparency
    chk = Image.new("RGBA", img.size, (60, 60, 70, 255))
    d = ImageDraw.Draw(chk)
    c = 24
    for yy in range(0, img.size[1], c):
        for xx in range(0, img.size[0], c):
            if (xx // c + yy // c) % 2 == 0:
                d.rectangle([xx, yy, xx + c, yy + c], fill=(110, 110, 125, 255))
    Image.alpha_composite(chk, img).convert("RGB").save("/tmp/logo_on_checker.png")
    print("previews -> /tmp/logo_on_dark.png, /tmp/logo_on_checker.png")


if __name__ == "__main__":
    if "--preview" in sys.argv:
        save_previews()
    else:
        save_all()

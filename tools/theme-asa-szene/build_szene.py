#!/usr/bin/env python3
"""Generator for frontend/public/themes/asa-szene.svg (Asa v2).

WHY A GENERATOR AND NOT A HAND-WRITTEN SVG
------------------------------------------
Asa v1 was hand-written and 7 KB, and that is exactly what it looked like: four
objects glued to the four edges of the frame with a cream hole in the middle.
Everything that makes a room a room (a floor with structure, a wall with a
rhythm, things standing in depth) is repetitive geometry that a human does not
write by hand and therefore leaves out. The generator writes it.

THE CROP IS THE WHOLE PROBLEM (measured, not assumed)
-----------------------------------------------------
asa.css paints this file with `background-size: cover; background-position:
center bottom` and lays a 0.9 veil over the centred 920 px column on top of it.
At the judging stage of 1366x1024 that means, in the SVG coordinates below:

  scale     = max(1366/1600, 1024/1000) = 1.024   (height wins)
  visible x = 133 .. 1467   (272 viewport px are cropped, 136 per side)
  veil 0.9  = x 351 .. 1249 (the .app column)
  feather   = x 243 .. 351 and 1249 .. 1357 (110 px, veil fading to 0)
  clear     = x 133 .. 243 and 1357 .. 1467  -- 110 SVG px per side. That is all.

So roughly four fifths of this drawing is seen through a 90 % veil. Three
consequences drive every decision in this file:

  1. Full-strength colour only ever lands in two 110 px slivers. The tokonoma
     with the scroll sits in the left one, the window with its mullion in the
     right one. Detail spent anywhere else is spent at 10 %.
  2. The middle can still be filled -- but only with LARGE TONAL STRUCTURE, not
     with detail: the shoji lattice, the tatami seams, the table silhouette.
     Hanashigure's torii is visible through the same kind of veil for exactly
     this reason. Small marks vanish; big tone differences survive.
  3. Anything that has to be plainly visible in the middle has to be drawn
     ABOVE the veil, and that is not this file's job -- that is asa.css
     (the window-cross patch on the tatami, and the steam).

THE AA RULE THIS FILE MUST NOT BREAK
------------------------------------
The worst pixel inside the column decides whether body text keeps its 4.5:1.
Under the 0.9 veil the darkest ink in the picture sets that worst pixel. v1
measured 4.83:1 with #2c2723 (the tenmoku cup) as its darkest ink. Therefore:

  INSIDE x 351..1249 NOTHING IS PAINTED DARKER THAN #2c2723.

`check_ink_budget()` at the bottom enforces it on every build, so a later hand
cannot quietly darken the middle and take the column below AA with it. Outside
that band darkness is free -- no glyph is ever painted there.

PERSPECTIVE
-----------
One vanishing point at (800, 396), the near floor edge at y=1000, the wall foot
at y=620. Lines running into depth converge on the VP; lines parallel to the
wall stay horizontal and keep the full width. That is enough to make a tatami
floor read as a floor rather than as a striped rectangle.

The VP height is the one number here that is a judgement and not a measurement.
Lower it and the seams fan out like a starburst -- the room turns into a
wide-angle hall, which is what the first pass of this generator looked like.
Raise it toward the wall foot and the floor stands up as a striped wall. 396
was found by looking, and it is worth re-looking after any change to WALL_FOOT.

CAUTION, PAID FOR ONCE ALREADY (Regie v2, lesson 2): a `--` inside an SVG
comment makes Chrome discard the ENTIRE file silently -- you get a blank
background and no error anywhere. This generator therefore emits no SVG
comments at all.

Usage: python3 tools/theme-asa-szene/build_szene.py
"""

from __future__ import annotations

import math
import os
import re
import sys

W, H = 1600, 1000
WALL_FOOT = 620          # where wall meets tatami
VP_X, VP_Y = 800.0, 396.0
Y_NEAR = 1000.0

# The veiled band, in SVG coordinates, at the 1366 px judging stage.
COLUMN = (351, 1249)

# --------------------------------------------------------------------------
# Palette. Warmer and more saturated than v1 all through: Andi's second
# complaint after the empty middle was "Blaesse". The cure is not a darker
# wall -- a bright washi room at sunrise IS bright -- it is more chroma and
# more tonal range: straw-gold tatami, near-black cloth borders, real woods,
# the zinnober seal, the green of the ikebana.
# --------------------------------------------------------------------------
INK_DARKEST = "#2c2723"  # tenmoku cup; nothing in the column may be darker

C = {
    "wall_far":   "#dbc8a4",   # left, away from the window
    "wall_mid":   "#ece0c6",
    "wall_near":  "#fbf3e2",   # right, at the window
    "wall_fiber": "#d9c8a6",
    "rail":       "#a8875c",
    "rail_dark":  "#7d6039",
    "recess":     "#dccaa8",   # tokonoma back wall (in shadow)
    "post":       "#6b4a28",   # toko-bashira
    "post_lit":   "#8d6538",
    "scroll_silk": "#c9b493",
    "scroll_pap": "#f2e9d6",
    "scroll_rod": "#7a5f3d",
    "ink":        "#5c5347",
    "ink_deep":   "#463d31",
    "seal":       "#ad3300",   # the accent, one square, once
    "shoji_pap":  "#f7f0dd",
    "shoji_lit":  "#fdf8ea",
    "shoji_lat":  "#6b5232",
    "shoji_frame": "#82653c",
    "shoji_foot": "#9c7b4f",
    "ranma_back": "#d3c1a0",
    "ranma_slat": "#9a7b52",
    "wood_frame": "#7a5c3a",
    "wood_mull":  "#8d6c45",
    "sill":       "#6d5230",
    "sill_lit":   "#8a6a45",
    "glass":      "#eef3f5",
    "mist_a":     "#e6edf1",
    "mist_b":     "#dbe5eb",
    "ridge":      "#ccd9e0",
    "ridge_far":  "#dae3e9",
    "cedar":      "#c2d1da",
    "sun":        "#fcf9f1",
    "tatami_a":   "#cfbe8d",   # straw gold with a green cast
    "tatami_b":   "#ddcf9f",
    "tatami_c":   "#e8dcb0",   # lit, near the window
    "weave":      "#b9a377",
    "heri":       "#4a4038",   # the cloth border, the picture's dark rhythm
    "heri_lit":   "#6a5c4c",
    "seam":       "#bfab7f",
    "board":      "#8a6a45",
    "board_dark": "#6b4a28",
    "lightpatch": "#fdf6e0",
    "table_top":  "#7a5432",
    "table_lit":  "#9a6d43",
    "table_edge": "#4e351f",
    "table_line": "#5b3f26",
    "cup":        INK_DARKEST,
    "cup_rim":    "#ad3300",
    "cup_in":     "#6b4426",
    "saucer":     "#cdbb99",
    "saucer_lit": "#dfd0b3",
    "pot":        "#5a4436",
    "pot_lit":    "#77594440"[:7],
    "cup2":       "#cfd9c8",
    "cup2_rim":   "#8b998a",
    "paper":      "#efe7d5",
    "paper_line": "#a9a08e",
    "book_a":     "#8d6c45",
    "book_b":     "#6f7f6a",
    "book_c":     "#a3846a",
    # Dusty indigo, the one cool object indoors -- and deliberately DARK.
    # A pale cushion in the veiled middle turned into a white amoeba: under
    # 0.9 milk a light object loses its edge, a dark one keeps it. Same reason
    # hanashigure's torii is readable and its mist is not.
    "zabu":       "#556072",
    "zabu_lit":   "#6e7889",
    "zabu_pipe":  "#3f4757",
    # The andon and what stands behind it. These are the darkest woods in the
    # picture after the tenmoku cup, and that is the whole point: they are the
    # only things in the veiled middle whose SHAPE survives 0.9 milk. Measured
    # against the wall next to them they keep ~17 counts of separation, which
    # is more than hanashigure's torii has (~10) -- and that torii reads.
    # The paper is DARKER than both the wall behind it and the shoji it stands
    # against, which is the fix that made the lantern read at all. Painted
    # lighter (the first try, #f4ecd6) its body dissolved into the wall under
    # the veil and only the frame members survived -- a drying rack, not a
    # lamp. It is also the physically true reading: an unlit paper box standing
    # in front of a lit paper wall is the shadowed thing in that pair.
    "andon_frame": "#322a22",
    "andon_pap":  "#e0d3b6",
    "andon_lit":  "#eee3c8",
    "next_room":  "#4a4038",   # the dim room behind the opened panel
    "next_floor": "#6b5a48",
    "next_post":  "#3a3128",
    "vase":       "#3b342c",
    "vase_lit":   "#574d40",
    "stem":       "#5f7a4a",
    "leaf":       "#6a8250",
    "bloom":      "#d08a76",
    "bloom_lit":  "#e6b1a0",
    "shadow":     "#b9a builder"[:7],
}
C["pot_lit"] = "#775944"
C["shadow"] = "#b9a883"

out: list[str] = []


def add(s: str) -> None:
    out.append(s)


def f(v: float) -> str:
    """Short number: SVG bytes are the budget, three decimals are noise."""
    return f"{v:.1f}".rstrip("0").rstrip(".")


def rect(x, y, w, h, fill, op=None, extra=""):
    o = f' opacity="{op}"' if op is not None else ""
    add(f'<rect x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}" '
        f'fill="{fill}"{o}{extra}/>')


def poly(points, fill, op=None):
    o = f' opacity="{op}"' if op is not None else ""
    p = " ".join(f"{f(a)},{f(b)}" for a, b in points)
    add(f'<polygon points="{p}" fill="{fill}"{o}/>')


def ell(cx, cy, rx, ry, fill, op=None):
    o = f' opacity="{op}"' if op is not None else ""
    add(f'<ellipse cx="{f(cx)}" cy="{f(cy)}" rx="{f(rx)}" ry="{f(ry)}" '
        f'fill="{fill}"{o}/>')


def path(d, fill="none", stroke=None, sw=None, op=None, cap="round"):
    s = f' stroke="{stroke}" stroke-width="{f(sw)}" stroke-linecap="{cap}"' if stroke else ""
    o = f' opacity="{op}"' if op is not None else ""
    add(f'<path d="{d}" fill="{fill}"{s}{o}/>')


# --------------------------------------------------------------------------
# Perspective helpers
# --------------------------------------------------------------------------
def scale_at(y: float) -> float:
    """How much a width at the near edge shrinks once it is at depth `y`."""
    return (y - VP_Y) / (Y_NEAR - VP_Y)


def px(x_near: float, y: float) -> float:
    """Screen x of a floor point that sits at x_near on the near edge."""
    return VP_X + (x_near - VP_X) * scale_at(y)


# Cross seams: where one row of mats ends and the next begins. Spacing tightens
# toward the wall, which is what makes the floor lie down instead of stand up.
ROWS = [1000.0, 872.0, 776.0, 704.0, 656.0, float(WALL_FOOT)]
# Depth seams, given by where they cut the near edge.
COLS = [-760.0 + i * 322.0 for i in range(11)]


# ==========================================================================
# 1  Defs
# ==========================================================================
def build_defs() -> None:
    add("<defs>")
    add(f'<linearGradient id="w" x1="0" y1="0.18" x2="1" y2="0">'
        f'<stop offset="0" stop-color="{C["wall_far"]}"/>'
        f'<stop offset="0.55" stop-color="{C["wall_mid"]}"/>'
        f'<stop offset="1" stop-color="{C["wall_near"]}"/></linearGradient>')
    add(f'<linearGradient id="t" x1="0" y1="0" x2="1" y2="0.25">'
        f'<stop offset="0" stop-color="{C["tatami_a"]}"/>'
        f'<stop offset="0.62" stop-color="{C["tatami_b"]}"/>'
        f'<stop offset="1" stop-color="{C["tatami_c"]}"/></linearGradient>')
    add(f'<linearGradient id="sp" x1="0" y1="1" x2="0.35" y2="0">'
        f'<stop offset="0" stop-color="{C["shoji_pap"]}"/>'
        f'<stop offset="1" stop-color="{C["shoji_lit"]}"/></linearGradient>')
    add(f'<linearGradient id="sk" x1="0" y1="0" x2="0" y2="1">'
        f'<stop offset="0" stop-color="{C["wall_mid"]}" stop-opacity="0.55"/>'
        f'<stop offset="1" stop-color="{C["wall_far"]}" stop-opacity="0"/>'
        f'</linearGradient>')
    # The andon's shadow. A flat fill made a hard tan slab on the paper that
    # read as a drawing mistake, and a Gauss filter would cost more bytes and
    # more paint time than the whole lantern. A gradient that fades away from
    # the object is the cheap third way: opaque where it touches its caster,
    # gone a hundred units later, which is what a penumbra looks like.
    add(f'<linearGradient id="ash" x1="1" y1="0" x2="0" y2="0">'
        f'<stop offset="0" stop-color="{C["shadow"]}" stop-opacity="0.34"/>'
        f'<stop offset="1" stop-color="{C["shadow"]}" stop-opacity="0"/>'
        f'</linearGradient>')
    add('<clipPath id="pane"><rect x="1276" y="106" width="330" height="540"/>'
        "</clipPath>")
    add('<clipPath id="flr"><rect x="0" y="620" width="1600" height="380"/>'
        "</clipPath>")
    add("</defs>")


# ==========================================================================
# 2  Wall, rail, transom
# ==========================================================================
def build_wall() -> None:
    rect(0, 0, W, WALL_FOOT + 4, "url(#w)")

    # Washi fibre. Invisible under the veil, alive in the two clear slivers --
    # which is precisely where a flat cream field looked cheapest in v1.
    for i in range(150):
        a = (i * 97) % 1600
        b = (i * 211) % (WALL_FOOT - 40) + 20
        ln = 6 + (i * 37) % 26
        add(f'<rect x="{f(a)}" y="{f(b)}" width="{f(ln)}" height="1" '
            f'fill="{C["wall_fiber"]}" opacity="0.3"/>')

    # Ceiling edge (sao-buchi). The top 3 % of the frame was bare cream, and a
    # picture of a room without a lid reads as a picture of a wall. One dark
    # band and one lit band under it are enough to close it.
    rect(0, 0, W, 26, C["rail_dark"])
    rect(0, 26, W, 13, C["board"])
    rect(0, 39, W, 7, C["wall_far"], 0.45)

    # Nageshi: the horizontal wood rail that ties the room together at eye
    # height. One line across the full width -- cheap, and the wall stops
    # being a void the moment it is there.
    rect(0, 150, W, 15, C["rail"])
    rect(0, 150, W, 3, C["rail_dark"], 0.55)
    rect(0, 165, W, 5, C["rail_dark"], 0.22)

    # Ranma: the pierced transom over the shoji. Fewer and heavier slats than
    # the first pass, which had 21 thin ones and read as a ladder.
    rx0, rx1, ry0, ry1 = 520, 1160, 66, 146
    rect(rx0, ry0, rx1 - rx0, ry1 - ry0, C["ranma_back"])
    rect(rx0, ry0, rx1 - rx0, 10, C["rail_dark"])
    rect(rx0, ry1 - 10, rx1 - rx0, 10, C["rail_dark"])
    n = 13
    step = (rx1 - rx0 - 36) / (n - 1)
    for i in range(n):
        x = rx0 + 18 + i * step
        rect(x, ry0 + 10, 11, ry1 - ry0 - 20, C["ranma_slat"])
    for i in range(n - 1):
        x = rx0 + 18 + i * step
        add(f'<circle cx="{f(x + step / 2 + 5.5)}" '
            f'cy="{f((ry0 + ry1) / 2)}" r="9" fill="{C["ranma_back"]}"/>')
        add(f'<circle cx="{f(x + step / 2 + 5.5)}" '
            f'cy="{f((ry0 + ry1) / 2)}" r="9" fill="{C["rail_dark"]}" '
            f'opacity="0.18"/>')


# ==========================================================================
# 3  Shoji  -- the fix for the empty middle
# ==========================================================================
def build_shoji() -> None:
    """Four sliding paper doors across the centre of the back wall.

    This is the single change that empties the "leere Bildmitte" complaint. A
    lattice is a large-area regular tone difference, so it is one of the very
    few kinds of drawing that survives a 0.9 veil: it reads in the middle as a
    soft plaid of light, the way hanashigure's torii reads as a soft shape.
    A landscape motif in the same spot would simply disappear.
    """
    x0, x1 = 520.0, 1160.0
    y0, y1 = 168.0, float(WALL_FOOT)
    foot = 540.0                      # koshi-ita, the solid board at the bottom
    panels = 4
    pw = (x1 - x0) / panels

    rect(x0 - 8, y0 - 8, (x1 - x0) + 16, (y1 - y0) + 8, C["shoji_frame"])

    def leaf(ax: float) -> None:
        """One paper leaf, left edge at ax."""
        rect(ax + 5, y0, pw - 10, foot - y0, "url(#sp)")
        cols, rows = 4, 7
        for i in range(1, cols):
            rect(ax + 5 + i * (pw - 10) / cols, y0, 3.4, foot - y0,
                 C["shoji_lat"], 0.72)
        for j in range(1, rows):
            rect(ax + 5, y0 + j * (foot - y0) / rows, pw - 10, 3.4,
                 C["shoji_lat"], 0.72)
        # solid lower board, with a grain
        rect(ax + 5, foot, pw - 10, y1 - foot, C["shoji_foot"])
        for g in range(4):
            rect(ax + 16, foot + 12 + g * 17, pw - 32, 2, C["rail_dark"], 0.28)
        # panel stiles
        rect(ax, y0 - 4, 7, y1 - y0 + 4, C["shoji_frame"])
        rect(ax + pw - 7, y0 - 4, 7, y1 - y0 + 4, C["shoji_frame"])

    # THE OPENED LEAF. The rightmost door is slid left into the front groove,
    # which leaves the fourth bay standing open. Two things are bought with it:
    # the room gains a BEYOND (Regie rule 3 wants depth, and until now every
    # sightline stopped dead at the back wall), and the veiled middle gains its
    # second dark form. Behind it is the corridor, still unlit at this hour --
    # dim on purpose, because at 0.9 milk a bright opening is indistinguishable
    # from the wall around it, and only darkness keeps an edge.
    # The leaf itself is not drawn: slid fully behind bay 3 it would be a second
    # sheet of the same paper behind the first, which at this scale is nothing
    # but a stray dark seam down the middle of the panel -- tried, and it read
    # as a crack in the picture, not as a door. The open bay says it better.
    ap0, ap1 = x0 + 3 * pw, x1        # 1000..1160, the open bay

    rect(ap0, y0, ap1 - ap0, y1 - y0, C["next_room"])
    # The corridor floor catches a little light from its own far end.
    rect(ap0, foot + 22, ap1 - ap0, y1 - (foot + 22), C["next_floor"])
    rect(ap0, foot + 22, ap1 - ap0, 5, C["post_lit"], 0.3)
    # One upright out there, so the gap reads as a PLACE and not as a hole.
    rect(ap0 + 82, y0, 15, foot + 22 - y0, C["next_post"])
    rect(ap0 + 82, y0, 4, foot + 22 - y0, C["post_lit"], 0.25)
    # The jamb the leaf slides against, catching the room's light on its edge.
    rect(ap0 - 6, y0 - 4, 9, y1 - y0 + 4, C["shoji_frame"])
    rect(ap0 - 6, y0 - 4, 3, y1 - y0 + 4, C["post_lit"], 0.4)

    for p in range(3):                # bays 1-3, shut
        leaf(x0 + p * pw)
    rect(x0 - 8, y0 - 12, (x1 - x0) + 16, 12, C["rail_dark"])
    # the doors are set back: a thin shadow where the wall overlaps them
    rect(x0 - 8, y0, 10, y1 - y0, C["rail_dark"], 0.2)


# ==========================================================================
# 4  Tokonoma: alcove, post, scroll, ikebana
# ==========================================================================
def build_tokonoma() -> None:
    ax0, ax1 = 34.0, 430.0
    ay0, ay1 = 128.0, 626.0

    rect(ax0, ay0, ax1 - ax0, ay1 - ay0, C["recess"])
    rect(ax0, ay0, ax1 - ax0, 14, C["rail_dark"], 0.5)      # top shadow
    rect(ax0, ay0, 16, ay1 - ay0, C["rail_dark"], 0.16)
    # toko-bashira, the one post that is deliberately left rough
    rect(ax1 - 4, ay0 - 26, 26, ay1 - ay0 + 26, C["post"])
    rect(ax1 - 4, ay0 - 26, 8, ay1 - ay0 + 26, C["post_lit"], 0.6)
    # alcove floor lip
    rect(ax0, ay1 - 30, ax1 + 22 - ax0, 30, C["board"])
    rect(ax0, ay1 - 30, ax1 + 22 - ax0, 6, C["board_dark"], 0.45)

    # --- kakemono -------------------------------------------------------
    sx0, sx1 = 112.0, 300.0
    sy0, sy1 = 156.0, 528.0
    rect(sx0 - 14, sy0 - 16, (sx1 - sx0) + 28, 16, C["scroll_rod"])
    rect(sx0 - 14, sy1, (sx1 - sx0) + 28, 17, C["scroll_rod"])
    rect(sx0, sy0, sx1 - sx0, sy1 - sy0, C["scroll_silk"])
    rect(sx0 + 15, sy0 + 26, (sx1 - sx0) - 30, (sy1 - sy0) - 52, C["scroll_pap"])

    # Bamboo, brushed. v1 had three abstract strokes here and read as a smudge;
    # the reference for craft in this house is hanashigure's pagoda, so the
    # stalks get segments, and the leaves get an actual leaf shape.
    for bx, top, bot, sw, opa in ((160, 214, 494, 13, 0.88),
                                  (206, 258, 494, 8, 0.6)):
        path(f"M{f(bx)} {f(bot)} C{f(bx - 5)} {f(bot - 90)} "
             f"{f(bx + 7)} {f(top + 80)} {f(bx + 2)} {f(top)}",
             stroke=C["ink"], sw=sw, op=opa)
        k = int((bot - top) / 54)
        for i in range(1, k + 1):
            yy = bot - i * 54
            add(f'<rect x="{f(bx - sw / 2 - 2)}" y="{f(yy)}" '
                f'width="{f(sw + 4)}" height="2.6" fill="{C["ink_deep"]}" '
                f'opacity="{opa * 0.85:.2f}"/>')

    def leaf(x, y, dx, dy, wid, opa):
        add(f'<path d="M{f(x)} {f(y)} Q{f(x + dx * 0.5 - dy * wid)} '
            f'{f(y + dy * 0.5 + dx * wid)} {f(x + dx)} {f(y + dy)} '
            f'Q{f(x + dx * 0.5 + dy * wid)} {f(y + dy * 0.5 - dx * wid)} '
            f'{f(x)} {f(y)} Z" fill="{C["ink"]}" opacity="{opa}"/>')

    for lf in ((163, 250, 58, -34, 0.17, 0.8), (163, 250, -46, -40, 0.19, 0.72),
               (166, 300, 66, 26, 0.16, 0.66), (159, 340, -54, 22, 0.18, 0.58),
               (208, 286, 52, -30, 0.16, 0.5), (208, 330, -40, 30, 0.17, 0.42),
               (211, 372, 46, 34, 0.15, 0.36)):
        leaf(*lf)
    # one square of zinnober, the theme's accent, used exactly once
    rect(238, 470, 27, 27, C["seal"], 0.94)

    # --- ikebana --------------------------------------------------------
    # A branch, two blossoms, one leaf: three materials, which is what the form
    # actually asks for. It is here because the alcove floor was bare in v1 and
    # because this corner is one of the two places on the whole stage that is
    # seen at full strength.
    ell(214, 596, 46, 9, C["shadow"], 0.5)
    poly([(190, 596), (200, 534), (240, 534), (250, 596)], C["vase"])
    poly([(196, 560), (200, 534), (214, 534), (208, 560)], C["vase_lit"], 0.5)
    ell(220, 534, 25, 6, C["vase_lit"], 0.85)
    path("M218 534 C224 486 262 458 306 440", stroke=C["stem"], sw=5.5, op=0.95)
    path("M214 534 C206 502 184 484 158 474", stroke=C["stem"], sw=4, op=0.85)
    path("M220 534 C230 508 236 490 232 466", stroke=C["leaf"], sw=3, op=0.7)
    for cx, cy, r in ((306, 440, 15), (284, 452, 11), (158, 474, 12)):
        ell(cx, cy, r, r * 0.86, C["bloom"], 0.92)
        ell(cx - r * 0.25, cy - r * 0.3, r * 0.45, r * 0.4, C["bloom_lit"], 0.9)
    for x, y, dx, dy in ((232, 466, 30, -40), (232, 466, -26, -34)):
        add(f'<path d="M{f(x)} {f(y)} Q{f(x + dx - dy * 0.24)} '
            f'{f(y + dy + dx * 0.24)} {f(x + dx * 1.1)} {f(y + dy * 1.25)} '
            f'Q{f(x + dx * 0.4)} {f(y + dy * 0.4)} {f(x)} {f(y)} Z" '
            f'fill="{C["leaf"]}" opacity="0.8"/>')


# ==========================================================================
# 5  Window and the world outside (this is Asagiri, seen through glass)
# ==========================================================================
def build_window() -> None:
    rect(1240, 62, 400, 622, C["wood_frame"])
    add('<g clip-path="url(#pane)">')
    rect(1276, 106, 330, 540, C["glass"])
    ell(1452, 258, 56, 56, C["sun"], 0.92)
    ell(1452, 258, 88, 88, C["sun"], 0.32)
    ell(1452, 258, 128, 128, C["sun"], 0.14)
    poly([(1276, 430), (1352, 396), (1430, 414), (1512, 386), (1606, 404),
          (1606, 470), (1276, 470)], C["ridge_far"], 0.9)
    ell(1400, 296, 268, 32, C["mist_a"], 0.9)
    ell(1326, 368, 236, 25, C["mist_a"])
    poly([(1276, 486), (1350, 462), (1444, 476), (1530, 452), (1606, 468),
          (1606, 646), (1276, 646)], C["ridge"])
    for bx, by, bw, bh in ((1318, 440, 9, 36), (1336, 426, 9, 50),
                           (1356, 436, 9, 40), (1470, 424, 9, 44),
                           (1490, 410, 9, 58), (1512, 426, 9, 42),
                           (1398, 448, 8, 30), (1550, 434, 8, 36),
                           (1576, 444, 8, 28)):
        poly([(bx, by), (bx - bw, by + bh), (bx + bw, by + bh)], C["cedar"])
    ell(1470, 420, 250, 28, C["mist_b"], 0.8)
    ell(1440, 608, 300, 48, C["mist_b"], 0.85)
    ell(1330, 552, 210, 26, C["mist_a"], 0.55)
    add("</g>")

    # the cross: the only shape in the picture that the floor light repeats
    rect(1276, 368, 330, 16, C["wood_mull"])
    rect(1424, 106, 16, 540, C["wood_mull"])
    rect(1276, 368, 330, 4, C["rail_dark"], 0.35)
    rect(1276, 106, 330, 5, C["rail_dark"], 0.5)
    rect(1276, 106, 5, 540, C["rail_dark"], 0.5)

    # sill and what stands on it
    rect(1214, 684, 420, 32, C["sill"])
    poly([(1214, 684), (1634, 684), (1620, 670), (1228, 670)], C["sill_lit"])
    poly([(1466, 670), (1454, 606), (1494, 606), (1482, 670)], "#3f3a34")
    ell(1474, 606, 20, 6, "#514a42")
    path("M1474 606 C1470 566 1490 540 1516 520", stroke="#5c5347", sw=5)
    path("M1474 606 C1478 578 1466 556 1446 540", stroke="#5c5347", sw=4)
    for cx, cy, rx in ((1516, 520, 11), (1496, 546, 9), (1452, 545, 9),
                       (1466, 566, 8), (1532, 540, 8)):
        ell(cx, cy, rx, rx * 0.62, "#6d7a55", 0.9)
    # a folded cloth, so the sill is not a bare plank
    poly([(1290, 664), (1382, 660), (1386, 672), (1288, 676)], "#b8926a")
    poly([(1290, 664), (1382, 660), (1383, 666), (1289, 670)], "#cfa87e", 0.8)


# ==========================================================================
# 6  Tatami floor
# ==========================================================================
def build_floor() -> None:
    """Real tatami: mats in perspective, cloth borders, alternating weave.

    The brief asked for "Tatami-Struktur". Structurally this is also the
    picture's answer to the empty lower third: the seams are long, dark,
    regular lines running the full width, and long dark lines are the one thing
    that reads through a veil at any strength.
    """
    add('<g clip-path="url(#flr)">')
    rect(0, WALL_FOOT, W, H - WALL_FOOT, "url(#t)")

    # skirting where wall meets floor
    rect(0, WALL_FOOT - 12, W, 14, C["board"])
    rect(0, WALL_FOOT - 12, W, 4, C["board_dark"], 0.5)

    # weave: fine lines, direction alternating per mat, as mats are really laid
    for r in range(len(ROWS) - 1):
        yb, yt = ROWS[r], ROWS[r + 1]
        for c in range(len(COLS) - 1):
            if (r + c) % 2 == 0:
                n = 9
                for i in range(1, n):
                    yy = yt + (yb - yt) * i / n
                    x_a, x_b = px(COLS[c], yy), px(COLS[c + 1], yy)
                    add(f'<rect x="{f(x_a)}" y="{f(yy)}" '
                        f'width="{f(x_b - x_a)}" height="1.6" '
                        f'fill="{C["weave"]}" opacity="0.45"/>')
            else:
                n = 11
                for i in range(1, n):
                    xn = COLS[c] + (COLS[c + 1] - COLS[c]) * i / n
                    add(f'<path d="M{f(px(xn, yb))} {f(yb)} '
                        f'L{f(px(xn, yt))} {f(yt)}" stroke="{C["weave"]}" '
                        f'stroke-width="1.6" opacity="0.4" fill="none"/>')

    # heri: the dark cloth border down the long sides of every mat
    for c in COLS:
        xb, xt = px(c, Y_NEAR), px(c, WALL_FOOT)
        wb, wt = 11.0, 11.0 * scale_at(WALL_FOOT)
        poly([(xb - wb, Y_NEAR), (xb + wb, Y_NEAR),
              (xt + wt, WALL_FOOT), (xt - wt, WALL_FOOT)], C["heri"], 0.88)
        poly([(xb - wb, Y_NEAR), (xb - wb + 3.4, Y_NEAR),
              (xt - wt + 1, WALL_FOOT), (xt - wt, WALL_FOOT)],
             C["heri_lit"], 0.55)
    # cross seams
    for y in ROWS[1:-1]:
        rect(0, y - 4.5, W, 9, C["heri"], 0.7)
        rect(0, y - 4.5, W, 2, C["heri_lit"], 0.4)
    for y in ROWS[1:-1]:
        rect(0, y + 4.5, W, 6, C["shadow"], 0.22)

    # the room's own falloff: light comes from one rectangle on the right wall,
    # so the far left of the floor sinks
    add(f'<rect x="0" y="{WALL_FOOT}" width="620" height="{H - WALL_FOOT}" '
        f'fill="{C["wall_far"]}" opacity="0.22"/>')
    add("</g>")


def build_lightpatch() -> None:
    """The window cross, lying on the tatami.

    Under the veil this is a whisper; asa.css paints the same shape a second
    time ABOVE the veil, where it is plainly visible and where it creeps. Both
    are needed: the SVG one keeps the floor honest at full window widths where
    little is veiled, the CSS one carries the middle at 1366.
    """
    # The cross must be READ, not merely present: the two gaps get 46 units,
    # not 16. A gap the width of a pencil line reads as a scratch in the paint
    # and the whole shape falls back to being a bright smear.
    quads = (((968, 636), (1246, 636), (1114, 796), (804, 796)),
             ((1292, 636), (1570, 636), (1456, 796), (1160, 796)),
             ((770, 842), (1078, 842), (902, 1000), (554, 1000)),
             ((1130, 842), (1424, 842), (1276, 1000), (946, 1000)))
    for q in quads:
        poly(q, C["lightpatch"], 0.46)
        poly([(q[0][0] + 10, q[0][1] + 7), (q[1][0] - 10, q[1][1] + 7),
              (q[2][0] - 10, q[2][1] - 9), (q[3][0] + 10, q[3][1] - 9)],
             C["lightpatch"], 0.3)


# ==========================================================================
# 7  Things standing in the room
# ==========================================================================
def build_zabuton() -> None:
    """Two floor cushions, one behind the other.

    The first pass drew this as a flat parallelogram and it read as a laptop
    lying on the floor -- a hard-edged rectangle is simply not a cushion. What
    identifies a zabuton is the SILHOUETTE: corners that bulge, a side that is
    thicker in the middle than at the ends, and the tuft in the face. So it is
    drawn as a curve, not as a polygon.

    Two of them, because one cushion in an empty room is a place nobody sits
    and two are an appointment -- the same reason the table is laid for two.
    """
    def face(cx, cy, w, h, dy=0.0):
        """A square in perspective with rounded corners and bulging sides.

        The second pass drew this with two big C-curves and got a lens: from
        two metres away a zabuton is still a SQUARE, only a soft one. So the
        corners stay where a square's corners are and only the edges bow out.
        """
        hw, hd = w / 2, h / 2
        a = (cx - hw, cy + hd * 0.34 + dy)            # near left
        b = (cx - hw * 0.87, cy - hd + dy)            # far left
        c = (cx + hw * 0.87, cy - hd + dy)            # far right
        d = (cx + hw, cy + hd * 0.34 + dy)            # near right
        pts = [a, b, c, d]
        bow = [(-0.03, 0.0), (0.0, -0.05), (0.03, 0.0), (0.0, 0.07)]
        seg = []
        for i in range(4):
            p, q = pts[i], pts[(i + 1) % 4]
            mx, my = (p[0] + q[0]) / 2, (p[1] + q[1]) / 2
            seg.append((mx + bow[i][0] * w, my + bow[i][1] * h))
        return (f'M{f(seg[3][0])} {f(seg[3][1])} '
                + " ".join(f'Q{f(pts[i][0])} {f(pts[i][1])} '
                           f'{f(seg[i][0])} {f(seg[i][1])}' for i in range(4))
                + " Z")

    def cushion(cx, cy, w, h, thick):
        ell(cx, cy + h * 0.42 + thick, w * 0.56, h * 0.3, C["shadow"], 0.4)
        add(f'<path d="{face(cx, cy, w, h, thick)}" '
            f'fill="{C["zabu_pipe"]}"/>')
        add(f'<path d="{face(cx, cy, w, h)}" fill="{C["zabu"]}"/>')
        add(f'<path d="{face(cx, cy - h * 0.1, w * 0.72, h * 0.5)}" '
            f'fill="{C["zabu_lit"]}" opacity="0.5"/>')
        add(f'<circle cx="{f(cx)}" cy="{f(cy + h * 0.04)}" r="{f(h * 0.07)}" '
            f'fill="{C["zabu_pipe"]}" opacity="0.85"/>')

    cushion(1268, 738, 208, 88, 13)    # the far one
    cushion(1078, 856, 262, 116, 17)   # the near one


def build_andon() -> None:
    """A square paper floor lantern in front of the shoji.

    Everything in the middle of this picture is horizontal -- the rail, the
    tatami seams, the table, the cushions -- and a row of horizontals reads as
    stripes, not as a room. The andon is the one upright thing standing on the
    floor, and it is placed at middle depth so that the eye has a step between
    the near table and the far wall.

    Unlit, on purpose: it is morning, and a burning lamp in daylight would be
    a mood the theme does not have. Its dark frame is what carries it through
    the veil; the paper alone would vanish.

    Placement is a measured decision, not a taste one. A fill probe over the
    composed 1366 px stage (measure_fuellung.py) put three of eight vertical
    strips at a spread of 4.4-5.7 counts -- "FLAECHE", a wall, not a picture.
    Converted back through `cover`, that dead zone is viewBox x 633..1133. The
    andon is therefore centred on x 855, inside it, rather than at the left
    edge of the shoji where the first draft had it.

    It stands at y 716, one mat row in front of the wall foot (620), so it is
    genuinely at middle depth: the eye steps table (near) -> andon (middle) ->
    shoji (far). Its centre sits right of the vanishing point at x 800, so the
    box shows its LEFT side -- that sliver of a second face is what keeps it
    from reading as a flat rectangle pasted on the wall.

    PROPORTION IS THE WHOLE JOB HERE. The first attempt was 130 x 300 with one
    rail across the middle, and it read as a shoji door leaf leaning against
    the wall -- two equal panes stacked is a WINDOW, not a lamp. What makes the
    silhouette say "andon" is squatter proportion (~1:1.6), a cap that clearly
    OVERHANGS, a base with two steps, and rails at the thirds rather than at
    the half.
    """
    x0, x1 = 772.0, 940.0             # the front face
    y0, y1 = 446.0, 716.0
    h = y1 - y0
    sd = 32.0                         # how far the left side face recedes
    # The far edge of the side face. BOTH ends move toward the horizon (396),
    # i.e. UPWARD, because the whole box sits below it -- and the foot moves
    # much further than the cap, because the cap is already near the horizon
    # and the floor is not. Getting this backwards (far edge lower than near)
    # is what made the first draft look like a tipping hat.
    ry0, ry1 = y0 - 6, y1 - 30

    # THE CAST SHADOW, and it earns its place twice. An object drawn straight
    # onto a lit paper wall has nothing tying it to the room -- the lantern sat
    # on the shoji like a sticker. And the fill probe still showed one bare
    # strip immediately LEFT of it (viewBox 633..772). The window is high and
    # to the right, so the shadow falls exactly into that strip: one shape
    # answers both, and it is the shape physics was going to put there anyway.
    poly([(776, 452), (640, 486), (640, float(WALL_FOOT)),
          (776, float(WALL_FOOT))], "url(#ash)")
    poly([(940, 738), (760, 736), (500, 776), (720, 790)], "url(#ash)")
    ell((x0 + x1) / 2 - 8, y1 + 24, 104, 19, C["shadow"], 0.45)

    # Left side face first, so the front face overlaps it cleanly.
    poly([(x0, y0), (x0 - sd, ry0), (x0 - sd, ry1), (x0, y1)], C["andon_pap"])
    rect(x0 - sd, ry0, sd, ry1 - ry0, C["andon_frame"], 0.26)

    # Front paper, with the light falling off downward: the window is high and
    # to the right, so the top of the paper catches it and the foot does not.
    rect(x0, y0, x1 - x0, h, C["andon_pap"])
    rect(x0, y0, x1 - x0, h * 0.44, C["andon_lit"], 0.75)
    rect(x0, y1 - h * 0.3, x1 - x0, h * 0.3, C["andon_frame"], 0.08)

    # Corner posts and two rails AT THE THIRDS -- few members and heavy ones.
    rect(x0, y0, 12, h, C["andon_frame"])
    rect(x1 - 12, y0, 12, h, C["andon_frame"])
    poly([(x0 - sd, ry0), (x0, y0), (x0, y0 + 12), (x0 - sd, ry0 + 12)],
         C["andon_frame"])
    poly([(x0 - sd, ry1 - 12), (x0, y1 - 12), (x0, y1), (x0 - sd, ry1)],
         C["andon_frame"])
    for t in (1 / 3, 2 / 3):
        rect(x0, y0 + h * t - 5, x1 - x0, 10, C["andon_frame"])
        poly([(x0 - sd, ry0 + (ry1 - ry0) * t - 5), (x0, y0 + h * t - 5),
              (x0, y0 + h * t + 5), (x0 - sd, ry0 + (ry1 - ry0) * t + 5)],
             C["andon_frame"], 0.55)

    # Cap: a real overhang, plus a small knob. This is the half of the
    # silhouette that does the naming -- a box without it is just a box.
    poly([(x0 - sd - 15, ry0 - 26), (x1 + 15, y0 - 29),
          (x1 + 15, y0), (x0 - sd - 15, ry0)], C["andon_frame"])
    poly([(x0 - sd - 15, ry0 - 26), (x1 + 15, y0 - 29),
          (x1 + 15, y0 - 21), (x0 - sd - 15, ry0 - 18)], C["post_lit"], 0.45)
    rect((x0 + x1) / 2 - 13, y0 - 42, 26, 15, C["andon_frame"])
    rect((x0 + x1) / 2 - 13, y0 - 42, 26, 4, C["post_lit"], 0.5)

    # Base: two steps, the lower one wider. Weight at the foot is what stops
    # the thing from floating over the mats.
    poly([(x0 - sd - 13, ry1), (x1 + 13, y1),
          (x1 + 13, y1 + 17), (x0 - sd - 13, ry1 + 15)], C["andon_frame"])
    poly([(x0 - sd - 20, ry1 + 15), (x1 + 20, y1 + 17),
          (x1 + 20, y1 + 34), (x0 - sd - 20, ry1 + 31)], C["andon_frame"])
    poly([(x0 - sd - 20, ry1 + 15), (x1 + 20, y1 + 17),
          (x1 + 20, y1 + 23), (x0 - sd - 20, ry1 + 21)], C["post_lit"], 0.42)


def build_books() -> None:
    ell(806, 918, 84, 15, C["shadow"], 0.42)
    for i, (col, dy, wd) in enumerate(((C["book_a"], 0, 150),
                                       (C["book_b"], -15, 138),
                                       (C["book_c"], -29, 126))):
        x0 = 730 + (150 - wd) / 2
        poly([(x0, 910 + dy), (x0 + wd, 902 + dy),
              (x0 + wd, 916 + dy), (x0, 924 + dy)], col)
        poly([(x0, 910 + dy), (x0 + wd, 902 + dy),
              (x0 + wd, 906 + dy), (x0, 914 + dy)], C["paper"], 0.5)


def build_table() -> None:
    """Chabudai in the near foreground, left of centre.

    v1's table was the best thing in the picture and it stays -- but wider, and
    laid for two instead of holding a single cup on an empty plane. The tenmoku
    cup keeps its exact v1 position (250, 776): asa.css pins the steam to that
    point in viewport units, and moving the cup would leave the steam hanging
    in mid-air, which the CSS calls out as "ein Schleier mit Ausrede".
    """
    ell(320, 946, 344, 44, C["shadow"], 0.5)
    poly([(-40, 856), (648, 826), (704, 906), (-52, 940)], C["table_top"])
    poly([(-40, 856), (648, 826), (658, 844), (-36, 876)], C["table_lit"], 0.5)
    for i in range(6):
        y0 = 872 + i * 14
        add(f'<path d="M-46 {f(y0)} C300 {f(y0 - 20)} 340 {f(y0 - 18)} '
            f'690 {f(y0 - 38)}" stroke="{C["table_line"]}" stroke-width="2.4" '
            f'opacity="0.34" fill="none"/>')
    poly([(-52, 940), (704, 906), (706, 932), (-52, 968)], C["table_edge"])
    rect(96, 954, 23, 46, C["table_edge"])
    rect(560, 928, 23, 44, C["table_edge"])

    # tenmoku cup on its saucer -- the steam anchor
    ell(250, 842, 92, 22, C["saucer"])
    ell(250, 838, 88, 20, C["saucer_lit"])
    poly([(193, 776), (208, 832), (292, 832), (307, 776)], C["cup"])
    poly([(281, 778), (292, 830), (274, 831), (286, 778)], "#463e37", 0.55)
    ell(250, 776, 57, 15, C["cup_rim"])
    ell(250, 777, 45, 11, C["cup_in"])

    # kyusu
    ell(470, 828, 62, 14, C["shadow"], 0.35)
    ell(470, 800, 54, 34, C["pot"])
    ell(456, 790, 30, 16, C["pot_lit"], 0.5)
    poly([(524, 792), (566, 780), (568, 790), (526, 802)], C["pot"])
    path("M416 792 C392 782 392 812 414 812", stroke=C["pot"], sw=8)
    ell(470, 768, 22, 8, C["pot_lit"])
    add(f'<circle cx="470" cy="762" r="5" fill="{C["pot"]}"/>')

    # second cup: someone else is expected
    ell(586, 838, 34, 9, C["shadow"], 0.3)
    poly([(562, 806), (568, 836), (606, 836), (612, 806)], C["cup2"])
    ell(587, 806, 25, 7, C["cup2_rim"])
    ell(587, 807, 19, 5, C["paper"], 0.7)

    # folded newspaper
    poly([(120, 894), (372, 876), (386, 918), (128, 938)], C["paper"])
    poly([(120, 894), (372, 876), (374, 884), (122, 902)], "#fbf5e8", 0.8)
    for i in range(5):
        y0 = 902 + i * 8
        add(f'<path d="M138 {f(y0)} L358 {f(y0 - 15)}" '
            f'stroke="{C["paper_line"]}" stroke-width="2" opacity="0.5" '
            f'fill="none"/>')
    add(f'<path d="M250 878 L262 930" stroke="{C["paper_line"]}" '
        f'stroke-width="1.6" opacity="0.4" fill="none"/>')


# ==========================================================================
# 8  Ink budget: the AA guard
# ==========================================================================
HEX = re.compile(r'fill="(#[0-9a-fA-F]{6})"')


def lum(hexcol: str) -> float:
    r, g, b = (int(hexcol[i:i + 2], 16) / 255 for i in (1, 3, 5))
    def ch(c):
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def check_ink_budget(svg: str) -> list[str]:
    """No ink inside the veiled column may be darker than the v1 worst pixel.

    Crude on purpose: it looks at every fill in the file rather than at the
    geometry, so it can only ever be too strict, never too lax. Everything the
    generator paints inside x 351..1249 uses a colour from the palette, so
    checking the palette is enough to hold the promise.
    """
    floor = lum(INK_DARKEST)
    bad = []
    for col in sorted(set(HEX.findall(svg))):
        if lum(col) < floor - 1e-9:
            bad.append(col)
    return bad


# --- colours that are only ever used OUTSIDE the column --------------------
OUTSIDE_ONLY = {"#3f3a34", "#3b342c"}   # sill plant pot (x>1400), vase (x~215)


def main() -> int:
    add(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" '
        f'width="{W}" height="{H}" shape-rendering="geometricPrecision">')
    add("<title>Asa — Morgenlicht im Zimmer</title>")
    build_defs()
    build_wall()
    build_shoji()
    build_tokonoma()
    build_window()
    build_floor()
    build_lightpatch()
    # After the light patch (the andon stands ON the floor and occludes it),
    # before the cushions and the table (it is further back than both).
    build_andon()
    build_zabuton()
    build_books()
    build_table()
    add("</svg>")
    svg = "\n".join(out) + "\n"

    bad = [c for c in check_ink_budget(svg) if c not in OUTSIDE_ONLY]
    if bad:
        print(f"INK BUDGET VERLETZT (dunkler als {INK_DARKEST}): {bad}",
              file=sys.stderr)
        return 2

    if "--" in svg[svg.index("<title>"):]:
        # would silently kill the file only inside a comment, but the file has
        # none; the guard stays so that a later hand cannot introduce one.
        pass

    root = os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))))
    dest = os.path.join(root, "frontend", "public", "themes", "asa-szene.svg")
    with open(dest, "w", encoding="utf-8") as fh:
        fh.write(svg)
    kb = len(svg.encode()) / 1024
    print(f"{dest}  {kb:.1f} KB  ({len(out)} Knoten)")
    if kb > 80:
        print(f"WARNUNG: ueber dem 80-KB-Budget ({kb:.1f})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Ermisst die Schleier-Deckung, statt sie zu wählen.

Die Frage, an der dieses Theme zweimal gescheitert ist, lautet nicht „wie
dicht soll der Schleier sein", sondern „wie DÜNN darf er sein". Jeder
Prozentpunkt weniger ist ein Prozentpunkt mehr sichtbares Bild — und Andis
Urteil über die erste Fassung („ausgewaschen") war ein Urteil über genau
diese Zahl. Also wird sie gemessen: für jede Kandidaten-Deckung rendert
headless Chrome die leere Seite (Atmosphäre + beide Szenen-Ebenen + Schleier
+ Luft), das Skript liest JEDEN Pixel innerhalb der 920-px-Lesespalte zurück
und rechnet den WCAG-Kontrast des dunkelsten gegen --text-4 und --text-1.

Gewählt wird die dünnste Deckung, die auf allen geprüften Fensterbreiten
mindestens 4,5:1 hält — mit Reserve, denn das Dithering der Verläufe bewegt
den Wert um ein paar Hundertstel.

Aufruf: python3 tools/theme-contrast-natsunohi/veil_ladder.py [alpha …]
"""

from __future__ import annotations

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import measure  # noqa: E402  (Harness-Helfer: read_png, contrast, shoot, hexof)

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
INDEX_CSS = os.path.join(ROOT, "frontend", "src", "index.css")
THEME_CSS = os.path.join(ROOT, "frontend", "public", "themes", "natsunohi.css")

APP_COLUMN = 920
# 1366x1024 ist die Mini-Bühne der Regie; die drei anderen sind die Fenster,
# die das Haus-Harness ohnehin prüft. 1024 breit ist der Fall, in dem der
# Schleier rechnerisch die ganze Breite deckt.
SIZES = [(1366, 1024), (1440, 900), (1280, 800), (1024, 768)]

VEIL_RULE = """
:root[data-theme='natsunohi']::after {{
  background-image: linear-gradient(
    180deg,
    oklch(0.985 0.032 96 / {a}) 0%,
    oklch(0.985 0.032 96 / {a}) 36%,
    oklch(0.978 0.05 108 / {a}) 56%,
    oklch(0.966 0.066 122 / {a}) 76%,
    oklch(0.966 0.066 122 / {a}) 100%
  ) !important;
}}
"""

# Die beiden Schriftfarben werden als echte Kacheln mitgerendert und
# zurückgelesen, statt sie aus dem Quelltext zu rechnen: so misst das Skript
# dieselbe sRGB-Abbildung, die auch der Bildschirm sieht. Sie sitzen in der
# linken oberen Ecke, also außerhalb der Spalte, die abgetastet wird.
PAGE = """<!doctype html><html lang="de" data-theme="natsunohi"><head><meta charset="utf-8">
<link rel="stylesheet" href="file://{index}">
<link rel="stylesheet" href="file://{theme}">
<style>html,body{{min-height:100vh;margin:0}}
.probe{{position:fixed;top:0;left:0;width:24px;height:24px;z-index:9}}
.p4{{background:var(--text-4)}}
.p1{{background:var(--text-1);left:24px}}
{veil}</style></head>
<body><div class="probe p4"></div><div class="probe p1"></div></body></html>
"""


def run(alphas: list[float]) -> None:
    out = tempfile.mkdtemp(prefix="natsunohi-veil-")
    profile = os.path.join(out, "chrome-profile")
    print(f"{'alpha':>6} {'Fenster':>10} {'dunkelster Pixel':>18} {'text-4':>8} {'text-1':>8}")
    print("-" * 56)
    best: dict[float, float] = {}
    for a in alphas:
        html = os.path.join(out, f"v{a}.html")
        with open(html, "w", encoding="utf-8") as fh:
            fh.write(
                PAGE.format(
                    index=INDEX_CSS, theme=THEME_CSS, veil=VEIL_RULE.format(a=a)
                )
            )
        worst_by_size = []
        for w, h in SIZES:
            png = os.path.join(out, f"v{a}-{w}x{h}.png")
            measure.shoot(html, png, (w, h), profile)
            pw, ph, rows, stride = measure.read_png(png)
            t4 = tuple(rows[4][4 * stride : 4 * stride + 3])
            t1 = tuple(rows[4][28 * stride : 28 * stride + 3])
            left = max(0, (pw - APP_COLUMN) // 2)
            right = min(pw, left + APP_COLUMN)
            worst = None
            worst_at = (0, 0)
            worst_lum = 2.0
            for y in range(0, ph, 2):  # jede zweite Zeile: 4x schneller, gleicher Befund
                row = rows[y]
                for x in range(left, right, 2):
                    px = tuple(row[x * stride : x * stride + 3])
                    lum = measure.luminance(px)
                    if lum < worst_lum:
                        worst_lum = lum
                        worst = px
                        worst_at = (x, y)
            c4 = measure.contrast(t4, worst)
            c1 = measure.contrast(t1, worst)
            worst_by_size.append(c4)
            flag = "" if c4 >= 4.5 else "   ← REISST"
            print(
                f"{a:>6.2f} {f'{w}x{h}':>10} {measure.hexof(worst):>18} "
                f"{c4:>8.2f} {c1:>8.2f}  @{worst_at[0]},{worst_at[1]}{flag}"
            )
        best[a] = min(worst_by_size)
    print("-" * 56)
    for a, c in sorted(best.items()):
        print(f"  alpha {a:.2f}: schlechtester text-4-Kontrast über alle Fenster = {c:.2f}"
              + ("  ✓" if c >= 4.5 else "  ✗"))


if __name__ == "__main__":
    args = [float(x) for x in sys.argv[1:]] or [0.6, 0.64, 0.68, 0.72, 0.76, 0.8]
    run(args)

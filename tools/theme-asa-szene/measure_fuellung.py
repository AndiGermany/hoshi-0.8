#!/usr/bin/env python3
"""Wie VOLL ist das Bild -- in Tonwert-Streuung je senkrechtem Streifen.

Regie v2, Fehler 1 war "Randstreifen um ein Loch": Motive an den Kanten, Mitte
leer. Das laesst sich sehen, aber ein Auge streitet, und eine Zahl nicht. Diese
Probe teilt das Bild in senkrechte Streifen und meldet je Streifen, wie weit
die Helligkeit darin streut.

WARUM STREUUNG UND NICHT HELLIGKEIT:
Eine leere Flaeche und eine volle Flaeche koennen dieselbe MITTLERE Helligkeit
haben -- die leere ist ueberall gleich, die volle hat Dunkles und Helles
nebeneinander. Genau das ist der Unterschied zwischen "da ist etwas" und "da
ist eine Wand". Gemessen wird deshalb die Standardabweichung der Leuchtdichte
und die Spannweite (p05..p95, nicht min/max -- ein einzelner schwarzer Pixel
soll keinen leeren Streifen retten).

LESART: Ein Streifen unter ~8 Zaehlwerten Streuung ist fuer das Auge eine
Flaeche. Die Aussenstreifen (Rollbild, Fenster) sind die Referenz: so voll wie
die muss die Mitte werden, sonst ist das Bild wieder ein Rahmen um ein Loch.

Aufruf: python3 tools/theme-asa-szene/measure_fuellung.py <png> [streifen]
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))), "theme-contrast"))
from png import read_png  # noqa: E402


def luma(r, g, b):
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    path = sys.argv[1]
    bands = int(sys.argv[2]) if len(sys.argv) > 2 else 8
    w, h, ch, px = read_png(path)

    print(f"{os.path.basename(path)}  {w}x{h}")
    print(f"{'Streifen':>16}  {'Mittel':>7}  {'Streuung':>8}  "
          f"{'p05..p95':>10}  Urteil")
    for b in range(bands):
        x0 = b * w // bands
        x1 = (b + 1) * w // bands
        vals = []
        for y in range(0, h, 2):          # jede zweite Zeile reicht
            base = y * w * ch
            for x in range(x0, x1, 2):
                i = base + x * ch
                vals.append(luma(px[i], px[i + 1], px[i + 2]))
        vals.sort()
        n = len(vals)
        mean = sum(vals) / n
        var = sum((v - mean) ** 2 for v in vals) / n
        sd = var ** 0.5
        p05, p95 = vals[n * 5 // 100], vals[n * 95 // 100]
        verdict = "FLAECHE" if sd < 8 else ("duenn" if sd < 14 else "voll")
        print(f"{x0:>7}..{x1:<7}  {mean:7.1f}  {sd:8.1f}  "
              f"{p05:4.0f}..{p95:<5.0f}  {verdict}")


if __name__ == "__main__":
    main()

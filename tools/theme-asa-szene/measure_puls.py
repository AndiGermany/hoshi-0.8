#!/usr/bin/env python3
"""Wie viel bewegt sich zwischen zwei Frames -- in Prozent bewegter Bildpunkte.

Regie v2, Lektion 6: "Herzschlag messbar machen (% bewegter Bildpunkte zwischen
Frames angeben)". Die Piloten haben 32 % (natsunohi, Wiese) und 2,9 %
(nagareboshi, gesamt) gemeldet -- dezent gegen mutig, aber beide belegt.

WARUM EINE SCHWELLE UND NICHT "ungleich":
Ein Pixel, der sich um einen Zaehlwert aendert, ist kein Herzschlag, sondern
Rundung im Compositing. Gezaehlt wird ab einer Differenz, die ein Auge auf
einer hellen Flaeche ueberhaupt bemerken kann; die Voreinstellung ist 3 von 255
pro Kanal, was auf diesem Thema (fast alles zwischen 220 und 255) grosszuegig
ist, aber nicht geschenkt.

Zusaetzlich wird die STAERKSTE Aenderung gemeldet. 40 % der Flaeche um je einen
Zaehlwert zu heben ist naemlich dieselbe Zahl wie eine echte Bewegung, sieht
aber nach nichts aus -- erst beide Zahlen zusammen sind eine Aussage.

Aufruf: python3 tools/theme-asa-szene/measure_puls.py [frames-verzeichnis]
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))), "theme-contrast"))
from png import read_png  # noqa: E402

THRESH = 3


def diff(a_path, b_path, box=None):
    wa, ha, ca, pa = read_png(a_path)
    wb, hb, cb, pb = read_png(b_path)
    if (wa, ha, ca) != (wb, hb, cb):
        raise SystemExit(f"Frames unvergleichbar: {wa}x{ha}x{ca} "
                         f"vs {wb}x{hb}x{cb}")
    x0, y0, x1, y1 = box or (0, 0, wa, ha)
    x1, y1 = min(x1, wa), min(y1, ha)
    moved = total = peak = 0
    for y in range(y0, y1):
        base = y * wa * ca
        for x in range(x0, x1):
            i = base + x * ca
            d = max(abs(pa[i] - pb[i]), abs(pa[i + 1] - pb[i + 1]),
                    abs(pa[i + 2] - pb[i + 2]))
            total += 1
            if d > peak:
                peak = d
            if d >= THRESH:
                moved += 1
    return 100.0 * moved / total, peak


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "frames")
    pairs = (("t1000", "t4000"), ("t4000", "t7000"), ("t7000", "t10000"),
             ("t1000", "t10000"))
    # Der Dampf steht im linken freien Streifen, der Lichtfleck in der Mitte.
    # Getrennt messen, sonst verschluckt die grosse ruhige Flaeche beide.
    zones = {"gesamt": None,
             "Dampf (links unten)": (0, 480, 300, 900),
             "Lichtfleck (Mitte unten)": (380, 620, 1180, 1024)}
    for name, box in zones.items():
        print(f"\n{name}:")
        for a, b in pairs:
            pa = os.path.join(d, a + ".png")
            pb = os.path.join(d, b + ".png")
            if not (os.path.exists(pa) and os.path.exists(pb)):
                print(f"  {a} -> {b}: FEHLT")
                continue
            pct, peak = diff(pa, pb, box)
            print(f"  {a} -> {b}: {pct:5.1f} % bewegt, staerkste Aenderung "
                  f"{peak:3d}/255")


if __name__ == "__main__":
    main()

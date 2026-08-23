"""Der BLICK auf amayadori — was das Auge sieht, in Zahlen.

Die Regie v2 verlangt zweierlei, das ein Kontrast-Messer nicht liefert: einen
SICHTBAREN Herzschlag (Regel 4/6: „% bewegter Bildpunkte angeben") und ein Bild,
dessen Mitte SZENE ist und kein Panel. Beides ist messbar, und beides ist am
19./20.08. genau deshalb schiefgegangen, weil niemand es gemessen hat.

Vier Messungen auf der Frame-Serie (tools/theme-contrast/frames.sh amayadori):

1. HERZSCHLAG — Anteil der Bildpunkte, die sich zwischen zwei Frames um mehr
   als `EPS` Stufen aendern. Das ist der fallende Regen. Getrennt ausgewiesen
   fuer das ganze Bild und fuer das Strassenband, weil der Pfuetzen-Schimmer
   eine eigene, langsamere Uhr hat als der Regen.

2. WARM/KALT im Strassenband — Anteil der Punkte mit R deutlich ueber B. Regen
   ist blau-grau; warm sind nur die Fenster und das, was sie ins Wasser werfen.
   Ein Strassenband, das mehrheitlich warm ist, IST der Sepia-Teppich, den Andi
   und die Vorgaenger-Hand beanstandet haben. Zielbild: klar kalt dominiert,
   warme Punkte sind die Minderheit (die Spiegelungen der Fenster).

3. DIE NAHT — der Helligkeitssprung ueber die Spaltenkante. Gemessen wird das
   groesste Gefaelle zwischen benachbarten 8-px-Saeulen im Uebergangsbereich.
   Ein Schleier, der als Rechteck auf dem Bild liegt, erzeugt hier eine Stufe;
   ein Schleier, der die Szene durchscheinen laesst, erzeugt eine Rampe.
   Faustzahl: Sprung > 6 Stufen je 8 px liest das Auge als Kante.

4. TONSPREIZUNG im Strassenband — Perzentile der Helligkeit. Die Piloten-Lehre
   (REZEPT D2) sagt: dunkle Themen muessen die Tonstufen SPREIZEN, sonst ist die
   Nacht eine Masse. Eine schmale Spanne ist der Befund „flach".

    python3 tools/theme-contrast/look_amayadori.py
"""

from __future__ import annotations

import sys
from pathlib import Path

from png import read_png

HERE = Path(__file__).resolve().parent
FRAMES = HERE / 'frames'
TIMES = (1, 4, 7, 10)

EPS = 6           # Stufen Unterschied, ab denen ein Punkt als „bewegt" zaehlt
WARM_EPS = 10     # R minus B, ab dem ein Punkt als warm zaehlt
COLUMN = 920      # die Lesespalte — dieselbe Zahl wie --amayadori-dry


def load(t):
    # png.read_png liefert (breite, hoehe, kanaele, FLACHER Puffer) — kein
    # Zeilen-Array. Der Index eines Punktes ist darum (y*w + x)*ch.
    w, h, ch, buf = read_png(str(FRAMES / f'amayadori-t{t}s.png'))
    return w, h, ch, buf


def lum(r, g, b):
    """Schnelle wahrgenommene Helligkeit — hier reicht die ganzzahlige Naeherung."""
    return (r * 299 + g * 587 + b * 114) // 1000


def main():
    frames = {}
    for t in TIMES:
        try:
            frames[t] = load(t)
        except FileNotFoundError:
            print(f'FEHLT: frames/amayadori-t{t}s.png — erst frames.sh laufen lassen')
            return 1

    w, h, ch, _ = frames[TIMES[0]]
    print(f'Frames {w}x{h}, {len(TIMES)} Zeitpunkte\n')

    # Das Strassenband: die SVG ist 1600x1000 und liegt `cover`/`center bottom`.
    # Die nasse Strasse beginnt dort bei y=706 von 1000, also bei 70,6 % der
    # SVG-Hoehe — im Frame gerechnet ab der Unterkante.
    svg_h_in_frame = w * (1000 / 1600)
    street_top = int(h - svg_h_in_frame * (1 - 706 / 1000))
    street_top = max(0, min(h - 2, street_top))
    print(f'Strassenband: y {street_top}…{h}  ({h - street_top} Zeilen)\n')

    # ── 1 · HERZSCHLAG ────────────────────────────────────────────────────────
    print('HERZSCHLAG — bewegte Bildpunkte zwischen den Frames')
    pairs = list(zip(TIMES, TIMES[1:]))
    for a, b in pairs:
        _, _, _, ba = frames[a]
        _, _, _, bb = frames[b]
        moved = total = 0
        moved_st = total_st = 0
        for y in range(0, h, 2):
            base = y * w * ch
            for x in range(0, w, 2):
                ia = base + x * ch
                d = (abs(ba[ia] - bb[ia])
                     + abs(ba[ia + 1] - bb[ia + 1])
                     + abs(ba[ia + 2] - bb[ia + 2]))
                total += 1
                hit = d > EPS
                if hit:
                    moved += 1
                if y >= street_top:
                    total_st += 1
                    if hit:
                        moved_st += 1
        pct = 100 * moved / max(1, total)
        pct_st = 100 * moved_st / max(1, total_st)
        print(f'  t{a}s→t{b}s   gesamt {pct:5.1f} %   Strasse {pct_st:5.1f} %')

    # ── 2 · WARM/KALT ─────────────────────────────────────────────────────────
    print('\nWARM/KALT im Strassenband (R-B > %d = warm)' % WARM_EPS)
    _, _, _, buf = frames[TIMES[0]]
    warm = cold = neutral = 0
    for y in range(street_top, h, 2):
        base = y * w * ch
        for x in range(0, w, 2):
            i = base + x * ch
            d = buf[i] - buf[i + 2]
            if d > WARM_EPS:
                warm += 1
            elif d < -WARM_EPS:
                cold += 1
            else:
                neutral += 1
    tot = max(1, warm + cold + neutral)
    print(f'  warm {100*warm/tot:5.1f} %   kalt {100*cold/tot:5.1f} %   neutral {100*neutral/tot:5.1f} %')
    if 100 * warm / tot > 45:
        print('  ⚠ SEPIA-TEPPICH: das Strassenband ist mehrheitlich warm.')

    # ── 3 · PANEL ODER SZENE ──────────────────────────────────────────────────
    # Die eigentliche Frage der Regie ist NICHT „wie gross ist der Sprung an der
    # Kante" — ein heller Gegenstand (das Plakat rechts) erzeugt dort denselben
    # Sprung wie ein Schleierrand, die Zahl kann beides nicht unterscheiden; sie
    # hat in der ersten Fassung dieses Skripts prompt das Plakat bei x=1328
    # gemeldet statt der Spaltenkante bei x=1143.
    #
    # Die Frage ist: steht hinter dem Schleier noch eine SZENE? Ein Panel ist
    # flach — es hat, bezogen auf seine eigene Helligkeit, kaum noch Struktur.
    # Eine gedimmte Szene behaelt ihre Struktur, sie wird nur leiser. Gemessen
    # wird darum die mittlere waagerechte Nachbardifferenz (= lokale Zeichnung),
    # normiert auf die mittlere Helligkeit des jeweiligen Bereichs. Innen und
    # aussen aehnlich = dieselbe Szene, nur gedaempft. Innen deutlich aermer =
    # der Schleier hat die Zeichnung erschlagen.
    print('\nPANEL ODER SZENE — relative Zeichnung innen/aussen')
    left_edge = (w - COLUMN) // 2
    right_edge = left_edge + COLUMN

    def detail(x_from, x_to, y_from, y_to):
        grad = light = c = 0
        for y in range(y_from, y_to, 2):
            base = y * w * ch
            prev = None
            for x in range(x_from, x_to, 2):
                i = base + x * ch
                v = lum(buf[i], buf[i + 1], buf[i + 2])
                if prev is not None:
                    grad += abs(v - prev)
                    c += 1
                light += v
                prev = v
        n_px = max(1, (x_to - x_from) // 2 * ((y_to - y_from) // 2))
        return grad / max(1, c), light / n_px

    # Der Bildbereich, in dem ueberhaupt Szene steht (unter dem Vordach, ueber
    # dem unteren Rand) — oben ist absichtlich fast schwarz, das wuerde die
    # Zahlen nur verduennen.
    y0, y1 = int(h * 0.30), h
    d_in, l_in = detail(left_edge + 40, right_edge - 40, y0, y1)
    d_lf, l_lf = detail(4, max(6, left_edge - 20), y0, y1)
    d_rt, l_rt = detail(min(w - 6, right_edge + 20), w - 4, y0, y1)
    d_out = (d_lf + d_rt) / 2
    l_out = (l_lf + l_rt) / 2
    rel_in = d_in / max(0.001, l_in)
    rel_out = d_out / max(0.001, l_out)
    print(f'  innen   Zeichnung {d_in:5.2f}  Helligkeit {l_in:5.1f}  relativ {rel_in:.3f}')
    print(f'  aussen  Zeichnung {d_out:5.2f}  Helligkeit {l_out:5.1f}  relativ {rel_out:.3f}')
    quot = rel_in / max(0.001, rel_out)

    # Gegen WELCHE Zahl ist das zu halten? Nicht gegen 1,0 — das waere
    # arithmetisch unmoeglich. Der Schleier tut zweierlei zugleich: er
    # MULTIPLIZIERT die Zeichnung mit (1 - Deckkraft) und ADDIERT seine eigene
    # Helligkeit. Selbst wenn hinter der Spalte exakt dieselbe Szene stuende
    # wie daneben, faellt die relative Zeichnung darum auf
    #     (1-a) * l_aussen / (a * bg + (1-a) * l_aussen).
    # Diese Zahl ist die ehrliche Messlatte. Liegt der gemessene Wert darueber,
    # traegt die Mitte MEHR Struktur als die Raender — dann ist sie eine Szene,
    # die gedaempft wird, und kein Panel. (Die erste Fassung dieses Skripts
    # verglich gegen willkuerliche 0,55 und meldete darum „PANEL", wo die
    # Mitte in Wahrheit dichter war als die Flanken.)
    VEIL = 0.86                      # --amayadori-veil
    BG = lum(0x10, 0x0E, 0x0C)       # --bg-base #100e0c, die Farbe des Schleiers
    # rel_out ist d_out/l_out, die Messlatte ist darum direkt der Faktor, um den
    # der Schleier die relative Zeichnung selbst dann druecken wuerde, wenn
    # innen und aussen dasselbe Bild staende.
    latte = ((1 - VEIL) * l_out) / max(0.001, VEIL * BG + (1 - VEIL) * l_out)
    print(f'  Verhaeltnis innen/aussen: {quot:.2f}   '
          f'(Messlatte bei gleicher Szene: {latte:.2f})')
    print('  → ' + ('PANEL: innen ist die Zeichnung erschlagen.' if quot < latte * 0.9
                    else 'SZENE: die Mitte traegt mehr Struktur als die Flanken, '
                         'der Schleier daempft sie nur.'))

    # ── 4 · TONSPREIZUNG ──────────────────────────────────────────────────────
    print('\nTONSPREIZUNG im Strassenband')
    vals = []
    for y in range(street_top, h, 3):
        base = y * w * ch
        for x in range(0, w, 3):
            i = base + x * ch
            vals.append(lum(buf[i], buf[i + 1], buf[i + 2]))
    vals.sort()
    def pct_at(p):
        return vals[min(len(vals) - 1, int(len(vals) * p))]
    p5, p50, p95, p99 = pct_at(0.05), pct_at(0.5), pct_at(0.95), pct_at(0.99)
    print(f'  p5 {p5}   p50 {p50}   p95 {p95}   p99 {p99}   Spanne p5–p95 {p95 - p5}')
    if p95 - p5 < 24:
        print('  ⚠ FLACH: zu wenig Tonspreizung, das Band liest sich als eine Masse.')
    return 0


if __name__ == '__main__':
    sys.exit(main())

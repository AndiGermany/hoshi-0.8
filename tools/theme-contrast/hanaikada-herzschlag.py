"""Misst die zwei Herzschlaege von HANAIKADA als % bewegter Bildpunkte.

    python3 tools/theme-contrast/hanaikada-herzschlag.py

Regie v2 verlangt „% bewegter Bildpunkte zwischen Frames angeben". Bei diesem
Thema reicht EINE Zahl nicht, weil zwei verschiedene Uhren laufen und die eine
die andere in einer Gesamtzahl vollstaendig verdecken wuerde:

  Herzschlag 1 — der Bluetenfall. Wenige, kleine Formen ueber die ganze Buehne.
    Gemessen OBERHALB des fernen Ufers (y < 486 viewBox): dort ist die Szene
    gemalt und still, jede Aenderung dort ist eine fallende Bluete.
  Herzschlag 2 — das Bluetenfloss, der Namensgeber. Sieben Baender treiben durch
    das Wasserband zwischen fernem und nahem Ufer (y 601..948 viewBox).

Der Schwellwert 8/255 ist Absicht: was darunter liegt, sieht kein Auge, und der
Beweis soll sichtbare Bewegung zaehlen, nicht Rundungsrauschen.

Geometrie: viewBox 1600x1000, fernes Ufer y 486..601, nahes Ufer y 948..972.
Der 1600er Frame steht 1:1; der 1366er haengt mit `cover` und ist ueber die
Hoehe skaliert (1024/1000), darum derselbe Schnitt mal 1,024.
"""

import os
import sys

HIER = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HIER)
from png import read_png  # noqa: E402

FRAMES = os.path.join(HIER, 'frames')
THRESH = 8


def load(pfad):
    w, h, nch, buf = read_png(pfad)
    return w, h, nch, buf


def diff_zonen(a, b, y_wald_bis, y_wasser_von, y_wasser_bis):
    wa, ha, ca, ba = a
    wb, hb, cb, bb = b
    assert (wa, ha, ca) == (wb, hb, cb), 'Frames verschiedener Groesse'
    zaehler = {'wald': [0, 0], 'wasser': [0, 0], 'gesamt': [0, 0]}
    stride = wa * ca
    for y in range(ha):
        off = y * stride
        if y < y_wald_bis:
            zone = 'wald'
        elif y_wasser_von <= y < y_wasser_bis:
            zone = 'wasser'
        else:
            zone = None
        for x in range(wa):
            i = off + x * ca
            d = max(
                abs(ba[i] - bb[i]),
                abs(ba[i + 1] - bb[i + 1]),
                abs(ba[i + 2] - bb[i + 2]),
            )
            bewegt = 1 if d >= THRESH else 0
            zaehler['gesamt'][0] += bewegt
            zaehler['gesamt'][1] += 1
            if zone:
                zaehler[zone][0] += bewegt
                zaehler[zone][1] += 1
    return {k: 100.0 * v[0] / v[1] for k, v in zaehler.items()}


def lauf(praefix, skala, label):
    y_wald_bis = int(486 * skala)
    y_wasser_von = int(601 * skala)
    y_wasser_bis = int(948 * skala)
    bilder = {
        t: load(os.path.join(FRAMES, '%s-t%ds.png' % (praefix, t))) for t in (1, 4, 7, 10)
    }
    print('== %s (Wald 0..%d, Wasser %d..%d) ==' % (label, y_wald_bis, y_wasser_von, y_wasser_bis))
    for t0, t1 in ((1, 4), (4, 7), (7, 10), (1, 10)):
        r = diff_zonen(bilder[t0], bilder[t1], y_wald_bis, y_wasser_von, y_wasser_bis)
        print(
            '  t%-2d -> t%-2d   Herzschlag 1 Bluetenfall %5.2f %%   '
            'Herzschlag 2 Floss %5.2f %%   gesamt %5.2f %%'
            % (t0, t1, r['wald'], r['wasser'], r['gesamt'])
        )


if __name__ == '__main__':
    lauf('hanaikada-1600', 1.0, '1600x1000')
    lauf('hanaikada', 1.024, '1366x1024')

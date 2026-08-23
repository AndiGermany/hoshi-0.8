"""Zeichnet `frontend/public/themes/aoi-szene.svg` — die Szene des Themas Aoi (青).

DAS BILD: das Wasser von INNEN. Man steht auf dem Grund eines stillen Sees und
schaut nach oben: ganz oben die Unterseite der Oberflaeche mit dem Snell-Fenster
(dem hellen Kreis, durch den der Himmel wirklich hereinkommt — aussen herum
spiegelt die Oberflaeche das dunkle Wasser zurueck), darunter das offene Blau,
in das ein paar Lichtschaefte fallen, links ein Tangwald, rechts ein Riff, unten
der Grund. Kein Drama, keine Welle, kein Sturm: 青 ist die Ruhe des Blaus.

WARUM EIN SKRIPT UND KEINE HANDGESETZTEN PFADE: die Geometrie ist von Hand
gewaehlt (jede Kontrollstelle steht hier als Zahl), aber die Wiederholungen —
14 Tiefenbaender, 30 Kraeuselungen, 46 Schwebeteilchen — sind Serien. Sie einmal
zu erzeugen ist ehrlicher (und aenderbar) als sie einmal abzutippen. Der Zufall
ist mit festem Startwert gezaehmt: derselbe Aufruf ergibt dieselbe Datei.

AUFRUF:  python3 tools/theme-contrast/build_aoi_szene.py
"""

from __future__ import annotations

import random
from pathlib import Path

W, H = 1600, 1000
OUT = Path("frontend/public/themes/aoi-szene.svg")

rnd = random.Random(2608)  # 青 · fester Startwert, damit die Datei reproduzierbar ist


def f(x: float) -> str:
    """Kurze Zahl: 1 Nachkommastelle reicht bei 1600x1000 und spart ~20 % Datei."""
    s = f"{x:.1f}".rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


parts: list[str] = []


def add(s: str) -> None:
    parts.append(s)


# ── 1. DIE TIEFE ────────────────────────────────────────────────────────────
# 14 flache Baender, oben das hellste Wasser, unten die Tinte. Das ist die
# Luftperspektive unter Wasser: Blau verschluckt zuerst das Rot, dann alles.
# Flache Rechtecke statt eines Verlaufs — dieselbe Bauweise wie Asagiris
# Nebelstapel, und im SVG viel billiger als ein <linearGradient> pro Ebene.
DEPTH = [
    "#16243a", "#152239", "#142137", "#131f34", "#121d31", "#111b2d", "#101929",
    "#0f1725", "#0e1521", "#0d131d", "#0c1119", "#0b0f16", "#0a0d13", "#090b10",
]
band_h = H / len(DEPTH)
for i, col in enumerate(DEPTH):
    y = i * band_h
    add(f"<rect x='0' y='{f(y)}' width='{W}' height='{f(band_h + 1)}' fill='{col}'/>")


# ── 2. DIE OBERFLAECHE VON UNTEN ────────────────────────────────────────────
# Drei gewellte Baender am oberen Rand: die Unterseite der Wasserhaut. Sie ist
# nicht glatt, sondern traegt die lange, flache Duenung eines stillen Tages.
def wave_band(y0: float, amp: float, phase: float, height: float, fill: str, op: float) -> str:
    """Ein Band mit gewellter Oberkante — vier Halbwellen ueber die Breite."""
    step = W / 4
    d = [f"M-20 {f(y0 + amp)}"]
    for k in range(4):
        x0 = -20 + k * (W + 40) / 4
        x1 = -20 + (k + 1) * (W + 40) / 4
        cy = y0 + (amp if (k + phase) % 2 else -amp)
        d.append(f"Q {f((x0 + x1) / 2)} {f(cy - amp * 1.6)} {f(x1)} {f(y0 + amp * (1 if k % 2 else -1))}")
    d.append(f"L{W + 20} {f(y0 + height)} L-20 {f(y0 + height)} Z")
    _ = step
    return f"<path fill='{fill}' opacity='{op}' d='{' '.join(d)}'/>"


add(wave_band(150, 16, 0, 130, "#1b3050", 0.55))
add(wave_band(104, 13, 1, 120, "#22436c", 0.5))
add(wave_band(62, 10, 0, 110, "#2b5480", 0.45))

# Das SNELL-FENSTER: der Kegel, durch den das Tageslicht wirklich eintritt
# (48,6 Grad um die Senkrechte). Aussen herum spiegelt die Oberflaeche das
# dunkle Wasser — darum wird das Fenster hier als eine einzige, sehr breite und
# sehr flache Ellipse gebaut, deren Rand nicht scharf ist, sondern in drei
# Stufen ausblutet. Es sitzt bewusst nicht mittig (x 46 %), damit die Szene
# nicht symmetrisch und damit tot wird.
for rx, ry, op, col in ((520, 92, 0.16, "#3f74a8"), (380, 68, 0.16, "#4b84bb"), (232, 44, 0.14, "#5c95cd")):
    add(f"<ellipse cx='736' cy='96' rx='{rx}' ry='{ry}' fill='{col}' opacity='{op}'/>")


# ── 3. DIE KRAEUSELUNG — das eigentliche Wasserlicht ────────────────────────
# 30 duenne, liegende Boegen unter der Haut. Sie sind das, was man von unten
# von einer Welle sieht: keine Welle, sondern eine LINIE aus Licht, die mit der
# Tiefe laenger, flacher und leiser wird. Gezeichnet als Strich (stroke), nicht
# als Flaeche — eine Kraeuselung hat keine Dicke, nur einen Verlauf.
for i in range(30):
    t = i / 29
    y = 44 + t * t * 330 + rnd.uniform(-12, 12)
    span = 120 + t * 320 + rnd.uniform(-40, 60)
    x = rnd.uniform(-60, W + 60 - span)
    bow = (10 - t * 7) * (1 if rnd.random() < 0.5 else -1)
    wdt = 4.4 - t * 2.6
    op = round(max(0.05, 0.30 - t * 0.24) * rnd.uniform(0.7, 1.15), 3)
    add(
        f"<path stroke='#5ea0f2' stroke-width='{f(wdt)}' stroke-linecap='round' fill='none' "
        f"opacity='{op}' d='M{f(x)} {f(y)} Q {f(x + span / 2)} {f(y + bow)} {f(x + span)} {f(y)}'/>"
    )


# ── 4. DIE LICHTSCHAEFTE ────────────────────────────────────────────────────
# Vier Keile, die von der Oberflaeche in die Tiefe fallen — unten breiter als
# oben, weil sie sich im Streulicht oeffnen, und unten leiser, weil das Wasser
# sie frisst. Zwei stehen weit aussen (dort, wo kein Text steht), zwei weiter
# innen und entsprechend blass.
for x_top, x_bot, w_top, w_bot, op in (
    (150, 40, 54, 190, 0.09),
    (430, 372, 34, 128, 0.055),
    (1180, 1268, 30, 116, 0.05),
    (1430, 1548, 62, 210, 0.085),
):
    add(
        f"<path fill='#5ea0f2' opacity='{op}' d='M{f(x_top)} 58 L{f(x_top + w_top)} 58 "
        f"L{f(x_bot + w_bot)} 780 L{f(x_bot)} 780 Z'/>"
    )


# ── 5. DER TANGWALD LINKS ───────────────────────────────────────────────────
# Sieben Baender, die vom Grund aufsteigen und sich oben zur Seite legen. Jedes
# ist ein geschlossener Pfad aus zwei Bezier-Kurven (Vorder- und Rueckkante),
# der sich nach oben verjuengt — kein Strich, damit die Silhouette wirklich
# deckend ist. Sie stehen IM Grund (Ueberlappungsregel aus Hanashigure: was ein
# Koerper sein soll, muss sich um >= 3 px ueberlappen).
def frond(x: float, top: float, lean: float, width: float, col: str, op: float) -> str:
    bot = 1010.0
    mid = (top + bot) / 2
    d = (
        f"M{f(x - width / 2)} {f(bot)} "
        f"C {f(x - width / 2 + lean * 0.15)} {f(mid)} {f(x + lean * 0.6 - width * 0.3)} {f(mid - 90)} "
        f"{f(x + lean)} {f(top)} "
        f"C {f(x + lean * 0.6 + width * 0.3)} {f(mid - 60)} {f(x + width / 2 + lean * 0.15)} {f(mid)} "
        f"{f(x + width / 2)} {f(bot)} Z"
    )
    return f"<path fill='{col}' opacity='{op}' d='{d}'/>"


for x, top, lean, wdt, col, op in (
    (86, 250, 96, 42, "#0a1119", 0.95),
    (152, 396, 74, 34, "#0c1420", 0.9),
    (36, 430, 58, 30, "#0b121b", 0.9),
    (214, 300, 118, 38, "#091019", 0.95),
    (268, 512, 66, 26, "#0d1622", 0.85),
    (128, 604, 44, 22, "#0e1824", 0.8),
    (312, 660, 52, 20, "#0f1926", 0.75),
):
    add(frond(x, top, lean, wdt, col, op))


# ── 6. DAS RIFF RECHTS ──────────────────────────────────────────────────────
# Ein Block, der aus dem rechten Rand in die Szene ragt, mit drei Grasbueschen
# auf dem Ruecken. Bewusst kantiger als der Tang: Stein gegen Pflanze.
add(
    "<path fill='#0a111a' opacity='0.95' d='M1600 1010 L1600 452 "
    "C 1540 470 1512 520 1470 566 C 1432 608 1404 596 1372 638 "
    "C 1340 680 1348 726 1322 772 C 1300 812 1272 838 1258 1010 Z'/>"
)
add(
    "<path fill='#0d1522' opacity='0.8' d='M1600 1010 L1600 610 "
    "C 1552 632 1520 686 1486 742 C 1452 798 1436 878 1428 1010 Z'/>"
)
for x, top, lean, wdt in ((1392, 590, -34, 16), (1436, 542, -22, 14), (1348, 690, -26, 13)):
    add(frond(x, top, lean, wdt, "#0b1220", 0.9))


# ── 7. DER GRUND ────────────────────────────────────────────────────────────
# Eine sehr flache Kuppe, fast schwarz: der Boden ist in dieser Tiefe kein Bild
# mehr, sondern nur noch die Stelle, an der das Blau aufhoert.
add(
    "<path fill='#070a0f' d='M-20 1010 L-20 940 "
    "C 260 906 520 928 820 918 C 1120 908 1360 934 1620 908 L1620 1010 Z'/>"
)
add(
    "<path fill='#090d14' opacity='0.7' d='M-20 1010 L-20 968 "
    "C 300 946 560 962 900 954 C 1200 947 1400 962 1620 944 L1620 1010 Z'/>"
)


# ── 8. SCHWEBETEILCHEN ──────────────────────────────────────────────────────
# 46 Punkte, die im Licht stehen. Sie sind der Grund, warum ein Lichtschaft
# ueberhaupt sichtbar ist — darum sitzen sie dicht an den Schaeften und duenn
# dazwischen, und sie werden mit der Tiefe kleiner und leiser.
for _ in range(46):
    near_shaft = rnd.random() < 0.62
    x = rnd.choice([rnd.gauss(120, 110), rnd.gauss(1470, 130)]) if near_shaft else rnd.uniform(0, W)
    x = min(max(x, 6), W - 6)
    y = rnd.uniform(90, 880)
    t = (y - 90) / 790
    r = round(rnd.uniform(1.4, 3.4) * (1 - t * 0.45), 2)
    op = round(rnd.uniform(0.12, 0.34) * (1 - t * 0.62), 3)
    add(f"<circle cx='{f(x)}' cy='{f(y)}' r='{r}' fill='#9dc4f5' opacity='{op}'/>")


HEAD = (
    "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1600 1000' width='1600' height='1000' "
    "preserveAspectRatio='xMidYMin slice'>"
    "<!-- AOI (青) - das Wasser von innen. Flache Vektor-Szene fuer das Theme Aoi,\n"
    "     erzeugt von tools/theme-contrast/build_aoi_szene.py (fester Startwert 2608).\n"
    "     KEINE Animation in dieser Datei: ein Hintergrundbild-SVG wird bei jedem Frame\n"
    "     neu gerastert. Das Licht atmet eine Ebene hoeher, in aoi.css, als Deckkraft\n"
    "     auf zwei zusammengesetzten Ebenen - siehe dort 'DAS LICHT ATMET'.\n"
    "     Ankerpunkt ist OBEN (xMidYMin): die Wasserhaut darf nie angeschnitten werden,\n"
    "     der Grund schon. -->"
)

svg = HEAD + "".join(parts) + "</svg>"
OUT.write_text(svg, encoding="utf-8")
print(f"{OUT}: {len(svg.encode('utf-8')) / 1024:.1f} KB, {len(parts)} Formen")

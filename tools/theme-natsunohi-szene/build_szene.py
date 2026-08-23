#!/usr/bin/env python3
"""Baut frontend/public/themes/natsunohi-szene.svg.

WARUM EIN GENERATOR: Die erste Fassung war von Hand geschrieben und hatte
38 Pfade — deshalb las die Wiese als flache Zacken-Bänder und die Baumkrone
als Klotz. Ein Blätterdach der Komorebi-Klasse braucht einige hundert
Einzelformen; die schreibt man nicht von Hand, und schon gar nicht zweimal.
Der Generator ist deterministisch (fester Seed): dieselbe Datei bei jedem
Lauf, also diffbar und wiederholbar.

MASS-VERTRAG (mit natsunohi.css abgestimmt):
  viewBox 1600x1000, geladen mit `background-size: cover; position: center bottom`.
  * Fenster 1366x1024 (die Mini-Buehne der Regie): hoehen-getrieben, Skala
    1.024, sichtbar ist viewBox-x 133..1467 — die aeusseren 133 Einheiten je
    Seite fallen weg. Vertikal faellt nichts weg.
  * Fenster 1920x1080: breiten-getrieben, Skala 1.2, oben fallen 147
    viewBox-Einheiten weg.
  => Alles Bildwichtige lebt in x 140..1460 und y 150..1000.

  Die 920-px-Lesespalte deckt bei jedem Fenster >= 1024px Hoehe die
  viewBox-Spanne x 350..1250 ab. Links (140..350) und rechts (1250..1460)
  bleiben unverschleiert — dort stehen die lauten Motive (Baumkrone links,
  Wolkenturm rechts), waehrend Wiese, Huegel und Sonnenblumen HINTER der
  Spalte durchlaufen (Regie v2, Regel 1).

WERTE-VERTRAG (der eigentliche Fix): Der Schleier ueber der Lesespalte
laesst 20 % der Zeichnung stehen. Damit schrumpft jeder HELLIGKEITSUNTER-
SCHIED im Bild dort auf ein Fuenftel — nicht die Farbe verschwindet unter
einem Schleier, sondern der Kontrast. Ukiyos Welle ueberlebt ihren
(gleich starken!) Schleier, weil sie intern L 0.33 bis L 0.96 spannt.
Die erste Fassung dieser Wiese spannte L 0.80 bis L 0.92; ein Fuenftel
davon ist 0.014, und das sieht kein Mensch — daher 'ausgewaschen'.
Diese Fassung spannt L 0.33 (Schattenfuss der Grasstufen, Kronentiefe)
bis L 0.87 (Sonnenfleck) und setzt die Sonnenblumen als Wertkontrast IN
SICH (Goldblatt L 0.79 gegen fast schwarze Mitte L 0.36) quer ueber die
volle Breite. Sie sind das, was man unter dem Schleier als erstes
wiedererkennt. Naeheres in RESULT.md.

ZWEI-EBENEN-VERTRAG: natsunohi.css laedt DIESE EINE Datei zweimal und
teilt sie per Maske am Horizont (Viewport 65,5..68 %, viewBox-y 670..696):
  * body::after  = alles OBERHALB (Himmelskoerper, Huegel, Baum, ferne Wiese)
  * :root::before = alles UNTERHALB (hohe Halme, nahe Sonnenblumen)
Die untere Ebene wird staerker geschert als die obere — Wind am Boden.
Darum darf KEIN einzelnes, erkennbares Objekt die Naht bei y 670..696
kreuzen: es erschiene sonst doppelt und gegeneinander versetzt. Grastextur
darf die Naht kreuzen (dort ist der Versatz Textur, kein Geist).
"""

from __future__ import annotations

import math
import os
import random

W, H = 1600, 1000

# Die Naht zwischen den beiden CSS-Ebenen (s. Modulkopf).
SEAM_TOP, SEAM_BOT = 670, 696

random.seed(20260819)


def r(a: float, b: float) -> float:
    return random.uniform(a, b)


def n(x: float, d: int = 1) -> str:
    """Zahl kurz: das SVG-Budget sind 80 KB, und jede Nachkommastelle kostet."""
    s = f"{round(x, d):.{d}f}".rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def halm(x: float, y: float, s: float, ang: float) -> str:
    """Ein Grashalm als PFADSEGMENT, nicht als <use>.

    Rechnung, warum: ein `<use href='#h' transform='translate(..)rotate(..)
    scale(..)'/>` kostet 69 Byte. Dieselbe Sichel als zwei quadratische Boegen
    mit eingebackener Neigung und Groesse, angehaengt an das d-Attribut einer
    gemeinsamen <path>-Gruppe, kostet 38 — und die Gruppe traegt Fuellfarbe und
    Element-Rahmen nur EINMAL statt 500-mal. Bei ~550 Halmen sind das 17 KB von
    einem 80-KB-Budget. Die Blattbueschel der Krone bleiben <use>: ihre Form
    ist 110 Byte lang, dort lohnt die Wiederverwendung wirklich.

    Aufbau: Fusspunkt (x,y), Breite w, Laenge L, Spitze um `ang` geneigt. Zwei
    Boegen, hin ueber die eine Flanke zur Spitze und zurueck ueber die andere —
    daher die Sichelform, die eine Gerade nicht hat.
    """
    L = 79.0 * s
    w = 3.4 * s
    dx = math.tan(math.radians(ang)) * L
    return (
        f"M{x - w:.0f} {y:.0f}"
        f"q{dx * 0.35 + w * 0.1:.0f} {-L * 0.55:.0f} {dx + w:.0f} {-L:.0f}"
        f"q{w * 0.9 - dx * 0.65:.0f} {L * 0.5:.0f} {w - dx:.0f} {L:.0f}z"
    )


def xf(px: float, py: float, rot: float, s: float) -> str:
    """Kompakter Transform fuer die ~800 <use>-Instanzen.

    Bei dieser Stueckzahl entscheiden Zeichen ueber das 80-KB-Budget: ganze
    Zahlen bei Position und Drehung (ein halbes viewBox-Pixel sieht niemand,
    ein Grad Drehung an einem Grashalm auch nicht), zwei Stellen nur bei der
    Skala, weil die sich auf die Groesse durchmultipliziert. `scale` faellt
    ganz weg, wenn es 1 ist."""
    t = f"translate({px:.0f} {py:.0f})"
    if abs(rot) >= 0.5:
        t += f"rotate({rot:.0f})"
    if abs(s - 1.0) >= 0.005:
        t += f"scale({s:.2f})"
    return t


out: list[str] = []
add = out.append


# ── Die Palette ──────────────────────────────────────────────────────────────
# Sechs Gruenstufen von der fernsten Kuppe bis zum naechsten Halm, dazu Baum,
# Stamm, Sonnenblume, Wolke. Luftperspektive: fern = heller und blauer, nah =
# dunkler und satter. KEIN Ton ueber L~0.72 in der Wiese (s. Werte-Vertrag).
# DER WERTUMFANG IST DER GANZE PUNKT. Unter dem Schleier bleiben nur 20 % der
# Zeichnung stehen, also schrumpft JEDER Helligkeitsunterschied im Bild auf ein
# Fuenftel. Ukiyos Welle ueberlebt ihren Schleier, weil sie intern von L 0.33
# (Tiefblau) bis L 0.96 (Gischt) reicht — ein Umfang von 0.62. Die erste
# Fassung dieser Wiese reichte von L 0.80 bis L 0.92, also 0.12; unter dem
# Schleier waren das 0.014, und das sieht kein Mensch. Genau darum sah sie
# ausgewaschen aus.
# Diese Palette spannt L 0.33 (tiefster Schatten) bis L 0.87 (Sonnenfleck) und
# gibt der Wiese damit denselben Umfang wie Ukiyo der Welle. Der Preis ist,
# dass die Zeichnung roh betrachtet fast zu kontrastreich wirkt — sie ist aber
# nie roh zu sehen: ueber der Lesespalte liegt immer der Schleier, daneben
# steht die Zeichnung ohnehin allein.
FERN_3 = "#6fae94"  # fernste Kuppe, schon halb Dunst
FERN_2 = "#3f8f61"
FERN_1 = "#20693e"  # Baumreihe am Horizont
WIESE_4 = "#5fae3a"  # ferne Wiese, hinter der Naht
WIESE_3 = "#469c26"
WIESE_2 = "#34871b"
WIESE_1 = "#277513"  # Vordergrund
WIESE_0 = "#18580d"  # der naechste, tiefste Halm
WIESE_SCHATTEN = "#13470a"  # der Fuss jeder Stufe: was die Tiefe traegt
SONNENFLECK = "#d6f76f"  # Licht auf der Wiese — der helle Pol des Umfangs
SONNENFLECK_2 = "#f2ffa6"  # wo die Sonne wirklich draufsteht

KRONE = ["#1a4f16", "#20601a", "#28711e", "#328722", "#3fa529", "#57c633", "#8ae84f"]
STAMM = "#5c3f27"
STAMM_D = "#34220f"
AST = "#4a3220"

BLUME = "#ffc03a"
BLUME_D = "#f2a61f"
BLUME_M = "#7a4d1f"
BLUME_MD = "#59351a"
STIEL = "#3c8a22"

WOLKE = "#ffffff"
WOLKE_S = "#dde5ee"
WOLKE_W = "#f7ead6"


# ── Kopf ─────────────────────────────────────────────────────────────────────
add(
    f"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 {W} {H}' "
    f"width='{W}' height='{H}' preserveAspectRatio='xMidYMax slice'>"
)
add(
    "<!-- NATSU NO HI (夏の日), zweite Fassung. Erzeugt von "
    "tools/theme-natsunohi-szene/build_szene.py — NICHT von Hand aendern, "
    "sondern den Generator aendern und neu bauen.\n"
    "     BILD: ein grosser Baum links mit echtem Blattwerk, ein Wolkenturm "
    "(入道雲) rechts, drei Huegelruecken dazwischen und ueber die volle "
    "Breite eine hohe Sommerwiese mit Sonnenblumen bis an den unteren "
    "Bildrand. Der Himmel wird NICHT hier gemalt (dort ist die Zeichnung "
    "transparent), sondern vom Atmosphaeren-Token des Themes.\n"
    "     KEINE ANIMATION IN DIESER DATEI: ein als background-image "
    "geladenes SVG ist ein eigenes Dokument, und prefers-reduced-motion des "
    "Einbetters kommt dort nicht zuverlaessig an. Alles, was sich bewegt, "
    "bewegt sich in natsunohi.css, wo der globale Brems-Schalter greift. -->"
)

# ── defs: die Bausteine, die hunderte Male verwendet werden ──────────────────
add("<defs>")

# Ein Blattbueschel der Krone: unrunde Traube, nicht Kreis (das war v1s Fehler).
add(
    "<path id='b' d='M0-15c8-6 19-5 24 2 8-2 15 3 15 11 4 4 3 12-3 15-1 8-9 12-17 9"
    "-6 6-17 5-22-2-9 1-16-5-15-13-5-5-4-15 3-18 2-6 9-9 15-4z'/>"
)
add(
    "<path id='c' d='M0-12c7-7 18-8 24-2 7-4 16-1 18 7 6 2 8 10 4 15 1 8-7 14-15 12"
    "-5 7-15 8-21 2-8 2-15-4-14-12-4-6-1-14 4-16z'/>"
)

# Die Sonnenblume, einmal gezeichnet, danach nur noch <use>.
# Bluete face-on (sfa) und leicht gedreht (sfb).
for sid, rx, ry, cnt in (("sfa", 46.0, 44.0, 17), ("sfb", 44.0, 30.0, 15)):
    add(f"<g id='{sid}'>")
    petals = []
    for k in range(cnt):
        a = 2 * math.pi * k / cnt + (0.12 if sid == "sfb" else 0.0)
        ca, sa = math.cos(a), math.sin(a)
        lo, li = 1.0, 0.40
        tipx, tipy = ca * rx * lo, sa * ry * lo
        b1x, b1y = -sa * rx * 0.20, ca * ry * 0.20
        m1x, m1y = ca * rx * 0.62 - sa * rx * 0.20, sa * ry * 0.62 + ca * ry * 0.20
        m2x, m2y = ca * rx * 0.62 + sa * rx * 0.20, sa * ry * 0.62 - ca * ry * 0.20
        petals.append(
            f"M{n(ca*rx*li)} {n(sa*ry*li)}"
            f"Q{n(m1x)} {n(m1y)} {n(tipx)} {n(tipy)}"
            f"Q{n(m2x)} {n(m2y)} {n(-b1x*li*2.5)} {n(-b1y*li*2.5)}Z"
        )
        del b1x, b1y
    add(f"<path fill='{BLUME}' d='{''.join(petals)}'/>")
    # zweite, kuerzere Petalenreihe: gibt der Bluete Tiefe statt Sternform
    petals2 = []
    for k in range(cnt):
        a = 2 * math.pi * (k + 0.5) / cnt
        ca, sa = math.cos(a), math.sin(a)
        petals2.append(
            f"M0 0Q{n(ca*rx*0.5-sa*rx*0.16)} {n(sa*ry*0.5+ca*ry*0.16)} "
            f"{n(ca*rx*0.78)} {n(sa*ry*0.78)}"
            f"Q{n(ca*rx*0.5+sa*rx*0.16)} {n(sa*ry*0.5-ca*ry*0.16)} 0 0Z"
        )
    add(f"<path fill='{BLUME_D}' d='{''.join(petals2)}'/>")
    add(f"<ellipse rx='{n(rx*0.40)}' ry='{n(ry*0.40)}' fill='{BLUME_M}'/>")
    add(f"<ellipse rx='{n(rx*0.26)}' ry='{n(ry*0.26)}' fill='{BLUME_MD}'/>")
    add("</g>")

# Ein Blatt am Sonnenblumenstiel.
add("<path id='sl' d='M0 0c14-14 34-18 46-10-10 12-30 18-46 10z'/>")

add("</defs>")


# ── 1. DER WOLKENTURM (入道雲) ────────────────────────────────────────────────
# Das Motiv des rechten Seitenraums. Aufgebaut wie eine echte Cumulus-
# congestus: ein breiter, flacher Fuss, darueber immer kleiner werdende
# Blumenkohl-Ballen. Drei Ebenen: Schattenseite (links unten, kuehl), Koerper
# (weiss), Lichtkante (warm, rechts oben — dort steht die Sonne der
# Atmosphaere bei 88 % / 9 %).
def wolkenturm(cx: float, base_y: float, scale: float, alpha: float) -> None:
    lobes: list[tuple[float, float, float]] = []
    # Fuss
    for k in range(7):
        lobes.append(
            (cx + (k - 3) * 62 * scale + r(-10, 10), base_y - r(0, 16) * scale, r(58, 82) * scale)
        )
    # Mittelbau
    for k in range(6):
        lobes.append(
            (
                cx + (k - 2.6) * 52 * scale + r(-14, 14),
                base_y - (86 + r(0, 34)) * scale,
                r(52, 76) * scale,
            )
        )
    # Turm
    for k in range(5):
        lobes.append(
            (
                cx + (k - 2.1) * 44 * scale + r(-16, 16),
                base_y - (168 + r(0, 40)) * scale,
                r(44, 66) * scale,
            )
        )
    for k in range(4):
        lobes.append(
            (
                cx + (k - 1.4) * 38 * scale + r(-14, 14),
                base_y - (244 + r(0, 34)) * scale,
                r(36, 54) * scale,
            )
        )
    for k in range(3):
        lobes.append(
            (
                cx + (k - 0.9) * 32 * scale + r(-12, 12),
                base_y - (306 + r(0, 26)) * scale,
                r(28, 44) * scale,
            )
        )
    lobes.append((cx - 12 * scale, base_y - 352 * scale, 32 * scale))

    def blob(dx: float, dy: float, fill: float, col: str, op: float) -> None:
        add(f"<g fill='{col}' opacity='{n(op,2)}'>")
        for lx, ly, lr in lobes:
            add(
                f"<circle cx='{n(lx+dx)}' cy='{n(ly+dy)}' r='{n(lr*fill)}'/>"
            )
        add("</g>")

    blob(-14 * scale, 16 * scale, 1.0, WOLKE_S, alpha)  # Schattenseite
    blob(0, 0, 0.97, WOLKE, alpha)  # Koerper
    blob(9 * scale, -9 * scale, 0.72, WOLKE_W, alpha * 0.85)  # Lichtkante


wolkenturm(1332, 596, 1.0, 0.96)
wolkenturm(1560, 612, 0.52, 0.7)
# Zwei kleine Schoenwetterwolken, damit der Himmel nicht nur rechts lebt.
for cx, by, sc, al in ((262, 356, 0.30, 0.62), (742, 300, 0.24, 0.5)):
    add(f"<g opacity='{n(al,2)}'>")
    for k in range(6):
        add(
            f"<ellipse cx='{n(cx+(k-2.5)*52*sc)}' cy='{n(by-r(0,20)*sc)}' "
            f"rx='{n(r(46,72)*sc)}' ry='{n(r(20,30)*sc)}' fill='{WOLKE}'/>"
        )
    add("</g>")


# ── 2. DIE HUEGEL ────────────────────────────────────────────────────────────
# Drei Ruecken ueber die volle Breite. Jeder ist eine Sinus-Ueberlagerung, also
# eine echte Landschaftslinie und keine Zacke. Von hinten nach vorn dunkler und
# satter — das ist die Tiefe, die v1 gefehlt hat.
def ruecken(y0: float, amp: float, ph: float, col: str, seed: float) -> str:
    pts = []
    for x in range(0, W + 41, 40):
        yy = (
            y0
            - amp * math.sin(x / 430.0 + ph)
            - amp * 0.45 * math.sin(x / 167.0 + ph * 2.3 + seed)
            - amp * 0.2 * math.sin(x / 79.0 + seed * 3)
        )
        pts.append(f"{x} {n(yy)}")
    return f"<path fill='{col}' d='M0 {H}L0 {pts[0].split()[1]}L" + "L".join(pts) + f"L{W} {H}Z'/>"


# DER GROSSE HUEGEL. Er ist der Grund, warum die Bildmitte ueberhaupt etwas
# hat: unter dem Schleier ueberlebt keine Textur, nur FORM. Ukiyos Welle ist
# eine einzige grosse Form, Komorebis Licht sind grosse Bahnen — die erste
# Fassung dieser Szene hatte in der Mitte nur feines Gras, und feines Gras
# wird unter 24 % Restdeckung zu Nebel. Dieser Ruecken steigt von rechts bis
# auf y 424 (Viewport ~42 %) und laeuft ueber die halbe Bildbreite hinter der
# Lesespalte durch. Er ist die zweite grosse Masse neben Baum und Wolkenturm.
gross = []
for x in range(0, W + 41, 40):
    tt = max(0.0, min(1.0, (x - 430) / 1010.0))
    yy = 640 - 216 * (tt * tt * (3 - 2 * tt)) - 14 * math.sin(x / 233.0 + 1.4)
    gross.append(f"{x} {n(yy)}")
add(f"<path fill='{FERN_2}' d='M0 {H}L0 640L" + "L".join(gross) + f"L{W} {H}Z'/>")

add(ruecken(560, 40, 0.4, FERN_3, 1.1))
add(ruecken(600, 30, 2.1, FERN_2, 2.7))
add(ruecken(632, 20, 3.6, FERN_1, 0.6))

# Baumreihe auf dem vordersten Ruecken: kleine Kuppen, die als Wald lesen.
add(f"<g fill='{FERN_1}'>")
x = 20.0
while x < W:
    hh = r(10, 22)
    add(f"<ellipse cx='{n(x)}' cy='{n(634 - hh * 0.5)}' rx='{n(r(9,17))}' ry='{n(hh)}'/>")
    x += r(16, 34)
add("</g>")


# ── 3. DIE WIESE ─────────────────────────────────────────────────────────────
# Vier Tiefenstufen. Jede Stufe ist eine Flaeche mit unruhiger Oberkante PLUS
# einer Reihe echter Halme, die aus dieser Kante wachsen — genau das
# unterscheidet Gras von einem Zackenband. Die Halme sind <use> auf zwei
# Grundformen, jeder mit eigener Drehung und Groesse.
def wiese(y0: float, wob: float, col: str, dichte: float, hmin: float, hmax: float) -> None:
    pts = []
    for x in range(0, W + 25, 24):
        yy = y0 - wob * math.sin(x / 205.0 + y0) - wob * 0.5 * math.sin(x / 61.0 + y0 * 0.7)
        pts.append(f"{x} {n(yy)}")
    add(f"<path fill='{col}' d='M0 {H}L" + "L".join(pts) + f"L{W} {H}Z'/>")
    # Der Schattenfuss: ein schmales, sehr dunkles Band direkt UNTER der Kante
    # der naechsten Stufe. Das ist die halbe Tiefe der ganzen Wiese und der
    # dunkle Pol des Wertumfangs (s. Palette) — ohne ihn liest jede Stufe als
    # Band statt als Boden, der vor dem naechsten liegt.
    add(
        f"<path fill='{WIESE_SCHATTEN}' opacity='0.5' d='M0 {n(y0+58)}L"
        + "L".join(pts)
        + f"L{W} {n(y0+58)}Z'/>"
    )
    seg = []
    x = -20.0
    while x < W + 20:
        yy = y0 - wob * math.sin(x / 205.0 + y0) - wob * 0.5 * math.sin(x / 61.0 + y0 * 0.7)
        seg.append(halm(x, yy + 5, r(hmin, hmax), r(-17, 17)))
        x += dichte * r(0.6, 1.5)
    add(f"<path fill='{col}' d='{''.join(seg)}'/>")


# ferne Wiese (oberhalb der Naht -> obere CSS-Ebene)
wiese(648, 9, WIESE_4, 22, 0.30, 0.52)
# Sonnenflecken auf der fernen Wiese: die Wiese ist nicht einfarbig
for col, op, cnt, ry0, ry1 in ((SONNENFLECK, 0.62, 9, 10, 20), (SONNENFLECK_2, 0.45, 5, 5, 11)):
    add(f"<g fill='{col}' opacity='{op}'>")
    for _ in range(cnt):
        add(
            f"<ellipse cx='{n(r(0, W))}' cy='{n(r(658, 700))}' "
            f"rx='{n(r(100, 240))}' ry='{n(r(ry0, ry1))}'/>"
        )
    add("</g>")

# ── Der Schatten des Baums, auf der fernen Wiese liegend ──────────────────
# Der Schatten des Baums auf der Wiese. Er ist nicht Zierde, sondern die
# einzige Stelle, die den Baum am Boden VERANKERT — ohne ihn schwebt er.
# Und er ist der dunkelste Fleck der ganzen Wiese, also ein Pol des
# Wertumfangs; unter dem Schleier liest man ihn noch als Schatten.
add(f"<ellipse cx='320' cy='704' rx='310' ry='44' fill='{WIESE_SCHATTEN}' opacity='0.72'/>")
add(f"<ellipse cx='300' cy='700' rx='190' ry='28' fill='{WIESE_SCHATTEN}' opacity='0.5'/>")

# WOLKENSCHATTEN. Grosse, weiche, dunkle Bahnen, die quer ueber die ganze
# Wiese laufen — dasselbe Argument wie beim grossen Huegel: die Bildmitte
# braucht Massen, keine Textur. An einem Tag mit einem Wolkenturm am Himmel
# ist das ausserdem schlicht wahr. Sie liegen auf der fernen Wiese, also in
# der oberen Ebene, und werden von den naeheren Stufen wieder ueberdeckt.
add(f"<g fill='{WIESE_SCHATTEN}' opacity='0.34'>")
for scx, scy, scrx, scry in ((520, 690, 430, 40), (1180, 676, 330, 30), (60, 700, 260, 34)):
    add(f"<ellipse cx='{scx}' cy='{scy}' rx='{scrx}' ry='{scry}'/>")
add("</g>")

# mittlere und nahe Wiese (unterhalb der Naht -> untere, staerker bewegte Ebene)
wiese(742, 22, WIESE_3, 17, 0.55, 0.85)
# Die grosse Lichtbahn: dort, wo die Sonne zwischen zwei Wolkenschatten voll
# auf die Wiese faellt. Sie laeuft ueber die halbe Breite hinter der
# Lesespalte durch und ist die hellste Flaeche der ganzen Wiese.
add(f"<g fill='{SONNENFLECK_2}' opacity='0.55'>")
add("<ellipse cx='760' cy='800' rx='520' ry='46'/>")
add("<ellipse cx='430' cy='826' rx='300' ry='30'/>")
add("</g>")
for col, op, cnt in ((SONNENFLECK, 0.5, 8), (SONNENFLECK_2, 0.34, 4)):
    add(f"<g fill='{col}' opacity='{op}'>")
    for _ in range(cnt):
        add(
            f"<ellipse cx='{n(r(0, W))}' cy='{n(r(756, 814))}' "
            f"rx='{n(r(120, 260))}' ry='{n(r(13, 27))}'/>"
        )
    add("</g>")
wiese(848, 28, WIESE_2, 14, 0.85, 1.25)
add(f"<g fill='{WIESE_SCHATTEN}' opacity='0.3'>")
for scx, scy, scrx, scry in ((880, 880, 400, 44), (180, 866, 300, 36)):
    add(f"<ellipse cx='{scx}' cy='{scy}' rx='{scrx}' ry='{scry}'/>")
add("</g>")
wiese(946, 24, WIESE_1, 11, 1.15, 1.75)


# ── 4. DIE SONNENBLUMEN ──────────────────────────────────────────────────────
# Ueber die VOLLE Breite, in drei Tiefen. Sie laufen bewusst hinter der
# Lesespalte durch: unter dem Schleier bleiben sie als blasse Goldscheiben
# lesbar (Wert-Vertrag), im Seitenraum stehen sie voll.
def blume(cx: float, cy: float, s: float, art: str, kipp: float, wurzel: float,
          blaetter: bool = True) -> None:
    add(
        f"<path stroke='{STIEL}' stroke-width='{n(max(2.2, 7*s),1)}' fill='none' "
        f"d='M{n(cx)} {n(cy)}Q{n(cx + kipp*14)} {n((cy+wurzel)/2)} "
        f"{n(cx + kipp*22)} {n(wurzel)}'/>"
    )
    if blaetter:
        for k in (-1, 1):
            ly = cy + (wurzel - cy) * (0.42 if k < 0 else 0.66)
            add(
                f"<use href='#sl' fill='{STIEL}' "
                f"transform='{xf(cx + kipp*12, ly, 18 if k > 0 else 160, s*0.8)}'/>"
            )
    add(f"<use href='#{art}' transform='{xf(cx, cy, kipp*9, s)}'/>")


# ferne Reihe (obere Ebene, klein) — Koepfe deutlich ueber der Naht.
# Ohne Stielblaetter: bei Skala 0.25 sind sie zwei unlesbare Pixel und kosten
# nur Bytes.
for _ in range(20):
    blume(r(20, W - 20), r(584, 644), r(0.24, 0.4), "sfb", r(-1, 1), r(666, 698), False)

# mittlere Reihe (untere Ebene) — vollstaendig unterhalb der Naht
for _ in range(21):
    cy = r(752, 826)
    blume(r(-10, W + 10), cy, r(0.52, 0.76),
          "sfa" if random.random() < 0.6 else "sfb", r(-1, 1), cy + r(120, 190))

# vordere Reihe — gross, teils vom unteren Rand angeschnitten
for _ in range(12):
    cy = r(858, 934)
    blume(r(-30, W + 30), cy, r(0.86, 1.28),
          "sfa" if random.random() < 0.7 else "sfb", r(-1, 1), cy + r(120, 200))

# Die vordersten, tiefsten Halme schneiden ueber allem durch — sie geben der
# Wiese ihre Naehe und schliessen das untere Bilddrittel.
seg = []
x = -20.0
while x < W + 20:
    seg.append(halm(x, r(994, 1004), r(1.5, 2.5), r(-20, 20)))
    x += r(6, 15)
add(f"<path fill='{WIESE_0}' d='{''.join(seg)}'/>")


# ── 5. DER BAUM ──────────────────────────────────────────────────────────────
# Das Hauptmotiv. Steht links, im unverschleierten Seitenraum, und laeuft mit
# seiner rechten Kronenhaelfte hinter die Lesespalte.
# Aufbau wie ein echter Baum, nicht wie eine Kugel:
#   Stamm -> Hauptaeste -> Kronen-Grundmassen (Lappen) -> hunderte Bueschel.
# Die Tonwahl je Bueschel folgt der Sonne (Atmosphaere: 88 % / 9 %, also von
# rechts oben): rechts-oben hell, links-unten dunkel. Das ist die Modellierung,
# die v1 gefehlt hat — dort war die Krone eine einfarbige Flaeche.
TX, TY = 330.0, 726.0  # Fusspunkt, sitzt in der fernen Wiese

add("<g>")
# Stamm: zwei Kurven, rechts die Lichtseite
add(
    f"<path fill='{STAMM}' d='M{n(TX-30)} {n(TY)}"
    f"C{n(TX-26)} {n(TY-90)} {n(TX-22)} {n(TY-150)} {n(TX-19)} {n(TY-232)}"
    f"L{n(TX+15)} {n(TY-238)}"
    f"C{n(TX+18)} {n(TY-150)} {n(TX+24)} {n(TY-80)} {n(TX+30)} {n(TY)}Z'/>"
)
add(
    f"<path fill='{STAMM_D}' d='M{n(TX-30)} {n(TY)}"
    f"C{n(TX-26)} {n(TY-90)} {n(TX-22)} {n(TY-150)} {n(TX-19)} {n(TY-232)}"
    f"L{n(TX-4)} {n(TY-235)}"
    f"C{n(TX-7)} {n(TY-150)} {n(TX-10)} {n(TY-80)} {n(TX-12)} {n(TY)}Z'/>"
)

# Hauptaeste in die Krone
aeste = [
    (-150, -430, -70, -320, 15),
    (140, -448, 70, -318, 14),
    (-46, -520, -30, -360, 13),
    (58, -540, 34, -370, 12),
    (-224, -366, -120, -300, 10),
    (222, -372, 120, -302, 10),
]
for ex, ey, mx, my, wd in aeste:
    add(
        f"<path stroke='{AST}' stroke-width='{wd}' fill='none' stroke-linecap='round' "
        f"d='M{n(TX)} {n(TY-244)}Q{n(TX+mx)} {n(TY+my)} {n(TX+ex)} {n(TY+ey)}'/>"
    )
add("</g>")

# Kronen-Lappen: die Grundmasse. Kein Kreis — sieben unterschiedlich grosse
# Ballen, die zusammen eine unruhige Silhouette ergeben.
lappen = [
    (TX - 168, TY - 392, 128, 104),
    (TX - 52, TY - 452, 150, 122),
    (TX + 96, TY - 418, 134, 110),
    (TX + 202, TY - 348, 100, 84),
    (TX - 236, TY - 320, 96, 80),
    (TX + 24, TY - 300, 168, 92),
    (TX - 96, TY - 262, 122, 72),
    (TX + 142, TY - 254, 98, 62),
]
add(f"<g fill='{KRONE[1]}'>")
for lx, ly, rx, ry in lappen:
    add(f"<ellipse cx='{n(lx)}' cy='{n(ly)}' rx='{n(rx)}' ry='{n(ry)}'/>")
add("</g>")


def in_krone(px: float, py: float, pad: float = 1.0) -> bool:
    for lx, ly, rx, ry in lappen:
        if ((px - lx) / (rx * pad)) ** 2 + ((py - ly) / (ry * pad)) ** 2 <= 1.0:
            return True
    return False


# Die Bueschel. Innen dicht, am Rand ueberstehend (das bricht die Silhouette
# auf und ist der Unterschied zwischen "Baum" und "gruener Klotz").
gruppen: dict[int, list[str]] = {k: [] for k in range(len(KRONE))}
tries = 0
placed = 0
while placed < 330 and tries < 20000:
    tries += 1
    px = r(TX - 380, TX + 330)
    py = r(TY - 590, TY - 190)
    innen = in_krone(px, py)
    rand = (not innen) and in_krone(px, py, 1.14)
    if not (innen or rand):
        continue
    if rand and random.random() < 0.45:
        continue
    # Sonnenstand: rechts oben hell, links unten dunkel.
    f = (px - TX) / 330.0 * 0.55 + ((TY - 330) - py) / 300.0 * 0.75 + r(-0.42, 0.42)
    idx = min(len(KRONE) - 1, max(0, int((f + 1.0) / 2.0 * len(KRONE))))
    if rand:
        idx = min(len(KRONE) - 1, idx + 1)
    s = r(0.70, 1.45) * (0.8 if rand else 1.0)
    gruppen[idx].append(
        f"<use href='#{'b' if random.random() < 0.6 else 'c'}' "
        f"transform='{xf(px, py, r(0, 360), s)}'/>"
    )
    placed += 1

for idx in range(len(KRONE)):
    if gruppen[idx]:
        add(f"<g fill='{KRONE[idx]}'>" + "".join(gruppen[idx]) + "</g>")

# Ein paar wenige Blaetter, die sich vom Baum geloest haben und fallen —
# Zierde, die nichts behauptet.
add(f"<g fill='{KRONE[3]}' opacity='0.8'>")
for _ in range(9):
    add(
        f"<use href='#c' transform='{xf(r(80, 700), r(430, 660), r(0, 360), r(0.28, 0.46))}'/>"
    )
add("</g>")

add("</svg>")

svg = "\n".join(out)

# XML verbietet '--' INNERHALB eines Kommentars. Das ist keine Formalie: Chrome
# verwirft bei diesem Fehler das GANZE Dokument still, und die Szene ist dann
# einfach weg — genau so ist der erste Bau dieser Fassung gescheitert (ein
# beilaeufig erwaehnter CSS-Token-Name im Kopfkommentar). Also wird es geprueft,
# statt sich darauf zu verlassen, dass man daran denkt.
for _c in svg.split("<!--")[1:]:
    if "--" in _c.split("-->")[0]:
        raise SystemExit("ABBRUCH: '--' in einem SVG-Kommentar — Chrome wuerde "
                         "die Datei still verwerfen.")

dst = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "frontend",
    "public",
    "themes",
    "natsunohi-szene.svg",
)
with open(dst, "w", encoding="utf-8") as fh:
    fh.write(svg)
print(f"{dst}\n{len(svg.encode('utf-8')) / 1024:.1f} KB, {svg.count('<use')} <use>, "
      f"{svg.count('<path')} <path>, {svg.count('<ellipse') + svg.count('<circle')} Ellipsen")

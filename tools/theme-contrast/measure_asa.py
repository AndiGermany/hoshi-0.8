"""Kontrast-BEWEIS des Asa-Pods — headless Chrome, echte Pixel, zwei Stufen.

    python3 tools/theme-contrast/measure_asa.py           # Tokens + die vier Fenster
    python3 tools/theme-contrast/measure_asa.py --sweep   # + die Schleier-Kurve

STUFE 1 — DIE FARBEN (harness-asa-tokens.html)
Jeder Token wird als Kachel gemalt und als Pixel zurueckgelesen. Damit stammt die
Kontrast-Tabelle im Datei-Kopf von asa.css aus dem, was Chrome wirklich
darstellt, und nicht aus meiner eigenen oklch-Rechnung. Das ist keine Pedanterie:
genau hier ist schon einmal eine Selbst-Rechnung gekippt. Sie erklaerte den
Alt-Akzent oklch(0.52 0.17 42) fuer ausserhalb von sRGB; nachgemessen stimmt das
nicht (bei C≈0.157 geht nur der Blau-Kanal auf 0 — das ist keine Gamut-Kante).
Was den Akzent wirklich bewegt hat, steht in dieser Tabelle: 4,45:1 auf der
Du-Blase, unter AA. Zahlen aus dem Bild, nicht aus dem Kopf.

STUFE 2 — DIE SZENE (harness-asa.html)
Der volle Hintergrund-Stapel (Atmosphaere → Zimmer + Schleier → Lichtschacht →
Dampf) im echten CSS-Stapel inkl. index.css, dann der schlechteste Pixel im
920-px-Band der App-Spalte.

WAS "SCHLECHTESTER PIXEL" HEISST — und warum hier nicht "der dunkelste" steht:
Der Kontrast zweier Farben faellt auf 1,0, wenn ihre Leuchtdichten gleich sind,
und steigt nach BEIDEN Seiten wieder an. Der schlimmste Untergrund fuer eine
Schrift ist deshalb der, dessen Leuchtdichte der ihren am naechsten kommt — nicht
pauschal der dunkelste. Bei einem hellen Thema mit dunkler Schrift ist das meist
derselbe Pixel; die Zeichnung enthaelt aber Dinge, die DUNKLER sind als --text-4
(die Tenmoku-Tasse, die Tischkante), und fuer die stimmt die Faustregel nicht
mehr. Das Skript sucht darum direkt das Minimum des Kontrasts ueber alle Pixel
und muss ueber hell/dunkel gar nichts annehmen.

WARUM DER RUHEZUSTAND GEMESSEN WIRD: alle vier Uhren (Schacht 190s/113s, Dampf
54s/83s) starten in ihrem 0-%- bzw. `from`-Frame, und beide beweglichen Schichten
hellen ausschliesslich auf. Der Schnappschuss bei ~2 s virtueller Zeit trifft
also praktisch exakt den Startframe; jede spaetere Phase ist HELLER an den
Stellen, die diese Schichten ueberhaupt beruehren, und damit unkritisch. Der
Grenzfall waere eine Schicht, die abdunkelt — asa hat keine.

Ohne Fremdpakete: der PNG-Dekoder unten ist Standardbibliothek (zlib). Chrome
laeuft mit EIGENEM --user-data-dir im Wegwerf-Verzeichnis; fremde Profile und
fremde Chrome-Prozesse werden nie angefasst.
"""

from __future__ import annotations

import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zlib
from pathlib import Path

from wcag_asa import contrast, luminance, to_hex

HERE = Path(__file__).resolve().parent
SCENE_HARNESS = HERE / "harness-asa.html"
TOKEN_HARNESS = HERE / "harness-asa-tokens.html"

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

# Die Fenster, fuer die der Datei-Kopf buergt. 1024x768 ist der interessante
# Fall: dort schneidet `cover` seitlich, der niedrige Tisch rueckt in die Spalte.
VIEWPORTS = [(1440, 900), (1280, 800), (1024, 768), (1920, 1080)]

APP_WIDTH = 920  # .app max-width aus index.css — die geschuetzte Lesespalte
TILE = 120  # Kachelkante in harness-asa-tokens.html
COLS = 8  # Kacheln je Reihe, dito


# ── PNG lesen (8 bit, Graustufe/RGB/RGBA, Filter 0–4) ───────────────────────


def png_pixels(path: Path) -> tuple[int, int, int, bytes]:
    """(breite, hoehe, kanaele, entfilterte bytes)."""
    raw = path.read_bytes()
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path} ist kein PNG")

    pos, idat, width, height, channels = 8, bytearray(), 0, 0, 0
    while pos < len(raw):
        (length,) = struct.unpack(">I", raw[pos : pos + 4])
        kind = raw[pos + 4 : pos + 8]
        body = raw[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, color = struct.unpack(">IIBB", body[:10])
            if depth != 8:
                raise ValueError(f"nur 8 bit unterstuetzt, nicht {depth}")
            channels = {0: 1, 2: 3, 4: 2, 6: 4}.get(color)
            if channels is None:
                raise ValueError(f"Farbtyp {color} (Palette?) nicht unterstuetzt")
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break

    data = zlib.decompress(bytes(idat))
    stride = width * channels
    out = bytearray(height * stride)
    prev = bytearray(stride)
    src = 0

    for y in range(height):
        ftype = data[src]
        src += 1
        line = bytearray(data[src : src + stride])
        src += stride
        if ftype == 1:  # Sub
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif ftype == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:  # Average
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:  # Paeth
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 0xFF
        elif ftype != 0:
            raise ValueError(f"unbekannter Filter {ftype} in Zeile {y}")
        out[y * stride : (y + 1) * stride] = line
        prev = line

    return width, height, channels, bytes(out)


def pixel_at(px: tuple[int, int, int, bytes], x: int, y: int) -> tuple[int, int, int]:
    width, _h, channels, data = px
    i = (y * width + x) * channels
    if channels < 3:  # Graustufe
        return data[i], data[i], data[i]
    return data[i], data[i + 1], data[i + 2]


# ── Chrome ──────────────────────────────────────────────────────────────────


def shoot(url: str, w: int, h: int, out: Path, timeout: float = 120.0) -> None:
    """Ein Screenshot — und zwar OHNE auf Chromes Ende zu warten.

    BEFUND (Aoi-Pod, 19.08., hier bestaetigt): dieses Chrome schreibt das PNG in
    ~3 s, BEENDET SICH ABER NIE — Updater und GCM-Registrierung halten den
    Prozess offen. Wer `subprocess.run(..., timeout=...)` benutzt, laeuft in den
    Timeout, obwohl das Bild laengst fertig auf der Platte liegt; das sieht aus
    wie ein Mess-Fehlschlag, ist aber ein Werkzeug-Fehler. Genau daran ist der
    erste Asa-Versuch gescheitert ("--screenshot haengt").

    Also: auf die DATEI warten (zwei gleiche Groessen hintereinander = fertig
    geschrieben), danach den EIGENEN Prozess beenden. Fremde Chrome-Prozesse
    bleiben unangetastet, das Profil ist ein Wegwerf-Verzeichnis.
    """
    out.unlink(missing_ok=True)
    profile = Path(tempfile.mkdtemp(prefix="asa-chrome-"))
    proc = subprocess.Popen(
        [
            CHROME,
            "--headless",
            f"--user-data-dir={profile}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
            "--disable-gpu",
            "--disable-background-networking",
            "--disable-component-update",
            "--hide-scrollbars",
            "--force-device-scale-factor=1",
            "--allow-file-access-from-files",
            "--virtual-time-budget=2000",
            f"--window-size={w},{h}",
            f"--screenshot={out}",
            url,
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    try:
        deadline, size = time.monotonic() + timeout, -1
        while time.monotonic() < deadline:
            time.sleep(0.4)
            if out.exists():
                now = out.stat().st_size
                if now > 0 and now == size:
                    return
                size = now
            elif proc.poll() is not None:
                raise RuntimeError("Chrome endete, ohne ein PNG zu schreiben")
        raise RuntimeError(f"kein Screenshot nach {timeout:.0f}s: {out}")
    finally:
        proc.kill()
        proc.wait(timeout=10)
        shutil.rmtree(profile, ignore_errors=True)


# ── Stufe 1: die Farben, wie Chrome sie malt ────────────────────────────────


def read_tokens(tmp: Path) -> dict[str, tuple[int, int, int]]:
    names = re.findall(r'data-name="([^"]+)"', TOKEN_HARNESS.read_text("utf-8"))
    rows = (len(names) + COLS - 1) // COLS
    shot = tmp / "asa-tokens.png"
    shoot(TOKEN_HARNESS.resolve().as_uri(), COLS * TILE, rows * TILE, shot)
    px = png_pixels(shot)
    out: dict[str, tuple[int, int, int]] = {}
    for i, name in enumerate(names):
        x = (i % COLS) * TILE + TILE // 2
        y = (i // COLS) * TILE + TILE // 2
        out[name] = pixel_at(px, x, y)
    return out


# ── Stufe 2: der schlechteste Pixel der Szene ───────────────────────────────


def worst_pixel(
    px: tuple[int, int, int, bytes], x0: int, x1: int, ref_lum: float
) -> tuple[tuple[int, int, int], float, tuple[int, int, int]]:
    """(schlechtester Pixel, sein Kontrast gegen ref_lum, dunkelster Pixel).

    Schlechtester = kleinster Kontrast = Leuchtdichte am naechsten an ref_lum.
    Gesucht wird ueber |lum - ref_lum|, damit auch Untergruende, die DUNKLER
    sind als die Schrift, richtig bewertet werden.
    """
    width, height, channels, data = px
    x0, x1 = max(0, x0), min(width, x1)
    stride = width * channels
    seen: dict[bytes, float] = {}  # Farbe → Leuchtdichte, spart Wiederholungen

    best_gap, best_rgb = 1e9, (0, 0, 0)
    dark_lum, dark_rgb = 1e9, (0, 0, 0)

    for y in range(height):
        row = y * stride
        for x in range(x0, x1):
            i = row + x * channels
            key = data[i : i + 3]
            lum = seen.get(key)
            if lum is None:
                lum = luminance(key[0], key[1], key[2])
                seen[key] = lum
            gap = lum - ref_lum
            if gap < 0:
                gap = -gap
            if gap < best_gap:
                best_gap, best_rgb = gap, (key[0], key[1], key[2])
            if lum < dark_lum:
                dark_lum, dark_rgb = lum, (key[0], key[1], key[2])

    return best_rgb, contrast(luminance(*best_rgb), ref_lum), dark_rgb


def measure_scene(url: str, w: int, h: int, tmp: Path, text_lum: float, tag: str) -> dict:
    shot = tmp / f"asa-{tag}.png"
    shoot(url, w, h, shot)
    px = png_pixels(shot)

    half = APP_WIDTH // 2
    lo, hi = w // 2 - half, w // 2 + half
    col_rgb, col_ratio, col_dark = worst_pixel(px, lo, hi, text_lum)

    if lo > 0:
        side_rgb, side_ratio, _ = worst_pixel(px, 0, lo, text_lum)
    else:
        side_rgb, side_ratio = col_rgb, col_ratio

    return {
        "viewport": f"{w}x{h}",
        "col_hex": to_hex(col_rgb),
        "col_ratio": col_ratio,
        "col_dark": to_hex(col_dark),
        "side_hex": to_hex(side_rgb),
        "side_ratio": side_ratio,
    }


# ── Bericht ─────────────────────────────────────────────────────────────────

# Welche Schrift auf welchen Flaechen stehen kann. --bg-user ist die Du-Blase,
# --bg-subtle die Hover-/Fuellflaeche; beide tragen Text und gehoeren deshalb in
# die Tabelle. Angegeben wird jeweils der SCHLECHTESTE der fuenf Untergruende.
BEDS = ["bg-elevated", "bg-surface", "bg-base", "bg-subtle", "bg-user"]
INKS = ["text-1", "text-2", "text-3", "text-4", "accent", "success", "warn", "error"]
# Farbe auf ihrer EIGENEN *-soft-Flaeche (so bauen Pills/Badges in index.css).
ON_SOFT = [("accent", "bed-accent"), ("success", "bed-success"),
           ("warn", "bed-warn"), ("error", "bed-error")]


def main() -> int:
    if not Path(CHROME).exists():
        print(f"Chrome nicht gefunden: {CHROME}", file=sys.stderr)
        return 2

    tmp = Path(tempfile.mkdtemp(prefix="asa-shots-"))

    # ── Stufe 1 ──
    tok = read_tokens(tmp)
    lum = {k: luminance(*v) for k, v in tok.items()}

    print("STUFE 1 — TOKEN, wie Chrome sie malt (aus dem Screenshot gelesen)")
    for name in INKS + BEDS + ["accent-ink", "bg-hairline", "bg-hairline-dashed"]:
        print(f"  --{name:<20} {to_hex(tok[name])}")

    print()
    print("  Schrift/Farbe gegen den SCHLECHTESTEN der fuenf realen Untergruende")
    print(f"  {'Token':<12}{'schlechtester Grund':<22}{'Kontrast':>9}   AA")
    worst_tokens = {}
    for ink in INKS:
        worst = min(((contrast(lum[ink], lum[b]), b) for b in BEDS), key=lambda t: t[0])
        worst_tokens[ink] = worst
        flag = "ja" if worst[0] >= 4.5 else "NEIN"
        print(f"  --{ink:<10}--{worst[1]:<20}{worst[0]:>9.2f}   {flag}")

    print()
    print("  Farbe auf ihrer EIGENEN *-soft-Flaeche (Pills/Badges)")
    for ink, bed in ON_SOFT:
        r = contrast(lum[ink], lum[bed])
        print(f"  --{ink:<10}auf {to_hex(tok[bed]):<18}{r:>9.2f}   "
              f"{'ja' if r >= 4.5 else 'NEIN'}")
    r_ink = contrast(lum["accent-ink"], lum["accent"])
    print(f"  --accent-ink auf --accent {'':<11}{r_ink:>9.2f}   "
          f"{'ja' if r_ink >= 4.5 else 'NEIN'}")

    hair = contrast(lum["bg-hairline"], lum["bg-base"])
    hair_d = contrast(lum["bg-hairline-dashed"], lum["bg-base"])
    print()
    print(f"  Haarlinie gegen --bg-base: {hair:.2f}:1 (gestrichelt {hair_d:.2f}:1) "
          f"— Zierlinie, kein UI-Bedienelement")

    # ── Stufe 2 ──
    text_lum = lum["text-4"]
    base = SCENE_HARNESS.resolve().as_uri()

    print()
    print("STUFE 2 — DIE SZENE, schlechtester Pixel im 920-px-Band")
    print(f"  gemessen gegen --text-4 = {to_hex(tok['text-4'])}")
    print(f"  {'Fenster':<12}{'schlechtester':<15}{'--text-4':>9}   AA")
    worst = 99.0
    for w, h in VIEWPORTS:
        r = measure_scene(base, w, h, tmp, text_lum, f"{w}x{h}")
        worst = min(worst, r["col_ratio"])
        print(f"  {r['viewport']:<12}{r['col_hex']:<15}{r['col_ratio']:>9.2f}   "
              f"{'ja' if r['col_ratio'] >= 4.5 else 'NEIN'}")
        print(f"  {'':<12}dunkelster Pixel der Spalte {r['col_dark']} · "
              f"Seitenraum {r['side_hex']} → {r['side_ratio']:.2f} "
              f"(dort steht nie eine Glyphe)")

    print()
    print(f"SCHLECHTESTER FALL ueber alle Fenster: --text-4 {worst:.2f}:1 "
          f"({'AA gehalten' if worst >= 4.5 else 'AA VERFEHLT'})")

    if "--sweep" in sys.argv:
        print()
        print("SCHLEIER-KURVE (1024x768 — das engste Fenster, --asa-veil variiert)")
        for veil in (0.0, 0.70, 0.80, 0.86, 0.90, 0.94):
            r = measure_scene(f"{base}?veil={veil}", 1024, 768, tmp, text_lum,
                              f"veil{veil}")
            mark = "  ← ausgeliefert" if abs(veil - 0.90) < 1e-9 else ""
            note = "" if r["col_ratio"] >= 4.5 else "  (unter AA)"
            print(f"  {veil:.2f}  {r['col_hex']}  --text-4 "
                  f"{r['col_ratio']:.2f}{note}{mark}")

    print()
    print(f"Screenshots: {tmp}")
    return 0 if worst >= 4.5 else 1


if __name__ == "__main__":
    raise SystemExit(main())

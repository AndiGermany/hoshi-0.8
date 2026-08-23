"""Kontrast-BEWEIS des Yoru-Pods — headless Chrome, echte Pixel.

WAS HIER ANDERS IST ALS BEI DEN HELLEN THEMEN
─────────────────────────────────────────────
Yoru ist der SONDERFALL der Order: Yoru IST der Basis-Token-Satz der App
(src/index.css). Seine Theme-Datei enthaelt keine einzige Farbe der Text-Treppe
und darf sie auch nicht enthalten — eine zweite Farbliste waere eine zweite
Wahrheit, die driftet. Der Pod kann --text-4 also NICHT aufhellen, um 4,5:1 zu
erreichen; --text-4 auf --bg-base steht bei ~2,8:1 und ist als leiseste Stufe
(Zeitstempel, Metazeilen) seit jeher so ausgeliefert. Das ist eine Aussage ueber
die BASISTOKEN der App, nicht ueber diese Szene.

Der Vertrag, den dieser Pod messen kann und einhaelt, ist darum der schaerfste,
der ohne Token-Aenderung ueberhaupt formulierbar ist:

    Die Szene macht die Lesespalte an KEINER Stelle heller, als Yorus
    ausgelieferte Atmosphaere sie ohnehin schon macht.

Deshalb misst dieses Skript IMMER ein Paar: denselben Aufbau mit Zeichnung und
ohne Zeichnung (?scene=off). Die Differenz ist der Beweis, nicht die absolute
Zahl. Auf dunklem Grund ist Helligkeit der Feind — gemessen wird der HELLSTE
Pixel im 920-px-Band, der Ort, an dem die leiseste Schrift am schlechtesten
steht. Nicht der Mittelwert, nicht --bg-base.

WARUM DER RUHEZUSTAND DER SCHLIMMSTE IST: alle vier Uhren starten in ihren
`from`-Frames, und beide Deckkraft-Uhren stehen dort auf opacity 1. Von da aus
kann der Atem nur dunkler werden. Der Screenshot faellt bei 2 s virtueller Zeit
von 71/89/163/283 s Periode, also praktisch exakt auf diesen hellsten Frame.

WOHER DIE SCHRIFTFARBEN KOMMEN: nicht aus eigener OKLCH-Mathematik (Lehre des
Aoi-Pods — nie die eigene Rechnung gegen die eigene Rechnung pruefen), sondern
aus dem Bild. Das Harness malt die vier Stufen der Text-Treppe als deckenden
16-px-Streifen an den oberen Rand; Chrome rechnet OKLCH→sRGB selbst, und hier
werden nur noch Pixel gelesen. Gescannt wird deshalb erst ab y = 24.

Kein Paket noetig (zlib ist Standardbibliothek). Chrome laeuft mit EIGENEM
--user-data-dir im Wegwerf-Verzeichnis; fremde Chrome-Prozesse bleiben
unangetastet.

Aufruf:
    python3 tools/theme-contrast-yoru/measure.py           # die vier Fenster, A/B
    python3 tools/theme-contrast-yoru/measure.py --sweep   # + Schleier-Kurve
"""

from __future__ import annotations

import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zlib
from pathlib import Path

from wcag import contrast_lum, luminance, rgb_to_hex

HERE = Path(__file__).resolve().parent
HARNESS = HERE / "harness.html"

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

VIEWPORTS = [(1920, 1080), (1440, 900), (1280, 800), (1024, 768)]

APP_WIDTH = 920  # .app max-width aus index.css — die geschuetzte Spalte
PROBE_H = 16  # Hoehe des Farb-Streifens im Harness
SCAN_TOP = 24  # ab hier wird nach dem hellsten Pixel gesucht
PROBE_NAMES = ["text-1", "text-2", "text-3", "text-4", "bg-base", "bg-surface"]


# ── PNG lesen (8 bit, RGB/RGBA, Filter 0–4) ─────────────────────────────────


def png_pixels(path: Path) -> tuple[int, int, int, bytes]:
    """Gibt (breite, hoehe, kanaele, rohbytes) zurueck — entfiltert, 8 bit."""
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
                raise ValueError(f"nur 8-bit unterstuetzt, nicht {depth}")
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


# ── Chrome ──────────────────────────────────────────────────────────────────


def shoot(url: str, w: int, h: int, out: Path, timeout: float = 90.0) -> None:
    """Ein Screenshot — und zwar OHNE auf Chromes Ende zu warten.

    BEFUND (Aoi-Pod, 19.08., hier bestaetigt): dieses Chrome schreibt das PNG in
    ~3 s, beendet sich danach aber nicht. Wer auf den PROZESS wartet, laeuft in
    den Timeout, obwohl das Bild laengst auf der Platte liegt — ein
    Werkzeugfehler, der wie ein Mess-Fehlschlag aussieht. Darum: auf die DATEI
    warten (zwei gleiche Groessen hintereinander = fertig geschrieben) und den
    EIGENEN Prozess danach selbst beenden.
    """
    out.unlink(missing_ok=True)
    profile = Path(tempfile.mkdtemp(prefix="yoru-chrome-"))
    proc = subprocess.Popen(
        [
            CHROME,
            "--headless",
            f"--user-data-dir={profile}",  # eigenes Profil, nie ein fremdes
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


# ── Messen ──────────────────────────────────────────────────────────────────


def pixel(px, x: int, y: int) -> tuple[float, float, float]:
    width, _height, channels, data = px
    i = y * width * channels + x * channels
    return (data[i] / 255, data[i + 1] / 255, data[i + 2] / 255)


def read_probe(px) -> dict[str, tuple[str, float]]:
    """Die Text-Treppe aus dem Farb-Streifen — Chromes eigene sRGB-Rechnung."""
    width = px[0]
    y = PROBE_H // 2
    out = {}
    for k, name in enumerate(PROBE_NAMES):
        x = int(width * (k + 0.5) / len(PROBE_NAMES))
        rgb = pixel(px, x, y)
        out[name] = (rgb_to_hex(rgb), luminance(rgb))
    return out


def brightest(px, x0: int, x1: int) -> tuple[str, float, tuple[int, int]]:
    """Hellster Pixel im Band [x0, x1), unterhalb des Farb-Streifens."""
    width, height, channels, data = px
    x0, x1 = max(0, x0), min(width, x1)
    best_lum, best_rgb, best_at = -1.0, (0.0, 0.0, 0.0), (0, 0)
    stride = width * channels

    for y in range(SCAN_TOP, height):
        row = y * stride
        for x in range(x0, x1):
            i = row + x * channels
            rgb = (data[i] / 255, data[i + 1] / 255, data[i + 2] / 255)
            lum = luminance(rgb)
            if lum > best_lum:
                best_lum, best_rgb, best_at = lum, rgb, (x, y)

    return rgb_to_hex(best_rgb), best_lum, best_at


def measure(url: str, w: int, h: int, tmp: Path, tag: str) -> dict:
    shot = tmp / f"yoru-{tag}-{w}x{h}.png"
    shoot(url, w, h, shot)
    px = png_pixels(shot)

    half = APP_WIDTH // 2
    col_lo, col_hi = w // 2 - half, w // 2 + half
    col_hex, col_lum, col_at = brightest(px, col_lo, col_hi)

    # Der Seitenraum als Gegenprobe: dort steht nie ein Buchstabe, dort DARF
    # das Bild sichtbar sein. Er wird gemessen, damit "die Zeichnung ist da"
    # belegt ist und nicht nur behauptet.
    if col_lo > 2:
        side_hex, side_lum, _ = brightest(px, 0, col_lo)
    else:
        side_hex, side_lum = col_hex, col_lum

    probe = read_probe(px)
    ratios = {
        name: contrast_lum(probe[name][1], col_lum)
        for name in ("text-1", "text-2", "text-3", "text-4")
    }
    return {
        "viewport": f"{w}x{h}",
        "col_hex": col_hex,
        "col_lum": col_lum,
        "col_at": col_at,
        "side_hex": side_hex,
        "side_lum": side_lum,
        "probe": probe,
        "ratios": ratios,
    }


def main() -> int:
    if not Path(CHROME).exists():
        print(f"Chrome nicht gefunden: {CHROME}", file=sys.stderr)
        return 2

    base = HARNESS.resolve().as_uri()
    tmp = Path(os.environ.get("YORU_TMP", tempfile.mkdtemp(prefix="yoru-shots-")))
    tmp.mkdir(parents=True, exist_ok=True)

    first = True
    print("A/B — hellster Pixel in der 920-px-Spalte, mit und ohne Zeichnung")
    print(
        f"  {'Fenster':<11}{'nur Atmosph.':<14}{'mit Szene':<12}"
        f"{'L ohne':>9}{'L mit':>9}   {'text-4 ohne→mit':>17}  Urteil"
    )

    verdicts = []
    worst4 = 99.0
    for w, h in VIEWPORTS:
        off = measure(f"{base}?scene=off", w, h, tmp, "ohne")
        on = measure(base, w, h, tmp, "mit")

        if first:
            first = False
            probe_line = "  ".join(
                f"{n} {on['probe'][n][0]}" for n in PROBE_NAMES
            )
            print(f"  [Farb-Streifen aus dem Bild] {probe_line}")

        darker = on["col_lum"] <= off["col_lum"]
        verdicts.append(darker)
        worst4 = min(worst4, on["ratios"]["text-4"])
        print(
            f"  {on['viewport']:<11}{off['col_hex']:<14}{on['col_hex']:<12}"
            f"{off['col_lum']:>9.5f}{on['col_lum']:>9.5f}"
            f"{off['ratios']['text-4']:>13.2f} → {on['ratios']['text-4']:.2f}  "
            f"{'DUNKLER (ok)' if darker else 'HELLER — VERTRAG GEBROCHEN'}"
        )
        print(
            f"  {'':<11}hellster Pixel bei {on['col_at']}, "
            f"Seitenraum {on['side_hex']} (dort steht nie ein Buchstabe)"
        )
        print(
            f"  {'':<11}Spalte absolut: "
            + " · ".join(f"{n} {on['ratios'][n]:.2f}:1" for n in
                         ("text-1", "text-2", "text-3", "text-4"))
        )

    ok = all(verdicts)
    print()
    print(
        "VERTRAG (Szene hellt die Spalte nirgends auf): "
        + ("GEHALTEN in allen vier Fenstern" if ok else "GEBROCHEN")
    )
    print(
        f"--text-4 im schlechtesten Fenster: {worst4:.2f}:1 — das ist der Wert "
        "der BASISTOKEN,\n  nicht der der Szene (s. Datei-Kopf, Rate-Stelle 1)."
    )

    # Der BODEN: dieselbe Szene ohne Yorus warme Atmosphaere. Er beantwortet die
    # einzige Frage, die nach dem A/B noch offen ist — naemlich ob die 2,79 an
    # der Zeichnung haengen (nein) oder an der Laternen-Pfuette, die Yoru seit
    # jeher hat. Was hier zwischen "flach" und "ausgeliefert" steht, ist der
    # vollstaendige Preis der Waerme; ein blosses Dimmen kaeme nie darueber
    # hinaus. Damit ist "die Pfuette bleibt" eine bezifferte Entscheidung.
    print()
    print("BODEN (1440x900) — was die Waerme kostet")
    flat = measure(f"{base}?atm=flat", 1440, 900, tmp, "flach")
    ship = measure(base, 1440, 900, tmp, "mit")
    print(
        f"  ohne Atmosphaere  {flat['col_hex']}  L {flat['col_lum']:.5f}  "
        f"text-4 {flat['ratios']['text-4']:.2f}:1"
    )
    print(
        f"  ausgeliefert      {ship['col_hex']}  L {ship['col_lum']:.5f}  "
        f"text-4 {ship['ratios']['text-4']:.2f}:1"
    )
    print(
        f"  → die ganze Laternen-Waerme kostet "
        f"{flat['ratios']['text-4'] - ship['ratios']['text-4']:.2f} Punkte text-4"
    )

    if "--sweep" in sys.argv:
        print()
        print("SCHLEIER-KURVE (1440x900, --yoru-veil ueberschrieben)")
        for veil in (0.0, 0.06, 0.12, 0.20, 0.35, 1.0):
            r = measure(f"{base}?veil={veil}", 1440, 900, tmp, f"veil{veil}")
            mark = "  <- ausgeliefert" if abs(veil - 0.06) < 1e-9 else ""
            print(
                f"  {veil:.2f}  {r['col_hex']}  L {r['col_lum']:.5f}  "
                f"text-4 {r['ratios']['text-4']:.2f}{mark}"
            )

    print()
    print(f"Screenshots: {tmp}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

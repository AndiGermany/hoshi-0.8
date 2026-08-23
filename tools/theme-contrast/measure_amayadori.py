"""Kontrast-BEWEIS des Amayadori-Pods — headless Chrome, echte Pixel.

Die Lehre vom 18.08. steht im Harness: ein Messfuehler, der die Themen-CSS ohne
die Basistoken laedt, misst eine Nachbildung und luegt. Dieses Skript misst
darum die AUSGELIEFERTE Datei im echten CSS-Stapel (index.css → themes.css →
amayadori.css), so wie `harness-amayadori.html` ihn aufbaut.

WAS GEMESSEN WIRD: der HELLSTE Pixel im 920-px-Band der App-Spalte. Auf dunklem
Grund ist Helligkeit der Feind — der hellste Hintergrund-Pixel ist der Ort, an
dem die leiseste Schrift (--text-4) am schlechtesten steht. Gegen genau diesen
Pixel wird gerechnet, nicht gegen den Mittelwert und nicht gegen --bg-base.

WELCHER FRAME: `?worst=1` friert Regen und Abfluss bei VOLLER Deckung ein. Ohne
den Schalter faellt der Screenshot bei ~2 s virtueller Zeit in den `from`-Frame
der Boe-Animation, also auf die duennste Regenwand — das waere die schmeichel-
haftere Zahl. Der eingefrorene Zustand ist ausserdem kein Kunstprodukt: genau
ihn zeigt das Theme jedem, der `prefers-reduced-motion` gesetzt hat.

Kein Paket noetig: der PNG-Dekoder unten ist Standardbibliothek (zlib). Chrome
laeuft mit EIGENEM --user-data-dir im Wegwerf-Verzeichnis (nie fremde Profile,
nie fremde Prozesse).

Aufruf:
    python3 tools/theme-contrast/measure_amayadori.py           # die Fenster
    python3 tools/theme-contrast/measure_amayadori.py --sweep   # + Schleier-Kurve
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

from wcag_amayadori import hex_to_rgb, luminance, rgb_to_hex

# ── Wo alles liegt ──────────────────────────────────────────────────────────

HERE = Path(__file__).resolve().parent
HARNESS = HERE / "harness-amayadori.html"

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

# Die Fenster, fuer die der Datei-Kopf buergt. Die drei schmalen stehen bewusst
# dabei — sie sind der GEGENBEWEIS zum geerbten Bug (Traufkante wanderte unter
# 1096 px in die Spalte, das Ablaufband stand ueber der Schrift):
#   1024x768  Traufe nur noch 52 px je Seite — Regen und Ablaufband muessen
#             vollstaendig dort hineinpassen, die Spalte bleibt trocken.
#   1000x800  40 px Traufe, der engste Fall, in dem noch Wetter sichtbar ist.
#    880x1180 schmaler als die Spalte: dry == 100vw, es darf ueberhaupt kein
#             nasses Pixel mehr geben (der Schleier deckt das ganze Fenster).
VIEWPORTS = [
    (1920, 1080),
    (1440, 900),
    (1280, 800),
    (1024, 768),
    (1000, 800),
    (880, 1180),
]

# Die Schrift, gegen die gerechnet wird.
TEXT_4 = "#9a8c74"
TEXT_1 = "#f2e9dc"

APP_WIDTH = 920  # .app max-width aus index.css:103 — die geschuetzte Spalte


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
        pos += 12 + length  # laenge + typ + daten + crc

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

    BEFUND des Aoi-Pods (19.08.): dieses Chrome schreibt das PNG in ~3 s,
    beendet sich danach aber nicht (GoogleUpdater und die GCM-Registrierung
    halten den Prozess offen). `subprocess.run(..., timeout=N)` laeuft deshalb
    in den Timeout, OBWOHL das Bild laengst fertig auf der Platte liegt — ein
    Werkzeugfehler, der wie ein Mess-Fehlschlag aussieht. Darum: auf die DATEI
    warten, nicht auf den Prozess, und den eigenen Prozess danach selbst
    beenden (nur den eigenen; das Profil ist ein Wegwerf-Verzeichnis).
    """
    out.unlink(missing_ok=True)
    profile = Path(tempfile.mkdtemp(prefix="amayadori-chrome-"))
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
                # Zwei gleiche Groessen hintereinander = fertig geschrieben.
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


def contrast_lum(la: float, lb: float) -> float:
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def brightest(px: tuple[int, int, int, bytes], x0: int, x1: int) -> tuple[str, float]:
    """Hellster Pixel im x-Band [x0, x1). Gibt (hex, luminanz) zurueck."""
    width, height, channels, data = px
    x0, x1 = max(0, x0), min(width, x1)
    best_lum, best_rgb = -1.0, (0.0, 0.0, 0.0)
    stride = width * channels

    for y in range(height):
        row = y * stride
        for x in range(x0, x1):
            i = row + x * channels
            rgb = (data[i] / 255, data[i + 1] / 255, data[i + 2] / 255)
            lum = luminance(rgb)
            if lum > best_lum:
                best_lum, best_rgb = lum, rgb

    return rgb_to_hex(best_rgb), best_lum


def measure(url: str, w: int, h: int, tmp: Path, tag: str = "") -> dict:
    shot = tmp / f"amayadori-{tag}{w}x{h}.png"
    shoot(url, w, h, shot)
    px = png_pixels(shot)

    half = min(APP_WIDTH, w) // 2
    col_lo, col_hi = w // 2 - half, w // 2 + half
    col_hex, col_lum = brightest(px, col_lo, col_hi)

    # Der Seitenraum als Gegenprobe: dort steht nie ein Buchstabe, dort DARF
    # das Bild hell sein. Gemessen wird er trotzdem, damit die Behauptung
    # "die Zeichnung ist da" belegt ist und nicht nur behauptet.
    if col_lo > 0:
        side_hex, side_lum = brightest(px, 0, col_lo)
    else:
        side_hex, side_lum = col_hex, col_lum

    l4, l1 = luminance(hex_to_rgb(TEXT_4)), luminance(hex_to_rgb(TEXT_1))
    return {
        "viewport": f"{w}x{h}",
        "col_hex": col_hex,
        "t4": contrast_lum(l4, col_lum),
        "t1": contrast_lum(l1, col_lum),
        "side_hex": side_hex,
        "side_t4": contrast_lum(l4, side_lum),
    }


def main() -> int:
    if not Path(CHROME).exists():
        print(f"Chrome nicht gefunden: {CHROME}", file=sys.stderr)
        return 2

    base = f"{HARNESS.resolve().as_uri()}?worst=1"
    tmp = Path(os.environ.get("AMAYADORI_TMP", tempfile.mkdtemp(prefix="amayadori-shots-")))
    tmp.mkdir(parents=True, exist_ok=True)

    print("SPALTE (min(920 px, Fenster) — dort steht Text), schlimmster Frame")
    print(f"  {'Fenster':<12}{'hellster':<10}{'--text-4':>10}{'--text-1':>10}   AA")
    worst = 99.0
    for w, h in VIEWPORTS:
        r = measure(base, w, h, tmp)
        worst = min(worst, r["t4"])
        ok = "ja" if r["t4"] >= 4.5 else "NEIN"
        print(f"  {r['viewport']:<12}{r['col_hex']:<10}{r['t4']:>10.2f}{r['t1']:>10.2f}   {ok}")
        print(
            f"  {'':<12}Seitenraum {r['side_hex']} → --text-4 {r['side_t4']:.2f} "
            f"(dort steht nie ein Buchstabe)"
        )

    print()
    print(
        f"SCHLECHTESTER FALL ueber alle Fenster: --text-4 {worst:.2f}:1 "
        f"({'AA gehalten' if worst >= 4.5 else 'AA VERFEHLT'})"
    )

    if "--sweep" in sys.argv:
        print()
        print("SCHLEIER-KURVE (1440x900, --amayadori-veil ueberschrieben)")
        # Eng um die Entscheidungsstelle: AA reisst zwischen 0,84 und 0,90, und
        # 0,90 haelt mit nur 0,22 Stufen Luft. 0,92/0,94 stehen deshalb mit in
        # der Kurve — nicht weil sie gewaehlt sind, sondern damit die Alternative
        # in RESULT.md eine Zahl hat statt einer Vermutung.
        for veil in (0.6, 0.7, 0.78, 0.84, 0.9, 0.92, 0.94, 0.95):
            r = measure(f"{base}&veil={veil}", 1440, 900, tmp, tag=f"veil{veil}-")
            mark = "  ← gewaehlt" if abs(veil - 0.9) < 1e-9 else ""
            print(
                f"  {veil:.2f}  {r['col_hex']}  --text-4 {r['t4']:.2f}"
                f"{'' if r['t4'] >= 4.5 else '  (unter AA)'}{mark}"
            )

    print()
    print(f"Screenshots: {tmp}")
    return 0 if worst >= 4.5 else 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Kontrast-Harness für das Theme „natsunohi" (夏の日).

Warum es diese Datei gibt
─────────────────────────
Im Repo existierte am 18.08. KEIN Kontrast-Mess-Harness (verifiziert). Die
Asagiri-Zahlen im Datei-Kopf jenes Themes sind mit einem Ad-hoc-Messfühler
entstanden, und die Lehre desselben Tages steht dort ausdrücklich: ein Fühler
OHNE den kompletten CSS-Import hat gelogen. Darum lädt dieses Harness immer
index.css (Basistoken) UND die Theme-Datei, und es misst auf echten
headless-Chrome-Pixeln statt auf gerechneten Farben.

Zwei Messungen, weil beide etwas anderes beweisen
─────────────────────────────────────────────────
1) PALETTE — eine Seite mit Farbkacheln. Jede Kachel wird gerendert und ihr
   Mittelpixel aus dem Screenshot gelesen. Das gibt die EXAKTEN sRGB-Werte, die
   der Browser aus den OKLCH-Angaben macht (inkl. Gamut-Mapping), und damit
   auch die *-soft-Füllungen als echte Komposition über --bg-surface.
2) SZENE — eine leere Seite mit Atmosphäre + Szene + Schleier + Deko-Ebenen.
   Screenshot bei 1440/1280/1024 px, dann der SCHLECHTESTE Pixel innerhalb der
   920-px-App-Spalte gegen --text-4 und --text-1.

   Die Szene wird ZWEIMAL gemessen:
     • „floor"  — die aufhellenden Deko-Ebenen (Flirren, Zikaden-Licht) sind
       abgeschaltet. Das ist der Boden: beide Ebenen können auf hellem Grund
       nur AUFHELLEN, also den Kontrast gegen dunkle Schrift nur verbessern.
       Kein Animationsframe kann je unter diesen Wert fallen — deshalb ist das
       der Wert, der im Datei-Kopf steht.
     • „full"   — alles an, Animationen per prefers-reduced-motion eingefroren.
       Nur zur Kontrolle, dass die Ebenen wirklich aufhellen.

Aufruf:  python3 measure.py            (misst alles)
         python3 measure.py --keep     (HTML/PNG im Ausgabeordner behalten)
"""

from __future__ import annotations

import json
import os
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import time
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
THEMES = os.path.join(REPO, "frontend", "public", "themes")
INDEX_CSS = os.path.join(REPO, "frontend", "src", "index.css")
# Überschreibbar, damit das Harness gegen ein FREMDES, bereits veröffentlichtes
# Theme geeicht werden kann: `THEME_ID=asagiri python3 measure.py` muss die im
# Asagiri-Kopf dokumentierten Werte (4,81 / 12,73) reproduzieren. Tut es das
# nicht, lügt der Messfühler — und nicht das Theme.
THEME_ID = os.environ.get("THEME_ID", "natsunohi")
THEME_CSS = os.path.join(THEMES, THEME_ID + ".css")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

# Die App-Lesespalte (index.css: `.app { max-width: 920px; margin: 0 auto }`).
APP_COLUMN = 920
WIDTHS = [(1440, 900), (1280, 800), (1024, 768)]


# ── PNG ohne Pillow ─────────────────────────────────────────────────────────
def read_png(path: str):
    """Minimaler PNG-Leser (8 bit, Farbtyp 2/6, nicht interlaced) → (w, h, rows).

    Chrome schreibt genau dieses Format. Rückgabe: rows[y] = bytes in RGB(A).
    """
    with open(path, "rb") as fh:
        data = fh.read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "kein PNG"
    pos = 8
    idat = bytearray()
    width = height = depth = ctype = None
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos : pos + 4])
        ctag = data[pos + 4 : pos + 8]
        body = data[pos + 8 : pos + 8 + length]
        pos += 12 + length
        if ctag == b"IHDR":
            width, height, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert depth == 8 and ctype in (2, 6) and interlace == 0, (depth, ctype, interlace)
        elif ctag == b"IDAT":
            idat += body
        elif ctag == b"IEND":
            break
    raw = zlib.decompress(bytes(idat))
    channels = 3 if ctype == 2 else 4
    stride = width * channels
    rows: list[bytearray] = []
    prev = bytearray(stride)
    off = 0
    for _ in range(height):
        ftype = raw[off]
        off += 1
        line = bytearray(raw[off : off + stride])
        off += stride
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
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        rows.append(line)
        prev = line
    return width, height, rows, channels


# ── WCAG 2.1 ────────────────────────────────────────────────────────────────
def _lin(c: int) -> float:
    s = c / 255.0
    return s / 12.92 if s <= 0.04045 else ((s + 0.055) / 1.055) ** 2.4


def luminance(rgb) -> float:
    r, g, b = rgb[0], rgb[1], rgb[2]
    return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)


def contrast(a, b) -> float:
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def hexof(rgb) -> str:
    return "#%02x%02x%02x" % (rgb[0], rgb[1], rgb[2])


# ── Chrome ──────────────────────────────────────────────────────────────────
def shoot(html_path: str, png_path: str, size, profile_dir: str) -> None:
    """Ein Screenshot, ein eigener Chrome.

    Chrome 151 schreibt die PNG-Datei zuverlässig, BEENDET sich danach aber
    nicht mehr (der alte `--screenshot`-und-raus-Weg ist tot). Darum: starten,
    auf die fertige Datei warten (Größe zwei Runden stabil), dann NUR den
    eigenen Prozessbaum beenden. Fremde Chrome-Fenster bleiben unangetastet —
    wir kennen unsere PID und benutzen ein eigenes --user-data-dir.
    """
    w, h = size
    if os.path.exists(png_path):
        os.remove(png_path)
    cmd = [
        CHROME,
        "--headless=new",
        "--disable-gpu",
        "--hide-scrollbars",
        "--force-color-profile=srgb",
        "--force-device-scale-factor=1",
        "--allow-file-access-from-files",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-extensions",
        "--user-data-dir=" + profile_dir,  # eigener Profilordner: fremde Chromes bleiben unberührt
        "--window-size=%d,%d" % (w, h),
        "--virtual-time-budget=2500",
        "--screenshot=" + png_path,
        "file://" + html_path,
    ]
    proc = subprocess.Popen(
        cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, start_new_session=True
    )
    last = -1
    stable = 0
    try:
        for _ in range(600):  # bis 60 s
            time.sleep(0.1)
            if proc.poll() is not None and os.path.exists(png_path):
                break
            if os.path.exists(png_path):
                size_now = os.path.getsize(png_path)
                if size_now > 0 and size_now == last:
                    stable += 1
                    if stable >= 3:
                        break
                else:
                    stable = 0
                last = size_now
    finally:
        if proc.poll() is None:
            try:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                proc.kill()
            proc.wait(timeout=20)
    if not os.path.exists(png_path) or os.path.getsize(png_path) == 0:
        err = proc.stderr.read().decode("utf-8", "replace") if proc.stderr else ""
        sys.exit("Chrome hat nichts gerendert:\n" + err[-3000:])


# ── Seiten ──────────────────────────────────────────────────────────────────
HEAD = """<!doctype html><html data-theme='{tid}'><head><meta charset='utf-8'>
<link rel='stylesheet' href='file://{index}'>
<link rel='stylesheet' href='file://{theme}'>
<style>{extra}</style></head><body>{body}</body></html>"""

# Die Kacheln: (Bezeichner, Aufbau). "solid" = eine Fläche, "over" = Füllung
# über --bg-surface (so bauen die Pills/Badges in index.css).
SOLIDS = [
    "bg-base",
    "bg-surface",
    "bg-elevated",
    "bg-subtle",
    "bg-user",
    "bg-hairline",
    "bg-hairline-dashed",
    "text-1",
    "text-2",
    "text-3",
    "text-4",
    "accent",
    "accent-ink",
    "success",
    "warn",
    "error",
]
OVERS = ["accent-soft", "accent-faint", "success-soft", "warn-soft", "error-soft", "accent-glow"]

CELL = 40


def palette_html(extra: str = "") -> str:
    cells = []
    for name in SOLIDS:
        cells.append("<i style='background:var(--%s)'></i>" % name)
    for name in OVERS:
        cells.append(
            "<i style='background:var(--bg-surface)'>"
            "<b style='background:var(--%s)'></b></i>" % name
        )
    css = (
        "html,body{margin:0;padding:0;background:#808080}"
        "i{display:block;float:left;width:%dpx;height:%dpx}"
        "b{display:block;width:100%%;height:100%%}" % (CELL, CELL)
    )
    return HEAD.format(
        tid=THEME_ID,
        index=INDEX_CSS,
        theme=THEME_CSS,
        extra=css + extra,
        body="".join(cells),
    )


# Die Szene: leere App-Spalte, sonst nichts. Zusätzlich das reduced-motion-Bild
# erzwingen wäre unnötig — Chrome headless animiert bei virtual time ohnehin
# deterministisch; die „floor"-Variante schaltet die aufhellenden Ebenen ganz ab.
SCENE_BODY = "<div class='app'></div>"
# ACHTUNG, hier lag ein stiller Messfehler: solange der Schleier auf demselben
# Element saß wie die Zeichnung, blendete diese Variante mit `:root::after` die
# DEKO aus. Seit der zweiten Fassung ist `:root::after` DER SCHLEIER — dieselbe
# Regel hätte ihn mitabgeschaltet und einen Kontrast von 1,0 gemeldet, also ein
# Theme für kaputt erklärt, das in Ordnung ist. Der Boden ist die Seite MIT
# Schleier und OHNE Luft: die Luft-Ebene ist nahezu weiß auf hellem Grund und
# kann nur aufhellen, der schlechteste Fall ist also die Seite ohne sie.
FLOOR_EXTRA = ":root[data-theme='%s'] body::before{display:none!important}" % THEME_ID


# Beide Szenen-Ebenen wogen, der Schleier steht (er ist ein eigenes Element und
# wird nicht mitgeschert). Trotzdem ist der schlechteste Moment nicht die Ruhe,
# sondern der volle Ausschlag: dort steht ein anderer Bildpunkt unter der
# Spaltenkante. Diese Variante hält beide Animationen an und setzt die
# Extremwerte fest ein — hintere Ebene ±1,1°, vordere ±3,2°, wie in der CSS.
SKEW_EXTRA = FLOOR_EXTRA + (
    ":root[data-theme='%s'] body::after{animation:none!important;"
    "transform:skewX(%s)!important}"
    ":root[data-theme='%s']::before{animation:none!important;"
    "transform:skewX(%s)!important}" % (THEME_ID, "{DEG}", THEME_ID, "{DEG3}")
)


def scene_html(variant: str) -> str:
    if variant == "floor":
        extra = FLOOR_EXTRA
    elif variant.startswith("skew"):
        sign = 1.0 if variant == "skew+" else -1.0
        extra = SKEW_EXTRA.replace("{DEG}", f"{1.1 * sign}deg").replace(
            "{DEG3}", f"{3.2 * sign}deg"
        )
    else:
        extra = ""
    return HEAD.format(
        tid=THEME_ID, index=INDEX_CSS, theme=THEME_CSS, extra=extra, body=SCENE_BODY
    )


# ── Messung ─────────────────────────────────────────────────────────────────
def measure() -> dict:
    out = tempfile.mkdtemp(prefix="natsunohi-contrast-")
    profile = os.path.join(out, "chrome-profile")  # NUR unser eigenes Profil
    result: dict = {"palette": {}, "scene": {}}

    # 1) Palette
    p_html = os.path.join(out, "palette.html")
    with open(p_html, "w") as fh:
        fh.write(palette_html())
    p_png = os.path.join(out, "palette.png")
    shoot(p_html, p_png, (CELL * len(SOLIDS + OVERS) + 80, 200), profile)
    w, h, rows, ch = read_png(p_png)
    colors: dict[str, tuple] = {}
    for i, name in enumerate(SOLIDS + OVERS):
        x = i * CELL + CELL // 2
        y = CELL // 2
        px = rows[y][x * ch : x * ch + 3]
        colors[name] = (px[0], px[1], px[2])
    result["colors"] = {k: hexof(v) for k, v in colors.items()}

    # Token-Matrix
    grounds = ["bg-base", "bg-surface", "bg-elevated", "bg-subtle", "bg-user"]
    fores = ["text-1", "text-2", "text-3", "text-4", "accent", "success", "warn", "error"]
    matrix = {}
    for f in fores:
        row = {g: round(contrast(colors[f], colors[g]), 2) for g in grounds}
        row["WORST"] = round(min(row.values()), 2)
        matrix[f] = row
    result["matrix"] = matrix
    result["on_own_soft"] = {
        "accent": round(contrast(colors["accent"], colors["accent-soft"]), 2),
        "success": round(contrast(colors["success"], colors["success-soft"]), 2),
        "warn": round(contrast(colors["warn"], colors["warn-soft"]), 2),
        "error": round(contrast(colors["error"], colors["error-soft"]), 2),
    }
    result["accent_ink_on_accent"] = round(contrast(colors["accent-ink"], colors["accent"]), 2)

    # 2) Szene
    for variant in ("floor", "skew+", "skew-", "full"):
        s_html = os.path.join(out, "scene-%s.html" % variant.replace("+", "p").replace("-", "m"))
        with open(s_html, "w") as fh:
            fh.write(scene_html(variant))
        per_width = {}
        for (vw, vh) in WIDTHS:
            png = os.path.join(out, "scene-%s-%d.png" % (variant.replace("+","p").replace("-","m"), vw))
            shoot(s_html, png, (vw, vh), profile)
            w, h, rows, ch = read_png(png)
            left = max(0, (w - APP_COLUMN) // 2)
            right = w - left
            worst4 = (99.0, None)
            worst1 = (99.0, None)
            seen: dict[tuple, bool] = {}
            for y in range(0, h, 2):  # jede 2. Zeile — 0,5 Mio Pixel reichen für die Suche
                row = rows[y]
                for x in range(left, right, 2):
                    px = (row[x * ch], row[x * ch + 1], row[x * ch + 2])
                    if px in seen:
                        continue
                    seen[px] = True
                    c4 = contrast(colors["text-4"], px)
                    if c4 < worst4[0]:
                        worst4 = (c4, px, x, y)
                    c1 = contrast(colors["text-1"], px)
                    if c1 < worst1[0]:
                        worst1 = (c1, px, x, y)
            per_width[vw] = {
                "text-4": round(worst4[0], 2),
                "text-4_pixel": hexof(worst4[1]),
                "text-4_at": [worst4[2], worst4[3]],
                "text-1": round(worst1[0], 2),
                "text-1_pixel": hexof(worst1[1]),
                "column": [left, right],
                "distinct_colors": len(seen),
            }
        result["scene"][variant] = per_width

    result["_outdir"] = out
    return result


if __name__ == "__main__":
    r = measure()
    print(json.dumps({k: v for k, v in r.items() if k != "_outdir"}, indent=2, ensure_ascii=False))
    if "--keep" in sys.argv:
        print("\nArtefakte: " + r["_outdir"], file=sys.stderr)
    else:
        shutil.rmtree(r["_outdir"], ignore_errors=True)

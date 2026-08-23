"""Farb-Werkzeug des Aoi-Pods (Theme-Revival 2026-08-18/19).

Rechnet sRGB-Hex und OKLCH auf die WCAG-2.1-Relativluminanz und daraus die
Kontrastverhaeltnisse, mit denen der Datei-Kopf von `public/themes/aoi.css`
argumentiert. Reine Standardbibliothek — das Ding soll auf jedem Mac laufen,
auf dem `python3` steht, ohne ein einziges Paket zu installieren.

Aufruf:  python3 tools/theme-contrast/wcag_aoi.py
"""

from __future__ import annotations

import math

# ── sRGB ────────────────────────────────────────────────────────────────────


def hex_to_rgb(h: str) -> tuple[float, float, float]:
    h = h.strip().lstrip("#")
    return tuple(int(h[i : i + 2], 16) / 255 for i in (0, 2, 4))  # type: ignore[return-value]


def rgb_to_hex(rgb: tuple[float, float, float]) -> str:
    return "#" + "".join(f"{max(0, min(255, round(c * 255))):02x}" for c in rgb)


def _lin(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def luminance(rgb: tuple[float, float, float]) -> float:
    r, g, b = (_lin(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a: tuple[float, float, float], b: tuple[float, float, float]) -> float:
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def ratio(hex_a: str, hex_b: str) -> float:
    return contrast(hex_to_rgb(hex_a), hex_to_rgb(hex_b))


# ── OKLCH → sRGB (Ottosson, D65) ────────────────────────────────────────────


def oklch_to_rgb(L: float, C: float, H: float) -> tuple[float, float, float]:
    h = math.radians(H)
    a, bb = C * math.cos(h), C * math.sin(h)
    l_ = L + 0.3963377774 * a + 0.2158037573 * bb
    m_ = L - 0.1055613458 * a - 0.0638541728 * bb
    s_ = L - 0.0894841775 * a - 1.2914855480 * bb
    l, m, s = l_**3, m_**3, s_**3
    r = +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
    g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
    b = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s

    def enc(c: float) -> float:
        c = 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055
        return c

    return (enc(r), enc(g), enc(b))


def in_gamut(L: float, C: float, H: float, eps: float = 1e-4) -> bool:
    return all(-eps <= c <= 1 + eps for c in oklch_to_rgb(L, C, H))


def oklch_hex(L: float, C: float, H: float) -> str:
    return rgb_to_hex(oklch_to_rgb(L, C, H))


# ── Alpha-Komposit (Schleier ueber Zeichnung) ───────────────────────────────


def over(fg_hex: str, alpha: float, bg_hex: str) -> str:
    f, b = hex_to_rgb(fg_hex), hex_to_rgb(bg_hex)
    return rgb_to_hex(tuple(alpha * f[i] + (1 - alpha) * b[i] for i in range(3)))  # type: ignore[arg-type]


# ── Der Aoi-Tokensatz ───────────────────────────────────────────────────────

GROUNDS = {
    "base": "#0c1017",
    "surface": "#141a26",
    "elevated": "#1a2333",
    "subtle": "#1e2839",
    "user": "#17222f",
}

INKS = {
    "text-1": "#e8eef7",
    "text-2": "#b9c3d4",
    "text-3": "#8c97ab",
    "text-4": "#848fa3",
    "accent": "#5ea0f2",
    "success": "#77c99a",
    "warn": "#e3b36a",
    "error": "#e07a6e",
}

# Deckkraft der *-soft-Fuellungen (Chip-Fall: Farbe auf ihrer eigenen Tönung)
SOFT_ALPHA = 0.16


def table() -> None:
    names = list(GROUNDS)
    print("           " + "".join(f"{n:>10}" for n in names) + f"{'WORST':>10}")
    for ink, ihex in INKS.items():
        vals = [ratio(ihex, GROUNDS[g]) for g in names]
        worst = min(vals)
        print(f"{ink:>10} " + "".join(f"{v:>10.2f}" for v in vals) + f"{worst:>10.2f}")

    print()
    print("Chip: Farbe auf ihrer eigenen *-soft-Fuellung (alpha %.2f)" % SOFT_ALPHA)
    for ink in ("accent", "success", "warn", "error"):
        for g in ("base", "surface"):
            fill = over(INKS[ink], SOFT_ALPHA, GROUNDS[g])
            print(f"  {ink:>8} auf soft/{g:<8} {fill}  {ratio(INKS[ink], fill):.2f}")

    print()
    print(f"  accent-ink #0c1017 auf --accent: {ratio('#0c1017', INKS['accent']):.2f}")
    for hl, val in (("hairline", "#1f2938"), ("hairline-dashed", "#2a3548")):
        line = "  " + f"{hl:>16}: " + "  ".join(
            f"{g} {ratio(val, GROUNDS[g]):.2f}" for g in GROUNDS
        )
        print(line)


if __name__ == "__main__":
    table()

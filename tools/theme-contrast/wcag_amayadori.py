"""Farb-Werkzeug des Amayadori-Pods (Theme-Revival 2026-08-18/19).

Rechnet sRGB-Hex auf die WCAG-2.1-Relativluminanz und daraus die Verhaeltnisse,
mit denen der Kopf von `public/themes/amayadori.css` argumentiert. Reine
Standardbibliothek — laeuft auf jedem Mac mit python3, ohne ein Paket.

Aufruf:  python3 tools/theme-contrast/wcag_amayadori.py
"""

from __future__ import annotations


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


def ratio(a: str, b: str) -> float:
    return contrast(hex_to_rgb(a), hex_to_rgb(b))


def over(fg: str, alpha: float, bg: str) -> str:
    f, b = hex_to_rgb(fg), hex_to_rgb(bg)
    return rgb_to_hex(tuple(alpha * f[i] + (1 - alpha) * b[i] for i in range(3)))  # type: ignore[arg-type]


# ── Der Amayadori-Tokensatz (Seele = die Alt-Palette aus old/amayadori.css) ──

GROUNDS = {
    "base": "#100e0c",
    "surface": "#1a1613",
    "elevated": "#221d18",
    "subtle": "#2c2620",
    "user": "#241d18",
}

INKS = {
    "text-1": "#f2e9dc",
    "text-2": "#d3c6b4",
    "text-3": "#a2947f",
    "text-4": "#9a8c74",  # +7 % gegenueber Andis #90836c — s. Kopf von amayadori.css
    "accent": "#ee9b6e",
    "success": "#8fbf87",
    "warn": "#e8c07a",
    "error": "#e0736a",
}

HAIRLINES = {"hairline": "#3a322a", "hairline-dashed": "#4a4038"}
ACCENT_INK = "#100e0c"
SOFT_ALPHA = {"accent": 0.2, "success": 0.16, "warn": 0.16, "error": 0.16}


def table() -> None:
    names = list(GROUNDS)
    print("           " + "".join(f"{n:>10}" for n in names) + f"{'WORST':>10}")
    worst_text = 99.0
    for ink, ihex in INKS.items():
        vals = [ratio(ihex, GROUNDS[g]) for g in names]
        w = min(vals)
        if ink.startswith("text"):
            worst_text = min(worst_text, w)
        print(f"{ink:>10} " + "".join(f"{v:>10.2f}" for v in vals) + f"{w:>10.2f}")

    print()
    print("CHIP — Farbe auf ihrer EIGENEN *-soft-Fuellung")
    for ink in ("accent", "success", "warn", "error"):
        for g in ("base", "surface", "subtle"):
            fill = over(INKS[ink], SOFT_ALPHA[ink], GROUNDS[g])
            print(f"  {ink:>8} auf soft/{g:<8} {fill}  {ratio(INKS[ink], fill):>5.2f}")

    print()
    print("TEXT-4 auf einer getoenten Status-Fuellung (.hero__time-Renderpfad)")
    for ink in ("accent", "success", "warn", "error"):
        fill = over(INKS[ink], SOFT_ALPHA[ink], GROUNDS["surface"])
        print(f"  auf {ink:>8}-soft/surface {fill}  text-4 {ratio(INKS['text-4'], fill):>5.2f}")

    print()
    print(f"  accent-ink {ACCENT_INK} auf --accent: {ratio(ACCENT_INK, INKS['accent']):.2f}")
    for hl, val in HAIRLINES.items():
        print(f"  {hl:>16} {val}: " + "  ".join(f"{g} {ratio(val, GROUNDS[g]):.2f}" for g in GROUNDS))

    print()
    print(f"Schlechtester Textwert der Matrix: {worst_text:.2f}")


if __name__ == "__main__":
    table()

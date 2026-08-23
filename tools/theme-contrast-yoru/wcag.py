"""Farb-Werkzeug des Yoru-Pods (Theme-Revival 2026-08-18/19).

Nur sRGB→WCAG-2.1-Relativluminanz und daraus das Kontrastverhaeltnis. Bewusst
KEINE OKLCH-Umrechnung: die Farben, gegen die dieser Pod rechnet, kommen aus dem
Bild, das Chrome gerendert hat (der Farb-Streifen im Harness) — nie aus eigener
Mathematik, die dann gegen sich selbst geprueft wuerde. Reine
Standardbibliothek; laeuft auf jedem Mac mit python3.
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
    """WCAG 2.1 Relativluminanz."""
    r, g, b = (_lin(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast_lum(la: float, lb: float) -> float:
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def contrast(a: tuple[float, float, float], b: tuple[float, float, float]) -> float:
    return contrast_lum(luminance(a), luminance(b))

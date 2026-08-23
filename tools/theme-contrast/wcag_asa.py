"""WCAG-2.1-Kontrast auf fertigen sRGB-Pixeln — die kleine, langweilige Hälfte.

Bewusst NUR sRGB→Luminanz→Kontrast. Keine oklch-Umrechnung: die macht Chrome
(siehe harness-asa-tokens.html). Dieses Modul bekommt ausschliesslich Bytes, die
schon auf dem Bildschirm standen, und darf deshalb nichts falsch machen, was sich
mit einem zweiten eigenen Fehler wieder aufheben koennte.
"""

from __future__ import annotations

# sRGB-Kanal → Linearlicht, einmal fuer alle 256 moeglichen Bytewerte.
# Der Scan liest Millionen Pixel; ein Tabellenzugriff ist dort billiger als
# zwei Gleitkomma-Potenzen.
_LIN = [
    (c / 255 / 12.92) if (c / 255) <= 0.04045 else (((c / 255 + 0.055) / 1.055) ** 2.4)
    for c in range(256)
]

# Die drei Gewichte aus WCAG 2.1 / Rec.709.
_R, _G, _B = 0.2126, 0.7152, 0.0722


def luminance(r: int, g: int, b: int) -> float:
    """Relative Leuchtdichte eines 8-bit-sRGB-Tripels."""
    return _R * _LIN[r] + _G * _LIN[g] + _B * _LIN[b]


def contrast(l1: float, l2: float) -> float:
    """Kontrastverhaeltnis zweier Leuchtdichten (Reihenfolge egal)."""
    hi, lo = (l1, l2) if l1 >= l2 else (l2, l1)
    return (hi + 0.05) / (lo + 0.05)


def contrast_rgb(a: tuple[int, int, int], b: tuple[int, int, int]) -> float:
    return contrast(luminance(*a), luminance(*b))


def to_hex(rgb: tuple[int, int, int]) -> str:
    return "#%02x%02x%02x" % rgb

/**
 * OKLCH → sRGB → WCAG-2.1 — die Rechen-Hälfte des Kontrast-Harness.
 * ───────────────────────────────────────────────────────────────────────────
 * Warum es das gibt: die Themen-Dateien schreiben ihre Kontraste in den Kopf
 * („selbst nachgerechnet"). Bis zum 18.08. wurde dafür geschätzt — und der
 * Messfühler ohne vollständigen CSS-Import hat gelogen. Diese Datei rechnet die
 * PALETTE (Token gegen Token, exakt); `measure.mjs` misst das echte RENDER
 * (Chrome headless, alle Schichten übereinander). Beides zusammen ist der
 * Beweis; eines allein ist eine Meinung.
 *
 * Kein npm, keine Abhängigkeit: das Harness muss in einem Worktree ohne
 * `npm install` sofort laufen.
 *
 * CLI:
 *   node color.mjs "oklch(0.9 0.012 78)" "oklch(0.105 0.008 330)"
 *     → Hex beider Farben + Kontrastverhältnis
 *   node color.mjs --over "oklch(0.95 0.03 70 / 0.8)" "oklch(0.105 0.008 330)"
 *     → Alpha-Komposit (Vordergrund ÜBER Hintergrund) als Hex
 */

/* ── OKLab-Matrizen (Björn Ottosson, oklab.h) ───────────────────────────── */

function oklabToLinearSrgb(L, a, b) {
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.291485548 * b;
  return [
    +4.0767416621 * l_ ** 3 - 3.3077115913 * m_ ** 3 + 0.2309699292 * s_ ** 3,
    -1.2684380046 * l_ ** 3 + 2.6097574011 * m_ ** 3 - 0.3413193965 * s_ ** 3,
    -0.0041960863 * l_ ** 3 - 0.7034186147 * m_ ** 3 + 1.707614701 * s_ ** 3,
  ];
}

/** Lineares sRGB → gammakodiertes sRGB (IEC 61966-2-1). */
function encode(c) {
  return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
}

/** Gammakodiertes sRGB → linear (WCAG 2.1 nutzt exakt diese Kurve). */
function decode(c) {
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

/**
 * OKLCH → sRGB-Kanäle 0..1, UNGEKLEMMT.
 * Ungeklemmt ist Absicht: ein Kanal außerhalb [0,1] heißt „außerhalb des
 * sRGB-Gamuts" — der Browser bildet das still ab, und genau das soll die
 * Gamut-Prüfung sehen statt es weggerundet zu bekommen.
 */
export function oklchToSrgb(L, C, h) {
  const rad = (h * Math.PI) / 180;
  const [lr, lg, lb] = oklabToLinearSrgb(L, C * Math.cos(rad), C * Math.sin(rad));
  return [encode(lr), encode(lg), encode(lb)];
}

/** Liegt die Farbe im sRGB-Gamut? (Toleranz 0.5/255 gegen Rundungsrauschen.) */
export function inGamut(rgb) {
  const tol = 0.5 / 255;
  return rgb.every((c) => c >= -tol && c <= 1 + tol);
}

/** WCAG-2.1-Relativluminanz aus sRGB 0..1. */
export function luminance([r, g, b]) {
  const c = [r, g, b].map((v) => decode(Math.min(1, Math.max(0, v))));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}

/** WCAG-2.1-Kontrastverhältnis zweier sRGB-Tripel. */
export function contrast(a, b) {
  const la = luminance(a);
  const lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/** sRGB 0..1 → #rrggbb (geklemmt — nur für die Anzeige). */
export function hex(rgb) {
  return (
    '#' +
    rgb
      .map((c) =>
        Math.round(Math.min(1, Math.max(0, c)) * 255)
          .toString(16)
          .padStart(2, '0'),
      )
      .join('')
  );
}

/** Vordergrund mit Alpha ÜBER deckendem Hintergrund (Standard „source-over"). */
export function over(fg, alpha, bg) {
  return [0, 1, 2].map((i) => fg[i] * alpha + bg[i] * (1 - alpha));
}

/**
 * Parst `oklch(L C H)` bzw. `oklch(L C H / A)` — auch mit Prozent-L.
 * Rückgabe: { rgb, alpha }.
 */
export function parseOklch(str) {
  const m = /oklch\(\s*([\d.]+%?)\s+([\d.]+)\s+([\d.]+)\s*(?:\/\s*([\d.]+%?)\s*)?\)/i.exec(str);
  if (!m) throw new Error(`kein oklch(): ${str}`);
  const L = m[1].endsWith('%') ? parseFloat(m[1]) / 100 : parseFloat(m[1]);
  const alpha = m[4] === undefined ? 1 : m[4].endsWith('%') ? parseFloat(m[4]) / 100 : parseFloat(m[4]);
  return { rgb: oklchToSrgb(L, parseFloat(m[2]), parseFloat(m[3])), alpha };
}

/** #rgb / #rrggbb → sRGB 0..1. */
export function parseHex(str) {
  let s = str.replace('#', '').trim();
  if (s.length === 3) s = s.split('').map((c) => c + c).join('');
  return [0, 2, 4].map((i) => parseInt(s.slice(i, i + 2), 16) / 255);
}

/** Akzeptiert oklch(...) oder #hex. */
export function parseColor(str) {
  return str.trim().startsWith('#') ? { rgb: parseHex(str), alpha: 1 } : parseOklch(str);
}

/* ── CLI ─────────────────────────────────────────────────────────────────── */

if (import.meta.url === `file://${process.argv[1]}`) {
  const args = process.argv.slice(2);
  if (args[0] === '--over') {
    const fg = parseColor(args[1]);
    const bg = parseColor(args[2]);
    const out = over(fg.rgb, fg.alpha, bg.rgb);
    console.log(`${hex(fg.rgb)} @${fg.alpha} über ${hex(bg.rgb)}  =  ${hex(out)}`);
  } else if (args.length >= 2) {
    const a = parseColor(args[0]);
    const b = parseColor(args[1]);
    const ratio = contrast(a.rgb, b.rgb);
    const gamut = [a, b].every((c) => inGamut(c.rgb)) ? 'sRGB ok' : 'AUSSERHALB sRGB';
    console.log(
      `${hex(a.rgb)}  vs  ${hex(b.rgb)}   ${ratio.toFixed(2)}:1  ` +
        `${ratio >= 4.5 ? 'AA' : ratio >= 3 ? 'nur-AA-large/non-text' : 'FAIL'}  (${gamut})`,
    );
  } else {
    console.log('nutzung: node color.mjs <farbe> <farbe> | node color.mjs --over <farbe+alpha> <farbe>');
  }
}

/**
 * DER HELLIGKEITS-MESSER — „wirkt das Bild heller?" als Zahl statt als Meinung.
 * ═══════════════════════════════════════════════════════════════════════════
 * ANLASS (21.08.): Andis Urteil über yoru v2 war „leider sehr dunkel". Die
 * bestehende Messkette konnte darauf nicht antworten. `measure.mjs` misst den
 * HELLSTEN Pixel der Lesespalte (den Worst Case der Lesbarkeit) — und genau der
 * war schon vorher fast am Anschlag, während das Bild trotzdem dunkel wirkte.
 * Beides ist kein Widerspruch: gefühlte Helligkeit ist der TYPISCHE Pixel, nicht
 * der hellste. Ein Bild aus 95 % Werten um 8/255 und 5 % Werten um 33/255 hat
 * einen perfekten Spitzenwert und wirkt wie ein schwarzes Rechteck.
 *
 * Darum misst diese Datei die VERTEILUNG über das ganze Bild:
 *   p50  der Median — „so hell ist dieses Bild für das Auge"
 *   p99  das obere Prozent — „so hell sind die Lichter"
 * Dazu p05/p25/p75/p95, damit man sieht, ob die Tonleiter gespreizt ist oder
 * ob alles auf einem Haufen liegt.
 *
 * ZWEI SKALEN je Wert, und beide werden gebraucht:
 *   Lrel  WCAG-Luminanz (0…1) — die Größe, in der die Kontrast-Zusage rechnet.
 *   sRGB  derselbe Wert zurück in einen Grauwert 0…255 — die Größe, in der man
 *         am Bildschirm URTEILT. Im tiefen Schwarz sind die beiden extrem
 *         ungleich gedehnt (Lrel 0,0008 ≈ 8/255, Lrel 0,0114 ≈ 33/255): eine
 *         Verdopplung der Luminanz ist dort nur ein Viertel Schritt im Grauwert.
 *         Wer nur Lrel berichtet, klingt nach viel und liefert wenig.
 *
 * NUTZUNG
 *   node tools/theme-contrast/helligkeit.mjs <a.png> [b.png …]
 *   node tools/theme-contrast/helligkeit.mjs --vergleich vorher.png nachher.png
 */

import { readFileSync } from 'node:fs';
import { basename } from 'node:path';
import { decodePng, pixel } from './png.mjs';

/** sRGB-Kanal → linear. ACHTUNG: `pixel()` aus png.mjs liefert bereits 0…1,
    nicht 0…255 — ein erster Wurf hat hier nochmal durch 255 geteilt und daraus
    „das Bild ist komplett schwarz" gelesen. Ein Messfehler, der die Diagnose
    bestätigt hätte, ist die gefährlichste Sorte. */
function lin(e) {
  return e <= 0.04045 ? e / 12.92 : ((e + 0.055) / 1.055) ** 2.4;
}
/** linear → sRGB-Grauwert 0…255, damit eine Lrel-Zahl wieder anschaulich wird. */
function grau(y) {
  const c = y <= 0.0031308 ? 12.92 * y : 1.055 * Math.pow(Math.max(y, 0), 1 / 2.4) - 0.055;
  return Math.round(Math.min(1, Math.max(0, c)) * 255);
}

export function verteilung(file) {
  const img = decodePng(readFileSync(file));
  const ys = new Float64Array(img.width * img.height);
  let i = 0;
  for (let y = 0; y < img.height; y++) {
    for (let x = 0; x < img.width; x++) {
      const [r, g, b] = pixel(img, x, y);
      ys[i++] = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
    }
  }
  ys.sort();
  const q = (p) => ys[Math.min(ys.length - 1, Math.round((p / 100) * (ys.length - 1)))];
  const mittel = ys.reduce((s, v) => s + v, 0) / ys.length;
  return {
    file,
    w: img.width,
    h: img.height,
    p05: q(5), p25: q(25), p50: q(50), p75: q(75), p95: q(95), p99: q(99),
    mittel,
  };
}

function zeile(v) {
  const s = (y) => `${y.toFixed(5)} (${String(grau(y)).padStart(3)}/255)`;
  return (
    `${basename(v.file).padEnd(26)} ${v.w}×${v.h}\n` +
    `   p05 ${s(v.p05)}   p25 ${s(v.p25)}   p50 ${s(v.p50)}\n` +
    `   p75 ${s(v.p75)}   p95 ${s(v.p95)}   p99 ${s(v.p99)}   Mittel ${s(v.mittel)}`
  );
}

const args = process.argv.slice(2);
if (args.length === 0) {
  console.error('nutzung: node helligkeit.mjs <a.png> [b.png …]');
  process.exit(2);
}
const vergleich = args[0] === '--vergleich';
const files = vergleich ? args.slice(1) : args;
const werte = files.map(verteilung);
for (const v of werte) console.log(zeile(v));

if (vergleich && werte.length === 2) {
  const [a, b] = werte;
  console.log('\nΔ (nachher gegen vorher, in Grauwerten):');
  for (const k of ['p05', 'p25', 'p50', 'p75', 'p95', 'p99', 'mittel']) {
    const ga = grau(a[k]);
    const gb = grau(b[k]);
    const fak = a[k] > 0 ? b[k] / a[k] : Infinity;
    console.log(
      `  ${k.padEnd(6)} ${String(ga).padStart(3)} → ${String(gb).padStart(3)}  ` +
        `(${gb - ga >= 0 ? '+' : ''}${gb - ga}, Luminanz ×${fak.toFixed(2)})`,
    );
  }
}

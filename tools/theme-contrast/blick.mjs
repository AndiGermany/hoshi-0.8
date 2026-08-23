/**
 * BLICK — zwei Zahlen, die man einem Bild sonst nur ansieht
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   node tools/theme-contrast/blick.mjs <theme> [vergleichs-theme]
 *
 * 1. TONSPREIZUNG. Regie-Regel „auch Nacht ist reich" und REZEPT D2 („Tonstufen
 *    SPREIZEN") sind Aussagen über ein HISTOGRAMM, nicht über einen Maximalwert.
 *    Ein Bild kann einen hellen Punkt haben und trotzdem eine Masse sein, wenn
 *    99 % seiner Fläche im Keller liegen — das war der Befund gegen aoi v1
 *    („Tiefe behauptet, nichts zu sehen"). Darum werden hier Perzentile
 *    gemeldet, getrennt für die LESESPALTE (dort staucht der Schleier) und die
 *    SEITENSTREIFEN (dort darf das Bild leuchten).
 *
 * 2. HERZSCHLAG. Anteil der Bildpunkte, die sich zwischen zwei Frames im
 *    Abstand von drei virtuellen Sekunden messbar ändern. Regie-Lektion 6
 *    verlangt diese Zahl ausdrücklich (natsunohi 32 % = mutig, nagareboshi
 *    2,9 % = dezent). Zwei Schwellen, weil eine allein nichts sagt: ab 2/255
 *    ist eine Änderung überhaupt vorhanden, ab 6/255 sieht man sie.
 *
 * Die Kartenfläche wird ausgespart (y < 440): sie ist deckend und würde beide
 * Zahlen verfälschen — sie misst dann sich selbst, nicht das Bild.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { decodePng, pixel } from './png.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRAMES = join(HERE, 'frames');

/* png.mjs liefert Kanäle als 0..1 (nicht 0..255) — hier steckte beim ersten
   Lauf der Fehler, der 0 % Bewegung meldete, obwohl die Frames verschiedene
   Prüfsummen hatten. */
const srgb = (c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
const lum = ([r, g, b]) => 0.2126 * srgb(r) + 0.7152 * srgb(g) + 0.0722 * srgb(b);
const b255 = (v) => v * 255;

/* Die Geometrie der Bühne bei 1366 px: die App-Spalte ist 920 px breit und
   mittig, der Schleier deckt genau sie und federt 110 px nach außen aus. */
const SPALTE = [223, 1143];
const STREIFEN = [
  [0, 113],
  [1253, 1366],
];
const KARTEN_BIS = 440;

function load(name) {
  return decodePng(readFileSync(join(FRAMES, name)));
}

function stats(img, ranges) {
  const ls = [];
  for (let y = KARTEN_BIS; y < img.height; y += 2) {
    for (const [x0, x1] of ranges) {
      for (let x = x0; x < Math.min(x1, img.width); x += 2) {
        ls.push(lum(pixel(img, x, y)));
      }
    }
  }
  ls.sort((a, b) => a - b);
  const q = (p) => ls[Math.min(ls.length - 1, Math.floor(p * ls.length))];
  return { p05: q(0.05), p50: q(0.5), p90: q(0.9), p99: q(0.99), max: ls[ls.length - 1] };
}

/* Nach Bildhälften getrennt, weil „die Lichtbahnen atmen sichtbar" eine
   Aussage über die OBERE Hälfte ist: unten stehen Tang und Wrack, deren
   Bewegung eine andere Uhr hat. Eine Gesamtzahl mischt beides und beweist
   dann keines von beiden. */
function bewegung(a, b, von = 0, bis = 1) {
  let n2 = 0;
  let n6 = 0;
  let total = 0;
  const y0 = Math.floor(a.height * von);
  const y1 = Math.floor(a.height * bis);
  for (let y = y0; y < y1; y += 2) {
    for (let x = 0; x < a.width; x += 2) {
      const pa = pixel(a, x, y);
      const pb = pixel(b, x, y);
      const d = Math.max(
        Math.abs(b255(pa[0]) - b255(pb[0])),
        Math.abs(b255(pa[1]) - b255(pb[1])),
        Math.abs(b255(pa[2]) - b255(pb[2])),
      );
      if (d >= 2) n2++;
      if (d >= 6) n6++;
      total++;
    }
  }
  return { weich: (100 * n2) / total, sichtbar: (100 * n6) / total };
}

const pct = (v) => `${(v * 1000).toFixed(2)}‰`;

function bericht(theme) {
  const f1 = load(`${theme}-t1s.png`);
  const sp = stats(f1, [SPALTE]);
  const st = stats(f1, STREIFEN);
  console.log(`\n── ${theme} ──────────────────────────────────`);
  console.log(
    `  Spalte     L: p05 ${pct(sp.p05)}  p50 ${pct(sp.p50)}  p90 ${pct(sp.p90)}  p99 ${pct(sp.p99)}  max ${pct(sp.max)}`,
  );
  console.log(
    `  Streifen   L: p05 ${pct(st.p05)}  p50 ${pct(st.p50)}  p90 ${pct(st.p90)}  p99 ${pct(st.p99)}  max ${pct(st.max)}`,
  );
  console.log(
    `  Spreizung  Spalte p90/p05 ${(sp.p90 / sp.p05).toFixed(1)}×   Streifen p90/p05 ${(st.p90 / st.p05).toFixed(1)}×`,
  );

  const paare = [
    [1, 4],
    [4, 7],
    [7, 10],
  ];
  for (const [a, b] of paare) {
    try {
      const fa = load(`${theme}-t${a}s.png`);
      const fb = load(`${theme}-t${b}s.png`);
      const m = bewegung(fa, fb);
      const o = bewegung(fa, fb, 0, 0.45);
      const u = bewegung(fa, fb, 0.55, 1);
      console.log(
        `  Herzschlag t${a}s→t${b}s: ganz ${m.weich.toFixed(1)}/${m.sichtbar.toFixed(1)} %` +
          `  ·  oben (Bahnen) ${o.weich.toFixed(1)}/${o.sichtbar.toFixed(1)} %` +
          `  ·  unten (Tang) ${u.weich.toFixed(1)}/${u.sichtbar.toFixed(1)} %   [≥2 / ≥6 von 255]`,
      );
    } catch {
      console.log(`  Herzschlag t${a}s→t${b}s: Frames fehlen`);
    }
  }
}

const args = process.argv.slice(2);
if (args.length === 0) {
  console.error('nutzung: node tools/theme-contrast/blick.mjs <theme> [vergleich...]');
  process.exit(1);
}
for (const t of args) bericht(t);
console.log('');

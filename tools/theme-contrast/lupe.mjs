/**
 * DIE LUPE — ein Bildausschnitt, vergrößert, als PNG.
 * ═══════════════════════════════════════════════════════════════════════════
 * ANLASS (21.08., yoru-Aufhellung): „Selbstabnahme mit eigenen Augen" scheitert
 * an einem Vollbild-Screenshot, sobald das Motiv KLEIN ist. Ein Stern hat hier
 * Radius 2 px und liegt acht Grauwerte über seinem Himmel; auf einem
 * 1366-px-Bild ist er schlicht nicht zu beurteilen — man sieht ihn nicht und
 * weiß nicht, ob er fehlt oder ob man ihn nur nicht sieht. Das ist der
 * Unterschied zwischen „geprüft" und „angeschaut".
 *
 * Vergrößert wird mit NEAREST NEIGHBOUR, ausdrücklich ohne Glättung: die Frage
 * ist ja gerade, welchen Wert ein einzelnes Pixel hat. Jede Interpolation
 * würde genau die Antwort verschmieren.
 *
 * Optional `--boost <faktor>`: hebt die Helligkeit rein für den BLICK an (mit
 * Gamma auf der linearen Luminanz, damit die Verhältnisse stimmen). Damit sieht
 * man Struktur in Zonen, die im Auslieferungszustand fast schwarz sind, ohne
 * das ausgelieferte Bild zu verändern. Die Datei sagt es im Namen, damit
 * niemand ein aufgehelltes Beweisbild für das echte hält.
 *
 *   node tools/theme-contrast/lupe.mjs <bild.png> <x> <y> <b> <h> <zoom> <out.png> [--boost 3]
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
import { decodePng } from './png.mjs';

function crc32(buf) {
  let c;
  const t = [];
  for (let n = 0; n < 256; n++) {
    c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  let crc = 0xffffffff;
  for (const b of buf) crc = t[(crc ^ b) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

/** RGB-Puffer (w*h*3) → PNG, Farbtyp 2, Filter 0. Reicht für einen Beleg. */
export function encodePng(w, h, rgb) {
  const raw = Buffer.alloc(h * (w * 3 + 1));
  for (let y = 0; y < h; y++) {
    raw[y * (w * 3 + 1)] = 0;
    Buffer.from(rgb.subarray(y * w * 3, (y + 1) * w * 3)).copy(raw, y * (w * 3 + 1) + 1);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const a = process.argv.slice(2);
if (a.length < 7) {
  console.error('nutzung: node lupe.mjs <bild.png> <x> <y> <b> <h> <zoom> <out.png> [--boost f]');
  process.exit(2);
}
const [src, X, Y, BW, BH, Z, out] = [a[0], +a[1], +a[2], +a[3], +a[4], +a[5], a[6]];
const bi = a.indexOf('--boost');
const boost = bi >= 0 ? +a[bi + 1] : 1;

const img = decodePng(readFileSync(src));
const w = BW * Z;
const h = BH * Z;
const rgb = new Uint8Array(w * h * 3);

const heben = (v) => {
  if (boost === 1) return v;
  const e = v / 255;
  const l = e <= 0.04045 ? e / 12.92 : ((e + 0.055) / 1.055) ** 2.4;
  const b = Math.min(1, l * boost);
  const s = b <= 0.0031308 ? 12.92 * b : 1.055 * Math.pow(b, 1 / 2.4) - 0.055;
  return Math.round(s * 255);
};

for (let y = 0; y < h; y++) {
  const sy = Math.min(img.height - 1, Y + Math.floor(y / Z));
  for (let x = 0; x < w; x++) {
    const sx = Math.min(img.width - 1, X + Math.floor(x / Z));
    const i = (sy * img.width + sx) * img.channels;
    const o = (y * w + x) * 3;
    rgb[o] = heben(img.data[i]);
    rgb[o + 1] = heben(img.data[i + 1]);
    rgb[o + 2] = heben(img.data[i + 2]);
  }
}
writeFileSync(out, encodePng(w, h, rgb));
console.log(`${out}  ${w}×${h}  aus ${src} (${X},${Y} ${BW}×${BH}, ×${Z}${boost !== 1 ? `, Boost ${boost}` : ''})`);

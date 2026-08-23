/**
 * **RAHMEN — bleibt die Kachel eine Kachel, wenn ihre Linie durchscheint?**
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   node tools/theme-contrast/rahmen.mjs            (alle Szenen, Ist-Zustand)
 *   RAHMEN_MIX=100% node …                          (Vergleichslauf: deckend)
 *   RAHMEN_OUT=<dir> RAHMEN_TAG=vorher node …       (Bilder zum Ansehen ablegen)
 *
 * WARUM ES NEBEN `flaechen.mjs` STEHT — und es nicht ersetzt. Jenes Harness
 * spart die Kante ausdrücklich aus (3 px Einzug, s. seinen Kopf): auf der
 * Haarlinie steht nie eine Glyphe, sie als schlechtesten Bildpunkt des
 * TEXTFELDES zu melden hieße, den Rahmen für den Inhalt zu halten. Genau
 * deshalb sagt `flaechen.mjs` aber auch nichts über die Frage, die Andis
 * Bestellung vom 23.08. aufwirft („mach bitte die Rahmen um die Widgets noch
 * transparenter"): eine Linie, die niemand mehr sieht, ist kein Rahmen mehr.
 *
 * WAS GEMESSEN WIRD. Eine echte `.tile` liegt an bekannten Koordinaten über
 * der echten Szene. Drei Bildpunkte auf derselben Spalte, mittig auf der
 * GERADEN Oberkante (die Ecken sind gerundet und antialiast — dort misst man
 * die Rundung, nicht die Linie):
 *
 *   aussen  (y = kante − 3)  die Szene direkt über der Kachel
 *   linie   (y = kante)      die Haarlinie selbst, komponiert über allem
 *   innen   (y = kante + 3)  der Kachelgrund (86 % Fläche + 14 % Szene)
 *
 * Gemeldet wird der WCAG-2.1-Kontrast linie↔innen und linie↔aussen sowie der
 * GRÖSSERE der beiden: die Kachel ist als Kachel lesbar, solange die Linie sich
 * von mindestens EINER ihrer beiden Seiten abhebt. Ein Rahmen ist Dekoration,
 * kein Text — der 4,5:1-Boden gilt hier NICHT. Der Riegel ist ein anderer und
 * er ist konservativ gewählt: **1,10:1**, empirisch die Schwelle, unter der die
 * Linie auf den Frames dieses Repos nicht mehr als Kante gelesen wird.
 *
 * Der CSS-Stapel ist derselbe wie in `flaechen.mjs`/`frames.sh` und in exakt
 * derselben Reihenfolge wie die App: index.css → styles/themes.css →
 * public/themes/<id>.css aus SEINEM echten Verzeichnis (damit `url('…-szene.svg')`
 * relativ auflöst). `RAHMEN_MIX` überschreibt `--hairline-mix` nur IM BILD —
 * so entsteht das Vorher/Nachher, ohne die Quelle anzufassen (`git stash` ist
 * im Pod-Betrieb verboten).
 */

import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePng, pixel } from './png.mjs';
import { contrast } from './color.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..', '..');
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const THEMES = join(REPO, 'frontend', 'public', 'themes');

/** Geometrie der Probe-Kachel — absolut gesetzt, damit die Kante bekannt ist. */
const W = 1366;
const H = 900;
const TILE = { x: 240, y: 300, w: 620, h: 260 };
/** Der Riegel für eine dekorative Linie (s. Kopf) — kein Text-AA-Boden. */
const FLOOR = 1.1;

const MIX = process.env.RAHMEN_MIX ?? null;
const want = process.argv.slice(2);
const ids = (JSON.parse(readFileSync(join(THEMES, 'manifest.json'), 'utf8')).themes ?? [])
  .map((t) => t.id)
  .filter((id) => existsSync(join(THEMES, `${id}.css`)))
  .filter((id) => want.length === 0 || want.includes(id));

const page = (theme) => `<!doctype html><html lang="de" data-theme="${theme}"><head><meta charset="utf-8">
<link rel="stylesheet" href="file://${REPO}/frontend/src/index.css">
<link rel="stylesheet" href="file://${REPO}/frontend/src/styles/themes.css">
<link rel="stylesheet" href="file://${THEMES}/${theme}.css">
<style>
  html,body{min-height:100vh;margin:0}
  ${MIX ? `:root{--hairline-mix:${MIX}}` : ''}
  /* Die Eintritts-Animation der Kachel verschiebt sie um 6 px — bei einem
     Screenshot mit virtual-time-budget ist sie längst durch, aber sie wird hier
     trotzdem stillgelegt: die Kante MUSS auf dem gemessenen Pixel liegen. */
  .tile{animation:none;position:absolute;left:${TILE.x}px;top:${TILE.y}px;width:${TILE.w}px;height:${TILE.h}px}
</style></head>
<body><div class="app" style="min-height:100vh">
<div class="tile"><div class="tile__head"><span class="tile__name">Wetter</span></div>
<div class="tile__value">21° / 14°, abends trocken.</div></div>
</div></body></html>`;

/** Ein Chrome, ein Bild — und danach der EIGENE Kindprozess tot (Pod-Regel). */
async function shoot(html, png, work) {
  const file = join(work, 'page.html');
  writeFileSync(file, html);
  if (existsSync(png)) rmSync(png);
  const child = spawn(CHROME, [
    '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
    '--no-first-run', '--no-default-browser-check', '--no-sandbox',
    `--window-size=${W},${H}`, '--virtual-time-budget=2500',
    `--screenshot=${png}`, `--user-data-dir=${join(work, 'profile')}`,
    `file://${file}`,
  ], { stdio: 'ignore' });
  for (let i = 0; i < 200 && !existsSync(png); i++) await new Promise((r) => setTimeout(r, 100));
  await new Promise((r) => setTimeout(r, 500));
  child.kill('SIGKILL');
  if (!existsSync(png)) throw new Error('kein Screenshot');
}

const work = mkdtempSync(join(tmpdir(), 'hoshi-rahmen-'));
const rows = [];
try {
  for (const id of ids) {
    const png = join(work, `${id}.png`);
    await shoot(page(id), png, work);
    const img = decodePng(readFileSync(png));
    const x = TILE.x + Math.round(TILE.w / 2); // mittig auf der geraden Kante
    const aussen = pixel(img, x, TILE.y - 3);
    const linie = pixel(img, x, TILE.y);
    const innen = pixel(img, x, TILE.y + 3);
    const cIn = contrast(linie, innen);
    const cOut = contrast(linie, aussen);
    rows.push({ id, cIn, cOut, best: Math.max(cIn, cOut) });
    if (process.env.RAHMEN_OUT)
      writeFileSync(join(process.env.RAHMEN_OUT, `rahmen-${process.env.RAHMEN_TAG ?? 'ist'}-${id}.png`), readFileSync(png));
  }
} finally {
  if (!process.env.RAHMEN_KEEP) rmSync(work, { recursive: true, force: true });
}

const n = (v) => v.toFixed(2).padStart(5);
console.log(`RAHMEN · --hairline-mix ${MIX ?? '(Quelle)'} · Riegel ${FLOOR.toFixed(2)}:1`);
for (const r of rows) {
  console.log(`  ${r.id.padEnd(13)} innen ${n(r.cIn)}  aussen ${n(r.cOut)}  best ${n(r.best)} ${r.best < FLOOR ? '✗' : '·'}`);
}
const worst = rows.reduce((a, b) => (a.best <= b.best ? a : b));
console.log(`  schlechteste Szene: ${worst.id} ${worst.best.toFixed(2)}:1`);
if (worst.best < FLOOR) {
  console.error(`RAHMEN: ${worst.id} unter dem Riegel — die Kachel ist keine Kachel mehr.`);
  process.exit(1);
}

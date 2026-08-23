/**
 * **FLÄCHEN-AA — der Kontrast-Wächter für durchscheinende Karten, über ALLE Szenen.**
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * WARUM ES NEBEN `measure.mjs` STEHT. Jenes Harness misst die LEERE Lesespalte:
 * `<div class="app"></div>`, keine Karte darin. Solange Kacheln deckend waren,
 * war das die vollständige Wahrheit — der schlechteste Bildpunkt der Spalte war
 * der schlechteste Bildpunkt überhaupt. Mit dem Transparenz-Auftrag vom 22.08.
 * („mach bitte auch die Hintergründe der Widgets etwas transparenter") stimmt
 * das nicht mehr: unter einer Karte mit Alpha < 1 mischt sich die SZENE in den
 * Kartengrund, und der Text steht auf dem Komposit, nicht auf dem Token.
 *
 * Ein Alpha-Komposit ist NICHT automatisch sicher, wenn beide Enden sicher
 * sind: liegen Kartengrund und Szenenpunkt auf VERSCHIEDENEN Seiten der
 * Textluminanz, wandert die Mischung durch das Minimum hindurch. Genau dieser
 * Fall ist bei einem hellen Thema mit dunklen Ankern (Fuyubares Zedern) real.
 * Also wird gemessen und nicht geschlossen.
 *
 * WAS GEMESSEN WIRD. Zwei Durchgänge je Thema und Fenster:
 *   `bar`   — die leere Spalte (reproduziert die Zusage von `measure.mjs`),
 *   `karte` — dieselbe Spalte, vollständig von EINER `.tile` bedeckt, in der
 *             echten Geometrie des Zuhause-Reiters (880 px breit, mittig).
 * Eine Karte über die volle Spaltenhöhe ist bewusst KEINE echte Kachel-Anordnung:
 * sie ist der Worst Case. Wo immer eine Kachel liegen kann, hat diese hier schon
 * gelegen — inklusive der hellsten und der dunkelsten Stelle der Szene.
 *
 * Gemeldet wird für jede Textstufe (--text-1 … --text-4) der schlechteste
 * WCAG-2.1-Kontrast über alle Bildpunkte des Feldes. Boden: 4,5:1.
 *
 * DIE AUSNAHME IST GEERBT, NICHT ERFUNDEN (`yoru.scenarios.mjs`, Kopf): eine
 * Textstufe, die 4,5:1 gegen KEINE Farbe der Welt erreicht — Yorus `--text-4`
 * kann höchstens 3,07:1 — bekommt keinen Riegel, sondern ein `~`. Ein Riegel,
 * den zu erfüllen mathematisch unmöglich ist, ist kein Riegel, sondern ein
 * dauerhaft rotes Licht, an dem sich niemand mehr stört. Solche Stufen werden
 * gemessen und gedruckt, damit ein Vorher/Nachher sie vergleichen kann.
 *
 * DIE KANTE WIRD AUSGESPART (3 px Einzug). Die Kachel trägt eine 1-px-Linie in
 * `--bg-hairline`; die liegt bauartbedingt zwischen Grund und Text und wäre in
 * fast jedem Thema der schlechteste Bildpunkt des Feldes. Auf ihr steht aber
 * nie eine Glyphe — sie zu messen hieße, den Rahmen für den Inhalt zu halten.
 *
 * DIE KARTE TRÄGT KEINEN EIGENEN TEXT im Bild — sie wird als reine Fläche
 * fotografiert und der Kontrast gegen die Textfarben GERECHNET. Läge Text im
 * Bild, wäre der schlechteste Bildpunkt der Text selbst (Kontrast 1:1) und die
 * Messung sagte nichts.
 *
 * Der CSS-Stapel ist derselbe wie in `measure.mjs` und in exakt derselben
 * Reihenfolge wie die App: index.css → styles/themes.css → public/themes/<id>.css
 * aus SEINEM echten Verzeichnis (damit `url('…-szene.svg')` relativ auflöst).
 *
 * NUTZUNG
 *   node tools/theme-contrast/flaechen.mjs               (alle Themen des Manifests)
 *   node tools/theme-contrast/flaechen.mjs yoru fuyubare (nur diese)
 *   FLAECHEN_KEEP=1 node …                               (Screenshots behalten)
 */

import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, statSync, mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePng, pixel } from './png.mjs';
import { hex, luminance } from './color.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
/**
 * Der Baum, dessen CSS gemessen wird — normalerweise dieses Repo. `FLAECHEN_REPO`
 * zeigt auf einen ANDEREN Stand (z. B. `git archive <sha> frontend` in ein
 * Temp-Verzeichnis) und macht damit ein Vorher/Nachher möglich, OHNE den
 * Arbeitsbaum anzufassen. Genau dafür gebaut: der Transparenz-Auftrag verlangt
 * beide Zahlen, und `git stash` ist im Pod-Betrieb verboten.
 */
const REPO = process.env.FLAECHEN_REPO ? resolve(process.env.FLAECHEN_REPO) : resolve(HERE, '..', '..');
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

/** Höhe des Token-Messstreifens; darunter beginnt das gemessene Feld. */
const PROBE_H = 24;
const PROBE_SW = 20;
/** Halbe Breite der App-Spalte (`.app { max-width: 920px }`). */
const HALF_COLUMN = 460;
/** Halbe Breite der Karte: 920 − 2×20 Polster von `.app__main` (gemessen: 880). */
const HALF_CARD = 440;

const FLOOR = 4.5;
/**
 * Einzug, der KANTE UND ECKEN aus dem Feld nimmt: 12 px Eckradius (`--radius`)
 * + 1 px Linie + 1 px Kantenglättung. Ohne die 12 lag der schlechteste Punkt
 * jedes hellen Themas in der abgerundeten Ecke — dort scheint die Szene an der
 * Karte VORBEI, und man misst nicht mehr die Karte, sondern ihren Ausschnitt.
 */
const KANTE = 14;
const TEXTS = ['--text-1', '--text-2', '--text-3', '--text-4'];
const PROBE_TOKENS = [...TEXTS, '--accent', '--bg-base', '--bg-surface'];

/**
 * Der bestmögliche Kontrast, den diese Textfarbe IN DIESEM THEMA erreichen
 * kann. Die Richtung entscheidet, und sie kommt aus `--bg-base`: in einem
 * dunklen Thema liegt jeder Grund UNTER der Schrift, das Maximum ist also
 * Schwarz; in einem hellen Thema darüber, dann ist es Weiß. Ohne diese
 * Richtung wäre Yorus `--text-4` „riegelfähig" (6,86:1 gegen Weiß) — ein Weiß,
 * das in einem Nachtthema nie vorkommt. Genau diese Unterscheidung trifft der
 * Kopf von `yoru.scenarios.mjs`, hier nur ausgerechnet statt ausgeschrieben.
 */
function ceiling(textRgb, baseRgb) {
  const l = luminance(textRgb);
  return luminance(baseRgb) < l ? (l + 0.05) / 0.05 : 1.05 / (l + 0.05);
}

/** Zwei Regime: Schleier ausgefedert (breit) und Schleier deckt alles (schmal). */
const VIEWPORTS = [
  [1366, 1024],
  [834, 1112],
];

const MIME = {
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.html': 'text/html',
  '.png': 'image/png',
  '.json': 'application/json',
};

/* ── Die zwei Seiten ──────────────────────────────────────────────────────── */

function pageHtml(theme, blatt, mitKarte) {
  const swatches = PROBE_TOKENS.map((t) => `<i style="background:var(${t})"></i>`).join('');
  // Die Karte: exakt die Klasse der echten Kachel (`.tile`), damit sie den
  // echten Hintergrund und die echte Kante trägt — nur in der Geometrie der
  // vollen Spalte statt einer Rasterzelle.
  const karte = mitKarte
    ? `<div class="tile" id="karte" style="position:fixed;left:calc(50% - ${HALF_CARD}px);width:${HALF_CARD * 2}px;top:${PROBE_H + 16}px;bottom:16px;"></div>`
    : '';
  return `<!doctype html>
<html lang="de" data-theme="${theme}">
<head>
<meta charset="utf-8">
<title>flaechen-aa</title>
<link rel="stylesheet" href="/src/index.css">
<link rel="stylesheet" href="/src/styles/themes.css">
<link rel="stylesheet" href="/themes/${blatt}">
<style>
  #probe { position: fixed; top: 0; left: 0; z-index: 9999; display: flex; margin: 0; padding: 0; }
  #probe i { display: block; width: ${PROBE_SW}px; height: ${PROBE_H}px; }
  html, body { height: 100%; margin: 0; }
  /* Der Eintritts-Stagger der Kachel würde sie im Moment des Knipsens versetzt
     zeigen; hier zählt die FLÄCHE, nicht ihr Auftritt. */
  #karte { animation: none !important; }
</style>
</head>
<body>
<div class="app"></div>
${karte}
<div id="probe">${swatches}</div>
</body>
</html>`;
}

function startServer() {
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (url.pathname === '/page.html') {
      const theme = url.searchParams.get('t');
      const mit = url.searchParams.get('k') === '1';
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      res.end(pageHtml(theme, url.searchParams.get('f'), mit));
      return;
    }
    const map = [
      ['/themes/', join(REPO, 'frontend', 'public', 'themes')],
      ['/src/', join(REPO, 'frontend', 'src')],
    ];
    for (const [prefix, root] of map) {
      if (url.pathname.startsWith(prefix)) {
        const file = join(root, url.pathname.slice(prefix.length));
        if (!file.startsWith(root) || !existsSync(file)) break;
        res.writeHead(200, { 'content-type': MIME[extname(file)] || 'application/octet-stream' });
        res.end(readFileSync(file));
        return;
      }
    }
    res.writeHead(404).end('nope');
  });
  return new Promise((ok) => server.listen(0, '127.0.0.1', () => ok(server)));
}

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

/** Ein Screenshot — Start/Warten/eigenen Kindprozess killen wie in measure.mjs. */
async function shoot(url, width, height, out, profile) {
  const child = spawn(
    CHROME,
    [
      '--headless',
      '--no-sandbox',
      '--disable-gpu',
      '--hide-scrollbars',
      '--force-device-scale-factor=1',
      '--no-first-run',
      '--no-default-browser-check',
      `--user-data-dir=${profile}`,
      `--window-size=${width},${height}`,
      '--virtual-time-budget=3000',
      `--screenshot=${out}`,
      url,
    ],
    { stdio: 'ignore' },
  );
  let lastSize = -1;
  for (let i = 0; i < 600; i++) {
    await sleep(100);
    if (existsSync(out)) {
      const size = statSync(out).size;
      if (size > 0 && size === lastSize) break;
      lastSize = size;
    }
    if (child.exitCode !== null && existsSync(out)) break;
  }
  child.kill('SIGKILL');
  if (!existsSync(out)) throw new Error(`Chrome lieferte kein Bild für ${url} @${width}`);
}

/** Schlechtester Kontrast einer Textfarbe gegen ein Pixelfeld. */
function worstAgainst(img, box, textRgb) {
  const lt = luminance(textRgb);
  let worst = Infinity;
  let at = null;
  for (let y = box.y0; y < box.y1; y++) {
    for (let x = box.x0; x < box.x1; x++) {
      const p = pixel(img, x, y);
      const lp = luminance(p);
      const ratio = (Math.max(lt, lp) + 0.05) / (Math.min(lt, lp) + 0.05);
      if (ratio < worst) {
        worst = ratio;
        at = { x, y, rgb: p };
      }
    }
  }
  return { ratio: worst, at };
}

const fmt = (n) => n.toFixed(2).replace('.', ',');

/* ── Lauf ─────────────────────────────────────────────────────────────────── */

const manifest = JSON.parse(
  readFileSync(join(REPO, 'frontend', 'public', 'themes', 'manifest.json'), 'utf8'),
);
const argThemes = process.argv.slice(2).filter((a) => !a.startsWith('-'));
/**
 * Id → Blattdatei, GENAU wie das Manifest sie nennt. Nicht `${id}.css` raten:
 * die Klassiker liegen unter `old/` (Kasumi ist `old/kasumi.css`), und der
 * erste Lauf am 22.08. hat Kasumi darum stillschweigend übersprungen — obwohl
 * die Automatik (Sora) abends genau dorthin auflöst.
 */
const FILE_OF = new Map(manifest.themes.filter((t) => t.file).map((t) => [t.id, t.file]));
const themes = argThemes.length ? argThemes : manifest.themes.map((t) => t.id);
const keep = !!process.env.FLAECHEN_KEEP;

const server = await startServer();
const port = server.address().port;
const work = mkdtempSync(join(tmpdir(), 'hoshi-flaechen-'));
const profile = join(work, 'chrome-profile');

let fails = 0;
const rows = [];
try {
  for (const theme of themes) {
    // Sora ist die Automatik: sie lädt kein eigenes Blatt, sondern setzt am
    // <html> das aufgelöste Thema. Ohne Datei gäbe es hier ein 404 und einen
    // Messwert über den Basis-Token — das wäre eine Zahl über nichts. Jedes
    // ANDERE Thema muss aber gemessen werden, auch wenn sein Blatt woanders
    // liegt als sein Name vermuten lässt (s. FILE_OF).
    const blatt = FILE_OF.get(theme) ?? `${theme}.css`;
    if (!existsSync(join(REPO, 'frontend', 'public', 'themes', blatt))) {
      console.log(`\n━━ ${theme} ━━ kein eigenes Blatt (Automatik) — übersprungen`);
      continue;
    }
    console.log(`\n━━ ${theme} ━━`);
    for (const [width, height] of VIEWPORTS) {
      for (const [mode, k] of [
        ['bar  ', '0'],
        ['karte', '1'],
      ]) {
        const png = join(work, `${theme}-${mode.trim()}-${width}x${height}.png`);
        const url = `http://127.0.0.1:${port}/page.html?t=${theme}&f=${encodeURIComponent(blatt)}&k=${k}`;
        await shoot(url, width, height, png, profile);
        const img = decodePng(readFileSync(png));

        const tokens = {};
        PROBE_TOKENS.forEach((name, i) => {
          tokens[name] = pixel(img, i * PROBE_SW + PROBE_SW / 2, PROBE_H / 2);
        });

        const cx = Math.round(img.width / 2);
        const half = k === '1' ? HALF_CARD - KANTE : HALF_COLUMN;
        const box = {
          x0: Math.max(0, cx - half),
          x1: Math.min(img.width, cx + half),
          y0: PROBE_H + 16 + (k === '1' ? KANTE : 0),
          y1: k === '1' ? img.height - 16 - KANTE : img.height,
        };

        const parts = [];
        let worstOfRow = Infinity;
        const werte = {};
        for (const t of TEXTS) {
          const { ratio, at } = worstAgainst(img, box, tokens[t]);
          const riegelfaehig = ceiling(tokens[t], tokens['--bg-base']) >= FLOOR;
          const ok = ratio >= FLOOR;
          if (riegelfaehig && !ok) fails++;
          if (riegelfaehig) worstOfRow = Math.min(worstOfRow, ratio);
          werte[t] = ratio;
          parts.push(`t${t.slice(-1)} ${fmt(ratio)}${riegelfaehig ? (ok ? '' : ' ✗') : '~'}`);
          if (riegelfaehig && !ok) parts.push(`@${hex(at.rgb)}`);
        }
        rows.push({ theme, width, height, mode: mode.trim(), worst: worstOfRow, werte });
        console.log(`  ${mode} ${String(width).padStart(4)}×${height}  ${parts.join(' · ')}`);
        if (!keep) rmSync(png, { force: true });
      }
    }
  }
} finally {
  server.close();
  if (!keep) rmSync(work, { recursive: true, force: true });
  else console.log(`\nScreenshots: ${work}`);
}

const worstKarte = rows.filter((r) => r.mode === 'karte').sort((a, b) => a.worst - b.worst)[0];
const worstBar = rows.filter((r) => r.mode === 'bar').sort((a, b) => a.worst - b.worst)[0];
console.log(
  `\nSchwächster Punkt · Karte: ${worstKarte ? `${fmt(worstKarte.worst)}:1 (${worstKarte.theme} ${worstKarte.width}×${worstKarte.height})` : '—'}` +
    `\nSchwächster Punkt · bar  : ${worstBar ? `${fmt(worstBar.worst)}:1 (${worstBar.theme} ${worstBar.width}×${worstBar.height})` : '—'}`,
);
console.log(
  fails === 0
    ? `✓ alle riegelfähigen Stufen aus ${rows.length} Feldern ≥ ${FLOOR}:1`
    : `✗ ${fails} Messung(en) unter ${FLOOR}:1`,
);
// Rohwerte für einen mechanischen Vorher/Nachher-Vergleich (kein Abtippen).
if (process.env.FLAECHEN_JSON) writeFileSync(process.env.FLAECHEN_JSON, JSON.stringify(rows, null, 1));
process.exit(fails === 0 ? 0 : 1);

/**
 * Kontrast-Harness: misst das ECHTE Render eines Themas, nicht ein Modell davon.
 * ═══════════════════════════════════════════════════════════════════════════
 * DIE LEHRE, DIE ES GEBAUT HAT (18.08.): ein Messfühler, der die Themen-CSS
 * ohne die Basis-Token aus `index.css` lädt, misst einen Hintergrund, den es so
 * nie gibt — er hat gelogen und der Fehler fiel erst am echten Bild auf. Darum
 * lädt dieses Harness IMMER den vollständigen Stapel in genau der Reihenfolge
 * der App:  index.css (Basis/Yoru) → styles/themes.css (Panel + :not-Basis) →
 * public/themes/<id>.css (das Thema selbst, aus SEINEM echten Verzeichnis,
 * damit `url('…-szene.svg')` relativ auflöst wie im Browser).
 *
 * WAS GEMESSEN WIRD. Nicht „Token gegen Token" (das rechnet color.mjs), sondern
 * der schlechteste PIXEL: für jede Textstufe wird über alle Pixel der 920-px-
 * App-Spalte das Minimum des WCAG-Kontrasts gesucht. Das ist die einzige Zahl,
 * die eine Zusage trägt — sie schließt Atmosphäre, Szene, Schleier und jede
 * animierte Schicht mit ein, egal wie sie zustande kommen.
 *
 * ZEIT IST EIN MESSPUNKT. Ein Thema mit seltenen Ereignissen (Nagareboshi:
 * Meteore) hat seinen schlechtesten Moment nicht bei t=0. Szenarien frieren
 * darum einzelne Schichten per `animation-play-state: paused` + negativem
 * `animation-delay` auf einem exakten Keyframe ein: gemessen wird echtes CSS zu
 * einer gewählten Sekunde, keine Nachbildung.
 *
 * ZWEI SCHRAUBEN JE SZENARIO, seit Nagareboshis zweiter Fassung (19.08.):
 *   `vt`     — das virtuelle Zeitbudget in ms (Vorgabe 3000). Nötig, weil eine
 *              Szene ihre Bewegung auch INNERHALB des SVG tragen kann; solche
 *              Uhren erreicht kein `animation-delay` von außen, wohl aber die
 *              Wahl der Sekunde, zu der Chrome knipst.
 *   `flags`  — zusätzliche Chrome-Schalter. Der wichtige ist
 *              `--force-prefers-reduced-motion`: er hält AUCH die Uhren im SVG
 *              an. Ist dessen Ruhezustand der hellste (Nagareboshi baut ihn so),
 *              misst dieses Szenario den echten Worst Case des Sternfelds
 *              statt einer zufällig erwischten Phase.
 *
 * Eigenes `--user-data-dir` unter os.tmpdir() (Pod-Regel: keine fremden
 * Chrome-Profile, keine fremden Prozesse anfassen).
 *
 * NUTZUNG
 *   node tools/theme-contrast/measure.mjs tools/theme-contrast/nagareboshi.scenarios.mjs
 *   node tools/theme-contrast/measure.mjs <config> --keep   (Screenshots behalten)
 */

import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { readFileSync, statSync, mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname, extname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { decodePng, pixel } from './png.mjs';
import { contrast, hex, luminance } from './color.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..', '..');
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

/** Höhe des Token-Messstreifens; darunter beginnt das gemessene Feld. */
const PROBE_H = 24;
const PROBE_SW = 20;
/** Halbe Breite der App-Spalte (`.app { max-width: 920px; margin: auto }`). */
const HALF_COLUMN = 460;

const MIME = {
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.html': 'text/html',
  '.png': 'image/png',
  '.json': 'application/json',
};

/* ── Die Seite, die gemessen wird ────────────────────────────────────────── */

function harnessHtml(cfg, scenario) {
  const swatches = cfg.probeTokens
    .map((t) => `<i style="background:var(${t})"></i>`)
    .join('');
  return `<!doctype html>
<html lang="de" data-theme="${cfg.theme}">
<head>
<meta charset="utf-8">
<title>theme-contrast</title>
<link rel="stylesheet" href="/src/index.css">
<link rel="stylesheet" href="/src/styles/themes.css">
<link rel="stylesheet" href="/themes/${cfg.theme}.css">
<style>
  /* Der Messstreifen: jede Kachel ist EIN Token, deckend gemalt. So kommt der
     Wert, den Chrome wirklich aus oklch() macht, im selben Screenshot mit —
     keine zweite Farbmathematik, die von der des Browsers abweichen könnte. */
  #probe { position: fixed; top: 0; left: 0; z-index: 9999; display: flex; margin: 0; padding: 0; }
  #probe i { display: block; width: ${PROBE_SW}px; height: ${PROBE_H}px; }
  html, body { height: 100%; margin: 0; }
${scenario.css || ''}
</style>
</head>
<body>
<div class="app"></div>
<div id="probe">${swatches}</div>
</body>
</html>`;
}

/* ── Statischer Server über dem Worktree ─────────────────────────────────── */

function startServer(cfg) {
  const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const scenario = cfg.scenarios.find((s) => s.name === url.searchParams.get('s')) || { css: '' };

    if (url.pathname === '/harness.html') {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      res.end(harnessHtml(cfg, scenario));
      return;
    }

    // /themes/* liegt im echten Auslieferungs-Verzeichnis, damit relative
    // url()-Verweise der Themen-CSS (die Szenen-SVG!) genau wie in Produktion
    // auflösen. /src/* ist die Basis-CSS.
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

/* ── Chrome ──────────────────────────────────────────────────────────────── */

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

/**
 * Ein Screenshot.
 *
 * WARUM NICHT `spawnSync`: Chrome schreibt die PNG nach ~1 s, beendet sich auf
 * diesem Mac aber erst ~25 s später (es hängt nach getaner Arbeit noch an
 * eigenen Aufräum-/Telemetriepfaden). Synchron gewartet kostet ein Messlauf
 * über drei Breiten und fünf Szenarien eine Viertelstunde statt einer Minute.
 * Deshalb: starten, auf die FERTIGE Datei warten (Größe muss zweimal in Folge
 * gleich sein — sonst liest man ein halb geschriebenes PNG) und dann den EIGENEN
 * Kindprozess beenden. Pod-Regel: nur der eigene Prozess, nie ein fremder
 * Chrome; die Prozess-Id kommt aus genau diesem `spawn`.
 */
async function shoot(url, width, height, out, profile, opts = {}) {
  const child = spawn(
    CHROME,
    [
      '--headless',
      '--no-sandbox', // sonst startet Chrome in dieser Umgebung nicht durch
      '--disable-gpu',
      '--hide-scrollbars',
      '--force-device-scale-factor=1',
      '--no-first-run',
      '--no-default-browser-check',
      `--user-data-dir=${profile}`,
      `--window-size=${width},${height}`,
      `--virtual-time-budget=${opts.vt ?? 3000}`,
      ...(opts.flags ?? []),
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

/* ── Auswertung ──────────────────────────────────────────────────────────── */

/**
 * Schlechtester Kontrast einer Textfarbe gegen ein Pixelfeld.
 * „Schlechtester" heißt: das Pixel, dessen Luminanz der Schrift am NÄCHSTEN
 * liegt — für helle Schrift auf dunklem Grund ist das automatisch die hellste
 * Stelle, für dunkle Schrift die dunkelste. Ein Polaritäts-Sonderfall ist damit
 * gar nicht erst nötig.
 */
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

function fmt(n) {
  return n.toFixed(2).replace('.', ',');
}

/* ── Lauf ────────────────────────────────────────────────────────────────── */

const configPath = process.argv[2];
if (!configPath) {
  console.error('nutzung: node measure.mjs <scenarios.mjs> [--keep]');
  process.exit(2);
}
const keep = process.argv.includes('--keep');
const cfg = (await import(pathToFileURL(resolve(configPath)).href)).default;

const server = await startServer(cfg);
const port = server.address().port;
const work = mkdtempSync(join(tmpdir(), 'hoshi-theme-contrast-'));
const profile = join(work, 'chrome-profile');

let fails = 0;
try {
  for (const scenario of cfg.scenarios) {
    console.log(`\n━━ ${scenario.name} ━━ ${scenario.note || ''}`);
    for (const [width, height] of cfg.viewports) {
      const png = join(work, `${scenario.name}-${width}x${height}.png`);
      await shoot(
        `http://127.0.0.1:${port}/harness.html?s=${encodeURIComponent(scenario.name)}`,
        width,
        height,
        png,
        profile,
        { vt: scenario.vt, flags: scenario.flags },
      );
      const img = decodePng(readFileSync(png));

      // Token-Istwerte aus dem Messstreifen lesen.
      const tokens = {};
      cfg.probeTokens.forEach((name, i) => {
        tokens[name] = pixel(img, i * PROBE_SW + PROBE_SW / 2, PROBE_H / 2);
      });

      const cx = Math.round(img.width / 2);
      const column = {
        x0: Math.max(0, cx - HALF_COLUMN),
        x1: Math.min(img.width, cx + HALF_COLUMN),
        y0: PROBE_H + 16,
        y1: img.height,
      };

      const parts = [];
      for (const t of cfg.textTokens) {
        const { ratio, at } = worstAgainst(img, column, tokens[t]);
        const ok = ratio >= (cfg.floor ?? 4.5);
        if (!ok) fails++;
        parts.push(`${t.replace('--', '')} ${fmt(ratio)}:1${ok ? '' : ' ✗'} @${hex(at.rgb)}`);
      }

      // Randraum nur zur Information: dort steht das Bild in voller Stärke und
      // es sitzt nie eine Glyphe (die App-Spalte ist 920 px und zentriert).
      let margin = '—';
      if (column.x0 > 8) {
        const m = worstAgainst(img, { x0: 0, x1: column.x0, y0: column.y0, y1: column.y1 }, tokens[cfg.textTokens.at(-1)]);
        margin = `${fmt(m.ratio)}:1`;
      }
      console.log(`  ${String(width).padStart(4)}×${height}  ${parts.join(' · ')}   [Rand ${margin}]`);
      if (!keep) rmSync(png, { force: true });
    }
  }
} finally {
  server.close();
  if (!keep) rmSync(work, { recursive: true, force: true });
  else console.log(`\nScreenshots: ${work}`);
}

console.log(
  fails === 0
    ? `\n✓ alle Werte ≥ ${cfg.floor ?? 4.5}:1 in der 920-px-Spalte`
    : `\n✗ ${fails} Messung(en) unter ${cfg.floor ?? 4.5}:1`,
);
process.exit(fails === 0 ? 0 : 1);

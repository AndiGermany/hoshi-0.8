#!/usr/bin/env node
// ─────────────────────────────────────────────────────────────────────────────
//  SZENE-PERF — was eine Szene KOSTET, in beiden Engines, mit echten Zahlen
//
//  Anlass: Andi, 23.08.2026 — „ich finde das kirschblüten im fluss design
//  unfassbar schön, aber es laggt leider, besonders, wenn ich das design
//  ausgewählt habe und die widgets anpasse". Sein Browser ist FIREFOX; sein
//  GPU-Helper stand bei 551 %. Eine Zahl aus Chrome allein hätte den Befund
//  nicht einmal berührt.
//
//  WAS GEMESSEN WIRD — zwei Zahlen, die zusammen erst eine Aussage ergeben:
//
//   1. FRAME-ZEITEN (im Browser, `requestAnimationFrame`-Abstände). Sie sagen,
//      ob die Seite flüssig ist. Sie sagen NICHT, was sie kostet: ein
//      Compositor, der mit 500 % CPU gerade noch 60 fps schafft, sieht hier
//      makellos aus.
//   2. CPU-SEKUNDEN aller Prozesse der Instanz (`ps -o time`, Auflösung
//      1/100 s, Differenz vor/nach dem Fenster). Das ist die Zahl, die Andi
//      im Aktivitätsmonitor gesehen hat, und sie ist es, die auf einem Gerät,
//      das 24/7 läuft, wirklich zählt.
//
//  ZWEI MODI, weil Andis Fall der zweite ist:
//   • `idle` — die Szene läuft, sonst passiert nichts.
//   • `edit` — der Edit-Modus der Bühne, nachgebaut: sechs wackelnde Kacheln
//     (`idle-wiggle`, versetzt) und EIN Zug, der wie in `HomeStage.runFrame`
//     je Bild ein `getBoundingClientRect()` liest und ein `transform` schreibt.
//
//  JEDER LAUF BEKOMMT SEINE EIGENE INSTANZ mit eigenem, frischem Profil und
//  wird danach vom eigenen Kindprozess-Baum aus wieder eingesammelt. Andis
//  laufender Browser wird nie angefasst (Pod-Regel: nie ein fremder Prozess).
//
//    node tools/theme-contrast/szene-perf.mjs \
//      --themes hanaikada,aoi,nagareboshi,asa --modes idle,edit \
//      --engines firefox,chrome --seconds 12 [--out datei.json] [--headless]
//
//  Ausgabe: Tabelle auf stdout, optional JSON.
// ─────────────────────────────────────────────────────────────────────────────
import { createServer } from 'node:http';
import { spawn, execFileSync } from 'node:child_process';
import { readFileSync, mkdtempSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = dirname(dirname(HERE));
const INDEX_CSS = join(REPO, 'frontend/src/index.css');

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FIREFOX = '/Applications/Firefox.app/Contents/MacOS/firefox';

/* ── Argumente ──────────────────────────────────────────────────────────── */
const argv = process.argv.slice(2);
const arg = (name, fallback) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};
const flag = (name) => argv.includes(`--${name}`);

const THEMES = arg('themes', 'hanaikada').split(',').filter(Boolean);
const MODES = arg('modes', 'idle,edit').split(',').filter(Boolean);
const ENGINES = arg('engines', 'chrome,firefox').split(',').filter(Boolean);
const SECONDS = Number(arg('seconds', '12'));
const WIDTH = Number(arg('width', '1600'));
const HEIGHT = Number(arg('height', '1000'));
const OUT = arg('out', '');
const HEADLESS = flag('headless');
const REPEAT = Number(arg('repeat', '1'));
/* Wo die Themen-Dateien liegen. Vorgabe ist der echte Stand; ein anderer Ordner
   erlaubt A/B-Läufe gegen Varianten, ohne den Baum anzufassen. */
const THEMES_DIR = arg('themes-dir', join(REPO, 'frontend/public/themes'));

/* ── Die Bühne ──────────────────────────────────────────────────────────────
   So nah an der echten Übersicht, wie eine eigenständige Seite kommen kann:
   dieselbe 920-px-Spalte, dieselben Kachel-Klassen, dasselbe Wackeln. Was
   fehlt, ist React — und das ist Absicht: gemessen werden soll die SZENE,
   nicht der Renderer. Der Zug schreibt darum von Hand genau das, was
   `HomeStage.runFrame` je Bild schreibt. */
const harness = (theme, mode, seconds, motion) => `<!doctype html>
<html lang="de" data-theme="${theme}"${motion ? ` data-scene-motion="${motion}"` : ''}>
<head>
<meta charset="utf-8">
<title>szene-perf ${theme} ${mode}</title>
<link rel="stylesheet" href="/index.css">
<link rel="stylesheet" href="/themes/${theme}.css">
<style>
  html, body { min-height: 100vh; margin: 0; }
  .app { max-width: 920px; margin: 0 auto; padding: 40px 24px; min-height: 100vh; }
  .idle__pages { display: block; }
  .idle__page { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
  .idle__tile {
    background: var(--bg-surface); border: 1px solid var(--bg-hairline);
    border-radius: 12px; padding: 16px; min-height: 120px; overflow-y: auto;
  }
  .perf { position: fixed; right: 8px; bottom: 8px; z-index: 99;
          font: 12px/1.4 monospace; color: var(--text-1);
          background: var(--bg-elevated); padding: 6px 8px; border-radius: 6px; }
</style>
</head>
<body>
<div class="app">
  <h1 style="font-size:2rem;margin:0 0 8px">21:47</h1>
  <p style="color:var(--text-2);margin:0 0 20px">Guten Abend, Andi.</p>
  <div class="idle__pages" data-edit="${mode === 'edit'}">
    <div class="idle__page" data-active="true">
      ${[
        ['Wetter', '21° / 14°, abends trocken.'],
        ['Einkauf', 'Milch · Hafer · Zitronen'],
        ['Räume', 'Wohnzimmer · Küche · Bad'],
        ['Nachrichten', 'Drei neue Meldungen.'],
        ['Musik', 'Zuletzt: Shirakami.'],
        ['Termine', 'Morgen 09:30 Zahnarzt.'],
      ]
        .map(
          ([t, s], i) =>
            `<div class="idle__tile" data-widget-id="w${i}" data-edit="${mode === 'edit'}" data-dragging="false" style="animation-delay:${-i * 190}ms">
               <p style="color:var(--text-1);margin:0 0 6px;font-weight:600">${t}</p>
               <p style="color:var(--text-2);margin:0">${s}</p>
               <p style="color:var(--text-4);margin:6px 0 0">Stand 21:40</p>
             </div>`,
        )
        .join('')}
    </div>
  </div>
</div>
<div class="perf" id="perf">misst …</div>
<script>
(() => {
  const MODE = ${JSON.stringify(mode)};
  const WINDOW_MS = ${seconds} * 1000;
  const WARMUP_MS = 2000;

  const page = document.querySelector('.idle__page');
  const dragged = document.querySelector('[data-widget-id="w0"]');
  if (MODE === 'edit') dragged.setAttribute('data-dragging', 'true');

  const deltas = [];
  let last = 0, t0 = 0, started = false, n = 0;

  const frame = (now) => {
    // Der Zug: EIN erzwungenes Layout + EIN transform je Bild — genau die
    // Arbeit aus HomeStage.runFrame (activePageBox() + style.transform).
    if (MODE === 'edit') {
      const box = page.getBoundingClientRect();
      const a = now / 700;
      const dx = Math.round(Math.cos(a) * 120 + box.width * 0);
      const dy = Math.round(Math.sin(a) * 90);
      dragged.style.transform = 'translate3d(' + dx + 'px,' + dy + 'px,0)';
    }
    if (!started) {
      if (!t0) t0 = now;
      if (now - t0 >= WARMUP_MS) { started = true; last = now; t0 = now; }
      return requestAnimationFrame(frame);
    }
    if (last) deltas.push(now - last);
    last = now;
    n++;
    if (now - t0 < WINDOW_MS) return requestAnimationFrame(frame);
    report();
  };

  const pct = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];

  const report = () => {
    const s = deltas.slice().sort((a, b) => a - b);
    const sum = deltas.reduce((a, b) => a + b, 0);
    const stats = {
      frames: deltas.length,
      seconds: +(sum / 1000).toFixed(2),
      fps: +((deltas.length / (sum / 1000))).toFixed(1),
      mean: +(sum / deltas.length).toFixed(2),
      p50: +pct(s, 0.5).toFixed(2),
      p95: +pct(s, 0.95).toFixed(2),
      p99: +pct(s, 0.99).toFixed(2),
      max: +s[s.length - 1].toFixed(2),
      over20: deltas.filter((d) => d > 20).length,
      over33: deltas.filter((d) => d > 33).length,
      viewport: innerWidth + 'x' + innerHeight,
      dpr: devicePixelRatio,
      ua: navigator.userAgent,
    };
    document.getElementById('perf').textContent =
      stats.fps + ' fps · p95 ' + stats.p95 + ' ms · >20ms ' + stats.over20;
    fetch('/report', { method: 'POST', body: JSON.stringify(stats) });
  };

  requestAnimationFrame(frame);
})();
</script>
</body>
</html>`;

/* ── Prozess-Buchhaltung ────────────────────────────────────────────────────
   `ps -o time` liefert auf macOS MM:SS.hh — Hundertstel, fein genug für ein
   Fenster von zwölf Sekunden. Gezählt wird der GANZE Baum unter der eigenen
   PID (Firefox: Parent + Tab + GPU-Helper; Chrome: Browser + Renderer + GPU),
   plus jeder Prozess, der das eigene Wegwerf-Profil in der Kommandozeile
   trägt — Chrome hängt seine Helfer nicht immer unter die eigene PID. */
const psSnapshot = () => {
  const out = execFileSync('/bin/ps', ['-Ao', 'pid=,ppid=,time=,args='], {
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
  });
  const rows = [];
  for (const line of out.split('\n')) {
    const m = line.match(/^\s*(\d+)\s+(\d+)\s+(\S+)\s+(.*)$/);
    if (!m) continue;
    const [, pid, ppid, time, args] = m;
    const parts = time.split(':').map(Number);
    const secs = parts.length === 3 ? parts[0] * 3600 + parts[1] * 60 + parts[2] : parts[0] * 60 + parts[1];
    rows.push({ pid: Number(pid), ppid: Number(ppid), secs, args });
  }
  return rows;
};

const treeCpu = (rows, rootPid, profileDir) => {
  const byPid = new Map(rows.map((r) => [r.pid, r]));
  const inTree = (r) => {
    let cur = r,
      hops = 0;
    while (cur && hops++ < 12) {
      if (cur.pid === rootPid) return true;
      cur = byPid.get(cur.ppid);
    }
    return false;
  };
  const mine = rows.filter((r) => inTree(r) || r.args.includes(profileDir));
  const per = {};
  let total = 0;
  for (const r of mine) {
    total += r.secs;
    const kind = /GPU|gpu/.test(r.args)
      ? 'gpu'
      : /Renderer|-childID|tab$|\btab\b/.test(r.args)
        ? 'content'
        : 'other';
    per[kind] = +((per[kind] || 0) + r.secs).toFixed(2);
  }
  return { total: +total.toFixed(2), per, pids: mine.length };
};

/* ── Profile ────────────────────────────────────────────────────────────── */
const firefoxProfile = () => {
  const dir = mkdtempSync(join(tmpdir(), 'szene-perf-ff-'));
  writeFileSync(
    join(dir, 'user.js'),
    [
      'user_pref("browser.shell.checkDefaultBrowser", false);',
      'user_pref("browser.startup.homepage_override.mstone", "ignore");',
      'user_pref("datareporting.policy.dataSubmissionEnabled", false);',
      'user_pref("browser.aboutwelcome.enabled", false);',
      'user_pref("toolkit.telemetry.reportingpolicy.firstRun", false);',
      'user_pref("browser.sessionstore.resume_from_crash", false);',
      'user_pref("browser.tabs.warnOnClose", false);',
      'user_pref("app.update.auto", false);',
    ].join('\n'),
  );
  // Fenstergröße kommt aus dem Profil — `--window-size` kennt Firefox nur headless.
  writeFileSync(
    join(dir, 'xulstore.json'),
    JSON.stringify({
      'chrome://browser/content/browser.xhtml': {
        'main-window': {
          screenX: '0',
          screenY: '0',
          width: String(WIDTH),
          height: String(HEIGHT),
          sizemode: 'normal',
        },
      },
    }),
  );
  return dir;
};

const chromeProfile = () => mkdtempSync(join(tmpdir(), 'szene-perf-cr-'));

const launch = (engine, url, profile) => {
  if (engine === 'firefox') {
    const args = ['--no-remote', '--new-instance', '--profile', profile];
    if (HEADLESS) args.push('--headless', `--window-size=${WIDTH},${HEIGHT}`);
    args.push(url);
    return spawn(FIREFOX, args, { stdio: 'ignore', detached: false });
  }
  const args = [
    `--user-data-dir=${profile}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-features=Translate,MediaRouter',
    `--window-size=${WIDTH},${HEIGHT}`,
    '--window-position=0,0',
  ];
  if (HEADLESS) args.push('--headless=new');
  args.push(url);
  return spawn(CHROME, args, { stdio: 'ignore', detached: false });
};

/* ── Server ─────────────────────────────────────────────────────────────── */
let pending = null;
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (req.method === 'POST' && url.pathname === '/report') {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      res.writeHead(204).end();
      try {
        pending?.(JSON.parse(body));
      } catch {
        pending?.(null);
      }
    });
    return;
  }
  if (url.pathname === '/index.css') {
    res.writeHead(200, { 'content-type': 'text/css' });
    return res.end(readFileSync(INDEX_CSS));
  }
  if (url.pathname.startsWith('/themes/')) {
    const name = url.pathname.slice('/themes/'.length);
    if (/[^A-Za-z0-9._-]/.test(name)) return res.writeHead(400).end();
    try {
      const data = readFileSync(join(THEMES_DIR, name));
      const type = name.endsWith('.svg') ? 'image/svg+xml' : 'text/css';
      res.writeHead(200, { 'content-type': type, 'cache-control': 'no-store' });
      return res.end(data);
    } catch {
      return res.writeHead(404).end();
    }
  }
  if (url.pathname === '/harness') {
    const theme = url.searchParams.get('theme') || 'hanaikada';
    const mode = url.searchParams.get('mode') || 'idle';
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    return res.end(harness(theme, mode, SECONDS, url.searchParams.get('motion') || ''));
  }
  res.writeHead(404).end();
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* `idle` · `edit` · dieselben mit Suffix `-still` (das Attribut, das
   `HomeStage` im Edit-Modus an <html> setzt) — so misst dieses Werkzeug den
   AUSGELIEFERTEN Zustand und nicht ein Labor-Konstrukt. */
const run = async (engine, theme, spec, port) => {
  const still = spec.endsWith('-still');
  const mode = still ? spec.slice(0, -'-still'.length) : spec;
  const profile = engine === 'firefox' ? firefoxProfile() : chromeProfile();
  const url =
    `http://127.0.0.1:${port}/harness?theme=${theme}&mode=${mode}` + (still ? '&motion=still' : '');
  const got = new Promise((resolve) => (pending = resolve));
  const child = launch(engine, url, profile);

  // Aufwärmen: Start, erster Paint, Warmup der Seite (2 s) — erst dann zählen.
  await sleep(5000);
  const before = treeCpu(psSnapshot(), child.pid, profile);
  const t0 = Date.now();

  const stats = await Promise.race([got, sleep((SECONDS + 25) * 1000).then(() => null)]);
  const wall = (Date.now() - t0) / 1000;
  const after = treeCpu(psSnapshot(), child.pid, profile);

  try {
    process.kill(child.pid, 'SIGTERM');
  } catch {
    /* schon fort */
  }
  await sleep(1500);
  try {
    process.kill(child.pid, 'SIGKILL');
  } catch {
    /* sauber beendet */
  }
  await sleep(500);
  try {
    rmSync(profile, { recursive: true, force: true });
  } catch {
    /* egal */
  }

  const cpu = +(after.total - before.total).toFixed(2);
  const perKind = {};
  for (const k of new Set([...Object.keys(before.per), ...Object.keys(after.per)])) {
    perKind[k] = +((after.per[k] || 0) - (before.per[k] || 0)).toFixed(2);
  }
  return {
    engine,
    theme,
    mode: spec,
    cpuSeconds: cpu,
    cpuPercent: +((cpu / wall) * 100).toFixed(0),
    cpuPerKind: perKind,
    wallSeconds: +wall.toFixed(1),
    ...(stats || { frames: 0, note: 'kein Bericht — Lauf verworfen' }),
  };
};

const main = async () => {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;
  const results = [];
  for (let round = 0; round < REPEAT; round++) {
    for (const engine of ENGINES) {
      for (const theme of THEMES) {
        for (const mode of MODES) {
          process.stderr.write(`… ${engine} ${theme} ${mode}${REPEAT > 1 ? ` (#${round + 1})` : ''}\n`);
          results.push({ round: round + 1, ...(await run(engine, theme, mode, port)) });
        }
      }
    }
  }
  server.close();

  const head = ['engine', 'theme', 'mode', 'fps', 'mean', 'p95', 'p99', 'max', '>20ms', '>33ms', 'CPU-s', 'CPU-%'];
  const rows = results.map((r) => [
    r.engine,
    r.theme,
    r.mode,
    r.fps ?? '—',
    r.mean ?? '—',
    r.p95 ?? '—',
    r.p99 ?? '—',
    r.max ?? '—',
    r.over20 ?? '—',
    r.over33 ?? '—',
    r.cpuSeconds,
    r.cpuPercent,
  ]);
  const w = head.map((h, i) => Math.max(String(h).length, ...rows.map((r) => String(r[i]).length)));
  const line = (cells) => cells.map((c, i) => String(c).padEnd(w[i])).join('  ');
  console.log(line(head));
  console.log(w.map((n) => '─'.repeat(n)).join('  '));
  for (const r of rows) console.log(line(r));

  if (OUT) {
    mkdirSync(dirname(OUT), { recursive: true });
    writeFileSync(OUT, JSON.stringify({ width: WIDTH, height: HEIGHT, seconds: SECONDS, results }, null, 2));
    console.log(`\n→ ${OUT}`);
  }
};

main().catch((e) => {
  console.error(e);
  process.exit(1);
});

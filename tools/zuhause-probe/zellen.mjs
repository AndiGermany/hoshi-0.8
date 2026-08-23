/**
 * **Die Zellen-Sonde** — welche Kachel besitzt welche Zelle, wie viele Zeilen
 * zeigt eine Seite WIRKLICH, und welche Zelle kann ein Finger überhaupt treffen?
 *
 * Entstanden aus Andis Livetest vom 23.08.: *„die uhr wird über die komplette
 * höhe einer seite angezeigt. ich kann kein widgent auf die linke seite der
 * ersten seite verschieben"* — zwei Sätze, die wie zwei Fehler klingen und
 * einer sind. Weder `probe.mjs` (misst Kachel- vs. Inhaltsfläche) noch
 * `touch.mjs` (fährt Gesten) beantworten die Frage, die dahintersteckt:
 * **stimmt das gerenderte Raster mit dem Modell überein?** Eine Seite, die
 * weniger Zeilen zeichnet, als auf sie passen, ist von außen nicht zu sehen —
 * man sieht nur eine Kachel, die zu groß aussieht, und einen Abwurf, der nicht
 * ankommt.
 *
 * Die Sonde fährt BEIDE Engines über denselben Messtext: Chrome über CDP
 * (`touch.mjs`-Muster) und echtes Firefox über WebDriver BiDi
 * (`firefox.mjs`-Muster) — Andi fährt Firefox, und ein Befund, den nur Chrome
 * kennt, ist kein Befund über sein Gerät.
 *
 * NUTZUNG: node zellen.mjs [breite] [hoehe]
 *   ZELL_ENGINE=chrome|firefox   Engine (Vorgabe chrome)
 *   ZELL_PORT=8796               Port des `serve.mjs`
 *   ZELL_SEED=v1|w7|luecke       Layout-Saat (Vorgabe w7)
 *   ZELL_ZUG=1                   zusätzlich EINEN Zug in die unterste Zeile fahren
 *   ZELL_ZEIT=2026-08-28T01:00Z  die Uhr der SEITE stellen (Nachtbilder!)
 *   ZELL_SHOT=<datei.png>        Bild am Ende
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const ENGINE = process.env.ZELL_ENGINE === 'firefox' ? 'firefox' : 'chrome';
const PORT = Number(process.env.ZELL_PORT ?? 8796);
const BASE = `http://127.0.0.1:${PORT}/`;
const W = Number(process.argv[2] ?? 1366);
const H = Number(process.argv[3] ?? 900);
const SEED_KIND = ['v1', 'w7', 'luecke'].includes(process.env.ZELL_SEED ?? '') ? process.env.ZELL_SEED : 'w7';
const ZUG = process.env.ZELL_ZUG === '1';
const SHOT = process.env.ZELL_SHOT ?? '';
/** `ZELL_ZEIT=2026-08-21T23:30:00Z` — die Uhr der SEITE stellen (s. `zeitJs`). */
const ZEIT = process.env.ZELL_ZEIT ? Date.parse(process.env.ZELL_ZEIT) : Number.NaN;
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FIREFOX = '/Applications/Firefox.app/Contents/MacOS/firefox';

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

/* ── Die drei Saaten ──────────────────────────────────────────────────────────
 * `v1`     — ein Gerät, das noch nie etwas angeordnet hat (der Packer sät).
 * `w7`     — dieselben gewachsenen Zellen wie in `touch.mjs`/`firefox.mjs`.
 * `luecke` — **Andis Form**: die Uhr groß links, zwei kleine Kacheln rechts
 *            daneben, und die UNTERSTE Zeile der Seite bleibt frei. Genau das
 *            ordnet ein Mensch an, und genau das hat das freie Raster bis zum
 *            23.08. falsch gezeichnet.
 */
const ORDER_V1 = [
  { id: 'wetter', size: 'L' }, { id: 'laeuft', size: 'L' }, { id: 'einkauf', size: 'L' },
  { id: 'vacuum', size: 'L' }, { id: 'climate', size: 'L' }, { id: 'news', size: 'L' },
];
const ORDER_W7 = [
  { id: 'uhr', size: 'L' }, { id: 'wecker', size: 'L' }, { id: 'wetter', size: 'XL' },
  { id: 'laeuft', size: 'M' }, { id: 'einkauf', size: 'M' }, { id: 'vacuum', size: 'XL' },
  { id: 'climate', size: 'L' }, { id: 'news', size: 'M' },
];
const PLACEMENTS_W7 = {
  2: { uhr: { col: 0, row: 0 }, wecker: { col: 1, row: 0 }, wetter: { col: 0, row: 1 },
       laeuft: { col: 0, row: 3 }, einkauf: { col: 1, row: 3 }, vacuum: { col: 0, row: 4 },
       climate: { col: 0, row: 6 }, news: { col: 1, row: 6 } },
  3: { uhr: { col: 0, row: 0 }, wecker: { col: 2, row: 0 }, wetter: { col: 0, row: 1 },
       laeuft: { col: 2, row: 1 }, einkauf: { col: 2, row: 2 }, vacuum: { col: 0, row: 3 },
       climate: { col: 0, row: 5 }, news: { col: 2, row: 5 } },
  4: { uhr: { col: 0, row: 0 }, wecker: { col: 3, row: 0 }, wetter: { col: 0, row: 1 },
       laeuft: { col: 2, row: 1 }, einkauf: { col: 3, row: 1 }, vacuum: { col: 0, row: 3 },
       climate: { col: 2, row: 3 }, news: { col: 2, row: 5 } },
};
const ORDER_LUECKE = [
  { id: 'uhr', size: 'L' }, { id: 'wecker', size: 'S' }, { id: 'wetter', size: 'L' },
  { id: 'laeuft', size: 'S' }, { id: 'einkauf', size: 'S' }, { id: 'vacuum', size: 'L' },
  { id: 'climate', size: 'L' }, { id: 'news', size: 'S' },
];
const PLACEMENTS_LUECKE = {
  3: { uhr: { col: 0, row: 0 }, wecker: { col: 2, row: 0 }, einkauf: { col: 2, row: 1 },
       wetter: { col: 0, row: 3 }, laeuft: { col: 2, row: 3 }, vacuum: { col: 0, row: 6 },
       climate: { col: 0, row: 9 }, news: { col: 2, row: 9 } },
};

const LAYOUT =
  SEED_KIND === 'luecke' ? { version: 1, order: ORDER_LUECKE, placements: PLACEMENTS_LUECKE }
  : SEED_KIND === 'w7' ? { version: 1, order: ORDER_W7, placements: PLACEMENTS_W7 }
  : { version: 1, order: ORDER_V1 };

const SEED = {
  'hoshi.settings': JSON.stringify({ theme: 'aoi', language: 'de', voice: 'coral' }),
  'hoshi.homeTiles.wetter': 'true', 'hoshi.homeTiles.laeuft': 'true',
  'hoshi.homeTiles.einkauf': 'true', 'hoshi.homeTiles.vacuum': 'true',
  'hoshi.homeTiles.climate': 'true', 'hoshi.homeTiles.currentAffairs': 'true',
  'hoshi.homeTiles.layout': JSON.stringify(LAYOUT),
};
const seedJs = Object.entries(SEED)
  .map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`)
  .join('');

/**
 * **Die Uhr der Seite stellen** — für Bilder, die man sonst nur nachts machen
 * könnte (die Uhr-L zeigt zwischen Sonnenunter- und -aufgang die Mondphase).
 *
 * Ein VERSATZ, kein eingefrorener Zeitpunkt: die Zeit läuft weiter, nur eben ab
 * einem anderen Punkt. Ein stehender `Date.now()` hätte den Minuten-Tick der
 * Uhr angehalten und damit ein Bild fotografiert, das es so nie gibt.
 */
const zeitJs = Number.isFinite(ZEIT)
  ? `(() => {
      const Echt = Date;
      const versatz = ${ZEIT} - Echt.now();
      const Gestellt = function (...a) {
        return a.length === 0 ? new Echt(Echt.now() + versatz) : new Echt(...a);
      };
      Gestellt.prototype = Echt.prototype;
      Gestellt.now = () => Echt.now() + versatz;
      Gestellt.parse = Echt.parse;
      Gestellt.UTC = Echt.UTC;
      globalThis.Date = Gestellt;
    })();`
  : '';

/* ── DER Messtext ─────────────────────────────────────────────────────────────
 * Er kennt dieselben drei Zahlen wie `homeLayout.ts` (132 px Mindesthöhe,
 * 252 px Mindestbreite, 12 px Lücke) — sonst könnte er nicht sagen, wie viele
 * Zeilen auf eine Seite PASSEN, sondern nur, wie viele gezeichnet werden. Der
 * Unterschied zwischen diesen beiden Zahlen ist der ganze Befund.
 */
const MESSEN = `JSON.stringify((() => {
  const MIN_H = 132, MIN_W = 252, GAP = 12;
  const fit = (a, min) => Math.floor((a + GAP) / (min + GAP));
  const box = (el) => { if (!el) return null; const r = el.getBoundingClientRect();
    return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
  const pages = [...document.querySelectorAll('.idle__page')];
  const aktiv = pages.findIndex((p) => p.dataset.active === 'true');
  const pagesBox = box(document.querySelector('.idle__pages'));
  const passt = pagesBox ? Math.max(1, fit(pagesBox.h, MIN_H)) : 0;
  const spalten = pagesBox ? Math.max(1, Math.min(4, fit(pagesBox.w, MIN_W))) : 0;
  const seiten = pages.map((p, i) => {
    const cs = getComputedStyle(p);
    const gezeichnet = cs.gridTemplateRows.split(' ').filter(Boolean).length;
    // HomeStage schreibt "1 / span 2" als INLINE-Stil — das ist die Wahrheit
    // des Modells. Der berechnete Stil meldet in beiden Engines "span 2" statt
    // einer Linie und taugt darum nicht zum Rechnen.
    const linie = (s, fallback) => {
      const m = /^\\s*(\\d+)\\s*\\/\\s*span\\s*(\\d+)/.exec(s || '');
      return m ? { start: Number(m[1]) - 1, span: Number(m[2]) } : fallback;
    };
    const kacheln = [...p.querySelectorAll('[data-widget-id]')].map((t) => {
      const c = getComputedStyle(t);
      const sc = linie(t.style.gridColumn, { start: parseInt(c.gridColumnStart, 10) - 1, span: 1 });
      const sr = linie(t.style.gridRow, { start: parseInt(c.gridRowStart, 10) - 1, span: 1 });
      return { id: t.getAttribute('data-widget-id'), col: sc.start, row: sr.start,
               cols: sc.span, rows: sr.span, rect: box(t) };
    }).filter((k) => Number.isFinite(k.col) && Number.isFinite(k.row));
    // Belegung im MODELL-Raster (spalten x passt), nicht im gezeichneten.
    const belegt = Array.from({ length: passt }, () => new Array(spalten).fill(false));
    for (const k of kacheln) for (let r = k.row; r < k.row + k.rows; r++)
      for (let c = k.col; c < k.col + k.cols; c++) if (belegt[r] && c < spalten) belegt[r][c] = true;
    const frei = [];
    for (let r = 0; r < passt; r++) for (let c = 0; c < spalten; c++) if (!belegt[r][c]) frei.push([c, r]);
    return { index: i, aktiv: p.dataset.active === 'true', box: box(p),
             gezeichneteZeilen: gezeichnet, kacheln, freieZellen: frei,
             unerreichbareZeilen: Math.max(0, passt - gezeichnet) };
  });
  return { engine: navigator.userAgent.includes('Firefox') ? 'firefox' : 'chrome',
           viewport: { w: innerWidth, h: innerHeight },
           buehne: box(document.querySelector('.idle__stage')), pagesBox,
           spalten, zeilenProSeite: passt, seitenzahl: pages.length, aktiv, seiten };
})())`;

/* ── Engine-Anschluss: zwei Wege, EIN Vertrag ─────────────────────────────── */
let evalJs, setzeSaat, laden, zeigerAktionen, bild, ende;

if (ENGINE === 'chrome') {
  const profile = mkdtempSync(join(tmpdir(), 'hoshi-zellen-'));
  const child = spawn(CHROME, ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    '--force-device-scale-factor=1', '--no-first-run', '--no-default-browser-check',
    `--remote-debugging-port=${Number(process.env.ZELL_DEBUG_PORT ?? 9481)}`,
    `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' });
  process.on('exit', () => child.kill('SIGKILL'));
  const dp = Number(process.env.ZELL_DEBUG_PORT ?? 9481);
  let version = null;
  for (let i = 0; i < 120; i++) {
    try { version = await (await fetch(`http://127.0.0.1:${dp}/json/version`)).json(); break; }
    catch { await sleep(100); }
  }
  if (!version) throw new Error('Chrome kam nicht hoch');
  const ws = new WebSocket(version.webSocketDebuggerUrl);
  await new Promise((r) => (ws.onopen = r));
  let seq = 0; const pending = new Map(); const events = [];
  ws.onmessage = (m) => { const msg = JSON.parse(m.data);
    if (msg.id && pending.has(msg.id)) { const p = pending.get(msg.id); pending.delete(msg.id);
      msg.error ? p.reject(new Error(JSON.stringify(msg.error))) : p.resolve(msg.result); }
    else if (msg.method) events.push(msg); };
  const send = (method, params = {}, sid) => new Promise((resolve, reject) => {
    const id = ++seq; pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params, ...(sid ? { sessionId: sid } : {}) })); });
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  const cmd = (m, p) => send(m, p, sessionId);
  await cmd('Page.enable'); await cmd('Runtime.enable');
  await cmd('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false });
  await cmd('Page.addScriptToEvaluateOnNewDocument', { source: zeitJs + seedJs });
  evalJs = async (expression) => {
    const res = await cmd('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (res.exceptionDetails) throw new Error(JSON.stringify(res.exceptionDetails).slice(0, 400));
    return res.result.value; };
  setzeSaat = async () => {};
  laden = async () => {
    const seen = events.length;
    await cmd('Page.navigate', { url: BASE });
    for (let i = 0; i < 200; i++) {
      if (events.slice(seen).some((e) => e.method === 'Page.loadEventFired')) break;
      await sleep(50);
    }
  };
  zeigerAktionen = async (punkte) => {
    for (const a of punkte) {
      if (a.type === 'pause') { await sleep(a.duration); continue; }
      const typ = a.type === 'down' ? 'mousePressed' : a.type === 'up' ? 'mouseReleased' : 'mouseMoved';
      await cmd('Input.dispatchMouseEvent', {
        type: typ, x: Math.round(a.x), y: Math.round(a.y), button: 'left',
        buttons: a.type === 'up' ? 0 : 1, clickCount: a.type === 'move' ? 0 : 1 });
      await sleep(16);
    }
  };
  bild = async (datei) => {
    const png = await cmd('Page.captureScreenshot', { format: 'png' });
    writeFileSync(datei, Buffer.from(png.data, 'base64'));
  };
  ende = () => { ws.close(); child.kill('SIGKILL'); };
} else {
  const profile = mkdtempSync(join(tmpdir(), 'hoshi-ff-zellen-'));
  const bidiPort = Number(process.env.ZELL_BIDI_PORT ?? 9223);
  const child = spawn(FIREFOX, ['--headless', '--no-remote', '--profile', profile,
    `--remote-debugging-port=${bidiPort}`, 'about:blank'], { stdio: 'ignore' });
  process.on('exit', () => child.kill('SIGKILL'));
  let ws = null;
  for (let i = 0; i < 150; i++) {
    try {
      const probe = new WebSocket(`ws://127.0.0.1:${bidiPort}/session`);
      await new Promise((ok, no) => { probe.onopen = ok; probe.onerror = no; });
      ws = probe; break;
    } catch { await sleep(200); }
  }
  if (!ws) throw new Error('Firefox BiDi kam nicht hoch');
  let seq = 0; const pending = new Map();
  ws.onmessage = (m) => { const msg = JSON.parse(m.data);
    if (msg.id && pending.has(msg.id)) { const p = pending.get(msg.id); pending.delete(msg.id);
      msg.type === 'success' ? p.resolve(msg.result) : p.reject(new Error(JSON.stringify(msg).slice(0, 400))); } };
  const bidi = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++seq; pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params })); });
  await bidi('session.new', { capabilities: { alwaysMatch: { acceptInsecureCerts: true } } });
  const tree = await bidi('browsingContext.getTree', {});
  const context = tree.contexts[0].context;
  await bidi('browsingContext.setViewport', { context, viewport: { width: W, height: H } });
  // BiDis Gegenstück zu Chromes `addScriptToEvaluateOnNewDocument`.
  if (zeitJs) {
    await bidi('script.addPreloadScript', { functionDeclaration: `() => { ${zeitJs} }` });
  }
  evalJs = async (expression) => {
    const res = await bidi('script.evaluate', { expression, target: { context }, awaitPromise: true, resultOwnership: 'none' });
    if (res.type === 'exception') throw new Error(JSON.stringify(res.exceptionDetails).slice(0, 400));
    return res.result?.value ?? null; };
  // Firefox braucht die Origin, BEVOR jemand in ihren localStorage schreibt.
  setzeSaat = async () => {
    await bidi('browsingContext.navigate', { context, url: BASE, wait: 'complete' });
    await evalJs(seedJs + '"ok"');
  };
  laden = async () => { await bidi('browsingContext.reload', { context, wait: 'complete' }); };
  zeigerAktionen = async (punkte) => {
    const acts = [];
    for (const a of punkte) {
      if (a.type === 'pause') acts.push({ type: 'pause', duration: a.duration });
      else if (a.type === 'down') acts.push({ type: 'pointerDown', button: 0 });
      else if (a.type === 'up') acts.push({ type: 'pointerUp', button: 0 });
      else acts.push({ type: 'pointerMove', x: Math.round(a.x), y: Math.round(a.y), origin: 'viewport', duration: 16 });
    }
    await bidi('input.performActions', { context, actions: [{ type: 'pointer', id: 'zeiger1', parameters: { pointerType: 'mouse' }, actions: acts }] });
  };
  bild = async (datei) => {
    const png = await bidi('browsingContext.captureScreenshot', { context });
    writeFileSync(datei, Buffer.from(png.data, 'base64'));
  };
  ende = () => { ws.close(); child.kill('SIGKILL'); };
}

/**
 * **Die Saat muss ANKOMMEN, nicht nur geschrieben werden.** Firefox bekommt sie
 * erst nach dem ersten Laden (die Origin muss stehen) — und genau dazwischen
 * schreibt die Bühne ihre eigene, frisch gesäte Anordnung zurück. Wer nur
 * schreibt und neu lädt, misst dann gelegentlich das Bild der App statt Andis.
 * Also: schreiben, laden, GEGENLESEN, notfalls wiederholen.
 */
const gesaeteZellen = JSON.stringify(LAYOUT.placements ?? null);
for (let versuch = 1; versuch <= 3; versuch += 1) {
  await setzeSaat();
  await laden();
  for (let i = 0; i < 150; i++) {
    if (await evalJs(`document.querySelectorAll('.idle__tile').length > 0`)) break;
    await sleep(100);
  }
  await sleep(1200);
  const da = await evalJs(
    `JSON.stringify(JSON.parse(localStorage.getItem('hoshi.homeTiles.layout') || '{}').placements ?? null)`,
  );
  if (da === gesaeteZellen || !LAYOUT.placements) break;
  if (versuch === 3) console.log(`\n⚠ Saat kam nicht an (${da}) — Messung ist NICHT Andis Anordnung`);
}

const zeig = (m) => {
  console.log(`\n${m.engine} · ${m.viewport.w}x${m.viewport.h} · Bühne ${m.buehne?.w}x${m.buehne?.h} · Schiene ${m.pagesBox?.w}x${m.pagesBox?.h}`);
  console.log(`Raster: ${m.spalten} Spalten × ${m.zeilenProSeite} Zeilen je Seite · ${m.seitenzahl} Seiten (aktiv ${m.aktiv})`);
  for (const s of m.seiten) {
    const warn = s.unerreichbareZeilen > 0 ? `  ⚠ ${s.unerreichbareZeilen} Zeile(n) NICHT gezeichnet` : '';
    console.log(` Seite ${s.index}${s.aktiv ? ' *' : ''}: gezeichnet ${s.gezeichneteZeilen}/${m.zeilenProSeite} Zeilen${warn}`);
    for (const k of s.kacheln) {
      console.log(`   ${String(k.id).padEnd(8)} Zelle (${k.col},${k.row}) ${k.cols}x${k.rows}  ${k.rect.w}x${k.rect.h} px` +
        (k.rect.h >= (s.box.h - 4) ? '  ← volle Seitenhöhe' : ''));
    }
    console.log(`   frei: ${s.freieZellen.length ? s.freieZellen.map(([c, r]) => `(${c},${r})`).join(' ') : '—'}`);
  }
};

const vorher = JSON.parse(await evalJs(MESSEN));
zeig(vorher);

/* ── Der Zug in die unterste Zeile ────────────────────────────────────────────
 * Die zweite Hälfte von Andis Satz. Gegriffen wird die KLEINSTE Kachel der
 * aktiven Seite (sie hat die wenigsten Nachbarn, ihr Zug beweist am meisten),
 * das Ziel ist die unterste Zeile ganz links — also genau die Zelle, die eine
 * zu klein gezeichnete Seite gar nicht besitzt.
 *
 * ZWEI Gesten, nicht eine: der lange Druck öffnet den Edit-Modus, und der
 * nachlaufende Zug derselben Geste wird bewusst geschluckt (`4be374f0`). Erst
 * danach steht die Bühne in ihrer EDIT-Geometrie — sie ist niedriger, weil
 * Leiste und Fach Platz nehmen —, und nur gegen diese darf man zielen.
 */
if (ZUG) {
  const klein0 = [...vorher.seiten[Math.max(0, vorher.aktiv)].kacheln]
    .sort((a, b) => a.cols * a.rows - b.cols * b.rows)[0];
  // Geste 1: langer Druck ⇒ Edit-Modus.
  const p0 = { x: klein0.rect.x + klein0.rect.w / 2, y: klein0.rect.y + klein0.rect.h / 2 };
  await zeigerAktionen([{ type: 'move', ...p0 }, { type: 'down', ...p0 },
    { type: 'pause', duration: 900 }, { type: 'up', ...p0 }]);
  await sleep(800);

  const imEdit = JSON.parse(await evalJs(MESSEN));
  const seite = imEdit.seiten[Math.max(0, imEdit.aktiv)];
  const klein = seite.kacheln.find((k) => k.id === klein0.id) ?? klein0;
  const zielSpalte = 0;
  const zielZeile = imEdit.zeilenProSeite - 1;
  const spaltenBreite = (seite.box.w - 12 * (imEdit.spalten - 1)) / imEdit.spalten;
  const zeilenHoehe = (seite.box.h - 12 * (imEdit.zeilenProSeite - 1)) / imEdit.zeilenProSeite;
  const ziel = {
    x: seite.box.x + zielSpalte * (spaltenBreite + 12) + spaltenBreite / 2,
    y: seite.box.y + zielZeile * (zeilenHoehe + 12) + zeilenHoehe / 2,
  };
  // Ein Griffpunkt, an dem wirklich die KACHEL liegt: der offene Stufen-Wähler
  // deckt ihre Mitte zu, ein Zug von dort packte die Bedienung.
  const griff = JSON.parse(await evalJs(`JSON.stringify((() => {
    const b = ${JSON.stringify(klein.rect)};
    for (const t of [0.18, 0.3, 0.5, 0.75]) {
      const x = Math.round(b.x + b.w / 2), y = Math.round(b.y + b.h * t);
      const el = document.elementFromPoint(x, y);
      if (el && el.closest('[data-widget-id]') && !el.closest('.idle__sizer')) return { x, y };
    }
    return { x: Math.round(b.x + b.w / 2), y: Math.round(b.y + b.h * 0.18) };
  })())`));

  // Geste 2: der eigentliche Zug.
  const bahn = [{ type: 'move', ...griff }, { type: 'down', ...griff }];
  for (let i = 1; i <= 20; i++) {
    const t = i / 20;
    bahn.push({ type: 'move', x: griff.x + (ziel.x - griff.x) * t, y: griff.y + (ziel.y - griff.y) * t });
  }
  bahn.push({ type: 'up', ...ziel });
  await zeigerAktionen(bahn);
  await sleep(900);
  const nachher = JSON.parse(await evalJs(MESSEN));
  const gelandet = nachher.seiten.flatMap((s) => s.kacheln).find((k) => k.id === klein.id);
  console.log(`\nEdit-Bühne: Seite ${seite.box.w}x${seite.box.h}, gezeichnet ${seite.gezeichneteZeilen}/${imEdit.zeilenProSeite} Zeilen`);
  console.log(`   gegriffen bei (${griff.x},${griff.y}) · gezielt auf (${Math.round(ziel.x)},${Math.round(ziel.y)})`);
  console.log(`\nZUG: ${klein.id} von Zelle (${klein.col},${klein.row}) auf gezeigte Stelle (${zielSpalte},${zielZeile})`);
  console.log(`   gelandet: ${gelandet ? `(${gelandet.col},${gelandet.row})` : 'nicht gefunden'}` +
    `  ⇒ ${gelandet && gelandet.col === zielSpalte && gelandet.row === zielZeile ? 'ANGEKOMMEN' : 'NICHT ANGEKOMMEN'}`);
  zeig(nachher);
}

if (SHOT) { await bild(SHOT); console.log(`\nBild: ${SHOT}`); }
ende();
process.exit(0);

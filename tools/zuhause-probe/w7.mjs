/**
 * **W7-Sonde** — misst und fotografiert genau das, worüber Andi am 20.08. ein
 * Urteil gefällt hat: die vertikale Dichte (Bühne ↔ Orb ↔ „Tippen zum
 * Sprechen" ↔ Fußleiste), den INHALTS-ANTEIL jeder Kachel (»beim Wetter ist
 * nichts gefüllt«), die Breite der Fußleiste und die Höhe der Edit-Leiste.
 *
 * Warum eine eigene Datei neben `probe.mjs`/`shot.mjs`: die beiden messen bzw.
 * fotografieren, und keiner von beiden kommt in den **Edit-Modus**. Der ist
 * aber die Hälfte der Bestellung — und er lässt sich nur über einen echten
 * langen Druck erreichen (`Input.dispatchMouseEvent`, 700 ms zwischen
 * `mousePressed` und `mouseReleased`), nicht über einen localStorage-Schlüssel.
 * Ein Skript, das beides in EINER Sitzung tut, vergleicht außerdem Normal- und
 * Edit-Ansicht bei exakt derselben Fenstergröße und demselben Datenstand.
 *
 * Dieselben zwei Fallen wie in `shot.mjs` sind eingebaut:
 *  - eigener `--user-data-dir` unter `os.tmpdir()`, am Ende stirbt NUR der
 *    eigene Kindprozess — kein fremder Chrome wird angefasst.
 *  - die API-Antworten kommen aus `serve.mjs` (vertragstreue Formen).
 *
 * Am Ende wird auf die geschriebene DATEI gewartet (`statSync`, Größe > 0),
 * bevor das Skript sich beendet: ein Bild, das noch im Puffer hängt, kann
 * niemand ansehen.
 *
 * NUTZUNG: node w6.mjs <out-dir> <tag>
 *   SHOT_PORT (8794) · SHOT_CDP_PORT (9447)
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.SHOT_PORT ?? 8794);
const CDP_PORT = Number(process.env.SHOT_CDP_PORT ?? 9447);
const BASE = `http://127.0.0.1:${PORT}/`;
const OUT_DIR = process.argv[2];
const TAG = process.argv[3] ?? 'w6';
/**
 * Die zwei Fenster der Bestellung. `SHOT_SIZES=700x900,600x900` hängt weitere
 * an — gebraucht für die Edit-Hilfe: der Höhensprung, den Andi gesehen hat,
 * entsteht erst, wenn der Text UMBRICHT, und das tut er bei 1366 px nie. Eine
 * Zusage „springt nie" prüft man dort, wo das Alte gesprungen ist.
 */
const SIZES = (process.env.SHOT_SIZES ?? '1366x1024,834x1112').split(',').map((s) => {
  const [w, h] = s.trim().split('x').map(Number);
  return { w, h, name: `${w}x${h}` };
});

/**
 * Alle acht Widgets an, jedes auf seiner Registry-Default-Stufe (kein
 * `layout`-Schlüssel!): so misst die Sonde den Zustand, den ein neues Gerät
 * zeigt — und nicht eine Anordnung, die nur in diesem Skript existiert.
 */
const SEED = {
  // Die UI-Sprache steht hier BEWUSST nicht: sie kommt nicht aus localStorage,
  // sondern vom Server (`/api/v1/settings/language`), und gesetzt wird sie
  // einzig von `LanguageSection` — also erst, wenn jemand die Einstellungen
  // öffnet. Ein `SHOT_LANG`-Knopf hier wäre eine Attrappe: er stünde im Skript,
  // änderte aber nichts. (Nebenbefund der W6-Messung, s. RESULT.md — nach einem
  // Neuladen steht die Übersicht wieder auf Deutsch, bis man in die
  // Einstellungen geht. Eigene Baustelle, nicht diese.)
  'hoshi.settings': JSON.stringify({ theme: 'aoi', voice: 'coral' }),
  'hoshi.homeTiles.uhr': 'true',
  'hoshi.homeTiles.wecker': 'true',
  'hoshi.homeTiles.wetter': 'true',
  'hoshi.homeTiles.laeuft': 'true',
  'hoshi.homeTiles.einkauf': 'true',
  'hoshi.homeTiles.vacuum': 'true',
  'hoshi.homeTiles.climate': 'true',
  'hoshi.homeTiles.currentAffairs': 'true',
};

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

const profile = mkdtempSync(join(tmpdir(), 'hoshi-w7-'));
const child = spawn(
  CHROME,
  [
    '--headless=new',
    '--no-sandbox',
    '--disable-gpu',
    '--hide-scrollbars',
    '--force-device-scale-factor=1',
    '--no-first-run',
    '--no-default-browser-check',
    `--remote-debugging-port=${CDP_PORT}`,
    `--user-data-dir=${profile}`,
    'about:blank',
  ],
  { stdio: 'ignore' },
);
const cleanup = () => child.kill('SIGKILL');
process.on('exit', cleanup);

let version = null;
for (let i = 0; i < 100; i++) {
  try {
    version = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`)).json();
    break;
  } catch {
    await sleep(100);
  }
}
if (!version) throw new Error('Chrome-DevTools kam nicht hoch');

const ws = new WebSocket(version.webSocketDebuggerUrl);
await new Promise((r) => (ws.onopen = r));
let seq = 0;
const pending = new Map();
const events = [];
ws.onmessage = (m) => {
  const msg = JSON.parse(m.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
  } else if (msg.method) events.push(msg);
};
const send = (method, params = {}, sessionId) =>
  new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
  });

mkdirSync(OUT_DIR, { recursive: true });


/**
 * **Die W7-Messung.** Sie beantwortet genau die drei Fragen, die Andi am
 * 21.08. gestellt hat — und keine davon lässt sich aus dem Code ableiten:
 *
 *  1. Ändert der Edit-Modus die BÜHNEN-Geometrie? (`rail`, `pageOf`)
 *  2. Ändert er die KACHEL-Geometrie? (`tiles[].box`, mit offenem Wähler)
 *  3. Bleibt eine Lücke, wenn eine Kachel still wird? (`cells`)
 *
 * Gemessen werden ECHTE `getBoundingClientRect`-Zahlen in einem echten
 * Chrome — jsdom rechnet kein Grid, und genau daran ist der Befund
 * entstanden, den diese Sonde nachstellt.
 */
const MEASURE = `(() => {
  const r = (sel, root = document) => { const e = root.querySelector(sel); return e ? e.getBoundingClientRect() : null; };
  const round = (v) => (v === null || v === undefined ? null : Math.round(v));
  const box = (b) => (b ? { x: round(b.left), y: round(b.top), w: round(b.width), h: round(b.height) } : null);
  const pages = [...document.querySelectorAll('.idle__page')];
  const tiles = [...document.querySelectorAll('[data-widget-id]')].map((el) => {
    const b = el.getBoundingClientRect();
    return {
      id: el.getAttribute('data-widget-id'),
      page: pages.findIndex((p) => p.contains(el)),
      cell: el.style.gridColumn + ' / ' + el.style.gridRow,
      box: box(b),
    };
  });
  const stage = document.querySelector('.idle__stage');
  const inFlow = stage
    ? [...stage.children]
        .filter((c) => { const p = getComputedStyle(c).position; return p !== 'absolute' && p !== 'fixed'; })
        .map((c) => c.className)
    : [];
  return {
    viewport: { w: innerWidth, h: innerHeight },
    docScrollH: document.documentElement.scrollHeight,
    editing: document.querySelector('.idle__stage')?.getAttribute('data-edit') ?? 'false',
    rail: box(r('.idle__pages')),
    tilesBox: box(r('.idle__tiles')),
    pageCount: pages.length,
    inFlowChildrenOfStage: inFlow,
    editbar: box(r('.idle__editbar')),
    tray: box(r('.idle__tray')),
    sizer: box(r('.idle__sizer')),
    sizerCell: (() => { const s = document.querySelector('.idle__sizer'); return s ? s.style.gridColumn + ' / ' + s.style.gridRow : null; })(),
    sizerButtons: [...document.querySelectorAll('.idle__sizerbtn')].map((b) => {
      const bb = b.getBoundingClientRect();
      return { dir: b.getAttribute('data-dir'), label: b.getAttribute('aria-label'), disabled: b.disabled,
        w: round(bb.width), h: round(bb.height), glyphPx: Math.round(parseFloat(getComputedStyle(b).fontSize)) };
    }),
    grips: document.querySelectorAll('.idle__grip').length,
    visibleStepLetter: (() => { const s = document.querySelector('.idle__sizerstep');
      if (!s) return null; const bb = s.getBoundingClientRect();
      return { text: s.textContent, w: round(bb.width), h: round(bb.height) }; })(),
    storedLayout: localStorage.getItem('hoshi.homeTiles.layout'),
    tiles,
  };
})()`;

const report = {};

for (const size of SIZES) {
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  await send('Page.enable', {}, sessionId);
  await send('Runtime.enable', {}, sessionId);
  await send(
    'Emulation.setDeviceMetricsOverride',
    { width: size.w, height: size.h, deviceScaleFactor: 1, mobile: false },
    sessionId,
  );
  const seedJs = Object.entries(SEED)
    .map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`)
    .join('');
  await send('Page.addScriptToEvaluateOnNewDocument', { source: seedJs }, sessionId);

  const seen = events.length;
  await send('Page.navigate', { url: BASE }, sessionId);
  for (let i = 0; i < 200; i++) {
    if (events.slice(seen).some((e) => e.method === 'Page.loadEventFired')) break;
    await sleep(50);
  }
  for (let i = 0; i < 150; i++) {
    const res = await send(
      'Runtime.evaluate',
      { expression: `document.querySelectorAll('.idle__tile').length > 0`, returnByValue: true },
      sessionId,
    );
    if (res.result.value === true) break;
    await sleep(100);
  }
  await sleep(1000); // Einblend-Animationen ausklingen lassen

  const shoot = async (suffix) => {
    const shot = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
    const out = join(OUT_DIR, `${TAG}-${suffix}-${size.name}.png`);
    writeFileSync(out, Buffer.from(shot.data, 'base64'));
    // Auf die DATEI warten, nicht auf den Aufruf.
    for (let i = 0; i < 50; i++) {
      try {
        if (statSync(out).size > 0) return out;
      } catch {
        /* noch nicht da */
      }
      await sleep(50);
    }
    throw new Error(`Bild wurde nicht geschrieben: ${out}`);
  };

  const measure = async () =>
    (await send('Runtime.evaluate', { expression: MEASURE, returnByValue: true }, sessionId)).result.value;

  console.log(await shoot('normal'));
  report[`${size.name}/normal`] = await measure();

  /* ── in den Edit-Modus: echter langer Druck auf die erste Kachel ───────── */
  const pt = await send(
    'Runtime.evaluate',
    {
      expression: `(() => { const t = document.querySelector('.idle__page[data-active="true"] [data-widget-id]');
        if (!t) return null; const b = t.getBoundingClientRect();
        return { x: Math.round(b.left + b.width / 2), y: Math.round(b.top + b.height / 2), id: t.getAttribute('data-widget-id') }; })()`,
      returnByValue: true,
    },
    sessionId,
  );
  const p = pt.result.value;
  if (p) {
    const mouse = (type, x, y) =>
      send('Input.dispatchMouseEvent', { type, x, y, button: 'left', clickCount: 1 }, sessionId);
    await mouse('mousePressed', p.x, p.y);
    await sleep(900); // > HOME_LONG_PRESS_MS (600)
    await mouse('mouseReleased', p.x, p.y);
    await sleep(700);
    console.log(await shoot('edit'));
    report[`${size.name}/edit`] = { longPressOn: p.id, ...(await measure()) };

    /* ── der Lücken-Fall: eine Kachel wird STILL ────────────────────────────
       Nicht der Schalter (das wäre das Fach — eine ENTSCHEIDUNG, die keine
       Lücke hinterlassen soll), sondern die Verdien-Regel §1.3: „Läuft" bleibt
       eingeschaltet, hat aber nichts mehr zu sagen. Erzeugt wird das so, wie
       es der echte Backend-Fall erzeugt — `/api/v1/scheduled` liefert eine
       LEERE Liste; derselbe Parser, dieselbe Kachel-Entscheidung
       (`enabled.laeuft && scheduled.length > 0` in `IdleFace.tsx`). */
    await mouse('mousePressed', 4, 4); // Edit-Modus schliessen
    await mouse('mouseReleased', 4, 4);
    await sleep(400);
    await send(
      'Page.addScriptToEvaluateOnNewDocument',
      {
        source: `(() => { const real = window.fetch;
          window.fetch = (input, init) => {
            const url = typeof input === 'string' ? input : (input && input.url) || '';
            if (url.includes('/api/v1/scheduled')) {
              return Promise.resolve(new Response('[]', { status: 200, headers: { 'content-type': 'application/json' } }));
            }
            return real(input, init);
          }; })()`,
      },
      sessionId,
    );
    const seen2 = events.length;
    await send('Page.reload', {}, sessionId);
    for (let i = 0; i < 200; i++) {
      if (events.slice(seen2).some((e) => e.method === 'Page.loadEventFired')) break;
      await sleep(50);
    }
    await sleep(1800);
    console.log(await shoot('luecke'));
    report[`${size.name}/luecke`] = await measure();
  }

  await send('Target.closeTarget', { targetId });
}

const jsonPath = join(OUT_DIR, `${TAG}-messung.json`);
writeFileSync(jsonPath, JSON.stringify(report, null, 1));
console.log(jsonPath);
for (const [key, m] of Object.entries(report)) {
  console.log(`\n── ${key} ── viewport ${m.viewport.w}x${m.viewport.h}, doc ${m.docScrollH}, edit=${m.editing}`);
  console.log(`   Schiene ${m.rail?.w}x${m.rail?.h} @${m.rail?.y} · Kachel-Kasten ${m.tilesBox?.w}x${m.tilesBox?.h} · Seiten ${m.pageCount}`);
  console.log(`   im FLUSS der Buehne: ${JSON.stringify(m.inFlowChildrenOfStage)}`);
  console.log(`   Leiste ${JSON.stringify(m.editbar)} · Fach ${JSON.stringify(m.tray)}`);
  console.log(`   Waehler ${JSON.stringify(m.sizer)} Zelle ${m.sizerCell} · Griffecken ${m.grips} · Stufen-Buchstabe ${JSON.stringify(m.visibleStepLetter)}`);
  if (m.sizerButtons.length) console.log(`   Knoepfe: ${JSON.stringify(m.sizerButtons)}`);
  console.log('   Kacheln: ' + m.tiles.map((t) => `${t.id} S${t.page} [${t.cell}] ${t.box.w}x${t.box.h}@${t.box.x},${t.box.y}`).join(' · '));
}

ws.close();
cleanup();
process.exit(0);

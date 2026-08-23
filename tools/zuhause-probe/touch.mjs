/**
 * **Die Finger-Sonde** — der Zuhause-Reiter mit ECHTEN Touch-Ereignissen.
 *
 * Warum es diese Sonde zusätzlich zu `probe.mjs`/`shot.mjs` braucht: Andi
 * bedient Hoshi auf dem iPad, und dort schickt niemand `mousedown`. Ein
 * headless Chrome kann über CDP `Input.dispatchTouchEvent` echte Touch-Punkte
 * setzen — daraus baut der Renderer dieselben `pointer*`-Ereignisse wie ein
 * Finger auf Glas, inklusive `touch-action`-Auswertung und Scroll-Übernahme.
 * jsdom kann das NICHT (dort gibt es kein Layout, keine Scroll-Achse und kein
 * `touch-action`), die Test-Suite kann diesen Befund also gar nicht haben.
 *
 * Die Sonde ANTWORTET auf vier Fragen, alle als Zahlen, keine Meinung:
 *   1. Wechselt ein waagerechter Wisch die Seite?   (Seite vorher/nachher)
 *   2. Versetzt ein Zug im Edit-Modus eine Kachel?  (Kachel-Rechteck vorher/nachher)
 *   3. Verdecken die Edit-Überlagerungen die Fläche? (überlappte px)
 *   4. Kommt man auch von einem Link/Knopf aus in den Edit-Modus, ohne dass
 *      der kurze Tipp dabei verloren geht?           (Klicks am Ziel, Edit an/aus)
 *
 * `PROBE_INPUT=mouse` fährt DIESELBEN Schritte mit `Input.dispatchMouseEvent`
 * ab — so lässt sich der Laptop-Weg („am Laptop geht das") gegen den
 * Finger-Weg stellen, ohne zwei Skripte auseinanderdriften zu lassen.
 *
 * NUTZUNG: node touch.mjs [breite] [hoehe]        (Vorgabe 834x1112 = iPad)
 *   PROBE_INPUT=mouse|touch   Eingabeart (Vorgabe touch)
 *   PROBE_PORT=8796           Port des `serve.mjs`
 *   PROBE_SHOTS=<dir>         wenn gesetzt: Bilder je Schritt dorthin
 *   PROBE_TAG=<name>          Präfix der Bilder (Vorgabe: die Eingabeart)
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.PROBE_PORT ?? 8796);
const BASE = `http://127.0.0.1:${PORT}/`;
const DEBUG_PORT = Number(process.env.PROBE_DEBUG_PORT ?? 9451);
const W = Number(process.argv[2] ?? 834);
const H = Number(process.argv[3] ?? 1112);
const INPUT = process.env.PROBE_INPUT === 'mouse' ? 'mouse' : 'touch';
const SHOTS = process.env.PROBE_SHOTS ?? '';
const TAG = process.env.PROBE_TAG ?? INPUT;

/**
 * Genug Kacheln, dass bei 834×1112 mehr als EINE Seite entsteht — sonst
 * bewiese ein „Wisch wechselt die Seite nicht" gar nichts (auf einer einzigen
 * Seite gibt es nichts zu wischen, und `HomeStage` riegelt dort bewusst ab).
 */
const ORDER_V1 = [
  { id: 'wetter', size: 'L' },
  { id: 'laeuft', size: 'L' },
  { id: 'einkauf', size: 'L' },
  { id: 'vacuum', size: 'L' },
  { id: 'climate', size: 'L' },
  { id: 'news', size: 'L' },
];

/**
 * **Andis Format, nicht das Lehrbuch-Format** (`PROBE_SEED=w7`).
 *
 * Die v1-Saat oben ist eine reine REIHENFOLGE — genau der Zustand eines
 * Geräts, das noch nie etwas angeordnet hat. Andi fährt seit W7 etwas
 * anderes: gewachsene `placements` je Spaltenzahl, dazu die Uhr und den
 * Wecker (die die v1-Saat gar nicht nennt) und Stufen, die er selbst gewählt
 * hat. Wer nur die erste Saat prüft, prüft ein Gerät, das es bei ihm nicht
 * gibt — und genau dort ist die Größeneinstellung stehengeblieben.
 *
 * `wecker: 'L'` ist Absicht: der Wecker kann laut Registry nur S · M. So ein
 * Wert steht in echten Dateien (eine frühere Registry erlaubte mehr), und er
 * ist der interessanteste Fall für die Stufen-Knöpfe.
 */
const ORDER_W7 = [
  { id: 'uhr', size: 'L' },
  { id: 'wecker', size: 'L' },
  { id: 'wetter', size: 'XL' },
  { id: 'laeuft', size: 'M' },
  { id: 'einkauf', size: 'M' },
  { id: 'vacuum', size: 'XL' },
  { id: 'climate', size: 'L' },
  { id: 'news', size: 'M' },
];

/** Zellen je Spaltenzahl — „quer und hoch merkt sich Hoshi getrennt". */
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

const SEED_KIND = process.env.PROBE_SEED === 'w7' ? 'w7' : 'v1';

const SEED = {
  'hoshi.settings': JSON.stringify({ theme: 'aoi', language: 'de', voice: 'coral' }),
  'hoshi.homeTiles.wetter': 'true',
  'hoshi.homeTiles.laeuft': 'true',
  'hoshi.homeTiles.einkauf': 'true',
  'hoshi.homeTiles.vacuum': 'true',
  'hoshi.homeTiles.climate': 'true',
  'hoshi.homeTiles.currentAffairs': 'true',
  'hoshi.homeTiles.layout': JSON.stringify(
    SEED_KIND === 'w7'
      ? { version: 1, order: ORDER_W7, placements: PLACEMENTS_W7 }
      : { version: 1, order: ORDER_V1 },
  ),
};

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

const profile = mkdtempSync(join(tmpdir(), 'hoshi-touch-'));
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
    `--remote-debugging-port=${DEBUG_PORT}`,
    `--user-data-dir=${profile}`,
    'about:blank',
  ],
  { stdio: 'ignore' },
);
const cleanup = () => child.kill('SIGKILL');
process.on('exit', cleanup);

/* ── winziger CDP-Client (Node-eigenes WebSocket), wie in shot.mjs ─────────── */
let version = null;
for (let i = 0; i < 100; i++) {
  try {
    version = await (await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/version`)).json();
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

const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
const cmd = (method, params) => send(method, params, sessionId);
const evalJs = async (expression) => {
  const res = await cmd('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (res.exceptionDetails) throw new Error(JSON.stringify(res.exceptionDetails).slice(0, 400));
  return res.result.value;
};

await cmd('Page.enable');
await cmd('Runtime.enable');
await cmd('Log.enable');
await cmd('Emulation.setDeviceMetricsOverride', {
  width: W,
  height: H,
  deviceScaleFactor: 1,
  // `mobile: true` nur für den Finger: der iPad meldet Touch-Punkte UND
  // rechnet das Viewport-Meta aus. Für die Maus bliebe das eine Lüge.
  mobile: INPUT === 'touch',
});
if (INPUT === 'touch') {
  await cmd('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
  await cmd('Emulation.setEmitTouchEventsForMouse', { enabled: false });
}
const seedJs = Object.entries(SEED)
  .map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`)
  .join('');
await cmd('Page.addScriptToEvaluateOnNewDocument', { source: seedJs });

const seen = events.length;
await cmd('Page.navigate', { url: BASE });
for (let i = 0; i < 200; i++) {
  if (events.slice(seen).some((e) => e.method === 'Page.loadEventFired')) break;
  await sleep(50);
}
for (let i = 0; i < 120; i++) {
  if (await evalJs(`document.querySelectorAll('.idle__tile').length > 0`)) break;
  await sleep(100);
}
await sleep(900);

/* ── Eingabe: EIN Weg pro Art, damit beide Läufe dieselbe Bahn fahren ─────── */

const TOUCH_ID = 1;
const point = (x, y) => [{ x: Math.round(x), y: Math.round(y), radiusX: 12, radiusY: 12, force: 1, id: TOUCH_ID }];

const down = (x, y) =>
  INPUT === 'touch'
    ? cmd('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: point(x, y) })
    : cmd('Input.dispatchMouseEvent', { type: 'mousePressed', x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1, buttons: 1 });
const move = (x, y) =>
  INPUT === 'touch'
    ? cmd('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: point(x, y) })
    : cmd('Input.dispatchMouseEvent', { type: 'mouseMoved', x: Math.round(x), y: Math.round(y), button: 'left', buttons: 1 });
const up = (x, y) =>
  INPUT === 'touch'
    ? cmd('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    : cmd('Input.dispatchMouseEvent', { type: 'mouseReleased', x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1, buttons: 0 });

/**
 * Ein Zug in `steps` Schritten. `holdMs` wartet NACH dem Aufsetzen und VOR der
 * ersten Bewegung — so entsteht aus demselben Weg ein Long-Press.
 */
async function drag(from, to, { steps = 14, holdMs = 0, stepMs = 16, settleMs = 500 } = {}) {
  await down(from.x, from.y);
  if (holdMs) await sleep(holdMs);
  for (let i = 1; i <= steps; i++) {
    const t = i / steps;
    await move(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t);
    await sleep(stepMs);
  }
  await up(to.x, to.y);
  await sleep(settleMs);
}

async function tap(x, y, { holdMs = 60 } = {}) {
  await down(x, y);
  await sleep(holdMs);
  await up(x, y);
  await sleep(400);
}

async function shot(name) {
  if (!SHOTS) return;
  mkdirSync(SHOTS, { recursive: true });
  const png = await cmd('Page.captureScreenshot', { format: 'png' });
  const out = join(SHOTS, `${TAG}-${name}-${W}x${H}.png`);
  writeFileSync(out, Buffer.from(png.data, 'base64'));
  console.log(`  Bild: ${out}`);
}

/* ── Messen ───────────────────────────────────────────────────────────────── */

const MEASURE = `(() => {
  const box = (el) => { if (!el) return null; const r = el.getBoundingClientRect();
    return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
  const pages = [...document.querySelectorAll('.idle__page')];
  const active = pages.findIndex((p) => p.dataset.active === 'true');
  const stage = document.querySelector('.idle__stage');
  const tiles = {};
  // NUR die Kacheln der SICHTBAREN Seite: die Nachbarseiten stehen links und
  // rechts daneben (x = −774 …), ein Finger käme dort nie hin.
  for (const t of (pages[active] || document).querySelectorAll('[data-widget-id]')) {
    const id = t.getAttribute('data-widget-id');
    if (id && !tiles[id]) tiles[id] = box(t);
  }
  // Wo liegt jede Kachel ÜBERHAUPT? („verschwunden" heißt sonst nur
  // „nicht auf dieser Seite", und das ist eine andere Aussage.)
  const wo = {};
  pages.forEach((p, index) => {
    for (const t of p.querySelectorAll('[data-widget-id]')) {
      const id = t.getAttribute('data-widget-id');
      if (id && wo[id] === undefined) wo[id] = index;
    }
  });
  const chain = (el) => { const out = []; let n = el;
    while (n && n.nodeType === 1 && out.length < 8) {
      out.push((n.className && typeof n.className === 'string' ? '.' + n.className.trim().split(/\\s+/)[0] : n.tagName)
        + ' touch-action=' + getComputedStyle(n).touchAction);
      n = n.parentElement; }
    return out; };
  const cx = Math.round(window.innerWidth / 2), cy = Math.round(window.innerHeight / 2);
  const hit = document.elementFromPoint(cx, cy);
  // Die SPALTEN der aktiven Seite — ohne sie ist ein waagerechter Zug nur eine
  // Vermutung in px. Mit ihnen wird er eine Aussage über ZELLEN.
  const spalten = pages[active]
    ? getComputedStyle(pages[active]).gridTemplateColumns.split(/\\s+/).map((v) => Math.round(parseFloat(v))).filter((v) => v > 0)
    : [];
  // Und die gespeicherte Wahrheit: was steht wirklich im Layout?
  let zellen = null;
  try { zellen = JSON.parse(localStorage.getItem('hoshi.homeTiles.layout') || '{}').placements ?? null; } catch (err) { zellen = 'LESEFEHLER'; }
  // Die GEZEICHNETEN Zellen — das, was ein Auge sieht. Weichen sie von den
  // gespeicherten ab, hat der Renderer korrigiert, ohne es zurueckzuschreiben;
  // jede weitere Rechnung liefe dann gegen ein Phantom.
  const zeilenProSeite = pages[0]
    ? getComputedStyle(pages[0]).gridTemplateRows.split(/\\s+/).filter((v) => parseFloat(v) > 0).length
    : 0;
  const gezeichnet = {};
  pages.forEach((p, index) => {
    for (const t of p.querySelectorAll('[data-widget-id]')) {
      const id = t.getAttribute('data-widget-id');
      const col = parseInt(getComputedStyle(t).gridColumnStart, 10) - 1;
      const row = parseInt(getComputedStyle(t).gridRowStart, 10) - 1;
      if (id && gezeichnet[id] === undefined && col >= 0 && row >= 0) {
        gezeichnet[id] = { col, row: row + index * zeilenProSeite };
      }
    }
  });
  return {
    spalten,
    zeilenProSeite,
    zellen,
    gezeichnet,
    viewport: { w: window.innerWidth, h: window.innerHeight },
    maxTouchPoints: navigator.maxTouchPoints,
    pageCount: pages.length,
    activePage: active,
    editing: stage ? stage.dataset.edit : null,
    stage: box(stage),
    pagesBox: box(document.querySelector('.idle__pages')),
    activePageBox: box(pages[active] || null),
    editbar: box(document.querySelector('.idle__editbar')),
    // Das Fach „Verfuegbar" ist am 22.08. gefallen (Andi: „unten ist auch eine
    // leiste. die soll weg."). Die Sonde fragt weiter danach — aber jetzt als
    // ANWESENHEITS-Frage: taucht es wieder auf, ist der Abriss zurueckgefallen.
    fach: box(document.querySelector('.idle__tray, [data-tray]')),
    editlayer: box(document.querySelector('.idle__editlayer')),
    sizer: box(document.querySelector('.idle__sizer')),
    tiles,
    wo,
    hitAtCentre: hit ? chain(hit)[0] : null,
    touchActionChainAtCentre: hit ? chain(hit) : [],
    docScrollY: Math.round(window.scrollY),
  };
})()`;

const measure = () => evalJs(MEASURE);

/**
 * **Fliegt im Handler eine Ausnahme?** Ohne diesen Mitschnitt sieht ein Klick,
 * der nichts tut, genauso aus wie ein Klick, der abstürzt — und beides fühlt
 * sich für Andi identisch an („es passiert nichts").
 */
const errorsSince = (mark) =>
  events
    .slice(mark)
    .filter((e) => e.method === 'Runtime.exceptionThrown' || e.method === 'Log.entryAdded')
    // **404 der Fake-API ist kein Produktfehler.** `serve.mjs` modelliert
    // bewusst nur die Endpunkte, die dieser Reiter braucht; alles andere
    // antwortet 404, und das FE behandelt es korrekt als „nicht erreichbar".
    // Ein Poller, der zufällig während des gemessenen Knopfdrucks feuert,
    // färbte den Schritt sonst rot — für etwas, das die Sonde selbst gebaut
    // hat. Gemeldet werden sie trotzdem, nur nicht mehr gewertet.
    .filter(
      (e) =>
        e.method !== 'Log.entryAdded' ||
        !/Failed to load resource.*404/.test(e.params?.entry?.text ?? ''),
    )
    .map((e) =>
      e.method === 'Runtime.exceptionThrown'
        ? `EXCEPTION ${e.params?.exceptionDetails?.exception?.description ?? e.params?.exceptionDetails?.text ?? '?'}`.slice(0, 300)
        : `LOG ${e.params?.entry?.level}: ${e.params?.entry?.text}`.slice(0, 300),
    );

/** Die `touch-action`-Kette GENAU unter diesem Punkt — die Frage, ob der
 *  Browser an dieser Stelle scrollen darf (und uns die Geste wegnimmt). */
const chainAt = (x, y) =>
  evalJs(`(() => {
    let n = document.elementFromPoint(${Math.round(x)}, ${Math.round(y)});
    const out = [];
    while (n && n.nodeType === 1 && out.length < 8) {
      const id = n.getAttribute && n.getAttribute('data-widget-id');
      const key = (id ? '#' + id : '') + (typeof n.className === 'string' && n.className.trim()
        ? '.' + n.className.trim().split(/\\s+/).slice(-1)[0] : n.tagName);
      out.push(key + ' touch-action=' + getComputedStyle(n).touchAction
        + (n.hasAttribute && n.hasAttribute('inert') ? ' INERT' : ''));
      n = n.parentElement;
    }
    return out;
  })()`);

/**
 * **Der Mitschnitt** — welche Ereignisse kommen an der Bühne überhaupt an?
 *
 * Das ist die Frage, an der sich „Maus-only-Handler" von „der Browser nimmt
 * uns die Geste weg" trennt: bleibt `pointerdown` allein und folgt ein
 * `pointercancel`, hat der Browser gescrollt statt uns zu bedienen.
 */
const traceStart = () =>
  evalJs(`(() => {
    const el = document.querySelector('.idle__stage');
    if (!el) return false;
    window.__probeLog = [];
    window.__probeOff?.();
    const kinds = ['pointerdown','pointermove','pointerup','pointercancel','lostpointercapture',
                   'touchstart','touchmove','touchend','touchcancel','click','scroll'];
    const name = (n) => !n || n.nodeType !== 1 ? String(n)
      : (n.getAttribute?.('data-widget-id') ? '#' + n.getAttribute('data-widget-id') : '')
        + (typeof n.className === 'string' && n.className.trim() ? '.' + n.className.trim().split(/\\s+/).slice(-1)[0] : n.tagName);
    // Gleiche Nachbarn zusammenfassen — interessant ist die REIHENFOLGE, nicht die Zahl.
    const push = (entry) => {
      const log = window.__probeLog;
      const last = log[log.length - 1];
      if (last && last.startsWith(entry)) {
        log[log.length - 1] = entry + ' x' + (Number(last.slice(entry.length + 2) || 1) + 1);
      } else log.push(entry);
    };
    const on = (e) => {
      push(e.type + ' @' + name(e.target));
      // Beim Abbruch die Frage beantworten, ob etwas GESCROLLT hat: nur dann
      // hat der Browser die Geste uebernommen.
      if (e.type === 'pointercancel') {
        const t = e.target.closest ? e.target.closest('[data-widget-id]') : null;
        const vv = window.visualViewport;
        push('beim-Abbruch scrollY=' + Math.round(window.scrollY)
          + ' docTop=' + Math.round(document.scrollingElement.scrollTop)
          + ' kachelTop=' + (t ? Math.round(t.scrollTop) : '-')
          + ' vvScale=' + (vv ? vv.scale : '-') + ' vvTop=' + (vv ? Math.round(vv.offsetTop) : '-'));
      }
    };
    for (const k of kinds) el.addEventListener(k, on, true);
    window.addEventListener('scroll', on, true);
    // Was passiert dem DOM unter dem Finger? Zwei Dinge nehmen dem Browser den
    // Zeiger weg und sind von aussen nicht zu sehen: die Kachel wird entfernt,
    // oder sie wird (bzw. ihr Kind) inert. Ohne diesen Beobachter sieht man nur
    // die Folge (pointercancel), nie die Ursache.
    const mo = new MutationObserver((recs) => {
      for (const r of recs) {
        if (r.type === 'attributes') { push('inert-neu-gehaengt'); continue; }
        for (const n of r.removedNodes) {
          if (n.nodeType !== 1) continue;
          const hit = n.getAttribute?.('data-widget-id') || n.querySelector?.('[data-widget-id]')?.getAttribute('data-widget-id');
          push('DOM-entfernt ' + (hit ? '#' + hit : name(n)));
        }
      }
    });
    mo.observe(document.querySelector('.idle__pages') || el,
      { childList: true, subtree: true, attributes: true, attributeFilter: ['inert'] });
    window.__probeOff = () => { for (const k of kinds) el.removeEventListener(k, on, true);
                                window.removeEventListener('scroll', on, true); mo.disconnect(); };
    return true;
  })()`);

/** Die Ereignis-KETTE in ihrer Reihenfolge (gleiche Nachbarn zusammengefasst). */
const traceRead = () =>
  evalJs(`(() => {
    const out = (window.__probeLog || []).slice(0, 40);
    window.__probeOff?.(); window.__probeOff = null; return out;
  })()`);

/** Wie viele px der Bühnen-Fläche liegen unter der Leiste? */
function overlap(a, b) {
  if (!a || !b) return 0;
  const w = Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x));
  const h = Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y));
  return w * h;
}

const report = { input: INPUT, viewport: `${W}x${H}`, steps: [] };
const say = (title, data) => {
  report.steps.push({ title, ...data });
  console.log(`\n── ${title}`);
  for (const [k, v] of Object.entries(data)) console.log(`   ${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`);
};

/* Schritt 0 — Ausgangslage */
const start = await measure();
say('0 · Ausgangslage', {
  viewport: start.viewport,
  maxTouchPoints: start.maxTouchPoints,
  pageCount: start.pageCount,
  activePage: start.activePage,
  hitAtCentre: start.hitAtCentre,
  touchActionChainAtCentre: start.touchActionChainAtCentre,
});
await shot('0-start');

/* Schritt 1 — waagerechter Wisch nach links (Seite +1) */
{
  // Die SICHTBARE Seite ist die Bahn — `.idle__pages` ist die ganze Schiene
  // (alle Seiten nebeneinander), ihr Rechteck liegt grösstenteils ausserhalb.
  const s = start.activePageBox ?? start.stage;
  const y = Math.round(s.y + s.h / 2);
  await traceStart();
  await drag({ x: Math.round(s.x + s.w * 0.8), y }, { x: Math.round(s.x + s.w * 0.2), y });
  const seenEvents = await traceRead();
  const after = await measure();
  say('1 · Wisch nach links (Seite +1)', {
    pageVorher: start.activePage,
    pageNachher: after.activePage,
    ereignisse: seenEvents,
    ok: after.activePage === start.activePage + 1,
  });
  await shot('1-swipe-links');
}

/* Schritt 2 — waagerechter Wisch zurück nach rechts */
{
  const before = await measure();
  const s = before.activePageBox ?? before.stage;
  const y = Math.round(s.y + s.h / 2);
  await traceStart();
  await drag({ x: Math.round(s.x + s.w * 0.2), y }, { x: Math.round(s.x + s.w * 0.8), y });
  const seenEvents = await traceRead();
  const after = await measure();
  say('2 · Wisch nach rechts (Seite −1)', {
    pageVorher: before.activePage,
    pageNachher: after.activePage,
    ereignisse: seenEvents,
    ok: after.activePage === before.activePage - 1,
  });
  await shot('2-swipe-rechts');
}

/* Schritt 3 — Long-Press öffnet den Edit-Modus */
{
  const before = await measure();
  const ids = Object.keys(before.tiles);
  const first = before.tiles[ids[0]];
  await traceStart();
  await down(first.x + first.w / 2, first.y + first.h / 2);
  await sleep(900);
  await up(first.x + first.w / 2, first.y + first.h / 2);
  await sleep(600);
  const seenEvents = await traceRead();
  const after = await measure();
  say('3 · Long-Press → Edit-Modus', {
    kachel: ids[0],
    editVorher: before.editing,
    editNachher: after.editing,
    sizerOffen: !!after.sizer,
    ereignisse: seenEvents,
    ok: after.editing === 'true',
  });
  await shot('3-edit-an');
}

/* Schritt 4 — Überlagerung messen (Fläche vs. Leiste) + ist das Fach weg? */
{
  const m = await measure();
  const area = m.activePageBox ? m.activePageBox.w * m.activePageBox.h : 0;
  const barPx = overlap(m.activePageBox, m.editbar);
  const trayPx = overlap(m.activePageBox, m.fach);
  say('4 · Überlagerung der Bearbeitungsfläche', {
    buehne: m.stage,
    schicht: m.editlayer,
    flaeche: m.activePageBox,
    leiste: m.editbar,
    fachVorhanden: !!m.fach,
    leisteUeberlappungPx2: barPx,
    fachUeberlappungPx2: trayPx,
    anteilProzent: area ? Math.round(((barPx + trayPx) / area) * 1000) / 10 : 0,
    // Die Leiste darf die Flaeche nicht decken — und unter der Buehne darf
    // ueberhaupt nichts mehr stehen.
    ok: barPx === 0 && trayPx === 0 && !m.fach,
  });
  await shot('4-overlays');
}

/* Schritt 5 — Kachel im Edit-Modus ziehen */
{
  const before = await measure();
  const ids = Object.keys(before.tiles);
  const from = before.tiles[ids[0]];
  const mid = (t) => ({ x: t.x + t.w / 2, y: t.y + t.h / 2 });
  const start = mid(from);
  /*
   * Das ZIEL muss weit genug weg sein, sonst prüft dieser Schritt nichts.
   * Zwei fast deckungsgleiche Kachelmitten (quer auf dem iPad passiert das)
   * machen aus dem Zug einen Tipp — und ein Tipp auf die ausgewählte Kachel
   * verlässt den Edit-Modus (W6). Die Kachel steht danach woanders, „ok" wäre
   * grün, und gemessen worden wäre das Gegenteil des Bestellten.
   */
  const far = ids
    .map((id) => ({ id, p: mid(before.tiles[id]) }))
    .map((c) => ({ ...c, d: Math.hypot(c.p.x - start.x, c.p.y - start.y) }))
    .sort((a, b) => b.d - a.d)[0];
  const page = before.activePageBox;
  const target =
    far && far.d >= 80
      ? far.p
      : { x: start.x, y: start.y + (page ? Math.round(page.h * 0.4) : 200) };
  const chainAtGrab = await chainAt(start.x, start.y);
  await traceStart();
  await drag(start, target, { steps: 20, stepMs: 24, settleMs: 800 });
  const seenEvents = await traceRead();
  const after = await measure();
  const a = before.tiles[ids[0]];
  const b = after.tiles[ids[0]];
  say('5 · Finger-Zug einer Kachel', {
    kachel: ids[0],
    vorher: a,
    nachher: b,
    griffKette: chainAtGrab,
    ereignisse: seenEvents,
    versatzPx: b ? { dx: b.x - a.x, dy: b.y - a.y } : null,
    woVorher: before.wo[ids[0]],
    woNachher: after.wo[ids[0]],
    // Der Edit-Modus muss den Zug ÜBERLEBEN: endet er, war es ein Tipp, und
    // die Kachel steht nur deshalb woanders, weil die Bühne zurückgesprungen
    // ist. Ein Zug, der den Modus verlässt, ist kein Zug.
    editNachher: after.editing,
    ok:
      after.editing === 'true' &&
      // Entweder sie liegt woanders auf DIESER Seite — oder gleich auf einer
      // anderen Seite bzw. im Fach. Alles drei ist ein gelungener Zug.
      (before.wo[ids[0]] !== after.wo[ids[0]] ||
        (!!b && (Math.abs(b.x - a.x) > 8 || Math.abs(b.y - a.y) > 8))),
  });
  await shot('5-drag');
}

/* ── Schritt 5b — die GRÖSSENEINSTELLUNG, wirklich gedrückt ────────────────
 *
 * Andi (22.08. nachts, live): *„Die Größen einstellung ist komplett kaputt. es
 * passiert nichts, wenn ich auf plus oder minus klicke."*
 *
 * Bis hierher hat die Sonde nur gemeldet, dass der Wähler AUFGEHT
 * (`sizerOffen`) — das ist kein Beweis, dass seine Knöpfe etwas tun. Hier wird
 * jetzt wirklich gedrückt: Stufe vorher, `disabled`-Zustand beider Knöpfe,
 * Stufe nachher, und alles, was dabei an Ausnahmen fliegt.
 */
{
  const before = await measure();
  const ids = Object.keys(before.tiles);
  // Es muss eine Kachel mit offenem Wähler geben — die aus Schritt 5.
  let sizer = await evalJs(`(() => {
    const s = document.querySelector('.idle__sizer');
    if (!s) return null;
    const tile = s.closest('.idle__page')?.querySelector('[data-widget-id]');
    const btns = [...s.querySelectorAll('.idle__sizerbtn')].map((b) => ({
      dir: b.getAttribute('data-dir'), disabled: b.disabled,
      x: Math.round(b.getBoundingClientRect().x + b.getBoundingClientRect().width / 2),
      y: Math.round(b.getBoundingClientRect().y + b.getBoundingClientRect().height / 2),
    }));
    return { aria: s.getAttribute('aria-label'), btns };
  })()`);

  // Kein offener Wähler? Dann einen aufmachen: im Edit öffnet ein Tipp auf
  // eine Kachel ihren Wähler.
  if (!sizer && ids.length > 0) {
    const t = before.tiles[ids[0]];
    await tap(t.x + t.w / 2, t.y + t.h / 2);
    sizer = await evalJs(`(() => {
      const s = document.querySelector('.idle__sizer');
      if (!s) return null;
      const btns = [...s.querySelectorAll('.idle__sizerbtn')].map((b) => ({
        dir: b.getAttribute('data-dir'), disabled: b.disabled,
        x: Math.round(b.getBoundingClientRect().x + b.getBoundingClientRect().width / 2),
        y: Math.round(b.getBoundingClientRect().y + b.getBoundingClientRect().height / 2),
      }));
      return { aria: s.getAttribute('aria-label'), btns };
    })()`);
  }

  if (!sizer) {
    say('5b · Stufen-Wähler: + und − wirklich drücken', {
      hinweis: 'kein Wähler offen — hier ist nichts zu drücken',
      ok: null,
    });
  } else {
    /** Was steht im SPEICHER? Das ist die Zahl, die der Knopf ändern soll. */
    const stored = () =>
      evalJs(`(() => {
        try {
          const raw = JSON.parse(localStorage.getItem('hoshi.homeTiles.layout') || '{}');
          const out = {};
          for (const e of raw.order || []) out[e.id] = e.size;
          return JSON.stringify(out);
        } catch (err) { return 'LESEFEHLER ' + err; }
      })()`);

    const which = await evalJs(`(() => {
      const s = document.querySelector('.idle__sizer');
      const tile = s && s.parentElement
        ? [...s.parentElement.querySelectorAll('[data-widget-id]')].map((t) => t.getAttribute('data-widget-id'))
        : [];
      return JSON.stringify(tile);
    })()`);

    const vorher = JSON.parse(await stored());
    const upBtn = sizer.btns.find((b) => b.dir === 'up');
    const downBtn = sizer.btns.find((b) => b.dir === 'down');

    // ECHT drücken — mit dem Finger bzw. der Maus, nicht per `.click()`.
    const mark = events.length;
    const press = upBtn && !upBtn.disabled ? upBtn : downBtn && !downBtn.disabled ? downBtn : null;
    /*
     * **Was liegt an dieser Stelle wirklich obenauf?** Ein Rechteck aus
     * `getBoundingClientRect` sagt nur, WO der Knopf ist — nicht, ob ihn
     * jemand zudeckt. Genau das ist von aussen unsichtbar und fuehlt sich fuer
     * eine Hand exakt wie „der Knopf tut nichts" an.
     */
    const obenauf = press
      ? await evalJs(`(() => {
          const el = document.elementFromPoint(${press.x}, ${press.y});
          if (!el) return 'NICHTS';
          const name = (n) => (typeof n.className === 'string' && n.className.trim()
            ? '.' + n.className.trim().split(/\\s+/).join('.') : n.tagName);
          const btn = el.closest('.idle__sizerbtn');
          return (btn ? 'KNOPF ' : 'VERDECKT VON ') + name(el)
            + ' | Kette: ' + [el, el.parentElement, el.parentElement?.parentElement]
                .filter(Boolean).map(name).join(' < ');
        })()`)
      : 'kein Knopf zu druecken';
    if (press) await tap(press.x, press.y);
    const fehler = errorsSince(mark);
    const nachher = JSON.parse(await stored());

    const geaendert = Object.keys(vorher).filter((k) => vorher[k] !== nachher[k]);
    say('5b · Stufen-Wähler: + und − wirklich drücken', {
      saat: SEED_KIND,
      waehlerFuer: sizer.aria,
      kachelnImRaster: which,
      knoepfe: sizer.btns.map((b) => `${b.dir}${b.disabled ? ' DISABLED' : ''}`),
      gedrueckt: press ? press.dir : 'KEINER (beide disabled)',
      obenaufAmKnopf: obenauf,
      stufenVorher: vorher,
      stufenNachher: nachher,
      geaendert,
      fehler,
      // Ein Knopf, der etwas tun soll, muss eine Stufe im Speicher bewegen.
      ok: !!press && geaendert.length === 1 && fehler.length === 0,
    });
    await shot('5b-sizer');
  }
}

/* ── Schritt 5c — der WAAGERECHTE Zug (Nachtrag 6, Andi-Livetest 23.08.) ───
 *
 * Andi wörtlich: *„ich konnte die widgets nicht verschieben, wenn ich sie nach
 * links und rechts geschoben habe."*
 *
 * Und er hat recht damit, dass die Sonde das nie gesehen hat: Schritt 5 sucht
 * sich die WEITEST entfernte Kachelmitte, und die liegt auf einer hohen Seite
 * immer untendrunter — gemessen wurde `dx=1, dy=318`. Ein blinder Fleck von
 * der Größe einer ganzen Achse.
 *
 * Hier wird die Kachel deshalb ausdrücklich in eine NACHBARSPALTE gezogen,
 * nach rechts und nach links, und dabei die Frage gestellt, die den Verdacht
 * der Hand entscheidet: **bleibt die Seite stehen?** Blättert sie, hat der
 * Zug gegen den Seitenwechsel verloren — genau das, was Andi beschreibt.
 */
{
  const GAP = 12;

  /** Edit-Modus sicherstellen (ein Tipp davor kann ihn geschlossen haben). */
  const sorgeFuerEdit = async () => {
    let m = await measure();
    if (m.editing === 'true') return m;
    const t = m.tiles[Object.keys(m.tiles)[0]];
    await down(t.x + t.w / 2, t.y + t.h / 2);
    await sleep(900);
    await up(t.x + t.w / 2, t.y + t.h / 2);
    await sleep(600);
    return measure();
  };

  /**
   * **Ein Griffpunkt, an dem wirklich die KACHEL liegt.**
   *
   * Die Mitte einer Kachel ist kein sicherer Griff: der Größen-Wähler steht
   * mitten darauf, und ein Zug, der auf ihm beginnt, ist Bedienung statt Zug
   * (`onEditChrome`). Genau das hat der erste Lauf dieses Schrittes gemessen —
   * `pointerdown @.idle__sizerrow`, `versatzPx dx=0`. Ein rotes Ergebnis, das
   * über den waagerechten Zug gar nichts ausgesagt hätte.
   */
  const griffIn = (id, t) =>
    evalJs(`(() => {
      const kandidaten = [
        [${t.x + t.w / 2}, ${t.y + t.h / 2}],
        [${t.x + t.w / 2}, ${t.y + t.h * 0.22}],
        [${t.x + t.w / 2}, ${t.y + t.h * 0.78}],
        [${t.x + t.w * 0.25}, ${t.y + t.h * 0.5}],
        [${t.x + t.w * 0.75}, ${t.y + t.h * 0.5}],
      ];
      for (const [x, y] of kandidaten) {
        const el = document.elementFromPoint(Math.round(x), Math.round(y));
        const tile = el && el.closest ? el.closest('[data-widget-id]') : null;
        if (tile && tile.getAttribute('data-widget-id') === ${JSON.stringify(id)}) {
          return JSON.stringify({ x: Math.round(x), y: Math.round(y) });
        }
      }
      return null;
    })()`);

  /**
   * EIN waagerechter Zug: `k` eine Spalte weiter, `bevorzugt` sagt wohin zuerst.
   * Gibt eine ZEILE zurück statt zu drucken — der Schritt fasst sie zusammen.
   */
  const einZug = async (m, k, griff, bevorzugt) => {
    const page = m.activePageBox;
    const cols = m.spalten ?? [];
    const colW = cols[0] ?? 0;
    const colMid = (i) => page.x + i * (colW + GAP) + colW / 2;
    const passt = (c) => c >= 0 && c + k.spanCols <= cols.length && c !== k.col;
    const zielCol = passt(k.col + bevorzugt)
      ? k.col + bevorzugt
      : passt(k.col - bevorzugt)
        ? k.col - bevorzugt
        : null;
    if (zielCol === null) {
      return {
        kachel: k.id,
        spanSpalten: k.spanCols,
        spalteVorher: k.col,
        hinweis:
          k.spanCols >= cols.length
            ? 'volle Breite — es gibt keine zweite Spalte, in die sie passt'
            : 'keine Nachbarspalte frei',
        ok: null,
      };
    }
    const von = { x: griff.x, y: griff.y };
    // Der FINGER benennt die Zielzelle (`homeDropCell` rechnet aus seiner
    // Position) — also zielt er auf die Mitte der Wunschspalte.
    const nach = { x: Math.round(colMid(zielCol)), y: griff.y };
    await traceStart();
    await drag(von, nach, { steps: 20, stepMs: 24, settleMs: 800 });
    const ereignisse = await traceRead();
    const danach = await measure();
    const b = danach.tiles[k.id];
    return {
      kachel: k.id,
      spanSpalten: k.spanCols,
      richtung: zielCol > k.col ? 'rechts' : 'links',
      spalteVorher: k.col,
      spalteZiel: zielCol,
      spalteNachher: b
        ? Math.max(0, Math.round((b.x - (danach.activePageBox?.x ?? page.x)) / (colW + GAP)))
        : null,
      bahn: `${von.x},${von.y} → ${nach.x},${nach.y}`,
      dx: b ? b.x - k.t.x : null,
      dy: b ? b.y - k.t.y : null,
      zeilenProSeite: m.zeilenProSeite,
      gespeichertVorher: m.zellen?.[String(cols.length)]?.[k.id] ?? null,
      gezeichnetVorher: m.gezeichnet?.[k.id] ?? null,
      gespeichertNachher: danach.zellen?.[String(cols.length)]?.[k.id] ?? null,
      gezeichnetNachher: danach.gezeichnet?.[k.id] ?? null,
      seiteVorher: m.activePage,
      seiteNachher: danach.activePage,
      editNachher: danach.editing,
      ereignisse: ereignisse.slice(0, 6),
      // Ein waagerechter Zug im Edit muss die KACHEL versetzen und die SEITE
      // stehen lassen. Blättert die Bühne, hat der Seitenwechsel gewonnen.
      ok:
        danach.editing === 'true' &&
        danach.activePage === m.activePage &&
        !!b &&
        Math.abs(b.x - k.t.x) > colW / 2,
    };
  };

  /** Alle greifbaren Kacheln der Seite, von schmal nach breit. */
  const greifbare = async (m) => {
    const page = m.activePageBox;
    const cols = m.spalten ?? [];
    const colW = cols[0] ?? 0;
    const out = [];
    for (const [id, t] of Object.entries(m.tiles)) {
      const k = {
        id,
        t,
        spanCols: Math.max(1, Math.round((t.w + GAP) / (colW + GAP))),
        col: Math.max(0, Math.round((t.x - page.x) / (colW + GAP))),
      };
      const g = await griffIn(id, t);
      if (g) out.push({ ...k, griff: JSON.parse(g) });
    }
    return out.sort((a, b) => a.spanCols - b.spanCols);
  };

  /* ── 5c: jede Kachelbreite einmal waagerecht ziehen ─────────────────────── */
  {
    const m0 = await sorgeFuerEdit();
    if ((m0.spalten ?? []).length < 2) {
      say('5c · Waagerechter Zug im Edit', {
        spalten: m0.spalten,
        hinweis: 'weniger als zwei Spalten — waagerecht gibt es nichts zu ziehen',
        ok: null,
      });
    } else {
      const zeilen = [];
      const gesehen = new Set();
      for (let runde = 0; runde < 6; runde++) {
        const m = await sorgeFuerEdit();
        const frei = (await greifbare(m)).filter((k) => !gesehen.has(k.id));
        if (frei.length === 0) break;
        // Je Breite EINE Kachel — der Verdacht der Hand hängt an der Breite,
        // nicht am Namen.
        const k = frei.find((c) => !zeilen.some((z) => z.spanSpalten === c.spanCols)) ?? frei[0];
        gesehen.add(k.id);
        zeilen.push(await einZug(m, k, k.griff, runde % 2 === 0 ? 1 : -1));
      }
      const geprueft = zeilen.filter((z) => z.ok !== null);
      say('5c · Waagerechter Zug im Edit (jede Kachelbreite)', {
        spalten: m0.spalten,
        zuege: zeilen,
        rot: geprueft.filter((z) => !z.ok).map((z) => `${z.kachel} (${z.spanSpalten} Spalten)`),
        ok: geprueft.length > 0 && geprueft.every((z) => z.ok),
      });
      await shot('5c-zug-waagerecht');
    }
  }

  /* ── 5d: der Zug an den RAND — gewinnt der Seitenwechsel? ───────────────── */
  {
    const m = await sorgeFuerEdit();
    const page = m.activePageBox;
    const cols = m.spalten ?? [];
    const colW = cols[0] ?? 0;
    const frei = (await greifbare(m)).filter((k) => k.spanCols < cols.length);
    if (!page || frei.length === 0) {
      say('5d · Zug bis an den Seitenrand', { hinweis: 'keine schmale Kachel greifbar', ok: null });
    } else {
      const k = frei[0];
      // Genau in die Zone, in der der Auto-Pager (`HOME_AUTOPAGE_EDGE_PX` = 56)
      // wacht — und dort so lange verweilen, wie eine Hand nun einmal braucht.
      const ziel = k.col === 0 ? page.x + page.w - 20 : page.x + 20;
      await traceStart();
      await down(k.griff.x, k.griff.y);
      for (let i = 1; i <= 20; i++) {
        await move(k.griff.x + ((ziel - k.griff.x) * i) / 20, k.griff.y);
        await sleep(24);
      }
      await sleep(900); // verweilen: der Auto-Pager braucht 500 ms
      await up(ziel, k.griff.y);
      await sleep(800);
      const ereignisse = await traceRead();
      const danach = await measure();
      const b = danach.tiles[k.id];
      say('5d · Zug bis an den Seitenrand', {
        kachel: k.id,
        spanSpalten: k.spanCols,
        bahn: `${k.griff.x},${k.griff.y} → ${Math.round(ziel)},${k.griff.y}`,
        randzonePx: 56,
        verweiltMs: 900,
        vorher: k.t,
        nachher: b ?? null,
        dx: b ? b.x - k.t.x : null,
        woVorher: m.wo[k.id],
        woNachher: danach.wo[k.id],
        seiteVorher: m.activePage,
        seiteNachher: danach.activePage,
        editNachher: danach.editing,
        ereignisse: ereignisse.slice(0, 6),
        // Am Rand DARF geblättert werden (Auto-Pager, §2.5) — verlangt ist
        // nur, dass die Kachel dabei MITKOMMT statt liegenzubleiben.
        ok:
          danach.editing === 'true' &&
          (danach.activePage === m.activePage
            ? !!b && Math.abs(b.x - k.t.x) > colW / 2
            : danach.wo[k.id] === danach.activePage),
      });
      await shot('5d-zug-rand');
    }
  }
}

/* ── Schritt 6 — der Finger auf einem LINK (Nachrichten-Kachel) ────────────
 *
 * Die Nachrichten-Kachel besteht fast nur aus Links. Ein Zeiger, der auf einem
 * `a[href]` aufsetzt, gehört bisher dem Link (`INTERACTIVE_DESCENDANTS`) —
 * am Laptop ist das folgenlos, weil der Rechtsklick der zweite Weg in den
 * Wähler ist. Auf Glas gibt es keinen Rechtsklick. Gefragt sind beide Hälften:
 * ein kurzer Tipp MUSS der Link bleiben, ein langer Druck MUSS in den
 * Edit-Modus führen.
 */
{
  // Erst raus aus dem Edit-Modus (Schritt 5 hat ihn angelassen). Der Weg dahin
  // war bis zum 23.08. ein Klick auf „Fertig"; die Leiste ist gefallen (Andi:
  // „nimm die UI oben … raus"), also nimmt die Sonde denselben Ausgang wie eine
  // Tastatur: Escape. Zweimal, weil der erste Druck den Stufen-Wähler schließt.
  // (Ein `?.click()` auf einen Knopf, den es nicht mehr gibt, tat NICHTS — und
  // der ganze Schritt maß danach einen Edit-Modus statt eines Links.)
  await evalJs(`(() => {
    for (let i = 0; i < 2; i++) {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    }
    return document.querySelector('.idle__stage')?.dataset.edit ?? 'KEINE BUEHNE';
  })()`);
  await sleep(600);

  // Die Nachrichten-Kachel liegt selten auf Seite 1 — über die Seitenpunkte
  // dorthin blättern (das ist der Tastatur-/Klick-Weg, nicht der Wisch: hier
  // wird der Link geprüft, nicht das Blättern).
  await evalJs(`(() => {
    const pages = [...document.querySelectorAll('.idle__page')];
    const at = pages.findIndex((p) => p.querySelector('[data-widget-id] a[href]'));
    if (at < 0) return false;
    document.querySelectorAll('.idle__dot')[at]?.click();
    return true;
  })()`);
  await sleep(700);

  const linkBox = await evalJs(`(() => {
    // Erst ein echter Link (Nachrichten-Kachel), sonst irgendein anderer
    // interaktiver Nachfahre auf einer PLATZIERTEN Kachel: der Riegel
    // INTERACTIVE_DESCENDANTS ist fuer beide derselbe Code-Pfad, und der
    // Sauger-Knopf haengt an der Buehne, wo die Nachrichten-Kachel es (noch)
    // nicht tut.
    const page = document.querySelector('.idle__page[data-active="true"]');
    const a = page && (page.querySelector('[data-widget-id] a[href]')
      || page.querySelector('[data-widget-id] button'));
    if (!a) return null;
    const r = a.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return null;
    // Der Klick wird MITGESCHRIEBEN statt ausgeführt: sonst navigiert die
    // Sonde weg und misst danach eine fremde Seite.
    window.__linkHits = [];
    window.__linkOff?.();
    window.__linkEl = a;
    // WO der Klick landet, ist die halbe Antwort: der nachlaufende Klick eines
    // Long-Press trifft nicht die Stelle, auf der der Finger lag, sondern das,
    // was nach dem Umbau dort liegt (die Edit-Leiste). Wer nur zaehlt, sieht
    // eine 1 und haelt sie fuer einen Link-Klick.
    const on = (e) => {
      window.__linkHits.push({
        durchgelassen: !e.defaultPrevented,
        amZiel: e.target === window.__linkEl || (window.__linkEl && window.__linkEl.contains(e.target)),
        ziel: (e.target.className && typeof e.target.className === 'string'
          ? '.' + e.target.className.trim().split(/\\s+/).slice(-1)[0] : e.target.tagName),
      });
      e.preventDefault();
    };
    document.addEventListener('click', on, false);
    window.__linkOff = () => document.removeEventListener('click', on, false);
    return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height),
             text: (a.textContent || '').trim().slice(0, 40),
             // Auf WELCHER Kachel liegt dieser Link überhaupt? Ohne diese Zeile
             // sieht man nur, dass nichts passiert, nie warum.
             kachel: a.closest('[data-widget-id]')?.getAttribute('data-widget-id') ?? 'KEINE' };
  })()`);

  if (!linkBox) {
    const da = await evalJs(`JSON.stringify({
      widgets: [...document.querySelectorAll('.idle__pages [data-widget-id]')].map((t) => t.getAttribute('data-widget-id')),
      linksImDom: document.querySelectorAll('.idle__pages a[href]').length,
      newsKachel: !!document.querySelector('.idle__news'),
    })`);
    say('6 · Finger auf einem interaktiven Nachfahren', { hinweis: 'kein sichtbarer Link gefunden', gefunden: da, ok: null });
  } else {
    const p = { x: linkBox.x + linkBox.w / 2, y: linkBox.y + linkBox.h / 2 };

    // (a) Kurzer Tipp — der Link muss ihn bekommen.
    await tap(p.x, p.y, { holdMs: 60 });
    const afterTap = await measure();
    const tapHits = await evalJs(`JSON.stringify(window.__linkHits)`);

    // (b) Langer Druck — er muss in den Edit-Modus führen.
    await evalJs(`window.__linkHits = []`);
    await traceStart();
    await down(p.x, p.y);
    await sleep(900);
    await up(p.x, p.y);
    await sleep(600);
    const seenEvents = await traceRead();
    const afterHold = await measure();
    const holdHits = await evalJs(`JSON.stringify(window.__linkHits)`);
    await evalJs(`window.__linkOff?.()`);

    const tapClicks = JSON.parse(tapHits);
    const holdClicks = JSON.parse(holdHits);
    /** Klicks, die beim ZIEL ankamen (nicht bloss irgendwo gefeuert wurden). */
    const tapAtTarget = tapClicks.filter((c) => c.amZiel && c.durchgelassen).length;
    /** Klicks, die der lange Druck IRGENDWO hat durchkommen lassen. */
    const holdLetThrough = holdClicks.filter((c) => c.durchgelassen).length;

    say('6 · Finger auf einem interaktiven Nachfahren (Link/Knopf auf einer Kachel)', {
      link: linkBox.text,
      // `KEINE` heißt: dieser Link liegt in einer Kachel, die die Bühne gar
      // nicht platziert hat (sie reicht das injizierte `data-widget-id` nicht
      // an ihr Wurzelelement durch). Dann ist der Long-Press hier nicht
      // messbar — nicht kaputt, sondern nicht angeschlossen.
      aufKachel: linkBox.kachel,
      tippKlicks: tapClicks,
      tippEdit: afterTap.editing,
      druckKlicks: holdClicks,
      druckEdit: afterHold.editing,
      ereignisse: seenEvents,
      erwartung: INPUT === 'mouse' ? 'Riegel bleibt (Rechtsklick ist der zweite Weg)' : 'Tipp = Link, langer Druck = Edit',
      // Zwei verschiedene Zusagen, je nach Gerät:
      //  Glas — der Tipp bleibt beim Ziel; der lange Druck wird zum Edit und
      //         lässt KEINEN Klick durch. Wichtig ist „durchgelassen", nicht
      //         „gefeuert": der nachlaufende Klick entsteht immer, er darf nur
      //         nirgends ankommen — auch nicht auf der Leiste, die sich
      //         inzwischen über die Stelle geschoben hat.
      //  Maus — der Riegel bleibt: der lange Druck ändert nichts, und der
      //         Klick kommt in beiden Fällen an. Auf dem Desktop führt der
      //         Rechtsklick in den Wähler, dort fehlt nichts.
      // `null` (statt „rot"), solange die Kachel gar nicht an der Bühne hängt:
      // dann misst dieser Schritt nichts, und das ehrlich zu sagen ist besser
      // als ein Urteil.
      ok:
        linkBox.kachel === 'KEINE'
          ? null
          : INPUT === 'mouse'
            ? tapAtTarget === 1 && afterTap.editing === 'false' && afterHold.editing === 'false'
            : tapAtTarget === 1 &&
              afterTap.editing === 'false' &&
              afterHold.editing === 'true' &&
              holdLetThrough === 0,
    });
    await shot('6-link');
  }
}

/* ── Schritt 7 — DIE DREI AUSGÄNGE, einzeln (23.08.) ───────────────────────
 *
 * Andi wörtlich: *„nimm die UI oben, wenn man etwas bearbeitet raus."* Mit der
 * Leiste fällt „Fertig" — der Knopf, der bis dahin der eine sichtbare Ausgang
 * war. Damit hängt alles an den drei Ausgängen, die es ohnehin gab; sie sind
 * ab jetzt nicht mehr Komfort, sondern die einzige Tür. Also wird jeder
 * einzeln gedrückt und einzeln gemessen:
 *
 *   A) Tipp auf die AUSGEWÄHLTE Kachel   B) Tipp ins Leere   C) Escape
 *
 * Jeder Durchgang beginnt mit einem frischen Long-Press: ein Ausgang, der nur
 * funktioniert, weil der vorige schon zugemacht hat, wäre kein Ausgang.
 */
{
  /**
   * **Frischer Edit-Modus, jedes Mal.** Erst raus (zweimal Escape: der erste
   * Druck gehört dem Stufen-Wähler), dann ein neuer Long-Press auf eine
   * bekannte Kachel. Ohne das „Raus" liefe der nächste Durchgang auf einem
   * Modus weiter, den ein anderer Schritt aufgemacht hat — und dann wäre die
   * AUSGEWÄHLTE Kachel eine andere als die, die diese Sonde gleich antippt.
   * Genau daran ist Ausgang A beim ersten Lauf gescheitert: er tippte eine
   * fremde Kachel an, und die auszuwählen ist richtig, kein Ausstieg.
   * Gibt die Kachel-Id zurück, auf der der Modus wirklich steht.
   */
  const rein = async () => {
    for (let i = 0; i < 2; i++) {
      await evalJs(`window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))`);
      await sleep(200);
    }
    const m = await measure();
    const id = Object.keys(m.tiles)[0];
    const t = m.tiles[id];
    await down(t.x + t.w / 2, t.y + t.h / 2);
    await sleep(900);
    await up(t.x + t.w / 2, t.y + t.h / 2);
    await sleep(600);
    return { ...(await measure()), gewaehlt: id };
  };

  const zeilen = [];

  /* A) Tipp auf die ausgewählte Kachel — der Long-Press hat sie ausgewählt. */
  {
    const vor = await rein();
    // GENAU die Kachel, die der Long-Press ausgewählt hat — eine andere
    // anzutippen wählt sie aus (W6) und ist kein Ausstieg.
    const id = vor.gewaehlt;
    const t = vor.tiles[id];
    await tap(t.x + t.w / 2, t.y + t.h / 2);
    const nach = await measure();
    zeilen.push({
      ausgang: 'A · Tipp auf die ausgewählte Kachel',
      kachel: id,
      editVorher: vor.editing,
      editNachher: nach.editing,
      ok: vor.editing === 'true' && nach.editing === 'false',
    });
  }

  /* B) Tipp ins Leere — auf die Bühne, aber auf keine Kachel. */
  {
    const vor = await rein();
    // Ein Punkt INNERHALB der Bühne, unter dem keine Kachel liegt: die Sonde
    // sucht ihn, statt ihn zu raten (auf einer vollen Bühne gibt es keinen —
    // dann sagt sie das, statt einen Tipp auf eine Kachel als „Leere" zu
    // verkaufen).
    const punkt = await evalJs(`(() => {
      const s = document.querySelector('.idle__stage');
      if (!s) return null;
      const r = s.getBoundingClientRect();
      for (let dy = 4; dy < r.height; dy += 8) {
        for (let dx = 4; dx < r.width; dx += 8) {
          const x = Math.round(r.x + dx), y = Math.round(r.y + dy);
          const el = document.elementFromPoint(x, y);
          if (!el || !el.closest) continue;
          if (el.closest('[data-widget-id]')) continue;
          if (el.closest('.idle__sizer, .idle__dots')) continue;
          if (!el.closest('.idle__stage')) continue;
          return JSON.stringify({ x, y });
        }
      }
      return null;
    })()`);
    if (!punkt) {
      zeilen.push({ ausgang: 'B · Tipp ins Leere', hinweis: 'kein freier Punkt auf der Bühne', ok: null });
    } else {
      const p = JSON.parse(punkt);
      await tap(p.x, p.y);
      const nach = await measure();
      zeilen.push({
        ausgang: 'B · Tipp ins Leere',
        punkt: p,
        editVorher: vor.editing,
        editNachher: nach.editing,
        ok: vor.editing === 'true' && nach.editing === 'false',
      });
    }
  }

  /* C) Escape — zweimal: der erste Druck schließt den Stufen-Wähler. */
  {
    const vor = await rein();
    const waehlerVorher = !!vor.sizer;
    await evalJs(`window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))`);
    await sleep(300);
    const zwischen = await measure();
    await evalJs(`window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))`);
    await sleep(300);
    const nach = await measure();
    zeilen.push({
      ausgang: 'C · Escape',
      waehlerVorher,
      // Die Zweistufigkeit ist die Zusage, nicht ein Umweg: der erste Druck
      // darf nie zwei Ebenen auf einmal nehmen.
      nachErstemDruck: { edit: zwischen.editing, waehler: !!zwischen.sizer },
      editNachher: nach.editing,
      ok: vor.editing === 'true' && nach.editing === 'false',
    });
  }

  const geprueft = zeilen.filter((z) => z.ok !== null);
  /**
   * **Die Bearbeitungsfläche IST die Bühne** (23.08.). Bis dahin rückte die
   * Seite im Edit um die gemessene Höhe der Leiste nach unten
   * (`--home-editband-top`). Gefragt sind darum zwei Rechtecke, nicht eins:
   * dasselbe Element außerhalb und innerhalb des Edit-Modus.
   */
  const flaecheImEdit = await (async () => {
    for (let i = 0; i < 2; i++) {
      await evalJs(`window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))`);
      await sleep(200);
    }
    await sleep(400);
    const aus = await measure();
    const m = await rein();
    const gleich = JSON.stringify(aus.activePageBox) === JSON.stringify(m.activePageBox);
    return {
      ausserhalb: { buehne: aus.stage, seite: aus.activePageBox },
      imEdit: { buehne: m.stage, seite: m.activePageBox },
      seiteByteGleich: gleich,
    };
  })();
  say('7 · Die drei Ausgänge aus dem Edit-Modus (ohne jede Leiste)', {
    leisteImDom: (await measure()).editbar !== null,
    flaecheImEdit,
    ausgaenge: zeilen,
    rot: geprueft.filter((z) => !z.ok).map((z) => z.ausgang),
    ok: geprueft.length === 3 && geprueft.every((z) => z.ok),
  });
  await shot('7-ausgaenge');
}

/* ── Schritt 8 — „Zurücksetzen" wohnt jetzt bei den Widget-Schaltern ───────
 *
 * Der Knopf ist mit der Edit-Leiste NICHT gestorben, er ist UMGEZOGEN:
 * Einstellungen → Zuhause & Integrationen → Zuhause-Widgets, direkt unter die
 * Schalter (Andi 23.08., Entscheidung der Hand: „dort wohnt jetzt alles
 * Verwalterische"). Zwei Dinge sind zu beweisen, und beide zählen:
 *   1. Er steht wirklich in DERSELBEN Rubrik wie die Schalter (nicht irgendwo
 *      in den Einstellungen — sonst wäre „umgezogen" nur ein Wort).
 *   2. Er hat dieselbe Rückfrage wie vorher: erster Druck schärft, zweiter
 *      setzt zurück.
 * Gedrückt wird per `.click()`: dieser Weg ist Tastatur/Zeiger-neutral, und
 * die Finger-Frage stellt sich im Drawer nicht (er scrollt normal).
 */
{
  const ergebnis = await evalJs(`(async () => {
    const schlaf = (ms) => new Promise((ok) => setTimeout(ok, ms));
    const KEY = 'hoshi.homeTiles.layout';
    const vorher = localStorage.getItem(KEY);
    document.querySelector('.nav__settings')?.click();
    await schlaf(400);
    document.querySelector('#settings-card-zuhause-integrationen')?.click();
    await schlaf(500);
    const knopf = [...document.querySelectorAll('.settings__layoutreset button')]
      .find((b) => /zurücksetzen/i.test(b.textContent || ''));
    if (!knopf) return JSON.stringify({ fehler: 'kein Zurücksetzen-Knopf in den Einstellungen' });
    // In derselben Rubrik wie die Schalter? Beide müssen unter EINER
    // \`.settings__group\` hängen — und die muss die Widget-Rubrik sein.
    const gruppe = knopf.closest('.settings__group');
    const schalter = [...(gruppe?.querySelectorAll('.settings__skills input, .settings__skills button') ?? [])];
    const rubrik = gruppe?.querySelector('.settings__label')?.textContent ?? null;
    const beschriftung1 = knopf.textContent;
    knopf.click();
    await schlaf(250);
    const nachErstem = localStorage.getItem(KEY);
    const knopf2 = [...document.querySelectorAll('.settings__layoutreset button')]
      .find((b) => /zurücksetzen|wirklich/i.test(b.textContent || ''));
    const beschriftung2 = knopf2?.textContent ?? null;
    knopf2?.click();
    await schlaf(400);
    const nachZweitem = localStorage.getItem(KEY);
    const bestaetigung = [...document.querySelectorAll('.settings__layoutreset ~ *, .settings__hint')]
      .map((p) => p.textContent).filter((t) => /zurückgesetzt/i.test(t || ''));
    return JSON.stringify({
      rubrik,
      schalterInDerselbenRubrik: schalter.length,
      beschriftung1, beschriftung2,
      layoutVorher: vorher ? JSON.parse(vorher).order.map((e) => e.id).join(',') : null,
      layoutNachErstemDruck: nachErstem ? JSON.parse(nachErstem).order.map((e) => e.id).join(',') : null,
      layoutNachZweitemDruck: nachZweitem ? JSON.parse(nachZweitem).order.map((e) => e.id).join(',') : null,
      bestaetigung,
    });
  })()`);
  const r = JSON.parse(ergebnis);
  say('8 · „Zurücksetzen" in den Einstellungen (mit Rückfrage)', {
    ...r,
    // Die Zusage: gleiche Rubrik wie die Schalter, erster Druck ändert NICHTS,
    // zweiter Druck setzt wirklich zurück.
    ok:
      !r.fehler &&
      r.schalterInDerselbenRubrik > 0 &&
      r.layoutNachErstemDruck === r.layoutVorher &&
      r.layoutNachZweitemDruck !== r.layoutVorher &&
      r.beschriftung1 !== r.beschriftung2,
  });
  await shot('8-settings-reset');
}

console.log(`\n${JSON.stringify(report)}`);
ws.close();
cleanup();
process.exit(0);

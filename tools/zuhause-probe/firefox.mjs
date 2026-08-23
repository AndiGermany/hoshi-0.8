/**
 * **Dieselben Fragen, andere Engine: der Zuhause-Reiter in echtem Firefox.**
 *
 * Andi (22.08., nach zwei Nächten Suche): *„Vergiss nicht, ich verwende
 * fiefox."* — und damit war klar, warum `touch.mjs` seine Regression mit jeder
 * Saat grün meldete: die Sonde fährt Chrome. Zwei Engines, zwei Wahrheiten
 * über Zeiger-Capture, und der Unterschied ist genau die Stelle, an der ich
 * gebaut habe.
 *
 * Firefox spricht kein CDP. Dieses Skript redet **WebDriver BiDi** (seit
 * Firefox 129 über `--remote-debugging-port`) — ein WebSocket-JSON-Protokoll,
 * hier auf das Nötigste heruntergebrochen: Sitzung, Navigation, `script
 * .evaluate`, `input.performActions`. Kein geckodriver, keine Abhängigkeit.
 *
 * Gemessen wird die EINE Frage, an der Andis Befund hängt: **wo kommt der
 * Klick an?** Ein `+`-Knopf, der nichts tut, sieht von außen genauso aus wie
 * ein Knopf, dessen Klick woanders landet.
 *
 * NUTZUNG: node firefox.mjs [breite] [hoehe]
 *   FF_PORT=8811      Port des `serve.mjs`
 *   FF_INPUT=touch    `touch` (Vorgabe) oder `mouse`
 *   FF_SEED=v1|w7     welche Layout-Saat
 *   FF_STIL=1         Zusatz-Schritt 6: die CSS-Zusagen vom 23.08. abfragen
 *   FF_MAX=1          Zusatz-Schritt 7: die Maximieren-Ansicht anfassen
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const FIREFOX = '/Applications/Firefox.app/Contents/MacOS/firefox';
const PORT = Number(process.env.FF_PORT ?? 8811);
const BASE = `http://127.0.0.1:${PORT}/`;
const BIDI_PORT = Number(process.env.FF_BIDI_PORT ?? 9222);
const W = Number(process.argv[2] ?? 834);
const H = Number(process.argv[3] ?? 1112);
const INPUT = process.env.FF_INPUT === 'mouse' ? 'mouse' : 'touch';
const SEED_KIND = process.env.FF_SEED === 'v1' ? 'v1' : 'w7';
const SHOT = process.env.FF_SHOT ?? '';

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

const ORDER_V1 = [
  { id: 'wetter', size: 'L' }, { id: 'laeuft', size: 'L' }, { id: 'einkauf', size: 'L' },
  { id: 'vacuum', size: 'L' }, { id: 'climate', size: 'L' }, { id: 'news', size: 'L' },
];
/**
 * **Andis Form — Zeichen für Zeichen die aus `touch.mjs`.**
 *
 * Zwei Engines dürfen nicht zwei verschiedene Wohnzimmer messen: sonst wäre
 * jeder Unterschied im Ergebnis zuerst ein Unterschied in der SAAT, und die
 * Aussage „in Firefox ist es anders" nichts wert.
 */
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

/* ── Firefox starten ──────────────────────────────────────────────────────── */
const profile = mkdtempSync(join(tmpdir(), 'hoshi-ff-'));
const child = spawn(
  FIREFOX,
  ['--headless', '--no-remote', '--profile', profile, `--remote-debugging-port=${BIDI_PORT}`, 'about:blank'],
  { stdio: 'ignore' },
);
const cleanup = () => child.kill('SIGKILL');
process.on('exit', cleanup);

/* ── winziger BiDi-Client ─────────────────────────────────────────────────── */
let ws = null;
for (let i = 0; i < 150; i++) {
  try {
    const probe = new WebSocket(`ws://127.0.0.1:${BIDI_PORT}/session`);
    await new Promise((ok, no) => {
      probe.onopen = ok;
      probe.onerror = no;
    });
    ws = probe;
    break;
  } catch {
    await sleep(200);
  }
}
if (!ws) throw new Error('Firefox BiDi kam nicht hoch');

let seq = 0;
const pending = new Map();
ws.onmessage = (m) => {
  const msg = JSON.parse(m.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.type === 'success' ? resolve(msg.result) : reject(new Error(JSON.stringify(msg).slice(0, 400)));
  }
};
const bidi = (method, params = {}) =>
  new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });

await bidi('session.new', { capabilities: { alwaysMatch: { acceptInsecureCerts: true } } });
const tree = await bidi('browsingContext.getTree', {});
const context = tree.contexts[0].context;
await bidi('browsingContext.setViewport', { context, viewport: { width: W, height: H } });

/** `script.evaluate` mit Rückgabe als einfacher Wert. */
const evalJs = async (expression) => {
  const res = await bidi('script.evaluate', {
    expression,
    target: { context },
    awaitPromise: true,
    resultOwnership: 'none',
  });
  if (res.type === 'exception') return `EXCEPTION ${JSON.stringify(res.exceptionDetails).slice(0, 300)}`;
  const v = res.result;
  return v?.type === 'string' || v?.type === 'number' || v?.type === 'boolean' ? v.value : v?.value ?? null;
};

// Saat setzen: erst die Seite laden (Origin muss stehen), dann schreiben und neu laden.
await bidi('browsingContext.navigate', { context, url: BASE, wait: 'complete' });
await evalJs(
  Object.entries(SEED)
    .map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`)
    .join('') + '"ok"',
);
await bidi('browsingContext.reload', { context, wait: 'complete' });
await sleep(4000);

/* ── Eingabe: echte Zeiger-Aktionen ──────────────────────────────────────── */
const pointerActions = (actions) =>
  bidi('input.performActions', {
    context,
    actions: [{ type: 'pointer', id: 'zeiger1', parameters: { pointerType: INPUT }, actions }],
  });

const tap = (x, y, holdMs = 60) =>
  pointerActions([
    { type: 'pointerMove', x: Math.round(x), y: Math.round(y), origin: 'viewport' },
    { type: 'pointerDown', button: 0 },
    { type: 'pause', duration: holdMs },
    { type: 'pointerUp', button: 0 },
  ]);

const longPress = (x, y, holdMs = 900) => tap(x, y, holdMs);

const dragTo = (from, to, steps = 16, holdMs = 0) => {
  const acts = [
    { type: 'pointerMove', x: Math.round(from.x), y: Math.round(from.y), origin: 'viewport' },
    { type: 'pointerDown', button: 0 },
  ];
  if (holdMs) acts.push({ type: 'pause', duration: holdMs });
  for (let i = 1; i <= steps; i++) {
    const t = i / steps;
    acts.push({
      type: 'pointerMove',
      x: Math.round(from.x + (to.x - from.x) * t),
      y: Math.round(from.y + (to.y - from.y) * t),
      origin: 'viewport',
      duration: 16,
    });
  }
  acts.push({ type: 'pointerUp', button: 0 });
  return pointerActions(acts);
};

/* ── Der Mitschnitt IN der Seite ─────────────────────────────────────────── */
const traceStart = () =>
  evalJs(`(() => {
    window.__ffLog = [];
    window.__ffOff?.();
    const name = (n) => !n || n.nodeType !== 1 ? String(n)
      : (n.getAttribute && n.getAttribute('data-widget-id') ? '#' + n.getAttribute('data-widget-id') : '')
        + (typeof n.className === 'string' && n.className.trim()
            ? '.' + n.className.trim().split(/\\s+/).slice(-1)[0] : n.tagName);
    const kinds = ['pointerdown','pointermove','pointerup','pointercancel','gotpointercapture',
                   'lostpointercapture','click'];
    const on = (e) => {
      const last = window.__ffLog[window.__ffLog.length - 1];
      const entry = e.type + ' @' + name(e.target);
      if (last && last.startsWith(entry)) {
        window.__ffLog[window.__ffLog.length - 1] = entry + ' x' + (Number(last.slice(entry.length + 2) || 1) + 1);
      } else window.__ffLog.push(entry);
    };
    for (const k of kinds) document.addEventListener(k, on, true);
    window.__ffOff = () => { for (const k of kinds) document.removeEventListener(k, on, true); };
    return 'ok';
  })()`);

const traceRead = () =>
  evalJs(`(() => { const o = JSON.stringify((window.__ffLog||[]).slice(0,40));
                   window.__ffOff?.(); window.__ffOff = null; return o; })()`);

const stored = () =>
  evalJs(`(() => { try {
      const raw = JSON.parse(localStorage.getItem('hoshi.homeTiles.layout') || '{}');
      const out = {}; for (const e of raw.order || []) out[e.id] = e.size;
      return JSON.stringify(out);
    } catch (err) { return 'LESEFEHLER'; } })()`);

const report = { engine: 'firefox', input: INPUT, seed: SEED_KIND, viewport: `${W}x${H}`, steps: [] };
const say = (title, data) => {
  report.steps.push({ title, ...data });
  console.log(`\n── ${title}`);
  for (const [k, v] of Object.entries(data)) console.log(`   ${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`);
};

/* Schritt 0 — steht die Bühne überhaupt? */
const start = JSON.parse(
  await evalJs(`JSON.stringify((() => {
    const box = (el) => { if (!el) return null; const r = el.getBoundingClientRect();
      return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
    const pages = [...document.querySelectorAll('.idle__page')];
    const active = pages.findIndex((p) => p.dataset.active === 'true');
    const tiles = {};
    for (const t of (pages[active] || document).querySelectorAll('[data-widget-id]')) {
      const id = t.getAttribute('data-widget-id');
      if (id && !tiles[id]) tiles[id] = box(t);
    }
    return { ua: navigator.userAgent.slice(0, 60), maxTouchPoints: navigator.maxTouchPoints,
             seiten: pages.length, aktiv: active, buehne: !!document.querySelector('.idle__stage'),
             edit: document.querySelector('.idle__stage')?.dataset.edit ?? null, tiles };
  })())`),
);
say('0 · Ausgangslage (Firefox)', {
  ua: start.ua, seiten: start.seiten, aktiv: start.aktiv, buehne: start.buehne,
  kacheln: Object.keys(start.tiles),
});

/* Schritt 1 — Long-Press öffnet den Edit-Modus + Wähler */
{
  const ids = Object.keys(start.tiles);
  const t = start.tiles[ids[0]];
  await traceStart();
  await longPress(t.x + t.w / 2, t.y + t.h / 2, 900);
  await sleep(700);
  const ereignisse = JSON.parse(await traceRead());
  const nach = JSON.parse(
    await evalJs(`JSON.stringify({
      edit: document.querySelector('.idle__stage')?.dataset.edit ?? null,
      waehler: !!document.querySelector('.idle__sizer'),
      aria: document.querySelector('.idle__sizer')?.getAttribute('aria-label') ?? null })`),
  );
  say('1 · Long-Press → Edit + Wähler', { kachel: ids[0], ...nach, ereignisse, ok: nach.edit === 'true' });
}

/* Schritt 2 — DER Beweis: den +/−-Knopf wirklich drücken */
{
  const btn = JSON.parse(
    await evalJs(`JSON.stringify((() => {
      const s = document.querySelector('.idle__sizer');
      if (!s) return null;
      const list = [...s.querySelectorAll('.idle__sizerbtn')].map((b) => {
        const r = b.getBoundingClientRect();
        return { dir: b.getAttribute('data-dir'), disabled: b.disabled,
                 x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
      });
      // WESSEN Wähler ist das? Ohne diese Id ist „eine Stufe hat sich bewegt"
      // nicht dasselbe wie „DIESE Stufe hat sich bewegt". Der Wähler liegt in
      // derselben Rasterzelle wie seine Kachel — also fragt man den Stapel.
      const r = s.getBoundingClientRect();
      const stapel = document.elementsFromPoint(Math.round(r.x + r.width / 2), Math.round(r.y + r.height / 2));
      const tile = stapel.map((el) => el.closest('[data-widget-id]')).find(Boolean) ?? null;
      return { list, aria: s.getAttribute('aria-label'),
               id: tile ? tile.getAttribute('data-widget-id') : null };
    })())`),
  );
  if (!btn) {
    say('2 · + / − wirklich drücken', { hinweis: 'kein Wähler offen', ok: null });
  } else {
    const press = btn.list.find((b) => !b.disabled) ?? null;
    const vorher = JSON.parse(await stored());
    await traceStart();
    if (press) await tap(press.x, press.y);
    await sleep(600);
    const ereignisse = JSON.parse(await traceRead());
    const nachher = JSON.parse(await stored());
    const geaendert = Object.keys(vorher).filter((k) => vorher[k] !== nachher[k]);
    say('2 · + / − wirklich drücken', {
      waehlerFuer: btn.aria,
      kachel: btn.id,
      knoepfe: btn.list.map((b) => `${b.dir}${b.disabled ? ' DISABLED' : ''}`),
      gedrueckt: press ? press.dir : 'KEINER',
      stufeVorher: vorher,
      stufeNachher: nachher,
      geaendert,
      ereignisse,
      /*
       * Gefragt ist, ob DIESE Kachel eine Stufe gewandert ist — nicht, ob
       * genau eine Zahl in der Datei anders wurde. Die Saat trägt absichtlich
       * eine Stufe, die die Registry nicht kennt (`wecker: 'L'`); der erste
       * Schreibvorgang biegt sie gerade, und das ist gesunde Normalisierung,
       * kein zweiter Knopfdruck.
       */
      ok: !!press && !!btn.id && vorher[btn.id] !== nachher[btn.id],
    });
  }
}

/* Schritt 3 — eine Kachel mit dem Finger ziehen */
{
  const vor = JSON.parse(
    await evalJs(`JSON.stringify((() => {
      const box = (el) => { const r = el.getBoundingClientRect();
        return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
      const pages = [...document.querySelectorAll('.idle__page')];
      const active = pages.findIndex((p) => p.dataset.active === 'true');
      const tiles = {};
      for (const t of (pages[active] || document).querySelectorAll('[data-widget-id]')) {
        const id = t.getAttribute('data-widget-id');
        if (id && !tiles[id]) tiles[id] = box(t);
      }
      const wo = {};
      pages.forEach((p, i) => { for (const t of p.querySelectorAll('[data-widget-id]'))
        if (wo[t.getAttribute('data-widget-id')] === undefined) wo[t.getAttribute('data-widget-id')] = i; });
      return { tiles, wo, page: pages[active] ? box(pages[active]) : null };
    })())`),
  );
  const mid = (t) => ({ x: t.x + t.w / 2, y: t.y + t.h / 2 });
  /*
   * **Greift dieser Punkt wirklich eine Kachel?** Nach Schritt 2 liegt der
   * offene Stufen-Wähler über seiner Kachel — ein Zug, der dort ansetzt, packt
   * die Bedienung statt der Kachel und tut folgerichtig nichts. Das wäre ein
   * Fehler der SONDE, der wie ein Produktfehler aussieht.
   */
  const greifbar = JSON.parse(
    await evalJs(`JSON.stringify((() => {
      const out = {};
      for (const [id, b] of Object.entries(${JSON.stringify(vor.tiles)})) {
        const el = document.elementFromPoint(Math.round(b.x + b.w / 2), Math.round(b.y + b.h / 2));
        out[id] = !!(el && el.closest('[data-widget-id]') && !el.closest('.idle__sizer'));
      }
      return out;
    })())`),
  );
  const ids = Object.keys(vor.tiles).filter((id) => greifbar[id]);
  if (ids.length === 0) {
    say('3 · Finger-Zug einer Kachel', { hinweis: 'keine freie Kachel zum Greifen', ok: null });
    ids.push(Object.keys(vor.tiles)[0]);
  }
  const from = vor.tiles[ids[0]];
  const startP = mid(from);
  const far = ids.map((id) => ({ id, p: mid(vor.tiles[id]) }))
    .map((c) => ({ ...c, d: Math.hypot(c.p.x - startP.x, c.p.y - startP.y) }))
    .sort((a, b) => b.d - a.d)[0];
  const to = far && far.d >= 80 ? far.p
    : { x: startP.x, y: startP.y + (vor.page ? Math.round(vor.page.h * 0.4) : 200) };
  await traceStart();
  await dragTo(startP, to, 20);
  await sleep(800);
  const ereignisse = JSON.parse(await traceRead());
  const nach = JSON.parse(
    await evalJs(`JSON.stringify((() => {
      const box = (el) => { const r = el.getBoundingClientRect();
        return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
      const pages = [...document.querySelectorAll('.idle__page')];
      const active = pages.findIndex((p) => p.dataset.active === 'true');
      const tiles = {};
      for (const t of (pages[active] || document).querySelectorAll('[data-widget-id]')) {
        const id = t.getAttribute('data-widget-id');
        if (id && !tiles[id]) tiles[id] = box(t);
      }
      const wo = {};
      pages.forEach((p, i) => { for (const t of p.querySelectorAll('[data-widget-id]'))
        if (wo[t.getAttribute('data-widget-id')] === undefined) wo[t.getAttribute('data-widget-id')] = i; });
      return { tiles, wo, edit: document.querySelector('.idle__stage')?.dataset.edit ?? null };
    })())`),
  );
  const a = vor.tiles[ids[0]];
  const b = nach.tiles[ids[0]];
  say('3 · Finger-Zug einer Kachel', {
    kachel: ids[0],
    vorher: a,
    nachher: b ?? null,
    woVorher: vor.wo[ids[0]],
    woNachher: nach.wo[ids[0]],
    editNachher: nach.edit,
    ereignisse,
    ok: nach.edit === 'true' &&
        (vor.wo[ids[0]] !== nach.wo[ids[0]] || (!!b && (Math.abs(b.x - a.x) > 8 || Math.abs(b.y - a.y) > 8))),
  });
}

/* ── Schritt 4 — der WAAGERECHTE Zug (Nachtrag 6, Andi-Livetest 23.08.) ────
 *
 * Andi wörtlich: *„ich konnte die widgets nicht verschieben, wenn ich sie nach
 * links und rechts geschoben habe."* Schritt 3 misst nur senkrecht (die
 * weitest entfernte Kachelmitte liegt auf einer hohen Bühne immer untendrunter)
 * — der blinde Fleck hatte die Größe einer ganzen Achse.
 */
{
  const GAP = 12;
  /** Alles, was ein waagerechter Zug wissen muss, in EINER Messung. */
  const messen = async () =>
    JSON.parse(
      await evalJs(`JSON.stringify((() => {
      const box = (el) => { if (!el) return null; const r = el.getBoundingClientRect();
        return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
      const pages = [...document.querySelectorAll('.idle__page')];
      const active = pages.findIndex((p) => p.dataset.active === 'true');
      const tiles = {};
      for (const t of (pages[active] || document).querySelectorAll('[data-widget-id]')) {
        const id = t.getAttribute('data-widget-id');
        if (id && !tiles[id]) tiles[id] = box(t);
      }
      const wo = {};
      pages.forEach((p, i) => { for (const t of p.querySelectorAll('[data-widget-id]'))
        if (wo[t.getAttribute('data-widget-id')] === undefined) wo[t.getAttribute('data-widget-id')] = i; });
      const spalten = pages[active]
        ? getComputedStyle(pages[active]).gridTemplateColumns.split(/\\s+/)
            .map((v) => Math.round(parseFloat(v))).filter((v) => v > 0)
        : [];
      let zellen = null;
      try { zellen = JSON.parse(localStorage.getItem('hoshi.homeTiles.layout') || '{}').placements ?? null; }
      catch (err) { zellen = 'LESEFEHLER'; }
      return { tiles, wo, spalten, zellen, aktiv: active, page: pages[active] ? box(pages[active]) : null,
               edit: document.querySelector('.idle__stage')?.dataset.edit ?? null };
    })())`),
    );

  /** Ein Griffpunkt, an dem wirklich die KACHEL liegt (nicht der Wähler darüber). */
  const griffIn = async (id, t) => {
    const raw = await evalJs(`(() => {
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
        if (tile && tile.getAttribute('data-widget-id') === ${JSON.stringify(id)} && !el.closest('.idle__sizer')) {
          return JSON.stringify({ x: Math.round(x), y: Math.round(y) });
        }
      }
      return '';
    })()`);
    return raw ? JSON.parse(raw) : null;
  };

  const sorgeFuerEdit = async () => {
    let m = await messen();
    if (m.edit === 'true') return m;
    const t = m.tiles[Object.keys(m.tiles)[0]];
    await longPress(t.x + t.w / 2, t.y + t.h / 2, 900);
    await sleep(700);
    return messen();
  };

  const m0 = await sorgeFuerEdit();
  const cols = m0.spalten ?? [];
  const colW = cols[0] ?? 0;
  if (!m0.page || cols.length < 2) {
    say('4 · Waagerechter Zug im Edit', {
      spalten: cols,
      hinweis: 'weniger als zwei Spalten — waagerecht gibt es nichts zu ziehen',
      ok: null,
    });
  } else {
    const zeilen = [];
    const gesehen = new Set();
    for (let runde = 0; runde < 4; runde++) {
      const m = await sorgeFuerEdit();
      const page = m.page;
      const kandidaten = [];
      for (const [id, t] of Object.entries(m.tiles)) {
        if (gesehen.has(id)) continue;
        const k = {
          id, t,
          spanCols: Math.max(1, Math.round((t.w + GAP) / (colW + GAP))),
          col: Math.max(0, Math.round((t.x - page.x) / (colW + GAP))),
        };
        const g = await griffIn(id, t);
        if (g) kandidaten.push({ ...k, griff: g });
      }
      kandidaten.sort((a, b) => a.spanCols - b.spanCols);
      if (kandidaten.length === 0) break;
      const k =
        kandidaten.find((c) => !zeilen.some((z) => z.spanSpalten === c.spanCols)) ?? kandidaten[0];
      gesehen.add(k.id);
      const bevorzugt = runde % 2 === 0 ? 1 : -1;
      const passt = (c) => c >= 0 && c + k.spanCols <= cols.length && c !== k.col;
      const zielCol = passt(k.col + bevorzugt)
        ? k.col + bevorzugt
        : passt(k.col - bevorzugt)
          ? k.col - bevorzugt
          : null;
      if (zielCol === null) {
        zeilen.push({
          kachel: k.id, spanSpalten: k.spanCols, spalteVorher: k.col,
          hinweis: k.spanCols >= cols.length
            ? 'volle Breite — es gibt keine zweite Spalte, in die sie passt'
            : 'keine Nachbarspalte frei',
          ok: null,
        });
        continue;
      }
      const nachP = { x: Math.round(page.x + zielCol * (colW + GAP) + colW / 2), y: k.griff.y };
      await traceStart();
      await dragTo(k.griff, nachP, 20);
      await sleep(800);
      const ereignisse = JSON.parse(await traceRead());
      const danach = await messen();
      const b = danach.tiles[k.id];
      zeilen.push({
        kachel: k.id,
        spanSpalten: k.spanCols,
        richtung: zielCol > k.col ? 'rechts' : 'links',
        spalteVorher: k.col,
        spalteZiel: zielCol,
        spalteNachher: b ? Math.max(0, Math.round((b.x - (danach.page?.x ?? page.x)) / (colW + GAP))) : null,
        bahn: `${k.griff.x},${k.griff.y} → ${nachP.x},${nachP.y}`,
        dx: b ? b.x - k.t.x : null,
        dy: b ? b.y - k.t.y : null,
        zelleVorher: m.zellen?.[String(cols.length)]?.[k.id] ?? null,
        zelleNachher: danach.zellen?.[String(cols.length)]?.[k.id] ?? null,
        seiteVorher: m.aktiv,
        seiteNachher: danach.aktiv,
        editNachher: danach.edit,
        ereignisse: ereignisse.slice(0, 6),
        ok: danach.edit === 'true' && danach.aktiv === m.aktiv && !!b && Math.abs(b.x - k.t.x) > colW / 2,
      });
    }
    const geprueft = zeilen.filter((z) => z.ok !== null);
    say('4 · Waagerechter Zug im Edit (jede Kachelbreite)', {
      spalten: cols,
      zuege: zeilen,
      rot: geprueft.filter((z) => !z.ok).map((z) => `${z.kachel} (${z.spanSpalten} Spalten)`),
      ok: geprueft.length > 0 && geprueft.every((z) => z.ok),
    });
  }
}

/* ── Schritt 5 — der Finger auf einem LINK (Nachtrag 2, in Gecko) ──────────
 *
 * Kurzer Tipp = der Link. Langer Druck = NUR der Edit-Modus, kein Link-Klick
 * hinterher. Beides muss in derselben Engine gelten, in der Andi wirklich
 * steht.
 */
{
  // Erst raus aus dem Edit-Modus, sonst sind alle Kachelkinder inert.
  await evalJs(`(() => { document.querySelector('.idle__editdone')?.click(); return 'ok'; })()`);
  await sleep(700);
  // Zur Nachrichten-Kachel blättern — über die Seitenpunkte, nicht per Wisch
  // (hier wird der Link geprüft, nicht das Blättern).
  await evalJs(`(() => {
    const pages = [...document.querySelectorAll('.idle__page')];
    const i = pages.findIndex((p) => p.querySelector('[data-widget-id="news"]'));
    const dots = [...document.querySelectorAll('.idle__dot')];
    if (i >= 0 && dots[i]) dots[i].click();
    return 'ok';
  })()`);
  await sleep(700);
  const ziel = JSON.parse(
    await evalJs(`JSON.stringify((() => {
      const a = document.querySelector('[data-widget-id="news"] a[href]');
      if (!a) return null;
      const r = a.getBoundingClientRect();
      window.__ffKlicks = [];
      window.__ffKlickOff?.();
      const on = (e) => { window.__ffKlicks.push({ ziel: e.target.className || e.target.tagName });
                          e.preventDefault(); };
      a.addEventListener('click', on);
      window.__ffKlickOff = () => a.removeEventListener('click', on);
      return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2),
               text: (a.textContent || '').trim().slice(0, 40) };
    })())`),
  );
  if (!ziel) {
    say('5 · Finger auf einem Link (Nachrichten-Kachel)', { hinweis: 'kein Link gefunden', ok: null });
  } else {
    const klicks = () => evalJs(`JSON.stringify(window.__ffKlicks || [])`);
    const editAn = () =>
      evalJs(`(document.querySelector('.idle__stage')?.dataset.edit ?? 'null')`);
    await evalJs(`(() => { window.__ffKlicks = []; return 'ok'; })()`);
    await tap(ziel.x, ziel.y, 60);
    await sleep(500);
    const tippKlicks = JSON.parse(await klicks());
    const tippEdit = await editAn();

    await evalJs(`(() => { window.__ffKlicks = []; return 'ok'; })()`);
    await traceStart();
    await longPress(ziel.x, ziel.y, 900);
    await sleep(700);
    const ereignisse = JSON.parse(await traceRead());
    const druckKlicks = JSON.parse(await klicks());
    const druckEdit = await editAn();
    say('5 · Finger auf einem Link (Nachrichten-Kachel)', {
      link: ziel.text,
      eingabe: INPUT,
      tippKlicks,
      tippEdit,
      druckKlicks,
      druckEdit,
      ereignisse,
      erwartung:
        INPUT === 'touch'
          ? 'Tipp = Link, langer Druck = NUR Edit (kein Link-Klick)'
          : 'Maus: Tipp = Link, langer Druck bleibt Link (kein Edit)',
      ok:
        tippKlicks.length === 1 &&
        (INPUT === 'touch'
          ? druckEdit === 'true' && druckKlicks.length === 0
          : druckEdit !== 'true'),
    });
  }
}

/* ── Schritt 7 · Maximieren, in Firefox angefasst (FF_MAX=1) ──────────────
   Chrome hat den Kasten schon aufgemacht (`maximieren.mjs`). Hier zaehlt eine
   andere Engine dieselben Dinge nach: Knopf da, Kasten auf, alle Meldungen
   drin, Quellen-Chip filtert, Escape schliesst. Vorher wird NEU GELADEN — die
   Schritte davor lassen den Edit-Modus an, und dort ist der Knopf per CSS weg
   (was diese Messung gleich mitbeweist: `imEdit` MUSS false sein). */
if (process.env.FF_MAX) {
  const imEdit = await evalJs(
    `(() => { const b = document.querySelector('[data-widget-id="news"] .idle__maxbtn'); return b ? getComputedStyle(b).display !== 'none' : false; })()`,
  );
  // Die Nachrichten-Kachel muss auf Seite 1 stehen, sonst liegt ihr Knopf
  // waagerecht ausserhalb des Fensters (erste Messung: x = 3437 bei 1366 px
  // Breite — Firefox lehnt eine solche Zeigerbewegung zu Recht ab). Also wird
  // sie vor dem Neuladen auf Zelle (0,0) genagelt, wie in `schnitt.mjs`.
  await evalJs(
    `localStorage.setItem('hoshi.homeTiles.layout', ${JSON.stringify(
      JSON.stringify({
        version: 1,
        order: [
          { id: 'news', size: 'M' },
          ...['uhr', 'wecker', 'wetter', 'laeuft', 'einkauf', 'vacuum', 'climate'].map((id) => ({ id, size: 'M' })),
        ],
        placements: Object.fromEntries([1, 2, 3, 4].map((c) => [String(c), { news: { col: 0, row: 0 } }])),
      }),
    )})`,
  );
  await bidi('browsingContext.reload', { context, wait: 'complete' });
  await sleep(1600);
  const mitte = JSON.parse(
    await evalJs(`(() => {
      const b = document.querySelector('[data-widget-id="news"] .idle__maxbtn');
      if (!b) return 'null';
      const r = b.getBoundingClientRect();
      return JSON.stringify({ x: r.x + r.width / 2, y: r.y + r.height / 2, ziel: Math.round(Math.min(r.width, r.height)) });
    })()`),
  );
  await tap(mitte.x, mitte.y);
  await sleep(500);
  const lies = () =>
    evalJs(`(() => {
      const auf = document.querySelector('.overlay.is-open .widgetmax');
      return JSON.stringify({
        offen: !!auf,
        rolle: auf ? auf.getAttribute('role') : null,
        meldungen: auf ? auf.querySelectorAll('.widgetmax__newsitem').length : 0,
        chips: auf ? auf.querySelectorAll('.widgetmax__chip').length : 0,
      });
    })()`);
  const auf = JSON.parse(await lies());
  const chip = JSON.parse(
    await evalJs(`(() => {
      const c = document.querySelectorAll('.overlay.is-open .widgetmax__chip')[1];
      if (!c) return 'null';
      const r = c.getBoundingClientRect();
      return JSON.stringify({ x: r.x + r.width / 2, y: r.y + r.height / 2 });
    })()`),
  );
  await tap(chip.x, chip.y);
  await sleep(400);
  const gefiltert = JSON.parse(await lies());
  await bidi('input.performActions', {
    context,
    actions: [
      { type: 'key', id: 'tastatur', actions: [{ type: 'keyDown', value: '\uE00C' }, { type: 'keyUp', value: '\uE00C' }] },
    ],
  });
  await sleep(500);
  const zu = JSON.parse(await lies());
  const aufDerKachel = await evalJs(`document.querySelectorAll('[data-widget-id="news"] .idle__newsitem').length`);
  say('7 · Maximieren in Firefox', {
    imEdit,
    knopfZiel: mitte.ziel,
    offen: auf.offen,
    rolle: auf.rolle,
    imKasten: auf.meldungen,
    aufDerKachel,
    chips: auf.chips,
    nachFilter: gefiltert.meldungen,
    nachEscape: zu.offen,
    erwartung: 'im Edit verborgen · 44-px-Ziel · Kasten auf · alle Meldungen · Chip filtert · Escape schliesst',
    ok:
      imEdit === false &&
      mitte.ziel >= 44 &&
      auf.offen === true &&
      auf.rolle === 'dialog' &&
      auf.meldungen > aufDerKachel &&
      gefiltert.meldungen > 0 &&
      gefiltert.meldungen < auf.meldungen &&
      zu.offen === false,
  });
}

/* ── Schritt 6 · Die Zusagen des 23.08. in EINER zweiten Engine (FF_STIL=1) ──
   Chrome ist nicht der Browser, sondern EIN Browser. Drei Dinge dieser Runde
   stehen und fallen mit Fähigkeiten, die Blink früher hatte als Gecko:
   `color-mix()` für den durchscheinenden Rahmen, `@container … (max-height)`
   für die flache Kachel, und `-webkit-line-clamp` für die zweizeilige
   Schlagzeile. Alle drei werden hier NICHT behauptet, sondern abgefragt. */
if (process.env.FF_STIL) {
  const stil = JSON.parse(
    await evalJs(`(() => {
      const tile = document.querySelector('.idle__tile');
      const cs = tile ? getComputedStyle(tile) : null;
      const probe = document.createElement('div');
      probe.style.cssText = 'container-type:size;container-name:kachel;width:200px;height:100px;position:absolute;left:-9999px';
      const kind = document.createElement('span');
      kind.className = 'ff-cq-probe';
      probe.appendChild(kind);
      const st = document.createElement('style');
      st.textContent = '@container kachel (max-height: 180px){.ff-cq-probe{color:rgb(1,2,3)}}';
      document.head.appendChild(st);
      document.body.appendChild(probe);
      const cq = getComputedStyle(kind).color;
      probe.remove(); st.remove();
      return JSON.stringify({
        rahmen: cs ? cs.borderTopColor : '-',
        durchsichtig: cs ? /rgba|\\/ 0\\.|color\\(/.test(cs.borderTopColor) : false,
        containerQueryHoehe: cq,
        lineClamp: CSS.supports('-webkit-line-clamp', '2'),
        colorMix: CSS.supports('color', 'color-mix(in oklab, red 50%, transparent)'),
      });
    })()`),
  );
  say('6 · Zusagen des 23.08. in Firefox', {
    ...stil,
    erwartung: 'Rahmen mit Alpha < 1 · Container-Query auf Höhe greift · line-clamp + color-mix da',
    ok:
      stil.durchsichtig &&
      stil.containerQueryHoehe === 'rgb(1, 2, 3)' &&
      stil.lineClamp === true &&
      stil.colorMix === true,
  });
}

if (SHOT) {
  const png = await bidi('browsingContext.captureScreenshot', { context });
  writeFileSync(SHOT, Buffer.from(png.data, 'base64'));
  console.log(`\n   Bild: ${SHOT}`);
}

console.log(`\n${JSON.stringify(report)}`);
ws.close();
cleanup();
process.exit(0);

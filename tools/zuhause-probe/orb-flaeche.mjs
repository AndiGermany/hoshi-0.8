/**
 * **Orb-Fläche + Flächen-Alpha — die Zahlen hinter „der Orb nimmt fast ein
 * Viertel des Bildes ein" (Andi, 22.08.).**
 *
 * `probe.mjs` misst die Blöcke des Zuhause-Reiters bei EINEM Format. Dieses
 * Skript beantwortet die zwei Fragen, die der Orb-/Transparenz-Auftrag stellt,
 * und beide brauchen mehrere Formate auf einmal:
 *
 *  1. **Wie viel Bild frisst der Orb wirklich?** Der Streit ist nie über die
 *     Kreisfläche geführt worden, sondern über den BLOCK: Orb + Fuge +
 *     Beschriftung + sein oberes Polster (`.voiceorb`). Der Kreis ist bei
 *     1366×1024 rund 1,2 % der Bildfläche — der Block dagegen nimmt bei einem
 *     flachen iPad-Fenster über ein Fünftel der HÖHE. „Ein Viertel" ist also
 *     eine Aussage über die Höhe, und genau die wird hier gemeldet: px, Anteil
 *     an der Fensterhöhe UND an der Fläche, je Format.
 *
 *  2. **Wie deckend sind die Flächen?** Nicht als Token-Text, sondern als
 *     `getComputedStyle().backgroundColor` — also das, was der Browser aus
 *     `color-mix(...)` wirklich macht, inklusive Alpha. Nur so ist ein
 *     Vorher/Nachher zwischen zwei Ständen vergleichbar.
 *
 * Die Formate sind Andis echte Fälle: das Flur-iPad quer mit und ohne
 * Safari-Leisten (1194×745 / 1180×820), hoch (834×1112), quer (1112×834) und
 * das Referenz-Fenster der Galerie-Sichtungen (1366×1024).
 *
 * Fallen wie überall hier: eigener Chrome mit eigenem `--user-data-dir`, am
 * Ende stirbt NUR der eigene Kindprozess.
 *
 * NUTZUNG:  node orb-flaeche.mjs [theme]      (Vorgabe: aoi)
 *           SHOT_PORT=8794 node orb-flaeche.mjs fuyubare
 */
import { spawn } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.SHOT_PORT ?? 8794);
const THEME = process.argv[2] ?? 'aoi';
const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

/** Andis echte Fenster + das Referenzformat der Galerie-Sichtungen. */
const VIEWPORTS = [
  [1366, 1024],
  [834, 1112],
  [1112, 834],
  [1194, 745],
  [1180, 820],
];

const profile = mkdtempSync(join(tmpdir(), 'hoshi-orbflaeche-'));
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
    '--remote-debugging-port=9447',
    `--user-data-dir=${profile}`,
    'about:blank',
  ],
  { stdio: 'ignore' },
);
process.on('exit', () => child.kill('SIGKILL'));

let version = null;
for (let i = 0; i < 100; i++) {
  try {
    version = await (await fetch('http://127.0.0.1:9447/json/version')).json();
    break;
  } catch {
    await sleep(100);
  }
}
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

const SEED = {
  'hoshi.settings': JSON.stringify({ theme: THEME, language: 'de', voice: 'coral' }),
  // Ohne diesen Flag schreibt `loadSettings()` ein gesätes 'yoru' einmalig auf
  // 'aoi' um (Aoi-Adopt-Migration) — s. die ausführliche Begründung in shot.mjs.
  'hoshi.settings.aoi-migrated': '1',
  'hoshi.homeTiles.wetter': 'true',
  'hoshi.homeTiles.laeuft': 'true',
  'hoshi.homeTiles.einkauf': 'true',
  'hoshi.homeTiles.currentAffairs': 'true',
  'hoshi.homeTiles.layout': JSON.stringify({
    version: 1,
    order: [
      { id: 'wetter', size: 'M' },
      { id: 'laeuft', size: 'M' },
      { id: 'einkauf', size: 'M' },
      { id: 'vacuum', size: 'L' },
      { id: 'climate', size: 'L' },
      { id: 'news', size: 'M' },
    ],
  }),
};

const EXPR = `(() => {
  const box = (sel) => { const e = document.querySelector(sel); if (!e) return null;
    const r = e.getBoundingClientRect();
    return {h: Math.round(r.height), w: Math.round(r.width), top: Math.round(r.top), bottom: Math.round(r.bottom)}; };
  const bg = (sel) => { const e = document.querySelector(sel); if (!e) return null;
    const s = getComputedStyle(e); return s.backgroundColor; };
  const vp = {w: innerWidth, h: innerHeight};
  const rootCss = getComputedStyle(document.documentElement);
  const tap = box('.voiceorb__tap');
  const blk = box('.voiceorb');
  const pct = (n, d) => Math.round((n / d) * 1000) / 10;
  return {
    viewport: vp,
    // WELCHES Thema wirklich steht. Nicht dasselbe wie das gesaete: die
    // Automatik (Sora) loest zur Laufzeit auf, und ein Ladefehler laesst
    // stillschweigend den Basis-Satz stehen. Ohne diese zwei Felder haette ich
    // am 22.08. beinahe einen Aoi-Screenshot als „Yoru" ins RESULT geschrieben.
    themeAttr: document.documentElement.dataset.theme ?? '(keins)',
    accent: rootCss.getPropertyValue('--accent').trim(),
    docScrollH: document.documentElement.scrollHeight,
    orbCircle: tap && {...tap, pctOfHeight: pct(tap.h, vp.h), pctOfArea: pct(tap.w * tap.h, vp.w * vp.h)},
    orbBlock: blk && {...blk, pctOfHeight: pct(blk.h, vp.h), pctOfArea: pct(blk.w * blk.h, vp.w * vp.h)},
    stage: box('.idle__tiles'),
    tile: box('.idle__tile'),
    tileCount: document.querySelectorAll('.idle__tile').length,
    bg: {
      tile: bg('.idle__tile'),
      homefoot: bg('.homefoot'),
      nav: bg('.nav'),
      orbCard: bg('.voiceorb__card'),
      appMain: bg('.app__main'),
      body: bg('body'),
    },
    orbVars: (() => { const e = document.querySelector('.voiceorb__tap'); if (!e) return null;
      const s = getComputedStyle(e);
      return {size: s.getPropertyValue('--orb-size').trim(), core: s.getPropertyValue('--orb-core').trim(),
              ring: s.getPropertyValue('--orb-ring').trim(), bloom: s.getPropertyValue('--orb-bloom').trim(),
              deco: s.getPropertyValue('--orb-deco').trim()}; })(),
    coreOpacity: (() => { const e = document.querySelector('.vc-orb__core');
      return e ? getComputedStyle(e).opacity : null; })(),
    surfaceMix: getComputedStyle(document.documentElement).getPropertyValue('--surface-mix').trim() || '(nicht gesetzt)',
    // Ein-Fenster-Vertrag: WELCHES Element reicht am tiefsten? Ein blosses
    // scrollHeight sagt nur DASS etwas uebersteht und schwankt zudem, solange
    // der Eintritts-Stagger (view-in, translateY 6px) noch laeuft — ein Name
    // ist die einzige Antwort, mit der man etwas anfangen kann.
    tiefstes: (() => {
      let best = null;
      for (const e of document.querySelectorAll('body *')) {
        const r = e.getBoundingClientRect();
        if (r.width === 0 && r.height === 0) continue;
        if (!best || r.bottom > best.bottom) {
          best = {bottom: Math.round(r.bottom), sel: e.tagName.toLowerCase() + (e.className && typeof e.className === 'string' ? '.' + e.className.trim().split(/\\s+/).join('.') : '')};
        }
      }
      return best;
    })(),
  };
})()`;

const out = [];
for (const [w, h] of VIEWPORTS) {
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  await send('Page.enable', {}, sessionId);
  await send('Runtime.enable', {}, sessionId);
  await send(
    'Emulation.setDeviceMetricsOverride',
    { width: w, height: h, deviceScaleFactor: 1, mobile: false },
    sessionId,
  );
  await send(
    'Page.addScriptToEvaluateOnNewDocument',
    {
      source: Object.entries(SEED)
        .map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`)
        .join(''),
    },
    sessionId,
  );
  const seen = events.length;
  await send('Page.navigate', { url: `http://127.0.0.1:${PORT}/` }, sessionId);
  for (let i = 0; i < 200; i++) {
    if (events.slice(seen).some((e) => e.method === 'Page.loadEventFired')) break;
    await sleep(50);
  }
  await sleep(1500);
  const res = await send('Runtime.evaluate', { expression: EXPR, returnByValue: true }, sessionId);
  out.push(res.result.value);
  await send('Target.closeTarget', { targetId });
}

console.log(JSON.stringify({ theme: THEME, runs: out }, null, 1));
ws.close();
child.kill('SIGKILL');
process.exit(0);

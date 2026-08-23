/**
 * Galerie-Probe: die Design-Fläche mit eigenen Augen ansehen UND den Weg
 * dorthin/zurück MESSEN.
 *
 * Warum ein eigenes Skript neben `shot.mjs`: die Galerie ist kein Zustand, den
 * man seeden kann — man muss sie AUFMACHEN. Und genau der Weg dahin ist das,
 * was Andi am 21.08. beanstandet hat („Dort ist immer noch die Zwischenseite"),
 * also wird er hier Schritt für Schritt protokolliert statt geglaubt:
 *
 *   1. Einstellungen auf  → steht die Übersicht mit ihren Karten da?
 *   2. EIN Klick auf „Darstellung" → ist die Galerie offen, und liegt darunter
 *      immer noch die ÜBERSICHT (nicht eine betretene Kategorie)?
 *   3. „Fertig" → landet man wieder auf der Übersicht?
 *
 * Dazu die Bilder: die offene Galerie in beiden Größen, plus das Bild NACH
 * „Fertig" — denn die Zwischenseite war genau das, was „Fertig" freilegte.
 *
 * Basis (CDP-Client, Chrome-Start, Aufräumen) ist 1:1 `shot.mjs`.
 *
 * NUTZUNG: node galerie.mjs <out-dir> [tag]
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.SHOT_PORT ?? 8794);
const BASE = `http://127.0.0.1:${PORT}/`;
const OUT_DIR = process.argv[2];
const TAG = process.argv[3] ?? 'galerie';
const SIZES = [
  { w: 1366, h: 1024, name: '1366x1024' },
  { w: 834, h: 1112, name: '834x1112' },
];

/** Startzustand: Aoi aktiv (ein Tag-Thema, mitten in der Liste). */
const SEED = {
  'hoshi.settings': JSON.stringify({ theme: 'aoi', language: 'de', voice: 'coral' }),
};

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

const profile = mkdtempSync(join(tmpdir(), 'hoshi-galerie-'));
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
    '--remote-debugging-port=9446',
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
    version = await (await fetch('http://127.0.0.1:9446/json/version')).json();
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
  await sleep(1200);

  const evalJs = async (expression) => {
    const res = await send(
      'Runtime.evaluate',
      { expression, returnByValue: true, awaitPromise: true },
      sessionId,
    );
    if (res.exceptionDetails) throw new Error(JSON.stringify(res.exceptionDetails));
    return res.result.value;
  };
  const shoot = async (name) => {
    const shot = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
    const out = join(OUT_DIR, `${TAG}-${name}-${size.name}.png`);
    writeFileSync(out, Buffer.from(shot.data, 'base64'));
    console.log(out);
  };

  /** Der Zustand der Schale, wie ihn ein Mensch sehen würde. */
  const stateJs = `(() => ({
    galerieOffen: !!document.querySelector('.overlay.is-open'),
    uebersichtDa: !!document.querySelector('.settings__catgrid'),
    rueckwegDa: !!document.querySelector('.settings__back'),
    kartenSichtbar: document.querySelectorAll('.settings__catcard').length,
    designKarten: document.querySelectorAll('.themegallery__card').length,
    gruppen: [...document.querySelectorAll('.themegallery__group h3')].map(h => h.textContent),
    aktiv: (document.querySelector('.themegallery__activename')||{}).textContent || null,
    backdrop: (() => { const b = document.querySelector('.overlay'); if (!b) return null;
      const r = b.getBoundingClientRect(); return Math.round(r.width)+'x'+Math.round(r.height); })(),
  }))()`;

  const steps = {};

  // ── 1. Einstellungen aufmachen ────────────────────────────────────────────
  await evalJs(`(() => {
    const b = [...document.querySelectorAll('button')].find(
      b => (b.getAttribute('aria-label')||'').toLowerCase().includes('einstellung'));
    if (!b) throw new Error('Einstellungs-Knopf nicht gefunden');
    b.click();
  })()`);
  await sleep(700);
  steps['1-einstellungen-offen'] = await evalJs(stateJs);
  await shoot('1-uebersicht');

  // ── 2. EIN Klick auf „Darstellung" ────────────────────────────────────────
  await evalJs(`document.querySelector('#settings-card-darstellung').click()`);
  await sleep(900);
  steps['2-nach-klick-darstellung'] = await evalJs(stateJs);
  await shoot('2-galerie');

  // Ganz nach unten scrollen: die letzte Gruppe muss auch wirklich erreichbar
  // sein (auf 834 px ist die Fläche höher als der Bildschirm).
  await evalJs(`(() => { const g = document.querySelector('.themegallery__groups');
    if (g) g.scrollTop = g.scrollHeight; const c = document.querySelector('.themegallery');
    if (c) c.scrollTop = c.scrollHeight; })()`);
  await sleep(500);
  await shoot('3-galerie-unten');

  // ── 3. „Fertig" ───────────────────────────────────────────────────────────
  await evalJs(`document.querySelector('.themegallery__done').click()`);
  await sleep(900);
  steps['3-nach-fertig'] = await evalJs(stateJs);
  await shoot('4-nach-fertig');

  report[size.name] = steps;
  await send('Target.closeTarget', { targetId });
}

const jsonOut = join(OUT_DIR, `${TAG}-messung.json`);
writeFileSync(jsonOut, JSON.stringify(report, null, 2));
console.log(jsonOut);
console.log(JSON.stringify(report, null, 2));

ws.close();
cleanup();
process.exit(0);

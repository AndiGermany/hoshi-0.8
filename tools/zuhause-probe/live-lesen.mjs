/**
 * **Der deployte Stand, NUR LESEND.**
 *
 * Gebaut für Andis Regression vom 22.08. nachts („Die Größen einstellung ist
 * komplett kaputt … ich kann nichts verschieben"), nachdem die Sonde
 * `touch.mjs` sie lokal mit beiden Saaten NICHT reproduzieren konnte. Wenn der
 * Fehler nicht im Code liegt, den ich baue, muss er in dem liegen, der bei ihm
 * läuft — und die einzige ehrliche Quelle dafür ist der laufende Stand selbst.
 *
 * **Was dieses Skript NICHT tut**, und zwar mit Absicht:
 *   - Es klickt nichts an. Kein `Input.dispatch*`, kein `.click()`.
 *   - Es schreibt nichts in `localStorage` und ändert keine Einstellung.
 *   - Es gibt keinen Token aus. Es braucht auch keinen: `VITE_TOKEN` ist eine
 *     BAU-Zeit-Variable (`src/api/config.ts`), das ausgelieferte Bündel trägt
 *     seinen Schlüssel also selbst. Hier wird nichts injiziert.
 *   - Es benutzt ein flüchtiges Profil unter `os.tmpdir()` und beendet am Ende
 *     nur den eigenen Kindprozess.
 *
 * Andis Layout ist SEIN Zustand: es wird gelesen und in Zahlen gemeldet
 * (welche Kachel, welche Stufe, welche Zellen) — nie verändert.
 *
 * NUTZUNG: node live-lesen.mjs [url]
 *   LIVE_URL=https://…      Vorgabe https://192.168.178.106:8082
 *   LIVE_SHOT=<datei.png>   wenn gesetzt: ein Bild dorthin
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const URL_ = process.argv[2] ?? process.env.LIVE_URL ?? 'https://192.168.178.106:8082';
const PORT = Number(process.env.LIVE_DEBUG_PORT ?? 9521);
const SHOT = process.env.LIVE_SHOT ?? '';
const W = Number(process.env.LIVE_W ?? 834);
const H = Number(process.env.LIVE_H ?? 1112);

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

const profile = mkdtempSync(join(tmpdir(), 'hoshi-live-'));
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
    // Der Prod-Stand fährt ein selbstsigniertes Zertifikat (dieselbe Lage wie
    // in `tools/prod-probe-0.8.sh`). NUR für dieses flüchtige Profil.
    '--ignore-certificate-errors',
    `--remote-debugging-port=${PORT}`,
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
    version = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json();
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
const cmd = (m, p) => send(m, p, sessionId);
const evalJs = async (expression) => {
  const res = await cmd('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (res.exceptionDetails) return `AUSWERTUNG-FEHLER ${JSON.stringify(res.exceptionDetails).slice(0, 200)}`;
  return res.result.value;
};

await cmd('Page.enable');
await cmd('Runtime.enable');
await cmd('Log.enable');
await cmd('Network.enable');
await cmd('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: true });
await cmd('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });

await cmd('Page.navigate', { url: URL_ });
for (let i = 0; i < 200; i++) {
  if (events.some((e) => e.method === 'Page.loadEventFired')) break;
  await sleep(50);
}
// Grosszuegig warten: die Kacheln kommen erst nach den Fetches, und ein
// Fehler beim Rendern zeigt sich genau dann.
await sleep(6000);

/* ── Was ist angekommen? ──────────────────────────────────────────────────── */

const fehler = events
  .filter((e) => e.method === 'Runtime.exceptionThrown')
  .map((e) => {
    const d = e.params?.exceptionDetails;
    return {
      text: d?.exception?.description ?? d?.text ?? '?',
      zeile: `${d?.url ?? ''}:${d?.lineNumber ?? '?'}`,
    };
  });

const logs = events
  .filter((e) => e.method === 'Log.entryAdded')
  .map((e) => `${e.params?.entry?.level}: ${e.params?.entry?.text}`.slice(0, 240));

const konsole = events
  .filter((e) => e.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(e.params?.type))
  .map((e) => `${e.params.type}: ${(e.params.args ?? []).map((a) => a.value ?? a.description ?? '?').join(' ')}`.slice(0, 240));

const fehlerhafteAntworten = events
  .filter((e) => e.method === 'Network.responseReceived' && e.params?.response?.status >= 400)
  .map((e) => `${e.params.response.status} ${e.params.response.url}`.slice(0, 160));

/** Der Zustand der Bühne — und Andis Layout, gelesen, nie geschrieben. */
const stand = await evalJs(`(() => {
  const q = (s) => document.querySelector(s);
  const box = (el) => { if (!el) return null; const r = el.getBoundingClientRect();
    return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
  let layout = null, layoutFehler = null;
  try {
    const raw = localStorage.getItem('hoshi.homeTiles.layout');
    if (raw) {
      const p = JSON.parse(raw);
      layout = {
        version: p.version,
        order: (p.order || []).map((e) => e.id + ':' + e.size),
        // NUR die Form, nicht die Werte-Wueste: welche Spaltenzahlen sind
        // angeordnet, und wie viele Kacheln stehen je Spaltenzahl darin?
        placements: Object.fromEntries(Object.entries(p.placements || {})
          .map(([k, v]) => [k, Object.keys(v || {}).length])),
        unbekannteFelder: Object.keys(p).filter((k) => !['version','order','placements'].includes(k)),
      };
    }
  } catch (err) { layoutFehler = String(err).slice(0, 200); }
  return {
    titel: document.title,
    reiter: [...document.querySelectorAll('.app__nav button, nav button')].map((b) => b.textContent.trim()),
    aktiverReiter: (q('[aria-current="page"]') || q('.app__navbtn[aria-current]'))?.textContent?.trim() ?? null,
    wurzelKinder: q('#root') ? q('#root').children.length : 'KEIN #root',
    buehne: !!q('.idle__stage'),
    buehneBox: box(q('.idle__stage')),
    seiten: document.querySelectorAll('.idle__page').length,
    kacheln: [...document.querySelectorAll('.idle__pages [data-widget-id]')].map((t) => t.getAttribute('data-widget-id')),
    kachelnOhneId: document.querySelectorAll('.idle__pages .idle__tile:not([data-widget-id])').length,
    editAn: q('.idle__stage')?.getAttribute('data-edit') ?? null,
    waehlerOffen: !!q('.idle__sizer'),
    leiste: !!q('.idle__editbar'),
    // Die CSS-Merkmale, die der Merge neu benutzt — hier gemessen statt vermutet.
    kann: {
      containerType: CSS.supports('container-type: inline-size'),
      containerQuery: CSS.supports('(container-type: inline-size)'),
      has: CSS.supports('selector(:has(*))'),
      inert: 'inert' in HTMLElement.prototype,
      colorMix: CSS.supports('color: color-mix(in srgb, red 50%, blue)'),
      touchAction: CSS.supports('touch-action: pan-y'),
    },
    layout,
    layoutFehler,
    // Die Kachel-Schalter: reine Ja/Nein-Werte, keine Geheimnisse. Sie
    // entscheiden mit, WELCHE Kacheln ueberhaupt auf der Buehne stehen — und
    // genau darin unterscheidet sich Andis Geraet von jeder Saat.
    schalter: Object.fromEntries(
      Object.keys(localStorage)
        .filter((k) => k.startsWith('hoshi.homeTiles.') && k !== 'hoshi.homeTiles.layout')
        .map((k) => [k.replace('hoshi.homeTiles.', ''), localStorage.getItem(k)]),
    ),
    // Welche Zellen stehen fuer 3 Spalten? (Ids + Zellen, das ist Andis
    // Anordnung — gelesen, nie geschrieben.)
    zellen3: (() => {
      try {
        const p = JSON.parse(localStorage.getItem('hoshi.homeTiles.layout') || '{}');
        return p.placements && p.placements['3'] ? p.placements['3'] : null;
      } catch { return null; }
    })(),
  };
})()`);

console.log('── Deployter Stand, nur gelesen ──────────────────────────────');
console.log(`   URL: ${URL_}   Viewport: ${W}x${H}`);
console.log(`   Stand: ${JSON.stringify(stand, null, 1)}`);
console.log(`\n   Ausnahmen (${fehler.length}):`);
for (const f of fehler) console.log(`     ${f.text.split('\n')[0]}   @ ${f.zeile}`);
console.log(`\n   Konsole error/warn (${konsole.length}):`);
for (const k of konsole) console.log(`     ${k}`);
console.log(`\n   Log-Eintraege (${logs.length}):`);
for (const l of logs) console.log(`     ${l}`);
console.log(`\n   HTTP >= 400 (${fehlerhafteAntworten.length}):`);
for (const r of fehlerhafteAntworten) console.log(`     ${r}`);

if (SHOT) {
  const png = await cmd('Page.captureScreenshot', { format: 'png' });
  writeFileSync(SHOT, Buffer.from(png.data, 'base64'));
  console.log(`\n   Bild: ${SHOT}`);
}

ws.close();
cleanup();
process.exit(0);

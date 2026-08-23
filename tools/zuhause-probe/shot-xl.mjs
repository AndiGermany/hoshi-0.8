/**
 * XL-Screenshots (W5): jede XL-fähige Kachel EINZELN, in voller Bühnenbreite.
 *
 * Warum je ein Bild statt eines Gesamtbilds: XL ist `{cols: columns, rows: 2}`
 * — auf drei Spalten belegt EINE XL-Kachel schon zwei Drittel der Bühne. Wären
 * mehrere gleichzeitig an, verteilte der Packer sie über Seiten, und man
 * fotografierte Seite 1 statt der Kachel, um die es geht. Der Schalter je
 * Widget (`hoshi.homeTiles.*`) ist ohnehin da; das Skript legt ihn nur um.
 *
 * Dieselben zwei Fallen wie in `shot.mjs` sind eingebaut:
 *  - Chrome beendet sich auf dieser Kiste nie von selbst ⇒ eigener
 *    `--user-data-dir` unter `os.tmpdir()`, am Ende stirbt NUR der eigene
 *    Kindprozess. Kein fremder Chrome wird angefasst.
 *  - Die API-Antworten sind gefälscht, aber vertragstreu (`serve-xl.mjs`).
 *
 * NUTZUNG: node shot-xl.mjs <out-dir> [widget…]
 *   Ohne Widget-Argument: alle fünf XL-fähigen (wetter laeuft einkauf climate news).
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.SHOT_PORT ?? 8795);
const CDP_PORT = Number(process.env.SHOT_CDP_PORT ?? 9446);
const BASE = `http://127.0.0.1:${PORT}/`;
const OUT_DIR = process.argv[2];
const ALL = ['wetter', 'laeuft', 'einkauf', 'climate', 'news'];
const WANTED = process.argv.length > 3 ? process.argv.slice(3) : ALL;
const SIZE = { w: Number(process.env.SHOT_W ?? 1366), h: Number(process.env.SHOT_H ?? 1024) };

/** Der `hoshi.homeTiles.*`-Schlüssel je Widget-Id (die drei Bestandskeys weichen ab). */
const FLAG_KEY = {
  uhr: 'hoshi.homeTiles.uhr',
  wecker: 'hoshi.homeTiles.wecker',
  wetter: 'hoshi.homeTiles.wetter',
  laeuft: 'hoshi.homeTiles.laeuft',
  einkauf: 'hoshi.homeTiles.einkauf',
  vacuum: 'hoshi.homeTiles.vacuum',
  climate: 'hoshi.homeTiles.climate',
  news: 'hoshi.homeTiles.currentAffairs',
};
const STAGE = ['uhr', 'wetter', 'laeuft', 'einkauf', 'vacuum', 'climate', 'news'];

/** localStorage-Saat: NUR `target` an, und zwar auf XL. */
function seedFor(target) {
  const seed = {
    'hoshi.settings': JSON.stringify({ theme: 'aoi', language: 'de', voice: 'coral' }),
    'hoshi.homeTiles.wecker': 'true',
    'hoshi.homeTiles.layout': JSON.stringify({
      version: 1,
      order: STAGE.map((id) => ({ id, size: id === target ? 'XL' : 'M' })),
    }),
  };
  for (const id of STAGE) seed[FLAG_KEY[id]] = String(id === target);
  return seed;
}

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

const profile = mkdtempSync(join(tmpdir(), 'hoshi-shot-xl-'));
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

for (const target of WANTED) {
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  await send('Page.enable', {}, sessionId);
  await send('Runtime.enable', {}, sessionId);
  await send(
    'Emulation.setDeviceMetricsOverride',
    { width: SIZE.w, height: SIZE.h, deviceScaleFactor: 1, mobile: false },
    sessionId,
  );
  const seedJs = Object.entries(seedFor(target))
    .map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`)
    .join('');
  await send('Page.addScriptToEvaluateOnNewDocument', { source: seedJs }, sessionId);

  const seen = events.length;
  await send('Page.navigate', { url: BASE }, sessionId);
  for (let i = 0; i < 200; i++) {
    if (events.slice(seen).some((e) => e.method === 'Page.loadEventFired')) break;
    await sleep(50);
  }
  // Auf die echte Kachel warten (sie kommt erst nach den Fetches).
  for (let i = 0; i < 150; i++) {
    const res = await send(
      'Runtime.evaluate',
      { expression: `document.querySelectorAll('.idle__tile').length > 0`, returnByValue: true },
      sessionId,
    );
    if (res.result.value === true) break;
    await sleep(100);
  }
  await sleep(900); // Einblend-Animationen ausklingen lassen

  // Die gemessene Fläche mit ins Log — ein Urteil über „trägt die Kachel die
  // Fläche" braucht Zahlen, nicht nur ein Bild.
  const geo = await send(
    'Runtime.evaluate',
    {
      expression: `(() => {
        const t = document.querySelector('.idle__tile');
        if (!t) return null;
        const r = t.getBoundingClientRect();
        const cols = getComputedStyle(document.querySelector('.idle__page')).gridTemplateColumns;
        return { w: Math.round(r.width), h: Math.round(r.height), cols: cols.split(' ').length };
      })()`,
      returnByValue: true,
    },
    sessionId,
  );

  const shot = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
  const out = join(OUT_DIR, `xl-${target}-${SIZE.w}x${SIZE.h}.png`);
  writeFileSync(out, Buffer.from(shot.data, 'base64'));
  const g = geo.result.value;
  console.log(`${out}  ${g ? `${g.w}×${g.h} px, Bühne ${g.cols} Spalten` : '(keine Kachel!)'}`);
  await send('Target.closeTarget', { targetId });
}

ws.close();
cleanup();
process.exit(0);

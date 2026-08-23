/**
 * Sauger-Screenshots über alle vier Stufen — Schwester von `shot.mjs`, mit
 * genau zwei Unterschieden: der Seed schaltet **nur** die Sauger-Kachel an
 * (sonst konkurrieren fünf Widgets um die Bühne und die Kachel ist auf jedem
 * Bild woanders), und geschossen wird EINE Fenstergröße über die vier Stufen
 * statt einer Stufe über zwei Fenstergrößen.
 *
 * Warum eine eigene Datei statt eines Schalters in `shot.mjs`: dort hängen die
 * Vorher/Nachher-Bilder der Feinschliff-Bestellung dran (`docs/screenshots/
 * zuhause-feinschliff/`) — wer sie nachstellen will, braucht den Seed
 * unverändert. Dieselbe Begründung wie bei `serve-xl.mjs`.
 *
 * NUTZUNG: node shot-sauger.mjs <out-dir> <tag>
 *   SHOT_PORT   Port des Probe-Servers (Default 8796)
 *   SHOT_EDIT   '1' ⇒ Bühne im Edit-Modus (Knöpfe müssen dann inert sein)
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.SHOT_PORT ?? 8796);
const BASE = `http://127.0.0.1:${PORT}/`;
const OUT_DIR = process.argv[2];
const TAG = process.argv[3] ?? 'sauger';
const W = Number(process.env.SHOT_W ?? 1366);
const H = Number(process.env.SHOT_H ?? 1024);
const STEPS = ['S', 'M', 'L', 'XL'];

const seedFor = (size) => ({
  'hoshi.settings': JSON.stringify({ theme: 'aoi', language: 'de', voice: 'coral' }),
  'hoshi.homeTiles.uhr': 'false',
  'hoshi.homeTiles.wecker': 'false',
  'hoshi.homeTiles.wetter': 'false',
  'hoshi.homeTiles.laeuft': 'false',
  'hoshi.homeTiles.einkauf': 'false',
  'hoshi.homeTiles.vacuum': 'true',
  'hoshi.homeTiles.climate': 'false',
  'hoshi.homeTiles.currentAffairs': 'false',
  'hoshi.homeTiles.layout': JSON.stringify({ version: 1, order: [{ id: 'vacuum', size }] }),
});

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

const profile = mkdtempSync(join(tmpdir(), 'hoshi-shot-sauger-'));
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
const cleanup = () => child.kill('SIGKILL');
process.on('exit', cleanup);

let version = null;
for (let i = 0; i < 100; i++) {
  try {
    version = await (await fetch('http://127.0.0.1:9447/json/version')).json();
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

for (const step of STEPS) {
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  await send('Page.enable', {}, sessionId);
  await send('Runtime.enable', {}, sessionId);
  await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false }, sessionId);
  const seedJs = Object.entries(seedFor(step))
    .map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`)
    .join('');
  await send('Page.addScriptToEvaluateOnNewDocument', { source: seedJs }, sessionId);

  const seen = events.length;
  await send('Page.navigate', { url: BASE }, sessionId);
  for (let i = 0; i < 200; i++) {
    if (events.slice(seen).some((e) => e.method === 'Page.loadEventFired')) break;
    await sleep(50);
  }
  for (let i = 0; i < 120; i++) {
    const res = await send(
      'Runtime.evaluate',
      { expression: `document.querySelectorAll('.idle__tile').length > 0`, returnByValue: true },
      sessionId,
    );
    if (res.result.value === true) break;
    await sleep(100);
  }
  if (process.env.SHOT_CLICK === '1') {
    // Den ersten Tat-Knopf wirklich drücken — nur so sieht man die
    // Rückmeldezeile (Annahme ODER die 502-Meldung des Probe-Servers mit
    // `SAUGER_ACTION=fail`). Ein Bild ohne Klick zeigt sie nie.
    await send(
      'Runtime.evaluate',
      { expression: `document.querySelector('.idle__hometileaction')?.click()`, returnByValue: true },
      sessionId,
    );
    await sleep(500);
  }
  if (process.env.SHOT_EDIT === '1') {
    // Der Edit-Modus kommt über den Knopf der Leiste — kein zweiter Weg hinein,
    // sonst fotografiert man einen Zustand, den die App so nie einnimmt.
    await send(
      'Runtime.evaluate',
      { expression: `document.querySelector('.settings__arrangebtn, [data-edit-enter]')?.click()`, returnByValue: true },
      sessionId,
    );
    await sleep(400);
  }
  await sleep(900);

  const shot = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
  const out = join(OUT_DIR, `${TAG}-${step}-${W}x${H}.png`);
  writeFileSync(out, Buffer.from(shot.data, 'base64'));
  console.log(out);
  await send('Target.closeTarget', { targetId });
}

ws.close();
cleanup();
process.exit(0);

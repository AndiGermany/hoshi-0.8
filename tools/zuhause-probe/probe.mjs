/** Geometrie-Probe: wo sitzen die Blöcke wirklich? node probe.mjs [w] [h] */
import { spawn } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.SHOT_PORT ?? 8794);
const W = Number(process.argv[2] ?? 1366);
const H = Number(process.argv[3] ?? 1024);
const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

const profile = mkdtempSync(join(tmpdir(), 'hoshi-probe-'));
const child = spawn(
  CHROME,
  [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    '--force-device-scale-factor=1', '--no-first-run', '--no-default-browser-check',
    '--remote-debugging-port=9446', `--user-data-dir=${profile}`, 'about:blank',
  ],
  { stdio: 'ignore' },
);
process.on('exit', () => child.kill('SIGKILL'));

let version = null;
for (let i = 0; i < 100; i++) {
  try { version = await (await fetch('http://127.0.0.1:9446/json/version')).json(); break; }
  catch { await sleep(100); }
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

const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
await send('Page.enable', {}, sessionId);
await send('Runtime.enable', {}, sessionId);
await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false }, sessionId);
const SEED = {
  'hoshi.settings': JSON.stringify({ theme: 'aoi', language: 'de', voice: 'coral' }),
  'hoshi.homeTiles.layout': JSON.stringify({
    version: 1,
    order: [
      { id: 'wetter', size: 'M' }, { id: 'laeuft', size: 'M' }, { id: 'einkauf', size: 'M' },
      { id: 'vacuum', size: 'L' }, { id: 'climate', size: 'L' }, { id: 'news', size: 'M' },
    ],
  }),
};
await send('Page.addScriptToEvaluateOnNewDocument', {
  source: Object.entries(SEED).map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`).join(''),
}, sessionId);
const seen = events.length;
await send('Page.navigate', { url: `http://127.0.0.1:${PORT}/` }, sessionId);
for (let i = 0; i < 200; i++) {
  if (events.slice(seen).some((e) => e.method === 'Page.loadEventFired')) break;
  await sleep(50);
}
await sleep(1800);

const expr = `(() => {
  const box = (sel) => { const e = document.querySelector(sel); if (!e) return null;
    const r = e.getBoundingClientRect(); return {sel, top: Math.round(r.top), bottom: Math.round(r.bottom), left: Math.round(r.left), right: Math.round(r.right), h: Math.round(r.height), w: Math.round(r.width)}; };
  return {
    viewport: {w: innerWidth, h: innerHeight},
    doc: {scrollH: document.documentElement.scrollHeight},
    boxes: ['.app', '.app__main', '.idle', '.idle__tiles', '.idle__chips', '.voiceorb', '.voiceorb__tap', '.voiceorb__hint', '.idle__tile'].map(box),
    tileCount: document.querySelectorAll('.idle__tile').length,
    weatherTile: (() => { const e = document.querySelector('[data-widget-id="wetter"]'); if (!e) return null;
      const r = e.getBoundingClientRect(); const c = e.querySelector('.idle__now').getBoundingClientRect();
      return {tile: {w: Math.round(r.width), h: Math.round(r.height)}, content: {w: Math.round(c.width), h: Math.round(c.height)}}; })(),
  };
})()`;
const res = await send('Runtime.evaluate', { expression: expr, returnByValue: true }, sessionId);
console.log(JSON.stringify(res.result.value, null, 1));
ws.close();
child.kill('SIGKILL');
process.exit(0);

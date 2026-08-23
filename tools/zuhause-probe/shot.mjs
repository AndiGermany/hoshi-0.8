/**
 * Zuhause-Screenshots: ein eigener headless Chrome über CDP.
 *
 * Warum CDP statt `--screenshot`: die Bühne braucht einen gesetzten
 * localStorage (Kachel-Schalter, Layout/Stufen) BEVOR sie rendert — das geht
 * nur mit einem Skript, das vor dem Dokument läuft.
 *
 * Chrome beendet sich auf dieser Kiste nie von selbst → eigener
 * `--user-data-dir`, und am Ende wird NUR der eigene Kindprozess beendet.
 *
 * NUTZUNG: node shot.mjs <out-dir> <tag>
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.SHOT_PORT ?? 8794);
const BASE = `http://127.0.0.1:${PORT}/`;
const OUT_DIR = process.argv[2];
const TAG = process.argv[3] ?? 'shot';
const SIZES = [
  { w: 1366, h: 1024, name: '1366x1024' },
  { w: 834, h: 1112, name: '834x1112' },
];

/** Was im localStorage stehen soll, bevor die App das erste Mal rendert. */
const SEED = {
  // Nur Theme/Stimme — die ANZEIGESPRACHE steht nicht im localStorage, sie
  // kommt vom Backend (`api/languageSettings.ts`). Der Probe-Server liefert
  // den Endpoint nicht, also rendert die Probe immer Deutsch; die fünf
  // Sprachen prüft die Test-Suite (i18nsweep.test.tsx), nicht dieses Skript.
  // SHOT_THEME wählt die Szene (Vorgabe: Aoi, ein Tag-Thema mitten in der
  // Liste). Gebraucht seit dem Transparenz-Auftrag 22.08.: ein Urteil über
  // Durchsichtigkeit muss gegen die HELLSTE (Fuyubare) und die DUNKELSTE
  // (Yoru) Szene fallen, nicht gegen eine mittlere.
  'hoshi.settings': JSON.stringify({
    theme: process.env.SHOT_THEME ?? 'aoi',
    language: 'de',
    voice: 'coral',
  }),
  // DER FLAG, OHNE DEN `SHOT_THEME=yoru` STILL LÜGT. `loadSettings()` trägt eine
  // Einmal-Migration vom alten Default (2026-07-02, Aoi-Adopt): ein gespeichertes
  // `theme: 'yoru'` wird zu `'aoi'` umgeschrieben, SOLANGE dieser Flag fehlt —
  // und in einem frischen Chrome-Profil fehlt er immer. Der erste Yoru-Versuch
  // am 22.08. hat darum brav Aoi fotografiert, mit Aoi-Akzent und Aoi-Szene.
  // Gesetzt heißt: die Migration ist „schon gelaufen", die gesäte Wahl gilt.
  'hoshi.settings.aoi-migrated': '1',
  'hoshi.homeTiles.wetter': 'true',
  'hoshi.homeTiles.laeuft': 'true',
  'hoshi.homeTiles.einkauf': process.env.SHOT_VARIANT === 'tall' ? 'false' : 'true',
  'hoshi.homeTiles.vacuum': 'false',
  'hoshi.homeTiles.climate': 'false',
  'hoshi.homeTiles.currentAffairs': 'true',
  // Wetter auf M (Andis Bestellung 3) — der Rest bleibt bei seinen Defaults.
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

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

const profile = mkdtempSync(join(tmpdir(), 'hoshi-shot-'));
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
    '--remote-debugging-port=9445',
    `--user-data-dir=${profile}`,
    'about:blank',
  ],
  { stdio: 'ignore' },
);

const cleanup = () => child.kill('SIGKILL');
process.on('exit', cleanup);

/* ── winziger CDP-Client (Node-eigenes WebSocket) ─────────────────────────── */
let version = null;
for (let i = 0; i < 100; i++) {
  try {
    version = await (await fetch('http://127.0.0.1:9445/json/version')).json();
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
  // Auf die echte Bühne warten (Kacheln kommen erst nach den Fetches).
  for (let i = 0; i < 120; i++) {
    const res = await send(
      'Runtime.evaluate',
      {
        expression: `!!document.querySelector('.idle__chips') && document.querySelectorAll('.idle__tile').length > 0`,
        returnByValue: true,
      },
      sessionId,
    );
    if (res.result.value === true) break;
    await sleep(100);
  }
  await sleep(900); // Einblend-Animationen ausklingen lassen

  // SHOT_TAB=Chat|Räume|Aktivität — einen anderen Reiter fotografieren.
  // Der Reiter ist KEIN localStorage-Zustand (App.tsx hält ihn im State), er
  // muss also geklickt werden. Gebraucht seit dem Transparenz-Auftrag 22.08.:
  // die Compose-Leiste ist die zentrale Fläche des Chat-Reiters, und wer sie
  // ändert, muss sie auch ansehen — nicht nur ihre Kontrastzahl lesen.
  if (process.env.SHOT_TAB) {
    const label = JSON.stringify(process.env.SHOT_TAB);
    await send(
      'Runtime.evaluate',
      {
        expression: `(() => { const b = [...document.querySelectorAll('.nav__tab')]
          .find((e) => e.textContent.trim() === ${label});
          if (!b) throw new Error('Reiter ' + ${label} + ' nicht gefunden');
          b.click(); return true; })()`,
        returnByValue: true,
      },
      sessionId,
    );
    await sleep(900); // der Reiter-Wechsel remountet und blendet ein
  }

  const shot = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
  const out = join(OUT_DIR, `${TAG}-${size.name}.png`);
  writeFileSync(out, Buffer.from(shot.data, 'base64'));
  console.log(out);
  await send('Target.closeTarget', { targetId });
}

ws.close();
cleanup();
process.exit(0);

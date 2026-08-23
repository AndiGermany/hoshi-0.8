/**
 * **Kachel-Ausbau-Sonde (21.08.)** — fotografiert genau die zwei Bilder, über
 * die Andi urteilt: den **Sonnenbogen der Uhr-L** (Tag UND Nacht) und die
 * **Mehrtage-Zeile der Wetter-XL**. Zusätzlich misst sie die Fläche, die das
 * jeweilige Bild wirklich bekommt — ein Urteil „aus 2 m lesbar" braucht Pixel,
 * keine Vermutung.
 *
 * Warum eine eigene Datei neben `shot-xl.mjs`: jenes Skript stellt JEDE Kachel
 * auf **XL**. Die Uhr kann kein XL (`CLOCK_SIZES` in `homeWidgets.ts`), ihr
 * Bogen lebt auf **L** — und der Nacht-Zustand braucht außerdem eine zweite
 * Server-Fixture (`SHOT_SUN=night`), also eine zweite Sitzung. Beides hätte
 * `shot-xl.mjs` zu einem Schalter-Skript gemacht.
 *
 * Dieselben zwei Fallen wie überall in diesem Verzeichnis sind eingebaut:
 *  - Chrome beendet sich auf dieser Kiste nie von selbst ⇒ eigener
 *    `--user-data-dir` unter `os.tmpdir()`, am Ende stirbt NUR der eigene
 *    Kindprozess (und der eigene Server).
 *  - Die API-Antworten sind gefälscht, aber vertragstreu (`serve-xl.mjs`) —
 *    ein Feld, das der FE-Parser verwirft, ist auch dort keins.
 *
 * NUTZUNG: node kachel.mjs <dist-dir> <out-dir>
 *   SHOT_PORT (8796) · SHOT_CDP_PORT (9448) · SHOT_W/SHOT_H (1366×1024)
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const HERE = dirname(fileURLToPath(import.meta.url));
const DIST = process.argv[2];
const OUT_DIR = process.argv[3];
const PORT = Number(process.env.SHOT_PORT ?? 8796);
const CDP_PORT = Number(process.env.SHOT_CDP_PORT ?? 9448);
const BASE = `http://127.0.0.1:${PORT}/`;
const SIZE = { w: Number(process.env.SHOT_W ?? 1366), h: Number(process.env.SHOT_H ?? 1024) };

if (!DIST || !OUT_DIR) throw new Error('NUTZUNG: node kachel.mjs <dist-dir> <out-dir>');

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

/**
 * Die drei Bilder der Bestellung. `sun` wählt die Server-Fixture, also eine
 * eigene Sitzung je Wert — deshalb sind sie hier nach `sun` gruppiert.
 */
const SHOTS = [
  { name: 'uhr-L-tag', on: { uhr: 'L' }, sun: 'day', probe: '.idle__sunarc' },
  { name: 'uhr-L-nacht', on: { uhr: 'L' }, sun: 'night', probe: '.idle__sunarc' },
  { name: 'wetter-XL', on: { wetter: 'XL' }, sun: 'day', probe: '.idle__outlook' },
  // Der ehrliche Alltagsblick: die Bühne, wie Andi sie ohne Zutun sieht
  // (Registry-Defaults, mehrere Kacheln nebeneinander). EINE Kachel allein
  // bekommt 621 px Höhe — ein Bild, das es im Betrieb nie gibt, und genau
  // deshalb ein schlechter Maßstab für „trägt der Bogen seine Fläche".
  {
    name: 'buehne-normal',
    on: { uhr: 'L', wetter: 'L', laeuft: 'L', einkauf: 'M' },
    sun: 'day',
    probe: '.idle__sunarc',
  },
];

/** localStorage-Saat: NUR die Kacheln aus `on` an, jede in ihrer Stufe. */
function seedFor(on) {
  const seed = {
    'hoshi.settings': JSON.stringify({ theme: 'aoi', language: 'de', voice: 'coral' }),
    'hoshi.homeTiles.wecker': 'false',
    'hoshi.homeTiles.layout': JSON.stringify({
      version: 1,
      order: STAGE.map((id) => ({ id, size: on[id] ?? 'M' })),
    }),
  };
  for (const id of STAGE) seed[FLAG_KEY[id]] = String(on[id] !== undefined);
  return seed;
}

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

mkdirSync(OUT_DIR, { recursive: true });
const measured = [];

for (const sun of ['day', 'night']) {
  const wanted = SHOTS.filter((s) => s.sun === sun);
  if (wanted.length === 0) continue;

  // ── Server mit der passenden Sonnen-Fixture ──────────────────────────────
  // Auf die ECHTE Antwort warten, nicht auf den Prozessstart: der erste Lauf
  // dieser Sonde hat genau hier gelogen — der Port war noch vom Vorlauf belegt,
  // der neue Server band still nicht, und Chrome fotografierte eine Übersicht
  // mit „Wetter ist bei diesem Deploy ausgeschaltet" (404). Ein Bild, das
  // aussieht wie ein Feature-Zustand, aber ein Werkzeugfehler war.
  const server = spawn('node', [join(HERE, 'serve-xl.mjs'), DIST, String(PORT)], {
    stdio: 'ignore',
    env: { ...process.env, SHOT_SUN: sun },
  });
  let served = null;
  for (let i = 0; i < 100; i++) {
    try {
      const res = await fetch(`${BASE}api/v1/weather/today`);
      if (res.ok) {
        served = await res.json();
        break;
      }
    } catch {
      /* noch nicht oben */
    }
    await sleep(100);
  }
  if (!served) throw new Error(`Probe-Server auf ${PORT} antwortet nicht (Port belegt?)`);
  const istNacht = Date.now() < served.sunriseEpochMs || Date.now() > served.sunsetEpochMs;
  if ((sun === 'night') !== istNacht) {
    throw new Error(`Fixture „${sun}" liefert die falsche Tageszeit — SHOT_SUN kam nicht an`);
  }
  const profile = mkdtempSync(join(tmpdir(), 'hoshi-shot-kachel-'));
  const chrome = spawn(
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
  const cleanup = () => {
    chrome.kill('SIGKILL');
    server.kill('SIGKILL');
  };
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

  for (const shot of wanted) {
    const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
    await send('Page.enable', {}, sessionId);
    await send('Runtime.enable', {}, sessionId);
    await send(
      'Emulation.setDeviceMetricsOverride',
      { width: SIZE.w, height: SIZE.h, deviceScaleFactor: 1, mobile: false },
      sessionId,
    );
    const seedJs = Object.entries(seedFor(shot.on))
      .map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`)
      .join('');
    await send('Page.addScriptToEvaluateOnNewDocument', { source: seedJs }, sessionId);

    const seen = events.length;
    await send('Page.navigate', { url: BASE }, sessionId);
    for (let i = 0; i < 200; i++) {
      if (events.slice(seen).some((e) => e.method === 'Page.loadEventFired')) break;
      await sleep(50);
    }
    // Auf das ECHTE neue Bild warten, nicht nur auf irgendeine Kachel — sonst
    // fotografiert man den Zustand vor dem Wetter-Fetch.
    let appeared = false;
    for (let i = 0; i < 150; i++) {
      const res = await send(
        'Runtime.evaluate',
        { expression: `document.querySelector(${JSON.stringify(shot.probe)}) !== null`, returnByValue: true },
        sessionId,
      );
      if (res.result.value === true) {
        appeared = true;
        break;
      }
      await sleep(100);
    }
    await sleep(900); // Einblend-Animationen ausklingen lassen

    const geo = await send(
      'Runtime.evaluate',
      {
        expression: `(() => {
          const tile = document.querySelector('.idle__tile');
          const el = document.querySelector(${JSON.stringify(shot.probe)});
          const box = (n) => (n ? { w: Math.round(n.getBoundingClientRect().width), h: Math.round(n.getBoundingClientRect().height) } : null);
          const phase = document.querySelector('.idle__sunarc')?.dataset.phase ?? null;
          const days = document.querySelectorAll('.idle__outlookday').length;
          return { tile: box(tile), art: box(el), phase, days };
        })()`,
        returnByValue: true,
      },
      sessionId,
    );

    const png = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
    const out = join(OUT_DIR, `${shot.name}-${SIZE.w}x${SIZE.h}.png`);
    writeFileSync(out, Buffer.from(png.data, 'base64'));
    // Auf die geschriebene DATEI warten — ein Bild im Puffer kann niemand ansehen.
    for (let i = 0; i < 50 && statSync(out).size === 0; i++) await sleep(20);

    const g = geo.result.value;
    measured.push({ shot: shot.name, appeared, ...g, file: out });
    console.log(
      `${out}  Kachel ${g.tile ? `${g.tile.w}×${g.tile.h}` : '—'} · Bild ${
        g.art ? `${g.art.w}×${g.art.h}` : 'FEHLT'
      }${g.phase ? ` · ${g.phase}` : ''}${g.days ? ` · ${g.days} Tage` : ''}`,
    );
    await send('Target.closeTarget', { targetId });
  }

  ws.close();
  cleanup();
  // Warten, bis der Port wirklich frei ist — sonst bindet der nächste
  // Durchgang still nicht (s. oben).
  for (let i = 0; i < 100; i++) {
    try {
      await fetch(`${BASE}api/v1/weather/today`);
      await sleep(100);
    } catch {
      break;
    }
  }
}

writeFileSync(join(OUT_DIR, 'messung.json'), `${JSON.stringify(measured, null, 2)}\n`);

// Laut scheitern statt still ein falsches Bild abliefern: fehlt das gesuchte
// Element, ist der Screenshot wertlos — und sieht trotzdem plausibel aus.
const fehlend = measured.filter((m) => !m.appeared).map((m) => m.shot);
if (fehlend.length > 0) throw new Error(`Bild fehlte in: ${fehlend.join(', ')}`);
process.exit(0);

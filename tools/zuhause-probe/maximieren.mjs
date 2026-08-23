/**
 * **MAXIMIEREN — geht der Kasten auf, filtert er, und geht er wieder zu?**
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   cd frontend && VITE_API_BASE=http://127.0.0.1:8802 npm run build
 *   node ../tools/zuhause-probe/maximieren.mjs "$PWD/dist" [out-dir]
 *
 *   MAX_PORT=8802  MAX_CDP=9462  MAX_FENSTER=1366x1024
 *
 * ANDI 23.08.: „ich habe keine möglichkeit diese anzuzeigen oder die
 * nachrichten zu filtern. füge einen ‚maximieren' hier und beim wetter an."
 *
 * Was hier NICHT geprüft wird: ob es hübsch ist. Dafür sind die Bilder da, und
 * die sieht ein Mensch an. Geprüft werden die fünf Zusagen, die man ZÄHLEN
 * kann — jede als eigene Zeile mit `ok`:
 *
 *   1. Der Knopf ist da (und trägt ein 44-px-Ziel).
 *   2. Ein Klick öffnet den Kasten (`role=dialog`, `aria-modal`).
 *   3. Im Kasten stehen ALLE Meldungen — nicht die sechs der Kachel.
 *   4. Ein Quellen-Chip filtert wirklich (Zahl vorher/nachher).
 *   5. Escape schließt, und der Fokus ist nicht gefangen.
 *
 * Der Server ist `serve-xl.mjs`: seine Fixture führt mehr Meldungen, als die
 * Kachel je zeigt — sonst wäre „alle" und „sechs" dieselbe Zahl und Zusage 3
 * bewiese nichts.
 */

import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { writeFileSync, mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const DIST = process.argv[2];
if (!DIST) throw new Error('Aufruf: node maximieren.mjs <dist-dir> [out-dir]');
const OUT = process.argv[3] ?? null;
const PORT = Number(process.env.MAX_PORT ?? 8802);
const CDP = Number(process.env.MAX_CDP ?? 9462);
const [W, H] = (process.env.MAX_FENSTER ?? '1366x1024').split('x').map(Number);
const BASE = `http://127.0.0.1:${PORT}/`;
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const STAGE = ['uhr', 'wecker', 'wetter', 'laeuft', 'einkauf', 'vacuum', 'climate', 'news'];
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
/** Nachrichten und Wetter vorne, damit beide sicher auf Seite 1 stehen. */
const SEED = {
  'hoshi.settings': JSON.stringify({ theme: 'aoi', language: 'de', voice: 'coral' }),
  'hoshi.settings.aoi-migrated': '1',
  'hoshi.homeTiles.layout': JSON.stringify({
    version: 1,
    order: [
      { id: 'news', size: 'M' },
      { id: 'wetter', size: 'M' },
      ...STAGE.filter((id) => id !== 'news' && id !== 'wetter').map((id) => ({ id, size: 'M' })),
    ],
    placements: Object.fromEntries(
      [1, 2, 3, 4].map((c) => [String(c), { news: { col: 0, row: 0 } }]),
    ),
  }),
};
for (const id of STAGE) SEED[FLAG_KEY[id]] = 'true';

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

/** Belegter Port ⇒ Abbruch statt einer Messung an einer fremden App (s. schnitt.mjs). */
async function portFrei(port, wofuer) {
  await new Promise((ok, fail) => {
    const wache = createServer();
    wache.once('error', (e) => fail(new Error(`Port ${port} (${wofuer}) belegt [${e.code}] — MAX_PORT/MAX_CDP setzen.`)));
    wache.listen(port, '127.0.0.1', () => wache.close(() => ok()));
  });
}
await portFrei(PORT, 'Probe-Server');
await portFrei(CDP, 'Chrome-Fernsteuerung');

const profil = mkdtempSync(join(tmpdir(), 'hoshi-max-'));
const server = spawn(process.execPath, [join(HERE, 'serve-xl.mjs'), DIST, String(PORT)], { stdio: 'ignore' });
const chrome = spawn(
  CHROME,
  [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
    '--no-first-run', '--no-default-browser-check', `--remote-debugging-port=${CDP}`,
    `--user-data-dir=${profil}`, 'about:blank',
  ],
  { stdio: 'ignore' },
);
const ende = () => {
  try { chrome.kill('SIGKILL'); } catch { /* schon tot */ }
  try { server.kill('SIGKILL'); } catch { /* schon tot */ }
  // Der eben erschossene Chrome schreibt noch in sein Profil — ein ENOTEMPTY
  // hier ist Aufräum-Kosmetik und darf einen grünen Lauf nicht rot färben.
  try { rmSync(profil, { recursive: true, force: true }); } catch { /* Reste im tmpdir */ }
};
process.on('exit', ende);

for (let i = 0; i < 100; i++) {
  try { if ((await fetch(`${BASE}api/health`)).ok) break; } catch { /* noch nicht oben */ }
  await sleep(100);
}
let wsUrl = null;
for (let i = 0; i < 100 && wsUrl === null; i++) {
  try {
    const r = await fetch(`http://127.0.0.1:${CDP}/json/version`);
    if (r.ok) wsUrl = (await r.json()).webSocketDebuggerUrl;
  } catch { /* Chrome startet noch */ }
  if (wsUrl === null) await sleep(100);
}
const ws = new WebSocket(wsUrl);
await new Promise((ok, fail) => { ws.onopen = ok; ws.onerror = fail; });
let seq = 0;
const offen = new Map();
const wartend = [];
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && offen.has(m.id)) {
    const { ok, fail } = offen.get(m.id);
    offen.delete(m.id);
    m.error ? fail(new Error(m.error.message)) : ok(m.result);
  } else if (m.method) {
    for (let i = wartend.length - 1; i >= 0; i--) if (wartend[i].method === m.method) wartend.splice(i, 1)[0].ok(m.params);
  }
};
const send = (method, params = {}, sessionId) =>
  new Promise((ok, fail) => {
    const id = ++seq;
    offen.set(id, { ok, fail });
    ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
  });
const aufEreignis = (method, ms = 15000) => new Promise((ok) => { wartend.push({ method, ok }); setTimeout(ok, ms); });

const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
await send('Page.enable', {}, sessionId);
await send('Runtime.enable', {}, sessionId);
await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false }, sessionId);
await send(
  'Page.addScriptToEvaluateOnNewDocument',
  { source: Object.entries(SEED).map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`).join('') },
  sessionId,
);
await send('Page.navigate', { url: BASE }, sessionId);
await aufEreignis('Page.loadEventFired');
await sleep(1400);

const evalJs = async (expression) => {
  const res = await send('Runtime.evaluate', { expression, returnByValue: true }, sessionId);
  if (res.exceptionDetails) throw new Error(res.exceptionDetails.exception?.description ?? 'JS-Fehler');
  return res.result.value;
};
/** Echter Maus-Klick auf die Mitte eines Elements (kein `el.click()` — das umgeht Overlays). */
const klick = async (selector) => {
  const box = await evalJs(`(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
  })()`);
  if (box === null) throw new Error(`kein Element: ${selector}`);
  for (const type of ['mousePressed', 'mouseReleased']) {
    await send('Input.dispatchMouseEvent', { type, x: box.x, y: box.y, button: 'left', clickCount: 1 }, sessionId);
  }
  await sleep(450);
};
const shot = async (name) => {
  if (OUT === null) return;
  const png = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
  writeFileSync(join(OUT, `max-${name}-${W}x${H}.png`), Buffer.from(png.data, 'base64'));
};
const zustand = () =>
  evalJs(`(() => {
    const auf = document.querySelector('.overlay.is-open');
    const karte = auf ? auf.querySelector('.widgetmax') : null;
    const btn = document.querySelector('[data-widget-id="news"] .idle__maxbtn');
    const r = btn ? btn.getBoundingClientRect() : null;
    return {
      knopfDa: !!btn,
      knopfZiel: r ? Math.round(Math.min(r.width, r.height)) : 0,
      offen: !!auf,
      titel: karte ? (karte.querySelector('.widgetmax__title')?.textContent ?? '') : '',
      rolle: karte ? karte.getAttribute('role') : null,
      modal: karte ? karte.getAttribute('aria-modal') : null,
      meldungen: karte ? karte.querySelectorAll('.widgetmax__newsitem').length : 0,
      chips: karte ? [...karte.querySelectorAll('.widgetmax__chip')].map((c) => c.textContent.trim()) : [],
      bilanz: karte ? (karte.querySelector('.widgetmax__count')?.textContent ?? '') : '',
      abschnitte: karte ? [...karte.querySelectorAll('.widgetmax__sectitle')].map((h) => h.textContent) : [],
      kachelMeldungen: document.querySelectorAll('[data-widget-id="news"] .idle__newsitem').length,
      fokus: document.activeElement ? document.activeElement.className : '',
    };
  })()`);

const bericht = [];
const sag = (titel, daten) => {
  bericht.push({ titel, ...daten });
  console.log(`\n── ${titel}`);
  for (const [k, v] of Object.entries(daten)) console.log(`   ${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`);
};

/* 1 · Der Knopf */
const zu = await zustand();
await shot('0-zu');
sag('1 · Der Maximieren-Knopf an der Nachrichten-Kachel', {
  knopfDa: zu.knopfDa,
  knopfZiel: zu.knopfZiel,
  kachelMeldungen: zu.kachelMeldungen,
  offen: zu.offen,
  ok: zu.knopfDa && zu.knopfZiel >= 44 && !zu.offen,
});

/* 2+3 · Klick öffnet, und darin stehen ALLE Meldungen */
await klick('[data-widget-id="news"] .idle__maxbtn');
const auf = await zustand();
await shot('1-nachrichten');
sag('2 · Ein Klick öffnet den Kasten', {
  offen: auf.offen,
  titel: auf.titel,
  rolle: auf.rolle,
  modal: auf.modal,
  ok: auf.offen && auf.rolle === 'dialog' && auf.modal === 'true',
});
sag('3 · Im Kasten stehen ALLE Meldungen (die Kachel zeigt weniger)', {
  imKasten: auf.meldungen,
  aufDerKachel: auf.kachelMeldungen,
  bilanz: auf.bilanz,
  ok: auf.meldungen > auf.kachelMeldungen,
});

/* 4 · Ein Quellen-Chip filtert wirklich */
const chipSel = '.overlay.is-open .widgetmax__chip:nth-of-type(2)';
await klick(chipSel);
const gefiltert = await zustand();
await shot('2-gefiltert');
sag('4 · Ein Quellen-Chip filtert', {
  chips: auf.chips,
  vorher: auf.meldungen,
  nachher: gefiltert.meldungen,
  bilanz: gefiltert.bilanz,
  ok: gefiltert.meldungen > 0 && gefiltert.meldungen < auf.meldungen,
});

/* 5 · Escape schließt */
await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }, sessionId);
await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 }, sessionId);
await sleep(500);
const geschlossen = await zustand();
sag('5 · Escape schließt', { offen: geschlossen.offen, ok: !geschlossen.offen });

/* 6 · Dasselbe beim Wetter — hier zählen die ABSCHNITTE */
await klick('[data-widget-id="wetter"] .idle__maxbtn');
const wetter = await zustand();
await shot('3-wetter');
sag('6 · Wetter maximiert zeigt alles, was der Vertrag hergibt', {
  offen: wetter.offen,
  titel: wetter.titel,
  abschnitte: wetter.abschnitte,
  ok: wetter.offen && wetter.abschnitte.length === 4,
});

const rot = bericht.filter((s) => s.ok === false);
console.log(`\n${JSON.stringify({ viewport: `${W}x${H}`, schritte: bericht })}`);
ende();
process.exit(rot.length === 0 ? 0 : 1);

/**
 * **Flächen-Sonde (22.08.)** — beantwortet die EINE Frage, die Andis Auftrag
 * „die Uhr sieht in klein etwas verloren aus … ich möchte, dass das Widget
 * besser ausgefüllt ist" messbar macht: **wie viel von der Kachelfläche trägt
 * wirklich etwas?**
 *
 * `probe.mjs` misst genau EINE Kachel (Wetter) und nur ihren äußeren
 * Inhaltskasten; `w7.mjs` misst den Inhaltsanteil, aber nur in seinen zwei
 * W7-Szenen. Für „Uhr S/M/L und Wetter S/M/L/XL, vorher gegen nachher, in zwei
 * Fenstern" braucht es beides zusammen — und zwar für JEDE Kachel derselben
 * Bühne, damit man nicht eine leere Uhr gegen eine volle Bühne rechnet.
 *
 * **Was „Inhalt" hier heißt:** nicht der Kasten um alles (der ist bei einer
 * `justify-content: center`-Spalte fast immer gleich der Kachel), sondern die
 * VEREINIGTE Fläche aller Blatt-Elemente (Elemente ohne Element-Kinder, plus
 * SVG-Figuren), auf die Kachel beschnitten. Das ist die ehrliche Antwort auf
 * „ist da etwas oder ist da Luft": zwei Textzeilen mit 200 px Abstand füllen
 * ihre Kachel NICHT, auch wenn ihr gemeinsamer Kasten sie ausfüllt.
 *
 * Dieselben zwei Fallen wie überall hier sind eingebaut (eigener
 * `--user-data-dir`, es stirbt nur der eigene Kindprozess; vertragstreue
 * Fake-API aus `serve-xl.mjs`).
 *
 * NUTZUNG: node flaeche.mjs <dist-dir> <out-dir> [tag]
 *   SHOT_PORT (8797) · SHOT_CDP_PORT (9449)
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
const TAG = process.argv[4] ?? 'flaeche';
const PORT = Number(process.env.SHOT_PORT ?? 8797);
const CDP_PORT = Number(process.env.SHOT_CDP_PORT ?? 9449);
const BASE = `http://127.0.0.1:${PORT}/`;

if (!DIST || !OUT_DIR) throw new Error('NUTZUNG: node flaeche.mjs <dist-dir> <out-dir> [tag]');

const VIEWPORTS = [
  { w: 1366, h: 1024 },
  { w: 834, h: 1112 },
];

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
const STAGE = ['uhr', 'wecker', 'wetter', 'laeuft', 'einkauf', 'vacuum', 'climate', 'news'];

/**
 * Die Szenen. Jede stellt EINE Stufe in den Mittelpunkt, lässt aber genug
 * Nachbarn stehen, dass die Bühne realistisch eng ist — eine einzelne Kachel
 * allein bekommt die ganze Bühne und misst damit einen Zustand, den im Betrieb
 * niemand sieht (derselbe Fehler, den `kachel.mjs` in seinem Kopfkommentar
 * beschreibt).
 */
const SCENES = [
  { name: 'klein', on: { uhr: 'S', wecker: 'S', wetter: 'S', laeuft: 'M', einkauf: 'M', news: 'M' } },
  { name: 'mittel', on: { uhr: 'M', wecker: 'M', wetter: 'M', laeuft: 'M', einkauf: 'M', news: 'M' } },
  { name: 'gross', on: { uhr: 'L', wecker: 'M', wetter: 'L', laeuft: 'L', einkauf: 'M', news: 'L' } },
  { name: 'xl', on: { uhr: 'L', wetter: 'XL', news: 'XL' } },
];

function seedFor(on) {
  const seed = {
    'hoshi.settings': JSON.stringify({ theme: 'aoi', language: 'de', voice: 'coral' }),
    'hoshi.homeTiles.layout': JSON.stringify({
      version: 1,
      order: STAGE.filter((id) => on[id] !== undefined).map((id) => ({ id, size: on[id] })),
    }),
  };
  for (const id of STAGE) seed[FLAG_KEY[id]] = String(on[id] !== undefined);
  return seed;
}

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

/**
 * Der Messausdruck im Browser. Rechnet je Kachel die **Vereinigungsfläche**
 * der Blatt-Rechtecke per Scanline (Elementzahl je Kachel ist klein, also ist
 * O(n² log n) hier billiger als jede Cleverness).
 */
const MEASURE = `(() => {
  /**
   * Die ECHTEN Farbflecken einer Kachel: die Zeilenkästen jedes Textknotens
   * (per Range, nicht per Element!) plus die Rechtecke der Grafiken.
   *
   * Warum nicht einfach die Element-Rechtecke: ein \`<time>\` in einer Flex-
   * SPALTE wird blockifiziert und ist damit IMMER kachelbreit — die Uhr sähe
   * dadurch „gefüllt" aus, obwohl nur 130 px Ziffern dastehen, während die
   * Wetter-Zeilen (Flex-Kinder mit \`align-items: flex-start\`) ehrlich auf
   * Textbreite schrumpfen. Zwei Kacheln wären dann mit zwei verschiedenen
   * Maßstäben gemessen worden.
   */
  const rects = (tile) => {
    const out = [];
    const t = tile.getBoundingClientRect();
    const clip = (r) => {
      const x0 = Math.max(r.left, t.left), x1 = Math.min(r.right, t.right);
      const y0 = Math.max(r.top, t.top), y1 = Math.min(r.bottom, t.bottom);
      if (x1 - x0 > 0.5 && y1 - y0 > 0.5) out.push({ x0, x1, y0, y1 });
    };
    const GRAPHIC = new Set(['svg', 'img', 'canvas', 'video']);
    for (const el of tile.querySelectorAll('*')) {
      const cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0) continue;
      if (el.classList.contains('idle__sronly')) continue; // nur fürs Ohr
      if (GRAPHIC.has(el.tagName.toLowerCase())) { clip(el.getBoundingClientRect()); continue; }
      for (const node of el.childNodes) {
        if (node.nodeType !== 3 || !node.data.trim()) continue;
        const range = document.createRange();
        range.selectNodeContents(node);
        for (const r of range.getClientRects()) clip(r);
      }
    }
    return out;
  };
  const unionArea = (rs) => {
    if (rs.length === 0) return 0;
    const xs = [...new Set(rs.flatMap((r) => [r.x0, r.x1]))].sort((a, b) => a - b);
    let area = 0;
    for (let i = 0; i < xs.length - 1; i++) {
      const [a, b] = [xs[i], xs[i + 1]];
      const spans = rs.filter((r) => r.x0 <= a && r.x1 >= b).map((r) => [r.y0, r.y1]).sort((p, q) => p[0] - q[0]);
      let covered = 0, cur = null;
      for (const [y0, y1] of spans) {
        if (cur === null) { cur = [y0, y1]; continue; }
        if (y0 > cur[1]) { covered += cur[1] - cur[0]; cur = [y0, y1]; } else cur[1] = Math.max(cur[1], y1);
      }
      if (cur) covered += cur[1] - cur[0];
      area += covered * (b - a);
    }
    return area;
  };
  const px = (v) => Math.round(parseFloat(v) || 0);
  // Eine INAKTIVE Seite ist \`visibility: hidden\` (index.css) — ihre Kinder
  // melden dann zwar Rechtecke, aber jedes Blatt fiele durch den
  // Sichtbarkeits-Filter und die Kachel meldete „0 px² Inhalt". Beim ersten
  // Lauf dieser Sonde sah das aus wie ein Fehler der Kachel und war einer der
  // Sonde. Also: für die Dauer der Messung ALLE Seiten sichtbar schalten und
  // hinterher exakt den vorherigen Inline-Stil wiederherstellen.
  // \`transition: visibility 0s linear var(--dur-base)\` VERZÖGERT das Sichtbar-
  // werden (Absicht der Bühne: die abziehende Seite soll erst nach dem Gleiten
  // verschwinden). Ohne das Abschalten der Transition misst man 240 ms lang
  // weiter den unsichtbaren Zustand.
  const pages = [...document.querySelectorAll('.idle__page')];
  const before = pages.map((p) => [p.style.visibility, p.style.transition]);
  for (const p of pages) { p.style.transition = 'none'; p.style.visibility = 'visible'; }
  void document.body.offsetHeight; // Reflow erzwingen, sonst misst man den alten Zustand
  const tiles = [...document.querySelectorAll('.idle__tile')].map((tile) => {
    const r = tile.getBoundingClientRect();
    const rs = rects(tile);
    const ink = unionArea(rs);
    const clock = tile.querySelector('.idle__clock');
    const cond = tile.querySelector('.idle__nowcond');
    // Die EFFEKTIVE Stufe steht nicht auf der Kachel, sondern auf dem Inhalts-
    // Wurzelknoten (\`data-size\` bzw. \`data-step\` bei der Uhr) — genau der
    // Wert, den die Bühne wirklich gewählt hat, nicht der gespeicherte.
    const stepEl = tile.querySelector('[data-size], [data-step]');
    // Eine Kachel OHNE \`data-widget-id\` ist keine Kleinigkeit, sondern der
    // Befund selbst: die Bühne hängt ihren Griff (und ihre Rasterzelle) per
    // \`cloneElement\` an das Wurzelelement — wer die Id nicht trägt, ist für
    // Long-Press, Stufen-Wähler und Zellzuweisung unsichtbar. Darum wird der
    // Fall benannt (\`news*\`) statt weggefiltert.
    const named = tile.dataset.widgetId ?? null;
    const guess = tile.classList.contains('idle__news') ? 'news*' : '?';
    return {
      id: named ?? guess,
      hasHandle: named !== null,
      size: stepEl ? (stepEl.dataset.size ?? stepEl.dataset.step ?? null) : null,
      tile: { w: Math.round(r.width), h: Math.round(r.height) },
      inkPx: Math.round(ink),
      fill: Math.round((ink / Math.max(1, r.width * r.height)) * 1000) / 10,
      leaves: rs.length,
      clockPx: clock ? px(getComputedStyle(clock).fontSize) : null,
      clockW: clock ? Math.round(clock.getBoundingClientRect().width) : null,
      condPx: cond ? px(getComputedStyle(cond).fontSize) : null,
      overflow: Math.round(tile.scrollHeight - tile.clientHeight),
      overflowX: Math.round(tile.scrollWidth - tile.clientWidth),
    };
  });
  pages.forEach((p, i) => { [p.style.visibility, p.style.transition] = before[i]; });
  return { viewport: { w: innerWidth, h: innerHeight }, pages: pages.length, tiles };
})()`;

mkdirSync(OUT_DIR, { recursive: true });

const server = spawn('node', [join(HERE, 'serve-xl.mjs'), DIST, String(PORT)], { stdio: 'ignore' });
let up = false;
for (let i = 0; i < 100; i++) {
  try {
    if ((await fetch(`${BASE}api/v1/weather/today`)).ok) { up = true; break; }
  } catch { /* noch nicht oben */ }
  await sleep(100);
}
if (!up) throw new Error(`Probe-Server auf ${PORT} antwortet nicht (Port belegt?)`);

const profile = mkdtempSync(join(tmpdir(), 'hoshi-flaeche-'));
const chrome = spawn(
  CHROME,
  [
    '--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
    '--force-device-scale-factor=1', '--no-first-run', '--no-default-browser-check',
    `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${profile}`, 'about:blank',
  ],
  { stdio: 'ignore' },
);
const cleanup = () => { chrome.kill('SIGKILL'); server.kill('SIGKILL'); };
process.on('exit', cleanup);

let version = null;
for (let i = 0; i < 100; i++) {
  try { version = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`)).json(); break; }
  catch { await sleep(100); }
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

const all = [];
for (const vp of VIEWPORTS) {
  for (const scene of SCENES) {
    const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
    await send('Page.enable', {}, sessionId);
    await send('Runtime.enable', {}, sessionId);
    await send('Emulation.setDeviceMetricsOverride', { width: vp.w, height: vp.h, deviceScaleFactor: 1, mobile: false }, sessionId);
    const seedJs = Object.entries(seedFor(scene.on))
      .map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`)
      .join('');
    await send('Page.addScriptToEvaluateOnNewDocument', { source: seedJs }, sessionId);

    const seen = events.length;
    await send('Page.navigate', { url: BASE }, sessionId);
    for (let i = 0; i < 200; i++) {
      if (events.slice(seen).some((e) => e.method === 'Page.loadEventFired')) break;
      await sleep(50);
    }
    // Auf die ECHTE Bühne warten: so viele Kacheln, wie die Szene bestellt hat.
    const wanted = Object.keys(scene.on).length;
    let ready = false;
    for (let i = 0; i < 150; i++) {
      const res = await send('Runtime.evaluate', {
        expression: `document.querySelectorAll('.idle__tile').length >= ${wanted}`,
        returnByValue: true,
      }, sessionId);
      if (res.result.value === true) { ready = true; break; }
      await sleep(100);
    }
    await sleep(900); // Einblend-Animationen ausklingen lassen

    const res = await send('Runtime.evaluate', { expression: MEASURE, returnByValue: true }, sessionId);
    const value = res.result.value;

    /**
     * **JEDE Seite wird fotografiert, nicht nur die erste.** Eine dichte Bühne
     * hat zwei oder drei Seiten, und ausgerechnet die Kachel, über die geurteilt
     * werden soll, landet gern auf Seite 2 — ein Bild von Seite 1 zeigt sie dann
     * schlicht nicht, sieht aber vollständig aus. Umgeschaltet wird per Hand am
     * DOM (Schiene verschieben + `data-active` setzen, Transitions aus), weil ein
     * Klick auf einen Seitenpunkt eine Animation startet, die man abwarten müsste.
     */
    const files = [];
    for (let p = 0; p < Math.max(1, value.pages); p++) {
      if (value.pages > 1) {
        await send('Runtime.evaluate', {
          expression: `(() => {
            const track = document.querySelector('.idle__pages');
            const pages = [...document.querySelectorAll('.idle__page')];
            track.style.transition = 'none';
            track.style.transform = 'translate3d(${-p * 100}%, 0, 0)';
            pages.forEach((el, i) => { el.style.transition = 'none'; el.dataset.active = i === ${p} ? 'true' : 'false'; });
          })()`,
          returnByValue: true,
        }, sessionId);
        await sleep(200);
      }
      const png = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
      const suffix = value.pages > 1 ? `-s${p + 1}` : '';
      const file = join(OUT_DIR, `${TAG}-${scene.name}-${vp.w}x${vp.h}${suffix}.png`);
      writeFileSync(file, Buffer.from(png.data, 'base64'));
      for (let i = 0; i < 50 && statSync(file).size === 0; i++) await sleep(20);
      files.push(file);
    }


    all.push({ scene: scene.name, viewport: `${vp.w}x${vp.h}`, ready, files, tiles: value.tiles });
    for (const t of value.tiles) {
      console.log(
        `${vp.w}x${vp.h} ${scene.name.padEnd(7)} ${t.id.padEnd(8)} ${String(t.size).padEnd(3)} ` +
        `Kachel ${String(t.tile.w).padStart(4)}×${String(t.tile.h).padStart(4)} · Inhalt ${String(t.inkPx).padStart(6)} px² ` +
        `= ${String(t.fill).padStart(5)} %` +
        (t.clockPx ? ` · Uhr-Typo ${t.clockPx} px (${t.clockW} br.)` : '') +
        (t.condPx ? ` · Lage-Typo ${t.condPx} px` : '') +
        (t.hasHandle ? '' : ' · OHNE data-widget-id') +
        (t.overflow > 1 ? ` · ÜBERLAUF↕ ${t.overflow} px` : '') +
        (t.overflowX > 1 ? ` · ÜBERLAUF↔ ${t.overflowX} px` : ''),
      );
    }
    console.log(`  → ${files.join('\n  → ')}`);
    await send('Target.closeTarget', { targetId });
  }
}

writeFileSync(join(OUT_DIR, `${TAG}-messung.json`), `${JSON.stringify(all, null, 2)}\n`);
ws.close();
cleanup();

// Laut scheitern statt still falsch messen: fehlt eine bestellte Kachel, ist
// die ganze Zeile wertlos und sieht trotzdem plausibel aus.
const fehlend = all.filter((a) => !a.ready).map((a) => `${a.scene}@${a.viewport}`);
if (fehlend.length > 0) throw new Error(`Bühne war unvollständig in: ${fehlend.join(', ')}`);
process.exit(0);

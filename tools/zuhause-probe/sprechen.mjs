/**
 * **Die Sprech-Sonde** — verschiebt das Sprech-Overlay die Kacheln?
 *
 * Andi (23.08. früh, wörtlich): *„Das overlay für das eingesprochene und
 * ausgegebene auf dem homescreen verschiebt wieder die größe von allen
 * widgets."*
 *
 * Diese Sonde beantwortet genau das, und zwar als ZAHLEN: sie schreibt die
 * Kachel-Rechtecke der Bühne **vor**, **während** und **nach** einem echten
 * Turn auf und vergleicht ihre Unterschriften. Eine Unterschrift ist die
 * Liste aller `data-widget-id`-Rechtecke der sichtbaren Seite (plus Spalten,
 * Zeilen, Seitenzahl) — ändert sich EIN Pixel, ändert sich die Unterschrift.
 * Die Zusage der Scheibe lautet: **über den ganzen Turn hinweg genau EINE**.
 *
 * **Warum ein echter Turn und kein injizierter Kasten.** Das Overlay hängt an
 * `session.turns`; ein ins DOM gehängter `div.voiceorb__card` misst das Markup
 * der Sonde, nicht das der App — und würde nach dem Umbau ein Gespenst messen.
 * Der Turn läuft darum wirklich: im Chat-Reiter getippt, gegen den SSE-Vertrag
 * von `serve.mjs` (`start`/`delta`/`done`, Formen aus `api/types.ts`), und die
 * Blase steht danach auf Zuhause, weil App.tsx EINE Session an beide Reiter
 * reicht. Was hier gemessen wird, sieht Andi genauso.
 *
 * **Beide Engines aus EINER Datei.** Chrome spricht CDP, Firefox WebDriver
 * BiDi — gebraucht werden aber nur drei Dinge (Viewport, `evaluate`, Bild).
 * Zwei Dateien würden beim ersten Feinschliff auseinanderlaufen und die
 * Aussage „in Firefox ist es anders" wertlos machen (dieselbe Lehre wie in
 * `firefox.mjs`: zwei Engines dürfen nicht zwei verschiedene Wohnzimmer
 * messen).
 *
 * NUTZUNG: node sprechen.mjs [breite] [hoehe]
 *   SPRECH_ENGINE=chrome|firefox|beide   (Vorgabe: beide)
 *   SPRECH_PORT=8798                     Port des `serve.mjs`
 *   SPRECH_SEED=w7|v1                    Layout-Saat (Vorgabe w7 = Andis Form)
 *   SPRECH_SHOTS=<dir>                   wenn gesetzt: Bilder je Zustand
 *   SPRECH_JSON=<datei>                  Bericht als JSON dorthin
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const FIREFOX = '/Applications/Firefox.app/Contents/MacOS/firefox';
const PORT = Number(process.env.SPRECH_PORT ?? 8798);
const BASE = `http://127.0.0.1:${PORT}/`;
const W = Number(process.argv[2] ?? 1366);
const H = Number(process.argv[3] ?? 1024);
const WANT = process.env.SPRECH_ENGINE ?? 'beide';
const SEED_KIND = process.env.SPRECH_SEED === 'v1' ? 'v1' : 'w7';
const SHOTS = process.env.SPRECH_SHOTS ?? '';
const JSON_OUT = process.env.SPRECH_JSON ?? '';

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

/* ── Die Saat — Zeichen für Zeichen die aus `touch.mjs`/`firefox.mjs` ─────── */
const ORDER_V1 = [
  { id: 'wetter', size: 'L' }, { id: 'laeuft', size: 'L' }, { id: 'einkauf', size: 'L' },
  { id: 'vacuum', size: 'L' }, { id: 'climate', size: 'L' }, { id: 'news', size: 'L' },
];
const ORDER_W7 = [
  { id: 'uhr', size: 'L' }, { id: 'wecker', size: 'L' }, { id: 'wetter', size: 'XL' },
  { id: 'laeuft', size: 'M' }, { id: 'einkauf', size: 'M' }, { id: 'vacuum', size: 'XL' },
  { id: 'climate', size: 'L' }, { id: 'news', size: 'M' },
];
const PLACEMENTS_W7 = {
  2: { uhr: { col: 0, row: 0 }, wecker: { col: 1, row: 0 }, wetter: { col: 0, row: 1 },
       laeuft: { col: 0, row: 3 }, einkauf: { col: 1, row: 3 }, vacuum: { col: 0, row: 4 },
       climate: { col: 0, row: 6 }, news: { col: 1, row: 6 } },
  3: { uhr: { col: 0, row: 0 }, wecker: { col: 2, row: 0 }, wetter: { col: 0, row: 1 },
       laeuft: { col: 2, row: 1 }, einkauf: { col: 2, row: 2 }, vacuum: { col: 0, row: 3 },
       climate: { col: 0, row: 5 }, news: { col: 2, row: 5 } },
  4: { uhr: { col: 0, row: 0 }, wecker: { col: 3, row: 0 }, wetter: { col: 0, row: 1 },
       laeuft: { col: 2, row: 1 }, einkauf: { col: 3, row: 1 }, vacuum: { col: 0, row: 3 },
       climate: { col: 2, row: 3 }, news: { col: 2, row: 5 } },
};
const SEED = {
  'hoshi.settings': JSON.stringify({ theme: 'aoi', language: 'de', voice: 'coral' }),
  'hoshi.homeTiles.wetter': 'true',
  'hoshi.homeTiles.laeuft': 'true',
  'hoshi.homeTiles.einkauf': 'true',
  'hoshi.homeTiles.vacuum': 'true',
  'hoshi.homeTiles.climate': 'true',
  'hoshi.homeTiles.currentAffairs': 'true',
  'hoshi.homeTiles.layout': JSON.stringify(
    SEED_KIND === 'w7'
      ? { version: 1, order: ORDER_W7, placements: PLACEMENTS_W7 }
      : { version: 1, order: ORDER_V1 },
  ),
};
const seedJs =
  Object.entries(SEED)
    .map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`)
    .join('') + '"ok"';

/* ══ Engine-Treiber ════════════════════════════════════════════════════════ */

/** Chrome über CDP (wie `touch.mjs`) — eigener `--user-data-dir`, eigener Tod. */
async function startChrome() {
  const profile = mkdtempSync(join(tmpdir(), 'hoshi-sprech-'));
  const debugPort = Number(process.env.SPRECH_CDP_PORT ?? 9455);
  const child = spawn(
    CHROME,
    ['--headless=new', '--no-sandbox', '--disable-gpu', '--hide-scrollbars',
     '--force-device-scale-factor=1', '--no-first-run', '--no-default-browser-check',
     `--remote-debugging-port=${debugPort}`, `--user-data-dir=${profile}`, 'about:blank'],
    { stdio: 'ignore' },
  );
  let version = null;
  for (let i = 0; i < 120; i++) {
    try {
      version = await (await fetch(`http://127.0.0.1:${debugPort}/json/version`)).json();
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
  ws.onmessage = (m) => {
    const msg = JSON.parse(m.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
    }
  };
  const send = (method, params = {}, sessionId) =>
    new Promise((resolve, reject) => {
      const id = ++seq;
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
    });
  const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
  const cmd = (method, params) => send(method, params, sessionId);
  await cmd('Page.enable');
  await cmd('Runtime.enable');
  await cmd('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false });
  await cmd('Page.addScriptToEvaluateOnNewDocument', { source: seedJs });
  await cmd('Page.navigate', { url: BASE });
  const evalJs = async (expression) => {
    const res = await cmd('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (res.exceptionDetails) throw new Error(JSON.stringify(res.exceptionDetails).slice(0, 400));
    return res.result.value;
  };
  return {
    name: 'chrome',
    evalJs,
    // Objekte reisen als JSON — nicht aus Sparsamkeit, sondern weil BiDi einen
    // Objekt-Rückgabewert als getippten Baum liefert. Beide Engines müssen aus
    // EINEM Ausdruck DENSELBEN Node-Wert machen, sonst misst man die Protokolle
    // statt die Seite.
    evalJson: async (expression) => JSON.parse(await evalJs(`JSON.stringify(${expression})`)),
    async shot(file) {
      const png = await cmd('Page.captureScreenshot', { format: 'png' });
      writeFileSync(file, Buffer.from(png.data, 'base64'));
    },
    close() {
      ws.close();
      child.kill('SIGKILL');
    },
  };
}

/** Firefox über WebDriver BiDi (wie `firefox.mjs`) — kein geckodriver nötig. */
async function startFirefox() {
  const profile = mkdtempSync(join(tmpdir(), 'hoshi-sprech-ff-'));
  const bidiPort = Number(process.env.SPRECH_BIDI_PORT ?? 9456);
  const child = spawn(
    FIREFOX,
    ['--headless', '--no-remote', '--profile', profile, `--remote-debugging-port=${bidiPort}`, 'about:blank'],
    { stdio: 'ignore' },
  );
  let ws = null;
  for (let i = 0; i < 150; i++) {
    try {
      const probe = new WebSocket(`ws://127.0.0.1:${bidiPort}/session`);
      await new Promise((ok, no) => {
        probe.onopen = ok;
        probe.onerror = no;
      });
      ws = probe;
      break;
    } catch {
      await sleep(200);
    }
  }
  if (!ws) throw new Error('Firefox BiDi kam nicht hoch');
  let seq = 0;
  const pending = new Map();
  ws.onmessage = (m) => {
    const msg = JSON.parse(m.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.type === 'success' ? resolve(msg.result) : reject(new Error(JSON.stringify(msg).slice(0, 400)));
    }
  };
  const bidi = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = ++seq;
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify({ id, method, params }));
    });
  /*
   * **Der Zombie-Firefox.** Diese Sonde tötet am Ende nur ihren EIGENEN
   * Kindprozess (Hausregel: nie einen fremden Browser anfassen). Wird der Lauf
   * abgebrochen, bevor er dort ankommt, bleibt ein headless Firefox stehen und
   * hält den BiDi-Port — der nächste Lauf scheitert dann mit „Maximum number
   * of active sessions", und das sagt einem nichts. Hier steht, was zu tun
   * ist, statt eines Stacktraces.
   */
  try {
    await bidi('session.new', { capabilities: { alwaysMatch: { acceptInsecureCerts: true } } });
  } catch (err) {
    if (/Maximum number of active sessions/.test(String(err.message))) {
      throw new Error(
        'Firefox hält noch eine Sitzung aus einem abgebrochenen Lauf. ' +
          'Aufräumen mit:  pkill -f hoshi-sprech-ff',
      );
    }
    throw err;
  }
  const tree = await bidi('browsingContext.getTree', {});
  const context = tree.contexts[0].context;
  await bidi('browsingContext.setViewport', { context, viewport: { width: W, height: H } });
  const evalJs = async (expression) => {
    const res = await bidi('script.evaluate', {
      expression, target: { context }, awaitPromise: true, resultOwnership: 'none',
    });
    if (res.type === 'exception') throw new Error(JSON.stringify(res.exceptionDetails).slice(0, 400));
    const v = res.result;
    return v?.type === 'undefined' || v?.type === 'null' ? null : v?.value ?? null;
  };
  // Saat: erst laden (der Origin muss stehen), dann schreiben, dann neu laden.
  await bidi('browsingContext.navigate', { context, url: BASE, wait: 'complete' });
  await evalJs(seedJs);
  await bidi('browsingContext.reload', { context, wait: 'complete' });
  return {
    name: 'firefox',
    evalJs,
    evalJson: async (expression) => JSON.parse(await evalJs(`JSON.stringify(${expression})`)),
    async shot(file) {
      const res = await bidi('browsingContext.captureScreenshot', { context });
      writeFileSync(file, Buffer.from(res.data, 'base64'));
    },
    close() {
      ws.close();
      child.kill('SIGKILL');
    },
  };
}

/* ══ Die Messung — EIN Ausdruck, den beide Engines auswerten ═══════════════ */

/**
 * Die **Unterschrift** eines Bildschirms: jedes Kachel-Rechteck der sichtbaren
 * Seite, dazu Spalten/Zeilen/Seiten und die Kästen der drei Etagen. Alles auf
 * ganze Pixel gerundet — ein Rechteck ist eine Zusage, keine Fließkommazahl.
 *
 * `blase` sagt, OB das Sprech-Overlay gerade steht; `fluss` sagt, ob es
 * überhaupt Fluss-Platz nimmt (`position` seines Kastens). Der zweite Wert ist
 * die eigentliche Diagnose: ein `static` über der Bühne ist die Ursache, ein
 * `absolute` ist der Fix.
 */
const MESSEN = `(() => {
  const box = (el) => { if (!el) return null; const r = el.getBoundingClientRect();
    return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }; };
  const pages = [...document.querySelectorAll('.idle__page')];
  const active = Math.max(0, pages.findIndex((p) => p.dataset.active === 'true'));
  // JEDE Kachel, nicht nur die der sichtbaren Seite: „alle Widgets ändern ihre
  // Größe" ist eine Aussage über alle acht. Die Nachbarseiten stehen links und
  // rechts daneben (x = −774 …) — auch das ist ein festes Rechteck, und wenn es
  // sich bewegt, hat sich die Bühne bewegt.
  const kacheln = {};
  const alle = {};
  pages.forEach((p, i) => {
    for (const t of p.querySelectorAll('[data-widget-id]')) {
      const id = t.getAttribute('data-widget-id');
      if (id && kacheln[id] === undefined) {
        kacheln[id] = box(t);
        alle[id] = i;
      }
    }
  });
  const karte = document.querySelector('.voiceorb__card');
  const spalten = pages[active]
    ? getComputedStyle(pages[active]).gridTemplateColumns.split(/\\s+/).map((v) => Math.round(parseFloat(v))).filter((v) => v > 0)
    : [];
  const zeilen = pages[active]
    ? getComputedStyle(pages[active]).gridTemplateRows.split(/\\s+/).map((v) => Math.round(parseFloat(v))).filter((v) => v > 0)
    : [];
  const doc = document.scrollingElement || document.documentElement;
  return {
    kacheln, wo: alle, spalten, zeilen,
    seiten: pages.length, aktiv: active,
    buehne: box(document.querySelector('.idle__stage')),
    kasten: box(document.querySelector('.idle__tiles')),
    schiene: box(document.querySelector('.idle__pages')),
    idle: box(document.querySelector('.idle')),
    orb: box(document.querySelector('.voiceorb')),
    orbtap: box(document.querySelector('.voiceorb__tap')),
    orbhint: box(document.querySelector('.voiceorb__hint')),
    fuss: box(document.querySelector('.homefoot')),
    // WER ragt aus dem Fenster? Ein scrollHeight über innerHeight nennt nur
    // die Verletzung, nicht den Täter.
    ueberstand: [...document.querySelectorAll('.app *')]
      .filter((el) => el.getBoundingClientRect().bottom > window.innerHeight + 0.5)
      .slice(0, 5)
      .map((el) => (typeof el.className === 'string' && el.className.trim()
        ? '.' + el.className.trim().split(/\\s+/)[0] : el.tagName)
        + ' bottom=' + Math.round(el.getBoundingClientRect().bottom)),
    blase: !!karte,
    blaseBox: box(karte),
    blaseFluss: karte ? getComputedStyle(karte).position : null,
    schicht: box(document.querySelector('.voiceorb__say')),
    schichtFluss: (() => {
      const s = document.querySelector('.voiceorb__say');
      return s ? getComputedStyle(s).position + '/' + getComputedStyle(s).pointerEvents : null;
    })(),
    fenster: { w: window.innerWidth, h: window.innerHeight },
    // Ein-Fenster-Vertrag: das Dokument darf auf Zuhause NIE scrollen.
    scrollHeight: doc ? Math.round(doc.scrollHeight) : null,
    scrollTop: doc ? Math.round(doc.scrollTop) : null,
  };
})()`;

/** Nur die Teile, die „die Kacheln haben sich bewegt" beantworten. */
const unterschrift = (m) =>
  JSON.stringify({
    kacheln: m.kacheln, wo: m.wo, spalten: m.spalten, zeilen: m.zeilen,
    seiten: m.seiten, kasten: m.kasten, schiene: m.schiene,
  });

/**
 * **Hält der Text auf der Blase AA?** Kein Schätzwert: die Sonde stapelt die
 * echten Hintergründe aller Vorfahren (mit ihren Alphas) zu EINER Farbe und
 * rechnet den WCAG-Kontrast gegen die echte Textfarbe. `backdrop-filter` wird
 * mitgemeldet — die Transparenz-Regel des Hauses verbietet ihn.
 */
const AA = `(() => {
  const karte = document.querySelector('.voiceorb__card');
  if (!karte) return null;
  const zahl = (c) => {
    const m = /rgba?\\(([^)]+)\\)/.exec(c);
    if (!m) return null;
    const p = m[1].split(',').map((v) => parseFloat(v));
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  };
  const ueber = (vorn, hinten) => ({
    r: vorn.r * vorn.a + hinten.r * (1 - vorn.a),
    g: vorn.g * vorn.a + hinten.g * (1 - vorn.a),
    b: vorn.b * vorn.a + hinten.b * (1 - vorn.a),
    a: 1,
  });
  const lum = (c) => {
    const f = (v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
    return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
  };
  const grund = (el) => {
    let acc = null;
    for (let n = el; n; n = n.parentElement) {
      const c = zahl(getComputedStyle(n).backgroundColor);
      if (!c || c.a === 0) continue;
      acc = acc === null ? c : ueber(acc, c);
      if (acc.a >= 0.999) break;
    }
    // Ganz hinten steht die Szene bzw. das Papier des Themas — als letzte
    // Instanz die Körperfarbe, sonst wäre die Rechnung offen.
    if (!acc || acc.a < 0.999) {
      const b = zahl(getComputedStyle(document.body).backgroundColor) || { r: 255, g: 255, b: 255, a: 1 };
      acc = acc === null ? b : ueber(acc, { ...b, a: 1 });
    }
    return acc;
  };
  const zeilen = [...karte.querySelectorAll('.voiceorb__row')];
  const out = [];
  for (const z of zeilen) {
    const fg = zahl(getComputedStyle(z).color);
    const bg = grund(z);
    const l1 = lum(fg), l2 = lum(bg);
    const ratio = (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    out.push({
      klasse: z.className.trim().split(/\\s+/).slice(-1)[0],
      text: getComputedStyle(z).color,
      grund: 'rgb(' + [bg.r, bg.g, bg.b].map((v) => Math.round(v)).join(',') + ')',
      kontrast: Math.round(ratio * 100) / 100,
      schrift: getComputedStyle(z).fontSize,
    });
  }
  const filter = (() => {
    for (let n = karte; n; n = n.parentElement) {
      const s = getComputedStyle(n);
      const f = s.backdropFilter || s.webkitBackdropFilter;
      if (f && f !== 'none') return n.className + ' → ' + f;
    }
    return null;
  })();
  return { zeilen: out, backdropFilter: filter, deckkraft: getComputedStyle(karte).backgroundColor };
})()`;

/* ══ Der Ablauf ════════════════════════════════════════════════════════════ */

const KLICK_TAB = (i) => `(() => {
  const b = document.querySelectorAll('.nav__tab')[${i}];
  if (!b) return 'KEIN REITER ' + ${i};
  b.click();
  return 'ok';
})()`;

/** Text ins Chat-Feld schreiben, wie eine Tastatur es täte (React hört auf `input`). */
const TIPPEN = (text) => `(() => {
  const ta = document.querySelector('.compose__input');
  if (!ta) return 'KEIN FELD';
  const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
  setter.call(ta, ${JSON.stringify(text)});
  ta.dispatchEvent(new Event('input', { bubbles: true }));
  return 'ok';
})()`;

const SENDEN = `(() => {
  const b = document.querySelector('.compose__send');
  if (!b) return 'KEIN KNOPF';
  if (b.disabled) return 'GESPERRT';
  b.click();
  return 'ok';
})()`;

async function lauf(engine) {
  const report = {
    engine: engine.name,
    fenster: `${W}x${H}`,
    saat: SEED_KIND,
    rueckbau: process.env.SPRECH_RUECKBAU === '1',
    schritte: [],
  };
  const say = (titel, daten) => {
    report.schritte.push({ titel, ...daten });
    console.log(`\n── [${engine.name}] ${titel}`);
    for (const [k, v] of Object.entries(daten)) {
      console.log(`   ${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`);
    }
  };
  const shot = async (name) => {
    if (!SHOTS) return;
    mkdirSync(SHOTS, { recursive: true });
    // Der Rückbau bekommt einen eigenen Namen — sonst überschreibt die
    // Gegenprobe den Beweis, den sie belegen soll.
    const tag = process.env.SPRECH_RUECKBAU === '1' ? 'rueckbau-' : '';
    await engine.shot(join(SHOTS, `${tag}${engine.name}-${name}-${W}x${H}.png`));
  };

  /**
   * **Warten, bis die Bühne WIRKLICH steht.** Nicht „bis Kacheln da sind": die
   * Kacheln von `laeuft`/`einkauf`/`news` entstehen erst, wenn ihre Antwort da
   * ist, und eine Bühne, der noch drei Kacheln fehlen, hat eine andere
   * Unterschrift als dieselbe Bühne zwei Sekunden später. Ein zu früh
   * gemessenes „vorher" würde diesen Unterschied dem Overlay anhängen.
   * Verlangt sind darum VIER gleiche Messungen in Folge (≈1 s Stillstand).
   */
  const einschwingen = async (max = 60) => {
    let letzte = '';
    let gleich = 0;
    for (let i = 0; i < max; i++) {
      const jetzt = unterschrift(await engine.evalJson(MESSEN));
      if (jetzt === letzte) {
        if (++gleich >= 3) return jetzt;
      } else {
        gleich = 0;
        letzte = jetzt;
      }
      await sleep(250);
    }
    return letzte;
  };
  for (let i = 0; i < 150; i++) {
    if (await engine.evalJs(`document.querySelectorAll('.idle__tile').length > 0`)) break;
    await sleep(100);
  }

  /**
   * **Der Rückbau** (`SPRECH_RUECKBAU=1`) — die Gegenprobe aus EINEM Bau.
   *
   * Stellt den Zustand VOR dem Fix vom 23.08. wieder her, in BEIDEN Engines,
   * ohne zweiten Bau und ohne `git stash`. `display: contents` an der Schicht
   * ist dabei der Kern: die Schicht verschwindet als Kasten, ihre Kinder werden
   * wieder unmittelbare Flex-Kinder des Orb-Blocks — genau die Struktur, die
   * bis zum 23.08. im Code stand. (Ein bloßes `position: static` an der Schicht
   * wäre NICHT dasselbe: der Kasten bliebe stehen und die Blase könnte sich
   * darin ausdehnen, statt wie früher vom Flex-Algorithmus gestaucht zu
   * werden.) Ohne diesen Schalter müsste man dem Satz „vorher war es kaputt"
   * glauben; mit ihm ist er jederzeit nachstellbar, auch in einem halben Jahr.
   */
  if (process.env.SPRECH_RUECKBAU === '1') {
    await engine.evalJs(`(() => {
      const s = document.createElement('style');
      s.id = 'sprech-rueckbau';
      s.textContent = [
        '.voiceorb__say { display: contents !important; }',
        '.voiceorb__card { margin: 8px 0 0 !important; box-shadow: none !important;',
        '  background: color-mix(in oklab, var(--bg-surface) var(--surface-mix), transparent) !important; }',
        '.voiceorb__error { margin: 4px 0 0 !important; }',
      ].join('\\n');
      document.head.appendChild(s);
      return 'ok';
    })()`);
    await sleep(300);
  }
  await einschwingen();

  const vorher = await engine.evalJson(MESSEN);
  const sigVorher = unterschrift(vorher);
  say('0 · VORHER (keine Blase)', {
    kacheln: vorher.kacheln,
    spalten: vorher.spalten,
    zeilen: vorher.zeilen,
    seiten: vorher.seiten,
    kasten: vorher.kasten,
    orb: vorher.orb,
    orbhint: vorher.orbhint,
    blase: vorher.blase,
    scrollHeight: vorher.scrollHeight,
    fenster: vorher.fenster,
    einFenster: vorher.scrollHeight <= vorher.fenster.h,
    ueberstand: vorher.ueberstand,
  });
  await shot('0-vorher');

  // ── Ein echter Turn: im Chat-Reiter tippen und senden ────────────────────
  say('1 · Turn starten (Chat-Reiter)', {
    reiter: await engine.evalJs(KLICK_TAB(1)),
  });
  await sleep(900);
  const getippt = await engine.evalJs(TIPPEN('Wie warm ist es im Flur und regnet es heute noch?'));
  await sleep(200);
  const gesendet = await engine.evalJs(SENDEN);
  // SOFORT zurück auf Zuhause — der Turn läuft dann DORT weiter (eine Session
  // für beide Reiter), und die Blase wächst unter Beobachtung.
  const zurueck = await engine.evalJs(KLICK_TAB(0));
  say('1b · gesendet, zurück auf Zuhause', { getippt, gesendet, zurueck });

  /* ── Die Zeitreihe, ab dem Moment, in dem die Bühne wieder steht ─────────
   *
   * Der Reiter-Wechsel remountet die Ansicht (`key={tab}` in App.tsx) — der
   * erste Bildaufbau danach ist naturgemäß ein anderer als der eingeschwungene
   * (die Bühne misst sich neu). Diesen Übergang dem Overlay anzulasten wäre
   * eine Lüge in beide Richtungen: vor dem Fix hätte er den Befund aufgebläht,
   * nach dem Fix würde er ihn fälschlich rot melden. Gemessen wird darum ab dem
   * Stillstand — die Blase steht laut TTL 30 s, es ist reichlich Zeit dafür. */
  await einschwingen();
  const proben = [];
  const gesehen = new Map();
  const t0 = Date.now();
  let blaseGesehen = false;
  let blaseImmer = true;
  while (Date.now() - t0 < 4000) {
    const m = await engine.evalJson(MESSEN);
    if (m.kacheln && Object.keys(m.kacheln).length > 0) {
      const sig = unterschrift(m);
      if (!gesehen.has(sig)) gesehen.set(sig, { erstesMs: Date.now() - t0, m, blase: m.blase });
      proben.push({ ms: Date.now() - t0, blase: m.blase, sig: sig === sigVorher ? 'wie vorher' : 'ANDERS' });
    }
    if (m.blase) blaseGesehen = true;
    else blaseImmer = false;
    await sleep(120);
  }
  const waehrend = await engine.evalJson(MESSEN);
  await shot('1-waehrend');

  const versatz = (a, b) => {
    const out = {};
    for (const id of Object.keys(a)) {
      const x = a[id], y = b[id];
      if (!y) { out[id] = 'WEG (andere Seite)'; continue; }
      if (x.x !== y.x || x.y !== y.y || x.w !== y.w || x.h !== y.h) {
        out[id] = `dx=${y.x - x.x} dy=${y.y - x.y} dw=${y.w - x.w} dh=${y.h - x.h}`;
      }
    }
    return out;
  };

  const aa = await engine.evalJson(AA);
  say('2 · WÄHREND (Blase steht)', {
    blaseGesehen,
    blaseDurchgehend: blaseImmer,
    blase: waehrend.blase,
    // Nimmt die Sprech-Anzeige Fluss-Platz? Gefragt ist die SCHICHT, nicht die
    // Blase darin: sie ist es, die im Fluss stand.
    schichtPosition: waehrend.schichtFluss,
    blaseBox: waehrend.blaseBox,
    kacheln: waehrend.kacheln,
    spalten: waehrend.spalten,
    zeilen: waehrend.zeilen,
    seiten: waehrend.seiten,
    kasten: waehrend.kasten,
    orb: waehrend.orb,
    versatzGegenVorher: versatz(vorher.kacheln, waehrend.kacheln),
    kastenHoeheVorherNachher: `${vorher.kasten?.h} → ${waehrend.kasten?.h}`,
    orbHoeheVorherNachher: `${vorher.orb?.h} → ${waehrend.orb?.h}`,
    orbhintVorherNachher: `${vorher.orbhint?.h} → ${waehrend.orbhint?.h}`,
    scrollHeight: waehrend.scrollHeight,
    einFenster: waehrend.scrollHeight <= waehrend.fenster.h,
    ueberstand: waehrend.ueberstand,
    unterschriftenImTurn: gesehen.size,
    lesbarkeit: aa,
  });

  // ── NACH: warten, bis die Blase von selbst geht (TTL) ────────────────────
  let weg = false;
  for (let i = 0; i < 400; i++) {
    if (!(await engine.evalJs(`!!document.querySelector('.voiceorb__card')`))) { weg = true; break; }
    await sleep(250);
  }
  await einschwingen();
  const nachher = await engine.evalJson(MESSEN);
  const sigNachher = unterschrift(nachher);
  await shot('2-nachher');

  const alleGleich = [...gesehen.keys()].every((s) => s === sigVorher) && sigNachher === sigVorher;
  say('3 · NACHHER (Blase abgelaufen)', {
    blaseWeg: weg,
    kacheln: nachher.kacheln,
    versatzGegenVorher: versatz(vorher.kacheln, nachher.kacheln),
    scrollHeight: nachher.scrollHeight,
    einFenster: nachher.scrollHeight <= nachher.fenster.h,
  });
  say('4 · URTEIL', {
    unterschriftenGesamt: gesehen.size,
    verlauf: proben.filter((p, i) => i === 0 || proben[i - 1].sig !== p.sig || proben[i - 1].blase !== p.blase),
    vorherGleichWaehrend: unterschrift(waehrend) === sigVorher,
    vorherGleichNachher: sigNachher === sigVorher,
    schicht: waehrend.schichtFluss,
    // Die eine Zusage der Scheibe: über den ganzen Turn EINE Unterschrift.
    ok: blaseGesehen && alleGleich,
  });
  report.ok = blaseGesehen && alleGleich;
  report.unterschriften = gesehen.size;
  report.aa = aa;
  return report;
}

const engines = [];
if (WANT === 'chrome' || WANT === 'beide') engines.push(await startChrome());
if (WANT === 'firefox' || WANT === 'beide') engines.push(await startFirefox());

const berichte = [];
for (const e of engines) {
  try {
    berichte.push(await lauf(e));
  } catch (err) {
    console.log(`\n!! [${e.name}] ${err.message}`);
    berichte.push({ engine: e.name, fehler: String(err.message).slice(0, 300) });
  } finally {
    e.close();
  }
}

console.log(`\n${JSON.stringify({ fenster: `${W}x${H}`, berichte })}`);
if (JSON_OUT) writeFileSync(JSON_OUT, JSON.stringify({ fenster: `${W}x${H}`, berichte }, null, 2));
process.exit(berichte.every((b) => b.ok) ? 0 : 1);

/**
 * **W6-Sonde** — misst und fotografiert genau das, worüber Andi am 20.08. ein
 * Urteil gefällt hat: die vertikale Dichte (Bühne ↔ Orb ↔ „Tippen zum
 * Sprechen" ↔ Fußleiste), den INHALTS-ANTEIL jeder Kachel (»beim Wetter ist
 * nichts gefüllt«), die Breite der Fußleiste und die Höhe der Edit-Leiste.
 *
 * Warum eine eigene Datei neben `probe.mjs`/`shot.mjs`: die beiden messen bzw.
 * fotografieren, und keiner von beiden kommt in den **Edit-Modus**. Der ist
 * aber die Hälfte der Bestellung — und er lässt sich nur über einen echten
 * langen Druck erreichen (`Input.dispatchMouseEvent`, 700 ms zwischen
 * `mousePressed` und `mouseReleased`), nicht über einen localStorage-Schlüssel.
 * Ein Skript, das beides in EINER Sitzung tut, vergleicht außerdem Normal- und
 * Edit-Ansicht bei exakt derselben Fenstergröße und demselben Datenstand.
 *
 * Dieselben zwei Fallen wie in `shot.mjs` sind eingebaut:
 *  - eigener `--user-data-dir` unter `os.tmpdir()`, am Ende stirbt NUR der
 *    eigene Kindprozess — kein fremder Chrome wird angefasst.
 *  - die API-Antworten kommen aus `serve.mjs` (vertragstreue Formen).
 *
 * Am Ende wird auf die geschriebene DATEI gewartet (`statSync`, Größe > 0),
 * bevor das Skript sich beendet: ein Bild, das noch im Puffer hängt, kann
 * niemand ansehen.
 *
 * NUTZUNG: node w6.mjs <out-dir> <tag>
 *   SHOT_PORT (8794) · SHOT_CDP_PORT (9447)
 */
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PORT = Number(process.env.SHOT_PORT ?? 8794);
const CDP_PORT = Number(process.env.SHOT_CDP_PORT ?? 9447);
const BASE = `http://127.0.0.1:${PORT}/`;
const OUT_DIR = process.argv[2];
const TAG = process.argv[3] ?? 'w6';
/**
 * Die zwei Fenster der Bestellung. `SHOT_SIZES=700x900,600x900` hängt weitere
 * an — gebraucht für die Edit-Hilfe: der Höhensprung, den Andi gesehen hat,
 * entsteht erst, wenn der Text UMBRICHT, und das tut er bei 1366 px nie. Eine
 * Zusage „springt nie" prüft man dort, wo das Alte gesprungen ist.
 */
const SIZES = (process.env.SHOT_SIZES ?? '1366x1024,834x1112').split(',').map((s) => {
  const [w, h] = s.trim().split('x').map(Number);
  return { w, h, name: `${w}x${h}` };
});

/**
 * Alle acht Widgets an, jedes auf seiner Registry-Default-Stufe (kein
 * `layout`-Schlüssel!): so misst die Sonde den Zustand, den ein neues Gerät
 * zeigt — und nicht eine Anordnung, die nur in diesem Skript existiert.
 */
const SEED = {
  // Die UI-Sprache steht hier BEWUSST nicht: sie kommt nicht aus localStorage,
  // sondern vom Server (`/api/v1/settings/language`), und gesetzt wird sie
  // einzig von `LanguageSection` — also erst, wenn jemand die Einstellungen
  // öffnet. Ein `SHOT_LANG`-Knopf hier wäre eine Attrappe: er stünde im Skript,
  // änderte aber nichts. (Nebenbefund der W6-Messung, s. RESULT.md — nach einem
  // Neuladen steht die Übersicht wieder auf Deutsch, bis man in die
  // Einstellungen geht. Eigene Baustelle, nicht diese.)
  'hoshi.settings': JSON.stringify({ theme: 'aoi', voice: 'coral' }),
  'hoshi.homeTiles.uhr': 'true',
  'hoshi.homeTiles.wecker': 'true',
  'hoshi.homeTiles.wetter': 'true',
  'hoshi.homeTiles.laeuft': 'true',
  'hoshi.homeTiles.einkauf': 'true',
  'hoshi.homeTiles.vacuum': 'true',
  'hoshi.homeTiles.climate': 'true',
  'hoshi.homeTiles.currentAffairs': 'true',
};

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

const profile = mkdtempSync(join(tmpdir(), 'hoshi-w6-'));
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

/**
 * Die Messung im Dokument. Zwei Zahlenarten, die verschiedene Fragen
 * beantworten:
 *  - **Lücken** (`gaps`): der senkrechte Abstand zwischen zwei Blöcken, also
 *    genau das, was Andi als „echt viel ungenutzter Platz" gesehen hat.
 *  - **Füllung** (`tiles[].fill`): welcher ANTEIL der Kachelfläche trägt
 *    Inhalt. Gemessen wird die Hüllfläche aller sichtbaren Kind-Rechtecke
 *    gegen die Kachel selbst — eine Kachel, deren Inhalt im oberen Drittel
 *    klebt, fällt damit auf, auch wenn das Bild noch „ordentlich" aussieht.
 */
const MEASURE = `(() => {
  const r = (sel, root = document) => { const e = root.querySelector(sel); return e ? e.getBoundingClientRect() : null; };
  const round = (v) => (v === null || v === undefined ? null : Math.round(v));
  const gap = (a, b) => (a && b ? Math.round(b.top - a.bottom) : null);
  const tiles = [...document.querySelectorAll('[data-widget-id]')].map((el) => {
    const box = el.getBoundingClientRect();
    // Hüllrechteck des sichtbaren Inhalts: alle Nachfahren mit echter Fläche.
    let top = Infinity, bottom = -Infinity, left = Infinity, right = -Infinity, area = 0;
    for (const kid of el.querySelectorAll('*')) {
      const k = kid.getBoundingClientRect();
      if (k.width < 1 || k.height < 1) continue;
      if (kid.children.length === 0 || getComputedStyle(kid).display === 'inline') area += k.width * k.height;
      top = Math.min(top, k.top); bottom = Math.max(bottom, k.bottom);
      left = Math.min(left, k.left); right = Math.max(right, k.right);
    }
    const hull = top === Infinity ? 0 : (bottom - top) * (right - left);
    const own = box.width * box.height;
    return {
      id: el.getAttribute('data-widget-id'),
      w: round(box.width), h: round(box.height),
      contentH: top === Infinity ? 0 : Math.round(bottom - top),
      fill: own > 0 ? Math.round((hull / own) * 100) : 0,
    };
  });
  const foot = r('.homefoot');
  const bar = r('.idle__editbar');
  // **Was die alte Hilfe gekostet HÄTTE** — gemessen, nicht geschätzt. Dieselbe
  // Leiste, dasselbe Fenster, dieselbe Sprache: die Vor-W6-Regeln werden kurz
  // inline zurückgelegt (Belegung sichtbar und umbrechend, Zusage umbrechend,
  // keine Mindesthöhe), die Höhe genommen, alles zurückgesetzt. Ein Diff aus
  // zwei getrennten Builds könnte man auf Datenstand oder Fenster schieben —
  // dieser hier nicht.
  const editHelp = (() => {
    const el = document.querySelector('.idle__editbar');
    if (!el) return null;
    const keys = el.querySelector('.idle__editkeys');
    const hint = el.querySelector('.idle__edithint');
    const now = Math.round(el.getBoundingClientRect().height);
    const cs = getComputedStyle(el);
    const texts = el.querySelector('.idle__edittexts');
    const textH = () => (texts ? Math.round(texts.getBoundingClientRect().height) : null);
    const knoepfe = [...el.querySelectorAll('button')].map((b) => Math.round(b.getBoundingClientRect().height));
    // Zeilen zählt man NICHT über getClientRects() — ein Block-Element gibt EIN
    // Rechteck zurück, egal wie oft der Text umbricht. Also Höhe / Zeilenhöhe.
    // (Und Vorsicht: dieser ganze Block lebt in einem Template-Literal — ein
    // Backtick im Kommentar zerlegt die Datei, nicht nur die Messung.)
    const zeilen = (node) => {
      if (!node) return 0;
      const lh = parseFloat(getComputedStyle(node).lineHeight);
      const h = node.getBoundingClientRect().height;
      return Number.isFinite(lh) && lh > 0 ? Math.round(h / lh) : null;
    };
    const textJetzt = textH();
    const undo = [];
    const set = (node, css) => { undo.push([node, node.getAttribute('style')]); Object.assign(node.style, css); };
    if (keys) set(keys, { position: 'static', width: 'auto', height: 'auto', margin: '2px 0 0',
      padding: '0', overflow: 'visible', clipPath: 'none', whiteSpace: 'normal', fontSize: '12px' });
    if (hint) set(hint, { whiteSpace: 'normal', overflow: 'visible', textOverflow: 'clip' });
    set(el, { minHeight: '0px' });
    void el.offsetHeight;
    const before = Math.round(el.getBoundingClientRect().height);
    const textAlt = textH();
    const keyLines = zeilen(keys);
    const hintLines = zeilen(hint);
    for (const [node, style] of undo) { if (style === null) node.removeAttribute('style'); else node.setAttribute('style', style); }
    void el.offsetHeight;
    return {
      jetzt: now,
      mitAlterHilfe: before,
      kostetPx: before - now,
      // Die belastbarere Zahl als die Leistenhöhe: der TEXTBLOCK. Die Leiste
      // wird im engen Fenster auf ihre Mindesthöhe gedrückt, der Textblock
      // nicht — er zeigt darum ungetrübt, was die Hilfe an Höhe verlangt.
      textblockJetzt: textJetzt,
      textblockAlt: textAlt,
      belegungZeilenAlt: keyLines,
      zusageZeilenAlt: hintLines,
      knoepfe,
      polster: cs.paddingTop + '/' + cs.paddingBottom,
      rahmen: cs.borderTopWidth,
      boxSizing: cs.boxSizing,
      // Bricht die EINE sichtbare Zeile ab (ehrliche Ellipse) statt umzubrechen?
      zusageGekuerzt: hint ? hint.scrollWidth > hint.clientWidth + 1 : null,
      // Nach dem Zurücksetzen: die Belegung MUSS wieder unsichtbar sein.
      belegungSichtbarDanach: keys ? Math.round(keys.getBoundingClientRect().height) > 1 : null,
    };
  })();
  return {
    viewport: { w: innerWidth, h: innerHeight },
    docScrollH: document.documentElement.scrollHeight,
    boxes: Object.fromEntries(['.idle', '.idle__head', '.idle__alarm', '.idle__stage', '.idle__tiles', '.idle__page', '.voiceorb', '.voiceorb__tap', '.voiceorb__hint', '.homefoot', '.idle__chips', '.nav', '.idle__editbar', '.idle__tray']
      .map((s) => [s, (() => { const b = r(s); return b ? { top: round(b.top), bottom: round(b.bottom), h: round(b.height), w: round(b.width) } : null; })()])),
    gaps: {
      'Bühne→Orb': gap(r('.idle__tiles'), r('.voiceorb__tap')),
      'Orb→Hinweis': gap(r('.voiceorb__tap'), r('.voiceorb__hint')),
      'Hinweis→Fußleiste': gap(r('.voiceorb__hint'), r('.homefoot')),
      'Fußleiste→Fensterboden': foot ? Math.round(innerHeight - foot.bottom) : null,
      'Kopf→Bühne': gap(r('.idle__head'), r('.idle__stage')),
    },
    footWidthPct: foot ? Math.round((foot.width / innerWidth) * 100) : null,
    // Die Fußleiste gegen ihren eigenen Inhalt gerechnet: footLeerPct ist der
    // Anteil Glas, hinter dem kein Wort steht. Das war die Bestellung („es
    // reicht leicht dezent auf der Länge von den Worten dort") — und 65 % der
    // Fensterbreite sagt darüber nichts, 80 % leeres Glas schon.
    footWortBreite: (() => { const c = r('.idle__chips'); return c ? round(c.width) : null; })(),
    footLeerPct: (() => {
      const c = r('.idle__chips');
      return foot && c && foot.width > 0 ? Math.round((1 - c.width / foot.width) * 100) : null;
    })(),
    footLinks: foot ? round(foot.left) : null,
    navLinks: (() => { const n = r('.nav'); return n ? round(n.left) : null; })(),
    // Gegenprobe zur Zusage „Breite = Inhalt": EIN Chip weniger (den gibt es
    // wirklich — ohne voice-Feld im Backend fehlt „Stimme: lokal" ganz), dann
    // neu messen. Schrumpft die Pille mit, ist die Breite der Inhalt und keine
    // Zahl, die zufällig passt.
    footOhneZweitenChip: (() => {
      const chips = [...document.querySelectorAll('.idle__chips > *')];
      const el = document.querySelector('.homefoot');
      if (!el || chips.length < 2) return null;
      const weg = chips.slice(-2); // Trenner + letzter Chip
      const alt = weg.map((n) => n.style.display);
      for (const n of weg) n.style.display = 'none';
      void el.offsetWidth;
      const schmal = Math.round(el.getBoundingClientRect().width);
      weg.forEach((n, i) => { n.style.display = alt[i]; });
      void el.offsetWidth;
      return schmal;
    })(),
    editBarH: bar ? round(bar.height) : null,
    editHelp,
    tiles,
    stageTileH: tiles.length ? Math.round(tiles.reduce((s, t) => s + t.h, 0) / tiles.length) : null,
    editing: document.querySelector('.idle__stage')?.getAttribute('data-edit') ?? null,
    sizerButtons: [...document.querySelectorAll('.idle__sizerbtn')].map((b) => ({
      label: b.getAttribute('aria-label'), disabled: b.disabled, dir: b.getAttribute('data-dir') ?? b.getAttribute('data-size'),
    })),
  };
})()`;

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
  for (let i = 0; i < 150; i++) {
    const res = await send(
      'Runtime.evaluate',
      { expression: `document.querySelectorAll('.idle__tile').length > 0`, returnByValue: true },
      sessionId,
    );
    if (res.result.value === true) break;
    await sleep(100);
  }
  await sleep(1000); // Einblend-Animationen ausklingen lassen

  const shoot = async (suffix) => {
    const shot = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
    const out = join(OUT_DIR, `${TAG}-${suffix}-${size.name}.png`);
    writeFileSync(out, Buffer.from(shot.data, 'base64'));
    // Auf die DATEI warten, nicht auf den Aufruf.
    for (let i = 0; i < 50; i++) {
      try {
        if (statSync(out).size > 0) return out;
      } catch {
        /* noch nicht da */
      }
      await sleep(50);
    }
    throw new Error(`Bild wurde nicht geschrieben: ${out}`);
  };

  const measure = async () =>
    (await send('Runtime.evaluate', { expression: MEASURE, returnByValue: true }, sessionId)).result.value;

  console.log(await shoot('normal'));
  report[`${size.name}/normal`] = await measure();

  /* ── in den Edit-Modus: echter langer Druck auf die zweite Kachel ───────── */
  const pt = await send(
    'Runtime.evaluate',
    {
      // NUR die AKTIVE Seite: die übrigen Seiten stehen im DOM, liegen aber
      // neben dem Fenster (die Schiene ist verschoben). Ein Druck auf ihre
      // Koordinaten trifft ins Leere — genau daran ist die erste Messung bei
      // 1366 px still gescheitert (Bild „normal" zweimal).
      expression: `(() => { const t = document.querySelector('.idle__page[data-active="true"] [data-widget-id]');
        if (!t) return null; const b = t.getBoundingClientRect();
        return { x: Math.round(b.left + b.width / 2), y: Math.round(b.top + b.height / 2), id: t.getAttribute('data-widget-id') }; })()`,
      returnByValue: true,
    },
    sessionId,
  );
  const p = pt.result.value;
  if (p) {
    const mouse = (type) =>
      send('Input.dispatchMouseEvent', { type, x: p.x, y: p.y, button: 'left', clickCount: 1 }, sessionId);
    await mouse('mousePressed');
    await sleep(900); // > HOME_LONG_PRESS_MS (600)
    await mouse('mouseReleased');
    await sleep(700);
    console.log(await shoot('edit'));
    report[`${size.name}/edit`] = { longPressOn: p.id, ...(await measure()) };

    /* ── der dritte Ausgang: einfach nichts tun ─────────────────────────────
       `SHOT_IDLE_MS=76000` lässt die Sitzung so lange in Ruhe und sieht dann
       nach, ob der Edit-Modus von selbst zugegangen ist. Kostet echte Zeit,
       darum nicht im Normallauf — aber jsdom mit gefälschten Uhren beweist
       eben NICHT, dass ein echter Browser den Timer auch laufen lässt. */
    const idleMs = Number(process.env.SHOT_IDLE_MS ?? 0);
    if (idleMs > 0) {
      await sleep(idleMs);
      const after = await measure();
      console.log(`   Ruhe-Ausgang nach ${idleMs} ms: data-edit=${after.editing}, Leiste ${after.editBarH ?? '—'}px`);
      report[`${size.name}/nach-ruhe`] = { wartete: idleMs, ...after };
    }
  }

  await send('Target.closeTarget', { targetId });
}

const jsonPath = join(OUT_DIR, `${TAG}-messung.json`);
writeFileSync(jsonPath, JSON.stringify(report, null, 1));
console.log(jsonPath);
for (const [key, m] of Object.entries(report)) {
  console.log(`\n── ${key} ── doc ${m.docScrollH}px / viewport ${m.viewport.h}px, Edit-Leiste ${m.editBarH ?? '—'}px`);
  console.log(`   Fußleiste: ${m.boxes['.homefoot']?.w}px breit (${m.footWidthPct}% des Fensters), davon ${m.footWortBreite}px Wörter → ${m.footLeerPct}% leeres Glas; linke Kante ${m.footLinks} vs. Nav ${m.navLinks}; mit einem Chip weniger ${m.footOhneZweitenChip}px`);
  console.log('   Lücken:', JSON.stringify(m.gaps));
  console.log('   Kacheln:', m.tiles.map((t) => `${t.id} ${t.w}×${t.h} füllt ${t.fill}%`).join(' · '));
  if (m.sizerButtons.length) console.log('   Wähler:', JSON.stringify(m.sizerButtons));
  if (m.editHelp) console.log('   Hilfe:', JSON.stringify(m.editHelp));
}

ws.close();
cleanup();
process.exit(0);

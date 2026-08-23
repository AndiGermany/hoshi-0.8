/**
 * **SCHNITT — welches Feld wird abgeschnitten? (Content-Fit-Audit)**
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   cd frontend && VITE_API_BASE=http://127.0.0.1:8801 npm run build
 *   node ../tools/zuhause-probe/schnitt.mjs "$PWD/dist"
 *   node ../tools/zuhause-probe/schnitt.mjs "$PWD/dist" vacuum climate   (nur die)
 *
 *   SCHNITT_PORT=8801  SCHNITT_CDP=9461  SCHNITT_JSON=<datei>  SCHNITT_SHOTS=<dir>
 *   SCHNITT_BILANZ=1 (Höhen je Zeile)  SCHNITT_FENSTER=1366x1024,834x1112
 *
 * ANDI 23.08., wörtlich: „Guck dir bitte noch die Widgets in den verschiedenen
 * Größen an. die felder werden oft abgeschnitten. dafür haben wir ja die
 * größen, dass sich sowas dynamisch anpasst und den inhalt anpasst."
 *
 * WAS DIESE SONDE KANN, WAS `flaeche.mjs` NICHT KANN. Jene meldet je Kachel
 * EINE Zahl (`scrollHeight − clientHeight`) — „irgendwas ragt 6 px raus". Das
 * ist ein Alarm, keine Diagnose: welches FELD es ist und ob es waagerecht mit
 * Ellipse endet oder senkrecht unter der Kante verschwindet, sagt sie nicht.
 * Hier wird jedes Text-Blatt einzeln gemessen und benannt.
 *
 * DREI ARTEN VON SCHNITT — sie sind NICHT dasselbe und werden getrennt gezählt:
 *
 *   ▼ UNTEN   Das Feld liegt (ganz oder teilweise) unter der sichtbaren Kante
 *             der Kachel. Auf einem Flur-iPad, das niemand anfasst, ist das
 *             der Fall, den Andi meint: die Zeile ist WEG. (Die Kachel
 *             scrollt zwar, aber Scrollen ist keine Antwort auf „zu viel für
 *             diese Stufe" — die Stufe soll den Inhalt wählen.)
 *   ⤓ KÄFIG   Dasselbe, ABER zwischen Feld und Kachel liegt eine Liste mit
 *             eigenem `overflow-y: auto` — die bestellte Bauart (§4-S1). Das
 *             Feld ist eine Fingerbreite entfernt, nicht weg, und die Kachel
 *             sagt mit „+N weitere", dass es da ist. Kein Schnitt.
 *   ▶ RECHTS  `scrollWidth > clientWidth`: die Zeile endet mit Ellipse oder
 *             wird hart beschnitten. Ein Wort fehlt, nicht eine Zeile.
 *   ✂ IN SICH `scrollHeight > clientHeight` bei EIGENEM `overflow: hidden` —
 *             die klassische `-webkit-line-clamp`-Kürzung. Oft ABSICHT (ein
 *             Nachrichten-Teaser darf nach drei Zeilen enden); darum eigene
 *             Spalte, damit die Absicht nicht in der Alarmzahl untergeht.
 *
 * MESSKANTE. Verglichen wird gegen die **Polster-Box** der Kachel
 * (`clientHeight` ab Innenkante des Rahmens) — nicht gegen `scrollHeight`:
 * genau dort schneidet der Browser ab. Ein Feld, das nur ins untere Polster
 * ragt, ist noch sichtbar und wird NICHT gemeldet (sonst zählt man Gestaltung
 * als Fehler).
 *
 * DIE BÜHNE IST REALISTISCH ENG (Lehre aus `kachel.mjs`/`flaeche.mjs`): alle
 * acht Widgets sind an, das geprüfte steht VORNE in der Reihenfolge (also
 * sicher auf Seite 1) und trägt die Stufe, um die es geht; die anderen tragen
 * ihre Registry-Vorgabe. Eine einzelne Kachel allein bekäme die ganze Bühne
 * und misst damit einen Zustand, den im Betrieb niemand sieht.
 *
 * GEMELDET WIRD DIE EFFEKTIVE STUFE. `effectiveSize` (homeLayout.ts) stuft ab,
 * wenn die Bühne zu schmal/flach ist: gespeichert XL, gezeichnet L. Die Tabelle
 * führt beide, sonst behauptet sie eine Messung, die es nicht gab.
 */

import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { readFileSync, writeFileSync, mkdtempSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const DIST = process.argv[2];
if (!DIST) throw new Error('Aufruf: node schnitt.mjs <dist-dir> [widget…]');
const ONLY = process.argv.slice(3);
const PORT = Number(process.env.SCHNITT_PORT ?? 8801);
const CDP = Number(process.env.SCHNITT_CDP ?? 9461);
const BASE = `http://127.0.0.1:${PORT}/`;
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

/** Registry-Wahrheit, hier gespiegelt (die Sonde importiert kein TypeScript). */
const SIZES = {
  uhr: ['S', 'M', 'L'],
  wecker: ['S', 'M'],
  wetter: ['S', 'M', 'L', 'XL'],
  laeuft: ['S', 'M', 'L', 'XL'],
  einkauf: ['S', 'M', 'L', 'XL'],
  vacuum: ['S', 'M', 'L', 'XL'],
  climate: ['S', 'M', 'L', 'XL'],
  news: ['S', 'M', 'L', 'XL'],
};
const DEFAULTS = { uhr: 'L', wecker: 'M', wetter: 'L', laeuft: 'L', einkauf: 'M', vacuum: 'L', climate: 'L', news: 'M' };
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
const STAGE = Object.keys(SIZES);

/**
 * Die zwei Fenster, an denen dieses Haus gemessen wird: Andis Schreibtisch und
 * das Flur-iPad hochkant. Beide sind schon die Bezugsgrößen von
 * `shot.mjs`/`flaeche.mjs` — eine dritte Zahl hier hieße, drei Wahrheiten zu
 * pflegen. Der enge Quer-Fall (1194×745) kommt über SCHNITT_FENSTER dazu.
 */
const VIEWS = (process.env.SCHNITT_FENSTER ?? '1366x1024,834x1112')
  .split(',')
  .map((s) => s.split('x').map(Number))
  .map(([w, h]) => ({ w, h }));

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));

/**
 * **Die geprüfte Kachel wird auf Seite 1, Zelle (0,0) GENAGELT** — und das ist
 * kein Komfort, sondern der Unterschied zwischen einer Messung und einem
 * Trugbild. Eine bloße Reihenfolge reicht nicht: die App schreibt beim ersten
 * Rendern selbst `placements` in denselben Schlüssel zurück (gesehen 23.08. —
 * die Sonde maß die Nachrichten-Kachel auf **Seite 3**, und eine inaktive Seite
 * ist `visibility: hidden`, behält aber ihre Geometrie. Ergebnis: plausible
 * Kachelmaße, plausible Überlauf-Zahl — und KEIN einziges Feld, weil jedes
 * Blatt „hidden" war. Ein stiller Nullbefund ist schlimmer als ein Fehler).
 *
 * Also wird die Zelle für JEDE mögliche Spaltenzahl mitgegeben; die anderen
 * Kacheln bleiben ohne Zelle und werden um sie herum gepackt (`placeByCells`
 * gibt einer Kachel ohne Zelle den nächsten freien Platz).
 */
const seedFor = (target, size) => {
  const order = [target, ...STAGE.filter((id) => id !== target)].map((id) => ({
    id,
    size: id === target ? size : DEFAULTS[id],
  }));
  const placements = {};
  for (const spalten of [1, 2, 3, 4]) placements[String(spalten)] = { [target]: { col: 0, row: 0 } };
  const seed = {
    'hoshi.settings': JSON.stringify({ theme: 'aoi', language: 'de', voice: 'coral' }),
    'hoshi.settings.aoi-migrated': '1',
    'hoshi.homeTiles.layout': JSON.stringify({ version: 1, order, placements }),
  };
  for (const id of STAGE) seed[FLAG_KEY[id]] = 'true';
  return seed;
};

/**
 * Der Messausdruck im Browser. `%ID%` wird ersetzt — kein Template-Literal mit
 * `${}`, weil der Ausdruck als String zu Chrome geht und `${}` dort schon
 * ausgewertet wäre.
 */
const MEASURE = `(() => {
  const tile = document.querySelector('[data-widget-id="%ID%"]');
  if (!tile) return { fehlt: true };
  const cs = getComputedStyle(tile);
  const box = tile.getBoundingClientRect();
  const bt = parseFloat(cs.borderTopWidth) || 0;
  const bl = parseFloat(cs.borderLeftWidth) || 0;
  // Die HARTE Kante: ab hier schneidet der Browser ab (Polster-Box, ohne Rahmen).
  const kanteUnten = box.top + bt + tile.clientHeight;
  const kanteRechts = box.left + bl + tile.clientWidth;

  /** Kurzer, wiedererkennbarer Name eines Feldes — Klasse vor Tag. */
  const nenn = (el) => {
    const c = (el.className && typeof el.className === 'string' ? el.className : '').trim().split(/\\s+/)
      .filter((x) => x && !/^tile$|^idle__tile$/.test(x));
    return (c[c.length - 1] || el.tagName.toLowerCase());
  };
  const kurz = (s) => (s || '').replace(/\\s+/g, ' ').trim().slice(0, 42);

  // Eine Kachel auf einer inaktiven Seite hat volle Geometrie, aber jedes Blatt
  // darin ist \`visibility: hidden\` — dort zu messen liefert einen stillen
  // Nullbefund. Lieber laut scheitern (s. Kopf von \`seedFor\`).
  if (cs.visibility === 'hidden') return { versteckt: true };

  const felder = [];
  for (const el of tile.querySelectorAll('*')) {
    // Nur BLÄTTER mit eigenem Text: ein Container, dessen Kind rausragt, würde
    // denselben Schnitt ein zweites Mal melden.
    const eigen = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim());
    if (!eigen) continue;
    const r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;
    const ecs = getComputedStyle(el);
    if (ecs.visibility === 'hidden' || ecs.display === 'none') continue;

    const unten = Math.round(r.bottom - kanteUnten);
    const rechts = Math.round(r.right - kanteRechts);
    const inSichY = el.scrollHeight - el.clientHeight;
    const inSichX = el.scrollWidth - el.clientWidth;
    const geklemmt = ecs.overflow !== 'visible' || ecs.overflowY !== 'visible' || ecs.overflowX !== 'visible';

    // Liegt zwischen Feld und Kachel ein eigener Scroll-Käfig (eine Liste mit
    // \`overflow-y: auto\`)? Dann ist das Feld nicht WEG, sondern eine
    // Fingerbreite entfernt — und die Kachel sagt mit ihrer „+N weitere"-Zeile,
    // dass es da ist. Das ist die bestellte Bauart und kein Schnitt.
    let kaefig = false;
    for (let p = el.parentElement; p && p !== tile; p = p.parentElement) {
      const pcs = getComputedStyle(p);
      if (pcs.overflowY === 'auto' || pcs.overflowY === 'scroll') { kaefig = true; break; }
    }

    const eintrag = { feld: nenn(el), text: kurz(el.textContent) };
    let treffer = false;
    if (unten > 1 && kaefig) { eintrag.kaefig = unten; treffer = true; }
    else if (unten > 1) { eintrag.unten = unten; eintrag.ganzWeg = r.top >= kanteUnten - 1; treffer = true; }
    if (rechts > 1) { eintrag.rechts = rechts; treffer = true; }
    if (inSichX > 1 && geklemmt) { eintrag.ellipse = inSichX; treffer = true; }
    if (inSichY > 1 && geklemmt) { eintrag.klemme = inSichY; treffer = true; }
    if (treffer) felder.push(eintrag);
  }

  /**
   * Wenn die Kachel überläuft, aber kein TEXT-Blatt unter der Kante liegt, ist
   * es ein Kasten (Liste, Knopfzeile, Polster). Ein Alarm ohne Namen ist keine
   * Diagnose — also den tiefsten Nachfahren benennen (Muster orb-flaeche.mjs).
   */
  let tiefster = null;
  for (const el of tile.querySelectorAll('*')) {
    const r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;
    if (!tiefster || r.bottom > tiefster.bottom) tiefster = { bottom: r.bottom, feld: nenn(el), text: kurz(el.textContent) };
  }

  /**
   * **Die Höhen-Bilanz** (SCHNITT_BILANZ=1): was die Kachel an Zeilen mitbringt
   * und was jede davon kostet. Ohne sie ist jede Container-Query-Schwelle eine
   * geratene Zahl — mit ihr steht im Commit, WARUM dort 196 px steht und nicht
   * 200. Direkte Kinder der Kachel plus die Posten jeder Liste darin.
   */
  const bilanz = [];
  for (const el of tile.children) {
    bilanz.push({ feld: nenn(el), h: Math.round(el.getBoundingClientRect().height) });
    if (el.tagName === 'UL' || el.tagName === 'OL') {
      for (const li of el.children) bilanz.push({ feld: '  ' + nenn(li), h: Math.round(li.getBoundingClientRect().height) });
    }
  }
  const innen = Math.round(tile.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom));

  return {
    bilanz,
    innen,
    tiefster: tiefster ? { ueber: Math.round(tiefster.bottom - kanteUnten), feld: tiefster.feld, text: tiefster.text } : null,
    stufe: tile.getAttribute('data-size') || (tile.querySelector('[data-size],[data-step]')
      ? (tile.querySelector('[data-size],[data-step]').getAttribute('data-size')
         || tile.querySelector('[data-size],[data-step]').getAttribute('data-step')) : null),
    kachel: { w: Math.round(box.width), h: Math.round(box.height) },
    ueberlaufY: Math.round(tile.scrollHeight - tile.clientHeight),
    ueberlaufX: Math.round(tile.scrollWidth - tile.clientWidth),
    felder,
  };
})()`;

/**
 * **Erst prüfen, ob der Port UNS gehört** — und sonst abbrechen.
 *
 * Das ist keine Vorsicht, das ist ein bezahlter Fehler (23.08.): auf 8798 lief
 * der Probe-Server eines PARALLELEN Pods aus einem anderen Worktree. `spawn`
 * mit `stdio: 'ignore'` schluckt das `EADDRINUSE` lautlos, der eigene Server
 * stirbt sofort — und die Sonde misst danach klaglos die fremde App weiter.
 * Ergebnis: eine vollständige Vorher/Nachher-Tabelle, in der sich nach einer
 * Stunde Arbeit **kein einziger Wert** geändert hatte, weil keine einzige
 * eigene Zeile je im Bild war. Ein belegter Port ist hier also nicht „schon
 * belegt", sondern „diese Messung wäre eine Fälschung".
 */
async function portFrei(port, wofuer) {
  await new Promise((ok, fail) => {
    const wache = createServer();
    wache.once('error', (e) =>
      fail(
        new Error(
          `Port ${port} (${wofuer}) ist belegt [${e.code}] — dort läuft etwas Fremdes, ` +
            `und die Sonde würde eine FREMDE App messen. Umlenken: SCHNITT_PORT/SCHNITT_CDP.`,
        ),
      ),
    );
    wache.listen(port, '127.0.0.1', () => wache.close(() => ok()));
  });
}
await portFrei(PORT, 'Probe-Server');
await portFrei(CDP, 'Chrome-Fernsteuerung');

/* ── Chrome + CDP (dieselbe Bauart wie die Geschwister-Skripte) ───────────── */
const profil = mkdtempSync(join(tmpdir(), 'hoshi-schnitt-'));
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
  rmSync(profil, { recursive: true, force: true });
};
process.on('exit', ende);

async function warteAufServer() {
  for (let i = 0; i < 100; i++) {
    try {
      const r = await fetch(`${BASE}api/health`);
      if (r.ok) return;
    } catch { /* noch nicht oben */ }
    await sleep(100);
  }
  throw new Error('Probe-Server kam nicht hoch');
}

async function wsUrl() {
  for (let i = 0; i < 100; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${CDP}/json/version`);
      if (r.ok) return (await r.json()).webSocketDebuggerUrl;
    } catch { /* Chrome startet noch */ }
    await sleep(100);
  }
  throw new Error('Chrome kam nicht hoch');
}

await warteAufServer();
const ws = new WebSocket(await wsUrl());
await new Promise((ok, fail) => {
  ws.onopen = ok;
  ws.onerror = fail;
});
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
    for (let i = wartend.length - 1; i >= 0; i--) {
      if (wartend[i].method === m.method) wartend.splice(i, 1)[0].ok(m.params);
    }
  }
};
const send = (method, params = {}, sessionId) =>
  new Promise((ok, fail) => {
    const id = ++seq;
    offen.set(id, { ok, fail });
    ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
  });
const aufEreignis = (method, ms = 15000) =>
  new Promise((ok) => {
    wartend.push({ method, ok });
    setTimeout(ok, ms);
  });

const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
await send('Page.enable', {}, sessionId);
await send('Runtime.enable', {}, sessionId);

const targets = ONLY.length ? ONLY : STAGE;
const bericht = [];
const fehler = [];
for (const view of VIEWS) {
  await send(
    'Emulation.setDeviceMetricsOverride',
    { width: view.w, height: view.h, deviceScaleFactor: 1, mobile: false },
    sessionId,
  );
  for (const id of targets) {
    for (const size of SIZES[id]) {
      const seed = seedFor(id, size);
      await send(
        'Page.addScriptToEvaluateOnNewDocument',
        {
          source: Object.entries(seed)
            .map(([k, v]) => `localStorage.setItem(${JSON.stringify(k)}, ${JSON.stringify(v)});`)
            .join(''),
        },
        sessionId,
      );
      await send('Page.navigate', { url: BASE }, sessionId);
      await aufEreignis('Page.loadEventFired');
      await sleep(1100); // Eintritts-Stagger + erster Poll der Hooks
      const expression = MEASURE.replaceAll('%ID%', id);
      const res = await send('Runtime.evaluate', { expression, returnByValue: true }, sessionId);
      const m = res.result.value;
      // Beides sind FEHLER der Sonde, keine Befunde über die Kachel — laut
      // sein, statt eine „✓"-Zeile zu drucken, die niemand gemessen hat.
      if (m?.fehlt || m?.versteckt) {
        fehler.push(`${view.w}x${view.h} ${id} ${size}: ${m.fehlt ? 'Kachel nicht im DOM' : 'Kachel auf einer versteckten Seite'}`);
        console.log(`${view.w}x${view.h}  ${id.padEnd(8)} ${size}   ⚠ ${m.fehlt ? 'nicht im DOM' : 'versteckte Seite'}`);
        continue;
      }
      bericht.push({ view: `${view.w}x${view.h}`, id, size, ...m });
      const kopf = `${view.w}x${view.h}  ${id.padEnd(8)} ${size}→${(m.stufe ?? '?').padEnd(2)} ${String(m.kachel.w).padStart(4)}×${String(m.kachel.h).padStart(4)}`;
      if (m.felder.length === 0 && m.ueberlaufY <= 1 && m.ueberlaufX <= 1) {
        console.log(`${kopf}  ✓`);
      } else {
        console.log(`${kopf}  ÜBERLAUF↕ ${m.ueberlaufY} ↔ ${m.ueberlaufX}`);
        for (const f of m.felder) {
          const wie = [
            f.unten ? `▼${f.unten}px${f.ganzWeg ? ' GANZ WEG' : ''}` : null,
            f.kaefig ? `⤓${f.kaefig}px` : null,
            f.rechts ? `▶${f.rechts}px` : null,
            f.ellipse ? `…${f.ellipse}px` : null,
            f.klemme ? `✂${f.klemme}px` : null,
          ].filter(Boolean).join(' ');
          console.log(`        ${f.feld.padEnd(26)} ${wie.padEnd(22)} „${f.text}"`);
        }
        if (m.felder.length === 0 && m.tiefster) {
          console.log(`        tiefster Kasten: ${m.tiefster.feld} (${m.tiefster.ueber} px über die Kante) „${m.tiefster.text}"`);
        }
      }
      if (process.env.SCHNITT_BILANZ) {
        console.log(`        Bilanz (Innenhoehe ${m.innen} px): ${m.bilanz.map((b) => `${b.feld.trim()} ${b.h}`).join(' · ')}`);
      }
      if (process.env.SCHNITT_SHOTS) {
        const shot = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
        writeFileSync(join(process.env.SCHNITT_SHOTS, `schnitt-${view.w}x${view.h}-${id}-${size}.png`), Buffer.from(shot.data, 'base64'));
      }
    }
  }
}

/* ── Die Zusammenfassung, die ins RESULT wandert ──────────────────────────── */
const zaehl = (pred) => bericht.filter((b) => b.felder.some(pred)).length;
console.log('');
console.log(`Fälle gemessen:            ${bericht.length}`);
console.log(`… mit ▼ unten abgeschnitten: ${zaehl((f) => f.unten)}`);
console.log(`… mit ⤓ im Scroll-Kaefig:      ${zaehl((f) => f.kaefig)}`);
console.log(`… mit ▶ rechts über Kante:   ${zaehl((f) => f.rechts)}`);
console.log(`… mit … Ellipse:             ${zaehl((f) => f.ellipse)}`);
console.log(`… mit ✂ Klemme (line-clamp): ${zaehl((f) => f.klemme)}`);
console.log(`… Kachel-Überlauf ↕:         ${bericht.filter((b) => b.ueberlaufY > 1).length}`);
if (fehler.length) {
  console.log('');
  console.log('NICHT GEMESSEN (Sonden-Fehler, nicht Befund):');
  for (const f of fehler) console.log(`  ${f}`);
}

if (process.env.SCHNITT_JSON) writeFileSync(process.env.SCHNITT_JSON, JSON.stringify(bericht, null, 1));
ende();
process.exit(0);

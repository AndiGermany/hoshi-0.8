/**
 * Ein Server für die Zuhause-Probe: statisches `dist/` + gefälschte, aber
 * VERTRAGSTREUE API-Antworten (die Formen stammen aus den echten Parsern in
 * `frontend/src/hooks/*`). Gleicher Origin wie die App → kein CORS nötig,
 * `API_BASE` zeigt per `VITE_API_BASE` hierher.
 *
 * NUTZUNG: node serve.mjs <dist-dir> <port>
 */
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';

const ROOT = process.argv[2];
const PORT = Number(process.argv[3] ?? 8794);
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.json': 'application/json; charset=utf-8',
};

const NOW = () => Date.now();
const iso = (offsetMs) => new Date(NOW() + offsetMs).toISOString();

/** Nächstes 07:00 Uhr lokal. */
function nextSeven() {
  const d = new Date();
  d.setHours(7, 0, 0, 0);
  if (d.getTime() <= NOW()) d.setDate(d.getDate() + 1);
  return d.getTime();
}

/** Der Stunden-Verlauf, damit die Regen-ab-Zeile eine echte Quelle hat. */
function hourly() {
  const out = [];
  const start = new Date();
  start.setMinutes(0, 0, 0);
  for (let i = 1; i <= 12; i++) {
    out.push({
      epochMs: start.getTime() + i * 3600000,
      tempC: 21 - Math.round(Math.abs(i - 3) * 0.7),
      precipProbability: i >= 5 ? 45 + i : 8,
    });
  }
  return out;
}

const API = {
  '/api/health': () => ({ status: 'up' }),
  '/api/v1/ops/status': () => ({
    enabled: true,
    overall: 'OK',
    memory: { level: 'OK', source: 'cgroup', detail: '512MB/2GB' },
    sidecars: [
      { name: 'stt', status: 'OK', detail: 'Whisper lokal' },
      { name: 'tts', status: 'OK', detail: 'Piper lokal' },
    ],
    voice: { engine: 'piper', cloud: false },
    allLocal: true,
    ts: NOW(),
  }),
  '/api/v1/weather/today': () => ({
    label: 'Duisburg',
    todayMin: 14,
    todayMax: 23,
    codeText: 'teilweise bewölkt',
    precipMm: 2.4,
    nowTemp: 21,
    nowCodeText: 'teilweise bewölkt',
    tomorrowMin: 13,
    tomorrowMax: 19,
    tomorrowCodeText: 'Regenschauer',
    sunriseEpochMs: new Date().setHours(6, 22, 0, 0),
    sunsetEpochMs: new Date().setHours(20, 48, 0, 0),
    hourly: hourly(),
  }),
  '/api/v1/scheduled': () => [
    {
      id: 'timer-nudeln-1',
      kind: 'TIMER',
      label: 'Nudeln',
      dueAtEpochMs: NOW() + 540000,
      remainingSeconds: 540,
    },
    { id: 'alarm-0700', kind: 'ALARM', label: 'Wecker', dueAtEpochMs: nextSeven() },
  ],
  '/api/v1/lists': () => [
    { id: 'item-milch', text: 'Milch', quantity: 2, addedAtEpochMs: NOW() - 7200000 },
    { id: 'item-brot', text: 'Brot', quantity: 1, addedAtEpochMs: NOW() - 3600000 },
  ],
  '/api/v1/currentaffairs/today': () => ({
    freshness: 'FRESH',
    observedAt: iso(0),
    lastSuccessfulRefreshAt: iso(-300000),
    items: [
      {
        id: 'ca-1',
        source: 'TAGESSCHAU',
        title: 'Bundesregierung beschließt neues Energiepaket',
        snippet: 'Die Koalition einigt sich auf Details zur Förderung.',
        attribution: 'tagesschau.de',
        canonicalUrl: 'https://www.tagesschau.de/inland/energiepaket-100.html',
        publishedAt: iso(-3600000),
        fetchedAt: iso(-300000),
      },
      {
        id: 'ca-2',
        source: 'HEISE',
        title: 'Neue Sicherheitslücke in verbreiteter Router-Firmware',
        attribution: 'heise online',
        canonicalUrl: 'https://www.heise.de/news/router-luecke-123456.html',
        publishedAt: iso(-7200000),
        fetchedAt: iso(-300000),
      },
    ],
  }),
  '/api/v1/home/registry': () => ({
    areas: [
      {
        areaId: 'wohnzimmer',
        label: 'Wohnzimmer',
        recentCommands: 12,
        entities: [
          {
            entityId: 'climate.wohnzimmer_thermostat',
            domain: 'climate',
            name: 'Thermostat',
            labels: [],
            state: 'heat',
            attrs: { current_temperature: '21.5', temperature: '22', hvac_action: 'heating' },
          },
        ],
      },
      {
        areaId: 'flur',
        label: 'Flur',
        entities: [
          {
            entityId: 'vacuum.roborock',
            domain: 'vacuum',
            name: 'Saugroboter',
            labels: [],
            state: 'docked',
            attrs: { battery_level: '88' },
          },
        ],
      },
    ],
    unassigned: [],
    statesFetchedAt: iso(0),
  }),
};

/**
 * **Ein echter Turn, ohne Backend** (23.08., Sprech-Overlay-Messung).
 *
 * Das Sprech-Overlay auf Zuhause zeigt `session.turns` — es entsteht NUR, wenn
 * wirklich ein Turn gelaufen ist. Eine Sonde, die den Kasten stattdessen ins
 * DOM injiziert, misst ihr eigenes Markup; sobald der Fix die Struktur
 * anfasst, misst sie ein Gespenst. Darum spricht dieser Server denselben
 * SSE-Vertrag wie das Backend: `start` → `delta`* → `done`, jedes Ereignis EIN
 * `data:`-Frame (Formen aus `frontend/src/api/types.ts#ChatEvent`, gelesen von
 * `api/sse.ts`). `speak:false` bleibt unbeantwortet — kein Audio, keine
 * Wiedergabe, der Turn ist reiner Text.
 *
 * Die Antwort ist so lang, wie der Aufrufer sie bestellt (`?len=`): der
 * Overlay-Kasten hat einen Deckel (`max-height`), und die interessante Frage
 * ist der WORST CASE — eine Antwort, die ihn erreicht.
 */
function chatStream(req, res, url) {
  // 900 Zeichen ist der WORST CASE mit Absicht: bei 46ch Kastenbreite sind das
  // rund 20 Zeilen — mehr, als der Deckel (`max-height: min(34vh, 300px)`)
  // durchlässt. Der Kasten steht also garantiert an seiner Obergrenze.
  const len = Math.max(0, Number(url.searchParams.get('len') ?? process.env.PROBE_ANSWER_LEN ?? 900));
  const satz =
    'Im Flur sind es 21 Grad, die Heizung läuft nicht, und für heute Abend ' +
    'ist Regen ab etwa 18 Uhr gemeldet. ';
  let text = '';
  while (text.length < len) text += satz;
  text = text.slice(0, len);
  res.writeHead(200, {
    'content-type': 'text/event-stream; charset=utf-8',
    'cache-control': 'no-cache',
    connection: 'keep-alive',
  });
  const frame = (obj) => res.write(`data: ${JSON.stringify(obj)}\n\n`);
  frame({ event: 'start', provider: 'LOCAL', category: 'SMALLTALK', model: 'gemma-probe' });
  // In Häppchen, wie ein echter Stream — sonst sähe die Sonde den
  // Streaming-Zustand (`busy`) nie, in dem der Kasten wächst.
  const stueck = 24;
  let at = 0;
  const tick = () => {
    if (at >= text.length) {
      frame({ event: 'done', provider: 'LOCAL' });
      res.end();
      return;
    }
    frame({ event: 'delta', text: text.slice(at, at + stueck) });
    at += stueck;
    setTimeout(tick, 20);
  };
  setTimeout(tick, 20);
}

createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const path = normalize(decodeURIComponent(url.pathname));
  if (path === '/api/v1/chat/stream' && req.method === 'POST') {
    req.resume(); // Body verwerfen, aber lesen — sonst hängt der Client
    chatStream(req, res, url);
    return;
  }
  const api = API[path];
  if (api) {
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify(api()));
    return;
  }
  if (path.startsWith('/api/')) {
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('404');
    return;
  }
  try {
    const file = path.endsWith('/') ? join(ROOT, path, 'index.html') : join(ROOT, path);
    const body = await readFile(file);
    res.writeHead(200, { 'content-type': TYPES[extname(file)] ?? 'application/octet-stream' });
    res.end(body);
  } catch {
    // SPA-Fallback
    try {
      const body = await readFile(join(ROOT, 'index.html'));
      res.writeHead(200, { 'content-type': TYPES['.html'] });
      res.end(body);
    } catch {
      res.writeHead(404, { 'content-type': 'text/plain' });
      res.end('404');
    }
  }
}).listen(PORT, '127.0.0.1', () => console.log(`serving ${ROOT} on ${PORT}`));

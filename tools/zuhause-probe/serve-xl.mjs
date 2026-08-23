/**
 * Der Probe-Server für die **XL-Stufen** (W5) — Schwester von `serve.mjs`,
 * bewusst eine eigene Datei statt eines Schalters darin:
 *
 *  - `serve.mjs` trägt die Feinschliff-Fixture vom 19.08. (2 Meldungen,
 *    2 Einkäufe, 1 Raum). Die Vorher/Nachher-Bilder in
 *    `docs/screenshots/zuhause-feinschliff/` hängen daran — wer sie
 *    nachstellen will, braucht sie unverändert.
 *  - XL braucht das Gegenteil: **mehr echte Zeilen, als in die Kachel
 *    passen**, sonst fotografiert man einen Deckel, den nie jemand erreicht.
 *    Erst 8 Meldungen zeigen „+2 nicht gezeigt", erst 10 Räume zeigen
 *    „+2 weitere".
 *
 * Die Formen stammen — wie dort — aus den echten Parsern in
 * `frontend/src/hooks/*` bzw. `frontend/src/components/homeTiles.ts`: ein
 * Feld, das der Parser verwirft, ist auch hier keins. Erfunden sind nur die
 * WERTE, nicht die Struktur.
 *
 * NUTZUNG: node serve-xl.mjs <dist-dir> <port>
 */
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';

const ROOT = process.argv[2];
const PORT = Number(process.argv[3] ?? 8795);
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

/**
 * Zwölf Stunden-Punkte im BE-Raster (`HOURLY_WINDOW = 12`, stündlich, erster
 * Punkt = laufende Stunde — s. `WeatherGroundingProvider.parseHourly`). Die
 * Kurve steigt bis zum Nachmittag und fällt danach; der Regen zieht ab der
 * fünften Stunde auf. Beides ist erfunden, aber im Wertebereich, den
 * Open-Meteo wirklich liefert (tempC gerundet, precipProbability 0–100).
 */
function hourly() {
  const start = new Date();
  start.setMinutes(0, 0, 0);
  const temps = [21, 22, 23, 23, 22, 21, 19, 18, 17, 16, 15, 15];
  const rain = [0, 0, 5, 10, 35, 60, 80, 75, 55, 30, 15, 5];
  return temps.map((tempC, i) => ({
    epochMs: start.getTime() + i * 3600000,
    tempC,
    precipProbability: rain[i],
  }));
}

/** Zehn Räume mit echten Thermostat-Attributen — zwei mehr, als XL zeigt. */
function climateAreas() {
  const rooms = [
    ['wohnzimmer', 'Wohnzimmer', 21.5, 22, true],
    ['kueche', 'Küche', 20.0, 20, false],
    ['bad', 'Bad', 23.4, 24, true],
    ['flur', 'Flur', 18.2, 18, false],
    ['schlafzimmer', 'Schlafzimmer', 17.8, 18, false],
    ['arbeitszimmer', 'Arbeitszimmer', 21.1, 21, false],
    ['kinderzimmer', 'Kinderzimmer', 20.6, 21, true],
    ['gaestezimmer', 'Gästezimmer', 16.9, 17, false],
    ['keller', 'Keller', 14.3, 15, false],
    ['dachboden', 'Dachboden', 13.1, 14, false],
  ];
  return rooms.map(([areaId, label, current, target, heating]) => ({
    areaId,
    label,
    recentCommands: 3,
    entities: [
      {
        entityId: `climate.${areaId}_thermostat`,
        domain: 'climate',
        name: 'Thermostat',
        labels: [],
        state: heating ? 'heat' : 'auto',
        attrs: {
          current_temperature: String(current),
          temperature: String(target),
          hvac_action: heating ? 'heating' : 'idle',
        },
      },
    ],
  }));
}

/** Fünfzehn Einkäufe — drei mehr, als XL (12) zeigt. */
function shopping() {
  const items = [
    ['Milch', 2],
    ['Vollkornbrot', 1],
    ['Eier', 10],
    ['Butter', 1],
    ['Tomaten', 6],
    ['Kaffeebohnen', 1],
    ['Spülmaschinentabs', 1],
    ['Haferflocken', 2],
    ['Olivenöl', 1],
    ['Zwiebeln', 3],
    ['Parmesan', 1],
    ['Apfelsaft', 2],
    ['Zahnpasta', 1],
    ['Küchenrolle', 4],
    ['Basilikum', 1],
  ];
  return items.map(([text, quantity], i) => ({
    id: `item-${i}`,
    text,
    quantity,
    addedAtEpochMs: NOW() - (i + 1) * 600000,
  }));
}

/** Sieben laufende Countdowns + ein Wecker — genug für zwei XL-Spalten. */
function scheduled() {
  const timers = [
    ['Nudeln', 9 * 60],
    ['Wäsche', 38 * 60],
    ['Ofen vorheizen', 4 * 60],
    ['Tee ziehen lassen', 2 * 60],
    ['Backblech', 26 * 60],
    ['Spülmaschine', 71 * 60],
    ['Hefeteig gehen lassen', 52 * 60],
  ];
  const out = timers.map(([label, secs], i) => ({
    id: `timer-${i}`,
    kind: 'TIMER',
    label,
    dueAtEpochMs: NOW() + secs * 1000,
    remainingSeconds: secs,
  }));
  out.push({ id: 'alarm-0700', kind: 'ALARM', label: 'Wecker', dueAtEpochMs: nextSeven() });
  return out;
}

/** Acht Meldungen mit Teasern — zwei mehr, als der Deckel (6) zeigt. */
function currentAffairs() {
  const items = [
    [
      'TAGESSCHAU',
      'Bundesregierung beschließt neues Energiepaket',
      'Die Koalition einigt sich auf Details zur Förderung von Wärmepumpen und Speichern; die Länder sollen die Hälfte der Kosten tragen.',
      'tagesschau.de',
    ],
    [
      'HEISE',
      'Neue Sicherheitslücke in verbreiteter Router-Firmware',
      'Betroffen sind mehrere Modellreihen; ein Update steht bereit, muss aber von Hand eingespielt werden.',
      'heise online',
    ],
    [
      'TAGESSCHAU',
      'Bahn kündigt neuen Fahrplan für den Fernverkehr an',
      'Ab Dezember fahren mehr Direktverbindungen in den Süden, dafür entfallen zwei Nachtzüge.',
      'tagesschau.de',
    ],
    [
      'HEISE',
      'Offene Modelle holen bei Sprachaufgaben auf',
      'Eine neue Auswertung sieht den Abstand zu kommerziellen Anbietern auf unter ein Jahr schrumpfen.',
      'heise online',
    ],
    [
      'TAGESSCHAU',
      'Wetterdienst warnt vor kräftigen Gewittern am Abend',
      'Örtlich sind Starkregen und Hagel möglich; der Schwerpunkt liegt im Westen.',
      'tagesschau.de',
    ],
    [
      'HEISE',
      'Stromnetz-Betreiber melden Rekord bei Einspeisung',
      'An zwei Tagen deckte erneuerbare Erzeugung rechnerisch den gesamten Bedarf.',
      'heise online',
    ],
    [
      'TAGESSCHAU',
      'Tarifrunde im öffentlichen Dienst geht in die dritte Runde',
      'Die Gewerkschaften fordern deutliche Zuschläge für Schichtdienste.',
      'tagesschau.de',
    ],
    [
      'HEISE',
      'Browser-Hersteller einigen sich auf gemeinsames Testverfahren',
      'Ein gemeinsamer Testlauf soll Unterschiede bei neuen Web-Funktionen schneller sichtbar machen.',
      'heise online',
    ],
  ];
  return {
    freshness: 'FRESH',
    observedAt: iso(0),
    lastSuccessfulRefreshAt: iso(-300000),
    items: items.map(([source, title, snippet, attribution], i) => ({
      id: `ca-${i}`,
      source,
      title,
      snippet,
      attribution,
      canonicalUrl: `https://example.invalid/artikel-${i}`,
      publishedAt: iso(-(i + 1) * 1800000),
      fetchedAt: iso(-300000),
    })),
  };
}

/**
 * Sieben Tage im `DayOutlook`-Raster (BE-Vertrag
 * `vault/tracks/RESULT-wetter-mehrtage-2026-08-21.md` §1.2 — Wire-Namen
 * AUSGESCHRIEBEN: `tempMin`/`tempMax`, nicht `tMin`/`tMax`). `offset: 0` ist
 * HEUTE; `dateIso` ist ein Kalendertag, kein Zeitpunkt. Tag 5 trägt bewusst
 * KEINE `precipProbability` — so steht im Bild auch der Fall „keine Angabe",
 * der nie zu „0 %" werden darf.
 */
function outlook() {
  const base = new Date();
  base.setHours(12, 0, 0, 0);
  const tage = [
    [15, 23, 'teilweise bewölkt', 0, 10],
    [14, 21, 'leichter Regen', 3.4, 60],
    [13, 19, 'Regenschauer', 5.1, 80],
    [12, 20, 'bedeckt', 0, 20],
    [14, 24, 'klar und sonnig', 0, null],
    [16, 27, 'klar und sonnig', 0, 5],
    [15, 25, 'mäßiger Schneefall', 0.2, 15],
  ];
  return tage.map(([tempMin, tempMax, codeText, precipMm, prob], offset) => {
    const d = new Date(base.getTime());
    d.setDate(d.getDate() + offset);
    const dateIso = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
      d.getDate(),
    ).padStart(2, '0')}`;
    const day = { offset, dateIso, tempMin, tempMax, codeText, precipMm };
    if (prob !== null) day.precipProbability = prob;
    return day;
  });
}

/**
 * Sonnenauf-/-untergang. `SHOT_SUN=night` schiebt beide so, dass JETZT
 * **vor** dem Aufgang liegt — dann fotografiert man den Nacht-Zustand des
 * Sonnenbogens (Punkt unter dem Horizont, Bogen gedimmt, Mond-Ring), ohne bis
 * 22 Uhr warten zu müssen. Die Werte bleiben echte Zeitpunkte; verschoben wird
 * der TAG, nicht die Wahrheit über ihn.
 */
function sun() {
  const rise = new Date().setHours(6, 22, 0, 0);
  const set = new Date().setHours(20, 48, 0, 0);
  if (process.env.SHOT_SUN !== 'night') return { sunriseEpochMs: rise, sunsetEpochMs: set };
  const shift = 12 * 3600000;
  return { sunriseEpochMs: rise + shift, sunsetEpochMs: set + shift };
}

/**
 * **Die volle Sauger-Familie** — dieselbe wie in `serve-sauger.mjs`, hier
 * hereingeholt, weil die Fixture bis 23.08. genau EINE `vacuum`-Entität führte
 * (`state: docked`, `battery_level: 88`). Das widersprach dem Zweck dieser
 * Datei: der Sauger zeigt auf L/XL Fortschritt, letzten Lauf, Mopp-Trocknung
 * und vier Wartungs-Restzeiten — mit einer nackten Entität misst ein
 * Content-Fit-Audit die LEERE Kachel und meldet fröhlich „passt".
 *
 * Werte und Einheiten sind aus `serve-sauger.mjs` übernommen (Sekunden, wie
 * HAs `roborock`-Integration sie führt — Einheiten werden nie geraten),
 * inklusive der negativen, ÜBERFÄLLIGEN Sensorzeit: der Fall mit der längsten
 * Zeile ist der, an dem eine Kachel bricht.
 */
const VAC = 'roborock_qrevo_pro';
const VAC_SEEN = NOW() - 12 * 60_000;
const vacSensor = (suffix, state, unit) => ({
  entityId: `sensor.${VAC}_${suffix}`,
  domain: 'sensor',
  name: suffix,
  labels: [],
  state,
  ...(unit ? { attrs: { unit_of_measurement: unit } } : {}),
  fromCacheSinceMs: VAC_SEEN,
});
const vacBinary = (suffix, state) => ({
  entityId: `binary_sensor.${VAC}_${suffix}`,
  domain: 'binary_sensor',
  name: suffix,
  labels: [],
  state,
  fromCacheSinceMs: VAC_SEEN,
});
/** `XL_VACUUM=cleaning` zeigt das Gegenstück (Fortschritt, Raum, „Zur Basis"). */
const VAC_CLEANING = process.env.XL_VACUUM === 'cleaning';
const vacuumFamily = () => [
  {
    entityId: `vacuum.${VAC}`,
    domain: 'vacuum',
    name: 'Saugroboter',
    labels: [],
    state: VAC_CLEANING ? 'cleaning' : 'docked',
    attrs: { battery_level: VAC_CLEANING ? '63' : '100' },
    ...(VAC_CLEANING ? {} : { fromCacheSinceMs: VAC_SEEN }),
  },
  vacSensor('batterie', VAC_CLEANING ? '63' : '100', '%'),
  ...(VAC_CLEANING
    ? [vacSensor('aktueller_raum', 'Küche'), vacSensor('reinigungsfortschritt', '42', '%'), vacBinary('reinigen', 'on')]
    : [vacBinary('ladestatus', 'on')]),
  vacSensor('letztes_reinigungsende', iso(-95 * 60_000)),
  vacSensor('letzter_reinigungsbeginn', iso(-195 * 60_000)),
  vacSensor('verbleibende_zeit_der_hauptburste', '634362', 's'),
  vacSensor('verbleibende_zeit_der_seitenburste', '274362', 's'),
  vacSensor('verbleibende_filterzeit', '94362', 's'),
  vacSensor('verbleibende_sensorzeit', '-43123', 's'),
  vacBinary('mopp_angebracht', 'on'),
  vacBinary('wasserkasten_angebracht', 'on'),
  vacBinary('dock_mopp_trocknung', VAC_CLEANING ? 'off' : 'on'),
];

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
    todayMin: 15,
    todayMax: 23,
    codeText: 'teilweise bewölkt',
    precipMm: 2.4,
    nowTemp: 21,
    nowCodeText: 'teilweise bewölkt',
    tomorrowMin: 13,
    tomorrowMax: 19,
    tomorrowCodeText: 'Regenschauer',
    ...sun(),
    hourly: hourly(),
    outlook: outlook(),
  }),
  '/api/v1/scheduled': scheduled,
  '/api/v1/lists': shopping,
  '/api/v1/currentaffairs/today': currentAffairs,
  '/api/v1/home/registry': () => ({
    areas: [...climateAreas(), { areaId: 'abstellraum', label: 'Abstellraum', entities: vacuumFamily() }],
    unassigned: [],
    statesFetchedAt: iso(0),
  }),
};

createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const path = normalize(decodeURIComponent(url.pathname));
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

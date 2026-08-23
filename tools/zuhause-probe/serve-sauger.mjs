/**
 * Der Probe-Server für die **Sauger-Kachel** (Andi 21.08.: „Zuletzt gesehen vor
 * 2 Min. … das ist Lärm" + „Was haben wir noch, was man hinzufügen kann, wenn
 * man das Widget größer macht?" + „Können wir den Sauger starten und nach Hause
 * fahren lassen?").
 *
 * Eigene Datei neben `serve.mjs`/`serve-xl.mjs` aus demselben Grund, aus dem es
 * `serve-xl.mjs` schon gibt: die Fixture ist eine ANDERE Behauptung. Hier
 * braucht es die **volle Metrik-Familie** eines echten Roborock — inklusive der
 * vier Felder, die bis 22.08. gemappt, aber nie angezeigt waren
 * (`letztes_reinigungsende`, `letzter_reinigungsbeginn`, `dock_mopp_trocknung`,
 * die vier Wartungs-Restzeiten) — und den **Cache-Carry** `fromCacheSinceMs`,
 * den der BE-Pod am 21.08. gebaut hat. Ohne ihn fotografiert man genau das
 * Bild, über das Andi sich beschwert hat, statt seiner Reparatur.
 *
 * Die Formen stammen aus den echten Parsern (`frontend/src/api/homeRegistry.ts`,
 * `frontend/src/components/homeTiles.ts`): ein Feld, das der Parser verwirft,
 * ist auch hier keins. Die Entity-Ids folgen dem echten Prod-Stamm
 * `roborock_qrevo_pro` (Andi 2026-08-13). Erfunden sind nur die WERTE.
 *
 * `POST /api/v1/home/vacuum/{action}` ist mitbedient, damit man die Knöpfe im
 * Bild auch wirklich drücken kann: `SAUGER_ACTION=fail` lässt ihn mit einer
 * ehrlichen 502 antworten (dieselbe `SettingsError`-Form wie der echte BE),
 * sonst nimmt er an.
 *
 * NUTZUNG: node serve-sauger.mjs <dist-dir> <port>
 */
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';

const ROOT = process.argv[2];
const PORT = Number(process.argv[3] ?? 8796);
const FAIL_ACTIONS = process.env.SAUGER_ACTION === 'fail';
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.json': 'application/json; charset=utf-8',
};

const iso = (offsetMs) => new Date(Date.now() + offsetMs).toISOString();
/**
 * Ein Zeitpunkt VOR jetzt, als ISO — so wie HA `device_class: timestamp`
 * liefert. Bewusst relativ statt „heute 14:20": eine feste Uhrzeit liegt am
 * Vormittag in der ZUKUNFT, und `vacuumLastClean` verwirft ein Ende in der
 * Zukunft (zu Recht — ein Lauf, der noch nicht fertig ist, ist nicht „zuletzt
 * fertig"). Genau daran ist die erste Fixture-Fassung aufgefallen.
 */
const minutesAgo = (min) => new Date(Date.now() - min * 60_000).toISOString();

const STEM = 'roborock_qrevo_pro';
/** Die letzte LIVE-Sichtung: 12 Minuten her — der Normalfall „schläft, aber wir wissen Bescheid". */
const SEEN = Date.now() - 12 * 60_000;

const sensor = (suffix, state, unit) => ({
  entityId: `sensor.${STEM}_${suffix}`,
  domain: 'sensor',
  name: suffix,
  labels: [],
  state,
  ...(unit ? { attrs: { unit_of_measurement: unit } } : {}),
  fromCacheSinceMs: SEEN,
});
const binary = (suffix, state) => ({
  entityId: `binary_sensor.${STEM}_${suffix}`,
  domain: 'binary_sensor',
  name: suffix,
  labels: [],
  state,
  fromCacheSinceMs: SEEN,
});

/**
 * `docked` = der Zustand, über den Andi geschrieben hat: der Sauger schläft im
 * Energiesparmodus, die Werte kommen aus dem Cache. `SAUGER_STATE=cleaning`
 * zeigt das Gegenstück (Fortschritt, Raum, „Zur Basis"-Knopf).
 */
const STATE = process.env.SAUGER_STATE ?? 'docked';
const CLEANING = STATE === 'cleaning';

const vacuumFamily = () => [
  {
    entityId: `vacuum.${STEM}`,
    domain: 'vacuum',
    name: 'Saugroboter',
    labels: [],
    state: STATE,
    attrs: { battery_level: CLEANING ? '63' : '100' },
    ...(CLEANING ? {} : { fromCacheSinceMs: SEEN }),
  },
  sensor('batterie', CLEANING ? '63' : '100', '%'),
  ...(CLEANING
    ? [sensor('aktueller_raum', 'Küche'), sensor('reinigungsfortschritt', '42', '%'), binary('reinigen', 'on')]
    : [binary('ladestatus', 'on')]),
  sensor('letztes_reinigungsende', minutesAgo(95)),
  sensor('letzter_reinigungsbeginn', minutesAgo(195)),
  // Andis wörtliches Bild (22.08.) UND die echte HA-Einheit — nicht mehr 'h'
  // geraten: HA Cores `roborock`-Integration (sensor.py, `*_time_left`-Keys)
  // führt diese vier als `native_unit_of_measurement=UnitOfTime.SECONDS`,
  // `device_class=DURATION`. Die alte Fixture-Annahme ('h', erfundene Werte
  // 243/123/87/18) war selbst eine geratene Einheit — genau das, was §-Regel
  // „Einheiten nie raten" verbietet. Diese vier Werte sind Andis reale Zahlen,
  // damit `shot-sauger.mjs` sein Vorher-Bild wortgleich zu seiner Beschwerde
  // zeigt (inkl. der negativen, ÜBERFÄLLIGEN Sensoren-Restzeit).
  sensor('verbleibende_zeit_der_hauptburste', '634362', 's'),
  sensor('verbleibende_zeit_der_seitenburste', '274362', 's'),
  sensor('verbleibende_filterzeit', '94362', 's'),
  sensor('verbleibende_sensorzeit', '-43123', 's'),
  binary('mopp_angebracht', 'on'),
  binary('wasserkasten_angebracht', 'on'),
  binary('dock_mopp_trocknung', CLEANING ? 'off' : 'on'),
];

const API = {
  '/api/health': () => ({ ok: true }),
  '/api/v1/home/registry': () => ({
    areas: [{ areaId: 'abstellraum', label: 'Abstellraum', entities: vacuumFamily() }],
    unassigned: [],
    statesFetchedAt: iso(0),
  }),
};

createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const path = normalize(decodeURIComponent(url.pathname));

  if (req.method === 'POST' && path.startsWith('/api/v1/home/vacuum/')) {
    const action = path.slice('/api/v1/home/vacuum/'.length);
    if (action !== 'start' && action !== 'return_to_base') {
      res.writeHead(400, { 'content-type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: 'vacuum-unknown-action', id: 'vacuum-action', message: 'Unbekannte Aktion.' }));
      return;
    }
    if (FAIL_ACTIONS) {
      res.writeHead(502, { 'content-type': 'application/json; charset=utf-8' });
      res.end(
        JSON.stringify({
          error: 'vacuum-action-failed',
          id: 'vacuum-action',
          message: 'Home Assistant hat die Tat nicht angenommen (HTTP 500).',
        }),
      );
      return;
    }
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ action, entityId: `vacuum.${STEM}`, accepted: true, haStatus: 200 }));
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
    try {
      const body = await readFile(join(ROOT, 'index.html'));
      res.writeHead(200, { 'content-type': TYPES['.html'] });
      res.end(body);
    } catch {
      res.writeHead(404, { 'content-type': 'text/plain' });
      res.end('404');
    }
  }
}).listen(PORT, '127.0.0.1', () => console.log(`serving ${ROOT} on ${PORT} (state=${STATE}, actions=${FAIL_ACTIONS ? 'fail' : 'ok'})`));

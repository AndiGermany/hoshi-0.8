import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { CLIMATE_TILE_XL_VISIBLE, ClimateTile, VacuumTile } from '../components/HomeTileCards';
import { IdleFace } from '../components/IdleFace';
import { homeTileLastSeenStorageKey } from '../hooks/useSettings';
import type { HomeRegistryEntity, HomeRegistrySnapshot, HomeRegistryState } from '../api/homeRegistry';

/**
 * **hometilecards.test** — Render-Zustände der Sauger-/Klima-Kachel
 * (Andi-Auftrag 2026-08-11). Rein prop-getrieben (`registry`-Prop, Muster
 * `idleface.test.tsx`) → `renderToStaticMarkup` ohne DOM/Fetch. Deckt: aktiv
 * (bekannter Zustand), nicht erreichbar (Registry aus/kaputt/kein Sauger/kein
 * Klima-Raum), Amber NUR bei `error`, max-4-Zeilen-Faltung der Klima-Kachel.
 */

const entity = (over: Partial<HomeRegistryEntity> = {}): HomeRegistryEntity => ({
  entityId: 'vacuum.rob',
  domain: 'vacuum',
  name: 'Rob',
  labels: [],
  ...over,
});

const live = (data: HomeRegistrySnapshot): HomeRegistryState => ({ kind: 'live', data });
const snapshot = (over: Partial<HomeRegistrySnapshot> = {}): HomeRegistrySnapshot => ({
  areas: [],
  unassigned: [],
  ...over,
});

describe('VacuumTile — Zustands-Mapping + Amber nur bei error', () => {
  it('cleaning ⇒ „Saugt gerade", kein Amber — und KEINE live-Pille mehr (W6)', () => {
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [entity({ state: 'cleaning' })] }))} />,
    );
    expect(html).toContain('Saugt gerade');
    expect(html).toContain('tile--live');
    expect(html).not.toContain('tile--warn');
    // W6 (Andi 20.08.: „Das Live kann aus den Widgets raus"): das Abzeichen ist
    // weg, die AUSSAGE nicht — der Satz „Saugt gerade" oben ist die Auskunft,
    // die die Pille nur wiederholt hat. Geprüft wird die Klasse, nicht das
    // Wort: `>live<` stünde auch in `data-status="live"` nicht, aber
    // `tile__pill` ist der Träger, um den es geht.
    expect(html).not.toContain('tile__pill');
  });

  it.each([
    ['docked', 'Bereit in der Ladestation'],
    ['returning', 'Fährt zurück'],
    ['paused', 'Pausiert'],
    ['idle', 'Bereit'],
  ] as const)('%s ⇒ "%s"', (state, text) => {
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [entity({ state })] }))} />,
    );
    expect(html).toContain(text);
  });

  it('error ⇒ „Braucht Hilfe" + Amber-Klasse tile--warn, aber weiterhin live (echter Zustand)', () => {
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [entity({ state: 'error' })] }))} />,
    );
    expect(html).toContain('Braucht Hilfe');
    expect(html).toContain('tile--warn');
  });

  it('battery_level vorhanden ⇒ Akku-Zeile zusätzlich', () => {
    const html = renderToStaticMarkup(
      <VacuumTile
        registry={live(snapshot({ unassigned: [entity({ state: 'docked', attrs: { battery_level: '87' } })] }))}
      />,
    );
    expect(html).toContain('87');
  });

  it('battery_level fehlt ⇒ keine Akku-Zeile', () => {
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [entity({ state: 'docked' })] }))} />,
    );
    expect(html).not.toContain('idle__hometilesub');
  });

  it('kein Sauger in der Registry ⇒ ehrliche stille Zeile, KEIN Amber', () => {
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot())} />);
    expect(html).toContain('idle__hometileunavailable');
    expect(html).not.toContain('tile--warn');
    expect(html).not.toContain('>live<');
  });

  it("state 'unavailable' ⇒ nicht erreichbar, KEIN Amber", () => {
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [entity({ state: 'unavailable' })] }))} />,
    );
    expect(html).toContain('idle__hometileunavailable');
    expect(html).not.toContain('tile--warn');
  });

  it("Registry null (Fetch läuft)/'off'/'unreachable' ⇒ dieselbe ehrliche stille Zeile", () => {
    for (const registry of [null, { kind: 'off' } as const, { kind: 'unreachable' } as const]) {
      const html = renderToStaticMarkup(<VacuumTile registry={registry} />);
      expect(html).toContain('idle__hometileunavailable');
      expect(html).not.toContain('tile--warn');
    }
  });
});

// ─────────────────────────────────────────────────────────────────────────
//  Sauger-Metrik-Familie (Andi-Auftrag 2026-08-13): die Geschwister-Entities
//  (sensor.*/binary_sensor.* mit demselben Stamm-Präfix), NICHT die vacuum-
//  Attribute. Der reale Prod-Zustand beim Bau: die GANZE Roborock-Familie war
//  `unavailable` — der letzte Fall hier bildet genau das ab.
// ─────────────────────────────────────────────────────────────────────────

const member = (entityId: string, domain: string, over: Partial<HomeRegistryEntity> = {}): HomeRegistryEntity => ({
  entityId,
  domain,
  name: entityId,
  labels: [],
  ...over,
});

describe('VacuumTile — Metrik-Familie', () => {
  it('Hybrid: vacuum unavailable, aber reinigen=on ⇒ Hybrid-Satz MIT ehrlicher Quelle, live, kein Amber', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'unavailable' });
    const cleaning = member('binary_sensor.rob_reinigen', 'binary_sensor', { state: 'on' });
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot({ unassigned: [vac, cleaning] }))} />);
    expect(html).toContain('Sensor');
    // „live" heißt seit W6 `data-status`/`tile--live` — nicht mehr ein Abzeichen
    // im Kopf. Der Zustand ist unverändert, nur seine Anzeige ist still.
    expect(html).toContain('data-status="live"');
    expect(html).not.toContain('tile__pill');
    expect(html).not.toContain('tile--warn');
  });

  it('aktueller_raum wird an den Status-Satz angehängt — Raumname roh (NUTZERDATEN)', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'cleaning' });
    const currentRoom = member('sensor.rob_aktueller_raum', 'sensor', { state: 'Küche' });
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot({ unassigned: [vac, currentRoom] }))} />);
    expect(html).toContain('Saugt gerade');
    expect(html).toContain('Küche');
  });

  it('reinigungsfortschritt NUR während reinigen=on (L — Fortschritt ist ein L-Feld, §3.2)', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'cleaning' });
    const progress = member('sensor.rob_reinigungsfortschritt', 'sensor', { state: '55' });
    const cleaningOn = member('binary_sensor.rob_reinigen', 'binary_sensor', { state: 'on' });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac, progress, cleaningOn] }))} size="L" />,
    );
    expect(html).toContain('55 %');
  });

  it('Amber via Familie (wasserknappheit=on), obwohl vacuum.state NICHT error ist', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'cleaning' });
    const waterShortage = member('binary_sensor.rob_wasserknappheit', 'binary_sensor', { state: 'on' });
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot({ unassigned: [vac, waterShortage] }))} />);
    expect(html).toContain('tile--warn');
  });

  it('staubsauger_fehler mit echtem Fehlwert ⇒ Amber + eigene Zeile mit dem rohen Wert (L — Fehlerdetails sind ein L-Feld, §3.2)', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'cleaning' });
    const vacuumError = member('sensor.rob_staubsauger_fehler', 'sensor', { state: 'E1' });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac, vacuumError] }))} size="L" />,
    );
    expect(html).toContain('tile--warn');
    expect(html).toContain('E1');
  });

  it("staubsauger_fehler='ok' ⇒ KEIN Amber (defensiver Sentinel)", () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'cleaning' });
    const vacuumError = member('sensor.rob_staubsauger_fehler', 'sensor', { state: 'ok' });
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot({ unassigned: [vac, vacuumError] }))} />);
    expect(html).not.toContain('tile--warn');
  });

  it('Wartungsblock zeigt Bürsten-Restzeit (Wert+Einheit) und Mopp-Info AUFGEKLAPPT (L — §3.2: „Auf 2×2 ist ein Fold sinnlos")', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked' });
    const mainBrush = member('sensor.rob_verbleibende_zeit_der_hauptburste', 'sensor', {
      state: '120',
      attrs: { unit_of_measurement: 'h' },
    });
    const moppAttached = member('binary_sensor.rob_mopp_angebracht', 'binary_sensor', { state: 'on' });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac, mainBrush, moppAttached] }))} size="L" />,
    );
    // Kein `<details>` mehr: die Fläche ist da, der Aufklapper war nur eine
    // Bitte um einen zweiten Klick für Werte, die längst hinpassen.
    expect(html).not.toContain('<details');
    expect(html).toContain('Wartung');
    // 120 h = 432000 s ≥ 48 h ⇒ Tage-Bucket, exakt: 432000/86400 = 5 (seit
    // 22.08. lesbar formatiert statt roher Wert+Einheit).
    expect(html).toContain('Hauptbürste: noch ~5 Tage');
    expect(html).toContain('Mopp dran');
  });

  it('keine Familienmitglieder brauchbar ⇒ gar kein Wartungsblock, auch keine Überschrift (L)', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked' });
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot({ unassigned: [vac] }))} size="L" />);
    expect(html).not.toContain('<details');
    // Eine Rubrik „Wartung" über null Zeilen wäre genau das Auffüllen, das
    // §2.3 verbietet.
    expect(html).not.toContain('Wartung');
  });

  it('die GANZE Familie unavailable (realer Prod-Zustand: Roborock offline) ⇒ ehrliche stille Zeile, kein Amber', () => {
    const vac = entity({ entityId: 'vacuum.roborock_qrevo_pro', state: 'unavailable' });
    const battery = member('sensor.roborock_qrevo_pro_batterie', 'sensor', { state: 'unavailable' });
    const cleaning = member('binary_sensor.roborock_qrevo_pro_reinigen', 'binary_sensor', { state: 'unavailable' });
    const charging = member('binary_sensor.roborock_qrevo_pro_ladestatus', 'binary_sensor', { state: 'unavailable' });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac, battery, cleaning, charging] }))} />,
    );
    expect(html).toContain('idle__hometileunavailable');
    expect(html).not.toContain('tile--warn');
    expect(html).not.toContain('>live<');
  });
});

// ─────────────────────────────────────────────────────────────────────────
//  Last-known-good-Fallback (Andi-Auftrag 2026-08-13, „Sauger-Sichtbarkeits-
//  Lücke" — der Roborock hängt ~23 h/Tag im WLAN-Tiefschlaf, das Poll-Raster
//  trifft sein Wach-Fenster fast nie). Drei Zustände: LIVE schlägt lastKnown
//  (auch wenn beides da ist); lastKnown-Zeile wenn NUR das da ist; weder noch
//  ⇒ die alte ehrliche „nicht erreichbar"-Zeile.
// ─────────────────────────────────────────────────────────────────────────

const FIXED_NOW = Date.parse('2026-08-13T20:03:00.000Z');

describe('VacuumTile — Last-known-good-Fallback', () => {
  it('LIVE schlägt lastKnown: ein brauchbarer Live-Zustand zeigt NIE die Zuletzt-gesehen-Zeile', () => {
    const vac = entity({
      entityId: 'vacuum.rob',
      state: 'cleaning',
      lastKnown: { state: 'docked', attrs: { battery_level: '50' }, seenAt: '2026-08-13T08:00:00.000Z' },
    });
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot({ unassigned: [vac] }))} nowMs={FIXED_NOW} />);
    expect(html).toContain('Saugt gerade');
    expect(html).not.toContain('Zuletzt gesehen');
  });

  it('live unbrauchbar + lastKnown da ⇒ „Zuletzt gesehen <relativ>: <warmer Satz>", KEIN Amber, KEIN live-Pill', () => {
    const vac = entity({
      entityId: 'vacuum.rob',
      state: 'unavailable',
      lastKnown: { state: 'docked', attrs: { battery_level: '82' }, seenAt: '2026-08-13T08:00:00.000Z' }, // 12h05 vorher
    });
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot({ unassigned: [vac] }))} nowMs={FIXED_NOW} />);
    expect(html).toContain('Zuletzt gesehen');
    expect(html).toContain('Bereit in der Ladestation');
    expect(html).toContain('vor 12 Std.');
    expect(html).toContain('82');
    expect(html).not.toContain('tile--warn');
    expect(html).not.toContain('tile__pill');
  });

  it('ein ALTER error-Zustand im lastKnown erscheint OHNE Amber (ein alter Fehler ist kein aktueller Alarm)', () => {
    const vac = entity({
      entityId: 'vacuum.rob',
      state: 'unavailable',
      lastKnown: { state: 'error', attrs: {}, seenAt: '2026-08-13T08:00:00.000Z' },
    });
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot({ unassigned: [vac] }))} nowMs={FIXED_NOW} />);
    expect(html).toContain('Braucht Hilfe');
    expect(html).not.toContain('tile--warn');
  });

  it('weder live brauchbar NOCH lastKnown ⇒ die alte ehrliche stille Zeile bleibt', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'unavailable' });
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot({ unassigned: [vac] }))} nowMs={FIXED_NOW} />);
    expect(html).toContain('idle__hometileunavailable');
    expect(html).not.toContain('Zuletzt gesehen');
  });

  it('kein Sauger überhaupt ⇒ unverändert die alte stille Zeile, kein Fallback erfunden', () => {
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot())} nowMs={FIXED_NOW} />);
    expect(html).toContain('idle__hometileunavailable');
    expect(html).not.toContain('Zuletzt gesehen');
  });

  it('ohne nowMs-Prop (Bestandsaufrufer) faellt die Komponente auf Date.now() zurueck und bricht nicht', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'cleaning' });
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot({ unassigned: [vac] }))} />);
    expect(html).toContain('Saugt gerade');
  });
});

describe('ClimateTile — eine Zeile je Raum, max 4 + Faltung, heizt-Indikator', () => {
  const room = (id: string, current: string, target: string, heating = false) =>
    entity({
      entityId: `climate.${id}`,
      domain: 'climate',
      state: 'heat',
      attrs: { current_temperature: current, temperature: target, hvac_action: heating ? 'heating' : 'idle' },
    });

  it('ein Raum mit climate ⇒ „<Raum> 21,5° → 22°"', () => {
    const html = renderToStaticMarkup(
      <ClimateTile
        registry={live(snapshot({ areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [room('wz', '21.5', '22')] }] }))}
      />,
    );
    expect(html).toContain('Wohnzimmer');
    expect(html).toContain('21,5°');
    expect(html).toContain('22°');
    expect(html).toContain('→');
  });

  it('hvac_action heating ⇒ heizt-Indikator; sonst weg', () => {
    const heating = renderToStaticMarkup(
      <ClimateTile
        registry={live(
          snapshot({ areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [room('wz', '21', '22', true)] }] }),
        )}
      />,
    );
    expect(heating).toContain('heizt');

    const idle = renderToStaticMarkup(
      <ClimateTile
        registry={live(
          snapshot({ areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [room('wz', '21', '22', false)] }] }),
        )}
      />,
    );
    expect(idle).not.toContain('heizt');
  });

  it('Raum ohne climate-Entity trägt keine Zeile', () => {
    const html = renderToStaticMarkup(
      <ClimateTile
        registry={live(
          snapshot({
            areas: [
              { areaId: 'wz', label: 'Wohnzimmer', entities: [room('wz', '21', '22')] },
              { areaId: 'flur', label: 'Flur', entities: [entity({ domain: 'light', entityId: 'light.flur' })] },
            ],
          }),
        )}
      />,
    );
    expect(html).toContain('Wohnzimmer');
    expect(html).not.toContain('Flur');
  });

  it('mehr als 4 Räume ⇒ die ersten 4 direkt sichtbar, Rest gefaltet hinter „+n weitere Räume" (L — 4 sichtbare Reihen sind ein L-Feld, §3.3)', () => {
    const areas = ['a', 'b', 'c', 'd', 'e', 'f'].map((id) => ({
      areaId: id,
      label: `Raum ${id.toUpperCase()}`,
      entities: [room(id, '20', '21')],
    }));
    const html = renderToStaticMarkup(<ClimateTile registry={live(snapshot({ areas }))} size="L" />);
    for (const label of ['Raum A', 'Raum B', 'Raum C', 'Raum D']) expect(html).toContain(label);
    expect(html).toContain('+2 weitere Räume');
    expect(html).toContain('<details');
    // die gefalteten Räume stehen trotzdem im Markup (aufklappbar, nicht verloren)
    expect(html).toContain('Raum E');
    expect(html).toContain('Raum F');
  });

  it('XL (§3.3/W5): 12 Raumzeilen ZWEISPALTIG, der Rest weiter gefaltet', () => {
    // 14 Räume, damit der Deckel überhaupt greift: die Selbstabnahme hat ihn
    // von 8 auf 12 gehoben, weil 8 Zeilen bei 10 echten Räumen zwei davon
    // wegfalteten und darunter 55 % der Kachel leer blieben.
    const areas = 'abcdefghijklmn'.split('').map((id) => ({
      areaId: id,
      label: `Raum ${id.toUpperCase()}`,
      entities: [room(id, '20', '21')],
    }));
    const html = renderToStaticMarkup(<ClimateTile registry={live(snapshot({ areas }))} size="XL" />);
    expect(html).toContain('idle__cardlist--two');
    // Genau 12 offene Zeilen — die Liste VOR dem <details> zählen.
    const offen = html.slice(0, html.indexOf('<details'));
    expect(offen.split('<li').length - 1).toBe(CLIMATE_TILE_XL_VISIBLE);
    for (const label of ['Raum A', 'Raum L']) expect(offen).toContain(label);
    expect(offen).not.toContain('Raum M');
    expect(html).toContain('+2 weitere Räume');
    // Der Nachschlag bleibt einspaltig — er ist ein Anhang, keine zweite Liste.
    const fold = html.slice(html.indexOf('<details'));
    expect(fold).not.toContain('idle__cardlist--two');
  });

  it('XL erfindet keine Zeilen: drei echte Räume ⇒ drei Zeilen und ruhige Fläche (§2.3)', () => {
    const areas = ['a', 'b', 'c'].map((id) => ({
      areaId: id,
      label: `Raum ${id.toUpperCase()}`,
      entities: [room(id, '20', '21')],
    }));
    const html = renderToStaticMarkup(<ClimateTile registry={live(snapshot({ areas }))} size="XL" />);
    expect(html.split('<li').length - 1).toBe(3);
    expect(html).not.toContain('<details');
    expect(html).not.toContain('weitere Räume');
  });

  it('L bleibt EINspaltig bei 4 Zeilen — die zweite Spalte gehört XL', () => {
    const areas = ['a', 'b', 'c', 'd', 'e'].map((id) => ({
      areaId: id,
      label: `Raum ${id.toUpperCase()}`,
      entities: [room(id, '20', '21')],
    }));
    const html = renderToStaticMarkup(<ClimateTile registry={live(snapshot({ areas }))} size="L" />);
    expect(html).not.toContain('idle__cardlist--two');
    expect(html.slice(0, html.indexOf('<details')).split('<li').length - 1).toBe(4);
  });

  it('der SAUGER hat seit 22.08. ein XL: dieselben Zeilen, Wartung zweispaltig (nichts erfunden)', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'cleaning' });
    const filter = member('sensor.rob_verbleibende_filterzeit', 'sensor', { state: '90' });
    const reg = live(snapshot({ unassigned: [vac, filter] }));
    const xl = renderToStaticMarkup(<VacuumTile registry={reg} size="XL" />);
    const l = renderToStaticMarkup(<VacuumTile registry={reg} size="L" />);
    // Der INHALT ist derselbe — XL erfindet keine Zeile dazu (§2.3).
    expect(xl).toContain('Filter: 90');
    expect(l).toContain('Filter: 90');
    // Unterschied ist allein das Layout: zwei Spalten statt einer.
    expect(xl).toContain('idle__cardlist--two');
    expect(l).not.toContain('idle__cardlist--two');
  });

  it('ein einzelner Raum ohne lesbaren State ⇒ eigene stille Zeile, der Rest der Kachel bleibt stehen', () => {
    const html = renderToStaticMarkup(
      <ClimateTile
        registry={live(
          snapshot({
            areas: [
              { areaId: 'wz', label: 'Wohnzimmer', entities: [room('wz', '21', '22')] },
              {
                areaId: 'bad',
                label: 'Bad',
                entities: [entity({ entityId: 'climate.bad', domain: 'climate', state: 'unavailable' })],
              },
            ],
          }),
        )}
      />,
    );
    expect(html).toContain('Wohnzimmer');
    expect(html).toContain('21°');
    expect(html).toContain('Bad grad nicht erreichbar.');
  });

  it('kein Raum mit climate ⇒ die ganze Kachel ehrlich nicht erreichbar', () => {
    const html = renderToStaticMarkup(<ClimateTile registry={live(snapshot())} />);
    expect(html).toContain('idle__hometileunavailable');
    expect(html).not.toContain('>live<');
  });

  it("Registry null/'off'/'unreachable' ⇒ dieselbe ehrliche stille Zeile", () => {
    for (const registry of [null, { kind: 'off' } as const, { kind: 'unreachable' } as const]) {
      const html = renderToStaticMarkup(<ClimateTile registry={registry} />);
      expect(html).toContain('idle__hometileunavailable');
    }
  });
});

describe('ClimateTile — Last-known-good-Fallback je Raum-Zeile (Andi-Auftrag 2026-08-13)', () => {
  const FIXED_NOW_CLIMATE = Date.parse('2026-08-13T20:03:00.000Z');

  it('LIVE schlägt lastKnown: ein brauchbarer Live-Wert zeigt NIE die Zuletzt-gesehen-Zeile', () => {
    const climate = entity({
      entityId: 'climate.wz',
      domain: 'climate',
      state: 'heat',
      attrs: { current_temperature: '21.5', temperature: '22' },
      lastKnown: { state: 'heat', attrs: { current_temperature: '18', temperature: '20' }, seenAt: '2026-08-13T08:00:00.000Z' },
    });
    const html = renderToStaticMarkup(
      <ClimateTile
        registry={live(snapshot({ areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [climate] }] }))}
        nowMs={FIXED_NOW_CLIMATE}
      />,
    );
    expect(html).toContain('21,5°');
    expect(html).not.toContain('zuletzt');
  });

  it('live unbrauchbar + lastKnown da ⇒ Raum-Zeile mit ALTEN Temperaturen + Alter, statt „grad nicht erreichbar"', () => {
    const climate = entity({
      entityId: 'climate.bad',
      domain: 'climate',
      state: 'unavailable',
      lastKnown: { state: 'heat', attrs: { current_temperature: '19', temperature: '21' }, seenAt: '2026-08-13T08:00:00.000Z' },
    });
    const html = renderToStaticMarkup(
      <ClimateTile
        registry={live(snapshot({ areas: [{ areaId: 'bad', label: 'Bad', entities: [climate] }] }))}
        nowMs={FIXED_NOW_CLIMATE}
      />,
    );
    expect(html).toContain('Bad');
    expect(html).toContain('19°');
    expect(html).toContain('21°');
    expect(html).toContain('vor 12 Std.');
    expect(html).not.toContain('Bad grad nicht erreichbar.');
  });

  it('weder live brauchbar NOCH lastKnown ⇒ die alte ehrliche Zeile „<Raum> grad nicht erreichbar." bleibt', () => {
    const climate = entity({ entityId: 'climate.bad', domain: 'climate', state: 'unavailable' });
    const html = renderToStaticMarkup(
      <ClimateTile
        registry={live(snapshot({ areas: [{ areaId: 'bad', label: 'Bad', entities: [climate] }] }))}
        nowMs={FIXED_NOW_CLIMATE}
      />,
    );
    expect(html).toContain('Bad grad nicht erreichbar.');
  });

  it('ein Raum MIT lastKnown neben einem normal erreichbaren Raum — der Rest der Kachel bleibt unberührt', () => {
    const good = entity({
      entityId: 'climate.wz',
      domain: 'climate',
      state: 'heat',
      attrs: { current_temperature: '21', temperature: '22' },
    });
    const stale = entity({
      entityId: 'climate.bad',
      domain: 'climate',
      state: 'unavailable',
      lastKnown: { state: 'heat', attrs: { current_temperature: '19', temperature: '21' }, seenAt: '2026-08-13T08:00:00.000Z' },
    });
    const html = renderToStaticMarkup(
      <ClimateTile
        registry={live(
          snapshot({
            areas: [
              { areaId: 'wz', label: 'Wohnzimmer', entities: [good] },
              { areaId: 'bad', label: 'Bad', entities: [stale] },
            ],
          }),
        )}
        nowMs={FIXED_NOW_CLIMATE}
      />,
    );
    expect(html).toContain('Wohnzimmer');
    expect(html).toContain('21°');
    expect(html).toContain('Bad');
    expect(html).toContain('vor 12 Std.');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  IdleFace-Verdrahtung: die beiden neuen optionalen Props (`homeRegistry`,
//  `vacuumTileEnabled`, `climateTileEnabled`) — Default AUS, alte Aufrufer
//  (idleface.test.tsx/i18n(sweep).test.tsx ohne diese Props) bleiben
//  unverändert byte-gleich.
// ─────────────────────────────────────────────────────────────────────────────

const baseProps = { nowMs: Date.UTC(2026, 7, 11, 8, 0), scheduled: [], weather: null, shopping: [] };

describe('IdleFace — Zuhause-Kacheln nur bei aktiviertem Schalter', () => {
  it('ohne die neuen Props (Bestandsaufruf) ⇒ keine Kachel, kein Bruch', () => {
    const html = renderToStaticMarkup(<IdleFace {...baseProps} />);
    expect(html).not.toContain('idle__hometileline');
    expect(html).not.toContain('idle__hometileunavailable');
  });

  it('vacuumTileEnabled:true ⇒ die Sauger-Kachel rendert; climateTileEnabled bleibt aus ⇒ keine Klima-Kachel', () => {
    const registry: HomeRegistryState = {
      kind: 'live',
      data: { areas: [], unassigned: [{ entityId: 'vacuum.rob', domain: 'vacuum', name: 'Rob', labels: [], state: 'cleaning' }] },
    };
    const html = renderToStaticMarkup(
      <IdleFace {...baseProps} homeRegistry={registry} vacuumTileEnabled climateTileEnabled={false} />,
    );
    expect(html).toContain('Saugt gerade');
    expect(html).not.toContain('idle__cardlist--climate');
  });

  it('beide Kacheln aktiv ⇒ beide rendern nebeneinander', () => {
    const registry: HomeRegistryState = {
      kind: 'live',
      data: {
        areas: [
          {
            areaId: 'wz',
            label: 'Wohnzimmer',
            entities: [
              {
                entityId: 'climate.wz',
                domain: 'climate',
                name: 'Thermostat',
                labels: [],
                state: 'heat',
                attrs: { current_temperature: '21', temperature: '22' },
              },
            ],
          },
        ],
        unassigned: [{ entityId: 'vacuum.rob', domain: 'vacuum', name: 'Rob', labels: [], state: 'docked' }],
      },
    };
    const html = renderToStaticMarkup(
      <IdleFace {...baseProps} homeRegistry={registry} vacuumTileEnabled climateTileEnabled />,
    );
    expect(html).toContain('Bereit in der Ladestation');
    expect(html).toContain('Wohnzimmer');
  });
});

// ═════════════════════════════════════════════════════════════════════════════
//  S2 „Ehrliche Anwesenheit" (DESIGN-widgets-settings-2026-08-15 §2.4)
//
//  Die Golden-Fälle der Scheibe, alle drei aus derselben Regel:
//    Quelle war schon einmal da + jetzt weg ⇒ „Nicht erreichbar — zuletzt
//                                             gesehen <Dauer>", 18px, nie Amber
//    Quelle nie gesehen                    ⇒ UNVERÄNDERT die stille 13px-Zeile
//    noch keine Antwort (registry null)    ⇒ ebenfalls still (kein Vorwurf
//                                             während des Ladeblitzers)
//
//  Gelesen wird hier nur — `renderToStaticMarkup` führt keine Effekte aus, das
//  Schreiben der Stempel prüft `hometilelastseen.test.tsx` am echten Mount.
//  Die Stempel werden darum direkt in einen In-Memory-Storage gelegt; nach
//  jedem Test wird er wieder abgeräumt, damit die Fälle oben (die alle ohne
//  Gedächtnis rechnen) unberührt bleiben.
// ═════════════════════════════════════════════════════════════════════════════

/** In-Memory-Storage in DOM-`Storage`-Form (node kennt kein echtes localStorage). */
function memoryStorage(seed: Record<string, string> = {}): Storage {
  const m = new Map<string, string>(Object.entries(seed));
  return {
    get length() {
      return m.size;
    },
    clear: () => m.clear(),
    getItem: (k: string) => (m.has(k) ? (m.get(k) as string) : null),
    key: (i: number) => Array.from(m.keys())[i] ?? null,
    removeItem: (k: string) => {
      m.delete(k);
    },
    setItem: (k: string, v: string) => {
      m.set(k, String(v));
    },
  };
}

describe('S2 — Rückfall einer BEKANNTEN Quelle bekommt ehrliche Minimal-Präsenz', () => {
  /** 12 h 03 vor FIXED_NOW — die Stufe „vor 12 Std." des bestehenden Alters-Idioms. */
  const SEEN_12H_AGO = FIXED_NOW - 12 * 60 * 60 * 1000 - 3 * 60 * 1000;

  const remember = (key: 'vacuum' | 'climate', atMs: number) =>
    vi.stubGlobal(
      'localStorage',
      memoryStorage({ [homeTileLastSeenStorageKey(key)]: String(atMs) }),
    );

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('Sauger: war da, jetzt weg ⇒ „Nicht erreichbar — zuletzt gesehen vor 12 Std." in der 18px-Zeile', () => {
    remember('vacuum', SEEN_12H_AGO);
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot())} nowMs={FIXED_NOW} />);
    expect(html).toContain('Nicht erreichbar — zuletzt gesehen vor 12 Std.');
    expect(html).toContain('idle__hometilestale'); // 18px/--text-3, nicht das 13px-Flüstern
    expect(html).not.toContain('idle__hometileunavailable');
    expect(html).not.toContain('tile--warn'); // ein alter Ausfall ist NIE ein Alarm
    expect(html).not.toContain('>live<'); // und auch kein „live"
  });

  it('Sauger: nie gesehen ⇒ UNVERÄNDERT die stille Zeile, keine erfundene Dauer', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot())} nowMs={FIXED_NOW} />);
    expect(html).toContain('idle__hometileunavailable');
    expect(html).not.toContain('Nicht erreichbar — zuletzt gesehen');
  });

  it('Sauger: BE-`lastKnown` schlägt das FE-Gedächtnis (echte alte Daten sind mehr wert als „war mal da")', () => {
    remember('vacuum', SEEN_12H_AGO);
    const vac = entity({
      entityId: 'vacuum.rob',
      state: 'unavailable',
      lastKnown: { state: 'docked', attrs: {}, seenAt: '2026-08-13T08:00:00.000Z' },
    });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac] }))} nowMs={FIXED_NOW} />,
    );
    expect(html).toContain('Zuletzt gesehen');
    expect(html).toContain('Bereit in der Ladestation');
    expect(html).not.toContain('Nicht erreichbar — zuletzt gesehen');
  });

  it('Sauger: registry noch `null` (erster Fetch läuft) ⇒ still, obwohl ein Stempel da ist', () => {
    remember('vacuum', SEEN_12H_AGO);
    const html = renderToStaticMarkup(<VacuumTile registry={null} nowMs={FIXED_NOW} />);
    expect(html).toContain('idle__hometileunavailable');
    expect(html).not.toContain('Nicht erreichbar — zuletzt gesehen');
  });

  it('Sauger: Naht aus/kaputt (`off`/`unreachable`) IST eine Antwort ⇒ ehrliche Präsenz', () => {
    for (const kind of ['off', 'unreachable'] as const) {
      remember('vacuum', SEEN_12H_AGO);
      const html = renderToStaticMarkup(<VacuumTile registry={{ kind }} nowMs={FIXED_NOW} />);
      expect(html, kind).toContain('Nicht erreichbar — zuletzt gesehen vor 12 Std.');
      vi.unstubAllGlobals();
    }
  });

  it('Klima: war da, jetzt kein Raum mehr lesbar ⇒ dieselbe ehrliche Zeile', () => {
    remember('climate', SEEN_12H_AGO);
    const html = renderToStaticMarkup(<ClimateTile registry={live(snapshot())} nowMs={FIXED_NOW} />);
    expect(html).toContain('Nicht erreichbar — zuletzt gesehen vor 12 Std.');
    expect(html).toContain('idle__hometilestale');
    expect(html).not.toContain('idle__hometileunavailable');
  });

  it('Klima: nie gesehen ⇒ UNVERÄNDERT die stille Zeile', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    const html = renderToStaticMarkup(<ClimateTile registry={live(snapshot())} nowMs={FIXED_NOW} />);
    expect(html).toContain('idle__hometileunavailable');
    expect(html).not.toContain('Nicht erreichbar — zuletzt gesehen');
  });

  it('die Kachel-Gedächtnisse sind getrennt: ein gesehener Sauger macht das Klima nicht laut', () => {
    remember('vacuum', SEEN_12H_AGO);
    const html = renderToStaticMarkup(<ClimateTile registry={live(snapshot())} nowMs={FIXED_NOW} />);
    expect(html).toContain('idle__hometileunavailable');
    expect(html).not.toContain('Nicht erreichbar — zuletzt gesehen');
  });

  it('kaputter Stempel (Müll/negativ) ⇒ still statt einer erfundenen Dauer', () => {
    for (const junk of ['nope', '-1', '0', '']) {
      vi.stubGlobal(
        'localStorage',
        memoryStorage({ [homeTileLastSeenStorageKey('vacuum')]: junk }),
      );
      const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot())} nowMs={FIXED_NOW} />);
      expect(html, junk).toContain('idle__hometileunavailable');
      expect(html, junk).not.toContain('Nicht erreichbar — zuletzt gesehen');
      vi.unstubAllGlobals();
    }
  });
});

/**
 * **W5-Regression.** Die Bühne setzt die berechnete Zelle per `cloneElement`
 * als `style.gridColumn/gridRow` auf das Wurzelelement dessen, was `node()`
 * liefert (`placeTile`, `HomeStage.tsx`). Klima und Sauger liefern als einzige
 * eine KOMPONENTE statt eines `<article>` — schluckt die das `style`-Prop,
 * fällt die Kachel stumm auf CSS-Auto-Platzierung zurück und steht auf 1×1,
 * während ihr Inhalt mit voller Bühnenbreite rechnet. Genau so gemessen am
 * 20.08.: 285 × 269 px statt 880 × 550.
 *
 * jsdom rechnet kein Layout, also prüft dieser Block das, was die Zelle
 * überhaupt erst wirksam macht: dass die Attribute am `<article>` ankommen —
 * und zwar in JEDEM Rückfall-Zweig, nicht nur im Live-Zweig.
 */
describe('W5 — die Kacheln reichen die Zelle der Bühne durch', () => {
  const cell = { gridColumn: '1 / span 3', gridRow: '1 / span 2' } as const;

  it('Klima (live) trägt grid-column/grid-row und die Widget-Id am <article>', () => {
    const html = renderToStaticMarkup(
      <ClimateTile
        registry={live(
          snapshot({
            areas: [
              {
                areaId: 'bad',
                label: 'Bad',
                entities: [
                  entity({
                    entityId: 'climate.bad',
                    domain: 'climate',
                    state: 'heat',
                    attrs: { current_temperature: '23.4', temperature: '24', hvac_action: 'heating' },
                  }),
                ],
              },
            ],
          }),
        )}
        size="XL"
        style={cell}
        data-widget-id="climate"
      />,
    );
    expect(html).toContain('grid-column:1 / span 3');
    expect(html).toContain('grid-row:1 / span 2');
    expect(html).toContain('data-widget-id="climate"');
  });

  it('Sauger (live) reicht ebenso durch — samt Edit-A11y (role/tabindex/aria-label)', () => {
    const html = renderToStaticMarkup(
      <VacuumTile
        registry={live(snapshot({ unassigned: [entity({ state: 'cleaning' })] }))}
        style={cell}
        data-widget-id="vacuum"
        role="button"
        tabIndex={0}
        aria-label="Sauger"
      />,
    );
    expect(html).toContain('grid-column:1 / span 3');
    expect(html).toContain('data-widget-id="vacuum"');
    expect(html).toContain('role="button"');
    expect(html).toContain('tabindex="0"');
    expect(html).toContain('aria-label="Sauger"');
  });

  it('auch der Rückfall-Zweig („nicht erreichbar") behält seine Zelle — sonst springt eine ausgefallene Kachel im Raster', () => {
    for (const html of [
      renderToStaticMarkup(<VacuumTile registry={null} style={cell} data-widget-id="vacuum" />),
      renderToStaticMarkup(<ClimateTile registry={null} style={cell} data-widget-id="climate" />),
    ]) {
      expect(html).toContain('grid-column:1 / span 3');
      expect(html).toContain('grid-row:1 / span 2');
    }
  });
});

// ─────────────────────────────────────────────────────────────────────────
//  Andi 21.08. — die ruhige Wahrheit, die nützlichen L/XL-Zeilen
// ─────────────────────────────────────────────────────────────────────────

/** 2026-08-21 14:30 LOKAL — alle Uhrzeit-Erwartungen unten sind damit zeitzonenfest. */
const SAUGER_NOW = new Date('2026-08-21T14:30:00').getTime();
const clockOf = (localIso: string) =>
  new Date(localIso).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });

describe('VacuumTile — Zeilen-Logik je Zustand (Andi 21.08.: „das ist Lärm")', () => {
  it('docked + FRISCH ⇒ ein Zustandssatz mit Akku, KEINE „zuletzt gesehen"-Zeile, KEINE Fußnote', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked' });
    const battery = member('sensor.rob_batterie', 'sensor', { state: '100' });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac, battery] }))} nowMs={SAUGER_NOW} />,
    );
    expect(html).toContain('Bereit in der Ladestation · Akku 100 %');
    expect(html).not.toContain('Zuletzt gesehen');
    expect(html).not.toContain('Nicht erreichbar');
    expect(html).not.toContain('Stand ');
  });

  it('docked + CACHE-CARRY ⇒ dieselbe Zeile, dazu leise „Stand HH:MM" — und weiterhin KEINE „zuletzt gesehen"-Zeile', () => {
    const seen = new Date('2026-08-21T14:20:00').getTime();
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked', fromCacheSinceMs: seen });
    const battery = member('sensor.rob_batterie', 'sensor', { state: '100', fromCacheSinceMs: seen });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac, battery] }))} nowMs={SAUGER_NOW} />,
    );
    expect(html).toContain('Bereit in der Ladestation · Akku 100 %');
    expect(html).toContain(`Stand ${clockOf('2026-08-21T14:20:00')}`);
    expect(html).toContain('idle__hometilewhisper');
    expect(html).not.toContain('Zuletzt gesehen');
    // Live-Kachel, kein Ausfall-Bild: der Energiesparmodus IST der Normalzustand.
    expect(html).toContain('data-status="live"');
  });

  it('die Fußnote ist size-UNABHÄNGIG — gecachte Werte sind auf S genauso gecacht wie auf XL', () => {
    const seen = new Date('2026-08-21T14:20:00').getTime();
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked', fromCacheSinceMs: seen });
    const reg = live(snapshot({ unassigned: [vac] }));
    for (const size of ['S', 'M', 'L', 'XL'] as const) {
      expect(renderToStaticMarkup(<VacuumTile registry={reg} size={size} nowMs={SAUGER_NOW} />)).toContain('Stand ');
    }
  });

  it('ECHTE Abwesenheit (kein Carry, nur lastKnown) ⇒ dort UND NUR dort steht „Zuletzt gesehen" — und KEIN Knopf', () => {
    const vac = entity({
      entityId: 'vacuum.rob',
      state: 'unavailable',
      lastKnown: { state: 'docked', attrs: { battery_level: '82' }, seenAt: '2026-08-20T14:30:00.000Z' },
    });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac] }))} nowMs={SAUGER_NOW} size="L" />,
    );
    expect(html).toContain('Zuletzt gesehen');
    expect(html).not.toContain('idle__hometileaction');
  });

  // Bis 23.08. hieß dieser Fall „keine Knöpfe". Andi hat die Regel selbst
  // zurückgenommen — wörtlich: „Den Start button hätte ich für den sauger auch
  // gerne im kleinen widget. hier soll es ein play button sein." Was BLEIBT,
  // ist die Aussage dahinter („das ist Lärm"): S trägt weiterhin EINEN
  // Zustandssatz ohne Akku, und der Knopf ist EINER — ohne Wort, ohne die
  // zweite Tat, mit `aria-label` statt Beschriftung.
  it('S trägt EINEN Zustandssatz (kein Akku) + genau den Play-Knopf (Andi 23.08.)', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked' });
    const battery = member('sensor.rob_batterie', 'sensor', { state: '100' });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac, battery] }))} size="S" nowMs={SAUGER_NOW} />,
    );
    expect(html).toContain('Bereit in der Ladestation');
    expect(html).not.toContain('Akku');
    // Der Play-Knopf ist da — und er ist der EINZIGE Knopf der Kachel.
    expect(html).toContain('idle__hometileaction--play');
    expect(html.match(/<button/g) ?? []).toHaveLength(1);
    // Keine Beschriftung im Text, aber ein Name für Screenreader.
    expect(html).toContain('aria-label="Start"');
    expect(html).not.toContain('>Start<');
    // „Zur Basis" wird NICHT in ein 44-px-Quadrat gepresst (Nachtrag der Order).
    expect(html).not.toContain('return_to_base');
    expect(html).not.toContain('Zur Basis');
  });

  it('S ohne startbaren Sauger zeigt gar keinen Knopf — `canStart` ist dieselbe Wahrheit wie für den großen', () => {
    // `cleaning` ⇒ canStart = false. Ein Play-Knopf, der nichts starten kann,
    // wäre eine Behauptung über den Sauger.
    const vac = entity({ entityId: 'vacuum.rob', state: 'cleaning' });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac] }))} size="S" nowMs={SAUGER_NOW} />,
    );
    expect(html).not.toContain('idle__hometileaction');
  });

  it('M trägt Raum UND Akku in derselben Zeile (Andis Bild ist EIN Satz, keine drei Absätze)', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'cleaning' });
    const room = member('sensor.rob_aktueller_raum', 'sensor', { state: 'Küche' });
    const battery = member('sensor.rob_batterie', 'sensor', { state: '63' });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac, room, battery] }))} size="M" nowMs={SAUGER_NOW} />,
    );
    expect(html).toContain('Saugt gerade · in Küche · Akku 63 %');
  });
});

describe('VacuumTile — was L/XL an ECHTEN Feldern dazugewinnt (Andi: „was haben wir noch?")', () => {
  const withRun = (endLocal: string, startLocal?: string) => {
    const members = [entity({ entityId: 'vacuum.rob', state: 'docked' })];
    members.push(
      member('sensor.rob_letztes_reinigungsende', 'sensor', { state: new Date(endLocal).toISOString() }),
    );
    if (startLocal) {
      members.push(
        member('sensor.rob_letzter_reinigungsbeginn', 'sensor', { state: new Date(startLocal).toISOString() }),
      );
    }
    return live(snapshot({ unassigned: members }));
  };

  it('„zuletzt fertig 14:20 · Dauer 1 h 40 min" — am selben Tag reicht die Uhrzeit', () => {
    const html = renderToStaticMarkup(
      <VacuumTile registry={withRun('2026-08-21T14:20:00', '2026-08-21T12:40:00')} size="L" nowMs={SAUGER_NOW} />,
    );
    expect(html).toContain(`zuletzt fertig ${clockOf('2026-08-21T14:20:00')}`);
    expect(html).toContain('Dauer 1 h 40 min');
  });

  it('an einem FRÜHEREN Tag trägt die Zeile das Alter statt einer Uhrzeit ohne Datum', () => {
    const html = renderToStaticMarkup(
      <VacuumTile registry={withRun('2026-08-19T10:00:00')} size="L" nowMs={SAUGER_NOW} />,
    );
    expect(html).toContain('zuletzt fertig vor 2 Tg.');
    expect(html).not.toContain('Dauer');
  });

  it('auf M gibt es die Zeile NICHT — sie ist ein L/XL-Feld (§3.2)', () => {
    const html = renderToStaticMarkup(
      <VacuumTile registry={withRun('2026-08-21T14:20:00')} size="M" nowMs={SAUGER_NOW} />,
    );
    expect(html).not.toContain('zuletzt fertig');
  });

  it('Mopp-Trocknung: nur die EINE interessante Hälfte hat eine Zeile', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked' });
    const drying = (state: string) => member('binary_sensor.rob_dock_mopp_trocknung', 'binary_sensor', { state });
    const on = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac, drying('on')] }))} size="L" nowMs={SAUGER_NOW} />,
    );
    const off = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac, drying('off')] }))} size="L" nowMs={SAUGER_NOW} />,
    );
    expect(on).toContain('Mopp trocknet');
    expect(off).not.toContain('Mopp trocknet');
  });

  it('Wartungs-Restzeiten stehen auf L offen da (kein zweiter Klick für Werte, die längst hinpassen)', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked' });
    const filter = member('sensor.rob_verbleibende_filterzeit', 'sensor', {
      state: '90',
      attrs: { unit_of_measurement: 'h' },
    });
    const sensors = member('sensor.rob_verbleibende_sensorzeit', 'sensor', { state: '18' });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac, filter, sensors] }))} size="L" nowMs={SAUGER_NOW} />,
    );
    expect(html).not.toContain('<details');
    // 90 h = 324000 s ≥ 48 h ⇒ Tage-Bucket, gerundet: 324000/86400 = 3,75 ⇒ 4
    // (seit 22.08. lesbar formatiert statt roher Wert+Einheit, s. ORDER-
    // sauger-wartung-lesbar-2026-08-22).
    expect(html).toContain('Filter: noch ~4 Tage');
    // Einheit NIE geraten: der Sensor ohne `unit_of_measurement` bleibt nackt
    // (kein Restzeit-Text ohne bekannte Einheit — ehrlicher Wert+Einheit-Rückfall).
    expect(html).toContain('Sensoren: 18<');
  });
});

describe('VacuumTile — Wartungs-Restzeit lesbar (ORDER-sauger-wartung-lesbar-2026-08-22)', () => {
  const withMaintenance = (over: Partial<Record<'mainBrush' | 'sideBrush' | 'filter' | 'sensor', string>>) => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked' });
    const members = [vac];
    if (over.mainBrush !== undefined) {
      members.push(
        member('sensor.rob_verbleibende_zeit_der_hauptburste', 'sensor', {
          state: over.mainBrush,
          attrs: { unit_of_measurement: 's' },
        }),
      );
    }
    if (over.sideBrush !== undefined) {
      members.push(
        member('sensor.rob_verbleibende_zeit_der_seitenburste', 'sensor', {
          state: over.sideBrush,
          attrs: { unit_of_measurement: 's' },
        }),
      );
    }
    if (over.filter !== undefined) {
      members.push(
        member('sensor.rob_verbleibende_filterzeit', 'sensor', { state: over.filter, attrs: { unit_of_measurement: 's' } }),
      );
    }
    if (over.sensor !== undefined) {
      members.push(
        member('sensor.rob_verbleibende_sensorzeit', 'sensor', { state: over.sensor, attrs: { unit_of_measurement: 's' } }),
      );
    }
    return live(snapshot({ unassigned: members }));
  };

  it('Andis reale Zahlen (22.08., wörtlich „Hauptbürste: 634362 s … nicht in Sekunden ^^") werden lesbar, KEINE nackte Sekundenzahl mehr', () => {
    const html = renderToStaticMarkup(
      <VacuumTile
        registry={withMaintenance({ mainBrush: '634362', sideBrush: '274362', filter: '94362', sensor: '-43123' })}
        size="XL"
        nowMs={SAUGER_NOW}
      />,
    );
    expect(html).toContain('Hauptbürste: noch ~7 Tage');
    expect(html).toContain('Seitenbürste: noch ~3 Tage');
    expect(html).toContain('Filter: noch ~26 h');
    expect(html).toContain('Sensoren: überfällig seit ~12 h');
    expect(html).not.toContain(' s<');
    expect(html).not.toContain('634362');
  });

  it('überfällige Restzeit trägt die dezente Warn-Klasse, NICHT die laute Amber-Kachel', () => {
    const html = renderToStaticMarkup(
      <VacuumTile registry={withMaintenance({ sensor: '-43123' })} size="L" nowMs={SAUGER_NOW} />,
    );
    expect(html).toContain('idle__cardlist__row--overdue');
    expect(html).not.toContain('tile--warn');
  });

  it('Restzeit ≥ 0 trägt NIE die Überfällig-Klasse', () => {
    const html = renderToStaticMarkup(
      <VacuumTile registry={withMaintenance({ mainBrush: '634362' })} size="L" nowMs={SAUGER_NOW} />,
    );
    expect(html).not.toContain('idle__cardlist__row--overdue');
  });

  it('Grenzfall exakt 0 s ⇒ „jetzt fällig", kein Absturz, keine erfundene Zahl', () => {
    const html = renderToStaticMarkup(<VacuumTile registry={withMaintenance({ filter: '0' })} size="L" nowMs={SAUGER_NOW} />);
    expect(html).toContain('Filter: jetzt fällig');
  });

  it('Grenzfall > 30 Tage Restzeit rendert ohne Deckel', () => {
    const html = renderToStaticMarkup(
      <VacuumTile registry={withMaintenance({ mainBrush: String(40 * 86400) })} size="L" nowMs={SAUGER_NOW} />,
    );
    expect(html).toContain('Hauptbürste: noch ~40 Tage');
  });

  it('Mopp + Wasserkasten beide dran ⇒ EINE Zeile statt zwei (Andi: „zu einer ruhigen Zeile zusammenfassen")', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked' });
    const mopp = member('binary_sensor.rob_mopp_angebracht', 'binary_sensor', { state: 'on' });
    const waterbox = member('binary_sensor.rob_wasserkasten_angebracht', 'binary_sensor', { state: 'on' });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac, mopp, waterbox] }))} size="L" nowMs={SAUGER_NOW} />,
    );
    const rows = html.match(/<li[^>]*>[^<]*<\/li>/g) ?? [];
    const merged = rows.find((r) => r.includes('Mopp dran') && r.includes('Wasserkasten dran'));
    expect(merged).toBeDefined();
    // Nur EINE <li> trägt beide Sätze — nicht zwei getrennte Zeilen wie vor 22.08.
    expect(rows.filter((r) => r.includes('Mopp dran') || r.includes('Wasserkasten dran'))).toHaveLength(1);
  });

  it('nur Mopp-Sensor vorhanden (Wasserkasten fehlt GANZ) ⇒ Mopp bleibt allein, keine erfundene Wasserkasten-Zeile', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked' });
    const mopp = member('binary_sensor.rob_mopp_angebracht', 'binary_sensor', { state: 'on' });
    const html = renderToStaticMarkup(
      <VacuumTile registry={live(snapshot({ unassigned: [vac, mopp] }))} size="L" nowMs={SAUGER_NOW} />,
    );
    expect(html).toContain('Mopp dran');
    expect(html).not.toContain('Wasserkasten');
  });

  it('unbekannte Einheit (z.B. %) ⇒ ehrlicher Wert+Einheit-Rückfall, KEINE erfundene Restzeit', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked' });
    const filter = member('sensor.rob_verbleibende_filterzeit', 'sensor', { state: '45', attrs: { unit_of_measurement: '%' } });
    const html = renderToStaticMarkup(<VacuumTile registry={live(snapshot({ unassigned: [vac, filter] }))} size="L" nowMs={SAUGER_NOW} />);
    expect(html).toContain('Filter: 45 %');
    expect(html).not.toContain('noch ~');
  });
});

describe('VacuumTile — welcher Knopf erscheint (die Semantik selbst ist in hometiles.test)', () => {
  const tileFor = (state: string, size: 'S' | 'M' | 'L' | 'XL' = 'L') =>
    renderToStaticMarkup(
      <VacuumTile
        registry={live(snapshot({ unassigned: [entity({ entityId: 'vacuum.rob', state })] }))}
        size={size}
        nowMs={SAUGER_NOW}
      />,
    );

  it('docked ⇒ „Start", kein „Zur Basis"', () => {
    const html = tileFor('docked');
    expect(html).toContain('>Start<');
    expect(html).not.toContain('>Zur Basis<');
  });

  it('cleaning ⇒ „Zur Basis", kein „Start"', () => {
    const html = tileFor('cleaning');
    expect(html).toContain('>Zur Basis<');
    expect(html).not.toContain('>Start<');
  });

  it('returning ⇒ gar keine Knopfleiste (er ist schon unterwegs nach Hause)', () => {
    expect(tileFor('returning')).not.toContain('idle__hometileactions');
  });

  it('paused ⇒ beide', () => {
    const html = tileFor('paused');
    expect(html).toContain('>Start<');
    expect(html).toContain('>Zur Basis<');
  });
});

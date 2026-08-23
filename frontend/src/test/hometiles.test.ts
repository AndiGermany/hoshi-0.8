import { describe, it, expect } from 'vitest';
import type { HomeRegistryEntity, HomeRegistrySnapshot } from '../api/homeRegistry';
import {
  climateRoomRows,
  findVacuum,
  findVacuumFamily,
  fmtTemp,
  formatMaintenanceDuration,
  formatRelativeAge,
  isEntityAvailable,
  maintenanceDurationStage,
  relativeAgeStage,
  vacuumBatteryLevel,
  vacuumFamilyAmber,
  vacuumFamilyAttached,
  vacuumFamilyBattery,
  vacuumFamilyErrorDetails,
  vacuumFamilyProgress,
  vacuumFamilyRoom,
  vacuumFamilyStatus,
  vacuumLastKnownBattery,
  vacuumLastKnownStatus,
  vacuumMaintenanceSeconds,
  vacuumMaintenanceValue,
  vacuumActionAvailability,
  vacuumFamilyCacheSince,
  vacuumLastClean,
  vacuumTileStatus,
  formatClock,
  isSameLocalDay,
  type VacuumStatusKind,
} from '../components/homeTiles';

/**
 * **hometiles.test** — reine Helfer der Zuhause-Kacheln (Draht-Vertrag der
 * Zuhause-Kacheln-Scheibe, Andi-Auftrag 2026-08-11). Deckt: das Sauger-
 * Zustands-Mapping (die sechs bekannten HA-`VacuumActivity`-Werte + der
 * ehrliche „nicht erreichbar"-Fallback), climate-nur-mit-Raum, und fehlende
 * `attrs` (Alt-Backend/HA führt sie für diese Entity gerade nicht).
 */

const entity = (over: Partial<HomeRegistryEntity> = {}): HomeRegistryEntity => ({
  entityId: 'vacuum.rob',
  domain: 'vacuum',
  name: 'Rob',
  labels: [],
  ...over,
});

const snapshot = (over: Partial<HomeRegistrySnapshot> = {}): HomeRegistrySnapshot => ({
  areas: [],
  unassigned: [],
  ...over,
});

describe('isEntityAvailable — state fehlt/unavailable/unknown ⇒ nicht erreichbar', () => {
  it('gesetzter, normaler State ⇒ erreichbar', () => {
    expect(isEntityAvailable(entity({ state: 'cleaning' }))).toBe(true);
    expect(isEntityAvailable(entity({ state: 'heat' }))).toBe(true);
  });

  it('state fehlt ⇒ nicht erreichbar', () => {
    expect(isEntityAvailable(entity())).toBe(false);
  });

  it("state 'unavailable'/'unknown' ⇒ nicht erreichbar", () => {
    expect(isEntityAvailable(entity({ state: 'unavailable' }))).toBe(false);
    expect(isEntityAvailable(entity({ state: 'unknown' }))).toBe(false);
  });
});

describe('findVacuum — erste vacuum-Entity, egal ob mit Raum', () => {
  it('kein Sauger irgendwo ⇒ null', () => {
    expect(findVacuum(snapshot())).toBeNull();
    expect(
      findVacuum(
        snapshot({
          areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [entity({ domain: 'light', entityId: 'light.x' })] }],
        }),
      ),
    ).toBeNull();
  });

  it('Sauger in einer Area ⇒ gefunden', () => {
    const vac = entity({ entityId: 'vacuum.rob' });
    const got = findVacuum(
      snapshot({ areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [vac] }] }),
    );
    expect(got).toEqual(vac);
  });

  it('Sauger nur in unassigned ⇒ trotzdem gefunden (Raum ist egal, ein Sauger wandert)', () => {
    const vac = entity({ entityId: 'vacuum.rob' });
    expect(findVacuum(snapshot({ unassigned: [vac] }))).toEqual(vac);
  });

  it('mehrere Sauger ⇒ der erste in Areas VOR unassigned', () => {
    const areaVac = entity({ entityId: 'vacuum.area' });
    const unassignedVac = entity({ entityId: 'vacuum.unassigned' });
    const got = findVacuum(
      snapshot({
        areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [areaVac] }],
        unassigned: [unassignedVac],
      }),
    );
    expect(got?.entityId).toBe('vacuum.area');
  });
});

describe('vacuumTileStatus — die sechs HA-Aktivitäten + der ehrliche Fallback', () => {
  it.each(['cleaning', 'docked', 'returning', 'paused', 'idle', 'error'] as const)(
    "'%s' ⇒ {kind:'known', status:'%s'}",
    (state) => {
      expect(vacuumTileStatus(entity({ state }))).toEqual({ kind: 'known', status: state });
    },
  );

  it('kein Sauger (null) ⇒ unreachable', () => {
    expect(vacuumTileStatus(null)).toEqual({ kind: 'unreachable' });
  });

  it("state fehlt/'unavailable'/'unknown' ⇒ unreachable, NIE ein geratener Zustand", () => {
    expect(vacuumTileStatus(entity())).toEqual({ kind: 'unreachable' });
    expect(vacuumTileStatus(entity({ state: 'unavailable' }))).toEqual({ kind: 'unreachable' });
    expect(vacuumTileStatus(entity({ state: 'unknown' }))).toEqual({ kind: 'unreachable' });
  });

  it('ein unbekannter/exotischer State-String ⇒ ebenfalls unreachable (kein Raten)', () => {
    expect(vacuumTileStatus(entity({ state: 'mowing' }))).toEqual({ kind: 'unreachable' });
  });
});

describe('vacuumBatteryLevel — attrs.battery_level, defensiv', () => {
  it('gültiger Wert ⇒ Zahl', () => {
    expect(vacuumBatteryLevel(entity({ attrs: { battery_level: '42' } }))).toBe(42);
  });

  it('kein Sauger ODER attrs fehlen ODER battery_level fehlt ⇒ null', () => {
    expect(vacuumBatteryLevel(null)).toBeNull();
    expect(vacuumBatteryLevel(entity())).toBeNull();
    expect(vacuumBatteryLevel(entity({ attrs: { current_temperature: '21' } }))).toBeNull();
  });

  it('nicht parsbarer Wert ⇒ null (kein NaN durchgereicht)', () => {
    expect(vacuumBatteryLevel(entity({ attrs: { battery_level: 'kaputt' } }))).toBeNull();
  });
});

// ─────────────────────────────────────────────────────────────────────────
//  Sauger-Metrik-Familie (Andi-Auftrag 2026-08-13: „lese dir alle Sauger-
//  Metriken aus … sind alle Sensoren sinnig angebunden?"). Roborock Qrevo Pro
//  ist der reale Prod-Stamm `roborock_qrevo_pro` — aber der Match läuft
//  GENERISCH über den entityId-Stamm der gefundenen `vacuum.*`-Entity, nicht
//  über einen fest verdrahteten Hersteller-Namen.
// ─────────────────────────────────────────────────────────────────────────

/** Ein Familienmitglied — eigener Helfer, weil Domain/entityId hier variieren (anders als der `entity()`-Default oben). */
const member = (entityId: string, domain: string, over: Partial<HomeRegistryEntity> = {}): HomeRegistryEntity => ({
  entityId,
  domain,
  name: entityId,
  labels: [],
  ...over,
});

describe('findVacuumFamily — generischer Stamm-Präfix-Match', () => {
  it('kein Sauger ⇒ null', () => {
    expect(findVacuumFamily(snapshot())).toBeNull();
  });

  it('Sauger ohne jedes Familienmitglied ⇒ family={vacuum}, alle anderen Felder undefined', () => {
    const vac = entity({ entityId: 'vacuum.roborock_qrevo_pro', state: 'docked' });
    const family = findVacuumFamily(snapshot({ unassigned: [vac] }));
    expect(family).toEqual({ vacuum: vac });
  });

  it('Familienmitglieder aus Areas UND unassigned werden über den Stamm gematcht, unabhängig vom Raum', () => {
    const vac = entity({ entityId: 'vacuum.roborock_qrevo_pro', state: 'docked' });
    const battery = member('sensor.roborock_qrevo_pro_batterie', 'sensor', { state: '73' });
    const cleaning = member('binary_sensor.roborock_qrevo_pro_reinigen', 'binary_sensor', { state: 'off' });
    const family = findVacuumFamily(
      snapshot({
        areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [battery] }],
        unassigned: [vac, cleaning],
      }),
    );
    expect(family?.battery).toEqual(battery);
    expect(family?.cleaning).toEqual(cleaning);
  });

  it('gleicher Suffix, aber falsche Domain ⇒ KEIN Match (Domain ist Teil des Vertrags)', () => {
    const vac = entity({ entityId: 'vacuum.roborock_qrevo_pro', state: 'docked' });
    // "reinigen" ist als binary_sensor definiert — ein sensor mit demselben Suffix zählt nicht.
    const wrongDomain = member('sensor.roborock_qrevo_pro_reinigen', 'sensor', { state: 'on' });
    const family = findVacuumFamily(snapshot({ unassigned: [vac, wrongDomain] }));
    expect(family?.cleaning).toBeUndefined();
  });

  it('gleicher Suffix, aber ANDERER Stamm (fremdes Gerät) ⇒ KEIN Match', () => {
    const vac = entity({ entityId: 'vacuum.roborock_qrevo_pro', state: 'docked' });
    const otherDevice = member('sensor.anderes_geraet_batterie', 'sensor', { state: '50' });
    const family = findVacuumFamily(snapshot({ unassigned: [vac, otherDevice] }));
    expect(family?.battery).toBeUndefined();
  });

  it('funktioniert generisch für JEDEN Stamm, nicht nur roborock_qrevo_pro', () => {
    const vac = entity({ entityId: 'vacuum.irgendein_zukuenftiger_sauger', state: 'idle' });
    const battery = member('sensor.irgendein_zukuenftiger_sauger_batterie', 'sensor', { state: '99' });
    const family = findVacuumFamily(snapshot({ unassigned: [vac, battery] }));
    expect(family?.battery).toEqual(battery);
  });
});

describe('vacuumFamilyStatus — Hybrid-Rettung, wenn die vacuum-Entity selbst schweigt', () => {
  it('keine Familie ⇒ unreachable', () => {
    expect(vacuumFamilyStatus(null)).toEqual({ kind: 'unreachable' });
  });

  it('vacuum liefert einen bekannten State ⇒ der gewinnt, Familie wird ignoriert', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'cleaning' });
    const cleaning = member('binary_sensor.rob_reinigen', 'binary_sensor', { state: 'off' });
    expect(vacuumFamilyStatus({ vacuum: vac, cleaning })).toEqual({ kind: 'known', status: 'cleaning' });
  });

  it('vacuum unavailable, aber reinigen=on ⇒ hybrid cleaning MIT ehrlicher Quelle', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'unavailable' });
    const cleaning = member('binary_sensor.rob_reinigen', 'binary_sensor', { state: 'on' });
    expect(vacuumFamilyStatus({ vacuum: vac, cleaning })).toEqual({ kind: 'hybrid', status: 'cleaning' });
  });

  it('vacuum unavailable, reinigen fehlt/off, aber ladestatus=on ⇒ hybrid charging', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'unavailable' });
    const charging = member('binary_sensor.rob_ladestatus', 'binary_sensor', { state: 'on' });
    expect(vacuumFamilyStatus({ vacuum: vac, charging })).toEqual({ kind: 'hybrid', status: 'charging' });
  });

  it('vacuum unavailable UND die ganze Familie unavailable/leer ⇒ unreachable, kein Raten', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'unavailable' });
    const cleaning = member('binary_sensor.rob_reinigen', 'binary_sensor', { state: 'unavailable' });
    expect(vacuumFamilyStatus({ vacuum: vac, cleaning })).toEqual({ kind: 'unreachable' });
    expect(vacuumFamilyStatus({ vacuum: vac })).toEqual({ kind: 'unreachable' });
  });

  it('vacuum meldet einen exotischen/unbekannten String ⇒ dieselbe Hybrid-Rettung greift', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'mowing' });
    const cleaning = member('binary_sensor.rob_reinigen', 'binary_sensor', { state: 'on' });
    expect(vacuumFamilyStatus({ vacuum: vac, cleaning })).toEqual({ kind: 'hybrid', status: 'cleaning' });
  });
});

describe('vacuumFamilyRoom/Progress/Battery — brauchbare Werte, defensiv', () => {
  it('aktueller_raum brauchbar ⇒ roher Raumname (NUTZERDATEN, unverändert)', () => {
    const currentRoom = member('sensor.rob_aktueller_raum', 'sensor', { state: 'Küche' });
    expect(vacuumFamilyRoom({ vacuum: entity(), currentRoom })).toBe('Küche');
  });

  it('aktueller_raum fehlt/unavailable ⇒ null', () => {
    expect(vacuumFamilyRoom({ vacuum: entity() })).toBeNull();
    const currentRoom = member('sensor.rob_aktueller_raum', 'sensor', { state: 'unavailable' });
    expect(vacuumFamilyRoom({ vacuum: entity(), currentRoom })).toBeNull();
  });

  it('reinigungsfortschritt NUR während reinigen=on', () => {
    const progress = member('sensor.rob_reinigungsfortschritt', 'sensor', { state: '42' });
    const cleaningOn = member('binary_sensor.rob_reinigen', 'binary_sensor', { state: 'on' });
    const cleaningOff = member('binary_sensor.rob_reinigen', 'binary_sensor', { state: 'off' });
    expect(vacuumFamilyProgress({ vacuum: entity(), progress, cleaning: cleaningOn })).toBe(42);
    expect(vacuumFamilyProgress({ vacuum: entity(), progress, cleaning: cleaningOff })).toBeNull();
    expect(vacuumFamilyProgress({ vacuum: entity(), progress })).toBeNull();
  });

  it('battery bevorzugt sensor.*_batterie vor attrs.battery_level', () => {
    const vac = entity({ attrs: { battery_level: '10' } });
    const battery = member('sensor.rob_batterie', 'sensor', { state: '73' });
    expect(vacuumFamilyBattery({ vacuum: vac, battery })).toBe(73);
  });

  it('battery-Sensor fehlt/unavailable ⇒ Fallback auf attrs.battery_level', () => {
    const vac = entity({ attrs: { battery_level: '10' } });
    expect(vacuumFamilyBattery({ vacuum: vac })).toBe(10);
    const unavailableBattery = member('sensor.rob_batterie', 'sensor', { state: 'unavailable' });
    expect(vacuumFamilyBattery({ vacuum: vac, battery: unavailableBattery })).toBe(10);
  });

  it('weder Sensor noch attrs ⇒ null', () => {
    expect(vacuumFamilyBattery({ vacuum: entity() })).toBeNull();
  });
});

describe('vacuumFamilyAmber — defensive Regeln (im Zweifel KEIN Amber)', () => {
  it('vacuum.state=error ⇒ Amber', () => {
    const status = vacuumTileStatus(entity({ state: 'error' }));
    expect(vacuumFamilyAmber({ vacuum: entity({ state: 'error' }) }, status)).toBe(true);
  });

  it('wasserknappheit=on ⇒ Amber', () => {
    const waterShortage = member('binary_sensor.rob_wasserknappheit', 'binary_sensor', { state: 'on' });
    const status = vacuumTileStatus(entity({ state: 'cleaning' }));
    expect(vacuumFamilyAmber({ vacuum: entity({ state: 'cleaning' }), waterShortage }, status)).toBe(true);
  });

  it('wasserkasten_angebracht=off ⇒ Amber; =on ⇒ kein Amber', () => {
    const status = vacuumTileStatus(entity({ state: 'cleaning' }));
    const off = member('binary_sensor.rob_wasserkasten_angebracht', 'binary_sensor', { state: 'off' });
    const on = member('binary_sensor.rob_wasserkasten_angebracht', 'binary_sensor', { state: 'on' });
    expect(vacuumFamilyAmber({ vacuum: entity({ state: 'cleaning' }), waterboxAttached: off }, status)).toBe(true);
    expect(vacuumFamilyAmber({ vacuum: entity({ state: 'cleaning' }), waterboxAttached: on }, status)).toBe(false);
  });

  it.each(['none', 'ok', '0', 'None', 'OK', ' none '])(
    "staubsauger_fehler=%j gilt als KEIN echter Fehler ⇒ kein Amber",
    (value) => {
      const vacuumError = member('sensor.rob_staubsauger_fehler', 'sensor', { state: value });
      const status = vacuumTileStatus(entity({ state: 'cleaning' }));
      expect(vacuumFamilyAmber({ vacuum: entity({ state: 'cleaning' }), vacuumError }, status)).toBe(false);
    },
  );

  it('staubsauger_fehler mit echtem Fehlwert ⇒ Amber', () => {
    const vacuumError = member('sensor.rob_staubsauger_fehler', 'sensor', { state: 'E1' });
    const status = vacuumTileStatus(entity({ state: 'cleaning' }));
    expect(vacuumFamilyAmber({ vacuum: entity({ state: 'cleaning' }), vacuumError }, status)).toBe(true);
  });

  it('dock_dock_fehler mit echtem Fehlwert ⇒ Amber', () => {
    const dockError = member('sensor.rob_dock_dock_fehler', 'sensor', { state: 'stuck' });
    const status = vacuumTileStatus(entity({ state: 'cleaning' }));
    expect(vacuumFamilyAmber({ vacuum: entity({ state: 'cleaning' }), dockError }, status)).toBe(true);
  });

  it('Fehler-Sensor selbst unavailable ⇒ zählt NICHT als echter Fehler (nicht brauchbar)', () => {
    const vacuumError = member('sensor.rob_staubsauger_fehler', 'sensor', { state: 'unavailable' });
    const status = vacuumTileStatus(entity({ state: 'cleaning' }));
    expect(vacuumFamilyAmber({ vacuum: entity({ state: 'cleaning' }), vacuumError }, status)).toBe(false);
  });

  it('keine Familie ⇒ kein Amber', () => {
    expect(vacuumFamilyAmber(null, { kind: 'unreachable' })).toBe(false);
  });

  it('gar kein Signal ⇒ kein Amber', () => {
    const status = vacuumTileStatus(entity({ state: 'cleaning' }));
    expect(vacuumFamilyAmber({ vacuum: entity({ state: 'cleaning' }) }, status)).toBe(false);
  });
});

describe('vacuumFamilyErrorDetails — „aber Wert zeigen": rohe Fehlwerte NUR wenn echt', () => {
  it('beide Sensoren mit echtem Fehler ⇒ beide im Ergebnis', () => {
    const vacuumError = member('sensor.rob_staubsauger_fehler', 'sensor', { state: 'E1' });
    const dockError = member('sensor.rob_dock_dock_fehler', 'sensor', { state: 'stuck' });
    expect(vacuumFamilyErrorDetails({ vacuum: entity(), vacuumError, dockError })).toEqual([
      { source: 'vacuum', value: 'E1' },
      { source: 'dock', value: 'stuck' },
    ]);
  });

  it('nur ein echter Fehler ⇒ nur der taucht auf', () => {
    const vacuumError = member('sensor.rob_staubsauger_fehler', 'sensor', { state: 'ok' });
    const dockError = member('sensor.rob_dock_dock_fehler', 'sensor', { state: 'E4' });
    expect(vacuumFamilyErrorDetails({ vacuum: entity(), vacuumError, dockError })).toEqual([
      { source: 'dock', value: 'E4' },
    ]);
  });

  it('kein echter Fehler/keine Familie ⇒ leer', () => {
    expect(vacuumFamilyErrorDetails({ vacuum: entity() })).toEqual([]);
    expect(vacuumFamilyErrorDetails(null)).toEqual([]);
  });
});

describe('vacuumMaintenanceValue/vacuumFamilyAttached — Wartungs-Fold, Einheit NIE geraten', () => {
  it('Wert MIT unit_of_measurement ⇒ Wert+Einheit', () => {
    const mainBrush = member('sensor.rob_verbleibende_zeit_der_hauptburste', 'sensor', {
      state: '120',
      attrs: { unit_of_measurement: 'h' },
    });
    expect(vacuumMaintenanceValue(mainBrush)).toEqual({ value: '120', unit: 'h' });
  });

  it('Wert OHNE unit_of_measurement ⇒ nur der Wert, KEINE geratene Einheit', () => {
    const filter = member('sensor.rob_verbleibende_filterzeit', 'sensor', { state: '30' });
    expect(vacuumMaintenanceValue(filter)).toEqual({ value: '30', unit: null });
  });

  it('Entity fehlt/unavailable ⇒ null (Zeile entfällt)', () => {
    expect(vacuumMaintenanceValue(undefined)).toBeNull();
    const unavailable = member('sensor.rob_verbleibende_sensorzeit', 'sensor', { state: 'unavailable' });
    expect(vacuumMaintenanceValue(unavailable)).toBeNull();
  });

  it('vacuumFamilyAttached: on ⇒ true, off ⇒ false, exotisch/fehlend ⇒ null', () => {
    expect(vacuumFamilyAttached(member('binary_sensor.rob_mopp_angebracht', 'binary_sensor', { state: 'on' }))).toBe(true);
    expect(vacuumFamilyAttached(member('binary_sensor.rob_mopp_angebracht', 'binary_sensor', { state: 'off' }))).toBe(
      false,
    );
    expect(vacuumFamilyAttached(undefined)).toBeNull();
    expect(
      vacuumFamilyAttached(member('binary_sensor.rob_mopp_angebracht', 'binary_sensor', { state: 'unavailable' })),
    ).toBeNull();
  });
});

describe('vacuumMaintenanceSeconds — Sekunden-Umrechnung, Einheit NIE geraten (ORDER-sauger-wartung-lesbar-2026-08-22)', () => {
  it('bekannte HA-UnitOfTime-Kürzel werden umgerechnet: s/min/h/d', () => {
    expect(vacuumMaintenanceSeconds({ value: '634362', unit: 's' })).toBe(634362);
    expect(vacuumMaintenanceSeconds({ value: '120', unit: 'min' })).toBe(7200);
    expect(vacuumMaintenanceSeconds({ value: '120', unit: 'h' })).toBe(432000);
    expect(vacuumMaintenanceSeconds({ value: '2', unit: 'd' })).toBe(172800);
  });

  it('negativer Wert bleibt negativ (überfällig ist kein Betrag)', () => {
    expect(vacuumMaintenanceSeconds({ value: '-43123', unit: 's' })).toBe(-43123);
  });

  it('fehlende ODER unbekannte Einheit ⇒ null, NIE geraten (ein künftiger Sauger könnte z.B. % liefern)', () => {
    expect(vacuumMaintenanceSeconds({ value: '90', unit: null })).toBeNull();
    expect(vacuumMaintenanceSeconds({ value: '45', unit: '%' })).toBeNull();
  });

  it('nicht-numerischer Wert ⇒ null', () => {
    expect(vacuumMaintenanceSeconds({ value: 'unbekannt', unit: 's' })).toBeNull();
  });
});

describe('maintenanceDurationStage/formatMaintenanceDuration — Formatierungs-Matrix (Auftrag Schritt 2+5)', () => {
  const strings = {
    remaining: {
      dueNow: 'jetzt fällig',
      minutes: (n: number) => `noch ~${n} min`,
      hours: (n: number) => `noch ~${n} h`,
      days: (n: number) => `noch ~${n} Tage`,
    },
    overdue: {
      minutes: (n: number) => `überfällig seit ~${n} min`,
      hours: (n: number) => `überfällig seit ~${n} h`,
      days: (n: number) => `überfällig seit ~${n} Tage`,
    },
  };

  it('Andis reale Zahlen (22.08.): Hauptbürste/Seitenbürste ⇒ Tage, Filter ⇒ Stunden, Sensoren ⇒ überfällig', () => {
    // 634362 s ≈ 7,34 Tage, 274362 s ≈ 3,18 Tage, 94362 s ≈ 26,21 h,
    // -43123 s ≈ 11,98 h überfällig — genau die Beispielzahlen aus der ORDER.
    expect(formatMaintenanceDuration(634362, strings)).toBe('noch ~7 Tage');
    expect(formatMaintenanceDuration(274362, strings)).toBe('noch ~3 Tage');
    expect(formatMaintenanceDuration(94362, strings)).toBe('noch ~26 h');
    expect(formatMaintenanceDuration(-43123, strings)).toBe('überfällig seit ~12 h');
  });

  it('Grenzfall 0 ⇒ „jetzt fällig", weder Rest noch Verzug', () => {
    expect(formatMaintenanceDuration(0, strings)).toBe('jetzt fällig');
    expect(maintenanceDurationStage(0)).toEqual({ kind: 'minutes', n: 0 });
  });

  it('Grenzfall negativ, unter einer Stunde ⇒ überfällig in Minuten (aufgerundet)', () => {
    expect(formatMaintenanceDuration(-30, strings)).toBe('überfällig seit ~1 min');
  });

  it('Grenzfall >30 Tage ⇒ Tage-Bucket rechnet unbegrenzt weiter (kein Deckel, keine Wochen-/Monats-Stufe)', () => {
    expect(formatMaintenanceDuration(40 * 86400, strings)).toBe('noch ~40 Tage');
    expect(formatMaintenanceDuration(-40 * 86400, strings)).toBe('überfällig seit ~40 Tage');
  });

  it('Schwelle bei genau 1 h ⇒ Stunden; kurz davor rollt die Rundung NICHT auf „60 min"', () => {
    expect(formatMaintenanceDuration(3600, strings)).toBe('noch ~1 h');
    expect(formatMaintenanceDuration(3599, strings)).toBe('noch ~1 h');
  });

  it('Schwelle bei genau 48 h (2 Tage) ⇒ Tage, nicht mehr Stunden (Auftrag wörtlich „≥2 Tage")', () => {
    expect(formatMaintenanceDuration(2 * 86400, strings)).toBe('noch ~2 Tage');
    expect(formatMaintenanceDuration(2 * 86400 - 1, strings)).toBe('noch ~48 h');
  });

  it('rundet statt zu floor()en — 11,98 h runden auf 12, nicht 11 (Sensoren-Beispiel oben)', () => {
    expect(maintenanceDurationStage(43123)).toEqual({ kind: 'hours', n: 12 });
  });
});

describe('climateRoomRows — NUR Areas mit climate-Entity', () => {
  it('Area ohne climate-Entity bleibt außen vor', () => {
    const rows = climateRoomRows(
      snapshot({
        areas: [{ areaId: 'flur', label: 'Flur', entities: [entity({ domain: 'light', entityId: 'light.flur' })] }],
      }),
    );
    expect(rows).toEqual([]);
  });

  it('Area MIT climate-Entity ⇒ eine Zeile mit geparsten Temperaturen + heating-Flag', () => {
    const climate = entity({
      entityId: 'climate.wz',
      domain: 'climate',
      state: 'heat',
      attrs: { current_temperature: '21.5', temperature: '22', hvac_action: 'heating' },
    });
    const rows = climateRoomRows(
      snapshot({ areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [climate] }] }),
    );
    expect(rows).toEqual([
      {
        areaId: 'wz',
        label: 'Wohnzimmer',
        currentTemperature: 21.5,
        targetTemperature: 22,
        heating: true,
        available: true,
        lastKnown: null, // additiv (Andi-Auftrag 2026-08-13, „Sauger-Sichtbarkeits-Lücke") — kein `entity.lastKnown` mitgegeben ⇒ null
      },
    ]);
  });

  it('hvac_action ungleich heating ⇒ heating:false', () => {
    const climate = entity({
      entityId: 'climate.wz',
      domain: 'climate',
      state: 'idle',
      attrs: { current_temperature: '21', temperature: '21', hvac_action: 'idle' },
    });
    const rows = climateRoomRows(
      snapshot({ areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [climate] }] }),
    );
    expect(rows[0].heating).toBe(false);
  });

  it('attrs fehlen komplett ⇒ current/target null, KEIN geratener Wert', () => {
    const climate = entity({ entityId: 'climate.wz', domain: 'climate', state: 'heat' });
    const rows = climateRoomRows(
      snapshot({ areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [climate] }] }),
    );
    expect(rows[0].currentTemperature).toBeNull();
    expect(rows[0].targetTemperature).toBeNull();
    expect(rows[0].heating).toBe(false);
  });

  it('state fehlt/unavailable ⇒ available:false in der Zeile', () => {
    const climate = entity({ entityId: 'climate.wz', domain: 'climate', state: 'unavailable' });
    const rows = climateRoomRows(
      snapshot({ areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [climate] }] }),
    );
    expect(rows[0].available).toBe(false);
  });

  it('mehrere Areas ⇒ eine Zeile je Area MIT climate, in Registry-Reihenfolge', () => {
    const climateA = entity({ entityId: 'climate.a', domain: 'climate', state: 'heat' });
    const climateB = entity({ entityId: 'climate.b', domain: 'climate', state: 'heat' });
    const rows = climateRoomRows(
      snapshot({
        areas: [
          { areaId: 'a', label: 'Raum A', entities: [climateA] },
          { areaId: 'b', label: 'Raum B', entities: [entity({ domain: 'light', entityId: 'light.b' })] },
          { areaId: 'c', label: 'Raum C', entities: [climateB] },
        ],
      }),
    );
    expect(rows.map((r) => r.areaId)).toEqual(['a', 'c']);
  });
});

describe('climateRoomRows — lastKnown-Fallback (additiv, Andi-Auftrag 2026-08-13, „Sauger-Sichtbarkeits-Lücke")', () => {
  it('entity.lastKnown vorhanden ⇒ row.lastKnown traegt die geparsten alten Temperaturen + seenAtMs', () => {
    const climate = entity({
      entityId: 'climate.wz',
      domain: 'climate',
      state: 'unavailable',
      lastKnown: {
        state: 'heat',
        attrs: { current_temperature: '20', temperature: '21', hvac_action: 'heating' },
        seenAt: '2026-08-13T20:03:00.000Z',
      },
    });
    const rows = climateRoomRows(snapshot({ areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [climate] }] }));
    expect(rows[0].available).toBe(false);
    expect(rows[0].lastKnown).toEqual({
      currentTemperature: 20,
      targetTemperature: 21,
      heating: true,
      seenAtMs: Date.parse('2026-08-13T20:03:00.000Z'),
    });
  });

  it('kein entity.lastKnown ⇒ row.lastKnown ist null', () => {
    const climate = entity({ entityId: 'climate.wz', domain: 'climate', state: 'heat' });
    const rows = climateRoomRows(snapshot({ areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [climate] }] }));
    expect(rows[0].lastKnown).toBeNull();
  });

  it('lastKnown.attrs ohne Temperaturen ⇒ current/target null im Fallback (kein Raten)', () => {
    const climate = entity({
      entityId: 'climate.wz',
      domain: 'climate',
      state: 'unavailable',
      lastKnown: { state: 'heat', attrs: {}, seenAt: '2026-08-13T20:03:00.000Z' },
    });
    const rows = climateRoomRows(snapshot({ areas: [{ areaId: 'wz', label: 'Wohnzimmer', entities: [climate] }] }));
    expect(rows[0].lastKnown?.currentTemperature).toBeNull();
    expect(rows[0].lastKnown?.targetTemperature).toBeNull();
    expect(rows[0].lastKnown?.heating).toBe(false);
  });
});

describe('relativeAgeStage — grobe Alters-Stufen (Andi-Auftrag 2026-08-13)', () => {
  const t0 = Date.parse('2026-08-13T20:00:00Z');

  it('unter 1 Minute ⇒ justNow', () => {
    expect(relativeAgeStage(t0, t0)).toEqual({ kind: 'justNow' });
    expect(relativeAgeStage(t0, t0 + 59_000)).toEqual({ kind: 'justNow' });
  });

  it('1 Minute bis unter 1 Stunde ⇒ minutes, abgerundet', () => {
    expect(relativeAgeStage(t0, t0 + 60_000)).toEqual({ kind: 'minutes', n: 1 });
    expect(relativeAgeStage(t0, t0 + 5 * 60_000 + 59_000)).toEqual({ kind: 'minutes', n: 5 });
  });

  it('1 Stunde bis unter 1 Tag ⇒ hours, abgerundet', () => {
    expect(relativeAgeStage(t0, t0 + 60 * 60_000)).toEqual({ kind: 'hours', n: 1 });
    expect(relativeAgeStage(t0, t0 + 23 * 60 * 60_000)).toEqual({ kind: 'hours', n: 23 });
  });

  it('ab 1 Tag ⇒ days, abgerundet', () => {
    expect(relativeAgeStage(t0, t0 + 24 * 60 * 60_000)).toEqual({ kind: 'days', n: 1 });
    expect(relativeAgeStage(t0, t0 + 3 * 24 * 60 * 60_000 + 60_000)).toEqual({ kind: 'days', n: 3 });
  });

  it('seenAtMs in der Zukunft (Client-Uhr-Drift) ⇒ ehrlich justNow, NIE negativ', () => {
    expect(relativeAgeStage(t0 + 10_000, t0)).toEqual({ kind: 'justNow' });
  });

  it('seenAtMs NaN (kaputtes Datum) ⇒ defensiv justNow', () => {
    expect(relativeAgeStage(NaN, t0)).toEqual({ kind: 'justNow' });
  });
});

describe('formatRelativeAge — verdrahtet die i18n-Textbausteine der jeweiligen Stufe', () => {
  const t0 = Date.parse('2026-08-13T20:00:00Z');
  const strings = {
    justNow: 'gerade eben',
    minutesAgo: (n: number) => `vor ${n} Min.`,
    hoursAgo: (n: number) => `vor ${n} Std.`,
    daysAgo: (n: number) => `vor ${n} Tg.`,
  };

  it('ruft je Stufe genau den passenden Textbaustein', () => {
    expect(formatRelativeAge(t0, t0, strings)).toBe('gerade eben');
    expect(formatRelativeAge(t0, t0 + 5 * 60_000, strings)).toBe('vor 5 Min.');
    expect(formatRelativeAge(t0, t0 + 3 * 60 * 60_000, strings)).toBe('vor 3 Std.');
    expect(formatRelativeAge(t0, t0 + 2 * 24 * 60 * 60_000, strings)).toBe('vor 2 Tg.');
  });
});

describe('vacuumLastKnownStatus/vacuumLastKnownBattery — Fallback NUR aus der vacuum.*-Entity (Andi-Auftrag 2026-08-13)', () => {
  it('vacuum.lastKnown mit bekanntem state ⇒ status + seenAtMs', () => {
    const vac = entity({
      entityId: 'vacuum.rob',
      state: 'unavailable',
      lastKnown: { state: 'docked', attrs: { battery_level: '82' }, seenAt: '2026-08-13T20:03:00.000Z' },
    });
    const family = findVacuumFamily(snapshot({ unassigned: [vac] }));
    const got = vacuumLastKnownStatus(family);
    expect(got).toEqual({ status: 'docked', seenAtMs: Date.parse('2026-08-13T20:03:00.000Z') });
  });

  it('vacuum.lastKnown fehlt ⇒ null', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'unavailable' });
    const family = findVacuumFamily(snapshot({ unassigned: [vac] }));
    expect(vacuumLastKnownStatus(family)).toBeNull();
  });

  it('kein Sauger ueberhaupt ⇒ null', () => {
    expect(vacuumLastKnownStatus(null)).toBeNull();
  });

  it('lastKnown.state außerhalb der sechs bekannten Aktivitäten ⇒ null (kein geratener Zustand)', () => {
    const vac = entity({
      entityId: 'vacuum.rob',
      state: 'unavailable',
      lastKnown: { state: 'exotisch', attrs: {}, seenAt: '2026-08-13T20:03:00.000Z' },
    });
    const family = findVacuumFamily(snapshot({ unassigned: [vac] }));
    expect(vacuumLastKnownStatus(family)).toBeNull();
  });

  it('lastKnown.seenAt kaputt (kein parsbares Datum) ⇒ null', () => {
    const vac = entity({
      entityId: 'vacuum.rob',
      state: 'unavailable',
      lastKnown: { state: 'docked', attrs: {}, seenAt: 'kein-datum' },
    });
    const family = findVacuumFamily(snapshot({ unassigned: [vac] }));
    expect(vacuumLastKnownStatus(family)).toBeNull();
  });

  it('vacuumLastKnownBattery liest NUR battery_level aus vacuum.lastKnown.attrs, nicht aus der Metrik-Familie', () => {
    const vac = entity({
      entityId: 'vacuum.rob',
      state: 'unavailable',
      lastKnown: { state: 'docked', attrs: { battery_level: '82' }, seenAt: '2026-08-13T20:03:00.000Z' },
    });
    const family = findVacuumFamily(snapshot({ unassigned: [vac] }));
    expect(vacuumLastKnownBattery(family)).toBe(82);
  });

  it('vacuumLastKnownBattery ohne battery_level im lastKnown ⇒ null', () => {
    const vac = entity({
      entityId: 'vacuum.rob',
      state: 'unavailable',
      lastKnown: { state: 'docked', attrs: {}, seenAt: '2026-08-13T20:03:00.000Z' },
    });
    const family = findVacuumFamily(snapshot({ unassigned: [vac] }));
    expect(vacuumLastKnownBattery(family)).toBeNull();
  });
});

describe('fmtTemp — Dezimaltrenner je Locale, glatte .0 weg', () => {
  it('de-DE: Komma', () => {
    expect(fmtTemp(21.5, 'de-DE')).toBe('21,5°');
    expect(fmtTemp(22, 'de-DE')).toBe('22°');
  });

  it('en-US: Punkt', () => {
    expect(fmtTemp(21.5, 'en-US')).toBe('21.5°');
  });

  it('rundet auf eine Nachkommastelle', () => {
    expect(fmtTemp(21.46, 'de-DE')).toBe('21,5°');
  });

  it('unbekanntes Locale ⇒ Punkt-Fallback', () => {
    expect(fmtTemp(21.5, 'xx-XX')).toBe('21.5°');
  });
});

// ─────────────────────────────────────────────────────────────────────────
//  Andi 21.08. — Cache-Carry, „zuletzt fertig", Knopf-Semantik
// ─────────────────────────────────────────────────────────────────────────

/** 2026-08-21 14:30 lokal — alle Zeit-Erwartungen unten rechnen gegen diesen Punkt. */
const NOW = new Date('2026-08-21T14:30:00').getTime();
const iso = (local: string) => new Date(local).toISOString();

describe('vacuumFamilyCacheSince — die leise „Stand HH:MM"-Quelle', () => {
  it('kein Carry ⇒ null (alles live, die Kachel schweigt darüber)', () => {
    const family = findVacuumFamily(snapshot({ unassigned: [entity({ state: 'docked' })] }));
    expect(vacuumFamilyCacheSince(family)).toBeNull();
  });

  it('Carry an der vacuum-Entity ⇒ genau dieser Zeitpunkt', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked', fromCacheSinceMs: 1000 });
    const family = findVacuumFamily(snapshot({ unassigned: [vac] }));
    expect(vacuumFamilyCacheSince(family)).toBe(1000);
  });

  it('MISCHFALL: der ÄLTESTE Stempel gewinnt — die Zeile ist eine Untergrenze der Frische, nie eine Beschönigung', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked', fromCacheSinceMs: 9000 });
    const battery = member('sensor.rob_batterie', 'sensor', { state: '100', fromCacheSinceMs: 3000 });
    const room = member('sensor.rob_aktueller_raum', 'sensor', { state: 'Küche' }); // live, kein Stempel
    const family = findVacuumFamily(snapshot({ unassigned: [vac, battery, room] }));
    expect(vacuumFamilyCacheSince(family)).toBe(3000);
  });

  it('kaputte Stempel (0/negativ) zählen nicht — lieber keine Fußnote als „Stand 01:00" von 1970', () => {
    const vac = entity({ entityId: 'vacuum.rob', state: 'docked', fromCacheSinceMs: 0 });
    const battery = member('sensor.rob_batterie', 'sensor', { state: '100', fromCacheSinceMs: -5 });
    const family = findVacuumFamily(snapshot({ unassigned: [vac, battery] }));
    expect(vacuumFamilyCacheSince(family)).toBeNull();
  });

  it('keine Familie ⇒ null', () => {
    expect(vacuumFamilyCacheSince(null)).toBeNull();
  });
});

describe('vacuumLastClean — „zuletzt fertig 14:20" plus Dauer', () => {
  const withClean = (end?: string, start?: string) => {
    const members = [entity({ entityId: 'vacuum.rob', state: 'docked' })];
    if (end !== undefined) members.push(member('sensor.rob_letztes_reinigungsende', 'sensor', { state: end }));
    if (start !== undefined) members.push(member('sensor.rob_letzter_reinigungsbeginn', 'sensor', { state: start }));
    return findVacuumFamily(snapshot({ unassigned: members }));
  };

  it('kein lastCleanEnd ⇒ null (keine Zeile, kein geratenes Datum)', () => {
    expect(vacuumLastClean(withClean(), NOW)).toBeNull();
  });

  it('unavailable ⇒ null', () => {
    expect(vacuumLastClean(withClean('unavailable'), NOW)).toBeNull();
  });

  it('kein parsbares Datum ⇒ null', () => {
    expect(vacuumLastClean(withClean('irgendwas'), NOW)).toBeNull();
  });

  it('Ende in der ZUKUNFT ⇒ null — ein Lauf, der noch nicht fertig ist, ist nicht „zuletzt fertig"', () => {
    expect(vacuumLastClean(withClean(iso('2026-08-21T18:00:00')), NOW)).toBeNull();
  });

  it('nur Ende ⇒ Zeitpunkt ohne Dauer', () => {
    const out = vacuumLastClean(withClean(iso('2026-08-21T14:20:00')), NOW);
    expect(out?.endMs).toBe(new Date('2026-08-21T14:20:00').getTime());
    expect(out?.startMs).toBeNull();
    expect(out?.durationMs).toBeNull();
  });

  it('Ende + plausibler Beginn ⇒ Dauer', () => {
    const out = vacuumLastClean(withClean(iso('2026-08-21T14:20:00'), iso('2026-08-21T12:40:00')), NOW);
    expect(out?.durationMs).toBe(100 * 60_000);
  });

  it('Beginn NACH dem Ende ⇒ kein Dauer-Unsinn, das Ende bleibt', () => {
    const out = vacuumLastClean(withClean(iso('2026-08-21T14:20:00'), iso('2026-08-21T16:00:00')), NOW);
    expect(out?.durationMs).toBeNull();
    expect(out?.endMs).toBe(new Date('2026-08-21T14:20:00').getTime());
  });

  it('Beginn ≥ 24 h vor dem Ende ⇒ keine Dauer (die zwei Stempel gehören nicht zum selben Lauf)', () => {
    const out = vacuumLastClean(withClean(iso('2026-08-21T14:20:00'), iso('2026-08-19T10:00:00')), NOW);
    expect(out?.durationMs).toBeNull();
  });
});

describe('vacuumActionAvailability — welcher Knopf wann (BE-Vertrag §3)', () => {
  const known = (status: VacuumStatusKind) => vacuumActionAvailability({ kind: 'known', status });

  it('docked ⇒ nur Start (der schlafende Sauger im Cache ist bedienbar — genau darum ging es)', () => {
    expect(known('docked')).toEqual({ canStart: true, canReturn: false });
  });

  it('idle ⇒ nur Start', () => {
    expect(known('idle')).toEqual({ canStart: true, canReturn: false });
  });

  it('cleaning ⇒ nur Zur Basis', () => {
    expect(known('cleaning')).toEqual({ canStart: false, canReturn: true });
  });

  it('paused ⇒ BEIDE (weiterfahren oder heimfahren sind von dort aus beide sinnvoll)', () => {
    expect(known('paused')).toEqual({ canStart: true, canReturn: true });
  });

  it('returning ⇒ KEINER — er ist schon unterwegs nach Hause', () => {
    expect(known('returning')).toEqual({ canStart: false, canReturn: false });
  });

  it('error ⇒ KEINER', () => {
    expect(known('error')).toEqual({ canStart: false, canReturn: false });
  });

  it('hybrid charging ⇒ Start, hybrid cleaning ⇒ Zur Basis', () => {
    expect(vacuumActionAvailability({ kind: 'hybrid', status: 'charging' })).toEqual({ canStart: true, canReturn: false });
    expect(vacuumActionAvailability({ kind: 'hybrid', status: 'cleaning' })).toEqual({ canStart: false, canReturn: true });
  });

  it('unreachable ⇒ KEINER — dort weiß niemand etwas über den Sauger', () => {
    expect(vacuumActionAvailability({ kind: 'unreachable' })).toEqual({ canStart: false, canReturn: false });
  });
});

describe('formatClock / isSameLocalDay', () => {
  it('formatiert zweistellig (de)', () => {
    expect(formatClock(new Date('2026-08-21T09:05:00').getTime(), 'de-DE')).toBe('09:05');
  });

  it('derselbe Kalendertag vs. der Tag davor', () => {
    const abends = new Date('2026-08-21T23:50:00').getTime();
    const nachts = new Date('2026-08-22T00:10:00').getTime();
    expect(isSameLocalDay(abends, NOW)).toBe(true);
    expect(isSameLocalDay(nachts, NOW)).toBe(false);
  });
});

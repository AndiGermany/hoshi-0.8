import type { HomeRegistryEntity, HomeRegistrySnapshot, HomeRegistryState } from '../api/homeRegistry';

/**
 * **homeTiles** — reine, netzfreie Helfer für die Zuhause-Kacheln (Sauger +
 * Klima, Andi-Auftrag 2026-08-11: „Zuhause-Kacheln, die man sich verdient").
 * Muster {@link ./roomsSort.ts}/{@link ./roomsRelevance.ts}: keine Komponente,
 * kein Netz — nur Ableitungen aus einem bereits geladenen
 * {@link HomeRegistrySnapshot}. Die Kachel-Komponenten (`HomeTileCards.tsx`)
 * verdrahten diese Funktionen nur.
 *
 * **Draht-Vertrag** (fixiert, ein BE-Pod baut ihn parallel): jede Registry-
 * Entity trägt additiv `state?: string` (roher HA-Zustand) und
 * `attrs?: Record<string,string>` mit höchstens `battery_level`,
 * `current_temperature`, `temperature`, `hvac_action`, `unit_of_measurement`
 * — s. KDoc in `api/homeRegistry.ts`.
 *
 * **Ehrlichkeit statt Raten**: ein fehlender/„unavailable"/„unknown"-State
 * heißt IMMER „nicht erreichbar" ({@link isEntityAvailable}), nie ein
 * geratener Zustand. Der Sauger kennt genau sechs HA-Aktivitäten
 * (`cleaning`/`docked`/`returning`/`paused`/`idle`/`error` — deckungsgleich
 * mit Home Assistants `VacuumActivity`-Enum); jeder andere/unbekannte String
 * fällt ebenfalls auf „nicht erreichbar", statt eine erfundene Bedeutung zu
 * behaupten.
 *
 * **Sauger-METRIK-FAMILIE (Andi-Auftrag 2026-08-13, „lese dir alle Sauger-
 * Metriken aus … sind alle Sensoren sinnig angebunden?"):** ein echter Sauger
 * (Roborock UND jeder künftige) legt seine Werte NICHT als Attribute der
 * `vacuum.*`-Entity ab, sondern als eigene Geschwister-Entities mit
 * demselben entityId-Stamm-Präfix (`sensor.<stem>_batterie`,
 * `binary_sensor.<stem>_reinigen`, …). {@link findVacuumFamily} matcht diese
 * Familie GENERISCH über den Stamm — funktioniert für jeden Sauger, nicht
 * nur den heutigen Roborock-Präfix `roborock_qrevo_pro`. Dieselbe
 * Ehrlichkeits-Regel gilt weiter: eine Zeile in der Kachel erscheint NUR bei
 * einem brauchbaren (verfügbaren, nicht-leeren) Wert — fehlt/„unavailable"/
 * „unknown" ⇒ die Zeile bleibt einfach weg statt einen Wert zu erfinden.
 * Einheiten (`attrs.unit_of_measurement`) werden NIE geraten: nur angezeigt,
 * wenn HA sie mitliefert.
 */

const UNAVAILABLE_STATES: ReadonlySet<string> = new Set(['unavailable', 'unknown']);

/**
 * Ist diese Entity gerade erreichbar? `state` fehlt/`unavailable`/`unknown`
 * ⇒ nein — der einzige Ehrlichkeits-Riegel, den BEIDE Kacheln teilen (Sauger-
 * Entity UND jede einzelne Klima-Zeile).
 */
export function isEntityAvailable(entity: Pick<HomeRegistryEntity, 'state'>): boolean {
  return entity.state !== undefined && !UNAVAILABLE_STATES.has(entity.state);
}

/**
 * Erste `vacuum`-Entity im ganzen Snapshot — zuerst die zugeordneten Areas
 * (in Registry-Reihenfolge), dann `unassigned`. Andis Vorgabe: „egal ob mit
 * Raum — ein Sauger wandert" (er fährt durch mehrere Räume, ein fester Raum
 * wäre ohnehin nur eine Momentaufnahme). `null` = kein Sauger in der Registry
 * — dann existiert der Einstellungs-Schalter erst gar nicht (s. SettingsPanel).
 */
export function findVacuum(snapshot: HomeRegistrySnapshot): HomeRegistryEntity | null {
  for (const area of snapshot.areas) {
    const hit = area.entities.find((e) => e.domain === 'vacuum');
    if (hit) return hit;
  }
  return snapshot.unassigned.find((e) => e.domain === 'vacuum') ?? null;
}

/** Die sechs HA-`VacuumActivity`-Zustände, denen die Sauger-Kachel einen warmen Satz gibt. */
export type VacuumStatusKind = 'cleaning' | 'docked' | 'returning' | 'paused' | 'idle' | 'error';

const KNOWN_VACUUM_STATES: ReadonlySet<string> = new Set<VacuumStatusKind>([
  'cleaning',
  'docked',
  'returning',
  'paused',
  'idle',
  'error',
]);

/**
 * Zustand der Sauger-Kachel: ein bekannter HA-State, eine ehrliche Hybrid-
 * Rettung aus der Metrik-Familie ({@link vacuumFamilyStatus}, `cleaning`/
 * `charging` NUR aus `reinigen`/`ladestatus`, NIE als „echter" vacuum-State
 * ausgegeben), oder ehrlich „nicht erreichbar".
 */
export type VacuumTileStatus =
  | { kind: 'known'; status: VacuumStatusKind }
  | { kind: 'hybrid'; status: 'cleaning' | 'charging' }
  | { kind: 'unreachable' };

/**
 * Leitet den Kachel-Zustand aus der Sauger-Entity ab: keine Entity ODER nicht
 * erreichbar ({@link isEntityAvailable}) ODER ein State außerhalb der sechs
 * bekannten HA-Aktivitäten ⇒ `{kind:'unreachable'}` — NIE ein geratener
 * Zustand für einen unbekannten String.
 */
export function vacuumTileStatus(entity: HomeRegistryEntity | null): VacuumTileStatus {
  if (!entity || !isEntityAvailable(entity)) return { kind: 'unreachable' };
  const state = entity.state as string;
  return KNOWN_VACUUM_STATES.has(state)
    ? { kind: 'known', status: state as VacuumStatusKind }
    : { kind: 'unreachable' };
}

/** `attrs.battery_level` als Zahl, `null` wenn HA es nicht führt oder der Wert nicht parsbar ist. */
export function vacuumBatteryLevel(entity: HomeRegistryEntity | null): number | null {
  const raw = entity?.attrs?.battery_level;
  if (raw === undefined) return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

// ─────────────────────────────────────────────────────────────────────────
//  Sauger-Metrik-Familie (Andi-Auftrag 2026-08-13)
// ─────────────────────────────────────────────────────────────────────────

/** Roher HA-`state`, NUR wenn die Entity überhaupt existiert UND {@link isEntityAvailable} ist — sonst `null`, nie geraten. */
function usableState(entity: HomeRegistryEntity | undefined): string | null {
  if (!entity || !isEntityAvailable(entity)) return null;
  const s = entity.state as string;
  return s.length > 0 ? s : null;
}

/** Schlüssel der Familienmitglieder, die die Sauger-Kachel kennt (Suffix-Map s. {@link VACUUM_FAMILY_SUFFIXES}). */
export type VacuumFamilyKey =
  | 'battery'
  | 'status'
  | 'currentRoom'
  | 'progress'
  | 'vacuumError'
  | 'dockError'
  | 'cleaning'
  | 'charging'
  | 'waterShortage'
  | 'waterboxAttached'
  | 'moppAttached'
  | 'moppDrying'
  | 'mainBrushTimeLeft'
  | 'sideBrushTimeLeft'
  | 'filterTimeLeft'
  | 'sensorTimeLeft'
  | 'lastCleanStart'
  | 'lastCleanEnd';

/**
 * `<domain>.<stem>_<suffix>` je Familienmitglied — vom Prod-Rand gelesen
 * (Andi 2026-08-11/13, Roborock Qrevo Pro), NICHT geraten. Der Stamm selbst
 * ({@link findVacuumFamily}) kommt aus der `vacuum.*`-Entity, NIE fest
 * verdrahtet — dieselbe Suffix-Map funktioniert für jeden künftigen Sauger.
 */
const VACUUM_FAMILY_SUFFIXES: Record<VacuumFamilyKey, { domain: string; suffix: string }> = {
  battery: { domain: 'sensor', suffix: 'batterie' },
  status: { domain: 'sensor', suffix: 'status' },
  currentRoom: { domain: 'sensor', suffix: 'aktueller_raum' },
  progress: { domain: 'sensor', suffix: 'reinigungsfortschritt' },
  vacuumError: { domain: 'sensor', suffix: 'staubsauger_fehler' },
  dockError: { domain: 'sensor', suffix: 'dock_dock_fehler' },
  mainBrushTimeLeft: { domain: 'sensor', suffix: 'verbleibende_zeit_der_hauptburste' },
  sideBrushTimeLeft: { domain: 'sensor', suffix: 'verbleibende_zeit_der_seitenburste' },
  filterTimeLeft: { domain: 'sensor', suffix: 'verbleibende_filterzeit' },
  sensorTimeLeft: { domain: 'sensor', suffix: 'verbleibende_sensorzeit' },
  lastCleanStart: { domain: 'sensor', suffix: 'letzter_reinigungsbeginn' },
  lastCleanEnd: { domain: 'sensor', suffix: 'letztes_reinigungsende' },
  cleaning: { domain: 'binary_sensor', suffix: 'reinigen' },
  charging: { domain: 'binary_sensor', suffix: 'ladestatus' },
  waterShortage: { domain: 'binary_sensor', suffix: 'wasserknappheit' },
  waterboxAttached: { domain: 'binary_sensor', suffix: 'wasserkasten_angebracht' },
  moppAttached: { domain: 'binary_sensor', suffix: 'mopp_angebracht' },
  moppDrying: { domain: 'binary_sensor', suffix: 'dock_mopp_trocknung' },
};

/** Die Sauger-Entity PLUS alle gefundenen Familienmitglieder (jedes Feld additiv/optional — HA führt selten alle). */
export type VacuumFamily = { vacuum: HomeRegistryEntity } & Partial<Record<VacuumFamilyKey, HomeRegistryEntity>>;

function allSnapshotEntities(snapshot: HomeRegistrySnapshot): HomeRegistryEntity[] {
  return [...snapshot.areas.flatMap((a) => a.entities), ...snapshot.unassigned];
}

/**
 * Findet die Sauger-Entity ({@link findVacuum}) UND ihre ganze Metrik-Familie:
 * zu `vacuum.<stem>` gehören alle Entities `<domain>.<stem>_<suffix>` aus
 * {@link VACUUM_FAMILY_SUFFIXES}, GENERISCH über den entityId-Stamm gematcht
 * (Andi: „egal ob Roborock oder ein künftiger Sauger — der Stamm entscheidet,
 * nicht ein fest verdrahteter Hersteller-Präfix"). Kein Sauger ⇒ `null`.
 * Fehlende Familienmitglieder bleiben einfach `undefined` — kein Fehler,
 * HA führt nicht jeden Sensor für jedes Gerät.
 */
export function findVacuumFamily(snapshot: HomeRegistrySnapshot): VacuumFamily | null {
  const vacuum = findVacuum(snapshot);
  if (!vacuum) return null;
  const stem = vacuum.entityId.slice(vacuum.domain.length + 1);
  const byId = new Map(allSnapshotEntities(snapshot).map((e) => [e.entityId, e]));
  const family: VacuumFamily = { vacuum };
  for (const key of Object.keys(VACUUM_FAMILY_SUFFIXES) as VacuumFamilyKey[]) {
    const { domain, suffix } = VACUUM_FAMILY_SUFFIXES[key];
    const hit = byId.get(`${domain}.${stem}_${suffix}`);
    if (hit) family[key] = hit;
  }
  return family;
}

/**
 * Zustand der Sauger-Kachel, jetzt MIT ehrlicher Hybrid-Rettung: schweigt die
 * `vacuum.*`-Entity selbst (unavailable/unknown/exotischer String), aber die
 * Familie liefert (`reinigen`=on bzw. `ladestatus`=on), zeigt die Kachel den
 * entsprechenden Zustand trotzdem — MIT ehrlicher Quelle (eigene `hybrid`-
 * Variante, eigene Texte in `i18n`, NIE als „echter" vacuum-State ausgegeben).
 * Liefert die Familie GAR NICHTS Brauchbares ⇒ `unreachable`, wie bisher.
 */
export function vacuumFamilyStatus(family: VacuumFamily | null): VacuumTileStatus {
  if (!family) return { kind: 'unreachable' };
  const primary = vacuumTileStatus(family.vacuum);
  if (primary.kind === 'known') return primary;
  if (usableState(family.cleaning) === 'on') return { kind: 'hybrid', status: 'cleaning' };
  if (usableState(family.charging) === 'on') return { kind: 'hybrid', status: 'charging' };
  return { kind: 'unreachable' };
}

/**
 * Last-known-good-Fallback der Sauger-Kachel (additiv, Andi-Auftrag
 * 2026-08-13, „Sauger-Sichtbarkeits-Lücke"): NUR die `vacuum.*`-Entity
 * selbst trägt `lastKnown` (bewusst NICHT die ganze Metrik-Familie — Andis
 * Vorgabe „Akku aus lastKnown-attrs wenn da" nennt genau EIN Attribut, s.
 * {@link vacuumLastKnownBattery}). `null`, wenn kein `lastKnown` mitkommt
 * ODER sein `state` außerhalb der sechs bekannten HA-Aktivitäten liegt
 * (dieselbe Ehrlichkeitsregel wie {@link vacuumTileStatus} — kein geratener
 * Zustand für einen unbekannten String) ODER `seenAt` kein parsbares Datum ist.
 */
export function vacuumLastKnownStatus(family: VacuumFamily | null): { status: VacuumStatusKind; seenAtMs: number } | null {
  const lk = family?.vacuum.lastKnown;
  if (!lk || !KNOWN_VACUUM_STATES.has(lk.state)) return null;
  const seenAtMs = Date.parse(lk.seenAt);
  if (!Number.isFinite(seenAtMs)) return null;
  return { status: lk.state as VacuumStatusKind, seenAtMs };
}

/** `family.vacuum.lastKnown.attrs.battery_level` als Zahl — „Akku aus lastKnown-attrs wenn da" (Andi-Vorgabe). `null` wenn nicht vorhanden/parsbar. */
export function vacuumLastKnownBattery(family: VacuumFamily | null): number | null {
  const raw = family?.vacuum.lastKnown?.attrs.battery_level;
  if (raw === undefined) return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

/** `sensor.<stem>_aktueller_raum`, roh — NUTZERDATEN (Roborock-Raumname), NIE übersetzt. `null` wenn nicht brauchbar. */
export function vacuumFamilyRoom(family: VacuumFamily | null): string | null {
  return usableState(family?.currentRoom);
}

/** `sensor.<stem>_reinigungsfortschritt` als Zahl, NUR während `binary_sensor.<stem>_reinigen`=on — sonst `null` (keine stehende Prozentzahl im Stillstand). */
export function vacuumFamilyProgress(family: VacuumFamily | null): number | null {
  if (usableState(family?.cleaning) !== 'on') return null;
  const raw = usableState(family?.progress);
  if (raw === null) return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

/** Bevorzugt `sensor.<stem>_batterie` (state), Fallback `vacuum.attrs.battery_level` ({@link vacuumBatteryLevel}). */
export function vacuumFamilyBattery(family: VacuumFamily | null): number | null {
  const raw = usableState(family?.battery);
  if (raw !== null) {
    const n = Number(raw);
    if (Number.isFinite(n)) return n;
  }
  return vacuumBatteryLevel(family?.vacuum ?? null);
}

/**
 * Sentinel-Werte, die `staubsauger_fehler`/`dock_dock_fehler` defensiv NICHT
 * als „echten" Fehler zählen lassen (Andi-Vorgabe wörtlich: „nicht 'none'/
 * 'ok'/'0'/leer"). Case-/Whitespace-insensitiv verglichen. Jeder andere
 * brauchbare Wert gilt als echter Fehlwert — RATE-STELLE: die realen
 * Roborock-Fehlerstrings kenne ich nicht (Gerät war beim Bau offline), diese
 * Liste ist die defensive Auslegung von Andis Vorgabe, kein verifiziertes Format.
 */
const NON_ERROR_SENTINELS: ReadonlySet<string> = new Set(['none', 'ok', '0', '']);

function isRealErrorValue(entity: HomeRegistryEntity | undefined): boolean {
  const raw = usableState(entity);
  if (raw === null) return false;
  return !NON_ERROR_SENTINELS.has(raw.trim().toLowerCase());
}

/**
 * AMBER nur bei (Andi-Vorgabe, wörtlich übernommen): `vacuum.state`=`error`
 * ODER `wasserknappheit`=on ODER `wasserkasten_angebracht`=off ODER
 * `staubsauger_fehler`/`dock_dock_fehler` mit echtem Fehlwert
 * ({@link isRealErrorValue}). Alles andere (fehlende/unavailable Sensoren,
 * harmlose Sentinel-Werte) bleibt bewusst OHNE Amber — im Zweifel kein Amber.
 */
export function vacuumFamilyAmber(family: VacuumFamily | null, status: VacuumTileStatus): boolean {
  if (!family) return false;
  if (status.kind === 'known' && status.status === 'error') return true;
  if (usableState(family.waterShortage) === 'on') return true;
  if (usableState(family.waterboxAttached) === 'off') return true;
  if (isRealErrorValue(family.vacuumError)) return true;
  if (isRealErrorValue(family.dockError)) return true;
  return false;
}

/**
 * Die rohen Fehlwerte von `staubsauger_fehler`/`dock_dock_fehler`, NUR wenn
 * sie einen echten Fehler tragen ({@link isRealErrorValue}) — Andis „aber
 * Wert zeigen": auch wenn ein Sensor NICHT allein für Amber reicht, zeigt die
 * Kachel den Rohwert an, sobald er als echter Fehler gilt (statt ihn nur für
 * die Amber-Entscheidung zu verbrauchen und wegzuwerfen). Beide Sensoren
 * können gleichzeitig auftauchen, deshalb ein Array.
 */
export function vacuumFamilyErrorDetails(family: VacuumFamily | null): Array<{ source: 'vacuum' | 'dock'; value: string }> {
  if (!family) return [];
  const out: Array<{ source: 'vacuum' | 'dock'; value: string }> = [];
  if (isRealErrorValue(family.vacuumError)) out.push({ source: 'vacuum', value: family.vacuumError!.state as string });
  if (isRealErrorValue(family.dockError)) out.push({ source: 'dock', value: family.dockError!.state as string });
  return out;
}

/** Ein Wartungs-Wert (Restzeit Bürste/Filter/Sensoren) — roher Wert PLUS Einheit, NUR wenn HA sie mitliefert (NIE geraten). */
export interface VacuumMaintenanceValue {
  value: string;
  /** `attrs.unit_of_measurement`, `null` wenn HA sie für diesen Sensor nicht führt. */
  unit: string | null;
}

/** Brauchbarer Wert (+ Einheit, falls vorhanden) einer Wartungs-Restzeit-Entity — `null` wenn nicht brauchbar. */
export function vacuumMaintenanceValue(entity: HomeRegistryEntity | undefined): VacuumMaintenanceValue | null {
  const value = usableState(entity);
  if (value === null) return null;
  const unit = entity?.attrs?.unit_of_measurement;
  return { value, unit: unit && unit.trim() ? unit : null };
}

// ─────────────────────────────────────────────────────────────────────────
//  Wartungs-Restzeit lesbar (Andi 22.08., ORDER-sauger-wartung-lesbar):
//  „Hauptbürste: 634362 s" statt „noch ~7 Tage". Schritt 1 des Auftrags war,
//  die Sekunden-Semantik zu BEWEISEN statt zu vermuten — s. KDoc unten.
// ─────────────────────────────────────────────────────────────────────────

/**
 * **Sekunden-Semantik BEWIESEN** (ORDER-sauger-wartung-lesbar-2026-08-22,
 * Schritt 1 — die Deutung der Hand im Auftrag war ein VERDACHT, kein Fakt,
 * und dieser Pod hat sie NICHT einfach übernommen): HA Cores `roborock`-
 * Integration (`homeassistant-roborock/custom_components/roborock/sensor.py`,
 * per Websuche gegen den echten Integrations-Quelltext geprüft) führt genau
 * diese vier Sensoren als `main_brush_time_left`/`side_brush_time_left`/
 * `filter_time_left`/`sensor_time_left`, jeweils mit
 * `native_unit_of_measurement=UnitOfTime.SECONDS`, `device_class=DURATION`.
 * „Time left" ist Restzeit BIS zum fälligen Wechsel, kein Verbrauchs-Zähler
 * seit dem letzten Wechsel — ein Wert unter 0 ist am echten Gerät möglich
 * (Verbrauch überschreitet die konfigurierte Lebensdauer) und heißt
 * „überfällig", nicht „kaputt". Der eigene BE-Vertrag (`HaHomeRegistryAdapter`
 * `ATTR_ALLOWLIST`-KDoc, `unit_of_measurement` „NUR stringifiziert
 * durchgereicht, NIE interpretiert/umgerechnet") bestätigt die andere Hälfte:
 * der BE trifft dazu keine eigene Aussage, die FE-Deutung steht dem nicht
 * entgegen — und braucht deshalb KEINE BE-Änderung (reine FE-Scheibe).
 *
 * **NIE geraten bleibt die EINHEIT selbst** (wie überall in dieser Datei):
 * konvertiert wird NUR, wenn `unit_of_measurement` eines der vier bekannten
 * HA-`UnitOfTime`-Kürzel trägt. Fehlt die Einheit ODER trägt sie etwas
 * anderes (ein künftiger Sauger könnte z.B. Prozent liefern), bleibt der Wert
 * UNKONVERTIERT — {@link vacuumMaintenanceSeconds} liefert dann `null`, und
 * die Kachel fällt auf die alte, ehrliche Wert+Einheit-Anzeige zurück
 * (`formatMaintenanceValue` in `HomeTileCards.tsx`).
 */
const TIME_UNIT_SECONDS: Readonly<Record<string, number>> = { s: 1, min: 60, h: 3600, d: 86400 };

/** {@link VacuumMaintenanceValue} als Sekunden, NUR wenn Zahl UND Einheit bekannt sind — sonst `null` (nie geraten). */
export function vacuumMaintenanceSeconds(v: VacuumMaintenanceValue): number | null {
  if (v.unit === null) return null;
  const factor = TIME_UNIT_SECONDS[v.unit];
  if (factor === undefined) return null;
  const n = Number(v.value);
  return Number.isFinite(n) ? n * factor : null;
}

/**
 * Grobe Dauer-Stufe einer Wartungs-Restzeit — dieselbe Idee wie
 * {@link relativeAgeStage} (minutes/hours/days statt einer nackten Zahl),
 * aber mit einer 48h- statt 24h-Schwelle: eine Restzeit von 26 h soll
 * „~26 h" bleiben, nicht schon bei einem Tag auf „~1 Tag" runden und die
 * Größenordnung verschlucken (Auftrag wörtlich: „1–48 h → ~26 h",
 * „≥2 Tage → ~7 Tage"). Erwartet den BETRAG — das Vorzeichen entscheidet
 * {@link formatMaintenanceDuration}, nicht diese Funktion.
 */
export type MaintenanceDurationStage =
  | { kind: 'minutes'; n: number }
  | { kind: 'hours'; n: number }
  | { kind: 'days'; n: number };

const MAINTENANCE_MINUTE_S = 60;
const MAINTENANCE_HOUR_S = 60 * MAINTENANCE_MINUTE_S;
const MAINTENANCE_TWO_DAY_S = 2 * 24 * MAINTENANCE_HOUR_S;
const MAINTENANCE_DAY_S = 24 * MAINTENANCE_HOUR_S;

export function maintenanceDurationStage(absSeconds: number): MaintenanceDurationStage {
  // Rundungs-Rollover an der 1h-Schwelle abgefangen: 3599 s runden auf "60 min"
  // (Math.round(3599/60) = 60) — eine Zeile, die "~60 min" statt "~1 h" sagt,
  // wäre eine kleine Unwahrheit über die eigene Rundungsregel. Bei den Tagen
  // greift dieselbe Rundung nie über die 48h-Schwelle hinaus (172799 s runden
  // auf 48 h, nicht 49), deshalb reicht der Fall hier für Minuten→Stunden.
  if (absSeconds < MAINTENANCE_HOUR_S) {
    const minutes = Math.round(absSeconds / MAINTENANCE_MINUTE_S);
    if (minutes < 60) return { kind: 'minutes', n: minutes };
  }
  if (absSeconds < MAINTENANCE_TWO_DAY_S) return { kind: 'hours', n: Math.round(absSeconds / MAINTENANCE_HOUR_S) };
  return { kind: 'days', n: Math.round(absSeconds / MAINTENANCE_DAY_S) };
}

/** Die sieben Textbausteine aus `i18n` (`homeTiles.vacuum.maintenance.{remaining,overdue}`), s. {@link formatMaintenanceDuration}. */
export interface MaintenanceDurationStrings {
  remaining: {
    /** Exakt 0 s — weder Rest noch Verzug, der eine Grenzfall ohne Zahl. */
    dueNow: string;
    minutes: (n: number) => string;
    hours: (n: number) => string;
    days: (n: number) => string;
  };
  overdue: { minutes: (n: number) => string; hours: (n: number) => string; days: (n: number) => string };
}

/**
 * „noch ~7 Tage" / „überfällig seit ~12 h" statt einer nackten Sekundenzahl —
 * die eigentliche Lesbarkeits-Reparatur (Auftrag Schritt 2). `seconds` ist
 * bereits {@link vacuumMaintenanceSeconds} (Vorzeichen trägt die Bedeutung):
 * `0` ⇒ „jetzt fällig" (Grenzfall der Formatierungs-Matrix), `< 0` ⇒
 * überfällig seit `|seconds|`, sonst Restzeit. „noch"/„überfällig seit"
 * machen IMMER klar, dass es eine Restzeit ist — nie eine nackte Zahl, die
 * wie eine Vergangenheits-Angabe („vor 4 Tg.") missverstanden werden könnte.
 */
export function formatMaintenanceDuration(seconds: number, strings: MaintenanceDurationStrings): string {
  if (seconds === 0) return strings.remaining.dueNow;
  const overdue = seconds < 0;
  const stage = maintenanceDurationStage(Math.abs(seconds));
  const bucket = overdue ? strings.overdue : strings.remaining;
  switch (stage.kind) {
    case 'minutes':
      return bucket.minutes(stage.n);
    case 'hours':
      return bucket.hours(stage.n);
    case 'days':
      return bucket.days(stage.n);
  }
}

/** `on`/`off` eines binary_sensor als `true`/`false`; jeder andere/fehlende Wert ⇒ `null` (keine Zeile, kein Raten). */
export function vacuumFamilyAttached(entity: HomeRegistryEntity | undefined): boolean | null {
  const raw = usableState(entity);
  if (raw === 'on') return true;
  if (raw === 'off') return false;
  return null;
}

// ─────────────────────────────────────────────────────────────────────────
//  Cache-Carry, „zuletzt fertig" und die zwei Knöpfe (Andi 21.08.:
//  „Zuletzt gesehen vor 2 Min. … das ist Lärm, meistens ist er einfach im
//  Energiesparmodus" + „Können wir den Sauger starten und nach Hause fahren
//  lassen?"). Alles hier bleibt REIN — kein Netz, kein `Date.now()`.
// ─────────────────────────────────────────────────────────────────────────

/** Alle Entities der Familie (die `vacuum.*`-Entity plus jedes gefundene Mitglied) — Reihenfolge unerheblich, alle Leser aggregieren. */
function familyEntities(family: VacuumFamily): HomeRegistryEntity[] {
  return Object.values(family).filter((e): e is HomeRegistryEntity => e !== undefined);
}

/**
 * **„Stand HH:MM"-Quelle** (Cache-Carry, s.
 * {@link HomeRegistryEntity.fromCacheSinceMs}): der Zeitpunkt, ab dem die
 * angezeigten Werte NICHT mehr live sind. `null` ⇒ alles live, die Kachel
 * schweigt darüber.
 *
 * **Es wird der ÄLTESTE Stempel der Familie genommen, nicht der jüngste.**
 * Der BE cacht je Entity; im Mischfall (Akku live, Status seit 14:20 gecacht)
 * ist der jüngste Stempel eine Beschönigung — die Zeile behauptete Frische für
 * Werte, die sie nicht haben. Der älteste ist die ehrliche Untergrenze: „ab
 * hier ist etwas von dem, was du siehst, aus dem Gedächtnis."
 *
 * Kein eigenes Verfallsdatum: die Obergrenze (`HOSHI_VACUUM_CACHE_MAX_AGE_HOURS`,
 * Default 24 h) liegt beim BE — jenseits davon kommt gar kein Carry mehr und
 * die Kachel fällt von selbst auf ihr ehrliches Abwesenheits-Bild zurück.
 */
export function vacuumFamilyCacheSince(family: VacuumFamily | null): number | null {
  if (!family) return null;
  let oldest: number | null = null;
  for (const entity of familyEntities(family)) {
    const ms = entity.fromCacheSinceMs;
    if (ms === undefined || !Number.isFinite(ms) || ms <= 0) continue;
    if (oldest === null || ms < oldest) oldest = ms;
  }
  return oldest;
}

/** Ein abgeschlossener Reinigungslauf, so weit die Familie ihn belegen kann ({@link vacuumLastClean}). */
export interface VacuumLastClean {
  /** Epoch-ms von `sensor.<stem>_letztes_reinigungsende`. */
  endMs: number;
  /** Epoch-ms von `sensor.<stem>_letzter_reinigungsbeginn` — `null`, wenn HA ihn nicht führt oder er nicht zum Ende passt. */
  startMs: number | null;
  /** `endMs - startMs`, NUR wenn beide da sind und die Dauer plausibel ist (>0 und < 24 h). Sonst `null`. */
  durationMs: number | null;
}

/** Roher State einer Familie-Entity als Epoch-ms (HA liefert `device_class: timestamp` als ISO-8601). `null` wenn unbrauchbar. */
function usableTimestampMs(entity: HomeRegistryEntity | undefined): number | null {
  const raw = usableState(entity);
  if (raw === null) return null;
  const ms = Date.parse(raw);
  return Number.isFinite(ms) ? ms : null;
}

const MAX_CLEAN_RUN_MS = 24 * 60 * 60 * 1000;
/** Toleranz gegen Uhr-Drift zwischen HA-Server und Browser, bevor ein Ende als „in der Zukunft" verworfen wird. */
const CLOCK_DRIFT_TOLERANCE_MS = 60_000;

/**
 * **„zuletzt fertig 14:20"** (Andi 21.08.: „Was haben wir noch, was man
 * hinzufügen kann, wenn man das Widget größer macht?") — aus `lastCleanEnd`,
 * optional mit Dauer aus `lastCleanStart`. Beide Sensoren sind seit dem
 * Familien-Bau gemappt, hatten aber nie eine Ableitung (Design-Doc §7,
 * Punkt 9: „Mein L-Vorschlag ‚zuletzt fertig 14:20' braucht also eine neue
 * Funktion").
 *
 * `null`, wenn:
 *  - `lastCleanEnd` fehlt/unavailable/kein parsbares Datum ist, ODER
 *  - das Ende mehr als eine Minute in der ZUKUNFT liegt. Ein Lauf, der noch
 *    nicht fertig ist, ist nicht „zuletzt fertig" — lieber keine Zeile als
 *    eine, die vorwärts zeigt.
 *
 * `startMs`/`durationMs` bleiben `null`, wenn der Beginn fehlt, nach dem Ende
 * liegt oder eine Dauer ≥ 24 h ergäbe (dann gehören die zwei Stempel
 * offensichtlich nicht zum selben Lauf — HA hält beide Sensoren getrennt
 * fort, und ein hängengebliebener Beginn darf keine „37 h"-Zeile erfinden).
 */
export function vacuumLastClean(family: VacuumFamily | null, nowMs: number): VacuumLastClean | null {
  if (!family) return null;
  const endMs = usableTimestampMs(family.lastCleanEnd);
  if (endMs === null || endMs > nowMs + CLOCK_DRIFT_TOLERANCE_MS) return null;
  const startMs = usableTimestampMs(family.lastCleanStart);
  const span = startMs === null ? null : endMs - startMs;
  const plausible = span !== null && span > 0 && span < MAX_CLEAN_RUN_MS;
  return { endMs, startMs: plausible ? startMs : null, durationMs: plausible ? span : null };
}

/** Welcher der zwei Knöpfe gehört auf die Kachel ({@link vacuumActionAvailability})? */
export interface VacuumActionAvailability {
  canStart: boolean;
  canReturn: boolean;
}

/**
 * **Knopf-Semantik** — die vom BE-Pod empfohlene Tabelle
 * (`vault/tracks/RESULT-sauger-aktionen-2026-08-21.md` §3), hier als EINE
 * reine Funktion, damit die Kachel sie nicht in JSX nachbaut:
 *
 * | Knopf | sichtbar bei |
 * |---|---|
 * | **Start** | `known` ∈ {docked, idle, paused} · `hybrid` charging |
 * | **Zur Basis** | `known` ∈ {cleaning, paused} · `hybrid` cleaning |
 *
 * `paused` trägt beide (weiterfahren ODER heimfahren — beides ist von dort aus
 * eine sinnvolle Bitte). `returning` trägt KEINEN: er ist bereits unterwegs
 * nach Hause, und „Start" mitten in der Rückfahrt wäre ein Befehl, den niemand
 * gemeint hat. `error` trägt keinen, `unreachable` erst recht nicht — dort
 * weiß niemand etwas über den Sauger.
 *
 * **Der Cache-Carry ist der Grund, warum das überhaupt trägt:** ohne ihn wäre
 * der schlafende, gedockte Sauger `unreachable` und beide Knöpfe wären im
 * Normalfall tot. Mit ihm ist er `docked` — und „Start" ist bedienbar, genau
 * wie bestellt.
 */
export function vacuumActionAvailability(status: VacuumTileStatus): VacuumActionAvailability {
  if (status.kind === 'known') {
    return {
      canStart: status.status === 'docked' || status.status === 'idle' || status.status === 'paused',
      canReturn: status.status === 'cleaning' || status.status === 'paused',
    };
  }
  if (status.kind === 'hybrid') {
    return { canStart: status.status === 'charging', canReturn: status.status === 'cleaning' };
  }
  return { canStart: false, canReturn: false };
}

/** „14:20" in der aktiven Locale — Muster `StageSparkline`/`useScheduledItems` (dieselben zwei Optionen, kein zweites Zeitformat im Haus). */
export function formatClock(epochMs: number, locale: string = 'de-DE'): string {
  return new Date(epochMs).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
}

/** Liegen zwei Zeitpunkte am selben LOKALEN Kalendertag? Entscheidet, ob „14:20" allein reicht oder ein relatives Alter dazu muss. */
export function isSameLocalDay(aMs: number, bMs: number): boolean {
  const a = new Date(aMs);
  const b = new Date(bMs);
  return (
    a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
  );
}

/** Last-known-good-Fallback einer Klima-Zeile (additiv, s. {@link ClimateRoomRow.lastKnown}). */
export interface ClimateRoomLastKnown {
  currentTemperature: number | null;
  targetTemperature: number | null;
  heating: boolean;
  /** Epoch-ms von `lastKnown.seenAt` (ISO), `NaN` wenn HA/BE einen kaputten Zeitstempel schickte (defensiv, s. {@link relativeAgeStage}). */
  seenAtMs: number;
}

/** Eine Zeile der Klima-Kachel — ein Raum MIT mindestens einer `climate`-Entity. */
export interface ClimateRoomRow {
  areaId: string;
  /** Raumname — NUTZERDATEN, nie übersetzt. */
  label: string;
  /** `attrs.current_temperature`, geparst; `null` wenn HA sie nicht führt. */
  currentTemperature: number | null;
  /** `attrs.temperature` (Soll-Wert), geparst; `null` wenn HA sie nicht führt. */
  targetTemperature: number | null;
  /** `attrs.hvac_action === 'heating'`. */
  heating: boolean;
  /** {@link isEntityAvailable} des Klima-Geräts dieser Zeile. */
  available: boolean;
  /**
   * Last-known-good-Fallback (additiv, Andi-Auftrag 2026-08-13, „Sauger-
   * Sichtbarkeits-Lücke" — dieselbe Naht gilt auch fürs Klima-Gerät): `null`,
   * solange kein `lastKnown` vom BE mitkommt (bereits erreichbar ODER nie ein
   * Vorerfolg). Der BE hängt `lastKnown` NUR an, wenn [available] `false`
   * ist — die Kachel-Komponente prüft das trotzdem selbst (Verteidigung in
   * der Tiefe), bevor sie diese Werte statt der stillen „nicht erreichbar"-
   * Zeile zeigt.
   */
  lastKnown: ClimateRoomLastKnown | null;
}

function parseTemp(raw: string | undefined): number | null {
  if (raw === undefined) return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

/** {@link ClimateRoomRow.lastKnown} aus `entity.lastKnown` — `null` wenn keins mitkam. */
function toClimateLastKnown(entity: HomeRegistryEntity): ClimateRoomLastKnown | null {
  const lk = entity.lastKnown;
  if (!lk) return null;
  return {
    currentTemperature: parseTemp(lk.attrs.current_temperature),
    targetTemperature: parseTemp(lk.attrs.temperature),
    heating: lk.attrs.hvac_action === 'heating',
    seenAtMs: Date.parse(lk.seenAt),
  };
}

/**
 * Eine Zeile je Area MIT climate-Entity — Areas ohne Klima-Gerät bleiben
 * ausgeblendet (Andis Bedingung: „die Kachel ist erst sinnvoll, wenn Räume
 * zugewiesen sind"). Trägt eine Area mehrere Klima-Geräte, zählt nur das
 * erste — die Kachel zeigt bewusst EINE Zeile je Raum, kein zweiter Baum.
 * Leeres Ergebnis ⇒ die Klima-Kachel hat (noch) keinen Grund zu existieren
 * (der Einstellungs-Schalter prüft genau das, s. SettingsPanel).
 */
export function climateRoomRows(snapshot: HomeRegistrySnapshot): ClimateRoomRow[] {
  const rows: ClimateRoomRow[] = [];
  for (const area of snapshot.areas) {
    const climate = area.entities.find((e) => e.domain === 'climate');
    if (!climate) continue;
    rows.push({
      areaId: area.areaId,
      label: area.label,
      currentTemperature: parseTemp(climate.attrs?.current_temperature),
      targetTemperature: parseTemp(climate.attrs?.temperature),
      heating: climate.attrs?.hvac_action === 'heating',
      available: isEntityAvailable(climate),
      lastKnown: toClimateLastKnown(climate),
    });
  }
  return rows;
}

/**
 * Wärmster/kältester Raum nach `currentTemperature` — für die S-Stufe der
 * Klima-Kachel (DESIGN-widget-raster-2026-08-18 §3.3, „wärmster/kältester
 * Raum als EIN Satz"). Räume ohne brauchbare Ist-Temperatur (nicht erreichbar,
 * kein Wert) fallen raus — nie ein geratenes Extrem. `null` ohne einen einzigen
 * brauchbaren Raum. Gleichstand behält den ersten Treffer in `rows`-Reihenfolge
 * (stabil, kein Neusortieren der Registry-Reihenfolge).
 */
export interface ClimateExtremeRoom {
  areaId: string;
  label: string;
  currentTemperature: number;
}

export interface ClimateExtremes {
  warmest: ClimateExtremeRoom;
  coldest: ClimateExtremeRoom;
}

export function climateExtremes(rows: readonly ClimateRoomRow[]): ClimateExtremes | null {
  const usable = rows.filter(
    (r): r is ClimateRoomRow & { currentTemperature: number } => r.currentTemperature !== null,
  );
  if (usable.length === 0) return null;
  const warmest = usable.reduce((a, b) => (b.currentTemperature > a.currentTemperature ? b : a));
  const coldest = usable.reduce((a, b) => (b.currentTemperature < a.currentTemperature ? b : a));
  return {
    warmest: { areaId: warmest.areaId, label: warmest.label, currentTemperature: warmest.currentTemperature },
    coldest: { areaId: coldest.areaId, label: coldest.label, currentTemperature: coldest.currentTemperature },
  };
}

/** Dezimaltrenner je Locale (Muster `IdleFace.DECIMAL_SEPARATOR`, hier dupliziert statt gekoppelt). */
const DECIMAL_SEPARATOR: Record<string, string> = {
  'de-DE': ',',
  'en-US': '.',
  'es-ES': ',',
  'fr-FR': ',',
  'it-IT': ',',
};

/** „21,5°" (de) / „21.5°" (en) — rundet auf eine Nachkommastelle, lässt eine glatte „.0" weg. */
export function fmtTemp(value: number, locale: string = 'de-DE'): string {
  const sep = DECIMAL_SEPARATOR[locale] ?? '.';
  const rounded = Math.round(value * 10) / 10;
  const str = Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1).replace('.', sep);
  return `${str}°`;
}

// ─────────────────────────────────────────────────────────────────────────
//  Relative Alters-Anzeige der Last-known-good-Zeilen (Andi-Auftrag
//  2026-08-13, „Sauger-Sichtbarkeits-Lücke") — GEMEINSAM von Sauger- und
//  Klima-Kachel genutzt, deshalb hier statt in einer der beiden Sektionen.
//  Einfache Minuten/Stunden/Tage-Stufen (keine bestehende Locale-Relativzeit-
//  Mechanik im Repo gefunden, s. RATE-STELLEN im Pod-Report) — die eigentliche
//  Wortwahl kommt aus `i18n` (`homeTiles.age`), diese Funktionen liefern nur
//  die Stufe + Zahl.
// ─────────────────────────────────────────────────────────────────────────

const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

export type RelativeAgeStage =
  | { kind: 'justNow' }
  | { kind: 'minutes'; n: number }
  | { kind: 'hours'; n: number }
  | { kind: 'days'; n: number };

/**
 * Grobe Alters-Stufe von `seenAtMs` aus Sicht von `nowMs`. NIE negativ — ein
 * `seenAtMs` in der Zukunft (Client-Uhr-Drift) fällt ehrlich auf `justNow`
 * statt eine negative Minutenzahl zu zeigen. `seenAtMs` NaN (kaputtes Datum,
 * s. {@link vacuumLastKnownStatus}/{@link toClimateLastKnown}) fällt ebenso
 * auf `justNow` — die Aufrufer filtern kaputte Zeitstempel ohnehin vorher weg,
 * dies ist nur die letzte Verteidigungslinie.
 */
export function relativeAgeStage(seenAtMs: number, nowMs: number): RelativeAgeStage {
  const diff = Number.isFinite(seenAtMs) ? Math.max(0, nowMs - seenAtMs) : 0;
  if (diff < MINUTE_MS) return { kind: 'justNow' };
  if (diff < HOUR_MS) return { kind: 'minutes', n: Math.floor(diff / MINUTE_MS) };
  if (diff < DAY_MS) return { kind: 'hours', n: Math.floor(diff / HOUR_MS) };
  return { kind: 'days', n: Math.floor(diff / DAY_MS) };
}

/** Die vier Text-Stufen aus `i18n` (`homeTiles.age`), s. {@link relativeAgeStage}. */
export interface RelativeAgeStrings {
  justNow: string;
  minutesAgo: (n: number) => string;
  hoursAgo: (n: number) => string;
  daysAgo: (n: number) => string;
}

/** Formatiert {@link relativeAgeStage} mit den lokalisierten Textbausteinen — „vor 5 Min." (de) / „5 min ago" (en) je nach [strings]. */
export function formatRelativeAge(seenAtMs: number, nowMs: number, strings: RelativeAgeStrings): string {
  const stage = relativeAgeStage(seenAtMs, nowMs);
  switch (stage.kind) {
    case 'justNow':
      return strings.justNow;
    case 'minutes':
      return strings.minutesAgo(stage.n);
    case 'hours':
      return strings.hoursAgo(stage.n);
    case 'days':
      return strings.daysAgo(stage.n);
  }
}

/**
 * **S2 „Ehrliche Anwesenheit"** (DESIGN-widgets-settings-2026-08-15 §2.4) — the
 * ONE rule that decides whether a tile whose live source is gone owes the loud,
 * honest presence line ("unavailable since …") or keeps today's quiet whisper.
 * Shared by both tiles so the two answers cannot drift apart.
 *
 * Returns the epoch-ms of the last true sighting to render, or `null` for
 * "stay quiet". Two guards, and both matter:
 *
 *  1. **[registry] `null` ⇒ quiet.** `null` is not "gone", it is "the first
 *     fetch has not answered yet". Announcing an outage during the load flash
 *     would accuse the household of a fault that nobody has established. Every
 *     real answer counts, including `off`/`unreachable`: from the tile's seat
 *     the source is then just as unreadable as a missing entity, and saying so
 *     is the honest report.
 *  2. **[lastSeenMs] `null` ⇒ quiet.** This is the design's "first appearance
 *     stays earned" rule, unchanged: a source this browser has never seen alive
 *     gets no presence, no duration, no claim. Only a KNOWN source that fell
 *     back is loud enough to be missed.
 *
 * A stamp from the future (client clock drift) is not filtered here —
 * {@link relativeAgeStage} already clamps it to "just now" rather than
 * rendering a negative age.
 */
export function homeTileUnavailableSince(
  registry: HomeRegistryState | null,
  lastSeenMs: number | null,
): number | null {
  if (registry === null) return null;
  if (lastSeenMs === null || !Number.isFinite(lastSeenMs) || lastSeenMs <= 0) return null;
  return lastSeenMs;
}

import { API_BASE, TOKEN } from './config';

/**
 * Typisierter Client für `GET /api/v1/home/registry` (Scheibe 1 des
 * Geräte-Zuordnungs-Konzepts, `.orch-bus/ctx/cowork-research-2026-07-15/
 * 11-geraete-zuordnung-konzept.md`), Spiegel von `de.hoshi.web.HomeRegistryController`
 * / `de.hoshi.adapters.ha.HaHomeRegistryAdapter`. READ-ONLY: HA bleibt die
 * eine Wahrheit, dieser Client zeigt nur ihren aktuellen Stand.
 *
 * Drei EHRLICHE Zustände (Muster `api/weatherToday`-Pendant `useWeatherToday`):
 *  - `{kind:'live', data}` — echter Snapshot (Areas + `unassigned`), nie erfunden.
 *  - `{kind:'off'}` — 404: die Naht ist beim Deploy deaktiviert
 *    (`HOSHI_HA_ENABLED`) ⇒ das FE bleibt bei der ehrlichen „kommt"-Ansicht.
 *  - `{kind:'unreachable'}` — 401/502/5xx/Netz/kaputter Body: die Naht
 *    existiert, liefert aber grad nichts Lesbares ⇒ „gerade nicht erreichbar".
 * `null` im Hook = noch nicht geladen (der erste Fetch läuft).
 */

/** Ein einzelnes HA-Entity — Spiegel von `HomeRegistryEntity` (Kotlin). */
export interface HomeRegistryEntity {
  entityId: string;
  /** `entity_id`-Präfix vor dem ersten `.` (z.B. `light`, `switch`, `sensor`). */
  domain: string;
  name: string;
  /** HA-Label-Namen; leer = kein Label gesetzt. */
  labels: string[];
  /**
   * Roher HA-Zustand (z.B. `"cleaning"`/`"docked"`/`"heat"`/`"unavailable"`) —
   * additiv seit der Zuhause-Kacheln-Scheibe (Andi-Auftrag 2026-08-11, ein
   * BE-Pod baut denselben Vertrag parallel). Fehlt das Feld (Alt-Backend oder
   * HA führt für diese Entity gerade keinen State) ⇒ „unbekannt", NIE geraten
   * — s. `components/homeTiles.ts#isEntityAvailable`.
   */
  state?: string;
  /**
   * Höchstens die für die Zuhause-Kacheln relevanten HA-Attribute:
   * `battery_level` (Sauger), `current_temperature`/`temperature`/
   * `hvac_action` (Klima), `unit_of_measurement` (Sauger-Metrik-Familie,
   * Andi-Auftrag 2026-08-13 — Einheit der Wartungs-Restzeiten, NIE geraten,
   * NUR angezeigt wenn HA sie mitliefert). Additiv + defensiv geparst — jedes
   * Attribut fehlt EINZELN, wenn HA es für diese Entity nicht führt;
   * unbekannte Attribut-Keys werden verworfen statt durchgereicht (kein
   * offener Grab-Bag).
   */
  attrs?: Record<string, string>;
  /**
   * Last-known-good-Fallback (additiv, Andi-Auftrag 2026-08-13, „Sauger-
   * Sichtbarkeits-Lücke" — der Roborock hängt ~23 h/Tag im WLAN-Tiefschlaf,
   * der `state`-Merge trifft sein Wach-Fenster fast nie): der BE hängt dieses
   * Feld NUR an, wenn [state] GERADE unbrauchbar ist (fehlt/`unavailable`/
   * `unknown`) UND irgendwann zuvor ein brauchbarer Zustand gemerkt wurde —
   * NIE gleichzeitig mit einem brauchbaren `state`. `attrs` hier ist
   * dieselbe Allowlist wie oben, `seenAt` ein ISO-8601-Zeitpunkt.
   */
  lastKnown?: { state: string; attrs: Record<string, string>; seenAt: string };
  /**
   * **Cache-Carry** (additiv, BE-Pod `pod/sauger-aktionen`, s.
   * `vault/tracks/RESULT-sauger-aktionen-2026-08-21.md` §2): Epoch-ms der
   * letzten LIVE-Sichtung dieser Entity. **Anwesend ⇒ [state]/[attrs] kommen
   * aus dem Cache, nicht live.** Abwesend ⇒ live (`NON_NULL` am BE: eine
   * nicht-gecachte Entity trägt das Feld gar nicht).
   *
   * Der BE setzt es NUR für die Sauger-Familie und nur innerhalb der
   * Obergrenze `HOSHI_VACUUM_CACHE_MAX_AGE_HOURS` (Default 24 h) — darüber
   * hinaus fällt die Entity ehrlich auf das Unavailable-Bild zurück. **Das
   * FE braucht deshalb keine eigene Verfalls-Schwelle**: dass hier ein Wert
   * steht, heißt bereits „jung genug, um etwas zu bedeuten".
   *
   * Warum Epoch-ms statt „Alter in ms": ein Alter driftete über die States-TTL
   * (60 s) hinweg, ein Zeitstempel nie — „Stand HH:MM" ist daraus direkt
   * formatierbar (`components/homeTiles.ts#vacuumFamilyCacheSince`).
   */
  fromCacheSinceMs?: number;
}

/** Eine HA-Area mit ihren Geräten — Spiegel von `HomeRegistryArea` (Kotlin). Kann leer sein. */
export interface HomeRegistryArea {
  areaId: string;
  label: string;
  entities: HomeRegistryEntity[];
  /**
   * 14-Tage-Zählung der an diese Area gerichteten Kommandos (Nutzungs-Naht:
   * Diary→`AreaUsageReader` am `GET /home/registry`-Rand). Optional + additiv —
   * fehlend heißt „nicht gemessen" (alte Snapshots) und wird wie 0 behandelt.
   */
  recentCommands?: number;
}

/** Der ganze Snapshot — Spiegel von `HomeRegistrySnapshot` (Kotlin). */
export interface HomeRegistrySnapshot {
  areas: HomeRegistryArea[];
  /** Entities OHNE Area-Zuordnung — die „tado-Lücke", ehrlich sichtbar statt versteckt. */
  unassigned: HomeRegistryEntity[];
  /**
   * ISO-8601-Zeitpunkt des letzten ERFOLGREICHEN States-Merges (additiv,
   * Andi-Auftrag 2026-08-13) — `undefined`/fehlend heißt „noch nie
   * erfolgreich" (Alt-Backend ODER der States-Call ist bislang jedes Mal
   * gescheitert). Aktuell nur informativ, kein Kachel-Konsument.
   */
  statesFetchedAt?: string;
}

export type HomeRegistryState =
  | { kind: 'live'; data: HomeRegistrySnapshot }
  | { kind: 'off' }
  | { kind: 'unreachable' };

/**
 * Höchstens diese fünf HA-Attribute sind für die Zuhause-Kacheln relevant
 * (Draht-Vertrag der Zuhause-Kacheln-Scheibe, `unit_of_measurement` additiv
 * seit der Sauger-Metrik-Familie, Andi-Auftrag 2026-08-13) — alles andere im
 * rohen `attrs`-Objekt wird beim Parsen verworfen, kein offener Grab-Bag.
 */
const KNOWN_ATTR_KEYS = [
  'battery_level',
  'current_temperature',
  'temperature',
  'hvac_action',
  'unit_of_measurement',
] as const;

/** Defensiver Parse von `attrs`: nur bekannte String-Werte, leeres Ergebnis ⇒ `undefined` (kein leeres Objekt). */
function toAttrs(raw: unknown): Record<string, string> | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const r = raw as Record<string, unknown>;
  const out: Record<string, string> = {};
  for (const key of KNOWN_ATTR_KEYS) {
    const v = r[key];
    if (typeof v === 'string' && v) out[key] = v;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/** Wie {@link toAttrs}, aber IMMER ein Objekt (auch leer) — `lastKnown.attrs` ist laut Draht-Vertrag nie `undefined`, nur ggf. leer. */
function toLastKnownAttrs(raw: unknown): Record<string, string> {
  return toAttrs(raw) ?? {};
}

/**
 * Defensiver Parse von `lastKnown` (s. {@link HomeRegistryEntity.lastKnown}):
 * `state`/`seenAt` müssen nicht-leere Strings sein — `seenAt` wird HIER NICHT
 * als Datum validiert (das erledigen die Kachel-Helfer defensiv beim
 * Anzeigen), ein kaputtes ISO-Format darf also den Rest der Entity nicht
 * mitreißen. Kaputt/fehlt ⇒ `undefined` (kein erfundener Fallback).
 */
function toLastKnown(raw: unknown): HomeRegistryEntity['lastKnown'] {
  if (!raw || typeof raw !== 'object') return undefined;
  const r = raw as Record<string, unknown>;
  if (typeof r.state !== 'string' || !r.state) return undefined;
  if (typeof r.seenAt !== 'string' || !r.seenAt) return undefined;
  return { state: r.state, attrs: toLastKnownAttrs(r.attrs), seenAt: r.seenAt };
}

/** Defensiver Parse eines Entity-Eintrags (kaputte/unbekannte Felder → `null`, nie geraten). */
function toEntity(raw: unknown): HomeRegistryEntity | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.entityId !== 'string' || !r.entityId) return null;
  if (typeof r.domain !== 'string' || !r.domain) return null;
  const name = typeof r.name === 'string' && r.name ? r.name : r.entityId;
  const labels = Array.isArray(r.labels)
    ? r.labels.filter((l): l is string => typeof l === 'string' && l.length > 0)
    : [];
  const entity: HomeRegistryEntity = { entityId: r.entityId, domain: r.domain, name, labels };
  if (typeof r.state === 'string' && r.state) entity.state = r.state;
  const attrs = toAttrs(r.attrs);
  if (attrs) entity.attrs = attrs;
  const lastKnown = toLastKnown(r.lastKnown);
  if (lastKnown) entity.lastKnown = lastKnown;
  // Cache-Carry (s. `HomeRegistryEntity.fromCacheSinceMs`): nur eine endliche
  // POSITIVE Zahl zählt. `0`/negativ/`NaN`/String ⇒ das Feld fehlt lieber, als
  // dass daraus ein „Stand 01:00" von 1970 wird.
  if (typeof r.fromCacheSinceMs === 'number' && Number.isFinite(r.fromCacheSinceMs) && r.fromCacheSinceMs > 0) {
    entity.fromCacheSinceMs = r.fromCacheSinceMs;
  }
  return entity;
}

/** Defensiver Parse eines Area-Eintrags — eine Area OHNE `entities`-Array gilt als leer (nicht kaputt). */
function toArea(raw: unknown): HomeRegistryArea | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.areaId !== 'string' || !r.areaId) return null;
  const label = typeof r.label === 'string' && r.label ? r.label : r.areaId;
  const entities = Array.isArray(r.entities)
    ? r.entities.map(toEntity).filter((e): e is HomeRegistryEntity => e !== null)
    : [];
  const area: HomeRegistryArea = { areaId: r.areaId, label, entities };
  // Nutzungs-Naht: OHNE dieses Durchreichen würde die 1(a)-Sortierung
  // (roomsSort.ts) still auf Geräteanzahl zurückfallen, sobald echte
  // Zählungen existieren — der Parser verschluckte das Feld anfangs
  // (Hand-Selbstanzeige 2026-08-11). Kaputte Werte fallen ehrlich weg.
  if (typeof r.recentCommands === 'number' && Number.isFinite(r.recentCommands) && r.recentCommands >= 0) {
    area.recentCommands = r.recentCommands;
  }
  return area;
}

/** Validiert die Wire-Antwort gegen `{areas:[...], unassigned:[...]}`. Fehlt der Vertrag ⇒ `null`. */
export function parseHomeRegistrySnapshot(body: unknown): HomeRegistrySnapshot | null {
  if (!body || typeof body !== 'object') return null;
  const b = body as Record<string, unknown>;
  if (!Array.isArray(b.areas) || !Array.isArray(b.unassigned)) return null;
  const areas = b.areas.map(toArea).filter((a): a is HomeRegistryArea => a !== null);
  const unassigned = b.unassigned.map(toEntity).filter((e): e is HomeRegistryEntity => e !== null);
  const snapshot: HomeRegistrySnapshot = { areas, unassigned };
  if (typeof b.statesFetchedAt === 'string' && b.statesFetchedAt) snapshot.statesFetchedAt = b.statesFetchedAt;
  return snapshot;
}

/** Abruf mit ehrlicher Zustands-Trennung (Muster `fetchWeatherToday`). */
export async function fetchHomeRegistry(signal?: AbortSignal): Promise<HomeRegistryState> {
  try {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
    const res = await fetch(`${API_BASE}/api/v1/home/registry`, { headers, signal });
    if (res.status === 404) return { kind: 'off' }; // Naht beim Deploy aus — ehrlich „kommt"
    if (!res.ok) return { kind: 'unreachable' }; // 401/502/5xx → grad nicht lesbar
    const body: unknown = await res.json().catch(() => null);
    const data = parseHomeRegistrySnapshot(body);
    return data ? { kind: 'live', data } : { kind: 'unreachable' };
  } catch {
    return { kind: 'unreachable' }; // Netzfehler/Abbruch → nie erfundene Räume/Geräte
  }
}

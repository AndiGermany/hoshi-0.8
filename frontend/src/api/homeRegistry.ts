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
}

/** Eine HA-Area mit ihren Geräten — Spiegel von `HomeRegistryArea` (Kotlin). Kann leer sein. */
export interface HomeRegistryArea {
  areaId: string;
  label: string;
  entities: HomeRegistryEntity[];
}

/** Der ganze Snapshot — Spiegel von `HomeRegistrySnapshot` (Kotlin). */
export interface HomeRegistrySnapshot {
  areas: HomeRegistryArea[];
  /** Entities OHNE Area-Zuordnung — die „tado-Lücke", ehrlich sichtbar statt versteckt. */
  unassigned: HomeRegistryEntity[];
}

export type HomeRegistryState =
  | { kind: 'live'; data: HomeRegistrySnapshot }
  | { kind: 'off' }
  | { kind: 'unreachable' };

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
  return { entityId: r.entityId, domain: r.domain, name, labels };
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
  return { areaId: r.areaId, label, entities };
}

/** Validiert die Wire-Antwort gegen `{areas:[...], unassigned:[...]}`. Fehlt der Vertrag ⇒ `null`. */
export function parseHomeRegistrySnapshot(body: unknown): HomeRegistrySnapshot | null {
  if (!body || typeof body !== 'object') return null;
  const b = body as Record<string, unknown>;
  if (!Array.isArray(b.areas) || !Array.isArray(b.unassigned)) return null;
  const areas = b.areas.map(toArea).filter((a): a is HomeRegistryArea => a !== null);
  const unassigned = b.unassigned.map(toEntity).filter((e): e is HomeRegistryEntity => e !== null);
  return { areas, unassigned };
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

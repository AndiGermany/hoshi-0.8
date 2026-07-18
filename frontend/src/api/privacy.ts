import { API_BASE, TOKEN } from './config';

/**
 * Typisierter Client für den Privatsphäre-Rand, Spiegel von
 * `de.hoshi.web.PrivacyController`:
 *  - `GET    /api/v1/privacy/summary`             → {@link PrivacySummary}
 *  - `DELETE /api/v1/privacy/{memory|episodic|diary}` → {@link PrivacyDeleteResult};
 *    501 ⇒ {@link PrivacyNotYetError} (das FE sagt ehrlich „kommt noch" statt zu raten).
 *
 * Auth + Base-URL exakt wie `api/skills.ts`: Token als `X-Hoshi-Token`-Header (leer ⇒
 * weggelassen → die Auth-Wand greift ehrlich mit 401), Pfade relativ zu `API_BASE`.
 */

/** Eine Store-Zeile (Entity-/Episodic-Memory) — `entries:null` = ehrlich „weiß nicht". */
export interface PrivacyStoreInfo {
  enabled: boolean;
  path: string;
  exists: boolean;
  sizeBytes: number;
  entries: number | null;
}

/** Diary-Zeile: Anzahl Tages-Dateien (das Diary trägt keine Gesprächs-Inhalte). */
export interface PrivacyDiaryInfo {
  enabled: boolean;
  dir: string;
  days: number;
}

/** Wire-Vertrag von `GET /api/v1/privacy/summary` (de.hoshi.web.PrivacySummary). */
export interface PrivacySummary {
  voice: { engine: string; cloud: boolean };
  sanitize: { enabled: boolean };
  memory: PrivacyStoreInfo;
  episodic: PrivacyStoreInfo;
  diary: PrivacyDiaryInfo;
}

/** Die drei löschbaren Datenarten — exakt die DELETE-Pfad-Segmente des Backends. */
export type PrivacyTarget = 'memory' | 'episodic' | 'diary';

/** Erfolgs-Antwort der Lösch-API: die vom Server BEWIESENE Zahl (Zeilen/Dateien). */
export interface PrivacyDeleteResult {
  target: string;
  deleted: number;
}

/**
 * 501 — der Lösch-Pfad ist serverseitig (noch) nicht gebaut. Eigener Fehlertyp,
 * damit die UI ehrlich „kommt noch" zeigt statt eines generischen Fehlers.
 */
export class PrivacyNotYetError extends Error {
  constructor(
    public readonly target: PrivacyTarget,
    message = 'Kommt noch — serverseitig noch nicht gebaut.',
  ) {
    super(message);
    this.name = 'PrivacyNotYetError';
  }
}

/** Token-Header wie `api/skills.ts` — nur setzen, wenn ein Token konfiguriert ist. */
function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

/** Defensiver Parse einer Store-Zeile (kaputte Felder → ehrliche Defaults, nie Fantasie). */
function toStoreInfo(raw: unknown): PrivacyStoreInfo {
  const r = (raw ?? {}) as Record<string, unknown>;
  return {
    enabled: r.enabled === true,
    path: typeof r.path === 'string' ? r.path : '',
    exists: r.exists === true,
    sizeBytes: typeof r.sizeBytes === 'number' ? r.sizeBytes : 0,
    entries: typeof r.entries === 'number' ? r.entries : null,
  };
}

function toSummary(raw: unknown): PrivacySummary {
  if (!raw || typeof raw !== 'object') throw new Error('Privacy-Antwort unlesbar.');
  const r = raw as Record<string, unknown>;
  const voice = (r.voice ?? {}) as Record<string, unknown>;
  const sanitize = (r.sanitize ?? {}) as Record<string, unknown>;
  const diary = (r.diary ?? {}) as Record<string, unknown>;
  return {
    voice: {
      engine: typeof voice.engine === 'string' ? voice.engine : 'unbekannt',
      cloud: voice.cloud === true,
    },
    sanitize: { enabled: sanitize.enabled === true },
    memory: toStoreInfo(r.memory),
    episodic: toStoreInfo(r.episodic),
    diary: {
      enabled: diary.enabled === true,
      dir: typeof diary.dir === 'string' ? diary.dir : '',
      days: typeof diary.days === 'number' ? diary.days : 0,
    },
  };
}

/** `GET /api/v1/privacy/summary` → {@link PrivacySummary}. Wirft bei 401/!ok/kaputtem Body. */
export async function fetchPrivacySummary(signal?: AbortSignal): Promise<PrivacySummary> {
  const res = await fetch(`${API_BASE}/api/v1/privacy/summary`, {
    headers: authHeaders(),
    signal,
  });
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSummary(await res.json());
}

/**
 * `DELETE /api/v1/privacy/{target}` → die bewiesene Lösch-Zahl.
 *  - 501 ⇒ {@link PrivacyNotYetError} („kommt noch", ehrlich),
 *  - 401 (Auth-Wand) / 5xx ⇒ Error.
 */
export async function deletePrivacyData(
  target: PrivacyTarget,
  signal?: AbortSignal,
): Promise<PrivacyDeleteResult> {
  const res = await fetch(`${API_BASE}/api/v1/privacy/${target}`, {
    method: 'DELETE',
    headers: authHeaders(),
    signal,
  });
  if (res.status === 501) throw new PrivacyNotYetError(target);
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  const body = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  return {
    target,
    deleted: typeof body.deleted === 'number' ? body.deleted : 0,
  };
}

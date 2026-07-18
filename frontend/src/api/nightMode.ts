import { API_BASE, TOKEN } from './config';

/**
 * Typisierter Client für den Nachtmodus-Settings-Rand (Scheibe 3 von 3, FE-Seite
 * von Scheibe 2), Spiegel von `de.hoshi.web.NightModeController` — PRO GERÄT
 * (Andi-Entscheidung 2026-07-12, `vault/tracks/prep/PREP-nachtmodus.md`):
 *  - `GET /api/v1/night-mode/devices` → ALLE konfigurierten UND aktuell
 *    verbundenen Geräte (Union), je als flaches {@link NightModeDevice}.
 *  - `GET /api/v1/night-mode/{satelliteId}` → EIN Gerät; unkonfiguriert ⇒ der
 *    Server-Default (`enabled=false`, `mode=SCHEDULE`, `22:00`–`07:00`, `dim=0.3`),
 *    KEIN 404 — das macht ein Gerät, das noch nie verbunden war, trotzdem
 *    vorab konfigurierbar (manuelles Targeting im FE, s. `NightModeSection`).
 *  - `PUT /api/v1/night-mode/{satelliteId}` Body {@link NightModeConfig} → der
 *    AUTORITATIVE neue Zustand (als {@link NightModeDevice}); 400 (ungültige
 *    Felder) ⇒ {@link NightModeValidationError}, 409 (Deploy-Decke zu) ⇒
 *    {@link NightModeLockedError}, 500 (Persist fehlgeschlagen) ⇒ Error.
 *
 * Wire-Format ist FLACH (kein verschachteltes `config`-Objekt) — exakt wie
 * `NightModeDeviceView`/`NightModeConfigRequest` im Backend serialisieren.
 *
 * Auth + Base-URL exakt wie `api/skills.ts`/`api/weatherLocation.ts`: Token als
 * `X-Hoshi-Token`-Header, Pfade relativ zu `API_BASE`.
 */

export type NightModeMode = 'SCHEDULE' | 'ALWAYS';

/** Die editierbaren Felder — exakt der PUT-Body (`NightModeConfigRequest`). */
export interface NightModeConfig {
  enabled: boolean;
  mode: NightModeMode;
  /** `HH:mm`, nur bei `mode === 'SCHEDULE'` wirksam. Rollover über Mitternacht erlaubt. */
  from: string;
  /** `HH:mm`, s. {@link from}. */
  to: string;
  /** Dimm-Stärke `0.0..1.0`. */
  dim: number;
}

/** Ein Geräte-Eintrag — das Wire-Format von GET .../devices, GET .../{id}, PUT .../{id}. */
export interface NightModeDevice extends NightModeConfig {
  satelliteId: string;
  /** Live aus der Downlink-Registry — verbunden JETZT, unabhängig vom Store. */
  connected: boolean;
  /** Die Deploy-Decke (`HOSHI_NIGHT_MODE_ENABLED`) — `false` ⇒ ein PUT greift nicht (409). */
  nightModeEnabled: boolean;
}

const MODES: readonly NightModeMode[] = ['SCHEDULE', 'ALWAYS'];

/** 409: Nachtmodus ist beim Deploy deaktiviert — der PUT greift nicht (kein Store-Write). */
export class NightModeLockedError extends Error {
  constructor(
    public readonly satelliteId: string,
    message = 'Beim Deploy deaktiviert; greift nicht.',
  ) {
    super(message);
    this.name = 'NightModeLockedError';
  }
}

/** 400: ein Feld war ungültig (mode/from/to/dim) — die Server-Meldung ist die Wahrheit. */
export class NightModeValidationError extends Error {
  constructor(
    public readonly satelliteId: string,
    message = 'Ungültige Eingabe.',
  ) {
    super(message);
    this.name = 'NightModeValidationError';
  }
}

/** Token-Header wie `api/skills.ts` — nur setzen, wenn ein Token konfiguriert ist. */
function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

/** Defensiver Parse eines Wire-Eintrags (kaputte/unbekannte Felder → sichere Defaults). */
function toDevice(raw: unknown): NightModeDevice | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.satelliteId !== 'string' || !r.satelliteId) return null;
  const mode: NightModeMode = MODES.includes(r.mode as NightModeMode) ? (r.mode as NightModeMode) : 'SCHEDULE';
  return {
    satelliteId: r.satelliteId,
    connected: r.connected === true,
    enabled: r.enabled === true,
    mode,
    from: typeof r.from === 'string' && r.from ? r.from : '22:00',
    to: typeof r.to === 'string' && r.to ? r.to : '07:00',
    dim: typeof r.dim === 'number' && Number.isFinite(r.dim) ? Math.min(1, Math.max(0, r.dim)) : 0.3,
    nightModeEnabled: r.nightModeEnabled === true,
  };
}

/** Liest `message` aus einem Fehler-Body (`NightModeError`), sonst der ehrliche Fallback. */
async function readErrorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const body = (await res.json()) as { message?: unknown };
    return typeof body.message === 'string' && body.message ? body.message : fallback;
  } catch {
    return fallback;
  }
}

/** `GET /api/v1/night-mode/devices` → `NightModeDevice[]`. Wirft bei 401/!ok/kaputtem Body. */
export async function fetchNightModeDevices(signal?: AbortSignal): Promise<NightModeDevice[]> {
  const res = await fetch(`${API_BASE}/api/v1/night-mode/devices`, {
    headers: authHeaders(),
    signal,
  });
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  const body: unknown = await res.json();
  if (!Array.isArray(body)) throw new Error('Nachtmodus-Geräteliste ist kein Array.');
  return body.map(toDevice).filter((d): d is NightModeDevice => d !== null);
}

/**
 * `GET /api/v1/night-mode/{satelliteId}` → EIN Gerät. Nie 404 (der Server liefert
 * unkonfiguriert einen Default-Zustand) — genau das macht das manuelle Targeting
 * einer noch nie verbundenen `satelliteId` möglich.
 */
export async function fetchNightModeDevice(
  satelliteId: string,
  signal?: AbortSignal,
): Promise<NightModeDevice> {
  const res = await fetch(`${API_BASE}/api/v1/night-mode/${encodeURIComponent(satelliteId)}`, {
    headers: authHeaders(),
    signal,
  });
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  const device = toDevice(await res.json());
  if (!device) throw new Error('Nachtmodus-Antwort unlesbar.');
  return device;
}

/**
 * `PUT /api/v1/night-mode/{satelliteId}` mit Body {@link NightModeConfig}. Gibt den
 * AUTORITATIVEN neuen Zustand zurück (das FE übernimmt ihn, statt optimistisch zu raten).
 *  - 400 (ungültige Felder) ⇒ {@link NightModeValidationError},
 *  - 409 (Deploy-Decke zu) ⇒ {@link NightModeLockedError} (kein Fehler-UI — „serverseitig noch aus"),
 *  - 401 (Auth-Wand) / 500 (Persist fehlgeschlagen) / sonst ⇒ Error.
 */
export async function saveNightModeDevice(
  satelliteId: string,
  config: NightModeConfig,
  signal?: AbortSignal,
): Promise<NightModeDevice> {
  const res = await fetch(`${API_BASE}/api/v1/night-mode/${encodeURIComponent(satelliteId)}`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(config),
    signal,
  });
  if (res.status === 409) {
    throw new NightModeLockedError(
      satelliteId,
      await readErrorMessage(res, 'Beim Deploy deaktiviert; greift nicht.'),
    );
  }
  if (res.status === 400) {
    throw new NightModeValidationError(satelliteId, await readErrorMessage(res, 'Ungültige Eingabe.'));
  }
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  const device = toDevice(await res.json());
  if (!device) throw new Error('Nachtmodus-Antwort unlesbar.');
  return device;
}

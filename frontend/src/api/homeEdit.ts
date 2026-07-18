import { API_BASE, TOKEN } from './config';

/**
 * Typisierter Client für den SCHREIB-Rand (Scheibe 2 des Geräte-Zuordnungs-
 * Konzepts, `.orch-bus/ctx/cowork-research-2026-07-15/11-geraete-zuordnung-konzept.md`),
 * Spiegel von `de.hoshi.web.HomeEditController`. „HA bleibt die eine Wahrheit,
 * Hoshi wird ihr Editor" — dieser Client ZUWEISEN: eine Entity bekommt eine Area.
 *
 *  - `GET /api/v1/home/edit/status` → `{editEnabled}`: die EINE Quelle, ob das FE
 *    Picker rendern darf. **Fail-closed**: jeder Fehler ⇒ `false` (kein Picker,
 *    nie ein Editor, den der Server gar nicht scharf geschaltet hat).
 *  - `PUT /api/v1/home/entity/{entityId}/area` Body `{areaId}` → der autoritative
 *    neue Zuordnungs-Stand. **KEIN optimistisches UI**: der Aufrufer lädt nach
 *    Erfolg die Registry neu, statt die Karte selbst zu verschieben.
 *    - 409 (Flag zu) ⇒ {@link HomeEditLockedError},
 *    - 400 (leere/unbekannte Area) ⇒ {@link HomeEditValidationError},
 *    - 401 (Auth-Wand) / 502 (HA nicht bestätigt) / sonst ⇒ Error.
 *
 * Auth + Base-URL exakt wie `api/nightMode.ts`: Token als `X-Hoshi-Token`-Header.
 */

/** Die neue Zuordnung, wie der Server sie autoritativ zurückgibt. */
export interface AreaAssignmentResult {
  entityId: string;
  areaId: string;
}

/** 409: die Geräte-Zuordnung ist beim Deploy deaktiviert (`HOSHI_HOME_EDIT_ENABLED`). */
export class HomeEditLockedError extends Error {
  constructor(
    public readonly entityId: string,
    message = 'Geräte-Zuordnung ist beim Deploy deaktiviert.',
  ) {
    super(message);
    this.name = 'HomeEditLockedError';
  }
}

/** 400: die Area war leer oder unbekannt — die Server-Meldung ist die Wahrheit. */
export class HomeEditValidationError extends Error {
  constructor(
    public readonly entityId: string,
    message = 'Ungültige Area.',
  ) {
    super(message);
    this.name = 'HomeEditValidationError';
  }
}

/** Token-Header wie `api/nightMode.ts` — nur setzen, wenn ein Token konfiguriert ist. */
function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

/** Liest `message` aus einem Fehler-Body (`SettingsError`), sonst der ehrliche Fallback. */
async function readErrorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const body = (await res.json()) as { message?: unknown };
    return typeof body.message === 'string' && body.message ? body.message : fallback;
  } catch {
    return fallback;
  }
}

/**
 * `GET /api/v1/home/edit/status` → `editEnabled`. Fail-closed: 401/!ok/Netzfehler/
 * kaputter Body ⇒ `false` (kein Picker). Nur ein echtes `{editEnabled:true}` schaltet ihn frei.
 */
export async function fetchHomeEditStatus(signal?: AbortSignal): Promise<boolean> {
  try {
    const res = await fetch(`${API_BASE}/api/v1/home/edit/status`, { headers: authHeaders(), signal });
    if (!res.ok) return false;
    const body: unknown = await res.json().catch(() => null);
    return !!(body && typeof body === 'object' && (body as Record<string, unknown>).editEnabled === true);
  } catch {
    return false;
  }
}

/**
 * `PUT /api/v1/home/entity/{entityId}/area` mit Body `{areaId}`. Gibt die
 * autoritative neue Zuordnung zurück; Fehler s. Modul-KDoc.
 */
export async function assignEntityArea(
  entityId: string,
  areaId: string,
  signal?: AbortSignal,
): Promise<AreaAssignmentResult> {
  const res = await fetch(`${API_BASE}/api/v1/home/entity/${encodeURIComponent(entityId)}/area`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ areaId }),
    signal,
  });
  if (res.status === 409) {
    throw new HomeEditLockedError(entityId, await readErrorMessage(res, 'Geräte-Zuordnung ist beim Deploy deaktiviert.'));
  }
  if (res.status === 400) {
    throw new HomeEditValidationError(entityId, await readErrorMessage(res, 'Ungültige Area.'));
  }
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(await readErrorMessage(res, `Backend antwortete HTTP ${res.status}`));
  const body = (await res.json().catch(() => null)) as Record<string, unknown> | null;
  const resultEntityId = typeof body?.entityId === 'string' && body.entityId ? body.entityId : entityId;
  const resultAreaId = typeof body?.areaId === 'string' && body.areaId ? body.areaId : areaId;
  return { entityId: resultEntityId, areaId: resultAreaId };
}

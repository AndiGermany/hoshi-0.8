import { API_BASE, TOKEN } from './config';
import { getActiveUiLanguage } from '../i18n/activeLanguageStore';
import { resolveUiStrings } from '../i18n/catalogs';

/**
 * Typisierter Client für den Quellen-Settings-Rand (aktive Nachrichten-Quellen
 * Tagesschau/heise/Golem), Spiegel von `de.hoshi.web.NewsSourcesSettingsController`:
 *  - `GET /api/v1/settings/news-sources` → {@link NewsSourcesSetting}
 *  - `PUT /api/v1/settings/news-sources` Body `{aktiv}` → autoritativer neuer
 *    Zustand; jede unbekannte Quellen-Id ⇒ HTTP 422 ⇒ {@link UnknownNewsSourceError}.
 *
 * Ids sind die rohen `CurrentAffairsSourceId`-Namen (z.B. `"TAGESSCHAU"`) —
 * dieselbe rohe Form wie `source` in `useCurrentAffairs.ts` (Proper Noun,
 * nie übersetzt).
 *
 * Auth + Base-URL exakt wie `api/languageSettings.ts`.
 */

export interface NewsSourcesSetting {
  aktiv: string[];
  verfuegbar: string[];
}

/** 422: mindestens eine der gewünschten Quellen-Ids ist unbekannt. */
export class UnknownNewsSourceError extends Error {
  constructor(
    public readonly requested: string[],
    message = 'Unbekannte Quelle.',
  ) {
    super(message);
    this.name = 'UnknownNewsSourceError';
  }
}

function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

function strings(raw: unknown): string[] {
  return Array.isArray(raw) ? raw.filter((s): s is string => typeof s === 'string') : [];
}

function toSetting(raw: unknown): NewsSourcesSetting {
  if (!raw || typeof raw !== 'object') throw new Error('Quellen-Antwort unlesbar.');
  const r = raw as Record<string, unknown>;
  if (!Array.isArray(r.aktiv) || !Array.isArray(r.verfuegbar)) throw new Error('Quellen-Antwort unlesbar.');
  return { aktiv: strings(r.aktiv), verfuegbar: strings(r.verfuegbar) };
}

/** `GET /api/v1/settings/news-sources`. Wirft bei 401/!ok/kaputtem Body. */
export async function fetchNewsSources(signal?: AbortSignal): Promise<NewsSourcesSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/news-sources`, {
    headers: authHeaders(),
    signal,
  });
  if (res.status === 401) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.authWall);
  if (!res.ok) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.httpStatus(res.status));
  return toSetting(await res.json());
}

/**
 * `PUT /api/v1/settings/news-sources` mit Body `{aktiv}`. Gibt den
 * AUTORITATIVEN neuen Zustand zurück (Readback statt Behauptung). Ein leeres
 * `aktiv` ist ein gültiger Wunsch (bewusst keine Quelle aktiv).
 *  - 422 (unbekannte Id) ⇒ {@link UnknownNewsSourceError},
 *  - 401 / 5xx ⇒ Error.
 */
export async function saveNewsSources(aktiv: string[], signal?: AbortSignal): Promise<NewsSourcesSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/news-sources`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ aktiv }),
    signal,
  });
  if (res.status === 422) throw new UnknownNewsSourceError(aktiv);
  if (res.status === 401) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.authWall);
  if (!res.ok) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.httpStatus(res.status));
  return toSetting(await res.json());
}

import { API_BASE, TOKEN } from './config';
import { getActiveUiLanguage } from '../i18n/activeLanguageStore';
import { resolveUiStrings } from '../i18n/catalogs';

/**
 * Typisierter Client für den Extended-Think-Settings-Rand (S2, Andis Auftrag
 * 26.07: „die Eskalations-Stufe hat KEIN UI-Element" — vorher nur Backend),
 * Spiegel von `de.hoshi.web.ExtendedThinkController`:
 *  - `GET /api/v1/settings/extended-think` → {@link ExtendedThinkSetting}
 *  - `PUT /api/v1/settings/extended-think` Body `{mode}` → autoritativer neuer
 *    Zustand (Readback); unbekannte Stufe ⇒ 400 ⇒ {@link UnknownEscalationModeError};
 *    beim Deploy deaktiviert ⇒ 409 ⇒ {@link EscalationLockedError}.
 *
 * Auth + Base-URL exakt wie `api/lookupModel.ts`.
 */

/** Die vier Stufen — exakt die Enum-Namen von `EscalationMode` (core-domain), Online-Grad aufsteigend. */
export type EscalationModeWire = 'AUS' | 'OFFLINE' | 'ERST_FRAGEN' | 'AUTOMATISCH';

/** Reihenfolge nach Online-Grad (Aus → Offline → Erst fragen → Automatisch) — die UI rendert in dieser Reihenfolge. */
export const ESCALATION_MODES: readonly EscalationModeWire[] = [
  'AUS',
  'OFFLINE',
  'ERST_FRAGEN',
  'AUTOMATISCH',
];

export interface ExtendedThinkSetting {
  /** Der Laufzeit-Store-Wert (Default ERST_FRAGEN bei offener Decke). */
  mode: EscalationModeWire;
  /** Ist die Deploy-Zeit-Decke `HOSHI_EXTENDED_THINK_ENABLED` offen? */
  ceilingOpen: boolean;
  /** `!ceilingOpen` — die Auswahl ist gesperrt (Server-Wahrheit, kein Fake-Enabled). */
  locked: boolean;
  /** Was die Pipeline wirklich fährt (Decke zu ⇒ immer "AUS"). */
  effectiveMode: EscalationModeWire;
}

function isEscalationMode(value: unknown): value is EscalationModeWire {
  return typeof value === 'string' && (ESCALATION_MODES as readonly string[]).includes(value);
}

/** 400: die gewählte Stufe ist keine der vier bekannten Stufen. */
export class UnknownEscalationModeError extends Error {
  constructor(
    public readonly mode: string,
    message = 'Unbekannte Stufe.',
  ) {
    super(message);
    this.name = 'UnknownEscalationModeError';
  }
}

/** 409: Extended Think ist beim Deploy deaktiviert — die Wahl greift nicht (kein Store-Write). */
export class EscalationLockedError extends Error {
  constructor(message = 'Beim Deploy deaktiviert; greift nicht.') {
    super(message);
    this.name = 'EscalationLockedError';
  }
}

function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

/** Defensiver Parse der Wire-Antwort (kaputte/unbekannte Felder → Error statt Rate-Werte). */
function toSetting(raw: unknown): ExtendedThinkSetting {
  if (!raw || typeof raw !== 'object') throw new Error('Eskalations-Antwort unlesbar.');
  const r = raw as Record<string, unknown>;
  if (!isEscalationMode(r.mode) || !isEscalationMode(r.effectiveMode)) {
    throw new Error('Eskalations-Antwort unlesbar.');
  }
  return {
    mode: r.mode,
    ceilingOpen: r.ceilingOpen === true,
    locked: r.locked === true,
    effectiveMode: r.effectiveMode,
  };
}

/** `GET /api/v1/settings/extended-think`. Wirft bei 401/!ok/kaputtem Body. */
export async function fetchExtendedThink(signal?: AbortSignal): Promise<ExtendedThinkSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/extended-think`, {
    headers: authHeaders(),
    signal,
  });
  if (res.status === 401) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.authWall);
  if (!res.ok) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.httpStatus(res.status));
  return toSetting(await res.json());
}

/**
 * `PUT /api/v1/settings/extended-think` mit Body `{mode}`. Gibt den
 * AUTORITATIVEN neuen Zustand zurück (Readback, kein optimistisches Umschalten).
 *  - 400 (unbekannte Stufe) ⇒ {@link UnknownEscalationModeError},
 *  - 409 (Decke zu) ⇒ {@link EscalationLockedError},
 *  - 401 / 5xx ⇒ Error.
 */
export async function saveExtendedThinkMode(
  mode: EscalationModeWire,
  signal?: AbortSignal,
): Promise<ExtendedThinkSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/extended-think`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ mode }),
    signal,
  });
  if (res.status === 400) throw new UnknownEscalationModeError(mode);
  if (res.status === 409) throw new EscalationLockedError();
  if (res.status === 401) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.authWall);
  if (!res.ok) throw new Error(resolveUiStrings(getActiveUiLanguage()).apiErrors.httpStatus(res.status));
  return toSetting(await res.json());
}

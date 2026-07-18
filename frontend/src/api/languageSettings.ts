import { API_BASE, TOKEN } from './config';

/**
 * Typisierter Client für den Sprach-Settings-Rand (Andi-Auftrag 2026-07-20:
 * „Hoshi versteht/denkt/spricht wählbar in DE/EN/ES/FR/IT"), Spiegel von
 * `de.hoshi.web.LanguageSettingsController`:
 *  - `GET /api/v1/settings/language` → {@link LanguageSetting}
 *  - `PUT /api/v1/settings/language` Body `{code}` → autoritativer neuer
 *    Zustand; unbekannter code ⇒ HTTP 422 ⇒ {@link UnknownLanguageError}.
 *
 * **Bewusst getrennt von der lokalen `useSettings`-Sprachwahl** (auto/de/en,
 * die PRO CHAT-/VOICE-REQUEST mitfließt, s. `api/chat.ts`/`api/voice.ts`): dieser
 * Client spricht den SERVER-DEFAULT an (greift für Ränder ohne eigene Wahl, z.B.
 * den Voice-PE-Satelliten) — 5 Sprachen (DE/EN/ES/FR/IT), Deutsch Tier 1, der
 * Rest Beta (Sprachpaket-Kern, s. `LanguagePackRegistry`).
 *
 * Auth + Base-URL exakt wie `api/lookupModel.ts`.
 */

export interface LanguageOption {
  code: string;
  endonym: string;
  /** true für jede Sprache außer Deutsch — Tier-1-Reichweite ist noch im Aufbau. */
  beta: boolean;
}

export interface LanguageSetting {
  aktiv: string;
  sprachen: LanguageOption[];
  /** Ehrlicher Hinweis, wenn die aktive Sprache nicht Deutsch ist (Smart-Home bleibt DE). */
  smartHomeHinweis: string | null;
}

/** 422: der gewählte Sprach-Code ist unbekannt. */
export class UnknownLanguageError extends Error {
  constructor(
    public readonly code: string,
    message = 'Unbekannte Sprache.',
  ) {
    super(message);
    this.name = 'UnknownLanguageError';
  }
}

function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

function toSetting(raw: unknown): LanguageSetting {
  if (!raw || typeof raw !== 'object') throw new Error('Sprach-Antwort unlesbar.');
  const r = raw as Record<string, unknown>;
  if (typeof r.aktiv !== 'string' || !Array.isArray(r.sprachen)) {
    throw new Error('Sprach-Antwort unlesbar.');
  }
  const sprachen: LanguageOption[] = r.sprachen
    .map((s): LanguageOption | null => {
      if (!s || typeof s !== 'object') return null;
      const ss = s as Record<string, unknown>;
      if (typeof ss.code !== 'string' || typeof ss.endonym !== 'string') return null;
      return { code: ss.code, endonym: ss.endonym, beta: ss.beta === true };
    })
    .filter((s): s is LanguageOption => s !== null);
  return {
    aktiv: r.aktiv,
    sprachen,
    smartHomeHinweis: typeof r.smartHomeHinweis === 'string' ? r.smartHomeHinweis : null,
  };
}

/** `GET /api/v1/settings/language`. Wirft bei 401/!ok/kaputtem Body. */
export async function fetchLanguageSettings(signal?: AbortSignal): Promise<LanguageSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/language`, {
    headers: authHeaders(),
    signal,
  });
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSetting(await res.json());
}

/**
 * `PUT /api/v1/settings/language` mit Body `{code}`. Gibt den AUTORITATIVEN
 * neuen Zustand zurück (Readback statt Behauptung).
 *  - 422 (unbekannter code) ⇒ {@link UnknownLanguageError},
 *  - 401 / 5xx ⇒ Error.
 */
export async function saveLanguageSetting(code: string, signal?: AbortSignal): Promise<LanguageSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/language`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ code }),
    signal,
  });
  if (res.status === 422) throw new UnknownLanguageError(code);
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSetting(await res.json());
}

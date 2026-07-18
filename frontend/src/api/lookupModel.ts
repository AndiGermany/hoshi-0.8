import { API_BASE, TOKEN } from './config';

/**
 * Typisierter Client für den Lookup-Modell-Settings-Rand (Andi-Video-Auftrag:
 * „das Lookup-Sprachmodell in den Einstellungen wählbar, zur Laufzeit"),
 * Spiegel von `de.hoshi.web.LookupModelController`:
 *  - `GET /api/v1/settings/lookup-model` → {@link LookupModelSetting}
 *  - `PUT /api/v1/settings/lookup-model` Body `{id}` → autoritativer neuer
 *    Zustand; unbekannte id ⇒ HTTP 422 ⇒ {@link UnknownLookupModelError}.
 *
 * Auth + Base-URL exakt wie `api/skills.ts`.
 */

export interface LookupModelOption {
  id: string;
  label: string;
  /** ca.-Kosten EINES typischen Nachschlags, in Cent. */
  centsProLookup: number;
}

export interface LookupModelSetting {
  aktiv: string;
  modelle: LookupModelOption[];
}

/** 422: die gewählte Modell-Id ist nicht im Katalog. */
export class UnknownLookupModelError extends Error {
  constructor(
    public readonly id: string,
    message = 'Unbekanntes Modell.',
  ) {
    super(message);
    this.name = 'UnknownLookupModelError';
  }
}

function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

function toSetting(raw: unknown): LookupModelSetting {
  if (!raw || typeof raw !== 'object') throw new Error('Lookup-Modell-Antwort unlesbar.');
  const r = raw as Record<string, unknown>;
  if (typeof r.aktiv !== 'string' || !Array.isArray(r.modelle)) {
    throw new Error('Lookup-Modell-Antwort unlesbar.');
  }
  const modelle: LookupModelOption[] = r.modelle
    .map((m): LookupModelOption | null => {
      if (!m || typeof m !== 'object') return null;
      const mm = m as Record<string, unknown>;
      if (typeof mm.id !== 'string' || typeof mm.label !== 'string') return null;
      return {
        id: mm.id,
        label: mm.label,
        centsProLookup: typeof mm.centsProLookup === 'number' ? mm.centsProLookup : 0,
      };
    })
    .filter((m): m is LookupModelOption => m !== null);
  return { aktiv: r.aktiv, modelle };
}

/** `GET /api/v1/settings/lookup-model`. Wirft bei 401/!ok/kaputtem Body. */
export async function fetchLookupModel(signal?: AbortSignal): Promise<LookupModelSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/lookup-model`, {
    headers: authHeaders(),
    signal,
  });
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSetting(await res.json());
}

/**
 * `PUT /api/v1/settings/lookup-model` mit Body `{id}`. Gibt den AUTORITATIVEN
 * neuen Zustand zurück (Readback statt Behauptung).
 *  - 422 (unbekannte id) ⇒ {@link UnknownLookupModelError},
 *  - 401 / 5xx ⇒ Error.
 */
export async function saveLookupModel(id: string, signal?: AbortSignal): Promise<LookupModelSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/lookup-model`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ id }),
    signal,
  });
  if (res.status === 422) throw new UnknownLookupModelError(id);
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSetting(await res.json());
}

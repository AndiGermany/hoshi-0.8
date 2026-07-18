import { API_BASE, TOKEN } from './config';

/**
 * Typisierter Client für den Brain-Modell-Settings-Rand (Andi-Auftrag „Brain
 * (LLM)"-Sektion), Spiegel von `de.hoshi.web.BrainSettingsController`:
 *  - `GET /api/v1/settings/brain` → {@link BrainSetting} — `aktiv` ist LIVE
 *    aus dem Sidecar-`/health` gelesen (kein behaupteter Cache-Wert).
 *  - `PUT /api/v1/settings/brain` Body `{id}` → 200 heißt „Wechsel
 *    ANGENOMMEN", NICHT „fertig" (dauert 60-120s) ⇒ unbekannte id ⇒ 422 ⇒
 *    {@link UnknownBrainModelError}; Sidecar kennt `/switch-model` noch nicht
 *    oder ist nicht erreichbar ⇒ 502 ⇒ {@link BrainSwitchUnavailableError}.
 *
 * Auth + Base-URL exakt wie `api/skills.ts`.
 */

export interface BrainModelOption {
  id: string;
  label: string;
  repo: string;
}

export interface BrainSetting {
  aktiv: string;
  modelle: BrainModelOption[];
  status: string;
}

/** 422: die gewählte Modell-Id ist nicht in der Zwei-Modell-Whitelist. */
export class UnknownBrainModelError extends Error {
  constructor(
    public readonly id: string,
    message = 'Unbekanntes Brain-Modell.',
  ) {
    super(message);
    this.name = 'UnknownBrainModelError';
  }
}

/** 502: der Brain-Sidecar kennt `/switch-model` noch nicht oder ist nicht erreichbar. */
export class BrainSwitchUnavailableError extends Error {
  constructor(message = 'Brain-Sidecar kann noch kein Umschalten / nicht erreichbar.') {
    super(message);
    this.name = 'BrainSwitchUnavailableError';
  }
}

function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

function toSetting(raw: unknown): BrainSetting {
  if (!raw || typeof raw !== 'object') throw new Error('Brain-Settings-Antwort unlesbar.');
  const r = raw as Record<string, unknown>;
  if (typeof r.aktiv !== 'string' || !Array.isArray(r.modelle) || typeof r.status !== 'string') {
    throw new Error('Brain-Settings-Antwort unlesbar.');
  }
  const modelle: BrainModelOption[] = r.modelle
    .map((m): BrainModelOption | null => {
      if (!m || typeof m !== 'object') return null;
      const mm = m as Record<string, unknown>;
      if (typeof mm.id !== 'string' || typeof mm.label !== 'string' || typeof mm.repo !== 'string') return null;
      return { id: mm.id, label: mm.label, repo: mm.repo };
    })
    .filter((m): m is BrainModelOption => m !== null);
  return { aktiv: r.aktiv, modelle, status: r.status };
}

/** `GET /api/v1/settings/brain`. Wirft bei 401/!ok/kaputtem Body. */
export async function fetchBrainSettings(signal?: AbortSignal): Promise<BrainSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/brain`, {
    headers: authHeaders(),
    signal,
  });
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSetting(await res.json());
}

/**
 * `PUT /api/v1/settings/brain` mit Body `{id}`. Gibt den GERADE gemessenen
 * Zustand zurück — KEIN optimistisches UI: direkt nach dem PUT zeigt das oft
 * noch das alte Modell/`status=loading` (der Wechsel dauert 60-120s). Das FE
 * pollt danach selbst weiter (s. `BrainModelSection`).
 *  - 422 (unbekannte id) ⇒ {@link UnknownBrainModelError},
 *  - 502 (Sidecar kennt `/switch-model` noch nicht / nicht erreichbar) ⇒
 *    {@link BrainSwitchUnavailableError},
 *  - 401 / 5xx ⇒ Error.
 */
export async function saveBrainModel(id: string, signal?: AbortSignal): Promise<BrainSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/brain`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ id }),
    signal,
  });
  if (res.status === 422) throw new UnknownBrainModelError(id);
  if (res.status === 502) throw new BrainSwitchUnavailableError();
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSetting(await res.json());
}

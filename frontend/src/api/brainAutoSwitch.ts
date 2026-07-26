import { API_BASE, TOKEN } from './config';

/**
 * Typisierter Client für das `brainAutoSwitch`-Settings (Andi-Auftrag „12B für
 * Chat, e4b für Voice", 2026-07-26), Spiegel von
 * `de.hoshi.web.BrainAutoSwitchController`:
 *  - `GET /api/v1/settings/brain-auto-switch` → {@link BrainAutoSwitchSetting}.
 *  - `PUT /api/v1/settings/brain-auto-switch` Body `{enabled}` → der neue Zustand.
 *
 * Auth + Base-URL exakt wie `api/brainSettings.ts`.
 */

export interface BrainAutoSwitchSetting {
  enabled: boolean;
}

function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

function toSetting(raw: unknown): BrainAutoSwitchSetting {
  if (!raw || typeof raw !== 'object') throw new Error('Auto-Switch-Antwort unlesbar.');
  const r = raw as Record<string, unknown>;
  if (typeof r.enabled !== 'boolean') throw new Error('Auto-Switch-Antwort unlesbar.');
  return { enabled: r.enabled };
}

/** `GET /api/v1/settings/brain-auto-switch`. Wirft bei 401/!ok/kaputtem Body. */
export async function fetchBrainAutoSwitch(signal?: AbortSignal): Promise<BrainAutoSwitchSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/brain-auto-switch`, {
    headers: authHeaders(),
    signal,
  });
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSetting(await res.json());
}

/**
 * `PUT /api/v1/settings/brain-auto-switch` mit Body `{enabled}`. Gibt den vom
 * Server bestätigten (persistierten) Zustand zurück.
 *  - 401 / 5xx ⇒ Error.
 */
export async function saveBrainAutoSwitch(enabled: boolean, signal?: AbortSignal): Promise<BrainAutoSwitchSetting> {
  const res = await fetch(`${API_BASE}/api/v1/settings/brain-auto-switch`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ enabled }),
    signal,
  });
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  return toSetting(await res.json());
}

import { API_BASE, TOKEN } from './config';
import type { Skill, SkillTier } from './types';

/**
 * Typisierter Client für den Skills-Settings-Rand (S2.3), Spiegel von
 * `de.hoshi.web.SettingsController`:
 *  - `GET  /api/v1/settings/skills`      → {@link Skill}[]
 *  - `PUT  /api/v1/settings/skills/{id}` Body `{enabled}` → autoritativer neuer Zustand;
 *    Decke zu ⇒ HTTP 409 ⇒ {@link SkillLockedError}; unbekannte id ⇒ 404.
 *
 * Auth + Base-URL exakt wie `api/chat.ts`: Token als `X-Hoshi-Token`-Header (aus
 * `config.TOKEN`/VITE_TOKEN; leer ⇒ weggelassen → die Auth-Wand greift ehrlich mit
 * 401), Pfade relativ zu `API_BASE` (VITE_API_BASE; leer ⇒ same-origin via Dev-Proxy).
 */

const TIERS: readonly SkillTier[] = ['LOCAL', 'EGRESS'];

/** Token-Header wie `api/chat.ts` — nur setzen, wenn ein Token konfiguriert ist. */
function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  const headers: Record<string, string> = { Accept: 'application/json', ...extra };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
  return headers;
}

/**
 * Typisierter Fehler für das PUT-409: die Decke ist beim Deploy zu (`locked`) — der
 * Toggle greift nicht. Der Hook fängt das gezielt, um die UI NICHT umzuklappen,
 * sondern die Zeile ehrlich als gesperrt zu markieren.
 */
export class SkillLockedError extends Error {
  constructor(
    public readonly id: string,
    message = 'Beim Deploy deaktiviert; greift nicht.',
  ) {
    super(message);
    this.name = 'SkillLockedError';
  }
}

/** Defensiver Parse einer Wire-Zeile (unbekannte/kaputte Felder → still verworfen). */
function toSkill(raw: unknown): Skill | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.id !== 'string') return null;
  const tier: SkillTier = TIERS.includes(r.tier as SkillTier) ? (r.tier as SkillTier) : 'LOCAL';
  return {
    id: r.id,
    labelDe: typeof r.labelDe === 'string' ? r.labelDe : r.id,
    labelEn: typeof r.labelEn === 'string' ? r.labelEn : r.id,
    tier,
    ceilingOpen: r.ceilingOpen === true,
    enabled: r.enabled === true,
    effective: r.effective === true,
    locked: r.locked === true,
  };
}

/** `GET /api/v1/settings/skills` → `Skill[]`. Wirft bei 401/!ok/kaputtem Body. */
export async function fetchSkills(signal?: AbortSignal): Promise<Skill[]> {
  const res = await fetch(`${API_BASE}/api/v1/settings/skills`, {
    headers: authHeaders(),
    signal,
  });
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  const body: unknown = await res.json();
  if (!Array.isArray(body)) throw new Error('Skills-Antwort ist kein Array.');
  return body.map(toSkill).filter((s): s is Skill => s !== null);
}

/**
 * `PUT /api/v1/settings/skills/{id}` mit Body `{enabled}`. Gibt den AUTORITATIVEN
 * neuen Skill-Zustand zurück (das FE merged den, statt optimistisch zu raten).
 *  - 409 (Decke zu) ⇒ {@link SkillLockedError} (kein UI-Flip),
 *  - 401 (Auth-Wand) / 404 (unbekannte id) / 5xx ⇒ Error.
 */
export async function setSkill(id: string, enabled: boolean, signal?: AbortSignal): Promise<Skill> {
  const res = await fetch(`${API_BASE}/api/v1/settings/skills/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ enabled }),
    signal,
  });
  if (res.status === 409) throw new SkillLockedError(id);
  if (res.status === 401) throw new Error('401 — Token fehlt oder ist ungültig (Auth-Wand).');
  if (res.status === 404) throw new Error(`Unbekannter Skill: ${id}`);
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  const updated = toSkill(await res.json());
  if (!updated) throw new Error('Skill-Antwort unlesbar.');
  return updated;
}

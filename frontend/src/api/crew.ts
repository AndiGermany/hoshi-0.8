import { API_BASE, TOKEN } from './config';

/**
 * Ein Crew-Mitglied der "Stellar Bloom" — Spiegel der Backend-`Crewmate`
 * (`de.hoshi.web.EasterEggController`).
 */
export interface CrewMember {
  name: string;
  role: string;
  mantra: string;
}

/** Defensiver Parse einer Wire-Zeile (unbekannte/kaputte Felder → still verworfen). */
function toMember(raw: unknown): CrewMember | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.name !== 'string') return null;
  return {
    name: r.name,
    role: typeof r.role === 'string' ? r.role : '',
    mantra: typeof r.mantra === 'string' ? r.mantra : '',
  };
}

/**
 * `GET /api/v1/crew` → `CrewMember[]`. Der Endpoint ist OEFFENTLICH (wie
 * `/api/health`) — ein Token ist nicht noetig; falls eins konfiguriert ist,
 * geht es trotzdem als `X-Hoshi-Token` mit (schadet nicht). Wirft bei !ok oder
 * kaputtem Body, damit das Easter-Egg ehrlich "konnte nicht laden" zeigen kann.
 */
export async function fetchCrew(signal?: AbortSignal): Promise<CrewMember[]> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;

  const res = await fetch(`${API_BASE}/api/v1/crew`, { headers, signal });
  if (!res.ok) throw new Error(`Backend antwortete HTTP ${res.status}`);
  const body: unknown = await res.json();
  if (!Array.isArray(body)) throw new Error('Crew-Antwort ist kein Array.');
  return body.map(toMember).filter((m): m is CrewMember => m !== null);
}

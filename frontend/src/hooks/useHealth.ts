import { useEffect, useRef, useState } from 'react';
import { API_BASE } from '../api/config';

/**
 * Ehrlichkeits-Achse:
 *  - `unknown` (grau) = noch nie geprüft / Prüfung läuft. NIE Fake-grün.
 *  - `up`      (grün) = `GET /api/health` → 200 und Body `{status:"up"}`.
 *  - `down`    (rot)  = alles andere (Netzfehler, 5xx, falscher Body).
 */
export type HealthState = 'unknown' | 'up' | 'down';

export interface Health {
  state: HealthState;
  lastChecked: number | null;
}

export function useHealth(intervalMs = 5000): Health {
  const [health, setHealth] = useState<Health>({ state: 'unknown', lastChecked: null });
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;

    const check = async (): Promise<void> => {
      let next: HealthState = 'down';
      try {
        const res = await fetch(`${API_BASE}/api/health`, {
          headers: { Accept: 'application/json' },
        });
        if (res.ok) {
          const body: unknown = await res.json().catch(() => null);
          const status = (body as { status?: unknown } | null)?.status;
          next = status === 'up' ? 'up' : 'down';
        }
      } catch {
        next = 'down';
      }
      if (aliveRef.current) setHealth({ state: next, lastChecked: Date.now() });
    };

    void check();
    const id = window.setInterval(() => void check(), intervalMs);
    return () => {
      aliveRef.current = false;
      window.clearInterval(id);
    };
  }, [intervalMs]);

  return health;
}

import { useEffect, useRef, useState } from 'react';
import { API_BASE, TOKEN } from '../api/config';

/**
 * Ops-Status (RAM-Druck + Sidecar-Gesundheit) aus `GET /api/v1/ops/status`.
 *
 * Ehrlichkeits-/Lärm-Achse (wie der Rest des FE):
 *  - Feature aus (`enabled:false`), 404/401/5xx oder Netzfehler → wir liefern
 *    `null`. Die {@link OpsStatusPill} rendert dann NICHTS (kein roter Fehler,
 *    kein Lärm — graceful hidden). Bei OFF-Flag ist das Feature unsichtbar.
 *  - Nur ein gültiger, eingeschalteter Status wird angezeigt.
 *
 * `parseOpsStatus`/`fetchOpsStatus` sind pure/seam-Funktionen (kein DOM, keine
 * Timer) → ohne Live-Backend unit-testbar (analog `api/sse.ts`). Der Hook
 * verdrahtet nur Polling + Cleanup (analog `useHealth`).
 */

export type OpsOverall = 'OK' | 'DEGRADED' | 'DOWN';
export type MemoryLevel = 'OK' | 'WARN' | 'CRITICAL' | 'UNKNOWN';
export type SidecarStatus = 'OK' | 'DEGRADED' | 'DOWN';

export interface OpsMemory {
  level: MemoryLevel;
  source: string;
  detail: string;
}

export interface OpsSidecar {
  name: string;
  status: SidecarStatus;
  detail: string;
}

/**
 * Aktive TTS-Engine (Toms ☁️-Cloud-Banner: „Cloud nur mit Banner").
 * `cloud:true` nur bei OpenAI (Egress) — das BE leitet beides seit dem
 * Runtime-Switch (9edbb1d/b4844d0) aus dem GEWÄHLTEN Laufzeit-Wunsch ab
 * (dieselbe Wahrheit wie die Settings-Sektion), Boot-Config (`HOSHI_TTS`)
 * NUR als Fallback ohne je einen Switch.
 */
export interface OpsVoice {
  engine: string;
  cloud: boolean;
}

export interface OpsStatus {
  overall: OpsOverall;
  memory: OpsMemory;
  sidecars: OpsSidecar[];
  /** `null` = BE liefert (noch) kein/kein gültiges voice-Feld → ehrlich: kein Banner behaupten. */
  voice: OpsVoice | null;
  /**
   * Andis Schloss-Bedingung: `true` NUR wenn der gesamte Sprech-Pfad lokal ist
   * (STT + Brain + gewählte TTS-Engine). Fehlend/ungültig ⇒ `false` (ehrlich
   * „nicht bewiesen", nie ein optimistisches Grün).
   */
  allLocal: boolean;
  ts: number;
}

const OVERALLS: readonly OpsOverall[] = ['OK', 'DEGRADED', 'DOWN'];
const LEVELS: readonly MemoryLevel[] = ['OK', 'WARN', 'CRITICAL', 'UNKNOWN'];
const SIDECAR_STATES: readonly SidecarStatus[] = ['OK', 'DEGRADED', 'DOWN'];

const str = (v: unknown, fallback = ''): string => (typeof v === 'string' ? v : fallback);

/**
 * Validiert die Wire-Antwort gegen den (eingefrorenen) Vertrag.
 * `enabled !== true` ODER fehlender/ungültiger Gesamtstatus → `null` (still).
 */
export function parseOpsStatus(body: unknown): OpsStatus | null {
  if (!body || typeof body !== 'object') return null;
  const b = body as Record<string, unknown>;

  // Feature-Flag: nur bei explizitem enabled:true zeigen wir überhaupt etwas.
  if (b.enabled !== true) return null;

  const overall = OVERALLS.includes(b.overall as OpsOverall) ? (b.overall as OpsOverall) : null;
  if (!overall) return null; // ohne ehrlichen Gesamtstatus: nichts zeigen

  const mem = (b.memory && typeof b.memory === 'object' ? b.memory : {}) as Record<string, unknown>;
  const level = LEVELS.includes(mem.level as MemoryLevel) ? (mem.level as MemoryLevel) : 'UNKNOWN';
  const memory: OpsMemory = { level, source: str(mem.source), detail: str(mem.detail) };

  const sidecars: OpsSidecar[] = Array.isArray(b.sidecars)
    ? (b.sidecars as unknown[]).flatMap((raw) => {
        if (!raw || typeof raw !== 'object') return [];
        const s = raw as Record<string, unknown>;
        const status = SIDECAR_STATES.includes(s.status as SidecarStatus)
          ? (s.status as SidecarStatus)
          : 'DOWN';
        return [{ name: str(s.name, '?'), status, detail: str(s.detail) }];
      })
    : [];

  // voice ist additiv-tolerant: fehlt/ungültig → null (kein Banner), NIE ein Parse-Fail
  // des Gesamtstatus — ältere BE-Stände ohne voice bleiben voll funktionsfähig.
  const v = (b.voice && typeof b.voice === 'object' ? b.voice : {}) as Record<string, unknown>;
  const voice: OpsVoice | null =
    typeof v.engine === 'string' && typeof v.cloud === 'boolean'
      ? { engine: v.engine, cloud: v.cloud }
      : null;

  // allLocal ist additiv-tolerant wie voice: fehlt/ungültig (älteres BE) ⇒ false,
  // NIE ein optimistisches Grün behaupten.
  const allLocal = b.allLocal === true;

  const ts = typeof b.ts === 'number' ? b.ts : Date.now();
  return { overall, memory, sidecars, voice, allLocal, ts };
}

/**
 * Best-effort-Abruf: jeder Misserfolg (404/401/5xx/Netz/Abbruch) → `null`.
 * Token geht als `X-Hoshi-Token` (gleicher Mechanismus wie `api/chat.ts`).
 */
export async function fetchOpsStatus(signal?: AbortSignal): Promise<OpsStatus | null> {
  try {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
    const res = await fetch(`${API_BASE}/api/v1/ops/status`, { headers, signal });
    if (!res.ok) return null; // 404 (Feature aus) / 401 / 5xx → still
    const body: unknown = await res.json().catch(() => null);
    return parseOpsStatus(body);
  } catch {
    return null; // Netzfehler/Abbruch → graceful hidden, nie ein roter Fehler
  }
}

/** Pollt `GET /api/v1/ops/status` (~30s), best-effort. null = nichts anzeigen. */
export function useOpsStatus(intervalMs = 30000): OpsStatus | null {
  const [status, setStatus] = useState<OpsStatus | null>(null);
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();

    const tick = async (): Promise<void> => {
      const next = await fetchOpsStatus(controller.signal);
      if (aliveRef.current) setStatus(next);
    };

    void tick();
    const id = window.setInterval(() => void tick(), intervalMs);
    return () => {
      aliveRef.current = false;
      controller.abort();
      window.clearInterval(id);
    };
  }, [intervalMs]);

  return status;
}

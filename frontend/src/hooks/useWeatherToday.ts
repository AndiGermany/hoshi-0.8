import { useEffect, useRef, useState } from 'react';
import { API_BASE, TOKEN } from '../api/config';

/**
 * Heutiges Wetter aus `GET /api/v1/weather/today` — die Datenquelle der
 * Wetter-Kachel im Idle-Gesicht ({@link IdleFace}). Das BE liest den EXAKTEN
 * Datenpfad des Wetter-Groundings (Open-Meteo, Store-Ort gewinnt) — die Kachel
 * zeigt also dieselbe Wahrheit, die Hoshi im Gespräch nutzt.
 *
 * Drei EHRLICHE Zustände statt einem stummen null:
 *  - `{kind:'live'}` — echte heutige Vorhersage (nie erfunden).
 *  - `{kind:'off'}` — 404: Wetter ist beim Deploy deaktiviert
 *    (`HOSHI_WEATHER_ENABLED`) ⇒ die Kachel bleibt gestrichelt („kommt"),
 *    exakt wie vor dem Endpoint.
 *  - `{kind:'unreachable'}` — 401/5xx/Netz/kaputtes JSON: der Endpoint
 *    existiert, liefert aber grad nichts Lesbares ⇒ „—" + ehrliche Notiz.
 * `null` im Hook = noch nicht geladen (der erste Fetch läuft).
 *
 * `parseWeatherToday`/`fetchWeatherToday` sind pure/seam-Funktionen (kein DOM,
 * keine Timer) → ohne Live-Backend unit-testbar (Muster `useOpsStatus`). Der
 * Hook pollt SANFT (~10 min — Wetter ändert sich langsam, das Idle-Gesicht
 * lebt lange).
 */

export interface WeatherToday {
  /** Wirksames Orts-Label (Store-Wert, sonst Deploy-Seed) — z.B. „Duisburg". */
  label: string;
  /** Heutige Min-Temperatur, gerundet (°C). */
  todayMin: number;
  /** Heutige Max-Temperatur, gerundet (°C). */
  todayMax: number;
  /** Deutscher Lagen-Text aus dem WMO-Code — z.B. „bedeckt". */
  codeText: string;
  /** Heutige Niederschlags-Summe in mm. */
  precipMm: number;
}

export type WeatherTodayState =
  | { kind: 'live'; data: WeatherToday }
  | { kind: 'off' }
  | { kind: 'unreachable' };

/**
 * Validiert die Wire-Antwort gegen den Vertrag `{label, todayMin, todayMax,
 * codeText, precipMm}`. Fehlt/falsch typisiert ⇒ `null` (nie eine erfundene Zahl).
 */
export function parseWeatherToday(body: unknown): WeatherToday | null {
  if (!body || typeof body !== 'object') return null;
  const b = body as Record<string, unknown>;
  if (typeof b.label !== 'string' || b.label === '') return null;
  if (typeof b.todayMin !== 'number' || typeof b.todayMax !== 'number') return null;
  if (typeof b.codeText !== 'string' || b.codeText === '') return null;
  if (typeof b.precipMm !== 'number') return null;
  return {
    label: b.label,
    todayMin: b.todayMin,
    todayMax: b.todayMax,
    codeText: b.codeText,
    precipMm: b.precipMm,
  };
}

/**
 * Abruf mit ehrlicher Zustands-Trennung: 404 = Feature aus (`off`), jeder
 * andere Misserfolg (401/5xx/Netz/kein Vertrag) = `unreachable`. Token geht
 * als `X-Hoshi-Token` (gleicher Mechanismus wie `useDiary`).
 */
export async function fetchWeatherToday(signal?: AbortSignal): Promise<WeatherTodayState> {
  try {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
    const res = await fetch(`${API_BASE}/api/v1/weather/today`, { headers, signal });
    if (res.status === 404) return { kind: 'off' }; // Wetter beim Deploy aus — ehrlich „kommt"
    if (!res.ok) return { kind: 'unreachable' }; // 401/502/5xx → grad nicht lesbar
    const body: unknown = await res.json().catch(() => null);
    const data = parseWeatherToday(body);
    return data ? { kind: 'live', data } : { kind: 'unreachable' };
  } catch {
    return { kind: 'unreachable' }; // Netzfehler/Abbruch → nie Fake-Wetter
  }
}

/** Pollt `GET /api/v1/weather/today` sanft (~10 min). `null` = erster Fetch läuft. */
export function useWeatherToday(intervalMs = 10 * 60 * 1000): WeatherTodayState | null {
  const [state, setState] = useState<WeatherTodayState | null>(null);
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    const controller = new AbortController();

    const tick = async (): Promise<void> => {
      const next = await fetchWeatherToday(controller.signal);
      if (aliveRef.current) setState(next);
    };

    void tick();
    const id = window.setInterval(() => void tick(), intervalMs);
    return () => {
      aliveRef.current = false;
      controller.abort();
      window.clearInterval(id);
    };
  }, [intervalMs]);

  return state;
}

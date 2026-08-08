import { useCallback, useEffect, useRef, useState } from 'react';
import { API_BASE, TOKEN } from '../api/config';

/**
 * Turn-Diary-Feed aus `GET /api/v1/diary/recent` — die Datenquelle der
 * Aktivitäts-View (Backend-Diary #10: eine JSONL-Zeile pro Turn, neueste zuerst).
 *
 * Ehrlichkeits-/Lärm-Achse (wie `useOpsStatus`):
 *  - 401/5xx/Netzfehler → `null` (nicht erreichbar, KEINE erfundenen Zeilen).
 *  - `[]` vom Backend ist eine echte Antwort: „noch kein Turn im Diary".
 *  - Das Diary trägt bewusst KEINE Gesprächs-Inhalte (Privacy by Design) —
 *    der Parser nimmt nur die ruhigen Mess-Felder mit.
 *
 * `parseDiaryTurns`/`fetchDiaryRecent` sind pure/seam-Funktionen (kein DOM,
 * keine Timer) → ohne Live-Backend unit-testbar. Der Hook lädt EINMAL beim
 * Öffnen der View + auf manuelles `refresh()` — bewusst KEIN Dauerpoll.
 *
 * `loadMore()` (Andi-Auftrag „Frühere laden" 2026-07-27): hängt weitere,
 * ÄLTERE Turns ans Ende des Arrays an — via den additiven `?before=`-Vertrag
 * von `GET /api/v1/diary/recent` (s. `DiaryController`). Der pure/seam-Anteil
 * (`fetchDiaryRecentBefore`) ist wie `fetchDiaryRecent` ohne Live-Backend
 * testbar; `hasMoreOlder` startet optimistisch `true` (unbekannt, nicht
 * „keine weiteren") und wird erst `false`, sobald eine `before`-Anfrage
 * ehrlich leer zurückkommt.
 */

export interface DiaryTurn {
  /** ISO-8601-Zeitpunkt des Turn-Starts (Pflicht — ohne Zeit keine ehrliche Zeile). */
  ts: string;
  /** Routing-Kategorie (z.B. FACT_SHORT, SMART_HOME); leer wenn unbekannt. */
  category: string;
  /** Effektive Persona des Turns; leer wenn unbekannt. */
  persona: string;
  /** Time-to-first-token in ms; null = nie ein Token gesehen. */
  ttftMs: number | null;
  /** Gesamtdauer des Turns in ms; null wenn die Zeile keine trägt. */
  totalMs: number | null;
  /** Ehrlichkeits-Deflect: deterministische „weiß ich nicht"-Antwort statt Brain. */
  deflected: boolean;
  /** Fehler-Stage (STT/LLM/SIDECAR/TTS); null = fehlerfrei. */
  error: string | null;
  /**
   * Stage-Latenzen (Perf-Diary, ab 06.07.2026): `null` = ALT-Zeile, die die
   * Keys gar nicht trägt („keine Stage-Daten“). Ein Objekt mit null-Werten ist
   * dagegen eine NEUE Zeile, deren Turn die jeweilige Stage ehrlich nicht
   * gemessen hat (Text-Turn ⇒ sttMs null, speak=false ⇒ ttsFirstAudioMs null).
   */
  stages: DiaryStageTimings | null;
}

/** Die fünf Stage-Messwerte einer neuen Diary-Zeile (je null = nicht gemessen). */
export interface DiaryStageTimings {
  sttMs: number | null;
  groundingMs: number | null;
  brainTtftMs: number | null;
  ttsFirstAudioMs: number | null;
  admissionWaitMs: number | null;
}

/** Die Stage-Keys der Wire-Zeile — Key ABWESEND (Alt-Zeile) ≠ Key null (nicht gemessen). */
const STAGE_KEYS = ['sttMs', 'groundingMs', 'brainTtftMs', 'ttsFirstAudioMs', 'admissionWaitMs'] as const;

/** Zahl oder ehrlich null — nie ein geratener Wert aus Junk. */
function num(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/**
 * Validiert die Wire-Antwort (Array von Diary-Zeilen). Kein Array → `null`;
 * Einträge ohne `ts`-String werden übersprungen (nie eine erfundene Zeile).
 */
export function parseDiaryTurns(body: unknown): DiaryTurn[] | null {
  if (!Array.isArray(body)) return null;
  return (body as unknown[]).flatMap((raw) => {
    if (!raw || typeof raw !== 'object') return [];
    const r = raw as Record<string, unknown>;
    if (typeof r.ts !== 'string' || r.ts === '') return [];
    // Alt-Zeile vs. neue Zeile EHRLICH unterscheiden: Alt-Zeilen (vor 06.07.)
    // tragen die Stage-Keys GAR NICHT ⇒ stages=null („keine Stage-Daten");
    // neue Zeilen tragen sie immer (ggf. mit null = nicht gemessen).
    const hasStageKeys = STAGE_KEYS.some((k) => k in r);
    return [
      {
        ts: r.ts,
        category: typeof r.category === 'string' ? r.category : '',
        persona: typeof r.persona === 'string' ? r.persona : '',
        ttftMs: num(r.ttftMs),
        totalMs: num(r.totalMs),
        deflected: r.deflected === true,
        error: typeof r.error === 'string' && r.error !== '' ? r.error : null,
        stages: hasStageKeys
          ? {
              sttMs: num(r.sttMs),
              groundingMs: num(r.groundingMs),
              brainTtftMs: num(r.brainTtftMs),
              ttsFirstAudioMs: num(r.ttsFirstAudioMs),
              admissionWaitMs: num(r.admissionWaitMs),
            }
          : null,
      },
    ];
  });
}

/** Gemeinsamer Abruf: jeder Misserfolg (401/5xx/Netz/Abbruch/kein Array) → `null`. */
async function fetchDiary(params: Record<string, string>, signal?: AbortSignal): Promise<DiaryTurn[] | null> {
  try {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (TOKEN.trim()) headers['X-Hoshi-Token'] = TOKEN;
    const qs = new URLSearchParams(params).toString();
    const res = await fetch(`${API_BASE}/api/v1/diary/recent?${qs}`, { headers, signal });
    if (!res.ok) return null; // 401/5xx → ehrlich „nicht erreichbar", kein Lärm
    const body: unknown = await res.json().catch(() => null);
    return parseDiaryTurns(body);
  } catch {
    return null; // Netzfehler/Abbruch → nicht erreichbar, nie ein Fake-Feed
  }
}

/**
 * Best-effort-Abruf: jeder Misserfolg (401/5xx/Netz/Abbruch/kein Array) → `null`.
 * Token geht als `X-Hoshi-Token` (gleicher Mechanismus wie `api/chat.ts`).
 */
export async function fetchDiaryRecent(
  limit = 50,
  signal?: AbortSignal,
): Promise<DiaryTurn[] | null> {
  return fetchDiary({ limit: String(limit) }, signal);
}

/** Nachlade-Schrittgröße (Netz-Seite) — deckungsgleich mit `FEED_PAGE_SIZE` der View. */
const FEED_LOAD_MORE_SIZE = 25;

/**
 * Nachlade-Seite: die `limit` jüngsten Zeilen STRIKT VOR `before` (additiver
 * Backend-Vertrag `?before=<ISO-8601-ts>`, s. `DiaryController`). Gleiche
 * Ehrlichkeits-Regel wie `fetchDiaryRecent`: jeder Misserfolg → `null`.
 */
export async function fetchDiaryRecentBefore(
  before: string,
  limit = FEED_LOAD_MORE_SIZE,
  signal?: AbortSignal,
): Promise<DiaryTurn[] | null> {
  return fetchDiary({ limit: String(limit), before }, signal);
}

/**
 * Lädt den Feed EINMAL beim Mount (View-Öffnen) und auf `refresh()` —
 * kein Intervall. `turns === null` heißt „(noch) nicht erreichbar/geladen".
 *
 * `loadMore()` hängt eine weitere, ÄLTERE Seite ans Ende an (kein Ersatz des
 * Arrays) — no-op ohne geladene Turns oder während eine Nachlade-Anfrage
 * schon läuft (kein doppelter Schuss bei Doppelklick). `hasMoreOlder` ist
 * VOR der ersten `before`-Anfrage optimistisch `true` (ehrlich „unbekannt",
 * nie eine erfundene Gewissheit) und wird erst `false`, wenn eine
 * `before`-Anfrage wirklich leer zurückkommt.
 */
export function useDiary(limit = 50): {
  turns: DiaryTurn[] | null;
  refresh: () => void;
  loadMore: () => void;
  hasMoreOlder: boolean;
} {
  const [turns, setTurns] = useState<DiaryTurn[] | null>(null);
  const [hasMoreOlder, setHasMoreOlder] = useState(true);
  const aliveRef = useRef(true);
  const turnsRef = useRef<DiaryTurn[] | null>(null);
  const loadingMoreRef = useRef(false);

  useEffect(() => {
    turnsRef.current = turns;
  }, [turns]);

  const refresh = useCallback(() => {
    void fetchDiaryRecent(limit).then((next) => {
      if (!aliveRef.current) return;
      setTurns(next);
      setHasMoreOlder(true); // frischer Ladevorgang: „mehr?" wieder unbekannt/offen
    });
  }, [limit]);

  const loadMore = useCallback(() => {
    if (loadingMoreRef.current) return; // schon eine Nachlade-Anfrage unterwegs
    const current = turnsRef.current;
    if (!current || current.length === 0) return; // nichts zum Anhängen
    const oldest = current[current.length - 1].ts;
    loadingMoreRef.current = true;
    void fetchDiaryRecentBefore(oldest, FEED_LOAD_MORE_SIZE).then((older) => {
      loadingMoreRef.current = false;
      if (!aliveRef.current) return;
      if (older === null || older.length === 0) {
        setHasMoreOlder(false); // ehrlich leer ⇒ keine weiteren mehr
        return;
      }
      setTurns((prev) => (prev ? [...prev, ...older] : prev));
    });
  }, []);

  useEffect(() => {
    aliveRef.current = true;
    refresh();
    return () => {
      aliveRef.current = false;
    };
  }, [refresh]);

  return { turns, refresh, loadMore, hasMoreOlder };
}

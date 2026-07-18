import type { DiaryTurn } from '../hooks/useDiary';

/**
 * Stage-Statistik der Aktivitäts-View (Perf-Diary) — REINE Funktionen, kein
 * DOM, kein Netz: client-seitig aus `GET /api/v1/diary/recent` aggregiert
 * (bewusst KEIN neuer Endpoint, solange recent reicht).
 *
 * Ehrlichkeits-Regeln:
 *  - Nur HEUTIGE Turns (lokaler Kalendertag) zählen in die Zusammenfassung.
 *  - Ein Turn ohne Wert für eine Stage fällt aus GENAU dieser Stage-Statistik
 *    heraus (kein 0-Auffüllen, keine Interpolation).
 *  - Keine Daten ⇒ p50/p95 = null ⇒ die View zeigt „—".
 *
 * Perzentil-Definition (mathematisch gepinnt, Tests): **Nearest-Rank** auf der
 * aufsteigend sortierten Liste — Rang = max(1, ceil(q·n)), 1-indiziert.
 * Beispiele: p50 von [1,2,3,4] = 2 · p95 von [1..20] = 19 · n=1 ⇒ der Wert.
 */

/** Die fünf Stages der Zusammenfassung (Anzeige-Reihenfolge = Pipeline-Reihenfolge). */
export const STAGES = [
  { key: 'sttMs', label: 'STT' },
  { key: 'groundingMs', label: 'Grounding' },
  { key: 'brainTtftMs', label: 'Brain (TTFT)' },
  { key: 'ttsFirstAudioMs', label: 'TTS (1. Audio)' },
  { key: 'admissionWaitMs', label: 'Admission' },
] as const;

export type StageKey = (typeof STAGES)[number]['key'];

export interface StageStat {
  /** Median (Nearest-Rank); null = keine Messwerte heute. */
  p50: number | null;
  /** 95. Perzentil (Nearest-Rank); null = keine Messwerte heute. */
  p95: number | null;
  /** Anzahl heutiger Turns MIT Messwert für diese Stage. */
  n: number;
}

/** Nearest-Rank-Perzentil (q ∈ (0,1]) über eine UNsortierte Werteliste; [] ⇒ null. */
export function percentile(values: number[], q: number): number | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const rank = Math.max(1, Math.ceil(q * sorted.length));
  return sorted[Math.min(rank, sorted.length) - 1];
}

/** Gleicher LOKALER Kalendertag wie `now`? (Unlesbares ts ⇒ false — fällt ehrlich raus.) */
export function isSameLocalDay(iso: string, now: Date): boolean {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return false;
  return (
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate()
  );
}

/**
 * Heutige p50/p95 je Stage. Turns ohne Stage-Daten (Alt-Zeilen) oder ohne den
 * jeweiligen Wert fallen ehrlich aus der jeweiligen Stage-Statistik.
 */
export function aggregateToday(
  turns: DiaryTurn[],
  now: Date,
): Record<StageKey, StageStat> {
  const today = turns.filter((t) => isSameLocalDay(t.ts, now));
  const out = {} as Record<StageKey, StageStat>;
  for (const { key } of STAGES) {
    const values = today.flatMap((t) => {
      const v = t.stages?.[key];
      return typeof v === 'number' ? [v] : [];
    });
    out[key] = { p50: percentile(values, 0.5), p95: percentile(values, 0.95), n: values.length };
  }
  return out;
}

/** Ein Punkt der Tages-Sparkline einer Stage: ms=null ist eine EHRLICHE Lücke. */
export interface StageSparkPoint {
  ms: number | null;
  /** ISO-Zeitpunkt des Turns (Tooltip-Text der Sparkline). */
  ts: string;
  /** Fehler-Turn (`turn.error !== null`) → hohler Punkt in der Sparkline. */
  error: boolean;
}

/**
 * Heutige Turns EINER Stage als chronologische Sparkline-Serie (ältester
 * zuerst — `turns` selbst kommt vom Diary neueste-zuerst, s. `useDiary`).
 * Turns ohne Wert für diese Stage bleiben als `ms=null` in der Serie (ehrliche
 * Lücke, dieselbe Ausfall-Regel wie `aggregateToday`) — die Sparkline darf sie
 * NICHT verbinden/interpolieren.
 */
export function stageSparkSeries(turns: DiaryTurn[], key: StageKey, now: Date): StageSparkPoint[] {
  const today = turns.filter((t) => isSameLocalDay(t.ts, now));
  const chronological = [...today].reverse();
  return chronological.map((t) => {
    const v = t.stages?.[key];
    return { ms: typeof v === 'number' ? v : null, ts: t.ts, error: t.error !== null };
  });
}

/** Ein Segment der pro-Turn-Leiste: Stage-Key, Label, ms und Breite in % (0..100). */
export interface StageSegment {
  key: StageKey | 'rest';
  label: string;
  ms: number;
  widthPct: number;
}

/** Die vier Leisten-Stages in Pipeline-Reihenfolge (Admission ist Warte-, keine Arbeits-Stage). */
const BAR_STAGES = [
  { key: 'sttMs', label: 'stt' },
  { key: 'groundingMs', label: 'grounding' },
  { key: 'brainTtftMs', label: 'brain' },
  { key: 'ttsFirstAudioMs', label: 'tts' },
] as const;

/**
 * Zerlegt einen Turn in die Segment-Leiste stt→grounding→brain→tts + Rest
 * („sonstiges" bis totalMs). null-Stages erzeugen KEIN Segment (ehrlich).
 *
 * Die Messwerte können sich zeitlich überlappen (ttsFirstAudioMs läuft ab
 * Stage-Start und enthält Brain-Wartezeit) — die Breiten werden deshalb auf
 * max(totalMs, Summe) normiert und der Rest nie negativ; die ms-Labels bleiben
 * die ECHTEN Messwerte.
 */
export function stageSegments(turn: DiaryTurn): StageSegment[] {
  if (turn.stages === null) return [];
  const parts = BAR_STAGES.flatMap(({ key, label }) => {
    const v = turn.stages?.[key];
    return typeof v === 'number' ? [{ key, label, ms: v }] : [];
  });
  const sum = parts.reduce((acc, p) => acc + p.ms, 0);
  const total = typeof turn.totalMs === 'number' ? turn.totalMs : null;
  const rest = total !== null ? Math.max(0, total - sum) : 0;
  const denom = Math.max(total ?? 0, sum + rest);
  if (denom <= 0) return [];
  const segments: StageSegment[] = parts.map((p) => ({
    key: p.key,
    label: p.label,
    ms: p.ms,
    widthPct: (p.ms / denom) * 100,
  }));
  if (rest > 0) {
    segments.push({ key: 'rest', label: 'sonstiges', ms: rest, widthPct: (rest / denom) * 100 });
  }
  return segments;
}

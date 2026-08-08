import type { DiaryTurn } from '../hooks/useDiary';

/**
 * Tages-Trenner + Cap/Nachladen für den Turn-Feed der Aktivitäts-View.
 *
 * Andi-Befund 2026-07-27: „das listet sich alle Turns — das bringt nichts,
 * wenn die Liste einfach nur wächst" (nach Wochen Betrieb eine ungegliederte
 * Endlos-Liste). Entscheidung: der Feed zeigt standardmäßig die letzten
 * {@link FEED_PAGE_SIZE} Turns, neueste zuerst, mit Tages-Trennern
 * („Heute"/„Gestern"/Datum) — ältere Turns nur hinter einem ruhigen
 * „Frühere laden"-Knopf. KEIN Endlos-Scroll: das ist ein Diagnose-Tab, kein
 * Social-Feed.
 *
 * Reine Funktionen, kein DOM/Netz — testbar wie `stageStats.ts`.
 */

/** Turns pro Schritt: Start-Cap UND Nachlade-Schrittgröße. */
export const FEED_PAGE_SIZE = 25;

export type DayKind = 'today' | 'yesterday' | 'earlier' | 'unknown';

export interface DaySegment {
  /** Gruppierungs-Schlüssel: lokaler Kalendertag „Y-M-D", oder „—" bei unlesbarem ts. */
  key: string;
  kind: DayKind;
  /** Nur bei kind==='earlier' gesetzt — das Datum für die lokalisierte Formatierung. */
  date: Date | null;
  /** Die Turns dieses Tages, in Feed-Reihenfolge (neueste zuerst). */
  turns: DiaryTurn[];
}

/** Lokaler Kalendertag um Mitternacht — Grundlage des Tages-ABSTANDS (keine 24h-Fenster-Fehler um Mitternacht). */
function startOfLocalDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

/** Heute/Gestern/früher — reiner Kalendertag-Abstand zu `now`. */
export function dayKind(d: Date, now: Date): DayKind {
  const oneDayMs = 86_400_000;
  const diff = Math.round((startOfLocalDay(now) - startOfLocalDay(d)) / oneDayMs);
  if (diff === 0) return 'today';
  if (diff === 1) return 'yesterday';
  return 'earlier';
}

/**
 * Gruppiert eine NEUESTE-ZUERST sortierte Turn-Liste in zusammenhängende
 * Tages-Segmente (Reihenfolge bleibt erhalten). Turns mit unlesbarem `ts`
 * fallen ehrlich in ein eigenes „unknown"-Segment — nie fälschlich als
 * „Heute" markiert.
 */
export function groupByDay(turns: DiaryTurn[], now: Date): DaySegment[] {
  const segments: DaySegment[] = [];
  for (const t of turns) {
    const d = new Date(t.ts);
    const valid = !Number.isNaN(d.getTime());
    const key = valid ? `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}` : '—';
    const last = segments[segments.length - 1];
    if (last && last.key === key) {
      last.turns.push(t);
      continue;
    }
    segments.push({
      key,
      kind: valid ? dayKind(d, now) : 'unknown',
      date: valid ? d : null,
      turns: [t],
    });
  }
  return segments;
}

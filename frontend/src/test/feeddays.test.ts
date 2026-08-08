import { describe, it, expect } from 'vitest';
import { FEED_PAGE_SIZE, dayKind, groupByDay } from '../components/feedDays';
import type { DiaryTurn } from '../hooks/useDiary';

/**
 * `components/feedDays.ts` — Tages-Trenner + Cap/Nachladen-Grundlage des
 * Turn-Feeds (Andi-Auftrag 2026-07-27). Reine Funktionen, kein DOM/Netz.
 */

const turn = (ts: string, over: Partial<DiaryTurn> = {}): DiaryTurn => ({
  ts,
  category: 'FACT_SHORT',
  persona: 'hoshi',
  ttftMs: 100,
  totalMs: 500,
  deflected: false,
  error: null,
  stages: null,
  ...over,
});

describe('FEED_PAGE_SIZE — die vereinbarte Schrittgröße (Cap UND Nachladen)', () => {
  it('ist 25 (Andi-Entscheid)', () => {
    expect(FEED_PAGE_SIZE).toBe(25);
  });
});

describe('dayKind — reiner Kalendertag-Abstand, keine 24h-Fenster-Fehler um Mitternacht', () => {
  const now = new Date(2026, 6, 27, 0, 30); // 27.07. 00:30 — knapp nach Mitternacht

  it('derselbe Kalendertag ⇒ „today", auch wenn < 24h nicht stimmen würde', () => {
    expect(dayKind(new Date(2026, 6, 27, 23, 0), now)).toBe('today');
  });

  it('der Kalendertag davor ⇒ „yesterday", selbst wenn nur Minuten dazwischen liegen', () => {
    expect(dayKind(new Date(2026, 6, 26, 23, 45), now)).toBe('yesterday');
  });

  it('zwei oder mehr Kalendertage zurück ⇒ „earlier"', () => {
    expect(dayKind(new Date(2026, 6, 25, 12, 0), now)).toBe('earlier');
    expect(dayKind(new Date(2026, 5, 1, 12, 0), now)).toBe('earlier');
  });
});

describe('groupByDay — Tages-Segmente einer neueste-zuerst sortierten Turn-Liste', () => {
  const now = new Date(2026, 6, 27, 10, 0);

  it('gruppiert zusammenhängende Turns desselben Kalendertags in EIN Segment', () => {
    const turns = [
      turn(new Date(2026, 6, 27, 9, 0).toISOString()),
      turn(new Date(2026, 6, 27, 8, 0).toISOString()),
      turn(new Date(2026, 6, 26, 20, 0).toISOString()),
      turn(new Date(2026, 6, 25, 11, 0).toISOString()),
    ];
    const segs = groupByDay(turns, now);
    expect(segs).toHaveLength(3);
    expect(segs[0].kind).toBe('today');
    expect(segs[0].turns).toHaveLength(2);
    expect(segs[1].kind).toBe('yesterday');
    expect(segs[1].turns).toHaveLength(1);
    expect(segs[2].kind).toBe('earlier');
    expect(segs[2].date?.getDate()).toBe(25);
  });

  it('unlesbares ts fällt in ein eigenes „unknown"-Segment — NIE fälschlich „today"', () => {
    const segs = groupByDay([turn('kaputt-kein-datum')], now);
    expect(segs).toHaveLength(1);
    expect(segs[0].kind).toBe('unknown');
    expect(segs[0].date).toBeNull();
  });

  it('leere Liste ⇒ keine Segmente', () => {
    expect(groupByDay([], now)).toEqual([]);
  });

  it('nicht-zusammenhängende gleiche Tage (z.B. durch einen Cap-Schnitt) bleiben getrennte Segmente', () => {
    // Realistisch selten (der Feed ist chronologisch), aber die Funktion soll
    // niemals zwei NICHT direkt benachbarte Turns desselben Tages fälschlich
    // zu einem Segment verschmelzen, nur weil der key gleich ist.
    const turns = [
      turn(new Date(2026, 6, 27, 9, 0).toISOString()),
      turn(new Date(2026, 6, 26, 20, 0).toISOString()),
      turn(new Date(2026, 6, 27, 7, 0).toISOString()), // wieder „today", aber NICHT direkt benachbart
    ];
    const segs = groupByDay(turns, now);
    expect(segs).toHaveLength(3);
    expect(segs[0].kind).toBe('today');
    expect(segs[1].kind).toBe('yesterday');
    expect(segs[2].kind).toBe('today');
  });
});

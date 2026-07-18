import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { AktivitaetView } from '../views/AktivitaetView';
import { parseDiaryTurns, type DiaryTurn } from '../hooks/useDiary';
import { aggregateToday, percentile, stageSegments } from '../components/stageStats';

/**
 * Stage-Metriken in der Aktivitäts-View (Perf-Diary):
 *  - Parse-Vertrag: Alt-Zeile (Keys FEHLEN) ⇒ stages=null · neue Zeile (Keys
 *    da, ggf. null-Werte) ⇒ stages-Objekt — die Ehrlichkeits-Unterscheidung.
 *  - p50/p95 mathematisch gepinnt (Nearest-Rank) + „heute"-Filter + Ausfall-
 *    Regel (Turn ohne Wert fällt aus GENAU der Stage).
 *  - Segment-Leiste: Reihenfolge, Rest bis totalMs, Normierung bei Überlappung.
 *  - Render: Zusammenfassungs-Kacheln, aufklappbare Zerlegung, ehrliche
 *    Zustände („—", „keine Stage-Daten (vor 06.07.)").
 */

const NOW = new Date('2026-07-06T12:00:00');

/** ts HEUTE (lokal) um die gegebene Stunde — passend zum NOW-Anker. */
const todayTs = (hour: number) => {
  const d = new Date(NOW);
  d.setHours(hour, 5, 0, 0);
  return d.toISOString();
};

const turn = (over: Partial<DiaryTurn> = {}): DiaryTurn => ({
  ts: todayTs(8),
  category: 'FACT_SHORT',
  persona: 'hoshi',
  ttftMs: 420,
  totalMs: 2000,
  deflected: false,
  error: null,
  stages: {
    sttMs: 300,
    groundingMs: 100,
    brainTtftMs: 900,
    ttsFirstAudioMs: 1400,
    admissionWaitMs: 0,
  },
  ...over,
});

const render = (turns: DiaryTurn[] | null) =>
  renderToStaticMarkup(
    <AktivitaetView observations={[]} turns={turns} onRefresh={() => {}} now={NOW} />,
  );

describe('parseDiaryTurns — Stage-Felder: Key fehlt (Alt) vs. Key null (nicht gemessen)', () => {
  it('neue Zeile mit Stage-Keys → stages-Objekt, null-Werte bleiben null', () => {
    const out = parseDiaryTurns([
      {
        ts: '2026-07-06T08:05:00Z',
        totalMs: 2000,
        sttMs: 300,
        groundingMs: null,
        brainTtftMs: 900,
        ttsFirstAudioMs: null,
        admissionWaitMs: 0,
      },
    ])!;
    expect(out[0].stages).toEqual({
      sttMs: 300,
      groundingMs: null,
      brainTtftMs: 900,
      ttsFirstAudioMs: null,
      admissionWaitMs: 0,
    });
    expect(out[0].totalMs).toBe(2000);
  });

  it('Alt-Zeile ohne Stage-Keys → stages=null (ehrlich „keine Stage-Daten")', () => {
    const out = parseDiaryTurns([{ ts: '2026-07-01T08:05:00Z', ttftMs: 420, totalMs: 1500 }])!;
    expect(out[0].stages).toBeNull();
  });

  it('Junk-Werte in Stage-Feldern → null, nie eine erfundene Zahl', () => {
    const out = parseDiaryTurns([
      { ts: '2026-07-06T08:05:00Z', sttMs: 'schnell', brainTtftMs: Infinity },
    ])!;
    expect(out[0].stages).toEqual({
      sttMs: null,
      groundingMs: null,
      brainTtftMs: null,
      ttsFirstAudioMs: null,
      admissionWaitMs: null,
    });
  });
});

describe('percentile — Nearest-Rank, mathematisch gepinnt', () => {
  it('p50 von [1,2,3,4] = 2 (Rang ceil(0.5·4)=2)', () => {
    expect(percentile([4, 2, 1, 3], 0.5)).toBe(2);
  });

  it('p95 von [1..20] = 19 (Rang ceil(0.95·20)=19)', () => {
    const vals = Array.from({ length: 20 }, (_, i) => i + 1);
    expect(percentile(vals, 0.95)).toBe(19);
  });

  it('p95 von [1..100] = 95 · p50 von [7] = 7 · leere Liste = null', () => {
    const vals = Array.from({ length: 100 }, (_, i) => i + 1);
    expect(percentile(vals, 0.95)).toBe(95);
    expect(percentile([7], 0.5)).toBe(7);
    expect(percentile([], 0.5)).toBeNull();
  });
});

describe('aggregateToday — heutige Turns, Ausfall-Regel je Stage', () => {
  it('nur heutige Turns zählen; gestern fällt raus', () => {
    const stats = aggregateToday(
      [
        turn({ stages: { sttMs: 100, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
        turn({ ts: todayTs(9), stages: { sttMs: 300, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
        // Gestern — darf die Statistik nicht berühren:
        turn({ ts: '2026-07-05T08:05:00.000Z', stages: { sttMs: 99999, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
      ],
      NOW,
    );
    expect(stats.sttMs.n).toBe(2);
    expect(stats.sttMs.p50).toBe(100); // Nearest-Rank: ceil(0.5·2)=1 ⇒ kleinerer Wert
    expect(stats.sttMs.p95).toBe(300); // ceil(0.95·2)=2 ⇒ größerer Wert
  });

  it('Turn ohne Wert fällt ehrlich aus GENAU der Stage; leere Stage ⇒ null/„—"', () => {
    const stats = aggregateToday(
      [
        turn({ stages: { sttMs: null, groundingMs: 50, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
        turn({ ts: todayTs(9), stages: null }), // Alt-Zeile: zählt in KEINE Stage
      ],
      NOW,
    );
    expect(stats.groundingMs).toEqual({ p50: 50, p95: 50, n: 1 });
    expect(stats.sttMs).toEqual({ p50: null, p95: null, n: 0 });
    expect(stats.ttsFirstAudioMs.n).toBe(0);
  });
});

describe('stageSegments — Leiste stt→grounding→brain→tts + Rest bis totalMs', () => {
  it('volle Zerlegung: Reihenfolge + Rest = totalMs − Summe', () => {
    const segs = stageSegments(
      turn({
        totalMs: 2000,
        stages: { sttMs: 300, groundingMs: 100, brainTtftMs: 900, ttsFirstAudioMs: null, admissionWaitMs: null },
      }),
    );
    expect(segs.map((s) => s.label)).toEqual(['stt', 'grounding', 'brain', 'sonstiges']);
    expect(segs.map((s) => s.ms)).toEqual([300, 100, 900, 700]);
    // Normierung: Summe der Breiten = 100 % (totalMs deckt alles).
    expect(segs.reduce((a, s) => a + s.widthPct, 0)).toBeCloseTo(100);
  });

  it('Überlappung (Summe > totalMs): Breiten normiert, Rest nie negativ, ms-Labels ECHT', () => {
    const segs = stageSegments(
      turn({
        totalMs: 2000,
        stages: { sttMs: 300, groundingMs: 100, brainTtftMs: 900, ttsFirstAudioMs: 1400, admissionWaitMs: null },
      }),
    );
    expect(segs.map((s) => s.label)).toEqual(['stt', 'grounding', 'brain', 'tts']); // kein Rest
    expect(segs.map((s) => s.ms)).toEqual([300, 100, 900, 1400]); // echte Messwerte
    expect(segs.reduce((a, s) => a + s.widthPct, 0)).toBeCloseTo(100); // auf die Summe normiert
  });

  it('Alt-Zeile (stages=null) und leere neue Zeile → keine Segmente', () => {
    expect(stageSegments(turn({ stages: null }))).toEqual([]);
    expect(
      stageSegments(
        turn({
          totalMs: null,
          stages: { sttMs: null, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null },
        }),
      ),
    ).toEqual([]);
  });
});

describe('AktivitaetView — Stage-Anzeige (Render-Vertrag)', () => {
  it('Zusammenfassung oben: Kachel je Stage mit p50/p95 aus heutigen Turns', () => {
    const out = render([turn()]);
    expect(out).toContain('Stage-Latenzen heute');
    expect(out).toContain('tiles--stages');
    for (const label of ['STT', 'Grounding', 'Brain (TTFT)', 'TTS (1. Audio)', 'Admission']) {
      expect(out).toContain(label);
    }
    expect(out).toContain('300 ms'); // stt p50 (ein Turn ⇒ p50=p95=Wert)
    expect(out).toContain('900 ms'); // brain p50
  });

  it('keine heutigen Messwerte → ehrliche „—"-Kacheln, keine erfundene Zahl', () => {
    const out = render([turn({ ts: '2026-07-01T08:05:00.000Z' })]); // nur ein ALTER Turn
    expect(out).toContain('keine Daten');
    expect(out).toContain('—');
  });

  it('pro Turn aufklappbar: Segment-Leiste mit ms-Labels in Pipeline-Reihenfolge', () => {
    const out = render([turn()]);
    expect(out).toContain('feed__details');
    expect(out).toContain('stagebar__track');
    expect(out).toContain('stagebar__seg--sttMs');
    expect(out).toContain('stagebar__seg--brainTtftMs');
    expect(out).toContain('stt: 300 ms'); // title-Label am Segment
    expect(out).toContain('gesamt'); // totalMs in der Legende
  });

  it('Alt-Zeile sagt ehrlich „keine Stage-Daten (vor 06.07.)"', () => {
    const out = render([turn({ stages: null })]);
    expect(out).toContain('keine Stage-Daten (vor 06.07.)');
    expect(out).not.toContain('stagebar__track');
  });

  it('Diary nicht erreichbar (null) → keine Zusammenfassung ohne Daten', () => {
    const out = render(null);
    expect(out).toContain('Diary nicht erreichbar');
    expect(out).not.toContain('tiles--stages');
  });
});

import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  StageSparkline,
  computeSparklineLayout,
  sparklineCap,
  mapValueToY,
  isP95Elevated,
  type StageSparklinePoint,
} from '../components/StageSparkline';
import { stageSparkSeries } from '../components/stageStats';
import { AktivitaetView } from '../views/AktivitaetView';
import type { DiaryTurn } from '../hooks/useDiary';

/**
 * Stage-Sparkline (Kachel-Upgrade der Aktivitäts-View): Ehrlichkeits-Fälle aus
 * dem Cowork-Report (.orch-bus/ctx/cowork-research-2026-07-15/02-metriken-graphen.md):
 *  - Lücken bleiben Lücken (kein Interpolieren, Linie bricht).
 *  - < 3 Messwerte insgesamt ⇒ NUR Punkte, keine Linie (kein Fake-Trend).
 *  - Ausreißer werden am Deckel geclampt + als ▲ markiert, der Tooltip nennt
 *    den ECHTEN Wert.
 *  - Deckel-Berechnung: clamp(max(2×p95, 1000 ms), ..., 20000 ms).
 *  - Referenzlinien (p50/p95) sitzen exakt an der berechneten y-Position.
 *  - Leere Serie ⇒ ehrlicher Leer-Zustand (kein SVG, keine erfundene Linie).
 */

const pt = (ms: number | null, over: Partial<StageSparklinePoint> = {}): StageSparklinePoint => ({
  ms,
  ts: '2026-07-15T08:00:00.000Z',
  ...over,
});

describe('sparklineCap — Deckel = clamp(max(2×p95, 1000 ms), ..., 20000 ms)', () => {
  it('kleines p95 ⇒ 1-Sekunden-Boden greift', () => {
    expect(sparklineCap(100)).toBe(1000);
    expect(sparklineCap(0)).toBe(1000);
  });

  it('mittleres p95 ⇒ 2×p95', () => {
    expect(sparklineCap(5000)).toBe(10000);
  });

  it('großes p95 ⇒ 20-Sekunden-Deckel greift (2×15000 > 20000)', () => {
    expect(sparklineCap(15000)).toBe(20000);
    expect(sparklineCap(50000)).toBe(20000);
  });
});

describe('mapValueToY — reine Skalen-Funktion', () => {
  it('0 ⇒ unterer Rand, cap ⇒ oberer Rand (Reserve-Pad eingerechnet)', () => {
    expect(mapValueToY(0, 1000, 40)).toBe(38); // height - BOTTOM_PAD(2)
    expect(mapValueToY(1000, 1000, 40)).toBe(6); // TOP_PAD
  });

  it('Werte über dem Deckel werden auf den Deckel geklemmt (nie negativ/über Rand hinaus)', () => {
    expect(mapValueToY(5000, 1000, 40)).toBe(mapValueToY(1000, 1000, 40));
  });
});

describe('isP95Elevated — p95 > 3×p50', () => {
  it('deutlich erhöht ⇒ true', () => {
    expect(isP95Elevated(100, 400)).toBe(true);
  });
  it('genau am Faktor 3 ⇒ false (strikt größer)', () => {
    expect(isP95Elevated(100, 300)).toBe(false);
  });
  it('fehlende Werte ⇒ false, nie ein erfundenes Warn-Signal', () => {
    expect(isP95Elevated(null, 400)).toBe(false);
    expect(isP95Elevated(100, null)).toBe(false);
  });
});

describe('computeSparklineLayout — Referenzlinien-Position', () => {
  it('p50/p95 landen exakt an der berechneten y-Koordinate', () => {
    // p95=1000 ⇒ cap = max(2000,1000) = 2000; plotH = 40-6-2 = 32.
    const layout = computeSparklineLayout([pt(500), pt(700), pt(900)], 500, 1000, 200, 40);
    expect(layout.cap).toBe(2000);
    expect(layout.p50Y).toBe(30); // 6 + (1 - 500/2000)*32
    expect(layout.p95Y).toBe(22); // 6 + (1 - 1000/2000)*32
  });

  it('keine Daten ⇒ keine Referenzlinie (null statt erfundener Position)', () => {
    const layout = computeSparklineLayout([pt(100), pt(200), pt(300)], null, null, 200, 40);
    expect(layout.p50Y).toBeNull();
    expect(layout.p95Y).toBeNull();
  });
});

describe('computeSparklineLayout — ehrliche Lücken', () => {
  it('ein Turn ohne Wert erzeugt keinen Punkt UND bricht die Linie', () => {
    const series = [pt(100), pt(null), pt(200), pt(300), pt(400)];
    const layout = computeSparklineLayout(series, 200, 400, 200, 40);
    // 4 echte Werte ⇒ Linie erlaubt, aber Index 0 ist von 2/3/4 durch die
    // Lücke bei Index 1 getrennt ⇒ genau EIN Segment, das NICHT bei Index 0 beginnt.
    expect(layout.plotted).toHaveLength(4);
    expect(layout.showLine).toBe(true);
    expect(layout.segments).toHaveLength(1);
    expect(layout.segments[0].map((p) => p.index)).toEqual([2, 3, 4]);
    // Index 0 bleibt ein einsamer Punkt (in keinem Segment).
    expect(layout.segments.some((seg) => seg.some((p) => p.index === 0))).toBe(false);
    expect(layout.plotted.find((p) => p.index === 0)).toBeDefined();
  });

  it('zwei durch eine Lücke getrennte 1er-Läufe ⇒ gar kein Segment trotz showLine', () => {
    const series = [pt(100), pt(null), pt(200), pt(null), pt(300)];
    const layout = computeSparklineLayout(series, 150, 300, 200, 40);
    expect(layout.plotted).toHaveLength(3); // ≥3 ⇒ showLine grundsätzlich erlaubt
    expect(layout.showLine).toBe(true);
    expect(layout.segments).toHaveLength(0); // aber jeder Lauf ist nur 1 Punkt lang
  });
});

describe('computeSparklineLayout — < 3 Messwerte ⇒ nie eine Linie', () => {
  it('0 Werte ⇒ keine Linie, keine Punkte', () => {
    const layout = computeSparklineLayout([pt(null), pt(null)], null, null, 200, 40);
    expect(layout.plotted).toHaveLength(0);
    expect(layout.showLine).toBe(false);
  });

  it('1 Wert ⇒ keine Linie', () => {
    const layout = computeSparklineLayout([pt(100)], 100, 100, 200, 40);
    expect(layout.plotted).toHaveLength(1);
    expect(layout.showLine).toBe(false);
  });

  it('2 direkt benachbarte Werte ⇒ IMMER NOCH keine Linie (kein Fake-Trend aus 2 Punkten)', () => {
    const layout = computeSparklineLayout([pt(100), pt(200)], 150, 200, 200, 40);
    expect(layout.plotted).toHaveLength(2);
    expect(layout.showLine).toBe(false);
    expect(layout.segments).toHaveLength(0);
  });

  it('3 Werte ⇒ Linie erlaubt', () => {
    const layout = computeSparklineLayout([pt(100), pt(200), pt(300)], 200, 300, 200, 40);
    expect(layout.showLine).toBe(true);
    expect(layout.segments).toHaveLength(1);
  });
});

describe('computeSparklineLayout — Ausreißer-Clamp', () => {
  it('Wert über dem Deckel: y auf den Deckel geklemmt, ms bleibt der ECHTE Wert, Tooltip nennt ihn', () => {
    // p95=500 ⇒ cap = max(1000,1000) = 1000.
    const layout = computeSparklineLayout([pt(100), pt(200), pt(5000)], 200, 500, 200, 40);
    const outlierPoint = layout.plotted[2];
    expect(layout.cap).toBe(1000);
    expect(outlierPoint.outlier).toBe(true);
    expect(outlierPoint.ms).toBe(5000); // NIE verfälscht
    expect(outlierPoint.y).toBe(mapValueToY(1000, 1000, 40)); // Zeichenposition geklemmt
    expect(outlierPoint.tooltip).toContain('5000 ms');
    expect(outlierPoint.tooltip).toContain('Ausreißer');
  });

  it('Fehler-Turn: Tooltip markiert „Fehler" zusätzlich', () => {
    const layout = computeSparklineLayout(
      [pt(100), pt(200), pt(300, { error: true })],
      200,
      300,
      200,
      40,
    );
    expect(layout.plotted[2].error).toBe(true);
    expect(layout.plotted[2].tooltip).toContain('Fehler');
  });
});

describe('computeSparklineLayout — leere Serie ⇒ ehrlicher Leer-Zustand', () => {
  it('[] ⇒ keine Punkte, keine Linie, keine Referenzlinien', () => {
    const layout = computeSparklineLayout([], null, null, 200, 40);
    expect(layout.plotted).toEqual([]);
    expect(layout.showLine).toBe(false);
    expect(layout.p50Y).toBeNull();
    expect(layout.p95Y).toBeNull();
  });

  it('Turns vorhanden, aber alle ms=null ⇒ ebenfalls leer', () => {
    const layout = computeSparklineLayout([pt(null), pt(null), pt(null)], null, null, 200, 40);
    expect(layout.plotted).toEqual([]);
  });
});

describe('StageSparkline — Render-Vertrag', () => {
  it('leere Serie ⇒ rendert NICHTS (kein SVG, keine erfundene Fläche)', () => {
    const out = renderToStaticMarkup(
      <StageSparkline label="STT" points={[]} p50={null} p95={null} />,
    );
    expect(out).toBe('');
  });

  it('< 3 Punkte ⇒ Punkte im Markup, aber keine .stagespark__line', () => {
    const out = renderToStaticMarkup(
      <StageSparkline label="STT" points={[pt(100), pt(200)]} p50={150} p95={200} />,
    );
    expect(out).toContain('stagespark__point');
    expect(out).not.toContain('stagespark__line');
  });

  it('≥ 3 Punkte ⇒ Linie vorhanden', () => {
    const out = renderToStaticMarkup(
      <StageSparkline
        label="STT"
        points={[pt(100), pt(200), pt(300)]}
        p50={200}
        p95={300}
      />,
    );
    expect(out).toContain('stagespark__line');
  });

  it('Ausreißer rendert als ▲ (.stagespark__outlier) mit echtem Wert im Tooltip', () => {
    const out = renderToStaticMarkup(
      <StageSparkline
        label="Brain (TTFT)"
        points={[pt(100), pt(200), pt(9000)]}
        p50={200}
        p95={500}
      />,
    );
    expect(out).toContain('stagespark__outlier');
    expect(out).toContain('9000 ms');
  });

  it('p95 > 3×p50 ⇒ p95-Referenzlinie in --warn', () => {
    const out = renderToStaticMarkup(
      <StageSparkline
        label="Brain (TTFT)"
        points={[pt(100), pt(200), pt(300)]}
        p50={100}
        p95={500}
      />,
    );
    expect(out).toContain('stagespark__ref--warn');
  });

  it('role=img + aria-label nennt Stage, Anzahl, Median, p95 (Muster der Segment-Leiste)', () => {
    const out = renderToStaticMarkup(
      <StageSparkline label="STT" points={[pt(300), pt(400), pt(500)]} p50={400} p95={500} />,
    );
    expect(out).toContain('role="img"');
    expect(out).toContain('STT heute: 3 Messwerte, Median 400 ms, p95 500 ms');
  });
});

describe('stageSparkSeries — heutige Turns EINER Stage, chronologisch, Ausfall-Regel', () => {
  const NOW = new Date('2026-07-15T12:00:00');
  const todayTs = (hour: number) => {
    const d = new Date(NOW);
    d.setHours(hour, 0, 0, 0);
    return d.toISOString();
  };
  const turn = (over: Partial<DiaryTurn> = {}): DiaryTurn => ({
    ts: todayTs(8),
    category: 'FACT_SHORT',
    persona: 'hoshi',
    ttftMs: 400,
    totalMs: 2000,
    deflected: false,
    error: null,
    stages: { sttMs: 300, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null },
    ...over,
  });

  it('kehrt die (neueste-zuerst) Diary-Reihenfolge in chronologisch (ältester zuerst) um', () => {
    // useDiary liefert neueste zuerst — hier bewusst so übergeben.
    const turns = [
      turn({ ts: todayTs(10), stages: { sttMs: 300, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
      turn({ ts: todayTs(9), stages: { sttMs: 200, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
      turn({ ts: todayTs(8), stages: { sttMs: 100, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
    ];
    const series = stageSparkSeries(turns, 'sttMs', NOW);
    expect(series.map((p) => p.ms)).toEqual([100, 200, 300]);
  });

  it('Turn ohne Wert für die Stage ⇒ ms=null in der Serie (ehrliche Lücke, kein Ausfall aus der Serie selbst)', () => {
    const turns = [
      turn({ ts: todayTs(10), stages: { sttMs: 300, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
      turn({ ts: todayTs(9), stages: { sttMs: null, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
      turn({ ts: todayTs(8), stages: { sttMs: 100, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
    ];
    const series = stageSparkSeries(turns, 'sttMs', NOW);
    expect(series.map((p) => p.ms)).toEqual([100, null, 300]);
  });

  it('gestrige Turns fallen raus (gleicher Filter wie aggregateToday)', () => {
    const turns = [
      turn({ ts: todayTs(8), stages: { sttMs: 100, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
      turn({ ts: '2026-07-14T08:00:00.000Z', stages: { sttMs: 99999, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
    ];
    const series = stageSparkSeries(turns, 'sttMs', NOW);
    expect(series).toHaveLength(1);
    expect(series[0].ms).toBe(100);
  });

  it('Alt-Zeile (stages=null) ⇒ ms=null in der Serie', () => {
    const turns = [turn({ ts: todayTs(8), stages: null })];
    const series = stageSparkSeries(turns, 'sttMs', NOW);
    expect(series).toEqual([{ ms: null, ts: todayTs(8), error: false }]);
  });
});

describe('AktivitaetView — Sparkline-Einbau in der Stage-Kachel', () => {
  const NOW = new Date('2026-07-15T12:00:00');
  const todayTs = (hour: number) => {
    const d = new Date(NOW);
    d.setHours(hour, 0, 0, 0);
    return d.toISOString();
  };
  const turn = (over: Partial<DiaryTurn> = {}): DiaryTurn => ({
    ts: todayTs(8),
    category: 'FACT_SHORT',
    persona: 'hoshi',
    ttftMs: 400,
    totalMs: 2000,
    deflected: false,
    error: null,
    stages: { sttMs: 300, groundingMs: 100, brainTtftMs: 900, ttsFirstAudioMs: 1400, admissionWaitMs: 0 },
    ...over,
  });

  it('mit heutigen Messwerten erscheint die Sparkline in der Kachel', () => {
    const out = renderToStaticMarkup(
      <AktivitaetView observations={[]} turns={[turn(), turn({ ts: todayTs(9) }), turn({ ts: todayTs(10) })]} onRefresh={() => {}} now={NOW} />,
    );
    expect(out).toContain('stagespark');
  });

  it('ohne heutige Messwerte bleibt die Kachel wie bisher, keine Sparkline', () => {
    const out = renderToStaticMarkup(
      <AktivitaetView
        observations={[]}
        turns={[turn({ ts: '2026-07-01T08:00:00.000Z' })]}
        onRefresh={() => {}}
        now={NOW}
      />,
    );
    expect(out).not.toContain('stagespark');
    expect(out).toContain('keine Daten');
  });

  it('p95 > 3×p50 einer Stage ⇒ p95-Text der Kachel in --warn', () => {
    const turns = [
      turn({ ts: todayTs(8), stages: { sttMs: 100, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
      turn({ ts: todayTs(9), stages: { sttMs: 100, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
      turn({ ts: todayTs(10), stages: { sttMs: 500, groundingMs: null, brainTtftMs: null, ttsFirstAudioMs: null, admissionWaitMs: null } }),
    ];
    const out = renderToStaticMarkup(
      <AktivitaetView observations={[]} turns={turns} onRefresh={() => {}} now={NOW} />,
    );
    expect(out).toContain('stagesum__p95--warn');
  });
});

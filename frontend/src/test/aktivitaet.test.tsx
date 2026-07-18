import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { AktivitaetView, type HealthObservation } from '../views/AktivitaetView';
import { parseDiaryTurns, type DiaryTurn } from '../hooks/useDiary';

const render = (observations: HealthObservation[], turns: DiaryTurn[] | null = []) =>
  renderToStaticMarkup(
    <AktivitaetView observations={observations} turns={turns} onRefresh={() => {}} />,
  );
const count = (s: string, needle: string) => s.split(needle).length - 1;

const turn = (over: Partial<DiaryTurn> = {}): DiaryTurn => ({
  ts: '2026-07-01T08:05:00Z',
  category: 'FACT_SHORT',
  persona: 'hoshi',
  ttftMs: 420,
  totalMs: 1500,
  deflected: false,
  error: null,
  stages: null,
  ...over,
});

describe('parseDiaryTurns — Parse-Vertrag zur Diary-Wire (GET /api/v1/diary/recent)', () => {
  it('nimmt aus einer vollen Diary-Zeile nur die ruhigen Mess-Felder mit', () => {
    // Volle Wire-Zeile exakt im JsonlTurnTraceAdapter-Format (inkl. Feldern,
    // die der Feed bewusst NICHT zeigt — deltaChars statt Text: Privacy by Design).
    const wire = [
      {
        ts: '2026-07-01T08:05:00Z',
        chatId: 'c1',
        category: 'SMART_HOME',
        provider: 'LOCAL',
        persona: 'hoshi',
        language: 'DE',
        ttftMs: 420,
        totalMs: 1500,
        deltaChars: 64,
        audioChunks: 3,
        speak: true,
        deflected: true,
        error: 'TTS',
        groundingUsed: false,
      },
    ];
    expect(parseDiaryTurns(wire)).toEqual([
      {
        ts: '2026-07-01T08:05:00Z',
        category: 'SMART_HOME',
        persona: 'hoshi',
        ttftMs: 420,
        totalMs: 1500,
        deflected: true,
        error: 'TTS',
        // Alt-Zeile (keine Stage-Keys) ⇒ stages ehrlich null („keine Stage-Daten").
        stages: null,
      },
    ]);
  });

  it('kein Array (Fehlerseite/Junk) → null, nicht []', () => {
    expect(parseDiaryTurns(null)).toBeNull();
    expect(parseDiaryTurns({ error: 'nope' })).toBeNull();
    expect(parseDiaryTurns('kaputt')).toBeNull();
  });

  it('leeres Array bleibt leeres Array (echte „noch kein Turn"-Antwort)', () => {
    expect(parseDiaryTurns([])).toEqual([]);
  });

  it('Einträge ohne ts-String fallen raus; Defaults sind ehrlich (null/false/leer)', () => {
    const out = parseDiaryTurns([
      { ts: '2026-07-01T08:05:00Z' }, // minimal gültig
      { category: 'X' }, // ohne ts → raus
      42, // Junk → raus
      { ts: '2026-07-01T08:06:00Z', ttftMs: 'schnell', error: '', deflected: 'ja' },
    ]);
    expect(out).toEqual([
      { ts: '2026-07-01T08:05:00Z', category: '', persona: '', ttftMs: null, totalMs: null, deflected: false, error: null, stages: null },
      { ts: '2026-07-01T08:06:00Z', category: '', persona: '', ttftMs: null, totalMs: null, deflected: false, error: null, stages: null },
    ]);
  });
});

describe('AktivitaetView — der Turn-Feed lebt (Render-Vertrag)', () => {
  it('rendert beide echten Achsen: 🟢 Turn-Feed (Diary) und 🟢 Health-Verlauf', () => {
    const out = render([]);
    expect(out).toContain('Aktivität');
    expect(out).toContain('Turn-Feed'); // 🟢 echt seit dem Diary (#10)
    expect(out).toContain('Tagesbuch'); // echte Quelle benannt, ohne Dev-Jargon
    expect(out).toContain('Health-Verlauf'); // 🟢 weiterhin echt
    expect(out).toContain('Verbindungsstatus');
    expect(out).not.toContain('nicht verdrahtet'); // die Hülle ist erweckt
  });

  it('eine Turn-Zeile trägt Uhrzeit · Kategorie-Chip · Persona · ttft — und KEINEN Inhalt', () => {
    const out = render([], [turn()]);
    expect(count(out, 'feed__row--turn')).toBe(1);
    expect(out).toContain('feed__time');
    expect(out).toContain('feed__chip');
    expect(out).toContain('FACT_SHORT');
    expect(out).toContain('feed__persona');
    expect(out).toContain('hoshi');
    expect(out).toContain('420 ms'); // ttft in ms
    // Ruhige Zeile: keine Flags ohne Grund.
    expect(out).not.toContain('feed__flag');
    expect(out).not.toContain('🔇');
    expect(out).not.toContain('⚠');
  });

  it('SVG-Flags statt Emojis: muted-Glyph markiert deflected, warn-Glyph error', () => {
    const out = render([], [
      turn({ deflected: true }),
      turn({ ts: '2026-07-01T08:06:00Z', error: 'TTS' }),
    ]);
    expect(count(out, 'feed__flag--deflected')).toBe(1);
    expect(count(out, 'feed__flag--error')).toBe(1);
    expect(count(out, 'glyph--muted')).toBe(1); // SVG-Glyph statt 🔇
    expect(count(out, 'glyph--warn')).toBe(1); // SVG-Glyph statt ⚠
    expect(out).not.toContain('🔇');
    expect(out).not.toContain('⚠');
  });

  it('ttftMs null → „—" statt einer erfundenen Zahl', () => {
    const out = render([], [turn({ ttftMs: null })]);
    expect(out).not.toContain('ms</span>');
  });

  it('trägt den stillen Privacy-Hinweis: das Diary hat bewusst keine Inhalte', () => {
    const out = render([], [turn()]);
    expect(out).toContain('feed__privacy');
    expect(out).toContain('Privacy by Design');
    expect(out).toContain('keine Gesprächs-Inhalte');
  });

  it('leeres Diary → ehrliche Leere, keine erfundene Zeile', () => {
    const out = render([], []);
    expect(out).toContain('Noch kein Turn im Diary');
    expect(out).not.toContain('feed__row--turn');
  });

  it('Diary nicht erreichbar (null) → ehrlicher Hinweis + Refresh-Knopf bleibt da', () => {
    const out = render([], null);
    expect(out).toContain('data-status="unreachable"');
    expect(out).toContain('Diary nicht erreichbar');
    expect(out).toContain('feed__refresh'); // manuell erneut versuchen — kein Dauerpoll
    expect(out).not.toContain('feed__row--turn');
  });

  it('zeigt ohne Health-Beobachtung KEINEN erfundenen Verlauf, nur einen ehrlichen Hinweis', () => {
    const out = render([]);
    expect(out).toContain('Noch keine Beobachtung');
    expect(out).not.toContain('feed__row--up');
  });

  it('listet reale Health-Beobachtungen als Feed-Zeilen, neueste zuerst', () => {
    const t = 1_700_000_000_000;
    const out = render([
      { state: 'up', at: t + 5000 },
      { state: 'down', at: t },
    ]);
    expect(out).toContain('feed__row--up');
    expect(out).toContain('feed__row--down');
    expect(out).toContain('Backend online');
    expect(out).toContain('Backend offline');
  });
});

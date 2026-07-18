import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  ALARM_PROGRESS_WINDOW_MS,
  IdleFace,
  alarmLineText,
  alarmProgress,
  diaryTodayStats,
  fmtP50,
  nextAlarm,
  planTileValue,
  statusChips,
  todayTileValue,
  weatherTile,
  weatherTileValue,
} from '../components/IdleFace';
import type { ScheduledItem } from '../hooks/useScheduledItems';
import type { DiaryTurn } from '../hooks/useDiary';
import type { WeatherToday, WeatherTodayState } from '../hooks/useWeatherToday';

/* Feste LOKALE Zeitpunkte (kein UTC-String) → TZ-unabhängige Tests:
   Samstag, 4. Juli 2026, 07:04 Ortszeit. */
const NOW = new Date(2026, 6, 4, 7, 4).getTime();
const H = 60 * 60 * 1000;

const alarm = (dueAtEpochMs: number, id = 'a1'): ScheduledItem => ({
  id,
  kind: 'ALARM',
  dueAtEpochMs,
});
const timer = (dueAtEpochMs: number, id = 't1'): ScheduledItem => ({
  id,
  kind: 'TIMER',
  dueAtEpochMs,
});

const turnAt = (d: Date, ttftMs: number | null, error: string | null = null): DiaryTurn => ({
  ts: d.toISOString(),
  category: 'FACT_SHORT',
  persona: 'hoshi',
  ttftMs,
  totalMs: null,
  deflected: false,
  error,
  stages: null,
});

const heute29: WeatherToday = {
  label: 'Duisburg',
  todayMin: 18,
  todayMax: 29,
  codeText: 'bedeckt',
  precipMm: 0,
};
const liveWeather: WeatherTodayState = { kind: 'live', data: heute29 };

const render = (over: Partial<Parameters<typeof IdleFace>[0]> = {}) =>
  renderToStaticMarkup(
    <IdleFace
      nowMs={NOW}
      health="up"
      voice={null}
      scheduled={[]}
      turns={[]}
      weather={null}
      {...over}
    />,
  );

const count = (html: string, needle: string) => html.split(needle).length - 1;

describe('IdleFace-Helfer — pur, ohne DOM', () => {
  it('nextAlarm nimmt den frühesten WECKER, nie Timer/Erinnerungen', () => {
    expect(nextAlarm([])).toBeNull();
    expect(nextAlarm([timer(NOW + H)])).toBeNull(); // Timer zählt nicht als Wecker
    const early = alarm(NOW + 2 * H, 'early');
    const late = alarm(NOW + 5 * H, 'late');
    expect(nextAlarm([late, early, timer(NOW + H)])?.id).toBe('early');
  });

  it('alarmProgress füllt die letzten 24 h vor dem Klingeln (0..1, geklemmt)', () => {
    expect(alarmProgress(NOW + ALARM_PROGRESS_WINDOW_MS, NOW)).toBe(0); // genau 24 h weg
    expect(alarmProgress(NOW + 36 * H, NOW)).toBe(0); // weiter weg ⇒ leer, nie negativ
    expect(alarmProgress(NOW + 12 * H, NOW)).toBeCloseTo(0.5, 10);
    expect(alarmProgress(NOW, NOW)).toBe(1); // fällig ⇒ voll
    expect(alarmProgress(NOW - H, NOW)).toBe(1); // überfällig bleibt voll
  });

  it('alarmLineText: „Wecker HH:MM · noch X" aus Weck-Uhrzeit + Restzeit', () => {
    const due = new Date(2026, 6, 4, 9, 0).getTime(); // 09:00 Ortszeit, in 1 h 56 min
    expect(alarmLineText(alarm(due), NOW)).toBe('Wecker 09:00 · noch 1 h 56 min');
    // Überfällig: nie eine negative Restzeit behaupten.
    expect(alarmLineText(alarm(NOW - H), NOW)).toContain('noch unter 1 min');
  });

  it('diaryTodayStats zählt NUR heute, Median über ttft, Fehler als Aussetzer', () => {
    const today9 = new Date(2026, 6, 4, 6, 30);
    const today10 = new Date(2026, 6, 4, 7, 0);
    const yesterday = new Date(2026, 6, 3, 22, 0);
    const stats = diaryTodayStats(
      [
        turnAt(today9, 1800),
        turnAt(today10, 2200, 'TTS'), // heutiger Fehler = 1 Aussetzer
        turnAt(yesterday, 99999, 'LLM'), // gestern: zählt gar nicht
        { ...turnAt(today10, null), ts: 'kaputt' }, // unlesbares ts: übersprungen
      ],
      NOW,
    );
    expect(stats.turns).toBe(2);
    expect(stats.p50Ms).toBe(2000); // Median aus [1800, 2200]
    expect(stats.errors).toBe(1);
  });

  it('diaryTodayStats: ttft-lose Turns zählen, liefern aber kein p50', () => {
    const stats = diaryTodayStats([turnAt(new Date(2026, 6, 4, 6, 0), null)], NOW);
    expect(stats.turns).toBe(1);
    expect(stats.p50Ms).toBeNull();
    expect(todayTileValue(stats)).toBe('1 Turn · p50 — · 0 Aussetzer');
  });

  it('fmtP50 formatiert deutsch mit Komma', () => {
    expect(fmtP50(1800)).toBe('1,8 s');
    expect(fmtP50(2000)).toBe('2,0 s');
    expect(fmtP50(480)).toBe('0,5 s');
  });

  it('planTileValue: kind-ehrliche Zählung, leer = „Nichts geplant"', () => {
    expect(planTileValue([])).toBe('Nichts geplant');
    expect(
      planTileValue([timer(1, 'x'), timer(2, 'y'), alarm(3, 'z')]),
    ).toBe('2 Timer · 1 Wecker');
    expect(planTileValue([{ id: 'r', kind: 'REMINDER', dueAtEpochMs: 4 }])).toBe('1 Erinnerung');
  });

  it('weatherTileValue: „18–29° · bedeckt" — kompakt aus echten Tageswerten', () => {
    expect(weatherTileValue(heute29)).toBe('18–29° · bedeckt');
    expect(
      weatherTileValue({ label: 'Berlin', todayMin: -2, todayMax: 5, codeText: 'leichter Schneefall', precipMm: 1.2 }),
    ).toBe('-2–5° · leichter Schneefall');
  });

  it('weatherTile: vier ehrliche Zustände (live · off · unreachable · lädt)', () => {
    const live = weatherTile(liveWeather);
    expect(live.honesty).toBe('live');
    expect(live.value).toBe('18–29° · bedeckt');
    expect(live.note).toContain('Duisburg'); // Ort-Label in der Notiz

    const off = weatherTile({ kind: 'off' });
    expect(off.honesty).toBe('pending'); // gestrichelt wie vor dem Endpoint
    expect(off.value).toBe('—');
    expect(off.note).toContain('ehrlich leer statt erfunden');

    const unreachable = weatherTile({ kind: 'unreachable' });
    expect(unreachable.honesty).toBe('live'); // Muster „Heute"-Kachel bei Diary-Ausfall
    expect(unreachable.value).toBe('—');
    expect(unreachable.note).toContain('Wetter grad nicht lesbar');

    const loading = weatherTile(null);
    expect(loading.honesty).toBe('pending');
    expect(loading.value).toBe('—');
  });

  it('statusChips: Health immer ehrlich, Stimme-Chip NUR mit echtem voice-Feld', () => {
    expect(statusChips('up', null)).toEqual([{ text: 'online', tone: 'ok' }]);
    expect(statusChips('down', null)[0].text).toBe('offline');
    expect(statusChips('unknown', null)[0].text).toBe('wird geprüft');
    // voice unbekannt ⇒ KEIN Stimme-Chip (nichts behaupten, was nichts misst):
    expect(statusChips('up', null)).toHaveLength(1);
    // Das Glyph leitet die Ansicht aus tone ab (muted SVG statt ☁/🔒-Emoji).
    expect(statusChips('up', { engine: 'openai', cloud: true })[1]).toEqual({
      text: 'Stimme: Cloud',
      tone: 'cloud',
    });
    expect(statusChips('up', { engine: 'voxtral', cloud: false })[1]).toEqual({
      text: 'Stimme: lokal',
      tone: 'local',
    });
  });
});

describe('IdleFace — das Aoi-„Zuhause"-Layout (Spec §2)', () => {
  it('zeigt die Typo-Uhr (echte Zeit) + tageszeitbewussten Gruß', () => {
    const html = render();
    expect(html).toContain('idle__clock');
    expect(html).toContain('07:04'); // echte (lokal konstruierte) Zeit, nichts erfunden
    expect(html).toContain('Guten Morgen'); // 7 Uhr ⇒ Morgen
    const evening = render({ nowMs: new Date(2026, 6, 4, 20, 15).getTime() });
    expect(evening).toContain('Guten Abend');
    expect(evening).toContain('20:15');
  });

  it('Wecker-Zeile: Uhrzeit, Restzeit, Haarlinie und der Vertrauens-Satz', () => {
    const due = new Date(2026, 6, 4, 9, 0).getTime();
    const html = render({ scheduled: [alarm(due)] });
    expect(html).toContain('data-alarm="set"');
    expect(html).toContain('Wecker 09:00 · noch 1 h 56 min');
    expect(html).toContain('klingelt auch offline'); // Text ist Teil des Designs
    expect(html).toContain('idle__alarmtrack'); // 2px-Haarlinie …
    expect(html).toContain('scaleX('); // … mit transform-Fortschritt (nie width/opacity)
  });

  it('ohne Wecker: ehrliche Leere statt Haarlinie und Versprechen', () => {
    const html = render({ scheduled: [timer(NOW + H)] }); // Timer ist KEIN Wecker
    expect(html).toContain('data-alarm="none"');
    expect(html).toContain('Kein Wecker gestellt');
    expect(html).not.toContain('klingelt auch offline'); // kein Satz über nichts
    expect(html).not.toContain('idle__alarmtrack');
  });

  it('3 Kacheln: ALLE LIVE mit echten Werten, wenn das Wetter Daten liefert', () => {
    const due = new Date(2026, 6, 4, 9, 0).getTime();
    const html = render({
      scheduled: [alarm(due), timer(NOW + H)],
      turns: [
        turnAt(new Date(2026, 6, 4, 6, 30), 1800),
        turnAt(new Date(2026, 6, 4, 7, 0), 2200, 'TTS'),
      ],
      weather: liveWeather,
    });
    expect(count(html, 'data-status="live"')).toBe(3);
    expect(count(html, 'data-status="pending"')).toBe(0);
    expect(html).toContain('2 Turns · p50 2,0 s · 1 Aussetzer'); // echte Diary-Zahlen
    expect(html).toContain('1 Timer · 1 Wecker'); // echte Scheduled-Zahlen
    expect(html).toContain('18–29° · bedeckt'); // echte Wetter-Werte, kompakt
    expect(html).toContain('Duisburg'); // Ort-Label in der Wetter-Notiz
  });

  it('Wetter beim Deploy aus (404) ⇒ Kachel bleibt gestrichelt mit ehrlichem Grund', () => {
    const html = render({ weather: { kind: 'off' } });
    expect(count(html, 'data-status="pending"')).toBe(1); // nur die Wetter-Kachel
    expect(html).toContain('ehrlich leer statt erfunden');
    expect(html).not.toContain('°'); // keine erfundenen Grade
  });

  it('Wetter nicht lesbar ⇒ „—" + ehrliche Notiz, nie Fake-Grade', () => {
    const html = render({ weather: { kind: 'unreachable' } });
    expect(html).toContain('Wetter grad nicht lesbar');
    expect(html).not.toContain('°');
  });

  it('Diary nicht erreichbar ⇒ die Kachel sagt das, statt Zahlen zu erfinden', () => {
    const html = render({ turns: null });
    expect(html).toContain('Diary nicht erreichbar');
    expect(html).not.toContain('Aussetzer'); // keine erfundene Statistik
  });

  it('leeres Diary heute ⇒ „Noch kein Turn" (leer ist ehrlich leer)', () => {
    const html = render({ turns: [turnAt(new Date(2026, 6, 3, 22, 0), 1500)] });
    expect(html).toContain('Noch kein Turn'); // der gestrige Turn zählt nicht als heute
  });

  it('stille Text-Chips: Health ehrlich, Stimme nur wenn gemessen (SVG statt Emoji)', () => {
    expect(render()).toContain('online');
    expect(render({ health: 'down' })).toContain('offline');
    expect(render()).not.toContain('Stimme:'); // voice=null ⇒ kein Chip
    const cloud = render({ voice: { engine: 'openai', cloud: true } });
    expect(cloud).toContain('Stimme: Cloud');
    expect(cloud).toContain('glyph--cloud'); // Wolken-SVG …
    expect(cloud).not.toContain('☁'); // … kein Emoji im Chip
    const local = render({ voice: { engine: 'voxtral', cloud: false } });
    expect(local).toContain('Stimme: lokal');
    expect(local).toContain('glyph--lock'); // Schloss-SVG …
    expect(local).not.toContain('🔒'); // … kein Emoji im Chip
  });

  it('Wecker-Zeile trägt das Wecker-SVG, kein ⏰-Emoji (Emoji-Sweep 2026-07-06)', () => {
    const withAlarm = render({ scheduled: [alarm(NOW + H)] });
    expect(withAlarm).toContain('glyph--alarm');
    expect(withAlarm).not.toContain('⏰');
    const without = render();
    expect(without).toContain('glyph--alarm'); // auch die ehrliche Leere trägt das Icon
    expect(without).not.toContain('⏰');
  });

  it('rendert KEINE Welle — ruhiges Papier (hier hört Hoshi nichts, Korrektur 20260706-1729)', () => {
    // Andi-Feedback 2026-07-06: „Da hört Hoshi nichts." Gesetz: nichts
    // leuchtet, was nichts misst — die Welle existiert nur im Chat-Voice-Flow
    // bei offenem Audio-Kanal, nie als synthetisches Atmen auf der Übersicht.
    const html = render();
    expect(html).not.toContain('vc-wave');
    expect(html).not.toContain('idle__wave');
    expect(html).not.toContain('<canvas');
  });
});

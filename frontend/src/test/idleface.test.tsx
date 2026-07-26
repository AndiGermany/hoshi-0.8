import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  ALARM_PROGRESS_WINDOW_MS,
  IdleFace,
  SHOPPING_VISIBLE_COUNT,
  alarmLineText,
  alarmProgress,
  diaryTodayStats,
  fmtP50,
  fmtPrecip,
  nextAlarm,
  statusChips,
  todayTileValue,
  weatherCategory,
  weatherNowContent,
} from '../components/IdleFace';
import type { ScheduledItem } from '../hooks/useScheduledItems';
import type { DiaryTurn } from '../hooks/useDiary';
import type { WeatherToday, WeatherTodayState } from '../hooks/useWeatherToday';
import type { ListItem } from '../api/lists';

/* Feste LOKALE Zeitpunkte (kein UTC-String) → TZ-unabhängige Tests:
   Samstag, 4. Juli 2026, 07:04 Ortszeit. */
const NOW = new Date(2026, 6, 4, 7, 4).getTime();
const H = 60 * 60 * 1000;

const alarm = (dueAtEpochMs: number, id = 'a1', label?: string): ScheduledItem => ({
  id,
  kind: 'ALARM',
  dueAtEpochMs,
  ...(label ? { label } : {}),
});
const timer = (dueAtEpochMs: number, id = 't1', label?: string): ScheduledItem => ({
  id,
  kind: 'TIMER',
  dueAtEpochMs,
  ...(label ? { label } : {}),
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

const shopItem = (over: Partial<ListItem> = {}): ListItem => ({
  id: 's-1',
  text: 'Milch',
  quantity: 1,
  addedAtEpochMs: 1,
  ...over,
});

const render = (over: Partial<Parameters<typeof IdleFace>[0]> = {}) =>
  renderToStaticMarkup(
    <IdleFace
      nowMs={NOW}
      health="up"
      voice={null}
      scheduled={[]}
      weather={null}
      shopping={[]}
      {...over}
    />,
  );

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

  it('fmtPrecip: ganze mm ohne Nachkommastelle, gebrochene mit Komma (de)', () => {
    expect(fmtPrecip(3)).toBe('3');
    expect(fmtPrecip(1.2)).toBe('1,2');
    expect(fmtPrecip(0.4)).toBe('0,4');
    expect(fmtPrecip(1.2, 'en-US')).toBe('1.2');
  });

  it('weatherNowContent: live liefert Lage/Spanne/Regen-Zeile + Icon-Kategorie getrennt', () => {
    const live = weatherNowContent(liveWeather);
    expect(live).toEqual({
      kind: 'live',
      cond: 'bedeckt',
      span: '18–29°',
      precip: 'trocken',
      category: 'cloudy',
    });

    const withRain = weatherNowContent({
      kind: 'live',
      data: { ...heute29, codeText: 'leichter Regen', precipMm: 3 },
    });
    expect(withRain).toEqual({
      kind: 'live',
      cond: 'leichter Regen',
      span: '18–29°',
      precip: '3 mm Regen heute',
      category: 'rain',
    });
  });

  it('weatherCategory: feste Zuordnung gegen die 28 bekannten WMO-Texte + Fallback', () => {
    expect(weatherCategory('klar und sonnig')).toBe('clear');
    expect(weatherCategory('überwiegend klar')).toBe('clear');
    expect(weatherCategory('teilweise bewölkt')).toBe('partly');
    expect(weatherCategory('bedeckt')).toBe('cloudy');
    expect(weatherCategory('neblig')).toBe('fog');
    expect(weatherCategory('gefrierender Nebel')).toBe('fog');
    expect(weatherCategory('leichter Nieselregen')).toBe('rain');
    expect(weatherCategory('starker Regen')).toBe('rain');
    expect(weatherCategory('mäßige Regenschauer')).toBe('rain');
    expect(weatherCategory('leichter Schneefall')).toBe('snow');
    expect(weatherCategory('Schneekörner')).toBe('snow');
    expect(weatherCategory('starke Schneeschauer')).toBe('snow'); // „schauer" enthalten, aber Schnee zuerst geprüft
    expect(weatherCategory('Gewitter mit Hagel')).toBe('thunder');
    expect(weatherCategory('wechselhaft')).toBe('cloudy'); // Fallback des Backends
  });

  it('weatherNowContent (Gap-Zustände): off/unreachable/lädt liefern je eine ehrliche Lücken-Zeile', () => {
    expect(weatherNowContent({ kind: 'off' })).toEqual({
      kind: 'gap',
      text: 'Kommt — ehrlich leer statt erfunden. Wetter ist bei diesem Deploy ausgeschaltet.',
    });
    expect(weatherNowContent({ kind: 'unreachable' })).toEqual({
      kind: 'gap',
      text: 'Wetter grad nicht lesbar — hier steht nichts Erfundenes.',
    });
    expect(weatherNowContent(null)).toEqual({ kind: 'gap', text: 'Wird gerade gelesen.' });
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

describe('IdleFace — das Flur-Display-Layout', () => {
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

  it('Jetzt-Band: Wetterlage, Tagesspanne, die Regen-Zeile bei precipMm > 0 — und ein dezentes Lage-Icon', () => {
    const html = render({
      weather: { kind: 'live', data: { ...heute29, codeText: 'starker Regen', precipMm: 3 } },
    });
    expect(html).toContain('idle__nowcond');
    expect(html).toContain('starker Regen');
    expect(html).toContain('18–29°');
    expect(html).toContain('3 mm Regen heute');
    expect(html).toContain('idle__nowicon');
    expect(html).toContain('glyph--rain-cloud'); // Regenwolke, kein Emoji
  });

  it('Jetzt-Band: das Lage-Icon folgt der Kategorie (Sonne/Wolke+Sonne/Nebel/Schnee/Gewitter)', () => {
    const withCond = (codeText: string) =>
      render({ weather: { kind: 'live', data: { ...heute29, codeText } } });
    expect(withCond('klar und sonnig')).toContain('glyph--sun');
    expect(withCond('teilweise bewölkt')).toContain('glyph--cloud-sun');
    expect(withCond('bedeckt')).toContain('glyph--cloud');
    expect(withCond('neblig')).toContain('glyph--fog');
    expect(withCond('leichter Schneefall')).toContain('glyph--snow-cloud');
    expect(withCond('Gewitter')).toContain('glyph--thunder-cloud');
  });

  it('Jetzt-Band trägt KEIN Settings-Zahnrad mehr (Andi-Korrektur 26.07 — die Top-Nav hat schon eines)', () => {
    const html = render({ weather: liveWeather });
    expect(html).not.toContain('ctxgear');
  });

  it('Jetzt-Band: precipMm === 0 ⇒ „trocken" statt „0 mm"', () => {
    const html = render({ weather: liveWeather });
    expect(html).toContain('trocken');
    expect(html).not.toContain('0 mm');
  });

  it('Jetzt-Band: Wetter beim Deploy aus (404) ⇒ ehrliche Lücken-Zeile, keine erfundenen Grade', () => {
    const html = render({ weather: { kind: 'off' } });
    expect(html).toContain('idle__nowgap');
    expect(html).toContain('ehrlich leer statt erfunden');
    expect(html).not.toContain('°');
  });

  it('Jetzt-Band: nicht lesbar ⇒ ehrliche Notiz, nie Fake-Grade', () => {
    const html = render({ weather: { kind: 'unreachable' } });
    expect(html).toContain('Wetter grad nicht lesbar');
    expect(html).not.toContain('°');
  });

  it('Jetzt-Band lädt (weather=null) ⇒ „Wird gerade gelesen."', () => {
    const html = render({ weather: null });
    expect(html).toContain('Wird gerade gelesen.');
  });

  it('„Läuft"-Karte: echte Countdowns mit Labels, sortiert wie vom Hook geliefert', () => {
    const html = render({
      scheduled: [
        alarm(new Date(2026, 6, 4, 12, 4).getTime(), 'nudeln', 'Nudeln'),
        timer(NOW + 38 * 60_000, 'waesche', 'Wäsche'),
      ],
    });
    expect(html).toContain('idle__cardlist');
    expect(html).toContain('12:04 Nudeln');
    expect(html).toContain('38 min Wäsche');
  });

  it('„Läuft"-Karte VERSCHWINDET, wenn nichts läuft (kein „Nichts geplant"-Text mehr)', () => {
    const html = render({ scheduled: [] });
    expect(html).not.toContain('idle__cardlist');
    expect(html).not.toContain('Nichts geplant');
  });

  it('Einkaufs-Karte: erste Einträge + „+N weitere", Menge als „2×"', () => {
    const items: ListItem[] = [
      shopItem({ id: '1', text: 'Milch', quantity: 2 }),
      shopItem({ id: '2', text: 'Brot' }),
      shopItem({ id: '3', text: 'Butter' }),
      shopItem({ id: '4', text: 'Eier' }),
      shopItem({ id: '5', text: 'Käse' }),
    ];
    const html = render({ shopping: items });
    expect(html).toContain('2×');
    expect(html).toContain('Milch');
    expect(html).toContain('Eier'); // vierter (letzter sichtbarer) Eintrag — Reihenfolge kommt 1:1 vom Client
    expect(html).not.toContain('Käse'); // fünfter Eintrag ist NICHT mehr direkt sichtbar
    expect(html).toContain('+1 weitere');
    expect(items.length - SHOPPING_VISIBLE_COUNT).toBe(1);
  });

  it('Einkaufs-Karte VERSCHWINDET, wenn die Liste leer ist (kein Fehler-Banner)', () => {
    const html = render({ shopping: [] });
    expect(html).not.toContain('idle__cardqty');
    expect(html).not.toContain('Einkauf');
  });

  it('beide Haushalts-Karten leer ⇒ kein Karten-Container im Markup', () => {
    const html = render({ scheduled: [], shopping: [] });
    expect(html).not.toContain('idle__tiles');
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

  it('rendert KEINE „Heute"-Turn-Statistik mehr (zog in die Diagnose-Sektion auf Aktivität um)', () => {
    const html = render({
      scheduled: [alarm(NOW + H)],
      weather: liveWeather,
      shopping: [shopItem()],
    });
    expect(html).not.toContain('Aussetzer');
    expect(html).not.toContain('>Heute<'); // die frühere Kachel-Titel-Zeile
  });
});

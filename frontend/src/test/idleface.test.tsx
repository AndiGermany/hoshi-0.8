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
  rainOnsetEpochMs,
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

  it('weatherNowContent: live liefert Lage/Spanne/Regen-Zeile + Icon-Kategorie getrennt (ohne current/morgen/sonne: alle Zusatzzeilen null)', () => {
    const live = weatherNowContent(liveWeather, NOW);
    expect(live).toEqual({
      kind: 'live',
      nowTemp: null,
      cond: 'bedeckt',
      span: '18–29°',
      precip: 'trocken',
      category: 'cloudy',
      tomorrow: null,
      rainFrom: null,
      sun: null,
    });

    const withRain = weatherNowContent(
      { kind: 'live', data: { ...heute29, codeText: 'leichter Regen', precipMm: 3 } },
      NOW,
    );
    expect(withRain).toEqual({
      kind: 'live',
      nowTemp: null,
      cond: 'leichter Regen',
      span: '18–29°',
      precip: '3 mm Regen heute',
      category: 'rain',
      tomorrow: null,
      rainFrom: null,
      sun: null,
    });
  });

  it('weatherNowContent: Jetzt-Temperatur/-Lage aus current, wenn das Backend sie liefert', () => {
    const withNow = weatherNowContent(
      { kind: 'live', data: { ...heute29, nowTemp: 22, nowCodeText: 'starker Regen' } },
      NOW,
    );
    expect(withNow.kind).toBe('live');
    if (withNow.kind !== 'live') throw new Error('unreachable');
    expect(withNow.nowTemp).toBe('22°');
    expect(withNow.cond).toBe('starker Regen'); // Jetzt-Lage, NICHT die Tagesbedingung (bedeckt)
    expect(withNow.category).toBe('rain'); // Icon folgt der Jetzt-Lage, nicht der Tagesbedingung
  });

  it('weatherNowContent: Morgen-Zeile NUR mit allen drei Morgen-Feldern, sonst null', () => {
    const complete = weatherNowContent(
      { kind: 'live', data: { ...heute29, tomorrowMin: 12, tomorrowMax: 22, tomorrowCodeText: 'sonnig' } },
      NOW,
    );
    if (complete.kind !== 'live') throw new Error('unreachable');
    expect(complete.tomorrow).toBe('morgen 12–22°, sonnig');

    const partial = weatherNowContent(
      { kind: 'live', data: { ...heute29, tomorrowMin: 12 } }, // max/codeText fehlen
      NOW,
    );
    if (partial.kind !== 'live') throw new Error('unreachable');
    expect(partial.tomorrow).toBeNull();
  });

  it('rainOnsetEpochMs: erste Stunde >20% Regenwahrscheinlichkeit, sonst null (kein Zahlenfriedhof)', () => {
    expect(rainOnsetEpochMs(undefined)).toBeNull();
    expect(rainOnsetEpochMs([])).toBeNull();
    expect(
      rainOnsetEpochMs([
        { epochMs: 1, tempC: 10, precipProbability: 15 },
        { epochMs: 2, tempC: 10, precipProbability: 20 }, // genau 20 zählt NICHT (Schwelle ist ">")
        { epochMs: 3, tempC: 10, precipProbability: 25 },
        { epochMs: 4, tempC: 10, precipProbability: 90 },
      ]),
    ).toBe(3); // die ERSTE Stunde über der Schwelle, nicht die höchste
  });

  it('weatherNowContent: Regen-ab-Zeile erscheint nur, wenn eine Stunde die 20%-Schwelle überschreitet', () => {
    const noRain = weatherNowContent(
      { kind: 'live', data: { ...heute29, hourly: [{ epochMs: 1, tempC: 10, precipProbability: 15 }] } },
      NOW,
    );
    if (noRain.kind !== 'live') throw new Error('unreachable');
    expect(noRain.rainFrom).toBeNull();

    const withRainHour = new Date(2026, 6, 4, 17, 0).getTime();
    const rain = weatherNowContent(
      {
        kind: 'live',
        data: { ...heute29, hourly: [{ epochMs: withRainHour, tempC: 10, precipProbability: 45 }] },
      },
      NOW,
    );
    if (rain.kind !== 'live') throw new Error('unreachable');
    expect(rain.rainFrom).toBe('Regen ab ~17:00');
  });

  it('weatherNowContent: Sonnen-Zeile — „hell ab" vor Sonnenaufgang, „hell bis" danach, null ohne Daten', () => {
    const sunrise = new Date(2026, 6, 4, 5, 32).getTime();
    const sunset = new Date(2026, 6, 4, 21, 34).getTime();

    const beforeSunrise = weatherNowContent(
      { kind: 'live', data: { ...heute29, sunriseEpochMs: sunrise, sunsetEpochMs: sunset } },
      new Date(2026, 6, 4, 4, 0).getTime(), // vor Sonnenaufgang
    );
    if (beforeSunrise.kind !== 'live') throw new Error('unreachable');
    expect(beforeSunrise.sun).toBe('hell ab 05:32');

    const afterSunrise = weatherNowContent(
      { kind: 'live', data: { ...heute29, sunriseEpochMs: sunrise, sunsetEpochMs: sunset } },
      NOW, // 07:04 — nach Sonnenaufgang
    );
    if (afterSunrise.kind !== 'live') throw new Error('unreachable');
    expect(afterSunrise.sun).toBe('hell bis 21:34');

    const noSunData = weatherNowContent({ kind: 'live', data: heute29 }, NOW);
    if (noSunData.kind !== 'live') throw new Error('unreachable');
    expect(noSunData.sun).toBeNull();
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
    expect(weatherNowContent({ kind: 'off' }, NOW)).toEqual({
      kind: 'gap',
      text: 'Kommt — ehrlich leer statt erfunden. Wetter ist bei diesem Deploy ausgeschaltet.',
    });
    expect(weatherNowContent({ kind: 'unreachable' }, NOW)).toEqual({
      kind: 'gap',
      text: 'Wetter grad nicht lesbar — hier steht nichts Erfundenes.',
    });
    expect(weatherNowContent(null, NOW)).toEqual({ kind: 'gap', text: 'Wird gerade gelesen.' });
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

  // ── Neu (Flur-Fertigstellung 2026-07-27): Jetzt-Temperatur, Morgen, Regen-ab
  // und Sonnenzeiten erscheinen/verschwinden EINZELN ehrlich — je nachdem, ob
  // das Backend die additiven Felder liefert. ────────────────────────────────

  it('Jetzt-Band: Jetzt-Temperatur + Jetzt-Lage groß, wenn das Backend current liefert', () => {
    const html = render({
      weather: { kind: 'live', data: { ...heute29, nowTemp: 22, nowCodeText: 'leichter Regen' } },
    });
    expect(html).toContain('idle__nowtemp');
    expect(html).toContain('22°');
    expect(html).toContain('leichter Regen'); // Jetzt-Lage, nicht „bedeckt" (Tagesbedingung)
    expect(html).toContain('18–29°'); // Tagesspanne bleibt als zweite Zeile da
  });

  it('Jetzt-Band: OHNE current-Daten bleibt der ehrliche Fallback (nur Tagesbedingung, keine erfundene Jetzt-Temperatur)', () => {
    const html = render({ weather: liveWeather }); // heute29 hat kein nowTemp/nowCodeText
    expect(html).not.toContain('idle__nowtemp');
    expect(html).toContain('bedeckt'); // Tagesbedingung bleibt die Kopfzeile
  });

  it('Jetzt-Band: Morgen-Zeile erscheint mit allen drei Morgen-Feldern, sonst nicht', () => {
    const withTomorrow = render({
      weather: {
        kind: 'live',
        data: { ...heute29, tomorrowMin: 12, tomorrowMax: 22, tomorrowCodeText: 'sonnig' },
      },
    });
    expect(withTomorrow).toContain('morgen 12–22°, sonnig');

    const without = render({ weather: liveWeather });
    expect(without).not.toContain('morgen');
  });

  it('Jetzt-Band: Regen-ab-Zeile NUR bei einer Stunde über der 20%-Schwelle', () => {
    const withRain = render({
      weather: {
        kind: 'live',
        data: {
          ...heute29,
          hourly: [{ epochMs: new Date(2026, 6, 4, 17, 0).getTime(), tempC: 15, precipProbability: 45 }],
        },
      },
    });
    expect(withRain).toContain('Regen ab ~17:00');

    const belowThreshold = render({
      weather: {
        kind: 'live',
        data: { ...heute29, hourly: [{ epochMs: 1, tempC: 15, precipProbability: 15 }] },
      },
    });
    expect(belowThreshold).not.toContain('Regen ab');
  });

  it('Jetzt-Band: leise Sonnen-Zeile „hell bis …" mit Sonnenauf-/-untergangsdaten, sonst weg', () => {
    const withSun = render({
      weather: {
        kind: 'live',
        data: {
          ...heute29,
          sunriseEpochMs: new Date(2026, 6, 4, 5, 32).getTime(),
          sunsetEpochMs: new Date(2026, 6, 4, 21, 34).getTime(),
        },
      },
    });
    expect(withSun).toContain('hell bis 21:34');
    expect(withSun).toContain('idle__nowline--sun');

    const withoutSun = render({ weather: liveWeather });
    expect(withoutSun).not.toContain('hell bis');
    expect(withoutSun).not.toContain('hell ab');
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

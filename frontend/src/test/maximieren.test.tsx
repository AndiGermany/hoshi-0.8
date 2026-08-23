import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  NewsMaxOverlay,
  WeatherMaxOverlay,
  daylight,
} from '../components/MaximizeOverlay';
import { CurrentAffairsWindow, CURRENT_AFFAIRS_EXPANDED_COUNT } from '../components/CurrentAffairsTile';
import { parseCurrentAffairs, type CurrentAffairsState } from '../hooks/useCurrentAffairs';
import { parseWeatherToday, type WeatherTodayState } from '../hooks/useWeatherToday';
import { de } from '../i18n/de';
import { en } from '../i18n/en';
import { es } from '../i18n/es';
import { fr } from '../i18n/fr';
import { it as itCatalog } from '../i18n/it';

/**
 * **maximieren.test** — die Vollbild-Ansichten (Andi 23.08.: „füge einen
 * ‚maximieren' hier und beim wetter an, wo man alle auswahlen, alle
 * informationen vernünftig angezeigt bekommt :)").
 *
 * Beide Komponenten sind rein prop-getrieben (kein Fetch, kein Timer, kein
 * DOM), also reicht `renderToStaticMarkup` — dasselbe Idiom wie
 * `currentaffairs.test.tsx`/`overlay.test.tsx`.
 *
 * WAS HIER GERIEGELT WIRD, ist genau das, was die Bestellung verspricht:
 *  1. ALLE Meldungen, nicht die sechs der Kachel,
 *  2. die Quellen-Chips sind die bestehenden Abzeichen und erscheinen nur,
 *     wenn es wirklich mehr als eine Quelle gibt,
 *  3. das Wetter zeigt jeden Abschnitt NUR mit echten Daten,
 *  4. fünf Sprachen tragen jeden neuen Text.
 *
 * Was NICHT hier steht: dass der Kasten aufgeht, filtert und auf Escape wieder
 * zugeht. Das ist Verhalten in einem echten Browser und wird dort gemessen —
 * `tools/zuhause-probe/maximieren.mjs` (Chrome) und `firefox.mjs` Schritt 7.
 */

const NOW_MS = Date.parse('2026-08-23T12:00:00Z');

/* ── Nachrichten-Fixture (vertragsförmiges Wire-JSON) ───────────────────── */

function wireItem(n: number, source = 'TAGESSCHAU'): Record<string, unknown> {
  return {
    id: `item-${n}`,
    source,
    title: `Headline number ${n}`,
    snippet: `Teaser text of headline ${n}.`,
    attribution: `${source} attribution`,
    canonicalUrl: `https://example.test/article-${n}`,
    publishedAt: '2026-08-23T08:30:00Z',
    fetchedAt: '2026-08-23T08:45:00Z',
  };
}

function newsState(count: number, sources: string[] = ['TAGESSCHAU']): CurrentAffairsState {
  const data = parseCurrentAffairs({
    items: Array.from({ length: count }, (_, i) => wireItem(i + 1, sources[i % sources.length])),
    observedAt: '2026-08-23T11:59:00Z',
    lastSuccessfulRefreshAt: '2026-08-23T08:45:00Z',
    freshness: 'FRESH',
  });
  if (data === null) throw new Error('fixture does not satisfy the contract');
  return { kind: 'live', data };
}

const renderNews = (state: CurrentAffairsState | null, open = true) =>
  renderToStaticMarkup(
    <NewsMaxOverlay
      open={open}
      onClose={() => {}}
      state={state}
      nowMs={NOW_MS}
      idleFace={de.idleFace}
      locale={de.locale}
    />,
  );

/* ── Wetter-Fixture ─────────────────────────────────────────────────────── */

const HOUR = 3600_000;
function weatherState(over: Record<string, unknown> = {}): WeatherTodayState {
  const data = parseWeatherToday({
    label: 'Duisburg',
    todayMin: 15,
    todayMax: 23,
    codeText: 'teilweise bewoelkt',
    precipMm: 2.4,
    nowTemp: 21,
    nowCodeText: 'teilweise bewoelkt',
    tomorrowMin: 13,
    tomorrowMax: 19,
    tomorrowCodeText: 'Regenschauer',
    sunriseEpochMs: NOW_MS - 6 * HOUR,
    sunsetEpochMs: NOW_MS + 8 * HOUR + 26 * 60_000,
    hourly: [
      { epochMs: NOW_MS, tempC: 21, precipProbability: 10 },
      { epochMs: NOW_MS + HOUR, tempC: 22, precipProbability: 40 },
    ],
    outlook: [
      { offset: 0, dateIso: '2026-08-23', tempMin: 15, tempMax: 23, codeText: 'teilweise bewoelkt', precipMm: 2.4 },
      { offset: 1, dateIso: '2026-08-24', tempMin: 14, tempMax: 21, codeText: 'leichter Regen', precipMm: 1.1 },
    ],
    ...over,
  });
  if (data === null) throw new Error('fixture does not satisfy the contract');
  return { kind: 'live', data };
}

const renderWeather = (weather: WeatherTodayState | null) =>
  renderToStaticMarkup(
    <WeatherMaxOverlay
      open
      onClose={() => {}}
      weather={weather}
      nowMs={NOW_MS}
      idleFace={de.idleFace}
      locale={de.locale}
    />,
  );

/* ── 1 · Nachrichten: ALLE Meldungen, nicht der Kachel-Deckel ───────────── */

describe('Nachrichten maximiert — der Deckel der Kachel gilt hier nicht', () => {
  it('zeigt ALLE 20 Meldungen, die Kachel dagegen nur sechs', () => {
    const state = newsState(20);
    const gross = renderNews(state);
    const kachel = renderToStaticMarkup(<CurrentAffairsWindow state={state} nowMs={NOW_MS} size="L" />);

    expect(gross.split('widgetmax__newsitem').length - 1).toBe(20);
    expect(kachel.split('idle__newsitem').length - 1).toBe(CURRENT_AFFAIRS_EXPANDED_COUNT);
    // Die Meldung, die auf der Kachel als „+14 weitere" gezaehlt wurde, steht hier.
    expect(kachel).not.toContain('Headline number 20');
    expect(gross).toContain('Headline number 20');
  });

  it('jede Meldung traegt Titel, Teaser, Quelle, Attribution, Alter UND Uhrzeit', () => {
    const html = renderNews(newsState(1));
    expect(html).toContain('Headline number 1');
    expect(html).toContain('Teaser text of headline 1.');
    expect(html).toContain('TAGESSCHAU');
    expect(html).toContain('TAGESSCHAU attribution');
    expect(html).toContain(de.idleFace.homeTiles.age.hoursAgo(3));
    expect(html).toContain(de.idleFace.currentAffairs.openSource);
    // Der Teaser wird NICHT geklemmt — „vernuenftig angezeigt" heisst ganz gelesen.
    expect(html).not.toContain('line-clamp');
  });

  it('der Link oeffnet die canonicalUrl in einem neuen Tab (wie auf der Kachel)', () => {
    const html = renderNews(newsState(1));
    expect(html).toContain('href="https://example.test/article-1"');
    expect(html).toContain('target="_blank"');
    expect(html).toContain('rel="noopener"');
  });

  it('die Bilanz zaehlt ehrlich', () => {
    expect(renderNews(newsState(8))).toContain(de.idleFace.currentAffairs.countInfo(8, 8));
  });

  it('kein Zustand ⇒ kein Inhalt, aber ein benannter Dialog (die Huelle bleibt montiert)', () => {
    const html = renderNews(null);
    expect(html).toContain('aria-label="Nachrichten"');
    expect(html).not.toContain('widgetmax__newsitem');
  });
});

describe('Quellen-Chips — die bestehenden Abzeichen als Filter, kein neues Konzept', () => {
  it('EINE Quelle ⇒ keine Chips (eine Wahl ohne Unterschied waere Laerm)', () => {
    expect(renderNews(newsState(4))).not.toContain('widgetmax__chip');
  });

  it('ZWEI Quellen ⇒ „Alle Quellen" + je ein Chip, in der Reihenfolge des Auftretens', () => {
    const html = renderNews(newsState(4, ['TAGESSCHAU', 'HEISE']));
    expect(html).toContain(de.idleFace.currentAffairs.allSources);
    expect(html).toContain(de.idleFace.currentAffairs.sourceFilterAria);
    // Auf die vollstaendige Klasse zaehlen: `widgetmax__chips` (die Gruppe)
    // enthaelt den Namen des Kindes als Teilkette.
    expect(html.split('class="widgetmax__chip"').length - 1).toBe(3); // Alle + 2 Quellen
    // Der Zustand steht in `aria-pressed`, nicht nur in der Farbe.
    expect(html).toContain('aria-pressed="true"');
    expect(html.indexOf('TAGESSCHAU')).toBeLessThan(html.indexOf('HEISE'));
  });
});

/* ── 2 · Wetter: jeder Abschnitt nur mit echten Daten ───────────────────── */

describe('Wetter maximiert — nur, was der Vertrag hergibt', () => {
  it('volle Antwort ⇒ vier Abschnitte, Ort im Titel, Tageslaenge gerechnet', () => {
    const html = renderWeather(weatherState());
    const s = de.idleFace.wetter.sections;
    expect(html).toContain('Wetter · Duisburg');
    for (const titel of [s.now, s.hourly, s.days, s.sun]) expect(html).toContain(titel);
    expect(html).toContain('15–23°');
    expect(html).toContain('13–19° · Regenschauer');
    // 6 h vor bis 8 h 26 min nach `nowMs` = 14 h 26 min.
    expect(html).toContain(s.daylightValue({ h: 14, min: 26 }));
    // Der Dezimaltrenner ist der der Sprache — nicht der von `String(2.4)`.
    expect(html).toContain('2,4 mm');
  });

  it('ohne `hourly` faellt der Stundenverlauf weg, statt leer dazustehen', () => {
    const html = renderWeather(weatherState({ hourly: undefined }));
    expect(html).not.toContain(de.idleFace.wetter.sections.hourly);
    expect(html).toContain(de.idleFace.wetter.sections.days);
  });

  it('ohne `outlook` faellt die Mehrtage-Zeile weg', () => {
    const html = renderWeather(weatherState({ outlook: undefined }));
    expect(html).not.toContain(de.idleFace.wetter.sections.days);
  });

  it('nur EINE Sonnenzeit ⇒ gar kein Sonnen-Abschnitt (Auf- und Untergang sind ein Paar)', () => {
    const html = renderWeather(weatherState({ sunsetEpochMs: undefined }));
    expect(html).not.toContain(de.idleFace.wetter.sections.sun);
  });

  it('ohne Morgen-Werte fehlt die Morgen-Spalte, der Rest bleibt', () => {
    const html = renderWeather(weatherState({ tomorrowCodeText: undefined }));
    expect(html).not.toContain(de.idleFace.wetter.sections.tomorrow);
    expect(html).toContain(de.idleFace.wetter.sections.span);
  });

  it('Luecken-Zustaende sagen denselben ehrlichen Satz wie die Kachel', () => {
    expect(renderWeather(null)).toContain(de.idleFace.wetter.loadingNote);
    expect(renderWeather({ kind: 'off' })).toContain(de.idleFace.wetter.offNote);
    expect(renderWeather({ kind: 'unreachable' })).toContain(de.idleFace.wetter.unreachableNote);
    // Und KEINEN erfundenen Abschnitt daneben.
    expect(renderWeather({ kind: 'off' })).not.toContain(de.idleFace.wetter.sections.now);
  });
});

describe('daylight — gerechnet, nicht geraten', () => {
  it('rechnet Minuten in Stunden + Minuten um', () => {
    expect(daylight(0, 14 * HOUR + 26 * 60_000)).toEqual({ h: 14, min: 26 });
  });
  it('ein Untergang VOR dem Aufgang (Polarnacht/kaputte Daten) ergibt 0, nie eine negative Laenge', () => {
    expect(daylight(5 * HOUR, HOUR)).toEqual({ h: 0, min: 0 });
  });
});

/* ── 3 · Fünf Sprachen ──────────────────────────────────────────────────── */

describe('i18n — jeder neue Text existiert in allen fuenf Sprachen', () => {
  const KATALOGE = [
    ['de', de],
    ['en', en],
    ['es', es],
    ['fr', fr],
    ['it', itCatalog],
  ] as const;

  it('Maximieren/Schliessen/ARIA und die Wetter-Abschnitte sind ueberall gefuellt', () => {
    for (const [name, katalog] of KATALOGE) {
      const t = katalog.idleFace;
      for (const [feld, wert] of [
        ['maximieren.open', t.maximieren.open],
        ['maximieren.close', t.maximieren.close],
        ['maximieren.openAria', t.maximieren.openAria('X')],
        ['currentAffairs.allSources', t.currentAffairs.allSources],
        ['currentAffairs.sourceFilterAria', t.currentAffairs.sourceFilterAria],
        ['currentAffairs.countInfo', t.currentAffairs.countInfo(2, 5)],
        ...Object.entries(t.wetter.sections).map(([k, v]) => [
          `wetter.sections.${k}`,
          typeof v === 'function' ? v({ h: 1, min: 2 }) : v,
        ]),
      ] as Array<[string, string]>) {
        expect(wert, `${name}: ${feld} ist leer`).toBeTruthy();
        expect(wert.trim(), `${name}: ${feld} ist Leerraum`).not.toBe('');
      }
      // Der ARIA-Text muss den Kachelnamen wirklich einsetzen.
      expect(t.maximieren.openAria('Wetter'), `${name}: openAria ignoriert den Namen`).toContain('Wetter');
      // Und die Bilanz muss beide Zahlen tragen, sobald gefiltert ist.
      expect(t.currentAffairs.countInfo(2, 5)).toContain('2');
      expect(t.currentAffairs.countInfo(2, 5)).toContain('5');
    }
  });

  it('ungefiltert nennt die Bilanz nur EINE Zahl (5 von 5 waere Papierkram)', () => {
    for (const [name, katalog] of KATALOGE) {
      const text = katalog.idleFace.currentAffairs.countInfo(5, 5);
      expect(text, `${name}`).toContain('5');
      expect(text.match(/5/g)?.length, `${name}: nennt die 5 zweimal`).toBe(1);
    }
  });
});

import { describe, expect, it, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { outlookColumns, parseIsoDay } from '../components/weatherOutlook';
import { weatherNowContent, weatherTileBody } from '../components/IdleFace';
import { parseWeatherToday, type DayOutlook } from '../hooks/useWeatherToday';
import { setActiveUiLanguage } from '../i18n';
import { de } from '../i18n/de';
import { en } from '../i18n/en';
import { es } from '../i18n/es';
import { fr } from '../i18n/fr';
import { it as itCatalog } from '../i18n/it';

/**
 * **Die Mehrtage-Zeile der XL-Wetterkachel** (Andi 21.08.), gespeist aus dem
 * seit 21.08. additiven Wire-Feld `outlook`
 * (`vault/tracks/RESULT-wetter-mehrtage-2026-08-21.md` §1.2/§3).
 *
 * Gepinnt wird die **Bindung an echte Felder** und die Ehrlichkeit drumherum:
 *  - die Wire-Namen sind `tempMin`/`tempMax` — das BE hat unterwegs gelernt,
 *    dass `tMin` über Jackson als `tmin` gelandet wäre; ein FE, das beides
 *    schluckt, hätte diesen Fehler wieder eingebaut,
 *  - ohne `outlook` (Alt-Backend) erscheint die Zeile GAR NICHT,
 *  - ein Tag mit kaputtem `dateIso` fällt EINZELN raus, nicht die ganze Zeile,
 *  - `precipProbability` fehlt ⇒ kein Prozent-Anhang, nie „0 %",
 *  - die Wochentags-Kürzel kommen aus ICU (fünf Sprachen), nicht aus einer
 *    zweiten handgepflegten Tabelle im FE.
 *
 * Die Fixture-Tage sind echte Kalendertage ab **Fr, 21.08.2026** — `parseIsoDay`
 * baut ein LOKALES Datum, die Wochentage stimmen deshalb in jeder Zeitzone.
 */

/** Sieben Tage im BE-Format, beginnend heute (`offset: 0`). */
function sieben(): DayOutlook[] {
  const tage: Array<[string, number, number, string, number, number | undefined]> = [
    ['2026-08-21', 15, 23, 'teilweise bewölkt', 0, 10],
    ['2026-08-22', 14, 21, 'leichter Regen', 3.4, 60],
    ['2026-08-23', 13, 19, 'Regenschauer', 5.1, 80],
    ['2026-08-24', 12, 20, 'bedeckt', 0, 20],
    ['2026-08-25', 14, 24, 'klar und sonnig', 0, undefined],
    ['2026-08-26', 16, 27, 'klar und sonnig', 0, 5],
    ['2026-08-27', 15, 25, 'teilweise bewölkt', 0.2, 15],
  ];
  return tage.map(([dateIso, tempMin, tempMax, codeText, precipMm, prob], offset) => {
    const day: DayOutlook = { offset, dateIso, tempMin, tempMax, codeText, precipMm };
    if (prob !== undefined) day.precipProbability = prob;
    return day;
  });
}

afterEach(() => setActiveUiLanguage('de'));

describe('parseIsoDay — ein Tag ist ein Kalendertag, keine Zeitzone', () => {
  it('liest `YYYY-MM-DD` als LOKALES Datum (nicht als UTC-Mitternacht)', () => {
    // `new Date('2026-08-21')` wäre UTC-Mitternacht — westlich von Greenwich
    // hieße der Wochentag dann Donnerstag. Genau diese Drift vermeidet der
    // BE-Vertrag mit `dateIso` statt Epoch-ms; das FE darf sie nicht wieder
    // einführen.
    const d = parseIsoDay('2026-08-21');
    expect(d?.getFullYear()).toBe(2026);
    expect(d?.getMonth()).toBe(7);
    expect(d?.getDate()).toBe(21);
  });

  it('weist alles zurück, was kein Kalendertag ist — inklusive stiller Roll-over', () => {
    expect(parseIsoDay('')).toBeNull();
    expect(parseIsoDay('heute')).toBeNull();
    expect(parseIsoDay('2026-8-21')).toBeNull();
    expect(parseIsoDay('2026-08-21T12:00:00Z')).toBeNull();
    // `new Date(2026, 1, 31)` wäre klaglos der 3. März geworden.
    expect(parseIsoDay('2026-02-31')).toBeNull();
    expect(parseIsoDay('2026-13-01')).toBeNull();
  });
});

describe('outlookColumns — echte Tage, sonst nichts', () => {
  it('kein `outlook` (Alt-Backend) ⇒ leere Liste ⇒ keine Zeile', () => {
    expect(outlookColumns(undefined)).toEqual([]);
    expect(outlookColumns([])).toEqual([]);
  });

  it('sieben Wire-Tage ergeben sieben Spalten — keine Obergrenze im Code', () => {
    const cols = outlookColumns(sieben());
    expect(cols).toHaveLength(7);
    expect(cols.map((c) => c.key)).toEqual(sieben().map((d) => d.dateIso));
  });

  it('die Spalte trägt Kürzel, Spanne und den BE-Lagentext unverändert', () => {
    const [heute, morgen] = outlookColumns(sieben());
    expect(heute.weekday).toBe('Fr');
    expect(heute.span).toBe('15–23°');
    expect(heute.codeText).toBe('teilweise bewölkt');
    expect(heute.today).toBe(true);
    expect(morgen.weekday).toBe('Sa');
    expect(morgen.today).toBe(false);
  });

  it('nur `offset === 0` ist HEUTE — die Zeile färbt, statt ein Wort zu erfinden', () => {
    expect(outlookColumns(sieben()).filter((c) => c.today)).toHaveLength(1);
  });

  it('ein Tag mit kaputtem `dateIso` fällt EINZELN raus, die übrigen bleiben', () => {
    const kaputt = sieben();
    kaputt[2] = { ...kaputt[2], dateIso: 'übermorgen' };
    const cols = outlookColumns(kaputt);
    expect(cols).toHaveLength(6);
    expect(cols.map((c) => c.key)).not.toContain('übermorgen');
  });

  it('Tooltip: Wochentag lang + Spanne + Lage; Regenwahrscheinlichkeit NUR wenn geliefert', () => {
    const cols = outlookColumns(sieben());
    expect(cols[1].title).toBe('Samstag, 14–21°, leichter Regen · 60 % Regen');
    // Tag 5 (Index 4) hat KEINE Wahrscheinlichkeit — kein „0 %", gar nichts.
    expect(cols[4].title).toBe('Dienstag, 14–24°, klar und sonnig');
    expect(cols[4].title).not.toContain('%');
  });

  it('fünf Sprachen: die Kürzel kommen aus ICU, nicht aus einer zweiten Tabelle', () => {
    const kuerzel = (locale: string): string => outlookColumns(sieben(), locale)[0].weekday;
    expect(kuerzel(de.locale)).toBe('Fr');
    expect(kuerzel(en.locale)).toBe('Fri');
    expect(kuerzel(es.locale)).toBe('vie');
    expect(kuerzel(fr.locale)).toBe('ven.');
    expect(kuerzel(itCatalog.locale)).toBe('ven');
  });

  it('der Tooltip spricht ebenfalls die aktive Sprache (langer Wochentag + Regensatz)', () => {
    const enCols = outlookColumns(sieben(), en.locale, en.idleFace.wetter.outlook);
    expect(enCols[1].title).toBe('Saturday, 14–21°, leichter Regen · 60% chance of rain');
    const frCols = outlookColumns(sieben(), fr.locale, fr.idleFace.wetter.outlook);
    expect(frCols[1].title).toContain('samedi');
    expect(frCols[1].title).toContain('60 % de pluie');
  });
});

describe('parseWeatherToday — die Wire-Naht des Ausblicks', () => {
  const kern = {
    label: 'Duisburg',
    todayMin: 15,
    todayMax: 23,
    codeText: 'teilweise bewölkt',
    precipMm: 2.4,
  };

  it('liest `outlook` mit den AUSGESCHRIEBENEN Wire-Namen tempMin/tempMax', () => {
    const parsed = parseWeatherToday({
      ...kern,
      outlook: [
        {
          offset: 0,
          dateIso: '2026-08-21',
          tempMin: 15,
          tempMax: 23,
          codeText: 'teilweise bewölkt',
          precipMm: 0,
          precipProbability: 10,
        },
      ],
    });
    expect(parsed?.outlook).toEqual([
      {
        offset: 0,
        dateIso: '2026-08-21',
        tempMin: 15,
        tempMax: 23,
        codeText: 'teilweise bewölkt',
        precipMm: 0,
        precipProbability: 10,
      },
    ]);
  });

  it('`tmin`/`tmax` werden NICHT akzeptiert — genau der Bug, den das BE gefangen hat', () => {
    const parsed = parseWeatherToday({
      ...kern,
      outlook: [
        { offset: 0, dateIso: '2026-08-21', tmin: 15, tmax: 23, codeText: 'bedeckt', precipMm: 0 },
      ],
    });
    expect(parsed).not.toBeNull(); // der KERN-Vertrag bleibt gültig …
    expect(parsed?.outlook).toBeUndefined(); // … aber der Tag ist keiner
  });

  it('fehlende `precipProbability` bleibt WEG (nie 0) — „keine Angabe" ist eine eigene Aussage', () => {
    const parsed = parseWeatherToday({
      ...kern,
      outlook: [
        { offset: 3, dateIso: '2026-08-24', tempMin: 12, tempMax: 20, codeText: 'bedeckt', precipMm: 0 },
      ],
    });
    expect(parsed?.outlook?.[0]).not.toHaveProperty('precipProbability');
  });

  it('ein kaputter Tag invalidiert die Antwort nicht — er fällt einzeln raus (Muster `hourly`)', () => {
    const parsed = parseWeatherToday({
      ...kern,
      outlook: [
        { offset: 0, dateIso: '2026-08-21', tempMin: 15, tempMax: 23, codeText: 'bedeckt', precipMm: 0 },
        { offset: 1, dateIso: '2026-08-22', tempMin: '14', tempMax: 21, codeText: 'Regen', precipMm: 3 },
        null,
      ],
    });
    expect(parsed?.outlook).toHaveLength(1);
    expect(parsed?.label).toBe('Duisburg');
  });

  it('gar kein `outlook` ⇒ das Feld fehlt (Alt-Backend bleibt gültig)', () => {
    expect(parseWeatherToday(kern)?.outlook).toBeUndefined();
  });
});

describe('Die Zeile im Markup — nur XL, nur mit echten Tagen', () => {
  const live = (outlook?: DayOutlook[]) =>
    weatherNowContent({
      kind: 'live',
      data: {
        label: 'Duisburg',
        todayMin: 15,
        todayMax: 23,
        codeText: 'teilweise bewölkt',
        precipMm: 2.4,
        ...(outlook ? { outlook } : {}),
      },
    }, Date.UTC(2026, 7, 21, 12, 0, 0));

  const render = (size: 'S' | 'M' | 'L' | 'XL', outlook?: DayOutlook[]): string =>
    renderToStaticMarkup(<>{weatherTileBody(live(outlook), size)}</>);

  it('XL trägt die Zeile mit allen sieben Tagen', () => {
    const html = render('XL', sieben());
    expect(html).toContain('idle__outlook');
    expect((html.match(/idle__outlookday/g) ?? []).length).toBe(7);
    expect(html).toContain('>Fr<');
    expect(html).toContain('>15–23°<');
    expect(html).toContain('data-today="true"');
  });

  it('S/M/L zeigen sie nicht — die Zeile ist die Belohnung der XL-Stufe', () => {
    for (const size of ['S', 'M', 'L'] as const) {
      expect(render(size, sieben()), `Stufe ${size}`).not.toContain('idle__outlook');
    }
  });

  it('XL OHNE `outlook` sieht aus wie vor dieser Erweiterung — kein leeres Raster', () => {
    const html = render('XL');
    expect(html).not.toContain('idle__outlook');
    // … und der Rest der XL-Kachel bleibt unberührt. (Die Stundenkurve fehlt
    // hier ebenfalls — die Fixture trägt kein `hourly`, und `WeatherHourly`
    // rendert dann nach derselben Regel nichts. Zwei Verdien-Regeln, ein Bild.)
    expect(html).toContain('idle__nowfacts--row');
    expect(html).toContain('15–23°');
  });

  it('das Lage-Icon je Tag kommt aus DERSELBEN Kategorie-Zuordnung wie die Kachel darüber', () => {
    const html = render('XL', sieben());
    // „Regenschauer" ⇒ rain-Glyph, „klar und sonnig" ⇒ sun-Glyph: beide müssen
    // im Markup vorkommen, sonst wurde pauschal ein Wolken-Icon gesetzt.
    expect((html.match(/idle__outlookicon/g) ?? []).length).toBe(7);
    expect(html).toContain('idle__outlookcond');
  });

  it('die Zeile ist eine Liste mit sprechendem aria-Label (aktive Sprache)', () => {
    expect(render('XL', sieben())).toContain(`aria-label="${de.idleFace.wetter.outlook.aria(7)}"`);
    const enHtml = renderToStaticMarkup(
      <>{weatherTileBody(live(sieben()), 'XL', en.idleFace, en.locale)}</>,
    );
    expect(enHtml).toContain(`aria-label="${en.idleFace.wetter.outlook.aria(7)}"`);
    expect(enHtml).toContain('>Fri<');
  });
});

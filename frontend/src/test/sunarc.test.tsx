import { describe, expect, it, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  DAY_MS,
  SUN_ARC_HORIZON_Y,
  SUN_ARC_HEIGHT,
  SunArc,
  computeSunArcLayout,
  sunPhase,
  type SunTimes,
} from '../components/SunArc';
import { clockTileBody, sunTimesOf } from '../components/IdleFace';
import type { WeatherToday, WeatherTodayState } from '../hooks/useWeatherToday';
import { setActiveUiLanguage } from '../i18n';
import { de } from '../i18n/de';
import { en } from '../i18n/en';

/**
 * **Der Sonnenbogen der L-Uhr** (Andi 21.08.: „wenn man die Größe ändert, dass
 * man den Sonnenverlauf anzeigt").
 *
 * Gepinnt wird hier NICHT „das Bild sieht gut aus" — das entscheidet ein
 * Screenshot —, sondern die **Positions-Rechnung** und die Ehrlichkeits-Regeln,
 * die man einem Bogen nicht ansieht:
 *  - die Sonne steht an ihrer ECHTEN Tagesposition (lineare Interpolation
 *    zwischen Aufgang und Untergang), nicht an einer geschätzten,
 *  - vor Aufgang / nach Untergang steht sie UNTER dem Horizont,
 *  - ohne Sonnenzeiten erscheint der Bogen GAR NICHT (Verdien-Regel) — statt
 *    eines leeren Rahmens oder einer gerechneten Ersatz-Sonne,
 *  - kaputte Daten (Untergang ≤ Aufgang, NaN) zeichnen nichts.
 *
 * Alle Zeiten sind arithmetisch aus einem Anker gebaut, nie aus Wanduhr-Text:
 * die Suite muss in jeder Zeitzone dasselbe Ergebnis liefern.
 */

const H = 3600000;
/** Anker: irgendein Mittag. Aufgang 06:00, Untergang 20:00 ⇒ 14 h Tageslicht. */
const RISE = Date.UTC(2026, 7, 21, 6, 0, 0);
const SET = RISE + 14 * H;
const SUN: SunTimes = { sunriseEpochMs: RISE, sunsetEpochMs: SET };

/** Die y-Koordinate der Sonne im Bildraum (aus der Prozentangabe zurückgerechnet). */
function sunY(nowMs: number, sun: SunTimes = SUN): number {
  const layout = computeSunArcLayout(nowMs, sun);
  expect(layout).not.toBeNull();
  return ((layout?.sun.topPercent ?? 0) * SUN_ARC_HEIGHT) / 100;
}

afterEach(() => setActiveUiLanguage('de'));

describe('sunPhase — die Sonnenposition ist gerechnet, nicht geschätzt', () => {
  it('Mittag der Tageslicht-Spanne: genau die Hälfte des Tages ist um', () => {
    expect(sunPhase(RISE + 7 * H, SUN)).toEqual({ daytime: true, fraction: 0.5 });
  });

  it('Viertel und Dreiviertel treffen exakt — die Interpolation ist linear in der Zeit', () => {
    expect(sunPhase(RISE + 3.5 * H, SUN)).toEqual({ daytime: true, fraction: 0.25 });
    expect(sunPhase(RISE + 10.5 * H, SUN)).toEqual({ daytime: true, fraction: 0.75 });
  });

  it('die Ränder gehören zum TAG: genau am Aufgang 0, genau am Untergang 1', () => {
    // Ein Punkt, der im Augenblick des Untergangs unter den Horizont springt,
    // wäre für den Betrachter ein Fehler, keine Feinheit.
    expect(sunPhase(RISE, SUN)).toEqual({ daytime: true, fraction: 0 });
    expect(sunPhase(SET, SUN)).toEqual({ daytime: true, fraction: 1 });
  });

  it('eine Minute nach Untergang ist NACHT — und die Nacht hat gerade erst begonnen', () => {
    const phase = sunPhase(SET + 60000, SUN);
    expect(phase?.daytime).toBe(false);
    expect(phase?.fraction).toBeGreaterThan(0);
    expect(phase?.fraction).toBeLessThan(0.05);
  });

  it('eine Minute vor Aufgang ist NACHT — und die Nacht ist fast vorbei', () => {
    const phase = sunPhase(RISE - 60000, SUN);
    expect(phase?.daytime).toBe(false);
    expect(phase?.fraction).toBeGreaterThan(0.95);
    expect(phase?.fraction).toBeLessThan(1);
  });

  it('Mitternacht liegt in der Mitte der Nacht — der fehlende Anker kommt aus dem 24-h-Versatz', () => {
    // Die Nacht läuft von SET (20:00) bis RISE + 24 h (06:00 am Folgetag),
    // ist also 10 h lang; 5 h nach Untergang ist Halbzeit. Das ist die EINE
    // benannte Annahme des Bildes (RESULT.md, Rate-Stelle 1) — kein Wert aus
    // dem Nichts, sondern der bekannte Anker um genau einen Tag verschoben.
    const phase = sunPhase(SET + 5 * H, SUN);
    expect(phase).toEqual({ daytime: false, fraction: 0.5 });
  });

  it('die Morgen-Nacht rechnet spiegelbildlich (gestriger Untergang = heutiger minus 24 h)', () => {
    // 5 h vor dem Aufgang (01:00) ⇒ dieselbe Halbzeit von der anderen Seite.
    expect(sunPhase(RISE - 5 * H, SUN)).toEqual({ daytime: false, fraction: 0.5 });
  });

  it('gealterte Wire-Daten (Sonnenzeiten von vorgestern) ergeben nie NaN und nie > 1', () => {
    const phase = sunPhase(SET + 3 * DAY_MS, SUN);
    expect(phase?.daytime).toBe(false);
    expect(phase?.fraction).toBe(1); // geklemmt, statt „die Nacht ist 300 % um"
  });

  it('fehlende Sonnenzeiten ⇒ null (die Verdien-Regel beginnt hier)', () => {
    expect(sunPhase(RISE + H, null)).toBeNull();
  });

  it('kaputte Daten ⇒ null: Untergang vor/gleich Aufgang, NaN', () => {
    expect(sunPhase(RISE + H, { sunriseEpochMs: SET, sunsetEpochMs: RISE })).toBeNull();
    expect(sunPhase(RISE + H, { sunriseEpochMs: RISE, sunsetEpochMs: RISE })).toBeNull();
    expect(sunPhase(RISE + H, { sunriseEpochMs: Number.NaN, sunsetEpochMs: SET })).toBeNull();
    expect(sunPhase(RISE + H, { sunriseEpochMs: RISE, sunsetEpochMs: Number.NaN })).toBeNull();
    expect(sunPhase(Number.NaN, SUN)).toBeNull();
  });
});

describe('computeSunArcLayout — was aus der Position ein Bild macht', () => {
  it('der Bogen beginnt und endet AUF dem Horizont und wölbt sich darüber', () => {
    const layout = computeSunArcLayout(RISE + 7 * H, SUN);
    const points = (layout?.arcPoints ?? '').split(' ').map((p) => p.split(',').map(Number));
    expect(points.length).toBeGreaterThan(10);
    expect(points[0][1]).toBe(SUN_ARC_HORIZON_Y);
    expect(points[points.length - 1][1]).toBe(SUN_ARC_HORIZON_Y);
    const highest = Math.min(...points.map((p) => p[1]));
    expect(highest).toBeLessThan(SUN_ARC_HORIZON_Y); // kleineres y = weiter oben
  });

  it('die Sonne wandert im Tagesverlauf nach RECHTS und steht mittags am höchsten', () => {
    const morgens = computeSunArcLayout(RISE + H, SUN);
    const mittags = computeSunArcLayout(RISE + 7 * H, SUN);
    const abends = computeSunArcLayout(RISE + 13 * H, SUN);
    expect(morgens?.sun.leftPercent).toBeLessThan(mittags?.sun.leftPercent ?? 0);
    expect(mittags?.sun.leftPercent).toBeLessThan(abends?.sun.leftPercent ?? 0);
    expect(mittags?.sun.topPercent).toBeLessThan(morgens?.sun.topPercent ?? 0);
    expect(mittags?.sun.topPercent).toBeLessThan(abends?.sun.topPercent ?? 0);
  });

  it('die Sonne liegt immer ÜBER dem Horizont — das Bild gibt es nur bei Tag', () => {
    expect(sunY(RISE + 7 * H)).toBeLessThan(SUN_ARC_HORIZON_Y);
    expect(sunY(RISE)).toBe(SUN_ARC_HORIZON_Y);
    expect(sunY(SET)).toBe(SUN_ARC_HORIZON_Y);
  });

  /**
   * Seit Andis Bestellung vom 23.08. gehört die Nacht dem Mond, nicht einer
   * gedimmten Sonne unter dem Horizont: `computeSunArcLayout` baut nur noch das
   * TAG-Bild. `sunPhase` bleibt für beide zuständig — es ist der Umschalter.
   */
  it('nachts gibt es KEIN Bogen-Layout mehr — dort steht der Mond', () => {
    expect(computeSunArcLayout(SET + 30 * 60000, SUN)).toBeNull();
    expect(computeSunArcLayout(RISE - 30 * 60000, SUN)).toBeNull();
    // Der Umschalter selbst weiß weiterhin beides.
    expect(sunPhase(SET + 30 * 60000, SUN)?.daytime).toBe(false);
    expect(sunPhase(RISE + 7 * H, SUN)?.daytime).toBe(true);
  });

  it('ohne brauchbare Daten gibt es kein Layout — und damit kein Bild', () => {
    expect(computeSunArcLayout(RISE + H, null)).toBeNull();
    expect(computeSunArcLayout(RISE + H, { sunriseEpochMs: SET, sunsetEpochMs: RISE })).toBeNull();
  });
});

describe('<SunArc> — die Verdien-Regel im Markup', () => {
  it('ohne Sonnenzeiten rendert die Komponente NICHTS (kein Rahmen, kein Loch)', () => {
    expect(renderToStaticMarkup(<SunArc nowMs={RISE + H} sun={null} />)).toBe('');
  });

  it('kaputte Sonnenzeiten rendern ebenfalls nichts — lieber kein Bild als ein falsches', () => {
    const html = renderToStaticMarkup(
      <SunArc nowMs={RISE + H} sun={{ sunriseEpochMs: SET, sunsetEpochMs: RISE }} />,
    );
    expect(html).toBe('');
  });

  it('bei Tag: Bogen + gefüllte Sonne, KEIN Mond, data-phase="day"', () => {
    const html = renderToStaticMarkup(<SunArc nowMs={RISE + 7 * H} sun={SUN} />);
    expect(html).toContain('data-phase="day"');
    expect(html).toContain('idle__sunarcsun');
    expect(html).not.toContain('idle__moon');
    expect(html).toContain('idle__sunarcline');
    expect(html).toContain(de.idleFace.uhr.sun.dayPhase);
  });

  /**
   * **Der Umschalter ist die echte Zeit, kein Hardcode** (Andi 23.08.: „Bei dem
   * goßen sonnenstand möchte ich in der nacht die mondphase angezeigt haben").
   * Eine Minute vor Untergang ist noch Bogen, eine Minute danach schon Mond.
   */
  it('bei Nacht: Mondscheibe statt Bogen — und zwar genau ab Sonnenuntergang', () => {
    const kurzVorher = renderToStaticMarkup(<SunArc nowMs={SET - 60000} sun={SUN} />);
    expect(kurzVorher).toContain('data-phase="day"');
    expect(kurzVorher).toContain('idle__sunarcline');

    const html = renderToStaticMarkup(<SunArc nowMs={SET + 60000} sun={SUN} />);
    expect(html).toContain('data-phase="night"');
    expect(html).toContain('idle__moondisc');
    expect(html).not.toContain('idle__sunarcline');
    expect(html).not.toContain('idle__sunarcsun');
    // 21.08.2026 ist der Mond zunehmend (Neumond war der 12., Vollmond der 28.).
    expect(html).toContain(de.idleFace.uhr.sun.moon.phases.waxingGibbous);

    // Und am Morgen wieder zurück: eine Minute vor Aufgang Mond, danach Bogen.
    expect(renderToStaticMarkup(<SunArc nowMs={RISE - 60000} sun={SUN} />)).toContain(
      'data-phase="night"',
    );
    expect(renderToStaticMarkup(<SunArc nowMs={RISE + 60000} sun={SUN} />)).toContain(
      'data-phase="day"',
    );
  });

  it('im Tag-SVG steht kein Text — Sonne und Uhrzeiten sind HTML (sonst verzerrt „none")', () => {
    const html = renderToStaticMarkup(<SunArc nowMs={RISE + 7 * H} sun={SUN} />);
    const svg = html.slice(html.indexOf('<svg'), html.indexOf('</svg>'));
    expect(svg).toContain('preserveAspectRatio="none"');
    expect(svg).not.toContain('<text');
    expect(html).toContain('idle__sunarctimes');
  });

  /**
   * Der Mond ist RUND — und darf deshalb als einziges Bild der Kachel NICHT
   * auf die Breite gedehnt werden. Ein Kreis unter `preserveAspectRatio="none"`
   * wäre eine Ellipse, und eine Ellipse ist kein Mond.
   */
  it('das Mond-SVG dehnt nicht: xMidYMid meet statt none', () => {
    const html = renderToStaticMarkup(<SunArc nowMs={SET + 2 * H} sun={SUN} />);
    const svg = html.slice(html.indexOf('<svg'), html.indexOf('</svg>'));
    expect(svg).toContain('preserveAspectRatio="xMidYMid meet"');
    expect(svg).not.toContain('preserveAspectRatio="none"');
  });

  it('der aria-Text nennt beide Sonnenzeiten und die Lage — in der aktiven Sprache', () => {
    setActiveUiLanguage('en');
    const tag = renderToStaticMarkup(<SunArc nowMs={RISE + 7 * H} sun={SUN} />);
    expect(tag).toContain('Sun path');
    expect(tag).toContain(en.idleFace.uhr.sun.dayPhase);
    expect(tag).not.toContain(de.idleFace.uhr.sun.dayPhase);

    // Nachts trägt der aria-Text die Phase, den Anteil und den Sonnenaufgang.
    const nacht = renderToStaticMarkup(<SunArc nowMs={SET + 2 * H} sun={SUN} />);
    expect(nacht).toContain('Moon phase');
    expect(nacht).toContain(en.idleFace.uhr.sun.moon.phases.waxingGibbous);
    expect(nacht).not.toContain(de.idleFace.uhr.sun.moon.phases.waxingGibbous);
  });
});

describe('Die Naht zur Uhr-Kachel — nur L, nur mit echten Feldern', () => {
  const heute: WeatherToday = {
    label: 'Duisburg',
    todayMin: 18,
    todayMax: 29,
    codeText: 'bedeckt',
    precipMm: 0,
  };
  const live = (extra: Partial<WeatherToday> = {}): WeatherTodayState => ({
    kind: 'live',
    data: { ...heute, ...extra },
  });

  it('sunTimesOf: beide Felder da ⇒ Zeiten, eines fehlt ⇒ null (ein Bogen hat zwei Füße)', () => {
    expect(sunTimesOf(live({ sunriseEpochMs: RISE, sunsetEpochMs: SET }))).toEqual(SUN);
    expect(sunTimesOf(live({ sunriseEpochMs: RISE }))).toBeNull();
    expect(sunTimesOf(live({ sunsetEpochMs: SET }))).toBeNull();
    expect(sunTimesOf(live())).toBeNull();
  });

  it('sunTimesOf: die ehrlichen Nicht-Zustände tragen keine Sonne', () => {
    expect(sunTimesOf(null)).toBeNull();
    expect(sunTimesOf({ kind: 'off' })).toBeNull();
    expect(sunTimesOf({ kind: 'unreachable' })).toBeNull();
  });

  it('L zeigt den Bogen, S und M nicht — die Stufe ist die Bestellung', () => {
    const l = renderToStaticMarkup(
      <>{clockTileBody(RISE + 7 * H, 'L', de.locale, de.idleFace, SUN)}</>,
    );
    expect(l).toContain('idle__sunarc');
    for (const size of ['S', 'M'] as const) {
      const html = renderToStaticMarkup(
        <>{clockTileBody(RISE + 7 * H, size, de.locale, de.idleFace, SUN)}</>,
      );
      expect(html, `Stufe ${size}`).not.toContain('idle__sunarc');
    }
  });

  it('L OHNE Sonnenzeiten sieht aus wie vor dieser Erweiterung (Verdien-Regel)', () => {
    const ohne = renderToStaticMarkup(<>{clockTileBody(RISE + 7 * H, 'L', de.locale, de.idleFace)}</>);
    expect(ohne).not.toContain('idle__sunarc');
    expect(ohne).toContain('idle__clock');
    expect(ohne).toContain('idle__clockdate');
  });
});

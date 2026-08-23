import { describe, expect, it, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  MIN_BAR_HEIGHT,
  HOURLY_VIEW_HEIGHT,
  HOURLY_VIEW_WIDTH,
  WeatherHourly,
  computeWeatherHourlyLayout,
} from '../components/WeatherHourly';
import type { HourlyPoint } from '../hooks/useWeatherToday';
import { setActiveUiLanguage } from '../i18n';
import { de } from '../i18n/de';
import { en } from '../i18n/en';

/**
 * **Der Stunden-Verlauf der XL-Wetterkachel** (W5,
 * DESIGN-widget-raster-2026-08-18 §3.1).
 *
 * Was hier gepinnt wird, ist nicht „das Bild sieht gut aus" — das entscheidet
 * ein Screenshot —, sondern die **Bindung an echte Felder** und die
 * Ehrlichkeits-Regeln, die man einem Diagramm nicht ansieht:
 *  - kein `hourly` ⇒ kein Bild (statt einer erfundenen Kurve),
 *  - `precipProbability === 0` ⇒ **kein** Balken (statt eines Balkens der
 *    Höhe 0, den man für „wenig Regen" halten könnte),
 *  - feste 0–100-%-Skala (statt aufs Tagesmaximum gedehnt),
 *  - die Temperatur-Marken tragen die ECHTEN Extremwerte des Fensters.
 *
 * Die Punktdichte der Fixtures folgt dem BE: `HOURLY_WINDOW = 12` stündliche
 * Punkte ab der laufenden Stunde (`WeatherGroundingProvider.parseHourly`).
 */

const H = 3600000;
const START = Date.UTC(2026, 7, 20, 12, 0, 0);

/** Zwölf Stunden im BE-Raster — Temperatur-Bogen, Regen ab der fünften Stunde. */
function zwoelfStunden(): HourlyPoint[] {
  const temps = [21, 22, 23, 23, 22, 21, 19, 18, 17, 16, 15, 15];
  const rain = [0, 0, 5, 10, 35, 60, 80, 75, 55, 30, 15, 5];
  return temps.map((tempC, i) => ({
    epochMs: START + i * H,
    tempC,
    precipProbability: rain[i],
  }));
}

afterEach(() => setActiveUiLanguage('de'));

describe('computeWeatherHourlyLayout — reine Geometrie', () => {
  it('leere Reihe ⇒ leeres Layout (die Kachel zeichnet nichts)', () => {
    const l = computeWeatherHourlyLayout([]);
    expect(l.points).toHaveLength(0);
    expect(l.bars).toHaveLength(0);
    expect(l.linePoints).toBeNull();
    expect(l.minTemp).toBeNull();
    expect(l.maxTemp).toBeNull();
    expect(l.ticks).toHaveLength(0);
  });

  it('12 Punkte ⇒ 12 Temperatur-Punkte, gleichmäßig auf Slot-Mitten', () => {
    const l = computeWeatherHourlyLayout(zwoelfStunden());
    expect(l.points).toHaveLength(12);
    const slot = HOURLY_VIEW_WIDTH / 12;
    expect(l.points[0].x).toBeCloseTo(slot / 2, 6);
    expect(l.points[11].x).toBeCloseTo(HOURLY_VIEW_WIDTH - slot / 2, 6);
    // Gleicher Abstand zwischen ALLEN Nachbarn (das BE liefert lückenlos stündlich).
    const gaps = l.points.slice(1).map((p, i) => p.x - l.points[i].x);
    for (const g of gaps) expect(g).toBeCloseTo(slot, 6);
  });

  it('die wärmste Stunde liegt oben, die kälteste unten — Skala über die ECHTEN Werte', () => {
    const l = computeWeatherHourlyLayout(zwoelfStunden());
    expect(l.maxTemp).toBe(23);
    expect(l.minTemp).toBe(15);
    const warm = l.points.find((p) => p.tempC === 23)!;
    const kalt = l.points.find((p) => p.tempC === 15)!;
    expect(warm.y).toBeLessThan(kalt.y); // y wächst nach unten
    // Die Marken-Prozente zeigen auf genau diese beiden Höhen.
    expect(l.maxTempPercent).toBeCloseTo((warm.y * 100) / HOURLY_VIEW_HEIGHT, 6);
    expect(l.minTempPercent).toBeCloseTo((kalt.y * 100) / HOURLY_VIEW_HEIGHT, 6);
  });

  it('alle Stunden gleich warm ⇒ die Linie liegt FLACH IN DER MITTE, nicht am Rand', () => {
    const flach = [0, 1, 2].map((i) => ({ epochMs: START + i * H, tempC: 19, precipProbability: 0 }));
    const l = computeWeatherHourlyLayout(flach);
    const ys = new Set(l.points.map((p) => p.y));
    expect(ys.size).toBe(1);
    expect(l.minTemp).toBe(19);
    expect(l.maxTemp).toBe(19);
    // In der Mitte des Temperatur-Bandes — am oberen Rand wäre es die
    // Behauptung „heute ist es maximal warm", die die Daten nicht tragen.
    const y = l.points[0].y;
    expect(y).toBeGreaterThan(HOURLY_VIEW_HEIGHT * 0.15);
    expect(y).toBeLessThan(HOURLY_VIEW_HEIGHT * 0.5);
  });

  it('EIN Punkt ⇒ kein Linien-Attribut (eine Linie braucht zwei Enden)', () => {
    const l = computeWeatherHourlyLayout([{ epochMs: START, tempC: 20, precipProbability: 40 }]);
    expect(l.points).toHaveLength(1);
    expect(l.linePoints).toBeNull();
    expect(l.bars).toHaveLength(1);
  });

  it('0 % ⇒ GAR KEIN Balken; jeder Wert > 0 bleibt sichtbar', () => {
    const punkte: HourlyPoint[] = [
      { epochMs: START, tempC: 20, precipProbability: 0 },
      { epochMs: START + H, tempC: 20, precipProbability: 1 },
      { epochMs: START + 2 * H, tempC: 20, precipProbability: 0 },
    ];
    const l = computeWeatherHourlyLayout(punkte);
    expect(l.bars).toHaveLength(1);
    expect(l.bars[0].index).toBe(1);
    expect(l.bars[0].probability).toBe(1); // der ECHTE Wert, nicht die Zeichenhöhe
    expect(l.bars[0].height).toBe(MIN_BAR_HEIGHT); // sichtbar statt weggerundet
  });

  it('feste 0–100-Skala: 50 % ist genau halb so hoch wie 100 % — auch ohne 100er im Fenster', () => {
    const mit100 = computeWeatherHourlyLayout([
      { epochMs: START, tempC: 20, precipProbability: 100 },
      { epochMs: START + H, tempC: 20, precipProbability: 50 },
    ]);
    expect(mit100.bars[1].height).toBeCloseTo(mit100.bars[0].height / 2, 6);

    // Dieselbe 50-%-Stunde in einem Fenster, dessen Maximum 50 % ist: gleich
    // hoch wie oben. Eine aufs Maximum gedehnte Skala ließe sie doppelt so
    // hoch aussehen — „halbwegs wahrscheinlich" sähe aus wie „sicher".
    const ohne100 = computeWeatherHourlyLayout([
      { epochMs: START, tempC: 20, precipProbability: 50 },
      { epochMs: START + H, tempC: 20, precipProbability: 20 },
    ]);
    expect(ohne100.bars[0].height).toBeCloseTo(mit100.bars[1].height, 6);
    expect(ohne100.maxProbability).toBe(50);
  });

  it('Balken stehen auf der Grundlinie und ragen nie ins Temperatur-Band', () => {
    const l = computeWeatherHourlyLayout(zwoelfStunden());
    for (const b of l.bars) {
      expect(b.y + b.height).toBeCloseTo(HOURLY_VIEW_HEIGHT, 6);
      expect(b.y).toBeGreaterThan(Math.max(...l.points.map((p) => p.y)));
    }
  });

  it('sparsame Achse: 12 Punkte ⇒ 4 Marken (jede 3. Stunde), ≤ 6 Punkte ⇒ jede', () => {
    const zwoelf = computeWeatherHourlyLayout(zwoelfStunden());
    expect(zwoelf.ticks.map((t) => t.index)).toEqual([0, 3, 6, 9]);

    const vier = computeWeatherHourlyLayout(zwoelfStunden().slice(0, 4));
    expect(vier.ticks.map((t) => t.index)).toEqual([0, 1, 2, 3]);
  });

  it('Achsen-Marken folgen der SPRACHE, nicht hart de-DE', () => {
    const deL = computeWeatherHourlyLayout(zwoelfStunden(), de.locale);
    const enL = computeWeatherHourlyLayout(zwoelfStunden(), en.locale);
    expect(deL.ticks[0].label).not.toBe(enL.ticks[0].label);
    expect(enL.ticks[0].label).toMatch(/AM|PM/);
  });
});

describe('<WeatherHourly> — das Bild', () => {
  it('ohne Stunden rendert es NICHTS (keine erfundene Kurve)', () => {
    expect(renderToStaticMarkup(<WeatherHourly points={[]} />)).toBe('');
  });

  it('aus der Fixture wird ein SVG mit Linie, Balken und echten Temperatur-Marken', () => {
    const html = renderToStaticMarkup(<WeatherHourly points={zwoelfStunden()} />);
    expect(html).toContain('idle__hourlychart');
    expect(html).toContain(`viewBox="0 0 ${HOURLY_VIEW_WIDTH} ${HOURLY_VIEW_HEIGHT}"`);
    expect(html).toContain('preserveAspectRatio="none"');
    // 10 der 12 Stunden führen Regen ⇒ 10 Balken.
    expect(html.split('idle__hourlybar').length - 1).toBe(10);
    const line = /class="idle__hourlyline" points="([^"]+)"/.exec(html);
    expect(line![1].split(' ')).toHaveLength(12);
    expect(html).toContain('>23°</span>');
    expect(html).toContain('>15°</span>');
    // Vier Achsen-Marken, jede mit einer echten Uhrzeit.
    expect(html.split('idle__hourlyhour').length - 1).toBe(4);
  });

  it('der „jetzt"-Punkt ist HTML, kein `<circle>` — im gedehnten SVG wäre er eine Ellipse', () => {
    // Kachel-Ausbau 21.08.: die Figur bekam endlich die volle Kachelbreite
    // (`align-self: stretch`; vorher stand sie auf ihrer 300-px-Eigenbreite,
    // s. `shots/xl-wetter-1366x1024.png` von W5). Damit wurde aus dem runden
    // SVG-Kreis sichtbar ein gequetschtes Oval — der Punkt liegt seither als
    // HTML über der Zeichenfläche, prozentual positioniert wie die
    // Temperatur-Marken und die Stundenachse.
    const html = renderToStaticMarkup(<WeatherHourly points={zwoelfStunden()} />);
    const svg = html.slice(html.indexOf('<svg'), html.indexOf('</svg>'));
    expect(svg).not.toContain('idle__hourlynow');
    expect(svg).not.toContain('<circle');
    expect(html).toContain('idle__hourlynow');

    const layout = computeWeatherHourlyLayout(zwoelfStunden());
    // Er sitzt auf dem ERSTEN Punkt der Kurve — „jetzt" ist der linke Rand.
    expect(layout.now?.leftPercent).toBeCloseTo((layout.points[0].x * 100) / layout.width, 6);
    expect(layout.now?.topPercent).toBeCloseTo((layout.points[0].y * 100) / layout.height, 6);
    expect(computeWeatherHourlyLayout([]).now).toBeNull();
  });

  it('IM SVG steht kein Text — es ist gedehnt, Text würde verzerren', () => {
    const html = renderToStaticMarkup(<WeatherHourly points={zwoelfStunden()} />);
    const svg = html.slice(html.indexOf('<svg'), html.indexOf('</svg>'));
    expect(svg).not.toContain('<text');
    // `<title>` ist der native Tooltip, kein gezeichneter Text.
    expect(svg).toContain('<title>');
  });

  it('gleiche Höchst- und Tiefsttemperatur ⇒ EINE Marke statt zweier gleicher', () => {
    const flach = [0, 1, 2].map((i) => ({ epochMs: START + i * H, tempC: 19, precipProbability: 0 }));
    const html = renderToStaticMarkup(<WeatherHourly points={flach} />);
    expect(html.split('idle__hourlytick').length - 1).toBe(1);
    expect(html).toContain('>19°</span>');
  });

  it('trockene Reihe: keine Balken, und der Vorlese-Text sagt es ehrlich', () => {
    const trocken = [0, 1, 2].map((i) => ({
      epochMs: START + i * H,
      tempC: 18 + i,
      precipProbability: 0,
    }));
    const html = renderToStaticMarkup(<WeatherHourly points={trocken} />);
    expect(html).not.toContain('idle__hourlybar');
    expect(html).toContain(de.idleFace.wetter.hourly.ariaDry);
  });

  it('aria-Text trägt Stundenzahl, Spanne und die höchste Regenwahrscheinlichkeit', () => {
    const html = renderToStaticMarkup(<WeatherHourly points={zwoelfStunden()} />);
    expect(html).toContain(de.idleFace.wetter.hourly.aria(12, '15°', '23°'));
    expect(html).toContain(de.idleFace.wetter.hourly.ariaRain(80));
  });

  it('EN-Sweep: kein Umlaut, kein deutsches Wort im englischen Bild', () => {
    setActiveUiLanguage('en');
    const html = renderToStaticMarkup(<WeatherHourly points={zwoelfStunden()} />);
    expect(html).not.toMatch(/[äöüÄÖÜß]/);
    expect(html).not.toContain('Stunden-Verlauf');
    expect(html).not.toContain('Regen');
    expect(html).toContain(en.idleFace.wetter.hourly.ariaRain(80));
    expect(html).toContain(en.idleFace.wetter.hourly.barTitle(80));
  });
});

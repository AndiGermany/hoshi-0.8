import { de } from '../i18n/de';
import { useUiStrings } from '../i18n';
import { dueClock } from '../hooks/useScheduledItems';
import type { HourlyPoint } from '../hooks/useWeatherToday';

/**
 * `<WeatherHourly>` — der Stunden-Verlauf der Wetter-Kachel auf der XL-Stufe
 * (W5, DESIGN-widget-raster-2026-08-18 §3.1): Temperaturlinie + Regen-
 * wahrscheinlichkeits-Balken über die volle Kachelbreite. Handgerolltes SVG,
 * **kein Chart-Paket, +0 KB Bundle**.
 *
 * **Warum eine neue Komponente und nicht `StageSparkline`** (Design-Rate 1,
 * hier bestätigt): jene ist auf `StageSparklinePoint {ms, ts, error}` gebaut —
 * eine Latenz-Serie mit p50/p95-Referenzlinien, Ausreißer-Dreiecken und
 * Fehler-Punkten. Nichts davon hat eine Bedeutung für zwölf Temperatur-/
 * Regenwerte. Übernommen ist ausschließlich das **Muster**: eine reine
 * Layout-Funktion ({@link computeWeatherHourlyLayout}) getrennt von der
 * Render-Schicht, Theme-Token statt eigener Farben, `vector-effect:
 * non-scaling-stroke` auf jedem Strich, keine eigene Animation (damit auch
 * kein eigener `prefers-reduced-motion`-Riegel nötig ist — das Bild ist
 * statisch).
 *
 * **Die echte Punktdichte, gemessen statt geraten** (Design-Rate 7): das BE
 * schneidet `hourly` auf `HOURLY_WINDOW = 12` **stündliche** Punkte, beginnend
 * bei der laufenden Stunde (`WeatherGroundingProvider.parseHourly` — Start-
 * Index ist der erste `hourly.time`, der nicht vor `current.time` liegt).
 * Also: 12 gleich weit auseinanderliegende Punkte, ~12 h Ausblick, keine
 * Lücken. Darauf ist dieses Bild gebaut — ein Balken je Stunde ist bei 12
 * Slots aus 2 m noch als einzelner Balken lesbar, bei 48 Viertelstunden wäre
 * es ein Kamm gewesen.
 *
 * **Ehrlichkeits-Regeln:**
 *  - Kein `hourly` / leere Liste ⇒ die Komponente rendert **nichts** (`null`).
 *    Die XL-Kachel zeigt dann ihre Textzeilen ohne Bild, statt eine erfundene
 *    Kurve zu zeichnen.
 *  - Ein einzelner Punkt ⇒ Balken + Punkt, **keine Linie**. Eine Linie braucht
 *    zwei Enden. (Bewusst NICHT die 3-Werte-Schwelle der `StageSparkline`:
 *    dort sind die Punkte unregelmäßig gemessene Turns, zwei davon suggerieren
 *    einen Trend, den es nicht gibt. Hier ist die Reihe eine lückenlose
 *    stündliche Vorhersage — zwei benachbarte Stunden *sind* ein echtes
 *    Segment.)
 *  - Die Regen-Skala ist **fest 0–100 %**, nicht auf das Maximum gedehnt: ein
 *    30-%-Balken sieht immer gleich hoch aus, egal wie der Rest des Tages
 *    aussieht. Eine gedehnte Skala ließe 8 % wie „viel" aussehen.
 *  - `precipProbability === 0` ⇒ **gar kein Balken** (nicht ein Balken der
 *    Höhe 0). Ein trockener Tag ist eine leere Grundlinie.
 *  - Jeder Wert > 0 bekommt mindestens {@link MIN_BAR_HEIGHT} Zeichenhöhe,
 *    damit „3 %" nicht zu „nichts" wird. Das ist eine Zeichen-Untergrenze wie
 *    das Ausreißer-Klemmen der `StageSparkline`, kein verfälschter Wert — die
 *    Zahl steht im `<title>`-Tooltip und im aria-Text.
 *  - Die Temperatur-Skala spannt **genau über die vorkommenden Werte**
 *    (min..max des Fensters), nicht über eine erfundene Rundzahl. Sind alle
 *    Stunden gleich warm, liegt die Linie flach in der Mitte statt am Rand.
 *  - **Im SVG steht kein Text.** Die Skala ist mit `preserveAspectRatio="none"`
 *    gedehnt — Text darin würde verzerren (dieselbe Begründung wie bei
 *    `StageSparkline`). Beschriftet wird in HTML daneben/darunter: zwei
 *    Temperatur-Marken (Maximum/Minimum, prozentual auf ihrer echten Höhe
 *    positioniert) und eine sparsame Stundenachse.
 */

/** Koordinatenraum des SVG (keine Pixel — CSS dehnt ihn auf die Kachelbreite). */
export const HOURLY_VIEW_WIDTH = 300;
export const HOURLY_VIEW_HEIGHT = 100;
/** Obere Kante des Temperatur-Bandes (Platz für die Strichstärke). */
const TEMP_TOP = 10;
/** Untere Kante des Temperatur-Bandes — darunter beginnt die Regen-Zone. */
const TEMP_BOTTOM = 54;
/** Grundlinie der Balken = Boden des Bildes. */
const BAR_BASE = HOURLY_VIEW_HEIGHT;
/** Höhe eines 100-%-Balkens. Der Abstand zu {@link TEMP_BOTTOM} hält die zwei Zonen getrennt. */
const BAR_FULL_HEIGHT = 34;
/** Zeichen-Untergrenze für jeden Wert > 0 (s. Ehrlichkeits-Regeln oben). */
export const MIN_BAR_HEIGHT = 1.5;
/** Anteil der Slot-Breite, den ein Balken einnimmt (der Rest ist Luft zwischen den Balken). */
const BAR_FILL = 0.62;

/** Ab dieser Punktzahl wird nur noch jede {@link HOURLY_AXIS_STEP}-te Stunde beschriftet. */
const AXIS_DENSE_LIMIT = 6;
/** Beschriftungs-Raster der Stundenachse (12 Punkte ⇒ 4 Marken: jetzt, +3 h, +6 h, +9 h). */
export const HOURLY_AXIS_STEP = 3;

/** Ein gezeichneter Regen-Balken (nur für Stunden mit `precipProbability > 0`). */
export interface HourlyBar {
  /** Index im ursprünglichen `points`-Array. */
  index: number;
  x: number;
  width: number;
  y: number;
  height: number;
  /** Der ECHTE Prozentwert (nie geklemmt — nur `height` hat eine Untergrenze). */
  probability: number;
}

/** Ein Punkt der Temperaturlinie — es gibt für JEDE Stunde genau einen. */
export interface HourlyTempPoint {
  index: number;
  x: number;
  y: number;
  tempC: number;
}

/** Eine Marke der sparsamen Stundenachse. */
export interface HourlyTick {
  index: number;
  /** Position in Prozent der Breite — die Achse ist HTML, das SVG dehnt sich unabhängig. */
  leftPercent: number;
  /** Uhrzeit in der aktiven Sprache: „14:00" (de-DE) / „02:00 PM" (en-US). */
  label: string;
}

export interface WeatherHourlyLayout {
  width: number;
  height: number;
  bars: HourlyBar[];
  points: HourlyTempPoint[];
  /** `points`-Attribut der Polyline; `null` bei < 2 Punkten (eine Linie braucht zwei Enden). */
  linePoints: string | null;
  /** Wärmste/kälteste Stunde des Fensters; `null` ohne Punkte. */
  maxTemp: number | null;
  minTemp: number | null;
  /** Höhe der Max-/Min-Marke in Prozent der Bildhöhe (für die HTML-Beschriftung). */
  maxTempPercent: number | null;
  minTempPercent: number | null;
  /** Höchste Regenwahrscheinlichkeit im Fenster; 0 ⇒ trocken, keine Balken. */
  maxProbability: number;
  ticks: HourlyTick[];
  /**
   * Der „jetzt"-Punkt (erste Stunde) in Prozent der Zeichenfläche — `null` ohne
   * Punkte. **Prozente statt SVG-Koordinaten**, weil er als HTML gezeichnet
   * wird: im mit `preserveAspectRatio="none"` gedehnten Bild wäre ein `<circle>`
   * eine Ellipse. Das fiel erst auf, als die Figur beim Kachel-Ausbau (21.08.)
   * die volle Kachelbreite bekam — vorher stand das SVG zufällig in seiner
   * Eigenbreite da, und der Kreis war rund, weil nichts gedehnt wurde.
   */
  now: { leftPercent: number; topPercent: number } | null;
}

/**
 * Stunden-Marke — **dieselbe** Uhrzeit-Form wie die Regen-ab-Zeile eine Zeile
 * darüber ({@link dueClock}, „Regen ab ~17:00"). Bewusst NICHT
 * `{hour:'numeric'}`: das liefert je Sprache „14 Uhr"/„2 PM"/„14 h" — vier
 * solcher Marken nebeneinander sind Lärm, und die Achse spräche eine andere
 * Sprache als der Satz direkt darüber.
 */
function fmtHour(epochMs: number, locale: string): string {
  return Number.isNaN(new Date(epochMs).getTime()) ? '' : dueClock(epochMs, locale);
}

/**
 * Reine Layout-Berechnung: Slot-Raster, Balken, Temperatur-Punkte/Linie,
 * Achsen-Marken. Kein DOM, keine Hooks — direkt unit-testbar (Muster
 * {@link computeSparklineLayout}).
 */
export function computeWeatherHourlyLayout(
  points: HourlyPoint[],
  locale: string = de.locale,
): WeatherHourlyLayout {
  const width = HOURLY_VIEW_WIDTH;
  const height = HOURLY_VIEW_HEIGHT;
  const n = points.length;
  const empty: WeatherHourlyLayout = {
    width,
    height,
    bars: [],
    points: [],
    linePoints: null,
    maxTemp: null,
    minTemp: null,
    maxTempPercent: null,
    minTempPercent: null,
    maxProbability: 0,
    ticks: [],
    now: null,
  };
  if (n === 0) return empty;

  const slot = width / n;
  const temps = points.map((p) => p.tempC);
  const minTemp = Math.min(...temps);
  const maxTemp = Math.max(...temps);
  const spread = maxTemp - minTemp;
  const band = TEMP_BOTTOM - TEMP_TOP;
  // Alle Stunden gleich warm ⇒ die Linie liegt flach in der MITTE des Bandes.
  // Am oberen Rand wäre sie eine Behauptung („heute ist es maximal warm"), die
  // die Daten nicht tragen.
  const tempY = (t: number): number =>
    spread === 0 ? TEMP_TOP + band / 2 : TEMP_BOTTOM - ((t - minTemp) / spread) * band;

  const plotted: HourlyTempPoint[] = points.map((p, i) => ({
    index: i,
    x: (i + 0.5) * slot,
    y: tempY(p.tempC),
    tempC: p.tempC,
  }));

  const bars: HourlyBar[] = [];
  let maxProbability = 0;
  points.forEach((p, i) => {
    const prob = Math.min(Math.max(p.precipProbability, 0), 100);
    if (prob > maxProbability) maxProbability = prob;
    if (prob <= 0) return; // trockene Stunde: KEIN Balken der Höhe 0
    const h = Math.max(MIN_BAR_HEIGHT, (prob / 100) * BAR_FULL_HEIGHT);
    const barWidth = slot * BAR_FILL;
    bars.push({
      index: i,
      x: (i + 0.5) * slot - barWidth / 2,
      width: barWidth,
      y: BAR_BASE - h,
      height: h,
      probability: p.precipProbability,
    });
  });

  const step = n <= AXIS_DENSE_LIMIT ? 1 : HOURLY_AXIS_STEP;
  const ticks: HourlyTick[] = [];
  points.forEach((p, i) => {
    if (i % step !== 0) return;
    const label = fmtHour(p.epochMs, locale);
    if (label === '') return; // kaputter Zeitstempel ⇒ lieber keine Marke als „Invalid Date"
    ticks.push({ index: i, leftPercent: ((i + 0.5) * slot * 100) / width, label });
  });

  return {
    width,
    height,
    bars,
    points: plotted,
    linePoints: plotted.length >= 2 ? plotted.map((p) => `${p.x},${p.y}`).join(' ') : null,
    maxTemp,
    minTemp,
    maxTempPercent: (tempY(maxTemp) * 100) / height,
    minTempPercent: (tempY(minTemp) * 100) / height,
    maxProbability,
    ticks,
    now: {
      leftPercent: (plotted[0].x * 100) / width,
      topPercent: (plotted[0].y * 100) / height,
    },
  };
}

export interface WeatherHourlyProps {
  /** Die Stunden-Punkte aus `WeatherToday.hourly` — leer/fehlend ⇒ es rendert nichts. */
  points: HourlyPoint[];
}

/**
 * Rendert **nichts**, wenn keine einzige Stunde vorliegt — die XL-Kachel steht
 * dann ohne Bild da, statt eine Fläche mit einer erfundenen Kurve zu füllen.
 */
export function WeatherHourly({ points }: WeatherHourlyProps) {
  const { idleFace, locale } = useUiStrings();
  const t = idleFace.wetter.hourly;
  const layout = computeWeatherHourlyLayout(points, locale);
  if (layout.points.length === 0) return null;

  const ariaParts = [t.aria(layout.points.length, `${layout.minTemp}°`, `${layout.maxTemp}°`)];
  ariaParts.push(layout.maxProbability > 0 ? t.ariaRain(layout.maxProbability) : t.ariaDry);
  // Gleiche Höchst- und Tiefsttemperatur ⇒ EINE Marke. Zwei identische Zahlen
  // übereinander wären keine Skala, sondern ein Fehler.
  const flat = layout.maxTemp === layout.minTemp;

  return (
    <figure className="idle__hourly">
      <div className="idle__hourlyplot">
        <div className="idle__hourlyscale" aria-hidden="true">
          <span className="idle__hourlytick" style={{ top: `${layout.maxTempPercent}%` }}>
            {layout.maxTemp}°
          </span>
          {!flat && (
            <span className="idle__hourlytick" style={{ top: `${layout.minTempPercent}%` }}>
              {layout.minTemp}°
            </span>
          )}
        </div>
        {/* Eigene Positionierungs-Fläche um das SVG: der „jetzt"-Punkt liegt
            als HTML DARÜBER (s. `WeatherHourlyLayout.now`) und braucht einen
            Bezugsrahmen, der genau die Zeichenfläche ist — nicht das ganze
            Gitter, in dem links noch die Temperatur-Marken stehen. */}
        <div className="idle__hourlyplotarea">
          <svg
            className="idle__hourlychart"
            viewBox={`0 0 ${layout.width} ${layout.height}`}
            preserveAspectRatio="none"
            role="img"
            aria-label={ariaParts.join(', ')}
          >
            {/* Balken ZUERST — sie liegen hinter der Linie. */}
            {layout.bars.map((b) => (
              <rect
                key={`bar-${b.index}`}
                className="idle__hourlybar"
                x={b.x}
                y={b.y}
                width={b.width}
                height={b.height}
                rx={1}
              >
                <title>{t.barTitle(b.probability)}</title>
              </rect>
            ))}
            <line
              className="idle__hourlybase"
              x1={0}
              y1={BAR_BASE}
              x2={layout.width}
              y2={BAR_BASE}
            />
            {layout.linePoints !== null && (
              <polyline className="idle__hourlyline" points={layout.linePoints} />
            )}
          </svg>
          {/* Die laufende Stunde trägt einen Punkt — der linke Rand ist „jetzt". */}
          {layout.now && (
            <span
              className="idle__hourlynow"
              style={{ left: `${layout.now.leftPercent}%`, top: `${layout.now.topPercent}%` }}
              aria-hidden="true"
            />
          )}
        </div>
        {/* Die Stundenachse liegt IM selben Gitter wie das Bild (Zeile 2,
            Spalte 2) — nur so steht „17" wirklich unter der 17-Uhr-Stunde.
            Als Geschwister der Plot-Fläche wäre sie um die Breite der
            Temperatur-Marken verschoben. */}
        <div className="idle__hourlyaxis" aria-hidden="true">
          {layout.ticks.map((tick) => (
            <span
              key={`tick-${tick.index}`}
              className="idle__hourlyhour"
              style={{ left: `${tick.leftPercent}%` }}
            >
              {tick.label}
            </span>
          ))}
        </div>
      </div>
    </figure>
  );
}

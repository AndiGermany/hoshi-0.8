import { de } from '../i18n/de';
import { useUiStrings } from '../i18n';
import { dueClock } from '../hooks/useScheduledItems';
import { moonLitPath, moonPhase } from './moonPhase';
import type { SunArcStrings } from '../i18n/types';

/**
 * `<SunArc>` — der Sonnenverlauf der Uhr-Kachel auf der **L**-Stufe (Andi
 * 21.08.: „wenn man die Größe ändert, dass man den Sonnenverlauf anzeigt").
 * Ein flacher Bogen von Aufgang (links) bis Untergang (rechts), die Sonne als
 * Punkt an ihrer ECHTEN Tagesposition. Handgerolltes Mini-SVG, **kein
 * Chart-Paket, +0 KB Bundle** — Muster {@link ../components/StageSparkline}:
 * reine Layout-Funktion ({@link computeSunArcLayout}) getrennt von der
 * Render-Schicht, Theme-Token statt eigener Farben, keine eigene Animation
 * (⇒ kein eigener `prefers-reduced-motion`-Riegel nötig, das Bild ist statisch
 * und ändert sich nur mit dem Minuten-Tick der Uhr).
 *
 * **Verdien-Regel (§2.3 „L erfindet niemals Inhalt"):** ohne
 * `sunriseEpochMs`/`sunsetEpochMs` erscheint der Bogen **gar nicht**. Die
 * L-Uhr sieht dann exakt aus wie vorher — kein Loch, kein Platzhalter, keine
 * gerechnete Ersatz-Sonne. Dasselbe gilt für unbrauchbare Daten (Untergang
 * nicht nach Aufgang, NaN): lieber kein Bild als ein falsches.
 *
 * **Warum die Sonne HTML ist und nicht `<circle>`:** das SVG wird mit
 * `preserveAspectRatio="none"` auf die Kachelbreite gedehnt (Muster
 * {@link ../components/WeatherHourly}) — ein Kreis darin würde zur Ellipse.
 * Die Sonne, der Mond und die zwei Uhrzeiten liegen deshalb als HTML über der
 * Zeichenfläche, prozentual positioniert; nur die Striche stehen im SVG, jeder
 * mit `vector-effect: non-scaling-stroke`. Genau so beschriftet der
 * Stunden-Verlauf schon heute seine Temperatur-Marken.
 *
 * **Die Nacht-Rate-Stelle (RESULT.md):** tagsüber ist die Position eine reine
 * Messung — `(jetzt − Aufgang) / (Untergang − Aufgang)`. Nachts kennt die
 * Kachel den fehlenden Anker nicht: der Abend-Nacht endet an MORGENS
 * Aufgang, die Morgen-Nacht begann an GESTERNS Untergang, und das Wire liefert
 * nur HEUTE. Statt einen Wert zu erfinden, verschiebt die Rechnung den
 * bekannten Anker um **exakt 24 h** (`sunrise + 24 h` bzw. `sunset − 24 h`) —
 * eine benannte Annahme mit ein bis zwei Minuten Fehler in unseren Breiten,
 * keine erfundene Zahl. Damit sie nie als Messung durchgeht, ist der
 * Nacht-Punkt sichtbar ein ANDERER: hohl, gedimmt, **unter** dem Horizont.
 *
 * **Seit 23.08.: nachts steht hier der MOND, nicht der Bogen** (Andi wörtlich:
 * „Bei dem goßen sonnenstand möchte ich in der nacht die mondphase angezeigt
 * haben :)"). Bis dahin stand hier ein leerer Ring mit der Begründung, eine
 * Sichel behaupte eine Phase, und eine Phase habe niemand geholt. Das war
 * richtig, solange niemand rechnete — jetzt rechnet {@link ./moonPhase} sie
 * lokal nach Meeus, und die Sichel behauptet nichts mehr, sie zeigt.
 *
 * Der Umschalter ist derselbe wie vorher: {@link sunPhase}`.daytime`, also die
 * ECHTEN Zeiten aus dem `WeatherToday`-Vertrag. Kein Hardcode, keine zweite
 * Nacht-Definition — und ohne Sonnenzeiten erscheint weiterhin gar nichts.
 */

/** Koordinatenraum des SVG (keine Pixel — CSS dehnt ihn auf die Kachelbreite). */
export const SUN_ARC_WIDTH = 240;
export const SUN_ARC_HEIGHT = 72;
/** Die Horizontlinie — Fuß des Bogens, Trennstrich zwischen Tag und Nacht. */
export const SUN_ARC_HORIZON_Y = 54;
/** Scheitel des Bogens (Mittag). Der Abstand zum Horizont ist die Bogenhöhe. */
const SUN_ARC_APEX_Y = 12;
/** Waagerechte Füße: links = Aufgang, rechts = Untergang. */
const SUN_ARC_LEFT_X = 16;
const SUN_ARC_RIGHT_X = 224;
/** Stützstellen der Bogen-Polylinie — 40 sind bei 200 Einheiten Breite glatt. */
const SUN_ARC_SAMPLES = 40;

/**
 * Die Mondscheibe teilt sich den Koordinatenraum mit dem Bogen (240 × 72), aber
 * NICHT dessen `preserveAspectRatio="none"`: ein gedehnter Kreis wäre eine
 * Ellipse, und eine Ellipse ist kein Mond. Ihr SVG passt sich mit
 * `xMidYMid meet` ein und steht damit mittig in der Kachelbreite.
 */
const MOON_CX = 120;
const MOON_CY = 34;
const MOON_R = 30;

/** Ein Tag ist 24 h — die benannte Annahme der Nacht-Interpolation (s. oben). */
export const DAY_MS = 24 * 60 * 60 * 1000;

/** Sonnenauf-/-untergang des Tages, wie sie im `WeatherToday`-Vertrag stehen. */
export interface SunTimes {
  sunriseEpochMs: number;
  sunsetEpochMs: number;
}

/**
 * Wo im Tag/in der Nacht steht die Sonne gerade?
 *
 * `null` ⇒ die Frage ist mit diesen Daten nicht beantwortbar (fehlend, NaN,
 * oder ein Untergang, der nicht nach dem Aufgang liegt) — der Aufrufer zeichnet
 * dann NICHTS.
 */
export interface SunPhase {
  /** true = die Sonne steht über dem Horizont. */
  daytime: boolean;
  /**
   * 0..1 — bei Tag der Anteil des Tageslichts, der vorbei ist; bei Nacht der
   * Anteil der Nacht (0 = gerade untergegangen, 1 = gleich Aufgang).
   */
  fraction: number;
}

/** 0..1, nie NaN — schützt vor gealterten Wire-Daten (Aufgang von vorgestern). */
function clamp01(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.min(Math.max(value, 0), 1);
}

/**
 * Die einzige Rechen-Wahrheit dieses Bildes. Tag = Messung, Nacht = Messung mit
 * EINER benannten Annahme (24-h-Tag, s. Kopfkommentar).
 *
 * Die Ränder gehören bewusst zum TAG: genau am Aufgang steht die Sonne auf dem
 * linken Fuß (`fraction` 0), genau am Untergang auf dem rechten (`fraction` 1).
 * Ein Punkt, der im selben Augenblick unter den Horizont springt, wäre für den
 * Betrachter ein Fehler, nicht eine Feinheit.
 */
export function sunPhase(nowMs: number, sun: SunTimes | null): SunPhase | null {
  if (sun === null) return null;
  const { sunriseEpochMs: rise, sunsetEpochMs: set } = sun;
  if (!Number.isFinite(rise) || !Number.isFinite(set) || !Number.isFinite(nowMs)) return null;
  if (set <= rise) return null; // kein Tageslicht-Fenster ⇒ kein Bogen (statt eines geteilten Nenners)

  if (nowMs >= rise && nowMs <= set) {
    return { daytime: true, fraction: clamp01((nowMs - rise) / (set - rise)) };
  }
  // Nacht — der fehlende Anker kommt aus dem 24-h-Versatz des bekannten.
  const [from, to] = nowMs > set ? [set, rise + DAY_MS] : [set - DAY_MS, rise];
  return { daytime: false, fraction: clamp01((nowMs - from) / (to - from)) };
}

/** Ein prozentual positionierter Marker (HTML über der gedehnten Zeichenfläche). */
export interface SunArcMarker {
  leftPercent: number;
  topPercent: number;
}

export interface SunArcLayout {
  width: number;
  height: number;
  /** `points`-Attribut der Bogen-Polylinie. */
  arcPoints: string;
  horizonY: number;
  phase: SunPhase;
  /** Die Sonne an ihrer echten Position auf dem Bogen. */
  sun: SunArcMarker;
  /**
   * Die zwei Bogenfüße in Prozent der Breite. Die Uhrzeiten darunter werden
   * damit **absolut** positioniert statt links/rechts angeschlagen: „06:22"
   * steht genau unter dem Fuß, an dem der Bogen beginnt. Dieselbe Mechanik wie
   * die Stundenachse in `WeatherHourly` — ein Bezugspunkt, der um ein paar
   * Prozent daneben liegt, ist kein Bezugspunkt.
   */
  riseLeftPercent: number;
  setLeftPercent: number;
}

/** x-Koordinate zum Tagesanteil f (0 = Aufgangs-Fuß, 1 = Untergangs-Fuß). */
function arcX(f: number): number {
  return SUN_ARC_LEFT_X + f * (SUN_ARC_RIGHT_X - SUN_ARC_LEFT_X);
}

/**
 * y-Koordinate zum Tagesanteil f — eine **Sinus-Halbwelle**, nicht eine
 * Bézier-Kurve: nur so liegt der Sonnenpunkt exakt auf dem gezeichneten
 * Strich (dieselbe Formel bestimmt beide), und die Form ist zufällig auch die
 * physikalisch ehrliche für den Sonnenstand.
 */
function arcY(f: number): number {
  return SUN_ARC_HORIZON_Y - Math.sin(Math.PI * f) * (SUN_ARC_HORIZON_Y - SUN_ARC_APEX_Y);
}

/**
 * Reine Layout-Berechnung des TAG-Bildes: Bogen, Horizont, Sonnenpunkt. Kein
 * DOM, keine Hooks — direkt unit-testbar (Muster `computeWeatherHourlyLayout`).
 *
 * `null` ⇒ hier ist kein Bogen zu zeichnen. Das hat seit dem 23.08. **zwei**
 * Gründe: die Frage ist unbeantwortbar (s. {@link sunPhase}) — **oder es ist
 * Nacht**, und dann gehört das Bild dem Mond ({@link ./moonPhase}), nicht einer
 * gedimmten Sonne unter dem Horizont. Der Aufrufer fragt darum weiterhin zuerst
 * {@link sunPhase} und entscheidet daran, welches der beiden Bilder er baut.
 */
export function computeSunArcLayout(nowMs: number, sun: SunTimes | null): SunArcLayout | null {
  const phase = sunPhase(nowMs, sun);
  if (phase === null || !phase.daytime) return null;

  const arc: string[] = [];
  for (let i = 0; i <= SUN_ARC_SAMPLES; i++) {
    const f = i / SUN_ARC_SAMPLES;
    arc.push(`${round(arcX(f))},${round(arcY(f))}`);
  }

  return {
    width: SUN_ARC_WIDTH,
    height: SUN_ARC_HEIGHT,
    arcPoints: arc.join(' '),
    horizonY: SUN_ARC_HORIZON_Y,
    phase,
    sun: marker(arcX(phase.fraction), arcY(phase.fraction)),
    riseLeftPercent: marker(SUN_ARC_LEFT_X, 0).leftPercent,
    setLeftPercent: marker(SUN_ARC_RIGHT_X, 0).leftPercent,
  };
}

function marker(x: number, y: number): SunArcMarker {
  return {
    leftPercent: round((x * 100) / SUN_ARC_WIDTH),
    topPercent: round((y * 100) / SUN_ARC_HEIGHT),
  };
}

/** Zwei Nachkommastellen — genug für 240 Einheiten, hält das Markup lesbar. */
function round(value: number): number {
  return Math.round(value * 100) / 100;
}

export interface SunArcProps {
  /** Jetzt (derselbe Minuten-Tick, der die Uhr treibt). */
  nowMs: number;
  /** Sonnenzeiten aus `WeatherToday` — `null` ⇒ die Komponente rendert NICHTS. */
  sun: SunTimes | null;
  /** Texte/Locale-Injektion für die puren Tests (Muster `weatherNowContent`). */
  strings?: SunArcStrings;
  locale?: string;
}

/**
 * Rendert **nichts** ohne brauchbare Sonnenzeiten — die L-Uhr steht dann da wie
 * vor dieser Erweiterung (Verdien-Regel, s. Kopfkommentar).
 *
 * Die zwei Uhrzeiten unter den Bogenfüßen sind `aria-hidden`: der ganze Satz
 * („Sonnenverlauf, Aufgang 06:22, Untergang 20:48, die Sonne steht am Himmel")
 * steht im `aria-label` des Bildes, damit ein Screenreader nicht zwei nackte
 * Zahlen ohne Bezug vorliest.
 */
export function SunArc({ nowMs, sun, strings, locale: localeProp }: SunArcProps) {
  const ui = useUiStrings();
  const t = strings ?? ui.idleFace.uhr.sun;
  const locale = localeProp ?? ui.locale;
  const phase = sunPhase(nowMs, sun);
  if (phase === null || sun === null) return null;

  const rise = dueClock(sun.sunriseEpochMs, locale);
  const set = dueClock(sun.sunsetEpochMs, locale);

  if (!phase.daytime) return <MoonFigure nowMs={nowMs} rise={rise} strings={t} locale={locale} />;

  const layout = computeSunArcLayout(nowMs, sun);
  if (layout === null) return null;

  return (
    <figure className="idle__sunarc" data-phase="day">
      <div className="idle__sunarcplot">
        <svg
          className="idle__sunarcchart"
          viewBox={`0 0 ${layout.width} ${layout.height}`}
          preserveAspectRatio="none"
          role="img"
          aria-label={t.aria(rise, set, t.dayPhase)}
        >
          <line
            className="idle__sunarchorizon"
            x1={0}
            y1={layout.horizonY}
            x2={layout.width}
            y2={layout.horizonY}
          />
          <polyline className="idle__sunarcline" points={layout.arcPoints} />
        </svg>
        {/* Die Sonne als HTML — im gedehnten SVG wäre sie eine Ellipse. */}
        <span
          className="idle__sunarcsun"
          style={{ left: `${layout.sun.leftPercent}%`, top: `${layout.sun.topPercent}%` }}
          aria-hidden="true"
          title={t.dayPhase}
        />
      </div>
      <figcaption className="idle__sunarctimes" aria-hidden="true">
        <span className="idle__sunarctime" style={{ left: `${layout.riseLeftPercent}%` }}>
          {rise}
        </span>
        <span className="idle__sunarctime" style={{ left: `${layout.setLeftPercent}%` }}>
          {set}
        </span>
      </figcaption>
    </figure>
  );
}

/**
 * **Das Nachtbild**: die Mondscheibe in ihrer echten Phase, darunter ihr Name.
 *
 * Zwei Kreise und ein Pfad, dieselbe Linienarbeit wie der Bogen (Theme-Token,
 * `vector-effect: non-scaling-stroke`, keine Animation, kein Emoji): der Umriss
 * ist die ganze Scheibe, der Pfad ihr beleuchteter Teil ({@link moonLitPath} —
 * die Geometrie steht dort, nicht hier).
 *
 * `%` kommt aus `toLocaleString`, nicht aus einem angehängten Zeichen: Prozent
 * setzt nicht jede Sprache gleich (fr schreibt „62 %", en „62%").
 */
function MoonFigure({
  nowMs,
  rise,
  strings,
  locale,
}: {
  nowMs: number;
  rise: string;
  strings: SunArcStrings;
  locale: string;
}) {
  const moon = moonPhase(nowMs);
  if (moon === null) return null;
  const name = strings.moon.phases[moon.name];
  const percent = moon.illumination.toLocaleString(locale, {
    style: 'percent',
    maximumFractionDigits: 0,
  });
  const lit = moonLitPath(moon, MOON_CX, MOON_CY, MOON_R);

  return (
    <figure className="idle__sunarc idle__moon" data-phase="night" data-moon={moon.name}>
      <div className="idle__sunarcplot">
        <svg
          className="idle__moonchart"
          viewBox={`0 0 ${SUN_ARC_WIDTH} ${SUN_ARC_HEIGHT}`}
          preserveAspectRatio="xMidYMid meet"
          role="img"
          aria-label={strings.moon.aria(name, percent, rise)}
        >
          <circle className="idle__moondisc" cx={MOON_CX} cy={MOON_CY} r={MOON_R} />
          {lit && <path className="idle__moonlit" d={lit} />}
        </svg>
      </div>
      <figcaption className="idle__moonname" aria-hidden="true">
        {name}
      </figcaption>
    </figure>
  );
}

/** Katalog-Default für die puren Aufrufe der Tests (Muster `IDLE_FACE_TEXTS`). */
export const SUN_ARC_TEXTS: SunArcStrings = de.idleFace.uhr.sun;

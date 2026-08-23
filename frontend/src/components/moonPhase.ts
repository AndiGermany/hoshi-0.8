/**
 * **moonPhase** — die Mondphase zu einem Zeitpunkt, lokal gerechnet.
 *
 * Andi (23.08.): *„Bei dem goßen sonnenstand möchte ich in der nacht die
 * mondphase angezeigt haben :)"*
 *
 * Bis hierher stand im Nachtbild der L-Uhr ausdrücklich **kein** Mond, sondern
 * ein leerer Ring — mit der Begründung im `SunArc`-Kopf: *„eine Sichel
 * behauptet eine Phase, und eine Phase hat niemand geholt."* Das war richtig,
 * solange niemand rechnete. Jetzt rechnet jemand, und zwar hier: keine neue
 * API, keine neue Abhängigkeit, keine geratene Zahl — die Standard-Formel.
 *
 * ## Die Formel (Belegstelle)
 *
 * Jean Meeus, *Astronomical Algorithms*, 2. Auflage:
 *
 *  - **Kap. 47** (Position of the Moon), Gl. 47.2–47.4: die drei mittleren
 *    Winkel `D` (mittlere Elongation Mond–Sonne), `M` (mittlere Anomalie der
 *    Sonne), `M'` (mittlere Anomalie des Mondes) als Polynome in `T`, den
 *    julianischen Jahrhunderten seit J2000.0.
 *  - **Kap. 48** (Illuminated Fraction of the Moon's Disk), Gl. 48.4 und 48.1:
 *    der Phasenwinkel `i` aus diesen drei Winkeln und daraus der beleuchtete
 *    Anteil `k = (1 + cos i)/2`.
 *
 * Meeus gibt für Gl. 48.4 einen Fehler von **rund 0,0014 in `k`** an (also
 * ~0,14 Prozentpunkte) — das ist drei Größenordnungen genauer, als ein Bild von
 * 60 px Durchmesser auflösen kann, und es ist die Genauigkeit einer RECHNUNG,
 * nicht einer Schätzung. Die verbreitete „Tage seit einem Referenz-Neumond
 * modulo 29,53"-Näherung wäre um bis zu **14 Stunden** daneben (die Mondbahn ist
 * eine Ellipse) und hätte an einem Abend „Vollmond" gesagt, an dem der Mond
 * sichtbar noch nicht voll ist. Genau das wäre die erfundene Zahl gewesen, die
 * das Haus nicht zeigt.
 *
 * Rein: kein DOM, kein Netz, keine Hooks (Muster `homeLayout.ts`/`greeting.ts`).
 * Die Zeichen-Seite ({@link moonLitPath}) ist ebenfalls hier, weil sie reine
 * Geometrie ist — `SunArc.tsx` malt nur noch, was hier steht.
 */

/** Mittlere synodische Monatslänge in Tagen (Meeus Kap. 49) — nur für `ageDays`. */
export const SYNODIC_MONTH_DAYS = 29.530588861;

/** Julianisches Datum des Unix-Nullpunkts — die Brücke von `Date.now()` zu Meeus. */
const JD_UNIX_EPOCH = 2440587.5;
/** J2000.0 als julianisches Datum — Nullpunkt aller Polynome in Kap. 47. */
const JD_J2000 = 2451545.0;
const MS_PER_DAY = 86400000;
const DEG = Math.PI / 180;

/**
 * Die acht Phasen, die ein Mensch benennt. Die vier „exakten" (neu, erstes
 * Viertel, voll, letztes Viertel) sind **schmale Fenster** um den echten
 * Zeitpunkt, nicht Achtel des Monats: „Vollmond" für vier Tage am Stück wäre
 * ein Wort, das dem Bild widerspricht, sobald die Sichel sichtbar angeknabbert
 * ist. Siehe {@link PHASE_WINDOW}.
 */
export type MoonPhaseName =
  | 'new'
  | 'waxingCrescent'
  | 'firstQuarter'
  | 'waxingGibbous'
  | 'full'
  | 'waningGibbous'
  | 'lastQuarter'
  | 'waningCrescent';

/**
 * Halbe Breite der vier exakten Phasen, gemessen im Mondzyklus (0..1).
 * 0,03 × 29,53 d ≈ **0,89 Tage** zu jeder Seite, also gut anderthalb Tage, in
 * denen „Vollmond" gesagt werden darf. Das deckt sich mit dem, was das Auge
 * einen Vollmond nennt (bei ±0,9 d sind noch rund 99 % der Scheibe hell) und
 * bleibt weit von der Halbmond-Grenze entfernt.
 */
const PHASE_WINDOW = 0.03;

export interface MoonPhase {
  /**
   * Phasenwinkel Sonne–Mond–Erde in Grad, 0..180: **0 = Vollmond,
   * 180 = Neumond**. Das ist Meeus' `i`, nicht der Mondalter-Winkel.
   */
  phaseAngleDeg: number;
  /** Beleuchteter Anteil der Scheibe, 0..1 (Meeus 48.1). */
  illumination: number;
  /** Nimmt der Mond zu? (Sichel rechts auf der Nordhalbkugel.) */
  waxing: boolean;
  /** Stelle im Zyklus, 0..1: 0 = Neumond, 0,25 = erstes Viertel, 0,5 = Vollmond. */
  cyclePosition: number;
  /** Mondalter in Tagen seit Neumond — {@link cyclePosition} × synodischer Monat. */
  ageDays: number;
  name: MoonPhaseName;
}

/** Grad in [0, 360) — die Polynome unten laufen über Jahrhunderte weit hinaus. */
function mod360(deg: number): number {
  const r = deg % 360;
  return r < 0 ? r + 360 : r;
}

const sinDeg = (deg: number) => Math.sin(deg * DEG);

/**
 * Die Mondphase zu `nowMs` (Unix-Millisekunden, UTC — die Formel kennt keine
 * Zeitzone, und die Phase auch nicht: der Mond steht für alle gleich).
 *
 * `null` bei einem unbrauchbaren Zeitpunkt (NaN/Infinity) — lieber kein Bild
 * als ein falsches, dieselbe Regel wie beim Sonnenbogen.
 */
export function moonPhase(nowMs: number): MoonPhase | null {
  if (!Number.isFinite(nowMs)) return null;

  const jde = nowMs / MS_PER_DAY + JD_UNIX_EPOCH;
  const T = (jde - JD_J2000) / 36525;
  const T2 = T * T;
  const T3 = T2 * T;
  const T4 = T3 * T;

  // Meeus 47.2 — mittlere Elongation des Mondes von der Sonne.
  const D =
    297.8501921 + 445267.1114034 * T - 0.0018819 * T2 + T3 / 545868 - T4 / 113065000;
  // Meeus 47.3 — mittlere Anomalie der Sonne.
  const M = 357.5291092 + 35999.0502909 * T - 0.0001536 * T2 + T3 / 24490000;
  // Meeus 47.4 — mittlere Anomalie des Mondes.
  const Mp =
    134.9633964 + 477198.8675055 * T + 0.0087414 * T2 + T3 / 69699 - T4 / 14712000;

  // Meeus 48.4 — Phasenwinkel i. Die sechs Störungsglieder sind der ganze
  // Unterschied zur „modulo 29,53"-Näherung: sie holen die Ellipse zurück.
  const i =
    180 -
    D -
    6.289 * sinDeg(Mp) +
    2.1 * sinDeg(M) -
    1.274 * sinDeg(2 * D - Mp) -
    0.658 * sinDeg(2 * D) -
    0.214 * sinDeg(2 * Mp) -
    0.11 * sinDeg(D);

  /*
   * `i` läuft mit `D` monoton nach unten: von +180° (Neumond) über 0°
   * (Vollmond) auf −180° (nächster Neumond). Nach `mod360` heißt das:
   * 180 → 0 ist der ZUNEHMENDE Halbzyklus, 360 → 180 der abnehmende.
   */
  const norm = mod360(i);
  const waxing = norm <= 180;
  const phaseAngleDeg = waxing ? norm : 360 - norm;
  const illumination = (1 + Math.cos(phaseAngleDeg * DEG)) / 2;
  const cyclePosition = waxing ? (180 - phaseAngleDeg) / 360 : (180 + phaseAngleDeg) / 360;

  return {
    phaseAngleDeg,
    illumination,
    waxing,
    cyclePosition,
    ageDays: cyclePosition * SYNODIC_MONTH_DAYS,
    name: moonPhaseName(cyclePosition),
  };
}

/**
 * Zyklusstelle ⇒ Name. Die vier exakten Phasen bekommen ein schmales Fenster
 * ({@link PHASE_WINDOW}), alles dazwischen heißt Sichel bzw. „mehr als halb".
 * Der Neumond wird an BEIDEN Enden geprüft (0 und 1 sind derselbe Punkt).
 */
export function moonPhaseName(cyclePosition: number): MoonPhaseName {
  const p = ((cyclePosition % 1) + 1) % 1;
  if (p < PHASE_WINDOW || p > 1 - PHASE_WINDOW) return 'new';
  if (Math.abs(p - 0.25) < PHASE_WINDOW) return 'firstQuarter';
  if (Math.abs(p - 0.5) < PHASE_WINDOW) return 'full';
  if (Math.abs(p - 0.75) < PHASE_WINDOW) return 'lastQuarter';
  if (p < 0.25) return 'waxingCrescent';
  if (p < 0.5) return 'waxingGibbous';
  if (p < 0.75) return 'waningGibbous';
  return 'waningCrescent';
}

/**
 * **Der beleuchtete Teil der Scheibe als SVG-Pfad.**
 *
 * Geometrie statt Glyphen-Tabelle: die Lichtgrenze (Terminator) ist der Rand
 * der beleuchteten Halbkugel, und der projiziert sich als **Halb-Ellipse** mit
 * derselben Höhe wie die Scheibe und der halben Breite
 * `r · |cos i| = r · |2k − 1|`. Deshalb genügen zwei Bögen:
 *
 *   1. der beleuchtete Rand — ein echter Halbkreis (rechts bei zunehmendem,
 *      links bei abnehmendem Mond; Nordhalbkugel),
 *   2. der Terminator zurück — die Halb-Ellipse. Bei mehr als halb voll wölbt
 *      sie sich in die DUNKLE Hälfte, bei weniger als halb in die helle. Genau
 *      das ist der Unterschied zwischen Sichel und „mehr als halb", und er
 *      steckt allein im `sweep`-Flag.
 *
 * Bei einem exakten Halbmond wird die Ellipse zur Geraden (`rx = 0`); SVG malt
 * dann eine gerade Linie, was genau richtig ist.
 *
 * Leerer String ⇒ **nichts zu zeichnen** (Neumond): der Aufrufer malt dann nur
 * den Umriss. Ein „fast unsichtbarer" Pfad wäre ein Strich, den niemand als
 * Mond liest.
 */
export function moonLitPath(
  phase: MoonPhase,
  cx: number,
  cy: number,
  r: number,
): string {
  const k = Math.min(Math.max(phase.illumination, 0), 1);
  if (k <= 0.005) return '';
  const top = `${round(cx)},${round(cy - r)}`;
  const bottom = `${round(cx)},${round(cy + r)}`;
  const rx = round(r * Math.abs(2 * k - 1));
  // Beleuchteter Rand: zunehmend rechts (Uhrzeigersinn = sweep 1), abnehmend links.
  const limbSweep = phase.waxing ? 1 : 0;
  /*
   * Terminator: er wölbt sich bei k > 0,5 in die dunkle Hälfte, bei k < 0,5 in
   * die helle. Von unten nach oben ist „nach links" der Uhrzeigersinn (sweep 1)
   * — für den zunehmenden Mond ist links die dunkle Seite, für den abnehmenden
   * die helle, daher die Spiegelung.
   */
  const gibbous = k > 0.5;
  const terminatorSweep = phase.waxing ? (gibbous ? 1 : 0) : gibbous ? 0 : 1;
  return (
    `M ${top} A ${round(r)},${round(r)} 0 0 ${limbSweep} ${bottom}` +
    ` A ${rx},${round(r)} 0 0 ${terminatorSweep} ${top} Z`
  );
}

/** Zwei Nachkommastellen — dieselbe Regel wie im Sonnenbogen. */
function round(value: number): number {
  return Math.round(value * 100) / 100;
}

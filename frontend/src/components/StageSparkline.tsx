/**
 * `<StageSparkline>` — handgerollte SVG-Sparkline für den Tages-Verlauf EINER
 * Pipeline-Stage (kein Chart-Package, +0 KB Bundle). Sitzt unter der
 * p50/p95-Zeile jeder Stage-Kachel in {@link ../views/AktivitaetView}.
 *
 * Ehrlichkeits-Regeln (Fortsetzung der Gesetze aus `stageStats.ts`):
 *  - x = Turn-Index über ALLE heutigen Turns (nicht Echtzeit — vermeidet
 *    Leerflächen bei unregelmäßigen Turns; der Zeitpunkt steht im Tooltip).
 *  - Ein Turn ohne Wert für diese Stage erzeugt KEINEN Punkt UND bricht die
 *    Linie — kein Interpolieren über die Lücke hinweg.
 *  - Unter 3 Messwerten insgesamt wird NIE eine Linie gezeichnet (nur Punkte)
 *    — zwei Punkte suggerieren sonst einen Trend, den es nicht gibt.
 *  - Skala: linear mit Deckel = clamp(max(2×p95_heute, 1000 ms), ..., 20000 ms)
 *    (siehe `sparklineCap`). Werte über dem Deckel werden NICHT verfälscht —
 *    nur die Zeichenposition wird auf den Deckel geklemmt und als ▲ markiert;
 *    der echte Messwert steht im nativen `<title>`-Tooltip.
 *  - Fehler-Turns (`error !== null`) sind hohle Punkte (`--error`).
 *  - Keine Achsen, keine Animation (reduced-motion ist bereits global in
 *    index.css geregelt — hier gibt es ohnehin nichts zu beruhigen).
 *  - Texte bleiben AUSSERHALB des skalierten SVG (kein `preserveAspectRatio`-
 *    Textverzerrungs-Risiko): die p50/p95-Zahlen stehen schon in der Kachel
 *    darüber, im SVG selbst steht kein Text.
 *
 * Reine Layout-Berechnung (`computeSparklineLayout`) ist von der Render-Schicht
 * getrennt — testbar ohne DOM/Renderer.
 */

/** Ein Punkt der Tages-Serie: ms=null ist eine EHRLICHE Lücke (nicht gemessen). */
export interface StageSparklinePoint {
  ms: number | null;
  /** ISO-Zeitpunkt des Turns (Tooltip-Text). */
  ts: string;
  /** Fehler-Turn (`turn.error !== null`) → hohler Punkt statt gefüllt. */
  error?: boolean;
}

/** Ein tatsächlich gezeichneter Punkt (nur Einträge mit `ms !== null`). */
export interface PlottedStagePoint {
  /** Index im ursprünglichen `points`-Array — bestimmt Nachbarschaft für Linien-Segmente. */
  index: number;
  x: number;
  y: number;
  /** Der ECHTE Messwert (nie geclampt — nur `y` ist auf den Deckel geklemmt). */
  ms: number;
  /** true = Wert liegt über dem Deckel (Zeichenposition geklemmt, ▲-Marker). */
  outlier: boolean;
  error: boolean;
  /** Nativer Tooltip-Text („14:32 · 890 ms" bzw. „… (Ausreißer)"/„… · Fehler"). */
  tooltip: string;
}

export interface StageSparklineLayout {
  cap: number;
  width: number;
  height: number;
  /** Nur Punkte mit echtem Messwert, in Original-Reihenfolge. */
  plotted: PlottedStagePoint[];
  /** Zusammenhängende Läufe (Original-Index lückenlos) mit ≥2 Punkten — je eine Polyline. */
  segments: PlottedStagePoint[][];
  /** false ⇒ nur Punkte zeichnen, KEINE Linie (< 3 Messwerte insgesamt). */
  showLine: boolean;
  p50Y: number | null;
  p95Y: number | null;
  /** p95 > 3×p50 — stiller Hinweis (p95-Referenzlinie + p95-Text in --warn). */
  p95Warn: boolean;
}

/** Reservierter Rand oben (Platz für den ▲-Ausreißer-Marker) / unten (Stroke-Puffer). */
const TOP_PAD = 6;
const BOTTOM_PAD = 2;

/**
 * y-Deckel der linearen Skala: `clamp(max(2×p95, 1000 ms), ..., 20000 ms)`.
 * p95=0/keine Daten ⇒ der 1-Sekunden-Boden greift.
 */
export function sparklineCap(p95: number): number {
  const withFloor = Math.max(2 * p95, 1000);
  return Math.min(withFloor, 20000);
}

/** Wert → y-Koordinate (0..height), auf `[0, cap]` geklemmt (nie NaN, nie negativ). */
export function mapValueToY(value: number, cap: number, height: number): number {
  const plotH = height - TOP_PAD - BOTTOM_PAD;
  if (cap <= 0) return height - BOTTOM_PAD;
  const clamped = Math.min(Math.max(value, 0), cap);
  return TOP_PAD + (1 - clamped / cap) * plotH;
}

/** p95 deutlich über p50 (>3×)? — einziges Warn-Kriterium, an zwei Stellen genutzt (Ref-Linie + Text). */
export function isP95Elevated(p50: number | null, p95: number | null): boolean {
  return p50 !== null && p95 !== null && p50 > 0 && p95 > 3 * p50;
}

function fmtTooltipTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
}

/** ms-Wert gerundet, nie erfunden ("1234 ms"). */
function fmtTooltipMs(ms: number): string {
  return `${Math.round(ms)} ms`;
}

/**
 * Reine Layout-Berechnung: Skala, Punkte, Linien-Segmente (mit ehrlichen
 * Lücken), Referenzlinien. Kein DOM — direkt unit-testbar.
 */
export function computeSparklineLayout(
  points: StageSparklinePoint[],
  p50: number | null,
  p95: number | null,
  width = 200,
  height = 40,
): StageSparklineLayout {
  const cap = sparklineCap(p95 ?? 0);
  const n = points.length;

  const plotted: PlottedStagePoint[] = [];
  points.forEach((p, i) => {
    if (p.ms === null) return; // ehrliche Lücke: kein Punkt
    const x = n > 1 ? (i / (n - 1)) * width : width / 2;
    const y = mapValueToY(p.ms, cap, height);
    const outlier = p.ms > cap;
    const time = fmtTooltipTime(p.ts);
    const bits = [time, fmtTooltipMs(p.ms)].filter(Boolean);
    let tooltip = bits.join(' · ');
    if (outlier) tooltip += ' (Ausreißer)';
    if (p.error) tooltip += ' · Fehler';
    plotted.push({ index: i, x, y, ms: p.ms, outlier, error: p.error === true, tooltip });
  });

  // Unter 3 Messwerten NIE eine Linie — sonst suggerieren 2 Punkte einen Trend.
  const showLine = plotted.length >= 3;
  const segments: PlottedStagePoint[][] = [];
  if (showLine) {
    let current: PlottedStagePoint[] = [];
    let prevIndex: number | null = null;
    for (const pt of plotted) {
      if (prevIndex !== null && pt.index === prevIndex + 1) {
        current.push(pt);
      } else {
        if (current.length >= 2) segments.push(current);
        current = [pt];
      }
      prevIndex = pt.index;
    }
    if (current.length >= 2) segments.push(current);
  }

  const p50Y = p50 !== null ? mapValueToY(p50, cap, height) : null;
  const p95Y = p95 !== null ? mapValueToY(p95, cap, height) : null;

  return { cap, width, height, plotted, segments, showLine, p50Y, p95Y, p95Warn: isP95Elevated(p50, p95) };
}

export interface StageSparklineProps {
  /** Stage-Label für den `aria-label`, z. B. "STT". */
  label: string;
  /** Heutige Serie, chronologisch (ältester zuerst); `ms=null` = ehrliche Lücke. */
  points: StageSparklinePoint[];
  /** Heutiger Median (aus `aggregateToday`) — Referenzlinie. */
  p50: number | null;
  /** Heutiges p95 (aus `aggregateToday`) — Referenzlinie + Deckel-Basis. */
  p95: number | null;
  /** Interne viewBox-Breite (Koordinatenraum, keine Pixel — CSS skaliert responsiv). */
  width?: number;
  /** Interne viewBox-Höhe. */
  height?: number;
}

/**
 * Rendert NICHTS, wenn kein einziger Messwert vorliegt (ehrlicher Leer-Zustand
 * — keine erfundene Linie, keine leere/kaputte Fläche). Der Aufrufer zeigt in
 * dem Fall ohnehin die bestehende „keine Daten"-Kachel.
 */
export function StageSparkline({ label, points, p50, p95, width = 200, height = 40 }: StageSparklineProps) {
  const layout = computeSparklineLayout(points, p50, p95, width, height);
  if (layout.plotted.length === 0) return null;

  const n = layout.plotted.length;
  const parts = [`${label} heute: ${n} Messwert${n === 1 ? '' : 'e'}`];
  if (p50 !== null) parts.push(`Median ${Math.round(p50)} ms`);
  if (p95 !== null) parts.push(`p95 ${Math.round(p95)} ms`);
  const ariaLabel = parts.join(', ');

  return (
    <svg
      className="stagespark"
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      role="img"
      aria-label={ariaLabel}
    >
      {layout.p50Y !== null && (
        <line className="stagespark__ref" x1={0} y1={layout.p50Y} x2={width} y2={layout.p50Y} />
      )}
      {layout.p95Y !== null && (
        <line
          className={`stagespark__ref${layout.p95Warn ? ' stagespark__ref--warn' : ''}`}
          x1={0}
          y1={layout.p95Y}
          x2={width}
          y2={layout.p95Y}
        />
      )}
      {layout.showLine &&
        layout.segments.map((seg) => (
          <polyline
            key={`seg-${seg[0].index}`}
            className="stagespark__line"
            points={seg.map((p) => `${p.x},${p.y}`).join(' ')}
          />
        ))}
      {layout.plotted.map((p) =>
        p.outlier ? (
          <polygon
            key={p.index}
            className="stagespark__outlier"
            points={`${p.x - 3.2},${p.y + 5.5} ${p.x + 3.2},${p.y + 5.5} ${p.x},${p.y - 1}`}
          >
            <title>{p.tooltip}</title>
          </polygon>
        ) : (
          <circle
            key={p.index}
            className={`stagespark__point${p.error ? ' stagespark__point--error' : ''}`}
            cx={p.x}
            cy={p.y}
            r={2.4}
          >
            <title>{p.tooltip}</title>
          </circle>
        ),
      )}
    </svg>
  );
}

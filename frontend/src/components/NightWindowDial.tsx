import { type PointerEvent as ReactPointerEvent, useId, useRef } from 'react';

/**
 * **NightWindowDial** — das zeitgemäße Herzstück des Nachtmodus-Settings (Andi
 * 12.07, wörtlich „überlege dir etwas zeitgemäßes"): eine runde 24h-Uhr (SVG),
 * Mitternacht oben, im Uhrzeigersinn, mit zwei ziehbaren Griffen „Abend" ({@link
 * NightWindowDialProps.from}) und „Morgen" ({@link NightWindowDialProps.to}).
 * Der Bogen dazwischen (im Uhrzeigersinn from→to, Mitternachts-Rollover erlaubt
 * — exakt `NightModeCompute.inWindow` im Backend) ist mit einem Dämmerungs-
 * Gradient gefüllt.
 *
 * **Bidirektional gekoppelt** mit zwei `<input type="time">` im Elternteil
 * ({@link NightModeSection}): der Dial ist reine Deko-/Maus-Schicht (`aria-hidden`),
 * die Zeitfelder sind der barrierefreie/präzise Pfad — beide schreiben in
 * denselben `from`/`to`-State, daher „springen" die Griffe live mit, wenn man
 * tippt, und umgekehrt aktualisiert Ziehen die Zeitfelder live (5-Min-Snap).
 *
 * Pointer-Events decken Maus UND Touch in einem Modell ab (kein Extra-Code
 * nötig) — `setPointerCapture` hält den Griff „gegriffen", auch wenn der Zeiger
 * kurz aus dem SVG wandert. Die Winkel↔Zeit-Mathematik ({@link timeToAngle}/
 * {@link angleToTime}) ist bewusst PURE (kein State, kein DOM) — direkt testbar.
 */

const MINUTES_PER_DAY = 1440;
const DEG_PER_MIN = 360 / MINUTES_PER_DAY;

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

/**
 * `HH:mm` → Winkel in Grad, `0` = Mitternacht (oben), im Uhrzeigersinn wachsend
 * (`90` = 06:00 rechts, `180` = 12:00 unten, `270` = 18:00 links). Unparsebare
 * Eingaben liefern `0` (never-throw an der Render-Naht, wie `NightModeCompute`
 * im Backend nie wirft).
 */
export function timeToAngle(time: string): number {
  const m = /^(\d{1,2}):(\d{1,2})$/.exec(time.trim());
  if (!m) return 0;
  const h = Number(m[1]);
  const min = Number(m[2]);
  if (!Number.isFinite(h) || !Number.isFinite(min)) return 0;
  const minutes = (((h * 60 + min) % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY;
  return minutes * DEG_PER_MIN;
}

/**
 * Winkel (Grad, beliebiger Wert — wird auf `[0,360)` normalisiert) → `HH:mm`,
 * gerastet auf `snapMinutes` (Default 5 — der Ziehen-Schritt). `360°`/`1440min`
 * rollen sauber auf `00:00` (Mitternachts-Rollover beim Ziehen über die 12-Uhr-
 * Position hinweg).
 */
export function angleToTime(angle: number, snapMinutes = 5): string {
  const normalized = ((angle % 360) + 360) % 360;
  const rawMinutes = normalized / DEG_PER_MIN;
  const snapUnit = Math.max(1, snapMinutes);
  let snapped = Math.round(rawMinutes / snapUnit) * snapUnit;
  snapped = ((snapped % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY;
  const h = Math.floor(snapped / 60);
  const min = snapped % 60;
  return `${pad2(h)}:${pad2(min)}`;
}

function pointAt(angleDeg: number, r: number, cx: number, cy: number): { x: number; y: number } {
  const rad = (angleDeg * Math.PI) / 180;
  return { x: cx + r * Math.sin(rad), y: cy - r * Math.cos(rad) };
}

/** Zeiger-Position (Client-Koordinaten) → Winkel relativ zur Kreis-Mitte (`rect`-Zentrum). */
function angleFromClientPoint(clientX: number, clientY: number, rect: { left: number; top: number; width: number; height: number }): number {
  const cx = rect.left + rect.width / 2;
  const cy = rect.top + rect.height / 2;
  const dx = clientX - cx;
  const dy = clientY - cy;
  const deg = (Math.atan2(dx, -dy) * 180) / Math.PI;
  return ((deg % 360) + 360) % 360;
}

/** Defensives Pointer-Capture: jsdom/ältere Browser kennen die Methode evtl. nicht. */
function tryCapture(el: Element, pointerId: number): void {
  try {
    el.setPointerCapture?.(pointerId);
  } catch {
    /* kein Capture — die Griffe bleiben trotzdem ziehbar (Capture ist nur Komfort). */
  }
}
function tryRelease(el: Element, pointerId: number): void {
  try {
    el.releasePointerCapture?.(pointerId);
  } catch {
    /* ignore */
  }
}

const HOURS = Array.from({ length: 24 }, (_, h) => h);
const MAJOR_HOURS = [0, 6, 12, 18];

export interface NightWindowDialProps {
  /** Fenster-Start (`HH:mm`) — der „Abend"-Griff. */
  from: string;
  /** Fenster-Ende (`HH:mm`) — der „Morgen"-Griff. */
  to: string;
  onFromChange: (time: string) => void;
  onToChange: (time: string) => void;
  /** Kantenlänge des SVG in px (quadratisch). Default 240. */
  size?: number;
  /** Aus (Master-Toggle) ⇒ Griffe nicht ziehbar, gedämpfte Optik. */
  disabled?: boolean;
  /** Für die „Jetzt"-Markierung; Default die echte Uhrzeit (injizierbar für Tests). */
  now?: Date;
}

export function NightWindowDial({
  from,
  to,
  onFromChange,
  onToChange,
  size = 240,
  disabled = false,
  now = new Date(),
}: NightWindowDialProps) {
  const gradientId = useId();
  const svgRef = useRef<SVGSVGElement>(null);
  const draggingRef = useRef<{ pointerId: number; handle: 'from' | 'to' } | null>(null);

  const center = size / 2;
  const stroke = Math.max(10, size * 0.065);
  const padding = Math.max(20, size * 0.11);
  const radius = center - padding - stroke / 2;
  const tickOuter = center - padding + 4;

  const fromAngle = timeToAngle(from);
  const toAngle = timeToAngle(to);
  const nowAngle = timeToAngle(`${pad2(now.getHours())}:${pad2(now.getMinutes())}`);

  const fromPoint = pointAt(fromAngle, radius, center, center);
  const toPoint = pointAt(toAngle, radius, center, center);
  const nowPoint = pointAt(nowAngle, radius, center, center);

  // Bogen im Uhrzeigersinn from→to; sweep=0 (from===to) heißt „volle 24h" (ALWAYS-
  // artiger Randfall), nicht „kein Fenster" — daher || 360 statt eines Nullbogens.
  const sweep = ((toAngle - fromAngle + 360) % 360) || 360;
  const largeArcFlag = sweep > 180 ? 1 : 0;
  const isFullCircle = Math.abs(((fromAngle - toAngle + 360) % 360)) < 0.01;

  const arcPath = isFullCircle
    ? null
    : `M ${fromPoint.x} ${fromPoint.y} A ${radius} ${radius} 0 ${largeArcFlag} 1 ${toPoint.x} ${toPoint.y}`;

  const angleForEvent = (e: ReactPointerEvent<SVGCircleElement>): number | null => {
    const rect = svgRef.current?.getBoundingClientRect();
    if (!rect) return null;
    return angleFromClientPoint(e.clientX, e.clientY, rect);
  };

  const beginDrag = (handle: 'from' | 'to') => (e: ReactPointerEvent<SVGCircleElement>) => {
    if (disabled) return;
    tryCapture(e.currentTarget, e.pointerId);
    draggingRef.current = { pointerId: e.pointerId, handle };
  };

  const moveDrag = (handle: 'from' | 'to') => (e: ReactPointerEvent<SVGCircleElement>) => {
    const active = draggingRef.current;
    if (!active || active.pointerId !== e.pointerId || active.handle !== handle) return;
    const angle = angleForEvent(e);
    if (angle === null) return;
    const time = angleToTime(angle, 5);
    if (handle === 'from') onFromChange(time);
    else onToChange(time);
  };

  const endDrag = (handle: 'from' | 'to') => (e: ReactPointerEvent<SVGCircleElement>) => {
    const active = draggingRef.current;
    if (active && active.pointerId === e.pointerId && active.handle === handle) {
      draggingRef.current = null;
    }
    tryRelease(e.currentTarget, e.pointerId);
  };

  return (
    <svg
      ref={svgRef}
      className={`nightdial ${disabled ? 'is-disabled' : ''}`}
      width={size}
      height={size}
      viewBox={`0 0 ${size} ${size}`}
      aria-hidden="true"
    >
      <defs>
        {/* Dämmerungs-Gradient: Indigo/Violett (Abend) → tiefes Nachtblau (Morgen-Rand). */}
        <linearGradient id={gradientId} x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="#7c6bd6" />
          <stop offset="55%" stopColor="#403f8f" />
          <stop offset="100%" stopColor="#10132e" />
        </linearGradient>
      </defs>

      {/* Basisring — die volle 24h-Spur. */}
      <circle
        cx={center}
        cy={center}
        r={radius}
        className="nightdial__track"
        strokeWidth={stroke}
        fill="none"
      />

      {/* Stunden-Ticks — dezent, Haupt-Ticks (0/6/12/18) länger + beschriftet. */}
      {HOURS.map((h) => {
        const angle = h * 15;
        const major = MAJOR_HOURS.includes(h);
        const outer = pointAt(angle, tickOuter, center, center);
        const inner = pointAt(angle, tickOuter - (major ? 10 : 5), center, center);
        return (
          <line
            key={h}
            x1={inner.x}
            y1={inner.y}
            x2={outer.x}
            y2={outer.y}
            className={`nightdial__tick ${major ? 'nightdial__tick--major' : ''}`}
          />
        );
      })}
      {MAJOR_HOURS.map((h) => {
        const pos = pointAt(h * 15, tickOuter + 11, center, center);
        return (
          <text key={`label-${h}`} x={pos.x} y={pos.y} className="nightdial__label" textAnchor="middle">
            {h}
          </text>
        );
      })}

      {/* Nacht-Bogen: Abend → Morgen, im Uhrzeigersinn, Mitternachts-Rollover erlaubt. */}
      {arcPath && (
        <path
          d={arcPath}
          className="nightdial__arc"
          stroke={`url(#${gradientId})`}
          strokeWidth={stroke}
          strokeLinecap="round"
          fill="none"
        />
      )}
      {isFullCircle && (
        <circle
          cx={center}
          cy={center}
          r={radius}
          className="nightdial__arc"
          stroke={`url(#${gradientId})`}
          strokeWidth={stroke}
          fill="none"
        />
      )}

      {/* Aktuelle Uhrzeit — kleiner, rein informativer Marker (kein Griff). */}
      <circle cx={nowPoint.x} cy={nowPoint.y} r={3.5} className="nightdial__now" />

      {/* Griffe: „Abend" (from) und „Morgen" (to) — Pointer- (deckt Touch mit ab) ziehbar. */}
      <circle
        cx={fromPoint.x}
        cy={fromPoint.y}
        r={11}
        className="nightdial__handle nightdial__handle--from"
        onPointerDown={beginDrag('from')}
        onPointerMove={moveDrag('from')}
        onPointerUp={endDrag('from')}
        onPointerCancel={endDrag('from')}
      />
      <circle
        cx={toPoint.x}
        cy={toPoint.y}
        r={11}
        className="nightdial__handle nightdial__handle--to"
        onPointerDown={beginDrag('to')}
        onPointerMove={moveDrag('to')}
        onPointerUp={endDrag('to')}
        onPointerCancel={endDrag('to')}
      />
    </svg>
  );
}

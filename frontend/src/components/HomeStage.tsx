import {
  cloneElement,
  isValidElement,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactElement,
  type ReactNode,
} from 'react';
import { useUiStrings } from '../i18n';
import { holdSceneMotion } from '../styles/sceneMotion';
import { subscribeHomeEdit, takeHomeEditRequest, useHomeLayout } from '../hooks/useHomeLayout';
import { useHomeTiles } from '../hooks/useSettings';
import {
  HOME_STAGE_GAP_PX,
  effectiveSize,
  homeDropCell,
  homeLayoutIndex,
  homePlacementsFor,
  homePlanPlacements,
  homeStageColumns,
  homeStageRows,
  isStageWidgetId,
  moveHomePlacement,
  planHomeStage,
  sizeToSpan,
  stepHomeTileSize,
  type HomeCell,
  type HomeLayoutTile,
  type HomePlacementMap,
  type HomeReservedCell,
  type HomeStageMetrics,
} from './homeLayout';
import { homeWidget, type HomeTileSize, type HomeWidgetId } from './homeWidgets';

/**
 * **HomeStage** — the widget stage of the home tab: it measures the space the
 * one-window frame leaves over, asks {@link planHomeStage} how many tiles fit,
 * and renders the rest as swipeable pages with dots.
 *
 * It is the ONLY new piece of resize logic on this tab (order: "keine neue
 * JS-Resize-Logik außer der Seiten-Berechnung, EIN ResizeObserver"). Everything
 * else about the responsive behaviour is CSS: the column count is derived from
 * the same measurement, written as one inline `grid-template-columns`, and the
 * pre-measurement paint falls back to `repeat(auto-fit, minmax(252px,1fr))` in
 * `index.css`.
 *
 * **Why it measures instead of asking CSS:** pagination needs to KNOW the
 * column and row count — an `auto-fit` grid knows it only after layout, and
 * nothing in the DOM reports it back. So the number lives in `homeLayout.ts`
 * (one testable rule), and CSS is told the result rather than guessing it a
 * second time.
 *
 * **The dot row is reserved even when there is only one page.** Not cosmetics:
 * the track height decides how many rows fit, which decides the page count,
 * which would decide whether dots exist — a feedback loop that can oscillate
 * (dots appear ⇒ track shorter ⇒ fewer rows ⇒ more pages ⇒ dots stay ⇒ …). A
 * constant 20 px row breaks the loop by construction, at the price of 20 px
 * that a single-page stage does not use.
 *
 * **Motion law:** the pager animates `transform` only (never width/left), the
 * dots only `opacity`/`transform`.
 */

/** A tile as the stage renders it: the layout span plus a builder for the finished element. */
export interface HomeStageTile extends HomeLayoutTile {
  /**
   * Builds this tile's content AT a given size — a FUNCTION, not a
   * ready-made node (Kurskorrektur 18.08., "Inhalt folgt der ECHTEN
   * Fläche"): `HomeStage` calls it exactly once per render, with the tile's
   * EFFECTIVE size (`effectiveSize(size, columns, rowsPerPage)`), so the
   * rendered content always matches the cell the tile really gets — never
   * the stored/default size blindly. Before the first real measurement
   * (SSR, `renderToStaticMarkup`, the first paint frame) it is called with
   * the STORED size unclamped, matching the CSS `auto-fit` fallback's own
   * "we do not know the box yet" honesty for the grid geometry.
   */
  node: (effectiveSize: HomeTileSize) => ReactNode;
  /**
   * Stored size (W1, DESIGN-widget-raster-2026-08-18 §5.1) — the ceiling
   * {@link effectiveSize} degrades from, given the CURRENT column/row count.
   * `sizeToSpan(effectiveSize(size, columns, rowsPerPage), columns)`
   * overrides `cols`/`rows` from {@link HomeLayoutTile} once the stage is
   * measured (a tile whose stored size is XL stays XL when the window
   * widens back out — nothing is overwritten, see `homeLayout.ts`). Omitted
   * ⇒ the tile keeps whatever `cols`/`rows` it already carries (default
   * 1×1), unchanged from before S3, and `node` is called with `'M'`.
   */
  size?: HomeTileSize;
}

/**
 * Re-parents `node`'s render onto the EXACT cell {@link HomeLayoutCell} (in
 * `homeLayout.ts`) computed for it — explicit `grid-column`/`grid-row`
 * `span`, not CSS auto-placement (Codex-Gegenprüfung 18.08. §1: the model
 * and the browser's own auto-placement cursor can disagree the moment tiles
 * of different spans are mixed, silently). `cloneElement` instead of an
 * extra wrapper `<div>`: the tile's own root element (an `<article
 * className="tile idle__tile …">`, always — none of them sets its own
 * `style` prop today) becomes the grid item directly, so nothing about its
 * CSS sizing (height/width, flex children) has to change. `isValidElement`
 * is a defensive guard for the type only — `node()` always returns a real
 * element in this codebase; anything else renders unpositioned rather than
 * crashing the whole stage over one tile.
 */
function placeTile(
  node: ReactNode,
  id: string,
  sizing: boolean,
  cell: { row: number; col: number; cols: number; rows: number } | null,
  edit: TileEditProps | null,
): ReactNode {
  if (!isValidElement(node)) return node;
  const el = node as ReactElement<{ style?: CSSProperties }>;
  const style: CSSProperties = { ...el.props.style };
  if (cell) {
    style.gridColumn = `${cell.col + 1} / span ${cell.cols}`;
    style.gridRow = `${cell.row + 1} / span ${cell.rows}`;
  }
  if (edit) {
    // Das Wackeln (§4.2) läuft VERSETZT — Gleichschritt sieht nach Fehler aus,
    // Versatz nach Leben. Der Versatz kommt aus dem Platz der Kachel, ist also
    // stabil und springt beim Neu-Rendern nicht.
    style.animationDelay = `${edit.wiggleDelayMs}ms`;
    if (edit.dragOffset) {
      // Der Zug ist REINES transform (Codex §5 „Drag-Translation bleibt reines
      // transform"): kein left/top, kein width — der Browser darf die Kachel
      // auf ihrer eigenen Ebene schieben, ohne das Raster neu zu rechnen.
      style.transform = `translate3d(${Math.round(edit.dragOffset.dx)}px, ${Math.round(edit.dragOffset.dy)}px, 0)`;
    }
  }
  return cloneElement(el, {
    // Die Kachel trägt ihre Id im DOM, weil der Pointer-Schiedsrichter unten
    // vom Ereignis-Ziel aus nach oben suchen muss ("auf WELCHER Kachel liegt
    // dieser Finger?") — auch im unvermessenen Zustand, wo es keine Zelle gibt.
    'data-widget-id': id,
    // Das Zucken bei 600 ms (§4.1): die Kachel, deren Wähler offen ist, hebt
    // sich 2 px. Nur `transform`, und unter `prefers-reduced-motion` gar nicht
    // (index.css) — das Motion-Gesetz des Hauses gilt auch für ein Zwinkern.
    ...(sizing ? { 'data-sizing': 'true' } : {}),
    ...(edit
      ? {
          'data-edit': 'true',
          'data-dragging': edit.dragOffset ? 'true' : 'false',
          // Die Kachel wird im Edit zum Bedienelement: fokussierbar, benannt,
          // mit Rollenbeschreibung. Ohne das gäbe es für Tastatur/VoiceOver
          // kein Verschieben — Codex §5, A11y-Pflicht 1.
          tabIndex: 0,
          role: 'button',
          'aria-roledescription': edit.roleDescription,
          'aria-label': edit.label,
          onKeyDown: edit.onKeyDown,
        }
      : {}),
    style,
  } as Partial<{ style?: CSSProperties }>);
}

/** Was eine Kachel im Edit-Modus zusätzlich trägt (s. {@link placeTile}). */
interface TileEditProps {
  wiggleDelayMs: number;
  dragOffset: { dx: number; dy: number } | null;
  label: string;
  roleDescription: string;
  onKeyDown: (e: ReactKeyboardEvent<HTMLElement>) => void;
}

/**
 * Nachfahren, die den Finger für sich beanspruchen: ein Long-Press auf einem
 * Nachrichten-Link oder einem `<summary>` gehört DIESEM Element, nicht dem
 * Stufen-Wähler (Codex-Gegenprüfung §3, Konsument 3 von vieren).
 */
const INTERACTIVE_DESCENDANTS =
  'a[href], button, input, select, textarea, summary, details, [role="button"], [role="link"], [contenteditable="true"]';

/**
 * Auf welchem größenverstellbaren Widget liegt dieses Ereignis-Ziel — und darf
 * es überhaupt einen Long-Press starten? `null` heißt: Finger weg, hier gibt
 * es nichts zu wählen (interaktiver Nachfahre, Krone, unbekannte Id, oder ein
 * Widget mit nur EINER erlaubten Stufe — ein Wähler ohne Wahl ist Lärm).
 */
function sizableWidgetAt(
  target: EventTarget | null,
  /**
   * **Der Nachfahren-Riegel gilt nur, wo es einen zweiten Weg gibt** (Querbefund
   * des Resize-Pods, 22.08.).
   *
   * Mit der Maus ist der Riegel richtig: ein langer Druck auf einer Schlagzeile
   * ist dort nichts Besonderes, und wer die Nachrichten-Kachel verstellen will,
   * hat den Rechtsklick ({@link HomeStage} `onContextMenu`).
   *
   * Auf Glas gibt es keinen Rechtsklick — und die Nachrichten-Kachel besteht
   * fast nur aus Links. Der Riegel bedeutete dort: **kein einziger Punkt dieser
   * Kachel führt in den Edit-Modus.** Gemessen mit
   * `tools/zuhause-probe/touch.mjs` (Schritt 6): 900 ms Druck auf einer
   * Schlagzeile ⇒ `editNachher: false`, und stattdessen ein Link-Klick.
   *
   * Der kurze Tipp bleibt trotzdem der Link: der Timer feuert erst nach
   * {@link HOME_LONG_PRESS_MS}, und erst dann schluckt `onClickCapture` den
   * Klick, den der lange Druck hinter sich herzieht.
   */
  ignoreInteractive = false,
): HomeWidgetId | null {
  if (!(target instanceof Element)) return null;
  if (!ignoreInteractive && target.closest(INTERACTIVE_DESCENDANTS)) return null;
  const id = target.closest('[data-widget-id]')?.getAttribute('data-widget-id') ?? null;
  if (id === null || !isStageWidgetId(id)) return null;
  return homeWidget(id).sizes.length > 1 ? id : null;
}

/**
 * Dasselbe im **Edit-Modus** — und da gilt der Nachfahren-Riegel NICHT mehr.
 * Genau daran scheiterte Andi am 19.08.: die Nachrichten-Kachel ist fast nur
 * Links, also fand `sizableWidgetAt` dort nie ein Widget. Im Edit sind alle
 * Kinder `inert` (s. Effekt in {@link HomeStage}), es gibt also keinen Link
 * mehr, dem der Finger gehören könnte — die ganze Kachel ist ein Griff.
 */
function widgetAt(target: EventTarget | null): HomeWidgetId | null {
  if (!(target instanceof Element)) return null;
  const id = target.closest('[data-widget-id]')?.getAttribute('data-widget-id') ?? null;
  return id !== null && isStageWidgetId(id) ? id : null;
}

/**
 * Gehört dieser Zeigerpunkt zur **Bedienung** des Edit-Modus statt zur Bühne?
 *
 * Gebraucht für Andis zweiten Ausstiegsweg (W6: „oder durch einen freien Klick
 * irgendwohin"): „frei" heißt *keine Kachel und kein Bedienelement*. Ohne
 * diesen Riegel schlösse der erste Druck auf einen Stufen-Knopf zugleich den
 * Modus, in dem der Knopf steht.
 *
 * `.idle__editbar` stand hier bis zum 23.08. — die Leiste ist gefallen (Andi:
 * „nimm die UI oben, wenn man etwas bearbeitet raus"). Ein Selektor auf ein
 * Element, das es nicht mehr gibt, ist kein harmloser Rest: er sagt der
 * nächsten Hand, hier sei noch eine Leiste zu bedienen.
 */
function onEditChrome(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest('.idle__sizer, .idle__dots') !== null;
}

/** Zwei Zell-Verteilungen sind gleich — der Vergleich, der ein `setState` pro Bild spart. */
function samePlacements(a: HomePlacementMap, b: HomePlacementMap): boolean {
  const keys = Object.keys(a);
  if (keys.length !== Object.keys(b).length) return false;
  return keys.every((id) => b[id] && a[id].col === b[id].col && a[id].row === b[id].row);
}

/**
 * Horizontal travel in px before a pointer gesture counts as a page swipe. Below
 * it, the gesture belongs to whatever is under the finger — a tap on a headline,
 * or a vertical scroll inside a tile that scrolls in its own frame. Deliberately
 * larger than a tap wobble and smaller than a deliberate flick.
 */
export const HOME_SWIPE_ENGAGE_PX = 14;

/** Share of the stage width a swipe must cover to actually turn the page. */
const HOME_SWIPE_COMMIT_RATIO = 0.18;
/** …but never more than this, so a swipe on a very wide window stays cheap. */
const HOME_SWIPE_COMMIT_MAX_PX = 96;
/** Resistance when dragging past the first/last page (rubber band, no bounce). */
const HOME_SWIPE_OVERDRAG_DIVISOR = 3;

/**
 * Wie lange „fest drücken" dauert, bis der Stufen-Wähler aufgeht (DESIGN §4.1,
 * Andi wörtlich: „fest drücken (oder ein paar sekunden)").
 */
export const HOME_LONG_PRESS_MS = 600;

/**
 * **Der dritte Ausgang: Vergessen.** Nach dieser Ruhezeit ohne jede Eingabe
 * verlässt die Bühne den Edit-Modus von selbst.
 *
 * Andis zwei bestellte Ausgänge (nochmal auf dieselbe Kachel tippen, oder
 * irgendwohin ins Leere) setzen beide voraus, dass jemand noch da ist. Dieses
 * Gerät ist aber ein Wandbildschirm im Flur: wer den Edit-Modus aufmacht und
 * dann weggerufen wird, lässt einen Bildschirm zurück, auf dem alle Kacheln
 * wackeln — nicht für Minuten, sondern bis zufällig wieder jemand vorbeikommt.
 * Ein Zustand, den nur Anwesenheit beenden kann, ist auf einem Wandgerät kein
 * Zustand, sondern ein Defekt. Echo Show und die Google-Displays machen es
 * genauso: Bearbeiten ist ein Ausflug, kein Aufenthalt.
 *
 * 75 Sekunden ist die Mitte des bestellten Fensters (60–90). Kürzer würde die
 * Bestellung selbst treffen — „Reihenfolge festlegen" heißt vergleichen,
 * zurücktreten, nochmal hinsehen, und dabei liegt die Hand auch mal eine halbe
 * Minute still. Länger wäre die Zusage kaum noch eine.
 *
 * WICHTIG ist, was NICHT mitzählt: nur echte Eingaben halten den Modus wach,
 * und eine laufende Geste beendet ihn nie (s. der Effekt unten). Ein Finger,
 * der eine Kachel gerade über die Bühne zieht, ist Anwesenheit — auch wenn er
 * dabei 80 Sekunden braucht.
 */
export const HOME_EDIT_IDLE_MS = 75_000;

/**
 * Wie oft eine ununterbrochene Bewegung die Ruhe-Uhr höchstens neu stellt.
 * `pointermove` feuert im Dutzend pro Sekunde; jedes Mal einen Timer zu
 * verwerfen und neu zu setzen wäre auf einem Gerät, das rund um die Uhr läuft,
 * verschwendete Arbeit. Einmal pro Sekunde reicht für eine 75-Sekunden-Frist.
 */
const HOME_EDIT_IDLE_THROTTLE_MS = 1000;

/**
 * Wackel-Toleranz eines ruhenden Fingers. **Bewusst kleiner als
 * {@link HOME_SWIPE_ENGAGE_PX}**, weil die beiden Zahlen zwei verschiedene
 * Fragen beantworten: 14 px fragt „ist diese Geste absichtlich waagerecht?",
 * 8 px fragt „liegt der Finger überhaupt noch still?". Wäre die Toleranz
 * gleich groß, überlebte der Long-Press-Timer ein 13 px langes SENKRECHTES
 * Scrollen und ginge dem Nutzer mitten in der Bewegung auf (Codex-Gegenprüfung
 * §3: „der Timer muss bei Bewegung in JEDER Richtung sterben"). Erfahrungswert
 * in der Größenordnung üblicher Tap-Slops — nicht am Gerät gemessen (Rate).
 */
export const HOME_TAP_SLOP_PX = 8;

/**
 * Wie lange eine gezogene Kachel am Bühnenrand gehalten werden muss, bis die
 * Bühne weiterblättert (DESIGN §2.5/§4.2 — Andi 19.08.: „Ich brauche eine
 * Möglichkeit, wie ich sie über die verschiedenen Seiten verschieben kann",
 * damit ist Auto-Paging **Pflicht**, nicht Kür). Lang genug, dass ein Zug quer
 * über die Bühne nicht versehentlich blättert; kurz genug, dass man nicht
 * wartet, ob überhaupt etwas passiert.
 */
export const HOME_AUTOPAGE_DWELL_MS = 500;

/** Wie breit die Zone an der linken/rechten Bühnenkante ist, die blättert. */
export const HOME_AUTOPAGE_EDGE_PX = 56;

/* `HOME_EDIT_BAND_GAP_PX` stand hier bis zum 23.08. — der Abstand zwischen der
   Edit-Leiste und der obersten Kachelreihe. Mit der Leiste ist auch das Band
   gefallen (Andi: „nimm die UI oben … raus"), und eine Konstante ohne Band ist
   ein toter Draht, kein Vorrat. */

/** `useLayoutEffect` in the browser, a no-op-safe `useEffect` under SSR/`renderToStaticMarkup`. */
const useIsomorphicLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect;

function tryCapture(el: Element, pointerId: number): void {
  try {
    el.setPointerCapture?.(pointerId);
  } catch {
    /* no capture — the swipe still works, capture is only comfort. */
  }
}
function tryRelease(el: Element, pointerId: number): void {
  try {
    el.releasePointerCapture?.(pointerId);
  } catch {
    /* ignore */
  }
}

/**
 * Wem gehört dieser eine Pointer-Down? (Codex-Gegenprüfung §3, angenommen als
 * Vertrag im Bus-Entscheid 20260818 §3.)
 *
 * - `pending` — noch offen. Long-Press-Timer läuft, ein Swipe kann noch gewinnen.
 * - `swipe` — die Geste hat sich als waagerecht bewiesen; der Timer ist tot.
 * - `edit` — die 600 ms sind voll; der Edit-Modus steht, der Swipe ist tot.
 * - `drag` — im Edit-Modus: der Finger trägt eine Kachel (W4).
 * - `cancelled` — niemand bekommt sie (zweiter Finger, `pointercancel`,
 *   verlorene Capture, Scroll-/Systemübernahme).
 *
 * **Genau EIN Besitzer, genau EIN Timer.** Es gibt bewusst keinen zweiten
 * Timer neben dem Swipe-Code — auch der Auto-Pager (W4, „>500 ms am
 * Bühnenrand") hängt an DIESEM `timer`-Feld, nicht daneben: wenn der Zug
 * beginnt, ist der Long-Press längst gefeuert oder gestorben, das Feld also
 * frei. Zwei Timer wären zwei Wahrheiten darüber, was der Finger gerade tut.
 */
type GesturePhase = 'pending' | 'swipe' | 'edit' | 'drag' | 'cancelled';

interface GestureState {
  pointerId: number;
  startX: number;
  startY: number;
  phase: GesturePhase;
  /**
   * Der EINE Timer dieser Geste: bis zur Phase `edit` der Long-Press, danach
   * (in `drag`) die Verweildauer am Bühnenrand. Nie beide gleichzeitig —
   * `null` heißt „keiner läuft".
   */
  timer: number | null;
  /** Das Widget unter dem Finger, falls es überhaupt Stufen zu wählen hat. */
  widgetId: HomeWidgetId | null;
  /** Zu welcher Seite blättert der laufende Rand-Timer? (`0` = keiner.) */
  edgeDir: -1 | 0 | 1;
  /**
   * **Welche Kachel war beim Aufsetzen des Fingers ausgewählt?** (W6.)
   *
   * Klingt nach einer Kopie von `openFor` — ist aber die einzig verlässliche
   * Quelle für die Frage „ist das der zweite Tipp auf DIESELBE Kachel?":
   * `onPointerDown` schließt den offenen Wähler bereits (damit ein Druck
   * daneben ihn wegnimmt), React rendert zwischen `pointerdown` und
   * `pointerup` neu, und `onPointerUp` läse aus seiner Closure dann immer
   * `null`. Der zweite Tipp könnte den Edit-Modus also nie erkennen. Hier
   * steht der Zustand VOR dem eigenen Nebeneffekt.
   */
  openAtDown: HomeWidgetId | null;
  /** Lag der Finger auf Leiste/Wähler/Griff/Punkten statt auf der Bühne? */
  fromChrome: boolean;
}

export function HomeStage({ tiles }: { tiles: readonly HomeStageTile[] }) {
  const { idleFace } = useUiStrings();
  // KEIN `reset` mehr: „Zurücksetzen" wohnt seit dem 23.08. in den
  // Einstellungen bei den Widget-Schaltern (`HomeTilesSection`) — dort, wo
  // alles Verwalterische wohnt. Die Bühne ordnet an; sie verwaltet nicht.
  const { layout, setSize, setPlacements } = useHomeLayout();
  /**
   * Nur noch LESEND: die Bühne schaltet seit dem 22.08. kein Widget mehr ein
   * oder aus (Andi: *„das aktivieren oder deaktivieren der verschiedenen
   * widgets soll über die einstellungen passieren."*). Gebraucht wird der
   * Zustand trotzdem — für die reservierten Lücken stiller Kacheln (§1.3):
   * ein eingeschaltetes Widget, das gerade nichts zu sagen hat, behält seine
   * Zelle; ein ausgeschaltetes reserviert nichts.
   */
  const { enabled } = useHomeTiles();
  const trackRef = useRef<HTMLDivElement>(null);
  /** Die Hülle — Bezugsrahmen der Edit-Schicht und Träger ihrer gemessenen Bänder. */
  const stageRef = useRef<HTMLDivElement>(null);
  const gestureRef = useRef<GestureState | null>(null);
  /** Veto gegen den Klick bzw. das Kontextmenü, die ein Long-Press hinter sich herzieht. */
  const suppressClickRef = useRef(false);
  const suppressContextMenuRef = useRef(false);
  /** Nach einem Größenwechsel: DIESE Richtung wieder fokussieren (A11y, s. Effekt unten). */
  const focusDirRef = useRef<'up' | 'down' | null>(null);
  /** Nach einem Zug per Tastatur: DIESE Kachel wieder fokussieren (Codex §5). */
  const focusWidgetRef = useRef<HomeWidgetId | null>(null);
  const [metrics, setMetrics] = useState<HomeStageMetrics | null>(null);
  const [page, setPage] = useState(0);
  const [dragX, setDragX] = useState(0);
  const [dragging, setDragging] = useState(false);
  /** Für WELCHE Kachel steht der Stufen-Wähler offen? (`null` = keine.) */
  const [openFor, setOpenFor] = useState<HomeWidgetId | null>(null);

  /* ── Edit-Modus (W4) ──────────────────────────────────────────────────── */

  /** Wackeln alle Kacheln? Der Zustand ÜBER dem Stufen-Wähler (§4.2). */
  const [editing, setEditing] = useState(false);
  /** Was zuletzt geschah — die `aria-live`-Ansage für einen Zug per Tastatur. */
  const [announcement, setAnnouncement] = useState('');
  /**
   * Der laufende Kachel-Zug. `order` ist die LIVE-Vorschau der sichtbaren
   * Reihenfolge — sie ersetzt das gespeicherte Layout, solange der Finger
   * liegt, und wird erst beim Loslassen geschrieben. So kostet ein Zug quer
   * über die Bühne EINEN Speicher-Schreibvorgang statt dreißig.
   */
  const [drag, setDrag] = useState<{
    id: HomeWidgetId;
    dx: number;
    dy: number;
    /**
     * W7-B: die Vorschau ist jetzt eine ZELL-Verteilung, keine Reihenfolge.
     * Es ist exakt das Ergebnis, das ein Loslassen an dieser Stelle schriebe —
     * berechnet von derselben reinen Funktion ({@link moveHomePlacement}), die
     * beim Abwurf läuft. Vorschau und Ergebnis können darum nicht auseinander.
     */
    placements: HomePlacementMap | null;
  } | null>(null);
  /**
   * Letzte Zeiger-Position + laufender `requestAnimationFrame` (Codex §5,
   * Pointer-Performance: „Live-Reorder per rAF gebündelt"). Ein
   * `pointermove` kann auf einem iPad 120×/s feuern; ohne diese Bündelung
   * plante und renderte die Bühne 120× pro Sekunde den ganzen Kachel-Baum.
   * So ist es höchstens EIN `setState` pro Bildschirm-Bild.
   */
  const rafRef = useRef<number | null>(null);
  const pointRef = useRef<{ x: number; y: number } | null>(null);
  /**
   * Die Zell-Verteilung, von der der LAUFENDE Zug ausgeht — festgehalten in dem
   * Moment, in dem aus der Berührung ein Zug wird. Ohne sie würde jedes Bild
   * auf der Vorschau des vorigen weiterrechnen und ein Zug quer über die Bühne
   * wäre nicht ein Umzug, sondern hundert.
   */
  const dragBaseRef = useRef<HomePlacementMap | null>(null);
  /** Welche Kachel-Kinder tragen gerade `inert`? (s. den Effekt weiter unten.) */
  const inertRef = useRef<Set<Element>>(new Set());

  // ── One measurement, one ResizeObserver ─────────────────────────────────
  // Layout effect (not `useEffect`): the first measurement lands BEFORE paint,
  // so the stage never shows an un-paginated frame and then jumps.
  useIsomorphicLayoutEffect(() => {
    const el = trackRef.current;
    if (!el) return;
    const read = () => {
      const box = el.getBoundingClientRect();
      const next: HomeStageMetrics | null =
        box.width > 0 && box.height > 0 ? { width: box.width, height: box.height } : null;
      setMetrics((prev) => {
        if (prev === null && next === null) return prev;
        if (prev && next && prev.width === next.width && prev.height === next.height) return prev;
        return next;
      });
    };
    read();
    if (typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(read);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  /* **Das Edit-Band ist am 23.08. mit der Leiste gefallen.**

     Hier stand bis dahin ein Beobachter, der die gemessene Höhe der Edit-
     Leiste als `--home-editband-top` an die Bühne schrieb; `.idle__page`
     bekam im Edit oben einen Rand in genau dieser Höhe, damit die Leiste
     keiner Kachel die Kopfzeile abschnitt (Andi 22.08.: „soll aber nicht die
     fläche zum bearbeiten überlagern"). Ohne Leiste gibt es kein Band, das
     ausweichen müsste — und die Bearbeitungsfläche ist im Edit-Modus jetzt
     buchstäblich die ganze Bühne: gemessen 880 × 669 px bei 1366 × 1024,
     Pixel für Pixel dasselbe Rechteck wie außerhalb des Edit-Modus. */


  // S3 sizes (§5.1, row clamp Kurskorrektur 18.08.): resolve each tile's
  // STORED `size` into an EFFECTIVE size for the CURRENT columns/rowsPerPage,
  // then (a) call `node(effectiveSize)` so the CONTENT matches the real cell
  // — not just its CSS span — and (b) turn that same effective size into
  // `cols`/`rows` for `planHomeStage` (untouched below, it only ever sees
  // plain cell spans). Unmeasured ⇒ no columns/rows to resolve against yet;
  // `node` gets the STORED size unclamped, same honesty as the CSS
  // `auto-fit` fallback for the geometry (file KDoc on `HomeStageTile.node`).
  // W3 (§5): das GESPEICHERTE Layout ist die Wahrheit über Reihenfolge und
  // Stufe — der Aufrufer (`IdleFace`) liefert nur, WELCHE Kacheln es gerade
  // gibt (Schalter + Verdien-Regel) und ihre Registry-Default-Stufe.
  //  - Die Stufe wird nur bei Kacheln überschrieben, die überhaupt eine
  //    ANBIETEN (`t.size`): eine Kachel ohne Stufen-Vertrag bleibt 1×1, wie
  //    vor S3 — sonst zöge der Speicher Aufrufer in ein Raster, das sie nie
  //    bestellt haben.
  //  - Sortiert wird STABIL nach dem Platz im Layout; eine Id, die das Layout
  //    nicht kennt, behält ihre Position am Ende statt zu verschwinden.
  //    Das Default-Layout ist genau die heutige `IdleFace`-Reihenfolge, hier
  //    ändert sich also nichts, bis Andi wirklich umsortiert (W4).
  /**
   * Der Anzeige-Name eines Widgets — aus den BESTEHENDEN Kachel-Strings, nicht
   * aus einem zweiten Namens-Katalog: derselbe Name zweimal übersetzt driftet
   * beim ersten Wording-Wechsel auseinander (Andi-Korrektur „Lagebild" ⇒
   * „Nachrichten" hat genau das schon einmal gekostet).
   */
  const widgetName = (id: HomeWidgetId): string => {
    switch (id) {
      case 'uhr':
        return idleFace.uhr.name;
      case 'wecker':
        return idleFace.wecker.name;
      case 'wetter':
        return idleFace.wetter.name;
      case 'laeuft':
        return idleFace.laeuft.name;
      case 'einkauf':
        return idleFace.einkauf.name;
      case 'vacuum':
        return idleFace.homeTiles.vacuum.name;
      case 'climate':
        return idleFace.homeTiles.climate.name;
      case 'news':
        return idleFace.currentAffairs.name;
      default:
        return id;
    }
  };

  const layoutIndex = useMemo(() => homeLayoutIndex(layout), [layout]);
  const orderedTiles = useMemo(() => {
    const decorated = tiles.map((tile, position) => {
      const entry = isStageWidgetId(tile.id) ? layoutIndex.get(tile.id) : undefined;
      return {
        tile: entry && tile.size ? { ...tile, size: entry.size } : tile,
        position,
        order: entry?.index ?? Number.MAX_SAFE_INTEGER,
      };
    });
    decorated.sort((a, b) => a.order - b.order || a.position - b.position);
    return decorated.map((d) => d.tile);
  }, [tiles, layoutIndex]);

  // Die EINE Messung, in Zahlen: Spalten und Zeilen je Seite. Sie stehen hier
  // oben, weil ab W7 alles darunter sie braucht — die Stufen-Degradierung, die
  // Zellen der aktuellen Spaltenzahl und die Zug-Rechnung.
  const columns = metrics ? homeStageColumns(metrics.width) : 0;
  const rowsPerPage = metrics ? homeStageRows(metrics.height) : 0;

  /** Der Fußabdruck, den eine gespeicherte Stufe auf DIESER Bühne hat. */
  const spanOfWidget = useCallback(
    (id: HomeWidgetId) => {
      if (columns <= 0) return { cols: 1, rows: 1 };
      const stored = layoutIndex.get(id)?.size ?? homeWidget(id).defaultSize ?? 'M';
      return sizeToSpan(effectiveSize(stored, columns, rowsPerPage), columns);
    },
    [columns, layoutIndex, rowsPerPage],
  );

  const sizedTiles = useMemo(() => {
    if (metrics === null) return orderedTiles.map((t) => ({ ...t, node: t.node(t.size ?? 'M') }));
    return orderedTiles.map((t) => {
      if (!t.size) return { ...t, node: t.node('M') };
      const es = effectiveSize(t.size, columns, rowsPerPage);
      const span = sizeToSpan(es, columns);
      return { ...t, cols: span.cols, rows: span.rows, node: t.node(es) };
    });
  }, [orderedTiles, metrics, columns, rowsPerPage]);

  /* ── Das freie Raster (W7-B) ──────────────────────────────────────────────
   *
   * Andi 21.08.: *„Ich möchte, dass ich die Widgets anordnen kann. Das soll
   * nicht automatisch bündig werden, sondern nur, wenn ich es verschiebe."*
   *
   * Drei Zutaten, und die dritte ist die, die man leicht vergisst:
   *  - die gespeicherten Zellen DIESER Spaltenzahl (quer ≠ hoch, §C);
   *  - während eines Zuges stattdessen die Live-Vorschau — dasselbe Ergebnis,
   *    nur noch nicht geschrieben;
   *  - die **reservierten Lücken**: Kacheln, deren Schalter AN ist, die aber
   *    gerade nichts zu sagen haben (Verdien-Regel §1.3). Ihre Zelle bleibt
   *    belegt. Ohne das stopfte die nächste heimatlose Kachel das Loch, und
   *    „Läuft" käme nach dem nächsten Countdown woanders wieder — genau das
   *    Nachrücken, das abbestellt ist. Ein AUSGESCHALTETES Widget reserviert
   *    dagegen nichts: das war eine Entscheidung, kein Schweigen.
   */
  const storedPlacements = columns > 0 ? homePlacementsFor(layout, columns) : null;
  const activePlacements = drag?.placements ?? storedPlacements;
  const reserved = useMemo<HomeReservedCell[]>(() => {
    if (!activePlacements) return [];
    const shown = new Set(sizedTiles.map((t) => t.id));
    const out: HomeReservedCell[] = [];
    for (const [id, cell] of Object.entries(activePlacements)) {
      if (shown.has(id) || !isStageWidgetId(id) || !enabled[id]) continue;
      out.push({ ...cell, ...spanOfWidget(id) });
    }
    return out;
  }, [activePlacements, sizedTiles, enabled, spanOfWidget]);

  const plan = useMemo(
    () => planHomeStage(sizedTiles, metrics, { placements: activePlacements, reserved }),
    [sizedTiles, metrics, activePlacements, reserved],
  );
  const pageCount = plan.pages.length;

  // A window that got taller (or a tile that disappeared) can drop the page the
  // user is standing on — step back instead of showing an empty stage.
  useEffect(() => {
    setPage((current) => (current > 0 && current >= pageCount ? Math.max(0, pageCount - 1) : current));
  }, [pageCount]);

  // Auf welcher Seite liegt die Kachel, deren Wähler offen ist? (`-1` = keine.)
  const sizerPage = useMemo(
    () =>
      openFor === null ? -1 : plan.pages.findIndex((p) => p.tiles.some((t) => t.id === openFor)),
    [plan, openFor],
  );
  // Der Wähler zieht die Bühne zu sich: ein Größenwechsel kann seine Kachel auf
  // eine andere Seite schieben (die Bühne rechnet neu) — bliebe die Bühne
  // stehen, wäre der eben gedrückte Knopf samt Fokus weg.
  useEffect(() => {
    if (sizerPage >= 0 && sizerPage !== page) setPage(sizerPage);
  }, [sizerPage, page]);
  // Verschwindet die Kachel unter dem offenen Wähler (Schalter aus, Quelle
  // still), verschwindet der Wähler mit ihr statt über einer Lücke zu stehen.
  useEffect(() => {
    if (openFor !== null && sizerPage < 0) setOpenFor(null);
  }, [openFor, sizerPage]);
  // A11y-Pflicht (§4.2): nach einem Größenwechsel bleibt der Fokus auf dem
  // gedrückten Knopf — auch wenn der Wähler dabei die Seite gewechselt hat und
  // React ihn neu gemountet hat. Läuft nach jedem Render, tut aber fast immer
  // nichts (`focusDirRef` ist nur direkt nach einem Klick gesetzt).
  //
  // W6-Zusatz, den die Stufen-Knöpfe nicht brauchten: der gedrückte Knopf kann
  // durch seinen EIGENEN Druck ausgrauen (`+` auf der letzten Stufe). Ein
  // `disabled`-Element ist nicht fokussierbar — der Fokus fiele ans
  // Dokument-Ende, und die Tastatur-Bedienung wäre nach genau einem Schritt
  // vorbei. Deshalb der Rückfall auf den GEGENKNOPF: er ist an dieser Kante
  // garantiert bedienbar (es gibt keine Stufe, die zugleich die kleinste und
  // die größte ist — ein Widget mit nur EINER Stufe bekommt gar keinen Wähler,
  // s. `sizableWidgetAt`).
  useIsomorphicLayoutEffect(() => {
    const want = focusDirRef.current;
    if (want === null) return;
    focusDirRef.current = null;
    const root = trackRef.current;
    const other = want === 'up' ? 'down' : 'up';
    const pick = (dir: string) =>
      root?.querySelector<HTMLButtonElement>(`.idle__sizerbtn[data-dir="${dir}"]`);
    const target = pick(want);
    (target && !target.disabled ? target : pick(other))?.focus();
  });

  /* ── Edit-Modus: Ableitungen und die zwei Hände (W4) ──────────────────── */

  const editText = idleFace.stage.edit;

  /**
   * Die sichtbaren Bühnen-Widgets in LESEREIHENFOLGE — seit W7 aus dem PLAN,
   * nicht mehr aus der gespeicherten Liste.
   *
   * Im freien Raster sind das zwei verschiedene Dinge: eine Kachel, die Andi
   * nach ganz rechts unten gezogen hat, steht in der Liste womöglich vorn. Was
   * ein Screenreader ansagt („Kachel 3 von 7") und wohin die Pfeiltasten
   * gehen, muss aber dem folgen, was man SIEHT. Der Plan ist die einzige
   * Quelle, die das weiß — er hat gerade jede Kachel auf ihre Zelle gesetzt.
   */
  const visibleIds = useMemo(
    () =>
      plan.pages
        .flatMap((p) => (plan.measured ? p.cells.map((c) => c.tile.id) : p.tiles.map((t) => t.id)))
        .filter((id): id is HomeWidgetId => isStageWidgetId(id)),
    [plan],
  );

  /** Wo liegt dieses Widget gerade? (Seitenübergreifende Zeile, `null` = nirgends.) */
  const cellOf = useCallback(
    (id: string): HomeCell | null => {
      if (!plan.measured) return null;
      for (let index = 0; index < plan.pages.length; index += 1) {
        const cell = plan.pages[index].cells.find((c) => c.tile.id === id);
        if (cell) return { col: cell.col, row: cell.row + index * plan.rowsPerPage };
      }
      return activePlacements?.[id] ?? null;
    },
    [plan, activePlacements],
  );

  /**
   * **Die Saat** (W7-B): eine Spaltenzahl, für die noch nie jemand etwas
   * angeordnet hat, bekommt EINMAL das Bild, das der heutige Packer malt — und
   * ist danach eingefroren. Dasselbe gilt für eine einzelne Kachel, die neu
   * dazukommt: sie bekommt ihren gefundenen Platz aufgeschrieben, damit sie
   * ihn behält.
   *
   * **Gesät wird über die SICHTBAREN Kacheln** — und das ist eine bewusste
   * Entscheidung gegen die elegantere Variante. Nahe lag, den Packer über
   * Geister-Kacheln aller EINGESCHALTETEN Widgets laufen zu lassen: das wäre
   * unabhängig von der Netz-Laufzeit und ergäbe exakt das Bild von vor W7.
   * Ausprobiert, gemessen, verworfen — es reserviert Platz für Widgets, die
   * womöglich nie etwas zu sagen haben (Sauger ohne HA, Nachrichten ohne
   * Artikel), und dann steht die einzige Kachel mit Inhalt auf Seite 4 hinter
   * drei leeren. Auf einem Flur-Display ist das schlimmer als eine Anordnung,
   * die beim allerersten Laden der Eintreffreihenfolge folgt.
   *
   * Der Preis ist damit benannt: **die allererste Anordnung auf einem frischen
   * Gerät hängt daran, was beim ersten Messen schon da war** — danach nie
   * wieder. Alles Spätere reiht sich hinten an (s. `placeByCells`), und ab dem
   * zweiten Laden erscheint jede Kachel sofort in ihrer eingefrorenen Zelle,
   * ganz gleich wann ihre Daten eintreffen.
   *
   * Bewusst NUR für Zellen, die FEHLEN. Eine Kachel, deren gespeicherte Zelle
   * gerade belegt ist (weil sie gewachsen ist), rendert `placeByCells` am
   * nächsten freien Platz — aber ihre gespeicherte Zelle bleibt stehen. Wird
   * sie wieder kleiner, geht sie nach Hause. Ein Zurückschreiben würde den
   * Ausweichplatz zur neuen Heimat machen, und eine Größenänderung wäre
   * heimlich ein Umzug.
   *
   * Während eines Zuges wird nicht gesät (die Vorschau ist noch keine
   * Entscheidung), und die Reihenfolge bleibt unangetastet: eine Saat
   * reproduziert exakt das, was ohnehin schon zu sehen war.
   */
  useEffect(() => {
    if (!plan.measured || columns <= 0 || drag) return;
    const seed = homePlanPlacements(plan);
    const missing = Object.keys(seed).filter(
      (id) => isStageWidgetId(id) && !storedPlacements?.[id],
    );
    if (missing.length === 0) return;
    const next: Record<string, HomeCell> = { ...(storedPlacements ?? {}) };
    for (const id of missing) next[id] = seed[id];
    setPlacements(columns, next);
  }, [plan, columns, drag, storedPlacements, setPlacements]);

  /** Auf welcher Seite liegt dieses Widget? (`-1` = auf keiner.) */
  const pageOf = useCallback(
    (id: string) => plan.pages.findIndex((p) => p.tiles.some((t) => t.id === id)),
    [plan],
  );

  /**
   * Die `aria-live`-Bestätigung eines Zuges (Codex §5). Sie nennt Platz UND
   * Seite, weil beides sich ändern kann und nur eines davon sichtbar ist.
   */
  const announceMove = useCallback(
    (id: HomeWidgetId, order: readonly HomeWidgetId[], atPage: number) => {
      const index = order.indexOf(id);
      if (index < 0) return;
      setAnnouncement(
        editText.moved(
          widgetName(id),
          index + 1,
          order.length,
          Math.max(0, atPage) + 1,
          Math.max(1, atPage + 1, pageCount),
        ),
      );
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps -- widgetName hängt nur an `idleFace`
    [editText, idleFace, pageCount],
  );

  /**
   * **Die Zell-Verteilung, die ein Zug ergäbe** — dieselbe reine Rechnung für
   * die Vorschau (jedes Bild) und für den Abwurf (einmal). Zwei Rechenwege für
   * dasselbe Bild wären zwei Gelegenheiten, sich zu widersprechen.
   *
   * `base` wird HEREINGEREICHT statt hier gelesen: während eines Zuges ist die
   * aktuelle Verteilung schon die Vorschau, und von einer Vorschau aus weiter
   * zu rechnen würde jeden Frame einen weiteren Zug ausführen.
   */
  const placementsAfterMove = useCallback(
    (base: HomePlacementMap, id: HomeWidgetId, target: HomeCell): HomePlacementMap => {
      const spans: Record<string, { cols: number; rows: number }> = {};
      for (const key of [...Object.keys(base), id]) {
        if (isStageWidgetId(key)) spans[key] = spanOfWidget(key);
      }
      return moveHomePlacement(base, id, target, spans, { columns, rowsPerPage });
    },
    [columns, rowsPerPage, spanOfWidget],
  );

  /**
   * **Die Verteilung, von der ein NEUER Zug ausgeht** (nie eine laufende
   * Vorschau) — und zwar die, die WIRKLICH DASTEHT.
   *
   * Hier stand `storedPlacements ?? homePlanPlacements(plan)`, also der reine
   * Dateiinhalt. Das war die zweite Hälfte von Andis Livetest vom 23.08.:
   * *„ich konnte die widgets nicht verschieben, wenn ich sie nach links und
   * rechts geschoben habe."* (Senkrecht ging.)
   *
   * Der Grund ist eine LÜCKE ZWISCHEN SPEICHER UND BILD, und sie trifft genau
   * die waagerechte Achse. `normalizeHomeLayout` lässt jede Spalte `col <
   * columns` durch — es kennt den Fußabdruck der Kachel nicht. Eine 2 Spalten
   * breite Kachel auf `col: 2` ist damit gültig gespeichert und trotzdem
   * unzeichenbar (2 + 2 > 3). {@link placeByCells} klemmt sie deshalb, findet
   * den Platz belegt und gibt ihr den nächsten freien — **schreibt das aber
   * bewusst nicht zurück** (eine Größenänderung soll kein heimlicher Umzug
   * sein, s. der Saat-Effekt weiter oben). Genau so entsteht Andis Lage: er
   * ändert Stufen mit + und −, die Zelle bleibt stehen, der Fußabdruck wächst.
   *
   * Gemessen (`tools/zuhause-probe/touch.mjs`, Schritt 5c, 834×1112, Saat w7,
   * Chrome UND Firefox, Zahl für Zahl gleich):
   *
   *   `laeuft` (2 Spalten)  gespeichert {col 2, row 1} · gezeichnet {col 0, row 3}
   *   Finger zieht auf Spalte 1 ⇒ geschrieben {col 1, row 3} ⇒ gezeichnet {col 0, row 4}
   *   versatz dx = 0, dy = +127 — sie rutscht RUNTER statt RÜBER.
   *   `wecker` (1 Spalte)   gespeichert = gezeichnet ⇒ dx = −269, sauber.
   *
   * Der Zug rechnete seine Verdrängungen also in einer Welt aus, die es auf
   * dem Bildschirm nicht gibt; das Ergebnis war für `placeByCells` erneut
   * unzeichenbar, und die Kachel bekam ein zweites Mal „den nächsten freien
   * Platz". Die ZEILE überlebt das (Zeilen wachsen nach unten beliebig), die
   * SPALTE nicht — sie ist bei `columns − Fußabdruck` hart zu Ende. Daher
   * „senkrecht geht, waagerecht nicht".
   *
   * Ein Zug ist eine Aussage über das, was man SIEHT. Also ist das Bild die
   * Grundlage: die gezeichneten Zellen gewinnen, die gespeicherten bleiben für
   * alles stehen, was gerade gar nicht auf der Bühne liegt — die reservierten
   * Lücken der stillen Kacheln (§1.3) und die Zellen anderer Spaltenzahlen
   * gehen dabei nicht verloren.
   *
   * **Der Preis, ehrlich benannt:** wer zieht, friert damit auch die
   * Ausweichplätze der anderen ein. Eine Kachel, die nach dem Verkleinern
   * „nach Hause" gefunden hätte, findet nach dem nächsten Zug ihr neues
   * Zuhause dort, wo sie steht. Das ist der richtige Tausch: eine Heimat, die
   * niemand sehen kann, ist kein Zuhause, sondern eine Falle.
   */
  const baseForMove = useCallback((): HomePlacementMap => {
    const gezeichnet = homePlanPlacements(plan);
    if (!storedPlacements) return gezeichnet;
    return { ...storedPlacements, ...gezeichnet };
  }, [storedPlacements, plan]);

  /** Die sichtbaren Widgets in der Lesereihenfolge DIESER Verteilung. */
  const readingOrderOf = useCallback(
    (cells: HomePlacementMap, ids: readonly HomeWidgetId[]): HomeWidgetId[] =>
      [...ids].sort((a, b) => {
        const ca = cells[a];
        const cb = cells[b];
        if (!ca || !cb) return ca ? -1 : cb ? 1 : 0;
        return ca.row - cb.row || ca.col - cb.col;
      }),
    [],
  );

  /** Ein Zug ist fertig: Zellen schreiben, ansagen, Fokus behalten. */
  const commitPlacements = useCallback(
    (id: HomeWidgetId, cells: HomePlacementMap, ids: readonly HomeWidgetId[]) => {
      if (columns <= 0) return;
      const reading = readingOrderOf(cells, ids);
      setPlacements(columns, cells, reading);
      focusWidgetRef.current = id;
      announceMove(id, reading, Math.floor((cells[id]?.row ?? 0) / Math.max(1, rowsPerPage)));
    },
    [announceMove, columns, readingOrderOf, rowsPerPage, setPlacements],
  );

  /** Ein Zug per Tastatur oder Abwurf: DIESE Kachel auf DIESE Zelle. */
  const commitMove = useCallback(
    (id: HomeWidgetId, target: HomeCell) => {
      const cells = placementsAfterMove(baseForMove(), id, target);
      commitPlacements(id, cells, visibleIds.includes(id) ? visibleIds : [...visibleIds, id]);
    },
    [baseForMove, commitPlacements, placementsAfterMove, visibleIds],
  );

  /**
   * **Verschieben ohne Zeiger** (Codex §5, A11y-Pflicht: „vor/zurück/nächste
   * Seite/aus Bühne entfernen"). Die Kachel ist im Edit ein `role="button"`
   * mit `tabIndex`, also erreichbar; hier bekommt sie ihre Tastatur:
   *
   *  - **Pfeile**: einen Platz vor/zurück in der LESEREIHENFOLGE — seit W7
   *    heißt das: **mit dem Nachbarn die Zelle tauschen**. Links/Hoch =
   *    zurück, Rechts/Runter = vor. Auf einem freien Raster ist „hoch" nicht
   *    zuverlässig „eine Zeile höher" (die Kacheln haben verschiedene Spannen
   *    und es gibt Lücken), aber „mit dem Nachbarn tauschen" stimmt immer und
   *    fasst genau zwei Kacheln an — nicht die ganze Bühne.
   *  - **Bild ↑/↓**: an den Anfang der vorigen/nächsten Seite — das ist
   *    Andis „über die verschiedenen Seiten verschieben" ohne Finger. Eine
   *    Seite über das Ende hinaus ist erlaubt: so entsteht eine neue.
   *  - **Eingabe/Leer**: Stufen-Wähler auf/zu.
   *  - **Escape**: Edit-Modus verlassen.
   *
   * **Entf/Rück gibt es hier nicht mehr** (Andi 22.08. nachts: *„das aktivieren
   * oder deaktivieren der verschiedenen widgets soll über die einstellungen
   * passieren."*). Die Taste legte eine Kachel ins Fach „Verfügbar" — also
   * schaltete sie ihr Widget aus. Mit dem Fach fällt sie mit: eine Taste, die
   * ein Widget verschwinden lässt, während der Weg zurück in einem anderen
   * Bildschirm liegt, ist auf einem Wandgerät kein Kürzel, sondern eine Falle.
   */
  const onTileKeyDown = useCallback(
    (id: HomeWidgetId) => (e: ReactKeyboardEvent<HTMLElement>) => {
      const at = visibleIds.indexOf(id);
      if (at < 0) return;
      const step = (delta: number) => {
        e.preventDefault();
        e.stopPropagation();
        const neighbour = visibleIds[at + delta];
        const target = neighbour ? cellOf(neighbour) : null;
        // Am Anfang/Ende gibt es keinen Nachbarn — dann passiert nichts.
        // Lieber ein wirkungsloser Tastendruck als ein geratener Umzug.
        if (target) commitMove(id, target);
      };
      const jump = (delta: number) => {
        e.preventDefault();
        e.stopPropagation();
        const to = Math.max(0, pageOf(id)) + delta;
        if (to < 0 || to > pageCount) return;
        commitMove(id, { col: 0, row: to * Math.max(1, plan.rowsPerPage) });
      };
      switch (e.key) {
        case 'ArrowLeft':
        case 'ArrowUp':
          step(-1);
          return;
        case 'ArrowRight':
        case 'ArrowDown':
          step(1);
          return;
        case 'PageUp':
          jump(-1);
          return;
        case 'PageDown':
          jump(1);
          return;
        case 'Enter':
        case ' ':
          e.preventDefault();
          e.stopPropagation();
          setOpenFor((current) => (current === id ? null : id));
          return;
        default:
      }
    },
    [cellOf, commitMove, pageCount, pageOf, plan.rowsPerPage, visibleIds],
  );

  /**
   * **Nach einem Zug bleibt der Fokus auf der Kachel** (Codex §5,
   * „Fokus-Erhalt nach Move/Disable"). Sie ist nach dem Zug ein anderes
   * DOM-Element — React setzt Kacheln beim Umsortieren neu —, also muss der
   * Fokus aktiv nachgeholt werden, sonst landet er nach jedem Pfeildruck am
   * Dokumentanfang und das Verschieben per Tastatur ist nach einem Schritt
   * vorbei.
   */
  useIsomorphicLayoutEffect(() => {
    const want = focusWidgetRef.current;
    if (want === null) return;
    focusWidgetRef.current = null;
    trackRef.current?.querySelector<HTMLElement>(`[data-widget-id="${want}"]`)?.focus();
  });

  /**
   * **Alle Kachel-Kinder sind im Edit inert** (Andis Befund 19.08.: „Ich kam
   * an den Größen-Wähler der News-Kachel nicht heran"). Ursache war der
   * Riegel, dass ein Long-Press auf einem interaktiven Nachfahren nicht
   * startet — und die Nachrichten-Kachel besteht fast nur aus Links.
   *
   * Im Edit-Modus ist die Kachel keine Kachel mehr, sondern ein Griff: ein
   * Tipp öffnet den Stufen-Wähler, die Ecke zieht. Also darf kein Kind mehr
   * Ereignisse oder Fokus für sich beanspruchen. `inert` nimmt Zeiger UND
   * Tastatur in einem Zug; `pointer-events: none` in `index.css` ist der
   * Gürtel für Browser ohne `inert`. Damit ist JEDE Kachel größenverstellbar,
   * auch die Nachrichten.
   *
   * Läuft nach jedem Render (kein Abhängigkeits-Array): der Kachelsatz kann
   * sich zwischen zwei Rendern ändern, und die Arbeit ist ~ein Dutzend
   * `setAttribute` auf sechs Kacheln.
   */
  useIsomorphicLayoutEffect(() => {
    const marked = inertRef.current;
    const root = trackRef.current;
    const want = new Set<Element>();
    if (editing && root) {
      root.querySelectorAll('[data-widget-id]').forEach((tile) => {
        for (const child of Array.from(tile.children)) want.add(child);
      });
    }
    // NUR die DIFFERENZ anfassen. Die frühere Fassung nahm bei jedem Render
    // allen Kindern `inert` ab und hängte es sofort wieder an — bei einem Zug
    // sind das ~26 Attribut-Schreibvorgänge pro Bild. Ein `inert`, das unter
    // einem liegenden Finger auch nur für einen Moment verschwindet und
    // wiederkommt, kostet in Chrome den Zeiger: der Browser feuert
    // `pointercancel` und schickt danach kein `pointermove` mehr (gemessen mit
    // `tools/zuhause-probe/touch.mjs`). Idempotent gibt es diesen Moment nicht.
    for (const child of marked) if (!want.has(child)) child.removeAttribute('inert');
    for (const child of want) if (!child.hasAttribute('inert')) child.setAttribute('inert', '');
    inertRef.current = want;
  });

  /** Beim Verschwinden der Bühne bleibt kein `inert` an fremden Knoten zurück. */
  useEffect(
    () => () => {
      for (const child of inertRef.current) child.removeAttribute('inert');
      inertRef.current = new Set();
    },
    [],
  );

  /**
   * „Widgets anordnen" aus den Einstellungen (§4.3) — der einzige Weg in den
   * Edit-Modus, der keinen Zeiger braucht. Der Wunsch wird beim Mounten UND
   * beim Zuhören abgeholt: stand die Bühne schon, gewinnt der Listener; hat
   * `App.tsx` erst den Reiter gewechselt, gewinnt das Mounten. Abgeholt wird
   * er genau einmal.
   */
  useEffect(() => {
    const take = () => {
      if (takeHomeEditRequest()) setEditing(true);
    };
    take();
    return subscribeHomeEdit(take);
  }, []);

  /** Escape verlässt den Edit-Modus, egal wo der Fokus gerade steht (§4.2). */
  useEffect(() => {
    if (!editing) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      // Steht der Stufen-Wähler offen, schließt Escape zuerst IHN — sonst
      // verlöre ein Druck zwei Ebenen auf einmal.
      setOpenFor((current) => {
        if (current !== null) return null;
        setEditing(false);
        return current;
      });
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [editing]);

  /**
   * **Ein Druck außerhalb der Bühne beendet den Edit-Modus** (W6, die zweite
   * Hälfte von Andis „oder durch einen freien Klick irgendwohin").
   *
   * „Irgendwohin" ist wörtlich zu nehmen: der Kopf, der Orb, die Fußleiste,
   * die Nav — alles, was nicht die Bühne ist. Die Bühne selbst regelt ihren
   * eigenen Leerraum in `onPointerUp` (dort weiß der Schiedsrichter, ob der
   * Finger auf einer Kachel lag, gezogen hat oder auf einem Bedienelement
   * saß); hier draußen gibt es nichts zu unterscheiden.
   *
   * `pointerdown`, nicht `click`: ein Druck auf den Orb soll den Modus
   * schließen UND aufnehmen, nicht eines von beidem verschlucken. Und in der
   * Capture-Phase, damit ein Ziel, das das Ereignis stoppt, den Ausstieg nicht
   * mit verschluckt.
   *
   * Der Weg IN den Modus über die Einstellungen bleibt heil: der Knopf-Druck
   * dort ist längst vorbei, wenn dieser Effekt (nach dem Render) seinen
   * Horcher anmeldet.
   */
  useEffect(() => {
    if (!editing) return;
    const onDown = (e: Event) => {
      if (e.target instanceof Element && e.target.closest('.idle__stage')) return;
      setEditing(false);
    };
    window.addEventListener('pointerdown', onDown, true);
    return () => window.removeEventListener('pointerdown', onDown, true);
  }, [editing]);

  /**
   * **Der dritte Ausgang: Ruhe** ({@link HOME_EDIT_IDLE_MS}). Die beiden
   * bestellten Ausgänge setzen eine anwesende Person voraus; dieser hier ist
   * für die Person, die nicht mehr da ist.
   *
   * Wach halten den Modus nur ECHTE Eingaben — Druck, Taste, Rad, Bewegung.
   * Bewusst NICHT: Uhrzeit-Ticks, eintreffende Wetterdaten, Neu-Renders. Sonst
   * hielte sich der Modus selbst am Leben, und die Zusage wäre keine.
   *
   * Zwei Riegel gegen ein Aussteigen zur Unzeit:
   *  - Eine laufende Geste (`gestureRef`) zählt als Anwesenheit: wer eine
   *    Kachel gerade zieht, ist da, auch wenn er lange braucht. Statt zu
   *    schließen wird dann neu gestellt.
   *  - Die Bewegungs-Meldung ist gedrosselt ({@link HOME_EDIT_IDLE_THROTTLE_MS}),
   *    damit ein wandernder Zeiger nicht sekündlich Timer verheizt.
   *
   * `capture: true` wie beim Klick-ins-Leere: ein Ziel, das das Ereignis stoppt,
   * soll die Ruhe-Uhr trotzdem zurücksetzen — sonst gälte ausgerechnet die
   * Bedienung der Edit-Leiste selbst als Untätigkeit.
   */
  useEffect(() => {
    if (!editing) return;
    let timer = 0;
    let last = 0;
    const arm = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(() => {
        if (gestureRef.current) {
          last = 0; // die Geste läuft noch — neu stellen, nicht schließen
          arm();
          return;
        }
        setEditing(false);
      }, HOME_EDIT_IDLE_MS);
    };
    const touch = () => {
      const now = Date.now();
      if (now - last < HOME_EDIT_IDLE_THROTTLE_MS) return;
      last = now;
      arm();
    };
    arm();
    const kinds = ['pointerdown', 'pointermove', 'keydown', 'wheel'] as const;
    for (const kind of kinds) window.addEventListener(kind, touch, true);
    return () => {
      window.clearTimeout(timer);
      for (const kind of kinds) window.removeEventListener(kind, touch, true);
    };
  }, [editing]);

  /**
   * **Die Szene hält an, solange man anordnet** (Andi 23.08.: „es laggt
   * leider, besonders, wenn ich das design ausgewählt habe und die widgets
   * anpasse").
   *
   * Gemessen mit `tools/theme-contrast/szene-perf.mjs` bei 1600×1000, Firefox
   * — Andis Browser: die Hanaikada-Szene kostet 782 % CPU und drückt die
   * Bildrate auf 35 fps, angehalten sind es 13 % und 60 fps. Beim Anordnen
   * zählt Reaktion, nicht Blütenfall.
   *
   * Hier steht bewusst NUR der Halt: welches Thema was anhält, entscheiden
   * `styles/themes.css` und die Themen-Dateien. Diese Bühne kennt keine
   * Themen und soll auch nie welche kennenlernen.
   *
   * Der Halt hängt an `editing` und nicht an der Geste: er soll auch stehen,
   * während man überlegt, wohin die Kachel soll — und das ist die längere
   * Zeit von beiden.
   */
  useEffect(() => {
    if (!editing) return;
    return holdSceneMotion();
  }, [editing]);

  /** Der Edit-Modus endet ⇒ nichts bleibt gesagt, nichts bleibt gegriffen. */
  useEffect(() => {
    if (editing) return;
    setAnnouncement('');
    setDrag(null);
    // Der Wähler gehört zum Modus: er darf ihn nicht überleben (W6 — der
    // Ausstieg per Tipp auf die ausgewählte Kachel lässt ihn sonst als
    // körperloses Kästchen über einer stillen Bühne stehen).
    setOpenFor(null);
  }, [editing]);

  const commitThreshold = Math.min(
    HOME_SWIPE_COMMIT_MAX_PX,
    Math.max(32, (metrics?.width ?? 0) * HOME_SWIPE_COMMIT_RATIO),
  );

  const endSwipe = useCallback(
    (el: Element, pointerId: number, deltaX: number) => {
      tryRelease(el, pointerId);
      setDragging(false);
      setDragX(0);
      if (Math.abs(deltaX) <= commitThreshold) return;
      setPage((current) => {
        const next = deltaX < 0 ? current + 1 : current - 1;
        return Math.max(0, Math.min(pageCount - 1, next));
      });
    },
    [commitThreshold, pageCount],
  );

  /** Tötet den laufenden Long-Press-Timer, ohne die Geste zu beenden. */
  const killTimer = (gesture: GestureState) => {
    if (gesture.timer !== null) {
      window.clearTimeout(gesture.timer);
      gesture.timer = null;
    }
  };

  /** Bricht einen laufenden `requestAnimationFrame` ab (Zug zu Ende oder abgebrochen). */
  const killFrame = () => {
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
    }
    pointRef.current = null;
  };

  /** Niemand bekommt diese Geste mehr — Timer tot, Zug zurück, kein Seitenwechsel. */
  const abandon = (el: Element | null) => {
    const gesture = gestureRef.current;
    if (!gesture) return;
    killTimer(gesture);
    killFrame();
    gesture.phase = 'cancelled';
    gestureRef.current = null;
    if (el) tryRelease(el, gesture.pointerId);
    setDragging(false);
    setDragX(0);
    // Ein abgebrochener Zug schreibt NICHTS (Codex §5, Interaktionstest
    // „Pointer-Cancel"): die Vorschau fällt weg, das gespeicherte Layout ist
    // unverändert — die Kachel springt sichtbar dorthin zurück, wo sie war.
    setDrag(null);
  };

  /** Die 600 ms sind voll: der Wähler geht auf, und die nativen Nebenwirkungen gehen aus. */
  const openSizer = (id: HomeWidgetId) => {
    // Was der native Long-Press schon angefangen hat (Textmarkierung/Callout),
    // wird hier zurückgenommen; das Weitere hält `data-sizing` per CSS ab.
    try {
      window.getSelection?.()?.removeAllRanges();
    } catch {
      /* keine Selection-API — dann gab es auch nichts zu markieren. */
    }
    suppressClickRef.current = true;
    suppressContextMenuRef.current = true;
    setOpenFor(id);
  };

  /* ── Der Zug selbst (W4) ──────────────────────────────────────────────── */

  /** Die gemessene Kachelfläche der AKTIVEN Seite — Basis jeder Drop-Rechnung. */
  const activePageBox = (): DOMRect | null => {
    const el = trackRef.current?.querySelector<HTMLElement>('.idle__page[data-active="true"]');
    return el ? el.getBoundingClientRect() : null;
  };

  /**
   * Der Auto-Pager (§2.5/§4.2 — Andi 19.08.: „über die verschiedenen Seiten
   * verschieben" ist Pflicht). Er hängt am EINEN Timer der Geste: solange der
   * Finger in der Randzone liegt, läuft er; verlässt der Finger sie, stirbt er
   * (das ist der „Auto-Paging-Abbruch", den Codex §5 als Test verlangt).
   * Feuert er, blättert die Bühne und der Timer startet neu — so kann man
   * ohne Loslassen über mehrere Seiten wandern.
   */
  const tickAutoPage = (gesture: GestureState, x: number) => {
    const box = activePageBox();
    if (!box || pageCount < 2) {
      if (gesture.edgeDir !== 0) {
        killTimer(gesture);
        gesture.edgeDir = 0;
      }
      return;
    }
    const dir: -1 | 0 | 1 =
      x <= box.left + HOME_AUTOPAGE_EDGE_PX ? -1 : x >= box.right - HOME_AUTOPAGE_EDGE_PX ? 1 : 0;
    if (dir === gesture.edgeDir) return;
    killTimer(gesture);
    gesture.edgeDir = dir;
    if (dir === 0) return;
    const arm = () => {
      gesture.timer = window.setTimeout(() => {
        if (gestureRef.current !== gesture || gesture.phase !== 'drag') return;
        gesture.timer = null;
        setPage((current) => {
          const next = Math.max(0, Math.min(pageCount - 1, current + dir));
          // Am Ende der Bühne hört das Blättern auf statt still weiterzuticken.
          if (next !== current) arm();
          else gesture.edgeDir = 0;
          return next;
        });
      }, HOME_AUTOPAGE_DWELL_MS);
    };
    arm();
  };

  /**
   * Ein Bild weiter: EIN `setState` pro Frame, egal wie oft der Zeiger feuert
   * (Codex §5, Pointer-Performance). Hier wird alles entschieden, was der Zug
   * verändert — Verschiebung, Vorschau-Reihenfolge, Fach-Ziel, Auto-Paging.
   */
  const runFrame = () => {
    rafRef.current = null;
    const gesture = gestureRef.current;
    const point = pointRef.current;
    if (!gesture || !point) return;

    if (gesture.phase !== 'drag' || gesture.widgetId === null) return;
    const id = gesture.widgetId;
    const dx = point.x - gesture.startX;
    const dy = point.y - gesture.startY;
    tickAutoPage(gesture, point.x);

    const target = dropCellAt(point.x, point.y);
    const base = dragBaseRef.current;
    if (target === null || base === null) {
      setDrag({ id, dx, dy, placements: null });
      return;
    }
    const next = placementsAfterMove(base, id, target);
    setDrag((current) =>
      current && current.placements && samePlacements(current.placements, next)
        ? { ...current, dx, dy }
        : { id, dx, dy, placements: next },
    );
  };

  /** Merkt sich die Zeiger-Position und plant höchstens EIN Bild ein. */
  const schedule = (x: number, y: number) => {
    pointRef.current = { x, y };
    if (rafRef.current !== null) return;
    rafRef.current =
      typeof requestAnimationFrame === 'function'
        ? requestAnimationFrame(runFrame)
        : (window.setTimeout(runFrame, 16) as unknown as number);
  };

  const onPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    // Ein zweiter Finger beendet jede Zuständigkeit (Zoom/Zwei-Finger-Scroll
    // ist weder Swipe noch Long-Press) — Codex §3.
    if (gestureRef.current) {
      abandon(e.currentTarget);
      return;
    }
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    // Ein hängengebliebenes Klick-Veto (Pointer-Up ohne folgenden Click) darf
    // nicht den nächsten echten Klick fressen.
    suppressClickRef.current = false;
    // Im Edit gilt der Nachfahren-Riegel nicht mehr (alle Kinder sind inert) —
    // deshalb zwei Fragen statt einer.
    // Auf Glas darf ein langer Druck auch auf einem Link in den Edit-Modus
    // führen — dort gibt es keinen Rechtsklick als zweiten Weg (s. KDoc am
    // zweiten Parameter von `sizableWidgetAt`). Bewusst eine POSITIVE Liste:
    // ein Ereignis ohne `pointerType` wird wie eine Maus behandelt, der Riegel
    // bleibt also stehen, wo wir das Gerät nicht kennen.
    const onGlass = e.pointerType === 'touch' || e.pointerType === 'pen';
    const widgetId = editing ? widgetAt(e.target) : sizableWidgetAt(e.target, onGlass);
    // Tippen neben den offenen Wähler schließt ihn — und startet trotzdem
    // sauber die nächste Geste (auch einen neuen Long-Press auf einer anderen
    // Kachel).
    if (openFor !== null && !(e.target instanceof Element && e.target.closest('.idle__sizer'))) {
      setOpenFor(null);
    }
    const gesture: GestureState = {
      pointerId: e.pointerId,
      startX: e.clientX,
      startY: e.clientY,
      phase: 'pending',
      timer: null,
      widgetId,
      edgeDir: 0,
      // `openFor` ist der Wert dieser Render-Closure; das `setOpenFor(null)`
      // eine Zeile höher ändert ihn nicht. Hier steht also noch der Zustand
      // von VOR diesem Tipp — genau das, was `onPointerUp` braucht (s. KDoc
      // am Feld).
      openAtDown: openFor,
      fromChrome: onEditChrome(e.target),
    };
    gestureRef.current = gesture;
    /* **HIER wird KEINE Capture geholt** — und das ist eine Narbe, keine
     * Selbstverständlichkeit.
     *
     * Hier stand `if (editing) tryCapture(...)`: im Edit sollte die Bühne den
     * Zeiger schon beim Aufsetzen halten, damit die Zug-Vorschau die Kachel
     * im DOM umsetzen darf, ohne den Zeiger mitzunehmen. In Chrome war das
     * grün — und in Firefox hat es die Größeneinstellung getötet.
     *
     * Andi (22.08. nachts, live, Firefox): *„Die Größen einstellung ist
     * komplett kaputt. es passiert nichts, wenn ich auf plus oder minus
     * klicke."* Gemessen mit `tools/zuhause-probe/firefox.mjs` (WebDriver
     * BiDi, echtes Gecko, Maus):
     *
     *   pointerdown @.idle__sizerbtn → gotpointercapture @.idle__stage
     *     → pointerup @.idle__stage → click @.idle__stage
     *
     * **Gecko zieht den `click` auf das capturende Element mit** (Pointer
     * Events, „Process pending pointer capture" — der Klick gehört dem
     * Capture-Halter, nicht dem getroffenen Knopf). Blink tut das nicht.
     * Der `onClick` des `+`-Knopfes wurde also nie aufgerufen, die Stufe im
     * Speicher blieb stehen, und von außen sah es aus, als sei der Knopf tot.
     * Dieselbe Zeile, zwei Engines, zwei völlig verschiedene Produkte.
     *
     * Eine Capture, die ein reiner TIPP anlegt, ist der Fehler — nicht die
     * Capture selbst. Sie wird deshalb erst geholt, wenn aus der Berührung
     * wirklich ein ZUG geworden ist (`onPointerMove`, Phase `drag`): dort
     * ist die Absicht bewiesen, dort gibt es keinen Klick mehr zu verlieren,
     * und die Vorschau läuft weiterhin unter dem Schutz der Capture. Ein
     * Tipp legt nirgends mehr eine an — in keiner Engine.
     */
    // KEIN `pageCount < 2`-Riegel mehr (Codex §3): auf einer einzelnen Seite
    // gibt es nichts zu wischen, aber sehr wohl etwas zu drücken.
    // Im Edit-Modus gibt es keinen Long-Press mehr — man IST schon drin; ein
    // Tipp wählt die Größe, eine Bewegung zieht.
    if (widgetId !== null && !editing) {
      gesture.timer = window.setTimeout(() => {
        if (gestureRef.current !== gesture || gesture.phase !== 'pending') return;
        gesture.phase = 'edit';
        gesture.timer = null;
        // Der lange Druck öffnet BEIDES: den Edit-Modus (alle wackeln) und den
        // Wähler der gedrückten Kachel. Zwei Drücke für „ich will genau diese
        // Kachel größer" wären einer zu viel, und die Kachel unter dem Finger
        // ist die einzige, die er gemeint haben kann.
        setEditing(true);
        openSizer(widgetId);
      }, HOME_LONG_PRESS_MS);
    }
  };

  const onPointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    const gesture = gestureRef.current;
    if (!gesture || gesture.pointerId !== e.pointerId) return;
    if (gesture.phase === 'edit' || gesture.phase === 'cancelled') return;
    if (gesture.phase === 'drag') {
      schedule(e.clientX, e.clientY);
      return;
    }
    const dx = e.clientX - gesture.startX;
    const dy = e.clientY - gesture.startY;
    // Der Timer stirbt bei Bewegung in JEDER Richtung — senkrechtes Scrollen
    // (`touch-action: pan-y` ist aktiv) darf keinen Wähler aufgehen lassen.
    if (Math.abs(dx) > HOME_TAP_SLOP_PX || Math.abs(dy) > HOME_TAP_SLOP_PX) killTimer(gesture);
    // Im Edit-Modus wird aus derselben Bewegung ein ZUG statt eines Wischs,
    // sobald sie die Tap-Toleranz verlässt — in JEDER Richtung, denn eine
    // Kachel wandert auch nach unten ins Fach.
    if (gesture.phase === 'pending' && editing && gesture.widgetId !== null) {
      if (Math.abs(dx) <= HOME_TAP_SLOP_PX && Math.abs(dy) <= HOME_TAP_SLOP_PX) return;
      gesture.phase = 'drag';
      // Die Verteilung, von der DIESER Zug ausgeht — einmal festgehalten, bevor
      // die erste Vorschau sie ersetzt (s. `placementsAfterMove`).
      dragBaseRef.current = baseForMove();
      tryCapture(e.currentTarget, e.pointerId);
      setOpenFor(null);
      schedule(e.clientX, e.clientY);
      return;
    }
    if (gesture.phase === 'pending') {
      if (pageCount < 2) return;
      // Waagerechte Absicht muss klar gewinnen, sonst gehört die Geste dem,
      // was unter dem Finger liegt (eigenes Scrollen, Link-Tipp).
      if (Math.abs(dx) < HOME_SWIPE_ENGAGE_PX || Math.abs(dx) <= Math.abs(dy)) return;
      gesture.phase = 'swipe';
      tryCapture(e.currentTarget, e.pointerId);
      setDragging(true);
    }
    const atStart = page === 0 && dx > 0;
    const atEnd = page === pageCount - 1 && dx < 0;
    setDragX(atStart || atEnd ? dx / HOME_SWIPE_OVERDRAG_DIVISOR : dx);
  };

  const onPointerUp = (e: ReactPointerEvent<HTMLDivElement>) => {
    const gesture = gestureRef.current;
    if (!gesture || gesture.pointerId !== e.pointerId) return;
    killTimer(gesture);
    killFrame();
    const { phase, widgetId, openAtDown, fromChrome } = gesture;
    gestureRef.current = null;
    tryRelease(e.currentTarget, e.pointerId);

    // Der Abwurf — die EINE Stelle, an der ein Zug ins Layout geschrieben wird.
    if (phase === 'drag' && widgetId !== null) {
      setDrag(null);
      if (drag?.placements) commitPlacements(widgetId, drag.placements, visibleIds);
      return;
    }
    /* ── Der Tipp im Edit-Modus (W6) ──────────────────────────────────────
     *
     * Andi 20.08., wörtlich: *„Um die Einstellung wieder zu verlassen, muss man
     * nochmal mit der Maus auf dem Widget geklickt haben oder durch einen
     * freien Klick irgendwohin."*
     *
     * Das steht in Spannung zu W4s Lösung für Andis eigenen Befund vom 19.08.
     * („ich kam an den Größen-Wähler der News-Kachel nicht heran"): dort öffnet
     * ein Tipp auf JEDE Kachel ihren Wähler. Beides zugleich geht nur, wenn der
     * Tipp unterscheidet, WELCHE Kachel gemeint ist — und genau das ist die
     * Entscheidung dieses Pods:
     *
     *   Tipp auf eine ANDERE Kachel  ⇒ die wird ausgewählt (Wähler geht auf).
     *   Tipp auf die AUSGEWÄHLTE     ⇒ der Edit-Modus endet.
     *   Tipp auf freie Fläche        ⇒ der Edit-Modus endet.
     *
     * Der Stufen-Zugang stirbt damit nicht: er ist genau einen Tipp entfernt,
     * für jede Kachel, wie seit W4. Was sich ändert, ist die Bedeutung des
     * ZWEITEN Tipps auf dieselbe Kachel — der schloss bisher nur den Wähler und
     * ließ einen wackelnden Bildschirm zurück, dessen Ausgang nur „Fertig"
     * oder Escape war. Ein Zustand, aus dem man nur über einen Knopf
     * herauskommt, ist auf einem Flur-Display eine Falle.
     *
     * Die TASTATUR behält die feinere Zweistufigkeit (Eingabe schaltet den
     * Wähler, Escape schließt erst ihn, dann den Modus): dort ist der Weg
     * zurück nie verstellt, und wer mit Tab durch die Kacheln geht, will beim
     * Bestätigen nicht aus dem Modus fallen.
     */
    if (phase === 'pending' && editing) {
      const still =
        Math.abs(e.clientX - gesture.startX) <= HOME_TAP_SLOP_PX &&
        Math.abs(e.clientY - gesture.startY) <= HOME_TAP_SLOP_PX;
      if (still && widgetId !== null) {
        if (openAtDown === widgetId) setEditing(false);
        else setOpenFor(widgetId);
        return;
      }
      // „Freier Klick irgendwohin" INNERHALB der Bühne. Leiste, Fach, Wähler,
      // Griffecke und Seitenpunkte sind Bedienung, keine freie Fläche —
      // sonst schlösse der erste Druck auf „Zurücksetzen" den Modus, in dem
      // der Knopf steht. Alles AUSSERHALB der Bühne erledigt der Fenster-
      // Horcher weiter oben.
      if (still && !fromChrome) {
        setEditing(false);
        return;
      }
    }
    endSwipe(e.currentTarget, e.pointerId, phase === 'swipe' ? e.clientX - gesture.startX : 0);
  };

  /**
   * **Die ZELLE, die ein Abwurf an dieser Bildschirmstelle meint** (W7-B, an
   * die Stelle des früheren Listenplatzes getreten).
   *
   * Gemessen wird gegen das Raster der AKTIVEN Seite — das ist das Raster, das
   * unter dem Finger wirklich liegt (eine Seite kann weniger Zeilen zeigen,
   * als auf sie passen). Die seitenlokale Zeile wird danach auf die
   * seitenübergreifende umgerechnet, denn nur die wird gespeichert.
   */
  const dropCellAt = (x: number, y: number): HomeCell | null => {
    const box = activePageBox();
    if (!box || !plan.measured || box.width <= 0 || box.height <= 0) return null;
    const rowsOnPage = Math.max(1, plan.pages[page]?.rows ?? plan.rowsPerPage);
    const local = homeDropCell(
      { x: x - box.left, y: y - box.top },
      { width: box.width, height: box.height, columns: plan.columns, rows: rowsOnPage },
    );
    return { col: local.col, row: local.row + page * Math.max(1, plan.rowsPerPage) };
  };

  const onPointerCancel = (e: ReactPointerEvent<HTMLDivElement>) => {
    const gesture = gestureRef.current;
    if (!gesture || gesture.pointerId !== e.pointerId) return;
    abandon(e.currentTarget);
  };

  /**
   * Verlorene Capture = Scroll-/Systemübernahme: die Geste gehört uns nicht mehr.
   *
   * **Nur wenn die BÜHNE selbst sie verliert** (`e.target === e.currentTarget`).
   * Ohne diese eine Frage hat der Finger auf dem iPad keine einzige Geste
   * zustande gebracht, und niemand konnte es sehen — der Grund liegt in einem
   * Unterschied zwischen Maus und Finger, den es im Code nirgends gab:
   *
   * Ein Touch-Punkt ist vom `pointerdown` an **implizit** an das getroffene
   * Element gefesselt (Pointer-Events-Spezifikation, „implicit pointer
   * capture" — nur für `touch`, nie für `mouse`). Die Kachel unter dem Finger
   * hält die Capture also längst, wenn `onPointerMove` sie mit
   * {@link tryCapture} an die Bühne holt. Genau dieser eigene Griff feuert am
   * alten Halter ein `lostpointercapture` — und das BLUBBERT bis hierher.
   * Die Bühne hat dann ihre eigene, gerade begonnene Geste für eine
   * Systemübernahme gehalten und sich selbst abgewürgt: Wisch tot, Zug tot,
   * schon beim ersten Millimeter.
   *
   * Am Laptop passiert das nie: eine Maus hat keine implizite Capture, dort
   * kommt `lostpointercapture` erst beim Loslassen — von der Bühne selbst.
   * Deshalb „am Laptop geht das" (Andi, 22.08.) bei identischem Code.
   */
  const onLostPointerCapture = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (e.target !== e.currentTarget) return;
    const gesture = gestureRef.current;
    if (!gesture || gesture.pointerId !== e.pointerId) return;
    abandon(null);
  };

  /**
   * Der Klick, den ein erfolgreicher Long-Press hinter sich herzieht, wird
   * geschluckt (Codex §3) — sonst öffnete derselbe Druck zusätzlich den Link
   * unter dem Finger. Capture-Phase, damit er die Kachel gar nicht erreicht.
   */
  const onClickCapture = (e: ReactMouseEvent<HTMLDivElement>) => {
    if (!suppressClickRef.current) return;
    /*
     * **Der nachlaufende Klick wird geschluckt, EGAL wo er landet** (Nachtrag 2
     * der Hand, 22.08. spät — auf dem gemergten Stand gemessen).
     *
     * Hier stand eine Ausnahme für Wähler, Edit-Leiste und Fach: die Bedienung,
     * die derselbe Long-Press gerade aufgemacht hat, sollte den ersten Druck
     * behalten. Der Gedanke war richtig, das Mittel falsch — denn auf Glas
     * landet der nachlaufende Klick nicht dort, wo der Finger lag, sondern dort,
     * wo nach dem Umbau etwas liegt. Ein langer Druck auf einer Schlagzeile
     * öffnet den Edit-Modus, die Leiste schiebt sich über genau diese Stelle,
     * und der synthetische Klick trifft **sie**. Gemessen auf der
     * Nachrichten-Kachel (`tools/zuhause-probe/touch.mjs`, Schritt 6):
     * `pointerdown@.idle__newstitle → … → click@.idle__editbar`. Die Ausnahme
     * winkte ihn durch — ein Druck, den niemand gemeint hat, auf einem Knopf,
     * den es eine Zehntelsekunde vorher noch nicht gab. „Fertig" lag in dieser
     * Leiste: der Modus konnte sich im selben Atemzug wieder schließen. (Die
     * Leiste selbst ist am 23.08. gefallen; der Befund bleibt trotzdem
     * lehrreich, denn der Stufen-Wähler geht bei jedem Long-Press genauso auf.)
     *
     * Die Ausnahme fragte nach dem ORT und lag damit falsch. Gefragt werden
     * muss, WOHER der Klick kommt:
     *
     *  - Ein Klick aus einer echten Zeigergeste trägt `detail >= 1`. Genau das
     *    ist der nachlaufende Klick — er gehört niemandem und wird geschluckt.
     *  - Tastatur (Eingabe/Leertaste), VoiceOver und jedes programmatische
     *    `.click()` liefern `detail === 0`. Die kommen von einer ABSICHT, nicht
     *    von dem Druck, der eben den Modus geöffnet hat — und die dürfen nie
     *    geschluckt werden. Ohne diese Zeile fräße das Veto den ersten
     *    Tastendruck auf einen Stufen-Knopf; genau das ist beim Bau schon
     *    einmal passiert.
     */
    if (e.detail === 0) return;
    suppressClickRef.current = false;
    e.preventDefault();
    e.stopPropagation();
  };

  /**
   * Das native Kontextmenü nach einem Long-Press bleibt zu. Auf dem Desktop
   * ist derselbe Ereignispfad zugleich der EINZIGE Zeiger-Weg zum Wähler, den
   * es ohne Edit-Modus (W4) gibt: Rechtsklick auf eine Kachel öffnet ihn.
   */
  const onContextMenu = (e: ReactMouseEvent<HTMLDivElement>) => {
    if (suppressContextMenuRef.current) {
      suppressContextMenuRef.current = false;
      e.preventDefault();
      return;
    }
    const widgetId = sizableWidgetAt(e.target);
    if (widgetId === null) return;
    e.preventDefault();
    const gesture = gestureRef.current;
    if (gesture) killTimer(gesture);
    openSizer(widgetId);
  };

  const onSizerKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'Escape') return;
    e.stopPropagation();
    setOpenFor(null);
  };

  /**
   * Die Größensteuerung EINER Kachel (§4.2, **W6-Fassung**). Andi 20.08.
   * wörtlich: *„Die Größenauswahl soll ein + und − sein; sobald es nicht größer
   * werden kann, sind die entsprechenden Pfeile ausgegraut."*
   *
   * Bis W5 standen hier vier Stufen-Knöpfe (S · M · L · XL). Zwei Gründe
   * sprachen dagegen, und Andi hat beide auf einmal benannt:
   *  1. Vier Knöpfe verlangen, dass man die Zuordnung „welcher Buchstabe ist
   *     wie groß" schon kennt. `−`/`+` verlangt nur, dass man weiß, in welche
   *     Richtung man will — und zeigt die Antwort sofort an der Kachel.
   *  2. Die Registry-Unterschiede (der Sauger hat kein XL, die Uhr auch nicht)
   *     äußerten sich als FEHLENDE Knöpfe: dieselbe Leiste hatte je nach
   *     Kachel drei oder vier Felder und sprang beim Wechsel. Jetzt sind es
   *     immer zwei, und der Unterschied äußert sich als *ausgegraut* — die
   *     Grenze ist sichtbar, statt spurlos zu sein.
   *
   * Ausgegraut wird NICHT per handgeschriebenem Vergleich, sondern per
   * {@link stepHomeTileSize}: `disabled` gilt genau dann, wenn dieselbe reine
   * Funktion, die auch der Klick ruft, `null` liefert. Ein Knopf kann damit
   * nicht drückbar aussehen und trotzdem nichts tun.
   *
   * **W7-D, Andi 21.08. wörtlich:** *„Vereinheitliche die Steuerung bei allen
   * Widgets, dass sie nur noch über das +/−-Prinzip steuerbar sind — einfach
   * durch dezentere Icons. Das M darunter verändert die Größe des Widgets, das
   * macht es schwer platzierbar."* Drei Folgen, alle hier:
   *
   *  1. **Der sichtbare Stufen-Buchstabe ist weg.** Er war eine zweite Anzeige
   *     derselben Sache — die Kachel SELBST zeigt ihre Stufe, indem sie größer
   *     oder kleiner ist. Für Screenreader bleibt der lange Name in der
   *     `aria-live`-Region: sonst gäbe ein Druck auf `+` gar keine hörbare
   *     Rückmeldung, weil sich am Knopf nichts ändert. Weggenommen wird also
   *     Optik, nicht Information.
   *  2. **Die Griffecke ist weg** (nicht nur versteckt). Sie war der zweite
   *     Weg zur Größe und belegte dafür 44 × 44 in der unteren rechten Ecke
   *     JEDER Kachel — ausgerechnet dort, wo ein Zug zum Platzieren beginnen
   *     will. „Schwer platzierbar" war sie damit wörtlich. Zwei Wege zur
   *     Größe waren außerdem zwei Wahrheiten (`sizeForSpan` gegen
   *     {@link stepHomeTileSize}), die auseinanderlaufen konnten.
   *  3. **Er liegt ÜBER der Kachel, nicht in ihrem Fluss** (`position:
   *     absolute` in seiner Rasterzelle, `index.css`) — dieselbe Regel wie die
   *     Edit-Schicht in W7-A, nur eine Ebene tiefer: die Kachel ist im Edit
   *     exakt so groß wie außerhalb, egal ob ihr Wähler offen steht.
   */
  const renderSizer = (
    id: HomeWidgetId,
    cell: { row: number; col: number; cols: number; rows: number } | null,
  ): ReactNode => {
    const allowed = homeWidget(id).sizes;
    const stored = layoutIndex.get(id)?.size ?? homeWidget(id).defaultSize ?? 'M';
    const shown = plan.measured ? effectiveSize(stored, plan.columns, plan.rowsPerPage) : stored;
    const steps: { dir: 'down' | 'up'; delta: -1 | 1; glyph: string; label: string }[] = [
      { dir: 'down', delta: -1, glyph: '−', label: idleFace.stage.sizeSmaller },
      { dir: 'up', delta: 1, glyph: '+', label: idleFace.stage.sizeLarger },
    ];
    return (
      <div
        key={`sizer-${id}`}
        className="idle__sizer"
        role="group"
        aria-label={idleFace.stage.sizerAria(widgetName(id))}
        onKeyDown={onSizerKeyDown}
        style={
          cell
            ? {
                gridColumn: `${cell.col + 1} / span ${cell.cols}`,
                gridRow: `${cell.row + 1} / span ${cell.rows}`,
              }
            : undefined
        }
      >
        <div className="idle__sizerrow">
          {steps.map((step) => {
            const next = stepHomeTileSize(allowed, stored, step.delta);
            return (
              <button
                key={step.dir}
                type="button"
                className="idle__sizerbtn"
                data-dir={step.dir}
                // „−" allein ist kein zugänglicher Name (Codex §5) — das Wort
                // steht im Label, das Zeichen auf dem Knopf.
                aria-label={step.label}
                disabled={next === null}
                onClick={() => {
                  if (next === null) return;
                  focusDirRef.current = step.dir;
                  setSize(id, next);
                }}
              >
                {step.glyph}
              </button>
            );
          })}
        </div>
        {/* **Die Stufe fürs OHR** (W7-D): der Buchstabe fürs Auge ist weg — die
            Kachel selbst ist die Anzeige. Die `aria-live`-Region bleibt, sonst
            wäre ein Druck auf `+` für VoiceOver eine Änderung ohne Antwort.
            Sie steht IMMER im DOM, auch bevor es etwas zu sagen gibt: eine
            Region, die zugleich mit ihrem Text entsteht, liest kaum ein
            Screenreader vor (dasselbe Argument wie bei `.idle__editsr` unten). */}
        <span className="idle__sizerstep idle__sronly" role="status" aria-live="polite">
          {idleFace.stage.sizeNames[stored]}
        </span>
        {/* Ehrlich statt still: die gewählte Stufe passt gerade nicht auf diesen
            Bildschirm. Der gespeicherte Wert bleibt trotzdem stehen (§0.4).
            Das darf sichtbar bleiben — es ist keine Steuerung, sondern eine
            Auskunft, und sie erscheint nur im Ausnahmefall. */}
        {shown !== stored && (
          <span className="idle__sizernote">
            {idleFace.stage.sizerEffective(idleFace.stage.sizeNames[shown])}
          </span>
        )}
      </div>
    );
  };

  // Eine leere Bühne rendert NICHTS — außer der Edit-Modus läuft: dann muss
  // das Fach erreichbar bleiben, sonst käme ein ausgeschaltetes Widget nie
  // wieder zurück.
  if (pageCount === 0 && !editing) return null;

  const trackStyle: CSSProperties = {
    transform: `translate3d(calc(${-page * 100}% + ${Math.round(dragX)}px), 0, 0)`,
  };

  /** Alles, was eine Kachel im Edit zusätzlich trägt — an EINER Stelle gebaut. */
  const editPropsFor = (id: string): TileEditProps | null => {
    if (!editing || !isStageWidgetId(id)) return null;
    const index = visibleIds.indexOf(id);
    return {
      // Der Versatz kommt aus dem Platz in der GANZEN Liste, nicht aus dem
      // Platz auf der Seite: sonst wackelten die erste Kachel von Seite 1 und
      // die erste von Seite 2 im Gleichschritt, und beim Blättern sähe man es.
      // 137 ms ist bewusst keine runde Zahl — bei einem glatten Vielfachen der
      // Dauer liefen je zwei Kacheln wieder synchron.
      wiggleDelayMs: (Math.max(0, index) * 137) % 900,
      dragOffset: drag && drag.id === id ? { dx: drag.dx, dy: drag.dy } : null,
      label: editText.tileAria(widgetName(id), index + 1, visibleIds.length),
      roleDescription: editText.tileRole,
      onKeyDown: onTileKeyDown(id),
    };
  };

  return (
    // Die Zeiger-Ereignisse hängen an der HÜLLE, nicht an der Schiene: ein Zug
    // kann im Fach beginnen (Widget zurück auf die Bühne) und über der Bühne
    // enden. Ein Besitzer, ein Element — sonst bräuchte das Fach eine zweite
    // Zustandsmaschine.
    <div
      className="idle__stage"
      ref={stageRef}
      data-edit={editing ? 'true' : 'false'}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
      onLostPointerCapture={onLostPointerCapture}
      onClickCapture={onClickCapture}
      onContextMenu={onContextMenu}
    >
      <div className="idle__tiles" data-pages={pageCount}>
        <div
          className="idle__pages"
          ref={trackRef}
          style={trackStyle}
          data-dragging={dragging ? 'true' : 'false'}
          // Solange ein Wähler offen ist, markiert der Finger keinen Text mehr
          // (index.css) — die zweite Hälfte der Long-Press-Nebenwirkungen.
          data-sizing={openFor !== null ? 'true' : 'false'}
          data-edit={editing ? 'true' : 'false'}
        >
          {plan.pages.map((stagePage, index) => (
            <div
              key={`page-${index}`}
              className="idle__page"
              data-active={index === page ? 'true' : 'false'}
              style={
                // Unmeasured ⇒ NO explicit grid: `index.css` falls back to
                // `auto-fit`, which is the honest "we do not know the box yet".
                plan.measured
                  ? {
                      gridTemplateColumns: `repeat(${plan.columns}, minmax(0, 1fr))`,
                      gridTemplateRows: `repeat(${stagePage.rows}, minmax(0, 1fr))`,
                      gap: `${HOME_STAGE_GAP_PX}px`,
                    }
                  : undefined
              }
            >
              {/* Measured ⇒ EXACT cells, explicit `grid-column`/`grid-row`
                  (Codex-Gegenprüfung §1 — no CSS auto-placement for stage
                  tiles, no `dense`, model and DOM cannot drift apart).
                  Unmeasured ⇒ plain DOM order, `.idle__page`'s `auto-fit`
                  fallback places them — there IS no cell yet to be exact about. */}
              {plan.measured
                ? stagePage.cells.flatMap((cell) => {
                    const id = cell.tile.id;
                    const open = id === openFor;
                    const out: ReactNode[] = [
                      placeTile(cell.tile.node, id, open, cell, editPropsFor(id)),
                    ];
                    if (!isStageWidgetId(id)) return out;
                    if (open) out.push(renderSizer(id, cell));
                    return out;
                  })
                : stagePage.tiles.flatMap((tile) => {
                    const open = tile.id === openFor;
                    const out: ReactNode[] = [
                      placeTile(tile.node, tile.id, open, null, editPropsFor(tile.id)),
                    ];
                    if (!isStageWidgetId(tile.id)) return out;
                    if (open) out.push(renderSizer(tile.id, null));
                    return out;
                  })}
            </div>
          ))}
        </div>
        {/* Dots from page two on (DESIGN §2.2) — on a single page they would be a
            control without a choice. The ROW stays reserved either way, see the
            component KDoc. */}
        {pageCount > 1 && (
          <div className="idle__dots" role="group" aria-label={idleFace.stage.pagesAria}>
            {plan.pages.map((_, index) => (
              <button
                key={`dot-${index}`}
                type="button"
                className="idle__dot"
                aria-current={index === page ? 'true' : undefined}
                aria-label={idleFace.stage.page(index + 1, pageCount)}
                onClick={() => setPage(index)}
              />
            ))}
          </div>
        )}
      </div>
      {/* **Der Edit-Modus trägt gar keine eigene Bedienung mehr** (Andi
          23.08., wörtlich: „nimm die UI oben, wenn man etwas bearbeitet raus,
          dann passt es für mich").

          Die Geschichte dieser Stelle in drei Sätzen: W4 stellte Leiste (oben)
          und Fach (unten) als Geschwister der Bühne in den Fluss — zusammen
          138 px, fast genau eine Kachelzeile, die der Edit-Modus der Bühne
          wegnahm. W7-A hob beide in eine absolut positionierte Schicht, damit
          keine Kachel mehr auf die nächste Seite rutscht. Am 22.08. fiel das
          Fach, am 23.08. die Leiste. Was bleibt, ist die Bühne selbst: ALLE
          drei Ausgänge hingen nie an der Leiste (Tipp auf die gewählte Kachel
          → `onPointerUp`; Tipp ins Leere → derselbe Handler bzw. der
          Fenster-Horcher; Escape → eigener Effekt), und „Zurücksetzen" wohnt
          jetzt dort, wo auch die Widget-Schalter wohnen: Einstellungen →
          Zuhause & Integrationen → Zuhause-Widgets.

          Geblieben ist nur, was NULL Pixel kostet und ohne das der Modus für
          Screenreader stumm wäre: die `aria-live`-Ansage nach einem Zug per
          Tastatur und die Tastatur-Belegung selbst. Beide standen bis eben in
          der Leiste; sie hier verwaisen zu lassen wäre kein Aufräumen,
          sondern der stille Verlust des einzigen Ortes, an dem Pfeiltasten und
          Bild ↑/↓ überhaupt angeboten werden. Die Region steht IMMER im DOM
          (nicht erst, wenn es etwas zu sagen gibt): eine `aria-live`-Region,
          die gleichzeitig mit ihrem Text entsteht, liest kaum ein
          Screenreader vor. */}
      {editing && (
        // `.idle__editsr` trägt selbst die sr-only-Regel (index.css) — nicht
        // erst seine Kinder: die Hülle ist ein Flex-Kind der Bühne, und ein
        // Flex-Kind mit 0 px Höhe kostet trotzdem eine 10-px-Fuge (`gap`).
        // Genau darum liegt sie außerhalb des Flusses, wie ihre Kinder.
        <div className="idle__editsr" role="group" aria-label={editText.title}>
          <p>{editText.keyHint}</p>
          <p role="status" aria-live="polite">
            {announcement}
          </p>
        </div>
      )}
    </div>
  );
}

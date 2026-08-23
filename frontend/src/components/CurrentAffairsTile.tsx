import type { ComponentPropsWithoutRef } from 'react';
import {
  type CurrentAffairsItem,
  type CurrentAffairsState,
} from '../hooks/useCurrentAffairs';
import { formatRelativeAge, type RelativeAgeStrings } from './homeTiles';
import type { HomeTileSize } from './homeWidgets';
import { dueClock } from '../hooks/useScheduledItems';
import { useUiStrings } from '../i18n';
import { SourceBadge } from './SourceBadge';
import { MaximizeButton } from './MaximizeButton';

/**
 * **Lagebild** — the "today" window of the home tab (order F5, wave 1). It is
 * hung into the very same `.idle__tiles` grid as the vacuum/climate tiles and
 * speaks the same design language (`tile idle__tile`, `tile__head`,
 * `.idle__hometile*` line sizes) — no second design system was invented.
 *
 * **The window earns its place, or it is not there** (convention
 * `OpsStatusPillLive`, "Kacheln, die man sich verdient"): `EMPTY`/`UNAVAILABLE`
 * — and equally a still-running first fetch, a switched-off feature, an
 * unreachable endpoint or a live answer without a single usable item — render
 * NOTHING. No empty scaffold, no "currently unavailable" threat display. The
 * only two states that produce markup are `FRESH` and `STALE` WITH items.
 *
 * **Freshness is visible, not hidden:**
 *  - `FRESH` ⇒ the honest `live` pill plus the "Stand HH:MM" line.
 *  - `STALE` ⇒ NO `live` pill (a live badge over old news would be a lie) and
 *    the "Stand" line carries a discreet amber age hint — the same amber idiom
 *    (`var(--warn)`, never `--error`) the vacuum tile and the ops pill use.
 *
 * **The "Stand" line comes from `lastSuccessfulRefreshAt`, never from
 * `observedAt`.** `observedAt` is stamped on every single answer and would
 * therefore show a fresh time on top of week-old headlines. Missing
 * `lastSuccessfulRefreshAt` ⇒ no line at all (we do not know, so we say
 * nothing).
 *
 * **Route ② was an inline "mehr" expansion; it is now the `size` prop
 * instead (W1, DESIGN-widget-raster-2026-08-18 §3.4/§8.3).** The order asked
 * for a `/lagebild` deep link and said to follow the existing router idiom —
 * but this app HAS no router and no deep-link mechanism at all: `App.tsx`
 * holds the active tab in a plain `useState<Tab>`, and nothing in `src/`
 * touches `location.hash`, `history.pushState`, `popstate` or
 * `URLSearchParams`. The toggle button (`.idle__news--open`, "mehr"/
 * "weniger") that used to bridge that gap is gone WITHOUT replacement: the
 * size step now IS the expansion — L shows up to
 * {@link CURRENT_AFFAIRS_EXPANDED_COUNT} headlines with `feedSnippet` and an
 * explicit "Quelle öffnen" action, M shows {@link CURRENT_AFFAIRS_WINDOW_COUNT}
 * compact cards, S shows one. No TopNav entry either way (promotion is earned
 * by usage, not granted up front).
 *
 * **L may never make the PAGE scroll** (live finding 2026-08-15, iPad: "you
 * basically have ONE window that must never scroll"). Two locks, deliberately
 * independent: the item cap above, and a `max-height` + `overflow-y:auto` +
 * `overscroll-behavior:contain` on the L-size list in `index.css`
 * (`.idle__news--big .idle__newslist`) — long headlines/teasers scroll INSIDE
 * the window, and the scroll never chains out to the page. Anything beyond
 * the cap is counted out loud (`restNotShown`) instead of vanishing silently.
 *
 * **XL (W5, §3.4)** is L two-column: the same six real headlines, the same
 * cap, the same rest count — but with room for longer teasers (the L list
 * clamps `feedSnippet` to three lines, XL to six; the field itself is
 * untouched, only how much of it fits). Deliberately NOT ten headlines: the
 * six are Andi's open gate §7.4, and a size step must not answer a question
 * that was asked of him. "L/XL erfindet niemals Inhalt" (§2.3).
 *
 * {@link CurrentAffairsWindow} is pure/prop-driven (no fetch, no timers, no
 * DOM, no internal state any more) → testable via `renderToStaticMarkup` at
 * every size, exactly like `IdleFace`/`VacuumTile`. {@link CurrentAffairsTile}
 * is what `IdleFace` renders — now a thin pass-through, since there is no
 * expand/collapse state left to own.
 */

/** S (§3.4): a single headline, no list at all. */
export const CURRENT_AFFAIRS_S_COUNT = 1;

/** M (§3.4, order: "max. 3 Karten") — the default size, byte-identical to the old collapsed view. */
export const CURRENT_AFFAIRS_WINDOW_COUNT = 3;

/**
 * L (§3.4) — at most this many headlines with snippets (live finding
 * 2026-08-15, iPad: "you basically have ONE window that must never scroll").
 * The window used to render every fetched item — with `CURRENT_AFFAIRS_LIMIT`
 * = 20 plus teasers that pushed the page far past the viewport, and the page
 * is exactly what must not scroll on a wall display. Six is the honest
 * compromise: it fills the capped area on a tablet without a page scrollbar.
 * The full list belongs into a coming overlay, not into this size step —
 * until then the items beyond the cap are NAMED instead of silently dropped
 * (see {@link CurrentAffairsWindow}'s rest line). **XL shares this cap** (W5):
 * it is the same six headlines in two columns, not ten — the number is Andi's
 * open gate §7.4, not a function of area.
 */
export const CURRENT_AFFAIRS_EXPANDED_COUNT = 6;

/** Everything the window needs once it has decided that it may render at all. */
export interface RenderableCurrentAffairs {
  items: CurrentAffairsItem[];
  /** Only ever `FRESH` or `STALE` here — the other two never reach the markup. */
  stale: boolean;
  /** `lastSuccessfulRefreshAt` as epoch ms; `null` ⇒ no "Stand" line. */
  refreshedAtMs: number | null;
}

/**
 * The single gate of this window: `null` means "render nothing". Everything
 * that is not a live `FRESH`/`STALE` answer WITH at least one usable item
 * fails the gate — first fetch running, feature off, endpoint unreachable,
 * `EMPTY`, `UNAVAILABLE`, or a live answer whose items all failed parsing.
 */
export function renderableCurrentAffairs(
  state: CurrentAffairsState | null,
): RenderableCurrentAffairs | null {
  if (state === null || state.kind !== 'live') return null;
  const { freshness, items, lastSuccessfulRefreshAtMs } = state.data;
  if (freshness !== 'FRESH' && freshness !== 'STALE') return null;
  if (items.length === 0) return null;
  return { items, stale: freshness === 'STALE', refreshedAtMs: lastSuccessfulRefreshAtMs };
}

/**
 * **Warum diese Kachel `<article>`-Requisiten durchreichen MUSS** (Andi 22.08.:
 * „bei den nachrichten geht das noch nicht"):
 *
 * `HomeStage.placeTile` hängt per `cloneElement` alles an das Element, das der
 * Kachel-Bauer zurückgibt: `data-widget-id` (der Griff, an dem Long-Press,
 * Stufen-Wähler und Zug die Kachel überhaupt erst finden), den `style` mit
 * `grid-column`/`grid-row` (ihre Zelle) und im Edit-Modus `tabIndex`/`role`/
 * `aria-*`/`onKeyDown`.
 *
 * Sieben der acht Kacheln geben ein rohes `<article>` zurück, bei denen landet
 * das direkt im DOM. Diese hier gab eine KOMPONENTE zurück — dieselben
 * Requisiten kamen also als React-Props an und wurden stillschweigend
 * weggeworfen. Gemessen (`tools/zuhause-probe/flaeche.mjs`, 22.08.): die
 * Nachrichten-Kachel war die einzige der Bühne OHNE `data-widget-id`, in jeder
 * Szene und in beiden Fenstern. Damit fand `sizableWidgetAt`/`widgetAt`
 * (HomeStage.tsx:189/204) sie nie — kein Long-Press, kein Tipp im Edit-Modus,
 * kein `+`/`−`. Sie bekam auch KEINE Zelle: eine M-Kachel (2×1) stand als
 * 1×1-Kachel in der ersten freien Lücke des Auto-Placement (Beweisbild
 * `vorher-klein-1366x1024.png`, Kachel 285 px statt 583 px breit).
 *
 * Die Naht liegt bewusst HIER und nicht in `HomeStage`: die Bühne darf ihre
 * Requisiten an ein Wurzelelement hängen dürfen, ohne wissen zu müssen, ob der
 * Bauer ein Host-Element oder eine Komponente liefert. Wer als Kachel
 * auftreten will, trägt ihre Requisiten.
 */
export interface CurrentAffairsWindowProps
  extends Omit<ComponentPropsWithoutRef<'article'>, 'children' | 'className'> {
  /** Endpoint state; `null` = the first fetch is still running ⇒ nothing renders. */
  state: CurrentAffairsState | null;
  /** Now (epoch ms), injected so the relative times stay testable (idiom `IdleFace`). */
  nowMs: number;
  /**
   * Content density (§3.4). Default `'M'` — a bare test/caller without one
   * behaves like the registry default, byte-identical to the old collapsed
   * view. `'L'` is what "expanded" used to mean (snippets + "Quelle öffnen");
   * `'XL'` is that same list in two columns with longer teasers (W5).
   */
  size?: HomeTileSize;
  /**
   * Öffnet die Vollbild-Ansicht (Andi 23.08.: „ich habe keine möglichkeit diese
   * anzuzeigen oder die nachrichten zu filtern"). **Optional mit Absicht:** die
   * Kachel ist rein/prop-getrieben und in jedem Test einzeln renderbar; ohne
   * diesen Rückruf erscheint der Knopf gar nicht erst, statt einen Ausgang zu
   * zeigen, hinter dem nichts liegt. Das Overlay selbst hängt an `IdleFace` —
   * eine Kachel mit `container-type: size` und `overflow: auto` ist der
   * denkbar schlechteste Elternteil für einen modalen Kasten.
   */
  onMaximize?: () => void;
}

/** One headline card — compact in the window, with snippet + action at L/XL ("big"). */
function HeadlineCard({
  item,
  nowMs,
  expanded,
  age,
  t,
}: {
  item: CurrentAffairsItem;
  nowMs: number;
  expanded: boolean;
  age: RelativeAgeStrings;
  t: ReturnType<typeof useUiStrings>['idleFace']['currentAffairs'];
}) {
  const relative = formatRelativeAge(item.publishedAtMs, nowMs, age);
  return (
    <li className="idle__newsitem">
      {/* The whole headline is the link — a click opens the canonical article in
          a new tab (`rel="noopener"` exactly like the chat source links). */}
      <a
        className="idle__newstitle"
        href={item.canonicalUrl}
        target="_blank"
        rel="noopener"
        aria-label={t.openAria(item.title)}
      >
        {item.title}
      </a>
      {/* Source name comes RAW from the feed (proper noun, never translated);
          only the separator and the relative age are catalogued. */}
      <p className="idle__hometilesub">{t.meta(item.source, relative)}</p>
      {expanded && item.feedSnippet !== null && (
        <p className="idle__newssnippet">{item.feedSnippet}</p>
      )}
      {/* Feed-owner attribution/license note — RAW like `source`, never
          translated. Shown only in the detail expansion (same gate as the
          teaser/"Quelle öffnen" action): the compact card already carries
          the source name, so the full attribution/license text earns its
          place only once someone opens the detail, same secondary-text
          class as the meta line above (no new style invented). A local
          per-provider SourceBadge (Kurs-Update) sits in front of the text —
          keyed by the source id, so an unknown source id falls back to
          text-only automatically (SourceBadge returns null). */}
      {expanded && item.attribution !== null && (
        <p className="idle__hometilesub">
          <SourceBadge sourceId={item.source} /> {item.attribution}
        </p>
      )}
      {expanded && (
        <a className="idle__newsopen" href={item.canonicalUrl} target="_blank" rel="noopener">
          {t.openSource}
        </a>
      )}
    </li>
  );
}

export function CurrentAffairsWindow({
  state,
  nowMs,
  size = 'M',
  onMaximize,
  ...stage
}: CurrentAffairsWindowProps) {
  const { idleFace, locale } = useUiStrings();
  const t = idleFace.currentAffairs;
  // The earned-tile rule: nothing real to show ⇒ nothing at all.
  const view = renderableCurrentAffairs(state);
  if (view === null) return null;

  const { items, stale, refreshedAtMs } = view;
  const cs = size;
  /** L und XL zeigen beide die Detail-Karte (Teaser + „Quelle öffnen"). */
  const big = cs === 'L' || cs === 'XL';
  const xl = cs === 'XL';
  const count =
    cs === 'S' ? CURRENT_AFFAIRS_S_COUNT : cs === 'M' ? CURRENT_AFFAIRS_WINDOW_COUNT : CURRENT_AFFAIRS_EXPANDED_COUNT;
  const shown = items.slice(0, count);
  /** Items beyond the L cap — named honestly instead of silently dropped (L/XL only). */
  const rest = items.length - CURRENT_AFFAIRS_EXPANDED_COUNT;

  return (
    <article
      // Die Bühnen-Requisiten ZUERST, damit die eigenen Zusagen der Kachel
      // (Klassen, `data-status`, `data-freshness`) sie nicht überschreiben
      // können — und damit ein künftiges gleichnamiges Attribut hier auffällt,
      // statt still zu gewinnen.
      {...stage}
      className={`tile idle__tile tile--live idle__news${big ? ' idle__news--big' : ''}${xl ? ' idle__news--xl' : ''}`}
      data-status="live"
      data-freshness={stale ? 'STALE' : 'FRESH'}
    >
      {/* KEINE `live`-Pille mehr (W6, Andi 20.08.: „Das Live kann aus den
          Widgets raus, das gibt uns etwas Platz"). Sie stand ohnehin nur, wenn
          NICHTS zu melden war — der Amber-STALE-Hinweis an der „Stand"-Zeile
          trug schon immer die einzige Aussage, die etwas kostet, und er
          BLEIBT. Die Pille war ihr stummer Gegenpart und damit reine Fläche. */}
      <div className="tile__head">
        <span className="tile__name">{t.name}</span>
        {onMaximize && (
          <MaximizeButton label={idleFace.maximieren.openAria(t.name)} onClick={onMaximize} />
        )}
      </div>

      {/* XL (§3.4): DIESELBEN sechs Meldungen, nur zweispaltig — der Deckel
          bleibt 6, weil er ein Viewport-Riegel ist und kein Platz-Riegel
          (Andis Gate 4 ist unbeantwortet, s. §7.4). Mehr Fläche heißt hier
          also: dieselben Meldungen mit mehr Luft und längeren Teasern, nicht
          mehr Meldungen. Was über dem Deckel liegt, wird weiter gezählt. */}
      <ul className={`idle__newslist${xl ? ' idle__newslist--two' : ''}`}>
        {shown.map((item) => (
          <HeadlineCard
            key={item.id}
            item={item}
            nowMs={nowMs}
            expanded={big}
            age={idleFace.homeTiles.age}
            t={t}
          />
        ))}
      </ul>

      {/* The cap is visible, not hidden: everything beyond
          CURRENT_AFFAIRS_EXPANDED_COUNT is counted out loud instead of quietly
          disappearing behind a scrollbar the page must never grow. */}
      {big && rest > 0 && <p className="idle__newsrest">{t.restNotShown(rest)}</p>}

      {/* "Stand HH:MM" — from lastSuccessfulRefreshAt ONLY (see the file KDoc).
          Unknown ⇒ the line disappears instead of claiming a time. Shown at
          every size — this was never gated by the old expand toggle either. */}
      {refreshedAtMs !== null && (
        <p className="idle__newsstand">
          {t.stand(dueClock(refreshedAtMs, locale))}
          {stale && (
            <span className="idle__newsage">
              {' · '}
              {t.staleHint(formatRelativeAge(refreshedAtMs, nowMs, idleFace.homeTiles.age))}
            </span>
          )}
        </p>
      )}
    </article>
  );
}

/**
 * The window as `IdleFace` renders it — a thin pass-through now that size
 * (not an internal expand/collapse state) decides the content. Kept as a
 * separate export so `IdleFace` has one stable name to render regardless of
 * how this file evolves (Muster `VacuumTile`/`ClimateTile`).
 *
 * Die Bühnen-Requisiten laufen hier MIT durch (s. KDoc von
 * {@link CurrentAffairsWindowProps}) — sonst endete `data-widget-id` in dieser
 * Zwischenstation statt am `<article>`, und die Kachel wäre wieder die einzige
 * ohne Griff.
 */
export function CurrentAffairsTile({
  state,
  nowMs = Date.now(),
  size = 'M',
  ...stage
}: Omit<CurrentAffairsWindowProps, 'nowMs'> & { nowMs?: number }) {
  return <CurrentAffairsWindow state={state} nowMs={nowMs} size={size} {...stage} />;
}

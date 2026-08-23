import { HOME_WIDGETS, type HomeTileSize, type HomeWidgetId } from './homeWidgets';

/**
 * **homeLayout** — the pure page model of the home stage ("Bühne"): given the
 * measured stage box and the configured tile list, it answers exactly two
 * questions — *how many columns/rows fit* and *which tile sits on which page*.
 * Net-free, DOM-free, side-effect-free (pattern `roomsSort.ts`), so the whole
 * composition can be tested without a layout engine; `HomeStage.tsx` only
 * measures and renders what this file decides.
 *
 * `HomeTileSize` itself lives in `./homeWidgets.ts` (the widget registry) —
 * this file only converts a size into cells ({@link sizeToSpan}/
 * {@link effectiveSize}), it does not own the type.
 *
 * **Why a model at all** (DESIGN-widgets-settings-2026-08-15 §2.2, decision 2 —
 * pulled forward from slice S3 after Andi's verdict on S1 at the real iPad,
 * 15.08.: *"so bringt es nichts — die Widgets sind einfach zusammengepresst"*):
 * the S1 stage was a single `1fr` row band with `grid-auto-rows: minmax(0,1fr)`,
 * i.e. it divided whatever height was left by however many rows the tiles
 * happened to need. On an iPad in landscape that is ~165–230 px for up to two
 * rows — every tile got ~85 px and every tile lied about its content. The rule
 * that replaces it:
 *
 *   **A tile is NEVER pressed below {@link HOME_TILE_MIN_HEIGHT_PX}. If the
 *   configured set does not fit, the tail flows onto page 2/3 — one tile less
 *   on the page, never one tile thinner.**
 *
 * Pages are the iOS idiom Andi asked for; they are swiped horizontally
 * (`HomeStage`), the page dots appear from page 2 on, and the document itself
 * still never scrolls (the S1 promise stays intact).
 *
 * **Order is the JSX order** of `IdleFace` — this model never re-sorts and never
 * back-fills a later, smaller tile into a hole left by an earlier, larger one.
 * A stable position is worth more on a hallway display than a dense packing:
 * Andi learns "Läuft is top-left", and that must not change because the vacuum
 * came online.
 *
 * **Forward compatible with S3 sizes (S/M/L).** Every tile already carries an
 * optional `cols`/`rows` span (default 1×1). The packer places real boxes into
 * a real occupancy grid, so the day `size: 'M'` becomes `{cols:2}` and
 * `size: 'L'` becomes `{cols:2, rows:2}`, nothing in here changes — only the
 * caller that builds the list. A span wider/taller than the page is clamped to
 * the page instead of dropping the tile (a tile that exists must be reachable).
 *
 * **S3 sizes are real now** ({@link sizeToSpan}/{@link effectiveSize},
 * DESIGN-widget-raster-2026-08-18 §2.3, corrected by the hand 18.08. evening —
 * see the KDoc on {@link sizeToSpan}): the caller (`HomeStage.tsx`) turns a
 * tile's stored `HomeTileSize` into `cols`/`rows` via these two pure
 * functions BEFORE handing the tile to {@link planHomeStage}. Nothing below —
 * `planHomeStage`/`paginate`/`firstFreeSpot`/`spanOf` — changed for this.
 */

/**
 * Minimum height of ONE stage cell in px — the number that decides how many
 * rows fit, and therefore where a page ends.
 *
 * Calibrated against the real cards, not guessed (DESIGN §2.2 proposed 132 px;
 * the arithmetic below confirms it): `.tile` padding 14+14 and its 1 px border
 * top/bottom = 30; `.tile__head` (name 15 px + pill) ≈ 20; the 6 px `.tile` gap;
 * two content lines of the LOUDEST tile family (`.idle__cardlist li`, 28 px at
 * line-height 1.2 = 33.6 each, 4 px gap) = 71.2. Sum ≈ 127 px — 132 px keeps a
 * few px of air for a taller pill without inviting a third line.
 *
 * Two lines is the deliberate floor: one line is a value without context, three
 * lines is already a comfortable card. A tile that gets less than this is not a
 * smaller tile, it is a broken one.
 */
export const HOME_TILE_MIN_HEIGHT_PX = 132;

/**
 * Minimum width of ONE stage column in px.
 *
 * **This is the number the S1 slice got wrong** and Andi felt as "clock and
 * weather far apart, tiles pressed together": with 280 px the iPad in landscape
 * (1194 px viewport ⇒ 1154 px stage) missed the fourth column by two pixels
 * (4×280 + 3×12 = 1156 > 1154) and spent the extra width the widened `.app`
 * had just won on three fat columns instead of four honest ones.
 *
 * 252 px fixes that with room to spare and, more importantly, produces exactly
 * the three width behaviours the order asks for — see {@link homeStageColumns}.
 * It is a *minimum*, not the rendered width: columns are `minmax(0,1fr)`, so on
 * the iPad in landscape the four columns actually render at ~279 px each — the
 * old 280 px minimum, now reached instead of missed.
 *
 * Mirrored as the `repeat(auto-fit, minmax(252px, 1fr))` fallback in
 * `index.css` (the pre-measurement paint) — `onewindow.test.ts` pins that the
 * two numbers stay equal.
 */
export const HOME_COLUMN_MIN_WIDTH_PX = 252;

/**
 * The stage gap in px — must mirror `gap` of `.idle__page` in `index.css`,
 * because both the column and the row arithmetic subtract it. Pinned by
 * `onewindow.test.ts`.
 */
export const HOME_STAGE_GAP_PX = 12;

/**
 * Hard ceiling of stage columns (DESIGN §2.1: "4 Spalten × N Zeilen"). Beyond
 * four columns a hallway tile stops being readable from three metres away, no
 * matter how wide the window is — extra width goes into wider tiles, not into
 * more of them.
 */
export const HOME_MAX_COLUMNS = 4;

/** One tile as the model sees it: an identity plus its (future) span. */
export interface HomeLayoutTile {
  /** Stable key of the tile (`laeuft`, `einkauf`, `vacuum`, `climate`, `news`). */
  id: string;
  /** Column span in cells, default 1. S3 sizes: S/M = 1/2, L = 2. */
  cols?: number;
  /** Row span in cells, default 1. S3 sizes: S/M = 1, L = 2. */
  rows?: number;
}

/** The measured inner box of the stage in CSS px. */
export interface HomeStageMetrics {
  width: number;
  height: number;
}

/**
 * One tile PLACED on a page — the exact cell {@link firstFreeSpot} decided
 * for it (0-based, row-major), not a hint. Codex-Gegenprüfung 18.08. §1: the
 * packer used to compute this and then discard it, leaving the renderer to
 * fall back on CSS auto-placement — which can silently disagree with the
 * model the moment tiles of different spans are mixed (a later, narrower
 * tile does not necessarily land where CSS's auto-placement cursor is,
 * even though `firstFreeSpot` scanned it into an earlier hole). The caller
 * (`HomeStage.tsx`) now renders EXACTLY this cell via explicit
 * `grid-column`/`grid-row`, so model and DOM cannot drift apart.
 */
export interface HomeLayoutCell<T extends HomeLayoutTile = HomeLayoutTile> {
  tile: T;
  row: number;
  col: number;
  cols: number;
  rows: number;
}

/** One page of the stage — tiles in their original order plus the rows they occupy. */
export interface HomeLayoutPage<T extends HomeLayoutTile = HomeLayoutTile> {
  tiles: T[];
  /**
   * The SAME tiles as {@link tiles}, one {@link HomeLayoutCell} each, in the
   * same order — the placement a renderer must use (see the KDoc above).
   * Empty in the unmeasured state (`HomeStagePlan.measured === false`):
   * there are no columns to place anything into yet, and the CSS `auto-fit`
   * fallback paints instead — see {@link HomeStagePlan.measured}.
   */
  cells: HomeLayoutCell<T>[];
  /**
   * The `1fr` row tracks this page is laid out with (≤ `rowsPerPage`) — and
   * therefore also the row raster a drop is measured against (`dropCellAt` in
   * `HomeStage.tsx`).
   *
   * The two placement modes answer this differently, on purpose:
   *
   *  - **Packed** ({@link paginate}, no stored cells): the rows actually
   *    occupied. The packer fills densely, so a gap only ever exists at the end
   *    of the last page — and three tiles on a tall stage should become three
   *    comfortable rows instead of three thin ones plus empty space.
   *  - **Free grid** ({@link placeByCells}, stored cells): always `rowsPerPage`.
   *    There a gap is a human decision, not leftover, and shrinking the page
   *    around it both stretched every tile on it and made the missing row
   *    impossible to drop into (Andi 23.08. — see the comment at the push site).
   */
  rows: number;
}

/** The complete plan for one stage measurement. */
export interface HomeStagePlan<T extends HomeLayoutTile = HomeLayoutTile> {
  /**
   * `false` ⇒ the stage has not been measured yet (first paint, SSR, jsdom
   * without layout). Then there is exactly ONE page carrying every tile and the
   * caller must NOT write explicit column/row counts — the CSS `auto-fit`
   * fallback takes over, which is the honest "we do not know yet" rendering
   * rather than a guessed pagination that jumps one frame later.
   */
  measured: boolean;
  columns: number;
  rowsPerPage: number;
  pages: HomeLayoutPage<T>[];
}

/** `n` boxes of `min` px with `gap` px between them fit into `available` px. */
const fitCount = (available: number, min: number, gap: number): number =>
  Math.floor((available + gap) / (min + gap));

/**
 * How many columns the stage carries at this width.
 *
 * One rule, no breakpoint table: as many columns of at least
 * {@link HOME_COLUMN_MIN_WIDTH_PX} as fit, capped at {@link HOME_MAX_COLUMNS}.
 * That single line produces the three behaviours the order asks for (widths are
 * STAGE widths; the viewport is 40 px wider because of `.app__main`'s padding):
 *
 * | stage width | viewport | columns |
 * |---|---|---|
 * | < 516 | < 556 | 1 (phone, portrait) |
 * | 516–779 | 556–819 | 2 (narrow window) |
 * | 780–1043 | 820–1083 | 3 (iPad portrait: 794 ⇒ 3 × 257) |
 * | ≥ 1044 | ≥ 1084 | 4 (iPad landscape: 1154 ⇒ 4 × 279) |
 *
 * A width of 0 or less means "not measured" and yields 1 — callers should use
 * {@link planHomeStage}, which treats that case as unmeasured instead.
 */
export function homeStageColumns(width: number): number {
  if (!Number.isFinite(width) || width <= 0) return 1;
  const fits = fitCount(width, HOME_COLUMN_MIN_WIDTH_PX, HOME_STAGE_GAP_PX);
  return Math.max(1, Math.min(HOME_MAX_COLUMNS, fits));
}

/**
 * How many rows of at least {@link HOME_TILE_MIN_HEIGHT_PX} fit into this stage
 * height. Always ≥ 1: a stage too short even for one honest tile still shows
 * one tile (clipped by its own frame) rather than nothing at all — an empty
 * home screen would be a worse lie than a tight one, and the page mechanism
 * cannot rescue a window that has no room for a single card.
 */
export function homeStageRows(height: number): number {
  if (!Number.isFinite(height) || height <= 0) return 1;
  return Math.max(1, fitCount(height, HOME_TILE_MIN_HEIGHT_PX, HOME_STAGE_GAP_PX));
}

/** Span of a tile, clamped into a page of `columns` × `rows` cells. */
const spanOf = (tile: HomeLayoutTile, columns: number, rows: number) => ({
  cols: Math.min(Math.max(1, Math.floor(tile.cols ?? 1)), columns),
  rows: Math.min(Math.max(1, Math.floor(tile.rows ?? 1)), rows),
});

/**
 * Packs tiles into pages of `columns` × `rowsPerPage` cells, first fit,
 * row-major, ORDER PRESERVED: the first tile that does not fit ends the page,
 * even if a later, smaller one still would. See the file KDoc for why stability
 * beats density here.
 */
function paginate<T extends HomeLayoutTile>(
  tiles: readonly T[],
  columns: number,
  rowsPerPage: number,
): HomeLayoutPage<T>[] {
  const pages: HomeLayoutPage<T>[] = [];
  let rest: readonly T[] = tiles;

  while (rest.length > 0) {
    const taken: T[] = [];
    // Placement decided for each taken tile — same order as `taken`, the
    // list a renderer must use verbatim (Codex-Gegenprüfung §1, KDoc on
    // {@link HomeLayoutCell}).
    const cells: HomeLayoutCell<T>[] = [];
    // Occupancy of the current page; `used` tracks the last row actually filled.
    const grid: boolean[][] = Array.from({ length: rowsPerPage }, () =>
      Array.from({ length: columns }, () => false),
    );
    let used = 0;
    let index = 0;

    while (index < rest.length) {
      const tile = rest[index];
      const span = spanOf(tile, columns, rowsPerPage);
      const spot = firstFreeSpot(grid, columns, rowsPerPage, span);
      if (spot === null) break;
      for (let r = spot.row; r < spot.row + span.rows; r += 1) {
        for (let c = spot.col; c < spot.col + span.cols; c += 1) grid[r][c] = true;
      }
      used = Math.max(used, spot.row + span.rows);
      taken.push(tile);
      cells.push({ tile, row: spot.row, col: spot.col, cols: span.cols, rows: span.rows });
      index += 1;
    }

    // Guard against a pathological "nothing fits" (cannot happen: `spanOf`
    // clamps every tile into the page) — bail out instead of looping forever.
    // No placement was computed for this branch, so `cells` stays empty.
    if (taken.length === 0) {
      pages.push({ tiles: [...rest], cells: [], rows: rowsPerPage });
      return pages;
    }

    pages.push({ tiles: taken, cells, rows: Math.max(1, used) });
    rest = rest.slice(index);
  }

  return pages;
}

/** First free `span`-sized box, scanning row-major (top-left first). */
function firstFreeSpot(
  grid: readonly boolean[][],
  columns: number,
  rows: number,
  span: { cols: number; rows: number },
): { row: number; col: number } | null {
  for (let row = 0; row + span.rows <= rows; row += 1) {
    for (let col = 0; col + span.cols <= columns; col += 1) {
      let free = true;
      for (let r = row; r < row + span.rows && free; r += 1) {
        for (let c = col; c < col + span.cols && free; c += 1) {
          if (grid[r][c]) free = false;
        }
      }
      if (free) return { row, col };
    }
  }
  return null;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Das FREIE Raster (W7-B, Andi 21.08.: „Ich möchte, dass ich die Widgets
//  anordnen kann. Das soll nicht automatisch bündig werden, sondern nur, wenn
//  ich es verschiebe.")
//
//  DIES ÜBERSCHREIBT §0.1/§2.4 des Raster-Designs („dicht gepackt", „eine
//  Prioritätsfolge für einen Auto-Packer") — per Andi-Order, nicht per
//  Eigenmächtigkeit. Der Packer oben bleibt trotzdem stehen, denn er hat jetzt
//  eine zweite, kleinere Rolle: er ist die SAAT für eine Spaltenzahl, für die
//  noch nie jemand etwas angeordnet hat.
//
//  Das Modell in einem Satz: **eine Kachel besitzt eine Zelle, nicht einen
//  Listenplatz.** `{col,row}` mit unbegrenztem `row`; die Seiten sind Bänder
//  von je `rowsPerPage` Zeilen, die daraus abgeleitet werden. Verschwindet
//  eine Kachel (Verdien-Regel §1.3), bleibt ihre Zelle RESERVIERT — sie
//  hinterlässt eine Lücke, und wenn sie wiederkommt, steht sie am alten Platz.
//  Kein Nachrücken, kein Umbau.
//
//  Warum je Spaltenzahl eine eigene Wahrheit: dasselbe iPad hat quer vier und
//  hoch drei Spalten. Eine Zelle (3,0) existiert hochkant physisch nicht. Zwei
//  getrennte Anordnungen sind darum keine Ausrede, sondern die einzige
//  ehrliche Form von „bleibt, wo ich es hinlege" — je Orientierung.
// ─────────────────────────────────────────────────────────────────────────────

/** Eine Zelle im freien Raster. 0-basiert; `row` ist SEITENÜBERGREIFEND (Seiten sind Bänder). */
export interface HomeCell {
  col: number;
  row: number;
}

/** Alle gespeicherten Zellen EINER Spaltenzahl: Widget-Id ⇒ Zelle. */
export type HomePlacementMap = Readonly<Record<string, HomeCell>>;

/** Die gespeicherten Zellen aller Spaltenzahlen: `"3"`/`"4"` ⇒ {@link HomePlacementMap}. */
export type HomePlacements = Readonly<Record<string, HomePlacementMap>>;

/**
 * Eine Zelle, die BELEGT ist, ohne dass eine Kachel gerendert wird — der
 * Fußabdruck einer Kachel, die gerade nichts zu sagen hat (Verdien-Regel).
 * Genau das ist „hinterlässt ihre LÜCKE": ohne diese Reservierung würde die
 * nächste heimatlose Kachel das Loch stopfen und die Stille wäre ein Umzug.
 */
export interface HomeReservedCell extends HomeCell {
  cols: number;
  rows: number;
}

/** Was {@link planHomeStage} über das freie Raster wissen muss. */
export interface HomePlanOptions {
  /** Die gespeicherten Zellen der AKTUELLEN Spaltenzahl. Leer/fehlt ⇒ der Packer sät. */
  placements?: HomePlacementMap | null;
  /** Zellen, die belegt bleiben, obwohl niemand darin gerendert wird (s. {@link HomeReservedCell}). */
  reserved?: readonly HomeReservedCell[];
}

/**
 * Die Belegungsfläche des freien Rasters: `columns` breit, nach unten
 * unbegrenzt (Zeilen wachsen bei Bedarf). Ein Kästchen für eine Mechanik, die
 * sonst an vier Stellen halb nachgebaut würde.
 */
class FreeGrid {
  private readonly rows: boolean[][] = [];

  constructor(
    private readonly columns: number,
    private readonly rowsPerPage: number,
  ) {}

  private ensure(row: number): void {
    while (this.rows.length <= row) this.rows.push(new Array<boolean>(this.columns).fill(false));
  }

  /**
   * Passt dieser Fußabdruck hierhin? Neben „ist frei" gilt eine zweite
   * Bedingung: er darf keine SEITENGRENZE überschreiten. Eine 2 Zeilen hohe
   * Kachel, die halb auf Seite 1 und halb auf Seite 2 läge, wäre auf beiden
   * halbiert — das Modell schiebt sie stattdessen ganz auf die nächste Seite.
   */
  free(col: number, row: number, cols: number, rows: number): boolean {
    if (col < 0 || row < 0 || col + cols > this.columns) return false;
    if (Math.floor(row / this.rowsPerPage) !== Math.floor((row + rows - 1) / this.rowsPerPage)) {
      return false;
    }
    this.ensure(row + rows - 1);
    for (let r = row; r < row + rows; r += 1) {
      for (let c = col; c < col + cols; c += 1) if (this.rows[r][c]) return false;
    }
    return true;
  }

  mark(col: number, row: number, cols: number, rows: number): void {
    this.ensure(row + rows - 1);
    for (let r = row; r < row + rows; r += 1) {
      for (let c = col; c < col + cols; c += 1) {
        if (c >= 0 && c < this.columns) this.rows[r][c] = true;
      }
    }
  }

  /**
   * Der nächste freie Platz, row-major von ganz oben — DERSELBE first fit, den
   * {@link firstFreeSpot} innerhalb einer Seite fährt, nur ohne Seitenboden.
   * Er läuft für genau zwei Fälle: eine Kachel, die noch nie eine Zelle hatte,
   * und eine, deren Zelle gerade belegt ist (weil sie gewachsen ist). Nie für
   * eine, die einfach nur dasteht.
   */
  firstFit(cols: number, rows: number, limit: number, from = 0): HomeCell {
    for (let row = Math.max(0, from); row <= limit; row += 1) {
      for (let col = 0; col + cols <= this.columns; col += 1) {
        if (this.free(col, row, cols, rows)) return { col, row };
      }
    }
    // Unerreichbar: `limit` ist großzügiger als die Summe aller Fußabdrücke.
    return { col: 0, row: limit + 1 };
  }
}

/** Wie weit `firstFit` höchstens suchen muss — großzügig, aber endlich. */
const fitLimit = (count: number, rowsPerPage: number) => (count + 2) * (rowsPerPage + 1);

/**
 * Kacheln auf ihre GESPEICHERTEN Zellen setzen (statt sie dicht zu packen).
 *
 * Die Reihenfolge der Vergabe entscheidet, wer bei einem Konflikt gewinnt, und
 * ist darum festgeschrieben:
 *  1. **Reservierte Zellen zuerst** — die Lücken der stillen Kacheln stehen,
 *     bevor irgendwer etwas sucht.
 *  2. **Kacheln MIT Zelle**, in Lesereihenfolge ihrer gespeicherten Zelle. Wer
 *     weiter oben/links gespeichert ist, bekommt seinen Platz zuerst; ein
 *     Konflikt trifft immer den, der später dran ist.
 *  3. **Kacheln OHNE Zelle**, in der übergebenen Reihenfolge, per first fit —
 *     das ist die Saat für eine neue Kachel (und, beim ersten Mal, für alle).
 *
 * Eine Kachel, deren gespeicherte Zelle nicht mehr passt (Spalte zu weit
 * rechts nach dem Drehen, Fußabdruck gewachsen, Seitengrenze im Weg),
 * bekommt den nächsten freien Platz — **nur sie**, niemand sonst rückt.
 */
function placeByCells<T extends HomeLayoutTile>(
  tiles: readonly T[],
  columns: number,
  rowsPerPage: number,
  placements: HomePlacementMap,
  reserved: readonly HomeReservedCell[],
): HomeLayoutPage<T>[] {
  const grid = new FreeGrid(columns, rowsPerPage);
  const limit = fitLimit(tiles.length + reserved.length, rowsPerPage);
  /** Die unterste Zeile, in der wirklich eine KACHEL liegt (Lücken zählen nicht). */
  let bottom = 0;

  for (const cell of reserved) {
    const cols = Math.min(Math.max(1, Math.floor(cell.cols)), columns);
    const rows = Math.min(Math.max(1, Math.floor(cell.rows)), rowsPerPage);
    const col = Math.min(Math.max(0, Math.floor(cell.col)), Math.max(0, columns - cols));
    const row = Math.max(0, Math.floor(cell.row));
    grid.mark(col, row, cols, rows);
  }

  const known: { tile: T; span: { cols: number; rows: number }; want: HomeCell }[] = [];
  const unknown: { tile: T; span: { cols: number; rows: number } }[] = [];
  for (const tile of tiles) {
    const span = spanOf(tile, columns, rowsPerPage);
    const want = placements[tile.id];
    if (want) known.push({ tile, span, want });
    else unknown.push({ tile, span });
  }
  known.sort((a, b) => a.want.row - b.want.row || a.want.col - b.want.col);

  const placed: { tile: T; cell: HomeCell; span: { cols: number; rows: number } }[] = [];
  const put = (tile: T, span: { cols: number; rows: number }, cell: HomeCell) => {
    grid.mark(cell.col, cell.row, span.cols, span.rows);
    bottom = Math.max(bottom, cell.row + span.rows);
    placed.push({ tile, cell, span });
  };
  for (const k of known) {
    const col = Math.min(Math.max(0, Math.floor(k.want.col)), Math.max(0, columns - k.span.cols));
    const row = Math.max(0, Math.floor(k.want.row));
    const fits = grid.free(col, row, k.span.cols, k.span.rows);
    put(k.tile, k.span, fits ? { col, row } : grid.firstFit(k.span.cols, k.span.rows, limit));
  }
  /**
   * Kacheln OHNE gespeicherte Zelle kommen HINTER alles Bekannte, nicht in die
   * erste Lücke. Im freien Raster ist eine Lücke kein freier Platz, sondern
   * ein aufgeräumtes Stück Bühne — wer keine Zelle hat, drängelt sich da nicht
   * hinein. (Die Bühnen-Widgets sind ohnehin alle gesät; hier landen praktisch
   * nur Ids, die das Layout gar nicht kennt — und für die galt schon immer
   * „behält ihre Position am Ende", s. `HomeStage`-KDoc.)
   */
  const tail = [
    ...reserved.map((c) => Math.max(0, Math.floor(c.row)) + Math.max(1, Math.floor(c.rows))),
    ...placed.map((p) => p.cell.row + p.span.rows),
    0,
  ].reduce((max, v) => Math.max(max, v), 0);
  for (const u of unknown) {
    put(u.tile, u.span, grid.firstFit(u.span.cols, u.span.rows, limit, tail));
  }

  placed.sort((a, b) => a.cell.row - b.cell.row || a.cell.col - b.cell.col);

  /**
   * **Eine Seite ohne Kachel ist keine Seite.** Die letzte Seite folgt aus den
   * echten Kacheln, nicht aus den Lücken: läge die Zelle einer stillen Kachel
   * hinter der letzten belegten Seite, entstünde sonst ein Seitenpunkt, hinter
   * dem NICHTS steht — ein Bedienelement, das ins Leere führt. Die Lücke ist
   * damit nicht vergessen (die Zelle steht weiter im Speicher); sie wird nur
   * erst wieder gezeigt, wenn die Kachel wiederkommt und die Seite mitbringt.
   */
  const lastPage = Math.max(0, Math.ceil(Math.max(bottom, 1) / rowsPerPage) - 1);
  const pages: HomeLayoutPage<T>[] = [];
  for (let index = 0; index <= lastPage; index += 1) {
    const on = placed.filter((p) => Math.floor(p.cell.row / rowsPerPage) === index);
    const cells = on.map((p) => ({
      tile: p.tile,
      row: p.cell.row - index * rowsPerPage,
      col: p.cell.col,
      cols: p.span.cols,
      rows: p.span.rows,
    }));
    /**
     * **Eine Seite des freien Rasters ist IMMER `rowsPerPage` Zeilen hoch** —
     * auch wenn die unterste Zeile leer bleibt (Andi-Livetest 23.08., wörtlich:
     * „die uhr wird über die komplette höhe einer seite angezeigt. ich kann kein
     * widgent auf die linke seite der ersten seite verschieben").
     *
     * Bis hierher stand hier die belegte Zeilenzahl (`used`, gedeckelt auf
     * `rowsPerPage`), geerbt vom Packer oben — und für den Packer ist sie auch
     * richtig: der packt dicht, eine Lücke gibt es dort nur am Ende der letzten
     * Seite, und drei Kacheln auf einer hohen Bühne sollen drei bequeme Zeilen
     * bekommen statt drei dünner plus leerer Fläche.
     *
     * Im freien Raster ist dieselbe Zahl falsch, und zwar doppelt. Gemessen
     * (`tools/zuhause-probe/zellen.mjs`, Saat `luecke`, 1366×900, beide
     * Engines): Bühne trägt 3 × 3 Zellen, die Uhr liegt bei (0,0) als 2×2, zwei
     * kleine Kacheln rechts daneben — die unterste Zeile gehört niemandem.
     * `used` war 2, die Seite zeichnete **2 von 3 Zeilen**:
     *
     *  1. Jede Kachel wuchs um die Hälfte mit. Die Uhr-L wurde **583 × 525 px**
     *     statt 583 × 346 — die volle Seitenhöhe, genau Andis erster Satz.
     *  2. Die dritte Zeile war nicht nur unsichtbar, sondern **unerreichbar**:
     *     `dropCellAt` (HomeStage) teilt die Seitenhöhe durch die GEZEICHNETEN
     *     Zeilen und klemmt auf `rows - 1`. Die freien Zellen (0,2)/(1,2)/(2,2)
     *     konnte kein Finger je meinen — und die zwei linken davon waren die
     *     einzigen freien Zellen der linken Seitenhälfte. Genau Andis zweiter
     *     Satz; der Zug landete gemessen wieder auf der Ausgangszelle.
     *
     * Eine Lücke, die ein Mensch selbst gelassen hat, ist kein Grund, die Bühne
     * umzurechnen — sie ist der Platz, auf den er als Nächstes etwas legen will.
     * Damit erledigt sich auch die Sorge der alten Fassung von selbst: eine
     * still gewordene Kachel kann die Zeilenzahl gar nicht mehr verändern, ihre
     * Reservierung muss dafür nichts mehr beitragen.
     */
    pages.push({ tiles: on.map((p) => p.tile), cells, rows: Math.max(1, rowsPerPage) });
  }
  return pages;
}

/**
 * The whole plan: measure ⇒ columns, rows, pages.
 *
 * `metrics === null` (not measured yet) is a first-class state, not an error:
 * one page with everything on it, `measured: false`, and the caller leaves the
 * grid to the CSS `auto-fit` fallback. `IdleFace` renders the same tiles either
 * way, so an SSR/`renderToStaticMarkup` render still contains every tile —
 * which is what the existing home tests assert.
 *
 * **W7-B:** gibt der Aufrufer gespeicherte Zellen mit, wird nicht mehr gepackt,
 * sondern PLATZIERT ({@link placeByCells}). Ohne sie bleibt alles beim Alten —
 * derselbe Packer, dieselben Seiten, dieselben Zellen; das ist die Saat.
 */
export function planHomeStage<T extends HomeLayoutTile>(
  tiles: readonly T[],
  metrics: HomeStageMetrics | null,
  options?: HomePlanOptions,
): HomeStagePlan<T> {
  if (tiles.length === 0) {
    return { measured: metrics !== null, columns: 0, rowsPerPage: 0, pages: [] };
  }
  if (metrics === null || metrics.width <= 0 || metrics.height <= 0) {
    return {
      measured: false,
      columns: 0,
      rowsPerPage: 0,
      pages: [{ tiles: [...tiles], cells: [], rows: 0 }],
    };
  }
  const columns = homeStageColumns(metrics.width);
  const rowsPerPage = homeStageRows(metrics.height);
  const placements = options?.placements ?? null;
  const pages =
    placements && Object.keys(placements).length > 0
      ? placeByCells(tiles, columns, rowsPerPage, placements, options?.reserved ?? [])
      : paginate(tiles, columns, rowsPerPage);
  return { measured: true, columns, rowsPerPage, pages };
}

/**
 * Die Zellen, die dieser Plan gerade zeigt — die SAAT, die man einfrieren
 * kann. `row` ist seitenübergreifend: Seite 2, Zeile 0 auf einer 2-Zeilen-Bühne
 * ist Zeile 2.
 */
export function homePlanPlacements(plan: HomeStagePlan): HomePlacementMap {
  const out: Record<string, HomeCell> = {};
  if (!plan.measured) return out;
  plan.pages.forEach((page, index) => {
    for (const cell of page.cells) {
      out[cell.tile.id] = { col: cell.col, row: cell.row + index * plan.rowsPerPage };
    }
  });
  return out;
}

/**
 * **Der Nutzer-Zug** — die einzige Bewegung, die Zellen neu vergibt.
 *
 * Die gezogene Kachel bekommt die Zielzelle. Was dann mit dem passiert, was
 * dort schon lag, ist die eigentliche Entscheidung dieses Pods:
 *
 *  - **Genau eine Kachel im Weg, gleiche Größe, und die gezogene hatte selbst
 *    eine Zelle ⇒ TAUSCH.** Das ist der Normalfall und die einzige Antwort,
 *    die keine dritte Kachel anfasst. „Einfügen und alle nachschieben" wäre
 *    genau das Auto-Bündig, das Andi abbestellt hat.
 *  - **Sonst ⇒ die Verdrängten suchen sich (und nur sie) den nächsten freien
 *    Platz.** Mehrere Betroffene oder ungleiche Fußabdrücke lassen sich nicht
 *    tauschen, ohne sich zu überlappen.
 *
 * Rein: kein Speicher, kein DOM. `spans` liefert die Fußabdrücke, die der
 * Aufrufer ohnehin schon gerechnet hat.
 */
export function moveHomePlacement(
  placements: HomePlacementMap,
  id: string,
  target: HomeCell,
  spans: Readonly<Record<string, { cols: number; rows: number }>>,
  geometry: { columns: number; rowsPerPage: number },
): HomePlacementMap {
  const columns = Math.max(1, Math.floor(geometry.columns));
  const rowsPerPage = Math.max(1, Math.floor(geometry.rowsPerPage));
  const spanOfId = (key: string) => {
    const raw = spans[key] ?? { cols: 1, rows: 1 };
    return {
      cols: Math.min(Math.max(1, Math.floor(raw.cols)), columns),
      rows: Math.min(Math.max(1, Math.floor(raw.rows)), rowsPerPage),
    };
  };
  const span = spanOfId(id);
  const col = Math.min(Math.max(0, Math.floor(target.col)), Math.max(0, columns - span.cols));
  let row = Math.max(0, Math.floor(target.row));
  // Eine Kachel, die zwei Zeilen braucht, darf nicht über die Seitengrenze
  // gelegt werden — sie rutscht auf den Anfang der nächsten Seite.
  if (Math.floor(row / rowsPerPage) !== Math.floor((row + span.rows - 1) / rowsPerPage)) {
    row = (Math.floor(row / rowsPerPage) + 1) * rowsPerPage;
  }
  const from = placements[id];

  const overlaps = (a: { cell: HomeCell; cols: number; rows: number }, b: { cell: HomeCell; cols: number; rows: number }) =>
    a.cell.col < b.cell.col + b.cols &&
    b.cell.col < a.cell.col + a.cols &&
    a.cell.row < b.cell.row + b.rows &&
    b.cell.row < a.cell.row + a.rows;

  const moved = { cell: { col, row }, ...span };
  const others = Object.keys(placements)
    .filter((key) => key !== id)
    .map((key) => ({ id: key, cell: placements[key], ...spanOfId(key) }))
    .sort((a, b) => a.cell.row - b.cell.row || a.cell.col - b.cell.col);
  const hit = others.filter((o) => overlaps(moved, o));

  const next: Record<string, HomeCell> = {};
  for (const o of others) next[o.id] = o.cell;
  next[id] = { col, row };

  if (hit.length === 0) return next;
  if (hit.length === 1 && from && hit[0].cols === span.cols && hit[0].rows === span.rows) {
    next[hit[0].id] = { col: from.col, row: from.row };
    return next;
  }

  // Der allgemeine Fall: alles Unbeteiligte bleibt stehen, die Verdrängten
  // suchen sich der Reihe nach den nächsten freien Platz.
  const grid = new FreeGrid(columns, rowsPerPage);
  grid.mark(col, row, span.cols, span.rows);
  const displaced = new Set(hit.map((h) => h.id));
  for (const o of others) {
    if (displaced.has(o.id)) continue;
    grid.mark(o.cell.col, o.cell.row, o.cols, o.rows);
  }
  const limit = fitLimit(others.length + 1, rowsPerPage);
  for (const h of hit) {
    const cell = grid.firstFit(h.cols, h.rows, limit);
    grid.mark(cell.col, cell.row, h.cols, h.rows);
    next[h.id] = cell;
  }
  return next;
}

/**
 * Stored size → cell span, given the CURRENT column count (DESIGN
 * §5.1/§2.3, **corrected by the hand 18.08. evening**: the overview carries
 * the same 920px width cap as every other tab since `d4ecb43`, so the stage
 * never reaches {@link HOME_MAX_COLUMNS} — a literal "XL = 4×2" would be
 * unreachable. XL therefore means **"full stage width × 2 rows"**, not "4
 * cells wide": at 3 columns it is 3×2, at 4 columns 4×2. S/M/L are plain
 * constants; only XL depends on [columns].
 *
 * This never has to guard against an XL span narrower than L's 2 columns —
 * {@link effectiveSize} already degrades XL to L (or S) before a caller gets
 * here, and `spanOf` clamps into the page regardless.
 */
export function sizeToSpan(size: HomeTileSize, columns: number): { cols: number; rows: number } {
  switch (size) {
    case 'S':
      return { cols: 1, rows: 1 };
    case 'M':
      return { cols: 2, rows: 1 };
    case 'L':
      return { cols: 2, rows: 2 };
    case 'XL':
      return { cols: Math.max(1, columns), rows: 2 };
  }
}

/**
 * The size a tile actually renders at, given the CURRENT column count AND row
 * count — the stored size is never overwritten (DESIGN §0.4): turn the phone
 * sideways again and an XL tile is XL again. Column degradation table (§2.3,
 * corrected):
 *
 * | stored | 4 col | 3 col | 2 col | 1 col |
 * |---|---|---|---|---|
 * | XL | XL | XL | L | S |
 * | L  | L  | L  | L | S |
 * | M  | M  | M  | M | S |
 * | S  | S  | S  | S | S |
 *
 * One column can only ever honestly show a 1×1 cell (no "1×2 hochkant", §2.3)
 * — everything but S degrades all the way down, not just one step. XL keeps
 * its own row (4 col vs. 3 col both stay XL) only because the correction
 * changed what XL *means*, not because the two columns counts differ here.
 *
 * **Row clamp (Kurskorrektur 18.08., Codex-Gegenprüfung §1/§4):** `spanOf`
 * clamps a tile's `rows` into `rowsPerPage` too, not just its `cols` into
 * `columns` — a one-row stage (`rowsPerPage < 2`) physically cannot show an
 * L/XL cell (both need 2 rows), no matter how many columns there are. Without
 * this, the CSS cell would silently shrink to one row while `weatherTileBody`
 * et al. kept rendering the two-row-worth of L/XL content into it — "XL/L
 * bleibt XL/L, nur eben zusammengepresst", exactly the S1 bug this whole
 * model exists to prevent (file KDoc). So the row check runs AFTER the column
 * table above and degrades L/XL one more step, to M (2×1 — the widest size
 * that only ever needs one row); S never needed a second row to begin with.
 */
export function effectiveSize(stored: HomeTileSize, columns: number, rowsPerPage: number): HomeTileSize {
  if (columns <= 1) return 'S';
  const columnSize = stored === 'XL' ? (columns >= 3 ? 'XL' : 'L') : stored;
  if (rowsPerPage < 2 && (columnSize === 'L' || columnSize === 'XL')) return 'M';
  return columnSize;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Das gespeicherte Layout (W3, DESIGN-widget-raster-2026-08-18 §5)
//
//  Gespeichert wird eine REIHENFOLGE, kein Bild: `{version:1, order:[{id,size}]}`.
//  Keine `col`/`row`, keine `pages` — dasselbe iPad hat quer 4 und hoch 3
//  Spalten, Koordinaten gälten für genau eine davon (§0.1/§8.1). Seiten und
//  Zellen rechnet `planHomeStage` oben, jedes Mal neu.
//
//  Produktzusage dazu (Bus 20260818-to-codex-raster-entscheid §2, nach Codex'
//  Gegenprüfung §2): das ist eine Prioritätsfolge für einen Auto-Packer, keine
//  freie Zellplatzierung — die UI-Copy sagt das auch so ("Reihenfolge
//  festlegen, Hoshi packt passend zur Bildschirmbreite").
//
//  ALLES hier ist rein: Parsen/Normalisieren kennt kein localStorage. Die
//  Speicher-Seite ist `hooks/useHomeLayout.ts`, damit die Härtungsfälle
//  (Doppel-Id, ungültige Stufe, unbekannte Version) ohne DOM testbar bleiben.
// ─────────────────────────────────────────────────────────────────────────────

/** Ein Eintrag der gespeicherten Reihenfolge: WELCHES Widget in WELCHER Stufe. */
export interface HomeLayoutEntry {
  id: HomeWidgetId;
  size: HomeTileSize;
}

/**
 * Das gespeicherte Layout, Version 1 (§5.1) — seit W7 mit {@link placements}.
 *
 * **Additiv, kein Version-Bump.** Eine Datei ohne `placements` ist eine
 * gültige Datei: sie sagt „für diese Spaltenzahl hat noch nie jemand etwas
 * angeordnet", und dann sät der Packer. Ein Bump hätte jedes bestehende Gerät
 * auf {@link DEFAULT_HOME_LAYOUT} zurückgesetzt (§5.3: eine unbekannte Version
 * wird NICHT geraten) — also hätte die Neuerung Andis Stufen weggeworfen, um
 * ihm Zellen zu geben. Niemand verliert etwas.
 */
export interface HomeLayoutV1 {
  version: 1;
  order: readonly HomeLayoutEntry[];
  /**
   * Die Zellen je Spaltenzahl (`"1"`…`"4"` ⇒ Id ⇒ `{col,row}`) — W7-B.
   * Fehlt oder ist leer ⇒ weggelassen, damit eine unangetastete Anordnung
   * exakt denselben JSON-Text schreibt wie vor W7.
   */
  placements?: HomePlacements;
}

/** Die Bühnen-Widgets in Registry-Reihenfolge — die Krone hat kein Layout (§0.3). */
const STAGE_WIDGETS = HOME_WIDGETS.filter((w) => w.rank === 'stage');

/** Ist das eine Id, die überhaupt auf der Bühne liegen kann? (Krone/Fremdes ⇒ nein.) */
export function isStageWidgetId(id: string): id is HomeWidgetId {
  return STAGE_WIDGETS.some((w) => w.id === id);
}

/**
 * Das Layout, das den HEUTIGEN Zustand reproduziert (§5.3):
 * `wetter L · laeuft L · einkauf M · vacuum L · climate L · news M`.
 *
 * Nicht getippt, sondern aus der Registry abgeleitet — sonst gäbe es zwei
 * Wahrheiten über die Default-Größe, und ein neues Widget müsste an zwei
 * Stellen eingetragen werden. Eingefroren, weil {@link normalizeHomeLayout}
 * ihn im Default-Fall unverändert zurückgibt: ein Aufrufer, der darauf
 * schreibt, würde sonst den Default aller künftigen Leser verbiegen.
 */
export const DEFAULT_HOME_LAYOUT: HomeLayoutV1 = Object.freeze({
  version: 1 as const,
  order: Object.freeze(
    STAGE_WIDGETS.map((w) => Object.freeze({ id: w.id, size: w.defaultSize ?? 'M' })),
  ),
});

/** Die erlaubten Stufen dieses Widgets — leer/unbekannt ⇒ nichts ist erlaubt. */
function allowedSizes(id: HomeWidgetId): readonly HomeTileSize[] {
  return HOME_WIDGETS.find((w) => w.id === id)?.sizes ?? [];
}

/** Default-Stufe dieses Widgets (Registry); Krone/unbekannt ⇒ `'M'` als letzte Reißleine. */
function defaultSizeOf(id: HomeWidgetId): HomeTileSize {
  return HOME_WIDGETS.find((w) => w.id === id)?.defaultSize ?? 'M';
}

/**
 * Rohwert ⇒ gültiges Layout. **Idempotent** (`normalize(normalize(x))` ist
 * `normalize(x)`) und total: es gibt keinen Eingabewert, für den diese
 * Funktion wirft.
 *
 * Die Härtungsregeln (Codex-Gegenprüfung §5 „Persistenz-Härtung", alle
 * getestet in `test/homelayoutstore.test.ts`):
 *  - kein Objekt / `version !== 1` / `order` kein Array ⇒ {@link DEFAULT_HOME_LAYOUT}.
 *    Eine unbekannte Version ist KEIN Migrationsfall, sondern eine Datei aus
 *    einer Zukunft, die dieser Code nicht kennt — sie zu raten wäre schlimmer
 *    als der Default (§5.3).
 *  - unbekannte / Krone- / doppelte Id ⇒ **ignoriert** (der ERSTE Treffer
 *    gewinnt, damit die Normalisierung nicht von der Reihenfolge der
 *    Duplikate abhängt).
 *  - ungültige Stufe, oder eine Stufe, die dieses Widget gar nicht kann
 *    (`XL` beim Sauger, §1.1) ⇒ die Default-Stufe des Widgets. Nicht die
 *    nächstkleinere: „so groß wie vorgesehen" ist die ehrlichere Antwort auf
 *    einen kaputten Wert als eine ausgedachte Nachbarstufe.
 *  - ein Widget aus der Registry, das FEHLT, wird hinten mit seiner
 *    Default-Stufe angehängt — so erscheinen neue Widgets ohne je eine
 *    Migration zu schreiben (§5.3 „Vorwärts-Migration ohne Version-Bump").
 */
export function normalizeHomeLayout(value: unknown): HomeLayoutV1 {
  if (typeof value !== 'object' || value === null) return DEFAULT_HOME_LAYOUT;
  const raw = value as { version?: unknown; order?: unknown };
  if (raw.version !== 1 || !Array.isArray(raw.order)) return DEFAULT_HOME_LAYOUT;

  const order: HomeLayoutEntry[] = [];
  const seen = new Set<HomeWidgetId>();
  for (const item of raw.order) {
    if (typeof item !== 'object' || item === null) continue;
    const entry = item as { id?: unknown; size?: unknown };
    if (typeof entry.id !== 'string' || !isStageWidgetId(entry.id)) continue;
    if (seen.has(entry.id)) continue;
    seen.add(entry.id);
    const allowed = allowedSizes(entry.id);
    const size =
      typeof entry.size === 'string' && (allowed as readonly string[]).includes(entry.size)
        ? (entry.size as HomeTileSize)
        : defaultSizeOf(entry.id);
    order.push({ id: entry.id, size });
  }
  for (const widget of STAGE_WIDGETS) {
    if (!seen.has(widget.id)) order.push({ id: widget.id, size: defaultSizeOf(widget.id) });
  }
  const placements = normalizePlacements((value as { placements?: unknown }).placements);
  return placements ? { version: 1, order, placements } : { version: 1, order };
}

/**
 * Rohwert ⇒ gültige Zellen, oder `undefined` (dann steht das Feld gar nicht
 * erst in der Datei). Dieselbe Härte wie {@link normalizeHomeLayout}, weil
 * dieselben Wege hierher führen — Handbearbeitung, halb geschriebene Datei,
 * eine ältere/neuere Hoshi-Version:
 *
 *  - Spaltenschlüssel muss `"1"`…`"{@link HOME_MAX_COLUMNS}"` sein. Ein Layout
 *    für sieben Spalten kann es nicht geben, also wird es auch nicht behalten.
 *  - Id muss ein Bühnen-Widget sein (Krone/Fremdes ⇒ weg).
 *  - `col`/`row` müssen endliche, nicht-negative ganze Zahlen sein; `col` muss
 *    in die Spaltenzahl passen. Alles andere wird VERWORFEN, nicht gerundet —
 *    eine geratene Zelle wäre ein Umzug, den niemand bestellt hat, und die
 *    Kachel bekommt ohne Zelle einfach den nächsten freien Platz.
 */
function normalizePlacements(value: unknown): HomePlacements | undefined {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return undefined;
  const out: Record<string, Record<string, HomeCell>> = {};
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    const columns = Number(key);
    if (!Number.isInteger(columns) || columns < 1 || columns > HOME_MAX_COLUMNS) continue;
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) continue;
    const cells: Record<string, HomeCell> = {};
    for (const [id, cell] of Object.entries(raw as Record<string, unknown>)) {
      if (!isStageWidgetId(id)) continue;
      if (typeof cell !== 'object' || cell === null) continue;
      const { col, row } = cell as { col?: unknown; row?: unknown };
      if (!Number.isInteger(col) || !Number.isInteger(row)) continue;
      if ((col as number) < 0 || (row as number) < 0 || (col as number) >= columns) continue;
      cells[id] = { col: col as number, row: row as number };
    }
    if (Object.keys(cells).length > 0) out[String(columns)] = cells;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/** Die gespeicherten Zellen DIESER Spaltenzahl — `null`, solange es keine gibt. */
export function homePlacementsFor(layout: HomeLayoutV1, columns: number): HomePlacementMap | null {
  const map = layout.placements?.[String(columns)];
  return map && Object.keys(map).length > 0 ? map : null;
}

/**
 * Dasselbe Layout, aber die Zellen DIESER Spaltenzahl sind neu — alle anderen
 * Spaltenzahlen bleiben unangetastet. Genau das ist „quer und hoch sind zwei
 * Anordnungen": ein Zug am Querformat fasst das Hochformat nicht an.
 */
export function withHomePlacements(
  layout: HomeLayoutV1,
  columns: number,
  cells: HomePlacementMap,
): HomeLayoutV1 {
  if (!Number.isInteger(columns) || columns < 1 || columns > HOME_MAX_COLUMNS) {
    return normalizeHomeLayout(layout);
  }
  return normalizeHomeLayout({
    version: 1,
    order: layout.order,
    placements: { ...(layout.placements ?? {}), [String(columns)]: cells },
  });
}

/** JSON-Text ⇒ gültiges Layout. Kein Text, kaputtes JSON, Müll ⇒ {@link DEFAULT_HOME_LAYOUT}. */
export function parseHomeLayout(raw: string | null | undefined): HomeLayoutV1 {
  if (typeof raw !== 'string' || raw.length === 0) return DEFAULT_HOME_LAYOUT;
  try {
    return normalizeHomeLayout(JSON.parse(raw) as unknown);
  } catch {
    return DEFAULT_HOME_LAYOUT;
  }
}

/** Layout ⇒ JSON-Text. Immer die NORMALISIERTE Form — was hier rausgeht, kommt genauso wieder rein. */
export function serializeHomeLayout(layout: HomeLayoutV1): string {
  return JSON.stringify(normalizeHomeLayout(layout));
}

/**
 * Die Reihenfolge als Nachschlagewerk: Id ⇒ Platz + gespeicherte Stufe. Der
 * Weg, auf dem `HomeStage` eine Kachel-Liste sortiert und ihre Stufe setzt,
 * ohne bei sechs Kacheln sechsmal linear zu suchen.
 */
export function homeLayoutIndex(
  layout: HomeLayoutV1,
): Map<HomeWidgetId, { index: number; size: HomeTileSize }> {
  const map = new Map<HomeWidgetId, { index: number; size: HomeTileSize }>();
  layout.order.forEach((entry, index) => map.set(entry.id, { index, size: entry.size }));
  return map;
}

/**
 * Dieselbe Reihenfolge, EIN Eintrag in neuer Stufe (rein — der Speicher ist
 * der Hook). Eine Stufe, die dieses Widget nicht kann, ändert **nichts**: das
 * ist kein kaputter gespeicherter Wert (den {@link normalizeHomeLayout} auf
 * den Default zieht), sondern ein Aufruf, den es nicht geben darf — die
 * unerlaubte Stufe steht im Wähler gar nicht erst zur Wahl (§4.2). Sie
 * stattdessen auf den Default zu ziehen würde einen Fehlgriff in eine
 * sichtbare, ungewollte Änderung verwandeln.
 */
export function withHomeTileSize(
  layout: HomeLayoutV1,
  id: HomeWidgetId,
  size: HomeTileSize,
): HomeLayoutV1 {
  if (!(allowedSizes(id) as readonly string[]).includes(size)) return normalizeHomeLayout(layout);
  // Die Zellen reisen unverändert mit: eine Stufe zu ändern ist kein Umzug.
  // Passt der neue Fußabdruck an der alten Zelle nicht mehr, findet
  // `placeByCells` beim Rendern den nächsten freien Platz — für DIESE Kachel.
  return normalizeHomeLayout({
    version: 1,
    order: layout.order.map((entry) => (entry.id === id ? { id, size } : entry)),
    placements: layout.placements,
  });
}

// ─────────────────────────────────────────────────────────────────────────────
//  Verschieben (W4, DESIGN §4.2 + Codex-Gegenprüfung §2 „Drop-Ziel →
//  Listenindex deterministisch")
//
//  Der Speicher ist eine REIHENFOLGE. Verschieben heißt deshalb: einen Index
//  ändern — nie eine Koordinate. Alles hier ist rein und ohne DOM, damit der
//  Vertrag „welches Drop-Ziel wird welcher Listenplatz" ohne Layout-Engine
//  beweisbar ist (jsdom rechnet kein CSS-Grid).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Dieselbe Anordnung, aber die SICHTBAREN Widgets stehen in der übergebenen
 * neuen Reihenfolge. Die unsichtbaren (Schalter aus, Quelle still) behalten
 * ihre absoluten Plätze — sonst würde ein Zug auf der Bühne ein Widget
 * mitschleifen, das Andi gerade gar nicht sieht, und beim Wiedereinschalten
 * stünde es woanders als er es verlassen hat.
 *
 * Das Verfahren: die alte Reihenfolge einmal durchgehen; jedes Mal, wenn ein
 * sichtbares Widget dran ist, das NÄCHSTE aus der neuen Sequenz einsetzen.
 * Total (unbekannte/doppelte Ids in `visibleOrder` werden ignoriert) und
 * idempotent, weil {@link normalizeHomeLayout} am Ende steht.
 */
export function withHomeOrder(
  layout: HomeLayoutV1,
  visibleOrder: readonly HomeWidgetId[],
): HomeLayoutV1 {
  const sizes = new Map(layout.order.map((e) => [e.id, e.size]));
  const seen = new Set<HomeWidgetId>();
  const wanted: HomeWidgetId[] = [];
  for (const id of visibleOrder) {
    if (!isStageWidgetId(id) || seen.has(id) || !sizes.has(id)) continue;
    seen.add(id);
    wanted.push(id);
  }
  let next = 0;
  const order = layout.order.map((entry) => {
    if (!seen.has(entry.id)) return entry;
    const id = wanted[next];
    next += 1;
    // Die Stufe reist MIT dem Widget, nicht mit dem Platz.
    return { id, size: sizes.get(id) ?? entry.size };
  });
  return normalizeHomeLayout({ version: 1, order, placements: layout.placements });
}


/**
 * **Zeigerpunkt ⇒ Rasterzelle** (W7-B) — die EINE Übersetzung von „wo liegt
 * der Finger" nach „welche Zelle ist gemeint".
 *
 * Sie ersetzt den früheren `homeDropIndex` (Zeigerpunkt ⇒ LISTENPLATZ), der
 * mit dem freien Raster seinen Sinn verloren hat: es gibt keinen Listenplatz
 * mehr zu treffen, nur noch eine Zelle. Zwei Übersetzungen nebeneinander
 * stehen zu lassen hieße, zwei Wahrheiten über dasselbe Ziel zu pflegen.
 *
 * Die Zelle ist SEITEN-LOKAL (`row` zählt ab dem Seitenanfang); den Sprung auf
 * die seitenübergreifende Zeile macht der Aufrufer, der weiß, welche Seite er
 * gerade misst. An den Rändern wird geklemmt — der Finger darf über die Bühne
 * hinauswandern, das Ziel bleibt bestimmt. Die 12-px-Lücke zwischen zwei
 * Zellen gehört der Zelle links/oben davon.
 */
export function homeDropCell(
  pointer: { x: number; y: number },
  geometry: { width: number; height: number; columns: number; rows: number },
): HomeCell {
  const columns = Math.max(1, Math.floor(geometry.columns));
  const rows = Math.max(1, Math.floor(geometry.rows));
  const clampIdx = (v: number, max: number) => Math.max(0, Math.min(max, v));
  const step = (extent: number, count: number) =>
    Math.max(1, (extent - HOME_STAGE_GAP_PX * (count - 1)) / count + HOME_STAGE_GAP_PX);
  return {
    col: clampIdx(Math.floor(pointer.x / step(geometry.width, columns)), columns - 1),
    row: clampIdx(Math.floor(pointer.y / step(geometry.height, rows)), rows - 1),
  };
}

/**
 * Die vier Stufen in ihrer natürlichen Reihenfolge — klein nach groß. Sie
 * steht HIER und nicht in der Registry, weil die Registry nur sagt, WELCHE
 * Stufen ein Widget kann, nicht welche größer ist als welche. Beides in einer
 * Liste wäre eine Ordnung, die man versehentlich beim Sortieren verliert.
 */
const SIZE_LADDER: readonly HomeTileSize[] = ['S', 'M', 'L', 'XL'];

/**
 * **Eine Stufe rauf oder runter** (W6, Andi 20.08. wörtlich: „Die
 * Größenauswahl soll ein + und − sein; sobald es nicht größer werden kann,
 * sind die entsprechenden Pfeile ausgegraut") — seit W7-D der **einzige** Weg
 * zur Größe: die Griffecke, die daneben stand, ist weg (s. `renderSizer` in
 * `HomeStage.tsx`). Zwei Wege waren zwei Wahrheiten, und die Ecke kostete
 * ausgerechnet die Kachelecke, an der man zum Verschieben greift.
 *
 * Genau diese Funktion beantwortet BEIDE Hälften der Bestellung mit EINER
 * Zahl: das Ergebnis ist die neue Stufe — oder `null`, und `null` heißt „an
 * dieser Kante ist Schluss", also *ausgegraut*. Der Knopf-Zustand ist damit
 * kein zweiter, handgeschriebener Vergleich in der Ansicht, der irgendwann
 * gegen das echte Verhalten driftet: gedrückt werden kann genau, was auch
 * wirkt.
 *
 * Gestuft wird über die ERLAUBTEN Stufen dieses Widgets, nicht über alle vier
 * — beim Sauger (kein XL, §1.1) ist `+` auf L schon das Ende, und ein
 * Zwischenschritt, den die Registry nicht kennt, entsteht nie.
 *
 * `current` außerhalb von [allowed] ist unerreichbar (`normalizeHomeLayout`
 * zieht jeden gespeicherten Wert auf eine erlaubte Stufe) und liefert
 * `null` — lieber ein toter Knopf als ein geratener Sprung.
 */
export function stepHomeTileSize(
  allowed: readonly HomeTileSize[],
  current: HomeTileSize,
  delta: 1 | -1,
): HomeTileSize | null {
  const ladder = SIZE_LADDER.filter((s) => allowed.includes(s));
  const at = ladder.indexOf(current);
  if (at < 0) return null;
  return ladder[at + delta] ?? null;
}

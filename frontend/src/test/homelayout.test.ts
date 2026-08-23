import { describe, it, expect } from 'vitest';
import {
  HOME_COLUMN_MIN_WIDTH_PX,
  HOME_MAX_COLUMNS,
  HOME_STAGE_GAP_PX,
  HOME_TILE_MIN_HEIGHT_PX,
  effectiveSize,
  homeStageColumns,
  homeStageRows,
  planHomeStage,
  sizeToSpan,
  type HomeLayoutTile,
} from '../components/homeLayout';
import type { HomeTileSize } from '../components/homeWidgets';

/**
 * **homelayout.test** — the contract of the stage model ("Komposition v2",
 * 15.08.). Pure arithmetic, no DOM, no React: the model is the only place that
 * decides how many tiles a window can honestly carry, so it is the only place
 * that has to be proven.
 *
 * The load-bearing promise, in Andi's words at the iPad ("the widgets are just
 * pressed together"): **a tile is never squeezed below
 * {@link HOME_TILE_MIN_HEIGHT_PX} — the tail goes to page 2 instead.** Every
 * test below either proves that rule or the geometry it stands on.
 */

const tiles = (...ids: string[]): HomeLayoutTile[] => ids.map((id) => ({ id }));
const ids = (list: readonly HomeLayoutTile[]): string[] => list.map((t) => t.id);

describe('homeStageColumns — one rule instead of a breakpoint table', () => {
  it('the iPad in landscape finally gets its fourth column (the S1 two-pixel miss)', () => {
    // 1194 px viewport − 40 px `.app__main` padding = 1154 px of stage.
    // With the old 280 px minimum: 4 × 280 + 3 × 12 = 1156 > 1154 ⇒ three columns.
    expect(homeStageColumns(1154)).toBe(4);
    expect(4 * HOME_COLUMN_MIN_WIDTH_PX + 3 * HOME_STAGE_GAP_PX).toBeLessThanOrEqual(1154);
  });

  it('iPad portrait (834 px viewport ⇒ 794 px stage) carries three columns', () => {
    expect(homeStageColumns(794)).toBe(3);
  });

  it('narrow windows fall back to two and one column', () => {
    expect(homeStageColumns(660)).toBe(2); // 700 px viewport
    expect(homeStageColumns(460)).toBe(1); // phone, portrait
  });

  it('the exact switch points follow from the minimum, nothing is hand-tuned', () => {
    // n columns fit iff n × min + (n−1) × gap ≤ width.
    for (const n of [2, 3, 4]) {
      const exact = n * HOME_COLUMN_MIN_WIDTH_PX + (n - 1) * HOME_STAGE_GAP_PX;
      expect(homeStageColumns(exact)).toBe(n);
      expect(homeStageColumns(exact - 1)).toBe(n - 1);
    }
  });

  it('very wide windows stop at four — extra width makes tiles wider, not more numerous', () => {
    expect(homeStageColumns(4000)).toBe(HOME_MAX_COLUMNS);
  });

  it('an unmeasured/absurd width never yields zero columns', () => {
    expect(homeStageColumns(0)).toBe(1);
    expect(homeStageColumns(-100)).toBe(1);
    expect(homeStageColumns(Number.NaN)).toBe(1);
  });
});

describe('homeStageRows — the minimum tile height decides where a page ends', () => {
  it('the real iPad stage (~165 px after the S1 frame) carries exactly one row', () => {
    expect(homeStageRows(165)).toBe(1);
  });

  it('a row is only counted when it fits COMPLETELY', () => {
    const two = 2 * HOME_TILE_MIN_HEIGHT_PX + HOME_STAGE_GAP_PX; // 276
    expect(homeStageRows(two)).toBe(2);
    expect(homeStageRows(two - 1)).toBe(1);
  });

  it('a stage too short even for one tile still shows one (nothing would be worse)', () => {
    expect(homeStageRows(40)).toBe(1);
    expect(homeStageRows(0)).toBe(1);
  });
});

describe('planHomeStage — pages instead of squeezing', () => {
  it('five tiles on a one-row/three-column stage become 3 + 2, not five thin ones', () => {
    const plan = planHomeStage(tiles('laeuft', 'einkauf', 'vacuum', 'climate', 'news'), {
      width: 794,
      height: 165,
    });
    expect(plan.measured).toBe(true);
    expect(plan.columns).toBe(3);
    expect(plan.rowsPerPage).toBe(1);
    expect(plan.pages).toHaveLength(2);
    expect(ids(plan.pages[0].tiles)).toEqual(['laeuft', 'einkauf', 'vacuum']);
    expect(ids(plan.pages[1].tiles)).toEqual(['climate', 'news']);
  });

  it('no page ever claims more rows than fit — the min height is never violated', () => {
    const plan = planHomeStage(tiles('a', 'b', 'c', 'd', 'e', 'f', 'g'), { width: 794, height: 300 });
    expect(plan.rowsPerPage).toBe(2);
    for (const page of plan.pages) {
      expect(page.rows).toBeLessThanOrEqual(plan.rowsPerPage);
      expect(page.tiles.length).toBeLessThanOrEqual(plan.columns * plan.rowsPerPage);
    }
    expect(plan.pages.flatMap((p) => ids(p.tiles))).toEqual(['a', 'b', 'c', 'd', 'e', 'f', 'g']);
  });

  it('a page reports the rows it really uses, not the rows it could have', () => {
    // Two tiles, four columns, room for three rows ⇒ ONE row, stretched over the
    // full stage — three thin rows with two of them empty would be the S1 bug
    // upside down.
    const plan = planHomeStage(tiles('a', 'b'), { width: 1154, height: 460 });
    expect(plan.rowsPerPage).toBe(3);
    expect(plan.pages).toHaveLength(1);
    expect(plan.pages[0].rows).toBe(1);
  });

  it('everything fits ⇒ exactly one page and no dots (the common case)', () => {
    const plan = planHomeStage(tiles('laeuft', 'einkauf', 'news'), { width: 1154, height: 300 });
    expect(plan.pages).toHaveLength(1);
    expect(ids(plan.pages[0].tiles)).toEqual(['laeuft', 'einkauf', 'news']);
  });

  it('an empty tile list produces no page at all', () => {
    expect(planHomeStage([], { width: 1154, height: 300 }).pages).toEqual([]);
    expect(planHomeStage([], null).pages).toEqual([]);
  });

  it('unmeasured ⇒ one page with everything and `measured: false` (the CSS fallback paints it)', () => {
    const plan = planHomeStage(tiles('a', 'b', 'c', 'd', 'e'), null);
    expect(plan.measured).toBe(false);
    expect(plan.columns).toBe(0);
    expect(plan.pages).toHaveLength(1);
    expect(ids(plan.pages[0].tiles)).toEqual(['a', 'b', 'c', 'd', 'e']);
  });

  it('a zero-sized box counts as unmeasured, not as a stage without room', () => {
    const plan = planHomeStage(tiles('a', 'b'), { width: 0, height: 0 });
    expect(plan.measured).toBe(false);
    expect(plan.pages).toHaveLength(1);
  });
});

describe('planHomeStage — the S3 foundation: tiles wider/taller than one cell', () => {
  it('a two-cell tile occupies two cells and pushes the rest onward', () => {
    const plan = planHomeStage(
      [{ id: 'weather', cols: 2 }, { id: 'laeuft' }, { id: 'einkauf' }],
      { width: 794, height: 165 }, // 3 columns, 1 row
    );
    expect(plan.pages).toHaveLength(2);
    expect(ids(plan.pages[0].tiles)).toEqual(['weather', 'laeuft']);
    expect(ids(plan.pages[1].tiles)).toEqual(['einkauf']);
  });

  it('order beats density: a later small tile does NOT fill the hole left by a big one', () => {
    // 3 columns × 1 row. After `a` (1 cell) the 2-cell `big` still fits (cells
    // 2+3). `small` does not — and must NOT be pulled forward past `big`.
    const plan = planHomeStage(
      [{ id: 'a' }, { id: 'big', cols: 2 }, { id: 'small' }],
      { width: 794, height: 165 },
    );
    expect(ids(plan.pages[0].tiles)).toEqual(['a', 'big']);
    expect(ids(plan.pages[1].tiles)).toEqual(['small']);
  });

  it('a 2×2 tile really claims two rows', () => {
    const plan = planHomeStage(
      [{ id: 'l', cols: 2, rows: 2 }, { id: 'a' }, { id: 'b' }, { id: 'c' }, { id: 'd' }],
      { width: 1154, height: 300 }, // 4 columns, 2 rows = 8 cells
    );
    expect(plan.pages).toHaveLength(1);
    expect(plan.pages[0].rows).toBe(2);
    expect(ids(plan.pages[0].tiles)).toEqual(['l', 'a', 'b', 'c', 'd']);
  });

  it('a span larger than the page is clamped, never dropped', () => {
    const plan = planHomeStage([{ id: 'huge', cols: 9, rows: 9 }, { id: 'a' }], {
      width: 460, // one column
      height: 165, // one row
    });
    expect(ids(plan.pages[0].tiles)).toEqual(['huge']);
    expect(plan.pages[0].rows).toBe(1);
    expect(ids(plan.pages[1].tiles)).toEqual(['a']);
  });
});

describe('planHomeStage — cells: the placement a renderer MUST use (Codex-Gegenprüfung 18.08. §1)', () => {
  it('cells mirror tiles 1:1, in order, each with the EXACT {row,col,cols,rows} firstFreeSpot decided', () => {
    // Same worked example as DESIGN §2.4: [L 2×2][S][S] on a 4-column stage —
    // the next S fills row 1 col 2 (0-based), the Tetris hole beside the L tile.
    const plan = planHomeStage(
      [{ id: 'l', cols: 2, rows: 2 }, { id: 's1' }, { id: 's2' }, { id: 's3' }],
      { width: 1154, height: 300 }, // 4 columns, 2 rows
    );
    expect(plan.pages).toHaveLength(1);
    const cells = plan.pages[0].cells;
    expect(cells.map((c) => c.tile.id)).toEqual(ids(plan.pages[0].tiles));
    expect(cells).toEqual([
      { tile: { id: 'l', cols: 2, rows: 2 }, row: 0, col: 0, cols: 2, rows: 2 },
      { tile: { id: 's1' }, row: 0, col: 2, cols: 1, rows: 1 },
      { tile: { id: 's2' }, row: 0, col: 3, cols: 1, rows: 1 },
      { tile: { id: 's3' }, row: 1, col: 2, cols: 1, rows: 1 },
    ]);
  });

  it('unmeasured ⇒ cells stays empty (no columns to place anything into yet, the CSS fallback paints instead)', () => {
    const plan = planHomeStage(tiles('a', 'b'), null);
    expect(plan.measured).toBe(false);
    expect(plan.pages[0].cells).toEqual([]);
  });
});

describe('sizeToSpan / effectiveSize — the four steps become cells (§2.3, Kurskorrekturen 18.08.)', () => {
  it('sizeToSpan: S/M/L are plain constants, no "1×2 hochkant" exists', () => {
    expect(sizeToSpan('S', 4)).toEqual({ cols: 1, rows: 1 });
    expect(sizeToSpan('M', 4)).toEqual({ cols: 2, rows: 1 });
    expect(sizeToSpan('L', 4)).toEqual({ cols: 2, rows: 2 });
  });

  it('sizeToSpan: XL means "full stage width × 2 rows", not "4 cells wide" (XL-Kurskorrektur)', () => {
    expect(sizeToSpan('XL', 4)).toEqual({ cols: 4, rows: 2 });
    expect(sizeToSpan('XL', 3)).toEqual({ cols: 3, rows: 2 }); // the 920px cap makes 4 columns unreachable on Übersicht
    expect(sizeToSpan('XL', 1)).toEqual({ cols: 1, rows: 2 }); // never called with columns<2 in practice (effectiveSize degrades first), still total
  });

  it('effectiveSize: the corrected column-degradation table (§2.3), rowsPerPage generous (2) throughout', () => {
    const table: Array<[HomeTileSize, number, HomeTileSize]> = [
      ['XL', 4, 'XL'],
      ['XL', 3, 'XL'],
      ['XL', 2, 'L'],
      ['XL', 1, 'S'],
      ['L', 4, 'L'],
      ['L', 3, 'L'],
      ['L', 2, 'L'],
      ['L', 1, 'S'],
      ['M', 4, 'M'],
      ['M', 2, 'M'],
      ['M', 1, 'S'],
      ['S', 4, 'S'],
      ['S', 1, 'S'],
    ];
    for (const [stored, columns, expected] of table) {
      expect(effectiveSize(stored, columns, 2)).toBe(expected);
    }
  });

  it('effectiveSize: the row clamp (Kurskorrektur — Codex-Gegenprüfung §1/§4) degrades L/XL to M on a one-row stage', () => {
    // A one-row stage cannot physically hold 2 rows, no matter how many
    // columns it has — content must not keep showing L/XL richness in a
    // cell `spanOf` will clamp to one row.
    expect(effectiveSize('XL', 4, 1)).toBe('M');
    expect(effectiveSize('L', 4, 1)).toBe('M');
    expect(effectiveSize('XL', 3, 1)).toBe('M');
    // Column degradation runs FIRST (XL → L at 2 columns), then the row
    // clamp degrades that L one more step — same destination either way.
    expect(effectiveSize('XL', 2, 1)).toBe('M');
    // M/S never needed a second row — the row clamp is a no-op for them.
    expect(effectiveSize('M', 4, 1)).toBe('M');
    expect(effectiveSize('S', 4, 1)).toBe('S');
    // A one-COLUMN stage still wins outright (§2.3) — the row clamp never
    // even runs, everything is S regardless of rowsPerPage.
    expect(effectiveSize('XL', 1, 1)).toBe('S');
    expect(effectiveSize('XL', 1, 5)).toBe('S');
  });

  it('effectiveSize: a roomy stage (rowsPerPage ≥ 2) never triggers the row clamp', () => {
    for (const rows of [2, 3, 5]) {
      expect(effectiveSize('XL', 4, rows)).toBe('XL');
      expect(effectiveSize('L', 2, rows)).toBe('L');
    }
  });

  it('the stored value is never mutated by either degradation — turn the stage back and forth, XL is XL again', () => {
    // effectiveSize is queried fresh each time; nothing about calling it with
    // a small (columns, rowsPerPage) pair persists into the next call.
    expect(effectiveSize('XL', 1, 1)).toBe('S');
    expect(effectiveSize('XL', 4, 2)).toBe('XL');
  });

  it('property: sizeToSpan(effectiveSize(...), columns) NEVER claims more than the real physical cell', () => {
    const sizes: HomeTileSize[] = ['S', 'M', 'L', 'XL'];
    for (const stored of sizes) {
      for (let columns = 1; columns <= 4; columns += 1) {
        for (let rowsPerPage = 1; rowsPerPage <= 3; rowsPerPage += 1) {
          const es = effectiveSize(stored, columns, rowsPerPage);
          const span = sizeToSpan(es, columns);
          expect(span.cols).toBeLessThanOrEqual(columns);
          expect(span.rows).toBeLessThanOrEqual(rowsPerPage);
        }
      }
    }
  });
});

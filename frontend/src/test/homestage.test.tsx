/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeAll, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { HomeStage, HOME_SWIPE_ENGAGE_PX, type HomeStageTile } from '../components/HomeStage';
import { de } from '../i18n/de';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/**
 * **homestage.test** — the swipe mechanics of the stage ("Komposition v2",
 * 15.08.). The page ARITHMETIC is proven without a DOM in `homelayout.test.ts`;
 * what needs a DOM is exactly the part that could not be modelled: does a
 * pointer gesture turn the page, and does it stay out of the way when the
 * gesture belongs to a tile?
 *
 * Idiom and pointer-event plumbing follow `nightWindowDial.test.tsx` (the one
 * existing pointer-drag precedent in this project — no third-party gesture
 * package exists here, and none was added: the dependencies are still exactly
 * react + react-dom).
 *
 * jsdom does no layout, so `getBoundingClientRect` is stubbed — that stub IS
 * the "measurement" the component reacts to, and switching it is how a
 * different window size is simulated.
 */

// `node` is a BUILDER now (W1, "Inhalt folgt der ECHTEN Fläche") — this suite
// only proves swipe mechanics, so the content it builds ignores the size it
// is handed and stays fixed; the placement (`grid-column`/`grid-row`) is
// proven in `homelayout.test.ts`/`idleface.test.tsx`, not here.
const tile = (id: string): HomeStageTile => ({
  id,
  node: () => (
    <article key={id} className="tile idle__tile" data-tile={id}>
      {id}
    </article>
  ),
});

const rect = (width: number, height: number): DOMRect =>
  ({
    x: 0,
    y: 0,
    width,
    height,
    top: 0,
    left: 0,
    right: width,
    bottom: height,
    toJSON: () => ({}),
  }) as DOMRect;

/** Every element reports the same box — only `.idle__pages` is ever measured. */
const stubLayout = (width: number, height: number) => {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() =>
    rect(width, height),
  );
};

const pointerEvt = (type: string, clientX: number, clientY: number, pointerId = 1): Event => {
  const evt = new MouseEvent(type, { bubbles: true, cancelable: true, clientX, clientY });
  Object.defineProperty(evt, 'pointerId', { value: pointerId, configurable: true });
  return evt;
};

describe('HomeStage — SSR/first paint (no measurement yet)', () => {
  it('renders every tile on one page and leaves the grid to the CSS fallback', () => {
    const html = renderToStaticMarkup(
      <HomeStage tiles={['laeuft', 'einkauf', 'vacuum', 'climate', 'news'].map(tile)} />,
    );
    expect(html).toContain('idle__tiles');
    expect(html).toContain('data-pages="1"');
    for (const id of ['laeuft', 'einkauf', 'vacuum', 'climate', 'news']) {
      expect(html).toContain(`data-tile="${id}"`);
    }
    // No inline grid before the measurement — `index.css` paints `auto-fit`.
    expect(html).not.toContain('grid-template-columns');
    // No dots without a second page.
    expect(html).not.toContain('idle__dot');
  });

  it('an empty tile list renders NOTHING (no empty stage frame)', () => {
    expect(renderToStaticMarkup(<HomeStage tiles={[]} />)).toBe('');
  });
});

describe('HomeStage — measured stage, pages and dots', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const render = async (tiles: HomeStageTile[]) => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<HomeStage tiles={tiles} />);
    });
  };

  const pages = () => [...container.querySelectorAll('.idle__page')];
  const activeIndex = () => pages().findIndex((p) => p.getAttribute('data-active') === 'true');
  const track = () => container.querySelector('.idle__pages')!;

  // Hermetisch gegen ANDERE Testdateien (localStorage überlebt Worker-weit;
  // CI-Rot 23.08.) — aber nur EINMAL pro Datei: die Tests dieses Blocks
  // bauen bewusst aufeinander auf (gespeichertes Layout wandert mit).
  beforeAll(() => {
    globalThis.localStorage?.clear?.();
  });
  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
  });
  afterEach(async () => {
    if (root) {
      const r = root;
      await act(async () => r.unmount());
      root = null;
    }
    container.remove();
    vi.restoreAllMocks();
  });

  it('iPad portrait stage (794 × 165): five tiles become two pages with dots', async () => {
    stubLayout(794, 165);
    await render(['laeuft', 'einkauf', 'vacuum', 'climate', 'news'].map(tile));

    expect(pages()).toHaveLength(2);
    expect(container.querySelector('.idle__tiles')!.getAttribute('data-pages')).toBe('2');
    expect([...pages()[0].querySelectorAll('[data-tile]')].map((n) => n.getAttribute('data-tile'))).toEqual([
      'laeuft',
      'einkauf',
      'vacuum',
    ]);
    // The measured grid is explicit — three columns, one row.
    expect(pages()[0].getAttribute('style')).toContain('repeat(3, minmax(0, 1fr))');
    const dots = [...container.querySelectorAll('.idle__dot')];
    expect(dots).toHaveLength(2);
    expect(dots[0].getAttribute('aria-current')).toBe('true');
    expect(dots[0].getAttribute('aria-label')).toBe(de.idleFace.stage.page(1, 2));
    expect(container.querySelector('.idle__dots')!.getAttribute('aria-label')).toBe(
      de.idleFace.stage.pagesAria,
    );
  });

  it('a tall, wide stage carries the same five tiles on ONE page — and shows no dots', async () => {
    stubLayout(1154, 300); // iPad landscape, roomier frame ⇒ 4 columns × 2 rows
    await render(['laeuft', 'einkauf', 'vacuum', 'climate', 'news'].map(tile));

    expect(pages()).toHaveLength(1);
    expect(container.querySelectorAll('.idle__dot')).toHaveLength(0);
    expect(pages()[0].getAttribute('style')).toContain('repeat(4, minmax(0, 1fr))');
  });

  it('a horizontal swipe turns the page', async () => {
    stubLayout(794, 165);
    await render(['laeuft', 'einkauf', 'vacuum', 'climate', 'news'].map(tile));
    expect(activeIndex()).toBe(0);

    await act(async () => {
      track().dispatchEvent(pointerEvt('pointerdown', 600, 300));
      track().dispatchEvent(pointerEvt('pointermove', 400, 302));
      track().dispatchEvent(pointerEvt('pointerup', 400, 302));
    });

    expect(activeIndex()).toBe(1);
    expect([...container.querySelectorAll('.idle__dot')][1].getAttribute('aria-current')).toBe('true');
  });

  it('swiping back returns to page one, and the last page does not wrap around', async () => {
    stubLayout(794, 165);
    await render(['laeuft', 'einkauf', 'vacuum', 'climate', 'news'].map(tile));

    const swipe = async (from: number, to: number) => {
      await act(async () => {
        track().dispatchEvent(pointerEvt('pointerdown', from, 300));
        track().dispatchEvent(pointerEvt('pointermove', to, 300));
        track().dispatchEvent(pointerEvt('pointerup', to, 300));
      });
    };

    await swipe(600, 400);
    expect(activeIndex()).toBe(1);
    // Further left on the LAST page: nothing beyond it.
    await swipe(600, 400);
    expect(activeIndex()).toBe(1);
    await swipe(400, 600);
    expect(activeIndex()).toBe(0);
    // Further right on the FIRST page: nothing before it.
    await swipe(400, 600);
    expect(activeIndex()).toBe(0);
  });

  it('a short drag is a tap, not a swipe — the page stays', async () => {
    stubLayout(794, 165);
    await render(['laeuft', 'einkauf', 'vacuum', 'climate', 'news'].map(tile));

    await act(async () => {
      track().dispatchEvent(pointerEvt('pointerdown', 600, 300));
      track().dispatchEvent(pointerEvt('pointermove', 600 - (HOME_SWIPE_ENGAGE_PX - 2), 300));
      track().dispatchEvent(pointerEvt('pointerup', 600 - (HOME_SWIPE_ENGAGE_PX - 2), 300));
    });

    expect(activeIndex()).toBe(0);
  });

  it('a mostly VERTICAL drag belongs to the tile underneath, not to the pager', async () => {
    stubLayout(794, 165);
    await render(['laeuft', 'einkauf', 'vacuum', 'climate', 'news'].map(tile));

    await act(async () => {
      track().dispatchEvent(pointerEvt('pointerdown', 600, 300));
      // 60 px left, 200 px up: the tile scrolls in its own frame, the stage rests.
      track().dispatchEvent(pointerEvt('pointermove', 540, 100));
      track().dispatchEvent(pointerEvt('pointerup', 540, 100));
    });

    expect(activeIndex()).toBe(0);
  });

  it('a cancelled gesture (system takeover) never commits a page turn', async () => {
    stubLayout(794, 165);
    await render(['laeuft', 'einkauf', 'vacuum', 'climate', 'news'].map(tile));

    await act(async () => {
      track().dispatchEvent(pointerEvt('pointerdown', 600, 300));
      track().dispatchEvent(pointerEvt('pointermove', 300, 300));
      track().dispatchEvent(pointerEvt('pointercancel', 300, 300));
    });

    expect(activeIndex()).toBe(0);
  });

  it('a single page ignores pointer gestures entirely', async () => {
    stubLayout(1154, 300);
    await render(['laeuft', 'einkauf'].map(tile));

    await act(async () => {
      track().dispatchEvent(pointerEvt('pointerdown', 600, 300));
      track().dispatchEvent(pointerEvt('pointermove', 200, 300));
      track().dispatchEvent(pointerEvt('pointerup', 200, 300));
    });

    expect(activeIndex()).toBe(0);
    expect(pages()).toHaveLength(1);
  });

  it('the dots are a real second path to every page (keyboard/click, not only swipe)', async () => {
    stubLayout(794, 165);
    await render(['laeuft', 'einkauf', 'vacuum', 'climate', 'news'].map(tile));

    const dots = [...container.querySelectorAll('.idle__dot')] as HTMLButtonElement[];
    await act(async () => {
      dots[1].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(activeIndex()).toBe(1);

    await act(async () => {
      dots[0].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(activeIndex()).toBe(0);
  });

  it('losing a page (fewer tiles) steps the reader back instead of showing an empty stage', async () => {
    stubLayout(794, 165);
    await render(['laeuft', 'einkauf', 'vacuum', 'climate', 'news'].map(tile));

    await act(async () => {
      track().dispatchEvent(pointerEvt('pointerdown', 600, 300));
      track().dispatchEvent(pointerEvt('pointermove', 300, 300));
      track().dispatchEvent(pointerEvt('pointerup', 300, 300));
    });
    expect(activeIndex()).toBe(1);

    await act(async () => {
      root!.render(<HomeStage tiles={['laeuft', 'einkauf'].map(tile)} />);
    });
    expect(pages()).toHaveLength(1);
    expect(activeIndex()).toBe(0);
  });
});

/**
 * **Explicit placement** — Codex-Gegenprüfung 18.08. §1: `firstFreeSpot`
 * computed a `{row,col}` for every tile and the renderer discarded it,
 * falling back to plain CSS auto-placement (implicit, no `dense`). That can
 * silently disagree with the model the moment spans are mixed — a small tile
 * scanned into an earlier hole by the packer is not guaranteed to land there
 * under the browser's own auto-placement cursor. `HomeStage` now writes each
 * tile's EXACT cell (`HomeLayoutCell` in `homeLayout.ts`) as inline
 * `grid-column`/`grid-row` `span`, so model and DOM cannot drift apart. This
 * suite pins the worked example from DESIGN-widget-raster-2026-08-18 §2.4
 * itself ("Beispiel 4×2: `[L 2×2][S][S]` ⇒ die nächste S-Kachel füllt Zeile 2
 * Spalte 3") at the DOM: page assignment, span, AND the concrete cell — on
 * the rendered element, not the model.
 */
describe('HomeStage — explicit placement pins the model onto the DOM (no CSS auto-placement)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  /** A tile with an EXPLICIT cell span — bypasses `size`/`effectiveSize` entirely, this suite is about placement, not content density. */
  const spanTile = (id: string, cols: number, rows: number): HomeStageTile => ({
    id,
    cols,
    rows,
    node: () => (
      <article key={id} className="tile idle__tile" data-tile={id}>
        {id}
      </article>
    ),
  });

  const render = async (tiles: HomeStageTile[]) => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<HomeStage tiles={tiles} />);
    });
  };

  const pages = () => [...container.querySelectorAll('.idle__page')];
  const tileEl = (id: string) => container.querySelector<HTMLElement>(`[data-tile="${id}"]`)!;
  const pageOf = (id: string) => pages().findIndex((p) => p.querySelector(`[data-tile="${id}"]`) !== null);

  beforeEach(() => {
    // Hermetisch: ein von ANDEREN Testdateien hinterlassenes Layout
    // (localStorage überlebt Worker-weit) verschiebt sonst die Seitenzahl —
    // CI-Rot 23.08. bei 2-Kern-Worker-Verteilung, lokal zufällig grün.
    globalThis.localStorage?.clear?.();
    container = document.createElement('div');
    document.body.appendChild(container);
  });
  afterEach(async () => {
    if (root) {
      const r = root;
      await act(async () => r.unmount());
      root = null;
    }
    container.remove();
    vi.restoreAllMocks();
  });

  it('4×2 stage, mixed spans: page assignment + span + the EXACT cell, per tile', async () => {
    stubLayout(1154, 300); // iPad landscape ⇒ 4 columns × 2 rows (rowsPerPage = fitCount(300,132,12) = 2)
    // a=L(2×2) b=S(1×1) c=S(1×1) d=M(2×1) — exactly fills page 1 (4+1+1+2=8 cells);
    // e=S(1×1) has nowhere left ⇒ opens page 2. Placement worked by hand against
    // `firstFreeSpot`'s row-major scan (§2.4's own example, extended by `d`):
    //   a → row 0 col 0 (2×2)   b → row 0 col 2 (1×1)   c → row 0 col 3 (1×1)
    //   d → row 1 col 2 (2×1)   e → row 0 col 0 of PAGE 2 (1×1)
    await render([spanTile('a', 2, 2), spanTile('b', 1, 1), spanTile('c', 1, 1), spanTile('d', 2, 1), spanTile('e', 1, 1)]);

    expect(pages()).toHaveLength(2);
    // Page assignment.
    for (const id of ['a', 'b', 'c', 'd']) expect(pageOf(id)).toBe(0);
    expect(pageOf('e')).toBe(1);

    // Span + the CONCRETE cell, read back from the rendered element's own style
    // (CSS grid lines are 1-based; `firstFreeSpot`'s row/col are 0-based).
    expect(tileEl('a').style.gridColumn).toBe('1 / span 2');
    expect(tileEl('a').style.gridRow).toBe('1 / span 2');
    expect(tileEl('b').style.gridColumn).toBe('3 / span 1');
    expect(tileEl('b').style.gridRow).toBe('1 / span 1');
    expect(tileEl('c').style.gridColumn).toBe('4 / span 1');
    expect(tileEl('c').style.gridRow).toBe('1 / span 1');
    // `d` is the one CSS auto-placement (without `dense`) would get wrong: its
    // own auto-flow cursor sits at row 0 after `c`, past the row-1 hole under
    // `a` that `firstFreeSpot` finds instead.
    expect(tileEl('d').style.gridColumn).toBe('3 / span 2');
    expect(tileEl('d').style.gridRow).toBe('2 / span 1');
    expect(tileEl('e').style.gridColumn).toBe('1 / span 1');
    expect(tileEl('e').style.gridRow).toBe('1 / span 1');
  });

  it('no `grid-auto-flow: dense` on the page — explicit placement makes it moot, but a stray `dense` would silently mask a model/DOM drift', async () => {
    stubLayout(1154, 300);
    await render([spanTile('a', 2, 2), spanTile('b', 1, 1)]);
    expect(pages()[0].getAttribute('style') ?? '').not.toContain('dense');
  });
});

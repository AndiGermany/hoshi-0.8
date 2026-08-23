/** @vitest-environment jsdom */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { readFileSync } from 'node:fs';
import { Overlay } from '../components/Overlay';
import { CrewOverlay } from '../components/CrewOverlay';
import type { CrewMember } from '../api/crew';

// ═════════════════════════════════════════════════════════════════════════════
//  Overlay — die EINE modale Schale (Design DESIGN-widgets-settings-2026-08-15
//  §3.2). Verallgemeinert aus CrewOverlay; Crew ist ihr erster Nutzer und muss
//  danach PIXEL-IDENTISCH aussehen. Drei Ebenen:
//   1. Der Render-Vertrag der Schale selbst (Backdrop, role/aria, Klassen).
//   2. Das Verhalten in jsdom: Escape, Backdrop-Klick, Karten-Klick, Autofokus.
//   3. Die Nicht-Regression von Crew — Rahmen-Markup Zeichen für Zeichen und
//      der Autofokus auf dem Schließen-Knopf — plus der Z-Ordnungs-Vertrag im
//      CSS (Drawer 50 → Overlay 60 → FiredToast 70).
// ═════════════════════════════════════════════════════════════════════════════

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

describe('Overlay — Render-Vertrag der Schale', () => {
  it('offen: Backdrop mit is-open, Karte als role=dialog + aria-modal + aria-label', () => {
    const html = renderToStaticMarkup(
      <Overlay open onClose={() => {}} label="Testfenster">
        <p>Inhalt</p>
      </Overlay>,
    );
    expect(html).toContain('class="overlay is-open"');
    expect(html).toContain('class="overlay__card"');
    expect(html).toContain('role="dialog"');
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain('aria-label="Testfenster"');
    expect(html).toContain('<p>Inhalt</p>');
  });

  it('geschlossen: kein is-open, aria-hidden gesetzt (kein Tab-Fang)', () => {
    const html = renderToStaticMarkup(
      <Overlay open={false} onClose={() => {}} label="Testfenster">
        <p>Inhalt</p>
      </Overlay>,
    );
    expect(html).not.toContain('is-open');
    expect(html).toContain('aria-hidden="true"');
  });

  it('die BEM-Wurzeln sind überschreibbar — dafür ist die Schale generalisiert', () => {
    const html = renderToStaticMarkup(
      <Overlay
        open
        onClose={() => {}}
        label="Crew"
        backdropClassName="crew-overlay"
        cardClassName="crew"
      >
        <p>x</p>
      </Overlay>,
    );
    expect(html).toContain('class="crew-overlay is-open"');
    expect(html).toContain('class="crew"');
  });
});

describe('Overlay — Verhalten (jsdom)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

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
  });

  const mount = async (el: React.ReactElement): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(el);
    });
  };

  const openOverlay = (onClose: () => void) => (
    <Overlay open onClose={onClose} label="Testfenster">
      <button type="button" className="first">
        erster
      </button>
      <button type="button" className="second">
        zweiter
      </button>
    </Overlay>
  );

  it('Escape schließt', async () => {
    const onClose = vi.fn();
    await mount(openOverlay(onClose));
    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Escape gehört dem offenen Overlay ALLEIN — ein Handler darunter sieht ihn nicht', async () => {
    // Der reale Fall (§3.3/1, Befund §1.4/6): der Einstellungs-Drawer hört
    // ebenfalls auf `window` und schließt sich bei Escape. Ohne diese Zusicherung
    // beantwortet EIN Escape mitten in der Aufnahme die Abbruch-Nachfrage UND
    // reißt den Dialog darunter weg — die Rollback-Semantik läuft dann nie, und
    // ein halbes Profil bleibt verwaist auf dem Server.
    const drawerClose = vi.fn();
    const outerListener = () => drawerClose();
    window.addEventListener('keydown', outerListener);
    try {
      const onClose = vi.fn();
      await mount(openOverlay(onClose));
      await act(async () => {
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      });
      expect(onClose).toHaveBeenCalledTimes(1);
      expect(drawerClose).not.toHaveBeenCalled();

      // Jede ANDERE Taste läuft ungehindert durch — sonst wäre die Schale ein
      // Tastatur-Loch für alles, was darunter liegt (und für React selbst).
      await act(async () => {
        window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }));
      });
      expect(drawerClose).toHaveBeenCalledTimes(1);
    } finally {
      window.removeEventListener('keydown', outerListener);
    }
  });

  it('geschlossen hört die Schale gar nicht erst auf Escape', async () => {
    const onClose = vi.fn();
    await mount(
      <Overlay open={false} onClose={onClose} label="Testfenster">
        <p>x</p>
      </Overlay>,
    );
    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });
    expect(onClose).not.toHaveBeenCalled();
  });

  it('Backdrop-Klick schließt — ein Klick IN die Karte nicht', async () => {
    const onClose = vi.fn();
    await mount(openOverlay(onClose));

    const card = container.querySelector('.overlay__card') as HTMLElement;
    await act(async () => {
      card.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(onClose).not.toHaveBeenCalled();

    const backdrop = container.querySelector('.overlay') as HTMLElement;
    await act(async () => {
      backdrop.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Autofokus: ohne initialFocusRef bekommt das erste fokussierbare Element der Karte den Fokus', async () => {
    await mount(openOverlay(() => {}));
    expect(document.activeElement).toBe(container.querySelector('.first'));
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  Crew-Nicht-Regression: die Schale trägt Crew, ohne einen Pixel zu bewegen.
// ─────────────────────────────────────────────────────────────────────────────

describe('CrewOverlay auf der Schale — Nicht-Regression', () => {
  const members: CrewMember[] = [{ name: 'mira', role: 'PO', mantra: 'm' }];

  it('der Rahmen steht Zeichen für Zeichen wie vor der Verallgemeinerung', () => {
    const open = renderToStaticMarkup(
      <CrewOverlay open members={members} onClose={() => {}} />,
    );
    expect(
      open.startsWith(
        '<div class="crew-overlay is-open" aria-hidden="false">' +
          '<aside class="crew" role="dialog" aria-modal="true" aria-label="Die Crew">',
      ),
    ).toBe(true);
    expect(open.endsWith('</aside></div>')).toBe(true);

    const closed = renderToStaticMarkup(
      <CrewOverlay open={false} members={members} onClose={() => {}} />,
    );
    expect(closed.startsWith('<div class="crew-overlay " aria-hidden="true">')).toBe(true);
  });

  it('Autofokus landet weiterhin auf dem Schließen-Knopf (vorher ein eigener Ref)', async () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    await act(async () => {
      root.render(<CrewOverlay open members={members} onClose={() => {}} />);
    });
    expect(document.activeElement).toBe(container.querySelector('.crew__close'));
    await act(async () => root.unmount());
    container.remove();
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  Z-Ordnung als ausführbarer Vertrag statt als Kommentar-Versprechen:
//  Drawer 50 → Overlay 60 → FiredToast 70. Der Wecker gewinnt immer.
// ─────────────────────────────────────────────────────────────────────────────

describe('Z-Ordnung + Karten-Geometrie im CSS', () => {
  const themesCss = readFileSync('src/styles/themes.css', 'utf8');
  const indexCss = readFileSync('src/index.css', 'utf8');

  /** Der z-index-Wert des Regelblocks, der mit `selector` beginnt. */
  const zIndexOf = (css: string, selector: string): number => {
    const start = css.indexOf(selector);
    expect(start, `${selector} fehlt im CSS`).toBeGreaterThanOrEqual(0);
    const block = css.slice(start, css.indexOf('}', start));
    const hit = /z-index:\s*(\d+)/.exec(block);
    expect(hit, `${selector} ohne z-index`).not.toBeNull();
    return Number(hit![1]);
  };

  it('Drawer 50 < Overlay 60 < FiredToast 70', () => {
    const drawer = zIndexOf(themesCss, '.settings-overlay {');
    const overlay = zIndexOf(themesCss, '.overlay,\n.crew-overlay {');
    const toast = zIndexOf(indexCss, '.fired-toast-wrap {');
    expect(drawer).toBe(50);
    expect(overlay).toBe(60);
    expect(toast).toBe(70);
    expect(drawer).toBeLessThan(overlay);
    expect(overlay).toBeLessThan(toast);
  });

  it('generische Karte und Crew-Karte teilen EINEN Regelblock (Pixel können nicht driften)', () => {
    const start = themesCss.indexOf('.overlay__card,\n.crew {');
    expect(start).toBeGreaterThanOrEqual(0);
    const block = themesCss.slice(start, themesCss.indexOf('}', start));
    expect(block).toContain('width: min(960px, 94vw)');
    expect(block).toContain('max-height: 90vh');
    expect(block).toContain('overflow-y: auto');
  });
});

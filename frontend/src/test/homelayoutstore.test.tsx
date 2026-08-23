/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import {
  DEFAULT_HOME_LAYOUT,
  homeLayoutIndex,
  isStageWidgetId,
  normalizeHomeLayout,
  parseHomeLayout,
  serializeHomeLayout,
  withHomeTileSize,
  type HomeLayoutV1,
} from '../components/homeLayout';
import {
  HOME_LAYOUT_STORAGE_KEY,
  loadHomeLayout,
  resetHomeLayout,
  saveHomeLayout,
  useHomeLayout,
} from '../hooks/useHomeLayout';
import { VACUUM_TILE_STORAGE_KEY, useHomeTiles } from '../hooks/useSettings';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/**
 * **homelayoutstore.test** — der Layout-Speicher (W3,
 * `vault/tracks/DESIGN-widget-raster-2026-08-18.md` §5 + Codex-Gegenprüfung
 * 18.08. §5 „Persistenz-Härtung").
 *
 * Zwei Hälften, bewusst getrennt:
 *  1. **Rein** (`components/homeLayout.ts`): was ein gültiges Layout ist.
 *     Jeder Härtungsfall, den die Gegenprüfung verlangt, hat hier seinen
 *     eigenen Fall — doppelte Ids, ungültige Stufen, unbekannte Version,
 *     unbekannte/fehlende Ids, Idempotenz.
 *  2. **Speicher** (`hooks/useHomeLayout.ts`): dass genau EIN Schlüssel
 *     beschrieben wird, dass ein blockierter/voller Speicher nichts bricht,
 *     dass zwei Hook-Instanzen im selben Tab dasselbe sehen (Same-Tab-Sync)
 *     und dass „Zurücksetzen" die SCHALTER nicht anfasst.
 *
 * Speicher-Idiom (`memoryStorage` + `vi.stubGlobal`) wie in
 * `hometilessettings.test.tsx` — node kennt kein echtes localStorage.
 */

/** In-Memory-Storage in DOM-`Storage`-Form. */
function memoryStorage(): Storage {
  const m = new Map<string, string>();
  return {
    get length() {
      return m.size;
    },
    clear: () => m.clear(),
    getItem: (k: string) => (m.has(k) ? (m.get(k) as string) : null),
    key: (i: number) => Array.from(m.keys())[i] ?? null,
    removeItem: (k: string) => {
      m.delete(k);
    },
    setItem: (k: string, v: string) => {
      m.set(k, String(v));
    },
  };
}

/** Kurzform der Reihenfolge für lesbare Erwartungen: `['wetter:L', …]`. */
const shape = (layout: HomeLayoutV1): string[] => layout.order.map((e) => `${e.id}:${e.size}`);

// W4 (Andi 19.08.): die Uhr ist Bühnen-Widget und führt die Reihenfolge an.
// W6 (Andi 20.08.): der WECKER ist ihr gefolgt und steht an Platz zwei — genau
// da, wo er im Kopf stand (unter dem Gruß, über der Bühne). Damit sind es acht.
const DEFAULT_SHAPE = [
  'uhr:L',
  'wecker:M',
  'wetter:L',
  'laeuft:L',
  'einkauf:M',
  'vacuum:L',
  'climate:L',
  'news:M',
];

describe('homeLayout — DEFAULT_HOME_LAYOUT reproduziert den heutigen Zustand (§5.3)', () => {
  it('ACHT Bühnen-Widgets in der Registry-Reihenfolge, die UHR vorn, mit den heutigen Größen', () => {
    expect(DEFAULT_HOME_LAYOUT.version).toBe(1);
    expect(shape(DEFAULT_HOME_LAYOUT)).toEqual(DEFAULT_SHAPE);
  });

  it('die Krone ist leer — ALLE acht Widgets stehen im Layout, auch der Wecker (W6)', () => {
    // Bis W5 war `wecker` die eine Id, die `isStageWidgetId` verneinte. Seit er
    // ein Bühnen-Widget ist, gibt es keine Kronen-Id mehr — was übrig bleibt,
    // sind Ids, die es gar nicht gibt.
    expect(shape(DEFAULT_HOME_LAYOUT).some((s) => s.startsWith('wecker:'))).toBe(true);
    expect(isStageWidgetId('wecker')).toBe(true);
    expect(isStageWidgetId('wetter')).toBe(true);
    expect(isStageWidgetId('gibtsnicht')).toBe(false);
  });

  it('die UHR steht als ERSTES im Layout — Andis Kurs-Update 19.08. (W4)', () => {
    expect(shape(DEFAULT_HOME_LAYOUT)[0]).toBe('uhr:L');
    expect(isStageWidgetId('uhr')).toBe(true);
  });
});

describe('normalizeHomeLayout — Härtung (Codex §5)', () => {
  it('kein Objekt / null / Array / Zahl ⇒ Default', () => {
    for (const junk of [null, undefined, 42, 'nope', [], true]) {
      expect(shape(normalizeHomeLayout(junk))).toEqual(DEFAULT_SHAPE);
    }
  });

  it('unbekannte Version ⇒ Default (nicht geraten)', () => {
    expect(shape(normalizeHomeLayout({ version: 2, order: [{ id: 'news', size: 'S' }] }))).toEqual(
      DEFAULT_SHAPE,
    );
    expect(shape(normalizeHomeLayout({ order: [{ id: 'news', size: 'S' }] }))).toEqual(DEFAULT_SHAPE);
    expect(shape(normalizeHomeLayout({ version: '1', order: [] }))).toEqual(DEFAULT_SHAPE);
  });

  it('`order` kein Array ⇒ Default', () => {
    expect(shape(normalizeHomeLayout({ version: 1, order: { news: 'S' } }))).toEqual(DEFAULT_SHAPE);
  });

  it('leeres `order` ⇒ alle sechs Widgets werden hinten angehängt', () => {
    expect(shape(normalizeHomeLayout({ version: 1, order: [] }))).toEqual(DEFAULT_SHAPE);
  });

  it('doppelte Id ⇒ der ERSTE Eintrag gewinnt, das Widget erscheint genau einmal', () => {
    const out = normalizeHomeLayout({
      version: 1,
      order: [
        { id: 'news', size: 'S' },
        { id: 'news', size: 'XL' },
      ],
    });
    expect(out.order.filter((e) => e.id === 'news')).toHaveLength(1);
    expect(shape(out)[0]).toBe('news:S');
  });

  it('ungültige Stufe (Müll, Zahl, fehlend) ⇒ Default-Stufe DIESES Widgets', () => {
    const out = normalizeHomeLayout({
      version: 1,
      order: [
        { id: 'news', size: 'XXL' },
        { id: 'wetter', size: 7 },
        { id: 'einkauf' },
      ],
    });
    expect(shape(out).slice(0, 3)).toEqual(['news:M', 'wetter:L', 'einkauf:M']);
  });

  it('eine Stufe, die DIESES Widget nicht kann (XL bei der Uhr, §1.1) ⇒ seine Default-Stufe', () => {
    // Bis 22.08. stand hier der Sauger; seit er ein XL hat (Andi 21.08.),
    // trägt die Uhr das Beispiel — sie kann S·M·L, ihre Felder sind mit Zeit,
    // Datum und Gruß wirklich abgezählt.
    const out = normalizeHomeLayout({ version: 1, order: [{ id: 'uhr', size: 'XL' }] });
    expect(shape(out)[0]).toBe('uhr:L');
  });

  it('unbekannte Ids werden ignoriert, kaputte Einträge übersprungen', () => {
    const out = normalizeHomeLayout({
      version: 1,
      order: [{ id: 'jellyfin', size: 'L' }, { id: 'sofa', size: 'L' }, null, 5, { size: 'L' }, { id: 'news', size: 'L' }],
    });
    expect(shape(out)[0]).toBe('news:L');
    expect(out.order).toHaveLength(8);
    expect(shape(out).some((s) => s.startsWith('jellyfin') || s.startsWith('sofa'))).toBe(false);
  });

  it('fehlende Widgets werden HINTEN mit Default-Stufe angehängt (Vorwärts-Migration, §5.3)', () => {
    const out = normalizeHomeLayout({ version: 1, order: [{ id: 'news', size: 'XL' }] });
    expect(shape(out)).toEqual([
      'news:XL',
      'uhr:L',
      'wecker:M',
      'wetter:L',
      'laeuft:L',
      'einkauf:M',
      'vacuum:L',
      'climate:L',
    ]);
  });

  it('idempotent: normalize(normalize(x)) === normalize(x)', () => {
    const once = normalizeHomeLayout({
      version: 1,
      order: [{ id: 'news', size: 'nope' }, { id: 'news', size: 'S' }, { id: 'climate', size: 'XL' }],
    });
    expect(normalizeHomeLayout(once)).toEqual(once);
    expect(normalizeHomeLayout(normalizeHomeLayout(once))).toEqual(once);
  });

  it('DEFAULT_HOME_LAYOUT ist eingefroren — kein Aufrufer verbiegt den Default aller Leser', () => {
    expect(Object.isFrozen(DEFAULT_HOME_LAYOUT)).toBe(true);
    expect(Object.isFrozen(DEFAULT_HOME_LAYOUT.order)).toBe(true);
  });
});

describe('parseHomeLayout / serializeHomeLayout', () => {
  it('kaputtes JSON, leerer Text, kein Text ⇒ Default, kein Wurf', () => {
    expect(shape(parseHomeLayout('{'))).toEqual(DEFAULT_SHAPE);
    expect(shape(parseHomeLayout(''))).toEqual(DEFAULT_SHAPE);
    expect(shape(parseHomeLayout(null))).toEqual(DEFAULT_SHAPE);
    expect(shape(parseHomeLayout('null'))).toEqual(DEFAULT_SHAPE);
  });

  it('Rundreise: serialize ⇒ parse ist derselbe Zustand', () => {
    const layout = withHomeTileSize(DEFAULT_HOME_LAYOUT, 'news', 'XL');
    expect(parseHomeLayout(serializeHomeLayout(layout))).toEqual(layout);
  });

  it('serialize schreibt IMMER die normalisierte Form (auch aus kaputter Eingabe)', () => {
    const text = serializeHomeLayout({ version: 1, order: [{ id: 'vacuum', size: 'XL' }] } as HomeLayoutV1);
    expect(JSON.parse(text)).toEqual(normalizeHomeLayout({ version: 1, order: [{ id: 'vacuum', size: 'XL' }] }));
  });
});

describe('withHomeTileSize / homeLayoutIndex', () => {
  it('setzt genau EINE Stufe und lässt die Reihenfolge in Ruhe (Verschieben ist W4)', () => {
    const out = withHomeTileSize(DEFAULT_HOME_LAYOUT, 'einkauf', 'XL');
    expect(shape(out)).toEqual([
      'uhr:L',
      'wecker:M',
      'wetter:L',
      'laeuft:L',
      'einkauf:XL',
      'vacuum:L',
      'climate:L',
      'news:M',
    ]);
  });

  it('eine Stufe, die das Widget nicht kann, ändert NICHTS (kein stiller Rückfall auf den Default)', () => {
    const out = withHomeTileSize(DEFAULT_HOME_LAYOUT, 'uhr', 'XL');
    expect(shape(out)).toEqual(DEFAULT_SHAPE);
  });

  it('homeLayoutIndex liefert Platz + gespeicherte Stufe je Id', () => {
    const index = homeLayoutIndex(withHomeTileSize(DEFAULT_HOME_LAYOUT, 'news', 'L'));
    expect(index.get('uhr')).toEqual({ index: 0, size: 'L' });
    // W6: der Wecker steht zwischen Uhr und Wetter — jede Zahl dahinter rückt
    // um eins. Genau das ist die Zusage „neue Widgets ohne Migration".
    expect(index.get('wecker')).toEqual({ index: 1, size: 'M' });
    expect(index.get('wetter')).toEqual({ index: 2, size: 'L' });
    expect(index.get('news')).toEqual({ index: 7, size: 'L' });
  });
});

/* ── Speicher-Seite ─────────────────────────────────────────────────────── */

/** Zeigt das Layout an und stellt die zwei Schreibwege als Knöpfe bereit. */
function LayoutProbe({ tag = 'a' }: { tag?: string }) {
  const { layout, setSize, reset } = useHomeLayout();
  return (
    <div>
      <span data-probe={tag}>{shape(layout).join('|')}</span>
      <button type="button" data-act={`${tag}-xl`} onClick={() => setSize('news', 'XL')}>
        xl
      </button>
      <button type="button" data-act={`${tag}-reset`} onClick={() => reset()}>
        reset
      </button>
    </div>
  );
}

/** Zweite Instanz mit einem SCHALTER daneben — beweist, dass Reset ihn nicht anfasst. */
function LayoutAndSwitchProbe() {
  const { layout } = useHomeLayout();
  const { vacuumEnabled } = useHomeTiles();
  return (
    <div>
      <span data-probe="b">{shape(layout).join('|')}</span>
      <span data-vacuum={String(vacuumEnabled)} />
    </div>
  );
}

describe('useHomeLayout — der Speicher (§5.2)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const mount = async (node: ReactNode): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(node);
    });
  };
  const text = (probe: string): string =>
    container.querySelector(`[data-probe="${probe}"]`)?.textContent ?? '';
  const click = async (act$: string): Promise<void> => {
    const el = container.querySelector(`[data-act="${act$}"]`) as HTMLButtonElement;
    await act(async () => {
      el.click();
    });
  };

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage());
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
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('unbelegter Schlüssel ⇒ Default-Layout', async () => {
    await mount(<LayoutProbe />);
    expect(text('a')).toBe(DEFAULT_SHAPE.join('|'));
  });

  it('eine Stufe setzen schreibt GENAU einen Schlüssel — `hoshi.homeTiles.layout`', async () => {
    await mount(<LayoutProbe />);
    await click('a-xl');
    expect(localStorage.getItem(HOME_LAYOUT_STORAGE_KEY)).not.toBeNull();
    expect(localStorage.length).toBe(1);
    expect(JSON.parse(localStorage.getItem(HOME_LAYOUT_STORAGE_KEY) as string)).toEqual({
      version: 1,
      order: [
        { id: 'uhr', size: 'L' },
        { id: 'wecker', size: 'M' },
        { id: 'wetter', size: 'L' },
        { id: 'laeuft', size: 'L' },
        { id: 'einkauf', size: 'M' },
        { id: 'vacuum', size: 'L' },
        { id: 'climate', size: 'L' },
        { id: 'news', size: 'XL' },
      ],
    });
  });

  it('die Wahl überlebt den Reload (frisch gelesen, nicht aus dem Render)', async () => {
    await mount(<LayoutProbe />);
    await click('a-xl');
    expect(shape(loadHomeLayout())).toEqual([...DEFAULT_SHAPE.slice(0, -1), 'news:XL']);
  });

  it('Same-Tab-Sync: schreibt die eine Instanz, sieht es die andere SOFORT (ohne Reload)', async () => {
    await mount(
      <>
        <LayoutProbe />
        <LayoutAndSwitchProbe />
      </>,
    );
    expect(text('b')).toBe(DEFAULT_SHAPE.join('|'));
    await click('a-xl');
    expect(text('a')).toContain('news:XL');
    expect(text('b')).toContain('news:XL');
  });

  it('Zurücksetzen löscht das Layout und lässt die SCHALTER unangetastet (§4.3)', async () => {
    localStorage.setItem(VACUUM_TILE_STORAGE_KEY, 'true');
    await mount(
      <>
        <LayoutProbe />
        <LayoutAndSwitchProbe />
      </>,
    );
    await click('a-xl');
    expect(localStorage.getItem(HOME_LAYOUT_STORAGE_KEY)).not.toBeNull();

    await click('a-reset');
    expect(localStorage.getItem(HOME_LAYOUT_STORAGE_KEY)).toBeNull();
    expect(text('a')).toBe(DEFAULT_SHAPE.join('|'));
    // Der Sauger-Schalter steht noch — Anordnung und Sichtbarkeit sind zwei Entscheidungen.
    expect(localStorage.getItem(VACUUM_TILE_STORAGE_KEY)).toBe('true');
    expect(container.querySelector('[data-vacuum]')?.getAttribute('data-vacuum')).toBe('true');
  });

  it('Snapshot-Identität: zwei Lesungen desselben Rohtexts liefern DASSELBE Objekt', () => {
    // Ohne diesen Zwischenspeicher schickt `useSyncExternalStore` React in eine
    // Endlosschleife (jedes Parsen wäre ein neues Objekt).
    saveHomeLayout(withHomeTileSize(DEFAULT_HOME_LAYOUT, 'news', 'S'));
    expect(loadHomeLayout()).toBe(loadHomeLayout());
  });

  it('blockierter Speicher (jeder Zugriff wirft) ⇒ Default-Layout, kein Wurf', async () => {
    const blocked = {
      get length() {
        return 0;
      },
      clear: () => {},
      key: () => null,
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
      removeItem: () => {
        throw new Error('blocked');
      },
    } as unknown as Storage;
    vi.stubGlobal('localStorage', blocked);
    await mount(<LayoutProbe />);
    expect(text('a')).toBe(DEFAULT_SHAPE.join('|'));
    await click('a-xl');
    await click('a-reset');
    expect(text('a')).toBe(DEFAULT_SHAPE.join('|'));
  });

  it('voller Speicher (nur `setItem` wirft) ⇒ kein Wurf, das Layout bleibt der Default', async () => {
    const store = memoryStorage();
    vi.stubGlobal('localStorage', {
      ...store,
      getItem: (k: string) => store.getItem(k),
      removeItem: (k: string) => store.removeItem(k),
      setItem: () => {
        throw new Error('QuotaExceededError');
      },
    } as unknown as Storage);
    await mount(<LayoutProbe />);
    await click('a-xl');
    expect(text('a')).toBe(DEFAULT_SHAPE.join('|'));
  });

  it('gar kein localStorage (SSR/node) ⇒ Default, kein Wurf', () => {
    vi.stubGlobal('localStorage', undefined);
    expect(shape(loadHomeLayout())).toEqual(DEFAULT_SHAPE);
    expect(() => saveHomeLayout(DEFAULT_HOME_LAYOUT)).not.toThrow();
    expect(() => resetHomeLayout()).not.toThrow();
  });

  it('kaputter gespeicherter Text ⇒ Default-Layout, kein Bruch der Bühne', async () => {
    localStorage.setItem(HOME_LAYOUT_STORAGE_KEY, '{"version":1,"order":[{"id":');
    await mount(<LayoutProbe />);
    expect(text('a')).toBe(DEFAULT_SHAPE.join('|'));
  });
});

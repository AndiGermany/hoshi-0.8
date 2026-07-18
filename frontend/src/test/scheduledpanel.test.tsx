/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { ScheduledPanel } from '../components/ScheduledPanel';
import {
  deleteScheduledItem,
  cancelAllScheduled,
  scheduledItemPrimary,
  dueClock,
  parseScheduledItems,
  type ScheduledItem,
} from '../hooks/useScheduledItems';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

const NOW = 1_000_000_000;
const item = (over: Partial<ScheduledItem> = {}): ScheduledItem => ({
  id: 's-1',
  kind: 'TIMER',
  dueAtEpochMs: NOW + 12 * 60_000,
  ...over,
});

// ── Pure Helfer: primäre Zeit-Angabe (kind-ehrlich) ───────────────────────────

describe('scheduledItemPrimary — Restzeit bzw. Weck-Uhrzeit', () => {
  it('TIMER/REMINDER → Restzeit „noch …"', () => {
    expect(scheduledItemPrimary(item(), NOW)).toBe('noch 12 min');
    expect(scheduledItemPrimary(item({ kind: 'REMINDER', dueAtEpochMs: NOW + 44 * 60_000 }), NOW)).toBe(
      'noch 44 min',
    );
  });

  it('ALARM → Weck-Uhrzeit „um HH:MM" (nicht Restzeit)', () => {
    const due = NOW + 9 * 60 * 60_000;
    expect(scheduledItemPrimary(item({ kind: 'ALARM', dueAtEpochMs: due }), NOW)).toBe(
      `um ${dueClock(due)}`,
    );
  });

  it('fällig/überfällig → nie negativ (noch unter 1 min)', () => {
    expect(scheduledItemPrimary(item({ dueAtEpochMs: NOW - 5000 }), NOW)).toBe('noch unter 1 min');
  });
});

describe('parseScheduledItems — remainingSeconds additiv, nie negativ', () => {
  it('übernimmt remainingSeconds, klemmt Negatives auf 0, ignoriert es sonst', () => {
    const [a] = parseScheduledItems([{ id: 'x', kind: 'TIMER', dueAtEpochMs: 1, remainingSeconds: 42 }]);
    expect(a.remainingSeconds).toBe(42);
    const [b] = parseScheduledItems([{ id: 'y', kind: 'TIMER', dueAtEpochMs: 1, remainingSeconds: -7 }]);
    expect(b.remainingSeconds).toBe(0);
    const [c] = parseScheduledItems([{ id: 'z', kind: 'TIMER', dueAtEpochMs: 1 }]);
    expect(c.remainingSeconds).toBeUndefined();
  });
});

// ── DELETE-Seams (best-effort, hinter der Token-Wand) ─────────────────────────

describe('deleteScheduledItem — DELETE …/{id}', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('204 → true; ruft DELETE mit URL-encodierter id', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 204 });
    vi.stubGlobal('fetch', fetchMock);
    expect(await deleteScheduledItem('s 1/x')).toBe(true);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/scheduled/s%201%2Fx');
    expect(init.method).toBe('DELETE');
  });

  it('404 (schon weg) → true; 500/Netz → false', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }));
    expect(await deleteScheduledItem('s-1')).toBe(true);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    expect(await deleteScheduledItem('s-1')).toBe(false);
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    expect(await deleteScheduledItem('s-1')).toBe(false);
  });
});

describe('cancelAllScheduled — DELETE …/scheduled → {count}', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('200 + {count:N} → N; DELETE auf den Sammel-Pfad', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve({ count: 3 }) });
    vi.stubGlobal('fetch', fetchMock);
    expect(await cancelAllScheduled()).toBe(3);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url.endsWith('/api/v1/scheduled')).toBe(true);
    expect(init.method).toBe('DELETE');
  });

  it('Fehler/kaputter Body → 0', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    expect(await cancelAllScheduled()).toBe(0);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve({}) }));
    expect(await cancelAllScheduled()).toBe(0);
  });
});

// ── Render-Vertrag (eingeklappt/leer) via Static-Markup ───────────────────────

describe('ScheduledPanel — Render-Vertrag (eingeklappt)', () => {
  const render = (items: ScheduledItem[]) =>
    renderToStaticMarkup(<ScheduledPanel items={items} nowMs={NOW} onDelete={() => {}} />);

  it('leer UND eingeklappt → rendert NICHTS (kein Lärm)', () => {
    expect(render([])).toBe('');
  });

  it('mit Items → ruhige Zusammenfassung als Aufklapp-Knopf, noch KEINE Liste', () => {
    const html = render([item()]);
    expect(html).toContain('sched__toggle');
    expect(html).toContain('Timer · noch 12 min');
    expect(html).toContain('aria-expanded="false"');
    expect(html).not.toContain('sched__list'); // erst nach dem Aufklappen
  });
});

// ── Interaktion (jsdom): aufklappen, löschen, alle löschen ────────────────────

describe('ScheduledPanel — aufklappen + Löschen ruft die Callbacks', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const mount = async (el: React.ReactElement): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(el);
    });
  };
  const click = async (sel: string, idx = 0): Promise<void> => {
    await act(async () => {
      container.querySelectorAll<HTMLButtonElement>(sel)[idx].click();
    });
  };

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

  it('Aufklappen zeigt je Item eine Zeile mit ✕; ✕ ruft onDelete(id)', async () => {
    const onDelete = vi.fn();
    await mount(
      <ScheduledPanel
        items={[item({ id: 'a', label: 'Tee' }), item({ id: 'b', kind: 'ALARM' })]}
        nowMs={NOW}
        onDelete={onDelete}
      />,
    );
    // eingeklappt: keine Liste
    expect(container.querySelector('.sched__list')).toBeNull();
    await click('.sched__toggle');
    // aufgeklappt: zwei Zeilen
    expect(container.querySelectorAll('.sched__row')).toHaveLength(2);
    expect(container.textContent).toContain('Tee');
    // ✕ der ersten Zeile
    await click('.sched__del', 0);
    expect(onDelete).toHaveBeenCalledWith('a');
  });

  it('„alle löschen" (bei >1 Items) ruft onDeleteAll', async () => {
    const onDeleteAll = vi.fn();
    await mount(
      <ScheduledPanel
        items={[item({ id: 'a' }), item({ id: 'b' })]}
        nowMs={NOW}
        onDelete={() => {}}
        onDeleteAll={onDeleteAll}
      />,
    );
    await click('.sched__toggle');
    await click('.sched__delall');
    expect(onDeleteAll).toHaveBeenCalledTimes(1);
  });

  it('aufgeklappt + leer → dezent „nichts aktiv"', async () => {
    // Erst mit einem Item mounten und aufklappen, dann auf leer re-rendern.
    await mount(<ScheduledPanel items={[item()]} nowMs={NOW} onDelete={() => {}} />);
    await click('.sched__toggle');
    await act(async () => {
      root!.render(<ScheduledPanel items={[]} nowMs={NOW} onDelete={() => {}} />);
    });
    expect(container.querySelector('.sched__empty')?.textContent).toBe('nichts aktiv');
  });
});

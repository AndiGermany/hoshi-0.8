import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { ScheduledLine } from '../components/ChatView';
import {
  parseScheduledItems,
  fetchScheduledItems,
  sameSchedule,
  fmtRemaining,
  scheduledLine,
  type ScheduledItem,
} from '../hooks/useScheduledItems';

// ── Test-Hilfen ───────────────────────────────────────────────────────────────

const NOW = 1_000_000_000; // fixe „Uhr" — der Countdown ist pur (nowMs-Parameter)

/** Gültiges aktives Item, per `over` punktuell überschreibbar. */
const item = (over: Partial<ScheduledItem> = {}): ScheduledItem => ({
  id: 's-1',
  kind: 'TIMER',
  dueAtEpochMs: NOW + 44 * 60_000,
  ...over,
});

/** Wire-Form eines Items (wie der Server sie schickt, label optional). */
const wire = (over: Record<string, unknown> = {}): Record<string, unknown> => ({
  id: 's-1',
  kind: 'TIMER',
  dueAtEpochMs: NOW + 44 * 60_000,
  ...over,
});

// ── parseScheduledItems — Wire-Vertrag ────────────────────────────────────────

describe('parseScheduledItems — Wire-Vertrag', () => {
  it('leeres Array / kein Array / Müll → [] (still, nie eine kaputte Zeile)', () => {
    expect(parseScheduledItems([])).toEqual([]);
    expect(parseScheduledItems(null)).toEqual([]);
    expect(parseScheduledItems('nope')).toEqual([]);
    expect(parseScheduledItems({ id: 'x' })).toEqual([]);
  });

  it('gültige Items werden geparst; label fehlt bei null (Optional-Contract)', () => {
    const items = parseScheduledItems([wire(), wire({ id: 's-2', kind: 'ALARM', label: 'Aufstehen' })]);
    expect(items).toHaveLength(2);
    expect(items[0]).toEqual(item());
    expect(items[0].label).toBeUndefined();
    expect(items[1].kind).toBe('ALARM');
    expect(items[1].label).toBe('Aufstehen');
  });

  it('Müll-Einträge/fehlende id werden verworfen, der Rest überlebt', () => {
    const items = parseScheduledItems([null, 42, wire({ id: '' }), wire({ id: 'ok' })]);
    expect(items).toHaveLength(1);
    expect(items[0].id).toBe('ok');
  });

  it('sortiert aufsteigend nach Fälligkeit („der nächste zuerst" als FE-Invariant)', () => {
    const items = parseScheduledItems([
      wire({ id: 'spät', dueAtEpochMs: 9_000 }),
      wire({ id: 'früh', dueAtEpochMs: 1_000 }),
    ]);
    expect(items.map((i) => i.id)).toEqual(['früh', 'spät']);
  });

  it('unbekannte kind fällt auf TIMER zurück', () => {
    expect(parseScheduledItems([wire({ kind: 'POMODORO' })])[0].kind).toBe('TIMER');
  });
});

// ── fetchScheduledItems — best-effort, Token-Wand ─────────────────────────────

describe('fetchScheduledItems — best-effort, graceful', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('401/404 → [] (Token-Wand/Feature fehlt → still, die Zeile verschwindet)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    expect(await fetchScheduledItems()).toEqual([]);
  });

  it('Netzfehler → []', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    expect(await fetchScheduledItems()).toEqual([]);
  });

  it('200 + Items → geparst; ruft den scheduled-Endpoint mit Accept-Header', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve([wire({ label: 'Tee' })]),
    });
    vi.stubGlobal('fetch', fetchMock);
    const items = await fetchScheduledItems();
    expect(items).toHaveLength(1);
    expect(items[0].label).toBe('Tee');
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/scheduled');
    expect(url).not.toContain('/fired'); // die AKTIVEN, nicht die Klingel-Naht
    expect((init.headers as Record<string, string>).Accept).toBe('application/json');
  });

  it('200 + leeres Array → [] (nichts läuft = keine Zeile)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) }));
    expect(await fetchScheduledItems()).toEqual([]);
  });
});

// ── sameSchedule — Referenz-Ruhe beim Poll ────────────────────────────────────

describe('sameSchedule — gleicher Stand heißt kein Re-render', () => {
  it('identischer Inhalt → true (der Hook behält die alte Referenz)', () => {
    expect(sameSchedule([item()], [item()])).toBe(true);
    expect(sameSchedule([], [])).toBe(true);
  });

  it('andere Länge / id / dueAt / kind / label → false', () => {
    expect(sameSchedule([item()], [])).toBe(false);
    expect(sameSchedule([item()], [item({ id: 'anders' })])).toBe(false);
    expect(sameSchedule([item()], [item({ dueAtEpochMs: NOW + 1 })])).toBe(false);
    expect(sameSchedule([item()], [item({ kind: 'ALARM' })])).toBe(false);
    expect(sameSchedule([item()], [item({ label: 'Tee' })])).toBe(false);
  });
});

// ── fmtRemaining + scheduledLine — der ruhige Text ────────────────────────────

describe('fmtRemaining — aufgerundete, menschliche Restdauer', () => {
  it('unter einer Minute / Minuten / Stunden', () => {
    expect(fmtRemaining(30_000)).toBe('unter 1 min');
    expect(fmtRemaining(44 * 60_000)).toBe('44 min');
    expect(fmtRemaining(60 * 60_000)).toBe('1 h');
    expect(fmtRemaining(72 * 60_000)).toBe('1 h 12 min');
  });

  it('rundet AUF (43,5 min → 44 min — lieber eine Minute zu viel als zu wenig)', () => {
    expect(fmtRemaining(43.5 * 60_000)).toBe('44 min');
  });
});

describe('scheduledLine — kind-ehrlich, ruhig', () => {
  it('keine Items → null (NICHTS rendern)', () => {
    expect(scheduledLine([], NOW)).toBeNull();
  });

  it('ein Timer → „Timer · noch 44 min"', () => {
    expect(scheduledLine([item()], NOW)).toBe('Timer · noch 44 min');
  });

  it('ein Wecker → „Wecker · …" (kind-ehrlich, nie „Timer" für einen ALARM)', () => {
    expect(scheduledLine([item({ kind: 'ALARM', dueAtEpochMs: NOW + 9 * 60 * 60_000 })], NOW)).toBe(
      'Wecker · noch 9 h',
    );
  });

  it('mehrere gleicher Art → „2 Timer · nächster in 12 min" (der früheste zählt)', () => {
    const items = [
      item({ id: 'a', dueAtEpochMs: NOW + 12 * 60_000 }),
      item({ id: 'b', dueAtEpochMs: NOW + 44 * 60_000 }),
    ];
    expect(scheduledLine(items, NOW)).toBe('2 Timer · nächster in 12 min');
  });

  it('gemischte Arten → „2 Timer/Wecker · nächster in …"', () => {
    const items = [
      item({ id: 'a', dueAtEpochMs: NOW + 12 * 60_000 }),
      item({ id: 'b', kind: 'ALARM', dueAtEpochMs: NOW + 44 * 60_000 }),
    ];
    expect(scheduledLine(items, NOW)).toBe('2 Timer/Wecker · nächster in 12 min');
  });
});

// ── ScheduledLine — Render-Vertrag ────────────────────────────────────────────

const render = (items: ScheduledItem[]) =>
  renderToStaticMarkup(<ScheduledLine items={items} nowMs={NOW} />);

describe('ScheduledLine — Render-Vertrag', () => {
  it('keine Items → rendert NICHTS (kein Lärm)', () => {
    expect(render([])).toBe('');
  });

  it('ein Timer → ruhige role=status-Zeile mit Countdown, OHNE ⏱-Glyph (Emoji-Sweep)', () => {
    const html = render([item()]);
    expect(html).toContain('chat__scheduled');
    expect(html).toContain('role="status"');
    expect(html).toContain('Timer · noch 44 min');
    expect(html).not.toContain('⏱'); // „Timer" steht im Text — das Glyph war ein Duplikat
    expect(html).not.toContain('role="alert"'); // ruhig — kein Alarm-Look
  });

  it('Wecker bleibt Wecker; mehrere zeigen Anzahl + nächsten Countdown', () => {
    expect(render([item({ kind: 'ALARM' })])).toContain('Wecker · noch 44 min');
    const html = render([
      item({ id: 'a', dueAtEpochMs: NOW + 12 * 60_000 }),
      item({ id: 'b', dueAtEpochMs: NOW + 44 * 60_000 }),
    ]);
    expect(html).toContain('2 Timer · nächster in 12 min');
  });
});

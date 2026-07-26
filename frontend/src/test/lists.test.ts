import { describe, it, expect, vi, afterEach } from 'vitest';
import { fetchListItems, parseListItems, type ListItem } from '../api/lists';

// ── Test-Hilfen ───────────────────────────────────────────────────────────────

/** Wire-Form eines Eintrags (wie ListsController sie schickt), punktuell überschreibbar. */
const wire = (over: Record<string, unknown> = {}): Record<string, unknown> => ({
  id: 'i-1',
  text: 'Milch',
  quantity: 1,
  addedAtEpochMs: 1_000,
  ...over,
});

// ── parseListItems — Wire-Vertrag ─────────────────────────────────────────────

describe('parseListItems — Wire-Vertrag (GET /api/v1/lists)', () => {
  it('leeres Array / kein Array / Müll → [] (still, nie eine kaputte Zeile)', () => {
    expect(parseListItems([])).toEqual([]);
    expect(parseListItems(null)).toEqual([]);
    expect(parseListItems('nope')).toEqual([]);
    expect(parseListItems({ id: 'x' })).toEqual([]);
  });

  it('gültige Items werden geparst — inklusive der echten quantity', () => {
    const items = parseListItems([wire(), wire({ id: 'i-2', text: 'Brot', quantity: 2 })]);
    expect(items).toHaveLength(2);
    expect(items[0]).toEqual<ListItem>({ id: 'i-1', text: 'Milch', quantity: 1, addedAtEpochMs: 1_000 });
    expect(items[1].quantity).toBe(2); // die „2×"-Dedupe-Zählung kommt 1:1 durch
  });

  it('quantity fehlt/ungültig ⇒ 1 (ein echtes Item ohne Zähler ist genau EIN Stück)', () => {
    expect(parseListItems([wire({ quantity: undefined })])[0].quantity).toBe(1);
    expect(parseListItems([wire({ quantity: 0 })])[0].quantity).toBe(1);
    expect(parseListItems([wire({ quantity: -3 })])[0].quantity).toBe(1);
    expect(parseListItems([wire({ quantity: 'zwei' })])[0].quantity).toBe(1);
  });

  it('Müll-Einträge/fehlende id/leerer Text werden verworfen, der Rest überlebt', () => {
    const items = parseListItems([
      null,
      42,
      wire({ id: '' }),
      wire({ id: 'ok-1', text: '' }),
      wire({ id: 'ok-2' }),
    ]);
    expect(items).toHaveLength(1);
    expect(items[0].id).toBe('ok-2');
  });

  it('sortiert aufsteigend nach addedAtEpochMs („ältestes zuerst" als FE-Invariant)', () => {
    const items = parseListItems([
      wire({ id: 'spät', addedAtEpochMs: 9_000 }),
      wire({ id: 'früh', addedAtEpochMs: 1_000 }),
    ]);
    expect(items.map((i) => i.id)).toEqual(['früh', 'spät']);
  });
});

// ── fetchListItems — best-effort, Token-Wand ──────────────────────────────────

describe('fetchListItems — best-effort, graceful (die Karte verschwindet, statt einen Fehler zu zeigen)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('401/404/5xx → [] (Token-Wand/Feature-aus/Serverfehler → still, keine Karte)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    expect(await fetchListItems()).toEqual([]);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    expect(await fetchListItems()).toEqual([]);
  });

  it('Netzfehler → [] (kein Fehler-Banner im Flur)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    expect(await fetchListItems()).toEqual([]);
  });

  it('kaputtes JSON → [] statt einer geworfenen Exception', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.reject(new Error('bad json')) }),
    );
    expect(await fetchListItems()).toEqual([]);
  });

  it('200 + Items → geparst; ruft /api/v1/lists mit Accept-Header', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve([wire({ text: 'Käse', quantity: 3 })]),
    });
    vi.stubGlobal('fetch', fetchMock);
    const items = await fetchListItems();
    expect(items).toHaveLength(1);
    expect(items[0].text).toBe('Käse');
    expect(items[0].quantity).toBe(3);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/lists');
    expect((init.headers as Record<string, string>).Accept).toBe('application/json');
  });

  it('200 + leeres Array → [] (leere Liste = keine Karte)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) }));
    expect(await fetchListItems()).toEqual([]);
  });
});

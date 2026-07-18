/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { useFiredItems, FIRED_TITLE, TITLE_BLINK_MS } from '../hooks/useFiredItems';
import { useScheduledItems } from '../hooks/useScheduledItems';
import { playAlarmChime, CHIME_REPEAT_MS } from '../audio/chime';

// ── Warum diese Datei existiert (Regression 2026-07-02) ───────────────────────
// Die reinen Seam-Tests (fired.test.tsx / scheduled.test.tsx) prüfen parse/
// fetch/merge — aber NIE den Effect selbst: renderToStaticMarkup führt keine
// Effects aus. So überlebte ein visibility-Gate im tick, das unter Chromes
// Window-Occlusion (verdecktes Fenster ⇒ auch der aktive Tab meldet `hidden`)
// JEDEN Poll inkl. Initial-Fetch blockierte — live: null Requests an
// /api/v1/scheduled(/fired), während die gate-freien Hooks (useOpsStatus/
// useHealth) sichtbar pollten. Diese Tests MOUNTEN die Hooks echt (jsdom,
// fake timers) und beweisen: Initial-Fetch feuert, das Interval läuft, das
// Cleanup stoppt — und zwar AUCH bei document.visibilityState === 'hidden'.
//
// Ring-1-Fix 2026-07-03 („der Timer hat heute nicht geklappt"): der Server ist
// jetzt idempotent (kein consume-once), quittiert wird per ack-POST, der Chime
// wiederholt solange unbestätigt, und bei Autoplay-Sperre blinkt der Tab-Titel.
// Auch DAS sind Effect-Verhalten — also leben die Beweise hier.

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// Chime als Modul-Mock: deterministisch „unhörbar" (Autoplay-Sperre wie in
// jsdom real — kein AudioContext), Aufrufe zählbar. Konstanten bleiben echt.
vi.mock('../audio/chime', async (importOriginal) => {
  const orig = await importOriginal<typeof import('../audio/chime')>();
  return { ...orig, playAlarmChime: vi.fn(() => false) };
});

/** Host-Komponenten: mounten genau einen Hook, rendern nichts. */
function FiredHost({ intervalMs }: { intervalMs: number }) {
  useFiredItems(intervalMs);
  return null;
}
/** Host mit sichtbarem State: macht items/ack/silenced im DOM prüfbar. */
function FiredStateHost({ intervalMs }: { intervalMs: number }) {
  const { items, ack, silenced } = useFiredItems(intervalMs);
  return (
    <div>
      <span data-testid="fired-ids">{items.map((i) => i.id).join(',')}</span>
      <span data-testid="fired-silenced">{String(silenced)}</span>
      <button type="button" data-testid="fired-ack" onClick={ack} />
    </div>
  );
}
function ScheduledHost({ intervalMs }: { intervalMs: number }) {
  useScheduledItems(intervalMs);
  return null;
}

/** `document.visibilityState` erzwingen (jsdom-Default ist 'visible'). */
function forceVisibility(state: DocumentVisibilityState): void {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
  Object.defineProperty(document, 'hidden', { value: state !== 'visible', configurable: true });
}
function resetVisibility(): void {
  // Eigene (instanz-)Properties löschen → die Prototype-Getter greifen wieder.
  delete (document as unknown as Record<string, unknown>).visibilityState;
  delete (document as unknown as Record<string, unknown>).hidden;
}

/** Wire-Item der Klingel-Naht (Server-Format, missed default false). */
const wireItem = (id = 'f-1', missed = false) => ({
  id,
  kind: 'ALARM',
  label: 'Kuchen',
  dueAtEpochMs: 1,
  firedAtEpochMs: 2,
  missed,
});

describe('Poll-Verdrahtung der Scheduled-Hooks (Effect + fake timers)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;
  let fetchMock: ReturnType<typeof vi.fn>;

  const mount = async (el: React.ReactElement): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(el);
    });
  };
  const unmount = async (): Promise<void> => {
    if (root) {
      const r = root;
      await act(async () => {
        r.unmount();
      });
      root = null;
    }
  };
  /** Fake-Uhr vordrehen und Microtasks (async tick) innerhalb von act flushen. */
  const advance = async (ms: number): Promise<void> => {
    await act(async () => {
      vi.advanceTimersByTime(ms);
    });
  };
  const ids = (): string => container.querySelector('[data-testid="fired-ids"]')?.textContent ?? '';
  const clickAck = async (): Promise<void> => {
    await act(async () => {
      container.querySelector<HTMLButtonElement>('[data-testid="fired-ack"]')!.click();
    });
  };
  /** Nur die GET-Polls auf …/scheduled/fired (ack-POSTs zählen extra). */
  const firedGets = (): number =>
    fetchMock.mock.calls.filter(
      ([url, init]) =>
        String(url).includes('/scheduled/fired') && (init as RequestInit | undefined)?.method !== 'POST',
    ).length;
  const ackPosts = (): [string, RequestInit][] =>
    fetchMock.mock.calls.filter(
      ([, init]) => (init as RequestInit | undefined)?.method === 'POST',
    ) as [string, RequestInit][];

  beforeEach(() => {
    vi.useFakeTimers();
    container = document.createElement('div');
    document.body.appendChild(container);
    fetchMock = vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) });
    vi.stubGlobal('fetch', fetchMock);
    vi.mocked(playAlarmChime).mockClear();
    vi.mocked(playAlarmChime).mockReturnValue(false); // jsdom-ehrlich: kein Web-Audio
  });

  afterEach(async () => {
    await unmount();
    container.remove();
    resetVisibility();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  // ── useFiredItems — Klingel-Poll (/api/v1/scheduled/fired) ─────────────────

  it('useFiredItems: Mount feuert SOFORT den Initial-Fetch auf …/scheduled/fired', async () => {
    await mount(<FiredHost intervalMs={5000} />);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/scheduled/fired');
  });

  it('useFiredItems: Interval pollt weiter (~5s); Unmount-Cleanup stoppt es', async () => {
    await mount(<FiredHost intervalMs={5000} />);
    await advance(5000);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await advance(5000);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    await unmount();
    await advance(20_000);
    expect(fetchMock).toHaveBeenCalledTimes(3); // nach Cleanup kein Poll mehr
  });

  it('useFiredItems REGRESSION: pollt auch bei visibilityState=hidden (Window-Occlusion)', async () => {
    forceVisibility('hidden'); // verdecktes Chrome-Fenster: aktiver Tab meldet hidden
    expect(document.hidden).toBe(true);
    await mount(<FiredHost intervalMs={5000} />);
    expect(fetchMock).toHaveBeenCalledTimes(1); // Initial-Fetch trotz hidden
    await advance(5000);
    expect(fetchMock).toHaveBeenCalledTimes(2); // Interval läuft trotz hidden
  });

  it('Banner BLEIBT ohne ack — solange der Server das Item liefert, kein Auto-Dismiss', async () => {
    // Der Server ist jetzt idempotent: er liefert das unbestätigte Item bei
    // JEDEM Poll wieder (jeder Tab sieht es). Ohne ack darf nichts verschwinden.
    fetchMock.mockResolvedValue({ ok: true, json: () => Promise.resolve([wireItem()]) });
    await mount(<FiredStateHost intervalMs={5000} />);
    expect(ids()).toBe('f-1');

    await advance(5000);
    await advance(5000);
    expect(firedGets()).toBeGreaterThanOrEqual(3);
    expect(ids()).toBe('f-1'); // klingelt weiter — niemand hat bestätigt
  });

  it('Fehl-Poll (Netzfehler) räumt NICHT — ein Funkloch stoppt keinen Wecker', async () => {
    fetchMock.mockResolvedValueOnce({ ok: true, json: () => Promise.resolve([wireItem()]) });
    fetchMock.mockRejectedValueOnce(new Error('offline'));
    fetchMock.mockResolvedValue({ ok: true, json: () => Promise.resolve([wireItem()]) });
    await mount(<FiredStateHost intervalMs={5000} />);
    expect(ids()).toBe('f-1');

    await advance(5000); // der Fehl-Poll
    expect(ids()).toBe('f-1'); // Zustand gehalten, Banner klingelt weiter
  });

  it('leerer Poll = Server-Wahrheit: von einem ANDEREN Tab quittiert ⇒ Banner weg', async () => {
    fetchMock.mockResolvedValueOnce({ ok: true, json: () => Promise.resolve([wireItem()]) });
    // Default-Mock liefert danach [] — serverseitig quittiert (anderer Tab).
    await mount(<FiredStateHost intervalMs={5000} />);
    expect(ids()).toBe('f-1');

    await advance(5000);
    expect(ids()).toBe(''); // ack macht es für BEIDE weg — auch hier
  });

  it('Tap = ack-POST auf …/fired/{id}/ack; Server-Race bringt das Item nicht zurück', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return Promise.resolve({ ok: true, status: 204 });
      // GET liefert das Item WEITER (Race: das ack ist noch nicht verdaut).
      return Promise.resolve({ ok: true, json: () => Promise.resolve([wireItem()]) });
    });
    await mount(<FiredStateHost intervalMs={5000} />);
    expect(ids()).toBe('f-1');

    await clickAck();
    expect(ids()).toBe(''); // optimistisch sofort weg
    const posts = ackPosts();
    expect(posts).toHaveLength(1); // genau EIN ack-POST pro Item
    expect(posts[0][0]).toContain('/api/v1/scheduled/fired/f-1/ack');

    await advance(5000); // Race: der Server liefert f-1 noch einmal
    expect(ids()).toBe(''); // bereits quittiert ⇒ taucht NICHT wieder auf
  });

  it('Chime wiederholt alle ~4s solange unbestätigt; ack stoppt die Schleife', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: () => Promise.resolve([wireItem()]) });
    await mount(<FiredStateHost intervalMs={60_000} />); // Poll aus dem Weg
    expect(vi.mocked(playAlarmChime).mock.calls.length).toBe(1); // sofort beim Feuern

    await advance(CHIME_REPEAT_MS);
    expect(vi.mocked(playAlarmChime).mock.calls.length).toBe(2); // …und wieder
    await advance(CHIME_REPEAT_MS);
    expect(vi.mocked(playAlarmChime).mock.calls.length).toBe(3); // …und wieder

    fetchMock.mockResolvedValue({ ok: true, json: () => Promise.resolve([]) });
    await clickAck();
    const after = vi.mocked(playAlarmChime).mock.calls.length;
    await advance(4 * CHIME_REPEAT_MS);
    expect(vi.mocked(playAlarmChime).mock.calls.length).toBe(after); // Ruhe nach ack
  });

  it('Autoplay-Sperre (Chime unhörbar) ⇒ silenced=true + Tab-Titel blinkt „⏰ Timer!"; ack stellt zurück', async () => {
    document.title = 'Hoshi';
    fetchMock.mockResolvedValue({ ok: true, json: () => Promise.resolve([wireItem()]) });
    await mount(<FiredStateHost intervalMs={60_000} />);

    // playAlarmChime meldet false (jsdom hat kein Web-Audio) ⇒ ehrlich eskalieren:
    expect(container.querySelector('[data-testid="fired-silenced"]')?.textContent).toBe('true');
    expect(document.title).toBe(FIRED_TITLE);
    await advance(TITLE_BLINK_MS);
    expect(document.title).toBe('Hoshi'); // Blink-Takt: zurück …
    await advance(TITLE_BLINK_MS);
    expect(document.title).toBe(FIRED_TITLE); // … und wieder Alarm

    fetchMock.mockResolvedValue({ ok: true, json: () => Promise.resolve([]) });
    await clickAck();
    expect(document.title).toBe('Hoshi'); // Cleanup stellt den Titel zurück
  });

  it('hörbarer Chime (Geste hat entsperrt) ⇒ silenced=false, KEIN Titel-Blinken', async () => {
    vi.mocked(playAlarmChime).mockReturnValue(true); // Context running
    document.title = 'Hoshi';
    fetchMock.mockResolvedValue({ ok: true, json: () => Promise.resolve([wireItem()]) });
    await mount(<FiredStateHost intervalMs={60_000} />);

    expect(container.querySelector('[data-testid="fired-silenced"]')?.textContent).toBe('false');
    await advance(TITLE_BLINK_MS);
    expect(document.title).toBe('Hoshi'); // Titel bleibt in Ruhe
  });

  // ── useScheduledItems — Timer-Zeile (/api/v1/scheduled) ────────────────────

  it('useScheduledItems: Mount feuert SOFORT den Initial-Fetch auf …/scheduled', async () => {
    await mount(<ScheduledHost intervalMs={15_000} />);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url.endsWith('/api/v1/scheduled')).toBe(true); // nicht der fired-Endpoint
  });

  it('useScheduledItems: Interval pollt weiter (~15s); Unmount-Cleanup stoppt es', async () => {
    await mount(<ScheduledHost intervalMs={15_000} />);
    await advance(15_000);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await unmount();
    await advance(60_000);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('useScheduledItems REGRESSION: pollt auch bei visibilityState=hidden (Window-Occlusion)', async () => {
    forceVisibility('hidden');
    await mount(<ScheduledHost intervalMs={15_000} />);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await advance(15_000);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});

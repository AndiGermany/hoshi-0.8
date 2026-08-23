/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest';
import { startVisiblePolling } from '../hooks/visiblePolling';

// ── Warum diese Datei existiert ───────────────────────────────────────────────
// startVisiblePolling ist das eine Stück, an dem acht Poller hängen — Health,
// Ops, Einkaufsliste, Home-Registry, Räume, Wetter, Lagebild, Scheduled. Ein
// Fehler hier ist ein Fehler in allen achten gleichzeitig, darum steht der
// Kontrakt hier isoliert und nicht nur indirekt über einen der Hooks.
//
// Der Kontrakt hat bewusst zwei Hälften, und die zweite ist die, an der das
// Projekt 2026-07-02 schon einmal gescheitert ist (Gate saß IM tick und
// blockierte auch den Initial-Fetch, siehe polling.test.tsx):
//   1. `hidden` pausiert das INTERVALL.
//   2. `hidden` blockiert NICHTS sonst — der Initial-Fetch liegt beim Aufrufer
//      und läuft immer; Sichtbarwerden holt sofort nach.

/** `document.visibilityState`/`hidden` erzwingen (jsdom-Default ist 'visible'). */
function forceVisibility(state: DocumentVisibilityState): void {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
  Object.defineProperty(document, 'hidden', { value: state !== 'visible', configurable: true });
}
function resetVisibility(): void {
  delete (document as unknown as Record<string, unknown>).visibilityState;
  delete (document as unknown as Record<string, unknown>).hidden;
}
/** Sichtbarkeit umschalten UND das echte Event feuern — daran hängt das Gate. */
function setVisibility(state: DocumentVisibilityState): void {
  forceVisibility(state);
  document.dispatchEvent(new Event('visibilitychange'));
}

describe('startVisiblePolling', () => {
  let tick: Mock<() => void>;
  let stop: (() => void) | null = null;

  beforeEach(() => {
    vi.useFakeTimers();
    tick = vi.fn<() => void>();
  });

  afterEach(() => {
    stop?.();
    stop = null;
    resetVisibility();
    vi.useRealTimers();
  });

  it('sichtbar: taktet unverändert im Intervall', () => {
    stop = startVisiblePolling(tick, 1000);
    expect(tick).toHaveBeenCalledTimes(0); // der Initial-Fetch gehört dem Aufrufer

    vi.advanceTimersByTime(1000);
    expect(tick).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(3000);
    expect(tick).toHaveBeenCalledTimes(4);
  });

  it('hidden beim Start: das Intervall läuft gar nicht erst an', () => {
    forceVisibility('hidden');
    stop = startVisiblePolling(tick, 1000);

    vi.advanceTimersByTime(60_000);
    expect(tick).toHaveBeenCalledTimes(0); // dunkles Display kostet keinen Request
  });

  it('sichtbar -> hidden: das Intervall stoppt', () => {
    stop = startVisiblePolling(tick, 1000);
    vi.advanceTimersByTime(2000);
    expect(tick).toHaveBeenCalledTimes(2);

    setVisibility('hidden');
    vi.advanceTimersByTime(60_000);
    expect(tick).toHaveBeenCalledTimes(2); // kein einziger Tick mehr
  });

  it('hidden -> sichtbar: holt SOFORT nach und taktet wieder', () => {
    forceVisibility('hidden');
    stop = startVisiblePolling(tick, 1000);
    vi.advanceTimersByTime(60_000);
    expect(tick).toHaveBeenCalledTimes(0);

    setVisibility('visible');
    expect(tick).toHaveBeenCalledTimes(1); // sofort frisch, nicht erst in 1000ms

    vi.advanceTimersByTime(1000);
    expect(tick).toHaveBeenCalledTimes(2); // Intervall läuft wieder
  });

  it('visibilitychange im sichtbaren Zustand löst KEINEN Extra-Fetch aus', () => {
    stop = startVisiblePolling(tick, 1000);
    setVisibility('visible');
    setVisibility('visible');
    expect(tick).toHaveBeenCalledTimes(0); // nur echte hidden->visible-Wechsel holen nach

    vi.advanceTimersByTime(1000);
    expect(tick).toHaveBeenCalledTimes(1); // und weiterhin genau EIN Intervall
  });

  it('mehrfaches hidden->visible startet kein zweites Intervall', () => {
    stop = startVisiblePolling(tick, 1000);
    setVisibility('hidden');
    setVisibility('visible');
    setVisibility('hidden');
    setVisibility('visible');
    expect(tick).toHaveBeenCalledTimes(2); // je einmal pro Sichtbarwerden

    vi.advanceTimersByTime(1000);
    expect(tick).toHaveBeenCalledTimes(3); // +1, nicht +2 — nur ein Intervall läuft
  });

  it('Cleanup stoppt das Intervall UND meldet den Listener ab', () => {
    const cleanup = startVisiblePolling(tick, 1000);
    vi.advanceTimersByTime(1000);
    expect(tick).toHaveBeenCalledTimes(1);

    cleanup();
    vi.advanceTimersByTime(60_000);
    expect(tick).toHaveBeenCalledTimes(1);

    // Ohne removeEventListener tickte ein längst unmounteter Hook hier nach.
    setVisibility('hidden');
    setVisibility('visible');
    vi.advanceTimersByTime(60_000);
    expect(tick).toHaveBeenCalledTimes(1);
  });

  it('Cleanup ist mehrfach aufrufbar, ohne zu werfen', () => {
    const cleanup = startVisiblePolling(tick, 1000);
    cleanup();
    expect(() => cleanup()).not.toThrow();
  });
});

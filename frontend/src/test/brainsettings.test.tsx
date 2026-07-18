/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  BRAIN_MODEL_TEXTS,
  BRAIN_POLL_INTERVAL_MS,
  BRAIN_POLL_TIMEOUT_MS,
  BrainModelSection,
  BrainModelSectionView,
  type BrainModelSectionViewProps,
} from '../components/SettingsPanel';
import {
  type BrainSetting,
  BrainSwitchUnavailableError,
  UnknownBrainModelError,
  fetchBrainSettings,
  saveBrainModel,
} from '../api/brainSettings';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/** Gültiger Wire-Zustand (e2b aktiv, ok), per `over` punktuell überschreibbar. */
const setting = (over: Partial<BrainSetting> = {}): BrainSetting => ({
  aktiv: 'e2b',
  modelle: [
    { id: 'e2b', label: 'Gemma-4 E2B (Default, schnell)', repo: 'mlx-community/gemma-4-e2b-it-4bit' },
    { id: 'e4b', label: 'Gemma-4 E4B (gründlicher, mehr RAM)', repo: 'mlx-community/gemma-4-e4b-it-4bit' },
  ],
  status: 'ok',
  ...over,
});

const okResponse = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

const render = (over: Partial<BrainModelSectionViewProps> = {}) =>
  renderToStaticMarkup(<BrainModelSectionView current={setting()} onSelect={() => {}} {...over} />);

describe('BrainModelSectionView — Render (aus GET)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('zeigt beide Whitelist-Modelle + den Status des aktiven', () => {
    const html = render();
    expect(html).toContain(BRAIN_MODEL_TEXTS.label);
    expect(html).toContain('settings-brain-model');
    expect(html).toContain('Gemma-4 E2B');
    expect(html).toContain('Gemma-4 E4B');
    expect(html).toContain('Status: läuft');
  });

  it('status unreachable ⇒ ehrlicher Klartext statt roher Wire-String', () => {
    const html = render({ current: setting({ aktiv: '', status: 'unreachable' }) });
    expect(html).toContain('nicht erreichbar');
  });

  it('pending: der 60-120s-Hinweis mit dem Ziel-Label steht da, Select gesperrt', () => {
    const html = render({ pending: { id: 'e4b', label: 'Gemma-4 E4B (gründlicher, mehr RAM)' } });
    expect(html).toContain('wechselt zu Gemma-4 E4B');
    expect(html).toContain('60-120 Sekunden');
    expect(html).toContain('disabled');
  });

  it('Fehler-Notiz (Sidecar kann noch nicht umschalten) steht als role=status', () => {
    const html = render({ note: BRAIN_MODEL_TEXTS.switchUnavailable });
    expect(html).toContain(BRAIN_MODEL_TEXTS.switchUnavailable);
    expect(html).toContain('role="status"');
  });

  it('Lade-Fehler: ehrliche Zeile als role=alert', () => {
    const html = render({ current: null, error: BRAIN_MODEL_TEXTS.loadError });
    expect(html).toContain(BRAIN_MODEL_TEXTS.loadError);
    expect(html).toContain('role="alert"');
  });
});

describe('api/brainSettings — Wire-Vertrag', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('fetchBrainSettings: GET, parst aktiv/modelle/status', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse(setting()));
    vi.stubGlobal('fetch', fetchMock);

    const got = await fetchBrainSettings();
    expect(got.aktiv).toBe('e2b');
    expect(got.modelle).toHaveLength(2);
    expect(got.status).toBe('ok');
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/settings/brain');
  });

  it('saveBrainModel: PUT {id} — die Antwort ist der GEMESSENE (nicht der behauptete) Zustand', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse(setting({ aktiv: 'e2b', status: 'loading' })));
    vi.stubGlobal('fetch', fetchMock);

    const got = await saveBrainModel('e4b');
    // KEIN optimistisches UI: der Server hat den Wechsel nur ANGENOMMEN, das
    // Body zeigt ehrlich noch das alte Modell/loading, nicht "e4b/ok".
    expect(got.aktiv).toBe('e2b');
    expect(got.status).toBe('loading');
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('PUT');
    expect(init.body).toBe(JSON.stringify({ id: 'e4b' }));
  });

  it('422 ⇒ UnknownBrainModelError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 422 }));
    await expect(saveBrainModel('12b')).rejects.toThrowError(UnknownBrainModelError);
  });

  it('502 ⇒ BrainSwitchUnavailableError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 502 }));
    await expect(saveBrainModel('e4b')).rejects.toThrowError(BrainSwitchUnavailableError);
  });

  it('401 ⇒ ehrlicher Auth-Fehler', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(fetchBrainSettings()).rejects.toThrow(/401/);
  });
});

describe('BrainModelSection — Poll-Verhalten (Container, Scope-Erweiterung „Brain (LLM)")', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  beforeEach(() => {
    vi.useFakeTimers();
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
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('PUT stösst den Wechsel an; das FE pollt danach GET bis status=ok MIT dem neuen Modell', async () => {
    let call = 0;
    const fetchMock = vi.fn(async () => {
      call += 1;
      if (call === 1) return okResponse(setting({ aktiv: 'e2b', status: 'ok' })); // initiales GET beim Mount
      if (call === 2) return okResponse(setting({ aktiv: 'e2b', status: 'loading' })); // PUT-Antwort: angenommen, noch nicht fertig
      return okResponse(setting({ aktiv: 'e4b', status: 'ok' })); // erster Poll: fertig
    });
    vi.stubGlobal('fetch', fetchMock);

    root = createRoot(container);
    await act(async () => {
      root!.render(<BrainModelSection />);
    });
    await act(async () => {
      await Promise.resolve();
    });

    const select = container.querySelector('#settings-brain-model') as HTMLSelectElement;
    expect(select.value).toBe('e2b');

    await act(async () => {
      select.value = 'e4b';
      select.dispatchEvent(new Event('change', { bubbles: true }));
      await Promise.resolve();
    });

    // Nach dem PUT (angenommen, noch loading): der ehrliche 60-120s-Hinweis steht da.
    expect(container.textContent).toContain('wechselt zu Gemma-4 E4B');
    expect(select.disabled).toBe(true);

    // Ein Poll-Tick später liefert der (gefakte) Sidecar status=ok mit e4b — der
    // Poll stoppt, der Hinweis verschwindet, das Select zeigt das neue Modell.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(BRAIN_POLL_INTERVAL_MS);
    });

    expect(container.textContent).not.toContain('wechselt zu');
    expect(select.value).toBe('e4b');
    expect(select.disabled).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('kein Erfolg innerhalb des Timeouts ⇒ ehrlicher Timeout-Hinweis, kein endloses stilles Warten', async () => {
    const fetchMock = vi.fn(async () => okResponse(setting({ aktiv: 'e2b', status: 'loading' }))); // bleibt IMMER loading
    vi.stubGlobal('fetch', fetchMock);

    root = createRoot(container);
    await act(async () => {
      root!.render(<BrainModelSection />);
    });
    await act(async () => {
      await Promise.resolve();
    });

    const select = container.querySelector('#settings-brain-model') as HTMLSelectElement;
    await act(async () => {
      select.value = 'e4b';
      select.dispatchEvent(new Event('change', { bubbles: true }));
      await Promise.resolve();
    });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(BRAIN_POLL_TIMEOUT_MS + BRAIN_POLL_INTERVAL_MS);
    });

    expect(container.textContent).toContain(BRAIN_MODEL_TEXTS.timeout);
    expect(select.disabled).toBe(false);
  });
});

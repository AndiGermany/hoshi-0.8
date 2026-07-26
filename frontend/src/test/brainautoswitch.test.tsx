/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  BrainAutoSwitchSection,
  BrainAutoSwitchSectionView,
  BrainModelSectionView,
  type BrainAutoSwitchSectionViewProps,
} from '../components/SettingsPanel';
import {
  type BrainAutoSwitchSetting,
  fetchBrainAutoSwitch,
  saveBrainAutoSwitch,
} from '../api/brainAutoSwitch';
import { de } from '../i18n/de';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

const setting = (over: Partial<BrainAutoSwitchSetting> = {}): BrainAutoSwitchSetting => ({
  enabled: false,
  ...over,
});

const okResponse = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

const render = (over: Partial<BrainAutoSwitchSectionViewProps> = {}) =>
  renderToStaticMarkup(
    <BrainAutoSwitchSectionView enabled={false} onToggle={() => {}} {...over} />,
  );

describe('BrainAutoSwitchSectionView — Render', () => {
  it('zeigt Label + erklärenden Satz, Schalter AUS', () => {
    const html = render();
    expect(html).toContain(de.brainAutoSwitch.label);
    expect(html).toContain(de.brainAutoSwitch.hint);
    expect(html).toContain('role="switch"');
    expect(html).toContain('aria-checked="false"');
  });

  it('enabled=true ⇒ aria-checked=true + is-on-Klasse', () => {
    const html = render({ enabled: true });
    expect(html).toContain('aria-checked="true"');
    expect(html).toContain('is-on');
  });

  it('Fehler-Zeile steht als role=alert', () => {
    const html = render({ error: de.brainAutoSwitch.loadError });
    expect(html).toContain(de.brainAutoSwitch.loadError);
    expect(html).toContain('role="alert"');
  });

  it('Notiz (fehlgeschlagenes Umschalten) steht als role=status', () => {
    const html = render({ note: de.brainAutoSwitch.failed });
    expect(html).toContain(de.brainAutoSwitch.failed);
    expect(html).toContain('role="status"');
  });

  it('busy/loading sperren den Schalter (disabled)', () => {
    expect(render({ busy: true })).toContain('disabled');
    expect(render({ loading: true })).toContain('disabled');
  });
});

describe('api/brainAutoSwitch — Wire-Vertrag', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('fetchBrainAutoSwitch: GET, parst enabled', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse(setting({ enabled: true })));
    vi.stubGlobal('fetch', fetchMock);

    const got = await fetchBrainAutoSwitch();
    expect(got.enabled).toBe(true);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/settings/brain-auto-switch');
  });

  it('saveBrainAutoSwitch: PUT {enabled}', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse(setting({ enabled: true })));
    vi.stubGlobal('fetch', fetchMock);

    const got = await saveBrainAutoSwitch(true);
    expect(got.enabled).toBe(true);
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('PUT');
    expect(init.body).toBe(JSON.stringify({ enabled: true }));
  });

  it('401 ⇒ ehrlicher Auth-Fehler', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(fetchBrainAutoSwitch()).rejects.toThrow(/401/);
  });

  it('5xx ⇒ Error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    await expect(saveBrainAutoSwitch(true)).rejects.toThrow();
  });
});

describe('BrainAutoSwitchSection — Container (lädt beim Mount, schaltet per Klick)', () => {
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
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('lädt den Ist-Zustand beim Mount und meldet ihn ehrlich (GET)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse(setting({ enabled: true })));
    vi.stubGlobal('fetch', fetchMock);

    root = createRoot(container);
    await act(async () => {
      root!.render(<BrainAutoSwitchSection />);
    });
    await act(async () => {
      await Promise.resolve();
    });

    const toggle = container.querySelector('button[role="switch"]') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-checked')).toBe('true');
  });

  it('Klick auf den Schalter stösst ein PUT mit dem umgekehrten Wert an', async () => {
    let call = 0;
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => {
      call += 1;
      if (call === 1) return okResponse(setting({ enabled: false })); // initiales GET
      return okResponse(setting({ enabled: true })); // PUT-Antwort
    });
    vi.stubGlobal('fetch', fetchMock);

    root = createRoot(container);
    await act(async () => {
      root!.render(<BrainAutoSwitchSection />);
    });
    await act(async () => {
      await Promise.resolve();
    });

    const toggle = container.querySelector('button[role="switch"]') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-checked')).toBe('false');

    await act(async () => {
      toggle.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
    });

    expect(toggle.getAttribute('aria-checked')).toBe('true');
    const [, init] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(init.method).toBe('PUT');
    expect(init.body).toBe(JSON.stringify({ enabled: true }));
  });

  it('Lade-Fehler zeigt eine ehrliche Zeile, kein stiller Absturz', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500 }));

    root = createRoot(container);
    await act(async () => {
      root!.render(<BrainAutoSwitchSection />);
    });
    await act(async () => {
      await Promise.resolve();
    });

    expect(container.textContent).toContain(de.brainAutoSwitch.loadError);
  });

  it('fehlgeschlagenes PUT zeigt eine Notiz UND der Schalter bleibt beim alten Wert', async () => {
    let call = 0;
    const fetchMock = vi.fn(async () => {
      call += 1;
      if (call === 1) return okResponse(setting({ enabled: false }));
      return { ok: false, status: 500 };
    });
    vi.stubGlobal('fetch', fetchMock);

    root = createRoot(container);
    await act(async () => {
      root!.render(<BrainAutoSwitchSection />);
    });
    await act(async () => {
      await Promise.resolve();
    });

    const toggle = container.querySelector('button[role="switch"]') as HTMLButtonElement;
    await act(async () => {
      toggle.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
    });

    expect(toggle.getAttribute('aria-checked')).toBe('false');
    expect(container.textContent).toContain(de.brainAutoSwitch.failed);
  });
});

describe('BrainModelSectionView — der ehrliche Zusatz-Hinweis, wenn die Automatik an ist', () => {
  it('autoSwitchActive=false (Default) ⇒ kein Zusatz-Hinweis', () => {
    const html = renderToStaticMarkup(
      <BrainModelSectionView
        current={{ aktiv: 'e2b', modelle: [{ id: 'e2b', label: 'Gemma-4 E2B', repo: 'r' }], status: 'ok' }}
        onSelect={() => {}}
      />,
    );
    expect(html).not.toContain(de.brainModel.autoSwitchNote);
  });

  it('autoSwitchActive=true ⇒ der ehrliche Zusatz-Hinweis steht unter der Auswahl', () => {
    const html = renderToStaticMarkup(
      <BrainModelSectionView
        current={{ aktiv: 'e2b', modelle: [{ id: 'e2b', label: 'Gemma-4 E2B', repo: 'r' }], status: 'ok' }}
        onSelect={() => {}}
        autoSwitchActive
      />,
    );
    expect(html).toContain(de.brainModel.autoSwitchNote);
  });
});

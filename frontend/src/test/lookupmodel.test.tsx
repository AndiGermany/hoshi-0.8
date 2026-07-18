import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  LOOKUP_MODEL_TEXTS,
  LookupModelSectionView,
  type LookupModelSectionViewProps,
} from '../components/SettingsPanel';
import {
  type LookupModelSetting,
  UnknownLookupModelError,
  fetchLookupModel,
  saveLookupModel,
} from '../api/lookupModel';

/** Gültiger Wire-Zustand (zwei Modelle, nano aktiv), per `over` punktuell überschreibbar. */
const setting = (over: Partial<LookupModelSetting> = {}): LookupModelSetting => ({
  aktiv: 'gpt-5.4-nano',
  modelle: [
    { id: 'gpt-5.4-nano', label: 'OpenAI Nano (Standard, günstig)', centsProLookup: 0.1 },
    { id: 'gpt-5.4-mini', label: 'OpenAI Mini (gründlicher, teurer)', centsProLookup: 0.4 },
  ],
  ...over,
});

const render = (over: Partial<LookupModelSectionViewProps> = {}) =>
  renderToStaticMarkup(<LookupModelSectionView current={setting()} onSelect={() => {}} {...over} />);

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('LookupModelSectionView — Render (aus GET)', () => {
  it('zeigt das Label, den Select mit beiden Modellen + Preis-Info, aktiv vorausgewählt', () => {
    const html = render();
    expect(html).toContain(LOOKUP_MODEL_TEXTS.label);
    expect(html).toContain('settings-lookup-model');
    expect(html).toContain('OpenAI Nano');
    expect(html).toContain('OpenAI Mini');
    expect(html).toContain('ca. 0.10 ct/Nachschlag');
    expect(html).toContain('selected=""'); // renderToStaticMarkup markiert die value-Option
  });

  it('busy: „wechselt…" steht da, Select disabled (kein Doppel-PUT)', () => {
    const html = render({ busy: true });
    expect(html).toContain(LOOKUP_MODEL_TEXTS.switching);
    expect(html).toContain('disabled');
  });

  it('PUT+Readback: current spiegelt IMMER den zuletzt vom Server gelesenen Zustand (mini jetzt aktiv)', () => {
    const html = render({ current: setting({ aktiv: 'gpt-5.4-mini' }) });
    expect(html).toContain('ca. 0.40 ct/Nachschlag');
  });

  it('Fehler-Notiz (unbekanntes Modell) steht als role=status im Panel', () => {
    const html = render({ note: LOOKUP_MODEL_TEXTS.unknown });
    expect(html).toContain(LOOKUP_MODEL_TEXTS.unknown);
    expect(html).toContain('role="status"');
  });

  it('Lade-Fehler: ehrliche Zeile als role=alert', () => {
    const html = render({ current: null, error: LOOKUP_MODEL_TEXTS.loadError });
    expect(html).toContain(LOOKUP_MODEL_TEXTS.loadError);
    expect(html).toContain('role="alert"');
  });
});

describe('api/lookupModel — Wire-Vertrag', () => {
  it('fetchLookupModel: GET auf den Settings-Pfad, parst den Zustand', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => setting(),
    });
    vi.stubGlobal('fetch', fetchMock);

    const got = await fetchLookupModel();
    expect(got.aktiv).toBe('gpt-5.4-nano');
    expect(got.modelle).toHaveLength(2);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/settings/lookup-model');
  });

  it('saveLookupModel: PUT {id}, Antwort trägt den AUTORITATIVEN neuen Zustand', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => setting({ aktiv: 'gpt-5.4-mini' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const got = await saveLookupModel('gpt-5.4-mini');
    expect(got.aktiv).toBe('gpt-5.4-mini');
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('PUT');
    expect(init.body).toBe(JSON.stringify({ id: 'gpt-5.4-mini' }));
  });

  it('422 ⇒ UnknownLookupModelError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 422 }));
    await expect(saveLookupModel('gpt-nirgendwo')).rejects.toThrowError(UnknownLookupModelError);
  });

  it('401 ⇒ ehrlicher Auth-Fehler', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(fetchLookupModel()).rejects.toThrow(/401/);
  });
});

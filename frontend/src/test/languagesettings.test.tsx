import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  LANGUAGE_SETTINGS_TEXTS,
  LanguageSectionView,
  type LanguageSectionViewProps,
} from '../components/LanguageSection';
import {
  type LanguageSetting,
  UnknownLanguageError,
  fetchLanguageSettings,
  saveLanguageSetting,
} from '../api/languageSettings';

/** Gültiger Wire-Zustand (alle fünf Sprachen, DE aktiv), per `over` punktuell überschreibbar. */
const setting = (over: Partial<LanguageSetting> = {}): LanguageSetting => ({
  aktiv: 'de',
  sprachen: [
    { code: 'de', endonym: 'Deutsch', beta: false },
    { code: 'en', endonym: 'English', beta: true },
    { code: 'es', endonym: 'Español', beta: true },
    { code: 'fr', endonym: 'Français', beta: true },
    { code: 'it', endonym: 'Italiano', beta: true },
  ],
  smartHomeHinweis: null,
  ...over,
});

const render = (over: Partial<LanguageSectionViewProps> = {}) =>
  renderToStaticMarkup(<LanguageSectionView current={setting()} onSelect={() => {}} {...over} />);

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('LanguageSectionView — Render (aus GET)', () => {
  it('zeigt alle fuenf Sprachen, DE ohne Beta-Suffix, die anderen MIT', () => {
    const html = render();
    expect(html).toContain(LANGUAGE_SETTINGS_TEXTS.label);
    expect(html).toContain('settings-server-language');
    expect(html).toContain('Deutsch');
    expect(html).toContain('English');
    expect(html).toContain('Español');
    expect(html).toContain('Français');
    expect(html).toContain('Italiano');
    // Genau vier Beta-Suffixe (EN/ES/FR/IT), keiner an Deutsch.
    const betaCount = html.split(LANGUAGE_SETTINGS_TEXTS.betaSuffix).length - 1;
    expect(betaCount).toBe(4);
    expect(html).toContain('selected=""'); // DE ist vorausgewählt
  });

  it('busy: „wechselt…" steht da, Select disabled (kein Doppel-PUT)', () => {
    const html = render({ busy: true });
    expect(html).toContain(LANGUAGE_SETTINGS_TEXTS.switching);
    expect(html).toContain('disabled');
  });

  it('PUT+Readback: current spiegelt IMMER den zuletzt vom Server gelesenen Zustand (en jetzt aktiv)', () => {
    const html = render({ current: setting({ aktiv: 'en', smartHomeHinweis: 'Smart-home commands: German only for now.' }) });
    expect(html).toContain('Smart-home commands: German only for now.');
  });

  it('Deutsch aktiv zeigt KEINEN Smart-Home-Hinweis', () => {
    const html = render({ current: setting({ aktiv: 'de', smartHomeHinweis: null }) });
    expect(html).not.toContain('Smart-home');
  });

  it('Fehler-Notiz (unbekannte Sprache) steht als role=status im Panel', () => {
    const html = render({ note: LANGUAGE_SETTINGS_TEXTS.unknown });
    expect(html).toContain(LANGUAGE_SETTINGS_TEXTS.unknown);
    expect(html).toContain('role="status"');
  });

  it('Lade-Fehler: ehrliche Zeile als role=alert', () => {
    const html = render({ current: null, error: LANGUAGE_SETTINGS_TEXTS.loadError });
    expect(html).toContain(LANGUAGE_SETTINGS_TEXTS.loadError);
    expect(html).toContain('role="alert"');
  });
});

describe('api/languageSettings — Wire-Vertrag', () => {
  it('fetchLanguageSettings: GET auf den Settings-Pfad, parst den Zustand', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => setting(),
    });
    vi.stubGlobal('fetch', fetchMock);

    const got = await fetchLanguageSettings();
    expect(got.aktiv).toBe('de');
    expect(got.sprachen).toHaveLength(5);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/settings/language');
  });

  it('saveLanguageSetting: PUT {code}, Antwort traegt den AUTORITATIVEN neuen Zustand', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => setting({ aktiv: 'es' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const got = await saveLanguageSetting('es');
    expect(got.aktiv).toBe('es');
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('PUT');
    expect(init.body).toBe(JSON.stringify({ code: 'es' }));
  });

  it('422 (unbekannter Code) ⇒ UnknownLanguageError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 422 }));
    await expect(saveLanguageSetting('xx')).rejects.toThrowError(UnknownLanguageError);
  });

  it('401 ⇒ ehrlicher Auth-Fehler', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(fetchLanguageSettings()).rejects.toThrow(/401/);
  });
});

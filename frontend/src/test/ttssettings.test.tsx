/** @vitest-environment jsdom */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  STIMME_TEXTS,
  TTS_ENGINE_TEXTS,
  StimmeSectionView,
  TtsAndVoiceSection,
  TtsEngineSectionView,
  type StimmeSectionViewProps,
  type TtsEngineSectionViewProps,
} from '../components/SettingsPanel';
import {
  type TtsSetting,
  EngineUnavailableError,
  UnknownEngineError,
  UnknownVoiceError,
  fetchTtsSettings,
  saveTtsEngine,
  saveTtsVoice,
} from '../api/ttsSettings';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/**
 * Gültiger Wire-Zustand (voxtral aktiv, say verfügbar, piper/openai nicht), per
 * `over` überschreibbar. `stimmen`/`stimmenHinweis`/`aktiveStimme` sind additive
 * Felder (Andi-Live-Befund „Stimme folgt der aktiven Engine") — hier bewusst
 * leer/`null` default (voxtral hat noch keinen Stimmen-Katalog).
 */
const setting = (over: Partial<TtsSetting> = {}): TtsSetting => ({
  aktiv: 'voxtral',
  engines: [
    { id: 'openai', verfuegbar: false, hinweis: 'Kein OPENAI_API_KEY gesetzt.' },
    { id: 'say', verfuegbar: true, hinweis: '' },
    { id: 'piper', verfuegbar: false, hinweis: 'nicht gestartet' },
    { id: 'voxtral', verfuegbar: true, hinweis: '' },
  ],
  stimmen: [],
  stimmenHinweis: '',
  aktiveStimme: null,
  ...over,
});

const render = (over: Partial<TtsEngineSectionViewProps> = {}) =>
  renderToStaticMarkup(<TtsEngineSectionView current={setting()} onSelect={() => {}} {...over} />);

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('TtsEngineSectionView — Render (aus GET)', () => {
  it('zeigt alle vier Engines mit Namen', () => {
    const html = render();
    expect(html).toContain(TTS_ENGINE_TEXTS.label);
    expect(html).toContain('OpenAI (Cloud)');
    expect(html).toContain('macOS say (lokal)');
    expect(html).toContain('Piper (lokal)');
    expect(html).toContain('Voxtral (lokal)');
  });

  it('die aktive Engine trägt das "aktiv"-Badge', () => {
    const html = render();
    expect(html).toContain(TTS_ENGINE_TEXTS.active);
  });

  it('nicht verfügbare Engines (piper/openai): Toggle disabled + ehrlicher Hinweis-Badge', () => {
    const html = render();
    expect(html).toContain('nicht gestartet');
    expect(html).toContain('Kein OPENAI_API_KEY gesetzt.');
    // Piper/OpenAI müssen disabled sein — say (verfügbar, nicht aktiv) darf NICHT disabled sein.
    // (grober, aber ehrlicher Textnachweis: 2x "nicht gestartet"/Key-Hinweis kommen nur bei den
    // unverfügbaren Zeilen vor, disabled steht daneben.)
    const disabledCount = (html.match(/disabled=""/g) ?? []).length;
    expect(disabledCount).toBeGreaterThanOrEqual(2); // piper + openai (voxtral ist aktiv, ebenfalls disabled)
  });

  it('verfügbare, NICHT aktive Engine (say) bleibt anwählbar — kein disabled', () => {
    const html = render();
    // "say" ist verfügbar und nicht aktiv ⇒ sein Toggle-Button darf NICHT disabled sein.
    const sayRowMatch = html.match(/settings__skillname">macOS say[\s\S]*?<\/button>/);
    expect(sayRowMatch).not.toBeNull();
    expect(sayRowMatch![0]).not.toContain('disabled');
  });

  it('busy: „wechselt…" steht da', () => {
    const html = render({ busy: true });
    expect(html).toContain(TTS_ENGINE_TEXTS.switching);
  });

  it('Fehler-Notiz (Engine nicht verfügbar) steht als role=status im Panel', () => {
    const html = render({ note: 'nicht gestartet' });
    expect(html).toContain('role="status"');
  });

  it('Lade-Fehler: ehrliche Zeile als role=alert', () => {
    const html = render({ current: null, error: TTS_ENGINE_TEXTS.loadError });
    expect(html).toContain(TTS_ENGINE_TEXTS.loadError);
    expect(html).toContain('role="alert"');
  });
});

describe('api/ttsSettings — Wire-Vertrag', () => {
  it('fetchTtsSettings: GET auf den Settings-Pfad, parst den Zustand', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => setting() });
    vi.stubGlobal('fetch', fetchMock);

    const got = await fetchTtsSettings();
    expect(got.aktiv).toBe('voxtral');
    expect(got.engines).toHaveLength(4);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/settings/tts');
  });

  it('saveTtsEngine: PUT {id}, Antwort traegt den AUTORITATIVEN neuen Zustand (Readback)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => setting({ aktiv: 'say' }) });
    vi.stubGlobal('fetch', fetchMock);

    const got = await saveTtsEngine('say');
    expect(got.aktiv).toBe('say');
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('PUT');
    expect(init.body).toBe(JSON.stringify({ id: 'say' }));
  });

  it('422 ⇒ UnknownEngineError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 422 }));
    await expect(saveTtsEngine('alexa')).rejects.toThrowError(UnknownEngineError);
  });

  it('409 ⇒ EngineUnavailableError mit dem Server-Hinweis', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 409,
        json: async () => ({ error: 'engine-unavailable', id: 'piper', message: 'nicht gestartet' }),
      }),
    );
    await expect(saveTtsEngine('piper')).rejects.toThrowError(EngineUnavailableError);
    await expect(saveTtsEngine('piper')).rejects.toThrow('nicht gestartet');
  });

  it('401 ⇒ ehrlicher Auth-Fehler', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(fetchTtsSettings()).rejects.toThrow(/401/);
  });

  // ── stimmen/stimmenHinweis/aktiveStimme — additive Felder (Stimme folgt der Engine) ──

  it('fetchTtsSettings: parst stimmen/stimmenHinweis/aktiveStimme aus dem GET', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () =>
        setting({
          aktiv: 'piper',
          stimmen: [{ id: 'de_DE-thorsten-medium', label: 'de_DE-thorsten-medium (medium)', locale: 'de_DE', lizenz: 'MIT / CC0-1.0' }],
          stimmenHinweis: '',
          aktiveStimme: 'de_DE-thorsten-medium',
        }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const got = await fetchTtsSettings();
    expect(got.stimmen).toHaveLength(1);
    expect(got.stimmen[0].id).toBe('de_DE-thorsten-medium');
    expect(got.stimmen[0].lizenz).toBe('MIT / CC0-1.0');
    expect(got.aktiveStimme).toBe('de_DE-thorsten-medium');
  });

  it('fetchTtsSettings: fehlende stimmen/aktiveStimme-Felder (aelterer Server) brechen nicht — Defaults', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ aktiv: 'voxtral', engines: setting().engines }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const got = await fetchTtsSettings();
    expect(got.stimmen).toEqual([]);
    expect(got.stimmenHinweis).toBe('');
    expect(got.aktiveStimme).toBeNull();
  });
});

describe('api/ttsSettings — saveTtsVoice (PUT {id,voice})', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('PUT {id,voice}, Antwort traegt den AUTORITATIVEN neuen Zustand (Readback)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => setting({ aktiv: 'say', stimmen: [{ id: 'Anna', label: 'Anna', locale: 'de_DE' }], aktiveStimme: 'Anna' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const got = await saveTtsVoice('say', 'Anna');
    expect(got.aktiveStimme).toBe('Anna');
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('PUT');
    expect(init.body).toBe(JSON.stringify({ id: 'say', voice: 'Anna' }));
  });

  it('422 mit error=unknown-voice ⇒ UnknownVoiceError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 422,
        json: async () => ({ error: 'unknown-voice', id: 'Gandalf', message: 'Unbekannte Stimme für diese Engine.' }),
      }),
    );
    await expect(saveTtsVoice('say', 'Gandalf')).rejects.toThrowError(UnknownVoiceError);
  });

  it('422 mit error=unknown-engine ⇒ UnknownEngineError (nicht faelschlich UnknownVoiceError)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 422,
        json: async () => ({ error: 'unknown-engine', id: 'alexa', message: 'Unbekannte Engine.' }),
      }),
    );
    await expect(saveTtsVoice('alexa', 'Anna')).rejects.toThrowError(UnknownEngineError);
  });

  it('409 ⇒ EngineUnavailableError mit dem Server-Hinweis', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 409,
        json: async () => ({ error: 'engine-unavailable', id: 'piper', message: 'nicht gestartet' }),
      }),
    );
    await expect(saveTtsVoice('piper', 'de_DE-thorsten-medium')).rejects.toThrowError(EngineUnavailableError);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  StimmeSectionView — Render (folgt der AKTIVEN Engine, Andi-Live-Befund)
// ─────────────────────────────────────────────────────────────────────────────

const openAiStimmen = [
  { id: 'coral', label: 'Coral' },
  { id: 'nova', label: 'Nova' },
];
const piperStimmen = [
  { id: 'de_DE-thorsten-medium', label: 'de_DE-thorsten-medium (medium)', locale: 'de_DE', lizenz: 'MIT / CC0-1.0' },
];

const renderStimme = (over: Partial<StimmeSectionViewProps> = {}) =>
  renderToStaticMarkup(
    <StimmeSectionView
      current={setting({ aktiv: 'openai', stimmen: openAiStimmen, aktiveStimme: 'coral' })}
      activeVoice="coral"
      onSelectVoice={() => {}}
      onPlaySample={() => {}}
      {...over}
    />,
  );

describe('StimmeSectionView — folgt der aktiven Engine (Andi-Live-Befund 20.07)', () => {
  it('openai aktiv: zeigt den Cloud-Hinweis + Badge, NICHT den lokal-Hinweis', () => {
    const html = renderStimme();
    expect(html).toContain(STIMME_TEXTS.cloudPrivacy);
    expect(html).toContain('settings__badge--egress');
    expect(html).toContain(STIMME_TEXTS.cloudBadge);
    expect(html).not.toContain(STIMME_TEXTS.localLine);
    // Beide OpenAI-Stimmen stehen als Optionen da.
    expect(html).toContain('value="coral"');
    expect(html).toContain('value="nova"');
  });

  it('piper aktiv: zeigt den lokal-Hinweis (KEIN Cloud-Hinweis) + die Thorsten-Stimme', () => {
    const html = renderStimme({
      current: setting({ aktiv: 'piper', stimmen: piperStimmen, aktiveStimme: 'de_DE-thorsten-medium' }),
      activeVoice: 'de_DE-thorsten-medium',
    });
    expect(html).toContain(STIMME_TEXTS.localLine);
    expect(html).toContain(STIMME_TEXTS.localPrivacy);
    expect(html).not.toContain(STIMME_TEXTS.cloudPrivacy);
    expect(html).not.toContain('settings__badge--egress');
    expect(html).toContain('value="de_DE-thorsten-medium"');
    expect(html).toContain('thorsten');
    // Lizenz-Klartext steht dabei (Andis Lizenz-/Contest-Entscheid steht noch aus).
    expect(html).toContain('MIT / CC0-1.0');
  });

  it('say aktiv: zeigt ebenfalls den lokal-Hinweis (nicht nur piper)', () => {
    const html = renderStimme({
      current: setting({ aktiv: 'say', stimmen: [{ id: 'Anna', label: 'Anna', locale: 'de_DE' }], aktiveStimme: 'Anna' }),
      activeVoice: 'Anna',
    });
    expect(html).toContain(STIMME_TEXTS.localLine);
    expect(html).not.toContain(STIMME_TEXTS.cloudPrivacy);
  });

  it('leere Stimmen-Liste (voxtral/Fehler): kein Select, der Server-Hinweis steht da', () => {
    const html = renderStimme({
      current: setting({ aktiv: 'voxtral', stimmen: [], stimmenHinweis: 'Stimmwahl für diese Engine kommt noch.', aktiveStimme: null }),
      activeVoice: '',
    });
    expect(html).not.toContain('<select');
    expect(html).toContain('Stimmwahl für diese Engine kommt noch.');
  });

  it('busy: „wechselt…" steht da, Select ist disabled', () => {
    const html = renderStimme({ voiceBusy: true });
    expect(html).toContain(STIMME_TEXTS.switching);
    expect(html).toContain('disabled=""');
  });

  it('Fehler-Notiz (unbekannte Stimme) steht als role=status', () => {
    const html = renderStimme({ voiceNote: STIMME_TEXTS.unknownVoice });
    expect(html).toContain(STIMME_TEXTS.unknownVoice);
    expect(html).toContain('role="status"');
  });

  it('Lade-Fehler: ehrliche Zeile als role=alert', () => {
    const html = renderStimme({ current: null, error: STIMME_TEXTS.loadError });
    expect(html).toContain(STIMME_TEXTS.loadError);
    expect(html).toContain('role="alert"');
  });

  it('Hörprobe-Knopf bleibt (spricht ohnehin die aktive Engine)', () => {
    const html = renderStimme();
    expect(html).toContain('settings__samplebtn');
    expect(html).toContain('glyph--play');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  TtsAndVoiceSection — der gemeinsame Container (Engine-Wechsel ⇒ Stimmen-
//  Liste lädt neu, EIN gemeinsamer Fetch)
// ─────────────────────────────────────────────────────────────────────────────

const okResponse = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

describe('TtsAndVoiceSection — Engine-Wechsel lädt die Stimmen-Liste neu (ein gemeinsamer Fetch)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const flush = async (): Promise<void> => {
    await act(async () => {
      await Promise.resolve();
    });
  };

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

  it('PUT-Readback beim Engine-Wechsel bringt bereits die Stimmen der NEUEN Engine mit — kein zweiter GET nötig', async () => {
    const initial = setting({
      aktiv: 'say',
      // piper muss VERFUEGBAR sein, sonst bleibt der Toggle im TtsEngineSectionView
      // disabled (ehrliches Verhalten — kein Klick auf eine nicht laufende Engine).
      engines: [
        { id: 'openai', verfuegbar: false, hinweis: 'Kein OPENAI_API_KEY gesetzt.' },
        { id: 'say', verfuegbar: true, hinweis: '' },
        { id: 'piper', verfuegbar: true, hinweis: '' },
        { id: 'voxtral', verfuegbar: true, hinweis: '' },
      ],
      stimmen: [{ id: 'Anna', label: 'Anna', locale: 'de_DE' }],
      aktiveStimme: 'Anna',
    });
    const afterSwitch = setting({
      aktiv: 'piper',
      stimmen: piperStimmen,
      aktiveStimme: 'de_DE-thorsten-medium',
    });
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') return okResponse(afterSwitch); // PUT-Antwort (Engine-Wechsel say→piper)
      return okResponse(initial); // initiales GET beim Mount
    });
    vi.stubGlobal('fetch', fetchMock);

    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
      root!.render(<TtsAndVoiceSection voice="coral" onVoice={() => {}} />);
    });
    await flush();

    // Vor dem Wechsel: say ist aktiv, die Stimmen-Liste zeigt Anna.
    expect(container.textContent).toContain('Anna');

    const piperToggle = Array.from(container.querySelectorAll('[role="switch"]')).find(
      (el) => el.getAttribute('aria-label') === 'Piper (lokal)',
    ) as HTMLButtonElement;
    expect(piperToggle, 'Piper-Toggle muss im DOM stehen').toBeTruthy();

    await act(async () => {
      piperToggle.click();
    });
    await flush();

    // Nach dem Wechsel: dieselbe Stimmen-Sektion zeigt jetzt die Thorsten-Stimme
    // — direkt aus der PUT-Readback, OHNE dass ein dritter fetch()-Call nötig war.
    expect(container.textContent).toContain('de_DE-thorsten-medium');
    expect(container.textContent).not.toContain('Anna');
    expect(fetchMock).toHaveBeenCalledTimes(2); // 1x initiales GET, 1x PUT — kein extra Stimmen-GET
  });

  it('openai aktiv: eine Stimmen-Auswahl ruft `onVoice` (Client-seitig), OHNE einen PUT auszulösen', async () => {
    const openAiActive = setting({ aktiv: 'openai', stimmen: openAiStimmen, aktiveStimme: 'coral' });
    const fetchMock = vi.fn(async () => okResponse(openAiActive));
    vi.stubGlobal('fetch', fetchMock);
    const onVoice = vi.fn();

    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
      root!.render(<TtsAndVoiceSection voice="coral" onVoice={onVoice} />);
    });
    await flush();

    const select = container.querySelector('#settings-voice') as HTMLSelectElement;
    await act(async () => {
      select.value = 'nova';
      select.dispatchEvent(new Event('change', { bubbles: true }));
      await Promise.resolve();
    });

    expect(onVoice).toHaveBeenCalledWith('nova');
    expect(fetchMock).toHaveBeenCalledTimes(1); // nur das initiale GET — kein PUT für openai-Stimmen
  });
});

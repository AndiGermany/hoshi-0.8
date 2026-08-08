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

// Andi-Auftrag (07.08): „TTS-Engine soll ein Drop Down werden" — vorher eine
// Liste von Zeilen mit Zwei-Stufen-Toggle (Muster SkillsSection), jetzt EIN
// natives `<select>` (Muster LookupModelSectionView) mit einer `<option>` je
// Engine. Nicht verfügbare Engines bleiben als `<option disabled>` in der
// Liste stehen, MIT ihrem ehrlichen Hinweis im sichtbaren Options-Text.
describe('TtsEngineSectionView — Dropdown-Render (aus GET) (Akzeptanzkriterium a)', () => {
  it('rendert alle vier Engines als Dropdown-Optionen', () => {
    const html = render();
    expect(html).toContain(TTS_ENGINE_TEXTS.label);
    expect(html).toContain('<select');
    expect(html).toContain('id="settings-tts-engine"');
    expect(html).toContain('settings__select');
    expect(html).toContain('<option value="openai"');
    expect(html).toContain('<option value="say"');
    expect(html).toContain('<option value="piper"');
    expect(html).toContain('<option value="voxtral"');
    expect(html).toContain('OpenAI (Cloud)');
    expect(html).toContain('macOS say (lokal)');
    expect(html).toContain('Piper (lokal)');
    expect(html).toContain('Voxtral (lokal)');
  });

  it('die aktive Engine (voxtral) ist im Dropdown vorausgewählt', () => {
    const html = render();
    expect(html).toContain('<option value="voxtral" selected="">Voxtral (lokal)</option>');
  });

  it('nicht verfügbare Engines (piper/openai): Option disabled + ehrlicher Hinweis im Options-Text', () => {
    const html = render();
    expect(html).toContain('OpenAI (Cloud) — Kein OPENAI_API_KEY gesetzt.');
    expect(html).toContain('Piper (lokal) — nicht gestartet');
    // Genau piper + openai sind disabled — say (verfügbar) und voxtral (aktiv, verfügbar) nicht.
    const disabledCount = (html.match(/disabled=""/g) ?? []).length;
    expect(disabledCount).toBe(2);
  });

  it('verfügbare, NICHT aktive Engine (say) bleibt anwählbar — keine disabled-Option', () => {
    const html = render();
    const sayOption = html.match(/<option value="say"[^>]*>[\s\S]*?<\/option>/);
    expect(sayOption).not.toBeNull();
    expect(sayOption![0]).not.toContain('disabled');
    expect(sayOption![0]).not.toContain('selected');
  });

  it('busy: Select ist disabled + „wechselt…" steht da', () => {
    const html = render({ busy: true });
    expect(html).toContain(TTS_ENGINE_TEXTS.switching);
    expect(html).toMatch(/<select[^>]*disabled=""/);
  });

  it('Fehler-Notiz (Engine nicht verfügbar) steht als role=status im Panel', () => {
    const html = render({ note: 'nicht gestartet' });
    expect(html).toContain('role="status"');
  });

  it('Lade-Fehler: ehrliche Zeile als role=alert, kein Select ohne current', () => {
    const html = render({ current: null, error: TTS_ENGINE_TEXTS.loadError });
    expect(html).toContain(TTS_ENGINE_TEXTS.loadError);
    expect(html).toContain('role="alert"');
    expect(html).not.toContain('<select');
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

    const engineSelect = container.querySelector('#settings-tts-engine') as HTMLSelectElement;
    expect(engineSelect, 'Engine-Dropdown muss im DOM stehen').toBeTruthy();

    await act(async () => {
      engineSelect.value = 'piper';
      engineSelect.dispatchEvent(new Event('change', { bubbles: true }));
      await Promise.resolve();
    });
    await flush();

    // Nach dem Wechsel: dieselbe Stimmen-Sektion zeigt jetzt die Thorsten-Stimme
    // — direkt aus der PUT-Readback, OHNE dass ein dritter fetch()-Call nötig war.
    expect(container.textContent).toContain('de_DE-thorsten-medium');
    expect(container.textContent).not.toContain('Anna');
    expect(fetchMock).toHaveBeenCalledTimes(2); // 1x initiales GET, 1x PUT — kein extra Stimmen-GET
  });

  // ── Akzeptanzkriterium (b): die Stimmen-Einstellungen sind ausgeblendet
  //    (kein leerer/disabled-Select), solange die aktive Engine (voxtral) noch
  //    keinen Stimmen-Katalog hat — UND erscheinen sofort, sobald auf eine
  //    Engine mit Stimmen (say) umgeschaltet wird. Kein zweiter Fetch nötig,
  //    dieselbe PUT-Readback treibt beide Sektionen (s. Test oben).
  it('Stimm-Einstellungen erscheinen NUR bei einer Engine mit Stimmen-Katalog (Akzeptanzkriterium b)', async () => {
    const voxtralActive = setting({ aktiv: 'voxtral', stimmen: [], aktiveStimme: null });
    const sayActive = setting({
      aktiv: 'say',
      stimmen: [{ id: 'Anna', label: 'Anna', locale: 'de_DE' }],
      aktiveStimme: 'Anna',
    });
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') return okResponse(sayActive);
      return okResponse(voxtralActive);
    });
    vi.stubGlobal('fetch', fetchMock);

    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
      root!.render(<TtsAndVoiceSection voice="coral" onVoice={() => {}} />);
    });
    await flush();

    // voxtral aktiv, keine Stimmen ⇒ kein Stimmen-Select im DOM — ausgeblendet,
    // nicht bloß disabled (der Server-Hinweis-Text steht stattdessen da).
    expect(container.querySelector('#settings-voice')).toBeNull();

    const engineSelect = container.querySelector('#settings-tts-engine') as HTMLSelectElement;
    await act(async () => {
      engineSelect.value = 'say';
      engineSelect.dispatchEvent(new Event('change', { bubbles: true }));
      await Promise.resolve();
    });
    await flush();

    // Nach dem Wechsel zu say (hat Stimmen): der Stimmen-Select erscheint sofort.
    const voiceSelect = container.querySelector('#settings-voice') as HTMLSelectElement | null;
    expect(voiceSelect, 'Stimmen-Select muss nach dem Wechsel zu say erscheinen').toBeTruthy();
    expect(voiceSelect!.value).toBe('Anna');
  });

  // ── Akzeptanzkriterium (c): wer zurückwechselt, findet seine Stimmen-Wahl
  //    wieder — kein Reset. Die Erinnerung liegt server-seitig (Andi-Auftrag:
  //    „Werte NICHT zurücksetzen beim Umschalten"); hier simuliert per Mock,
  //    der jeder Engine ihre zuletzt gemerkte Stimme zurückgibt (Muster
  //    JsonFileTtsEngineStore) — say→piper→say verliert Annas Wahl NICHT.
  it('say → piper → zurück zu say: die gemerkte Stimme (Anna) ist wieder da, kein Reset (Akzeptanzkriterium c)', async () => {
    const sayActive = setting({
      aktiv: 'say',
      engines: [
        { id: 'openai', verfuegbar: false, hinweis: 'Kein OPENAI_API_KEY gesetzt.' },
        { id: 'say', verfuegbar: true, hinweis: '' },
        { id: 'piper', verfuegbar: true, hinweis: '' },
        { id: 'voxtral', verfuegbar: true, hinweis: '' },
      ],
      stimmen: [{ id: 'Anna', label: 'Anna', locale: 'de_DE' }],
      aktiveStimme: 'Anna',
    });
    const piperActive = setting({
      aktiv: 'piper',
      engines: sayActive.engines,
      stimmen: piperStimmen,
      aktiveStimme: 'de_DE-thorsten-medium',
    });
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') {
        const body = JSON.parse(init.body as string) as { id: string };
        // Der Server merkt sich die Stimme PRO Engine — say liefert immer Anna,
        // piper immer Thorsten zurück, unabhängig davon, in welcher Reihenfolge
        // umgeschaltet wird.
        return okResponse(body.id === 'piper' ? piperActive : sayActive);
      }
      return okResponse(sayActive); // initiales GET
    });
    vi.stubGlobal('fetch', fetchMock);

    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
      root!.render(<TtsAndVoiceSection voice="coral" onVoice={() => {}} />);
    });
    await flush();
    expect(container.textContent).toContain('Anna');

    const engineSelect = container.querySelector('#settings-tts-engine') as HTMLSelectElement;

    await act(async () => {
      engineSelect.value = 'piper';
      engineSelect.dispatchEvent(new Event('change', { bubbles: true }));
      await Promise.resolve();
    });
    await flush();
    expect(container.textContent).toContain('de_DE-thorsten-medium');
    expect(container.textContent).not.toContain('Anna');

    await act(async () => {
      engineSelect.value = 'say';
      engineSelect.dispatchEvent(new Event('change', { bubbles: true }));
      await Promise.resolve();
    });
    await flush();

    // Zurück bei say: Annas Wahl steht wieder da — nicht leer, nicht zurückgesetzt.
    expect(container.textContent).toContain('Anna');
    expect(container.textContent).not.toContain('de_DE-thorsten-medium');
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

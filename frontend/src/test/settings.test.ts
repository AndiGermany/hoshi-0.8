import { describe, it, expect, vi, afterEach } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  DEFAULT_SETTINGS,
  PERSONAS,
  SETTINGS_STORAGE_KEY,
  THEMES,
  VOICES,
  loadSettings,
  saveSettings,
  type Persona,
} from '../hooks/useSettings';
import { SettingsPanel } from '../components/SettingsPanel';
import { streamChat } from '../api/chat';

/** In-Memory-Storage, der die DOM-`Storage`-Form erfüllt (node hat kein localStorage). */
function memoryStorage(): Storage {
  const m = new Map<string, string>();
  return {
    get length() {
      return m.size;
    },
    clear: () => m.clear(),
    getItem: (k: string) => (m.has(k) ? (m.get(k) as string) : null),
    key: (i: number) => Array.from(m.keys())[i] ?? null,
    removeItem: (k: string) => {
      m.delete(k);
    },
    setItem: (k: string, v: string) => {
      m.set(k, String(v));
    },
  };
}

/** Sofort-fertiger SSE-Body: getReader() liefert direkt done (kein echtes Netz). */
function okEmptyStream() {
  return {
    status: 200,
    ok: true,
    body: {
      getReader() {
        return { read: () => Promise.resolve({ done: true, value: undefined }) };
      },
    },
  };
}

/** Panel-Render ohne Live-Backend: Static-Markup führt keine Effects aus (useSkills bleibt inert). */
const renderPanel = (persona: Persona, voice = 'coral') =>
  renderToStaticMarkup(
    createElement(SettingsPanel, {
      open: true,
      onClose: () => {},
      theme: 'yoru',
      language: 'de',
      persona,
      voice,
      onTheme: () => {},
      onLanguage: () => {},
      onPersona: () => {},
      onVoice: () => {},
    }),
  );

describe('useSettings — Persistenz + Defaults', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('Default-Theme ist Aoi (青 — Andi-Adopt 2026-07-02, nur der Fallback)', () => {
    expect(DEFAULT_SETTINGS.theme).toBe('aoi');
    expect(DEFAULT_SETTINGS.language).toBe('auto'); // bilinguale Auto-Erkennung (DE/EN)
    expect(DEFAULT_SETTINGS.persona).toBe('Standard');
    expect(DEFAULT_SETTINGS.voice).toBe('coral'); // Boot-Default des BE-Adapters
  });

  it('ohne gespeicherte Werte → Defaults (Aoi/auto/Standard)', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    expect(loadSettings()).toEqual(DEFAULT_SETTINGS);
  });

  it('Aoi-Einmal-Migration: alter Default yoru wird EINMAL zu aoi, danach zählt die Wahl', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    // Bestands-Client mit dem alten (auto-persistierten) Default yoru: erste Ladung
    // migriert zu Aoi (Andi-Adopt 2026-07-02) und setzt das Einmal-Flag.
    saveSettings({ theme: 'yoru', language: 'de', persona: 'Standard', voice: 'coral' });
    expect(loadSettings().theme).toBe('aoi');
    // Wer DANACH bewusst yoru wählt, behält es — das Flag verhindert Wiederholung.
    saveSettings({ theme: 'yoru', language: 'de', persona: 'Standard', voice: 'coral' });
    expect(loadSettings().theme).toBe('yoru');
    // …und Aoi steht als Karte im Panel-Katalog, an erster Stelle (Default).
    expect(THEMES[0].id).toBe('aoi');
  });

  it('Nicht-Default-Themes werden von der Migration NIE angefasst', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    saveSettings({ theme: 'kasumi', language: 'de', persona: 'Standard', voice: 'coral' });
    expect(loadSettings().theme).toBe('kasumi');
  });

  it('persistiert + stellt {theme, language, persona, voice} wieder her', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    saveSettings({ theme: 'kasumi', language: 'en', persona: 'Standard', voice: 'nova' });
    expect(loadSettings()).toEqual({
      theme: 'kasumi',
      language: 'en',
      persona: 'Standard',
      voice: 'nova',
    });
  });

  it('schreibt unter dem erwarteten Storage-Key', () => {
    const store = memoryStorage();
    vi.stubGlobal('localStorage', store);
    saveSettings({ theme: 'asa', language: 'de', persona: 'Standard', voice: 'coral' });
    expect(store.getItem(SETTINGS_STORAGE_KEY)).toContain('asa');
  });

  it('Nagareboshi (流れ星) ist gültig: persistiert + wird wiederhergestellt', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    saveSettings({ theme: 'nagareboshi', language: 'de', persona: 'Standard', voice: 'coral' });
    expect(loadSettings().theme).toBe('nagareboshi');
    // …und steht als Karte im Panel-Katalog (Name + poetischer Hint).
    expect(THEMES.map((t) => t.id)).toContain('nagareboshi');
  });

  it('ignoriert ungültiges Theme/Sprache/Stimme und kaputtes JSON → Defaults', () => {
    const store = memoryStorage();
    vi.stubGlobal('localStorage', store);
    store.setItem(
      SETTINGS_STORAGE_KEY,
      JSON.stringify({ theme: 'bogus', language: 'fr', voice: 'darth-vader' }),
    );
    expect(loadSettings().theme).toBe('aoi');
    expect(loadSettings().language).toBe('auto'); // ungültige Sprache → Default 'auto'
    expect(loadSettings().voice).toBe('coral'); // unbekannte Stimme → Default 'coral'
    store.setItem(SETTINGS_STORAGE_KEY, '{ kaputtes json');
    expect(loadSettings()).toEqual(DEFAULT_SETTINGS);
  });

  it('ohne localStorage (node/SSR) → Defaults, kein Wurf', () => {
    expect(() => loadSettings()).not.toThrow();
    expect(loadSettings()).toEqual(DEFAULT_SETTINGS);
  });
});

describe('PERSONAS — self-demonstrating Picker (Text-Hörprobe)', () => {
  it('jede Persona hat einen sample-Satz: nicht leer, einzeilig, kein Markdown', () => {
    expect(PERSONAS).toHaveLength(4);
    for (const p of PERSONAS) {
      expect(p.sample, p.id).toBeTruthy();
      expect(p.sample, p.id).not.toContain('\n'); // EIN sprechbarer Satz
      expect(p.sample, p.id).not.toMatch(/[*#_`]|- /); // kein Markdown/Listen
      expect(p.sample.length, p.id).toBeLessThan(120); // kurz genug für TTS/Panel
    }
  });

  it('die Samples unterscheiden sich je Persona (sonst demonstriert nichts)', () => {
    const samples = PERSONAS.map((p) => p.sample);
    expect(new Set(samples).size).toBe(samples.length);
    // Knapp ist der Minimal-Ton — der kürzeste Satz im Katalog.
    const knapp = PERSONAS.find((p) => p.id === 'Knapp')!;
    for (const p of PERSONAS) {
      if (p.id !== 'Knapp') expect(knapp.sample.length).toBeLessThan(p.sample.length);
    }
  });

  it('Render-Vertrag: „So klinge ich" + Sample der gewählten Persona', () => {
    const html = renderPanel('Kumpel');
    expect(html).toContain('So klinge ich');
    expect(html).toContain('settings__sample');
    expect(html).toContain('Jacke einpacken, fertig!'); // Kumpel-Sample, live zur Auswahl
  });

  it('Render-Vertrag: Sample wechselt mit der Auswahl (Ruhig statt Kumpel)', () => {
    const html = renderPanel('Ruhig');
    expect(html).toContain('zieht wohl etwas Regen auf'); // Ruhig
    expect(html).not.toContain('Jacke einpacken'); // Kumpel-Sample ist weg
  });
});

describe('VOICES — Stimmen-Katalog + Hörprobe (#6+#7)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('genau die 13 OpenAI-Stimmen (Whitelist-Spiegel), eindeutig, coral dabei', () => {
    expect(VOICES).toHaveLength(13);
    expect(new Set(VOICES).size).toBe(13);
    for (const v of ['alloy', 'ash', 'ballad', 'coral', 'echo', 'fable', 'onyx', 'nova', 'sage', 'shimmer', 'verse', 'marin', 'cedar']) {
      expect(VOICES, v).toContain(v);
    }
  });

  it('persistiert + stellt voice wieder her (wie theme/persona)', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    saveSettings({ ...DEFAULT_SETTINGS, voice: 'marin' });
    expect(loadSettings().voice).toBe('marin');
  });

  // Die Render-Verträge der Stimmen-GRUPPE selbst (Select-Optionen, Cloud-Badge,
  // Selektions-Zustand) sind seit Andis Live-Befund „Stimme folgt der aktiven
  // Engine" NICHT mehr statisch aus {@link VOICES} — sie kommen jetzt aus dem
  // GET-Wire-Vertrag (`stimmen`/`aktiveStimme`) und werden darum prop-getrieben
  // gegen {@link StimmeSectionView} getestet, s. `ttssettings.test.tsx`
  // (Muster {@link TtsEngineSectionView}). `renderPanel` hier rendert nur noch
  // STATISCH (kein Effect läuft in `renderToStaticMarkup`) — die Sektion selbst
  // bräuchte einen echten GET, um Optionen zu zeigen.
});

describe('Verdrahtung — Settings fließen in den Chat-Request', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('Sprache + persona + voice kommen aus den Settings, wenn opts nichts vorgeben', async () => {
    vi.stubGlobal('localStorage', memoryStorage());
    saveSettings({ theme: 'yoru', language: 'en', persona: 'Standard', voice: 'nova' });
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    await streamChat('hi', { onEvent: () => {} });

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const body = JSON.parse(init.body as string);
    expect(body.language).toBe('EN'); // aus den Settings, GROSS, ohne opts.language
    expect(body.persona).toBe('Standard'); // additives Persona-Feld
    expect(body.voice).toBe('nova'); // die Panel-Stimme fließt mit (#6)
  });

  it("'auto' → languagePolicy AUTO mit konkretem language DE als Fallback", async () => {
    vi.stubGlobal('localStorage', memoryStorage());
    saveSettings({ theme: 'yoru', language: 'auto', persona: 'Standard', voice: 'coral' });
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    await streamChat('hi', { onEvent: () => {} });

    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.languagePolicy).toBe('AUTO'); // die Wahl (Backend erkennt pro Eingabe)
    expect(body.language).toBe('DE'); // konkreter Legacy-Fallback
  });

  it('explizite opts schlagen die Settings (language/persona/voice)', async () => {
    vi.stubGlobal('localStorage', memoryStorage());
    saveSettings({ theme: 'yoru', language: 'en', persona: 'Standard', voice: 'coral' });
    const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
    vi.stubGlobal('fetch', fetchMock);

    await streamChat('hi', {
      onEvent: () => {},
      language: 'de',
      persona: 'Forscherin',
      voice: 'marin',
    });

    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.language).toBe('DE');
    expect(body.persona).toBe('Forscherin');
    expect(body.voice).toBe('marin');
  });
});

import { describe, it, expect, vi, afterEach } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  DEFAULT_SETTINGS,
  LANGUAGE_IDS,
  LANGUAGES,
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

  it('Yoake (夜明け) ist auswählbar, persistiert und als Morgendämmerung beschrieben', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    saveSettings({ theme: 'yoake', language: 'de', persona: 'Standard', voice: 'coral' });
    expect(loadSettings().theme).toBe('yoake');
    expect(THEMES.find((t) => t.id === 'yoake')).toEqual({
      id: 'yoake',
      label: 'Yoake',
      hint: 'Morgendämmerung zwischen Indigo und Koralle (夜明け)',
    });
  });

  it('Natsu no Hi (夏の日) ist auswählbar, persistiert und als Sommertag beschrieben', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    saveSettings({ theme: 'natsunohi', language: 'de', persona: 'Standard', voice: 'coral' });
    expect(loadSettings().theme).toBe('natsunohi');
    expect(THEMES.find((t) => t.id === 'natsunohi')).toEqual({
      id: 'natsunohi',
      label: 'Natsu no Hi',
      hint: 'Sommertag in Ramune-Blau und warmem Washi (夏の日)',
    });
  });

  it('Amayadori (雨宿り) ist auswählbar, persistiert und als Regenpause beschrieben', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    saveSettings({ theme: 'amayadori', language: 'de', persona: 'Standard', voice: 'coral' });
    expect(loadSettings().theme).toBe('amayadori');
    expect(THEMES.find((t) => t.id === 'amayadori')).toEqual({
      id: 'amayadori',
      label: 'Amayadori',
      hint: 'Draußen fällt es weiter; hier drinnen ist es trocken und jemand hat Licht gelassen. (雨宿り)',
    });
  });

  it('ignoriert ungültiges Theme/Sprache/Stimme und kaputtes JSON → Defaults', () => {
    const store = memoryStorage();
    vi.stubGlobal('localStorage', store);
    // 'pt' (Portugiesisch) ist bewusst gewählt: KEINE der sechs gültigen Sprachen
    // (auto/de/en/es/fr/it, s. LANGUAGE_IDS) — 'fr' wäre seit dem Sprachen-Auftrag
    // 2026-07-27 kein gutes Beispiel für „ungültig" mehr, weil es das jetzt ist.
    store.setItem(
      SETTINGS_STORAGE_KEY,
      JSON.stringify({ theme: 'bogus', language: 'pt', voice: 'darth-vader' }),
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

// ─────────────────────────────────────────────────────────────────────────────
//  Sprachwahl (Chat + STT) — Andi-Auftrag 2026-07-27 „fünf Sprachen ohne
//  Sternchen": Español/Français/Italiano sind seither NEBEN Automatisch/
//  Deutsch/English wählbar (bewusst NICHT Teil von 'auto' — s. KDoc `Language`
//  in api/types.ts).
// ─────────────────────────────────────────────────────────────────────────────

describe('LANGUAGE_IDS/LANGUAGES — sechs wählbare Chat-/STT-Sprachen', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('genau sechs Codes, auto/de/en zuerst, es/fr/it NEU dazu', () => {
    expect(LANGUAGE_IDS).toEqual(['auto', 'de', 'en', 'es', 'fr', 'it']);
    expect(LANGUAGES.map((l) => l.id)).toEqual(['auto', 'de', 'en', 'es', 'fr', 'it']);
  });

  it('jede Sprache hat ein nicht-leeres Label aus dem Text-Katalog', () => {
    for (const l of LANGUAGES) {
      expect(l.label.trim().length, l.id).toBeGreaterThan(0);
    }
  });

  it.each(['es', 'fr', 'it'] as const)(
    "'%s' persistiert + wird aus localStorage wiederhergestellt (wie de/en)",
    (code) => {
      vi.stubGlobal('localStorage', memoryStorage());
      saveSettings({ theme: 'yoru', language: code, persona: 'Standard', voice: 'coral' });
      expect(loadSettings().language).toBe(code);
    },
  );
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

  // Andi-Auftrag 2026-07-27 („fünf Sprachen ohne Sternchen"): Español/Français/
  // Italiano sind explizit wählbar, aber NIE als `languagePolicy` verschickt —
  // das Backend-Enum `LanguagePolicy` kennt nur AUTO/DE/EN; ein `languagePolicy:
  // "ES"` würde die ganze Jackson-Deserialisierung zum Scheitern bringen. Der
  // Turn muss trotzdem in ES/FR/IT laufen — über das konkrete `language`-Feld
  // (voller 5-Sprachen-Backend-Enum), das der LanguageResolver bei fehlender
  // Policy als Legacy-Fallback liest.
  it.each(['es', 'fr', 'it'] as const)(
    "language:'%s' → konkretes language-Feld GROSS, languagePolicy bleibt WEG (kein 400 möglich)",
    async (code) => {
      vi.stubGlobal('localStorage', memoryStorage());
      const fetchMock = vi.fn().mockResolvedValue(okEmptyStream());
      vi.stubGlobal('fetch', fetchMock);

      await streamChat('hi', { onEvent: () => {}, language: code });

      const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
      expect(body.language).toBe(code.toUpperCase());
      expect(body).not.toHaveProperty('languagePolicy'); // JSON.stringify ließ den undefined-Key weg
    },
  );
});

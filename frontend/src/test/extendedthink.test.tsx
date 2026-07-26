/** @vitest-environment jsdom */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  EXTENDED_THINK_TEXTS,
  ExtendedThinkSection,
  ExtendedThinkSectionView,
  type ExtendedThinkSectionViewProps,
} from '../components/SettingsPanel';
import {
  type ExtendedThinkSetting,
  ESCALATION_MODES,
  EscalationLockedError,
  UnknownEscalationModeError,
  fetchExtendedThink,
  saveExtendedThinkMode,
} from '../api/extendedThink';
import { CATALOGS, SUPPORTED_UI_LANGUAGES } from '../i18n';

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

// ─────────────────────────────────────────────────────────────────────────────
//  Extended-Think-Stufenwahl (Andi-Auftrag 26.07: „die Eskalations-Stufe hat
//  KEIN UI-Element" — vorher nur GET/PUT /api/v1/settings/extended-think im
//  Backend). Deckt:
//   1. ExtendedThinkSectionView — Render-Vertrag (Muster LookupModelSectionView).
//   2. api/extendedThink — Wire-Vertrag (Muster api/lookupModel).
//   3. ExtendedThinkSection — Container: laden/wählen/Fehler/Readback (Muster
//      TtsAndVoiceSection — Klick statt Select).
//   4. i18n-Vollständigkeit der neuen Kataloge (`extendedThink`) in allen fünf
//      Sprachen — Werte, nicht nur der TS-Typ.
// ─────────────────────────────────────────────────────────────────────────────

/** Gültiger Wire-Zustand (Decke offen, ERST_FRAGEN aktiv), per `over` punktuell überschreibbar. */
const setting = (over: Partial<ExtendedThinkSetting> = {}): ExtendedThinkSetting => ({
  mode: 'ERST_FRAGEN',
  ceilingOpen: true,
  locked: false,
  effectiveMode: 'ERST_FRAGEN',
  ...over,
});

const okResponse = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

const render = (over: Partial<ExtendedThinkSectionViewProps> = {}) =>
  renderToStaticMarkup(<ExtendedThinkSectionView current={setting()} onSelect={() => {}} {...over} />);

describe('ExtendedThinkSectionView — Render (aus GET)', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('zeigt Label, Hinweis und alle vier Stufen als Radio-Karten nach Online-Grad geordnet', () => {
    const html = render();
    expect(html).toContain(EXTENDED_THINK_TEXTS.label);
    expect(html).toContain(EXTENDED_THINK_TEXTS.hint);
    expect(html).toContain('role="radiogroup"');
    expect((html.match(/role="radio"/g) ?? []).length).toBe(4);
    // Reihenfolge nach Online-Grad: Aus → Offline → Erst fragen → Automatisch.
    const positions = ESCALATION_MODES.map((m) => html.indexOf(EXTENDED_THINK_TEXTS.modes[m].title));
    for (const p of positions) expect(p).toBeGreaterThan(-1);
    expect(positions).toEqual([...positions].sort((a, b) => a - b));
    // Je Karte: Titel + EIN erklärender Satz.
    for (const m of ESCALATION_MODES) {
      expect(html).toContain(EXTENDED_THINK_TEXTS.modes[m].title);
      expect(html).toContain(EXTENDED_THINK_TEXTS.modes[m].description);
    }
  });

  it('ERST_FRAGEN ist voreingestellt aktiv (Laufzeit-Default) und trägt das Empfohlen-Badge', () => {
    const html = render();
    expect(html).toMatch(/aria-checked="true"[^>]*>[\s\S]*?Erst fragen/);
    expect(html).toContain(EXTENDED_THINK_TEXTS.recommendedBadge);
    // Genau EIN Badge — nicht jede Karte trägt „Empfohlen".
    expect((html.match(new RegExp(EXTENDED_THINK_TEXTS.recommendedBadge, 'g')) ?? []).length).toBe(1);
  });

  it('AUTOMATISCH aktiv ⇒ dessen Karte trägt aria-checked=true, die anderen false', () => {
    const html = render({ current: setting({ mode: 'AUTOMATISCH' }) });
    expect(html).toMatch(/aria-checked="true"[^>]*>[\s\S]*?Automatisch/);
    expect((html.match(/aria-checked="true"/g) ?? []).length).toBe(1);
  });

  it('Decke zu (locked): der ehrliche Sperr-Hinweis steht da, alle Karten sind disabled', () => {
    const html = render({ current: setting({ locked: true, ceilingOpen: false, effectiveMode: 'AUS' }) });
    expect(html).toContain(EXTENDED_THINK_TEXTS.locked);
    expect((html.match(/disabled=""/g) ?? []).length).toBe(4);
  });

  it('busy: der wechselt-Hinweis steht da, alle Karten sind disabled (kein Doppel-PUT)', () => {
    const html = render({ busy: true });
    expect(html).toContain(EXTENDED_THINK_TEXTS.switching);
    expect((html.match(/disabled=""/g) ?? []).length).toBe(4);
  });

  it('Fehler-Notiz (unbekannte Stufe) steht als role=status im Panel', () => {
    const html = render({ note: EXTENDED_THINK_TEXTS.unknown });
    expect(html).toContain(EXTENDED_THINK_TEXTS.unknown);
    expect(html).toContain('role="status"');
  });

  it('Lade-Fehler: ehrliche Zeile als role=alert, keine Karten ohne current', () => {
    const html = render({ current: null, error: EXTENDED_THINK_TEXTS.loadError });
    expect(html).toContain(EXTENDED_THINK_TEXTS.loadError);
    expect(html).toContain('role="alert"');
    expect(html).not.toContain('role="radiogroup"');
  });

  it('lädt…: solange current fehlt, steht die ehrliche Lade-Zeile da', () => {
    const html = render({ current: null, loading: true });
    expect(html).toContain(EXTENDED_THINK_TEXTS.loading);
  });
});

describe('api/extendedThink — Wire-Vertrag', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('fetchExtendedThink: GET auf den Settings-Pfad, parst den Zustand', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse(setting()));
    vi.stubGlobal('fetch', fetchMock);

    const got = await fetchExtendedThink();
    expect(got.mode).toBe('ERST_FRAGEN');
    expect(got.ceilingOpen).toBe(true);
    expect(got.locked).toBe(false);
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/settings/extended-think');
  });

  it('saveExtendedThinkMode: PUT {mode}, Antwort trägt den AUTORITATIVEN neuen Zustand', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse(setting({ mode: 'AUTOMATISCH', effectiveMode: 'AUTOMATISCH' })));
    vi.stubGlobal('fetch', fetchMock);

    const got = await saveExtendedThinkMode('AUTOMATISCH');
    expect(got.mode).toBe('AUTOMATISCH');
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('PUT');
    expect(init.body).toBe(JSON.stringify({ mode: 'AUTOMATISCH' }));
  });

  it('400 ⇒ UnknownEscalationModeError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400 }));
    await expect(saveExtendedThinkMode('AUTOMATISCH')).rejects.toThrowError(UnknownEscalationModeError);
  });

  it('409 ⇒ EscalationLockedError (Decke beim Deploy zu)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 409 }));
    await expect(saveExtendedThinkMode('AUTOMATISCH')).rejects.toThrowError(EscalationLockedError);
  });

  it('401 ⇒ ehrlicher Auth-Fehler', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    await expect(fetchExtendedThink()).rejects.toThrow(/401/);
  });

  it('kaputter Body (unbekannter mode-Wert) ⇒ Error statt geratenem Zustand', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(okResponse({ mode: 'TURBO', ceilingOpen: true, locked: false, effectiveMode: 'TURBO' })),
    );
    await expect(fetchExtendedThink()).rejects.toThrow();
  });
});

describe('ExtendedThinkSection — Container: laden/wählen/Fehler/Readback', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const flush = async (): Promise<void> => {
    await act(async () => {
      await Promise.resolve();
    });
  };

  const mount = async (): Promise<void> => {
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
      root!.render(<ExtendedThinkSection />);
    });
    await flush();
  };

  const radioFor = (title: string): HTMLButtonElement =>
    Array.from(container.querySelectorAll('[role="radio"]')).find((el) =>
      el.textContent?.includes(title),
    ) as HTMLButtonElement;

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

  it('laden: GET beim Mount zeigt den Server-Ist-Zustand (kein optimistisches Grün)', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => okResponse(setting({ mode: 'OFFLINE', effectiveMode: 'OFFLINE' }))));
    await mount();

    const offlineRadio = radioFor(EXTENDED_THINK_TEXTS.modes.OFFLINE.title);
    expect(offlineRadio.getAttribute('aria-checked')).toBe('true');
    const erstFragenRadio = radioFor(EXTENDED_THINK_TEXTS.modes.ERST_FRAGEN.title);
    expect(erstFragenRadio.getAttribute('aria-checked')).toBe('false');
  });

  it('wählen + Readback: Klick auf eine andere Stufe PUTtet und übernimmt die AUTORITATIVE Server-Antwort', async () => {
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') {
        return okResponse(setting({ mode: 'AUTOMATISCH', effectiveMode: 'AUTOMATISCH' }));
      }
      return okResponse(setting()); // initiales GET: ERST_FRAGEN aktiv
    });
    vi.stubGlobal('fetch', fetchMock);
    await mount();

    const automatischRadio = radioFor(EXTENDED_THINK_TEXTS.modes.AUTOMATISCH.title);
    expect(automatischRadio.getAttribute('aria-checked')).toBe('false');

    await act(async () => {
      automatischRadio.click();
    });
    await flush();

    expect(automatischRadio.getAttribute('aria-checked')).toBe('true');
    expect(radioFor(EXTENDED_THINK_TEXTS.modes.ERST_FRAGEN.title).getAttribute('aria-checked')).toBe('false');
    expect(fetchMock).toHaveBeenCalledTimes(2); // 1x GET, 1x PUT — kein dritter Fetch nötig
    const [, init] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(init.body).toBe(JSON.stringify({ mode: 'AUTOMATISCH' }));
  });

  it('erneuter Klick auf die bereits aktive Stufe löst KEINEN PUT aus', async () => {
    const fetchMock = vi.fn(async () => okResponse(setting()));
    vi.stubGlobal('fetch', fetchMock);
    await mount();

    const erstFragenRadio = radioFor(EXTENDED_THINK_TEXTS.modes.ERST_FRAGEN.title);
    await act(async () => {
      erstFragenRadio.click();
    });
    await flush();

    expect(fetchMock).toHaveBeenCalledTimes(1); // nur das initiale GET
  });

  it('Fehler (409 — Decke zu): ehrliche Notiz, der Ist-Zustand wird ehrlich neu geladen', async () => {
    let call = 0;
    const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      call += 1;
      if (init?.method === 'PUT') return { ok: false, status: 409 };
      // 1. Aufruf: initiales GET (offene Decke). 2. Aufruf: Neuladen NACH dem
      // Fehlschlag — die Decke ist in Wahrheit inzwischen zu (ehrlicher Reload).
      if (call === 1) return okResponse(setting());
      return okResponse(setting({ locked: true, ceilingOpen: false, effectiveMode: 'AUS' }));
    });
    vi.stubGlobal('fetch', fetchMock);
    await mount();

    const automatischRadio = radioFor(EXTENDED_THINK_TEXTS.modes.AUTOMATISCH.title);
    await act(async () => {
      automatischRadio.click();
    });
    await flush();

    expect(container.textContent).toContain(EXTENDED_THINK_TEXTS.locked);
    expect(fetchMock).toHaveBeenCalledTimes(3); // GET, PUT (409), Reload-GET
    // Nach dem ehrlichen Reload sind die Karten gesperrt — kein Doppel-Versuch möglich.
    expect(radioFor(EXTENDED_THINK_TEXTS.modes.AUTOMATISCH.title).hasAttribute('disabled')).toBe(true);
  });

  it('Fehler (Netz kaputt): ehrliche Lade-Fehler-Zeile statt eines stillen leeren Panels', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('kein Netz')));
    await mount();

    expect(container.textContent).toContain(EXTENDED_THINK_TEXTS.loadError);
    expect(container.querySelector('[role="radiogroup"]')).toBeNull();
  });

  it('Decke zu (locked): Klick auf eine andere Stufe bleibt wirkungslos (Karten disabled)', async () => {
    const fetchMock = vi.fn(async () =>
      okResponse(setting({ locked: true, ceilingOpen: false, effectiveMode: 'AUS' })),
    );
    vi.stubGlobal('fetch', fetchMock);
    await mount();

    const automatischRadio = radioFor(EXTENDED_THINK_TEXTS.modes.AUTOMATISCH.title);
    expect(automatischRadio.hasAttribute('disabled')).toBe(true);
    await act(async () => {
      automatischRadio.click();
    });
    await flush();

    expect(fetchMock).toHaveBeenCalledTimes(1); // nur das initiale GET — der Klick griff nicht
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  i18n-Vollständigkeit: der neue `extendedThink`-Katalog ist in ALLEN fünf
//  Sprachen echt befüllt (Werte, nicht nur der TS-Typ — ein leerer String wäre
//  type-valide, aber inhaltlich eine Lücke).
// ─────────────────────────────────────────────────────────────────────────────

describe('i18n — extendedThink-Katalog vollständig in allen fünf Sprachen', () => {
  it('jede Sprache hat nicht-leere Texte für jedes Feld + alle vier Stufen', () => {
    expect(SUPPORTED_UI_LANGUAGES.length).toBe(5);
    for (const lang of SUPPORTED_UI_LANGUAGES) {
      const t = CATALOGS[lang].extendedThink;
      for (const [key, value] of Object.entries(t)) {
        if (key === 'modes') continue;
        expect(typeof value === 'string' && value.trim().length > 0, `${lang}.extendedThink.${key}`).toBe(
          true,
        );
      }
      for (const mode of ESCALATION_MODES) {
        expect(t.modes[mode].title.trim().length, `${lang}.extendedThink.modes.${mode}.title`).toBeGreaterThan(0);
        expect(
          t.modes[mode].description.trim().length,
          `${lang}.extendedThink.modes.${mode}.description`,
        ).toBeGreaterThan(0);
      }
    }
  });

  it('DE ist byte-gleich zum Auftrag: Reihenfolge Aus → Offline → Erst fragen → Automatisch, Erst fragen empfohlen+Default', () => {
    expect(ESCALATION_MODES).toEqual(['AUS', 'OFFLINE', 'ERST_FRAGEN', 'AUTOMATISCH']);
    const de = CATALOGS.de.extendedThink;
    expect(de.modes.AUS.title).toBe('Aus');
    expect(de.modes.OFFLINE.title).toBe('Offline');
    expect(de.modes.ERST_FRAGEN.title).toBe('Erst fragen');
    expect(de.modes.AUTOMATISCH.title).toBe('Automatisch');
    expect(de.recommendedBadge).toBe('Empfohlen');
  });
});

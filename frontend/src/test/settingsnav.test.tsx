/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  SETTINGS_CATEGORIES,
  SettingsCategoryNav,
  SettingsPanel,
  type SettingsCategoryId,
} from '../components/SettingsPanel';

// ─────────────────────────────────────────────────────────────────────────────
//  Kategorie-Navigation (Andi 15.07: „hier müssen wir zu weit scrollen, daher
//  organisiere das bitte übersichtlich neu"). Deckt zwei Ebenen ab:
//   1. SettingsCategoryNav isoliert (Render-Vertrag, Klick, Pfeiltasten) — kein
//      Live-Backend nötig, weil die Sub-Sektionen hier gar nicht mitmounten.
//   2. Der volle SettingsPanel-Mount: alle Kategorie-Panels bleiben IMMER
//      gemountet (nur `hidden` schaltet), der Wechsel zeigt/versteckt die
//      richtigen Panels. `fetch` wird global weggeblockt (Netz-Stub) — die
//      Kind-Sektionen (Skills/Speaker/Privacy/Weather/NightMode) fangen das
//      längst ehrlich ab (eigene Tests decken ihre Fehlerpfade schon ab).
// ─────────────────────────────────────────────────────────────────────────────

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

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

describe('SETTINGS_CATEGORIES — die sieben IA-Kategorien', () => {
  it('genau 7 Kategorien, eindeutige ids, in der dokumentierten Reihenfolge', () => {
    // 'modell-leistung' ist neu (Andi-Auftrag: Brain-Modell-Umschalter) — der
    // bisher ABSICHTLICH leere IA-Reiter (vault/tracks/DESIGN-settings-ia-
    // 2026-06-30.md) ist jetzt gefüllt.
    expect(SETTINGS_CATEGORIES.map((c) => c.id)).toEqual([
      'darstellung',
      'sprache-stimme',
      'persoenlichkeit',
      'modell-leistung',
      'faehigkeiten',
      'gedaechtnis-privatsphaere',
      'standort-integrationen',
    ]);
    expect(new Set(SETTINGS_CATEGORIES.map((c) => c.id)).size).toBe(SETTINGS_CATEGORIES.length);
  });
});

describe('SettingsCategoryNav — Render-Vertrag (WAI-ARIA-Tabs)', () => {
  it('role="tablist" + ein role="tab" je Kategorie, aktiver trägt aria-selected + tabIndex 0', () => {
    const html = renderToStaticMarkup(
      <SettingsCategoryNav active="faehigkeiten" onSelect={() => {}} />,
    );
    expect(html).toContain('role="tablist"');
    expect((html.match(/role="tab"/g) ?? []).length).toBe(SETTINGS_CATEGORIES.length);
    expect(html).toContain('aria-label="Einstellungs-Kategorien"');
    // der aktive Reiter (Fähigkeiten): selected + im Tab-Fokus erreichbar (tabindex 0).
    expect(html).toMatch(/id="settings-tab-faehigkeiten"[^>]*aria-selected="true"[^>]*tabindex="0"/);
    // alle Labels stehen drin (& kommt HTML-escaped aus renderToStaticMarkup).
    for (const c of SETTINGS_CATEGORIES) expect(html).toContain(c.label.replace('&', '&amp;'));
  });

  it('inaktive Reiter: aria-selected=false + tabindex -1 (roving tabindex)', () => {
    const html = renderToStaticMarkup(
      <SettingsCategoryNav active="darstellung" onSelect={() => {}} />,
    );
    expect(html).toMatch(/id="settings-tab-faehigkeiten"[^>]*aria-selected="false"[^>]*tabindex="-1"/);
  });
});

describe('SettingsCategoryNav — Klick + Pfeiltasten (jsdom)', () => {
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
  });

  function Host() {
    const [active, setActive] = useState<SettingsCategoryId>('darstellung');
    return <SettingsCategoryNav active={active} onSelect={setActive} />;
  }

  it('Klick auf einen Reiter ruft onSelect mit dessen id auf', async () => {
    const onSelect = vi.fn();
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsCategoryNav active="darstellung" onSelect={onSelect} />);
    });
    const tab = container.querySelector('#settings-tab-persoenlichkeit') as HTMLButtonElement;
    await act(async () => {
      tab.click();
    });
    expect(onSelect).toHaveBeenCalledWith('persoenlichkeit');
  });

  it('ArrowRight/ArrowLeft wandern durchs Set (mit Wrap-Around) und nehmen den Fokus mit', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<Host />);
    });

    const tablist = container.querySelector('[role="tablist"]') as HTMLDivElement;
    const first = container.querySelector('#settings-tab-darstellung') as HTMLButtonElement;
    first.focus();
    expect(document.activeElement).toBe(first);

    await act(async () => {
      tablist.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true }),
      );
    });
    const second = container.querySelector('#settings-tab-sprache-stimme') as HTMLButtonElement;
    expect(second.getAttribute('aria-selected')).toBe('true');
    expect(document.activeElement).toBe(second);

    // ArrowLeft von der ersten Kategorie wrapt zur letzten.
    await act(async () => {
      first.focus();
    });
    // active ist inzwischen 'sprache-stimme' — ArrowLeft geht zurück zu 'darstellung'.
    await act(async () => {
      tablist.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true, cancelable: true }),
      );
    });
    const back = container.querySelector('#settings-tab-darstellung') as HTMLButtonElement;
    expect(back.getAttribute('aria-selected')).toBe('true');
  });
});

describe('SettingsPanel — Kategorie-Wechsel zeigt/versteckt die richtigen Panels', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const baseProps = {
    open: true,
    onClose: () => {},
    theme: 'yoru' as const,
    language: 'de' as const,
    persona: 'Standard' as const,
    voice: 'coral',
    onTheme: () => {},
    onLanguage: () => {},
    onPersona: () => {},
    onVoice: () => {},
  };

  const flush = async (): Promise<void> => {
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
  };

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage());
    // Netz weg-stubben: die Kind-Sektionen fangen Fehlschläge längst ehrlich ab
    // (eigene Tests decken das ab) — hier geht es nur um die Kategorie-Navigation.
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('settingsnav-test: kein Netz')));
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

  const panel = (id: SettingsCategoryId) =>
    container.querySelector(`#settings-panel-${id}`) as HTMLElement;
  const tab = (id: SettingsCategoryId) =>
    container.querySelector(`#settings-tab-${id}`) as HTMLButtonElement;

  it('initial: nur „Darstellung" ist sichtbar, alle anderen fünf Panels tragen hidden', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();

    expect(panel('darstellung').hidden).toBe(false);
    for (const c of SETTINGS_CATEGORIES) {
      if (c.id === 'darstellung') continue;
      expect(panel(c.id).hidden, c.id).toBe(true);
    }
    // Alle Panels bleiben trotzdem gemountet: die Stimme-Gruppe steht im DOM,
    // auch während seine Kategorie gerade nicht aktiv ist (das `<select>` selbst
    // rendert erst NACH dem GET — hier weggeblockt — darum das stabile Label).
    expect(container.querySelector('label[for="settings-voice"]')).not.toBeNull();
  });

  it('Klick auf „Fähigkeiten" blendet Skills/Wecker-Eskalation ein, Darstellung aus', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();

    await act(async () => {
      tab('faehigkeiten').click();
    });

    expect(panel('faehigkeiten').hidden).toBe(false);
    expect(panel('darstellung').hidden).toBe(true);
    expect(tab('faehigkeiten').getAttribute('aria-selected')).toBe('true');
    expect(tab('darstellung').getAttribute('aria-selected')).toBe('false');
    // Inhalt der Kategorie ist da (Skills-Gruppentitel + Wecker-Eskalation-Feld).
    expect(panel('faehigkeiten').textContent).toContain('Skills');
    expect(panel('faehigkeiten').querySelector('#settings-escalation')).not.toBeNull();
  });

  it('Klick auf „Gedächtnis & Privatsphäre" zeigt Sprecher + Privatsphäre zusammen', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();

    await act(async () => {
      tab('gedaechtnis-privatsphaere').click();
    });

    const p = panel('gedaechtnis-privatsphaere');
    expect(p.hidden).toBe(false);
    expect(p.textContent).toContain('Erkannte Sprecher');
    expect(p.textContent).toContain('Privatsphäre');
  });

  it('Klick auf „Standort & Integrationen" zeigt Wetter-Ort + Nachtmodus zusammen', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();

    await act(async () => {
      tab('standort-integrationen').click();
    });

    const p = panel('standort-integrationen');
    expect(p.hidden).toBe(false);
    expect(p.textContent).toContain('Wetter-Ort');
    expect(p.textContent).toContain('Nachtmodus');
  });

  it('nur EIN Panel ist je Klick sichtbar (die anderen fünf bleiben hidden)', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();

    await act(async () => {
      tab('sprache-stimme').click();
    });

    const visible = SETTINGS_CATEGORIES.filter((c) => !panel(c.id).hidden);
    expect(visible.map((c) => c.id)).toEqual(['sprache-stimme']);
  });
});

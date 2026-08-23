/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import {
  LOOKUP_MODEL_TEXTS,
  SETTINGS_CATEGORIES,
  SETTINGS_PANEL_CATEGORY_IDS,
  SettingsPanel,
  settingsCategoryHeadingId,
  type SettingsCategoryId,
} from '../components/SettingsPanel';
import { de } from '../i18n/de';
import type { EscalationModeWire } from '../api/extendedThink';

// ─────────────────────────────────────────────────────────────────────────────
//  Kategorie-Navigation (Andi 15.07: „hier müssen wir zu weit scrollen, daher
//  organisiere das bitte übersichtlich neu").
//
//  ── UMBAU 15.08 (Andi live am iPad, nach dem Original-Auftrag) ───────────────
//  Diese Datei prüfte bis dahin die Chip-Reiterleiste `SettingsCategoryNav`
//  (Render-Vertrag, Klick, Pfeiltasten/roving tabindex). Die Leiste ist ersatzlos
//  gestrichen: die Übersicht aus sieben Karten (§3.1) ist der Einstieg, und
//  INNERHALB einer Kategorie führt genau ein Weg zurück. Ein Test einer
//  gestrichenen Komponente wäre nicht „grün zu halten", sondern zu ersetzen —
//  darum prüft die Datei jetzt dieselbe Frage an der neuen Naht:
//   1. Kategorie-Wechsel läuft über die ÜBERSICHTSKARTE (nicht mehr über einen
//      Reiter) und zeigt/versteckt weiterhin genau ein Panel.
//   2. Die ARIA-Naht ist nicht tot: `region` + eigene Überschrift statt
//      `tabpanel` + `aria-labelledby` auf eine verschwundene Reiter-Id.
//  ── NACHTRAG 21.08 (Andi: „Dort ist immer noch die Zwischenseite") ──────────
//  „Darstellung" ist keine Kategorie mehr, sondern eine AUSLÖSER-Karte: sie hebt
//  die Galerie und lässt die Schale auf der Übersicht stehen. Alle Schleifen über
//  „jede Kategorie hat ein Panel" laufen darum jetzt über
//  {@link SETTINGS_PANEL_CATEGORY_IDS} (die sechs mit Panel) statt über
//  SETTINGS_CATEGORIES (die sieben Karten) — und wo eine Kategorie nur als
//  „irgendwo herkommen" gebraucht wurde, steht eine mit Panel.
//  Unverändert bleibt: alle Kategorie-Panels sind IMMER gemountet (nur `hidden`
//  schaltet). `fetch` wird global weggeblockt (Netz-Stub) — die Kind-Sektionen
//  (Skills/Speaker/Privacy/Weather/NightMode) fangen das längst ehrlich ab
//  (eigene Tests decken ihre Fehlerpfade schon ab).
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
    // Neuordnung 07.08 (Andi-Auftrag: „ordne die Kategorien in den Settings
    // geordnet an") — Nutzungs-Rhythmus statt Online-Grad als Ordnungsprinzip:
    // Alltägliches vorn (Darstellung · Sprache & Stimme · Persönlichkeit ·
    // Zuhause & Integrationen — Reiter, die man beiläufig oder gelegentlich
    // öffnet), Set-once/Technik hinten (Online & Nachschlagen · Gedächtnis &
    // Privatsphäre · Modell & Leistung — selten angefasst, ganz hinten die
    // reine Technik-Einstellung). Reine Umsortierung: keine Kategorie wurde
    // umbenannt oder zusammengelegt (s. SettingsPanel.tsx, SETTINGS_CATEGORY_IDS).
    expect(SETTINGS_CATEGORIES.map((c) => c.id)).toEqual([
      'darstellung',
      'sprache-stimme',
      'persoenlichkeit',
      'zuhause-integrationen',
      'online-nachschlagen',
      'gedaechtnis-privatsphaere',
      'modell-leistung',
    ]);
    expect(new Set(SETTINGS_CATEGORIES.map((c) => c.id)).size).toBe(SETTINGS_CATEGORIES.length);
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
  // Seit der Schalen-Scheibe (15.08 §3.1) ist die Kategorie-ÜBERSICHT die
  // Einstiegs-Ebene — und seit demselben Tag der EINZIGE Weg hinein: die
  // Chip-Reiterleiste ist gestrichen (s. Datei-Kopf). Ein Wechsel ist darum
  // „zurück auf die Übersicht, dann die andere Karte".
  const card = (id: SettingsCategoryId) =>
    container.querySelector(`#settings-card-${id}`) as HTMLButtonElement;
  const back = () => container.querySelector('.settings__back') as HTMLButtonElement;
  const enterCategory = async (id: SettingsCategoryId): Promise<void> => {
    if (back()) {
      await act(async () => {
        back().click();
      });
    }
    await act(async () => {
      card(id).click();
    });
  };

  it('Einstieg: die Übersicht steht da, KEIN Kategorie-Panel ist sichtbar', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();

    // Die sieben Karten in Andis Ordnung (SETTINGS_CATEGORY_IDS, 07.08).
    const cards = Array.from(container.querySelectorAll('.settings__catcard'));
    expect(cards).toHaveLength(SETTINGS_CATEGORIES.length);
    // Auf der Übersicht gibt es keine Reiter — und kein Panel ist offen.
    expect(container.querySelector('[role="tablist"]')).toBeNull();
    for (const id of SETTINGS_PANEL_CATEGORY_IDS) expect(panel(id).hidden, id).toBe(true);
    // …aber alles bleibt gemountet (Hooks/Fetches laufen weiter).
    expect(container.querySelector('label[for="settings-voice"]')).not.toBeNull();
  });

  // ── Andi 21.08.: „Dort ist immer noch die Zwischenseite." ──────────────────
  //    Ein Klick auf „Darstellung" ist der ganze Weg zur Galerie. Die Schale
  //    darf dabei NICHT in eine Kategorie laufen — sonst läge unter dem
  //    Vollbild-Overlay wieder eine Seite, die „Fertig" freilegt.
  it('die Karte „Darstellung" betritt KEINE Kategorie — sie hat gar kein Panel mehr', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();

    // Die Karte steht weiter an ihrem Platz (Andis Rhythmus vom 07.08.) …
    expect(card('darstellung')).not.toBeNull();
    // … aber es gibt kein Panel dahinter, das man betreten könnte.
    expect(container.querySelector('#settings-panel-darstellung')).toBeNull();
    expect(SETTINGS_PANEL_CATEGORY_IDS).not.toContain('darstellung');

    await act(async () => {
      card('darstellung').click();
    });
    await flush();

    // Die Schale steht weiter auf der Übersicht: die sieben Karten sind da,
    // der „‹ Einstellungen"-Rückweg (den es nur INNERHALB einer Kategorie gibt)
    // ist es nicht. Genau daran hängt, wohin „Fertig" zurückführt.
    expect(container.querySelectorAll('.settings__catcard')).toHaveLength(
      SETTINGS_CATEGORIES.length,
    );
    expect(back()).toBeNull();
    for (const id of SETTINGS_PANEL_CATEGORY_IDS) expect(panel(id).hidden, id).toBe(true);
  });

  it('nach dem Tipp auf „Sprache & Stimme": nur dieses Panel ist sichtbar, alle anderen tragen hidden', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();
    await enterCategory('sprache-stimme');

    expect(panel('sprache-stimme').hidden).toBe(false);
    for (const id of SETTINGS_PANEL_CATEGORY_IDS) {
      if (id === 'sprache-stimme') continue;
      expect(panel(id).hidden, id).toBe(true);
    }
    // Alle Panels bleiben trotzdem gemountet: die Stimme-Gruppe steht im DOM,
    // auch während seine Kategorie gerade nicht aktiv ist (das `<select>` selbst
    // rendert erst NACH dem GET — hier weggeblockt — darum das stabile Label).
    expect(container.querySelector('label[for="settings-voice"]')).not.toBeNull();
  });

  it('Wechsel auf „Online & Nachschlagen" blendet Eskalations-Stufen/Lookup-Modell ein, Darstellung aus', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();
    await enterCategory('persoenlichkeit');
    await enterCategory('online-nachschlagen');

    expect(panel('online-nachschlagen').hidden).toBe(false);
    expect(panel('persoenlichkeit').hidden).toBe(true);
    // Inhalt der Kategorie ist da: die vier Eskalations-Stufen (Andi-Auftrag
    // 26.07 — vorher hatte diese Einstellung KEIN UI-Element) direkt über dem
    // Nachschlag-Modell (zog aus der ehemaligen Fähigkeiten-Kategorie hierher).
    expect(panel('online-nachschlagen').textContent).toContain('Wenn Hoshi etwas nicht weiß');
    expect(panel('online-nachschlagen').textContent).toContain('Online-Nachschlag');
  });

  it('Wechsel auf „Gedächtnis & Privatsphäre" zeigt Sprecher + Privatsphäre zusammen', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();
    await enterCategory('persoenlichkeit');
    await enterCategory('gedaechtnis-privatsphaere');

    const p = panel('gedaechtnis-privatsphaere');
    expect(p.hidden).toBe(false);
    expect(p.textContent).toContain('Erkannte Sprecher');
    expect(p.textContent).toContain('Privatsphäre');
  });

  it('Wechsel auf „Zuhause & Integrationen" zeigt Wetter-Ort + Skills + Wecker-Eskalation + Nachtmodus zusammen', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();
    await enterCategory('persoenlichkeit');
    await enterCategory('zuhause-integrationen');

    // Umbenannt + erweitert 26.07: die ehemalige Fähigkeiten-Kategorie ist
    // aufgelöst, Skills-Toggles + Wecker-Eskalation stehen jetzt hier neben
    // Wetter-Ort/Nachtmodus (vorher „Standort & Integrationen").
    const p = panel('zuhause-integrationen');
    expect(p.hidden).toBe(false);
    expect(p.textContent).toContain('Wetter-Ort');
    expect(p.textContent).toContain('Skills');
    expect(p.querySelector('#settings-escalation')).not.toBeNull();
    expect(p.textContent).toContain('Nachtmodus');
  });

  it('nur EIN Panel ist je Wechsel sichtbar (die anderen sechs bleiben hidden)', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();
    await enterCategory('persoenlichkeit');
    await enterCategory('sprache-stimme');

    const visible = SETTINGS_PANEL_CATEGORY_IDS.filter((id) => !panel(id).hidden);
    expect(visible).toEqual(['sprache-stimme']);
  });

  // ── Was von der Reiter-Leiste übrig bleiben MUSS: die Beschriftung ──────────
  //    Ein Panel ohne Tablist darf kein `tabpanel` sein, und `aria-labelledby`
  //    darf nicht auf eine Id zeigen, die mit der Leiste verschwunden ist. Beides
  //    wäre stilles, kaputtes ARIA — deshalb steht es hier als Vertrag.

  it('jedes Panel ist eine `region`, benannt von seiner eigenen Überschrift (kein totes ARIA)', async () => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();
    await enterCategory('persoenlichkeit');

    for (const id of SETTINGS_PANEL_CATEGORY_IDS) {
      const p = panel(id);
      expect(p.getAttribute('role'), id).toBe('region');
      expect(p.getAttribute('aria-labelledby'), id).toBe(settingsCategoryHeadingId(id));
      const heading = container.querySelector(`#${settingsCategoryHeadingId(id)}`);
      expect(heading, id).not.toBeNull();
      expect(heading!.textContent, id).toBe(de.settings.categories[id]);
    }
    // Und wirklich nichts von der alten Leiste ist noch im Bild.
    expect(container.querySelector('[role="tablist"]')).toBeNull();
    expect(container.querySelectorAll('[role="tab"]')).toHaveLength(0);
    expect(container.querySelectorAll('[role="tabpanel"]')).toHaveLength(0);
    expect(container.querySelector('[id^="settings-tab-"]')).toBeNull();
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  Nachschlag-Modell-Kaskade (bündig-Sweep, Muster-Übertrag „TTS-Dropdown"):
//  die Nachschlag-Modell-Karte betrifft nur, WOMIT Hoshi online nachschaut —
//  bei Extended-Think-Stufe „Aus" schaut sie NIE nach, die Karte war vorher
//  trotzdem immer da („heute beziehungslos"). Jetzt blendet sie sich aus,
//  sobald der Server-Ist-Zustand das sicher bestätigt (effectiveMode==='AUS'),
//  bleibt aber sichtbar, solange die Stufe noch unbekannt ist (lädt/Fehler) —
//  nie fälschlich verstecken. Getrennte Fetches (extended-think/lookup-model)
//  bleiben getrennt, s. `OnlineNachschlagenGroup` in SettingsPanel.tsx.
// ─────────────────────────────────────────────────────────────────────────────

describe('Online & Nachschlagen — Nachschlag-Modell-Kaskade', () => {
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

  const lookupModelBody = {
    aktiv: 'gpt-5.4-nano',
    modelle: [{ id: 'gpt-5.4-nano', label: 'OpenAI Nano', centsProLookup: 0.1 }],
  };

  /** Routing-Fake: `/extended-think` liefert die Stufe (oder scheitert), `/lookup-model` antwortet fest. */
  const stubFetch = (extendedThink: 'error' | EscalationModeWire) => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url.includes('/settings/extended-think')) {
        if (extendedThink === 'error') throw new Error('kaskaden-test: kein Netz');
        return {
          ok: true,
          status: 200,
          json: async () => ({
            mode: extendedThink,
            ceilingOpen: true,
            locked: false,
            effectiveMode: extendedThink,
          }),
        };
      }
      if (url.includes('/settings/lookup-model')) {
        return { ok: true, status: 200, json: async () => lookupModelBody };
      }
      throw new Error(`Kaskaden-Test: unerwartete URL ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  };

  const openOnlineNachschlagen = async (): Promise<HTMLElement> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();
    // Übersicht → Kategorie (Schalen-Scheibe 15.08 §3.1): die Karte ist der
    // Einstieg, die Chip-Reiterleiste lebt erst innerhalb der Kategorie.
    const card = container.querySelector('#settings-card-online-nachschlagen') as HTMLButtonElement;
    await act(async () => {
      card.click();
    });
    await flush();
    return container.querySelector('#settings-panel-online-nachschlagen') as HTMLElement;
  };

  it('Stufe AUTOMATISCH (≠ Aus): die Nachschlag-Modell-Karte ist sichtbar', async () => {
    stubFetch('AUTOMATISCH');
    const panel = await openOnlineNachschlagen();
    expect(panel.querySelector('#settings-lookup-model')).not.toBeNull();
    // LOOKUP_MODEL_TEXTS.hint ist eindeutig (anders als .label, das als Teilstring
    // auch im Extended-Think-Hinweis vorkommt: „…schnellen Online-Nachschlag…").
    expect(panel.textContent).toContain(LOOKUP_MODEL_TEXTS.hint);
  });

  it('Stufe AUS: die Nachschlag-Modell-Karte ist am Ende unsichtbar (kein disabled Select, echtes Ausblenden)', async () => {
    // Im kurzen Fenster VOR dem Extended-Think-GET ist die Stufe noch unbekannt —
    // die Karte darf da (richtigerweise) kurz mitmounten/fetchen, s. den
    // „unbekannt"-Test unten. Hier zählt der Endzustand NACH dem Settle.
    stubFetch('AUS');
    const panel = await openOnlineNachschlagen();
    expect(panel.querySelector('#settings-lookup-model')).toBeNull();
    expect(panel.textContent).not.toContain(LOOKUP_MODEL_TEXTS.hint);
  });

  it('Stufe unbekannt (Extended-Think-Fetch scheitert): die Karte bleibt sichtbar — nie fälschlich verstecken', async () => {
    stubFetch('error');
    const panel = await openOnlineNachschlagen();
    expect(panel.querySelector('#settings-lookup-model')).not.toBeNull();
  });
});

/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { readFileSync } from 'node:fs';
// Namensraum-Import: damit „das Bauteil ist wirklich weg" eine Aussage über die
// EXPORTE ist und nicht über den Quelltext (der es im Grabstein-Kommentar noch
// erwähnen darf und soll).
import * as SettingsPanelModule from '../components/SettingsPanel';
import {
  SETTINGS_ANCHOR_CATEGORY,
  SETTINGS_CATEGORY_IDS,
  SETTINGS_PANEL_CATEGORY_IDS,
  SettingsCategoryOverview,
  SettingsPanel,
  settingsCategoryCardId,
  settingsCategoryHeadingId,
  type SettingsCategoryId,
} from '../components/SettingsPanel';
import { de } from '../i18n/de';

// ═════════════════════════════════════════════════════════════════════════════
//  SETTINGS-SCHALE (Design DESIGN-widgets-settings-2026-08-15.md §3.1)
//
//  Der Drawer hat zwei Ebenen: eine Übersicht aus sieben gleich großen Karten
//  als EINSTIEG, und die Kategorie dahinter — dort führt GENAU EIN Weg zurück,
//  „‹ Einstellungen". Fünf Dinge sind hier festgenagelt:
//   1. Die Übersicht rendert genau sieben gleich große Karten in Andis Ordnung.
//   2. Übersicht → Kategorie → zurück funktioniert in beide Richtungen.
//   3. Deep-Link-Anker ÜBERSPRINGEN die Übersicht (Regressionstest: ein
//      kontextuelles Zahnrad darf nie einen Extra-Tipp kosten).
//   4. Kein Panel wird je unmountet — die Ebene schaltet nur `hidden`.
//   5. NACHTRAG 15.08 (Andi live am iPad, nach dem Original-Auftrag): innerhalb
//      einer Kategorie ist die Chip-Reiterleiste KOMPLETT weg — auch nach einem
//      Deep-Link-Sprung. Zwei Ebenen, je ein Ausgang; eine zweite, parallele
//      Wechsel-Möglichkeit hätte die Optionen-Wand wieder aufgebaut, die die
//      Übersicht gerade abgeräumt hat. Das Panel ist darum kein `tabpanel` mehr
//      (ohne Tablist wäre die Rolle eine Lüge und `aria-labelledby` zeigte auf
//      eine verschwundene Id), sondern eine `region` mit eigener Überschrift.
//   6. NACHTRAG 21.08 (Andi: „Dort ist immer noch die Zwischenseite"): SIEBEN
//      Karten, aber nur SECHS Panels. „Darstellung" ist eine AUSLÖSER-Karte —
//      sie hebt die Galerie und lässt die Schale auf der Übersicht stehen, hat
//      also gar kein Panel mehr. Jede Schleife über „alle Panels" läuft darum
//      hier über {@link SETTINGS_PANEL_CATEGORY_IDS}; SETTINGS_CATEGORY_IDS
//      bleibt die Wahrheit über die KARTEN (Punkt 1 zählt weiter sieben).
//      Dass die Auslöser-Karte keine Kategorie betritt, nagelt settingsnav fest.
// ═════════════════════════════════════════════════════════════════════════════

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

// ─────────────────────────────────────────────────────────────────────────────
//  1) Die Übersicht selbst — isoliert, ohne Netz (Render-Vertrag)
// ─────────────────────────────────────────────────────────────────────────────

describe('SettingsCategoryOverview — sieben gleich große Karten', () => {
  const html = renderToStaticMarkup(<SettingsCategoryOverview onSelect={() => {}} />);

  it('genau sieben Karten — keine Füll-Kachel für die achte Zelle', () => {
    expect((html.match(/class="settings__catcard"/g) ?? []).length).toBe(7);
    expect(SETTINGS_CATEGORY_IDS).toHaveLength(7);
  });

  it('in Andis Ordnung (SETTINGS_CATEGORY_IDS vom 07.08) — die Übersicht sortiert NICHT um', () => {
    const order = Array.from(html.matchAll(/id="settings-card-([a-z-]+)"/g)).map((m) => m[1]);
    expect(order).toEqual([...SETTINGS_CATEGORY_IDS]);
  });

  it('je Karte: Glyph + Name + EIN Halbsatz, alle aus dem Katalog', () => {
    for (const id of SETTINGS_CATEGORY_IDS) {
      expect(html).toContain(`id="${settingsCategoryCardId(id)}"`);
      expect(html, id).toContain(de.settings.categories[id].replace('&', '&amp;'));
      expect(html, id).toContain(de.settings.categoryBlurbs[id]);
    }
    // Sieben Glyphen, alle aria-hidden (der Name trägt die Semantik, nie das Icon).
    expect((html.match(/class="settings__catcardglyph" aria-hidden="true"/g) ?? []).length).toBe(7);
    expect((html.match(/<svg /g) ?? []).length).toBe(7);
  });

  it('gleich groß: alle Karten tragen dieselbe Klasse, das Raster gibt die Maße vor', () => {
    // Keine Sonderfall-Klasse an einer einzelnen Karte — „gleich groß" ist eine
    // CSS-Eigenschaft des Rasters, nicht der einzelnen Kachel.
    expect(html).not.toMatch(/class="settings__catcard [^"]/);
    const css = readFileSync('src/styles/themes.css', 'utf8');
    const start = css.indexOf('.settings__catgrid {');
    expect(start).toBeGreaterThanOrEqual(0);
    const grid = css.slice(start, css.indexOf('}', start));
    // Zwei Spalten im 340px-Drawer …
    expect(grid).toContain('grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))');
    // … und gleich hohe Zeilen, egal wie lang der Halbsatz ist.
    expect(grid).toContain('grid-auto-rows: 1fr');
  });

  it('die Karte ist ein Knopf (Tastatur/Tap gleichermaßen), kein klickbares div', () => {
    expect(html).toMatch(/<button type="button" id="settings-card-darstellung"/);
  });

  it('im echten Drawer sind es GENAU zwei Spalten (Rechnung aus den CSS-Werten)', () => {
    // Kein Browser in diesem Pod ⇒ die Behauptung „zwei Spalten im 340px-Drawer"
    // wird aus den tatsächlichen CSS-Zahlen nachgerechnet statt geglaubt.
    const css = readFileSync('src/styles/themes.css', 'utf8');
    const num = (block: string, prop: RegExp): number => {
      const hit = prop.exec(block);
      expect(hit, `${prop} fehlt`).not.toBeNull();
      return Number(hit![1]);
    };
    const drawer = css.slice(css.indexOf('.settings {'), css.indexOf('}', css.indexOf('.settings {')));
    const grid = css.slice(
      css.indexOf('.settings__catgrid {'),
      css.indexOf('}', css.indexOf('.settings__catgrid {')),
    );
    const drawerWidth = num(drawer, /width:\s*min\((\d+)px/);
    const pad = num(drawer, /padding:\s*(\d+)px/);
    const minCol = num(grid, /minmax\((\d+)px/);
    const gap = num(grid, /gap:\s*(\d+)px/);

    const inner = drawerWidth - 2 * pad; // 380 − 40 = 340
    const fits = (cols: number) => cols * minCol + (cols - 1) * gap <= inner;
    expect(inner).toBe(340);
    expect(fits(2)).toBe(true);
    expect(fits(3)).toBe(false);
    // 7 Karten auf 2 Spalten = 4 Zeilen, die achte Zelle bleibt leer.
    expect(Math.ceil(SETTINGS_CATEGORY_IDS.length / 2)).toBe(4);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  2) Die Schale im vollen Panel — Navigation und Deep-Link
// ─────────────────────────────────────────────────────────────────────────────

describe('SettingsPanel — Schalen-Navigation Übersicht ↔ Kategorie', () => {
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
    // Netz weg-stubben: die Kind-Sektionen fangen das längst ehrlich ab (eigene
    // Tests decken ihre Fehlerpfade) — hier geht es nur um die Schale.
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('settingsshell-test: kein Netz')));
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

  const mount = async (el: React.ReactElement): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(el);
    });
    await flush();
  };

  const panel = (id: SettingsCategoryId) =>
    container.querySelector(`#settings-panel-${id}`) as HTMLElement;
  const overview = () => container.querySelector('.settings__catgrid');
  const tablist = () => container.querySelector('[role="tablist"]');
  const back = () => container.querySelector('.settings__back') as HTMLButtonElement;

  it('Übersicht → Kategorie → zurück: beide Wege gehen, ohne je ein Panel zu unmounten', async () => {
    await mount(<SettingsPanel {...baseProps} />);

    // (a) Einstieg: Übersicht, keine Reiter, kein sichtbares Panel.
    expect(overview()).not.toBeNull();
    expect(tablist()).toBeNull();
    expect(back()).toBeNull();
    for (const id of SETTINGS_PANEL_CATEGORY_IDS) expect(panel(id).hidden, id).toBe(true);

    // (b) Tipp auf eine Karte → genau diese Kategorie. KEINE Reiterleiste (15.08),
    //     nur der Rückweg — und die Kategorie nennt sich selbst per Überschrift.
    await act(async () => {
      (
        container.querySelector(`#${settingsCategoryCardId('persoenlichkeit')}`) as HTMLButtonElement
      ).click();
    });
    expect(overview()).toBeNull();
    expect(tablist()).toBeNull();
    expect(panel('persoenlichkeit').hidden).toBe(false);
    const visible = SETTINGS_PANEL_CATEGORY_IDS.filter((id) => !panel(id).hidden);
    expect(visible).toEqual(['persoenlichkeit']);

    // (c) „‹ Einstellungen" führt zurück auf die Übersicht.
    expect(back().getAttribute('aria-label')).toBe(de.settings.overviewBackAria);
    expect(back().textContent).toContain(de.settings.overviewBack);
    await act(async () => {
      back().click();
    });
    expect(overview()).not.toBeNull();
    expect(tablist()).toBeNull();
    for (const id of SETTINGS_PANEL_CATEGORY_IDS) expect(panel(id).hidden, id).toBe(true);

    // (d) Alles blieb die ganze Zeit gemountet — kein Panel ist je verschwunden.
    for (const id of SETTINGS_PANEL_CATEGORY_IDS) expect(panel(id)).not.toBeNull();
  });

  // Gefahren auf „Persönlichkeit" statt wie bis 21.08. auf „Darstellung": die
  // Auslöser-Karte betritt keine Kategorie mehr, es gäbe hier also gar kein
  // Panel und keinen Rückweg zu prüfen. Die Aussage („innerhalb einer Kategorie
  // keine Reiterleiste") gilt unverändert — sie braucht nur eine Kategorie.
  it('INNERHALB einer Kategorie gibt es keine Chip-Reiterleiste mehr — nur „‹ Einstellungen" (15.08)', async () => {
    await mount(<SettingsPanel {...baseProps} />);
    await act(async () => {
      (
        container.querySelector(`#${settingsCategoryCardId('persoenlichkeit')}`) as HTMLButtonElement
      ).click();
    });

    // Weder die Leiste noch ein einzelner Reiter noch eine Reiter-Id bleiben übrig.
    expect(tablist()).toBeNull();
    expect(container.querySelectorAll('[role="tab"]')).toHaveLength(0);
    expect(container.querySelector('[id^="settings-tab-"]')).toBeNull();
    expect(container.querySelector('.settings__catnav')).toBeNull();
    // Der Rückweg ist der einzige Ausgang.
    expect(back()).not.toBeNull();

    // Und die ARIA-Naht ist NICHT tot zurückgelassen: das Panel ist eine `region`,
    // benannt von seiner eigenen Überschrift (kein `tabpanel` ohne Tablist, kein
    // `aria-labelledby` auf eine Id, die es nicht mehr gibt).
    const p = panel('persoenlichkeit');
    expect(p.getAttribute('role')).toBe('region');
    const headingId = p.getAttribute('aria-labelledby')!;
    expect(headingId).toBe(settingsCategoryHeadingId('persoenlichkeit'));
    const heading = container.querySelector(`#${headingId}`) as HTMLElement;
    expect(heading).not.toBeNull();
    expect(heading.textContent).toBe(de.settings.categories.persoenlichkeit);

    // Nichts Totes bleibt zurück: weder das Bauteil (es hatte keinen Aufrufer
    // mehr) noch seine CSS-Regeln (eine Regel ohne Element ist Ballast, den der
    // nächste Leser für Absicht hält).
    expect(SettingsPanelModule).not.toHaveProperty('SettingsCategoryNav');
    expect(SettingsPanelModule).not.toHaveProperty('settingsTabId');
    // Dasselbe für die Zwischenseite (21.08.): `ThemeSection` war ihr einziger
    // Inhalt und hat mit ihr keinen Aufrufer mehr — sie ist gelöscht, nicht
    // bloß nicht mehr gerendert (ein toter Export lädt zum Wieder-Einbau ein).
    expect(SettingsPanelModule).not.toHaveProperty('ThemeSection');
    const css = readFileSync('src/styles/themes.css', 'utf8');
    expect(css).not.toContain('.settings__cattab {');
    expect(css).not.toContain('.settings__catnav {');
  });

  it('jede Kategorie MIT Panel nennt sich selbst — jede Überschrift-Id existiert genau einmal', async () => {
    await mount(<SettingsPanel {...baseProps} />);
    for (const id of SETTINGS_PANEL_CATEGORY_IDS) {
      const heads = container.querySelectorAll(`#${settingsCategoryHeadingId(id)}`);
      expect(heads, id).toHaveLength(1);
      expect(panel(id).getAttribute('aria-labelledby'), id).toBe(settingsCategoryHeadingId(id));
    }
    // Gegenprobe zur Zwischenseite (21.08.): sieben Karten, sechs Panels — und
    // die Auslöser-Karte lässt auch keine verwaiste Überschrift zurück, die ein
    // Screenreader als leere Kategorie vorlesen würde.
    expect(SETTINGS_CATEGORY_IDS).toHaveLength(SETTINGS_PANEL_CATEGORY_IDS.length + 1);
    expect(container.querySelector(`#${settingsCategoryHeadingId('darstellung')}`)).toBeNull();
    expect(panel('darstellung')).toBeNull();
  });

  it('Deep-Link ÜBERSPRINGT die Übersicht und landet direkt in der Kategorie', async () => {
    // Regressionstest für alle drei verdrahteten Anker (Sprecher-Chip,
    // Wecker-Banner, Wetter-Ort): ein kontextuelles Zahnrad darf nie einen
    // Extra-Tipp kosten.
    for (const [anchor, category] of Object.entries(SETTINGS_ANCHOR_CATEGORY) as [
      string,
      SettingsCategoryId,
    ][]) {
      await mount(<SettingsPanel {...baseProps} category={category} />);
      expect(overview(), anchor).toBeNull();
      expect(panel(category).hidden, anchor).toBe(false);
      // Und der Rückweg steht bereit — auch wer per Deep-Link kam, findet die Übersicht.
      expect(back(), anchor).not.toBeNull();
      // …aber eben AUCH hier keine Reiterleiste (15.08): der Sprung landet in
      // derselben Kategorie-Ebene wie der Tipp auf eine Karte, nicht in einer
      // zweiten mit eigener Navigation.
      expect(tablist(), anchor).toBeNull();

      const r = root;
      root = null;
      await act(async () => r!.unmount());
    }
  });

  it('Wieder-Öffnen ohne Deep-Link kehrt auf die Übersicht zurück (der Einstieg ist der Einstieg)', async () => {
    await mount(<SettingsPanel {...baseProps} />);
    await act(async () => {
      (
        container.querySelector(`#${settingsCategoryCardId('zuhause-integrationen')}`) as HTMLButtonElement
      ).click();
    });
    expect(overview()).toBeNull();

    // Schließen …
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} open={false} />);
    });
    // … und normal (ohne category) wieder öffnen.
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} open />);
    });
    expect(overview()).not.toBeNull();
  });
});

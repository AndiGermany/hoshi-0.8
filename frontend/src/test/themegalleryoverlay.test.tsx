/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { readFileSync } from 'node:fs';
import { SettingsPanel } from '../components/SettingsPanel';
import { CATALOGS, SUPPORTED_UI_LANGUAGES } from '../i18n';
import { parseThemeManifest, primeThemeManifest, resetThemeCatalog } from '../styles/themeCatalog';

// ═════════════════════════════════════════════════════════════════════════════
//  DIE DESIGN-GALERIE IST EIN VOLLBILD-OVERLAY (Andi-Auftrag 19.08., wörtlich)
//
//   „Wenn ich das Design in den Einstellungen auswähle, passiert alles auf einem
//    kleinen Fenster rechts. Dann muss ich nochmal einen Button drücken, damit
//    ich mir alle Designs anzeigen lassen kann. Das macht keinen Sinn. Ich
//    möchte, dass direkt ein Overlay über die komplette Seite geht, wo die
//    Designs präsentiert werden."  UND:
//   „Die Geschichten zu den Designs laufen aus den Boxen."
//
//  Der Befund dahinter war KEIN Styling-Versehen, sondern zwei harte Fehler —
//  beide headless am laufenden Dev-Server gemessen (1366×1024):
//
//   1. ORT IM BAUM. Die Galerie hing in `ThemeSection`, also im `<aside
//      class="settings">`. Dieses Element trägt einen `transform` fürs Slide-in,
//      und ein Element mit `transform` wird zum ENTHALTENDEN BLOCK jedes
//      `position: fixed`-Nachfahren. Gemessen: Backdrop 379×1024 statt
//      1366×1024 — die Galerie KONNTE die Seite nie überdecken. Kein Stylesheet
//      hätte das je geheilt.
//   2. KASKADE. `.themegallery__card` (Z. 593) und `.settings__theme` (Z. 667)
//      hatten dieselbe Spezifität — die spätere Regel gewann, die Kachel-Regeln
//      verloren. Die Karten blieben 40-px-Swatch-ZEILEN, die Szene wurde in die
//      40-px-Spalte gequetscht (gemessen: 40×25 px) und die Charakter-Zeile
//      landete darunter in derselben 40-px-Spalte. GENAU das sind die
//      „Geschichten, die aus den Boxen laufen".
//
//  Diese Datei hält beide Ursachen fest — die eine im DOM, die andere im CSS.
// ═════════════════════════════════════════════════════════════════════════════

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

const THEMES_CSS = readFileSync('src/styles/themes.css', 'utf8');
const MANIFEST = parseThemeManifest(JSON.parse(readFileSync('public/themes/manifest.json', 'utf8')));

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

/**
 * Der Regelblock eines Selektors aus themes.css.
 *
 * Bewusst die LETZTE Fundstelle: erstens gewinnt bei gleicher Spezifität genau
 * die (das ist die Falle, um die es in dieser Datei geht), zweitens steht ein
 * Selektor oft auch noch als Zeile einer Sammel-Regel weiter oben — `indexOf`
 * läse dann den falschen Block.
 */
function ruleFor(selector: string): string {
  const at = THEMES_CSS.lastIndexOf(selector + ' {');
  expect(at, `Selektor fehlt: ${selector}`).toBeGreaterThan(-1);
  return THEMES_CSS.slice(at, THEMES_CSS.indexOf('}', at));
}

describe('Ein Klick auf „Darstellung" — die Galerie kommt SOFORT, über die ganze Seite', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;
  let themed: string[] = [];

  const baseProps = {
    open: true,
    onClose: () => {},
    theme: 'aoi' as const,
    language: 'de' as const,
    persona: 'Standard' as const,
    voice: 'coral',
    onTheme: (t: string) => themed.push(t),
    onLanguage: () => {},
    onPersona: () => {},
    onVoice: () => {},
  };

  const flush = async (): Promise<void> => {
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
  };

  beforeEach(async () => {
    themed = [];
    primeThemeManifest(MANIFEST);
    vi.stubGlobal('localStorage', memoryStorage());
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('galerie-test: kein Netz')));
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} />);
    });
    await flush();
  });

  afterEach(async () => {
    if (root) {
      const r = root;
      await act(async () => r.unmount());
      root = null;
    }
    container.remove();
    resetThemeCatalog();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  const card = () => container.querySelector('#settings-card-darstellung') as HTMLButtonElement;
  const backdrop = () => container.querySelector('.overlay') as HTMLElement | null;
  const isOpen = () => !!backdrop()?.className.includes('is-open');
  const click = async (el: HTMLElement) => {
    await act(async () => {
      el.click();
    });
    await flush();
  };

  it('vor dem Klick ist die Galerie zu', () => {
    expect(isOpen()).toBe(false);
  });

  it('EIN Klick auf die Design-Karte öffnet sie — kein zweiter Knopf dazwischen', async () => {
    await click(card());
    expect(isOpen()).toBe(true);
    // Die Kernzusage in Zahlen: von „Einstellungen offen" bis „alle Designs
    // sichtbar" liegt GENAU ein Klick. Vorher waren es zwei.
    expect(container.querySelectorAll('[role="radio"]').length).toBeGreaterThan(10);
  });

  it('das Overlay hängt AUSSERHALB des Drawers — sonst sperrt dessen transform es ein', async () => {
    await click(card());
    // Ursache 1 aus dem Dateikopf, als ausführbarer Vertrag: kein Overlay
    // unterhalb von `aside.settings`.
    expect(container.querySelector('aside.settings .overlay')).toBeNull();
    // …aber sehr wohl im transform-freien Wirt daneben.
    const host = container.querySelector('.settings__galleryhost');
    expect(host).not.toBeNull();
    expect(host?.querySelector('.overlay')).not.toBeNull();
    expect(ruleFor('.settings__galleryhost')).toContain('display: contents');
  });

  it('eine Auswahl WENDET AN und lässt die Galerie offen (Andi will vergleichen)', async () => {
    await click(card());
    const radios = Array.from(container.querySelectorAll('[role="radio"]')) as HTMLElement[];
    const notActive = radios.find((r) => r.getAttribute('aria-checked') === 'false')!;
    await click(notActive);
    expect(themed).toHaveLength(1); // sofort angewendet
    expect(isOpen()).toBe(true); // …und NICHT zugefallen
  });

  // ── Andi 21.08.: „Dort ist immer noch die Zwischenseite." ───────────────────
  //    Der Rückweg ist der Kern der Beschwerde. Bis 21.08. betrat der Klick
  //    zusätzlich die Kategorie „Darstellung" — sie lag unsichtbar unter dem
  //    Vollbild-Overlay, und „Fertig" legte sie frei: eine Seite, die genau die
  //    zwei Dinge trug, die im Overlay schon standen. Jetzt hebt die Karte NUR
  //    das Overlay; man kehrt dorthin zurück, wo man hergekommen ist.
  it('„Fertig" führt zurück zur ÜBERSICHT — kein Zwischenhalt, der Drawer bleibt offen', async () => {
    await click(card());
    // Während das Overlay steht, ist darunter unverändert die Übersicht: keine
    // Kategorie ist betreten, es gibt also nichts, was „Fertig" freilegen könnte.
    expect(container.querySelector('.settings__catgrid')).not.toBeNull();
    expect(container.querySelector('.settings__back')).toBeNull();

    await click(container.querySelector('.themegallery__done') as HTMLElement);
    expect(isOpen()).toBe(false);
    expect(container.querySelector('.settings-overlay.is-open')).not.toBeNull();

    // Nach „Fertig" steht man wieder auf der Übersicht — derselbe Ort, von dem
    // aus man losgegangen ist. Der frühere Wieder-Einstiegs-Knopf im Panel ist
    // damit ersatzlos weg: der Weg zurück in die Galerie IST die Karte.
    expect(container.querySelector('.settings__catgrid')).not.toBeNull();
    expect(container.querySelector('.settings__back')).toBeNull();
    expect(container.querySelector('.settings__themegallerybtn')).toBeNull();

    // …und dieselbe Karte öffnet sie beliebig oft wieder. Ein Weg hinein, ein
    // Weg hinaus.
    await click(card());
    expect(isOpen()).toBe(true);
    await click(container.querySelector('.themegallery__close') as HTMLElement);
    expect(isOpen()).toBe(false);
    expect(container.querySelector('.settings__catgrid')).not.toBeNull();
  });

  it('Escape gehört der Galerie EXKLUSIV — sie geht zu, der Drawer bleibt', async () => {
    let drawerClosed = 0;
    await act(async () => {
      root!.render(<SettingsPanel {...baseProps} onClose={() => drawerClosed++} />);
    });
    await flush();
    await click(card());
    expect(isOpen()).toBe(true);

    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    await flush();
    // Der Drawer hat einen EIGENEN window-Escape-Handler. Die Schale hört in der
    // CAPTURE-Phase und stoppt dort die Ausbreitung — sonst schlüge ein Escape
    // beide Ebenen auf einmal zu (Overlay.tsx dokumentiert den Unfall).
    expect(isOpen()).toBe(false);
    expect(drawerClosed).toBe(0);
    // Escape landet dort, wo „Fertig" landet: auf der Übersicht. Zwei Ausgänge
    // aus derselben Fläche dürfen nicht an zwei verschiedenen Orten enden.
    expect(container.querySelector('.settings__catgrid')).not.toBeNull();
    expect(container.querySelector('.settings__back')).toBeNull();
  });

  it('der Autofokus landet im Overlay (Fokus-Falle des Anlern-Musters)', async () => {
    await click(card());
    expect(document.activeElement).toBe(container.querySelector('.themegallery__close'));
    const dialog = container.querySelector('.themegallery') as HTMLElement;
    expect(dialog.getAttribute('role')).toBe('dialog');
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(dialog.getAttribute('aria-label')).toBeTruthy();
  });
});

describe('Die Fläche PRÄSENTIERT — Vollbild, große Kacheln, Text bleibt drin', () => {
  it('die Karte ist fast die ganze Seite (nicht mehr die 960-px-Dialogkarte)', () => {
    // Zwei Klassen (0,2,0) — die generische `.overlay__card` (0,1,0) bleibt für
    // Crew & Co. unangetastet.
    const rule = ruleFor('.overlay__card.themegallery');
    expect(rule).toContain('96vw');
    expect(rule).toContain('94vh');
  });

  it('das Raster ist mehrspaltig, wo Platz ist — Kacheln ab 280 px', () => {
    expect(ruleFor('.themegallery__grid')).toContain('minmax(280px, 1fr)');
  });

  it('DIE KASKADEN-FALLE: jede Kachel-Regel trägt zwei Klassen, sonst gewinnt .settings__theme', () => {
    // Der eigentliche Bug. Ohne die zweite Klasse überstimmt `.settings__theme`
    // (weiter unten in derselben Datei) diese Regeln wieder — und die Karten
    // fallen still in die 40-px-Zeile zurück, in der die Geschichten überlaufen.
    for (const selector of [
      '.themegallery__grid .themegallery__card',
      '.themegallery__grid .themegallery__swatch',
    ]) {
      expect(THEMES_CSS, selector).toContain(selector + ' {');
    }
    // Die Kachel-Achse selbst: EINE Spalte (statt `40px 1fr`).
    expect(ruleFor('.themegallery__grid .themegallery__card')).toContain(
      'grid-template-columns: 1fr',
    );
    // Die Vorschau darf die Zwei-Zeilen-Spanne der Drawer-Zeile nicht erben.
    expect(
      ruleFor('.themegallery__grid .themegallery__swatch,\n.themegallery__grid .themegallery__scene'),
    ).toContain('grid-row: auto');
  });

  it('die Geschichten bleiben in der Box: Umbruch + Zeilen-Clamp statt Überlauf', () => {
    const shared = ruleFor(
      '.themegallery__grid .settings__themename,\n.themegallery__grid .settings__themehint',
    );
    // Riegel 1: ein Grid-Kind schrumpft ohne `min-width: 0` nicht unter sein
    // längstes Wort — und `anywhere` bricht auch das längste noch um.
    expect(shared).toContain('min-width: 0');
    expect(shared).toContain('overflow-wrap: anywhere');
    expect(shared).toContain('overflow: hidden');
    // Riegel 2: was dann noch nicht passt, endet in „…" INNERHALB der Karte.
    expect(ruleFor('.themegallery__grid .settings__themehint')).toContain('line-clamp: 3');
    expect(ruleFor('.themegallery__grid .settings__themename')).toContain('line-clamp: 2');
  });
});

describe('i18n — der Fertig-Weg spricht alle fünf Sprachen', () => {
  it('jede Sprache hat einen eigenen, nicht leeren Text', () => {
    const seen = new Set<string>();
    for (const lang of SUPPORTED_UI_LANGUAGES) {
      const done = CATALOGS[lang].settings.themeGalleryDone;
      expect(done, lang).toBeTruthy();
      expect(done.trim(), lang).toBe(done);
      seen.add(done);
    }
    // Fünf Sprachen, fünf echte Übersetzungen (kein durchgereichtes Deutsch).
    expect(seen.size).toBe(SUPPORTED_UI_LANGUAGES.length);
  });
});

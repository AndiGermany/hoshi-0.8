/** @vitest-environment jsdom */
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { ThemeGallery } from '../components/ThemeGallery';
import { TopNav } from '../components/TopNav';
import {
  ALL_THEME_IDS,
  DEFAULT_SETTINGS,
  NAGORI_UNLOCK_KEY,
  THEME_IDS,
  isNagoriUnlocked,
  loadSettings,
  saveSettings,
  unlockNagori,
  visibleThemeGroups,
  type Theme,
} from '../hooks/useSettings';
import {
  parseThemeManifest,
  primeThemeManifest,
  resetThemeCatalog,
  type ThemeManifest,
} from '../styles/themeCatalog';
import { CATALOGS, SUPPORTED_UI_LANGUAGES } from '../i18n';
import { de } from '../i18n/de';

/** Die ausgelieferte Manifest-Datei — Pfad relativ zum Vitest-Root (`frontend/`). */
const MANIFEST = parseThemeManifest(
  JSON.parse(readFileSync('public/themes/manifest.json', 'utf8')),
) as ThemeManifest;

/** Die Ids einer Gruppe, in Manifest-Reihenfolge (ohne die versteckten). */
const visibleIdsOf = (groupId: string): string[] =>
  MANIFEST.themes.filter((t) => t.group === groupId && !t.hidden && !t.retired).map((t) => t.id);

/**
 * Wie viele Karten die Galerie ÜBERHAUPT zeigen kann — alle Themen ohne die im
 * RUHESTAND (seit 21.08. genau Kasumi, s. `themeCatalog.ts`: es bleibt ein
 * gültiges Thema und wird von Sora weiter rotiert, steht aber in keiner
 * Galerie). Nagori ist hier MITGEZÄHLT: es ist nicht zurückgezogen, sondern
 * `hidden` — ein Fund holt es dazu, und genau darum geht es in dieser Datei.
 *
 * Bewusst aus dem Manifest gerechnet statt als Zahl hingeschrieben: ein neues
 * Thema soll diese Datei nicht rot machen, ein still verschwundenes schon.
 */
const GALLERY_MAX_CARDS = MANIFEST.themes.filter((t) => !t.retired).length;

// ═════════════════════════════════════════════════════════════════════════════
//  NAGORI (名残) — das versteckte zehnte Theme, ein Vorbote der 0.9
//
//  名残 = „das, was zurückbleibt" (von 波残り, was die Welle am Strand lässt).
//  Es ist der Codename der KOMMENDEN Version und darum bewusst kein normaler
//  Listeneintrag, sondern ein Fund: 3× schnell auf die Versions-Zeile der
//  Kopfzeile („0.8.4 · Suisei") — die Stelle, an der die Zukunft durchscheint.
//
//  Diese Datei pinnt genau die vier Dinge, an denen so ein Easter-Egg kaputt
//  geht, ohne dass es jemand merkt:
//   1. die Registry kennt Nagori (persistierbar!), aber die PICKER-Liste nicht,
//   2. ohne Fund steht es in keinem Rendering — mit Fund in „Eigene Stimmung",
//   3. die Geste selbst: 3 schnelle Klicks setzen Flag UND aktivieren sofort;
//      zwei Klicks oder drei langsame tun nichts,
//   4. das Thema trägt einen VOLLSTÄNDIGEN Token-Satz (sonst erbt es fremde
//      Farben) und die Leuchtspur läuft genau einmal.
// ═════════════════════════════════════════════════════════════════════════════

// Die Kopfzeile lebt im Test ohne Netz: die Ops-Pille und das Crew-Overlay
// ziehen sonst im Effekt am Backend. Beide sind für die Geste irrelevant.
vi.mock('../components/OpsStatusPill', () => ({ OpsStatusPillLive: () => null }));
vi.mock('../components/CrewOverlay', () => ({ CrewOverlayLive: () => null }));

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/**
 * In-Memory-Storage in DOM-`Storage`-Form (Idiom aus settings.test.ts /
 * themegroups.test.tsx): das nackte `localStorage` ist unter Node/Vitest nicht
 * verlässlich da, und jeder Test soll ohnehin auf einem frischen Gerät starten.
 */
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

/** Der Storage dieses Tests (frisch je Test — s. beforeEach). */
let store: Storage;

beforeEach(() => {
  store = memoryStorage();
  vi.stubGlobal('localStorage', store);
  // Der Picker rendert seit dem .old-Umzug aus dem Manifest;
  // `renderToStaticMarkup` führt keine Effekte aus, also wird die echte
  // ausgelieferte Datei direkt eingesetzt.
  primeThemeManifest(MANIFEST);
});

afterEach(() => {
  resetThemeCatalog();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/**
 * Rendert die Design-WAHL-Fläche statisch und gibt ein parsbares Dokument
 * zurück.
 *
 * Seit 21.08. ist das EINE Komponente. Zwischenstand 19.08.: die Panel-Sektion
 * `ThemeSection` trug die Aktiv-Zeile, die Karten standen in der Galerie, und
 * für Nagoris Zusicherungen waren beide zusammen „der Picker". Mit dem Auflösen
 * der Zwischenseite (Andi: „Dort ist immer noch die Zwischenseite") hat die
 * Sektion ihren letzten Aufrufer verloren und ist gelöscht — die Aktiv-Zeile
 * wohnt jetzt im Galerie-Kopf (`.themegallery__active`) und ist dort größer.
 * Geprüft werden also unverändert dieselben Stellen, nur an EINEM Ort.
 */
function renderPicker(theme: Theme): Document {
  const html = renderToStaticMarkup(
    <ThemeGallery open onClose={() => {}} theme={theme} onTheme={() => {}} />,
  );
  return new DOMParser().parseFromString(html, 'text/html');
}

/** Die Aktiv-Zeile — seit 21.08. im Galerie-Kopf statt in der Panel-Sektion. */
const activeRow = (doc: Document): HTMLElement | null =>
  doc.querySelector('.themegallery__active');

const radios = (doc: Document): HTMLElement[] =>
  Array.from(doc.querySelectorAll('[role="radio"]')) as HTMLElement[];

// ─────────────────────────────────────────────────────────────────────────────
//  1) Registry — persistierbar, aber nicht in der Liste
// ─────────────────────────────────────────────────────────────────────────────

describe('Registry — Nagori ist wählbar, ohne in der Liste zu stehen', () => {
  it('ALL_THEME_IDS = die neun sichtbaren + nagori; THEME_IDS bleibt bei neun', () => {
    expect([...ALL_THEME_IDS]).toEqual([...THEME_IDS, 'nagori']);
    expect(THEME_IDS).not.toContain('nagori');
    expect(THEME_IDS).toHaveLength(9);
  });

  it('das Manifest führt Nagori als `hidden` — es ist kein normaler Listeneintrag', () => {
    const nagori = MANIFEST.themes.find((t) => t.id === 'nagori');
    expect(nagori?.hidden).toBe(true);
    expect(nagori?.group).toBe('stimmung');
    // …und es ist das EINZIGE versteckte Thema (sonst wäre die Regel unscharf).
    expect(MANIFEST.themes.filter((t) => t.hidden).map((t) => t.id)).toEqual(['nagori']);
  });

  it('seine Farben wohnen in einer eigenen Datei, nicht im Bundle', () => {
    expect(MANIFEST.themes.find((t) => t.id === 'nagori')?.file).toBe('nagori.css');
  });

  it('eine Nagori-Wahl überlebt Speichern/Laden (sonst wäre der Fund beim Neustart weg)', () => {
    saveSettings({ ...DEFAULT_SETTINGS, theme: 'nagori' });
    expect(loadSettings().theme).toBe('nagori');
  });

  it('unbekannte Theme-Ids fallen weiterhin auf den Default zurück (kein Loch im Riegel)', () => {
    store.setItem('hoshi.settings', JSON.stringify({ ...DEFAULT_SETTINGS, theme: 'nagori-2' }));
    expect(loadSettings().theme).toBe(DEFAULT_SETTINGS.theme);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  2) Sichtbarkeit — ohne Fund existiert es im UI nicht
// ─────────────────────────────────────────────────────────────────────────────

describe('Ohne Fund — Nagori steht in keinem Rendering', () => {
  it('isNagoriUnlocked() ist auf einem frischen Gerät false', () => {
    expect(isNagoriUnlocked()).toBe(false);
  });

  it('visibleThemeGroups lässt Nagori aus jeder Gruppe heraus', () => {
    const groups = visibleThemeGroups(MANIFEST, 'aoi');
    expect(groups.flatMap((g) => g.themes).map((t) => t.id)).not.toContain('nagori');
    // Alle anderen zeigbaren Themen stehen vollständig da — versteckt ist NUR
    // Nagori (Kasumi fehlt aus dem anderen Grund: Ruhestand, s. GALLERY_MAX_CARDS).
    expect(groups.flatMap((g) => g.themes)).toHaveLength(GALLERY_MAX_CARDS - 1);
  });

  it('ohne geladenes Manifest gibt es ehrlich gar keine Gruppen (statt einer erfundenen Liste)', () => {
    expect(visibleThemeGroups(null, 'aoi')).toEqual([]);
  });

  it('der Picker zeigt genau die bekannten Karten — kein Wort „Nagori"', () => {
    const doc = renderPicker('aoi');
    expect(radios(doc)).toHaveLength(GALLERY_MAX_CARDS - 1);
    const html = doc.body.innerHTML;
    expect(html).not.toContain('Nagori');
    expect(html).not.toContain('名残');
    expect(doc.querySelector('.settings__nagorinote')).toBeNull();
  });
});

describe('Nach dem Fund — Nagori wohnt in „Eigene Stimmung"', () => {
  beforeEach(() => {
    store.setItem(NAGORI_UNLOCK_KEY, 'true');
  });

  it('isNagoriUnlocked() liest das Flag (nur exakt "true" zählt)', () => {
    expect(isNagoriUnlocked()).toBe(true);
    store.setItem(NAGORI_UNLOCK_KEY, 'yes');
    expect(isNagoriUnlocked()).toBe(false);
  });

  it('es hängt als letzte Karte hinten an der Stimmungs-Gruppe (nicht an der Uhr)', () => {
    const groups = visibleThemeGroups(MANIFEST, 'aoi');
    expect(groups.flatMap((g) => g.themes)).toHaveLength(GALLERY_MAX_CARDS);
    const stimmung = groups.find((g) => g.id === 'stimmung');
    expect(stimmung?.themes.map((t) => t.id)).toEqual(['nagori']);
    // Die Tageslage-Gruppen bleiben unberührt — Nagori folgt keiner Uhr. (Bis
    // 21.08. stand hier „Klassiker": diese Gruppe ist seit der Tageslage-
    // Sortierung der RUHESTAND und steht gar nicht mehr in der Galerie, die
    // Gegenprobe wäre also gegen `undefined` gelaufen.)
    for (const id of ['morgen', 'tag', 'abend-nacht']) {
      expect(groups.find((g) => g.id === id)?.themes.map((t) => t.id), id).toEqual(
        visibleIdsOf(id),
      );
    }
    // …und der Fund holt WIRKLICH nur Nagori: Kasumi bleibt im Ruhestand.
    expect(groups.flatMap((g) => g.themes).map((t) => t.id)).not.toContain('kasumi');
  });

  it('die Karte steht am Ende ihrer Gruppe, mit Gruppen-Label und echter Farbvorschau', () => {
    const doc = renderPicker('aoi');
    const cards = radios(doc);
    expect(cards).toHaveLength(GALLERY_MAX_CARDS);
    const card = cards.find((c) => c.getAttribute('aria-label') === 'Eigene Stimmung: Nagori');
    expect(card).toBeDefined();
    expect(card?.textContent).toContain('Nagori · was zurückbleibt');
    expect(card?.querySelector('.settings__swatch')?.getAttribute('data-theme')).toBe('nagori');
    // …als LETZTE ihrer Gruppe: direkt danach beginnt die nächste Gruppe.
    const stimmung = cards.filter((c) =>
      (c.getAttribute('aria-label') ?? '').startsWith('Eigene Stimmung:'),
    );
    expect(stimmung[stimmung.length - 1]).toBe(card);
  });

  it('darunter steht die Einordnung „名残 — ein Vorbote von 0.9" (genau einmal)', () => {
    const doc = renderPicker('aoi');
    const notes = doc.querySelectorAll('.settings__nagorinote');
    expect(notes).toHaveLength(1);
    expect(notes[0].textContent).toBe(de.settings.nagori.note);
  });

  it('ist Nagori gewählt, ist genau seine Karte angekreuzt — und kein Pin-Hinweis lügt', () => {
    const doc = renderPicker('nagori');
    const checked = radios(doc).filter((r) => r.getAttribute('aria-checked') === 'true');
    expect(checked).toHaveLength(1);
    expect(checked[0].getAttribute('aria-label')).toBe('Eigene Stimmung: Nagori');
    expect(doc.querySelector('.settings__themepinned')).toBeNull();
  });

  it('die Aktiv-Zeile UND die Gruppen-Zuordnung folgen Nagori, sobald es gewählt ist', () => {
    const doc = renderPicker('nagori');
    // Aktiv-Zeile (Andi-Auftrag 07.08): Name + Gruppen-Beiwort „Eigene Stimmung".
    // Sie steht seit 21.08. im Galerie-Kopf statt in der gelöschten Panel-Sektion.
    const active = activeRow(doc);
    expect(active).not.toBeNull();
    expect(active?.textContent).toContain('Nagori');
    expect(active?.textContent).toContain('Eigene Stimmung');
    expect(active?.querySelector('.settings__activeswatch')?.getAttribute('data-theme')).toBe(
      'nagori',
    );
    // Seit §3.4 gibt es keine Falten mehr, die „aufgehen" könnten (die Galerie
    // hat 960 px und zeigt alle Gruppen als Überschriften). Die Zusicherung
    // dahinter bleibt dieselbe: die angekreuzte Karte steht in „Eigene Stimmung".
    expect(doc.querySelectorAll('details')).toHaveLength(0);
    const checked = radios(doc).find((r) => r.getAttribute('aria-checked') === 'true');
    expect(checked?.closest('.themegallery__group')?.className).toContain(
      'themegallery__group--stimmung',
    );
  });
});

describe('Ehrlichkeit — ein aktives Thema ist nie unsichtbar', () => {
  it('Nagori aktiv OHNE Flag: die Karte steht trotzdem da (sonst wäre nichts ankreuzbar)', () => {
    expect(isNagoriUnlocked()).toBe(false);
    const doc = renderPicker('nagori');
    expect(radios(doc)).toHaveLength(GALLERY_MAX_CARDS);
    const checked = radios(doc).filter((r) => r.getAttribute('aria-checked') === 'true');
    expect(checked[0].getAttribute('aria-label')).toBe('Eigene Stimmung: Nagori');
  });

  it('…und auch OHNE Flag steht seine Gruppe da (die Aktiv-Zeile lügt nie)', () => {
    expect(isNagoriUnlocked()).toBe(false);
    const doc = renderPicker('nagori');
    // Die Gruppe „Eigene Stimmung" ist als Überschrift da UND trägt die Karte —
    // ein aktives Thema, das nirgends steht, wäre nicht ankreuzbar.
    const stimmung = doc.querySelector('.themegallery__group--stimmung');
    expect(stimmung).not.toBeNull();
    expect(stimmung?.querySelector('#settings-themegroup-stimmung')?.textContent).toBe(
      de.settings.themeGroups.stimmung.title,
    );
    const checked = radios(doc).find((r) => r.getAttribute('aria-checked') === 'true');
    expect(stimmung?.contains(checked!)).toBe(true);
    expect(activeRow(doc)?.textContent).toContain('Nagori');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  3) Die Geste — 3× schnell auf die Versions-Zeile
// ─────────────────────────────────────────────────────────────────────────────

describe('Der Fund — 3 schnelle Klicks auf die Versions-Zeile', () => {
  let host: HTMLDivElement;
  let root: Root;

  const mount = () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    act(() => {
      root.render(<TopNav tab="overview" onTab={() => {}} onOpenSettings={() => {}} />);
    });
  };

  const version = (): HTMLButtonElement =>
    host.querySelector('.nav__ver') as HTMLButtonElement;

  const tap = (times: number) => {
    for (let i = 0; i < times; i++) act(() => version().click());
  };

  afterEach(() => {
    if (root) act(() => root.unmount());
    host?.remove();
  });

  it('die Versions-Zeile ist ein echter Button (Tastatur erreicht das Egg auch)', () => {
    mount();
    expect(version().tagName).toBe('BUTTON');
    expect(version().getAttribute('type')).toBe('button');
    // …sieht aber weiterhin aus wie Text: kein Titel, kein „klick mich"-Hinweis.
    expect(version().getAttribute('title')).toBeNull();
    expect(version().textContent).toContain('· Nagori');
  });

  it('3× klicken setzt das Flag UND aktiviert Nagori sofort', () => {
    mount();
    tap(3);
    expect(store.getItem(NAGORI_UNLOCK_KEY)).toBe('true');
    expect(loadSettings().theme).toBe('nagori');
  });

  it('2× klicken tut gar nichts (kein halber Fund)', () => {
    mount();
    tap(2);
    expect(isNagoriUnlocked()).toBe(false);
    expect(loadSettings().theme).toBe(DEFAULT_SETTINGS.theme);
  });

  it('3× LANGSAM (über das Fenster verteilt) tut nichts — die Geste ist bewusst', () => {
    const clock = { t: 1_000_000 };
    vi.spyOn(Date, 'now').mockImplementation(() => clock.t);
    mount();
    tap(1);
    clock.t += 1_500; // > 1,2 s ⇒ zählt als neuer erster Tap
    tap(1);
    clock.t += 1_500;
    tap(1);
    expect(isNagoriUnlocked()).toBe(false);
  });

  it('nach dem Fund passiert nichts mehr: eine spätere Theme-Wahl bleibt stehen', () => {
    mount();
    tap(3);
    saveSettings({ ...loadSettings(), theme: 'kasumi' }); // bewusst weitergewählt
    tap(3);
    expect(loadSettings().theme).toBe('kasumi');
  });

  it('unlockNagori() ist idempotent — zweimal aufrufen ändert nichts am Ergebnis', () => {
    unlockNagori();
    unlockNagori();
    expect(store.getItem(NAGORI_UNLOCK_KEY)).toBe('true');
    expect(loadSettings().theme).toBe('nagori');
    mount(); // Kopfzeile bleibt danach ruhig
    tap(3);
    expect(loadSettings().theme).toBe('nagori');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  4) Katalog + CSS — der Eigenname bleibt, die Farben sind vollständig
// ─────────────────────────────────────────────────────────────────────────────

describe('Katalog — fünf Sprachen, ein Eigenname', () => {
  it('jede Sprache kennt Nagori; der Name bleibt überall „Nagori"', () => {
    for (const lang of SUPPORTED_UI_LANGUAGES) {
      const n = CATALOGS[lang].settings.nagori;
      expect(n.label, lang).toBe('Nagori');
      expect(n.gloss, lang).toBeTruthy();
      expect(n.hint, lang).toContain('名残');
      expect(n.note, lang).toContain('名残');
      expect(n.note, lang).toContain('0.9');
    }
  });

  it('die Listen-Kataloge bleiben bei den neun sichtbaren (Nagori leckt nicht hinein)', () => {
    for (const lang of SUPPORTED_UI_LANGUAGES) {
      const s = CATALOGS[lang].settings;
      expect(Object.keys(s.themes).sort(), lang).toEqual([...THEME_IDS].sort());
      expect(Object.keys(s.themeGlosses).sort(), lang).toEqual([...THEME_IDS].sort());
    }
  });
});

describe('CSS — blaue Stunde mit einem einzigen Akzent, jetzt in eigener Datei', () => {
  // Seit dem .old-Umzug (2026-08-08) liegt Nagoris Farbe NICHT mehr im Bundle,
  // sondern unter `public/themes/nagori.css` und wird zur Laufzeit nachgeladen.
  // Der Maßstab für Vollständigkeit ist unverändert ein ausgebautes
  // Bestands-Theme — das wohnt jetzt unter `public/themes/old/`.
  const css = readFileSync('public/themes/nagori.css', 'utf8');
  const amayadoriCss = readFileSync('public/themes/old/amayadori.css', 'utf8');

  /** Schneidet den Token-Block eines Themas aus (bis zur schließenden Klammer). */
  const block = (source: string, id: string): string => {
    const start = source.indexOf(`:root[data-theme='${id}'],`);
    expect(start, id).toBeGreaterThan(-1);
    const open = source.indexOf('{', start);
    return source.slice(open, source.indexOf('\n}', open));
  };

  /** Die Namen aller Custom-Properties, die ein Block setzt. */
  const tokens = (source: string, id: string): string[] =>
    Array.from(block(source, id).matchAll(/(--[a-z0-9-]+):/g), (m) => m[1]).sort();

  it('die Datei ist wirklich ausgezogen — themes.css kennt Nagori nicht mehr', () => {
    const themesCss = readFileSync('src/styles/themes.css', 'utf8').replace(
      /\/\*[\s\S]*?\*\//g,
      '',
    );
    expect(themesCss).not.toContain("data-theme='nagori'");
    expect(themesCss).not.toContain('@keyframes nagori-trail');
  });

  it('trägt beide Selektoren — sonst bliebe die Vorschau-Kachel farblos', () => {
    // Derselbe Riegel wie in themegroups.test.tsx, hier für das versteckte Thema:
    // die Kachel ist kein <html>, ein reiner :root-Block träfe sie nicht.
    expect(/(^|[,\s])\[data-theme='nagori'\]/m.test(css)).toBe(true);
    expect(css).toContain(":root[data-theme='nagori'],");
  });

  it('setzt jedes Token, das ein ausgebautes Bestands-Theme setzt (kein geerbter Rest)', () => {
    for (const token of tokens(amayadoriCss, 'amayadori')) {
      expect(tokens(css, 'nagori'), token).toContain(token);
    }
    expect(block(css, 'nagori')).toContain('color-scheme: dark');
  });

  it('Glut-Amber ist die EINZIGE Akzentfarbe — alle vier Akzent-Token teilen sie', () => {
    const amber = 'oklch(0.785 0.135 58';
    for (const token of ['--accent:', '--accent-soft:', '--accent-faint:', '--accent-glow:']) {
      const line = block(css, 'nagori')
        .split('\n')
        .find((l) => l.trim().startsWith(token));
      expect(line?.includes(amber), token).toBe(true);
    }
    // Die Tinte auf dem Amber ist das Indigo des Grundes (Kontrast 9,6:1).
    expect(block(css, 'nagori')).toContain('--accent-ink: oklch(0.16 0.045 270)');
  });

  it('die Leuchtspur ist Zierde, kein Dauerlauf: ein Durchgang, dann steht sie', () => {
    const trail = css.slice(css.indexOf(":root[data-theme='nagori'] body::before"));
    expect(trail).toContain('animation: nagori-trail');
    expect(trail.slice(0, trail.indexOf('@keyframes'))).not.toContain('infinite');
    expect(css).toContain('@keyframes nagori-trail');
    // Nur opacity/transform animieren (Projekt-Konvention, s. index.css).
    const frames = css.slice(css.indexOf('@keyframes nagori-trail'));
    expect(frames.slice(0, frames.indexOf('\n}\n'))).toMatch(/opacity|transform/);
  });

  it('der Hintergrund ist ein ruhiger Vertikalverlauf mit Flutlinie', () => {
    const atmosphere = block(css, 'nagori');
    expect(atmosphere).toContain('--theme-atmosphere');
    expect(atmosphere).toContain('linear-gradient(180deg');
    expect(atmosphere).toContain('var(--bg-base)');
  });
});

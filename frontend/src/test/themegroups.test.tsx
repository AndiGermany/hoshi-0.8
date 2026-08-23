/** @vitest-environment jsdom */
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { readFileSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { THEME_SCENE_IMAGES, ThemeGallery, themeGroupHeadingId } from '../components/ThemeGallery';
import {
  DEFAULT_SETTINGS,
  SORA_ROTATION,
  THEME_IDS,
  loadSettings,
  saveSettings,
  type Theme,
} from '../hooks/useSettings';
import {
  THEME_GLOSS_LANGUAGES,
  THEME_GROUP_IDS,
  parseThemeManifest,
  primeThemeManifest,
  resetThemeCatalog,
  visibleGroups,
  type ThemeManifest,
} from '../styles/themeCatalog';
import { SUPPORTED_UI_LANGUAGES } from '../i18n';
import { de } from '../i18n/de';

// ═════════════════════════════════════════════════════════════════════════════
//  Theme-GRUPPEN — seit dem .old-Umzug (Andi-Auftrag 2026-08-08: „Ich möchte,
//  dass du die Designs in ein .old verschiebst. Das soll dynamisch nachladbar
//  sein — nicht in der CSS liegen, sondern dynamisch geladen werden.") kommt
//  die Gruppierung aus `public/themes/manifest.json` statt aus einem Array im
//  Code.
//
//  Diese Datei liest darum die ECHTE ausgelieferte Manifest-Datei (nicht eine
//  Test-Attrappe) und pinnt genau das, was die Übersicht ausmacht — dieselben
//  Zusagen wie vor dem Umzug, nur an der neuen Quelle:
//   1. die Gruppen decken die Themen VOLLSTÄNDIG und ÜBERSCHNEIDUNGSFREI ab
//      (ein neues Thema fällt nicht still aus dem Panel),
//   2. die Gruppen stehen in TAGES-Reihenfolge (nicht alphabetisch), und
//      innerhalb einer Gruppe läuft es von hell nach dunkel,
//   3. Sora zeigt das GERADE aufgelöste Theme — die Regel ist ablesbar, bevor
//      man sie wählt,
//   4. die persistierten Ids sind UNVERÄNDERT — niemand verliert seine Wahl,
//   5. die Farbvorschau hängt an echten Werten: jede Kachel bekommt die drei
//      Manifest-Hexes inline (lade-frei) UND trägt weiterhin `data-theme`; die
//      Hexes stehen wirklich so in der jeweiligen Themen-DATEI (CSS-Riegel unten).
//
//  ── ZWEI ÄNDERUNGEN VOM 21.08. (Andi: „Sortiere die Designs logisch und
//     gruppiere diese" · „Entferne die alten, noch nicht animierten Designs") ──
//
//  (a) DIE GRUPPE „szenen" GIBT ES NICHT MEHR. Sie war eine Überschrift über
//      dreizehn Karten — das benennt einen Stapel, es ordnet ihn nicht. An ihre
//      Stelle treten die TAGESLAGEN `morgen` · `tag` · `abend-nacht`
//      ({@link SCENE_GROUPS}); die Szenen selbst sind dieselben (inzwischen
//      vierzehn, s. Hanaikada).
//      Jedes `idsOf('szenen')` von früher heißt hier jetzt {@link sceneIds}.
//
//  (b) KASUMI IST IM RUHESTAND (`retired: true`, s. styles/themeCatalog.ts). Es
//      bleibt ein GÜLTIGES Thema — gespeicherte Wahl, CSS-Datei und vor allem
//      die {@link SORA_ROTATION} sind unberührt —, steht aber in keiner Galerie
//      mehr. Deshalb sind „was das Manifest FÜHRT" und „was die Galerie ZEIGT"
//      hier ab jetzt zwei verschiedene Mengen, und die Tests sagen jeweils dazu,
//      welche sie meinen. Der Riegel gegen den Fehler, sie zu verschmelzen,
//      steht in „Ruhestand" ganz unten.
// ═════════════════════════════════════════════════════════════════════════════

/** Die ausgelieferte Manifest-Datei — Pfad relativ zum Vitest-Root (`frontend/`). */
const MANIFEST_RAW = readFileSync('public/themes/manifest.json', 'utf8');
const MANIFEST = parseThemeManifest(JSON.parse(MANIFEST_RAW)) as ThemeManifest;

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/** Die Ids einer Gruppe, in Manifest-Reihenfolge. */
const idsOf = (groupId: string, manifest: ThemeManifest = MANIFEST): string[] =>
  manifest.themes.filter((t) => t.group === groupId).map((t) => t.id);

/**
 * Die drei Tageslage-Gruppen — zusammen genau das, was bis 21.08. die EINE
 * Gruppe `szenen` war (s. Punkt (a) im Dateikopf). In Anzeige-Reihenfolge.
 */
const SCENE_GROUPS = ['morgen', 'tag', 'abend-nacht'] as const;

/** Alle Szenen-Ids über die drei Tageslagen hinweg, in Anzeige-Reihenfolge. */
const sceneIds = (manifest: ThemeManifest = MANIFEST): string[] =>
  SCENE_GROUPS.flatMap((g) => idsOf(g, manifest));

/** Themen im RUHESTAND: im Manifest geführt, aber nie in der Galerie. */
const retiredIds = (manifest: ThemeManifest = MANIFEST): string[] =>
  manifest.themes.filter((t) => t.retired).map((t) => t.id);

/** In-Memory-Storage in DOM-`Storage`-Form (wie in settings.test.ts). */
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
 * Rendert die GALERIE statisch — seit 21.08. die EINZIGE Design-Fläche. Hier
 * wohnen die Karten, die Gruppen-Überschriften, die Aktiv-Zeile, der Sora-Bogen
 * und der Pin-Hinweis. (Die Panel-Sektion `ThemeSection` daneben war Andis
 * Zwischenseite und ist gelöscht — s. den Block „Aktiv-Zeile" weiter unten.)
 */
function renderGallery(theme: Theme): Document {
  const html = renderToStaticMarkup(
    <ThemeGallery open onClose={() => {}} theme={theme} onTheme={() => {}} />,
  );
  return new DOMParser().parseFromString(html, 'text/html');
}

const radios = (doc: Document): HTMLElement[] =>
  Array.from(doc.querySelectorAll('[role="radio"]')) as HTMLElement[];

beforeEach(() => {
  // Der Picker rendert aus dem Manifest. `renderToStaticMarkup` führt keine
  // Effekte aus — also wird die ECHTE Datei vorher direkt eingesetzt (dieselbe
  // Naht, die auch ein Server-Rendering nutzen würde).
  primeThemeManifest(MANIFEST);
});

afterEach(() => {
  resetThemeCatalog();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('Das Manifest ist die Wahrheit — die ausgelieferte Datei ist gültig', () => {
  it('lässt sich parsen und trägt Version 1', () => {
    expect(MANIFEST).not.toBeNull();
    expect(MANIFEST.version).toBe(1);
  });

  it('jedes Thema gehört zu genau einer DEKLARIERTEN Gruppe, keine Gruppe ist leer', () => {
    const declared = MANIFEST.groups.map((g) => g.id);
    expect(new Set(declared).size).toBe(declared.length); // keine Gruppe doppelt
    for (const theme of MANIFEST.themes) expect(declared, theme.id).toContain(theme.group);
    for (const g of declared) expect(idsOf(g).length, g).toBeGreaterThan(0);
  });

  it('keine Id doppelt — sonst gewänne stillschweigend die erste', () => {
    const ids = MANIFEST.themes.map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('Reihenfolge: die Automatik ganz oben, dann der Tag von früh nach spät', () => {
    // Andi 21.08.: „Sortiere die Designs logisch und gruppiere diese." Die
    // Reihenfolge IST die Ordnung: wer nicht wählen will, ist nach einer Karte
    // fertig (Automatik); wer wählt, liest den Tag von vorn nach hinten.
    // „Klassiker" ist der Ruhestand und steht darum ganz unten — sichtbar nur,
    // wenn ein zurückgezogenes Thema gerade aktiv ist.
    expect(MANIFEST.groups.map((g) => g.id)).toEqual([
      'automatik',
      'morgen',
      'tag',
      'abend-nacht',
      'stimmung',
      'klassiker',
    ]);
    expect(MANIFEST.groups.map((g) => g.id)).toEqual([...THEME_GROUP_IDS]);
  });

  it('INNERHALB jeder Tageslage läuft es von hell nach dunkel (nicht alphabetisch)', () => {
    // Die zweite Hälfte der Ordnung. Gemessen an der WCAG-Relativluminanz von
    // `swatch[0]` (`--bg-surface`) — der Fläche, die später den Bildschirm
    // füllt. Ohne diesen Riegel rutscht ein neues Thema irgendwo in die Gruppe
    // und die Gruppe ist wieder ein Stapel.
    const luminance = (hex: string): number => {
      const ch = (v: number): number => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
      const [r, g, b] = [1, 3, 5].map((i) => ch(parseInt(hex.slice(i, i + 2), 16) / 255));
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    for (const group of SCENE_GROUPS) {
      const ls = idsOf(group).map(
        (id) => MANIFEST.themes.find((t) => t.id === id)!.swatch[0],
      ).map(luminance);
      const sorted = [...ls].sort((a, b) => b - a);
      expect(ls, group).toEqual(sorted);
    }
  });

  it('Gruppe „Folgt dem Tag" enthält NUR Sora (es ist keine Farbe, sondern eine Regel)', () => {
    expect(idsOf('automatik')).toEqual(['sora']);
  });

  it('Gruppe „Klassiker" ist der RUHESTAND — genau Kasumi, und keine Station der Rotation fehlt', () => {
    // ── Bewusste Invariant-Änderung, 19.08.2026 (Themen-Revival) ──────────────
    // Bis dahin galt „klassiker == SORA_ROTATION". Andis Order vom 18.08. hat
    // die sieben Alt-Themen (amayadori · natsunohi · nagareboshi · asa · yoake ·
    // aoi · yoru) von sieben Pods zu ECHTEN Szenen ausbauen lassen — mit eigener
    // CSS, eigener SVG-Zeichnung und gemessenem Kontrast. Übrig blieb Kasumi,
    // das (noch) keinen Pod hatte.
    //
    // ── Und die Folge daraus, 21.08. (Andi: „Entferne die alten, noch nicht
    //    animierten Designs") ──────────────────────────────────────────────────
    // Ein einzelnes szenenloses Thema unter einer eigenen Überschrift ist kein
    // Kapitel, sondern ein Rest. Kasumi geht darum in den RUHESTAND: `retired`,
    // raus aus der Galerie — und die Gruppe „Klassiker" ist damit die Schublade
    // dafür, nicht mehr eine Auswahl.
    expect(idsOf('klassiker')).toEqual(['kasumi']);
    expect(retiredIds()).toEqual(['kasumi']);
    //
    // Das Invariant war nie eine Zusage ÜBER die Rotation, sondern eine
    // zufällige Deckung zweier Listen. `SORA_ROTATION` ist unverändert
    // hartcodiert (`useSettings.ts`) und wurde NICHT angefasst: Soras Tagesbogen
    // läuft Station für Station wie vorher (Beweis: der Bogen-Test unten prüft
    // weiterhin 'Nagareboshi › Asa › Aoi › Kasumi › Yoru').
    const ids = MANIFEST.themes.map((t) => t.id);
    for (const id of SORA_ROTATION) expect(ids, id).toContain(id);
  });

  it('Gruppe „Eigene Stimmung" ist nach dem Revival nur noch Nagoris Zuhause', () => {
    // Die drei Bilder-Themen sind zu Szenen geworden (s. oben); zurück bleibt
    // das versteckte Nagori. FOLGE FÜRS PANEL: `visibleGroups()` wirft leere
    // Gruppen raus, und Nagori zählt erst nach dem Fund — die Überschrift
    // „Eigene Stimmung" steht also erst wieder da, wenn man Nagori gefunden hat.
    expect(idsOf('stimmung')).toEqual(['nagori']);
    for (const id of idsOf('stimmung')) expect(SORA_ROTATION).not.toContain(id);
  });

  it('die Szenen verteilen sich auf die drei Tageslagen — jede an ihrer Stunde', () => {
    // Bis 21.08. lagen alle dreizehn in EINER Gruppe `szenen`. Dieselben
    // Szenen, jetzt nach der Frage sortiert, mit der man sie sucht — und seit
    // dem 22.08. sind es vierzehn (Hanaikada kam vom Theme-Pod dazu).
    expect(idsOf('morgen')).toEqual(['asagiri', 'asa', 'yoake']);
    expect(idsOf('tag')).toEqual([
      // Hanaikada kam am 22.08. vom Theme-Pod dazu (Manifest-Gruppe `szenen`,
      // Sortierung ausdrücklich diesem Pod überlassen) und sitzt mit 96,2 %
      // Relativluminanz am HELLEN Anfang des Tages — heller als alles andere.
      // Die Szene bestätigt es: volles Tageslicht, Kirschblüten über blauem
      // Fluss. Es steht damit neben Hanashigure, dem anderen Blüten-Thema.
      'fuyubare',
      'hanaikada',
      'komorebi',
      'momiji',
      'hanashigure',
      'ukiyo',
      'natsunohi',
      'aoi',
    ]);
    expect(idsOf('abend-nacht')).toEqual([
      'natsumatsuri',
      'yukiakari',
      'amayadori',
      'yoru',
      'nagareboshi',
    ]);
    // Zusammen sind es fünfzehn (Fuyubare kam 22.08. dazu) — keine ist beim Umsortieren hängengeblieben
    // oder doppelt gelandet.
    expect(sceneIds()).toHaveLength(16);
    expect(new Set(sceneIds()).size).toBe(16);
    // Jede davon trägt eine eigene Datei — keine zeigt mehr in den alten Stub.
    for (const id of sceneIds()) {
      const file = MANIFEST.themes.find((t) => t.id === id)?.file;
      expect(file, id).toBe(`${id}.css`);
    }
  });

  it('Momiji sitzt im TAG, nicht am Abend — die Einordnung folgt der FARBE, nicht dem Motiv', () => {
    // Der eine Fall, bei dem Motiv und Tageslage auseinandergehen: „Ahornfärbung"
    // klingt nach Abend, die Fläche ist aber #fff4e7 — ein heller Herbst-TAG
    // (91,7 % Relativluminanz). Sortiert wird nach dem, was man SIEHT.
    const momiji = MANIFEST.themes.find((t) => t.id === 'momiji');
    expect(momiji?.group).toBe('tag');
    expect(momiji?.swatch[0]).toBe('#fff4e7');
    // Gegenprobe am anderen Ende derselben Gruppe: Aoi ist dunkel und steht
    // darum zuletzt im Tag — aber eben noch im Tag, nicht in der Nacht.
    expect(MANIFEST.themes.find((t) => t.id === 'aoi')?.group).toBe('tag');
    const tag = idsOf('tag');
    expect(tag[tag.length - 1]).toBe('aoi');
  });

  it('der Umzug hat KEIN Bestands-Thema verloren (alle 0.8-Ids stehen im Manifest)', () => {
    const ids = MANIFEST.themes.map((t) => t.id);
    for (const id of [...THEME_IDS, 'nagori']) expect(ids, id).toContain(id);
  });

  it('jedes Thema hat ein Beiwort in ALLEN fünf UI-Sprachen (kein Loch beim Umschalten)', () => {
    expect([...THEME_GLOSS_LANGUAGES]).toEqual([...SUPPORTED_UI_LANGUAGES]);
    for (const theme of MANIFEST.themes) {
      for (const lang of SUPPORTED_UI_LANGUAGES) {
        expect(theme.gloss[lang], `${theme.id}/${lang}`).toBeTruthy();
      }
    }
  });

  it('die deutschen Beiworte der Bestands-Themen sind BYTE-GLEICH zum Text-Katalog', () => {
    // Das Beiwort ist mit dem Umzug vom i18n-Katalog ins Manifest gewandert —
    // hier hängen beide Stände aneinander, damit der Umzug nichts umgetextet hat.
    for (const id of THEME_IDS) {
      const entry = MANIFEST.themes.find((t) => t.id === id);
      expect(entry?.gloss.de, id).toBe(de.settings.themeGlosses[id]);
    }
    expect(MANIFEST.themes.find((t) => t.id === 'nagori')?.gloss.de).toBe(de.settings.nagori.gloss);
  });
});

describe('Persistierte Ids — der Umzug ändert NICHTS an der gespeicherten Wahl', () => {
  it('THEME_IDS (die Schlüssel der Text-Kataloge) ist unverändert', () => {
    expect([...THEME_IDS]).toEqual([
      'aoi',
      'yoru',
      'asa',
      'natsunohi',
      'kasumi',
      'nagareboshi',
      'yoake',
      'amayadori',
      'sora',
    ]);
  });

  it('jede Id aus jeder Gruppe überlebt einen Speicher-/Lade-Rundlauf', () => {
    for (const theme of MANIFEST.themes.map((t) => t.id)) {
      // 'yoru' ist der Sonderfall der Einmal-Aoi-Migration (settings.test.ts):
      // erst nach gesetztem Flag bleibt die bewusste Wahl stehen.
      vi.stubGlobal('localStorage', memoryStorage());
      saveSettings({ ...DEFAULT_SETTINGS, theme });
      if (theme === 'yoru') {
        loadSettings(); // setzt das Einmal-Flag (aoi-Migration)
        saveSettings({ ...DEFAULT_SETTINGS, theme }); // …danach zählt die bewusste Wahl
      }
      expect(loadSettings().theme, theme).toBe(theme);
    }
  });

  it('eine Id, die das Manifest NICHT kennt, fällt auf den Default zurück', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    localStorage.setItem(
      'hoshi.settings',
      JSON.stringify({ ...DEFAULT_SETTINGS, theme: 'gibtesnicht' }),
    );
    expect(loadSettings().theme).toBe(DEFAULT_SETTINGS.theme);
  });

  it('OHNE geladenes Manifest bleibt eine gespeicherte Wahl stehen (Kaltstart-Ehrlichkeit)', () => {
    // Sie darf nicht sterben, nur weil die Manifest-Datei noch unterwegs ist —
    // useSettings räumt später auf, sobald das Manifest wirklich da ist.
    resetThemeCatalog();
    vi.stubGlobal('localStorage', memoryStorage());
    localStorage.setItem('hoshi.settings', JSON.stringify({ ...DEFAULT_SETTINGS, theme: 'momiji' }));
    expect(loadSettings().theme).toBe('momiji');
  });
});

describe('Aktiv-Zeile — „was läuft gerade?" steht im GALERIE-KOPF', () => {
  // ── Wo dieser Block herkommt ──────────────────────────────────────────────
  // Er hieß bis 21.08. „ThemeSection — was NACH §3.4 noch im Panel steht": die
  // Auswahl war in die Galerie gezogen, die Antwort auf „was läuft gerade?"
  // blieb als Panel-Sektion zurück, dazu EIN Knopf „Alle Designs ansehen".
  //
  // Genau dieser Rest war Andis Zwischenseite („Dort ist immer noch die
  // Zwischenseite"): eine Seite hinter der Karte, die zwei Dinge trug, die es
  // beide schon im Overlay gab. Die Sektion ist gelöscht — die Aktiv-Zeile
  // wohnt in `.themegallery__active`, größer und einen Klick statt zwei
  // entfernt. Die Zusicherungen hier sind dieselben geblieben; sie zeigen nur
  // auf die Fläche, auf der die Zeile jetzt steht.

  /** Die Aktiv-Zeile im Galerie-Kopf. */
  const activeRow = (theme: Theme): HTMLElement | null =>
    renderGallery(theme).querySelector('.themegallery__active');

  it('Aktiv-Zeile: Swatch + Name + Gruppen-Beiwort des laufenden Themas', () => {
    // Kasumi ist der interessante Fall: es ist im RUHESTAND und hat trotzdem
    // eine Aktiv-Zeile, solange es läuft. Ein Thema, das aktiv ist, aber
    // nirgends steht, wäre unehrlich — und nicht abwählbar.
    const active = activeRow('kasumi');
    expect(active).not.toBeNull();
    expect(active?.textContent).toContain('Kasumi');
    expect(active?.textContent).toContain('Klassiker'); // Gruppen-Beiwort
    const swatch = active?.querySelector('.settings__activeswatch');
    expect(swatch?.getAttribute('data-theme')).toBe('kasumi');
    expect(swatch?.querySelector('.settings__swatchbg')).not.toBeNull();
    // Eigene Klasse (NICHT `.settings__swatch`) — sonst zählte die Aktiv-Zeile
    // als weitere Karte mit und verschöbe die index-basierten Swatch-Tests der
    // Galerie.
    expect(active?.querySelectorAll('.settings__swatch')).toHaveLength(0);
  });

  it('Sora aktiv: Name bleibt „Sora", die Vorschau zeigt das GERADE aufgelöste Thema', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 6, 19, 8, 0, 0, 0)); // 08:00 → Asa-Fenster
    const active = activeRow('sora');
    expect(active?.textContent).toContain('Sora');
    expect(active?.textContent).toContain('Folgt dem Tag');
    // Asa HAT eine Szene ⇒ die Vorschau ist das Bild, nicht die Swatch.
    expect(active?.querySelector('img')?.getAttribute('src')).toBe(THEME_SCENE_IMAGES.asa);
  });

  it('folgt jeder Wahl (kein zweiter, eigener Zustand) — quer durch die Tageslagen', () => {
    const groupOf = (theme: Theme) => activeRow(theme)?.textContent;
    // Je eine Station aus jeder der drei Tageslagen, plus der Ruhestand als
    // Gegenprobe „auch eine ANDERE Gruppe wird korrekt benannt".
    expect(groupOf('yoake')).toContain('Morgen');
    expect(groupOf('aoi')).toContain('Tag');
    expect(groupOf('yoru')).toContain('Abend & Nacht');
    expect(groupOf('kasumi')).toContain('Klassiker');
  });

  it('ohne Manifest steht ehrlich „lädt …" da — keine erfundene, keine leere Liste', () => {
    resetThemeCatalog();
    const doc = renderGallery('aoi');
    expect(doc.querySelector('.settings__themeloading')?.textContent).toBe(
      de.settings.themeLoading,
    );
    expect(radios(doc)).toHaveLength(0);
  });
});

describe('ThemeGallery — was im Overlay wirklich steht', () => {
  it('alle Karten stehen in Manifest-Reihenfolge im DOM, mit „Gruppe: Name"', () => {
    const doc = renderGallery('aoi');
    // Die Reihenfolge IST die Ordnung vom 21.08.: erst die Regel, dann der Tag
    // von früh nach spät, in jeder Gruppe von hell nach dunkel.
    expect(radios(doc).map((r) => r.getAttribute('aria-label'))).toEqual([
      'Folgt dem Tag: Sora',
      'Morgen: Asagiri',
      'Morgen: Asa',
      'Morgen: Yoake',
      'Tag: Fuyubare',
      'Tag: Hanaikada',
      'Tag: Komorebi',
      'Tag: Momiji',
      'Tag: Hanashigure',
      'Tag: Ukiyo',
      'Tag: Natsu no Hi',
      'Tag: Aoi',
      'Abend & Nacht: Natsumatsuri',
      'Abend & Nacht: Yukiakari',
      'Abend & Nacht: Amayadori',
      'Abend & Nacht: Yoru',
      'Abend & Nacht: Nagareboshi',
    ]);
    // VIERZEHN statt fünfzehn: Kasumi ist im Ruhestand (`retired`) und steht
    // nicht mehr zur Wahl — die vierzehn Szenen plus Sora sind vollständig da.
    expect(radios(doc)).toHaveLength(17);
    expect(radios(doc).map((r) => r.getAttribute('aria-label')).join()).not.toContain('Kasumi');
  });

  it('jede Gruppe hat eine Überschrift mit stabiler Id (a11y: Gruppen sind benannt)', () => {
    const doc = renderGallery('aoi');
    for (const g of visibleGroups(MANIFEST, false)) {
      const head = doc.getElementById(themeGroupHeadingId(g.id));
      expect(head, g.id).not.toBeNull();
      expect(head?.textContent).toBe(de.settings.themeGroups[g.id].title);
    }
    // Die Auswahl bleibt EINE exklusive Wahl über alle Gruppen hinweg.
    const group = doc.querySelector('[role="radiogroup"]');
    expect(group?.getAttribute('aria-label')).toBe(de.settings.themeGroupAria);
  });

  it('Gruppen sind ÜBERSCHRIFTEN, keine Falten mehr — auf 960 px kostet nichts einen Klick', () => {
    // Der Kern von §3.4: `<details>` war die richtige Antwort auf 340 px und ist
    // die falsche auf 960 px. Ein Fold, den man erst öffnen muss, ist auf einer
    // Galerie-Fläche nur eine Hürde vor dem Vergleichen.
    const doc = renderGallery('aoi');
    expect(doc.querySelectorAll('details')).toHaveLength(0);
    // VIER Überschriften: Automatik + die drei Tageslagen. „Eigene Stimmung"
    // trägt nur das versteckte Nagori und „Klassiker" nur das zurückgezogene
    // Kasumi — `visibleGroups()` lässt leere Gruppen bewusst weg (sonst stünde
    // eine Überschrift ohne eine einzige Karte da).
    expect(
      Array.from(doc.querySelectorAll('.themegallery__group')).map((g) =>
        g.querySelector('h3')?.textContent,
      ),
    ).toEqual(['Folgt dem Tag', 'Morgen', 'Tag', 'Abend & Nacht']);
    // …und ALLE fünfzehn Karten stehen gleichzeitig da (nicht nur die der
    // aktiven Gruppe, wie zu `<details>`-Zeiten).
    expect(radios(doc)).toHaveLength(17);
  });

  it('das AKTIVE Design steht prominent im Kopf — nicht als Karte unter Karten', () => {
    const doc = renderGallery('kasumi');
    const head = doc.querySelector('.themegallery__active');
    expect(head).not.toBeNull();
    expect(head?.textContent).toContain('Kasumi');
    expect(head?.textContent).toContain('Klassiker'); // Gruppen-Beiwort
    expect(head?.textContent).toContain(de.settings.themeActiveLabel);
    // Es bleibt trotzdem bei genau EINER angekreuzten Karte (kein Duplikat).
    expect(radios(doc).filter((r) => r.getAttribute('aria-checked') === 'true')).toHaveLength(1);
  });

  it('ohne Manifest steht ehrlich „lädt …" da — keine erfundene, keine leere Liste', () => {
    resetThemeCatalog();
    const doc = renderGallery('aoi');
    expect(radios(doc)).toHaveLength(0);
    expect(doc.querySelector('.settings__themeloading')?.textContent).toBe(
      de.settings.themeLoading,
    );
  });

  it('genau eine Karte ist aria-checked — die gewählte', () => {
    const doc = renderGallery('kasumi');
    const checked = radios(doc).filter((r) => r.getAttribute('aria-checked') === 'true');
    expect(checked).toHaveLength(1);
    expect(checked[0].getAttribute('aria-label')).toBe('Klassiker: Kasumi');
  });

  it('auch eine der NEUEN Szenen ist normal wählbar (sie sind keine Sonderfälle)', () => {
    const doc = renderGallery('momiji');
    const checked = radios(doc).filter((r) => r.getAttribute('aria-checked') === 'true');
    expect(checked).toHaveLength(1);
    expect(checked[0].getAttribute('aria-label')).toBe('Tag: Momiji');
    // Name + Beiwort kommen aus dem Manifest, die Charakter-Zeile mangels
    // Katalog-Eintrag aus den Kanji — wahr statt erfunden.
    expect(checked[0].textContent).toContain('Momiji · Ahornfärbung');
    expect(checked[0].textContent).toContain('紅葉');
  });

  it('Tastatur bleibt bedienbar: jede Karte ist ein echter <button> (kein div)', () => {
    const doc = renderGallery('aoi');
    expect(radios(doc).every((r) => r.tagName === 'BUTTON')).toBe(true);
    expect(radios(doc).every((r) => r.getAttribute('type') === 'button')).toBe(true);
  });

  it('Farbvorschau: die NICHT-Szenen-Karten tragen eine Swatch mit data-theme + drei Flächen', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 6, 19, 8, 0, 0, 0)); // 08:00 → Asa-Fenster
    // Kasumi aktiv, weil es das EINZIGE Thema ohne eigene Zeichnung ist — und
    // seit 21.08. im Ruhestand, steht also nur noch da, solange es läuft. Genau
    // deshalb ist es hier der Prüfstein: die Swatch ist nicht mehr die Regel,
    // sondern der ehrliche Rückfall für ein Thema ohne Szene.
    const doc = renderGallery('kasumi');
    const swatches = Array.from(doc.querySelectorAll('.settings__swatch'));
    // 16 Karten (15 + das aktive Kasumi) − 14 Szenen − Sora (zeigt um 08:00
    // Asas Szene, s. unten) = EINE Swatch.
    expect(radios(doc)).toHaveLength(18);
    expect(swatches).toHaveLength(1);
    for (const s of swatches) {
      expect(s.getAttribute('aria-hidden')).toBe('true');
      expect(s.querySelector('.settings__swatchbg')).not.toBeNull();
      expect(s.querySelector('.settings__swatchaccent')).not.toBeNull();
      expect(s.querySelector('.settings__swatchtext')).not.toBeNull();
    }
    // Jede Karte zeigt ihr eigenes Thema.
    expect(swatches.map((s) => s.getAttribute('data-theme'))).toEqual(['kasumi']);
    // …und Soras Karte zeigt weiterhin das GERADE aufgelöste Thema — nur eben
    // als dessen Szene statt als Farbfeld, seit Asa eine Zeichnung hat. Die
    // Zusicherung („Sora rät nicht, Sora zeigt") ist dieselbe, der Beweis ist
    // jetzt das Bild.
    const sora = radios(doc).find((r) => r.getAttribute('aria-label') === 'Folgt dem Tag: Sora');
    expect(sora?.querySelector('img')?.getAttribute('src')).toBe(THEME_SCENE_IMAGES.asa);
  });

  it('die drei Flächen tragen die Manifest-Hexes INLINE — Vorschau ohne Nachladen', () => {
    // Der Kern des .old-Umzugs, unverändert gültig: `data-theme` allein färbt nur
    // GELADENE Themen. Die Inline-Hexes machen die Vorschau lade-frei (sonst zöge
    // das Öffnen der Galerie sechzehn Stylesheets).
    // Kasumi aktiv — sonst stünde die einzige Swatch-Karte gar nicht da (s. den
    // Test darüber). Nicht mehr über den Index gesucht, sondern über ihr
    // `data-theme`: eine Position verschöbe sich beim nächsten Pod wieder.
    const doc = renderGallery('kasumi');
    const swatch = doc.querySelector('.settings__swatch[data-theme="kasumi"]') as HTMLElement;
    expect(swatch).not.toBeNull();
    const kasumi = MANIFEST.themes.find((t) => t.id === 'kasumi');
    const styles = Array.from(swatch.children).map((c) => c.getAttribute('style') ?? '');
    expect(styles[0]).toContain(kasumi?.swatch[0]);
    expect(styles[1]).toContain(kasumi?.swatch[1]);
    expect(styles[2]).toContain(kasumi?.swatch[2]);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  Die Szenen zeigen ihre ECHTE Szene (§3.4) — der Unterschied zwischen
//  „Farbmuster" und „so sieht Hoshi dann aus". Seit dem Revival vom 19.08. sind
//  es vierzehn statt sechs, zusammen ~700 KB (ukiyo-wave.svg allein 212 KB) —
//  der Riegel unten wird damit wichtiger, nicht unwichtiger: die Galerie darf
//  sie NICHT alle beim Öffnen ziehen.
// ─────────────────────────────────────────────────────────────────────────────

describe('Szenen-Vorschau — echte Bilder, und zwar erst beim Sichtbarwerden', () => {
  const read = (path: string): string | null => {
    try {
      return readFileSync(path, 'utf8');
    } catch {
      return null;
    }
  };

  it('genau die Szenen-Themen haben ein Bild — und jede Datei liegt wirklich da', () => {
    expect(Object.keys(THEME_SCENE_IMAGES).sort()).toEqual(sceneIds().slice().sort());
    for (const [id, url] of Object.entries(THEME_SCENE_IMAGES)) {
      // `public/` wird 1:1 unter `/` ausgeliefert — der Riegel gegen ein
      // umbenanntes Asset, das sonst still als kaputtes Bild endete.
      expect(read(`public${url}`), id).not.toBeNull();
    }
  });

  it('Szenen-Karten zeigen ihr Bild (loading="lazy"), Nicht-Szenen ihre Swatch', () => {
    // jsdom kennt keinen IntersectionObserver ⇒ die Komponente rendert das Bild
    // sofort (ehrlicher Rückfall: lieber eine sichtbare Szene als ein
    // Platzhalter, der nie auflöst) — `loading="lazy"` bleibt der Browser-Gurt.
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 6, 19, 8, 0, 0, 0)); // 08:00 → Sora zeigt Asa
    const doc = renderGallery('aoi');
    const imgs = Array.from(doc.querySelectorAll('.themegallery__card img'));
    // 14 Szenen-Karten + Soras Karte, die die Szene des GERADE aufgelösten
    // Themas zeigt (08:00 ⇒ Asa) = 15 Bilder.
    expect(imgs).toHaveLength(17);
    for (const img of imgs) {
      expect(img.getAttribute('loading')).toBe('lazy');
      expect(img.getAttribute('src')).toMatch(/^\/themes\//);
      expect(img.getAttribute('alt')).toBeTruthy();
    }
    // Soras Karte steht seit der Tageslage-Sortierung VORNE (die Regel zuerst),
    // dann die vierzehn Szenen in Tageslage-Reihenfolge.
    expect(imgs.map((i) => i.getAttribute('src'))).toEqual([
      THEME_SCENE_IMAGES.asa, // Sora, um 08:00 aufgelöst zu Asa
      ...sceneIds().map((id) => THEME_SCENE_IMAGES[id]),
    ]);
  });

  it('MIT IntersectionObserver: kein einziges Bild, solange nichts sichtbar ist — erst der Beobachter holt sie', async () => {
    // Das ist der eigentliche Beweis für §3.4 („die Galerie darf sie nicht alle
    // beim Öffnen ziehen"): ein Beobachter, der NIE feuert, lässt null Bytes
    // fliegen; lässt man ihn feuern, stehen die fünfzehn Bilder da.
    // Die Uhr MUSS hier stillstehen: Soras Karte zeigt das gerade aufgelöste
    // Thema, und Kasumi (20:00-Station) ist das einzige ohne Zeichnung — ohne
    // feste Zeit zählte dieser Test abends 14 und morgens 15 Bilder.
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 6, 19, 8, 0, 0, 0)); // 08:00 → Asa
    const callbacks: IntersectionObserverCallback[] = [];
    class FakeObserver {
      constructor(cb: IntersectionObserverCallback) {
        callbacks.push(cb);
      }
      observe() {}
      disconnect() {}
      unobserve() {}
      takeRecords() {
        return [];
      }
    }
    vi.stubGlobal('IntersectionObserver', FakeObserver);

    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    await act(async () => {
      root.render(<ThemeGallery open onClose={() => {}} theme="aoi" onTheme={() => {}} />);
    });

    // Nichts sichtbar ⇒ nichts geladen, aber die Karten stehen bereits da:
    // 14 Szenen + Soras Karte, die um 08:00 Asas Szene zeigt.
    expect(container.querySelectorAll('.themegallery__card img')).toHaveLength(0);
    expect(container.querySelectorAll('.themegallery__scene')).toHaveLength(17);
    expect(callbacks).toHaveLength(17);

    await act(async () => {
      for (const cb of callbacks) {
        cb([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver);
      }
    });
    expect(container.querySelectorAll('.themegallery__card img')).toHaveLength(17);

    await act(async () => root.unmount());
    container.remove();
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  Sora + Pin-Hinweis: unverändert dieselben Zusicherungen wie vor §3.4 — sie
//  zeigen nur auf die Galerie, weil dort jetzt die Karten stehen.
// ─────────────────────────────────────────────────────────────────────────────

describe('Sora — die Regel ist ablesbar, bevor man sie wählt', () => {
  /** Baut ein lokales Datum an einer Uhrzeit (Tag egal — nur die Stunde zählt). */
  const at = (hour: number): Date => new Date(2026, 6, 19, hour, 0, 0, 0);
  /** Die Sora-Karte (Gruppe „Folgt dem Tag") — sie steht nicht an Index 0. */
  const soraCard = (doc: Document): HTMLElement =>
    radios(doc).find((r) => r.getAttribute('aria-label') === 'Folgt dem Tag: Sora') as HTMLElement;

  it('zeigt das GERADE aufgelöste Theme („folgt dem Tag · jetzt …")', () => {
    vi.useFakeTimers();
    vi.setSystemTime(at(8)); // 08:00 → Asa-Fenster
    let doc = renderGallery('aoi');
    expect(soraCard(doc).textContent).toContain('folgt dem Tag · jetzt Asa');
    // Seit dem Revival hat Asa eine eigene Zeichnung — Soras Vorschau ist damit
    // kein Farbfeld mehr, sondern die Szene selbst. Dieselbe Zusicherung („Sora
    // zeigt, was gerade läuft"), nur ein besserer Beweis.
    expect(soraCard(doc).querySelector('img')?.getAttribute('src')).toBe(THEME_SCENE_IMAGES.asa);

    vi.setSystemTime(at(3)); // 03:00 → die tiefste Nacht: Nagareboshi
    doc = renderGallery('aoi');
    expect(soraCard(doc).textContent).toContain('folgt dem Tag · jetzt Nagareboshi');
    expect(soraCard(doc).querySelector('img')?.getAttribute('src')).toBe(
      THEME_SCENE_IMAGES.nagareboshi,
    );

    vi.setSystemTime(at(20)); // 20:00 → Kasumi, die einzige Station OHNE Szene
    doc = renderGallery('aoi');
    expect(soraCard(doc).textContent).toContain('folgt dem Tag · jetzt Kasumi');
    expect(soraCard(doc).querySelector('img')).toBeNull();
    expect(soraCard(doc).querySelector('.settings__swatch')?.getAttribute('data-theme')).toBe(
      'kasumi',
    );
  });

  it('der Tagesbogen steht als reine VORSCHAU darunter — in Tages-Reihenfolge, nicht klickbar', () => {
    const doc = renderGallery('aoi');
    const arc = doc.querySelector('.settings__themearc') as HTMLElement;
    expect(arc).not.toBeNull();
    expect(arc.textContent).toBe('Nagareboshi › Asa › Aoi › Kasumi › Yoru');
    expect(arc.querySelector('button')).toBeNull();
    expect(arc.querySelector('[role="radio"]')).toBeNull();
  });

  it('markiert im Bogen leise, welche Station gerade dran ist', () => {
    vi.useFakeTimers();
    vi.setSystemTime(at(20)); // 20:00 → Kasumi
    const doc = renderGallery('sora');
    const now = doc.querySelectorAll('.settings__themearcstep.is-now');
    expect(now).toHaveLength(1);
    expect(now[0].textContent).toContain('Kasumi');
  });
});

describe('Gepinnte Tageszeit — leiser Hinweis, dass die Automatik gerade pausiert', () => {
  it('steht da, wenn ein Rotations-Theme fest gewählt ist', () => {
    const note = renderGallery('yoru').querySelector('.settings__themepinned');
    expect(note?.textContent).toBe('Yoru steht gerade fest — die Automatik pausiert.');
  });

  it('fehlt bei Sora, bei den Stimmungs- und bei den Szenen-Themen (dort ist nichts pausiert)', () => {
    for (const id of ['sora', 'yoake', 'natsunohi', 'amayadori', 'asagiri'] as Theme[]) {
      expect(renderGallery(id).querySelector('.settings__themepinned'), id).toBeNull();
    }
  });
});

describe('Beiworte — schön bleibt schön, aber niemand muss raten', () => {
  it('jede Karte trägt die Übersetzung ihres Namens', () => {
    // Kasumi aktiv, damit WIRKLICH jede Karte dasteht: es ist seit 21.08. im
    // Ruhestand und sonst nicht in der Galerie — sein Beiwort steht aber
    // unverändert im Katalog und muss auf seiner Karte ankommen, solange die
    // Karte existiert.
    const doc = renderGallery('kasumi');
    const text = radios(doc)
      .map((r) => r.textContent ?? '')
      .join('\n');
    for (const [id, gloss] of Object.entries(de.settings.themeGlosses)) {
      expect(text, id).toContain(` · ${gloss}`);
    }
    expect(text).toContain('Nagareboshi · Sternschnuppe');
    expect(text).toContain('Yoake · Morgengrauen');
    expect(text).toContain('Natsu no Hi · Sommertag');
    expect(text).toContain('Amayadori · Regenpause');
    // …und die neuen Szenen genauso (ihr Beiwort kommt aus dem Manifest).
    expect(text).toContain('Asagiri · Morgennebel');
    expect(text).toContain('Komorebi · Licht durch Blätter');
  });

  it('alle fünf Kataloge kennen eine Charakter-Zeile für JEDES Katalog-Theme (kein Loch)', () => {
    for (const id of THEME_IDS) {
      expect(de.settings.themeGlosses[id], id).toBeTruthy();
    }
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  RUHESTAND — unsichtbar in der Galerie, aber weiterhin ein GÜLTIGES Thema
// ─────────────────────────────────────────────────────────────────────────────

describe('Ruhestand — Kasumi ist aus der Galerie raus, aber nicht aus Hoshi', () => {
  // Andi 21.08.: „Entferne die alten, noch nicht animierten Designs." Kasumi ist
  // das letzte Thema ohne eigene Szene. „Entfernen" durfte hier aber NICHT
  // „löschen" heißen: Kasumi ist die 18–22-Uhr-Station von Soras Tagesbogen, und
  // Sora ist die Voreinstellung. Ein gelöschtes Kasumi hätte den Abend jedes
  // Sora-Nutzers stillschweigend kaputtgemacht.
  //
  // Darum: raus aus der WAHL, drin in der MECHANIK.

  it('das Manifest führt Kasumi weiter — mit Gruppe, Datei und Beiwort', () => {
    const kasumi = MANIFEST.themes.find((t) => t.id === 'kasumi');
    expect(kasumi).toBeDefined();
    expect(kasumi?.retired).toBe(true);
    expect(kasumi?.file).toBe('old/kasumi.css');
    expect(kasumi?.gloss.de).toBe(de.settings.themeGlosses.kasumi);
  });

  it('Soras Tagesbogen ist UNBERÜHRT — Kasumi rotiert weiter zu seiner Stunde', () => {
    // Der eigentliche Grund für `retired` statt „löschen".
    expect(SORA_ROTATION).toContain('kasumi');
    expect([...SORA_ROTATION]).toEqual(['nagareboshi', 'asa', 'aoi', 'kasumi', 'yoru']);
  });

  it('eine gespeicherte Kasumi-Wahl überlebt — niemand verliert sein Thema', () => {
    vi.stubGlobal('localStorage', memoryStorage());
    saveSettings({ ...DEFAULT_SETTINGS, theme: 'kasumi' });
    expect(loadSettings().theme).toBe('kasumi');
  });

  it('die Galerie zeigt es NICHT — auch nicht nach dem Nagori-Fund', () => {
    // DER RIEGEL gegen den naheliegenden Fehler: Kasumi über `hidden` zu
    // verstecken statt über `retired`. `hidden` hängt an EINEM Schalter, dem
    // Nagori-Fund — wer Nagori findet, hätte Kasumi gleich mit zurückbekommen.
    // Zwei verschiedene Gründe, unsichtbar zu sein, brauchen zwei Felder.
    for (const unlocked of [false, true]) {
      const ids = visibleGroups(MANIFEST, unlocked).flatMap((g) => g.themes.map((t) => t.id));
      expect(ids, `nagoriUnlocked=${unlocked}`).not.toContain('kasumi');
    }
    // Gegenprobe, dass der Schalter überhaupt etwas tut: Nagori kommt dazu.
    expect(
      visibleGroups(MANIFEST, true).flatMap((g) => g.themes.map((t) => t.id)),
    ).toContain('nagori');
  });

  it('…AUSSER es läuft gerade — ein aktives Thema ist nie unsichtbar', () => {
    // Dieselbe Ehrlichkeits-Regel wie bei Nagori: was läuft, ist ankreuzbar.
    // Sonst stünde die Galerie ohne eine einzige angekreuzte Karte da und der
    // Weg zu einem anderen Thema wäre geraten.
    const ids = visibleGroups(MANIFEST, false, 'kasumi').flatMap((g) =>
      g.themes.map((t) => t.id),
    );
    expect(ids).toContain('kasumi');
    const checked = radios(renderGallery('kasumi')).filter(
      (r) => r.getAttribute('aria-checked') === 'true',
    );
    expect(checked).toHaveLength(1);
    expect(checked[0].getAttribute('aria-label')).toBe('Klassiker: Kasumi');
  });

  it('seine CSS-Datei liegt weiter da — sonst liefe Sora abends ins Leere', () => {
    expect(readFileSync('public/themes/old/kasumi.css', 'utf8')).toContain(
      ":root[data-theme='kasumi'],",
    );
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  CSS-RIEGEL — nach dem Umzug: liegt wirklich JEDE Datei da, die das Manifest
//  verspricht, und trägt sie beide Selektoren?
// ─────────────────────────────────────────────────────────────────────────────

describe('CSS-Riegel — jede Manifest-Zeile hat eine echte, vollständige Datei', () => {
  /** Liest eine Datei; `null` statt Wurf, damit die Zusicherung die Aussage trägt. */
  const read = (path: string): string | null => {
    try {
      return readFileSync(path, 'utf8');
    } catch {
      return null;
    }
  };

  it('jede im Manifest genannte Datei existiert unter public/themes/', () => {
    for (const theme of MANIFEST.themes) {
      if (!theme.file) continue; // Sora ist eine Regel, keine Farbe
      expect(read(`public/themes/${theme.file}`), theme.id).not.toBeNull();
    }
  });

  it('jede Themen-Datei trägt BEIDE Selektoren — sonst bliebe die Vorschau-Kachel farblos', () => {
    // Die Vorschau-Kachel ist kein <html>: ein reiner `:root[data-theme='…']`-
    // Block würde sie NICHT treffen, sie erbte dann die Farben des aktiven
    // Themas. Darum trägt jeder Block zusätzlich den :root-freien Selektor.
    for (const theme of MANIFEST.themes) {
      if (!theme.file || theme.id === 'yoru') continue; // Yoru = Basis-Satz, s. unten
      const css = read(`public/themes/${theme.file}`) as string;
      expect(css.includes(`:root[data-theme='${theme.id}'],`), theme.id).toBe(true);
      expect(
        new RegExp(`(^|[,\\s])\\[data-theme='${theme.id}'\\]`, 'm').test(css),
        `${theme.id}: :root-freier Token-Block fehlt`,
      ).toBe(true);
    }
    // Yoru IST der Basis-Satz und wohnt weiterhin in index.css — dort steht der
    // :root-freie Selektor, damit seine Vorschau-Kachel farbig bleibt.
    const indexCss = read('src/index.css') as string;
    expect(/(^|[,\s])\[data-theme='yoru'\]/m.test(indexCss)).toBe(true);
  });

  it('die Manifest-Swatches sind die echten Token der Datei (kein zweiter Farbsatz)', () => {
    // Stichprobe auf vier Themen mit festen, hier ausgeschriebenen Werten —
    // sie belegen, dass die Vorschau nicht rät, sondern die Datei zitiert.
    // Anders als vor dem Revival schreiben die Pod-Dateien ihre Farben in OKLCH
    // und nennen den sRGB-Wert im Kommentar dahinter; die Stichprobe akzeptiert
    // darum BEIDE Schreibweisen und wird dadurch breiter, nicht weicher.
    const cases: Record<string, string[]> = {
      aoi: ['#141a26', '#5ea0f2', '#e8eef7'], // Hex direkt
      amayadori: ['#1a1613', '#ee9b6e', '#f2e9dc'], // Hex direkt
      yoake: ['#171327', '#ff9575', '#f7eee7'], // OKLCH + Hex-Kommentar
      nagareboshi: ['#050812', '#f8b65d', '#e9edf2'], // v2-Szene 20.08. — OKLCH + Hex-Kommentar
    };
    // Der Wert, den die DATEI für ein Token nennt: entweder als Hex-Literal
    // (`--accent: #5ea0f2;`) oder als Kommentar hinter dem OKLCH-Wert.
    const hexInFile = (css: string, token: string): string | null =>
      new RegExp(`^\\s*${token}:\\s*(#[0-9a-f]{6})\\s*;`, 'm').exec(css)?.[1] ??
      new RegExp(`^\\s*${token}:[^;]*;\\s*/\\*\\s*(#[0-9a-f]{6})`, 'm').exec(css)?.[1] ??
      null;
    for (const [id, expected] of Object.entries(cases)) {
      const theme = MANIFEST.themes.find((t) => t.id === id);
      expect(theme?.swatch.map((c) => c.toLowerCase()), id).toEqual(expected);
      const css = (read(`public/themes/${theme?.file}`) as string).toLowerCase();
      expect(hexInFile(css, '--bg-surface'), id).toBe(expected[0]);
      expect(hexInFile(css, '--accent'), id).toBe(expected[1]);
      expect(hexInFile(css, '--text-1'), id).toBe(expected[2]);
    }
    // EHRLICH: `natsunohi` steht bewusst nicht in dieser Stichprobe. Seine Datei
    // rechnet durchgehend in OKLCH und nennt NIRGENDS einen sRGB-Wert; ein
    // statischer Vergleich müsste die Umrechnung hier nachbauen — also eigene
    // Mathematik gegen eigene Mathematik prüfen. Sein Swatch ist stattdessen
    // headless in Chrome gegengelesen (s. RESULT.md der Integrations-Scheibe).
    expect(read('public/themes/natsunohi.css')).not.toMatch(/--bg-surface:.*#[0-9a-f]{6}/i);
  });

  it('…und wo eine OKLCH-Datei ihren Hex selbst im Kommentar nennt, stimmt er überein', () => {
    // Die Szenen-Dateien der Designer-Pods rechnen in OKLCH und schreiben den
    // sRGB-Wert als Kommentar dahinter. Wo dieser Kommentar steht, ist er die
    // Quelle für den Manifest-Swatch — dieser Riegel hält beide zusammen, auch
    // wenn ein Pod die Farbe später nachjustiert. Wo KEIN Kommentar steht, ist
    // der Wert umgerechnet (s. Rate-Stellen der Umzugs-Scheibe).
    const tokenFor = ['--bg-surface', '--accent', '--text-1'];
    for (const theme of MANIFEST.themes) {
      if (!theme.file) continue;
      const css = read(`public/themes/${theme.file}`) as string;
      tokenFor.forEach((token, i) => {
        // `  --accent: oklch(…); /* #a23d50 */`  → der Hex hinter dem Semikolon
        const line = new RegExp(`^\\s*${token}:[^;]*;\\s*/\\*\\s*(#[0-9a-fA-F]{6})`, 'm').exec(css);
        if (!line) return; // kein Hex-Kommentar → nichts zu vergleichen
        expect(line[1].toLowerCase(), `${theme.id}/${token}`).toBe(theme.swatch[i].toLowerCase());
      });
    }
  });
});

describe('themes.css — die Themen sind wirklich AUSGEZOGEN, der Default bleibt sofort da', () => {
  const themesCss = readFileSync('src/styles/themes.css', 'utf8');
  /** Nur die REGELN — Kommentare dürfen die Selektoren selbstverständlich nennen. */
  const rules = themesCss.replace(/\/\*[\s\S]*?\*\//g, '');

  it('kein einziger per-Theme-Token-Block wohnt noch im Bundle', () => {
    // Der Kern von Andis Auftrag: „nicht in der CSS liegen, sondern dynamisch
    // geladen werden". Ein zurückgerutschter Block fiele hier sofort auf.
    for (const theme of MANIFEST.themes) {
      expect(rules.includes(`data-theme='${theme.id}']`), theme.id).toBe(false);
    }
    // Auch keine Themen-eigenen Signaturen (Traufe/Leuchtspur) mehr.
    expect(rules).not.toContain('@keyframes amayadori-drift');
    expect(rules).not.toContain('@keyframes nagori-trail');
  });

  it('der Basis-Look steht OHNE Netz im Bundle — und ist genau das Default-Theme', () => {
    // `:root:not([data-theme])` ist (0,2,0) und schlägt damit das `:root` aus
    // index.css unabhängig von der Bündel-Reihenfolge. Ohne diesen Block sähe
    // der erste Frame nach Yoru statt nach Aoi aus.
    expect(themesCss).toContain(':root:not([data-theme]) {');
    expect(DEFAULT_SETTINGS.theme).toBe('aoi');
    // Seit dem Revival ist Aoi eine echte Szene mit eigener Datei; der alte Stub
    // `old/aoi.css` ist mit dieser Scheibe entfernt. Die drei Werte stehen
    // unverändert (byte-gleich) in der neuen Datei — genau das prüft die Zeile.
    const aoiCss = readFileSync('public/themes/aoi.css', 'utf8');
    const base = themesCss.slice(themesCss.indexOf(':root:not([data-theme]) {'));
    for (const token of ['--bg-base: #0c1017', '--bg-surface: #141a26', '--accent: #5ea0f2']) {
      expect(base, token).toContain(token);
      expect(aoiCss, token).toContain(token); // …und die Datei sagt dasselbe
    }
  });

  it('die Atmosphäre greift auch OHNE gesetztes data-theme (sonst startet es flach)', () => {
    expect(themesCss).toContain(':root:not([data-theme]) body {');
    expect(themesCss).toContain(':root[data-theme] body {');
  });

  it('der Basis-Block LECKT NICHT in einen gesetzten Theme-Zustand — die tragende Zusage', () => {
    // Das ist die eine Stelle, an der diese Datei genau sein muss. Zwei Gründe:
    //
    //  a) REIHENFOLGE: main.tsx importiert App (und damit themes.css) VOR
    //     index.css — im gebauten Bundle steht themes.css also ZUERST. Ein
    //     nackter `:root {…}`-Block hier (0,1,0) würde vom `:root` aus index.css
    //     (0,1,0, aber später) geschlagen: der Default-Look wäre Yoru statt Aoi.
    //     `:root:not([data-theme])` ist (0,2,0) — `:not()` erbt die Spezifität
    //     seines Arguments — und gewinnt unabhängig von der Reihenfolge.
    //
    //  b) KEIN LECK: sobald `data-theme` am <html> steht, greift der Block gar
    //     nicht mehr. Darunter liegt dann genau wie vor dem Umzug der
    //     `:root`-Satz aus index.css (= Yoru). Kein Theme erbt plötzlich einen
    //     Aoi-Token, den es früher nicht hatte — insbesondere behält
    //     `[data-theme='yoru']` (index.css) seine Rolle als Basis-Satz.
    expect(/^:root\s*\{/m.test(rules), 'nackter :root-Block in themes.css').toBe(false);
    expect(/^:root,/m.test(rules), 'nackte :root-Selektorliste in themes.css').toBe(false);
    // Jeder Token-Block dieser Datei hängt am `:not([data-theme])`-Riegel.
    const tokenBlocks = rules.match(/^[^\s@}][^{]*\{[^}]*--bg-base:/gm) ?? [];
    expect(tokenBlocks).toHaveLength(1);
    expect(tokenBlocks[0]).toContain(':not([data-theme])');
  });

  it('die Kachel-Flächen lesen weiterhin echte Token als Fallback (kein driftender Farbsatz)', () => {
    expect(themesCss).toContain('.settings__swatchbg {');
    for (const token of ['var(--bg-surface)', 'var(--accent)', 'var(--text-1)']) {
      expect(themesCss).toContain(token);
    }
    // Die alten, handgepflegten Vorschau-Verläufe sind weg — sie waren die
    // zweite Farbliste, die bei jedem neuen Theme mitgepflegt werden musste.
    expect(themesCss).not.toContain('.settings__swatch--');
  });
});

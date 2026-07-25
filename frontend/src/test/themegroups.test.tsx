/** @vitest-environment jsdom */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { ThemeSection, themeGroupHeadingId } from '../components/SettingsPanel';
import {
  DEFAULT_SETTINGS,
  SORA_ROTATION,
  THEME_GROUPS,
  THEME_GROUP_IDS,
  THEME_IDS,
  loadSettings,
  saveSettings,
  type Theme,
} from '../hooks/useSettings';
import { de } from '../i18n/de';

// ═════════════════════════════════════════════════════════════════════════════
//  Theme-GRUPPEN (Andi 25.07: „Überlege dir ein Konzept, wie man die Auswahl der
//  Themen übersichtlicher machen kann. Das sind jetzt schon einige.")
//
//  Was hier gepinnt wird, ist genau das, was die Übersicht ausmacht:
//   1. die drei Gruppen decken die acht Themen VOLLSTÄNDIG und ÜBERSCHNEIDUNGS-
//      FREI ab (ein künftiges neuntes Theme fällt nicht still aus dem Panel),
//   2. „Tageszeiten" steht in TAGES-Reihenfolge (nicht alphabetisch),
//   3. Sora zeigt das GERADE aufgelöste Theme — die Regel ist ablesbar, bevor
//      man sie wählt,
//   4. die persistierten Ids ({@link THEME_IDS}) sind UNVERÄNDERT — niemand
//      verliert seine gespeicherte Wahl,
//   5. die echte Farbvorschau hängt an den echten Theme-Token: jede Kachel setzt
//      `data-theme` selbst, und für JEDES Theme existiert dazu ein Token-Block,
//      der auch auf verschachtelte Elemente greift (CSS-Riegel unten).
// ═════════════════════════════════════════════════════════════════════════════

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

/** Rendert die Sektion statisch und gibt ein parsbares Dokument zurück. */
function render(theme: Theme): Document {
  const html = renderToStaticMarkup(<ThemeSection theme={theme} onTheme={() => {}} />);
  return new DOMParser().parseFromString(html, 'text/html');
}

const radios = (doc: Document): HTMLElement[] =>
  Array.from(doc.querySelectorAll('[role="radio"]')) as HTMLElement[];

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('THEME_GROUPS — drei Gruppen statt einer Liste', () => {
  it('die Gruppen decken exakt THEME_IDS ab — jede Id genau einmal', () => {
    const grouped = THEME_GROUPS.flatMap((g) => g.themes);
    expect([...grouped].sort()).toEqual([...THEME_IDS].sort());
    expect(new Set(grouped).size).toBe(grouped.length); // keine Id doppelt
    expect(grouped).toHaveLength(8);
  });

  it('Reihenfolge der Gruppen: Automatik ganz oben, dann Tageszeiten, dann Stimmung', () => {
    expect(THEME_GROUPS.map((g) => g.id)).toEqual(['automatik', 'tageszeiten', 'stimmung']);
    expect(THEME_GROUPS.map((g) => g.id)).toEqual([...THEME_GROUP_IDS]);
  });

  it('Gruppe 1 „Folgt dem Tag" enthält NUR Sora (es ist keine Farbe, sondern eine Regel)', () => {
    expect(THEME_GROUPS[0].themes).toEqual(['sora']);
  });

  it('Gruppe 2 „Tageszeiten" ist die Rotation in TAGES-Reihenfolge, nicht alphabetisch', () => {
    expect(THEME_GROUPS[1].themes).toEqual(['nagareboshi', 'asa', 'aoi', 'kasumi', 'yoru']);
    expect(THEME_GROUPS[1].themes).toEqual([...SORA_ROTATION]);
    // Gegenprobe: alphabetisch wäre eine ANDERE Reihenfolge.
    expect(THEME_GROUPS[1].themes).not.toEqual([...THEME_GROUPS[1].themes].sort());
  });

  it('Gruppe 3 „Eigene Stimmung" sind die beiden Bilder — bewusst NICHT an der Uhr', () => {
    expect(THEME_GROUPS[2].themes).toEqual(['yoake', 'natsunohi']);
    for (const id of THEME_GROUPS[2].themes) expect(SORA_ROTATION).not.toContain(id);
  });
});

describe('Persistierte Ids — die Gruppierung ändert NICHTS an der gespeicherten Wahl', () => {
  it('THEME_IDS ist unverändert (Reihenfolge + Ids, Aoi weiterhin zuerst)', () => {
    expect([...THEME_IDS]).toEqual([
      'aoi',
      'yoru',
      'asa',
      'natsunohi',
      'kasumi',
      'nagareboshi',
      'yoake',
      'sora',
    ]);
  });

  it('jede Id aus jeder Gruppe überlebt einen Speicher-/Lade-Rundlauf', () => {
    for (const theme of THEME_GROUPS.flatMap((g) => g.themes)) {
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
});

describe('ThemeSection — was im Panel wirklich steht', () => {
  it('alle acht Karten stehen in Gruppen-Reihenfolge im DOM', () => {
    const doc = render('aoi');
    expect(radios(doc).map((r) => r.getAttribute('aria-label'))).toEqual([
      'Folgt dem Tag: Sora',
      'Tageszeiten: Nagareboshi',
      'Tageszeiten: Asa',
      'Tageszeiten: Aoi',
      'Tageszeiten: Kasumi',
      'Tageszeiten: Yoru',
      'Eigene Stimmung: Yoake',
      'Eigene Stimmung: Natsu no Hi',
    ]);
  });

  it('jede Gruppe hat eine Überschrift mit stabiler Id (a11y: Gruppen sind benannt)', () => {
    const doc = render('aoi');
    for (const g of THEME_GROUPS) {
      const head = doc.getElementById(themeGroupHeadingId(g.id));
      expect(head, g.id).not.toBeNull();
      expect(head?.textContent).toBe(de.settings.themeGroups[g.id].title);
    }
    // Die Auswahl bleibt EINE exklusive Wahl über alle Gruppen hinweg.
    const group = doc.querySelector('[role="radiogroup"]');
    expect(group?.getAttribute('aria-label')).toBe(de.settings.themeGroupAria);
  });

  it('genau eine Karte ist aria-checked — die gewählte', () => {
    const doc = render('kasumi');
    const checked = radios(doc).filter((r) => r.getAttribute('aria-checked') === 'true');
    expect(checked).toHaveLength(1);
    expect(checked[0].getAttribute('aria-label')).toBe('Tageszeiten: Kasumi');
  });

  it('Tastatur bleibt bedienbar: jede Karte ist ein echter <button> (kein div)', () => {
    const doc = render('aoi');
    expect(radios(doc).every((r) => r.tagName === 'BUTTON')).toBe(true);
    expect(radios(doc).every((r) => r.getAttribute('type') === 'button')).toBe(true);
  });

  it('echte Farbvorschau: jede Karte trägt eine Swatch mit eigenem data-theme + drei Flächen', () => {
    const doc = render('aoi');
    const swatches = Array.from(doc.querySelectorAll('.settings__swatch'));
    expect(swatches).toHaveLength(8);
    for (const s of swatches) {
      expect(s.getAttribute('aria-hidden')).toBe('true');
      expect(s.querySelector('.settings__swatchbg')).not.toBeNull();
      expect(s.querySelector('.settings__swatchaccent')).not.toBeNull();
      expect(s.querySelector('.settings__swatchtext')).not.toBeNull();
    }
    // Nicht-Sora-Karten zeigen ihr eigenes Thema (Reihenfolge = Gruppen-Reihenfolge).
    expect(swatches.slice(1).map((s) => s.getAttribute('data-theme'))).toEqual([
      'nagareboshi',
      'asa',
      'aoi',
      'kasumi',
      'yoru',
      'yoake',
      'natsunohi',
    ]);
  });
});

describe('Sora — die Regel ist ablesbar, bevor man sie wählt', () => {
  /** Baut ein lokales Datum an einer Uhrzeit (Tag egal — nur die Stunde zählt). */
  const at = (hour: number): Date => new Date(2026, 6, 19, hour, 0, 0, 0);

  it('zeigt das GERADE aufgelöste Theme („folgt dem Tag · jetzt …")', () => {
    vi.useFakeTimers();
    vi.setSystemTime(at(8)); // 08:00 → Asa-Fenster
    let doc = render('aoi');
    expect(radios(doc)[0].textContent).toContain('folgt dem Tag · jetzt Asa');
    expect(doc.querySelectorAll('.settings__swatch')[0].getAttribute('data-theme')).toBe('asa');

    vi.setSystemTime(at(3)); // 03:00 → die tiefste Nacht: Nagareboshi
    doc = render('aoi');
    expect(radios(doc)[0].textContent).toContain('folgt dem Tag · jetzt Nagareboshi');
    expect(doc.querySelectorAll('.settings__swatch')[0].getAttribute('data-theme')).toBe(
      'nagareboshi',
    );
  });

  it('der Tagesbogen steht als reine VORSCHAU darunter — in Tages-Reihenfolge, nicht klickbar', () => {
    const doc = render('aoi');
    const arc = doc.querySelector('.settings__themearc') as HTMLElement;
    expect(arc).not.toBeNull();
    expect(arc.textContent).toBe('Nagareboshi › Asa › Aoi › Kasumi › Yoru');
    expect(arc.querySelector('button')).toBeNull();
    expect(arc.querySelector('[role="radio"]')).toBeNull();
  });

  it('markiert im Bogen leise, welche Station gerade dran ist', () => {
    vi.useFakeTimers();
    vi.setSystemTime(at(20)); // 20:00 → Kasumi
    const doc = render('sora');
    const now = doc.querySelectorAll('.settings__themearcstep.is-now');
    expect(now).toHaveLength(1);
    expect(now[0].textContent).toContain('Kasumi');
  });
});

describe('Gepinnte Tageszeit — leiser Hinweis, dass die Automatik gerade pausiert', () => {
  it('steht da, wenn ein Rotations-Theme fest gewählt ist', () => {
    const doc = render('yoru');
    const note = doc.querySelector('.settings__themepinned');
    expect(note?.textContent).toBe('Yoru steht gerade fest — die Automatik pausiert.');
  });

  it('fehlt bei Sora und bei den Stimmungs-Themes (dort ist nichts pausiert)', () => {
    expect(render('sora').querySelector('.settings__themepinned')).toBeNull();
    expect(render('yoake').querySelector('.settings__themepinned')).toBeNull();
    expect(render('natsunohi').querySelector('.settings__themepinned')).toBeNull();
  });
});

describe('Beiworte — schön bleibt schön, aber niemand muss raten', () => {
  it('jede Karte trägt die Übersetzung ihres Namens', () => {
    const doc = render('aoi');
    const text = radios(doc)
      .map((r) => r.textContent ?? '')
      .join('\n');
    for (const [id, gloss] of Object.entries(de.settings.themeGlosses)) {
      expect(text, id).toContain(` · ${gloss}`);
    }
    expect(text).toContain('Nagareboshi · Sternschnuppe');
    expect(text).toContain('Yoake · Morgengrauen');
    expect(text).toContain('Natsu no Hi · Sommertag');
  });

  it('alle fünf Kataloge kennen ein Beiwort für JEDES Theme (kein Loch)', () => {
    for (const id of THEME_IDS) {
      expect(de.settings.themeGlosses[id], id).toBeTruthy();
    }
  });
});

describe('CSS-Riegel — die Farbvorschau hängt an den echten Theme-Token', () => {
  // Pfade relativ zum Vitest-Root (dem `frontend/`-Verzeichnis) — s. node-fs.d.ts
  // dazu, warum hier gelesen und nicht `?raw`-importiert wird.
  const themesCss = readFileSync('src/styles/themes.css', 'utf8');
  const indexCss = readFileSync('src/index.css', 'utf8');
  const css = `${indexCss}\n${themesCss}`;

  it('jedes Theme hat einen Token-Block, der AUCH auf verschachtelte Elemente greift', () => {
    // Die Vorschau-Kachel ist kein <html>: ein reiner `:root[data-theme='…']`-
    // Block würde sie NICHT treffen, sie erbte dann die Farben des aktiven
    // Themas. Darum trägt jeder Block zusätzlich den :root-freien Selektor.
    for (const id of THEME_IDS) {
      if (id === 'sora') continue; // Sora hat keine eigenen Farben (Regel, kein Thema)
      const selector = new RegExp(`(^|[,\\s])\\[data-theme='${id}'\\]`, 'm');
      expect(selector.test(css), `${id}: :root-freier Token-Block fehlt`).toBe(true);
    }
  });

  it('die Kachel-Flächen lesen echte Token (kein zweiter, driftender Farbsatz)', () => {
    expect(themesCss).toContain('.settings__swatchbg {');
    for (const token of ['var(--bg-surface)', 'var(--accent)', 'var(--text-1)']) {
      expect(themesCss).toContain(token);
    }
    // Die alten, handgepflegten Vorschau-Verläufe sind weg — sie waren die
    // zweite Farbliste, die bei jedem neuen Theme mitgepflegt werden musste.
    expect(themesCss).not.toContain('.settings__swatch--');
  });
});

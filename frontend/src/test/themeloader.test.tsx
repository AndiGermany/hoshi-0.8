/** @vitest-environment jsdom */
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import {
  THEME_BASE_PATH,
  THEME_LINK_ATTR,
  ensureThemeLoaded,
  isThemeLinked,
  resetThemeLoader,
  useAppliedTheme,
} from '../styles/themeLoader';
import {
  THEME_MANIFEST_URL,
  cachedThemeManifest,
  findTheme,
  isKnownTheme,
  loadThemeManifest,
  parseThemeManifest,
  primeThemeManifest,
  resetThemeCatalog,
  themeGloss,
  visibleGroups,
  type ThemeManifest,
} from '../styles/themeCatalog';
import { DEFAULT_SETTINGS, SETTINGS_STORAGE_KEY, useSettings } from '../hooks/useSettings';

// ═════════════════════════════════════════════════════════════════════════════
//  DER .old-UMZUG (Andi-Auftrag 2026-08-08: „Ich möchte, dass du die Designs in
//  ein .old verschiebst. Das soll dynamisch nachladbar sein — nicht in der CSS
//  liegen, sondern dynamisch geladen werden. Zeigen wir, was auch in 1.0 bleiben
//  wird.")
//
//  Diese Datei pinnt die NAHT selbst — die Mechanik, an der ein dynamisch
//  nachgeladenes Design kaputtgeht, ohne dass es jemand merkt:
//
//   1. LOADER — genau EIN <link> je Thema, einmal geladen bleibt es; ein
//      Fehlschlag ist ehrlich (false + Warnung + totes <link> weg) und
//      wiederholbar.
//   2. MANIFEST — es ist eine DATEI, also potenziell falsch. Falsche Version,
//      kaputter Eintrag, ausgebrochener Pfad: nichts davon darf die Auswahl
//      mitreißen oder gar Code ausführen.
//   3. FOUC — `data-theme` wird ERST nach dem `load`-Ereignis gesetzt. Vorher
//      steht der Basis-Look; scheitert das Laden, bleibt der aktuelle Look.
//   4. ROTATION — Soras Tageswechsel nimmt exakt denselben Lade-Weg wie eine
//      Wahl von Hand (sonst würde die Automatik unbemerkt ungestylt schalten).
// ═════════════════════════════════════════════════════════════════════════════

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/** Die echte ausgelieferte Manifest-Datei (Pfad relativ zum Vitest-Root). */
const MANIFEST_JSON = JSON.parse(readFileSync('public/themes/manifest.json', 'utf8')) as unknown;
const MANIFEST = parseThemeManifest(MANIFEST_JSON) as ThemeManifest;

/** Das eingehängte <link> eines Themas (oder `null`). */
const linkOf = (id: string): HTMLLinkElement | null =>
  document.querySelector(`link[${THEME_LINK_ATTR}="${id}"]`);

/** Feuert das `load`-Ereignis, das der Browser feuern würde (jsdom lädt kein CSS). */
const fireLoad = (id: string) => linkOf(id)?.dispatchEvent(new Event('load'));
const fireError = (id: string) => linkOf(id)?.dispatchEvent(new Event('error'));

beforeEach(() => {
  resetThemeLoader();
  resetThemeCatalog();
  delete document.documentElement.dataset.theme;
});

afterEach(() => {
  resetThemeLoader();
  resetThemeCatalog();
  delete document.documentElement.dataset.theme;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

// ─────────────────────────────────────────────────────────────────────────────
//  1) Der Loader
// ─────────────────────────────────────────────────────────────────────────────

describe('themeLoader — ein <link> je Thema, ehrlich beim Scheitern', () => {
  it('hängt ein Stylesheet-<link> mit sprechendem Pfad und Marker-Attribut ein', () => {
    void ensureThemeLoaded('kasumi', 'old/kasumi.css');
    const link = linkOf('kasumi');
    expect(link).not.toBeNull();
    expect(link?.rel).toBe('stylesheet');
    // `?v=dev`: der Build-Stempel fürs Cache-Busting — im Test immer 'dev',
    // weil vitest das vite-define nicht zieht (s. themeLoader.buildId).
    expect(link?.getAttribute('href')).toBe(`${THEME_BASE_PATH}old/kasumi.css?v=dev`);
    expect(link?.parentElement).toBe(document.head);
    expect(isThemeLinked('kasumi')).toBe(true);
  });

  it('löst ERST nach dem load-Ereignis auf — daran hängt die ganze FOUC-Zusage', async () => {
    let settled: boolean | 'offen' = 'offen';
    const promise = ensureThemeLoaded('yoru', 'old/yoru.css').then((ok) => (settled = ok));
    // Ein Tick vergehen lassen: ohne `load` darf sich nichts entschieden haben.
    await Promise.resolve();
    expect(settled).toBe('offen');
    fireLoad('yoru');
    await promise;
    expect(settled).toBe(true);
  });

  it('lädt genau EINMAL: der zweite Aufruf bekommt dasselbe Promise, kein zweites <link>', async () => {
    const first = ensureThemeLoaded('asagiri', 'asagiri.css');
    const second = ensureThemeLoaded('asagiri', 'asagiri.css');
    expect(second).toBe(first);
    expect(document.querySelectorAll(`link[${THEME_LINK_ATTR}="asagiri"]`)).toHaveLength(1);
    fireLoad('asagiri');
    expect(await first).toBe(true);
    // …und auch NACH dem Laden bleibt es bei einem <link> (Zurückwechseln ist sofort).
    expect(await ensureThemeLoaded('asagiri', 'asagiri.css')).toBe(true);
    expect(document.querySelectorAll(`link[${THEME_LINK_ATTR}="asagiri"]`)).toHaveLength(1);
  });

  it('ein bereits im DOM stehendes <link> wird übernommen statt verdoppelt', async () => {
    const preload = document.createElement('link');
    preload.rel = 'stylesheet';
    preload.href = '/themes/momiji.css';
    preload.setAttribute(THEME_LINK_ATTR, 'momiji');
    document.head.appendChild(preload);

    const promise = ensureThemeLoaded('momiji', 'momiji.css');
    expect(document.querySelectorAll(`link[${THEME_LINK_ATTR}="momiji"]`)).toHaveLength(1);
    preload.dispatchEvent(new Event('load'));
    expect(await promise).toBe(true);
  });

  it('Fehlschlag ist EHRLICH: false, eine Warnung, totes <link> weg — nie eine halbe Seite', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const promise = ensureThemeLoaded('gibtesnicht', 'gibtesnicht.css');
    fireError('gibtesnicht');
    expect(await promise).toBe(false);
    expect(linkOf('gibtesnicht')).toBeNull(); // aufgeräumt
    expect(warn).toHaveBeenCalledTimes(1);
    expect(String(warn.mock.calls[0][0])).toContain('gibtesnicht');
  });

  it('…und wiederholbar: nach einem Fehlschlag darf ein neuer Versuch gelingen', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const first = ensureThemeLoaded('komorebi', 'komorebi.css');
    fireError('komorebi');
    expect(await first).toBe(false);

    const second = ensureThemeLoaded('komorebi', 'komorebi.css');
    expect(second).not.toBe(first); // kein gecachter Fehlschlag
    expect(linkOf('komorebi')).not.toBeNull();
    fireLoad('komorebi');
    expect(await second).toBe(true);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  2) Das Manifest — es ist eine Datei, also potenziell falsch
// ─────────────────────────────────────────────────────────────────────────────

describe('parseThemeManifest — Validierung, damit eine kaputte Datei nichts mitreißt', () => {
  /** Ein minimales, gültiges Manifest als Bauklotz für die Gegenproben. */
  const valid = () => ({
    version: 1,
    groups: [{ id: 'tag', order: 1 }],
    themes: [
      {
        id: 'testtheme',
        name: 'Test',
        kanji: '試',
        gloss: { de: 'a', en: 'b', es: 'c', fr: 'd', it: 'e' },
        group: 'tag',
        swatch: ['#111111', '#222222', '#333333'],
        file: 'testtheme.css',
      },
    ],
  });

  it('die ECHTE ausgelieferte Datei ist gültig (der wichtigste Fall)', () => {
    expect(MANIFEST).not.toBeNull();
    expect(MANIFEST.themes.length).toBeGreaterThan(0);
  });

  it('kein Objekt / falsche Version / keine Gruppen / keine Themen ⇒ null', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    expect(parseThemeManifest(null)).toBeNull();
    expect(parseThemeManifest('nope')).toBeNull();
    expect(parseThemeManifest([])).toBeNull();
    expect(parseThemeManifest({ ...valid(), version: 2 })).toBeNull();
    expect(parseThemeManifest({ ...valid(), groups: [] })).toBeNull();
    expect(parseThemeManifest({ ...valid(), themes: [] })).toBeNull();
  });

  it('Gruppen kommen in `order`-Reihenfolge heraus, egal wie sie in der Datei stehen', () => {
    const parsed = parseThemeManifest({
      ...valid(),
      groups: [
        { id: 'klassiker', order: 4 },
        { id: 'tag', order: 1 },
        { id: 'automatik', order: 2 },
      ],
    });
    expect(parsed?.groups.map((g) => g.id)).toEqual(['tag', 'automatik', 'klassiker']);
  });

  it('unbekannte Gruppen-Ids fliegen raus (Tippfehler soll nicht still eine Gruppe erfinden)', () => {
    const parsed = parseThemeManifest({
      ...valid(),
      groups: [{ id: 'tag', order: 1 }, { id: 'tageszeiten', order: 2 }],
    });
    expect(parsed?.groups.map((g) => g.id)).toEqual(['tag']);
  });

  it('EIN kaputter Eintrag fliegt raus — die anderen überleben', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const base = valid();
    const parsed = parseThemeManifest({
      ...base,
      themes: [{ id: 'kaputt' }, ...base.themes],
    });
    expect(parsed?.themes.map((t) => t.id)).toEqual(['testtheme']);
    expect(warn).toHaveBeenCalled();
  });

  it('jedes Pflichtfeld ist wirklich Pflicht (sonst erbte die Kachel fremde Farben)', () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const broken: Record<string, unknown>[] = [
      { id: 'GROSS' }, // Id muss zum CSS-Selektor/Pfad passen
      { id: 'x y' },
      { name: '' },
      { kanji: '' },
      { group: 'gibtsnicht' },
      { gloss: { de: 'a' } }, // nicht alle fünf Sprachen
      { swatch: ['#111111', '#222222'] }, // nur zwei Flächen
      { swatch: ['rot', '#222222', '#333333'] }, // kein Hex
      { file: '../../etc/passwd.css' }, // Ausbruch aus dem Themen-Ordner
      { file: 'x.js' }, // kein Stylesheet
      { hidden: 'ja' }, // kein Boolean
    ];
    for (const patch of broken) {
      const base = valid();
      const parsed = parseThemeManifest({
        ...base,
        themes: [{ ...base.themes[0], ...patch }],
      });
      expect(parsed, JSON.stringify(patch)).toBeNull();
    }
  });

  it('`file` DARF fehlen — Sora ist eine Regel, keine Farbe', () => {
    const base = valid();
    const noFile = { ...base.themes[0] } as Record<string, unknown>;
    delete noFile.file;
    const parsed = parseThemeManifest({ ...base, themes: [noFile] });
    expect(parsed?.themes[0].file).toBeUndefined();
    // …und die echte Datei nutzt genau das für Sora.
    expect(findTheme(MANIFEST, 'sora')?.file).toBeUndefined();
  });

  it('eine doppelte Id gewinnt nicht zweimal — der erste Eintrag zählt', () => {
    const base = valid();
    const parsed = parseThemeManifest({
      ...base,
      themes: [base.themes[0], { ...base.themes[0], name: 'Zweiter' }],
    });
    expect(parsed?.themes).toHaveLength(1);
    expect(parsed?.themes[0].name).toBe('Test');
  });
});

describe('Katalog-Abfragen — Gruppen, Beiworte, unbekannte Ids', () => {
  it('visibleGroups: ohne Manifest ehrlich leer, nie eine erfundene Liste', () => {
    expect(visibleGroups(null, false)).toEqual([]);
  });

  it('visibleGroups blendet `hidden` aus — und auf Wunsch wieder ein', () => {
    const locked = visibleGroups(MANIFEST, false).flatMap((g) => g.themes.map((t) => t.id));
    const unlocked = visibleGroups(MANIFEST, true).flatMap((g) => g.themes.map((t) => t.id));
    expect(locked).not.toContain('nagori');
    expect(unlocked).toContain('nagori');
    expect(unlocked).toHaveLength(locked.length + 1);
  });

  it('leere Gruppen fallen raus (eine Überschrift ohne Inhalt wäre eine Lüge)', () => {
    const parsed = parseThemeManifest({
      version: 1,
      groups: [
        { id: 'tag', order: 1 },
        { id: 'klassiker', order: 2 },
      ],
      themes: [
        {
          id: 'nurszene',
          name: 'N',
          kanji: '試',
          gloss: { de: 'a', en: 'b', es: 'c', fr: 'd', it: 'e' },
          group: 'tag',
          swatch: ['#111111', '#222222', '#333333'],
        },
      ],
    });
    expect(visibleGroups(parsed, false).map((g) => g.id)).toEqual(['tag']);
  });

  it('themeGloss liefert die Sprache — unbekannte Sprache fällt auf Deutsch zurück', () => {
    const kasumi = findTheme(MANIFEST, 'kasumi') as NonNullable<ReturnType<typeof findTheme>>;
    expect(themeGloss(kasumi, 'de')).toBe('Dunst');
    expect(themeGloss(kasumi, 'en')).toBe('haze');
    expect(themeGloss(kasumi, 'kli')).toBe('Dunst');
  });

  it('isKnownTheme: mit Manifest ein Riegel, OHNE Manifest bewusst durchlässig', () => {
    expect(isKnownTheme('kasumi', MANIFEST)).toBe(true);
    expect(isKnownTheme('gibtesnicht', MANIFEST)).toBe(false);
    // Kaltstart: die gespeicherte Wahl darf nicht sterben, nur weil die Datei
    // noch unterwegs ist — useSettings räumt später auf.
    expect(isKnownTheme('gibtesnicht', null)).toBe(true);
  });
});

describe('loadThemeManifest — genau einmal je Session, Fehlschlag nicht gecacht', () => {
  it('holt die Datei, parst sie und cacht sie (der zweite Aufruf geht nicht ins Netz)', async () => {
    // Argument bewusst mit-typisiert: der Test prüft unten die URL.
    const fetchMock = vi.fn(async (_url: string) => ({ ok: true, json: async () => MANIFEST_JSON }));
    vi.stubGlobal('fetch', fetchMock);

    const loaded = await loadThemeManifest();
    expect(loaded?.themes.length).toBe(MANIFEST.themes.length);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe(THEME_MANIFEST_URL);
    expect(cachedThemeManifest()).not.toBeNull();

    await loadThemeManifest();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('parallele Aufrufe teilen sich EINEN Roundtrip', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => MANIFEST_JSON }));
    vi.stubGlobal('fetch', fetchMock);
    await Promise.all([loadThemeManifest(), loadThemeManifest(), loadThemeManifest()]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('kein Netz / 404 ⇒ null + Warnung, und der nächste Versuch darf gelingen', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, status: 404 })
      .mockResolvedValueOnce({ ok: true, json: async () => MANIFEST_JSON });
    vi.stubGlobal('fetch', fetchMock);

    expect(await loadThemeManifest()).toBeNull();
    expect(warn).toHaveBeenCalled();
    expect(cachedThemeManifest()).toBeNull();
    expect(await loadThemeManifest()).not.toBeNull(); // Fehlschlag war nicht gecacht
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  3+4) useAppliedTheme — FOUC-Naht und Sora-Rotation
// ─────────────────────────────────────────────────────────────────────────────

describe('useAppliedTheme — erst laden, dann anschalten', () => {
  let host: HTMLDivElement;
  let root: Root;

  /** Mountet die Naht mit einem Thema; `rerender` wechselt es (= Rotation). */
  const Harness = ({ theme }: { theme: string }) => {
    useAppliedTheme(theme);
    return null;
  };

  const mount = async (theme: string) => {
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    await act(async () => {
      root.render(<Harness theme={theme} />);
    });
  };

  const rerender = async (theme: string) => {
    await act(async () => {
      root.render(<Harness theme={theme} />);
    });
  };

  afterEach(() => {
    if (root) act(() => root.unmount());
    host?.remove();
  });

  beforeEach(() => {
    primeThemeManifest(MANIFEST);
  });

  it('setzt data-theme ERST NACH dem load-Ereignis (kein halb angezogenes Thema)', async () => {
    await mount('kasumi');
    // Das <link> hängt, aber das Attribut steht noch NICHT — genau das ist die Zusage.
    expect(linkOf('kasumi')).not.toBeNull();
    expect(document.documentElement.dataset.theme).toBeUndefined();

    await act(async () => {
      fireLoad('kasumi');
    });
    expect(document.documentElement.dataset.theme).toBe('kasumi');
  });

  it('scheitert die Datei, bleibt der aktuelle Look stehen — nie eine weiße Seite', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    document.documentElement.dataset.theme = 'aoi'; // der Stand vorher
    await mount('amayadori');
    await act(async () => {
      fireError('amayadori');
    });
    expect(document.documentElement.dataset.theme).toBe('aoi'); // unverändert
  });

  it('kennt das Manifest die Id nicht, passiert gar nichts (Basis-Look bleibt)', async () => {
    await mount('gibtesnicht');
    expect(linkOf('gibtesnicht')).toBeNull();
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });

  it('ohne Manifest wird nichts angeschaltet (statt eine Farbwelt zu behaupten)', async () => {
    resetThemeCatalog();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 500 })));
    await mount('kasumi');
    expect(document.documentElement.dataset.theme).toBeUndefined();
  });

  it('ROTATION: der Tageswechsel lädt nach und schaltet erst danach um', async () => {
    // Sora löst sich in ein konkretes Theme auf (useSettings/useResolvedTheme);
    // dieser Wechsel kommt hier als neue `themeId` an — und nimmt exakt
    // denselben Weg wie eine Wahl von Hand.
    await mount('kasumi');
    await act(async () => {
      fireLoad('kasumi');
    });
    expect(document.documentElement.dataset.theme).toBe('kasumi');

    await rerender('yoru'); // 22:00 — das nächste Sora-Fenster
    expect(linkOf('yoru')).not.toBeNull();
    // Bis Yoru wirklich da ist, bleibt Kasumi stehen (kein Zwischenzustand).
    expect(document.documentElement.dataset.theme).toBe('kasumi');

    await act(async () => {
      fireLoad('yoru');
    });
    expect(document.documentElement.dataset.theme).toBe('yoru');
  });

  it('zurück auf ein SCHON geladenes Thema: kein zweites <link>, sofort umgeschaltet', async () => {
    await mount('kasumi');
    await act(async () => {
      fireLoad('kasumi');
    });
    await rerender('yoru');
    await act(async () => {
      fireLoad('yoru');
    });
    await rerender('kasumi');
    expect(document.querySelectorAll(`link[${THEME_LINK_ATTR}="kasumi"]`)).toHaveLength(1);
    expect(document.documentElement.dataset.theme).toBe('kasumi');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
//  5) Der nachträgliche Riegel — was der Kaltstart durchgelassen hat
// ─────────────────────────────────────────────────────────────────────────────

describe('useSettings — räumt eine unbekannte Theme-Id auf, sobald das Manifest da ist', () => {
  let host: HTMLDivElement;
  let root: Root;
  let store: Storage;
  let seen: string | undefined;

  /** In-Memory-Storage in DOM-`Storage`-Form (Idiom aus settings.test.ts). */
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

  const Harness = () => {
    seen = useSettings().theme;
    return null;
  };

  const mount = async () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    root = createRoot(host);
    await act(async () => {
      root.render(<Harness />);
    });
  };

  beforeEach(() => {
    seen = undefined;
    store = memoryStorage();
    vi.stubGlobal('localStorage', store);
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => MANIFEST_JSON })));
  });

  afterEach(() => {
    if (root) act(() => root.unmount());
    host?.remove();
  });

  it('ein Thema, das es nicht mehr gibt, fällt auf den Default — und wird so gespeichert', async () => {
    // Genau der Fall, den der Kaltstart bewusst durchlässt: `loadSettings` läuft
    // synchron, das Manifest kommt erst danach.
    store.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({ ...DEFAULT_SETTINGS, theme: 'weggezogen' }));
    await mount();
    expect(seen).toBe(DEFAULT_SETTINGS.theme);
    expect(JSON.parse(store.getItem(SETTINGS_STORAGE_KEY) as string).theme).toBe(
      DEFAULT_SETTINGS.theme,
    );
  });

  it('eine gültige Wahl bleibt unangetastet (auch eine der NEUEN Szenen)', async () => {
    store.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({ ...DEFAULT_SETTINGS, theme: 'momiji' }));
    await mount();
    expect(seen).toBe('momiji');
  });

  it('ist das Manifest NICHT ladbar, bleibt die Wahl stehen (kein Verlust wegen Netzfehler)', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 503 })));
    store.setItem(SETTINGS_STORAGE_KEY, JSON.stringify({ ...DEFAULT_SETTINGS, theme: 'momiji' }));
    await mount();
    expect(seen).toBe('momiji');
  });
});

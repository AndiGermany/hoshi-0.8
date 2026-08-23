/** @vitest-environment jsdom */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act } from 'react';
import { readFileSync } from 'node:fs';
import { createRoot, type Root } from 'react-dom/client';
import {
  HomeStage,
  HOME_AUTOPAGE_DWELL_MS,
  HOME_EDIT_IDLE_MS,
  type HomeStageTile,
} from '../components/HomeStage';
import {
  DEFAULT_HOME_LAYOUT,
  withHomeOrder,
  type HomeLayoutV1,
} from '../components/homeLayout';
import { HOME_LAYOUT_STORAGE_KEY, saveHomeLayout } from '../hooks/useHomeLayout';
import { SCENE_MOTION_ATTR, holdSceneMotion, resetSceneMotion } from '../styles/sceneMotion';
import type { HomeTileSize, HomeWidgetId } from '../components/homeWidgets';
import { CATALOGS, SUPPORTED_UI_LANGUAGES } from '../i18n';

/** Die deutschen Edit-Texte — aus dem Katalog gelesen, nie abgeschrieben. */
const EDIT_DE = CATALOGS['de'].idleFace.stage.edit;

(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;

/**
 * **homeeditmode.test** — die Scheibe W4: der Edit-Modus mit Hand
 * (`vault/tracks/DESIGN-widget-raster-2026-08-18.md` §4.2 + Andis
 * Kurs-Updates 19.08. + die Pflichtliste aus `.orch-bus/inbox/
 * 20260818-2140-codex-widget-raster-review.md` §5).
 *
 * **Warum diese Datei nicht `homeedit.test.tsx` heißt** (so nennt sie der
 * Auftrag): der Name war schon belegt — `test/homeedit.test.tsx` prüft seit
 * `cf22389` den RÄUME-Editor (`api/homeEdit.ts`, HA-Entity-Zuweisung). Zwei
 * völlig verschiedene Dinge unter einem Dateinamen wären teurer als ein
 * präziserer Name.
 *
 * jsdom rechnet kein CSS-Grid und kein Layout — `getBoundingClientRect` ist
 * gestubbt, der Stub IST die Messung (Idiom `homestage.test.tsx`/
 * `homesizer.test.tsx`). Deshalb sind die Platzierungs-Entscheidungen selbst
 * als REINE Funktionen geprüft (Teil 1), bevor irgendein DOM sie benutzt.
 */

/* ── Werkzeug ─────────────────────────────────────────────────────────────── */

const rect = (left: number, top: number, width: number, height: number): DOMRect =>
  ({
    x: left,
    y: top,
    width,
    height,
    top,
    left,
    right: left + width,
    bottom: top + height,
    toJSON: () => ({}),
  }) as DOMRect;

/**
 * Die Bühne: 900 × 300 an (0,0) ⇒ 3 Spalten × 2 Zeilen (6 Zellen je Seite).
 * Zellbreite (900 − 2·12)/3 = 292, Schritt 304; Zellhöhe (300 − 12)/2 = 144,
 * Schritt 156. Unter y 300 liegt nichts mehr — das Fach ist am 22.08.
 * gefallen (Andi: „unten ist auch eine leiste. die soll weg.“).
 */
const PAGE = rect(0, 0, 900, 300);
const COL_X = [100, 400, 700];
const ROW_Y = [50, 200];

const stubBoxes = () => {
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
    this: HTMLElement,
  ) {
    return PAGE;
  });
};

const tile = (id: string, size?: HomeTileSize): HomeStageTile => ({
  id,
  size,
  node: (effective) => (
    <article key={id} className="tile idle__tile" data-tile={id} data-size={effective}>
      <span data-body={id}>{id}</span>
    </article>
  ),
});

/** Eine Kachel voller Links — die Nachrichten-Kachel, an der Andi hängenblieb. */
const linkTile = (id: string, size?: HomeTileSize): HomeStageTile => ({
  id,
  size,
  node: (effective) => (
    <article key={id} className="tile idle__tile" data-tile={id} data-size={effective}>
      <a data-link={id} href="https://example.invalid">
        Schlagzeile
      </a>
    </article>
  ),
});

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

/** Sieben 1×1-Kacheln ⇒ 6 auf Seite 1, eine auf Seite 2. */
const ALL: HomeWidgetId[] = ['uhr', 'wetter', 'laeuft', 'einkauf', 'vacuum', 'climate', 'news'];
const allSmall = (): HomeLayoutV1 => ({
  version: 1,
  order: ALL.map((id) => ({ id, size: 'S' as HomeTileSize })),
});

/* ═══ 1 · Die reinen Verträge (kein DOM) ═══════════════════════════════════ */

describe('withHomeOrder — sichtbare umsortieren, unsichtbare stehen lassen', () => {
  const shape = (l: HomeLayoutV1) => l.order.map((e) => e.id);

  it('die sichtbaren Widgets nehmen die neue Reihenfolge ein', () => {
    // Sichtbar sind hier SIEBEN der acht — der Wecker (Platz 1 im Default) ist
    // ausgeschaltet und rührt sich darum nicht vom Fleck. Die sieben anderen
    // füllen die verbleibenden Plätze 0, 2, 3, 4, 5, 6, 7 in der neuen Folge.
    const out = withHomeOrder(DEFAULT_HOME_LAYOUT, ['wetter', 'uhr', 'laeuft', 'einkauf', 'vacuum', 'climate', 'news']);
    expect(shape(out)).toEqual(['wetter', 'wecker', 'uhr', 'laeuft', 'einkauf', 'vacuum', 'climate', 'news']);
  });

  it('ein Widget, das gerade NICHT sichtbar ist, behält seinen absoluten Platz', () => {
    // Sichtbar sind nur uhr/wetter/news (Plätze 0, 2, 7). Wird news nach vorn
    // gezogen, tauschen NUR diese drei Plätze — wecker/laeuft/einkauf/vacuum/
    // climate bleiben, wo sie waren. Der Wecker auf Platz 1 ist seit W6 der
    // schärfste Fall davon: er liegt MITTEN zwischen zwei getauschten Plätzen.
    const out = withHomeOrder(DEFAULT_HOME_LAYOUT, ['news', 'uhr', 'wetter']);
    expect(shape(out)).toEqual([
      'news',
      'wecker',
      'uhr',
      'laeuft',
      'einkauf',
      'vacuum',
      'climate',
      'wetter',
    ]);
  });

  it('die Stufe reist MIT dem Widget, nicht mit dem Platz', () => {
    const start: HomeLayoutV1 = {
      version: 1,
      order: [
        { id: 'uhr', size: 'S' },
        { id: 'wetter', size: 'XL' },
        { id: 'laeuft', size: 'L' },
        { id: 'einkauf', size: 'M' },
        { id: 'vacuum', size: 'L' },
        { id: 'climate', size: 'L' },
        { id: 'news', size: 'M' },
      ],
    };
    const out = withHomeOrder(start, ['wetter', 'uhr', 'laeuft', 'einkauf', 'vacuum', 'climate', 'news']);
    expect(out.order[0]).toEqual({ id: 'wetter', size: 'XL' });
    expect(out.order[1]).toEqual({ id: 'uhr', size: 'S' });
  });

  it('unbekannte und doppelte Ids in der neuen Reihenfolge werden ignoriert', () => {
    const out = withHomeOrder(DEFAULT_HOME_LAYOUT, [
      'jellyfin' as HomeWidgetId,
      'news',
      'news',
      'uhr',
    ]);
    expect(shape(out).length).toBe(8);
    expect(new Set(shape(out)).size).toBe(8);
  });

  it('dieselbe Reihenfolge nochmal ⇒ unverändert (idempotent)', () => {
    const once = withHomeOrder(DEFAULT_HOME_LAYOUT, ['news', 'uhr', 'wetter']);
    expect(withHomeOrder(once, once.order.map((e) => e.id))).toEqual(once);
  });
});

/* ═══ 2 · Der Edit-Modus am DOM ════════════════════════════════════════════ */

describe('HomeStage — der Edit-Modus (W4)', () => {
  let container: HTMLDivElement;
  let root: Root | null = null;

  const mount = async (tiles: HomeStageTile[]): Promise<void> => {
    root = createRoot(container);
    await act(async () => {
      root!.render(<HomeStage tiles={tiles} />);
    });
  };
  const tileEl = (id: string): HTMLElement =>
    container.querySelector(`[data-tile="${id}"]`) as HTMLElement;
  const stage = (): HTMLElement => container.querySelector('.idle__stage') as HTMLElement;
  const sizer = (): HTMLElement | null => container.querySelector('.idle__sizer');
  const tray = (): HTMLElement | null => container.querySelector('.idle__tray');
  /**
   * **Der DOM-Beweis, dass der Edit-Modus läuft.** Bis zum 23.08. war das die
   * Leiste oben; sie ist gefallen (Andi: „nimm die UI oben, wenn man etwas
   * bearbeitet raus"). Was den Modus jetzt im Baum markiert, ist die
   * unsichtbare Gruppe, die die zwei Dinge trägt, die null Pixel kosten und
   * ohne die der Modus für Screenreader stumm wäre: Tastenbelegung + Ansage.
   */
  const editAn = (): HTMLElement | null => container.querySelector('.idle__editsr');
  const announced = (): string =>
    container.querySelector('.idle__editsr [role="status"]')?.textContent ?? '';
  const order = (): string[] =>
    Array.from(container.querySelectorAll('[data-tile]')).map((el) => el.getAttribute('data-tile') ?? '');
  const stored = (): string[] =>
    JSON.parse(localStorage.getItem(HOME_LAYOUT_STORAGE_KEY) ?? '{"order":[]}').order.map(
      (e: { id: string }) => e.id,
    );

  const fire = async (
    target: EventTarget,
    type: string,
    x: number,
    y: number,
    pointerId = 1,
    /** Ohne Angabe ist es eine MAUS — genau wie die Bühne ein Ereignis ohne
     *  `pointerType` behandelt (positive Liste, s. `onPointerDown`). */
    pointerType = 'mouse',
  ) => {
    const evt = new MouseEvent(type, { bubbles: true, cancelable: true, clientX: x, clientY: y });
    Object.defineProperty(evt, 'pointerId', { value: pointerId, configurable: true });
    Object.defineProperty(evt, 'pointerType', { value: pointerType, configurable: true });
    await act(async () => {
      target.dispatchEvent(evt);
    });
  };
  const key = async (target: EventTarget, k: string) => {
    await act(async () => {
      target.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
    });
  };
  const tick = async (ms: number) => {
    await act(async () => {
      vi.advanceTimersByTime(ms);
    });
  };
  /** Ein Bild weiter — der rAF-Sammler soll laufen. */
  const frame = async () => {
    await tick(20);
  };
  /** Bringt die Bühne in den Edit-Modus (langer Druck auf eine Kachel). */
  const enterEdit = async (id: string) => {
    await fire(tileEl(id), 'pointerdown', 10, 10);
    await tick(600);
    await fire(stage(), 'pointerup', 10, 10);
  };

  beforeEach(() => {
    vi.stubGlobal('localStorage', memoryStorage());
    vi.useFakeTimers();
    resetSceneMotion();
    container = document.createElement('div');
    document.body.appendChild(container);
    stubBoxes();
    saveHomeLayout(allSmall());
    // Sauger und Klima sind im BESTAND default AUS (Andi schaltet sie bewusst
    // an). Für diese Sonden liegen alle sieben Widgets auf der Bühne, sonst
    // fehlten zwei Kacheln, die mit dem Prüfling nichts zu tun haben.
    localStorage.setItem('hoshi.homeTiles.vacuum', 'true');
    localStorage.setItem('hoshi.homeTiles.climate', 'true');
  });
  afterEach(async () => {
    if (root) {
      const r = root;
      await act(async () => r.unmount());
      root = null;
    }
    container.remove();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  const sevenTiles = () => ALL.map((id) => tile(id, 'S'));

  /* ── Einstieg und Ausstieg ─────────────────────────────────────────────── */

  it('ein langer Druck lässt ALLE Kacheln wackeln — nicht nur die gedrückte', async () => {
    await mount(sevenTiles());
    expect(container.querySelectorAll('[data-edit="true"][data-tile]')).toHaveLength(0);
    await enterEdit('uhr');
    // Alle Kacheln der aktiven Seite tragen die Edit-Markierung …
    expect(container.querySelectorAll('[data-tile][data-edit="true"]').length).toBeGreaterThan(1);
    // … und der Wähler der gedrückten steht sofort (ein Druck, nicht zwei).
    expect(sizer()).not.toBeNull();
    expect(editAn()).not.toBeNull();
  });

  it('das Wackeln ist VERSETZT — zwei Kacheln laufen nie im Gleichschritt', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    const delays = Array.from(container.querySelectorAll<HTMLElement>('[data-tile][data-edit="true"]')).map(
      (el) => el.style.animationDelay,
    );
    expect(delays.length).toBeGreaterThan(2);
    expect(new Set(delays).size).toBe(delays.length);
  });

  /**
   * **Der Edit-Modus trägt gar keine eigene Bedienung mehr** (Andi 23.08.:
   * „nimm die UI oben, wenn man etwas bearbeitet raus, dann passt es für
   * mich"). Weder Leiste noch „Fertig"-Knopf — und weil ein Modus ohne Knopf
   * nur so gut ist wie seine Ausgänge, prüfen die drei Tests darunter jeden
   * einzeln (Tipp auf die Kachel · Tipp ins Leere · Escape).
   */
  it('im Edit steht KEINE Leiste und kein Knopf auf der Bühne — nur Kacheln', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    expect(editAn(), 'der Edit-Modus läuft gar nicht').not.toBeNull();
    for (const weg of ['.idle__editbar', '.idle__editdone', '.idle__editreset', '.idle__editlayer']) {
      expect(container.querySelector(weg), `${weg} ist zurück`).toBeNull();
    }
    // Knöpfe auf der Bühne gibt es weiterhin — aber nur die des Stufen-Wählers
    // und die Seitenpunkte. Nichts davon beendet den Modus.
    const knoepfe = Array.from(container.querySelectorAll('.idle__stage button')).map((b) =>
      b.className.trim().split(/\s+/)[0],
    );
    expect(new Set(knoepfe)).toEqual(new Set(['idle__sizerbtn', 'idle__dot']));
  });

  /* ── Die Szene hält an, solange man anordnet (Andi 23.08.) ───────────────
   *
   * Andi wörtlich: „ich finde das kirschblüten im fluss design unfassbar
   * schön, aber es laggt leider, besonders, wenn ich das design ausgewählt
   * habe und die widgets anpasse." Gemessen (Firefox, 1600×1000,
   * `tools/theme-contrast/szene-perf.mjs`): 14,8 fps bei 465 % CPU im Edit,
   * angehalten 60,0 fps bei 38 %.
   *
   * Geprüft wird hier NUR der Schalter — was ein Thema daraus macht, steht in
   * `styles/themes.css` und in den Themen-Dateien. Die Bühne kennt keine
   * Themen, und dieser Test soll sie auch nicht welche lehren. */
  it('der Edit-Modus hält die Szene an — und lässt sie beim Verlassen weiterlaufen', async () => {
    await mount(sevenTiles());
    expect(document.documentElement.getAttribute(SCENE_MOTION_ATTR)).toBeNull();
    await enterEdit('uhr');
    expect(document.documentElement.getAttribute(SCENE_MOTION_ATTR)).toBe('still');
    // Ausgang per Escape — der „Fertig"-Knopf fiel mit der Edit-Leiste
    // (Homescreen-Finale 23.08.). Escape ist zweistufig: erst Abwahl des
    // gewählten Widgets, dann Verlassen — darum zwei Tastendrücke.
    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    await act(async () => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(document.documentElement.getAttribute(SCENE_MOTION_ATTR)).toBeNull();
  });

  it('eine Bühne, die im Edit-Modus verschwindet, lässt die Szene nicht angehalten zurück', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    expect(document.documentElement.getAttribute(SCENE_MOTION_ATTR)).toBe('still');
    const r = root!;
    root = null;
    await act(async () => r.unmount());
    expect(document.documentElement.getAttribute(SCENE_MOTION_ATTR)).toBeNull();
  });

  it('Escape schließt erst den Wähler, dann den Edit-Modus — nicht beides auf einmal', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    expect(sizer()).not.toBeNull();
    await key(window, 'Escape');
    expect(sizer()).toBeNull();
    expect(editAn()).not.toBeNull();
    await key(window, 'Escape');
    expect(editAn()).toBeNull();
  });

  /* ── W6: die zwei neuen Ausstiege (Andi 20.08.) ───────────────────────────
   *
   * Andi wörtlich: „Um die Einstellung wieder zu verlassen, muss man nochmal
   * mit der Maus auf dem Widget geklickt haben oder durch einen freien Klick
   * irgendwohin." Beide Wege sind Pflicht — und beide müssen den Stufen-Zugang
   * überleben, der seit W4 an genau derselben Geste hängt.
   */

  it('W6: ein Tipp auf eine ANDERE Kachel wählt sie aus — der Stufen-Zugang stirbt nicht', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    expect(sizer()?.getAttribute('aria-label')).toContain('Uhr');
    // Der erste Tipp auf eine fremde Kachel wählt sie — wie seit W4.
    await fire(tileEl('wetter'), 'pointerdown', COL_X[1], ROW_Y[0]);
    await fire(tileEl('wetter'), 'pointerup', COL_X[1], ROW_Y[0]);
    expect(editAn()).not.toBeNull();
    expect(sizer()?.getAttribute('aria-label')).toContain('Wetter');
  });

  it('W6: der zweite Tipp auf DIESELBE Kachel verlässt den Edit-Modus', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    expect(sizer()?.getAttribute('aria-label')).toContain('Uhr');
    await fire(tileEl('uhr'), 'pointerdown', COL_X[0], ROW_Y[0]);
    await fire(tileEl('uhr'), 'pointerup', COL_X[0], ROW_Y[0]);
    expect(editAn()).toBeNull();
    expect(sizer()).toBeNull();
    expect(container.querySelectorAll('[data-tile][data-edit="true"]')).toHaveLength(0);
  });

  it('W6: der Ausstieg erkennt „dieselbe Kachel" AUCH, weil pointerdown den Wähler schon zumacht', async () => {
    // Der Fallstrick, an dem diese Geste ohne `openAtDown` gescheitert wäre:
    // `onPointerDown` schließt den offenen Wähler (damit ein Druck daneben ihn
    // wegnimmt), React rendert dazwischen neu — `onPointerUp` läse aus seiner
    // Closure dann immer `null` und erkennte den zweiten Tipp nie. Der Test
    // hält fest, dass zwischen Druck und Loslassen wirklich ein Render liegt
    // und der Ausstieg ihn überlebt.
    await mount(sevenTiles());
    await enterEdit('uhr');
    await fire(tileEl('uhr'), 'pointerdown', COL_X[0], ROW_Y[0]);
    expect(sizer()).toBeNull(); // schon zu — und trotzdem …
    await fire(tileEl('uhr'), 'pointerup', COL_X[0], ROW_Y[0]);
    expect(editAn()).toBeNull(); // … endet der Modus.
  });

  it('W6: ein freier Klick auf die leere Bühnenfläche verlässt den Edit-Modus', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    // Die Hülle selbst ist keine Kachel: derselbe Weg, den ein Finger neben
    // den Kacheln nimmt.
    await fire(stage(), 'pointerdown', 5, 5, 3);
    await fire(stage(), 'pointerup', 5, 5, 3);
    expect(editAn()).toBeNull();
  });

  it('W6: ein Druck AUF die Bedienung ist kein freier Klick — der Stufen-Wähler überlebt ihn', async () => {
    // Ohne diesen Riegel schlösse der erste Druck auf „+"/„−" den Modus, in
    // dem der Knopf steht: der Druck käme nie beim Knopf an.
    // (Bis zum 23.08. stand hier „Zurücksetzen" aus der Edit-Leiste. Die
    // Leiste ist weg, der Riegel gilt unverändert — nur trägt ihn jetzt die
    // einzige Bedienung, die es auf der Bühne noch gibt.)
    await mount(sevenTiles());
    await enterEdit('uhr');
    const knopf = container.querySelector('.idle__sizerbtn') as HTMLElement;
    await fire(knopf, 'pointerdown', 5, 5, 4);
    await fire(knopf, 'pointerup', 5, 5, 4);
    expect(editAn()).not.toBeNull();
    expect(sizer(), 'der Wähler hat seinen eigenen Druck nicht überlebt').not.toBeNull();
  });

  it('W6: ein Druck AUSSERHALB der Bühne (Kopf, Orb, Nav …) verlässt den Modus ebenfalls', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    await act(async () => {
      outside.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true, cancelable: true }));
    });
    expect(editAn()).toBeNull();
    outside.remove();
  });

  it('W6: der dritte Ausgang — nach der Ruhezeit geht der Modus von selbst zu', async () => {
    // Ein Wandbildschirm im Flur darf nicht wackeln, bis zufällig wieder
    // jemand vorbeikommt. Beide bestellten Ausgänge setzen Anwesenheit voraus;
    // dieser ist für die Person, die weggerufen wurde.
    await mount(sevenTiles());
    await enterEdit('uhr');
    await tick(HOME_EDIT_IDLE_MS - 1000);
    expect(editAn(), 'kurz vor der Frist muss der Modus noch stehen').not.toBeNull();
    await tick(1000);
    expect(editAn()).toBeNull();
  });

  it('W6: jede echte Eingabe stellt die Ruhe-Uhr zurück — die Frist gilt ab der LETZTEN', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    // Kurz vor Ablauf bewegt sich der Zeiger: das ist Anwesenheit.
    await tick(HOME_EDIT_IDLE_MS - 1000);
    await fire(stage(), 'pointermove', 40, 40, 9);
    // Ohne Rückstellung wäre der Modus jetzt zu. Er ist es nicht.
    await tick(HOME_EDIT_IDLE_MS - 1000);
    expect(editAn(), 'die Bewegung hat die Uhr nicht zurückgestellt').not.toBeNull();
    // Und ab der letzten Eingabe läuft die volle Frist erneut.
    await tick(1000);
    expect(editAn()).toBeNull();
  });

  it('W6: eine LAUFENDE Geste wird nie abgeschnitten — Ziehen ist Anwesenheit', async () => {
    // Wer eine Kachel gerade über die Bühne schiebt, ist da — auch wenn er
    // dafür länger braucht als die Frist. Der Riegel dagegen ist `gestureRef`:
    // läuft eine Geste, wird die Uhr neu gestellt statt geschlossen.
    await mount(sevenTiles());
    await enterEdit('uhr');
    await fire(tileEl('uhr'), 'pointerdown', 10, 10, 7); // Finger liegt auf
    await tick(HOME_EDIT_IDLE_MS + 1000);
    expect(editAn(), 'der Modus hat einem ruhenden Finger den Boden weggezogen').not.toBeNull();
    await fire(stage(), 'pointerup', 10, 10, 7); // Finger geht hoch
  });

  /**
   * **Andi 22.08. nachts:** *„Entferne bitte die hinweise beim bearbeiten der
   * widgets."* — und 23.08.: *„nimm die UI oben … raus"*.
   *
   * W4 zeigte die ganze Tastatur-Belegung, W6 nahm sie heraus (sie brach um
   * und kostete Kachelzeilen), W7 ließ eine dezente Zeile links stehen, am
   * 22.08. fiel auch die, am 23.08. die ganze Leiste. Was bleibt, ist
   * unsichtbar — und muss es bleiben: die Belegung ist die einzige Stelle, an
   * der Pfeiltasten und Bild ↑/↓ überhaupt angeboten werden.
   */
  it('der Edit-Modus zeigt gar nichts mehr — die Tastenbelegung lebt nur fürs Ohr weiter', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    const gruppe = editAn() as HTMLElement;
    // Die HÜLLE selbst trägt die sr-only-Regel — nicht nur ihre Kinder. Sie
    // ist ein Flex-Kind der Bühne; stünde sie im Fluss, kostete sie trotz 0 px
    // Höhe die 10-px-Fuge darüber und verschöbe damit jede Kachel.
    expect(readFileSync('src/index.css', 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')).toMatch(
      /\.idle__sronly,\s*\n\.idle__editsr,\s*\n\.idle__sizerstep\s*\{/,
    );
    const kinder = Array.from(gruppe.children);
    expect(kinder.length).toBeGreaterThan(0);
    // Die Belegung ist nicht verwaist mitgefallen …
    expect(kinder.map((el) => el.textContent)).toContain(EDIT_DE.keyHint);
    // … und die Gruppe trägt den Namen, unter dem man hier hineinkommt.
    expect(gruppe.getAttribute('aria-label')).toBe(EDIT_DE.title);
    // Und sie verspricht nichts mehr, was es nicht mehr gibt: „Entf" ist mit
    // dem Fach gefallen.
    expect(EDIT_DE.keyHint).not.toMatch(/Entf/);
  });

  it('W6: die TASTATUR behält ihre Zweistufigkeit — Eingabe fällt nicht aus dem Modus', async () => {
    // Wer mit Tab durch die Kacheln geht, will beim Bestätigen nicht aus dem
    // Modus fallen: dort ist der Weg zurück (Escape) nie verstellt, also
    // braucht die Tastatur den kurzen Ausstieg des Fingers nicht.
    await mount(sevenTiles());
    await enterEdit('uhr');
    await key(tileEl('uhr'), 'Enter'); // Wähler zu …
    expect(sizer()).toBeNull();
    expect(editAn()).not.toBeNull(); // … Modus bleibt.
    await key(tileEl('uhr'), 'Enter'); // und wieder auf
    expect(sizer()).not.toBeNull();
  });

  it('ein REITERWECHSEL beendet den Edit-Modus (die Ansicht mountet neu, App.tsx `key={tab}`)', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    expect(editAn()).not.toBeNull();
    // Genau das, was ein Reiterwechsel tut: unmount + frisch mounten.
    await act(async () => root!.unmount());
    root = null;
    await mount(sevenTiles());
    expect(editAn()).toBeNull();
    expect(container.querySelectorAll('[data-tile][data-edit="true"]')).toHaveLength(0);
  });

  /**
   * **„Zurücksetzen" ist am 23.08. in die Einstellungen gezogen** — zu den
   * Widget-Schaltern, wo alles Verwalterische wohnt. Der Knopf, seine
   * Rückfrage und die Zusage „die Schalter bleiben unangetastet" werden dort
   * geprüft, wo er jetzt steht: `hometilessettings.test.tsx`, Suite
   * „HomeTilesSection — Layout zurücksetzen".
   *
   * Was HIER bleibt, ist die Gegenprobe: die Bühne setzt nichts mehr zurück.
   * Ein Knopf, den es nicht mehr gibt, kann keinen Test bestehen — ein
   * versehentlich wieder eingebauter dagegen schon, und genau den fängt das
   * hier ab.
   */
  it('die Bühne setzt nichts mehr zurück — der Weg dorthin liegt in den Einstellungen', async () => {
    saveHomeLayout(withHomeOrder(allSmall(), ['news', 'uhr', 'wetter', 'laeuft', 'einkauf', 'vacuum', 'climate']));
    await mount(sevenTiles());
    await enterEdit('uhr');
    // Kein Knopf auf der Bühne trägt das Wort — weder sichtbar noch als Label.
    const beschriftungen = Array.from(container.querySelectorAll('.idle__stage button')).map(
      (b) => `${b.textContent ?? ''} ${b.getAttribute('aria-label') ?? ''}`,
    );
    for (const text of beschriftungen) expect(text).not.toMatch(/[Zz]urücksetzen/);
    // Und Andis eigene Reihenfolge steht unverändert: nichts hat sie angefasst.
    const layoutNow = (): HomeLayoutV1 =>
      JSON.parse(localStorage.getItem(HOME_LAYOUT_STORAGE_KEY) as string) as HomeLayoutV1;
    expect(layoutNow().order.map((e) => e.id)[0]).toBe('news');
    expect(layoutNow().order.map((e) => e.id)).not.toEqual(DEFAULT_HOME_LAYOUT.order.map((e) => e.id));
  });

  /* ── Andis News-Befund (19.08.) ────────────────────────────────────────── */

  it('ANDIS BEFUND: die Kachel voller Links startet KEINEN Edit — mit der MAUS bleibt der Riegel', async () => {
    await mount([linkTile('news', 'S'), tile('uhr', 'S')]);
    await fire(container.querySelector('[data-link]') as HTMLElement, 'pointerdown', 10, 10);
    await tick(700);
    expect(editAn()).toBeNull();
  });

  /* ── Querbefund des Resize-Pods (22.08.), auf Glas nachgemessen ──────────
   *
   * Der Riegel oben ist mit der Maus richtig: wer die Nachrichten-Kachel
   * verstellen will, hat dort den Rechtsklick als zweiten Weg. Auf einem iPad
   * gibt es keinen Rechtsklick — und die Kachel besteht fast nur aus Links.
   * Der Riegel hieß dort: KEIN Punkt dieser Kachel führt in den Edit-Modus.
   * Gemessen mit `tools/zuhause-probe/touch.mjs` (Schritt 6) auf der echten
   * Kachel: 900 ms Druck auf einer Schlagzeile ⇒ kein Edit, stattdessen ein
   * Link-Klick.
   *
   * Beide Hälften müssen stimmen, sonst tauscht man einen Fehler gegen einen
   * anderen: der kurze Tipp bleibt der Link, der lange Druck wird zum Edit.
   */
  it('AUF GLAS: ein langer Druck auf der Schlagzeile führt in den Edit-Modus', async () => {
    await mount([linkTile('news', 'S'), tile('uhr', 'S')]);
    await fire(container.querySelector('[data-link]') as HTMLElement, 'pointerdown', 10, 10, 1, 'touch');
    await tick(700);
    expect(editAn()).not.toBeNull();
    // Und zwar für DIESE Kachel — der Wähler steht, wo der Finger lag.
    expect(sizer()?.getAttribute('aria-label')).toContain('Nachrichten');
  });

  it('AUF GLAS: der KURZE Tipp bleibt der Link — kein Edit, kein geschlucktes Klicken', async () => {
    await mount([linkTile('news', 'S'), tile('uhr', 'S')]);
    const link = container.querySelector('[data-link]') as HTMLElement;
    await fire(link, 'pointerdown', 10, 10, 1, 'touch');
    await tick(120); // deutlich unter HOME_LONG_PRESS_MS
    await fire(link, 'pointerup', 10, 10, 1, 'touch');
    expect(editAn()).toBeNull();

    // `detail: 1` — ein Klick aus einer echten Zeigergeste, so wie ein Browser
    // ihn nach dem Loslassen schickt (s. `onClickCapture`).
    const click = new MouseEvent('click', { bubbles: true, cancelable: true, detail: 1 });
    await act(async () => {
      link.dispatchEvent(click);
    });
    expect(click.defaultPrevented).toBe(false);
  });

  it('AUF GLAS: nach dem langen Druck wird der nachlaufende Link-Klick GESCHLUCKT', async () => {
    await mount([linkTile('news', 'S'), tile('uhr', 'S')]);
    const link = container.querySelector('[data-link]') as HTMLElement;
    await fire(link, 'pointerdown', 10, 10, 1, 'touch');
    await tick(700);
    await fire(link, 'pointerup', 10, 10, 1, 'touch');
    const click = new MouseEvent('click', { bubbles: true, cancelable: true, detail: 1 });
    await act(async () => {
      link.dispatchEvent(click);
    });
    // Ohne das öffnete derselbe Druck zusätzlich die Schlagzeile im Browser.
    expect(click.defaultPrevented).toBe(true);
  });

  /* ── Nachtrag 2 der Hand (22.08. spät) ──────────────────────────────────
   *
   * Der nachlaufende Klick landet auf Glas NICHT dort, wo der Finger lag: der
   * lange Druck macht Bedienung auf, die sich über genau diese Stelle schiebt,
   * und der synthetische Klick trifft **sie**. Gemessen auf der echten
   * Nachrichten-Kachel (`touch.mjs` Schritt 6):
   * `pointerdown@.idle__newstitle → … → click@.idle__editbar`.
   *
   * Die frühere Ausnahme („Leiste/Wähler nie schlucken") winkte ihn durch —
   * ein Druck, den niemand gemeint hat, auf einem Knopf, den es eine
   * Zehntelsekunde vorher noch nicht gab. Die Leiste ist am 23.08. gefallen;
   * der Stufen-Wähler geht bei jedem Long-Press genauso auf, also gilt die
   * Lehre unverändert — sie wird hier nur an ihm gemessen.
   */
  it('AUF GLAS: der nachlaufende Klick wird auch auf dem frisch geöffneten WÄHLER geschluckt', async () => {
    await mount([linkTile('news', 'S'), tile('uhr', 'S')]);
    await fire(container.querySelector('[data-link]') as HTMLElement, 'pointerdown', 10, 10, 1, 'touch');
    await tick(700);
    await fire(container.querySelector('[data-link]') as HTMLElement, 'pointerup', 10, 10, 1, 'touch');
    const knopf = container.querySelector('.idle__sizerbtn') as HTMLElement;
    expect(knopf).not.toBeNull();
    const click = new MouseEvent('click', { bubbles: true, cancelable: true, detail: 1 });
    await act(async () => {
      knopf.dispatchEvent(click);
    });
    expect(click.defaultPrevented).toBe(true);
    // Und der Modus steht noch — nichts hat sich selbst gedrückt.
    expect(editAn()).not.toBeNull();
  });

  it('AUF GLAS: der TASTATUR-Druck auf einen Stufen-Knopf wird nie geschluckt (detail 0)', async () => {
    await mount([linkTile('news', 'S'), tile('uhr', 'S')]);
    await fire(container.querySelector('[data-link]') as HTMLElement, 'pointerdown', 10, 10, 1, 'touch');
    await tick(700);
    await fire(container.querySelector('[data-link]') as HTMLElement, 'pointerup', 10, 10, 1, 'touch');
    const knopf = container.querySelector('.idle__sizerbtn') as HTMLElement;
    // Eingabe/Leertaste und VoiceOver liefern `detail: 0` — eine ABSICHT, kein
    // Nachlauf. Sie darf das Veto nie treffen, sonst ist der Modus für die
    // Tastatur eine Falle.
    const click = new MouseEvent('click', { bubbles: true, cancelable: true, detail: 0 });
    await act(async () => {
      knopf.dispatchEvent(click);
    });
    expect(click.defaultPrevented).toBe(false);
  });

  it('ANDIS LÖSUNG: im Edit sind ALLE Kachel-Kinder inert — auch die Links', async () => {
    await mount([linkTile('news', 'S'), tile('uhr', 'S')]);
    await enterEdit('uhr');
    expect((container.querySelector('[data-link]') as HTMLElement).hasAttribute('inert')).toBe(true);
    expect((container.querySelector('[data-body="uhr"]') as HTMLElement).hasAttribute('inert')).toBe(true);
  });

  it('ANDIS LÖSUNG: ein Tipp AUF DEN LINK öffnet jetzt den Größen-Wähler der News-Kachel', async () => {
    await mount([linkTile('news', 'S'), tile('uhr', 'S')]);
    await enterEdit('uhr');
    await key(window, 'Escape'); // den Uhr-Wähler zu, sonst prüfen wir den falschen
    const link = container.querySelector('[data-link]') as HTMLElement;
    await fire(link, 'pointerdown', 400, 50);
    await fire(link, 'pointerup', 400, 50);
    expect(sizer()?.getAttribute('aria-label')).toContain('Nachrichten');
  });

  it('der Edit endet ⇒ die Kinder sind wieder bedienbar (kein zurückgelassenes inert)', async () => {
    await mount([linkTile('news', 'S'), tile('uhr', 'S')]);
    await enterEdit('uhr');
    // Seit dem 23.08. gibt es keinen „Fertig"-Knopf mehr, der den Modus
    // schließen könnte — Escape ist der Ausgang, der überall funktioniert.
    await key(window, 'Escape'); // schließt zuerst den Wähler …
    await key(window, 'Escape'); // … dann den Modus
    expect(editAn()).toBeNull();
    expect((container.querySelector('[data-link]') as HTMLElement).hasAttribute('inert')).toBe(false);
  });

  /* ── Ziehen ────────────────────────────────────────────────────────────── */

  it('ein Zug ordnet LIVE um und schreibt beim Loslassen genau einmal', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    expect(order().slice(0, 3)).toEqual(['uhr', 'wetter', 'laeuft']);
    await fire(tileEl('uhr'), 'pointerdown', COL_X[0], ROW_Y[0]);
    await fire(stage(), 'pointermove', COL_X[2], ROW_Y[0]);
    await frame();
    /* **W7 änderte die ANTWORT, nicht die Frage** (Andi 21.08.: „Das soll
       nicht automatisch bündig werden, sondern nur, wenn ich es verschiebe.").
       Bis W6 hieß „Uhr auf den Platz von Läuft ziehen": einfügen und ALLE
       dazwischen nachschieben ⇒ `wetter, laeuft, uhr`. Genau dieses
       Nachschieben ist abbestellt. Jetzt tauschen die zwei beteiligten
       Kacheln ihre Zellen und sonst rührt sich niemand ⇒ `laeuft` steht auf
       Platz 1 (dem der Uhr), `wetter` bleibt unberührt in der Mitte. */
    expect(order().slice(0, 3)).toEqual(['laeuft', 'wetter', 'uhr']);
    expect(stored().slice(0, 3)).toEqual(['uhr', 'wetter', 'laeuft']);
    await fire(stage(), 'pointerup', COL_X[2], ROW_Y[0]);
    expect(stored().slice(0, 3)).toEqual(['laeuft', 'wetter', 'uhr']);
  });

  it('die gezogene Kachel bewegt sich per transform — nichts anderes', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    await fire(tileEl('uhr'), 'pointerdown', COL_X[0], ROW_Y[0]);
    await fire(stage(), 'pointermove', COL_X[0] + 40, ROW_Y[0] + 25);
    await frame();
    const dragged = container.querySelector('[data-tile="uhr"]') as HTMLElement;
    expect(dragged.getAttribute('data-dragging')).toBe('true');
    expect(dragged.style.transform).toContain('translate3d(40px, 25px, 0)');
    expect(dragged.style.left).toBe('');
    expect(dragged.style.width).toBe('');
  });

  it('POINTER-CANCEL mitten im Zug schreibt NICHTS — die Kachel springt zurück', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    await fire(tileEl('uhr'), 'pointerdown', COL_X[0], ROW_Y[0]);
    await fire(stage(), 'pointermove', COL_X[2], ROW_Y[0]);
    await frame();
    expect(order()[2]).toBe('uhr');
    await fire(stage(), 'pointercancel', COL_X[2], ROW_Y[0]);
    expect(order().slice(0, 3)).toEqual(['uhr', 'wetter', 'laeuft']);
    expect(stored().slice(0, 3)).toEqual(['uhr', 'wetter', 'laeuft']);
  });

  it('eine VERLORENE Capture (System-/Scrollübernahme) bricht den Zug genauso ab', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    await fire(tileEl('uhr'), 'pointerdown', COL_X[0], ROW_Y[0]);
    await fire(stage(), 'pointermove', COL_X[2], ROW_Y[0]);
    await frame();
    await fire(stage(), 'lostpointercapture', COL_X[2], ROW_Y[0]);
    expect(stored().slice(0, 3)).toEqual(['uhr', 'wetter', 'laeuft']);
  });

  it('verschwindet die gezogene Kachel MITTEN im Zug, stürzt nichts ab', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    await fire(tileEl('uhr'), 'pointerdown', COL_X[0], ROW_Y[0]);
    await fire(stage(), 'pointermove', COL_X[1], ROW_Y[0]);
    await frame();
    // Die Quelle wird still (Verdien-Regel §1.3) — der Aufrufer liefert sie nicht mehr.
    await act(async () => {
      root!.render(<HomeStage tiles={ALL.filter((id) => id !== 'uhr').map((id) => tile(id, 'S'))} />);
    });
    await fire(stage(), 'pointermove', COL_X[2], ROW_Y[0]);
    await frame();
    await fire(stage(), 'pointerup', COL_X[2], ROW_Y[0]);
    expect(order()).not.toContain('uhr');
    // Die Reihenfolge der übrigen ist unbeschädigt — acht seit W6 (der Wecker
    // ist das achte Bühnen-Widget), auch wenn nur sieben sichtbar gemountet
    // sind: aus dem SPEICHER verschwindet beim Ziehen nie eines.
    expect(stored()).toHaveLength(8);
  });

  it('ein Zug quer über die Bühne kostet EIN Bild pro Frame, nicht eines pro Ereignis', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    const raf = vi.spyOn(window, 'requestAnimationFrame');
    await fire(tileEl('uhr'), 'pointerdown', COL_X[0], ROW_Y[0]);
    for (let i = 0; i < 20; i += 1) {
      await fire(stage(), 'pointermove', COL_X[0] + i, ROW_Y[0] + i);
    }
    // Zwanzig Ereignisse, EIN offener Frame (der Zug-Start plant ihn, die
    // übrigen 19 legen nur ihre Position ab) — Codex §5.
    expect(raf.mock.calls.length).toBeLessThanOrEqual(2);
  });

  /* ── Auto-Paging ───────────────────────────────────────────────────────── */

  it('AUTO-PAGING: 500 ms am rechten Rand blättern die Bühne weiter', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    const activePage = () =>
      Array.from(container.querySelectorAll('.idle__page')).findIndex(
        (p) => p.getAttribute('data-active') === 'true',
      );
    expect(activePage()).toBe(0);
    await fire(tileEl('uhr'), 'pointerdown', COL_X[0], ROW_Y[0]);
    await fire(stage(), 'pointermove', 890, ROW_Y[0]);
    await frame();
    expect(activePage()).toBe(0);
    await tick(HOME_AUTOPAGE_DWELL_MS);
    expect(activePage()).toBe(1);
  });

  it('AUTO-PAGING-ABBRUCH: verlässt der Finger die Randzone, blättert nichts', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    const activePage = () =>
      Array.from(container.querySelectorAll('.idle__page')).findIndex(
        (p) => p.getAttribute('data-active') === 'true',
      );
    await fire(tileEl('uhr'), 'pointerdown', COL_X[0], ROW_Y[0]);
    await fire(stage(), 'pointermove', 890, ROW_Y[0]);
    await frame();
    await tick(HOME_AUTOPAGE_DWELL_MS - 100);
    await fire(stage(), 'pointermove', COL_X[1], ROW_Y[0]); // zurück in die Mitte
    await frame();
    await tick(HOME_AUTOPAGE_DWELL_MS * 2);
    expect(activePage()).toBe(0);
  });

  /* ── Das Fach „Verfügbar" ist gefallen (Andi 22.08. nachts) ────────────── */

  /**
   * Andi wörtlich: *„unten ist auch eine leiste. die soll weg. das aktivieren
   * oder deaktivieren der verschiedenen widgets soll über die einstellungen
   * passieren."*
   *
   * Der Riegel prüft beide Hälften: unten steht nichts mehr, und die Bühne
   * schaltet auch auf keinem anderen Weg mehr ein Widget aus.
   */
  it('unter der Bühne liegt nichts mehr — und ein Zug nach unten schaltet kein Widget aus', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    expect(tray()).toBeNull();
    expect(container.querySelector('[data-tray]')).toBeNull();
    // Der Zug, der die Kachel früher ins Fach legte, lässt den Schalter jetzt
    // in Ruhe: unter der Bühne ist Bühne.
    await fire(tileEl('news'), 'pointerdown', COL_X[2], ROW_Y[1]);
    await fire(stage(), 'pointermove', 400, 350);
    await frame();
    await fire(stage(), 'pointerup', 400, 350);
    expect(localStorage.getItem('hoshi.homeTiles.currentAffairs')).not.toBe('false');
  });

  /* ── Tastatur + A11y (Codex §5) ────────────────────────────────────────── */

  it('die Kacheln sind im Edit fokussierbare Bedienelemente mit Namen und Rolle', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    const el = tileEl('wetter');
    expect(el.getAttribute('tabindex')).toBe('0');
    expect(el.getAttribute('role')).toBe('button');
    expect(el.getAttribute('aria-roledescription')).toBeTruthy();
    expect(el.getAttribute('aria-label')).toMatch(/Wetter.*2.*7/);
  });

  it('Pfeil rechts/links verschiebt einen Platz vor und zurück', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    await key(tileEl('uhr'), 'ArrowRight');
    expect(stored().slice(0, 2)).toEqual(['wetter', 'uhr']);
    await key(tileEl('uhr'), 'ArrowLeft');
    expect(stored().slice(0, 2)).toEqual(['uhr', 'wetter']);
  });

  it('Bild ↓ trägt die Kachel auf die nächste SEITE (Andis „über die Seiten verschieben" ohne Finger)', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    await key(tileEl('uhr'), 'PageDown');
    // Sechs Kacheln je Seite ⇒ die Uhr landet hinten.
    expect(stored()[6]).toBe('uhr');
  });

  it('Entf schaltet KEIN Widget mehr aus — der Weg dorthin liegt in den Einstellungen', async () => {
    // Die Taste legte die Kachel ins Fach, also schaltete sie ihr Widget aus.
    // Mit dem Fach fällt sie mit: eine Taste, die ein Widget verschwinden
    // lässt, während der Weg zurück in einem anderen Bildschirm liegt, ist auf
    // einem Wandgerät kein Kürzel, sondern eine Falle.
    await mount(sevenTiles());
    await enterEdit('uhr');
    await key(tileEl('news'), 'Delete');
    expect(localStorage.getItem('hoshi.homeTiles.currentAffairs')).not.toBe('false');
    expect(container.querySelector('[data-tile="news"]')).not.toBeNull();
  });

  it('Eingabe öffnet und schließt den Stufen-Wähler', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    await key(window, 'Escape');
    expect(sizer()).toBeNull();
    await key(tileEl('wetter'), 'Enter');
    expect(sizer()?.getAttribute('aria-label')).toContain('Wetter');
    await key(tileEl('wetter'), 'Enter');
    expect(sizer()).toBeNull();
  });

  it('jeder Zug wird per `aria-live` bestätigt — mit Platz UND Seite', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    const live = container.querySelector('.idle__editsr [role="status"]') as HTMLElement;
    expect(live.getAttribute('aria-live')).toBe('polite');
    await key(tileEl('uhr'), 'ArrowRight');
    expect(announced()).toMatch(/Uhr.*2.*7.*Seite/);
  });

  it('nach einem Zug bleibt der FOKUS auf der Kachel — sonst wäre nach einem Schritt Schluss', async () => {
    await mount(sevenTiles());
    await enterEdit('uhr');
    tileEl('uhr').focus();
    await key(tileEl('uhr'), 'ArrowRight');
    expect(document.activeElement?.getAttribute('data-tile')).toBe('uhr');
    await key(document.activeElement as HTMLElement, 'ArrowRight');
    expect(stored().slice(0, 3)).toEqual(['wetter', 'laeuft', 'uhr']);
  });

  /* ── Drehen während des Edits ──────────────────────────────────────────── */

  it('ROTATION während des Edits: der Modus bleibt, die Stufen geben nach, der Speicher nicht', async () => {
    saveHomeLayout({ version: 1, order: ALL.map((id) => ({ id, size: 'L' as HomeTileSize })) });
    await mount(ALL.map((id) => tile(id, 'L')));
    await enterEdit('uhr');
    expect(tileEl('uhr').getAttribute('data-size')).toBe('L');
    // Hochkant: eine Spalte ⇒ alles degradiert auf S (§2.3) …
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
      this: HTMLElement,
    ) {
      return rect(0, 0, 400, 300);
    });
    await act(async () => {
      window.dispatchEvent(new Event('resize'));
      root!.render(<HomeStage tiles={ALL.map((id) => tile(id, 'L'))} />);
    });
    expect(editAn()).not.toBeNull();
    // … und der gespeicherte Wert bleibt L.
    expect(
      JSON.parse(localStorage.getItem(HOME_LAYOUT_STORAGE_KEY) as string).order[0].size,
    ).toBe('L');
  });
});

/* ═══ 3 · Die Verträge in CSS und Sprache ══════════════════════════════════ */

describe('Edit-Modus — die CSS-Zusagen (die jsdom nicht rechnen kann)', () => {
  const CSS = readFileSync('src/index.css', 'utf8');
  const ruleOf = (selector: string): string => {
    const m = new RegExp(`\\n${selector.replace(/[.[\]='*]/g, (c) => `\\${c}`)}\\s*\\{([^}]*)\\}`).exec(CSS);
    expect(m, `Regel ${selector} fehlt`).not.toBeNull();
    return m![1];
  };

  it('das Wackeln ist ±0,45° und läuft 2,4 s', () => {
    expect(ruleOf(".idle__tile[data-edit='true']")).toMatch(/animation:\s*idle-wiggle\s+2\.4s/);
    const frames = /@keyframes idle-wiggle\s*\{([\s\S]*?)\n\}/.exec(CSS);
    expect(frames).not.toBeNull();
    expect(frames![1]).toContain('rotate(-0.45deg)');
    expect(frames![1]).toContain('rotate(0.45deg)');
  });

  it('bewegt wird NUR `transform` — kein left/top/width im Wackeln', () => {
    const frames = /@keyframes idle-wiggle\s*\{([\s\S]*?)\n\}/.exec(CSS);
    expect(frames![1]).not.toMatch(/\b(left|top|width|height|margin)\s*:/);
  });

  it('`prefers-reduced-motion` ⇒ KEIN Wackeln, sondern ein gestrichelter Rahmen (§4.2 wörtlich)', () => {
    const blocks = Array.from(
      CSS.matchAll(/@media \(prefers-reduced-motion: reduce\)\s*\{([\s\S]*?)\n\}/g),
    ).map((m) => m[1]);
    const withEdit = blocks.find((b) => b.includes(".idle__tile[data-edit='true']"));
    expect(withEdit, 'reduced-motion kennt den Edit-Modus nicht').toBeTruthy();
    const rule = /\.idle__tile\[data-edit='true'\]\s*\{([^}]*)\}/.exec(withEdit as string);
    expect(rule![1]).toMatch(/animation:\s*none/);
    expect(rule![1]).toMatch(/outline:\s*2px\s+dashed/);
  });

  it('W7-D: der +/−-Knopf ist ein 44-px-Fingerziel — die OPTIK ist ruhiger, die Fläche nicht', () => {
    const btn = ruleOf('.idle__sizerbtn');
    expect(btn).toMatch(/width:\s*44px/);
    expect(btn).toMatch(/height:\s*44px/);
    // Andi 21.08.: „einfach durch dezentere Icons". Das Zeichen ist kleiner
    // als der Fließtext der Leiste war (24 px) — die Fläche bleibt 44.
    const size = /font-size:\s*(\d+)px/.exec(btn);
    expect(Number(size?.[1])).toBeLessThanOrEqual(18);
    // Und die Griffecke gibt es nicht mehr: EIN Weg zur Größe, nicht zwei.
    expect(CSS).not.toContain('.idle__grip');
  });

  it('die Kachel-Kinder sind im Edit auch per CSS taub (Gürtel neben `inert`)', () => {
    expect(CSS).toMatch(/\.idle__pages\[data-edit='true'\]\s+\.idle__tile\s*>\s*\*\s*\{[^}]*pointer-events:\s*none/);
  });

  it('W6: die Kopf-Mechanik ist ZWEIZEILIG — Gruß oben, Bühne darunter, und sonst nichts', () => {
    // Aus `auto auto 1fr auto` sind über drei Bestellungen zwei Zeilen
    // geworden: die Chips zogen in die Fußleiste (W5), der Wecker auf die
    // Bühne (W6). Beide tragen darum KEINE `grid-row` mehr — eine
    // Zeilen-Zuweisung an ein Element, das in diesem Grid nicht mehr liegt,
    // ist eine Behauptung, keine Regel.
    expect(ruleOf('.idle__head')).toMatch(/grid-row:\s*1/);
    expect(ruleOf('.idle__stage')).toMatch(/grid-row:\s*2/);
    expect(ruleOf('.idle__alarm')).not.toMatch(/grid-row/);
    expect(ruleOf('.idle__chips')).not.toMatch(/grid-row/);
  });

  /**
   * **Die Leiste ist weg — und zwar aus dem Blatt, nicht nur aus dem Bild**
   * (Andi 23.08.: „nimm die UI oben, wenn man etwas bearbeitet raus").
   *
   * Hier stand bis dahin die Herleitung ihrer 62 px Mindesthöhe (Andi 20.08.:
   * „Die Höhe ist unterschiedlich, wenn die viel zu lange Hilfe angezeigt
   * wird"). Der Riegel ist jetzt der umgekehrte: KEINE dieser Regeln darf
   * zurückkommen, sonst nähme sie der `1fr`-Bühne wieder Höhe weg.
   */
  it('23.08.: kein Selektor der Edit-Leiste steht mehr im Blatt', () => {
    for (const tot of [
      '.idle__editbar',
      '.idle__editdone',
      '.idle__editreset',
      '.idle__editlayer',
      '.idle__edithint',
      '.idle__edittexts',
      '--home-editband-top',
    ]) {
      // Kommentare raus: die Regeln sind gefallen, ihre Geschichte steht als
      // Fließtext daneben und darf nicht als Regel zählen (dasselbe `strip`
      // wie in `onewindow.test.ts`).
      expect(CSS.replace(/\/\*[\s\S]*?\*\//g, ''), `${tot} ist zurück`).not.toContain(tot);
    }
    // Und die Bearbeitungsfläche bekommt keinen Rand mehr von oben aufgedrückt:
    // die Seite im Edit ist Pixel für Pixel die Seite außerhalb.
    expect(CSS.replace(/\/\*[\s\S]*?\*\//g, '')).not.toMatch(
      /\.idle__pages\[data-edit='true'\]\s+\.idle__page\s*\{/,
    );
  });

  it('die `aria-live`-Region ist versteckt, aber NICHT `display:none` (sonst liest sie niemand)', () => {
    // Aus drei Kopien derselben Versteck-Regel ist EINE geworden, und seit dem
    // 23.08. auch nur noch EIN Klassenname: `.idle__editannounce` und
    // `.idle__editkeys` sind mit der Leiste gefallen und benutzen das
    // vorhandene `.idle__sronly`. Der Riegel prüft die geteilte Regel — und
    // dass niemand ihr still eine `display:none`-Fassung untergeschoben hat.
    const shared = /\.idle__sronly,\s*\n\.idle__editsr,\s*\n\.idle__sizerstep\s*\{([^}]*)\}/.exec(CSS);
    expect(shared, 'die geteilte sr-only-Regel fehlt').not.toBeNull();
    const rule = shared![1];
    expect(rule).not.toMatch(/display:\s*none/);
    expect(rule).not.toMatch(/visibility:\s*hidden/);
    expect(rule).toMatch(/clip-path/);
  });
});

describe('Edit-Modus — jeder neue Text steht in allen fünf Sprachen (Codex §5)', () => {
  it('die Edit-Strings sind vollständig, nicht-leer und nirgends deutsch durchgereicht', () => {
    for (const lang of SUPPORTED_UI_LANGUAGES) {
      const e = CATALOGS[lang].idleFace.stage.edit;
      for (const k of ['title', 'tileRole', 'keyHint'] as const) {
        expect(e[k], `${lang}.${k}`).toBeTruthy();
      }
      expect(e.tileAria('X', 2, 7)).toContain('X');
      expect(e.moved('X', 2, 7, 1, 3)).toContain('X');
      // Die Fach-Strings sind mit dem Fach gefallen (22.08.), die
      // Leisten-Strings mit der Leiste (23.08.) — kein Katalog trägt sie noch
      // als Leiche mit. `reset`/`resetArmed`/`resetDone` wären dabei die
      // gefährlichsten: es gibt sie WEITERHIN, nur in den Einstellungen
      // (`settings.homeTilesLayoutReset*`). Zwei Fassungen desselben Satzes
      // sind der sichere Weg zu zwei Wortlauten.
      for (const tot of [
        'hint', 'trayLabel', 'trayEmpty', 'trayAdd', 'removed', 'added',
        'done', 'reset', 'resetArmed', 'resetDone',
      ]) {
        expect(e, `${lang}.${tot} lebt noch`).not.toHaveProperty(tot);
      }
      expect(CATALOGS[lang].idleFace.uhr.name).toBeTruthy();
      expect(CATALOGS[lang].settings.homeTilesLayoutArrange).toBeTruthy();
      // Der EINE Ort, an dem „Zurücksetzen" jetzt wohnt — in jeder Sprache.
      expect(CATALOGS[lang].settings.homeTilesLayoutReset, `${lang}.homeTilesLayoutReset`).toBeTruthy();
      expect(CATALOGS[lang].settings.homeTilesLayoutResetArmed).toBeTruthy();
      expect(CATALOGS[lang].settings.homeTilesLayoutResetDone).toBeTruthy();
    }
    // Die fünf „Widgets anordnen" sind wirklich fünf verschiedene Sätze — kein
    // Katalog hat den deutschen Text nur kopiert.
    const titel = SUPPORTED_UI_LANGUAGES.map((l) => CATALOGS[l].idleFace.stage.edit.title);
    expect(new Set(titel).size).toBeGreaterThan(3);
  });
});

/* ═══ 4 · Der Still-Schalter für sich (Andi 23.08.) ═════════════════════════ */

describe('sceneMotion — wer anhält, gibt auch wieder frei', () => {
  beforeEach(() => resetSceneMotion());
  afterEach(() => resetSceneMotion());

  it('zählt Halter, statt zu schalten — zwei Bühnen, eine Szene', () => {
    const a = holdSceneMotion();
    const b = holdSceneMotion();
    expect(document.documentElement.getAttribute(SCENE_MOTION_ATTR)).toBe('still');
    // Der erste gibt frei — der zweite ordnet noch an, die Szene bleibt stehen.
    a();
    expect(document.documentElement.getAttribute(SCENE_MOTION_ATTR)).toBe('still');
    b();
    expect(document.documentElement.getAttribute(SCENE_MOTION_ATTR)).toBeNull();
  });

  it('eine doppelt aufgerufene Freigabe zählt einmal — sonst risse sie fremde Halte mit', () => {
    // React fährt Effekt-Cleanups unter StrictMode doppelt; ohne diese Zusage
    // stünde der Zähler danach im Minus und der nächste Halt käme nie an.
    const a = holdSceneMotion();
    const b = holdSceneMotion();
    a();
    a();
    expect(document.documentElement.getAttribute(SCENE_MOTION_ATTR)).toBe('still');
    b();
    expect(document.documentElement.getAttribute(SCENE_MOTION_ATTR)).toBeNull();
  });
});
